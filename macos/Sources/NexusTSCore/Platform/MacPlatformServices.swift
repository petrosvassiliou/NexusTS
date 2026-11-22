import CoreSpotlight
import CryptoKit
import Darwin
import Foundation
import IOKit
import IOKit.serial
import LocalAuthentication
import Security
import UniformTypeIdentifiers

// MARK: - 1. Touch ID / Apple Watch & macOS Keychain Vault Passphrase Manager

public enum BiometricUnlockError: LocalizedError, Sendable {
    case biometricsUnavailable(String)
    case authenticationCancelled(String)
    case keychainFailure(OSStatus)
    case localKeystoreFailure(String)
    case noStoredPassphrase

    public var errorDescription: String? {
        switch self {
        case .biometricsUnavailable(let msg): return msg
        case .authenticationCancelled(let msg): return msg
        case .keychainFailure(let status): return "macOS Keychain returned OSStatus \(status)."
        case .localKeystoreFailure(let msg): return "Failed to seal Touch ID unlock key: \(msg)"
        case .noStoredPassphrase: return "No vault passphrase saved for Touch ID unlock yet."
        }
    }
}

/// Hardware-bound CryptoKit `AES.GCM` + `IOPlatformUUID` + `LAContext` Touch ID / Apple Watch Enclave Store.
/// Avoids legacy `SecKeychain` / `SecItemAdd` ACL popups ("Keychain Not Found" / `OSStatus -60006`) on
/// ad-hoc signed macOS builds while enforcing real Secure Enclave Touch ID / Apple Watch verification.
public final class MacKeychainAndBiometrics: @unchecked Sendable {
    public static let shared = MacKeychainAndBiometrics()
    private let serviceName = "com.nexusts.app.macos.vault"
    private let lock = NSLock()
    private let storageDirectoryURL: URL

    private static let vaultNamespace = "vault"
    private static let tokenNamespace = "tokens"

    public init(storageDirectoryURL: URL? = nil) {
        if let custom = storageDirectoryURL {
            self.storageDirectoryURL = custom
        } else {
            let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support", isDirectory: true)
            self.storageDirectoryURL = appSupport
                .appendingPathComponent("NexusTS", isDirectory: true)
                .appendingPathComponent("SecureEnclaveStore", isDirectory: true)
        }
    }

    private var machineKeyURL: URL {
        storageDirectoryURL.appendingPathComponent(".machine-key.bin")
    }

    private var keystoreFileURL: URL {
        storageDirectoryURL.appendingPathComponent("keystore-v1.json")
    }

    private static func readHardwarePlatformUUID() -> String {
        let service = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("IOPlatformExpertDevice"))
        guard service != 0 else { return "nexusts-macos-local-hardware" }
        defer { IOObjectRelease(service) }
        if let prop = IORegistryEntryCreateCFProperty(
            service,
            "IOPlatformUUID" as CFString,
            kCFAllocatorDefault,
            0
        )?.takeRetainedValue() as? String, !prop.isEmpty {
            return prop
        }
        return "nexusts-macos-local-hardware"
    }

    private func ensureDirectoryAndMachineKeyLocked() throws -> Data {
        let fm = FileManager.default
        if !fm.fileExists(atPath: storageDirectoryURL.path) {
            try fm.createDirectory(
                at: storageDirectoryURL,
                withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700]
            )
            var dirRes = URLResourceValues()
            dirRes.isExcludedFromBackup = true
            var mutableDir = storageDirectoryURL
            try? mutableDir.setResourceValues(dirRes)
        }

        if let existing = try? Data(contentsOf: machineKeyURL), existing.count == 32 {
            return existing
        }

        let randomKey = SymmetricKey(size: .bits256)
        let keyBytes = randomKey.withUnsafeBytes { Data($0) }
        try writeRestrictedFileLocked(keyBytes, to: machineKeyURL)
        return keyBytes
    }

    private func writeRestrictedFileLocked(_ data: Data, to targetURL: URL) throws {
        let tmpURL = targetURL.deletingLastPathComponent().appendingPathComponent(".\(UUID().uuidString).tmp")
        try data.write(to: tmpURL, options: .atomic)
        _ = Darwin.chmod(tmpURL.path, mode_t(0o600))
        let fm = FileManager.default
        if fm.fileExists(atPath: targetURL.path) {
            _ = try fm.replaceItemAt(targetURL, withItemAt: tmpURL)
        } else {
            try fm.moveItem(at: tmpURL, to: targetURL)
        }
        _ = Darwin.chmod(targetURL.path, mode_t(0o600))
        var res = URLResourceValues()
        res.isExcludedFromBackup = true
        var mutableURL = targetURL
        try? mutableURL.setResourceValues(res)
    }

    private func loadStoreMapLocked() -> [String: String] {
        guard let raw = try? Data(contentsOf: keystoreFileURL),
              let decoded = try? JSONDecoder().decode([String: String].self, from: raw) else {
            return [:]
        }
        return decoded
    }

    private func saveStoreMapLocked(_ map: [String: String]) throws {
        let encoded = try JSONEncoder().encode(map)
        try writeRestrictedFileLocked(encoded, to: keystoreFileURL)
    }

    private func deriveEntryKeyLocked(namespace: String, account: String) throws -> SymmetricKey {
        let machineKey = try ensureDirectoryAndMachineKeyLocked()
        let hwUUID = Self.readHardwarePlatformUUID()
        var ikmBytes = Data()
        ikmBytes.append(machineKey)
        ikmBytes.append(Data(hwUUID.utf8))
        ikmBytes.append(Data(NSUserName().utf8))
        let ikm = SymmetricKey(data: ikmBytes)
        let salt = Data(SHA256.hash(data: Data("\(serviceName):\(namespace)".utf8)))
        let info = Data("nexusts-macos-biometric-enclave-v1:\(namespace):\(account)".utf8)
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: ikm,
            salt: salt,
            info: info,
            outputByteCount: 32
        )
    }

    public func sealSecret(_ secret: String, namespace: String, account: String) throws {
        lock.lock()
        defer { lock.unlock() }
        do {
            let key = try deriveEntryKeyLocked(namespace: namespace, account: account)
            let entryId = "\(namespace):\(account)"
            let aad = Data(entryId.utf8)
            let sealed = try AES.GCM.seal(Data(secret.utf8), using: key, authenticating: aad)
            guard let combined = sealed.combined else {
                throw BiometricUnlockError.localKeystoreFailure("Missing AES-GCM combined ciphertext")
            }
            var map = loadStoreMapLocked()
            map[entryId] = combined.base64EncodedString()
            try saveStoreMapLocked(map)
        } catch let bioErr as BiometricUnlockError {
            throw bioErr
        } catch {
            throw BiometricUnlockError.localKeystoreFailure(error.localizedDescription)
        }
    }

    public func unsealSecret(namespace: String, account: String) throws -> String? {
        lock.lock()
        defer { lock.unlock() }
        let entryId = "\(namespace):\(account)"
        let map = loadStoreMapLocked()
        guard let b64 = map[entryId], let combined = Data(base64Encoded: b64) else {
            return nil
        }
        let key = try deriveEntryKeyLocked(namespace: namespace, account: account)
        let aad = Data(entryId.utf8)
        let box = try AES.GCM.SealedBox(combined: combined)
        let plain = try AES.GCM.open(box, using: key, authenticating: aad)
        return String(data: plain, encoding: .utf8)
    }

    private func removeEntriesLocked(where predicate: (String) -> Bool) {
        var map = loadStoreMapLocked()
        let before = map.count
        map = map.filter { !predicate($0.key) }
        if map.count != before {
            try? saveStoreMapLocked(map)
        }
    }

    public var canUseTouchIdOrCompanion: Bool {
        let context = LAContext()
        var error: NSError?
        if #available(macOS 15.0, *) {
            if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometricsOrCompanion, error: &error) {
                return true
            }
        }
        return context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
    }

    public func hasStoredPassphrase(forVaultId vaultId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let map = loadStoreMapLocked()
        return map["\(Self.vaultNamespace):\(vaultId)"] != nil
    }

    public func storePassphraseInKeychain(_ passphrase: String, forVaultId vaultId: String) throws {
        try sealSecret(passphrase, namespace: Self.vaultNamespace, account: vaultId)
    }

    public func deleteStoredPassphrase(forVaultId vaultId: String) {
        lock.lock()
        defer { lock.unlock() }
        let target = "\(Self.vaultNamespace):\(vaultId)"
        removeEntriesLocked { $0 == target }
    }

    public func deleteAllVaultPassphrases() {
        lock.lock()
        defer { lock.unlock() }
        let prefix = "\(Self.vaultNamespace):"
        removeEntriesLocked { $0.hasPrefix(prefix) }
    }

    public func unlockPassphraseWithBiometrics(
        forVaultId vaultId: String,
        reason: String = "Unlock your encrypted NexusTS connection vault"
    ) async throws -> String {
        guard hasStoredPassphrase(forVaultId: vaultId) else {
            throw BiometricUnlockError.noStoredPassphrase
        }

        let context = LAContext()
        context.localizedCancelTitle = "Enter Passphrase"
        var authError: NSError?

        let policy: LAPolicy = {
            if #available(macOS 15.0, *) {
                if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometricsOrCompanion, error: &authError) {
                    return .deviceOwnerAuthenticationWithBiometricsOrCompanion
                }
            }
            if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &authError) {
                return .deviceOwnerAuthenticationWithBiometrics
            }
            return .deviceOwnerAuthentication
        }()

        do {
            let granted = try await context.evaluatePolicy(policy, localizedReason: reason)
            guard granted else {
                throw BiometricUnlockError.authenticationCancelled("Authentication was not granted.")
            }
        } catch {
            throw BiometricUnlockError.authenticationCancelled(error.localizedDescription)
        }

        do {
            guard let secret = try unsealSecret(namespace: Self.vaultNamespace, account: vaultId) else {
                throw BiometricUnlockError.noStoredPassphrase
            }
            return secret
        } catch let bioErr as BiometricUnlockError {
            throw bioErr
        } catch {
            throw BiometricUnlockError.localKeystoreFailure(error.localizedDescription)
        }
    }

    // Generic OAuth / API Token Encrypted Store Helpers
    public func saveToken(_ token: String, key: String) {
        if token.isEmpty {
            lock.lock()
            let target = "\(Self.tokenNamespace):\(key)"
            removeEntriesLocked { $0 == target }
            lock.unlock()
            return
        }
        try? sealSecret(token, namespace: Self.tokenNamespace, account: key)
    }

    public func loadToken(key: String) -> String? {
        try? unsealSecret(namespace: Self.tokenNamespace, account: key)
    }
}

