import CNexusTSNative
import Foundation

public enum NativeBridgeError: LocalizedError, Equatable, Sendable {
    case invalidVaultHeader
    case authenticationFailed(String)
    case serializationFailed(String)
    case sshTransportError(String)
    case sftpError(String)
    case vncDecodeError(String)
    case networkUtilityError(String)

    public var errorDescription: String? {
        switch self {
        case .invalidVaultHeader:
            return "Invalid or unsupported .nxts v2 vault container header."
        case .authenticationFailed(let detail):
            return detail.isEmpty ? "Vault authentication failed: invalid passphrase or tampered container." : detail
        case .serializationFailed(let detail):
            return "Vault serialization failed: \(detail)"
        case .sshTransportError(let detail):
            return "SSH transport error: \(detail)"
        case .sftpError(let detail):
            return "SFTP operation error: \(detail)"
        case .vncDecodeError(let detail):
            return "VNC framebuffer decode error: \(detail)"
        case .networkUtilityError(let detail):
            return detail
        }
    }
}

// MARK: - 1. .nxts v2 Zero-Knowledge Vault Serializer (Rust Argon2id + AES-256-GCM)

public struct Argon2KdfConfiguration: Codable, Equatable, Sendable {
    public var memoryKiB: UInt32
    public var iterations: UInt32
    public var parallelism: UInt32

    public static let enterpriseDefault = Argon2KdfConfiguration(
        memoryKiB: 65_536,
        iterations: 3,
        parallelism: 4
    )

    public static let interactiveFast = Argon2KdfConfiguration(
        memoryKiB: 16_384,
        iterations: 2,
        parallelism: 2
    )

    public init(memoryKiB: UInt32 = 65_536, iterations: UInt32 = 3, parallelism: UInt32 = 4) {
        self.memoryKiB = max(8_192, memoryKiB)
        self.iterations = max(1, iterations)
        self.parallelism = max(1, parallelism)
    }
}

public enum NxtsVaultSerializer {
    public static let magicBytes: [UInt8] = [0x4E, 0x58, 0x54, 0x53, 0x00, 0x02]
    public static let headerLength = 70
    public static let defaultTransparentPassphrase = "NexusTS-LiveDriveSync-ZeroKnowledge-v1"

    public static func hasNxtsMagic(_ data: Data) -> Bool {
        guard data.count >= headerLength + 16 else { return false }
        return Array(data.prefix(6)) == magicBytes
    }

    public static func inspectRevisionHeader(_ data: Data) -> UInt64? {
        guard hasNxtsMagic(data) else { return nil }
        let revBytes = Array(data[62..<70])
        return revBytes.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
    }

    public static func seal(
        document: NxtsVaultDocument,
        passphrase: String,
        kdf: Argon2KdfConfiguration = .enterpriseDefault
    ) throws -> Data {
        let encoder = JSONEncoder()
        let jsonPlaintext: Data
        do {
            jsonPlaintext = try encoder.encode(document)
        } catch {
            throw NativeBridgeError.serializationFailed(error.localizedDescription)
        }
        return try sealRaw(
            plaintext: jsonPlaintext,
            passphrase: Data(passphrase.utf8),
            revision: UInt64(max(1, document.revision)),
            kdf: kdf
        )
    }

    public static func sealRaw(
        plaintext: Data,
        passphrase: Data,
        revision: UInt64,
        kdf: Argon2KdfConfiguration = .enterpriseDefault
    ) throws -> Data {
        let buf: NexusFfiBuffer = plaintext.withUnsafeBytes { plainRaw in
            passphrase.withUnsafeBytes { passRaw in
                let plainPtr = plainRaw.baseAddress?.assumingMemoryBound(to: UInt8.self)
                let passPtr = passRaw.baseAddress?.assumingMemoryBound(to: UInt8.self)
                return nexusts_ffi_seal_vault(
                    plainPtr,
                    plaintext.count,
                    passPtr,
                    passphrase.count,
                    max(1, revision),
                    kdf.memoryKiB,
                    kdf.iterations,
                    kdf.parallelism
                )
            }
        }
        defer { nexusts_ffi_free_buffer(buf) }
        if let errPtr = buf.error_msg {
            throw NativeBridgeError.authenticationFailed(String(cString: errPtr))
        }
        guard let ptr = buf.data, buf.len > 0 else {
            throw NativeBridgeError.serializationFailed("Rust FFI returned empty .nxts container")
        }
        return Data(bytes: ptr, count: buf.len)
    }

