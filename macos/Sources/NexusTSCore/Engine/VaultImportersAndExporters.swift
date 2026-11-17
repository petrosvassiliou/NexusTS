import Compression
import Foundation

// MARK: - Enterprise Multi-Format Importer (.nxts, Royal TS .rtsz/.rtsx, KeePass XML, Bitwarden, 1Password, CSV)

public struct ImportedVaultSummary: Equatable, Sendable {
    public let document: NxtsVaultDocument
    public let detectedFormat: String
    public let importedConnections: Int
    public let importedFolders: Int
    public let importedCredentials: Int

    public var isRoyalTsFormat: Bool {
        let lower = detectedFormat.lowercased()
        return lower.contains("royal ts") || lower.contains("royal json") || lower.contains(".rtsz") || lower.contains(".rtsx") || lower.contains(".rjson")
    }
}

public enum EnterpriseVaultFormatImporter {
    public static func importData(
        _ data: Data,
        fileName: String = "import",
        passphrase: String = ""
    ) throws -> ImportedVaultSummary {
        let lowerExt = (fileName as NSString).pathExtension.lowercased()

        // 1. Native .nxts v2 binary envelope
        if NxtsVaultSerializer.hasNxtsMagic(data) || lowerExt == "nxts" {
            let doc = VaultHierarchySanitizer.sanitizeVaultDocument(
                try NxtsVaultSerializer.open(sealedData: data, passphrase: passphrase)
            )
            return ImportedVaultSummary(
                document: doc,
                detectedFormat: "NexusTS v2 (.nxts)",
                importedConnections: doc.connections.count,
                importedFolders: doc.folders.count,
                importedCredentials: doc.credentials.count
            )
        }

        // 2. ZIP container (.rtsz or .1pux)
        if data.count >= 4 && Array(data.prefix(4)) == [0x50, 0x4B, 0x03, 0x04] {
            if let extractedOnePux = extractZipEntry(data: data, matchingSuffix: "export.data") {
                return try importOnePasswordJson(extractedOnePux, label: "1Password (.1pux)")
            }
            if let extractedRtsx = extractZipEntry(data: data, matchingSuffix: ".rtsx")
                ?? extractZipEntry(data: data, matchingSuffix: ".xml") {
                return try importRoyalTsXml(extractedRtsx, vaultName: (fileName as NSString).deletingPathExtension)
            }
            if lowerExt == "rtsz" {
                throw NativeBridgeError.serializationFailed("Royal TS .rtsz archive could not be extracted or is encrypted with an unsupported legacy cipher.")
            }
        }

        // 3. GZIP container (.rtsz GZIP variant)
        if data.count >= 2 && Array(data.prefix(2)) == [0x1F, 0x8B],
           let gunzipped = decompressGzipViaTool(data) {
            return try importRoyalTsXml(gunzipped, vaultName: (fileName as NSString).deletingPathExtension)
        }

        let text = decodeFlexibleUtfText(data).trimmingCharacters(in: .whitespacesAndNewlines)

        // 4. XML formats (Royal TS .rtsx vs KeePass 2.x XML)
        if text.hasPrefix("<") || lowerExt == "rtsx" || lowerExt == "xml" {
            if text.contains("<KeePassFile") {
                return importKeePassXml(Data(text.utf8), vaultName: (fileName as NSString).deletingPathExtension)
            }
            return try importRoyalTsXml(Data(text.utf8), vaultName: (fileName as NSString).deletingPathExtension)
        }

        // 5. JSON formats (Bitwarden, 1Password, Royal JSON, or raw NxtsVaultDocument JSON)
        if text.hasPrefix("{") {
            let utf8 = Data(text.utf8)
            if text.contains("\"documentId\"") && text.contains("\"schemaVersion\"") {
                let doc = VaultHierarchySanitizer.sanitizeVaultDocument(
                    try JSONDecoder().decode(NxtsVaultDocument.self, from: utf8)
                )
                return ImportedVaultSummary(
                    document: doc,
                    detectedFormat: "NexusTS JSON",
                    importedConnections: doc.connections.count,
                    importedFolders: doc.folders.count,
                    importedCredentials: doc.credentials.count
                )
            }
            if text.contains("\"items\"") && (text.contains("\"encrypted\"") || text.contains("\"login\"")) {
                return try importBitwardenJson(utf8)
            }
            if text.contains("\"accounts\"") && text.contains("\"vaults\"") {
                return try importOnePasswordJson(utf8, label: "1Password JSON")
            }
            if text.contains("\"Objects\"") || lowerExt == "rjson" {
                let dyn = DynamicFolder(name: "Royal JSON", scriptOrEndpointPayload: text)
                let conns = PropertyInheritanceResolver.parseRoyalDynamicObjectsJson(data: utf8, dynamicFolder: dyn)
                let doc = NxtsVaultDocument(name: (fileName as NSString).deletingPathExtension, connections: conns)
                return ImportedVaultSummary(
                    document: doc,
                    detectedFormat: "Royal JSON (.rjson)",
                    importedConnections: conns.count,
                    importedFolders: 0,
                    importedCredentials: 0
                )
            }
        }

        // 6. RFC 4180 CSV Inventory
        return importCsvInventory(text: text, vaultName: (fileName as NSString).deletingPathExtension)
    }

    // MARK: Royal TS .rtsx XML Parser

    public static func importRoyalTsXml(_ xmlData: Data, vaultName: String) throws -> ImportedVaultSummary {
        let text = String(data: xmlData, encoding: .utf8) ?? ""
        if !text.contains("<RoyalDocument") && !text.contains("<RTSZDocument") && !text.contains("<RoyalFolder") {
            throw NativeBridgeError.serializationFailed("Selected XML file is not a valid Royal TS (.rtsx / .rtsz) document.")
        }
        let delegate = RoyalTsXmlSAXParser()
        let parser = XMLParser(data: xmlData)
        parser.shouldResolveExternalEntities = false
        parser.delegate = delegate
        guard parser.parse() || !delegate.hasNoCompletedNodes else {
            throw NativeBridgeError.serializationFailed(
                parser.parserError?.localizedDescription ?? "Malformed XML in Royal TS document."
            )
        }

        let rawDoc = delegate.buildVaultDocument(fallbackName: vaultName.isEmpty ? "Imported Royal TS Vault" : vaultName)
        let doc = RoyalTsImportMerger.normalizeImportedRoyalDocument(rawDoc, unwrapBuiltInConnectionsRoot: true)
        return ImportedVaultSummary(
            document: doc,
            detectedFormat: "Royal TS (.rtsx/.rtsz)",
            importedConnections: doc.connections.count,
            importedFolders: doc.folders.count,
            importedCredentials: doc.credentials.count
        )
    }

