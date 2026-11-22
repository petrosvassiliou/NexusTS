import AppKit
import NexusTSCore
import SwiftUI
import UniformTypeIdentifiers

public struct NexusMainWindowView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow

    @State private var pendingEncryptedImportURL: URL? = nil
    @State private var importArchivePassword: String = ""
    @State private var exportPayload: ExportedFileDocument? = nil
    @State private var exportFormat: VaultExportFormat = .nxts
    @State private var isExportingVaultFile = false
    @State private var bannerAlertMessage: String? = nil

    public init() {}

    public var body: some View {
        ZStack {
            Color(red: 0.973, green: 0.980, blue: 0.988)
                .ignoresSafeArea()

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
                VStack(spacing: 0) {
                    GooglebookDesktopTopBar(
                        onImportVault: {
                            store.isPresentingImportFilePicker = true
                        },
                        onExportVault: { fmt in
                            do {
                                let data = try VaultDocumentExporter.export(document: store.document, format: fmt)
                                exportFormat = fmt
                                exportPayload = ExportedFileDocument(data: data)
                                isExportingVaultFile = true
                            } catch {
                                store.reportError(error, operationTitle: "Vault Export Failed")
                            }
                        }
                    )

                    Divider()

                    HStack(spacing: 0) {
                        if store.columnVisibility != .detailOnly {
                            SidebarHierarchyView()
                                .frame(width: 242)
                                .background(Color.white)
                            Divider()
                        }

                        workspaceDetailCanvas
                            .frame(maxWidth: .infinity, maxHeight: .infinity)

                        if store.isInspectorPresented && store.activeSection == .sessions {
                            Divider()
                            InspectorColumnView()
                                .frame(width: 295)
                                .background(Color.white)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
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
        .sheet(isPresented: $store.isPresentingSoftwareUpdateSheet) {
            MacSoftwareUpdateSheet()
                .environmentObject(store)
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
            isPresented: $store.isPresentingImportFilePicker,
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
                    Image(systemName: "arrow.triangle.2.circlepath.circle.fill")
                        .foregroundStyle(Color(nsColor: .systemBlue))

                    if store.updateInstallPhase.isBusy {
                        Text(store.updateInstallPhase.statusText)
                            .font(.callout.weight(.medium))
                            .lineLimit(1)
                    } else {
                        Text("NexusTS \(update.tagName) is ready to install inside the app (no DMG required).")
                            .font(.callout.weight(.medium))
                            .lineLimit(1)
                    }

                    Spacer()

                    if case .downloading(let progress) = store.updateInstallPhase {
                        ProgressView(value: min(1.0, max(0.0, progress)))
                            .progressViewStyle(.linear)
                            .frame(width: 120)
                    } else if store.updateInstallPhase.isBusy {
                        ProgressView()
                            .controlSize(.small)
                    }

                    Button("Update & Relaunch") {
                        Task { await store.installAvailableUpdateAndRelaunch() }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                    .disabled(store.updateInstallPhase.isBusy)

                    Button("Details…") {
                        store.isPresentingSoftwareUpdateSheet = true
                    }
                    .controlSize(.small)

                    if !store.updateInstallPhase.isBusy {
                        Button {
                            store.updateCandidate = nil
                        } label: {
                            Image(systemName: "xmark")
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(.bar)
                Divider()
            }

            if store.activeSection == .sessions {
                SplitPaneWorkspaceView()
            } else {
                UnifiedWorkspaceSettingsView(showsWorkspaceHeader: true)
            }

            if store.isBottomConsolePresented {
                Divider()
                BottomAuditAndBroadcastConsole()
                    .frame(height: 175)
            }
        }
}

// MARK: - 1:1 Googlebook Top Workspace Bar (`DesktopTopWindowChrome`, 58pt)

private struct GooglebookDesktopTopBar: View {
    @EnvironmentObject private var store: WorkspaceStore
    let onImportVault: () -> Void
    let onExportVault: (VaultExportFormat) -> Void

    @State private var isCreateMenuPresented = false
    @State private var isLangMenuPresented = false
    @State private var isToolsMenuPresented = false

    private static let royalSapphire = Color(red: 0.114, green: 0.306, blue: 0.847)
    private static let royalSapphireHover = Color(red: 0.094, green: 0.255, blue: 0.725)
    private static let surfaceLow = Color(red: 0.945, green: 0.961, blue: 0.976)
    private static let activeContainer = Color(red: 0.859, green: 0.918, blue: 0.996)
    private static let activeOnContainer = Color(red: 0.118, green: 0.227, blue: 0.541)
    private static let subtleBorder = Color.black.opacity(0.09)

    private var showsVaultPill: Bool {
        let trimmed = store.document.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmed.isEmpty && trimmed != "NexusTS Enterprise Vault" && trimmed != "NexusTS Vault"
    }

    var body: some View {
        HStack(spacing: 12) {
            // Left Cluster: Morphing Menu + Emblem + "NexusTS" + Vault Pill + Settings Pill + Return Chip
            HStack(spacing: 10) {
                GooglebookMorphIconButton(
                    systemName: "line.3.horizontal",
                    isActive: store.columnVisibility != .detailOnly,
                    accessibilityId: "nexusts.toolbar.toggleSidebar",
                    tooltip: MacWorkspaceShortcut.toggleSidebar.title(in: store.strings),
                    shortcut: MacWorkspaceShortcut.toggleSidebar.keycap
                ) {
                    store.toggleSidebarVisibility()
                }

                HStack(spacing: 9) {
                    Image(nsImage: NSApp.applicationIconImage)
                        .resizable()
                        .interpolation(.high)
                        .frame(width: 30, height: 30)
                        .clipShape(RoundedRectangle(cornerRadius: 8.5, style: .continuous))

                    Text("NexusTS")
                        .font(.system(size: 15.5, weight: .semibold))
                        .foregroundStyle(Color(red: 0.059, green: 0.090, blue: 0.165))
                }
                .padding(.trailing, 2)

                if showsVaultPill {
                    Text(store.document.name)
                        .font(.system(size: 11.5, weight: .medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .padding(.horizontal, 11)
                        .padding(.vertical, 5)
                        .background(Capsule(style: .continuous).fill(Self.surfaceLow))
                        .frame(maxWidth: 160)
                }

                Button {
                    store.toggleUnifiedSettings()
                } label: {
                    HStack(spacing: 7) {
                        Image(systemName: "gearshape")
                            .font(.system(size: 14, weight: .medium))
                        Text(store.strings.settings)
                            .font(.system(size: 12.5, weight: store.activeSection == .sessions ? .medium : .semibold))
                            .lineLimit(1)
                    }
                    .foregroundStyle(store.activeSection == .sessions ? Color.primary : Self.activeOnContainer)
                    .padding(.horizontal, 14)
                    .frame(height: 38)
                    .background(
                        Capsule(style: .continuous)
                            .fill(store.activeSection == .sessions ? Self.surfaceLow : Self.activeContainer)
                    )
                    .overlay(
                        Capsule(style: .continuous)
                            .strokeBorder(
                                store.activeSection == .sessions
                                    ? Self.subtleBorder
                                    : Self.royalSapphire.opacity(0.32),
                                lineWidth: 1
                            )
                    )
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("nexusts.toolbar.settings")
                .discreetTooltip(store.strings.settings, shortcut: "⌘,")

                if store.activeSection != .sessions {
                    Button {
                        store.selectSection(.sessions)
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "arrow.left")
                                .font(.system(size: 12.5, weight: .semibold))
                            Text(store.strings.connections)
                                .font(.system(size: 12, weight: .medium))
                                .lineLimit(1)
                        }
                        .foregroundStyle(Color.primary)
                        .padding(.horizontal, 12)
                        .frame(height: 34)
                        .background(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(Color.white)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .strokeBorder(Color.black.opacity(0.14), lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("nexusts.toolbar.backConnections")
                    .discreetTooltip(store.strings.connections, shortcut: "⌘1")
                }
            }

            Spacer(minLength: 8)

            // Center Cluster: Sleek 38pt Quick-Connect Trigger Pill (`⌘K`)
            Button {
                store.toggleQuickConnect()
            } label: {
                HStack(spacing: 9) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 13.5, weight: .medium))
                        .foregroundStyle(.secondary)
                    Text(store.strings.quickConnectTitle)
                        .font(.system(size: 12.5, weight: .medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    KeycapBadge("⌘K")
                }
                .padding(.horizontal, 13)
                .frame(minWidth: 190, idealWidth: 300, maxWidth: 340)
                .frame(height: 38)
                .background(Capsule(style: .continuous).fill(Self.surfaceLow))
                .overlay(Capsule(style: .continuous).strokeBorder(Self.subtleBorder, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("nexusts.toolbar.quickConnect")
            .discreetTooltip(
                MacWorkspaceShortcut.quickConnect.title(in: store.strings),
                shortcut: MacWorkspaceShortcut.quickConnect.keycap
            )

            Spacer(minLength: 8)

            // Right Cluster: Split-Action Primary Button + Synced Chip + Flag Language Picker + Inspector + Lock + Tools Menu
            HStack(spacing: 8) {
                HStack(spacing: 3) {
                    Button {
                        store.presentNewConnectionSheet()
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "plus")
                                .font(.system(size: 13, weight: .bold))
                            Text(store.strings.newConnection)
                                .font(.system(size: 12.5, weight: .semibold))
                                .lineLimit(1)
                        }
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .frame(height: 40)
                        .background(
                            UnevenRoundedRectangle(
                                topLeadingRadius: 20,
                                bottomLeadingRadius: 20,
                                bottomTrailingRadius: 6,
                                topTrailingRadius: 6,
                                style: .continuous
                            )
                            .fill(Self.royalSapphire)
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("nexusts.toolbar.newConnection")
                    .discreetTooltip(
                        MacWorkspaceShortcut.newConnection.title(in: store.strings),
                        shortcut: MacWorkspaceShortcut.newConnection.keycap
                    )

                    Button {
                        isCreateMenuPresented.toggle()
                    } label: {
                        Image(systemName: "chevron.down")
                            .font(.system(size: 11.5, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 35, height: 40)
                            .background(
                                UnevenRoundedRectangle(
                                    topLeadingRadius: isCreateMenuPresented ? 12 : 6,
                                    bottomLeadingRadius: isCreateMenuPresented ? 12 : 6,
                                    bottomTrailingRadius: isCreateMenuPresented ? 12 : 20,
                                    topTrailingRadius: isCreateMenuPresented ? 12 : 20,
                                    style: .continuous
                                )
                                .fill(isCreateMenuPresented ? Self.royalSapphireHover : Self.royalSapphire)
                            )
                    }
                    .buttonStyle(.plain)
                    .popover(isPresented: $isCreateMenuPresented, arrowEdge: .bottom) {
                        VStack(alignment: .leading, spacing: 2) {
                            GooglebookMenuRowButton(title: store.strings.newConnection, symbol: "plus", shortcut: "⌘N") {
                                isCreateMenuPresented = false
                                store.presentNewConnectionSheet()
                            }
                            GooglebookMenuRowButton(title: store.strings.newFolder, symbol: "folder.badge.plus", shortcut: "⇧⌘N") {
                                isCreateMenuPresented = false
                                store.presentNewFolderSheet()
                            }
                            GooglebookMenuRowButton(title: store.strings.newSmartFolder, symbol: "sparkles.rectangle.stack") {
                                isCreateMenuPresented = false
                                store.isPresentingNewSmartFolderSheet = true
                            }
                            GooglebookMenuRowButton(title: store.strings.newCredential, symbol: "key") {
                                isCreateMenuPresented = false
                                store.isPresentingNewCredentialSheet = true
                            }
                            GooglebookMenuRowButton(title: store.strings.newTemplate, symbol: "bookmark") {
                                isCreateMenuPresented = false
                                store.openUnifiedSettings(tab: .templates)
                            }
                            Divider().padding(.vertical, 4)
                            GooglebookMenuRowButton(title: store.strings.localTerminal, symbol: "terminal", shortcut: "⌘T") {
                                isCreateMenuPresented = false
                                store.openLocalTerminal()
                            }
                            GooglebookMenuRowButton(title: store.strings.importShortCta, symbol: "square.and.arrow.up") {
                                isCreateMenuPresented = false
                                onImportVault()
                            }
                            Divider().padding(.vertical, 4)
                            ForEach(VaultExportFormat.allCases) { fmt in
                                GooglebookMenuRowButton(title: fmt.displayTitle, symbol: "square.and.arrow.down") {
                                    isCreateMenuPresented = false
                                    onExportVault(fmt)
                                }
                            }
                        }
                        .padding(6)
                        .frame(width: 268)
                    }
                }

                if store.hasConfiguredCloudSync {
                    Button {
                        Task { await store.syncCloudNow(reportErrors: true) }
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "icloud.and.arrow.up")
                                .font(.system(size: 13, weight: .medium))
                            Text(store.strings.syncedRevLabel(store.document.revision))
                                .font(.system(size: 11.5, weight: .medium))
                                .lineLimit(1)
                        }
                        .foregroundStyle(Color(red: 0.016, green: 0.471, blue: 0.341))
                        .padding(.horizontal, 11)
                        .frame(height: 34)
                        .background(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(Color(red: 0.016, green: 0.471, blue: 0.341).opacity(0.08))
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .strokeBorder(Color(red: 0.016, green: 0.471, blue: 0.341).opacity(0.24), lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("nexusts.toolbar.syncNow")
                    .discreetTooltip(
                        MacWorkspaceShortcut.syncNow.title(in: store.strings),
                        shortcut: MacWorkspaceShortcut.syncNow.keycap
                    )
                }

                if store.broadcastInputEnabled {
                    GooglebookMorphIconButton(
                        systemName: "dot.radiowaves.left.and.right",
                        isActive: true,
                        accessibilityId: "nexusts.toolbar.broadcastInput",
                        tooltip: MacWorkspaceShortcut.broadcastInput.title(in: store.strings),
                        shortcut: MacWorkspaceShortcut.broadcastInput.keycap
                    ) {
                        store.broadcastInputEnabled.toggle()
                    }
                }

                // Vector Flag Language Picker Pill (`[ 🇩🇪 DE ▾ ]`)
                Button {
                    isLangMenuPresented.toggle()
                } label: {
                    HStack(spacing: 7) {
                        MacLanguageFlagBadge(code: store.appLanguage.resolvedCode)
                        Text(store.appLanguage.resolvedCode.uppercased())
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.primary)
                        Image(systemName: "chevron.down")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 11)
                    .frame(height: 40)
                    .background(Capsule(style: .continuous).fill(Self.surfaceLow))
                    .overlay(Capsule(style: .continuous).strokeBorder(Self.subtleBorder, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .popover(isPresented: $isLangMenuPresented, arrowEdge: .bottom) {
                    let languageChoices: [(NexusAppLanguage, String, String)] = [
                        (.de, "de", "Deutsch"),
                        (.en, "en", "English"),
                        (.fr, "fr", "Français"),
                        (.it, "it", "Italiano"),
                        (.es, "es", "Español"),
                        (.el, "el", "Ελληνικά")
                    ]
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(languageChoices, id: \.1) { lang, code, label in
                            let selected = store.appLanguage.resolvedCode == code
                            Button {
                                store.appLanguage = lang
                                isLangMenuPresented = false
                            } label: {
                                HStack(spacing: 10) {
                                    MacLanguageFlagBadge(code: code)
                                    Text(label)
                                        .font(.system(size: 12.5, weight: selected ? .semibold : .regular))
                                        .foregroundStyle(.primary)
                                    Spacer()
                                    if selected {
                                        Image(systemName: "checkmark")
                                            .font(.system(size: 11.5, weight: .bold))
                                            .foregroundStyle(Self.royalSapphire)
                                    }
                                }
                                .padding(.horizontal, 10)
                                .padding(.vertical, 7)
                                .background(
                                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                                        .fill(selected ? Self.royalSapphire.opacity(0.08) : Color.clear)
                                )
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(6)
                    .frame(width: 196)
                }

                GooglebookMorphIconButton(
                    systemName: "info.circle",
                    isActive: store.isInspectorPresented,
                    accessibilityId: "nexusts.toolbar.toggleInspector",
                    tooltip: MacWorkspaceShortcut.toggleInspector.title(in: store.strings),
                    shortcut: MacWorkspaceShortcut.toggleInspector.keycap
                ) {
                    store.toggleInspector()
                }

                GooglebookMorphIconButton(
                    systemName: "lock.open",
                    isActive: false,
                    accessibilityId: "nexusts.toolbar.lockVault",
                    tooltip: MacWorkspaceShortcut.lockVault.title(in: store.strings),
                    shortcut: MacWorkspaceShortcut.lockVault.keycap
                ) {
                    store.lockVault()
                }

                GooglebookMorphIconButton(
                    systemName: "ellipsis",
                    isActive: isToolsMenuPresented,
                    rotationDegrees: 90,
                    accessibilityId: "nexusts.toolbar.toolsMenu",
                    tooltip: store.strings.studios
                ) {
                    isToolsMenuPresented.toggle()
                }
                .popover(isPresented: $isToolsMenuPresented, arrowEdge: .bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        GooglebookMenuRowButton(title: store.strings.updatesSettings, symbol: "arrow.down.circle") {
                            isToolsMenuPresented = false
                            store.isPresentingSoftwareUpdateSheet = true
                            Task { await store.checkGitHubUpdates(manual: true) }
                        }
                        GooglebookMenuRowButton(
                            title: store.strings.auditConsole,
                            symbol: "terminal",
                            isChecked: store.isBottomConsolePresented
                        ) {
                            isToolsMenuPresented = false
                            store.isBottomConsolePresented.toggle()
                        }
                        GooglebookMenuRowButton(
                            title: "Remote Key Capture",
                            symbol: "keyboard",
                            isChecked: store.remoteKeyCaptureEnabled
                        ) {
                            isToolsMenuPresented = false
                            store.remoteKeyCaptureEnabled.toggle()
                        }
                        GooglebookMenuRowButton(
                            title: store.strings.broadcastInput,
                            symbol: "dot.radiowaves.left.and.right",
                            isChecked: store.broadcastInputEnabled
                        ) {
                            isToolsMenuPresented = false
                            store.broadcastInputEnabled.toggle()
                        }
                        if !store.paneTree.allTabs.isEmpty {
                            Divider().padding(.vertical, 4)
                            GooglebookMenuRowButton(title: store.strings.splitRight, symbol: "rectangle.split.2x1", shortcut: "⌘D") {
                                isToolsMenuPresented = false
                                store.splitFocusedPane(.horizontal)
                            }
                            GooglebookMenuRowButton(title: store.strings.splitDown, symbol: "rectangle.split.1x2", shortcut: "⇧⌘D") {
                                isToolsMenuPresented = false
                                store.splitFocusedPane(.vertical)
                            }
                        }
                        Divider().padding(.vertical, 4)
                        GooglebookMenuRowButton(title: store.strings.resetDatabase, symbol: "trash", isDestructive: true) {
                            isToolsMenuPresented = false
                            store.isPresentingResetDatabaseSheet = true
                        }
                    }
                    .padding(6)
                    .frame(width: 256)
                }
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 58)
        .background(Color.white)
    }
}

private struct GooglebookMorphIconButton: View {
    let systemName: String
    let isActive: Bool
    var rotationDegrees: Double = 0
    var accessibilityId: String = ""
    var tooltip: String = ""
    var shortcut: String? = nil
    let action: () -> Void

    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 15.5, weight: .medium))
                .rotationEffect(.degrees(rotationDegrees))
                .foregroundStyle(
                    isActive
                        ? Color(red: 0.118, green: 0.227, blue: 0.541)
                        : (isHovered ? Color.primary : Color.secondary)
                )
                .frame(width: 40, height: 40)
                .background(
                    RoundedRectangle(cornerRadius: isActive ? 13 : 20, style: .continuous)
                        .fill(
                            isActive
                                ? Color(red: 0.859, green: 0.918, blue: 0.996)
                                : (isHovered ? Color(red: 0.945, green: 0.961, blue: 0.976) : Color.clear)
                        )
                )
                .overlay(
                    RoundedRectangle(cornerRadius: isActive ? 13 : 20, style: .continuous)
                        .strokeBorder(
                            isActive ? Color(red: 0.114, green: 0.306, blue: 0.847).opacity(0.24) : Color.clear,
                            lineWidth: 1
                        )
                )
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityId)
        .discreetTooltip(tooltip, shortcut: shortcut)
        .onHover { hovering in
            withAnimation(.easeOut(duration: 0.14)) {
                isHovered = hovering
            }
        }
    }
}

private struct GooglebookMenuRowButton: View {
    let title: String
    let symbol: String
    var shortcut: String? = nil
    var isChecked: Bool = false
    var isDestructive: Bool = false
    let action: () -> Void

    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: symbol)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(isDestructive ? Color.red : Color.secondary)
                    .frame(width: 18)
                Text(title)
                    .font(.system(size: 12.5, weight: isChecked ? .semibold : .regular))
                    .foregroundStyle(isDestructive ? Color.red : Color.primary)
                    .lineLimit(1)
                Spacer(minLength: 8)
                if isChecked {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11.5, weight: .bold))
                        .foregroundStyle(Color(red: 0.114, green: 0.306, blue: 0.847))
                } else if let shortcut {
                    Text(shortcut)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(
                        isHovered
                            ? (isDestructive ? Color.red.opacity(0.10) : Color(red: 0.937, green: 0.965, blue: 1.0))
                            : Color.clear
                    )
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
    }
}

// Crisp 24x16 Vector Flag Badge matching Googlebook `NexusLanguageFlagBadge`
struct MacLanguageFlagBadge: View {
    let code: String

    var body: some View {
        Canvas { context, size in
            let w = size.width
            let h = size.height
            switch code.lowercased() {
            case "de":
                context.fill(Path(CGRect(x: 0, y: 0, width: w, height: h / 3)), with: .color(Color(red: 0.09, green: 0.09, blue: 0.11)))
                context.fill(Path(CGRect(x: 0, y: h / 3, width: w, height: h / 3)), with: .color(Color(red: 0.87, green: 0.0, blue: 0.0)))
                context.fill(Path(CGRect(x: 0, y: 2 * h / 3, width: w, height: h / 3)), with: .color(Color(red: 1.0, green: 0.81, blue: 0.0)))
            case "fr":
                context.fill(Path(CGRect(x: 0, y: 0, width: w / 3, height: h)), with: .color(Color(red: 0.0, green: 0.14, blue: 0.58)))
                context.fill(Path(CGRect(x: w / 3, y: 0, width: w / 3, height: h)), with: .color(.white))
                context.fill(Path(CGRect(x: 2 * w / 3, y: 0, width: w / 3, height: h)), with: .color(Color(red: 0.93, green: 0.16, blue: 0.22)))
            case "it":
                context.fill(Path(CGRect(x: 0, y: 0, width: w / 3, height: h)), with: .color(Color(red: 0.0, green: 0.57, blue: 0.27)))
                context.fill(Path(CGRect(x: w / 3, y: 0, width: w / 3, height: h)), with: .color(.white))
                context.fill(Path(CGRect(x: 2 * w / 3, y: 0, width: w / 3, height: h)), with: .color(Color(red: 0.81, green: 0.17, blue: 0.22)))
            case "es":
                context.fill(Path(CGRect(x: 0, y: 0, width: w, height: h * 0.25)), with: .color(Color(red: 0.67, green: 0.08, blue: 0.11)))
                context.fill(Path(CGRect(x: 0, y: h * 0.25, width: w, height: h * 0.5)), with: .color(Color(red: 0.95, green: 0.75, blue: 0.0)))
                context.fill(Path(CGRect(x: 0, y: h * 0.75, width: w, height: h * 0.25)), with: .color(Color(red: 0.67, green: 0.08, blue: 0.11)))
            case "el":
                let blue = Color(red: 0.05, green: 0.37, blue: 0.69)
                context.fill(Path(CGRect(x: 0, y: 0, width: w, height: h)), with: .color(blue))
                let stripeH = h / 9.0
                for idx in stride(from: 1, to: 9, by: 2) {
                    context.fill(Path(CGRect(x: 0, y: CGFloat(idx) * stripeH, width: w, height: stripeH)), with: .color(.white))
                }
                let canton = stripeH * 5.0
                context.fill(Path(CGRect(x: 0, y: 0, width: canton, height: canton)), with: .color(blue))
                context.fill(Path(CGRect(x: canton * 0.4, y: 0, width: canton * 0.2, height: canton)), with: .color(.white))
                context.fill(Path(CGRect(x: 0, y: canton * 0.4, width: canton, height: canton * 0.2)), with: .color(.white))
            default:
                context.fill(Path(CGRect(x: 0, y: 0, width: w, height: h)), with: .color(Color(red: 0.0, green: 0.13, blue: 0.41)))
                var diag = Path()
                diag.move(to: .zero)
                diag.addLine(to: CGPoint(x: w, y: h))
                diag.move(to: CGPoint(x: w, y: 0))
                diag.addLine(to: CGPoint(x: 0, y: h))
                context.stroke(diag, with: .color(.white), lineWidth: 2.8)
                context.stroke(diag, with: .color(Color(red: 0.78, green: 0.06, blue: 0.18)), lineWidth: 1.4)
                context.fill(Path(CGRect(x: w * 0.41, y: 0, width: w * 0.18, height: h)), with: .color(.white))
                context.fill(Path(CGRect(x: 0, y: h * 0.36, width: w, height: h * 0.28)), with: .color(.white))
                context.fill(Path(CGRect(x: w * 0.44, y: 0, width: w * 0.12, height: h)), with: .color(Color(red: 0.78, green: 0.06, blue: 0.18)))
                context.fill(Path(CGRect(x: 0, y: h * 0.42, width: w, height: h * 0.16)), with: .color(Color(red: 0.78, green: 0.06, blue: 0.18)))
            }
        }
        .frame(width: 24, height: 16)
        .clipShape(RoundedRectangle(cornerRadius: 3.5, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 3.5, style: .continuous)
                .strokeBorder(Color.black.opacity(0.15), lineWidth: 0.65)
        )
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
                                    adHocPassword: adHoc.password,
                                    adHocDomain: adHoc.domain
                                )
                                store.isQuickConnectPresented = false
                            } label: {
                                HStack(spacing: 10) {
                                    ProtocolSquircleIcon(protocolType: adHoc.protocolType, size: 28)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("Instant Connect to \(adHoc.displayName)")
                                            .font(.body.weight(.medium))
                                        Text(verbatim: "\(adHoc.protocolType.displayName) • Port \(String(adHoc.port))")
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
                    Label("URI Schemes: ssh:// rdp:// vnc:// ard:// sftp:// ftp:// ftps:// scp:// telnet:// serial:// k8s:// docker://", systemImage: "link")
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
                adHocPassword: adHoc.password,
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
                        Text(store.strings.resetDatabase)
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
                                .contextMenu {
                                    Button {
                                        store.closeTab(tab.id)
                                    } label: {
                                        Label(store.strings.disconnectSession, systemImage: "xmark.circle")
                                    }
                                    if !tab.isLocalShell && (
                                        tab.connection.protocolType == .RDP ||
                                        tab.connection.protocolType == .WINDOWS_TERMINAL_SERVICES
                                    ) {
                                        Button(role: .destructive) {
                                            store.logOffTabSession(tab.id)
                                        } label: {
                                            Label(store.strings.logOffSession, systemImage: "rectangle.portrait.and.arrow.right")
                                        }
                                    }
                                }
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
                    .discreetTooltip("Collapse Full-Screen Session Bar")

                    Button {
                        store.exitFullScreenSession()
                    } label: {
                        Label("Exit Full Screen", systemImage: "arrow.down.right.and.arrow.up.left")
                            .font(.caption.weight(.semibold))
                    }
                    .controlSize(.small)
                    .discreetTooltip("Exit Full Screen", shortcut: "⌃⌘F")
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
                    .discreetTooltip("Exit Full Screen", shortcut: "⌃⌘F")
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
        return HStack(spacing: 6) {
            SidebarConnectionTypeIcon(
                protocolType: conn.protocolType,
                customHex: conn.inheritable.displayColorAccentHex,
                size: 15
            )
            Text(conn.name)
                .font(.system(size: 14, weight: isSelected ? .semibold : .medium))
                .foregroundStyle(.primary)
                .lineLimit(1)
            Spacer()
            Button {
                store.openConnection(conn)
                isPresented = false
            } label: {
                Image(systemName: "play.fill")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
            .discreetTooltip("Connect to \(conn.name)")
        }
        .padding(.leading, CGFloat(depth) * 14 + 16)
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