// MARK: - 2. CoreSpotlight Vault Connection Indexer

public enum MacSpotlightVaultIndexer {
    public static let domainIdentifier = "com.nexusts.app.connections"
    public static let itemPrefix = "nexusts-connection:"

    public static func reindexConnections(in document: NxtsVaultDocument, enabled: Bool = true) {
        guard CSSearchableIndex.isIndexingAvailable() else { return }
        let index = CSSearchableIndex.default()
        if !enabled {
            index.deleteSearchableItems(withDomainIdentifiers: [domainIdentifier]) { _ in }
            return
        }

        let foldersById = Dictionary(uniqueKeysWithValues: document.folders.map { ($0.id, $0) })
        let items: [CSSearchableItem] = document.connections
            .filter { !document.tombstonedObjectIds.contains($0.id) }
            .map { conn in
                let attrs = CSSearchableItemAttributeSet(contentType: .item)
                attrs.title = "\(conn.name) (\(conn.protocolType.shortBadge))"
                let chain = PropertyInheritanceResolver.folderAncestorChain(
                    leafFolderId: conn.parentFolderId,
                    foldersById: foldersById
                )
                let folderLabel = chain.isEmpty ? document.name : chain.map(\.name).joined(separator: " / ")
                attrs.contentDescription = "\(conn.protocolType.displayName) • \(conn.host) • \(folderLabel)"
                attrs.keywords = [conn.name, conn.host, conn.protocolType.rawValue, conn.protocolType.shortBadge] + conn.tags
                return CSSearchableItem(
                    uniqueIdentifier: "\(itemPrefix)\(conn.id)",
                    domainIdentifier: domainIdentifier,
                    attributeSet: attrs
                )
            }

        index.deleteSearchableItems(withDomainIdentifiers: [domainIdentifier]) { _ in
            if !items.isEmpty {
                index.indexSearchableItems(items) { _ in }
            }
        }
    }

    public static func connectionId(fromSpotlightIdentifier identifier: String) -> String? {
        guard identifier.hasPrefix(itemPrefix) else { return nil }
        return String(identifier.dropFirst(itemPrefix.count))
    }
}

// MARK: - 3. IOKit Hardware USB-Serial (/dev/cu.*) Discovery & POSIX Termios Session

public struct DiscoveredSerialPort: Identifiable, Equatable, Hashable, Sendable {
    public var id: String { calloutPath }
    public let calloutPath: String
    public let displayName: String
    public let driverKind: String
}

public final class MacSerialPortSession: @unchecked Sendable {
    private var fd: Int32 = -1
    private let lock = NSLock()

    public static let supportedBaudRates: [Int] = [
        300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600
    ]

