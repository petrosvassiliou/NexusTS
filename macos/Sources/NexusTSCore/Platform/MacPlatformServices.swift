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
    case noStoredPassphrase

    public var errorDescription: String? {
        switch self {
        case .biometricsUnavailable(let msg): return msg
        case .authenticationCancelled(let msg): return msg
        case .keychainFailure(let status): return "macOS Keychain returned OSStatus \(status)."
        case .noStoredPassphrase: return "No vault passphrase saved in macOS Keychain yet."
        }
    }
}

public final class MacKeychainAndBiometrics: @unchecked Sendable {
    public static let shared = MacKeychainAndBiometrics()
    private let serviceName = "com.nexusts.app.macos.vault"

    public init() {}

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
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: vaultId,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        return SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess
    }

    public func storePassphraseInKeychain(_ passphrase: String, forVaultId vaultId: String) throws {
        let secretData = Data(passphrase.utf8)
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: vaultId
        ]
        SecItemDelete(deleteQuery as CFDictionary)

        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: vaultId,
            kSecAttrLabel as String: "NexusTS Zero-Knowledge Vault (\(vaultId))",
            kSecValueData as String: secretData,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        let status = SecItemAdd(addQuery as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw BiometricUnlockError.keychainFailure(status)
        }
    }

    public func deleteStoredPassphrase(forVaultId vaultId: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: vaultId
        ]
        SecItemDelete(query as CFDictionary)
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

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: vaultId,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess,
              let data = item as? Data,
              let secret = String(data: data, encoding: .utf8) else {
            throw BiometricUnlockError.keychainFailure(status)
        }
        return secret
    }

    // Generic OAuth / API Token Keychain Helpers
    public func saveToken(_ token: String, key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "\(serviceName).tokens",
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
        guard !token.isEmpty else { return }
        var add = query
        add[kSecValueData as String] = Data(token.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    public func loadToken(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "\(serviceName).tokens",
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
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

        if settings.flowControl.uppercased() == "RTS_CTS" {
            options.c_cflag |= tcflag_t(CRTSCTS)
        } else if settings.flowControl.uppercased() == "XON_XOFF" {
            options.c_iflag |= tcflag_t(IXON | IXOFF)
        }

        let speed = speed_t(max(300, settings.baudRate))
        cfsetspeed(&options, speed)
        tcsetattr(rawFd, TCSANOW, &options)

        // macOS IOSSIOSPEED ioctl (0x80085402) allows non-standard high baud rates like 460800 / 921600
        let iossiospeed: UInt = 0x8008_5402
        var customSpeed = speed_t(settings.baudRate)
        _ = ioctl(rawFd, iossiospeed, &customSpeed)

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
            kill(pid, SIGHUP)
            var st: Int32 = 0
            _ = waitpid(pid, &st, WNOHANG)
        }
    }
}

// MARK: - 5. GitHub Releases macOS Signed DMG Update Checker & SHA-256 Verifier

public struct MacReleaseUpdateCandidate: Equatable, Sendable {
    public let tagName: String
    public let releaseTitle: String
    public let htmlReleaseUrl: URL
    public let dmgAssetName: String?
    public let dmgDownloadUrl: URL?
    public let sha256SumsUrl: URL?
    public let releaseNotes: String

    public init(
        tagName: String,
        releaseTitle: String,
        htmlReleaseUrl: URL,
        dmgAssetName: String? = nil,
        dmgDownloadUrl: URL? = nil,
        sha256SumsUrl: URL? = nil,
        releaseNotes: String = ""
    ) {
        self.tagName = tagName
        self.releaseTitle = releaseTitle
        self.htmlReleaseUrl = htmlReleaseUrl
        self.dmgAssetName = dmgAssetName
        self.dmgDownloadUrl = dmgDownloadUrl
        self.sha256SumsUrl = sha256SumsUrl
        self.releaseNotes = releaseNotes
    }
}

public enum MacGitHubReleaseUpdater {
    public static let currentVersion = "1.1.7"

