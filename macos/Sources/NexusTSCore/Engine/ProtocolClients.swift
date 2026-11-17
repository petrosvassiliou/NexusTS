import CryptoKit
import Darwin
import Foundation
import Security

// MARK: - Shared Configurable TLS URLSession Pool

public enum ConfigurableTlsUrlSession {
    private final class PermissiveTlsDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {
        func urlSession(
            _ session: URLSession,
            didReceive challenge: URLAuthenticationChallenge,
            completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
        ) {
            if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
               let trust = challenge.protectionSpace.serverTrust {
                completionHandler(.useCredential, URLCredential(trust: trust))
            } else {
                completionHandler(.performDefaultHandling, nil)
            }
        }
    }

    private static let strictSession: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 30
        return URLSession(configuration: cfg)
    }()

    private static let permissiveDelegate = PermissiveTlsDelegate()

    private static let permissiveSession: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 30
        return URLSession(configuration: cfg, delegate: permissiveDelegate, delegateQueue: nil)
    }()

    public static func session(verifyTls: Bool) -> URLSession {
        verifyTls ? strictSession : permissiveSession
    }
}

// MARK: - 1. Asciicast v2 (.cast) Session Recorder & Replay Parser

public struct AsciicastEvent: Equatable, Identifiable, Sendable {
    public let id = UUID()
    public let elapsedSeconds: Double
    public let kind: String // "o" or "i"
    public let payload: String
}

public final class AsciicastSessionRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var startedAt: Date = Date()
    private var width: Int = 110
    private var height: Int = 32
    private var title: String = "NexusTS Session"
    private(set) public var isRecording: Bool = false
    private var events: [AsciicastEvent] = []

    public init() {}

    public func start(width: Int, height: Int, title: String) {
        lock.lock()
        defer { lock.unlock() }
        self.startedAt = Date()
        self.width = max(10, width)
        self.height = max(4, height)
        self.title = title
        self.events.removeAll()
        self.isRecording = true
    }

    public func stop() {
        lock.lock()
        defer { lock.unlock() }
        self.isRecording = false
    }

    public func recordOutput(_ text: String) {
        lock.lock()
        defer { lock.unlock() }
        guard isRecording, !text.isEmpty else { return }
        let elapsed = Date().timeIntervalSince(startedAt)
        events.append(AsciicastEvent(elapsedSeconds: elapsed, kind: "o", payload: text))
    }

    public func exportCastDocument() -> String {
        lock.lock()
        defer { lock.unlock() }
        let headerDict: [String: Any] = [
            "version": 2,
            "width": width,
            "height": height,
            "timestamp": Int(startedAt.timeIntervalSince1970),
            "title": title,
            "env": ["TERM": "xterm-256color", "SHELL": "/bin/zsh"]
        ]
        let headerJson = (try? JSONSerialization.data(withJSONObject: headerDict))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{\"version\":2}"

        var lines = [headerJson]
        for ev in events {
            let arr: [Any] = [
                (ev.elapsedSeconds * 1000).rounded() / 1000,
                ev.kind,
                ev.payload
            ]
            if let data = try? JSONSerialization.data(withJSONObject: arr),
               let line = String(data: data, encoding: .utf8) {
                lines.append(line)
            }
        }
        return lines.joined(separator: "\n")
    }

    public var recordedEvents: [AsciicastEvent] {
        lock.lock()
        defer { lock.unlock() }
        return events
    }
}

// MARK: - 2. Cloud & Hypervisor Inventory Clients (Proxmox VE, vSphere, AWS EC2 SigV4, Azure ARM, Hyper-V)

public struct CloudVmInventoryItem: Identifiable, Equatable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let powerState: String
    public let addressOrNode: String
    public let cpuSummary: String
    public let memorySummary: String
    public let consoleProtocol: VaultProtocolType

    public init(
        id: String,
        name: String,
        powerState: String,
        addressOrNode: String,
        cpuSummary: String,
        memorySummary: String,
        consoleProtocol: VaultProtocolType = .SSH
    ) {
        self.id = id
        self.name = name
        self.powerState = powerState
        self.addressOrNode = addressOrNode
        self.cpuSummary = cpuSummary
        self.memorySummary = memorySummary
        self.consoleProtocol = consoleProtocol
    }
}

public enum HyperVPowerAction: String, CaseIterable, Sendable {
    case start = "START"
    case stop = "STOP"
    case save = "SAVE"
    case reboot = "REBOOT"

    public var displayTitle: String {
        switch self {
        case .start: return "Start"
        case .stop: return "Turn Off"
        case .save: return "Save State"
        case .reboot: return "Restart"
        }
    }

    public var requestedWmiState: Int {
        switch self {
        case .start: return 2
        case .stop: return 3
        case .save: return 32769
        case .reboot: return 10
        }
    }
}

public struct HyperVPortDiagnostic: Identifiable, Equatable, Sendable {
    public var id: UInt16 { port }
    public let port: UInt16
    public let serviceLabel: String
    public let isOpen: Bool
    public let latencyMs: UInt64

    public init(port: UInt16, serviceLabel: String, isOpen: Bool, latencyMs: UInt64) {
        self.port = port
        self.serviceLabel = serviceLabel
        self.isOpen = isOpen
        self.latencyMs = latencyMs
    }
}

public enum CloudHypervisorClient {
    /// Strips legacy poisoned defaults (`https://localhost:8006` from Proxmox or bare `https://<host>` on port 443)
    /// so Hyper-V connections automatically probe WinRM HTTP `:5985`, WinRM HTTPS `:5986`, and OpenSSH `:22`.
    public static func sanitizedHyperVEndpoint(rawEndpoint: String, connectionHost: String) -> String {
        let trimmed = rawEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !trimmed.isEmpty else { return "" }
        let lower = trimmed.lowercased()
        let cleanHost = connectionHost.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        if lower.contains(":8006") && !cleanHost.contains(":8006") {
            return ""
        }
        if (lower.contains("localhost") || lower.contains("127.0.0.1"))
            && !cleanHost.isEmpty
            && cleanHost != "localhost"
            && cleanHost != "127.0.0.1" {
            return ""
        }
        // Bare https://<host> or http://<host> without an explicit :port came from generic URL synthesis (port 443/80).
        if let comps = URLComponents(string: trimmed), comps.port == nil {
            let path = comps.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased()
            if path.isEmpty {
                return ""
            }
        }
        return trimmed
    }

    public static func diagnoseHyperVPorts(host: String) async -> [HyperVPortDiagnostic] {
        let cleanHost = extractBareHostname(host)
        guard !cleanHost.isEmpty else { return [] }
        let targets: [(UInt16, String)] = [
            (2179, "VMConnect"),
            (5985, "WinRM HTTP"),
            (5986, "WinRM HTTPS"),
            (22, "OpenSSH"),
            (3389, "Host RDP")
        ]
        return await withTaskGroup(of: (Int, HyperVPortDiagnostic).self) { group in
            for (idx, item) in targets.enumerated() {
                group.addTask {
                    let hit = NativeNetworkDiagnostics.probeTcpPort(host: cleanHost, port: item.0, timeoutMs: 650)
                    let isOpen = hit?.open ?? false
                    let latency = hit?.connectLatencyMs ?? 0
                    return (idx, HyperVPortDiagnostic(port: item.0, serviceLabel: item.1, isOpen: isOpen, latencyMs: latency))
                }
            }
            var ordered = Array<HyperVPortDiagnostic?>(repeating: nil, count: targets.count)
            for await (idx, diag) in group {
                ordered[idx] = diag
            }
            return ordered.compactMap { $0 }
        }
    }

    private static func extractBareHostname(_ rawHost: String) -> String {
        var h = rawHost.trimmingCharacters(in: .whitespacesAndNewlines)
        if h.lowercased().hasPrefix("http://") || h.lowercased().hasPrefix("https://"),
           let comps = URLComponents(string: h),
           let parsedHost = comps.host, !parsedHost.isEmpty {
            return parsedHost
        }
        if let slashIdx = h.firstIndex(of: "/") {
            h = String(h[..<slashIdx])
        }
        if !h.hasPrefix("["), h.filter({ $0 == ":" }).count == 1, let colonIdx = h.lastIndex(of: ":") {
            let portPart = h[h.index(after: colonIdx)...]
            if Int(portPart) != nil {
                h = String(h[..<colonIdx])
            }
        }
        return h.trimmingCharacters(in: CharacterSet(charactersIn: "[] "))
    }

    private static func splitWindowsIdentity(
        credential: VaultCredential?,
        fallbackRealmOrDomain: String
    ) -> (username: String, password: String, domain: String) {
        guard let cred = credential else { return ("", "", "") }
        let rawUser = cred.username.trimmingCharacters(in: .whitespacesAndNewlines)
        let rawDom = cred.domain.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallbackDom = fallbackRealmOrDomain.trimmingCharacters(in: .whitespacesAndNewlines)

        if let backslash = rawUser.firstIndex(of: "\\") {
            let domPart = String(rawUser[..<backslash])
            let userPart = String(rawUser[rawUser.index(after: backslash)...])
            let effectiveDom = domPart == "." ? "" : (rawDom.isEmpty ? domPart : rawDom)
            return (userPart, cred.secretPassword, effectiveDom)
        }
        let resolvedDom = !rawDom.isEmpty ? rawDom : (rawUser.contains("@") ? "" : fallbackDom)
        return (rawUser, cred.secretPassword, resolvedDom == "." ? "" : resolvedDom)
    }

    public static func resolveProxmoxBaseUrl(apiEndpointUrl: String, connectionHost: String) -> String {
        let cleanHost = extractBareHostname(connectionHost)
        var ep = apiEndpointUrl.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if ep.lowercased().contains("localhost") && !cleanHost.isEmpty && cleanHost.lowercased() != "localhost" {
            ep = ""
        }
        if ep.isEmpty {
            guard !cleanHost.isEmpty else { return "" }
            let bracketed = cleanHost.contains(":") ? "[\(cleanHost)]" : cleanHost
            return "https://\(bracketed):8006"
        }
        if !ep.lowercased().hasPrefix("http://") && !ep.lowercased().hasPrefix("https://") {
            ep = "https://\(ep)"
        }
        if let comps = URLComponents(string: ep), comps.port == nil, let h = comps.host, !h.isEmpty {
            var updated = comps
            updated.port = 8006
            if let built = updated.string {
                return built.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            }
        }
        return ep
    }

    public static func resolveVsphereBaseUrl(apiEndpointUrl: String, connectionHost: String) -> String {
        let cleanHost = extractBareHostname(connectionHost)
        var ep = apiEndpointUrl.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if ep.lowercased().contains("localhost") && !cleanHost.isEmpty && cleanHost.lowercased() != "localhost" {
            ep = ""
        }
        if ep.isEmpty {
            guard !cleanHost.isEmpty else { return "" }
            let bracketed = cleanHost.contains(":") ? "[\(cleanHost)]" : cleanHost
            return "https://\(bracketed)"
        }
        if !ep.lowercased().hasPrefix("http://") && !ep.lowercased().hasPrefix("https://") {
            ep = "https://\(ep)"
        }
        if ep.lowercased().hasSuffix("/sdk") {
            ep = String(ep.dropLast(4))
        }
        return ep.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    private static func formatProxmoxApiTokenHeader(username: String, secret: String, defaultRealm: String) -> String {
        if secret.hasPrefix("PVEAPIToken=") {
            return secret
        }
        let realm = defaultRealm.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "pam" : defaultRealm.trimmingCharacters(in: .whitespacesAndNewlines)
        var tokenId = username.trimmingCharacters(in: .whitespacesAndNewlines)
        if let bangIdx = tokenId.firstIndex(of: "!") {
            let userPart = String(tokenId[..<bangIdx])
            let tokPart = String(tokenId[tokenId.index(after: bangIdx)...])
            if !userPart.contains("@") {
                tokenId = "\(userPart)@\(realm)!\(tokPart)"
            }
        }
        return "PVEAPIToken=\(tokenId)=\(secret)"
    }

    private static func authenticateProxmoxRequest(
        base: String,
        cfg: CloudVirtualizationProtocolSettings,
        credential: VaultCredential?,
        urlSession: URLSession
    ) async throws -> (authHeader: String?, cookieHeader: String?, csrfToken: String?) {
        guard let cred = credential, !cred.secretPassword.isEmpty else {
            return (nil, nil, nil)
        }
        let realm = cfg.realmOrDomain.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "pam"
            : cfg.realmOrDomain.trimmingCharacters(in: .whitespacesAndNewlines)
        if cred.username.contains("!") || cred.secretPassword.hasPrefix("PVEAPIToken=") {
            let hdr = formatProxmoxApiTokenHeader(username: cred.username, secret: cred.secretPassword, defaultRealm: realm)
            return (hdr, nil, nil)
        }
        guard let ticketUrl = URL(string: "\(base)/api2/json/access/ticket") else {
            throw NativeBridgeError.networkUtilityError("Invalid Proxmox ticket URL for \(base)")
        }
        let userWithRealm = cred.username.contains("@") ? cred.username : "\(cred.username)@\(realm)"
        var ticketReq = URLRequest(url: ticketUrl)
        ticketReq.httpMethod = "POST"
        ticketReq.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
        var formComps = URLComponents()
        formComps.queryItems = [
            URLQueryItem(name: "username", value: userWithRealm),
            URLQueryItem(name: "password", value: cred.secretPassword)
        ]
        ticketReq.httpBody = Data((formComps.percentEncodedQuery ?? "").utf8)
        let (ticketData, ticketResp) = try await urlSession.data(for: ticketReq)
        if let http = ticketResp as? HTTPURLResponse, http.statusCode >= 400 {
            throw NativeBridgeError.authenticationFailed("Proxmox authentication failed (HTTP \(http.statusCode)) for \(userWithRealm).")
        }
        struct PveTicketResp: Decodable {
            struct TicketPayload: Decodable {
                let ticket: String?
                let CSRFPreventionToken: String?
            }
            let data: TicketPayload?
        }
        let parsed = try JSONDecoder().decode(PveTicketResp.self, from: ticketData)
        guard let ticket = parsed.data?.ticket, !ticket.isEmpty else {
            throw NativeBridgeError.authenticationFailed("Proxmox ticket response did not contain a valid PVEAuthCookie.")
        }
        return (nil, "PVEAuthCookie=\(ticket)", parsed.data?.CSRFPreventionToken)
    }

    public static func fetchInventory(
        connection: VaultConnection,
        credential: VaultCredential?
    ) async throws -> [CloudVmInventoryItem] {
        guard case .cloudVirtualization(let cfg) = connection.settings else { return [] }
        let urlSession = ConfigurableTlsUrlSession.session(verifyTls: cfg.verifyTls)

        switch connection.protocolType {
        case .PROXMOX_VE:
            let base = resolveProxmoxBaseUrl(apiEndpointUrl: cfg.apiEndpointUrl, connectionHost: connection.host)
            guard !base.isEmpty, let url = URL(string: "\(base)/api2/json/cluster/resources?type=vm") else {
                throw NativeBridgeError.networkUtilityError("Proxmox VE host or API URL is empty. Edit the connection and set your Proxmox node IP or FQDN.")
            }
            let auth = try await authenticateProxmoxRequest(base: base, cfg: cfg, credential: credential, urlSession: urlSession)
            var req = URLRequest(url: url)
            if let authHdr = auth.authHeader { req.setValue(authHdr, forHTTPHeaderField: "Authorization") }
            if let cookie = auth.cookieHeader { req.setValue(cookie, forHTTPHeaderField: "Cookie") }
            if let csrf = auth.csrfToken { req.setValue(csrf, forHTTPHeaderField: "CSRFPreventionToken") }

            let (data, resp) = try await urlSession.data(for: req)
            if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
                if http.statusCode == 401 || http.statusCode == 403 {
                    throw NativeBridgeError.authenticationFailed("Proxmox VE rejected credentials (HTTP \(http.statusCode)). Verify realm (pam/pve) or API token privileges.")
                }
                throw NativeBridgeError.networkUtilityError("Proxmox VE API returned HTTP \(http.statusCode).")
            }
            struct PveResp: Decodable {
                struct Item: Decodable {
                    let vmid: Int?
                    let name: String?
                    let status: String?
                    let node: String?
                    let type: String?
                    let maxcpu: Int?
                    let maxmem: Int64?
                }
                let data: [Item]?
            }
            let decoded = try JSONDecoder().decode(PveResp.self, from: data)
            return (decoded.data ?? []).map { vm in
                let idStr = "\(vm.vmid ?? 100)"
                let vmType = (vm.type ?? "qemu").lowercased()
                let node = vm.node ?? extractBareHostname(connection.host)
                let memGb = Double(vm.maxmem ?? 0) / (1024 * 1024 * 1024)
                return CloudVmInventoryItem(
                    id: "\(node)/\(vmType)/\(idStr)",
                    name: vm.name ?? "vm-\(idStr)",
                    powerState: (vm.status ?? "unknown").uppercased(),
                    addressOrNode: node,
                    cpuSummary: "\(vm.maxcpu ?? 2) vCPU (\(vmType.uppercased()))",
                    memorySummary: String(format: "%.1f GiB", memGb),
                    consoleProtocol: .WEB_PAGE
                )
            }

        case .VMWARE_VSPHERE:
            let base = resolveVsphereBaseUrl(apiEndpointUrl: cfg.apiEndpointUrl, connectionHost: connection.host)
            guard !base.isEmpty else {
                throw NativeBridgeError.networkUtilityError("vSphere / ESXi host address is empty. Edit the connection and enter your vCenter or ESXi hostname/IP.")
            }
            return try await fetchVsphereOrEsxiInventory(base: base, fallbackHost: extractBareHostname(connection.host), credential: credential, urlSession: urlSession)

        case .AWS_EC2:
            let cleanHost = connection.host.trimmingCharacters(in: .whitespacesAndNewlines)
            let region: String = {
                let r = cfg.regionOrCluster.trimmingCharacters(in: .whitespacesAndNewlines)
                if !r.isEmpty { return r }
                if !cleanHost.isEmpty && !cleanHost.hasPrefix("http") && !cleanHost.contains(".") { return cleanHost }
                return "us-east-1"
            }()
            let sessionToken: String? = {
                let ext = (credential?.externalReferenceUri ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                if ext.hasPrefix("sts:") { return String(ext.dropFirst(4)) }
                let sub = cfg.subscriptionOrAccountId.trimmingCharacters(in: .whitespacesAndNewlines)
                if sub.count > 40 { return sub }
                return nil
            }()
            return try await describeEc2InstancesSigV4(
                region: region,
                accessKeyId: credential?.username ?? "",
                secretAccessKey: credential?.secretPassword ?? "",
                sessionToken: sessionToken,
                endpointOverride: cfg.apiEndpointUrl,
                verifyTls: cfg.verifyTls
            )

        case .AZURE_VM:
            return try await fetchAzureArmInventory(connection: connection, cfg: cfg, credential: credential, urlSession: urlSession)

        case .HYPER_V:
            return try await fetchHyperVMultiTransportInventory(
                connection: connection,
                cfg: cfg,
                credential: credential
            )

        default:
            return []
        }
    }

    public static func performCloudPowerAction(
        connection: VaultConnection,
        credential: VaultCredential?,
        vmId: String,
        vmName: String,
        action: HyperVPowerAction
    ) async throws -> String {
        if connection.protocolType == .HYPER_V {
            return try await performHyperVPowerAction(
                connection: connection,
                credential: credential,
                vmId: vmId,
                vmName: vmName,
                action: action
            )
        }
        guard case .cloudVirtualization(let cfg) = connection.settings else {
            throw NativeBridgeError.networkUtilityError("Missing cloud virtualization configuration.")
        }
        let urlSession = ConfigurableTlsUrlSession.session(verifyTls: cfg.verifyTls)

        switch connection.protocolType {
        case .PROXMOX_VE:
            let base = resolveProxmoxBaseUrl(apiEndpointUrl: cfg.apiEndpointUrl, connectionHost: connection.host)
            let auth = try await authenticateProxmoxRequest(base: base, cfg: cfg, credential: credential, urlSession: urlSession)
            let parts = vmId.split(separator: "/").map(String.init)
            let node = parts.count >= 3 ? parts[0] : extractBareHostname(connection.host)
            let kind = parts.count >= 3 ? parts[1] : "qemu"
            let numericId = parts.last ?? vmId
            let pveVerb: String = {
                switch action {
                case .start: return "start"
                case .stop: return "stop"
                case .save: return kind == "lxc" ? "shutdown" : "suspend"
                case .reboot: return "reboot"
                }
            }()
            guard let url = URL(string: "\(base)/api2/json/nodes/\(node)/\(kind)/\(numericId)/status/\(pveVerb)") else {
                throw NativeBridgeError.networkUtilityError("Invalid Proxmox power URL.")
            }
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            if let authHdr = auth.authHeader { req.setValue(authHdr, forHTTPHeaderField: "Authorization") }
            if let cookie = auth.cookieHeader { req.setValue(cookie, forHTTPHeaderField: "Cookie") }
            if let csrf = auth.csrfToken { req.setValue(csrf, forHTTPHeaderField: "CSRFPreventionToken") }
            let (_, resp) = try await urlSession.data(for: req)
            if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
                throw NativeBridgeError.networkUtilityError("Proxmox \(pveVerb) failed (HTTP \(http.statusCode)).")
            }
            return "Proxmox \(action.displayTitle) dispatched for \(vmName) (\(kind)/\(numericId))."

        case .VMWARE_VSPHERE:
            let base = resolveVsphereBaseUrl(apiEndpointUrl: cfg.apiEndpointUrl, connectionHost: connection.host)
            return try await performVsphereOrEsxiPowerAction(
                base: base,
                vmId: vmId,
                vmName: vmName,
                action: action,
                credential: credential,
                urlSession: urlSession
            )

        case .AWS_EC2:
            let cleanHost = connection.host.trimmingCharacters(in: .whitespacesAndNewlines)
            let region = cfg.regionOrCluster.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? ((!cleanHost.isEmpty && !cleanHost.contains(".")) ? cleanHost : "us-east-1")
                : cfg.regionOrCluster.trimmingCharacters(in: .whitespacesAndNewlines)
            let ec2Action: String = {
                switch action {
                case .start: return "StartInstances"
                case .stop, .save: return "StopInstances"
                case .reboot: return "RebootInstances"
                }
            }()
            let sessionToken: String? = {
                let ext = (credential?.externalReferenceUri ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                if ext.hasPrefix("sts:") { return String(ext.dropFirst(4)) }
                let sub = cfg.subscriptionOrAccountId.trimmingCharacters(in: .whitespacesAndNewlines)
                return sub.count > 40 ? sub : nil
            }()
            _ = try await executeEc2SignedQuery(
                region: region,
                accessKeyId: credential?.username ?? "",
                secretAccessKey: credential?.secretPassword ?? "",
                sessionToken: sessionToken,
                formBody: "Action=\(ec2Action)&InstanceId.1=\(vmId)&Version=2016-11-15",
                endpointOverride: cfg.apiEndpointUrl,
                verifyTls: cfg.verifyTls
            )
            return "AWS EC2 \(ec2Action) dispatched for \(vmName) (\(vmId))."

        case .AZURE_VM:
            let token = try await resolveAzureBearerToken(cfg: cfg, credential: credential, urlSession: urlSession)
            let armBase = resolveAzureArmBaseUrl(cfg: cfg)
            let armVerb: String = {
                switch action {
                case .start: return "start"
                case .stop: return "powerOff"
                case .save: return "deallocate"
                case .reboot: return "restart"
                }
            }()
            let resourcePath = vmId.hasPrefix("/") ? vmId : "/subscriptions/\(cfg.subscriptionOrAccountId)/providers/Microsoft.Compute/virtualMachines/\(vmId)"
            guard let url = URL(string: "\(armBase)\(resourcePath)/\(armVerb)?api-version=2023-09-01") else {
                throw NativeBridgeError.networkUtilityError("Invalid Azure ARM power URL.")
            }
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            req.setValue("0", forHTTPHeaderField: "Content-Length")
            let (respData, resp) = try await urlSession.data(for: req)
            if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
                let errText = String(data: respData, encoding: .utf8) ?? ""
                throw NativeBridgeError.networkUtilityError("Azure \(armVerb) failed (HTTP \(http.statusCode)): \(errText.prefix(200))")
            }
            return "Azure \(armVerb) dispatched for \(vmName)."

        default:
            throw NativeBridgeError.networkUtilityError("Power actions are not supported for \(connection.protocolType.displayName).")
        }
    }

