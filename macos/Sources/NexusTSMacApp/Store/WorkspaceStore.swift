import AppKit
import Combine
import Foundation
import NexusTSCore
import SwiftUI

public enum WorkspacePrimarySection: String, CaseIterable, Identifiable, Hashable {
    case sessions
    case overview
    case templates
    case credentials
    case gateways
    case automationTasks
    case cloudSync

    public var id: String { rawValue }

    public var sfSymbol: String {
        switch self {
        case .sessions: return "macwindow.split.2x1"
        case .overview: return "square.grid.2x2"
        case .templates: return "doc.on.doc"
        case .credentials: return "key.horizontal"
        case .gateways: return "network.badge.shield.half.filled"
        case .automationTasks: return "bolt.horizontal"
        case .cloudSync: return "icloud.and.arrow.trianglehead.2.clockwise.rotate.90"
        }
    }
}

public struct AuditLogEntry: Identifiable, Equatable {
    public let id = UUID()
    public let timestamp: Date
    public let category: String
    public let message: String
}

// MARK: - Persistent Live Terminal Runtime (Survives Tab Switch & Window Detach/Re-Dock)

@MainActor
public final class LiveTerminalController: ObservableObject {
    public let tabId: UUID
    public let connection: VaultConnection
    public let isLocalShell: Bool
    public let recorder = AsciicastSessionRecorder()

    @Published public private(set) var snapshot: VtViewportSnapshot = .empty(cols: 110, rows: 32)
    @Published public private(set) var isConnected: Bool = false
    @Published public private(set) var statusBanner: String = "Initializing…"
    @Published public private(set) var activeForwardsCount: Int = 0
    @Published public var isRecordingCast: Bool = false

    private let vtEngine: NativeVtTerminalEngine
    private var localPty: MacLocalPtySession?
    private var sshSession: NativeSshSession?
    private var pollTimer: Timer?
    private var currentCols: Int = 110
    private var currentRows: Int = 32

    public init(
        tabId: UUID,
        connection: VaultConnection,
        isLocalShell: Bool,
        effectiveCredential: VaultCredential?,
        bastionConnection: VaultConnection? = nil,
        bastionCredential: VaultCredential? = nil
    ) {
        self.tabId = tabId
        self.connection = connection
        self.isLocalShell = isLocalShell
        let scrollback: Int = {
            if case .ssh(let s) = connection.settings { return s.scrollbackLines }
            return 10_000
        }()
        self.vtEngine = NativeVtTerminalEngine(cols: 110, rows: 32, maxScrollback: scrollback)

        if isLocalShell {
            startLocalShell()
        } else {
            startRemoteSshSession(
                credential: effectiveCredential,
                bastionConn: bastionConnection,
                bastionCred: bastionCredential
            )
        }
    }

    public func shutdown() {
        pollTimer?.invalidate()
        pollTimer = nil
        localPty?.terminate()
        localPty = nil
        sshSession?.disconnect()
        sshSession = nil
        isConnected = false
    }

    private func startLocalShell() {
        let pty = MacLocalPtySession()
        do {
            try pty.launchLoginShell(cols: currentCols, rows: currentRows)
            self.localPty = pty
            self.isConnected = true
            self.statusBanner = "Local macOS Login Shell (/bin/zsh)"
            startOutputPump()
        } catch {
            self.statusBanner = error.localizedDescription
            feedBannerText("\r\n\u{1B}[31m[Local PTY Error] \(error.localizedDescription)\u{1B}[0m\r\n")
        }
    }

    private func startRemoteSshSession(
        credential: VaultCredential?,
        bastionConn: VaultConnection?,
        bastionCred: VaultCredential?
    ) {
        let sshSettings: SshProtocolSettings = {
            if case .ssh(let s) = connection.settings { return s }
            return SshProtocolSettings(port: connection.protocolType.defaultPort)
        }()
        let user = (credential?.username.isEmpty == false) ? credential!.username : NSUserName()
        let auth: SshFfiConnectPayload.Auth = {
            if let pem = credential?.privateKeyPem, !pem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                let pass = credential?.keyPassphrase.isEmpty == true ? nil : credential?.keyPassphrase
                return .privateKeyPem(pem: pem, passphrase: pass)
            }
            return .password(credential?.secretPassword ?? "")
        }()

        let spec = SshFfiConnectPayload(
            host: connection.host,
            port: UInt16(clamping: sshSettings.port),
            username: user,
            auth: auth,
            termEnv: sshSettings.terminalType,
            initialCols: UInt32(currentCols),
            initialRows: UInt32(currentRows),
            enableX11: sshSettings.enableX11Forwarding,
            keepaliveSecs: UInt64(max(0, sshSettings.keepAliveIntervalSec))
        )

