package com.nexusts.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.nexusts.app.core.gdrivesync.auth.GoogleDriveAuthManager
import com.nexusts.app.core.gdrivesync.auth.GoogleDriveConsentResolutionRequiredException
import com.nexusts.app.core.gdrivesync.sync.DriveStorageZone
import com.nexusts.app.core.gdrivesync.sync.GoogleDriveVaultSyncEngine
import com.nexusts.app.core.gdrivesync.sync.SafDriveSyncOutcome
import com.nexusts.app.core.gdrivesync.sync.SafLiveGoogleDriveBridge
import com.nexusts.app.core.gdrivesync.sync.VaultSyncResult
import com.nexusts.app.core.ui.theme.NexusTSTheme
import com.nexusts.app.core.ui.workspace.AdHocConnectionSyntaxParser
import com.nexusts.app.core.ui.workspace.GooglebookWindowController
import com.nexusts.app.core.ui.workspace.QuickLaunchPlacement
import com.nexusts.app.core.ui.workspace.WorkspaceSessionTab
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.feature.workspace.NexusWorkspaceScreen
import com.nexusts.app.feature.workspace.NexusWorkspaceViewModel
import com.nexusts.app.updater.GitHubReleaseUpdateChecker
import com.nexusts.app.updater.PlayProtectCompliantUpdateCoordinator
import com.nexusts.app.updater.PlayUpdateUiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Primary Adaptive Googlebook OS & Android Tablet Desktop Window Activity (`v1.1`).
 * Handles:
 * - Direct `.nxts`, `.rtsz`, KeePass XML, Bitwarden JSON, 1Password `.1pux`, & CSV Vault opening
 * - Dual-Engine Live Google Drive Synchronization:
 *   1. Zero-Cloud-Console Persistent SAF Bridge (`SafLiveGoogleDriveBridge`) + 3-Way Delta Merge
 *   2. Google Drive REST API v3 (`AuthorizationClient` OAuth 2.0 + `GoogleDriveVaultSyncEngine`)
 * - URI scheme launches (`ssh://`, `rdp://`, `vnc://`, `sftp://`, `serial://`, `k8s://`, `docker://`, `nexusts://`)
 * - Multi-monitor & freeform tab detachment into `DetachedSessionActivity`
 * - Play-Protect-Compliant `AppUpdateManager` & Direct-Install GitHub Releases Update Checker
 * - Android 13+ Per-App Language Preferences dynamic switching
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val workspaceViewModel: NexusWorkspaceViewModel by viewModels()

    @Inject
    lateinit var windowController: GooglebookWindowController

    @Inject
    lateinit var updateCoordinator: PlayProtectCompliantUpdateCoordinator

    @Inject
    lateinit var driveAuthManager: GoogleDriveAuthManager

    @Inject
    lateinit var driveSyncEngine: GoogleDriveVaultSyncEngine

    @Inject
    lateinit var safDriveBridge: SafLiveGoogleDriveBridge

    @Inject
    lateinit var vaultSerializer: NxtsVaultSerializer

    private val githubUpdateChecker = GitHubReleaseUpdateChecker()

    private val playUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* Play Core handles installation flow & emits InstallState updates */ }

    private val driveOAuthConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            lifecycleScope.launch {
                runCatching {
                    driveAuthManager.completeAuthorizationFromIntent(this@MainActivity, result.data)
                }.onSuccess { identity ->
                    workspaceViewModel.updateDriveAccountAndSafState(signedInEmail = identity.email)
                    executeOAuthDriveSync(identity.oauthAccessToken, identity.email)
                }.onFailure { err ->
                    workspaceViewModel.updateDriveSyncProgress(false, "OAuth 2.0 consent failed: ${err.message}")
                }
            }
        } else {
            workspaceViewModel.updateDriveSyncProgress(false, "Google Drive OAuth 2.0 consent canceled.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Hydrate persisted Google Drive SAF & OAuth 2.0 state on startup
        val (persistedEmail, _) = driveAuthManager.readSignedInAccount()
        val persistedSaf = safDriveBridge.getLinkedVaultInfo()
        workspaceViewModel.updateDriveAccountAndSafState(
            signedInEmail = persistedEmail,
            linkedSafLabel = persistedSaf?.displayName.orEmpty(),
            autoSyncOnSave = driveAuthManager.isAutoSyncOnSaveEnabled()
        )
        GoogleDriveVaultSyncEngine.schedulePeriodicSync(this)

        updateCoordinator.registerFlexibleListener()
        lifecycleScope.launch {
            updateCoordinator.checkAndTriggerPlayUpdate(playUpdateLauncher)
            // For direct-installed APKs (outside Google Play), run non-blocking GitHub Releases check
            val installerPkg = runCatching {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            }.getOrNull()
            if (installerPkg != "com.android.vending") {
                checkGitHubReleases(silentWhenUpToDate = true)
            }
        }

        handleIncomingIntent(intent)

        setContent {
            val displays by windowController.observeDisplays()
                .collectAsState(initial = windowController.queryConnectedDisplays())
            val updateState by updateCoordinator.updateState.collectAsState()
            val uiState by workspaceViewModel.uiState.collectAsState()
            val localizedStrings = rememberLocalizedWorkspaceStrings()

            val (savedClientId, savedFolderId) = remember(driveAuthManager) {
                driveAuthManager.readRuntimeOAuthConfiguration()
            }
            val effectiveInitialClientId = remember(driveAuthManager, savedClientId) {
                driveAuthManager.resolveConfiguredWebClientId(BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID)
            }

            var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }
            var lastObservedAutoSyncRev by remember { mutableLongStateOf(uiState.vaultDocument.revision) }

            // Auto-sync encrypted .nxts to Google Drive whenever vault mutations bump revision
            LaunchedEffect(uiState.vaultDocument.revision, uiState.driveAutoSyncOnSave) {
                val rev = uiState.vaultDocument.revision
                if (uiState.driveAutoSyncOnSave && rev > lastObservedAutoSyncRev) {
                    lastObservedAutoSyncRev = rev
                    if (safDriveBridge.getLinkedVaultInfo() != null) {
                        executeSafDriveSync()
                    } else if (uiState.driveSignedInEmail.isNotBlank()) {
                        val zone = if (uiState.useSharedTeamDriveZone) {
                            DriveStorageZone.SHARED_TEAM_DRIVE
                        } else {
                            DriveStorageZone.APPDATA_PRIVATE
                        }
                        runCatching {
                            driveSyncEngine.stageLocalEncryptedVaultForSync(
                                document = uiState.vaultDocument,
                                passphrase = DEFAULT_LIVE_DRIVE_PASSPHRASE.toCharArray(),
                                zone = zone,
                                sharedFolderId = driveAuthManager.resolveConfiguredSharedFolderId()
                            )
                            GoogleDriveVaultSyncEngine.triggerImmediateDeltaSync(this@MainActivity)
                        }
                    }
                } else {
                    lastObservedAutoSyncRev = rev
                }
            }

            val fileImportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    importVaultUri(uri)
                }
            }

            val fileExportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("*/*")
            ) { uri: Uri? ->
                val payload = pendingExportBytes
                pendingExportBytes = null
                if (uri != null && payload != null) {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(payload)
                            stream.flush()
                        }
                    }
                }
            }

            val createLiveSafVaultLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/octet-stream")
            ) { uri: Uri? ->
                if (uri != null) {
                    val linked = safDriveBridge.linkPersistentVaultUri(uri)
                    workspaceViewModel.updateDriveAccountAndSafState(linkedSafLabel = linked.displayName)
                    lifecycleScope.launch { executeSafDriveSync() }
                }
            }

            val linkExistingSafVaultLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    val linked = safDriveBridge.linkPersistentVaultUri(uri)
                    workspaceViewModel.updateDriveAccountAndSafState(linkedSafLabel = linked.displayName)
                    lifecycleScope.launch { executeSafDriveSync() }
                }
            }

            NexusTSTheme {
                NexusWorkspaceScreen(
                    viewModel = workspaceViewModel,
                    strings = localizedStrings,
                    connectedDisplays = displays,
                    flexibleUpdateDownloaded = updateState is PlayUpdateUiState.FlexibleReadyToInstall,
                    onCompleteFlexibleUpdate = { updateCoordinator.completeFlexibleUpdate() },
                    onRequestImportFilePicker = {
                        fileImportLauncher.launch(arrayOf("*/*"))
                    },
                    onRequestGoogleDriveSync = {
                        triggerLiveGoogleDriveSync()
                    },
                    onDetachTabToNativeWindow = { tab, targetDisplayId ->
                        val payloadJson = Json.encodeToString(WorkspaceSessionTab.serializer(), tab)
                        windowController.launchDetachedSessionWindow(
                            sourceActivity = this@MainActivity,
                            detachedActivityClass = DetachedSessionActivity::class.java,
                            serializedTabJson = payloadJson,
                            targetDisplayId = targetDisplayId
                        )
                    },
                    onRequestExportVault = { format ->
                        val (suggestedFileName, payloadBytes) = workspaceViewModel.buildVaultExportPayload(format)
                        pendingExportBytes = payloadBytes
                        fileExportLauncher.launch(suggestedFileName)
                    },
                    initialOAuthWebClientId = effectiveInitialClientId,
                    initialSharedDriveFolderId = savedFolderId,
                    onSaveOAuthConfig = { clientId, folderId ->
                        driveAuthManager.saveRuntimeOAuthConfiguration(clientId, folderId)
                    },
                    onCreateLiveSafDriveVault = {
                        val baseName = uiState.vaultDocument.name
                            .let { if (it.endsWith(".nxts", true)) it else "$it.nxts" }
                        createLiveSafVaultLauncher.launch(baseName)
                    },
                    onLinkExistingSafDriveVault = {
                        linkExistingSafVaultLauncher.launch(arrayOf("*/*"))
                    },
                    onUnlinkSafDriveVault = {
                        safDriveBridge.unlinkPersistentVaultUri()
                        workspaceViewModel.updateDriveAccountAndSafState(linkedSafLabel = "")
                        workspaceViewModel.updateDriveSyncProgress(false, "Unlinked persistent Google Drive SAF vault.")
                    },
                    onAuthorizeAndSyncOAuthDrive = {
                        authorizeAndExecuteOAuthDriveSync()
                    },
                    onSignOutOAuthDrive = {
                        driveAuthManager.signOut()
                        workspaceViewModel.updateDriveAccountAndSafState(signedInEmail = "")
                        workspaceViewModel.updateDriveSyncProgress(false, "Signed out of Google Drive REST API v3.")
                    },
                    onToggleAutoSyncOnSave = { enabled ->
                        driveAuthManager.setAutoSyncOnSaveEnabled(enabled)
                        workspaceViewModel.setDriveAutoSyncOnSave(enabled)
                    },
                    onCheckGitHubReleases = {
                        lifecycleScope.launch { checkGitHubReleases(silentWhenUpToDate = false) }
                    },
                    onOpenGitHubReleaseUrl = { url ->
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        updateCoordinator.unregisterFlexibleListener()
        super.onDestroy()
    }

    private fun triggerLiveGoogleDriveSync() {
        if (safDriveBridge.getLinkedVaultInfo() != null) {
            lifecycleScope.launch { executeSafDriveSync() }
            return
        }
        authorizeAndExecuteOAuthDriveSync()
        GoogleDriveVaultSyncEngine.triggerImmediateDeltaSync(this)
    }

    private suspend fun executeSafDriveSync() {
        val linked = safDriveBridge.getLinkedVaultInfo() ?: return
        workspaceViewModel.updateDriveSyncProgress(true, "Synchronizing '${linked.displayName}' with Google Drive…")
        runCatching {
            safDriveBridge.synchronizeLinkedSafVault(
                currentDocument = workspaceViewModel.uiState.value.vaultDocument,
                vaultPassphrase = DEFAULT_LIVE_DRIVE_PASSPHRASE.toCharArray()
            )
        }.onSuccess { outcome ->
            when (outcome) {
                is SafDriveSyncOutcome.AlreadyUpToDate ->
                    workspaceViewModel.updateDriveSyncProgress(
                        false,
                        "Google Drive '${linked.displayName}' is up to date (Revision ${outcome.revision})."
                    )
                is SafDriveSyncOutcome.PushedToDrive ->
                    workspaceViewModel.applySyncedVaultDocument(
                        outcome.updatedDocument,
                        "Pushed encrypted .nxts (${outcome.bytesWritten} B) to Google Drive '${linked.displayName}' (Rev ${outcome.updatedDocument.revision})."
                    )
                is SafDriveSyncOutcome.PulledFromDrive ->
                    workspaceViewModel.applySyncedVaultDocument(
                        outcome.remoteDocument,
                        "Pulled latest Google Drive vault '${outcome.remoteDocument.name}' (Rev ${outcome.remoteDocument.revision})."
                    )
                is SafDriveSyncOutcome.ThreeWayMerged ->
                    workspaceViewModel.applySyncedVaultDocument(
                        outcome.mergedDocument,
                        "3-Way Vector-Clock Merge synced to Google Drive (${outcome.resolvedConflicts.size} conflict(s) resolved, Rev ${outcome.mergedDocument.revision})."
                    )
            }
        }.onFailure { err ->
            workspaceViewModel.updateDriveSyncProgress(
                false,
                "Google Drive SAF sync error: ${err.message}"
            )
        }
    }

    private fun authorizeAndExecuteOAuthDriveSync() {
        lifecycleScope.launch {
            workspaceViewModel.updateDriveSyncProgress(true, "Authorizing Google Drive API v3 (OAuth 2.0)…")
            try {
                val identity = driveAuthManager.signInAndAuthorizeDrive(
                    activity = this@MainActivity,
                    webClientId = BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID
                )
                workspaceViewModel.updateDriveAccountAndSafState(signedInEmail = identity.email)
                executeOAuthDriveSync(identity.oauthAccessToken, identity.email)
            } catch (consentRequired: GoogleDriveConsentResolutionRequiredException) {
                driveOAuthConsentLauncher.launch(
                    IntentSenderRequest.Builder(consentRequired.resolutionIntentSender).build()
                )
            } catch (err: Throwable) {
                workspaceViewModel.updateDriveSyncProgress(
                    false,
                    "OAuth 2.0 authorization requires GCP Android OAuth Client (or use Card 1 Zero-Setup SAF Sync): ${err.message}"
                )
            }
        }
    }

    private suspend fun executeOAuthDriveSync(accessToken: String, editorIdentity: String) {
        val state = workspaceViewModel.uiState.value
        val doc = state.vaultDocument
        val zone = if (state.useSharedTeamDriveZone) {
            DriveStorageZone.SHARED_TEAM_DRIVE
        } else {
            DriveStorageZone.APPDATA_PRIVATE
        }
        val folderId = driveAuthManager.resolveConfiguredSharedFolderId()

        runCatching {
            val passphrase = DEFAULT_LIVE_DRIVE_PASSPHRASE.toCharArray()
            driveSyncEngine.stageLocalEncryptedVaultForSync(
                document = doc,
                passphrase = passphrase,
                zone = zone,
                sharedFolderId = folderId
            )
            val drive = driveAuthManager.buildDriveService(accessToken)
            driveSyncEngine.synchronizeVault(
                drive = drive,
                documentId = doc.documentId,
                displayName = doc.name,
                zone = zone,
                parentFolderId = if (zone == DriveStorageZone.SHARED_TEAM_DRIVE) folderId else null,
                unlockedPassphraseOrNull = passphrase,
                localDeviceId = editorIdentity.ifBlank { "Googlebook-OS" }
            )
        }.onSuccess { result ->
            when (result) {
                is VaultSyncResult.UpToDate ->
                    workspaceViewModel.updateDriveSyncProgress(
                        false,
                        "Drive API v3 up to date (headRevision=${result.headRevisionId})."
                    )
                is VaultSyncResult.PushedNewRevision ->
                    workspaceViewModel.updateDriveSyncProgress(
                        false,
                        "Pushed Revision ${result.localRevision} to Google Drive API v3 (head=${result.newRevisionId})."
                    )
                is VaultSyncResult.PulledRemoteUpdate -> {
                    runCatching {
                        vaultSerializer.unsealDocument(result.encryptedBytes, DEFAULT_LIVE_DRIVE_PASSPHRASE.toCharArray())
                    }.onSuccess { pulledDoc ->
                        workspaceViewModel.applySyncedVaultDocument(
                            pulledDoc,
                            "Pulled remote vault from Google Drive API v3 (Rev ${pulledDoc.revision})."
                        )
                    }.onFailure {
                        workspaceViewModel.importVaultOrRtszBytes(doc.name, result.encryptedBytes)
                    }
                }
                is VaultSyncResult.DeltaMergedAndSynced ->
                    workspaceViewModel.applySyncedVaultDocument(
                        result.mergedDocument,
                        "Drive API v3 3-Way Delta Merge complete (${result.resolvedConflicts.size} conflict(s) resolved, Rev ${result.mergedDocument.revision})."
                    )
                is VaultSyncResult.RemoteLockActiveAlert ->
                    workspaceViewModel.updateDriveSyncProgress(
                        false,
                        "Advisory Team Lock held by ${result.warning.lockedByIdentity}."
                    )
            }
        }.onFailure { err ->
            workspaceViewModel.updateDriveSyncProgress(false, "Google Drive API v3 sync failed: ${err.message}")
        }
    }

    private suspend fun checkGitHubReleases(silentWhenUpToDate: Boolean) {
        githubUpdateChecker.checkLatestRelease(BuildConfig.VERSION_NAME)
            .onSuccess { res ->
                if (res.isUpdateAvailable) {
                    val targetUrl = res.signedApkDownloadUrl ?: res.releaseHtmlUrl
                    workspaceViewModel.updateGitHubReleaseState(
                        banner = "Update available: ${res.latestTag} (installed: v${res.currentVersion})",
                        releaseUrl = targetUrl
                    )
                } else if (!silentWhenUpToDate) {
                    workspaceViewModel.updateGitHubReleaseState(
                        banner = "Up to date: v${res.currentVersion} matches latest GitHub Release (${res.latestTag}).",
                        releaseUrl = res.releaseHtmlUrl
                    )
                }
            }
            .onFailure { err ->
                if (!silentWhenUpToDate) {
                    workspaceViewModel.updateGitHubReleaseState(
                        banner = "GitHub Releases check failed: ${err.message}"
                    )
                }
            }
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        val dataUri = incoming?.data ?: return
        val scheme = dataUri.scheme?.lowercase().orEmpty()

        when (scheme) {
            "ssh", "rdp", "vnc", "sftp", "serial", "k8s", "kubectl", "docker", "nexusts" -> {
                AdHocConnectionSyntaxParser.parseOrNull(dataUri.toString())?.let { candidate ->
                    workspaceViewModel.launchQuickCandidate(
                        candidate = candidate,
                        placement = QuickLaunchPlacement.ACTIVE_PANE_TAB,
                        onDetachWindowRequested = {}
                    )
                }
            }
            "content", "file" -> importVaultUri(dataUri)
        }
    }

    private fun importVaultUri(uri: Uri) {
        runCatching {
            val fileName = resolveDisplayName(uri)
            contentResolver.openInputStream(uri)?.use { stream ->
                val rawBytes = stream.readBytes()
                workspaceViewModel.importVaultOrRtszBytes(fileName = fileName, bytes = rawBytes)
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme.equals("content", ignoreCase = true)) {
            runCatching {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        val queried = cursor.getString(nameIndex)?.trim()
                        if (!queried.isNullOrBlank()) return queried
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "vault.nxts"
    }

    companion object {
        private const val DEFAULT_LIVE_DRIVE_PASSPHRASE = "NexusTS-LiveDriveSync-ZeroKnowledge-v1"
    }
}
