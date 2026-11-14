import AppKit
import NexusTSCore
import SwiftUI
import UniformTypeIdentifiers

public struct NexusMainWindowView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow

    @State private var isImportingVaultFile = false
    @State private var exportPayload: ExportedFileDocument? = nil
    @State private var exportFormat: VaultExportFormat = .nxts
    @State private var isExportingVaultFile = false
    @State private var isDownloadingVerifiedDmg = false
    @State private var bannerAlertMessage: String? = nil

    public init() {}

    public var body: some View {
        ZStack {
            LiquidGlassBackdropAura()

            if store.isVaultLocked {
                ZeroKnowledgeLockShieldView()
                    .transition(.opacity)
            } else {
                NavigationSplitView(columnVisibility: $store.columnVisibility) {
                    SidebarHierarchyView()
                        .navigationSplitViewColumnWidth(min: 185, ideal: 225, max: 340)
                } detail: {
                    workspaceDetailCanvas
                        .inspector(isPresented: $store.isInspectorPresented) {
                            InspectorColumnView()
                                .inspectorColumnWidth(min: 265, ideal: 300, max: 375)
                        }
                }
                .navigationTitle(store.document.name)
                .navigationSubtitle("Rev \(store.document.revision) • \(store.syncStatusSummary)")
                .toolbarBackground(.ultraThinMaterial, for: .windowToolbar)
                .toolbar {
                    unifiedMacToolbar
                }

                if store.isQuickConnectPresented {
                    QuickConnectSpotlightOverlay()
                        .zIndex(20)
                }
            }
        }
        .sheet(isPresented: $store.isPresentingNewConnectionSheet) {
            ConnectionEditorSheet(
                existing: nil,
                initialFolderId: store.newConnectionInitialFolderId,
                initialProtocol: store.newConnectionInitialProtocol
            )
        }
        .sheet(item: $store.editingConnection) { conn in
            ConnectionEditorSheet(
                existing: conn,
                initialFolderId: conn.parentFolderId,
                initialProtocol: conn.protocolType
            )
        }
        .sheet(isPresented: $store.isPresentingNewFolderSheet) {
            FolderInheritanceEditorSheet(existing: nil)
        }
        .sheet(item: $store.editingFolder) { folder in
            FolderInheritanceEditorSheet(existing: folder)
        }
        .sheet(item: $store.renamingFolder) { folder in
            RenameFolderSheet(folder: folder)
        }
        .sheet(item: $store.connectWithOptionsTarget) { target in
            ConnectWithOptionsSheet(connection: target)
        }
        .confirmationDialog(
            "Delete Folder \"\(store.deletingFolderCandidate?.name ?? "")\"?",
            isPresented: Binding(
                get: { store.deletingFolderCandidate != nil },
                set: { if !$0 { store.deletingFolderCandidate = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(store.strings.deleteFolderAndContents, role: .destructive) {
                if let folder = store.deletingFolderCandidate {
                    store.deleteFolder(folder.id, deleteContents: true)
                }
            }
            Button(store.strings.deleteFolderKeepContents) {
                if let folder = store.deletingFolderCandidate {
                    store.deleteFolder(folder.id, deleteContents: false)
                }
            }
            Button("Cancel", role: .cancel) {
                store.deletingFolderCandidate = nil
            }
        } message: {
            if let folder = store.deletingFolderCandidate {
                let count = store.connectionCountInSubtree(folder.id)
                Text("Choose whether to delete all \(count) connection(s) and subfolders inside \"\(folder.name)\" or move its contents up one level.")
            }
        }
        .fileImporter(
            isPresented: $isImportingVaultFile,
            allowedContentTypes: [.data, .xml, .json, .commaSeparatedText, .zip],
            allowsMultipleSelection: false
        ) { result in
            if case .success(let urls) = result, let first = urls.first {
                do {
                    let summary = try store.importExternalVaultFile(url: first)
                    bannerAlertMessage = "Imported \(summary.detectedFormat): \(summary.importedConnections) connections, \(summary.importedFolders) folders, \(summary.importedCredentials) credentials."
                } catch {
                    bannerAlertMessage = error.localizedDescription
                }
            }
        }
        .fileExporter(
            isPresented: $isExportingVaultFile,
            document: exportPayload,
            contentType: .data,
            defaultFilename: "\(store.document.name.replacingOccurrences(of: " ", with: "-")).\(exportFormat.rawValue)"
        ) { _ in
            exportPayload = nil
        }
        .alert("NexusTS Vault", isPresented: Binding(
            get: { bannerAlertMessage != nil },
            set: { if !$0 { bannerAlertMessage = nil } }
        )) {
            Button("OK", role: .cancel) { bannerAlertMessage = nil }
        } message: {
            Text(bannerAlertMessage ?? "")
        }
    }

    @ViewBuilder
    private var workspaceDetailCanvas: some View {
        VStack(spacing: 0) {
            if let update = store.updateCandidate {
                HStack(spacing: 10) {
                    Image(systemName: "arrow.down.circle.fill")
                        .foregroundStyle(Color(nsColor: .systemBlue))
                    Text("NexusTS \(update.tagName) is available on GitHub Releases (100% vault-compatible in-place upgrade).")
                        .font(.callout.weight(.medium))
                    Spacer()
                    if isDownloadingVerifiedDmg {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        Button("Download Verified DMG…") {
                            isDownloadingVerifiedDmg = true
                            Task { @MainActor in
                                defer { isDownloadingVerifiedDmg = false }
                                do {
                                    if let dmgUrl = try await store.downloadVerifiedUpdateDmg() {
                                        NSWorkspace.shared.open(dmgUrl)
                                        NSWorkspace.shared.activateFileViewerSelecting([dmgUrl])
                                    } else {
                                        NSWorkspace.shared.open(update.dmgDownloadUrl ?? update.htmlReleaseUrl)
                                    }
                                } catch {
                                    NSWorkspace.shared.open(update.dmgDownloadUrl ?? update.htmlReleaseUrl)
                                }
                            }
                        }
                        .controlSize(.small)
                    }
                    Button {
                        store.updateCandidate = nil
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(.bar)
                Divider()
            }

            switch store.activeSection {
            case .sessions:
                SplitPaneWorkspaceView()
            case .overview:
                VaultOverviewDashboardView()
            case .templates:
                TemplatesStudioView()
            case .credentials:
                CredentialsStudioView()
            case .gateways:
                GatewaysStudioView()
            case .automationTasks:
                AutomationTasksStudioView()
            case .cloudSync:
                CloudSyncStudioView()
            }

            if store.isBottomConsolePresented {
                Divider()
                BottomAuditAndBroadcastConsole()
                    .frame(height: 175)
            }
        }
    }

    private func studioTitle(for section: WorkspacePrimarySection) -> String {
        switch section {
        case .sessions: return store.strings.connections
        case .overview: return store.strings.overview
        case .templates: return store.strings.templates
        case .credentials: return store.strings.credentials
        case .gateways: return store.strings.gateways
        case .automationTasks: return store.strings.automationTasks
        case .cloudSync: return store.strings.cloudSync
        }
    }

    @ToolbarContentBuilder
    private var unifiedMacToolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .navigation) {
            if store.activeSection != .sessions {
                Button {
                    store.activeSection = .sessions
                } label: {
                    Label(store.strings.connections, systemImage: "chevron.left")
                }
                .help("Return to Connections & Sessions (⌘1)")
            }

            Menu {
                ForEach(WorkspacePrimarySection.allCases) { section in
                    Button {
                        store.activeSection = section
                    } label: {
                        Label(
                            studioTitle(for: section),
                            systemImage: store.activeSection == section ? "checkmark.circle.fill" : section.sfSymbol
                        )
                    }
                }
            } label: {
                Label(
                    store.activeSection == .sessions ? store.strings.studios : studioTitle(for: store.activeSection),
                    systemImage: store.activeSection == .sessions ? "square.grid.2x2" : store.activeSection.sfSymbol
                )
            }
            .help("Switch Workspace Studio (⌘1–⌘7)")
        }

        ToolbarItemGroup(placement: .primaryAction) {
            Menu {
                Button {
                    store.newConnectionInitialFolderId = store.selectedFolderId
                    store.newConnectionInitialProtocol = .SSH
                    store.isPresentingNewConnectionSheet = true
                } label: {
                    Label(store.strings.newConnection, systemImage: "plus.rectangle.on.rectangle")
                }

                Button {
                    store.newConnectionInitialFolderId = store.selectedFolderId
                    store.newConnectionInitialProtocol = .RDP
                    store.isPresentingNewConnectionSheet = true
                } label: {
                    Label("New Remote Desktop (RDP)…", systemImage: "display")
                }

                Button {
                    store.newFolderInitialParentId = store.selectedFolderId
                    store.isPresentingNewFolderSheet = true
                } label: {
                    Label(store.strings.newFolder, systemImage: "folder.badge.plus")
                }

                Divider()

                Button {
                    store.openLocalTerminal()
                } label: {
                    Label(store.strings.localTerminal, systemImage: "apple.terminal")
                }

                Divider()

                Button {
                    isImportingVaultFile = true
                } label: {
                    Label("Import Vault (.nxts, .rtsz, .1pux, .kdbx, .csv)…", systemImage: "square.and.arrow.down")
                }

                Menu("Export Vault") {
                    ForEach(VaultExportFormat.allCases) { fmt in
                        Button(fmt.displayTitle) {
                            if let data = try? VaultDocumentExporter.export(document: store.document, format: fmt) {
                                exportFormat = fmt
                                exportPayload = ExportedFileDocument(data: data)
                                isExportingVaultFile = true
                            }
                        }
                    }
                }
            } label: {
                Label("Add", systemImage: "plus")
            } primaryAction: {
                store.newConnectionInitialFolderId = store.selectedFolderId
                store.newConnectionInitialProtocol = .SSH
                store.isPresentingNewConnectionSheet = true
            }
            .help("Create a new connection, folder, or import an enterprise vault (⌘N)")

            Button {
                store.openLocalTerminal()
            } label: {
                Label(store.strings.localTerminal, systemImage: "terminal")
            }
            .help("Open local macOS login shell (/bin/zsh) (⌘T)")

            Button {
                store.isQuickConnectPresented.toggle()
            } label: {
                Label("Quick Connect", systemImage: "sparkle.magnifyingglass")
            }
            .help("Quick Connect & Fuzzy Host Search (⌘K)")

            Button {
                store.splitFocusedPane(.horizontal)
            } label: {
                Label(store.strings.splitRight, systemImage: "rectangle.split.2x1")
            }
            .help("Split Active Pane Right (⌘D)")

            Button {
                store.splitFocusedPane(.vertical)
            } label: {
                Label(store.strings.splitDown, systemImage: "rectangle.split.1x2")
            }
            .help("Split Active Pane Down (⇧⌘D)")

            Toggle(isOn: $store.broadcastInputEnabled) {
                Label(store.strings.broadcastInput, systemImage: "dot.radiowaves.left.and.right")
            }
            .help("Broadcast keyboard input to all terminal panes (⇧⌘B)")

            Button {
                Task { await store.syncCloudNow() }
            } label: {
                Label(store.strings.syncNow, systemImage: "arrow.triangle.2.circlepath.icloud")
            }
            .help("Synchronize vault with iCloud Drive or Google Drive (⇧⌘S)")

            Button {
                store.lockVault()
            } label: {
                Label(store.strings.lockVault, systemImage: "lock")
            }
            .help("Lock zero-knowledge vault and zeroize keys (⇧⌘L)")

            Button {
                store.isInspectorPresented.toggle()
            } label: {
                Label(store.strings.inspectorTitle, systemImage: "sidebar.trailing")
            }
            .help("Toggle Property Inspector (⌥⌘I)")
        }
    }
}

// MARK: - Spotlight-Style Floating Quick Connect Palette (⌘K)

struct QuickConnectSpotlightOverlay: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow
    @State private var query: String = ""
    @FocusState private var isSearchFocused: Bool

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.opacity(0.22)
                .ignoresSafeArea()
                .onTapGesture {
                    store.isQuickConnectPresented = false
                }

            VStack(spacing: 0) {
                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(.secondary)

                    TextField(store.strings.quickConnectPlaceholder, text: $query)
                        .textFieldStyle(.plain)
                        .font(.system(size: 15))
                        .focused($isSearchFocused)
                        .onSubmit {
                            launchPrimaryResult(split: nil)
                        }

                    KeycapBadge("esc")
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 13)

                Divider()

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        if let adHoc = QuickConnectParser.parseAdHocUri(query), !query.isEmpty {
                            Button {
                                store.openConnection(
                                    adHoc.toAdHocVaultConnection(),
                                    adHocUsername: adHoc.username,
                                    adHocDomain: adHoc.domain
                                )
                                store.isQuickConnectPresented = false
                            } label: {
                                HStack(spacing: 10) {
                                    ProtocolSquircleIcon(protocolType: adHoc.protocolType, size: 28)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("Instant Connect to \(adHoc.displayName)")
                                            .font(.body.weight(.medium))
                                        Text("\(adHoc.protocolType.displayName) • Port \(adHoc.port)")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    KeycapBadge("↩")
                                }
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            Divider().padding(.vertical, 4)
                        }

                        let hits = QuickConnectParser.rankConnections(query: query, in: store.document)
                        ForEach(hits) { hit in
                            HStack(spacing: 10) {
                                ProtocolSquircleIcon(
                                    protocolType: hit.connection.protocolType,
                                    customHex: hit.connection.inheritable.displayColorAccentHex,
                                    size: 28
                                )
                                VStack(alignment: .leading, spacing: 2) {
                                    HStack(spacing: 6) {
                                        Text(hit.connection.name)
                                            .font(.body.weight(.medium))
                                        if hit.connection.favorite {
                                            Image(systemName: "star.fill")
                                                .font(.caption2)
                                                .foregroundStyle(.yellow)
                                        }
                                    }
                                    Text("\(hit.connection.host) • \(hit.folderBreadcrumb)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Button("Split") {
                                    store.openConnection(hit.connection, inSplit: .horizontal)
                                    store.isQuickConnectPresented = false
                                }
                                .controlSize(.small)
                                Button("Connect") {
                                    store.openConnection(hit.connection)
                                    store.isQuickConnectPresented = false
                                }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.small)
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                        }
                    }
                    .padding(8)
                }
                .frame(maxHeight: 340)

                Divider()
                HStack(spacing: 14) {
                    Label("↩ Open Tab", systemImage: "return")
                    Label("URI Schemes: ssh:// rdp:// vnc:// ard:// sftp:// serial:// k8s:// docker://", systemImage: "link")
                    Spacer()
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
            }
            .frame(width: 620)
            .clipShape(RoundedRectangle(cornerRadius: LiquidGlassMetrics.modalCornerRadius, style: .continuous))
            .liquidGlassSurface(
                cornerRadius: LiquidGlassMetrics.modalCornerRadius,
                prominence: .elevated
            )
            .padding(.top, 56)
            .onExitCommand {
                store.isQuickConnectPresented = false
            }
        }
        .onAppear {
            isSearchFocused = true
        }
    }

    private func launchPrimaryResult(split: SplitOrientation?) {
        let hits = QuickConnectParser.rankConnections(query: query, in: store.document)
        if let top = hits.first, !query.contains("://") {
            store.openConnection(top.connection, inSplit: split)
            store.isQuickConnectPresented = false
        } else if let adHoc = QuickConnectParser.parseAdHocUri(query) {
            store.openConnection(
                adHoc.toAdHocVaultConnection(),
                inSplit: split,
                adHocUsername: adHoc.username,
                adHocDomain: adHoc.domain
            )
            store.isQuickConnectPresented = false
        }
    }
}

