import AppKit
import CoreSpotlight
import Darwin
import NexusTSCore
import SwiftUI

public final class NexusMacAppDelegate: NSObject, NSApplicationDelegate {
    public func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.appearance = NSAppearance(named: .aqua)
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        MainActor.assumeIsolated {
            DiscreetTooltipController.shared.installIfNeeded()
        }
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

    public func applicationWillTerminate(_ notification: Notification) {
        NativeRdpSession.shutdownAllNativeSessions()
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
            let clipWorkDir = FileManager.default.temporaryDirectory
                .appendingPathComponent("nexusts-selftest-clip-\(UUID().uuidString)", isDirectory: true)
            defer { try? FileManager.default.removeItem(at: clipWorkDir) }
            try? FileManager.default.createDirectory(at: clipWorkDir, withIntermediateDirectories: true)
            guard !en.newConnection.isEmpty,
                  !de.newConnection.isEmpty,
                  NativeRdpSession.runNativeCliprdrBinaryRoundTripSelfTest(workDirectory: clipWorkDir.path) else {
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
                .preferredColorScheme(.light)
                .frame(minWidth: 980, minHeight: 620)
                .onAppear {
                    let s = store
                    DiscreetTooltipController.shared.stringsProvider = { [weak s] in
                        s?.strings ?? LocalizedWorkspaceStrings.catalog(for: .system)
                    }
                    DiscreetTooltipController.shared.installIfNeeded()
                }
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
                    .preferredColorScheme(.light)
                    .frame(minWidth: 720, minHeight: 480)
            }
        }
        .windowToolbarStyle(.unifiedCompact)

        Settings {
            MacSettingsView()
                .environmentObject(store)
                .preferredColorScheme(.light)
        }

        MenuBarExtra("NexusTS", systemImage: "network.badge.shield.half.filled") {
            MenuBarQuickAgentView()
                .environmentObject(store)
                .preferredColorScheme(.light)
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

        CommandGroup(replacing: .undoRedo) {
            Button("Undo") {
                dispatchEditAction(
                    selector: Selector(("undo:")),
                    rdpShortcut: .undo
                )
            }
            .keyboardShortcut("z", modifiers: [.command])

            Button("Redo") {
                dispatchEditAction(
                    selector: Selector(("redo:")),
                    rdpShortcut: .redo
                )
            }
            .keyboardShortcut("z", modifiers: [.command, .shift])
        }

        CommandGroup(replacing: .pasteboard) {
            Button("Cut") {
                dispatchEditAction(
                    selector: #selector(NSText.cut(_:)),
                    rdpShortcut: .cut
                )
            }
            .keyboardShortcut("x", modifiers: [.command])

            Button("Copy") {
                dispatchEditAction(
                    selector: #selector(NSText.copy(_:)),
                    rdpShortcut: .copy
                )
            }
            .keyboardShortcut("c", modifiers: [.command])

            Button("Paste") {
                dispatchEditAction(
                    selector: #selector(NSText.paste(_:)),
                    rdpShortcut: .paste
                )
            }
            .keyboardShortcut("v", modifiers: [.command])

            Button("Select All") {
                dispatchEditAction(
                    selector: #selector(NSText.selectAll(_:)),
                    rdpShortcut: .selectAll
                )
            }
            .keyboardShortcut("a", modifiers: [.command])
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

    private func findVisibleSessionView<T: NSView>(
        ofType type: T.Type,
        in root: NSView?,
        matching predicate: (T) -> Bool = { _ in true }
    ) -> T? {
        guard let view = root, !view.isHidden else { return nil }
        if let match = view as? T, predicate(match) {
            return match
        }
        for sub in view.subviews {
            if let found = findVisibleSessionView(ofType: type, in: sub, matching: predicate) {
                return found
            }
        }
        return nil
    }

    private func dispatchEditAction(
        selector: Selector,
        rdpShortcut: MacRdpScancodeMapper.MacCommandRemoteShortcut
    ) {
        let keyWin = NSApp.keyWindow
        if let rdpView = keyWin?.firstResponder as? RdpPointerAndKeyNSView {
            rdpView.executeRemoteCommandShortcut(rdpShortcut)
            return
        }
        if let termView = keyWin?.firstResponder as? TerminalKeyCaptureNSView {
            switch rdpShortcut {
            case .copy:
                termView.copy(nil)
            case .paste:
                termView.paste(nil)
            default:
                _ = NSApp.sendAction(selector, to: nil, from: nil)
            }
            return
        }
        if keyWin?.firstResponder is NSText ||
            keyWin?.attachedSheet != nil ||
            store.isQuickConnectPresented {
            _ = NSApp.sendAction(selector, to: nil, from: nil)
            return
        }
        if let win = keyWin {
            let preferredSession = store.focusedOrFirstActiveTab.flatMap { store.existingRdpController(for: $0) }
            if let rdpView = (preferredSession.flatMap { target in
                findVisibleSessionView(
                    ofType: RdpPointerAndKeyNSView.self,
                    in: win.contentView,
                    matching: { $0.session === target }
                )
            }) ?? findVisibleSessionView(ofType: RdpPointerAndKeyNSView.self, in: win.contentView) {
                win.makeFirstResponder(rdpView)
                rdpView.executeRemoteCommandShortcut(rdpShortcut)
                return
            }
            if let termView = findVisibleSessionView(ofType: TerminalKeyCaptureNSView.self, in: win.contentView) {
                win.makeFirstResponder(termView)
                switch rdpShortcut {
                case .copy:
                    termView.copy(nil)
                    return
                case .paste:
                    termView.paste(nil)
                    return
                default:
                    break
                }
            }
        }
        _ = NSApp.sendAction(selector, to: nil, from: nil)
    }
}
