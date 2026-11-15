import AppKit
import CoreSpotlight
import NexusTSCore
import SwiftUI

@main
public struct NexusTSApp: App {
    @StateObject private var store = WorkspaceStore()

    public init() {
        NSWindow.allowsAutomaticWindowTabbing = true
    }

    public var body: some Scene {
        WindowGroup {
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
            SidebarCommands()
            InspectorCommands()
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
        CommandGroup(after: .newItem) {
            Button("New Connection…") {
                let targetFolder = store.selectedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId
                    ? nil
                    : store.selectedFolderId
                store.newConnectionInitialFolderId = targetFolder
                store.newConnectionInitialProtocol = .SSH
                store.isPresentingNewConnectionSheet = true
            }
            .keyboardShortcut("n", modifiers: [.command])

            Button("New Folder…") {
                let parentFolder = store.selectedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId
                    ? nil
                    : store.selectedFolderId
                store.newFolderInitialParentId = parentFolder
                store.isPresentingNewFolderSheet = true
            }
            .keyboardShortcut("n", modifiers: [.command, .shift])

            Button(store.strings.newSmartFolder) {
                store.isPresentingNewSmartFolderSheet = true
            }

            Button(store.strings.newDynamicFolder) {
                store.isPresentingNewDynamicFolderSheet = true
            }

            Button(store.strings.newCredential) {
                store.isPresentingNewCredentialSheet = true
            }

            Button("New Local Terminal (/bin/zsh)") {
                store.openLocalTerminal()
            }
            .keyboardShortcut("t", modifiers: [.command])

            Divider()

            Button("Quick Connect Palette…") {
                store.isQuickConnectPresented.toggle()
            }
            .keyboardShortcut("k", modifiers: [.command])
        }

        CommandMenu("Workspace") {
            Button(store.strings.connections) {
                store.activeSection = .sessions
            }
            .keyboardShortcut("1", modifiers: [.command])

            Button(store.strings.overview) {
                store.activeSection = .overview
            }
            .keyboardShortcut("2", modifiers: [.command])

            Button(store.strings.templates) {
                store.activeSection = .templates
            }
            .keyboardShortcut("3", modifiers: [.command])

            Button(store.strings.credentials) {
                store.activeSection = .credentials
            }
            .keyboardShortcut("4", modifiers: [.command])

            Button(store.strings.gateways) {
                store.activeSection = .gateways
            }
            .keyboardShortcut("5", modifiers: [.command])

            Button(store.strings.automationTasks) {
                store.activeSection = .automationTasks
            }
            .keyboardShortcut("6", modifiers: [.command])

            Button(store.strings.cloudSync) {
                store.activeSection = .cloudSync
            }
            .keyboardShortcut("7", modifiers: [.command])

            Divider()

            Button("Split Active Pane Right") {
                store.splitFocusedPane(.horizontal)
            }
            .keyboardShortcut("d", modifiers: [.command])

            Button("Split Active Pane Down") {
                store.splitFocusedPane(.vertical)
            }
            .keyboardShortcut("d", modifiers: [.command, .shift])

            Divider()

            Toggle("Broadcast Keyboard Input to All Terminals", isOn: $store.broadcastInputEnabled)
                .keyboardShortcut("b", modifiers: [.command, .shift])

            Toggle("Toggle Audit & Broadcast Console", isOn: $store.isBottomConsolePresented)
                .keyboardShortcut("j", modifiers: [.command, .shift])

            Toggle("Toggle Property Inspector", isOn: $store.isInspectorPresented)
                .keyboardShortcut("i", modifiers: [.command, .option])

            Divider()

            Button("Synchronize Cloud Vault Now") {
                Task { await store.syncCloudNow(reportErrors: true) }
            }
            .keyboardShortcut("s", modifiers: [.command, .shift])

            Button("Lock Zero-Knowledge Vault") {
                store.lockVault()
            }
            .keyboardShortcut("l", modifiers: [.command, .shift])

            Divider()

            Button(role: .destructive) {
                store.isPresentingResetDatabaseSheet = true
            } label: {
                Text(store.strings.resetDatabase)
            }
        }
    }
}
