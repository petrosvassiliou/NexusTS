import NexusTSCore
import SwiftUI

public struct SidebarHierarchyView: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow

    public init() {}

    private var filteredConnections: [VaultConnection] {
        let q = store.sidebarSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return store.activeConnections }
        return store.activeConnections.filter { conn in
            conn.name.lowercased().contains(q)
                || conn.host.lowercased().contains(q)
                || conn.protocolType.rawValue.lowercased().contains(q)
                || conn.tags.contains(where: { $0.lowercased().contains(q) })
        }
    }

    public var body: some View {
        List {
            Section("Studios") {
                sidebarStudioRow(.sessions, title: store.strings.connections, badge: store.paneTree.allTabs.count + store.detachedWindowTabs.count)
                sidebarStudioRow(.overview, title: store.strings.overview, badge: 0)
                sidebarStudioRow(.templates, title: store.strings.templates, badge: store.document.templates.count)
                sidebarStudioRow(.credentials, title: store.strings.credentials, badge: store.document.credentials.count)
                sidebarStudioRow(.gateways, title: store.strings.gateways, badge: store.document.gateways.count)
                sidebarStudioRow(.automationTasks, title: store.strings.automationTasks, badge: store.document.todoItems.filter { !$0.completed }.count)
                sidebarStudioRow(.cloudSync, title: store.strings.cloudSync, badge: 0)
            }

            let favorites = filteredConnections.filter(\.favorite)
            if !favorites.isEmpty {
                Section(store.strings.favorites) {
                    ForEach(favorites) { conn in
                        SidebarConnectionRow(connection: conn)
                    }
                }
            }

            let recents: [VaultConnection] = store.document.recentConnectionIds.prefix(6).compactMap { id in
                filteredConnections.first(where: { $0.id == id })
            }
            if !recents.isEmpty && store.sidebarSearchQuery.isEmpty {
                Section(store.strings.recentSessions) {
                    ForEach(recents) { conn in
                        SidebarConnectionRow(connection: conn)
                    }
                }
            }

            let smartList = store.document.smartFolders.filter { !store.document.tombstonedObjectIds.contains($0.id) }
            if !smartList.isEmpty && store.sidebarSearchQuery.isEmpty {
                Section(store.strings.smartFolders) {
                    ForEach(smartList) { sf in
                        let matches = PropertyInheritanceResolver.evaluateSmartFolder(sf, in: store.document)
                        DisclosureGroup {
                            ForEach(matches) { conn in
                                SidebarConnectionRow(connection: conn)
                            }
                        } label: {
                            Label(sf.name, systemImage: "gearshape.2")
                                .badge(matches.count)
                        }
                    }
                }
            }

            let dynList = store.document.dynamicFolders.filter { !store.document.tombstonedObjectIds.contains($0.id) }
            if !dynList.isEmpty && store.sidebarSearchQuery.isEmpty {
                Section(store.strings.dynamicFolders) {
                    ForEach(dynList) { dyn in
                        let cached = store.dynamicFolderCache[dyn.id] ?? []
                        DisclosureGroup {
                            ForEach(cached) { conn in
                                SidebarConnectionRow(connection: conn)
                            }
                        } label: {
                            HStack {
                                Label(dyn.name, systemImage: "bolt.badge.clock")
                                Spacer()
                                Button {
                                    Task { await store.refreshDynamicFolder(dyn) }
                                } label: {
                                    Image(systemName: "arrow.clockwise")
                                        .font(.caption)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }

            Section(store.strings.vaultHierarchy) {
                if !store.sidebarSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    ForEach(filteredConnections) { conn in
                        SidebarConnectionRow(connection: conn)
                    }
                } else {
                    let rootFolders = store.activeFolders
                        .filter { $0.parentFolderId == nil }
                        .sorted {
                            if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
                            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
                        }
                    ForEach(rootFolders) { folder in
                        SidebarFolderTreeBranch(folder: folder)
                    }

                    let rootConnections = store.activeConnections
                        .filter { $0.parentFolderId == nil }
                        .sorted {
                            if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
                            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
                        }
                    ForEach(rootConnections) { conn in
                        SidebarConnectionRow(connection: conn)
                    }
                    .dropDestination(for: String.self) { items, _ in
                        store.handleSidebarDrop(items, intoFolderId: nil)
                        return true
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .searchable(text: $store.sidebarSearchQuery, placement: .sidebar, prompt: "Filter hosts & tags")
    }

    @ViewBuilder
    private func sidebarStudioRow(
        _ section: WorkspacePrimarySection,
        title: String,
        badge: Int
    ) -> some View {
        Button {
            store.activeSection = section
        } label: {
            HStack {
                Label(title, systemImage: section.sfSymbol)
                    .foregroundStyle(store.activeSection == section ? Color.accentColor : .primary)
                Spacer()
                if badge > 0 {
                    Text("\(badge)")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 1)
                        .background(Capsule().fill(Color.primary.opacity(0.08)))
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct SidebarFolderTreeBranch: View {
    @EnvironmentObject private var store: WorkspaceStore
    let folder: VaultFolder
    @State private var isExpanded: Bool = true
    @State private var isDropTargeted: Bool = false

    var body: some View {
        let childFolders = store.activeFolders
            .filter { $0.parentFolderId == folder.id }
            .sorted {
                if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
                return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
        let childConnections = store.activeConnections
            .filter { $0.parentFolderId == folder.id }
            .sorted {
                if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
                return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }

        DisclosureGroup(isExpanded: $isExpanded) {
            ForEach(childFolders) { sub in
                SidebarFolderTreeBranch(folder: sub)
            }
            ForEach(childConnections) { conn in
                SidebarConnectionRow(connection: conn)
            }
        } label: {
            HStack(spacing: 7) {
                Image(systemName: isExpanded ? "folder.fill" : "folder")
                    .foregroundStyle(
                        HIGColorUtilities.color(fromHex: folder.inheritable.displayColorAccentHex)
                            ?? Color.accentColor
                    )
                Text(folder.name)
                    .font(.body.weight(.medium))
                    .lineLimit(1)
                if let env = folder.inheritable.environmentBadge, !env.isEmpty {
                    EnvironmentCapsuleBadge(env)
                }
                Spacer()
                Text("\(childConnections.count)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 2)
            .background(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(isDropTargeted ? Color.accentColor.opacity(0.18) : .clear)
            )
            .contentShape(Rectangle())
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
            if hovering && !isExpanded {
                isExpanded = true
            }
        }
        .contextMenu {
            Button {
                for conn in childConnections {
                    store.openConnection(conn)
                }
            } label: {
                Label("Connect All in Folder (\(childConnections.count))", systemImage: "bolt.fill")
            }

            Button {
                store.disconnectAllInFolder(folder.id)
            } label: {
                Label(store.strings.disconnectAll, systemImage: "xmark.circle")
            }

            Divider()

            Button {
                store.newConnectionInitialFolderId = folder.id
                store.newConnectionInitialProtocol = .SSH
                store.isPresentingNewConnectionSheet = true
            } label: {
                Label("New SSH Connection Here…", systemImage: "terminal")
            }

            Button {
                store.newConnectionInitialFolderId = folder.id
                store.newConnectionInitialProtocol = .RDP
                store.isPresentingNewConnectionSheet = true
            } label: {
                Label("New RDP Connection Here…", systemImage: "display")
            }

            Menu("Move Folder to…") {
                Button("Top Level (Root)") {
                    store.moveFolder(folder.id, toParentFolderId: nil)
                }
                Divider()
                ForEach(store.activeFolders.filter { $0.id != folder.id }) { target in
                    Button(target.name) {
                        store.moveFolder(folder.id, toParentFolderId: target.id)
                    }
                }
            }

            Divider()

            Button {
                store.editingFolder = folder
            } label: {
                Label("Folder Properties & Inheritance…", systemImage: "slider.horizontal.3")
            }

            Button(role: .destructive) {
                store.commitMutation("Deleted folder \(folder.name)") { doc in
                    doc.tombstonedObjectIds.insert(folder.id)
                    doc.folders.removeAll { $0.id == folder.id }
                    for idx in doc.connections.indices where doc.connections[idx].parentFolderId == folder.id {
                        doc.connections[idx].parentFolderId = folder.parentFolderId
                    }
                }
            } label: {
                Label("Delete Folder", systemImage: "trash")
            }
        }
    }
}

struct SidebarConnectionRow: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow
    let connection: VaultConnection
    @State private var isReorderTargeted: Bool = false

    var body: some View {
        let liveCount = store.activeSessionCount(forConnectionId: connection.id)
        let isSelected = store.selectedConnectionId == connection.id

        HStack(spacing: 8) {
            ProtocolSquircleIcon(
                protocolType: connection.protocolType,
                customHex: connection.inheritable.displayColorAccentHex,
                size: 22
            )
            VStack(alignment: .leading, spacing: 1) {
                Text(connection.name)
                    .font(.system(size: 13, weight: isSelected ? .semibold : .regular))
                    .lineLimit(1)
                Text(connection.host)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
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
        .padding(.vertical, 2)
        .padding(.horizontal, 4)
        .background(
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(isSelected ? Color.accentColor.opacity(0.16) : .clear)
        )
        .overlay(alignment: .top) {
            if isReorderTargeted {
                Rectangle()
                    .fill(Color.accentColor)
                    .frame(height: 2)
            }
        }
        .contentShape(Rectangle())
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
