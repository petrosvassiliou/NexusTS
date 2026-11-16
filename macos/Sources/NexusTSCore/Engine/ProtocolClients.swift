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

public enum CloudHypervisorClient {
    public static func fetchInventory(
        connection: VaultConnection,
        credential: VaultCredential?
    ) async throws -> [CloudVmInventoryItem] {
        guard case .cloudVirtualization(let cfg) = connection.settings else { return [] }
        let base = cfg.apiEndpointUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let urlSession = ConfigurableTlsUrlSession.session(verifyTls: cfg.verifyTls)

        switch connection.protocolType {
        case .PROXMOX_VE:
            guard let url = URL(string: "\(base)/api2/json/cluster/resources?type=vm") else { return [] }
            var req = URLRequest(url: url)
            if let cred = credential, !cred.secretPassword.isEmpty {
                if cred.username.contains("!") || cred.secretPassword.hasPrefix("PVEAPIToken=") {
                    let headerVal = cred.secretPassword.hasPrefix("PVEAPIToken=")
                        ? cred.secretPassword
                        : "PVEAPIToken=\(cred.username)=\(cred.secretPassword)"
                    req.setValue(headerVal, forHTTPHeaderField: "Authorization")
                } else if let ticketUrl = URL(string: "\(base)/api2/json/access/ticket") {
                    let realm = cfg.realmOrDomain.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        ? "pam"
                        : cfg.realmOrDomain.trimmingCharacters(in: .whitespacesAndNewlines)
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
                    let (ticketData, _) = try await urlSession.data(for: ticketReq)
                    struct PveTicketResp: Decodable {
                        struct TicketPayload: Decodable {
                            let ticket: String?
                            let CSRFPreventionToken: String?
                        }
                        let data: TicketPayload?
                    }
                    if let ticketResp = try? JSONDecoder().decode(PveTicketResp.self, from: ticketData),
                       let ticket = ticketResp.data?.ticket,
                       !ticket.isEmpty {
                        req.setValue("PVEAuthCookie=\(ticket)", forHTTPHeaderField: "Cookie")
                        if let csrf = ticketResp.data?.CSRFPreventionToken, !csrf.isEmpty {
                            req.setValue(csrf, forHTTPHeaderField: "CSRFPreventionToken")
                        }
                    }
                }
            }
            let (data, _) = try await urlSession.data(for: req)
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
            let (tokData, _) = try await urlSession.data(for: authReq)
            let token = String(data: tokData, encoding: .utf8)?.trimmingCharacters(in: CharacterSet(charactersIn: "\"\n ")) ?? ""

            var listReq = URLRequest(url: vmUrl)
            listReq.setValue(token, forHTTPHeaderField: "vmware-api-session-id")
            let (vmData, _) = try await urlSession.data(for: listReq)
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
            let (data, _) = try await urlSession.data(for: req)
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

        case .HYPER_V:
            return try await fetchHyperVWinRmInventory(
                baseEndpoint: base.isEmpty ? "https://\(connection.host):5986" : base,
                hostLabel: connection.host,
                credential: credential,
                urlSession: urlSession
            )

        default:
            return []
        }
    }

