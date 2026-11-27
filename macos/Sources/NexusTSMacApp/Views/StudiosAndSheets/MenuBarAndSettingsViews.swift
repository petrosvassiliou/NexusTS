import AppKit
import NexusTSCore
import SwiftUI

// MARK: - 1. macOS MenuBarExtra Quick-Connect Agent

public struct MenuBarQuickAgentView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow

    public init() {}

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
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
            .padding(.bottom, 3)

            Divider()

            if store.isVaultLocked {
                Button {
                    NSApp.setActivationPolicy(.regular)
                    openWindow(id: "main-workspace")
                    NSApp.activate(ignoringOtherApps: true)
                } label: {
                    Label(store.strings.unlockVault, systemImage: "lock.fill")
                }
            } else {
                Button {
                    NSApp.setActivationPolicy(.regular)
                    openWindow(id: "main-workspace")
                    NSApp.activate(ignoringOtherApps: true)
                } label: {
                    Label(store.strings.openWorkspaceAction, systemImage: "macwindow")
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
                    Text(store.strings.favorites)
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

                let totpCreds = store.activeCredentials.filter { !$0.totpSecretBase32.isEmpty }
                if !totpCreds.isEmpty {
                    Divider()
                    Text(store.strings.copyTotpHeader)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)

                    ForEach(totpCreds) { cred in
                        Button {
                            if let otp = TotpCodeGenerator.generateTotp(base32Secret: cred.totpSecretBase32) {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(otp, forType: .string)
                            }
                        } label: {
                            Label("TOTP: \(cred.name)", systemImage: "clock.badge.checkmark")
                        }
                    }
                }
            }

            Divider()

            Button {
                Task { await store.syncCloudNow(reportErrors: true) }
            } label: {
                Label(store.strings.syncNow, systemImage: "arrow.triangle.2.circlepath.icloud")
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
                    Label(store.strings.installUpdateFormat(rel.tagName), systemImage: "arrow.down.circle.fill")
                } else {
                    Label(store.strings.checkForUpdatesMenuAction, systemImage: "arrow.triangle.2.circlepath.circle")
                }
            }

            Button {
                store.lockVault()
            } label: {
                Label(store.strings.lockVault, systemImage: "lock.fill")
            }

            Divider()

            Button(store.strings.quitAppAction) {
                NSApplication.shared.terminate(nil)
            }
            .keyboardShortcut("q", modifiers: [.command])
        }
        .padding(10)
        .frame(width: 288)
    }
}

// MARK: - 2. Unified Tabbed Workspace Settings (Toolbar ⚙ Button & ⌘,)

