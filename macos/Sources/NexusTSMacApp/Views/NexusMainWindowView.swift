import AppKit
import NexusTSCore
import SwiftUI
import UniformTypeIdentifiers

public struct NexusMainWindowView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow

    @State private var isImportingVaultFile = false
    @State private var pendingEncryptedImportURL: URL? = nil
    @State private var importArchivePassword: String = ""
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
            } else if store.isFullScreenSessionMode && !store.paneTree.allTabs.isEmpty {
                FullScreenSessionMacWorkspaceView()

                if store.isQuickConnectPresented {
                    QuickConnectSpotlightOverlay()
                        .zIndex(20)
                }
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
        .sheet(isPresented: $store.isPresentingNewSmartFolderSheet) {
            SmartFolderEditorSheet(existing: nil)
        }
        .sheet(item: $store.editingSmartFolder) { sf in
            SmartFolderEditorSheet(existing: sf)
        }
        .sheet(isPresented: $store.isPresentingNewDynamicFolderSheet) {
            DynamicFolderEditorSheet(existing: nil)
        }
        .sheet(item: $store.editingDynamicFolder) { df in
            DynamicFolderEditorSheet(existing: df)
        }
        .sheet(isPresented: $store.isPresentingNewCredentialSheet) {
            CredentialEditorSheet(existing: nil)
        }
        .sheet(item: $store.editingCredential) { cred in
            CredentialEditorSheet(existing: cred)
        }
        .sheet(item: $store.pendingRoyalImport) { pending in
            RoyalTsImportPlacementSheet(pending: pending)
        }
        .sheet(isPresented: $store.isPresentingResetDatabaseSheet) {
            ResetDatabaseConfirmationSheet()
        }
        .sheet(item: $store.activeAppError) { appErr in
            NexusAppErrorSheet(error: appErr)
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
            switch result {
            case .success(let urls):
                guard let first = urls.first else { return }
                do {
                    let summary = try store.importExternalVaultFile(url: first)
                    if !summary.isRoyalTsFormat {
                        bannerAlertMessage = "Imported \(summary.detectedFormat): \(summary.importedConnections) connections, \(summary.importedFolders) folders, \(summary.importedCredentials) credentials."
                    }
                } catch {
                    let classified = NexusErrorClassifier.classify(error, operationTitle: "Vault Import Failed")
                    if classified.category == .vaultCryptoOrCorruption {
                        pendingEncryptedImportURL = first
                        importArchivePassword = ""
                    } else {
                        store.reportAppError(classified)
                    }
                }
            case .failure(let error):
                store.reportError(error, operationTitle: "File Picker Error")
            }
        }
        .alert(
            "Password-Protected Vault / Royal TS Archive",
            isPresented: Binding(
                get: { pendingEncryptedImportURL != nil },
                set: { if !$0 { pendingEncryptedImportURL = nil } }
            )
        ) {
            SecureField("Archive Password / Passphrase", text: $importArchivePassword)
            Button("Decrypt & Import") {
                guard let url = pendingEncryptedImportURL else { return }
                let password = importArchivePassword
                pendingEncryptedImportURL = nil
                importArchivePassword = ""
                do {
                    let summary = try store.importExternalVaultFile(url: url, passphrase: password)
                    if !summary.isRoyalTsFormat {
                        bannerAlertMessage = "Imported \(summary.detectedFormat): \(summary.importedConnections) connections, \(summary.importedFolders) folders, \(summary.importedCredentials) credentials."
                    }
                } catch {
                    store.reportError(error, operationTitle: "Encrypted Archive Import Failed")
                }
            }
            Button("Cancel", role: .cancel) {
                pendingEncryptedImportURL = nil
                importArchivePassword = ""
            }
        } message: {
            Text("Enter the password used to encrypt \"\(pendingEncryptedImportURL?.lastPathComponent ?? "file")\".")
        }
        .fileExporter(
            isPresented: $isExportingVaultFile,
            document: exportPayload,
            contentType: .data,
            defaultFilename: "\(store.document.name.replacingOccurrences(of: " ", with: "-")).\(exportFormat.rawValue)"
        ) { result in
            exportPayload = nil
            if case .failure(let error) = result {
                store.reportError(error, operationTitle: "Vault Export Failed")
            }
        }
        .onChange(of: store.statusToastMessage) { _, msg in
            if let msg {
                bannerAlertMessage = msg
                store.statusToastMessage = nil
            }
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
            Button {
                store.toggleSidebarVisibility()
            } label: {
                Label("Toggle Sidebar", systemImage: "line.3.horizontal")
            }
            .help("Show / Hide Sidebar")

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
                    let targetFolder = store.selectedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId
                        ? nil
                        : store.selectedFolderId
                    store.newConnectionInitialFolderId = targetFolder
                    store.newConnectionInitialProtocol = .SSH
                    store.isPresentingNewConnectionSheet = true
                } label: {
                    Label(store.strings.newConnection, systemImage: "plus.rectangle.on.rectangle")
                }

                Button {
                    let targetFolder = store.selectedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId
                        ? nil
                        : store.selectedFolderId
                    store.newConnectionInitialFolderId = targetFolder
                    store.newConnectionInitialProtocol = .RDP
                    store.isPresentingNewConnectionSheet = true
                } label: {
                    Label("New Remote Desktop (RDP)…", systemImage: "display")
                }

                Button {
                    let parentFolder = store.selectedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId
                        ? nil
                        : store.selectedFolderId
                    store.newFolderInitialParentId = parentFolder
                    store.isPresentingNewFolderSheet = true
                } label: {
                    Label(store.strings.newFolder, systemImage: "folder.badge.plus")
                }

                Button {
                    store.isPresentingNewSmartFolderSheet = true
                } label: {
                    Label(store.strings.newSmartFolder, systemImage: "sparkles.rectangle.stack")
                }

                Button {
                    store.isPresentingNewDynamicFolderSheet = true
                } label: {
                    Label(store.strings.newDynamicFolder, systemImage: "bolt.badge.clock")
                }

                Button {
                    store.isPresentingNewCredentialSheet = true
                } label: {
                    Label(store.strings.newCredential, systemImage: "key.fill")
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
                    Label("Import Vault (.nxts, .rtsz, .rtsx, .rjson, .1pux, .kdbx, .csv)…", systemImage: "square.and.arrow.down")
                }

                Menu("Export Vault") {
                    ForEach(VaultExportFormat.allCases) { fmt in
                        Button(fmt.displayTitle) {
                            do {
                                let data = try VaultDocumentExporter.export(document: store.document, format: fmt)
                                exportFormat = fmt
                                exportPayload = ExportedFileDocument(data: data)
                                isExportingVaultFile = true
                            } catch {
                                store.reportError(error, operationTitle: "Vault Export Failed")
                            }
                        }
                    }
                }

                Divider()

                Button(role: .destructive) {
                    store.isPresentingResetDatabaseSheet = true
                } label: {
                    Label(store.strings.resetDatabase, systemImage: "trash")
                }
            } label: {
                Label("Add", systemImage: "plus")
            } primaryAction: {
                let targetFolder = store.selectedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId
                    ? nil
                    : store.selectedFolderId
                store.newConnectionInitialFolderId = targetFolder
                store.newConnectionInitialProtocol = .SSH
                store.isPresentingNewConnectionSheet = true
            }
            .help("Create a new connection, folder, intelligent folder, credential, or import a Royal TS / enterprise vault (⌘N)")

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
                Task { await store.syncCloudNow(reportErrors: true) }
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

                    Divider()
                        .frame(width: 260)
                        .padding(.top, 4)

                    Button(role: .destructive) {
                        store.isPresentingResetDatabaseSheet = true
                    } label: {
                        Text("\(store.strings.resetDatabase)…")
                            .font(.caption)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
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

// MARK: - Full-Screen Session Workspace with Active Session Switcher & Folder Hierarchy Launcher

private struct FullScreenSessionMacWorkspaceView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @State private var isToolbarExpanded: Bool = true
    @State private var isFolderBrowserPresented: Bool = false

    var body: some View {
        let allTabs = store.paneTree.allTabs
        let activeTab = store.focusedOrFirstActiveTab

        VStack(spacing: 0) {
            if isToolbarExpanded {
                HStack(spacing: 8) {
                    Button {
                        isFolderBrowserPresented.toggle()
                    } label: {
                        Label("Folders & Servers", systemImage: "folder.fill")
                            .font(.caption.weight(.semibold))
                    }
                    .controlSize(.small)
                    .popover(isPresented: $isFolderBrowserPresented, arrowEdge: .bottom) {
                        FullScreenFolderLauncherPopover(isPresented: $isFolderBrowserPresented)
                            .environmentObject(store)
                    }

                    Divider().frame(height: 16)

                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(allTabs) { tab in
                                let isSelected = tab.id == activeTab?.id
                                let accent = HIGColorUtilities.color(fromHex: tab.accentHex)
                                    ?? tab.connection.protocolType.appleSystemTint
                                HStack(spacing: 6) {
                                    Button {
                                        store.selectTabGlobally(tab.id)
                                    } label: {
                                        HStack(spacing: 5) {
                                            Image(systemName: tab.isLocalShell ? "apple.terminal" : tab.connection.protocolType.sfSymbolName)
                                                .font(.system(size: 11, weight: .semibold))
                                                .foregroundStyle(accent)
                                            Text(tab.title)
                                                .font(.system(size: 12, weight: isSelected ? .semibold : .regular))
                                                .lineLimit(1)
                                        }
                                        .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)

                                    Button {
                                        store.closeTab(tab.id)
                                    } label: {
                                        Image(systemName: "xmark")
                                            .font(.system(size: 9, weight: .bold))
                                            .foregroundStyle(.secondary)
                                    }
                                    .buttonStyle(.plain)
                                }
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .liquidGlassCapsule(tint: accent, isSelected: isSelected, isHovered: false)
                            }
                        }
                    }

                    Spacer(minLength: 8)

                    Button {
                        isToolbarExpanded = false
                    } label: {
                        Image(systemName: "chevron.up")
                            .font(.caption)
                    }
                    .buttonStyle(.plain)
                    .help("Collapse Full-Screen Session Bar")

                    Button {
                        store.exitFullScreenSession()
                    } label: {
                        Label("Exit Full Screen", systemImage: "arrow.down.right.and.arrow.up.left")
                            .font(.caption.weight(.semibold))
                    }
                    .controlSize(.small)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(.bar)
                Divider()
            } else {
                HStack(spacing: 8) {
                    Text("\(activeTab?.title ?? "") • \(allTabs.count) active")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button {
                        isToolbarExpanded = true
                    } label: {
                        Label("Sessions & Folders", systemImage: "chevron.down")
                            .font(.caption2)
                    }
                    .buttonStyle(.plain)

                    Button {
                        store.exitFullScreenSession()
                    } label: {
                        Image(systemName: "arrow.down.right.and.arrow.up.left")
                            .font(.caption2)
                    }
                    .buttonStyle(.plain)
                    .help("Exit Full Screen")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 3)
                .background(.bar)
                Divider()
            }

            if let activeTab {
                SessionCanvasRouterView(tab: activeTab)
                    .id(activeTab.id)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }
}

private struct FullScreenFolderLauncherPopover: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Binding var isPresented: Bool
    @State private var filterQuery: String = ""
    @State private var collapsedFolderIds: Set<String> = []

    var body: some View {
        let trimmed = filterQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label("Connect to Server", systemImage: "folder.fill")
                    .font(.headline)
                Spacer()
                Text("Double-click or ▶ to connect")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            TextField("Filter folders or servers…", text: $filterQuery)
                .textFieldStyle(.roundedBorder)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    if !trimmed.isEmpty {
                        let matching = store.activeConnections.filter {
                            $0.name.lowercased().contains(trimmed) || $0.host.lowercased().contains(trimmed)
                        }
                        ForEach(matching) { conn in
                            connectionLauncherRow(conn, depth: 0)
                        }
                    } else {
                        let rootFolders = store.activeFolders.filter { $0.parentFolderId == nil }
                        ForEach(rootFolders) { folder in
                            AnyView(folderSubtreeRows(folder: folder, depth: 0))
                        }
                        let rootConnections = store.activeConnections
                            .filter { $0.parentFolderId == nil }
                            .sorted { $0.sortOrder < $1.sortOrder }
                        ForEach(rootConnections) { conn in
                            connectionLauncherRow(conn, depth: 0)
                        }

                        if !store.activeSmartFolders.isEmpty {
                            Divider().padding(.vertical, 4)
                            Text("Intelligent Folders")
                                .font(.caption2.weight(.bold))
                                .foregroundStyle(.secondary)
                                .padding(.horizontal, 6)
                            ForEach(store.activeSmartFolders) { sf in
                                let sfKey = "sf:\(sf.id)"
                                let expanded = !collapsedFolderIds.contains(sfKey)
                                let matches = PropertyInheritanceResolver.evaluateSmartFolder(sf, in: store.document)
                                Button {
                                    if expanded {
                                        collapsedFolderIds.insert(sfKey)
                                    } else {
                                        collapsedFolderIds.remove(sfKey)
                                    }
                                } label: {
                                    HStack(spacing: 6) {
                                        Image(systemName: expanded ? "chevron.down" : "chevron.right")
                                            .font(.caption2)
                                        Image(systemName: "sparkles.rectangle.stack")
                                            .foregroundStyle(.purple)
                                        Text("\(sf.name) (\(matches.count))")
                                            .font(.callout.weight(.medium))
                                        Spacer()
                                    }
                                    .padding(.vertical, 4)
                                    .padding(.horizontal, 6)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)

                                if expanded {
                                    ForEach(matches) { conn in
                                        connectionLauncherRow(conn, depth: 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .frame(width: 380, height: 400)
        }
        .padding(12)
    }

    @ViewBuilder
    private func folderSubtreeRows(folder: VaultFolder, depth: Int) -> some View {
        let expanded = !collapsedFolderIds.contains(folder.id)
        let childFolders = store.activeFolders.filter { $0.parentFolderId == folder.id }
        let childConns = store.activeConnections
            .filter { $0.parentFolderId == folder.id }
            .sorted { $0.sortOrder < $1.sortOrder }

        Button {
            if expanded {
                collapsedFolderIds.insert(folder.id)
            } else {
                collapsedFolderIds.remove(folder.id)
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: expanded ? "chevron.down" : "chevron.right")
                    .font(.caption2)
                Image(systemName: "folder.fill")
                    .foregroundStyle(Color.accentColor)
                Text(folder.name)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.leading, CGFloat(depth) * 14)
            .padding(.vertical, 5)
            .padding(.horizontal, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)

        if expanded {
            ForEach(childFolders) { sub in
                AnyView(folderSubtreeRows(folder: sub, depth: depth + 1))
            }
            ForEach(childConns) { conn in
                connectionLauncherRow(conn, depth: depth + 1)
            }
        }
    }

    private func connectionLauncherRow(_ conn: VaultConnection, depth: Int) -> some View {
        let isSelected = store.selectedConnectionId == conn.id
        return HStack(spacing: 8) {
            Text(conn.name)
                .font(.system(size: 14, weight: isSelected ? .semibold : .medium))
                .foregroundStyle(.primary)
                .lineLimit(1)
            Spacer()
            Text(conn.protocolType.shortBadge)
                .font(.system(size: 9.5, weight: .regular, design: .monospaced))
                .foregroundStyle(.tertiary)
            Button {
                store.openConnection(conn)
                isPresented = false
            } label: {
                Image(systemName: "play.fill")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
            .help("Connect to \(conn.name)")
        }
        .padding(.leading, CGFloat(depth) * 14 + 14)
        .padding(.vertical, 5)
        .padding(.horizontal, 6)
        .background(
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(isSelected ? Color.accentColor.opacity(0.18) : Color.clear)
        )
        .contentShape(Rectangle())
        .onTapGesture(count: 2) {
            store.openConnection(conn)
            isPresented = false
        }
        .simultaneousGesture(
            TapGesture(count: 1).onEnded {
                store.selectedConnectionId = conn.id
            }
        )
    }
}

