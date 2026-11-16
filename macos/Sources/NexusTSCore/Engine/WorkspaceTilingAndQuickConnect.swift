import Foundation

// MARK: - Recursive BSP Split-Pane Tiling Model

public enum SplitOrientation: String, Codable, Equatable, Sendable {
    case horizontal // Side-by-side (Split Right)
    case vertical   // Top-and-bottom (Split Down)
}

public enum SessionLifecycleStatus: String, Codable, Equatable, Sendable {
    case connecting
    case connected
    case disconnected
    case error
}

public struct WorkspaceSessionTab: Identifiable, Equatable, Hashable, Sendable {
    public let id: UUID
    public var connection: VaultConnection
    public var title: String
    public var isLocalShell: Bool
    public var status: SessionLifecycleStatus
    public var broadcastEnabled: Bool
    public var zoomFactor: Double
    public var accentHex: String?
    public var adHocUsernameOverride: String?
    public var adHocPasswordOverride: String?
    public var adHocDomainOverride: String?
    public var startedAt: Date

    public init(
        id: UUID = UUID(),
        connection: VaultConnection,
        title: String? = nil,
        isLocalShell: Bool = false,
        status: SessionLifecycleStatus = .connected,
        broadcastEnabled: Bool = false,
        zoomFactor: Double = 1.0,
        accentHex: String? = nil,
        adHocUsernameOverride: String? = nil,
        adHocPasswordOverride: String? = nil,
        adHocDomainOverride: String? = nil,
        startedAt: Date = Date()
    ) {
        self.id = id
        self.connection = connection
        self.title = title ?? connection.name
        self.isLocalShell = isLocalShell
        self.status = status
        self.broadcastEnabled = broadcastEnabled
        self.zoomFactor = zoomFactor
        self.accentHex = accentHex ?? connection.inheritable.displayColorAccentHex
        self.adHocUsernameOverride = adHocUsernameOverride
        self.adHocPasswordOverride = adHocPasswordOverride
        self.adHocDomainOverride = adHocDomainOverride
        self.startedAt = startedAt
    }

    public static func makeLocalZshTab() -> WorkspaceSessionTab {
        let hostName = Host.current().localizedName ?? "Mac"
        let conn = VaultConnection(
            id: "local-zsh-\(UUID().uuidString.lowercased())",
            name: "\(hostName) — zsh",
            host: "localhost",
            protocolType: .SSH,
            osFamily: "macOS"
        )
        return WorkspaceSessionTab(
            connection: conn,
            title: "\(hostName) — zsh",
            isLocalShell: true,
            status: .connected,
            accentHex: "#34D399"
        )
    }
}

