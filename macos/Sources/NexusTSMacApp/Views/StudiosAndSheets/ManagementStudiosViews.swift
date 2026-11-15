import AppKit
import NexusTSCore
import SwiftUI
import UniformTypeIdentifiers

// MARK: - 1. Vault Overview Dashboard (Apple HIG Hero Cards & Favorites Grid)

public struct VaultOverviewDashboardView: View {
    @EnvironmentObject private var store: WorkspaceStore

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(alignment: .center, spacing: 14) {
                    Image(nsImage: NSApp.applicationIconImage)
                        .resizable()
                        .interpolation(.high)
                        .frame(width: 48, height: 48)
                        .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))

                    VStack(alignment: .leading, spacing: 4) {
                        Text(store.document.name)
                            .font(.largeTitle.weight(.bold))
                        Text("Zero-Knowledge Argon2id + AES-256-GCM Vault • Revision \(store.document.revision)")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()

                    Button {
                        store.openLocalTerminal()
                    } label: {
                        Label(store.strings.localTerminal, systemImage: "apple.terminal")
                    }
                    .controlSize(.large)

                    Button {
                        store.presentNewConnectionSheet()
                    } label: {
                        Label(store.strings.newConnection, systemImage: "plus")
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                }

                // Summary Metric Cards
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 185), spacing: 14)], spacing: 14) {
                    OverviewStatCard(
                        title: "Connections",
                        value: "\(store.activeConnections.count)",
                        subtitle: "\(store.activeFolders.count) folders",
                        symbol: "server.rack",
                        tint: Color(nsColor: .systemBlue)
                    ) {
                        store.isQuickConnectPresented = true
                    }

                    OverviewStatCard(
                        title: "Live Sessions",
                        value: "\(store.paneTree.allTabs.count + store.detachedWindowTabs.count)",
                        subtitle: "\(store.detachedWindowTabs.count) detached windows",
                        symbol: "macwindow.split.2x1",
                        tint: Color(nsColor: .systemGreen)
                    ) {
                        store.selectSection(.sessions)
                    }

                    OverviewStatCard(
                        title: "Credentials & TOTP",
                        value: "\(store.document.credentials.count)",
                        subtitle: "SSH Keys & Passwords",
                        symbol: "key.horizontal.fill",
                        tint: Color(nsColor: .systemOrange)
                    ) {
                        store.selectSection(.credentials)
                    }

                    OverviewStatCard(
                        title: "Cloud Sync",
                        value: store.linkedCloudFileURL != nil ? "Linked" : "Local",
                        subtitle: store.syncStatusSummary,
                        symbol: "icloud.fill",
                        tint: Color(nsColor: .systemTeal)
                    ) {
                        store.selectSection(.cloudSync)
                    }
                }

                // Favorites Quick Launch Grid
                let favorites = store.activeConnections.filter(\.favorite)
                if !favorites.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Label(store.strings.favorites, systemImage: "star.fill")
                            .font(.headline)

                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 250), spacing: 12)], spacing: 12) {
                            ForEach(favorites) { conn in
                                let isSelected = store.selectedConnectionId == conn.id
                                let cardTint = HIGColorUtilities.color(fromHex: conn.inheritable.displayColorAccentHex) ?? conn.protocolType.appleSystemTint
                                HStack(spacing: 12) {
                                    ProtocolSquircleIcon(
                                        protocolType: conn.protocolType,
                                        customHex: conn.inheritable.displayColorAccentHex,
                                        size: 34
                                    )
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(conn.name)
                                            .font(.body.weight(.semibold))
                                            .foregroundStyle(.primary)
                                            .lineLimit(1)
                                        Text(conn.protocolType.displayName)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                    }
                                    Spacer()
                                    Image(systemName: "arrow.up.right.circle.fill")
                                        .foregroundStyle(Color.accentColor)
                                }
                                .padding(12)
                                .liquidGlassSurface(
                                    cornerRadius: LiquidGlassMetrics.cardCornerRadius,
                                    tint: cardTint,
                                    prominence: isSelected ? .regular : .subtle
                                )
                                .contentShape(RoundedRectangle(cornerRadius: LiquidGlassMetrics.cardCornerRadius, style: .continuous))
                                .onTapGesture(count: 2) {
                                    store.openConnection(conn)
                                }
                                .simultaneousGesture(
                                    TapGesture(count: 1).onEnded {
                                        store.selectedConnectionId = conn.id
                                    }
                                )
                                .contextMenu {
                                    Button("Connect") {
                                        store.openConnection(conn)
                                    }
                                    Button("Edit Connection…") {
                                        store.editingConnection = conn
                                    }
                                }
                                .help("Double-click to connect to \(conn.name)")
                            }
                        }
                    }
                }

                // Keyboard Shortcut Cheat Sheet (localized + interactive buttons matching menu bar 1:1)
                VStack(alignment: .leading, spacing: 8) {
                    Text(store.strings.workspaceShortcutsTitle)
                        .font(.headline)

                    LazyVGrid(
                        columns: [GridItem(.adaptive(minimum: 185), spacing: 10)],
                        alignment: .leading,
                        spacing: 10
                    ) {
                        ForEach(MacWorkspaceShortcut.overviewCheatSheetItems) { item in
                            ShortcutLegendItem(
                                item.title(in: store.strings),
                                keys: item.keycap,
                                isActive: isShortcutActive(item)
                            ) {
                                store.executeShortcut(item)
                            }
                        }
                    }
                    .padding(12)
                    .liquidGlassSurface(cornerRadius: 12, prominence: .subtle)
                }
            }
            .padding(28)
        }
        .scrollContentBackground(.hidden)
    }

    private func isShortcutActive(_ item: MacWorkspaceShortcut) -> Bool {
        switch item {
        case .broadcastInput:
            return store.broadcastInputEnabled
        case .toggleInspector:
            return store.isInspectorPresented
        case .toggleSidebar:
            return store.columnVisibility != .detailOnly
        case .quickConnect:
            return store.isQuickConnectPresented
        default:
            return false
        }
    }
}

