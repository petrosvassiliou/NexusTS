package com.nexusts.app

import android.app.Activity
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.nexusts.app.core.gdrivesync.auth.GoogleDriveAuthManager
import com.nexusts.app.core.gdrivesync.auth.GoogleDriveConsentResolutionRequiredException
import com.nexusts.app.core.gdrivesync.sync.DriveStorageZone
import com.nexusts.app.core.gdrivesync.sync.GoogleDriveVaultSyncEngine
import com.nexusts.app.core.gdrivesync.sync.SafDriveSyncOutcome
import com.nexusts.app.core.gdrivesync.sync.SafLiveGoogleDriveBridge
import com.nexusts.app.core.gdrivesync.sync.VaultSyncResult
import com.nexusts.app.core.ui.input.NexusShortcutAction
import com.nexusts.app.core.ui.input.isSystemPassThroughKeyEvent
import com.nexusts.app.core.ui.theme.NexusTSTheme
import com.nexusts.app.core.ui.workspace.AdHocConnectionSyntaxParser
import com.nexusts.app.core.ui.workspace.GooglebookWindowController
import com.nexusts.app.core.ui.workspace.QuickLaunchPlacement
import com.nexusts.app.core.ui.workspace.WorkspaceSessionTab
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.feature.workspace.ActiveVaultSessionStore
import com.nexusts.app.feature.workspace.NexusWorkspaceScreen
import com.nexusts.app.feature.workspace.NexusWorkspaceViewModel
import com.nexusts.app.security.BiometricVaultUnlockManager
import com.nexusts.app.service.ActiveSessionsForegroundService
import com.nexusts.app.updater.GitHubReleaseCheckResult
import com.nexusts.app.updater.GitHubReleaseUpdateChecker
import com.nexusts.app.updater.PlayProtectCompliantUpdateCoordinator
import com.nexusts.app.updater.PlayUpdateUiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Primary Adaptive Googlebook OS & Android Tablet Desktop Window Activity (`v0.8.14`).
 * Handles:
 * - Atomic local `.nxts` vault persistence & cold-start restoration (`nexusts_local_vault.nxts`)
 * - Hardware-backed Biometric / Passkey / Device-Credential vault unlock (`BiometricVaultUnlockManager`), Quick-Lock, & Idle Auto-Lock
 * - Direct `.nxts`, Royal TS `.rtsz` / `.rtsx` / `.rjson`, KeePass `.kdbx` / XML, Bitwarden JSON, 1Password `.1pux` / `.1pif`, & CSV opening
 * - Window-level hardware keyboard shortcut interception (`Ctrl+K`, `Escape`, `Ctrl+N`, `Ctrl+T`, etc.) even with zero focused Compose node
 * - Automatic Live Google Drive Synchronization (on startup, on local save, and on remote Drive change via `ContentObserver` + foreground resume + background watcher)
 * - Persistent SAF Folder Redirection (`OpenDocumentTree` -> FreeRDP `RDPDR` `\\tsclient\<Share>`)
 * - URI scheme launches (`ssh://`, `rdp://`, `vnc://`, `sftp://`, `scp://`, `ftp://`, `ftps://`, `telnet://`, `serial://`, `k8s://`, `kubectl://`, `docker://`, `http://`, `https://`, `nexusts://`)
 * - Multi-monitor & freeform tab detachment into `DetachedSessionActivity` + seamless Re-Dock
 * - Play-Protect-Compliant `AppUpdateManager` & Verified Direct-Install GitHub Releases Auto-Update Coordinator
 * - Android 13+ Per-App Language Preferences dynamic switching across all 6 enterprise locales
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

    @Inject
    lateinit var biometricUnlockManager: BiometricVaultUnlockManager

    private val githubUpdateChecker = GitHubReleaseUpdateChecker()
    private var latestGitHubReleaseCheck: GitHubReleaseCheckResult? = null
    private var triggerFileImportFromActivity: (() -> Unit)? = null
    private val consumedShortcutKeyCodes = mutableSetOf<Int>()
    private val safSyncMutex = Mutex()
    private var debouncedSafSyncJob: Job? = null

    @Volatile
    private var pendingFollowUpSafSync: Boolean = false

    @Volatile
    private var lastUserInteractionElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()

    @Volatile
    private var isBiometricPromptActive: Boolean = false

    @Volatile
    private var autoBiometricDismissedWhileResumed: Boolean = false

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
        lastUserInteractionElapsedRealtimeMs = SystemClock.elapsedRealtime()

        // 1. Restore persisted local Argon2id + AES-256-GCM vault container before initializing sync.
        //    When Biometric Unlock is enabled, require BiometricPrompt or passphrase on cold start.
        val savedColdStartPass = if (biometricUnlockManager.isBiometricUnlockEnabled()) {
            null
        } else {
            driveAuthManager.readVaultPassphraseOrNull()
        }
        try {
            workspaceViewModel.sessionStore.loadPersistedVaultIfNeeded(
                filesDir = filesDir,
                serializer = vaultSerializer,
                candidatePassphrases = listOfNotNull(savedColdStartPass)
            )
        } finally {
            savedColdStartPass?.fill('\u0000')
        }
        syncBiometricAndVaultSecurityState()

        // 2. Hydrate persisted Google Drive SAF, Keystore Passphrase opt-in, & OAuth 2.0 state
        val (persistedEmail, _) = driveAuthManager.readSignedInAccount()
        val persistedSaf = safDriveBridge.getLinkedVaultInfo()
        workspaceViewModel.updateDriveAccountAndSafState(
            signedInEmail = persistedEmail,
            linkedSafLabel = persistedSaf?.displayName.orEmpty(),
            autoSyncOnSave = driveAuthManager.isAutoSyncOnSaveEnabled(),
            rememberVaultPassphrase = driveAuthManager.isRememberVaultPassphraseEnabled()
        )
        GoogleDriveVaultSyncEngine.scheduleBackgroundSync(this)

        workspaceViewModel.setAutoCheckUpdatesState(updateCoordinator.isAutoCheckEnabled())
        updateCoordinator.registerFlexibleListener()
        lifecycleScope.launch {
            updateCoordinator.checkAndTriggerPlayUpdate(playUpdateLauncher)
            // For direct-installed APKs (outside Google Play), run Play-Protect-compatible HTTPS GitHub Releases auto-check
            val installerPkg = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    packageManager.getInstallSourceInfo(packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getInstallerPackageName(packageName)
                }
            }.getOrNull()
            if (installerPkg != "com.android.vending") {
                if (updateCoordinator.isAutoCheckEnabled()) {
                    checkGitHubReleases(silentWhenUpToDate = true, triggeredAutomatically = true)
                }
                while (true) {
                    delay(PlayProtectCompliantUpdateCoordinator.AUTO_CHECK_INTERVAL_MS)
                    if (updateCoordinator.isAutoCheckEnabled()) {
                        checkGitHubReleases(silentWhenUpToDate = true, triggeredAutomatically = true)
                    }
                }
            }
        }

        handleIncomingIntent(intent)

        setContent {
            val displays by windowController.observeDisplays()
                .collectAsState(initial = windowController.queryConnectedDisplays())
            val updateState by updateCoordinator.updateState.collectAsState()
            val uiState by workspaceViewModel.uiState.collectAsState()
            val sharedVaultSnapshot by workspaceViewModel.sessionStore.snapshot.collectAsState()
            val rootPane by workspaceViewModel.tilingController.rootNode.collectAsState()
            val localizedStrings = rememberLocalizedWorkspaceStrings()

            val initialOAuthPair = remember(driveAuthManager) {
                driveAuthManager.readRuntimeOAuthConfiguration()
            }
            var savedClientId by remember { mutableStateOf(initialOAuthPair.first) }
            var savedFolderId by remember { mutableStateOf(initialOAuthPair.second) }
            val effectiveInitialClientId = remember(driveAuthManager, savedClientId) {
                driveAuthManager.resolveConfiguredWebClientId(BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID)
            }

            var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }
            var pendingExportFileName by remember { mutableStateOf("") }
            var lastObservedAutoSyncRev by remember { mutableLongStateOf(uiState.vaultDocument.revision) }

            // Atomically persist encrypted .nxts vault locally whenever vault mutations or lock events occur
            LaunchedEffect(sharedVaultSnapshot.mutationRevision) {
                if (!sharedVaultSnapshot.isStarterUninitialized && !sharedVaultSnapshot.hasPreImportBackup) {
                    withContext(Dispatchers.IO) {
                        val kp = driveAuthManager.readVaultPassphraseOrNull(sharedVaultSnapshot.vaultDocument.documentId)
                        try {
                            runCatching {
                                workspaceViewModel.sessionStore.persistVaultToDisk(filesDir, vaultSerializer, kp)
                            }.onFailure { err ->
                                workspaceViewModel.reportException(err, "Local Vault Save Failed")
                            }
                        } finally {
                            kp?.fill('\u0000')
                        }
                    }
                }
            }

            // Keep background SSH tunnels & RDP sessions alive across Googlebook OS virtual desktops
            LaunchedEffect(rootPane, sharedVaultSnapshot.isVaultLocked) {
                val activeCount = if (sharedVaultSnapshot.isVaultLocked) {
                    0
                } else {
                    val uniqueActiveTabIds = workspaceViewModel.tilingController.allActiveTabs().map { it.tabId }.toSet() +
                        workspaceViewModel.sessionStore.sshControllers.keys +
                        workspaceViewModel.sessionStore.rdpSessions.keys
                    uniqueActiveTabIds.size
                }
                ActiveSessionsForegroundService.syncSessionKeepAlive(this@MainActivity, activeCount)
            }

            // 0. Automatic Googlebook Fingerprint / Touch ID Sensor Activation whenever Vault is Locked
            LaunchedEffect(uiState.isVaultLocked, uiState.biometricUnlockEnabled, uiState.hasPreImportBackup) {
                if (uiState.isVaultLocked) {
                    if (uiState.biometricUnlockEnabled && !uiState.hasPreImportBackup) {
                        autoBiometricDismissedWhileResumed = false
                        delay(180L)
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            workspaceViewModel.uiState.value.isVaultLocked &&
                            !autoBiometricDismissedWhileResumed
                        ) {
                            triggerBiometricUnlockPrompt(isAutomatic = true)
                        }
                    }
                } else {
                    autoBiometricDismissedWhileResumed = false
                    // Automatically enroll custom passphrase for Fingerprint unlock on first manual unlock (unless opted out)
                    if (!biometricUnlockManager.hasEnrolledPassphrase() &&
                        !biometricUnlockManager.isUserExplicitlyOptedOut() &&
                        biometricUnlockManager.isBiometricOrDeviceCredentialAvailable()
                    ) {
                        resolveQuickLockPassphraseOrNull()?.let { pass ->
                            try {
                                biometricUnlockManager.enrollPassphrase(pass, enableBiometricUnlock = true)
                                syncBiometricAndVaultSecurityState()
                            } finally {
                                pass.fill('\u0000')
                            }
                        }
                    }
                }
            }

            // 1. Automatic Startup & Vault-Unlock Google Drive Sync
            LaunchedEffect(uiState.isVaultLocked, uiState.driveLinkedSafLabel, uiState.driveSignedInEmail) {
                if (!uiState.isVaultLocked) {
                    if (safDriveBridge.getLinkedVaultInfo() != null) {
                        executeSafDriveSync()
                        lastObservedAutoSyncRev = workspaceViewModel.uiState.value.vaultDocument.revision
                    } else if (uiState.driveSignedInEmail.isNotBlank()) {
                        GoogleDriveVaultSyncEngine.triggerImmediateDeltaSync(this@MainActivity)
                    }
                }
            }

            // 2. Live Remote Google Drive Change Detection + Automatic Biometric Re-Arm on Resume
            DisposableEffect(uiState.driveLinkedSafLabel, uiState.isVaultLocked) {
                val linkedInfo = safDriveBridge.getLinkedVaultInfo()
                var registeredObserver: ContentObserver? = null
                if (!uiState.isVaultLocked && linkedInfo != null) {
                    val targetUri = runCatching { Uri.parse(linkedInfo.uriString) }.getOrNull()
                    if (targetUri != null) {
                        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                            override fun onChange(selfChange: Boolean) {
                                scheduleDebouncedSafDriveSync(delayMs = 900L)
                            }
                        }
                        runCatching {
                            contentResolver.registerContentObserver(targetUri, true, observer)
                            registeredObserver = observer
                        }
                    }
                }

                val lifecycleObserver = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) {
                        autoBiometricDismissedWhileResumed = false
                    } else if (event == Lifecycle.Event.ON_RESUME) {
                        val cur = workspaceViewModel.uiState.value
                        if (cur.isVaultLocked) {
                            if (biometricUnlockManager.isBiometricUnlockEnabled() &&
                                !cur.hasPreImportBackup &&
                                !autoBiometricDismissedWhileResumed
                            ) {
                                lifecycleScope.launch {
                                    delay(150L)
                                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                        workspaceViewModel.uiState.value.isVaultLocked &&
                                        !autoBiometricDismissedWhileResumed
                                    ) {
                                        triggerBiometricUnlockPrompt(isAutomatic = true)
                                    }
                                }
                            }
                        } else {
                            if (safDriveBridge.getLinkedVaultInfo() != null) {
                                scheduleDebouncedSafDriveSync(delayMs = 400L)
                            } else if (cur.driveSignedInEmail.isNotBlank()) {
                                GoogleDriveVaultSyncEngine.triggerImmediateDeltaSync(this@MainActivity)
                            }
                        }
                    }
                }
                lifecycle.addObserver(lifecycleObserver)

                onDispose {
                    lifecycle.removeObserver(lifecycleObserver)
                    registeredObserver?.let { obs ->
                        runCatching { contentResolver.unregisterContentObserver(obs) }
                    }
                }
            }

            // 3. Lightweight Background Remote-Change Watcher (catches cloud DocumentsProvider updates without ContentObserver push)
            LaunchedEffect(uiState.driveLinkedSafLabel, uiState.isVaultLocked) {
                if (uiState.isVaultLocked || safDriveBridge.getLinkedVaultInfo() == null) return@LaunchedEffect
                while (true) {
                    delay(15_000L)
                    val cur = workspaceViewModel.uiState.value
                    if (cur.isVaultLocked || safDriveBridge.getLinkedVaultInfo() == null) break
                    if (!safSyncMutex.isLocked && safDriveBridge.hasRemoteContentChanged()) {
                        executeSafDriveSync()
                        lastObservedAutoSyncRev = workspaceViewModel.uiState.value.vaultDocument.revision
                    }
                }
            }

            // 4. Automatic Local-Change Sync whenever vault mutations bump revision
            LaunchedEffect(uiState.vaultDocument.revision, uiState.driveAutoSyncOnSave) {
                val rev = uiState.vaultDocument.revision
                if (!uiState.isVaultLocked && uiState.driveAutoSyncOnSave && rev > lastObservedAutoSyncRev) {
                    lastObservedAutoSyncRev = rev
                    if (safDriveBridge.getLinkedVaultInfo() != null) {
                        scheduleDebouncedSafDriveSync(delayMs = 900L)
                    } else if (uiState.driveSignedInEmail.isNotBlank()) {
                        val zone = if (uiState.useSharedTeamDriveZone) {
                            DriveStorageZone.SHARED_TEAM_DRIVE
                        } else {
                            DriveStorageZone.APPDATA_PRIVATE
                        }
                        val pass = resolveEffectiveSyncPassphrase(uiState.vaultDocument.documentId)
                        try {
                            runCatching {
                                driveSyncEngine.stageLocalEncryptedVaultForSync(
                                    document = uiState.vaultDocument,
                                    passphrase = pass.copyOf(),
                                    zone = zone,
                                    sharedFolderId = driveAuthManager.resolveConfiguredSharedFolderId()
                                )
                                GoogleDriveVaultSyncEngine.triggerImmediateDeltaSync(this@MainActivity)
                            }.onFailure { err ->
                                workspaceViewModel.updateDriveSyncProgress(
                                    false,
                                    "Google Drive auto-sync staging failed: ${err.message}"
                                )
                            }
                        } finally {
                            pass.fill('\u0000')
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

            SideEffect {
                triggerFileImportFromActivity = { fileImportLauncher.launch(arrayOf("*/*")) }
            }

            val fileExportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("*/*")
            ) { uri: Uri? ->
                val payload = pendingExportBytes
                val targetName = pendingExportFileName.ifBlank { "vault-export" }
                pendingExportBytes = null
                pendingExportFileName = ""
                if (uri != null && payload != null) {
                    lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val stream = contentResolver.openOutputStream(uri)
                                    ?: throw IllegalStateException("Could not open output stream for '$targetName'.")
                                stream.use {
                                    it.write(payload)
                                    it.flush()
                                }
                            }
                        }.onSuccess {
                            workspaceViewModel.notifyUserMessage("Exported '$targetName' (${payload.size} bytes).")
                        }.onFailure { err ->
                            workspaceViewModel.reportException(err, "Export Failed ('$targetName')")
                        }
                    }
                }
            }

            val createLiveSafVaultLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/octet-stream")
            ) { uri: Uri? ->
                if (uri != null) {
                    val linked = safDriveBridge.linkPersistentVaultUri(
                        uri = uri,
                        documentId = uiState.vaultDocument.documentId
                    )
                    workspaceViewModel.updateDriveAccountAndSafState(linkedSafLabel = linked.displayName)
                    lifecycleScope.launch { executeSafDriveSync() }
                }
            }

            val linkExistingSafVaultLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    val linked = safDriveBridge.linkPersistentVaultUri(uri = uri, documentId = null)
                    workspaceViewModel.updateDriveAccountAndSafState(linkedSafLabel = linked.displayName)
                    lifecycleScope.launch { executeSafDriveSync() }
                }
            }

            var pendingRdpSafFolderCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
            val pickRdpSafFolderLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { treeUri: Uri? ->
                val callback = pendingRdpSafFolderCallback
                pendingRdpSafFolderCallback = null
                if (treeUri != null && callback != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }.onFailure { err ->
                        workspaceViewModel.reportException(err, "SAF Folder Permission Failed")
                    }
                    callback(treeUri.toString())
                }
            }

            LaunchedEffect(sharedVaultSnapshot.isVaultLocked, sharedVaultSnapshot.mutationRevision) {
                syncBiometricAndVaultSecurityState()
            }

            LaunchedEffect(uiState.idleAutoLockMinutes, uiState.isVaultLocked) {
                if (uiState.isVaultLocked || uiState.idleAutoLockMinutes <= 0) return@LaunchedEffect
                lastUserInteractionElapsedRealtimeMs = SystemClock.elapsedRealtime()
                val timeoutMs = uiState.idleAutoLockMinutes * 60_000L
                while (true) {
                    delay(5_000L)
                    val currentState = workspaceViewModel.uiState.value
                    if (currentState.isVaultLocked || currentState.idleAutoLockMinutes <= 0) break
                    val idleElapsed = SystemClock.elapsedRealtime() - lastUserInteractionElapsedRealtimeMs
                    if (idleElapsed >= timeoutMs) {
                        performQuickOrIdleLock(isIdleAutoLock = true)
                        break
                    }
                }
            }

            LaunchedEffect(uiState.isFullScreenSessionMode) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = true
                controller.isAppearanceLightNavigationBars = true
                if (uiState.isFullScreenSessionMode) {
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
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
                        launchDetachedTabActivity(tab, targetDisplayId)
                    },
                    onRequestExportVault = { format ->
                        lifecycleScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    workspaceViewModel.buildVaultExportPayload(format)
                                }
                            }.onSuccess { (suggestedFileName, payloadBytes) ->
                                pendingExportBytes = payloadBytes
                                pendingExportFileName = suggestedFileName
                                fileExportLauncher.launch(suggestedFileName)
                            }.onFailure { err ->
                                workspaceViewModel.reportException(err, "Vault Export Failed")
                            }
                        }
                    },
                    initialOAuthWebClientId = effectiveInitialClientId,
                    initialSharedDriveFolderId = savedFolderId,
                    onSaveOAuthConfig = { clientId, folderId ->
                        driveAuthManager.saveRuntimeOAuthConfiguration(clientId, folderId)
                        val refreshed = driveAuthManager.readRuntimeOAuthConfiguration()
                        savedClientId = refreshed.first
                        savedFolderId = refreshed.second
                    },
                    onSaveVaultSyncPassphrase = { pass, rememberInKeystore ->
                        val chars = pass.toCharArray()
                        val currentSnap = workspaceViewModel.sessionStore.snapshot.value
                        workspaceViewModel.sessionStore.setActivePassphrase(chars)
                        driveAuthManager.saveVaultPassphrasePreference(
                            passphrase = chars,
                            rememberPassphrase = rememberInKeystore,
                            documentId = currentSnap.vaultDocument.documentId.takeIf { rememberInKeystore }
                        )
                        workspaceViewModel.updateDriveAccountAndSafState(
                            rememberVaultPassphrase = driveAuthManager.isRememberVaultPassphraseEnabled()
                        )
                        syncBiometricAndVaultSecurityState()
                        lifecycleScope.launch {
                            try {
                                if (!currentSnap.isStarterUninitialized && !currentSnap.hasPreImportBackup) {
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            workspaceViewModel.sessionStore.persistVaultToDisk(filesDir, vaultSerializer, chars)
                                        }.onFailure { err ->
                                            workspaceViewModel.reportException(err, "Local Vault Save Failed")
                                        }
                                    }
                                }
                            } finally {
                                chars.fill('\u0000')
                            }
                            if (safDriveBridge.getLinkedVaultInfo() != null) {
                                executeSafDriveSync()
                            } else if (workspaceViewModel.uiState.value.driveSignedInEmail.isNotBlank()) {
                                GoogleDriveVaultSyncEngine.triggerImmediateDeltaSync(this@MainActivity)
                            }
                        }
                    },
                    onClearVaultSyncPassphrase = {
                        driveAuthManager.clearSavedVaultPassphrase()
                        workspaceViewModel.updateDriveAccountAndSafState(rememberVaultPassphrase = false)
                        syncBiometricAndVaultSecurityState()
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
                        lifecycleScope.launch {
                            checkGitHubReleases(silentWhenUpToDate = false, triggeredAutomatically = false)
                        }
                    },
                    onOpenGitHubReleaseUrl = { url ->
                        updateCoordinator.launchTrustedBrowserUpdate(this@MainActivity, url)
                    },
                    onToggleAutoCheckUpdates = { enabled ->
                        updateCoordinator.setAutoCheckEnabled(enabled)
                        workspaceViewModel.setAutoCheckUpdatesState(enabled)
                    },
                    onTriggerBrowserUpdate = { url ->
                        if (!updateCoordinator.launchTrustedBrowserUpdate(this@MainActivity, url)) {
                            workspaceViewModel.setUpdateVerificationStatus(
                                "Blocked untrusted update URL (HTTPS github.com allowlist enforced)."
                            )
                        }
                    },
                    onDownloadVerifiedUpdateToFiles = {
                        val release = latestGitHubReleaseCheck
                        if (release != null) {
                            lifecycleScope.launch {
                                workspaceViewModel.setUpdateVerificationStatus(
                                    "Downloading & verifying ${release.apkFileName.ifBlank { release.latestTag }} via Android DownloadManager…"
                                )
                                updateCoordinator.downloadAndVerifyViaSystemManager(release)
                                    .onSuccess { summary ->
                                        workspaceViewModel.setUpdateVerificationStatus(
                                            "Verified ${summary.fileName} (SHA-256 ${summary.fileSha256Hex.take(16)}…, APK signer ${summary.signerCertSha256Hex.take(16)}…). Opening Android Downloads…"
                                        )
                                        updateCoordinator.openSystemDownloadsFolder(this@MainActivity)
                                    }
                                    .onFailure { err ->
                                        workspaceViewModel.setUpdateVerificationStatus(
                                            "Update verification failed (package purged): ${err.message}"
                                        )
                                    }
                            }
                        }
                    },
                    onSkipUpdateVersion = { tag ->
                        updateCoordinator.setSkippedVersionTag(tag)
                        workspaceViewModel.notifyUserMessage("Skipped update prompt for $tag.")
                    },
                    onRequestBiometricUnlock = {
                        triggerBiometricUnlockPrompt(isAutomatic = false)
                    },
                    onQuickLockVault = {
                        performQuickOrIdleLock(isIdleAutoLock = false)
                    },
                    onToggleBiometricUnlock = { enabled ->
                        if (!enabled) {
                            biometricUnlockManager.setBiometricUnlockEnabled(false)
                        } else if (biometricUnlockManager.hasEnrolledPassphrase()) {
                            biometricUnlockManager.setBiometricUnlockEnabled(true)
                        } else {
                            resolveQuickLockPassphraseOrNull()?.let { pass ->
                                try {
                                    biometricUnlockManager.enrollPassphrase(pass, enableBiometricUnlock = true)
                                } finally {
                                    pass.fill('\u0000')
                                }
                            }
                        }
                        syncBiometricAndVaultSecurityState()
                    },
                    onEnrollBiometricPassphrase = { chars, biometricChecked ->
                        try {
                            biometricUnlockManager.enrollPassphrase(
                                passphrase = chars,
                                enableBiometricUnlock = biometricChecked
                            )
                            syncBiometricAndVaultSecurityState()
                        } finally {
                            chars.fill('\u0000')
                        }
                    },
                    onChangeIdleAutoLockMinutes = { minutes ->
                        biometricUnlockManager.setIdleAutoLockMinutes(minutes)
                        lastUserInteractionElapsedRealtimeMs = SystemClock.elapsedRealtime()
                        syncBiometricAndVaultSecurityState()
                    },
                    onPickRdpSharedSafFolder = { onFolderSelected ->
                        pendingRdpSafFolderCallback = onFolderSelected
                        pickRdpSafFolderLauncher.launch(null)
                    },
                    onResetNexusDatabase = {
                        lifecycleScope.launch {
                            runCatching {
                                biometricUnlockManager.clearEnrolledPassphrase()
                                driveAuthManager.clearSavedVaultPassphrase()
                                withContext(Dispatchers.IO) {
                                    workspaceViewModel.sessionStore.resetLocalVaultDatabase(filesDir, vaultSerializer)
                                }
                            }.onSuccess { freshDoc ->
                                workspaceViewModel.updateDriveAccountAndSafState(rememberVaultPassphrase = false)
                                workspaceViewModel.resetWorkspaceStateAfterDatabaseWipe(
                                    freshDoc = freshDoc,
                                    confirmationMessage = localizedStrings.resetDatabaseSuccess
                                )
                                syncBiometricAndVaultSecurityState()
                            }.onFailure { err ->
                                workspaceViewModel.reportException(err, "Database Reset Failed")
                            }
                        }
                    }
                )
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastUserInteractionElapsedRealtimeMs = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        lastUserInteractionElapsedRealtimeMs = SystemClock.elapsedRealtime()
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastUserInteractionElapsedRealtimeMs = SystemClock.elapsedRealtime()
        if (isSystemPassThroughKeyEvent(event)) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_UP && consumedShortcutKeyCodes.remove(event.keyCode)) {
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val state = workspaceViewModel.uiState.value
            if (!state.isVaultLocked && event.keyCode == KeyEvent.KEYCODE_ESCAPE && state.isQuickConnectVisible) {
                workspaceViewModel.dismissQuickConnect()
                consumedShortcutKeyCodes.add(event.keyCode)
                return true
            }
            val handled = workspaceViewModel.shortcutEngine.dispatchAndroidKeyEvent(
                native = event,
                remoteCaptureActive = state.remoteKeyCaptureEnabled
            ) { action ->
                if (state.isVaultLocked && action != NexusShortcutAction.OPEN_VAULT_DOCUMENT) {
                    false
                } else {
                    workspaceViewModel.handleShortcut(
                        action = action,
                        onOpenVaultPicker = { triggerFileImportFromActivity?.invoke() },
                        onSyncDriveNow = { triggerLiveGoogleDriveSync() },
                        onDetachActiveTab = { tab -> launchDetachedTabActivity(tab, null) }
                    )
                    true
                }
            }
            if (handled) {
                consumedShortcutKeyCodes.add(event.keyCode)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        triggerFileImportFromActivity = null
        updateCoordinator.unregisterFlexibleListener()
        super.onDestroy()
    }

    private fun triggerBiometricUnlockPrompt(isAutomatic: Boolean) {
        if (isBiometricPromptActive) return
        if (!biometricUnlockManager.isBiometricUnlockEnabled() ||
            !biometricUnlockManager.isBiometricOrDeviceCredentialAvailable()
        ) {
            if (!isAutomatic) {
                workspaceViewModel.notifyUserMessage(
                    "Biometric or device credential authentication is not configured on this device."
                )
            }
            return
        }
        if (!isAutomatic) {
            autoBiometricDismissedWhileResumed = false
        }
        isBiometricPromptActive = true
        biometricUnlockManager.authenticateAndUnsealPassphrase(
            activity = this,
            onSuccess = { chars ->
                isBiometricPromptActive = false
                autoBiometricDismissedWhileResumed = false
                workspaceViewModel.unlockVaultWithPassphrase(chars)
            },
            onError = { msg ->
                isBiometricPromptActive = false
                if (!isAutomatic) {
                    workspaceViewModel.notifyUserMessage(msg)
                }
            },
            onCanceled = {
                isBiometricPromptActive = false
                if (isAutomatic) {
                    autoBiometricDismissedWhileResumed = true
                }
            }
        )
    }

    private fun syncBiometricAndVaultSecurityState() {
        val defaultChars = ActiveVaultSessionStore.DEFAULT_LOCAL_VAULT_PASSPHRASE.toCharArray()
        val hasCustomSessionPass = workspaceViewModel.sessionStore.copyActivePassphraseOrNull()?.let { chars ->
            val isCustom = !chars.contentEquals(defaultChars)
            chars.fill('\u0000')
            isCustom
        } ?: false
        val hasSavedPassphrase = biometricUnlockManager.hasEnrolledPassphrase() ||
            hasCustomSessionPass ||
            driveAuthManager.isRememberVaultPassphraseEnabled()
        workspaceViewModel.updateBiometricAndAutoLockState(
            hardwareAvailable = biometricUnlockManager.isBiometricOrDeviceCredentialAvailable(),
            biometricEnabled = biometricUnlockManager.isBiometricUnlockEnabled(),
            hasSavedPassphrase = hasSavedPassphrase,
            idleAutoLockMinutes = biometricUnlockManager.getIdleAutoLockMinutes()
        )
    }

    private fun resolveQuickLockPassphraseOrNull(): CharArray? {
        val defaultChars = ActiveVaultSessionStore.DEFAULT_LOCAL_VAULT_PASSPHRASE.toCharArray()
        workspaceViewModel.sessionStore.copyActivePassphraseOrNull()?.let { chars ->
            if (!chars.contentEquals(defaultChars)) {
                return chars
            }
            chars.fill('\u0000')
        }
        biometricUnlockManager.copyEnrolledPassphraseOrNull()?.let { return it }
        val docId = workspaceViewModel.uiState.value.vaultDocument.documentId
        return driveAuthManager.readVaultPassphraseOrNull(docId)
    }

    private fun performQuickOrIdleLock(isIdleAutoLock: Boolean) {
        val pass = resolveQuickLockPassphraseOrNull()
        if (pass != null) {
            workspaceViewModel.lockVaultWithPassphrase(pass)
        } else {
            lastUserInteractionElapsedRealtimeMs = SystemClock.elapsedRealtime()
            workspaceViewModel.openLockVaultDialog()
            if (isIdleAutoLock) {
                workspaceViewModel.notifyUserMessage(
                    "Idle auto-lock reached: enter a vault passphrase once to enable automatic vault locking."
                )
            }
        }
    }

    private fun launchDetachedTabActivity(tab: WorkspaceSessionTab, targetDisplayId: Int?) {
        val payloadJson = Json.encodeToString(WorkspaceSessionTab.serializer(), tab)
        windowController.launchDetachedSessionWindow(
            sourceActivity = this,
            detachedActivityClass = DetachedSessionActivity::class.java,
            serializedTabJson = payloadJson,
            targetDisplayId = targetDisplayId
        )
    }

    private fun resolveEffectiveSyncPassphrase(docId: String? = null): CharArray {
        return driveAuthManager.readVaultPassphraseOrNull(docId)
            ?: workspaceViewModel.sessionStore.copyActivePassphraseOrDefault(DEFAULT_LIVE_DRIVE_PASSPHRASE)
    }

    private fun scheduleDebouncedSafDriveSync(delayMs: Long = 900L) {
        debouncedSafSyncJob?.cancel()
        debouncedSafSyncJob = lifecycleScope.launch {
            delay(delayMs)
            if (!workspaceViewModel.uiState.value.isVaultLocked) {
                executeSafDriveSync()
            }
        }
    }

    private fun triggerLiveGoogleDriveSync() {
        if (safDriveBridge.getLinkedVaultInfo() != null) {
            lifecycleScope.launch { executeSafDriveSync() }
            return
        }
        if (workspaceViewModel.uiState.value.driveSignedInEmail.isNotBlank()) {
            authorizeAndExecuteOAuthDriveSync()
            GoogleDriveVaultSyncEngine.triggerImmediateDeltaSync(this)
        } else {
            workspaceViewModel.selectRailDestination("sync")
        }
    }

    private suspend fun executeSafDriveSync() {
        if (!safSyncMutex.tryLock()) {
            pendingFollowUpSafSync = true
            return
        }
        try {
            val linked = safDriveBridge.getLinkedVaultInfo() ?: return
            val snapshotState = workspaceViewModel.uiState.value
            if (snapshotState.isVaultLocked) return
            workspaceViewModel.updateDriveSyncProgress(true, "Syncing '${linked.displayName}' with Google Drive…")
            val pass = resolveEffectiveSyncPassphrase(snapshotState.vaultDocument.documentId)
            try {
                runCatching {
                    safDriveBridge.synchronizeLinkedSafVault(
                        currentDocument = snapshotState.vaultDocument,
                        vaultPassphrase = pass.copyOf(),
                        localDeviceId = "Googlebook-OS",
                        localIsUninitializedStarter = snapshotState.isStarterUninitialized
                    )
                }.onSuccess { outcome ->
                    val syncedDoc = when (outcome) {
                        is SafDriveSyncOutcome.AlreadyUpToDate -> null
                        is SafDriveSyncOutcome.PushedToDrive -> outcome.updatedDocument
                        is SafDriveSyncOutcome.PulledFromDrive -> outcome.remoteDocument
                        is SafDriveSyncOutcome.ThreeWayMerged -> outcome.mergedDocument
                    }
                    if (syncedDoc != null && String(pass) != DEFAULT_LIVE_DRIVE_PASSPHRASE) {
                        workspaceViewModel.sessionStore.setActivePassphrase(pass)
                        if (driveAuthManager.isRememberVaultPassphraseEnabled()) {
                            driveAuthManager.saveVaultPassphrasePreference(
                                passphrase = pass,
                                rememberPassphrase = true,
                                documentId = syncedDoc.documentId
                            )
                        }
                    }
                    when (outcome) {
                        is SafDriveSyncOutcome.AlreadyUpToDate ->
                            workspaceViewModel.updateDriveSyncProgress(
                                false,
                                "Synced with '${linked.displayName}' (Revision ${outcome.revision})."
                            )
                        is SafDriveSyncOutcome.PushedToDrive ->
                            workspaceViewModel.applySyncedVaultDocument(
                                outcome.updatedDocument,
                                "Synced changes to '${linked.displayName}' (Revision ${outcome.updatedDocument.revision})."
                            )
                        is SafDriveSyncOutcome.PulledFromDrive ->
                            workspaceViewModel.applySyncedVaultDocument(
                                outcome.remoteDocument,
                                "Updated from Google Drive '${linked.displayName}' (Revision ${outcome.remoteDocument.revision})."
                            )
                        is SafDriveSyncOutcome.ThreeWayMerged ->
                            workspaceViewModel.applySyncedVaultDocument(
                                outcome.mergedDocument,
                                "Merged changes with '${linked.displayName}' (Revision ${outcome.mergedDocument.revision})."
                            )
                    }
                }.onFailure { err ->
                    val msg = if (err is com.nexusts.app.core.vault.crypto.VaultAuthenticationException) {
                        "Master passphrase required to unlock '${linked.displayName}' (Failed to unlock .nxts vault)."
                    } else {
                        "Google Drive sync error: ${err.message}"
                    }
                    workspaceViewModel.updateDriveSyncProgress(false, msg)
                }
            } finally {
                pass.fill('\u0000')
            }
        } finally {
            safSyncMutex.unlock()
            if (pendingFollowUpSafSync) {
                pendingFollowUpSafSync = false
                scheduleDebouncedSafDriveSync(delayMs = 600L)
            }
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
        val passphrase = resolveEffectiveSyncPassphrase(doc.documentId)

        try {
            runCatching {
                if (!state.isStarterUninitialized && !state.isVaultLocked) {
                    driveSyncEngine.stageLocalEncryptedVaultForSync(
                        document = doc,
                        passphrase = passphrase.copyOf(),
                        zone = zone,
                        sharedFolderId = folderId
                    )
                }
                val drive = driveAuthManager.buildDriveService(accessToken)
                driveSyncEngine.synchronizeVault(
                    drive = drive,
                    documentId = doc.documentId,
                    displayName = doc.name,
                    zone = zone,
                    parentFolderId = if (zone == DriveStorageZone.SHARED_TEAM_DRIVE) folderId else null,
                    unlockedPassphraseOrNull = passphrase.copyOf(),
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
                            vaultSerializer.unsealDocument(result.encryptedBytes, passphrase.copyOf())
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
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private suspend fun checkGitHubReleases(
        silentWhenUpToDate: Boolean,
        triggeredAutomatically: Boolean = false
    ) {
        workspaceViewModel.setCheckingForUpdates(true)
        githubUpdateChecker.checkLatestRelease(BuildConfig.VERSION_NAME)
            .onSuccess { res ->
                latestGitHubReleaseCheck = res
                updateCoordinator.recordLastCheckTimestamp()
                val isNotSkipped = res.latestTag != updateCoordinator.getSkippedVersionTag()
                val banner = when {
                    res.isUpdateAvailable ->
                        "Update available: ${res.latestTag} (installed: v${res.currentVersion})"
                    !silentWhenUpToDate ->
                        "Up to date: v${res.currentVersion} matches latest GitHub Release (${res.latestTag})."
                    else -> ""
                }
                workspaceViewModel.applyAutoUpdateCheckOutcome(
                    isUpdateAvailable = res.isUpdateAvailable,
                    latestTag = res.latestTag,
                    currentVersion = res.currentVersion,
                    releaseTitle = res.releaseTitle,
                    releaseNotes = res.releaseNotes,
                    sha256Hex = res.expectedApkSha256Hex.orEmpty(),
                    apkSizeBytes = res.apkSizeBytes,
                    publishedAtIso = res.publishedAtIso,
                    apkDownloadUrl = res.signedApkDownloadUrl.orEmpty(),
                    releaseHtmlUrl = res.releaseHtmlUrl,
                    bannerMessage = banner,
                    openDialogAutomatically = triggeredAutomatically && res.isUpdateAvailable && isNotSkipped
                )
            }
            .onFailure { err ->
                workspaceViewModel.setCheckingForUpdates(false)
                if (!silentWhenUpToDate) {
                    val msg = "GitHub Releases check failed: ${err.message}"
                    workspaceViewModel.updateGitHubReleaseState(banner = msg)
                    workspaceViewModel.setUpdateVerificationStatus(msg)
                }
            }
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        val dataUri = incoming?.data ?: return
        val scheme = dataUri.scheme?.lowercase().orEmpty()

        when (scheme) {
            "ssh", "rdp", "vnc", "sftp", "scp", "ftp", "ftps",
            "telnet", "serial", "k8s", "kubectl", "docker",
            "http", "https", "nexusts" -> {
                val parsed = AdHocConnectionSyntaxParser.parseOrNull(dataUri.toString())
                if (parsed != null) {
                    workspaceViewModel.launchQuickCandidate(
                        candidate = parsed,
                        placement = QuickLaunchPlacement.ACTIVE_PANE_TAB,
                        onDetachWindowRequested = { tab -> launchDetachedTabActivity(tab, null) }
                    )
                } else {
                    workspaceViewModel.reportException(
                        IllegalArgumentException("Invalid connection URI syntax: '$dataUri'"),
                        "Invalid Connection URI"
                    )
                }
            }
            "content", "file" -> importVaultUri(dataUri)
        }
    }

    private fun importVaultUri(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val fileName = resolveDisplayName(uri)
                    val stream = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Could not open selected file '$fileName'. Check storage permissions or cloud file availability.")
                    val rawBytes = stream.use { it.readBytes() }
                    fileName to rawBytes
                }
            }.onSuccess { (fileName, rawBytes) ->
                workspaceViewModel.importVaultOrRtszBytes(fileName = fileName, bytes = rawBytes)
            }.onFailure { err ->
                workspaceViewModel.reportImportFailure("Unable to read selected file: ${err.message}", err)
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