    // MARK: - VMware vCenter REST (7/8 & 6.5 CIS) + Standalone ESXi VIM SOAP (`/sdk`)

    private static func fetchVsphereOrEsxiInventory(
        base: String,
        fallbackHost: String,
        credential: VaultCredential?,
        urlSession: URLSession
    ) async throws -> [CloudVmInventoryItem] {
        let basicHeader: String? = credential.map {
            "Basic " + Data("\($0.username):\($0.secretPassword)".utf8).base64EncodedString()
        }

        // 1. Try vCenter 7.0 / 8.0 REST API (`/api/session` + `/api/vcenter/vm`)
        if let sessionUrl = URL(string: "\(base)/api/session"),
           let vmUrl = URL(string: "\(base)/api/vcenter/vm") {
            var authReq = URLRequest(url: sessionUrl)
            authReq.httpMethod = "POST"
            if let basicHeader { authReq.setValue(basicHeader, forHTTPHeaderField: "Authorization") }
            if let (tokData, resp) = try? await urlSession.data(for: authReq),
               let http = resp as? HTTPURLResponse {
                if http.statusCode == 401 || http.statusCode == 403 {
                    throw NativeBridgeError.authenticationFailed("vSphere REST authentication failed (HTTP \(http.statusCode)).")
                }
                if (200..<300).contains(http.statusCode) {
                    let token = String(data: tokData, encoding: .utf8)?.trimmingCharacters(in: CharacterSet(charactersIn: "\"\r\n ")) ?? ""
                    var listReq = URLRequest(url: vmUrl)
                    listReq.setValue(token, forHTTPHeaderField: "vmware-api-session-id")
                    let (vmData, listResp) = try await urlSession.data(for: listReq)
                    if let lHttp = listResp as? HTTPURLResponse, (200..<300).contains(lHttp.statusCode) {
                        struct VsphereVm: Decodable {
                            let vm: String?
                            let name: String?
                            let power_state: String?
                            let cpu_count: Int?
                            let memory_size_MiB: Int?
                        }
                        struct GuestIdent: Decodable {
                            let ip_address: String?
                            let family: String?
                        }
                        let vms = (try? JSONDecoder().decode([VsphereVm].self, from: vmData)) ?? []
                        var out: [CloudVmInventoryItem] = []
                        for item in vms {
                            let moid = item.vm ?? UUID().uuidString
                            var guestIp = ""
                            var consoleProto: VaultProtocolType = .WEB_PAGE
                            if (item.power_state ?? "").uppercased().contains("ON"),
                               let guestUrl = URL(string: "\(base)/api/vcenter/vm/\(moid)/guest/identity") {
                                var gReq = URLRequest(url: guestUrl)
                                gReq.setValue(token, forHTTPHeaderField: "vmware-api-session-id")
                                if let (gData, gResp) = try? await urlSession.data(for: gReq),
                                   let gHttp = gResp as? HTTPURLResponse, (200..<300).contains(gHttp.statusCode),
                                   let ident = try? JSONDecoder().decode(GuestIdent.self, from: gData),
                                   let ip = ident.ip_address, !ip.isEmpty {
                                    guestIp = ip
                                    consoleProto = (ident.family ?? "").uppercased().contains("WIN") ? .RDP : .SSH
                                }
                            }
                            out.append(CloudVmInventoryItem(
                                id: moid,
                                name: item.name ?? "vSphere VM",
                                powerState: item.power_state ?? "POWERED_ON",
                                addressOrNode: guestIp.isEmpty ? fallbackHost : guestIp,
                                cpuSummary: "\(item.cpu_count ?? 2) vCPU",
                                memorySummary: "\(item.memory_size_MiB ?? 4096) MiB",
                                consoleProtocol: consoleProto
                            ))
                        }
                        return out
                    }
                }
            }
        }

        // 2. Try vCenter 6.5 / 6.7 CIS REST API (`/rest/com/vmware/cis/session` + `/rest/vcenter/vm`)
        if let cisUrl = URL(string: "\(base)/rest/com/vmware/cis/session"),
           let cisVmUrl = URL(string: "\(base)/rest/vcenter/vm") {
            var cisReq = URLRequest(url: cisUrl)
            cisReq.httpMethod = "POST"
            if let basicHeader { cisReq.setValue(basicHeader, forHTTPHeaderField: "Authorization") }
            if let (tokData, resp) = try? await urlSession.data(for: cisReq),
               let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) {
                struct CisToken: Decodable { let value: String? }
                if let tok = (try? JSONDecoder().decode(CisToken.self, from: tokData))?.value, !tok.isEmpty {
                    var listReq = URLRequest(url: cisVmUrl)
                    listReq.setValue(tok, forHTTPHeaderField: "vmware-api-session-id")
                    let (vmData, _) = try await urlSession.data(for: listReq)
                    struct CisVmList: Decodable {
                        struct Item: Decodable {
                            let vm: String?
                            let name: String?
                            let power_state: String?
                            let cpu_count: Int?
                            let memory_size_MiB: Int?
                        }
                        let value: [Item]?
                    }
                    let list = try JSONDecoder().decode(CisVmList.self, from: vmData)
                    return (list.value ?? []).map { item in
                        CloudVmInventoryItem(
                            id: item.vm ?? UUID().uuidString,
                            name: item.name ?? "vSphere VM",
                            powerState: item.power_state ?? "POWERED_ON",
                            addressOrNode: fallbackHost,
                            cpuSummary: "\(item.cpu_count ?? 2) vCPU",
                            memorySummary: "\(item.memory_size_MiB ?? 4096) MiB",
                            consoleProtocol: .WEB_PAGE
                        )
                    }
                }
            }
        }

