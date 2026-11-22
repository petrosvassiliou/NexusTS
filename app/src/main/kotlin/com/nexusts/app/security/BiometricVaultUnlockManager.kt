package com.nexusts.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardware-backed Biometric / Passkey / Device-Credential Vault Unlock & Idle Auto-Lock Manager
 * for Googlebook OS & Android Tablets (`v1.1.7`).
 *
 * Seals the user's Argon2id `.nxts` master passphrase under an Android Keystore AES-256-GCM key
 * (`nexusts_biometric_vault_aes256_v1`) and gates unsealing on [BiometricPrompt] verification
 * (`BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL`).
 */
@Singleton
class BiometricVaultUnlockManager @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isBiometricOrDeviceCredentialAvailable(): Boolean {
        return runCatching {
            val status = BiometricManager.from(appContext).canAuthenticate(AUTHENTICATORS_MASK)
            status == BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
    }

    fun isBiometricUnlockEnabled(): Boolean =
        prefs.getBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false) && hasEnrolledPassphrase()

    fun hasEnrolledPassphrase(): Boolean =
        !prefs.getString(KEY_SEALED_PASSPHRASE, null).isNullOrBlank()

    fun isUserExplicitlyOptedOut(): Boolean =
        prefs.getBoolean(KEY_BIOMETRIC_USER_OPTED_OUT, false)

    fun setBiometricUnlockEnabled(enabled: Boolean) {
        if (!enabled) {
            prefs.edit().putBoolean(KEY_BIOMETRIC_USER_OPTED_OUT, true).apply()
            clearEnrolledPassphrase(preserveOptOut = true)
        } else {
            prefs.edit()
                .putBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, true)
                .putBoolean(KEY_BIOMETRIC_USER_OPTED_OUT, false)
                .apply()
        }
    }

    fun getIdleAutoLockMinutes(): Int {
        val raw = prefs.getInt(KEY_IDLE_AUTO_LOCK_MINUTES, 0)
        return if (raw in SUPPORTED_IDLE_TIMEOUT_MINUTES) raw else 0
    }

    fun setIdleAutoLockMinutes(minutes: Int) {
        val normalized = if (minutes in SUPPORTED_IDLE_TIMEOUT_MINUTES) minutes else 0
        prefs.edit().putInt(KEY_IDLE_AUTO_LOCK_MINUTES, normalized).apply()
    }

    /**
     * Encrypts and stores [passphrase] under Android Keystore AES-256-GCM for Biometric / Quick-Lock use.
     */
    fun enrollPassphrase(passphrase: CharArray, enableBiometricUnlock: Boolean = true): Boolean {
        if (passphrase.isEmpty()) return false
        val utf8Buf = Charsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val plainBytes = ByteArray(utf8Buf.remaining())
        utf8Buf.get(plainBytes)
        if (utf8Buf.hasArray()) {
            Arrays.fill(utf8Buf.array(), 0)
        }
        return try {
            val sealed = sealBytes(plainBytes)
            prefs.edit()
                .putString(KEY_SEALED_PASSPHRASE, sealed)
                .putBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, enableBiometricUnlock)
                .putBoolean(KEY_BIOMETRIC_USER_OPTED_OUT, !enableBiometricUnlock)
                .apply()
            true
        } catch (_: Exception) {
            false
        } finally {
            Arrays.fill(plainBytes, 0)
        }
    }

    fun clearEnrolledPassphrase(preserveOptOut: Boolean = false) {
        val editor = prefs.edit()
            .remove(KEY_SEALED_PASSPHRASE)
            .putBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false)
        if (!preserveOptOut) {
            editor.remove(KEY_BIOMETRIC_USER_OPTED_OUT)
        }
        editor.apply()
    }

    /**
     * Reads a copy of the enrolled passphrase for re-sealing during Quick-Lock or Idle Auto-Lock
     * when the vault is currently unlocked, or after successful [BiometricPrompt] authentication.
     */
    fun copyEnrolledPassphraseOrNull(): CharArray? {
        val sealed = prefs.getString(KEY_SEALED_PASSPHRASE, null)?.takeIf { it.isNotBlank() } ?: return null
        val plainBytes = runCatching { unsealBytes(sealed) }.getOrNull() ?: return null
        return try {
            val charBuf = Charsets.UTF_8.decode(ByteBuffer.wrap(plainBytes))
            val chars = CharArray(charBuf.remaining())
            charBuf.get(chars)
            if (charBuf.hasArray()) {
                Arrays.fill(charBuf.array(), '\u0000')
            }
            chars.takeIf { it.isNotEmpty() }
        } finally {
            Arrays.fill(plainBytes, 0)
        }
    }

    /**
     * Launches the native Googlebook OS / Android [BiometricPrompt] (fingerprint, face, passkey, or PIN/pattern)
     * and invokes [onSuccess] with a freshly allocated [CharArray] passphrase upon hardware verification.
     */
    fun authenticateAndUnsealPassphrase(
        activity: FragmentActivity,
        title: String = "Unlock NexusTS Vault",
        subtitle: String = "Touch the fingerprint sensor or verify your identity to unlock NexusTS",
        onSuccess: (CharArray) -> Unit,
        onError: (String) -> Unit,
        onCanceled: () -> Unit = {}
    ) {
        if (!hasEnrolledPassphrase()) {
            onError("No vault passphrase is enrolled for biometric unlock yet.")
            return
        }
        if (!isBiometricOrDeviceCredentialAvailable()) {
            onError("Biometric or device credential authentication is not configured on this device.")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val chars = copyEnrolledPassphraseOrNull()
                if (chars != null) {
                    onSuccess(chars)
                } else {
                    onError("Could not unseal keystore vault credential; please enter your master passphrase.")
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                ) {
                    onCanceled()
                } else {
                    onError(errString.toString())
                }
            }
        }

        // Note: Do NOT set negativeButtonText when DEVICE_CREDENTIAL is included in allowedAuthenticators.
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS_MASK)
            .setConfirmationRequired(false)
            .build()

        runCatching {
            BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
        }.onFailure { err ->
            onError(err.message ?: "Biometric prompt unavailable.")
        }
    }

    private fun sealBytes(plainBytes: ByteArray): String {
        val androidKey = getOrCreateAndroidKeystoreKey()
        return if (androidKey != null) {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, androidKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainBytes)
            "ks1:${Base64.getEncoder().encodeToString(iv)}:${Base64.getEncoder().encodeToString(cipherBytes)}"
        } else {
            val fallbackKey = getOrCreateJvmFallbackKey()
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, fallbackKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val cipherBytes = cipher.doFinal(plainBytes)
            "jvm1:${Base64.getEncoder().encodeToString(iv)}:${Base64.getEncoder().encodeToString(cipherBytes)}"
        }
    }

    private fun unsealBytes(token: String): ByteArray {
        val parts = token.split(':')
        require(parts.size == 3) { "Malformed sealed biometric token" }
        val scheme = parts[0]
        val iv = Base64.getDecoder().decode(parts[1])
        val cipherBytes = Base64.getDecoder().decode(parts[2])
        val key: SecretKey = when (scheme) {
            "ks1" -> requireNotNull(getOrCreateAndroidKeystoreKey()) { "AndroidKeyStore unavailable" }
            "jvm1" -> getOrCreateJvmFallbackKey()
            else -> throw IllegalArgumentException("Unsupported sealed token scheme: $scheme")
        }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherBytes)
    }

    private fun getOrCreateAndroidKeystoreKey(): SecretKey? {
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }.getOrNull()
    }

    private fun getOrCreateJvmFallbackKey(): SecretKey {
        val existingB64 = prefs.getString(KEY_JVM_TEST_AES_KEY, null)
        val raw = if (!existingB64.isNullOrBlank()) {
            Base64.getDecoder().decode(existingB64)
        } else {
            ByteArray(32).also { bytes ->
                SecureRandom().nextBytes(bytes)
                prefs.edit().putString(KEY_JVM_TEST_AES_KEY, Base64.getEncoder().encodeToString(bytes)).apply()
            }
        }
        return SecretKeySpec(raw, "AES")
    }

    companion object {
        const val PREFS_NAME = "nexusts_biometric_vault_prefs"
        private const val KEY_BIOMETRIC_UNLOCK_ENABLED = "biometric_unlock_enabled"
        private const val KEY_BIOMETRIC_USER_OPTED_OUT = "biometric_user_opted_out"
        private const val KEY_SEALED_PASSPHRASE = "sealed_biometric_passphrase"
        private const val KEY_IDLE_AUTO_LOCK_MINUTES = "idle_auto_lock_minutes"
        private const val KEY_JVM_TEST_AES_KEY = "jvm_fallback_aes256_key"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "nexusts_biometric_vault_aes256_v1"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        val SUPPORTED_IDLE_TIMEOUT_MINUTES = listOf(0, 1, 5, 15, 30)

        const val AUTHENTICATORS_MASK =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
