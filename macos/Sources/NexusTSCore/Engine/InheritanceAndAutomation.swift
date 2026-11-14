import CryptoKit
import Foundation

// MARK: - Cascading Property Inheritance & Smart / Dynamic Folder Evaluators

public struct EffectiveConnectionProfile: Equatable, Sendable {
    public let connection: VaultConnection
    public let effectiveCredential: VaultCredential?
    public let credentialInheritedFrom: String
    public let promptCredentialAtConnect: Bool
    public let effectiveGateway: SecureGatewayConfig?
    public let gatewayInheritedFrom: String
    public let effectiveEnvironmentBadge: String?
    public let preConnectTaskId: String?
    public let postDisconnectTaskId: String?
    public let displayColorAccentHex: String?
    public let requireConnectConfirmation: Bool
    public let effectiveCustomProperties: [String: CustomPropertyEntry]

    public init(
        connection: VaultConnection,
        effectiveCredential: VaultCredential?,
        credentialInheritedFrom: String,
        promptCredentialAtConnect: Bool,
        effectiveGateway: SecureGatewayConfig?,
        gatewayInheritedFrom: String,
        effectiveEnvironmentBadge: String?,
        preConnectTaskId: String?,
        postDisconnectTaskId: String?,
        displayColorAccentHex: String?,
        requireConnectConfirmation: Bool,
        effectiveCustomProperties: [String: CustomPropertyEntry]
    ) {
        self.connection = connection
        self.effectiveCredential = effectiveCredential
        self.credentialInheritedFrom = credentialInheritedFrom
        self.promptCredentialAtConnect = promptCredentialAtConnect
        self.effectiveGateway = effectiveGateway
        self.gatewayInheritedFrom = gatewayInheritedFrom
        self.effectiveEnvironmentBadge = effectiveEnvironmentBadge
        self.preConnectTaskId = preConnectTaskId
        self.postDisconnectTaskId = postDisconnectTaskId
        self.displayColorAccentHex = displayColorAccentHex
        self.requireConnectConfirmation = requireConnectConfirmation
        self.effectiveCustomProperties = effectiveCustomProperties
    }
}

public enum PropertyInheritanceResolver {
    public static func folderAncestorChain(
        leafFolderId: String?,
        foldersById: [String: VaultFolder]
    ) -> [VaultFolder] {
        var chain: [VaultFolder] = []
        var visited = Set<String>()
        var cursor = leafFolderId
        while let currentId = cursor, !visited.contains(currentId), let folder = foldersById[currentId] {
            visited.insert(currentId)
            chain.append(folder)
            cursor = folder.parentFolderId
        }
        return chain.reversed()
    }

