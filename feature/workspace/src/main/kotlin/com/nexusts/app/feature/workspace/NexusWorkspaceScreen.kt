package com.nexusts.app.feature.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Folder as FilledFolderIcon
import androidx.compose.material.icons.filled.FolderOpen as FilledFolderOpenIcon
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HorizontalSplit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.PowerSettingsNew
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
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.components.ExpressiveMorphingIconButton
import com.nexusts.app.core.ui.components.ExpressiveSplitActionButton
import com.nexusts.app.core.ui.components.NexusActionChip
import com.nexusts.app.core.ui.components.NexusBrandEmblem
import com.nexusts.app.core.ui.components.NexusLanguageFlagBadge
import com.nexusts.app.core.ui.components.parseHexColorOrNull
import com.nexusts.app.core.ui.input.LocalNexusShortcutEngine
import com.nexusts.app.core.ui.input.NexusShortcutAction
import com.nexusts.app.core.ui.input.nexusDesktopPointerButtons
import com.nexusts.app.core.ui.input.nexusHoverTracker
import com.nexusts.app.core.ui.input.rememberDesktopHoverState
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
import com.nexusts.app.core.vault.importer.DetectedExternalVaultFormat
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
import com.nexusts.app.feature.workspace.studios.NewDynamicFolderDialog
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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Complete Adaptive Googlebook OS & Android Tablet Enterprise Workspace Screen
 * built on Google Material Design 3 guidelines with 100% Royal TS feature parity.
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
    onSaveVaultSyncPassphrase: (String, Boolean) -> Unit = { _, _ -> },
    onClearVaultSyncPassphrase: () -> Unit = {},
    onCreateLiveSafDriveVault: () -> Unit = {},
    onLinkExistingSafDriveVault: () -> Unit = {},
    onUnlinkSafDriveVault: () -> Unit = {},
    onAuthorizeAndSyncOAuthDrive: () -> Unit = {},
    onSignOutOAuthDrive: () -> Unit = {},
    onToggleAutoSyncOnSave: (Boolean) -> Unit = { viewModel.setDriveAutoSyncOnSave(it) },
    onCheckGitHubReleases: () -> Unit = {},
    onOpenGitHubReleaseUrl: (String) -> Unit = {},
    onToggleAutoCheckUpdates: (Boolean) -> Unit = { viewModel.setAutoCheckUpdatesState(it) },
    onTriggerBrowserUpdate: (String) -> Unit = onOpenGitHubReleaseUrl,
    onDownloadVerifiedUpdateToFiles: () -> Unit = {},
    onSkipUpdateVersion: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val rootPane by viewModel.tilingController.rootNode.collectAsState()
    val focusedPaneId by viewModel.tilingController.focusedPaneId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.observePendingReDockTabs()
    }

    LaunchedEffect(uiState.userSnackbarMessage) {
        val msg = uiState.userSnackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeUserSnackbar()
    }

    // Full-Screen Zero-Knowledge Vault Lock Shield:
    // Completely isolates sidebar, studios, inspector, sessions, Ctrl+K, and exports while locked.
    if (uiState.isVaultLocked) {
        FullScreenVaultUnlockShield(
            vaultTitle = uiState.pendingImportFileName ?: uiState.vaultDocument.name,
            unlockError = uiState.vaultUnlockError,
            hasPreImportBackup = uiState.hasPreImportBackup,
            strings = strings,
            onUnlock = { pass -> viewModel.unlockVaultWithPassphrase(pass.toCharArray()) },
            onCancelPendingImport = viewModel::cancelPendingVaultImport,
            onOpenVaultPicker = onRequestImportFilePicker,
            modifier = modifier
        )
        return
    }

    val activeDockedTabs = remember(rootPane) {
        viewModel.tilingController.allActiveTabs()
    }
    val activeSessionCountByConnectionId = remember(activeDockedTabs, uiState.vaultDocument, uiState.auditLogs.size) {
        val counts = mutableMapOf<String, Int>()
        activeDockedTabs.forEach { tab ->
            counts[tab.connectionId] = (counts[tab.connectionId] ?: 0) + 1
        }
        val dockedTabIds = activeDockedTabs.map { it.tabId }.toSet()
        viewModel.sessionStore.tabConnectionIds.forEach { (tabId, connId) ->
            if (tabId !in dockedTabIds &&
                (viewModel.sessionStore.sshControllers.containsKey(tabId) ||
                    viewModel.sessionStore.rdpSessions.containsKey(tabId))
            ) {
                counts[connId] = (counts[connId] ?: 0) + 1
            }
        }
        counts.toMap()
    }
    val activeConnectionIds = remember(activeSessionCountByConnectionId) {
        activeSessionCountByConnectionId.filterValues { it > 0 }.keys
    }
    val activeSessionCount = remember(activeSessionCountByConnectionId, activeDockedTabs) {
        activeSessionCountByConnectionId.values.sum().coerceAtLeast(activeDockedTabs.size)
    }
    val pendingTodoCount = remember(uiState.vaultDocument.todoItems, uiState.vaultDocument.tombstonedObjectIds) {
        uiState.vaultDocument.todoItems.count { !it.completed && it.id !in uiState.vaultDocument.tombstonedObjectIds }
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

    val isDriveLinked = uiState.driveLinkedSafLabel.isNotBlank() || uiState.driveSignedInEmail.isNotBlank()
    val currentSupportedLocale = remember(strings.appName, strings.actionNewConnection) {
        NexusLocaleController.currentSupportedLocale()
    }

    CompositionLocalProvider(LocalNexusShortcutEngine provides viewModel.shortcutEngine) {
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
                    isDriveLinked = isDriveLinked,
                    isSidebarVisible = uiState.isSidebarVisible,
                    isInspectorVisible = uiState.isInspectorVisible,
                    isBottomDrawerVisible = uiState.isBottomDrawerVisible,
                    isVaultLocked = uiState.isVaultLocked,
                    remoteKeyCaptureEnabled = uiState.remoteKeyCaptureEnabled,
                    hasActiveSessions = activeSessionCount > 0,
                    broadcastEnabled = uiState.broadcastModeEnabled,
                    flexibleUpdateDownloaded = flexibleUpdateDownloaded,
                    githubUpdateAvailable = uiState.updateAvailable,
                    githubUpdateTag = uiState.updateLatestTag,
                    currentLocale = currentSupportedLocale,
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
                    onNewDynamicFolder = viewModel::openNewDynamicFolderDialog,
                    onNewTemplate = viewModel::openNewTemplateDialog,
                    onNewLocalShell = viewModel::launchLocalShellTab,
                    onImportRtsz = onRequestImportFilePicker,
                    onExportVault = onRequestExportVault,
                    onSyncGoogleDrive = onRequestGoogleDriveSync,
                    onToggleRemoteKeyCapture = viewModel::toggleRemoteKeyCapture,
                    onToggleBroadcast = viewModel::toggleGlobalBroadcast,
                    onLockVault = viewModel::openLockVaultDialog,
                    onOpenLanguageDialog = viewModel::openLanguageDialog,
                    onOpenAutoUpdateDialog = viewModel::openAutoUpdateDialog,
                    onCompleteFlexibleUpdate = onCompleteFlexibleUpdate
                )
            },
            sidebarTreeContent = {
                VaultHierarchySidebarTree(
                    uiState = uiState,
                    strings = strings,
                    activeSessionCountByConnectionId = activeSessionCountByConnectionId,
                    onFilterQueryChanged = viewModel::updateSidebarFilterQuery,
                    smartFolderConnectionsProvider = viewModel::evaluateSmartFolderConnections,
                    onToggleSmartFolder = viewModel::toggleSmartFolderSelection,
                    onNewSmartFolder = viewModel::openNewSmartFolderDialog,
                    onDeleteSmartFolder = viewModel::deleteSmartFolderFromVault,
                    onNewDynamicFolder = viewModel::openNewDynamicFolderDialog,
                    onToggleDynamicFolder = viewModel::toggleDynamicFolder,
                    onReloadDynamicFolder = viewModel::reloadDynamicFolder,
                    onDeleteDynamicFolder = viewModel::deleteDynamicFolderFromVault,
                    onNewFolder = viewModel::openNewFolderDialog,
                    onNewConnection = viewModel::openNewConnectionDialog,
                    onNewRdpInFolder = { folderId ->
                        viewModel.openNewConnectionInFolder(folderId, VaultProtocolType.RDP)
                    },
                    onNewSshInFolder = { folderId ->
                        viewModel.openNewConnectionInFolder(folderId, VaultProtocolType.SSH)
                    },
                    onNewSubfolder = viewModel::openNewSubfolder,
                    onEditFolder = viewModel::openEditFolderDialog,
                    onDuplicateFolder = viewModel::duplicateFolder,
                    onDeleteFolder = viewModel::deleteFolder,
                    onConnectAllInFolder = { folderId ->
                        viewModel.connectAllInFolder(folderId) { tab ->
                            onDetachTabToNativeWindow(tab, null)
                        }
                    },
                    onDisconnectAllInFolder = { folderId ->
                        viewModel.disconnectAllInFolder(folderId)
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
                    onDisconnectConnection = { connId ->
                        viewModel.disconnectConnectionSessions(connId)
                    },
                    onOpenMoveConnectionDialog = viewModel::openMoveConnectionDialog,
                    onMoveConnectionToFolder = { connId, targetFolderId ->
                        viewModel.moveConnectionToFolder(connId, targetFolderId)
                    },
                    onLaunchConnection = { conn, placement ->
                        viewModel.selectRailDestination("connections")
                        viewModel.launchConnection(conn, placement) { detachedTab ->
                            onDetachTabToNativeWindow(detachedTab, null)
                        }
                    },
                    onLaunchConnectionInSplitDown = { conn ->
                        viewModel.selectRailDestination("connections")
                        viewModel.launchConnectionInSplitDown(conn)
                    },
                    onLaunchLocalShell = viewModel::launchLocalShellTab,
                    onImportVault = onRequestImportFilePicker
                )
            },
            mainWorkspaceContent = {
                when (uiState.activeRailId) {
                    "templates" -> ConnectionTemplatesStudioView(
                        templates = uiState.vaultDocument.templates,
                        strings = strings,
                        onCreateConnectionFromTemplate = viewModel::createConnectionFromTemplate,
                        onOpenNewTemplateDialog = viewModel::openNewTemplateDialog,
                        onDeleteTemplate = viewModel::deleteTemplateFromVault,
                        onSaveTemplate = viewModel::saveTemplateToVault,
                        onDuplicateTemplate = viewModel::duplicateTemplate
                    )

                    "credentials" -> CredentialVaultStudioView(
                        credentials = uiState.vaultDocument.credentials,
                        strings = strings,
                        onGenerateEd25519Key = viewModel::generateAndStoreSshKeyPair,
                        onCreateCredential = viewModel::addCredentialToVault,
                        onUpdateCredential = viewModel::updateCredentialInVault,
                        onDuplicateCredential = viewModel::duplicateCredential,
                        onDeleteCredential = viewModel::deleteCredentialFromVault,
                        onRequestImportVaultFile = onRequestImportFilePicker
                    )

                    "gateways" -> SecureGatewayStudioView(
                        gateways = uiState.vaultDocument.gateways,
                        healthReports = uiState.gatewayHealthReports,
                        strings = strings,
                        onProbeGateway = viewModel::probeGatewayLatency,
                        onAddGateway = viewModel::addGatewayToVault,
                        activeLeases = uiState.activeGatewayLeases,
                        credentials = uiState.vaultDocument.credentials,
                        onUpdateGateway = viewModel::updateGatewayInVault,
                        onDeleteGateway = viewModel::deleteGatewayFromVault,
                        onOpenGatewayLease = { gw -> viewModel.openGatewayLoopbackLease(gw) },
                        onCloseGatewayLease = viewModel::closeGatewayLease
                    )

                    "tasks" -> AutomationTasksStudioView(
                        commandTasks = uiState.vaultDocument.commandTasks,
                        keySequenceTasks = uiState.vaultDocument.keySequenceTasks,
                        strings = strings,
                        onRunCommandTask = viewModel::executeCommandTask,
                        onRunKeySequenceTask = viewModel::executeKeySequenceTask,
                        onAddCommandTask = viewModel::addCommandTaskToVault,
                        onUpdateCommandTask = viewModel::updateCommandTaskInVault,
                        onAddKeySequenceTask = viewModel::addKeySequenceTaskToVault,
                        onUpdateKeySequenceTask = viewModel::updateKeySequenceTaskInVault,
                        onDeleteCommandTask = viewModel::deleteCommandTask,
                        onDeleteKeySequenceTask = viewModel::deleteKeySequenceTask
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
                        rememberVaultPassphrase = uiState.driveRememberVaultPassphrase,
                        onSaveVaultSyncPassphrase = onSaveVaultSyncPassphrase,
                        onClearVaultSyncPassphrase = onClearVaultSyncPassphrase,
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
                            viewModel.transferTabToDetachedWindow(tab.tabId)?.let { extracted ->
                                onDetachTabToNativeWindow(extracted, displayId)
                            }
                        },
                        onToggleTabBroadcast = { tab ->
                            viewModel.tilingController.updateTab(tab.tabId) {
                                it.copy(broadcastEnabled = !it.broadcastEnabled)
                            }
                        },
                        onReconnectTab = viewModel::reconnectSessionTab,
                        onCloseEmptyPane = viewModel.tilingController::closeEmptyPane,
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
                                onToggleTodo = viewModel::toggleTodoItem,
                                onDeleteTodo = viewModel::deleteTodoItem,
                                onImportVault = onRequestImportFilePicker,
                                onNewFolder = viewModel::openNewFolderDialog,
                                onNewRdpInFolder = { folderId ->
                                    viewModel.openNewConnectionInFolder(folderId, VaultProtocolType.RDP)
                                },
                                onNewSshInFolder = { folderId ->
                                    viewModel.openNewConnectionInFolder(folderId, VaultProtocolType.SSH)
                                },
                                onNewSubfolder = viewModel::openNewSubfolder,
                                onEditFolder = viewModel::openEditFolderDialog,
                                onDuplicateFolder = viewModel::duplicateFolder,
                                onDeleteFolder = viewModel::deleteFolder,
                                onDisconnectAllInFolder = { folderId ->
                                    viewModel.disconnectAllInFolder(folderId)
                                },
                                onLaunchConnectionInSplitRight = { conn ->
                                    viewModel.launchConnection(conn, QuickLaunchPlacement.SPLIT_RIGHT_PANE)
                                },
                                onLaunchConnectionInSplitDown = viewModel::launchConnectionInSplitDown,
                                onLaunchConnectionDetached = { conn ->
                                    viewModel.launchConnection(conn, QuickLaunchPlacement.DETACHED_NEW_WINDOW) { tab ->
                                        onDetachTabToNativeWindow(tab, null)
                                    }
                                },
                                onEditConnection = viewModel::openEditConnectionDialog,
                                onDuplicateConnection = viewModel::duplicateConnection,
                                onToggleFavorite = viewModel::toggleFavoriteConnection,
                                onDeleteConnection = viewModel::deleteConnection,
                                onDisconnectConnection = { connId ->
                                    viewModel.disconnectConnectionSessions(connId)
                                },
                                onMoveConnectionToFolder = viewModel::openMoveConnectionDialog,
                                onLaunchLocalShell = viewModel::launchLocalShellTab,
                                activeConnectionIds = activeConnectionIds
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
                    todoItems = uiState.vaultDocument.todoItems.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
                    strings = strings,
                    onEditConnection = viewModel::openEditConnectionDialog,
                    onConnectWithOptions = viewModel::openConnectWithOptions,
                    onEditParentFolderCredentials = viewModel::openEditFolderDialog,
                    onSaveNotesAndCustomProps = { connId, notes, props, accent ->
                        viewModel.updateConnectionNotesAndCustomProps(connId, notes, props, accent)
                    },
                    onDeleteCustomProperty = viewModel::deleteCustomProperty,
                    onAddTodo = { connId, title -> viewModel.addTodoItem(title, targetObjectId = connId) },
                    onToggleTodo = viewModel::toggleTodoItem,
                    onDeleteTodo = viewModel::deleteTodoItem,
                    onGenerateSshKey = viewModel::generateAndStoreSshKeyPair
                )
            },
            bottomStatusDrawerContent = {
                BottomTelemetryAndBroadcastDrawer(
                    logs = uiState.auditLogs,
                    strings = strings,
                    onBroadcastCommand = viewModel::sendBroadcastCommandLine,
                    onClearLogs = viewModel::clearAuditLogs
                )
            },
            floatingOverlayContent = {
                QuickConnectCommandPalette(
                    visible = uiState.isQuickConnectVisible,
                    vaultCandidates = remember(uiState.vaultDocument, uiState.hydratedDynamicConnections) {
                        viewModel.buildQuickConnectCandidates()
                    },
                    placeholderText = strings.quickConnectPlaceholder,
                    footerHintText = strings.quickConnectFooterHint,
                    onDismiss = viewModel::dismissQuickConnect,
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
                    folders = uiState.vaultDocument.folders.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
                    credentials = uiState.vaultDocument.credentials.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
                    gateways = uiState.vaultDocument.gateways.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
                    resolveFolderPreview = viewModel::resolveFolderInheritedPreview,
                    strings = strings,
                    onDismiss = viewModel::dismissNewConnectionDialog,
                    onSaveConnection = viewModel::saveConnectionToVault,
                    sshConnections = remember(uiState.vaultDocument.connections, uiState.vaultDocument.tombstonedObjectIds) {
                        uiState.vaultDocument.connections.filter {
                            it.protocol == VaultProtocolType.SSH && it.id !in uiState.vaultDocument.tombstonedObjectIds
                        }
                    },
                    defaultFolderId = uiState.newConnectionDefaultFolderId,
                    defaultProtocol = uiState.newConnectionDefaultProtocol
                )
                val folderChildCount = uiState.editingFolder?.let { f ->
                    uiState.vaultDocument.connections.count {
                        it.parentFolderId == f.id && it.id !in uiState.vaultDocument.tombstonedObjectIds
                    }
                } ?: 0
                FolderPropertiesAndCredentialDialog(
                    visible = uiState.isFolderDialogVisible,
                    editingFolder = uiState.editingFolder,
                    allFolders = uiState.vaultDocument.folders.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
                    credentials = uiState.vaultDocument.credentials.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
                    childConnectionCount = folderChildCount,
                    strings = strings,
                    onDismiss = viewModel::dismissFolderDialog,
                    onSaveFolderAndCredential = viewModel::saveFolderAndCredentialToVault,
                    onDeleteFolder = viewModel::deleteFolder,
                    defaultParentFolderId = uiState.newFolderDefaultParentId
                )
                ConnectWithOptionsDialog(
                    targetConnection = uiState.connectWithOptionsTarget,
                    credentials = uiState.vaultDocument.credentials.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
                    templates = uiState.vaultDocument.templates.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
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
                NewDynamicFolderDialog(
                    visible = uiState.isNewDynamicFolderDialogVisible,
                    folders = uiState.vaultDocument.folders.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
                    strings = strings,
                    onDismiss = viewModel::dismissNewDynamicFolderDialog,
                    onSaveDynamicFolder = viewModel::saveDynamicFolderToVault
                )
                NewTemplateDialog(
                    visible = uiState.isNewTemplateDialogVisible,
                    strings = strings,
                    onDismiss = viewModel::dismissNewTemplateDialog,
                    onSaveTemplate = viewModel::saveTemplateToVault
                )
                LanguageSelectionDialog(
                    visible = uiState.isLanguageDialogVisible,
                    currentLocale = currentSupportedLocale,
                    strings = strings,
                    onSelectLocale = { locale ->
                        NexusLocaleController.switchLanguage(locale)
                        viewModel.dismissLanguageDialog()
                    },
                    onDismiss = viewModel::dismissLanguageDialog
                )
                MoveConnectionToFolderDialog(
                    targetConnection = uiState.movingConnectionTarget,
                    folders = uiState.vaultDocument.folders.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
                    strings = strings,
                    onMoveToFolder = { connId, targetFolderId ->
                        viewModel.moveConnectionToFolder(connId, targetFolderId)
                    },
                    onDismiss = viewModel::dismissMoveConnectionDialog
                )
                PlayProtectAutoUpdateDialog(
                    visible = uiState.isAutoUpdateDialogVisible,
                    uiState = uiState,
                    strings = strings,
                    onToggleAutoCheck = onToggleAutoCheckUpdates,
                    onCheckNow = onCheckGitHubReleases,
                    onTriggerBrowserUpdate = {
                        val targetUrl = uiState.updateApkDownloadUrl.ifBlank { uiState.updateReleaseHtmlUrl }
                        if (targetUrl.isNotBlank()) {
                            onTriggerBrowserUpdate(targetUrl)
                        }
                    },
                    onDownloadVerifiedToFiles = onDownloadVerifiedUpdateToFiles,
                    onSkipVersion = {
                        if (uiState.updateLatestTag.isNotBlank()) {
                            onSkipUpdateVersion(uiState.updateLatestTag)
                        }
                        viewModel.dismissAutoUpdateDialog()
                    },
                    onDismiss = viewModel::dismissAutoUpdateDialog
                )
                LockVaultPassphraseDialog(
                    visible = uiState.isLockVaultDialogVisible,
                    strings = strings,
                    onDismiss = viewModel::dismissLockVaultDialog,
                    onConfirmLock = { pass -> viewModel.lockVaultWithPassphrase(pass.toCharArray()) }
                )
                EncryptedImportPassphraseDialog(
                    pendingFileName = uiState.pendingEncryptedImportFileName,
                    pendingFormat = uiState.pendingEncryptedImportFormat,
                    errorMessage = uiState.pendingEncryptedImportError,
                    strings = strings,
                    onDismiss = viewModel::dismissEncryptedImportDialog,
                    onSubmitPassphrase = { pass -> viewModel.submitEncryptedImportPassword(pass.toCharArray()) }
                )
                ImportErrorAlertDialog(
                    errorMessage = uiState.importErrorDialogMessage,
                    strings = strings,
                    onDismiss = viewModel::dismissImportErrorDialog
                )
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            },
            modifier = modifier
        )
    }
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
        SessionProtocolKind.RDP -> {
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
        SessionProtocolKind.AZURE_VM, SessionProtocolKind.WINDOWS_TS -> {
            val cloudSettings = (conn?.settings as? ProtocolSpecificSettings.CloudVirtualization)
                ?: ProtocolSpecificSettings.CloudVirtualization(apiEndpointUrl = "https://${tab.subtitleHost}")
            CloudHypervisorConsoleView(
                protocol = conn?.protocol ?: if (tab.protocol == SessionProtocolKind.WINDOWS_TS) {
                    VaultProtocolType.WINDOWS_TERMINAL_SERVICES
                } else {
                    VaultProtocolType.PROXMOX_VE
                },
                endpointHost = tab.subtitleHost,
                settings = cloudSettings,
                secret = secret,
                openConsoleCallback = { vmName, vmIp, vmProto ->
                    val dynamicVmConn = VaultConnection(
                        name = vmName,
                        host = vmIp,
                        protocol = vmProto,
                        settings = when (vmProto) {
                            VaultProtocolType.RDP -> ProtocolSpecificSettings.Rdp()
                            VaultProtocolType.VNC -> ProtocolSpecificSettings.Vnc(appleArdDiffieHellmanAuth = false)
                            VaultProtocolType.APPLE_SCREEN_SHARING -> ProtocolSpecificSettings.Vnc(appleArdDiffieHellmanAuth = true)
                            else -> ProtocolSpecificSettings.Ssh()
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
    isDriveLinked: Boolean,
    isSidebarVisible: Boolean,
    isInspectorVisible: Boolean,
    isBottomDrawerVisible: Boolean,
    isVaultLocked: Boolean,
    remoteKeyCaptureEnabled: Boolean,
    hasActiveSessions: Boolean,
    broadcastEnabled: Boolean,
    flexibleUpdateDownloaded: Boolean,
    githubUpdateAvailable: Boolean,
    githubUpdateTag: String,
    currentLocale: SupportedNexusLocale,
    strings: WorkspaceLocalizedStrings,
    onToggleSidebar: () -> Unit,
    onToggleInspector: () -> Unit,
    onToggleBottomDrawer: () -> Unit,
    onOpenQuickConnect: () -> Unit,
    onNewConnection: () -> Unit,
    onNewFolder: () -> Unit,
    onNewSmartFolder: () -> Unit,
    onNewDynamicFolder: () -> Unit,
    onNewTemplate: () -> Unit,
    onNewLocalShell: () -> Unit,
    onImportRtsz: () -> Unit,
    onExportVault: (VaultExportFormat) -> Unit,
    onSyncGoogleDrive: () -> Unit,
    onToggleRemoteKeyCapture: () -> Unit,
    onToggleBroadcast: () -> Unit,
    onLockVault: () -> Unit,
    onOpenLanguageDialog: () -> Unit,
    onOpenAutoUpdateDialog: () -> Unit,
    onCompleteFlexibleUpdate: () -> Unit
) {
    var createMenuExpanded by remember { mutableStateOf(false) }
    var toolsMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Window Identity Cluster
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExpressiveMorphingIconButton(
                    icon = Icons.Outlined.ViewSidebar,
                    contentDescription = null,
                    isActive = isSidebarVisible,
                    onClick = onToggleSidebar
                )
                NexusBrandEmblem(size = 36.dp, cornerRadius = 11.dp)
                Text(
                    text = strings.appName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (vaultName.isNotBlank() && vaultName != NexusWorkspaceViewModel.DEFAULT_VAULT_NAME) {
                    Surface(
                        shape = RoundedCornerShape(99.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.widthIn(max = 190.dp)
                    ) {
                        Text(
                            text = vaultName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // Center Google M3 Quick-Connect Omnibox (`Ctrl + K`) — generous 44dp pill
            val quickConnectHover = rememberDesktopHoverState()
            val quickConnectInteraction = remember { MutableInteractionSource() }
            val isQuickConnectHovered = quickConnectHover.value.isHovered
            Surface(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(min = 210.dp, max = 380.dp)
                    .padding(horizontal = 14.dp)
                    .height(44.dp)
                    .nexusHoverTracker(quickConnectHover, PointerIcon.Hand)
                    .clip(RoundedCornerShape(99.dp))
                    .clickable(
                        interactionSource = quickConnectInteraction,
                        indication = null,
                        onClick = onOpenQuickConnect
                    ),
                shape = RoundedCornerShape(99.dp),
                color = if (isQuickConnectHovered) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                border = BorderStroke(
                    1.dp,
                    if (isQuickConnectHovered) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isQuickConnectHovered) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = strings.quickConnectButton,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isQuickConnectHovered) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Surface(
                        shape = RoundedCornerShape(99.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest
                    ) {
                        Text(
                            text = "Ctrl+K",
                            style = NexusTheme.monospace.keycapShortcut,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Right Google M3 Action Cluster (Clean & Uncluttered)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (flexibleUpdateDownloaded) {
                    FilledTonalButton(
                        onClick = onCompleteFlexibleUpdate,
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.updateRestartAction, style = MaterialTheme.typography.labelLarge)
                    }
                } else if (githubUpdateAvailable) {
                    FilledTonalButton(
                        onClick = onOpenAutoUpdateDialog,
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (githubUpdateTag.isNotBlank()) {
                                "${strings.autoUpdateAvailableBadge} • $githubUpdateTag"
                            } else {
                                strings.autoUpdateAvailableBadge
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
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
                            text = { Text(strings.vaultSectionDynamicFolders) },
                            leadingIcon = { Icon(Icons.Outlined.DynamicFeed, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onNewDynamicFolder()
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

                if (isDriveLinked) {
                    NexusActionChip(
                        onClick = onSyncGoogleDrive,
                        label = String.format(strings.syncStatusSyncedFormat, vaultRevision),
                        leadingIcon = Icons.Outlined.CloudSync
                    )
                }

                if (broadcastEnabled) {
                    ExpressiveMorphingIconButton(
                        icon = Icons.Outlined.Podcasts,
                        contentDescription = strings.actionBroadcastInput,
                        isActive = true,
                        activeTint = NexusTheme.extendedColors.broadcastActiveBanner,
                        activeContainer = NexusTheme.extendedColors.broadcastActiveBanner.copy(alpha = 0.16f),
                        onClick = onToggleBroadcast
                    )
                }

                // Language Flag Trigger Pill (opens Material 3 LanguageSelectionDialog with vector flags)
                val langHover = rememberDesktopHoverState()
                val langInteraction = remember { MutableInteractionSource() }
                val isLangHovered = langHover.value.isHovered
                Surface(
                    modifier = Modifier
                        .height(40.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = strings.actionLanguagePicker
                        }
                        .nexusHoverTracker(langHover, PointerIcon.Hand)
                        .clip(RoundedCornerShape(99.dp))
                        .clickable(
                            interactionSource = langInteraction,
                            indication = null,
                            onClick = onOpenLanguageDialog
                        ),
                    shape = RoundedCornerShape(99.dp),
                    color = if (isLangHovered) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isLangHovered) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NexusLanguageFlagBadge(
                            languageCode = currentLocale.bcp47Tag,
                            width = 24.dp,
                            height = 16.dp,
                            cornerRadius = 4.dp
                        )
                        Text(
                            text = currentLocale.bcp47Tag.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                ExpressiveMorphingIconButton(
                    icon = Icons.Outlined.Info,
                    contentDescription = strings.inspectorTitle,
                    isActive = isInspectorVisible,
                    onClick = onToggleInspector
                )

                ExpressiveMorphingIconButton(
                    icon = if (isVaultLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    contentDescription = strings.actionLockVault,
                    isActive = isVaultLocked,
                    onClick = onLockVault
                )

                // Secondary Workspace Tools Menu (declutters top window bar)
                Box {
                    ExpressiveMorphingIconButton(
                        icon = Icons.Outlined.MoreVert,
                        contentDescription = "Workspace Tools",
                        isActive = toolsMenuExpanded || isBottomDrawerVisible || remoteKeyCaptureEnabled,
                        onClick = { toolsMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = toolsMenuExpanded,
                        onDismissRequest = { toolsMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (githubUpdateAvailable && githubUpdateTag.isNotBlank()) {
                                        "${strings.autoUpdateTitle} ($githubUpdateTag)"
                                    } else {
                                        strings.autoUpdateTitle
                                    }
                                )
                            },
                            leadingIcon = { Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null) },
                            onClick = {
                                toolsMenuExpanded = false
                                onOpenAutoUpdateDialog()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.drawerAuditLogs) },
                            leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                            trailingIcon = {
                                if (isBottomDrawerVisible) {
                                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            },
                            onClick = {
                                toolsMenuExpanded = false
                                onToggleBottomDrawer()
                            }
                        )
                        if (hasActiveSessions || remoteKeyCaptureEnabled) {
                            DropdownMenuItem(
                                text = { Text("Remote Key Capture") },
                                leadingIcon = { Icon(Icons.Outlined.Keyboard, contentDescription = null) },
                                trailingIcon = {
                                    if (remoteKeyCaptureEnabled) {
                                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                },
                                onClick = {
                                    toolsMenuExpanded = false
                                    onToggleRemoteKeyCapture()
                                }
                            )
                        }
                        if (hasActiveSessions || broadcastEnabled) {
                            DropdownMenuItem(
                                text = { Text(strings.actionBroadcastInput) },
                                leadingIcon = { Icon(Icons.Outlined.Podcasts, contentDescription = null) },
                                trailingIcon = {
                                    if (broadcastEnabled) {
                                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                },
                                onClick = {
                                    toolsMenuExpanded = false
                                    onToggleBroadcast()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val ROOT_DROP_TARGET_KEY = "__NEXUSTS_ROOT_LEVEL__"

@Stable
private class SidebarTreeDragDropState {
    var draggingConnection by mutableStateOf<VaultConnection?>(null)
    var pointerPositionInRoot by mutableStateOf(Offset.Zero)
    var hoveredDropTargetKey by mutableStateOf<String?>(null)
    private val targetBoundsInRoot = mutableStateMapOf<String, Rect>()

    val isDragging: Boolean
        get() = draggingConnection != null

    fun registerDropTarget(key: String, bounds: Rect) {
        targetBoundsInRoot[key] = bounds
    }

    fun unregisterDropTarget(key: String) {
        targetBoundsInRoot.remove(key)
        if (hoveredDropTargetKey == key) {
            hoveredDropTargetKey = null
        }
    }

    fun startDrag(connection: VaultConnection, initialRootPos: Offset) {
        draggingConnection = connection
        pointerPositionInRoot = initialRootPos
        updateHoveredTarget(initialRootPos)
    }

    fun updateDragByDelta(delta: Offset) {
        val next = pointerPositionInRoot + delta
        pointerPositionInRoot = next
        updateHoveredTarget(next)
    }

    fun updateDragAtRoot(rootPos: Offset) {
        pointerPositionInRoot = rootPos
        updateHoveredTarget(rootPos)
    }

    private fun updateHoveredTarget(rootPos: Offset) {
        val conn = draggingConnection ?: run {
            hoveredDropTargetKey = null
            return
        }
        val matchedKey = targetBoundsInRoot.entries
            .firstOrNull { (key, rect) ->
                rect.contains(rootPos) &&
                    when (key) {
                        ROOT_DROP_TARGET_KEY -> conn.parentFolderId != null
                        else -> key != conn.parentFolderId
                    }
            }
            ?.key
        hoveredDropTargetKey = matchedKey
    }

    fun finishDrag(onMoveConnectionToFolder: (String, String?) -> Unit) {
        val conn = draggingConnection
        val targetKey = hoveredDropTargetKey
        draggingConnection = null
        hoveredDropTargetKey = null
        if (conn != null && targetKey != null) {
            val destinationFolderId = if (targetKey == ROOT_DROP_TARGET_KEY) null else targetKey
            if (destinationFolderId != conn.parentFolderId) {
                onMoveConnectionToFolder(conn.id, destinationFolderId)
            }
        }
    }

    fun cancelDrag() {
        draggingConnection = null
        hoveredDropTargetKey = null
    }
}

private data class SidebarFolderEntry(
    val folder: VaultFolder,
    val depth: Int,
    val directConnections: List<VaultConnection>,
    val subtreeConnectionCount: Int,
    val subtreeActiveSessionCount: Int,
    val hasChildFolders: Boolean,
    val isExpanded: Boolean
)

@Composable
private fun VaultHierarchySidebarTree(
    uiState: WorkspaceUiState,
    strings: WorkspaceLocalizedStrings,
    activeSessionCountByConnectionId: Map<String, Int> = emptyMap(),
    onFilterQueryChanged: (String) -> Unit,
    smartFolderConnectionsProvider: (SmartFolder) -> List<VaultConnection>,
    onToggleSmartFolder: (String) -> Unit,
    onNewSmartFolder: () -> Unit,
    onDeleteSmartFolder: (String) -> Unit,
    onNewDynamicFolder: () -> Unit,
    onToggleDynamicFolder: (DynamicFolder) -> Unit,
    onReloadDynamicFolder: (DynamicFolder) -> Unit,
    onDeleteDynamicFolder: (String) -> Unit,
    onNewFolder: () -> Unit,
    onNewConnection: () -> Unit = {},
    onNewRdpInFolder: (String) -> Unit = {},
    onNewSshInFolder: (String) -> Unit = {},
    onNewSubfolder: (String) -> Unit = {},
    onEditFolder: (VaultFolder) -> Unit,
    onDuplicateFolder: (VaultFolder) -> Unit = {},
    onDeleteFolder: (String) -> Unit,
    onConnectAllInFolder: (String) -> Unit,
    onDisconnectAllInFolder: (String) -> Unit = {},
    onSelectConnection: (String) -> Unit,
    onEditConnection: (VaultConnection) -> Unit,
    onConnectWithOptions: (VaultConnection) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDuplicateConnection: (VaultConnection) -> Unit,
    onDeleteConnection: (String) -> Unit,
    onDisconnectConnection: (String) -> Unit = {},
    onOpenMoveConnectionDialog: (VaultConnection) -> Unit = {},
    onMoveConnectionToFolder: (String, String?) -> Unit = { _, _ -> },
    onLaunchConnection: (VaultConnection, QuickLaunchPlacement) -> Unit,
    onLaunchConnectionInSplitDown: (VaultConnection) -> Unit = {},
    onLaunchLocalShell: () -> Unit = {},
    onImportVault: () -> Unit = {}
) {
    val doc = uiState.vaultDocument
    val query = uiState.sidebarFilterQuery.trim().lowercase()
    var sidebarBgMenuOpen by remember { mutableStateOf(false) }
    val dragDropState = remember { SidebarTreeDragDropState() }
    var sidebarOriginInRoot by remember { mutableStateOf(Offset.Zero) }

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
    val activeSmartFolders = remember(doc.smartFolders, doc.tombstonedObjectIds) {
        doc.smartFolders.filterNot { it.id in doc.tombstonedObjectIds }
    }
    val activeDynamicFolders = remember(doc.dynamicFolders, doc.tombstonedObjectIds) {
        doc.dynamicFolders.filterNot { it.id in doc.tombstonedObjectIds }
    }
    val activeFolders = remember(doc.folders, doc.tombstonedObjectIds) {
        doc.folders.filterNot { it.id in doc.tombstonedObjectIds }
    }
    val activeFolderIds = remember(activeFolders) { activeFolders.map { it.id }.toSet() }
    var collapsedFolderIds by remember { mutableStateOf(emptySet<String>()) }

    // Automatically expand a collapsed folder when hovering a dragged connection over it for 480ms
    LaunchedEffect(dragDropState.hoveredDropTargetKey) {
        val hoveredKey = dragDropState.hoveredDropTargetKey
        if (hoveredKey != null && hoveredKey != ROOT_DROP_TARGET_KEY && hoveredKey in collapsedFolderIds) {
            delay(480L)
            if (dragDropState.hoveredDropTargetKey == hoveredKey) {
                collapsedFolderIds = collapsedFolderIds - hoveredKey
            }
        }
    }

    val flattenedFolders = remember(
        activeFolders,
        doc.connections,
        doc.tombstonedObjectIds,
        query,
        collapsedFolderIds,
        activeSessionCountByConnectionId
    ) {
        val childrenByParent = activeFolders.groupBy { folder ->
            folder.parentFolderId?.takeIf { it in activeFolderIds && it != folder.id }
        }
        val allConnectionsByFolder = doc.connections
            .filterNot { it.id in doc.tombstonedObjectIds }
            .groupBy { it.parentFolderId }
        val connectionsByFolder = doc.connections
            .filter(::matchesFilter)
            .groupBy { it.parentFolderId }

        fun countSubtreeMatches(folderId: String, visited: Set<String>): Int {
            if (folderId in visited) return 0
            val nextVisited = visited + folderId
            val direct = connectionsByFolder[folderId]?.size ?: 0
            val childTotal = childrenByParent[folderId].orEmpty().sumOf { child ->
                countSubtreeMatches(child.id, nextVisited)
            }
            return direct + childTotal
        }

        fun countSubtreeActiveSessions(folderId: String, visited: Set<String>): Int {
            if (folderId in visited) return 0
            val nextVisited = visited + folderId
            val directSessions = allConnectionsByFolder[folderId].orEmpty().sumOf { conn ->
                activeSessionCountByConnectionId[conn.id] ?: 0
            }
            val childSessions = childrenByParent[folderId].orEmpty().sumOf { child ->
                countSubtreeActiveSessions(child.id, nextVisited)
            }
            return directSessions + childSessions
        }

        fun subtreeMatchesFolderQuery(folder: VaultFolder, visited: Set<String>): Boolean {
            if (folder.id in visited) return false
            if (query.isEmpty()) return true
            if (folder.name.lowercase().contains(query)) return true
            if (!connectionsByFolder[folder.id].isNullOrEmpty()) return true
            val nextVisited = visited + folder.id
            return childrenByParent[folder.id].orEmpty().any { child ->
                subtreeMatchesFolderQuery(child, nextVisited)
            }
        }

        val result = mutableListOf<SidebarFolderEntry>()
        fun visit(folder: VaultFolder, depth: Int, visited: Set<String>) {
            if (folder.id in visited) return
            if (!subtreeMatchesFolderQuery(folder, visited)) return
            val nextVisited = visited + folder.id
            val childFolders = childrenByParent[folder.id].orEmpty()
            val directConns = connectionsByFolder[folder.id].orEmpty()
            val subtreeCount = countSubtreeMatches(folder.id, visited)
            val subtreeActive = countSubtreeActiveSessions(folder.id, visited)
            val expanded = query.isNotEmpty() || folder.id !in collapsedFolderIds
            result += SidebarFolderEntry(
                folder = folder,
                depth = depth,
                directConnections = directConns,
                subtreeConnectionCount = subtreeCount,
                subtreeActiveSessionCount = subtreeActive,
                hasChildFolders = childFolders.isNotEmpty(),
                isExpanded = expanded
            )
            if (expanded) {
                childFolders.forEach { child ->
                    visit(child, depth + 1, nextVisited)
                }
            }
        }

        childrenByParent[null].orEmpty().forEach { root ->
            visit(root, 0, emptySet())
        }
        result
    }

    val rootConnections = remember(doc.connections, doc.tombstonedObjectIds, activeFolderIds, query) {
        doc.connections.filter {
            (it.parentFolderId == null || it.parentFolderId !in activeFolderIds) && matchesFilter(it)
        }
    }
    val hasUpperSections = favorites.isNotEmpty() ||
        (query.isEmpty() && recentConnections.isNotEmpty()) ||
        activeSmartFolders.isNotEmpty() ||
        activeDynamicFolders.isNotEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                sidebarOriginInRoot = coords.positionInRoot()
            }
            .nexusDesktopPointerButtons(onSecondaryClick = { sidebarBgMenuOpen = true })
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Instant Google M3 Pill Filter Bar
            OutlinedTextField(
                value = uiState.sidebarFilterQuery,
                onValueChange = onFilterQueryChanged,
                placeholder = { Text(strings.sidebarFilterPlaceholder, style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (uiState.sidebarFilterQuery.isNotEmpty()) {
                        IconButton(onClick = { onFilterQueryChanged("") }, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(99.dp),
                modifier = Modifier.fillMaxWidth()
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = strings.vaultSectionFavorites,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    items(favorites, key = { "fav-${it.id}" }) { conn ->
                        ConnectionTreeRow(
                            connection = conn,
                            indentLevel = 0,
                            isSelected = conn.id == uiState.selectedConnectionId,
                            strings = strings,
                            activeSessionCount = activeSessionCountByConnectionId[conn.id] ?: 0,
                            enableDragToFolder = true,
                            dragDropState = dragDropState,
                            onSelect = { onSelectConnection(conn.id) },
                            onEdit = { onEditConnection(conn) },
                            onConnectWithOptions = { onConnectWithOptions(conn) },
                            onToggleFavorite = { onToggleFavorite(conn.id) },
                            onDuplicate = { onDuplicateConnection(conn) },
                            onDelete = { onDeleteConnection(conn.id) },
                            onDisconnect = { onDisconnectConnection(conn.id) },
                            onOpenMoveDialog = { onOpenMoveConnectionDialog(conn) },
                            onMoveToFolder = { targetFolderId -> onMoveConnectionToFolder(conn.id, targetFolderId) },
                            onLaunch = { placement -> onLaunchConnection(conn, placement) },
                            onLaunchSplitDown = { onLaunchConnectionInSplitDown(conn) }
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
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = strings.vaultSectionRecents,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    items(recentConnections.take(5), key = { "recent-${it.id}" }) { conn ->
                        ConnectionTreeRow(
                            connection = conn,
                            indentLevel = 0,
                            isSelected = conn.id == uiState.selectedConnectionId,
                            strings = strings,
                            activeSessionCount = activeSessionCountByConnectionId[conn.id] ?: 0,
                            enableDragToFolder = true,
                            dragDropState = dragDropState,
                            onSelect = { onSelectConnection(conn.id) },
                            onEdit = { onEditConnection(conn) },
                            onConnectWithOptions = { onConnectWithOptions(conn) },
                            onToggleFavorite = { onToggleFavorite(conn.id) },
                            onDuplicate = { onDuplicateConnection(conn) },
                            onDelete = { onDeleteConnection(conn.id) },
                            onDisconnect = { onDisconnectConnection(conn.id) },
                            onOpenMoveDialog = { onOpenMoveConnectionDialog(conn) },
                            onMoveToFolder = { targetFolderId -> onMoveConnectionToFolder(conn.id, targetFolderId) },
                            onLaunch = { placement -> onLaunchConnection(conn, placement) },
                            onLaunchSplitDown = { onLaunchConnectionInSplitDown(conn) }
                        )
                    }
                }

                // 3. Smart Folders Section (shown when populated)
                if (activeSmartFolders.isNotEmpty()) {
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
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            IconButton(onClick = onNewSmartFolder, modifier = Modifier.size(26.dp)) {
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = strings.actionNewSmartFolder,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                    activeSmartFolders.forEach { smart ->
                        val matchedConnections = smartFolderConnectionsProvider(smart).filter(::matchesFilter)
                        val isExpanded = uiState.selectedSmartFolderId == smart.id
                        item(key = "smart-${smart.id}") {
                            SmartFolderTreeRow(
                                smartFolder = smart,
                                matchedCount = matchedConnections.size,
                                isExpanded = isExpanded,
                                strings = strings,
                                onToggle = { onToggleSmartFolder(smart.id) },
                                onConnectAll = {
                                    matchedConnections.forEach { conn ->
                                        onLaunchConnection(conn, QuickLaunchPlacement.ACTIVE_PANE_TAB)
                                    }
                                },
                                onNewSmartFolder = onNewSmartFolder,
                                onDelete = { onDeleteSmartFolder(smart.id) }
                            )
                        }
                        if (isExpanded) {
                            items(matchedConnections, key = { "smart-${smart.id}-${it.id}" }) { conn ->
                                ConnectionTreeRow(
                                    connection = conn,
                                    indentLevel = 1,
                                    isSelected = conn.id == uiState.selectedConnectionId,
                                    strings = strings,
                                    activeSessionCount = activeSessionCountByConnectionId[conn.id] ?: 0,
                                    onSelect = { onSelectConnection(conn.id) },
                                    onEdit = { onEditConnection(conn) },
                                    onConnectWithOptions = { onConnectWithOptions(conn) },
                                    onToggleFavorite = { onToggleFavorite(conn.id) },
                                    onDuplicate = { onDuplicateConnection(conn) },
                                    onDelete = { onDeleteConnection(conn.id) },
                                    onDisconnect = { onDisconnectConnection(conn.id) },
                                    onOpenMoveDialog = { onOpenMoveConnectionDialog(conn) },
                                    onLaunch = { placement -> onLaunchConnection(conn, placement) },
                                    onLaunchSplitDown = { onLaunchConnectionInSplitDown(conn) }
                                )
                            }
                        }
                    }
                }

                // 4. Dynamic Folders Section (shown when populated)
                if (activeDynamicFolders.isNotEmpty()) {
                    item(key = "header-dynamic-folders") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = strings.vaultSectionDynamicFolders,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            IconButton(onClick = onNewDynamicFolder, modifier = Modifier.size(26.dp)) {
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = strings.vaultSectionDynamicFolders,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                    activeDynamicFolders.forEach { dyn ->
                        val hydrated = uiState.hydratedDynamicConnections[dyn.id].orEmpty().filter(::matchesFilter)
                        val isExpanded = uiState.selectedDynamicFolderId == dyn.id
                        item(key = "dyn-${dyn.id}") {
                            DynamicFolderTreeRow(
                                dynamicFolder = dyn,
                                isExpanded = isExpanded,
                                strings = strings,
                                onToggle = { onToggleDynamicFolder(dyn) },
                                onReload = { onReloadDynamicFolder(dyn) },
                                onNewDynamicFolder = onNewDynamicFolder,
                                onDelete = { onDeleteDynamicFolder(dyn.id) }
                            )
                        }
                        if (isExpanded) {
                            items(hydrated, key = { "dyn-${dyn.id}-${it.id}" }) { conn ->
                                ConnectionTreeRow(
                                    connection = conn,
                                    indentLevel = 1,
                                    isSelected = conn.id == uiState.selectedConnectionId,
                                    strings = strings,
                                    activeSessionCount = activeSessionCountByConnectionId[conn.id] ?: 0,
                                    onSelect = { onSelectConnection(conn.id) },
                                    onEdit = { onEditConnection(conn) },
                                    onConnectWithOptions = { onConnectWithOptions(conn) },
                                    onToggleFavorite = { onToggleFavorite(conn.id) },
                                    onDuplicate = { onDuplicateConnection(conn) },
                                    onDelete = { onDeleteConnection(conn.id) },
                                    onDisconnect = { onDisconnectConnection(conn.id) },
                                    onLaunch = { placement -> onLaunchConnection(conn, placement) },
                                    onLaunchSplitDown = { onLaunchConnectionInSplitDown(conn) }
                                )
                            }
                        }
                    }
                }

                // 5. Hierarchical Nested Folders & Connections Section
                item(key = "header-folders") {
                    if (hasUpperSections) {
                        Spacer(modifier = Modifier.height(6.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = strings.vaultSectionFolders,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        IconButton(
                            onClick = onNewFolder,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = strings.actionNewFolder,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                // Root-Level Drop Zone Banner (appears while dragging a nested connection to move it out of folders)
                val draggingConn = dragDropState.draggingConnection
                if (draggingConn != null && draggingConn.parentFolderId != null) {
                    item(key = "root-drop-target") {
                        val isRootHovered = dragDropState.hoveredDropTargetKey == ROOT_DROP_TARGET_KEY
                        DisposableEffect(Unit) {
                            onDispose {
                                dragDropState.unregisterDropTarget(ROOT_DROP_TARGET_KEY)
                            }
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coords ->
                                    dragDropState.registerDropTarget(ROOT_DROP_TARGET_KEY, coords.boundsInRoot())
                                },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isRootHovered) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            border = BorderStroke(
                                width = if (isRootHovered) 2.dp else 1.dp,
                                color = if (isRootHovered) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DriveFileMove,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = strings.moveToRootFolder,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                flattenedFolders.forEach { entry ->
                    val folder = entry.folder
                    val explicitFolderCred = if (folder.inheritable.credentialMode == InheritanceMode.OVERRIDE_EXPLICIT) {
                        doc.credentials.firstOrNull {
                            it.id == folder.inheritable.explicitCredentialId && it.id !in doc.tombstonedObjectIds
                        }
                    } else {
                        null
                    }
                    item(key = "folder-${folder.id}") {
                        FolderTreeRow(
                            folder = folder,
                            depth = entry.depth,
                            isExpanded = entry.isExpanded,
                            hasExpandableChildren = entry.hasChildFolders || entry.directConnections.isNotEmpty(),
                            explicitFolderCredentialName = explicitFolderCred?.name,
                            childCount = entry.subtreeConnectionCount,
                            activeSessionCountInSubtree = entry.subtreeActiveSessionCount,
                            dragDropState = dragDropState,
                            strings = strings,
                            onToggleExpand = {
                                collapsedFolderIds = if (folder.id in collapsedFolderIds) {
                                    collapsedFolderIds - folder.id
                                } else {
                                    collapsedFolderIds + folder.id
                                }
                            },
                            onNewRdpInFolder = { onNewRdpInFolder(folder.id) },
                            onNewSshInFolder = { onNewSshInFolder(folder.id) },
                            onNewSubfolder = { onNewSubfolder(folder.id) },
                            onEditFolder = { onEditFolder(folder) },
                            onDuplicateFolder = { onDuplicateFolder(folder) },
                            onDeleteFolder = { onDeleteFolder(folder.id) },
                            onConnectAllInFolder = { onConnectAllInFolder(folder.id) },
                            onDisconnectAllInFolder = { onDisconnectAllInFolder(folder.id) }
                        )
                    }
                    if (entry.isExpanded) {
                        items(entry.directConnections, key = { "fconn-${folder.id}-${it.id}" }) { conn ->
                            ConnectionTreeRow(
                                connection = conn,
                                indentLevel = entry.depth + 1,
                                isSelected = conn.id == uiState.selectedConnectionId,
                                strings = strings,
                                activeSessionCount = activeSessionCountByConnectionId[conn.id] ?: 0,
                                enableDragToFolder = true,
                                dragDropState = dragDropState,
                                onSelect = { onSelectConnection(conn.id) },
                                onEdit = { onEditConnection(conn) },
                                onConnectWithOptions = { onConnectWithOptions(conn) },
                                onToggleFavorite = { onToggleFavorite(conn.id) },
                                onDuplicate = { onDuplicateConnection(conn) },
                                onDelete = { onDeleteConnection(conn.id) },
                                onDisconnect = { onDisconnectConnection(conn.id) },
                                onOpenMoveDialog = { onOpenMoveConnectionDialog(conn) },
                                onMoveToFolder = { targetFolderId -> onMoveConnectionToFolder(conn.id, targetFolderId) },
                                onLaunch = { placement -> onLaunchConnection(conn, placement) },
                                onLaunchSplitDown = { onLaunchConnectionInSplitDown(conn) }
                            )
                        }
                    }
                }

                items(rootConnections, key = { "root-${it.id}" }) { conn ->
                    ConnectionTreeRow(
                        connection = conn,
                        indentLevel = 0,
                        isSelected = conn.id == uiState.selectedConnectionId,
                        strings = strings,
                        activeSessionCount = activeSessionCountByConnectionId[conn.id] ?: 0,
                        enableDragToFolder = true,
                        dragDropState = dragDropState,
                        onSelect = { onSelectConnection(conn.id) },
                        onEdit = { onEditConnection(conn) },
                        onConnectWithOptions = { onConnectWithOptions(conn) },
                        onToggleFavorite = { onToggleFavorite(conn.id) },
                        onDuplicate = { onDuplicateConnection(conn) },
                        onDelete = { onDeleteConnection(conn.id) },
                        onDisconnect = { onDisconnectConnection(conn.id) },
                        onOpenMoveDialog = { onOpenMoveConnectionDialog(conn) },
                        onMoveToFolder = { targetFolderId -> onMoveConnectionToFolder(conn.id, targetFolderId) },
                        onLaunch = { placement -> onLaunchConnection(conn, placement) },
                        onLaunchSplitDown = { onLaunchConnectionInSplitDown(conn) }
                    )
                }

                if (flattenedFolders.isEmpty() && rootConnections.isEmpty()) {
                    item(key = "empty-sidebar-hint") {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, start = 2.dp, end = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeviceHub,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                    modifier = Modifier.size(26.dp)
                                )
                                FilledTonalButton(
                                    onClick = onNewConnection,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = strings.actionNewConnection,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating Drag Ghost Preview Card while dragging a connection between folders
        val activeDraggedConn = dragDropState.draggingConnection
        if (activeDraggedConn != null) {
            val targetLabel = when (val key = dragDropState.hoveredDropTargetKey) {
                null -> null
                ROOT_DROP_TARGET_KEY -> strings.moveToRootFolder
                else -> activeFolders.firstOrNull { it.id == key }?.name
            }
            val uiProto = NexusWorkspaceViewModel.mapProtocolToUiKind(activeDraggedConn.protocol)
            val localX = (dragDropState.pointerPositionInRoot.x - sidebarOriginInRoot.x + 14f)
                .roundToInt()
                .coerceAtLeast(8)
            val localY = (dragDropState.pointerPositionInRoot.y - sidebarOriginInRoot.y - 22f)
                .roundToInt()
                .coerceAtLeast(8)

            Surface(
                modifier = Modifier
                    .offset { IntOffset(localX, localY) }
                    .widthIn(max = 260.dp)
                    .shadow(10.dp, RoundedCornerShape(14.dp)),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = protocolIcon(uiProto),
                        contentDescription = null,
                        tint = protocolAccentColor(uiProto),
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = activeDraggedConn.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (targetLabel != null) {
                            Text(
                                text = String.format(strings.dragDropMoveHintFormat, targetLabel),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = sidebarBgMenuOpen,
            onDismissRequest = { sidebarBgMenuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text(strings.actionNewConnection) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    sidebarBgMenuOpen = false
                    onNewConnection()
                }
            )
            DropdownMenuItem(
                text = { Text(strings.actionNewFolder) },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                onClick = {
                    sidebarBgMenuOpen = false
                    onNewFolder()
                }
            )
            DropdownMenuItem(
                text = { Text(strings.actionNewSmartFolder) },
                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                onClick = {
                    sidebarBgMenuOpen = false
                    onNewSmartFolder()
                }
            )
            DropdownMenuItem(
                text = { Text(strings.vaultSectionDynamicFolders) },
                leadingIcon = { Icon(Icons.Outlined.DynamicFeed, contentDescription = null) },
                onClick = {
                    sidebarBgMenuOpen = false
                    onNewDynamicFolder()
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Local Terminal") },
                leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                onClick = {
                    sidebarBgMenuOpen = false
                    onLaunchLocalShell()
                }
            )
            DropdownMenuItem(
                text = { Text(strings.actionImportRtsz) },
                leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                onClick = {
                    sidebarBgMenuOpen = false
                    onImportVault()
                }
            )
        }
    }
}

@Composable
private fun SmartFolderTreeRow(
    smartFolder: SmartFolder,
    matchedCount: Int,
    isExpanded: Boolean,
    strings: WorkspaceLocalizedStrings,
    onToggle: () -> Unit,
    onConnectAll: () -> Unit,
    onNewSmartFolder: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hover = rememberDesktopHoverState()
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered = hover.value.isHovered

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .nexusHoverTracker(hover, PointerIcon.Hand)
                .clip(MaterialTheme.shapes.small)
                .background(
                    when {
                        isExpanded -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                        isHovered -> MaterialTheme.colorScheme.surfaceContainerHigh
                        else -> Color.Transparent
                    }
                )
                .nexusDesktopPointerButtons(onSecondaryClick = { menuOpen = true })
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onToggle
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = smartFolder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = matchedCount.toString(),
                    style = NexusTheme.monospace.keycapShortcut,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = strings.actionDeleteItem,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (isExpanded) "Collapse Smart Folder" else "Expand Smart Folder") },
                leadingIcon = {
                    Icon(
                        if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null
                    )
                },
                onClick = {
                    menuOpen = false
                    onToggle()
                }
            )
            if (matchedCount > 0) {
                DropdownMenuItem(
                    text = { Text(strings.actionConnectAllFolder) },
                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onConnectAll()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(strings.actionNewSmartFolder) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onNewSmartFolder()
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(strings.actionDeleteItem, color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun DynamicFolderTreeRow(
    dynamicFolder: DynamicFolder,
    isExpanded: Boolean,
    strings: WorkspaceLocalizedStrings,
    onToggle: () -> Unit,
    onReload: () -> Unit,
    onNewDynamicFolder: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hover = rememberDesktopHoverState()
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered = hover.value.isHovered

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .nexusHoverTracker(hover, PointerIcon.Hand)
                .clip(MaterialTheme.shapes.small)
                .background(
                    when {
                        isExpanded -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                        isHovered -> MaterialTheme.colorScheme.surfaceContainerHigh
                        else -> Color.Transparent
                    }
                )
                .nexusDesktopPointerButtons(onSecondaryClick = { menuOpen = true })
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onToggle
                )
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
                    text = dynamicFolder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onReload,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = strings.actionReloadDynamicFolder,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = strings.actionDeleteItem,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (isExpanded) "Collapse Dynamic Folder" else "Expand Dynamic Folder") },
                leadingIcon = {
                    Icon(
                        if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null
                    )
                },
                onClick = {
                    menuOpen = false
                    onToggle()
                }
            )
            DropdownMenuItem(
                text = { Text(strings.actionReloadDynamicFolder) },
                leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onReload()
                }
            )
            DropdownMenuItem(
                text = { Text(strings.vaultSectionDynamicFolders) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onNewDynamicFolder()
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(strings.actionDeleteItem, color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun FolderTreeRow(
    folder: VaultFolder,
    depth: Int,
    isExpanded: Boolean,
    hasExpandableChildren: Boolean,
    explicitFolderCredentialName: String?,
    childCount: Int,
    activeSessionCountInSubtree: Int,
    dragDropState: SidebarTreeDragDropState,
    strings: WorkspaceLocalizedStrings,
    onToggleExpand: () -> Unit,
    onNewRdpInFolder: () -> Unit,
    onNewSshInFolder: () -> Unit,
    onNewSubfolder: () -> Unit,
    onEditFolder: () -> Unit,
    onDuplicateFolder: () -> Unit,
    onDeleteFolder: () -> Unit,
    onConnectAllInFolder: () -> Unit,
    onDisconnectAllInFolder: () -> Unit
) {
    var folderMenuOpen by remember { mutableStateOf(false) }
    val hover = rememberDesktopHoverState()
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered = hover.value.isHovered
    val folderAccent = parseHexColorOrNull(folder.inheritable.displayColorAccentHex)
        ?: MaterialTheme.colorScheme.primary
    val isDropTargetHovered = dragDropState.isDragging && dragDropState.hoveredDropTargetKey == folder.id

    DisposableEffect(folder.id) {
        onDispose {
            dragDropState.unregisterDropTarget(folder.id)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 18).dp, top = 2.dp, bottom = 2.dp)
            .onGloballyPositioned { coords ->
                dragDropState.registerDropTarget(folder.id, coords.boundsInRoot())
            }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .nexusHoverTracker(hover, PointerIcon.Hand)
                .clip(RoundedCornerShape(14.dp))
                .nexusDesktopPointerButtons(onSecondaryClick = { folderMenuOpen = true })
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        if (hasExpandableChildren) {
                            onToggleExpand()
                        } else {
                            onEditFolder()
                        }
                    }
                ),
            shape = RoundedCornerShape(14.dp),
            color = when {
                isDropTargetHovered -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                isHovered -> lerp(MaterialTheme.colorScheme.surfaceContainerHigh, folderAccent, 0.11f)
                else -> lerp(MaterialTheme.colorScheme.surfaceContainerLow, folderAccent, 0.06f)
            },
            border = BorderStroke(
                width = if (isDropTargetHovered) 2.dp else 1.dp,
                color = when {
                    isDropTargetHovered -> MaterialTheme.colorScheme.primary
                    isHovered -> folderAccent.copy(alpha = 0.45f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasExpandableChildren) {
                        IconButton(
                            onClick = onToggleExpand,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = if (isExpanded) {
                                    Icons.Outlined.KeyboardArrowDown
                                } else {
                                    Icons.Outlined.ChevronRight
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                    } else {
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Distinct Structured Container Badge with Filled Folder Icon
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = folderAccent.copy(alpha = 0.17f),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isExpanded && hasExpandableChildren) {
                                    FilledFolderOpenIcon
                                } else {
                                    FilledFolderIcon
                                },
                                contentDescription = null,
                                tint = folderAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (activeSessionCountInSubtree > 0) {
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Text(
                                    text = activeSessionCountInSubtree.toString(),
                                    style = NexusTheme.monospace.keycapShortcut,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        IconButton(
                            onClick = onDisconnectAllInFolder,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PowerSettingsNew,
                                contentDescription = "${strings.actionDisconnectAllFolder}: ${folder.name}",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else if (childCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = childCount.toString(),
                                style = NexusTheme.monospace.keycapShortcut,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Direct `+` icon button to immediately create an RDP connection inside this folder
                    IconButton(
                        onClick = onNewRdpInFolder,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "New RDP Connection in ${folder.name}",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (isHovered || folderMenuOpen) {
                        IconButton(
                            onClick = { folderMenuOpen = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "Folder Actions",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = folderMenuOpen,
            onDismissRequest = { folderMenuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text("New RDP Connection") },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onNewRdpInFolder()
                }
            )
            DropdownMenuItem(
                text = { Text("New SSH Connection") },
                leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onNewSshInFolder()
                }
            )
            DropdownMenuItem(
                text = { Text("New Subfolder") },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onNewSubfolder()
                }
            )
            HorizontalDivider()
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
            if (activeSessionCountInSubtree > 0 || childCount > 0) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (activeSessionCountInSubtree > 0) {
                                "${strings.actionDisconnectAllFolder} ($activeSessionCountInSubtree)"
                            } else {
                                strings.actionDisconnectAllFolder
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.PowerSettingsNew,
                            contentDescription = null,
                            tint = if (activeSessionCountInSubtree > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    onClick = {
                        folderMenuOpen = false
                        onDisconnectAllInFolder()
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
            DropdownMenuItem(
                text = { Text("Duplicate Folder") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onDuplicateFolder()
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(strings.actionDeleteItem, color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    folderMenuOpen = false
                    onDeleteFolder()
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
    onLaunch: (QuickLaunchPlacement) -> Unit,
    onLaunchSplitDown: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onOpenMoveDialog: () -> Unit = {},
    onMoveToFolder: (String?) -> Unit = {},
    activeSessionCount: Int = 0,
    enableDragToFolder: Boolean = false,
    dragDropState: SidebarTreeDragDropState? = null,
    indentLevel: Int = 1
) {
    val clipboard = LocalClipboardManager.current
    var contextMenuOpen by remember { mutableStateOf(false) }
    val hover = rememberDesktopHoverState()
    val handleHover = rememberDesktopHoverState()
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered = hover.value.isHovered
    val uiProto = NexusWorkspaceViewModel.mapProtocolToUiKind(connection.protocol)
    val customAccent = parseHexColorOrNull(connection.inheritable.displayColorAccentHex)
    val effectiveIconTint = customAccent ?: protocolAccentColor(uiProto)
    val isBeingDragged = dragDropState?.draggingConnection?.id == connection.id

    var rowOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var handleOriginInRoot by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indentLevel > 0) ((indentLevel - 1) * 18 + 12).dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Vertical Tree Hierarchy Guide Rail when nested inside a Folder
            if (indentLevel > 0) {
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                                }
                            )
                    )
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 50.dp)
                    .onGloballyPositioned { coords ->
                        rowOriginInRoot = coords.positionInRoot()
                    }
                    .nexusHoverTracker(hover, PointerIcon.Hand)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            isBeingDragged -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
                            isSelected -> MaterialTheme.colorScheme.secondaryContainer
                            isHovered -> MaterialTheme.colorScheme.surfaceContainerHigh
                            else -> Color.Transparent
                        }
                    )
                    .pointerInput(connection.id, connection.parentFolderId, enableDragToFolder) {
                        if (!enableDragToFolder || dragDropState == null) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (down.type != PointerType.Mouse) return@awaitEachGesture
                            var dragStarted = false
                            var accumulated = Offset.Zero
                            val slop = viewConfiguration.touchSlop
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.buttons.isSecondaryPressed) {
                                    if (dragStarted) dragDropState.cancelDrag()
                                    break
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (dragStarted) {
                                        change.consume()
                                        dragDropState.finishDrag { _, targetFolderId ->
                                            onMoveToFolder(targetFolderId)
                                        }
                                    }
                                    break
                                }
                                val delta = change.positionChange()
                                if (!dragStarted) {
                                    accumulated += delta
                                    if (event.buttons.isPrimaryPressed && accumulated.getDistance() > slop) {
                                        dragStarted = true
                                        dragDropState.startDrag(connection, rowOriginInRoot + change.position)
                                        change.consume()
                                    }
                                } else {
                                    change.consume()
                                    dragDropState.updateDragAtRoot(rowOriginInRoot + change.position)
                                }
                            }
                        }
                    }
                    .nexusDesktopPointerButtons(onSecondaryClick = { contextMenuOpen = true })
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        onSelect()
                        onLaunch(QuickLaunchPlacement.ACTIVE_PANE_TAB)
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (enableDragToFolder && dragDropState != null) {
                    Box(
                        modifier = Modifier
                            .size(width = 20.dp, height = 34.dp)
                            .onGloballyPositioned { coords ->
                                handleOriginInRoot = coords.positionInRoot()
                            }
                            .nexusHoverTracker(handleHover, PointerIcon.Hand)
                            .pointerInput(connection.id, connection.parentFolderId) {
                                detectDragGestures(
                                    onDragStart = { localOffset ->
                                        dragDropState.startDrag(connection, handleOriginInRoot + localOffset)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragDropState.updateDragByDelta(dragAmount)
                                    },
                                    onDragEnd = {
                                        dragDropState.finishDrag { _, targetFolderId ->
                                            onMoveToFolder(targetFolderId)
                                        }
                                    },
                                    onDragCancel = {
                                        dragDropState.cancelDrag()
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DragIndicator,
                            contentDescription = "Drag ${connection.name}",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (isHovered || isSelected || handleHover.value.isHovered) 0.85f else 0.35f
                            )
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Tinted Protocol Squircle Endpoint Badge + Live Session Indicator Dot
                Box(modifier = Modifier.size(36.dp)) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = effectiveIconTint.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = protocolIcon(uiProto),
                                contentDescription = null,
                                tint = effectiveIconTint,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    if (activeSessionCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(1.5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = connection.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (connection.favorite) {
                            Icon(
                                imageVector = Icons.Outlined.Star,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    Text(
                        text = "${connection.protocol.name} • ${connection.host}",
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Decluttered trailing area: clean single action button only on hover/selection
                if (isHovered || isSelected || contextMenuOpen) {
                    IconButton(
                        onClick = { contextMenuOpen = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = strings.actionEditConnection,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = contextMenuOpen,
            onDismissRequest = { contextMenuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text("Connect") },
                leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onSelect()
                    onLaunch(QuickLaunchPlacement.ACTIVE_PANE_TAB)
                }
            )
            DropdownMenuItem(
                text = { Text(strings.actionConnectWithOptions) },
                leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onConnectWithOptions()
                }
            )
            if (activeSessionCount > 0) {
                DropdownMenuItem(
                    text = { Text("${strings.actionDisconnectSession} ($activeSessionCount)") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.PowerSettingsNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        contextMenuOpen = false
                        onDisconnect()
                    }
                )
            }
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
                text = { Text(strings.splitDown) },
                leadingIcon = { Icon(Icons.Outlined.HorizontalSplit, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onLaunchSplitDown()
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
                text = { Text(strings.actionMoveToFolder) },
                leadingIcon = { Icon(Icons.Outlined.DriveFileMove, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onOpenMoveDialog()
                }
            )
            DropdownMenuItem(
                text = { Text("Copy Host") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    clipboard.setText(AnnotatedString(connection.host))
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
    onDeleteCustomProperty: (String, String) -> Unit,
    onAddTodo: (String, String) -> Unit,
    onToggleTodo: (String) -> Unit,
    onDeleteTodo: (String) -> Unit,
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
                    Column(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onEditConnection(profile.connection) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.actionEditConnection,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        FilledTonalButton(
                            onClick = { onConnectWithOptions(profile.connection) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.actionConnectWithOptions,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
                onToggleTodo = onToggleTodo,
                onDeleteCustomProperty = { propKey ->
                    onDeleteCustomProperty(profile.connection.id, propKey)
                },
                onDeleteTodo = onDeleteTodo
            )
        } else {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = strings.emptyWorkspaceSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
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
fun FullScreenVaultUnlockShield(
    vaultTitle: String,
    unlockError: String?,
    hasPreImportBackup: Boolean,
    strings: WorkspaceLocalizedStrings,
    onUnlock: (String) -> Unit,
    onCancelPendingImport: () -> Unit,
    onOpenVaultPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    var passphrase by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.width(460.dp)
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Text(
                        text = strings.appName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = vaultTitle,
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = strings.vaultLockedBanner,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!unlockError.isNullOrBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = unlockError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(strings.vaultPassphraseLabel) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        enabled = passphrase.isNotEmpty(),
                        onClick = {
                            val current = passphrase
                            passphrase = ""
                            onUnlock(current)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.actionUnlockVault)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (hasPreImportBackup) {
                            OutlinedButton(
                                onClick = {
                                    passphrase = ""
                                    onCancelPendingImport()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(strings.dialogActionCancel)
                            }
                        }
                        TextButton(
                            onClick = onOpenVaultPicker,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.actionImportRtsz)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomTelemetryAndBroadcastDrawer(
    logs: List<String>,
    strings: WorkspaceLocalizedStrings,
    onBroadcastCommand: (String) -> Unit,
    onClearLogs: () -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current
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
                enabled = cmd.isNotBlank(),
                onClick = {
                    onBroadcastCommand(cmd)
                    cmd = ""
                }
            ) {
                Text(strings.drawerSendBroadcast)
            }
            OutlinedButton(
                enabled = logs.isNotEmpty(),
                onClick = { clipboard.setText(AnnotatedString(logs.joinToString("\n"))) }
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy All")
            }
            TextButton(
                enabled = logs.isNotEmpty(),
                onClick = onClearLogs
            ) {
                Icon(Icons.Outlined.ClearAll, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear")
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs) { line ->
                var logMenuOpen by remember { mutableStateOf(false) }
                val hover = rememberDesktopHoverState()
                Box {
                    Text(
                        text = line,
                        style = NexusTheme.monospace.logEntry,
                        color = if (hover.value.isHovered) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .nexusHoverTracker(hover, PointerIcon.Hand)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (hover.value.isHovered) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    Color.Transparent
                                }
                            )
                            .nexusDesktopPointerButtons(onSecondaryClick = { logMenuOpen = true })
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    DropdownMenu(
                        expanded = logMenuOpen,
                        onDismissRequest = { logMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy Log Entry") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                logMenuOpen = false
                                clipboard.setText(AnnotatedString(line))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy All Logs") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                logMenuOpen = false
                                clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Clear Audit Log", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Icons.Outlined.ClearAll, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                logMenuOpen = false
                                onClearLogs()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EncryptedImportPassphraseDialog(
    pendingFileName: String?,
    pendingFormat: DetectedExternalVaultFormat?,
    errorMessage: String?,
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit,
    onSubmitPassphrase: (String) -> Unit
) {
    if (pendingFileName == null) return
    var passphrase by remember(pendingFileName) { mutableStateOf("") }

    val formatLabel = when (pendingFormat) {
        DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE -> "Password-Protected Royal TS Archive (.rtsz)"
        DetectedExternalVaultFormat.NXTS_ENCRYPTED_BINARY -> "Encrypted NexusTS Vault (.nxts)"
        DetectedExternalVaultFormat.KEEPASS_KDBX_BINARY -> "Encrypted KeePass Database (.kdbx)"
        else -> "Encrypted Vault Archive"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Unlock Encrypted Import") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "$formatLabel: $pendingFileName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(strings.vaultPassphraseLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = passphrase.isNotEmpty(),
                onClick = {
                    val entered = passphrase
                    passphrase = ""
                    onSubmitPassphrase(entered)
                }
            ) {
                Text(strings.actionUnlockVault)
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
private fun ImportErrorAlertDialog(
    errorMessage: String?,
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit
) {
    if (errorMessage.isNullOrBlank()) return

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("Import Failed") },
        text = {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(strings.dialogActionCancel)
            }
        }
    )
}

@Composable
private fun LanguageSelectionDialog(
    visible: Boolean,
    currentLocale: SupportedNexusLocale,
    strings: WorkspaceLocalizedStrings,
    onSelectLocale: (SupportedNexusLocale) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = strings.dialogLanguageTitle,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SupportedNexusLocale.entries.forEach { locale ->
                    val isSelected = locale == currentLocale
                    val localizedLabel = strings.languageLabels[locale] ?: locale.nativeDisplayName
                    val rowHover = rememberDesktopHoverState()
                    val rowInteraction = remember { MutableInteractionSource() }
                    val isHovered = rowHover.value.isHovered

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .nexusHoverTracker(rowHover, PointerIcon.Hand)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(
                                interactionSource = rowInteraction,
                                indication = null,
                                onClick = { onSelectLocale(locale) }
                            ),
                        shape = RoundedCornerShape(16.dp),
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                            isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                NexusLanguageFlagBadge(
                                    languageCode = locale.bcp47Tag,
                                    width = 38.dp,
                                    height = 26.dp,
                                    cornerRadius = 6.dp
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = locale.nativeDisplayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "$localizedLabel • ${locale.regionSubtitle}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(99.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                                ) {
                                    Text(
                                        text = locale.bcp47Tag.uppercase(),
                                        style = NexusTheme.monospace.keycapShortcut,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.dialogActionCancel)
            }
        }
    )
}

@Composable
private fun MoveConnectionToFolderDialog(
    targetConnection: VaultConnection?,
    folders: List<VaultFolder>,
    strings: WorkspaceLocalizedStrings,
    onMoveToFolder: (String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val connection = targetConnection ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = {
            Icon(
                imageVector = Icons.Outlined.DriveFileMove,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = strings.actionMoveToFolder,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${connection.name} (${connection.host})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(4.dp))

                val isAtRoot = connection.parentFolderId == null
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            onMoveToFolder(connection.id, null)
                            onDismiss()
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isAtRoot) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isAtRoot) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = strings.moveToRootFolder,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        if (isAtRoot) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                folders.forEach { folder ->
                    val isCurrent = connection.parentFolderId == folder.id
                    val accent = parseHexColorOrNull(folder.inheritable.displayColorAccentHex)
                        ?: MaterialTheme.colorScheme.primary
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                onMoveToFolder(connection.id, folder.id)
                                onDismiss()
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = FilledFolderIcon,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.dialogActionCancel)
            }
        }
    )
}

@Composable
private fun PlayProtectAutoUpdateDialog(
    visible: Boolean,
    uiState: WorkspaceUiState,
    strings: WorkspaceLocalizedStrings,
    onToggleAutoCheck: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onTriggerBrowserUpdate: () -> Unit,
    onDownloadVerifiedToFiles: () -> Unit,
    onSkipVersion: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = {
            Icon(
                imageVector = Icons.Outlined.SystemUpdateAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = strings.autoUpdateTitle,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Google Play Protect Compliance Shield Banner
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Google Play Protect Verified Architecture",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Zero REQUEST_INSTALL_PACKAGES • HTTPS GitHub Release + SHA-256 + APK Signature Scheme v3/v4",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Auto-check toggle card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = strings.autoUpdateAutoCheck,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = uiState.autoCheckUpdatesEnabled,
                            onCheckedChange = onToggleAutoCheck
                        )
                    }
                }

                // Current release status card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (uiState.updateAvailable) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    border = BorderStroke(
                        1.dp,
                        if (uiState.updateAvailable) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (uiState.isCheckingForUpdates) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = if (uiState.updateAvailable) {
                                        Icons.Outlined.SystemUpdateAlt
                                    } else {
                                        Icons.Outlined.CheckCircle
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                text = when {
                                    uiState.isCheckingForUpdates -> "Checking GitHub Releases over HTTPS…"
                                    uiState.updateAvailable -> "${strings.autoUpdateAvailableBadge}: ${uiState.updateLatestTag}"
                                    else -> strings.autoUpdateUpToDate
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (uiState.updateCurrentVersion.isNotBlank() || uiState.updateLatestTag.isNotBlank()) {
                            val sizeMb = if (uiState.updateApkSizeBytes > 0L) {
                                String.format(" • %.1f MB", uiState.updateApkSizeBytes / (1024.0 * 1024.0))
                            } else {
                                ""
                            }
                            Text(
                                text = "Installed: v${uiState.updateCurrentVersion.ifBlank { "1.1.5" }}  →  Latest: ${uiState.updateLatestTag.ifBlank { "current" }}$sizeMb",
                                style = NexusTheme.monospace.codeSnippet,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        if (uiState.updateSha256Hex.isNotBlank()) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        clipboard.setText(AnnotatedString(uiState.updateSha256Hex))
                                    },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "SHA-256: ${uiState.updateSha256Hex.take(24)}…",
                                        style = NexusTheme.monospace.keycapShortcut,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = "Copy SHA-256",
                                        modifier = Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }

                        if (uiState.updateReleaseNotes.isNotBlank()) {
                            Text(
                                text = uiState.updateReleaseNotes.take(420),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (uiState.updateVerificationMessage.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = uiState.updateVerificationMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (uiState.updateAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onDownloadVerifiedToFiles,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Verified APK Download", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(onClick = onSkipVersion) {
                            Text("Skip")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (uiState.updateAvailable) {
                Button(onClick = onTriggerBrowserUpdate) {
                    Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.autoUpdateInstallAction)
                }
            } else {
                FilledTonalButton(
                    onClick = onCheckNow,
                    enabled = !uiState.isCheckingForUpdates
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.syncCheckGitHubUpdates)
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (uiState.updateAvailable) {
                    TextButton(
                        onClick = onCheckNow,
                        enabled = !uiState.isCheckingForUpdates
                    ) {
                        Text("Check Again")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(strings.dialogActionCancel)
                }
            }
        }
    )
}