    public static func discoverSerialPorts() -> [DiscoveredSerialPort] {
        var found: [DiscoveredSerialPort] = []
        var seen = Set<String>()

        if let matchingDict = IOServiceMatching(kIOSerialBSDServiceValue) as NSMutableDictionary? {
            matchingDict[kIOSerialBSDTypeKey] = kIOSerialBSDAllTypes
            var iterator: io_iterator_t = 0
            if IOServiceGetMatchingServices(kIOMainPortDefault, matchingDict, &iterator) == KERN_SUCCESS {
                defer { IOObjectRelease(iterator) }
                var service = IOIteratorNext(iterator)
                while service != 0 {
                    defer {
                        IOObjectRelease(service)
                        service = IOIteratorNext(iterator)
                    }
                    let calloutRef = IORegistryEntryCreateCFProperty(
                        service,
                        kIOCalloutDeviceKey as CFString,
                        kCFAllocatorDefault,
                        0
                    )?.takeRetainedValue() as? String

                    let ttyNameRef = IORegistryEntryCreateCFProperty(
                        service,
                        kIOTTYDeviceKey as CFString,
                        kCFAllocatorDefault,
                        0
                    )?.takeRetainedValue() as? String

                    if let path = calloutRef, seen.insert(path).inserted {
                        let lower = path.lowercased()
                        let kind: String = {
                            if lower.contains("usbserial") { return "USB-UART Bridge" }
                            if lower.contains("usbmodem") { return "USB CDC-ACM" }
                            if lower.contains("bluetooth") { return "Bluetooth SPP" }
                            return "POSIX Serial"
                        }()
                        found.append(DiscoveredSerialPort(
                            calloutPath: path,
                            displayName: ttyNameRef ?? (path as NSString).lastPathComponent,
                            driverKind: kind
                        ))
                    }
                }
            }
        }
        return found.sorted { $0.calloutPath < $1.calloutPath }
    }

    public init() {}

    deinit {
        close()
    }

    public var isOpen: Bool {
        lock.lock()
        defer { lock.unlock() }
        return fd >= 0
    }

    public func open(settings: SerialUartProtocolSettings) throws {
        close()
        let rawFd = Darwin.open(settings.devicePathHint, O_RDWR | O_NOCTTY | O_NONBLOCK)
        guard rawFd >= 0 else {
            let msg = String(cString: strerror(errno))
            throw NativeBridgeError.networkUtilityError("Cannot open \(settings.devicePathHint): \(msg)")
        }

        _ = ioctl(rawFd, TIOCEXCL)
        _ = fcntl(rawFd, F_SETFL, O_NONBLOCK)

        var options = termios()
        tcgetattr(rawFd, &options)
        cfmakeraw(&options)

        options.c_cflag |= tcflag_t(CLOCAL | CREAD)
        options.c_cflag &= ~tcflag_t(CSIZE)
        switch settings.dataBits {
        case 5: options.c_cflag |= tcflag_t(CS5)
        case 6: options.c_cflag |= tcflag_t(CS6)
        case 7: options.c_cflag |= tcflag_t(CS7)
        default: options.c_cflag |= tcflag_t(CS8)
        }

        if settings.stopBits == 2 {
            options.c_cflag |= tcflag_t(CSTOPB)
        } else {
            options.c_cflag &= ~tcflag_t(CSTOPB)
        }

        switch settings.parity.uppercased() {
        case "EVEN":
            options.c_cflag |= tcflag_t(PARENB)
            options.c_cflag &= ~tcflag_t(PARODD)
        case "ODD":
            options.c_cflag |= tcflag_t(PARENB | PARODD)
        default:
            options.c_cflag &= ~tcflag_t(PARENB)
        }

        options.c_cflag &= ~tcflag_t(CRTSCTS)
        options.c_iflag &= ~tcflag_t(IXON | IXOFF | IXANY)
        if settings.flowControl.uppercased() == "RTS_CTS" {
            options.c_cflag |= tcflag_t(CRTSCTS)
        } else if settings.flowControl.uppercased() == "XON_XOFF" {
            options.c_iflag |= tcflag_t(IXON | IXOFF)
        }

        let requestedBaud = max(300, settings.baudRate)
        if requestedBaud > 230_400 {
            cfsetspeed(&options, speed_t(B115200))
        } else {
            cfsetspeed(&options, speed_t(requestedBaud))
        }
        tcsetattr(rawFd, TCSANOW, &options)

        // macOS IOSSIOSPEED ioctl allows non-standard high baud rates such as 460800 / 921600.
        // Try both 32-bit speed ioctl (0x80045402) and 64-bit speed_t ioctl (0x80085402).
        var customSpeed = speed_t(requestedBaud)
        if ioctl(rawFd, UInt(0x8004_5402), &customSpeed) < 0 {
            _ = ioctl(rawFd, UInt(0x8008_5402), &customSpeed)
        }

        lock.lock()
        self.fd = rawFd
        lock.unlock()

        setControlLines(dtr: settings.dtrEnabled, rts: settings.rtsEnabled)
    }

    public func setControlLines(dtr: Bool, rts: Bool) {
        lock.lock()
        let activeFd = fd
        lock.unlock()
        guard activeFd >= 0 else { return }

        _ = ioctl(activeFd, dtr ? TIOCSDTR : TIOCCDTR)
        var rtsFlag: Int32 = TIOCM_RTS
        _ = ioctl(activeFd, rts ? TIOCMBIS : TIOCMBIC, &rtsFlag)
    }

    public func sendBreak() {
        lock.lock()
        let activeFd = fd
        lock.unlock()
        guard activeFd >= 0 else { return }
        _ = tcsendbreak(activeFd, 0)
    }

    public func write(_ data: Data) {
        lock.lock()
        let activeFd = fd
        lock.unlock()
        guard activeFd >= 0, !data.isEmpty else { return }
        data.withUnsafeBytes { raw in
            if let ptr = raw.baseAddress {
                _ = Darwin.write(activeFd, ptr, data.count)
            }
        }
    }

    public func drainAvailableBytes() -> Data {
        lock.lock()
        let activeFd = fd
        lock.unlock()
        guard activeFd >= 0 else { return Data() }
        var out = Data()
        var buf = [UInt8](repeating: 0, count: 4096)
        while true {
            let n = Darwin.read(activeFd, &buf, buf.count)
            if n > 0 {
                out.append(contentsOf: buf.prefix(n))
            } else {
                break
            }
        }
        return out
    }

    public func close() {
        lock.lock()
        let old = fd
        fd = -1
        lock.unlock()
        if old >= 0 {
            Darwin.close(old)
        }
    }
}

// MARK: - 4. Native macOS Local /bin/zsh PTY Shell Session (Darwin forkpty)

public final class MacLocalPtySession: @unchecked Sendable {
    private var masterFd: Int32 = -1
    private var childPid: pid_t = -1
    private let lock = NSLock()

    public init() {}

    deinit {
        terminate()
    }

    public var processIdentifier: pid_t {
        lock.lock()
        defer { lock.unlock() }
        return childPid
    }

