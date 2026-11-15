import AppKit
import NexusTSCore
import SwiftUI

public struct SplitPaneWorkspaceView: View {
    @EnvironmentObject private var store: WorkspaceStore

    public init() {}

    public var body: some View {
        if store.paneTree.allTabs.isEmpty {
            VaultOverviewDashboardView()
        } else {
            RecursivePaneNodeView(node: store.paneTree)
        }
    }
}

private struct RecursivePaneNodeView: View {
    @EnvironmentObject private var store: WorkspaceStore
    let node: WorkspacePaneNode

    var body: some View {
        switch node {
        case .leaf(let paneId, let tabs, let activeTabId):
            LeafPaneContainerView(paneId: paneId, tabs: tabs, activeTabId: activeTabId)

        case .split(let splitId, let orientation, let ratio, let first, let second):
            GeometryReader { geo in
                if orientation == .horizontal {
                    let firstW = max(160, geo.size.width * ratio - 3)
                    HStack(spacing: 0) {
                        RecursivePaneNodeView(node: first)
                            .frame(width: firstW)

                        PaneDividerHandle(orientation: .horizontal)
                            .gesture(
                                DragGesture(minimumDistance: 2)
                                    .onChanged { value in
                                        guard geo.size.width > 0 else { return }
                                        let nextRatio = Double(value.location.x / geo.size.width)
                                        store.paneTree = store.paneTree.updatingSplitRatio(
                                            splitId: splitId,
                                            ratio: nextRatio
                                        )
                                    }
                            )

                        RecursivePaneNodeView(node: second)
                            .frame(maxWidth: .infinity)
                    }
                } else {
                    let firstH = max(120, geo.size.height * ratio - 3)
                    VStack(spacing: 0) {
                        RecursivePaneNodeView(node: first)
                            .frame(height: firstH)

                        PaneDividerHandle(orientation: .vertical)
                            .gesture(
                                DragGesture(minimumDistance: 2)
                                    .onChanged { value in
                                        guard geo.size.height > 0 else { return }
                                        let nextRatio = Double(value.location.y / geo.size.height)
                                        store.paneTree = store.paneTree.updatingSplitRatio(
                                            splitId: splitId,
                                            ratio: nextRatio
                                        )
                                    }
                            )

                        RecursivePaneNodeView(node: second)
                            .frame(maxHeight: .infinity)
                    }
                }
            }
        }
    }
}

private struct PaneDividerHandle: View {
    let orientation: SplitOrientation
    @State private var isHovering = false

    var body: some View {
        ZStack {
            Rectangle()
                .fill(isHovering ? Color.accentColor.opacity(0.45) : Color(nsColor: .separatorColor))
        }
        .frame(
            width: orientation == .horizontal ? 5 : nil,
            height: orientation == .vertical ? 5 : nil
        )
        .contentShape(Rectangle())
        .onHover { inside in
            isHovering = inside
            if inside {
                (orientation == .horizontal ? NSCursor.resizeLeftRight : NSCursor.resizeUpDown).push()
            } else {
                NSCursor.pop()
            }
        }
    }
}

