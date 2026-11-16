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

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    public var body: some View {
        DualPaneTransferLiveBody(
            tab: tab,
            controller: store.transferController(for: tab)
        )
    }
}

private struct DualPaneTransferLiveBody: View {
    @EnvironmentObject private var store: WorkspaceStore
    let tab: WorkspaceSessionTab
    @ObservedObject var controller: LiveTransferController

    @State private var selectedLocalId: LocalFileEntry.ID? = nil
    @State private var selectedRemoteId: SftpRemoteEntry.ID? = nil
    @State private var showHiddenFiles: Bool = true

    @State private var isCreatingRemoteFolder: Bool = false
    @State private var newFolderName: String = ""

    @State private var renamingEntry: SftpRemoteEntry? = nil
    @State private var renameTargetText: String = ""

    @State private var chmodEntry: SftpRemoteEntry? = nil
    @State private var chmodOctalText: String = "0755"

    private var latestConnection: VaultConnection {
        store.activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
    }

    private var transferSettings: FileTransferProtocolSettings {
        if case .fileTransfer(let s) = latestConnection.settings { return s }
        return FileTransferProtocolSettings(
            port: latestConnection.protocolType.defaultPort,
            ftpsImplicitTls: latestConnection.protocolType == .FTPS
        )
    }

