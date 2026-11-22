import AppKit
import NexusTSCore
import SwiftUI
import UniformTypeIdentifiers

// MARK: - 1. Vault Overview Dashboard (1:1 Googlebook `ExpressiveVaultOverviewDashboard`)

public struct VaultOverviewDashboardView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @State private var showAddTodoComposer: Bool = false
    @State private var newTodoTitle: String = ""

    private static let royalSapphire = Color(red: 0.114, green: 0.306, blue: 0.847)
    private static let surfaceLow = Color(red: 0.945, green: 0.961, blue: 0.976)
    private static let subtleBorder = Color.black.opacity(0.08)

    public init() {}

    public var body: some View {
        let conns = store.activeConnections
        let todos = store.document.todoItems.filter { !store.document.tombstonedObjectIds.contains($0.id) }
        let templates = store.document.templates.filter { !store.document.tombstonedObjectIds.contains($0.id) }
        let favorites = conns.filter(\.favorite)
        let activeTabsCount = store.paneTree.allTabs.count + store.detachedWindowTabs.count
        let liveConnIds = Set(
            store.paneTree.allTabs.map(\.connection.id) + store.detachedWindowTabs.values.map(\.connection.id)
        )

        if conns.isEmpty && todos.isEmpty && !showAddTodoComposer {
            // Centered M3 Welcome Card (`ExpressiveVaultOverviewDashboard` empty state)
            GeometryReader { geo in
                ScrollView {
                    VStack {
                        VStack(spacing: 20) {
                            Image(nsImage: NSApp.applicationIconImage)
                                .resizable()
                                .interpolation(.high)
                                .frame(width: 72, height: 72)
                                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))

                            VStack(spacing: 8) {
                                Text(store.strings.emptyWorkspaceTitle)
                                    .font(.system(size: 24, weight: .bold))
                                    .foregroundStyle(Color(red: 0.059, green: 0.090, blue: 0.165))
                                    .multilineTextAlignment(.center)

                                Text(store.strings.emptyWorkspaceHint)
                                    .font(.system(size: 13.5))
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center)
                                    .frame(maxWidth: 470)
                            }

                            HStack(spacing: 10) {
                                GooglebookActionPill(
                                    title: store.strings.newConnection,
                                    symbol: "plus",
                                    style: .primary
                                ) {
                                    store.presentNewConnectionSheet()
                                }

                                GooglebookActionPill(
                                    title: store.strings.quickConnectTitle,
                                    symbol: "magnifyingglass",
                                    style: .tonal
                                ) {
                                    store.toggleQuickConnect()
                                }

                                GooglebookActionPill(
                                    title: store.strings.localTerminal,
                                    symbol: "terminal",
                                    style: .outlined
                                ) {
                                    store.openLocalTerminal()
                                }

                                GooglebookActionPill(
                                    title: store.strings.importShortCta,
                                    symbol: "square.and.arrow.up",
                                    style: .outlined
                                ) {
                                    store.isPresentingImportFilePicker = true
                                }
                            }

                            Divider()

                            HStack(spacing: 8) {
                                ForEach(["RDP", "SSH", "SFTP", "VNC", "HTTPS", "K8s", "Serial"], id: \.self) { proto in
                                    Text(proto)
                                        .font(.system(size: 11, weight: .semibold, design: .monospaced))
                                        .foregroundStyle(.secondary)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 4)
                                        .background(
                                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                                .fill(Self.surfaceLow)
                                        )
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                                .strokeBorder(Self.subtleBorder, lineWidth: 1)
                                        )
                                }
                            }
                        }
                        .padding(.horizontal, 40)
                        .padding(.vertical, 36)
                        .frame(maxWidth: 600)
                        .background(
                            RoundedRectangle(cornerRadius: 28, style: .continuous)
                                .fill(Color.white)
                                .shadow(color: Color.black.opacity(0.05), radius: 16, x: 0, y: 4)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 28, style: .continuous)
                                .strokeBorder(Self.subtleBorder, lineWidth: 1)
                        )
                    }
                    .frame(minWidth: geo.size.width, minHeight: max(420, geo.size.height - 56))
                    .padding(28)
                }
            }
        } else {
            // Populated Launchpad (`ExpressiveVaultOverviewDashboard` populated state)
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    // 1. Hero Command Launchpad Card
                    VStack(alignment: .leading, spacing: 16) {
                        HStack(alignment: .center, spacing: 14) {
                            Image(nsImage: NSApp.applicationIconImage)
                                .resizable()
                                .interpolation(.high)
                                .frame(width: 46, height: 46)
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

                            VStack(alignment: .leading, spacing: 3) {
                                Text(store.document.name)
                                    .font(.system(size: 21, weight: .bold))
                                    .foregroundStyle(Color(red: 0.059, green: 0.090, blue: 0.165))
                                Text("\(conns.count) \(store.strings.connections.lowercased()) • \(activeTabsCount) \(store.strings.activeSessionsLower)")
                                    .font(.system(size: 12, design: .monospaced))
                                    .foregroundStyle(.secondary)
                            }

                            Spacer(minLength: 12)

                            HStack(spacing: 10) {
                                GooglebookActionPill(
                                    title: store.strings.localTerminal,
                                    symbol: "terminal",
                                    style: .outlined
                                ) {
                                    store.openLocalTerminal()
                                }

                                GooglebookActionPill(
                                    title: store.strings.newConnection,
                                    symbol: "plus",
                                    style: .primary
                                ) {
                                    store.presentNewConnectionSheet()
                                }
                            }
                        }

                        // Full-width Quick-Connect Search Trigger Bar (`⌘K`)
                        Button {
                            store.toggleQuickConnect()
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "magnifyingglass")
                                    .font(.system(size: 15, weight: .medium))
                                    .foregroundStyle(Self.royalSapphire)
                                Text(store.strings.quickConnectLaunchpadHint)
                                    .font(.system(size: 13.5))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                Spacer(minLength: 8)
                                KeycapBadge("⌘K")
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                            .background(
                                RoundedRectangle(cornerRadius: 16, style: .continuous)
                                    .fill(Self.surfaceLow)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 16, style: .continuous)
                                    .strokeBorder(Self.subtleBorder, lineWidth: 1)
                            )
                            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        }
                        .buttonStyle(.plain)

                        // Secondary Pills Row
                        HStack(spacing: 8) {
                            if !templates.isEmpty {
                                GooglebookActionPill(
                                    title: "\(store.strings.templates) (\(templates.count))",
                                    symbol: "bookmark",
                                    style: .tonal,
                                    compact: true
                                ) {
                                    store.openUnifiedSettings(tab: .templates)
                                }
                            }

                            GooglebookActionPill(
                                title: store.strings.importShortCta,
                                symbol: "square.and.arrow.up",
                                style: .outlined,
                                compact: true
                            ) {
                                store.isPresentingImportFilePicker = true
                            }

                            if todos.isEmpty && !showAddTodoComposer {
                                Button {
                                    showAddTodoComposer = true
                                } label: {
                                    HStack(spacing: 5) {
                                        Image(systemName: "plus")
                                            .font(.system(size: 11.5, weight: .semibold))
                                        Text(store.strings.addTodoCta)
                                            .font(.system(size: 12, weight: .semibold))
                                    }
                                    .foregroundStyle(Self.royalSapphire)
                                    .padding(.horizontal, 10)
                                    .frame(height: 32)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 22)
                    .background(
                        RoundedRectangle(cornerRadius: 24, style: .continuous)
                            .fill(Color.white)
                            .shadow(color: Color.black.opacity(0.04), radius: 10, x: 0, y: 2)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 24, style: .continuous)
                            .strokeBorder(Self.subtleBorder, lineWidth: 1)
                    )

                    // 2. Pinned Favorites Quick-Launch Grid (`★ Favorites`)
                    if !favorites.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(spacing: 7) {
                                Image(systemName: "star.fill")
                                    .foregroundStyle(Color(red: 0.96, green: 0.62, blue: 0.04))
                                Text(store.strings.favorites)
                                    .font(.system(size: 15, weight: .semibold))
                            }

                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 252), spacing: 12)], spacing: 12) {
                                ForEach(favorites) { conn in
                                    GooglebookFavoriteCard(
                                        conn: conn,
                                        isSelected: store.selectedConnectionId == conn.id,
                                        isLive: liveConnIds.contains(conn.id),
                                        onSelect: { store.selectedConnectionId = conn.id },
                                        onConnect: { store.openConnection(conn) },
                                        onEdit: { store.editingConnection = conn },
                                        onConnectOptions: { store.connectWithOptionsTarget = conn }
                                    )
                                }
                            }
                        }
                    } else {
                        HStack(spacing: 12) {
                            Image(systemName: "star.fill")
                                .foregroundStyle(Color(red: 0.96, green: 0.62, blue: 0.04))
                            Text(store.strings.favoritesEmptyHint)
                                .font(.system(size: 13))
                                .foregroundStyle(.secondary)
                            Spacer()
                        }
                        .padding(.horizontal, 18)
                        .padding(.vertical, 14)
                        .background(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .fill(Color.white)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(Self.subtleBorder, lineWidth: 1)
                        )
                    }

                    // 3. Workspace Checklist Card
                    if !todos.isEmpty || showAddTodoComposer {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack(spacing: 12) {
                                Text(store.strings.todoTitle)
                                    .font(.system(size: 15, weight: .semibold))
                                Spacer()
                                HStack(spacing: 8) {
                                    TextField("\(store.strings.addTodoCta)…", text: $newTodoTitle)
                                        .textFieldStyle(.roundedBorder)
                                        .frame(maxWidth: 280)
                                        .onSubmit {
                                            submitNewTodo()
                                        }
                                    GooglebookActionPill(
                                        title: store.strings.addTodoCta,
                                        symbol: "plus",
                                        style: .primary,
                                        compact: true
                                    ) {
                                        submitNewTodo()
                                    }
                                }
                            }

                            ForEach(todos) { item in
                                HStack(spacing: 10) {
                                    Button {
                                        store.commitMutation("Toggled checklist item") { doc in
                                            if let idx = doc.todoItems.firstIndex(where: { $0.id == item.id }) {
                                                doc.todoItems[idx].completed.toggle()
                                                doc.todoItems[idx].updatedAtEpochMs = VaultClock.nowMs()
                                            }
                                        }
                                    } label: {
                                        Image(systemName: item.completed ? "checkmark.square.fill" : "square")
                                            .font(.system(size: 15))
                                            .foregroundStyle(item.completed ? Self.royalSapphire : .secondary)
                                    }
                                    .buttonStyle(.plain)

                                    Text(item.title)
                                        .font(.system(size: 13))
                                        .strikethrough(item.completed)
                                        .foregroundStyle(item.completed ? .secondary : .primary)

                                    Spacer()

                                    Button {
                                        store.commitMutation("Removed checklist item") { doc in
                                            doc.todoItems.removeAll { $0.id == item.id }
                                        }
                                    } label: {
                                        Image(systemName: "xmark")
                                            .font(.system(size: 11, weight: .semibold))
                                            .foregroundStyle(.secondary)
                                    }
                                    .buttonStyle(.plain)
                                }
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(
                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                        .fill(Self.surfaceLow)
                                )
                            }
                        }
                        .padding(20)
                        .background(
                            RoundedRectangle(cornerRadius: 20, style: .continuous)
                                .fill(Color.white)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 20, style: .continuous)
                                .strokeBorder(Self.subtleBorder, lineWidth: 1)
                        )
                    }
                }
                .frame(maxWidth: 1040)
                .padding(.horizontal, 32)
                .padding(.vertical, 28)
                .frame(maxWidth: .infinity)
            }
            .scrollContentBackground(.hidden)
        }
    }

    private func submitNewTodo() {
        let trimmed = newTodoTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        store.commitMutation("Added checklist item") { doc in
            doc.todoItems.append(VaultTodoItem(title: trimmed))
        }
        newTodoTitle = ""
        showAddTodoComposer = false
    }
}