public struct UnifiedWorkspaceSettingsView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let showsWorkspaceHeader: Bool

    @State private var newMasterPassphrase: String = ""
    @State private var confirmMasterPassphrase: String = ""
    @State private var saveToTouchIdKeychain: Bool = true
    @State private var securityStatusBanner: String? = nil

    public init(showsWorkspaceHeader: Bool = true) {
        self.showsWorkspaceHeader = showsWorkspaceHeader
    }

    public var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 10) {
                if showsWorkspaceHeader {
                    HStack(spacing: 10) {
                        Image(systemName: "gearshape.fill")
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(Color.accentColor)
                        Text(store.strings.settings)
                            .font(.title3.weight(.semibold))
                        Spacer()
                        Button {
                            store.selectSection(.sessions)
                        } label: {
                            Label(store.strings.connections, systemImage: "arrow.left")
                        }
                        .controlSize(.small)
                    }
                }

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(UnifiedSettingsTab.allCases) { tab in
                            SettingsTabCapsuleButton(
                                title: tab.title(in: store.strings),
                                systemImage: tab.sfSymbol,
                                isSelected: store.activeSettingsTab == tab
                            ) {
                                store.activeSettingsTab = tab
                            }
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .background(.bar)

            Divider()

            Group {
                switch store.activeSettingsTab {
                case .general:
                    generalTab
                case .cloudSync:
                    CloudSyncStudioView()
                case .security:
                    securityTab
                case .credentials:
                    CredentialsStudioView()
                case .gateways:
                    GatewaysStudioView()
                case .templates:
                    TemplatesStudioView()
                case .tasks:
                    AutomationTasksStudioView()
                case .updates:
                    MacSoftwareUpdateContentView(showsSheetChrome: false)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var generalTab: some View {
        Form {
            Section(store.strings.languageAndAppearanceSection) {
                Picker(store.strings.appearanceLabel, selection: $store.appearanceMode) {
                    ForEach(NexusAppearanceMode.allCases) { mode in
                        Text(mode.title(in: store.strings)).tag(mode)
                    }
                }
                .pickerStyle(.segmented)

                Picker(store.strings.workspaceLanguageLabel, selection: $store.appLanguage) {
                    ForEach(NexusAppLanguage.allCases) { lang in
                        Text(lang.nativeDisplayName).tag(lang)
                    }
                }

                Toggle(store.strings.spotlightIndexingToggle, isOn: $store.spotlightIndexingEnabled)
                    .onChange(of: store.spotlightIndexingEnabled) { _, enabled in
                        MacSpotlightVaultIndexer.reindexConnections(in: store.document, enabled: enabled)
                    }
            }

            Section(store.strings.terminalAndSessionsSection) {
                Picker(store.strings.monospacedFontLabel, selection: $store.terminalFontName) {
                    Text("SF Mono").tag("SF Mono")
                    Text("Menlo").tag("Menlo")
                    Text("Monaco").tag("Monaco")
                }

                Slider(value: $store.terminalFontSize, in: 10...22, step: 1) {
                    Text(store.strings.defaultFontSizeLabel(Int(store.terminalFontSize)))
                }

                Toggle(store.strings.remoteKeyCapture, isOn: $store.remoteKeyCaptureEnabled)
            }

            Section(store.strings.vaultStorageSection) {
                LabeledContent(store.strings.localVaultPathLabel) {
                    Text(WorkspaceStore.localVaultFileURL.path)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(store.strings.resetDatabase)
                            .font(.callout.weight(.medium))
                        Text(store.strings.resetDatabaseHint)
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
            Section(store.strings.vaultEncryptionSection) {
                SecureField(store.strings.newMasterPassphrasePlaceholder, text: $newMasterPassphrase)
                SecureField(store.strings.confirmMasterPassphrasePlaceholder, text: $confirmMasterPassphrase)

                Toggle(store.strings.touchIdUnlockToggle, isOn: $saveToTouchIdKeychain)
                    .disabled(!MacKeychainAndBiometrics.shared.canUseTouchIdOrCompanion)

                HStack {
                    Button(store.strings.applyEncryptionKeyAction) {
                        guard newMasterPassphrase == confirmMasterPassphrase else {
                            securityStatusBanner = store.strings.passphrasesMismatchError
                            return
                        }
                        do {
                            try store.configureMasterPassphrase(newMasterPassphrase, saveForTouchId: saveToTouchIdKeychain)
                            securityStatusBanner = newMasterPassphrase.isEmpty
                                ? "Reset to automatic passwordless sync key."
                                : (saveToTouchIdKeychain
                                    ? "Master passphrase applied (Touch ID / Apple Watch unlock enabled)."
                                    : "Master passphrase applied.")
                            newMasterPassphrase = ""
                            confirmMasterPassphrase = ""
                        } catch {
                            securityStatusBanner = error.localizedDescription
                        }
                    }
                    .buttonStyle(.borderedProminent)

                    Button {
                        store.lockVault()
                    } label: {
                        Label(store.strings.lockVault, systemImage: "lock.fill")
                    }

                    if let msg = securityStatusBanner {
                        Text(msg)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section(store.strings.emergencyRecoverySection) {
                Button(role: .destructive) {
                    store.isPresentingResetDatabaseSheet = true
                } label: {
                    Label(store.strings.resetDatabase, systemImage: "trash")
                }
            }
        }
        .formStyle(.grouped)
    }
}

private struct SettingsTabCapsuleButton: View {
    let title: String
    let systemImage: String
    let isSelected: Bool
    let action: () -> Void
    @State private var isHovered: Bool = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: systemImage)
                    .font(.system(size: 11.5, weight: .semibold))
                Text(title)
                    .font(.system(size: 12, weight: isSelected ? .semibold : .medium))
                    .lineLimit(1)
            }
            .foregroundStyle(isSelected || isHovered ? Color.primary : Color.secondary)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .liquidGlassCapsule(
                tint: Color.accentColor,
                isSelected: isSelected,
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

public struct MacSettingsView: View {
    @EnvironmentObject private var store: WorkspaceStore

    public init() {}

    public var body: some View {
        Group {
            if store.isVaultLocked {
                ZeroKnowledgeLockShieldView()
            } else {
                UnifiedWorkspaceSettingsView(showsWorkspaceHeader: false)
            }
        }
        .frame(minWidth: 780, minHeight: 540)
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
    @State private var detectedAuthSource: String = "Checking…"
    @State private var tokenSavedNotice: String? = nil

    private func refreshAuthStatus() {
        Task.detached(priority: .utility) {
            let source = MacGitHubReleaseUpdater.resolveGitHubAuthToken()?.source ?? "None configured"
            await MainActor.run {
                detectedAuthSource = source
            }
        }
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
