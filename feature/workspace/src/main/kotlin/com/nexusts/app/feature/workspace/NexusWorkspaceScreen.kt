package com.nexusts.app.feature.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Folder as FilledFolderIcon
import androidx.compose.material.icons.filled.FolderOpen as FilledFolderOpenIcon
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HorizontalSplit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Menu
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
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.nexusts.app.core.ui.input.nexusInteractiveRow
import com.nexusts.app.core.ui.input.nexusRemoteKeyPassthrough
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
import com.nexusts.app.core.vault.error.NexusAppError
import com.nexusts.app.core.vault.error.NexusErrorCategory
import com.nexusts.app.core.vault.error.VaultHierarchySanitizer
import com.nexusts.app.core.vault.exporter.VaultExportFormat
import com.nexusts.app.core.vault.importer.DetectedExternalVaultFormat
import com.nexusts.app.core.vault.importer.RoyalTsImportPlacementMode
import com.nexusts.app.core.vault.inheritance.EffectiveConnectionProfile
import com.nexusts.app.core.vault.model.CustomPropertyEntry
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.RdpAudioRedirectionMode
import com.nexusts.app.core.vault.model.SmartFolder
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.core.vault.model.VaultTodoItem
import com.nexusts.app.feature.workspace.i18n.NexusLocaleController
import com.nexusts.app.feature.workspace.i18n.SupportedNexusLocale
import com.nexusts.app.feature.workspace.i18n.WorkspaceLocalizedStrings
import com.nexusts.app.feature.workspace.studios.AutomationTasksStudioView
import com.nexusts.app.feature.workspace.studios.ConnectWithOptionsDialog
import com.nexusts.app.feature.workspace.studios.ConnectionTemplatesStudioView
import com.nexusts.app.feature.workspace.studios.CredentialEditorDialog
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
    onRequestBiometricUnlock: (() -> Unit)? = null,
    onQuickLockVault: (() -> Unit)? = null,
    onToggleBiometricUnlock: ((Boolean) -> Unit)? = null,
    onEnrollBiometricPassphrase: ((CharArray, Boolean) -> Unit)? = null,
    onChangeIdleAutoLockMinutes: ((Int) -> Unit)? = null,
    onPickRdpSharedSafFolder: (((String) -> Unit) -> Unit)? = null,
    onResetNexusDatabase: (() -> Unit)? = null,
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
        Box(modifier = modifier.fillMaxSize()) {
            FullScreenVaultUnlockShield(
                vaultTitle = uiState.pendingImportFileName ?: uiState.vaultDocument.name,
                unlockError = uiState.vaultUnlockError,
                hasPreImportBackup = uiState.hasPreImportBackup,
                strings = strings,
                onUnlock = { pass -> viewModel.unlockVaultWithPassphrase(pass.toCharArray()) },
                onCancelPendingImport = viewModel::cancelPendingVaultImport,
                onOpenVaultPicker = onRequestImportFilePicker,
                biometricUnlockAvailable = uiState.biometricUnlockEnabled &&
                    !uiState.hasPreImportBackup &&
                    onRequestBiometricUnlock != null,
                onBiometricUnlock = { onRequestBiometricUnlock?.invoke() },
                onRequestResetDatabase = if (onResetNexusDatabase != null) {
                    viewModel::openResetDatabaseDialog
                } else {
                    null
                },
                modifier = Modifier.fillMaxSize()
            )
            ResetDatabaseConfirmationDialog(
                visible = uiState.isResetDatabaseDialogVisible,
                canExportBackup = false,
                strings = strings,
                onExportBackupFirst = {},
                onConfirmReset = {
                    onResetNexusDatabase?.invoke()
                },
                onDismiss = viewModel::dismissResetDatabaseDialog
            )
            NexusErrorDialog(
                error = uiState.activeAppError,
                strings = strings,
                onEditRelatedConnection = null,
                onDismiss = viewModel::dismissActiveAppError
            )
        }
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

    val activeFullScreenTab = remember(rootPane, focusedPaneId) {
        viewModel.tilingController.focusedOrFirstActiveTab()
    }
    val onShortcutAction: (NexusShortcutAction) -> Unit = { action ->
        viewModel.handleShortcut(
            action = action,
            onOpenVaultPicker = onRequestImportFilePicker,
            onSyncDriveNow = onRequestGoogleDriveSync
        ) { tab -> onDetachTabToNativeWindow(tab, null) }
    }

    CompositionLocalProvider(LocalNexusShortcutEngine provides viewModel.shortcutEngine) {
        Box(modifier = modifier.fillMaxSize()) {
            if (uiState.isFullScreenSessionMode && activeFullScreenTab != null) {
                FullScreenSessionWorkspace(
                    activeTab = activeFullScreenTab,
                    allActiveTabs = activeDockedTabs,
                    uiState = uiState,
                    strings = strings,
                    remoteKeyCaptureActive = uiState.remoteKeyCaptureEnabled,
                    onShortcutTriggered = onShortcutAction,
                    onForwardRawKeyToActiveSession = viewModel::forwardRawKeyToFocusedSession,
                    onSelectTab = viewModel::selectTabGlobally,
                    onCloseTab = viewModel::closeSessionTab,
                    onExitFullScreen = viewModel::exitFullScreenSessionMode,
                    onOpenQuickConnect = {
                        viewModel.handleShortcut(NexusShortcutAction.QUICK_CONNECT_PALETTE) {}
                    },
                    onNewConnection = viewModel::openNewConnectionDialog,
                    smartFolderConnectionsProvider = viewModel::evaluateSmartFolderConnections,
                    onSelectConnection = viewModel::selectConnectionInTree,
                    onLaunchConnection = { conn ->
                        viewModel.launchConnection(conn, QuickLaunchPlacement.ACTIVE_PANE_TAB)
                    },
                    sessionContent = { tab ->
                        key(tab.tabId) {
                            ActiveProtocolSessionDispatcher(
                                tab = tab,
                                isFocused = true,
                                viewModel = viewModel,
                                strings = strings
                            )
                        }
                    }
                )
            } else {
                NexusAdaptiveDesktopScaffold(
                    destinations = destinations,
                    selectedDestinationId = uiState.activeRailId,
                    onSelectDestination = viewModel::selectRailDestination,
                    isSidebarVisible = uiState.isSidebarVisible,
                    isInspectorVisible = uiState.isInspectorVisible,
                    isBottomDrawerVisible = uiState.isBottomDrawerVisible,
                    remoteKeyCaptureActive = uiState.remoteKeyCaptureEnabled,
                    onShortcutTriggered = onShortcutAction,
                    onForwardRawKeyToActiveSession = viewModel::forwardRawKeyToFocusedSession,
                    topWindowBar = {
                        DesktopTopWindowChrome(
                            vaultName = uiState.vaultDocument.name,
                            vaultRevision = uiState.vaultDocument.revision,
                            destinations = destinations,
                    selectedDestinationId = uiState.activeRailId,
                    onSelectDestination = viewModel::selectRailDestination,
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
                    onNewTemplate = viewModel::openNewTemplateDialog,
                    onNewCredential = viewModel::openNewCredentialDialog,
                    onNewLocalShell = viewModel::launchLocalShellTab,
                    onImportRtsz = onRequestImportFilePicker,
                    onExportVault = onRequestExportVault,
                    onSyncGoogleDrive = onRequestGoogleDriveSync,
                    onToggleRemoteKeyCapture = viewModel::toggleRemoteKeyCapture,
                    onToggleBroadcast = viewModel::toggleGlobalBroadcast,
                    onLockVault = viewModel::openLockVaultDialog,
                    isLanguageMenuExpanded = uiState.isLanguageMenuExpanded,
                    onToggleLanguageMenu = viewModel::toggleLanguageMenu,
                    onDismissLanguageMenu = viewModel::dismissLanguageMenu,
                    onSelectLocale = { locale ->
                        NexusLocaleController.switchLanguage(locale)
                        viewModel.dismissLanguageMenu()
                    },
                    onOpenAutoUpdateDialog = viewModel::openAutoUpdateDialog,
                    onCompleteFlexibleUpdate = onCompleteFlexibleUpdate,
                    onRequestResetDatabase = viewModel::openResetDatabaseDialog
                )
            },
            sidebarTreeContent = {
                VaultHierarchySidebarTree(
                    uiState = uiState,
                    strings = strings,
                    activeSessionCountByConnectionId = activeSessionCountByConnectionId,
                    onFilterQueryChanged = viewModel::updateSidebarFilterQuery,
                    onSelectQuickFilter = viewModel::setSidebarQuickFilter,
                    smartFolderConnectionsProvider = viewModel::evaluateSmartFolderConnections,
                    onToggleSmartFolder = viewModel::toggleSmartFolderSelection,
                    onNewSmartFolder = viewModel::openNewSmartFolderDialog,
                    onEditSmartFolder = viewModel::openEditSmartFolderDialog,
                    onDuplicateSmartFolder = viewModel::duplicateSmartFolder,
                    onDeleteSmartFolder = viewModel::deleteSmartFolderFromVault,
                    onNewFolder = viewModel::openNewFolderDialog,
                    onSelectFolder = viewModel::selectFolderInTree,
                    onNewConnection = viewModel::openNewConnectionDialog,
                    onNewRdpInFolder = { folderId ->
                        viewModel.openNewConnectionInFolder(folderId, VaultProtocolType.RDP)
                    },
                    onNewSshInFolder = { folderId ->
                        viewModel.openNewConnectionInFolder(folderId, VaultProtocolType.SSH)
                    },
                    onNewSubfolder = viewModel::openNewSubfolder,
                    onRenameFolder = viewModel::openRenameFolderDialog,
                    onEditFolder = viewModel::openEditFolderDialog,
                    onDuplicateFolder = viewModel::duplicateFolder,
                    onRequestDeleteFolder = viewModel::requestDeleteFolderConfirmation,
                    onDeleteFolder = { folderId -> viewModel.deleteFolder(folderId, deleteContents = false) },
                    onConnectAllRdpInFolder = { folderId ->
                        viewModel.connectAllRdpInFolder(folderId) { tab ->
                            onDetachTabToNativeWindow(tab, null)
                        }
                    },
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
                    canMoveFolderToParent = viewModel::canMoveFolderToParent,
                    onOpenMoveFolderDialog = viewModel::openMoveFolderDialog,
                    onMoveFolderToParent = { folderId, targetParentId ->
                        viewModel.moveFolderToParent(folderId, targetParentId)
                    },
                    onMoveOrReorderFolder = { draggedId, targetId, placement ->
                        viewModel.moveOrReorderFolder(draggedId, targetId, placement)
                    },
                    onReorderFolder = { folderId, moveUp ->
                        viewModel.reorderFolder(folderId, moveUp)
                    },
                    onReorderConnection = { connId, moveUp ->
                        viewModel.reorderConnection(connId, moveUp)
                    },
                    onReorderConnectionRelativeTo = { draggedId, targetId, placeAfter ->
                        viewModel.reorderConnectionRelativeTo(draggedId, targetId, placeAfter)
                    },
                    onNewCredential = viewModel::openNewCredentialDialog,
                    onEditCredential = viewModel::openEditCredentialDialog,
                    onDuplicateCredential = viewModel::duplicateCredential,
                    onDeleteCredential = viewModel::deleteCredentialFromVault,
                    onGenerateSshKeyPair = viewModel::generateAndStoreSshKeyPair,
                    onReorderCredentialRelativeTo = viewModel::reorderCredentialRelativeTo,
                    onRejectedDropIntoCredentials = {
                        viewModel.reportStructuredError(
                            com.nexusts.app.core.vault.error.NexusAppError(
                                category = com.nexusts.app.core.vault.error.NexusErrorCategory.VALIDATION,
                                title = strings.credentialsSystemFolderTitle,
                                userMessage = strings.credentialsSystemFolderOnlyHint,
                                remediationHint = "Drop connections and folders onto a connection folder or the root hierarchy instead.",
                                technicalDetails = "NXTS-CRED-DROP-REJECTED"
                            )
                        )
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
                        onOpenGitHubReleaseUrl = onOpenGitHubReleaseUrl,
                        onRequestResetDatabase = if (onResetNexusDatabase != null) {
                            viewModel::openResetDatabaseDialog
                        } else {
                            null
                        }
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
                        onReorderTab = { paneId, fromIdx, toIdx ->
                            viewModel.tilingController.reorderTab(paneId, fromIdx, toIdx)
                        },
                        onMoveTabToPane = { tabId, targetPaneId, targetIdx ->
                            viewModel.tilingController.moveTabToPane(tabId, targetPaneId, targetIdx)
                        },
                        onEnterFullScreen = { tab ->
                            viewModel.enterFullScreenForTab(tab.tabId)
                        },
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
            floatingOverlayContent = {},
            modifier = Modifier.fillMaxSize()
                )
            }

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
                    defaultProtocol = uiState.newConnectionDefaultProtocol,
                    onPickSafSharedFolder = onPickRdpSharedSafFolder
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
                CredentialEditorDialog(
                    visible = uiState.isCredentialDialogVisible,
                    existingCredential = uiState.editingCredential,
                    strings = strings,
                    onDismiss = viewModel::dismissCredentialDialog,
                    onSaveCredential = viewModel::saveCredentialFromDialog,
                    onDeleteCredential = viewModel::deleteCredentialFromVault
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
                    onSaveSmartFolder = viewModel::saveSmartFolderToVault,
                    existingSmartFolder = uiState.editingSmartFolder,
                    allFolders = uiState.vaultDocument.folders.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
                    validateRegex = viewModel::validateSmartFolderRegex,
                    previewMatches = viewModel::previewSmartFolderMatches,
                    onDeleteSmartFolder = viewModel::deleteSmartFolderFromVault
                )
                NewTemplateDialog(
                    visible = uiState.isNewTemplateDialogVisible,
                    strings = strings,
                    onDismiss = viewModel::dismissNewTemplateDialog,
                    onSaveTemplate = viewModel::saveTemplateToVault
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
                MoveFolderToParentDialog(
                    targetFolder = uiState.movingFolderTarget,
                    folders = uiState.vaultDocument.folders.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
                    canMoveFolderToParent = viewModel::canMoveFolderToParent,
                    strings = strings,
                    onMoveToParent = { folderId, targetParentId ->
                        viewModel.moveFolderToParent(folderId, targetParentId)
                    },
                    onDismiss = viewModel::dismissMoveFolderDialog
                )
                RenameFolderDialog(
                    targetFolder = uiState.renamingFolderTarget,
                    strings = strings,
                    onRename = { folderId, newName ->
                        viewModel.renameFolder(folderId, newName)
                    },
                    onDismiss = viewModel::dismissRenameFolderDialog
                )
                DeleteFolderConfirmationDialog(
                    targetFolder = uiState.deletingFolderTarget,
                    folders = uiState.vaultDocument.folders.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
                    connections = uiState.vaultDocument.connections.filterNot { it.id in uiState.vaultDocument.tombstonedObjectIds },
                    strings = strings,
                    onDeleteWithContents = { folderId ->
                        viewModel.deleteFolder(folderId, deleteContents = true)
                    },
                    onDeleteKeepContents = { folderId ->
                        viewModel.deleteFolder(folderId, deleteContents = false)
                    },
                    onDismiss = viewModel::dismissDeleteFolderConfirmation
                )
                RoyalTsImportPlacementDialog(
                    pendingImport = uiState.pendingRoyalTsImport,
                    strings = strings,
                    onConfirmPlacement = viewModel::confirmRoyalTsImport,
                    onDismiss = viewModel::dismissRoyalTsImportDialog
                )
                ResetDatabaseConfirmationDialog(
                    visible = uiState.isResetDatabaseDialogVisible,
                    canExportBackup = uiState.vaultDocument.connections.isNotEmpty() ||
                        uiState.vaultDocument.folders.isNotEmpty() ||
                        uiState.vaultDocument.credentials.isNotEmpty(),
                    strings = strings,
                    onExportBackupFirst = {
                        onRequestExportVault(VaultExportFormat.NXTS_VAULT)
                    },
                    onConfirmReset = {
                        onResetNexusDatabase?.invoke()
                    },
                    onDismiss = viewModel::dismissResetDatabaseDialog
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
                    biometricHardwareAvailable = uiState.biometricHardwareAvailable,
                    biometricUnlockEnabled = uiState.biometricUnlockEnabled,
                    hasSavedPassphrase = uiState.hasSavedVaultLockPassphrase,
                    idleAutoLockMinutes = uiState.idleAutoLockMinutes,
                    onDismiss = viewModel::dismissLockVaultDialog,
                    onQuickLock = onQuickLockVault,
                    onToggleBiometricUnlock = onToggleBiometricUnlock,
                    onChangeIdleAutoLockMinutes = onChangeIdleAutoLockMinutes,
                    onConfirmLock = { pass, biometricChecked ->
                        val chars = pass.toCharArray()
                        onEnrollBiometricPassphrase?.invoke(chars.copyOf(), biometricChecked)
                        viewModel.lockVaultWithPassphrase(chars)
                    }
                )
                EncryptedImportPassphraseDialog(
                    pendingFileName = uiState.pendingEncryptedImportFileName,
                    pendingFormat = uiState.pendingEncryptedImportFormat,
                    errorMessage = uiState.pendingEncryptedImportError,
                    strings = strings,
                    onDismiss = viewModel::dismissEncryptedImportDialog,
                    onSubmitPassphrase = { pass -> viewModel.submitEncryptedImportPassword(pass.toCharArray()) }
                )
                NexusErrorDialog(
                    error = uiState.activeAppError,
                    strings = strings,
                    onEditRelatedConnection = uiState.activeAppError?.recoveryConnectionId?.let { relId ->
                        uiState.vaultDocument.connections.firstOrNull {
                            it.id == relId && it.id !in uiState.vaultDocument.tombstonedObjectIds
                        }?.let { targetConn ->
                            {
                                viewModel.dismissActiveAppError()
                                viewModel.openEditConnectionDialog(targetConn)
                            }
                        }
                    },
                    onDismiss = viewModel::dismissActiveAppError
                )
                ImportErrorAlertDialog(
                    errorMessage = if (uiState.activeAppError == null) uiState.importErrorDialogMessage else null,
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
        }
    }
}

@Composable
private fun FullScreenSessionWorkspace(
    activeTab: WorkspaceSessionTab,
    allActiveTabs: List<WorkspaceSessionTab>,
    uiState: WorkspaceUiState,
    strings: WorkspaceLocalizedStrings,
    remoteKeyCaptureActive: Boolean,
    onShortcutTriggered: (NexusShortcutAction) -> Unit,
    onForwardRawKeyToActiveSession: (com.nexusts.app.core.ui.input.RawHardwareKeyPacket) -> Boolean,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onExitFullScreen: () -> Unit,
    onOpenQuickConnect: () -> Unit,
    onNewConnection: () -> Unit,
    smartFolderConnectionsProvider: (SmartFolder) -> List<VaultConnection>,
    onSelectConnection: (String) -> Unit,
    onLaunchConnection: (VaultConnection) -> Unit,
    sessionContent: @Composable (WorkspaceSessionTab) -> Unit
) {
    val shortcutEngine = LocalNexusShortcutEngine.current
    var isToolbarCollapsed by remember { mutableStateOf(false) }
    var isFolderBrowserOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusTheme.extendedColors.terminalCanvasBackground)
            .nexusRemoteKeyPassthrough(
                captureEnabled = remoteKeyCaptureActive,
                shortcutEngine = shortcutEngine,
                onHostShortcut = onShortcutTriggered,
                onForwardToRemote = onForwardRawKeyToActiveSession
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Full-Screen Session Switcher & Folder Tree Toolbar (strictly outside session viewport)
            if (isToolbarCollapsed) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .background(Color(0xFF0A0F1A))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { isToolbarCollapsed = false }
                                .padding(horizontal = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "Expand Full-Screen Toolbar",
                                tint = Color(0xFF7DD3FC),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "${activeTab.title} (${allActiveTabs.size} active)",
                                style = NexusTheme.monospace.keycapShortcut,
                                color = Color(0xFFE2E8F0),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Exit Full Screen",
                                style = NexusTheme.monospace.keycapShortcut,
                                color = Color(0xFF93C5FD),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(onClick = onExitFullScreen)
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .background(Color(0xFF0A0F1A))
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // 1. Hierarchical Folder Structure / Connect Server Trigger
                            val folderBtnShape = RoundedCornerShape(8.dp)
                            Row(
                                modifier = Modifier
                                    .height(28.dp)
                                    .clip(folderBtnShape)
                                    .background(
                                        if (isFolderBrowserOpen) {
                                            Color(0xFF0284C7).copy(alpha = 0.30f)
                                        } else {
                                            Color(0xFF1E293B)
                                        },
                                        folderBtnShape
                                    )
                                    .border(
                                        0.8.dp,
                                        if (isFolderBrowserOpen) Color(0xFF38BDF8) else Color(0xFF334155),
                                        folderBtnShape
                                    )
                                    .clickable { isFolderBrowserOpen = !isFolderBrowserOpen }
                                    .padding(horizontal = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AccountTree,
                                    contentDescription = "Browse Folders & Servers",
                                    tint = Color(0xFF7DD3FC),
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = if (strings.vaultSectionFolders.contains("&")) {
                                        strings.vaultSectionFolders
                                    } else {
                                        "${strings.vaultSectionFolders} & Servers"
                                    },
                                    style = NexusTheme.monospace.keycapShortcut,
                                    color = Color(0xFFF1F5F9),
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = if (isFolderBrowserOpen) {
                                        Icons.Outlined.KeyboardArrowUp
                                    } else {
                                        Icons.Outlined.KeyboardArrowDown
                                    },
                                    contentDescription = null,
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            IconButton(
                                onClick = onOpenQuickConnect,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = strings.quickConnectButton,
                                    tint = Color(0xFFCBD5E1),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(18.dp)
                                    .background(Color(0xFF334155))
                            )

                            // 2. Active Sessions Switcher Strip
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                allActiveTabs.forEach { tab ->
                                    key(tab.tabId) {
                                        val isCurrent = tab.tabId == activeTab.tabId
                                        val accent = parseHexColorOrNull(tab.customColorHex)
                                            ?: protocolAccentColor(tab.protocol)
                                        val chipShape = RoundedCornerShape(8.dp)
                                        Row(
                                            modifier = Modifier
                                                .height(28.dp)
                                                .clip(chipShape)
                                                .background(
                                                    if (isCurrent) {
                                                        accent.copy(alpha = 0.22f)
                                                    } else {
                                                        Color(0xFF162032)
                                                    },
                                                    chipShape
                                                )
                                                .border(
                                                    width = if (isCurrent) 1.dp else 0.7.dp,
                                                    color = if (isCurrent) accent else Color(0xFF293548),
                                                    shape = chipShape
                                                )
                                                .padding(end = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .clickable {
                                                        isFolderBrowserOpen = false
                                                        onSelectTab(tab.tabId)
                                                    }
                                                    .padding(start = 8.dp, end = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Icon(
                                                    imageVector = protocolIcon(tab.protocol),
                                                    contentDescription = null,
                                                    tint = accent,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Text(
                                                    text = tab.title,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (isCurrent) Color.White else Color(0xFFCBD5E1),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.widthIn(max = 160.dp)
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                                    .semantics(mergeDescendants = true) {
                                                        onClick {
                                                            onCloseTab(tab.tabId)
                                                            true
                                                        }
                                                    }
                                                    .pointerInput(tab.tabId) {
                                                        detectTapGestures { onCloseTab(tab.tabId) }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Close,
                                                    contentDescription = "${strings.closeTab}: ${tab.title}",
                                                    tint = Color(0xFF94A3B8),
                                                    modifier = Modifier.size(11.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 3. Collapse Bar & Exit Full Screen Controls
                            IconButton(
                                onClick = {
                                    isFolderBrowserOpen = false
                                    isToolbarCollapsed = true
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.KeyboardArrowUp,
                                    contentDescription = "Collapse Full-Screen Toolbar",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            val exitBtnShape = RoundedCornerShape(8.dp)
                            Row(
                                modifier = Modifier
                                    .height(28.dp)
                                    .clip(exitBtnShape)
                                    .background(Color(0xFF1E293B), exitBtnShape)
                                    .border(0.8.dp, Color(0xFF475569), exitBtnShape)
                                    .clickable(onClick = onExitFullScreen)
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FullscreenExit,
                                    contentDescription = "Exit Full Screen",
                                    tint = Color(0xFF7DD3FC),
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Exit Full Screen",
                                    style = NexusTheme.monospace.keycapShortcut,
                                    color = Color(0xFFF1F5F9),
                                    maxLines = 1
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.6.dp, color = Color(0xFF1E293B))
                    }
                }
            }

            // Full-Screen Session Viewport + Optional Folder Tree Launcher Overlay
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                sessionContent(activeTab)

                if (isFolderBrowserOpen && !isToolbarCollapsed) {
                    // Scrim to dismiss folder launcher when clicking outside
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.32f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                isFolderBrowserOpen = false
                            }
                    )
                    FullScreenFolderStructurePanel(
                        uiState = uiState,
                        strings = strings,
                        activeConnectionIds = remember(allActiveTabs) {
                            allActiveTabs.map { it.connectionId }.toSet()
                        },
                        smartFolderConnectionsProvider = smartFolderConnectionsProvider,
                        onSelectConnection = onSelectConnection,
                        onLaunchConnection = { conn ->
                            isFolderBrowserOpen = false
                            onLaunchConnection(conn)
                        },
                        onNewConnection = {
                            isFolderBrowserOpen = false
                            onNewConnection()
                        },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 8.dp, top = 4.dp, bottom = 16.dp)
                            .width(392.dp)
                            .heightIn(max = 580.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FullScreenFolderStructurePanel(
    uiState: WorkspaceUiState,
    strings: WorkspaceLocalizedStrings,
    activeConnectionIds: Set<String>,
    smartFolderConnectionsProvider: (SmartFolder) -> List<VaultConnection>,
    onSelectConnection: (String) -> Unit,
    onLaunchConnection: (VaultConnection) -> Unit,
    onNewConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val doc = uiState.vaultDocument
    val tombstones = doc.tombstonedObjectIds
    var filterQuery by remember { mutableStateOf("") }
    val expandedFolderIds = remember { mutableStateMapOf<String, Boolean>() }
    val expandedSmartIds = remember { mutableStateMapOf<String, Boolean>() }

    val activeFolders = remember(doc.folders, tombstones) {
        doc.folders.filter {
            it.id !in tombstones && it.id != VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID
        }.sortedWith(compareBy<VaultFolder> { it.sortOrder }.thenBy { it.name.lowercase() })
    }
    val activeConnections = remember(doc.connections, tombstones) {
        doc.connections.filter {
            it.id !in tombstones && it.parentFolderId != VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID
        }.sortedWith(compareBy<VaultConnection> { it.sortOrder }.thenBy { it.name.lowercase() })
    }
    val activeSmartFolders = remember(doc.smartFolders, tombstones) {
        doc.smartFolders.filter { it.id !in tombstones }
    }

    val normalizedQuery = filterQuery.trim().lowercase()
    fun matchesConnection(conn: VaultConnection): Boolean {
        if (normalizedQuery.isEmpty()) return true
        return conn.name.lowercase().contains(normalizedQuery) ||
            conn.host.lowercase().contains(normalizedQuery) ||
            conn.protocol.name.lowercase().contains(normalizedQuery) ||
            conn.protocol.displayName.lowercase().contains(normalizedQuery) ||
            conn.protocol.shortBadge.lowercase().contains(normalizedQuery)
    }

    data class FlatTreeEntry(
        val key: String,
        val indent: Int,
        val folder: VaultFolder? = null,
        val smartFolder: SmartFolder? = null,
        val connection: VaultConnection? = null,
        val childCount: Int = 0,
        val isExpanded: Boolean = false
    )

    val entries = remember(
        activeFolders,
        activeConnections,
        activeSmartFolders,
        normalizedQuery,
        expandedFolderIds.toMap(),
        expandedSmartIds.toMap()
    ) {
        val list = mutableListOf<FlatTreeEntry>()
        val validFolderIds = activeFolders.map { it.id }.toSet()

        fun countSubtreeConnections(folderId: String): Int {
            val direct = activeConnections.count { it.parentFolderId == folderId && matchesConnection(it) }
            val sub = activeFolders
                .filter { it.parentFolderId == folderId }
                .sumOf { countSubtreeConnections(it.id) }
            return direct + sub
        }

        fun appendFolderSubtree(parentId: String?, depth: Int) {
            val childFolders = activeFolders.filter { folder ->
                val normalizedParent = folder.parentFolderId?.takeIf { it in validFolderIds }
                normalizedParent == parentId
            }
            childFolders.forEach { folder ->
                val count = countSubtreeConnections(folder.id)
                if (normalizedQuery.isEmpty() || count > 0 || folder.name.lowercase().contains(normalizedQuery)) {
                    val expanded = if (normalizedQuery.isNotEmpty()) {
                        true
                    } else {
                        expandedFolderIds[folder.id] ?: true
                    }
                    list += FlatTreeEntry(
                        key = "fs-fld-${folder.id}",
                        indent = depth,
                        folder = folder,
                        childCount = count,
                        isExpanded = expanded
                    )
                    if (expanded) {
                        appendFolderSubtree(folder.id, depth + 1)
                        activeConnections
                            .filter { it.parentFolderId == folder.id && matchesConnection(it) }
                            .forEach { conn ->
                                list += FlatTreeEntry(
                                    key = "fs-conn-${folder.id}-${conn.id}",
                                    indent = depth + 1,
                                    connection = conn
                                )
                            }
                    }
                }
            }
        }

        appendFolderSubtree(parentId = null, depth = 0)

        // Root-level connections
        activeConnections
            .filter { conn ->
                val normalizedParent = conn.parentFolderId?.takeIf { it in validFolderIds }
                normalizedParent == null && matchesConnection(conn)
            }
            .forEach { conn ->
                list += FlatTreeEntry(
                    key = "fs-root-conn-${conn.id}",
                    indent = 0,
                    connection = conn
                )
            }

        // Intelligent / Smart Folders
        activeSmartFolders.forEach { smart ->
            val matched = smartFolderConnectionsProvider(smart).filter(::matchesConnection)
            if (normalizedQuery.isEmpty() || matched.isNotEmpty() || smart.name.lowercase().contains(normalizedQuery)) {
                val expanded = if (normalizedQuery.isNotEmpty()) {
                    true
                } else {
                    expandedSmartIds[smart.id] ?: false
                }
                list += FlatTreeEntry(
                    key = "fs-smart-${smart.id}",
                    indent = 0,
                    smartFolder = smart,
                    childCount = matched.size,
                    isExpanded = expanded
                )
                if (expanded) {
                    matched.forEach { conn ->
                        list += FlatTreeEntry(
                            key = "fs-smart-conn-${smart.id}-${conn.id}",
                            indent = 1,
                            connection = conn
                        )
                    }
                }
            }
        }

        list
    }

    Surface(
        modifier = modifier.shadow(16.dp, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = strings.navConnections,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Double-click a server to connect",
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onNewConnection,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = strings.actionNewConnection,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                placeholder = {
                    Text(
                        text = strings.sidebarFilterPlaceholder,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(entries, key = { it.key }) { item ->
                    when {
                        item.folder != null -> {
                            val folder = item.folder
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val current = expandedFolderIds[folder.id] ?: true
                                        expandedFolderIds[folder.id] = !current
                                    }
                                    .padding(
                                        start = (6 + item.indent * 14).dp,
                                        end = 8.dp,
                                        top = 7.dp,
                                        bottom = 7.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (item.isExpanded) {
                                        Icons.Outlined.ExpandMore
                                    } else {
                                        Icons.Outlined.ChevronRight
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Icon(
                                    imageVector = if (item.isExpanded) {
                                        Icons.Filled.FilledFolderOpenIcon
                                    } else {
                                        Icons.Filled.FilledFolderIcon
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = parseHexColorOrNull(folder.inheritable.displayColorAccentHex)
                                        ?: MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${item.childCount}",
                                    style = NexusTheme.monospace.keycapShortcut,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                                )
                            }
                        }

                        item.smartFolder != null -> {
                            val smart = item.smartFolder
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val current = expandedSmartIds[smart.id] ?: false
                                        expandedSmartIds[smart.id] = !current
                                    }
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (item.isExpanded) {
                                        Icons.Outlined.ExpandMore
                                    } else {
                                        Icons.Outlined.ChevronRight
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = smart.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${item.childCount}",
                                    style = NexusTheme.monospace.keycapShortcut,
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.78f)
                                )
                            }
                        }

                        item.connection != null -> {
                            val conn = item.connection
                            val isSelected = uiState.selectedConnectionId == conn.id
                            val isActive = conn.id in activeConnectionIds
                            val rowHover = rememberDesktopHoverState()
                            val isRowHovered = rowHover.value.isHovered
                            val rowShape = RoundedCornerShape(8.dp)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .nexusHoverTracker(rowHover, PointerIcon.Hand)
                                    .clip(rowShape)
                                    .background(
                                        when {
                                            isSelected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
                                            isRowHovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                            else -> Color.Transparent
                                        },
                                        rowShape
                                    )
                                    .nexusInteractiveRow(
                                        rowKey = "fs-launch-${item.key}",
                                        onClick = { onSelectConnection(conn.id) },
                                        onDoubleClick = {
                                            onSelectConnection(conn.id)
                                            onLaunchConnection(conn)
                                        }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = (12 + item.indent * 14).dp,
                                            end = 6.dp,
                                            top = 7.dp,
                                            bottom = 7.dp
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (isActive) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                    Text(
                                        text = conn.name,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = 14.5.sp,
                                            lineHeight = 19.sp,
                                            fontWeight = if (isSelected || isActive) {
                                                FontWeight.SemiBold
                                            } else {
                                                FontWeight.Medium
                                            },
                                            letterSpacing = 0.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = conn.protocol.shortSidebarBadge(),
                                        style = NexusTheme.monospace.keycapShortcut.copy(
                                            fontSize = 9.sp,
                                            lineHeight = 11.sp,
                                            fontWeight = FontWeight.Normal
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                                        maxLines = 1
                                    )
                                    IconButton(
                                        onClick = {
                                            onSelectConnection(conn.id)
                                            onLaunchConnection(conn)
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.PlayArrow,
                                            contentDescription = "Connect ${conn.name}",
                                            tint = MaterialTheme.colorScheme.primary.copy(
                                                alpha = if (isRowHovered || isSelected) 0.95f else 0.52f
                                            ),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
                onZoomFactorChanged = onZoomChanged,
                isFullScreen = uiState.isFullScreenSessionMode,
                onToggleFullScreen = viewModel::toggleFullScreenSessionMode,
                onEditConnection = conn?.let { target ->
                    { viewModel.openEditConnectionDialog(target) }
                }
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
    destinations: List<NexusRailDestination> = emptyList(),
    selectedDestinationId: String = "connections",
    onSelectDestination: (String) -> Unit = {},
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
    onNewTemplate: () -> Unit,
    onNewCredential: () -> Unit = {},
    onNewLocalShell: () -> Unit,
    onImportRtsz: () -> Unit,
    onExportVault: (VaultExportFormat) -> Unit,
    onSyncGoogleDrive: () -> Unit,
    onToggleRemoteKeyCapture: () -> Unit,
    onToggleBroadcast: () -> Unit,
    onLockVault: () -> Unit,
    isLanguageMenuExpanded: Boolean,
    onToggleLanguageMenu: () -> Unit,
    onDismissLanguageMenu: () -> Unit,
    onSelectLocale: (SupportedNexusLocale) -> Unit,
    onOpenAutoUpdateDialog: () -> Unit,
    onCompleteFlexibleUpdate: () -> Unit,
    onRequestResetDatabase: (() -> Unit)? = null
) {
    var createMenuExpanded by remember { mutableStateOf(false) }
    var toolsMenuExpanded by remember { mutableStateOf(false) }
    var studiosMenuExpanded by remember { mutableStateOf(false) }
    val activeStudio = remember(destinations, selectedDestinationId) {
        destinations.firstOrNull { it.id == selectedDestinationId }
    }
    val inNonSessionStudio = selectedDestinationId != "connections"

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Window Identity + Studios Switcher Cluster
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ExpressiveMorphingIconButton(
                    icon = Icons.Outlined.Menu,
                    contentDescription = "Toggle Sidebar",
                    isActive = isSidebarVisible,
                    onClick = onToggleSidebar
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    NexusBrandEmblem(size = 30.dp, cornerRadius = 9.dp)
                    Text(
                        text = strings.appName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (vaultName.isNotBlank() && vaultName != NexusWorkspaceViewModel.DEFAULT_VAULT_NAME) {
                    Surface(
                        shape = RoundedCornerShape(99.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.widthIn(max = 150.dp)
                    ) {
                        Text(
                            text = vaultName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }

                // Top-Toolbar Studios Switcher (replaces the wide 96dp left NavigationRail)
                if (destinations.isNotEmpty()) {
                    Box {
                        val studiosHover = rememberDesktopHoverState()
                        val studiosInteraction = remember { MutableInteractionSource() }
                        val isStudiosHovered = studiosHover.value.isHovered
                        Surface(
                            modifier = Modifier
                                .height(38.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = strings.navStudiosMenu
                                }
                                .nexusHoverTracker(studiosHover, PointerIcon.Hand)
                                .clip(RoundedCornerShape(99.dp))
                                .clickable(
                                    interactionSource = studiosInteraction,
                                    indication = null,
                                    onClick = { studiosMenuExpanded = true }
                                ),
                            shape = RoundedCornerShape(99.dp),
                            color = when {
                                inNonSessionStudio && (studiosMenuExpanded || isStudiosHovered) ->
                                    androidx.compose.ui.graphics.lerp(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        MaterialTheme.colorScheme.onSecondaryContainer,
                                        0.08f
                                    )
                                inNonSessionStudio -> MaterialTheme.colorScheme.secondaryContainer
                                studiosMenuExpanded || isStudiosHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            border = BorderStroke(
                                1.dp,
                                if (inNonSessionStudio || studiosMenuExpanded || isStudiosHovered) {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                }
                            )
                        ) {
                            val studiosFg = if (inNonSessionStudio) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                            Row(
                                modifier = Modifier.padding(start = 15.dp, end = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                Icon(
                                    imageVector = if (inNonSessionStudio && activeStudio != null) {
                                        activeStudio.icon
                                    } else {
                                        Icons.Outlined.Tune
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                    tint = if (inNonSessionStudio) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Text(
                                    text = if (inNonSessionStudio && activeStudio != null) {
                                        activeStudio.label
                                    } else {
                                        strings.navStudiosMenu
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = studiosFg,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Outlined.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                    tint = if (inNonSessionStudio) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = studiosMenuExpanded,
                            onDismissRequest = { studiosMenuExpanded = false },
                            shape = NexusDropdownMenuShape
                        ) {
                            destinations.forEach { dest ->
                                val isSelected = dest.id == selectedDestinationId
                                NexusSpacedDropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Text(dest.label)
                                            if (dest.badgeCount > 0) {
                                                Surface(
                                                    shape = RoundedCornerShape(99.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer
                                                ) {
                                                    Text(
                                                        text = dest.badgeCount.toString(),
                                                        style = NexusTheme.monospace.keycapShortcut,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = dest.icon,
                                            contentDescription = null,
                                            tint = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    },
                                    trailingIcon = {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Outlined.CheckCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        studiosMenuExpanded = false
                                        onSelectDestination(dest.id)
                                    }
                                )
                            }
                        }
                    }
                }

                if (inNonSessionStudio) {
                    NexusActionChip(
                        onClick = { onSelectDestination("connections") },
                        label = strings.navConnections,
                        leadingIcon = Icons.AutoMirrored.Outlined.ArrowBack
                    )
                }
            }

            // Center Google M3 Quick-Connect Omnibox (`Ctrl + K`) — sleek 38dp pill
            val quickConnectHover = rememberDesktopHoverState()
            val quickConnectInteraction = remember { MutableInteractionSource() }
            val isQuickConnectHovered = quickConnectHover.value.isHovered
            Surface(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(min = 190.dp, max = 360.dp)
                    .padding(horizontal = 14.dp)
                    .height(38.dp)
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
                    modifier = Modifier.padding(horizontal = 14.dp),
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
                            modifier = Modifier.size(16.dp),
                            tint = if (isQuickConnectHovered) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = strings.quickConnectButton,
                            style = MaterialTheme.typography.bodySmall,
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
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Right Google M3 Action Cluster (Clean & Uncluttered)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (flexibleUpdateDownloaded) {
                    FilledTonalButton(
                        onClick = onCompleteFlexibleUpdate,
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(strings.updateRestartAction, style = MaterialTheme.typography.labelLarge)
                    }
                } else if (githubUpdateAvailable) {
                    FilledTonalButton(
                        onClick = onOpenAutoUpdateDialog,
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(
                            text = String.format(
                                strings.autoUpdateAvailableFormat,
                                githubUpdateTag.ifBlank { "" }
                            ).trim(),
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
                        onDismissRequest = { createMenuExpanded = false },
                        shape = NexusDropdownMenuShape
                    ) {
                        NexusSpacedDropdownMenuItem(
                            text = { Text(strings.actionNewConnection) },
                            leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onNewConnection()
                            }
                        )
                        NexusSpacedDropdownMenuItem(
                            text = { Text(strings.actionNewFolder) },
                            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onNewFolder()
                            }
                        )
                        NexusSpacedDropdownMenuItem(
                            text = { Text(strings.actionNewSmartFolder) },
                            leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onNewSmartFolder()
                            }
                        )
                        NexusSpacedDropdownMenuItem(
                            text = { Text(strings.studioActionAddCredential) },
                            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onNewCredential()
                            }
                        )
                        NexusSpacedDropdownMenuItem(
                            text = { Text(strings.actionNewTemplate) },
                            leadingIcon = { Icon(Icons.Outlined.Bookmarks, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onNewTemplate()
                            }
                        )
                        NexusMenuDivider()
                        NexusSpacedDropdownMenuItem(
                            text = { Text("Local Terminal") },
                            leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onNewLocalShell()
                            }
                        )
                        NexusSpacedDropdownMenuItem(
                            text = { Text(strings.actionImportRtsz) },
                            leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onImportRtsz()
                            }
                        )
                        NexusMenuDivider()
                        NexusSpacedDropdownMenuItem(
                            text = { Text(strings.actionExportNxts) },
                            leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onExportVault(VaultExportFormat.NXTS_VAULT)
                            }
                        )
                        NexusSpacedDropdownMenuItem(
                            text = { Text(strings.actionExportRtsz) },
                            leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                            onClick = {
                                createMenuExpanded = false
                                onExportVault(VaultExportFormat.RTSZ_ROYAL_ARCHIVE)
                            }
                        )
                        NexusSpacedDropdownMenuItem(
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

                // Language Flag Dropdown Pill (opens anchored Material 3 DropdownMenu with vector flags)
                Box {
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
                                onClick = onToggleLanguageMenu
                            ),
                        shape = RoundedCornerShape(99.dp),
                        color = when {
                            isLanguageMenuExpanded -> MaterialTheme.colorScheme.secondaryContainer
                            isLangHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = BorderStroke(
                            1.dp,
                            when {
                                isLanguageMenuExpanded -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                isLangHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 14.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
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
                            Icon(
                                imageVector = if (isLanguageMenuExpanded) {
                                    Icons.Outlined.ExpandLess
                                } else {
                                    Icons.Outlined.KeyboardArrowDown
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (isLanguageMenuExpanded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    LanguageSelectionDropdownMenu(
                        expanded = isLanguageMenuExpanded,
                        currentLocale = currentLocale,
                        strings = strings,
                        onSelectLocale = onSelectLocale,
                        onDismiss = onDismissLanguageMenu
                    )
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
                        onDismissRequest = { toolsMenuExpanded = false },
                        shape = NexusDropdownMenuShape
                    ) {
                        NexusSpacedDropdownMenuItem(
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
                        NexusSpacedDropdownMenuItem(
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
                            NexusSpacedDropdownMenuItem(
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
                            NexusSpacedDropdownMenuItem(
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
                        if (onRequestResetDatabase != null) {
                            NexusMenuDivider()
                            NexusSpacedDropdownMenuItem(
                                text = { Text(strings.actionResetDatabase, color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    toolsMenuExpanded = false
                                    onRequestResetDatabase()
                                }
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
        }
    }
}

private val NexusDropdownMenuShape = RoundedCornerShape(16.dp)
private val NexusMenuItemPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)

@Composable
private fun NexusSpacedDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true
) {
    DropdownMenuItem(
        modifier = modifier,
        text = {
            Box(modifier = Modifier.padding(start = 6.dp, end = 8.dp)) {
                text()
            }
        },
        onClick = onClick,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        contentPadding = NexusMenuItemPadding
    )
}

@Composable
private fun NexusMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 5.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    )
}

private const val ROOT_DROP_TARGET_KEY = "__NEXUSTS_ROOT_LEVEL__"

@Stable
private class SidebarTreeDragDropState {
    var draggingConnection by mutableStateOf<VaultConnection?>(null)
    var draggingFolder by mutableStateOf<VaultFolder?>(null)
    var draggingCredential by mutableStateOf<VaultCredential?>(null)
    var pointerPositionInRoot by mutableStateOf(Offset.Zero)
    var hoveredDropTargetKey by mutableStateOf<String?>(null)
    var hoveredFolderDropPlacement by mutableStateOf(FolderDropPlacement.INSIDE)
    var hoveredConnectionTargetId by mutableStateOf<String?>(null)
    var hoveredConnectionPlaceAfter by mutableStateOf(false)
    var hoveredCredentialTargetId by mutableStateOf<String?>(null)
    var hoveredCredentialPlaceAfter by mutableStateOf(false)
    var canMoveFolderValidator: (String, String?) -> Boolean = { _, _ -> false }
    var folderParentLookup: (String) -> String? = { null }

    private val targetBoundsInRoot = mutableStateMapOf<String, Rect>()
    private val connectionBoundsInRoot = mutableStateMapOf<String, Rect>()
    private val credentialBoundsInRoot = mutableStateMapOf<String, Rect>()

    val isDragging: Boolean
        get() = draggingConnection != null || draggingFolder != null || draggingCredential != null

    fun registerDropTarget(key: String, bounds: Rect) {
        targetBoundsInRoot[key] = bounds
    }

    fun unregisterDropTarget(key: String) {
        targetBoundsInRoot.remove(key)
        if (hoveredDropTargetKey == key) {
            hoveredDropTargetKey = null
        }
    }

    fun registerConnectionTarget(connectionId: String, bounds: Rect) {
        connectionBoundsInRoot[connectionId] = bounds
    }

    fun unregisterConnectionTarget(connectionId: String) {
        connectionBoundsInRoot.remove(connectionId)
        if (hoveredConnectionTargetId == connectionId) {
            hoveredConnectionTargetId = null
        }
    }

    fun registerCredentialTarget(credentialId: String, bounds: Rect) {
        credentialBoundsInRoot[credentialId] = bounds
    }

    fun unregisterCredentialTarget(credentialId: String) {
        credentialBoundsInRoot.remove(credentialId)
        if (hoveredCredentialTargetId == credentialId) {
            hoveredCredentialTargetId = null
        }
    }

    fun startDrag(connection: VaultConnection, initialRootPos: Offset) {
        draggingFolder = null
        draggingCredential = null
        draggingConnection = connection
        pointerPositionInRoot = initialRootPos
        updateHoveredTarget(initialRootPos)
    }

    fun startFolderDrag(folder: VaultFolder, initialRootPos: Offset) {
        draggingConnection = null
        draggingCredential = null
        draggingFolder = folder
        pointerPositionInRoot = initialRootPos
        updateHoveredTarget(initialRootPos)
    }

    fun startCredentialDrag(credential: VaultCredential, initialRootPos: Offset) {
        draggingConnection = null
        draggingFolder = null
        draggingCredential = credential
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
        val conn = draggingConnection
        val folder = draggingFolder
        val cred = draggingCredential
        if (cred != null) {
            hoveredDropTargetKey = null
            hoveredConnectionTargetId = null
            val matchedCred = credentialBoundsInRoot.entries
                .firstOrNull { (credId, rect) -> credId != cred.id && rect.contains(rootPos) }
            if (matchedCred != null) {
                hoveredCredentialTargetId = matchedCred.key
                hoveredCredentialPlaceAfter = rootPos.y >= matchedCred.value.center.y
            } else {
                hoveredCredentialTargetId = null
            }
            return
        }
        hoveredCredentialTargetId = null

        // Check if dragging a connection or folder over the bottom-pinned "Credentials" system folder
        val sysCredBounds = targetBoundsInRoot[VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID]
        if ((conn != null || folder != null) && sysCredBounds != null && sysCredBounds.contains(rootPos)) {
            hoveredConnectionTargetId = null
            hoveredFolderDropPlacement = FolderDropPlacement.INSIDE
            hoveredDropTargetKey = VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID
            return
        }

        if (conn != null) {
            val matchedConn = connectionBoundsInRoot.entries
                .firstOrNull { (connId, rect) -> connId != conn.id && rect.contains(rootPos) }
            if (matchedConn != null) {
                hoveredConnectionTargetId = matchedConn.key
                hoveredConnectionPlaceAfter = rootPos.y >= matchedConn.value.center.y
                hoveredDropTargetKey = null
                return
            }
            hoveredConnectionTargetId = null
            hoveredFolderDropPlacement = FolderDropPlacement.INSIDE
            hoveredDropTargetKey = targetBoundsInRoot.entries
                .firstOrNull { (key, rect) ->
                    rect.contains(rootPos) &&
                        when (key) {
                            VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID -> true
                            ROOT_DROP_TARGET_KEY -> conn.parentFolderId != null
                            else -> key != conn.parentFolderId
                        }
                }
                ?.key
        } else if (folder != null) {
            hoveredConnectionTargetId = null
            val matchedTarget = targetBoundsInRoot.entries
                .firstOrNull { (key, rect) ->
                    if (!rect.contains(rootPos) || key == folder.id) return@firstOrNull false
                    when (key) {
                        VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID -> true
                        ROOT_DROP_TARGET_KEY -> folder.parentFolderId != null
                        else -> {
                            val relY = ((rootPos.y - rect.top) / rect.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            val candidatePlacement = when {
                                relY < 0.27f -> FolderDropPlacement.BEFORE
                                relY > 0.73f -> FolderDropPlacement.AFTER
                                else -> FolderDropPlacement.INSIDE
                            }
                            if (candidatePlacement == FolderDropPlacement.INSIDE) {
                                canMoveFolderValidator(folder.id, key)
                            } else {
                                val targetParent = folderParentLookup(key)
                                targetParent == folder.parentFolderId || canMoveFolderValidator(folder.id, targetParent)
                            }
                        }
                    }
                }
            if (matchedTarget != null) {
                val (key, rect) = matchedTarget
                hoveredDropTargetKey = key
                hoveredFolderDropPlacement = if (key == ROOT_DROP_TARGET_KEY || key == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) {
                    FolderDropPlacement.INSIDE
                } else {
                    val relY = ((rootPos.y - rect.top) / rect.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    when {
                        relY < 0.27f -> FolderDropPlacement.BEFORE
                        relY > 0.73f -> FolderDropPlacement.AFTER
                        else -> FolderDropPlacement.INSIDE
                    }
                }
            } else {
                hoveredDropTargetKey = null
                hoveredFolderDropPlacement = FolderDropPlacement.INSIDE
            }
        } else {
            hoveredDropTargetKey = null
            hoveredConnectionTargetId = null
        }
    }

    fun finishDrag(
        onMoveConnectionToFolder: (String, String?) -> Unit,
        onReorderConnectionRelativeTo: (String, String, Boolean) -> Unit = { _, _, _ -> },
        onRejectedDropIntoCredentials: () -> Unit = {}
    ) {
        val conn = draggingConnection
        val targetConnId = hoveredConnectionTargetId
        val placeAfter = hoveredConnectionPlaceAfter
        val targetKey = hoveredDropTargetKey
        cancelDrag()
        if (conn != null) {
            if (targetKey == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) {
                onRejectedDropIntoCredentials()
            } else if (targetConnId != null && targetConnId != conn.id) {
                onReorderConnectionRelativeTo(conn.id, targetConnId, placeAfter)
            } else if (targetKey != null) {
                val destinationFolderId = if (targetKey == ROOT_DROP_TARGET_KEY) null else targetKey
                if (destinationFolderId != conn.parentFolderId) {
                    onMoveConnectionToFolder(conn.id, destinationFolderId)
                }
            }
        }
    }

    fun finishFolderDrag(
        onMoveFolderToParent: (String, String?) -> Unit,
        onMoveOrReorderFolder: (String, String, FolderDropPlacement) -> Unit = { _, _, _ -> },
        onRejectedDropIntoCredentials: () -> Unit = {}
    ) {
        val folder = draggingFolder
        val targetKey = hoveredDropTargetKey
        val placement = hoveredFolderDropPlacement
        cancelDrag()
        if (folder != null && targetKey != null) {
            when (targetKey) {
                VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID -> {
                    onRejectedDropIntoCredentials()
                }
                ROOT_DROP_TARGET_KEY -> {
                    if (canMoveFolderValidator(folder.id, null)) {
                        onMoveFolderToParent(folder.id, null)
                    }
                }
                else -> {
                    onMoveOrReorderFolder(folder.id, targetKey, placement)
                }
            }
        }
    }

    fun finishCredentialDrag(
        onReorderCredentialRelativeTo: (String, String, Boolean) -> Unit
    ) {
        val cred = draggingCredential
        val targetCredId = hoveredCredentialTargetId
        val placeAfter = hoveredCredentialPlaceAfter
        cancelDrag()
        if (cred != null && targetCredId != null && targetCredId != cred.id) {
            onReorderCredentialRelativeTo(cred.id, targetCredId, placeAfter)
        }
    }

    fun cancelDrag() {
        draggingConnection = null
        draggingFolder = null
        draggingCredential = null
        hoveredDropTargetKey = null
        hoveredFolderDropPlacement = FolderDropPlacement.INSIDE
        hoveredConnectionTargetId = null
        hoveredCredentialTargetId = null
    }
}

private data class SidebarFolderEntry(
    val folder: VaultFolder,
    val depth: Int,
    val directConnections: List<VaultConnection>,
    val subtreeConnectionCount: Int,
    val subtreeRdpCount: Int,
    val subtreeActiveSessionCount: Int,
    val hasChildFolders: Boolean,
    val isExpanded: Boolean
)

@Composable
private fun SidebarQuickFilterChip(
    label: String,
    selected: Boolean,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    count: Int = 0,
    onClick: () -> Unit
) {
    val hover = rememberDesktopHoverState()
    val interaction = remember { MutableInteractionSource() }
    val isHovered = hover.value.isHovered
    Surface(
        modifier = Modifier
            .height(26.dp)
            .nexusHoverTracker(hover, PointerIcon.Hand)
            .clip(RoundedCornerShape(99.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = RoundedCornerShape(99.dp),
        color = when {
            selected && isHovered -> androidx.compose.ui.graphics.lerp(
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
                0.08f
            )
            selected -> MaterialTheme.colorScheme.secondaryContainer
            isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> Color.Transparent
        },
        border = BorderStroke(
            width = 1.dp,
            color = when {
                selected -> MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                isHovered -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        val fgColor = when {
            selected -> MaterialTheme.colorScheme.onSecondaryContainer
            isHovered -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = fgColor
                )
            }
            Text(
                text = if (count > 0) "$label $count" else label,
                style = MaterialTheme.typography.labelSmall,
                color = fgColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun VaultHierarchySidebarTree(
    uiState: WorkspaceUiState,
    strings: WorkspaceLocalizedStrings,
    activeSessionCountByConnectionId: Map<String, Int> = emptyMap(),
    onFilterQueryChanged: (String) -> Unit,
    onSelectQuickFilter: (SidebarQuickFilter) -> Unit = {},
    smartFolderConnectionsProvider: (SmartFolder) -> List<VaultConnection>,
    onToggleSmartFolder: (String) -> Unit,
    onNewSmartFolder: () -> Unit,
    onEditSmartFolder: (SmartFolder) -> Unit = {},
    onDuplicateSmartFolder: (SmartFolder) -> Unit = {},
    onDeleteSmartFolder: (String) -> Unit,
    onNewFolder: () -> Unit,
    onSelectFolder: (String?) -> Unit = {},
    onNewConnection: () -> Unit = {},
    onNewRdpInFolder: (String) -> Unit = {},
    onNewSshInFolder: (String) -> Unit = {},
    onNewSubfolder: (String) -> Unit = {},
    onRenameFolder: (VaultFolder) -> Unit = {},
    onEditFolder: (VaultFolder) -> Unit,
    onDuplicateFolder: (VaultFolder) -> Unit = {},
    onRequestDeleteFolder: (VaultFolder) -> Unit = {},
    onDeleteFolder: (String) -> Unit,
    onConnectAllRdpInFolder: (String) -> Unit = {},
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
    canMoveFolderToParent: (String, String?) -> Boolean = { _, _ -> false },
    onOpenMoveFolderDialog: (VaultFolder) -> Unit = {},
    onMoveFolderToParent: (String, String?) -> Unit = { _, _ -> },
    onMoveOrReorderFolder: (String, String, FolderDropPlacement) -> Unit = { _, _, _ -> },
    onReorderFolder: (String, Boolean) -> Unit = { _, _ -> },
    onReorderConnection: (String, Boolean) -> Unit = { _, _ -> },
    onReorderConnectionRelativeTo: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onNewCredential: () -> Unit = {},
    onEditCredential: (VaultCredential) -> Unit = {},
    onDuplicateCredential: (VaultCredential) -> Unit = {},
    onDeleteCredential: (String) -> Unit = {},
    onGenerateSshKeyPair: () -> Unit = {},
    onReorderCredentialRelativeTo: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onRejectedDropIntoCredentials: () -> Unit = {},
    onLaunchConnection: (VaultConnection, QuickLaunchPlacement) -> Unit,
    onLaunchConnectionInSplitDown: (VaultConnection) -> Unit = {},
    onLaunchLocalShell: () -> Unit = {},
    onImportVault: () -> Unit = {}
) {
    val doc = uiState.vaultDocument
    val query = uiState.sidebarFilterQuery.trim().lowercase()
    var sidebarBgMenuOpen by remember { mutableStateOf(false) }
    var sidebarBgMenuOffsetPx by remember { mutableStateOf(Offset.Zero) }
    val dragDropState = remember { SidebarTreeDragDropState() }
    dragDropState.canMoveFolderValidator = canMoveFolderToParent
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
    val activeFolders = remember(doc.folders, doc.tombstonedObjectIds) {
        doc.folders
            .filterNot { it.id in doc.tombstonedObjectIds }
            .sortedWith(
                compareBy<VaultFolder> { it.sortOrder }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.id }
            )
    }
    val activeCredentials = remember(doc.credentials, doc.tombstonedObjectIds, query) {
        doc.credentials
            .filterNot { it.id in doc.tombstonedObjectIds }
            .filter { cred ->
                query.isEmpty() ||
                    cred.name.lowercase().contains(query) ||
                    cred.username.lowercase().contains(query) ||
                    cred.domain.lowercase().contains(query)
            }
    }
    val totalActiveCredentialCount = remember(doc.credentials, doc.tombstonedObjectIds) {
        doc.credentials.count { it.id !in doc.tombstonedObjectIds }
    }
    val activeFolderIds = remember(activeFolders) { activeFolders.map { it.id }.toSet() }
    val folderByIdMap = remember(activeFolders) { activeFolders.associateBy { it.id } }
    dragDropState.folderParentLookup = { id -> folderByIdMap[id]?.parentFolderId }
    val childrenByParentMap = remember(activeFolders, activeFolderIds) {
        activeFolders.groupBy { folder ->
            folder.parentFolderId?.takeIf { it in activeFolderIds && it != folder.id }
        }
    }
    val sortedActiveConnections = remember(doc.connections, doc.tombstonedObjectIds) {
        doc.connections
            .filterNot { it.id in doc.tombstonedObjectIds }
            .sortedWith(
                compareBy<VaultConnection> { it.sortOrder }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.id }
            )
    }
    var collapsedFolderIds by remember { mutableStateOf(emptySet<String>()) }
    var isSystemCredentialsExpanded by remember { mutableStateOf(true) }

    fun collectSubtreeIds(folderId: String): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(folderId)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (visited.add(id)) {
                childrenByParentMap[id].orEmpty().forEach { queue.add(it.id) }
            }
        }
        return visited
    }

    // Automatically expand a collapsed folder when hovering a dragged connection or folder INSIDE it for 480ms
    LaunchedEffect(dragDropState.hoveredDropTargetKey, dragDropState.hoveredFolderDropPlacement) {
        val hoveredKey = dragDropState.hoveredDropTargetKey
        if (hoveredKey != null &&
            hoveredKey != ROOT_DROP_TARGET_KEY &&
            hoveredKey != VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID &&
            dragDropState.hoveredFolderDropPlacement == FolderDropPlacement.INSIDE &&
            hoveredKey in collapsedFolderIds
        ) {
            delay(480L)
            if (dragDropState.hoveredDropTargetKey == hoveredKey &&
                dragDropState.hoveredFolderDropPlacement == FolderDropPlacement.INSIDE
            ) {
                collapsedFolderIds = collapsedFolderIds - hoveredKey
            }
        }
    }

    val flattenedFolders = remember(
        activeFolders,
        childrenByParentMap,
        sortedActiveConnections,
        query,
        collapsedFolderIds,
        activeSessionCountByConnectionId
    ) {
        val allConnectionsByFolder = sortedActiveConnections.groupBy { it.parentFolderId }
        val connectionsByFolder = sortedActiveConnections
            .filter(::matchesFilter)
            .groupBy { it.parentFolderId }

        fun countSubtreeMatches(folderId: String, visited: Set<String>): Int {
            if (folderId in visited) return 0
            val nextVisited = visited + folderId
            val direct = connectionsByFolder[folderId]?.size ?: 0
            val childTotal = childrenByParentMap[folderId].orEmpty().sumOf { child ->
                countSubtreeMatches(child.id, nextVisited)
            }
            return direct + childTotal
        }

        fun countSubtreeRdp(folderId: String, visited: Set<String>): Int {
            if (folderId in visited) return 0
            val nextVisited = visited + folderId
            val directRdp = allConnectionsByFolder[folderId].orEmpty().count { conn ->
                conn.protocol == VaultProtocolType.RDP || conn.protocol == VaultProtocolType.WINDOWS_TERMINAL_SERVICES
            }
            val childRdp = childrenByParentMap[folderId].orEmpty().sumOf { child ->
                countSubtreeRdp(child.id, nextVisited)
            }
            return directRdp + childRdp
        }

        fun countSubtreeActiveSessions(folderId: String, visited: Set<String>): Int {
            if (folderId in visited) return 0
            val nextVisited = visited + folderId
            val directSessions = allConnectionsByFolder[folderId].orEmpty().sumOf { conn ->
                activeSessionCountByConnectionId[conn.id] ?: 0
            }
            val childSessions = childrenByParentMap[folderId].orEmpty().sumOf { child ->
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
            return childrenByParentMap[folder.id].orEmpty().any { child ->
                subtreeMatchesFolderQuery(child, nextVisited)
            }
        }

        val result = mutableListOf<SidebarFolderEntry>()
        fun visit(folder: VaultFolder, depth: Int, visited: Set<String>) {
            if (folder.id in visited) return
            if (!subtreeMatchesFolderQuery(folder, visited)) return
            val nextVisited = visited + folder.id
            val childFolders = childrenByParentMap[folder.id].orEmpty()
            val directConns = connectionsByFolder[folder.id].orEmpty()
            val subtreeCount = countSubtreeMatches(folder.id, visited)
            val subtreeRdp = countSubtreeRdp(folder.id, visited)
            val subtreeActive = countSubtreeActiveSessions(folder.id, visited)
            val expanded = query.isNotEmpty() || folder.id !in collapsedFolderIds
            result += SidebarFolderEntry(
                folder = folder,
                depth = depth,
                directConnections = directConns,
                subtreeConnectionCount = subtreeCount,
                subtreeRdpCount = subtreeRdp,
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

        childrenByParentMap[null].orEmpty().forEach { root ->
            visit(root, 0, emptySet())
        }
        result
    }

    val rootConnections = remember(sortedActiveConnections, activeFolderIds, query) {
        sortedActiveConnections.filter {
            (it.parentFolderId == null || it.parentFolderId !in activeFolderIds) && matchesFilter(it)
        }
    }
    val allCollapsed = activeFolders.isNotEmpty() && collapsedFolderIds.containsAll(activeFolderIds)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                sidebarOriginInRoot = coords.positionInRoot()
            }
            .nexusDesktopPointerButtons(
                onSecondaryClick = { pos ->
                    sidebarBgMenuOffsetPx = pos
                    sidebarBgMenuOpen = true
                }
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Compact Explorer Header: Search + Quick Actions + Filter Chips
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (uiState.sidebarFilterQuery.isEmpty()) {
                                    Text(
                                        text = strings.sidebarFilterPlaceholder,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = uiState.sidebarFilterQuery,
                                    onValueChange = onFilterQueryChanged,
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (uiState.sidebarFilterQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { onFilterQueryChanged("") },
                                    modifier = Modifier.size(18.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Clear Filter",
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    IconButton(
                        onClick = onNewFolder,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CreateNewFolder,
                            contentDescription = strings.actionNewFolder,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onNewConnection,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = strings.actionNewConnection,
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (activeFolders.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                collapsedFolderIds = if (allCollapsed) emptySet() else activeFolderIds
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                imageVector = if (allCollapsed) Icons.Outlined.UnfoldMore else Icons.Outlined.UnfoldLess,
                                contentDescription = if (allCollapsed) "Expand All Folders" else "Collapse All Folders",
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Compact Filter Chips (`All` default shows pure Folders & Connections immediately)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    SidebarQuickFilterChip(
                        label = strings.vaultSectionFolders,
                        selected = uiState.sidebarQuickFilter == SidebarQuickFilter.ALL,
                        leadingIcon = Icons.Outlined.Folder,
                        onClick = { onSelectQuickFilter(SidebarQuickFilter.ALL) }
                    )
                    SidebarQuickFilterChip(
                        label = "★",
                        selected = uiState.sidebarQuickFilter == SidebarQuickFilter.FAVORITES,
                        count = favorites.size,
                        onClick = {
                            onSelectQuickFilter(
                                if (uiState.sidebarQuickFilter == SidebarQuickFilter.FAVORITES) {
                                    SidebarQuickFilter.ALL
                                } else {
                                    SidebarQuickFilter.FAVORITES
                                }
                            )
                        }
                    )
                    SidebarQuickFilterChip(
                        label = strings.vaultSectionRecents.split(" ").firstOrNull() ?: "Recent",
                        selected = uiState.sidebarQuickFilter == SidebarQuickFilter.RECENTS,
                        leadingIcon = Icons.Outlined.History,
                        count = recentConnections.size,
                        onClick = {
                            onSelectQuickFilter(
                                if (uiState.sidebarQuickFilter == SidebarQuickFilter.RECENTS) {
                                    SidebarQuickFilter.ALL
                                } else {
                                    SidebarQuickFilter.RECENTS
                                }
                            )
                        }
                    )
                }
            }

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                when (uiState.sidebarQuickFilter) {
                    SidebarQuickFilter.FAVORITES -> {
                        if (favorites.isEmpty()) {
                            item(key = "empty-favorites") {
                                Text(
                                    text = "No favorite connections yet",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        } else {
                            groupConnectionsIntelligently(favorites).forEach { group ->
                                if (group.showsHeader) {
                                    item(key = "fav-hdr-${group.badge}") {
                                        SidebarProtocolGroupHeaderRow(
                                            badge = group.badge,
                                            count = group.connections.size,
                                            indentLevel = 0
                                        )
                                    }
                                }
                                items(group.connections, key = { "fav-${it.id}" }) { conn ->
                                    ConnectionTreeRow(
                                        connection = conn,
                                        indentLevel = 0,
                                        isSelected = conn.id == uiState.selectedConnectionId,
                                        strings = strings,
                                        activeSessionCount = activeSessionCountByConnectionId[conn.id] ?: 0,
                                        enableDragToFolder = false,
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
                    }

                    SidebarQuickFilter.RECENTS -> {
                        if (recentConnections.isEmpty()) {
                            item(key = "empty-recents") {
                                Text(
                                    text = "No recent sessions",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        } else {
                            groupConnectionsIntelligently(
                                recentConnections,
                                preserveInputOrderWithinGroups = true
                            ).forEach { group ->
                                if (group.showsHeader) {
                                    item(key = "recent-hdr-${group.badge}") {
                                        SidebarProtocolGroupHeaderRow(
                                            badge = group.badge,
                                            count = group.connections.size,
                                            indentLevel = 0
                                        )
                                    }
                                }
                                items(group.connections, key = { "recent-${it.id}" }) { conn ->
                                    ConnectionTreeRow(
                                        connection = conn,
                                        indentLevel = 0,
                                        isSelected = conn.id == uiState.selectedConnectionId,
                                        strings = strings,
                                        activeSessionCount = activeSessionCountByConnectionId[conn.id] ?: 0,
                                        enableDragToFolder = false,
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
                    }

                    SidebarQuickFilter.ALL -> {
                        // Root-Level Drop Zone Banner (appears while dragging a nested connection or nested folder to move it to root)
                        val draggingConn = dragDropState.draggingConnection
                        val draggingFolder = dragDropState.draggingFolder
                        if ((draggingConn != null && draggingConn.parentFolderId != null) ||
                            (draggingFolder != null && draggingFolder.parentFolderId != null)
                        ) {
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
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isRootHovered) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    border = BorderStroke(
                                        width = if (isRootHovered) 1.5.dp else 1.dp,
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
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DriveFileMove,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = strings.moveToRootFolder,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        // Pure Hierarchical Folders & Connections Tree right from the top
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
                                    isSelectedFolder = folder.id == uiState.selectedFolderId,
                                    isExpanded = entry.isExpanded,
                                    hasExpandableChildren = entry.hasChildFolders || entry.directConnections.isNotEmpty(),
                                    explicitFolderCredentialName = explicitFolderCred?.name,
                                    childCount = entry.subtreeConnectionCount,
                                    rdpCount = entry.subtreeRdpCount,
                                    activeSessionCountInSubtree = entry.subtreeActiveSessionCount,
                                    dragDropState = dragDropState,
                                    strings = strings,
                                    onSelectFolder = { onSelectFolder(folder.id) },
                                    onToggleExpand = {
                                        collapsedFolderIds = if (folder.id in collapsedFolderIds) {
                                            collapsedFolderIds - folder.id
                                        } else {
                                            collapsedFolderIds + folder.id
                                        }
                                    },
                                    onExpandSubtree = {
                                        collapsedFolderIds = collapsedFolderIds - collectSubtreeIds(folder.id)
                                    },
                                    onCollapseSubtree = {
                                        collapsedFolderIds = collapsedFolderIds + collectSubtreeIds(folder.id)
                                    },
                                    onNewRdpInFolder = { onNewRdpInFolder(folder.id) },
                                    onNewSshInFolder = { onNewSshInFolder(folder.id) },
                                    onNewSubfolder = { onNewSubfolder(folder.id) },
                                    onRenameFolder = { onRenameFolder(folder) },
                                    onEditFolder = { onEditFolder(folder) },
                                    onDuplicateFolder = { onDuplicateFolder(folder) },
                                    onRequestDeleteFolder = { onRequestDeleteFolder(folder) },
                                    onDeleteFolder = { onDeleteFolder(folder.id) },
                                    onConnectAllRdpInFolder = { onConnectAllRdpInFolder(folder.id) },
                                    onConnectAllInFolder = { onConnectAllInFolder(folder.id) },
                                    onDisconnectAllInFolder = { onDisconnectAllInFolder(folder.id) },
                                    onOpenMoveFolderDialog = { onOpenMoveFolderDialog(folder) },
                                    onMoveFolderToParent = { targetParentId -> onMoveFolderToParent(folder.id, targetParentId) },
                                    onMoveOrReorderFolder = { draggedId, targetId, placement ->
                                        onMoveOrReorderFolder(draggedId, targetId, placement)
                                    },
                                    onRejectedDropIntoCredentials = onRejectedDropIntoCredentials,
                                    onMoveUp = { onReorderFolder(folder.id, true) },
                                    onMoveDown = { onReorderFolder(folder.id, false) }
                                )
                            }
                            if (entry.isExpanded) {
                                groupConnectionsIntelligently(entry.directConnections).forEach { group ->
                                    if (group.showsHeader) {
                                        item(key = "fconn-hdr-${folder.id}-${group.badge}") {
                                            SidebarProtocolGroupHeaderRow(
                                                badge = group.badge,
                                                count = group.connections.size,
                                                indentLevel = entry.depth + 1
                                            )
                                        }
                                    }
                                    items(group.connections, key = { "fconn-${folder.id}-${it.id}" }) { conn ->
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
                                            onReorderRelativeTo = { targetConnId, placeAfter ->
                                                onReorderConnectionRelativeTo(conn.id, targetConnId, placeAfter)
                                            },
                                            onRejectedDropIntoCredentials = onRejectedDropIntoCredentials,
                                            onMoveUp = { onReorderConnection(conn.id, true) },
                                            onMoveDown = { onReorderConnection(conn.id, false) },
                                            onLaunch = { placement -> onLaunchConnection(conn, placement) },
                                            onLaunchSplitDown = { onLaunchConnectionInSplitDown(conn) }
                                        )
                                    }
                                }
                            }
                        }

                        groupConnectionsIntelligently(rootConnections).forEach { group ->
                            if (group.showsHeader) {
                                item(key = "root-hdr-${group.badge}") {
                                    SidebarProtocolGroupHeaderRow(
                                        badge = group.badge,
                                        count = group.connections.size,
                                        indentLevel = 0
                                    )
                                }
                            }
                            items(group.connections, key = { "root-${it.id}" }) { conn ->
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
                                    onReorderRelativeTo = { targetConnId, placeAfter ->
                                        onReorderConnectionRelativeTo(conn.id, targetConnId, placeAfter)
                                    },
                                    onRejectedDropIntoCredentials = onRejectedDropIntoCredentials,
                                    onMoveUp = { onReorderConnection(conn.id, true) },
                                    onMoveDown = { onReorderConnection(conn.id, false) },
                                    onLaunch = { placement -> onLaunchConnection(conn, placement) },
                                    onLaunchSplitDown = { onLaunchConnectionInSplitDown(conn) }
                                )
                            }
                        }

                        if (flattenedFolders.isEmpty() && rootConnections.isEmpty()) {
                            item(key = "empty-sidebar-hint") {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp, start = 2.dp, end = 2.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeviceHub,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                        FilledTonalButton(
                                            onClick = onNewConnection,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
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

                        // Always-discoverable Intelligent Folders (Smart Folders) section with inline `+` action
                        item(key = "header-smart-folders") {
                            Spacer(modifier = Modifier.height(6.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                    Text(
                                        text = strings.vaultSectionSmartFolders,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                IconButton(onClick = onNewSmartFolder, modifier = Modifier.size(22.dp)) {
                                    Icon(
                                        imageVector = Icons.Outlined.Add,
                                        contentDescription = strings.actionNewSmartFolder,
                                        modifier = Modifier.size(14.dp),
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
                                    onEdit = { onEditSmartFolder(smart) },
                                    onDuplicate = { onDuplicateSmartFolder(smart) },
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
                                groupConnectionsIntelligently(matchedConnections).forEach { group ->
                                    if (group.showsHeader) {
                                        item(key = "smart-hdr-${smart.id}-${group.badge}") {
                                            SidebarProtocolGroupHeaderRow(
                                                badge = group.badge,
                                                count = group.connections.size,
                                                indentLevel = 1
                                            )
                                        }
                                    }
                                    items(group.connections, key = { "smart-${smart.id}-${it.id}" }) { conn ->
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
                    }
                }
            }

            // Pinned System Folder "Credentials" — ALWAYS at the bottom of the left sidebar hierarchy,
            // strictly holding VaultCredential items only (never connections or subfolders).
            SystemCredentialsSidebarFooter(
                credentials = activeCredentials,
                totalCredentialCount = totalActiveCredentialCount,
                isExpanded = isSystemCredentialsExpanded,
                dragDropState = dragDropState,
                strings = strings,
                onToggleExpand = { isSystemCredentialsExpanded = !isSystemCredentialsExpanded },
                onNewCredential = onNewCredential,
                onEditCredential = onEditCredential,
                onDuplicateCredential = onDuplicateCredential,
                onDeleteCredential = onDeleteCredential,
                onGenerateSshKeyPair = onGenerateSshKeyPair,
                onReorderCredentialRelativeTo = onReorderCredentialRelativeTo
            )
        }

        // Floating Drag Ghost Preview Card while dragging a connection, folder, or credential
        val activeDraggedConn = dragDropState.draggingConnection
        val activeDraggedFolder = dragDropState.draggingFolder
        val activeDraggedCred = dragDropState.draggingCredential
        if (activeDraggedConn != null || activeDraggedFolder != null || activeDraggedCred != null) {
            val isRejectedCredDrop =
                dragDropState.hoveredDropTargetKey == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID
            val targetLabel = when {
                isRejectedCredDrop -> strings.systemCredentialsFolderTitle
                dragDropState.hoveredCredentialTargetId != null -> {
                    val targetCred = activeCredentials.firstOrNull {
                        it.id == dragDropState.hoveredCredentialTargetId
                    }
                    targetCred?.let {
                        if (dragDropState.hoveredCredentialPlaceAfter) "After ${it.name}" else "Before ${it.name}"
                    }
                }
                dragDropState.hoveredConnectionTargetId != null -> {
                    val targetConn = sortedActiveConnections.firstOrNull {
                        it.id == dragDropState.hoveredConnectionTargetId
                    }
                    targetConn?.let {
                        if (dragDropState.hoveredConnectionPlaceAfter) "After ${it.name}" else "Before ${it.name}"
                    }
                }
                dragDropState.hoveredDropTargetKey == ROOT_DROP_TARGET_KEY -> strings.moveToRootFolder
                dragDropState.hoveredDropTargetKey != null -> {
                    val targetFolder = activeFolders.firstOrNull { it.id == dragDropState.hoveredDropTargetKey }
                    targetFolder?.let { tf ->
                        when (dragDropState.hoveredFolderDropPlacement) {
                            FolderDropPlacement.BEFORE -> "Before ${tf.name}"
                            FolderDropPlacement.AFTER -> "After ${tf.name}"
                            FolderDropPlacement.INSIDE -> tf.name
                        }
                    }
                }
                else -> null
            }
            val localX = (dragDropState.pointerPositionInRoot.x - sidebarOriginInRoot.x + 14f)
                .roundToInt()
                .coerceAtLeast(8)
            val localY = (dragDropState.pointerPositionInRoot.y - sidebarOriginInRoot.y - 22f)
                .roundToInt()
                .coerceAtLeast(8)

            Surface(
                modifier = Modifier
                    .offset { IntOffset(localX, localY) }
                    .widthIn(max = 250.dp)
                    .shadow(8.dp, RoundedCornerShape(10.dp)),
                shape = RoundedCornerShape(10.dp),
                color = if (isRejectedCredDrop) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                border = BorderStroke(
                    1.5.dp,
                    if (isRejectedCredDrop) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when {
                        activeDraggedConn != null -> {
                            val uiProto = NexusWorkspaceViewModel.mapProtocolToUiKind(activeDraggedConn.protocol)
                            Icon(
                                imageVector = protocolIcon(uiProto),
                                contentDescription = null,
                                tint = protocolAccentColor(uiProto),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        activeDraggedCred != null -> {
                            Icon(
                                imageVector = Icons.Outlined.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Filled.FilledFolderIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = activeDraggedConn?.name
                                ?: activeDraggedFolder?.name
                                ?: activeDraggedCred?.name.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isRejectedCredDrop) {
                            Text(
                                text = strings.credentialsSystemFolderOnlyHint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else if (targetLabel != null) {
                            Text(
                                text = String.format(strings.dragDropMoveHintFormat, targetLabel),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        val bgDensity = LocalDensity.current
        DropdownMenu(
            expanded = sidebarBgMenuOpen,
            onDismissRequest = { sidebarBgMenuOpen = false },
            offset = with(bgDensity) { DpOffset(sidebarBgMenuOffsetPx.x.toDp(), 0.dp) },
            shape = NexusDropdownMenuShape
        ) {
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionNewConnection) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    sidebarBgMenuOpen = false
                    onNewConnection()
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionNewFolder) },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                onClick = {
                    sidebarBgMenuOpen = false
                    onNewFolder()
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionNewSmartFolder) },
                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                onClick = {
                    sidebarBgMenuOpen = false
                    onNewSmartFolder()
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.studioActionAddCredential) },
                leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                onClick = {
                    sidebarBgMenuOpen = false
                    onNewCredential()
                }
            )
            NexusMenuDivider()
            NexusSpacedDropdownMenuItem(
                text = { Text("Local Terminal") },
                leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                onClick = {
                    sidebarBgMenuOpen = false
                    onLaunchLocalShell()
                }
            )
            NexusSpacedDropdownMenuItem(
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
private fun SystemCredentialsSidebarFooter(
    credentials: List<VaultCredential>,
    totalCredentialCount: Int,
    isExpanded: Boolean,
    dragDropState: SidebarTreeDragDropState,
    strings: WorkspaceLocalizedStrings,
    onToggleExpand: () -> Unit,
    onNewCredential: () -> Unit,
    onEditCredential: (VaultCredential) -> Unit,
    onDuplicateCredential: (VaultCredential) -> Unit,
    onDeleteCredential: (String) -> Unit,
    onGenerateSshKeyPair: () -> Unit,
    onReorderCredentialRelativeTo: (String, String, Boolean) -> Unit
) {
    var headerMenuOpen by remember { mutableStateOf(false) }
    var headerMenuOffsetPx by remember { mutableStateOf(Offset.Zero) }
    val hover = rememberDesktopHoverState()
    val isHovered = hover.value.isHovered
    val isRejectedHover =
        (dragDropState.draggingConnection != null || dragDropState.draggingFolder != null) &&
            dragDropState.hoveredDropTargetKey == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID

    DisposableEffect(Unit) {
        onDispose {
            dragDropState.unregisterDropTarget(VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                dragDropState.registerDropTarget(
                    VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID,
                    coords.boundsInRoot()
                )
            },
        color = if (isRejectedHover) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(
                thickness = 1.dp,
                color = if (isRejectedHover) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                }
            )
            Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 32.dp)
                        .nexusHoverTracker(hover, PointerIcon.Hand)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                isRejectedHover -> MaterialTheme.colorScheme.errorContainer
                                isHovered || headerMenuOpen -> MaterialTheme.colorScheme.surfaceContainerHigh
                                else -> Color.Transparent
                            }
                        )
                        .nexusInteractiveRow(
                            rowKey = VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID,
                            onClick = onToggleExpand,
                            onSecondaryClick = { pos ->
                                headerMenuOffsetPx = pos
                                headerMenuOpen = true
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded && credentials.isNotEmpty()) {
                                Icons.Outlined.KeyboardArrowDown
                            } else {
                                Icons.Outlined.ChevronRight
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isRejectedHover) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            }
                        )
                        Text(
                            text = strings.systemCredentialsFolderTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isRejectedHover) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = totalCredentialCount.toString(),
                                style = NexusTheme.monospace.keycapShortcut,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                        IconButton(
                            onClick = onNewCredential,
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = strings.studioActionAddCredential,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }

                val density = LocalDensity.current
                DropdownMenu(
                    expanded = headerMenuOpen,
                    onDismissRequest = { headerMenuOpen = false },
                    offset = with(density) { DpOffset(headerMenuOffsetPx.x.toDp(), 0.dp) },
                    shape = NexusDropdownMenuShape
                ) {
                    NexusSpacedDropdownMenuItem(
                        text = { Text(strings.studioActionAddCredential) },
                        leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        onClick = {
                            headerMenuOpen = false
                            onNewCredential()
                        }
                    )
                    NexusSpacedDropdownMenuItem(
                        text = { Text(strings.inspectorGenerateSshKey) },
                        leadingIcon = { Icon(Icons.Outlined.VpnKey, contentDescription = null) },
                        onClick = {
                            headerMenuOpen = false
                            onGenerateSshKeyPair()
                        }
                    )
                    if (credentials.isNotEmpty()) {
                        NexusMenuDivider()
                        NexusSpacedDropdownMenuItem(
                            text = { Text(if (isExpanded) "Collapse" else "Expand") },
                            leadingIcon = {
                                Icon(
                                    if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                headerMenuOpen = false
                                onToggleExpand()
                            }
                        )
                    }
                }
            }

            if (isExpanded && credentials.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 176.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 14.dp, end = 6.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    credentials.forEach { cred ->
                        CredentialTreeRow(
                            credential = cred,
                            dragDropState = dragDropState,
                            strings = strings,
                            onEdit = { onEditCredential(cred) },
                            onDuplicate = { onDuplicateCredential(cred) },
                            onDelete = { onDeleteCredential(cred.id) },
                            onReorderRelativeTo = onReorderCredentialRelativeTo
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialTreeRow(
    credential: VaultCredential,
    dragDropState: SidebarTreeDragDropState,
    strings: WorkspaceLocalizedStrings,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onReorderRelativeTo: (String, String, Boolean) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var menuOffsetPx by remember { mutableStateOf(Offset.Zero) }
    val hover = rememberDesktopHoverState()
    val handleHover = rememberDesktopHoverState()
    val isHovered = hover.value.isHovered
    val isBeingDragged = dragDropState.draggingCredential?.id == credential.id
    val isReorderTarget = dragDropState.hoveredCredentialTargetId == credential.id
    val reorderPlaceAfter = dragDropState.hoveredCredentialPlaceAfter
    var handleOriginInRoot by remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(credential.id) {
        onDispose {
            dragDropState.unregisterCredentialTarget(credential.id)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                dragDropState.registerCredentialTarget(credential.id, coords.boundsInRoot())
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 28.dp)
                .nexusHoverTracker(hover, PointerIcon.Hand)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    when {
                        isBeingDragged -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.38f)
                        isHovered || menuOpen -> MaterialTheme.colorScheme.surfaceContainerHigh
                        else -> Color.Transparent
                    }
                )
                .nexusInteractiveRow(
                    rowKey = "cred-${credential.id}",
                    onClick = onEdit,
                    onSecondaryClick = { pos ->
                        menuOffsetPx = pos
                        menuOpen = true
                    }
                )
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 13.dp, height = 20.dp)
                        .onGloballyPositioned { coords ->
                            handleOriginInRoot = coords.positionInRoot()
                        }
                        .nexusHoverTracker(handleHover, PointerIcon.Hand)
                        .pointerInput(credential.id) {
                            detectDragGestures(
                                onDragStart = { localOffset ->
                                    dragDropState.startCredentialDrag(credential, handleOriginInRoot + localOffset)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragDropState.updateDragByDelta(dragAmount)
                                },
                                onDragEnd = {
                                    dragDropState.finishCredentialDrag(onReorderRelativeTo)
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
                        contentDescription = "Drag ${credential.name}",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (isHovered || handleHover.value.isHovered) 0.72f else 0.22f
                        )
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    text = credential.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (isHovered || menuOpen) {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = strings.actionEditCredential,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (isReorderTarget) {
            Box(
                modifier = Modifier
                    .align(if (reorderPlaceAfter) Alignment.BottomCenter else Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.tertiary)
            )
        }

        val density = LocalDensity.current
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = with(density) { DpOffset(menuOffsetPx.x.toDp(), 0.dp) },
            shape = NexusDropdownMenuShape
        ) {
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionEditCredential) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onEdit()
                }
            )
            if (credential.username.isNotBlank()) {
                NexusSpacedDropdownMenuItem(
                    text = { Text("Copy Username") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        clipboard.setText(AnnotatedString(credential.username))
                    }
                )
            }
            if (!credential.secretPassword.isNullOrEmpty()) {
                NexusSpacedDropdownMenuItem(
                    text = { Text("Copy Password") },
                    leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        clipboard.setText(AnnotatedString(credential.secretPassword.orEmpty()))
                    }
                )
            }
            NexusSpacedDropdownMenuItem(
                text = { Text("Duplicate") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDuplicate()
                }
            )
            NexusMenuDivider()
            NexusSpacedDropdownMenuItem(
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
private fun SmartFolderTreeRow(
    smartFolder: SmartFolder,
    matchedCount: Int,
    isExpanded: Boolean,
    strings: WorkspaceLocalizedStrings,
    onToggle: () -> Unit,
    onEdit: () -> Unit = {},
    onDuplicate: () -> Unit = {},
    onConnectAll: () -> Unit,
    onNewSmartFolder: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var menuOffsetPx by remember { mutableStateOf(Offset.Zero) }
    val hover = rememberDesktopHoverState()
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
                        isHovered || menuOpen -> MaterialTheme.colorScheme.surfaceContainerHigh
                        else -> Color.Transparent
                    }
                )
                .nexusInteractiveRow(
                    rowKey = "smart-${smartFolder.id}",
                    onClick = onToggle,
                    onDoubleClick = onEdit,
                    onSecondaryClick = { pos ->
                        menuOffsetPx = pos
                        menuOpen = true
                    }
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
                if (isHovered || menuOpen) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = strings.actionEditSmartFolder,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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

        val density = LocalDensity.current
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = with(density) { DpOffset(menuOffsetPx.x.toDp(), 0.dp) },
            shape = NexusDropdownMenuShape
        ) {
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionEditSmartFolder) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onEdit()
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionDuplicateSmartFolder) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDuplicate()
                }
            )
            NexusMenuDivider()
            NexusSpacedDropdownMenuItem(
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
                NexusSpacedDropdownMenuItem(
                    text = { Text(strings.actionConnectAllFolder) },
                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onConnectAll()
                    }
                )
            }
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionNewSmartFolder) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onNewSmartFolder()
                }
            )
            NexusMenuDivider()
            NexusSpacedDropdownMenuItem(
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
    isSelectedFolder: Boolean = false,
    isExpanded: Boolean,
    hasExpandableChildren: Boolean,
    explicitFolderCredentialName: String?,
    childCount: Int,
    rdpCount: Int = 0,
    activeSessionCountInSubtree: Int,
    dragDropState: SidebarTreeDragDropState,
    strings: WorkspaceLocalizedStrings,
    onSelectFolder: () -> Unit = {},
    onToggleExpand: () -> Unit,
    onExpandSubtree: () -> Unit = {},
    onCollapseSubtree: () -> Unit = {},
    onNewRdpInFolder: () -> Unit,
    onNewSshInFolder: () -> Unit,
    onNewSubfolder: () -> Unit,
    onRenameFolder: () -> Unit = {},
    onEditFolder: () -> Unit,
    onDuplicateFolder: () -> Unit,
    onRequestDeleteFolder: () -> Unit = {},
    onDeleteFolder: () -> Unit,
    onConnectAllRdpInFolder: () -> Unit = {},
    onConnectAllInFolder: () -> Unit,
    onDisconnectAllInFolder: () -> Unit,
    onOpenMoveFolderDialog: () -> Unit = {},
    onMoveFolderToParent: (String?) -> Unit = {},
    onMoveOrReorderFolder: (String, String, FolderDropPlacement) -> Unit = { _, _, _ -> },
    onRejectedDropIntoCredentials: () -> Unit = {},
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {}
) {
    var folderMenuOpen by remember { mutableStateOf(false) }
    var folderMenuOffsetPx by remember { mutableStateOf(Offset.Zero) }
    val hover = rememberDesktopHoverState()
    val handleHover = rememberDesktopHoverState()
    val isHovered = hover.value.isHovered
    val folderAccent = parseHexColorOrNull(folder.inheritable.displayColorAccentHex)
        ?: MaterialTheme.colorScheme.primary
    val isDropTargetHovered = dragDropState.isDragging && dragDropState.hoveredDropTargetKey == folder.id
    val dropPlacement = if (isDropTargetHovered) dragDropState.hoveredFolderDropPlacement else FolderDropPlacement.INSIDE
    val isInsideDropTarget = isDropTargetHovered && dropPlacement == FolderDropPlacement.INSIDE
    val isFolderBeingDragged = dragDropState.draggingFolder?.id == folder.id
    var rowOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var handleOriginInRoot by remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(folder.id) {
        onDispose {
            dragDropState.unregisterDropTarget(folder.id)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (depth > 0) ((depth - 1) * 14 + 8).dp else 0.dp)
            .onGloballyPositioned { coords ->
                rowOriginInRoot = coords.positionInRoot()
                dragDropState.registerDropTarget(folder.id, coords.boundsInRoot())
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (depth > 0) {
                Box(
                    modifier = Modifier
                        .width(10.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .fillMaxHeight()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 32.dp)
                        .nexusHoverTracker(hover, PointerIcon.Hand)
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(folder.id, folder.parentFolderId) {
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
                                            dragDropState.finishFolderDrag(
                                                onMoveFolderToParent = { _, targetParentId ->
                                                    onMoveFolderToParent(targetParentId)
                                                },
                                                onMoveOrReorderFolder = onMoveOrReorderFolder,
                                                onRejectedDropIntoCredentials = onRejectedDropIntoCredentials
                                            )
                                        }
                                        break
                                    }
                                    val delta = change.positionChange()
                                    if (!dragStarted) {
                                        accumulated += delta
                                        if (event.buttons.isPrimaryPressed && accumulated.getDistance() > slop) {
                                            dragStarted = true
                                            dragDropState.startFolderDrag(folder, rowOriginInRoot + change.position)
                                            change.consume()
                                        }
                                    } else {
                                        change.consume()
                                        dragDropState.updateDragAtRoot(rowOriginInRoot + change.position)
                                    }
                                }
                            }
                        }
                        .nexusInteractiveRow(
                            rowKey = "folder-${folder.id}",
                            onClick = {
                                onSelectFolder()
                                if (hasExpandableChildren) {
                                    onToggleExpand()
                                }
                            },
                            onDoubleClick = {
                                onSelectFolder()
                                if (hasExpandableChildren) {
                                    onToggleExpand()
                                }
                            },
                            onSecondaryClick = { pos ->
                                onSelectFolder()
                                folderMenuOffsetPx = pos
                                folderMenuOpen = true
                            }
                        ),
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        isFolderBeingDragged -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        isInsideDropTarget -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                        isSelectedFolder -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.68f)
                        isHovered || folderMenuOpen -> lerp(MaterialTheme.colorScheme.surfaceContainerHigh, folderAccent, 0.10f)
                        else -> Color.Transparent
                    },
                    border = if (isInsideDropTarget) {
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 14.dp, height = 22.dp)
                                    .onGloballyPositioned { coords ->
                                        handleOriginInRoot = coords.positionInRoot()
                                    }
                                    .nexusHoverTracker(handleHover, PointerIcon.Hand)
                                    .pointerInput(folder.id, folder.parentFolderId) {
                                        detectDragGestures(
                                            onDragStart = { localOffset ->
                                                dragDropState.startFolderDrag(folder, handleOriginInRoot + localOffset)
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragDropState.updateDragByDelta(dragAmount)
                                            },
                                            onDragEnd = {
                                                dragDropState.finishFolderDrag(
                                                    onMoveFolderToParent = { _, targetParentId ->
                                                        onMoveFolderToParent(targetParentId)
                                                    },
                                                    onMoveOrReorderFolder = onMoveOrReorderFolder,
                                                    onRejectedDropIntoCredentials = onRejectedDropIntoCredentials
                                                )
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
                                    contentDescription = "Drag folder ${folder.name}",
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = if (isHovered || isSelectedFolder || handleHover.value.isHovered) 0.75f else 0.22f
                                    )
                                )
                            }

                            if (hasExpandableChildren) {
                                IconButton(
                                    onClick = onToggleExpand,
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isExpanded) {
                                            Icons.Outlined.KeyboardArrowDown
                                        } else {
                                            Icons.Outlined.ChevronRight
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.width(20.dp))
                            }

                            Icon(
                                imageVector = if (isExpanded && hasExpandableChildren) {
                                    Icons.Filled.FilledFolderOpenIcon
                                } else {
                                    Icons.Filled.FilledFolderIcon
                                },
                                contentDescription = null,
                                tint = folderAccent,
                                modifier = Modifier.size(17.dp)
                            )

                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (explicitFolderCredentialName != null) {
                                Spacer(modifier = Modifier.width(5.dp))
                                Icon(
                                    imageVector = Icons.Outlined.Key,
                                    contentDescription = explicitFolderCredentialName,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (activeSessionCountInSubtree > 0) {
                                Surface(
                                    shape = RoundedCornerShape(99.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
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
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.PowerSettingsNew,
                                        contentDescription = "${strings.actionDisconnectAllFolder}: ${folder.name}",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            } else if (childCount > 0) {
                                Text(
                                    text = childCount.toString(),
                                    style = NexusTheme.monospace.keycapShortcut,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }

                            // Direct `+` icon button to immediately create an RDP connection inside this folder
                            IconButton(
                                onClick = onNewRdpInFolder,
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = "New RDP Connection in ${folder.name}",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = if (isHovered) 1f else 0.72f)
                                )
                            }

                            if (isHovered || isSelectedFolder || folderMenuOpen) {
                                IconButton(
                                    onClick = {
                                        onSelectFolder()
                                        folderMenuOffsetPx = Offset.Zero
                                        folderMenuOpen = true
                                    },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.MoreVert,
                                        contentDescription = "Folder Actions",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                if (isDropTargetHovered && dropPlacement != FolderDropPlacement.INSIDE) {
                    Box(
                        modifier = Modifier
                            .align(
                                if (dropPlacement == FolderDropPlacement.AFTER) {
                                    Alignment.BottomCenter
                                } else {
                                    Alignment.TopCenter
                                }
                            )
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        val density = LocalDensity.current
        DropdownMenu(
            expanded = folderMenuOpen,
            onDismissRequest = { folderMenuOpen = false },
            offset = with(density) { DpOffset(folderMenuOffsetPx.x.toDp(), 0.dp) },
            shape = NexusDropdownMenuShape
        ) {
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionEditFolderCredentials) },
                leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onEditFolder()
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionRenameFolder) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onRenameFolder()
                }
            )
            NexusMenuDivider()
            NexusSpacedDropdownMenuItem(
                text = {
                    Text(
                        if (rdpCount > 0) {
                            "${strings.actionStartAllRdpFolder} ($rdpCount)"
                        } else {
                            strings.actionStartAllRdpFolder
                        }
                    )
                },
                enabled = rdpCount > 0,
                leadingIcon = {
                    Icon(
                        protocolIcon(SessionProtocolKind.RDP),
                        contentDescription = null
                    )
                },
                onClick = {
                    folderMenuOpen = false
                    onConnectAllRdpInFolder()
                }
            )
            if (childCount > 0) {
                NexusSpacedDropdownMenuItem(
                    text = { Text("${strings.actionConnectAllFolder} ($childCount)") },
                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    onClick = {
                        folderMenuOpen = false
                        onConnectAllInFolder()
                    }
                )
            }
            if (activeSessionCountInSubtree > 0 || childCount > 0) {
                NexusSpacedDropdownMenuItem(
                    text = {
                        Text(
                            text = if (activeSessionCountInSubtree > 0) {
                                "${strings.actionDisconnectAllFolder} ($activeSessionCountInSubtree)"
                            } else {
                                strings.actionDisconnectAllFolder
                            }
                        )
                    },
                    enabled = activeSessionCountInSubtree > 0,
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
            NexusMenuDivider()
            NexusSpacedDropdownMenuItem(
                text = { Text("New RDP Connection") },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onNewRdpInFolder()
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text("New SSH Connection") },
                leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onNewSshInFolder()
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text("New Subfolder") },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onNewSubfolder()
                }
            )
            NexusMenuDivider()
            NexusSpacedDropdownMenuItem(
                text = { Text("Move Folder to…") },
                leadingIcon = { Icon(Icons.Outlined.DriveFileMove, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onOpenMoveFolderDialog()
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text("Move Up") },
                leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onMoveUp()
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text("Move Down") },
                leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onMoveDown()
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text("Duplicate Folder") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    folderMenuOpen = false
                    onDuplicateFolder()
                }
            )
            if (hasExpandableChildren) {
                NexusSpacedDropdownMenuItem(
                    text = { Text(if (isExpanded) "Collapse Subfolders" else "Expand Subfolders") },
                    leadingIcon = {
                        Icon(
                            if (isExpanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        folderMenuOpen = false
                        if (isExpanded) onCollapseSubtree() else onExpandSubtree()
                    }
                )
            }
            NexusMenuDivider()
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionDeleteItem, color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    folderMenuOpen = false
                    onRequestDeleteFolder()
                }
            )
        }
    }
}

private fun VaultProtocolType.shortSidebarBadge(): String = when (this) {
    VaultProtocolType.RDP -> "RDP"
    VaultProtocolType.SSH -> "SSH"
    VaultProtocolType.TELNET -> "TEL"
    VaultProtocolType.SERIAL -> "UART"
    VaultProtocolType.SFTP -> "SFTP"
    VaultProtocolType.FTP -> "FTP"
    VaultProtocolType.FTPS -> "FTPS"
    VaultProtocolType.SCP -> "SCP"
    VaultProtocolType.VNC -> "VNC"
    VaultProtocolType.APPLE_SCREEN_SHARING -> "ARD"
    VaultProtocolType.WEB_PAGE -> "WEB"
    VaultProtocolType.VMWARE_VSPHERE -> "ESXi"
    VaultProtocolType.PROXMOX_VE -> "PVE"
    VaultProtocolType.HYPER_V -> "HV"
    VaultProtocolType.AWS_EC2 -> "EC2"
    VaultProtocolType.AZURE_VM -> "AZ"
    VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> "RDP"
    VaultProtocolType.UTILITY_PING -> "PING"
    VaultProtocolType.UTILITY_TRACEROUTE -> "MTR"
    VaultProtocolType.UTILITY_PORT_SCANNER -> "SCAN"
    VaultProtocolType.UTILITY_WAKE_ON_LAN -> "WOL"
    VaultProtocolType.KUBERNETES_POD -> "K8S"
    VaultProtocolType.DOCKER_CONTAINER -> "OCI"
}

data class SidebarProtocolConnectionGroup(
    val badge: String,
    val connections: List<VaultConnection>,
    val showsHeader: Boolean
)

private val CANONICAL_SIDEBAR_PROTOCOL_ORDER = listOf(
    "RDP", "SSH", "VNC", "ARD", "SFTP", "SCP", "FTP", "FTPS",
    "TEL", "UART", "WEB", "K8S", "OCI", "PVE", "ESXi", "HV",
    "EC2", "AZ", "PING", "MTR", "SCAN", "WOL"
)

fun groupConnectionsIntelligently(
    connections: List<VaultConnection>,
    preserveInputOrderWithinGroups: Boolean = false
): List<SidebarProtocolConnectionGroup> {
    if (connections.isEmpty()) return emptyList()
    val buckets = linkedMapOf<String, MutableList<VaultConnection>>()
    for (conn in connections) {
        val badge = conn.protocol.shortSidebarBadge()
        buckets.getOrPut(badge) { mutableListOf() }.add(conn)
    }
    val sortedKeys = buckets.keys.sortedWith(
        compareBy<String> { key ->
            val idx = CANONICAL_SIDEBAR_PROTOCOL_ORDER.indexOf(key)
            if (idx >= 0) idx else CANONICAL_SIDEBAR_PROTOCOL_ORDER.size
        }.thenBy { it }
    )
    val showsHeader = sortedKeys.size > 1
    return sortedKeys.map { badge ->
        val raw = buckets[badge].orEmpty()
        val ordered = if (preserveInputOrderWithinGroups) {
            raw.toList()
        } else {
            raw.sortedWith(compareBy<VaultConnection> { it.sortOrder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        SidebarProtocolConnectionGroup(
            badge = badge,
            connections = ordered,
            showsHeader = showsHeader
        )
    }
}

@Composable
private fun SidebarProtocolGroupHeaderRow(
    badge: String,
    count: Int,
    indentLevel: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (indentLevel > 0) ((indentLevel - 1) * 14 + 20).dp else 8.dp,
                end = 8.dp,
                top = 4.dp,
                bottom = 1.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = badge,
            style = NexusTheme.monospace.keycapShortcut.copy(
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(0.8.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        )
        Text(
            text = count.toString(),
            style = NexusTheme.monospace.keycapShortcut.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        )
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
    onReorderRelativeTo: (String, Boolean) -> Unit = { _, _ -> },
    onRejectedDropIntoCredentials: () -> Unit = {},
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    activeSessionCount: Int = 0,
    enableDragToFolder: Boolean = false,
    dragDropState: SidebarTreeDragDropState? = null,
    indentLevel: Int = 1
) {
    val clipboard = LocalClipboardManager.current
    var contextMenuOpen by remember { mutableStateOf(false) }
    var contextMenuOffsetPx by remember { mutableStateOf(Offset.Zero) }
    val hover = rememberDesktopHoverState()
    val handleHover = rememberDesktopHoverState()
    val isHovered = hover.value.isHovered
    val uiProto = NexusWorkspaceViewModel.mapProtocolToUiKind(connection.protocol)
    val customAccent = parseHexColorOrNull(connection.inheritable.displayColorAccentHex)
    val effectiveIconTint = customAccent ?: protocolAccentColor(uiProto)
    val isBeingDragged = dragDropState?.draggingConnection?.id == connection.id
    val isReorderTarget = enableDragToFolder && dragDropState?.hoveredConnectionTargetId == connection.id
    val reorderPlaceAfter = dragDropState?.hoveredConnectionPlaceAfter == true

    var rowOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var handleOriginInRoot by remember { mutableStateOf(Offset.Zero) }

    if (enableDragToFolder && dragDropState != null) {
        DisposableEffect(connection.id) {
            onDispose {
                dragDropState.unregisterConnectionTarget(connection.id)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indentLevel > 0) ((indentLevel - 1) * 14 + 8).dp else 0.dp)
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
                        .width(10.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .fillMaxHeight()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                }
                            )
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 30.dp)
                        .onGloballyPositioned { coords ->
                            rowOriginInRoot = coords.positionInRoot()
                            if (enableDragToFolder && dragDropState != null) {
                                dragDropState.registerConnectionTarget(connection.id, coords.boundsInRoot())
                            }
                        }
                        .nexusHoverTracker(hover, PointerIcon.Hand)
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            when {
                                isBeingDragged -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
                                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                                isHovered || contextMenuOpen -> MaterialTheme.colorScheme.surfaceContainerHigh
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
                                            dragDropState.finishDrag(
                                                onMoveConnectionToFolder = { _, targetFolderId ->
                                                    onMoveToFolder(targetFolderId)
                                                },
                                                onReorderConnectionRelativeTo = { _, targetConnId, placeAfter ->
                                                    onReorderRelativeTo(targetConnId, placeAfter)
                                                },
                                                onRejectedDropIntoCredentials = onRejectedDropIntoCredentials
                                            )
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
                        .nexusInteractiveRow(
                            rowKey = "conn-${connection.id}",
                            onClick = {
                                onSelect()
                            },
                            onDoubleClick = {
                                onSelect()
                                onLaunch(QuickLaunchPlacement.ACTIVE_PANE_TAB)
                            },
                            onSecondaryClick = { pos ->
                                onSelect()
                                contextMenuOffsetPx = pos
                                contextMenuOpen = true
                            },
                            onTertiaryMiddleClick = {
                                onSelect()
                                onLaunch(QuickLaunchPlacement.SPLIT_RIGHT_PANE)
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (enableDragToFolder && dragDropState != null) {
                        Box(
                            modifier = Modifier
                                .size(width = 14.dp, height = 22.dp)
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
                                            dragDropState.finishDrag(
                                                onMoveConnectionToFolder = { _, targetFolderId ->
                                                    onMoveToFolder(targetFolderId)
                                                },
                                                onReorderConnectionRelativeTo = { _, targetConnId, placeAfter ->
                                                    onReorderRelativeTo(targetConnId, placeAfter)
                                                },
                                                onRejectedDropIntoCredentials = onRejectedDropIntoCredentials
                                            )
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
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = if (isHovered || isSelected || handleHover.value.isHovered) 0.75f else 0.22f
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(3.dp))
                    }

                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = connection.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (connection.favorite) {
                            Icon(
                                imageVector = Icons.Outlined.Star,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    if (isHovered) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = effectiveIconTint.copy(alpha = 0.14f),
                            border = BorderStroke(0.6.dp, effectiveIconTint.copy(alpha = 0.32f))
                        ) {
                            Text(
                                text = connection.protocol.shortSidebarBadge(),
                                style = NexusTheme.monospace.keycapShortcut.copy(
                                    fontSize = 8.5.sp,
                                    lineHeight = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }

                    if (activeSessionCount > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }

                    // Quick inline Edit + MoreVert buttons on hover/selection
                    if (isHovered || isSelected || contextMenuOpen) {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = strings.actionEditConnection,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                onSelect()
                                contextMenuOffsetPx = Offset.Zero
                                contextMenuOpen = true
                            },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "Connection Actions",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (isReorderTarget) {
                    Box(
                        modifier = Modifier
                            .align(if (reorderPlaceAfter) Alignment.BottomCenter else Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        val density = LocalDensity.current
        DropdownMenu(
            expanded = contextMenuOpen,
            onDismissRequest = { contextMenuOpen = false },
            offset = with(density) { DpOffset(contextMenuOffsetPx.x.toDp(), 0.dp) },
            shape = NexusDropdownMenuShape
        ) {
            NexusSpacedDropdownMenuItem(
                text = { Text("Connect") },
                leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onSelect()
                    onLaunch(QuickLaunchPlacement.ACTIVE_PANE_TAB)
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionEditConnection) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onEdit()
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionConnectWithOptions) },
                leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onConnectWithOptions()
                }
            )
            if (activeSessionCount > 0) {
                NexusSpacedDropdownMenuItem(
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
            NexusMenuDivider()
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.splitRight) },
                leadingIcon = { Icon(Icons.Outlined.VerticalSplit, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onLaunch(QuickLaunchPlacement.SPLIT_RIGHT_PANE)
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.splitDown) },
                leadingIcon = { Icon(Icons.Outlined.HorizontalSplit, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onLaunchSplitDown()
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.detachNewWindow) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onLaunch(QuickLaunchPlacement.DETACHED_NEW_WINDOW)
                }
            )
            NexusMenuDivider()
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionMoveToFolder) },
                leadingIcon = { Icon(Icons.Outlined.DriveFileMove, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onOpenMoveDialog()
                }
            )
            if (enableDragToFolder) {
                NexusSpacedDropdownMenuItem(
                    text = { Text("Move Up") },
                    leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                    onClick = {
                        contextMenuOpen = false
                        onMoveUp()
                    }
                )
                NexusSpacedDropdownMenuItem(
                    text = { Text("Move Down") },
                    leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                    onClick = {
                        contextMenuOpen = false
                        onMoveDown()
                    }
                )
            }
            NexusSpacedDropdownMenuItem(
                text = { Text("Copy Host") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    clipboard.setText(AnnotatedString(connection.host))
                }
            )
            NexusSpacedDropdownMenuItem(
                text = { Text(strings.actionDuplicateConnection) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onDuplicate()
                }
            )
            NexusSpacedDropdownMenuItem(
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
            NexusMenuDivider()
            NexusSpacedDropdownMenuItem(
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
                                if (rdpSettings.redirectedDriveSafUris.isNotEmpty()) {
                                    append(" • ${rdpSettings.redirectedDriveSafUris.size} SAF Drive(s)")
                                } else if (rdpSettings.redirectDrives) {
                                    append(" • Drives")
                                }
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
    biometricHardwareAvailable: Boolean = false,
    biometricUnlockEnabled: Boolean = false,
    hasSavedPassphrase: Boolean = false,
    idleAutoLockMinutes: Int = 0,
    onDismiss: () -> Unit,
    onQuickLock: (() -> Unit)? = null,
    onToggleBiometricUnlock: ((Boolean) -> Unit)? = null,
    onChangeIdleAutoLockMinutes: ((Int) -> Unit)? = null,
    onConfirmLock: (String, Boolean) -> Unit
) {
    if (!visible) return
    var lockPassphrase by remember { mutableStateOf("") }
    var enrollBiometricChecked by remember(biometricUnlockEnabled, biometricHardwareAvailable) {
        mutableStateOf(biometricUnlockEnabled || biometricHardwareAvailable)
    }
    val timeoutOptions = remember {
        listOf(0 to "Off", 1 to "1m", 5 to "5m", 15 to "15m", 30 to "30m")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(strings.actionLockVault) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hasSavedPassphrase && onQuickLock != null) {
                    FilledTonalButton(
                        onClick = {
                            lockPassphrase = ""
                            onQuickLock()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Quick Lock Now")
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                }

                OutlinedTextField(
                    value = lockPassphrase,
                    onValueChange = { lockPassphrase = it },
                    label = { Text(strings.vaultPassphraseLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Fingerprint,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Biometric / Passkey Unlock",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = if (biometricHardwareAvailable || biometricUnlockEnabled) {
                                        "Android Keystore AES-256-GCM + BiometricPrompt"
                                    } else {
                                        "Configure screen lock / fingerprint on device to enable"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = enrollBiometricChecked,
                            enabled = biometricHardwareAvailable || biometricUnlockEnabled,
                            onCheckedChange = { next ->
                                enrollBiometricChecked = next
                                if (!next || hasSavedPassphrase) {
                                    onToggleBiometricUnlock?.invoke(next)
                                }
                            }
                        )
                    }
                }

                if (onChangeIdleAutoLockMinutes != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Timer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Idle Auto-Lock Timeout",
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                timeoutOptions.forEach { (mins, label) ->
                                    FilterChip(
                                        selected = idleAutoLockMinutes == mins,
                                        onClick = { onChangeIdleAutoLockMinutes(mins) },
                                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = lockPassphrase.isNotEmpty(),
                onClick = {
                    val entered = lockPassphrase
                    lockPassphrase = ""
                    onConfirmLock(entered, enrollBiometricChecked && (biometricHardwareAvailable || biometricUnlockEnabled))
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
    biometricUnlockAvailable: Boolean = false,
    onBiometricUnlock: () -> Unit = {},
    onRequestResetDatabase: (() -> Unit)? = null,
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

                    if (biometricUnlockAvailable) {
                        FilledTonalButton(
                            onClick = onBiometricUnlock,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Unlock with Biometrics / Passkey")
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

                    if (onRequestResetDatabase != null && !hasPreImportBackup) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        TextButton(
                            onClick = onRequestResetDatabase,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.actionResetDatabase)
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
private fun LanguageSelectionDropdownMenu(
    expanded: Boolean,
    currentLocale: SupportedNexusLocale,
    strings: WorkspaceLocalizedStrings,
    onSelectLocale: (SupportedNexusLocale) -> Unit,
    onDismiss: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 276.dp, max = 336.dp),
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = strings.dialogLanguageTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(bottom = 5.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )

        SupportedNexusLocale.entries.forEach { locale ->
            val isSelected = locale == currentLocale
            val localizedLabel = strings.languageLabels[locale] ?: locale.nativeDisplayName

            DropdownMenuItem(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)
                        } else {
                            Color.Transparent
                        }
                    ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                leadingIcon = {
                    NexusLanguageFlagBadge(
                        languageCode = locale.bcp47Tag,
                        width = 28.dp,
                        height = 19.dp,
                        cornerRadius = 4.dp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.padding(start = 6.dp, end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = locale.nativeDisplayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (localizedLabel != locale.nativeDisplayName) {
                                "$localizedLabel • ${locale.regionSubtitle}"
                            } else {
                                locale.regionSubtitle
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                trailingIcon = {
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
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                onClick = { onSelectLocale(locale) }
            )
        }
    }
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
                                    imageVector = Icons.Filled.FilledFolderIcon,
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
private fun MoveFolderToParentDialog(
    targetFolder: VaultFolder?,
    folders: List<VaultFolder>,
    canMoveFolderToParent: (String, String?) -> Boolean,
    strings: WorkspaceLocalizedStrings,
    onMoveToParent: (String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val folder = targetFolder ?: return
    val validCandidates = remember(folder, folders) {
        folders
            .filter { candidate ->
                candidate.id != folder.id &&
                    (candidate.id == folder.parentFolderId || canMoveFolderToParent(folder.id, candidate.id))
            }
            .sortedWith(
                compareBy<VaultFolder> { it.sortOrder }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
    }

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
                text = "Move Folder",
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
                    text = folder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(4.dp))

                val isAtRoot = folder.parentFolderId == null
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            if (!isAtRoot) {
                                onMoveToParent(folder.id, null)
                            }
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

                validCandidates.forEach { candidate ->
                    val isCurrent = folder.parentFolderId == candidate.id
                    val accent = parseHexColorOrNull(candidate.inheritable.displayColorAccentHex)
                        ?: MaterialTheme.colorScheme.primary
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                if (!isCurrent) {
                                    onMoveToParent(folder.id, candidate.id)
                                }
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
                                    imageVector = Icons.Filled.FilledFolderIcon,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = candidate.name,
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
private fun RenameFolderDialog(
    targetFolder: VaultFolder?,
    strings: WorkspaceLocalizedStrings,
    onRename: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val folder = targetFolder ?: return
    var folderName by remember(folder.id, folder.name) { mutableStateOf(folder.name) }
    val trimmedName = folderName.trim()
    val isReservedRootCredentialsName = folder.parentFolderId == null &&
        VaultHierarchySanitizer.isReservedCredentialsFolderName(trimmedName)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        },
        title = {
            Text(
                text = strings.dialogRenameFolderTitle,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(strings.dialogFieldName) },
                    isError = isReservedRootCredentialsName,
                    supportingText = if (isReservedRootCredentialsName) {
                        { Text(strings.credentialsSystemFolderReservedError) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (trimmedName.isNotEmpty() && !isReservedRootCredentialsName) {
                        onRename(folder.id, trimmedName)
                    }
                },
                enabled = trimmedName.isNotEmpty() && !isReservedRootCredentialsName
            ) {
                Text(strings.dialogActionSave)
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
private fun DeleteFolderConfirmationDialog(
    targetFolder: VaultFolder?,
    folders: List<VaultFolder>,
    connections: List<VaultConnection>,
    strings: WorkspaceLocalizedStrings,
    onDeleteWithContents: (String) -> Unit,
    onDeleteKeepContents: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val folder = targetFolder ?: return
    val (subFolderCount, connectionCount) = remember(folder.id, folders, connections) {
        val childrenByParent = folders.groupBy { it.parentFolderId }
        val subtreeIds = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(folder.id)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (subtreeIds.add(current)) {
                childrenByParent[current].orEmpty().forEach { child -> queue.add(child.id) }
            }
        }
        val nestedFolders = (subtreeIds.size - 1).coerceAtLeast(0)
        val nestedConnections = connections.count { it.parentFolderId in subtreeIds }
        nestedFolders to nestedConnections
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(26.dp)
            )
        },
        title = {
            Text(
                text = strings.dialogDeleteFolderTitle,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = buildString {
                        append("\"${folder.name}\"")
                        if (subFolderCount > 0 || connectionCount > 0) {
                            append(" ($connectionCount connections")
                            if (subFolderCount > 0) append(", $subFolderCount subfolders")
                            append(")")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = { onDeleteWithContents(folder.id) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.actionDeleteFolderAndContents)
                }

                FilledTonalButton(
                    onClick = { onDeleteKeepContents(folder.id) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.DriveFileMove, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.actionDeleteFolderKeepContents)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
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
                            text = strings.autoUpdateAutoCheckToggle,
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
                                    uiState.updateAvailable -> String.format(
                                        strings.autoUpdateAvailableFormat,
                                        uiState.updateLatestTag
                                    ).trim()
                                    else -> String.format(
                                        strings.autoUpdateUpToDateFormat,
                                        uiState.updateCurrentVersion.ifBlank { "0.3.0-beta.6" }
                                    )
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
                                text = "Installed: v${uiState.updateCurrentVersion.ifBlank { "0.3.0-beta.6" }}  →  Latest: ${uiState.updateLatestTag.ifBlank { "current" }}$sizeMb",
                                style = NexusTheme.monospace.keycapShortcut,
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

@Composable
private fun RoyalTsImportPlacementDialog(
    pendingImport: PendingRoyalTsImportState?,
    strings: WorkspaceLocalizedStrings,
    onConfirmPlacement: (RoyalTsImportPlacementMode, String) -> Unit,
    onDismiss: () -> Unit
) {
    val state = pendingImport ?: return
    var selectedMode by remember(state.fileName) {
        mutableStateOf(RoyalTsImportPlacementMode.CREATE_NEW_FOLDER)
    }
    var customFolderName by remember(state.fileName, state.suggestedFolderName) {
        mutableStateOf(state.suggestedFolderName)
    }
    val trimmedFolderName = customFolderName.trim()
    val isInvalidNewFolderName = selectedMode == RoyalTsImportPlacementMode.CREATE_NEW_FOLDER &&
        (trimmedFolderName.isEmpty() || VaultHierarchySanitizer.isReservedCredentialsFolderName(trimmedFolderName))

    val options = remember(strings) {
        listOf(
            Triple(
                RoyalTsImportPlacementMode.CREATE_NEW_FOLDER,
                strings.royalImportModeNewFolder,
                Icons.Outlined.CreateNewFolder
            ),
            Triple(
                RoyalTsImportPlacementMode.PLACE_AT_ROOT,
                strings.royalImportModeRoot,
                Icons.Outlined.Folder
            ),
            Triple(
                RoyalTsImportPlacementMode.REPLACE_EXISTING,
                strings.royalImportModeReplaceAll,
                Icons.Outlined.Delete
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = {
            Icon(
                imageVector = Icons.Outlined.UploadFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = strings.dialogRoyalImportTitle,
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
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = state.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${state.importedDocument.connections.size} connections • " +
                                "${state.importedDocument.folders.size} folders • " +
                                "${state.importedDocument.credentials.size} credentials",
                            style = NexusTheme.monospace.keycapShortcut,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                options.forEach { (mode, label, icon) ->
                    val isSelected = selectedMode == mode
                    val isDestructive = mode == RoyalTsImportPlacementMode.REPLACE_EXISTING
                    val containerColor = when {
                        isSelected && isDestructive -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    }
                    val borderColor = when {
                        isSelected && isDestructive -> MaterialTheme.colorScheme.error
                        isSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { selectedMode = mode },
                        shape = RoundedCornerShape(16.dp),
                        color = containerColor,
                        border = BorderStroke(1.dp, borderColor)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedMode = mode }
                                )
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (isDestructive) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(19.dp)
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isDestructive && isSelected) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (mode == RoyalTsImportPlacementMode.CREATE_NEW_FOLDER && isSelected) {
                                val reservedError = VaultHierarchySanitizer.isReservedCredentialsFolderName(trimmedFolderName)
                                OutlinedTextField(
                                    value = customFolderName,
                                    onValueChange = { customFolderName = it },
                                    label = { Text(strings.royalImportFolderNameLabel) },
                                    isError = isInvalidNewFolderName,
                                    supportingText = if (reservedError) {
                                        { Text(strings.credentialsSystemFolderReservedError) }
                                    } else {
                                        null
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val isDestructive = selectedMode == RoyalTsImportPlacementMode.REPLACE_EXISTING
            Button(
                onClick = { onConfirmPlacement(selectedMode, trimmedFolderName) },
                enabled = !isInvalidNewFolderName,
                colors = if (isDestructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(strings.actionImportRtsz)
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
private fun ResetDatabaseConfirmationDialog(
    visible: Boolean,
    canExportBackup: Boolean,
    strings: WorkspaceLocalizedStrings,
    onExportBackupFirst: () -> Unit,
    onConfirmReset: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    var confirmedChecked by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(30.dp)
            )
        },
        title = {
            Text(
                text = strings.dialogResetDatabaseTitle,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = strings.dialogResetDatabaseMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                if (canExportBackup) {
                    OutlinedButton(
                        onClick = onExportBackupFirst,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.actionExportNxts)
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { confirmedChecked = !confirmedChecked },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = confirmedChecked,
                            onCheckedChange = { confirmedChecked = it }
                        )
                        Text(
                            text = strings.resetDatabaseWarningBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmReset,
                enabled = confirmedChecked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.actionConfirmResetDatabase)
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
private fun NexusErrorDialog(
    error: NexusAppError?,
    strings: WorkspaceLocalizedStrings,
    onEditRelatedConnection: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val activeError = error ?: return
    val clipboard = LocalClipboardManager.current
    var detailsExpanded by remember(activeError.id) { mutableStateOf(false) }

    val categoryIcon = when (activeError.category) {
        NexusErrorCategory.AUTHENTICATION_FAILED -> Icons.Outlined.VpnKey
        NexusErrorCategory.TLS_CERTIFICATE -> Icons.Outlined.Security
        NexusErrorCategory.CLOUD_SYNC -> Icons.Outlined.CloudSync
        NexusErrorCategory.VAULT_CRYPTO_OR_CORRUPTION -> Icons.Outlined.Lock
        NexusErrorCategory.VALIDATION -> Icons.Outlined.WarningAmber
        else -> Icons.Outlined.ErrorOutline
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = {
            Icon(
                imageVector = categoryIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = activeError.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                ) {
                    Text(
                        text = activeError.category.name.replace('_', ' '),
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = activeError.userMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (activeError.remediationHint.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = activeError.remediationHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (activeError.technicalDetails.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { detailsExpanded = !detailsExpanded },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Technical Details",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Icon(
                                    imageVector = if (detailsExpanded) {
                                        Icons.Outlined.ExpandLess
                                    } else {
                                        Icons.Outlined.ExpandMore
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (detailsExpanded) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = activeError.technicalDetails,
                                    style = NexusTheme.monospace.logEntry,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(activeError.formatDiagnosticReport()))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.actionCopyDiagnosticReport,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (onEditRelatedConnection != null) {
                        FilledTonalButton(
                            onClick = onEditRelatedConnection,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.actionEditConnection,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(strings.dialogActionCancel)
            }
        }
    )
}



