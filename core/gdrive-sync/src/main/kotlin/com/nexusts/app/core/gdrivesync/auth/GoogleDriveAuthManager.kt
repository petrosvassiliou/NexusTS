package com.nexusts.app.core.gdrivesync.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.RestrictionsManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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

data class AuthenticatedGoogleIdentity(
    val email: String,
    val displayName: String?,
    val idToken: String,
    val oauthAccessToken: String
)

class GoogleDriveConsentResolutionRequiredException(
    val resolutionIntentSender: IntentSender,
    message: String = "User interactive consent required for Google Drive OAuth 2.0 scopes"
) : IllegalStateException(message)

/**
 * Native Google Identity Services & Android Credential Manager OAuth 2.0 Authenticator (`v1.2`).
 *
 * Supports two seamless authorization paths:
 * 1. **Direct Android OAuth 2.0 (`AuthorizationClient`)**: Works with an Android OAuth Client (package
 *    `com.nexusts.app` + RSA-4096 SHA-1) WITHOUT requiring a Web Client ID! Resolves user identity via
 *    Drive v3 `about.get(fields="user(emailAddress,displayName)")`.
 * 2. **Enterprise Hybrid OIDC + OAuth 2.0 (`CredentialManager` + `AuthorizationClient`)**: Enriches
 *    authentication with a signed OIDC `idToken` whenever `webClientId` is configured via MDM or Studio.
 */