private struct GooglebookActionPill: View {
    enum Style {
        case primary
        case tonal
        case outlined
    }

    let title: String
    let symbol: String
    let style: Style
    var compact: Bool = false
    let action: () -> Void

    @State private var isHovered = false

    var body: some View {
        let sapphire = Color(red: 0.114, green: 0.306, blue: 0.847)
        let sapphireHover = Color(red: 0.094, green: 0.255, blue: 0.725)
        let bg: Color = {
            switch style {
            case .primary:
                return isHovered ? sapphireHover : sapphire
            case .tonal:
                return isHovered
                    ? Color(red: 0.859, green: 0.918, blue: 0.996)
                    : Color(red: 0.945, green: 0.961, blue: 0.976)
            case .outlined:
                return isHovered ? Color(red: 0.937, green: 0.965, blue: 1.0) : Color.white
            }
        }()
        let fg: Color = style == .primary ? .white : .primary
        let border: Color = {
            switch style {
            case .primary:
                return .clear
            case .tonal:
                return Color.black.opacity(0.08)
            case .outlined:
                return isHovered ? sapphire.opacity(0.38) : Color.black.opacity(0.14)
            }
        }()

        Button(action: action) {
            HStack(spacing: 7) {
                Image(systemName: symbol)
                    .font(.system(size: compact ? 12 : 13, weight: .semibold))
                Text(title)
                    .font(.system(size: compact ? 12 : 12.5, weight: style == .primary ? .semibold : .medium))
                    .lineLimit(1)
            }
            .foregroundStyle(fg)
            .padding(.horizontal, compact ? 12 : 15)
            .frame(height: compact ? 32 : 38)
            .background(Capsule(style: .continuous).fill(bg))
            .overlay(Capsule(style: .continuous).strokeBorder(border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
    }
}

private struct GooglebookFavoriteCard: View {
    let conn: VaultConnection
    let isSelected: Bool
    let isLive: Bool
    let onSelect: () -> Void
    let onConnect: () -> Void
    let onEdit: () -> Void
    let onConnectOptions: () -> Void

    @State private var isHovered = false

    var body: some View {
        let tint = HIGColorUtilities.color(fromHex: conn.inheritable.displayColorAccentHex) ?? conn.protocolType.appleSystemTint
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                SidebarConnectionTypeIcon(
                    protocolType: conn.protocolType,
                    customHex: conn.inheritable.displayColorAccentHex,
                    size: 15
                )
                Text(conn.protocolType.shortBadge)
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundStyle(tint)
                if isLive {
                    Circle()
                        .fill(Color(nsColor: .systemGreen))
                        .frame(width: 6, height: 6)
                }
                Spacer()
                HStack(spacing: 4) {
                    Button(action: onConnectOptions) {
                        Image(systemName: "slider.horizontal.3")
                            .font(.system(size: 11.5))
                            .foregroundStyle(.secondary)
                            .frame(width: 22, height: 22)
                    }
                    .buttonStyle(.plain)
                    Button(action: onEdit) {
                        Image(systemName: "pencil")
                            .font(.system(size: 11.5))
                            .foregroundStyle(.secondary)
                            .frame(width: 22, height: 22)
                    }
                    .buttonStyle(.plain)
                }
            }

            Text(conn.name)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
        }
        .padding(.horizontal, 15)
        .padding(.vertical, 13)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(isSelected ? tint.opacity(0.09) : Color.white)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(
                    isSelected ? tint : tint.opacity(isHovered ? 0.55 : 0.28),
                    lineWidth: 1
                )
        )
        .shadow(color: Color.black.opacity(isHovered ? 0.06 : 0.03), radius: isHovered ? 8 : 3, x: 0, y: 2)
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .onTapGesture(count: 2, perform: onConnect)
        .simultaneousGesture(TapGesture(count: 1).onEnded(onSelect))
        .onHover { isHovered = $0 }
        .contextMenu {
            Button("Connect", action: onConnect)
            Button("Connect with Options…", action: onConnectOptions)
            Button("Edit Connection…", action: onEdit)
        }
        .discreetTooltip("Double-click to connect to \(conn.name)")
    }
}

