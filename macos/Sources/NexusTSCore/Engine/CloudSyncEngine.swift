import CryptoKit
import Foundation
import Network

// MARK: - 1. Vector Clock & 3-Way Object-Level Delta Conflict Resolver

public enum VectorClockRelation: String, Equatable, Sendable {
    case IDENTICAL
    case LOCAL_AHEAD
    case REMOTE_AHEAD
    case CONCURRENT_DIVERGENCE
}

public struct ObjectConflictEntry: Equatable, Identifiable, Sendable {
    public var id: String { "\(collection):\(objectId)" }
    public let collection: String
    public let objectId: String
    public let winningSide: String
    public let localUpdatedAtMs: Int64
    public let remoteUpdatedAtMs: Int64
}

public struct VaultMergeOutcome: Equatable, Sendable {
    public let mergedDocument: NxtsVaultDocument
    public let relation: VectorClockRelation
    public let resolvedConflicts: [ObjectConflictEntry]
}

public enum VaultDeltaConflictResolver {
    public static func compareVectorClocks(
        local: [String: Int64],
        remote: [String: Int64]
    ) -> VectorClockRelation {
        let allActors = Set(local.keys).union(remote.keys)
        var localGreater = false
        var remoteGreater = false

        for actor in allActors {
            let lv = local[actor] ?? 0
            let rv = remote[actor] ?? 0
            if lv > rv { localGreater = true }
            if rv > lv { remoteGreater = true }
        }

        switch (localGreater, remoteGreater) {
        case (false, false): return .IDENTICAL
        case (true, false): return .LOCAL_AHEAD
        case (false, true): return .REMOTE_AHEAD
        case (true, true): return .CONCURRENT_DIVERGENCE
        }
    }