    public static func open(
        sealedData: Data,
        passphrase: String
    ) throws -> NxtsVaultDocument {
        guard hasNxtsMagic(sealedData) else {
            throw NativeBridgeError.invalidVaultHeader
        }
        let (plaintext, headerRevision) = try openRaw(
            sealedData: sealedData,
            passphrase: Data(passphrase.utf8)
        )
        let decoder = JSONDecoder()
        do {
            var doc = try decoder.decode(NxtsVaultDocument.self, from: plaintext)
            if Int64(headerRevision) > doc.revision {
                doc.revision = Int64(headerRevision)
            }
            return doc
        } catch {
            throw NativeBridgeError.serializationFailed(error.localizedDescription)
        }
    }

    public static func openRaw(
        sealedData: Data,
        passphrase: Data
    ) throws -> (plaintext: Data, revision: UInt64) {
        guard hasNxtsMagic(sealedData) else {
            throw NativeBridgeError.invalidVaultHeader
        }
        var revisionOut: UInt64 = 0
        let buf: NexusFfiBuffer = sealedData.withUnsafeBytes { containerRaw in
            passphrase.withUnsafeBytes { passRaw in
                let cPtr = containerRaw.baseAddress?.assumingMemoryBound(to: UInt8.self)
                let pPtr = passRaw.baseAddress?.assumingMemoryBound(to: UInt8.self)
                return nexusts_ffi_open_vault(
                    cPtr,
                    sealedData.count,
                    pPtr,
                    passphrase.count,
                    &revisionOut
                )
            }
        }
        defer { nexusts_ffi_free_buffer(buf) }
        if let errPtr = buf.error_msg {
            throw NativeBridgeError.authenticationFailed(String(cString: errPtr))
        }
        guard let ptr = buf.data else {
            return (Data(), revisionOut)
        }
        return (Data(bytes: ptr, count: buf.len), revisionOut)
    }
}

// MARK: - 2. Ed25519 SSH Keypair Generator & In-Memory Agent

public struct GeneratedEd25519Keypair: Codable, Equatable, Sendable {
    public let keyId: String
    public let publicKeyOpenSsh: String
    public let privateKeyOpenSshPem: String
    public let sha256Fingerprint: String

    enum CodingKeys: String, CodingKey {
        case keyId = "key_id"
        case publicKeyOpenSsh = "public_key_openssh"
        case privateKeyOpenSshPem = "private_key_openssh_pem"
        case sha256Fingerprint = "sha256_fingerprint"
    }
}

public enum NativeSshCryptoBridge {
    public static func generateEd25519Keypair(comment: String = "ops@nexusts-macos") throws -> GeneratedEd25519Keypair {
        let jsonPtr = comment.withCString { cPtr in
            nexusts_ffi_generate_ed25519_keypair_json(cPtr)
        }
        guard let jsonPtr else {
            throw NativeBridgeError.serializationFailed("Failed to generate Ed25519 keypair via Rust FFI")
        }
        defer { nexusts_ffi_free_cstr(jsonPtr) }
        let jsonData = Data(String(cString: jsonPtr).utf8)
        return try JSONDecoder().decode(GeneratedEd25519Keypair.self, from: jsonData)
    }

    public static func signChallenge(keyId: String, challenge: Data) throws -> Data {
        let buf: NexusFfiBuffer = keyId.withCString { kPtr in
            challenge.withUnsafeBytes { raw in
                nexusts_ffi_ssh_agent_sign(
                    kPtr,
                    raw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                    challenge.count
                )
            }
        }
        defer { nexusts_ffi_free_buffer(buf) }
        if let err = buf.error_msg {
            throw NativeBridgeError.sshTransportError(String(cString: err))
        }
        guard let dataPtr = buf.data else { return Data() }
        return Data(bytes: dataPtr, count: buf.len)
    }

    public static func clearAgentKeys() {
        nexusts_ffi_ssh_agent_clear()
    }
}

// MARK: - 3. xterm-256color / 24-Bit TrueColor VT Terminal Buffer

public struct VtViewportSnapshot: Codable, Equatable, Sendable {
    public let cols: Int
    public let rows: Int
    public let cursorCol: Int
    public let cursorRow: Int
    public let cursorVisible: Bool
    public let bracketedPasteMode: Bool
    public let title: String
    public let visibleLines: [String]
    public let scrollbackLines: [String]
    public let packedRgbaAttrs: [UInt64]

    enum CodingKeys: String, CodingKey {
        case cols, rows, title
        case cursorCol = "cursor_col"
        case cursorRow = "cursor_row"
        case cursorVisible = "cursor_visible"
        case bracketedPasteMode = "bracketed_paste_mode"
        case visibleLines = "visible_lines"
        case scrollbackLines = "scrollback_lines"
        case packedRgbaAttrs = "packed_rgba_attrs"
    }

