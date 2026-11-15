import Foundation

// MARK: - 1. Structured Application Error Taxonomy & Diagnostic Classifier

public enum NexusErrorCategory: String, Codable, Equatable, Sendable {
    case networkUnreachable = "NETWORK_UNREACHABLE"
    case authenticationFailed = "AUTHENTICATION_FAILED"
    case tlsCertificate = "TLS_CERTIFICATE"
    case portConflict = "PORT_CONFLICT"
    case vaultCryptoOrCorruption = "VAULT_CRYPTO_OR_CORRUPTION"
    case importExportFormat = "IMPORT_EXPORT_FORMAT"
    case cloudSync = "CLOUD_SYNC"
    case validation = "VALIDATION"
    case `internal` = "INTERNAL"
}

public struct NexusAppError: Identifiable, Equatable, Sendable {
    public let id: String
    public let category: NexusErrorCategory
    public let title: String
    public let userMessage: String
    public let remediationHint: String
    public let technicalDetails: String
    public let timestampEpochMs: Int64
    public let recoveryConnectionId: String?

    public init(
        id: String = UUID().uuidString.lowercased(),
        category: NexusErrorCategory,
        title: String,
        userMessage: String,
        remediationHint: String,
        technicalDetails: String = "",
        timestampEpochMs: Int64 = VaultClock.nowMs(),
        recoveryConnectionId: String? = nil
    ) {
        self.id = id
        self.category = category
        self.title = title
        self.userMessage = userMessage
        self.remediationHint = remediationHint
        self.technicalDetails = technicalDetails
        self.timestampEpochMs = timestampEpochMs
        self.recoveryConnectionId = recoveryConnectionId
    }

    public func formatDiagnosticReport() -> String {
        var lines: [String] = [
            "NexusTS Diagnostic Report",
            "Category: \(category.rawValue)",
            "Title: \(title)",
            "Summary: \(userMessage)"
        ]
        if !remediationHint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            lines.append("Suggested Fix: \(remediationHint)")
        }
        if !technicalDetails.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            lines.append("Technical Details: \(technicalDetails)")
        }
        lines.append("TimestampEpochMs: \(timestampEpochMs)")
        return lines.joined(separator: "\n")
    }
}