    // MARK: KeePass 2.x XML Importer

    public static func importKeePassXml(_ xmlData: Data, vaultName: String) -> ImportedVaultSummary {
        let delegate = KeePassXmlSAXParser()
        let parser = XMLParser(data: xmlData)
        parser.shouldResolveExternalEntities = false
        parser.delegate = delegate
        _ = parser.parse()

        let doc = delegate.buildDocument(vaultName: vaultName.isEmpty ? "KeePass Imported Vault" : vaultName)
        return ImportedVaultSummary(
            document: doc,
            detectedFormat: "KeePass 2.x XML",
            importedConnections: doc.connections.count,
            importedFolders: doc.folders.count,
            importedCredentials: doc.credentials.count
        )
    }

    // MARK: Bitwarden JSON Importer

    public static func importBitwardenJson(_ jsonData: Data) throws -> ImportedVaultSummary {
        struct BwFolder: Decodable { let id: String; let name: String }
        struct BwUri: Decodable { let uri: String? }
        struct BwLogin: Decodable {
            let username: String?
            let password: String?
            let totp: String?
            let uris: [BwUri]?
        }
        struct BwItem: Decodable {
            let id: String?
            let folderId: String?
            let name: String?
            let notes: String?
            let favorite: Bool?
            let login: BwLogin?
        }
        struct BwRoot: Decodable {
            let folders: [BwFolder]?
            let items: [BwItem]?
        }

        let root = try JSONDecoder().decode(BwRoot.self, from: jsonData)
        let folders = (root.folders ?? []).map { VaultFolder(id: $0.id, name: $0.name) }
        var creds: [VaultCredential] = []
        var conns: [VaultConnection] = []

        for item in root.items ?? [] {
            guard let login = item.login else { continue }
            let itemId = item.id ?? UUID().uuidString.lowercased()
            let title = item.name ?? "Bitwarden Entry"
            let credId = "cred-\(itemId)"
            creds.append(VaultCredential(
                id: credId,
                parentFolderId: item.folderId,
                name: title,
                username: login.username ?? "",
                secretPassword: login.password ?? "",
                totpSecretBase32: login.totp ?? ""
            ))
            if let rawUri = login.uris?.first?.uri, !rawUri.isEmpty {
                let parsed = QuickConnectParser.parseAdHocUri(rawUri)
                var inheritable = InheritableProperties(
                    credentialMode: .OVERRIDE_EXPLICIT,
                    explicitCredentialId: credId
                )
                inheritable.environmentBadge = nil
                conns.append(VaultConnection(
                    id: "conn-\(itemId)",
                    parentFolderId: item.folderId,
                    name: title,
                    host: parsed?.host ?? rawUri,
                    protocolType: parsed?.protocolType ?? .WEB_PAGE,
                    notes: item.notes ?? "",
                    favorite: item.favorite ?? false,
                    inheritable: inheritable,
                    settings: parsed?.settings ?? VaultProtocolType.WEB_PAGE.defaultSettings(host: rawUri)
                ))
            }
        }

        let doc = NxtsVaultDocument(
            name: "Bitwarden Imported Vault",
            folders: folders,
            connections: conns,
            credentials: creds
        )
        return ImportedVaultSummary(
            document: doc,
            detectedFormat: "Bitwarden JSON",
            importedConnections: conns.count,
            importedFolders: folders.count,
            importedCredentials: creds.count
        )
    }

    // MARK: 1Password .1pux / JSON Importer

    public static func importOnePasswordJson(_ jsonData: Data, label: String) throws -> ImportedVaultSummary {
        struct OpField: Decodable {
            let designation: String?
            let id: String?
            let value: OpFieldValue?
        }
        struct OpFieldValue: Decodable {
            let stringValue: String?
            init(from decoder: Decoder) throws {
                let c = try decoder.singleValueContainer()
                if let s = try? c.decode(String.self) {
                    self.stringValue = s
                } else if let dict = try? c.decode([String: String].self) {
                    self.stringValue = dict["totp"] ?? dict.values.first
                } else {
                    self.stringValue = nil
                }
            }
        }
        struct OpUrl: Decodable { let url: String? }
        struct OpOverview: Decodable {
            let title: String?
            let url: String?
            let urls: [OpUrl]?
        }
        struct OpDetails: Decodable {
            let loginFields: [OpField]?
            let notesPlain: String?
        }
        struct OpItem: Decodable {
            let uuid: String?
            let overview: OpOverview?
            let details: OpDetails?
        }
        struct OpVault: Decodable {
            let attrs: [String: String]?
            let items: [OpItem]?
        }
        struct OpAccount: Decodable {
            let vaults: [OpVault]?
        }
        struct OpRoot: Decodable {
            let accounts: [OpAccount]?
        }

        let root = try JSONDecoder().decode(OpRoot.self, from: jsonData)
        var folders: [VaultFolder] = []
        var creds: [VaultCredential] = []
        var conns: [VaultConnection] = []

        for acct in root.accounts ?? [] {
            for vault in acct.vaults ?? [] {
                let folderId = UUID().uuidString.lowercased()
                let vName = vault.attrs?["name"] ?? "1Password Vault"
                folders.append(VaultFolder(id: folderId, name: vName))

                for item in vault.items ?? [] {
                    let id = item.uuid ?? UUID().uuidString.lowercased()
                    let title = item.overview?.title ?? "1Password Item"
                    var username = ""
                    var password = ""
                    for f in item.details?.loginFields ?? [] {
                        if f.designation == "username" { username = f.value?.stringValue ?? "" }
                        if f.designation == "password" { password = f.value?.stringValue ?? "" }
                    }
                    let credId = "cred-\(id)"
                    creds.append(VaultCredential(
                        id: credId,
                        parentFolderId: folderId,
                        name: title,
                        username: username,
                        secretPassword: password
                    ))
                    let rawUrl = item.overview?.url ?? item.overview?.urls?.first?.url ?? ""
                    if !rawUrl.isEmpty {
                        let parsed = QuickConnectParser.parseAdHocUri(rawUrl)
                        conns.append(VaultConnection(
                            id: "conn-\(id)",
                            parentFolderId: folderId,
                            name: title,
                            host: parsed?.host ?? rawUrl,
                            protocolType: parsed?.protocolType ?? .WEB_PAGE,
                            notes: item.details?.notesPlain ?? "",
                            inheritable: InheritableProperties(
                                credentialMode: .OVERRIDE_EXPLICIT,
                                explicitCredentialId: credId
                            ),
                            settings: parsed?.settings ?? VaultProtocolType.WEB_PAGE.defaultSettings(host: rawUrl)
                        ))
                    }
                }
            }
        }

        let doc = NxtsVaultDocument(
            name: "1Password Imported Vault",
            folders: folders,
            connections: conns,
            credentials: creds
        )
        return ImportedVaultSummary(
            document: doc,
            detectedFormat: label,
            importedConnections: conns.count,
            importedFolders: folders.count,
            importedCredentials: creds.count
        )
    }

