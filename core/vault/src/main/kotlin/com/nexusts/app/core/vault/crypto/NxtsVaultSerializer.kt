package com.nexusts.app.core.vault.crypto

import com.nexusts.app.core.vault.model.NxtsVaultDocument
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JNI Bridge to Rust `nexusts-crypto` (`libnexusts_jni.so`), with transparent fallback to
 * byte-for-byte compatible BouncyCastle Argon2id + HKDF-SHA256 + JCE AES-256-GCM when running
 * in local JVM unit tests.
 */
object NativeVaultCryptoBridge {
    val isNativeLoaded: Boolean = runCatching {
        System.loadLibrary("nexusts_jni")
        true
    }.getOrDefault(false)

    @JvmStatic
    external fun nativeSealNxtsVault(
        plaintext: ByteArray,
        passphraseUtf8: ByteArray,
        revision: Long,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int
    ): ByteArray

    @JvmStatic
    external fun nativeOpenNxtsVault(
        container: ByteArray,
        passphraseUtf8: ByteArray
    ): ByteArray

    @JvmStatic
    external fun nativeGenerateEd25519KeypairJson(comment: String): String
}

class VaultAuthenticationException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

data class KdfSecurityParameters(
    val memoryKiB: Int = 65_536,
    val iterations: Int = 3,
    val parallelism: Int = 4
)

/**
 * Production `.nxts` Zero-Knowledge Vault Serializer & Encryptor.
 * Binary Wire Layout matches `native/rust/crates/nexusts-crypto` byte-for-byte:
 * - `[0..6]`   Magic `NXTS\x00\x02`
 * - `[6..10]`  Argon2id `m_cost_kib` (u32 BE)
 * - `[10..14]` Argon2id `t_cost` (u32 BE)
 * - `[14..18]` Argon2id `p_cost` (u32 BE)
 * - `[18..50]` 32-byte CSPRNG Salt
 * - `[50..62]` 12-byte AES-256-GCM Nonce
 * - `[62..70]` 64-bit Vault Revision (u64 BE, authenticated in GCM AAD)
 * - `[70..]`   AES-256-GCM Ciphertext + 128-bit Tag
 */
