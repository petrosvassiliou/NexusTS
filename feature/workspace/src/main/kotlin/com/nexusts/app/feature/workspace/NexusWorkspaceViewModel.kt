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
import com.nexusts.app.core.vault.importer.RtszDocumentImporter
import com.nexusts.app.core.vault.inheritance.EffectiveConnectionProfile
import com.nexusts.app.core.vault.inheritance.PropertyInheritanceResolver
import com.nexusts.app.core.vault.model.CommandTask
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.GatewayTransportKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
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
import com.nexusts.app.protocols.rdp.bridge.RdpManagedSession
import com.nexusts.app.protocols.ssh.bridge.SshTerminalSessionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class WorkspaceUiState(
    val activeRailId: String = "connections",
    val isSidebarVisible: Boolean = true,
    val isInspectorVisible: Boolean = true,
    val isBottomDrawerVisible: Boolean = false,
    val isQuickConnectVisible: Boolean = false,
    val remoteKeyCaptureEnabled: Boolean = false,
    val broadcastModeEnabled: Boolean = false,
    val isVaultLocked: Boolean = false,
    val vaultDocument: NxtsVaultDocument = createEnterpriseStarterVault(),
    val selectedConnectionId: String? = null,
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
    private val automationEngine: AutomationTaskEngine
) : ViewModel() {

    val tilingController = WorkspaceTilingController()
    private val rtszImporter = RtszDocumentImporter()

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private val sshControllers = ConcurrentHashMap<String, SshTerminalSessionController>()
    private val rdpSessions = ConcurrentHashMap<String, RdpManagedSession>()
    private var sealedLockedVaultSnapshot: ByteArray? = null

    init {
        val firstConn = _uiState.value.vaultDocument.connections.firstOrNull()
        if (firstConn != null) {
            _uiState.update { it.copy(selectedConnectionId = firstConn.id) }
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

    fun selectConnectionInTree(connectionId: String) {
        _uiState.update { it.copy(selectedConnectionId = connectionId) }
    }

    fun effectiveProfileForSelected(): EffectiveConnectionProfile? {
        val state = _uiState.value
        val conn = state.vaultDocument.connections.firstOrNull { it.id == state.selectedConnectionId }
            ?: return null
        return inheritanceResolver.resolveEffectiveProfile(state.vaultDocument, conn)
    }

    fun resolveConnectionAndSecret(connectionId: String): Pair<VaultConnection?, ResolvedSessionSecret> {
        val doc = _uiState.value.vaultDocument
        val conn = doc.connections.firstOrNull { it.id == connectionId }
            ?: return null to ResolvedSessionSecret(username = "admin")
        val effective = inheritanceResolver.resolveEffectiveProfile(doc, conn)
        val cred = effective.effectiveCredential
        val secret = if (cred != null) {
            ResolvedSessionSecret(
                username = cred.username,
                password = cred.secretPassword,
                domain = cred.domain,
                privateKeyPem = cred.privateKeyPem,
                keyPassphrase = cred.keyPassphrase
            )
        } else {
            ResolvedSessionSecret(username = "root")
        }
        return conn to secret
    }

    fun getOrCreateSshController(tab: WorkspaceSessionTab): SshTerminalSessionController {
        return sshControllers.getOrPut(tab.tabId) {
            val (conn, secret) = resolveConnectionAndSecret(tab.connectionId)
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
            val (conn, secret) = resolveConnectionAndSecret(tab.connectionId)
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
            broadcastEnabled = _uiState.value.broadcastModeEnabled
        )
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
        sshControllers.remove(tabId)?.close()
        rdpSessions.remove(tabId)?.close()
        tilingController.closeTab(tabId)
    }

    fun handleShortcut(action: NexusShortcutAction, onDetachActiveTab: (WorkspaceSessionTab) -> Unit) {
        when (action) {
            NexusShortcutAction.QUICK_CONNECT_PALETTE ->
                _uiState.update { it.copy(isQuickConnectVisible = !it.isQuickConnectVisible) }
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
            NexusShortcutAction.TOGGLE_BROADCAST_INPUT -> toggleGlobalBroadcast()
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
            else -> Unit
        }
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
        _uiState.update { state ->
            state.copy(
                vaultDocument = state.vaultDocument.copy(
                    revision = state.vaultDocument.revision + 1L,
                    credentials = state.vaultDocument.credentials + newCred
                )
            )
        }
        appendLog("[SSH Agent] Generated OpenSSH Ed25519 key ${generated.sha256_fingerprint}")
    }

    fun lockVaultWithPassphrase(passphrase: CharArray) {
        viewModelScope.launch {
            val sealed = vaultSerializer.sealDocument(_uiState.value.vaultDocument, passphrase)
            sealedLockedVaultSnapshot = sealed
            _uiState.update { it.copy(isVaultLocked = true) }
            appendLog("[Vault] Sealed .nxts container (${sealed.size} bytes, Argon2id + AES-256-GCM)")
        }
    }

    fun unlockVaultWithPassphrase(passphrase: CharArray) {
        val blob = sealedLockedVaultSnapshot
        if (blob == null) {
            _uiState.update { it.copy(isVaultLocked = false) }
            return
        }
        viewModelScope.launch {
            runCatching {
                vaultSerializer.unsealDocument(blob, passphrase)
            }.onSuccess { doc ->
                _uiState.update { it.copy(vaultDocument = doc, isVaultLocked = false) }
                appendLog("[Vault] Unlocked '${doc.name}' (Revision ${doc.revision})")
            }.onFailure { err ->
                appendLog("[Vault Error] ${err.message}")
            }
        }
    }

    fun importVaultOrRtszBytes(fileName: String, bytes: ByteArray, passphraseOrNull: CharArray? = null) {
        viewModelScope.launch {
            runCatching {
                if (fileName.endsWith(".rtsz", true) || fileName.endsWith(".rtsx", true)) {
                    rtszImporter.importRtszBytes(bytes, fileName.substringBeforeLast('.'))
                } else {
                    requireNotNull(passphraseOrNull) { "Passphrase required to open encrypted .nxts vault" }
                    vaultSerializer.unsealDocument(bytes, passphraseOrNull)
                }
            }.onSuccess { importedDoc ->
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

    fun evaluateSmartFolderCount(smartFolder: SmartFolder): Int =
        inheritanceResolver.evaluateSmartFolder(_uiState.value.vaultDocument, smartFolder).size

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
            val folderCloudId = "folder-hybrid-cloud"

            return NxtsVaultDocument(
                documentId = "nxts-enterprise-core",
                name = "NexusCorp Infrastructure.nxts",
                revision = 12L,
                rootInheritable = InheritableProperties(
                    credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                    explicitCredentialId = opsCredId,
                    gatewayMode = InheritanceMode.NONE_DIRECT
                ),
                folders = listOf(
                    VaultFolder(
                        id = folderProdId,
                        name = "Production Core (EU-Central)",
                        tags = listOf("prod", "tier-0"),
                        inheritable = InheritableProperties(
                            credentialMode = InheritanceMode.INHERIT_FROM_PARENT,
                            gatewayMode = InheritanceMode.OVERRIDE_EXPLICIT,
                            explicitGatewayId = bastionGwId,
                            environmentBadge = "PRODUCTION"
                        )
                    ),
                    VaultFolder(
                        id = folderCloudId,
                        name = "Hybrid Cloud & Hypervisors",
                        tags = listOf("cloud", "infra"),
                        inheritable = InheritableProperties(
                            credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                            explicitCredentialId = winCredId,
                            gatewayMode = InheritanceMode.INHERIT_FROM_PARENT,
                            environmentBadge = "STAGING"
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
                connections = listOf(
                    VaultConnection(
                        id = "conn-k8s-bastion-ssh",
                        parentFolderId = folderProdId,
                        name = "k8s-control-plane-01",
                        host = "10.24.0.10",
                        protocol = VaultProtocolType.SSH,
                        osFamily = "Linux",
                        tags = listOf("k8s", "prod"),
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
                        id = "conn-win-rds-01",
                        parentFolderId = folderProdId,
                        name = "WinServer-2025-DC01",
                        host = "dc01.corp.nexusts.internal",
                        protocol = VaultProtocolType.RDP,
                        osFamily = "Windows",
                        tags = listOf("ad", "rdp", "smartcard"),
                        inheritable = InheritableProperties(
                            credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                            explicitCredentialId = winCredId
                        ),
                        settings = ProtocolSpecificSettings.Rdp(
                            enableH264Avc444 = true,
                            redirectSmartcardYubiKey = true,
                            multiMonitorSpanning = true
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
                        keyFingerprintSha256 = "SHA256:kX9pNexusTSZeroKnowledgeEd25519Key"
                    ),
                    VaultCredential(
                        id = winCredId,
                        name = "CORP\\DomainAdmin + YubiKey PIV",
                        sourceKind = CredentialSourceKind.HARDWARE_FIDO2_PASSKEY,
                        username = "adm.architect",
                        domain = "CORP"
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
                )
            )
        }
    }
}