        // 3. Standalone ESXi Host VIM SOAP XML API (`POST <base>/sdk`)
        return try await fetchEsxiSoapInventory(base: base, fallbackHost: fallbackHost, credential: credential, urlSession: urlSession)
    }

    private static func esxiSoapPost(
        sdkUrl: URL,
        soapBody: String,
        cookie: String?,
        urlSession: URLSession
    ) async throws -> (xml: String, setCookie: String?) {
        let envelope = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:urn="urn:vim25">
          <soapenv:Body>\(soapBody)</soapenv:Body>
        </soapenv:Envelope>
        """
        var req = URLRequest(url: sdkUrl)
        req.httpMethod = "POST"
        req.setValue("text/xml; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("\"urn:vim25/7.0\"", forHTTPHeaderField: "SOAPAction")
        if let cookie, !cookie.isEmpty {
            req.setValue(cookie, forHTTPHeaderField: "Cookie")
        }
        req.httpBody = Data(envelope.utf8)
        let (data, resp) = try await urlSession.data(for: req)
        let xml = String(data: data, encoding: .utf8) ?? ""
        if let http = resp as? HTTPURLResponse {
            if xml.contains("InvalidLogin") || http.statusCode == 401 {
                throw NativeBridgeError.authenticationFailed("ESXi / vSphere VIM SOAP login rejected credentials.")
            }
            if http.statusCode >= 500, let fault = extractXmlTagValue(xml, tag: "faultstring") {
                throw NativeBridgeError.networkUtilityError("ESXi SOAP Fault: \(fault)")
            } else if http.statusCode >= 400 {
                throw NativeBridgeError.networkUtilityError("ESXi /sdk endpoint returned HTTP \(http.statusCode).")
            }
            let rawSetCookie = http.value(forHTTPHeaderField: "Set-Cookie")
            let sessionCookie = rawSetCookie?.split(separator: ";").first.map(String.init)
            return (xml, sessionCookie)
        }
        return (xml, nil)
    }

    private static func extractXmlTagValue(_ xml: String, tag: String) -> String? {
        guard let open = xml.range(of: "<\(tag)", options: .caseInsensitive),
              let gt = xml[open.upperBound...].firstIndex(of: ">") else { return nil }
        let afterGt = xml.index(after: gt)
        guard let close = xml[afterGt...].range(of: "</\(tag)>", options: .caseInsensitive) else { return nil }
        return String(xml[afterGt..<close.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func loginEsxiSoapSession(
        sdkUrl: URL,
        credential: VaultCredential?,
        urlSession: URLSession
    ) async throws -> (sessionCookie: String, rootFolderMoId: String, propCollectorMoId: String, viewMgrMoId: String) {
        let scBody = """
        <urn:RetrieveServiceContent>
          <urn:_this type="ServiceInstance">ServiceInstance</urn:_this>
        </urn:RetrieveServiceContent>
        """
        let (scXml, initialCookie) = try await esxiSoapPost(sdkUrl: sdkUrl, soapBody: scBody, cookie: nil, urlSession: urlSession)
        let sessionMgr = extractXmlTagValue(scXml, tag: "sessionManager") ?? "ha-sessionmgr"
        let rootFolder = extractXmlTagValue(scXml, tag: "rootFolder") ?? "ha-folder-root"
        let propCollector = extractXmlTagValue(scXml, tag: "propertyCollector") ?? "ha-property-collector"
        let viewMgr = extractXmlTagValue(scXml, tag: "viewManager") ?? "ha-view-manager"

        let user = xmlEscape(credential?.username ?? "root")
        let pass = xmlEscape(credential?.secretPassword ?? "")
        let loginBody = """
        <urn:Login>
          <urn:_this type="SessionManager">\(xmlEscape(sessionMgr))</urn:_this>
          <urn:userName>\(user)</urn:userName>
          <urn:password>\(pass)</urn:password>
        </urn:Login>
        """
        let (_, loginCookie) = try await esxiSoapPost(sdkUrl: sdkUrl, soapBody: loginBody, cookie: initialCookie, urlSession: urlSession)
        let activeCookie = loginCookie ?? initialCookie ?? ""
        return (activeCookie, rootFolder, propCollector, viewMgr)
    }

    private static func fetchEsxiSoapInventory(
        base: String,
        fallbackHost: String,
        credential: VaultCredential?,
        urlSession: URLSession
    ) async throws -> [CloudVmInventoryItem] {
        guard let sdkUrl = URL(string: "\(base)/sdk") else {
            throw NativeBridgeError.networkUtilityError("Invalid ESXi SDK URL: \(base)/sdk")
        }
        let session = try await loginEsxiSoapSession(sdkUrl: sdkUrl, credential: credential, urlSession: urlSession)
        let createViewBody = """
        <urn:CreateContainerView>
          <urn:_this type="ViewManager">\(xmlEscape(session.viewMgrMoId))</urn:_this>
          <urn:container type="Folder">\(xmlEscape(session.rootFolderMoId))</urn:container>
          <urn:type>VirtualMachine</urn:type>
          <urn:recursive>true</urn:recursive>
        </urn:CreateContainerView>
        """
        let (viewXml, _) = try await esxiSoapPost(sdkUrl: sdkUrl, soapBody: createViewBody, cookie: session.sessionCookie, urlSession: urlSession)
        let containerViewId = extractXmlTagValue(viewXml, tag: "returnval") ?? "session[0]"

        let retrieveBody = """
        <urn:RetrievePropertiesEx>
          <urn:_this type="PropertyCollector">\(xmlEscape(session.propCollectorMoId))</urn:_this>
          <urn:specSet>
            <urn:propSet>
              <urn:type>VirtualMachine</urn:type>
              <urn:pathSet>name</urn:pathSet>
              <urn:pathSet>runtime.powerState</urn:pathSet>
              <urn:pathSet>config.hardware.numCPU</urn:pathSet>
              <urn:pathSet>config.hardware.memoryMB</urn:pathSet>
              <urn:pathSet>guest.ipAddress</urn:pathSet>
              <urn:pathSet>guest.guestFullName</urn:pathSet>
            </urn:propSet>
            <urn:objectSet>
              <urn:obj type="ContainerView">\(xmlEscape(containerViewId))</urn:obj>
              <urn:skip>true</urn:skip>
              <urn:selectSet xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="urn:TraversalSpec">
                <urn:name>traverseView</urn:name>
                <urn:type>ContainerView</urn:type>
                <urn:path>view</urn:path>
                <urn:skip>false</urn:skip>
              </urn:selectSet>
            </urn:objectSet>
          </urn:specSet>
          <urn:options/>
        </urn:RetrievePropertiesEx>
        """
        let (propsXml, _) = try await esxiSoapPost(sdkUrl: sdkUrl, soapBody: retrieveBody, cookie: session.sessionCookie, urlSession: urlSession)
        return parseEsxiSoapPropertiesXml(propsXml, fallbackHost: fallbackHost)
    }

    public static func parseEsxiSoapPropertiesXml(_ xml: String, fallbackHost: String) -> [CloudVmInventoryItem] {
        final class EsxiPropsDelegate: NSObject, XMLParserDelegate {
            let fallbackHost: String
            var items: [CloudVmInventoryItem] = []
            private var insideObjects = false
            private var insidePropSet = false
            private var currentText = ""
            private var moid = ""
            private var propName = ""
            private var props: [String: String] = [:]

            init(fallbackHost: String) { self.fallbackHost = fallbackHost }

            private func localName(_ raw: String) -> String {
                raw.split(separator: ":").last.map(String.init) ?? raw
            }

            func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
                let local = localName(qName ?? elementName)
                currentText = ""
                if local == "objects" {
                    insideObjects = true
                    moid = ""
                    props = [:]
                } else if insideObjects && local == "propSet" {
                    insidePropSet = true
                    propName = ""
                }
            }

            func parser(_ parser: XMLParser, foundCharacters string: String) {
                currentText += string
            }

            func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
                let local = localName(qName ?? elementName)
                let val = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
                if insidePropSet {
                    if local == "name" {
                        propName = val
                    } else if local == "val" && !propName.isEmpty {
                        props[propName] = val
                    } else if local == "propSet" {
                        insidePropSet = false
                    }
                } else if insideObjects {
                    if local == "obj" {
                        moid = val
                    } else if local == "objects" {
                        insideObjects = false
                        guard !moid.isEmpty else { return }
                        let rawPower = (props["runtime.powerState"] ?? "poweredOff").lowercased()
                        let powerState: String = {
                            if rawPower.contains("on") { return "POWERED_ON" }
                            if rawPower.contains("suspend") { return "SUSPENDED" }
                            return "POWERED_OFF"
                        }()
                        let guestIp = props["guest.ipAddress"] ?? ""
                        let guestOs = (props["guest.guestFullName"] ?? "").lowercased()
                        let proto: VaultProtocolType = guestIp.isEmpty
                            ? .WEB_PAGE
                            : (guestOs.contains("win") ? .RDP : .SSH)
                        items.append(CloudVmInventoryItem(
                            id: moid,
                            name: props["name"] ?? "ESXi VM \(moid)",
                            powerState: powerState,
                            addressOrNode: guestIp.isEmpty ? fallbackHost : guestIp,
                            cpuSummary: "\(props["config.hardware.numCPU"] ?? "2") vCPU",
                            memorySummary: "\(props["config.hardware.memoryMB"] ?? "4096") MiB",
                            consoleProtocol: proto
                        ))
                    }
                }
                currentText = ""
            }
        }
        guard let data = xml.data(using: .utf8), !data.isEmpty else { return [] }
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = false
        let delegate = EsxiPropsDelegate(fallbackHost: fallbackHost)
        parser.delegate = delegate
        _ = parser.parse()
        return delegate.items
    }

    private static func performVsphereOrEsxiPowerAction(
        base: String,
        vmId: String,
        vmName: String,
        action: HyperVPowerAction,
        credential: VaultCredential?,
        urlSession: URLSession
    ) async throws -> String {
        let restAction: String = {
            switch action {
            case .start: return "start"
            case .stop: return "stop"
            case .save: return "suspend"
            case .reboot: return "reset"
            }
        }()
        if let sessionUrl = URL(string: "\(base)/api/session"),
           let powerUrl = URL(string: "\(base)/api/vcenter/vm/\(vmId)/power?action=\(restAction)") {
            var authReq = URLRequest(url: sessionUrl)
            authReq.httpMethod = "POST"
            if let cred = credential {
                let basic = Data("\(cred.username):\(cred.secretPassword)".utf8).base64EncodedString()
                authReq.setValue("Basic \(basic)", forHTTPHeaderField: "Authorization")
            }
            if let (tokData, resp) = try? await urlSession.data(for: authReq),
               let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) {
                let token = String(data: tokData, encoding: .utf8)?.trimmingCharacters(in: CharacterSet(charactersIn: "\"\r\n ")) ?? ""
                var pReq = URLRequest(url: powerUrl)
                pReq.httpMethod = "POST"
                pReq.setValue(token, forHTTPHeaderField: "vmware-api-session-id")
                let (_, pResp) = try await urlSession.data(for: pReq)
                if let pHttp = pResp as? HTTPURLResponse, (200..<300).contains(pHttp.statusCode) {
                    return "vSphere \(action.displayTitle) dispatched for \(vmName)."
                }
            }
        }

        guard let sdkUrl = URL(string: "\(base)/sdk") else {
            throw NativeBridgeError.networkUtilityError("Invalid ESXi SDK URL.")
        }
        let session = try await loginEsxiSoapSession(sdkUrl: sdkUrl, credential: credential, urlSession: urlSession)
        let soapTask: String = {
            switch action {
            case .start: return "PowerOnVM_Task"
            case .stop: return "PowerOffVM_Task"
            case .save: return "SuspendVM_Task"
            case .reboot: return "ResetVM_Task"
            }
        }()
        let taskBody = "<urn:\(soapTask)><urn:_this type=\"VirtualMachine\">\(xmlEscape(vmId))</urn:_this></urn:\(soapTask)>"
        _ = try await esxiSoapPost(sdkUrl: sdkUrl, soapBody: taskBody, cookie: session.sessionCookie, urlSession: urlSession)
        return "ESXi \(soapTask) dispatched for \(vmName)."
    }

    // MARK: - Azure Resource Manager (OAuth2 Client Credentials + Bearer + statusOnly + NIC IP Resolution)

    private static func resolveAzureArmBaseUrl(cfg: CloudVirtualizationProtocolSettings) -> String {
        let ep = cfg.apiEndpointUrl.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if ep.isEmpty || ep.lowercased().contains("localhost:") {
            return "https://management.azure.com"
        }
        if ep.lowercased().hasPrefix("http://") || ep.lowercased().hasPrefix("https://") {
            return ep
        }
        return "https://management.azure.com"
    }

    private static func resolveAzureBearerToken(
        cfg: CloudVirtualizationProtocolSettings,
        credential: VaultCredential?,
        urlSession: URLSession
    ) async throws -> String {
        guard let cred = credential else {
            throw NativeBridgeError.authenticationFailed("Azure VM requires a Vault Credential (Entra ID Service Principal Client ID + Secret, or Bearer Token).")
        }
        let secret = cred.secretPassword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !secret.isEmpty else {
            throw NativeBridgeError.authenticationFailed("Azure credential secret/token is empty.")
        }
        if secret.hasPrefix("eyJ") && secret.contains(".") {
            return secret
        }
        if secret.lowercased().hasPrefix("bearer ") {
            return String(secret.dropFirst(7)).trimmingCharacters(in: .whitespaces)
        }

        let rawUser = cred.username.trimmingCharacters(in: .whitespacesAndNewlines)
        var tenantId = cfg.realmOrDomain.trimmingCharacters(in: .whitespacesAndNewlines)
        if tenantId == "pam" { tenantId = "" }
        if tenantId.isEmpty { tenantId = cred.domain.trimmingCharacters(in: .whitespacesAndNewlines) }
        var clientId = rawUser
        if rawUser.contains("|") {
            let parts = rawUser.split(separator: "|", maxSplits: 1).map(String.init)
            if tenantId.isEmpty { tenantId = parts[0] }
            clientId = parts[1]
        }
        if tenantId.isEmpty || clientId.isEmpty {
            return secret
        }

        let tokenEndpoint: URL = {
            let armBase = resolveAzureArmBaseUrl(cfg: cfg)
            if armBase.contains("127.0.0.1") || armBase.contains("localhost") {
                return URL(string: "\(armBase)/\(tenantId)/oauth2/v2.0/token")!
            }
            return URL(string: "https://login.microsoftonline.com/\(tenantId)/oauth2/v2.0/token")!
        }()
        var req = URLRequest(url: tokenEndpoint)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var form = URLComponents()
        form.queryItems = [
            URLQueryItem(name: "grant_type", value: "client_credentials"),
            URLQueryItem(name: "client_id", value: clientId),
            URLQueryItem(name: "client_secret", value: secret),
            URLQueryItem(name: "scope", value: "https://management.azure.com/.default")
        ]
        req.httpBody = Data((form.percentEncodedQuery ?? "").utf8)
        let (data, resp) = try await urlSession.data(for: req)
        if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
            throw NativeBridgeError.authenticationFailed("Azure Entra ID OAuth2 token exchange failed (HTTP \(http.statusCode)).")
        }
        struct OAuthResp: Decodable { let access_token: String? }
        guard let tok = (try? JSONDecoder().decode(OAuthResp.self, from: data))?.access_token, !tok.isEmpty else {
            throw NativeBridgeError.authenticationFailed("Azure OAuth2 response did not contain an access_token.")
        }
        return tok
    }

    private static func fetchAzureArmInventory(
        connection: VaultConnection,
        cfg: CloudVirtualizationProtocolSettings,
        credential: VaultCredential?,
        urlSession: URLSession
    ) async throws -> [CloudVmInventoryItem] {
        let subId = cfg.subscriptionOrAccountId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? connection.host.trimmingCharacters(in: .whitespacesAndNewlines)
            : cfg.subscriptionOrAccountId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !subId.isEmpty else {
            throw NativeBridgeError.networkUtilityError("Azure Subscription ID is empty. Edit the connection and enter your Subscription GUID.")
        }
        let token = try await resolveAzureBearerToken(cfg: cfg, credential: credential, urlSession: urlSession)
        let armBase = resolveAzureArmBaseUrl(cfg: cfg)
        let rg = cfg.regionOrCluster.trimmingCharacters(in: .whitespacesAndNewlines)
        let listUrlStr = rg.isEmpty
            ? "\(armBase)/subscriptions/\(subId)/providers/Microsoft.Compute/virtualMachines?api-version=2023-09-01&statusOnly=true"
            : "\(armBase)/subscriptions/\(subId)/resourceGroups/\(rg)/providers/Microsoft.Compute/virtualMachines?api-version=2023-09-01"
        guard let url = URL(string: listUrlStr) else { return [] }

        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await urlSession.data(for: req)
        if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
            let errBody = String(data: data, encoding: .utf8) ?? ""
            if http.statusCode == 401 || http.statusCode == 403 {
                throw NativeBridgeError.authenticationFailed("Azure ARM authentication rejected (HTTP \(http.statusCode)): \(errBody.prefix(200))")
            }
            throw NativeBridgeError.networkUtilityError("Azure ARM returned HTTP \(http.statusCode): \(errBody.prefix(200))")
        }

        struct AzureList: Decodable {
            struct VM: Decodable {
                struct Props: Decodable {
                    struct Hardware: Decodable { let vmSize: String? }
                    struct Storage: Decodable {
                        struct OsDisk: Decodable { let osType: String? }
                        let osDisk: OsDisk?
                    }
                    struct NetProfile: Decodable {
                        struct NicRef: Decodable { let id: String? }
                        let networkInterfaces: [NicRef]?
                    }
                    struct InstanceView: Decodable {
                        struct Status: Decodable { let code: String?; let displayStatus: String? }
                        let statuses: [Status]?
                    }
                    let hardwareProfile: Hardware?
                    let storageProfile: Storage?
                    let networkProfile: NetProfile?
                    let instanceView: InstanceView?
                }
                let id: String?
                let name: String?
                let location: String?
                let properties: Props?
            }
            let value: [VM]?
        }
        struct AzureNic: Decodable {
            struct Props: Decodable {
                struct IpCfg: Decodable {
                    struct IpProps: Decodable {
                        struct PubRef: Decodable { let id: String? }
                        let privateIPAddress: String?
                        let publicIPAddress: PubRef?
                    }
                    let properties: IpProps?
                }
                let ipConfigurations: [IpCfg]?
            }
            let properties: Props?
        }
        struct AzurePublicIp: Decodable {
            struct Props: Decodable { let ipAddress: String? }
            let properties: Props?
        }

        let list = try JSONDecoder().decode(AzureList.self, from: data)
        var items: [CloudVmInventoryItem] = []
        for vm in (list.value ?? []) {
            let vmId = vm.id ?? UUID().uuidString
            let powerCode = vm.properties?.instanceView?.statuses?
                .first(where: { ($0.code ?? "").lowercased().hasPrefix("powerstate/") })?.code ?? ""
            let powerState: String = {
                let lower = powerCode.lowercased()
                if lower.contains("running") { return "RUNNING" }
                if lower.contains("deallocat") { return "DEALLOCATED" }
                if lower.contains("stopped") { return "STOPPED" }
                return powerCode.isEmpty ? "RUNNING" : powerCode.replacingOccurrences(of: "PowerState/", with: "").uppercased()
            }()
            var resolvedIp = ""
            if let nicId = vm.properties?.networkProfile?.networkInterfaces?.first?.id,
               let nicUrl = URL(string: "\(armBase)\(nicId)?api-version=2023-09-01") {
                var nicReq = URLRequest(url: nicUrl)
                nicReq.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                if let (nicData, nicResp) = try? await urlSession.data(for: nicReq),
                   let nHttp = nicResp as? HTTPURLResponse, (200..<300).contains(nHttp.statusCode),
                   let nic = try? JSONDecoder().decode(AzureNic.self, from: nicData),
                   let ipProps = nic.properties?.ipConfigurations?.first?.properties {
                    resolvedIp = ipProps.privateIPAddress ?? ""
                    if let pubId = ipProps.publicIPAddress?.id,
                       let pubUrl = URL(string: "\(armBase)\(pubId)?api-version=2023-09-01") {
                        var pubReq = URLRequest(url: pubUrl)
                        pubReq.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                        if let (pubData, _) = try? await urlSession.data(for: pubReq),
                           let pub = try? JSONDecoder().decode(AzurePublicIp.self, from: pubData),
                           let pubIp = pub.properties?.ipAddress, !pubIp.isEmpty {
                            resolvedIp = pubIp
                        }
                    }
                }
            }
            let osType = (vm.properties?.storageProfile?.osDisk?.osType ?? "Linux").lowercased()
            let proto: VaultProtocolType = osType.contains("win") ? .RDP : .SSH
            items.append(CloudVmInventoryItem(
                id: vmId,
                name: vm.name ?? "Azure VM",
                powerState: powerState,
                addressOrNode: resolvedIp.isEmpty ? (vm.location ?? "") : resolvedIp,
                cpuSummary: vm.properties?.hardwareProfile?.vmSize ?? "Standard_D2s_v5",
                memorySummary: vm.location ?? "Azure",
                consoleProtocol: proto
            ))
        }
        return items
    }

    // MARK: - Hyper-V Multi-Transport Engine (WinRM 5985 NTLMv2+SPNEGO Sealing -> WinRM 5986 HTTPS NTLMv2 -> OpenSSH 22 PowerShell)

    public static func performHyperVPowerAction(
        connection: VaultConnection,
        credential: VaultCredential?,
        vmId: String,
        vmName: String,
        action: HyperVPowerAction
    ) async throws -> String {
        let cfg: CloudVirtualizationProtocolSettings = {
            if case .cloudVirtualization(let c) = connection.settings { return c }
            return CloudVirtualizationProtocolSettings(apiEndpointUrl: "")
        }()
        let bareHost = extractBareHostname(connection.host)
        guard !bareHost.isEmpty else {
            throw NativeBridgeError.networkUtilityError("Hyper-V host address is empty. Edit the connection and set the Hyper-V Server IP or hostname.")
        }
        let identity = splitWindowsIdentity(credential: credential, fallbackRealmOrDomain: cfg.realmOrDomain)
        let hasCreds = !identity.username.isEmpty || !(credential?.privateKeyPem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
        if !hasCreds {
            throw NativeBridgeError.authenticationFailed("Hyper-V requires Windows Administrator credentials. Assign or inherit a Vault Credential on this connection.")
        }

        let explicitEndpoint = sanitizedHyperVEndpoint(rawEndpoint: cfg.apiEndpointUrl, connectionHost: bareHost)
        let permissiveTlsSession = ConfigurableTlsUrlSession.session(verifyTls: false)
        var transportErrors: [String] = []

        if !explicitEndpoint.isEmpty, let comps = URLComponents(string: explicitEndpoint), let scheme = comps.scheme?.lowercased() {
            let endpointHost = comps.host ?? bareHost
            let endpointPath = comps.path.isEmpty ? "/wsman" : comps.path
            if scheme == "http" {
                let port = UInt16(clamping: comps.port ?? 5985)
                return try await executeHyperVWsmanStateChangeHttp(
                    host: endpointHost,
                    port: port,
                    path: endpointPath,
                    identity: identity,
                    vmId: vmId,
                    vmName: vmName,
                    action: action
                )
            } else if scheme == "https" {
                let port = comps.port ?? 5986
                return try await executeHyperVWsmanStateChangeHttps(
                    baseEndpoint: "https://\(endpointHost):\(port)\(endpointPath)",
                    spnHost: endpointHost,
                    identity: identity,
                    vmId: vmId,
                    vmName: vmName,
                    action: action,
                    urlSession: cfg.verifyTls ? ConfigurableTlsUrlSession.session(verifyTls: true) : permissiveTlsSession
                )
            }
        }

        if !identity.username.isEmpty {
            do {
                return try await executeHyperVWsmanStateChangeHttp(
                    host: bareHost,
                    port: 5985,
                    path: "/wsman",
                    identity: identity,
                    vmId: vmId,
                    vmName: vmName,
                    action: action
                )
            } catch {
                transportErrors.append("WinRM :5985 — \(error.localizedDescription)")
            }

            do {
                return try await executeHyperVWsmanStateChangeHttps(
                    baseEndpoint: "https://\(bareHost):5986/wsman",
                    spnHost: bareHost,
                    identity: identity,
                    vmId: vmId,
                    vmName: vmName,
                    action: action,
                    urlSession: permissiveTlsSession
                )
            } catch {
                transportErrors.append("WinRM HTTPS :5986 — \(error.localizedDescription)")
            }
        }

        if let cred = credential {
            do {
                return try await executeHyperVSshPowerAction(
                    host: bareHost,
                    credential: cred,
                    identity: identity,
                    vmId: vmId,
                    vmName: vmName,
                    action: action
                )
            } catch {
                transportErrors.append("OpenSSH :22 — \(error.localizedDescription)")
            }
        }

        throw NativeBridgeError.networkUtilityError(
            "Hyper-V \(action.displayTitle) failed across all transports (\(transportErrors.joined(separator: " | ")))."
        )
    }

    private static func fetchHyperVMultiTransportInventory(
        connection: VaultConnection,
        cfg: CloudVirtualizationProtocolSettings,
        credential: VaultCredential?
    ) async throws -> [CloudVmInventoryItem] {
        let bareHost = extractBareHostname(connection.host)
        guard !bareHost.isEmpty else {
            throw NativeBridgeError.networkUtilityError("Hyper-V host address is empty. Edit the connection and enter your Hyper-V Server IP or FQDN.")
        }

        let identity = splitWindowsIdentity(credential: credential, fallbackRealmOrDomain: cfg.realmOrDomain)
        let hasSshKey = !(credential?.privateKeyPem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
        if identity.username.isEmpty && !hasSshKey {
            throw NativeBridgeError.authenticationFailed(
                "Hyper-V authentication requires a Windows / Hyper-V Administrator credential. Edit this connection (or parent folder) to assign a Vault Credential, or connect directly to a VM by GUID on TCP 2179 above."
            )
        }

        let explicitEndpoint = sanitizedHyperVEndpoint(rawEndpoint: cfg.apiEndpointUrl, connectionHost: bareHost)
        // Hyper-V WinRM :5986 almost universally uses machine self-signed certs unless AD CS templates are deployed
        let httpsSession = ConfigurableTlsUrlSession.session(verifyTls: false)

        if !explicitEndpoint.isEmpty, let comps = URLComponents(string: explicitEndpoint), let scheme = comps.scheme?.lowercased() {
            let endpointHost = comps.host ?? bareHost
            let endpointPath = comps.path.isEmpty ? "/wsman" : comps.path
            if scheme == "http" {
                let port = UInt16(clamping: comps.port ?? 5985)
                return try await fetchHyperVWinRmHttpInventory(
                    host: endpointHost,
                    port: port,
                    path: endpointPath,
                    hostLabel: bareHost,
                    identity: identity
                )
            } else if scheme == "https" {
                let port = comps.port ?? 5986
                return try await fetchHyperVWinRmHttpsInventory(
                    wsmanEndpoint: "https://\(endpointHost):\(port)\(endpointPath)",
                    spnHost: endpointHost,
                    hostLabel: bareHost,
                    identity: identity,
                    urlSession: httpsSession
                )
            }
        }

        var transportErrors: [String] = []

        // Tier 1: Stock Windows Server WinRM HTTP (:5985/wsman) with NTLMv2 + [MS-WSMV] SPNEGO RC4 Sealing
        if !identity.username.isEmpty {
            do {
                return try await fetchHyperVWinRmHttpInventory(
                    host: bareHost,
                    port: 5985,
                    path: "/wsman",
                    hostLabel: bareHost,
                    identity: identity
                )
            } catch {
                transportErrors.append("WinRM :5985 (\(error.localizedDescription))")
            }

            // Tier 2: WinRM HTTPS (:5986/wsman) with 2-step NTLMv2 + Permissive TLS + Basic fallback
            do {
                return try await fetchHyperVWinRmHttpsInventory(
                    wsmanEndpoint: "https://\(bareHost):5986/wsman",
                    spnHost: bareHost,
                    hostLabel: bareHost,
                    identity: identity,
                    urlSession: httpsSession
                )
            } catch {
                transportErrors.append("WinRM :5986 (\(error.localizedDescription))")
            }
        }

        // Tier 3: Windows OpenSSH Server (:22) PowerShell Get-VM fallback
        if let cred = credential {
            do {
                return try await fetchHyperVSshInventory(
                    host: bareHost,
                    hostLabel: bareHost,
                    credential: cred,
                    identity: identity
                )
            } catch {
                transportErrors.append("SSH :22 (\(error.localizedDescription))")
            }
        }

        throw NativeBridgeError.networkUtilityError(
            "Unable to query Hyper-V host \(bareHost). Enable WinRM (`Enable-PSRemoting -Force`) on TCP 5985/5986 or OpenSSH on TCP 22, or connect directly by VM GUID on TCP 2179. Details: \(transportErrors.joined(separator: " • "))"
        )
    }

    private static func buildHyperVEnumerateEnvelope(toUrl: String) -> String {
        let msgId = UUID().uuidString.lowercased()
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                    xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                    xmlns:w="http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd"
                    xmlns:n="http://schemas.xmlsoap.org/ws/2004/09/enumeration">
          <s:Header>
            <a:To>\(xmlEscape(toUrl))</a:To>
            <w:ResourceURI s:mustUnderstand="true">http://schemas.microsoft.com/wbem/wsman/1/wmi/root/virtualization/v2/Msvm_ComputerSystem</w:ResourceURI>
            <a:ReplyTo><a:Address s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address></a:ReplyTo>
            <a:Action s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/09/enumeration/Enumerate</a:Action>
            <w:MaxEnvelopeSize s:mustUnderstand="true">512000</w:MaxEnvelopeSize>
            <a:MessageID>uuid:\(msgId)</a:MessageID>
            <w:OperationTimeout>PT15.000S</w:OperationTimeout>
          </s:Header>
          <s:Body>
            <n:Enumerate>
              <w:OptimizeEnumeration/>
              <w:MaxElements>200</w:MaxElements>
            </n:Enumerate>
          </s:Body>
        </s:Envelope>
        """
    }

    private static func buildHyperVPullEnvelope(toUrl: String, enumerationContext: String) -> String {
        let msgId = UUID().uuidString.lowercased()
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                    xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                    xmlns:w="http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd"
                    xmlns:n="http://schemas.xmlsoap.org/ws/2004/09/enumeration">
          <s:Header>
            <a:To>\(xmlEscape(toUrl))</a:To>
            <w:ResourceURI s:mustUnderstand="true">http://schemas.microsoft.com/wbem/wsman/1/wmi/root/virtualization/v2/Msvm_ComputerSystem</w:ResourceURI>
            <a:ReplyTo><a:Address s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address></a:ReplyTo>
            <a:Action s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/09/enumeration/Pull</a:Action>
            <w:MaxEnvelopeSize s:mustUnderstand="true">512000</w:MaxEnvelopeSize>
            <a:MessageID>uuid:\(msgId)</a:MessageID>
            <w:OperationTimeout>PT15.000S</w:OperationTimeout>
          </s:Header>
          <s:Body>
            <n:Pull>
              <n:EnumerationContext>\(xmlEscape(enumerationContext))</n:EnumerationContext>
              <n:MaxElements>200</n:MaxElements>
            </n:Pull>
          </s:Body>
        </s:Envelope>
        """
    }

    private static func buildHyperVStateChangeEnvelope(toUrl: String, vmId: String, requestedState: Int) -> String {
        let msgId = UUID().uuidString.lowercased()
        let resourceUri = "http://schemas.microsoft.com/wbem/wsman/1/wmi/root/virtualization/v2/Msvm_ComputerSystem"
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                    xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                    xmlns:w="http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd"
                    xmlns:p="\(resourceUri)">
          <s:Header>
            <a:To>\(xmlEscape(toUrl))</a:To>
            <w:ResourceURI s:mustUnderstand="true">\(resourceUri)</w:ResourceURI>
            <a:ReplyTo><a:Address s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address></a:ReplyTo>
            <a:Action s:mustUnderstand="true">\(resourceUri)/RequestStateChange</a:Action>
            <w:MaxEnvelopeSize s:mustUnderstand="true">512000</w:MaxEnvelopeSize>
            <a:MessageID>uuid:\(msgId)</a:MessageID>
            <w:SelectorSet>
              <w:Selector Name="CreationClassName">Msvm_ComputerSystem</w:Selector>
              <w:Selector Name="Name">\(xmlEscape(vmId))</w:Selector>
            </w:SelectorSet>
            <w:OperationTimeout>PT15.000S</w:OperationTimeout>
          </s:Header>
          <s:Body>
            <p:RequestStateChange_INPUT>
              <p:RequestedState>\(requestedState)</p:RequestedState>
            </p:RequestStateChange_INPUT>
          </s:Body>
        </s:Envelope>
        """
    }

    private static func xmlEscape(_ raw: String) -> String {
        raw.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }

    private static func fetchHyperVWinRmHttpInventory(
        host: String,
        port: UInt16,
        path: String,
        hostLabel: String,
        identity: (username: String, password: String, domain: String)
    ) async throws -> [CloudVmInventoryItem] {
        let normalizedPath = path.hasPrefix("/") ? path : "/\(path)"
        let toUrl = "http://\(host):\(port)\(normalizedPath)"
        let enumerateSoap = buildHyperVEnumerateEnvelope(toUrl: toUrl)

        let firstXml = try await Task.detached(priority: .userInitiated) {
            try NativeNetworkDiagnostics.winrmWsmanPost(
                host: host,
                port: port,
                path: normalizedPath,
                username: identity.username,
                password: identity.password,
                domain: identity.domain,
                soapXml: enumerateSoap,
                timeoutMs: 10_000
            )
        }.value

        if let fault = extractWsmanFaultSummary(firstXml) {
            throw NativeBridgeError.networkUtilityError("WinRM SOAP Fault: \(fault)")
        }
        var items = parseHyperVWsmanXml(firstXml, hostLabel: hostLabel)
        if items.isEmpty,
           !firstXml.contains("EndOfSequence"),
           let enumCtx = extractWsmanEnumerationContext(firstXml),
           !enumCtx.isEmpty {
            let pullSoap = buildHyperVPullEnvelope(toUrl: toUrl, enumerationContext: enumCtx)
            let pullXml = try await Task.detached(priority: .userInitiated) {
                try NativeNetworkDiagnostics.winrmWsmanPost(
                    host: host,
                    port: port,
                    path: normalizedPath,
                    username: identity.username,
                    password: identity.password,
                    domain: identity.domain,
                    soapXml: pullSoap,
                    timeoutMs: 10_000
                )
            }.value
            if let fault = extractWsmanFaultSummary(pullXml) {
                throw NativeBridgeError.networkUtilityError("WinRM Pull Fault: \(fault)")
            }
            items = parseHyperVWsmanXml(pullXml, hostLabel: hostLabel)
        }
        return items
    }

    private static func fetchHyperVWinRmHttpsInventory(
        wsmanEndpoint: String,
        spnHost: String,
        hostLabel: String,
        identity: (username: String, password: String, domain: String),
        urlSession: URLSession
    ) async throws -> [CloudVmInventoryItem] {
        let wsmanUrlStr = wsmanEndpoint.lowercased().contains("/wsman")
            ? wsmanEndpoint
            : "\(wsmanEndpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/wsman"
        let enumerateSoap = buildHyperVEnumerateEnvelope(toUrl: wsmanUrlStr)
        let firstXml = try await postWsmanHttpsWithNtlmOrBasic(
            wsmanUrlStr: wsmanUrlStr,
            spnHost: spnHost,
            soapXml: enumerateSoap,
            identity: identity,
            urlSession: urlSession
        )
        if let fault = extractWsmanFaultSummary(firstXml) {
            throw NativeBridgeError.networkUtilityError("WinRM HTTPS Fault: \(fault)")
        }
        var items = parseHyperVWsmanXml(firstXml, hostLabel: hostLabel)
        if items.isEmpty,
           !firstXml.contains("EndOfSequence"),
           let enumCtx = extractWsmanEnumerationContext(firstXml),
           !enumCtx.isEmpty {
            let pullSoap = buildHyperVPullEnvelope(toUrl: wsmanUrlStr, enumerationContext: enumCtx)
            let pullXml = try await postWsmanHttpsWithNtlmOrBasic(
                wsmanUrlStr: wsmanUrlStr,
                spnHost: spnHost,
                soapXml: pullSoap,
                identity: identity,
                urlSession: urlSession
            )
            items = parseHyperVWsmanXml(pullXml, hostLabel: hostLabel)
        }
        return items
    }

    private static func postWsmanHttpsWithNtlmOrBasic(
        wsmanUrlStr: String,
        spnHost: String,
        soapXml: String,
        identity: (username: String, password: String, domain: String),
        urlSession: URLSession
    ) async throws -> String {
        guard let wsmanUrl = URL(string: wsmanUrlStr) else {
            throw NativeBridgeError.networkUtilityError("Invalid WinRM HTTPS URL: \(wsmanUrlStr)")
        }
        let bodyData = Data(soapXml.utf8)

        // Step 1: Attempt NTLMv2 over TLS (`Negotiate` / `NTLM`)
        if !identity.username.isEmpty {
            let type1 = NativeNetworkDiagnostics.buildNtlmNegotiate()
            if !type1.isEmpty {
                var negReq = URLRequest(url: wsmanUrl)
                negReq.httpMethod = "POST"
                negReq.setValue("application/soap+xml;charset=UTF-8", forHTTPHeaderField: "Content-Type")
                negReq.setValue("Keep-Alive", forHTTPHeaderField: "Connection")
                negReq.setValue("Negotiate \(type1)", forHTTPHeaderField: "Authorization")
                negReq.httpBody = Data()

                let (step1Data, step1Resp) = try await urlSession.data(for: negReq)
                if let http1 = step1Resp as? HTTPURLResponse {
                    if (200..<300).contains(http1.statusCode) && !step1Data.isEmpty {
                        return String(data: step1Data, encoding: .utf8) ?? ""
                    }
                    if http1.statusCode == 401,
                       let wwwAuth = http1.value(forHTTPHeaderField: "WWW-Authenticate"),
                       let (scheme, challengeB64) = extractNtlmChallengeToken(from: wwwAuth) {
                        let type3 = try NativeNetworkDiagnostics.buildNtlmAuthenticate(
                            challengeB64: challengeB64,
                            username: identity.username,
                            password: identity.password,
                            domain: identity.domain,
                            spnHost: spnHost
                        )
                        var authReq = URLRequest(url: wsmanUrl)
                        authReq.httpMethod = "POST"
                        authReq.setValue("application/soap+xml;charset=UTF-8", forHTTPHeaderField: "Content-Type")
                        authReq.setValue("Keep-Alive", forHTTPHeaderField: "Connection")
                        authReq.setValue("\(scheme) \(type3)", forHTTPHeaderField: "Authorization")
                        authReq.httpBody = bodyData

                        let (step2Data, step2Resp) = try await urlSession.data(for: authReq)
                        if let http2 = step2Resp as? HTTPURLResponse {
                            if (200..<300).contains(http2.statusCode) {
                                return String(data: step2Data, encoding: .utf8) ?? ""
                            }
                            if http2.statusCode == 401 {
                                throw NativeBridgeError.authenticationFailed("Hyper-V WinRM HTTPS NTLMv2 authentication rejected (HTTP 401).")
                            }
                        }
                    }
                }
            }
        }

        // Step 2: Basic Auth over TLS fallback
        var basicReq = URLRequest(url: wsmanUrl)
        basicReq.httpMethod = "POST"
        basicReq.setValue("application/soap+xml;charset=UTF-8", forHTTPHeaderField: "Content-Type")
        if !identity.username.isEmpty {
            let userPrincipal = identity.domain.isEmpty ? identity.username : "\(identity.domain)\\\(identity.username)"
            let basic = Data("\(userPrincipal):\(identity.password)".utf8).base64EncodedString()
            basicReq.setValue("Basic \(basic)", forHTTPHeaderField: "Authorization")
        }
        basicReq.httpBody = bodyData

        let (xmlData, resp) = try await urlSession.data(for: basicReq)
        if let http = resp as? HTTPURLResponse {
            if http.statusCode == 401 {
                throw NativeBridgeError.authenticationFailed("Hyper-V WinRM HTTPS authentication rejected (HTTP 401).")
            }
            if http.statusCode >= 400 {
                throw NativeBridgeError.networkUtilityError("Hyper-V WinRM HTTPS returned HTTP \(http.statusCode).")
            }
        }
        return String(data: xmlData, encoding: .utf8) ?? ""
    }

    private static func extractNtlmChallengeToken(from headerValue: String) -> (scheme: String, token: String)? {
        for part in headerValue.split(separator: ",") {
            let trimmed = part.trimmingCharacters(in: .whitespacesAndNewlines)
            for prefix in ["Negotiate ", "NTLM "] {
                if trimmed.lowercased().hasPrefix(prefix.lowercased()) {
                    let tok = String(trimmed.dropFirst(prefix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
                    if !tok.isEmpty, !tok.contains(" ") {
                        let schemeName = prefix.trimmingCharacters(in: .whitespaces)
                        return (schemeName, tok)
                    }
                }
            }
        }
        return nil
    }

    private static func executeHyperVWsmanStateChangeHttp(
        host: String,
        port: UInt16,
        path: String,
        identity: (username: String, password: String, domain: String),
        vmId: String,
        vmName: String,
        action: HyperVPowerAction
    ) async throws -> String {
        let normalizedPath = path.hasPrefix("/") ? path : "/\(path)"
        let toUrl = "http://\(host):\(port)\(normalizedPath)"
        let soap = buildHyperVStateChangeEnvelope(toUrl: toUrl, vmId: vmId, requestedState: action.requestedWmiState)
        let respXml = try await Task.detached(priority: .userInitiated) {
            try NativeNetworkDiagnostics.winrmWsmanPost(
                host: host,
                port: port,
                path: normalizedPath,
                username: identity.username,
                password: identity.password,
                domain: identity.domain,
                soapXml: soap,
                timeoutMs: 12_000
            )
        }.value
        try validateHyperVStateChangeResponse(respXml, vmName: vmName, action: action)
        return "\(action.displayTitle) dispatched for \(vmName)."
    }

    private static func executeHyperVWsmanStateChangeHttps(
        baseEndpoint: String,
        spnHost: String,
        identity: (username: String, password: String, domain: String),
        vmId: String,
        vmName: String,
        action: HyperVPowerAction,
        urlSession: URLSession
    ) async throws -> String {
        let soap = buildHyperVStateChangeEnvelope(toUrl: baseEndpoint, vmId: vmId, requestedState: action.requestedWmiState)
        let respXml = try await postWsmanHttpsWithNtlmOrBasic(
            wsmanUrlStr: baseEndpoint,
            spnHost: spnHost,
            soapXml: soap,
            identity: identity,
            urlSession: urlSession
        )
        try validateHyperVStateChangeResponse(respXml, vmName: vmName, action: action)
        return "\(action.displayTitle) dispatched for \(vmName)."
    }

    private static func validateHyperVStateChangeResponse(
        _ xml: String,
        vmName: String,
        action: HyperVPowerAction
    ) throws {
        if let fault = extractWsmanFaultSummary(xml) {
            throw NativeBridgeError.networkUtilityError("Hyper-V SOAP Fault: \(fault)")
        }
        if let retCode = extractWmiReturnValue(xml), retCode != 0 && retCode != 4096 {
            let detail: String
            switch retCode {
            case 32769: detail = "Access denied (32769)"
            case 32775: detail = "VM is not in a valid state for \(action.displayTitle) (32775)"
            default: detail = "WMI return code \(retCode)"
            }
            throw NativeBridgeError.networkUtilityError("Hyper-V \(action.displayTitle) on \(vmName) failed: \(detail)")
        }
    }

    private static func fetchHyperVSshInventory(
        host: String,
        hostLabel: String,
        credential: VaultCredential,
        identity: (username: String, password: String, domain: String)
    ) async throws -> [CloudVmInventoryItem] {
        let psScript = "powershell.exe -NoProfile -NonInteractive -Command \"$ErrorActionPreference='Stop'; Get-VM | ForEach-Object { [PSCustomObject]@{ Id = $_.Id.Guid.ToString(); Name = $_.Name; State = $_.State.ToString(); CpuCount = [int]$_.ProcessorCount; MemAssignedMB = [int]($_.MemoryAssigned / 1MB); MemStartupMB = [int]($_.MemoryStartup / 1MB) } } | ConvertTo-Json -Compress\""
        let rawOut = try await runWindowsSshPowerShell(
            host: host,
            credential: credential,
            identity: identity,
            command: psScript
        )
        return try parseHyperVPowerShellJson(rawOut, hostLabel: hostLabel)
    }

    private static func executeHyperVSshPowerAction(
        host: String,
        credential: VaultCredential,
        identity: (username: String, password: String, domain: String),
        vmId: String,
        vmName: String,
        action: HyperVPowerAction
    ) async throws -> String {
        let cleanGuid = vmId.filter { $0.isHexDigit || $0 == "-" }
        let safeName = vmName.replacingOccurrences(of: "'", with: "''")
        let selector = cleanGuid.count >= 32 ? "Get-VM -Id '\(cleanGuid)'" : "Get-VM -Name '\(safeName)'"
        let verb: String = {
            switch action {
            case .start: return "Start-VM -ErrorAction Stop"
            case .stop: return "Stop-VM -TurnOff -Force -ErrorAction Stop"
            case .save: return "Save-VM -ErrorAction Stop"
            case .reboot: return "Restart-VM -Force -ErrorAction Stop"
            }
        }()
        let cmd = "powershell.exe -NoProfile -NonInteractive -Command \"\(selector) | \(verb)\""
        _ = try await runWindowsSshPowerShell(host: host, credential: credential, identity: identity, command: cmd)
        return "\(action.displayTitle) executed via SSH PowerShell for \(vmName)."
    }

    private static func runWindowsSshPowerShell(
        host: String,
        credential: VaultCredential,
        identity: (username: String, password: String, domain: String),
        command: String
    ) async throws -> String {
        let pem = credential.privateKeyPem.trimmingCharacters(in: .whitespacesAndNewlines)
        let auth: SshFfiConnectPayload.Auth = !pem.isEmpty
            ? .privateKeyPem(
                pem: pem,
                passphrase: credential.keyPassphrase.isEmpty ? nil : credential.keyPassphrase
            )
            : .password(identity.password)

        let candidateUsers: [String] = {
            let baseUser = identity.username.isEmpty ? "Administrator" : identity.username
            if !identity.domain.isEmpty && !baseUser.contains("\\") && !baseUser.contains("@") {
                return ["\(identity.domain)\\\(baseUser)", baseUser]
            }
            return [baseUser]
        }()

        return try await Task.detached(priority: .userInitiated) {
            var lastErr: Error?
            for sshUser in candidateUsers {
                let spec = SshFfiConnectPayload(
                    host: host,
                    port: 22,
                    username: sshUser,
                    auth: auth
                )
                do {
                    return try NativeSshSession.execCommand(spec: spec, command: command, timeoutSecs: 15)
                } catch {
                    lastErr = error
                }
            }
            throw lastErr ?? NativeBridgeError.sshTransportError("Windows OpenSSH command failed")
        }.value
    }

    public static func parseHyperVPowerShellJson(_ raw: String, hostLabel: String) throws -> [CloudVmInventoryItem] {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "null" {
            return []
        }
        struct PsVmRow: Decodable {
            let Id: String?
            let Name: String?
            let State: String?
            let CpuCount: Int?
            let MemAssignedMB: Int?
            let MemStartupMB: Int?
        }

        let jsonSlice: Substring = {
            if let arrStart = trimmed.firstIndex(of: "["), let arrEnd = trimmed.lastIndex(of: "]"), arrStart < arrEnd {
                if let objStart = trimmed.firstIndex(of: "{"), objStart < arrStart {
                    // Check whether array starts first
                } else {
                    return trimmed[arrStart...arrEnd]
                }
            }
            if let objStart = trimmed.firstIndex(of: "{"), let objEnd = trimmed.lastIndex(of: "}"), objStart < objEnd {
                return trimmed[objStart...objEnd]
            }
            return trimmed[...]
        }()

        let data = Data(String(jsonSlice).utf8)
        let rows: [PsVmRow]
        if jsonSlice.first == "[" {
            rows = try JSONDecoder().decode([PsVmRow].self, from: data)
        } else if jsonSlice.first == "{" {
            rows = [try JSONDecoder().decode(PsVmRow.self, from: data)]
        } else {
            throw NativeBridgeError.networkUtilityError("Unexpected PowerShell output: \(trimmed.prefix(180))")
        }

        return rows.map { row in
            let vmId = (row.Id ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let rawState = (row.State ?? "Unknown").uppercased()
            let normalizedState: String = {
                if rawState.contains("RUN") { return "RUNNING" }
                if rawState.contains("OFF") || rawState.contains("STOP") { return "STOPPED" }
                if rawState.contains("SAV") { return "SAVED" }
                if rawState.contains("PAUS") { return "PAUSED" }
                return rawState
            }()
            let cpus = max(1, row.CpuCount ?? 2)
            let memMb = (row.MemAssignedMB ?? 0) > 0 ? (row.MemAssignedMB ?? 0) : (row.MemStartupMB ?? 0)
            let memLabel = memMb >= 1024
                ? String(format: "%.1f GiB", Double(memMb) / 1024.0)
                : "\(max(0, memMb)) MiB"
            return CloudVmInventoryItem(
                id: vmId.isEmpty ? UUID().uuidString.lowercased() : vmId,
                name: row.Name ?? (vmId.isEmpty ? "Hyper-V VM" : vmId),
                powerState: normalizedState,
                addressOrNode: hostLabel,
                cpuSummary: "\(cpus) vCPU",
                memorySummary: memMb > 0 ? memLabel : "Dynamic RAM",
                consoleProtocol: .RDP
            )
        }
    }

    private static func extractWsmanEnumerationContext(_ xml: String) -> String? {
        guard let range = xml.range(of: "EnumerationContext>", options: .caseInsensitive) else { return nil }
        let after = xml[range.upperBound...]
        guard let closeTag = after.range(of: "</", options: .caseInsensitive) else { return nil }
        let val = after[..<closeTag.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines)
        return val.isEmpty ? nil : val
    }

    private static func extractWsmanFaultSummary(_ xml: String) -> String? {
        guard xml.range(of: ":Fault>", options: .caseInsensitive) != nil
                || xml.range(of: "<Fault>", options: .caseInsensitive) != nil else {
            return nil
        }
        for tag in ["Text>", "Message>", "FaultString>"] {
            if let open = xml.range(of: tag, options: .caseInsensitive) {
                let tail = xml[open.upperBound...]
                if let close = tail.range(of: "</", options: .caseInsensitive) {
                    let msg = tail[..<close.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines)
                    if !msg.isEmpty { return String(msg.prefix(240)) }
                }
            }
        }
        return "WS-Management Fault returned by Hyper-V host"
    }

    private static func extractWmiReturnValue(_ xml: String) -> Int? {
        guard let open = xml.range(of: "ReturnValue>", options: .caseInsensitive) else { return nil }
        let tail = xml[open.upperBound...]
        guard let close = tail.range(of: "</", options: .caseInsensitive) else { return nil }
        return Int(tail[..<close.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines))
    }

    public static func parseHyperVWsmanXml(_ xml: String, hostLabel: String) -> [CloudVmInventoryItem] {
        final class HyperVXmlDelegate: NSObject, XMLParserDelegate {
            let hostLabel: String
            var items: [CloudVmInventoryItem] = []
            private var insideComputerSystem = false
            private var currentText = ""
            private var currentId = ""
            private var currentElementName = ""
            private var currentCaption = ""
            private var currentEnabledState = ""

            init(hostLabel: String) {
                self.hostLabel = hostLabel
            }

            private func localName(_ raw: String) -> String {
                raw.split(separator: ":").last.map(String.init) ?? raw
            }

            func parser(
                _ parser: XMLParser,
                didStartElement elementName: String,
                namespaceURI: String?,
                qualifiedName qName: String?,
                attributes attributeDict: [String: String] = [:]
            ) {
                let local = localName(qName ?? elementName)
                currentText = ""
                if local == "Msvm_ComputerSystem" {
                    insideComputerSystem = true
                    currentId = ""
                    currentElementName = ""
                    currentCaption = ""
                    currentEnabledState = ""
                }
            }

            func parser(_ parser: XMLParser, foundCharacters string: String) {
                currentText += string
            }

            func parser(
                _ parser: XMLParser,
                didEndElement elementName: String,
                namespaceURI: String?,
                qualifiedName qName: String?
            ) {
                guard insideComputerSystem else { return }
                let local = localName(qName ?? elementName)
                let val = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
                switch local {
                case "Name":
                    if currentId.isEmpty { currentId = val }
                case "ElementName":
                    currentElementName = val
                case "Caption":
                    currentCaption = val
                case "EnabledState":
                    currentEnabledState = val
                case "Msvm_ComputerSystem":
                    insideComputerSystem = false
                    // Skip the Hyper-V host computer entry itself ("Hosting Computer System")
                    if currentCaption.lowercased().contains("hosting computer system") {
                        return
                    }
                    let state: String = {
                        switch currentEnabledState {
                        case "2": return "RUNNING"
                        case "3": return "STOPPED"
                        case "32768": return "PAUSED"
                        case "32769": return "SAVED"
                        default: return currentEnabledState.isEmpty ? "UNKNOWN" : "STATE_\(currentEnabledState)"
                        }
                    }()
                    let resolvedId = currentId.isEmpty ? UUID().uuidString : currentId
                    let resolvedName = currentElementName.isEmpty ? resolvedId : currentElementName
                    items.append(CloudVmInventoryItem(
                        id: resolvedId,
                        name: resolvedName,
                        powerState: state,
                        addressOrNode: hostLabel,
                        cpuSummary: "VMConnect 2179",
                        memorySummary: resolvedId,
                        consoleProtocol: .RDP
                    ))
                default:
                    break
                }
            }
        }

        guard let data = xml.data(using: .utf8), !data.isEmpty else { return [] }
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = false
        let delegate = HyperVXmlDelegate(hostLabel: hostLabel)
        parser.delegate = delegate
        _ = parser.parse()
        return delegate.items
    }

    /// Signs and dispatches an AWS EC2 `DescribeInstances` query using full AWS Signature v4 (`AWS4-HMAC-SHA256`),
    /// supporting temporary IAM STS `SessionToken` (`X-Amz-Security-Token`), custom VPC/loopback endpoints, and TLS toggles.
    public static func describeEc2InstancesSigV4(
        region: String,
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String? = nil,
        endpointOverride: String = "",
        verifyTls: Bool = true
    ) async throws -> [CloudVmInventoryItem] {
        guard !accessKeyId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !secretAccessKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NativeBridgeError.authenticationFailed(
                "AWS EC2 requires an IAM Access Key ID (Username) and Secret Access Key (Password)."
            )
        }
        let xml = try await executeEc2SignedQuery(
            region: region,
            accessKeyId: accessKeyId,
            secretAccessKey: secretAccessKey,
            sessionToken: sessionToken,
            formBody: "Action=DescribeInstances&Version=2016-11-15",
            endpointOverride: endpointOverride,
            verifyTls: verifyTls
        )
        return parseEc2InstancesXml(xml)
    }

    public static func executeEc2SignedQuery(
        region: String,
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String? = nil,
        formBody: String,
        endpointOverride: String = "",
        verifyTls: Bool = true
    ) async throws -> String {
        let cleanAccessKey = accessKeyId.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanSecretKey = secretAccessKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanAccessKey.isEmpty, !cleanSecretKey.isEmpty else {
            throw NativeBridgeError.authenticationFailed(
                "AWS EC2 requires an IAM Access Key ID (Username) and Secret Access Key (Password)."
            )
        }
        let cleanRegion = region.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "us-east-1" : region.trimmingCharacters(in: .whitespacesAndNewlines)

        let endpoint: URL = {
            let trimmedEp = endpointOverride.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmedEp.isEmpty,
               trimmedEp.lowercased() != "https://localhost:8006",
               (trimmedEp.lowercased().hasPrefix("http://") || trimmedEp.lowercased().hasPrefix("https://")),
               var comps = URLComponents(string: trimmedEp) {
                if comps.path.isEmpty { comps.path = "/" }
                if let built = comps.url { return built }
            }
            return URL(string: "https://ec2.\(cleanRegion).amazonaws.com/")!
        }()

        let hostHeader: String = {
            let h = endpoint.host ?? "ec2.\(cleanRegion).amazonaws.com"
            if let port = endpoint.port {
                let defaultPort = (endpoint.scheme?.lowercased() == "http") ? 80 : 443
                if port != defaultPort {
                    return "\(h):\(port)"
                }
            }
            return h
        }()
        let canonicalUri = endpoint.path.isEmpty ? "/" : endpoint.path
        let bodyData = Data(formBody.utf8)

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        let amzDate = formatter.string(from: Date())
        let dateStamp = String(amzDate.prefix(8))

        let cleanToken = (sessionToken ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let payloadHash = SHA256.hash(data: bodyData).map { String(format: "%02x", $0) }.joined()

        let canonicalHeaders: String
        let signedHeaders: String
        if !cleanToken.isEmpty {
            canonicalHeaders = "content-type:application/x-www-form-urlencoded; charset=utf-8\nhost:\(hostHeader)\nx-amz-date:\(amzDate)\nx-amz-security-token:\(cleanToken)\n"
            signedHeaders = "content-type;host;x-amz-date;x-amz-security-token"
        } else {
            canonicalHeaders = "content-type:application/x-www-form-urlencoded; charset=utf-8\nhost:\(hostHeader)\nx-amz-date:\(amzDate)\n"
            signedHeaders = "content-type;host;x-amz-date"
        }

        let canonicalRequest = "POST\n\(canonicalUri)\n\n\(canonicalHeaders)\n\(signedHeaders)\n\(payloadHash)"
        let canonicalHash = SHA256.hash(data: Data(canonicalRequest.utf8)).map { String(format: "%02x", $0) }.joined()

        let credentialScope = "\(dateStamp)/\(cleanRegion)/ec2/aws4_request"
        let stringToSign = "AWS4-HMAC-SHA256\n\(amzDate)\n\(credentialScope)\n\(canonicalHash)"

        func hmac(_ key: Data, _ msg: String) -> Data {
            Data(HMAC<SHA256>.authenticationCode(for: Data(msg.utf8), using: SymmetricKey(data: key)))
        }
        let kDate = hmac(Data("AWS4\(cleanSecretKey)".utf8), dateStamp)
        let kRegion = hmac(kDate, cleanRegion)
        let kService = hmac(kRegion, "ec2")
        let kSigning = hmac(kService, "aws4_request")
        let signature = hmac(kSigning, stringToSign).map { String(format: "%02x", $0) }.joined()

        let authHeader = "AWS4-HMAC-SHA256 Credential=\(cleanAccessKey)/\(credentialScope), SignedHeaders=\(signedHeaders), Signature=\(signature)"
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue(amzDate, forHTTPHeaderField: "X-Amz-Date")
        if !cleanToken.isEmpty {
            req.setValue(cleanToken, forHTTPHeaderField: "X-Amz-Security-Token")
        }
        req.setValue(authHeader, forHTTPHeaderField: "Authorization")
        req.httpBody = bodyData

        let (xmlData, resp) = try await ConfigurableTlsUrlSession.session(verifyTls: verifyTls).data(for: req)
        let xml = String(data: xmlData, encoding: .utf8) ?? ""
        if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
            let awsCode = extractXmlTagValue(xml, tag: "Code") ?? "HTTP_\(http.statusCode)"
            let awsMsg = extractXmlTagValue(xml, tag: "Message") ?? String(xml.prefix(200))
            if http.statusCode == 401 || http.statusCode == 403 || awsCode.contains("Auth") || awsCode.contains("Signature") {
                throw NativeBridgeError.authenticationFailed("AWS EC2 \(awsCode): \(awsMsg)")
            }
            throw NativeBridgeError.networkUtilityError("AWS EC2 \(awsCode) (HTTP \(http.statusCode)): \(awsMsg)")
        }
        return xml
    }

    /// Depth-aware XMLParser for AWS EC2 `DescribeInstancesResponse` preserving instance fields
    /// across nested `<tagSet><item>`, `<groupSet><item>`, and `<networkInterfaceSet><item>` elements.
    public static func parseEc2InstancesXml(_ xml: String) -> [CloudVmInventoryItem] {
        final class Ec2DescribeXmlDelegate: NSObject, XMLParserDelegate {
            var items: [CloudVmInventoryItem] = []

            private var pathStack: [String] = []
            private var instanceItemDepth: Int? = nil
            private var tagItemDepth: Int? = nil

            private var currentText: String = ""
            private var currentInstanceId: String = ""
            private var currentStateName: String = ""
            private var currentInstanceType: String = ""
            private var currentPrivateIp: String = ""
            private var currentPublicIp: String = ""
            private var currentNameTag: String = ""
            private var currentPlatform: String = ""
            private var currentPlatformDetails: String = ""

            private var currentTagKey: String = ""
            private var currentTagValue: String = ""

            private func localName(_ raw: String) -> String {
                raw.split(separator: ":").last.map(String.init) ?? raw
            }

            func parser(
                _ parser: XMLParser,
                didStartElement elementName: String,
                namespaceURI: String?,
                qualifiedName qName: String?,
                attributes attributeDict: [String: String] = [:]
            ) {
                let local = localName(qName ?? elementName)
                let parent = pathStack.last ?? ""
                pathStack.append(local)
                currentText = ""

                if instanceItemDepth == nil && local == "item" && parent == "instancesSet" {
                    instanceItemDepth = pathStack.count
                    currentInstanceId = ""
                    currentStateName = ""
                    currentInstanceType = ""
                    currentPrivateIp = ""
                    currentPublicIp = ""
                    currentNameTag = ""
                    currentPlatform = ""
                    currentPlatformDetails = ""
                } else if let instDepth = instanceItemDepth,
                          tagItemDepth == nil,
                          pathStack.count == instDepth + 2,
                          local == "item",
                          parent == "tagSet" {
                    tagItemDepth = pathStack.count
                    currentTagKey = ""
                    currentTagValue = ""
                }
            }

            func parser(_ parser: XMLParser, foundCharacters string: String) {
                currentText += string
            }

            func parser(
                _ parser: XMLParser,
                didEndElement elementName: String,
                namespaceURI: String?,
                qualifiedName qName: String?
            ) {
                let local = localName(qName ?? elementName)
                let depth = pathStack.count
                let val = currentText.trimmingCharacters(in: .whitespacesAndNewlines)

                if let tDepth = tagItemDepth {
                    if depth == tDepth + 1 {
                        if local == "key" {
                            currentTagKey = val
                        } else if local == "value" {
                            currentTagValue = val
                        }
                    } else if depth == tDepth && local == "item" {
                        if currentTagKey == "Name" && !currentTagValue.isEmpty {
                            currentNameTag = currentTagValue
                        }
                        tagItemDepth = nil
                    }
                } else if let instDepth = instanceItemDepth {
                    if depth == instDepth + 1 {
                        switch local {
                        case "instanceId":
                            currentInstanceId = val
                        case "instanceType":
                            currentInstanceType = val
                        case "privateIpAddress":
                            currentPrivateIp = val
                        case "ipAddress":
                            currentPublicIp = val
                        case "platform":
                            currentPlatform = val
                        case "platformDetails":
                            currentPlatformDetails = val
                        default:
                            break
                        }
                    } else if depth == instDepth + 2 {
                        let parent = pathStack.dropLast().last ?? ""
                        if parent == "instanceState" && local == "name" {
                            currentStateName = val
                        }
                    } else if depth == instDepth && local == "item" {
                        if !currentInstanceId.isEmpty {
                            let displayName = currentNameTag.isEmpty ? currentInstanceId : currentNameTag
                            let ip = currentPublicIp.isEmpty ? currentPrivateIp : currentPublicIp
                            let state = currentStateName.isEmpty ? "RUNNING" : currentStateName.uppercased()
                            let isWin = currentPlatform.lowercased().contains("windows")
                                || currentPlatformDetails.lowercased().contains("windows")
                            let cProto: VaultProtocolType = isWin ? .RDP : .SSH
                            items.append(CloudVmInventoryItem(
                                id: currentInstanceId,
                                name: displayName,
                                powerState: state,
                                addressOrNode: ip,
                                cpuSummary: currentInstanceType.isEmpty ? "m6i.large" : currentInstanceType,
                                memorySummary: "EC2 EBS",
                                consoleProtocol: cProto
                            ))
                        }
                        instanceItemDepth = nil
                    }
                }

                if !pathStack.isEmpty {
                    pathStack.removeLast()
                }
                currentText = ""
            }
        }

        guard let data = xml.data(using: .utf8), !data.isEmpty else { return [] }
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = false
        let delegate = Ec2DescribeXmlDelegate()
        parser.delegate = delegate
        _ = parser.parse()
        return delegate.items
    }
}

// MARK: - 3. Kubernetes Pod & Docker Container Workload Client (Async POSIX AF_UNIX + SSH + Kubeconfig + Safe Shell Escaping)

public struct ContainerWorkloadSummary: Identifiable, Equatable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let namespaceOrImage: String
    public let status: String
}

