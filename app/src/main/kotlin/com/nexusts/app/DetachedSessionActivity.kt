package com.nexusts.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.components.ExpressiveMorphingIconButton
import com.nexusts.app.core.ui.input.LocalNexusShortcutEngine
import com.nexusts.app.core.ui.input.NexusShortcutAction
import com.nexusts.app.core.ui.input.RawHardwareKeyPacket
import com.nexusts.app.core.ui.input.isSystemPassThroughKeyEvent
import com.nexusts.app.core.ui.input.nexusRemoteKeyPassthrough
import com.nexusts.app.core.ui.theme.NexusTSTheme
import com.nexusts.app.core.ui.workspace.GooglebookWindowController
import com.nexusts.app.core.ui.workspace.SessionProtocolKind
import com.nexusts.app.core.ui.workspace.WorkspaceSessionTab
import com.nexusts.app.feature.workspace.ActiveProtocolSessionDispatcher
import com.nexusts.app.feature.workspace.FullScreenVaultUnlockShield
import com.nexusts.app.feature.workspace.NexusWorkspaceViewModel
import com.nexusts.app.protocols.ssh.bridge.SshNativeBridge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.Json

/**
 * Native Googlebook OS Freeform Multi-Window Session Host (`documentLaunchMode="always"`).
 * Hosts detached RDP, SSH, SFTP, VNC, Web, or Hypervisor tabs on secondary or external displays
 * with hardware key passthrough, Zero-Knowledge Vault Lock isolation, and 1-click Re-Docking.
 */
@AndroidEntryPoint
class DetachedSessionActivity : AppCompatActivity() {

    private val viewModel: NexusWorkspaceViewModel by viewModels()
    private var detachedTab: WorkspaceSessionTab? = null
    private var reDockedToWorkspace: Boolean = false
    private var detachedKeyCaptureEnabled: Boolean = false
    private var onReleaseDetachedCapture: (() -> Unit)? = null
    private val consumedShortcutKeyCodes = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val payload = intent.getStringExtra(GooglebookWindowController.EXTRA_DETACHED_TAB_PAYLOAD)
        val parsedTab = payload?.let {
            runCatching { Json.decodeFromString(WorkspaceSessionTab.serializer(), it) }.getOrNull()
        }

        if (parsedTab == null) {
            finish()
            return
        }
        detachedTab = parsedTab

        title = "${parsedTab.title} — ${getString(R.string.app_name)}"

        setContent {
            val strings = rememberLocalizedWorkspaceStrings()
            val uiState by viewModel.uiState.collectAsState()
            var keyCaptureEnabled by remember(parsedTab.protocol) {
                mutableStateOf(
                    parsedTab.protocol == SessionProtocolKind.RDP ||
                        parsedTab.protocol == SessionProtocolKind.SSH ||
                        parsedTab.protocol == SessionProtocolKind.TELNET
                )
            }

            SideEffect {
                detachedKeyCaptureEnabled = keyCaptureEnabled
                onReleaseDetachedCapture = { keyCaptureEnabled = false }
            }

            CompositionLocalProvider(LocalNexusShortcutEngine provides viewModel.shortcutEngine) {
                NexusTSTheme {
                    if (uiState.isVaultLocked) {
                        FullScreenVaultUnlockShield(
                            vaultTitle = uiState.pendingImportFileName ?: uiState.vaultDocument.name,
                            unlockError = uiState.vaultUnlockError,
                            hasPreImportBackup = uiState.hasPreImportBackup,
                            strings = strings,
                            onUnlock = { pass -> viewModel.unlockVaultWithPassphrase(pass.toCharArray()) },
                            onCancelPendingImport = viewModel::cancelPendingVaultImport,
                            onOpenVaultPicker = {}
                        )
                    } else {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .nexusRemoteKeyPassthrough(
                                    captureEnabled = keyCaptureEnabled,
                                    shortcutEngine = viewModel.shortcutEngine,
                                    onHostShortcut = { action ->
                                        when (action) {
                                            NexusShortcutAction.RELEASE_REMOTE_KEY_CAPTURE -> keyCaptureEnabled = false
                                            NexusShortcutAction.CLOSE_ACTIVE_TAB -> finish()
                                            else -> Unit
                                        }
                                    },
                                    onForwardToRemote = { packet ->
                                        forwardDetachedHardwareKey(parsedTab, packet)
                                    }
                                ),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = parsedTab.title,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "• ${parsedTab.subtitleHost} (${parsedTab.protocol.name})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )

                                        ExpressiveMorphingIconButton(
                                            icon = Icons.Outlined.Keyboard,
                                            contentDescription = "Remote Key Capture",
                                            isActive = keyCaptureEnabled,
                                            onClick = { keyCaptureEnabled = !keyCaptureEnabled }
                                        )

                                        Spacer(modifier = Modifier.width(6.dp))

                                        FilledTonalButton(
                                            onClick = {
                                                viewModel.sessionStore.enqueueReDockTab(parsedTab)
                                                reDockedToWorkspace = true
                                                finish()
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.VerticalSplit,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Re-Dock", style = MaterialTheme.typography.labelMedium)
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))

                                        IconButton(onClick = { finish() }) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription = strings.closeTab
                                            )
                                        }
                                    }
                                }

                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    ActiveProtocolSessionDispatcher(
                                        tab = parsedTab,
                                        isFocused = true,
                                        viewModel = viewModel,
                                        strings = strings
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isSystemPassThroughKeyEvent(event)) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_UP && consumedShortcutKeyCodes.remove(event.keyCode)) {
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !viewModel.uiState.value.isVaultLocked) {
            val handled = viewModel.shortcutEngine.dispatchAndroidKeyEvent(
                native = event,
                remoteCaptureActive = detachedKeyCaptureEnabled
            ) { action ->
                when (action) {
                    NexusShortcutAction.RELEASE_REMOTE_KEY_CAPTURE -> {
                        onReleaseDetachedCapture?.invoke()
                        true
                    }
                    NexusShortcutAction.CLOSE_ACTIVE_TAB -> {
                        finish()
                        true
                    }
                    else -> false
                }
            }
            if (handled) {
                consumedShortcutKeyCodes.add(event.keyCode)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        onReleaseDetachedCapture = null
        if (isFinishing && !reDockedToWorkspace) {
            detachedTab?.let { tab ->
                viewModel.closeDetachedTabResources(tab.tabId)
            }
        }
        super.onDestroy()
    }

    private fun forwardDetachedHardwareKey(tab: WorkspaceSessionTab, packet: RawHardwareKeyPacket): Boolean {
        return when (tab.protocol) {
            SessionProtocolKind.RDP, SessionProtocolKind.WINDOWS_TS ->
                viewModel.sessionStore.rdpSessions[tab.tabId]?.sendHardwareKey(packet) ?: false

            SessionProtocolKind.SSH, SessionProtocolKind.TELNET -> {
                val ansi = SshNativeBridge.translateHardwareKeyToAnsi(
                    keyCode = packet.keyCode,
                    unicodeChar = packet.unicodeChar,
                    ctrlPressed = packet.ctrlPressed,
                    altPressed = packet.altPressed
                ) ?: return false
                if (packet.isKeyDown) {
                    viewModel.sessionStore.sshControllers[tab.tabId]?.sendUserBytes(ansi)
                }
                true
            }
            else -> false
        }
    }
}
