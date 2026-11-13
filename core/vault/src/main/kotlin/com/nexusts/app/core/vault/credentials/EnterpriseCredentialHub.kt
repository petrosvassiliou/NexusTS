package com.nexusts.app.core.vault.credentials

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import com.nexusts.app.core.vault.crypto.NativeVaultCryptoBridge
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.VaultCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.IdentityHashMap
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.nexusts.app.core.vault.importer.SecureXmlDomParser

data class ResolvedSessionSecret(
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val privateKeyPem: String = "",
    val keyPassphrase: String = "",
    val totpCode: String? = null,
    val fido2AssertionJson: String? = null
)

@Serializable
data class GeneratedSshKeyInfo(
    val key_id: String,
    val public_key_openssh: String,
    val private_key_openssh_pem: String,
    val sha256_fingerprint: String
)

/**
 * Enterprise Credential Hub integrating:
 * 1. Built-in Zero-Knowledge `.nxts` Credentials & OpenSSH Ed25519 Key Generator
 * 2. 1Password Connect Server REST API (`op://vault/item/field`)
 * 3. Bitwarden Vault CLI/Serve REST API & Android CredentialManager Autofill
 * 4. Native KeePass `.kdbx` 4.x (Argon2id + AES-256-CBC + ChaCha20 Inner Stream) Unsealer
 * 5. Passkeys & Hardware Security Keys (FIDO2 / WebAuthn YubiKey via AndroidX CredentialManager)
 */
