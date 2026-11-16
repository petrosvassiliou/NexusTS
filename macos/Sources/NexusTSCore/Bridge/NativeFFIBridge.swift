import CNexusTSNative
import CoreGraphics
import Foundation

public enum NativeBridgeError: LocalizedError, Equatable, Sendable {
    case invalidVaultHeader
    case authenticationFailed(String)
    case serializationFailed(String)
    case sshTransportError(String)
    case telnetError(String)
    case sftpError(String)
    case scpError(String)
    case ftpError(String)
    case ftpsError(String)
    case vncDecodeError(String)
    case vncSessionError(String)
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
        case .telnetError(let detail):
            return "Telnet error: \(detail)"
        case .sftpError(let detail):
            return "SFTP operation error: \(detail)"
        case .scpError(let detail):
            return "SCP operation error: \(detail)"
        case .ftpError(let detail):
            return "FTP operation error: \(detail)"
        case .ftpsError(let detail):
            return "FTPS operation error: \(detail)"
        case .vncDecodeError(let detail):
            return "VNC framebuffer decode error: \(detail)"
        case .vncSessionError(let detail):
            return "VNC session error: \(detail)"
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

// MARK: - 3. xterm-256color / 24-Bit TrueColor VT Terminal Buffer & Live Telnet

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

