import CryptoKit
import Foundation

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

public enum CloudHypervisorClient {
    public static func fetchInventory(
        connection: VaultConnection,
        credential: VaultCredential?
    ) async throws -> [CloudVmInventoryItem] {
        guard case .cloudVirtualization(let cfg) = connection.settings else { return [] }
        let base = cfg.apiEndpointUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))

        switch connection.protocolType {
        case .PROXMOX_VE:
            guard let url = URL(string: "\(base)/api2/json/cluster/resources?type=vm") else { return [] }
            var req = URLRequest(url: url)
            if let cred = credential, !cred.secretPassword.isEmpty {
                req.setValue("PVEAPIToken=\(cred.username)=\(cred.secretPassword)", forHTTPHeaderField: "Authorization")
            }
            let (data, _) = try await URLSession.shared.data(for: req)
            struct PveResp: Decodable {
                struct Item: Decodable {
                    let vmid: Int?
                    let name: String?
                    let status: String?
                    let node: String?
                    let maxcpu: Int?
                    let maxmem: Int64?
                }
                let data: [Item]?
            }
            let decoded = try JSONDecoder().decode(PveResp.self, from: data)
            return (decoded.data ?? []).map { vm in
                let idStr = "\(vm.vmid ?? 100)"
                let memGb = Double(vm.maxmem ?? 0) / (1024 * 1024 * 1024)
                return CloudVmInventoryItem(
                    id: idStr,
                    name: vm.name ?? "vm-\(idStr)",
                    powerState: (vm.status ?? "unknown").uppercased(),
                    addressOrNode: vm.node ?? connection.host,
                    cpuSummary: "\(vm.maxcpu ?? 2) vCPU",
                    memorySummary: String(format: "%.1f GiB", memGb),
                    consoleProtocol: .VNC
                )
            }

        case .VMWARE_VSPHERE:
            guard let sessionUrl = URL(string: "\(base)/api/session"),
                  let vmUrl = URL(string: "\(base)/api/vcenter/vm") else { return [] }
            var authReq = URLRequest(url: sessionUrl)
            authReq.httpMethod = "POST"
            if let cred = credential {
                let basic = Data("\(cred.username):\(cred.secretPassword)".utf8).base64EncodedString()
                authReq.setValue("Basic \(basic)", forHTTPHeaderField: "Authorization")
            }
            let (tokData, _) = try await URLSession.shared.data(for: authReq)
            let token = String(data: tokData, encoding: .utf8)?.trimmingCharacters(in: CharacterSet(charactersIn: "\"\n ")) ?? ""

            var listReq = URLRequest(url: vmUrl)
            listReq.setValue(token, forHTTPHeaderField: "vmware-api-session-id")
            let (vmData, _) = try await URLSession.shared.data(for: listReq)
            struct VsphereVm: Decodable {
                let vm: String?
                let name: String?
                let power_state: String?
                let cpu_count: Int?
                let memory_size_MiB: Int?
            }
            let vms = (try? JSONDecoder().decode([VsphereVm].self, from: vmData)) ?? []
            return vms.map { item in
                CloudVmInventoryItem(
                    id: item.vm ?? UUID().uuidString,
                    name: item.name ?? "vSphere VM",
                    powerState: item.power_state ?? "POWERED_ON",
                    addressOrNode: connection.host,
                    cpuSummary: "\(item.cpu_count ?? 2) vCPU",
                    memorySummary: "\(item.memory_size_MiB ?? 4096) MiB",
                    consoleProtocol: .SSH
                )
            }

        case .AWS_EC2:
            let region = cfg.regionOrCluster.isEmpty ? "eu-central-1" : cfg.regionOrCluster
            return try await describeEc2InstancesSigV4(
                region: region,
                accessKeyId: credential?.username ?? "",
                secretAccessKey: credential?.secretPassword ?? ""
            )

        case .AZURE_VM:
            let subId = cfg.subscriptionOrAccountId
            guard let url = URL(string: "https://management.azure.com/subscriptions/\(subId)/providers/Microsoft.Compute/virtualMachines?api-version=2024-03-01") else {
                return []
            }
            var req = URLRequest(url: url)
            if let token = credential?.secretPassword, !token.isEmpty {
                req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            }
            let (data, _) = try await URLSession.shared.data(for: req)
            struct AzureList: Decodable {
                struct VM: Decodable {
                    let id: String?
                    let name: String?
                    let location: String?
                }
                let value: [VM]?
            }
            let list = try JSONDecoder().decode(AzureList.self, from: data)
            return (list.value ?? []).map { vm in
                CloudVmInventoryItem(
                    id: vm.id ?? UUID().uuidString,
                    name: vm.name ?? "Azure VM",
                    powerState: "RUNNING",
                    addressOrNode: vm.location ?? "westeurope",
                    cpuSummary: "Standard_D4s_v5",
                    memorySummary: "16 GiB",
                    consoleProtocol: .SSH
                )
            }

        default:
            return []
        }
    }

    /// Signs and dispatches an AWS EC2 `DescribeInstances` query using full AWS Signature v4 (`AWS4-HMAC-SHA256`).
    public static func describeEc2InstancesSigV4(
        region: String,
        accessKeyId: String,
        secretAccessKey: String
    ) async throws -> [CloudVmInventoryItem] {
        guard !accessKeyId.isEmpty, !secretAccessKey.isEmpty else { return [] }
        let host = "ec2.\(region).amazonaws.com"
        let endpoint = URL(string: "https://\(host)/")!
        let body = "Action=DescribeInstances&Version=2016-11-15"
        let bodyData = Data(body.utf8)

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        let now = Date()
        let amzDate = formatter.string(from: now)
        let dateStamp = String(amzDate.prefix(8))

        let payloadHash = SHA256.hash(data: bodyData).map { String(format: "%02x", $0) }.joined()
        let canonicalHeaders = "content-type:application/x-www-form-urlencoded; charset=utf-8\nhost:\(host)\nx-amz-date:\(amzDate)\n"
        let signedHeaders = "content-type;host;x-amz-date"
        let canonicalRequest = "POST\n/\n\n\(canonicalHeaders)\n\(signedHeaders)\n\(payloadHash)"
        let canonicalHash = SHA256.hash(data: Data(canonicalRequest.utf8)).map { String(format: "%02x", $0) }.joined()

        let credentialScope = "\(dateStamp)/\(region)/ec2/aws4_request"
        let stringToSign = "AWS4-HMAC-SHA256\n\(amzDate)\n\(credentialScope)\n\(canonicalHash)"

        func hmac(_ key: Data, _ msg: String) -> Data {
            Data(HMAC<SHA256>.authenticationCode(for: Data(msg.utf8), using: SymmetricKey(data: key)))
        }
        let kDate = hmac(Data("AWS4\(secretAccessKey)".utf8), dateStamp)
        let kRegion = hmac(kDate, region)
        let kService = hmac(kRegion, "ec2")
        let kSigning = hmac(kService, "aws4_request")
        let signature = hmac(kSigning, stringToSign).map { String(format: "%02x", $0) }.joined()

        let authHeader = "AWS4-HMAC-SHA256 Credential=\(accessKeyId)/\(credentialScope), SignedHeaders=\(signedHeaders), Signature=\(signature)"
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue(amzDate, forHTTPHeaderField: "X-Amz-Date")
        req.setValue(authHeader, forHTTPHeaderField: "Authorization")
        req.httpBody = bodyData

        let (xmlData, _) = try await URLSession.shared.data(for: req)
        let xml = String(data: xmlData, encoding: .utf8) ?? ""
        return parseEc2InstancesXml(xml)
    }

    private static func parseEc2InstancesXml(_ xml: String) -> [CloudVmInventoryItem] {
        var items: [CloudVmInventoryItem] = []
        let parts = xml.components(separatedBy: "<instanceId>")
        for chunk in parts.dropFirst() {
            let instId = chunk.components(separatedBy: "</instanceId>").first ?? "i-unknown"
            let state = chunk.components(separatedBy: "<name>").dropFirst().first?.components(separatedBy: "</name>").first ?? "running"
            let instType = chunk.components(separatedBy: "<instanceType>").dropFirst().first?.components(separatedBy: "</instanceType>").first ?? "m6i.large"
            let ip = chunk.components(separatedBy: "<privateIpAddress>").dropFirst().first?.components(separatedBy: "</privateIpAddress>").first ?? ""
            items.append(CloudVmInventoryItem(
                id: instId,
                name: instId,
                powerState: state.uppercased(),
                addressOrNode: ip,
                cpuSummary: instType,
                memorySummary: "EC2 EBS",
                consoleProtocol: .SSH
            ))
        }
        return items
    }
}

