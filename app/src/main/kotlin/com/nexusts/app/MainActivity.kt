package com.nexusts.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
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

            val fileImportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    importVaultUri(uri)
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
            val fileName = uri.lastPathSegment ?: "vault.rtsz"
            contentResolver.openInputStream(uri)?.use { stream ->
                val rawBytes = stream.readBytes()
                workspaceViewModel.importVaultOrRtszBytes(fileName = fileName, bytes = rawBytes)
            }
        }
    }
}