// MARK: - Zero-Knowledge Lock Shield with Native Touch ID / Apple Watch Button

struct ZeroKnowledgeLockShieldView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @State private var passphraseInput: String = ""

    var body: some View {
        ZStack {
            LiquidGlassBackdropAura()

            VStack(spacing: 20) {
                ZStack {
                    Circle()
                        .fill(.ultraThinMaterial)
                        .frame(width: 84, height: 84)
                        .overlay(
                            Circle()
                                .fill(Color.accentColor.opacity(0.16))
                        )
                        .overlay(
                            Circle()
                                .strokeBorder(Color.accentColor.opacity(0.38), lineWidth: 0.85)
                        )
                    Image(systemName: "lock.shield.fill")
                        .font(.system(size: 40, weight: .semibold))
                        .symbolRenderingMode(.hierarchical)
                        .foregroundStyle(Color.accentColor)
                }

                VStack(spacing: 6) {
                    Text(store.document.name)
                        .font(.title2.weight(.semibold))
                    Text("Zero-Knowledge Argon2id (64 MiB) + AES-256-GCM Sealed Vault")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                VStack(spacing: 12) {
                    SecureField("Master Passphrase", text: $passphraseInput)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 300)
                        .onSubmit {
                            _ = store.unlockVault(withPassphrase: passphraseInput)
                        }

                    HStack(spacing: 10) {
                        if MacKeychainAndBiometrics.shared.hasStoredPassphrase(forVaultId: store.document.documentId) {
                            Button {
                                Task { await store.unlockWithTouchId() }
                            } label: {
                                Label(store.strings.unlockWithTouchId, systemImage: "touchid")
                            }
                            .controlSize(.large)
                        }

                        Button(store.strings.unlockVault) {
                            _ = store.unlockVault(withPassphrase: passphraseInput)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                    }

                    if let err = store.unlockErrorText {
                        Text(err)
                            .font(.caption)
                            .foregroundStyle(Color(nsColor: .systemRed))
                    }
                }
            }
            .padding(36)
            .liquidGlassSurface(
                cornerRadius: LiquidGlassMetrics.modalCornerRadius,
                tint: Color.accentColor,
                prominence: .elevated
            )
        }
    }
}

