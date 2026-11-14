import NexusTSCore
import SwiftUI

public struct SidebarHierarchyView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow

    public init() {}

    private var trimmedQuery: String {
        store.sidebarSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private var filteredConnections: [VaultConnection] {
        let base: [VaultConnection] = {
            switch store.sidebarFilterMode {
            case .all:
                return store.activeConnections
            case .favorites:
                return store.activeConnections.filter(\.favorite)
            case .recents:
                return store.document.recentConnectionIds.compactMap { id in
                    store.activeConnections.first(where: { $0.id == id })
                }
            }
        }()
        guard !trimmedQuery.isEmpty else { return base }
        return base.filter { conn in
            conn.name.lowercased().contains(trimmedQuery)
                || conn.host.lowercased().contains(trimmedQuery)
                || conn.protocolType.rawValue.lowercased().contains(trimmedQuery)
                || conn.tags.contains(where: { $0.lowercased().contains(trimmedQuery) })
        }
    }

    public var body: some View {
        VStack(spacing: 4) {
            sidebarCompactHeaderBar
                .padding(.horizontal, 8)
                .padding(.top, 6)

            List {
                switch store.sidebarFilterMode {
                case .favorites:
                    let favorites = filteredConnections
                    if favorites.isEmpty {
                        Text("No pinned favorite connections.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.vertical, 6)
                    } else {
                        SidebarGroupedConnectionList(connections: favorites)
                    }

                case .recents:
                    let recents = Array(filteredConnections.prefix(12))
                    if recents.isEmpty {
                        Text("No recent connections yet.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.vertical, 6)
                    } else {
                        SidebarGroupedConnectionList(
                            connections: recents,
                            preserveInputOrderWithinGroups: true
                        )
                    }

                case .all:
                    if !trimmedQuery.isEmpty {
                        let matchingFolders = store.activeFolders.filter {
                            $0.name.lowercased().contains(trimmedQuery)
                        }
                        ForEach(matchingFolders) { folder in
                            SidebarFolderTreeBranch(folder: folder)
                        }
                        SidebarGroupedConnectionList(connections: filteredConnections)
                    } else {
                        let activeFolderIds = Set(store.activeFolders.map(\.id))
                        let rootFolders = store.activeFolders.filter {
                            $0.parentFolderId == nil || !activeFolderIds.contains($0.parentFolderId ?? "")
                        }
                        ForEach(rootFolders) { folder in
                            SidebarFolderTreeBranch(folder: folder)
                        }

                        let rootConnections = store.activeConnections
                            .filter { $0.parentFolderId == nil || !activeFolderIds.contains($0.parentFolderId ?? "") }
                        SidebarGroupedConnectionList(connections: rootConnections)
                            .dropDestination(for: String.self) { items, _ in
                                store.handleSidebarDrop(items, intoFolderId: nil)
                                return true
                            }

                        let smartList = store.document.smartFolders.filter {
                            !store.document.tombstonedObjectIds.contains($0.id)
                        }
                        if !smartList.isEmpty {
                            Section(store.strings.smartFolders) {
                                ForEach(smartList) { sf in
                                    let matches = PropertyInheritanceResolver.evaluateSmartFolder(sf, in: store.document)
                                    DisclosureGroup {
                                        SidebarGroupedConnectionList(connections: matches)
                                    } label: {
                                        Label(sf.name, systemImage: "gearshape.2")
                                            .font(.system(size: 12.5, weight: .medium))
                                            .badge(matches.count)
                                    }
                                }
                            }
                        }

                        let dynList = store.document.dynamicFolders.filter {
                            !store.document.tombstonedObjectIds.contains($0.id)
                        }
                        if !dynList.isEmpty {
                            Section(store.strings.dynamicFolders) {
                                ForEach(dynList) { dyn in
                                    let cached = store.dynamicFolderCache[dyn.id] ?? []
                                    DisclosureGroup {
                                        SidebarGroupedConnectionList(connections: cached)
                                    } label: {
                                        HStack {
                                            Label(dyn.name, systemImage: "bolt.badge.clock")
                                                .font(.system(size: 12.5, weight: .medium))
                                            Spacer()
                                            Button {
                                                Task { await store.refreshDynamicFolder(dyn) }
                                            } label: {
                                                Image(systemName: "arrow.clockwise")
                                                    .font(.caption2)
                                            }
                                            .buttonStyle(.plain)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .listStyle(.sidebar)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, 25)
            .contextMenu {
                Button {
                    store.newFolderInitialParentId = nil
                    store.isPresentingNewFolderSheet = true
                } label: {
                    Label(store.strings.newFolder, systemImage: "folder.badge.plus")
                }

                Button {
                    store.newConnectionInitialFolderId = nil
                    store.newConnectionInitialProtocol = .RDP
                    store.isPresentingNewConnectionSheet = true
                } label: {
                    Label("New RDP Connection…", systemImage: "display")
                }

                Button {
                    store.newConnectionInitialFolderId = nil
                    store.newConnectionInitialProtocol = .SSH
                    store.isPresentingNewConnectionSheet = true
                } label: {
                    Label("New SSH Connection…", systemImage: "terminal")
                }

                if !store.activeFolders.isEmpty {
                    Divider()
                    Button {
                        store.expandAllFolders()
                    } label: {
                        Label("Expand All Folders", systemImage: "arrow.up.left.and.arrow.down.right")
                    }
                    Button {
                        store.collapseAllFolders()
                    } label: {
                        Label("Collapse All Folders", systemImage: "arrow.down.right.and.arrow.up.left")
                    }
                }
            }
        }
        .background(LiquidGlassBackdropAura())
        .searchable(text: $store.sidebarSearchQuery, placement: .sidebar, prompt: "Filter folders & hosts")
    }

    @ViewBuilder
    private var sidebarCompactHeaderBar: some View {
        let allCollapsed = !store.activeFolders.isEmpty
            && store.collapsedFolderIds.count >= store.activeFolders.count

        HStack(spacing: 4) {
            sidebarFilterPill(
                mode: .all,
                label: store.strings.connections,
                systemImage: "folder"
            )
            sidebarFilterPill(
                mode: .favorites,
                label: nil,
                systemImage: "star.fill",
                helpText: store.strings.favorites
            )
            sidebarFilterPill(
                mode: .recents,
                label: nil,
                systemImage: "clock",
                helpText: store.strings.recentSessions
            )

            Spacer(minLength: 4)

            SidebarGlassIconButton(
                systemImage: "folder.badge.plus",
                helpText: store.strings.newFolder
            ) {
                store.newFolderInitialParentId = store.selectedFolderId
                store.isPresentingNewFolderSheet = true
            }

            SidebarGlassIconButton(
                systemImage: "plus",
                helpText: store.strings.newConnection
            ) {
                store.newConnectionInitialFolderId = store.selectedFolderId
                store.newConnectionInitialProtocol = .RDP
                store.isPresentingNewConnectionSheet = true
            }

            if !store.activeFolders.isEmpty {
                SidebarGlassIconButton(
                    systemImage: allCollapsed ? "chevron.down.square" : "chevron.up.square",
                    helpText: allCollapsed ? "Expand All Folders" : "Collapse All Folders"
                ) {
                    if allCollapsed {
                        store.expandAllFolders()
                    } else {
                        store.collapseAllFolders()
                    }
                }
            }
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 5)
        .liquidGlassSurface(cornerRadius: 12, prominence: .subtle)
    }

    @ViewBuilder
    private func sidebarFilterPill(
        mode: SidebarFilterMode,
        label: String?,
        systemImage: String,
        helpText: String? = nil
    ) -> some View {
        SidebarFilterPillButton(
            selected: store.sidebarFilterMode == mode,
            label: label,
            systemImage: systemImage,
            helpText: helpText ?? label ?? ""
        ) {
            store.activeSection = .sessions
            store.sidebarFilterMode = mode
        }
    }
}

private struct SidebarGlassIconButton: View {
    let systemImage: String
    let helpText: String
    let action: () -> Void
    @State private var isHovered: Bool = false

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 11.5, weight: .semibold))
                .foregroundStyle(isHovered ? Color.primary : Color.secondary)
                .frame(width: 22, height: 22)
                .liquidGlassCapsule(isHovered: isHovered)
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            withAnimation(LiquidGlassMetrics.hoverAnimation) {
                isHovered = hovering
            }
        }
        .help(helpText)
    }
}

private struct SidebarFilterPillButton: View {
    let selected: Bool
    let label: String?
    let systemImage: String
    let helpText: String
    let action: () -> Void
    @State private var isHovered: Bool = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: systemImage)
                    .font(.system(size: 10.5, weight: .semibold))
                if let label {
                    Text(label)
                        .font(.system(size: 11, weight: selected ? .semibold : .medium))
                        .lineLimit(1)
                }
            }
            .foregroundStyle(selected || isHovered ? Color.primary : Color.secondary)
            .padding(.horizontal, 8)
            .padding(.vertical, 3.5)
            .liquidGlassCapsule(
                tint: Color.accentColor,
                isSelected: selected,
                isHovered: isHovered
            )
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            withAnimation(LiquidGlassMetrics.hoverAnimation) {
                isHovered = hovering
            }
        }
        .help(helpText)
    }
}

struct SidebarGroupedConnectionList: View {
    let connections: [VaultConnection]
    var preserveInputOrderWithinGroups: Bool = false

    var body: some View {
        let groups = IntelligentConnectionGrouper.group(
            connections,
            preserveInputOrderWithinGroups: preserveInputOrderWithinGroups
        )
        ForEach(groups) { group in
            if group.showsHeader {
                SidebarProtocolGroupHeader(
                    title: group.groupBadge,
                    protocolType: group.primaryProtocol,
                    count: group.connections.count
                )
            }
            ForEach(group.connections) { conn in
                SidebarConnectionRow(connection: conn)
            }
        }
    }
}

struct SidebarFolderTreeBranch: View {
    @EnvironmentObject private var store: WorkspaceStore
    let folder: VaultFolder
    @State private var isDropTargeted: Bool = false
    @State private var isHovered: Bool = false

    private var isExpandedBinding: Binding<Bool> {
        Binding(
            get: { !store.collapsedFolderIds.contains(folder.id) },
            set: { expanded in
                if expanded {
                    store.collapsedFolderIds.remove(folder.id)
                } else {
                    store.collapsedFolderIds.insert(folder.id)
                }
            }
        )
    }

    var body: some View {
        let childFolders = store.activeFolders.filter { $0.parentFolderId == folder.id }
        let childConnections = store.activeConnections
            .filter { $0.parentFolderId == folder.id }
        let subtreeTotal = store.connectionCountInSubtree(folder.id)
        let subtreeRdpCount = store.rdpConnectionCountInSubtree(folder.id)
        let subtreeLiveCount = store.activeSessionCountInSubtree(folder.id)
        let isExpanded = !store.collapsedFolderIds.contains(folder.id)
        let isSelected = store.selectedFolderId == folder.id
        let folderTint = HIGColorUtilities.color(fromHex: folder.inheritable.displayColorAccentHex)
            ?? Color.accentColor

        DisclosureGroup(isExpanded: isExpandedBinding) {
            ForEach(childFolders) { sub in
                SidebarFolderTreeBranch(folder: sub)
            }
            SidebarGroupedConnectionList(connections: childConnections)
        } label: {
            HStack(spacing: 6) {
                Image(systemName: isExpanded ? "folder.fill" : "folder")
                    .font(.system(size: 13))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(folderTint)
                Text(folder.name)
                    .font(.system(size: 12.5, weight: .medium))
                    .lineLimit(1)
                if let env = folder.inheritable.environmentBadge, !env.isEmpty {
                    EnvironmentCapsuleBadge(env)
                }
                Spacer(minLength: 4)
                if subtreeLiveCount > 0 {
                    HStack(spacing: 3) {
                        Circle()
                            .fill(Color(nsColor: .systemGreen))
                            .frame(width: 6, height: 6)
                        Text("\(subtreeLiveCount)")
                            .font(.system(size: 10.5, weight: .semibold, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                } else if subtreeTotal > 0 {
                    Text("\(subtreeTotal)")
                        .font(.system(size: 10.5, design: .monospaced))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 2)
            .padding(.horizontal, 5)
            .background(
                RoundedRectangle(cornerRadius: LiquidGlassMetrics.rowCornerRadius, style: .continuous)
                    .fill(
                        isDropTargeted
                            ? folderTint.opacity(0.20)
                            : (isSelected ? Color.primary.opacity(0.08) : (isHovered ? Color.primary.opacity(0.04) : .clear))
                    )
            )
            .contentShape(Rectangle())
            .onHover { hovering in
                withAnimation(LiquidGlassMetrics.hoverAnimation) {
                    isHovered = hovering
                }
            }
            .onTapGesture {
                store.selectedFolderId = folder.id
            }
            .draggable("folder:\(folder.id)")
        }
        .dropDestination(for: String.self) { droppedIds, _ in
            store.handleSidebarDrop(droppedIds, intoFolderId: folder.id)
            return true
        } isTargeted: { hovering in
            isDropTargeted = hovering
            if hovering && store.collapsedFolderIds.contains(folder.id) {
                store.collapsedFolderIds.remove(folder.id)
            }
        }
        .contextMenu {
            Button {
                store.connectAllRdpInFolder(folder.id)
            } label: {
                Label(
                    subtreeRdpCount > 0
                        ? "\(store.strings.startAllRdp) (\(subtreeRdpCount))"
                        : store.strings.startAllRdp,
                    systemImage: "display.2"
                )
            }
            .disabled(subtreeRdpCount == 0)

            Button {
                store.connectAllInFolder(folder.id)
            } label: {
                Label("Connect All in Folder (\(subtreeTotal))", systemImage: "bolt.fill")
            }
            .disabled(subtreeTotal == 0)

            Button {
                store.disconnectAllInFolder(folder.id)
            } label: {
                Label(
                    subtreeLiveCount > 0
                        ? "\(store.strings.disconnectAll) (\(subtreeLiveCount))"
                        : store.strings.disconnectAll,
                    systemImage: "xmark.circle"
                )
            }
            .disabled(subtreeLiveCount == 0)

            Divider()

            Button {
                store.newConnectionInitialFolderId = folder.id
                store.newConnectionInitialProtocol = .RDP
                store.isPresentingNewConnectionSheet = true
            } label: {
                Label("New RDP Connection Here…", systemImage: "display")
            }

            Button {
                store.newConnectionInitialFolderId = folder.id
                store.newConnectionInitialProtocol = .SSH
                store.isPresentingNewConnectionSheet = true
            } label: {
                Label("New SSH Connection Here…", systemImage: "terminal")
            }

            Button {
                store.newFolderInitialParentId = folder.id
                store.isPresentingNewFolderSheet = true
            } label: {
                Label("New Subfolder Here…", systemImage: "folder.badge.plus")
            }

            Divider()

            Button {
                store.renamingFolder = folder
            } label: {
                Label(store.strings.renameFolder, systemImage: "pencil")
            }

            Menu("Move Folder to…") {
                Button("Top Level (Root)") {
                    store.moveFolder(folder.id, toParentFolderId: nil)
                }
                .disabled(folder.parentFolderId == nil)

                let validTargets = store.activeFolders.filter {
                    store.canMoveFolder(folder.id, toParentFolderId: $0.id)
                }
                if !validTargets.isEmpty {
                    Divider()
                    ForEach(validTargets) { target in
                        Button(target.name) {
                            store.moveFolder(folder.id, toParentFolderId: target.id)
                        }
                        .disabled(folder.parentFolderId == target.id)
                    }
                }
            }

            Button {
                store.reorderFolder(folder.id, moveUp: true)
            } label: {
                Label("Move Folder Up", systemImage: "arrow.up")
            }

            Button {
                store.reorderFolder(folder.id, moveUp: false)
            } label: {
                Label("Move Folder Down", systemImage: "arrow.down")
            }

            Button {
                store.duplicateFolder(folder)
            } label: {
                Label("Duplicate Folder", systemImage: "plus.square.on.square")
            }

            if !childFolders.isEmpty {
                Button {
                    if isExpanded {
                        store.collapseSubtree(folder.id)
                    } else {
                        store.expandSubtree(folder.id)
                    }
                } label: {
                    Label(
                        isExpanded ? "Collapse Subfolders" : "Expand Subfolders",
                        systemImage: isExpanded ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right"
                    )
                }
            }

            Button {
                store.editingFolder = folder
            } label: {
                Label("Folder Properties & Inheritance…", systemImage: "slider.horizontal.3")
            }

            Divider()

            Button(role: .destructive) {
                store.deletingFolderCandidate = folder
            } label: {
                Label("Delete Folder…", systemImage: "trash")
            }
        }
    }
}

struct SidebarConnectionRow: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow
    let connection: VaultConnection
    @State private var isReorderTargeted: Bool = false
    @State private var isHovered: Bool = false

    var body: some View {
        let liveCount = store.activeSessionCount(forConnectionId: connection.id)
        let isSelected = store.selectedConnectionId == connection.id
        let protoTint = HIGColorUtilities.color(fromHex: connection.inheritable.displayColorAccentHex)
            ?? connection.protocolType.appleSystemTint
        let tooltipText = connection.host.isEmpty
            ? "\(connection.protocolType.displayName) (\(connection.protocolType.shortBadge))"
            : "\(connection.protocolType.displayName) (\(connection.protocolType.shortBadge)) • \(connection.host)"

        HStack(spacing: 5) {
            Text(connection.name)
                .font(.system(size: 12.5, weight: isSelected ? .semibold : .regular))
                .foregroundStyle(.primary)
                .lineLimit(1)
            if connection.favorite {
                Image(systemName: "star.fill")
                    .font(.system(size: 9))
                    .foregroundStyle(.yellow)
            }
            if !connection.host.isEmpty && !isHovered {
                Text(connection.host)
                    .font(.system(size: 10.5, design: .monospaced))
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 4)
            if isHovered {
                ProtocolHoverGlassPill(
                    protocolType: connection.protocolType,
                    customHex: connection.inheritable.displayColorAccentHex
                )
                .transition(.opacity.combined(with: .scale(scale: 0.94)))
            }
            if liveCount > 0 {
                HStack(spacing: 3) {
                    Circle()
                        .fill(Color(nsColor: .systemGreen))
                        .frame(width: 6, height: 6)
                    if liveCount > 1 {
                        Text("\(liveCount)")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(.vertical, 2.5)
        .padding(.horizontal, 6)
        .background {
            if isSelected || isHovered {
                RoundedRectangle(cornerRadius: LiquidGlassMetrics.rowCornerRadius, style: .continuous)
                    .fill(.ultraThinMaterial)
                    .overlay(
                        RoundedRectangle(cornerRadius: LiquidGlassMetrics.rowCornerRadius, style: .continuous)
                            .fill(isSelected ? protoTint.opacity(0.16) : Color.primary.opacity(0.05))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: LiquidGlassMetrics.rowCornerRadius, style: .continuous)
                            .strokeBorder(
                                isSelected ? protoTint.opacity(0.38) : Color.white.opacity(0.18),
                                lineWidth: 0.7
                            )
                    )
            }
        }
        .overlay(alignment: .top) {
            if isReorderTargeted {
                Rectangle()
                    .fill(Color.accentColor)
                    .frame(height: 2)
            }
        }
        .contentShape(Rectangle())
        .onHover { hovering in
            withAnimation(LiquidGlassMetrics.hoverAnimation) {
                isHovered = hovering
            }
        }
        .help(tooltipText)
        .onTapGesture(count: 2) {
            store.openConnection(connection)
        }
        .simultaneousGesture(
            TapGesture(count: 1).onEnded {
                store.selectedConnectionId = connection.id
            }
        )
        .draggable("conn:\(connection.id)")
        .dropDestination(for: String.self) { payloads, _ in
            for raw in payloads {
                let id = raw.hasPrefix("conn:") ? String(raw.dropFirst("conn:".count)) : raw
                if !raw.hasPrefix("folder:") {
                    store.reorderConnection(id, before: connection.id)
                }
            }
            return true
        } isTargeted: { hovering in
            isReorderTargeted = hovering
        }
        .contextMenu {
            Button {
                store.openConnection(connection)
            } label: {
                Label("Connect", systemImage: "play.fill")
            }

            Button {
                store.connectWithOptionsTarget = connection
            } label: {
                Label("Connect with Options…", systemImage: "slider.horizontal.2.square")
            }

            Button {
                store.openConnection(connection, inSplit: .horizontal)
            } label: {
                Label(store.strings.splitRight, systemImage: "rectangle.split.2x1")
            }

            Button {
                store.openConnection(connection, inSplit: .vertical)
            } label: {
                Label(store.strings.splitDown, systemImage: "rectangle.split.1x2")
            }

            Button {
                let tab = WorkspaceSessionTab(connection: connection)
                store.detachedWindowTabs[tab.id] = tab
                openWindow(id: "detached-session", value: tab.id)
            } label: {
                Label(store.strings.detachToWindow, systemImage: "macwindow.badge.plus")
            }

            Divider()

            Menu("Move to Folder") {
                Button("Top Level (No Folder)") {
                    store.moveConnection(connection.id, toFolderId: nil)
                }
                Divider()
                ForEach(store.activeFolders) { folder in
                    Button(folder.name) {
                        store.moveConnection(connection.id, toFolderId: folder.id)
                    }
                }
            }

            Button {
                store.commitMutation("Toggled favorite") { doc in
                    if let idx = doc.connections.firstIndex(where: { $0.id == connection.id }) {
                        doc.connections[idx].favorite.toggle()
                    }
                }
            } label: {
                Label(connection.favorite ? "Remove from Favorites" : "Add to Favorites", systemImage: connection.favorite ? "star.slash" : "star")
            }

            Button {
                store.commitMutation("Duplicated connection") { doc in
                    var copy = connection
                    copy.id = UUID().uuidString.lowercased()
                    copy.name = "\(connection.name) Copy"
                    copy.updatedAtEpochMs = VaultClock.nowMs()
                    doc.connections.append(copy)
                }
            } label: {
                Label("Duplicate Connection", systemImage: "plus.square.on.square")
            }

            Button {
                store.editingConnection = connection
            } label: {
                Label("Edit Connection…", systemImage: "pencil")
            }

            Divider()

            Button(role: .destructive) {
                store.deleteConnection(connection.id)
            } label: {
                Label("Delete Connection", systemImage: "trash")
            }
        }
    }
}
