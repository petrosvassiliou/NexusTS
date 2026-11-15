import AppKit
import NexusTSCore
import SwiftUI

private enum FlattenedHierarchyRow: Identifiable {
    case folder(folder: VaultFolder, depth: Int, hasChildren: Bool)
    case groupHeader(id: String, title: String, protocolType: VaultProtocolType, count: Int, depth: Int)
    case connection(connection: VaultConnection, depth: Int, uniqueRowKey: String)

    var id: String {
        switch self {
        case .folder(let f, _, _):
            return "folder-row-\(f.id)"
        case .groupHeader(let id, _, _, _, _):
            return "group-hdr-\(id)"
        case .connection(_, _, let key):
            return key
        }
    }
}

public struct SidebarHierarchyView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow
    @State private var collapsedSmartFolderIds: Set<String> = []
    @State private var isRootDropTargeted: Bool = false

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

    private func buildFlattenedHierarchyRows() -> [FlattenedHierarchyRow] {
        var rows: [FlattenedHierarchyRow] = []
        let activeFolders = store.activeFolders
        let activeFolderIds = Set(activeFolders.map(\.id))
        let allConnections = store.activeConnections
        var visitedFolders = Set<String>()

        func appendGroupedConnections(_ conns: [VaultConnection], scopeKey: String, depth: Int) {
            let groups = IntelligentConnectionGrouper.group(conns, preserveInputOrderWithinGroups: false)
            for group in groups {
                if group.showsHeader {
                    rows.append(
                        .groupHeader(
                            id: "\(scopeKey)-\(group.id)",
                            title: group.groupBadge,
                            protocolType: group.primaryProtocol,
                            count: group.connections.count,
                            depth: depth
                        )
                    )
                }
                for conn in group.connections {
                    rows.append(
                        .connection(
                            connection: conn,
                            depth: depth,
                            uniqueRowKey: "conn-row-\(scopeKey)-\(conn.id)"
                        )
                    )
                }
            }
        }

        func appendFolderSubtree(_ folder: VaultFolder, depth: Int) {
            guard visitedFolders.insert(folder.id).inserted else { return }
            let childFolders = activeFolders.filter { $0.parentFolderId == folder.id }
            let childConnections = allConnections.filter { $0.parentFolderId == folder.id }
            let hasChildren = !childFolders.isEmpty || !childConnections.isEmpty
            rows.append(.folder(folder: folder, depth: depth, hasChildren: hasChildren))

            guard !store.collapsedFolderIds.contains(folder.id) else { return }
            for sub in childFolders {
                appendFolderSubtree(sub, depth: depth + 1)
            }
            appendGroupedConnections(childConnections, scopeKey: folder.id, depth: depth + 1)
        }

        let rootFolders = activeFolders.filter {
            $0.parentFolderId == nil || !activeFolderIds.contains($0.parentFolderId ?? "")
        }
        for folder in rootFolders {
            appendFolderSubtree(folder, depth: 0)
        }

        let rootConnections = allConnections.filter {
            $0.parentFolderId == nil || !activeFolderIds.contains($0.parentFolderId ?? "")
        }
        appendGroupedConnections(rootConnections, scopeKey: "root", depth: 0)
        return rows
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
                        SidebarFlatConnectionSection(
                            connections: favorites,
                            scopeKey: "favorites",
                            preserveInputOrderWithinGroups: false
                        )
                    }

                case .recents:
                    let recents = Array(filteredConnections.prefix(12))
                    if recents.isEmpty {
                        Text("No recent connections yet.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.vertical, 6)
                    } else {
                        SidebarFlatConnectionSection(
                            connections: recents,
                            scopeKey: "recents",
                            preserveInputOrderWithinGroups: true
                        )
                    }

                case .all:
                    if !trimmedQuery.isEmpty {
                        let matchingFolders = store.activeFolders.filter {
                            $0.name.lowercased().contains(trimmedQuery)
                        }
                        ForEach(matchingFolders) { folder in
                            SidebarFolderRow(
                                folder: folder,
                                depth: 0,
                                hasChildren: store.connectionCountInSubtree(folder.id) > 0
                            )
                        }
                        SidebarFlatConnectionSection(
                            connections: filteredConnections,
                            scopeKey: "search",
                            preserveInputOrderWithinGroups: false
                        )
                    } else {
                        // 1. Connections & Folders Hierarchy (flattened sibling rows so right-clicks and drops never bubble to ancestor folders)
                        Section {
                            let hierarchyRows = buildFlattenedHierarchyRows()
                            if hierarchyRows.isEmpty {
                                SidebarEmptyHierarchyDropCard(isTargeted: isRootDropTargeted)
                                    .dropDestination(for: String.self) { payloads, _ in
                                        store.handleSidebarDrop(payloads, intoFolderId: nil)
                                        return true
                                    } isTargeted: { hovering in
                                        isRootDropTargeted = hovering
                                    }
                            } else {
                                ForEach(hierarchyRows) { item in
                                    switch item {
                                    case .folder(let folder, let depth, let hasChildren):
                                        SidebarFolderRow(
                                            folder: folder,
                                            depth: depth,
                                            hasChildren: hasChildren
                                        )
                                    case .groupHeader(_, let title, let proto, let count, let depth):
                                        SidebarProtocolGroupHeader(
                                            title: title,
                                            protocolType: proto,
                                            count: count
                                        )
                                        .padding(.leading, CGFloat(depth) * 14)
                                    case .connection(let conn, let depth, _):
                                        SidebarConnectionRow(
                                            connection: conn,
                                            depth: depth,
                                            allowReorderingDrop: true
                                        )
                                    }
                                }

                                // Root-level drop target strip to move nested folders or connections back to root
                                SidebarRootDropStrip(isTargeted: isRootDropTargeted)
                                    .dropDestination(for: String.self) { payloads, _ in
                                        store.handleSidebarDrop(payloads, intoFolderId: nil)
                                        return true
                                    } isTargeted: { hovering in
                                        isRootDropTargeted = hovering
                                    }
                            }
                        } header: {
                            HStack(spacing: 6) {
                                Text(store.strings.vaultHierarchy)
                                    .font(.caption.weight(.semibold))
                                Spacer()
                                Button {
                                    store.newFolderInitialParentId = nil
                                    store.isPresentingNewFolderSheet = true
                                } label: {
                                    Image(systemName: "folder.badge.plus")
                                        .font(.caption)
                                }
                                .buttonStyle(.plain)
                                .help(store.strings.newFolder)

                                Button {
                                    let targetFolder = store.selectedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId
                                        ? nil
                                        : store.selectedFolderId
                                    store.newConnectionInitialFolderId = targetFolder
                                    store.newConnectionInitialProtocol = .RDP
                                    store.isPresentingNewConnectionSheet = true
                                } label: {
                                    Image(systemName: "plus")
                                        .font(.caption)
                                }
                                .buttonStyle(.plain)
                                .help(store.strings.newConnection)
                            }
                        }

                        // 2. Intelligent Folders (Smart Folders) — always manageable & creatable from sidebar
                        Section {
                            let smartList = store.activeSmartFolders
                            if smartList.isEmpty {
                                Button {
                                    store.isPresentingNewSmartFolderSheet = true
                                } label: {
                                    HStack(spacing: 6) {
                                        Image(systemName: "sparkles.rectangle.stack")
                                            .foregroundStyle(.secondary)
                                        Text(store.strings.newSmartFolder)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                        Spacer()
                                    }
                                    .padding(.vertical, 2)
                                }
                                .buttonStyle(.plain)
                            } else {
                                ForEach(smartList) { sf in
                                    let matches = PropertyInheritanceResolver.evaluateSmartFolder(sf, in: store.document)
                                    let isExpanded = !collapsedSmartFolderIds.contains(sf.id)
                                    SidebarSmartFolderRow(
                                        smartFolder: sf,
                                        matchCount: matches.count,
                                        isExpanded: isExpanded
                                    ) {
                                        if isExpanded {
                                            collapsedSmartFolderIds.insert(sf.id)
                                        } else {
                                            collapsedSmartFolderIds.remove(sf.id)
                                        }
                                    }
                                    if isExpanded {
                                        ForEach(matches) { matchedConn in
                                            SidebarConnectionRow(
                                                connection: matchedConn,
                                                depth: 1,
                                                allowReorderingDrop: false
                                            )
                                        }
                                    }
                                }
                            }
                        } header: {
                            HStack(spacing: 6) {
                                Text(store.strings.smartFolders)
                                    .font(.caption.weight(.semibold))
                                Spacer()
                                Button {
                                    store.isPresentingNewSmartFolderSheet = true
                                } label: {
                                    Image(systemName: "plus")
                                        .font(.caption)
                                }
                                .buttonStyle(.plain)
                                .help(store.strings.newSmartFolder)
                            }
                        }

                        // 3. Bottom-Pinned System "Credentials" Folder (credentials only, always at bottom)
                        Section {
                            SidebarSystemCredentialsFolderRow()
                            let credsExpanded = !store.collapsedFolderIds.contains(VaultHierarchySanitizer.systemCredentialsFolderId)
                            if credsExpanded {
                                let creds = store.activeCredentials
                                if creds.isEmpty {
                                    Button {
                                        store.isPresentingNewCredentialSheet = true
                                    } label: {
                                        HStack(spacing: 6) {
                                            Image(systemName: "key.fill")
                                                .font(.caption2)
                                                .foregroundStyle(.secondary)
                                            Text(store.strings.newCredential)
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                            Spacer()
                                        }
                                        .padding(.leading, 18)
                                        .padding(.vertical, 2)
                                    }
                                    .buttonStyle(.plain)
                                } else {
                                    ForEach(creds) { cred in
                                        SidebarCredentialRow(credential: cred)
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
        }
        .background(LiquidGlassBackdropAura())
        .searchable(text: $store.sidebarSearchQuery, placement: .sidebar, prompt: "Filter folders & connections")
    }

    @ViewBuilder
    private var sidebarCompactHeaderBar: some View {
        let allCollapsed = !store.activeFolders.isEmpty
            && store.collapsedFolderIds.isSuperset(of: store.activeFolders.map(\.id))

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
                let parentId = store.selectedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId
                    ? nil
                    : store.selectedFolderId
                store.newFolderInitialParentId = parentId
                store.isPresentingNewFolderSheet = true
            }

            SidebarGlassIconButton(
                systemImage: "sparkles.rectangle.stack",
                helpText: store.strings.newSmartFolder
            ) {
                store.isPresentingNewSmartFolderSheet = true
            }

            SidebarGlassIconButton(
                systemImage: "plus",
                helpText: store.strings.newConnection
            ) {
                let targetFolder = store.selectedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId
                    ? nil
                    : store.selectedFolderId
                store.newConnectionInitialFolderId = targetFolder
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

private struct SidebarEmptyHierarchyDropCard: View {
    @EnvironmentObject private var store: WorkspaceStore
    let isTargeted: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("No connections or folders yet.")
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
            HStack(spacing: 8) {
                Button {
                    store.newConnectionInitialFolderId = nil
                    store.newConnectionInitialProtocol = .RDP
                    store.isPresentingNewConnectionSheet = true
                } label: {
                    Label(store.strings.newConnection, systemImage: "plus")
                        .font(.caption)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)

                Button {
                    store.newFolderInitialParentId = nil
                    store.isPresentingNewFolderSheet = true
                } label: {
                    Label(store.strings.newFolder, systemImage: "folder.badge.plus")
                        .font(.caption)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .strokeBorder(
                    isTargeted ? Color.accentColor : Color.secondary.opacity(0.2),
                    style: StrokeStyle(lineWidth: 1, dash: [4, 3])
                )
        )
    }
}

private struct SidebarRootDropStrip: View {
    let isTargeted: Bool

    var body: some View {
        HStack(spacing: 6) {
            if isTargeted {
                Image(systemName: "arrow.turn.left.up")
                    .font(.caption2)
                    .foregroundStyle(Color.accentColor)
                Text("Drop here to move to Root level")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Color.accentColor)
            } else {
                Color.clear
                    .frame(height: 6)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, isTargeted ? 4 : 0)
        .padding(.horizontal, 6)
        .background(
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(isTargeted ? Color.accentColor.opacity(0.14) : Color.clear)
        )
        .contentShape(Rectangle())
    }
}

private struct SidebarFlatConnectionSection: View {
    let connections: [VaultConnection]
    let scopeKey: String
    var preserveInputOrderWithinGroups: Bool

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
                SidebarConnectionRow(
                    connection: conn,
                    depth: 0,
                    allowReorderingDrop: false
                )
            }
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

// MARK: - Independent Flat Folder Row (Zero Ancestor Context-Menu / Drop Hijacking)

struct SidebarFolderRow: View {
    @EnvironmentObject private var store: WorkspaceStore
    let folder: VaultFolder
    let depth: Int
    let hasChildren: Bool

    @State private var activeDropZone: FolderDropPlacement? = nil
    @State private var isHovered: Bool = false
    @State private var rowHeight: CGFloat = 26

    private var isExpanded: Bool {
        !store.collapsedFolderIds.contains(folder.id)
    }

    private func toggleExpanded() {
        if isExpanded {
            store.collapsedFolderIds.insert(folder.id)
        } else {
            store.collapsedFolderIds.remove(folder.id)
        }
    }

    private func resolveDropPlacement(locationY: CGFloat) -> FolderDropPlacement {
        let h = max(20, rowHeight)
        if locationY <= h * 0.26 {
            return .before
        } else if locationY >= h * 0.74 {
            return .after
        }
        return .inside
    }

    var body: some View {
        let subtreeTotal = store.connectionCountInSubtree(folder.id)
        let subtreeRdpCount = store.rdpConnectionCountInSubtree(folder.id)
        let subtreeLiveCount = store.activeSessionCountInSubtree(folder.id)
        let childFoldersExist = store.activeFolders.contains { $0.parentFolderId == folder.id }
        let isSelected = store.selectedFolderId == folder.id && store.selectedConnectionId == nil
        let folderTint = HIGColorUtilities.color(fromHex: folder.inheritable.displayColorAccentHex)
            ?? Color.accentColor

        HStack(spacing: 5) {
            Button {
                toggleExpanded()
            } label: {
                Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                    .font(.system(size: 9.5, weight: .bold))
                    .foregroundStyle(hasChildren ? .secondary : .tertiary)
                    .frame(width: 12, height: 16)
            }
            .buttonStyle(.plain)
            .opacity(hasChildren ? 1.0 : 0.35)

            Image(systemName: isExpanded ? "folder.fill" : "folder")
                .font(.system(size: 13))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(folderTint)

            Text(folder.name)
                .font(.system(size: 12.5, weight: isSelected ? .semibold : .medium))
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
        .padding(.leading, CGFloat(depth) * 14)
        .padding(.vertical, 2.5)
        .padding(.horizontal, 5)
        .background(
            GeometryReader { geo in
                RoundedRectangle(cornerRadius: LiquidGlassMetrics.rowCornerRadius, style: .continuous)
                    .fill(
                        activeDropZone == .inside
                            ? folderTint.opacity(0.22)
                            : (isSelected ? folderTint.opacity(0.14) : (isHovered ? Color.primary.opacity(0.05) : .clear))
                    )
                    .onAppear { rowHeight = geo.size.height }
                    .onChange(of: geo.size.height) { _, h in rowHeight = h }
            }
        )
        .overlay(alignment: .top) {
            if activeDropZone == .before {
                Rectangle()
                    .fill(Color.accentColor)
                    .frame(height: 2)
            }
        }
        .overlay(alignment: .bottom) {
            if activeDropZone == .after {
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
        .onTapGesture {
            // Left-clicking a folder strictly selects & toggles expansion; never auto-opens Folder Properties
            store.selectedFolderId = folder.id
            store.selectedConnectionId = nil
            toggleExpanded()
        }
        .draggable("folder:\(folder.id)")
        .dropDestination(for: String.self) { droppedPayloads, location in
            defer { activeDropZone = nil }
            let placement = resolveDropPlacement(locationY: location.y)
            for raw in droppedPayloads {
                if raw.hasPrefix("folder:") {
                    let draggedFolderId = String(raw.dropFirst("folder:".count))
                    store.moveOrReorderFolder(draggedFolderId, relativeTo: folder.id, placement: placement)
                    if placement == .inside {
                        store.collapsedFolderIds.remove(folder.id)
                    }
                } else {
                    store.handleSidebarDrop([raw], intoFolderId: folder.id)
                    store.collapsedFolderIds.remove(folder.id)
                }
            }
            return true
        } isTargeted: { hovering in
            activeDropZone = hovering ? .inside : nil
        }
        .contextMenu {
            Button {
                store.selectedFolderId = folder.id
                store.selectedConnectionId = nil
                store.editingFolder = folder
            } label: {
                Label("Folder Properties & Inheritance…", systemImage: "slider.horizontal.3")
            }

            Button {
                store.selectedFolderId = folder.id
                store.renamingFolder = folder
            } label: {
                Label(store.strings.renameFolder, systemImage: "pencil")
            }

            Divider()

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

            if childFoldersExist {
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

            Divider()

            Button(role: .destructive) {
                store.deletingFolderCandidate = folder
            } label: {
                Label("Delete Folder…", systemImage: "trash")
            }
        }
    }
}

// MARK: - Independent Flat Connection Row (Name-Only Display & Prominent Right-Click Edit)

struct SidebarConnectionRow: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow
    let connection: VaultConnection
    var depth: Int = 0
    var allowReorderingDrop: Bool = true

    @State private var isReorderTargeted: Bool = false
    @State private var isHovered: Bool = false

    var body: some View {
        let liveCount = store.activeSessionCount(forConnectionId: connection.id)
        let isSelected = store.selectedConnectionId == connection.id
        let protoTint = HIGColorUtilities.color(fromHex: connection.inheritable.displayColorAccentHex)
            ?? connection.protocolType.appleSystemTint
        let tooltipText = "\(connection.name) • \(connection.protocolType.displayName)"

        HStack(spacing: 5) {
            // Strictly display ONLY the connection name — never the IP address
            Text(connection.name)
                .font(.system(size: 12.5, weight: isSelected ? .semibold : .regular))
                .foregroundStyle(.primary)
                .lineLimit(1)
            if connection.favorite {
                Image(systemName: "star.fill")
                    .font(.system(size: 9))
                    .foregroundStyle(.yellow)
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
        .padding(.leading, CGFloat(depth) * 14 + 14)
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
            if isReorderTargeted && allowReorderingDrop {
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
            guard allowReorderingDrop else { return false }
            for raw in payloads {
                if raw.hasPrefix("cred:") {
                    store.handleSidebarDrop([raw], intoFolderId: connection.parentFolderId)
                } else if raw.hasPrefix("folder:") {
                    let fId = String(raw.dropFirst("folder:".count))
                    store.moveFolder(fId, toParentFolderId: connection.parentFolderId)
                } else {
                    let id = raw.hasPrefix("conn:") ? String(raw.dropFirst("conn:".count)) : raw
                    store.reorderConnection(id, before: connection.id)
                }
            }
            return true
        } isTargeted: { hovering in
            isReorderTargeted = hovering
        }
        .contextMenu {
            Button {
                store.selectedConnectionId = connection.id
                store.openConnection(connection)
            } label: {
                Label("Connect", systemImage: "play.fill")
            }

            Button {
                store.selectedConnectionId = connection.id
                store.editingConnection = connection
            } label: {
                Label(store.strings.editConnection, systemImage: "pencil")
            }

            Button {
                store.selectedConnectionId = connection.id
                store.connectWithOptionsTarget = connection
            } label: {
                Label("Connect with Options…", systemImage: "slider.horizontal.2.square")
            }

            Divider()

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
                .disabled(connection.parentFolderId == nil)

                if !store.activeFolders.isEmpty {
                    Divider()
                    ForEach(store.activeFolders) { folder in
                        Button(folder.name) {
                            store.moveConnection(connection.id, toFolderId: folder.id)
                        }
                        .disabled(connection.parentFolderId == folder.id)
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
                Label(
                    connection.favorite ? "Remove from Favorites" : "Add to Favorites",
                    systemImage: connection.favorite ? "star.slash" : "star"
                )
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

            Divider()

            Button(role: .destructive) {
                store.deleteConnection(connection.id)
            } label: {
                Label("Delete Connection", systemImage: "trash")
            }
        }
    }
}

// MARK: - Intelligent Folder (SmartFolder) Rows

private struct SidebarSmartFolderRow: View {
    @EnvironmentObject private var store: WorkspaceStore
    let smartFolder: SmartFolder
    let matchCount: Int
    let isExpanded: Bool
    let onToggle: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            Button(action: onToggle) {
                Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                    .font(.system(size: 9.5, weight: .bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 12, height: 16)
            }
            .buttonStyle(.plain)

            Image(systemName: "sparkles.rectangle.stack.fill")
                .font(.system(size: 12.5))
                .foregroundStyle(Color(nsColor: .systemPurple))

            Text(smartFolder.name)
                .font(.system(size: 12.5, weight: .medium))
                .lineLimit(1)

            Spacer(minLength: 4)

            Text("\(matchCount)")
                .font(.system(size: 10.5, weight: .semibold, design: .monospaced))
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
        .padding(.horizontal, 5)
        .contentShape(Rectangle())
        .onTapGesture(perform: onToggle)
        .contextMenu {
            Button {
                store.editingSmartFolder = smartFolder
            } label: {
                Label(store.strings.editSmartFolder, systemImage: "slider.horizontal.3")
            }

            Button {
                store.duplicateSmartFolder(smartFolder)
            } label: {
                Label(store.strings.duplicateSmartFolder, systemImage: "plus.square.on.square")
            }

            Button {
                store.isPresentingNewSmartFolderSheet = true
            } label: {
                Label(store.strings.newSmartFolder, systemImage: "plus")
            }

            Divider()

            Button(role: .destructive) {
                store.deleteSmartFolder(smartFolder.id)
            } label: {
                Label("Delete Intelligent Folder", systemImage: "trash")
            }
        }
    }
}

// MARK: - Bottom-Pinned System "Credentials" Folder & Credential Rows

private struct SidebarSystemCredentialsFolderRow: View {
    @EnvironmentObject private var store: WorkspaceStore
    @State private var isDropHovered: Bool = false

    private var isExpanded: Bool {
        !store.collapsedFolderIds.contains(VaultHierarchySanitizer.systemCredentialsFolderId)
    }

    private func toggleExpanded() {
        if isExpanded {
            store.collapsedFolderIds.insert(VaultHierarchySanitizer.systemCredentialsFolderId)
        } else {
            store.collapsedFolderIds.remove(VaultHierarchySanitizer.systemCredentialsFolderId)
        }
    }

    var body: some View {
        let credCount = store.activeCredentials.count
        let isSelected = store.selectedFolderId == VaultHierarchySanitizer.systemCredentialsFolderId

        HStack(spacing: 5) {
            Button(action: toggleExpanded) {
                Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                    .font(.system(size: 9.5, weight: .bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 12, height: 16)
            }
            .buttonStyle(.plain)

            Image(systemName: "folder.fill.badge.person.crop")
                .font(.system(size: 13))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(Color(nsColor: .systemOrange))

            Text(store.strings.systemCredentialsFolder)
                .font(.system(size: 12.5, weight: .semibold))
                .lineLimit(1)

            Image(systemName: "lock.fill")
                .font(.system(size: 9))
                .foregroundStyle(.secondary)

            Spacer(minLength: 4)

            Text("\(credCount)")
                .font(.system(size: 10.5, weight: .semibold, design: .monospaced))
                .foregroundStyle(.secondary)

            Button {
                store.isPresentingNewCredentialSheet = true
            } label: {
                Image(systemName: "plus")
                    .font(.caption2.weight(.bold))
            }
            .buttonStyle(.plain)
            .help(store.strings.newCredential)
        }
        .padding(.vertical, 3)
        .padding(.horizontal, 5)
        .background(
            RoundedRectangle(cornerRadius: LiquidGlassMetrics.rowCornerRadius, style: .continuous)
                .fill(
                    isDropHovered
                        ? Color(nsColor: .systemOrange).opacity(0.18)
                        : (isSelected ? Color(nsColor: .systemOrange).opacity(0.12) : .clear)
                )
        )
        .contentShape(Rectangle())
        .onTapGesture {
            store.selectedFolderId = VaultHierarchySanitizer.systemCredentialsFolderId
            store.selectedConnectionId = nil
            toggleExpanded()
        }
        .dropDestination(for: String.self) { payloads, _ in
            store.handleSidebarDrop(payloads, intoFolderId: VaultHierarchySanitizer.systemCredentialsFolderId)
            return payloads.allSatisfy { $0.hasPrefix("cred:") }
        } isTargeted: { hovering in
            isDropHovered = hovering
        }
        .contextMenu {
            Button {
                store.isPresentingNewCredentialSheet = true
            } label: {
                Label(store.strings.newCredential, systemImage: "key.fill")
            }

            Button {
                store.activeSection = .credentials
            } label: {
                Label("Open Credentials & TOTP Studio", systemImage: "key.horizontal")
            }
        }
    }
}

private struct SidebarCredentialRow: View {
    @EnvironmentObject private var store: WorkspaceStore
    let credential: VaultCredential
    @State private var isReorderTargeted: Bool = false
    @State private var isHovered: Bool = false

    var body: some View {
        let isSelected = store.selectedCredentialId == credential.id

        HStack(spacing: 6) {
            Image(systemName: credential.privateKeyPem.isEmpty ? "key.fill" : "key.radiowaves.forward.fill")
                .font(.system(size: 11))
                .foregroundStyle(Color(nsColor: .systemOrange))

            Text(credential.name)
                .font(.system(size: 12, weight: isSelected ? .semibold : .regular))
                .lineLimit(1)

            Spacer(minLength: 4)

            if !credential.totpSecretBase32.isEmpty {
                Image(systemName: "clock.badge.checkmark")
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.leading, 18)
        .padding(.vertical, 2)
        .padding(.horizontal, 6)
        .background(
            RoundedRectangle(cornerRadius: LiquidGlassMetrics.rowCornerRadius, style: .continuous)
                .fill(isSelected ? Color(nsColor: .systemOrange).opacity(0.14) : (isHovered ? Color.primary.opacity(0.04) : .clear))
        )
        .overlay(alignment: .top) {
            if isReorderTargeted {
                Rectangle()
                    .fill(Color(nsColor: .systemOrange))
                    .frame(height: 2)
            }
        }
        .contentShape(Rectangle())
        .onHover { hovering in
            withAnimation(LiquidGlassMetrics.hoverAnimation) {
                isHovered = hovering
            }
        }
        .onTapGesture(count: 2) {
            store.editingCredential = credential
        }
        .simultaneousGesture(
            TapGesture(count: 1).onEnded {
                store.selectedCredentialId = credential.id
                store.selectedConnectionId = nil
            }
        )
        .draggable("cred:\(credential.id)")
        .dropDestination(for: String.self) { payloads, _ in
            for raw in payloads {
                if raw.hasPrefix("cred:") {
                    let draggedId = String(raw.dropFirst("cred:".count))
                    store.reorderCredential(draggedId, before: credential.id)
                } else {
                    store.rejectDropIntoSystemCredentialsFolder()
                }
            }
            return true
        } isTargeted: { hovering in
            isReorderTargeted = hovering
        }
        .contextMenu {
            Button {
                store.editingCredential = credential
            } label: {
                Label(store.strings.editCredential, systemImage: "pencil")
            }

            if !credential.username.isEmpty {
                Button {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(credential.username, forType: .string)
                } label: {
                    Label("Copy Username", systemImage: "person.crop.square")
                }
            }

            if !credential.secretPassword.isEmpty {
                Button {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(credential.secretPassword, forType: .string)
                } label: {
                    Label("Copy Password", systemImage: "doc.on.clipboard")
                }
            }

            Button {
                store.duplicateCredential(credential)
            } label: {
                Label("Duplicate Credential", systemImage: "plus.square.on.square")
            }

            Divider()

            Button(role: .destructive) {
                store.deleteCredential(credential.id)
            } label: {
                Label("Delete Credential", systemImage: "trash")
            }
        }
    }
}