// MARK: - Bottom Audit Log & Global Broadcast Console

struct BottomAuditAndBroadcastConsole: View {
    @EnvironmentObject private var store: WorkspaceStore
    @State private var broadcastCommandText: String = ""

    private static let timeFmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Label(store.strings.auditConsole, systemImage: "text.and.command.macwindow")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                HStack(spacing: 6) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .foregroundStyle(store.broadcastInputEnabled ? Color.orange : .secondary)
                    TextField("Broadcast command to all open terminals…", text: $broadcastCommandText)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 320)
                        .onSubmit {
                            guard !broadcastCommandText.isEmpty else { return }
                            store.executeBroadcastCommandLine(broadcastCommandText)
                            broadcastCommandText = ""
                        }
                    Button("Send") {
                        guard !broadcastCommandText.isEmpty else { return }
                        store.executeBroadcastCommandLine(broadcastCommandText)
                        broadcastCommandText = ""
                    }
                    .controlSize(.small)
                }
                Button {
                    store.isBottomConsolePresented = false
                } label: {
                    Image(systemName: "chevron.down")
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(.bar)

            Divider()

            List(store.auditLog) { item in
                HStack(spacing: 10) {
                    Text(Self.timeFmt.string(from: item.timestamp))
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(.secondary)
                    Text(item.category.uppercased())
                        .font(.system(size: 10, weight: .bold, design: .rounded))
                        .foregroundStyle(Color.accentColor)
                        .frame(width: 78, alignment: .leading)
                    Text(item.message)
                        .font(.system(size: 12))
                        .lineLimit(1)
                }
            }
            .listStyle(.plain)
        }
    }
}

// MARK: - FileExporter Wrapper

struct ExportedFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    var data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        self.data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
