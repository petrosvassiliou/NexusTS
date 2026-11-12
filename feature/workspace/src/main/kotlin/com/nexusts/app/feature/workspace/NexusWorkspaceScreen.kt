package com.nexusts.app.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.input.nexusDesktopPointerButtons
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.ui.workspace.ExternalDisplayDescriptor
import com.nexusts.app.core.ui.workspace.NexusAdaptiveDesktopScaffold
import com.nexusts.app.core.ui.workspace.NexusRailDestination
import com.nexusts.app.core.ui.workspace.QuickConnectCommandPalette
import com.nexusts.app.core.ui.workspace.QuickLaunchPlacement
import com.nexusts.app.core.ui.workspace.SessionProtocolKind
import com.nexusts.app.core.ui.workspace.SplitOrientation
import com.nexusts.app.core.ui.workspace.SplitPaneWorkspaceContainer
import com.nexusts.app.core.ui.workspace.SplitWorkspaceLabels
import com.nexusts.app.core.ui.workspace.WorkspaceSessionTab
import com.nexusts.app.core.ui.workspace.protocolAccentColor
import com.nexusts.app.core.ui.workspace.protocolIcon
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.feature.workspace.i18n.NexusLocaleController
import com.nexusts.app.feature.workspace.i18n.SupportedNexusLocale
import com.nexusts.app.feature.workspace.i18n.WorkspaceLocalizedStrings
import com.nexusts.app.protocols.rdp.ui.RdpSessionSurfaceView
import com.nexusts.app.protocols.ssh.ui.DualPaneTransferCommanderView
import com.nexusts.app.protocols.ssh.ui.SshTerminalSessionView
import com.nexusts.app.protocols.vnc.ui.CloudHypervisorConsoleView
import com.nexusts.app.protocols.vnc.ui.IsolatedWebSessionView
import com.nexusts.app.protocols.vnc.ui.NetworkUtilitiesView
import com.nexusts.app.protocols.vnc.ui.VncSessionCanvasView

/**
 * Complete Adaptive Googlebook OS & Android Tablet Enterprise Workspace Screen.
 */
