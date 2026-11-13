package com.nexusts.app.core.gdrivesync.auth

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.content.RestrictionsManager
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.tasks.await

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
 * Native Google Identity Services & Android Credential Manager OAuth 2.0 Authenticator
 * scoping strictly to `appDataFolder` (private zero-knowledge vaults) and `drive.file`
 * (explicitly shared team folders / Team Drives).
 */
class GoogleDriveAuthManager(
    private val appContext: Context
) {
    /**
     * Resolves the OAuth 2.0 Web Client ID from Android Enterprise `RestrictionsManager`
     * (`google_oauth_web_client_id`) first, falling back to compile-time `BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID`.
     */
    fun resolveConfiguredWebClientId(fallbackWebClientId: String = ""): String {
        val restrictions = appContext.getSystemService(RestrictionsManager::class.java)
            ?.applicationRestrictions
        val mdmClientId = restrictions?.getString("google_oauth_web_client_id")?.trim().orEmpty()
        return mdmClientId.ifBlank { fallbackWebClientId.trim() }
    }

    /**
     * Performs native Android Credential Manager sign-in + OAuth 2.0 Drive scope authorization.
     */
    suspend fun signInAndAuthorizeDrive(
        activity: Activity,
        webClientId: String = ""
    ): AuthenticatedGoogleIdentity {
        val effectiveClientId = resolveConfiguredWebClientId(webClientId)
        require(effectiveClientId.isNotBlank()) {
            "Google OAuth 2.0 Web Client ID is not configured. Set NEXUSTS_GOOGLE_WEB_CLIENT_ID or MDM google_oauth_web_client_id."
        }

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
            ?: error("Expected Google ID CustomCredential")
        val idTokenCred = GoogleIdTokenCredential.createFrom(customCred.data)

        val scopes = listOf(
            Scope(DriveScopes.DRIVE_APPDATA),
            Scope(DriveScopes.DRIVE_FILE)
        )
        val authRequest = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .build()

        val authResult = Identity.getAuthorizationClient(activity)
            .authorize(authRequest)
            .await()

        if (authResult.hasResolution()) {
            val pendingIntent = authResult.pendingIntent
            if (pendingIntent != null) {
                throw GoogleDriveConsentResolutionRequiredException(pendingIntent.intentSender)
            }
        }

        val accessToken = requireNotNull(authResult.accessToken) {
            "Google Drive OAuth 2.0 access token not granted"
        }

        return AuthenticatedGoogleIdentity(
            email = idTokenCred.id,
            displayName = idTokenCred.displayName,
            idToken = idTokenCred.idToken,
            oauthAccessToken = accessToken
        )
    }

    /**
     * Builds an authenticated Google Drive API v3 client using the OAuth 2.0 bearer token.
     */
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
            .setApplicationName("NexusTS/1.0 (Googlebook OS)")
            .build()
    }
}