    public static func empty(cols: Int = 100, rows: Int = 30) -> VtViewportSnapshot {
        VtViewportSnapshot(
            cols: cols,
            rows: rows,
            cursorCol: 0,
            cursorRow: 0,
            cursorVisible: true,
            bracketedPasteMode: false,
            title: "",
            visibleLines: Array(repeating: "", count: rows),
            scrollbackLines: [],
            packedRgbaAttrs: []
        )
    }
}

public final class NativeVtTerminalEngine: @unchecked Sendable {
    private let handle: Int64
    private let lock = NSLock()

    public init(cols: Int = 110, rows: Int = 32, maxScrollback: Int = 10_000) {
        self.handle = nexusts_ffi_vt_create(
            UInt32(max(10, cols)),
            UInt32(max(4, rows)),
            UInt32(max(0, maxScrollback))
        )
    }

    deinit {
        if handle > 0 {
            nexusts_ffi_vt_destroy(handle)
        }
    }

    public func resize(cols: Int, rows: Int) {
        lock.lock()
        defer { lock.unlock() }
        guard handle > 0 else { return }
        nexusts_ffi_vt_resize(handle, UInt32(max(10, cols)), UInt32(max(4, rows)))
    }

    public func feedAndSnapshot(_ data: Data) -> VtViewportSnapshot? {
        lock.lock()
        defer { lock.unlock() }
        guard handle > 0 else { return nil }
        let cstrPtr: UnsafeMutablePointer<CChar>? = data.withUnsafeBytes { raw in
            nexusts_ffi_vt_feed_and_snapshot_json(
                handle,
                raw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                data.count
            )
        }
        guard let cstrPtr else { return nil }
        defer { nexusts_ffi_free_cstr(cstrPtr) }
        let json = Data(String(cString: cstrPtr).utf8)
        return try? JSONDecoder().decode(VtViewportSnapshot.self, from: json)
    }

    public func currentSnapshot() -> VtViewportSnapshot? {
        lock.lock()
        defer { lock.unlock() }
        guard handle > 0, let cstrPtr = nexusts_ffi_vt_snapshot_json(handle) else { return nil }
        defer { nexusts_ffi_free_cstr(cstrPtr) }
        let json = Data(String(cString: cstrPtr).utf8)
        return try? JSONDecoder().decode(VtViewportSnapshot.self, from: json)
    }

    public static func processTelnetWire(
        cols: UInt16,
        rows: UInt16,
        term: String = "xterm-256color",
        wireBytes: Data
    ) -> (cleanTerminalBytes: Data, replyBytes: Data) {
        var repliesBuf = NexusFfiBuffer(data: nil, len: 0, cap: 0, error_msg: nil)
        let cleanBuf: NexusFfiBuffer = term.withCString { termPtr in
            wireBytes.withUnsafeBytes { raw in
                nexusts_ffi_telnet_process(
                    cols,
                    rows,
                    termPtr,
                    raw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                    wireBytes.count,
                    &repliesBuf
                )
            }
        }
        defer {
            nexusts_ffi_free_buffer(cleanBuf)
            nexusts_ffi_free_buffer(repliesBuf)
        }
        let clean = cleanBuf.data != nil ? Data(bytes: cleanBuf.data, count: cleanBuf.len) : Data()
        let replies = repliesBuf.data != nil ? Data(bytes: repliesBuf.data, count: repliesBuf.len) : Data()
        return (clean, replies)
    }
}

// MARK: - 4. Async SSHv2 (russh) + Bastion + Port Forwards + SFTP v3

public struct SshFfiConnectPayload: Codable, Sendable {
    public enum Auth: Codable, Sendable {
        case password(String)
        case privateKeyPem(pem: String, passphrase: String?)

        enum TopKeys: String, CodingKey {
            case Password, PrivateKeyPem
        }
        enum PassKeys: String, CodingKey { case password }
        enum KeyKeys: String, CodingKey { case pem, passphrase }

        public func encode(to encoder: Encoder) throws {
            var top = encoder.container(keyedBy: TopKeys.self)
            switch self {
            case .password(let pw):
                var nested = top.nestedContainer(keyedBy: PassKeys.self, forKey: .Password)
                try nested.encode(pw, forKey: .password)
            case .privateKeyPem(let pem, let pass):
                var nested = top.nestedContainer(keyedBy: KeyKeys.self, forKey: .PrivateKeyPem)
                try nested.encode(pem, forKey: .pem)
                try nested.encodeIfPresent(pass, forKey: .passphrase)
            }
        }

