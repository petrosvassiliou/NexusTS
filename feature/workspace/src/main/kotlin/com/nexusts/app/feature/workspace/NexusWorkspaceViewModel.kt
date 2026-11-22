package com.nexusts.app.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexusts.app.core.ui.input.NexusShortcutAction
import com.nexusts.app.core.ui.input.NexusShortcutEngine
import com.nexusts.app.core.ui.input.RawHardwareKeyPacket
import com.nexusts.app.core.ui.workspace.QuickConnectCandidate
import com.nexusts.app.core.ui.workspace.QuickLaunchPlacement
import com.nexusts.app.core.ui.workspace.SessionLifecycleStatus
import com.nexusts.app.core.ui.workspace.SessionProtocolKind
import com.nexusts.app.core.ui.workspace.SplitOrientation
import com.nexusts.app.core.ui.workspace.WorkspaceSessionTab
import com.nexusts.app.core.ui.workspace.WorkspaceTilingController
import com.nexusts.app.core.vault.automation.AutomationTaskEngine
import com.nexusts.app.core.vault.credentials.EnterpriseCredentialHub
import com.nexusts.app.core.vault.credentials.ResolvedSessionSecret
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.error.NexusAppError
import com.nexusts.app.core.vault.error.NexusErrorCategory
import com.nexusts.app.core.vault.error.NexusErrorClassifier
import com.nexusts.app.core.vault.error.VaultHierarchySanitizer
import com.nexusts.app.core.vault.exporter.VaultDocumentExporter
import com.nexusts.app.core.vault.exporter.VaultExportFormat
import com.nexusts.app.core.vault.gateway.ActiveTunnelLease
import com.nexusts.app.core.vault.gateway.GatewayHealthReport
import com.nexusts.app.core.vault.gateway.SecureGatewayTunnelManager
import com.nexusts.app.core.vault.importer.DetectedExternalVaultFormat
import com.nexusts.app.core.vault.importer.EnterpriseVaultFormatImporter
import com.nexusts.app.core.vault.importer.RoyalTsImportMerger
import com.nexusts.app.core.vault.importer.RoyalTsImportPlacementMode
import com.nexusts.app.core.vault.importer.RtszDocumentImporter
import com.nexusts.app.core.vault.inheritance.EffectiveConnectionProfile
import com.nexusts.app.core.vault.inheritance.PropertyInheritanceResolver
import com.nexusts.app.core.vault.model.CommandTask
import com.nexusts.app.core.vault.model.ConnectionTemplate
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.CustomPropertyEntry
import com.nexusts.app.core.vault.model.DynamicFolder
import com.nexusts.app.core.vault.model.DynamicScriptInterpreter
import com.nexusts.app.core.vault.model.GatewayTransportKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.KeySequenceTask
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.PortForwardKind
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.RdpAudioRedirectionMode
import com.nexusts.app.core.vault.model.SecureGatewayConfig
import com.nexusts.app.core.vault.model.SmartFolder
import com.nexusts.app.core.vault.model.SmartFolderFilter
import com.nexusts.app.core.vault.model.SshPortForwardRule
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.core.vault.model.VaultTodoItem
import com.nexusts.app.protocols.rdp.bridge.RdpManagedSession
import com.nexusts.app.protocols.ssh.bridge.SshNativeBridge
import com.nexusts.app.protocols.ssh.bridge.SshTerminalSessionController
import com.nexusts.app.protocols.ssh.ui.TransferCommanderSessionController
import com.nexusts.app.protocols.vnc.bridge.VncRfbSessionClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Arrays
import java.util.UUID
import javax.inject.Inject

enum class SidebarQuickFilter {
    ALL,
    FAVORITES,
    RECENTS
}

enum class FolderDropPlacement {
    BEFORE,
    INSIDE,
    AFTER
}

data class PendingRoyalTsImportState(
    val fileName: String,
    val importedDocument: NxtsVaultDocument,
    val suggestedFolderName: String
)

data class WorkspaceUiState(
    val activeRailId: String = "connections",
    val isSidebarVisible: Boolean = true,
    val isInspectorVisible: Boolean = false,
    val isBottomDrawerVisible: Boolean = false,
    val isQuickConnectVisible: Boolean = false,
    val isNewConnectionDialogVisible: Boolean = false,
    val editingConnection: VaultConnection? = null,
    val newConnectionDefaultFolderId: String? = null,
    val newConnectionDefaultProtocol: VaultProtocolType = VaultProtocolType.RDP,
    val isFolderDialogVisible: Boolean = false,
    val editingFolder: VaultFolder? = null,
    val newFolderDefaultParentId: String? = null,
    val connectWithOptionsTarget: VaultConnection? = null,
    val isNewSmartFolderDialogVisible: Boolean = false,
    val editingSmartFolder: SmartFolder? = null,
    val isNewDynamicFolderDialogVisible: Boolean = false,
    val editingDynamicFolder: DynamicFolder? = null,
    val isCredentialDialogVisible: Boolean = false,
    val editingCredential: VaultCredential? = null,
    val isNewTemplateDialogVisible: Boolean = false,
    val isLockVaultDialogVisible: Boolean = false,
    val isResetDatabaseDialogVisible: Boolean = false,
    val pendingRoyalTsImport: PendingRoyalTsImportState? = null,
    val pendingEncryptedImportFileName: String? = null,
    val pendingEncryptedImportFormat: DetectedExternalVaultFormat? = null,
    val pendingEncryptedImportError: String? = null,
    val importErrorDialogMessage: String? = null,
    val activeAppError: NexusAppError? = null,
    val userSnackbarMessage: String? = null,
    val sidebarFilterQuery: String = "",
    val sidebarQuickFilter: SidebarQuickFilter = SidebarQuickFilter.ALL,
    val useSharedTeamDriveZone: Boolean = false,
    val driveSignedInEmail: String = "",
    val driveLinkedSafLabel: String = "",
    val driveSyncStatusBanner: String = "",
    val driveIsSyncing: Boolean = false,
    val driveAutoSyncOnSave: Boolean = true,
    val driveRememberVaultPassphrase: Boolean = false,
    val githubLatestReleaseBanner: String = "",
    val githubLatestReleaseUrl: String = "",
    val isLanguageMenuExpanded: Boolean = false,
    val movingConnectionTarget: VaultConnection? = null,
    val movingFolderTarget: VaultFolder? = null,
    val renamingFolderTarget: VaultFolder? = null,
    val deletingFolderTarget: VaultFolder? = null,
    val isAutoUpdateDialogVisible: Boolean = false,
    val autoCheckUpdatesEnabled: Boolean = true,
    val isCheckingForUpdates: Boolean = false,
    val updateAvailable: Boolean = false,
    val updateLatestTag: String = "",
    val updateCurrentVersion: String = "",
    val updateReleaseTitle: String = "",
    val updateReleaseNotes: String = "",
    val updateSha256Hex: String = "",
    val updateApkSizeBytes: Long = 0L,
    val updatePublishedAtIso: String = "",
    val updateApkDownloadUrl: String = "",
    val updateReleaseHtmlUrl: String = "",
    val updateVerificationMessage: String = "",
    val remoteKeyCaptureEnabled: Boolean = false,
    val isFullScreenSessionMode: Boolean = false,
    val broadcastModeEnabled: Boolean = false,
    val isVaultLocked: Boolean = false,
    val biometricHardwareAvailable: Boolean = false,
    val biometricUnlockEnabled: Boolean = false,
    val hasSavedVaultLockPassphrase: Boolean = false,
    val idleAutoLockMinutes: Int = 0,
    val vaultUnlockError: String? = null,
    val hasPreImportBackup: Boolean = false,
    val pendingImportFileName: String? = null,
    val isStarterUninitialized: Boolean = true,
    val vaultDocument: NxtsVaultDocument = NexusWorkspaceViewModel.createEnterpriseStarterVault(),
    val selectedConnectionId: String? = null,
    val selectedFolderId: String? = null,
    val selectedSmartFolderId: String? = null,
    val selectedDynamicFolderId: String? = null,
    val hydratedDynamicConnections: Map<String, List<VaultConnection>> = emptyMap(),
    val gatewayHealthReports: Map<String, GatewayHealthReport> = emptyMap(),
    val activeGatewayLeases: List<ActiveTunnelLease> = emptyList(),
    val auditLogs: List<String> = listOf(
        "[NexusTS] Argon2id + AES-256-GCM Zero-Knowledge Engine initialized.",
        "[WindowManager] Native Googlebook OS Freeform & Multi-Monitor controller ready."
    )
)