    public static func mergeDocuments(
        local: NxtsVaultDocument,
        remote: NxtsVaultDocument,
        localDeviceId: String = "macos"
    ) -> VaultMergeOutcome {
        let relation = compareVectorClocks(local: local.vectorClock, remote: remote.vectorClock)
        let mergedTombstones = local.tombstonedObjectIds.union(remote.tombstonedObjectIds)
        var conflicts: [ObjectConflictEntry] = []

        func mergeCollection<T: VaultTimestampedEntity>(
            _ name: String,
            _ localItems: [T],
            _ remoteItems: [T]
        ) -> [T] {
            let localById = Dictionary(uniqueKeysWithValues: localItems.map { ($0.id, $0) })
            let remoteById = Dictionary(uniqueKeysWithValues: remoteItems.map { ($0.id, $0) })

            var orderedIds: [String] = []
            var seen = Set<String>()
            for item in localItems where !seen.contains(item.id) {
                seen.insert(item.id)
                orderedIds.append(item.id)
            }
            for item in remoteItems where !seen.contains(item.id) {
                seen.insert(item.id)
                orderedIds.append(item.id)
            }

            var merged: [T] = []
            for id in orderedIds where !mergedTombstones.contains(id) {
                switch (localById[id], remoteById[id]) {
                case (let l?, nil):
                    merged.append(l)
                case (nil, let r?):
                    merged.append(r)
                case (let l?, let r?):
                    if l == r {
                        merged.append(l)
                    } else if l.updatedAtEpochMs >= r.updatedAtEpochMs {
                        conflicts.append(ObjectConflictEntry(
                            collection: name,
                            objectId: id,
                            winningSide: "LOCAL",
                            localUpdatedAtMs: l.updatedAtEpochMs,
                            remoteUpdatedAtMs: r.updatedAtEpochMs
                        ))
                        merged.append(l)
                    } else {
                        conflicts.append(ObjectConflictEntry(
                            collection: name,
                            objectId: id,
                            winningSide: "REMOTE",
                            localUpdatedAtMs: l.updatedAtEpochMs,
                            remoteUpdatedAtMs: r.updatedAtEpochMs
                        ))
                        merged.append(r)
                    }
                case (nil, nil):
                    break
                }
            }
            return merged
        }

        let mergedFolders = mergeCollection("folders", local.folders, remote.folders)
        let mergedSmart = mergeCollection("smartFolders", local.smartFolders, remote.smartFolders)
        let mergedDynamic = mergeCollection("dynamicFolders", local.dynamicFolders, remote.dynamicFolders)
        let mergedConns = mergeCollection("connections", local.connections, remote.connections)
        let mergedCreds = mergeCollection("credentials", local.credentials, remote.credentials)
        let mergedGateways = mergeCollection("gateways", local.gateways, remote.gateways)
        let mergedCmds = mergeCollection("commandTasks", local.commandTasks, remote.commandTasks)
        let mergedMacros = mergeCollection("keySequenceTasks", local.keySequenceTasks, remote.keySequenceTasks)
        let mergedTemplates = mergeCollection("templates", local.templates, remote.templates)
        let mergedTodos = mergeCollection("todoItems", local.todoItems, remote.todoItems)

        let newerRoot = local.updatedAtEpochMs >= remote.updatedAtEpochMs ? local : remote

        var recentSeen = Set<String>()
        var mergedRecents: [String] = []
        for id in (local.recentConnectionIds + remote.recentConnectionIds) {
            if !mergedTombstones.contains(id) && recentSeen.insert(id).inserted {
                mergedRecents.append(id)
                if mergedRecents.count >= 12 { break }
            }
        }

        let actors = Set(local.vectorClock.keys).union(remote.vectorClock.keys).union([localDeviceId])
        var mergedClock: [String: Int64] = [:]
        for actor in actors {
            let maxVal = max(local.vectorClock[actor] ?? 0, remote.vectorClock[actor] ?? 0)
            mergedClock[actor] = actor == localDeviceId ? maxVal + 1 : maxVal
        }

        let mergedDoc = NxtsVaultDocument(
            documentId: local.documentId,
            name: newerRoot.name,
            description: newerRoot.description,
            schemaVersion: max(local.schemaVersion, remote.schemaVersion),
            revision: max(local.revision, remote.revision) + 1,
            lastModifiedByDevice: localDeviceId,
            updatedAtEpochMs: VaultClock.nowMs(),
            vectorClock: mergedClock,
            rootInheritable: newerRoot.rootInheritable,
            folders: mergedFolders,
            smartFolders: mergedSmart,
            dynamicFolders: mergedDynamic,
            connections: mergedConns,
            credentials: mergedCreds,
            gateways: mergedGateways,
            commandTasks: mergedCmds,
            keySequenceTasks: mergedMacros,
            templates: mergedTemplates,
            todoItems: mergedTodos,
            recentConnectionIds: mergedRecents,
            tombstonedObjectIds: mergedTombstones
        )

        return VaultMergeOutcome(
            mergedDocument: mergedDoc,
            relation: relation,
            resolvedConflicts: conflicts
        )
    }
}

// MARK: - 2. iCloud Drive & Security-Scoped Bookmark Live Cloud Sync (Google Drive for Desktop / Finder)

public enum CloudSyncStatusAction: String, Equatable, Sendable {
    case alreadyUpToDate = "Up to date"
    case pushedLocal = "Pushed local changes"
    case pulledRemote = "Pulled newer remote vault"
    case deltaMerged = "3-way delta merged & synced"
}

public struct LiveBookmarkSyncResult: Equatable, Sendable {
    public let document: NxtsVaultDocument
    public let action: CloudSyncStatusAction
    public let conflictCount: Int
    public let syncedFileURL: URL
}