class NxtsVaultSerializer(
    private val kdfParams: KdfSecurityParameters = KdfSecurityParameters()
) {
    private val secureRandom = SecureRandom()
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "_type"
    }

    fun sealDocument(document: NxtsVaultDocument, masterPassphrase: CharArray): ByteArray {
        val plaintextBytes = json.encodeToString(NxtsVaultDocument.serializer(), document)
            .encodeToByteArray()
        val passphraseBytes = CharBufferUtf8.encode(masterPassphrase)
        try {
            if (NativeVaultCryptoBridge.isNativeLoaded) {
                return NativeVaultCryptoBridge.nativeSealNxtsVault(
                    plaintext = plaintextBytes,
                    passphraseUtf8 = passphraseBytes,
                    revision = document.revision,
                    memoryKiB = kdfParams.memoryKiB,
                    iterations = kdfParams.iterations,
                    parallelism = kdfParams.parallelism
                )
            }
            return sealJvmCompatible(plaintextBytes, passphraseBytes, document.revision)
        } finally {
            Arrays.fill(plaintextBytes, 0)
            Arrays.fill(passphraseBytes, 0)
        }
    }

    fun unsealDocument(nxtsContainer: ByteArray, masterPassphrase: CharArray): NxtsVaultDocument {
        val passphraseBytes = CharBufferUtf8.encode(masterPassphrase)
        var plaintext: ByteArray? = null
        try {
            plaintext = if (NativeVaultCryptoBridge.isNativeLoaded) {
                try {
                    NativeVaultCryptoBridge.nativeOpenNxtsVault(nxtsContainer, passphraseBytes)
                } catch (e: RuntimeException) {
                    throw VaultAuthenticationException("Failed to unlock .nxts vault", e)
                }
            } else {
                openJvmCompatible(nxtsContainer, passphraseBytes)
            }
            val decoded = json.decodeFromString(
                NxtsVaultDocument.serializer(),
                plaintext.decodeToString()
            )
            return migrateLegacyDefaultRdpScale(decoded)
        } finally {
            Arrays.fill(passphraseBytes, 0)
            if (plaintext != null) Arrays.fill(plaintext, 0)
        }
    }

    private fun migrateLegacyDefaultRdpScale(doc: NxtsVaultDocument): NxtsVaultDocument {
        fun migrateSettings(settings: com.nexusts.app.core.vault.model.ProtocolSpecificSettings): com.nexusts.app.core.vault.model.ProtocolSpecificSettings {
            if (settings is com.nexusts.app.core.vault.model.ProtocolSpecificSettings.Rdp &&
                !settings.hiDpiScaleCustomized &&
                settings.hiDpiEnabled &&
                (settings.desktopScaleFactorPercent == 150 || settings.desktopScaleFactorPercent == 100)
            ) {
                return settings.copy(
                    desktopScaleFactorPercent = 200,
                    deviceScaleFactorPercent = 180
                )
            }
            return settings
        }
        return doc.copy(
            connections = doc.connections.map { conn ->
                val migrated = migrateSettings(conn.settings)
                if (migrated === conn.settings) conn else conn.copy(settings = migrated)
            },
            templates = doc.templates.map { tpl ->
                val migrated = migrateSettings(tpl.settings)
                if (migrated === tpl.settings) tpl else tpl.copy(settings = migrated)
            }
        )
    }

    fun inspectUnencryptedRevisionHeader(nxtsContainer: ByteArray): Long? {
        if (nxtsContainer.size < HEADER_LENGTH) return null
        for (i in MAGIC_V2.indices) {
            if (nxtsContainer[i] != MAGIC_V2[i]) return null
        }
        return ByteBuffer.wrap(nxtsContainer, 62, 8).order(ByteOrder.BIG_ENDIAN).long
    }

    private fun sealJvmCompatible(
        plaintext: ByteArray,
        passphraseBytes: ByteArray,
        revision: Long
    ): ByteArray {
        val salt = ByteArray(SALT_LEN).also(secureRandom::nextBytes)
        val nonce = ByteArray(NONCE_LEN).also(secureRandom::nextBytes)
        val aesKey = deriveAes256GcmSubkey(
            passphraseBytes = passphraseBytes,
            salt = salt,
            memoryKiB = kdfParams.memoryKiB,
            iterations = kdfParams.iterations,
            parallelism = kdfParams.parallelism
        )

        try {
            val header = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN).apply {
                put(MAGIC_V2)
                putInt(kdfParams.memoryKiB)
                putInt(kdfParams.iterations)
                putInt(kdfParams.parallelism)
                put(salt)
                put(nonce)
                putLong(revision)
            }.array()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(128, nonce)
            )
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(plaintext)

            return ByteArray(HEADER_LENGTH + ciphertext.size).also { out ->
                System.arraycopy(header, 0, out, 0, HEADER_LENGTH)
                System.arraycopy(ciphertext, 0, out, HEADER_LENGTH, ciphertext.size)
            }
        } finally {
            Arrays.fill(aesKey, 0)
        }
    }

    private fun openJvmCompatible(container: ByteArray, passphraseBytes: ByteArray): ByteArray {
        if (container.size < HEADER_LENGTH + 16) {
            throw VaultAuthenticationException("Truncated or invalid .nxts file")
        }
        for (i in MAGIC_V2.indices) {
            if (container[i] != MAGIC_V2[i]) {
                throw VaultAuthenticationException("Unsupported .nxts magic header")
            }
        }

        val headerBuf = ByteBuffer.wrap(container, 0, HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN)
        headerBuf.position(6)
        val memoryKiB = headerBuf.int
        val iterations = headerBuf.int
        val parallelism = headerBuf.int
        if (memoryKiB < 8_192 || iterations < 1 || parallelism < 1) {
            throw VaultAuthenticationException("KDF parameters below security floor")
        }

        val salt = ByteArray(SALT_LEN).also { headerBuf.get(it) }
        val nonce = ByteArray(NONCE_LEN).also { headerBuf.get(it) }

        val aesKey = deriveAes256GcmSubkey(
            passphraseBytes = passphraseBytes,
            salt = salt,
            memoryKiB = memoryKiB,
            iterations = iterations,
            parallelism = parallelism
        )

        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(128, nonce)
            )
            cipher.updateAAD(container, 0, HEADER_LENGTH)
            return cipher.doFinal(container, HEADER_LENGTH, container.size - HEADER_LENGTH)
        } catch (badTag: AEADBadTagException) {
            throw VaultAuthenticationException(
                "Vault authentication failed: wrong master passphrase or corrupted file",
                badTag
            )
        } finally {
            Arrays.fill(aesKey, 0)
        }
    }

    private fun deriveAes256GcmSubkey(
        passphraseBytes: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int
    ): ByteArray {
        val argonParams = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()

        val ikm = ByteArray(32)
        val generator = Argon2BytesGenerator()
        generator.init(argonParams)
        generator.generateBytes(passphraseBytes, ikm)

        val subkey = ByteArray(32)
        try {
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(
                HKDFParameters(
                    ikm,
                    salt,
                    "nexusts-vault-v2-aes256gcm".encodeToByteArray()
                )
            )
            hkdf.generateBytes(subkey, 0, 32)
            return subkey
        } finally {
            Arrays.fill(ikm, 0)
        }
    }

    private object CharBufferUtf8 {
        fun encode(chars: CharArray): ByteArray {
            val byteBuf = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
            val out = ByteArray(byteBuf.remaining())
            byteBuf.get(out)
            if (byteBuf.hasArray()) {
                Arrays.fill(byteBuf.array(), 0)
            }
            return out
        }
    }

    companion object {
        private val MAGIC_V2 = byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 0x00, 0x02)
        private const val SALT_LEN = 32
        private const val NONCE_LEN = 12
        private const val HEADER_LENGTH = 70
    }
}