public indirect enum WorkspacePaneNode: Equatable, Identifiable, Sendable {
    case leaf(paneId: UUID, tabs: [WorkspaceSessionTab], activeTabId: UUID?)
    case split(
        splitId: UUID,
        orientation: SplitOrientation,
        ratio: Double,
        first: WorkspacePaneNode,
        second: WorkspacePaneNode
    )

    public var id: UUID {
        switch self {
        case .leaf(let paneId, _, _): return paneId
        case .split(let splitId, _, _, _, _): return splitId
        }
    }

    public static func initialEmpty() -> WorkspacePaneNode {
        .leaf(paneId: UUID(), tabs: [], activeTabId: nil)
    }

    public var allTabs: [WorkspaceSessionTab] {
        switch self {
        case .leaf(_, let tabs, _):
            return tabs
        case .split(_, _, _, let first, let second):
            return first.allTabs + second.allTabs
        }
    }

    public var allLeafIds: [UUID] {
        switch self {
        case .leaf(let paneId, _, _):
            return [paneId]
        case .split(_, _, _, let first, let second):
            return first.allLeafIds + second.allLeafIds
        }
    }

    public func findTab(id: UUID) -> WorkspaceSessionTab? {
        allTabs.first { $0.id == id }
    }

    public func leafContaining(tabId: UUID) -> UUID? {
        switch self {
        case .leaf(let paneId, let tabs, _):
            return tabs.contains(where: { $0.id == tabId }) ? paneId : nil
        case .split(_, _, _, let first, let second):
            return first.leafContaining(tabId: tabId) ?? second.leafContaining(tabId: tabId)
        }
    }

    public func insertingTab(_ tab: WorkspaceSessionTab, into targetPaneId: UUID? = nil) -> WorkspacePaneNode {
        let resolvedTarget = targetPaneId ?? allLeafIds.first
        switch self {
        case .leaf(let paneId, var tabs, _):
            if paneId == resolvedTarget {
                tabs.append(tab)
                return .leaf(paneId: paneId, tabs: tabs, activeTabId: tab.id)
            }
            return self
        case .split(let splitId, let orientation, let ratio, let first, let second):
            return .split(
                splitId: splitId,
                orientation: orientation,
                ratio: ratio,
                first: first.insertingTab(tab, into: resolvedTarget),
                second: second.insertingTab(tab, into: resolvedTarget)
            )
        }
    }

    public func activatingTab(_ tabId: UUID, in paneId: UUID) -> WorkspacePaneNode {
        switch self {
        case .leaf(let id, let tabs, _):
            guard id == paneId, tabs.contains(where: { $0.id == tabId }) else { return self }
            return .leaf(paneId: id, tabs: tabs, activeTabId: tabId)
        case .split(let splitId, let orientation, let ratio, let first, let second):
            return .split(
                splitId: splitId,
                orientation: orientation,
                ratio: ratio,
                first: first.activatingTab(tabId, in: paneId),
                second: second.activatingTab(tabId, in: paneId)
            )
        }
    }

    public func activatingTabGlobally(_ tabId: UUID) -> (updatedTree: WorkspacePaneNode, paneId: UUID?) {
        guard let targetPaneId = leafContaining(tabId: tabId) else {
            return (self, nil)
        }
        return (activatingTab(tabId, in: targetPaneId), targetPaneId)
    }

    public func focusedOrFirstActiveTab(focusedPaneId: UUID?) -> WorkspaceSessionTab? {
        func findActiveInLeaf(_ targetId: UUID, node: WorkspacePaneNode) -> WorkspaceSessionTab? {
            switch node {
            case .leaf(let paneId, let tabs, let activeTabId):
                guard paneId == targetId else { return nil }
                return tabs.first(where: { $0.id == activeTabId }) ?? tabs.first
            case .split(_, _, _, let first, let second):
                return findActiveInLeaf(targetId, node: first) ?? findActiveInLeaf(targetId, node: second)
            }
        }
        if let focusedPaneId, let active = findActiveInLeaf(focusedPaneId, node: self) {
            return active
        }
        return allTabs.first
    }

    public func updatingTab(_ tabId: UUID, transform: (inout WorkspaceSessionTab) -> Void) -> WorkspacePaneNode {
        switch self {
        case .leaf(let paneId, var tabs, let activeId):
            if let idx = tabs.firstIndex(where: { $0.id == tabId }) {
                transform(&tabs[idx])
                return .leaf(paneId: paneId, tabs: tabs, activeTabId: activeId)
            }
            return self
        case .split(let splitId, let orientation, let ratio, let first, let second):
            return .split(
                splitId: splitId,
                orientation: orientation,
                ratio: ratio,
                first: first.updatingTab(tabId, transform: transform),
                second: second.updatingTab(tabId, transform: transform)
            )
        }
    }

    public func splittingPane(
        _ targetPaneId: UUID,
        orientation: SplitOrientation,
        newTab: WorkspaceSessionTab? = nil
    ) -> (updatedTree: WorkspacePaneNode, newPaneId: UUID) {
        let createdPaneId = UUID()
        func walk(_ node: WorkspacePaneNode) -> WorkspacePaneNode {
            switch node {
            case .leaf(let paneId, _, _):
                guard paneId == targetPaneId else { return node }
                let secondLeaf = WorkspacePaneNode.leaf(
                    paneId: createdPaneId,
                    tabs: newTab.map { [$0] } ?? [],
                    activeTabId: newTab?.id
                )
                return .split(
                    splitId: UUID(),
                    orientation: orientation,
                    ratio: 0.5,
                    first: node,
                    second: secondLeaf
                )
            case .split(let splitId, let o, let ratio, let first, let second):
                return .split(
                    splitId: splitId,
                    orientation: o,
                    ratio: ratio,
                    first: walk(first),
                    second: walk(second)
                )
            }
        }
        return (walk(self), createdPaneId)
    }

    public func updatingSplitRatio(splitId: UUID, ratio: Double) -> WorkspacePaneNode {
        let clamped = min(0.85, max(0.15, ratio))
        switch self {
        case .leaf:
            return self
        case .split(let id, let orientation, let oldRatio, let first, let second):
            return .split(
                splitId: id,
                orientation: orientation,
                ratio: id == splitId ? clamped : oldRatio,
                first: first.updatingSplitRatio(splitId: splitId, ratio: clamped),
                second: second.updatingSplitRatio(splitId: splitId, ratio: clamped)
            )
        }
    }

    public func removingTab(_ tabId: UUID) -> WorkspacePaneNode {
        func prune(_ node: WorkspacePaneNode) -> WorkspacePaneNode? {
            switch node {
            case .leaf(let paneId, var tabs, let activeTabId):
                guard let idx = tabs.firstIndex(where: { $0.id == tabId }) else { return node }
                tabs.remove(at: idx)
                if tabs.isEmpty {
                    return nil
                }
                let nextActive: UUID? = {
                    if activeTabId != tabId { return activeTabId }
                    let clampedIdx = min(idx, tabs.count - 1)
                    return tabs[clampedIdx].id
                }()
                return .leaf(paneId: paneId, tabs: tabs, activeTabId: nextActive)
            case .split(let splitId, let orientation, let ratio, let first, let second):
                let nextFirst = prune(first)
                let nextSecond = prune(second)
                switch (nextFirst, nextSecond) {
                case (let f?, let s?):
                    return .split(splitId: splitId, orientation: orientation, ratio: ratio, first: f, second: s)
                case (let f?, nil):
                    return f
                case (nil, let s?):
                    return s
                case (nil, nil):
                    return nil
                }
            }
        }
        return prune(self) ?? .initialEmpty()
    }

    public func reorderingOrMovingTab(
        _ draggedTabId: UUID,
        before targetTabId: UUID?,
        in targetPaneId: UUID
    ) -> WorkspacePaneNode {
        guard draggedTabId != targetTabId,
              let movingTab = findTab(id: draggedTabId) else {
            return self
        }
        let treeWithoutDragged = removingTab(draggedTabId)
        // If removing the only tab in targetPaneId collapsed that leaf, fall back to the first remaining leaf
        let resolvedPaneId = treeWithoutDragged.allLeafIds.contains(targetPaneId)
            ? targetPaneId
            : (treeWithoutDragged.allLeafIds.first ?? targetPaneId)

        func insertInLeaf(_ node: WorkspacePaneNode) -> WorkspacePaneNode {
            switch node {
            case .leaf(let paneId, var tabs, _):
                guard paneId == resolvedPaneId else { return node }
                if let beforeId = targetTabId,
                   let idx = tabs.firstIndex(where: { $0.id == beforeId }) {
                    tabs.insert(movingTab, at: idx)
                } else {
                    tabs.append(movingTab)
                }
                return .leaf(paneId: paneId, tabs: tabs, activeTabId: movingTab.id)
            case .split(let splitId, let orientation, let ratio, let first, let second):
                return .split(
                    splitId: splitId,
                    orientation: orientation,
                    ratio: ratio,
                    first: insertInLeaf(first),
                    second: insertInLeaf(second)
                )
            }
        }
        return insertInLeaf(treeWithoutDragged)
    }
}

