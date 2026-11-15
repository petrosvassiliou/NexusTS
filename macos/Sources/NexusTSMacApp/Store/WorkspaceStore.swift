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

public enum SidebarFilterMode: String, CaseIterable, Identifiable, Hashable {
    case all
    case favorites
    case recents

    public var id: String { rawValue }
}

public struct AuditLogEntry: Identifiable, Equatable {
    public let id = UUID()
    public let timestamp: Date
    public let category: String
    public let message: String
}

public enum FolderDropPlacement: String, Equatable, Sendable {
    case before
    case inside
    case after
}

public struct PendingRoyalTsImport: Identifiable, Equatable, Sendable {
    public let id: String
    public let summary: ImportedVaultSummary
    public let suggestedFolderName: String

    public init(
        id: String = UUID().uuidString.lowercased(),
        summary: ImportedVaultSummary,
        suggestedFolderName: String
    ) {
        self.id = id
        self.summary = summary
        let cleanDocName = summary.document.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanFile = suggestedFolderName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanDocName.isEmpty && cleanDocName != "Imported Royal TS Vault" && cleanDocName != "NexusTS Vault" {
            self.suggestedFolderName = cleanDocName
        } else if !cleanFile.isEmpty {
            self.suggestedFolderName = cleanFile
        } else {
            self.suggestedFolderName = "Imported Royal TS"
        }
    }
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
    @Published public private(set) var portForwardWarnings: [String] = []
    @Published public private(set) var lastConnectError: NexusAppError? = nil
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
            self.lastConnectError = nil
            self.statusBanner = "Local macOS Login Shell (/bin/zsh)"
            startOutputPump()
        } catch {
            self.statusBanner = error.localizedDescription
            self.lastConnectError = NexusErrorClassifier.classify(error, operationTitle: "Local Terminal Failed")
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
        let connId = connection.id
        let connHost = connection.host

        Task { @MainActor [weak self] in
            do {
                let (session, finalBoundCount, warnings) = try await Task.detached(priority: .userInitiated) { () -> (NativeSshSession, Int, [String]) in
                    let session = try NativeSshSession.connect(spec: spec, bastion: bastionSpec, openPty: true)
                    var boundCount = 0
                    var forwardFailures: [String] = []
                    for rule in forwards {
                        switch rule.kind {
                        case .LOCAL:
                            do {
                                try session.startLocalPortForward(
                                    bindAddress: rule.bindAddress,
                                    bindPort: UInt16(clamping: rule.bindPort),
                                    targetHost: rule.targetHost,
                                    targetPort: UInt16(clamping: rule.targetPort)
                                )
                                boundCount += 1
                            } catch {
                                forwardFailures.append("L \(rule.bindAddress):\(rule.bindPort) → \(rule.targetHost):\(rule.targetPort): \(error.localizedDescription)")
                            }
                        case .DYNAMIC_SOCKS5:
                            do {
                                try session.startDynamicSocks5Forward(
                                    bindAddress: rule.bindAddress,
                                    bindPort: UInt16(clamping: rule.bindPort)
                                )
                                boundCount += 1
                            } catch {
                                forwardFailures.append("D \(rule.bindAddress):\(rule.bindPort): \(error.localizedDescription)")
                            }
                        case .REMOTE:
                            break
                        }
                    }
                    if !startupCmd.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        _ = session.sendInput(Data((startupCmd + "\n").utf8))
                    }
                    return (session, boundCount, forwardFailures)
                }.value

                guard let self else {
                    session.disconnect()
                    return
                }
                self.sshSession = session
                self.isConnected = true
                self.lastConnectError = nil
                self.activeForwardsCount = finalBoundCount
                self.portForwardWarnings = warnings
                self.statusBanner = warnings.isEmpty
                    ? "Connected • SSH-2.0 (\(terminalType))"
                    : "Connected • \(warnings.count) port-forward warning(s)"
                for warning in warnings {
                    self.feedBannerText("\r\n\u{1B}[33m⚠ [Port Forward Warning] \(warning)\u{1B}[0m\r\n")
                }
                self.startOutputPump()
            } catch {
                let classified = NexusErrorClassifier.classify(
                    error,
                    operationTitle: "SSH Connection Failed (\(connHost))",
                    recoveryConnectionId: connId
                )
                self?.isConnected = false
                self?.lastConnectError = classified
                self?.statusBanner = classified.userMessage
                self?.feedBannerText("\r\n\u{1B}[38;2;255;99;99m✖ \(classified.userMessage)\r\n  ↳ \(classified.remediationHint)\r\n  [\(error.localizedDescription)]\u{1B}[0m\r\n")
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
    @Published public var sidebarFilterMode: SidebarFilterMode = .all
    @Published public var collapsedFolderIds: Set<String> = []
    @Published public var isSystemCredentialsCollapsed: Bool = false
    @Published public var selectedConnectionId: String? = nil
    @Published public var selectedFolderId: String? = nil
    @Published public var selectedCredentialId: String? = nil
    @Published public var sidebarSearchQuery: String = ""
    @Published public var isInspectorPresented: Bool = false
    @Published public var isBottomConsolePresented: Bool = false
    @Published public var isQuickConnectPresented: Bool = false

    // Split-Pane & Detached Windows
    @Published public var paneTree: WorkspacePaneNode = .initialEmpty()
    @Published public var focusedPaneId: UUID? = nil
    @Published public var detachedWindowTabs: [UUID: WorkspaceSessionTab] = [:]
    @Published public var isFullScreenSessionMode: Bool = false

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

    // Sheet Presentation Triggers & Global Error State
    @Published public var editingConnection: VaultConnection? = nil
    @Published public var isPresentingNewConnectionSheet: Bool = false
    @Published public var newConnectionInitialFolderId: String? = nil
    @Published public var newConnectionInitialProtocol: VaultProtocolType = .SSH
    @Published public var editingFolder: VaultFolder? = nil
    @Published public var isPresentingNewFolderSheet: Bool = false
    @Published public var newFolderInitialParentId: String? = nil
    @Published public var renamingFolder: VaultFolder? = nil
    @Published public var deletingFolderCandidate: VaultFolder? = nil
    @Published public var connectWithOptionsTarget: VaultConnection? = nil
    @Published public var editingSmartFolder: SmartFolder? = nil
    @Published public var isPresentingNewSmartFolderSheet: Bool = false
    @Published public var editingDynamicFolder: DynamicFolder? = nil
    @Published public var isPresentingNewDynamicFolderSheet: Bool = false
    @Published public var editingCredential: VaultCredential? = nil
    @Published public var isPresentingNewCredentialSheet: Bool = false
    @Published public var pendingRoyalImport: PendingRoyalTsImport? = nil
    @Published public var isPresentingResetDatabaseSheet: Bool = false
    @Published public var activeAppError: NexusAppError? = nil
    @Published public var statusToastMessage: String? = nil

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
        Task { [weak self] in
            await self?.checkGitHubUpdates()
        }
    }

    // MARK: - Structured Error Reporting

    public func reportAppError(_ appError: NexusAppError) {
        self.activeAppError = appError
        appendAudit("Error", "\(appError.title): \(appError.userMessage)")
    }

    public func reportError(
        _ error: Error,
        operationTitle: String,
        recoveryConnectionId: String? = nil
    ) {
        let classified = NexusErrorClassifier.classify(
            error,
            operationTitle: operationTitle,
            recoveryConnectionId: recoveryConnectionId
        )
        reportAppError(classified)
    }

    // MARK: - Persistence, Locking, Corrupt Vault Quarantine & Touch ID

    private func loadPersistedLocalVault() {
        let url = Self.localVaultFileURL
        guard FileManager.default.fileExists(atPath: url.path) else {
            persistLocalVault()
            MacSpotlightVaultIndexer.reindexConnections(in: document, enabled: spotlightIndexingEnabled)
            return
        }

        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            reportError(error, operationTitle: "Local Vault Read Failed")
            return
        }

        guard NxtsVaultSerializer.hasNxtsMagic(data) else {
            // Quarantine corrupt local .nxts file instead of overwriting or bricking launch
            let backupURL = url.deletingLastPathComponent()
                .appendingPathComponent("nexusts_local_vault.corrupt-\(VaultClock.nowMs()).nxts.bak")
            try? FileManager.default.moveItem(at: url, to: backupURL)
            self.document = NxtsVaultDocument.sampleStarterVault()
            persistLocalVault()
            MacSpotlightVaultIndexer.reindexConnections(in: document, enabled: spotlightIndexingEnabled)
            reportAppError(
                NexusAppError(
                    category: .vaultCryptoOrCorruption,
                    title: "Corrupted Local Vault Quarantined",
                    userMessage: "The local vault file had an invalid header and was safely quarantined so NexusTS could start.",
                    remediationHint: "Your original file was preserved at \(backupURL.lastPathComponent). You can re-import a backup or sync from Cloud Sync.",
                    technicalDetails: "Quarantined path: \(backupURL.path) (\(data.count) bytes)"
                )
            )
            return
        }

        if let opened = try? NxtsVaultSerializer.open(
            sealedData: data,
            passphrase: NxtsVaultSerializer.defaultTransparentPassphrase
        ) {
            let sanitized = VaultHierarchySanitizer.sanitizeVaultDocument(opened)
            self.document = sanitized
            self.hasCustomPassphrase = false
            self.isVaultLocked = false
            if sanitized != opened {
                persistLocalVault()
            }
            MacSpotlightVaultIndexer.reindexConnections(in: sanitized, enabled: spotlightIndexingEnabled)
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
            reportError(error, operationTitle: "Vault Persistence Failed")
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
        isFullScreenSessionMode = false
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
                    let sanitized = VaultHierarchySanitizer.sanitizeVaultDocument(opened)
                    self.activePassphrase = pass
                    self.document = sanitized
                    self.isVaultLocked = false
                    self.unlockErrorText = nil
                    if sanitized != opened {
                        persistLocalVault()
                    }
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

    public func resetVaultDatabase() {
        for (_, ctrl) in terminalControllers { ctrl.shutdown() }
        terminalControllers.removeAll()
        for (_, rdp) in rdpSessions { rdp.destroy() }
        rdpSessions.removeAll()
        paneTree = .initialEmpty()
        detachedWindowTabs.removeAll()
        isFullScreenSessionMode = false
        dynamicFolderCache.removeAll()
        NativeSshCryptoBridge.clearAgentKeys()
        MacKeychainAndBiometrics.shared.deleteAllVaultPassphrases()

        let url = Self.localVaultFileURL
        try? FileManager.default.removeItem(at: url)

        self.activePassphrase = NxtsVaultSerializer.defaultTransparentPassphrase
        self.hasCustomPassphrase = false
        self.isVaultLocked = false
        self.unlockErrorText = nil
        self.selectedConnectionId = nil
        self.selectedFolderId = nil
        self.selectedCredentialId = nil
        self.collapsedFolderIds.removeAll()
        self.pendingRoyalImport = nil
        self.isPresentingResetDatabaseSheet = false
        self.document = NxtsVaultDocument.sampleStarterVault()
        MacSpotlightVaultIndexer.reindexConnections(in: document, enabled: false)
        persistLocalVault()
        statusToastMessage = "NexusTS database has been reset to a clean empty vault."
        appendAudit("Reset", "Reset local NexusTS database to a clean empty vault.")
    }

    // MARK: - Sessions, BSP Tiling & Multi-Window Detach / Re-Dock

    public var activeConnections: [VaultConnection] {
        document.connections.filter { !document.tombstonedObjectIds.contains($0.id) }
    }

    public var activeFolders: [VaultFolder] {
        document.folders
            .filter {
                !document.tombstonedObjectIds.contains($0.id)
                    && $0.id != VaultHierarchySanitizer.systemCredentialsFolderId
                    && !($0.parentFolderId == nil && VaultHierarchySanitizer.isReservedCredentialsFolderName($0.name))
            }
            .sorted {
                if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
                return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
    }

    public var activeCredentials: [VaultCredential] {
        document.credentials.filter { !document.tombstonedObjectIds.contains($0.id) }
    }

    public var activeSmartFolders: [SmartFolder] {
        document.smartFolders.filter { !document.tombstonedObjectIds.contains($0.id) }
    }

    public var activeDynamicFolders: [DynamicFolder] {
        document.dynamicFolders.filter { !document.tombstonedObjectIds.contains($0.id) }
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

    public func toggleSidebarVisibility() {
        columnVisibility = (columnVisibility == .detailOnly) ? .all : .detailOnly
    }

    public var focusedOrFirstActiveTab: WorkspaceSessionTab? {
        paneTree.focusedOrFirstActiveTab(focusedPaneId: focusedPaneId)
    }

    public func selectTabGlobally(_ tabId: UUID) {
        let (updated, paneId) = paneTree.activatingTabGlobally(tabId)
        paneTree = updated
        if let paneId {
            focusedPaneId = paneId
        }
        if let tab = paneTree.findTab(id: tabId), !tab.isLocalShell {
            selectedConnectionId = tab.connection.id
        }
    }

    public func enterFullScreenSession(tabId: UUID? = nil) {
        if let tabId {
            selectTabGlobally(tabId)
        }
        guard !paneTree.allTabs.isEmpty else { return }
        activeSection = .sessions
        isFullScreenSessionMode = true
    }

    public func exitFullScreenSession() {
        isFullScreenSessionMode = false
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
        if paneTree.allTabs.isEmpty {
            isFullScreenSessionMode = false
        }
    }

    /// Detaches a live session tab into a secondary macOS window WITHOUT dropping the SSH/RDP stream.
    public func stageTabForDetachedWindow(_ tabId: UUID) -> UUID? {
        guard let tab = paneTree.findTab(id: tabId) else { return nil }
        detachedWindowTabs[tabId] = tab
        paneTree = paneTree.removingTab(tabId)
        if paneTree.allTabs.isEmpty {
            isFullScreenSessionMode = false
        }
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
        let latestConnection = activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
        let resolved = PropertyInheritanceResolver.resolve(connection: latestConnection, in: document)
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
        if case .ssh(let sshCfg) = latestConnection.settings,
           let bastionId = sshCfg.bastionJumpConnectionId,
           let foundBastion = activeConnections.first(where: { $0.id == bastionId }) {
            bastionConn = foundBastion
            bastionCred = PropertyInheritanceResolver.resolve(connection: foundBastion, in: document).effectiveCredential
        }

        let created = LiveTerminalController(
            tabId: tab.id,
            connection: latestConnection,
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
        return buildAndConnectRdpSession(for: tab)
    }

    @discardableResult
    public func reconnectRdpSession(for tab: WorkspaceSessionTab) -> NativeRdpSession {
        rdpSessions.removeValue(forKey: tab.id)?.destroy()
        return buildAndConnectRdpSession(for: tab)
    }

    private func buildAndConnectRdpSession(for tab: WorkspaceSessionTab) -> NativeRdpSession {
        let latestConnection = activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
        let resolved = PropertyInheritanceResolver.resolve(connection: latestConnection, in: document)
        let rdpSettings: RdpProtocolSettings = {
            if case .rdp(let s) = latestConnection.settings { return s }
            return RdpProtocolSettings()
        }()
        let user = tab.adHocUsernameOverride ?? resolved.effectiveCredential?.username ?? "Administrator"
        let pass = tab.adHocPasswordOverride ?? resolved.effectiveCredential?.secretPassword ?? ""
        let dom = tab.adHocDomainOverride ?? resolved.effectiveCredential?.domain ?? ""

        let session = NativeRdpSession(
            host: latestConnection.host,
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

    // MARK: - Drag-and-Drop Folder Reparenting, Reordering, System "Credentials" Guard & CRUD

    public func rejectDropIntoSystemCredentialsFolder() {
        reportAppError(
            NexusAppError(
                category: .validation,
                title: "Credentials System Folder",
                userMessage: "The system folder \"Credentials\" can only hold credentials, not connections or subfolders.",
                remediationHint: "Drop connections or folders onto a regular folder or the connection hierarchy root."
            )
        )
    }

    public func moveConnection(_ connectionId: String, toFolderId targetFolderId: String?) {
        if targetFolderId == VaultHierarchySanitizer.systemCredentialsFolderId {
            rejectDropIntoSystemCredentialsFolder()
            return
        }
        commitMutation("Moved connection") { doc in
            if let idx = doc.connections.firstIndex(where: { $0.id == connectionId }) {
                let siblingMaxOrder = doc.connections
                    .filter { $0.parentFolderId == targetFolderId && $0.id != connectionId }
                    .map(\.sortOrder)
                    .max() ?? -1
                doc.connections[idx].parentFolderId = targetFolderId
                doc.connections[idx].sortOrder = siblingMaxOrder + 1
                doc.connections[idx].updatedAtEpochMs = VaultClock.nowMs()
            }
        }
    }

    public func reorderConnection(_ draggedConnectionId: String, before targetConnectionId: String) {
        guard draggedConnectionId != targetConnectionId else { return }
        commitMutation("Reordered connection") { doc in
            guard let target = doc.connections.first(where: { $0.id == targetConnectionId }),
                  let draggedIdx = doc.connections.firstIndex(where: { $0.id == draggedConnectionId }) else {
                return
            }
            let destFolderId = target.parentFolderId
            if destFolderId == VaultHierarchySanitizer.systemCredentialsFolderId { return }
            doc.connections[draggedIdx].parentFolderId = destFolderId
            let orderedSiblings = doc.connections
                .filter { $0.parentFolderId == destFolderId && !doc.tombstonedObjectIds.contains($0.id) && $0.id != draggedConnectionId }
                .sorted {
                    if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
                    return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
                }
            var newSequence = orderedSiblings.map(\.id)
            if let insertPos = newSequence.firstIndex(of: targetConnectionId) {
                newSequence.insert(draggedConnectionId, at: insertPos)
            } else {
                newSequence.append(draggedConnectionId)
            }
            let now = VaultClock.nowMs()
            for (rank, id) in newSequence.enumerated() {
                if let i = doc.connections.firstIndex(where: { $0.id == id }) {
                    doc.connections[i].sortOrder = rank
                    doc.connections[i].updatedAtEpochMs = now
                }
            }
        }
    }

    public func canMoveFolder(_ folderId: String, toParentFolderId targetParentId: String?) -> Bool {
        if folderId == VaultHierarchySanitizer.systemCredentialsFolderId { return false }
        if targetParentId == VaultHierarchySanitizer.systemCredentialsFolderId { return false }
        if folderId == targetParentId { return false }
        var cursor = targetParentId
        var visited = Set<String>()
        while let current = cursor {
            if !visited.insert(current).inserted { return false }
            if current == folderId { return false }
            cursor = document.folders.first(where: { $0.id == current })?.parentFolderId
        }
        return true
    }

    public func moveFolder(_ folderId: String, toParentFolderId targetParentId: String?) {
        if targetParentId == VaultHierarchySanitizer.systemCredentialsFolderId {
            rejectDropIntoSystemCredentialsFolder()
            return
        }
        guard canMoveFolder(folderId, toParentFolderId: targetParentId) else { return }
        commitMutation("Moved folder") { doc in
            if let idx = doc.folders.firstIndex(where: { $0.id == folderId }) {
                let siblingMaxOrder = doc.folders
                    .filter { $0.parentFolderId == targetParentId && $0.id != folderId }
                    .map(\.sortOrder)
                    .max() ?? -1
                doc.folders[idx].parentFolderId = targetParentId
                doc.folders[idx].sortOrder = siblingMaxOrder + 1
                doc.folders[idx].updatedAtEpochMs = VaultClock.nowMs()
            }
        }
    }

    public func moveOrReorderFolder(
        _ draggedFolderId: String,
        relativeTo targetFolderId: String,
        placement: FolderDropPlacement
    ) {
        if draggedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId { return }
        if targetFolderId == VaultHierarchySanitizer.systemCredentialsFolderId {
            rejectDropIntoSystemCredentialsFolder()
            return
        }
        guard draggedFolderId != targetFolderId else { return }

        switch placement {
        case .inside:
            moveFolder(draggedFolderId, toParentFolderId: targetFolderId)
        case .before, .after:
            guard let target = activeFolders.first(where: { $0.id == targetFolderId }) else { return }
            let newParentId = target.parentFolderId
            guard canMoveFolder(draggedFolderId, toParentFolderId: newParentId) else { return }
            commitMutation("Reordered folder") { doc in
                guard let draggedIdx = doc.folders.firstIndex(where: { $0.id == draggedFolderId }) else { return }
                doc.folders[draggedIdx].parentFolderId = newParentId
                let siblings = doc.folders
                    .filter {
                        $0.parentFolderId == newParentId
                            && !doc.tombstonedObjectIds.contains($0.id)
                            && $0.id != draggedFolderId
                            && $0.id != VaultHierarchySanitizer.systemCredentialsFolderId
                    }
                    .sorted {
                        if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
                        return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
                    }
                var orderedIds = siblings.map(\.id)
                if let targetPos = orderedIds.firstIndex(of: targetFolderId) {
                    let insertIndex = placement == .before ? targetPos : (targetPos + 1)
                    orderedIds.insert(draggedFolderId, at: min(insertIndex, orderedIds.count))
                } else {
                    orderedIds.append(draggedFolderId)
                }
                let now = VaultClock.nowMs()
                for (rank, id) in orderedIds.enumerated() {
                    if let i = doc.folders.firstIndex(where: { $0.id == id }) {
                        doc.folders[i].sortOrder = rank
                        doc.folders[i].updatedAtEpochMs = now
                    }
                }
            }
        }
    }

    public func renameFolder(_ folderId: String, newName: String) {
        guard folderId != VaultHierarchySanitizer.systemCredentialsFolderId else { return }
        let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        if VaultHierarchySanitizer.isReservedCredentialsFolderName(trimmed) {
            reportAppError(
                NexusAppError(
                    category: .validation,
                    title: "Reserved Folder Name",
                    userMessage: "\"Credentials\" is reserved for the bottom-pinned system Credentials folder.",
                    remediationHint: "Choose a different folder name for your connections."
                )
            )
            return
        }
        commitMutation("Renamed folder to \(trimmed)") { doc in
            if let idx = doc.folders.firstIndex(where: { $0.id == folderId }) {
                doc.folders[idx].name = trimmed
                doc.folders[idx].updatedAtEpochMs = VaultClock.nowMs()
            }
        }
        renamingFolder = nil
    }

    public func reorderFolder(_ folderId: String, moveUp: Bool) {
        guard folderId != VaultHierarchySanitizer.systemCredentialsFolderId else { return }
        guard let target = activeFolders.first(where: { $0.id == folderId }) else { return }
        let siblings = activeFolders.filter { $0.parentFolderId == target.parentFolderId }
        guard let currentPos = siblings.firstIndex(where: { $0.id == folderId }) else { return }
        let swapPos = moveUp ? currentPos - 1 : currentPos + 1
        guard siblings.indices.contains(swapPos) else { return }

        var ids = siblings.map(\.id)
        ids.swapAt(currentPos, swapPos)
        let now = VaultClock.nowMs()
        commitMutation("Reordered folder \(target.name)") { doc in
            for (rank, id) in ids.enumerated() {
                if let idx = doc.folders.firstIndex(where: { $0.id == id }) {
                    doc.folders[idx].sortOrder = rank
                    doc.folders[idx].updatedAtEpochMs = now
                }
            }
        }
    }

    public func duplicateFolder(_ folder: VaultFolder) {
        guard folder.id != VaultHierarchySanitizer.systemCredentialsFolderId else { return }
        commitMutation("Duplicated folder \(folder.name)") { doc in
            let now = VaultClock.nowMs()
            let newFolderId = UUID().uuidString.lowercased()
            let siblingMaxOrder = doc.folders
                .filter { $0.parentFolderId == folder.parentFolderId }
                .map(\.sortOrder)
                .max() ?? -1
            var folderCopy = folder
            folderCopy.id = newFolderId
            folderCopy.name = "\(folder.name) Copy"
            folderCopy.sortOrder = siblingMaxOrder + 1
            folderCopy.updatedAtEpochMs = now
            doc.folders.append(folderCopy)

            let directConns = doc.connections.filter {
                $0.parentFolderId == folder.id && !doc.tombstonedObjectIds.contains($0.id)
            }
            for conn in directConns {
                var connCopy = conn
                connCopy.id = UUID().uuidString.lowercased()
                connCopy.parentFolderId = newFolderId
                connCopy.updatedAtEpochMs = now
                doc.connections.append(connCopy)
            }
        }
    }

    public func deleteFolder(_ folderId: String, deleteContents: Bool) {
        guard folderId != VaultHierarchySanitizer.systemCredentialsFolderId else {
            deletingFolderCandidate = nil
            return
        }
        guard let folder = document.folders.first(where: { $0.id == folderId }) else {
            deletingFolderCandidate = nil
            return
        }
        if deleteContents {
            let subtreeIds = collectSubtreeFolderIds(rootFolderId: folderId)
            disconnectAllInFolder(folderId)
            commitMutation("Deleted folder \(folder.name) and contents") { doc in
                let removedConnIds = doc.connections
                    .filter { $0.parentFolderId.map(subtreeIds.contains) ?? false }
                    .map(\.id)
                for fId in subtreeIds { doc.tombstonedObjectIds.insert(fId) }
                for cId in removedConnIds { doc.tombstonedObjectIds.insert(cId) }
                doc.folders.removeAll { subtreeIds.contains($0.id) }
                doc.connections.removeAll { $0.parentFolderId.map(subtreeIds.contains) ?? false }
                doc.recentConnectionIds.removeAll { removedConnIds.contains($0) }
            }
            if let selFolder = selectedFolderId, subtreeIds.contains(selFolder) {
                selectedFolderId = nil
            }
            if let selConn = selectedConnectionId, !activeConnections.contains(where: { $0.id == selConn }) {
                selectedConnectionId = activeConnections.first?.id
            }
            collapsedFolderIds.subtract(subtreeIds)
        } else {
            let now = VaultClock.nowMs()
            commitMutation("Deleted folder \(folder.name) (moved items up)") { doc in
                doc.tombstonedObjectIds.insert(folder.id)
                doc.folders.removeAll { $0.id == folder.id }
                for idx in doc.folders.indices where doc.folders[idx].parentFolderId == folder.id {
                    doc.folders[idx].parentFolderId = folder.parentFolderId
                    doc.folders[idx].updatedAtEpochMs = now
                }
                for idx in doc.connections.indices where doc.connections[idx].parentFolderId == folder.id {
                    doc.connections[idx].parentFolderId = folder.parentFolderId
                    doc.connections[idx].updatedAtEpochMs = now
                }
            }
            if selectedFolderId == folder.id {
                selectedFolderId = folder.parentFolderId
            }
            collapsedFolderIds.remove(folder.id)
        }
        deletingFolderCandidate = nil
    }

    // MARK: - Intelligent Folders (SmartFolder), Dynamic Folders & Credentials CRUD

    public func saveSmartFolder(_ smartFolder: SmartFolder) {
        if let regexError = PropertyInheritanceResolver.validateSmartFolderRegex(smartFolder.filter.hostPatternRegex) {
            reportAppError(
                NexusAppError(
                    category: .validation,
                    title: "Invalid Intelligent Folder Regex",
                    userMessage: regexError,
                    remediationHint: "Fix the regular expression syntax before saving the Intelligent Folder."
                )
            )
            return
        }
        commitMutation("Saved Intelligent Folder \(smartFolder.name)") { doc in
            doc.tombstonedObjectIds.remove(smartFolder.id)
            if let idx = doc.smartFolders.firstIndex(where: { $0.id == smartFolder.id }) {
                doc.smartFolders[idx] = smartFolder
            } else {
                doc.smartFolders.append(smartFolder)
            }
        }
    }

    public func duplicateSmartFolder(_ smartFolder: SmartFolder) {
        var copy = smartFolder
        copy.id = "smart-\(UUID().uuidString.lowercased())"
        copy.name = "\(smartFolder.name) Copy"
        copy.updatedAtEpochMs = VaultClock.nowMs()
        commitMutation("Duplicated Intelligent Folder \(smartFolder.name)") { doc in
            doc.smartFolders.append(copy)
        }
    }

    public func deleteSmartFolder(_ smartFolderId: String) {
        commitMutation("Deleted Intelligent Folder") { doc in
            doc.tombstonedObjectIds.insert(smartFolderId)
            doc.smartFolders.removeAll { $0.id == smartFolderId }
        }
    }

    public func saveDynamicFolder(_ dynamicFolder: DynamicFolder) {
        if dynamicFolder.interpreter == .JSON_HTTP_API,
           !dynamicFolder.scriptOrEndpointPayload.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasPrefix("http"),
           let jsonErr = PropertyInheritanceResolver.validateDynamicFolderJsonPayload(dynamicFolder.scriptOrEndpointPayload) {
            reportAppError(
                NexusAppError(
                    category: .validation,
                    title: "Invalid Dynamic Folder JSON",
                    userMessage: jsonErr,
                    remediationHint: "Provide an HTTPS endpoint URL or valid RoyalJSON {\"Objects\": [...]}."
                )
            )
            return
        }
        commitMutation("Saved Dynamic Folder \(dynamicFolder.name)") { doc in
            doc.tombstonedObjectIds.remove(dynamicFolder.id)
            if let idx = doc.dynamicFolders.firstIndex(where: { $0.id == dynamicFolder.id }) {
                doc.dynamicFolders[idx] = dynamicFolder
            } else {
                doc.dynamicFolders.append(dynamicFolder)
            }
        }
        Task { await refreshDynamicFolder(dynamicFolder) }
    }

    public func duplicateDynamicFolder(_ dynamicFolder: DynamicFolder) {
        var copy = dynamicFolder
        copy.id = "dyn-\(UUID().uuidString.lowercased())"
        copy.name = "\(dynamicFolder.name) Copy"
        copy.updatedAtEpochMs = VaultClock.nowMs()
        commitMutation("Duplicated Dynamic Folder \(dynamicFolder.name)") { doc in
            doc.dynamicFolders.append(copy)
        }
    }

    public func deleteDynamicFolder(_ dynamicFolderId: String) {
        dynamicFolderCache.removeValue(forKey: dynamicFolderId)
        commitMutation("Deleted Dynamic Folder") { doc in
            doc.tombstonedObjectIds.insert(dynamicFolderId)
            doc.dynamicFolders.removeAll { $0.id == dynamicFolderId }
        }
    }

    public func saveCredential(_ credential: VaultCredential) {
        var normalized = credential
        normalized.parentFolderId = nil
        normalized.updatedAtEpochMs = VaultClock.nowMs()
        commitMutation("Saved credential \(normalized.name)") { doc in
            doc.tombstonedObjectIds.remove(normalized.id)
            if let idx = doc.credentials.firstIndex(where: { $0.id == normalized.id }) {
                doc.credentials[idx] = normalized
            } else {
                doc.credentials.append(normalized)
            }
        }
    }

    public func duplicateCredential(_ credential: VaultCredential) {
        var copy = credential
        copy.id = "cred-\(UUID().uuidString.lowercased())"
        copy.parentFolderId = nil
        copy.name = "\(credential.name) Copy"
        copy.updatedAtEpochMs = VaultClock.nowMs()
        commitMutation("Duplicated credential \(credential.name)") { doc in
            doc.credentials.append(copy)
        }
    }

    public func deleteCredential(_ credentialId: String) {
        if selectedCredentialId == credentialId {
            selectedCredentialId = nil
        }
        commitMutation("Deleted credential") { doc in
            doc.tombstonedObjectIds.insert(credentialId)
            doc.credentials.removeAll { $0.id == credentialId }
        }
    }

    public func reorderCredential(_ draggedCredentialId: String, before targetCredentialId: String) {
        guard draggedCredentialId != targetCredentialId else { return }
        commitMutation("Reordered credential") { doc in
            guard let dragged = doc.credentials.first(where: { $0.id == draggedCredentialId }) else { return }
            var remaining = doc.credentials.filter { $0.id != draggedCredentialId }
            if let pos = remaining.firstIndex(where: { $0.id == targetCredentialId }) {
                remaining.insert(dragged, at: pos)
            } else {
                remaining.append(dragged)
            }
            doc.credentials = remaining
        }
    }

    public func orderedConnectionsInSubtree(_ folderId: String) -> [VaultConnection] {
        var orderedFolders: [String] = []
        var visited = Set<String>()
        func visit(_ currentId: String) {
            guard visited.insert(currentId).inserted else { return }
            orderedFolders.append(currentId)
            let children = activeFolders.filter { $0.parentFolderId == currentId }
            for child in children {
                visit(child.id)
            }
        }
        visit(folderId)

        var collected: [VaultConnection] = []
        for fId in orderedFolders {
            let conns = activeConnections
                .filter { $0.parentFolderId == fId }
                .sorted {
                    if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
                    return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
                }
            collected.append(contentsOf: conns)
        }
        return collected
    }

    public func connectionCountInSubtree(_ folderId: String) -> Int {
        let subtreeIds = collectSubtreeFolderIds(rootFolderId: folderId)
        return activeConnections.filter { $0.parentFolderId.map(subtreeIds.contains) ?? false }.count
    }

    public func rdpConnectionCountInSubtree(_ folderId: String) -> Int {
        let subtreeIds = collectSubtreeFolderIds(rootFolderId: folderId)
        return activeConnections.filter { conn in
            guard let p = conn.parentFolderId, subtreeIds.contains(p) else { return false }
            return conn.protocolType == .RDP || conn.protocolType == .WINDOWS_TERMINAL_SERVICES
        }.count
    }

    public func activeSessionCountInSubtree(_ folderId: String) -> Int {
        let subtreeIds = collectSubtreeFolderIds(rootFolderId: folderId)
        return activeConnections
            .filter { $0.parentFolderId.map(subtreeIds.contains) ?? false }
            .reduce(0) { $0 + activeSessionCount(forConnectionId: $1.id) }
    }

    public func connectAllInFolder(_ folderId: String) {
        let targets = orderedConnectionsInSubtree(folderId)
        for conn in targets {
            openConnection(conn)
        }
    }

    public func connectAllRdpInFolder(_ folderId: String) {
        let rdpTargets = orderedConnectionsInSubtree(folderId).filter {
            $0.protocolType == .RDP || $0.protocolType == .WINDOWS_TERMINAL_SERVICES
        }
        for conn in rdpTargets {
            openConnection(conn)
        }
        appendAudit("Session", "Started \(rdpTargets.count) RDP connection(s) in folder subtree.")
    }

    public func expandAllFolders() {
        collapsedFolderIds.removeAll()
    }

    public func collapseAllFolders() {
        var ids = Set(activeFolders.map(\.id))
        ids.insert(VaultHierarchySanitizer.systemCredentialsFolderId)
        collapsedFolderIds = ids
    }

    public func expandSubtree(_ folderId: String) {
        collapsedFolderIds.subtract(collectSubtreeFolderIds(rootFolderId: folderId))
    }

    public func collapseSubtree(_ folderId: String) {
        collapsedFolderIds.formUnion(collectSubtreeFolderIds(rootFolderId: folderId))
    }

    public func handleSidebarDrop(_ payloads: [String], intoFolderId targetFolderId: String?) {
        if targetFolderId == VaultHierarchySanitizer.systemCredentialsFolderId {
            let hasNonCred = payloads.contains { !$0.hasPrefix("cred:") }
            if hasNonCred {
                rejectDropIntoSystemCredentialsFolder()
            }
            return
        }
        for raw in payloads {
            if raw.hasPrefix("cred:") {
                reportAppError(
                    NexusAppError(
                        category: .validation,
                        title: "Credential Placement Restricted",
                        userMessage: "Credentials always remain inside the bottom-pinned \"Credentials\" system folder.",
                        remediationHint: "Assign credentials to folders or connections via Folder Properties or Edit Connection."
                    )
                )
            } else if raw.hasPrefix("folder:") {
                let fId = String(raw.dropFirst("folder:".count))
                moveFolder(fId, toParentFolderId: targetFolderId)
            } else if raw.hasPrefix("conn:") {
                let cId = String(raw.dropFirst("conn:".count))
                moveConnection(cId, toFolderId: targetFolderId)
            } else {
                moveConnection(raw, toFolderId: targetFolderId)
            }
        }
    }

    public func setRdpSharedMacFolder(for connectionId: String, folderURL: URL?) {
        commitMutation("Updated RDP shared folder") { doc in
            guard let idx = doc.connections.firstIndex(where: { $0.id == connectionId }),
                  case .rdp(var rdp) = doc.connections[idx].settings else { return }
            if let folderURL {
                rdp.redirectDrives = true
                rdp.redirectedDriveSafUris = [folderURL.absoluteString]
            } else {
                rdp.redirectDrives = false
                rdp.redirectedDriveSafUris = []
            }
            doc.connections[idx].settings = .rdp(rdp)
            doc.connections[idx].updatedAtEpochMs = VaultClock.nowMs()
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

    @discardableResult
    public func importExternalVaultFile(url: URL, passphrase: String = "") throws -> ImportedVaultSummary {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        let summary = try EnterpriseVaultFormatImporter.importData(
            data,
            fileName: url.lastPathComponent,
            passphrase: passphrase.isEmpty ? activePassphrase : passphrase
        )
        if summary.isRoyalTsFormat {
            let suggestedName = url.deletingPathExtension().lastPathComponent
            self.pendingRoyalImport = PendingRoyalTsImport(
                summary: summary,
                suggestedFolderName: suggestedName
            )
            appendAudit("Import", "Parsed \(summary.detectedFormat) (\(summary.importedConnections) connections, \(summary.importedFolders) folders) — awaiting placement choice.")
            return summary
        }

        let sanitizedImported = VaultHierarchySanitizer.sanitizeVaultDocument(summary.document)
        let outcome = VaultDeltaConflictResolver.mergeDocuments(
            local: document,
            remote: sanitizedImported,
            localDeviceId: "macos"
        )
        document = VaultHierarchySanitizer.sanitizeVaultDocument(outcome.mergedDocument)
        persistLocalVault()
        appendAudit("Import", "Imported \(summary.detectedFormat): \(summary.importedConnections) connections, \(summary.importedFolders) folders.")
        return summary
    }

    public func confirmRoyalTsImport(mode: RoyalTsImportPlacementMode, folderName: String) {
        guard let pending = pendingRoyalImport else { return }
        let merged = RoyalTsImportMerger.applyPlacement(
            existing: document,
            importedRaw: pending.summary.document,
            mode: mode,
            newFolderName: folderName
        )
        if mode == .replaceExisting {
            for (_, ctrl) in terminalControllers { ctrl.shutdown() }
            terminalControllers.removeAll()
            for (_, rdp) in rdpSessions { rdp.destroy() }
            rdpSessions.removeAll()
            paneTree = .initialEmpty()
            detachedWindowTabs.removeAll()
            selectedConnectionId = nil
            selectedFolderId = nil
        }
        self.document = merged
        self.pendingRoyalImport = nil
        persistLocalVault()
        let summary = pending.summary
        statusToastMessage = "Imported \(summary.importedConnections) connection(s), \(summary.importedFolders) folder(s), and \(summary.importedCredentials) credential(s) from Royal TS."
        appendAudit("Import", "Completed Royal TS import (\(mode.rawValue)): \(summary.importedConnections) connections, \(summary.importedFolders) folders.")
    }

    public func linkCloudVaultURL(_ url: URL) throws {
        try cloudBookmarkBridge.linkVaultFileURL(url)
        linkedCloudFileURL = url
        Task { await syncCloudNow(reportErrors: true) }
    }

    public func syncCloudNow(reportErrors: Bool = false) async {
        if let url = linkedCloudFileURL ?? cloudBookmarkBridge.resolveLinkedURL() {
            do {
                let res = try cloudBookmarkBridge.syncWithLinkedFile(
                    localDocument: document,
                    passphrase: activePassphrase,
                    explicitURL: url,
                    kdf: .interactiveFast
                )
                self.document = VaultHierarchySanitizer.sanitizeVaultDocument(res.document)
                self.linkedCloudFileURL = res.syncedFileURL
                self.syncStatusSummary = "\(res.action.rawValue) (\(res.syncedFileURL.lastPathComponent))"
                appendAudit("Cloud Sync", "\(res.action.rawValue) via \(res.syncedFileURL.path) (\(res.conflictCount) conflicts resolved).")
            } catch {
                syncStatusSummary = "Sync error: \(error.localizedDescription)"
                appendAudit("Cloud Sync", "Error: \(error.localizedDescription)")
                if reportErrors {
                    reportError(error, operationTitle: "Cloud Sync Failed")
                }
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
                self.document = VaultHierarchySanitizer.sanitizeVaultDocument(res.document)
                self.syncStatusSummary = "Google Drive: \(res.action.rawValue)"
                persistLocalVault()
                appendAudit("Google Drive", "\(res.action.rawValue) (\(res.conflicts) conflicts merged).")
            } catch {
                syncStatusSummary = "Drive API error"
                appendAudit("Google Drive", error.localizedDescription)
                if reportErrors {
                    reportError(error, operationTitle: "Google Drive Sync Failed")
                }
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

    public func downloadVerifiedUpdateDmg() async throws -> URL? {
        guard let candidate = updateCandidate else { return nil }
        let fileURL = try await MacGitHubReleaseUpdater.downloadAndVerifyDmg(candidate: candidate)
        appendAudit("Updater", "Verified SHA-256 & downloaded \(fileURL.lastPathComponent).")
        return fileURL
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
