import AppKit
import NexusTSCore
import SwiftUI

// MARK: - 1. macOS MenuBarExtra Quick-Connect Agent

public struct MenuBarQuickAgentView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow

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
                NSApp.setActivationPolicy(.regular)
                openWindow(id: "main-workspace")
                NSApp.activate(ignoringOtherApps: true)
            } label: {
                Label("Open NexusTS Workspace", systemImage: "macwindow")
            }

            Button {
                NSApp.setActivationPolicy(.regular)
                openWindow(id: "main-workspace")
                NSApp.activate(ignoringOtherApps: true)
                store.openLocalTerminal()
            } label: {
                Label(MacWorkspaceShortcut.localTerminal.title(in: store.strings), systemImage: "apple.terminal")
            }

            Button {
                NSApp.setActivationPolicy(.regular)
                openWindow(id: "main-workspace")
                NSApp.activate(ignoringOtherApps: true)
                store.isQuickConnectPresented = true
            } label: {
                Label(MacWorkspaceShortcut.quickConnect.menuTitle(in: store.strings), systemImage: "sparkle.magnifyingglass")
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
                Task { await store.syncCloudNow(reportErrors: true) }
            } label: {
                Label("Sync Cloud Vault Now", systemImage: "arrow.triangle.2.circlepath.icloud")
            }

            Button {
                NSApp.setActivationPolicy(.regular)
                openWindow(id: "main-workspace")
                NSApp.activate(ignoringOtherApps: true)
                store.isPresentingSoftwareUpdateSheet = true
                if store.updateCandidate == nil {
                    Task { await store.checkGitHubUpdates(manual: true) }
                }
            } label: {
                if let rel = store.updateCandidate {
                    Label("Install Update (\(rel.tagName))…", systemImage: "arrow.down.circle.fill")
                } else {
                    Label("Check for Updates…", systemImage: "arrow.triangle.2.circlepath.circle")
                }
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
        .frame(width: 560, height: 470)
    }

    private var generalTab: some View {
        Form {
            Section("Workspace Preferences") {
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

            Section("Database Maintenance") {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(store.strings.resetDatabase)
                            .font(.callout.weight(.medium))
                        Text("Wipe all local folders, connections, credentials, and passphrases back to an empty vault.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(role: .destructive) {
                        store.isPresentingResetDatabaseSheet = true
                    } label: {
                        Text(store.strings.resetDatabase)
                    }
                }
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

            Section("Emergency Recovery") {
                Button(role: .destructive) {
                    store.isPresentingResetDatabaseSheet = true
                } label: {
                    Label(store.strings.resetDatabase, systemImage: "trash")
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
        MacSoftwareUpdateContentView(showsSheetChrome: false)
    }
}

// MARK: - 3. In-App macOS Software Update Sheet & Settings Panel (No DMG Required)

public struct MacSoftwareUpdateSheet: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.dismiss) private var dismiss

    public init() {}

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: "arrow.triangle.2.circlepath.circle.fill")
                    .font(.system(size: 28))
                    .foregroundStyle(Color.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text("NexusTS In-App Software Update")
                        .font(.headline)
                    Text("Downloads, verifies SHA-256, replaces NexusTS.app in place, and relaunches automatically — no DMG needed.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(16)
            .background(.bar)

            Divider()

            MacSoftwareUpdateContentView(showsSheetChrome: true)

            Divider()

            HStack {
                Spacer()
                Button("Close") {
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)
                .disabled(store.updateInstallPhase.isBusy)
            }
            .padding(12)
            .background(.bar)
        }
        .frame(width: 580, height: 490)
    }
}

private struct MacSoftwareUpdateContentView: View {
    @EnvironmentObject private var store: WorkspaceStore
    let showsSheetChrome: Bool

    @State private var githubTokenInput: String = ""
    @State private var detectedAuthSource: String = "None configured"
    @State private var tokenSavedNotice: String? = nil

    private func refreshAuthStatus() {
        detectedAuthSource = MacGitHubReleaseUpdater.resolveGitHubAuthToken()?.source ?? "None configured"
    }

    var body: some View {
        Form {
            Section("In-App Auto-Update (Universal2 App Bundle)") {
                LabeledContent("Installed Version", value: "NexusTS v\(MacGitHubReleaseUpdater.currentVersion) (Universal2)")
                LabeledContent("Install Location") {
                    Text(MacGitHubReleaseUpdater.resolveTargetInstallAppURL().path)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Toggle("Automatically check for updates on launch & in background", isOn: $store.autoCheckUpdatesEnabled)

                HStack(spacing: 10) {
                    Button("Check for Updates Now") {
                        Task { await store.checkGitHubUpdates(manual: true) }
                    }
                    .disabled(store.updateInstallPhase.isBusy)

                    if store.updateInstallPhase.isBusy {
                        ProgressView()
                            .controlSize(.small)
                    }

                    if !store.updateInstallPhase.statusText.isEmpty {
                        Text(store.updateInstallPhase.statusText)
                            .font(.caption)
                            .foregroundStyle(
                                {
                                    if case .failed = store.updateInstallPhase {
                                        return Color(nsColor: .systemRed)
                                    }
                                    return Color.secondary
                                }()
                            )
                    }
                }

                if case .downloading(let progress) = store.updateInstallPhase {
                    ProgressView(value: min(1.0, max(0.0, progress)))
                        .progressViewStyle(.linear)
                }
            }

            if let rel = store.updateCandidate {
                Section("Available Update: \(rel.tagName)") {
                    LabeledContent("Release", value: rel.releaseTitle)
                    if rel.zipSizeBytes > 0 {
                        let mb = String(format: "%.1f MB", Double(rel.zipSizeBytes) / (1024.0 * 1024.0))
                        LabeledContent("Bundle Size", value: "\(rel.zipAssetName ?? "macOS Universal .zip") (\(mb))")
                    }
                    if !rel.releaseNotes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        ScrollView {
                            Text(rel.releaseNotes)
                                .font(.caption)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                        }
                        .frame(maxHeight: showsSheetChrome ? 110 : 80)
                    }

                    HStack(spacing: 10) {
                        Button("Install Update & Relaunch") {
                            Task { await store.installAvailableUpdateAndRelaunch() }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(store.updateInstallPhase.isBusy)

                        Button("View Release Notes on GitHub") {
                            NSWorkspace.shared.open(rel.htmlReleaseUrl)
                        }
                    }
                }
            }

            Section("GitHub Access Token (Private Repository Releases)") {
                LabeledContent("Detected Credential Source", value: detectedAuthSource)
                SecureField("Optional GitHub Personal Access Token (Contents: Read-only)", text: $githubTokenInput)
                HStack(spacing: 8) {
                    Button("Save Token in Keychain") {
                        MacGitHubReleaseUpdater.saveKeychainGitHubToken(githubTokenInput)
                        githubTokenInput = ""
                        refreshAuthStatus()
                        tokenSavedNotice = "Saved to macOS Keychain."
                        Task { await store.checkGitHubUpdates(manual: true) }
                    }
                    .disabled(githubTokenInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                    if MacGitHubReleaseUpdater.loadKeychainGitHubToken() != nil {
                        Button("Clear Keychain Token", role: .destructive) {
                            MacGitHubReleaseUpdater.saveKeychainGitHubToken("")
                            refreshAuthStatus()
                            tokenSavedNotice = "Cleared Keychain token."
                        }
                    }

                    if let notice = tokenSavedNotice {
                        Text(notice)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .onAppear {
            refreshAuthStatus()
        }
    }
}