// MARK: - Quick Connect Ad-Hoc URI Parser & Fuzzy Search

public struct ParsedQuickConnectTarget: Equatable, Sendable {
    public let displayName: String
    public let host: String
    public let port: Int
    public let username: String?
    public let password: String?
    public let domain: String?
    public let protocolType: VaultProtocolType
    public let settings: ProtocolSpecificSettings

    public init(
        displayName: String,
        host: String,
        port: Int,
        username: String?,
        password: String? = nil,
        domain: String?,
        protocolType: VaultProtocolType,
        settings: ProtocolSpecificSettings
    ) {
        self.displayName = displayName
        self.host = host
        self.port = port
        self.username = username
        self.password = password
        self.domain = domain
        self.protocolType = protocolType
        self.settings = settings
    }

    public func toAdHocVaultConnection() -> VaultConnection {
        let isWin = (protocolType == .RDP || protocolType == .WINDOWS_TERMINAL_SERVICES)
        return VaultConnection(
            id: "adhoc-\(UUID().uuidString.lowercased())",
            name: displayName,
            host: host,
            protocolType: protocolType,
            osFamily: isWin ? "Windows" : (protocolType == .APPLE_SCREEN_SHARING ? "macOS" : "Linux"),
            tags: ["quick-connect"],
            settings: settings
        )
    }
}

public struct QuickConnectSearchHit: Identifiable, Equatable, Sendable {
    public var id: String { connection.id }
    public let connection: VaultConnection
    public let score: Int
    public let folderBreadcrumb: String
}