class GoogleDriveAuthManager(
    private val appContext: Context
) {
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val requestedDriveScopes = listOf(
        Scope(DriveScopes.DRIVE_APPDATA),
        Scope(DriveScopes.DRIVE_FILE),
        Scope("email"),
        Scope("profile")
    )

    fun saveRuntimeOAuthConfiguration(webClientId: String, sharedDriveFolderId: String = "") {
        prefs.edit()
            .putString(KEY_RUNTIME_WEB_CLIENT_ID, webClientId.trim())
            .putString(KEY_RUNTIME_SHARED_FOLDER_ID, sharedDriveFolderId.trim())
            .apply()
    }

    fun readRuntimeOAuthConfiguration(): Pair<String, String> {
        val clientId = prefs.getString(KEY_RUNTIME_WEB_CLIENT_ID, "").orEmpty()
        val folderId = prefs.getString(KEY_RUNTIME_SHARED_FOLDER_ID, "")
            ?.trim()
            ?.ifBlank { DEFAULT_PROVISIONED_DRIVE_FOLDER_ID }
            ?: DEFAULT_PROVISIONED_DRIVE_FOLDER_ID
        return clientId to folderId
    }

    fun readSignedInAccount(): Pair<String, String> {
        val email = prefs.getString(KEY_SIGNED_IN_EMAIL, "").orEmpty()
        val name = prefs.getString(KEY_SIGNED_IN_DISPLAY_NAME, "").orEmpty()
        return email to name
    }

    fun isAutoSyncOnSaveEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO_SYNC_ON_SAVE, true)

    fun setAutoSyncOnSaveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC_ON_SAVE, enabled).apply()
    }

    fun isRememberVaultPassphraseEnabled(): Boolean =
        prefs.getBoolean(KEY_REMEMBER_VAULT_PASSPHRASE, false)

    /**
     * Encrypts and stores the user's vault passphrase in Android Keystore-backed AES-256-GCM preferences
     * when the user opts in (enabling seamless cold-start unlock & background 3-way Drive merges),
     * or purges it immediately when opted out.
     */
    @JvmOverloads
    fun saveVaultPassphrasePreference(
        passphrase: CharArray?,
        rememberPassphrase: Boolean = passphrase != null && passphrase.isNotEmpty(),
        documentId: String? = null
    ) {
        if (!rememberPassphrase || passphrase == null || passphrase.isEmpty()) {
            clearSavedVaultPassphrase(documentId)
            return
        }

        val utf8Buf = Charsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val plainBytes = ByteArray(utf8Buf.remaining())
        utf8Buf.get(plainBytes)
        if (utf8Buf.hasArray()) {
            Arrays.fill(utf8Buf.array(), 0)
        }

        try {
            val sealedToken = sealPassphraseBytes(plainBytes)
            val editor = prefs.edit()
                .putBoolean(KEY_REMEMBER_VAULT_PASSPHRASE, true)
                .putString(KEY_SEALED_DEFAULT_PASSPHRASE, sealedToken)
            if (!documentId.isNullOrBlank()) {
                editor.putString("$KEY_SEALED_DOC_PASSPHRASE_PREFIX$documentId", sealedToken)
            }
            editor.apply()
        } finally {
            Arrays.fill(plainBytes, 0)
        }
    }

    /**
     * Decrypts and returns the opted-in local vault passphrase if saved, or `null` if not configured.
     */
    @JvmOverloads
    fun readVaultPassphraseOrNull(documentId: String? = null): CharArray? {
        if (!isRememberVaultPassphraseEnabled()) return null
        val sealedToken = if (!documentId.isNullOrBlank()) {
            prefs.getString("$KEY_SEALED_DOC_PASSPHRASE_PREFIX$documentId", null)
                ?: prefs.getString(KEY_SEALED_DEFAULT_PASSPHRASE, null)
        } else {
            prefs.getString(KEY_SEALED_DEFAULT_PASSPHRASE, null)
        } ?: return null

        val plainBytes = runCatching { unsealPassphraseBytes(sealedToken) }.getOrNull() ?: return null
        return try {
            val charBuf = Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(plainBytes))
            val out = CharArray(charBuf.remaining())
            charBuf.get(out)
            if (charBuf.hasArray()) {
                Arrays.fill(charBuf.array(), '\u0000')
            }
            out.takeIf { it.isNotEmpty() }
        } finally {
            Arrays.fill(plainBytes, 0)
        }
    }

    @JvmOverloads
    fun clearSavedVaultPassphrase(documentId: String? = null) {
        val editor = prefs.edit()
        if (!documentId.isNullOrBlank()) {
            editor.remove("$KEY_SEALED_DOC_PASSPHRASE_PREFIX$documentId")
        } else {
            editor.putBoolean(KEY_REMEMBER_VAULT_PASSPHRASE, false)
                .remove(KEY_SEALED_DEFAULT_PASSPHRASE)
            prefs.all.keys
                .filter { it.startsWith(KEY_SEALED_DOC_PASSPHRASE_PREFIX) }
                .forEach { editor.remove(it) }
        }
        editor.apply()
    }

    fun signOut() {
        prefs.edit()
            .remove(KEY_SIGNED_IN_EMAIL)
            .remove(KEY_SIGNED_IN_DISPLAY_NAME)
            .apply()
    }

    /**
     * Resolves the optional OAuth 2.0 Web Client ID in strict enterprise precedence:
     * 1. Android Enterprise `RestrictionsManager` (`google_oauth_web_client_id`)
     * 2. User-saved runtime configuration (`nexusts_oauth_config`)
     * 3. Compile-time `BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID`
     */
    fun resolveConfiguredWebClientId(fallbackWebClientId: String = ""): String {
        val restrictions = appContext.getSystemService(RestrictionsManager::class.java)
            ?.applicationRestrictions
        val mdmClientId = restrictions?.getString("google_oauth_web_client_id")?.trim().orEmpty()
        if (mdmClientId.isNotBlank()) return mdmClientId
        val runtimeClientId = prefs.getString(KEY_RUNTIME_WEB_CLIENT_ID, "")?.trim().orEmpty()
        if (runtimeClientId.isNotBlank()) return runtimeClientId
        return fallbackWebClientId.trim()
    }

    fun resolveConfiguredSharedFolderId(): String {
        val restrictions = appContext.getSystemService(RestrictionsManager::class.java)
            ?.applicationRestrictions
        val mdmFolderId = restrictions?.getString("managed_team_drive_id")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: restrictions?.getString("managed_shared_drive_id")?.trim()
                ?.takeIf { it.isNotBlank() }
        if (!mdmFolderId.isNullOrBlank()) return mdmFolderId
        return readRuntimeOAuthConfiguration().second
    }

    /**
     * Performs interactive Google Drive OAuth 2.0 authorization (`drive.appdata` + `drive.file`).
     * Works with or without a Web Client ID!
     */
    suspend fun signInAndAuthorizeDrive(
        activity: Activity,
        webClientId: String = ""
    ): AuthenticatedGoogleIdentity {
        val effectiveClientId = resolveConfiguredWebClientId(webClientId)
        var idTokenEmail: String? = null
        var idTokenName: String? = null
        var rawIdToken = ""

        // Optional OIDC ID token step when an enterprise Web Client ID is configured
        if (effectiveClientId.isNotBlank()) {
            runCatching {
                val credentialManager = CredentialManager.create(activity)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(effectiveClientId)
                    .setAutoSelectEnabled(true)
                    .build()

                val credResult = credentialManager.getCredential(
                    activity,
                    GetCredentialRequest(listOf(googleIdOption))
                )
                val customCred = credResult.credential as? CustomCredential
                if (customCred != null) {
                    val idTokenCred = GoogleIdTokenCredential.createFrom(customCred.data)
                    idTokenEmail = idTokenCred.id
                    idTokenName = idTokenCred.displayName
                    rawIdToken = idTokenCred.idToken
                }
            }
        }

        val authRequest = AuthorizationRequest.builder()
            .setRequestedScopes(requestedDriveScopes)
            .build()

        val authResult = Identity.getAuthorizationClient(activity)
            .authorize(authRequest)
            .await()

        return finalizeAuthorizationResult(authResult, idTokenEmail, idTokenName, rawIdToken)
    }

    /**
     * Completes interactive Google Drive OAuth 2.0 scope consent returned from `StartIntentSenderForResult`.
     */
    suspend fun completeAuthorizationFromIntent(
        activity: Activity,
        data: Intent?
    ): AuthenticatedGoogleIdentity {
        val authResult = Identity.getAuthorizationClient(activity)
            .getAuthorizationResultFromIntent(data)
        return finalizeAuthorizationResult(authResult, null, null, "")
    }

    /**
     * Attempts non-interactive background token retrieval for [com.nexusts.app.core.gdrivesync.sync.VaultDriveSyncWorker].
     * Returns `null` if user interaction/consent is required or Play Services is unavailable.
     */
    suspend fun trySilentBackgroundAccessToken(): String? = runCatching {
        val authRequest = AuthorizationRequest.builder()
            .setRequestedScopes(requestedDriveScopes)
            .build()
        val result = Identity.getAuthorizationClient(appContext)
            .authorize(authRequest)
            .await()
        if (!result.hasResolution()) result.accessToken?.takeIf { it.isNotBlank() } else null
    }.getOrNull()

    private suspend fun finalizeAuthorizationResult(
        authResult: AuthorizationResult,
        knownEmail: String?,
        knownDisplayName: String?,
        rawIdToken: String
    ): AuthenticatedGoogleIdentity {
        if (authResult.hasResolution()) {
            val pendingIntent = authResult.pendingIntent
            if (pendingIntent != null) {
                throw GoogleDriveConsentResolutionRequiredException(pendingIntent.intentSender)
            }
        }

        val accessToken = requireNotNull(authResult.accessToken) {
            "Google Drive OAuth 2.0 access token was not granted."
        }

        val (resolvedEmail, resolvedName) = if (!knownEmail.isNullOrBlank()) {
            knownEmail to knownDisplayName
        } else {
            fetchDriveUserProfile(accessToken)
        }

        prefs.edit()
            .putString(KEY_SIGNED_IN_EMAIL, resolvedEmail)
            .putString(KEY_SIGNED_IN_DISPLAY_NAME, resolvedName.orEmpty())
            .apply()

        return AuthenticatedGoogleIdentity(
            email = resolvedEmail,
            displayName = resolvedName,
            idToken = rawIdToken,
            oauthAccessToken = accessToken
        )
    }

    private suspend fun fetchDriveUserProfile(oauthAccessToken: String): Pair<String, String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val about = buildDriveService(oauthAccessToken)
                    .about()
                    .get()
                    .setFields("user(emailAddress,displayName)")
                    .execute()
                val email = about.user?.emailAddress?.takeIf { it.isNotBlank() } ?: "google-drive-user@google.com"
                email to about.user?.displayName
            }.getOrElse {
                "google-drive-user@google.com" to "Google Drive User"
            }
        }

    fun buildDriveService(oauthAccessToken: String): Drive {
        val bearerInitializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $oauthAccessToken"
            request.connectTimeout = 15_000
            request.readTimeout = 30_000
        }
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            bearerInitializer
        )
            .setApplicationName("NexusTS/1.2 (Googlebook OS)")
            .build()
    }

    private fun sealPassphraseBytes(plainBytes: ByteArray): String {
        val keystoreKey = getOrCreateAndroidKeystoreKeyOrNull()
        return if (keystoreKey != null) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plainBytes)
            "v1:aks:${Base64.getEncoder().encodeToString(iv)}:${Base64.getEncoder().encodeToString(ciphertext)}"
        } else {
            val wrapKey = getOrCreateFallbackWrapKey()
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrapKey, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plainBytes)
            "v1:jce:${Base64.getEncoder().encodeToString(iv)}:${Base64.getEncoder().encodeToString(ciphertext)}"
        }
    }

    private fun unsealPassphraseBytes(sealedToken: String): ByteArray? {
        val parts = sealedToken.split(':')
        if (parts.size != 4 || parts[0] != "v1") return null
        val mode = parts[1]
        val iv = Base64.getDecoder().decode(parts[2])
        val ciphertext = Base64.getDecoder().decode(parts[3])
        val secretKey: SecretKey = when (mode) {
            "aks" -> getOrCreateAndroidKeystoreKeyOrNull() ?: return null
            "jce" -> getOrCreateFallbackWrapKey()
            else -> return null
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateAndroidKeystoreKeyOrNull(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (ks.getEntry(KEYSTORE_PASSPHRASE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return@runCatching existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_PASSPHRASE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        keyGen.generateKey()
    }.getOrNull()

    private fun getOrCreateFallbackWrapKey(): SecretKey {
        val existingB64 = prefs.getString(KEY_FALLBACK_WRAP_KEY, null)
        val rawBytes = if (!existingB64.isNullOrBlank()) {
            Base64.getDecoder().decode(existingB64)
        } else {
            ByteArray(32).also { bytes ->
                SecureRandom().nextBytes(bytes)
                prefs.edit().putString(KEY_FALLBACK_WRAP_KEY, Base64.getEncoder().encodeToString(bytes)).apply()
            }
        }
        return SecretKeySpec(rawBytes, "AES")
    }

    companion object {
        /** Pre-provisioned `NexusTS Vaults` folder ID created in the user's connected Google Drive */
        const val DEFAULT_PROVISIONED_DRIVE_FOLDER_ID = "17C98pGqQUAoQwQ-OSLUHPj4_dtb4_wtS"
        private const val PREFS_NAME = "nexusts_oauth_config"
        private const val KEY_RUNTIME_WEB_CLIENT_ID = "web_client_id"
        private const val KEY_RUNTIME_SHARED_FOLDER_ID = "shared_drive_folder_id"
        private const val KEY_SIGNED_IN_EMAIL = "signed_in_email"
        private const val KEY_SIGNED_IN_DISPLAY_NAME = "signed_in_display_name"
        private const val KEY_AUTO_SYNC_ON_SAVE = "auto_sync_on_save"
        private const val KEY_REMEMBER_VAULT_PASSPHRASE = "remember_vault_passphrase"
        private const val KEY_SEALED_DEFAULT_PASSPHRASE = "sealed_vault_passphrase_default"
        private const val KEY_SEALED_DOC_PASSPHRASE_PREFIX = "sealed_vault_passphrase_doc_"
        private const val KEY_FALLBACK_WRAP_KEY = "sealed_vault_fallback_wrap_key"
        private const val KEYSTORE_PASSPHRASE_ALIAS = "nexusts_vault_passphrase_aes256_v1"
    }
}