// MARK: - 3. Kubernetes Pod & Docker Container Workload Client (Supports macOS /var/run/docker.sock)

public struct ContainerWorkloadSummary: Identifiable, Equatable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let namespaceOrImage: String
    public let status: String
}

public enum ContainerWorkloadClient {
    public static func listWorkloads(
        protocolType: VaultProtocolType,
        settings: ContainerExecProtocolSettings,
        bearerToken: String = ""
    ) async throws -> [ContainerWorkloadSummary] {
        if protocolType == .DOCKER_CONTAINER {
            let data = try await dockerRequest(
                endpoint: settings.clusterOrSocketEndpoint,
                path: "/containers/json?all=true"
            )
            struct DockerContainer: Decodable {
                let Id: String?
                let Names: [String]?
                let Image: String?
                let State: String?
            }
            let list = (try? JSONDecoder().decode([DockerContainer].self, from: data)) ?? []
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
            let base = settings.clusterOrSocketEndpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard let url = URL(string: "\(base)/api/v1/namespaces/\(settings.namespace)/pods") else { return [] }
            var req = URLRequest(url: url)
            if !bearerToken.isEmpty {
                req.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
            }
            let (data, _) = try await URLSession.shared.data(for: req)
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
        bearerToken: String = ""
    ) async throws -> String {
        let target = settings.podOrContainerName
        guard !target.isEmpty else { return "" }
        if protocolType == .DOCKER_CONTAINER {
            let raw = try await dockerRequest(
                endpoint: settings.clusterOrSocketEndpoint,
                path: "/containers/\(target)/logs?stdout=1&stderr=1&tail=\(max(10, settings.logTailLines))"
            )
            return stripDockerMultiplexHeaders(raw)
        } else {
            let base = settings.clusterOrSocketEndpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            var path = "\(base)/api/v1/namespaces/\(settings.namespace)/pods/\(target)/log?tailLines=\(max(10, settings.logTailLines))"
            if !settings.subContainerName.isEmpty {
                path += "&container=\(settings.subContainerName)"
            }
            guard let url = URL(string: path) else { return "" }
            var req = URLRequest(url: url)
            if !bearerToken.isEmpty {
                req.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
            }
            let (data, _) = try await URLSession.shared.data(for: req)
            return String(data: data, encoding: .utf8) ?? ""
        }
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

    private static func dockerRequest(endpoint: String, path: String) async throws -> Data {
        if endpoint.hasPrefix("unix://") || endpoint.hasPrefix("/") {
            let sockPath = endpoint.replacingOccurrences(of: "unix://", with: "")
            let proc = Process()
            proc.executableURL = URL(fileURLWithPath: "/usr/bin/curl")
            proc.arguments = ["-sS", "--max-time", "5", "--unix-socket", sockPath, "http://localhost\(path)"]
            let outPipe = Pipe()
            proc.standardOutput = outPipe
            proc.standardError = FileHandle.nullDevice
            try proc.run()
            let data = outPipe.fileHandleForReading.readDataToEndOfFile()
            proc.waitUntilExit()
            return data
        } else {
            let base = endpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard let url = URL(string: "\(base)\(path)") else { return Data() }
            let (data, _) = try await URLSession.shared.data(from: url)
            return data
        }
    }
}

// MARK: - 4. IPv4 CIDR Subnet Calculator & POSIX DNS Lookup

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
