package com.nexusts.app.core.vault.importer

import com.nexusts.app.core.vault.credentials.KeePassKdbx4Reader
import com.nexusts.app.core.vault.model.CommandTask
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.core.vault.model.VaultTodoItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

enum class DetectedExternalVaultFormat {
    NXTS_ENCRYPTED_BINARY,
    ROYAL_TS_RTSZ_OR_RTSX,
    ROYAL_TS_ENCRYPTED_ARCHIVE,
    ROYAL_JSON,
    KEEPASS_KDBX_BINARY,
    KEEPASS_XML_2X,
    BITWARDEN_JSON,
    ONEPASSWORD_1PUX_OR_JSON,
    CSV_INVENTORY_OR_PASSWORDS,
    UNKNOWN
}

/**
 * Enterprise Multi-Format Password Manager & Inventory Importer (`v1.3`).
 * Supports native zero-loss import of:
 * - **Royal TS / Royal TSX (`.rtsz`, `.rtsx`, `.rjson`)**: GZIP-compressed `.rtsz`, ZIP & WinZip-AES/ZipCrypto
 *   Lockdown `.rtsz`, UTF-8 / UTF-16 XML `.rtsx`, and RoyalJSON (`.rjson`) documents.
 * - **KeePass `.kdbx` 4.x Binary & KeePass 2.x XML**: Nested `<Group>` folders, `<Entry>` credentials,
 *   RFC 6238 `otpauth://` TOTP secrets, OpenSSH PEM keys, and protocol endpoint synthesis.
 * - **Bitwarden Vault JSON (`.json`)**: Nested `folders[]` (`"Prod/EU/Linux"`), `items[]` (Logins, TOTP, URIs, and SSH Keys),
 *   with explicit encrypted-export detection.
 * - **1Password `.1pux` ZIP archives, JSON exports & `.1pif` multi-record exports**: `accounts[].vaults[].items[]` (`export.data`) and `***`-delimited `.1pif` records.
 * - **Enterprise Inventory & Password Manager CSV (`.csv`)**: Lossless NexusTS CMDB CSV round-trips, Bitwarden CSV, 1Password CSV, and KeePass CSV.
 */
class EnterpriseVaultFormatImporter {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun detectFormat(fileName: String, rawBytes: ByteArray): DetectedExternalVaultFormat {
        if (rawBytes.isEmpty()) return DetectedExternalVaultFormat.UNKNOWN

        // 1. Encrypted NexusTS .nxts container magic ("NXTS")
        if (rawBytes.size >= 4 &&
            rawBytes[0] == 0x4E.toByte() &&
            rawBytes[1] == 0x58.toByte() &&
            rawBytes[2] == 0x54.toByte() &&
            rawBytes[3] == 0x53.toByte()
        ) {
            return DetectedExternalVaultFormat.NXTS_ENCRYPTED_BINARY
        }

        // 2. Binary KeePass .kdbx magic bytes (0x03, 0xD9, 0xA2, 0x9A)
        if (rawBytes.size >= 4 &&
            rawBytes[0] == 0x03.toByte() &&
            rawBytes[1] == 0xD9.toByte() &&
            rawBytes[2] == 0xA2.toByte() &&
            rawBytes[3] == 0x9A.toByte()
        ) {
            return DetectedExternalVaultFormat.KEEPASS_KDBX_BINARY
        }

        val lowerName = fileName.trim().lowercase()

        // 3. GZIP magic (0x1F, 0x8B) — Default Royal TS (Windows) & Royal TSX (macOS) .rtsz container!
        if (rawBytes.size >= 2 &&
            rawBytes[0] == 0x1F.toByte() &&
            rawBytes[1] == 0x8B.toByte()
        ) {
            val gunzippedHead = gunzipPreviewBytes(rawBytes, maxBytes = 8192)
            if (gunzippedHead != null && gunzippedHead.isNotEmpty()) {
                val innerText = decodeBomAwarePreview(gunzippedHead, maxBytes = gunzippedHead.size)
                if (innerText.contains("<KeePassFile", ignoreCase = true)) {
                    return DetectedExternalVaultFormat.KEEPASS_XML_2X
                }
                if (innerText.startsWith("<") ||
                    innerText.contains("RTSZDocument", ignoreCase = true) ||
                    innerText.contains("RoyalDocument", ignoreCase = true) ||
                    innerText.contains("RoyalFolder", ignoreCase = true)
                ) {
                    return DetectedExternalVaultFormat.ROYAL_TS_RTSZ_OR_RTSX
                }
            }
            if (lowerName.endsWith(".rtsz") || lowerName.endsWith(".rtsx")) {
                return DetectedExternalVaultFormat.ROYAL_TS_RTSZ_OR_RTSX
            }
            return DetectedExternalVaultFormat.UNKNOWN
        }

        // 4. ZIP archive magic (0x50, 0x4B, 0x03, 0x04)
        val isZip = rawBytes.size >= 4 &&
            rawBytes[0] == 0x50.toByte() &&
            rawBytes[1] == 0x4B.toByte() &&
            rawBytes[2] == 0x03.toByte() &&
            rawBytes[3] == 0x04.toByte()

        if (isZip) {
            if (lowerName.endsWith(".1pux") || zipContainsEntry(rawBytes, "export.data")) {
                return DetectedExternalVaultFormat.ONEPASSWORD_1PUX_OR_JSON
            }
            if (EncryptedZipExtractor.isEncryptedZip(rawBytes)) {
                return DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE
            }
            val firstEntryPreview = readFirstZipEntryPreview(rawBytes, maxBytes = 8192)
            if (firstEntryPreview != null && firstEntryPreview.isNotEmpty()) {
                val unwrapped = if (firstEntryPreview.size >= 2 &&
                    firstEntryPreview[0] == 0x1F.toByte() &&
                    firstEntryPreview[1] == 0x8B.toByte()
                ) {
                    gunzipPreviewBytes(firstEntryPreview, maxBytes = 8192) ?: firstEntryPreview
                } else {
                    firstEntryPreview
                }
                val previewText = decodeBomAwarePreview(unwrapped, maxBytes = unwrapped.size)
                if (previewText.contains("<KeePassFile", ignoreCase = true)) {
                    return DetectedExternalVaultFormat.KEEPASS_XML_2X
                }
                if (previewText.startsWith("<") ||
                    previewText.contains("RTSZDocument", ignoreCase = true) ||
                    previewText.contains("RoyalDocument", ignoreCase = true)
                ) {
                    return DetectedExternalVaultFormat.ROYAL_TS_RTSZ_OR_RTSX
                }
            }
            if (lowerName.endsWith(".rtsz") ||
                lowerName.endsWith(".rtsx") ||
                zipContainsEntry(rawBytes, ".rtsx") ||
                zipContainsEntry(rawBytes, ".rtsz") ||
                zipContainsEntry(rawBytes, ".xml")
            ) {
                return DetectedExternalVaultFormat.ROYAL_TS_RTSZ_OR_RTSX
            }
            return DetectedExternalVaultFormat.UNKNOWN
        }

        // 5. BOM-aware UTF-8 / UTF-16LE / UTF-16BE text inspection
        val headText = decodeBomAwarePreview(rawBytes, maxBytes = 8192)

        if (headText.startsWith("<")) {
            return if (headText.contains("<KeePassFile", ignoreCase = true)) {
                DetectedExternalVaultFormat.KEEPASS_XML_2X
            } else {
                DetectedExternalVaultFormat.ROYAL_TS_RTSZ_OR_RTSX
            }
        }

        if ((lowerName.endsWith(".rtsz") || lowerName.endsWith(".rtsx")) && headText.contains("<")) {
            return DetectedExternalVaultFormat.ROYAL_TS_RTSZ_OR_RTSX
        }

        if (lowerName.endsWith(".1pif") || headText.startsWith("***")) {
            return DetectedExternalVaultFormat.ONEPASSWORD_1PUX_OR_JSON
        }

        if (lowerName.endsWith(".rjson")) {
            return DetectedExternalVaultFormat.ROYAL_JSON
        }

        if (headText.startsWith("{")) {
            return when {
                headText.contains("\"Objects\"") && (
                    headText.contains("\"RemoteDesktopConnection\"", ignoreCase = true) ||
                        headText.contains("\"TerminalConnection\"", ignoreCase = true) ||
                        headText.contains("\"SSHConnection\"", ignoreCase = true) ||
                        headText.contains("\"RoyalFolder\"", ignoreCase = true) ||
                        headText.contains("\"ComputerName\"", ignoreCase = true) ||
                        headText.contains("\"Type\"", ignoreCase = true)
                    ) -> DetectedExternalVaultFormat.ROYAL_JSON

                (headText.contains("\"accounts\"") && headText.contains("\"vaults\"")) ||
                    headText.contains("\"secureContents\"") ||
                    (headText.contains("\"typeName\"") && headText.contains("\"uuid\"")) ||
                    headText.contains("\n***") ||
                    lowerName.contains("1password") ->
                    DetectedExternalVaultFormat.ONEPASSWORD_1PUX_OR_JSON

                else -> DetectedExternalVaultFormat.BITWARDEN_JSON
            }
        }

