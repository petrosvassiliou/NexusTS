import AppKit
import Combine
import Darwin
import Foundation
import NexusTSCore
import SwiftUI

public enum WorkspacePrimarySection: String, CaseIterable, Identifiable, Hashable {
    case sessions
    case settings
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
        case .settings: return "gearshape"
        case .overview: return "square.grid.2x2"
        case .templates: return "doc.on.doc"
        case .credentials: return "key.horizontal"
        case .gateways: return "network.badge.shield.half.filled"
        case .automationTasks: return "bolt.horizontal"
        case .cloudSync: return "icloud.and.arrow.trianglehead.2.clockwise.rotate.90"
        }
    }
}

public enum UnifiedSettingsTab: String, CaseIterable, Identifiable, Hashable {
    case general
    case cloudSync
    case security
    case credentials
    case gateways
    case templates
    case tasks
    case updates

    public var id: String { rawValue }

    public var sfSymbol: String {
        switch self {
        case .general: return "slider.horizontal.3"
        case .cloudSync: return "arrow.triangle.2.circlepath.icloud"
        case .security: return "lock.shield"
        case .credentials: return "key.fill"
        case .gateways: return "network.badge.shield.half.filled"
        case .templates: return "doc.on.doc"
        case .tasks: return "bolt.horizontal"
        case .updates: return "arrow.down.circle"
        }
    }