    public var applicationCursorKeys: Bool {
        lock.lock()
        defer { lock.unlock() }
        guard handle > 0 else { return false }
        return nexusts_vt_application_cursor_keys(handle)
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

public final class NativeTelnetSession: @unchecked Sendable {
    public private(set) var handle: Int64
    private let lock = NSLock()

    private init(handle: Int64) {
        self.handle = handle
    }

    deinit {
        disconnect()
    }

    public static func connect(
        host: String,
        port: UInt16 = 23,
        cols: Int = 110,
        rows: Int = 32,
        term: String = "xterm-256color"
    ) throws -> NativeTelnetSession {
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let sessionId: Int64 = host.withCString { hPtr in
            term.withCString { tPtr in
                nexusts_telnet_connect(
                    hPtr,
                    port == 0 ? 23 : port,
                    UInt16(clamping: max(10, cols)),
                    UInt16(clamping: max(4, rows)),
                    tPtr,
                    &errPtr
                )
            }
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.telnetError(String(cString: errPtr))
        }
        guard sessionId > 0 else {
            throw NativeBridgeError.telnetError("Telnet session failed to initialize")
        }
        return NativeTelnetSession(handle: sessionId)
    }

    public var isConnected: Bool {
        lock.lock()
        let h = handle
        lock.unlock()
        return h > 0 && nexusts_telnet_is_connected(h)
    }

    @discardableResult
    public func sendInput(_ data: Data) -> Bool {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0, !data.isEmpty else { return false }
        return data.withUnsafeBytes { raw in
            nexusts_telnet_write(
                h,
                raw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                data.count
            )
        }
    }

    public func drainOutput() -> (bytes: Data, eof: Bool) {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { return (Data(), true) }
        var isEof = false
        let buf = nexusts_telnet_read(h, &isEof)
        defer { nexusts_ffi_free_buffer(buf) }
        let out = buf.data != nil && buf.len > 0 ? Data(bytes: buf.data, count: buf.len) : Data()
        return (out, isEof)
    }

    public func resize(cols: Int, rows: Int) {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { return }
        _ = nexusts_telnet_resize(
            h,
            UInt16(clamping: max(10, cols)),
            UInt16(clamping: max(4, rows))
        )
    }

    public func lastErrorMessage() -> String {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0, let ptr = nexusts_telnet_last_error(h) else { return "" }
        defer { nexusts_ffi_free_cstr(ptr) }
        return String(cString: ptr)
    }

    public func disconnect() {
        lock.lock()
        let h = handle
        handle = 0
        lock.unlock()
        if h > 0 {
            nexusts_telnet_disconnect(h)
        }
    }
}

// MARK: - 4. Async SSHv2 (russh) + Bastion + Port Forwards + SFTP v3 + SCP + FTP

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

    public var isConnected: Bool {
        handle > 0 && nexusts_ffi_ssh_is_connected(handle)
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

public protocol RemoteFileTransferClient: AnyObject, Sendable {
    func pwd() throws -> String
    func listDirectory(_ remotePath: String) throws -> [SftpRemoteEntry]
    func createDirectory(_ remotePath: String) throws
    func removeEntry(_ remotePath: String, isDirectory: Bool) throws
    func renameEntry(from oldPath: String, to newPath: String) throws
    func chmod(_ remotePath: String, modeOctal: UInt32) throws
    func downloadBytes(_ remotePath: String) throws -> Data
    func uploadBytes(_ data: Data, to remotePath: String) throws
    func disconnect()
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

public final class NativeSftpSession: RemoteFileTransferClient, @unchecked Sendable {
    private var handle: Int64

    init(handle: Int64) {
        self.handle = handle
    }

    deinit {
        close()
    }

    public func pwd() throws -> String {
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ptr = nexusts_sftp_pwd(handle, &errPtr)
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.sftpError(String(cString: errPtr))
        }
        guard let ptr else { return "/" }
        defer { nexusts_ffi_free_cstr(ptr) }
        return String(cString: ptr)
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

    public func chmod(_ remotePath: String, modeOctal: UInt32) throws {
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = remotePath.withCString { pPtr in
            nexusts_sftp_chmod(handle, pPtr, modeOctal, &errPtr)
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.sftpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.sftpError("chmod failed for \(remotePath)") }
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

    public func disconnect() {
        close()
    }
}

public final class NativeScpClient: RemoteFileTransferClient, @unchecked Sendable {
    public private(set) var handle: Int64
    private let lock = NSLock()

    private init(handle: Int64) {
        self.handle = handle
    }

    deinit {
        disconnect()
    }

    public static func connect(
        spec: SshFfiConnectPayload,
        bastion: SshFfiConnectPayload? = nil
    ) throws -> NativeScpClient {
        let specJson = String(decoding: try JSONEncoder().encode(spec), as: UTF8.self)
        let bastionJson = try bastion.map { String(decoding: try JSONEncoder().encode($0), as: UTF8.self) } ?? ""
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let scpId: Int64 = specJson.withCString { sPtr in
            bastionJson.withCString { bPtr in
                nexusts_scp_connect_json(
                    sPtr,
                    bastion == nil ? nil : bPtr,
                    &errPtr
                )
            }
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.scpError(String(cString: errPtr))
        }
        guard scpId > 0 else {
            throw NativeBridgeError.scpError("SCP session failed to initialize")
        }
        return NativeScpClient(handle: scpId)
    }

    public func pwd() throws -> String {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.scpError("SCP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ptr = nexusts_scp_pwd(h, &errPtr)
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.scpError(String(cString: errPtr))
        }
        guard let ptr else { return "/" }
        defer { nexusts_ffi_free_cstr(ptr) }
        return String(cString: ptr)
    }

    public func listDirectory(_ remotePath: String) throws -> [SftpRemoteEntry] {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.scpError("SCP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let jsonPtr = remotePath.withCString { pPtr in
            nexusts_scp_list_dir_json(h, pPtr, &errPtr)
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.scpError(String(cString: errPtr))
        }
        guard let jsonPtr else { return [] }
        defer { nexusts_ffi_free_cstr(jsonPtr) }
        return try JSONDecoder().decode([SftpRemoteEntry].self, from: Data(String(cString: jsonPtr).utf8))
    }

    public func createDirectory(_ remotePath: String) throws {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.scpError("SCP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = remotePath.withCString { nexusts_scp_mkdir(h, $0, &errPtr) }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.scpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.scpError("mkdir failed for \(remotePath)") }
    }

    public func removeEntry(_ remotePath: String, isDirectory: Bool = false) throws {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.scpError("SCP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = remotePath.withCString { nexusts_scp_remove(h, $0, &errPtr) }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.scpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.scpError("rm failed for \(remotePath)") }
    }

    public func renameEntry(from oldPath: String, to newPath: String) throws {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.scpError("SCP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = oldPath.withCString { oPtr in
            newPath.withCString { nPtr in
                nexusts_scp_rename(h, oPtr, nPtr, &errPtr)
            }
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.scpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.scpError("rename failed") }
    }

    public func chmod(_ remotePath: String, modeOctal: UInt32) throws {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.scpError("SCP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = remotePath.withCString { nexusts_scp_chmod(h, $0, modeOctal, &errPtr) }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.scpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.scpError("chmod failed for \(remotePath)") }
    }

    public func downloadBytes(_ remotePath: String) throws -> Data {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.scpError("SCP session closed") }
        let buf = remotePath.withCString { nexusts_scp_download(h, $0) }
        defer { nexusts_ffi_free_buffer(buf) }
        if let err = buf.error_msg {
            throw NativeBridgeError.scpError(String(cString: err))
        }
        guard let ptr = buf.data else { return Data() }
        return Data(bytes: ptr, count: buf.len)
    }

    public func uploadBytes(_ data: Data, to remotePath: String) throws {
        try uploadBytes(data, to: remotePath, modeOctal: 0o644)
    }

    public func uploadBytes(_ data: Data, to remotePath: String, modeOctal: UInt32) throws {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.scpError("SCP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = remotePath.withCString { pPtr in
            data.withUnsafeBytes { raw in
                nexusts_scp_upload(
                    h,
                    pPtr,
                    raw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                    data.count,
                    modeOctal,
                    &errPtr
                )
            }
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.scpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.scpError("upload failed for \(remotePath)") }
    }

    public func disconnect() {
        lock.lock()
        let h = handle
        handle = 0
        lock.unlock()
        if h > 0 {
            nexusts_scp_disconnect(h)
        }
    }
}

public final class NativeFtpClient: RemoteFileTransferClient, @unchecked Sendable {
    public private(set) var handle: Int64
    private let lock = NSLock()

    private init(handle: Int64) {
        self.handle = handle
    }

    deinit {
        disconnect()
    }

    public static func connect(
        host: String,
        port: UInt16 = 21,
        username: String = "anonymous",
        password: String = ""
    ) throws -> NativeFtpClient {
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ftpId: Int64 = host.withCString { hPtr in
            username.withCString { uPtr in
                password.withCString { pPtr in
                    nexusts_ftp_connect(
                        hPtr,
                        port == 0 ? 21 : port,
                        uPtr,
                        pPtr,
                        &errPtr
                    )
                }
            }
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.ftpError(String(cString: errPtr))
        }
        guard ftpId > 0 else {
            throw NativeBridgeError.ftpError("FTP session failed to initialize")
        }
        return NativeFtpClient(handle: ftpId)
    }

    public func pwd() throws -> String {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.ftpError("FTP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ptr = nexusts_ftp_pwd(h, &errPtr)
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.ftpError(String(cString: errPtr))
        }
        guard let ptr else { return "/" }
        defer { nexusts_ffi_free_cstr(ptr) }
        return String(cString: ptr)
    }

    public func cwd(_ remoteDir: String) throws {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.ftpError("FTP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = remoteDir.withCString { nexusts_ftp_cwd(h, $0, &errPtr) }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.ftpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.ftpError("CWD failed for \(remoteDir)") }
    }

    public func listDirectory(_ remotePath: String) throws -> [SftpRemoteEntry] {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.ftpError("FTP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let jsonPtr = remotePath.withCString { pPtr in
            nexusts_ftp_list_dir_json(h, pPtr, &errPtr)
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.ftpError(String(cString: errPtr))
        }
        guard let jsonPtr else { return [] }
        defer { nexusts_ffi_free_cstr(jsonPtr) }
        return try JSONDecoder().decode([SftpRemoteEntry].self, from: Data(String(cString: jsonPtr).utf8))
    }

    public func createDirectory(_ remotePath: String) throws {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.ftpError("FTP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = remotePath.withCString { nexusts_ftp_mkdir(h, $0, &errPtr) }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.ftpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.ftpError("MKD failed for \(remotePath)") }
    }

    public func removeEntry(_ remotePath: String, isDirectory: Bool = false) throws {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.ftpError("FTP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = remotePath.withCString { nexusts_ftp_remove(h, $0, &errPtr) }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.ftpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.ftpError("DELE/RMD failed for \(remotePath)") }
    }

    public func renameEntry(from oldPath: String, to newPath: String) throws {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.ftpError("FTP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = oldPath.withCString { oPtr in
            newPath.withCString { nPtr in
                nexusts_ftp_rename(h, oPtr, nPtr, &errPtr)
            }
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.ftpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.ftpError("RNFR/RNTO failed") }
    }

    public func chmod(_ remotePath: String, modeOctal: UInt32) throws {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.ftpError("FTP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = remotePath.withCString { nexusts_ftp_chmod(h, $0, modeOctal, &errPtr) }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.ftpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.ftpError("SITE CHMOD failed for \(remotePath)") }
    }

    public func downloadBytes(_ remotePath: String) throws -> Data {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.ftpError("FTP session closed") }
        let buf = remotePath.withCString { nexusts_ftp_download(h, $0) }
        defer { nexusts_ffi_free_buffer(buf) }
        if let err = buf.error_msg {
            throw NativeBridgeError.ftpError(String(cString: err))
        }
        guard let ptr = buf.data else { return Data() }
        return Data(bytes: ptr, count: buf.len)
    }

    public func uploadBytes(_ data: Data, to remotePath: String) throws {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { throw NativeBridgeError.ftpError("FTP session closed") }
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let ok = remotePath.withCString { pPtr in
            data.withUnsafeBytes { raw in
                nexusts_ftp_upload(
                    h,
                    pPtr,
                    raw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                    data.count,
                    &errPtr
                )
            }
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.ftpError(String(cString: errPtr))
        }
        if !ok { throw NativeBridgeError.ftpError("STOR failed for \(remotePath)") }
    }

    public func disconnect() {
        lock.lock()
        let h = handle
        handle = 0
        lock.unlock()
        if h > 0 {
            nexusts_ftp_disconnect(h)
        }
    }
}

// MARK: - 5. RFB / VNC / Apple Screen Sharing Framebuffer & Live Session Engine

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

    public func copyBgraPixels() -> Data {
        let count = width * height * 4
        var buffer = Data(count: count)
        buffer.withUnsafeMutableBytes { raw in
            if let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) {
                _ = nexusts_ffi_vnc_copy_bgra(handle, base, count)
            }
        }
        return buffer
    }
}

public final class NativeLiveVncSession: @unchecked Sendable {
    public private(set) var handle: Int64
    private let lock = NSLock()
    private var persistentContext: CGContext?
    private var persistentWidth: Int = 0
    private var persistentHeight: Int = 0
    private var lastRenderedSequence: UInt64 = 0
    private var lastRenderedImage: CGImage?

    private init(handle: Int64) {
        self.handle = handle
    }

    deinit {
        disconnect()
    }

    public static func connect(
        host: String,
        port: UInt16 = 5900,
        username: String = "",
        password: String = "",
        sharedSession: Bool = true,
        viewOnly: Bool = false
    ) throws -> NativeLiveVncSession {
        var errPtr: UnsafeMutablePointer<CChar>? = nil
        let vncId: Int64 = host.withCString { hPtr in
            username.withCString { uPtr in
                password.withCString { pPtr in
                    nexusts_vnc_session_connect(
                        hPtr,
                        port == 0 ? 5900 : port,
                        uPtr,
                        pPtr,
                        sharedSession,
                        viewOnly,
                        &errPtr
                    )
                }
            }
        }
        if let errPtr {
            defer { nexusts_ffi_free_cstr(errPtr) }
            throw NativeBridgeError.vncSessionError(String(cString: errPtr))
        }
        guard vncId > 0 else {
            throw NativeBridgeError.vncSessionError("VNC session failed to initialize")
        }
        return NativeLiveVncSession(handle: vncId)
    }

    public var isConnected: Bool {
        lock.lock()
        let h = handle
        lock.unlock()
        return h > 0 && nexusts_vnc_session_is_connected(h)
    }

    public func lastErrorMessage() -> String {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0, let ptr = nexusts_vnc_session_last_error(h) else { return "" }
        defer { nexusts_ffi_free_cstr(ptr) }
        return String(cString: ptr)
    }

    public func desktopName() -> String {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0, let ptr = nexusts_vnc_session_desktop_name(h) else { return "" }
        defer { nexusts_ffi_free_cstr(ptr) }
        return String(cString: ptr)
    }

    public var frameSequence: UInt64 {
        lock.lock()
        let h = handle
        lock.unlock()
        return h > 0 ? nexusts_vnc_session_frame_sequence(h) : 0
    }

    public func dimensions() -> (width: Int, height: Int)? {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { return nil }
        var w: UInt32 = 0
        var ht: UInt32 = 0
        guard nexusts_vnc_session_get_dimensions(h, &w, &ht), w > 0, ht > 0 else { return nil }
        return (Int(w), Int(ht))
    }

    public func copyFrameBgra() -> (bgra: Data, width: Int, height: Int)? {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { return nil }
        var w: UInt32 = 0
        var ht: UInt32 = 0
        guard nexusts_vnc_session_get_dimensions(h, &w, &ht), w > 0, ht > 0 else { return nil }
        let byteCount = Int(w) * Int(ht) * 4
        var data = Data(count: byteCount)
        let copied = data.withUnsafeMutableBytes { raw -> Int in
            guard let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return 0 }
            return nexusts_vnc_session_copy_frame_bgra(h, base, byteCount)
        }
        guard copied > 0 else { return nil }
        return (data, Int(w), Int(ht))
    }

    public func copyFrameRgba() -> (rgba: Data, width: Int, height: Int)? {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { return nil }
        var w: UInt32 = 0
        var ht: UInt32 = 0
        guard nexusts_vnc_session_get_dimensions(h, &w, &ht), w > 0, ht > 0 else { return nil }
        let byteCount = Int(w) * Int(ht) * 4
        var data = Data(count: byteCount)
        let copied = data.withUnsafeMutableBytes { raw -> Int in
            guard let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return 0 }
            return nexusts_vnc_session_copy_frame_rgba(h, base, byteCount)
        }
        guard copied > 0 else { return nil }
        return (data, Int(w), Int(ht))
    }

    public func copyFrameIntoPersistentBuffer(
        forceFull: Bool = false
    ) -> (image: CGImage, width: Int, height: Int, sequence: UInt64)? {
        lock.lock()
        defer { lock.unlock() }
        guard handle > 0 else { return nil }

        let seq = nexusts_vnc_session_frame_sequence(handle)
        if !forceFull && seq == lastRenderedSequence {
            return nil
        }
        if forceFull && seq == lastRenderedSequence,
           let cached = lastRenderedImage,
           persistentWidth > 0,
           persistentHeight > 0 {
            return (cached, persistentWidth, persistentHeight, seq)
        }

        var w: UInt32 = 0
        var ht: UInt32 = 0
        guard nexusts_vnc_session_get_dimensions(handle, &w, &ht), w > 0, ht > 0 else { return nil }
        let width = Int(w)
        let height = Int(ht)

        if persistentContext == nil || persistentWidth != width || persistentHeight != height {
            persistentContext = CGContext(
                data: nil,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: width * 4,
                space: NativeRdpSession.deviceRgbColorSpace,
                bitmapInfo: NativeRdpSession.bgrxBitmapInfo
            )
            persistentWidth = width
            persistentHeight = height
        }

        guard let ctx = persistentContext,
              let dstBase = ctx.data?.assumingMemoryBound(to: UInt8.self) else {
            return nil
        }

        let copied = nexusts_vnc_session_copy_frame_bgra(handle, dstBase, width * height * 4)
        guard copied > 0, let image = ctx.makeImage() else { return nil }
        lastRenderedSequence = seq
        lastRenderedImage = image
        return (image, width, height, seq)
    }

    @discardableResult
    public func sendPointer(buttonMask: UInt8, x: Int, y: Int) -> Bool {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { return false }
        return nexusts_vnc_session_send_pointer(
            h,
            buttonMask,
            UInt16(clamping: max(0, x)),
            UInt16(clamping: max(0, y))
        )
    }

    @discardableResult
    public func sendKey(down: Bool, keysym: UInt32) -> Bool {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { return false }
        return nexusts_vnc_session_send_key(h, down, keysym)
    }

    @discardableResult
    public func sendClipboard(_ text: String) -> Bool {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0 else { return false }
        return text.withCString { nexusts_vnc_session_send_clipboard(h, $0) }
    }

    public func pullClipboard() -> String? {
        lock.lock()
        let h = handle
        lock.unlock()
        guard h > 0, let ptr = nexusts_vnc_session_pull_clipboard(h) else { return nil }
        defer { nexusts_ffi_free_cstr(ptr) }
        return String(cString: ptr)
    }

    public func disconnect() {
        lock.lock()
        let h = handle
        handle = 0
        persistentContext = nil
        lastRenderedImage = nil
        persistentWidth = 0
        persistentHeight = 0
        lock.unlock()
        if h > 0 {
            nexusts_vnc_session_disconnect(h)
        }
    }
}

// MARK: - 6. FreeRDP 3.x Native macOS Session Bridge & PC XT Set-1 Input Mapper

public final class NativeRdpSession: @unchecked Sendable {
    public static let deviceRgbColorSpace = CGColorSpaceCreateDeviceRGB()
    public static let bgrxBitmapInfo: UInt32 =
        CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue

    private var handle: Int64 = 0
    private let stateLock = NSLock()
    private var connectingFlag: Bool = false

    private let frameLock = NSLock()
    private var persistentContext: CGContext?
    private var persistentWidth: Int = 0
    private var persistentHeight: Int = 0
    private var lastRenderedSequence: UInt64 = 0
    private var lastRenderedImage: CGImage?

    public init(
        host: String,
        username: String,
        password: String,
        domain: String,
        settings: RdpProtocolSettings,
        initialWidth: Int = 1920,
        initialHeight: Int = 1080
    ) {
        let driveName = settings.macSharedDriveName
        let drivePath = settings.macSharedFolderPath ?? ""
        let runtimeHomeDir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent("NexusTS/freerdp_runtime", isDirectory: true)
            .path ?? ""
        self.handle = host.withCString { hostPtr in
            username.withCString { userPtr in
                password.withCString { passPtr in
                    domain.withCString { domPtr in
                        settings.rdGatewayHostname.withCString { gwPtr in
                            settings.remoteAppProgram.withCString { appPtr in
                                settings.remoteAppArguments.withCString { argsPtr in
                                    driveName.withCString { driveNamePtr in
                                        drivePath.withCString { drivePathPtr in
                                            runtimeHomeDir.withCString { homeDirPtr in
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
                                                cfg.redirect_drives = settings.redirectDrives || !drivePath.isEmpty
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
                                                cfg.shared_drive_name = driveNamePtr
                                                cfg.shared_drive_local_path = drivePathPtr
                                                cfg.app_home_dir = homeDirPtr
                                                return nexusts_rdp_create_session(&cfg)
                                            }
                                        }
                                    }
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

    public var isConnecting: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return connectingFlag
    }

    public func setConnecting(_ value: Bool) {
        stateLock.lock()
        connectingFlag = value
        stateLock.unlock()
    }

    public func connect() -> Bool {
        stateLock.lock()
        let h = handle
        connectingFlag = h > 0
        stateLock.unlock()
        guard h > 0 else { return false }
        defer { setConnecting(false) }
        return nexusts_rdp_connect(h)
    }

    public var isConnected: Bool {
        stateLock.lock()
        let h = handle
        stateLock.unlock()
        return h > 0 && nexusts_rdp_is_connected(h)
    }

    @discardableResult
    public func requestDynamicResize(width: Int, height: Int, scalePercent: Int = 100) -> Bool {
        stateLock.lock()
        let h = handle
        stateLock.unlock()
        guard h > 0 else { return false }
        let evenW = UInt32(max(320, width & ~1))
        let evenH = UInt32(max(240, height & ~1))
        return nexusts_rdp_request_resize(h, evenW, evenH, UInt32(max(100, scalePercent)))
    }

    public func sendScancode(_ scancode: UInt16, keyDown: Bool, extended: Bool = false) {
        stateLock.lock()
        let h = handle
        stateLock.unlock()
        guard h > 0 else { return }
        nexusts_rdp_send_key_scancode(h, scancode, keyDown, extended)
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
        stateLock.lock()
        let h = handle
        stateLock.unlock()
        guard h > 0 else { return }
        nexusts_rdp_send_pointer_event(
            h,
            flags,
            UInt16(clamping: max(0, x)),
            UInt16(clamping: max(0, y))
        )
    }

    public func pushLocalClipboard(_ text: String) {
        stateLock.lock()
        let h = handle
        stateLock.unlock()
        guard h > 0 else { return }
        text.withCString { nexusts_rdp_set_local_clipboard(h, $0) }
    }

    public func pullRemoteClipboard() -> String {
        stateLock.lock()
        let h = handle
        stateLock.unlock()
        guard h > 0, let ptr = nexusts_rdp_copy_remote_clipboard(h) else { return "" }
        defer { nexusts_rdp_free_cstr(ptr) }
        return String(cString: ptr)
    }

    public func lastErrorMessage() -> String {
        stateLock.lock()
        let h = handle
        stateLock.unlock()
        guard h > 0, let ptr = nexusts_rdp_copy_last_error(h) else { return "" }
        defer { nexusts_rdp_free_cstr(ptr) }
        return String(cString: ptr)
    }

    public var frameSequence: UInt64 {
        stateLock.lock()
        let h = handle
        stateLock.unlock()
        return h > 0 ? nexusts_rdp_frame_sequence(h) : 0
    }

    public var remoteClipboardSequence: UInt64 {
        stateLock.lock()
        let h = handle
        stateLock.unlock()
        return h > 0 ? nexusts_rdp_remote_clipboard_sequence(h) : 0
    }

    /// Copies the latest native shadow surface (`BGRX32` layout on macOS `[B, G, R, X]`).
    public func copyLatestFrame() -> (rgba: Data, width: Int, height: Int)? {
        stateLock.lock()
        let h = handle
        stateLock.unlock()
        guard h > 0 else { return nil }
        var w: UInt32 = 0
        var ht: UInt32 = 0
        let needed = nexusts_rdp_copy_frame_rgba(h, nil, 0, &w, &ht)
        guard needed > 0, w > 0, ht > 0 else { return nil }
        var data = Data(count: needed)
        data.withUnsafeMutableBytes { raw in
            if let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) {
                _ = nexusts_rdp_copy_frame_rgba(h, base, needed, &w, &ht)
            }
        }
        return (data, Int(w), Int(ht))
    }

    public static func makeBgrxCGImage(bgrx: Data, width: Int, height: Int) -> CGImage? {
        guard width > 0, height > 0, bgrx.count >= width * height * 4 else { return nil }
        guard let provider = CGDataProvider(data: bgrx as CFData) else { return nil }
        return CGImage(
            width: width,
            height: height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: width * 4,
            space: deviceRgbColorSpace,
            bitmapInfo: CGBitmapInfo(rawValue: bgrxBitmapInfo),
            provider: provider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        )
    }

    /// Zero-swizzle incremental dirty-scanline frame presenter backed by a persistent VM-COW `CGContext`.
    /// Allocates backing memory ONLY when `(width, height)` changes and updates ONLY dirty scanlines in place.
    public func copyDirtyFrameIntoPersistentBuffer(
        forceFull: Bool = false
    ) -> (image: CGImage, width: Int, height: Int, sequence: UInt64)? {
        stateLock.lock()
        let h = handle
        stateLock.unlock()
        guard h > 0 else { return nil }

        frameLock.lock()
        defer { frameLock.unlock() }

        let seq = nexusts_rdp_frame_sequence(h)
        if seq == 0 { return nil }
        if !forceFull && seq == lastRenderedSequence {
            return nil
        }
        if forceFull && seq == lastRenderedSequence,
           let cached = lastRenderedImage,
           persistentWidth > 0,
           persistentHeight > 0 {
            return (cached, persistentWidth, persistentHeight, seq)
        }

        var w: UInt32 = 0
        var ht: UInt32 = 0
        var dx: UInt32 = 0
        var dy: UInt32 = 0
        var dw: UInt32 = 0
        var dh: UInt32 = 0

        let needed = nexusts_rdp_copy_dirty_frame(h, nil, 0, &w, &ht, &dx, &dy, &dw, &dh)
        guard needed > 0, w > 0, ht > 0 else { return nil }

        let targetW = Int(w)
        let targetH = Int(ht)
        let capacity = targetW * targetH * 4

        if persistentContext == nil || persistentWidth != targetW || persistentHeight != targetH {
            guard let ctx = CGContext(
                data: nil,
                width: targetW,
                height: targetH,
                bitsPerComponent: 8,
                bytesPerRow: targetW * 4,
                space: Self.deviceRgbColorSpace,
                bitmapInfo: Self.bgrxBitmapInfo
            ), let dstBase = ctx.data?.assumingMemoryBound(to: UInt8.self) else {
                return nil
            }

            _ = nexusts_rdp_copy_frame_rgba(h, dstBase, capacity, &w, &ht)
            _ = nexusts_rdp_copy_dirty_frame(h, dstBase, capacity, &w, &ht, &dx, &dy, &dw, &dh)
            persistentContext = ctx
            persistentWidth = Int(w)
            persistentHeight = Int(ht)

            guard let image = ctx.makeImage() else { return nil }
            lastRenderedSequence = seq
            lastRenderedImage = image
            return (image, persistentWidth, persistentHeight, seq)
        }

        guard let ctx = persistentContext,
              let dstBase = ctx.data?.assumingMemoryBound(to: UInt8.self) else {
            return nil
        }

        let copied = nexusts_rdp_copy_dirty_frame(h, dstBase, capacity, &w, &ht, &dx, &dy, &dw, &dh)
        if Int(w) != persistentWidth || Int(ht) != persistentHeight {
            persistentContext = nil
            persistentWidth = 0
            persistentHeight = 0
            lastRenderedImage = nil
            return nil
        }

        if copied > 0 && dw > 0 && dh > 0 {
            guard let image = ctx.makeImage() else { return nil }
            lastRenderedSequence = seq
            lastRenderedImage = image
            return (image, targetW, targetH, seq)
        }

        if forceFull {
            _ = nexusts_rdp_copy_frame_rgba(h, dstBase, capacity, &w, &ht)
            guard let image = ctx.makeImage() else { return nil }
            lastRenderedSequence = seq
            lastRenderedImage = image
            return (image, targetW, targetH, seq)
        }

        lastRenderedSequence = seq
        return nil
    }

    public func disconnect() {
        stateLock.lock()
        let h = handle
        connectingFlag = false
        stateLock.unlock()
        if h > 0 {
            nexusts_rdp_disconnect(h)
        }
    }

    public func destroy() {
        stateLock.lock()
        let h = handle
        handle = 0
        connectingFlag = false
        stateLock.unlock()

        frameLock.lock()
        persistentContext = nil
        lastRenderedImage = nil
        persistentWidth = 0
        persistentHeight = 0
        lastRenderedSequence = 0
        frameLock.unlock()

        if h > 0 {
            nexusts_rdp_destroy_session(h)
        }
    }
}

/// Maps macOS Carbon virtual keyCodes (`NSEvent.keyCode`), modifier flags, scroll wheels,
/// and aspect-fit viewport coordinates to PC XT Set-1 RDP wire inputs.
public enum MacRdpScancodeMapper {
    /// Maps a macOS Carbon virtual keyCode (`UInt16`) to `(scancode: UInt16, extended: Bool)`.
    public static func mapKeyCode(
        _ keyCode: UInt16,
        captureRemoteShortcuts: Bool = true
    ) -> (scancode: UInt16, extended: Bool)? {
        if let mod = mapModifierKeyCode(keyCode, captureRemoteShortcuts: captureRemoteShortcuts) {
            return (mod.scancode, mod.extended)
        }
        switch keyCode {
        // Letters (ANSI / ISO virtual keyCodes)
        case 0x00: return (0x1E, false) // A
        case 0x01: return (0x1F, false) // S
        case 0x02: return (0x20, false) // D
        case 0x03: return (0x21, false) // F
        case 0x04: return (0x23, false) // H
        case 0x05: return (0x22, false) // G
        case 0x06: return (0x2C, false) // Z
        case 0x07: return (0x2D, false) // X
        case 0x08: return (0x2E, false) // C
        case 0x09: return (0x2F, false) // V
        case 0x0B: return (0x30, false) // B
        case 0x0C: return (0x10, false) // Q
        case 0x0D: return (0x11, false) // W
        case 0x0E: return (0x12, false) // E
        case 0x0F: return (0x13, false) // R
        case 0x10: return (0x15, false) // Y
        case 0x11: return (0x14, false) // T
        case 0x1F: return (0x18, false) // O
        case 0x20: return (0x16, false) // U
        case 0x22: return (0x17, false) // I
        case 0x23: return (0x19, false) // P
        case 0x25: return (0x26, false) // L
        case 0x26: return (0x24, false) // J
        case 0x28: return (0x25, false) // K
        case 0x2D: return (0x31, false) // N
        case 0x2E: return (0x32, false) // M

        // Digits & Symbols
        case 0x12: return (0x02, false) // 1
        case 0x13: return (0x03, false) // 2
        case 0x14: return (0x04, false) // 3
        case 0x15: return (0x05, false) // 4
        case 0x16: return (0x07, false) // 6
        case 0x17: return (0x06, false) // 5
        case 0x18: return (0x0D, false) // =
        case 0x19: return (0x0A, false) // 9
        case 0x1A: return (0x08, false) // 7
        case 0x1B: return (0x0C, false) // -
        case 0x1C: return (0x09, false) // 8
        case 0x1D: return (0x0B, false) // 0
        case 0x1E: return (0x1B, false) // ]
        case 0x21: return (0x1A, false) // [
        case 0x27: return (0x28, false) // '
        case 0x29: return (0x27, false) // ;
        case 0x2A: return (0x2B, false) // \
        case 0x2B: return (0x33, false) // ,
        case 0x2C: return (0x35, false) // /
        case 0x2F: return (0x34, false) // .
        case 0x32: return (0x29, false) // `
        case 0x0A: return (0x56, false) // ISO §

        // Editing, Control & Navigation
        case 0x24: return (0x1C, false) // Return
        case 0x30: return (0x0F, false) // Tab
        case 0x31: return (0x39, false) // Space
        case 0x33: return (0x0E, false) // Backspace
        case 0x35: return (0x01, false) // Escape
        case 0x75: return (0x53, true)  // Forward Delete
        case 0x72: return (0x52, true)  // Insert / Help
        case 0x73: return (0x47, true)  // Home
        case 0x74: return (0x49, true)  // Page Up
        case 0x77: return (0x4F, true)  // End
        case 0x79: return (0x51, true)  // Page Down
        case 0x7B: return (0x4B, true)  // Left Arrow
        case 0x7C: return (0x4D, true)  // Right Arrow
        case 0x7D: return (0x50, true)  // Down Arrow
        case 0x7E: return (0x48, true)  // Up Arrow

        // Function Keys F1..F12 (+ F13..F15)
        case 0x7A: return (0x3B, false) // F1
        case 0x78: return (0x3C, false) // F2
        case 0x63: return (0x3D, false) // F3
        case 0x76: return (0x3E, false) // F4
        case 0x60: return (0x3F, false) // F5
        case 0x61: return (0x40, false) // F6
        case 0x62: return (0x41, false) // F7
        case 0x64: return (0x42, false) // F8
        case 0x65: return (0x43, false) // F9
        case 0x6D: return (0x44, false) // F10
        case 0x67: return (0x57, false) // F11
        case 0x6F: return (0x58, false) // F12
        case 0x69: return (0x37, true)  // F13 / PrintScreen

        // Numeric Keypad
        case 0x4C: return (0x1C, true)  // Keypad Enter
        case 0x4B: return (0x35, true)  // Keypad /
        case 0x43: return (0x37, false) // Keypad *
        case 0x4E: return (0x4A, false) // Keypad -
        case 0x45: return (0x4E, false) // Keypad +
        case 0x41: return (0x53, false) // Keypad .
        case 0x47: return (0x45, false) // Keypad Clear / NumLock
        case 0x51: return (0x0D, false) // Keypad =
        case 0x52: return (0x52, false) // Keypad 0
        case 0x53: return (0x4F, false) // Keypad 1
        case 0x54: return (0x50, false) // Keypad 2
        case 0x55: return (0x51, false) // Keypad 3
        case 0x56: return (0x4B, false) // Keypad 4
        case 0x57: return (0x4C, false) // Keypad 5
        case 0x58: return (0x4D, false) // Keypad 6
        case 0x59: return (0x47, false) // Keypad 7
        case 0x5B: return (0x48, false) // Keypad 8
        case 0x5C: return (0x49, false) // Keypad 9

        default:
            return nil
        }
    }

    /// Maps macOS modifier virtual keyCodes (`flagsChanged`) to PC XT Set-1 scancodes.
    public static func mapModifierKeyCode(
        _ keyCode: UInt16,
        captureRemoteShortcuts: Bool = true
    ) -> (scancode: UInt16, extended: Bool, isCapsLock: Bool)? {
        switch keyCode {
        case 0x38: return (0x2A, false, false) // Left Shift
        case 0x3C: return (0x36, false, false) // Right Shift
        case 0x3B: return (0x1D, false, false) // Left Control
        case 0x3E: return (0x1D, true, false)  // Right Control
        case 0x3A: return (0x38, false, false) // Left Option / Alt
        case 0x3D: return (0x38, true, false)  // Right Option / AltGr
        case 0x37: return captureRemoteShortcuts ? (0x5B, true, false) : nil // Left Command -> Left Win
        case 0x36: return captureRemoteShortcuts ? (0x5C, true, false) : nil // Right Command -> Right Win
        case 0x39: return (0x3A, false, true)  // Caps Lock (tap)
        default: return nil
        }
    }

    /// Encodes an RDP pointer wheel step (`PTR_FLAGS_WHEEL` `0x0200` or `PTR_FLAGS_HWHEEL` `0x0400`)
    /// using 9-bit two's-complement rotation units (`0x0100 | ((-mag) & 0xFF)` for negative steps).
    public static func encodeWheelFlags(delta: Int, horizontal: Bool = false) -> UInt16? {
        guard delta != 0 else { return nil }
        let baseFlag: UInt16 = horizontal ? 0x0400 : 0x0200
        if delta > 0 {
            let mag = UInt16(min(255, delta))
            return baseFlag | mag
        } else {
            let mag = min(256, -delta)
            let negBits = UInt16(bitPattern: Int16(-mag)) & 0x00FF
            return baseFlag | 0x0100 | negBits
        }
    }

    /// Maps a local view point into remote desktop coordinates, accounting for letterbox/pillarbox
    /// aspect-fit (`scaleToFit == true`) or aspect-fill (`scaleToFit == false`) sub-rects.
    public static func mapViewportPoint(
        localX: CGFloat,
        localY: CGFloat,
        viewWidth: CGFloat,
        viewHeight: CGFloat,
        remoteWidth: Int,
        remoteHeight: Int,
        scaleToFit: Bool = true,
        originBottomLeft: Bool = true
    ) -> (x: Int, y: Int) {
        let vw = max(1.0, viewWidth)
        let vh = max(1.0, viewHeight)
        let rw = CGFloat(max(1, remoteWidth))
        let rh = CGFloat(max(1, remoteHeight))
        let topLeftY = originBottomLeft ? (vh - localY) : localY

        let scale = scaleToFit ? min(vw / rw, vh / rh) : max(vw / rw, vh / rh)
        let contentW = max(1.0, rw * scale)
        let contentH = max(1.0, rh * scale)
        let offsetX = (vw - contentW) * 0.5
        let offsetY = (vh - contentH) * 0.5

        let normX = (localX - offsetX) / contentW
        let normY = (topLeftY - offsetY) / contentH

        let maxX = max(0, remoteWidth - 1)
        let maxY = max(0, remoteHeight - 1)
        let px = min(max(0, Int((normX * rw).rounded(.down))), maxX)
        let py = min(max(0, Int((normY * rh).rounded(.down))), maxY)
        return (px, py)
    }
}

// MARK: - 7. Network Diagnostics Bridge (WoL, Port Probe, Scanner, Ping, Traceroute, Banner, FTP MLSD)

public struct PortProbeHit: Codable, Equatable, Identifiable, Sendable {
    public var id: UInt16 { port }
    public let port: UInt16
    public let open: Bool
    public let connectLatencyMs: UInt64
    public let guessedService: String
    public let serviceBanner: String?

    public init(
        port: UInt16,
        open: Bool,
        connectLatencyMs: UInt64,
        guessedService: String,
        serviceBanner: String? = nil
    ) {
        self.port = port
        self.open = open
        self.connectLatencyMs = connectLatencyMs
        self.guessedService = guessedService
        self.serviceBanner = serviceBanner
    }

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

    public static func scanPorts(
        host: String,
        startPort: UInt16,
        endPort: UInt16,
        timeoutMs: UInt64 = 250,
        concurrency: UInt32 = 128
    ) -> [PortProbeHit] {
        let lo = min(startPort, endPort)
        let hi = max(startPort, endPort)
        let jsonPtr = host.withCString { hPtr in
            nexusts_net_scan_ports_json(hPtr, lo, hi, timeoutMs, max(1, concurrency))
        }
        guard let jsonPtr else { return [] }
        defer { nexusts_ffi_free_cstr(jsonPtr) }
        return (try? JSONDecoder().decode([PortProbeHit].self, from: Data(String(cString: jsonPtr).utf8))) ?? []
    }

    public static func tcpPingMs(host: String, port: UInt16, timeoutMs: UInt64 = 1000) -> Int64 {
        host.withCString { hPtr in
            nexusts_net_tcp_ping_ms(hPtr, port, timeoutMs)
        }
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