public enum NexusErrorClassifier {
    public static func classify(
        _ error: Error,
        operationTitle: String,
        recoveryConnectionId: String? = nil
    ) -> NexusAppError {
        let rawMsg = error.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        let summary = rawMsg.isEmpty ? String(describing: error) : rawMsg
        let lower = summary.lowercased()
        let tech = "\(String(reflecting: type(of: error))): \(summary)"

        if lower.contains("invalid passphrase")
            || lower.contains("authentication tag")
            || lower.contains("wrong password")
            || lower.contains("tampered container")
            || lower.contains("vault authentication failed") {
            return NexusAppError(
                category: .vaultCryptoOrCorruption,
                title: operationTitle,
                userMessage: "Decryption failed because the passphrase/password is incorrect or the encrypted container was modified.",
                remediationHint: "Double-check your master passphrase or archive password and try again.",
                technicalDetails: tech,
                recoveryConnectionId: recoveryConnectionId
            )
        }

        if lower.contains("nodename nor servname")
            || lower.contains("name or service not known")
            || (lower.contains("dns") && lower.contains("failed"))
            || lower.contains("hostname could not be found") {
            return NexusAppError(
                category: .networkUnreachable,
                title: operationTitle,
                userMessage: "Could not resolve the server hostname.",
                remediationHint: "Verify the hostname/FQDN spelling, VPN/DNS connectivity, or use an IP address.",
                technicalDetails: tech,
                recoveryConnectionId: recoveryConnectionId
            )
        }

        if lower.contains("connection refused")
            || lower.contains("timed out")
            || lower.contains("network is unreachable")
            || lower.contains("could not connect to the server") {
            return NexusAppError(
                category: .networkUnreachable,
                title: operationTitle,
                userMessage: "The target host could not be reached over the network.",
                remediationHint: "Confirm the host is powered on, check the port number and firewall rules, or verify your VPN / Secure Gateway tunnel.",
                technicalDetails: tech,
                recoveryConnectionId: recoveryConnectionId
            )
        }

        if lower.contains("certificate") || lower.contains("ssl") || lower.contains("tls") {
            return NexusAppError(
                category: .tlsCertificate,
                title: operationTitle,
                userMessage: "TLS/SSL certificate verification failed for the remote endpoint.",
                remediationHint: "Trust the server's CA certificate in macOS Keychain, or enable 'Ignore Self-Signed TLS Certificate Warnings' for lab hosts.",
                technicalDetails: tech,
                recoveryConnectionId: recoveryConnectionId
            )
        }

        if lower.contains("address already in use") || lower.contains("eaddrinuse") {
            return NexusAppError(
                category: .portConflict,
                title: operationTitle,
                userMessage: "Local port is already in use by another session or application.",
                remediationHint: "Choose an unused local bind port in Port Forwarding settings or close the conflicting session.",
                technicalDetails: tech,
                recoveryConnectionId: recoveryConnectionId
            )
        }

        if (lower.contains("auth") && (lower.contains("fail") || lower.contains("denied") || lower.contains("rejected")))
            || lower.contains("permission denied")
            || lower.contains("logon_failure") {
            return NexusAppError(
                category: .authenticationFailed,
                title: operationTitle,
                userMessage: "Authentication was rejected by the remote host.",
                remediationHint: "Verify the assigned credential's username, domain, password, or SSH private key.",
                technicalDetails: tech,
                recoveryConnectionId: recoveryConnectionId
            )
        }

        if lower.contains("xml")
            || lower.contains("corrupt")
            || lower.contains("unsupported")
            || lower.contains("invalid or unsupported .nxts")
            || lower.contains("format") {
            return NexusAppError(
                category: .importExportFormat,
                title: operationTitle,
                userMessage: summary,
                remediationHint: "Verify that the selected file is a valid, uncorrupted .nxts, .rtsz, .rtsx, .rjson, .kdbx, Bitwarden, 1Password, or CSV export.",
                technicalDetails: tech,
                recoveryConnectionId: recoveryConnectionId
            )
        }

        if lower.contains("icloud") || lower.contains("google drive") || lower.contains("oauth") || lower.contains("bookmark") {
            return NexusAppError(
                category: .cloudSync,
                title: operationTitle,
                userMessage: summary,
                remediationHint: "Check network connectivity or re-link the iCloud Drive / Google Drive vault file in Cloud Sync Studio.",
                technicalDetails: tech,
                recoveryConnectionId: recoveryConnectionId
            )
        }

        return NexusAppError(
            category: .internal,
            title: operationTitle,
            userMessage: summary,
            remediationHint: "Review the technical details below or retry the operation.",
            technicalDetails: tech,
            recoveryConnectionId: recoveryConnectionId
        )
    }