    public func launchLoginShell(cols: Int = 110, rows: Int = 32) throws {
        terminate()

        var win = winsize(
            ws_row: UInt16(max(4, rows)),
            ws_col: UInt16(max(10, cols)),
            ws_xpixel: 0,
            ws_ypixel: 0
        )
        var amaster: Int32 = -1
        let shellPath = ProcessInfo.processInfo.environment["SHELL"] ?? "/bin/zsh"

        // Prepare C strings BEFORE forkpty so child process executes purely async-signal-safe C calls
        let cShell = strdup(shellPath)
        let cLoginArg = strdup("-l")
        var envPairs: [UnsafeMutablePointer<CChar>?] = []
        var mergedEnv = ProcessInfo.processInfo.environment
        mergedEnv["TERM"] = "xterm-256color"
        mergedEnv["COLORTERM"] = "truecolor"
        mergedEnv["TERM_PROGRAM"] = "NexusTS"
        if mergedEnv["LANG"] == nil { mergedEnv["LANG"] = "en_US.UTF-8" }
        for (k, v) in mergedEnv {
            envPairs.append(strdup("\(k)=\(v)"))
        }
        envPairs.append(nil)

        let pid = forkpty(&amaster, nil, nil, &win)
        if pid < 0 {
            free(cShell)
            free(cLoginArg)
            for p in envPairs where p != nil { free(p) }
            throw NativeBridgeError.sshTransportError("forkpty failed: \(String(cString: strerror(errno)))")
        }

        if pid == 0 {
            var argv: [UnsafeMutablePointer<CChar>?] = [cShell, cLoginArg, nil]
            execve(cShell, &argv, &envPairs)
            _exit(127)
        }

        free(cShell)
        free(cLoginArg)
        for p in envPairs where p != nil { free(p) }

        let flags = fcntl(amaster, F_GETFL)
        _ = fcntl(amaster, F_SETFL, flags | O_NONBLOCK)

        lock.lock()
        self.masterFd = amaster
        self.childPid = pid
        lock.unlock()
    }

    public func resize(cols: Int, rows: Int) {
        lock.lock()
        let fd = masterFd
        lock.unlock()
        guard fd >= 0 else { return }
        var win = winsize(
            ws_row: UInt16(max(4, rows)),
            ws_col: UInt16(max(10, cols)),
            ws_xpixel: 0,
            ws_ypixel: 0
        )
        _ = ioctl(fd, TIOCSWINSZ, &win)
    }

    public func sendInput(_ data: Data) {
        lock.lock()
        let fd = masterFd
        lock.unlock()
        guard fd >= 0, !data.isEmpty else { return }
        data.withUnsafeBytes { raw in
            if let ptr = raw.baseAddress {
                _ = Darwin.write(fd, ptr, data.count)
            }
        }
    }

    public func drainOutput() -> (bytes: Data, exited: Bool) {
        lock.lock()
        let fd = masterFd
        let pid = childPid
        lock.unlock()
        guard fd >= 0 else { return (Data(), true) }

        var out = Data()
        var buf = [UInt8](repeating: 0, count: 8192)
        while true {
            let n = Darwin.read(fd, &buf, buf.count)
            if n > 0 {
                out.append(contentsOf: buf.prefix(n))
            } else {
                break
            }
        }

        var status: Int32 = 0
        let w = pid > 0 ? waitpid(pid, &status, WNOHANG) : -1
        let exited = (w == pid)
        if exited {
            lock.lock()
            if childPid == pid {
                childPid = -1
            }
            lock.unlock()
        }
        return (out, exited)
    }

    public func terminate() {
        lock.lock()
        let fd = masterFd
        let pid = childPid
        masterFd = -1
        childPid = -1
        lock.unlock()

        if fd >= 0 { Darwin.close(fd) }
        if pid > 0 {
            _ = kill(-pid, SIGHUP)
            _ = kill(pid, SIGHUP)
            var st: Int32 = 0
            if waitpid(pid, &st, WNOHANG) == pid {
                return
            }
            DispatchQueue.global(qos: .utility).async {
                var status: Int32 = 0
                usleep(30_000)
                if waitpid(pid, &status, WNOHANG) == pid {
                    return
                }
                _ = kill(-pid, SIGTERM)
                _ = kill(pid, SIGTERM)
                for _ in 0..<10 {
                    usleep(20_000)
                    if waitpid(pid, &status, WNOHANG) == pid {
                        return
                    }
                }
                _ = kill(-pid, SIGKILL)
                _ = kill(pid, SIGKILL)
                _ = waitpid(pid, &status, 0)
            }
        }
    }
}

// MARK: - 5. GitHub Releases macOS Signed DMG Update Checker & SHA-256 Verifier

public enum MacUpdateInstallPhase: Equatable, Sendable {
    case idle
    case checking
    case downloading(progress: Double)
    case verifyingSha256
    case extractingAppBundle
    case applyingAndRelaunching
    case upToDate
    case failed(String)

    public var isBusy: Bool {
        switch self {
        case .checking, .downloading, .verifyingSha256, .extractingAppBundle, .applyingAndRelaunching:
            return true
        case .idle, .upToDate, .failed:
            return false
        }
    }

    public var statusText: String {
        switch self {
        case .idle:
            return ""
        case .checking:
            return "Checking GitHub Releases…"
        case .downloading(let progress):
            let pct = Int((min(1.0, max(0.0, progress)) * 100.0).rounded())
            return pct > 0 ? "Downloading update inside NexusTS (\(pct)%)…" : "Downloading update inside NexusTS…"
        case .verifyingSha256:
            return "Verifying cryptographic SHA-256 signature…"
        case .extractingAppBundle:
            return "Unpacking Universal2 NexusTS.app…"
        case .applyingAndRelaunching:
            return "Installing update in place & relaunching NexusTS…"
        case .upToDate:
            return "NexusTS is up to date (v\(MacGitHubReleaseUpdater.currentVersion))."
        case .failed(let message):
            return message
        }
    }
}

public enum MacAutoUpdateError: LocalizedError, Equatable, Sendable {
    case authenticationRequired(repoSlug: String)
    case missingZipBundleAsset(tagName: String)
    case downloadFailed(String)
    case sha256Mismatch(assetName: String)
    case extractionFailed(String)
    case invalidAppBundle(String)

    public var errorDescription: String? {
        switch self {
        case .authenticationRequired(let repoSlug):
            return "GitHub repository \(repoSlug) is private. Save a read-only GitHub Personal Access Token (Contents: Read-only) in Settings → Updates (or sign in with GitHub CLI `gh auth login`) to enable one-click in-app updates."
        case .missingZipBundleAsset(let tagName):
            return "Release \(tagName) does not include a macOS .zip app bundle yet."
        case .downloadFailed(let msg):
            return msg
        case .sha256Mismatch(let assetName):
            return "SHA-256 checksum mismatch for \(assetName). Update aborted for security."
        case .extractionFailed(let msg):
            return msg
        case .invalidAppBundle(let msg):
            return msg
        }
    }
}

public struct MacReleaseUpdateCandidate: Equatable, Sendable {
    public let tagName: String
    public let releaseTitle: String
    public let htmlReleaseUrl: URL
    public let zipAssetName: String?
    public let zipDownloadUrl: URL?
    public let zipApiAssetUrl: URL?
    public let zipSizeBytes: Int64
    public let zipExpectedSha256: String?
    public let dmgAssetName: String?
    public let dmgDownloadUrl: URL?
    public let sha256SumsUrl: URL?
    public let sha256SumsApiAssetUrl: URL?
    public let releaseNotes: String

    public var hasInAppZipUpdate: Bool {
        zipApiAssetUrl != nil || zipDownloadUrl != nil
    }