class EnterpriseCredentialHub(
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Generates a fresh OpenSSH Ed25519 keypair using Rust `nexusts-crypto` (or RFC-compliant `openssh-key-v1\0` BouncyCastle fallback).
     */
    fun generateEd25519SshKeyPair(comment: String): GeneratedSshKeyInfo {
        if (NativeVaultCryptoBridge.isNativeLoaded) {
            val payload = NativeVaultCryptoBridge.nativeGenerateEd25519KeypairJson(comment)
            return json.decodeFromString(GeneratedSshKeyInfo.serializer(), payload)
        }
        val random = SecureRandom()
        val privParams = Ed25519PrivateKeyParameters(random)
        val seedBytes = privParams.encoded
        val pubBytes = privParams.generatePublicKey().encoded
        val keyTypeBytes = "ssh-ed25519".toByteArray(Charsets.US_ASCII)

        val wirePub = ByteArrayOutputStream(4 + keyTypeBytes.size + 4 + pubBytes.size).apply {
            writeSshString(keyTypeBytes)
            writeSshString(pubBytes)
        }.toByteArray()

        val pubB64 = Base64.getEncoder().encodeToString(wirePub)
        val sha256B64 = Base64.getEncoder()
            .withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(wirePub))
        val fingerprint = "SHA256:$sha256B64"
        val publicOpenSsh = if (comment.isBlank()) "ssh-ed25519 $pubB64" else "ssh-ed25519 $pubB64 $comment"

        // Build standard OpenSSH PROTOCOL.key ("openssh-key-v1\0") unencrypted private key structure
        val privateSection = ByteArrayOutputStream().apply {
            val checkInt = random.nextInt()
            writeU32Be(checkInt)
            writeU32Be(checkInt)
            writeSshString(keyTypeBytes)
            writeSshString(pubBytes)
            writeSshString(seedBytes + pubBytes)
            writeSshString(comment.toByteArray(Charsets.UTF_8))
            var padByte = 1
            while (size() % 8 != 0) {
                write(padByte and 0xFF)
                padByte++
            }
        }.toByteArray()

        val noneBytes = "none".toByteArray(Charsets.US_ASCII)
        val opensshKeyBlob = ByteArrayOutputStream().apply {
            write("openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII))
            writeSshString(noneBytes) // ciphername
            writeSshString(noneBytes) // kdfname
            writeSshString(ByteArray(0)) // kdfoptions
            writeU32Be(1) // number of keys
            writeSshString(wirePub)
            writeSshString(privateSection)
        }.toByteArray()

        val wrappedB64 = Base64.getEncoder()
            .encodeToString(opensshKeyBlob)
            .chunked(70)
            .joinToString("\n")
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$wrappedB64\n-----END OPENSSH PRIVATE KEY-----\n"

        return GeneratedSshKeyInfo(
            key_id = fingerprint,
            public_key_openssh = publicOpenSsh,
            private_key_openssh_pem = pem,
            sha256_fingerprint = fingerprint
        )
    }

    private fun ByteArrayOutputStream.writeU32Be(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeSshString(bytes: ByteArray) {
        writeU32Be(bytes.size)
        write(bytes)
    }

    /**
     * Computes RFC 6238 6-digit TOTP code for MFA-protected SSH/Web/Gateway sessions.
     */
    fun generateCurrentTotpCode(base32Secret: String, epochSeconds: Long = System.currentTimeMillis() / 1000L): String {
        val keyBytes = decodeBase32(base32Secret)
        val counter = epochSeconds / 30L
        val msg = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
        val hash = mac.doFinal(msg)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        val otp = binary % 1_000_000
        return otp.toString().padStart(6, '0')
    }

    /**
     * Resolves any [VaultCredential] into runtime [ResolvedSessionSecret] across internal and external vaults.
     */
    suspend fun resolveCredential(
        credential: VaultCredential,
        activityContext: Context? = null,
        onePasswordConnectUrl: String? = null,
        onePasswordConnectToken: String? = null,
        bitwardenServeUrl: String? = null,
        kdbxBytesProvider: (suspend (String) -> Pair<ByteArray, CharArray>)? = null,
        relyingPartyChallengeJson: String? = null
    ): ResolvedSessionSecret = withContext(Dispatchers.IO) {
        val totp = credential.totpSecretBase32.takeIf { it.isNotBlank() }?.let { generateCurrentTotpCode(it) }

        when (credential.sourceKind) {
            CredentialSourceKind.VAULT_INTERNAL, CredentialSourceKind.SSH_KEY_PAIR -> {
                ResolvedSessionSecret(
                    username = credential.username,
                    password = credential.secretPassword,
                    domain = credential.domain,
                    privateKeyPem = credential.privateKeyPem,
                    keyPassphrase = credential.keyPassphrase,
                    totpCode = totp
                )
            }

            CredentialSourceKind.ONEPASSWORD_CONNECT -> {
                resolveFromOnePasswordConnect(
                    opUri = credential.externalReferenceUri,
                    connectBaseUrl = requireNotNull(onePasswordConnectUrl) { "1Password Connect URL required" },
                    connectBearerToken = requireNotNull(onePasswordConnectToken) { "1Password Connect Token required" }
                )
            }

            CredentialSourceKind.BITWARDEN_VAULT -> {
                if (!bitwardenServeUrl.isNullOrBlank()) {
                    resolveFromBitwardenServe(
                        itemId = credential.externalReferenceUri,
                        serveBaseUrl = bitwardenServeUrl
                    )
                } else if (activityContext != null) {
                    resolveFromAndroidCredentialManagerPassword(activityContext)
                } else {
                    error("Bitwarden requires either local API server URL or Android CredentialManager Activity context")
                }
            }

            CredentialSourceKind.KEEPASS_KDBX -> {
                val (kdbxBytes, masterPass) = requireNotNull(kdbxBytesProvider) {
                    "KeePass .kdbx file provider required"
                }.invoke(credential.externalReferenceUri)
                val entries = KeePassKdbx4Reader.readEntries(kdbxBytes, masterPass)
                val normalizedRef = credential.externalReferenceUri.replace("-", "").trim()
                val matched = entries.firstOrNull {
                    it.uuidHex.equals(normalizedRef, ignoreCase = true) ||
                        it.uuidHex.equals(credential.externalReferenceUri, ignoreCase = true) ||
                        it.title.equals(credential.name, ignoreCase = true)
                } ?: error("Entry '${credential.name}' not found in .kdbx database")
                ResolvedSessionSecret(
                    username = matched.username,
                    password = matched.password,
                    privateKeyPem = matched.sshPrivateKeyPem
                )
            }

            CredentialSourceKind.HARDWARE_FIDO2_PASSKEY -> {
                val ctx = requireNotNull(activityContext) {
                    "Activity context required for FIDO2 / Hardware Security Key ceremony"
                }
                val requestJson = relyingPartyChallengeJson ?: buildDefaultFido2AssertionRequest(
                    credential.fido2CredentialIdBase64
                )
                val assertion = requestHardwarePasskeyAssertion(ctx, requestJson)
                ResolvedSessionSecret(
                    username = credential.username,
                    domain = credential.domain,
                    fido2AssertionJson = assertion
                )
            }
        }
    }

    private fun resolveFromOnePasswordConnect(
        opUri: String,
        connectBaseUrl: String,
        connectBearerToken: String
    ): ResolvedSessionSecret {
        // Format: op://<vaultUuid>/<itemUuid>
        val cleaned = opUri.removePrefix("op://").split("/")
        require(cleaned.size >= 2) { "Expected 1Password reference op://<vaultId>/<itemId>" }
        val vaultId = cleaned[0]
        val itemId = cleaned[1]
        val endpoint = "${connectBaseUrl.trimEnd('/')}/v1/vaults/$vaultId/items/$itemId"

        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $connectBearerToken")
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("1Password Connect HTTP ${resp.code}")
            val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            val fields = root["fields"]?.jsonArray.orEmpty()
            var username = ""
            var password = ""
            var sshKey = ""
            fields.forEach { elem ->
                val obj = elem.jsonObject
                val purpose = obj["purpose"]?.jsonPrimitive?.content.orEmpty()
                val label = obj["label"]?.jsonPrimitive?.content.orEmpty().lowercase()
                val value = obj["value"]?.jsonPrimitive?.content.orEmpty()
                when {
                    purpose == "USERNAME" || label == "username" -> username = value
                    purpose == "PASSWORD" || label == "password" -> password = value
                    label.contains("private key") || value.contains("BEGIN OPENSSH PRIVATE KEY") -> sshKey = value
                }
            }
            return ResolvedSessionSecret(username = username, password = password, privateKeyPem = sshKey)
        }
    }

    private fun resolveFromBitwardenServe(
        itemId: String,
        serveBaseUrl: String
    ): ResolvedSessionSecret {
        val endpoint = "${serveBaseUrl.trimEnd('/')}/object/item/$itemId"
        val req = Request.Builder().url(endpoint).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Bitwarden API HTTP ${resp.code}")
            val data = json.parseToJsonElement(resp.body?.string().orEmpty())
                .jsonObject["data"]?.jsonObject ?: error("Missing Bitwarden item payload")
            val login = data["login"]?.jsonObject
            return ResolvedSessionSecret(
                username = login?.get("username")?.jsonPrimitive?.content.orEmpty(),
                password = login?.get("password")?.jsonPrimitive?.content.orEmpty()
            )
        }
    }

    private suspend fun resolveFromAndroidCredentialManagerPassword(activityContext: Context): ResolvedSessionSecret {
        val credentialManager = CredentialManager.create(activityContext)
        val request = GetCredentialRequest(listOf(GetPasswordOption()))
        val result = withContext(Dispatchers.Main) {
            credentialManager.getCredential(activityContext, request)
        }
        val cred = result.credential as? PasswordCredential
            ?: error("Selected credential is not a password credential")
        return ResolvedSessionSecret(username = cred.id, password = cred.password)
    }

    private suspend fun requestHardwarePasskeyAssertion(
        activityContext: Context,
        requestJson: String
    ): String {
        val credentialManager = CredentialManager.create(activityContext)
        val option = GetPublicKeyCredentialOption(requestJson = requestJson)
        val result = withContext(Dispatchers.Main) {
            credentialManager.getCredential(activityContext, GetCredentialRequest(listOf(option)))
        }
        val pkCred = result.credential as? PublicKeyCredential
            ?: error("Hardware FIDO2 token did not return a PublicKeyCredential")
        return pkCred.authenticationResponseJson
    }

    private fun buildDefaultFido2AssertionRequest(credentialIdB64: String): String {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val challengeB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)
        val allowList = if (credentialIdB64.isNotBlank()) {
            """[{"type":"public-key","id":"$credentialIdB64","transports":["usb","nfc","internal"]}]"""
        } else {
            "[]"
        }
        return """
            {
              "challenge": "$challengeB64Url",
              "rpId": "nexusts.app",
              "userVerification": "preferred",
              "timeout": 60000,
              "allowCredentials": $allowList
            }
        """.trimIndent()
    }

    private fun decodeBase32(raw: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = raw.uppercase().replace("=", "").replace(" ", "")
        val out = ArrayList<Byte>(clean.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val idx = alphabet.indexOf(ch)
            if (idx < 0) continue
            buffer = (buffer shl 5) or idx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}

/**
 * Native KeePass `.kdbx` 4.0 / 4.1 Binary Container Reader (Argon2id + AES-256-CBC + ChaCha20 Protected Stream).
 */
object KeePassKdbx4Reader {
    private const val KDBX_SIG_1 = 0x9AA2D903.toInt()
    private const val KDBX_SIG_2 = 0xB54BFB67.toInt()

    data class KdbxEntry(
        val uuidHex: String,
        val title: String,
        val username: String,
        val password: String,
        val sshPrivateKeyPem: String = "",
        val url: String = "",
        val notes: String = "",
        val totpSecret: String = "",
        val groupPath: List<String> = emptyList()
    )

    fun readEntries(kdbxBytes: ByteArray, masterPassword: CharArray): List<KdbxEntry> {
        val buf = ByteBuffer.wrap(kdbxBytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.remaining() >= 12 && buf.int == KDBX_SIG_1 && buf.int == KDBX_SIG_2) {
            "Invalid .kdbx file signature"
        }
        val versionMinor = buf.short.toInt() and 0xFFFF
        val versionMajor = buf.short.toInt() and 0xFFFF
        require(versionMajor == 4) { "Expected KDBX 4.x database (got $versionMajor.$versionMinor)" }

        var encryptionIv = ByteArray(0)
        var kdfParametersBlob = ByteArray(0)
        var compressionFlags = 1

        while (true) {
            val fieldId = buf.get().toInt() and 0xFF
            val fieldSize = buf.int
            val fieldData = ByteArray(fieldSize).also { buf.get(it) }
            when (fieldId) {
                0 -> break
                3 -> compressionFlags = ByteBuffer.wrap(fieldData).order(ByteOrder.LITTLE_ENDIAN).int
                7 -> encryptionIv = fieldData
                11 -> kdfParametersBlob = fieldData
            }
        }

        val headerEndOffset = buf.position()
        // Read 32-byte header SHA-256 + 32-byte header HMAC-SHA-256
        val headerSha256 = ByteArray(32).also { buf.get(it) }
        val headerHmac = ByteArray(32).also { buf.get(it) }

        val computedHeaderSha = MessageDigest.getInstance("SHA-256")
            .digest(kdbxBytes.copyOfRange(0, headerEndOffset))
        require(computedHeaderSha.contentEquals(headerSha256)) { "Corrupted KDBX4 outer header digest" }
        require(headerHmac.isNotEmpty() && encryptionIv.isNotEmpty()) { "Incomplete KDBX4 encryption header" }

        val kdfMap = parseVariantDictionary(kdfParametersBlob)
        val salt = kdfMap["S"] as? ByteArray ?: error("Missing KDBX4 Argon2 salt")
        val memoryBytes = (kdfMap["M"] as? Long) ?: (64L * 1024L * 1024L)
        val iterations = ((kdfMap["I"] as? Long) ?: 2L).toInt()
        val parallelism = ((kdfMap["P"] as? Int) ?: 2)

        val passSha = MessageDigest.getInstance("SHA-256")
            .digest(String(masterPassword).toByteArray(Charsets.UTF_8))
        val compositeKey = MessageDigest.getInstance("SHA-256").digest(passSha)

        val transformedKey = ByteArray(32)
        val argon = Argon2BytesGenerator()
        argon.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB((memoryBytes / 1024L).toInt())
                .withIterations(iterations)
                .withParallelism(parallelism)
                .build()
        )
        argon.generateBytes(compositeKey, transformedKey)

        val masterKey = MessageDigest.getInstance("SHA-256").apply {
            update(salt)
            update(transformedKey)
        }.digest()

        // Reassemble HMAC-block stream payload
        val payloadEncrypted = readHmacBlockStream(buf)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(masterKey, "AES"), IvParameterSpec(encryptionIv))
        val decryptedPayload = cipher.doFinal(payloadEncrypted)

        val decompressed = if (compressionFlags == 1) {
            GZIPInputStream(ByteArrayInputStream(decryptedPayload)).use { it.readBytes() }
        } else {
            decryptedPayload
        }

        // Parse KDBX4 inner header for ChaCha20 protected value key
        val innerBuf = ByteBuffer.wrap(decompressed).order(ByteOrder.LITTLE_ENDIAN)
        var innerStreamKey = ByteArray(0)
        while (true) {
            val innerId = innerBuf.get().toInt() and 0xFF
            val innerLen = innerBuf.int
            val innerBytes = ByteArray(innerLen).also { innerBuf.get(it) }
            if (innerId == 0) break
            if (innerId == 2) innerStreamKey = innerBytes
        }
        val xmlBytes = ByteArray(innerBuf.remaining()).also { innerBuf.get(it) }
        return parseKeePassXml(xmlBytes, innerStreamKey)
    }

    private fun readHmacBlockStream(buf: ByteBuffer): ByteArray {
        val out = ByteArrayOutputStream()
        while (buf.remaining() >= 36) {
            val blockHmac = ByteArray(32).also { buf.get(it) }
            val blockSize = buf.int
            if (blockSize <= 0 || blockHmac.isEmpty()) break
            val blockBytes = ByteArray(blockSize).also { buf.get(it) }
            out.write(blockBytes)
        }
        return out.toByteArray()
    }

    private fun parseVariantDictionary(blob: ByteArray): Map<String, Any> {
        if (blob.size < 4) return emptyMap()
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        buf.short // version
        val map = mutableMapOf<String, Any>()
        while (buf.hasRemaining()) {
            val type = buf.get().toInt() and 0xFF
            if (type == 0) break
            val nameLen = buf.int
            val name = ByteArray(nameLen).also { buf.get(it) }.toString(Charsets.UTF_8)
            val valLen = buf.int
            val valBytes = ByteArray(valLen).also { buf.get(it) }
            val value: Any = when (type) {
                0x04, 0x0C -> ByteBuffer.wrap(valBytes).order(ByteOrder.LITTLE_ENDIAN).int
                0x05, 0x0D -> ByteBuffer.wrap(valBytes).order(ByteOrder.LITTLE_ENDIAN).long
                0x42 -> valBytes
                else -> valBytes
            }
            map[name] = value
        }
        return map
    }

    internal fun parseKeePassXml(xmlBytes: ByteArray, innerStreamKey: ByteArray): List<KdbxEntry> {
        val sha512 = MessageDigest.getInstance("SHA-512").digest(innerStreamKey)
        val chacha = ChaCha7539Engine()
        if (innerStreamKey.isNotEmpty()) {
            chacha.init(
                false,
                ParametersWithIV(
                    KeyParameter(sha512.copyOfRange(0, 32)),
                    sha512.copyOfRange(32, 44)
                )
            )
        }

        val doc = SecureXmlDomParser.parseXmlBytes(xmlBytes, normalizeDocument = false)

        // Pass 1: Advance ChaCha7539Engine across EVERY Protected="True" <Value> node in exact XML document order
        // (including <History> entries and Recycle Bin groups) so the stream cipher never desynchronizes!
        val decryptedValues = IdentityHashMap<Element, String>()
        if (innerStreamKey.isNotEmpty()) {
            val allValues = doc.getElementsByTagName("Value")
            for (vIdx in 0 until allValues.length) {
                val valElem = allValues.item(vIdx) as? Element ?: continue
                if (valElem.getAttribute("Protected").equals("True", ignoreCase = true)) {
                    val rawB64 = valElem.textContent?.trim().orEmpty()
                    if (rawB64.isNotEmpty()) {
                        val cipherBytes = Base64.getDecoder().decode(rawB64)
                        val plainBytes = ByteArray(cipherBytes.size)
                        if (cipherBytes.isNotEmpty()) {
                            chacha.processBytes(cipherBytes, 0, cipherBytes.size, plainBytes, 0)
                        }
                        decryptedValues[valElem] = plainBytes.toString(Charsets.UTF_8)
                    } else {
                        decryptedValues[valElem] = ""
                    }
                }
            }
        }

        // Pass 2: Materialize active <Entry> nodes (skipping <History> children and reading ONLY direct <String> children)
        val entryNodes = doc.getElementsByTagName("Entry")
        val entries = mutableListOf<KdbxEntry>()

        for (i in 0 until entryNodes.length) {
            val entryElem = entryNodes.item(i) as Element
            if ((entryElem.parentNode as? Element)?.tagName == "History") continue

            val uuidRaw = entryElem.directChildElement("UUID")?.textContent.orEmpty()
            val uuidHex = decodeKdbxUuidToHex(uuidRaw)

            var title = ""
            var user = ""
            var pass = ""
            var sshPem = ""
            var url = ""
            var notes = ""
            var totp = ""

            val childNodes = entryElem.childNodes
            for (cIdx in 0 until childNodes.length) {
                val child = childNodes.item(cIdx)
                if (child.nodeType != Node.ELEMENT_NODE) continue
                val strElem = child as Element
                if (strElem.tagName != "String") continue

                val key = strElem.directChildElement("Key")?.textContent.orEmpty()
                val valElem = strElem.directChildElement("Value") ?: continue
                val decoded = decryptedValues[valElem] ?: valElem.textContent.orEmpty()

                when (key) {
                    "Title" -> title = decoded
                    "UserName" -> user = decoded
                    "Password" -> pass = decoded
                    "URL" -> url = decoded
                    "Notes" -> notes = decoded
                    "otp", "TOTP Seed", "TimeOtp-Secret-Base32" -> if (totp.isBlank()) totp = decoded
                    "SSHKey", "PrivateKey" -> sshPem = decoded
                    else -> if (sshPem.isBlank() &&
                        (decoded.contains("BEGIN OPENSSH PRIVATE KEY") || decoded.contains("BEGIN RSA PRIVATE KEY"))
                    ) {
                        sshPem = decoded
                    }
                }
            }

            val groupSegments = mutableListOf<String>()
            var parentCursor = entryElem.parentNode
            while (parentCursor is Element) {
                if (parentCursor.tagName == "Group") {
                    val gName = parentCursor.directChildElement("Name")?.textContent?.trim().orEmpty()
                    val isRootGroup = (parentCursor.parentNode as? Element)?.tagName == "Root"
                    if (!isRootGroup && gName.isNotEmpty()) {
                        groupSegments.add(0, gName)
                    }
                }
                parentCursor = parentCursor.parentNode
            }

            entries += KdbxEntry(
                uuidHex = uuidHex,
                title = title,
                username = user,
                password = pass,
                sshPrivateKeyPem = sshPem,
                url = url,
                notes = notes,
                totpSecret = totp,
                groupPath = groupSegments
            )
        }
        return entries
    }

    internal fun decodeKdbxUuidToHex(uuidRaw: String): String {
        val trimmed = uuidRaw.trim()
        if (trimmed.isEmpty()) return ""
        val decoded = runCatching { Base64.getDecoder().decode(trimmed) }.getOrNull()
        if (decoded != null && decoded.size == 16) {
            return decoded.joinToString("") { "%02x".format(it) }
        }
        return trimmed.replace("-", "").lowercase()
    }

    private fun Element.directChildElement(tagName: String): Element? {
        val children = childNodes
        for (idx in 0 until children.length) {
            val child = children.item(idx)
            if (child.nodeType == Node.ELEMENT_NODE && (child as Element).tagName == tagName) {
                return child
            }
        }
        return null
    }
}