// MARK: - 2. Templates Studio

public struct TemplatesStudioView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @State private var newTemplateName: String = ""
    @State private var newTemplateProto: VaultProtocolType = .SSH
    @State private var newTemplateDesc: String = ""

    public init() {}

    public var body: some View {
        VStack(spacing: 0) {
            HStack {
                Label("Connection Templates", systemImage: "doc.on.doc.fill")
                    .font(.headline)
                Spacer()
                TextField("Template Name", text: $newTemplateName)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 180)
                Picker("Protocol", selection: $newTemplateProto) {
                    ForEach(VaultProtocolType.allCases) { p in
                        Text(p.displayName).tag(p)
                    }
                }
                .frame(width: 200)
                Button("Add Template") {
                    guard !newTemplateName.trimmingCharacters(in: .whitespaces).isEmpty else { return }
                    let tpl = ConnectionTemplate(
                        name: newTemplateName,
                        description: newTemplateDesc.isEmpty ? "Reusable \(newTemplateProto.displayName) preset" : newTemplateDesc,
                        protocolType: newTemplateProto
                    )
                    store.commitMutation("Created template \(tpl.name)") { doc in
                        doc.templates.append(tpl)
                    }
                    newTemplateName = ""
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(14)
            .background(.bar)

            Divider()

            List {
                ForEach(store.document.templates) { tpl in
                    HStack(spacing: 12) {
                        ProtocolSquircleIcon(protocolType: tpl.protocolType, customHex: tpl.colorAccentHex, size: 30)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(tpl.name).font(.body.weight(.semibold))
                            Text("\(tpl.protocolType.displayName) • \(tpl.description)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Spawn Connection") {
                            let targetFolder = store.selectedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId
                                ? nil
                                : store.selectedFolderId
                            let conn = VaultConnection(
                                parentFolderId: targetFolder,
                                name: "\(tpl.name) Host",
                                host: "",
                                protocolType: tpl.protocolType,
                                inheritable: tpl.inheritable,
                                settings: tpl.settings
                            )
                            store.commitMutation("Spawned connection from template \(tpl.name)") { doc in
                                doc.connections.append(conn)
                            }
                            store.editingConnection = conn
                        }
                        .controlSize(.small)

                        Button(role: .destructive) {
                            store.commitMutation("Removed template \(tpl.name)") { doc in
                                doc.tombstonedObjectIds.insert(tpl.id)
                                doc.templates.removeAll { $0.id == tpl.id }
                            }
                        } label: {
                            Image(systemName: "trash")
                        }
                        .buttonStyle(.borderless)
                    }
                    .padding(.vertical, 4)
                }
            }
        }
    }
}