@Composable
fun NexusWorkspaceScreen(
    viewModel: NexusWorkspaceViewModel,
    strings: WorkspaceLocalizedStrings,
    connectedDisplays: List<ExternalDisplayDescriptor>,
    flexibleUpdateDownloaded: Boolean,
    onCompleteFlexibleUpdate: () -> Unit,
    onRequestImportFilePicker: () -> Unit,
    onRequestGoogleDriveSync: () -> Unit,
    onDetachTabToNativeWindow: (WorkspaceSessionTab, Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val rootPane by viewModel.tilingController.rootNode.collectAsState()
    val focusedPaneId by viewModel.tilingController.focusedPaneId.collectAsState()

    val destinations = remember(strings) {
        listOf(
            NexusRailDestination("connections", strings.navConnections, Icons.Outlined.Terminal),
            NexusRailDestination("credentials", strings.navCredentials, Icons.Outlined.Key),
            NexusRailDestination("gateways", strings.navGateways, Icons.Outlined.DeviceHub),
            NexusRailDestination("tasks", strings.navTasks, Icons.Outlined.AutoAwesome),
            NexusRailDestination("sync", strings.navDriveSync, Icons.Outlined.CloudSync)
        )
    }

    val splitLabels = remember(strings) {
        SplitWorkspaceLabels(
            splitRightLabel = strings.splitRight,
            splitDownLabel = strings.splitDown,
            detachNewWindowLabel = strings.detachNewWindow,
            detachToDisplayPrefix = strings.detachToDisplayPrefix,
            toggleBroadcastLabel = strings.toggleBroadcast,
            closeTabLabel = strings.closeTab,
            emptyWorkspaceTitle = strings.emptyWorkspaceTitle,
            emptyWorkspaceSubtitle = strings.emptyWorkspaceSubtitle
        )
    }

    NexusAdaptiveDesktopScaffold(
        destinations = destinations,
        selectedDestinationId = uiState.activeRailId,
        onSelectDestination = viewModel::selectRailDestination,
        isSidebarVisible = uiState.isSidebarVisible,
        isInspectorVisible = uiState.isInspectorVisible,
        isBottomDrawerVisible = uiState.isBottomDrawerVisible,
        remoteKeyCaptureActive = uiState.remoteKeyCaptureEnabled,
        onShortcutTriggered = { action ->
            viewModel.handleShortcut(
                action = action,
                onOpenVaultPicker = onRequestImportFilePicker,
                onSyncDriveNow = onRequestGoogleDriveSync
            ) { tab -> onDetachTabToNativeWindow(tab, null) }
        },
        onForwardRawKeyToActiveSession = viewModel::forwardRawKeyToFocusedSession,
        topWindowBar = {
            DesktopTopWindowChrome(
                vaultName = uiState.vaultDocument.name,
                vaultRevision = uiState.vaultDocument.revision,
                isVaultLocked = uiState.isVaultLocked,
                broadcastEnabled = uiState.broadcastModeEnabled,
                flexibleUpdateDownloaded = flexibleUpdateDownloaded,
                strings = strings,
                onToggleSidebar = {
                    viewModel.handleShortcut(com.nexusts.app.core.ui.input.NexusShortcutAction.TOGGLE_SIDEBAR_TREE) {}
                },
                onOpenQuickConnect = {
                    viewModel.handleShortcut(com.nexusts.app.core.ui.input.NexusShortcutAction.QUICK_CONNECT_PALETTE) {}
                },
                onNewConnection = viewModel::openNewConnectionDialog,
                onImportRtsz = onRequestImportFilePicker,
                onSyncGoogleDrive = onRequestGoogleDriveSync,
                onToggleBroadcast = viewModel::toggleGlobalBroadcast,
                onLockVault = viewModel::openLockVaultDialog,
                onCompleteFlexibleUpdate = onCompleteFlexibleUpdate
            )
        },
        sidebarTreeContent = {
            if (uiState.isVaultLocked) {
                VaultUnlockPane(
                    strings = strings,
                    onUnlock = { pass -> viewModel.unlockVaultWithPassphrase(pass.toCharArray()) }
                )
            } else {
                VaultHierarchySidebarTree(
                    uiState = uiState,
                    strings = strings,
                    smartFolderConnectionsProvider = viewModel::evaluateSmartFolderConnections,
                    onToggleSmartFolder = viewModel::toggleSmartFolderSelection,
                    onSelectConnection = { id ->
                        viewModel.selectConnectionInTree(id)
                        viewModel.selectRailDestination("connections")
                    },
                    onLaunchConnection = { conn, placement ->
                        viewModel.selectRailDestination("connections")
                        viewModel.launchConnection(conn, placement) { detachedTab ->
                            onDetachTabToNativeWindow(detachedTab, null)
                        }
                    }
                )
            }
        },
        mainWorkspaceContent = {
            when (uiState.activeRailId) {
                "credentials" -> com.nexusts.app.feature.workspace.studios.CredentialVaultStudioView(
                    credentials = uiState.vaultDocument.credentials,
                    strings = strings,
                    onGenerateEd25519Key = viewModel::generateAndStoreSshKeyPair,
                    onCreateCredential = viewModel::addCredentialToVault
                )

                "gateways" -> com.nexusts.app.feature.workspace.studios.SecureGatewayStudioView(
                    gateways = uiState.vaultDocument.gateways,
                    healthReports = uiState.gatewayHealthReports,
                    strings = strings,
                    onProbeGateway = viewModel::probeGatewayLatency,
                    onAddGateway = viewModel::addGatewayToVault
                )

                "tasks" -> com.nexusts.app.feature.workspace.studios.AutomationTasksStudioView(
                    commandTasks = uiState.vaultDocument.commandTasks,
                    keySequenceTasks = uiState.vaultDocument.keySequenceTasks,
                    strings = strings,
                    onRunCommandTask = viewModel::executeCommandTask,
                    onRunKeySequenceTask = viewModel::executeKeySequenceTask,
                    onAddCommandTask = viewModel::addCommandTaskToVault
                )

                "sync" -> com.nexusts.app.feature.workspace.studios.DriveSyncAndConflictStudioView(
                    vaultDocument = uiState.vaultDocument,
                    useSharedTeamDrive = uiState.useSharedTeamDriveZone,
                    strings = strings,
                    onSelectSyncZone = viewModel::setDriveSyncZone,
                    onTriggerDeltaSync = onRequestGoogleDriveSync
                )

                else -> SplitPaneWorkspaceContainer(
                    node = rootPane,
                    focusedPaneId = focusedPaneId,
                    connectedDisplays = connectedDisplays,
                    labels = splitLabels,
                    onFocusPane = viewModel.tilingController::focusPane,
                    onSelectTab = viewModel.tilingController::selectTab,
                    onCloseTab = viewModel::closeSessionTab,
                    onSplitPane = { paneId, orientation ->
                        viewModel.tilingController.splitPane(paneId, orientation)
                    },
                    onResizeSplit = viewModel.tilingController::updateSplitRatio,
                    onDetachTabToWindow = { tab, displayId ->
                        viewModel.tilingController.extractTabForDetachedWindow(tab.tabId)?.let { extracted ->
                            onDetachTabToNativeWindow(extracted, displayId)
                        }
                    },
                    onToggleTabBroadcast = { tab ->
                        viewModel.tilingController.updateTab(tab.tabId) {
                            it.copy(broadcastEnabled = !it.broadcastEnabled)
                        }
                    },
                    emptyStateContent = {
                        ExpressiveEmptyWorkspaceHero(
                            strings = strings,
                            onOpenQuickConnect = {
                                viewModel.handleShortcut(
                                    com.nexusts.app.core.ui.input.NexusShortcutAction.QUICK_CONNECT_PALETTE
                                ) {}
                            }
                        )
                    },
                    sessionRenderer = { tab, isFocused ->
                        ActiveProtocolSessionDispatcher(
                            tab = tab,
                            isFocused = isFocused,
                            viewModel = viewModel,
                            strings = strings
                        )
                    }
                )
            }
        },
        propertyInspectorContent = {
            ContextualPropertyInspectorPane(
                profile = viewModel.effectiveProfileForSelected(),
                strings = strings,
                onGenerateSshKey = viewModel::generateAndStoreSshKeyPair
            )
        },
        bottomStatusDrawerContent = {
            BottomTelemetryAndBroadcastDrawer(
                logs = uiState.auditLogs,
                strings = strings,
                onBroadcastCommand = viewModel::sendBroadcastCommandLine
            )
        },
        floatingOverlayContent = {
            QuickConnectCommandPalette(
                visible = uiState.isQuickConnectVisible,
                vaultCandidates = remember(uiState.vaultDocument) {
                    viewModel.buildQuickConnectCandidates()
                },
                placeholderText = strings.quickConnectPlaceholder,
                footerHintText = strings.quickConnectFooterHint,
                onDismiss = {
                    viewModel.handleShortcut(
                        com.nexusts.app.core.ui.input.NexusShortcutAction.QUICK_CONNECT_PALETTE
                    ) {}
                },
                onLaunchCandidate = { candidate, placement ->
                    viewModel.selectRailDestination("connections")
                    viewModel.launchQuickCandidate(candidate, placement) { tab ->
                        onDetachTabToNativeWindow(tab, null)
                    }
                }
            )
            com.nexusts.app.feature.workspace.studios.NewOrEditConnectionDialog(
                visible = uiState.isNewConnectionDialogVisible,
                folders = uiState.vaultDocument.folders,
                strings = strings,
                onDismiss = viewModel::dismissNewConnectionDialog,
                onSaveConnection = viewModel::saveConnectionToVault
            )
            LockVaultPassphraseDialog(
                visible = uiState.isLockVaultDialogVisible,
                strings = strings,
                onDismiss = viewModel::dismissLockVaultDialog,
                onConfirmLock = { pass -> viewModel.lockVaultWithPassphrase(pass.toCharArray()) }
            )
        },
        modifier = modifier
    )
}

@Composable
fun ActiveProtocolSessionDispatcher(
    tab: WorkspaceSessionTab,
    isFocused: Boolean,
    viewModel: NexusWorkspaceViewModel,
    strings: WorkspaceLocalizedStrings
) {
    val uiState by viewModel.uiState.collectAsState()
    val (conn, secret) = remember(tab.connectionId, uiState.vaultDocument, viewModel) {
        viewModel.resolveConnectionAndSecret(tab.connectionId)
    }
    val onZoomChanged: (Float) -> Unit = remember(tab.tabId, viewModel) {
        { nextZoom ->
            viewModel.tilingController.updateTab(tab.tabId) { it.copy(zoomFactor = nextZoom) }
        }
    }

    when (tab.protocol) {
        SessionProtocolKind.RDP, SessionProtocolKind.WINDOWS_TS -> {
            val rdpSession = remember(tab.tabId) { viewModel.getOrCreateRdpSession(tab) }
            RdpSessionSurfaceView(
                session = rdpSession,
                isFocused = isFocused,
                ctrlAltDelLabel = strings.rdpSendCtrlAltDel,
                smartcardActiveLabel = strings.rdpSmartcardActive,
                dynamicResizeLabel = strings.rdpDynamicResizeActive,
                externalZoomFactor = tab.zoomFactor,
                onZoomFactorChanged = onZoomChanged
            )
        }

        SessionProtocolKind.SSH, SessionProtocolKind.TELNET, SessionProtocolKind.SERIAL -> {
            val sshController = remember(tab.tabId) { viewModel.getOrCreateSshController(tab) }
            SshTerminalSessionView(
                controller = sshController,
                isFocused = isFocused,
                broadcastActive = tab.broadcastEnabled,
                pasteActionLabel = strings.sshPasteClipboard,
                tunnelsActiveLabel = strings.sshTunnelsActive,
                externalZoomFactor = tab.zoomFactor,
                onZoomFactorChanged = onZoomChanged
            )
        }

        SessionProtocolKind.SFTP, SessionProtocolKind.FTP,
        SessionProtocolKind.FTPS, SessionProtocolKind.SCP -> {
            DualPaneTransferCommanderView(
                protocolBadge = tab.protocol.name,
                remoteHost = tab.subtitleHost,
                localTitle = strings.sftpLocalPane,
                remoteTitle = strings.sftpRemotePane,
                transferQueueTitle = strings.sftpTransferQueue
            )
        }

        SessionProtocolKind.VNC, SessionProtocolKind.APPLE_SCREEN_SHARING -> {
            val vncSettings = (conn?.settings as? ProtocolSpecificSettings.Vnc)
                ?: ProtocolSpecificSettings.Vnc(
                    appleArdDiffieHellmanAuth = tab.protocol == SessionProtocolKind.APPLE_SCREEN_SHARING
                )
            VncSessionCanvasView(
                host = tab.subtitleHost,
                isAppleScreenSharing = tab.protocol == SessionProtocolKind.APPLE_SCREEN_SHARING,
                settings = vncSettings,
                externalZoomFactor = tab.zoomFactor,
                onZoomFactorChanged = onZoomChanged
            )
        }

        SessionProtocolKind.WEB_PAGE -> {
            val webSettings = (conn?.settings as? ProtocolSpecificSettings.WebPage)
                ?: ProtocolSpecificSettings.WebPage(url = tab.subtitleHost)
            IsolatedWebSessionView(
                settings = webSettings,
                secret = secret,
                autoFillActionLabel = strings.webAutofillCredentials
            )
        }

        SessionProtocolKind.VMWARE_VSPHERE, SessionProtocolKind.PROXMOX_VE,
        SessionProtocolKind.HYPER_V, SessionProtocolKind.AWS_EC2,
        SessionProtocolKind.AZURE_VM -> {
            val cloudSettings = (conn?.settings as? ProtocolSpecificSettings.CloudVirtualization)
                ?: ProtocolSpecificSettings.CloudVirtualization(apiEndpointUrl = "https://${tab.subtitleHost}")
            CloudHypervisorConsoleView(
                protocol = conn?.protocol ?: VaultProtocolType.PROXMOX_VE,
                endpointHost = tab.subtitleHost,
                settings = cloudSettings,
                secret = secret,
                openConsoleCallback = { vmName, vmIp, vmProto ->
                    val dynamicVmConn = VaultConnection(
                        name = vmName,
                        host = vmIp,
                        protocol = vmProto,
                        settings = if (vmProto == VaultProtocolType.RDP) {
                            ProtocolSpecificSettings.Rdp()
                        } else {
                            ProtocolSpecificSettings.Ssh()
                        }
                    )
                    viewModel.launchConnection(dynamicVmConn, QuickLaunchPlacement.SPLIT_RIGHT_PANE)
                }
            )
        }

        SessionProtocolKind.NET_UTILITY -> {
            val netSettings = (conn?.settings as? ProtocolSpecificSettings.NetUtility)
                ?: ProtocolSpecificSettings.NetUtility()
            NetworkUtilitiesView(
                initialTool = conn?.protocol ?: VaultProtocolType.UTILITY_PORT_SCANNER,
                initialHost = tab.subtitleHost,
                settings = netSettings,
                executeLabel = strings.netutilExecute
            )
        }
    }
}

@Composable
private fun DesktopTopWindowChrome(
    vaultName: String,
    vaultRevision: Long,
    isVaultLocked: Boolean,
    broadcastEnabled: Boolean,
    flexibleUpdateDownloaded: Boolean,
    strings: WorkspaceLocalizedStrings,
    onToggleSidebar: () -> Unit,
    onOpenQuickConnect: () -> Unit,
    onNewConnection: () -> Unit,
    onImportRtsz: () -> Unit,
    onSyncGoogleDrive: () -> Unit,
    onToggleBroadcast: () -> Unit,
    onLockVault: () -> Unit,
    onCompleteFlexibleUpdate: () -> Unit
) {
    var langMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onToggleSidebar, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.ViewSidebar, contentDescription = null)
                }
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = strings.appName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "• $vaultName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Center Expressive Quick-Connect Command Bar Trigger (`Ctrl/Cmd + K`)
            Surface(
                modifier = Modifier
                    .width(320.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .clickable(onClick = onOpenQuickConnect),
                shape = RoundedCornerShape(99.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.quickConnectButton,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "⌘K",
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // Right Action Cluster: Play In-App Update Banner, New Connection, Sync, Import .rtsz, Broadcast, Vault Lock, 6-Language Switcher
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (flexibleUpdateDownloaded) {
                    FilledTonalButton(onClick = onCompleteFlexibleUpdate) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.updateRestartAction, style = MaterialTheme.typography.labelMedium)
                    }
                }

                AssistChip(
                    onClick = onNewConnection,
                    label = { Text(strings.actionNewConnection, style = MaterialTheme.typography.labelSmall) }
                )

                AssistChip(
                    onClick = onSyncGoogleDrive,
                    label = {
                        Text(
                            text = String.format(strings.syncStatusSyncedFormat, vaultRevision),
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.CloudSync, contentDescription = null, modifier = Modifier.size(15.dp))
                    }
                )

                AssistChip(
                    onClick = onImportRtsz,
                    label = { Text(strings.actionImportRtsz, style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(15.dp))
                    }
                )

                IconButton(onClick = onToggleBroadcast, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Podcasts,
                        contentDescription = strings.actionBroadcastInput,
                        tint = if (broadcastEnabled) {
                            NexusTheme.extendedColors.broadcastActiveBanner
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                IconButton(onClick = onLockVault, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (isVaultLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                        contentDescription = strings.actionLockVault
                    )
                }

                Box {
                    IconButton(onClick = { langMenuExpanded = true }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription = strings.actionLanguagePicker
                        )
                    }
                    DropdownMenu(
                        expanded = langMenuExpanded,
                        onDismissRequest = { langMenuExpanded = false }
                    ) {
                        SupportedNexusLocale.entries.forEach { locale ->
                            DropdownMenuItem(
                                text = {
                                    Text(strings.languageLabels[locale] ?: locale.bcp47Tag.uppercase())
                                },
                                onClick = {
                                    langMenuExpanded = false
                                    NexusLocaleController.switchLanguage(locale)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultHierarchySidebarTree(
    uiState: WorkspaceUiState,
    strings: WorkspaceLocalizedStrings,
    smartFolderConnectionsProvider: (com.nexusts.app.core.vault.model.SmartFolder) -> List<VaultConnection>,
    onToggleSmartFolder: (String) -> Unit,
    onSelectConnection: (String) -> Unit,
    onLaunchConnection: (VaultConnection, QuickLaunchPlacement) -> Unit
) {
    val doc = uiState.vaultDocument

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                text = strings.vaultSectionSmartFolders,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        doc.smartFolders.forEach { smart ->
            val matchedConnections = smartFolderConnectionsProvider(smart)
            val isExpanded = uiState.selectedSmartFolderId == smart.id
            item(key = "smart-${smart.id}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            if (isExpanded) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .clickable { onToggleSmartFolder(smart.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(smart.name, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        text = matchedConnections.size.toString(),
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isExpanded) {
                items(matchedConnections, key = { "smart-${smart.id}-${it.id}" }) { conn ->
                    ConnectionTreeRow(
                        connection = conn,
                        isSelected = conn.id == uiState.selectedConnectionId,
                        strings = strings,
                        onSelect = { onSelectConnection(conn.id) },
                        onLaunch = { placement -> onLaunchConnection(conn, placement) }
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = strings.vaultSectionFolders,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }

        doc.folders.forEach { folder ->
            item(key = "folder-${folder.id}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            val folderConnections = doc.connections.filter { it.parentFolderId == folder.id }
            items(folderConnections, key = { it.id }) { conn ->
                ConnectionTreeRow(
                    connection = conn,
                    isSelected = conn.id == uiState.selectedConnectionId,
                    strings = strings,
                    onSelect = { onSelectConnection(conn.id) },
                    onLaunch = { placement -> onLaunchConnection(conn, placement) }
                )
            }
        }
    }
}

@Composable
private fun ConnectionTreeRow(
    connection: VaultConnection,
    isSelected: Boolean,
    strings: WorkspaceLocalizedStrings,
    onSelect: () -> Unit,
    onLaunch: (QuickLaunchPlacement) -> Unit
) {
    var contextMenuOpen by remember { mutableStateOf(false) }
    val uiProto = NexusWorkspaceViewModel.mapProtocolToUiKind(connection.protocol)

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                    else androidx.compose.ui.graphics.Color.Transparent
                )
                .nexusDesktopPointerButtons(onSecondaryClick = { contextMenuOpen = true })
                .clickable {
                    onSelect()
                    onLaunch(QuickLaunchPlacement.ACTIVE_PANE_TAB)
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = protocolIcon(uiProto),
                contentDescription = null,
                tint = protocolAccentColor(uiProto),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = connection.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = connection.host,
                    style = NexusTheme.monospace.keycapShortcut,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        DropdownMenu(
            expanded = contextMenuOpen,
            onDismissRequest = { contextMenuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text(strings.splitRight) },
                leadingIcon = { Icon(Icons.Outlined.VerticalSplit, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onLaunch(QuickLaunchPlacement.SPLIT_RIGHT_PANE)
                }
            )
            DropdownMenuItem(
                text = { Text(strings.detachNewWindow) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onLaunch(QuickLaunchPlacement.DETACHED_NEW_WINDOW)
                }
            )
        }
    }
}

@Composable
private fun ContextualPropertyInspectorPane(
    profile: com.nexusts.app.core.vault.inheritance.EffectiveConnectionProfile?,
    strings: WorkspaceLocalizedStrings,
    onGenerateSshKey: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = strings.inspectorTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        if (profile != null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.inspectorProtocolEndpoint, style = MaterialTheme.typography.labelMedium)
                    Text(profile.connection.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${profile.connection.protocol.name} • ${profile.connection.host}",
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.inspectorEffectiveCredential, style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = profile.effectiveCredential?.name ?: "-",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = String.format(strings.inspectorInheritedFromFormat, profile.credentialSourceLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.inspectorEffectiveGateway, style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = profile.effectiveGateway?.name ?: profile.gatewaySourceLabel,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = String.format(strings.inspectorInheritedFromFormat, profile.gatewaySourceLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        FilledTonalButton(
            onClick = onGenerateSshKey,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(strings.inspectorGenerateSshKey)
        }
    }
}

@Composable
private fun LockVaultPassphraseDialog(
    visible: Boolean,
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit,
    onConfirmLock: (String) -> Unit
) {
    if (!visible) return
    var lockPassphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        title = { Text(strings.actionLockVault) },
        text = {
            OutlinedTextField(
                value = lockPassphrase,
                onValueChange = { lockPassphrase = it },
                label = { Text(strings.vaultPassphraseLabel) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                enabled = lockPassphrase.isNotEmpty(),
                onClick = {
                    onConfirmLock(lockPassphrase)
                    lockPassphrase = ""
                }
            ) {
                Text(strings.actionLockVault)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.dialogActionCancel)
            }
        }
    )
}

@Composable
private fun VaultUnlockPane(
    strings: WorkspaceLocalizedStrings,
    onUnlock: (String) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = strings.vaultLockedBanner, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text(strings.vaultPassphraseLabel) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            enabled = passphrase.isNotEmpty(),
            onClick = {
                onUnlock(passphrase)
                passphrase = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(strings.actionUnlockVault)
        }
    }
}

@Composable
private fun BottomTelemetryAndBroadcastDrawer(
    logs: List<String>,
    strings: WorkspaceLocalizedStrings,
    onBroadcastCommand: (String) -> Unit
) {
    var cmd by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                placeholder = { Text(strings.drawerBroadcastPrompt, style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    onBroadcastCommand(cmd)
                    cmd = ""
                }
            ) {
                Text(strings.drawerSendBroadcast)
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs) { line ->
                Text(
                    text = line,
                    style = NexusTheme.monospace.logEntry,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ExpressiveEmptyWorkspaceHero(
    strings: WorkspaceLocalizedStrings,
    onOpenQuickConnect: () -> Unit
) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Security,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = strings.emptyWorkspaceTitle,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = strings.emptyWorkspaceSubtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Button(onClick = onOpenQuickConnect) {
            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(strings.quickConnectButton)
        }
    }
}