        let bastionSpec: SshFfiConnectPayload? = bastionConn.map { bConn in
            let bPort: Int = {
                if case .ssh(let s) = bConn.settings { return s.port }
                return 22
            }()
            let bUser = (bastionCred?.username.isEmpty == false) ? bastionCred!.username : user
            let bAuth: SshFfiConnectPayload.Auth = {
                if let pem = bastionCred?.privateKeyPem, !pem.isEmpty {
                    return .privateKeyPem(pem: pem, passphrase: bastionCred?.keyPassphrase)
                }
                return .password(bastionCred?.secretPassword ?? "")
            }()
            return SshFfiConnectPayload(host: bConn.host, port: UInt16(clamping: bPort), username: bUser, auth: bAuth)
        }

        feedBannerText("\u{1B}[38;2;91;155;255m● Connecting to \(user)@\(connection.host):\(sshSettings.port)…\u{1B}[0m\r\n")
        statusBanner = "Connecting to \(connection.host):\(sshSettings.port)…"

        let forwards = sshSettings.portForwards.filter(\.enabled)
        let startupCmd = sshSettings.startupCommand

        let terminalType = sshSettings.terminalType

        Task { @MainActor [weak self] in
            do {
                let (session, finalBoundCount) = try await Task.detached(priority: .userInitiated) { () -> (NativeSshSession, Int) in
                    let session = try NativeSshSession.connect(spec: spec, bastion: bastionSpec, openPty: true)
                    var boundCount = 0
                    for rule in forwards {
                        switch rule.kind {
                        case .LOCAL:
                            try? session.startLocalPortForward(
                                bindAddress: rule.bindAddress,
                                bindPort: UInt16(clamping: rule.bindPort),
                                targetHost: rule.targetHost,
                                targetPort: UInt16(clamping: rule.targetPort)
                            )
                            boundCount += 1
                        case .DYNAMIC_SOCKS5:
                            try? session.startDynamicSocks5Forward(
                                bindAddress: rule.bindAddress,
                                bindPort: UInt16(clamping: rule.bindPort)
                            )
                            boundCount += 1
                        case .REMOTE:
                            break
                        }
                    }
                    if !startupCmd.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        _ = session.sendInput(Data((startupCmd + "\n").utf8))
                    }
                    return (session, boundCount)
                }.value

                guard let self else {
                    session.disconnect()
                    return
                }
                self.sshSession = session
                self.isConnected = true
                self.activeForwardsCount = finalBoundCount
                self.statusBanner = "Connected • SSH-2.0 (\(terminalType))"
                self.startOutputPump()
            } catch {
                self?.isConnected = false
                self?.statusBanner = "Disconnected"
                self?.feedBannerText("\r\n\u{1B}[38;2;255;99;99m✖ \(error.localizedDescription)\u{1B}[0m\r\n")
            }
        }
    }

    private func startOutputPump() {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.pumpOnce()
            }
        }
    }

    private func pumpOnce() {
        if let pty = localPty {
            let (bytes, exited) = pty.drainOutput()
            if !bytes.isEmpty {
                if let text = String(data: bytes, encoding: .utf8) {
                    recorder.recordOutput(text)
                }
                if let snap = vtEngine.feedAndSnapshot(bytes) {
                    self.snapshot = snap
                }
            }
            if exited && isConnected {
                isConnected = false
                statusBanner = "Shell exited"
                feedBannerText("\r\n\u{1B}[90m[Process completed]\u{1B}[0m\r\n")
                pollTimer?.invalidate()
            }
        } else if let ssh = sshSession {
            let (bytes, eof) = ssh.drainOutput()
            if !bytes.isEmpty {
                if let text = String(data: bytes, encoding: .utf8) {
                    recorder.recordOutput(text)
                }
                if let snap = vtEngine.feedAndSnapshot(bytes) {
                    self.snapshot = snap
                }
            }
            if eof && isConnected {
                isConnected = false
                statusBanner = "Remote connection closed"
                feedBannerText("\r\n\u{1B}[90m[Connection closed by remote host]\u{1B}[0m\r\n")
                pollTimer?.invalidate()
            }
        }
    }

    public func sendBytes(_ data: Data) {
        if let pty = localPty {
            pty.sendInput(data)
        } else if let ssh = sshSession {
            _ = ssh.sendInput(data)
        }
    }

    public func sendText(_ text: String, bracketedIfActive: Bool = false) {
        if bracketedIfActive && snapshot.bracketedPasteMode {
            sendBytes(Data("\u{1B}[200~\(text)\u{1B}[201~".utf8))
        } else {
            sendBytes(Data(text.utf8))
        }
    }

    public func resizeGrid(cols: Int, rows: Int) {
        let c = max(20, cols)
        let r = max(6, rows)
        guard c != currentCols || r != currentRows else { return }
        currentCols = c
        currentRows = r
        vtEngine.resize(cols: c, rows: r)
        localPty?.resize(cols: c, rows: r)
        sshSession?.resizePty(cols: c, rows: r)
        if let snap = vtEngine.currentSnapshot() {
            self.snapshot = snap
        }
    }

    public func toggleRecording() -> String? {
        if isRecordingCast {
            recorder.stop()
            isRecordingCast = false
            return recorder.exportCastDocument()
        } else {
            recorder.start(width: currentCols, height: currentRows, title: connection.name)
            isRecordingCast = true
            return nil
        }
    }

    public func bindAdditionalForward(_ rule: SshPortForwardRule) throws {
        guard let ssh = sshSession else {
            throw NativeBridgeError.sshTransportError("Connect SSH session before starting live port forwards.")
        }
        switch rule.kind {
        case .LOCAL:
            try ssh.startLocalPortForward(
                bindAddress: rule.bindAddress,
                bindPort: UInt16(clamping: rule.bindPort),
                targetHost: rule.targetHost,
                targetPort: UInt16(clamping: rule.targetPort)
            )
            activeForwardsCount += 1
        case .DYNAMIC_SOCKS5:
            try ssh.startDynamicSocks5Forward(
                bindAddress: rule.bindAddress,
                bindPort: UInt16(clamping: rule.bindPort)
            )
            activeForwardsCount += 1
        case .REMOTE:
            break
        }
    }

    private func feedBannerText(_ ansi: String) {
        if let snap = vtEngine.feedAndSnapshot(Data(ansi.utf8)) {
            self.snapshot = snap
        }
    }
}