    public static func classifyRdpError(
        host: String,
        port: Int,
        rawNativeError: String,
        connectionId: String? = nil
    ) -> NexusAppError {
        let cleaned = rawNativeError.trimmingCharacters(in: .whitespacesAndNewlines)
        let upper = cleaned.uppercased()

        if cleaned.isEmpty {
            return NexusAppError(
                category: .networkUnreachable,
                title: "RDP Connection Failed (\(host):\(port))",
                userMessage: "Could not establish an RDP session with \(host):\(port).",
                remediationHint: "Verify the host/IP, TCP port \(port), assigned credentials, and NLA / TLS certificate settings.",
                technicalDetails: "FreeRDP nexusts_rdp_connect returned false (\(host):\(port))",
                recoveryConnectionId: connectionId
            )
        }

        if upper.hasPrefix("INTERNAL:")
            || upper.contains("FREERDP_CONTEXT_NEW")
            || upper.contains("FREERDP_NEW")
            || upper.contains("GDI_INIT") {
            return NexusAppError(
                category: .internal,
                title: "RDP Native Engine Error (\(host):\(port))",
                userMessage: "The local FreeRDP 3.x session engine failed to initialize.",
                remediationHint: "Close and reopen the session tab or restart NexusTS. Technical diagnostics are included below.",
                technicalDetails: cleaned,
                recoveryConnectionId: connectionId
            )
        }

        if upper.contains("DNS_NAME_NOT_FOUND") || upper.contains("NAME_NOT_FOUND") {
            return NexusAppError(
                category: .networkUnreachable,
                title: "Hostname Not Found (\(host))",
                userMessage: "DNS lookup failed for '\(host)'.",
                remediationHint: "Verify the hostname spelling, DNS/VPN configuration, or connect via IP address.",
                technicalDetails: cleaned,
                recoveryConnectionId: connectionId
            )
        }

        if upper.contains("HYBRID_REQUIRED") {
            return NexusAppError(
                category: .authenticationFailed,
                title: "Windows NLA Credentials Required (\(host))",
                userMessage: "This Windows host requires Network Level Authentication (NLA / CredSSP) before displaying the desktop.",
                remediationHint: "Assign a username and password credential to this connection (or parent folder) and click Reconnect.",
                technicalDetails: cleaned,
                recoveryConnectionId: connectionId
            )
        }

        if upper.contains("AUTHENTICATION_FAILED")
            || upper.contains("LOGON_FAILURE")
            || upper.contains("WRONG_PASSWORD")
            || upper.contains("STATUS_LOGON_FAILURE")
            || upper.contains("SEC_E_INVALID_TOKEN") {
            return NexusAppError(
                category: .authenticationFailed,
                title: "RDP Authentication Failed (\(host))",
                userMessage: "Windows / NLA (CredSSP) rejected the username, domain, or password.",
                remediationHint: "Edit the connection or its assigned credential in the Credentials folder, or verify DOMAIN\\username format and NLA settings.",
                technicalDetails: cleaned,
                recoveryConnectionId: connectionId
            )
        }

        if upper.contains("ACCOUNT_LOCKED_OUT") || upper.contains("ACCOUNT_DISABLED") || upper.contains("PASSWORD_EXPIRED") {
            return NexusAppError(
                category: .authenticationFailed,
                title: "Remote Account Restricted (\(host))",
                userMessage: "The Windows account is locked out, disabled, or its password has expired.",
                remediationHint: "Unlock or reset the Windows / Active Directory account before reconnecting.",
                technicalDetails: cleaned,
                recoveryConnectionId: connectionId
            )
        }

        if upper.contains("TLS_CERTIFICATE_CHANGED") {
            return NexusAppError(
                category: .tlsCertificate,
                title: "RDP Host Certificate Changed (\(host))",
                userMessage: "The X.509 certificate fingerprint presented by \(host):\(port) changed since a previous connection.",
                remediationHint: "Verify that the Windows server certificate was intentionally rotated (no MITM), or enable 'Ignore Self-Signed TLS Certificate Warnings' in RDP settings to accept the rotated certificate.",
                technicalDetails: cleaned,
                recoveryConnectionId: connectionId
            )
        }

        if upper.contains("TLS_CONNECT_FAILED") || upper.contains("CERT") || upper.contains("SSL") {
            return NexusAppError(
                category: .tlsCertificate,
                title: "TLS / Certificate Handshake Rejected (\(host))",
                userMessage: "The RDP server's TLS handshake or certificate could not be verified.",
                remediationHint: "Enable 'Ignore Self-Signed TLS Certificate Warnings' in RDP settings if this host uses a custom/legacy certificate.",
                technicalDetails: cleaned,
                recoveryConnectionId: connectionId
            )
        }

        if upper.contains("ERRCONNECT_CONNECT_TRANSPORT_FAILED") {
            return NexusAppError(
                category: .networkUnreachable,
                title: "RDP Transport Negotiation Dropped (\(host):\(port))",
                userMessage: "The RDP server closed the connection during security or capability activation.",
                remediationHint: "Verify NLA / TLS settings, ensure the account has Remote Desktop log-on rights on \(host), or toggle 'Ignore Self-Signed TLS Certificate Warnings' in RDP settings.",
                technicalDetails: cleaned,
                recoveryConnectionId: connectionId
            )
        }

        if upper.contains("ERRCONNECT_CONNECT_FAILED")
            || upper.contains("CONNECT_TRANSPORT_FAILED")
            || upper.contains("CONNECT_CANCELLED")
            || upper.contains("MCS_CONNECT_INITIAL_ERROR")
            || upper.contains("PEER_DISCONNECTED") {
            return NexusAppError(
                category: .networkUnreachable,
                title: "Host Unreachable (\(host):\(port))",
                userMessage: "TCP connection to \(host):\(port) failed or timed out.",
                remediationHint: "Ensure Remote Desktop is enabled on the target host, port \(port) is reachable, and your VPN/Gateway is active.",
                technicalDetails: cleaned,
                recoveryConnectionId: connectionId
            )
        }

        return NexusAppError(
            category: .networkUnreachable,
            title: "RDP Session Disconnected (\(host):\(port))",
            userMessage: "The remote desktop session ended with an error.",
            remediationHint: "Check the FreeRDP diagnostic code below, adjust RDP settings or credentials, and click Reconnect.",
            technicalDetails: cleaned,
            recoveryConnectionId: connectionId
        )
    }
}

// MARK: - 2. Cross-Platform Vault Hierarchy Sanitizer & System "Credentials" Folder Guard