    public init(
        tagName: String,
        releaseTitle: String,
        htmlReleaseUrl: URL,
        zipAssetName: String? = nil,
        zipDownloadUrl: URL? = nil,
        zipApiAssetUrl: URL? = nil,
        zipSizeBytes: Int64 = 0,
        zipExpectedSha256: String? = nil,
        dmgAssetName: String? = nil,
        dmgDownloadUrl: URL? = nil,
        sha256SumsUrl: URL? = nil,
        sha256SumsApiAssetUrl: URL? = nil,
        releaseNotes: String = ""
    ) {
        self.tagName = tagName
        self.releaseTitle = releaseTitle
        self.htmlReleaseUrl = htmlReleaseUrl
        self.zipAssetName = zipAssetName
        self.zipDownloadUrl = zipDownloadUrl
        self.zipApiAssetUrl = zipApiAssetUrl
        self.zipSizeBytes = zipSizeBytes
        self.zipExpectedSha256 = zipExpectedSha256
        self.dmgAssetName = dmgAssetName
        self.dmgDownloadUrl = dmgDownloadUrl
        self.sha256SumsUrl = sha256SumsUrl
        self.sha256SumsApiAssetUrl = sha256SumsApiAssetUrl
        self.releaseNotes = releaseNotes
    }
}

public enum MacGitHubReleaseUpdater {
    public static let currentVersion = "0.9.3"
    public static let githubTokenKeychainKey = "github_release_pat"

    private static let legacyPreRebaseVersions: Set<String> = [
        "1.0.0",
        "1.1.0", "1.1.1", "1.1.2", "1.1.3", "1.1.4",
        "1.1.5", "1.1.6", "1.1.7", "1.1.8", "1.1.9",
        "1.2.0", "1.2.1-beta.1", "1.2.1-beta.2"
    ]