    public static func checkForUpdates(
        repoSlug: String = "petrosvassiliou/NexusTS"
    ) async throws -> MacReleaseUpdateCandidate? {
        guard let url = URL(string: "https://api.github.com/repos/\(repoSlug)/releases/latest") else {
            return nil
        }
        var req = URLRequest(url: url)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("NexusTS-macOS/\(currentVersion)", forHTTPHeaderField: "User-Agent")

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else { return nil }

        struct ReleasePayload: Decodable {
            struct Asset: Decodable {
                let name: String
                let browser_download_url: String
            }
            let tag_name: String
            let name: String?
            let html_url: String
            let body: String?
            let assets: [Asset]?
        }
        let rel = try JSONDecoder().decode(ReleasePayload.self, from: data)
        let remoteClean = rel.tag_name.trimmingCharacters(in: CharacterSet(charactersIn: "vV"))
        guard compareSemver(remoteClean, isNewerThan: currentVersion),
              let htmlUrl = URL(string: rel.html_url) else {
            return nil
        }

        let dmgAsset = rel.assets?.first(where: {
            $0.name.lowercased().hasSuffix(".dmg")
        }) ?? rel.assets?.first(where: {
            $0.name.lowercased().contains("macos") && $0.name.lowercased().hasSuffix(".zip")
        })

        let sumsAsset = rel.assets?.first(where: {
            $0.name == "SHA256SUMS-macos.txt"
        }) ?? rel.assets?.first(where: {
            $0.name == "SHA256SUMS.txt"
        })

        return MacReleaseUpdateCandidate(
            tagName: rel.tag_name,
            releaseTitle: rel.name ?? rel.tag_name,
            htmlReleaseUrl: htmlUrl,
            dmgAssetName: dmgAsset?.name,
            dmgDownloadUrl: dmgAsset.flatMap { URL(string: $0.browser_download_url) },
            sha256SumsUrl: sumsAsset.flatMap { URL(string: $0.browser_download_url) },
            releaseNotes: rel.body ?? ""
        )
    }

    public static func verifySha256Manifest(
        fileData: Data,
        expectedAssetName: String,
        manifestText: String
    ) -> Bool {
        let actualHex = SHA256.hash(data: fileData)
            .map { String(format: "%02x", $0) }
            .joined()
            .lowercased()

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

    public static func downloadAndVerifyDmg(
        candidate: MacReleaseUpdateCandidate
    ) async throws -> URL {
        guard let dmgUrl = candidate.dmgDownloadUrl else {
            throw NativeBridgeError.networkUtilityError("Release \(candidate.tagName) does not include a macOS DMG asset yet.")
        }
        let assetName = candidate.dmgAssetName ?? dmgUrl.lastPathComponent
        let (dmgData, dmgResp) = try await URLSession.shared.data(from: dmgUrl)
        guard let http = dmgResp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw NativeBridgeError.networkUtilityError("Failed to download \(assetName)")
        }

        if let sumsUrl = candidate.sha256SumsUrl,
           let (sumsData, sumsResp) = try? await URLSession.shared.data(from: sumsUrl),
           let sumsHttp = sumsResp as? HTTPURLResponse,
           (200..<300).contains(sumsHttp.statusCode),
           let manifestText = String(data: sumsData, encoding: .utf8),
           manifestText.contains(assetName) {
            guard verifySha256Manifest(fileData: dmgData, expectedAssetName: assetName, manifestText: manifestText) else {
                throw NativeBridgeError.authenticationFailed("SHA-256 checksum mismatch for \(assetName)!")
            }
        }

        let downloadsDir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let destination = downloadsDir.appendingPathComponent(assetName)
        try dmgData.write(to: destination, options: .atomic)
        return destination
    }

    public static func compareSemver(_ candidate: String, isNewerThan current: String) -> Bool {
        let lhs = candidate.split(separator: ".").map { Int($0.prefix(while: \.isNumber)) ?? 0 }
        let rhs = current.split(separator: ".").map { Int($0.prefix(while: \.isNumber)) ?? 0 }
        let count = max(lhs.count, rhs.count)
        for i in 0..<count {
            let l = i < lhs.count ? lhs[i] : 0
            let r = i < rhs.count ? rhs[i] : 0
            if l != r { return l > r }
        }
        return false
    }
}