    // MARK: RFC 4180 CSV Importer

    public static func importCsvInventory(text: String, vaultName: String) -> ImportedVaultSummary {
        let rows = parseRfc4180Csv(text)
        guard rows.count >= 2 else {
            return ImportedVaultSummary(
                document: NxtsVaultDocument(name: vaultName.isEmpty ? "CSV Vault" : vaultName),
                detectedFormat: "RFC 4180 CSV",
                importedConnections: 0,
                importedFolders: 0,
                importedCredentials: 0
            )
        }
        let header = rows[0].map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
        func col(_ names: [String], in row: [String]) -> String {
            for n in names {
                if let idx = header.firstIndex(of: n), idx < row.count {
                    return row[idx].trimmingCharacters(in: .whitespacesAndNewlines)
                }
            }
            return ""
        }

        var foldersByName: [String: VaultFolder] = [:]
        var conns: [VaultConnection] = []

        for row in rows.dropFirst() {
            let host = col(["host", "hostname", "uri", "ip", "computername"], in: row)
            guard !host.isEmpty else { continue }
            let name = {
                let n = col(["name", "title", "displayname"], in: row)
                return n.isEmpty ? host : n
            }()
            let protoRaw = col(["protocol", "type"], in: row).uppercased()
            let proto = VaultProtocolType(rawValue: protoRaw) ?? .SSH
            let portVal = Int(col(["port"], in: row)) ?? proto.defaultPort
            let folderName = col(["folder", "group"], in: row)
            var parentId: String? = nil
            if !folderName.isEmpty {
                if let existing = foldersByName[folderName] {
                    parentId = existing.id
                } else {
                    let f = VaultFolder(name: folderName)
                    foldersByName[folderName] = f
                    parentId = f.id
                }
            }
            let osFamily = {
                let o = col(["osfamily", "os"], in: row)
                return o.isEmpty ? "Linux" : o
            }()
            let fav = ["true", "1", "yes"].contains(col(["favorite"], in: row).lowercased())
            let tags = col(["tags"], in: row)
                .split(separator: ";")
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
            let colorHex = col(["colorhex", "color"], in: row)
            let notes = col(["notes", "description"], in: row)
            let connId = {
                let c = col(["connectionid", "id"], in: row)
                return c.isEmpty ? UUID().uuidString.lowercased() : c
            }()

            var settings = proto.defaultSettings(host: host)
            if portVal > 0 {
                switch settings {
                case .ssh(var s): s.port = portVal; settings = .ssh(s)
                case .rdp(var s): s.port = portVal; settings = .rdp(s)
                case .vnc(var s): s.port = portVal; settings = .vnc(s)
                case .fileTransfer(var s): s.port = portVal; settings = .fileTransfer(s)
                default: break
                }
            }

            let cluster = col(["containercluster"], in: row)
            if !cluster.isEmpty {
                var cExec = ContainerExecProtocolSettings(
                    clusterOrSocketEndpoint: cluster,
                    podOrContainerName: col(["containerworkload"], in: row)
                )
                cExec.contextName = col(["containercontext"], in: row)
                cExec.namespace = col(["containernamespace"], in: row)
                cExec.subContainerName = col(["containersubcontainer"], in: row)
                let sh = col(["containershell"], in: row)
                if !sh.isEmpty { cExec.shellCommand = sh }
                cExec.followLogsOnOpen = col(["containerfollowlogs"], in: row).lowercased() == "true"
                settings = .containerExec(cExec)
            }

            conns.append(VaultConnection(
                id: connId,
                parentFolderId: parentId,
                name: name,
                host: host,
                protocolType: proto,
                osFamily: osFamily,
                tags: tags,
                notes: notes,
                favorite: fav,
                inheritable: InheritableProperties(
                    displayColorAccentHex: colorHex.isEmpty ? nil : colorHex
                ),
                settings: settings
            ))
        }

        let doc = NxtsVaultDocument(
            name: vaultName.isEmpty ? "CSV Imported Vault" : vaultName,
            folders: Array(foldersByName.values),
            connections: conns
        )
        return ImportedVaultSummary(
            document: doc,
            detectedFormat: "RFC 4180 CSV",
            importedConnections: conns.count,
            importedFolders: doc.folders.count,
            importedCredentials: 0
        )
    }

    // MARK: Helper Parsers

    public static func parseRfc4180Csv(_ text: String) -> [[String]] {
        var rows: [[String]] = []
        var currentRow: [String] = []
        var field = ""
        var inQuotes = false
        let chars = Array(text)
        var i = 0

        while i < chars.count {
            let c = chars[i]
            if inQuotes {
                if c == "\"" {
                    if i + 1 < chars.count && chars[i + 1] == "\"" {
                        field.append("\"")
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                if c == "\"" {
                    inQuotes = true
                } else if c == "," {
                    currentRow.append(field)
                    field = ""
                } else if c == "\r" {
                    if i + 1 < chars.count && chars[i + 1] == "\n" { i += 1 }
                    currentRow.append(field)
                    field = ""
                    if !currentRow.allSatisfy({ $0.isEmpty }) {
                        rows.append(currentRow)
                    }
                    currentRow = []
                } else if c == "\n" {
                    currentRow.append(field)
                    field = ""
                    if !currentRow.allSatisfy({ $0.isEmpty }) {
                        rows.append(currentRow)
                    }
                    currentRow = []
                } else {
                    field.append(c)
                }
            }
            i += 1
        }
        if !field.isEmpty || !currentRow.isEmpty {
            currentRow.append(field)
            if !currentRow.allSatisfy({ $0.isEmpty }) {
                rows.append(currentRow)
            }
        }
        return rows
    }

    private static func decodeFlexibleUtfText(_ data: Data) -> String {
        if data.count >= 2 {
            let b0 = data[data.startIndex]
            let b1 = data[data.index(after: data.startIndex)]
            if b0 == 0xFF && b1 == 0xFE {
                return String(data: data.dropFirst(2), encoding: .utf16LittleEndian) ?? ""
            }
            if b0 == 0xFE && b1 == 0xFF {
                return String(data: data.dropFirst(2), encoding: .utf16BigEndian) ?? ""
            }
        }
        return String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .isoLatin1)
            ?? ""
    }

    private static func extractZipEntry(data: Data, matchingSuffix: String) -> Data? {
        let fm = FileManager.default
        let tempDir = fm.temporaryDirectory.appendingPathComponent("nxts_zip_\(UUID().uuidString)")
        defer { try? fm.removeItem(at: tempDir) }
        do {
            try fm.createDirectory(at: tempDir, withIntermediateDirectories: true)
            let archiveFile = tempDir.appendingPathComponent("archive.zip")
            try data.write(to: archiveFile)
            let outDir = tempDir.appendingPathComponent("out")
            try fm.createDirectory(at: outDir, withIntermediateDirectories: true)

            let proc = Process()
            proc.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
            proc.arguments = ["-qq", "-o", archiveFile.path, "-d", outDir.path]
            try proc.run()
            proc.waitUntilExit()

            if let enumerator = fm.enumerator(at: outDir, includingPropertiesForKeys: nil) {
                for case let url as URL in enumerator {
                    if url.lastPathComponent.lowercased().hasSuffix(matchingSuffix.lowercased()) {
                        return try? Data(contentsOf: url)
                    }
                }
            }
        } catch {
            return nil
        }
        return nil
    }

    private static func decompressGzipViaTool(_ data: Data) -> Data? {
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/usr/bin/gunzip")
        proc.arguments = ["-c"]
        let inPipe = Pipe()
        let outPipe = Pipe()
        proc.standardInput = inPipe
        proc.standardOutput = outPipe
        proc.standardError = FileHandle.nullDevice
        do {
            try proc.run()
            inPipe.fileHandleForWriting.write(data)
            try? inPipe.fileHandleForWriting.close()
            let out = outPipe.fileHandleForReading.readDataToEndOfFile()
            proc.waitUntilExit()
            return out.isEmpty ? nil : out
        } catch {
            return nil
        }
    }
}

// MARK: - Royal TS .rtsx SAX Delegate

private final class RoyalTsXmlSAXParser: NSObject, XMLParserDelegate {
    struct RawNode {
        var elementType: String
        var fields: [String: String] = [:]
    }