public enum VaultHierarchySanitizer {
    public static let systemCredentialsFolderId: String = "__NEXUSTS_SYSTEM_CREDENTIALS__"

    public static let knownStarterDemoIds: Set<String> = [
        // Android / Googlebook OS legacy demo IDs
        "folder-prod",
        "folder-eu-core",
        "folder-lab",
        "folder-win-ad",
        "folder-hybrid-cloud",
        "conn-win-dc01",
        "conn-k8s-bastion",
        "conn-sftp-vault",
        "conn-proxmox-ve",
        "conn-win-rds-01",
        "conn-win-rds-02",
        "conn-k8s-bastion-ssh",
        "conn-prod-sftp",
        "conn-macos-ard",
        "conn-net-scanner",
        "conn-serial-switch-01",
        "conn-k8s-pod-exec-01",
        "cred-domain-admin",
        "cred-ops-ed25519",
        "cred-ops-root",
        "cred-corp-domain",
        "gw-eu-zero-trust",
        "gw-eu-bastion",
        "smart-linux-prod",
        "smart-all-rdp",
        "dyn-cloud-fleet",
        "tpl-hardened-rdp",
        "tpl-bastion-ssh",
        "tpl-win-console-hidpi",
        "task-apt-sec",
        "task-k8s-pods",
        "macro-win-elevate",
        "todo-rotate-ssh",
        "todo-audit-rdp",
        "todo-cert-rotate",
        "todo-k8s-etcd",
        // macOS legacy sampleStarterVault IDs
        "folder-prod-core",
        "folder-homelab",
        "conn-bastion-01",
        "conn-dc-01",
        "conn-pve-01",
        "conn-mac-build-01",
        "cred-prod-ops",
        "smart-prod-all",
        "tpl-hardened-ssh",
        "task-fleet-health"
    ]

    private static let knownStarterSmartFolderNames: Set<String> = [
        "All Production Linux Nodes",
        "All Remote Desktop (RDP/VNC)",
        "All Production Hosts"
    ]

    private static let knownStarterTaskNames: Set<String> = [
        "Cluster Pod Health Audit",
        "System Health & Uptime Snapshot"
    ]

    private static let knownStarterMacroNames: Set<String> = [
        "Windows Domain Elevated Sign-In + 2FA Macro"
    ]

    private static let knownStarterTodoTitles: Set<String> = [
        "Enable Kerberos ticket forwarding on EU Bastion",
        "Rotate Kerberos krbtgt keys during Sunday maintenance window"
    ]

    public static func isReservedCredentialsFolderName(_ name: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.caseInsensitiveCompare("Credentials") == .orderedSame
            || trimmed.caseInsensitiveCompare("Anmeldeinformationen") == .orderedSame
    }