private struct OverviewStatCard: View {
    let title: String
    let value: String
    let subtitle: String
    let symbol: String
    let tint: Color
    let action: () -> Void
    @State private var isHovered: Bool = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Image(systemName: symbol)
                        .font(.title3.weight(.semibold))
                        .symbolRenderingMode(.hierarchical)
                        .foregroundStyle(tint)
                    Spacer()
                    Text(value)
                        .font(.title2.weight(.bold).monospacedDigit())
                        .foregroundStyle(.primary)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .padding(14)
            .liquidGlassSurface(
                cornerRadius: LiquidGlassMetrics.cardCornerRadius,
                tint: tint,
                prominence: .regular,
                isHovered: isHovered
            )
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            withAnimation(LiquidGlassMetrics.hoverAnimation) {
                isHovered = hovering
            }
        }
    }
}

private struct ShortcutLegendItem: View {
    let label: String
    let keys: String
    let isActive: Bool
    let action: () -> Void
    @State private var isHovered: Bool = false

    init(_ label: String, keys: String, isActive: Bool = false, action: @escaping () -> Void) {
        self.label = label
        self.keys = keys
        self.isActive = isActive
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Text(label)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(isActive ? Color.accentColor : .primary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                KeycapBadge(keys)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(
                        isActive
                            ? Color.accentColor.opacity(0.14)
                            : Color.primary.opacity(isHovered ? 0.08 : 0.03)
                    )
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .strokeBorder(
                        isActive ? Color.accentColor.opacity(0.35) : Color.primary.opacity(0.08),
                        lineWidth: 0.75
                    )
            )
            .contentShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            isHovered = hovering
        }
        .help("\(label) (\(keys))")
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
                                    .help("Copy 6-Digit RFC 6238 TOTP Code")
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

// MARK: - 6. Cloud Sync Studio (iCloud Drive + Google Drive for Desktop + Drive REST API v3)

public struct CloudSyncStudioView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @State private var isPickingCloudFile: Bool = false
    @State private var googleOauthBearerInput: String = ""
    @State private var discoveredCloudFolders: [(provider: String, url: URL)] = []