    private static let objectTags: Set<String> = [
        "RoyalDocument", "RoyalFolder", "RoyalSmartFolder", "RoyalDynamicFolder", "RoyalTrash",
        "RoyalRDSConnection", "RoyalSSHConnection", "RoyalVNCConnection",
        "RoyalFileTransferConnection", "RoyalWebPageConnection", "RoyalCredential",
        "RoyalHyperVConnection", "RoyalVMwareConnection", "RoyalProxmoxConnection",
        "RoyalWindowsTerminalServicesConnection", "RoyalTerminalServicesConnection"
    ]

    private var stack: [RawNode] = []
    private var completedNodes: [RawNode] = []
    private var currentText = ""

    var hasNoCompletedNodes: Bool { completedNodes.isEmpty }

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        currentText = ""
        if Self.objectTags.contains(elementName) {
            stack.append(RawNode(elementType: elementName))
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        currentText.append(string)
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        if Self.objectTags.contains(elementName), var finished = stack.popLast() {
            if finished.fields["ParentID"] == nil, let parent = stack.last, let pid = parent.fields["ID"] {
                finished.fields["ParentID"] = pid
            }
            if stack.contains(where: { $0.elementType == "RoyalTrash" }) || elementName == "RoyalTrash" {
                return
            }
            completedNodes.append(finished)
        } else if !stack.isEmpty {
            let trimmed = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                stack[stack.count - 1].fields[elementName] = trimmed
            }
        }
        currentText = ""
    }

