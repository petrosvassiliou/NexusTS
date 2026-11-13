package com.nexusts.app.feature.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.components.ExpressiveMorphingIconButton
import com.nexusts.app.core.ui.components.ExpressiveSplitActionButton
import com.nexusts.app.core.ui.components.parseHexColorOrNull
import com.nexusts.app.core.ui.input.NexusShortcutAction
import com.nexusts.app.core.ui.input.nexusDesktopPointerButtons
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.ui.workspace.ExternalDisplayDescriptor
import com.nexusts.app.core.ui.workspace.NexusAdaptiveDesktopScaffold
import com.nexusts.app.core.ui.workspace.NexusRailDestination
import com.nexusts.app.core.ui.workspace.QuickConnectCommandPalette
import com.nexusts.app.core.ui.workspace.QuickLaunchPlacement
import com.nexusts.app.core.ui.workspace.SessionProtocolKind
import com.nexusts.app.core.ui.workspace.SplitPaneWorkspaceContainer
import com.nexusts.app.core.ui.workspace.SplitWorkspaceLabels
import com.nexusts.app.core.ui.workspace.WorkspaceSessionTab
import com.nexusts.app.core.ui.workspace.protocolAccentColor
import com.nexusts.app.core.ui.workspace.protocolIcon
import com.nexusts.app.core.vault.exporter.VaultExportFormat
import com.nexusts.app.core.vault.inheritance.EffectiveConnectionProfile
import com.nexusts.app.core.vault.model.CustomPropertyEntry
import com.nexusts.app.core.vault.model.DynamicFolder
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.RdpAudioRedirectionMode
import com.nexusts.app.core.vault.model.SmartFolder
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.core.vault.model.VaultTodoItem
import com.nexusts.app.feature.workspace.i18n.NexusLocaleController
import com.nexusts.app.feature.workspace.i18n.SupportedNexusLocale
import com.nexusts.app.feature.workspace.i18n.WorkspaceLocalizedStrings
import com.nexusts.app.feature.workspace.studios.AutomationTasksStudioView
import com.nexusts.app.feature.workspace.studios.ConnectWithOptionsDialog
import com.nexusts.app.feature.workspace.studios.ConnectionTemplatesStudioView
import com.nexusts.app.feature.workspace.studios.CredentialVaultStudioView
import com.nexusts.app.feature.workspace.studios.DriveSyncAndConflictStudioView
import com.nexusts.app.feature.workspace.studios.ExpressiveVaultOverviewDashboard
import com.nexusts.app.feature.workspace.studios.FolderPropertiesAndCredentialDialog
import com.nexusts.app.feature.workspace.studios.InspectorExtendedRoyalCards
import com.nexusts.app.feature.workspace.studios.NewOrEditConnectionDialog
import com.nexusts.app.feature.workspace.studios.NewSmartFolderDialog
import com.nexusts.app.feature.workspace.studios.NewTemplateDialog
import com.nexusts.app.feature.workspace.studios.SecureGatewayStudioView
import com.nexusts.app.protocols.rdp.ui.RdpSessionSurfaceView
import com.nexusts.app.protocols.ssh.serial.UsbSerialConsoleView
import com.nexusts.app.protocols.ssh.ui.DualPaneTransferCommanderView
import com.nexusts.app.protocols.ssh.ui.SshTerminalSessionView
import com.nexusts.app.protocols.vnc.ui.CloudHypervisorConsoleView
import com.nexusts.app.protocols.vnc.ui.IsolatedWebSessionView
import com.nexusts.app.protocols.vnc.ui.KubernetesAndContainerTerminalView
import com.nexusts.app.protocols.vnc.ui.NetworkUtilitiesView
import com.nexusts.app.protocols.vnc.ui.VncSessionCanvasView