    public static func resolve(
        connection: VaultConnection,
        in document: NxtsVaultDocument
    ) -> EffectiveConnectionProfile {
        let activeFolders = Dictionary(
            uniqueKeysWithValues: document.folders
                .filter { !document.tombstonedObjectIds.contains($0.id) }
                .map { ($0.id, $0) }
        )
        let credentialsById = Dictionary(
            uniqueKeysWithValues: document.credentials
                .filter { !document.tombstonedObjectIds.contains($0.id) }
                .map { ($0.id, $0) }
        )
        let gatewaysById = Dictionary(
            uniqueKeysWithValues: document.gateways
                .filter { !document.tombstonedObjectIds.contains($0.id) }
                .map { ($0.id, $0) }
        )

        var stages: [(sourceName: String, props: InheritableProperties)] = [
            ("Vault Root", document.rootInheritable)
        ]
        for folder in folderAncestorChain(leafFolderId: connection.parentFolderId, foldersById: activeFolders) {
            stages.append((folder.name, folder.inheritable))
        }
        stages.append((connection.name, connection.inheritable))

        var resolvedCredId: String? = nil
        var credSource = "None (Direct)"
        var promptAtConnect = false

        var resolvedGatewayId: String? = nil
        var gatewaySource = "Direct"

        var envBadge: String? = nil
        var preTaskId: String? = nil
        var postTaskId: String? = nil
        var accentHex: String? = nil
        var confirmRequired = false
        var customProps: [String: CustomPropertyEntry] = [:]

        for (source, props) in stages {
            switch props.credentialMode {
            case .INHERIT_FROM_PARENT:
                break
            case .OVERRIDE_EXPLICIT:
                resolvedCredId = props.explicitCredentialId
                credSource = source
                promptAtConnect = false
            case .NONE_DIRECT:
                resolvedCredId = nil
                credSource = "None (\(source))"
                promptAtConnect = false
            case .PROMPT_AT_CONNECT:
                resolvedCredId = nil
                credSource = "Prompt (\(source))"
                promptAtConnect = true
            }

            switch props.gatewayMode {
            case .INHERIT_FROM_PARENT:
                break
            case .OVERRIDE_EXPLICIT:
                resolvedGatewayId = props.explicitGatewayId
                gatewaySource = source
            case .NONE_DIRECT, .PROMPT_AT_CONNECT:
                resolvedGatewayId = nil
                gatewaySource = "Direct"
            }

            if let badge = props.environmentBadge?.trimmingCharacters(in: .whitespacesAndNewlines), !badge.isEmpty {
                envBadge = badge
            }
            if let pre = props.preConnectTaskId?.trimmingCharacters(in: .whitespacesAndNewlines), !pre.isEmpty {
                preTaskId = pre
            }
            if let post = props.postDisconnectTaskId?.trimmingCharacters(in: .whitespacesAndNewlines), !post.isEmpty {
                postTaskId = post
            }
            if let hex = props.displayColorAccentHex?.trimmingCharacters(in: .whitespacesAndNewlines), !hex.isEmpty {
                accentHex = hex
            }
            if props.requireConnectConfirmation {
                confirmRequired = true
            }
            for entry in props.customProperties where !entry.key.isEmpty {
                customProps[entry.key] = entry
            }
        }

        return EffectiveConnectionProfile(
            connection: connection,
            effectiveCredential: resolvedCredId.flatMap { credentialsById[$0] },
            credentialInheritedFrom: credSource,
            promptCredentialAtConnect: promptAtConnect,
            effectiveGateway: resolvedGatewayId.flatMap { gatewaysById[$0] },
            gatewayInheritedFrom: gatewaySource,
            effectiveEnvironmentBadge: envBadge,
            preConnectTaskId: preTaskId,
            postDisconnectTaskId: postTaskId,
            displayColorAccentHex: accentHex,
            requireConnectConfirmation: confirmRequired,
            effectiveCustomProperties: customProps
        )
    }

    public static func evaluateSmartFolder(
        _ smartFolder: SmartFolder,
        in document: NxtsVaultDocument
    ) -> [VaultConnection] {
        let f = smartFolder.filter
        let compiledRegex: NSRegularExpression? = {
            guard let pattern = f.hostPatternRegex?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !pattern.isEmpty else { return nil }
            return try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
        }()
        let hasInvalidRegex: Bool = {
            guard let pattern = f.hostPatternRegex?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !pattern.isEmpty else { return false }
            return compiledRegex == nil
        }()
        if hasInvalidRegex { return [] }

        return document.connections
            .filter { !document.tombstonedObjectIds.contains($0.id) }
            .filter { conn in
                if !f.includeProtocols.isEmpty && !f.includeProtocols.contains(conn.protocolType) {
                    return false
                }
                if !f.includeTags.isEmpty {
                    let lowerTags = Set(conn.tags.map { $0.lowercased() })
                    if !f.includeTags.allSatisfy({ lowerTags.contains($0.lowercased()) }) {
                        return false
                    }
                }
                if let osFilter = f.osFamilyFilter?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !osFilter.isEmpty,
                   conn.osFamily.caseInsensitiveCompare(osFilter) != .orderedSame {
                    return false
                }
                if let envFilter = f.environmentFilter?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !envFilter.isEmpty {
                    let resolved = resolve(connection: conn, in: document)
                    guard let badge = resolved.effectiveEnvironmentBadge,
                          badge.caseInsensitiveCompare(envFilter) == .orderedSame else {
                        return false
                    }
                }
                if let regex = compiledRegex {
                    let hostRange = NSRange(conn.host.startIndex..<conn.host.endIndex, in: conn.host)
                    let nameRange = NSRange(conn.name.startIndex..<conn.name.endIndex, in: conn.name)
                    let matchHost = regex.firstMatch(in: conn.host, range: hostRange) != nil
                    let matchName = regex.firstMatch(in: conn.name, range: nameRange) != nil
                    if !matchHost && !matchName { return false }
                }
                return true
            }
    }