    func buildVaultDocument(fallbackName: String) -> NxtsVaultDocument {
        var docName = fallbackName
        var docId = UUID().uuidString.lowercased()
        var folders: [VaultFolder] = []
        var smartFolders: [SmartFolder] = []
        var dynamicFolders: [DynamicFolder] = []
        var creds: [VaultCredential] = []
        var conns: [VaultConnection] = []

        for node in completedNodes where node.elementType == "RoyalDocument" {
            if let n = node.fields["Name"], !n.isEmpty { docName = n }
            if let id = node.fields["ID"], !id.isEmpty { docId = id }
        }

        let rootIds = Set(completedNodes.filter { $0.elementType == "RoyalDocument" }.compactMap { $0.fields["ID"] })

        for (seqIndex, node) in completedNodes.enumerated() {
            let id = node.fields["ID"] ?? UUID().uuidString.lowercased()
            let rawParent = node.fields["ParentID"]
            let parentFolderId = (rawParent == nil || rootIds.contains(rawParent!)) ? nil : rawParent
            let name = node.fields["Name"] ?? "Unnamed"
            let sortOrder = Int(node.fields["SortOrder"] ?? node.fields["PositionNr"] ?? "") ?? seqIndex
            let parsedTags = (node.fields["Tags"] ?? "")
                .split(whereSeparator: { $0 == ";" || $0 == "," })
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }

            func parseInheritable() -> InheritableProperties {
                let modeInt = Int(node.fields["CredentialMode"] ?? "1") ?? 1
                let mode: InheritanceMode = {
                    switch modeInt {
                    case 0: return .NONE_DIRECT
                    case 1: return .INHERIT_FROM_PARENT
                    case 2, 3: return .OVERRIDE_EXPLICIT
                    case 4: return .PROMPT_AT_CONNECT
                    default: return .INHERIT_FROM_PARENT
                    }
                }()
                var custom: [CustomPropertyEntry] = []
                for idx in 1...20 {
                    if let val = node.fields["CustomField\(idx)"], !val.isEmpty {
                        custom.append(CustomPropertyEntry(
                            key: "CustomField\(idx)",
                            value: val,
                            isProtected: idx >= 11
                        ))
                    }
                }
                return InheritableProperties(
                    credentialMode: mode,
                    explicitCredentialId: node.fields["CredentialId"] ?? node.fields["CredentialID"],
                    environmentBadge: node.fields["Environment"],
                    displayColorAccentHex: node.fields["Color"],
                    customProperties: custom
                )
            }

            switch node.elementType {
            case "RoyalFolder":
                folders.append(VaultFolder(
                    id: id,
                    parentFolderId: parentFolderId,
                    name: name,
                    description: node.fields["Description"] ?? "",
                    tags: parsedTags,
                    inheritable: parseInheritable(),
                    sortOrder: sortOrder
                ))
            case "RoyalSmartFolder":
                let includeTags = (node.fields["IncludeTags"] ?? node.fields["Tags"] ?? "")
                    .split(whereSeparator: { $0 == ";" || $0 == "," })
                    .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }
                let excludeTags = (node.fields["ExcludeTags"] ?? "")
                    .split(whereSeparator: { $0 == ";" || $0 == "," })
                    .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }
                let protoList = (node.fields["Protocols"] ?? "")
                    .split(whereSeparator: { $0 == ";" || $0 == "," })
                    .compactMap { VaultProtocolType(rawValue: $0.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()) }
                smartFolders.append(SmartFolder(
                    id: id,
                    name: name,
                    filter: SmartFolderFilter(
                        includeTags: includeTags,
                        excludeTags: excludeTags,
                        includeProtocols: protoList,
                        osFamilyFilter: node.fields["OSFamily"],
                        hostPatternRegex: node.fields["HostPattern"] ?? node.fields["FilterPattern"],
                        environmentFilter: node.fields["Environment"],
                        scopeFolderId: node.fields["ScopeFolderId"]
                    )
                ))
            case "RoyalDynamicFolder":
                let interpRaw = (node.fields["ScriptInterpreter"] ?? "JSON_HTTP_API").uppercased()
                let interp: DynamicScriptInterpreter = {
                    if interpRaw.contains("PYTHON") { return .PYTHON3 }
                    if interpRaw.contains("SH") || interpRaw.contains("BASH") { return .SHELL_SH }
                    return .JSON_HTTP_API
                }()
                dynamicFolders.append(DynamicFolder(
                    id: id,
                    parentFolderId: parentFolderId,
                    name: name,
                    interpreter: interp,
                    scriptOrEndpointPayload: node.fields["Script"] ?? "{\"Objects\":[]}",
                    inheritable: parseInheritable()
                ))
            case "RoyalCredential":
                creds.append(VaultCredential(
                    id: id,
                    parentFolderId: nil,
                    name: name,
                    username: node.fields["UserName"] ?? "",
                    domain: node.fields["Domain"] ?? "",
                    secretPassword: node.fields["Password"] ?? "",
                    privateKeyPem: node.fields["KeyFileContent"] ?? ""
                ))
            case "RoyalHyperVConnection":
                let rawUri = node.fields["URI"] ?? ""
                let apiUrl: String = {
                    if let explicit = node.fields["ApiEndpointUrl"] { return explicit }
                    if rawUri.lowercased().hasPrefix("http://") || rawUri.lowercased().hasPrefix("https://") { return rawUri }
                    return ""
                }()
                var hvSettings = CloudVirtualizationProtocolSettings(apiEndpointUrl: apiUrl)
                hvSettings.regionOrCluster = node.fields["RegionOrCluster"] ?? ""
                hvSettings.subscriptionOrAccountId = node.fields["SubscriptionOrAccountId"] ?? ""
                hvSettings.realmOrDomain = node.fields["RealmOrDomain"] ?? "pam"
                if let autoSync = node.fields["AutoSyncDynamicInventory"] {
                    hvSettings.autoSyncDynamicInventory = autoSync.lowercased() == "true"
                }
                conns.append(VaultConnection(
                    id: id,
                    parentFolderId: parentFolderId,
                    name: name,
                    host: rawUri,
                    protocolType: .HYPER_V,
                    osFamily: "Windows",
                    tags: parsedTags,
                    notes: node.fields["Description"] ?? "",
                    favorite: (node.fields["Favorite"] ?? "").lowercased() == "true",
                    sortOrder: sortOrder,
                    inheritable: parseInheritable(),
                    settings: .cloudVirtualization(hvSettings)
                ))
            case "RoyalVMwareConnection", "RoyalProxmoxConnection":
                let isPve = node.elementType == "RoyalProxmoxConnection" || (node.fields["NexusProtocolType"] ?? "").uppercased() == "PROXMOX_VE"
                let proto: VaultProtocolType = isPve ? .PROXMOX_VE : .VMWARE_VSPHERE
                let rawUri = node.fields["URI"] ?? ""
                let apiUrl = node.fields["ApiEndpointUrl"]
                    ?? ((rawUri.lowercased().hasPrefix("http://") || rawUri.lowercased().hasPrefix("https://"))
                        ? rawUri
                        : (rawUri.isEmpty ? "" : "https://\(rawUri):\(proto.defaultPort)"))
                var virt = CloudVirtualizationProtocolSettings(apiEndpointUrl: apiUrl)
                virt.regionOrCluster = node.fields["RegionOrCluster"] ?? ""
                virt.realmOrDomain = node.fields["RealmOrDomain"] ?? "pam"
                conns.append(VaultConnection(
                    id: id,
                    parentFolderId: parentFolderId,
                    name: name,
                    host: rawUri,
                    protocolType: proto,
                    osFamily: "Linux",
                    tags: parsedTags,
                    notes: node.fields["Description"] ?? "",
                    favorite: (node.fields["Favorite"] ?? "").lowercased() == "true",
                    sortOrder: sortOrder,
                    inheritable: parseInheritable(),
                    settings: .cloudVirtualization(virt)
                ))
            case "RoyalRDSConnection", "RoyalWindowsTerminalServicesConnection", "RoyalTerminalServicesConnection":
                let hyperVMode = (node.fields["HyperVMode"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                let vmGuid = (node.fields["HyperVInstance"] ?? node.fields["VMId"] ?? node.fields["InstanceID"] ?? "")
                    .trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "{}\"'")))
                let rawUri = node.fields["URI"] ?? ""

                if hyperVMode == "1" || (hyperVMode.lowercased() == "true" && vmGuid.isEmpty) {
                    let apiUrl = node.fields["ApiEndpointUrl"]
                        ?? ((rawUri.lowercased().hasPrefix("http://") || rawUri.lowercased().hasPrefix("https://")) ? rawUri : "")
                    conns.append(VaultConnection(
                        id: id,
                        parentFolderId: parentFolderId,
                        name: name,
                        host: rawUri,
                        protocolType: .HYPER_V,
                        osFamily: "Windows",
                        tags: parsedTags,
                        notes: node.fields["Description"] ?? "",
                        favorite: (node.fields["Favorite"] ?? "").lowercased() == "true",
                        sortOrder: sortOrder,
                        inheritable: parseInheritable(),
                        settings: .cloudVirtualization(CloudVirtualizationProtocolSettings(apiEndpointUrl: apiUrl))
                    ))
                } else {
                    var rdp = RdpProtocolSettings()
                    let isHyperVVm = hyperVMode == "2" || !vmGuid.isEmpty
                    if isHyperVVm {
                        rdp.hyperVVmId = vmGuid
                        let enhRaw = (node.fields["HyperVEnhancedSessionMode"] ?? node.fields["EnhancedSessionMode"] ?? "").lowercased()
                        rdp.hyperVEnhancedSession = enhRaw == "true" || enhRaw == "1"
                        rdp.port = Int(node.fields["HyperVPort"] ?? node.fields["RDPPort"] ?? node.fields["Port"] ?? "2179") ?? 2179
                        let explicitIgnore = node.fields["IgnoreCertificateWarning"] ?? node.fields["IgnoreCertificate"]
                        rdp.ignoreCertificateWarnings = explicitIgnore == nil
                            ? true
                            : ["true", "1"].contains(explicitIgnore!.lowercased())
                    } else {
                        rdp.port = Int(node.fields["RDPPort"] ?? node.fields["Port"] ?? "3389") ?? 3389
                        if let explicitIgnore = node.fields["IgnoreCertificateWarning"] ?? node.fields["IgnoreCertificate"] {
                            rdp.ignoreCertificateWarnings = ["true", "1"].contains(explicitIgnore.lowercased())
                        }
                    }
                    rdp.connectToConsoleAdmin = (node.fields["ConnectToAdministerOrConsole"] ?? "").lowercased() == "true"
                    if let explicitScaleStr = node.fields["DesktopScaleFactor"] ?? node.fields["ScaleFactor"],
                       let explicitScale = Int(explicitScaleStr.trimmingCharacters(in: .whitespacesAndNewlines)),
                       (100...500).contains(explicitScale) {
                        rdp.desktopScaleFactorPercent = explicitScale
                        rdp.deviceScaleFactorPercent = explicitScale >= 175 ? 180 : (explicitScale >= 125 ? 140 : 100)
                        rdp.hiDpiScaleCustomized = true
                    }
                    let isWts = node.elementType != "RoyalRDSConnection"
                    conns.append(VaultConnection(
                        id: id,
                        parentFolderId: parentFolderId,
                        name: name,
                        host: rawUri,
                        protocolType: isWts ? .WINDOWS_TERMINAL_SERVICES : .RDP,
                        osFamily: "Windows",
                        tags: parsedTags,
                        notes: node.fields["Description"] ?? "",
                        favorite: (node.fields["Favorite"] ?? "").lowercased() == "true",
                        sortOrder: sortOrder,
                        inheritable: parseInheritable(),
                        settings: .rdp(rdp)
                    ))
                }
            case "RoyalSSHConnection":
                let isTelnet = (node.fields["IsTelnetConnection"] ?? "").lowercased() == "true"
                var ssh = SshProtocolSettings(port: Int(node.fields["Port"] ?? (isTelnet ? "23" : "22")) ?? 22)
                ssh.terminalType = node.fields["TerminalType"] ?? "xterm-256color"
                conns.append(VaultConnection(
                    id: id,
                    parentFolderId: parentFolderId,
                    name: name,
                    host: node.fields["URI"] ?? "",
                    protocolType: isTelnet ? .TELNET : .SSH,
                    osFamily: "Linux",
                    tags: parsedTags,
                    notes: node.fields["Description"] ?? "",
                    favorite: (node.fields["Favorite"] ?? "").lowercased() == "true",
                    sortOrder: sortOrder,
                    inheritable: parseInheritable(),
                    settings: .ssh(ssh)
                ))
            case "RoyalVNCConnection":
                var vnc = VncProtocolSettings(port: Int(node.fields["VNCPort"] ?? node.fields["Port"] ?? "5900") ?? 5900)
                vnc.appleArdDiffieHellmanAuth = (node.fields["AppleScreenSharing"] ?? "").lowercased() == "true"
                conns.append(VaultConnection(
                    id: id,
                    parentFolderId: parentFolderId,
                    name: name,
                    host: node.fields["URI"] ?? "",
                    protocolType: vnc.appleArdDiffieHellmanAuth ? .APPLE_SCREEN_SHARING : .VNC,
                    osFamily: vnc.appleArdDiffieHellmanAuth ? "macOS" : "Linux",
                    tags: parsedTags,
                    notes: node.fields["Description"] ?? "",
                    sortOrder: sortOrder,
                    inheritable: parseInheritable(),
                    settings: .vnc(vnc)
                ))
            case "RoyalFileTransferConnection":
                let protoStr = (node.fields["ConnectionType"] ?? "SFTP").uppercased()
                let proto: VaultProtocolType = protoStr.contains("SCP") ? .SCP : (protoStr.contains("FTPS") ? .FTPS : (protoStr.contains("FTP") ? .FTP : .SFTP))
                let ft = FileTransferProtocolSettings(
                    port: Int(node.fields["Port"] ?? "\(proto.defaultPort)") ?? proto.defaultPort,
                    ftpsImplicitTls: proto == .FTPS
                )
                conns.append(VaultConnection(
                    id: id,
                    parentFolderId: parentFolderId,
                    name: name,
                    host: node.fields["URI"] ?? "",
                    protocolType: proto,
                    tags: parsedTags,
                    notes: node.fields["Description"] ?? "",
                    sortOrder: sortOrder,
                    inheritable: parseInheritable(),
                    settings: .fileTransfer(ft)
                ))
            case "RoyalWebPageConnection":
                let uri = node.fields["URI"] ?? "https://localhost"
                conns.append(VaultConnection(
                    id: id,
                    parentFolderId: parentFolderId,
                    name: name,
                    host: uri,
                    protocolType: .WEB_PAGE,
                    tags: parsedTags,
                    notes: node.fields["Description"] ?? "",
                    sortOrder: sortOrder,
                    inheritable: parseInheritable(),
                    settings: .webPage(WebPageProtocolSettings(url: uri))
                ))
            default:
                break
            }
        }

        return NxtsVaultDocument(
            documentId: docId,
            name: docName,
            folders: folders,
            smartFolders: smartFolders,
            dynamicFolders: dynamicFolders,
            connections: conns,
            credentials: creds
        )
    }
}