        public init(from decoder: Decoder) throws {
            let top = try decoder.container(keyedBy: TopKeys.self)
            if top.contains(.Password) {
                let nested = try top.nestedContainer(keyedBy: PassKeys.self, forKey: .Password)
                self = .password(try nested.decode(String.self, forKey: .password))
            } else {
                let nested = try top.nestedContainer(keyedBy: KeyKeys.self, forKey: .PrivateKeyPem)
                self = .privateKeyPem(
                    pem: try nested.decode(String.self, forKey: .pem),
                    passphrase: try nested.decodeIfPresent(String.self, forKey: .passphrase)
                )
            }
        }
    }

    public var host: String
    public var port: UInt16
    public var username: String
    public var auth: Auth
    public var expectedHostFingerprintSha256: String?
    public var termEnv: String
    public var initialCols: UInt32
    public var initialRows: UInt32
    public var enableX11: Bool
    public var keepaliveSecs: UInt64

    enum CodingKeys: String, CodingKey {
        case host, port, username, auth
        case expectedHostFingerprintSha256 = "expected_host_fingerprint_sha256"
        case termEnv = "term_env"
        case initialCols = "initial_cols"
        case initialRows = "initial_rows"
        case enableX11 = "enable_x11"
        case keepaliveSecs = "keepalive_secs"
    }

    public init(
        host: String,
        port: UInt16 = 22,
        username: String,
        auth: Auth,
        expectedHostFingerprintSha256: String? = nil,
        termEnv: String = "xterm-256color",
        initialCols: UInt32 = 110,
        initialRows: UInt32 = 32,
        enableX11: Bool = false,
        keepaliveSecs: UInt64 = 30
    ) {
        self.host = host
        self.port = port
        self.username = username
        self.auth = auth
        self.expectedHostFingerprintSha256 = expectedHostFingerprintSha256
        self.termEnv = termEnv
        self.initialCols = initialCols
        self.initialRows = initialRows
        self.enableX11 = enableX11
        self.keepaliveSecs = keepaliveSecs
    }
}

public final class NativeSshSession: @unchecked Sendable {
    public private(set) var handle: Int64

    private init(handle: Int64) {
        self.handle = handle
    }

    deinit {
        disconnect()
    }

    public static func connect(
        spec: SshFfiConnectPayload,
        bastion: SshFfiConnectPayload? = nil,
        openPty: Bool = true
    ) throws -> NativeSshSession {
        let specJson = String(decoding: try JSONEncoder().encode(spec), as: UTF8.self)
        let bastionJson = try bastion.map { String(decoding: try JSONEncoder().encode($0), as: UTF8.self) } ?? ""
        var errPtr: UnsafeMutablePointer<CChar>? = nil

        let sessionId: Int64 = specJson.withCString { sPtr in
            bastionJson.withCString { bPtr in
                nexusts_ffi_ssh_connect_json(
                    sPtr,
                    bastion == nil ? nil : bPtr,
                    openPty,
                    &errPtr
                )
            }
        }

        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.sshTransportError(String(cString: errPtr))
        }
        guard sessionId > 0 else {
            throw NativeBridgeError.sshTransportError("SSH session failed to initialize")
        }
        return NativeSshSession(handle: sessionId)
    }

    @discardableResult
    public func sendInput(_ data: Data) -> Bool {
        guard handle > 0 else { return false }
        return data.withUnsafeBytes { raw in
            nexusts_ffi_ssh_send_input(
                handle,
                raw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                data.count
            )
        }
    }

    public func drainOutput() -> (bytes: Data, eof: Bool) {
        guard handle > 0 else { return (Data(), true) }
        var isEof = false
        let buf = nexusts_ffi_ssh_drain_output(handle, &isEof)
        defer { nexusts_ffi_free_buffer(buf) }
        let out = buf.data != nil && buf.len > 0 ? Data(bytes: buf.data, count: buf.len) : Data()
        return (out, isEof)
    }

    public func resizePty(cols: Int, rows: Int) {
        guard handle > 0 else { return }
        _ = nexusts_ffi_ssh_resize_pty(handle, UInt32(max(10, cols)), UInt32(max(4, rows)))
    }

    public func startLocalPortForward(
        bindAddress: String,
        bindPort: UInt16,
        targetHost: String,
        targetPort: UInt16
    ) throws {
        guard handle > 0 else { return }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = bindAddress.withCString { bPtr in
            targetHost.withCString { tPtr in
                nexusts_ffi_ssh_start_local_forward(handle, bPtr, bindPort, tPtr, targetPort, &errPtr)
            }
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.sshTransportError(String(cString: errPtr))
        }
        if !ok {
            throw NativeBridgeError.sshTransportError("Failed to bind local port forward \(bindAddress):\(bindPort)")
        }
    }

    public func startDynamicSocks5Forward(bindAddress: String, bindPort: UInt16) throws {
        guard handle > 0 else { return }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = bindAddress.withCString { bPtr in
            nexusts_ffi_ssh_start_socks5_forward(handle, bPtr, bindPort, &errPtr)
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.sshTransportError(String(cString: errPtr))
        }
        if !ok {
            throw NativeBridgeError.sshTransportError("Failed to bind SOCKS5 proxy \(bindAddress):\(bindPort)")
        }
    }

    public func openSftpSubsystem() throws -> NativeSftpSession {
        guard handle > 0 else {
            throw NativeBridgeError.sftpError("Underlying SSH session is closed")
        }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let sftpId = nexusts_ffi_sftp_open_on_ssh(handle, &errPtr)
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.sftpError(String(cString: errPtr))
        }
        guard sftpId > 0 else {
            throw NativeBridgeError.sftpError("Failed to open SFTP v3 subsystem")
        }
        return NativeSftpSession(handle: sftpId)
    }

    public func disconnect() {
        if handle > 0 {
            nexusts_ffi_ssh_disconnect(handle)
            handle = 0
        }
    }
}