    public static func sanitizeVaultDocument(_ doc: NxtsVaultDocument) -> NxtsVaultDocument {
        let activeConnsWithoutDemo = doc.connections.filter { !knownStarterDemoIds.contains($0.id) }
        let userReferencedFolderIds = Set(activeConnsWithoutDemo.compactMap(\.parentFolderId))
        let activeFoldersWithoutDemo = doc.folders.filter {
            !(knownStarterDemoIds.contains($0.id) && !userReferencedFolderIds.contains($0.id))
        }

        let folderChildrenMap = Dictionary(grouping: activeFoldersWithoutDemo, by: \.parentFolderId)
        let connsByFolder = Dictionary(grouping: activeConnsWithoutDemo, by: \.parentFolderId)

        func subtreeHasAnyConnections(_ folderId: String, visited: inout Set<String>) -> Bool {
            guard visited.insert(folderId).inserted else { return false }
            if !(connsByFolder[folderId] ?? []).isEmpty { return true }
            for child in folderChildrenMap[folderId] ?? [] {
                if subtreeHasAnyConnections(child.id, visited: &visited) { return true }
            }
            return false
        }

        func collectSubtreeIds(_ folderId: String, out: inout Set<String>) {
            guard out.insert(folderId).inserted else { return }
            for child in folderChildrenMap[folderId] ?? [] {
                collectSubtreeIds(child.id, out: &out)
            }
        }

        var absorbedCredentialFolderIds = Set<String>()
        for folder in activeFoldersWithoutDemo {
            var visited = Set<String>()
            if folder.parentFolderId == nil,
               isReservedCredentialsFolderName(folder.name),
               !subtreeHasAnyConnections(folder.id, visited: &visited) {
                collectSubtreeIds(folder.id, out: &absorbedCredentialFolderIds)
            }
        }

        let cleanedFolders = activeFoldersWithoutDemo.filter {
            !absorbedCredentialFolderIds.contains($0.id) && $0.id != systemCredentialsFolderId
        }
        let validFolderIds = Set(cleanedFolders.map(\.id))

        let cleanedConnections = activeConnsWithoutDemo.map { conn -> VaultConnection in
            if let pid = conn.parentFolderId, !validFolderIds.contains(pid) {
                var copy = conn
                copy.parentFolderId = nil
                return copy
            }
            return conn
        }

        let cleanedCredentials = doc.credentials
            .filter { !knownStarterDemoIds.contains($0.id) }
            .map { cred -> VaultCredential in
                if cred.parentFolderId != nil {
                    var copy = cred
                    copy.parentFolderId = nil
                    return copy
                }
                return cred
            }

        let cleanedSmartFolders = doc.smartFolders.filter {
            !knownStarterDemoIds.contains($0.id) && !knownStarterSmartFolderNames.contains($0.name)
        }
        let cleanedDynamicFolders = doc.dynamicFolders.filter { !knownStarterDemoIds.contains($0.id) }
        let cleanedGateways = doc.gateways.filter { !knownStarterDemoIds.contains($0.id) }
        let cleanedTasks = doc.commandTasks.filter {
            !knownStarterDemoIds.contains($0.id) && !knownStarterTaskNames.contains($0.name)
        }
        let cleanedMacros = doc.keySequenceTasks.filter {
            !knownStarterDemoIds.contains($0.id) && !knownStarterMacroNames.contains($0.name)
        }
        let cleanedTemplates = doc.templates.filter { !knownStarterDemoIds.contains($0.id) }
        let cleanedTodos = doc.todoItems.filter {
            !knownStarterDemoIds.contains($0.id) && !knownStarterTodoTitles.contains($0.title)
        }
        let validConnIds = Set(cleanedConnections.map(\.id))
        let cleanedRecents = doc.recentConnectionIds.filter { validConnIds.contains($0) }

        let cleanDocId = doc.documentId == "nxts-enterprise-core" ? "nxts-default-vault" : doc.documentId
        let cleanName: String = {
            if doc.name == "Production Infrastructure"
                || doc.name == "Enterprise Infrastructure Vault"
                || doc.documentId == "nxts-enterprise-core" {
                return "NexusTS Vault"
            }
            return doc.name
        }()

        let changed = cleanDocId != doc.documentId
            || cleanName != doc.name
            || cleanedFolders != doc.folders
            || cleanedConnections != doc.connections
            || cleanedCredentials != doc.credentials
            || cleanedSmartFolders != doc.smartFolders
            || cleanedDynamicFolders != doc.dynamicFolders
            || cleanedGateways != doc.gateways
            || cleanedTasks != doc.commandTasks
            || cleanedMacros != doc.keySequenceTasks
            || cleanedTemplates != doc.templates
            || cleanedTodos != doc.todoItems
            || cleanedRecents != doc.recentConnectionIds

        guard changed else { return doc }

        var copy = doc
        copy.documentId = cleanDocId
        copy.name = cleanName
        copy.folders = cleanedFolders
        copy.smartFolders = cleanedSmartFolders
        copy.dynamicFolders = cleanedDynamicFolders
        copy.connections = cleanedConnections
        copy.credentials = cleanedCredentials
        copy.gateways = cleanedGateways
        copy.commandTasks = cleanedTasks
        copy.keySequenceTasks = cleanedMacros
        copy.templates = cleanedTemplates
        copy.todoItems = cleanedTodos
        copy.recentConnectionIds = cleanedRecents
        return copy
    }
}

// MARK: - 3. Royal TS Import Placement Modes & Hierarchy Merger

public enum RoyalTsImportPlacementMode: String, CaseIterable, Identifiable, Sendable {
    case createNewFolder = "CREATE_NEW_FOLDER"
    case placeAtRoot = "PLACE_AT_ROOT"
    case replaceExisting = "REPLACE_EXISTING"

    public var id: String { rawValue }
}

