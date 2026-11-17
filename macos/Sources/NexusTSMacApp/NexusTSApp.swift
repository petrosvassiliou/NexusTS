import AppKit
import CoreSpotlight
import Darwin
import NexusTSCore
import SwiftUI

public final class NexusMacAppDelegate: NSObject, NSApplicationDelegate {
    public func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }

    public func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        if !flag {
            for window in sender.windows where window.isMiniaturized {
                window.deminiaturize(nil)
                return false
            }
            return true
        }
        return true
    }
}

@main
public struct NexusTSApp: App {
    @NSApplicationDelegateAdaptor(NexusMacAppDelegate.self) private var appDelegate
    @StateObject private var store = WorkspaceStore()

    public init() {
        if CommandLine.arguments.contains("--self-test") {
            let en = LocalizedWorkspaceStrings.catalog(for: .en)
            let de = LocalizedWorkspaceStrings.catalog(for: .de)
            guard !en.newConnection.isEmpty, !de.newConnection.isEmpty else {
                Darwin.exit(1)
            }
            print("NEXUSTS_MACOS_SELF_TEST_OK version=\(MacGitHubReleaseUpdater.currentVersion)")
            fflush(stdout)
            Darwin.exit(0)
        }
        // Disable AppKit's automatic window tabbing so macOS does not inject "File > New Tab (⌘T)"
        // and hijack ⌘T away from opening a NexusTS Local Terminal tab.
        NSWindow.allowsAutomaticWindowTabbing = false
    }

    public var body: some Scene {
        WindowGroup(id: "main-workspace") {
            NexusMainWindowView()
                .environmentObject(store)
                .frame(minWidth: 980, minHeight: 620)
                .onOpenURL { incomingURL in
                    handleIncomingURL(incomingURL)
                }
                .onContinueUserActivity(CSSearchableItemActionType) { activity in
                    if let identifier = activity.userInfo?[CSSearchableItemActivityIdentifier] as? String,
                       let connId = MacSpotlightVaultIndexer.connectionId(fromSpotlightIdentifier: identifier),
                       let conn = store.activeConnections.first(where: { $0.id == connId }) {
                        store.openConnection(conn)
                    }
                }
        }
        .windowToolbarStyle(.unified(showsTitle: true))
        .defaultSize(width: 1380, height: 860)
        .commands {
            ToolbarCommands()
            NexusMenuBarCommands(store: store)
        }

        WindowGroup("Detached Session", id: "detached-session", for: UUID.self) { $tabId in
            if let id = tabId {
                DetachedSessionWindowRootView(tabId: id)
                    .environmentObject(store)
                    .frame(minWidth: 720, minHeight: 480)
            }
        }
        .windowToolbarStyle(.unifiedCompact)

        Settings {
            MacSettingsView()
                .environmentObject(store)
        }

        MenuBarExtra("NexusTS", systemImage: "network.badge.shield.half.filled") {
            MenuBarQuickAgentView()
                .environmentObject(store)
        }
        .menuBarExtraStyle(.window)
    }

    private func handleIncomingURL(_ url: URL) {
        if url.isFileURL {
            do {
                _ = try store.importExternalVaultFile(url: url)
            } catch {
                store.reportError(error, operationTitle: "Vault File Open Failed")
            }
        } else if let adHoc = QuickConnectParser.parseAdHocUri(url.absoluteString) {
            store.openConnection(
                adHoc.toAdHocVaultConnection(),
                adHocUsername: adHoc.username,
                adHocPassword: adHoc.password,
                adHocDomain: adHoc.domain
            )
        } else {
            store.reportAppError(
                NexusAppError(
                    category: .validation,
                    title: "Unsupported Connection URI",
                    userMessage: "Could not parse the incoming URI: \(url.absoluteString)",
                    remediationHint: "Supported schemes include ssh://, rdp://, vnc://, ard://, sftp://, serial://, k8s://, and docker://."
                )
            )
        }
    }
}

// MARK: - Idiomatic macOS Menu Bar Commands

struct NexusMenuBarCommands: Commands {
    @ObservedObject var store: WorkspaceStore

