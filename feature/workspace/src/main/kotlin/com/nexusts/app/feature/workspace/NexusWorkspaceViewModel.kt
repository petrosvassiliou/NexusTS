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
import com.nexusts.app.core.vault.exporter.VaultDocumentExporter
import com.nexusts.app.core.vault.exporter.VaultExportFormat
import com.nexusts.app.core.vault.gateway.ActiveTunnelLease
import com.nexusts.app.core.vault.gateway.GatewayHealthReport
import com.nexusts.app.core.vault.gateway.SecureGatewayTunnelManager
import com.nexusts.app.core.vault.importer.DetectedExternalVaultFormat
import com.nexusts.app.core.vault.importer.EnterpriseVaultFormatImporter
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays
import java.util.UUID
import javax.inject.Inject

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
    val isNewDynamicFolderDialogVisible: Boolean = false,
    val isNewTemplateDialogVisible: Boolean = false,
    val isLockVaultDialogVisible: Boolean = false,
    val pendingEncryptedImportFileName: String? = null,
    val pendingEncryptedImportFormat: DetectedExternalVaultFormat? = null,
    val pendingEncryptedImportError: String? = null,
    val importErrorDialogMessage: String? = null,
    val userSnackbarMessage: String? = null,
    val sidebarFilterQuery: String = "",
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
    val broadcastModeEnabled: Boolean = false,
    val isVaultLocked: Boolean = false,
    val vaultUnlockError: String? = null,
    val hasPreImportBackup: Boolean = false,
    val pendingImportFileName: String? = null,
    val isStarterUninitialized: Boolean = true,
    val vaultDocument: NxtsVaultDocument = NexusWorkspaceViewModel.createEnterpriseStarterVault(),
    val selectedConnectionId: String? = null,
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
    private val adHocOverrides
        get() = activeVaultStore.adHocOverrides

    private val _uiState = MutableStateFlow(
        WorkspaceUiState(
            vaultDocument = activeVaultStore.snapshot.value.vaultDocument,
            isVaultLocked = activeVaultStore.snapshot.value.isVaultLocked,
            vaultUnlockError = activeVaultStore.snapshot.value.vaultUnlockError,
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
                _uiState.update { current ->
                    val dynamicAll = current.hydratedDynamicConnections.values.flatten()
                    val activeConns = shared.vaultDocument.connections.filterNot {
                        it.id in shared.vaultDocument.tombstonedObjectIds
                    }
                    val validSelectedId = current.selectedConnectionId
                        ?.takeIf { selId ->
                            activeConns.any { it.id == selId } || dynamicAll.any { it.id == selId }
                        }
                        ?: activeConns.firstOrNull()?.id
                    current.copy(
                        vaultDocument = shared.vaultDocument,
                        isVaultLocked = shared.isVaultLocked,
                        vaultUnlockError = shared.vaultUnlockError,
                        hasPreImportBackup = shared.hasPreImportBackup,
                        pendingImportFileName = shared.pendingImportFileName,
                        isStarterUninitialized = shared.isStarterUninitialized,
                        selectedConnectionId = validSelectedId,
                        remoteKeyCaptureEnabled = if (shared.isVaultLocked) false else current.remoteKeyCaptureEnabled,
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
        SessionProtocolKind.TELNET -> true
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
        _uiState.update {
            it.copy(
                editingFolder = null,
                newFolderDefaultParentId = parentFolderId,
                isFolderDialogVisible = true
            )
        }
    }

    fun openEditFolderDialog(folder: VaultFolder) {
        if (_uiState.value.isVaultLocked) return
        _uiState.update {
            it.copy(
                editingFolder = folder,
                newFolderDefaultParentId = folder.parentFolderId,
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

    fun selectConnectionInTree(connectionId: String) {
        _uiState.update { it.copy(selectedConnectionId = connectionId) }
    }

    fun toggleSmartFolderSelection(smartFolderId: String) {
        _uiState.update { state ->
            val next = if (state.selectedSmartFolderId == smartFolderId) null else smartFolderId
            state.copy(selectedSmartFolderId = next)
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
            cred != null -> ResolvedSessionSecret(
                username = cred.username,
                password = cred.secretPassword,
                domain = cred.domain,
                privateKeyPem = cred.privateKeyPem,
                keyPassphrase = cred.keyPassphrase
            )
            else -> ResolvedSessionSecret(username = "root")
        }
        return conn to secret
    }

    fun getOrCreateSshController(tab: WorkspaceSessionTab): SshTerminalSessionController {
        activeVaultStore.tabConnectionIds[tab.tabId] = tab.connectionId
        return sshControllers.getOrPut(tab.tabId) {
            val (conn, secret) = resolveConnectionAndSecret(tab.connectionId, tab.tabId)
            val override = adHocOverrides[tab.tabId]
            val sshSettings = conn?.settings as? ProtocolSpecificSettings.Ssh
            val doc = _uiState.value.vaultDocument
            val bastion = sshSettings?.bastionJumpConnectionId?.let { id ->
                doc.connections.firstOrNull { it.id == id }?.host
            }
            val defaultPort = if (tab.protocol == SessionProtocolKind.TELNET) 23 else 22
            SshTerminalSessionController(
                title = tab.title,
                host = override?.adHocHost ?: conn?.host ?: tab.subtitleHost,
                port = override?.adHocPort ?: sshSettings?.port ?: defaultPort,
                username = secret.username.ifBlank { "root" },
                portForwards = sshSettings?.portForwards.orEmpty(),
                enableX11Forwarding = sshSettings?.enableX11Forwarding ?: false,
                bastionHost = bastion
            )
        }
    }

    fun getOrCreateRdpSession(tab: WorkspaceSessionTab): RdpManagedSession {
        activeVaultStore.tabConnectionIds[tab.tabId] = tab.connectionId
        return rdpSessions.getOrPut(tab.tabId) {
            val (conn, secret) = resolveConnectionAndSecret(tab.connectionId, tab.tabId)
            val override = adHocOverrides[tab.tabId]
            val effectiveHost = override?.adHocHost ?: conn?.host ?: tab.subtitleHost
            val fallbackConn = (conn?.copy(host = effectiveHost)) ?: VaultConnection(
                id = tab.connectionId,
                name = tab.title,
                host = effectiveHost,
                protocol = VaultProtocolType.RDP,
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
        val children = doc.connections.filter {
            it.parentFolderId in folderTreeIds && it.id !in doc.tombstonedObjectIds
        }
        if (children.isEmpty()) return
        _uiState.update { it.copy(activeRailId = "connections") }
        children.forEach { conn ->
            launchConnection(conn, QuickLaunchPlacement.ACTIVE_PANE_TAB, onDetachWindowRequested)
        }
        val folderName = doc.folders.firstOrNull { it.id == folderId }?.name ?: folderId
        appendLog("[Bulk Connect] Opened ${children.size} session(s) from folder '$folderName'")
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

    fun reconnectSessionTab(tab: WorkspaceSessionTab) {
        sshControllers.remove(tab.tabId)?.close()
        rdpSessions.remove(tab.tabId)?.close()
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
            _uiState.update { it.copy(remoteKeyCaptureEnabled = false) }
        }
        return extracted
    }

    fun closeDetachedTabResources(tabId: String) {
        activeVaultStore.closeTabResources(tabId)
    }

    fun updateSidebarFilterQuery(query: String) {
        _uiState.update { it.copy(sidebarFilterQuery = query) }
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
        val cloned = folder.copy(
            id = UUID.randomUUID().toString(),
            name = "${folder.name} (Copy)",
            updatedAtEpochMs = System.currentTimeMillis()
        )
        saveFolderAndCredentialToVault(cloned)
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

    fun deleteFolder(folderId: String) {
        val currentDoc = _uiState.value.vaultDocument
        val targetFolder = currentDoc.folders.firstOrNull { it.id == folderId } ?: return
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
                editingFolder = if (state.editingFolder?.id == folderId) null else state.editingFolder,
                isFolderDialogVisible = if (state.editingFolder?.id == folderId) false else state.isFolderDialogVisible,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
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
                appendLog("[DynamicFolder Error] '${dynamicFolder.name}': ${err.message}")
            }
        }
    }

    fun openNewDynamicFolderDialog() {
        if (_uiState.value.isVaultLocked) return
        _uiState.update { it.copy(isNewDynamicFolderDialogVisible = true) }
    }

    fun dismissNewDynamicFolderDialog() {
        _uiState.update { it.copy(isNewDynamicFolderDialogVisible = false) }
    }

    fun saveDynamicFolderToVault(dynamicFolder: DynamicFolder) {
        activeVaultStore.updateVaultDocument { doc ->
            val exists = doc.dynamicFolders.any { it.id == dynamicFolder.id }
            val nextList = if (exists) {
                doc.dynamicFolders.map { if (it.id == dynamicFolder.id) dynamicFolder else it }
            } else {
                doc.dynamicFolders + dynamicFolder
            }
            bumpDocRevisionAndClock(doc).copy(
                dynamicFolders = nextList,
                tombstonedObjectIds = doc.tombstonedObjectIds - dynamicFolder.id
            )
        }
        _uiState.update {
            it.copy(
                isNewDynamicFolderDialogVisible = false,
                selectedDynamicFolderId = dynamicFolder.id,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
            )
        }
        reloadDynamicFolder(dynamicFolder)
        appendLog("[DynamicFolder] Saved '${dynamicFolder.name}' (${dynamicFolder.interpreter.name})")
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
                selectedDynamicFolderId = state.selectedDynamicFolderId.takeUnless { it == dynamicFolderId },
                hydratedDynamicConnections = state.hydratedDynamicConnections - dynamicFolderId,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
            )
        }
        appendLog("[DynamicFolder] Deleted dynamic folder ($dynamicFolderId)")
    }

    fun openNewSmartFolderDialog() {
        if (_uiState.value.isVaultLocked) return
        _uiState.update { it.copy(isNewSmartFolderDialogVisible = true) }
    }

    fun dismissNewSmartFolderDialog() {
        _uiState.update { it.copy(isNewSmartFolderDialogVisible = false) }
    }

    fun saveSmartFolderToVault(smartFolder: SmartFolder) {
        activeVaultStore.updateVaultDocument { doc ->
            val exists = doc.smartFolders.any { it.id == smartFolder.id }
            val nextList = if (exists) {
                doc.smartFolders.map { if (it.id == smartFolder.id) smartFolder else it }
            } else {
                doc.smartFolders + smartFolder
            }
            bumpDocRevisionAndClock(doc).copy(
                smartFolders = nextList,
                tombstonedObjectIds = doc.tombstonedObjectIds - smartFolder.id
            )
        }
        _uiState.update {
            it.copy(
                isNewSmartFolderDialogVisible = false,
                selectedSmartFolderId = smartFolder.id,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
            )
        }
        appendLog("[SmartFolder] Saved dynamic filter '${smartFolder.name}'")
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
        val adHocTab = WorkspaceSessionTab(
            connectionId = candidate.id,
            title = candidate.title,
            subtitleHost = resolvedHost,
            protocol = candidate.protocol,
            status = SessionLifecycleStatus.CONNECTED,
            broadcastEnabled = state.broadcastModeEnabled
        )
        if (candidate.parsedHost != null ||
            candidate.parsedPort != null ||
            !candidate.parsedUsername.isNullOrBlank() ||
            !candidate.parsedDomain.isNullOrBlank()
        ) {
            adHocOverrides[adHocTab.tabId] = AdHocLaunchOverride(
                adHocUsername = candidate.parsedUsername.orEmpty(),
                adHocDomain = candidate.parsedDomain.orEmpty(),
                adHocHost = candidate.parsedHost,
                adHocPort = candidate.parsedPort
            )
        }
        _uiState.update {
            it.copy(
                activeRailId = "connections",
                remoteKeyCaptureEnabled = (placement != QuickLaunchPlacement.DETACHED_NEW_WINDOW && isInteractiveRemoteKeyProtocol(candidate.protocol)) || it.remoteKeyCaptureEnabled
            )
        }
        appendLog("[QuickConnect] Launched ad-hoc ${candidate.protocol.name} -> $resolvedHost")
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
            _uiState.update { it.copy(remoteKeyCaptureEnabled = false) }
        }
        runConfiguredLifecycleTask(conn?.inheritable?.postDisconnectTaskId, "Post-Disconnect")
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
            NexusShortcutAction.SPLIT_PANE_VERTICAL ->
                tilingController.splitPane(orientation = SplitOrientation.HORIZONTAL)
            NexusShortcutAction.SPLIT_PANE_HORIZONTAL ->
                tilingController.splitPane(orientation = SplitOrientation.VERTICAL)
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
            NexusShortcutAction.ZOOM_IN_SESSION ->
                tilingController.adjustFocusedTabZoom(+0.15f)
            NexusShortcutAction.ZOOM_OUT_SESSION ->
                tilingController.adjustFocusedTabZoom(-0.15f)
            NexusShortcutAction.ZOOM_RESET_SESSION ->
                tilingController.adjustFocusedTabZoom(null)
        }
    }

    fun saveConnectionToVault(connection: VaultConnection) {
        evictLiveSessionsForConnections(setOf(connection.id))

        var wasUpdated = false
        val stamped = connection.copy(updatedAtEpochMs = System.currentTimeMillis())
        activeVaultStore.updateVaultDocument { doc ->
            val exists = doc.connections.any { it.id == stamped.id }
            wasUpdated = exists
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

    fun saveFolderAndCredentialToVault(folder: VaultFolder, inlineNewCredential: VaultCredential? = null) {
        val finalFolder = if (inlineNewCredential != null) {
            folder.copy(
                inheritable = folder.inheritable.copy(
                    credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                    explicitCredentialId = inlineNewCredential.id
                )
            )
        } else {
            folder
        }

        val currentDoc = _uiState.value.vaultDocument
        val affectedFolderIds = collectDescendantFolderIds(currentDoc.folders + finalFolder, finalFolder.id)
        val affectedConnIds = currentDoc.connections
            .filter { it.parentFolderId in affectedFolderIds }
            .map { it.id }
            .toSet()
        evictLiveSessionsForConnections(affectedConnIds)

        activeVaultStore.updateVaultDocument { doc ->
            val updatedCredentials = if (inlineNewCredential != null) {
                doc.credentials + inlineNewCredential.copy(parentFolderId = finalFolder.id)
            } else {
                doc.credentials
            }
            val folderExists = doc.folders.any { it.id == finalFolder.id }
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
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
            )
        }
        val credSummary = inlineNewCredential?.let { " + saved folder credential '${it.name}'" }.orEmpty()
        appendLog("[Folder] Saved folder '${finalFolder.name}' (${finalFolder.inheritable.credentialMode.name})$credSummary")
    }

    fun addCredentialToVault(credential: VaultCredential) {
        activeVaultStore.updateVaultDocument { doc ->
            val exists = doc.credentials.any { it.id == credential.id }
            val nextCreds = if (exists) {
                doc.credentials.map { if (it.id == credential.id) credential else it }
            } else {
                doc.credentials + credential
            }
            bumpDocRevisionAndClock(doc).copy(
                credentials = nextCreds,
                tombstonedObjectIds = doc.tombstonedObjectIds - credential.id
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[Credentials] Stored '${credential.name}' (${credential.sourceKind.name})")
    }

    fun duplicateCredential(credential: VaultCredential) {
        addCredentialToVault(
            credential.copy(
                id = UUID.randomUUID().toString(),
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

        val stamped = credential.copy(updatedAtEpochMs = System.currentTimeMillis())
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
                appendLog("[Gateway Lease Error] ${gateway.name}: ${err.message}")
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
        activeVaultStore.replaceUnlockedVault(syncedDoc, markInitialized = true)
        _uiState.update {
            it.copy(
                vaultDocument = syncedDoc,
                selectedConnectionId = it.selectedConnectionId
                    ?.takeIf { selId -> syncedDoc.connections.any { c -> c.id == selId && c.id !in syncedDoc.tombstonedObjectIds } }
                    ?: syncedDoc.connections.firstOrNull { c -> c.id !in syncedDoc.tombstonedObjectIds }?.id,
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
            state.isNewTemplateDialogVisible ||
            state.isLockVaultDialogVisible ||
            state.isLanguageMenuExpanded ||
            state.movingConnectionTarget != null ||
            state.isAutoUpdateDialogVisible ||
            state.pendingEncryptedImportFileName != null ||
            state.importErrorDialogMessage != null ||
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

            SessionProtocolKind.SSH, SessionProtocolKind.TELNET -> {
                val ansiBytes = SshNativeBridge.translateHardwareKeyToAnsi(
                    keyCode = packet.keyCode,
                    unicodeChar = packet.unicodeChar,
                    ctrlPressed = packet.ctrlPressed,
                    altPressed = packet.altPressed
                ) ?: return false
                if (packet.isKeyDown) {
                    if (activeTab.broadcastEnabled) {
                        automationEngine.emitBroadcastBytes(ansiBytes, originTabId = null)
                    } else {
                        sshControllers[activeTab.tabId]?.sendUserBytes(ansiBytes)
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
        val generated = credentialHub.generateEd25519SshKeyPair("ops@nexusts.app")
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
    }

    fun lockVaultWithPassphrase(passphrase: CharArray) {
        val passCopy = passphrase.copyOf()
        Arrays.fill(passphrase, '\u0000')
        val docSnapshot = _uiState.value.vaultDocument
        viewModelScope.launch {
            val sealed = try {
                withContext(Dispatchers.Default) {
                    vaultSerializer.sealDocument(docSnapshot, passCopy.copyOf())
                }
            } finally {
                Arrays.fill(passCopy, '\u0000')
            }
            tilingController.allActiveTabs().forEach { tilingController.closeTab(it.tabId) }
            gatewayTunnelManager.closeAllLeases()
            activeVaultStore.lockWithSealedBytes(sealed)
            _uiState.update {
                it.copy(
                    isVaultLocked = true,
                    isLockVaultDialogVisible = false,
                    isLanguageMenuExpanded = false,
                    movingConnectionTarget = null,
                    isAutoUpdateDialogVisible = false,
                    remoteKeyCaptureEnabled = false,
                    isQuickConnectVisible = false,
                    hydratedDynamicConnections = emptyMap()
                )
            }
            appendLog("[Vault] Sealed .nxts container (${sealed.size} bytes, Argon2id + AES-256-GCM)")
        }
    }

    fun cancelPendingVaultImport() {
        val restored = activeVaultStore.cancelPendingImport()
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
                }.onSuccess { unlockedDoc ->
                    val isExternalEncrypted = detected == DetectedExternalVaultFormat.KEEPASS_KDBX_BINARY ||
                        detected == DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE
                    val finalDoc = if (isExternalEncrypted && snap.hasPreImportBackup) {
                        val baseDoc = activeVaultStore.cancelPendingImport()
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
                    val message = err.message ?: "Invalid passphrase or corrupted encrypted vault container."
                    activeVaultStore.setUnlockError(message)
                    _uiState.update { it.copy(vaultUnlockError = message) }
                    appendLog("[Vault Error] $message")
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
        val currentIsEmpty = cur.folders.isEmpty() &&
            cur.connections.isEmpty() &&
            cur.credentials.isEmpty() &&
            cur.gateways.isEmpty() &&
            cur.commandTasks.isEmpty() &&
            cur.keySequenceTasks.isEmpty() &&
            cur.templates.isEmpty() &&
            cur.smartFolders.isEmpty() &&
            cur.dynamicFolders.isEmpty() &&
            cur.todoItems.isEmpty()

        val mergedFolders = cur.folders.filterNot { f -> importedDoc.folders.any { it.id == f.id } } + importedDoc.folders
        val mergedSmartFolders = cur.smartFolders.filterNot { s -> importedDoc.smartFolders.any { it.id == s.id } } + importedDoc.smartFolders
        val mergedDynamicFolders = cur.dynamicFolders.filterNot { d -> importedDoc.dynamicFolders.any { it.id == d.id } } + importedDoc.dynamicFolders
        val mergedConns = cur.connections.filterNot { c -> importedDoc.connections.any { it.id == c.id } } + importedDoc.connections
        val mergedCreds = cur.credentials.filterNot { c -> importedDoc.credentials.any { it.id == c.id } } + importedDoc.credentials
        val mergedGateways = cur.gateways.filterNot { g -> importedDoc.gateways.any { it.id == g.id } } + importedDoc.gateways
        val mergedCommandTasks = cur.commandTasks.filterNot { t -> importedDoc.commandTasks.any { it.id == t.id } } + importedDoc.commandTasks
        val mergedKeyTasks = cur.keySequenceTasks.filterNot { k -> importedDoc.keySequenceTasks.any { it.id == k.id } } + importedDoc.keySequenceTasks
        val mergedTemplates = cur.templates.filterNot { t -> importedDoc.templates.any { it.id == t.id } } + importedDoc.templates
        val mergedTodos = cur.todoItems.filterNot { t -> importedDoc.todoItems.any { it.id == t.id } } + importedDoc.todoItems

        val importedIds = buildSet {
            importedDoc.folders.forEach { add(it.id) }
            importedDoc.smartFolders.forEach { add(it.id) }
            importedDoc.dynamicFolders.forEach { add(it.id) }
            importedDoc.connections.forEach { add(it.id) }
            importedDoc.credentials.forEach { add(it.id) }
            importedDoc.gateways.forEach { add(it.id) }
            importedDoc.commandTasks.forEach { add(it.id) }
            importedDoc.keySequenceTasks.forEach { add(it.id) }
            importedDoc.templates.forEach { add(it.id) }
            importedDoc.todoItems.forEach { add(it.id) }
        }

        return bumpDocRevisionAndClock(cur).copy(
            name = if (currentIsEmpty && importedDoc.name.isNotBlank()) importedDoc.name else cur.name,
            rootInheritable = if (currentIsEmpty) importedDoc.rootInheritable else cur.rootInheritable,
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
            tombstonedObjectIds = cur.tombstonedObjectIds - importedIds
        )
    }

    fun importVaultOrRtszBytes(
        fileName: String,
        bytes: ByteArray,
        passphraseOrNull: CharArray? = null,
        mergePasswordManagerEntries: Boolean = true
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
                }.onSuccess { importedDoc ->
                    val shouldMerge = detected != DetectedExternalVaultFormat.NXTS_ENCRYPTED_BINARY && mergePasswordManagerEntries
                    val finalDoc = if (shouldMerge) {
                        mergeImportedIntoCurrentVault(activeVaultStore.snapshot.value.vaultDocument, importedDoc)
                    } else {
                        if (detected == DetectedExternalVaultFormat.NXTS_ENCRYPTED_BINARY && passCopy != null) {
                            activeVaultStore.setActivePassphrase(passCopy)
                        }
                        importedDoc
                    }

                    pendingEncryptedImportBytes?.let { Arrays.fill(it, 0.toByte()) }
                    pendingEncryptedImportBytes = null

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
                            userSnackbarMessage = summaryMsg
                        )
                    }
                    appendLog("[Import ${detected.name}] $summaryMsg")
                }.onFailure { err ->
                    val errMsg = err.message ?: "Failed to import '$fileName'."
                    reportImportFailure("Failed to import '$fileName': $errMsg")
                }
            } finally {
                passCopy?.let { Arrays.fill(it, '\u0000') }
            }
        }
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
                }.onSuccess { importedDoc ->
                    pendingEncryptedImportBytes?.let { Arrays.fill(it, 0.toByte()) }
                    pendingEncryptedImportBytes = null

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
                    val errMsg = err.message ?: "Incorrect password or corrupted encrypted archive."
                    _uiState.update { it.copy(pendingEncryptedImportError = errMsg) }
                    appendLog("[Import Error] '$fileName': $errMsg")
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

    fun reportImportFailure(message: String) {
        _uiState.update { it.copy(importErrorDialogMessage = message) }
        appendLog("[Import Error] $message")
    }

    fun dismissImportErrorDialog() {
        _uiState.update { it.copy(importErrorDialogMessage = null) }
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
            VaultProtocolType.RDP -> SessionProtocolKind.RDP
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
            VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> SessionProtocolKind.WINDOWS_TS
            VaultProtocolType.UTILITY_PING,
            VaultProtocolType.UTILITY_TRACEROUTE,
            VaultProtocolType.UTILITY_PORT_SCANNER,
            VaultProtocolType.UTILITY_WAKE_ON_LAN -> SessionProtocolKind.NET_UTILITY
        }

        const val DEFAULT_VAULT_DOCUMENT_ID = "nxts-default-vault"
        const val DEFAULT_VAULT_NAME = "NexusTS Vault"

        private val LEGACY_DEMO_CONNECTION_IDS = setOf(
            "conn-win-rds-01",
            "conn-win-rds-02",
            "conn-k8s-bastion-ssh",
            "conn-prod-sftp",
            "conn-macos-ard",
            "conn-proxmox-ve",
            "conn-net-scanner",
            "conn-serial-switch-01",
            "conn-k8s-pod-exec-01"
        )

        private val LEGACY_DEMO_FOLDER_IDS = setOf(
            "folder-prod-core",
            "folder-win-ad",
            "folder-hybrid-cloud"
        )

        private val LEGACY_DEMO_CREDENTIAL_IDS = setOf(
            "cred-ops-root",
            "cred-corp-domain"
        )

        private val LEGACY_DEMO_GATEWAY_IDS = setOf(
            "gw-eu-bastion"
        )

        private val LEGACY_DEMO_DYNAMIC_FOLDER_IDS = setOf(
            "dyn-cloud-fleet"
        )

        private val LEGACY_DEMO_TEMPLATE_IDS = setOf(
            "tpl-win-console-hidpi",
            "tpl-hardened-ssh"
        )

        private val LEGACY_DEMO_TODO_IDS = setOf(
            "todo-cert-rotate",
            "todo-k8s-etcd"
        )

        private val LEGACY_DEMO_SMART_FOLDER_NAMES = setOf(
            "All Production Linux Nodes",
            "All Remote Desktop (RDP/VNC)"
        )

        private val LEGACY_DEMO_COMMAND_TASK_NAMES = setOf(
            "Cluster Pod Health Audit"
        )

        private val LEGACY_DEMO_MACRO_NAMES = setOf(
            "Windows Domain Elevated Sign-In + 2FA Macro"
        )

        /**
         * Returns `true` when [doc] is the auto-seeded legacy `v1.1.1` demo vault (`nxts-enterprise-core`)
         * with zero user-added connections, allowing `ActiveVaultSessionStore` to discard it cleanly on upgrade.
         */
        fun isPureLegacyStarterDemoVault(doc: NxtsVaultDocument): Boolean {
            if (doc.documentId != "nxts-enterprise-core") return false
            val hasUserConnections = doc.connections.any { it.id !in LEGACY_DEMO_CONNECTION_IDS }
            val hasUserFolders = doc.folders.any { it.id !in LEGACY_DEMO_FOLDER_IDS }
            val hasUserCredentials = doc.credentials.any { it.id !in LEGACY_DEMO_CREDENTIAL_IDS }
            return !hasUserConnections && !hasUserFolders && !hasUserCredentials
        }

        /**
         * Strips any legacy `v1.1.1` demo entries if a user created custom connections inside the old starter vault.
         */
        fun sanitizeLegacyDemoEntries(doc: NxtsVaultDocument): NxtsVaultDocument {
            if (doc.documentId != "nxts-enterprise-core") return doc
            val referencedFolderIds = doc.connections
                .filterNot { it.id in LEGACY_DEMO_CONNECTION_IDS }
                .mapNotNull { it.parentFolderId }
                .toSet()
            return doc.copy(
                documentId = DEFAULT_VAULT_DOCUMENT_ID,
                name = DEFAULT_VAULT_NAME,
                rootInheritable = InheritableProperties(),
                folders = doc.folders.filter { it.id !in LEGACY_DEMO_FOLDER_IDS || it.id in referencedFolderIds },
                smartFolders = doc.smartFolders.filterNot { it.name in LEGACY_DEMO_SMART_FOLDER_NAMES },
                dynamicFolders = doc.dynamicFolders.filterNot { it.id in LEGACY_DEMO_DYNAMIC_FOLDER_IDS },
                connections = doc.connections.filterNot { it.id in LEGACY_DEMO_CONNECTION_IDS },
                credentials = doc.credentials.filterNot { it.id in LEGACY_DEMO_CREDENTIAL_IDS },
                gateways = doc.gateways.filterNot { it.id in LEGACY_DEMO_GATEWAY_IDS },
                commandTasks = doc.commandTasks.filterNot { it.name in LEGACY_DEMO_COMMAND_TASK_NAMES },
                keySequenceTasks = doc.keySequenceTasks.filterNot { it.name in LEGACY_DEMO_MACRO_NAMES },
                templates = doc.templates.filterNot { it.id in LEGACY_DEMO_TEMPLATE_IDS },
                todoItems = doc.todoItems.filterNot { it.id in LEGACY_DEMO_TODO_IDS },
                recentConnectionIds = doc.recentConnectionIds.filterNot { it in LEGACY_DEMO_CONNECTION_IDS }
            )
        }

        /**
         * Clean, zero-clutter default empty vault initialized on first launch.
         */
        fun createEnterpriseStarterVault(): NxtsVaultDocument = NxtsVaultDocument(
            documentId = DEFAULT_VAULT_DOCUMENT_ID,
            name = DEFAULT_VAULT_NAME,
            revision = 1L
        )
    }
}