// MARK: - KeePass 2.x XML SAX Delegate

private final class KeePassXmlSAXParser: NSObject, XMLParserDelegate {
    private var folderStack: [VaultFolder] = []
    private var folders: [VaultFolder] = []
    private var creds: [VaultCredential] = []
    private var conns: [VaultConnection] = []

    private var inEntry = false
    private var inHistory = false
    private var currentKey: String?
    private var entryStrings: [String: String] = [:]
    private var textBuf = ""

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        textBuf = ""
        if elementName == "History" { inHistory = true; return }
        if inHistory { return }
        if elementName == "Group" {
            let parentId = folderStack.last?.id
            folderStack.append(VaultFolder(parentFolderId: parentId, name: "Group"))
        } else if elementName == "Entry" {
            inEntry = true
            entryStrings = [:]
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        textBuf.append(string)
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        if elementName == "History" { inHistory = false; return }
        if inHistory { return }
        let val = textBuf.trimmingCharacters(in: .whitespacesAndNewlines)

        if inEntry {
            if elementName == "Key" {
                currentKey = val
            } else if elementName == "Value", let k = currentKey {
                entryStrings[k] = val
                currentKey = nil
            } else if elementName == "Entry" {
                inEntry = false
                let title = entryStrings["Title"] ?? "KeePass Entry"
                let user = entryStrings["UserName"] ?? ""
                let pass = entryStrings["Password"] ?? ""
                let url = entryStrings["URL"] ?? ""
                let notes = entryStrings["Notes"] ?? ""
                let parentId = folderStack.last?.id
                let credId = UUID().uuidString.lowercased()
                creds.append(VaultCredential(
                    id: credId,
                    parentFolderId: nil,
                    name: title,
                    username: user,
                    secretPassword: pass
                ))
                if !url.isEmpty {
                    let parsed = QuickConnectParser.parseAdHocUri(url)
                    conns.append(VaultConnection(
                        parentFolderId: parentId,
                        name: title,
                        host: parsed?.host ?? url,
                        protocolType: parsed?.protocolType ?? .SSH,
                        notes: notes,
                        inheritable: InheritableProperties(
                            credentialMode: .OVERRIDE_EXPLICIT,
                            explicitCredentialId: credId
                        ),
                        settings: parsed?.settings ?? VaultProtocolType.SSH.defaultSettings(host: url)
                    ))
                }
            }
        } else if !folderStack.isEmpty {
            if elementName == "Name" {
                folderStack[folderStack.count - 1].name = val.isEmpty ? "Group" : val
            } else if elementName == "Group", let finished = folderStack.popLast() {
                folders.append(finished)
            }
        }
        textBuf = ""
    }