    public static func isLegacyPreRebaseVersion(_ rawVersion: String) -> Bool {
        let cleaned = rawVersion
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "vV"))
        return legacyPreRebaseVersions.contains(cleaned)
    }

    // MARK: - GitHub Token Discovery (Supports Both Public & Private Repositories)

    public static func saveKeychainGitHubToken(_ rawToken: String) {
        let trimmed = rawToken.trimmingCharacters(in: .whitespacesAndNewlines)
        MacKeychainAndBiometrics.shared.saveToken(trimmed, key: githubTokenKeychainKey)
    }

    public static func loadKeychainGitHubToken() -> String? {
        guard let saved = MacKeychainAndBiometrics.shared.loadToken(key: githubTokenKeychainKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
              !saved.isEmpty else {
            return nil
        }
        return saved
    }

    public static func resolveGitHubAuthToken(explicitToken: String? = nil) -> (token: String, source: String)? {
        if let exp = explicitToken?.trimmingCharacters(in: .whitespacesAndNewlines), !exp.isEmpty {
            return (exp, "Explicit Token")
        }
        if let saved = loadKeychainGitHubToken() {
            return (saved, "macOS Keychain")
        }
        let env = ProcessInfo.processInfo.environment
        for envKey in ["NEXUSTS_GITHUB_TOKEN", "GITHUB_TOKEN", "GH_TOKEN"] {
            if let val = env[envKey]?.trimmingCharacters(in: .whitespacesAndNewlines), !val.isEmpty {
                return (val, envKey)
            }
        }
        for ghPath in ["/opt/homebrew/bin/gh", "/usr/local/bin/gh", "/usr/bin/gh"] {
            if FileManager.default.isExecutableFile(atPath: ghPath),
               let out = runQuickHelperProcess(executable: ghPath, arguments: ["auth", "token"]),
               !out.isEmpty {
                return (out, "GitHub CLI (gh)")
            }
        }
        if FileManager.default.isExecutableFile(atPath: "/usr/bin/git"),
           let credOut = runQuickHelperProcess(
               executable: "/usr/bin/git",
               arguments: ["credential-osxkeychain", "get"],
               stdinString: "protocol=https\nhost=github.com\n\n"
           ) {
            for line in credOut.split(whereSeparator: \.isNewline) {
                if line.hasPrefix("password=") {
                    let pwd = String(line.dropFirst("password=".count)).trimmingCharacters(in: .whitespacesAndNewlines)
                    if !pwd.isEmpty {
                        return (pwd, "macOS Git Keychain")
                    }
                }
            }
        }
        return nil
    }

    private static func runQuickHelperProcess(
        executable: String,
        arguments: [String],
        stdinString: String? = nil
    ) -> String? {
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: executable)
        proc.arguments = arguments
        let outPipe = Pipe()
        proc.standardOutput = outPipe
        proc.standardError = FileHandle.nullDevice
        let inPipe: Pipe?
        if stdinString != nil {
            let p = Pipe()
            proc.standardInput = p
            inPipe = p
        } else {
            proc.standardInput = FileHandle.nullDevice
            inPipe = nil
        }
        do {
            try proc.run()
            if let stdinString, let inPipe {
                try? inPipe.fileHandleForWriting.write(contentsOf: Data(stdinString.utf8))
                try? inPipe.fileHandleForWriting.close()
            }
            let outData = outPipe.fileHandleForReading.readDataToEndOfFile()
            proc.waitUntilExit()
            guard proc.terminationStatus == 0 else { return nil }
            let str = String(data: outData, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
            return (str?.isEmpty == false) ? str : nil
        } catch {
            return nil
        }
    }

    // MARK: - Release Check

    public static func checkForUpdates(
        repoSlug: String = "petrosvassiliou/NexusTS",
        explicitToken: String? = nil
    ) async throws -> MacReleaseUpdateCandidate? {
        struct ReleasePayload: Decodable {
            struct Asset: Decodable {
                let name: String
                let url: String?
                let browser_download_url: String
                let size: Int64?
                let digest: String?
            }
            let tag_name: String
            let name: String?
            let html_url: String
            let body: String?
            let draft: Bool?
            let assets: [Asset]?
        }

        let isBetaChannel = currentVersion.contains("-")
        let endpoint = isBetaChannel
            ? "https://api.github.com/repos/\(repoSlug)/releases?per_page=15"
            : "https://api.github.com/repos/\(repoSlug)/releases/latest"
        guard let url = URL(string: endpoint) else {
            return nil
        }

        let resolvedAuth = resolveGitHubAuthToken(explicitToken: explicitToken)

        func performReleaseQuery(bearerToken: String?) async throws -> (Data, HTTPURLResponse) {
            var req = URLRequest(url: url)
            req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
            req.setValue("NexusTS-macOS/\(currentVersion)", forHTTPHeaderField: "User-Agent")
            if let bearerToken, !bearerToken.isEmpty {
                req.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
            }
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse else {
                throw MacAutoUpdateError.downloadFailed("Invalid HTTP response from GitHub Releases.")
            }
            return (data, http)
        }

        var (data, http) = try await performReleaseQuery(bearerToken: resolvedAuth?.token)
        if (http.statusCode == 401 || http.statusCode == 403), resolvedAuth != nil {
            // Stale local token — retry unauthenticated in case repository is public
            (data, http) = try await performReleaseQuery(bearerToken: nil)
        }

        if http.statusCode == 404 {
            throw MacAutoUpdateError.authenticationRequired(repoSlug: repoSlug)
        }
        guard http.statusCode == 200 else {
            throw MacAutoUpdateError.downloadFailed("GitHub Releases API returned HTTP \(http.statusCode).")
        }

        let rel: ReleasePayload
        if isBetaChannel {
            let list = try JSONDecoder().decode([ReleasePayload].self, from: data)
                .filter { $0.draft != true }
                .filter { isLegacyPreRebaseVersion(currentVersion) || !isLegacyPreRebaseVersion($0.tag_name) }
            guard let newest = list.max(by: { compareSemver($1.tag_name, isNewerThan: $0.tag_name) }) else {
                return nil
            }
            rel = newest
        } else {
            rel = try JSONDecoder().decode(ReleasePayload.self, from: data)
        }

        let remoteClean = rel.tag_name.trimmingCharacters(in: CharacterSet(charactersIn: "vV"))
        guard compareSemver(remoteClean, isNewerThan: currentVersion),
              let htmlUrl = URL(string: rel.html_url) else {
            return nil
        }

        let zipAsset = rel.assets?.first(where: {
            let lower = $0.name.lowercased()
            return lower.contains("macos") && lower.hasSuffix(".zip")
        }) ?? rel.assets?.first(where: {
            $0.name.lowercased().hasSuffix(".zip")
        })

        let dmgAsset = rel.assets?.first(where: {
            $0.name.lowercased().hasSuffix(".dmg")
        })

        let sumsAsset = rel.assets?.first(where: {
            $0.name == "SHA256SUMS-macos.txt"
        }) ?? rel.assets?.first(where: {
            $0.name == "SHA256SUMS.txt"
        })

        let parsedDigestHex: String? = {
            guard let raw = zipAsset?.digest?.trimmingCharacters(in: .whitespacesAndNewlines),
                  raw.lowercased().hasPrefix("sha256:") else {
                return nil
            }
            let hex = String(raw.dropFirst("sha256:".count)).lowercased()
            return hex.count == 64 ? hex : nil
        }()

        return MacReleaseUpdateCandidate(
            tagName: rel.tag_name,
            releaseTitle: rel.name ?? rel.tag_name,
            htmlReleaseUrl: htmlUrl,
            zipAssetName: zipAsset?.name,
            zipDownloadUrl: zipAsset.flatMap { URL(string: $0.browser_download_url) },
            zipApiAssetUrl: zipAsset?.url.flatMap { URL(string: $0) },
            zipSizeBytes: zipAsset?.size ?? 0,
            zipExpectedSha256: parsedDigestHex,
            dmgAssetName: dmgAsset?.name,
            dmgDownloadUrl: dmgAsset.flatMap { URL(string: $0.browser_download_url) },
            sha256SumsUrl: sumsAsset.flatMap { URL(string: $0.browser_download_url) },
            sha256SumsApiAssetUrl: sumsAsset?.url.flatMap { URL(string: $0) },
            releaseNotes: rel.body ?? ""
        )
    }

    // MARK: - SHA-256 Verification, In-App .zip Extraction & Atomic Bundle Replacement

    public static func sha256Hex(of data: Data) -> String {
        SHA256.hash(data: data)
            .map { String(format: "%02x", $0) }
            .joined()
            .lowercased()
    }

    public static func verifySha256Manifest(
        fileData: Data,
        expectedAssetName: String,
        manifestText: String
    ) -> Bool {
        let actualHex = sha256Hex(of: fileData)

        for rawLine in manifestText.split(whereSeparator: \.isNewline) {
            let parts = rawLine.split(whereSeparator: { $0 == " " || $0 == "\t" })
            guard parts.count >= 2 else { continue }
            let hashToken = String(parts[0]).lowercased()
            let fileToken = String(parts.last!).trimmingCharacters(in: CharacterSet(charactersIn: "*./"))
            if fileToken == expectedAssetName {
                return hashToken == actualHex
            }
        }
        return false
    }

    /// Resolves where the updated `NexusTS.app` should be installed.
    /// If the user launched `NexusTS.app` directly from a read-only mounted DMG (`/Volumes/...`),
    /// automatically installs into `/Applications/NexusTS.app` (or `~/Applications/NexusTS.app`).
    public static func resolveTargetInstallAppURL(
        currentBundleURL: URL = Bundle.main.bundleURL,
        isApplicationsWritable: Bool? = nil
    ) -> URL {
        let standardized = currentBundleURL.standardizedFileURL
        let path = standardized.path
        if path.hasSuffix(".app") && !path.hasPrefix("/Volumes/") {
            return standardized
        }
        let canWriteSysApps = isApplicationsWritable ?? FileManager.default.isWritableFile(atPath: "/Applications")
        if canWriteSysApps {
            return URL(fileURLWithPath: "/Applications/NexusTS.app")
        }
        let userApps = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Applications", isDirectory: true)
        return userApps.appendingPathComponent("NexusTS.app")
    }

    public static func posixSingleQuote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    /// Generates the detached POSIX shell script that waits for the running PID to exit,
    /// atomically swaps the staged `NexusTS.app` into place, clears quarantine xattrs, and relaunches.
    public static func buildAtomicSwapShellScript(
        oldPid: Int32,
        stagedAppPath: String,
        targetAppPath: String,
        stagingRootPath: String
    ) -> String {
        let backupAppPath = "\(targetAppPath).prev-update"
        let targetParentDir = URL(fileURLWithPath: targetAppPath).deletingLastPathComponent().path

        let qStaged = posixSingleQuote(stagedAppPath)
        let qTarget = posixSingleQuote(targetAppPath)
        let qBackup = posixSingleQuote(backupAppPath)
        let qParent = posixSingleQuote(targetParentDir)
        let qStageRoot = posixSingleQuote(stagingRootPath)

        let lsregisterBin = "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
        // Escape command for AppleScript `do shell script "..." with administrator privileges` fallback
        let privCmd = "rm -rf \(qBackup) && (if [ -e \(qTarget) ]; then mv \(qTarget) \(qBackup); fi) && mkdir -p \(qParent) && mv \(qStaged) \(qTarget) && /usr/bin/xattr -cr \(qTarget) && /usr/bin/touch \(qTarget) && (\(lsregisterBin) -f \(qTarget) 2>/dev/null || true) && rm -rf \(qBackup) \(qStageRoot)"
        let appleScriptEscaped = privCmd
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")

        return """
        #!/bin/sh
        OLD_PID=\(oldPid)
        i=0
        while kill -0 "$OLD_PID" 2>/dev/null && [ "$i" -lt 150 ]; do
          sleep 0.1
          i=$((i + 1))
        done

        rm -rf \(qBackup) 2>/dev/null || true
        mkdir -p \(qParent) 2>/dev/null || true
        if [ -e \(qTarget) ]; then
          mv \(qTarget) \(qBackup) 2>/dev/null || true
        fi

        if mv \(qStaged) \(qTarget) 2>/dev/null; then
          /usr/bin/xattr -cr \(qTarget) 2>/dev/null || true
          /usr/bin/touch \(qTarget) 2>/dev/null || true
          \(lsregisterBin) -f \(qTarget) 2>/dev/null || true
          rm -rf \(qBackup) \(qStageRoot) 2>/dev/null || true
          /usr/bin/open -n \(qTarget)
          exit 0
        fi

        if [ -e \(qBackup) ] && [ ! -e \(qTarget) ]; then
          mv \(qBackup) \(qTarget) 2>/dev/null || true
        fi

        if /usr/bin/osascript -e "do shell script \\"\(appleScriptEscaped)\\" with administrator privileges"; then
          /usr/bin/open -n \(qTarget)
          exit 0
        fi

        exit 1
        """
    }

    /// Extracts a `NexusTS-*-macOS-Universal.zip` archive using `/usr/bin/ditto` and verifies the resulting `.app` bundle.
    public static func extractAndValidateAppBundle(
        fromZip zipFileURL: URL,
        into extractDirectoryURL: URL
    ) throws -> URL {
        try FileManager.default.createDirectory(at: extractDirectoryURL, withIntermediateDirectories: true)

        let ditto = Process()
        ditto.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        ditto.arguments = ["-x", "-k", zipFileURL.path, extractDirectoryURL.path]
        let errPipe = Pipe()
        ditto.standardOutput = FileHandle.nullDevice
        ditto.standardError = errPipe
        try ditto.run()
        let errData = errPipe.fileHandleForReading.readDataToEndOfFile()
        ditto.waitUntilExit()
        guard ditto.terminationStatus == 0 else {
            let msg = String(data: errData, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "ditto exit \(ditto.terminationStatus)"
            throw MacAutoUpdateError.extractionFailed("Failed to unpack update archive: \(msg)")
        }

        var foundAppURL: URL?
        if let enumerator = FileManager.default.enumerator(
            at: extractDirectoryURL,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) {
            for case let candidateURL as URL in enumerator {
                if candidateURL.pathExtension == "app" {
                    foundAppURL = candidateURL
                    break
                }
            }
        }

        guard let stagedAppURL = foundAppURL else {
            throw MacAutoUpdateError.invalidAppBundle("No .app bundle found inside \(zipFileURL.lastPathComponent).")
        }

        // Clear any quarantine attributes before verifying and swapping
        let xattr = Process()
        xattr.executableURL = URL(fileURLWithPath: "/usr/bin/xattr")
        xattr.arguments = ["-cr", stagedAppURL.path]
        xattr.standardOutput = FileHandle.nullDevice
        xattr.standardError = FileHandle.nullDevice
        try? xattr.run()
        xattr.waitUntilExit()

        let infoPlistPath = stagedAppURL.appendingPathComponent("Contents/Info.plist").path
        let macExecPath = stagedAppURL.appendingPathComponent("Contents/MacOS/NexusTS").path
        guard FileManager.default.fileExists(atPath: infoPlistPath),
              FileManager.default.fileExists(atPath: macExecPath) else {
            throw MacAutoUpdateError.invalidAppBundle("Extracted NexusTS.app is missing Contents/MacOS/NexusTS.")
        }
        try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: macExecPath)
        return stagedAppURL
    }

    /// Downloads the Universal2 `.zip` release inside the app (reporting live progress),
    /// verifies its SHA-256 digest, unpacks `NexusTS.app`, and launches the atomic in-place swap & relaunch helper.
    public static func downloadVerifyAndStageUpdate(
        candidate: MacReleaseUpdateCandidate,
        explicitToken: String? = nil,
        onPhaseChange: (@Sendable (MacUpdateInstallPhase) -> Void)? = nil
    ) async throws -> (stagedAppURL: URL, stagingRootURL: URL) {
        let auth = resolveGitHubAuthToken(explicitToken: explicitToken)

        let downloadTargetURL: URL
        let useApiOctetStream: Bool
        if let token = auth?.token, !token.isEmpty, let apiUrl = candidate.zipApiAssetUrl {
            downloadTargetURL = apiUrl
            useApiOctetStream = true
        } else if let browserUrl = candidate.zipDownloadUrl {
            downloadTargetURL = browserUrl
            useApiOctetStream = false
        } else if let apiUrl = candidate.zipApiAssetUrl {
            downloadTargetURL = apiUrl
            useApiOctetStream = true
        } else {
            throw MacAutoUpdateError.missingZipBundleAsset(tagName: candidate.tagName)
        }

        let stagingRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("NexusTS-AutoUpdate-\(UUID().uuidString.lowercased())", isDirectory: true)
        try FileManager.default.createDirectory(at: stagingRoot, withIntermediateDirectories: true)

        let assetName = candidate.zipAssetName ?? "NexusTS-\(candidate.tagName)-macOS-Universal.zip"
        let zipDestURL = stagingRoot.appendingPathComponent(assetName)

        onPhaseChange?(.downloading(progress: 0.0))
        do {
            try await downloadGitHubAsset(
                url: downloadTargetURL,
                bearerToken: auth?.token,
                useOctetStreamAccept: useApiOctetStream,
                destinationURL: zipDestURL,
                expectedBytes: candidate.zipSizeBytes
            ) { fraction in
                onPhaseChange?(.downloading(progress: fraction))
            }
        } catch {
            try? FileManager.default.removeItem(at: stagingRoot)
            throw error
        }

        onPhaseChange?(.verifyingSha256)
        let zipData = try Data(contentsOf: zipDestURL)

        if let expectedHex = candidate.zipExpectedSha256 {
            let actualHex = sha256Hex(of: zipData)
            guard actualHex == expectedHex.lowercased() else {
                try? FileManager.default.removeItem(at: stagingRoot)
                throw MacAutoUpdateError.sha256Mismatch(assetName: assetName)
            }
        } else if let sumsUrl = (auth != nil ? candidate.sha256SumsApiAssetUrl : nil) ?? candidate.sha256SumsUrl {
            let sumsDest = stagingRoot.appendingPathComponent("SHA256SUMS-macos.txt")
            let useSumsApi = (sumsUrl == candidate.sha256SumsApiAssetUrl)
            if (try? await downloadGitHubAsset(
                url: sumsUrl,
                bearerToken: auth?.token,
                useOctetStreamAccept: useSumsApi,
                destinationURL: sumsDest,
                expectedBytes: 0,
                onProgress: nil
            )) != nil,
               let manifestText = try? String(contentsOf: sumsDest, encoding: .utf8),
               manifestText.contains(assetName) {
                guard verifySha256Manifest(fileData: zipData, expectedAssetName: assetName, manifestText: manifestText) else {
                    try? FileManager.default.removeItem(at: stagingRoot)
                    throw MacAutoUpdateError.sha256Mismatch(assetName: assetName)
                }
            }
        }

        onPhaseChange?(.extractingAppBundle)
        let extractDir = stagingRoot.appendingPathComponent("extracted", isDirectory: true)
        do {
            let stagedApp = try extractAndValidateAppBundle(fromZip: zipDestURL, into: extractDir)
            try? FileManager.default.removeItem(at: zipDestURL)
            return (stagedApp, stagingRoot)
        } catch {
            try? FileManager.default.removeItem(at: stagingRoot)
            throw error
        }
    }

    public static func launchAtomicSwapHelperAndRelaunch(
        stagedAppURL: URL,
        stagingRootURL: URL,
        targetAppURL: URL? = nil
    ) throws {
        let resolvedTarget = targetAppURL ?? resolveTargetInstallAppURL()
        let script = buildAtomicSwapShellScript(
            oldPid: getpid(),
            stagedAppPath: stagedAppURL.path,
            targetAppPath: resolvedTarget.path,
            stagingRootPath: stagingRootURL.path
        )
        let scriptURL = stagingRootURL.appendingPathComponent("apply-nexusts-update.sh")
        try Data(script.utf8).write(to: scriptURL, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: scriptURL.path)

        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/bin/sh")
        proc.arguments = [scriptURL.path]
        proc.standardInput = FileHandle.nullDevice
        proc.standardOutput = FileHandle.nullDevice
        proc.standardError = FileHandle.nullDevice
        try proc.run()
    }

    private static func downloadGitHubAsset(
        url: URL,
        bearerToken: String?,
        useOctetStreamAccept: Bool,
        destinationURL: URL,
        expectedBytes: Int64,
        onProgress: (@Sendable (Double) -> Void)?
    ) async throws {
        var req = URLRequest(url: url)
        req.setValue("NexusTS-macOS/\(currentVersion)", forHTTPHeaderField: "User-Agent")
        if useOctetStreamAccept {
            req.setValue("application/octet-stream", forHTTPHeaderField: "Accept")
        }
        if let bearerToken, !bearerToken.isEmpty {
            req.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        }

        let delegate = GitHubAssetDownloadDelegate(
            destinationURL: destinationURL,
            fallbackExpectedBytes: expectedBytes,
            onProgress: onProgress
        )
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        let (_, http) = try await withCheckedThrowingContinuation { (cont: CheckedContinuation<(URL, HTTPURLResponse), Error>) in
            delegate.attachContinuation(cont)
            let task = session.downloadTask(with: req)
            task.resume()
        }

        if http.statusCode == 401 || http.statusCode == 404 {
            throw MacAutoUpdateError.authenticationRequired(repoSlug: "petrosvassiliou/NexusTS")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw MacAutoUpdateError.downloadFailed("Asset download failed with HTTP \(http.statusCode).")
        }
    }

    public static func compareSemver(_ candidate: String, isNewerThan current: String) -> Bool {
        let candTrim = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "vV"))
        let currTrim = current.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "vV"))
        guard !candTrim.isEmpty, !currTrim.isEmpty else { return false }
        if !isLegacyPreRebaseVersion(currTrim) && isLegacyPreRebaseVersion(candTrim) {
            return false
        }

        let candNoBuild = String(candTrim.split(separator: "+", maxSplits: 1).first ?? "")
        let currNoBuild = String(currTrim.split(separator: "+", maxSplits: 1).first ?? "")

        let candMainAndPre = candNoBuild.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        let currMainAndPre = currNoBuild.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)

        let candCore = candMainAndPre[0].split(separator: ".").map { Int($0) ?? 0 }
        let currCore = currMainAndPre[0].split(separator: ".").map { Int($0) ?? 0 }
        let maxCore = max(candCore.count, currCore.count)
        for i in 0..<maxCore {
            let l = i < candCore.count ? candCore[i] : 0
            let r = i < currCore.count ? currCore[i] : 0
            if l != r { return l > r }
        }

        let candPre = candMainAndPre.count > 1 ? String(candMainAndPre[1]) : ""
        let currPre = currMainAndPre.count > 1 ? String(currMainAndPre[1]) : ""

        // Stable release has higher precedence than pre-release of the same core version (1.2.1 > 1.2.1-beta.1)
        if candPre.isEmpty && !currPre.isEmpty { return true }
        if !candPre.isEmpty && currPre.isEmpty { return false }
        if candPre.isEmpty && currPre.isEmpty { return false }

        let candSegs = candPre.split(separator: ".").map(String.init)
        let currSegs = currPre.split(separator: ".").map(String.init)
        let minSegs = min(candSegs.count, currSegs.count)
        for i in 0..<minSegs {
            let cs = candSegs[i]
            let us = currSegs[i]
            if cs == us { continue }
            if let cn = Int(cs), let un = Int(us) {
                return cn > un
            }
            if Int(cs) != nil && Int(us) == nil { return false }
            if Int(cs) == nil && Int(us) != nil { return true }
            return cs > us
        }
        return candSegs.count > currSegs.count
    }
}