    /// Queries Hyper-V `Msvm_ComputerSystem` virtual machines over WinRM WS-Management (`:5985` / `:5986`).
    private static func fetchHyperVWinRmInventory(
        baseEndpoint: String,
        hostLabel: String,
        credential: VaultCredential?,
        urlSession: URLSession
    ) async throws -> [CloudVmInventoryItem] {
        let wsmanUrlStr = baseEndpoint.lowercased().hasSuffix("/wsman")
            ? baseEndpoint
            : "\(baseEndpoint)/wsman"
        guard let wsmanUrl = URL(string: wsmanUrlStr) else { return [] }

        let msgId = UUID().uuidString.lowercased()
        let soapEnvelope = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                    xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                    xmlns:w="http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd"
                    xmlns:n="http://schemas.xmlsoap.org/ws/2004/09/enumeration">
          <s:Header>
            <a:To>\(wsmanUrlStr)</a:To>
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

        var req = URLRequest(url: wsmanUrl)
        req.httpMethod = "POST"
        req.setValue("application/soap+xml;charset=UTF-8", forHTTPHeaderField: "Content-Type")
        if let cred = credential, !cred.username.isEmpty {
            let userPrincipal = cred.domain.isEmpty ? cred.username : "\(cred.domain)\\\(cred.username)"
            let basic = Data("\(userPrincipal):\(cred.secretPassword)".utf8).base64EncodedString()
            req.setValue("Basic \(basic)", forHTTPHeaderField: "Authorization")
        }
        req.httpBody = Data(soapEnvelope.utf8)

        let (xmlData, resp) = try await urlSession.data(for: req)
        if let http = resp as? HTTPURLResponse, http.statusCode == 401 {
            throw NativeBridgeError.authenticationFailed("Hyper-V WinRM authentication rejected (HTTP 401).")
        }
        let xml = String(data: xmlData, encoding: .utf8) ?? ""
        return parseHyperVWsmanXml(xml, hostLabel: hostLabel)
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
                        cpuSummary: "Hyper-V Gen2",
                        memorySummary: "WinRM v2",
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

        let (xmlData, _) = try await ConfigurableTlsUrlSession.session(verifyTls: true).data(for: req)
        let xml = String(data: xmlData, encoding: .utf8) ?? ""
        return parseEc2InstancesXml(xml)
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
                            let ip = currentPrivateIp.isEmpty ? currentPublicIp : currentPrivateIp
                            let state = currentStateName.isEmpty ? "RUNNING" : currentStateName.uppercased()
                            let cProto: VaultProtocolType = currentPlatform.lowercased().contains("windows") ? .RDP : .SSH
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

// MARK: - 3. Kubernetes Pod & Docker Container Workload Client (Async POSIX AF_UNIX + Safe Shell Escaping)

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

    /// Builds an injection-safe `kubectl exec` or `docker exec` command string.
    public static func buildSafeExecCommand(
        protocolType: VaultProtocolType,
        settings: ContainerExecProtocolSettings
    ) -> String {
        let rawTokens = settings.shellCommand
            .split(whereSeparator: { $0.isWhitespace })
            .map(String.init)
        let shellTokens = (rawTokens.isEmpty ? ["/bin/sh"] : rawTokens)
            .map { shellEscapeSingleQuoted($0) }
            .joined(separator: " ")

        let target = settings.podOrContainerName.isEmpty ? "pod" : settings.podOrContainerName
        if protocolType == .KUBERNETES_POD {
            var parts: [String] = ["kubectl"]
            let ctx = settings.contextName.trimmingCharacters(in: .whitespacesAndNewlines)
            if !ctx.isEmpty {
                parts.append("--context")
                parts.append(shellEscapeSingleQuoted(ctx))
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
            return "docker exec -it \(shellEscapeSingleQuoted(target)) \(shellTokens)"
        }
    }

    public static func listWorkloads(
        protocolType: VaultProtocolType,
        settings: ContainerExecProtocolSettings,
        bearerToken: String = ""
    ) async throws -> [ContainerWorkloadSummary] {
        if protocolType == .DOCKER_CONTAINER {
            let data = try await dockerRequest(
                endpoint: settings.clusterOrSocketEndpoint,
                path: "/containers/json?all=true",
                verifyTls: settings.verifyTls
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
            let ns = encodePathSegment(settings.namespace.isEmpty ? "default" : settings.namespace)
            guard let url = URL(string: "\(base)/api/v1/namespaces/\(ns)/pods") else { return [] }
            var req = URLRequest(url: url)
            if !bearerToken.isEmpty {
                req.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
            }
            let (data, _) = try await ConfigurableTlsUrlSession.session(verifyTls: settings.verifyTls).data(for: req)
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
            let encodedTarget = encodePathSegment(target)
            let raw = try await dockerRequest(
                endpoint: settings.clusterOrSocketEndpoint,
                path: "/containers/\(encodedTarget)/logs?stdout=1&stderr=1&tail=\(max(10, settings.logTailLines))",
                verifyTls: settings.verifyTls
            )
            return stripDockerMultiplexHeaders(raw)
        } else {
            let base = settings.clusterOrSocketEndpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            let ns = encodePathSegment(settings.namespace.isEmpty ? "default" : settings.namespace)
            let pod = encodePathSegment(target)
            var comps = URLComponents(string: "\(base)/api/v1/namespaces/\(ns)/pods/\(pod)/log")
            var queryItems = [URLQueryItem(name: "tailLines", value: "\(max(10, settings.logTailLines))")]
            if !settings.subContainerName.isEmpty {
                queryItems.append(URLQueryItem(name: "container", value: settings.subContainerName))
            }
            comps?.queryItems = queryItems
            guard let url = comps?.url else { return "" }
            var req = URLRequest(url: url)
            if !bearerToken.isEmpty {
                req.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
            }
            let (data, _) = try await ConfigurableTlsUrlSession.session(verifyTls: settings.verifyTls).data(for: req)
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

    private static func dockerRequest(endpoint: String, path: String, verifyTls: Bool) async throws -> Data {
        if endpoint.hasPrefix("unix://") || endpoint.hasPrefix("/") {
            let sockPath = endpoint.replacingOccurrences(of: "unix://", with: "")
            return try await Task.detached(priority: .userInitiated) {
                try performUnixDomainHttpGet(socketPath: sockPath, requestPath: path)
            }.value
        } else {
            let base = endpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard let url = URL(string: "\(base)\(path)") else { return Data() }
            let (data, _) = try await ConfigurableTlsUrlSession.session(verifyTls: verifyTls).data(from: url)
            return data
        }
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
        if headerStr.lowercased().contains("transfer-encoding: chunked") {
            return decodeChunkedHttpBody(bodySlice)
        }
        return bodySlice
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

// MARK: - 4. Native macOS FTPS Client (Implicit TLS :990 & Explicit AUTH TLS :21 + PBSZ 0 + PROT P)

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
        let portStr = "\(port == 0 ? 990 : port)"
        let gai = getaddrinfo(host, portStr, &hints, &res)
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

    func enableTls(peerHost: String, verifyTls: Bool) throws {
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
        _ = SSLSetProtocolVersionMin(ctx, .tlsProtocol12)

        if !verifyTls {
            _ = SSLSetSessionOption(ctx, .breakOnServerAuth, true)
        }

        repeat {
            status = SSLHandshake(ctx)
            if status == errSSLServerAuthCompleted && !verifyTls {
                // Continue past server trust break when certificate verification is disabled
                continue
            }
        } while status == errSSLWouldBlock || (status == errSSLServerAuthCompleted && !verifyTls)

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
    private let lock = NSLock()
    private var control: FtpsTlsSocketStream?
    private var protectDataChannel: Bool = true

    private init(
        host: String,
        port: UInt16,
        verifyTls: Bool,
        control: FtpsTlsSocketStream,
        protectDataChannel: Bool
    ) {
        self.host = host
        self.port = port
        self.verifyTls = verifyTls
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
        let ctrl = try FtpsTlsSocketStream.connectTcp(host: host, port: resolvedPort)
        do {
            if implicitTls {
                try ctrl.enableTls(peerHost: host, verifyTls: verifyTls)
                _ = try readReply(from: ctrl, expectedCodes: [220])
            } else {
                _ = try readReply(from: ctrl, expectedCodes: [220])
                try sendCommand("AUTH TLS", on: ctrl)
                let authReply = try readReply(from: ctrl)
                if authReply.code != 234 {
                    try sendCommand("AUTH SSL", on: ctrl)
                    _ = try readReply(from: ctrl, expectedCodes: [234])
                }
                try ctrl.enableTls(peerHost: host, verifyTls: verifyTls)
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

    private func openPassiveDataSocket(on ctrl: FtpsTlsSocketStream) throws -> FtpsTlsSocketStream {
        try Self.sendCommand("EPSV", on: ctrl)
        let epsv = try Self.readReply(from: ctrl)
        if epsv.code == 229, let dataPort = Self.parseEpsvPort(epsv.message) {
            return try FtpsTlsSocketStream.connectTcp(host: host, port: dataPort)
        }

        try Self.sendCommand("PASV", on: ctrl)
        let pasv = try Self.readReply(from: ctrl, expectedCodes: [227])
        guard let (pasvHost, pasvPort) = Self.parsePasvAddress(pasv.message) else {
            throw NativeBridgeError.ftpsError("Unable to parse PASV reply: \(pasv.message)")
        }
        let targetHost = (pasvHost == "0.0.0.0" || pasvHost.isEmpty) ? host : pasvHost
        return try FtpsTlsSocketStream.connectTcp(host: targetHost, port: pasvPort)
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

    public func pwd() throws -> String {
        lock.lock()
        defer { lock.unlock() }
        guard let ctrl = control else { throw NativeBridgeError.ftpsError("FTPS session closed") }
        try Self.sendCommand("PWD", on: ctrl)
        let reply = try Self.readReply(from: ctrl, expectedCodes: [257])
        if let firstQuote = reply.message.firstIndex(of: "\""),
           let lastQuote = reply.message[reply.message.index(after: firstQuote)...].firstIndex(of: "\"") {
            return String(reply.message[reply.message.index(after: firstQuote)..<lastQuote])
        }
        return "/"
    }

    public func listDirectory(_ remotePath: String) throws -> [SftpRemoteEntry] {
        lock.lock()
        defer { lock.unlock() }
        guard let ctrl = control else { throw NativeBridgeError.ftpsError("FTPS session closed") }

        let dir = remotePath.isEmpty ? "/" : remotePath
        try Self.sendCommand("CWD \(dir)", on: ctrl)
        _ = try Self.readReply(from: ctrl, expectedCodes: [250, 200])

        let mlsdStream = try openPassiveDataSocket(on: ctrl)
        try Self.sendCommand("MLSD", on: ctrl)
        let mlsdPrelim = try Self.readReply(from: ctrl)
        if mlsdPrelim.code == 125 || mlsdPrelim.code == 150 {
            if protectDataChannel {
                try mlsdStream.enableTls(peerHost: host, verifyTls: verifyTls)
            }
            let rawData = try mlsdStream.readAllUntilEof()
            mlsdStream.close()
            _ = try Self.readReply(from: ctrl, expectedCodes: [226, 250])
            let rawText = String(decoding: rawData, as: UTF8.self)
            return NativeNetworkDiagnostics.parseFtpMlsd(parentDir: dir, rawMlsd: rawText)
        }
        mlsdStream.close()

        let listStream = try openPassiveDataSocket(on: ctrl)
        try Self.sendCommand("LIST -la", on: ctrl)
        let listPrelim = try Self.readReply(from: ctrl, expectedCodes: [125, 150])
        _ = listPrelim
        if protectDataChannel {
            try listStream.enableTls(peerHost: host, verifyTls: verifyTls)
        }
        let listData = try listStream.readAllUntilEof()
        listStream.close()
        _ = try Self.readReply(from: ctrl, expectedCodes: [226, 250])
        return Self.parseUnixListOutput(parentDir: dir, rawList: String(decoding: listData, as: UTF8.self))
    }

    private static func parseUnixListOutput(parentDir: String, rawList: String) -> [SftpRemoteEntry] {
        let cleanParent = parentDir.hasSuffix("/") ? String(parentDir.dropLast()) : parentDir
        var entries: [SftpRemoteEntry] = []
        for rawLine in rawList.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty, !line.lowercased().hasPrefix("total ") else { continue }
            let cols = line.split(separator: " ", maxSplits: 8, omittingEmptySubsequences: true).map(String.init)
            guard cols.count >= 9 else { continue }
            let perms = cols[0]
            var name = cols[8]
            let isSymlink = perms.hasPrefix("l")
            if isSymlink, let arrow = name.range(of: " -> ") {
                name = String(name[..<arrow.lowerBound])
            }
            if name == "." || name == ".." || name.isEmpty { continue }
            let isDir = perms.hasPrefix("d")
            let size = UInt64(cols[4]) ?? 0
            let full = cleanParent.isEmpty ? "/\(name)" : "\(cleanParent)/\(name)"
            entries.append(SftpRemoteEntry(
                name: name,
                fullPath: full,
                isDirectory: isDir,
                isSymlink: isSymlink,
                sizeBytes: size,
                unixMode: parseRwxMode(perms, isDir: isDir),
                modifiedEpochSec: 0,
                owner: cols[2]
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
                try dataStream.enableTls(peerHost: host, verifyTls: verifyTls)
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
                try dataStream.enableTls(peerHost: host, verifyTls: verifyTls)
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