    var body: some Commands {
        CommandGroup(after: .appInfo) {
            Button("Check for Updates…") {
                store.isPresentingSoftwareUpdateSheet = true
                Task { await store.checkGitHubUpdates(manual: true) }
            }
        }

        // Replace SwiftUI's default "New Window (⌘N)" so ⌘N opens New Connection instead of spawning a new window.
        CommandGroup(replacing: .newItem) {
            Button(MacWorkspaceShortcut.newConnection.menuTitle(in: store.strings)) {
                store.presentNewConnectionSheet()
            }
            .keyboardShortcut("n", modifiers: [.command])
            .disabled(store.isVaultLocked)

            Button(MacWorkspaceShortcut.newFolder.menuTitle(in: store.strings)) {
                store.presentNewFolderSheet()
            }
            .keyboardShortcut("n", modifiers: [.command, .shift])
            .disabled(store.isVaultLocked)

            Button(store.strings.newSmartFolder) {
                store.isPresentingNewSmartFolderSheet = true
            }
            .disabled(store.isVaultLocked)

            Button(store.strings.newCredential) {
                store.isPresentingNewCredentialSheet = true
            }
            .disabled(store.isVaultLocked)

            Divider()

            Button(MacWorkspaceShortcut.localTerminal.menuTitle(in: store.strings)) {
                store.openLocalTerminal()
            }
            .keyboardShortcut("t", modifiers: [.command])
            .disabled(store.isVaultLocked)

            Button(MacWorkspaceShortcut.quickConnect.menuTitle(in: store.strings)) {
                store.toggleQuickConnect()
            }
            .keyboardShortcut("k", modifiers: [.command])
            .disabled(store.isVaultLocked)
        }

        CommandGroup(after: .toolbar) {
            Divider()

            Toggle(
                MacWorkspaceShortcut.toggleSidebar.title(in: store.strings),
                isOn: Binding(
                    get: { store.columnVisibility != .detailOnly },
                    set: { show in store.columnVisibility = show ? .all : .detailOnly }
                )
            )
            .keyboardShortcut("s", modifiers: [.command, .control])
            .disabled(store.isVaultLocked)

            Toggle(
                MacWorkspaceShortcut.toggleInspector.title(in: store.strings),
                isOn: Binding(
                    get: { store.isInspectorPresented },
                    set: { newValue in
                        if newValue && store.isFullScreenSessionMode {
                            store.isFullScreenSessionMode = false
                        }
                        store.isInspectorPresented = newValue
                    }
                )
            )
            .keyboardShortcut("i", modifiers: [.command, .option])
            .disabled(store.isVaultLocked)

            Toggle(
                MacWorkspaceShortcut.toggleAuditConsole.title(in: store.strings),
                isOn: $store.isBottomConsolePresented
            )
            .keyboardShortcut("j", modifiers: [.command, .shift])
            .disabled(store.isVaultLocked)

            Toggle(
                MacWorkspaceShortcut.fullScreenSession.title(in: store.strings),
                isOn: Binding(
                    get: { store.isFullScreenSessionMode },
                    set: { _ in store.toggleFullScreenSessionMode() }
                )
            )
            .keyboardShortcut("f", modifiers: [.command, .shift])
            .disabled(store.isVaultLocked || (store.paneTree.allTabs.isEmpty && !store.isFullScreenSessionMode))
        }

        CommandMenu("Workspace") {
            Button(store.strings.connections) {
                store.selectSection(.sessions)
            }
            .keyboardShortcut("1", modifiers: [.command])
            .disabled(store.isVaultLocked)

            Button(store.strings.overview) {
                store.selectSection(.overview)
            }
            .keyboardShortcut("2", modifiers: [.command])
            .disabled(store.isVaultLocked)

            Button(store.strings.templates) {
                store.selectSection(.templates)
            }
            .keyboardShortcut("3", modifiers: [.command])
            .disabled(store.isVaultLocked)

            Button(store.strings.credentials) {
                store.selectSection(.credentials)
            }
            .keyboardShortcut("4", modifiers: [.command])
            .disabled(store.isVaultLocked)

            Button(store.strings.gateways) {
                store.selectSection(.gateways)
            }
            .keyboardShortcut("5", modifiers: [.command])
            .disabled(store.isVaultLocked)

            Button(store.strings.automationTasks) {
                store.selectSection(.automationTasks)
            }
            .keyboardShortcut("6", modifiers: [.command])
            .disabled(store.isVaultLocked)

            Button(store.strings.cloudSync) {
                store.selectSection(.cloudSync)
            }
            .keyboardShortcut("7", modifiers: [.command])
            .disabled(store.isVaultLocked)

            Divider()

            Button(MacWorkspaceShortcut.splitRight.title(in: store.strings)) {
                store.splitFocusedPane(.horizontal)
            }
            .keyboardShortcut("d", modifiers: [.command])
            .disabled(store.isVaultLocked)

            Button(MacWorkspaceShortcut.splitDown.title(in: store.strings)) {
                store.splitFocusedPane(.vertical)
            }
            .keyboardShortcut("d", modifiers: [.command, .shift])
            .disabled(store.isVaultLocked)

            Divider()

            Toggle(
                MacWorkspaceShortcut.broadcastInput.title(in: store.strings),
                isOn: $store.broadcastInputEnabled
            )
            .keyboardShortcut("b", modifiers: [.command, .shift])
            .disabled(store.isVaultLocked)

            Divider()

            let focusedTab = store.focusedOrFirstActiveTab
            let focusedTabIsRdp = focusedTab.map {
                !$0.isLocalShell && (
                    $0.connection.protocolType == .RDP ||
                    $0.connection.protocolType == .WINDOWS_TERMINAL_SERVICES
                )
            } ?? false

            Button(store.strings.disconnectSession) {
                if let tab = store.focusedOrFirstActiveTab {
                    store.closeTab(tab.id)
                }
            }
            .disabled(store.isVaultLocked || focusedTab == nil)

            Button(store.strings.logOffSession) {
                if let tab = store.focusedOrFirstActiveTab {
                    store.logOffTabSession(tab.id)
                }
            }
            .disabled(store.isVaultLocked || !focusedTabIsRdp)

            Divider()

            Button(MacWorkspaceShortcut.syncNow.title(in: store.strings)) {
                Task { await store.syncCloudNow(reportErrors: true) }
            }
            .keyboardShortcut("s", modifiers: [.command, .shift])
            .disabled(store.isVaultLocked)

            Button(MacWorkspaceShortcut.lockVault.title(in: store.strings)) {
                store.lockVault()
            }
            .keyboardShortcut("l", modifiers: [.command, .shift])
            .disabled(store.isVaultLocked)

            Divider()

            Button(role: .destructive) {
                store.isPresentingResetDatabaseSheet = true
            } label: {
                Text(store.strings.resetDatabase)
            }
        }
    }
}