    private var mappedLocalEntries: [LocalFileEntry] {
        controller.localEntries
            .filter { showHiddenFiles || !$0.lastPathComponent.hasPrefix(".") }
            .map { url in
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

    private var filteredRemoteEntries: [SftpRemoteEntry] {
        controller.remoteEntries
            .filter { showHiddenFiles || !$0.name.hasPrefix(".") }
            .sorted {
                if $0.isDirectory != $1.isDirectory { return $0.isDirectory && !$1.isDirectory }
                return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
    }

    private var selectedLocalItem: LocalFileEntry? {
        guard let id = selectedLocalId else { return nil }
        return mappedLocalEntries.first(where: { $0.id == id })
    }

    private var selectedRemoteItem: SftpRemoteEntry? {
        guard let id = selectedRemoteId else { return nil }
        return filteredRemoteEntries.first(where: { $0.id == id })
    }

    var body: some View {
        VStack(spacing: 0) {
            // Unified Commander Action Bar (SFTP / SCP / FTP / FTPS)
            HStack(spacing: 10) {
                Circle()
                    .fill(
                        controller.isConnected
                            ? Color(nsColor: .systemGreen)
                            : (controller.isConnecting ? Color(nsColor: .systemOrange) : Color(nsColor: .systemRed))
                    )
                    .frame(width: 8, height: 8)

                Text(verbatim: "\(latestConnection.protocolType.shortBadge) Commander • \(latestConnection.host):\(String(transferSettings.port))")
                    .font(.callout.weight(.semibold))

                if latestConnection.protocolType == .FTPS {
                    EnvironmentCapsuleBadge(transferSettings.ftpsImplicitTls || transferSettings.port == 990 ? "Implicit TLS" : "Explicit AUTH TLS")
                }

                Spacer()

                Toggle("Show Dotfiles", isOn: $showHiddenFiles)
                    .toggleStyle(.checkbox)
                    .font(.caption)

                Button {
                    if let local = selectedLocalItem, !local.isDirectory {
                        controller.uploadFiles([local.url], onError: reportTransferError)
                    }
                } label: {
                    Label("Upload →", systemImage: "arrow.right.square.fill")
                }
                .controlSize(.small)
                .disabled(!controller.isConnected || selectedLocalItem == nil || selectedLocalItem?.isDirectory == true)

                Button {
                    if let remote = selectedRemoteItem, !remote.isDirectory {
                        controller.downloadEntry(remote, onError: reportTransferError)
                    }
                } label: {
                    Label("← Download", systemImage: "arrow.left.square.fill")
                }
                .controlSize(.small)
                .disabled(!controller.isConnected || selectedRemoteItem == nil || selectedRemoteItem?.isDirectory == true)

                Button {
                    if controller.isConnected {
                        controller.refreshLocal()
                        controller.refreshRemote(onError: reportTransferError)
                    } else {
                        store.reconnectTransferController(for: tab)
                    }
                } label: {
                    Label(controller.isConnected ? "Refresh" : "Connect", systemImage: "arrow.clockwise")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .disabled(controller.isConnecting)
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
                            let parent = URL(fileURLWithPath: controller.localPath).deletingLastPathComponent().path
                            controller.localPath = parent.isEmpty ? "/" : parent
                            controller.refreshLocal()
                        } label: {
                            Image(systemName: "chevron.up")
                        }
                        .buttonStyle(.borderless)
                        .help("Parent Directory")

                        TextField("Local Path", text: $controller.localPath)
                            .textFieldStyle(.roundedBorder)
                            .font(.system(size: 12, design: .monospaced))
                            .onSubmit {
                                controller.refreshLocal()
                            }

                        Button {
                            NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: controller.localPath)])
                        } label: {
                            Image(systemName: "folder")
                        }
                        .buttonStyle(.borderless)
                        .help("Reveal in macOS Finder")
                    }
                    .padding(8)
                    .background(Color(nsColor: .windowBackgroundColor))

                    Divider()

                    Table(mappedLocalEntries, selection: $selectedLocalId) {
                        TableColumn("Name") { item in
                            HStack(spacing: 6) {
                                Image(systemName: item.isDirectory ? "folder.fill" : "doc")
                                    .foregroundStyle(item.isDirectory ? Color.accentColor : .secondary)
                                Text(item.name)
                                    .lineLimit(1)
                            }
                            .contentShape(Rectangle())
                            .onTapGesture(count: 2) {
                                if item.isDirectory {
                                    controller.localPath = item.url.path
                                    controller.refreshLocal()
                                } else if controller.isConnected {
                                    controller.uploadFiles([item.url], onError: reportTransferError)
                                }
                            }
                            .contextMenu {
                                if item.isDirectory {
                                    Button("Open Directory") {
                                        controller.localPath = item.url.path
                                        controller.refreshLocal()
                                    }
                                } else {
                                    Button("Upload to Remote") {
                                        controller.uploadFiles([item.url], onError: reportTransferError)
                                    }
                                    .disabled(!controller.isConnected)
                                }
                                Button("Reveal in Finder") {
                                    NSWorkspace.shared.activateFileViewerSelecting([item.url])
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

                // RIGHT PANE: Remote SFTP v3 / SCP / FTP / FTPS Server
                VStack(spacing: 0) {
                    HStack(spacing: 6) {
                        Image(systemName: "server.rack")
                            .foregroundStyle(Color(nsColor: .systemBlue))
                        Button {
                            let parent = (controller.remotePath as NSString).deletingLastPathComponent
                            let nextPath = parent.isEmpty ? "/" : parent
                            controller.refreshRemote(pathOverride: nextPath, onError: reportTransferError)
                        } label: {
                            Image(systemName: "chevron.up")
                        }
                        .buttonStyle(.borderless)
                        .disabled(!controller.isConnected)

                        TextField("Remote Path", text: $controller.remotePath)
                            .textFieldStyle(.roundedBorder)
                            .font(.system(size: 12, design: .monospaced))
                            .onSubmit {
                                controller.refreshRemote(pathOverride: controller.remotePath, onError: reportTransferError)
                            }

                        Button {
                            isCreatingRemoteFolder = true
                        } label: {
                            Image(systemName: "folder.badge.plus")
                        }
                        .buttonStyle(.borderless)
                        .help("Create Remote Directory")
                        .disabled(!controller.isConnected)
                        .popover(isPresented: $isCreatingRemoteFolder) {
                            HStack(spacing: 8) {
                                TextField("Folder name", text: $newFolderName)
                                    .textFieldStyle(.roundedBorder)
                                    .frame(width: 160)
                                    .onSubmit {
                                        submitCreateFolder()
                                    }
                                Button("Create") {
                                    submitCreateFolder()
                                }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.small)
                            }
                            .padding(10)
                        }

                        Button {
                            if let entry = selectedRemoteItem {
                                renameTargetText = entry.name
                                renamingEntry = entry
                            }
                        } label: {
                            Image(systemName: "pencil")
                        }
                        .buttonStyle(.borderless)
                        .help("Rename Remote Item")
                        .disabled(!controller.isConnected || selectedRemoteItem == nil)

                        Button {
                            if let entry = selectedRemoteItem {
                                chmodOctalText = String(format: "%04o", entry.unixMode & 0o7777)
                                chmodEntry = entry
                            }
                        } label: {
                            Image(systemName: "lock.open.rotation")
                        }
                        .buttonStyle(.borderless)
                        .help("Change POSIX Permissions (chmod)")
                        .disabled(!controller.isConnected || selectedRemoteItem == nil)

                        Button(role: .destructive) {
                            if let entry = selectedRemoteItem {
                                controller.deleteRemoteEntry(entry, onError: reportTransferError)
                                selectedRemoteId = nil
                            }
                        } label: {
                            Image(systemName: "trash")
                        }
                        .buttonStyle(.borderless)
                        .help("Delete Remote Item")
                        .disabled(!controller.isConnected || selectedRemoteItem == nil)
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
                            .contentShape(Rectangle())
                            .onTapGesture(count: 2) {
                                if entry.isDirectory {
                                    controller.refreshRemote(pathOverride: entry.fullPath, onError: reportTransferError)
                                } else {
                                    controller.downloadEntry(entry, onError: reportTransferError)
                                }
                            }
                            .contextMenu {
                                if entry.isDirectory {
                                    Button("Open Directory") {
                                        controller.refreshRemote(pathOverride: entry.fullPath, onError: reportTransferError)
                                    }
                                } else {
                                    Button("Download to Local Pane") {
                                        controller.downloadEntry(entry, onError: reportTransferError)
                                    }
                                }
                                Divider()
                                Button("Rename…") {
                                    renameTargetText = entry.name
                                    renamingEntry = entry
                                }
                                Button("Permissions (chmod)…") {
                                    chmodOctalText = String(format: "%04o", entry.unixMode & 0o7777)
                                    chmodEntry = entry
                                }
                                Divider()
                                Button("Delete", role: .destructive) {
                                    controller.deleteRemoteEntry(entry, onError: reportTransferError)
                                    if selectedRemoteId == entry.id {
                                        selectedRemoteId = nil
                                    }
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

            HStack(spacing: 8) {
                if controller.isConnecting {
                    ProgressView()
                        .controlSize(.small)
                }
                Text(controller.statusText)
                    .font(.caption)
                    .foregroundStyle(controller.lastErrorText != nil ? Color(nsColor: .systemRed) : .secondary)
                    .lineLimit(1)
                Spacer()
                Text("\(mappedLocalEntries.count) local • \(filteredRemoteEntries.count) remote")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 5)
            .background(.bar)
        }
        .onAppear {
            showHiddenFiles = transferSettings.showHiddenDotfiles
        }
        .sheet(item: $renamingEntry) { entry in
            VStack(alignment: .leading, spacing: 14) {
                Text("Rename Remote Item")
                    .font(.headline)
                TextField("New name", text: $renameTargetText)
                    .textFieldStyle(.roundedBorder)
                HStack {
                    Button("Cancel", role: .cancel) {
                        renamingEntry = nil
                    }
                    Spacer()
                    Button("Rename") {
                        controller.renameRemoteEntry(entry, newName: renameTargetText, onError: reportTransferError)
                        renamingEntry = nil
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(renameTargetText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .padding(18)
            .frame(width: 340)
        }
        .sheet(item: $chmodEntry) { entry in
            VStack(alignment: .leading, spacing: 14) {
                Text("Permissions (chmod) — \(entry.name)")
                    .font(.headline)
                TextField("Octal mode (e.g. 0755 or 0644)", text: $chmodOctalText)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(.body, design: .monospaced))
                HStack {
                    Button("Cancel", role: .cancel) {
                        chmodEntry = nil
                    }
                    Spacer()
                    Button("Apply") {
                        controller.chmodRemoteEntry(entry, octalString: chmodOctalText, onError: reportTransferError)
                        chmodEntry = nil
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding(18)
            .frame(width: 340)
        }
    }

    private func submitCreateFolder() {
        let trimmed = newFolderName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        newFolderName = ""
        isCreatingRemoteFolder = false
        controller.createRemoteDirectory(named: trimmed, onError: reportTransferError)
    }

    private func reportTransferError(_ error: Error) {
        store.reportError(
            error,
            operationTitle: "\(latestConnection.protocolType.shortBadge) File Transfer Error",
            recoveryConnectionId: latestConnection.id
        )
    }
}