public enum ContainerWorkloadClient {
    private static let pathSegmentAllowed: CharacterSet = {
        var cs = CharacterSet.urlPathAllowed
        cs.remove(charactersIn: "/?&=#")
        return cs
    }()

    public static func encodePathSegment(_ segment: String) -> String {
        segment.addingPercentEncoding(withAllowedCharacters: pathSegmentAllowed) ?? segment
    }

    /// Strict POSIX single-quote shell escaping (`'...'`, escaping embedded `'` as `'\''`).
    public static func shellEscapeSingleQuoted(_ argument: String) -> String {
        "'" + argument.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    /// Auto-discovers active local Docker Unix sockets on macOS (Docker Desktop, OrbStack, Colima, Rancher Desktop).
    public static func resolveDockerUnixSocketPath(preferredEndpoint: String) -> String? {
        let trimmed = preferredEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            if trimmed.hasPrefix("unix://") || trimmed.hasPrefix("/") {
                let candidate = trimmed.replacingOccurrences(of: "unix://", with: "")
                if FileManager.default.fileExists(atPath: candidate) {
                    return candidate
                }
                if trimmed != "unix:///var/run/docker.sock" && trimmed != "/var/run/docker.sock" {
                    return candidate
                }
            } else {
                return nil
            }
        }
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        let candidates = [
            "/var/run/docker.sock",
            "\(home)/.docker/run/docker.sock",
            "\(home)/.orbstack/run/docker.sock",
            "\(home)/.colima/default/docker.sock",
            "\(home)/.rd/docker.sock"
        ]
        for path in candidates where FileManager.default.fileExists(atPath: path) {
            return path
        }
        return nil
    }