public struct SftpRemoteEntry: Codable, Equatable, Hashable, Identifiable, Sendable {
    public var id: String { fullPath }
    public let name: String
    public let fullPath: String
    public let isDirectory: Bool
    public let isSymlink: Bool
    public let sizeBytes: UInt64
    public let unixMode: UInt32
    public let modifiedEpochSec: UInt64
    public let owner: String

    enum CodingKeys: String, CodingKey {
        case name, owner
        case fullPath = "full_path"
        case isDirectory = "is_directory"
        case isSymlink = "is_symlink"
        case sizeBytes = "size_bytes"
        case unixMode = "unix_mode"
        case modifiedEpochSec = "modified_epoch_sec"
    }

    public init(
        name: String,
        fullPath: String,
        isDirectory: Bool,
        isSymlink: Bool = false,
        sizeBytes: UInt64,
        unixMode: UInt32 = 0o644,
        modifiedEpochSec: UInt64 = 0,
        owner: String = "user"
    ) {
        self.name = name
        self.fullPath = fullPath
        self.isDirectory = isDirectory
        self.isSymlink = isSymlink
        self.sizeBytes = sizeBytes
        self.unixMode = unixMode
        self.modifiedEpochSec = modifiedEpochSec
        self.owner = owner
    }

    public var rwxPermissions: String {
        let prefix = isDirectory ? "d" : (isSymlink ? "l" : "-")
        func triplet(_ shift: UInt32) -> String {
            let bits = (unixMode >> shift) & 0b111
            let r = (bits & 0b100) != 0 ? "r" : "-"
            let w = (bits & 0b010) != 0 ? "w" : "-"
            let x = (bits & 0b001) != 0 ? "x" : "-"
            return "\(r)\(w)\(x)"
        }
        return "\(prefix)\(triplet(6))\(triplet(3))\(triplet(0))"
    }
}

public final class NativeSftpSession: @unchecked Sendable {
    private var handle: Int64

    init(handle: Int64) {
        self.handle = handle
    }

    deinit {
        close()
    }

    public func listDirectory(_ remotePath: String) throws -> [SftpRemoteEntry] {
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let jsonPtr = remotePath.withCString { pPtr in
            nexusts_ffi_sftp_list_dir_json(handle, pPtr, &errPtr)
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.sftpError(String(cString: errPtr))
        }
        guard let jsonPtr else { return [] }
        defer { nexusts_ffi_free_cstr(jsonPtr) }
        return try JSONDecoder().decode([SftpRemoteEntry].self, from: Data(String(cString: jsonPtr).utf8))
    }