public enum RoyalTsImportMerger {
    /// Normalizes an imported Royal TS document:
    /// - Absorbs any root-level `"Credentials"` folder into the system Credentials container.
    /// - Optionally unwraps Royal TS's built-in single root `"Connections"` wrapper folder.
    public static func normalizeImportedRoyalDocument(
        _ imported: NxtsVaultDocument,
        unwrapBuiltInConnectionsRoot: Bool = true
    ) -> NxtsVaultDocument {
        var sanitized = VaultHierarchySanitizer.sanitizeVaultDocument(imported)

        guard unwrapBuiltInConnectionsRoot else { return sanitized }

        let rootFolders = sanitized.folders.filter { $0.parentFolderId == nil }
        let rootConnections = sanitized.connections.filter { $0.parentFolderId == nil }

        if rootFolders.count == 1,
           rootConnections.isEmpty,
           rootFolders[0].name.trimmingCharacters(in: .whitespacesAndNewlines)
               .caseInsensitiveCompare("Connections") == .orderedSame {
            let wrapper = rootFolders[0]
            let wrapperId = wrapper.id
            let hasWrapperInheritance =
                (wrapper.inheritable.credentialMode == .OVERRIDE_EXPLICIT && wrapper.inheritable.explicitCredentialId != nil)
                || (wrapper.inheritable.gatewayMode == .OVERRIDE_EXPLICIT && wrapper.inheritable.explicitGatewayId != nil)
                || !(wrapper.inheritable.environmentBadge ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty

            let promotedFolders = sanitized.folders
                .filter { $0.id != wrapperId }
                .map { f -> VaultFolder in
                    guard f.parentFolderId == wrapperId else { return f }
                    var copy = f
                    copy.parentFolderId = nil
                    if hasWrapperInheritance && copy.inheritable.credentialMode == .INHERIT_FROM_PARENT {
                        copy.inheritable = mergeParentInheritable(parent: wrapper.inheritable, child: copy.inheritable)
                    }
                    return copy
                }

            let promotedConns = sanitized.connections.map { c -> VaultConnection in
                guard c.parentFolderId == wrapperId else { return c }
                var copy = c
                copy.parentFolderId = nil
                if hasWrapperInheritance && copy.inheritable.credentialMode == .INHERIT_FROM_PARENT {
                    copy.inheritable = mergeParentInheritable(parent: wrapper.inheritable, child: copy.inheritable)
                }
                return copy
            }

            let promotedDynamic = sanitized.dynamicFolders.map { df -> DynamicFolder in
                guard df.parentFolderId == wrapperId else { return df }
                var copy = df
                copy.parentFolderId = nil
                return copy
            }

            sanitized.folders = promotedFolders
            sanitized.connections = promotedConns
            sanitized.dynamicFolders = promotedDynamic
        }

        return sanitized
    }

    public static func applyPlacement(
        existing: NxtsVaultDocument,
        importedRaw: NxtsVaultDocument,
        mode: RoyalTsImportPlacementMode,
        newFolderName: String = ""
    ) -> NxtsVaultDocument {
        let now = VaultClock.nowMs()
        let normalized = normalizeImportedRoyalDocument(importedRaw, unwrapBuiltInConnectionsRoot: true)
        let remapped = remapAllObjectIds(normalized, now: now)

        switch mode {
        case .replaceExisting:
            var replaced = VaultHierarchySanitizer.sanitizeVaultDocument(remapped)
            replaced.documentId = existing.documentId
            replaced.name = normalized.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? existing.name
                : normalized.name
            replaced.revision = existing.revision + 1
            replaced.lastModifiedByDevice = "macOS"
            replaced.updatedAtEpochMs = now
            var clock = existing.vectorClock
            clock["macos"] = max(clock["macos"] ?? 0, existing.revision + 1)
            replaced.vectorClock = clock
            replaced.tombstonedObjectIds = []
            return replaced

        case .placeAtRoot:
            let existingRootMaxOrder = existing.folders
                .filter { $0.parentFolderId == nil && !existing.tombstonedObjectIds.contains($0.id) }
                .map(\.sortOrder)
                .max() ?? -1
            let existingRootConnMaxOrder = existing.connections
                .filter { $0.parentFolderId == nil && !existing.tombstonedObjectIds.contains($0.id) }
                .map(\.sortOrder)
                .max() ?? -1

            var rootOrderCursor = existingRootMaxOrder + 1
            let shiftedFolders = remapped.folders.map { f -> VaultFolder in
                guard f.parentFolderId == nil else { return f }
                var copy = f
                copy.sortOrder = rootOrderCursor
                rootOrderCursor += 1
                return copy
            }

            var rootConnCursor = existingRootConnMaxOrder + 1
            let shiftedConnections = remapped.connections.map { c -> VaultConnection in
                guard c.parentFolderId == nil else { return c }
                var copy = c
                copy.sortOrder = rootConnCursor
                rootConnCursor += 1
                return copy
            }

            var merged = existing
            merged.folders = existing.folders + shiftedFolders
            merged.smartFolders = existing.smartFolders + remapped.smartFolders
            merged.dynamicFolders = existing.dynamicFolders + remapped.dynamicFolders
            merged.connections = existing.connections + shiftedConnections
            merged.credentials = existing.credentials + remapped.credentials
            merged.gateways = existing.gateways + remapped.gateways
            merged.commandTasks = existing.commandTasks + remapped.commandTasks
            merged.keySequenceTasks = existing.keySequenceTasks + remapped.keySequenceTasks
            merged.templates = existing.templates + remapped.templates
            merged.todoItems = existing.todoItems + remapped.todoItems
            merged.bumpRevision(actor: "macos")
            return VaultHierarchySanitizer.sanitizeVaultDocument(merged)

        case .createNewFolder:
            let rawCleaned = newFolderName.trimmingCharacters(in: .whitespacesAndNewlines)
            let fallbackTitle = normalized.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "Imported Royal TS"
                : normalized.name
            let candidateTitle = rawCleaned.isEmpty ? fallbackTitle : rawCleaned
            let safeTitle = VaultHierarchySanitizer.isReservedCredentialsFolderName(candidateTitle)
                ? "\(candidateTitle) (Imported)"
                : candidateTitle
            let wrapperId = "folder-\(UUID().uuidString.lowercased())"
            let nextRootOrder = (existing.folders
                .filter { $0.parentFolderId == nil && !existing.tombstonedObjectIds.contains($0.id) }
                .map(\.sortOrder)
                .max() ?? -1) + 1

            let wrapperFolder = VaultFolder(
                id: wrapperId,
                parentFolderId: nil,
                name: safeTitle,
                description: "Imported from Royal TS",
                sortOrder: nextRootOrder,
                updatedAtEpochMs: now
            )

            let reparentedFolders = remapped.folders.map { f -> VaultFolder in
                guard f.parentFolderId == nil else { return f }
                var copy = f
                copy.parentFolderId = wrapperId
                return copy
            }
            let reparentedConns = remapped.connections.map { c -> VaultConnection in
                guard c.parentFolderId == nil else { return c }
                var copy = c
                copy.parentFolderId = wrapperId
                return copy
            }
            let reparentedDynamic = remapped.dynamicFolders.map { df -> DynamicFolder in
                guard df.parentFolderId == nil else { return df }
                var copy = df
                copy.parentFolderId = wrapperId
                return copy
            }

            var merged = existing
            merged.folders = existing.folders + [wrapperFolder] + reparentedFolders
            merged.smartFolders = existing.smartFolders + remapped.smartFolders
            merged.dynamicFolders = existing.dynamicFolders + reparentedDynamic
            merged.connections = existing.connections + reparentedConns
            merged.credentials = existing.credentials + remapped.credentials
            merged.gateways = existing.gateways + remapped.gateways
            merged.commandTasks = existing.commandTasks + remapped.commandTasks
            merged.keySequenceTasks = existing.keySequenceTasks + remapped.keySequenceTasks
            merged.templates = existing.templates + remapped.templates
            merged.todoItems = existing.todoItems + remapped.todoItems
            merged.bumpRevision(actor: "macos")
            return VaultHierarchySanitizer.sanitizeVaultDocument(merged)
        }
    }

    private static func remapAllObjectIds(_ doc: NxtsVaultDocument, now: Int64) -> NxtsVaultDocument {
        let folderIdMap = Dictionary(uniqueKeysWithValues: doc.folders.map { ($0.id, "folder-\(UUID().uuidString.lowercased())") })
        let credIdMap = Dictionary(uniqueKeysWithValues: doc.credentials.map { ($0.id, "cred-\(UUID().uuidString.lowercased())") })
        let gwIdMap = Dictionary(uniqueKeysWithValues: doc.gateways.map { ($0.id, "gw-\(UUID().uuidString.lowercased())") })
        let taskIdMap = Dictionary(uniqueKeysWithValues: doc.commandTasks.map { ($0.id, "task-\(UUID().uuidString.lowercased())") })
        let connIdMap = Dictionary(uniqueKeysWithValues: doc.connections.map { ($0.id, "conn-\(UUID().uuidString.lowercased())") })

        func remapInheritable(_ inh: InheritableProperties) -> InheritableProperties {
            var copy = inh
            if let cid = copy.explicitCredentialId { copy.explicitCredentialId = credIdMap[cid] ?? cid }
            if let gid = copy.explicitGatewayId { copy.explicitGatewayId = gwIdMap[gid] ?? gid }
            if let pre = copy.preConnectTaskId { copy.preConnectTaskId = taskIdMap[pre] ?? pre }
            if let post = copy.postDisconnectTaskId { copy.postDisconnectTaskId = taskIdMap[post] ?? post }
            return copy
        }

        var copy = doc
        copy.folders = doc.folders.map { f in
            var nf = f
            nf.id = folderIdMap[f.id] ?? f.id
            nf.parentFolderId = f.parentFolderId.flatMap { folderIdMap[$0] ?? $0 }
            nf.inheritable = remapInheritable(f.inheritable)
            nf.updatedAtEpochMs = now
            return nf
        }
        copy.smartFolders = doc.smartFolders.map { sf in
            var nsf = sf
            nsf.id = "smart-\(UUID().uuidString.lowercased())"
            nsf.filter.scopeFolderId = sf.filter.scopeFolderId.flatMap { folderIdMap[$0] ?? $0 }
            nsf.updatedAtEpochMs = now
            return nsf
        }
        copy.dynamicFolders = doc.dynamicFolders.map { df in
            var ndf = df
            ndf.id = "dyn-\(UUID().uuidString.lowercased())"
            ndf.parentFolderId = df.parentFolderId.flatMap { folderIdMap[$0] ?? $0 }
            ndf.inheritable = remapInheritable(df.inheritable)
            ndf.updatedAtEpochMs = now
            return ndf
        }
        copy.connections = doc.connections.map { c in
            var nc = c
            nc.id = connIdMap[c.id] ?? c.id
            nc.parentFolderId = c.parentFolderId.flatMap { folderIdMap[$0] ?? $0 }
            nc.inheritable = remapInheritable(c.inheritable)
            if case .ssh(var sshCfg) = nc.settings, let bid = sshCfg.bastionJumpConnectionId {
                sshCfg.bastionJumpConnectionId = connIdMap[bid] ?? bid
                nc.settings = .ssh(sshCfg)
            }
            nc.updatedAtEpochMs = now
            return nc
        }
        copy.credentials = doc.credentials.map { cr in
            var ncr = cr
            ncr.id = credIdMap[cr.id] ?? cr.id
            ncr.parentFolderId = nil
            ncr.updatedAtEpochMs = now
            return ncr
        }
        copy.gateways = doc.gateways.map { gw in
            var ngw = gw
            ngw.id = gwIdMap[gw.id] ?? gw.id
            ngw.credentialId = gw.credentialId.flatMap { credIdMap[$0] ?? $0 }
            ngw.updatedAtEpochMs = now
            return ngw
        }
        copy.commandTasks = doc.commandTasks.map { t in
            var nt = t
            nt.id = taskIdMap[t.id] ?? t.id
            nt.updatedAtEpochMs = now
            return nt
        }
        copy.keySequenceTasks = doc.keySequenceTasks.map { m in
            var nm = m
            nm.id = "macro-\(UUID().uuidString.lowercased())"
            nm.updatedAtEpochMs = now
            return nm
        }
        copy.templates = doc.templates.map { tp in
            var ntp = tp
            ntp.id = "tpl-\(UUID().uuidString.lowercased())"
            ntp.inheritable = remapInheritable(tp.inheritable)
            ntp.updatedAtEpochMs = now
            return ntp
        }
        copy.todoItems = doc.todoItems.map { td in
            var ntd = td
            ntd.id = "todo-\(UUID().uuidString.lowercased())"
            ntd.targetObjectId = td.targetObjectId.flatMap { connIdMap[$0] ?? folderIdMap[$0] ?? $0 }
            ntd.updatedAtEpochMs = now
            return ntd
        }
        return copy
    }

    private static func mergeParentInheritable(
        parent: InheritableProperties,
        child: InheritableProperties
    ) -> InheritableProperties {
        var copy = child
        if child.credentialMode == .INHERIT_FROM_PARENT {
            copy.credentialMode = parent.credentialMode
            copy.explicitCredentialId = parent.explicitCredentialId
        }
        if child.gatewayMode == .INHERIT_FROM_PARENT {
            copy.gatewayMode = parent.gatewayMode
            copy.explicitGatewayId = parent.explicitGatewayId
        }
        if child.environmentBadge == nil {
            copy.environmentBadge = parent.environmentBadge
        }
        return copy
    }
}