        if (looksLikePrintableText(rawBytes) && (
                lowerName.endsWith(".csv") ||
                    (headText.lines().firstOrNull()?.contains(",") == true &&
                        headText.lines().count { it.isNotBlank() } >= 2)
                )
        ) {
            return DetectedExternalVaultFormat.CSV_INVENTORY_OR_PASSWORDS
        }

        return DetectedExternalVaultFormat.UNKNOWN
    }

    @JvmOverloads
    fun importExternalFormat(
        format: DetectedExternalVaultFormat,
        rawBytes: ByteArray,
        documentNameHint: String,
        masterPassphrase: CharArray? = null
    ): NxtsVaultDocument {
        val cleanName = documentNameHint.substringBeforeLast('.').ifBlank { "Imported Enterprise Vault" }
        return when (format) {
            DetectedExternalVaultFormat.ROYAL_TS_RTSZ_OR_RTSX ->
                RtszDocumentImporter().importRtszBytes(rawBytes, cleanName, masterPassphrase)
            DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE -> {
                if (masterPassphrase == null || masterPassphrase.isEmpty()) {
                    throw IllegalArgumentException(
                        "Royal TS Lockdown (.rtsz) archive is password-protected and requires a password to import."
                    )
                }
                RtszDocumentImporter().importRtszBytes(rawBytes, cleanName, masterPassphrase)
            }
            DetectedExternalVaultFormat.ROYAL_JSON -> importRoyalJson(rawBytes, cleanName)
            DetectedExternalVaultFormat.KEEPASS_KDBX_BINARY -> {
                if (masterPassphrase == null || masterPassphrase.isEmpty()) {
                    throw IllegalArgumentException(
                        "KeePass .kdbx binary database is encrypted and requires a master passphrase to import."
                    )
                }
                importKeePassKdbxBinary(rawBytes, masterPassphrase, cleanName)
            }
            DetectedExternalVaultFormat.KEEPASS_XML_2X -> importKeePassXml(rawBytes, cleanName)
            DetectedExternalVaultFormat.BITWARDEN_JSON -> importBitwardenJson(rawBytes, cleanName)
            DetectedExternalVaultFormat.ONEPASSWORD_1PUX_OR_JSON -> importOnePassword1PuxOrJson(rawBytes, cleanName)
            DetectedExternalVaultFormat.CSV_INVENTORY_OR_PASSWORDS -> importCsvVault(rawBytes, cleanName)
            DetectedExternalVaultFormat.NXTS_ENCRYPTED_BINARY -> throw IllegalArgumentException(
                "Encrypted NexusTS .nxts binary vault requires unlocking with NxtsVaultSerializer."
            )
            DetectedExternalVaultFormat.UNKNOWN -> throw IllegalArgumentException(
                "Unsupported or unrecognized external vault format for '$documentNameHint'."
            )
        }
    }

    /**
     * Imports encrypted KeePass 4.x `.kdbx` binary containers using [KeePassKdbx4Reader].
     */
    fun importKeePassKdbxBinary(
        kdbxBytes: ByteArray,
        masterPassphrase: CharArray,
        vaultName: String = "Imported KeePass KDBX Vault"
    ): NxtsVaultDocument {
        val kdbxEntries = KeePassKdbx4Reader.readEntries(kdbxBytes, masterPassphrase)
        val foldersByPath = linkedMapOf<String, VaultFolder>()
        val credentials = mutableListOf<VaultCredential>()
        val connections = mutableListOf<VaultConnection>()

        for (entry in kdbxEntries) {
            if (entry.groupPath.any { it.equals("Recycle Bin", ignoreCase = true) || it.equals("Backup", ignoreCase = true) }) {
                continue
            }
            val folderId = if (entry.groupPath.isNotEmpty()) {
                ensureNestedFolderChain(entry.groupPath.joinToString("/"), null, foldersByPath)
            } else {
                null
            }
            val title = entry.title.ifBlank { "KeePass Entry" }
            val (domain, username) = splitDomainUser(entry.username)
            val credId = entry.uuidHex.takeIf { it.isNotBlank() }?.let { "kp-cred-$it" }
                ?: UUID.randomUUID().toString()

            credentials += VaultCredential(
                id = credId,
                parentFolderId = folderId,
                name = title,
                sourceKind = if (entry.sshPrivateKeyPem.isNotBlank()) {
                    CredentialSourceKind.SSH_KEY_PAIR
                } else {
                    CredentialSourceKind.KEEPASS_KDBX
                },
                username = username,
                domain = domain,
                secretPassword = entry.password,
                privateKeyPem = entry.sshPrivateKeyPem,
                externalReferenceUri = entry.uuidHex,
                totpSecretBase32 = extractBase32TotpSecret(entry.totpSecret)
            )

            synthesizeConnectionFromEndpoint(
                title = title,
                rawUri = entry.url,
                parentFolderId = folderId,
                credentialId = credId,
                notes = entry.notes
            )?.let { connections += it }
        }

        return NxtsVaultDocument(
            name = vaultName,
            description = "Imported from KeePass 4.x (.kdbx)",
            folders = foldersByPath.values.toList(),
            connections = connections,
            credentials = credentials
        )
    }