    /// Normalizes a Docker TCP/HTTP/HTTPS endpoint or returns `nil` if empty / Unix / SSH.
    public static func resolveDockerHttpBaseUrl(endpoint: String, remoteHost: String, verifyTls: Bool) -> String? {
        let trimmed = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.lowercased().hasPrefix("http://") || trimmed.lowercased().hasPrefix("https://") {
            return trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        }
        if trimmed.lowercased().hasPrefix("tcp://") {
            let rest = String(trimmed.dropFirst(6)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            let scheme = (verifyTls || rest.hasSuffix(":2376")) ? "https" : "http"
            return "\(scheme)://\(rest)"
        }
        if !trimmed.isEmpty && !trimmed.hasPrefix("unix://") && !trimmed.hasPrefix("/") && !trimmed.lowercased().hasPrefix("ssh://") {
            let scheme = (verifyTls || trimmed.hasSuffix(":2376")) ? "https" : "http"
            return "\(scheme)://\(trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "/")))"
        }
        let hostTrimmed = remoteHost.trimmingCharacters(in: .whitespacesAndNewlines)
        if hostTrimmed.contains(":2375") || hostTrimmed.contains(":2376") {
            let scheme = (verifyTls || hostTrimmed.hasSuffix(":2376")) ? "https" : "http"
            return "\(scheme)://\(hostTrimmed)"
        }
        return nil
    }

    private static func isRemoteDockerHost(_ host: String) -> Bool {
        let h = host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return !h.isEmpty && h != "localhost" && h != "127.0.0.1" && h != "::1" && !h.hasPrefix("/")
    }

    /// Parses `~/.kube/config` to extract a server endpoint and optional bearer token when `clusterOrSocketEndpoint` is empty.
    public static func resolveKubernetesConfig(
        settings: ContainerExecProtocolSettings,
        remoteHost: String,
        bearerToken: String
    ) -> (baseUrl: String, bearerToken: String) {
        let cleanToken: String = {
            let t = bearerToken.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.lowercased().hasPrefix("bearer ") {
                return String(t.dropFirst(7)).trimmingCharacters(in: .whitespaces)
            }
            return t
        }()

        let rawEp = settings.clusterOrSocketEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if !rawEp.isEmpty && rawEp.lowercased() != "https://kubernetes.default.svc:6443" {
            let normalizedEp = (rawEp.lowercased().hasPrefix("http://") || rawEp.lowercased().hasPrefix("https://"))
                ? rawEp
                : "https://\(rawEp)"
            return (normalizedEp, cleanToken)
        }

        let cleanHost = remoteHost.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanHost.isEmpty && cleanHost != "localhost" && !cleanHost.contains(" ") {
            if cleanHost.lowercased().hasPrefix("http://") || cleanHost.lowercased().hasPrefix("https://") {
                return (cleanHost.trimmingCharacters(in: CharacterSet(charactersIn: "/")), cleanToken)
            }
            if cleanHost.contains(".") || cleanHost.contains(":") {
                let withPort = cleanHost.contains(":") ? cleanHost : "\(cleanHost):6443"
                return ("https://\(withPort)", cleanToken)
            }
        }

        let kubeconfigPath = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".kube/config").path
        if let rawYaml = try? String(contentsOfFile: kubeconfigPath, encoding: .utf8) {
            var discoveredServer = ""
            var discoveredToken = ""
            for rawLine in rawYaml.components(separatedBy: .newlines) {
                let line = rawLine.trimmingCharacters(in: .whitespaces)
                if discoveredServer.isEmpty && line.hasPrefix("server:") {
                    let val = String(line.dropFirst(7)).trimmingCharacters(in: CharacterSet(charactersIn: " \"'"))
                    if val.hasPrefix("http://") || val.hasPrefix("https://") {
                        discoveredServer = val.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                    }
                } else if discoveredToken.isEmpty && line.hasPrefix("token:") {
                    discoveredToken = String(line.dropFirst(6)).trimmingCharacters(in: CharacterSet(charactersIn: " \"'"))
                }
            }
            if !discoveredServer.isEmpty {
                return (discoveredServer, cleanToken.isEmpty ? discoveredToken : cleanToken)
            }
        }

        return (rawEp.isEmpty ? "https://127.0.0.1:6443" : rawEp, cleanToken)
    }

