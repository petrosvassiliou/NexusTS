import AppKit
import NexusTSCore
import SwiftUI

// MARK: - 1. macOS MenuBarExtra Quick-Connect Agent

public struct MenuBarQuickAgentView: View {
    @EnvironmentObject private var store: WorkspaceStore

    public init() {}

    public var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: "network.badge.shield.half.filled")
                    .foregroundStyle(Color.accentColor)
                Text("NexusTS Vault")
                    .font(.headline)
                Spacer()
                let liveCount = store.paneTree.allTabs.count + store.detachedWindowTabs.count
                if liveCount > 0 {
                    Text("\(liveCount) active")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(Color(nsColor: .systemGreen))
                }
            }
            .padding(.bottom, 2)

            Divider()

            Button {
                NSApp.activate(ignoringOtherApps: true)
                store.openLocalTerminal()
            } label: {
                Label("New Local macOS Shell (/bin/zsh)", systemImage: "apple.terminal")
            }

            Button {
                NSApp.activate(ignoringOtherApps: true)
                store.isQuickConnectPresented = true
            } label: {
                Label("Quick Connect Palette…", systemImage: "sparkle.magnifyingglass")
            }

            let favorites = store.activeConnections.filter(\.favorite).prefix(8)
            if !favorites.isEmpty {
                Divider()
                Text("Favorites")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                ForEach(Array(favorites)) { conn in
                    Button {
                        NSApp.activate(ignoringOtherApps: true)
                        store.openConnection(conn)
                    } label: {
                        Label("\(conn.name) (\(conn.protocolType.shortBadge))", systemImage: conn.protocolType.sfSymbolName)
                    }
                }
            }

            let totpCreds = store.document.credentials.filter { !$0.totpSecretBase32.isEmpty }
            if !totpCreds.isEmpty {
                Divider()
                Text("Copy 2FA TOTP Code")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                ForEach(totpCreds) { cred in
                    Button {
                        if let otp = TotpCodeGenerator.generateTotp(base32Secret: cred.totpSecretBase32) {
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(otp, forType: .string)
                        }
                    } label: {
                        Label("Copy TOTP: \(cred.name)", systemImage: "clock.badge.checkmark")
                    }
                }
            }

            Divider()

            Button {
                Task { await store.syncCloudNow() }
            } label: {
                Label("Sync Cloud Vault Now", systemImage: "arrow.triangle.2.circlepath.icloud")
            }

            Button {
                store.lockVault()
            } label: {
                Label("Lock Zero-Knowledge Vault", systemImage: "lock.fill")
            }

            Divider()

            Button("Quit NexusTS") {
                NSApplication.shared.terminate(nil)
            }
            .keyboardShortcut("q", modifiers: [.command])
        }
        .padding(8)
        .frame(width: 280)
    }
}

// MARK: - 2. Native macOS Settings Window (⌘,)

public struct MacSettingsView: View {
    @EnvironmentObject private var store: WorkspaceStore

    @State private var newMasterPassphrase: String = ""
    @State private var confirmMasterPassphrase: String = ""
    @State private var saveToTouchIdKeychain: Bool = true
    @State private var securityStatusBanner: String? = nil

    public init() {}

    public var body: some View {
        TabView {
            generalTab
                .tabItem {
                    Label("General", systemImage: "gearshape")
                }

            securityTab
                .tabItem {
                    Label("Security & Touch ID", systemImage: "touchid")
                }

            terminalTab
                .tabItem {
                    Label("Terminal", systemImage: "terminal")
                }

            cloudSyncTab
                .tabItem {
                    Label("Cloud Sync", systemImage: "icloud")
                }

            updatesTab
                .tabItem {
                    Label("Updates", systemImage: "arrow.down.circle")
                }
        }
        .frame(width: 540, height: 380)
    }

    private var generalTab: some View {
        Form {
            Picker("Workspace Language", selection: $store.appLanguage) {
                ForEach(NexusAppLanguage.allCases) { lang in
                    Text(lang.nativeDisplayName).tag(lang)
                }
            }

            Toggle("Index Connections in macOS Spotlight Search", isOn: $store.spotlightIndexingEnabled)
                .onChange(of: store.spotlightIndexingEnabled) { _, enabled in
                    MacSpotlightVaultIndexer.reindexConnections(in: store.document, enabled: enabled)
                }

            LabeledContent("Local Vault Path") {
                Text(WorkspaceStore.localVaultFileURL.path)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .formStyle(.grouped)
    }

    private var securityTab: some View {
        Form {
            Section("Argon2id + AES-256-GCM Master Passphrase") {
                SecureField("New Master Passphrase (blank resets to transparent key)", text: $newMasterPassphrase)
                SecureField("Confirm Master Passphrase", text: $confirmMasterPassphrase)

                Toggle("Enable Touch ID / Apple Watch Unlock via macOS Keychain", isOn: $saveToTouchIdKeychain)
                    .disabled(!MacKeychainAndBiometrics.shared.canUseTouchIdOrCompanion)

                HStack {
                    Button("Apply Vault Encryption Key") {
                        guard newMasterPassphrase == confirmMasterPassphrase else {
                            securityStatusBanner = "Passphrases do not match."
                            return
                        }
                        do {
                            try store.configureMasterPassphrase(newMasterPassphrase, saveForTouchId: saveToTouchIdKeychain)
                            securityStatusBanner = newMasterPassphrase.isEmpty
                                ? "Reset to transparent sync key."
                                : "Master passphrase sealed with Argon2id + Keychain."
                            newMasterPassphrase = ""
                            confirmMasterPassphrase = ""
                        } catch {
                            securityStatusBanner = error.localizedDescription
                        }
                    }
                    .buttonStyle(.borderedProminent)

                    if let msg = securityStatusBanner {
                        Text(msg)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .formStyle(.grouped)
    }

    private var terminalTab: some View {
        Form {
            Picker("Monospaced Font Family", selection: $store.terminalFontName) {
                Text("SF Mono").tag("SF Mono")
                Text("Menlo").tag("Menlo")
                Text("Monaco").tag("Monaco")
            }

            Slider(value: $store.terminalFontSize, in: 10...22, step: 1) {
                Text("Default Font Size (\(Int(store.terminalFontSize)) pt)")
            }

            Toggle("Capture Remote Shortcut Keys Inside Active Sessions", isOn: $store.remoteKeyCaptureEnabled)
        }
        .formStyle(.grouped)
    }

    private var cloudSyncTab: some View {
        Form {
            Toggle("Automatically Sync Linked Cloud Vault on Save", isOn: $store.autoSyncOnSave)
            Picker("Google Drive Storage Zone", selection: $store.driveStorageZone) {
                ForEach(DriveStorageZone.allCases, id: \.self) { z in
                    Text(z.displayTitle).tag(z)
                }
            }
            LabeledContent("Current Cloud Status", value: store.syncStatusSummary)
        }
        .formStyle(.grouped)
    }

    private var updatesTab: some View {
        Form {
            LabeledContent("Installed macOS Version", value: "NexusTS \(MacGitHubReleaseUpdater.currentVersion) (Universal2)")
            Button("Check GitHub Releases Now") {
                Task { await store.checkGitHubUpdates() }
            }
            if let rel = store.updateCandidate {
                LabeledContent("Available Release", value: "\(rel.tagName) — \(rel.releaseTitle)")
            }
        }
        .formStyle(.grouped)
    }
}