// MARK: - Central Workspace Store (@MainActor)

@MainActor
public final class WorkspaceStore: ObservableObject {
    @Published public var document: NxtsVaultDocument
    @Published public var isVaultLocked: Bool = false
    @Published public var unlockErrorText: String? = nil
    @Published public var hasCustomPassphrase: Bool = false

    // Navigation & Chrome State
    @Published public var columnVisibility: NavigationSplitViewVisibility = .all
    @Published public var activeSection: WorkspacePrimarySection = .sessions
    @Published public var selectedConnectionId: String? = nil
    @Published public var selectedFolderId: String? = nil
    @Published public var sidebarSearchQuery: String = ""
    @Published public var isInspectorPresented: Bool = true
    @Published public var isBottomConsolePresented: Bool = false
    @Published public var isQuickConnectPresented: Bool = false

    // Split-Pane & Detached Windows
    @Published public var paneTree: WorkspacePaneNode = .initialEmpty()
    @Published public var focusedPaneId: UUID? = nil
    @Published public var detachedWindowTabs: [UUID: WorkspaceSessionTab] = [:]

    // Broadcast, Preferences & Sync
    @Published public var broadcastInputEnabled: Bool = false
    @Published public var remoteKeyCaptureEnabled: Bool = false
    @Published public var appLanguage: NexusAppLanguage = .system
    @Published public var terminalFontName: String = "SF Mono"
    @Published public var terminalFontSize: Double = 13.0
    @Published public var spotlightIndexingEnabled: Bool = true
    @Published public var autoSyncOnSave: Bool = true
    @Published public var driveStorageZone: DriveStorageZone = .SHARED_TEAM_DRIVE
    @Published public var syncStatusSummary: String = "Local Zero-Knowledge Vault"
    @Published public var linkedCloudFileURL: URL? = nil
    @Published public var updateCandidate: MacReleaseUpdateCandidate? = nil
    @Published public var auditLog: [AuditLogEntry] = []
    @Published public var dynamicFolderCache: [String: [VaultConnection]] = [:]

    // Sheet Presentation Triggers
    @Published public var editingConnection: VaultConnection? = nil
    @Published public var isPresentingNewConnectionSheet: Bool = false
    @Published public var newConnectionInitialFolderId: String? = nil
    @Published public var newConnectionInitialProtocol: VaultProtocolType = .SSH
    @Published public var editingFolder: VaultFolder? = nil
    @Published public var isPresentingNewFolderSheet: Bool = false
    @Published public var connectWithOptionsTarget: VaultConnection? = nil