    /// Builds an injection-safe `kubectl exec` or `docker exec` (local or SSH remote) command string.
    public static func buildSafeExecCommand(
        protocolType: VaultProtocolType,
        settings: ContainerExecProtocolSettings,
        remoteSshHost: String = "",
        remoteSshUser: String = ""
    ) -> String {
        let rawTokens = settings.shellCommand
            .split(whereSeparator: { $0.isWhitespace })
            .map(String.init)
        let shellTokens = (rawTokens.isEmpty ? ["/bin/sh"] : rawTokens)
            .map { shellEscapeSingleQuoted($0) }
            .joined(separator: " ")

        let target = settings.podOrContainerName.isEmpty ? "container" : settings.podOrContainerName
        if protocolType == .KUBERNETES_POD {
            var parts: [String] = ["kubectl"]
            let ctx = settings.contextName.trimmingCharacters(in: .whitespacesAndNewlines)
            if !ctx.isEmpty {
                parts.append("--context")
                parts.append(shellEscapeSingleQuoted(ctx))
            }
            let ep = settings.clusterOrSocketEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
            if !ep.isEmpty && ep.lowercased() != "https://kubernetes.default.svc:6443" {
                parts.append("--server")
                parts.append(shellEscapeSingleQuoted(ep))
                if !settings.verifyTls {
                    parts.append("--insecure-skip-tls-verify=true")
                }
            }
            let ns = settings.namespace.isEmpty ? "default" : settings.namespace
            parts.append("-n")
            parts.append(shellEscapeSingleQuoted(ns))
            parts.append("exec")
            parts.append("-it")
            parts.append(shellEscapeSingleQuoted(target))
            let sub = settings.subContainerName.trimmingCharacters(in: .whitespacesAndNewlines)
            if !sub.isEmpty {
                parts.append("-c")
                parts.append(shellEscapeSingleQuoted(sub))
            }
            parts.append("--")
            parts.append(shellTokens)
            return parts.joined(separator: " ")
        } else {
            let dockerExec = "docker exec -it \(shellEscapeSingleQuoted(target)) \(shellTokens)"
            let ep = settings.clusterOrSocketEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
            if ep.lowercased().hasPrefix("ssh://") {
                let sshDest = String(ep.dropFirst(6)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                return "ssh -t \(shellEscapeSingleQuoted(sshDest)) \(shellEscapeSingleQuoted(dockerExec))"
            }
            if resolveDockerHttpBaseUrl(endpoint: ep, remoteHost: remoteSshHost, verifyTls: settings.verifyTls) == nil,
               resolveDockerUnixSocketPath(preferredEndpoint: ep) == nil,
               isRemoteDockerHost(remoteSshHost) {
                let cleanHost = remoteSshHost.trimmingCharacters(in: .whitespacesAndNewlines)
                let cleanUser = remoteSshUser.trimmingCharacters(in: .whitespacesAndNewlines)
                let dest = cleanUser.isEmpty ? cleanHost : "\(cleanUser)@\(cleanHost)"
                return "ssh -t \(shellEscapeSingleQuoted(dest)) \(shellEscapeSingleQuoted(dockerExec))"
            }
            return dockerExec
        }
    }

    public static func listWorkloads(
        protocolType: VaultProtocolType,
        settings: ContainerExecProtocolSettings,
        bearerToken: String = "",
        remoteHost: String = "",
        sshCredential: VaultCredential? = nil
    ) async throws -> [ContainerWorkloadSummary] {
        if protocolType == .DOCKER_CONTAINER {
            let ep = settings.clusterOrSocketEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
            let useSshDocker = ep.lowercased().hasPrefix("ssh://")
                || (resolveDockerHttpBaseUrl(endpoint: ep, remoteHost: remoteHost, verifyTls: settings.verifyTls) == nil
                    && resolveDockerUnixSocketPath(preferredEndpoint: ep) == nil
                    && isRemoteDockerHost(remoteHost))

            if useSshDocker {
                return try await listDockerWorkloadsOverSsh(endpoint: ep, remoteHost: remoteHost, credential: sshCredential)
            }

            let data = try await dockerRequest(
                endpoint: ep,
                remoteHost: remoteHost,
                path: "/containers/json?all=true",
                verifyTls: settings.verifyTls
            )
            struct DockerContainer: Decodable {
                let Id: String?
                let Names: [String]?
                let Image: String?
                let State: String?
            }
            let list = try JSONDecoder().decode([DockerContainer].self, from: data)
            return list.map { c in
                let rawName = c.Names?.first?.trimmingCharacters(in: CharacterSet(charactersIn: "/")) ?? String((c.Id ?? "container").prefix(12))
                return ContainerWorkloadSummary(
                    id: String((c.Id ?? rawName).prefix(12)),
                    name: rawName,
                    namespaceOrImage: c.Image ?? "oci:latest",
                    status: (c.State ?? "running").uppercased()
                )
            }
        } else {
            let resolvedK8s = resolveKubernetesConfig(settings: settings, remoteHost: remoteHost, bearerToken: bearerToken)
            let ns = encodePathSegment(settings.namespace.isEmpty ? "default" : settings.namespace)
            guard let url = URL(string: "\(resolvedK8s.baseUrl)/api/v1/namespaces/\(ns)/pods") else {
                throw NativeBridgeError.networkUtilityError("Invalid Kubernetes API endpoint: \(resolvedK8s.baseUrl)")
            }
            var req = URLRequest(url: url)
            if !resolvedK8s.bearerToken.isEmpty {
                req.setValue("Bearer \(resolvedK8s.bearerToken)", forHTTPHeaderField: "Authorization")
            }
            let (data, resp) = try await ConfigurableTlsUrlSession.session(verifyTls: settings.verifyTls).data(for: req)
            if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
                let msg = String(data: data, encoding: .utf8) ?? ""
                if http.statusCode == 401 || http.statusCode == 403 {
                    throw NativeBridgeError.authenticationFailed("Kubernetes API rejected token (HTTP \(http.statusCode)): \(msg.prefix(180))")
                }
                throw NativeBridgeError.networkUtilityError("Kubernetes API error (HTTP \(http.statusCode)): \(msg.prefix(180))")
            }
            struct K8sPodList: Decodable {
                struct Item: Decodable {
                    struct Meta: Decodable { let name: String?; let namespace: String? }
                    struct Status: Decodable { let phase: String? }
                    let metadata: Meta?
                    let status: Status?
                }
                let items: [Item]?
            }
            let list = try JSONDecoder().decode(K8sPodList.self, from: data)
            return (list.items ?? []).map { pod in
                let name = pod.metadata?.name ?? "pod"
                return ContainerWorkloadSummary(
                    id: name,
                    name: name,
                    namespaceOrImage: pod.metadata?.namespace ?? settings.namespace,
                    status: (pod.status?.phase ?? "Running").uppercased()
                )
            }
        }
    }