    public static func evaluateDynamicFolder(
        _ dynamicFolder: DynamicFolder,
        allowLocalScriptExecution: Bool = false
    ) async -> [VaultConnection] {
        let trimmed = dynamicFolder.scriptOrEndpointPayload.trimmingCharacters(in: .whitespacesAndNewlines)
        var rawJson: String? = nil

        if trimmed.hasPrefix("{") {
            rawJson = trimmed
        } else if dynamicFolder.interpreter == .JSON_HTTP_API,
                  let url = URL(string: trimmed),
                  url.scheme == "https" || url.scheme == "http" {
            if let (data, _) = try? await URLSession.shared.data(from: url) {
                rawJson = String(data: data, encoding: .utf8)
            }
        } else if allowLocalScriptExecution {
            rawJson = runLocalDynamicScript(trimmed, interpreter: dynamicFolder.interpreter)
        }

        guard let rawJson, let data = rawJson.data(using: .utf8) else { return [] }
        return parseRoyalDynamicObjectsJson(data: data, dynamicFolder: dynamicFolder)
    }

    public static func parseRoyalDynamicObjectsJson(
        data: Data,
        dynamicFolder: DynamicFolder
    ) -> [VaultConnection] {
        struct DynamicPayload: Decodable {
            struct Item: Decodable {
                let objectType: String?
                let Name: String?
                let ComputerName: String?
                let URI: String?
                let Port: Int?
                let OS: String?
                let Tags: [String]?

                enum CodingKeys: String, CodingKey {
                    case objectType = "Type"
                    case Name, ComputerName, URI, Port, OS, Tags
                }
            }
            let Objects: [Item]?
        }
        guard let decoded = try? JSONDecoder().decode(DynamicPayload.self, from: data),
              let items = decoded.Objects else { return [] }

        return items.enumerated().compactMap { idx, item in
            let host = (item.ComputerName ?? item.URI ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !host.isEmpty else { return nil }
            let name = item.Name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? host
            let proto: VaultProtocolType = {
                switch (item.objectType ?? "SSH").uppercased() {
                case "RDP", "REMOTE DESKTOP": return .RDP
                case "VNC": return .VNC
                case "ARD", "APPLE_SCREEN_SHARING": return .APPLE_SCREEN_SHARING
                case "SFTP": return .SFTP
                case "HTTPS", "HTTP", "WEB", "WEB_PAGE": return .WEB_PAGE
                default: return .SSH
                }
            }()
            var settings = proto.defaultSettings(host: host)
            if let port = item.Port, port > 0 {
                switch settings {
                case .ssh(var s): s.port = port; settings = .ssh(s)
                case .rdp(var s): s.port = port; settings = .rdp(s)
                case .vnc(var s): s.port = port; settings = .vnc(s)
                case .fileTransfer(var s): s.port = port; settings = .fileTransfer(s)
                default: break
                }
            }
            return VaultConnection(
                id: "dyn-\(dynamicFolder.id)-\(idx)-\(abs(name.hashValue))",
                parentFolderId: dynamicFolder.parentFolderId,
                name: name,
                host: host,
                protocolType: proto,
                osFamily: item.OS ?? "Linux",
                tags: item.Tags ?? ["dynamic"],
                inheritable: dynamicFolder.inheritable,
                settings: settings
            )
        }
    }

    private static func runLocalDynamicScript(
        _ script: String,
        interpreter: DynamicScriptInterpreter
    ) -> String? {
        let proc = Process()
        switch interpreter {
        case .SHELL_SH:
            proc.executableURL = URL(fileURLWithPath: "/bin/sh")
            proc.arguments = ["-c", script]
        case .PYTHON3:
            proc.executableURL = URL(fileURLWithPath: "/usr/bin/env")
            proc.arguments = ["python3", "-c", script]
        case .JSON_HTTP_API:
            return nil
        }
        let pipe = Pipe()
        proc.standardOutput = pipe
        proc.standardError = FileHandle.nullDevice
        do {
            try proc.run()
            proc.waitUntilExit()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            return String(data: data, encoding: .utf8)
        } catch {
            return nil
        }
    }
}

// MARK: - RFC 6238 TOTP Authenticator Generator

public enum TotpCodeGenerator {
    public static func generateTotp(
        base32Secret: String,
        timestamp: Date = Date(),
        periodSeconds: Int = 30,
        digits: Int = 6
    ) -> String? {
        guard let keyBytes = decodeBase32(base32Secret), !keyBytes.isEmpty else {
            return nil
        }
        let counter = UInt64(max(0, Int64(timestamp.timeIntervalSince1970))) / UInt64(max(1, periodSeconds))
        var bigEndianCounter = counter.bigEndian
        let counterData = withUnsafeBytes(of: &bigEndianCounter) { Data($0) }

        let key = SymmetricKey(data: keyBytes)
        let mac = HMAC<Insecure.SHA1>.authenticationCode(for: counterData, using: key)
        let hash = Array(mac)
        guard hash.count >= 20 else { return nil }

        let offset = Int(hash[hash.count - 1] & 0x0F)
        let binary =
            (UInt32(hash[offset] & 0x7F) << 24) |
            (UInt32(hash[offset + 1]) << 16) |
            (UInt32(hash[offset + 2]) << 8) |
            UInt32(hash[offset + 3])

        let mod = UInt32(pow(10.0, Double(digits)))
        let otp = binary % mod
        return String(format: "%0*u", digits, otp)
    }