    public func createDirectory(_ remotePath: String) throws {
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = remotePath.withCString { nexusts_ffi_sftp_mkdir(handle, $0, &errPtr) }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.sftpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.sftpError("mkdir failed for \(remotePath)") }
    }

    public func removeEntry(_ remotePath: String, isDirectory: Bool) throws {
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = remotePath.withCString { nexusts_ffi_sftp_remove(handle, $0, isDirectory, &errPtr) }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.sftpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.sftpError("rm failed for \(remotePath)") }
    }

    public func renameEntry(from oldPath: String, to newPath: String) throws {
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = oldPath.withCString { oPtr in
            newPath.withCString { nPtr in
                nexusts_ffi_sftp_rename(handle, oPtr, nPtr, &errPtr)
            }
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.sftpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.sftpError("rename failed") }
    }

    public func downloadBytes(_ remotePath: String) throws -> Data {
        let buf = remotePath.withCString { nexusts_ffi_sftp_download(handle, $0) }
        defer { nexusts_ffi_free_buffer(buf) }
        if let err = buf.error_msg {
            throw NativeBridgeError.sftpError(String(cString: err))
        }
        guard let ptr = buf.data else { return Data() }
        return Data(bytes: ptr, count: buf.len)
    }

    public func uploadBytes(_ data: Data, to remotePath: String) throws {
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = remotePath.withCString { pPtr in
            data.withUnsafeBytes { raw in
                nexusts_ffi_sftp_upload(
                    handle,
                    pPtr,
                    raw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                    data.count,
                    &errPtr
                )
            }
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.sftpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.sftpError("upload failed for \(remotePath)") }
    }

    public func close() {
        if handle > 0 {
            nexusts_ffi_sftp_close(handle)
            handle = 0
        }
    }
}

// MARK: - 5. RFB / VNC / Apple Screen Sharing Framebuffer Engine

public final class NativeVncFramebuffer: @unchecked Sendable {
    private let handle: Int64
    public private(set) var width: Int
    public private(set) var height: Int

    public init(width: Int = 1280, height: Int = 800) {
        self.width = max(1, width)
        self.height = max(1, height)
        self.handle = nexusts_ffi_vnc_create(UInt32(self.width), UInt32(self.height))
    }

    deinit {
        if handle > 0 {
            nexusts_ffi_vnc_destroy(handle)
        }
    }

    public func resize(width: Int, height: Int) {
        self.width = max(1, width)
        self.height = max(1, height)
        nexusts_ffi_vnc_resize(handle, UInt32(self.width), UInt32(self.height))
    }

    public func applyRectangle(
        encoding: Int32,
        x: UInt16,
        y: UInt16,
        w: UInt16,
        h: UInt16,
        payload: Data
    ) throws {
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = payload.withUnsafeBytes { raw in
            nexusts_ffi_vnc_apply_rect(
                handle,
                encoding,
                x,
                y,
                w,
                h,
                raw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                payload.count,
                &errPtr
            )
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.vncDecodeError(String(cString: errPtr))
        }
        if !ok {
            throw NativeBridgeError.vncDecodeError("RFB rectangle decode failed")
        }
    }

    public func copyRgbaPixels() -> Data {
        let count = width * height * 4
        var buffer = Data(count: count)
        buffer.withUnsafeMutableBytes { raw in
            if let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) {
                _ = nexusts_ffi_vnc_copy_rgba(handle, base, count)
            }
        }
        return buffer
    }
}

// MARK: - 6. FreeRDP 3.x Native macOS Session Bridge

public final class NativeRdpSession: @unchecked Sendable {
    private var handle: Int64 = 0