    public static func fetchLogs(
        protocolType: VaultProtocolType,
        settings: ContainerExecProtocolSettings,
        bearerToken: String = "",
        remoteHost: String = "",
        sshCredential: VaultCredential? = nil
    ) async throws -> String {
        let target = settings.podOrContainerName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !target.isEmpty else { return "" }
        if protocolType == .DOCKER_CONTAINER {
            let ep = settings.clusterOrSocketEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
            let useSshDocker = ep.lowercased().hasPrefix("ssh://")
                || (resolveDockerHttpBaseUrl(endpoint: ep, remoteHost: remoteHost, verifyTls: settings.verifyTls) == nil
                    && resolveDockerUnixSocketPath(preferredEndpoint: ep) == nil
                    && isRemoteDockerHost(remoteHost))

            if useSshDocker {
                let cmd = "docker logs --tail \(max(10, settings.logTailLines)) \(shellEscapeSingleQuoted(target)) 2>&1"
                return try await runDockerSshCommand(endpoint: ep, remoteHost: remoteHost, credential: sshCredential, command: cmd)
            }

            let encodedTarget = encodePathSegment(target)
            let raw = try await dockerRequest(
                endpoint: ep,
                remoteHost: remoteHost,
                path: "/containers/\(encodedTarget)/logs?stdout=1&stderr=1&tail=\(max(10, settings.logTailLines))",
                verifyTls: settings.verifyTls
            )
            return stripDockerMultiplexHeaders(raw)
        } else {
            let resolvedK8s = resolveKubernetesConfig(settings: settings, remoteHost: remoteHost, bearerToken: bearerToken)
            let ns = encodePathSegment(settings.namespace.isEmpty ? "default" : settings.namespace)
            let pod = encodePathSegment(target)
            var comps = URLComponents(string: "\(resolvedK8s.baseUrl)/api/v1/namespaces/\(ns)/pods/\(pod)/log")
            var queryItems = [URLQueryItem(name: "tailLines", value: "\(max(10, settings.logTailLines))")]
            if !settings.subContainerName.isEmpty {
                queryItems.append(URLQueryItem(name: "container", value: settings.subContainerName))
            }
            comps?.queryItems = queryItems
            guard let url = comps?.url else { return "" }
            var req = URLRequest(url: url)
            if !resolvedK8s.bearerToken.isEmpty {
                req.setValue("Bearer \(resolvedK8s.bearerToken)", forHTTPHeaderField: "Authorization")
            }
            let (data, resp) = try await ConfigurableTlsUrlSession.session(verifyTls: settings.verifyTls).data(for: req)
            if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
                let msg = String(data: data, encoding: .utf8) ?? ""
                throw NativeBridgeError.networkUtilityError("Kubernetes pod logs failed (HTTP \(http.statusCode)): \(msg.prefix(180))")
            }
            return String(data: data, encoding: .utf8) ?? ""
        }
    }

    private static func listDockerWorkloadsOverSsh(
        endpoint: String,
        remoteHost: String,
        credential: VaultCredential?
    ) async throws -> [ContainerWorkloadSummary] {
        let cmd = "docker ps -a --format '{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.State}}'"
        let output = try await runDockerSshCommand(endpoint: endpoint, remoteHost: remoteHost, credential: credential, command: cmd)
        var results: [ContainerWorkloadSummary] = []
        for rawLine in output.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { continue }
            let cols = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            guard cols.count >= 4 else { continue }
            let id = String(cols[0].prefix(12))
            let name = cols[1].isEmpty ? id : cols[1]
            results.append(ContainerWorkloadSummary(
                id: id,
                name: name,
                namespaceOrImage: cols[2].isEmpty ? "oci:latest" : cols[2],
                status: cols[3].isEmpty ? "RUNNING" : cols[3].uppercased()
            ))
        }
        return results
    }

    private static func runDockerSshCommand(
        endpoint: String,
        remoteHost: String,
        credential: VaultCredential?,
        command: String
    ) async throws -> String {
        var sshHost = remoteHost.trimmingCharacters(in: .whitespacesAndNewlines)
        var sshPort: UInt16 = 22
        var sshUser = credential?.username.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if endpoint.lowercased().hasPrefix("ssh://"), let url = URL(string: endpoint) {
            if let h = url.host, !h.isEmpty { sshHost = h }
            if let p = url.port, p > 0 { sshPort = UInt16(clamping: p) }
            if let u = url.user, !u.isEmpty { sshUser = u }
        } else if sshHost.contains("@") {
            let parts = sshHost.split(separator: "@", maxSplits: 1).map(String.init)
            if sshUser.isEmpty { sshUser = parts[0] }
            sshHost = parts[1]
        }
        if sshUser.isEmpty { sshUser = "root" }
        let pem = (credential?.privateKeyPem ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let auth: SshFfiConnectPayload.Auth = !pem.isEmpty
            ? .privateKeyPem(pem: pem, passphrase: (credential?.keyPassphrase ?? "").isEmpty ? nil : credential?.keyPassphrase)
            : .password(credential?.secretPassword ?? "")

        let spec = SshFfiConnectPayload(host: sshHost, port: sshPort, username: sshUser, auth: auth)
        return try await Task.detached(priority: .userInitiated) {
            try NativeSshSession.execCommand(spec: spec, command: command, timeoutSecs: 15)
        }.value
    }

    public static func stripDockerMultiplexHeaders(_ data: Data) -> String {
        let bytes = Array(data)
        var i = 0
        var out = Data()
        while i + 8 <= bytes.count {
            let streamType = bytes[i]
            if streamType <= 2 && bytes[i + 1] == 0 && bytes[i + 2] == 0 && bytes[i + 3] == 0 {
                let len = (Int(bytes[i + 4]) << 24) | (Int(bytes[i + 5]) << 16) | (Int(bytes[i + 6]) << 8) | Int(bytes[i + 7])
                if len >= 0 && i + 8 + len <= bytes.count {
                    out.append(contentsOf: bytes[(i + 8)..<(i + 8 + len)])
                    i += 8 + len
                    continue
                }
            }
            // Fallback: unmultiplexed TTY stream
            return String(data: data, encoding: .utf8) ?? ""
        }
        return out.isEmpty
            ? (String(data: data, encoding: .utf8) ?? "")
            : (String(data: out, encoding: .utf8) ?? "")
    }

    private static func dockerRequest(endpoint: String, remoteHost: String, path: String, verifyTls: Bool) async throws -> Data {
        if let httpBase = resolveDockerHttpBaseUrl(endpoint: endpoint, remoteHost: remoteHost, verifyTls: verifyTls) {
            guard let url = URL(string: "\(httpBase)\(path)") else {
                throw NativeBridgeError.networkUtilityError("Invalid Docker HTTP endpoint: \(httpBase)")
            }
            let (data, resp) = try await ConfigurableTlsUrlSession.session(verifyTls: verifyTls).data(from: url)
            if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
                let msg = String(data: data, encoding: .utf8) ?? ""
                throw NativeBridgeError.networkUtilityError("Docker Engine HTTP \(http.statusCode): \(msg.prefix(180))")
            }
            return data
        }
        guard let sockPath = resolveDockerUnixSocketPath(preferredEndpoint: endpoint) else {
            throw NativeBridgeError.networkUtilityError(
                "No local Docker socket found (/var/run/docker.sock, ~/.docker/run/docker.sock, ~/.orbstack/run/docker.sock, ~/.colima/default/docker.sock). Start Docker Desktop/OrbStack or specify a remote SSH/TCP host."
            )
        }
        return try await Task.detached(priority: .userInitiated) {
            try performUnixDomainHttpGet(socketPath: sockPath, requestPath: path)
        }.value
    }

    private static func performUnixDomainHttpGet(socketPath: String, requestPath: String) throws -> Data {
        let fd = Darwin.socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else {
            throw NativeBridgeError.networkUtilityError("socket(AF_UNIX) failed: \(String(cString: strerror(errno)))")
        }
        defer { Darwin.close(fd) }

        var one: Int32 = 1
        _ = setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        var timeout = timeval(tv_sec: 5, tv_usec: 0)
        _ = setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        _ = setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let pathBytes = Array(socketPath.utf8)
        let maxPathLen = MemoryLayout.size(ofValue: addr.sun_path) - 1
        guard !pathBytes.isEmpty, pathBytes.count <= maxPathLen else {
            throw NativeBridgeError.networkUtilityError("Invalid Docker socket path: \(socketPath)")
        }
        addr.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
        withUnsafeMutablePointer(to: &addr.sun_path) { sunPathPtr in
            let dst = UnsafeMutableRawPointer(sunPathPtr).assumingMemoryBound(to: UInt8.self)
            for (idx, b) in pathBytes.enumerated() {
                dst[idx] = b
            }
            dst[pathBytes.count] = 0
        }

        let rc = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { saPtr in
                Darwin.connect(fd, saPtr, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
        guard rc == 0 else {
            throw NativeBridgeError.networkUtilityError(
                "Docker socket connect failed (\(socketPath)): \(String(cString: strerror(errno)))"
            )
        }

        let httpReq = "GET \(requestPath) HTTP/1.1\r\nHost: localhost\r\nAccept: application/json\r\nConnection: close\r\n\r\n"
        try httpReq.utf8.withContiguousStorageIfAvailable { buf -> Void in
            var sent = 0
            while sent < buf.count {
                let n = Darwin.send(fd, buf.baseAddress!.advanced(by: sent), buf.count - sent, 0)
                if n <= 0 {
                    throw NativeBridgeError.networkUtilityError("Failed writing HTTP request to Docker socket")
                }
                sent += n
            }
        } ?? {
            let reqData = Data(httpReq.utf8)
            try reqData.withUnsafeBytes { raw in
                guard let base = raw.baseAddress else { return }
                var sent = 0
                while sent < reqData.count {
                    let n = Darwin.send(fd, base.advanced(by: sent), reqData.count - sent, 0)
                    if n <= 0 {
                        throw NativeBridgeError.networkUtilityError("Failed writing HTTP request to Docker socket")
                    }
                    sent += n
                }
            }
        }()

        var rawResp = Data()
        var chunk = [UInt8](repeating: 0, count: 16_384)
        while true {
            let n = Darwin.recv(fd, &chunk, chunk.count, 0)
            if n > 0 {
                rawResp.append(contentsOf: chunk.prefix(n))
            } else {
                break
            }
        }

        let headerSep = Data([0x0D, 0x0A, 0x0D, 0x0A])
        guard let sepRange = rawResp.range(of: headerSep) else {
            return rawResp
        }
        let headerStr = String(decoding: rawResp[..<sepRange.lowerBound], as: UTF8.self)
        let bodySlice = Data(rawResp[sepRange.upperBound...])
        let decodedBody = headerStr.lowercased().contains("transfer-encoding: chunked")
            ? decodeChunkedHttpBody(bodySlice)
            : bodySlice
        if let firstLine = headerStr.components(separatedBy: "\r\n").first {
            let parts = firstLine.split(separator: " ")
            if parts.count >= 2, let code = Int(parts[1]), code >= 400 {
                let errText = String(data: decodedBody, encoding: .utf8) ?? ""
                throw NativeBridgeError.networkUtilityError("Docker socket HTTP \(code): \(errText.prefix(180))")
            }
        }
        return decodedBody
    }

    private static func decodeChunkedHttpBody(_ chunked: Data) -> Data {
        var out = Data()
        let bytes = Array(chunked)
        var pos = 0
        while pos < bytes.count {
            var lineEnd = pos
            while lineEnd + 1 < bytes.count && !(bytes[lineEnd] == 0x0D && bytes[lineEnd + 1] == 0x0A) {
                lineEnd += 1
            }
            guard lineEnd + 1 < bytes.count else { break }
            let sizeHeader = String(decoding: bytes[pos..<lineEnd], as: UTF8.self)
                .split(separator: ";").first?
                .trimmingCharacters(in: .whitespaces) ?? "0"
            guard let chunkSize = Int(sizeHeader, radix: 16), chunkSize > 0 else {
                break
            }
            let dataStart = lineEnd + 2
            guard dataStart + chunkSize <= bytes.count else {
                out.append(contentsOf: bytes[dataStart..<bytes.count])
                break
            }
            out.append(contentsOf: bytes[dataStart..<(dataStart + chunkSize)])
            pos = dataStart + chunkSize + 2
        }
        return out
    }
}

// MARK: - 4. Native macOS FTPS Client (Implicit TLS :990 & Explicit AUTH TLS :21 + PBSZ 0 + PROT P + Session Reuse)

private final class FtpsTlsSocketStream {
    private var fd: Int32
    private var fdBox: UnsafeMutablePointer<Int32>?
    private var sslContext: SSLContext?
    private var readBuffer = Data()

    init(fd: Int32) {
        self.fd = fd
    }

    deinit {
        close()
    }

    static func connectTcp(host: String, port: UInt16, timeoutSeconds: Int = 12) throws -> FtpsTlsSocketStream {
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        hints.ai_protocol = IPPROTO_TCP

        var res: UnsafeMutablePointer<addrinfo>? = nil
        let cleanHost = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        let portStr = "\(port == 0 ? 990 : port)"
        let gai = getaddrinfo(cleanHost, portStr, &hints, &res)
        guard gai == 0, let head = res else {
            throw NativeBridgeError.ftpsError("DNS resolution failed for \(host):\(portStr)")
        }
        defer { freeaddrinfo(head) }

        var lastErrno: Int32 = ECONNREFUSED
        var current: UnsafeMutablePointer<addrinfo>? = head
        while let info = current {
            let sock = Darwin.socket(info.pointee.ai_family, info.pointee.ai_socktype, info.pointee.ai_protocol)
            if sock >= 0 {
                var one: Int32 = 1
                _ = setsockopt(sock, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
                var tv = timeval(tv_sec: timeoutSeconds, tv_usec: 0)
                _ = setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
                _ = setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

                if Darwin.connect(sock, info.pointee.ai_addr, info.pointee.ai_addrlen) == 0 {
                    return FtpsTlsSocketStream(fd: sock)
                }
                lastErrno = errno
                Darwin.close(sock)
            }
            current = info.pointee.ai_next
        }
        throw NativeBridgeError.ftpsError(
            "TCP connect to \(host):\(portStr) failed: \(String(cString: strerror(lastErrno)))"
        )
    }

    private static let sslReadCallback: SSLReadFunc = { connection, data, dataLength in
        let sock = connection.assumingMemoryBound(to: Int32.self).pointee
        let requested = dataLength.pointee
        guard requested > 0 else {
            dataLength.pointee = 0
            return noErr
        }
        let dst = data.assumingMemoryBound(to: UInt8.self)
        var total = 0
        while total < requested {
            let n = Darwin.recv(sock, dst.advanced(by: total), requested - total, 0)
            if n > 0 {
                total += n
            } else if n == 0 {
                dataLength.pointee = total
                return total > 0 ? noErr : errSSLClosedNoNotify
            } else {
                let err = errno
                if err == EINTR { continue }
                dataLength.pointee = total
                if err == EAGAIN || err == EWOULDBLOCK {
                    return errSSLWouldBlock
                }
                return errSecIO
            }
        }
        dataLength.pointee = total
        return noErr
    }

    private static let sslWriteCallback: SSLWriteFunc = { connection, data, dataLength in
        let sock = connection.assumingMemoryBound(to: Int32.self).pointee
        let requested = dataLength.pointee
        guard requested > 0 else {
            dataLength.pointee = 0
            return noErr
        }
        let src = data.assumingMemoryBound(to: UInt8.self)
        var total = 0
        while total < requested {
            let n = Darwin.send(sock, src.advanced(by: total), requested - total, 0)
            if n > 0 {
                total += n
            } else if n < 0 && errno == EINTR {
                continue
            } else {
                dataLength.pointee = total
                if errno == EAGAIN || errno == EWOULDBLOCK {
                    return errSSLWouldBlock
                }
                return errSecIO
            }
        }
        dataLength.pointee = total
        return noErr
    }

    func enableTls(peerHost: String, verifyTls: Bool, sessionPeerId: String = "") throws {
        guard sslContext == nil else { return }
        guard fd >= 0 else {
            throw NativeBridgeError.ftpsError("Socket closed before TLS handshake")
        }
        guard let ctx = SSLCreateContext(nil, .clientSide, .streamType) else {
            throw NativeBridgeError.ftpsError("SSLCreateContext failed")
        }

        let box = UnsafeMutablePointer<Int32>.allocate(capacity: 1)
        box.initialize(to: fd)
        self.fdBox = box

        var status = SSLSetIOFuncs(ctx, Self.sslReadCallback, Self.sslWriteCallback)
        guard status == noErr else {
            throw NativeBridgeError.ftpsError("SSLSetIOFuncs failed (OSStatus \(status))")
        }
        status = SSLSetConnection(ctx, UnsafeRawPointer(box))
        guard status == noErr else {
            throw NativeBridgeError.ftpsError("SSLSetConnection failed (OSStatus \(status))")
        }

        let cleanHost = peerHost.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if !cleanHost.isEmpty {
            _ = cleanHost.withCString { cStr in
                SSLSetPeerDomainName(ctx, cStr, strlen(cStr))
            }
        }
        if !sessionPeerId.isEmpty {
            // Enables TLS session resumption across FTPS control and passive data channels
            // required by vsftpd (`require_ssl_reuse=YES`), ProFTPD, and FileZilla Server.
            _ = sessionPeerId.withCString { cStr in
                SSLSetPeerID(ctx, cStr, strlen(cStr))
            }
        }
        _ = SSLSetProtocolVersionMin(ctx, .tlsProtocol12)

        if !verifyTls {
            _ = SSLSetSessionOption(ctx, .breakOnServerAuth, true)
        }

        let peerAuthCompleted: OSStatus = errSSLPeerAuthCompleted // -9841
        repeat {
            status = SSLHandshake(ctx)
            if status == peerAuthCompleted && !verifyTls {
                // Continue past server trust break when certificate verification is disabled
                continue
            }
        } while status == errSSLWouldBlock || (status == peerAuthCompleted && !verifyTls)

        guard status == noErr else {
            throw NativeBridgeError.ftpsError("FTPS TLS handshake failed (OSStatus \(status))")
        }
        self.sslContext = ctx
    }

    func writeAll(_ data: Data) throws {
        guard fd >= 0 else { throw NativeBridgeError.ftpsError("FTPS socket closed") }
        guard !data.isEmpty else { return }

        if let ctx = sslContext {
            try data.withUnsafeBytes { raw in
                guard let base = raw.baseAddress else { return }
                var offset = 0
                while offset < data.count {
                    var processed = 0
                    let st = SSLWrite(ctx, base.advanced(by: offset), data.count - offset, &processed)
                    if st == noErr || (st == errSSLWouldBlock && processed > 0) {
                        offset += processed
                    } else {
                        throw NativeBridgeError.ftpsError("FTPS TLS write failed (OSStatus \(st))")
                    }
                }
            }
        } else {
            try data.withUnsafeBytes { raw in
                guard let base = raw.baseAddress else { return }
                var offset = 0
                while offset < data.count {
                    let n = Darwin.send(fd, base.advanced(by: offset), data.count - offset, 0)
                    if n > 0 {
                        offset += n
                    } else if n < 0 && errno == EINTR {
                        continue
                    } else {
                        throw NativeBridgeError.ftpsError("FTPS TCP write failed: \(String(cString: strerror(errno)))")
                    }
                }
            }
        }
    }

    func readSome(maxBytes: Int = 16_384) throws -> Data {
        guard fd >= 0 else { return Data() }
        var buf = [UInt8](repeating: 0, count: maxBytes)
        if let ctx = sslContext {
            var processed = 0
            let st = SSLRead(ctx, &buf, buf.count, &processed)
            if processed > 0 {
                return Data(buf.prefix(processed))
            }
            if st == errSSLClosedGraceful || st == errSSLClosedNoNotify || st == errSSLClosedAbort {
                return Data()
            }
            if st == noErr {
                return Data()
            }
            throw NativeBridgeError.ftpsError("FTPS TLS read failed (OSStatus \(st))")
        } else {
            while true {
                let n = Darwin.recv(fd, &buf, buf.count, 0)
                if n > 0 {
                    return Data(buf.prefix(n))
                } else if n == 0 {
                    return Data()
                } else if errno == EINTR {
                    continue
                } else {
                    throw NativeBridgeError.ftpsError("FTPS TCP read failed: \(String(cString: strerror(errno)))")
                }
            }
        }
    }

    func readAllUntilEof() throws -> Data {
        var all = readBuffer
        readBuffer.removeAll()
        while true {
            let chunk = try readSome(maxBytes: 32_768)
            if chunk.isEmpty { break }
            all.append(chunk)
        }
        return all
    }

    func readLine() throws -> String {
        while true {
            if let nlIdx = readBuffer.firstIndex(of: 0x0A) {
                let lineData = readBuffer[..<nlIdx]
                readBuffer.removeSubrange(...nlIdx)
                var line = String(decoding: lineData, as: UTF8.self)
                if line.hasSuffix("\r") {
                    line.removeLast()
                }
                return line
            }
            let chunk = try readSome(maxBytes: 4096)
            if chunk.isEmpty {
                if !readBuffer.isEmpty {
                    let tail = String(decoding: readBuffer, as: UTF8.self)
                    readBuffer.removeAll()
                    return tail
                }
                throw NativeBridgeError.ftpsError("FTPS server closed control connection unexpectedly")
            }
            readBuffer.append(chunk)
        }
    }

    func close() {
        if let ctx = sslContext {
            _ = SSLClose(ctx)
            sslContext = nil
        }
        if fd >= 0 {
            Darwin.close(fd)
            fd = -1
        }
        if let box = fdBox {
            box.deinitialize(count: 1)
            box.deallocate()
            fdBox = nil
        }
    }
}

public final class MacFtpsClient: RemoteFileTransferClient, @unchecked Sendable {
    private let host: String
    private let port: UInt16
    private let verifyTls: Bool
    private let tlsSessionPeerId: String
    private let lock = NSLock()
    private var control: FtpsTlsSocketStream?
    private var protectDataChannel: Bool = true
    private var epsvSupported: Bool = true
    private var mlsdSupported: Bool = true

    private init(
        host: String,
        port: UInt16,
        verifyTls: Bool,
        tlsSessionPeerId: String,
        control: FtpsTlsSocketStream,
        protectDataChannel: Bool
    ) {
        self.host = host
        self.port = port
        self.verifyTls = verifyTls
        self.tlsSessionPeerId = tlsSessionPeerId
        self.control = control
        self.protectDataChannel = protectDataChannel
    }

    deinit {
        disconnect()
    }

    /// Connects to an FTPS server using either Implicit TLS (`:990`) or Explicit `AUTH TLS` (`:21` + `PBSZ 0` + `PROT P`).
    public static func connect(
        host: String,
        port: UInt16,
        username: String,
        password: String,
        implicitTls: Bool,
        verifyTls: Bool = true
    ) throws -> MacFtpsClient {
        let resolvedPort: UInt16 = port == 0 ? (implicitTls ? 990 : 21) : port
        let sessionPeerId = "ftps:\(host):\(resolvedPort):\(UUID().uuidString)"
        let ctrl = try FtpsTlsSocketStream.connectTcp(host: host, port: resolvedPort)
        do {
            if implicitTls {
                try ctrl.enableTls(peerHost: host, verifyTls: verifyTls, sessionPeerId: sessionPeerId)
                _ = try readReply(from: ctrl, expectedCodes: [220])
            } else {
                _ = try readReply(from: ctrl, expectedCodes: [220])
                try sendCommand("AUTH TLS", on: ctrl)
                let authReply = try readReply(from: ctrl)
                if authReply.code != 234 {
                    try sendCommand("AUTH SSL", on: ctrl)
                    _ = try readReply(from: ctrl, expectedCodes: [234])
                }
                try ctrl.enableTls(peerHost: host, verifyTls: verifyTls, sessionPeerId: sessionPeerId)
            }

            let effectiveUser = username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "anonymous" : username
            try sendCommand("USER \(effectiveUser)", on: ctrl)
            let userReply = try readReply(from: ctrl)
            if userReply.code == 331 || userReply.code == 332 {
                try sendCommand("PASS \(password)", on: ctrl)
                _ = try readReply(from: ctrl, expectedCodes: [230, 202])
            } else if userReply.code != 230 && userReply.code != 202 {
                throw NativeBridgeError.ftpsError("FTPS USER rejected (\(userReply.code)): \(userReply.message)")
            }

            try sendCommand("PBSZ 0", on: ctrl)
            _ = try readReply(from: ctrl, expectedCodes: [200])

            try sendCommand("PROT P", on: ctrl)
            let protReply = try readReply(from: ctrl)
            let dataProtected = (protReply.code == 200)

            try sendCommand("TYPE I", on: ctrl)
            _ = try readReply(from: ctrl, expectedCodes: [200])

            try sendCommand("OPTS UTF8 ON", on: ctrl)
            _ = try? readReply(from: ctrl)

            return MacFtpsClient(
                host: host,
                port: resolvedPort,
                verifyTls: verifyTls,
                tlsSessionPeerId: sessionPeerId,
                control: ctrl,
                protectDataChannel: dataProtected
            )
        } catch {
            ctrl.close()
            throw error
        }
    }

    private static func sendCommand(_ cmd: String, on stream: FtpsTlsSocketStream) throws {
        let sanitized = cmd.replacingOccurrences(of: "\r", with: "").replacingOccurrences(of: "\n", with: "")
        try stream.writeAll(Data("\(sanitized)\r\n".utf8))
    }

    private static func readReply(
        from stream: FtpsTlsSocketStream,
        expectedCodes: [Int]? = nil
    ) throws -> (code: Int, message: String) {
        let firstLine = try stream.readLine()
        guard firstLine.count >= 3, let code = Int(firstLine.prefix(3)) else {
            throw NativeBridgeError.ftpsError("Malformed FTPS reply: \(firstLine)")
        }
        var lines = [firstLine]
        if firstLine.count >= 4 && firstLine[firstLine.index(firstLine.startIndex, offsetBy: 3)] == "-" {
            let endPrefix = String(format: "%03d ", code)
            while true {
                let nextLine = try stream.readLine()
                lines.append(nextLine)
                if nextLine.hasPrefix(endPrefix) {
                    break
                }
            }
        }
        let combined = lines.joined(separator: "\n")
        if let expected = expectedCodes, !expected.contains(code) {
            throw NativeBridgeError.ftpsError("FTPS error \(code): \(combined)")
        }
        return (code, combined)
    }

    private static func isUnroutableOrPrivateIpv4(_ ip: String) -> Bool {
        let octets = ip.split(separator: ".").compactMap { Int($0) }
        guard octets.count == 4 else { return false }
        let (a, b) = (octets[0], octets[1])
        return a == 0
            || a == 10
            || a == 127
            || (a == 172 && (16...31).contains(b))
            || (a == 192 && b == 168)
            || (a == 169 && b == 254)
            || (a == 100 && (64...127).contains(b))
    }

    private func openPassiveDataSocket(on ctrl: FtpsTlsSocketStream) throws -> FtpsTlsSocketStream {
        if epsvSupported {
            try Self.sendCommand("EPSV", on: ctrl)
            let epsv = try Self.readReply(from: ctrl)
            if epsv.code == 229, let dataPort = Self.parseEpsvPort(epsv.message) {
                return try FtpsTlsSocketStream.connectTcp(host: host, port: dataPort)
            }
            epsvSupported = false
        }

        try Self.sendCommand("PASV", on: ctrl)
        let pasv = try Self.readReply(from: ctrl, expectedCodes: [227])
        guard let (pasvHost, pasvPort) = Self.parsePasvAddress(pasv.message) else {
            throw NativeBridgeError.ftpsError("Unable to parse PASV reply: \(pasv.message)")
        }
        let substituteControlHost = pasvHost.isEmpty
            || pasvHost == "0.0.0.0"
            || (Self.isUnroutableOrPrivateIpv4(pasvHost) && !Self.isUnroutableOrPrivateIpv4(host) && host != "localhost")
        let primaryHost = substituteControlHost ? host : pasvHost
        if let connected = try? FtpsTlsSocketStream.connectTcp(host: primaryHost, port: pasvPort) {
            return connected
        }
        if primaryHost != host {
            return try FtpsTlsSocketStream.connectTcp(host: host, port: pasvPort)
        }
        return try FtpsTlsSocketStream.connectTcp(host: primaryHost, port: pasvPort)
    }

    private static func parseEpsvPort(_ reply: String) -> UInt16? {
        guard let openIdx = reply.firstIndex(of: "("),
              let closeIdx = reply.lastIndex(of: ")"),
              openIdx < closeIdx else { return nil }
        let inside = String(reply[reply.index(after: openIdx)..<closeIdx])
        guard let delim = inside.first else { return nil }
        let parts = inside.split(separator: delim, omittingEmptySubsequences: false)
        guard parts.count >= 4, let p = UInt16(parts[3]) else { return nil }
        return p
    }

    private static func parsePasvAddress(_ reply: String) -> (host: String, port: UInt16)? {
        guard let openIdx = reply.firstIndex(of: "("),
              let closeIdx = reply.lastIndex(of: ")"),
              openIdx < closeIdx else { return nil }
        let nums = reply[reply.index(after: openIdx)..<closeIdx]
            .split(separator: ",")
            .compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
        guard nums.count == 6 else { return nil }
        let ip = "\(nums[0]).\(nums[1]).\(nums[2]).\(nums[3])"
        let port = UInt16(clamping: nums[4] * 256 + nums[5])
        return (ip, port)
    }

    private func pwdUnlocked(on ctrl: FtpsTlsSocketStream) throws -> String {
        try Self.sendCommand("PWD", on: ctrl)
        let reply = try Self.readReply(from: ctrl, expectedCodes: [257])
        if let firstQuote = reply.message.firstIndex(of: "\""),
           let lastQuote = reply.message[reply.message.index(after: firstQuote)...].firstIndex(of: "\"") {
            return String(reply.message[reply.message.index(after: firstQuote)..<lastQuote])
        }
        return "/"
    }

    public func pwd() throws -> String {
        lock.lock()
        defer { lock.unlock() }
        guard let ctrl = control else { throw NativeBridgeError.ftpsError("FTPS session closed") }
        return try pwdUnlocked(on: ctrl)
    }

    public func listDirectory(_ remotePath: String) throws -> [SftpRemoteEntry] {
        lock.lock()
        defer { lock.unlock() }
        guard let ctrl = control else { throw NativeBridgeError.ftpsError("FTPS session closed") }

        let trimmed = remotePath.trimmingCharacters(in: .whitespacesAndNewlines)
        let dir: String
        if trimmed.isEmpty || trimmed == "." {
            dir = (try? pwdUnlocked(on: ctrl)) ?? "/"
        } else {
            dir = trimmed
            try Self.sendCommand("CWD \(dir)", on: ctrl)
            _ = try Self.readReply(from: ctrl, expectedCodes: [250, 200])
        }

        if mlsdSupported {
            let mlsdStream = try openPassiveDataSocket(on: ctrl)
            try Self.sendCommand("MLSD", on: ctrl)
            let mlsdPrelim = try Self.readReply(from: ctrl)
            if mlsdPrelim.code == 125 || mlsdPrelim.code == 150 {
                if protectDataChannel {
                    try mlsdStream.enableTls(peerHost: host, verifyTls: verifyTls, sessionPeerId: tlsSessionPeerId)
                }
                let rawData = try mlsdStream.readAllUntilEof()
                mlsdStream.close()
                _ = try Self.readReply(from: ctrl, expectedCodes: [226, 250])
                let rawText = String(decoding: rawData, as: UTF8.self)
                return NativeNetworkDiagnostics.parseFtpMlsd(parentDir: dir, rawMlsd: rawText)
            }
            mlsdStream.close()
            mlsdSupported = false
        }

        let listStream = try openPassiveDataSocket(on: ctrl)
        try Self.sendCommand("LIST", on: ctrl)
        _ = try Self.readReply(from: ctrl, expectedCodes: [125, 150])
        if protectDataChannel {
            try listStream.enableTls(peerHost: host, verifyTls: verifyTls, sessionPeerId: tlsSessionPeerId)
        }
        let listData = try listStream.readAllUntilEof()
        listStream.close()
        _ = try Self.readReply(from: ctrl, expectedCodes: [226, 250])
        return Self.parseUnixOrDosListOutput(parentDir: dir, rawList: String(decoding: listData, as: UTF8.self))
    }

    private static func parseUnixOrDosListOutput(parentDir: String, rawList: String) -> [SftpRemoteEntry] {
        let cleanParent = parentDir.hasSuffix("/") ? String(parentDir.dropLast()) : parentDir
        let months: Set<String> = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]
        var entries: [SftpRemoteEntry] = []
        for rawLine in rawList.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: CharacterSet(charactersIn: "\r\n"))
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty, !trimmed.lowercased().hasPrefix("total ") else { continue }

            // Windows IIS / MS-DOS `MM-DD-YY  HH:MMAM  <DIR>|size  filename`
            if let firstChar = trimmed.first, firstChar.isNumber {
                let dosTokens = trimmed.split(separator: " ", maxSplits: 3, omittingEmptySubsequences: true).map(String.init)
                if dosTokens.count == 4, dosTokens[0].contains("-") {
                    let isDir = dosTokens[2].uppercased() == "<DIR>"
                    let size = isDir ? 0 : (UInt64(dosTokens[2]) ?? 0)
                    let name = dosTokens[3]
                    if name != "." && name != ".." && !name.isEmpty {
                        let full = cleanParent.isEmpty ? "/\(name)" : "\(cleanParent)/\(name)"
                        entries.append(SftpRemoteEntry(
                            name: name,
                            fullPath: full,
                            isDirectory: isDir,
                            isSymlink: false,
                            sizeBytes: size,
                            unixMode: isDir ? 0o755 : 0o644,
                            modifiedEpochSec: 0,
                            owner: "iis"
                        ))
                    }
                    continue
                }
            }

            // UNIX `drwxr-xr-x 2 owner [domain users] 4096 Nov 17 10:20 filename`
            let words = trimmed.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
            guard words.count >= 9 else { continue }
            let perms = words[0]
            var monthIdx: Int? = nil
            for idx in 4..<(words.count - 2) {
                if months.contains(words[idx].lowercased()) && UInt64(words[idx - 1]) != nil {
                    monthIdx = idx
                    break
                }
            }
            let sizeIdx = (monthIdx ?? 5) - 1
            let size = sizeIdx < words.count ? (UInt64(words[sizeIdx]) ?? 0) : 0
            let nameTokenIdx = (monthIdx ?? 5) + 3
            guard nameTokenIdx < words.count else { continue }
            var name: String = words[nameTokenIdx...].joined(separator: " ")
            if let mIdx = monthIdx, mIdx + 2 < words.count {
                let timeOrYearToken = words[mIdx + 2]
                if let mRange = trimmed.range(of: " \(words[mIdx]) "),
                   let tRange = trimmed[mRange.upperBound...].range(of: timeOrYearToken) {
                    let afterTime = trimmed[tRange.upperBound...].drop(while: { $0 == " " })
                    if !afterTime.isEmpty {
                        name = String(afterTime)
                    }
                }
            }
            let isSymlink = perms.hasPrefix("l")
            if isSymlink, let arrow = name.range(of: " -> ") {
                name = String(name[..<arrow.lowerBound])
            }
            if name == "." || name == ".." || name.isEmpty { continue }
            let isDir = perms.hasPrefix("d")
            let full = cleanParent.isEmpty ? "/\(name)" : "\(cleanParent)/\(name)"
            entries.append(SftpRemoteEntry(
                name: name,
                fullPath: full,
                isDirectory: isDir,
                isSymlink: isSymlink,
                sizeBytes: size,
                unixMode: parseRwxMode(perms, isDir: isDir),
                modifiedEpochSec: 0,
                owner: words[2]
            ))
        }
        return entries
    }

    private static func parseRwxMode(_ perms: String, isDir: Bool) -> UInt32 {
        let chars = Array(perms)
        guard chars.count >= 10 else { return isDir ? 0o755 : 0o644 }
        var mode: UInt32 = 0
        let flags: [(Int, Character, UInt32)] = [
            (1, "r", 0o400), (2, "w", 0o200), (3, "x", 0o100),
            (4, "r", 0o040), (5, "w", 0o020), (6, "x", 0o010),
            (7, "r", 0o004), (8, "w", 0o002), (9, "x", 0o001)
        ]
        for (idx, expected, bit) in flags {
            if chars[idx] == expected || (expected == "x" && (chars[idx] == "s" || chars[idx] == "t")) {
                mode |= bit
            }
        }
        return mode
    }

    public func createDirectory(_ remotePath: String) throws {
        lock.lock()
        defer { lock.unlock() }
        guard let ctrl = control else { throw NativeBridgeError.ftpsError("FTPS session closed") }
        try Self.sendCommand("MKD \(remotePath)", on: ctrl)
        _ = try Self.readReply(from: ctrl, expectedCodes: [257, 250, 200])
    }

    public func removeEntry(_ remotePath: String, isDirectory: Bool) throws {
        lock.lock()
        defer { lock.unlock() }
        guard let ctrl = control else { throw NativeBridgeError.ftpsError("FTPS session closed") }
        try Self.sendCommand("\(isDirectory ? "RMD" : "DELE") \(remotePath)", on: ctrl)
        let reply = try Self.readReply(from: ctrl)
        if reply.code != 250 && reply.code != 200 {
            try Self.sendCommand("\(isDirectory ? "DELE" : "RMD") \(remotePath)", on: ctrl)
            _ = try Self.readReply(from: ctrl, expectedCodes: [250, 200])
        }
    }

    public func renameEntry(from oldPath: String, to newPath: String) throws {
        lock.lock()
        defer { lock.unlock() }
        guard let ctrl = control else { throw NativeBridgeError.ftpsError("FTPS session closed") }
        try Self.sendCommand("RNFR \(oldPath)", on: ctrl)
        _ = try Self.readReply(from: ctrl, expectedCodes: [350])
        try Self.sendCommand("RNTO \(newPath)", on: ctrl)
        _ = try Self.readReply(from: ctrl, expectedCodes: [250, 200])
    }

    public func chmod(_ remotePath: String, modeOctal: UInt32) throws {
        lock.lock()
        defer { lock.unlock() }
        guard let ctrl = control else { throw NativeBridgeError.ftpsError("FTPS session closed") }
        let cmd = String(format: "SITE CHMOD %04o %@", modeOctal & 0o7777, remotePath)
        try Self.sendCommand(cmd, on: ctrl)
        _ = try Self.readReply(from: ctrl, expectedCodes: [200, 250])
    }

    public func downloadBytes(_ remotePath: String) throws -> Data {
        lock.lock()
        defer { lock.unlock() }
        guard let ctrl = control else { throw NativeBridgeError.ftpsError("FTPS session closed") }
        let dataStream = try openPassiveDataSocket(on: ctrl)
        do {
            try Self.sendCommand("RETR \(remotePath)", on: ctrl)
            _ = try Self.readReply(from: ctrl, expectedCodes: [125, 150])
            if protectDataChannel {
                try dataStream.enableTls(peerHost: host, verifyTls: verifyTls, sessionPeerId: tlsSessionPeerId)
            }
            let payload = try dataStream.readAllUntilEof()
            dataStream.close()
            _ = try Self.readReply(from: ctrl, expectedCodes: [226, 250])
            return payload
        } catch {
            dataStream.close()
            throw error
        }
    }

    public func uploadBytes(_ data: Data, to remotePath: String) throws {
        lock.lock()
        defer { lock.unlock() }
        guard let ctrl = control else { throw NativeBridgeError.ftpsError("FTPS session closed") }
        let dataStream = try openPassiveDataSocket(on: ctrl)
        do {
            try Self.sendCommand("STOR \(remotePath)", on: ctrl)
            _ = try Self.readReply(from: ctrl, expectedCodes: [125, 150])
            if protectDataChannel {
                try dataStream.enableTls(peerHost: host, verifyTls: verifyTls, sessionPeerId: tlsSessionPeerId)
            }
            try dataStream.writeAll(data)
            dataStream.close()
            _ = try Self.readReply(from: ctrl, expectedCodes: [226, 250])
        } catch {
            dataStream.close()
            throw error
        }
    }

    public func disconnect() {
        lock.lock()
        let ctrl = control
        control = nil
        lock.unlock()
        if let ctrl {
            try? Self.sendCommand("QUIT", on: ctrl)
            ctrl.close()
        }
    }
}