    func buildDocument(vaultName: String) -> NxtsVaultDocument {
        VaultHierarchySanitizer.sanitizeVaultDocument(
            NxtsVaultDocument(name: vaultName, folders: folders, connections: conns, credentials: creds)
        )
    }
}

// MARK: - Multi-Format Vault Exporter (.nxts, .rtsx, .rtsz, .csv)

public enum VaultExportFormat: String, CaseIterable, Identifiable, Sendable {
    case nxts = "nxts"
    case rtsx = "rtsx"
    case rtsz = "rtsz"
    case csv = "csv"

    public var id: String { rawValue }

    public var displayTitle: String {
        switch self {
        case .nxts: return "NexusTS Zero-Knowledge Vault (.nxts)"
        case .rtsx: return "Royal TS Unencrypted XML (.rtsx)"
        case .rtsz: return "Royal TS Compressed Archive (.rtsz)"
        case .csv: return "Enterprise CSV Inventory (.csv)"
        }
    }
}

public enum VaultDocumentExporter {
    public static func export(
        document: NxtsVaultDocument,
        format: VaultExportFormat,
        passphrase: String = NxtsVaultSerializer.defaultTransparentPassphrase
    ) throws -> Data {
        switch format {
        case .nxts:
            return try NxtsVaultSerializer.seal(document: document, passphrase: passphrase)
        case .rtsx:
            return Data(exportRoyalTsXmlString(document: document).utf8)
        case .rtsz:
            let xmlData = Data(exportRoyalTsXmlString(document: document).utf8)
            return try createSingleEntryZip(entryName: "document.rtsx", payload: xmlData)
        case .csv:
            return Data(exportCsvString(document: document).utf8)
        }
    }