    /**
     * Imports KeePass 2.x XML (`<KeePassFile><Root><Group>...`) preserving nested folders,
     * credentials, TOTP seeds (`otpauth://` or raw Base32), SSH private keys, and remote hosts.
     */
    fun importKeePassXml(xmlBytes: ByteArray, vaultName: String = "Imported KeePass Vault"): NxtsVaultDocument {
        val dom = runCatching {
            SecureXmlDomParser.parseXmlBytes(xmlBytes)
        }.getOrElse { ex ->
            throw IllegalArgumentException("Invalid KeePass 2.x XML document: ${ex.message}", ex)
        }

        val folders = mutableListOf<VaultFolder>()
        val credentials = mutableListOf<VaultCredential>()
        val connections = mutableListOf<VaultConnection>()

        val rootNodes = dom.documentElement.getElementsByTagName("Root")
        val startContainer = (rootNodes.item(0) as? Element) ?: dom.documentElement

        fun walkGroup(groupElem: Element, parentFolderId: String?, isSyntheticRoot: Boolean) {
            val groupName = groupElem.directChildText("Name").ifBlank { "Group" }
            val groupUuid = groupElem.directChildText("UUID")
                .takeIf { it.isNotBlank() }
                ?.let { "kp-group-${it.filter(Char::isLetterOrDigit)}" }
                ?: UUID.randomUUID().toString()

            val currentFolderId = if (isSyntheticRoot) {
                null
            } else {
                folders += VaultFolder(
                    id = groupUuid,
                    parentFolderId = parentFolderId,
                    name = groupName,
                    description = groupElem.directChildText("Notes")
                )
                groupUuid
            }

            val children = groupElem.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType != Node.ELEMENT_NODE) continue
                val childElem = child as Element
                when (childElem.tagName) {
                    "Group" -> {
                        val subName = childElem.directChildText("Name")
                        if (!subName.equals("Recycle Bin", ignoreCase = true) &&
                            !subName.equals("Backup", ignoreCase = true)
                        ) {
                            walkGroup(childElem, currentFolderId, isSyntheticRoot = false)
                        }
                    }
                    "Entry" -> {
                        val kvMap = parseKeePassEntryStrings(childElem)
                        val title = kvMap["Title"].orEmpty().ifBlank { "KeePass Entry" }
                        val rawUser = kvMap["UserName"].orEmpty()
                        val password = kvMap["Password"].orEmpty()
                        val url = kvMap["URL"].orEmpty()
                        val notes = kvMap["Notes"].orEmpty()
                        val otpRaw = kvMap["otp"]
                            ?: kvMap["TOTP Seed"]
                            ?: kvMap["TimeOtp-Secret-Base32"]
                            ?: ""
                        val sshKeyCandidate = kvMap.values.firstOrNull {
                            it.contains("BEGIN OPENSSH PRIVATE KEY") || it.contains("BEGIN RSA PRIVATE KEY")
                        }.orEmpty()

                        val (domain, username) = splitDomainUser(rawUser)
                        val totpSecret = extractBase32TotpSecret(otpRaw)
                        val credId = childElem.directChildText("UUID")
                            .takeIf { it.isNotBlank() }
                            ?.let { "kp-cred-${it.filter(Char::isLetterOrDigit)}" }
                            ?: UUID.randomUUID().toString()

                        credentials += VaultCredential(
                            id = credId,
                            parentFolderId = currentFolderId,
                            name = title,
                            sourceKind = if (sshKeyCandidate.isNotBlank()) {
                                CredentialSourceKind.SSH_KEY_PAIR
                            } else {
                                CredentialSourceKind.KEEPASS_KDBX
                            },
                            username = username,
                            domain = domain,
                            secretPassword = password,
                            privateKeyPem = sshKeyCandidate,
                            totpSecretBase32 = totpSecret
                        )

                        synthesizeConnectionFromEndpoint(
                            title = title,
                            rawUri = url,
                            parentFolderId = currentFolderId,
                            credentialId = credId,
                            notes = notes
                        )?.let { connections += it }
                    }
                }
            }
        }

        val topChildren = startContainer.childNodes
        var foundDirectGroup = false
        for (i in 0 until topChildren.length) {
            val n = topChildren.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == "Group") {
                foundDirectGroup = true
                walkGroup(n, parentFolderId = null, isSyntheticRoot = true)
            }
        }
        if (!foundDirectGroup && startContainer.tagName == "Group") {
            walkGroup(startContainer, parentFolderId = null, isSyntheticRoot = true)
        }

        return NxtsVaultDocument(
            name = vaultName,
            description = "Imported from KeePass 2.x / KDBX XML",
            folders = folders,
            connections = connections,
            credentials = credentials
        )
    }

    /**
     * Imports Bitwarden unencrypted JSON export (`folders[]` + `items[]`), reconstructing nested
     * `"Prod/EU/Linux"` folder hierarchies and rejecting encrypted Bitwarden exports with a clear message.
     */
    fun importBitwardenJson(jsonBytes: ByteArray, vaultName: String = "Imported Bitwarden Vault"): NxtsVaultDocument {
        val root = lenientJson.parseToJsonElement(jsonBytes.toString(StandardCharsets.UTF_8)).jsonObject
        val isEncrypted = root["encrypted"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true
        if (isEncrypted) {
            throw IllegalArgumentException(
                "This Bitwarden JSON export is encrypted. Please export an unencrypted JSON or use .nxts encrypted vaults."
            )
        }

        val foldersByPath = linkedMapOf<String, VaultFolder>()
        val credentials = mutableListOf<VaultCredential>()
        val connections = mutableListOf<VaultConnection>()

        val folderIdRemap = mutableMapOf<String, String>()
        val rawFolderEntries = (root["folders"] as? JsonArray)
            ?.map { folderEl ->
                val obj = folderEl.jsonObject
                obj.str("id") to obj.str("name").ifBlank { "Bitwarden Folder" }
            }
            ?.sortedBy { (_, name) -> name.split('/').count { it.trim().isNotEmpty() } }
            .orEmpty()

        for ((origId, rawName) in rawFolderEntries) {
            val preferredLeafId = origId.takeIf { it.isNotBlank() }
            val resolvedLeafId = ensureNestedFolderChain(rawName, preferredLeafId, foldersByPath)
            if (origId.isNotBlank() && resolvedLeafId != null) {
                folderIdRemap[origId] = resolvedLeafId
            }
        }

        (root["items"] as? JsonArray)?.forEach { itemEl ->
            val item = itemEl.jsonObject
            val itemId = item.str("id").ifBlank { UUID.randomUUID().toString() }
            val name = item.str("name").ifBlank { "Bitwarden Item" }
            val notes = item.str("notes")
            val folderId = item.str("folderId").let { folderIdRemap[it] ?: it.takeIf(String::isNotBlank) }
            val favorite = item["favorite"]?.jsonPrimitive?.contentOrNull == "true"

            val loginObj = item["login"] as? JsonObject
            val sshKeyObj = item["sshKey"] as? JsonObject

            val rawUser = loginObj?.str("username").orEmpty()
            val password = loginObj?.str("password").orEmpty()
            val totpRaw = loginObj?.str("totp").orEmpty()
            val privKey = sshKeyObj?.str("privateKey").orEmpty()
            val pubKey = sshKeyObj?.str("publicKey").orEmpty()
            val fingerprint = sshKeyObj?.str("keyFingerprint").orEmpty()

            if (loginObj != null || sshKeyObj != null) {
                val (domain, user) = splitDomainUser(rawUser)
                val credId = "bw-cred-$itemId"
                credentials += VaultCredential(
                    id = credId,
                    parentFolderId = folderId,
                    name = name,
                    sourceKind = if (privKey.isNotBlank()) {
                        CredentialSourceKind.SSH_KEY_PAIR
                    } else {
                        CredentialSourceKind.BITWARDEN_VAULT
                    },
                    username = user,
                    domain = domain,
                    secretPassword = password,
                    privateKeyPem = privKey,
                    publicKeyOpenSsh = pubKey,
                    keyFingerprintSha256 = fingerprint,
                    totpSecretBase32 = extractBase32TotpSecret(totpRaw)
                )

                val firstUri = (loginObj?.get("uris") as? JsonArray)
                    ?.firstOrNull()
                    ?.let { (it as? JsonObject)?.str("uri") }
                    .orEmpty()

                synthesizeConnectionFromEndpoint(
                    title = name,
                    rawUri = firstUri,
                    parentFolderId = folderId,
                    credentialId = credId,
                    notes = notes,
                    favorite = favorite
                )?.let { connections += it }
            }
        }

        return NxtsVaultDocument(
            name = vaultName,
            description = "Imported from Bitwarden Vault JSON",
            folders = foldersByPath.values.toList(),
            connections = connections,
            credentials = credentials
        )
    }

    /**
     * Imports 1Password `.1pux` ZIP archives (`export.data`), 1Password JSON exports,
     * and 1Password `.1pif` multi-record exports (`***`-delimited records).
     */
    fun importOnePassword1PuxOrJson(rawBytes: ByteArray, vaultName: String = "Imported 1Password Vault"): NxtsVaultDocument {
        val jsonPayloadBytes = extractZipEntryOrRaw(rawBytes, "export.data")
        val payloadText = jsonPayloadBytes.toString(StandardCharsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')

        if (isOnePassword1PifPayload(payloadText)) {
            return importOnePassword1Pif(payloadText, vaultName)
        }

        val root = lenientJson.parseToJsonElement(payloadText).jsonObject

        val folders = mutableListOf<VaultFolder>()
        val credentials = mutableListOf<VaultCredential>()
        val connections = mutableListOf<VaultConnection>()

        fun processItem(item: JsonObject, parentFolderId: String?) {
            val uuid = item.str("uuid").ifBlank { UUID.randomUUID().toString() }
            val overview = item["overview"] as? JsonObject
            val title = overview?.str("title")?.ifBlank { null }
                ?: item.str("title").ifBlank { "1Password Item" }
            val primaryUrl = overview?.str("url")
                ?.ifBlank { null }
                ?: (overview?.get("urls") as? JsonArray)
                    ?.firstOrNull()
                    ?.let { (it as? JsonObject)?.str("url") }
                    .orEmpty()

            var username = ""
            var password = ""
            var totp = ""
            var sshPem = ""
            val details = item["details"] as? JsonObject
            val notes = details?.str("notesPlain").orEmpty()

            (details?.get("loginFields") as? JsonArray)?.forEach { f ->
                val fObj = f.jsonObject
                val designation = fObj.str("designation").lowercase()
                val fieldName = fObj.str("name").lowercase()
                val value = fObj.str("value")
                when {
                    designation == "username" || fieldName == "username" -> username = value
                    designation == "password" || fieldName == "password" -> password = value
                }
            }

            (details?.get("sections") as? JsonArray)?.forEach { sec ->
                val fields = (sec as? JsonObject)?.get("fields") as? JsonArray ?: return@forEach
                fields.forEach { fld ->
                    val fldObj = fld.jsonObject
                    val titleLower = fldObj.str("title").lowercase()
                    val valueObj = fldObj["value"]
                    when {
                        valueObj is JsonObject && valueObj["totp"] != null -> {
                            totp = valueObj.str("totp")
                        }
                        valueObj is JsonObject && valueObj["sshKey"] != null -> {
                            sshPem = (valueObj["sshKey"] as? JsonObject)?.str("privateKey").orEmpty()
                        }
                        valueObj is JsonElement -> {
                            val strVal = (valueObj as? JsonObject)?.str("string")
                                ?: runCatching { valueObj.jsonPrimitive.contentOrNull }.getOrNull().orEmpty()
                            if (username.isBlank() && titleLower.contains("user")) username = strVal
                            if (password.isBlank() && titleLower.contains("pass")) password = strVal
                            if (totp.isBlank() && titleLower.contains("otp")) totp = strVal
                        }
                    }
                }
            }

            val (domain, cleanUser) = splitDomainUser(username)
            val credId = "1p-cred-$uuid"
            credentials += VaultCredential(
                id = credId,
                parentFolderId = parentFolderId,
                name = title,
                sourceKind = if (sshPem.isNotBlank()) {
                    CredentialSourceKind.SSH_KEY_PAIR
                } else {
                    CredentialSourceKind.ONEPASSWORD_CONNECT
                },
                username = cleanUser,
                domain = domain,
                secretPassword = password,
                privateKeyPem = sshPem,
                totpSecretBase32 = extractBase32TotpSecret(totp)
            )

            synthesizeConnectionFromEndpoint(
                title = title,
                rawUri = primaryUrl,
                parentFolderId = parentFolderId,
                credentialId = credId,
                notes = notes
            )?.let { connections += it }
        }

        val accounts = root["accounts"] as? JsonArray
        if (accounts != null) {
            accounts.forEach { acct ->
                val vaults = (acct as? JsonObject)?.get("vaults") as? JsonArray ?: return@forEach
                vaults.forEach { v ->
                    val vObj = v.jsonObject
                    val vAttrs = vObj["attrs"] as? JsonObject
                    val vName = vAttrs?.str("name")?.ifBlank { null } ?: "1Password Vault"
                    val folderId = "1p-vault-${vAttrs?.str("uuid")?.ifBlank { UUID.randomUUID().toString() }}"
                    folders += VaultFolder(id = folderId, name = vName)
                    (vObj["items"] as? JsonArray)?.forEach { itemEl ->
                        processItem(itemEl.jsonObject, folderId)
                    }
                }
            }
        } else {
            (root["items"] as? JsonArray)?.forEach { itemEl ->
                processItem(itemEl.jsonObject, null)
            }
        }

        return NxtsVaultDocument(
            name = vaultName,
            description = "Imported from 1Password (.1pux / JSON)",
            folders = folders,
            connections = connections,
            credentials = credentials
        )
    }

    /**
     * Imports 1Password `.1pif` multi-record exports where JSON records are separated by lines starting with `***`.
     */
    fun importOnePassword1Pif(pifText: String, vaultName: String = "Imported 1Password 1PIF Vault"): NxtsVaultDocument {
        val credentials = mutableListOf<VaultCredential>()
        val connections = mutableListOf<VaultConnection>()

        val recordBlocks = mutableListOf<String>()
        val currentBlock = StringBuilder()
        for (line in pifText.lineSequence()) {
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("***")) {
                if (currentBlock.isNotBlank()) {
                    recordBlocks += currentBlock.toString().trim()
                    currentBlock.setLength(0)
                }
            } else if (trimmedLine.isNotEmpty()) {
                if (currentBlock.isNotEmpty()) currentBlock.append('\n')
                currentBlock.append(line)
            }
        }
        if (currentBlock.isNotBlank()) {
            recordBlocks += currentBlock.toString().trim()
        }

        for (block in recordBlocks) {
            val record = runCatching { lenientJson.parseToJsonElement(block).jsonObject }.getOrNull() ?: continue
            if (record["trashed"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true) {
                continue
            }

            val uuid = record.str("uuid").ifBlank { UUID.randomUUID().toString() }
            val title = record.str("title").ifBlank { "1Password Item" }
            val secure = record["secureContents"] as? JsonObject

            var primaryUrl = record.str("location").ifBlank {
                (secure?.get("URLs") as? JsonArray)
                    ?.firstOrNull()
                    ?.let { (it as? JsonObject)?.str("url") }
                    .orEmpty()
            }
            val notes = secure?.str("notesPlain").orEmpty()
            var username = secure?.str("username").orEmpty()
            var password = secure?.str("password").orEmpty()
            var totp = ""
            var sshPem = ""

            (secure?.get("fields") as? JsonArray)?.forEach { f ->
                val fObj = (f as? JsonObject) ?: return@forEach
                val designation = fObj.str("designation").lowercase()
                val fieldName = fObj.str("name").lowercase()
                val fieldType = fObj.str("type").uppercase()
                val value = fObj.str("value")
                when {
                    designation == "username" || fieldName == "username" -> if (username.isBlank()) username = value
                    designation == "password" || fieldName == "password" || (fieldType == "P" && password.isBlank()) ->
                        password = value
                    fieldName.contains("otp") || value.startsWith("otpauth://", ignoreCase = true) ->
                        if (totp.isBlank()) totp = value
                }
            }

            (secure?.get("sections") as? JsonArray)?.forEach { sec ->
                val fields = (sec as? JsonObject)?.get("fields") as? JsonArray ?: return@forEach
                fields.forEach { fld ->
                    val fldObj = (fld as? JsonObject) ?: return@forEach
                    val fldTitle = fldObj.str("t").ifBlank { fldObj.str("title") }.lowercase()
                    val fldName = fldObj.str("n").ifBlank { fldObj.str("name") }.lowercase()
                    val valElem = fldObj["v"] ?: fldObj["value"]
                    val strVal = when (valElem) {
                        is JsonObject -> valElem.str("totp")
                            .ifBlank { (valElem["sshKey"] as? JsonObject)?.str("privateKey").orEmpty() }
                            .ifBlank { valElem.str("string") }
                        is JsonElement -> runCatching { valElem.jsonPrimitive.contentOrNull }.getOrNull().orEmpty()
                        else -> ""
                    }
                    if (strVal.isNotBlank()) {
                        when {
                            fldName.startsWith("totp") || fldTitle.contains("otp") || fldTitle.contains("one-time") ||
                                strVal.startsWith("otpauth://", ignoreCase = true) ->
                                if (totp.isBlank()) totp = strVal
                            fldTitle.contains("private key") ||
                                strVal.contains("BEGIN OPENSSH PRIVATE KEY") ||
                                strVal.contains("BEGIN RSA PRIVATE KEY") ->
                                if (sshPem.isBlank()) sshPem = strVal
                            fldTitle.contains("user") && username.isBlank() -> username = strVal
                            fldTitle.contains("pass") && password.isBlank() -> password = strVal
                            (fldTitle == "hostname" || fldTitle == "server" || fldName == "hostname") && primaryUrl.isBlank() ->
                                primaryUrl = strVal
                        }
                    }
                }
            }

            val openTags = ((record["openContents"] as? JsonObject)?.get("tags") as? JsonArray)
                ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull?.trim() }.getOrNull() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

            val (domain, cleanUser) = splitDomainUser(username)
            val credId = "1p-cred-$uuid"
            credentials += VaultCredential(
                id = credId,
                name = title,
                sourceKind = if (sshPem.isNotBlank()) {
                    CredentialSourceKind.SSH_KEY_PAIR
                } else {
                    CredentialSourceKind.ONEPASSWORD_CONNECT
                },
                username = cleanUser,
                domain = domain,
                secretPassword = password,
                privateKeyPem = sshPem,
                totpSecretBase32 = extractBase32TotpSecret(totp)
            )

            synthesizeConnectionFromEndpoint(
                title = title,
                rawUri = primaryUrl,
                parentFolderId = null,
                credentialId = credId,
                notes = notes,
                tags = openTags
            )?.let { connections += it }
        }

        return NxtsVaultDocument(
            name = vaultName,
            description = "Imported from 1Password (.1pif)",
            connections = connections,
            credentials = credentials
        )
    }

    private fun isOnePassword1PifPayload(text: String): Boolean {
        if (text.startsWith("***") || text.contains("\n***")) return true
        if (text.startsWith("{")) {
            val root = runCatching { lenientJson.parseToJsonElement(text).jsonObject }.getOrNull()
            if (root != null && (root.containsKey("secureContents") || root.containsKey("typeName")) &&
                !root.containsKey("accounts") && !root.containsKey("items")
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Imports RFC 4180 CSV inventories & password exports (NexusTS CSV, Bitwarden CSV, 1Password CSV, KeePass CSV).
     */
    fun importCsvVault(csvBytes: ByteArray, vaultName: String = "Imported CSV Inventory"): NxtsVaultDocument {
        val rows = parseRfc4180Csv(csvBytes.toString(StandardCharsets.UTF_8))
        if (rows.size < 2) {
            return NxtsVaultDocument(name = vaultName)
        }
        val headers = rows.first().map { it.trim().lowercase() }
        fun colIndex(vararg aliases: String): Int =
            aliases.firstNotNullOfOrNull { alias -> headers.indexOf(alias.lowercase()).takeIf { it >= 0 } } ?: -1

        val idxConnId = colIndex("connectionid", "id")
        val idxName = colIndex("name", "title", "account")
        val idxHost = colIndex("host", "login_uri", "url", "website", "hostname")
        val idxProto = colIndex("protocol", "type")
        val idxPort = colIndex("port")
        val idxFolder = colIndex("folder", "group")
        val idxOsFamily = colIndex("osfamily", "os")
        val idxFavorite = colIndex("favorite", "isfavorite")
        val idxCredMode = colIndex("credentialmode")
        val idxAssignedCred = colIndex("assignedcredential")
        val idxTags = colIndex("tags", "tag")
        val idxColorHex = colIndex("colorhex", "color")
        val idxUser = colIndex("username", "login_username")
        val idxPass = colIndex("password", "login_password")
        val idxTotp = colIndex("totp", "login_totp", "otp")
        val idxNotes = colIndex("notes", "comments")

        val idxContainerCluster = colIndex("containercluster")
        val idxContainerContext = colIndex("containercontext")
        val idxContainerNamespace = colIndex("containernamespace")
        val idxContainerWorkload = colIndex("containerworkload")
        val idxContainerSubContainer = colIndex("containersubcontainer")
        val idxContainerShell = colIndex("containershell")
        val idxContainerFollowLogs = colIndex("containerfollowlogs")

        val foldersByPath = linkedMapOf<String, VaultFolder>()
        val credentialsByName = linkedMapOf<String, VaultCredential>()
        val credentials = mutableListOf<VaultCredential>()
        val connections = mutableListOf<VaultConnection>()

        for (row in rows.drop(1)) {
            if (row.all { it.isBlank() }) continue
            fun cell(idx: Int): String = if (idx in row.indices) row[idx].trim() else ""

            val connIdOverride = cell(idxConnId).takeIf { it.isNotBlank() }
            val title = cell(idxName).ifBlank { cell(idxHost).ifBlank { "Imported Entry" } }
            val folderPath = cell(idxFolder)
            val folderId = if (folderPath.isNotBlank()) {
                ensureNestedFolderChain(folderPath, null, foldersByPath)
            } else {
                null
            }

            val rawUser = cell(idxUser)
            val rawPass = cell(idxPass)
            val rawTotp = cell(idxTotp)
            val assignedCredName = cell(idxAssignedCred)
            val notes = cell(idxNotes)
            val osFamilyOverride = cell(idxOsFamily).takeIf { it.isNotBlank() }
            val favoriteVal = cell(idxFavorite).let { it.equals("true", ignoreCase = true) || it == "1" }
            val explicitCredMode = cell(idxCredMode).let { raw ->
                InheritanceMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }
            val tagsList = cell(idxTags)
                .let { raw -> if (';' in raw) raw.split(';') else raw.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val colorHex = cell(idxColorHex).takeIf { it.isNotBlank() }

            val credId: String? = when {
                rawUser.isNotBlank() || rawPass.isNotBlank() || rawTotp.isNotBlank() -> {
                    val (domain, user) = splitDomainUser(rawUser)
                    val id = UUID.randomUUID().toString()
                    val credObj = VaultCredential(
                        id = id,
                        parentFolderId = folderId,
                        name = assignedCredName.ifBlank { "$title Credential" },
                        sourceKind = CredentialSourceKind.VAULT_INTERNAL,
                        username = user,
                        domain = domain,
                        secretPassword = rawPass,
                        totpSecretBase32 = extractBase32TotpSecret(rawTotp)
                    )
                    credentials += credObj
                    if (assignedCredName.isNotBlank()) {
                        credentialsByName.putIfAbsent(assignedCredName.lowercase(), credObj)
                    }
                    id
                }
                assignedCredName.isNotBlank() -> {
                    val existing = credentialsByName.getOrPut(assignedCredName.lowercase()) {
                        VaultCredential(
                            id = UUID.randomUUID().toString(),
                            parentFolderId = folderId,
                            name = assignedCredName,
                            sourceKind = CredentialSourceKind.VAULT_INTERNAL
                        ).also { credentials += it }
                    }
                    existing.id
                }
                else -> null
            }

            val hostVal = cell(idxHost)
            val explicitProto = cell(idxProto).uppercase()
            val explicitPort = cell(idxPort).toIntOrNull()

            if (hostVal.isNotBlank()) {
                val matchedProto = VaultProtocolType.entries.firstOrNull { it.name == explicitProto }
                if (matchedProto != null) {
                    val containerCsvMeta = if (
                        matchedProto == VaultProtocolType.KUBERNETES_POD ||
                        matchedProto == VaultProtocolType.DOCKER_CONTAINER
                    ) {
                        CsvContainerMetadata(
                            cluster = cell(idxContainerCluster),
                            context = cell(idxContainerContext),
                            namespace = cell(idxContainerNamespace),
                            workload = cell(idxContainerWorkload),
                            subContainer = cell(idxContainerSubContainer),
                            shell = cell(idxContainerShell),
                            followLogs = cell(idxContainerFollowLogs)
                        )
                    } else {
                        null
                    }
                    connections += buildConnectionForProtocol(
                        idOverride = connIdOverride,
                        title = title,
                        host = hostVal,
                        protocol = matchedProto,
                        portOverride = explicitPort,
                        parentFolderId = folderId,
                        credentialId = credId,
                        credentialModeOverride = explicitCredMode,
                        osFamilyOverride = osFamilyOverride,
                        tags = tagsList,
                        colorHex = colorHex,
                        notes = notes,
                        favorite = favoriteVal,
                        containerMetadata = containerCsvMeta
                    )
                } else {
                    synthesizeConnectionFromEndpoint(
                        idOverride = connIdOverride,
                        title = title,
                        rawUri = hostVal,
                        parentFolderId = folderId,
                        credentialId = credId,
                        credentialModeOverride = explicitCredMode,
                        osFamilyOverride = osFamilyOverride,
                        tags = tagsList,
                        colorHex = colorHex,
                        notes = notes,
                        favorite = favoriteVal
                    )?.let { connections += it }
                }
            }
        }

        return NxtsVaultDocument(
            name = vaultName,
            description = "Imported from CSV Inventory",
            folders = foldersByPath.values.toList(),
            connections = connections,
            credentials = credentials
        )
    }

    /**
     * Reconstructs nested folder paths (e.g. `"Prod/EU/Linux"`) with proper `parentFolderId` chains.
     */
    private fun ensureNestedFolderChain(
        rawPath: String,
        leafFolderIdHint: String?,
        foldersByPath: MutableMap<String, VaultFolder>
    ): String? {
        val segments = rawPath.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        var parentId: String? = null
        var canonicalPath = ""
        for ((index, seg) in segments.withIndex()) {
            canonicalPath = if (canonicalPath.isEmpty()) seg else "$canonicalPath/$seg"
            val key = canonicalPath.lowercase()
            val isLeaf = index == segments.lastIndex
            val existing = foldersByPath[key]
            val folder = if (existing != null) {
                existing
            } else {
                val id = if (isLeaf && !leafFolderIdHint.isNullOrBlank()) {
                    leafFolderIdHint
                } else {
                    UUID.randomUUID().toString()
                }
                VaultFolder(
                    id = id,
                    parentFolderId = parentId,
                    name = seg
                ).also { foldersByPath[key] = it }
            }
            parentId = folder.id
        }
        return parentId
    }

    internal fun extractBase32TotpSecret(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("otpauth://", ignoreCase = true)) {
            val query = trimmed.substringAfter('?', "")
            for (part in query.split('&')) {
                val kv = part.split('=', limit = 2)
                if (kv.size == 2 && kv[0].equals("secret", ignoreCase = true)) {
                    return URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name())
                        .uppercase()
                        .filter { it in 'A'..'Z' || it in '2'..'7' }
                }
            }
        }
        return trimmed.uppercase().filter { it in 'A'..'Z' || it in '2'..'7' }
    }

    private data class CsvContainerMetadata(
        val cluster: String = "",
        val context: String = "",
        val namespace: String = "",
        val workload: String = "",
        val subContainer: String = "",
        val shell: String = "",
        val followLogs: String = ""
    ) {
        val hasExplicitColumns: Boolean
            get() = cluster.isNotBlank() || context.isNotBlank() || namespace.isNotBlank() ||
                workload.isNotBlank() || subContainer.isNotBlank() || shell.isNotBlank() || followLogs.isNotBlank()
    }

    private fun synthesizeConnectionFromEndpoint(
        idOverride: String? = null,
        title: String,
        rawUri: String,
        parentFolderId: String?,
        credentialId: String?,
        credentialModeOverride: InheritanceMode? = null,
        osFamilyOverride: String? = null,
        tags: List<String> = emptyList(),
        colorHex: String? = null,
        notes: String = "",
        favorite: Boolean = false
    ): VaultConnection? {
        val trimmed = rawUri.trim()
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase()
        val (proto, host, port) = when {
            lower.startsWith("rdp://") ->
                parseHostPort(trimmed.substringAfter("://"), VaultProtocolType.RDP, 3389)
            lower.startsWith("ssh://") ->
                parseHostPort(trimmed.substringAfter("://"), VaultProtocolType.SSH, 22)
            lower.startsWith("sftp://") ->
                parseHostPort(trimmed.substringAfter("://"), VaultProtocolType.SFTP, 22)
            lower.startsWith("ftp://") ->
                parseHostPort(trimmed.substringAfter("://"), VaultProtocolType.FTP, 21)
            lower.startsWith("ftps://") ->
                parseHostPort(trimmed.substringAfter("://"), VaultProtocolType.FTPS, 990)
            lower.startsWith("scp://") ->
                parseHostPort(trimmed.substringAfter("://"), VaultProtocolType.SCP, 22)
            lower.startsWith("vnc://") ->
                parseHostPort(trimmed.substringAfter("://"), VaultProtocolType.VNC, 5900)
            lower.startsWith("telnet://") ->
                parseHostPort(trimmed.substringAfter("://"), VaultProtocolType.TELNET, 23)
            lower.startsWith("serial://") ->
                Triple(VaultProtocolType.SERIAL, trimmed.substringAfter("://"), 115_200)
            lower.startsWith("k8s://") || lower.startsWith("kubectl://") ->
                Triple(VaultProtocolType.KUBERNETES_POD, trimmed.substringAfter("://"), 6443)
            lower.startsWith("docker://") ->
                Triple(VaultProtocolType.DOCKER_CONTAINER, trimmed.substringAfter("://"), 2376)
            lower.startsWith("http://") || lower.startsWith("https://") ->
                Triple(VaultProtocolType.WEB_PAGE, trimmed, if (lower.startsWith("http://")) 80 else 443)
            !trimmed.contains(' ') && (trimmed.contains('.') || trimmed.contains(':')) -> {
                classifyBarePasswordManagerEndpoint(title, trimmed) ?: return null
            }
            else -> return null
        }

        return buildConnectionForProtocol(
            idOverride = idOverride,
            title = title,
            host = host,
            protocol = proto,
            portOverride = port,
            parentFolderId = parentFolderId,
            credentialId = credentialId,
            credentialModeOverride = credentialModeOverride,
            osFamilyOverride = osFamilyOverride,
            tags = tags,
            colorHex = colorHex,
            notes = notes,
            favorite = favorite
        )
    }

    private fun classifyBarePasswordManagerEndpoint(
        title: String,
        bareEndpoint: String
    ): Triple<VaultProtocolType, String, Int>? {
        val (_, cleanHost, parsedPort) = parseHostPort(bareEndpoint, VaultProtocolType.WEB_PAGE, -1)
        if (cleanHost.isBlank()) return null

        val hasRdpTitleHint = RDP_TITLE_HINT_REGEX.containsMatchIn(title)
        val hasSshTitleHint = SSH_TITLE_HINT_REGEX.containsMatchIn(title)

        return when {
            parsedPort in setOf(22, 2222, 2200, 22222) ->
                Triple(VaultProtocolType.SSH, cleanHost, parsedPort)
            parsedPort in setOf(3389, 3390) ->
                Triple(VaultProtocolType.RDP, cleanHost, parsedPort)
            parsedPort in 5900..5999 ->
                Triple(VaultProtocolType.VNC, cleanHost, parsedPort)
            parsedPort == 23 ->
                Triple(VaultProtocolType.TELNET, cleanHost, 23)
            parsedPort == 21 ->
                Triple(VaultProtocolType.FTP, cleanHost, 21)
            parsedPort == 80 || parsedPort == 8080 ->
                Triple(VaultProtocolType.WEB_PAGE, "http://$bareEndpoint", parsedPort)
            parsedPort == 443 || parsedPort == 8443 ->
                Triple(VaultProtocolType.WEB_PAGE, "https://$bareEndpoint", parsedPort)
            hasRdpTitleHint ->
                Triple(VaultProtocolType.RDP, cleanHost, if (parsedPort > 0) parsedPort else 3389)
            hasSshTitleHint ->
                Triple(VaultProtocolType.SSH, cleanHost, if (parsedPort > 0) parsedPort else 22)
            else -> {
                val normalizedUrl = if (cleanHost.count { it == ':' } > 1 && !bareEndpoint.startsWith("[")) {
                    "https://[$bareEndpoint]"
                } else {
                    "https://$bareEndpoint"
                }
                Triple(VaultProtocolType.WEB_PAGE, normalizedUrl, if (parsedPort > 0) parsedPort else 443)
            }
        }
    }

    private fun buildConnectionForProtocol(
        idOverride: String? = null,
        title: String,
        host: String,
        protocol: VaultProtocolType,
        portOverride: Int?,
        parentFolderId: String?,
        credentialId: String?,
        credentialModeOverride: InheritanceMode? = null,
        osFamilyOverride: String? = null,
        tags: List<String> = emptyList(),
        colorHex: String? = null,
        notes: String = "",
        favorite: Boolean = false,
        containerMetadata: CsvContainerMetadata? = null
    ): VaultConnection {
        val isStripPathNonWebProtocol = when (protocol) {
            VaultProtocolType.RDP,
            VaultProtocolType.WINDOWS_TERMINAL_SERVICES,
            VaultProtocolType.SSH,
            VaultProtocolType.TELNET,
            VaultProtocolType.SFTP,
            VaultProtocolType.FTP,
            VaultProtocolType.FTPS,
            VaultProtocolType.SCP,
            VaultProtocolType.VNC,
            VaultProtocolType.APPLE_SCREEN_SHARING,
            VaultProtocolType.UTILITY_PING,
            VaultProtocolType.UTILITY_TRACEROUTE,
            VaultProtocolType.UTILITY_PORT_SCANNER,
            VaultProtocolType.UTILITY_WAKE_ON_LAN -> true
            else -> false
        }

        val (effectiveHost, effectivePort) = if (isStripPathNonWebProtocol) {
            val (_, parsedHost, parsedPort) = parseHostPort(host, protocol, -1)
            val resolvedHost = parsedHost.ifBlank { host }
            val resolvedPort = portOverride ?: parsedPort.takeIf { it > 0 }
            resolvedHost to resolvedPort
        } else {
            host to portOverride
        }

        val settings: ProtocolSpecificSettings = when (protocol) {
            VaultProtocolType.RDP, VaultProtocolType.WINDOWS_TERMINAL_SERVICES ->
                ProtocolSpecificSettings.Rdp(port = effectivePort ?: 3389)
            VaultProtocolType.SSH, VaultProtocolType.TELNET ->
                ProtocolSpecificSettings.Ssh(port = effectivePort ?: if (protocol == VaultProtocolType.TELNET) 23 else 22)
            VaultProtocolType.SERIAL ->
                ProtocolSpecificSettings.SerialUart(
                    baudRate = effectivePort?.takeIf { it > 0 } ?: 115_200,
                    devicePathHint = effectiveHost
                )
            VaultProtocolType.KUBERNETES_POD, VaultProtocolType.DOCKER_CONTAINER -> {
                if (containerMetadata != null && containerMetadata.hasExplicitColumns) {
                    ProtocolSpecificSettings.ContainerExec(
                        clusterOrSocketEndpoint = containerMetadata.cluster.ifBlank { effectiveHost },
                        contextName = containerMetadata.context.ifBlank { "prod-eu-central" },
                        namespace = containerMetadata.namespace.ifBlank { "default" },
                        podOrContainerName = containerMetadata.workload,
                        subContainerName = containerMetadata.subContainer,
                        shellCommand = containerMetadata.shell.ifBlank { "/bin/sh" },
                        followLogsOnOpen = containerMetadata.followLogs.equals("true", ignoreCase = true)
                    )
                } else if (!effectiveHost.contains("://") && effectiveHost.contains('/')) {
                    ProtocolSpecificSettings.ContainerExec(
                        podOrContainerName = effectiveHost.substringAfterLast('/'),
                        namespace = effectiveHost.substringBeforeLast('/', "default").ifBlank { "default" },
                        clusterOrSocketEndpoint = effectiveHost
                    )
                } else {
                    ProtocolSpecificSettings.ContainerExec(
                        clusterOrSocketEndpoint = effectiveHost,
                        namespace = "default",
                        podOrContainerName = ""
                    )
                }
            }
            VaultProtocolType.SFTP, VaultProtocolType.FTP, VaultProtocolType.FTPS, VaultProtocolType.SCP -> {
                val defaultFtPort = when (protocol) {
                    VaultProtocolType.FTP -> 21
                    VaultProtocolType.FTPS -> 990
                    else -> 22
                }
                ProtocolSpecificSettings.FileTransfer(port = effectivePort?.takeIf { it > 0 } ?: defaultFtPort)
            }
            VaultProtocolType.VNC, VaultProtocolType.APPLE_SCREEN_SHARING ->
                ProtocolSpecificSettings.Vnc(
                    port = effectivePort?.takeIf { it > 0 } ?: 5900,
                    appleArdDiffieHellmanAuth = protocol == VaultProtocolType.APPLE_SCREEN_SHARING
                )
            VaultProtocolType.WEB_PAGE -> {
                val webUrl = when {
                    effectiveHost.startsWith("http://", ignoreCase = true) ||
                        effectiveHost.startsWith("https://", ignoreCase = true) -> effectiveHost
                    effectiveHost.count { it == ':' } > 1 && !effectiveHost.startsWith("[") -> "https://[$effectiveHost]"
                    else -> "https://$effectiveHost"
                }
                ProtocolSpecificSettings.WebPage(url = webUrl)
            }
            VaultProtocolType.UTILITY_PING,
            VaultProtocolType.UTILITY_TRACEROUTE,
            VaultProtocolType.UTILITY_PORT_SCANNER,
            VaultProtocolType.UTILITY_WAKE_ON_LAN ->
                ProtocolSpecificSettings.NetUtility()
            else -> {
                val apiUrl = if (effectiveHost.startsWith("http://", ignoreCase = true) ||
                    effectiveHost.startsWith("https://", ignoreCase = true)
                ) {
                    effectiveHost
                } else {
                    "https://$effectiveHost"
                }
                ProtocolSpecificSettings.CloudVirtualization(apiEndpointUrl = apiUrl)
            }
        }

        val resolvedCredMode = when {
            credentialId != null && (credentialModeOverride == null || credentialModeOverride == InheritanceMode.OVERRIDE_EXPLICIT) ->
                InheritanceMode.OVERRIDE_EXPLICIT
            credentialModeOverride != null -> credentialModeOverride
            else -> InheritanceMode.INHERIT_FROM_PARENT
        }

        val inheritable = InheritableProperties(
            credentialMode = resolvedCredMode,
            explicitCredentialId = if (resolvedCredMode == InheritanceMode.OVERRIDE_EXPLICIT) credentialId else null,
            displayColorAccentHex = colorHex
        )

        val defaultOs = when (protocol) {
            VaultProtocolType.RDP, VaultProtocolType.WINDOWS_TERMINAL_SERVICES, VaultProtocolType.HYPER_V -> "Windows"
            VaultProtocolType.APPLE_SCREEN_SHARING -> "macOS"
            else -> "Linux"
        }

        return VaultConnection(
            id = idOverride ?: UUID.randomUUID().toString(),
            parentFolderId = parentFolderId,
            name = title,
            host = effectiveHost,
            protocol = protocol,
            osFamily = osFamilyOverride ?: defaultOs,
            tags = tags,
            notes = notes,
            favorite = favorite,
            inheritable = inheritable,
            settings = settings
        )
    }

    internal fun parseHostPort(
        authorityRaw: String,
        proto: VaultProtocolType,
        defaultPort: Int
    ): Triple<VaultProtocolType, String, Int> {
        val withoutScheme = if (authorityRaw.contains("://")) {
            authorityRaw.substringAfter("://")
        } else {
            authorityRaw
        }
        val clean = withoutScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
            .trim()

        if (clean.startsWith("[")) {
            val closeBracketIdx = clean.indexOf(']')
            if (closeBracketIdx > 1) {
                val ipv6Host = clean.substring(1, closeBracketIdx)
                val remainder = clean.substring(closeBracketIdx + 1)
                val port = if (remainder.startsWith(":")) {
                    remainder.substring(1).toIntOrNull() ?: defaultPort
                } else {
                    defaultPort
                }
                return Triple(proto, ipv6Host, port)
            }
        }

        val colonCount = clean.count { it == ':' }
        if (colonCount > 1) {
            // Bare IPv6 address (multiple colons without brackets, e.g. 2001:db8::1)
            return Triple(proto, clean, defaultPort)
        }

        if (colonCount == 1) {
            val colonIdx = clean.indexOf(':')
            if (colonIdx > 0 && colonIdx < clean.lastIndex) {
                val maybePort = clean.substring(colonIdx + 1).toIntOrNull()
                if (maybePort != null) {
                    return Triple(proto, clean.substring(0, colonIdx), maybePort)
                }
            }
        }

        return Triple(proto, clean, defaultPort)
    }

    private fun parseKeePassEntryStrings(entryElem: Element): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val children = entryElem.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == "String") {
                val k = n.directChildText("Key")
                val v = n.directChildText("Value")
                if (k.isNotBlank()) result[k] = v
            }
        }
        return result
    }

    private fun Element.directChildText(tagName: String): String {
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && (child as Element).tagName == tagName) {
                return child.textContent?.trim().orEmpty()
            }
        }
        return ""
    }

    private fun JsonObject.str(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull.orEmpty() }.getOrDefault("")

    private fun splitDomainUser(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        val idx = trimmed.indexOf('\\')
        return if (idx > 0 && idx < trimmed.lastIndex) {
            trimmed.substring(0, idx) to trimmed.substring(idx + 1)
        } else {
            "" to trimmed
        }
    }

    /**
     * Imports RoyalJSON (`.rjson` / Dynamic Folder JSON) documents supporting both nested `Objects[]`
     * inside `Folder` items and flat `ParentID`-linked `Objects[]` lists.
     */
    fun importRoyalJson(rawBytes: ByteArray, vaultName: String = "Imported RoyalJSON Vault"): NxtsVaultDocument {
        val text = decodeBomAwareFullText(rawBytes)
        val root = lenientJson.parseToJsonElement(text).jsonObject
        val docName = root.strAny("Name", "DocumentName").ifBlank { vaultName }
        val docDesc = root.strAny("Description", "Notes").ifBlank { "Imported from RoyalJSON (.rjson)" }

        val folders = mutableListOf<VaultFolder>()
        val connections = mutableListOf<VaultConnection>()
        val credentials = mutableListOf<VaultCredential>()
        val commandTasks = mutableListOf<CommandTask>()
        val todoItems = mutableListOf<VaultTodoItem>()

        fun visitObjects(array: JsonArray?, implicitParentFolderId: String?) {
            array?.forEach { elem ->
                val obj = elem as? JsonObject ?: return@forEach
                val typeRaw = obj.strAny("Type", "ObjectType").trim()
                val typeLower = typeRaw.lowercase()
                val id = obj.strAny("ID", "Id", "id").ifBlank { UUID.randomUUID().toString() }
                val name = obj.strAny("Name", "DisplayName", "Title").ifBlank { typeRaw.ifBlank { "Royal Item" } }
                val parentId = obj.strAny("ParentID", "ParentId").ifBlank { implicitParentFolderId.orEmpty() }
                    .takeIf { it.isNotBlank() }
                val notes = obj.strAny("Notes", "Description")
                val colorHex = obj.strAny("Color", "ColorName").takeIf { it.startsWith("#") }
                val isFavorite = obj.strAny("Favorite", "IsFavorite").equals("true", ignoreCase = true)

                when {
                    typeLower == "folder" || typeLower == "royalfolder" -> {
                        val credFromParent = obj.strAny("CredentialsFromParent").equals("true", ignoreCase = true)
                        val credId = obj.strAny("CredentialId", "CredentialID").takeIf { it.isNotBlank() }
                        folders += VaultFolder(
                            id = id,
                            parentFolderId = parentId,
                            name = name,
                            description = notes,
                            inheritable = InheritableProperties(
                                credentialMode = when {
                                    credId != null -> InheritanceMode.OVERRIDE_EXPLICIT
                                    credFromParent -> InheritanceMode.INHERIT_FROM_PARENT
                                    else -> InheritanceMode.INHERIT_FROM_PARENT
                                },
                                explicitCredentialId = credId,
                                displayColorAccentHex = colorHex
                            )
                        )
                        visitObjects(obj["Objects"] as? JsonArray, id)
                    }

                    typeLower == "credential" || typeLower == "royalcredential" || typeLower == "dynamiccredential" -> {
                        val rawUser = obj.strAny("Username", "UserName")
                        val explicitDomain = obj.strAny("Domain")
                        val (parsedDom, parsedUser) = splitDomainUser(rawUser)
                        val privKey = obj.strAny("KeyFileContent", "PrivateKey", "PrivateKeyContent")
                        credentials += VaultCredential(
                            id = id,
                            parentFolderId = parentId,
                            name = name,
                            sourceKind = if (privKey.isNotBlank()) {
                                CredentialSourceKind.SSH_KEY_PAIR
                            } else {
                                CredentialSourceKind.VAULT_INTERNAL
                            },
                            username = parsedUser,
                            domain = explicitDomain.ifBlank { parsedDom },
                            secretPassword = obj.strAny("Password"),
                            privateKeyPem = privKey,
                            keyPassphrase = obj.strAny("Passphrase", "KeyPassphrase")
                        )
                    }

                    typeLower == "commandtask" || typeLower == "royalcommandtask" || typeLower == "task" -> {
                        commandTasks += CommandTask(
                            id = id,
                            name = name,
                            commandTemplate = obj.strAny("Command", "CommandLine"),
                            broadcastToAllSelectedSessions = obj.strAny("BroadcastToAll").equals("true", ignoreCase = true),
                            appendNewline = !obj.strAny("AppendNewline").equals("false", ignoreCase = true)
                        )
                    }

                    typeLower == "todo" || typeLower == "todoitem" || typeLower == "royaltodo" -> {
                        todoItems += VaultTodoItem(
                            id = id,
                            targetObjectId = parentId,
                            title = name,
                            completed = obj.strAny("Completed", "IsCompleted", "Done").equals("true", ignoreCase = true),
                            priority = obj.strAny("Priority").ifBlank { "NORMAL" },
                            dueIsoDate = obj.strAny("DueDate")
                        )
                    }

                    else -> {
                        val subType = obj.strAny("SubType", "ConnectionType", "TerminalType").lowercase()
                        val proto: VaultProtocolType? = when {
                            typeLower in setOf("remotedesktopconnection", "remotedesktop", "rds", "rdp", "royalrdsconnection") ->
                                VaultProtocolType.RDP
                            typeLower in setOf("terminalconnection", "ssh", "sshconnection", "royalsshconnection", "royalterminalconnection") ->
                                when {
                                    subType.contains("telnet") -> VaultProtocolType.TELNET
                                    subType.contains("serial") -> VaultProtocolType.SERIAL
                                    else -> VaultProtocolType.SSH
                                }
                            typeLower in setOf("telnet", "telnetconnection") -> VaultProtocolType.TELNET
                            typeLower in setOf("serial", "serialconnection") -> VaultProtocolType.SERIAL
                            typeLower in setOf("vnc", "vncconnection", "royalvncconnection") -> VaultProtocolType.VNC
                            typeLower in setOf("filetransferconnection", "filetransfer", "sftp", "scp", "ftp", "ftps") ->
                                when {
                                    typeLower == "scp" || subType.contains("scp") -> VaultProtocolType.SCP
                                    typeLower == "ftps" || subType.contains("ftps") -> VaultProtocolType.FTPS
                                    typeLower == "ftp" || (subType.contains("ftp") && !subType.contains("sftp")) -> VaultProtocolType.FTP
                                    else -> VaultProtocolType.SFTP
                                }
                            typeLower in setOf("webconnection", "webpage", "web", "royalwebconnection", "royalwebpageconnection") ->
                                VaultProtocolType.WEB_PAGE
                            else -> null
                        }

                        if (proto != null) {
                            val rawHost = obj.strAny("ComputerName", "URI", "Host", "HostName", "URL").ifBlank { name }
                            val explicitPort = obj.strAny("Port", "RDPPort", "SSHPort").toIntOrNull()
                                ?.takeIf { it in 1..65535 }

                            val inlineUser = obj.strAny("Username", "UserName")
                            val inlinePass = obj.strAny("Password")
                            val explicitCredId = obj.strAny("CredentialId", "CredentialID").takeIf { it.isNotBlank() }
                            val credIdToBind = if (explicitCredId != null) {
                                explicitCredId
                            } else if (inlineUser.isNotBlank() || inlinePass.isNotBlank()) {
                                val synthId = "rjson-cred-$id"
                                val (dom, usr) = splitDomainUser(inlineUser)
                                credentials += VaultCredential(
                                    id = synthId,
                                    parentFolderId = parentId,
                                    name = "$name Credential",
                                    sourceKind = CredentialSourceKind.VAULT_INTERNAL,
                                    username = usr,
                                    domain = obj.strAny("Domain").ifBlank { dom },
                                    secretPassword = inlinePass
                                )
                                synthId
                            } else {
                                null
                            }

                            val credFromParent = obj.strAny("CredentialsFromParent").equals("true", ignoreCase = true)
                            val credMode = when {
                                credIdToBind != null -> InheritanceMode.OVERRIDE_EXPLICIT
                                credFromParent || parentId != null -> InheritanceMode.INHERIT_FROM_PARENT
                                else -> InheritanceMode.NONE_DIRECT
                            }

                            connections += buildConnectionForProtocol(
                                idOverride = id,
                                title = name,
                                host = rawHost,
                                protocol = proto,
                                portOverride = explicitPort,
                                parentFolderId = parentId,
                                credentialId = credIdToBind,
                                credentialModeOverride = credMode,
                                colorHex = colorHex,
                                notes = notes,
                                favorite = isFavorite
                            )
                        }
                        if (obj["Objects"] is JsonArray) {
                            visitObjects(obj["Objects"] as? JsonArray, parentId)
                        }
                    }
                }
            }
        }

        visitObjects(root["Objects"] as? JsonArray, null)

        return NxtsVaultDocument(
            name = docName,
            description = docDesc,
            folders = folders,
            connections = connections,
            credentials = credentials,
            commandTasks = commandTasks,
            todoItems = todoItems
        )
    }

    private fun JsonObject.strAny(vararg keys: String): String {
        for (k in keys) {
            val direct = str(k)
            if (direct.isNotBlank()) return direct
            val ciKey = entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.key
            if (ciKey != null) {
                val ciVal = str(ciKey)
                if (ciVal.isNotBlank()) return ciVal
            }
        }
        return ""
    }

    private fun gunzipPreviewBytes(rawBytes: ByteArray, maxBytes: Int): ByteArray? = runCatching {
        GZIPInputStream(ByteArrayInputStream(rawBytes)).use { gis ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(2048)
            var remaining = maxBytes
            while (remaining > 0) {
                val read = gis.read(buf, 0, minOf(buf.size, remaining))
                if (read <= 0) break
                out.write(buf, 0, read)
                remaining -= read
            }
            out.toByteArray()
        }
    }.getOrNull()

    private fun readFirstZipEntryPreview(rawBytes: ByteArray, maxBytes: Int): ByteArray? = runCatching {
        ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(2048)
                    var remaining = maxBytes
                    while (remaining > 0) {
                        val read = zis.read(buf, 0, minOf(buf.size, remaining))
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        remaining -= read
                    }
                    return@runCatching out.toByteArray()
                }
                entry = zis.nextEntry
            }
            null
        }
    }.getOrNull()

    private fun decodeBomAwarePreview(bytes: ByteArray, maxBytes: Int): String {
        val sliceLen = minOf(bytes.size, maxBytes)
        if (sliceLen <= 0) return ""
        val (charset, offset) = detectBomCharsetAndOffset(bytes, sliceLen)
        if (sliceLen <= offset) return ""
        val usableLen = if (charset == StandardCharsets.UTF_16LE || charset == StandardCharsets.UTF_16BE) {
            (sliceLen - offset) and 0x7FFFFFFE // even byte count for UTF-16
        } else {
            sliceLen - offset
        }
        if (usableLen <= 0) return ""
        return String(bytes, offset, usableLen, charset)
            .trimStart('\uFEFF', '\u0000', ' ', '\t', '\r', '\n')
    }

    private fun decodeBomAwareFullText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val (charset, offset) = detectBomCharsetAndOffset(bytes, bytes.size)
        return String(bytes, offset, bytes.size - offset, charset)
            .trimStart('\uFEFF', '\u0000', ' ', '\t', '\r', '\n')
    }

    private fun detectBomCharsetAndOffset(bytes: ByteArray, len: Int): Pair<Charset, Int> {
        if (len >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return StandardCharsets.UTF_8 to 3
        }
        if (len >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte()
        ) {
            return StandardCharsets.UTF_16LE to 2
        }
        if (len >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte()
        ) {
            return StandardCharsets.UTF_16BE to 2
        }
        if (len >= 4 && bytes[1] == 0x00.toByte() && bytes[3] == 0x00.toByte() && bytes[0] != 0x00.toByte()) {
            return StandardCharsets.UTF_16LE to 0
        }
        if (len >= 4 && bytes[0] == 0x00.toByte() && bytes[2] == 0x00.toByte() && bytes[1] != 0x00.toByte()) {
            return StandardCharsets.UTF_16BE to 0
        }
        return StandardCharsets.UTF_8 to 0
    }

    private fun looksLikePrintableText(rawBytes: ByteArray): Boolean {
        val sampleLen = minOf(rawBytes.size, 512)
        if (sampleLen == 0) return false
        var suspiciousControlCount = 0
        for (i in 0 until sampleLen) {
            val b = rawBytes[i].toInt() and 0xFF
            if (b == 0) return false
            if (b < 0x09 || (b in 0x0E..0x1F)) {
                suspiciousControlCount++
            }
        }
        return suspiciousControlCount * 20 <= sampleLen // <= 5% control bytes
    }

    private fun zipContainsEntry(rawBytes: ByteArray, entrySuffix: String): Boolean {
        runCatching {
            ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(entrySuffix, ignoreCase = true)) {
                        return true
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return false
    }

    private fun extractZipEntryOrRaw(rawBytes: ByteArray, wantedSuffix: String): ByteArray {
        if (rawBytes.size >= 4 &&
            rawBytes[0] == 0x50.toByte() &&
            rawBytes[1] == 0x4B.toByte() &&
            rawBytes[2] == 0x03.toByte() &&
            rawBytes[3] == 0x04.toByte()
        ) {
            ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(wantedSuffix, ignoreCase = true)) {
                        return zis.readBytes()
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return rawBytes
    }

    internal fun parseRfc4180Csv(input: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < input.length && input[i + 1] == '"') {
                        currentField.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow += currentField.toString()
                        currentField.setLength(0)
                    }
                    '\r' -> {
                        if (i + 1 < input.length && input[i + 1] == '\n') i++
                        currentRow += currentField.toString()
                        currentField.setLength(0)
                        rows += currentRow
                        currentRow = mutableListOf()
                    }
                    '\n' -> {
                        currentRow += currentField.toString()
                        currentField.setLength(0)
                        rows += currentRow
                        currentRow = mutableListOf()
                    }
                    else -> currentField.append(c)
                }
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow += currentField.toString()
            rows += currentRow
        }
        return rows
    }

    companion object {
        private val RDP_TITLE_HINT_REGEX =
            Regex("\\b(rdp|remote\\s*desktop|rds|winserver|domain\\s*controller)\\b", RegexOption.IGNORE_CASE)
        private val SSH_TITLE_HINT_REGEX =
            Regex("\\b(ssh|bastion|jumpbox|jumphost)\\b", RegexOption.IGNORE_CASE)
    }
}