// MARK: - 5. IPv4 CIDR Subnet Calculator & POSIX DNS Lookup

public struct SubnetCalculationResult: Equatable, Sendable {
    public let cidrNotation: String
    public let networkAddress: String
    public let subnetMask: String
    public let wildcardMask: String
    public let broadcastAddress: String
    public let usableRange: String
    public let usableHostCount: UInt64
}

public enum NetworkCalculators {
    public static func calculateIpv4Subnet(cidrInput: String) -> SubnetCalculationResult? {
        let parts = cidrInput.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: "/")
        guard let ipStr = parts.first else { return nil }
        let octets = ipStr.split(separator: ".").compactMap { UInt32($0) }
        guard octets.count == 4, octets.allSatisfy({ $0 <= 255 }) else { return nil }

        let prefix: Int
        if parts.count == 2, let p = Int(parts[1]), (0...32).contains(p) {
            prefix = p
        } else {
            prefix = 24
        }

        let ip = (octets[0] << 24) | (octets[1] << 16) | (octets[2] << 8) | octets[3]
        let mask: UInt32 = prefix == 0 ? 0 : (~UInt32(0) << (32 - prefix))
        let wildcard = ~mask
        let network = ip & mask
        let broadcast = network | wildcard

        func fmt(_ v: UInt32) -> String {
            "\((v >> 24) & 0xFF).\((v >> 16) & 0xFF).\((v >> 8) & 0xFF).\(v & 0xFF)"
        }

        let usableRange: String
        let usableCount: UInt64
        if prefix == 32 {
            usableRange = "\(fmt(network)) (Host Route)"
            usableCount = 1
        } else if prefix == 31 {
            usableRange = "\(fmt(network)) – \(fmt(broadcast)) (RFC 3021 P2P)"
            usableCount = 2
        } else {
            usableRange = "\(fmt(network &+ 1)) – \(fmt(broadcast &- 1))"
            usableCount = (UInt64(1) << (32 - prefix)) - 2
        }

        return SubnetCalculationResult(
            cidrNotation: "\(fmt(network))/\(prefix)",
            networkAddress: fmt(network),
            subnetMask: fmt(mask),
            wildcardMask: fmt(wildcard),
            broadcastAddress: fmt(broadcast),
            usableRange: usableRange,
            usableHostCount: usableCount
        )
    }

    public static func resolveDnsRecords(hostname: String) -> [String] {
        var hints = addrinfo()
        hints.ai_socktype = SOCK_STREAM
        hints.ai_family = AF_UNSPEC

        var res: UnsafeMutablePointer<addrinfo>? = nil
        let status = getaddrinfo(hostname, nil, &hints, &res)
        guard status == 0, let head = res else { return [] }
        defer { freeaddrinfo(head) }

        var results: [String] = []
        var seen = Set<String>()
        var ptr: UnsafeMutablePointer<addrinfo>? = head
        while let current = ptr {
            var hostBuf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(
                current.pointee.ai_addr,
                current.pointee.ai_addrlen,
                &hostBuf,
                socklen_t(hostBuf.count),
                nil,
                0,
                NI_NUMERICHOST
            ) == 0 {
                let ip = String(cString: hostBuf)
                let recordType = current.pointee.ai_family == AF_INET6 ? "AAAA" : "A"
                let line = "\(recordType)  →  \(ip)"
                if seen.insert(line).inserted {
                    results.append(line)
                }
            }
            ptr = current.pointee.ai_next
        }
        return results
    }
}