    private var activePassphrase: String = NxtsVaultSerializer.defaultTransparentPassphrase
    private var terminalControllers: [UUID: LiveTerminalController] = [:]
    private var rdpSessions: [UUID: NativeRdpSession] = [:]
    public let cloudBookmarkBridge = MacCloudBookmarkSyncBridge()
    public let googleDriveEngine = GoogleDriveRestSyncEngine()

    public var strings: LocalizedWorkspaceStrings {
        LocalizedWorkspaceStrings.catalog(for: appLanguage)
    }

    public static var localVaultFileURL: URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = appSupport.appendingPathComponent("NexusTS", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("nexusts_local_vault.nxts")
    }

    public init() {
        let initial = NxtsVaultDocument.sampleStarterVault()
        self.document = initial
        self.linkedCloudFileURL = cloudBookmarkBridge.resolveLinkedURL()
        loadPersistedLocalVault()
        appendAudit("Vault", "NexusTS macOS initialized (\(document.connections.count) connections).")
    }

    // MARK: - Persistence, Locking & Touch ID

    private func loadPersistedLocalVault() {
        let url = Self.localVaultFileURL
        guard FileManager.default.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url),
              NxtsVaultSerializer.hasNxtsMagic(data) else {
            persistLocalVault()
            MacSpotlightVaultIndexer.reindexConnections(in: document, enabled: spotlightIndexingEnabled)
            return
        }