private struct LeafPaneContainerView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow

    let paneId: UUID
    let tabs: [WorkspaceSessionTab]
    let activeTabId: UUID?

    private var activeTab: WorkspaceSessionTab? {
        tabs.first(where: { $0.id == activeTabId }) ?? tabs.first
    }

    var body: some View {
        let isFocusedPane = store.focusedPaneId == paneId

        VStack(spacing: 0) {
            // Safari / Xcode-style macOS Document Tab Strip
            HStack(spacing: 4) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 4) {
                        ForEach(tabs) { tab in
                            MacTabPillView(
                                tab: tab,
                                isActive: tab.id == activeTab?.id,
                                onSelect: {
                                    store.focusedPaneId = paneId
                                    store.paneTree = store.paneTree.activatingTab(tab.id, in: paneId)
                                    if !tab.isLocalShell {
                                        store.selectedConnectionId = tab.connection.id
                                    }
                                },
                                onClose: {
                                    store.closeTab(tab.id)
                                },
                                onDetachWindow: {
                                    if let detachedId = store.stageTabForDetachedWindow(tab.id) {
                                        openWindow(id: "detached-session", value: detachedId)
                                    }
                                },
                                onEnterFullScreen: {
                                    store.enterFullScreenSession(tabId: tab.id)
                                },
                                onDropTabPayload: { payload in
                                    handleTabDropPayload(payload, beforeTabId: tab.id)
                                }
                            )
                        }
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 4)
                }

                Spacer(minLength: 4)

                HStack(spacing: 6) {
                    if let activeTab {
                        Button {
                            store.enterFullScreenSession(tabId: activeTab.id)
                        } label: {
                            Image(systemName: "arrow.up.left.and.arrow.down.right")
                                .font(.caption)
                        }
                        .buttonStyle(.plain)
                        .help("\(MacWorkspaceShortcut.fullScreenSession.title(in: store.strings)) (\(MacWorkspaceShortcut.fullScreenSession.keycap))")
                    }

                    Button {
                        store.focusedPaneId = paneId
                        store.openLocalTerminal()
                    } label: {
                        Image(systemName: "plus")
                            .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(.plain)
                    .help("\(MacWorkspaceShortcut.localTerminal.title(in: store.strings)) (\(MacWorkspaceShortcut.localTerminal.keycap))")

                    Button {
                        let (updated, newId) = store.paneTree.splittingPane(
                            paneId,
                            orientation: .horizontal,
                            newTab: WorkspaceSessionTab.makeLocalZshTab()
                        )
                        store.paneTree = updated
                        store.focusedPaneId = newId
                    } label: {
                        Image(systemName: "rectangle.split.2x1")
                            .font(.caption)
                    }
                    .buttonStyle(.plain)
                    .help("\(MacWorkspaceShortcut.splitRight.title(in: store.strings)) (\(MacWorkspaceShortcut.splitRight.keycap))")
                }
                .foregroundStyle(.secondary)
                .padding(.trailing, 10)
            }
            .padding(.vertical, 2)
            .background(.ultraThinMaterial)
            .dropDestination(for: String.self) { payloads, _ in
                for p in payloads {
                    handleTabDropPayload(p, beforeTabId: nil)
                }
                return true
            }
            .overlay(alignment: .top) {
                if isFocusedPane && store.paneTree.allLeafIds.count > 1 {
                    Rectangle()
                        .fill(Color.accentColor)
                        .frame(height: 2)
                }
            }

            Divider()

            if let tab = activeTab {
                SessionCanvasRouterView(tab: tab)
                    .id(tab.id)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .simultaneousGesture(
                        TapGesture().onEnded {
                            store.focusedPaneId = paneId
                        }
                    )
            } else {
                ContentUnavailableView(
                    "Empty Split Pane",
                    systemImage: "terminal",
                    description: Text("Select a connection from the sidebar or press ⌘T for a local macOS shell.")
                )
            }
        }
    }

    private func handleTabDropPayload(_ payload: String, beforeTabId: UUID?) {
        if payload.hasPrefix("tab:"),
           let draggedId = UUID(uuidString: String(payload.dropFirst("tab:".count))) {
            store.paneTree = store.paneTree.reorderingOrMovingTab(
                draggedId,
                before: beforeTabId,
                in: paneId
            )
            store.focusedPaneId = paneId
        } else if payload.hasPrefix("conn:") {
            let connId = String(payload.dropFirst("conn:".count))
            if let conn = store.activeConnections.first(where: { $0.id == connId }) {
                store.focusedPaneId = paneId
                store.openConnection(conn)
            }
        }
    }
}

private struct MacTabPillView: View {
    let tab: WorkspaceSessionTab
    let isActive: Bool
    let onSelect: () -> Void
    let onClose: () -> Void
    let onDetachWindow: () -> Void
    let onEnterFullScreen: () -> Void
    let onDropTabPayload: (String) -> Void
    @State private var isHovering = false
    @State private var isDropTargeted = false