public enum QuickConnectParser {
    public static func parseAdHocUri(_ rawInput: String) -> ParsedQuickConnectTarget? {
        let input = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !input.isEmpty else { return nil }

        // Hardware Serial devices (/dev/cu.*, /dev/tty*, serial://, COM1)
        if input.hasPrefix("/dev/cu.") || input.hasPrefix("/dev/tty") || input.uppercased().hasPrefix("COM") || input.lowercased().hasPrefix("serial://") {
            let cleanPath = input.replacingOccurrences(of: "serial://", with: "", options: .caseInsensitive)
            var uart = SerialUartProtocolSettings()
            uart.devicePathHint = cleanPath
            return ParsedQuickConnectTarget(
                displayName: "Serial \(cleanPath)",
                host: cleanPath,
                port: uart.baudRate,
                username: nil,
                domain: nil,
                protocolType: .SERIAL,
                settings: .serialUart(uart)
            )
        }

        // Kubernetes & Docker URIs
        let lower = input.lowercased()
        if lower.hasPrefix("k8s://") || lower.hasPrefix("kubectl://") {
            let rest = input.components(separatedBy: "://").dropFirst().joined(separator: "://")
            let parts = rest.split(separator: "/", maxSplits: 1).map(String.init)
            let ns = parts.count > 1 ? parts[0] : "default"
            let pod = parts.count > 1 ? parts[1] : (parts.first ?? "pod")
            var ce = ContainerExecProtocolSettings(podOrContainerName: pod)
            ce.namespace = ns
            return ParsedQuickConnectTarget(
                displayName: "k8s \(ns)/\(pod)",
                host: pod,
                port: 6443,
                username: nil,
                domain: nil,
                protocolType: .KUBERNETES_POD,
                settings: .containerExec(ce)
            )
        }

        if lower.hasPrefix("docker://") {
            let container = String(input.dropFirst("docker://".count))
            let ce = ContainerExecProtocolSettings(
                clusterOrSocketEndpoint: "unix:///var/run/docker.sock",
                podOrContainerName: container
            )
            return ParsedQuickConnectTarget(
                displayName: "docker \(container)",
                host: container,
                port: 0,
                username: nil,
                domain: nil,
                protocolType: .DOCKER_CONTAINER,
                settings: .containerExec(ce)
            )
        }

        // Web URLs
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") {
            let comps = URLComponents(string: input)
            let host = comps?.host ?? input
            let resolvedPort = comps?.port ?? (lower.hasPrefix("http://") ? 80 : 443)
            let urlUser = (comps?.user?.isEmpty == false) ? comps?.user : nil
            let urlPass = (comps?.password?.isEmpty == false) ? comps?.password : nil
            return ParsedQuickConnectTarget(
                displayName: host,
                host: host,
                port: resolvedPort,
                username: urlUser,
                password: urlPass,
                domain: nil,
                protocolType: .WEB_PAGE,
                settings: .webPage(WebPageProtocolSettings(url: input))
            )
        }

        // Scheme-prefixed or bare [domain\]user[:password]@host[:port]
        var scheme = ""
        var remainder = input
        if let range = input.range(of: "://") {
            scheme = String(input[..<range.lowerBound]).lowercased()
            remainder = String(input[range.upperBound...])
        }

        var username: String? = nil
        var password: String? = nil
        var domain: String? = nil
        var hostPort = remainder
        if let atIdx = remainder.lastIndex(of: "@") {
            let userPart = String(remainder[..<atIdx])
            hostPort = String(remainder[remainder.index(after: atIdx)...])
            var rawUser = userPart
            if let slashIdx = userPart.firstIndex(of: "\\") {
                domain = String(userPart[..<slashIdx])
                rawUser = String(userPart[userPart.index(after: slashIdx)...])
            }
            if let colonIdx = rawUser.firstIndex(of: ":") {
                let u = String(rawUser[..<colonIdx])
                let p = String(rawUser[rawUser.index(after: colonIdx)...])
                username = u.isEmpty ? nil : u
                password = p.isEmpty ? nil : p
            } else {
                username = rawUser.isEmpty ? nil : rawUser
            }
        }

        if let slashIdx = hostPort.firstIndex(of: "/") {
            hostPort = String(hostPort[..<slashIdx])
        }

        var host = hostPort
        var explicitPort: Int? = nil
        if !hostPort.hasPrefix("["), let colonIdx = hostPort.lastIndex(of: ":") {
            let portCandidate = String(hostPort[hostPort.index(after: colonIdx)...])
            if let p = Int(portCandidate), p > 0 && p <= 65535 {
                explicitPort = p
                host = String(hostPort[..<colonIdx])
            }
        }
        guard !host.isEmpty, !host.contains(" ") else { return nil }