        if let opened = try? NxtsVaultSerializer.open(
            sealedData: data,
            passphrase: NxtsVaultSerializer.defaultTransparentPassphrase
        ) {
            self.document = opened
            self.hasCustomPassphrase = false
            self.isVaultLocked = false
            MacSpotlightVaultIndexer.reindexConnections(in: opened, enabled: spotlightIndexingEnabled)
        } else {
            // Custom master passphrase is set -> present Zero-Knowledge Lock Shield + Touch ID!
            self.hasCustomPassphrase = true
            self.isVaultLocked = true
        }
    }

    public func persistLocalVault() {
        do {
            let sealed = try NxtsVaultSerializer.seal(
                document: document,
                passphrase: activePassphrase,
                kdf: .interactiveFast
            )
            let url = Self.localVaultFileURL
            let tmp = url.deletingLastPathComponent().appendingPathComponent(".nexusts_local_vault.nxts.tmp")
            try sealed.write(to: tmp, options: .atomic)
            if FileManager.default.fileExists(atPath: url.path) {
                _ = try FileManager.default.replaceItemAt(url, withItemAt: tmp)
            } else {
                try FileManager.default.moveItem(at: tmp, to: url)
            }
            MacSpotlightVaultIndexer.reindexConnections(in: document, enabled: spotlightIndexingEnabled)

            if autoSyncOnSave && linkedCloudFileURL != nil {
                Task { await syncCloudNow() }
            }
        } catch {
            appendAudit("Error", "Vault persistence error: \(error.localizedDescription)")
        }
    }

    public func commitMutation(_ reason: String, mutate: (inout NxtsVaultDocument) -> Void) {
        mutate(&document)
        document.bumpRevision(actor: "macos")
        persistLocalVault()
        appendAudit("Vault", "\(reason) (rev \(document.revision))")
    }

    public func lockVault() {
        // Close live interactive sessions for zero-knowledge protection
        for (_, ctrl) in terminalControllers { ctrl.shutdown() }
        terminalControllers.removeAll()
        for (_, rdp) in rdpSessions { rdp.destroy() }
        rdpSessions.removeAll()
        paneTree = .initialEmpty()
        detachedWindowTabs.removeAll()
        NativeSshCryptoBridge.clearAgentKeys()
        isVaultLocked = true
        unlockErrorText = nil
        appendAudit("Security", "Vault locked and in-memory keys zeroized.")
    }

    public func unlockVault(withPassphrase candidate: String) -> Bool {
        let trimmed = candidate
        let url = Self.localVaultFileURL
        if FileManager.default.fileExists(atPath: url.path),
           let data = try? Data(contentsOf: url),
           NxtsVaultSerializer.hasNxtsMagic(data) {
            let tryPassphrases = trimmed.isEmpty
                ? [activePassphrase, NxtsVaultSerializer.defaultTransparentPassphrase]
                : [trimmed]
            for pass in tryPassphrases {
                if let opened = try? NxtsVaultSerializer.open(sealedData: data, passphrase: pass) {
                    self.activePassphrase = pass
                    self.document = opened
                    self.isVaultLocked = false
                    self.unlockErrorText = nil
                    appendAudit("Security", "Vault unlocked successfully.")
                    return true
                }
            }
            self.unlockErrorText = "Invalid master passphrase for this .nxts v2 vault."
            return false
        }
        self.isVaultLocked = false
        return true
    }

    public func unlockWithTouchId() async {
        do {
            let pass = try await MacKeychainAndBiometrics.shared.unlockPassphraseWithBiometrics(
                forVaultId: document.documentId
            )
            _ = unlockVault(withPassphrase: pass)
        } catch {
            unlockErrorText = error.localizedDescription
        }
    }

    public func configureMasterPassphrase(_ newPassphrase: String, saveForTouchId: Bool) throws {
        let effective = newPassphrase.isEmpty
            ? NxtsVaultSerializer.defaultTransparentPassphrase
            : newPassphrase
        self.activePassphrase = effective
        self.hasCustomPassphrase = !newPassphrase.isEmpty
        persistLocalVault()
        if saveForTouchId && !newPassphrase.isEmpty {
            try MacKeychainAndBiometrics.shared.storePassphraseInKeychain(
                newPassphrase,
                forVaultId: document.documentId
            )
            appendAudit("Security", "Master passphrase sealed in macOS Keychain for Touch ID / Apple Watch unlock.")
        } else if newPassphrase.isEmpty {
            MacKeychainAndBiometrics.shared.deleteStoredPassphrase(forVaultId: document.documentId)
        }
    }

    // MARK: - Sessions, BSP Tiling & Multi-Window Detach / Re-Dock

    public var activeConnections: [VaultConnection] {
        document.connections.filter { !document.tombstonedObjectIds.contains($0.id) }
    }

    public var activeFolders: [VaultFolder] {
        document.folders
            .filter { !document.tombstonedObjectIds.contains($0.id) }
            .sorted {
                if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
                return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
    }

    public var selectedConnection: VaultConnection? {
        guard let id = selectedConnectionId else {
            if let focused = focusedPaneId,
               case .leaf(_, let tabs, let activeId) = findLeafNode(id: focused),
               let activeTab = tabs.first(where: { $0.id == activeId }) ?? tabs.first,
               !activeTab.isLocalShell {
                return activeConnections.first { $0.id == activeTab.connection.id } ?? activeTab.connection
            }
            return nil
        }
        return activeConnections.first { $0.id == id }
    }

    public func openConnection(
        _ connection: VaultConnection,
        inSplit orientation: SplitOrientation? = nil,
        adHocUsername: String? = nil,
        adHocPassword: String? = nil,
        adHocDomain: String? = nil
    ) {
        activeSection = .sessions
        selectedConnectionId = connection.id

        var recents = document.recentConnectionIds.filter { $0 != connection.id }
        recents.insert(connection.id, at: 0)
        document.recentConnectionIds = Array(recents.prefix(12))

        let resolved = PropertyInheritanceResolver.resolve(connection: connection, in: document)
        let tab = WorkspaceSessionTab(
            connection: connection,
            broadcastEnabled: broadcastInputEnabled,
            accentHex: resolved.displayColorAccentHex,
            adHocUsernameOverride: adHocUsername,
            adHocPasswordOverride: adHocPassword,
            adHocDomainOverride: adHocDomain
        )

        if let orientation, let targetLeaf = focusedPaneId ?? paneTree.allLeafIds.first {
            let (updated, newLeafId) = paneTree.splittingPane(targetLeaf, orientation: orientation, newTab: tab)
            paneTree = updated
            focusedPaneId = newLeafId
        } else {
            let targetLeaf = focusedPaneId ?? paneTree.allLeafIds.first
            paneTree = paneTree.insertingTab(tab, into: targetLeaf)
            focusedPaneId = paneTree.leafContaining(tabId: tab.id)
        }

        appendAudit("Session", "Opened \(connection.protocolType.shortBadge) session to \(connection.name) (\(connection.host)).")
    }

    public func openLocalTerminal(inSplit orientation: SplitOrientation? = nil) {
        activeSection = .sessions
        let tab = WorkspaceSessionTab.makeLocalZshTab()
        if let orientation, let targetLeaf = focusedPaneId ?? paneTree.allLeafIds.first {
            let (updated, newLeafId) = paneTree.splittingPane(targetLeaf, orientation: orientation, newTab: tab)
            paneTree = updated
            focusedPaneId = newLeafId
        } else {
            let targetLeaf = focusedPaneId ?? paneTree.allLeafIds.first
            paneTree = paneTree.insertingTab(tab, into: targetLeaf)
            focusedPaneId = paneTree.leafContaining(tabId: tab.id)
        }
        appendAudit("Terminal", "Launched local macOS login shell (/bin/zsh).")
    }

    public func splitFocusedPane(_ orientation: SplitOrientation) {
        guard let targetLeaf = focusedPaneId ?? paneTree.allLeafIds.first else { return }
        let shellTab = WorkspaceSessionTab.makeLocalZshTab()
        let (updated, newLeafId) = paneTree.splittingPane(targetLeaf, orientation: orientation, newTab: shellTab)
        paneTree = updated
        focusedPaneId = newLeafId
    }

    public func closeTab(_ tabId: UUID, destroyRuntime: Bool = true) {
        paneTree = paneTree.removingTab(tabId)
        detachedWindowTabs.removeValue(forKey: tabId)
        if destroyRuntime {
            terminalControllers.removeValue(forKey: tabId)?.shutdown()
            rdpSessions.removeValue(forKey: tabId)?.destroy()
        }
        if let focused = focusedPaneId, !paneTree.allLeafIds.contains(focused) {
            focusedPaneId = paneTree.allLeafIds.first
        }
    }

    /// Detaches a live session tab into a secondary macOS window WITHOUT dropping the SSH/RDP stream.
    public func stageTabForDetachedWindow(_ tabId: UUID) -> UUID? {
        guard let tab = paneTree.findTab(id: tabId) else { return nil }
        detachedWindowTabs[tabId] = tab
        paneTree = paneTree.removingTab(tabId)
        appendAudit("Window", "Detached '\(tab.title)' into separate macOS window.")
        return tabId
    }

    /// Losslessly re-docks a detached macOS window tab back into the main BSP split-pane workspace.
    public func reDockDetachedTab(_ tabId: UUID) {
        guard let tab = detachedWindowTabs.removeValue(forKey: tabId) else { return }
        activeSection = .sessions
        let targetLeaf = focusedPaneId ?? paneTree.allLeafIds.first
        paneTree = paneTree.insertingTab(tab, into: targetLeaf)
        focusedPaneId = paneTree.leafContaining(tabId: tab.id)
        appendAudit("Window", "Re-docked '\(tab.title)' into main workspace.")
    }

    public func disconnectAllInFolder(_ folderId: String) {
        let subtreeFolderIds = collectSubtreeFolderIds(rootFolderId: folderId)
        let matchingConnIds = Set(
            activeConnections
                .filter { $0.parentFolderId.map(subtreeFolderIds.contains) ?? false }
                .map(\.id)
        )
        let toCloseDocked = paneTree.allTabs.filter { matchingConnIds.contains($0.connection.id) }.map(\.id)
        let toCloseDetached = detachedWindowTabs.values.filter { matchingConnIds.contains($0.connection.id) }.map(\.id)
        for id in (toCloseDocked + toCloseDetached) {
            closeTab(id, destroyRuntime: true)
        }
        appendAudit("Session", "Closed \(toCloseDocked.count + toCloseDetached.count) session(s) in folder subtree.")
    }

    public func collectSubtreeFolderIds(rootFolderId: String) -> Set<String> {
        var result: Set<String> = [rootFolderId]
        var added = true
        while added {
            added = false
            for f in activeFolders where !result.contains(f.id) {
                if let p = f.parentFolderId, result.contains(p) {
                    result.insert(f.id)
                    added = true
                }
            }
        }
        return result
    }

    public func activeSessionCount(forConnectionId connId: String) -> Int {
        let docked = paneTree.allTabs.filter { $0.connection.id == connId }.count
        let detached = detachedWindowTabs.values.filter { $0.connection.id == connId }.count
        return docked + detached
    }

    public func terminalController(for tab: WorkspaceSessionTab) -> LiveTerminalController {
        if let existing = terminalControllers[tab.id] {
            return existing
        }
        let resolved = PropertyInheritanceResolver.resolve(connection: tab.connection, in: document)
        var effectiveCred = resolved.effectiveCredential
        if tab.adHocUsernameOverride != nil || tab.adHocPasswordOverride != nil {
            var copy = effectiveCred ?? VaultCredential(name: "Ad-Hoc")
            if let u = tab.adHocUsernameOverride, !u.isEmpty { copy.username = u }
            if let p = tab.adHocPasswordOverride, !p.isEmpty { copy.secretPassword = p }
            if let d = tab.adHocDomainOverride, !d.isEmpty { copy.domain = d }
            effectiveCred = copy
        }

        var bastionConn: VaultConnection? = nil
        var bastionCred: VaultCredential? = nil
        if case .ssh(let sshCfg) = tab.connection.settings,
           let bastionId = sshCfg.bastionJumpConnectionId,
           let foundBastion = activeConnections.first(where: { $0.id == bastionId }) {
            bastionConn = foundBastion
            bastionCred = PropertyInheritanceResolver.resolve(connection: foundBastion, in: document).effectiveCredential
        }

        let created = LiveTerminalController(
            tabId: tab.id,
            connection: tab.connection,
            isLocalShell: tab.isLocalShell,
            effectiveCredential: effectiveCred,
            bastionConnection: bastionConn,
            bastionCredential: bastionCred
        )
        terminalControllers[tab.id] = created
        return created
    }

    public func rdpController(for tab: WorkspaceSessionTab) -> NativeRdpSession {
        if let existing = rdpSessions[tab.id] {
            return existing
        }
        let resolved = PropertyInheritanceResolver.resolve(connection: tab.connection, in: document)
        let rdpSettings: RdpProtocolSettings = {
            if case .rdp(let s) = tab.connection.settings { return s }
            return RdpProtocolSettings()
        }()
        let user = tab.adHocUsernameOverride ?? resolved.effectiveCredential?.username ?? "Administrator"
        let pass = tab.adHocPasswordOverride ?? resolved.effectiveCredential?.secretPassword ?? ""
        let dom = tab.adHocDomainOverride ?? resolved.effectiveCredential?.domain ?? ""

        let session = NativeRdpSession(
            host: tab.connection.host,
            username: user,
            password: pass,
            domain: dom,
            settings: rdpSettings,
            initialWidth: rdpSettings.customDesktopWidth,
            initialHeight: rdpSettings.customDesktopHeight
        )
        _ = session.connect()
        rdpSessions[tab.id] = session
        return session
    }

    // MARK: - Broadcast Bus & Automation Execution

    public func broadcastTerminalInput(_ data: Data, originatingTabId: UUID?) {
        guard broadcastInputEnabled else { return }
        let allTabs = paneTree.allTabs + Array(detachedWindowTabs.values)
        for tab in allTabs where tab.id != originatingTabId && (tab.broadcastEnabled || broadcastInputEnabled) {
            terminalControllers[tab.id]?.sendBytes(data)
        }
    }

    public func executeBroadcastCommandLine(_ line: String) {
        let payload = Data((line + "\r").utf8)
        for (_, ctrl) in terminalControllers {
            ctrl.sendBytes(payload)
        }
        appendAudit("Broadcast", "Sent command to \(terminalControllers.count) terminal session(s).")
    }

    public func runCommandTask(_ task: CommandTask) {
        let targets: [WorkspaceSessionTab] = {
            if task.broadcastToAllSelectedSessions || broadcastInputEnabled {
                return paneTree.allTabs + Array(detachedWindowTabs.values)
            }
            if let focused = focusedPaneId,
               case .leaf(_, let tabs, let activeId) = findLeafNode(id: focused),
               let active = tabs.first(where: { $0.id == activeId }) ?? tabs.first {
                return [active]
            }
            return paneTree.allTabs.prefix(1).map { $0 }
        }()

        for tab in targets {
            let profile = PropertyInheritanceResolver.resolve(connection: tab.connection, in: document)
            let expanded = AutomationTaskEngine.expandTokens(template: task.commandTemplate, profile: profile)
            let finalStr = task.appendNewline ? "\(expanded)\r" : expanded
            terminalControllers[tab.id]?.sendText(finalStr)
        }
        appendAudit("Automation", "Executed task '\(task.name)' on \(targets.count) session(s).")
    }

    public func runKeySequenceTask(_ macro: KeySequenceTask) {
        guard let tab = paneTree.allTabs.first else { return }
        let profile = PropertyInheritanceResolver.resolve(connection: tab.connection, in: document)
        let steps = AutomationTaskEngine.compileKeySequence(macroSequence: macro.macroSequence, profile: profile)
        guard let ctrl = terminalControllers[tab.id] else { return }

        Task { @MainActor in
            for step in steps {
                switch step {
                case .text(let s):
                    ctrl.sendText(s)
                case .specialKey(_, let bytes):
                    ctrl.sendBytes(bytes)
                case .wait(let ms):
                    try? await Task.sleep(nanoseconds: ms * 1_000_000)
                }
                if macro.keystrokeDelayMs > 0 {
                    try? await Task.sleep(nanoseconds: UInt64(macro.keystrokeDelayMs) * 1_000_000)
                }
            }
        }
        appendAudit("Automation", "Dispatched key sequence '\(macro.name)'.")
    }

    // MARK: - Drag-and-Drop Folder Reparenting, Import/Export & Cloud Sync

    public func moveConnection(_ connectionId: String, toFolderId targetFolderId: String?) {
        commitMutation("Moved connection") { doc in
            if let idx = doc.connections.firstIndex(where: { $0.id == connectionId }) {
                doc.connections[idx].parentFolderId = targetFolderId
                doc.connections[idx].updatedAtEpochMs = VaultClock.nowMs()
            }
        }
    }

    public func deleteConnection(_ connectionId: String) {
        let tabsToClose = paneTree.allTabs.filter { $0.connection.id == connectionId }.map(\.id)
        for id in tabsToClose { closeTab(id) }
        commitMutation("Deleted connection") { doc in
            doc.tombstonedObjectIds.insert(connectionId)
            doc.connections.removeAll { $0.id == connectionId }
        }
    }

    public func importExternalVaultFile(url: URL, passphrase: String = "") throws -> ImportedVaultSummary {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        let summary = try EnterpriseVaultFormatImporter.importData(
            data,
            fileName: url.lastPathComponent,
            passphrase: passphrase.isEmpty ? activePassphrase : passphrase
        )
        let outcome = VaultDeltaConflictResolver.mergeDocuments(
            local: document,
            remote: summary.document,
            localDeviceId: "macos"
        )
        document = outcome.mergedDocument
        persistLocalVault()
        appendAudit("Import", "Imported \(summary.detectedFormat): \(summary.importedConnections) connections, \(summary.importedFolders) folders.")
        return summary
    }

    public func linkCloudVaultURL(_ url: URL) throws {
        try cloudBookmarkBridge.linkVaultFileURL(url)
        linkedCloudFileURL = url
        Task { await syncCloudNow() }
    }

    public func syncCloudNow() async {
        if let url = linkedCloudFileURL ?? cloudBookmarkBridge.resolveLinkedURL() {
            do {
                let res = try cloudBookmarkBridge.syncWithLinkedFile(
                    localDocument: document,
                    passphrase: activePassphrase,
                    explicitURL: url,
                    kdf: .interactiveFast
                )
                self.document = res.document
                self.linkedCloudFileURL = res.syncedFileURL
                self.syncStatusSummary = "\(res.action.rawValue) (\(res.syncedFileURL.lastPathComponent))"
                appendAudit("Cloud Sync", "\(res.action.rawValue) via \(res.syncedFileURL.path) (\(res.conflictCount) conflicts resolved).")
            } catch {
                syncStatusSummary = "Sync error: \(error.localizedDescription)"
                appendAudit("Cloud Sync", "Error: \(error.localizedDescription)")
            }
        } else if let googleToken = MacKeychainAndBiometrics.shared.loadToken(key: "gdrive_oauth_bearer"),
                  !googleToken.isEmpty {
            do {
                let res = try await googleDriveEngine.syncVault(
                    localDocument: document,
                    passphrase: activePassphrase,
                    bearerToken: googleToken,
                    zone: driveStorageZone,
                    kdf: .interactiveFast
                )
                self.document = res.document
                self.syncStatusSummary = "Google Drive: \(res.action.rawValue)"
                persistLocalVault()
                appendAudit("Google Drive", "\(res.action.rawValue) (\(res.conflicts) conflicts merged).")
            } catch {
                syncStatusSummary = "Drive API error"
                appendAudit("Google Drive", error.localizedDescription)
            }
        }
    }

    public func refreshDynamicFolder(_ dyn: DynamicFolder) async {
        let items = await PropertyInheritanceResolver.evaluateDynamicFolder(dyn, allowLocalScriptExecution: false)
        dynamicFolderCache[dyn.id] = items
        appendAudit("Dynamic Folder", "Reloaded '\(dyn.name)' (\(items.count) dynamic hosts).")
    }

    public func checkGitHubUpdates() async {
        if let candidate = try? await MacGitHubReleaseUpdater.checkForUpdates() {
            self.updateCandidate = candidate
            appendAudit("Updater", "New release available on GitHub: \(candidate.tagName).")
        }
    }

    public func appendAudit(_ category: String, _ message: String) {
        auditLog.insert(AuditLogEntry(timestamp: Date(), category: category, message: message), at: 0)
        if auditLog.count > 250 {
            auditLog.removeLast(auditLog.count - 250)
        }
    }

    private func findLeafNode(id: UUID) -> WorkspacePaneNode? {
        func search(_ node: WorkspacePaneNode) -> WorkspacePaneNode? {
            switch node {
            case .leaf(let paneId, _, _):
                return paneId == id ? node : nil
            case .split(_, _, _, let f, let s):
                return search(f) ?? search(s)
            }
        }
        return search(paneTree)
    }
}