public final class MacCloudBookmarkSyncBridge: @unchecked Sendable {
    private let defaults: UserDefaults
    private let bookmarkKey = "com.nexusts.macos.cloud.securityBookmark"
    private let lastShaKey = "com.nexusts.macos.cloud.lastRemoteSha256"
    private let lastRevKey = "com.nexusts.macos.cloud.lastSyncedRevision"
    private let linkedDocIdKey = "com.nexusts.macos.cloud.linkedDocId"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public static func discoverCloudVaultDirectories() -> [(provider: String, url: URL)] {
        var locations: [(String, URL)] = []
        let fm = FileManager.default
        let home = fm.homeDirectoryForCurrentUser

        // 1. Google Drive for Desktop (~/Library/CloudStorage/GoogleDrive-*) across all localized Drive folder names
        let cloudStorage = home.appendingPathComponent("Library/CloudStorage")
        if let entries = try? fm.contentsOfDirectory(at: cloudStorage, includingPropertiesForKeys: [.isDirectoryKey]) {
            let knownDriveRoots = [
                "My Drive",
                "Meine Ablage",
                "Mon Drive",
                "Il mio Drive",
                "Mi unidad",
                "Ο Drive μου"
            ]
            for entry in entries.sorted(by: { $0.lastPathComponent < $1.lastPathComponent })
            where entry.lastPathComponent.hasPrefix("GoogleDrive") {
                let accountSuffix = entry.lastPathComponent
                    .replacingOccurrences(of: "GoogleDrive-", with: "")
                let label = accountSuffix.isEmpty || accountSuffix == "GoogleDrive"
                    ? "Google Drive"
                    : "Google Drive (\(accountSuffix))"
                var resolvedRoot: URL? = nil
                for candidate in knownDriveRoots {
                    let candidateUrl = entry.appendingPathComponent(candidate)
                    if fm.fileExists(atPath: candidateUrl.path) {
                        resolvedRoot = candidateUrl
                        break
                    }
                }
                if resolvedRoot == nil,
                   let children = try? fm.contentsOfDirectory(at: entry, includingPropertiesForKeys: [.isDirectoryKey]) {
                    resolvedRoot = children.first { child in
                        guard !child.lastPathComponent.hasPrefix(".") else { return false }
                        var isDir: ObjCBool = false
                        return fm.fileExists(atPath: child.path, isDirectory: &isDir) && isDir.boolValue
                    }
                }
                let baseRoot = resolvedRoot ?? entry.appendingPathComponent("My Drive")
                locations.append((label, baseRoot.appendingPathComponent("NexusTS Vaults")))
            }
        }

        // 2. iCloud Drive
        let icloudDocs = home.appendingPathComponent("Library/Mobile Documents/com~apple~CloudDocs")
        if fm.fileExists(atPath: icloudDocs.path) {
            locations.append(("iCloud Drive", icloudDocs.appendingPathComponent("NexusTS Vaults")))
        }
        return locations
    }