// MARK: - 3. Credentials, Ed25519 SSH Keygen & TOTP Studio

public struct CredentialsStudioView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @State private var credName: String = ""
    @State private var credUsername: String = "root"
    @State private var credDomain: String = ""
    @State private var credPassword: String = ""
    @State private var credTotpSecret: String = ""
    @State private var revealedCredentialIds: Set<String> = []
    @State private var tickDate: Date = Date()

    private let totpTimer = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()

    public init() {}

    public var body: some View {
        HSplitView {
            List {
                Section("Vault Credentials (\(store.activeCredentials.count))") {
                    ForEach(store.activeCredentials) { cred in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Image(systemName: cred.privateKeyPem.isEmpty ? "key.fill" : "key.radiowaves.forward.fill")
                                    .foregroundStyle(Color.accentColor)
                                Text(cred.name)
                                    .font(.body.weight(.semibold))
                                Spacer()
                                if !cred.totpSecretBase32.isEmpty,
                                   let otp = TotpCodeGenerator.generateTotp(base32Secret: cred.totpSecretBase32, timestamp: tickDate) {
                                    Button {
                                        NSPasteboard.general.clearContents()
                                        NSPasteboard.general.setString(otp, forType: .string)
                                    } label: {
                                        HStack(spacing: 4) {
                                            Text(otp)
                                                .font(.system(.caption, design: .monospaced).weight(.bold))
                                            Text("(\(TotpCodeGenerator.secondsRemaining(at: tickDate))s)")
                                                .font(.caption2.monospacedDigit())
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    .controlSize(.small)
                                    .discreetTooltip("Copy 6-Digit RFC 6238 TOTP Code")
                                }
                            }

                            HStack(spacing: 10) {
                                Text(cred.domain.isEmpty ? cred.username : "\(cred.domain)\\\(cred.username)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)

                                if !cred.keyFingerprintSha256.isEmpty {
                                    Text(cred.keyFingerprintSha256)
                                        .font(.system(size: 10, design: .monospaced))
                                        .foregroundStyle(.tertiary)
                                        .lineLimit(1)
                                }

                                Spacer()

                                Button("Edit…") {
                                    store.editingCredential = cred
                                }
                                .controlSize(.mini)

                                if !cred.publicKeyOpenSsh.isEmpty {
                                    Button("Copy Public Key") {
                                        NSPasteboard.general.clearContents()
                                        NSPasteboard.general.setString(cred.publicKeyOpenSsh, forType: .string)
                                    }
                                    .controlSize(.mini)
                                }

                                if !cred.secretPassword.isEmpty {
                                    Button(revealedCredentialIds.contains(cred.id) ? cred.secretPassword : "Copy Password") {
                                        NSPasteboard.general.clearContents()
                                        NSPasteboard.general.setString(cred.secretPassword, forType: .string)
                                    }
                                    .controlSize(.mini)

                                    Button {
                                        if revealedCredentialIds.contains(cred.id) {
                                            revealedCredentialIds.remove(cred.id)
                                        } else {
                                            revealedCredentialIds.insert(cred.id)
                                        }
                                    } label: {
                                        Image(systemName: revealedCredentialIds.contains(cred.id) ? "eye.slash" : "eye")
                                    }
                                    .buttonStyle(.borderless)
                                }

                                Button(role: .destructive) {
                                    store.deleteCredential(cred.id)
                                } label: {
                                    Image(systemName: "trash")
                                }
                                .buttonStyle(.borderless)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .frame(minWidth: 380)

            Form {
                Section("Create Password / TOTP Credential") {
                    TextField("Profile Name", text: $credName)
                    TextField("Username", text: $credUsername)
                    TextField("AD / NetBIOS Domain (Optional)", text: $credDomain)
                    SecureField("Password / API Token", text: $credPassword)
                    TextField("TOTP Secret (Base32 RFC 6238)", text: $credTotpSecret)

                    Button("Save Vault Credential") {
                        guard !credName.trimmingCharacters(in: .whitespaces).isEmpty else { return }
                        let created = VaultCredential(
                            name: credName,
                            sourceKind: .VAULT_INTERNAL,
                            username: credUsername,
                            domain: credDomain,
                            secretPassword: credPassword,
                            totpSecretBase32: credTotpSecret
                        )
                        store.saveCredential(created)
                        credName = ""
                        credPassword = ""
                        credTotpSecret = ""
                    }
                    .buttonStyle(.borderedProminent)
                }

                Section("Native Rust Ed25519 SSH Keypair Generator") {
                    Text("Generates an OpenSSH Ed25519 keypair in memory via `nexusts-crypto` and stores it encrypted inside the `.nxts` vault.")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Button {
                        do {
                            let pair = try NativeSshCryptoBridge.generateEd25519Keypair(
                                comment: "\(NSUserName())@\(Host.current().localizedName ?? "macOS")"
                            )
                            let sshCred = VaultCredential(
                                name: credName.isEmpty ? "Ed25519 SSH Key (\(pair.keyId.prefix(6)))" : credName,
                                sourceKind: .SSH_KEY_PAIR,
                                username: credUsername.isEmpty ? "root" : credUsername,
                                privateKeyPem: pair.privateKeyOpenSshPem,
                                publicKeyOpenSsh: pair.publicKeyOpenSsh,
                                keyFingerprintSha256: pair.sha256Fingerprint
                            )
                            store.saveCredential(sshCred)
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(pair.publicKeyOpenSsh, forType: .string)
                        } catch {
                            store.reportError(error, operationTitle: "SSH Key Generation Failed")
                        }
                    } label: {
                        Label("Generate Ed25519 Keypair & Copy Public Key", systemImage: "wand.and.stars")
                    }
                }
            }
            .formStyle(.grouped)
            .frame(minWidth: 340)
        }
        .onReceive(totpTimer) { now in
            tickDate = now
        }
    }
}

// MARK: - 4. Secure Gateways & Bastion Health Studio

public struct GatewaysStudioView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @State private var gwName: String = ""
    @State private var gwHost: String = ""
    @State private var gwPort: Int = 22
    @State private var gwKind: GatewayTransportKind = .SSH_TUNNEL_BASTION
    @State private var gatewayHealthById: [String: String] = [:]

    public init() {}

    public var body: some View {
        HSplitView {
            List {
                Section("Configured Bastions & Gateways") {
                    ForEach(store.document.gateways) { gw in
                        HStack(spacing: 12) {
                            Image(systemName: "network.badge.shield.half.filled")
                                .font(.title3)
                                .foregroundStyle(Color(nsColor: .systemIndigo))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(gw.name).font(.body.weight(.semibold))
                                Text("\(gw.kind.rawValue) • \(gw.host):\(gw.port)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if let health = gatewayHealthById[gw.id] {
                                EnvironmentCapsuleBadge(health)
                            }
                            Button("Probe RTT") {
                                let host = gw.host
                                let port = UInt16(clamping: gw.port)
                                let id = gw.id
                                Task.detached {
                                    let hit = NativeNetworkDiagnostics.probeTcpPort(host: host, port: port, timeoutMs: 800)
                                    await MainActor.run {
                                        gatewayHealthById[id] = (hit?.open == true) ? "\(hit!.connectLatencyMs) ms" : "UNREACHABLE"
                                    }
                                }
                            }
                            .controlSize(.small)

                            Button(role: .destructive) {
                                store.commitMutation("Removed gateway \(gw.name)") { doc in
                                    doc.tombstonedObjectIds.insert(gw.id)
                                    doc.gateways.removeAll { $0.id == gw.id }
                                }
                            } label: {
                                Image(systemName: "trash")
                            }
                            .buttonStyle(.borderless)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .frame(minWidth: 360)

            Form {
                Section("Add Bastion / Proxy Gateway") {
                    TextField("Gateway Name", text: $gwName)
                    Picker("Transport", selection: $gwKind) {
                        ForEach(GatewayTransportKind.allCases, id: \.self) { k in
                            Text(k.rawValue).tag(k)
                        }
                    }
                    TextField("Hostname / IP", text: $gwHost)
                    TextField("Port", value: $gwPort, format: .number)

                    Button("Add Gateway") {
                        guard !gwName.isEmpty, !gwHost.isEmpty else { return }
                        let gw = SecureGatewayConfig(name: gwName, kind: gwKind, host: gwHost, port: gwPort)
                        store.commitMutation("Added gateway \(gw.name)") { doc in
                            doc.gateways.append(gw)
                        }
                        gwName = ""
                        gwHost = ""
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .formStyle(.grouped)
            .frame(minWidth: 320)
        }
    }
}

// MARK: - 5. Automation Command Tasks & Key Sequence Macros Studio

public struct AutomationTasksStudioView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @State private var cmdName: String = ""
    @State private var cmdTemplate: String = "ssh-Audit --host $URI$ --user $EffectiveUsername$"
    @State private var cmdBroadcast: Bool = false

    @State private var macroName: String = ""
    @State private var macroSeq: String = "$EffectiveUsername${TAB}$EffectivePassword${ENTER}"
    @State private var newTodoTitle: String = ""

    public init() {}

    public var body: some View {
        HSplitView {
            List {
                Section("Command Tasks ($URI$, $EffectiveUsername$, $CustomProperty.*$)") {
                    ForEach(store.document.commandTasks) { task in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(task.name).font(.body.weight(.semibold))
                                Text(task.commandTemplate)
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Run") {
                                store.runCommandTask(task)
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.small)

                            Button(role: .destructive) {
                                store.commitMutation("Deleted command task") { doc in
                                    doc.tombstonedObjectIds.insert(task.id)
                                    doc.commandTasks.removeAll { $0.id == task.id }
                                }
                            } label: {
                                Image(systemName: "trash")
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }

                Section("Key Sequence Macros ({ENTER}, {TAB}, {WAIT:500})") {
                    ForEach(store.document.keySequenceTasks) { macro in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(macro.name).font(.body.weight(.semibold))
                                Text(macro.macroSequence)
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Send Keys") {
                                store.runKeySequenceTask(macro)
                            }
                            .controlSize(.small)

                            Button(role: .destructive) {
                                store.commitMutation("Deleted macro") { doc in
                                    doc.tombstonedObjectIds.insert(macro.id)
                                    doc.keySequenceTasks.removeAll { $0.id == macro.id }
                                }
                            } label: {
                                Image(systemName: "trash")
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }

                Section("Runbook To-Do Checklist") {
                    ForEach(store.document.todoItems) { item in
                        HStack {
                            Button {
                                store.commitMutation("Toggled runbook item") { doc in
                                    if let idx = doc.todoItems.firstIndex(where: { $0.id == item.id }) {
                                        doc.todoItems[idx].completed.toggle()
                                        doc.todoItems[idx].updatedAtEpochMs = VaultClock.nowMs()
                                    }
                                }
                            } label: {
                                Image(systemName: item.completed ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(item.completed ? Color(nsColor: .systemGreen) : .secondary)
                            }
                            .buttonStyle(.plain)

                            Text(item.title)
                                .strikethrough(item.completed)
                            Spacer()
                            EnvironmentCapsuleBadge(item.priority)
                        }
                    }
                }
            }
            .frame(minWidth: 400)

            Form {
                Section("New Command Task") {
                    TextField("Task Name", text: $cmdName)
                    TextField("Command Template", text: $cmdTemplate)
                        .font(.system(.body, design: .monospaced))
                    Toggle("Broadcast to All Open Terminals", isOn: $cmdBroadcast)
                    Button("Save Command Task") {
                        guard !cmdName.isEmpty else { return }
                        let t = CommandTask(name: cmdName, commandTemplate: cmdTemplate, broadcastToAllSelectedSessions: cmdBroadcast)
                        store.commitMutation("Added command task \(t.name)") { doc in
                            doc.commandTasks.append(t)
                        }
                        cmdName = ""
                    }
                    .buttonStyle(.borderedProminent)
                }

                Section("New Key Sequence Macro") {
                    TextField("Macro Name", text: $macroName)
                    TextField("Sequence", text: $macroSeq)
                        .font(.system(.body, design: .monospaced))
                    Button("Save Key Sequence") {
                        guard !macroName.isEmpty else { return }
                        let m = KeySequenceTask(name: macroName, macroSequence: macroSeq)
                        store.commitMutation("Added key sequence \(m.name)") { doc in
                            doc.keySequenceTasks.append(m)
                        }
                        macroName = ""
                    }
                }

                Section("Add Runbook Action Item") {
                    TextField("Action Item", text: $newTodoTitle)
                    Button("Add To-Do") {
                        guard !newTodoTitle.isEmpty else { return }
                        store.commitMutation("Added runbook todo") { doc in
                            doc.todoItems.append(VaultTodoItem(title: newTodoTitle))
                        }
                        newTodoTitle = ""
                    }
                }
            }
            .formStyle(.grouped)
            .frame(minWidth: 340)
        }
    }
}

// MARK: - 6. Simplified Automatic Google Drive & Cloud Sync View

public struct CloudSyncStudioView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @State private var isPickingExistingCloudFile: Bool = false
    @State private var discoveredCloudFolders: [(provider: String, url: URL)] = []

    private static let timeFormatter: DateFormatter = {
        let df = DateFormatter()
        df.dateStyle = .none
        df.timeStyle = .medium
        return df
    }()

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                // Single clean, approachable Google Drive / Cloud Sync card
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(
                                    (store.linkedCloudFileURL != nil ? Color(nsColor: .systemGreen) : Color.accentColor)
                                        .opacity(0.14)
                                )
                                .frame(width: 44, height: 44)
                            Image(systemName: store.linkedCloudFileURL != nil ? "checkmark.icloud.fill" : "icloud.and.arrow.up")
                                .font(.system(size: 20, weight: .semibold))
                                .foregroundStyle(store.linkedCloudFileURL != nil ? Color(nsColor: .systemGreen) : Color.accentColor)
                        }

                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 8) {
                                Text(store.strings.cloudSync)
                                    .font(.title3.weight(.semibold))
                                if store.isSyncingCloud {
                                    ProgressView()
                                        .controlSize(.small)
                                } else if store.linkedCloudFileURL != nil {
                                    Text("Active")
                                        .font(.caption2.weight(.bold))
                                        .foregroundStyle(Color(nsColor: .systemGreen))
                                        .padding(.horizontal, 7)
                                        .padding(.vertical, 2)
                                        .background(
                                            Capsule(style: .continuous)
                                                .fill(Color(nsColor: .systemGreen).opacity(0.15))
                                        )
                                }
                            }
                            Text("Automatically syncs on startup, when you make changes, and whenever remote changes are detected.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        Spacer()

                        if store.hasConfiguredCloudSync {
                            Button {
                                Task { await store.syncCloudNow(reportErrors: true) }
                            } label: {
                                Label(store.isSyncingCloud ? "Syncing…" : store.strings.syncNow, systemImage: "arrow.triangle.2.circlepath")
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(store.isSyncingCloud)
                        }
                    }

                    Divider()

                    if let linked = store.linkedCloudFileURL {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(spacing: 8) {
                                Image(systemName: "doc.badge.gearshape.fill")
                                    .foregroundStyle(Color.accentColor)
                                Text(linked.lastPathComponent)
                                    .font(.callout.weight(.semibold))
                                if let syncedAt = store.lastCloudSyncDate {
                                    Text("• Last synced \(Self.timeFormatter.string(from: syncedAt))")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }

                            Text(linked.path)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)

                            Text(store.syncStatusSummary)
                                .font(.caption)
                                .foregroundStyle(.secondary)

                            HStack(spacing: 10) {
                                Button {
                                    NSWorkspace.shared.activateFileViewerSelecting([linked])
                                } label: {
                                    Label("Show in Finder", systemImage: "folder")
                                }

                                Button {
                                    isPickingExistingCloudFile = true
                                } label: {
                                    Label("Switch Vault File…", systemImage: "arrow.left.arrow.right")
                                }

                                Spacer()

                                Button(role: .destructive) {
                                    store.unlinkCloudVault()
                                } label: {
                                    Label("Disconnect Sync", systemImage: "xmark.circle")
                                }
                            }
                            .padding(.top, 4)
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Choose a Google Drive (or iCloud Drive) vault file once. NexusTS keeps your connections, folders, and credentials encrypted and synchronized automatically across all your devices.")
                                .font(.callout)
                                .foregroundStyle(.secondary)

                            if !discoveredCloudFolders.isEmpty {
                                VStack(alignment: .leading, spacing: 8) {
                                    ForEach(Array(discoveredCloudFolders.enumerated()), id: \.offset) { _, entry in
                                        HStack {
                                            Image(systemName: entry.provider.contains("Google") ? "externaldrive.fill.badge.icloud" : "icloud.fill")
                                                .foregroundStyle(Color.accentColor)
                                            VStack(alignment: .leading, spacing: 1) {
                                                Text(entry.provider)
                                                    .font(.callout.weight(.medium))
                                                Text(entry.url.path)
                                                    .font(.caption2)
                                                    .foregroundStyle(.secondary)
                                                    .lineLimit(1)
                                            }
                                            Spacer()
                                            Button("Sync Here") {
                                                let fileUrl = entry.url.appendingPathComponent("NexusTS-Vault.nxts")
                                                do {
                                                    try FileManager.default.createDirectory(at: entry.url, withIntermediateDirectories: true)
                                                    try store.linkCloudVaultURL(fileUrl)
                                                } catch {
                                                    store.reportError(error, operationTitle: "Cloud Vault Link Failed")
                                                }
                                            }
                                            .buttonStyle(.borderedProminent)
                                            .controlSize(.small)
                                        }
                                        .padding(10)
                                        .background(
                                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                                .fill(Color.primary.opacity(0.04))
                                        )
                                    }
                                }
                            }

                            HStack(spacing: 10) {
                                Button {
                                    presentCreateSyncedVaultSavePanel()
                                } label: {
                                    Label("Create New Synced Vault…", systemImage: "plus.circle.fill")
                                }
                                .buttonStyle(.borderedProminent)

                                Button {
                                    isPickingExistingCloudFile = true
                                } label: {
                                    Label("Open Existing Vault (.nxts)…", systemImage: "folder.badge.person.crop")
                                }
                            }
                            .padding(.top, 2)
                        }
                    }
                }
                .padding(20)
                .liquidGlassSurface(cornerRadius: 14, prominence: .regular)
            }
            .frame(maxWidth: 760)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .fileImporter(
            isPresented: $isPickingExistingCloudFile,
            allowedContentTypes: [.data],
            allowsMultipleSelection: false
        ) { res in
            switch res {
            case .success(let urls):
                guard let url = urls.first else { return }
                do {
                    try store.linkCloudVaultURL(url)
                } catch {
                    store.reportError(error, operationTitle: "Cloud Vault Link Failed")
                }
            case .failure(let error):
                store.reportError(error, operationTitle: "File Picker Error")
            }
        }
        .onAppear {
            discoveredCloudFolders = MacCloudBookmarkSyncBridge.discoverCloudVaultDirectories()
        }
    }

    private func presentCreateSyncedVaultSavePanel() {
        let panel = NSSavePanel()
        panel.title = "Create Synced Vault in Google Drive"
        panel.prompt = "Create & Sync"
        panel.nameFieldStringValue = "NexusTS-Vault.nxts"
        panel.canCreateDirectories = true
        if let preferredDir = discoveredCloudFolders.first?.url {
            try? FileManager.default.createDirectory(at: preferredDir, withIntermediateDirectories: true)
            panel.directoryURL = preferredDir
        }
        if panel.runModal() == .OK, let targetURL = panel.url {
            let finalURL = targetURL.pathExtension.lowercased() == "nxts"
                ? targetURL
                : targetURL.appendingPathExtension("nxts")
            do {
                try store.linkCloudVaultURL(finalURL)
            } catch {
                store.reportError(error, operationTitle: "Cloud Vault Link Failed")
            }
        }
    }
}