    public func title(in strings: LocalizedWorkspaceStrings) -> String {
        switch self {
        case .general: return strings.generalSettings
        case .cloudSync: return strings.cloudSync
        case .security: return strings.securitySettings
        case .credentials: return strings.credentials
        case .gateways: return strings.gateways
        case .templates: return strings.templates
        case .tasks: return strings.automationTasks
        case .updates: return strings.updatesSettings
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
    private var telnetSession: NativeTelnetSession?
    private var pollTimer: Timer?
    private var currentCols: Int = 110
    private var currentRows: Int = 32

    public var applicationCursorKeys: Bool {
        vtEngine.applicationCursorKeys
    }

    public init(
        tabId: UUID,
        connection: VaultConnection,
        isLocalShell: Bool,
        effectiveCredential: VaultCredential?,
        bastionConnection: VaultConnection? = nil,
        bastionCredential: VaultCredential? = nil,
        gatewayBastionSpec: SshFfiConnectPayload? = nil
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
        } else if connection.protocolType == .TELNET {
            startRemoteTelnetSession()
        } else {
            startRemoteSshSession(
                credential: effectiveCredential,
                bastionConn: bastionConnection,
                bastionCred: bastionCredential,
                gatewayBastionSpec: gatewayBastionSpec
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
        telnetSession?.disconnect()
        telnetSession = nil
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

    private func startRemoteTelnetSession() {
        let sshSettings: SshProtocolSettings = {
            if case .ssh(let s) = connection.settings { return s }
            return SshProtocolSettings(port: 23)
        }()
        let resolvedPort = sshSettings.port > 0 ? sshSettings.port : 23
        let termType = sshSettings.terminalType.isEmpty ? "xterm-256color" : sshSettings.terminalType
        let startupCmd = sshSettings.startupCommand
        let connId = connection.id
        let connHost = connection.host
        let cols = currentCols
        let rows = currentRows

        feedBannerText("\u{1B}[38;2;91;155;255m● Connecting via Telnet to \(connHost):\(resolvedPort)…\u{1B}[0m\r\n")
        statusBanner = "Connecting to \(connHost):\(resolvedPort)…"

        Task { @MainActor [weak self] in
            do {
                let session = try await Task.detached(priority: .userInitiated) { () -> NativeTelnetSession in
                    let tn = try NativeTelnetSession.connect(
                        host: connHost,
                        port: UInt16(clamping: resolvedPort),
                        cols: cols,
                        rows: rows,
                        term: termType
                    )
                    if !startupCmd.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        _ = tn.sendInput(Data((startupCmd + "\r\n").utf8))
                    }
                    return tn
                }.value

                guard let self else {
                    session.disconnect()
                    return
                }
                self.telnetSession = session
                self.isConnected = true
                self.lastConnectError = nil
                self.statusBanner = "Connected • Telnet RFC 854 (\(termType))"
                self.startOutputPump()
            } catch {
                let classified = NexusErrorClassifier.classify(
                    error,
                    operationTitle: "Telnet Connection Failed (\(connHost))",
                    recoveryConnectionId: connId
                )
                self?.isConnected = false
                self?.lastConnectError = classified
                self?.statusBanner = classified.userMessage
                self?.feedBannerText("\r\n\u{1B}[38;2;255;99;99m✖ \(classified.userMessage)\r\n  ↳ \(classified.remediationHint)\r\n  [\(error.localizedDescription)]\u{1B}[0m\r\n")
            }
        }
    }

    private func startRemoteSshSession(
        credential: VaultCredential?,
        bastionConn: VaultConnection?,
        bastionCred: VaultCredential?,
        gatewayBastionSpec: SshFfiConnectPayload?
    ) {
        let sshSettings: SshProtocolSettings = {
            if case .ssh(let s) = connection.settings { return s }
            return SshProtocolSettings(port: connection.protocolType.defaultPort)
        }()
        let user = (credential?.username.isEmpty == false) ? credential!.username : NSUserName()
        let auth: SshFfiConnectPayload.Auth = {
            if let pem = credential?.privateKeyPem, !pem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                let pass = (credential?.keyPassphrase.isEmpty == true) ? nil : credential?.keyPassphrase
                return .privateKeyPem(pem: pem, passphrase: pass)
            }
            return .password(credential?.secretPassword ?? "")
        }()

        let spec = SshFfiConnectPayload(
            host: connection.host,
            port: UInt16(clamping: sshSettings.port > 0 ? sshSettings.port : 22),
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
                if let pem = bastionCred?.privateKeyPem, !pem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    let bPass = (bastionCred?.keyPassphrase.isEmpty == true) ? nil : bastionCred?.keyPassphrase
                    return .privateKeyPem(pem: pem, passphrase: bPass)
                }
                return .password(bastionCred?.secretPassword ?? "")
            }()
            return SshFfiConnectPayload(host: bConn.host, port: UInt16(clamping: bPort), username: bUser, auth: bAuth)
        } ?? gatewayBastionSpec

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
        } else if let tn = telnetSession {
            let (bytes, eof) = tn.drainOutput()
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
                let err = tn.lastErrorMessage()
                statusBanner = err.isEmpty ? "Remote Telnet closed" : "Telnet closed: \(err)"
                feedBannerText("\r\n\u{1B}[90m[Telnet connection closed by remote host]\u{1B}[0m\r\n")
                pollTimer?.invalidate()
            }
        }
    }

    public func sendBytes(_ data: Data) {
        if let pty = localPty {
            pty.sendInput(data)
        } else if let ssh = sshSession {
            _ = ssh.sendInput(data)
        } else if let tn = telnetSession {
            _ = tn.sendInput(data)
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
        telnetSession?.resize(cols: c, rows: r)
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

// MARK: - Persistent Dual-Pane File Transfer Runtime (SFTP / SCP / FTP / FTPS)

@MainActor
public final class LiveTransferController: ObservableObject {
    public let tabId: UUID
    public private(set) var connection: VaultConnection

    @Published public var localPath: String
    @Published public var remotePath: String
    @Published public private(set) var localEntries: [URL] = []
    @Published public private(set) var remoteEntries: [SftpRemoteEntry] = []
    @Published public private(set) var isConnecting: Bool = false
    @Published public private(set) var isConnected: Bool = false
    @Published public var statusText: String = "Initializing…"
    @Published public var lastErrorText: String? = nil

    private var underlyingSshSession: NativeSshSession?
    private var remoteClient: RemoteFileTransferClient?
    private var showHiddenDotfiles: Bool = true

    public init(
        tabId: UUID,
        connection: VaultConnection,
        credential: VaultCredential?,
        bastionSpec: SshFfiConnectPayload?
    ) {
        self.tabId = tabId
        self.connection = connection
        let ftSettings: FileTransferProtocolSettings = {
            if case .fileTransfer(let s) = connection.settings { return s }
            return FileTransferProtocolSettings(
                port: connection.protocolType.defaultPort,
                ftpsImplicitTls: connection.protocolType == .FTPS
            )
        }()
        self.showHiddenDotfiles = ftSettings.showHiddenDotfiles
        self.localPath = ftSettings.initialLocalDirectory.isEmpty
            ? FileManager.default.homeDirectoryForCurrentUser.path
            : ftSettings.initialLocalDirectory
        self.remotePath = ftSettings.initialRemoteDirectory
        refreshLocal()
        connect(connection: connection, credential: credential, bastionSpec: bastionSpec)
    }

    public func shutdown() {
        let client = remoteClient
        let ssh = underlyingSshSession
        remoteClient = nil
        underlyingSshSession = nil
        isConnected = false
        isConnecting = false
        Task.detached(priority: .utility) {
            client?.disconnect()
            ssh?.disconnect()
        }
    }

    public func connect(
        connection: VaultConnection,
        credential: VaultCredential?,
        bastionSpec: SshFfiConnectPayload?
    ) {
        self.connection = connection
        let oldClient = remoteClient
        let oldSsh = underlyingSshSession
        remoteClient = nil
        underlyingSshSession = nil

        let ftSettings: FileTransferProtocolSettings = {
            if case .fileTransfer(let s) = connection.settings { return s }
            return FileTransferProtocolSettings(
                port: connection.protocolType.defaultPort,
                ftpsImplicitTls: connection.protocolType == .FTPS
            )
        }()
        self.showHiddenDotfiles = ftSettings.showHiddenDotfiles
        let proto = connection.protocolType
        let host = connection.host
        let port = UInt16(clamping: ftSettings.port > 0 ? ftSettings.port : proto.defaultPort)
        let user = (credential?.username.isEmpty == false)
            ? credential!.username
            : ((proto == .FTP || proto == .FTPS) ? "anonymous" : NSUserName())
        let password = credential?.secretPassword ?? ""
        let keyPem = credential?.privateKeyPem ?? ""
        let keyPass = (credential?.keyPassphrase.isEmpty == true) ? nil : credential?.keyPassphrase
        let initialRemote = remotePath

        isConnecting = true
        isConnected = false
        lastErrorText = nil
        statusText = "Connecting via \(proto.shortBadge) to \(host):\(port)…"

        Task { @MainActor [weak self] in
            do {
                let (sshHolder, client, resolvedDir, entries) = try await Task.detached(
                    priority: .userInitiated
                ) { () -> (NativeSshSession?, RemoteFileTransferClient, String, [SftpRemoteEntry]) in
                    oldClient?.disconnect()
                    oldSsh?.disconnect()

                    let sshAuth: SshFfiConnectPayload.Auth = {
                        if !keyPem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            return .privateKeyPem(pem: keyPem, passphrase: keyPass)
                        }
                        return .password(password)
                    }()

                    var sshSession: NativeSshSession? = nil
                    let transferClient: RemoteFileTransferClient

                    switch proto {
                    case .SCP:
                        let spec = SshFfiConnectPayload(host: host, port: port, username: user, auth: sshAuth)
                        transferClient = try NativeScpClient.connect(spec: spec, bastion: bastionSpec)
                    case .FTP:
                        transferClient = try NativeFtpClient.connect(
                            host: host,
                            port: port,
                            username: user,
                            password: password
                        )
                    case .FTPS:
                        let implicit = ftSettings.ftpsImplicitTls
                        transferClient = try MacFtpsClient.connect(
                            host: host,
                            port: port,
                            username: user,
                            password: password,
                            implicitTls: implicit,
                            verifyTls: ftSettings.verifyTls
                        )
                    default:
                        let spec = SshFfiConnectPayload(host: host, port: port, username: user, auth: sshAuth)
                        let ssh = try NativeSshSession.connect(spec: spec, bastion: bastionSpec, openPty: false)
                        do {
                            let sftp = try ssh.openSftpSubsystem()
                            sshSession = ssh
                            transferClient = sftp
                        } catch {
                            ssh.disconnect()
                            throw error
                        }
                    }

                    let trimmedInitial = initialRemote.trimmingCharacters(in: .whitespacesAndNewlines)
                    var targetDir: String
                    if trimmedInitial.isEmpty || trimmedInitial == "~" || trimmedInitial == "." {
                        targetDir = (try? transferClient.pwd()) ?? ""
                    } else {
                        targetDir = trimmedInitial
                    }
                    do {
                        let listed = try transferClient.listDirectory(targetDir)
                        let displayDir = targetDir.isEmpty ? ((try? transferClient.pwd()) ?? "/") : targetDir
                        return (sshSession, transferClient, displayDir, listed)
                    } catch {
                        if let fallbackPwd = try? transferClient.pwd(), fallbackPwd != targetDir {
                            let listed = try transferClient.listDirectory(fallbackPwd)
                            return (sshSession, transferClient, fallbackPwd, listed)
                        }
                        let listedRoot = try transferClient.listDirectory("/")
                        return (sshSession, transferClient, "/", listedRoot)
                    }
                }.value

                guard let self else {
                    client.disconnect()
                    sshHolder?.disconnect()
                    return
                }
                self.underlyingSshSession = sshHolder
                self.remoteClient = client
                self.remotePath = resolvedDir
                self.remoteEntries = self.filterHidden(entries)
                self.isConnecting = false
                self.isConnected = true
                self.lastErrorText = nil
                self.statusText = "Connected • \(proto.displayName) (\(entries.count) items)"
            } catch {
                guard let self else { return }
                self.isConnecting = false
                self.isConnected = false
                self.lastErrorText = error.localizedDescription
                self.statusText = "\(proto.shortBadge) error: \(error.localizedDescription)"
            }
        }
    }

    private func filterHidden(_ entries: [SftpRemoteEntry]) -> [SftpRemoteEntry] {
        guard !showHiddenDotfiles else { return entries }
        return entries.filter { !$0.name.hasPrefix(".") }
    }

    public func refreshLocal() {
        do {
            let items = try FileManager.default.contentsOfDirectory(
                at: URL(fileURLWithPath: localPath),
                includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey],
                options: showHiddenDotfiles ? [] : [.skipsHiddenFiles]
            )
            localEntries = items.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
        } catch {
            lastErrorText = "Local directory error: \(error.localizedDescription)"
            statusText = lastErrorText ?? ""
        }
    }

    public func refreshRemote(
        pathOverride: String? = nil,
        onError: ((Error) -> Void)? = nil
    ) {
        let target = pathOverride ?? remotePath
        guard let client = remoteClient else {
            statusText = "Not connected to \(connection.host)"
            return
        }
        Task { @MainActor [weak self] in
            do {
                let listed = try await Task.detached(priority: .userInitiated) {
                    try client.listDirectory(target)
                }.value
                guard let self else { return }
                self.remotePath = target
                self.remoteEntries = self.filterHidden(listed)
                self.lastErrorText = nil
                self.statusText = "Listed \(self.remoteEntries.count) items in \(target)"
            } catch {
                self?.lastErrorText = error.localizedDescription
                self?.statusText = "Directory error: \(error.localizedDescription)"
                onError?(error)
            }
        }
    }

    public func uploadFiles(_ urls: [URL], onError: ((Error) -> Void)? = nil) {
        guard let client = remoteClient else {
            let err = NativeBridgeError.sftpError("Connect remote session before uploading files.")
            statusText = err.localizedDescription
            onError?(err)
            return
        }
        let targetDir = remotePath
        statusText = "Uploading \(urls.count) item(s)…"
        Task { @MainActor [weak self] in
            do {
                var uploadedCount = 0
                try await Task.detached(priority: .userInitiated) {
                    for fileUrl in urls {
                        var isDir: ObjCBool = false
                        guard FileManager.default.fileExists(atPath: fileUrl.path, isDirectory: &isDir),
                              !isDir.boolValue else {
                            continue
                        }
                        let bytes = try Data(contentsOf: fileUrl)
                        let cleanDir = targetDir.hasSuffix("/") ? String(targetDir.dropLast()) : targetDir
                        let dest = cleanDir.isEmpty ? "/\(fileUrl.lastPathComponent)" : "\(cleanDir)/\(fileUrl.lastPathComponent)"
                        try client.uploadBytes(bytes, to: dest)
                        uploadedCount += 1
                    }
                }.value
                guard let self else { return }
                self.statusText = "Uploaded \(uploadedCount) file(s) to \(targetDir)"
                self.refreshRemote(onError: onError)
            } catch {
                self?.lastErrorText = error.localizedDescription
                self?.statusText = "Upload failed: \(error.localizedDescription)"
                onError?(error)
            }
        }
    }

    public func downloadEntry(
        _ entry: SftpRemoteEntry,
        destinationDirectory: URL? = nil,
        onError: ((Error) -> Void)? = nil
    ) {
        guard !entry.isDirectory else { return }
        guard let client = remoteClient else {
            let err = NativeBridgeError.sftpError("Connect remote session before downloading files.")
            statusText = err.localizedDescription
            onError?(err)
            return
        }
        let destFolder = destinationDirectory ?? URL(fileURLWithPath: localPath)
        let destFile = destFolder.appendingPathComponent(entry.name)
        statusText = "Downloading \(entry.name)…"
        Task { @MainActor [weak self] in
            do {
                let count = try await Task.detached(priority: .userInitiated) { () -> Int in
                    let data = try client.downloadBytes(entry.fullPath)
                    try data.write(to: destFile, options: .atomic)
                    return data.count
                }.value
                guard let self else { return }
                self.statusText = "Downloaded \(entry.name) (\(ByteCountFormatter.string(fromByteCount: Int64(count), countStyle: .file)))"
                self.refreshLocal()
            } catch {
                self?.lastErrorText = error.localizedDescription
                self?.statusText = "Download failed: \(error.localizedDescription)"
                onError?(error)
            }
        }
    }

    public func createRemoteDirectory(named name: String, onError: ((Error) -> Void)? = nil) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let client = remoteClient else { return }
        let base = remotePath.hasSuffix("/") ? String(remotePath.dropLast()) : remotePath
        let target = base.isEmpty ? "/\(trimmed)" : "\(base)/\(trimmed)"
        Task { @MainActor [weak self] in
            do {
                try await Task.detached(priority: .userInitiated) {
                    try client.createDirectory(target)
                }.value
                self?.statusText = "Created \(target)"
                self?.refreshRemote(onError: onError)
            } catch {
                self?.lastErrorText = error.localizedDescription
                self?.statusText = "mkdir failed: \(error.localizedDescription)"
                onError?(error)
            }
        }
    }

    public func deleteRemoteEntry(_ entry: SftpRemoteEntry, onError: ((Error) -> Void)? = nil) {
        guard let client = remoteClient else { return }
        Task { @MainActor [weak self] in
            do {
                try await Task.detached(priority: .userInitiated) {
                    try client.removeEntry(entry.fullPath, isDirectory: entry.isDirectory)
                }.value
                self?.statusText = "Deleted \(entry.name)"
                self?.refreshRemote(onError: onError)
            } catch {
                self?.lastErrorText = error.localizedDescription
                self?.statusText = "Delete failed: \(error.localizedDescription)"
                onError?(error)
            }
        }
    }

    public func renameRemoteEntry(
        _ entry: SftpRemoteEntry,
        newName: String,
        onError: ((Error) -> Void)? = nil
    ) {
        let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let client = remoteClient else { return }
        let parent = (entry.fullPath as NSString).deletingLastPathComponent
        let cleanParent = parent.hasSuffix("/") ? String(parent.dropLast()) : parent
        let dest = cleanParent.isEmpty ? "/\(trimmed)" : "\(cleanParent)/\(trimmed)"
        Task { @MainActor [weak self] in
            do {
                try await Task.detached(priority: .userInitiated) {
                    try client.renameEntry(from: entry.fullPath, to: dest)
                }.value
                self?.statusText = "Renamed \(entry.name) → \(trimmed)"
                self?.refreshRemote(onError: onError)
            } catch {
                self?.lastErrorText = error.localizedDescription
                self?.statusText = "Rename failed: \(error.localizedDescription)"
                onError?(error)
            }
        }
    }

    public func chmodRemoteEntry(
        _ entry: SftpRemoteEntry,
        octalString: String,
        onError: ((Error) -> Void)? = nil
    ) {
        let cleaned = octalString
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "0o", with: "")
        guard let mode = UInt32(cleaned, radix: 8), let client = remoteClient else {
            statusText = "Invalid octal mode '\(octalString)' (example: 0755 or 644)"
            return
        }
        Task { @MainActor [weak self] in
            do {
                try await Task.detached(priority: .userInitiated) {
                    try client.chmod(entry.fullPath, modeOctal: mode)
                }.value
                self?.statusText = String(format: "Changed permissions on %@ to %04o", entry.name, mode)
                self?.refreshRemote(onError: onError)
            } catch {
                self?.lastErrorText = error.localizedDescription
                self?.statusText = "chmod failed: \(error.localizedDescription)"
                onError?(error)
            }
        }
    }
}

