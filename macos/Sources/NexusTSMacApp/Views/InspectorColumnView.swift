import AppKit
import NexusTSCore
import SwiftUI

public struct InspectorColumnView: View {
    @EnvironmentObject private var store: WorkspaceStore

    @State private var totpTick: Date = Date()
    @State private var revealedCustomKeys: Set<String> = []
    @State private var newTodoTitle: String = ""
    @State private var newCustomKey: String = ""
    @State private var newCustomValue: String = ""
    @State private var newCustomProtected: Bool = false

    private let secondTimer = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()

    public init() {}

    public var body: some View {
        Group {
            if let conn = store.selectedConnection {
                let profile = PropertyInheritanceResolver.resolve(connection: conn, in: store.document)
                Form {
                    // 1. Header Identity
                    Section("Selected Connection") {
                        HStack(spacing: 10) {
                            ProtocolSquircleIcon(
                                protocolType: conn.protocolType,
                                customHex: profile.displayColorAccentHex,
                                size: 34
                            )
                            VStack(alignment: .leading, spacing: 2) {
                                Text(conn.name)
                                    .font(.headline)
                                Text("\(conn.host):\(conn.settings.effectivePort)")
                                    .font(.caption.monospaced())
                                    .foregroundStyle(.secondary)
                            }
                        }

                        HStack(spacing: 8) {
                            Button("Connect") {
                                store.openConnection(conn)
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.small)

                            Button("Edit…") {
                                store.editingConnection = conn
                            }
                            .controlSize(.small)
                        }
                    }

                    // 2. Effective Inherited Profile
                    Section("Effective Inheritance") {
                        LabeledContent("Credential") {
                            VStack(alignment: .trailing, spacing: 2) {
                                Text(profile.effectiveCredential?.name ?? (profile.promptCredentialAtConnect ? "Prompt at Connect" : "None"))
                                    .font(.callout.weight(.medium))
                                Text("via \(profile.credentialInheritedFrom)")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }

                        LabeledContent("Secure Gateway") {
                            VStack(alignment: .trailing, spacing: 2) {
                                Text(profile.effectiveGateway?.name ?? "Direct")
                                    .font(.callout.weight(.medium))
                                Text(profile.gatewayInheritedFrom)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }

                        if let badge = profile.effectiveEnvironmentBadge {
                            LabeledContent("Environment") {
                                EnvironmentCapsuleBadge(badge)
                            }
                        }
                    }

                    // 3. Live RFC 6238 TOTP Authenticator Card
                    if let secret = profile.effectiveCredential?.totpSecretBase32,
                       !secret.isEmpty,
                       let otp = TotpCodeGenerator.generateTotp(base32Secret: secret, timestamp: totpTick) {
                        let rem = TotpCodeGenerator.secondsRemaining(at: totpTick)
                        Section("RFC 6238 TOTP Authenticator") {
                            HStack(spacing: 12) {
                                ZStack {
                                    Circle()
                                        .stroke(Color.primary.opacity(0.12), lineWidth: 3)
                                    Circle()
                                        .trim(from: 0, to: CGFloat(rem) / 30.0)
                                        .stroke(rem <= 5 ? Color.red : Color.accentColor, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                                        .rotationEffect(.degrees(-90))
                                    Text("\(rem)")
                                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                                }
                                .frame(width: 28, height: 28)

                                Text("\(otp.prefix(3)) \(otp.suffix(3))")
                                    .font(.system(size: 18, weight: .bold, design: .monospaced))

                                Spacer()

                                Button {
                                    NSPasteboard.general.clearContents()
                                    NSPasteboard.general.setString(otp, forType: .string)
                                } label: {
                                    Image(systemName: "doc.on.doc")
                                }
                                .buttonStyle(.borderless)
                                .help("Copy 6-digit TOTP code")
                            }
                        }
                    }

                    // 4. Finder-Style Color Accent Tags
                    Section("Accent Tag") {
                        HStack(spacing: 8) {
                            ForEach(HIGColorUtilities.finderTagPalette, id: \.label) { item in
                                let dotColor = HIGColorUtilities.color(fromHex: item.hex) ?? Color.secondary.opacity(0.35)
                                let isChosen = conn.inheritable.displayColorAccentHex == item.hex
                                Circle()
                                    .fill(dotColor)
                                    .frame(width: 16, height: 16)
                                    .overlay(
                                        Circle()
                                            .strokeBorder(Color.primary, lineWidth: isChosen ? 2 : 0)
                                    )
                                    .onTapGesture {
                                        store.commitMutation("Updated accent color") { doc in
                                            if let idx = doc.connections.firstIndex(where: { $0.id == conn.id }) {
                                                doc.connections[idx].inheritable.displayColorAccentHex = item.hex
                                            }
                                        }
                                    }
                                    .help(item.label)
                            }
                        }
                    }

                    // 5. Custom Properties ($CustomProperty.*$)
                    Section("Custom Properties") {
                        let sortedProps = profile.effectiveCustomProperties.values.sorted { $0.key < $1.key }
                        ForEach(sortedProps) { prop in
                            HStack {
                                Text(prop.key)
                                    .font(.caption.weight(.medium))
                                Spacer()
                                if prop.isProtected && !revealedCustomKeys.contains(prop.key) {
                                    Text("••••••••")
                                        .font(.caption.monospaced())
                                        .foregroundStyle(.secondary)
                                } else {
                                    Text(prop.value)
                                        .font(.caption.monospaced())
                                        .lineLimit(1)
                                }
                                if prop.isProtected {
                                    Button {
                                        if revealedCustomKeys.contains(prop.key) {
                                            revealedCustomKeys.remove(prop.key)
                                        } else {
                                            revealedCustomKeys.insert(prop.key)
                                        }
                                    } label: {
                                        Image(systemName: revealedCustomKeys.contains(prop.key) ? "eye.slash" : "eye")
                                            .font(.caption)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }

                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 6) {
                                TextField("Key", text: $newCustomKey)
                                    .textFieldStyle(.roundedBorder)
                                TextField("Value", text: $newCustomValue)
                                    .textFieldStyle(.roundedBorder)
                            }
                            HStack {
                                Toggle("Protected", isOn: $newCustomProtected)
                                    .toggleStyle(.checkbox)
                                    .font(.caption)
                                Spacer()
                                Button("Add") {
                                    let k = newCustomKey.trimmingCharacters(in: .whitespacesAndNewlines)
                                    guard !k.isEmpty else { return }
                                    store.commitMutation("Added custom property \(k)") { doc in
                                        if let idx = doc.connections.firstIndex(where: { $0.id == conn.id }) {
                                            doc.connections[idx].inheritable.customProperties.removeAll { $0.key == k }
                                            doc.connections[idx].inheritable.customProperties.append(
                                                CustomPropertyEntry(key: k, value: newCustomValue, isProtected: newCustomProtected)
                                            )
                                        }
                                    }
                                    newCustomKey = ""
                                    newCustomValue = ""
                                }
                                .controlSize(.mini)
                            }
                        }
                    }

                    // 6. Notes & Per-Connection Checklist
                    Section("Runbook Checklist & Notes") {
                        let todos = store.document.todoItems.filter {
                            $0.targetObjectId == conn.id && !store.document.tombstonedObjectIds.contains($0.id)
                        }
                        ForEach(todos) { item in
                            HStack {
                                Button {
                                    store.commitMutation("Toggled To-Do") { doc in
                                        if let idx = doc.todoItems.firstIndex(where: { $0.id == item.id }) {
                                            doc.todoItems[idx].completed.toggle()
                                        }
                                    }
                                } label: {
                                    Image(systemName: item.completed ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(item.completed ? Color.accentColor : .secondary)
                                }
                                .buttonStyle(.plain)

                                Text(item.title)
                                    .font(.caption)
                                    .strikethrough(item.completed)
                            }
                        }

                        HStack {
                            TextField("Add runbook task…", text: $newTodoTitle)
                                .textFieldStyle(.roundedBorder)
                                .onSubmit { addTodo(for: conn.id) }
                            Button("Add") { addTodo(for: conn.id) }
                                .controlSize(.mini)
                        }

                        if !conn.notes.isEmpty {
                            Text(conn.notes)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .formStyle(.grouped)
                .onReceive(secondTimer) { now in
                    totpTick = now
                }
            } else {
                Form {
                    Section("Vault Summary") {
                        LabeledContent("Document", value: store.document.name)
                        LabeledContent("Revision", value: "#\(store.document.revision)")
                        LabeledContent("Connections", value: "\(store.activeConnections.count)")
                        LabeledContent("Folders", value: "\(store.activeFolders.count)")
                        LabeledContent("Credentials", value: "\(store.document.credentials.count)")
                    }

                    Section("SSH Key Generator") {
                        Text("Generate an unencrypted-in-RAM, Argon2id-vault-sealed OpenSSH Ed25519 keypair.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Button {
                            if let kp = try? NativeSshCryptoBridge.generateEd25519Keypair(comment: "\(NSUserName())@\(Host.current().localizedName ?? "mac")") {
                                store.commitMutation("Generated Ed25519 SSH keypair") { doc in
                                    doc.credentials.append(VaultCredential(
                                        name: "Ed25519 (\(kp.sha256Fingerprint.prefix(16))…)",
                                        sourceKind: .SSH_KEY_PAIR,
                                        username: NSUserName(),
                                        privateKeyPem: kp.privateKeyOpenSshPem,
                                        publicKeyOpenSsh: kp.publicKeyOpenSsh,
                                        keyFingerprintSha256: kp.sha256Fingerprint
                                    ))
                                }
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(kp.publicKeyOpenSsh, forType: .string)
                            }
                        } label: {
                            Label("Generate Ed25519 Key & Copy Public Key", systemImage: "key.radiowaves.forward.fill")
                        }
                        .controlSize(.small)
                    }
                }
                .formStyle(.grouped)
            }
        }
    }

    private func addTodo(for connectionId: String) {
        let trimmed = newTodoTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        store.commitMutation("Added runbook item") { doc in
            doc.todoItems.append(VaultTodoItem(targetObjectId: connectionId, title: trimmed))
        }
        newTodoTitle = ""
    }
}
