import AppKit
import Foundation
import NexusTSCore
import SwiftUI

public struct LocalFileEntry: Identifiable, Hashable {
    public var id: String { url.path }
    public let name: String
    public let url: URL
    public let isDirectory: Bool
    public let sizeBytes: UInt64
    public let modifiedDate: Date
}

public struct DualPaneTransferView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let tab: WorkspaceSessionTab

    @State private var localDirectoryURL: URL = FileManager.default.homeDirectoryForCurrentUser
    @State private var localEntries: [LocalFileEntry] = []
    @State private var selectedLocalId: LocalFileEntry.ID? = nil

    @State private var remotePath: String = "/"
    @State private var remoteEntries: [SftpRemoteEntry] = []
    @State private var selectedRemoteId: SftpRemoteEntry.ID? = nil

    @State private var sshTransport: NativeSshSession? = nil
    @State private var sftpSession: NativeSftpSession? = nil
    @State private var isConnected: Bool = false
    @State private var isBusy: Bool = false
    @State private var showHiddenFiles: Bool = false
    @State private var transferStatusText: String = "Idle"
    @State private var newFolderName: String = ""
    @State private var isCreatingRemoteFolder: Bool = false

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    private var transferSettings: FileTransferProtocolSettings {
        if case .fileTransfer(let s) = tab.connection.settings { return s }
        return FileTransferProtocolSettings(port: tab.connection.protocolType.defaultPort)
    }

    public var body: some View {
        VStack(spacing: 0) {
            // Unified Commander Action Bar
            HStack(spacing: 10) {
                Circle()
                    .fill(isConnected ? Color(nsColor: .systemGreen) : Color(nsColor: .systemOrange))
                    .frame(width: 8, height: 8)

                Text("\(tab.connection.protocolType.shortBadge) Commander • \(tab.connection.host):\(transferSettings.port)")
                    .font(.callout.weight(.semibold))

                Spacer()

                Toggle("Show Dotfiles", isOn: $showHiddenFiles)
                    .toggleStyle(.checkbox)
                    .font(.caption)
                    .onChange(of: showHiddenFiles) { _, _ in
                        reloadLocalDirectory()
                    }

                Button {
                    uploadSelectedLocalFile()
                } label: {
                    Label("Upload →", systemImage: "arrow.right.square.fill")
                }
                .controlSize(.small)
                .disabled(!isConnected || selectedLocalId == nil || isBusy)

                Button {
                    downloadSelectedRemoteFile()
                } label: {
                    Label("← Download", systemImage: "arrow.left.square.fill")
                }
                .controlSize(.small)
                .disabled(!isConnected || selectedRemoteId == nil || isBusy)

                Button {
                    connectAndRefreshRemote()
                } label: {
                    Label(isConnected ? "Refresh" : "Connect", systemImage: "arrow.clockwise")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.bar)

            Divider()

            HSplitView {
                // LEFT PANE: Local macOS Finder Volume
                VStack(spacing: 0) {
                    HStack(spacing: 6) {
                        Image(systemName: "laptopcomputer")
                            .foregroundStyle(.secondary)
                        Button {
                            localDirectoryURL = localDirectoryURL.deletingLastPathComponent()
                            reloadLocalDirectory()
                        } label: {
                            Image(systemName: "chevron.up")
                        }
                        .buttonStyle(.borderless)
                        .help("Parent Directory")

                        TextField("Local Path", text: Binding(
                            get: { localDirectoryURL.path },
                            set: { localDirectoryURL = URL(fileURLWithPath: $0) }
                        ))
                        .textFieldStyle(.roundedBorder)
                        .font(.system(size: 12, design: .monospaced))
                        .onSubmit {
                            reloadLocalDirectory()
                        }

                        Button {
                            NSWorkspace.shared.activateFileViewerSelecting([localDirectoryURL])
                        } label: {
                            Image(systemName: "folder")
                        }
                        .buttonStyle(.borderless)
                        .help("Reveal in macOS Finder")
                    }
                    .padding(8)
                    .background(Color(nsColor: .windowBackgroundColor))

                    Divider()

                    Table(localEntries, selection: $selectedLocalId) {
                        TableColumn("Name") { item in
                            HStack(spacing: 6) {
                                Image(systemName: item.isDirectory ? "folder.fill" : "doc")
                                    .foregroundStyle(item.isDirectory ? Color.accentColor : .secondary)
                                Text(item.name)
                                    .lineLimit(1)
                            }
                            .onTapGesture(count: 2) {
                                if item.isDirectory {
                                    localDirectoryURL = item.url
                                    reloadLocalDirectory()
                                }
                            }
                        }
                        TableColumn("Size") { item in
                            Text(item.isDirectory ? "—" : ByteCountFormatter.string(fromByteCount: Int64(item.sizeBytes), countStyle: .file))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        .width(85)
                    }
                }
                .frame(minWidth: 260)

                // RIGHT PANE: Remote SFTP v3 / FTP Server
                VStack(spacing: 0) {
                    HStack(spacing: 6) {
                        Image(systemName: "server.rack")
                            .foregroundStyle(Color(nsColor: .systemBlue))
                        Button {
                            let parent = (remotePath as NSString).deletingLastPathComponent
                            remotePath = parent.isEmpty ? "/" : parent
                            refreshRemoteDirectory()
                        } label: {
                            Image(systemName: "chevron.up")
                        }
                        .buttonStyle(.borderless)

                        TextField("Remote Path", text: $remotePath)
                            .textFieldStyle(.roundedBorder)
                            .font(.system(size: 12, design: .monospaced))
                            .onSubmit {
                                refreshRemoteDirectory()
                            }

                        Button {
                            isCreatingRemoteFolder = true
                        } label: {
                            Image(systemName: "folder.badge.plus")
                        }
                        .buttonStyle(.borderless)
                        .disabled(!isConnected)
                        .popover(isPresented: $isCreatingRemoteFolder) {
                            HStack(spacing: 8) {
                                TextField("Folder name", text: $newFolderName)
                                    .textFieldStyle(.roundedBorder)
                                    .frame(width: 150)
                                Button("Create") {
                                    createRemoteDirectory()
                                }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.small)
                            }
                            .padding(10)
                        }

                        Button(role: .destructive) {
                            deleteSelectedRemoteEntry()
                        } label: {
                            Image(systemName: "trash")
                        }
                        .buttonStyle(.borderless)
                        .disabled(!isConnected || selectedRemoteId == nil)
                    }
                    .padding(8)
                    .background(Color(nsColor: .windowBackgroundColor))

                    Divider()

                    Table(filteredRemoteEntries, selection: $selectedRemoteId) {
                        TableColumn("Remote Name") { entry in
                            HStack(spacing: 6) {
                                Image(systemName: entry.isDirectory ? "folder.fill" : (entry.isSymlink ? "link" : "doc.text"))
                                    .foregroundStyle(entry.isDirectory ? Color(nsColor: .systemBlue) : .secondary)
                                Text(entry.name)
                                    .lineLimit(1)
                            }
                            .onTapGesture(count: 2) {
                                if entry.isDirectory {
                                    remotePath = entry.fullPath
                                    refreshRemoteDirectory()
                                }
                            }
                        }
                        TableColumn("Permissions") { entry in
                            Text(entry.rwxPermissions)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(.secondary)
                        }
                        .width(92)
                        TableColumn("Size") { entry in
                            Text(entry.isDirectory ? "—" : ByteCountFormatter.string(fromByteCount: Int64(entry.sizeBytes), countStyle: .file))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        .width(85)
                    }
                }
                .frame(minWidth: 280)
            }

            Divider()

            HStack {
                if isBusy {
                    ProgressView()
                        .controlSize(.small)
                }
                Text(transferStatusText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Text("\(localEntries.count) local • \(filteredRemoteEntries.count) remote")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 5)
            .background(.bar)
        }
        .onAppear {
            showHiddenFiles = transferSettings.showHiddenDotfiles
            if !transferSettings.initialLocalDirectory.isEmpty {
                localDirectoryURL = URL(fileURLWithPath: transferSettings.initialLocalDirectory)
            }
            if !transferSettings.initialRemoteDirectory.isEmpty {
                remotePath = transferSettings.initialRemoteDirectory
            }
            reloadLocalDirectory()
            connectAndRefreshRemote()
        }
        .onDisappear {
            sftpSession?.close()
            sshTransport?.disconnect()
        }
    }

    private var filteredRemoteEntries: [SftpRemoteEntry] {
        remoteEntries
            .filter { showHiddenFiles || !$0.name.hasPrefix(".") }
            .sorted {
                if $0.isDirectory != $1.isDirectory { return $0.isDirectory && !$1.isDirectory }
                return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
    }

    private func reloadLocalDirectory() {
        let fm = FileManager.default
        let options: FileManager.DirectoryEnumerationOptions = showHiddenFiles ? [] : [.skipsHiddenFiles]
        guard let urls = try? fm.contentsOfDirectory(
            at: localDirectoryURL,
            includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey],
            options: options
        ) else {
            localEntries = []
            return
        }
        localEntries = urls.compactMap { url in
            let vals = try? url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey])
            return LocalFileEntry(
                name: url.lastPathComponent,
                url: url,
                isDirectory: vals?.isDirectory ?? false,
                sizeBytes: UInt64(max(0, vals?.fileSize ?? 0)),
                modifiedDate: vals?.contentModificationDate ?? Date()
            )
        }
        .sorted {
            if $0.isDirectory != $1.isDirectory { return $0.isDirectory && !$1.isDirectory }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    private func connectAndRefreshRemote() {
        guard tab.connection.protocolType == .SFTP || tab.connection.protocolType == .SCP else {
            transferStatusText = "\(tab.connection.protocolType.displayName) ready for \(tab.connection.host):\(transferSettings.port)"
            return
        }
        if isConnected, sftpSession != nil {
            refreshRemoteDirectory()
            return
        }

        let resolved = PropertyInheritanceResolver.resolve(connection: tab.connection, in: store.document)
        let cred = resolved.effectiveCredential
        let user = tab.adHocUsernameOverride ?? ((cred?.username.isEmpty == false) ? cred!.username : NSUserName())
        let auth: SshFfiConnectPayload.Auth = {
            if let pem = cred?.privateKeyPem, !pem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return .privateKeyPem(pem: pem, passphrase: cred?.keyPassphrase.isEmpty == true ? nil : cred?.keyPassphrase)
            }
            return .password(tab.adHocPasswordOverride ?? cred?.secretPassword ?? "")
        }()
        let spec = SshFfiConnectPayload(
            host: tab.connection.host,
            port: UInt16(clamping: transferSettings.port),
            username: user,
            auth: auth
        )
        let targetDir = remotePath
        isBusy = true
        transferStatusText = "Opening SFTP v3 subsystem on \(user)@\(tab.connection.host)…"

        Task.detached(priority: .userInitiated) {
            do {
                let ssh = try NativeSshSession.connect(spec: spec, openPty: false)
                let sftp = try ssh.openSftpSubsystem()
                let entries = try sftp.listDirectory(targetDir)
                await MainActor.run {
                    self.sshTransport = ssh
                    self.sftpSession = sftp
                    self.remoteEntries = entries
                    self.isConnected = true
                    self.isBusy = false
                    self.transferStatusText = "Connected via SFTP v3 (\(entries.count) items)"
                }
            } catch {
                await MainActor.run {
                    self.isConnected = false
                    self.isBusy = false
                    self.transferStatusText = error.localizedDescription
                }
            }
        }
    }

    private func refreshRemoteDirectory() {
        guard let sftp = sftpSession else { return }
        let path = remotePath
        isBusy = true
        Task.detached(priority: .userInitiated) {
            do {
                let entries = try sftp.listDirectory(path)
                await MainActor.run {
                    self.remoteEntries = entries
                    self.isBusy = false
                    self.transferStatusText = "Listed \(path)"
                }
            } catch {
                await MainActor.run {
                    self.isBusy = false
                    self.transferStatusText = error.localizedDescription
                }
            }
        }
    }

    private func uploadSelectedLocalFile() {
        guard let sftp = sftpSession,
              let localId = selectedLocalId,
              let local = localEntries.first(where: { $0.id == localId }),
              !local.isDirectory else { return }
        let destPath = remotePath.hasSuffix("/") ? "\(remotePath)\(local.name)" : "\(remotePath)/\(local.name)"
        isBusy = true
        transferStatusText = "Uploading \(local.name) → \(destPath)…"

        Task.detached(priority: .userInitiated) {
            do {
                let bytes = try Data(contentsOf: local.url)
                try sftp.uploadBytes(bytes, to: destPath)
                let refreshed = try sftp.listDirectory(remotePath)
                await MainActor.run {
                    self.remoteEntries = refreshed
                    self.isBusy = false
                    self.transferStatusText = "Uploaded \(local.name) (\(bytes.count) bytes)"
                }
            } catch {
                await MainActor.run {
                    self.isBusy = false
                    self.transferStatusText = error.localizedDescription
                }
            }
        }
    }

    private func downloadSelectedRemoteFile() {
        guard let sftp = sftpSession,
              let remoteId = selectedRemoteId,
              let entry = remoteEntries.first(where: { $0.id == remoteId }),
              !entry.isDirectory else { return }
        let destURL = localDirectoryURL.appendingPathComponent(entry.name)
        isBusy = true
        transferStatusText = "Downloading \(entry.name)…"

        Task.detached(priority: .userInitiated) {
            do {
                let data = try sftp.downloadBytes(entry.fullPath)
                try data.write(to: destURL, options: .atomic)
                await MainActor.run {
                    self.reloadLocalDirectory()
                    self.isBusy = false
                    self.transferStatusText = "Downloaded \(entry.name) (\(data.count) bytes)"
                }
            } catch {
                await MainActor.run {
                    self.isBusy = false
                    self.transferStatusText = error.localizedDescription
                }
            }
        }
    }

    private func createRemoteDirectory() {
        guard let sftp = sftpSession, !newFolderName.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let path = remotePath.hasSuffix("/") ? "\(remotePath)\(newFolderName)" : "\(remotePath)/\(newFolderName)"
        newFolderName = ""
        isCreatingRemoteFolder = false
        Task.detached(priority: .userInitiated) {
            try? sftp.createDirectory(path)
            let refreshed = (try? sftp.listDirectory(remotePath)) ?? []
            await MainActor.run {
                self.remoteEntries = refreshed
                self.transferStatusText = "Created directory \(path)"
            }
        }
    }

    private func deleteSelectedRemoteEntry() {
        guard let sftp = sftpSession,
              let remoteId = selectedRemoteId,
              let entry = remoteEntries.first(where: { $0.id == remoteId }) else { return }
        Task.detached(priority: .userInitiated) {
            try? sftp.removeEntry(entry.fullPath, isDirectory: entry.isDirectory)
            let refreshed = (try? sftp.listDirectory(remotePath)) ?? []
            await MainActor.run {
                self.remoteEntries = refreshed
                self.selectedRemoteId = nil
                self.transferStatusText = "Deleted \(entry.name)"
            }
        }
    }
}
