package com.nexusts.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.nexusts.app.core.gdrivesync.auth.GoogleDriveAuthManager
import com.nexusts.app.core.gdrivesync.sync.GoogleDriveVaultSyncEngine
import com.nexusts.app.core.ui.theme.NexusTSTheme
import com.nexusts.app.core.ui.workspace.AdHocConnectionSyntaxParser
import com.nexusts.app.core.ui.workspace.GooglebookWindowController
import com.nexusts.app.core.ui.workspace.QuickLaunchPlacement
import com.nexusts.app.core.ui.workspace.WorkspaceSessionTab
import com.nexusts.app.feature.workspace.NexusWorkspaceScreen
import com.nexusts.app.feature.workspace.NexusWorkspaceViewModel
import com.nexusts.app.updater.PlayProtectCompliantUpdateCoordinator
import com.nexusts.app.updater.PlayUpdateUiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Primary Adaptive Googlebook OS & Android Tablet Desktop Window Activity.
 * Handles:
 * - Direct `.nxts` & `.rtsz` Vault file opening via `ACTION_VIEW` / `ACTION_EDIT`
 * - Vault & Inventory export to `.nxts`, `.rtsz`, and `.csv` via SAF `CreateDocument`
 * - URI scheme launches (`ssh://`, `rdp://`, `vnc://`, `sftp://`, `nexusts://`)
 * - Multi-monitor & freeform tab detachment into `DetachedSessionActivity`
 * - Play Protect Compliant `AppUpdateManager` Flexible & Immediate update flows
 * - Android 13+ Per-App Language Preferences dynamic switching
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val workspaceViewModel: NexusWorkspaceViewModel by viewModels()

    @Inject
    lateinit var windowController: GooglebookWindowController

    @Inject
    lateinit var updateCoordinator: PlayProtectCompliantUpdateCoordinator

    private val playUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* Play Core handles installation flow & emits InstallState updates */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        updateCoordinator.registerFlexibleListener()
        lifecycleScope.launch {
            updateCoordinator.checkAndTriggerPlayUpdate(playUpdateLauncher)
        }

        handleIncomingIntent(intent)

        setContent {
            val displays by windowController.observeDisplays()
                .collectAsState(initial = windowController.queryConnectedDisplays())
            val updateState by updateCoordinator.updateState.collectAsState()
            val localizedStrings = rememberLocalizedWorkspaceStrings()

            val driveAuthManager = remember { GoogleDriveAuthManager(applicationContext) }
            val (savedClientId, savedFolderId) = remember(driveAuthManager) {
                driveAuthManager.readRuntimeOAuthConfiguration()
            }
            val effectiveInitialClientId = remember(driveAuthManager, savedClientId) {
                driveAuthManager.resolveConfiguredWebClientId(BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID)
            }

            var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }

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
                        GoogleDriveVaultSyncEngine.triggerImmediateDeltaSync(this@MainActivity)
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

    private fun handleIncomingIntent(incoming: Intent?) {
        val dataUri = incoming?.data ?: return
        val scheme = dataUri.scheme?.lowercase().orEmpty()

        when (scheme) {
            "ssh", "rdp", "vnc", "sftp", "nexusts" -> {
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
}