private final class GitHubAssetDownloadDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let destinationURL: URL
    private let fallbackExpectedBytes: Int64
    private let onProgress: (@Sendable (Double) -> Void)?
    private var continuation: CheckedContinuation<(URL, HTTPURLResponse), Error>?
    private var fileMoveError: Error?

    init(
        destinationURL: URL,
        fallbackExpectedBytes: Int64,
        onProgress: (@Sendable (Double) -> Void)?
    ) {
        self.destinationURL = destinationURL
        self.fallbackExpectedBytes = fallbackExpectedBytes
        self.onProgress = onProgress
    }

    func attachContinuation(_ cont: CheckedContinuation<(URL, HTTPURLResponse), Error>) {
        lock.lock()
        self.continuation = cont
        lock.unlock()
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        var redirected = request
        if response.url?.host?.lowercased() != request.url?.host?.lowercased() {
            // Strip GitHub API Bearer token when following 302 redirect to presigned S3/Azure asset storage
            redirected.setValue(nil, forHTTPHeaderField: "Authorization")
            redirected.setValue("*/*", forHTTPHeaderField: "Accept")
        }
        completionHandler(redirected)
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        let total = totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : fallbackExpectedBytes
        guard total > 0 else { return }
        let fraction = min(1.0, max(0.0, Double(totalBytesWritten) / Double(total)))
        onProgress?(fraction)
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        do {
            if FileManager.default.fileExists(atPath: destinationURL.path) {
                try FileManager.default.removeItem(at: destinationURL)
            }
            try FileManager.default.moveItem(at: location, to: destinationURL)
            onProgress?(1.0)
        } catch {
            lock.lock()
            self.fileMoveError = error
            lock.unlock()
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        lock.lock()
        let cont = self.continuation
        self.continuation = nil
        let moveErr = self.fileMoveError
        lock.unlock()

        guard let cont else { return }
        if let error {
            cont.resume(throwing: error)
        } else if let moveErr {
            cont.resume(throwing: moveErr)
        } else if let http = task.response as? HTTPURLResponse {
            cont.resume(returning: (destinationURL, http))
        } else {
            cont.resume(throwing: MacAutoUpdateError.downloadFailed("Missing HTTP response during update download."))
        }
    }
}