    public init() {}

    public var body: some View {
        Form {
            Section("Zero-Knowledge Cloud Sync Status") {
                LabeledContent("Vault Document ID", value: store.document.documentId)
                LabeledContent("Revision", value: "\(store.document.revision)")
                LabeledContent("Status", value: store.syncStatusSummary)
                LabeledContent("Vector Clock") {
                    Text(
                        store.document.vectorClock
                            .sorted(by: { $0.key < $1.key })
                            .map { "\($0.key):\($0.value)" }
                            .joined(separator: "  •  ")
                    )
                    .font(.system(.caption, design: .monospaced))
                }
                Toggle("Auto-Sync on Every Vault Save", isOn: $store.autoSyncOnSave)

                HStack {
                    Button {
                        Task { await store.syncCloudNow(reportErrors: true) }
                    } label: {
                        Label("Synchronize & 3-Way Delta Merge Now", systemImage: "arrow.triangle.2.circlepath.icloud.fill")
                    }
                    .buttonStyle(.borderedProminent)
                }
            }

            Section("iCloud Drive & Google Drive for Desktop (Security-Scoped Bookmark)") {
                if let linked = store.linkedCloudFileURL {
                    LabeledContent("Linked .nxts File", value: linked.path)
                    HStack {
                        Button("Reveal in Finder") {
                            NSWorkspace.shared.activateFileViewerSelecting([linked])
                        }
                        Button("Unlink Cloud File", role: .destructive) {
                            store.cloudBookmarkBridge.unlinkVaultFile()
                            store.linkedCloudFileURL = nil
                            store.syncStatusSummary = "Local Zero-Knowledge Vault"
                        }
                    }
                } else {
                    Text("Link an encrypted `.nxts` file inside iCloud Drive or Google Drive for Desktop to seamlessly sync with Android, Chromebook, and other Macs.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                ForEach(Array(discoveredCloudFolders.enumerated()), id: \.offset) { _, entry in
                    HStack {
                        Label(entry.provider, systemImage: "icloud")
                        Spacer()
                        Button("Create & Link Shared Vault Here") {
                            let fileUrl = entry.url.appendingPathComponent("NexusTS-Shared-Vault.nxts")
                            do {
                                try FileManager.default.createDirectory(at: entry.url, withIntermediateDirectories: true)
                                try store.linkCloudVaultURL(fileUrl)
                            } catch {
                                store.reportError(error, operationTitle: "Cloud Vault Link Failed")
                            }
                        }
                        .controlSize(.small)
                    }
                }

                Button("Choose Existing .nxts Vault File in Finder…") {
                    isPickingCloudFile = true
                }
            }

            Section("Google Drive REST API v3 Direct Sync (Cross-Platform Android Parity)") {
                Picker("Drive Storage Zone", selection: $store.driveStorageZone) {
                    ForEach(DriveStorageZone.allCases, id: \.self) { z in
                        Text(z.displayTitle).tag(z)
                    }
                }
                SecureField("Google OAuth 2.0 Bearer Token (drive.file / drive.appdata)", text: $googleOauthBearerInput)
                Button("Save Google Drive Token to macOS Keychain & Sync") {
                    MacKeychainAndBiometrics.shared.saveToken(googleOauthBearerInput, key: "gdrive_oauth_bearer")
                    Task { await store.syncCloudNow(reportErrors: true) }
                }
                .disabled(googleOauthBearerInput.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .formStyle(.grouped)
        .fileImporter(
            isPresented: $isPickingCloudFile,
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
            googleOauthBearerInput = MacKeychainAndBiometrics.shared.loadToken(key: "gdrive_oauth_bearer") ?? ""
        }
    }
}