    public static func secondsRemaining(at date: Date = Date(), periodSeconds: Int = 30) -> Int {
        let epoch = max(0, Int(date.timeIntervalSince1970))
        return periodSeconds - (epoch % max(1, periodSeconds))
    }

    public static func decodeBase32(_ input: String) -> Data? {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
        var lookup: [Character: UInt8] = [:]
        for (idx, ch) in alphabet.enumerated() {
            lookup[ch] = UInt8(idx)
        }
        let cleaned = input
            .uppercased()
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "-", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
        guard !cleaned.isEmpty else { return nil }

        var buffer: UInt32 = 0
        var bitsLeft = 0
        var out = Data()

        for ch in cleaned {
            guard let val = lookup[ch] else { return nil }
            buffer = (buffer << 5) | UInt32(val)
            bitsLeft += 5
            if bitsLeft >= 8 {
                bitsLeft -= 8
                out.append(UInt8((buffer >> bitsLeft) & 0xFF))
            }
        }
        return out
    }
}

// MARK: - Single-Pass Variable Token Expansion & Security-Hardened Key Sequence Engine

public enum MacroStep: Equatable, Sendable {
    case text(String)
    case specialKey(name: String, bytes: Data)
    case wait(milliseconds: UInt64)
}

public enum AutomationTaskEngine {
    private static let tokenRegex: NSRegularExpression = {
        try! NSRegularExpression(
            pattern: #"\$(URI|Name|OS|Notes|EffectiveUsername|EffectiveDomain|EffectivePassword|EffectiveTOTP|CustomProperty\.[A-Za-z0-9_.-]+)\$|\{URI\}"#,
            options: [.caseInsensitive]
        )
    }()

    private static let macroBraceRegex: NSRegularExpression = {
        try! NSRegularExpression(
            pattern: #"\{([A-Z0-9+_]+)(?::(\d+))?\}"#,
            options: []
        )
    }()

    /// Single-pass expansion prevents cross-expansion when a secret contains `$Name$` or `$URI$`.
    public static func expandTokens(
        template: String,
        profile: EffectiveConnectionProfile,
        now: Date = Date()
    ) -> String {
        let nsRange = NSRange(template.startIndex..<template.endIndex, in: template)
        let matches = tokenRegex.matches(in: template, range: nsRange)
        guard !matches.isEmpty else { return template }

        var customCI: [String: String] = [:]
        for (propKey, entry) in profile.effectiveCustomProperties {
            customCI[propKey.lowercased()] = entry.value
        }

        var output = ""
        var cursor = template.startIndex

        for match in matches {
            guard let fullRange = Range(match.range, in: template) else { continue }
            output.append(contentsOf: template[cursor..<fullRange.lowerBound])

            let rawToken = String(template[fullRange])
            let replacement: String
            if rawToken.caseInsensitiveCompare("{URI}") == .orderedSame {
                replacement = profile.connection.host
            } else if let innerRange = Range(match.range(at: 1), in: template) {
                let inner = String(template[innerRange])
                let lower = inner.lowercased()
                switch lower {
                case "uri":
                    replacement = profile.connection.host
                case "name":
                    replacement = profile.connection.name
                case "os":
                    replacement = profile.connection.osFamily
                case "notes":
                    replacement = profile.connection.notes
                case "effectiveusername":
                    replacement = profile.effectiveCredential?.username ?? ""
                case "effectivedomain":
                    replacement = profile.effectiveCredential?.domain ?? ""
                case "effectivepassword":
                    replacement = profile.effectiveCredential?.secretPassword ?? ""
                case "effectivetotp":
                    if let secret = profile.effectiveCredential?.totpSecretBase32, !secret.isEmpty {
                        replacement = TotpCodeGenerator.generateTotp(base32Secret: secret, timestamp: now) ?? ""
                    } else {
                        replacement = ""
                    }
                default:
                    if lower.hasPrefix("customproperty.") {
                        let propKey = String(lower.dropFirst("customproperty.".count))
                        replacement = customCI[propKey] ?? ""
                    } else {
                        replacement = rawToken
                    }
                }
            } else {
                replacement = rawToken
            }

            output.append(replacement)
            cursor = fullRange.upperBound
        }

        output.append(contentsOf: template[cursor..<template.endIndex])
        return output
    }