    public init(
        host: String,
        username: String,
        password: String,
        domain: String,
        settings: RdpProtocolSettings,
        initialWidth: Int = 1920,
        initialHeight: Int = 1080
    ) {
        self.handle = host.withCString { hostPtr in
            username.withCString { userPtr in
                password.withCString { passPtr in
                    domain.withCString { domPtr in
                        settings.rdGatewayHostname.withCString { gwPtr in
                            settings.remoteAppProgram.withCString { appPtr in
                                settings.remoteAppArguments.withCString { argsPtr in
                                    var cfg = NexusRdpConfig()
                                    cfg.host = hostPtr
                                    cfg.port = UInt16(clamping: settings.port)
                                    cfg.username = userPtr
                                    cfg.password = passPtr
                                    cfg.domain = domPtr
                                    cfg.rd_gateway_host = gwPtr
                                    cfg.rd_gateway_port = UInt16(clamping: settings.rdGatewayPort)
                                    cfg.initial_width = UInt32(max(640, initialWidth))
                                    cfg.initial_height = UInt32(max(480, initialHeight))
                                    cfg.desktop_dpi = settings.hiDpiEnabled ? 192 : 96
                                    cfg.connect_to_console = settings.connectToConsoleAdmin
                                    cfg.hidpi_enabled = settings.hiDpiEnabled
                                    cfg.desktop_scale_factor = UInt32(max(100, settings.desktopScaleFactorPercent))
                                    cfg.device_scale_factor = UInt32(max(100, settings.deviceScaleFactorPercent))
                                    cfg.color_depth_bits = UInt32(settings.colorDepthBits)
                                    cfg.enable_gfx_pipeline = settings.enableGfxPipeline
                                    cfg.enable_h264_avc444 = settings.enableH264Avc444
                                    cfg.enable_remotefx = settings.enableRemoteFx
                                    cfg.enable_dynamic_resize = settings.resizeBehavior == .DYNAMIC_DISP_DVC
                                    cfg.multi_monitor_spanning = settings.multiMonitorSpanning
                                    cfg.redirect_clipboard = settings.redirectClipboard
                                    cfg.audio_redirection_mode = settings.audioRedirectionMode.nativeCode
                                    cfg.redirect_audio_out = settings.redirectAudioPlayback
                                    cfg.redirect_mic_in = settings.redirectMicrophone
                                    cfg.redirect_drives = settings.redirectDrives
                                    cfg.redirect_printers = settings.redirectPrinters
                                    cfg.redirect_serial_ports = settings.redirectPorts
                                    cfg.redirect_smartcard = settings.redirectSmartcardYubiKey
                                    cfg.enable_font_smoothing = settings.enableFontSmoothing
                                    cfg.enable_desktop_composition = settings.enableDesktopComposition
                                    cfg.enable_wallpaper = settings.enableWallpaper
                                    cfg.enable_full_window_drag = settings.enableFullWindowDrag
                                    cfg.enable_menu_animations = settings.enableMenuAnimations
                                    cfg.enable_themes = settings.enableThemes
                                    cfg.compression_enabled = settings.compressionEnabled
                                    cfg.nla_security = settings.nlaSecurityRequired
                                    cfg.tls_security = settings.tlsSecurity
                                    cfg.restricted_admin_mode = settings.restrictedAdminMode
                                    cfg.ignore_certificate = settings.ignoreCertificateWarnings
                                    cfg.remote_app_mode = settings.remoteAppMode
                                    cfg.remote_app_program = appPtr
                                    cfg.remote_app_args = argsPtr
                                    return nexusts_rdp_create_session(&cfg)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deinit {
        destroy()
    }

    public func connect() -> Bool {
        guard handle > 0 else { return false }
        return nexusts_rdp_connect(handle)
    }

    public var isConnected: Bool {
        handle > 0 && nexusts_rdp_is_connected(handle)
    }

    @discardableResult
    public func requestDynamicResize(width: Int, height: Int, scalePercent: Int = 100) -> Bool {
        guard handle > 0 else { return false }
        let evenW = UInt32(max(320, width & ~1))
        let evenH = UInt32(max(240, height & ~1))
        return nexusts_rdp_request_resize(handle, evenW, evenH, UInt32(max(100, scalePercent)))
    }

    public func sendScancode(_ scancode: UInt16, keyDown: Bool, extended: Bool = false) {
        guard handle > 0 else { return }
        nexusts_rdp_send_key_scancode(handle, scancode, keyDown, extended)
    }

    public func sendCtrlAltDel() {
        sendScancode(0x1D, keyDown: true, extended: false)
        sendScancode(0x38, keyDown: true, extended: false)
        sendScancode(0x53, keyDown: true, extended: true)
        sendScancode(0x53, keyDown: false, extended: true)
        sendScancode(0x38, keyDown: false, extended: false)
        sendScancode(0x1D, keyDown: false, extended: false)
    }

    public func sendPointerEvent(flags: UInt16, x: Int, y: Int) {
        guard handle > 0 else { return }
        nexusts_rdp_send_pointer_event(
            handle,
            flags,
            UInt16(clamping: max(0, x)),
            UInt16(clamping: max(0, y))
        )
    }

    public func pushLocalClipboard(_ text: String) {
        guard handle > 0 else { return }
        text.withCString { nexusts_rdp_set_local_clipboard(handle, $0) }
    }

    public func pullRemoteClipboard() -> String {
        guard handle > 0, let ptr = nexusts_rdp_copy_remote_clipboard(handle) else { return "" }
        defer { nexusts_rdp_free_cstr(ptr) }
        return String(cString: ptr)
    }

    public var frameSequence: UInt64 {
        handle > 0 ? nexusts_rdp_frame_sequence(handle) : 0
    }

    public func copyLatestFrame() -> (rgba: Data, width: Int, height: Int)? {
        guard handle > 0 else { return nil }
        var w: UInt32 = 0
        var h: UInt32 = 0
        let needed = nexusts_rdp_copy_frame_rgba(handle, nil, 0, &w, &h)
        guard needed > 0, w > 0, h > 0 else { return nil }
        var data = Data(count: needed)
        data.withUnsafeMutableBytes { raw in
            if let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) {
                _ = nexusts_rdp_copy_frame_rgba(handle, base, needed, &w, &h)
            }
        }
        return (data, Int(w), Int(h))
    }

    public func disconnect() {
        if handle > 0 {
            nexusts_rdp_disconnect(handle)
        }
    }

    public func destroy() {
        if handle > 0 {
            nexusts_rdp_destroy_session(handle)
            handle = 0
        }
    }
}

// MARK: - 7. Network Diagnostics Bridge (WoL, Port Probe, Traceroute, Banner, FTP MLSD)

public struct PortProbeHit: Codable, Equatable, Identifiable, Sendable {
    public var id: UInt16 { port }
    public let port: UInt16
    public let open: Bool
    public let connectLatencyMs: UInt64
    public let guessedService: String
    public let serviceBanner: String?

    enum CodingKeys: String, CodingKey {
        case port, open
        case connectLatencyMs = "connect_latency_ms"
        case guessedService = "guessed_service"
        case serviceBanner = "service_banner"
    }
}

public struct TracerouteHop: Codable, Equatable, Identifiable, Sendable {
    public var id: UInt8 { ttl }
    public let ttl: UInt8
    public let responderAddr: String
    public let rttMs: Double
    public let reachedDestination: Bool
    public let timedOut: Bool

    enum CodingKeys: String, CodingKey {
        case ttl
        case responderAddr = "responder_addr"
        case rttMs = "rtt_ms"
        case reachedDestination = "reached_destination"
        case timedOut = "timed_out"
    }
}

public enum NativeNetworkDiagnostics {
    public static func sendWakeOnLan(
        macAddress: String,
        broadcastIp: String = "255.255.255.255",
        udpPort: UInt16 = 9,
        secureOnHex: String? = nil
    ) throws -> Int {
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let sec = secureOnHex ?? ""
        let bytesSent = macAddress.withCString { macPtr in
            broadcastIp.withCString { ipPtr in
                sec.withCString { secPtr in
                    nexusts_ffi_net_wol(
                        macPtr,
                        ipPtr,
                        udpPort,
                        sec.isEmpty ? nil : secPtr,
                        &errPtr
                    )
                }
            }
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.networkUtilityError(String(cString: errPtr))
        }
        if bytesSent < 0 {
            throw NativeBridgeError.networkUtilityError("Failed to broadcast Wake-on-LAN Magic Packet")
        }
        return Int(bytesSent)
    }

    public static func probeTcpPort(host: String, port: UInt16, timeoutMs: UInt64 = 350) -> PortProbeHit? {
        let jsonPtr = host.withCString { nexusts_ffi_net_probe_tcp_json($0, port, timeoutMs) }
        guard let jsonPtr else { return nil }
        defer { nexusts_ffi_free_cstr(jsonPtr) }
        let str = String(cString: jsonPtr)
        if str == "null" || str.isEmpty { return nil }
        return try? JSONDecoder().decode(PortProbeHit.self, from: Data(str.utf8))
    }

    public static func traceRoute(
        host: String,
        probePort: UInt16 = 443,
        maxHops: UInt8 = 24,
        timeoutMs: UInt64 = 450
    ) -> [TracerouteHop] {
        let jsonPtr = host.withCString {
            nexusts_ffi_net_traceroute_json($0, probePort, maxHops, timeoutMs)
        }
        guard let jsonPtr else { return [] }
        defer { nexusts_ffi_free_cstr(jsonPtr) }
        return (try? JSONDecoder().decode([TracerouteHop].self, from: Data(String(cString: jsonPtr).utf8))) ?? []
    }

    public static func grabServiceBanner(host: String, port: UInt16, timeoutMs: UInt64 = 500) -> String {
        let ptr = host.withCString { nexusts_ffi_net_grab_banner($0, port, timeoutMs) }
        guard let ptr else { return "" }
        defer { nexusts_ffi_free_cstr(ptr) }
        return String(cString: ptr)
    }

    public static func parseFtpMlsd(parentDir: String, rawMlsd: String) -> [SftpRemoteEntry] {
        let ptr = parentDir.withCString { pPtr in
            rawMlsd.withCString { mPtr in
                nexusts_ffi_parse_ftp_mlsd_json(pPtr, mPtr)
            }
        }
        guard let ptr else { return [] }
        defer { nexusts_ffi_free_cstr(ptr) }
        return (try? JSONDecoder().decode([SftpRemoteEntry].self, from: Data(String(cString: ptr).utf8))) ?? []
    }
}