    public static func exportRoyalTsXmlString(document: NxtsVaultDocument) -> String {
        func esc(_ s: String) -> String {
            s.replacingOccurrences(of: "&", with: "&amp;")
                .replacingOccurrences(of: "<", with: "&lt;")
                .replacingOccurrences(of: ">", with: "&gt;")
                .replacingOccurrences(of: "\"", with: "&quot;")
        }
        var lines: [String] = [
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<RTSZDocument>",
            "  <RoyalDocument>",
            "    <ID>\(esc(document.documentId))</ID>",
            "    <Name>\(esc(document.name))</Name>"
        ]
        for folder in document.folders where !document.tombstonedObjectIds.contains(folder.id) {
            let parent = folder.parentFolderId ?? document.documentId
            lines.append("    <RoyalFolder>")
            lines.append("      <ID>\(esc(folder.id))</ID>")
            lines.append("      <ParentID>\(esc(parent))</ParentID>")
            lines.append("      <Name>\(esc(folder.name))</Name>")
            lines.append("      <Description>\(esc(folder.description))</Description>")
            lines.append("      <SortOrder>\(folder.sortOrder)</SortOrder>")
            lines.append("    </RoyalFolder>")
        }
        for sf in document.smartFolders where !document.tombstonedObjectIds.contains(sf.id) {
            lines.append("    <RoyalSmartFolder>")
            lines.append("      <ID>\(esc(sf.id))</ID>")
            lines.append("      <ParentID>\(esc(document.documentId))</ParentID>")
            lines.append("      <Name>\(esc(sf.name))</Name>")
            if !sf.filter.includeTags.isEmpty {
                lines.append("      <IncludeTags>\(esc(sf.filter.includeTags.joined(separator: ";")))</IncludeTags>")
            }
            if !sf.filter.excludeTags.isEmpty {
                lines.append("      <ExcludeTags>\(esc(sf.filter.excludeTags.joined(separator: ";")))</ExcludeTags>")
            }
            if !sf.filter.includeProtocols.isEmpty {
                lines.append("      <Protocols>\(esc(sf.filter.includeProtocols.map(\.rawValue).joined(separator: ";")))</Protocols>")
            }
            if let hostRegex = sf.filter.hostPatternRegex, !hostRegex.isEmpty {
                lines.append("      <HostPattern>\(esc(hostRegex))</HostPattern>")
            }
            if let osFam = sf.filter.osFamilyFilter, !osFam.isEmpty {
                lines.append("      <OSFamily>\(esc(osFam))</OSFamily>")
            }
            if let env = sf.filter.environmentFilter, !env.isEmpty {
                lines.append("      <Environment>\(esc(env))</Environment>")
            }
            if let scopeId = sf.filter.scopeFolderId, !scopeId.isEmpty {
                lines.append("      <ScopeFolderId>\(esc(scopeId))</ScopeFolderId>")
            }
            lines.append("    </RoyalSmartFolder>")
        }
        let activeCreds = document.credentials.filter { !document.tombstonedObjectIds.contains($0.id) }
        if !activeCreds.isEmpty {
            let sysCredFolderId = "royal-system-credentials-\(document.documentId)"
            lines.append("    <RoyalFolder>")
            lines.append("      <ID>\(esc(sysCredFolderId))</ID>")
            lines.append("      <ParentID>\(esc(document.documentId))</ParentID>")
            lines.append("      <Name>Credentials</Name>")
            lines.append("    </RoyalFolder>")
            for cred in activeCreds {
                lines.append("    <RoyalCredential>")
                lines.append("      <ID>\(esc(cred.id))</ID>")
                lines.append("      <ParentID>\(esc(sysCredFolderId))</ParentID>")
                lines.append("      <Name>\(esc(cred.name))</Name>")
                lines.append("      <UserName>\(esc(cred.username))</UserName>")
                if !cred.domain.isEmpty {
                    lines.append("      <Domain>\(esc(cred.domain))</Domain>")
                }
                if !cred.secretPassword.isEmpty {
                    lines.append("      <Password>\(esc(cred.secretPassword))</Password>")
                }
                lines.append("    </RoyalCredential>")
            }
        }
        for conn in document.connections where !document.tombstonedObjectIds.contains(conn.id) {
            let parent = conn.parentFolderId ?? document.documentId
            let tag: String = {
                switch conn.protocolType {
                case .RDP: return "RoyalRDSConnection"
                case .WINDOWS_TERMINAL_SERVICES: return "RoyalWindowsTerminalServicesConnection"
                case .VNC, .APPLE_SCREEN_SHARING: return "RoyalVNCConnection"
                case .SFTP, .FTP, .FTPS, .SCP: return "RoyalFileTransferConnection"
                case .WEB_PAGE: return "RoyalWebPageConnection"
                case .HYPER_V: return "RoyalHyperVConnection"
                case .PROXMOX_VE: return "RoyalProxmoxConnection"
                case .VMWARE_VSPHERE, .AWS_EC2, .AZURE_VM: return "RoyalVMwareConnection"
                default: return "RoyalSSHConnection"
                }
            }()
            lines.append("    <\(tag)>")
            lines.append("      <ID>\(esc(conn.id))</ID>")
            lines.append("      <ParentID>\(esc(parent))</ParentID>")
            lines.append("      <Name>\(esc(conn.name))</Name>")
            lines.append("      <URI>\(esc(conn.host))</URI>")
            lines.append("      <Port>\(conn.settings.effectivePort)</Port>")
            if conn.inheritable.credentialMode == .OVERRIDE_EXPLICIT,
               let credId = conn.inheritable.explicitCredentialId, !credId.isEmpty {
                lines.append("      <CredentialMode>2</CredentialMode>")
                lines.append("      <CredentialId>\(esc(credId))</CredentialId>")
            }
            if case .rdp(let rdp) = conn.settings {
                lines.append("      <RDPPort>\(rdp.effectiveRdpPort)</RDPPort>")
                if !rdp.hyperVVmId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    lines.append("      <HyperVMode>2</HyperVMode>")
                    lines.append("      <HyperVInstance>\(esc(rdp.hyperVVmId.trimmingCharacters(in: .whitespacesAndNewlines)))</HyperVInstance>")
                    lines.append("      <HyperVPort>\(rdp.effectiveRdpPort)</HyperVPort>")
                    lines.append("      <HyperVEnhancedSessionMode>\(rdp.hyperVEnhancedSession ? "True" : "False")</HyperVEnhancedSessionMode>")
                }
            } else if case .cloudVirtualization(let cv) = conn.settings, !cv.apiEndpointUrl.isEmpty {
                lines.append("      <ApiEndpointUrl>\(esc(cv.apiEndpointUrl))</ApiEndpointUrl>")
            }
            lines.append("      <Description>\(esc(conn.notes))</Description>")
            lines.append("      <SortOrder>\(conn.sortOrder)</SortOrder>")
            lines.append("      <Favorite>\(conn.favorite ? "True" : "False")</Favorite>")
            lines.append("    </\(tag)>")
        }
        lines.append("  </RoyalDocument>")
        lines.append("</RTSZDocument>")
        return lines.joined(separator: "\n")
    }

    public static func exportCsvString(document: NxtsVaultDocument) -> String {
        let header = "ConnectionID,Name,Protocol,Host,Port,Folder,OSFamily,Favorite,CredentialMode,AssignedCredential,Tags,ColorHex,Notes,ContainerCluster,ContainerContext,ContainerNamespace,ContainerWorkload,ContainerSubContainer,ContainerShell,ContainerFollowLogs"
        let foldersById = Dictionary(uniqueKeysWithValues: document.folders.map { ($0.id, $0.name) })
        let credsById = Dictionary(uniqueKeysWithValues: document.credentials.map { ($0.id, $0.name) })

        func q(_ s: String) -> String {
            if s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") {
                return "\"\(s.replacingOccurrences(of: "\"", with: "\"\""))\""
            }
            return s
        }

        var lines = [header]
        for conn in document.connections where !document.tombstonedObjectIds.contains(conn.id) {
            let folderName = conn.parentFolderId.flatMap { foldersById[$0] } ?? ""
            let credName = conn.inheritable.explicitCredentialId.flatMap { credsById[$0] } ?? ""
            let tagsJoined = conn.tags.joined(separator: ";")
            let colorHex = conn.inheritable.displayColorAccentHex ?? ""

            var cCluster = "", cContext = "", cNs = "", cWork = "", cSub = "", cShell = "", cLogs = ""
            if case .containerExec(let ce) = conn.settings {
                cCluster = ce.clusterOrSocketEndpoint
                cContext = ce.contextName
                cNs = ce.namespace
                cWork = ce.podOrContainerName
                cSub = ce.subContainerName
                cShell = ce.shellCommand
                cLogs = ce.followLogsOnOpen ? "true" : "false"
            }

            let row = [
                q(conn.id),
                q(conn.name),
                q(conn.protocolType.rawValue),
                q(conn.host),
                "\(conn.settings.effectivePort)",
                q(folderName),
                q(conn.osFamily),
                conn.favorite ? "true" : "false",
                q(conn.inheritable.credentialMode.rawValue),
                q(credName),
                q(tagsJoined),
                q(colorHex),
                q(conn.notes),
                q(cCluster),
                q(cContext),
                q(cNs),
                q(cWork),
                q(cSub),
                q(cShell),
                q(cLogs)
            ].joined(separator: ",")
            lines.append(row)
        }
        return lines.joined(separator: "\n")
    }

    private static func createSingleEntryZip(entryName: String, payload: Data) throws -> Data {
        let fm = FileManager.default
        let tempDir = fm.temporaryDirectory.appendingPathComponent("nxts_zip_out_\(UUID().uuidString)")
        defer { try? fm.removeItem(at: tempDir) }
        try fm.createDirectory(at: tempDir, withIntermediateDirectories: true)
        let entryURL = tempDir.appendingPathComponent(entryName)
        let zipURL = tempDir.appendingPathComponent("vault.rtsz")
        try payload.write(to: entryURL)

        let proc = Process()
        proc.currentDirectoryURL = tempDir
        proc.executableURL = URL(fileURLWithPath: "/usr/bin/zip")
        proc.arguments = ["-q", zipURL.path, entryName]
        try proc.run()
        proc.waitUntilExit()
        return try Data(contentsOf: zipURL)
    }
}