    /// Parses `{SPECIAL_KEY}` and `{WAIT:ms}` BEFORE expanding `$...$` variables so literal braces inside
    /// `$EffectivePassword$` or `$Notes$` can never inject control keystrokes.
    public static func compileKeySequence(
        macroSequence: String,
        profile: EffectiveConnectionProfile,
        now: Date = Date()
    ) -> [MacroStep] {
        let nsRange = NSRange(macroSequence.startIndex..<macroSequence.endIndex, in: macroSequence)
        let matches = macroBraceRegex.matches(in: macroSequence, range: nsRange)

        var steps: [MacroStep] = []
        var cursor = macroSequence.startIndex

        for match in matches {
            guard let fullRange = Range(match.range, in: macroSequence),
                  let nameRange = Range(match.range(at: 1), in: macroSequence) else { continue }

            let keyToken = String(macroSequence[nameRange])
            if keyToken == "URI" {
                continue
            }

            if cursor < fullRange.lowerBound {
                let literalSegment = String(macroSequence[cursor..<fullRange.lowerBound])
                let expanded = expandTokens(template: literalSegment, profile: profile, now: now)
                if !expanded.isEmpty {
                    steps.append(.text(expanded))
                }
            }

            if keyToken == "WAIT" {
                var waitMs: UInt64 = 250
                if match.range(at: 2).location != NSNotFound,
                   let msRange = Range(match.range(at: 2), in: macroSequence),
                   let parsed = UInt64(macroSequence[msRange]) {
                    waitMs = parsed
                }
                steps.append(.wait(milliseconds: waitMs))
            } else if let specialBytes = encodeSpecialKey(keyToken) {
                steps.append(.specialKey(name: keyToken, bytes: specialBytes))
            } else {
                let rawLiteral = String(macroSequence[fullRange])
                steps.append(.text(expandTokens(template: rawLiteral, profile: profile, now: now)))
            }

            cursor = fullRange.upperBound
        }

        if cursor < macroSequence.endIndex {
            let tail = String(macroSequence[cursor..<macroSequence.endIndex])
            let expanded = expandTokens(template: tail, profile: profile, now: now)
            if !expanded.isEmpty {
                steps.append(.text(expanded))
            }
        }

        return steps
    }

    private static func encodeSpecialKey(_ key: String) -> Data? {
        switch key {
        case "ENTER", "RETURN": return Data([0x0D])
        case "TAB": return Data([0x09])
        case "ESC", "ESCAPE": return Data([0x1B])
        case "SPACE": return Data([0x20])
        case "BACKSPACE", "BKSP": return Data([0x7F])
        case "DELETE", "DEL": return Data("\u{1B}[3~".utf8)
        case "UP": return Data("\u{1B}[A".utf8)
        case "DOWN": return Data("\u{1B}[B".utf8)
        case "RIGHT": return Data("\u{1B}[C".utf8)
        case "LEFT": return Data("\u{1B}[D".utf8)
        case "HOME": return Data("\u{1B}[H".utf8)
        case "END": return Data("\u{1B}[F".utf8)
        case "PGUP": return Data("\u{1B}[5~".utf8)
        case "PGDN": return Data("\u{1B}[6~".utf8)
        case "F1": return Data("\u{1B}OP".utf8)
        case "F2": return Data("\u{1B}OQ".utf8)
        case "F3": return Data("\u{1B}OR".utf8)
        case "F4": return Data("\u{1B}OS".utf8)
        case "F5": return Data("\u{1B}[15~".utf8)
        case "F6": return Data("\u{1B}[17~".utf8)
        case "F7": return Data("\u{1B}[18~".utf8)
        case "F8": return Data("\u{1B}[19~".utf8)
        case "F9": return Data("\u{1B}[20~".utf8)
        case "F10": return Data("\u{1B}[21~".utf8)
        case "F11": return Data("\u{1B}[23~".utf8)
        case "F12": return Data("\u{1B}[24~".utf8)
        case "CTRL+ALT+DEL": return Data([0x1B, 0x7F])
        default:
            if key.hasPrefix("CTRL+"), key.count == 6, let ascii = key.last?.asciiValue, ascii >= 65, ascii <= 90 {
                return Data([ascii - 64])
            }
            if key.hasPrefix("ALT+"), key.count == 5, let ch = key.last {
                return Data("\u{1B}\(ch.lowercased())".utf8)
            }
            return nil
        }
    }
}