// MARK: - Persistent Live VNC / Apple ARD Desktop Runtime

@MainActor
public final class LiveVncController: ObservableObject {
    public let tabId: UUID
    public private(set) var connection: VaultConnection
    public private(set) var vncSettings: VncProtocolSettings
    public private(set) var effectiveUsername: String
    public private(set) var effectivePassword: String

    @Published public private(set) var session: NativeLiveVncSession?
    @Published public private(set) var isConnecting: Bool = false
    @Published public private(set) var isConnected: Bool = false
    @Published public private(set) var desktopName: String = ""
    @Published public private(set) var dimensionsText: String = "Connecting…"
    @Published public private(set) var lastErrorText: String? = nil

    public init(
        tabId: UUID,
        connection: VaultConnection,
        credential: VaultCredential?
    ) {
        self.tabId = tabId
        self.connection = connection
        if case .vnc(let s) = connection.settings {
            self.vncSettings = s
        } else {
            self.vncSettings = VncProtocolSettings(
                port: 5900,
                appleArdDiffieHellmanAuth: connection.protocolType == .APPLE_SCREEN_SHARING
            )
        }
        self.effectiveUsername = credential?.username ?? ""
        self.effectivePassword = credential?.secretPassword ?? ""
        connect(connection: connection, credential: credential)
    }

    public func connect(connection: VaultConnection, credential: VaultCredential?) {
        self.connection = connection
        if case .vnc(let s) = connection.settings {
            self.vncSettings = s
        }
        self.effectiveUsername = credential?.username ?? ""
        self.effectivePassword = credential?.secretPassword ?? ""

        let old = session
        session = nil
        isConnecting = true
        isConnected = false
        lastErrorText = nil
        dimensionsText = "Connecting…"

        let host = connection.host
        let port = UInt16(clamping: vncSettings.port > 0 ? vncSettings.port : 5900)
        let useArdAuth = connection.protocolType == .APPLE_SCREEN_SHARING || vncSettings.appleArdDiffieHellmanAuth
        let user = useArdAuth ? effectiveUsername : ""
        let pass = effectivePassword
        let shared = vncSettings.sharedSession
        let viewOnly = vncSettings.viewOnly

        Task { @MainActor [weak self] in
            do {
                let connected = try await Task.detached(priority: .userInitiated) { () -> NativeLiveVncSession in
                    old?.disconnect()
                    return try NativeLiveVncSession.connect(
                        host: host,
                        port: port,
                        username: user,
                        password: pass,
                        sharedSession: shared,
                        viewOnly: viewOnly
                    )
                }.value

                guard let self else {
                    connected.disconnect()
                    return
                }
                self.session = connected
                self.isConnecting = false
                self.isConnected = connected.isConnected
                self.desktopName = connected.desktopName()
                if let dims = connected.dimensions() {
                    self.dimensionsText = "\(dims.width)×\(dims.height)"
                } else {
                    self.dimensionsText = "Connected"
                }
            } catch {
                guard let self else { return }
                self.isConnecting = false
                self.isConnected = false
                self.lastErrorText = error.localizedDescription
                self.dimensionsText = "Disconnected"
            }
        }
    }