    public func linkVaultFileURL(_ url: URL) throws {
        #if os(macOS)
        let bookmark: Data
        if let standard = try? url.bookmarkData(
            options: [],
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        ) {
            bookmark = standard
        } else {
            bookmark = try url.bookmarkData(
                options: [.withSecurityScope],
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
        }
        #else
        let bookmark = try url.bookmarkData()
        #endif
        defaults.set(bookmark, forKey: bookmarkKey)
        defaults.removeObject(forKey: lastShaKey)
        defaults.removeObject(forKey: lastRevKey)
        defaults.removeObject(forKey: linkedDocIdKey)
    }

    public func unlinkVaultFile() {
        defaults.removeObject(forKey: bookmarkKey)
        defaults.removeObject(forKey: lastShaKey)
        defaults.removeObject(forKey: lastRevKey)
        defaults.removeObject(forKey: linkedDocIdKey)
    }

    public func resolveLinkedURL() -> URL? {
        guard let data = defaults.data(forKey: bookmarkKey) else { return nil }
        var stale = false
        #if os(macOS)
        let resolved = (try? URL(
            resolvingBookmarkData: data,
            options: [],
            relativeTo: nil,
            bookmarkDataIsStale: &stale
        )) ?? (try? URL(
            resolvingBookmarkData: data,
            options: [.withSecurityScope],
            relativeTo: nil,
            bookmarkDataIsStale: &stale
        ))
        guard let url = resolved else { return nil }
        if stale {
            try? linkVaultFileURL(url)
        }
        return url
        #else
        return try? URL(resolvingBookmarkData: data, bookmarkDataIsStale: &stale)
        #endif
    }

    /// Lightweight check returning `true` when the linked Google Drive / iCloud `.nxts` file on disk
    /// has changed externally relative to our last recorded sync hash.
    public func hasExternalChangeOnDisk(explicitURL: URL? = nil) -> Bool {
        guard let targetURL = explicitURL ?? resolveLinkedURL() else { return false }
        #if os(macOS)
        let scoped = targetURL.startAccessingSecurityScopedResource()
        defer { if scoped { targetURL.stopAccessingSecurityScopedResource() } }
        #endif
        guard FileManager.default.fileExists(atPath: targetURL.path),
              let remoteBytes = try? Data(contentsOf: targetURL),
              !remoteBytes.isEmpty else {
            return false
        }
        let currentSha = sha256Hex(remoteBytes)
        guard let lastSha = defaults.string(forKey: lastShaKey), !lastSha.isEmpty else {
            return true
        }
        return currentSha != lastSha
    }

    public func syncWithLinkedFile(
        localDocument: NxtsVaultDocument,
        passphrase: String,
        explicitURL: URL? = nil,
        kdf: Argon2KdfConfiguration = .enterpriseDefault,
        localIsStarterVault: Bool = false
    ) throws -> LiveBookmarkSyncResult {
        guard let targetURL = explicitURL ?? resolveLinkedURL() else {
            throw NativeBridgeError.serializationFailed("No Google Drive or iCloud Drive .nxts file linked.")
        }
        #if os(macOS)
        let scoped = targetURL.startAccessingSecurityScopedResource()
        defer { if scoped { targetURL.stopAccessingSecurityScopedResource() } }
        #endif

        let fm = FileManager.default
        try fm.createDirectory(
            at: targetURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )

        if !fm.fileExists(atPath: targetURL.path) {
            let sealed = try NxtsVaultSerializer.seal(document: localDocument, passphrase: passphrase, kdf: kdf)
            try writeAtomically(sealed, to: targetURL)
            recordSyncState(docId: localDocument.documentId, rev: localDocument.revision, sha256: sha256Hex(sealed))
            return LiveBookmarkSyncResult(
                document: localDocument,
                action: .pushedLocal,
                conflictCount: 0,
                syncedFileURL: targetURL
            )
        }

        let remoteBytes = try Data(contentsOf: targetURL)
        if remoteBytes.isEmpty {
            let sealed = try NxtsVaultSerializer.seal(document: localDocument, passphrase: passphrase, kdf: kdf)
            try writeAtomically(sealed, to: targetURL)
            recordSyncState(docId: localDocument.documentId, rev: localDocument.revision, sha256: sha256Hex(sealed))
            return LiveBookmarkSyncResult(
                document: localDocument,
                action: .pushedLocal,
                conflictCount: 0,
                syncedFileURL: targetURL
            )
        }

        let remoteSha = sha256Hex(remoteBytes)
        let lastSha = defaults.string(forKey: lastShaKey)
        let lastRev = Int64(defaults.integer(forKey: lastRevKey))

        if remoteSha == lastSha {
            if localDocument.revision > lastRev {
                let sealed = try NxtsVaultSerializer.seal(document: localDocument, passphrase: passphrase, kdf: kdf)
                try writeAtomically(sealed, to: targetURL)
                recordSyncState(docId: localDocument.documentId, rev: localDocument.revision, sha256: sha256Hex(sealed))
                return LiveBookmarkSyncResult(
                    document: localDocument,
                    action: .pushedLocal,
                    conflictCount: 0,
                    syncedFileURL: targetURL
                )
            }
            return LiveBookmarkSyncResult(
                document: localDocument,
                action: .alreadyUpToDate,
                conflictCount: 0,
                syncedFileURL: targetURL
            )
        }

        let remoteDoc = try NxtsVaultSerializer.open(sealedData: remoteBytes, passphrase: passphrase)

        // Fresh starter vault linking to an existing Google Drive vault -> adopt remote vault cleanly
        if localIsStarterVault || (lastSha == nil && localDocument.revision <= 1 && remoteDoc.documentId != localDocument.documentId) {
            recordSyncState(docId: remoteDoc.documentId, rev: remoteDoc.revision, sha256: remoteSha)
            return LiveBookmarkSyncResult(
                document: remoteDoc,
                action: .pulledRemote,
                conflictCount: 0,
                syncedFileURL: targetURL
            )
        }

        // Remote file changed on Google Drive while zero local edits occurred since our last sync -> pull remote cleanly
        if let lastSha, !lastSha.isEmpty, remoteSha != lastSha,
           localDocument.revision <= lastRev,
           remoteDoc.documentId == localDocument.documentId {
            recordSyncState(docId: remoteDoc.documentId, rev: remoteDoc.revision, sha256: remoteSha)
            return LiveBookmarkSyncResult(
                document: remoteDoc,
                action: .pulledRemote,
                conflictCount: 0,
                syncedFileURL: targetURL
            )
        }

        let clockRel = VaultDeltaConflictResolver.compareVectorClocks(
            local: localDocument.vectorClock,
            remote: remoteDoc.vectorClock
        )

        switch clockRel {
        case .IDENTICAL where remoteDoc == localDocument:
            recordSyncState(docId: localDocument.documentId, rev: localDocument.revision, sha256: remoteSha)
            return LiveBookmarkSyncResult(
                document: localDocument,
                action: .alreadyUpToDate,
                conflictCount: 0,
                syncedFileURL: targetURL
            )
        case .REMOTE_AHEAD where localDocument.revision <= max(lastRev, 1):
            recordSyncState(docId: remoteDoc.documentId, rev: remoteDoc.revision, sha256: remoteSha)
            return LiveBookmarkSyncResult(
                document: remoteDoc,
                action: .pulledRemote,
                conflictCount: 0,
                syncedFileURL: targetURL
            )
        case .LOCAL_AHEAD:
            let sealed = try NxtsVaultSerializer.seal(document: localDocument, passphrase: passphrase, kdf: kdf)
            try writeAtomically(sealed, to: targetURL)
            recordSyncState(docId: localDocument.documentId, rev: localDocument.revision, sha256: sha256Hex(sealed))
            return LiveBookmarkSyncResult(
                document: localDocument,
                action: .pushedLocal,
                conflictCount: 0,
                syncedFileURL: targetURL
            )
        default:
            let outcome = VaultDeltaConflictResolver.mergeDocuments(
                local: localDocument,
                remote: remoteDoc,
                localDeviceId: "macos"
            )
            let sealed = try NxtsVaultSerializer.seal(
                document: outcome.mergedDocument,
                passphrase: passphrase,
                kdf: kdf
            )
            try writeAtomically(sealed, to: targetURL)
            recordSyncState(
                docId: outcome.mergedDocument.documentId,
                rev: outcome.mergedDocument.revision,
                sha256: sha256Hex(sealed)
            )
            return LiveBookmarkSyncResult(
                document: outcome.mergedDocument,
                action: .deltaMerged,
                conflictCount: outcome.resolvedConflicts.count,
                syncedFileURL: targetURL
            )
        }
    }

    private func writeAtomically(_ data: Data, to url: URL) throws {
        let tmp = url.deletingLastPathComponent().appendingPathComponent(".\(url.lastPathComponent).tmp")
        try data.write(to: tmp, options: .atomic)
        _ = try FileManager.default.replaceItemAt(url, withItemAt: tmp)
    }

    private func recordSyncState(docId: String, rev: Int64, sha256: String) {
        defaults.set(docId, forKey: linkedDocIdKey)
        defaults.set(Int(rev), forKey: lastRevKey)
        defaults.set(sha256, forKey: lastShaKey)
    }

    private func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - 3. Google Drive REST API v3 Engine (Cross-Platform Android/Googlebook OS Compatible)

public enum DriveStorageZone: String, Codable, CaseIterable, Sendable {
    case APPDATA_PRIVATE
    case SHARED_TEAM_DRIVE

    public var displayTitle: String {
        switch self {
        case .APPDATA_PRIVATE: return "Private AppData Vault (appDataFolder)"
        case .SHARED_TEAM_DRIVE: return "Google Drive Folder ('NexusTS Vaults' / Team Drive)"
        }
    }
}

public final class GoogleDriveRestSyncEngine: @unchecked Sendable {
    public static let vaultMimeType = "application/vnd.nexusts.vault"
    public static let folderMimeType = "application/vnd.google-apps.folder"
    public static let defaultSharedFolderName = "NexusTS Vaults"

    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func syncVault(
        localDocument: NxtsVaultDocument,
        passphrase: String,
        bearerToken: String,
        zone: DriveStorageZone = .SHARED_TEAM_DRIVE,
        editorIdentity: String = Host.current().localizedName ?? "macOS",
        kdf: Argon2KdfConfiguration = .enterpriseDefault
    ) async throws -> (document: NxtsVaultDocument, action: CloudSyncStatusAction, conflicts: Int) {
        let fileName = localDocument.name.hasSuffix(".nxts") ? localDocument.name : "\(localDocument.name).nxts"
        let folderId: String? = zone == .SHARED_TEAM_DRIVE
            ? try await resolveOrCreateVaultFolder(bearerToken: bearerToken)
            : nil

        if let existing = try await findRemoteVaultFile(
            fileName: fileName,
            zone: zone,
            folderId: folderId,
            bearerToken: bearerToken
        ) {
            let nowMs = VaultClock.nowMs()
            if let holder = existing.appProperties?["nxts_lock_holder"],
               !holder.isEmpty,
               holder != editorIdentity,
               let expiresStr = existing.appProperties?["nxts_lock_expires_ms"],
               let expiresMs = Int64(expiresStr),
               expiresMs > nowMs {
                throw NativeBridgeError.serializationFailed("Vault is currently locked for editing by \(holder).")
            }

            let remoteData = try await downloadDriveFile(fileId: existing.id, bearerToken: bearerToken)
            if NxtsVaultSerializer.hasNxtsMagic(remoteData) {
                let remoteDoc = try NxtsVaultSerializer.open(sealedData: remoteData, passphrase: passphrase)
                let rel = VaultDeltaConflictResolver.compareVectorClocks(
                    local: localDocument.vectorClock,
                    remote: remoteDoc.vectorClock
                )

                switch rel {
                case .IDENTICAL where remoteDoc == localDocument:
                    return (localDocument, .alreadyUpToDate, 0)
                case .REMOTE_AHEAD:
                    return (remoteDoc, .pulledRemote, 0)
                case .LOCAL_AHEAD:
                    let sealed = try NxtsVaultSerializer.seal(document: localDocument, passphrase: passphrase, kdf: kdf)
                    try await uploadMultipartVault(
                        existingFileId: existing.id,
                        fileName: fileName,
                        zone: zone,
                        parentFolderId: folderId,
                        document: localDocument,
                        sealedBytes: sealed,
                        bearerToken: bearerToken,
                        description: localDocument.description
                    )
                    return (localDocument, .pushedLocal, 0)
                default:
                    let merged = VaultDeltaConflictResolver.mergeDocuments(
                        local: localDocument,
                        remote: remoteDoc,
                        localDeviceId: "macos"
                    )
                    let sealed = try NxtsVaultSerializer.seal(
                        document: merged.mergedDocument,
                        passphrase: passphrase,
                        kdf: kdf
                    )
                    try await uploadMultipartVault(
                        existingFileId: existing.id,
                        fileName: fileName,
                        zone: zone,
                        parentFolderId: folderId,
                        document: merged.mergedDocument,
                        sealedBytes: sealed,
                        bearerToken: bearerToken,
                        description: "NexusTS Delta-Merged Encrypted Vault"
                    )
                    return (merged.mergedDocument, .deltaMerged, merged.resolvedConflicts.count)
                }
            }
        }

        let sealed = try NxtsVaultSerializer.seal(document: localDocument, passphrase: passphrase, kdf: kdf)
        try await uploadMultipartVault(
            existingFileId: nil,
            fileName: fileName,
            zone: zone,
            parentFolderId: folderId,
            document: localDocument,
            sealedBytes: sealed,
            bearerToken: bearerToken,
            description: localDocument.description
        )
        return (localDocument, .pushedLocal, 0)
    }

    private struct DriveFileMetadata: Decodable {
        let id: String
        let name: String
        let appProperties: [String: String]?
    }

    private struct DriveFileListResponse: Decodable {
        let files: [DriveFileMetadata]
    }

    private func resolveOrCreateVaultFolder(bearerToken: String) async throws -> String {
        let q = "mimeType = '\(Self.folderMimeType)' and name = '\(Self.defaultSharedFolderName)' and trashed = false"
        var comps = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
        comps.queryItems = [
            URLQueryItem(name: "q", value: q),
            URLQueryItem(name: "spaces", value: "drive"),
            URLQueryItem(name: "supportsAllDrives", value: "true"),
            URLQueryItem(name: "includeItemsFromAllDrives", value: "true"),
            URLQueryItem(name: "fields", value: "files(id,name)")
        ]
        var req = URLRequest(url: comps.url!)
        req.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        let (data, _) = try await session.data(for: req)
        if let list = try? JSONDecoder().decode(DriveFileListResponse.self, from: data),
           let first = list.files.first {
            return first.id
        }

        var createReq = URLRequest(url: URL(string: "https://www.googleapis.com/drive/v3/files?supportsAllDrives=true")!)
        createReq.httpMethod = "POST"
        createReq.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        createReq.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        let body: [String: Any] = [
            "name": Self.defaultSharedFolderName,
            "mimeType": Self.folderMimeType
        ]
        createReq.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (createdData, _) = try await session.data(for: createReq)
        let created = try JSONDecoder().decode(DriveFileMetadata.self, from: createdData)
        return created.id
    }

    private func findRemoteVaultFile(
        fileName: String,
        zone: DriveStorageZone,
        folderId: String?,
        bearerToken: String
    ) async throws -> DriveFileMetadata? {
        let escaped = fileName.replacingOccurrences(of: "'", with: "\\'")
        var query = "name = '\(escaped)' and trashed = false"
        if zone == .SHARED_TEAM_DRIVE, let folderId {
            query += " and '\(folderId)' in parents"
        }
        var comps = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
        comps.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "spaces", value: zone == .APPDATA_PRIVATE ? "appDataFolder" : "drive"),
            URLQueryItem(name: "supportsAllDrives", value: "true"),
            URLQueryItem(name: "includeItemsFromAllDrives", value: "true"),
            URLQueryItem(name: "orderBy", value: "modifiedTime desc"),
            URLQueryItem(name: "fields", value: "files(id,name,appProperties)")
        ]
        var req = URLRequest(url: comps.url!)
        req.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        let (data, _) = try await session.data(for: req)
        let decoded = try JSONDecoder().decode(DriveFileListResponse.self, from: data)
        return decoded.files.first
    }

