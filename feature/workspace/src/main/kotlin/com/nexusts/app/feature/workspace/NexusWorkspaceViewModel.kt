package com.nexusts.app.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexusts.app.core.ui.input.NexusShortcutAction
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
import com.nexusts.app.core.vault.gateway.GatewayHealthReport
import com.nexusts.app.core.vault.gateway.SecureGatewayTunnelManager
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
import com.nexusts.app.protocols.ssh.bridge.SshTerminalSessionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class AdHocLaunchOverride(
    val overrideCredentialId: String? = null,
    val adHocUsername: String = "",
    val adHocDomain: String = "",
    val adHocPassword: String = "",
    val forceRdpConsoleAdmin: Boolean? = null,
    val appliedTemplateId: String? = null
)

data class WorkspaceUiState(
    val activeRailId: String = "connections",
    val isSidebarVisible: Boolean = true,
    val isInspectorVisible: Boolean = true,
    val isBottomDrawerVisible: Boolean = false,
    val isQuickConnectVisible: Boolean = false,
    val isNewConnectionDialogVisible: Boolean = false,
    val editingConnection: VaultConnection? = null,
    val isFolderDialogVisible: Boolean = false,
    val editingFolder: VaultFolder? = null,
    val connectWithOptionsTarget: VaultConnection? = null,
    val isNewSmartFolderDialogVisible: Boolean = false,
    val isNewTemplateDialogVisible: Boolean = false,
    val isLockVaultDialogVisible: Boolean = false,
    val sidebarFilterQuery: String = "",
    val useSharedTeamDriveZone: Boolean = false,
    val remoteKeyCaptureEnabled: Boolean = false,
    val broadcastModeEnabled: Boolean = false,
    val isVaultLocked: Boolean = false,
    val vaultDocument: NxtsVaultDocument = NexusWorkspaceViewModel.createEnterpriseStarterVault(),
    val selectedConnectionId: String? = null,
    val selectedSmartFolderId: String? = null,
    val selectedDynamicFolderId: String? = null,
    val hydratedDynamicConnections: Map<String, List<VaultConnection>> = emptyMap(),
    val gatewayHealthReports: Map<String, GatewayHealthReport> = emptyMap(),
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

    val tilingController = WorkspaceTilingController()
    private val rtszImporter = RtszDocumentImporter()
    private val vaultExporter = VaultDocumentExporter()

    private val _uiState = MutableStateFlow(
        WorkspaceUiState(
            vaultDocument = activeVaultStore.snapshot.value.vaultDocument,
            isVaultLocked = activeVaultStore.snapshot.value.isVaultLocked,
            selectedConnectionId = activeVaultStore.snapshot.value.vaultDocument.connections.firstOrNull()?.id
        )
    )
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private val sshControllers = ConcurrentHashMap<String, SshTerminalSessionController>()
    private val rdpSessions = ConcurrentHashMap<String, RdpManagedSession>()
    private val adHocOverrides = ConcurrentHashMap<String, AdHocLaunchOverride>()

    init {
        viewModelScope.launch {
            var lastSeenMutation = activeVaultStore.snapshot.value.mutationRevision
            activeVaultStore.snapshot.collect { shared ->
                if (shared.mutationRevision != lastSeenMutation) {
                    lastSeenMutation = shared.mutationRevision
                    rdpSessions.values.forEach { runCatching { it.close() } }
                    rdpSessions.clear()
                }
                _uiState.update { current ->
                    val validSelectedId = current.selectedConnectionId
                        ?.takeIf { selId -> shared.vaultDocument.connections.any { it.id == selId } }
                        ?: shared.vaultDocument.connections.firstOrNull()?.id
                    current.copy(
                        vaultDocument = shared.vaultDocument,
                        isVaultLocked = shared.isVaultLocked,
                        selectedConnectionId = validSelectedId
                    )
                }
            }
        }
        viewModelScope.launch {
            automationEngine.broadcastBus.collect { packet ->
                val tabs = tilingController.allActiveTabs().filter { it.broadcastEnabled }
                tabs.forEach { tab ->
                    sshControllers[tab.tabId]?.sendUserBytes(packet.payloadBytes)
                }
            }
        }
    }

    fun selectRailDestination(id: String) {
        _uiState.update { it.copy(activeRailId = id) }
    }

    fun openNewConnectionDialog() {
        _uiState.update { it.copy(editingConnection = null, isNewConnectionDialogVisible = true) }
    }

    fun openEditConnectionDialog(connection: VaultConnection) {
        _uiState.update { it.copy(editingConnection = connection, isNewConnectionDialogVisible = true) }
    }

    fun dismissNewConnectionDialog() {
        _uiState.update { it.copy(editingConnection = null, isNewConnectionDialogVisible = false) }
    }

    fun openNewFolderDialog() {
        _uiState.update { it.copy(editingFolder = null, isFolderDialogVisible = true) }
    }

    fun openEditFolderDialog(folder: VaultFolder) {
        _uiState.update { it.copy(editingFolder = folder, isFolderDialogVisible = true) }
    }

    fun dismissFolderDialog() {
        _uiState.update { it.copy(editingFolder = null, isFolderDialogVisible = false) }
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
        val localTab = WorkspaceSessionTab(
            connectionId = "local-shell-${System.currentTimeMillis()}",
            title = "Local Terminal",
            subtitleHost = "127.0.0.1",
            protocol = SessionProtocolKind.SSH,
            status = SessionLifecycleStatus.CONNECTED,
            broadcastEnabled = _uiState.value.broadcastModeEnabled
        )
        _uiState.update { it.copy(activeRailId = "connections") }
        tilingController.openTab(localTab)
        appendLog("[Terminal] Opened local Googlebook OS terminal tab (${localTab.tabId.take(8)})")
    }

    fun effectiveProfileForSelected(): EffectiveConnectionProfile? {
        val state = _uiState.value
        val conn = state.vaultDocument.connections.firstOrNull { it.id == state.selectedConnectionId }
            ?: return null
        return inheritanceResolver.resolveEffectiveProfile(state.vaultDocument, conn)
    }

    fun resolveConnectionAndSecret(connectionId: String, tabId: String? = null): Pair<VaultConnection?, ResolvedSessionSecret> {
        val doc = _uiState.value.vaultDocument
        val dynamicAll = _uiState.value.hydratedDynamicConnections.values.flatten()
        val rawConn = doc.connections.firstOrNull { it.id == connectionId }
            ?: dynamicAll.firstOrNull { it.id == connectionId }
            ?: return null to ResolvedSessionSecret(username = "admin")

        val override = tabId?.let { adHocOverrides[it] }
        val appliedTemplate = override?.appliedTemplateId?.let { tplId ->
            doc.templates.firstOrNull { it.id == tplId }
        }
        val templatedConn = if (appliedTemplate != null && appliedTemplate.protocol == rawConn.protocol) {
            rawConn.copy(settings = appliedTemplate.settings)
        } else {
            rawConn
        }
        val conn = if (override?.forceRdpConsoleAdmin != null && templatedConn.settings is ProtocolSpecificSettings.Rdp) {
            templatedConn.copy(
                settings = (templatedConn.settings as ProtocolSpecificSettings.Rdp).copy(
                    connectToConsoleAdmin = override.forceRdpConsoleAdmin
                )
            )
        } else {
            templatedConn
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
        return sshControllers.getOrPut(tab.tabId) {
            val (conn, secret) = resolveConnectionAndSecret(tab.connectionId, tab.tabId)
            val sshSettings = conn?.settings as? ProtocolSpecificSettings.Ssh
            val doc = _uiState.value.vaultDocument
            val bastion = sshSettings?.bastionJumpConnectionId?.let { id ->
                doc.connections.firstOrNull { it.id == id }?.host
            }
            SshTerminalSessionController(
                title = tab.title,
                host = conn?.host ?: tab.subtitleHost,
                port = sshSettings?.port ?: 22,
                username = secret.username.ifBlank { "root" },
                portForwards = sshSettings?.portForwards.orEmpty(),
                enableX11Forwarding = sshSettings?.enableX11Forwarding ?: false,
                bastionHost = bastion
            )
        }
    }

    fun getOrCreateRdpSession(tab: WorkspaceSessionTab): RdpManagedSession {
        return rdpSessions.getOrPut(tab.tabId) {
            val (conn, secret) = resolveConnectionAndSecret(tab.connectionId, tab.tabId)
            val fallbackConn = conn ?: VaultConnection(
                id = tab.connectionId,
                name = tab.title,
                host = tab.subtitleHost,
                protocol = VaultProtocolType.RDP,
                settings = ProtocolSpecificSettings.Rdp()
            )
            val settings = (fallbackConn.settings as? ProtocolSpecificSettings.Rdp)
                ?: ProtocolSpecificSettings.Rdp()
            RdpManagedSession(
                connection = fallbackConn,
                settings = settings,
                secret = secret
            )
        }
    }

    fun launchConnection(
        connection: VaultConnection,
        placement: QuickLaunchPlacement = QuickLaunchPlacement.ACTIVE_PANE_TAB,
        onDetachWindowRequested: ((WorkspaceSessionTab) -> Unit)? = null
    ) {
        val profile = inheritanceResolver.resolveEffectiveProfile(_uiState.value.vaultDocument, connection)
        val tab = WorkspaceSessionTab(
            connectionId = connection.id,
            title = connection.name,
            subtitleHost = connection.host,
            protocol = mapProtocolToUiKind(connection.protocol),
            status = SessionLifecycleStatus.CONNECTED,
            environmentTag = profile.effectiveEnvironmentBadge,
            customColorHex = profile.effectiveColorAccentHex,
            broadcastEnabled = _uiState.value.broadcastModeEnabled
        )
        recordRecentConnection(connection.id)
        appendLog("[Connect] Opening ${connection.protocol.name} session '${connection.name}' (${connection.host})")

        when (placement) {
            QuickLaunchPlacement.ACTIVE_PANE_TAB -> tilingController.openTab(tab)
            QuickLaunchPlacement.SPLIT_RIGHT_PANE -> tilingController.splitPane(
                orientation = SplitOrientation.HORIZONTAL,
                tabForNewPane = tab
            )
            QuickLaunchPlacement.DETACHED_NEW_WINDOW -> onDetachWindowRequested?.invoke(tab)
        }
    }

    fun openConnectWithOptions(connection: VaultConnection) {
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
        val profile = inheritanceResolver.resolveEffectiveProfile(_uiState.value.vaultDocument, connection)
        val tplAccent = override.appliedTemplateId?.let { id ->
            _uiState.value.vaultDocument.templates.firstOrNull { it.id == id }?.colorAccentHex
        }
        val consoleSuffix = if (override.forceRdpConsoleAdmin == true && !connection.name.contains("/admin")) {
            " (/admin)"
        } else {
            ""
        }
        val tab = WorkspaceSessionTab(
            connectionId = connection.id,
            title = connection.name + consoleSuffix,
            subtitleHost = connection.host,
            protocol = mapProtocolToUiKind(connection.protocol),
            status = SessionLifecycleStatus.CONNECTED,
            environmentTag = profile.effectiveEnvironmentBadge,
            customColorHex = tplAccent ?: profile.effectiveColorAccentHex,
            broadcastEnabled = _uiState.value.broadcastModeEnabled
        )
        adHocOverrides[tab.tabId] = override
        recordRecentConnection(connection.id)
        _uiState.update { it.copy(connectWithOptionsTarget = null, activeRailId = "connections") }
        appendLog("[Connect+Options] Launched '${connection.name}' with custom session overrides")

        when (placement) {
            QuickLaunchPlacement.ACTIVE_PANE_TAB -> tilingController.openTab(tab)
            QuickLaunchPlacement.SPLIT_RIGHT_PANE -> tilingController.splitPane(
                orientation = SplitOrientation.HORIZONTAL,
                tabForNewPane = tab
            )
            QuickLaunchPlacement.DETACHED_NEW_WINDOW -> onDetachWindowRequested?.invoke(tab)
        }
    }

    fun connectAllInFolder(
        folderId: String,
        onDetachWindowRequested: ((WorkspaceSessionTab) -> Unit)? = null
    ) {
        val doc = _uiState.value.vaultDocument
        val children = doc.connections.filter {
            it.parentFolderId == folderId && it.id !in doc.tombstonedObjectIds
        }
        if (children.isEmpty()) return
        _uiState.update { it.copy(activeRailId = "connections") }
        children.forEach { conn ->
            launchConnection(conn, QuickLaunchPlacement.ACTIVE_PANE_TAB, onDetachWindowRequested)
        }
        val folderName = doc.folders.firstOrNull { it.id == folderId }?.name ?: folderId
        appendLog("[Bulk Connect] Opened ${children.size} session(s) from folder '$folderName'")
    }

    fun reconnectSessionTab(tab: WorkspaceSessionTab) {
        sshControllers.remove(tab.tabId)?.close()
        rdpSessions.remove(tab.tabId)?.close()
        tilingController.updateTab(tab.tabId) { it.copy(status = SessionLifecycleStatus.RECONNECTING) }
        tilingController.updateTab(tab.tabId) { it.copy(status = SessionLifecycleStatus.CONNECTED) }
        appendLog("[Session] Reconnected '${tab.title}' (${tab.subtitleHost})")
    }

    fun updateSidebarFilterQuery(query: String) {
        _uiState.update { it.copy(sidebarFilterQuery = query) }
    }

    fun toggleFavoriteConnection(connectionId: String) {
        activeVaultStore.updateVaultDocument { doc ->
            doc.copy(
                revision = doc.revision + 1L,
                connections = doc.connections.map {
                    if (it.id == connectionId) it.copy(favorite = !it.favorite, updatedAtEpochMs = System.currentTimeMillis()) else it
                }
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
    }

    fun duplicateConnection(connection: VaultConnection) {
        val cloned = connection.copy(
            id = UUID.randomUUID().toString(),
            name = "${connection.name} (Copy)",
            updatedAtEpochMs = System.currentTimeMillis()
        )
        saveConnectionToVault(cloned)
    }

    fun deleteConnection(connectionId: String) {
        tilingController.allActiveTabs()
            .filter { it.connectionId == connectionId }
            .forEach { closeSessionTab(it.tabId) }

        activeVaultStore.updateVaultDocument { doc ->
            doc.copy(
                revision = doc.revision + 1L,
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
                    updatedDoc.connections.firstOrNull()?.id
                } else {
                    state.selectedConnectionId
                }
            )
        }
        appendLog("[Vault] Deleted connection ($connectionId) with tombstone marker")
    }

    fun toggleDynamicFolder(dynamicFolder: DynamicFolder) {
        val currentlyOpen = _uiState.value.selectedDynamicFolderId == dynamicFolder.id
        _uiState.update { it.copy(selectedDynamicFolderId = if (currentlyOpen) null else dynamicFolder.id) }
        if (!currentlyOpen && !_uiState.value.hydratedDynamicConnections.containsKey(dynamicFolder.id)) {
            reloadDynamicFolder(dynamicFolder)
        }
    }

    fun reloadDynamicFolder(dynamicFolder: DynamicFolder) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    inheritanceResolver.evaluateDynamicFolder(dynamicFolder)
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

    fun openNewSmartFolderDialog() {
        _uiState.update { it.copy(isNewSmartFolderDialogVisible = true) }
    }

    fun dismissNewSmartFolderDialog() {
        _uiState.update { it.copy(isNewSmartFolderDialogVisible = false) }
    }

    fun saveSmartFolderToVault(smartFolder: SmartFolder) {
        activeVaultStore.updateVaultDocument { doc ->
            doc.copy(
                revision = doc.revision + 1L,
                smartFolders = doc.smartFolders + smartFolder
            )
        }
        _uiState.update {
            it.copy(
                isNewSmartFolderDialogVisible = false,
                selectedSmartFolderId = smartFolder.id,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
            )
        }
        appendLog("[SmartFolder] Created dynamic filter '${smartFolder.name}'")
    }

    fun openNewTemplateDialog() {
        _uiState.update { it.copy(isNewTemplateDialogVisible = true) }
    }

    fun dismissNewTemplateDialog() {
        _uiState.update { it.copy(isNewTemplateDialogVisible = false) }
    }

    fun saveTemplateToVault(template: ConnectionTemplate) {
        activeVaultStore.updateVaultDocument { doc ->
            doc.copy(
                revision = doc.revision + 1L,
                templates = doc.templates + template
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

    fun createConnectionFromTemplate(template: ConnectionTemplate) {
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
            doc.copy(
                revision = doc.revision + 1L,
                todoItems = listOf(item) + doc.todoItems
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[To-Do] Added task '${item.title}'")
    }

    fun toggleTodoItem(todoId: String) {
        activeVaultStore.updateVaultDocument { doc ->
            doc.copy(
                revision = doc.revision + 1L,
                todoItems = doc.todoItems.map {
                    if (it.id == todoId) it.copy(completed = !it.completed, updatedAtEpochMs = System.currentTimeMillis()) else it
                }
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
    }

    fun updateConnectionNotesAndCustomProps(
        connectionId: String,
        notes: String,
        customProps: List<CustomPropertyEntry>,
        colorAccentHex: String?
    ) {
        activeVaultStore.updateVaultDocument { doc ->
            doc.copy(
                revision = doc.revision + 1L,
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

    private fun recordRecentConnection(connectionId: String) {
        activeVaultStore.updateVaultDocument { doc ->
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
        val existing = _uiState.value.vaultDocument.connections.firstOrNull { it.id == candidate.id }
        if (existing != null) {
            launchConnection(existing, placement, onDetachWindowRequested)
            return
        }
        // Ad-hoc URI connection synthesized on the fly
        val adHocTab = WorkspaceSessionTab(
            connectionId = candidate.id,
            title = candidate.title,
            subtitleHost = candidate.subtitle,
            protocol = candidate.protocol,
            status = SessionLifecycleStatus.CONNECTED
        )
        appendLog("[QuickConnect] Launched ad-hoc ${candidate.protocol.name} -> ${candidate.subtitle}")
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
        adHocOverrides.remove(tabId)
        sshControllers.remove(tabId)?.close()
        rdpSessions.remove(tabId)?.close()
        tilingController.closeTab(tabId)
    }

    fun handleShortcut(
        action: NexusShortcutAction,
        onOpenVaultPicker: (() -> Unit)? = null,
        onSyncDriveNow: (() -> Unit)? = null,
        onDetachActiveTab: (WorkspaceSessionTab) -> Unit
    ) {
        when (action) {
            NexusShortcutAction.QUICK_CONNECT_PALETTE ->
                _uiState.update { it.copy(isQuickConnectVisible = !it.isQuickConnectVisible) }
            NexusShortcutAction.NEW_CONNECTION_DIALOG ->
                _uiState.update {
                    val nextOpen = !it.isNewConnectionDialogVisible
                    it.copy(
                        editingConnection = if (nextOpen) null else it.editingConnection,
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
                if (activeTab != null) closeSessionTab(activeTab.tabId)
            }
            NexusShortcutAction.DETACH_TAB_TO_NEW_WINDOW -> {
                val activeTab = findFocusedActiveTab(tilingController.focusedPaneId.value)
                if (activeTab != null) {
                    tilingController.extractTabForDetachedWindow(activeTab.tabId)?.let(onDetachActiveTab)
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
        val staleTabs = rdpSessions.entries.filter { it.value.connection.id == connection.id }.map { it.key }
        staleTabs.forEach { tabId -> rdpSessions.remove(tabId)?.close() }

        var wasUpdated = false
        activeVaultStore.updateVaultDocument { doc ->
            val nextRev = doc.revision + 1L
            val nextClock = doc.vectorClock.toMutableMap().apply {
                put("googlebook", nextRev)
            }
            val exists = doc.connections.any { it.id == connection.id }
            wasUpdated = exists
            val updatedConnections = if (exists) {
                doc.connections.map { if (it.id == connection.id) connection else it }
            } else {
                doc.connections + connection
            }
            doc.copy(
                revision = nextRev,
                vectorClock = nextClock,
                connections = updatedConnections
            )
        }
        _uiState.update { state ->
            state.copy(
                activeRailId = "connections",
                selectedConnectionId = connection.id,
                isNewConnectionDialogVisible = false,
                editingConnection = null,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
            )
        }
        tilingController.allActiveTabs()
            .filter { it.connectionId == connection.id }
            .forEach { tab ->
                tilingController.updateTab(tab.tabId) {
                    it.copy(title = connection.name, subtitleHost = connection.host)
                }
            }
        val verb = if (wasUpdated) "Updated" else "Added"
        appendLog("[Vault] $verb ${connection.protocol.name} connection '${connection.name}' (${connection.host})")
    }

    fun saveFolderAndCredentialToVault(folder: VaultFolder, inlineNewCredential: VaultCredential? = null) {
        // Refresh cached RDP sessions so any child RDP connection inheriting from this folder picks up new credentials on next connect/render
        rdpSessions.values.forEach { runCatching { it.close() } }
        rdpSessions.clear()

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

        activeVaultStore.updateVaultDocument { doc ->
            val nextRev = doc.revision + 1L
            val nextClock = doc.vectorClock.toMutableMap().apply {
                put("googlebook", nextRev)
            }
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
            doc.copy(
                revision = nextRev,
                vectorClock = nextClock,
                folders = updatedFolders,
                credentials = updatedCredentials
            )
        }
        _uiState.update { state ->
            state.copy(
                isFolderDialogVisible = false,
                editingFolder = null,
                vaultDocument = activeVaultStore.snapshot.value.vaultDocument
            )
        }
        val credSummary = inlineNewCredential?.let { " + saved folder credential '${it.name}'" }.orEmpty()
        appendLog("[Folder] Saved folder '${finalFolder.name}' (${finalFolder.inheritable.credentialMode.name})$credSummary")
    }

    fun addCredentialToVault(credential: VaultCredential) {
        activeVaultStore.updateVaultDocument { doc ->
            doc.copy(
                revision = doc.revision + 1L,
                credentials = doc.credentials + credential
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[Credentials] Stored '${credential.name}' (${credential.sourceKind.name})")
    }

    fun addGatewayToVault(gateway: SecureGatewayConfig) {
        activeVaultStore.updateVaultDocument { doc ->
            doc.copy(
                revision = doc.revision + 1L,
                gateways = doc.gateways + gateway
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[Gateway] Registered ${gateway.kind.name} '${gateway.name}' (${gateway.host}:${gateway.port})")
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

    fun addCommandTaskToVault(task: CommandTask) {
        activeVaultStore.updateVaultDocument { doc ->
            doc.copy(
                revision = doc.revision + 1L,
                commandTasks = doc.commandTasks + task
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[Automation] Added task '${task.name}'")
    }

    fun executeCommandTask(task: CommandTask) {
        val profile = effectiveProfileForSelected()
        val rendered = if (profile != null) {
            automationEngine.renderCommandTask(task, profile)
        } else {
            if (task.appendNewline) "${task.commandTemplate}\r\n" else task.commandTemplate
        }
        val bytes = rendered.replace("\n", "\r\n").replace("\r\r\n", "\r\n").encodeToByteArray()
        if (task.broadcastToAllSelectedSessions) {
            automationEngine.emitBroadcastBytes(bytes, originTabId = null)
        } else {
            val focused = findFocusedActiveTab(tilingController.focusedPaneId.value)
            if (focused != null) {
                sshControllers[focused.tabId]?.sendUserBytes(bytes)
            }
        }
        appendLog("[Automation] Executed '${task.name}': ${task.commandTemplate}")
    }

    fun executeKeySequenceTask(macro: KeySequenceTask) {
        val doc = _uiState.value.vaultDocument
        val focusedTab = findFocusedActiveTab(tilingController.focusedPaneId.value)
        val targetConn = doc.connections.firstOrNull { it.id == focusedTab?.connectionId }
            ?: doc.connections.firstOrNull { it.id == _uiState.value.selectedConnectionId }
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
                    if (focusedTab != null) {
                        sshControllers[focusedTab.tabId]?.sendUserBytes(ch.encodeToByteArray())
                    }
                },
                sendSpecialKey = { token ->
                    when (token) {
                        "CTRL+ALT+DEL" -> focusedTab?.let { rdpSessions[it.tabId]?.sendCtrlAltDel() }
                        "ENTER" -> focusedTab?.let { sshControllers[it.tabId]?.sendUserBytes("\r".encodeToByteArray()) }
                        "TAB" -> focusedTab?.let { sshControllers[it.tabId]?.sendUserBytes("\t".encodeToByteArray()) }
                        "ESC" -> focusedTab?.let { sshControllers[it.tabId]?.sendUserBytes(byteArrayOf(0x1B)) }
                        "CTRL+C" -> focusedTab?.let { sshControllers[it.tabId]?.sendUserBytes(byteArrayOf(0x03)) }
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

    fun forwardRawKeyToFocusedSession(packet: RawHardwareKeyPacket): Boolean {
        val activeTab = findFocusedActiveTab(tilingController.focusedPaneId.value) ?: return false
        return when (activeTab.protocol) {
            SessionProtocolKind.RDP -> rdpSessions[activeTab.tabId]?.sendHardwareKey(packet) ?: false
            SessionProtocolKind.SSH, SessionProtocolKind.TELNET, SessionProtocolKind.SERIAL -> {
                if (packet.isKeyDown && packet.unicodeChar > 0) {
                    val bytes = packet.unicodeChar.toChar().toString().encodeToByteArray()
                    if (activeTab.broadcastEnabled) {
                        automationEngine.emitBroadcastBytes(bytes, activeTab.tabId)
                    } else {
                        sshControllers[activeTab.tabId]?.sendUserBytes(bytes)
                    }
                    true
                } else {
                    false
                }
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
            doc.copy(
                revision = doc.revision + 1L,
                credentials = doc.credentials + newCred
            )
        }
        _uiState.update { it.copy(vaultDocument = activeVaultStore.snapshot.value.vaultDocument) }
        appendLog("[SSH Agent] Generated OpenSSH Ed25519 key ${generated.sha256_fingerprint}")
    }

    fun lockVaultWithPassphrase(passphrase: CharArray) {
        viewModelScope.launch {
            val sealed = vaultSerializer.sealDocument(_uiState.value.vaultDocument, passphrase)
            activeVaultStore.lockWithSealedBytes(sealed)
            _uiState.update {
                it.copy(
                    isVaultLocked = true,
                    isLockVaultDialogVisible = false,
                    isSidebarVisible = true
                )
            }
            appendLog("[Vault] Sealed .nxts container (${sealed.size} bytes, Argon2id + AES-256-GCM)")
        }
    }

    fun unlockVaultWithPassphrase(passphrase: CharArray) {
        val blob = activeVaultStore.snapshot.value.sealedLockedVaultSnapshot
        if (blob == null) {
            activeVaultStore.clearLockWithoutBlob()
            _uiState.update { it.copy(isVaultLocked = false) }
            return
        }
        viewModelScope.launch {
            runCatching {
                vaultSerializer.unsealDocument(blob, passphrase)
            }.onSuccess { doc ->
                activeVaultStore.replaceUnlockedVault(doc)
                _uiState.update {
                    it.copy(
                        vaultDocument = doc,
                        selectedConnectionId = doc.connections.firstOrNull()?.id,
                        isVaultLocked = false
                    )
                }
                appendLog("[Vault] Unlocked '${doc.name}' (Revision ${doc.revision})")
            }.onFailure { err ->
                appendLog("[Vault Error] ${err.message}")
            }
        }
    }

    fun importVaultOrRtszBytes(fileName: String, bytes: ByteArray, passphraseOrNull: CharArray? = null) {
        val hasNxtsMagic = bytes.size >= 4 &&
            bytes[0] == 0x4E.toByte() &&
            bytes[1] == 0x58.toByte() &&
            bytes[2] == 0x54.toByte() &&
            bytes[3] == 0x53.toByte()
        val isRtszFormat = !hasNxtsMagic && (
            fileName.endsWith(".rtsz", true) ||
                fileName.endsWith(".rtsx", true) ||
                (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) ||
                bytes.decodeToString(0, minOf(bytes.size, 64), throwOnInvalidSequence = false).trimStart().startsWith("<")
            )

        if (!isRtszFormat && passphraseOrNull == null) {
            activeVaultStore.stageEncryptedBytesForUnlock(bytes, fileName)
            _uiState.update {
                it.copy(
                    isVaultLocked = true,
                    isSidebarVisible = true
                )
            }
            appendLog("[Import] Staged encrypted vault '$fileName' — enter passphrase in sidebar to unlock.")
            return
        }

        viewModelScope.launch {
            runCatching {
                if (isRtszFormat) {
                    rtszImporter.importRtszBytes(bytes, fileName.substringBeforeLast('.'))
                } else {
                    vaultSerializer.unsealDocument(bytes, passphraseOrNull!!)
                }
            }.onSuccess { importedDoc ->
                activeVaultStore.replaceUnlockedVault(importedDoc)
                _uiState.update {
                    it.copy(
                        vaultDocument = importedDoc,
                        selectedConnectionId = importedDoc.connections.firstOrNull()?.id,
                        isVaultLocked = false
                    )
                }
                appendLog("[Import] Loaded '${importedDoc.name}' (${importedDoc.connections.size} connections)")
            }.onFailure { err ->
                appendLog("[Import Error] ${err.message}")
            }
        }
    }

    fun buildVaultExportPayload(
        format: VaultExportFormat,
        exportPassphrase: CharArray = "NexusTS-Vault-Export".toCharArray()
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
                    ?: vaultSerializer.sealDocument(doc, exportPassphrase.copyOf())
            }
            VaultExportFormat.RTSZ_ROYAL_ARCHIVE -> {
                vaultExporter.exportToRtszBytes(doc, includeSecrets = false)
            }
            VaultExportFormat.ENTERPRISE_CSV -> {
                vaultExporter.exportToEnterpriseCsv(doc).encodeToByteArray()
            }
        }
        appendLog("[Export] Prepared '${suggestedName}' (${bytes.size} bytes, format=${format.name})")
        return suggestedName to bytes
    }

    fun buildQuickConnectCandidates(): List<QuickConnectCandidate> {
        val doc = _uiState.value.vaultDocument
        return doc.connections.map { conn ->
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
        sshControllers.values.forEach { runCatching { it.close() } }
        sshControllers.clear()
        rdpSessions.values.forEach { runCatching { it.close() } }
        rdpSessions.clear()
        gatewayTunnelManager.closeAllLeases()
        super.onCleared()
    }

    companion object {
        fun mapProtocolToUiKind(proto: VaultProtocolType): SessionProtocolKind = when (proto) {
            VaultProtocolType.RDP -> SessionProtocolKind.RDP
            VaultProtocolType.SSH -> SessionProtocolKind.SSH
            VaultProtocolType.TELNET -> SessionProtocolKind.TELNET
            VaultProtocolType.SERIAL -> SessionProtocolKind.SERIAL
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

        fun createEnterpriseStarterVault(): NxtsVaultDocument {
            val opsCredId = "cred-ops-root"
            val winCredId = "cred-corp-domain"
            val bastionGwId = "gw-eu-bastion"

            val folderProdId = "folder-prod-core"
            val folderWinAdId = "folder-win-ad"
            val folderCloudId = "folder-hybrid-cloud"

            return NxtsVaultDocument(
                documentId = "nxts-enterprise-core",
                name = "NexusCorp Infrastructure.nxts",
                revision = 12L,
                rootInheritable = InheritableProperties(
                    credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                    explicitCredentialId = opsCredId,
                    gatewayMode = InheritanceMode.NONE_DIRECT,
                    customProperties = listOf(
                        CustomPropertyEntry("CostCenter", "INFRA-EU-01"),
                        CustomPropertyEntry("PagerDutyEscalation", "P0-SRE-CORE")
                    )
                ),
                folders = listOf(
                    VaultFolder(
                        id = folderWinAdId,
                        name = "Windows Server Farm (AD)",
                        description = "Tier-0 Active Directory Domain Controllers & RDS Session Hosts",
                        tags = listOf("ad", "windows", "tier-0"),
                        inheritable = InheritableProperties(
                            credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                            explicitCredentialId = winCredId,
                            gatewayMode = InheritanceMode.OVERRIDE_EXPLICIT,
                            explicitGatewayId = bastionGwId,
                            environmentBadge = "PRODUCTION",
                            displayColorAccentHex = "#6EA2FF",
                            customProperties = listOf(
                                CustomPropertyEntry("ADForest", "corp.nexusts.internal")
                            )
                        )
                    ),
                    VaultFolder(
                        id = folderProdId,
                        name = "Production Core (EU-Central)",
                        description = "Kubernetes Control Plane & Release Artifact Nodes",
                        tags = listOf("prod", "tier-0"),
                        inheritable = InheritableProperties(
                            credentialMode = InheritanceMode.INHERIT_FROM_PARENT,
                            gatewayMode = InheritanceMode.OVERRIDE_EXPLICIT,
                            explicitGatewayId = bastionGwId,
                            environmentBadge = "PRODUCTION",
                            displayColorAccentHex = "#3CE6B8"
                        )
                    ),
                    VaultFolder(
                        id = folderCloudId,
                        name = "Hybrid Cloud & Hypervisors",
                        description = "Proxmox VE, macOS M4 CI Builders & Diagnostic Tooling",
                        tags = listOf("cloud", "infra"),
                        inheritable = InheritableProperties(
                            credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                            explicitCredentialId = winCredId,
                            gatewayMode = InheritanceMode.INHERIT_FROM_PARENT,
                            environmentBadge = "STAGING",
                            displayColorAccentHex = "#FFB86B"
                        )
                    )
                ),
                smartFolders = listOf(
                    SmartFolder(
                        name = "All Production Linux Nodes",
                        filter = SmartFolderFilter(osFamilyFilter = "Linux", environmentFilter = "PRODUCTION")
                    ),
                    SmartFolder(
                        name = "All Remote Desktop (RDP/VNC)",
                        filter = SmartFolderFilter(
                            includeProtocols = listOf(
                                VaultProtocolType.RDP,
                                VaultProtocolType.VNC,
                                VaultProtocolType.APPLE_SCREEN_SHARING
                            )
                        )
                    )
                ),
                dynamicFolders = listOf(
                    DynamicFolder(
                        id = "dyn-cloud-fleet",
                        parentFolderId = folderCloudId,
                        name = "Ephemeral Cloud Fleet (Dynamic JSON)",
                        interpreter = DynamicScriptInterpreter.JSON_HTTP_API,
                        scriptOrEndpointPayload = """{"Objects":[{"Type":"SSH","Name":"ephemeral-gpu-worker-01","ComputerName":"10.88.4.11","Port":22,"OS":"Linux","Tags":["gpu","dynamic"]},{"Type":"RDP","Name":"ephemeral-win-qa-04","ComputerName":"10.88.4.40","Port":3389,"OS":"Windows","Tags":["qa","dynamic"]}]}""",
                        inheritable = InheritableProperties(
                            credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                            explicitCredentialId = opsCredId,
                            displayColorAccentHex = "#59D2FE"
                        )
                    )
                ),
                connections = listOf(
                    VaultConnection(
                        id = "conn-win-rds-01",
                        parentFolderId = folderWinAdId,
                        name = "WinServer-2025-DC01",
                        host = "dc01.corp.nexusts.internal",
                        protocol = VaultProtocolType.RDP,
                        osFamily = "Windows",
                        tags = listOf("ad", "rdp", "smartcard"),
                        notes = "Primary FSMO Role Holder • Requires YubiKey PIV or DomainAdmin + TOTP.",
                        favorite = true,
                        inheritable = InheritableProperties(
                            credentialMode = InheritanceMode.INHERIT_FROM_PARENT,
                            gatewayMode = InheritanceMode.INHERIT_FROM_PARENT,
                            customProperties = listOf(
                                CustomPropertyEntry("Rack", "FRA1-RACK-04"),
                                CustomPropertyEntry("iDRAC", "https://idrac-dc01.corp.internal")
                            )
                        ),
                        settings = ProtocolSpecificSettings.Rdp(
                            connectToConsoleAdmin = false,
                            hiDpiEnabled = true,
                            desktopScaleFactorPercent = 150,
                            enableH264Avc444 = true,
                            redirectClipboard = true,
                            audioRedirectionMode = com.nexusts.app.core.vault.model.RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE,
                            redirectDrives = true,
                            redirectSmartcardYubiKey = true,
                            enableFontSmoothing = true,
                            enableDesktopComposition = true,
                            multiMonitorSpanning = true
                        )
                    ),
                    VaultConnection(
                        id = "conn-win-rds-02",
                        parentFolderId = folderWinAdId,
                        name = "RDS-ConsoleHost-02 (/admin)",
                        host = "rds02.corp.nexusts.internal",
                        protocol = VaultProtocolType.RDP,
                        osFamily = "Windows",
                        tags = listOf("rds", "console", "rdp"),
                        notes = "Direct Console Session 0 maintenance endpoint.",
                        inheritable = InheritableProperties(
                            credentialMode = InheritanceMode.INHERIT_FROM_PARENT,
                            gatewayMode = InheritanceMode.INHERIT_FROM_PARENT
                        ),
                        settings = ProtocolSpecificSettings.Rdp(
                            connectToConsoleAdmin = true,
                            hiDpiEnabled = true,
                            desktopScaleFactorPercent = 125,
                            redirectClipboard = true,
                            audioRedirectionMode = com.nexusts.app.core.vault.model.RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST,
                            redirectPrinters = true,
                            enableFontSmoothing = true,
                            nlaSecurityRequired = true
                        )
                    ),
                    VaultConnection(
                        id = "conn-k8s-bastion-ssh",
                        parentFolderId = folderProdId,
                        name = "k8s-control-plane-01",
                        host = "10.24.0.10",
                        protocol = VaultProtocolType.SSH,
                        osFamily = "Linux",
                        tags = listOf("k8s", "prod"),
                        notes = "Forwards localhost:16443 -> 127.0.0.1:6443 kube-apiserver.",
                        favorite = true,
                        settings = ProtocolSpecificSettings.Ssh(
                            port = 22,
                            enableX11Forwarding = true,
                            portForwards = listOf(
                                SshPortForwardRule(
                                    kind = PortForwardKind.LOCAL,
                                    bindPort = 16443,
                                    targetHost = "127.0.0.1",
                                    targetPort = 6443
                                )
                            )
                        )
                    ),
                    VaultConnection(
                        id = "conn-prod-sftp",
                        parentFolderId = folderProdId,
                        name = "Artifact-Vault-SFTP",
                        host = "artifacts.nexusts.internal",
                        protocol = VaultProtocolType.SFTP,
                        osFamily = "Linux",
                        tags = listOf("sftp", "releases"),
                        settings = ProtocolSpecificSettings.FileTransfer(port = 22)
                    ),
                    VaultConnection(
                        id = "conn-macos-ard",
                        parentFolderId = folderCloudId,
                        name = "macOS-BuildCluster-M4",
                        host = "10.40.8.22",
                        protocol = VaultProtocolType.APPLE_SCREEN_SHARING,
                        osFamily = "macOS",
                        favorite = true,
                        settings = ProtocolSpecificSettings.Vnc(appleArdDiffieHellmanAuth = true)
                    ),
                    VaultConnection(
                        id = "conn-proxmox-ve",
                        parentFolderId = folderCloudId,
                        name = "Proxmox-VE-Cluster",
                        host = "pve01.nexusts.internal",
                        protocol = VaultProtocolType.PROXMOX_VE,
                        osFamily = "Linux",
                        settings = ProtocolSpecificSettings.CloudVirtualization(
                            apiEndpointUrl = "https://pve01.nexusts.internal:8006/api2/json",
                            regionOrCluster = "eu-fra-cluster01"
                        )
                    ),
                    VaultConnection(
                        id = "conn-net-scanner",
                        parentFolderId = folderCloudId,
                        name = "Subnet Port & WoL Diagnostic",
                        host = "127.0.0.1",
                        protocol = VaultProtocolType.UTILITY_PORT_SCANNER,
                        osFamily = "Network",
                        settings = ProtocolSpecificSettings.NetUtility(portScanRangeStart = 20, portScanRangeEnd = 445)
                    )
                ),
                credentials = listOf(
                    VaultCredential(
                        id = opsCredId,
                        name = "SRE Root SSH Identity",
                        sourceKind = CredentialSourceKind.SSH_KEY_PAIR,
                        username = "sre-admin",
                        keyFingerprintSha256 = "SHA256:kX9pNexusTSZeroKnowledgeEd25519Key",
                        totpSecretBase32 = "JBSWY3DPEHPK3PXP"
                    ),
                    VaultCredential(
                        id = winCredId,
                        name = "CORP\\DomainAdmin + YubiKey PIV",
                        sourceKind = CredentialSourceKind.HARDWARE_FIDO2_PASSKEY,
                        username = "adm.architect",
                        domain = "CORP",
                        totpSecretBase32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                    )
                ),
                gateways = listOf(
                    SecureGatewayConfig(
                        id = bastionGwId,
                        name = "EU-Central NexusTS Gateway",
                        kind = GatewayTransportKind.NEXUSTS_SECURE_GATEWAY,
                        host = "gw-eu.nexusts.app",
                        port = 443,
                        credentialId = opsCredId
                    )
                ),
                commandTasks = listOf(
                    CommandTask(
                        name = "Cluster Pod Health Audit",
                        commandTemplate = "kubectl get pods -A -o wide && uptime",
                        broadcastToAllSelectedSessions = true
                    )
                ),
                keySequenceTasks = listOf(
                    KeySequenceTask(
                        name = "Windows Domain Elevated Sign-In + 2FA Macro",
                        macroSequence = "{CTRL+ALT+DEL}{WAIT:350}\$EffectiveUsername\${TAB}\$EffectivePassword\${ENTER}{WAIT:500}\$EffectiveTOTP\${ENTER}",
                        keystrokeDelayMs = 25
                    )
                ),
                templates = listOf(
                    ConnectionTemplate(
                        id = "tpl-win-console-hidpi",
                        name = "Windows Server Console + HiDPI 150%",
                        description = "Connects to /admin console with 150% Retina/HiDPI scale, Smartcard & Clipboard",
                        protocol = VaultProtocolType.RDP,
                        colorAccentHex = "#6EA2FF",
                        settings = ProtocolSpecificSettings.Rdp(
                            connectToConsoleAdmin = true,
                            hiDpiEnabled = true,
                            desktopScaleFactorPercent = 150,
                            redirectClipboard = true,
                            redirectSmartcardYubiKey = true
                        )
                    ),
                    ConnectionTemplate(
                        id = "tpl-hardened-ssh",
                        name = "Hardened Bastion SSH + X11",
                        description = "xterm-256color with X11 forwarding and 15s keep-alive",
                        protocol = VaultProtocolType.SSH,
                        colorAccentHex = "#3CE6B8",
                        settings = ProtocolSpecificSettings.Ssh(
                            enableX11Forwarding = true,
                            keepAliveIntervalSec = 15
                        )
                    )
                ),
                todoItems = listOf(
                    VaultTodoItem(
                        id = "todo-cert-rotate",
                        targetObjectId = "conn-win-rds-01",
                        title = "Rotate LDAPS & RD Gateway TLS leaf certificates before Q4 window",
                        completed = false,
                        priority = "HIGH",
                        dueIsoDate = "2026-12-01"
                    ),
                    VaultTodoItem(
                        id = "todo-k8s-etcd",
                        targetObjectId = "conn-k8s-bastion-ssh",
                        title = "Verify etcd encrypted snapshot replication on EU-Central",
                        completed = true,
                        priority = "NORMAL"
                    )
                ),
                recentConnectionIds = listOf("conn-win-rds-01", "conn-k8s-bastion-ssh")
            )
        }
    }
}