/**
 * Complete Adaptive Googlebook OS & Android Tablet Enterprise Workspace Screen
 * built on Material Design 3 Expressive with 100% Royal TS feature parity.
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
    onRequestExportVault: (VaultExportFormat) -> Unit = {},
    initialOAuthWebClientId: String = "",
    initialSharedDriveFolderId: String = "17C98pGqQUAoQwQ-OSLUHPj4_dtb4_wtS",
    onSaveOAuthConfig: (String, String) -> Unit = { _, _ -> },
    onCreateLiveSafDriveVault: () -> Unit = {},
    onLinkExistingSafDriveVault: () -> Unit = {},
    onUnlinkSafDriveVault: () -> Unit = {},
    onAuthorizeAndSyncOAuthDrive: () -> Unit = {},
    onSignOutOAuthDrive: () -> Unit = {},
    onToggleAutoSyncOnSave: (Boolean) -> Unit = { viewModel.setDriveAutoSyncOnSave(it) },
    onCheckGitHubReleases: () -> Unit = {},
    onOpenGitHubReleaseUrl: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val rootPane by viewModel.tilingController.rootNode.collectAsState()
    val focusedPaneId by viewModel.tilingController.focusedPaneId.collectAsState()

    val activeSessionCount = remember(rootPane) {
        viewModel.tilingController.allActiveTabs().size
    }
    val pendingTodoCount = remember(uiState.vaultDocument.todoItems) {
        uiState.vaultDocument.todoItems.count { !it.completed }
    }

    val destinations = remember(strings, activeSessionCount, uiState.vaultDocument.templates.size, pendingTodoCount) {
        listOf(
            NexusRailDestination("connections", strings.navConnections, Icons.Outlined.Terminal, badgeCount = activeSessionCount),
            NexusRailDestination("templates", strings.navTemplates, Icons.Outlined.Bookmarks, badgeCount = uiState.vaultDocument.templates.size),
            NexusRailDestination("credentials", strings.navCredentials, Icons.Outlined.Key),
            NexusRailDestination("gateways", strings.navGateways, Icons.Outlined.DeviceHub),
            NexusRailDestination("tasks", strings.navTasks, Icons.Outlined.AutoAwesome, badgeCount = pendingTodoCount),
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
            reconnectTabLabel = strings.actionReconnectTab,
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
                isSidebarVisible = uiState.isSidebarVisible,
                isInspectorVisible = uiState.isInspectorVisible,
                isBottomDrawerVisible = uiState.isBottomDrawerVisible,
                isVaultLocked = uiState.isVaultLocked,
                broadcastEnabled = uiState.broadcastModeEnabled,
                flexibleUpdateDownloaded = flexibleUpdateDownloaded,
                strings = strings,
                onToggleSidebar = {
                    viewModel.handleShortcut(NexusShortcutAction.TOGGLE_SIDEBAR_TREE) {}
                },
                onToggleInspector = {
                    viewModel.handleShortcut(NexusShortcutAction.TOGGLE_PROPERTY_INSPECTOR) {}
                },
                onToggleBottomDrawer = {
                    viewModel.handleShortcut(NexusShortcutAction.TOGGLE_BOTTOM_LOG_DRAWER) {}
                },
                onOpenQuickConnect = {
                    viewModel.handleShortcut(NexusShortcutAction.QUICK_CONNECT_PALETTE) {}
                },
                onNewConnection = viewModel::openNewConnectionDialog,
                onNewFolder = viewModel::openNewFolderDialog,
                onNewSmartFolder = viewModel::openNewSmartFolderDialog,
                onNewTemplate = viewModel::openNewTemplateDialog,
                onNewLocalShell = viewModel::launchLocalShellTab,
                onImportRtsz = onRequestImportFilePicker,
                onExportVault = onRequestExportVault,
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
                    onFilterQueryChanged = viewModel::updateSidebarFilterQuery,
                    smartFolderConnectionsProvider = viewModel::evaluateSmartFolderConnections,
                    onToggleSmartFolder = viewModel::toggleSmartFolderSelection,
                    onNewSmartFolder = viewModel::openNewSmartFolderDialog,
                    onToggleDynamicFolder = viewModel::toggleDynamicFolder,
                    onReloadDynamicFolder = viewModel::reloadDynamicFolder,
                    onNewFolder = viewModel::openNewFolderDialog,
                    onEditFolder = viewModel::openEditFolderDialog,
                    onConnectAllInFolder = { folderId ->
                        viewModel.connectAllInFolder(folderId) { tab ->
                            onDetachTabToNativeWindow(tab, null)
                        }
                    },
                    onSelectConnection = { id ->
                        viewModel.selectConnectionInTree(id)
                        viewModel.selectRailDestination("connections")
                    },
                    onEditConnection = viewModel::openEditConnectionDialog,
                    onConnectWithOptions = viewModel::openConnectWithOptions,
                    onToggleFavorite = viewModel::toggleFavoriteConnection,
                    onDuplicateConnection = viewModel::duplicateConnection,
                    onDeleteConnection = viewModel::deleteConnection,
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
                "templates" -> ConnectionTemplatesStudioView(
                    templates = uiState.vaultDocument.templates,
                    strings = strings,
                    onCreateConnectionFromTemplate = viewModel::createConnectionFromTemplate,
                    onOpenNewTemplateDialog = viewModel::openNewTemplateDialog
                )

                "credentials" -> CredentialVaultStudioView(
                    credentials = uiState.vaultDocument.credentials,
                    strings = strings,
                    onGenerateEd25519Key = viewModel::generateAndStoreSshKeyPair,
                    onCreateCredential = viewModel::addCredentialToVault,
                    onRequestImportVaultFile = onRequestImportFilePicker
                )

                "gateways" -> SecureGatewayStudioView(
                    gateways = uiState.vaultDocument.gateways,
                    healthReports = uiState.gatewayHealthReports,
                    strings = strings,
                    onProbeGateway = viewModel::probeGatewayLatency,
                    onAddGateway = viewModel::addGatewayToVault
                )

                "tasks" -> AutomationTasksStudioView(
                    commandTasks = uiState.vaultDocument.commandTasks,
                    keySequenceTasks = uiState.vaultDocument.keySequenceTasks,
                    strings = strings,
                    onRunCommandTask = viewModel::executeCommandTask,
                    onRunKeySequenceTask = viewModel::executeKeySequenceTask,
                    onAddCommandTask = viewModel::addCommandTaskToVault
                )

                "sync" -> DriveSyncAndConflictStudioView(
                    vaultDocument = uiState.vaultDocument,
                    useSharedTeamDrive = uiState.useSharedTeamDriveZone,
                    strings = strings,
                    onSelectSyncZone = viewModel::setDriveSyncZone,
                    onTriggerDeltaSync = onRequestGoogleDriveSync,
                    initialOAuthWebClientId = initialOAuthWebClientId,
                    initialSharedFolderId = initialSharedDriveFolderId,
                    onSaveOAuthConfig = onSaveOAuthConfig,
                    driveSignedInEmail = uiState.driveSignedInEmail,
                    driveLinkedSafLabel = uiState.driveLinkedSafLabel,
                    driveSyncStatusBanner = uiState.driveSyncStatusBanner,
                    driveIsSyncing = uiState.driveIsSyncing,
                    driveAutoSyncOnSave = uiState.driveAutoSyncOnSave,
                    onToggleAutoSyncOnSave = onToggleAutoSyncOnSave,
                    onCreateLiveSafDriveVault = onCreateLiveSafDriveVault,
                    onLinkExistingSafDriveVault = onLinkExistingSafDriveVault,
                    onUnlinkSafDriveVault = onUnlinkSafDriveVault,
                    onAuthorizeAndSyncOAuthDrive = onAuthorizeAndSyncOAuthDrive,
                    onSignOutOAuthDrive = onSignOutOAuthDrive,
                    githubReleaseBanner = uiState.githubLatestReleaseBanner,
                    githubReleaseUrl = uiState.githubLatestReleaseUrl,
                    onCheckGitHubReleases = onCheckGitHubReleases,
                    onOpenGitHubReleaseUrl = onOpenGitHubReleaseUrl
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
                    onReconnectTab = viewModel::reconnectSessionTab,
                    emptyStateContent = {
                        ExpressiveVaultOverviewDashboard(
                            vaultDocument = uiState.vaultDocument,
                            activeSessionCount = activeSessionCount,
                            strings = strings,
                            onOpenQuickConnect = {
                                viewModel.handleShortcut(NexusShortcutAction.QUICK_CONNECT_PALETTE) {}
                            },
                            onNewConnection = viewModel::openNewConnectionDialog,
                            onOpenTemplates = { viewModel.selectRailDestination("templates") },
                            onLaunchConnection = { conn ->
                                viewModel.launchConnection(conn, QuickLaunchPlacement.ACTIVE_PANE_TAB)
                            },
                            onConnectWithOptions = viewModel::openConnectWithOptions,
                            onConnectAllInFolder = { folderId ->
                                viewModel.connectAllInFolder(folderId) { tab ->
                                    onDetachTabToNativeWindow(tab, null)
                                }
                            },
                            onAddTodo = { title -> viewModel.addTodoItem(title, targetObjectId = null) },
                            onToggleTodo = viewModel::toggleTodoItem
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
            val activeProfile = viewModel.effectiveProfileForSelected()
            val parentFolder = activeProfile?.connection?.parentFolderId?.let { folderId ->
                uiState.vaultDocument.folders.firstOrNull { it.id == folderId }
            }
            ContextualPropertyInspectorPane(
                profile = activeProfile,
                parentFolder = parentFolder,
                todoItems = uiState.vaultDocument.todoItems,
                strings = strings,
                onEditConnection = viewModel::openEditConnectionDialog,
                onConnectWithOptions = viewModel::openConnectWithOptions,
                onEditParentFolderCredentials = viewModel::openEditFolderDialog,
                onSaveNotesAndCustomProps = { connId, notes, props, accent ->
                    viewModel.updateConnectionNotesAndCustomProps(connId, notes, props, accent)
                },
                onAddTodo = { connId, title -> viewModel.addTodoItem(title, targetObjectId = connId) },
                onToggleTodo = viewModel::toggleTodoItem,
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
                    viewModel.handleShortcut(NexusShortcutAction.QUICK_CONNECT_PALETTE) {}
                },
                onLaunchCandidate = { candidate, placement ->
                    viewModel.selectRailDestination("connections")
                    viewModel.launchQuickCandidate(candidate, placement) { tab ->
                        onDetachTabToNativeWindow(tab, null)
                    }
                }
            )
            NewOrEditConnectionDialog(
                visible = uiState.isNewConnectionDialogVisible,
                initialConnection = uiState.editingConnection,
                folders = uiState.vaultDocument.folders,
                credentials = uiState.vaultDocument.credentials,
                resolveFolderPreview = viewModel::resolveFolderInheritedPreview,
                strings = strings,
                onDismiss = viewModel::dismissNewConnectionDialog,
                onSaveConnection = viewModel::saveConnectionToVault,
                sshConnections = remember(uiState.vaultDocument.connections) {
                    uiState.vaultDocument.connections.filter { it.protocol == VaultProtocolType.SSH }
                }
            )
            val folderChildCount = uiState.editingFolder?.let { f ->
                uiState.vaultDocument.connections.count { it.parentFolderId == f.id }
            } ?: 0
            FolderPropertiesAndCredentialDialog(
                visible = uiState.isFolderDialogVisible,
                editingFolder = uiState.editingFolder,
                allFolders = uiState.vaultDocument.folders,
                credentials = uiState.vaultDocument.credentials,
                childConnectionCount = folderChildCount,
                strings = strings,
                onDismiss = viewModel::dismissFolderDialog,
                onSaveFolderAndCredential = viewModel::saveFolderAndCredentialToVault
            )
            ConnectWithOptionsDialog(
                targetConnection = uiState.connectWithOptionsTarget,
                credentials = uiState.vaultDocument.credentials,
                templates = uiState.vaultDocument.templates,
                strings = strings,
                onDismiss = viewModel::dismissConnectWithOptions,
                onConnectWithOverride = { conn, override, placement ->
                    viewModel.launchConnectionWithOptions(conn, override, placement) { tab ->
                        onDetachTabToNativeWindow(tab, null)
                    }
                }
            )
            NewSmartFolderDialog(
                visible = uiState.isNewSmartFolderDialogVisible,
                strings = strings,
                onDismiss = viewModel::dismissNewSmartFolderDialog,
                onSaveSmartFolder = viewModel::saveSmartFolderToVault
            )
            NewTemplateDialog(
                visible = uiState.isNewTemplateDialogVisible,
                strings = strings,
                onDismiss = viewModel::dismissNewTemplateDialog,
                onSaveTemplate = viewModel::saveTemplateToVault
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
    val (conn, secret) = remember(tab.tabId, tab.connectionId, uiState.vaultDocument, viewModel) {
        viewModel.resolveConnectionAndSecret(tab.connectionId, tab.tabId)
    }
    val onZoomChanged: (Float) -> Unit = remember(tab.tabId, viewModel) {
        { nextZoom ->
            viewModel.tilingController.updateTab(tab.tabId) { it.copy(zoomFactor = nextZoom) }
        }
    }

    when (tab.protocol) {
        SessionProtocolKind.RDP, SessionProtocolKind.WINDOWS_TS -> {
            val rdpSession = remember(tab.tabId, conn, secret) { viewModel.getOrCreateRdpSession(tab) }
            RdpSessionSurfaceView(
                session = rdpSession,
                isFocused = isFocused,
                ctrlAltDelLabel = strings.rdpSendCtrlAltDel,
                smartcardActiveLabel = strings.rdpSmartcardActive,
                dynamicResizeLabel = strings.rdpDynamicResizeActive,
                clipboardSyncLabel = strings.rdpSyncClipboardChip,
                consoleBadgeLabel = strings.rdpConsoleBadge,
                externalZoomFactor = tab.zoomFactor,
                onZoomFactorChanged = onZoomChanged
            )
        }

        SessionProtocolKind.SSH, SessionProtocolKind.TELNET -> {
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

        SessionProtocolKind.SERIAL -> {
            val serialSettings = (conn?.settings as? ProtocolSpecificSettings.SerialUart)
                ?: ProtocolSpecificSettings.SerialUart(devicePathHint = tab.subtitleHost)
            UsbSerialConsoleView(
                endpointLabel = tab.subtitleHost,
                initialSettings = serialSettings
            )
        }

        SessionProtocolKind.KUBERNETES_POD, SessionProtocolKind.DOCKER_CONTAINER -> {
            val containerSettings = (conn?.settings as? ProtocolSpecificSettings.ContainerExec)
                ?: ProtocolSpecificSettings.ContainerExec(podOrContainerName = tab.subtitleHost)
            KubernetesAndContainerTerminalView(
                isKubernetes = tab.protocol == SessionProtocolKind.KUBERNETES_POD,
                endpointHost = tab.subtitleHost,
                settings = containerSettings,
                secret = secret
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
    isSidebarVisible: Boolean,
    isInspectorVisible: Boolean,
    isBottomDrawerVisible: Boolean,
    isVaultLocked: Boolean,
    broadcastEnabled: Boolean,
    flexibleUpdateDownloaded: Boolean,
    strings: WorkspaceLocalizedStrings,
    onToggleSidebar: () -> Unit,
    onToggleInspector: () -> Unit,
    onToggleBottomDrawer: () -> Unit,
    onOpenQuickConnect: () -> Unit,
    onNewConnection: () -> Unit,
    onNewFolder: () -> Unit,
    onNewSmartFolder: () -> Unit,
    onNewTemplate: () -> Unit,
    onNewLocalShell: () -> Unit,
    onImportRtsz: () -> Unit,
    onExportVault: (VaultExportFormat) -> Unit,
    onSyncGoogleDrive: () -> Unit,
    onToggleBroadcast: () -> Unit,
    onLockVault: () -> Unit,
    onCompleteFlexibleUpdate: () -> Unit
) {
    var langMenuExpanded by remember { mutableStateOf(false) }
    var createMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Window Identity Cluster
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExpressiveMorphingIconButton(
                    icon = Icons.Outlined.ViewSidebar,
                    contentDescription = null,
                    isActive = isSidebarVisible,
                    onClick = onToggleSidebar
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                Text(
                    text = strings.appName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = vaultName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Center Expressive Quick-Connect Command Bar Trigger (`Ctrl/Cmd + K`)
            Surface(
                modifier = Modifier
                    .width(330.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .clickable(onClick = onOpenQuickConnect),
                shape = RoundedCornerShape(99.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
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
                    Surface(
                        shape = RoundedCornerShape(99.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest
                    ) {
                        Text(
                            text = "⌘K",
                            style = NexusTheme.monospace.keycapShortcut,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Right Expressive Action Cluster
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

                // M3 Expressive Split-Action Button (`[ + New Connection | ▾ ]`)
                Box {
                    ExpressiveSplitActionButton(
                        label = strings.actionNewConnection,
                        leadingIcon = Icons.Outlined.Add,
                        isMenuExpanded = createMenuExpanded,
                        onPrimaryClick = onNewConnection,
                        onTrailingMenuClick = { createMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = createMenuExpanded,
                        onDismissRequest = { createMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(strings.actionNewConnection) },
                            leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onNewConnection()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.actionNewFolder) },
                            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onNewFolder()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.actionNewSmartFolder) },
                            leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onNewSmartFolder()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.actionNewTemplate) },
                            leadingIcon = { Icon(Icons.Outlined.Bookmarks, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onNewTemplate()
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Local Terminal") },
                            leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onNewLocalShell()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.actionImportRtsz) },
                            leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onImportRtsz()
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(strings.actionExportNxts) },
                            leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onExportVault(VaultExportFormat.NXTS_VAULT)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.actionExportRtsz) },
                            leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onExportVault(VaultExportFormat.RTSZ_ROYAL_ARCHIVE)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.actionExportCsv) },
                            leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onExportVault(VaultExportFormat.ENTERPRISE_CSV)
                            }
                        )
                    }
                }

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

                ExpressiveMorphingIconButton(
                    icon = Icons.Outlined.Podcasts,
                    contentDescription = strings.actionBroadcastInput,
                    isActive = broadcastEnabled,
                    activeTint = NexusTheme.extendedColors.broadcastActiveBanner,
                    activeContainer = NexusTheme.extendedColors.broadcastActiveBanner.copy(alpha = 0.16f),
                    onClick = onToggleBroadcast
                )

                ExpressiveMorphingIconButton(
                    icon = Icons.Outlined.Info,
                    contentDescription = strings.inspectorTitle,
                    isActive = isInspectorVisible,
                    onClick = onToggleInspector
                )

                ExpressiveMorphingIconButton(
                    icon = Icons.Outlined.Terminal,
                    contentDescription = strings.drawerAuditLogs,
                    isActive = isBottomDrawerVisible,
                    onClick = onToggleBottomDrawer
                )

                ExpressiveMorphingIconButton(
                    icon = if (isVaultLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    contentDescription = strings.actionLockVault,
                    isActive = isVaultLocked,
                    onClick = onLockVault
                )

                Box {
                    ExpressiveMorphingIconButton(
                        icon = Icons.Outlined.Language,
                        contentDescription = strings.actionLanguagePicker,
                        isActive = langMenuExpanded,
                        onClick = { langMenuExpanded = true }
                    )
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
    onFilterQueryChanged: (String) -> Unit,
    smartFolderConnectionsProvider: (SmartFolder) -> List<VaultConnection>,
    onToggleSmartFolder: (String) -> Unit,
    onNewSmartFolder: () -> Unit,
    onToggleDynamicFolder: (DynamicFolder) -> Unit,
    onReloadDynamicFolder: (DynamicFolder) -> Unit,
    onNewFolder: () -> Unit,
    onEditFolder: (VaultFolder) -> Unit,
    onConnectAllInFolder: (String) -> Unit,
    onSelectConnection: (String) -> Unit,
    onEditConnection: (VaultConnection) -> Unit,
    onConnectWithOptions: (VaultConnection) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDuplicateConnection: (VaultConnection) -> Unit,
    onDeleteConnection: (String) -> Unit,
    onLaunchConnection: (VaultConnection, QuickLaunchPlacement) -> Unit
) {
    val doc = uiState.vaultDocument
    val query = uiState.sidebarFilterQuery.trim().lowercase()

    fun matchesFilter(conn: VaultConnection): Boolean {
        if (conn.id in doc.tombstonedObjectIds) return false
        if (query.isEmpty()) return true
        return conn.name.lowercase().contains(query) ||
            conn.host.lowercase().contains(query) ||
            conn.protocol.name.lowercase().contains(query) ||
            conn.osFamily.lowercase().contains(query) ||
            conn.tags.any { it.lowercase().contains(query) }
    }

    val favorites = remember(doc.connections, doc.tombstonedObjectIds, query) {
        doc.connections.filter { it.favorite && matchesFilter(it) }
    }
    val recentConnections = remember(doc.recentConnectionIds, doc.connections, doc.tombstonedObjectIds, query) {
        doc.recentConnectionIds.mapNotNull { id ->
            doc.connections.firstOrNull { it.id == id && matchesFilter(it) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Instant Sidebar Filter Bar
        OutlinedTextField(
            value = uiState.sidebarFilterQuery,
            onValueChange = onFilterQueryChanged,
            placeholder = { Text(strings.sidebarFilterPlaceholder, style = MaterialTheme.typography.bodySmall) },
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            trailingIcon = {
                if (uiState.sidebarFilterQuery.isNotEmpty()) {
                    IconButton(onClick = { onFilterQueryChanged("") }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(99.dp),
            modifier = Modifier.fillMaxWidth()
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 1. Pinned Favorites Section
            if (favorites.isNotEmpty()) {
                item(key = "header-favorites") {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = strings.vaultSectionFavorites,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                items(favorites, key = { "fav-${it.id}" }) { conn ->
                    ConnectionTreeRow(
                        connection = conn,
                        isSelected = conn.id == uiState.selectedConnectionId,
                        strings = strings,
                        onSelect = { onSelectConnection(conn.id) },
                        onEdit = { onEditConnection(conn) },
                        onConnectWithOptions = { onConnectWithOptions(conn) },
                        onToggleFavorite = { onToggleFavorite(conn.id) },
                        onDuplicate = { onDuplicateConnection(conn) },
                        onDelete = { onDeleteConnection(conn.id) },
                        onLaunch = { placement -> onLaunchConnection(conn, placement) }
                    )
                }
            }

            // 2. Recent Sessions Section (shown when not filtering)
            if (query.isEmpty() && recentConnections.isNotEmpty()) {
                item(key = "header-recents") {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = strings.vaultSectionRecents,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                items(recentConnections.take(4), key = { "recent-${it.id}" }) { conn ->
                    ConnectionTreeRow(
                        connection = conn,
                        isSelected = conn.id == uiState.selectedConnectionId,
                        strings = strings,
                        onSelect = { onSelectConnection(conn.id) },
                        onEdit = { onEditConnection(conn) },
                        onConnectWithOptions = { onConnectWithOptions(conn) },
                        onToggleFavorite = { onToggleFavorite(conn.id) },
                        onDuplicate = { onDuplicateConnection(conn) },
                        onDelete = { onDeleteConnection(conn.id) },
                        onLaunch = { placement -> onLaunchConnection(conn, placement) }
                    )
                }
            }

            // 3. Smart Folders Section
            item(key = "header-smart-folders") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = strings.vaultSectionSmartFolders,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    IconButton(onClick = onNewSmartFolder, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = strings.actionNewSmartFolder,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
            doc.smartFolders.forEach { smart ->
                val matchedConnections = smartFolderConnectionsProvider(smart).filter(::matchesFilter)
                val isExpanded = uiState.selectedSmartFolderId == smart.id
                item(key = "smart-${smart.id}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (isExpanded) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                                else Color.Transparent
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
                            onEdit = { onEditConnection(conn) },
                            onConnectWithOptions = { onConnectWithOptions(conn) },
                            onToggleFavorite = { onToggleFavorite(conn.id) },
                            onDuplicate = { onDuplicateConnection(conn) },
                            onDelete = { onDeleteConnection(conn.id) },
                            onLaunch = { placement -> onLaunchConnection(conn, placement) }
                        )
                    }
                }
            }

            // 4. Dynamic Folders Section (Script / Cloud JSON Inventory)
            if (doc.dynamicFolders.isNotEmpty()) {
                item(key = "header-dynamic-folders") {
                    Text(
                        text = strings.vaultSectionDynamicFolders,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                doc.dynamicFolders.forEach { dyn ->
                    val hydrated = uiState.hydratedDynamicConnections[dyn.id].orEmpty().filter(::matchesFilter)
                    val isExpanded = uiState.selectedDynamicFolderId == dyn.id
                    item(key = "dyn-${dyn.id}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(
                                    if (isExpanded) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                                    else Color.Transparent
                                )
                                .clickable { onToggleDynamicFolder(dyn) }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DynamicFeed,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = dyn.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { onReloadDynamicFolder(dyn) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = strings.actionReloadDynamicFolder,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                    if (isExpanded) {
                        items(hydrated, key = { "dyn-${dyn.id}-${it.id}" }) { conn ->
                            ConnectionTreeRow(
                                connection = conn,
                                isSelected = conn.id == uiState.selectedConnectionId,
                                strings = strings,
                                onSelect = { onSelectConnection(conn.id) },
                                onEdit = { onEditConnection(conn) },
                                onConnectWithOptions = { onConnectWithOptions(conn) },
                                onToggleFavorite = { onToggleFavorite(conn.id) },
                                onDuplicate = { onDuplicateConnection(conn) },
                                onDelete = { onDeleteConnection(conn.id) },
                                onLaunch = { placement -> onLaunchConnection(conn, placement) }
                            )
                        }
                    }
                }
            }

            // 5. Hierarchical Folders & Connections Section
            item(key = "header-folders") {
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = strings.vaultSectionFolders,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    IconButton(
                        onClick = onNewFolder,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = strings.actionNewFolder,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            doc.folders.forEach { folder ->
                val folderConnections = doc.connections.filter {
                    it.parentFolderId == folder.id && matchesFilter(it)
                }
                if (query.isEmpty() || folderConnections.isNotEmpty()) {
                    val explicitFolderCred = if (folder.inheritable.credentialMode == InheritanceMode.OVERRIDE_EXPLICIT) {
                        doc.credentials.firstOrNull { it.id == folder.inheritable.explicitCredentialId }
                    } else {
                        null
                    }
                    item(key = "folder-${folder.id}") {
                        FolderTreeRow(
                            folder = folder,
                            explicitFolderCredentialName = explicitFolderCred?.name,
                            childCount = folderConnections.size,
                            strings = strings,
                            onEditFolder = { onEditFolder(folder) },
                            onConnectAllInFolder = { onConnectAllInFolder(folder.id) }
                        )
                    }
                    items(folderConnections, key = { it.id }) { conn ->
                        ConnectionTreeRow(
                            connection = conn,
                            isSelected = conn.id == uiState.selectedConnectionId,
                            strings = strings,
                            onSelect = { onSelectConnection(conn.id) },
                            onEdit = { onEditConnection(conn) },
                            onConnectWithOptions = { onConnectWithOptions(conn) },
                            onToggleFavorite = { onToggleFavorite(conn.id) },
                            onDuplicate = { onDuplicateConnection(conn) },
                            onDelete = { onDeleteConnection(conn.id) },
                            onLaunch = { placement -> onLaunchConnection(conn, placement) }
                        )
                    }
                }
            }

            val knownFolderIds = doc.folders.map { it.id }.toSet()
            val rootConnections = doc.connections.filter {
                (it.parentFolderId == null || it.parentFolderId !in knownFolderIds) && matchesFilter(it)
            }
            items(rootConnections, key = { "root-${it.id}" }) { conn ->
                ConnectionTreeRow(
                    connection = conn,
                    isSelected = conn.id == uiState.selectedConnectionId,
                    strings = strings,
                    onSelect = { onSelectConnection(conn.id) },
                    onEdit = { onEditConnection(conn) },
                    onConnectWithOptions = { onConnectWithOptions(conn) },
                    onToggleFavorite = { onToggleFavorite(conn.id) },
                    onDuplicate = { onDuplicateConnection(conn) },
                    onDelete = { onDeleteConnection(conn.id) },
                    onLaunch = { placement -> onLaunchConnection(conn, placement) }
                )
            }
        }
    }
}

@Composable
private fun FolderTreeRow(
    folder: VaultFolder,
    explicitFolderCredentialName: String?,
    childCount: Int,
    strings: WorkspaceLocalizedStrings,
    onEditFolder: () -> Unit,
    onConnectAllInFolder: () -> Unit
) {
    var folderMenuOpen by remember { mutableStateOf(false) }
    val folderAccent = parseHexColorOrNull(folder.inheritable.displayColorAccentHex)
        ?: MaterialTheme.colorScheme.primary

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .nexusDesktopPointerButtons(onSecondaryClick = { folderMenuOpen = true })
                .clickable(onClick = onEditFolder)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = folderAccent,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (explicitFolderCredentialName != null) {
                        Text(
                            text = explicitFolderCredentialName,
                            style = NexusTheme.monospace.keycapShortcut,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (childCount > 0) {
                    IconButton(
                        onClick = onConnectAllInFolder,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = strings.actionConnectAllFolder,
                            modifier = Modifier.size(15.dp),
                            tint = folderAccent
                        )
                    }
                }
                IconButton(
                    onClick = onEditFolder,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Key,
                        contentDescription = strings.actionEditFolderCredentials,
                        modifier = Modifier.size(14.dp),
                        tint = if (explicitFolderCredentialName != null) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        DropdownMenu(
            expanded = folderMenuOpen,
            onDismissRequest = { folderMenuOpen = false }
        ) {
            if (childCount > 0) {
                DropdownMenuItem(
                    text = { Text(strings.actionConnectAllFolder) },
                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    onClick = {
                        folderMenuOpen = false
                        onConnectAllInFolder()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(strings.actionEditFolderCredentials) },
                leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onEditFolder()
                }
            )
        }
    }
}

@Composable
private fun ConnectionTreeRow(
    connection: VaultConnection,
    isSelected: Boolean,
    strings: WorkspaceLocalizedStrings,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onConnectWithOptions: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onLaunch: (QuickLaunchPlacement) -> Unit
) {
    var contextMenuOpen by remember { mutableStateOf(false) }
    val uiProto = NexusWorkspaceViewModel.mapProtocolToUiKind(connection.protocol)
    val customAccent = parseHexColorOrNull(connection.inheritable.displayColorAccentHex)
    val effectiveIconTint = customAccent ?: protocolAccentColor(uiProto)

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                    else Color.Transparent
                )
                .nexusDesktopPointerButtons(onSecondaryClick = { contextMenuOpen = true })
                .clickable {
                    onSelect()
                    onLaunch(QuickLaunchPlacement.ACTIVE_PANE_TAB)
                }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (customAccent != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(customAccent)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = protocolIcon(uiProto),
                contentDescription = null,
                tint = effectiveIconTint,
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
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = if (connection.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    contentDescription = strings.actionToggleFavorite,
                    modifier = Modifier.size(14.dp),
                    tint = if (connection.favorite) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = strings.actionEditConnection,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = contextMenuOpen,
            onDismissRequest = { contextMenuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text(strings.actionConnectWithOptions) },
                leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onConnectWithOptions()
                }
            )
            DropdownMenuItem(
                text = { Text(strings.actionEditConnection) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onEdit()
                }
            )
            DropdownMenuItem(
                text = { Text(strings.actionDuplicateConnection) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onDuplicate()
                }
            )
            DropdownMenuItem(
                text = { Text(strings.actionToggleFavorite) },
                leadingIcon = {
                    Icon(
                        if (connection.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = null
                    )
                },
                onClick = {
                    contextMenuOpen = false
                    onToggleFavorite()
                }
            )
            HorizontalDivider()
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
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(strings.actionDeleteItem, color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    contextMenuOpen = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun ContextualPropertyInspectorPane(
    profile: EffectiveConnectionProfile?,
    parentFolder: VaultFolder?,
    todoItems: List<VaultTodoItem>,
    strings: WorkspaceLocalizedStrings,
    onEditConnection: (VaultConnection) -> Unit,
    onConnectWithOptions: (VaultConnection) -> Unit,
    onEditParentFolderCredentials: (VaultFolder) -> Unit,
    onSaveNotesAndCustomProps: (String, String, List<CustomPropertyEntry>, String?) -> Unit,
    onAddTodo: (String, String) -> Unit,
    onToggleTodo: (String) -> Unit,
    onGenerateSshKey: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(strings.inspectorProtocolEndpoint, style = MaterialTheme.typography.labelMedium)
                    Text(profile.connection.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${profile.connection.protocol.name} • ${profile.connection.host}",
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { onEditConnection(profile.connection) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.actionEditConnection, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = { onConnectWithOptions(profile.connection) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.actionConnectWithOptions, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
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
                    if (parentFolder != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { onEditParentFolderCredentials(parentFolder) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.actionEditFolderCredentials, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            val rdpSettings = profile.connection.settings as? ProtocolSpecificSettings.Rdp
            if (rdpSettings != null) {
                val audioLabel = when (rdpSettings.audioRedirectionMode) {
                    RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE -> strings.rdpAudioLocal
                    RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST -> strings.rdpAudioRemote
                    RdpAudioRedirectionMode.DO_NOT_PLAY -> strings.rdpAudioMute
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("RDP Classic Options", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = buildString {
                                append("Port ${rdpSettings.port} • ${rdpSettings.colorDepthBits}-bit")
                                if (rdpSettings.connectToConsoleAdmin) append(" • /admin")
                                if (rdpSettings.hiDpiEnabled) append(" • HiDPI ${rdpSettings.desktopScaleFactorPercent}%")
                            },
                            style = NexusTheme.monospace.keycapShortcut,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = buildString {
                                append("Audio: $audioLabel")
                                if (rdpSettings.redirectClipboard) append(" • Clipboard")
                                if (rdpSettings.redirectDrives) append(" • Drives")
                                if (rdpSettings.redirectPrinters) append(" • Printers")
                                if (rdpSettings.redirectSmartcardYubiKey) append(" • Smartcard")
                            },
                            style = NexusTheme.monospace.keycapShortcut,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = buildString {
                                if (rdpSettings.nlaSecurityRequired) append("NLA") else append("RDP-Sec")
                                if (rdpSettings.restrictedAdminMode) append(" • RestrictedAdmin")
                                if (rdpSettings.enableFontSmoothing) append(" • ClearType")
                                if (rdpSettings.rdGatewayHostname.isNotBlank()) {
                                    append(" • RDG ${rdpSettings.rdGatewayHostname}")
                                }
                            },
                            style = NexusTheme.monospace.keycapShortcut,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
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

            // Live 2FA/TOTP Authenticator, Custom Properties (`$CustomProperty.*$`), Notes, and To-Do Checklist
            InspectorExtendedRoyalCards(
                profile = profile,
                todoItems = todoItems,
                strings = strings,
                onSaveNotesAndCustomProps = { notes, props, accent ->
                    onSaveNotesAndCustomProps(profile.connection.id, notes, props, accent)
                },
                onAddTodo = { title -> onAddTodo(profile.connection.id, title) },
                onToggleTodo = onToggleTodo
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

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