    var body: some View {
        let accent = HIGColorUtilities.color(fromHex: tab.accentHex) ?? tab.connection.protocolType.appleSystemTint

        HStack(spacing: 6) {
            Button(action: onSelect) {
                HStack(spacing: 6) {
                    Image(systemName: tab.isLocalShell ? "apple.terminal" : tab.connection.protocolType.sfSymbolName)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(accent)

                    Text(tab.title)
                        .font(.system(size: 12, weight: isActive ? .semibold : .regular))
                        .foregroundStyle(.primary)
                        .lineLimit(1)

                    if tab.broadcastEnabled {
                        Image(systemName: "dot.radiowaves.left.and.right")
                            .font(.system(size: 10))
                            .foregroundStyle(.orange)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 14, height: 14)
            }
            .buttonStyle(.plain)
            .opacity(isHovering || isActive ? 1.0 : 0.35)
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 5)
        .liquidGlassCapsule(tint: accent, isSelected: isActive || isDropTargeted, isHovered: isHovering)
        .contentShape(Capsule(style: .continuous))
        .onHover { hovering in
            withAnimation(LiquidGlassMetrics.hoverAnimation) {
                isHovering = hovering
            }
        }
        .draggable("tab:\(tab.id.uuidString)")
        .dropDestination(for: String.self) { payloads, _ in
            for p in payloads {
                onDropTabPayload(p)
            }
            return true
        } isTargeted: { hovering in
            isDropTargeted = hovering
        }
        .contextMenu {
            Button("Full Screen Session") { onEnterFullScreen() }
            Button("Move Tab to New Window") { onDetachWindow() }
            Divider()
            Button("Close Tab", role: .destructive) { onClose() }
        }
    }
}

// MARK: - Protocol Session Router

public struct SessionCanvasRouterView: View {
    public let tab: WorkspaceSessionTab

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    public var body: some View {
        if tab.isLocalShell {
            TerminalSessionView(tab: tab)
        } else {
            switch tab.connection.protocolType {
            case .SSH, .TELNET:
                TerminalSessionView(tab: tab)
            case .RDP:
                RdpSessionView(tab: tab)
            case .VNC, .APPLE_SCREEN_SHARING:
                VncScreenSharingView(tab: tab)
            case .SFTP, .FTP, .FTPS, .SCP:
                DualPaneTransferView(tab: tab)
            case .SERIAL:
                SerialConsoleView(tab: tab)
            case .WEB_PAGE:
                IsolatedWebSessionView(tab: tab)
            case .KUBERNETES_POD, .DOCKER_CONTAINER:
                ContainerWorkloadStudioView(tab: tab)
            case .VMWARE_VSPHERE, .PROXMOX_VE, .HYPER_V, .AWS_EC2, .AZURE_VM, .WINDOWS_TERMINAL_SERVICES:
                CloudHypervisorSessionView(tab: tab)
            case .UTILITY_PING, .UTILITY_TRACEROUTE, .UTILITY_PORT_SCANNER, .UTILITY_WAKE_ON_LAN:
                NetworkDiagnosticsSessionView(tab: tab)
            }
        }
    }
}

// MARK: - Secondary Detached Session Window Root

public struct DetachedSessionWindowRootView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.dismiss) private var dismiss
    public let tabId: UUID

    public init(tabId: UUID) {
        self.tabId = tabId
    }

    public var body: some View {
        if let tab = store.detachedWindowTabs[tabId] {
            SessionCanvasRouterView(tab: tab)
                .navigationTitle(tab.title)
                .navigationSubtitle("\(tab.connection.protocolType.displayName) • \(tab.connection.host)")
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            store.reDockDetachedTab(tabId)
                            dismiss()
                        } label: {
                            Label("Re-Dock in Main Window", systemImage: "macwindow.on.rectangle")
                        }
                        .help("Move live session back into the main workspace split-pane tree without disconnecting")
                    }
                }
        } else {
            ContentUnavailableView(
                "Session Re-Docked or Closed",
                systemImage: "checkmark.circle",
                description: Text("This session was returned to the main NexusTS window.")
            )
        }
    }
}