    private func downloadDriveFile(fileId: String, bearerToken: String) async throws -> Data {
        let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(fileId)?alt=media&supportsAllDrives=true")!
        var req = URLRequest(url: url)
        req.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        let (data, _) = try await session.data(for: req)
        return data
    }

    private func uploadMultipartVault(
        existingFileId: String?,
        fileName: String,
        zone: DriveStorageZone,
        parentFolderId: String?,
        document: NxtsVaultDocument,
        sealedBytes: Data,
        bearerToken: String,
        description: String
    ) async throws {
        let boundary = "nxts_vault_boundary_\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))"
        let endpoint: URL = {
            if let existingFileId {
                return URL(string: "https://www.googleapis.com/upload/drive/v3/files/\(existingFileId)?uploadType=multipart&supportsAllDrives=true")!
            }
            return URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true")!
        }()

        var meta: [String: Any] = [
            "name": fileName,
            "mimeType": Self.vaultMimeType,
            "description": description,
            "appProperties": [
                "nxts_doc_id": document.documentId,
                "nxts_rev": "\(document.revision)",
                "nxts_lock_holder": "",
                "nxts_lock_expires_ms": "0"
            ]
        ]
        if existingFileId == nil {
            if zone == .APPDATA_PRIVATE {
                meta["parents"] = ["appDataFolder"]
            } else if let parentFolderId {
                meta["parents"] = [parentFolderId]
            }
        }
        let metaJson = try JSONSerialization.data(withJSONObject: meta)

        var body = Data()
        body.append(Data("--\(boundary)\r\n".utf8))
        body.append(Data("Content-Type: application/json; charset=UTF-8\r\n\r\n".utf8))
        body.append(metaJson)
        body.append(Data("\r\n--\(boundary)\r\n".utf8))
        body.append(Data("Content-Type: \(Self.vaultMimeType)\r\n\r\n".utf8))
        body.append(sealedBytes)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))

        var req = URLRequest(url: endpoint)
        req.httpMethod = existingFileId == nil ? "POST" : "PATCH"
        req.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        req.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.httpBody = body

        let (_, resp) = try await session.data(for: req)
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw NativeBridgeError.serializationFailed("Google Drive API upload returned HTTP \(http.statusCode)")
        }
    }
}