        let proto: VaultProtocolType = {
            switch scheme {
            case "rdp", "wts", "mstsc": return .RDP
            case "ssh": return .SSH
            case "telnet": return .TELNET
            case "sftp": return .SFTP
            case "ftp": return .FTP
            case "ftps", "ftpes": return .FTPS
            case "scp": return .SCP
            case "vnc": return .VNC
            case "ard", "screensharing": return .APPLE_SCREEN_SHARING
            case "vsphere": return .VMWARE_VSPHERE
            case "pve", "proxmox": return .PROXMOX_VE
            case "hyperv": return .HYPER_V
            case "ping": return .UTILITY_PING
            case "trace", "traceroute": return .UTILITY_TRACEROUTE
            case "scan": return .UTILITY_PORT_SCANNER
            case "wol": return .UTILITY_WAKE_ON_LAN
            default:
                if let p = explicitPort {
                    switch p {
                    case 3389: return .RDP
                    case 5900: return .VNC
                    case 23: return .TELNET
                    case 443, 8080, 8443: return .WEB_PAGE
                    case 22: return .SSH
                    default:
                        return (domain?.isEmpty == false) ? .RDP : .SSH
                    }
                }
                return (domain?.isEmpty == false) ? .RDP : .SSH
            }
        }()

        let port = explicitPort ?? (scheme == "ftpes" ? 21 : proto.defaultPort)
        var settings = proto.defaultSettings(host: proto == .WEB_PAGE ? "https://\(host):\(port)" : host)
        switch settings {
        case .ssh(var s): s.port = port; settings = .ssh(s)
        case .rdp(var s): s.port = port; settings = .rdp(s)
        case .vnc(var s): s.port = port; settings = .vnc(s)
        case .fileTransfer(var s):
            s.port = port
            if proto == .FTPS && (scheme == "ftpes" || port == 21) {
                s.ftpsImplicitTls = false
            }
            settings = .fileTransfer(s)
        default: break
        }

        let labelPrefix = username.map { "\($0)@" } ?? ""
        return ParsedQuickConnectTarget(
            displayName: "\(labelPrefix)\(host)",
            host: host,
            port: port,
            username: username,
            password: password,
            domain: domain,
            protocolType: proto,
            settings: settings
        )
    }

    public static func rankConnections(
        query: String,
        in document: NxtsVaultDocument
    ) -> [QuickConnectSearchHit] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let foldersById = Dictionary(uniqueKeysWithValues: document.folders.map { ($0.id, $0) })

        func breadcrumb(for folderId: String?) -> String {
            let chain = PropertyInheritanceResolver.folderAncestorChain(leafFolderId: folderId, foldersById: foldersById)
            return chain.isEmpty ? "Top Level" : chain.map(\.name).joined(separator: " › ")
        }

        let active = document.connections.filter { !document.tombstonedObjectIds.contains($0.id) }
        if q.isEmpty {
            let recentSet = Set(document.recentConnectionIds)
            return active
                .sorted { lhs, rhs in
                    if lhs.favorite != rhs.favorite { return lhs.favorite }
                    if recentSet.contains(lhs.id) != recentSet.contains(rhs.id) {
                        return recentSet.contains(lhs.id)
                    }
                    return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
                }
                .prefix(20)
                .map { QuickConnectSearchHit(connection: $0, score: 100, folderBreadcrumb: breadcrumb(for: $0.parentFolderId)) }
        }

        var hits: [QuickConnectSearchHit] = []
        for conn in active {
            var score = 0
            let nameLower = conn.name.lowercased()
            let hostLower = conn.host.lowercased()
            if nameLower.hasPrefix(q) {
                score += 120
            } else if nameLower.contains(q) {
                score += 90
            }
            if hostLower.contains(q) {
                score += 80
            }
            if conn.tags.contains(where: { $0.lowercased().contains(q) }) {
                score += 60
            }
            if conn.protocolType.rawValue.lowercased().contains(q) || conn.protocolType.shortBadge.lowercased() == q {
                score += 50
            }
            if score > 0 {
                if conn.favorite { score += 15 }
                hits.append(QuickConnectSearchHit(
                    connection: conn,
                    score: score,
                    folderBreadcrumb: breadcrumb(for: conn.parentFolderId)
                ))
            }
        }
        return hits.sorted { $0.score > $1.score }
    }
}