@HiltViewModel
class NexusWorkspaceViewModel @Inject constructor(
    private val vaultSerializer: NxtsVaultSerializer,
    private val inheritanceResolver: PropertyInheritanceResolver,
    private val credentialHub: EnterpriseCredentialHub,
    private val automationEngine: AutomationTaskEngine,
    private val gatewayTunnelManager: SecureGatewayTunnelManager,
    private val activeVaultStore: ActiveVaultSessionStore
) : ViewModel() {

    val sessionStore: ActiveVaultSessionStore
        get() = activeVaultStore

    val shortcutEngine = NexusShortcutEngine()
    val tilingController = WorkspaceTilingController()
    private val rtszImporter = RtszDocumentImporter()
    private val enterpriseFormatImporter = EnterpriseVaultFormatImporter()
    private val vaultExporter = VaultDocumentExporter()
    private var pendingEncryptedImportBytes: ByteArray? = null

    private val sshControllers
        get() = activeVaultStore.sshControllers
    private val rdpSessions
        get() = activeVaultStore.rdpSessions
    private val transferControllers
        get() = activeVaultStore.transferControllers
    private val vncSessions
        get() = activeVaultStore.vncSessions
    private val tabGatewayLeases
        get() = activeVaultStore.tabGatewayLeases
    private val adHocOverrides
        get() = activeVaultStore.adHocOverrides

    var onePasswordConnectUrl: String? = null
    var onePasswordConnectToken: String? = null
    var bitwardenServeUrl: String? = null
    var kdbxBytesProvider: (suspend (String) -> Pair<ByteArray, CharArray>)? = null

    private val _uiState = MutableStateFlow(
        WorkspaceUiState(
            vaultDocument = VaultHierarchySanitizer.sanitizeVaultDocument(activeVaultStore.snapshot.value.vaultDocument),
            isVaultLocked = activeVaultStore.snapshot.value.isVaultLocked,
            vaultUnlockError = activeVaultStore.snapshot.value.vaultUnlockError,
            activeAppError = activeVaultStore.snapshot.value.startupRecoveryError,
            hasPreImportBackup = activeVaultStore.snapshot.value.hasPreImportBackup,
            pendingImportFileName = activeVaultStore.snapshot.value.pendingImportFileName,
            isStarterUninitialized = activeVaultStore.snapshot.value.isStarterUninitialized,
            selectedConnectionId = activeVaultStore.snapshot.value.vaultDocument.connections.firstOrNull()?.id
        )
    )
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            activeVaultStore.snapshot.collect { shared ->
                if (shared.isVaultLocked) {
                    tilingController.allActiveTabs().forEach { tab ->
                        tilingController.closeTab(tab.tabId)
                    }
                }
                val recoveryErr = shared.startupRecoveryError
                if (recoveryErr != null) {
                    activeVaultStore.clearStartupRecoveryError()
                }
                val cleanDoc = VaultHierarchySanitizer.sanitizeVaultDocument(shared.vaultDocument)
                _uiState.update { current ->
                    val dynamicAll = current.hydratedDynamicConnections.values.flatten()
                    val activeConns = cleanDoc.connections.filterNot {
                        it.id in cleanDoc.tombstonedObjectIds
                    }
                    val validSelectedId = current.selectedConnectionId
                        ?.takeIf { selId ->
                            activeConns.any { it.id == selId } || dynamicAll.any { it.id == selId }
                        }
                        ?: activeConns.firstOrNull()?.id
                    val validSelectedFolderId = current.selectedFolderId?.takeIf { fid ->
                        fid == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID ||
                            cleanDoc.folders.any { it.id == fid && it.id !in cleanDoc.tombstonedObjectIds }
                    }
                    current.copy(
                        vaultDocument = cleanDoc,
                        isVaultLocked = shared.isVaultLocked,
                        vaultUnlockError = shared.vaultUnlockError,
                        activeAppError = recoveryErr ?: current.activeAppError,
                        hasPreImportBackup = shared.hasPreImportBackup,
                        pendingImportFileName = shared.pendingImportFileName,
                        isStarterUninitialized = shared.isStarterUninitialized,
                        selectedConnectionId = validSelectedId,
                        selectedFolderId = validSelectedFolderId,
                        remoteKeyCaptureEnabled = if (shared.isVaultLocked) false else current.remoteKeyCaptureEnabled,
                        isFullScreenSessionMode = if (shared.isVaultLocked) false else current.isFullScreenSessionMode,
                        isQuickConnectVisible = if (shared.isVaultLocked) false else current.isQuickConnectVisible
                    )
                }
            }
        }
        viewModelScope.launch {
            automationEngine.broadcastBus.collect { packet ->
                val tabs = tilingController.allActiveTabs().filter { it.broadcastEnabled }
                tabs.forEach { tab ->
                    if (packet.originTabId == null || packet.originTabId != tab.tabId) {
                        sshControllers[tab.tabId]?.sendUserBytes(packet.payloadBytes)
                    }
                }
            }
        }
        viewModelScope.launch {
            gatewayTunnelManager.activeLeases.collect { leases ->
                _uiState.update { it.copy(activeGatewayLeases = leases) }
            }
        }
    }

    /**
     * Observes tabs re-docked from freeform [com.nexusts.app.DetachedSessionActivity] windows
     * and attaches them back into the main workspace tiling tree without dropping their live sessions.
     */
    suspend fun observePendingReDockTabs() {
        activeVaultStore.pendingReDockTabs.collect { queued ->
            if (queued.isNotEmpty()) {
                val drained = activeVaultStore.consumePendingReDockTabs()
                drained.forEach { tab ->
                    _uiState.update {
                        it.copy(
                            activeRailId = "connections",
                            remoteKeyCaptureEnabled = isInteractiveRemoteKeyProtocol(tab.protocol) || it.remoteKeyCaptureEnabled
                        )
                    }
                    tilingController.openTab(tab)
                    appendLog("[WindowManager] Re-docked live session '${tab.title}' into main workspace")
                }
            }
        }
    }

    private fun bumpDocRevisionAndClock(doc: NxtsVaultDocument): NxtsVaultDocument {
        val nextRev = doc.revision + 1L
        val nextClock = doc.vectorClock.toMutableMap().apply {
            put("googlebook", nextRev)
        }
        return doc.copy(
            revision = nextRev,
            vectorClock = nextClock,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun evictLiveSessionsForConnections(connectionIds: Set<String>) {
        if (connectionIds.isEmpty()) return
        val matchingTabs = tilingController.allActiveTabs()
            .filter { it.connectionId in connectionIds }
            .map { it.tabId }
            .toSet()
        activeVaultStore.evictSessionsForConnectionIds(connectionIds, matchingTabs)
    }

    private fun collectDescendantFolderIds(folders: List<VaultFolder>, rootFolderId: String): Set<String> {
        val collected = mutableSetOf(rootFolderId)
        var added = true
        while (added) {
            added = false
            for (f in folders) {
                if (f.parentFolderId != null && f.parentFolderId in collected && collected.add(f.id)) {
                    added = true
                }
            }
        }
        return collected
    }

    private fun isInteractiveRemoteKeyProtocol(protocol: SessionProtocolKind): Boolean = when (protocol) {
        SessionProtocolKind.RDP,
        SessionProtocolKind.WINDOWS_TS,
        SessionProtocolKind.SSH,
        SessionProtocolKind.TELNET,
        SessionProtocolKind.VNC,
        SessionProtocolKind.APPLE_SCREEN_SHARING -> true
        else -> false
    }

    private fun requiresInteractiveConnectPrompt(doc: NxtsVaultDocument, connection: VaultConnection): Boolean {
        if (connection.inheritable.requireConnectConfirmation) return true
        if (connection.inheritable.credentialMode == InheritanceMode.PROMPT_AT_CONNECT) return true
        if (connection.inheritable.credentialMode != InheritanceMode.INHERIT_FROM_PARENT) return false

        val folderById = doc.folders.associateBy { it.id }
        val visited = mutableSetOf<String>()
        var currentFolderId = connection.parentFolderId
        while (currentFolderId != null && visited.add(currentFolderId)) {
            val folder = folderById[currentFolderId] ?: break
            when (folder.inheritable.credentialMode) {
                InheritanceMode.PROMPT_AT_CONNECT -> return true
                InheritanceMode.OVERRIDE_EXPLICIT,
                InheritanceMode.NONE_DIRECT -> return false
                InheritanceMode.INHERIT_FROM_PARENT -> currentFolderId = folder.parentFolderId
            }
        }
        return doc.rootInheritable.credentialMode == InheritanceMode.PROMPT_AT_CONNECT
    }

    fun selectRailDestination(id: String) {
        if (_uiState.value.isVaultLocked) return
        _uiState.update {
            it.copy(
                activeRailId = id,
                remoteKeyCaptureEnabled = if (id == "connections") it.remoteKeyCaptureEnabled else false
            )
        }
    }

    fun toggleRemoteKeyCapture() {
        if (_uiState.value.isVaultLocked) return
        _uiState.update { it.copy(remoteKeyCaptureEnabled = !it.remoteKeyCaptureEnabled) }
    }

    fun dismissQuickConnect() {
        _uiState.update { it.copy(isQuickConnectVisible = false) }
    }

    fun openNewConnectionDialog() {
        if (_uiState.value.isVaultLocked) return
        _uiState.update {
            it.copy(
                editingConnection = null,
                newConnectionDefaultFolderId = null,
                newConnectionDefaultProtocol = VaultProtocolType.RDP,
                isNewConnectionDialogVisible = true
            )
        }
    }

    fun openNewConnectionInFolder(
        folderId: String?,
        protocol: VaultProtocolType = VaultProtocolType.RDP
    ) {
        if (_uiState.value.isVaultLocked) return
        if (folderId == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) {
            _uiState.update {
                it.copy(userSnackbarMessage = "The 'Credentials' folder can only hold credentials.")
            }
            return
        }
        _uiState.update {
            it.copy(
                editingConnection = null,
                newConnectionDefaultFolderId = folderId,
                newConnectionDefaultProtocol = protocol,
                isNewConnectionDialogVisible = true
            )
        }
    }

    fun openEditConnectionDialog(connection: VaultConnection) {
        if (_uiState.value.isVaultLocked) return
        _uiState.update {
            it.copy(
                editingConnection = connection,
                newConnectionDefaultFolderId = connection.parentFolderId,
                newConnectionDefaultProtocol = connection.protocol,
                isNewConnectionDialogVisible = true
            )
        }
    }

    fun dismissNewConnectionDialog() {
        _uiState.update {
            it.copy(
                editingConnection = null,
                newConnectionDefaultFolderId = null,
                newConnectionDefaultProtocol = VaultProtocolType.RDP,
                isNewConnectionDialogVisible = false
            )
        }
    }

    fun openNewFolderDialog() {
        if (_uiState.value.isVaultLocked) return
        _uiState.update {
            it.copy(
                editingFolder = null,
                newFolderDefaultParentId = null,
                isFolderDialogVisible = true
            )
        }
    }

    fun openNewSubfolder(parentFolderId: String?) {
        if (_uiState.value.isVaultLocked) return
        if (parentFolderId == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) {
            _uiState.update {
                it.copy(userSnackbarMessage = "The 'Credentials' folder can only hold credentials.")
            }
            return
        }
        _uiState.update {
            it.copy(
                editingFolder = null,
                newFolderDefaultParentId = parentFolderId,
                isFolderDialogVisible = true
            )
        }
    }

    fun openEditFolderDialog(folder: VaultFolder) {
        if (_uiState.value.isVaultLocked || folder.id == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) return
        _uiState.update {
            it.copy(
                editingFolder = folder,
                newFolderDefaultParentId = folder.parentFolderId,
                selectedFolderId = folder.id,
                isFolderDialogVisible = true
            )
        }
    }

    fun dismissFolderDialog() {
        _uiState.update {
            it.copy(
                editingFolder = null,
                newFolderDefaultParentId = null,
                isFolderDialogVisible = false
            )
        }
    }

    fun resolveFolderInheritedPreview(folderId: String?): Pair<VaultCredential?, String> {
        val doc = _uiState.value.vaultDocument
        val syntheticProbe = VaultConnection(
            parentFolderId = folderId,
            name = "Inherited Preview",
            host = "preview.internal",
            protocol = VaultProtocolType.RDP,
            inheritable = InheritableProperties(credentialMode = InheritanceMode.INHERIT_FROM_PARENT),
            settings = ProtocolSpecificSettings.Rdp()
        )
        val profile = inheritanceResolver.resolveEffectiveProfile(doc, syntheticProbe)
        return profile.effectiveCredential to profile.credentialSourceLabel
    }

    fun openLockVaultDialog() {
        if (_uiState.value.isVaultLocked) {
            _uiState.update { it.copy(isSidebarVisible = true) }
        } else {
            _uiState.update { it.copy(isLockVaultDialogVisible = true) }
        }
    }

    fun dismissLockVaultDialog() {
        _uiState.update { it.copy(isLockVaultDialogVisible = false) }
    }

    fun selectFolderInTree(folderId: String?) {
        _uiState.update {
            it.copy(
                selectedFolderId = folderId,
                selectedSmartFolderId = null,
                selectedDynamicFolderId = null
            )
        }
    }

    fun selectConnectionInTree(connectionId: String) {
        val switchedToActiveTab = tilingController.activateConnectionIfPresent(connectionId)
        _uiState.update {
            it.copy(
                selectedConnectionId = connectionId,
                selectedFolderId = null,
                activeRailId = if (switchedToActiveTab) "connections" else it.activeRailId
            )
        }
    }

    fun toggleSmartFolderSelection(smartFolderId: String) {
        _uiState.update { state ->
            val next = if (state.selectedSmartFolderId == smartFolderId) null else smartFolderId
            state.copy(
                selectedSmartFolderId = next,
                selectedFolderId = null
            )
        }
    }

    fun launchLocalShellTab() {
        if (_uiState.value.isVaultLocked) return
        val localTab = WorkspaceSessionTab(
            connectionId = "local-shell-${System.currentTimeMillis()}",
            title = "Local Terminal",
            subtitleHost = "127.0.0.1",
            protocol = SessionProtocolKind.SSH,
            status = SessionLifecycleStatus.CONNECTED,
            broadcastEnabled = _uiState.value.broadcastModeEnabled
        )
        _uiState.update {
            it.copy(
                activeRailId = "connections",
                remoteKeyCaptureEnabled = true
            )
        }
        tilingController.openTab(localTab)
        appendLog("[Terminal] Opened local Googlebook OS terminal tab (${localTab.tabId.take(8)})")
    }

    fun effectiveProfileForSelected(): EffectiveConnectionProfile? {
        val state = _uiState.value
        if (state.isVaultLocked) return null
        val selId = state.selectedConnectionId ?: return null
        val doc = state.vaultDocument
        if (selId in doc.tombstonedObjectIds) return null
        val conn = doc.connections.firstOrNull { it.id == selId }
            ?: state.hydratedDynamicConnections.values.flatten().firstOrNull { it.id == selId }
            ?: return null
        return inheritanceResolver.resolveEffectiveProfile(doc, conn)
    }

    fun resolveConnectionAndSecret(connectionId: String, tabId: String? = null): Pair<VaultConnection?, ResolvedSessionSecret> {
        val doc = _uiState.value.vaultDocument
        val dynamicAll = _uiState.value.hydratedDynamicConnections.values.flatten()
        val override = tabId?.let { adHocOverrides[it] }

        val rawConn = doc.connections.firstOrNull { it.id == connectionId && it.id !in doc.tombstonedObjectIds }
            ?: dynamicAll.firstOrNull { it.id == connectionId }

        if (rawConn == null) {
            val adHocSecret = if (override != null && override.adHocUsername.isNotBlank()) {
                ResolvedSessionSecret(
                    username = override.adHocUsername,
                    password = override.adHocPassword,
                    domain = override.adHocDomain
                )
            } else {
                ResolvedSessionSecret(username = "admin")
            }
            return null to adHocSecret
        }

        val appliedTemplate = override?.appliedTemplateId?.let { tplId ->
            doc.templates.firstOrNull { it.id == tplId }
        }
        val templatedConn = if (appliedTemplate != null && appliedTemplate.protocol == rawConn.protocol) {
            rawConn.copy(settings = appliedTemplate.settings)
        } else {
            rawConn
        }
        val connWithHost = if (!override?.adHocHost.isNullOrBlank()) {
            templatedConn.copy(host = override!!.adHocHost!!)
        } else {
            templatedConn
        }
        val conn = if (override?.forceRdpConsoleAdmin != null && connWithHost.settings is ProtocolSpecificSettings.Rdp) {
            connWithHost.copy(
                settings = (connWithHost.settings as ProtocolSpecificSettings.Rdp).copy(
                    connectToConsoleAdmin = override.forceRdpConsoleAdmin
                )
            )
        } else {
            connWithHost
        }

        val effective = inheritanceResolver.resolveEffectiveProfile(doc, conn)
        val explicitOverrideCred = override?.overrideCredentialId?.let { cid ->
            doc.credentials.firstOrNull { it.id == cid }
        }
        val cred = explicitOverrideCred ?: effective.effectiveCredential

        val secret = when {
            override != null && override.adHocUsername.isNotBlank() -> ResolvedSessionSecret(
                username = override.adHocUsername,
                password = override.adHocPassword,
                domain = override.adHocDomain
            )
            cred != null -> resolveVaultCredentialSecret(cred)
            else -> ResolvedSessionSecret(username = "root")
        }
        return conn to secret
    }

    private fun resolveVaultCredentialSecret(cred: VaultCredential): ResolvedSessionSecret {
        val hasLocalSecret = cred.secretPassword.isNotBlank() || cred.privateKeyPem.isNotBlank()
        val isExternalSource = cred.externalReferenceUri.isNotBlank() ||
            (cred.sourceKind != CredentialSourceKind.VAULT_INTERNAL && cred.sourceKind != CredentialSourceKind.SSH_KEY_PAIR)
        if (!hasLocalSecret && isExternalSource) {
            val externalResolved = runCatching {
                runBlocking {
                    withTimeout(2_000L) {
                        credentialHub.resolveCredential(
                            credential = cred,
                            onePasswordConnectUrl = onePasswordConnectUrl,
                            onePasswordConnectToken = onePasswordConnectToken,
                            bitwardenServeUrl = bitwardenServeUrl,
                            kdbxBytesProvider = kdbxBytesProvider
                        )
                    }
                }
            }.getOrNull()
            if (externalResolved != null) {
                return externalResolved
            }
        }
        val totp = cred.totpSecretBase32.takeIf { it.isNotBlank() }?.let {
            runCatching { credentialHub.generateCurrentTotpCode(it) }.getOrNull()
        }
        return ResolvedSessionSecret(
            username = cred.username,
            password = cred.secretPassword,
            domain = cred.domain,
            privateKeyPem = cred.privateKeyPem,
            keyPassphrase = cred.keyPassphrase,
            totpCode = totp
        )
    }

    fun getOrCreateSshController(tab: WorkspaceSessionTab): SshTerminalSessionController {
        activeVaultStore.tabConnectionIds[tab.tabId] = tab.connectionId
        return sshControllers.getOrPut(tab.tabId) {
            val isLocalShell = tab.connectionId.startsWith("local-shell-")
            val (conn, secret) = resolveConnectionAndSecret(tab.connectionId, tab.tabId)
            val override = adHocOverrides[tab.tabId]
            val sshSettings = conn?.settings as? ProtocolSpecificSettings.Ssh
            val doc = _uiState.value.vaultDocument
            val isTelnet = !isLocalShell && (
                tab.protocol == SessionProtocolKind.TELNET ||
                    conn?.protocol == VaultProtocolType.TELNET ||
                    override?.adHocProtocolType == VaultProtocolType.TELNET
                )
            val rawHost = override?.adHocHost ?: conn?.host ?: tab.subtitleHost
            val effectivePort = resolveEffectiveTelnetOrSshPort(conn, tab.protocol, override)

            val bastionConn = sshSettings?.bastionJumpConnectionId?.let { id ->
                doc.connections.firstOrNull { it.id == id && it.id !in doc.tombstonedObjectIds }
            }
            val effectiveGateway = if (!isLocalShell && bastionConn == null && conn != null) {
                inheritanceResolver.resolveEffectiveProfile(doc, conn).effectiveGateway
            } else {
                null
            }

            var targetHost = rawHost
            var targetPort = effectivePort
            var bastionHost: String? = null
            var bastionPort = 22
            var bastionUser = ""
            var bastionPassword = ""
            var bastionKeyPem = ""
            var tunnelLease: ActiveTunnelLease? = null

            if (bastionConn != null) {
                val (_, bastionSecret) = resolveConnectionAndSecret(bastionConn.id)
                bastionHost = bastionConn.host
                bastionPort = (bastionConn.settings as? ProtocolSpecificSettings.Ssh)?.port?.takeIf { it > 0 } ?: 22
                bastionUser = bastionSecret.username.ifBlank { "root" }
                bastionPassword = bastionSecret.password
                bastionKeyPem = bastionSecret.privateKeyPem
            } else if (effectiveGateway != null && effectiveGateway.kind == GatewayTransportKind.SSH_TUNNEL_BASTION) {
                val gwCred = doc.credentials.firstOrNull { it.id == effectiveGateway.credentialId }
                val gwSecret = gwCred?.let { resolveVaultCredentialSecret(it) }
                if (SshNativeBridge.isNativeLoaded && rawHost.isNotBlank()) {
                    val lease = runCatching {
                        runBlocking {
                            gatewayTunnelManager.openLoopbackTunnelLease(
                                gateway = effectiveGateway,
                                credential = gwCred,
                                targetHost = rawHost,
                                targetPort = effectivePort
                            )
                        }
                    }.getOrNull()
                    if (lease != null) {
                        tabGatewayLeases[tab.tabId] = lease
                        targetHost = lease.localBindHost
                        targetPort = lease.localBindPort
                        tunnelLease = lease
                    } else {
                        bastionHost = effectiveGateway.host
                        bastionPort = effectiveGateway.port.takeIf { it > 0 } ?: 22
                        bastionUser = gwSecret?.username?.ifBlank { "root" } ?: "root"
                        bastionPassword = gwSecret?.password.orEmpty()
                        bastionKeyPem = gwSecret?.privateKeyPem.orEmpty()
                    }
                } else {
                    bastionHost = effectiveGateway.host
                    bastionPort = effectiveGateway.port.takeIf { it > 0 } ?: 22
                    bastionUser = gwSecret?.username?.ifBlank { "root" } ?: "root"
                    bastionPassword = gwSecret?.password.orEmpty()
                    bastionKeyPem = gwSecret?.privateKeyPem.orEmpty()
                }
            }

            SshTerminalSessionController(
                title = tab.title,
                host = targetHost,
                port = targetPort,
                username = secret.username.ifBlank { "root" },
                portForwards = sshSettings?.portForwards.orEmpty(),
                enableX11Forwarding = sshSettings?.enableX11Forwarding ?: false,
                bastionHost = bastionHost,
                isLocalShell = isLocalShell,
                isTelnet = isTelnet,
                password = secret.password,
                privateKeyPem = secret.privateKeyPem,
                privateKeyPassphrase = secret.keyPassphrase,
                startupCommand = sshSettings?.startupCommand.orEmpty(),
                terminalType = sshSettings?.terminalType?.ifBlank { "xterm-256color" } ?: "xterm-256color",
                scrollbackLines = sshSettings?.scrollbackLines ?: 10_000,
                bastionPort = bastionPort,
                bastionUsername = bastionUser,
                bastionPassword = bastionPassword,
                bastionPrivateKeyPem = bastionKeyPem,
                tunnelLease = tunnelLease
            )
        }
    }

    fun getOrCreateRdpSession(tab: WorkspaceSessionTab): RdpManagedSession {
        activeVaultStore.tabConnectionIds[tab.tabId] = tab.connectionId
        return rdpSessions.getOrPut(tab.tabId) {
            val (conn, secret) = resolveConnectionAndSecret(tab.connectionId, tab.tabId)
            val override = adHocOverrides[tab.tabId]
            val effectiveHost = override?.adHocHost ?: conn?.host ?: tab.subtitleHost
            val fallbackProto = conn?.protocol
                ?: override?.adHocProtocolType?.takeIf { it == VaultProtocolType.WINDOWS_TERMINAL_SERVICES }
                ?: VaultProtocolType.RDP
            val fallbackConn = (conn?.copy(host = effectiveHost)) ?: VaultConnection(
                id = tab.connectionId,
                name = tab.title,
                host = effectiveHost,
                protocol = fallbackProto,
                settings = ProtocolSpecificSettings.Rdp()
            )
            val baseSettings = (fallbackConn.settings as? ProtocolSpecificSettings.Rdp)
                ?: ProtocolSpecificSettings.Rdp()
            val settings = if (override?.adHocPort != null) {
                baseSettings.copy(port = override.adHocPort)
            } else {
                baseSettings
            }
            RdpManagedSession(
                connection = fallbackConn,
                settings = settings,
                secret = secret
            )
        }
    }

    fun getOrCreateTransferController(tab: WorkspaceSessionTab): TransferCommanderSessionController {
        activeVaultStore.tabConnectionIds[tab.tabId] = tab.connectionId
        return transferControllers.getOrPut(tab.tabId) {
            val (conn, secret) = resolveConnectionAndSecret(tab.connectionId, tab.tabId)
            val override = adHocOverrides[tab.tabId]
            val doc = _uiState.value.vaultDocument
            val proto = conn?.protocol ?: override?.adHocProtocolType ?: when (tab.protocol) {
                SessionProtocolKind.SCP -> VaultProtocolType.SCP
                SessionProtocolKind.FTP -> VaultProtocolType.FTP
                SessionProtocolKind.FTPS -> VaultProtocolType.FTPS
                else -> VaultProtocolType.SFTP
            }
            val ftSettings = conn?.settings as? ProtocolSpecificSettings.FileTransfer
            val defaultPort = when (proto) {
                VaultProtocolType.FTP -> 21
                VaultProtocolType.FTPS -> if (ftSettings?.ftpsImplicitTls == true) 990 else 21
                else -> 22
            }
            val rawHost = override?.adHocHost ?: conn?.host ?: tab.subtitleHost
            val effectivePort = override?.adHocPort ?: ftSettings?.port?.takeIf { it > 0 } ?: defaultPort

            val effectiveGateway = if (conn != null) {
                inheritanceResolver.resolveEffectiveProfile(doc, conn).effectiveGateway
            } else {
                null
            }
            var bastionHost = ""
            var bastionPort = 22
            var bastionUser = ""
            var bastionPassword = ""
            var bastionKeyPem = ""
            if (effectiveGateway != null && effectiveGateway.kind == GatewayTransportKind.SSH_TUNNEL_BASTION) {
                val gwCred = doc.credentials.firstOrNull { it.id == effectiveGateway.credentialId }
                val gwSecret = gwCred?.let { resolveVaultCredentialSecret(it) }
                bastionHost = effectiveGateway.host
                bastionPort = effectiveGateway.port.takeIf { it > 0 } ?: 22
                bastionUser = gwSecret?.username?.ifBlank { "root" } ?: "root"
                bastionPassword = gwSecret?.password.orEmpty()
                bastionKeyPem = gwSecret?.privateKeyPem.orEmpty()
            }

            TransferCommanderSessionController(
                protocolType = proto,
                host = rawHost,
                port = effectivePort,
                secret = secret,
                initialLocalPathHint = ftSettings?.initialLocalDirectory?.ifBlank { "/home/googlebook/Downloads" }
                    ?: "/home/googlebook/Downloads",
                initialRemotePathHint = ftSettings?.initialRemoteDirectory.orEmpty(),
                ftpsImplicitTls = ftSettings?.ftpsImplicitTls ?: (proto == VaultProtocolType.FTPS && effectivePort == 990),
                allowInsecureTls = !(ftSettings?.verifyTls ?: false),
                bastionHost = bastionHost,
                bastionPort = bastionPort,
                bastionUsername = bastionUser,
                bastionPassword = bastionPassword,
                bastionPrivateKeyPem = bastionKeyPem
            )
        }
    }

    fun getOrCreateVncSession(tab: WorkspaceSessionTab): VncRfbSessionClient {
        activeVaultStore.tabConnectionIds[tab.tabId] = tab.connectionId
        return vncSessions.getOrPut(tab.tabId) {
            VncRfbSessionClient()
        }
    }

    private fun runConfiguredLifecycleTask(taskId: String?, phaseLabel: String) {
        if (taskId.isNullOrBlank()) return
        val doc = _uiState.value.vaultDocument
        doc.commandTasks.firstOrNull { it.id == taskId }?.let { task ->
            appendLog("[$phaseLabel] Running command task '${task.name}'")
            executeCommandTask(task)
            return
        }
        doc.keySequenceTasks.firstOrNull { it.id == taskId }?.let { macro ->
            appendLog("[$phaseLabel] Running key-sequence macro '${macro.name}'")
            executeKeySequenceTask(macro)
        }
    }

    fun launchConnection(
        connection: VaultConnection,
        placement: QuickLaunchPlacement = QuickLaunchPlacement.ACTIVE_PANE_TAB,
        onDetachWindowRequested: ((WorkspaceSessionTab) -> Unit)? = null
    ) {
        val state = _uiState.value
        if (state.isVaultLocked) return

        if (requiresInteractiveConnectPrompt(state.vaultDocument, connection)) {
            _uiState.update { it.copy(connectWithOptionsTarget = connection) }
            appendLog("[Connect] Prompting credentials/confirmation for '${connection.name}'")
            return
        }

        val profile = inheritanceResolver.resolveEffectiveProfile(state.vaultDocument, connection)
        val uiKind = mapProtocolToUiKind(connection.protocol)
        val tab = WorkspaceSessionTab(
            connectionId = connection.id,
            title = connection.name,
            subtitleHost = connection.host,
            protocol = uiKind,
            status = SessionLifecycleStatus.CONNECTED,
            environmentTag = profile.effectiveEnvironmentBadge,
            customColorHex = profile.effectiveColorAccentHex,
            broadcastEnabled = state.broadcastModeEnabled
        )
        activeVaultStore.tabConnectionIds[tab.tabId] = connection.id
        recordRecentConnection(connection.id)
        appendLog("[Connect] Opening ${connection.protocol.name} session '${connection.name}' (${connection.host})")

        when (placement) {
            QuickLaunchPlacement.ACTIVE_PANE_TAB -> {
                _uiState.update {
                    it.copy(
                        activeRailId = "connections",
                        selectedConnectionId = connection.id,
                        remoteKeyCaptureEnabled = isInteractiveRemoteKeyProtocol(uiKind) || it.remoteKeyCaptureEnabled
                    )
                }
                tilingController.openTab(tab)
            }
            QuickLaunchPlacement.SPLIT_RIGHT_PANE -> {
                _uiState.update {
                    it.copy(
                        activeRailId = "connections",
                        selectedConnectionId = connection.id,
                        remoteKeyCaptureEnabled = isInteractiveRemoteKeyProtocol(uiKind) || it.remoteKeyCaptureEnabled
                    )
                }
                tilingController.splitPane(
                    orientation = SplitOrientation.HORIZONTAL,
                    tabForNewPane = tab
                )
            }
            QuickLaunchPlacement.DETACHED_NEW_WINDOW -> onDetachWindowRequested?.invoke(tab)
        }

        runConfiguredLifecycleTask(connection.inheritable.preConnectTaskId, "Pre-Connect")
    }

    fun openConnectWithOptions(connection: VaultConnection) {
        if (_uiState.value.isVaultLocked) return
        _uiState.update { it.copy(connectWithOptionsTarget = connection) }
    }

    fun dismissConnectWithOptions() {
        _uiState.update { it.copy(connectWithOptionsTarget = null) }
    }

    fun launchConnectionWithOptions(
        connection: VaultConnection,
        override: AdHocLaunchOverride,
        placement: QuickLaunchPlacement = QuickLaunchPlacement.ACTIVE_PANE_TAB,
        onDetachWindowRequested: ((WorkspaceSessionTab) -> Unit)? = null
    ) {
        if (_uiState.value.isVaultLocked) return
        val profile = inheritanceResolver.resolveEffectiveProfile(_uiState.value.vaultDocument, connection)
        val tplAccent = override.appliedTemplateId?.let { id ->
            _uiState.value.vaultDocument.templates.firstOrNull { it.id == id }?.colorAccentHex
        }
        val consoleSuffix = if (override.forceRdpConsoleAdmin == true && !connection.name.contains("/admin")) {
            " (/admin)"
        } else {
            ""
        }
        val uiKind = mapProtocolToUiKind(connection.protocol)
        val effectiveHost = override.adHocHost ?: connection.host
        val tab = WorkspaceSessionTab(
            connectionId = connection.id,
            title = connection.name + consoleSuffix,
            subtitleHost = effectiveHost,
            protocol = uiKind,
            status = SessionLifecycleStatus.CONNECTED,
            environmentTag = profile.effectiveEnvironmentBadge,
            customColorHex = tplAccent ?: profile.effectiveColorAccentHex,
            broadcastEnabled = _uiState.value.broadcastModeEnabled
        )
        activeVaultStore.tabConnectionIds[tab.tabId] = connection.id
        adHocOverrides[tab.tabId] = override
        recordRecentConnection(connection.id)
        _uiState.update {
            it.copy(
                connectWithOptionsTarget = null,
                activeRailId = "connections",
                selectedConnectionId = connection.id,
                remoteKeyCaptureEnabled = (placement != QuickLaunchPlacement.DETACHED_NEW_WINDOW && isInteractiveRemoteKeyProtocol(uiKind)) || it.remoteKeyCaptureEnabled
            )
        }
        appendLog("[Connect+Options] Launched '${connection.name}' with custom session overrides")

        when (placement) {
            QuickLaunchPlacement.ACTIVE_PANE_TAB -> tilingController.openTab(tab)
            QuickLaunchPlacement.SPLIT_RIGHT_PANE -> tilingController.splitPane(
                orientation = SplitOrientation.HORIZONTAL,
                tabForNewPane = tab
            )
            QuickLaunchPlacement.DETACHED_NEW_WINDOW -> onDetachWindowRequested?.invoke(tab)
        }

        runConfiguredLifecycleTask(connection.inheritable.preConnectTaskId, "Pre-Connect")
    }

    fun connectAllInFolder(
        folderId: String,
        onDetachWindowRequested: ((WorkspaceSessionTab) -> Unit)? = null
    ) {
        if (_uiState.value.isVaultLocked) return
        val doc = _uiState.value.vaultDocument
        val folderTreeIds = collectDescendantFolderIds(doc.folders, folderId)
        val children = doc.connections
            .filter { it.parentFolderId in folderTreeIds && it.id !in doc.tombstonedObjectIds }
            .sortedWith(compareBy<VaultConnection> { it.sortOrder }.thenBy { it.name.lowercase() })
        if (children.isEmpty()) return
        _uiState.update { it.copy(activeRailId = "connections") }
        children.forEach { conn ->
            launchConnection(conn, QuickLaunchPlacement.ACTIVE_PANE_TAB, onDetachWindowRequested)
        }
        val folderName = doc.folders.firstOrNull { it.id == folderId }?.name ?: folderId
        appendLog("[Bulk Connect] Opened ${children.size} session(s) from folder '$folderName'")
    }

    /**
     * Counts all active RDP / Windows Terminal Services connections inside [folderId]
     * and any nested subfolders.
     */
    fun countRdpConnectionsInSubtree(folderId: String): Int {
        val doc = _uiState.value.vaultDocument
        val folderTreeIds = collectDescendantFolderIds(doc.folders, folderId)
        return doc.connections.count { conn ->
            conn.parentFolderId in folderTreeIds &&
                conn.id !in doc.tombstonedObjectIds &&
                (conn.protocol == VaultProtocolType.RDP || conn.protocol == VaultProtocolType.WINDOWS_TERMINAL_SERVICES)
        }
    }

    /**
     * Recursively launches all RDP / Windows Terminal Services connections inside [folderId]
     * and its nested subfolders in deterministic folder/sort order, leaving SSH/VNC/other
     * connections untouched.
     */
    fun connectAllRdpInFolder(
        folderId: String,
        onDetachWindowRequested: ((WorkspaceSessionTab) -> Unit)? = null
    ): Int {
        if (_uiState.value.isVaultLocked) return 0
        val doc = _uiState.value.vaultDocument
        val folderName = doc.folders.firstOrNull { it.id == folderId }?.name ?: folderId
        val folderTreeIds = collectDescendantFolderIds(doc.folders, folderId)
        val rdpChildren = doc.connections
            .filter { conn ->
                conn.parentFolderId in folderTreeIds &&
                    conn.id !in doc.tombstonedObjectIds &&
                    (conn.protocol == VaultProtocolType.RDP || conn.protocol == VaultProtocolType.WINDOWS_TERMINAL_SERVICES)
            }
            .sortedWith(compareBy<VaultConnection> { it.sortOrder }.thenBy { it.name.lowercase() })

        if (rdpChildren.isEmpty()) {
            _uiState.update { it.copy(userSnackbarMessage = "No RDP connections in '$folderName'") }
            return 0
        }

        _uiState.update { it.copy(activeRailId = "connections") }
        rdpChildren.forEach { conn ->
            launchConnection(conn, QuickLaunchPlacement.ACTIVE_PANE_TAB, onDetachWindowRequested)
        }
        val msg = "Started ${rdpChildren.size} RDP session(s) from '$folderName'"
        _uiState.update { it.copy(userSnackbarMessage = msg) }
        appendLog("[Bulk RDP] $msg")
        return rdpChildren.size
    }

    /**
     * Recursively disconnects all active docked & detached sessions belonging to [folderId]
     * and any of its nested subfolders, running post-disconnect tasks cleanly.
     */
    fun disconnectAllInFolder(folderId: String): Int {
        if (_uiState.value.isVaultLocked) return 0
        val doc = _uiState.value.vaultDocument
        val folderName = doc.folders.firstOrNull { it.id == folderId }?.name ?: folderId
        val folderTreeIds = collectDescendantFolderIds(doc.folders, folderId)
        val connectionIdsInSubtree = doc.connections
            .filter { it.parentFolderId in folderTreeIds && it.id !in doc.tombstonedObjectIds }
            .map { it.id }
            .toSet()

        if (connectionIdsInSubtree.isEmpty()) {
            _uiState.update { it.copy(userSnackbarMessage = "No active sessions in '$folderName'") }
            return 0
        }

        val matchingDockedTabs = tilingController.allActiveTabs()
            .filter { it.connectionId in connectionIdsInSubtree }
        matchingDockedTabs.forEach { tab ->
            closeSessionTab(tab.tabId)
        }
        val detachedEvicted = activeVaultStore.evictSessionsForConnectionIds(connectionIdsInSubtree)
        val totalClosed = matchingDockedTabs.size + detachedEvicted

        val msg = if (totalClosed > 0) {
            "Disconnected $totalClosed session(s) in '$folderName'"
        } else {
            "No active sessions in '$folderName'"
        }
        _uiState.update { it.copy(userSnackbarMessage = msg) }
        appendLog("[Bulk Disconnect] $msg")
        return totalClosed
    }

    /**
     * Disconnects all active docked & detached sessions for a single [connectionId].
     */
    fun disconnectConnectionSessions(connectionId: String): Int {
        if (_uiState.value.isVaultLocked) return 0
        val doc = _uiState.value.vaultDocument
        val connName = doc.connections.firstOrNull { it.id == connectionId }?.name ?: connectionId
        val matchingTabs = tilingController.allActiveTabs().filter { it.connectionId == connectionId }
        matchingTabs.forEach { tab -> closeSessionTab(tab.tabId) }
        val detachedEvicted = activeVaultStore.evictSessionsForConnectionIds(setOf(connectionId))
        val totalClosed = matchingTabs.size + detachedEvicted
        if (totalClosed > 0) {
            _uiState.update { it.copy(userSnackbarMessage = "Disconnected '$connName'") }
            appendLog("[Disconnect] Closed $totalClosed session(s) for '$connName'")
        }
        return totalClosed
    }

    /**
     * Sends a remote Windows logoff command (`shutdown.exe /l /f`) to an active RDP/WTS tab
     * and closes the session tab cleanly after flushing over Fast-Path.
     */
    fun logOffTabSession(tabId: String) {
        if (_uiState.value.isVaultLocked) return
        val tab = tilingController.allActiveTabs().firstOrNull { it.tabId == tabId }
        val rdp = rdpSessions[tabId]
        val label = tab?.title ?: tabId
        if (rdp != null) {
            viewModelScope.launch(Dispatchers.IO) {
                rdp.logOffRemoteSession()
                delay(450L)
                withContext(Dispatchers.Main) {
                    closeSessionTab(tabId)
                    _uiState.update { it.copy(userSnackbarMessage = "Logged off '$label'") }
                    appendLog("[Log Off] Sent Windows session logoff for '$label'")
                }
            }
        } else {
            closeSessionTab(tabId)
            _uiState.update { it.copy(userSnackbarMessage = "Logged off '$label'") }
            appendLog("[Log Off] Closed session '$label'")
        }
    }

    /**
     * Logs off all active RDP / Windows Terminal Services sessions for [connectionId] and disconnects them.
     */
    fun logOffConnectionSessions(connectionId: String): Int {
        if (_uiState.value.isVaultLocked) return 0
        val doc = _uiState.value.vaultDocument
        val connName = doc.connections.firstOrNull { it.id == connectionId }?.name ?: connectionId
        val matchingTabs = tilingController.allActiveTabs().filter { it.connectionId == connectionId }
        val matchingLiveRdp = rdpSessions.entries
            .filter { (tid, sess) -> sess.connection.id == connectionId || matchingTabs.any { it.tabId == tid } }
            .map { it.value }
            .distinct()

        if (matchingLiveRdp.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                matchingLiveRdp.forEach { it.logOffRemoteSession() }
                delay(450L)
                withContext(Dispatchers.Main) {
                    matchingTabs.forEach { tab -> closeSessionTab(tab.tabId) }
                    activeVaultStore.evictSessionsForConnectionIds(setOf(connectionId))
                    _uiState.update { it.copy(userSnackbarMessage = "Logged off '$connName'") }
                    appendLog("[Log Off] Logged off ${matchingLiveRdp.size} remote session(s) for '$connName'")
                }
            }
            return matchingLiveRdp.size
        }

        val closed = disconnectConnectionSessions(connectionId)
        if (closed > 0) {
            _uiState.update { it.copy(userSnackbarMessage = "Logged off '$connName'") }
        }
        return closed
    }

    fun openMoveConnectionDialog(connection: VaultConnection) {
        if (_uiState.value.isVaultLocked) return
        _uiState.update { it.copy(movingConnectionTarget = connection) }
    }

    fun dismissMoveConnectionDialog() {
        _uiState.update { it.copy(movingConnectionTarget = null) }
    }

    /**
     * Moves [connectionId] into [targetFolderId] (or top-level root when `null`) via drag-and-drop
     * or context menu, preserving any active SSH/RDP sessions while refreshing inherited badges/colors.
     */
    fun moveConnectionToFolder(connectionId: String, targetFolderId: String?): Boolean {
        if (_uiState.value.isVaultLocked) return false
        if (targetFolderId == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) {
            _uiState.update {
                it.copy(
                    movingConnectionTarget = null,
                    userSnackbarMessage = "The 'Credentials' folder can only hold credentials."
                )
            }
            return false
        }
        val currentDoc = _uiState.value.vaultDocument
        val existing = currentDoc.connections.firstOrNull {
            it.id == connectionId && it.id !in currentDoc.tombstonedObjectIds
        } ?: return false

        val normalizedTargetId = targetFolderId?.takeIf { folderId ->
            currentDoc.folders.any { it.id == folderId && it.id !in currentDoc.tombstonedObjectIds }
        }
        if (targetFolderId != null && normalizedTargetId == null) return false
        if (existing.parentFolderId == normalizedTargetId) {
            _uiState.update { it.copy(movingConnectionTarget = null) }
            return false
        }

        val movedConn = existing.copy(
            parentFolderId = normalizedTargetId,
            sortOrder = (sortedSiblingConnections(
                currentDoc.connections,
                normalizedTargetId,
                currentDoc.tombstonedObjectIds
            ).maxOfOrNull { it.sortOrder } ?: 0) + 10,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                connections = doc.connections.map { if (it.id == connectionId) movedConn else it }
            )
        }
        val updatedDoc = activeVaultStore.snapshot.value.vaultDocument
        val refreshedProfile = inheritanceResolver.resolveEffectiveProfile(updatedDoc, movedConn)
        tilingController.allActiveTabs()
            .filter { it.connectionId == connectionId }
            .forEach { tab ->
                tilingController.updateTab(tab.tabId) {
                    it.copy(
                        environmentTag = refreshedProfile.effectiveEnvironmentBadge,
                        customColorHex = refreshedProfile.effectiveColorAccentHex
                    )
                }
            }

        val destinationLabel = normalizedTargetId?.let { fid ->
            updatedDoc.folders.firstOrNull { it.id == fid }?.name
        } ?: "Top Level"
        val moveMessage = "Moved '${movedConn.name}' to '$destinationLabel'"
        _uiState.update {
            it.copy(
                vaultDocument = updatedDoc,
                selectedConnectionId = connectionId,
                movingConnectionTarget = null,
                userSnackbarMessage = moveMessage
            )
        }
        appendLog("[Vault] $moveMessage")
        return true
    }

    fun openMoveFolderDialog(folder: VaultFolder) {
        if (_uiState.value.isVaultLocked) return
        if (folder.id == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) return
        _uiState.update { it.copy(movingFolderTarget = folder) }
    }

    fun dismissMoveFolderDialog() {
        _uiState.update { it.copy(movingFolderTarget = null) }
    }

    /**
     * Verifies that moving [folderId] under [targetParentFolderId] will not create a cycle
     * (moving a folder into itself or any of its own nested descendants) or target Credentials.
     */
    fun canMoveFolderToParent(folderId: String, targetParentFolderId: String?): Boolean {
        if (_uiState.value.isVaultLocked) return false
        if (folderId == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID ||
            targetParentFolderId == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID
        ) {
            return false
        }
        val doc = _uiState.value.vaultDocument
        val folder = doc.folders.firstOrNull {
            it.id == folderId && it.id !in doc.tombstonedObjectIds
        } ?: return false
        if (targetParentFolderId == null && VaultHierarchySanitizer.isReservedCredentialsFolderName(folder.name)) {
            return false
        }
        if (targetParentFolderId == folderId) return false
        if (targetParentFolderId != null) {
            val targetExists = doc.folders.any {
                it.id == targetParentFolderId && it.id !in doc.tombstonedObjectIds
            }
            if (!targetExists) return false
            val descendants = collectDescendantFolderIds(doc.folders, folderId)
            if (targetParentFolderId in descendants) return false
        }
        return folder.parentFolderId != targetParentFolderId
    }

    /**
     * Moves [folderId] under [targetParentFolderId] (`null` = top-level root) with strict cycle rejection,
     * refreshing inherited badges/colors on all active tabs inside the moved folder subtree.
     */
    fun moveFolderToParent(folderId: String, targetParentFolderId: String?): Boolean {
        if (_uiState.value.isVaultLocked) return false
        if (folderId == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID ||
            targetParentFolderId == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID
        ) {
            _uiState.update {
                it.copy(
                    movingFolderTarget = null,
                    userSnackbarMessage = "The 'Credentials' folder can only hold credentials."
                )
            }
            return false
        }
        val currentDoc = _uiState.value.vaultDocument
        val existing = currentDoc.folders.firstOrNull {
            it.id == folderId && it.id !in currentDoc.tombstonedObjectIds
        } ?: return false

        val normalizedTargetId = targetParentFolderId?.takeIf { fid ->
            currentDoc.folders.any { it.id == fid && it.id !in currentDoc.tombstonedObjectIds }
        }
        if (targetParentFolderId != null && normalizedTargetId == null) return false

        if (normalizedTargetId == null && VaultHierarchySanitizer.isReservedCredentialsFolderName(existing.name)) {
            _uiState.update {
                it.copy(
                    movingFolderTarget = null,
                    userSnackbarMessage = "The root 'Credentials' folder is reserved for credentials."
                )
            }
            return false
        }

        if (normalizedTargetId == folderId ||
            (normalizedTargetId != null && normalizedTargetId in collectDescendantFolderIds(currentDoc.folders, folderId))
        ) {
            val msg = "Cannot move '${existing.name}' into itself or a child folder"
            _uiState.update { it.copy(movingFolderTarget = null, userSnackbarMessage = msg) }
            return false
        }
        if (existing.parentFolderId == normalizedTargetId) {
            _uiState.update { it.copy(movingFolderTarget = null) }
            return false
        }

        val nextSortOrder = (sortedSiblingFolders(
            currentDoc.folders,
            normalizedTargetId,
            currentDoc.tombstonedObjectIds
        ).maxOfOrNull { it.sortOrder } ?: 0) + 10
        val movedFolder = existing.copy(
            parentFolderId = normalizedTargetId,
            sortOrder = nextSortOrder,
            updatedAtEpochMs = System.currentTimeMillis()
        )

        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                folders = doc.folders.map { if (it.id == folderId) movedFolder else it }
            )
        }
        val updatedDoc = activeVaultStore.snapshot.value.vaultDocument
        refreshLiveTabInheritanceForSubtree(updatedDoc, folderId)

        val destLabel = normalizedTargetId?.let { fid ->
            updatedDoc.folders.firstOrNull { it.id == fid }?.name
        } ?: "Top Level"
        val moveMessage = "Moved folder '${movedFolder.name}' to '$destLabel'"
        _uiState.update {
            it.copy(
                vaultDocument = updatedDoc,
                selectedFolderId = folderId,
                movingFolderTarget = null,
                userSnackbarMessage = moveMessage
            )
        }
        appendLog("[Vault] $moveMessage")
        return true
    }

    /**
     * Drag-and-drop handler for folders supporting nesting (`INSIDE`) and relative sibling reordering
     * (`BEFORE` / `AFTER`) across any depth with cycle and `"Credentials"` system folder protection.
     */
    fun moveOrReorderFolder(
        draggedFolderId: String,
        targetFolderId: String,
        placement: FolderDropPlacement
    ): Boolean {
        if (_uiState.value.isVaultLocked) return false
        if (draggedFolderId == targetFolderId) return false
        if (draggedFolderId == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID ||
            targetFolderId == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID
        ) {
            _uiState.update {
                it.copy(userSnackbarMessage = "The 'Credentials' folder cannot be moved or hold connection folders.")
            }
            return false
        }
        if (placement == FolderDropPlacement.INSIDE) {
            return moveFolderToParent(draggedFolderId, targetFolderId)
        }

        val doc = _uiState.value.vaultDocument
        val dragged = doc.folders.firstOrNull {
            it.id == draggedFolderId && it.id !in doc.tombstonedObjectIds
        } ?: return false
        val target = doc.folders.firstOrNull {
            it.id == targetFolderId && it.id !in doc.tombstonedObjectIds
        } ?: return false

        val destParentId = target.parentFolderId
        if (destParentId == draggedFolderId ||
            (destParentId != null && destParentId in collectDescendantFolderIds(doc.folders, draggedFolderId))
        ) {
            _uiState.update {
                it.copy(userSnackbarMessage = "Cannot move '${dragged.name}' into itself or a child folder")
            }
            return false
        }
        if (destParentId == null && VaultHierarchySanitizer.isReservedCredentialsFolderName(dragged.name)) {
            _uiState.update {
                it.copy(userSnackbarMessage = "The root 'Credentials' folder is reserved for credentials.")
            }
            return false
        }

        val destSiblings = sortedSiblingFolders(doc.folders, destParentId, doc.tombstonedObjectIds)
            .filterNot { it.id == draggedFolderId }
            .toMutableList()
        val targetIdx = destSiblings.indexOfFirst { it.id == targetFolderId }
        if (targetIdx < 0) return false
        val insertIdx = (if (placement == FolderDropPlacement.AFTER) targetIdx + 1 else targetIdx)
            .coerceIn(0, destSiblings.size)
        val now = System.currentTimeMillis()
        val movedDragged = dragged.copy(parentFolderId = destParentId, updatedAtEpochMs = now)
        destSiblings.add(insertIdx, movedDragged)

        val updatedById = destSiblings.mapIndexed { idx, f ->
            f.id to f.copy(sortOrder = (idx + 1) * 10, updatedAtEpochMs = now)
        }.toMap()

        activeVaultStore.updateVaultDocument { current ->
            bumpDocRevisionAndClock(current).copy(
                folders = current.folders.map { f -> updatedById[f.id] ?: f }
            )
        }
        val updatedDoc = activeVaultStore.snapshot.value.vaultDocument
        if (dragged.parentFolderId != destParentId) {
            refreshLiveTabInheritanceForSubtree(updatedDoc, draggedFolderId)
        }
        _uiState.update {
            it.copy(
                vaultDocument = updatedDoc,
                selectedFolderId = draggedFolderId
            )
        }
        return true
    }

    private fun refreshLiveTabInheritanceForSubtree(updatedDoc: NxtsVaultDocument, rootFolderId: String) {
        val subtreeFolderIds = collectDescendantFolderIds(updatedDoc.folders, rootFolderId)
        val subtreeConns = updatedDoc.connections
            .filter { it.parentFolderId in subtreeFolderIds && it.id !in updatedDoc.tombstonedObjectIds }
            .associateBy { it.id }

        tilingController.allActiveTabs().forEach { tab ->
            val affectedConn = subtreeConns[tab.connectionId] ?: return@forEach
            val refreshed = inheritanceResolver.resolveEffectiveProfile(updatedDoc, affectedConn)
            tilingController.updateTab(tab.tabId) {
                it.copy(
                    environmentTag = refreshed.effectiveEnvironmentBadge,
                    customColorHex = refreshed.effectiveColorAccentHex
                )
            }
        }
    }

    /**
     * Reorders [folderId] up or down among its sibling folders under the same `parentFolderId`.
     */
    fun reorderFolder(folderId: String, moveUp: Boolean): Boolean {
        if (_uiState.value.isVaultLocked || folderId == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) return false
        val doc = _uiState.value.vaultDocument
        val target = doc.folders.firstOrNull {
            it.id == folderId && it.id !in doc.tombstonedObjectIds
        } ?: return false
        val siblings = sortedSiblingFolders(doc.folders, target.parentFolderId, doc.tombstonedObjectIds)
            .toMutableList()
        val idx = siblings.indexOfFirst { it.id == folderId }
        val swapIdx = if (moveUp) idx - 1 else idx + 1
        if (idx < 0 || swapIdx !in siblings.indices) return false

        val tmp = siblings[idx]
        siblings[idx] = siblings[swapIdx]
        siblings[swapIdx] = tmp

        val updatedOrders = siblings.mapIndexed { i, f -> f.id to ((i + 1) * 10) }.toMap()
        activeVaultStore.updateVaultDocument { current ->
            bumpDocRevisionAndClock(current).copy(
                folders = current.folders.map { f ->
                    val newOrder = updatedOrders[f.id]
                    if (newOrder != null) f.copy(sortOrder = newOrder) else f
                }
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        return true
    }

    /**
     * Reorders [connectionId] up or down among its sibling connections in the same folder.
     */
    fun reorderConnection(connectionId: String, moveUp: Boolean): Boolean {
        if (_uiState.value.isVaultLocked) return false
        val doc = _uiState.value.vaultDocument
        val target = doc.connections.firstOrNull {
            it.id == connectionId && it.id !in doc.tombstonedObjectIds
        } ?: return false
        val siblings = sortedSiblingConnections(doc.connections, target.parentFolderId, doc.tombstonedObjectIds)
            .toMutableList()
        val idx = siblings.indexOfFirst { it.id == connectionId }
        val swapIdx = if (moveUp) idx - 1 else idx + 1
        if (idx < 0 || swapIdx !in siblings.indices) return false

        val tmp = siblings[idx]
        siblings[idx] = siblings[swapIdx]
        siblings[swapIdx] = tmp

        val now = System.currentTimeMillis()
        val updatedOrders = siblings.mapIndexed { i, c -> c.id to ((i + 1) * 10) }.toMap()
        activeVaultStore.updateVaultDocument { current ->
            bumpDocRevisionAndClock(current).copy(
                connections = current.connections.map { c ->
                    val newOrder = updatedOrders[c.id]
                    if (newOrder != null) c.copy(sortOrder = newOrder, updatedAtEpochMs = now) else c
                }
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        return true
    }

    /**
     * Moves & reorders [draggedConnectionId] directly before or after [targetConnectionId]
     * (moving it into `targetConnection.parentFolderId` if dragged across folders).
     */
    fun reorderConnectionRelativeTo(
        draggedConnectionId: String,
        targetConnectionId: String,
        placeAfter: Boolean
    ): Boolean {
        if (_uiState.value.isVaultLocked || draggedConnectionId == targetConnectionId) return false
        val doc = _uiState.value.vaultDocument
        val dragged = doc.connections.firstOrNull {
            it.id == draggedConnectionId && it.id !in doc.tombstonedObjectIds
        } ?: return false
        val target = doc.connections.firstOrNull {
            it.id == targetConnectionId && it.id !in doc.tombstonedObjectIds
        } ?: return false

        val destFolderId = target.parentFolderId
        val destSiblings = sortedSiblingConnections(doc.connections, destFolderId, doc.tombstonedObjectIds)
            .filterNot { it.id == draggedConnectionId }
            .toMutableList()
        val targetIdx = destSiblings.indexOfFirst { it.id == targetConnectionId }
        if (targetIdx < 0) return false
        val insertIdx = if (placeAfter) targetIdx + 1 else targetIdx
        val now = System.currentTimeMillis()
        val movedDragged = dragged.copy(parentFolderId = destFolderId, updatedAtEpochMs = now)
        destSiblings.add(insertIdx.coerceIn(0, destSiblings.size), movedDragged)

        val updatedById = destSiblings.mapIndexed { i, c ->
            c.id to c.copy(sortOrder = (i + 1) * 10, updatedAtEpochMs = now)
        }.toMap()

        activeVaultStore.updateVaultDocument { current ->
            bumpDocRevisionAndClock(current).copy(
                connections = current.connections.map { c -> updatedById[c.id] ?: c }
            )
        }
        val updatedDoc = activeVaultStore.snapshot.value.vaultDocument
        if (dragged.parentFolderId != destFolderId) {
            val refreshed = inheritanceResolver.resolveEffectiveProfile(
                updatedDoc,
                updatedById[draggedConnectionId] ?: movedDragged
            )
            tilingController.allActiveTabs()
                .filter { it.connectionId == draggedConnectionId }
                .forEach { tab ->
                    tilingController.updateTab(tab.tabId) {
                        it.copy(
                            environmentTag = refreshed.effectiveEnvironmentBadge,
                            customColorHex = refreshed.effectiveColorAccentHex
                        )
                    }
                }
        }
        _uiState.update {
            it.copy(
                vaultDocument = updatedDoc,
                selectedConnectionId = draggedConnectionId
            )
        }
        return true
    }

    fun reconnectSessionTab(tab: WorkspaceSessionTab) {
        sshControllers.remove(tab.tabId)?.close()
        rdpSessions.remove(tab.tabId)?.close()
        transferControllers.remove(tab.tabId)?.close()
        vncSessions.remove(tab.tabId)?.close()
        tabGatewayLeases.remove(tab.tabId)?.let { lease ->
            gatewayTunnelManager.closeTunnelLease(lease.leaseId)
        }
        tilingController.updateTab(tab.tabId) { it.copy(status = SessionLifecycleStatus.RECONNECTING) }
        tilingController.updateTab(tab.tabId) { it.copy(status = SessionLifecycleStatus.CONNECTED) }
        appendLog("[Session] Reconnected '${tab.title}' (${tab.subtitleHost})")
    }

    /**
     * Extracts [tabId] from the main workspace pane tree for tear-off into a freeform
     * [com.nexusts.app.DetachedSessionActivity] window while keeping its live SSH/RDP session running.
     */
    fun transferTabToDetachedWindow(tabId: String): WorkspaceSessionTab? {
        val extracted = tilingController.extractTabForDetachedWindow(tabId)
        if (extracted != null && tilingController.allActiveTabs().isEmpty()) {
            _uiState.update { it.copy(remoteKeyCaptureEnabled = false, isFullScreenSessionMode = false) }
        }
        return extracted
    }

    fun closeDetachedTabResources(tabId: String) {
        activeVaultStore.closeTabResources(tabId)
    }

    fun updateSidebarFilterQuery(query: String) {
        _uiState.update { it.copy(sidebarFilterQuery = query) }
    }

    fun setSidebarQuickFilter(filter: SidebarQuickFilter) {
        _uiState.update { it.copy(sidebarQuickFilter = filter) }
    }

    fun toggleFavoriteConnection(connectionId: String) {
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                connections = doc.connections.map {
                    if (it.id == connectionId) it.copy(favorite = !it.favorite, updatedAtEpochMs = System.currentTimeMillis()) else it
                }
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
    }

    fun launchConnectionInSplitDown(connection: VaultConnection) {
        val state = _uiState.value
        if (state.isVaultLocked) return
        if (requiresInteractiveConnectPrompt(state.vaultDocument, connection)) {
            _uiState.update { it.copy(connectWithOptionsTarget = connection) }
            return
        }
        val profile = inheritanceResolver.resolveEffectiveProfile(state.vaultDocument, connection)
        val uiKind = mapProtocolToUiKind(connection.protocol)
        val tab = WorkspaceSessionTab(
            connectionId = connection.id,
            title = connection.name,
            subtitleHost = connection.host,
            protocol = uiKind,
            status = SessionLifecycleStatus.CONNECTED,
            environmentTag = profile.effectiveEnvironmentBadge,
            customColorHex = profile.effectiveColorAccentHex,
            broadcastEnabled = state.broadcastModeEnabled
        )
        activeVaultStore.tabConnectionIds[tab.tabId] = connection.id
        recordRecentConnection(connection.id)
        _uiState.update {
            it.copy(
                activeRailId = "connections",
                selectedConnectionId = connection.id,
                remoteKeyCaptureEnabled = isInteractiveRemoteKeyProtocol(uiKind) || it.remoteKeyCaptureEnabled
            )
        }
        tilingController.splitPane(
            orientation = SplitOrientation.VERTICAL,
            tabForNewPane = tab
        )
        appendLog("[Connect] Split down ${connection.protocol.name} session '${connection.name}' (${connection.host})")
        runConfiguredLifecycleTask(connection.inheritable.preConnectTaskId, "Pre-Connect")
    }

    fun duplicateConnection(connection: VaultConnection) {
        val cloned = connection.copy(
            id = UUID.randomUUID().toString(),
            name = "${connection.name} (Copy)",
            updatedAtEpochMs = System.currentTimeMillis()
        )
        saveConnectionToVault(cloned)
    }

    fun duplicateFolder(folder: VaultFolder) {
        if (folder.id == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) return
        val cloned = folder.copy(
            id = UUID.randomUUID().toString(),
            name = "${folder.name} (Copy)",
            updatedAtEpochMs = System.currentTimeMillis()
        )
        saveFolderAndCredentialToVault(cloned)
    }

    fun openRenameFolderDialog(folder: VaultFolder) {
        if (_uiState.value.isVaultLocked || folder.id == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) return
        _uiState.update { it.copy(renamingFolderTarget = folder) }
    }

    fun dismissRenameFolderDialog() {
        _uiState.update { it.copy(renamingFolderTarget = null) }
    }

    /**
     * Fast, non-disruptive folder rename that updates [VaultFolder.name] and [VaultFolder.updatedAtEpochMs]
     * without tearing down active SSH/RDP sessions inside the folder.
     */
    fun renameFolder(folderId: String, newName: String): Boolean {
        if (_uiState.value.isVaultLocked || folderId == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) return false
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val currentDoc = _uiState.value.vaultDocument
        val existing = currentDoc.folders.firstOrNull {
            it.id == folderId && it.id !in currentDoc.tombstonedObjectIds
        } ?: return false
        if (existing.parentFolderId == null && VaultHierarchySanitizer.isReservedCredentialsFolderName(trimmed)) {
            _uiState.update {
                it.copy(
                    renamingFolderTarget = null,
                    userSnackbarMessage = "The root 'Credentials' folder is reserved for credentials."
                )
            }
            return false
        }
        if (existing.name == trimmed) {
            _uiState.update { it.copy(renamingFolderTarget = null) }
            return true
        }
        val now = System.currentTimeMillis()
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                folders = doc.folders.map { f ->
                    if (f.id == folderId) f.copy(name = trimmed, updatedAtEpochMs = now) else f
                }
            )
        }
        val updatedDoc = activeVaultStore.snapshot.value.vaultDocument
        val msg = "Renamed folder '${existing.name}' to '$trimmed'"
        _uiState.update {
            it.copy(
                vaultDocument = updatedDoc,
                renamingFolderTarget = null,
                userSnackbarMessage = msg
            )
        }
        appendLog("[Folder] $msg")
        return true
    }

    fun requestDeleteFolderConfirmation(folder: VaultFolder) {
        if (_uiState.value.isVaultLocked || folder.id == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) return
        _uiState.update { it.copy(deletingFolderTarget = folder) }
    }

    fun dismissDeleteFolderConfirmation() {
        _uiState.update { it.copy(deletingFolderTarget = null) }
    }

    fun deleteConnection(connectionId: String) {
        tilingController.allActiveTabs()
            .filter { it.connectionId == connectionId }
            .forEach { closeSessionTab(it.tabId) }
        evictLiveSessionsForConnections(setOf(connectionId))

        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                connections = doc.connections.filterNot { it.id == connectionId },
                recentConnectionIds = doc.recentConnectionIds.filterNot { it == connectionId },
                tombstonedObjectIds = doc.tombstonedObjectIds + connectionId
            )
        }
        _uiState.update { state ->
            val updatedDoc = activeVaultStore.snapshot.value.vaultDocument
            state.copy(
                vaultDocument = updatedDoc,
                selectedConnectionId = if (state.selectedConnectionId == connectionId) {
                    updatedDoc.connections.firstOrNull { it.id !in updatedDoc.tombstonedObjectIds }?.id
                } else {
                    state.selectedConnectionId
                }
            )
        }
        appendLog("[Vault] Deleted connection ($connectionId) with tombstone marker")
    }

    fun deleteFolder(folderId: String, deleteContents: Boolean = false) {
        if (folderId == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) return
        val currentDoc = _uiState.value.vaultDocument
        val targetFolder = currentDoc.folders.firstOrNull { it.id == folderId } ?: return

        if (deleteContents) {
            val subtreeFolderIds = collectDescendantFolderIds(currentDoc.folders, folderId)
            val deletedConnIds = currentDoc.connections
                .filter { it.parentFolderId in subtreeFolderIds }
                .map { it.id }
                .toSet()
            val deletedDynamicIds = currentDoc.dynamicFolders
                .filter { it.parentFolderId in subtreeFolderIds }
                .map { it.id }
                .toSet()

            tilingController.allActiveTabs()
                .filter { it.connectionId in deletedConnIds }
                .forEach { closeSessionTab(it.tabId) }
            evictLiveSessionsForConnections(deletedConnIds)

            val allTombstones = subtreeFolderIds + deletedConnIds + deletedDynamicIds
            activeVaultStore.updateVaultDocument { doc ->
                bumpDocRevisionAndClock(doc).copy(
                    folders = doc.folders.filterNot { it.id in subtreeFolderIds },
                    connections = doc.connections.filterNot { it.id in deletedConnIds },
                    recentConnectionIds = doc.recentConnectionIds.filterNot { it in deletedConnIds },
                    dynamicFolders = doc.dynamicFolders.filterNot { it.id in deletedDynamicIds },
                    tombstonedObjectIds = doc.tombstonedObjectIds + allTombstones
                )
            }
            val updatedDoc = activeVaultStore.snapshot.value.vaultDocument
            val msg = "Deleted folder '${targetFolder.name}' and ${deletedConnIds.size} connection(s)"
            _uiState.update { state ->
                state.copy(
                    deletingFolderTarget = if (state.deletingFolderTarget?.id in subtreeFolderIds) null else state.deletingFolderTarget,
                    renamingFolderTarget = if (state.renamingFolderTarget?.id in subtreeFolderIds) null else state.renamingFolderTarget,
                    movingFolderTarget = if (state.movingFolderTarget?.id in subtreeFolderIds) null else state.movingFolderTarget,
                    editingFolder = if (state.editingFolder?.id in subtreeFolderIds) null else state.editingFolder,
                    isFolderDialogVisible = if (state.editingFolder?.id in subtreeFolderIds) false else state.isFolderDialogVisible,
                    selectedFolderId = if (state.selectedFolderId in subtreeFolderIds) null else state.selectedFolderId,
                    selectedConnectionId = if (state.selectedConnectionId in deletedConnIds) {
                        updatedDoc.connections.firstOrNull { it.id !in updatedDoc.tombstonedObjectIds }?.id
                    } else {
                        state.selectedConnectionId
                    },
                    hydratedDynamicConnections = state.hydratedDynamicConnections - deletedDynamicIds,
                    vaultDocument = updatedDoc,
                    userSnackbarMessage = msg
                )
            }
            appendLog("[Folder] $msg")
            return
        }

        val fallbackParentId = targetFolder.parentFolderId
        val affectedConnIds = currentDoc.connections
            .filter { it.parentFolderId == folderId }
            .map { it.id }
            .toSet()

        evictLiveSessionsForConnections(affectedConnIds)

        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                folders = doc.folders
                    .filterNot { it.id == folderId }
                    .map { f ->
                        if (f.parentFolderId == folderId) f.copy(parentFolderId = fallbackParentId) else f
                    },
                connections = doc.connections.map { c ->
                    if (c.parentFolderId == folderId) {
                        c.copy(parentFolderId = fallbackParentId, updatedAtEpochMs = System.currentTimeMillis())
                    } else {
                        c
                    }
                },
                dynamicFolders = doc.dynamicFolders.map { df ->
                    if (df.parentFolderId == folderId) df.copy(parentFolderId = fallbackParentId) else df
                },
                tombstonedObjectIds = doc.tombstonedObjectIds + folderId
            )
        }
        _uiState.update { state ->
            state.copy(
                deletingFolderTarget = if (state.deletingFolderTarget?.id == folderId) null else state.deletingFolderTarget,
                renamingFolderTarget = if (state.renamingFolderTarget?.id == folderId) null else state.renamingFolderTarget,
                movingFolderTarget = if (state.movingFolderTarget?.id == folderId) null else state.movingFolderTarget,
                editingFolder = if (state.editingFolder?.id == folderId) null else state.editingFolder,
                isFolderDialogVisible = if (state.editingFolder?.id == folderId) false else state.isFolderDialogVisible,
                selectedFolderId = if (state.selectedFolderId == folderId) fallbackParentId else state.selectedFolderId,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument,
                userSnackbarMessage = "Deleted folder '${targetFolder.name}' (items moved up)"
            )
        }
        appendLog("[Folder] Deleted folder '${targetFolder.name}' and re-parented child items")
    }

    fun toggleDynamicFolder(dynamicFolder: DynamicFolder) {
        val currentlyOpen = _uiState.value.selectedDynamicFolderId == dynamicFolder.id
        _uiState.update { it.copy(selectedDynamicFolderId = if (currentlyOpen) null else dynamicFolder.id) }
        if (!currentlyOpen && !_uiState.value.hydratedDynamicConnections.containsKey(dynamicFolder.id)) {
            reloadDynamicFolder(dynamicFolder)
        }
    }

    fun reloadDynamicFolder(dynamicFolder: DynamicFolder, allowLocalScriptExecution: Boolean = false) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    inheritanceResolver.evaluateDynamicFolder(
                        dynamicFolder = dynamicFolder,
                        allowLocalScriptExecution = allowLocalScriptExecution
                    )
                }
            }.onSuccess { items ->
                _uiState.update { state ->
                    state.copy(
                        selectedDynamicFolderId = dynamicFolder.id,
                        hydratedDynamicConnections = state.hydratedDynamicConnections + (dynamicFolder.id to items)
                    )
                }
                appendLog("[DynamicFolder] Hydrated ${items.size} host(s) from '${dynamicFolder.name}'")
            }.onFailure { err ->
                reportException(err, "Dynamic Folder '${dynamicFolder.name}' Failed")
            }
        }
    }

    fun openNewDynamicFolderDialog() {
        if (_uiState.value.isVaultLocked) return
        _uiState.update {
            it.copy(
                editingDynamicFolder = null,
                isNewDynamicFolderDialogVisible = true
            )
        }
    }

    fun openEditDynamicFolderDialog(dynamicFolder: DynamicFolder) {
        if (_uiState.value.isVaultLocked) return
        _uiState.update {
            it.copy(
                editingDynamicFolder = dynamicFolder,
                isNewDynamicFolderDialogVisible = true
            )
        }
    }

    fun dismissNewDynamicFolderDialog() {
        _uiState.update {
            it.copy(
                editingDynamicFolder = null,
                isNewDynamicFolderDialogVisible = false
            )
        }
    }

    fun saveDynamicFolderToVault(dynamicFolder: DynamicFolder) {
        val stamped = dynamicFolder.copy(
            parentFolderId = dynamicFolder.parentFolderId?.takeUnless {
                it == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID
            },
            name = dynamicFolder.name.trim().ifBlank { "Dynamic Folder" },
            updatedAtEpochMs = System.currentTimeMillis()
        )
        activeVaultStore.updateVaultDocument { doc ->
            val exists = doc.dynamicFolders.any { it.id == stamped.id }
            val nextList = if (exists) {
                doc.dynamicFolders.map { if (it.id == stamped.id) stamped else it }
            } else {
                doc.dynamicFolders + stamped
            }
            bumpDocRevisionAndClock(doc).copy(
                dynamicFolders = nextList,
                tombstonedObjectIds = doc.tombstonedObjectIds - stamped.id
            )
        }
        _uiState.update {
            it.copy(
                editingDynamicFolder = null,
                isNewDynamicFolderDialogVisible = false,
                selectedDynamicFolderId = stamped.id,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument,
                userSnackbarMessage = "Saved dynamic folder '${stamped.name}'"
            )
        }
        reloadDynamicFolder(stamped)
        appendLog("[DynamicFolder] Saved '${stamped.name}' (${stamped.interpreter.name})")
    }

    fun duplicateDynamicFolder(dynamicFolder: DynamicFolder) {
        saveDynamicFolderToVault(
            dynamicFolder.copy(
                id = UUID.randomUUID().toString(),
                name = "${dynamicFolder.name} (Copy)",
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    fun deleteDynamicFolderFromVault(dynamicFolderId: String) {
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                dynamicFolders = doc.dynamicFolders.filterNot { it.id == dynamicFolderId },
                tombstonedObjectIds = doc.tombstonedObjectIds + dynamicFolderId
            )
        }
        _uiState.update { state ->
            state.copy(
                editingDynamicFolder = state.editingDynamicFolder.takeUnless { it?.id == dynamicFolderId },
                selectedDynamicFolderId = state.selectedDynamicFolderId.takeUnless { it == dynamicFolderId },
                hydratedDynamicConnections = state.hydratedDynamicConnections - dynamicFolderId,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
            )
        }
        appendLog("[DynamicFolder] Deleted dynamic folder ($dynamicFolderId)")
    }

    fun openNewSmartFolderDialog() {
        if (_uiState.value.isVaultLocked) return
        _uiState.update {
            it.copy(
                editingSmartFolder = null,
                isNewSmartFolderDialogVisible = true
            )
        }
    }

    fun openEditSmartFolderDialog(smartFolder: SmartFolder) {
        if (_uiState.value.isVaultLocked) return
        _uiState.update {
            it.copy(
                editingSmartFolder = smartFolder,
                isNewSmartFolderDialogVisible = true
            )
        }
    }

    fun dismissNewSmartFolderDialog() {
        _uiState.update {
            it.copy(
                editingSmartFolder = null,
                isNewSmartFolderDialogVisible = false
            )
        }
    }

    fun saveSmartFolderToVault(smartFolder: SmartFolder) {
        val regexError = inheritanceResolver.validateSmartFolderRegex(smartFolder.filter.hostPatternRegex)
        if (regexError != null) {
            reportStructuredError(
                NexusAppError(
                    category = NexusErrorCategory.VALIDATION,
                    title = "Invalid Intelligent Folder Regex",
                    userMessage = "The host regular expression pattern '${smartFolder.filter.hostPatternRegex}' is invalid.",
                    remediationHint = "Fix or clear the regex pattern so all parentheses and character classes are balanced.",
                    technicalDetails = regexError
                )
            )
            return
        }
        val stamped = smartFolder.copy(
            name = smartFolder.name.trim().ifBlank { "Intelligent Folder" },
            updatedAtEpochMs = System.currentTimeMillis()
        )
        activeVaultStore.updateVaultDocument { doc ->
            val exists = doc.smartFolders.any { it.id == stamped.id }
            val nextList = if (exists) {
                doc.smartFolders.map { if (it.id == stamped.id) stamped else it }
            } else {
                doc.smartFolders + stamped
            }
            bumpDocRevisionAndClock(doc).copy(
                smartFolders = nextList,
                tombstonedObjectIds = doc.tombstonedObjectIds - stamped.id
            )
        }
        _uiState.update {
            it.copy(
                editingSmartFolder = null,
                isNewSmartFolderDialogVisible = false,
                selectedSmartFolderId = stamped.id,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument,
                userSnackbarMessage = "Saved intelligent folder '${stamped.name}'"
            )
        }
        appendLog("[SmartFolder] Saved dynamic filter '${stamped.name}'")
    }

    fun duplicateSmartFolder(smartFolder: SmartFolder) {
        saveSmartFolderToVault(
            smartFolder.copy(
                id = UUID.randomUUID().toString(),
                name = "${smartFolder.name} (Copy)",
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    fun deleteSmartFolderFromVault(smartFolderId: String) {
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                smartFolders = doc.smartFolders.filterNot { it.id == smartFolderId },
                tombstonedObjectIds = doc.tombstonedObjectIds + smartFolderId
            )
        }
        _uiState.update { state ->
            state.copy(
                editingSmartFolder = state.editingSmartFolder.takeUnless { it?.id == smartFolderId },
                selectedSmartFolderId = state.selectedSmartFolderId.takeUnless { it == smartFolderId },
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
            )
        }
        appendLog("[SmartFolder] Deleted smart folder ($smartFolderId)")
    }

    fun openNewTemplateDialog() {
        if (_uiState.value.isVaultLocked) return
        _uiState.update { it.copy(isNewTemplateDialogVisible = true) }
    }

    fun dismissNewTemplateDialog() {
        _uiState.update { it.copy(isNewTemplateDialogVisible = false) }
    }

    fun saveTemplateToVault(template: ConnectionTemplate) {
        activeVaultStore.updateVaultDocument { doc ->
            val exists = doc.templates.any { it.id == template.id }
            val nextTemplates = if (exists) {
                doc.templates.map { if (it.id == template.id) template else it }
            } else {
                doc.templates + template
            }
            bumpDocRevisionAndClock(doc).copy(
                templates = nextTemplates,
                tombstonedObjectIds = doc.tombstonedObjectIds - template.id
            )
        }
        _uiState.update {
            it.copy(
                isNewTemplateDialogVisible = false,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
            )
        }
        appendLog("[Templates] Saved reusable Royal TS template '${template.name}' (${template.protocol.name})")
    }

    fun duplicateTemplate(template: ConnectionTemplate) {
        saveTemplateToVault(
            template.copy(
                id = UUID.randomUUID().toString(),
                name = "${template.name} (Copy)"
            )
        )
    }

    fun deleteTemplateFromVault(templateId: String) {
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                templates = doc.templates.filterNot { it.id == templateId },
                tombstonedObjectIds = doc.tombstonedObjectIds + templateId
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[Templates] Deleted template ($templateId)")
    }

    fun createConnectionFromTemplate(template: ConnectionTemplate) {
        if (_uiState.value.isVaultLocked) return
        val draft = VaultConnection(
            name = "${template.name} Host",
            host = "",
            protocol = template.protocol,
            osFamily = if (template.protocol == VaultProtocolType.RDP) "Windows" else "Linux",
            inheritable = template.inheritable.copy(
                displayColorAccentHex = template.colorAccentHex ?: template.inheritable.displayColorAccentHex
            ),
            settings = template.settings
        )
        _uiState.update {
            it.copy(
                editingConnection = draft,
                isNewConnectionDialogVisible = true
            )
        }
    }

    fun addTodoItem(title: String, targetObjectId: String? = _uiState.value.selectedConnectionId, priority: String = "NORMAL") {
        if (title.isBlank()) return
        val item = VaultTodoItem(
            targetObjectId = targetObjectId,
            title = title.trim(),
            priority = priority
        )
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                todoItems = listOf(item) + doc.todoItems
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[To-Do] Added task '${item.title}'")
    }

    fun toggleTodoItem(todoId: String) {
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                todoItems = doc.todoItems.map {
                    if (it.id == todoId) it.copy(completed = !it.completed, updatedAtEpochMs = System.currentTimeMillis()) else it
                }
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
    }

    fun deleteTodoItem(todoId: String) {
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                todoItems = doc.todoItems.filterNot { it.id == todoId },
                tombstonedObjectIds = doc.tombstonedObjectIds + todoId
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[To-Do] Removed task ($todoId)")
    }

    fun updateConnectionNotesAndCustomProps(
        connectionId: String,
        notes: String,
        customProps: List<CustomPropertyEntry>,
        colorAccentHex: String?
    ) {
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                connections = doc.connections.map { conn ->
                    if (conn.id == connectionId) {
                        conn.copy(
                            notes = notes,
                            inheritable = conn.inheritable.copy(
                                displayColorAccentHex = colorAccentHex,
                                customProperties = customProps
                            ),
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    } else {
                        conn
                    }
                }
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[Inspector] Updated notes & custom properties for connection $connectionId")
    }

    fun deleteCustomProperty(connectionId: String, key: String) {
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                connections = doc.connections.map { conn ->
                    if (conn.id == connectionId) {
                        conn.copy(
                            inheritable = conn.inheritable.copy(
                                customProperties = conn.inheritable.customProperties.filterNot {
                                    it.key.equals(key, ignoreCase = true)
                                }
                            ),
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    } else {
                        conn
                    }
                }
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[Inspector] Removed custom property '$key'")
    }

    private fun recordRecentConnection(connectionId: String) {
        activeVaultStore.updateMetadataWithoutMarkingInitialized { doc ->
            val updatedRecents = (listOf(connectionId) + doc.recentConnectionIds.filterNot { it == connectionId }).take(8)
            doc.copy(recentConnectionIds = updatedRecents)
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
    }

    fun launchQuickCandidate(
        candidate: QuickConnectCandidate,
        placement: QuickLaunchPlacement,
        onDetachWindowRequested: (WorkspaceSessionTab) -> Unit
    ) {
        val state = _uiState.value
        if (state.isVaultLocked) return
        val doc = state.vaultDocument
        val existing = doc.connections.firstOrNull { it.id == candidate.id && it.id !in doc.tombstonedObjectIds }
            ?: state.hydratedDynamicConnections.values.flatten().firstOrNull { it.id == candidate.id }
        if (existing != null) {
            launchConnection(existing, placement, onDetachWindowRequested)
            return
        }

        val resolvedHost = candidate.parsedHost?.takeIf { it.isNotBlank() } ?: candidate.subtitle
        val resolvedVaultProto = candidate.parsedProtocolSubtype?.let { sub ->
            VaultProtocolType.entries.firstOrNull { it.name.equals(sub, ignoreCase = true) }
        } ?: defaultVaultProtocolForUiKind(candidate.protocol)
        val effectiveUiKind = mapProtocolToUiKind(resolvedVaultProto)

        val adHocTab = WorkspaceSessionTab(
            connectionId = candidate.id,
            title = candidate.title,
            subtitleHost = resolvedHost,
            protocol = effectiveUiKind,
            status = SessionLifecycleStatus.CONNECTED,
            broadcastEnabled = state.broadcastModeEnabled
        )
        if (candidate.isAdHocUri ||
            candidate.parsedHost != null ||
            candidate.parsedPort != null ||
            !candidate.parsedUsername.isNullOrBlank() ||
            !candidate.parsedDomain.isNullOrBlank() ||
            candidate.parsedProtocolSubtype != null
        ) {
            adHocOverrides[adHocTab.tabId] = AdHocLaunchOverride(
                adHocUsername = candidate.parsedUsername.orEmpty(),
                adHocDomain = candidate.parsedDomain.orEmpty(),
                adHocHost = candidate.parsedHost ?: resolvedHost,
                adHocPort = candidate.parsedPort,
                adHocProtocolType = resolvedVaultProto
            )
        }
        _uiState.update {
            it.copy(
                activeRailId = "connections",
                remoteKeyCaptureEnabled = (placement != QuickLaunchPlacement.DETACHED_NEW_WINDOW && isInteractiveRemoteKeyProtocol(effectiveUiKind)) || it.remoteKeyCaptureEnabled
            )
        }
        appendLog("[QuickConnect] Launched ad-hoc ${resolvedVaultProto.name} -> $resolvedHost")
        when (placement) {
            QuickLaunchPlacement.ACTIVE_PANE_TAB -> tilingController.openTab(adHocTab)
            QuickLaunchPlacement.SPLIT_RIGHT_PANE -> tilingController.splitPane(
                orientation = SplitOrientation.HORIZONTAL,
                tabForNewPane = adHocTab
            )
            QuickLaunchPlacement.DETACHED_NEW_WINDOW -> onDetachWindowRequested(adHocTab)
        }
    }

    fun closeSessionTab(tabId: String) {
        val closingTab = tilingController.allActiveTabs().firstOrNull { it.tabId == tabId }
        val conn = closingTab?.let { t ->
            _uiState.value.vaultDocument.connections.firstOrNull { it.id == t.connectionId }
        }
        activeVaultStore.closeTabResources(tabId)
        tilingController.closeTab(tabId)
        if (tilingController.allActiveTabs().isEmpty()) {
            _uiState.update { it.copy(remoteKeyCaptureEnabled = false, isFullScreenSessionMode = false) }
        }
        runConfiguredLifecycleTask(conn?.inheritable?.postDisconnectTaskId, "Post-Disconnect")
    }

    fun selectTabGlobally(tabId: String) {
        if (tilingController.selectTabById(tabId)) {
            val selected = tilingController.focusedOrFirstActiveTab()
            if (selected != null) {
                _uiState.update { state ->
                    state.copy(
                        selectedConnectionId = selected.connectionId,
                        remoteKeyCaptureEnabled = isInteractiveRemoteKeyProtocol(selected.protocol) || state.remoteKeyCaptureEnabled
                    )
                }
            }
        }
    }

    fun enterFullScreenForTab(tabId: String) {
        if (_uiState.value.isVaultLocked) return
        if (tilingController.selectTabById(tabId)) {
            _uiState.update { it.copy(isFullScreenSessionMode = true) }
            appendLog("[Workspace] Entered full-screen session mode")
        }
    }

    fun toggleFullScreenSessionMode() {
        if (_uiState.value.isVaultLocked) return
        val hasTabs = tilingController.allActiveTabs().isNotEmpty()
        _uiState.update { current ->
            val next = if (!hasTabs) false else !current.isFullScreenSessionMode
            current.copy(isFullScreenSessionMode = next)
        }
    }

    fun exitFullScreenSessionMode() {
        _uiState.update { it.copy(isFullScreenSessionMode = false) }
    }

    fun handleShortcut(
        action: NexusShortcutAction,
        onOpenVaultPicker: (() -> Unit)? = null,
        onSyncDriveNow: (() -> Unit)? = null,
        onDetachActiveTab: (WorkspaceSessionTab) -> Unit = {}
    ) {
        if (_uiState.value.isVaultLocked) {
            if (action == NexusShortcutAction.OPEN_VAULT_DOCUMENT) {
                onOpenVaultPicker?.invoke()
            }
            return
        }

        when (action) {
            NexusShortcutAction.QUICK_CONNECT_PALETTE ->
                _uiState.update { it.copy(isQuickConnectVisible = !it.isQuickConnectVisible) }
            NexusShortcutAction.NEW_CONNECTION_DIALOG ->
                _uiState.update {
                    val nextOpen = !it.isNewConnectionDialogVisible
                    it.copy(
                        editingConnection = null,
                        newConnectionDefaultFolderId = null,
                        newConnectionDefaultProtocol = VaultProtocolType.RDP,
                        isNewConnectionDialogVisible = nextOpen
                    )
                }
            NexusShortcutAction.NEW_LOCAL_TERMINAL ->
                launchLocalShellTab()
            NexusShortcutAction.OPEN_VAULT_DOCUMENT ->
                onOpenVaultPicker?.invoke()
            NexusShortcutAction.LOCK_ALL_VAULTS ->
                openLockVaultDialog()
            NexusShortcutAction.SYNC_GOOGLE_DRIVE_NOW -> {
                appendLog("[Shortcut] Triggering Google Drive API v3 delta-sync…")
                onSyncDriveNow?.invoke()
            }
            NexusShortcutAction.RELEASE_REMOTE_KEY_CAPTURE ->
                _uiState.update { it.copy(remoteKeyCaptureEnabled = false) }
            NexusShortcutAction.TOGGLE_SIDEBAR_TREE ->
                _uiState.update { it.copy(isSidebarVisible = !it.isSidebarVisible) }
            NexusShortcutAction.TOGGLE_PROPERTY_INSPECTOR ->
                _uiState.update { it.copy(isInspectorVisible = !it.isInspectorVisible) }
            NexusShortcutAction.TOGGLE_BOTTOM_LOG_DRAWER ->
                _uiState.update { it.copy(isBottomDrawerVisible = !it.isBottomDrawerVisible) }
            NexusShortcutAction.SPLIT_PANE_VERTICAL -> {
                _uiState.update { it.copy(activeRailId = "connections", isFullScreenSessionMode = false) }
                if (tilingController.allActiveTabs().isEmpty()) {
                    _uiState.update { it.copy(isQuickConnectVisible = true) }
                } else {
                    tilingController.splitPane(orientation = SplitOrientation.HORIZONTAL)
                }
            }
            NexusShortcutAction.SPLIT_PANE_HORIZONTAL -> {
                _uiState.update { it.copy(activeRailId = "connections", isFullScreenSessionMode = false) }
                if (tilingController.allActiveTabs().isEmpty()) {
                    _uiState.update { it.copy(isQuickConnectVisible = true) }
                } else {
                    tilingController.splitPane(orientation = SplitOrientation.VERTICAL)
                }
            }
            NexusShortcutAction.FOCUS_NEXT_PANE ->
                tilingController.focusNextPane()
            NexusShortcutAction.NEXT_TAB ->
                tilingController.selectNextTabInFocusedPane()
            NexusShortcutAction.PREVIOUS_TAB ->
                tilingController.selectPreviousTabInFocusedPane()
            NexusShortcutAction.TOGGLE_BROADCAST_INPUT ->
                toggleGlobalBroadcast()
            NexusShortcutAction.CLOSE_ACTIVE_TAB -> {
                val focusedId = tilingController.focusedPaneId.value
                val activeTab = findFocusedActiveTab(focusedId)
                if (activeTab != null) {
                    closeSessionTab(activeTab.tabId)
                } else {
                    tilingController.closeEmptyPane(focusedId)
                }
            }
            NexusShortcutAction.DETACH_TAB_TO_NEW_WINDOW -> {
                val activeTab = findFocusedActiveTab(tilingController.focusedPaneId.value)
                if (activeTab != null) {
                    transferTabToDetachedWindow(activeTab.tabId)?.let(onDetachActiveTab)
                }
            }
            NexusShortcutAction.TOGGLE_FULLSCREEN_SESSION ->
                toggleFullScreenSessionMode()
            NexusShortcutAction.ZOOM_IN_SESSION ->
                tilingController.adjustFocusedTabZoom(+0.15f)
            NexusShortcutAction.ZOOM_OUT_SESSION ->
                tilingController.adjustFocusedTabZoom(-0.15f)
            NexusShortcutAction.ZOOM_RESET_SESSION ->
                tilingController.adjustFocusedTabZoom(null)
        }
    }

    fun saveConnectionToVault(connection: VaultConnection) {
        if (connection.host.isBlank() && connection.protocol != VaultProtocolType.WEB_PAGE) {
            reportStructuredError(
                NexusAppError(
                    category = NexusErrorCategory.VALIDATION,
                    title = "Invalid Connection Host",
                    userMessage = "Hostname or IP address cannot be empty.",
                    remediationHint = "Enter a valid hostname, FQDN, or IPv4/IPv6 address before saving."
                )
            )
            return
        }
        val safeParentFolderId = connection.parentFolderId?.takeUnless {
            it == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID
        }
        evictLiveSessionsForConnections(setOf(connection.id))

        var wasUpdated = false
        var finalSavedConn: VaultConnection = connection
        activeVaultStore.updateVaultDocument { doc ->
            val exists = doc.connections.any { it.id == connection.id }
            wasUpdated = exists
            val effectiveSortOrder = if (!exists && connection.sortOrder == 0) {
                (sortedSiblingConnections(doc.connections, safeParentFolderId, doc.tombstonedObjectIds)
                    .maxOfOrNull { it.sortOrder } ?: 0) + 10
            } else {
                connection.sortOrder
            }
            val stamped = connection.copy(
                name = connection.name.trim().ifBlank { connection.host.trim() },
                host = connection.host.trim(),
                parentFolderId = safeParentFolderId,
                sortOrder = effectiveSortOrder,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            finalSavedConn = stamped
            val updatedConnections = if (exists) {
                doc.connections.map { if (it.id == stamped.id) stamped else it }
            } else {
                doc.connections + stamped
            }
            bumpDocRevisionAndClock(doc).copy(
                connections = updatedConnections,
                tombstonedObjectIds = doc.tombstonedObjectIds - stamped.id
            )
        }
        val stamped = finalSavedConn
        _uiState.update { state ->
            state.copy(
                activeRailId = "connections",
                selectedConnectionId = stamped.id,
                isNewConnectionDialogVisible = false,
                editingConnection = null,
                newConnectionDefaultFolderId = null,
                newConnectionDefaultProtocol = VaultProtocolType.RDP,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
            )
        }
        val refreshedProfile = inheritanceResolver.resolveEffectiveProfile(
            activeVaultStore.snapshot.value.vaultDocument,
            stamped
        )
        tilingController.allActiveTabs()
            .filter { it.connectionId == stamped.id }
            .forEach { tab ->
                tilingController.updateTab(tab.tabId) {
                    it.copy(
                        title = stamped.name,
                        subtitleHost = stamped.host,
                        protocol = mapProtocolToUiKind(stamped.protocol),
                        environmentTag = refreshedProfile.effectiveEnvironmentBadge,
                        customColorHex = refreshedProfile.effectiveColorAccentHex
                    )
                }
            }
        val verb = if (wasUpdated) "Updated" else "Added"
        appendLog("[Vault] $verb ${stamped.protocol.name} connection '${stamped.name}' (${stamped.host})")
    }

    fun validateFolderName(name: String, parentFolderId: String?): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Folder name cannot be empty."
        if (parentFolderId == null && VaultHierarchySanitizer.isReservedCredentialsFolderName(trimmed)) {
            return "The root 'Credentials' folder is reserved for credentials at the bottom of the sidebar."
        }
        return null
    }

    fun saveFolderAndCredentialToVault(folder: VaultFolder, inlineNewCredential: VaultCredential? = null) {
        if (folder.id == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID) return
        val safeParentId = folder.parentFolderId?.takeUnless {
            it == VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID
        }
        val trimmedName = folder.name.trim()
        if (trimmedName.isEmpty()) return
        if (safeParentId == null && VaultHierarchySanitizer.isReservedCredentialsFolderName(trimmedName)) {
            _uiState.update {
                it.copy(userSnackbarMessage = "The 'Credentials' system folder can only hold credentials.")
            }
            return
        }

        val normalizedFolder = folder.copy(
            name = trimmedName,
            parentFolderId = safeParentId
        )
        val baseFolder = if (inlineNewCredential != null) {
            normalizedFolder.copy(
                inheritable = normalizedFolder.inheritable.copy(
                    credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                    explicitCredentialId = inlineNewCredential.id
                )
            )
        } else {
            normalizedFolder
        }

        val currentDoc = _uiState.value.vaultDocument
        val affectedFolderIds = collectDescendantFolderIds(currentDoc.folders + baseFolder, baseFolder.id)
        val affectedConnIds = currentDoc.connections
            .filter { it.parentFolderId in affectedFolderIds }
            .map { it.id }
            .toSet()
        evictLiveSessionsForConnections(affectedConnIds)

        activeVaultStore.updateVaultDocument { doc ->
            val updatedCredentials = if (inlineNewCredential != null) {
                doc.credentials + inlineNewCredential.copy(parentFolderId = null)
            } else {
                doc.credentials
            }
            val folderExists = doc.folders.any { it.id == baseFolder.id }
            val finalFolder = if (!folderExists && baseFolder.sortOrder == 0) {
                val nextOrder = (sortedSiblingFolders(doc.folders, baseFolder.parentFolderId, doc.tombstonedObjectIds)
                    .maxOfOrNull { it.sortOrder } ?: 0) + 10
                baseFolder.copy(sortOrder = nextOrder)
            } else {
                baseFolder
            }
            val updatedFolders = if (folderExists) {
                doc.folders.map { if (it.id == finalFolder.id) finalFolder else it }
            } else {
                doc.folders + finalFolder
            }
            bumpDocRevisionAndClock(doc).copy(
                folders = updatedFolders,
                credentials = updatedCredentials,
                tombstonedObjectIds = doc.tombstonedObjectIds - finalFolder.id
            )
        }
        _uiState.update { state ->
            state.copy(
                isFolderDialogVisible = false,
                editingFolder = null,
                newFolderDefaultParentId = null,
                selectedFolderId = baseFolder.id,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
            )
        }
        val credSummary = inlineNewCredential?.let { " + saved folder credential '${it.name}'" }.orEmpty()
        appendLog("[Folder] Saved folder '${baseFolder.name}' (${baseFolder.inheritable.credentialMode.name})$credSummary")
    }

    fun openNewCredentialDialog() {
        if (_uiState.value.isVaultLocked) return
        _uiState.update {
            it.copy(
                editingCredential = null,
                isCredentialDialogVisible = true
            )
        }
    }

    fun openEditCredentialDialog(credential: VaultCredential) {
        if (_uiState.value.isVaultLocked) return
        _uiState.update {
            it.copy(
                editingCredential = credential,
                isCredentialDialogVisible = true
            )
        }
    }

    fun dismissCredentialDialog() {
        _uiState.update {
            it.copy(
                editingCredential = null,
                isCredentialDialogVisible = false
            )
        }
    }

    fun saveCredentialFromDialog(credential: VaultCredential) {
        val cleanCred = credential.copy(
            parentFolderId = null,
            name = credential.name.trim().ifBlank { credential.username.trim().ifBlank { "Credential" } },
            updatedAtEpochMs = System.currentTimeMillis()
        )
        val exists = _uiState.value.vaultDocument.credentials.any { it.id == cleanCred.id }
        if (exists) {
            updateCredentialInVault(cleanCred)
        } else {
            addCredentialToVault(cleanCred)
        }
        _uiState.update {
            it.copy(
                editingCredential = null,
                isCredentialDialogVisible = false,
                selectedFolderId = VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID,
                userSnackbarMessage = "Saved credential '${cleanCred.name}'"
            )
        }
    }

    fun reorderCredentialRelativeTo(
        draggedCredentialId: String,
        targetCredentialId: String,
        placeAfter: Boolean
    ): Boolean {
        if (_uiState.value.isVaultLocked || draggedCredentialId == targetCredentialId) return false
        val creds = _uiState.value.vaultDocument.credentials.toMutableList()
        val draggedIdx = creds.indexOfFirst { it.id == draggedCredentialId }
        if (draggedIdx < 0) return false
        val dragged = creds.removeAt(draggedIdx)
        val targetIdx = creds.indexOfFirst { it.id == targetCredentialId }
        if (targetIdx < 0) return false
        val insertIdx = (if (placeAfter) targetIdx + 1 else targetIdx).coerceIn(0, creds.size)
        creds.add(insertIdx, dragged)
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(credentials = creds)
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        return true
    }

    fun addCredentialToVault(credential: VaultCredential) {
        val normalized = credential.copy(parentFolderId = null)
        activeVaultStore.updateVaultDocument { doc ->
            val exists = doc.credentials.any { it.id == normalized.id }
            val nextCreds = if (exists) {
                doc.credentials.map { if (it.id == normalized.id) normalized else it }
            } else {
                doc.credentials + normalized
            }
            bumpDocRevisionAndClock(doc).copy(
                credentials = nextCreds,
                tombstonedObjectIds = doc.tombstonedObjectIds - normalized.id
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[Credentials] Stored '${normalized.name}' (${normalized.sourceKind.name})")
    }

    fun duplicateCredential(credential: VaultCredential) {
        addCredentialToVault(
            credential.copy(
                id = UUID.randomUUID().toString(),
                parentFolderId = null,
                name = "${credential.name} (Copy)",
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    fun updateCredentialInVault(credential: VaultCredential) {
        val docNow = _uiState.value.vaultDocument
        val affectedConns = docNow.connections
            .filter { conn ->
                inheritanceResolver.resolveEffectiveProfile(docNow, conn).effectiveCredential?.id == credential.id
            }
            .map { it.id }
            .toSet()
        evictLiveSessionsForConnections(affectedConns)

        val stamped = credential.copy(parentFolderId = null, updatedAtEpochMs = System.currentTimeMillis())
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                credentials = doc.credentials.map { if (it.id == stamped.id) stamped else it }
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[Credentials] Updated '${stamped.name}'")
    }

    fun deleteCredentialFromVault(credentialId: String) {
        val docNow = _uiState.value.vaultDocument
        val affectedConns = docNow.connections
            .filter { conn ->
                inheritanceResolver.resolveEffectiveProfile(docNow, conn).effectiveCredential?.id == credentialId
            }
            .map { it.id }
            .toSet()
        evictLiveSessionsForConnections(affectedConns)

        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                credentials = doc.credentials.filterNot { it.id == credentialId },
                folders = doc.folders.map { f ->
                    if (f.inheritable.explicitCredentialId == credentialId) {
                        f.copy(
                            inheritable = f.inheritable.copy(
                                credentialMode = InheritanceMode.INHERIT_FROM_PARENT,
                                explicitCredentialId = null
                            )
                        )
                    } else {
                        f
                    }
                },
                connections = doc.connections.map { c ->
                    if (c.inheritable.explicitCredentialId == credentialId) {
                        c.copy(
                            inheritable = c.inheritable.copy(
                                credentialMode = InheritanceMode.INHERIT_FROM_PARENT,
                                explicitCredentialId = null
                            )
                        )
                    } else {
                        c
                    }
                },
                gateways = doc.gateways.map { gw ->
                    if (gw.credentialId == credentialId) gw.copy(credentialId = null) else gw
                },
                tombstonedObjectIds = doc.tombstonedObjectIds + credentialId
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[Credentials] Deleted credential ($credentialId)")
    }

    fun addGatewayToVault(gateway: SecureGatewayConfig) {
        activeVaultStore.updateVaultDocument { doc ->
            val exists = doc.gateways.any { it.id == gateway.id }
            val nextGateways = if (exists) {
                doc.gateways.map { if (it.id == gateway.id) gateway else it }
            } else {
                doc.gateways + gateway
            }
            bumpDocRevisionAndClock(doc).copy(
                gateways = nextGateways,
                tombstonedObjectIds = doc.tombstonedObjectIds - gateway.id
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[Gateway] Registered ${gateway.kind.name} '${gateway.name}' (${gateway.host}:${gateway.port})")
    }

    fun updateGatewayInVault(gateway: SecureGatewayConfig) {
        addGatewayToVault(gateway)
    }

    fun deleteGatewayFromVault(gatewayId: String) {
        gatewayTunnelManager.activeLeases.value
            .filter { it.gatewayId == gatewayId }
            .forEach { gatewayTunnelManager.closeTunnelLease(it.leaseId) }

        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                gateways = doc.gateways.filterNot { it.id == gatewayId },
                folders = doc.folders.map { f ->
                    if (f.inheritable.explicitGatewayId == gatewayId) {
                        f.copy(
                            inheritable = f.inheritable.copy(
                                gatewayMode = InheritanceMode.INHERIT_FROM_PARENT,
                                explicitGatewayId = null
                            )
                        )
                    } else {
                        f
                    }
                },
                connections = doc.connections.map { c ->
                    if (c.inheritable.explicitGatewayId == gatewayId) {
                        c.copy(
                            inheritable = c.inheritable.copy(
                                gatewayMode = InheritanceMode.INHERIT_FROM_PARENT,
                                explicitGatewayId = null
                            )
                        )
                    } else {
                        c
                    }
                },
                tombstonedObjectIds = doc.tombstonedObjectIds + gatewayId
            )
        }
        _uiState.update {
            it.copy(
                gatewayHealthReports = it.gatewayHealthReports - gatewayId,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
            )
        }
        appendLog("[Gateway] Deleted gateway ($gatewayId)")
    }

    fun probeGatewayLatency(gateway: SecureGatewayConfig) {
        viewModelScope.launch {
            val cred = _uiState.value.vaultDocument.credentials.firstOrNull { it.id == gateway.credentialId }
            val report = gatewayTunnelManager.probeGatewayHealth(gateway, cred)
            _uiState.update { state ->
                state.copy(gatewayHealthReports = state.gatewayHealthReports + (gateway.id to report))
            }
            appendLog("[Gateway Probe] ${gateway.name}: reachable=${report.reachable}, rtt=${report.latencyMs}ms (${report.protocolBanner})")
        }
    }

    fun openGatewayLoopbackLease(
        gateway: SecureGatewayConfig,
        targetHost: String = gateway.host,
        targetPort: Int = gateway.port
    ) {
        viewModelScope.launch {
            val cred = _uiState.value.vaultDocument.credentials.firstOrNull { it.id == gateway.credentialId }
            runCatching {
                gatewayTunnelManager.openLoopbackTunnelLease(
                    gateway = gateway,
                    credential = cred,
                    targetHost = targetHost.trim().ifBlank { gateway.host },
                    targetPort = targetPort.coerceIn(1, 65535)
                )
            }.onSuccess { lease ->
                appendLog("[Gateway Lease] Bound ${lease.localBindHost}:${lease.localBindPort} -> ${lease.remoteTargetHost}:${lease.remoteTargetPort} via '${gateway.name}'")
            }.onFailure { err ->
                reportException(err, "Gateway Tunnel Failed (${gateway.name})")
            }
        }
    }

    fun closeGatewayLease(leaseId: String) {
        gatewayTunnelManager.closeTunnelLease(leaseId)
        appendLog("[Gateway Lease] Closed loopback relay ($leaseId)")
    }

    fun addCommandTaskToVault(task: CommandTask) {
        activeVaultStore.updateVaultDocument { doc ->
            val exists = doc.commandTasks.any { it.id == task.id }
            val nextTasks = if (exists) {
                doc.commandTasks.map { if (it.id == task.id) task else it }
            } else {
                doc.commandTasks + task
            }
            bumpDocRevisionAndClock(doc).copy(
                commandTasks = nextTasks,
                tombstonedObjectIds = doc.tombstonedObjectIds - task.id
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[Automation] Saved task '${task.name}'")
    }

    fun updateCommandTaskInVault(task: CommandTask) {
        addCommandTaskToVault(task)
    }

    fun deleteCommandTask(taskId: String) {
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                commandTasks = doc.commandTasks.filterNot { it.id == taskId },
                tombstonedObjectIds = doc.tombstonedObjectIds + taskId
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[Automation] Deleted command task ($taskId)")
    }

    fun addKeySequenceTaskToVault(macro: KeySequenceTask) {
        activeVaultStore.updateVaultDocument { doc ->
            val exists = doc.keySequenceTasks.any { it.id == macro.id }
            val nextMacros = if (exists) {
                doc.keySequenceTasks.map { if (it.id == macro.id) macro else it }
            } else {
                doc.keySequenceTasks + macro
            }
            bumpDocRevisionAndClock(doc).copy(
                keySequenceTasks = nextMacros,
                tombstonedObjectIds = doc.tombstonedObjectIds - macro.id
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[KeySequence] Saved macro '${macro.name}'")
    }

    fun updateKeySequenceTaskInVault(macro: KeySequenceTask) {
        addKeySequenceTaskToVault(macro)
    }

    fun deleteKeySequenceTask(macroId: String) {
        activeVaultStore.updateVaultDocument { doc ->
            bumpDocRevisionAndClock(doc).copy(
                keySequenceTasks = doc.keySequenceTasks.filterNot { it.id == macroId },
                tombstonedObjectIds = doc.tombstonedObjectIds + macroId
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[KeySequence] Deleted macro ($macroId)")
    }

    private fun renderCommandBytesForConnection(task: CommandTask, connectionId: String?): ByteArray {
        val state = _uiState.value
        val doc = state.vaultDocument
        val conn = connectionId?.let { cid ->
            doc.connections.firstOrNull { it.id == cid }
                ?: state.hydratedDynamicConnections.values.flatten().firstOrNull { it.id == cid }
        }
        val profile = conn?.let { inheritanceResolver.resolveEffectiveProfile(doc, it) } ?: effectiveProfileForSelected()
        val rendered = if (profile != null) {
            automationEngine.renderCommandTask(task, profile)
        } else {
            if (task.appendNewline) "${task.commandTemplate}\r\n" else task.commandTemplate
        }
        return rendered.replace("\n", "\r\n").replace("\r\r\n", "\r\n").encodeToByteArray()
    }

    fun executeCommandTask(task: CommandTask) {
        if (task.broadcastToAllSelectedSessions) {
            val broadcastTabs = tilingController.allActiveTabs().filter { it.broadcastEnabled }
            if (broadcastTabs.isNotEmpty()) {
                broadcastTabs.forEach { tab ->
                    val bytes = renderCommandBytesForConnection(task, tab.connectionId)
                    sshControllers[tab.tabId]?.sendUserBytes(bytes)
                }
            } else {
                val focused = findFocusedActiveTab(tilingController.focusedPaneId.value)
                val bytes = renderCommandBytesForConnection(task, focused?.connectionId)
                if (focused != null && sshControllers.containsKey(focused.tabId)) {
                    sshControllers[focused.tabId]?.sendUserBytes(bytes)
                } else {
                    automationEngine.emitBroadcastBytes(bytes, originTabId = null)
                }
            }
        } else {
            val focused = findFocusedActiveTab(tilingController.focusedPaneId.value)
            if (focused != null) {
                val bytes = renderCommandBytesForConnection(task, focused.connectionId)
                sshControllers[focused.tabId]?.sendUserBytes(bytes)
            }
        }
        appendLog("[Automation] Executed '${task.name}': ${task.commandTemplate}")
    }

    fun executeKeySequenceTask(macro: KeySequenceTask) {
        val state = _uiState.value
        val doc = state.vaultDocument
        val dynamicAll = state.hydratedDynamicConnections.values.flatten()
        val focusedTab = findFocusedActiveTab(tilingController.focusedPaneId.value)
        val targetConn = doc.connections.firstOrNull { it.id == focusedTab?.connectionId }
            ?: dynamicAll.firstOrNull { it.id == focusedTab?.connectionId }
            ?: doc.connections.firstOrNull { it.id == state.selectedConnectionId }
            ?: doc.connections.firstOrNull()
        val profile = if (targetConn != null) {
            inheritanceResolver.resolveEffectiveProfile(doc, targetConn)
        } else {
            appendLog("[Automation] Cannot run '${macro.name}': no target connection profile.")
            return
        }

        viewModelScope.launch {
            automationEngine.executeKeySequence(
                task = macro,
                profile = profile,
                sendText = { ch ->
                    val tabId = focusedTab?.tabId ?: return@executeKeySequence
                    sshControllers[tabId]?.sendUserBytes(ch.encodeToByteArray())
                    rdpSessions[tabId]?.sendTextInput(ch)
                },
                sendSpecialKey = { token ->
                    val tabId = focusedTab?.tabId ?: return@executeKeySequence
                    when (token.uppercase()) {
                        "CTRL+ALT+DEL" -> rdpSessions[tabId]?.sendCtrlAltDel()
                        "ENTER", "RETURN" -> {
                            sshControllers[tabId]?.sendUserBytes("\r".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x1C, false)
                        }
                        "TAB" -> {
                            sshControllers[tabId]?.sendUserBytes("\t".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x0F, false)
                        }
                        "ESC", "ESCAPE" -> {
                            sshControllers[tabId]?.sendUserBytes(byteArrayOf(0x1B))
                            rdpSessions[tabId]?.sendKeyTap(0x01, false)
                        }
                        "SPACE" -> {
                            sshControllers[tabId]?.sendUserBytes(" ".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x39, false)
                        }
                        "BACKSPACE" -> {
                            sshControllers[tabId]?.sendUserBytes(byteArrayOf(0x7F))
                            rdpSessions[tabId]?.sendKeyTap(0x0E, false)
                        }
                        "DEL", "DELETE" -> {
                            sshControllers[tabId]?.sendUserBytes("\u001b[3~".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x53, true)
                        }
                        "UP" -> {
                            sshControllers[tabId]?.sendUserBytes("\u001b[A".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x48, true)
                        }
                        "DOWN" -> {
                            sshControllers[tabId]?.sendUserBytes("\u001b[B".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x50, true)
                        }
                        "LEFT" -> {
                            sshControllers[tabId]?.sendUserBytes("\u001b[D".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x4B, true)
                        }
                        "RIGHT" -> {
                            sshControllers[tabId]?.sendUserBytes("\u001b[C".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x4D, true)
                        }
                        "CTRL+C" -> sshControllers[tabId]?.sendUserBytes(byteArrayOf(0x03))
                        "F1" -> {
                            sshControllers[tabId]?.sendUserBytes("\u001bOP".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x3B, false)
                        }
                        "F2" -> {
                            sshControllers[tabId]?.sendUserBytes("\u001bOQ".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x3C, false)
                        }
                        "F3" -> {
                            sshControllers[tabId]?.sendUserBytes("\u001bOR".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x3D, false)
                        }
                        "F4" -> {
                            sshControllers[tabId]?.sendUserBytes("\u001bOS".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x3E, false)
                        }
                        "F5" -> {
                            sshControllers[tabId]?.sendUserBytes("\u001b[15~".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x3F, false)
                        }
                        "F11" -> {
                            sshControllers[tabId]?.sendUserBytes("\u001b[23~".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x57, false)
                        }
                        "F12" -> {
                            sshControllers[tabId]?.sendUserBytes("\u001b[24~".encodeToByteArray())
                            rdpSessions[tabId]?.sendKeyTap(0x58, false)
                        }
                    }
                }
            )
            appendLog("[KeySequence] Executed macro '${macro.name}' (${macro.macroSequence})")
        }
    }

    fun setDriveSyncZone(useTeamDrive: Boolean) {
        _uiState.update { it.copy(useSharedTeamDriveZone = useTeamDrive) }
        appendLog("[Google Drive] Active sync zone = ${if (useTeamDrive) "Shared Team Drive" else "appDataFolder"}")
    }

    fun updateDriveAccountAndSafState(
        signedInEmail: String = _uiState.value.driveSignedInEmail,
        linkedSafLabel: String = _uiState.value.driveLinkedSafLabel,
        autoSyncOnSave: Boolean = _uiState.value.driveAutoSyncOnSave,
        rememberVaultPassphrase: Boolean = _uiState.value.driveRememberVaultPassphrase
    ) {
        _uiState.update {
            it.copy(
                driveSignedInEmail = signedInEmail,
                driveLinkedSafLabel = linkedSafLabel,
                driveAutoSyncOnSave = autoSyncOnSave,
                driveRememberVaultPassphrase = rememberVaultPassphrase
            )
        }
    }

    fun updateDriveSyncProgress(isSyncing: Boolean, banner: String) {
        _uiState.update {
            it.copy(
                driveIsSyncing = isSyncing,
                driveSyncStatusBanner = banner
            )
        }
        if (banner.isNotBlank()) {
            appendLog("[Google Drive] $banner")
        }
    }

    fun applySyncedVaultDocument(syncedDoc: NxtsVaultDocument, summaryBanner: String) {
        val cleaned = VaultHierarchySanitizer.sanitizeVaultDocument(syncedDoc)
        activeVaultStore.replaceUnlockedVault(cleaned, markInitialized = true)
        _uiState.update {
            it.copy(
                vaultDocument = cleaned,
                selectedConnectionId = it.selectedConnectionId
                    ?.takeIf { selId -> cleaned.connections.any { c -> c.id == selId && c.id !in cleaned.tombstonedObjectIds } }
                    ?: cleaned.connections.firstOrNull { c -> c.id !in cleaned.tombstonedObjectIds }?.id,
                isVaultLocked = false,
                isStarterUninitialized = false,
                driveIsSyncing = false,
                driveSyncStatusBanner = summaryBanner
            )
        }
        appendLog("[Google Drive] $summaryBanner")
    }

    fun setDriveAutoSyncOnSave(enabled: Boolean) {
        _uiState.update { it.copy(driveAutoSyncOnSave = enabled) }
        appendLog("[Google Drive] Auto-Sync on vault save = $enabled")
    }

    fun updateGitHubReleaseState(banner: String, releaseUrl: String = "") {
        _uiState.update {
            it.copy(
                githubLatestReleaseBanner = banner,
                githubLatestReleaseUrl = releaseUrl
            )
        }
        if (banner.isNotBlank()) {
            appendLog("[Update Checker] $banner")
        }
    }

    fun toggleLanguageMenu() {
        if (_uiState.value.isVaultLocked) return
        _uiState.update { it.copy(isLanguageMenuExpanded = !it.isLanguageMenuExpanded) }
    }

    fun openLanguageMenu() {
        if (_uiState.value.isVaultLocked) return
        _uiState.update { it.copy(isLanguageMenuExpanded = true) }
    }

    fun dismissLanguageMenu() {
        _uiState.update { it.copy(isLanguageMenuExpanded = false) }
    }

    fun openAutoUpdateDialog() {
        if (_uiState.value.isVaultLocked) return
        _uiState.update { it.copy(isAutoUpdateDialogVisible = true) }
    }

    fun dismissAutoUpdateDialog() {
        _uiState.update { it.copy(isAutoUpdateDialogVisible = false) }
    }

    fun setAutoCheckUpdatesState(enabled: Boolean) {
        _uiState.update { it.copy(autoCheckUpdatesEnabled = enabled) }
    }

    fun setCheckingForUpdates(checking: Boolean) {
        _uiState.update { it.copy(isCheckingForUpdates = checking) }
    }

    fun applyAutoUpdateCheckOutcome(
        isUpdateAvailable: Boolean,
        latestTag: String,
        currentVersion: String,
        releaseTitle: String,
        releaseNotes: String,
        sha256Hex: String,
        apkSizeBytes: Long,
        publishedAtIso: String,
        apkDownloadUrl: String,
        releaseHtmlUrl: String,
        bannerMessage: String,
        openDialogAutomatically: Boolean = false
    ) {
        val primaryUrl = apkDownloadUrl.ifBlank { releaseHtmlUrl }
        _uiState.update {
            it.copy(
                isCheckingForUpdates = false,
                updateAvailable = isUpdateAvailable,
                updateLatestTag = latestTag,
                updateCurrentVersion = currentVersion,
                updateReleaseTitle = releaseTitle,
                updateReleaseNotes = releaseNotes,
                updateSha256Hex = sha256Hex,
                updateApkSizeBytes = apkSizeBytes,
                updatePublishedAtIso = publishedAtIso,
                updateApkDownloadUrl = apkDownloadUrl,
                updateReleaseHtmlUrl = releaseHtmlUrl,
                githubLatestReleaseBanner = bannerMessage.ifBlank { it.githubLatestReleaseBanner },
                githubLatestReleaseUrl = primaryUrl,
                isAutoUpdateDialogVisible = if (openDialogAutomatically && !it.isVaultLocked) true else it.isAutoUpdateDialogVisible
            )
        }
        if (bannerMessage.isNotBlank()) {
            appendLog("[Auto-Update] $bannerMessage")
        }
    }

    fun setUpdateVerificationStatus(message: String) {
        _uiState.update { it.copy(updateVerificationMessage = message) }
        if (message.isNotBlank()) {
            appendLog("[Auto-Update Verify] $message")
        }
    }

    private fun hasModalDialogOrOverlayOpen(state: WorkspaceUiState): Boolean {
        return state.isQuickConnectVisible ||
            state.isNewConnectionDialogVisible ||
            state.isFolderDialogVisible ||
            state.connectWithOptionsTarget != null ||
            state.isNewSmartFolderDialogVisible ||
            state.isNewDynamicFolderDialogVisible ||
            state.isCredentialDialogVisible ||
            state.isNewTemplateDialogVisible ||
            state.isLockVaultDialogVisible ||
            state.isResetDatabaseDialogVisible ||
            state.pendingRoyalTsImport != null ||
            state.isLanguageMenuExpanded ||
            state.movingConnectionTarget != null ||
            state.isAutoUpdateDialogVisible ||
            state.pendingEncryptedImportFileName != null ||
            state.importErrorDialogMessage != null ||
            state.activeAppError != null ||
            state.isBottomDrawerVisible
    }

    fun forwardRawKeyToFocusedSession(packet: RawHardwareKeyPacket): Boolean {
        val state = _uiState.value
        if (state.isVaultLocked || state.activeRailId != "connections" || hasModalDialogOrOverlayOpen(state)) {
            return false
        }
        val activeTab = findFocusedActiveTab(tilingController.focusedPaneId.value) ?: return false
        return when (activeTab.protocol) {
            SessionProtocolKind.RDP, SessionProtocolKind.WINDOWS_TS ->
                rdpSessions[activeTab.tabId]?.sendHardwareKey(packet) ?: false

            SessionProtocolKind.VNC, SessionProtocolKind.APPLE_SCREEN_SHARING ->
                vncSessions[activeTab.tabId]?.sendHardwareKey(packet) ?: false

            SessionProtocolKind.SSH, SessionProtocolKind.TELNET -> {
                val sshController = sshControllers[activeTab.tabId]
                val ansiBytes = SshNativeBridge.translateHardwareKeyToAnsi(
                    keyCode = packet.keyCode,
                    unicodeChar = packet.unicodeChar,
                    ctrlPressed = packet.ctrlPressed,
                    altPressed = packet.altPressed,
                    applicationCursorKeys = sshController?.isApplicationCursorKeys() == true
                ) ?: return false
                if (packet.isKeyDown) {
                    if (activeTab.broadcastEnabled) {
                        automationEngine.emitBroadcastBytes(ansiBytes, originTabId = null)
                    } else {
                        sshController?.sendUserBytes(ansiBytes)
                    }
                }
                true
            }
            else -> false
        }
    }

    fun toggleGlobalBroadcast() {
        val next = !_uiState.value.broadcastModeEnabled
        tilingController.toggleBroadcastOnAllTerminals(next)
        _uiState.update { it.copy(broadcastModeEnabled = next, isBottomDrawerVisible = next || it.isBottomDrawerVisible) }
        appendLog("[Broadcast] Multi-terminal simultaneous input = $next")
    }

    fun sendBroadcastCommandLine(command: String) {
        if (command.isBlank()) return
        val bytes = "$command\r\n".encodeToByteArray()
        automationEngine.emitBroadcastBytes(bytes, originTabId = null)
        appendLog("[Broadcast] Dispatched command to active terminal panes: $command")
    }

    fun generateAndStoreSshKeyPair() {
        runCatching {
            credentialHub.generateEd25519SshKeyPair("ops@nexusts.app")
        }.onSuccess { generated ->
            val newCred = VaultCredential(
                name = "Ed25519 Key (${generated.sha256_fingerprint.take(19)}…)",
                sourceKind = CredentialSourceKind.SSH_KEY_PAIR,
                username = "ops",
                privateKeyPem = generated.private_key_openssh_pem,
                publicKeyOpenSsh = generated.public_key_openssh,
                keyFingerprintSha256 = generated.sha256_fingerprint
            )
            activeVaultStore.updateVaultDocument { doc ->
                bumpDocRevisionAndClock(doc).copy(
                    credentials = doc.credentials + newCred
                )
            }
            _uiState.update {
                it.copy(
                    vaultDocument = activeVaultStore.snapshot.value.vaultDocument,
                    userSnackbarMessage = "Generated Ed25519 SSH key (${generated.sha256_fingerprint.take(19)}…)"
                )
            }
            appendLog("[SSH Agent] Generated OpenSSH Ed25519 key ${generated.sha256_fingerprint}")
        }.onFailure { err ->
            reportException(err, "SSH Key Generation Failed")
        }
    }

    fun updateBiometricAndAutoLockState(
        hardwareAvailable: Boolean? = null,
        biometricEnabled: Boolean? = null,
        hasSavedPassphrase: Boolean? = null,
        idleAutoLockMinutes: Int? = null
    ) {
        _uiState.update { current ->
            current.copy(
                biometricHardwareAvailable = hardwareAvailable ?: current.biometricHardwareAvailable,
                biometricUnlockEnabled = biometricEnabled ?: current.biometricUnlockEnabled,
                hasSavedVaultLockPassphrase = hasSavedPassphrase ?: current.hasSavedVaultLockPassphrase,
                idleAutoLockMinutes = idleAutoLockMinutes ?: current.idleAutoLockMinutes
            )
        }
    }

    fun lockVaultWithPassphrase(passphrase: CharArray) {
        val passCopy = passphrase.copyOf()
        Arrays.fill(passphrase, '\u0000')
        val docSnapshot = _uiState.value.vaultDocument
        viewModelScope.launch {
            val sealedResult = try {
                runCatching {
                    withContext(Dispatchers.Default) {
                        vaultSerializer.sealDocument(docSnapshot, passCopy.copyOf())
                    }
                }
            } finally {
                Arrays.fill(passCopy, '\u0000')
            }
            sealedResult.onSuccess { sealed ->
                tilingController.allActiveTabs().forEach { tilingController.closeTab(it.tabId) }
                gatewayTunnelManager.closeAllLeases()
                activeVaultStore.lockWithSealedBytes(sealed)
                _uiState.update {
                    it.copy(
                        isVaultLocked = true,
                        isLockVaultDialogVisible = false,
                        isLanguageMenuExpanded = false,
                        movingConnectionTarget = null,
                        movingFolderTarget = null,
                        isAutoUpdateDialogVisible = false,
                        remoteKeyCaptureEnabled = false,
                        isFullScreenSessionMode = false,
                        isQuickConnectVisible = false,
                        hydratedDynamicConnections = emptyMap()
                    )
                }
                appendLog("[Vault] Sealed .nxts container (${sealed.size} bytes, Argon2id + AES-256-GCM)")
            }.onFailure { err ->
                reportException(err, "Failed to Lock Vault")
            }
        }
    }

    fun cancelPendingVaultImport() {
        val restored = VaultHierarchySanitizer.sanitizeVaultDocument(activeVaultStore.cancelPendingImport())
        _uiState.update {
            it.copy(
                vaultDocument = restored,
                selectedConnectionId = restored.connections.firstOrNull()?.id,
                isVaultLocked = false,
                vaultUnlockError = null,
                hasPreImportBackup = false,
                pendingImportFileName = null
            )
        }
        appendLog("[Import] Canceled encrypted vault import and restored active workspace.")
    }

    private fun isRoyalTsImportFormat(format: DetectedExternalVaultFormat): Boolean = when (format) {
        DetectedExternalVaultFormat.ROYAL_TS_RTSZ_OR_RTSX,
        DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE,
        DetectedExternalVaultFormat.ROYAL_JSON -> true
        else -> false
    }

    private fun suggestRoyalTsFolderName(fileName: String, importedDoc: NxtsVaultDocument): String {
        val stem = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringBeforeLast('.')
            .trim()
        val candidate = stem.ifBlank { importedDoc.name.trim() }.ifBlank { "Imported Connections" }
        return if (VaultHierarchySanitizer.isReservedCredentialsFolderName(candidate)) {
            "$candidate (Imported)"
        } else {
            candidate
        }
    }

    fun unlockVaultWithPassphrase(passphrase: CharArray) {
        val snap = activeVaultStore.snapshot.value
        val blob = snap.sealedLockedVaultSnapshot
        if (blob == null) {
            Arrays.fill(passphrase, '\u0000')
            activeVaultStore.clearLockWithoutBlob()
            _uiState.update { it.copy(isVaultLocked = false, vaultUnlockError = null) }
            return
        }

        val passCopy = passphrase.copyOf()
        Arrays.fill(passphrase, '\u0000')
        val pendingFile = snap.pendingImportFileName.orEmpty()
        val detected = enterpriseFormatImporter.detectFormat(pendingFile, blob)

        viewModelScope.launch {
            try {
                runCatching {
                    withContext(Dispatchers.Default) {
                        when (detected) {
                            DetectedExternalVaultFormat.KEEPASS_KDBX_BINARY,
                            DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE ->
                                enterpriseFormatImporter.importExternalFormat(
                                    format = detected,
                                    rawBytes = blob,
                                    documentNameHint = pendingFile.ifBlank { "ImportedVault" },
                                    masterPassphrase = passCopy.copyOf()
                                )

                            else -> vaultSerializer.unsealDocument(blob, passCopy.copyOf())
                        }
                    }
                }.onSuccess { rawUnlockedDoc ->
                    val unlockedDoc = VaultHierarchySanitizer.sanitizeVaultDocument(rawUnlockedDoc)
                    if (detected == DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE && snap.hasPreImportBackup) {
                        val baseDoc = VaultHierarchySanitizer.sanitizeVaultDocument(activeVaultStore.cancelPendingImport())
                        activeVaultStore.replaceUnlockedVault(baseDoc, markInitialized = !snap.isStarterUninitialized)
                        val normalizedRoyal = RoyalTsImportMerger.normalizeImportedRoyalDocument(unlockedDoc)
                        _uiState.update {
                            it.copy(
                                vaultDocument = baseDoc,
                                isVaultLocked = false,
                                vaultUnlockError = null,
                                hasPreImportBackup = false,
                                pendingImportFileName = null,
                                pendingRoyalTsImport = PendingRoyalTsImportState(
                                    fileName = pendingFile.ifBlank { "RoyalTS Archive" },
                                    importedDocument = normalizedRoyal,
                                    suggestedFolderName = suggestRoyalTsFolderName(pendingFile, normalizedRoyal)
                                )
                            )
                        }
                        appendLog("[Import] Decrypted Royal TS archive '$pendingFile' — awaiting placement selection.")
                        return@onSuccess
                    }

                    val isExternalEncrypted = detected == DetectedExternalVaultFormat.KEEPASS_KDBX_BINARY
                    val finalDoc = if (isExternalEncrypted && snap.hasPreImportBackup) {
                        val baseDoc = VaultHierarchySanitizer.sanitizeVaultDocument(activeVaultStore.cancelPendingImport())
                        mergeImportedIntoCurrentVault(baseDoc, unlockedDoc)
                    } else {
                        activeVaultStore.setActivePassphrase(passCopy)
                        unlockedDoc
                    }
                    activeVaultStore.replaceUnlockedVault(finalDoc, markInitialized = true)
                    _uiState.update {
                        it.copy(
                            vaultDocument = finalDoc,
                            selectedConnectionId = unlockedDoc.connections.firstOrNull()?.id
                                ?: finalDoc.connections.firstOrNull()?.id,
                            isVaultLocked = false,
                            vaultUnlockError = null,
                            hasPreImportBackup = false,
                            pendingImportFileName = null
                        )
                    }
                    appendLog("[Vault] Unlocked '${finalDoc.name}' (Revision ${finalDoc.revision})")
                }.onFailure { err ->
                    val classified = NexusErrorClassifier.classifyThrowable(err, "Vault Unlock Failed")
                    val message = "${classified.userMessage} ${classified.remediationHint}".trim()
                    activeVaultStore.setUnlockError(message)
                    _uiState.update { it.copy(vaultUnlockError = message) }
                    appendLog("[Vault Error] $message (${classified.technicalDetails})")
                }
            } finally {
                Arrays.fill(passCopy, '\u0000')
            }
        }
    }

    private fun mergeImportedIntoCurrentVault(
        cur: NxtsVaultDocument,
        importedDoc: NxtsVaultDocument
    ): NxtsVaultDocument {
        val cleanCur = VaultHierarchySanitizer.sanitizeVaultDocument(cur)
        val cleanImported = VaultHierarchySanitizer.sanitizeVaultDocument(importedDoc)
        val currentIsEmpty = cleanCur.folders.isEmpty() &&
            cleanCur.connections.isEmpty() &&
            cleanCur.credentials.isEmpty() &&
            cleanCur.gateways.isEmpty() &&
            cleanCur.commandTasks.isEmpty() &&
            cleanCur.keySequenceTasks.isEmpty() &&
            cleanCur.templates.isEmpty() &&
            cleanCur.smartFolders.isEmpty() &&
            cleanCur.dynamicFolders.isEmpty() &&
            cleanCur.todoItems.isEmpty()

        val mergedFolders = cleanCur.folders.filterNot { f -> cleanImported.folders.any { it.id == f.id } } + cleanImported.folders
        val mergedSmartFolders = cleanCur.smartFolders.filterNot { s -> cleanImported.smartFolders.any { it.id == s.id } } + cleanImported.smartFolders
        val mergedDynamicFolders = cleanCur.dynamicFolders.filterNot { d -> cleanImported.dynamicFolders.any { it.id == d.id } } + cleanImported.dynamicFolders
        val mergedConns = cleanCur.connections.filterNot { c -> cleanImported.connections.any { it.id == c.id } } + cleanImported.connections
        val mergedCreds = cleanCur.credentials.filterNot { c -> cleanImported.credentials.any { it.id == c.id } } + cleanImported.credentials
        val mergedGateways = cleanCur.gateways.filterNot { g -> cleanImported.gateways.any { it.id == g.id } } + cleanImported.gateways
        val mergedCommandTasks = cleanCur.commandTasks.filterNot { t -> cleanImported.commandTasks.any { it.id == t.id } } + cleanImported.commandTasks
        val mergedKeyTasks = cleanCur.keySequenceTasks.filterNot { k -> cleanImported.keySequenceTasks.any { it.id == k.id } } + cleanImported.keySequenceTasks
        val mergedTemplates = cleanCur.templates.filterNot { t -> cleanImported.templates.any { it.id == t.id } } + cleanImported.templates
        val mergedTodos = cleanCur.todoItems.filterNot { t -> cleanImported.todoItems.any { it.id == t.id } } + cleanImported.todoItems

        val importedIds = buildSet {
            cleanImported.folders.forEach { add(it.id) }
            cleanImported.smartFolders.forEach { add(it.id) }
            cleanImported.dynamicFolders.forEach { add(it.id) }
            cleanImported.connections.forEach { add(it.id) }
            cleanImported.credentials.forEach { add(it.id) }
            cleanImported.gateways.forEach { add(it.id) }
            cleanImported.commandTasks.forEach { add(it.id) }
            cleanImported.keySequenceTasks.forEach { add(it.id) }
            cleanImported.templates.forEach { add(it.id) }
            cleanImported.todoItems.forEach { add(it.id) }
        }

        return bumpDocRevisionAndClock(cleanCur).copy(
            name = if (currentIsEmpty && cleanImported.name.isNotBlank()) cleanImported.name else cleanCur.name,
            rootInheritable = if (currentIsEmpty) cleanImported.rootInheritable else cleanCur.rootInheritable,
            folders = mergedFolders,
            smartFolders = mergedSmartFolders,
            dynamicFolders = mergedDynamicFolders,
            connections = mergedConns,
            credentials = mergedCreds,
            gateways = mergedGateways,
            commandTasks = mergedCommandTasks,
            keySequenceTasks = mergedKeyTasks,
            templates = mergedTemplates,
            todoItems = mergedTodos,
            tombstonedObjectIds = cleanCur.tombstonedObjectIds - importedIds
        )
    }

    fun importVaultOrRtszBytes(
        fileName: String,
        bytes: ByteArray,
        passphraseOrNull: CharArray? = null,
        mergePasswordManagerEntries: Boolean = true,
        royalTsPlacementMode: RoyalTsImportPlacementMode? = null,
        royalTsNewFolderName: String = ""
    ) {
        if (bytes.isEmpty()) {
            passphraseOrNull?.let { Arrays.fill(it, '\u0000') }
            reportImportFailure("Selected file '$fileName' is empty (0 bytes).")
            return
        }

        val detected = enterpriseFormatImporter.detectFormat(fileName, bytes)

        if (detected == DetectedExternalVaultFormat.UNKNOWN) {
            passphraseOrNull?.let { Arrays.fill(it, '\u0000') }
            reportImportFailure(
                "Could not recognize '$fileName' as a valid Royal TS (.rtsz / .rtsx / .rjson), " +
                    "NexusTS (.nxts), KeePass (.kdbx / .xml), Bitwarden (.json), 1Password (.1pux / .1pif), or CSV vault file."
            )
            return
        }

        if (passphraseOrNull == null && detected == DetectedExternalVaultFormat.NXTS_ENCRYPTED_BINARY) {
            activeVaultStore.stageEncryptedBytesForUnlock(bytes, fileName)
            _uiState.update {
                it.copy(
                    isVaultLocked = true,
                    vaultUnlockError = null,
                    hasPreImportBackup = true,
                    pendingImportFileName = fileName,
                    isSidebarVisible = true
                )
            }
            appendLog("[Import] Staged encrypted .nxts vault '$fileName' — enter passphrase to unlock.")
            return
        }

        if (passphraseOrNull == null && (
                detected == DetectedExternalVaultFormat.KEEPASS_KDBX_BINARY ||
                    detected == DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE
                )
        ) {
            pendingEncryptedImportBytes = bytes.copyOf()
            _uiState.update {
                it.copy(
                    pendingEncryptedImportFileName = fileName,
                    pendingEncryptedImportFormat = detected,
                    pendingEncryptedImportError = null
                )
            }
            appendLog("[Import] Encrypted archive '$fileName' (${detected.name}) requires password to import.")
            return
        }

        val passCopy = passphraseOrNull?.copyOf()
        passphraseOrNull?.let { Arrays.fill(it, '\u0000') }

        viewModelScope.launch {
            try {
                runCatching {
                    withContext(Dispatchers.Default) {
                        when (detected) {
                            DetectedExternalVaultFormat.NXTS_ENCRYPTED_BINARY ->
                                vaultSerializer.unsealDocument(bytes, passCopy!!.copyOf())

                            DetectedExternalVaultFormat.KEEPASS_KDBX_BINARY,
                            DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE ->
                                enterpriseFormatImporter.importExternalFormat(
                                    format = detected,
                                    rawBytes = bytes,
                                    documentNameHint = fileName,
                                    masterPassphrase = passCopy!!.copyOf()
                                )

                            DetectedExternalVaultFormat.ROYAL_TS_RTSZ_OR_RTSX ->
                                rtszImporter.importRtszBytes(
                                    rawBytes = bytes,
                                    fallbackDocumentName = fileName.substringBeforeLast('.').ifBlank { "RoyalTS Document" }
                                )

                            DetectedExternalVaultFormat.ROYAL_JSON,
                            DetectedExternalVaultFormat.KEEPASS_XML_2X,
                            DetectedExternalVaultFormat.BITWARDEN_JSON,
                            DetectedExternalVaultFormat.ONEPASSWORD_1PUX_OR_JSON,
                            DetectedExternalVaultFormat.CSV_INVENTORY_OR_PASSWORDS ->
                                enterpriseFormatImporter.importExternalFormat(detected, bytes, fileName)

                            DetectedExternalVaultFormat.UNKNOWN ->
                                throw IllegalArgumentException("Unsupported vault file format: '$fileName'")
                        }
                    }
                }.onSuccess { rawImportedDoc ->
                    val importedDoc = VaultHierarchySanitizer.sanitizeVaultDocument(rawImportedDoc)
                    pendingEncryptedImportBytes?.let { Arrays.fill(it, 0.toByte()) }
                    pendingEncryptedImportBytes = null

                    if (isRoyalTsImportFormat(detected)) {
                        val normalizedRoyal = RoyalTsImportMerger.normalizeImportedRoyalDocument(importedDoc)
                        if (royalTsPlacementMode == null) {
                            _uiState.update {
                                it.copy(
                                    pendingRoyalTsImport = PendingRoyalTsImportState(
                                        fileName = fileName,
                                        importedDocument = normalizedRoyal,
                                        suggestedFolderName = suggestRoyalTsFolderName(fileName, normalizedRoyal)
                                    ),
                                    pendingEncryptedImportFileName = null,
                                    pendingEncryptedImportFormat = null,
                                    pendingEncryptedImportError = null,
                                    importErrorDialogMessage = null,
                                    activeAppError = null
                                )
                            }
                            appendLog("[Import ${detected.name}] Parsed '$fileName' (${normalizedRoyal.connections.size} connections) — awaiting placement choice.")
                            return@onSuccess
                        } else {
                            applyRoyalTsPlacementToVault(
                                fileName = fileName,
                                normalizedRoyal = normalizedRoyal,
                                mode = royalTsPlacementMode,
                                folderName = royalTsNewFolderName
                            )
                            return@onSuccess
                        }
                    }

                    val shouldMerge = detected != DetectedExternalVaultFormat.NXTS_ENCRYPTED_BINARY && mergePasswordManagerEntries
                    val finalDoc = if (shouldMerge) {
                        mergeImportedIntoCurrentVault(activeVaultStore.snapshot.value.vaultDocument, importedDoc)
                    } else {
                        if (detected == DetectedExternalVaultFormat.NXTS_ENCRYPTED_BINARY && passCopy != null) {
                            activeVaultStore.setActivePassphrase(passCopy)
                        }
                        importedDoc
                    }

                    activeVaultStore.replaceUnlockedVault(finalDoc, markInitialized = true)
                    val summaryMsg = "Imported ${importedDoc.connections.size} connection(s), " +
                        "${importedDoc.folders.size} folder(s), ${importedDoc.credentials.size} credential(s) from '$fileName'"
                    _uiState.update {
                        it.copy(
                            vaultDocument = finalDoc,
                            activeRailId = "connections",
                            isSidebarVisible = true,
                            selectedConnectionId = importedDoc.connections.firstOrNull()?.id
                                ?: finalDoc.connections.firstOrNull()?.id,
                            selectedSmartFolderId = null,
                            selectedDynamicFolderId = null,
                            isVaultLocked = false,
                            vaultUnlockError = null,
                            hasPreImportBackup = false,
                            pendingImportFileName = null,
                            pendingEncryptedImportFileName = null,
                            pendingEncryptedImportFormat = null,
                            pendingEncryptedImportError = null,
                            importErrorDialogMessage = null,
                            activeAppError = null,
                            userSnackbarMessage = summaryMsg
                        )
                    }
                    appendLog("[Import ${detected.name}] $summaryMsg")
                }.onFailure { err ->
                    val errMsg = err.message ?: "Failed to import '$fileName'."
                    reportImportFailure("Failed to import '$fileName': $errMsg", err)
                }
            } finally {
                passCopy?.let { Arrays.fill(it, '\u0000') }
            }
        }
    }

    fun confirmRoyalTsImport(mode: RoyalTsImportPlacementMode, folderName: String = "") {
        val pending = _uiState.value.pendingRoyalTsImport ?: return
        applyRoyalTsPlacementToVault(
            fileName = pending.fileName,
            normalizedRoyal = pending.importedDocument,
            mode = mode,
            folderName = folderName.ifBlank { pending.suggestedFolderName }
        )
    }

    fun dismissRoyalTsImportDialog() {
        _uiState.update { it.copy(pendingRoyalTsImport = null) }
    }

    private fun applyRoyalTsPlacementToVault(
        fileName: String,
        normalizedRoyal: NxtsVaultDocument,
        mode: RoyalTsImportPlacementMode,
        folderName: String
    ) {
        if (mode == RoyalTsImportPlacementMode.REPLACE_EXISTING) {
            tilingController.allActiveTabs().forEach { closeSessionTab(it.tabId) }
            gatewayTunnelManager.closeAllLeases()
            activeVaultStore.closeAllLiveSessions()
        }
        val finalDoc = RoyalTsImportMerger.applyPlacement(
            existingDoc = activeVaultStore.snapshot.value.vaultDocument,
            rawImportedDoc = normalizedRoyal,
            mode = mode,
            newFolderName = folderName,
            actorDevice = "googlebook"
        )
        activeVaultStore.replaceUnlockedVault(finalDoc, markInitialized = true)
        val summaryMsg = "Imported ${normalizedRoyal.connections.size} connection(s), " +
            "${normalizedRoyal.folders.size} folder(s), ${normalizedRoyal.credentials.size} credential(s) from '$fileName'"
        _uiState.update {
            it.copy(
                vaultDocument = finalDoc,
                activeRailId = "connections",
                isSidebarVisible = true,
                selectedConnectionId = finalDoc.connections.lastOrNull()?.id
                    ?: finalDoc.connections.firstOrNull()?.id,
                selectedSmartFolderId = null,
                selectedDynamicFolderId = null,
                pendingRoyalTsImport = null,
                isVaultLocked = false,
                vaultUnlockError = null,
                hasPreImportBackup = false,
                pendingImportFileName = null,
                pendingEncryptedImportFileName = null,
                pendingEncryptedImportFormat = null,
                pendingEncryptedImportError = null,
                importErrorDialogMessage = null,
                activeAppError = null,
                userSnackbarMessage = summaryMsg
            )
        }
        appendLog("[RoyalTS Import:${mode.name}] $summaryMsg")
    }

    fun submitEncryptedImportPassword(passphrase: CharArray) {
        val rawBytes = pendingEncryptedImportBytes
        val state = _uiState.value
        val fileName = state.pendingEncryptedImportFileName.orEmpty()
        val format = state.pendingEncryptedImportFormat
        if (rawBytes == null || format == null) {
            Arrays.fill(passphrase, '\u0000')
            dismissEncryptedImportDialog()
            return
        }

        val passCopy = passphrase.copyOf()
        Arrays.fill(passphrase, '\u0000')

        viewModelScope.launch {
            try {
                runCatching {
                    withContext(Dispatchers.Default) {
                        enterpriseFormatImporter.importExternalFormat(
                            format = format,
                            rawBytes = rawBytes,
                            documentNameHint = fileName,
                            masterPassphrase = passCopy.copyOf()
                        )
                    }
                }.onSuccess { rawImportedDoc ->
                    val importedDoc = VaultHierarchySanitizer.sanitizeVaultDocument(rawImportedDoc)
                    pendingEncryptedImportBytes?.let { Arrays.fill(it, 0.toByte()) }
                    pendingEncryptedImportBytes = null

                    if (isRoyalTsImportFormat(format)) {
                        val normalizedRoyal = RoyalTsImportMerger.normalizeImportedRoyalDocument(importedDoc)
                        _uiState.update {
                            it.copy(
                                pendingRoyalTsImport = PendingRoyalTsImportState(
                                    fileName = fileName,
                                    importedDocument = normalizedRoyal,
                                    suggestedFolderName = suggestRoyalTsFolderName(fileName, normalizedRoyal)
                                ),
                                pendingEncryptedImportFileName = null,
                                pendingEncryptedImportFormat = null,
                                pendingEncryptedImportError = null
                            )
                        }
                        appendLog("[Import ${format.name}] Decrypted '$fileName' — awaiting placement choice.")
                        return@onSuccess
                    }

                    val finalDoc = mergeImportedIntoCurrentVault(
                        activeVaultStore.snapshot.value.vaultDocument,
                        importedDoc
                    )
                    activeVaultStore.replaceUnlockedVault(finalDoc, markInitialized = true)
                    val summaryMsg = "Imported ${importedDoc.connections.size} connection(s), " +
                        "${importedDoc.folders.size} folder(s), ${importedDoc.credentials.size} credential(s) from '$fileName'"
                    _uiState.update {
                        it.copy(
                            vaultDocument = finalDoc,
                            activeRailId = "connections",
                            isSidebarVisible = true,
                            selectedConnectionId = importedDoc.connections.firstOrNull()?.id
                                ?: finalDoc.connections.firstOrNull()?.id,
                            selectedSmartFolderId = null,
                            selectedDynamicFolderId = null,
                            pendingEncryptedImportFileName = null,
                            pendingEncryptedImportFormat = null,
                            pendingEncryptedImportError = null,
                            userSnackbarMessage = summaryMsg
                        )
                    }
                    appendLog("[Import ${format.name}] $summaryMsg")
                }.onFailure { err ->
                    val classified = NexusErrorClassifier.classifyThrowable(err, "Encrypted Import Failed")
                    val errMsg = "${classified.userMessage} ${classified.remediationHint}".trim()
                    _uiState.update { it.copy(pendingEncryptedImportError = errMsg) }
                    appendLog("[Import Error] '$fileName': $errMsg (${classified.technicalDetails})")
                }
            } finally {
                Arrays.fill(passCopy, '\u0000')
            }
        }
    }

    fun dismissEncryptedImportDialog() {
        pendingEncryptedImportBytes?.let { Arrays.fill(it, 0.toByte()) }
        pendingEncryptedImportBytes = null
        _uiState.update {
            it.copy(
                pendingEncryptedImportFileName = null,
                pendingEncryptedImportFormat = null,
                pendingEncryptedImportError = null
            )
        }
    }

    fun openResetDatabaseDialog() {
        _uiState.update { it.copy(isResetDatabaseDialogVisible = true) }
    }

    fun dismissResetDatabaseDialog() {
        _uiState.update { it.copy(isResetDatabaseDialogVisible = false) }
    }

    fun resetWorkspaceStateAfterDatabaseWipe(freshDoc: NxtsVaultDocument, confirmationMessage: String) {
        tilingController.allActiveTabs().forEach { tilingController.closeTab(it.tabId) }
        gatewayTunnelManager.closeAllLeases()
        _uiState.update {
            it.copy(
                vaultDocument = freshDoc,
                activeRailId = "connections",
                selectedConnectionId = null,
                selectedFolderId = null,
                selectedSmartFolderId = null,
                selectedDynamicFolderId = null,
                hydratedDynamicConnections = emptyMap(),
                gatewayHealthReports = emptyMap(),
                activeGatewayLeases = emptyList(),
                isResetDatabaseDialogVisible = false,
                isVaultLocked = false,
                isFullScreenSessionMode = false,
                remoteKeyCaptureEnabled = false,
                vaultUnlockError = null,
                hasSavedVaultLockPassphrase = false,
                biometricUnlockEnabled = false,
                pendingRoyalTsImport = null,
                importErrorDialogMessage = null,
                activeAppError = null,
                userSnackbarMessage = confirmationMessage
            )
        }
        appendLog("[Database Reset] $confirmationMessage")
    }

    fun reportStructuredError(error: NexusAppError) {
        _uiState.update {
            it.copy(
                activeAppError = error,
                importErrorDialogMessage = if (error.category == NexusErrorCategory.IMPORT_EXPORT_FORMAT) {
                    error.userMessage
                } else {
                    it.importErrorDialogMessage
                }
            )
        }
        appendLog("[Error:${error.category.name}] ${error.title}: ${error.userMessage} (${error.technicalDetails})")
    }

    fun reportException(
        throwable: Throwable,
        operationTitle: String,
        recoveryConnectionId: String? = null
    ) {
        val structured = NexusErrorClassifier.classifyThrowable(
            throwable = throwable,
            operationTitle = operationTitle,
            recoveryConnectionId = recoveryConnectionId
        )
        reportStructuredError(structured)
    }

    fun dismissActiveAppError() {
        _uiState.update {
            it.copy(
                activeAppError = null,
                importErrorDialogMessage = null
            )
        }
    }

    fun reportImportFailure(message: String, throwable: Throwable? = null) {
        val structured = if (throwable != null) {
            NexusErrorClassifier.classifyThrowable(throwable, "Import Failed").copy(
                userMessage = message
            )
        } else {
            NexusAppError(
                category = NexusErrorCategory.IMPORT_EXPORT_FORMAT,
                title = "Import Failed",
                userMessage = message,
                remediationHint = "Verify that the selected file is a valid, uncorrupted .rtsz, .rtsx, .rjson, .nxts, .kdbx, Bitwarden, 1Password, or CSV export."
            )
        }
        _uiState.update {
            it.copy(
                importErrorDialogMessage = message,
                activeAppError = structured
            )
        }
        appendLog("[Import Error] $message")
    }

    fun dismissImportErrorDialog() {
        _uiState.update {
            it.copy(
                importErrorDialogMessage = null,
                activeAppError = null
            )
        }
    }

    fun notifyUserMessage(message: String) {
        if (message.isBlank()) return
        _uiState.update { it.copy(userSnackbarMessage = message) }
    }

    fun consumeUserSnackbar() {
        _uiState.update { it.copy(userSnackbarMessage = null) }
    }

    fun clearAuditLogs() {
        _uiState.update {
            it.copy(auditLogs = listOf("[NexusTS] Audit log cleared."))
        }
    }

    fun buildVaultExportPayload(
        format: VaultExportFormat,
        exportPassphrase: CharArray? = null
    ): Pair<String, ByteArray> {
        val doc = _uiState.value.vaultDocument
        val baseStem = doc.name
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .ifBlank { "NexusTS-Vault" }
        val suggestedName = "$baseStem.${format.extension}"

        val bytes = when (format) {
            VaultExportFormat.NXTS_VAULT -> {
                activeVaultStore.snapshot.value.sealedLockedVaultSnapshot
                    ?: run {
                        val effectivePass = exportPassphrase?.copyOf()
                            ?: activeVaultStore.copyActivePassphraseOrDefault()
                        try {
                            vaultSerializer.sealDocument(doc, effectivePass.copyOf())
                        } finally {
                            Arrays.fill(effectivePass, '\u0000')
                        }
                    }
            }
            VaultExportFormat.RTSZ_ROYAL_ARCHIVE -> {
                vaultExporter.exportToRtszBytes(doc, includeSecrets = false)
            }
            VaultExportFormat.ENTERPRISE_CSV -> {
                vaultExporter.exportToEnterpriseCsv(doc).encodeToByteArray()
            }
        }
        appendLog("[Export] Prepared '$suggestedName' (${bytes.size} bytes, format=${format.name})")
        return suggestedName to bytes
    }

    fun buildQuickConnectCandidates(): List<QuickConnectCandidate> {
        val state = _uiState.value
        if (state.isVaultLocked) return emptyList()
        val doc = state.vaultDocument
        val saved = doc.connections
            .filterNot { it.id in doc.tombstonedObjectIds }
            .map { conn ->
                val profile = inheritanceResolver.resolveEffectiveProfile(doc, conn)
                QuickConnectCandidate(
                    id = conn.id,
                    title = conn.name,
                    subtitle = conn.host,
                    protocol = mapProtocolToUiKind(conn.protocol),
                    folderBreadcrumb = profile.folderBreadcrumb.joinToString(" / "),
                    tags = conn.tags
                )
            }
        val dynamic = state.hydratedDynamicConnections.flatMap { (dynId, items) ->
            val dynName = doc.dynamicFolders.firstOrNull { it.id == dynId }?.name ?: "Dynamic Folder"
            items.map { conn ->
                QuickConnectCandidate(
                    id = conn.id,
                    title = conn.name,
                    subtitle = conn.host,
                    protocol = mapProtocolToUiKind(conn.protocol),
                    folderBreadcrumb = dynName,
                    tags = conn.tags
                )
            }
        }
        return saved + dynamic
    }

    fun evaluateSmartFolderConnections(smartFolder: SmartFolder): List<VaultConnection> =
        inheritanceResolver.evaluateSmartFolder(_uiState.value.vaultDocument, smartFolder)

    fun evaluateSmartFolderCount(smartFolder: SmartFolder): Int =
        evaluateSmartFolderConnections(smartFolder).size

    fun validateSmartFolderRegex(pattern: String?): String? =
        inheritanceResolver.validateSmartFolderRegex(pattern)

    fun previewSmartFolderMatches(filter: SmartFolderFilter): List<VaultConnection> =
        inheritanceResolver.evaluateSmartFolder(
            _uiState.value.vaultDocument,
            SmartFolder(name = "Preview", filter = filter)
        )

    private fun findFocusedActiveTab(focusedPaneId: String): WorkspaceSessionTab? {
        fun search(node: com.nexusts.app.core.ui.workspace.PaneNode): WorkspaceSessionTab? = when (node) {
            is com.nexusts.app.core.ui.workspace.PaneNode.Leaf ->
                if (node.nodeId == focusedPaneId) node.activeTab else null
            is com.nexusts.app.core.ui.workspace.PaneNode.Split ->
                search(node.first) ?: search(node.second)
        }
        return search(tilingController.rootNode.value)
    }

    private fun appendLog(entry: String) {
        _uiState.update { it.copy(auditLogs = (listOf(entry) + it.auditLogs).take(100)) }
    }

    override fun onCleared() {
        // Close tabs owned by this window's tilingController without destroying sessions transferred to detached windows
        tilingController.allActiveTabs().forEach { tab ->
            activeVaultStore.closeTabResources(tab.tabId)
        }
        super.onCleared()
    }

    companion object {
        fun mapProtocolToUiKind(proto: VaultProtocolType): SessionProtocolKind = when (proto) {
            VaultProtocolType.RDP,
            VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> SessionProtocolKind.RDP
            VaultProtocolType.SSH -> SessionProtocolKind.SSH
            VaultProtocolType.TELNET -> SessionProtocolKind.TELNET
            VaultProtocolType.SERIAL -> SessionProtocolKind.SERIAL
            VaultProtocolType.KUBERNETES_POD -> SessionProtocolKind.KUBERNETES_POD
            VaultProtocolType.DOCKER_CONTAINER -> SessionProtocolKind.DOCKER_CONTAINER
            VaultProtocolType.SFTP -> SessionProtocolKind.SFTP
            VaultProtocolType.FTP -> SessionProtocolKind.FTP
            VaultProtocolType.FTPS -> SessionProtocolKind.FTPS
            VaultProtocolType.SCP -> SessionProtocolKind.SCP
            VaultProtocolType.VNC -> SessionProtocolKind.VNC
            VaultProtocolType.APPLE_SCREEN_SHARING -> SessionProtocolKind.APPLE_SCREEN_SHARING
            VaultProtocolType.WEB_PAGE -> SessionProtocolKind.WEB_PAGE
            VaultProtocolType.VMWARE_VSPHERE -> SessionProtocolKind.VMWARE_VSPHERE
            VaultProtocolType.PROXMOX_VE -> SessionProtocolKind.PROXMOX_VE
            VaultProtocolType.HYPER_V -> SessionProtocolKind.HYPER_V
            VaultProtocolType.AWS_EC2 -> SessionProtocolKind.AWS_EC2
            VaultProtocolType.AZURE_VM -> SessionProtocolKind.AZURE_VM
            VaultProtocolType.UTILITY_PING,
            VaultProtocolType.UTILITY_TRACEROUTE,
            VaultProtocolType.UTILITY_PORT_SCANNER,
            VaultProtocolType.UTILITY_WAKE_ON_LAN -> SessionProtocolKind.NET_UTILITY
        }

        fun defaultVaultProtocolForUiKind(kind: SessionProtocolKind): VaultProtocolType = when (kind) {
            SessionProtocolKind.RDP -> VaultProtocolType.RDP
            SessionProtocolKind.WINDOWS_TS -> VaultProtocolType.WINDOWS_TERMINAL_SERVICES
            SessionProtocolKind.SSH -> VaultProtocolType.SSH
            SessionProtocolKind.TELNET -> VaultProtocolType.TELNET
            SessionProtocolKind.SERIAL -> VaultProtocolType.SERIAL
            SessionProtocolKind.KUBERNETES_POD -> VaultProtocolType.KUBERNETES_POD
            SessionProtocolKind.DOCKER_CONTAINER -> VaultProtocolType.DOCKER_CONTAINER
            SessionProtocolKind.SFTP -> VaultProtocolType.SFTP
            SessionProtocolKind.FTP -> VaultProtocolType.FTP
            SessionProtocolKind.FTPS -> VaultProtocolType.FTPS
            SessionProtocolKind.SCP -> VaultProtocolType.SCP
            SessionProtocolKind.VNC -> VaultProtocolType.VNC
            SessionProtocolKind.APPLE_SCREEN_SHARING -> VaultProtocolType.APPLE_SCREEN_SHARING
            SessionProtocolKind.WEB_PAGE -> VaultProtocolType.WEB_PAGE
            SessionProtocolKind.VMWARE_VSPHERE -> VaultProtocolType.VMWARE_VSPHERE
            SessionProtocolKind.PROXMOX_VE -> VaultProtocolType.PROXMOX_VE
            SessionProtocolKind.HYPER_V -> VaultProtocolType.HYPER_V
            SessionProtocolKind.AWS_EC2 -> VaultProtocolType.AWS_EC2
            SessionProtocolKind.AZURE_VM -> VaultProtocolType.AZURE_VM
            SessionProtocolKind.NET_UTILITY -> VaultProtocolType.UTILITY_PING
        }

        /**
         * Resolves the effective TCP port for an SSH or Telnet session, ensuring `VaultProtocolType.TELNET`
         * defaults to port `23` even when constructed with a default `ProtocolSpecificSettings.Ssh()` (`port = 22`),
         * while preserving any explicit custom port (such as `2323` or an ad-hoc override).
         */
        fun resolveEffectiveTelnetOrSshPort(
            conn: VaultConnection?,
            tabProtocol: SessionProtocolKind,
            adHocOverride: AdHocLaunchOverride? = null
        ): Int {
            adHocOverride?.adHocPort?.takeIf { it > 0 }?.let { return it }
            val isTelnet = tabProtocol == SessionProtocolKind.TELNET ||
                conn?.protocol == VaultProtocolType.TELNET ||
                adHocOverride?.adHocProtocolType == VaultProtocolType.TELNET
            val sshSettings = conn?.settings as? ProtocolSpecificSettings.Ssh
            return if (isTelnet) {
                val configured = sshSettings?.port ?: 23
                if (configured <= 0 || (conn?.protocol == VaultProtocolType.TELNET && configured == 22)) {
                    23
                } else {
                    configured
                }
            } else {
                sshSettings?.port?.takeIf { it > 0 } ?: 22
            }
        }

        const val DEFAULT_VAULT_DOCUMENT_ID = "nxts-default-vault"
        const val DEFAULT_VAULT_NAME = "NexusTS Vault"

        /**
         * Returns `true` when [doc] contains only legacy demo objects and zero user-created folders,
         * connections, or credentials, allowing `ActiveVaultSessionStore` to discard it cleanly on upgrade.
         */
        fun isPureLegacyStarterDemoVault(doc: NxtsVaultDocument): Boolean {
            if (doc.documentId != "nxts-enterprise-core" && doc.name != "Production Infrastructure") return false
            val cleaned = VaultHierarchySanitizer.sanitizeVaultDocument(doc)
            return cleaned.connections.isEmpty() && cleaned.folders.isEmpty() && cleaned.credentials.isEmpty()
        }

        /**
         * Strips any legacy demo entries and absorbs empty/credential-only root `"Credentials"` folders.
         */
        fun sanitizeLegacyDemoEntries(doc: NxtsVaultDocument): NxtsVaultDocument =
            VaultHierarchySanitizer.sanitizeVaultDocument(doc)

        /**
         * Clean, zero-clutter default empty vault initialized on first launch.
         */
        fun createEnterpriseStarterVault(): NxtsVaultDocument = NxtsVaultDocument(
            documentId = DEFAULT_VAULT_DOCUMENT_ID,
            name = DEFAULT_VAULT_NAME,
            revision = 1L
        )

        fun sortedSiblingFolders(
            folders: List<VaultFolder>,
            parentFolderId: String?,
            tombstones: Set<String> = emptySet()
        ): List<VaultFolder> {
            return folders
                .filter { it.parentFolderId == parentFolderId && it.id !in tombstones }
                .sortedWith(
                    compareBy<VaultFolder> { it.sortOrder }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                        .thenBy { it.id }
                )
        }

        fun sortedSiblingConnections(
            connections: List<VaultConnection>,
            parentFolderId: String?,
            tombstones: Set<String> = emptySet()
        ): List<VaultConnection> {
            return connections
                .filter { it.parentFolderId == parentFolderId && it.id !in tombstones }
                .sortedWith(
                    compareBy<VaultConnection> { it.sortOrder }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                        .thenBy { it.id }
                )
        }
    }
}

fun VaultProtocolType.toProtocolKind(): SessionProtocolKind =
    NexusWorkspaceViewModel.mapProtocolToUiKind(this)