    public func updateRuntimeStatus(isConnected: Bool, dimensionsText: String?, errorText: String?) {
        if self.isConnected != isConnected {
            self.isConnected = isConnected
        }
        if let dims = dimensionsText, !dims.isEmpty, self.dimensionsText != dims {
            self.dimensionsText = dims
        }
        if let err = errorText, !err.isEmpty, self.lastErrorText != err {
            self.lastErrorText = err
        }
    }

    public func shutdown() {
        let current = session
        session = nil
        isConnected = false
        isConnecting = false
        current?.disconnect()
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
    @Published public var activeSettingsTab: UnifiedSettingsTab = .general
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
    @Published public var syncStatusSummary: String = "Not connected"
    @Published public var linkedCloudFileURL: URL? = nil
    @Published public var isSyncingCloud: Bool = false
    @Published public var lastCloudSyncDate: Date? = nil
    @Published public var updateCandidate: MacReleaseUpdateCandidate? = nil
    @Published public var updateInstallPhase: MacUpdateInstallPhase = .idle
    @Published public var autoCheckUpdatesEnabled: Bool = (UserDefaults.standard.object(forKey: "nexusts.macos.autoCheckUpdates") as? Bool) ?? true {
        didSet {
            UserDefaults.standard.set(autoCheckUpdatesEnabled, forKey: "nexusts.macos.autoCheckUpdates")
        }
    }
    @Published public var isPresentingSoftwareUpdateSheet: Bool = false
    @Published public var auditLog: [AuditLogEntry] = []

    // Sheet Presentation Triggers & Global Error State
    @Published public var editingConnection: VaultConnection? = nil
    @Published public var isPresentingNewConnectionSheet: Bool = false
    @Published public var newConnectionInitialFolderId: String? = nil
    @Published public var newConnectionInitialProtocol: VaultProtocolType = .RDP
    @Published public var editingFolder: VaultFolder? = nil
    @Published public var isPresentingNewFolderSheet: Bool = false
    @Published public var newFolderInitialParentId: String? = nil
    @Published public var renamingFolder: VaultFolder? = nil
    @Published public var deletingFolderCandidate: VaultFolder? = nil
    @Published public var connectWithOptionsTarget: VaultConnection? = nil
    @Published public var editingSmartFolder: SmartFolder? = nil
    @Published public var isPresentingNewSmartFolderSheet: Bool = false
    @Published public var editingCredential: VaultCredential? = nil
    @Published public var isPresentingNewCredentialSheet: Bool = false
    @Published public var pendingRoyalImport: PendingRoyalTsImport? = nil
    @Published public var isPresentingResetDatabaseSheet: Bool = false
    @Published public var activeAppError: NexusAppError? = nil
    @Published public var statusToastMessage: String? = nil

    private var activePassphrase: String = NxtsVaultSerializer.defaultTransparentPassphrase
    private var isStarterVaultUninitialized: Bool = false
    private var terminalControllers: [UUID: LiveTerminalController] = [:]
    private var rdpSessions: [UUID: NativeRdpSession] = [:]
    private var transferControllers: [UUID: LiveTransferController] = [:]
    private var vncControllers: [UUID: LiveVncController] = [:]
    private var didShutdownForTermination: Bool = false
    private var appWillTerminateObserver: NSObjectProtocol?
    private var appDidBecomeActiveObserver: NSObjectProtocol?
    private var cloudDebouncedSyncTask: Task<Void, Never>? = nil
    private var cloudWatcherLoopTask: Task<Void, Never>? = nil
    private var pendingFollowUpCloudSync: Bool = false
    private var watchedFileFd: Int32 = -1
    private var watchedDirFd: Int32 = -1
    private var watchedFileSource: DispatchSourceFileSystemObject? = nil
    private var watchedDirSource: DispatchSourceFileSystemObject? = nil
    public let cloudBookmarkBridge = MacCloudBookmarkSyncBridge()
    public let googleDriveEngine = GoogleDriveRestSyncEngine()

    public var hasConfiguredCloudSync: Bool {
        if linkedCloudFileURL != nil || cloudBookmarkBridge.resolveLinkedURL() != nil {
            return true
        }
        if let token = MacKeychainAndBiometrics.shared.loadToken(key: "gdrive_oauth_bearer"), !token.isEmpty {
            return true
        }
        return false
    }

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
        if let linked = self.linkedCloudFileURL {
            self.syncStatusSummary = "Connected to \(linked.lastPathComponent)"
        }
        loadPersistedLocalVault()
        appendAudit("Vault", "NexusTS macOS initialized (\(document.connections.count) connections).")
        self.appWillTerminateObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.willTerminateNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            if Thread.isMainThread {
                MainActor.assumeIsolated {
                    self?.shutdownAllSessionsForAppTermination()
                }
            } else {
                Task { @MainActor [weak self] in
                    self?.shutdownAllSessionsForAppTermination()
                }
            }
        }
        self.appDidBecomeActiveObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, !self.isVaultLocked, self.hasConfiguredCloudSync else { return }
                self.scheduleDebouncedCloudSync(delayNanoseconds: 350_000_000)
            }
        }
        installCloudFileWatcher()
        startCloudChangeWatcherLoop()
        if !isVaultLocked && hasConfiguredCloudSync {
            Task { [weak self] in
                await self?.syncCloudNow(reportErrors: false)
            }
        }
        Task { [weak self] in
            if self?.autoCheckUpdatesEnabled == true {
                await self?.checkGitHubUpdates(manual: false)
            }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 4 * 3600 * 1_000_000_000)
                if Task.isCancelled { break }
                if self?.autoCheckUpdatesEnabled == true {
                    await self?.checkGitHubUpdates(manual: false)
                }
            }
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
            self.isStarterVaultUninitialized = true
            persistLocalVault(triggerCloudSync: false)
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
            self.isStarterVaultUninitialized = true
            persistLocalVault(triggerCloudSync: false)
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
            self.isStarterVaultUninitialized = false
            self.hasCustomPassphrase = false
            self.isVaultLocked = false
            if sanitized != opened {
                persistLocalVault(triggerCloudSync: false)
            }
            MacSpotlightVaultIndexer.reindexConnections(in: sanitized, enabled: spotlightIndexingEnabled)
        } else {
            // Custom master passphrase is set -> present Zero-Knowledge Lock Shield + Touch ID!
            self.hasCustomPassphrase = true
            self.isVaultLocked = true
        }
    }

    public func persistLocalVault(triggerCloudSync: Bool = true) {
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

            if triggerCloudSync && autoSyncOnSave && hasConfiguredCloudSync && !isVaultLocked {
                scheduleDebouncedCloudSync()
            }
        } catch {
            reportError(error, operationTitle: "Vault Persistence Failed")
        }
    }

    public func commitMutation(_ reason: String, mutate: (inout NxtsVaultDocument) -> Void) {
        isStarterVaultUninitialized = false
        mutate(&document)
        document.bumpRevision(actor: "macos")
        persistLocalVault(triggerCloudSync: true)
        appendAudit("Vault", "\(reason) (rev \(document.revision))")
    }

    private func shutdownAllLiveRuntimes() {
        for (_, rdp) in rdpSessions { rdp.setFrameReadyHandler(nil) }
        for (_, ctrl) in terminalControllers { ctrl.shutdown() }
        terminalControllers.removeAll()
        for (_, rdp) in rdpSessions { rdp.destroy() }
        rdpSessions.removeAll()
        for (_, transfer) in transferControllers { transfer.shutdown() }
        transferControllers.removeAll()
        for (_, vnc) in vncControllers { vnc.shutdown() }
        vncControllers.removeAll()
    }

    public func shutdownAllSessionsForAppTermination() {
        guard !didShutdownForTermination else { return }
        didShutdownForTermination = true
        teardownCloudFileWatcher()
        cloudWatcherLoopTask?.cancel()
        cloudDebouncedSyncTask?.cancel()
        for (_, rdp) in rdpSessions {
            rdp.setFrameReadyHandler(nil)
        }
        NativeRdpSession.shutdownAllNativeSessions()
        for (_, rdp) in rdpSessions {
            rdp.destroy()
        }
        rdpSessions.removeAll()
        for (_, ctrl) in terminalControllers { ctrl.shutdown() }
        terminalControllers.removeAll()
        for (_, transfer) in transferControllers { transfer.shutdown() }
        transferControllers.removeAll()
        for (_, vnc) in vncControllers { vnc.shutdown() }
        vncControllers.removeAll()
    }

    public func lockVault() {
        // Close live interactive sessions for zero-knowledge protection
        shutdownAllLiveRuntimes()
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
                        persistLocalVault(triggerCloudSync: false)
                    }
                    appendAudit("Security", "Vault unlocked successfully.")
                    if hasConfiguredCloudSync {
                        installCloudFileWatcher()
                        Task { [weak self] in
                            await self?.syncCloudNow(reportErrors: false)
                        }
                    }
                    return true
                }
            }
            self.unlockErrorText = "Invalid master passphrase for this .nxts v2 vault."
            return false
        }
        self.isVaultLocked = false
        if hasConfiguredCloudSync {
            installCloudFileWatcher()
            Task { [weak self] in
                await self?.syncCloudNow(reportErrors: false)
            }
        }
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
        shutdownAllLiveRuntimes()
        paneTree = .initialEmpty()
        detachedWindowTabs.removeAll()
        isFullScreenSessionMode = false
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

    public func presentNewConnectionSheet(defaultProtocol: VaultProtocolType = .RDP) {
        let targetFolder = selectedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId
            ? nil
            : selectedFolderId
        newConnectionInitialFolderId = targetFolder
        newConnectionInitialProtocol = defaultProtocol
        isPresentingNewConnectionSheet = true
    }

    public func presentNewFolderSheet() {
        let parentFolder = selectedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId
            ? nil
            : selectedFolderId
        newFolderInitialParentId = parentFolder
        isPresentingNewFolderSheet = true
    }

    public func toggleQuickConnect() {
        isQuickConnectPresented.toggle()
    }

    public func selectSection(_ section: WorkspacePrimarySection) {
        isFullScreenSessionMode = false
        switch section {
        case .sessions:
            activeSection = .sessions
        case .settings, .overview:
            activeSection = .settings
            activeSettingsTab = .general
        case .cloudSync:
            activeSection = .settings
            activeSettingsTab = .cloudSync
        case .credentials:
            activeSection = .settings
            activeSettingsTab = .credentials
        case .gateways:
            activeSection = .settings
            activeSettingsTab = .gateways
        case .templates:
            activeSection = .settings
            activeSettingsTab = .templates
        case .automationTasks:
            activeSection = .settings
            activeSettingsTab = .tasks
        }
    }

    public func openUnifiedSettings(tab: UnifiedSettingsTab? = nil) {
        isFullScreenSessionMode = false
        if let tab {
            activeSettingsTab = tab
        }
        activeSection = .settings
    }

    public func toggleUnifiedSettings() {
        isFullScreenSessionMode = false
        if activeSection == .sessions {
            activeSection = .settings
        } else {
            activeSection = .sessions
        }
    }

    public func toggleInspector() {
        if isFullScreenSessionMode {
            isFullScreenSessionMode = false
        }
        isInspectorPresented.toggle()
    }

    public func toggleFullScreenSessionMode() {
        if isFullScreenSessionMode {
            exitFullScreenSession()
        } else if !paneTree.allTabs.isEmpty {
            enterFullScreenSession()
        }
    }

    public func executeShortcut(_ shortcut: MacWorkspaceShortcut) {
        guard !isVaultLocked else { return }
        switch shortcut {
        case .quickConnect:
            toggleQuickConnect()
        case .localTerminal:
            openLocalTerminal()
        case .newConnection:
            presentNewConnectionSheet()
        case .newFolder:
            presentNewFolderSheet()
        case .splitRight:
            splitFocusedPane(.horizontal)
        case .splitDown:
            splitFocusedPane(.vertical)
        case .broadcastInput:
            broadcastInputEnabled.toggle()
        case .toggleSidebar:
            toggleSidebarVisibility()
        case .toggleInspector:
            toggleInspector()
        case .toggleAuditConsole:
            isBottomConsolePresented.toggle()
        case .fullScreenSession:
            toggleFullScreenSessionMode()
        case .syncNow:
            Task { await syncCloudNow(reportErrors: true) }
        case .lockVault:
            lockVault()
        }
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

    /// Switches the main workspace to an already-open session tab for `connId` when active;
    /// if `connId` has no active session, leaves the main workspace untouched.
    @discardableResult
    public func focusActiveSessionIfOpen(forConnectionId connId: String) -> Bool {
        selectedConnectionId = connId
        selectedCredentialId = nil
        let (updatedTree, activatedTabId, targetPaneId) = paneTree.activatingConnectionIfPresent(
            connId,
            currentFocusedPaneId: focusedPaneId
        )
        if activatedTabId != nil {
            paneTree = updatedTree
            if let targetPaneId {
                focusedPaneId = targetPaneId
            }
            if activeSection != .sessions {
                activeSection = .sessions
            }
            return true
        }
        if let detachedTab = detachedWindowTabs.values.first(where: { !$0.isLocalShell && $0.connection.id == connId }) {
            if let win = NSApp.windows.first(where: { $0.isVisible && $0.title.contains(detachedTab.title) }) {
                win.makeKeyAndOrderFront(nil)
            }
            return true
        }
        return false
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
        isFullScreenSessionMode = false
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
        isFullScreenSessionMode = false
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
        isFullScreenSessionMode = false
        activeSection = .sessions
        guard let targetLeaf = focusedPaneId ?? paneTree.allLeafIds.first else { return }
        if paneTree.allTabs.isEmpty {
            let firstShell = WorkspaceSessionTab.makeLocalZshTab()
            paneTree = paneTree.insertingTab(firstShell, into: targetLeaf)
        }
        let secondShell = WorkspaceSessionTab.makeLocalZshTab()
        let (updated, newLeafId) = paneTree.splittingPane(targetLeaf, orientation: orientation, newTab: secondShell)
        paneTree = updated
        focusedPaneId = newLeafId
    }

    public func closeTab(_ tabId: UUID, destroyRuntime: Bool = true) {
        paneTree = paneTree.removingTab(tabId)
        detachedWindowTabs.removeValue(forKey: tabId)
        if destroyRuntime {
            terminalControllers.removeValue(forKey: tabId)?.shutdown()
            if let oldRdp = rdpSessions.removeValue(forKey: tabId) {
                Task.detached(priority: .utility) {
                    oldRdp.destroy()
                }
            }
            transferControllers.removeValue(forKey: tabId)?.shutdown()
            vncControllers.removeValue(forKey: tabId)?.shutdown()
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

    public func activeTabs(forConnectionId connId: String) -> [WorkspaceSessionTab] {
        let docked = paneTree.allTabs.filter { !$0.isLocalShell && $0.connection.id == connId }
        let detached = detachedWindowTabs.values.filter { !$0.isLocalShell && $0.connection.id == connId }
        return docked + detached
    }

    public func activeSessionCount(forConnectionId connId: String) -> Int {
        activeTabs(forConnectionId: connId).count
    }

    public func hasActiveRdpSession(forConnectionId connId: String) -> Bool {
        activeTabs(forConnectionId: connId).contains {
            $0.connection.protocolType == .RDP || $0.connection.protocolType == .WINDOWS_TERMINAL_SERVICES
        }
    }

    public func disconnectConnection(_ connId: String) {
        let tabs = activeTabs(forConnectionId: connId)
        guard !tabs.isEmpty else { return }
        let connName = tabs.first?.title ?? connId
        for tab in tabs {
            closeTab(tab.id, destroyRuntime: true)
        }
        appendAudit("Session", "Disconnected \(tabs.count) session(s) for '\(connName)'.")
    }

    public func logOffTabSession(_ tabId: UUID) {
        let tab = paneTree.findTab(id: tabId) ?? detachedWindowTabs[tabId]
        let isRdp = tab.map {
            !$0.isLocalShell && ($0.connection.protocolType == .RDP || $0.connection.protocolType == .WINDOWS_TERMINAL_SERVICES)
        } ?? false

        guard isRdp, let rdp = rdpSessions[tabId], rdp.isConnected else {
            closeTab(tabId, destroyRuntime: true)
            return
        }

        let title = tab?.title ?? "RDP Session"
        rdp.logOffRemoteSession()
        appendAudit("Session", "Sent Windows session logoff to '\(title)'.")

        Task { @MainActor [weak self] in
            for _ in 0..<14 {
                if !rdp.isConnected {
                    break
                }
                try? await Task.sleep(nanoseconds: 150_000_000)
            }
            guard let self else { return }
            if self.paneTree.findTab(id: tabId) != nil || self.detachedWindowTabs[tabId] != nil {
                self.closeTab(tabId, destroyRuntime: true)
            }
        }
    }

    public func logOffConnectionSessions(_ connId: String) {
        let tabs = activeTabs(forConnectionId: connId)
        for tab in tabs {
            if tab.connection.protocolType == .RDP || tab.connection.protocolType == .WINDOWS_TERMINAL_SERVICES {
                logOffTabSession(tab.id)
            } else {
                closeTab(tab.id, destroyRuntime: true)
            }
        }
    }

    private func resolveEffectiveCredential(
        for tab: WorkspaceSessionTab,
        resolved: EffectiveConnectionProfile
    ) -> VaultCredential? {
        var effectiveCred = resolved.effectiveCredential
        if tab.adHocUsernameOverride != nil || tab.adHocPasswordOverride != nil || tab.adHocDomainOverride != nil {
            var copy = effectiveCred ?? VaultCredential(name: "Ad-Hoc")
            if let u = tab.adHocUsernameOverride, !u.isEmpty { copy.username = u }
            if let p = tab.adHocPasswordOverride, !p.isEmpty { copy.secretPassword = p }
            if let d = tab.adHocDomainOverride, !d.isEmpty { copy.domain = d }
            effectiveCred = copy
        }
        return effectiveCred
    }

    private func resolveGatewayBastionPayload(
        resolved: EffectiveConnectionProfile,
        fallbackUser: String
    ) -> SshFfiConnectPayload? {
        guard let gw = resolved.effectiveGateway,
              gw.kind == .SSH_TUNNEL_BASTION,
              !gw.host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        let gwCred = gw.credentialId.flatMap { id in
            activeCredentials.first(where: { $0.id == id })
        }
        let user = (gwCred?.username.isEmpty == false) ? gwCred!.username : fallbackUser
        let auth: SshFfiConnectPayload.Auth = {
            if let pem = gwCred?.privateKeyPem, !pem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                let pass = (gwCred?.keyPassphrase.isEmpty == true) ? nil : gwCred?.keyPassphrase
                return .privateKeyPem(pem: pem, passphrase: pass)
            }
            return .password(gwCred?.secretPassword ?? "")
        }()
        return SshFfiConnectPayload(
            host: gw.host,
            port: UInt16(clamping: gw.port > 0 ? gw.port : 22),
            username: user,
            auth: auth
        )
    }

    public func terminalController(for tab: WorkspaceSessionTab) -> LiveTerminalController {
        if let existing = terminalControllers[tab.id] {
            return existing
        }
        let latestConnection = activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
        let resolved = PropertyInheritanceResolver.resolve(connection: latestConnection, in: document)
        let effectiveCred = resolveEffectiveCredential(for: tab, resolved: resolved)

        var bastionConn: VaultConnection? = nil
        var bastionCred: VaultCredential? = nil
        if case .ssh(let sshCfg) = latestConnection.settings,
           let bastionId = sshCfg.bastionJumpConnectionId,
           let foundBastion = activeConnections.first(where: { $0.id == bastionId }) {
            bastionConn = foundBastion
            bastionCred = PropertyInheritanceResolver.resolve(connection: foundBastion, in: document).effectiveCredential
        }
        let gatewayBastion = bastionConn == nil
            ? resolveGatewayBastionPayload(resolved: resolved, fallbackUser: effectiveCred?.username ?? NSUserName())
            : nil

        let created = LiveTerminalController(
            tabId: tab.id,
            connection: latestConnection,
            isLocalShell: tab.isLocalShell,
            effectiveCredential: effectiveCred,
            bastionConnection: bastionConn,
            bastionCredential: bastionCred,
            gatewayBastionSpec: gatewayBastion
        )
        terminalControllers[tab.id] = created
        return created
    }

    public func existingRdpController(for tab: WorkspaceSessionTab) -> NativeRdpSession? {
        rdpSessions[tab.id]
    }

    public func rdpController(
        for tab: WorkspaceSessionTab,
        initialViewportSize: CGSize? = nil,
        backingScaleFactor: CGFloat? = nil,
        useRetinaOverride: Bool? = nil
    ) -> NativeRdpSession {
        if let existing = rdpSessions[tab.id] {
            return existing
        }
        return buildAndConnectRdpSession(
            for: tab,
            initialViewportSize: initialViewportSize,
            backingScaleFactor: backingScaleFactor,
            useRetinaOverride: useRetinaOverride
        )
    }

    @discardableResult
    public func reconnectRdpSession(
        for tab: WorkspaceSessionTab,
        initialViewportSize: CGSize? = nil,
        backingScaleFactor: CGFloat? = nil,
        useRetinaOverride: Bool? = nil
    ) -> NativeRdpSession {
        if let old = rdpSessions.removeValue(forKey: tab.id) {
            Task.detached(priority: .utility) {
                old.destroy()
            }
        }
        return buildAndConnectRdpSession(
            for: tab,
            initialViewportSize: initialViewportSize,
            backingScaleFactor: backingScaleFactor,
            useRetinaOverride: useRetinaOverride
        )
    }

    private func buildAndConnectRdpSession(
        for tab: WorkspaceSessionTab,
        initialViewportSize: CGSize? = nil,
        backingScaleFactor: CGFloat? = nil,
        useRetinaOverride: Bool? = nil
    ) -> NativeRdpSession {
        let liveTab = paneTree.findTab(id: tab.id) ?? detachedWindowTabs[tab.id] ?? tab
        let latestConnection = activeConnections.first(where: { $0.id == liveTab.connection.id }) ?? liveTab.connection
        let resolved = PropertyInheritanceResolver.resolve(connection: latestConnection, in: document)
        let rdpSettings: RdpProtocolSettings = {
            if case .rdp(let s) = latestConnection.settings { return s }
            return RdpProtocolSettings()
        }()
        let user = liveTab.adHocUsernameOverride ?? resolved.effectiveCredential?.username ?? "Administrator"
        let pass = liveTab.adHocPasswordOverride ?? resolved.effectiveCredential?.secretPassword ?? ""
        let dom = liveTab.adHocDomainOverride ?? resolved.effectiveCredential?.domain ?? ""

        let effectiveBackingScale = max(1.0, backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 2.0)
        let defaultRetina = rdpSettings.hiDpiEnabled && (effectiveBackingScale >= 1.5 || rdpSettings.desktopScaleFactorPercent > 100)
        let useRetina = useRetinaOverride ?? defaultRetina

        var effectiveSettings = rdpSettings
        if !useRetina {
            effectiveSettings.hiDpiEnabled = false
        }

        let h264Cap = NativeRdpSession.usesH264Cap(settings: effectiveSettings)
        let (initWidth, initHeight): (Int, Int) = {
            guard rdpSettings.resizeBehavior == .DYNAMIC_DISP_DVC else {
                return NativeRdpSession.clampSurfacePixelDimensions(
                    width: rdpSettings.customDesktopWidth,
                    height: rdpSettings.customDesktopHeight,
                    h264Enabled: h264Cap
                )
            }
            let viewportPoints: CGSize = {
                if let vp = initialViewportSize, vp.width >= 320, vp.height >= 240 {
                    return vp
                }
                if let winRect = NSApp.keyWindow?.contentLayoutRect.size, winRect.width >= 480, winRect.height >= 360 {
                    let sidebarDeduction: CGFloat = (!isFullScreenSessionMode && columnVisibility != .detailOnly) ? 260.0 : 0.0
                    let inspectorDeduction: CGFloat = (!isFullScreenSessionMode && isInspectorPresented) ? 280.0 : 0.0
                    let estW = max(640.0, winRect.width - sidebarDeduction - inspectorDeduction)
                    let estH = max(480.0, winRect.height - 72.0)
                    return CGSize(width: estW, height: estH)
                }
                return CGSize(width: 1280.0, height: 800.0)
            }()
            let factor = useRetina ? effectiveBackingScale : 1.0
            let rawW = max(640, Int((viewportPoints.width * factor).rounded()))
            let rawH = max(480, Int((viewportPoints.height * factor).rounded()))
            return NativeRdpSession.clampSurfacePixelDimensions(
                width: rawW,
                height: rawH,
                h264Enabled: h264Cap
            )
        }()

        let session = NativeRdpSession(
            host: latestConnection.host,
            username: user,
            password: pass,
            domain: dom,
            settings: effectiveSettings,
            initialWidth: initWidth,
            initialHeight: initHeight,
            backingScaleFactor: Double(effectiveBackingScale)
        )
        if effectiveSettings.redirectClipboard {
            switch MacPasteboardClipboardExtractor.extractClipboardPayload(from: .general) {
            case .files(let initialPaths):
                session.pushLocalClipboardFiles(initialPaths)
            case .text(let initialClip):
                session.pushLocalClipboard(initialClip)
            case .fileIntentEmpty, .empty:
                break
            }
        }
        session.setConnecting(true)
        rdpSessions[tab.id] = session
        Task.detached(priority: .userInitiated) {
            _ = session.connect()
        }
        return session
    }

    public func transferController(for tab: WorkspaceSessionTab) -> LiveTransferController {
        if let existing = transferControllers[tab.id] {
            return existing
        }
        let latestConnection = activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
        let resolved = PropertyInheritanceResolver.resolve(connection: latestConnection, in: document)
        let effectiveCred = resolveEffectiveCredential(for: tab, resolved: resolved)
        let bastionSpec = resolveGatewayBastionPayload(
            resolved: resolved,
            fallbackUser: effectiveCred?.username ?? NSUserName()
        )
        let created = LiveTransferController(
            tabId: tab.id,
            connection: latestConnection,
            credential: effectiveCred,
            bastionSpec: bastionSpec
        )
        transferControllers[tab.id] = created
        return created
    }

    public func reconnectTransferController(for tab: WorkspaceSessionTab) {
        let latestConnection = activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
        let resolved = PropertyInheritanceResolver.resolve(connection: latestConnection, in: document)
        let effectiveCred = resolveEffectiveCredential(for: tab, resolved: resolved)
        let bastionSpec = resolveGatewayBastionPayload(
            resolved: resolved,
            fallbackUser: effectiveCred?.username ?? NSUserName()
        )
        if let existing = transferControllers[tab.id] {
            existing.connect(connection: latestConnection, credential: effectiveCred, bastionSpec: bastionSpec)
        } else {
            _ = transferController(for: tab)
        }
    }

    public func vncController(for tab: WorkspaceSessionTab) -> LiveVncController {
        if let existing = vncControllers[tab.id] {
            return existing
        }
        let latestConnection = activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
        let resolved = PropertyInheritanceResolver.resolve(connection: latestConnection, in: document)
        let effectiveCred = resolveEffectiveCredential(for: tab, resolved: resolved)
        let created = LiveVncController(
            tabId: tab.id,
            connection: latestConnection,
            credential: effectiveCred
        )
        vncControllers[tab.id] = created
        return created
    }

    public func reconnectVncController(for tab: WorkspaceSessionTab) {
        let latestConnection = activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
        let resolved = PropertyInheritanceResolver.resolve(connection: latestConnection, in: document)
        let effectiveCred = resolveEffectiveCredential(for: tab, resolved: resolved)
        if let existing = vncControllers[tab.id] {
            existing.connect(connection: latestConnection, credential: effectiveCred)
        } else {
            _ = vncController(for: tab)
        }
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

    public func setRdpHyperVEnhancedSession(
        forTab tabId: UUID,
        connectionId: String,
        enabled: Bool
    ) {
        if document.connections.contains(where: { $0.id == connectionId }) {
            commitMutation(enabled ? "Enabled Hyper-V Enhanced Session Mode" : "Disabled Hyper-V Enhanced Session Mode") { doc in
                guard let idx = doc.connections.firstIndex(where: { $0.id == connectionId }),
                      case .rdp(var rdp) = doc.connections[idx].settings else { return }
                rdp.hyperVEnhancedSession = enabled
                rdp.hyperVEnhancedSessionCustomized = true
                if enabled {
                    rdp.redirectClipboard = true
                    rdp.redirectClipboardCustomized = true
                }
                doc.connections[idx].settings = .rdp(rdp)
                doc.connections[idx].updatedAtEpochMs = VaultClock.nowMs()
            }
        }
        paneTree = paneTree.updatingTab(tabId) { tabItem in
            if case .rdp(var rdp) = tabItem.connection.settings {
                rdp.hyperVEnhancedSession = enabled
                rdp.hyperVEnhancedSessionCustomized = true
                if enabled {
                    rdp.redirectClipboard = true
                    rdp.redirectClipboardCustomized = true
                }
                tabItem.connection.settings = .rdp(rdp)
            }
        }
        if var detached = detachedWindowTabs[tabId],
           case .rdp(var rdp) = detached.connection.settings {
            rdp.hyperVEnhancedSession = enabled
            rdp.hyperVEnhancedSessionCustomized = true
            if enabled {
                rdp.redirectClipboard = true
                rdp.redirectClipboardCustomized = true
            }
            detached.connection.settings = .rdp(rdp)
            detachedWindowTabs[tabId] = detached
        }
    }

    public func deleteConnection(_ connectionId: String) {
        let tabsToClose = (paneTree.allTabs + Array(detachedWindowTabs.values))
            .filter { $0.connection.id == connectionId }
            .map(\.id)
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
            shutdownAllLiveRuntimes()
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
        autoSyncOnSave = true
        installCloudFileWatcher()
        Task { await syncCloudNow(reportErrors: true) }
    }

    public func unlinkCloudVault() {
        teardownCloudFileWatcher()
        cloudDebouncedSyncTask?.cancel()
        cloudBookmarkBridge.unlinkVaultFile()
        linkedCloudFileURL = nil
        lastCloudSyncDate = nil
        syncStatusSummary = "Not connected"
        appendAudit("Cloud Sync", "Disconnected cloud vault synchronization.")
    }

    public func scheduleDebouncedCloudSync(delayNanoseconds: UInt64 = 1_100_000_000) {
        guard !isVaultLocked, hasConfiguredCloudSync else { return }
        cloudDebouncedSyncTask?.cancel()
        cloudDebouncedSyncTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: delayNanoseconds)
            guard !Task.isCancelled, let self, !self.isVaultLocked else { return }
            await self.syncCloudNow(reportErrors: false)
        }
    }

    public func installCloudFileWatcher() {
        teardownCloudFileWatcher()
        guard !CommandLine.arguments.contains("--self-test"),
              let url = linkedCloudFileURL ?? cloudBookmarkBridge.resolveLinkedURL() else {
            return
        }

        let dirPath = url.deletingLastPathComponent().path
        let dirFd = Darwin.open(dirPath, O_EVTONLY)
        if dirFd >= 0 {
            watchedDirFd = dirFd
            let dirSrc = DispatchSource.makeFileSystemObjectSource(
                fileDescriptor: dirFd,
                eventMask: [.write, .rename],
                queue: .main
            )
            let capturedDirFd = dirFd
            dirSrc.setEventHandler { [weak self] in
                MainActor.assumeIsolated {
                    guard let self, !self.isVaultLocked else { return }
                    if self.watchedFileSource == nil {
                        self.armWatchedFileSourceIfPresent(for: url)
                    }
                    if self.cloudBookmarkBridge.hasExternalChangeOnDisk(explicitURL: url) {
                        self.scheduleDebouncedCloudSync(delayNanoseconds: 900_000_000)
                    }
                }
            }
            dirSrc.setCancelHandler {
                Darwin.close(capturedDirFd)
            }
            watchedDirSource = dirSrc
            dirSrc.resume()
        }

        armWatchedFileSourceIfPresent(for: url)
    }

    private func armWatchedFileSourceIfPresent(for url: URL) {
        if let existing = watchedFileSource {
            watchedFileSource = nil
            watchedFileFd = -1
            existing.cancel()
        }
        let fileFd = Darwin.open(url.path, O_EVTONLY)
        guard fileFd >= 0 else { return }
        watchedFileFd = fileFd
        let fileSrc = DispatchSource.makeFileSystemObjectSource(
            fileDescriptor: fileFd,
            eventMask: [.write, .extend, .attrib, .rename, .delete],
            queue: .main
        )
        let capturedFileFd = fileFd
        fileSrc.setEventHandler { [weak self, weak fileSrc] in
            let flags = fileSrc?.data ?? []
            MainActor.assumeIsolated {
                guard let self, !self.isVaultLocked else { return }
                if flags.contains(.delete) || flags.contains(.rename) {
                    // Cloud providers (Google Drive / iCloud) write via atomic temp-file rename; re-arm on the new inode
                    Task { @MainActor [weak self] in
                        try? await Task.sleep(nanoseconds: 500_000_000)
                        guard let self, !self.isVaultLocked else { return }
                        self.armWatchedFileSourceIfPresent(for: url)
                        if self.cloudBookmarkBridge.hasExternalChangeOnDisk(explicitURL: url) {
                            await self.syncCloudNow(reportErrors: false)
                        }
                    }
                } else if self.cloudBookmarkBridge.hasExternalChangeOnDisk(explicitURL: url) {
                    self.scheduleDebouncedCloudSync(delayNanoseconds: 850_000_000)
                }
            }
        }
        fileSrc.setCancelHandler {
            Darwin.close(capturedFileFd)
        }
        watchedFileSource = fileSrc
        fileSrc.resume()
    }

    public func teardownCloudFileWatcher() {
        if let fSrc = watchedFileSource {
            watchedFileSource = nil
            watchedFileFd = -1
            fSrc.cancel()
        } else if watchedFileFd >= 0 {
            Darwin.close(watchedFileFd)
            watchedFileFd = -1
        }
        if let dSrc = watchedDirSource {
            watchedDirSource = nil
            watchedDirFd = -1
            dSrc.cancel()
        } else if watchedDirFd >= 0 {
            Darwin.close(watchedDirFd)
            watchedDirFd = -1
        }
    }

    private func startCloudChangeWatcherLoop() {
        guard !CommandLine.arguments.contains("--self-test") else { return }
        cloudWatcherLoopTask?.cancel()
        cloudWatcherLoopTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 12_000_000_000)
                guard !Task.isCancelled, let self else { break }
                guard !self.isVaultLocked, !self.isSyncingCloud else { continue }
                if let url = self.linkedCloudFileURL ?? self.cloudBookmarkBridge.resolveLinkedURL(),
                   self.cloudBookmarkBridge.hasExternalChangeOnDisk(explicitURL: url) {
                    await self.syncCloudNow(reportErrors: false)
                }
            }
        }
    }

    public func syncCloudNow(reportErrors: Bool = false) async {
        guard !isVaultLocked else { return }
        if isSyncingCloud {
            pendingFollowUpCloudSync = true
            return
        }
        isSyncingCloud = true
        defer {
            isSyncingCloud = false
            if pendingFollowUpCloudSync {
                pendingFollowUpCloudSync = false
                scheduleDebouncedCloudSync(delayNanoseconds: 600_000_000)
            }
        }

        if let url = linkedCloudFileURL ?? cloudBookmarkBridge.resolveLinkedURL() {
            do {
                let starterFlag = isStarterVaultUninitialized
                let bridge = cloudBookmarkBridge
                let docSnapshot = document
                let passSnapshot = activePassphrase
                let res = try await Task.detached(priority: .utility) {
                    try bridge.syncWithLinkedFile(
                        localDocument: docSnapshot,
                        passphrase: passSnapshot,
                        explicitURL: url,
                        kdf: .interactiveFast,
                        localIsStarterVault: starterFlag
                    )
                }.value
                let sanitized = VaultHierarchySanitizer.sanitizeVaultDocument(res.document)
                let docChanged = (sanitized != self.document)
                self.document = sanitized
                self.isStarterVaultUninitialized = false
                self.linkedCloudFileURL = res.syncedFileURL
                self.lastCloudSyncDate = Date()
                if docChanged || res.action == .pulledRemote || res.action == .deltaMerged {
                    persistLocalVault(triggerCloudSync: false)
                }
                if watchedFileSource == nil {
                    installCloudFileWatcher()
                }
                switch res.action {
                case .alreadyUpToDate:
                    self.syncStatusSummary = "Up to date with \(res.syncedFileURL.lastPathComponent)"
                case .pushedLocal:
                    self.syncStatusSummary = "Synced changes to \(res.syncedFileURL.lastPathComponent)"
                case .pulledRemote:
                    self.syncStatusSummary = "Updated from \(res.syncedFileURL.lastPathComponent)"
                case .deltaMerged:
                    self.syncStatusSummary = "Merged changes with \(res.syncedFileURL.lastPathComponent)"
                }
                if res.action != .alreadyUpToDate {
                    appendAudit("Cloud Sync", "\(res.action.rawValue) via \(res.syncedFileURL.path) (\(res.conflictCount) conflicts resolved).")
                }
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
                self.isStarterVaultUninitialized = false
                self.lastCloudSyncDate = Date()
                self.syncStatusSummary = "Synced with Google Drive"
                persistLocalVault(triggerCloudSync: false)
                appendAudit("Google Drive", "\(res.action.rawValue) (\(res.conflicts) conflicts merged).")
            } catch {
                syncStatusSummary = "Drive sync error"
                appendAudit("Google Drive", error.localizedDescription)
                if reportErrors {
                    reportError(error, operationTitle: "Google Drive Sync Failed")
                }
            }
        }
    }

    public func checkGitHubUpdates(manual: Bool = false) async {
        guard !updateInstallPhase.isBusy else { return }
        if manual {
            updateInstallPhase = .checking
        }
        do {
            if let candidate = try await MacGitHubReleaseUpdater.checkForUpdates() {
                let isNew = self.updateCandidate?.tagName != candidate.tagName
                self.updateCandidate = candidate
                self.updateInstallPhase = .idle
                if isNew || manual {
                    appendAudit("Updater", "New release available on GitHub: \(candidate.tagName).")
                }
            } else {
                self.updateCandidate = nil
                if manual {
                    self.updateInstallPhase = .upToDate
                } else if case .checking = self.updateInstallPhase {
                    self.updateInstallPhase = .idle
                }
            }
        } catch {
            if manual {
                self.updateInstallPhase = .failed(error.localizedDescription)
            } else {
                self.updateInstallPhase = .idle
            }
        }
    }

    public func installAvailableUpdateAndRelaunch() async {
        guard let candidate = updateCandidate, !updateInstallPhase.isBusy else { return }
        do {
            let (stagedAppURL, stagingRootURL) = try await MacGitHubReleaseUpdater.downloadVerifyAndStageUpdate(
                candidate: candidate
            ) { [weak self] phase in
                Task { @MainActor in
                    self?.updateInstallPhase = phase
                }
            }

            updateInstallPhase = .applyingAndRelaunching
            appendAudit("Updater", "Applying NexusTS \(candidate.tagName) in place and relaunching…")
            persistLocalVault()
            shutdownAllLiveRuntimes()

            try MacGitHubReleaseUpdater.launchAtomicSwapHelperAndRelaunch(
                stagedAppURL: stagedAppURL,
                stagingRootURL: stagingRootURL
            )

            NSApplication.shared.terminate(nil)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                Darwin.exit(0)
            }
        } catch {
            updateInstallPhase = .failed(error.localizedDescription)
            reportError(error, operationTitle: "In-App Update Failed")
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
