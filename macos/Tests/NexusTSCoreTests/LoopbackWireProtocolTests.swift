import CoreGraphics
import Darwin
import Foundation
@testable import NexusTSCore
import XCTest

final class LoopbackWireProtocolTests: XCTestCase {

    // MARK: - POSIX Loopback Socket Helpers

    private struct LoopbackListener {
        let fd: Int32
        let port: UInt16

        func close() {
            if fd >= 0 {
                Darwin.close(fd)
            }
        }
    }

    private static func makeLoopbackListener(preferredPort: UInt16 = 0) throws -> LoopbackListener {
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
        }

        var one: Int32 = 1
        _ = setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        _ = setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = preferredPort.bigEndian
        addr.sin_addr = in_addr(s_addr: UInt32(0x7F00_0001).bigEndian)

        let bindRc = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Darwin.bind(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindRc == 0 else {
            let err = errno
            Darwin.close(fd)
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(err))
        }

        guard Darwin.listen(fd, 16) == 0 else {
            let err = errno
            Darwin.close(fd)
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(err))
        }

        var bound = sockaddr_in()
        var boundLen = socklen_t(MemoryLayout<sockaddr_in>.size)
        let nameRc = withUnsafeMutablePointer(to: &bound) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Darwin.getsockname(fd, sa, &boundLen)
            }
        }
        guard nameRc == 0 else {
            let err = errno
            Darwin.close(fd)
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(err))
        }

        return LoopbackListener(fd: fd, port: UInt16(bigEndian: bound.sin_port))
    }

    private static func acceptSocket(_ serverFd: Int32, timeoutSeconds: Int = 5) -> Int32 {
        var tv = timeval(tv_sec: timeoutSeconds, tv_usec: 0)
        _ = setsockopt(serverFd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        let clientFd = Darwin.accept(serverFd, nil, nil)
        guard clientFd >= 0 else { return -1 }

        var one: Int32 = 1
        _ = setsockopt(clientFd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        _ = setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        _ = setsockopt(clientFd, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        return clientFd
    }

    @discardableResult
    private static func sendAll(_ fd: Int32, _ bytes: [UInt8]) -> Bool {
        guard fd >= 0 else { return false }
        if bytes.isEmpty { return true }
        return bytes.withUnsafeBufferPointer { buf in
            guard let base = buf.baseAddress else { return false }
            var sent = 0
            while sent < buf.count {
                let n = Darwin.send(fd, base.advanced(by: sent), buf.count - sent, 0)
                if n > 0 {
                    sent += n
                } else if n < 0 && errno == EINTR {
                    continue
                } else {
                    return false
                }
            }
            return true
        }
    }

    @discardableResult
    private static func sendLine(_ fd: Int32, _ line: String) -> Bool {
        sendAll(fd, Array("\(line)\r\n".utf8))
    }

    private static func recvExact(_ fd: Int32, count: Int) -> [UInt8]? {
        guard fd >= 0 else { return nil }
        if count == 0 { return [] }
        var out = [UInt8](repeating: 0, count: count)
        var got = 0
        while got < count {
            let n = out.withUnsafeMutableBufferPointer { buf -> Int in
                guard let base = buf.baseAddress else { return -1 }
                return Darwin.recv(fd, base.advanced(by: got), count - got, 0)
            }
            if n > 0 {
                got += n
            } else if n < 0 && errno == EINTR {
                continue
            } else {
                return nil
            }
        }
        return out
    }

    private final class PosixLineReader {
        private let fd: Int32
        private var buffer = Data()

        init(fd: Int32) {
            self.fd = fd
        }

        func readLine() -> String? {
            while true {
                if let nl = buffer.firstIndex(of: 0x0A) {
                    let slice = buffer[..<nl]
                    buffer.removeSubrange(...nl)
                    var text = String(decoding: slice, as: UTF8.self)
                    if text.hasSuffix("\r") {
                        text.removeLast()
                    }
                    return text
                }
                var chunk = [UInt8](repeating: 0, count: 1024)
                let n = Darwin.recv(fd, &chunk, chunk.count, 0)
                if n > 0 {
                    buffer.append(contentsOf: chunk.prefix(n))
                } else if n < 0 && errno == EINTR {
                    continue
                } else {
                    return nil
                }
            }
        }
    }

    // MARK: - 1. RDP PC XT Set-1 Scancode Mapper, 9-Bit Wheel & Aspect-Fit Viewport Math

    func testMacRdpScancodeWheelAndAspectViewportMapping() throws {
        // Verify 's' (Carbon 0x01) maps to XT 0x1F rather than Escape (0x01)
        let keyS = try XCTUnwrap(MacRdpScancodeMapper.mapKeyCode(0x01))
        XCTAssertEqual(keyS.scancode, 0x1F)
        XCTAssertFalse(keyS.extended)
        XCTAssertNotEqual(keyS.scancode, 0x01)

        // Verify WASD + Esc + Return
        XCTAssertEqual(MacRdpScancodeMapper.mapKeyCode(0x00)?.scancode, 0x1E) // A
        XCTAssertEqual(MacRdpScancodeMapper.mapKeyCode(0x0D)?.scancode, 0x11) // W
        XCTAssertEqual(MacRdpScancodeMapper.mapKeyCode(0x02)?.scancode, 0x20) // D
        XCTAssertEqual(MacRdpScancodeMapper.mapKeyCode(0x35)?.scancode, 0x01) // Esc
        XCTAssertEqual(MacRdpScancodeMapper.mapKeyCode(0x24)?.scancode, 0x1C) // Return

        // Verify extended navigation & arrow keys
        let up = try XCTUnwrap(MacRdpScancodeMapper.mapKeyCode(0x7E))
        let down = try XCTUnwrap(MacRdpScancodeMapper.mapKeyCode(0x7D))
        let left = try XCTUnwrap(MacRdpScancodeMapper.mapKeyCode(0x7B))
        let right = try XCTUnwrap(MacRdpScancodeMapper.mapKeyCode(0x7C))
        let fwdDel = try XCTUnwrap(MacRdpScancodeMapper.mapKeyCode(0x75))
        XCTAssertTrue(up.scancode == 0x48 && up.extended)
        XCTAssertTrue(down.scancode == 0x50 && down.extended)
        XCTAssertTrue(left.scancode == 0x4B && left.extended)
        XCTAssertTrue(right.scancode == 0x4D && right.extended)
        XCTAssertTrue(fwdDel.scancode == 0x53 && fwdDel.extended)

        // Verify F1..F12
        XCTAssertEqual(MacRdpScancodeMapper.mapKeyCode(0x7A)?.scancode, 0x3B) // F1
        XCTAssertEqual(MacRdpScancodeMapper.mapKeyCode(0x6D)?.scancode, 0x44) // F10
        XCTAssertEqual(MacRdpScancodeMapper.mapKeyCode(0x67)?.scancode, 0x57) // F11
        XCTAssertEqual(MacRdpScancodeMapper.mapKeyCode(0x6F)?.scancode, 0x58) // F12

        // Verify modifier flagsChanged mapping & Command shortcut gating
        let lShift = try XCTUnwrap(MacRdpScancodeMapper.mapModifierKeyCode(0x38))
        let rCtrl = try XCTUnwrap(MacRdpScancodeMapper.mapModifierKeyCode(0x3E))
        let rAlt = try XCTUnwrap(MacRdpScancodeMapper.mapModifierKeyCode(0x3D))
        let caps = try XCTUnwrap(MacRdpScancodeMapper.mapModifierKeyCode(0x39))
        XCTAssertTrue(lShift.scancode == 0x2A && !lShift.extended && !lShift.isCapsLock)
        XCTAssertTrue(rCtrl.scancode == 0x1D && rCtrl.extended)
        XCTAssertTrue(rAlt.scancode == 0x38 && rAlt.extended)
        XCTAssertTrue(caps.scancode == 0x3A && caps.isCapsLock)

        let lWinCaptured = try XCTUnwrap(MacRdpScancodeMapper.mapModifierKeyCode(0x37, captureRemoteShortcuts: true))
        XCTAssertTrue(lWinCaptured.scancode == 0x5B && lWinCaptured.extended)
        XCTAssertNil(MacRdpScancodeMapper.mapModifierKeyCode(0x37, captureRemoteShortcuts: false))
        XCTAssertNil(MacRdpScancodeMapper.mapModifierKeyCode(0x36, captureRemoteShortcuts: false))

        // Verify 9-bit signed wheel flags
        XCTAssertNil(MacRdpScancodeMapper.encodeWheelFlags(delta: 0))
        XCTAssertEqual(MacRdpScancodeMapper.encodeWheelFlags(delta: 120, horizontal: false), 0x0278)
        XCTAssertEqual(MacRdpScancodeMapper.encodeWheelFlags(delta: -120, horizontal: false), 0x0388)
        XCTAssertEqual(MacRdpScancodeMapper.encodeWheelFlags(delta: -60, horizontal: true), 0x05C4)

        // Verify aspect-fit letterbox mapping (1920x1080 remote inside 1920x1200 NSView -> 60px top/bottom bars)
        let centerPt = MacRdpScancodeMapper.mapViewportPoint(
            localX: 960,
            localY: 600,
            viewWidth: 1920,
            viewHeight: 1200,
            remoteWidth: 1920,
            remoteHeight: 1080,
            scaleToFit: true,
            originBottomLeft: true
        )
        XCTAssertEqual(centerPt.x, 960)
        XCTAssertEqual(centerPt.y, 540)

        let topLetterboxBarPt = MacRdpScancodeMapper.mapViewportPoint(
            localX: 200,
            localY: 1185, // inside top 60px letterbox bar (bottom-left NSView coordinates)
            viewWidth: 1920,
            viewHeight: 1200,
            remoteWidth: 1920,
            remoteHeight: 1080,
            scaleToFit: true,
            originBottomLeft: true
        )
        XCTAssertEqual(topLetterboxBarPt.x, 200)
        XCTAssertEqual(topLetterboxBarPt.y, 0)

        // Verify zero-swizzle BGRX32 CGImage creation
        let bgrxPixels = Data(repeating: 0x7F, count: 4 * 4 * 4)
        let cgImg = try XCTUnwrap(NativeRdpSession.makeBgrxCGImage(bgrx: bgrxPixels, width: 4, height: 4))
        XCTAssertEqual(cgImg.width, 4)
        XCTAssertEqual(cgImg.height, 4)
        XCTAssertEqual(cgImg.bitmapInfo.rawValue, NativeRdpSession.bgrxBitmapInfo)
    }

    // MARK: - 2. WINDOWS_TERMINAL_SERVICES Routing, QuickConnect Password & Shell Injection Escaping

    func testWindowsTerminalServicesRoutingQuickConnectPasswordAndSafeExec() throws {
        let wtsSettings = VaultProtocolType.WINDOWS_TERMINAL_SERVICES.defaultSettings(host: "rds01.corp.local")
        if case .rdp(let rdpCfg) = wtsSettings {
            XCTAssertEqual(rdpCfg.port, 3389)
        } else {
            XCTFail("Expected WINDOWS_TERMINAL_SERVICES defaultSettings to produce .rdp settings")
        }

        let wtsConn = VaultConnection(
            name: "Prod RDS Farm",
            host: "rds01.corp.local",
            protocolType: .WINDOWS_TERMINAL_SERVICES
        )
        XCTAssertEqual(wtsConn.effectivePort, 3389)

        let rdpQuick = try XCTUnwrap(
            QuickConnectParser.parseAdHocUri("CORP\\alice:S3cr3t!@rdp.corp.local:3390")
        )
        XCTAssertEqual(rdpQuick.protocolType, .RDP)
        XCTAssertEqual(rdpQuick.domain, "CORP")
        XCTAssertEqual(rdpQuick.username, "alice")
        XCTAssertEqual(rdpQuick.password, "S3cr3t!")
        XCTAssertEqual(rdpQuick.host, "rdp.corp.local")
        XCTAssertEqual(rdpQuick.port, 3390)

        let ftpesQuick = try XCTUnwrap(
            QuickConnectParser.parseAdHocUri("ftpes://deploy:pw99@files.corp.local")
        )
        XCTAssertEqual(ftpesQuick.protocolType, .FTPS)
        XCTAssertEqual(ftpesQuick.port, 21)
        XCTAssertEqual(ftpesQuick.username, "deploy")
        XCTAssertEqual(ftpesQuick.password, "pw99")
        if case .fileTransfer(let ftCfg) = ftpesQuick.settings {
            XCTAssertFalse(ftCfg.ftpsImplicitTls)
        } else {
            XCTFail("Expected fileTransfer settings for ftpes://")
        }

        let webConn = VaultConnection(
            name: "Intranet UI",
            host: "intranet.local",
            protocolType: .WEB_PAGE,
            settings: .webPage(WebPageProtocolSettings(url: "https://intranet.local:8443/ui"))
        )
        XCTAssertEqual(webConn.effectivePort, 8443)

        // Verify shell metacharacter injection neutralization in container exec builder
        var untrustedExec = ContainerExecProtocolSettings(
            podOrContainerName: "api-pod; touch /tmp/pwned"
        )
        untrustedExec.namespace = "prod$(whoami)"
        untrustedExec.shellCommand = "/bin/sh -c id"
        untrustedExec.contextName = "eks'prod"
        let safeKubeCmd = ContainerWorkloadClient.buildSafeExecCommand(
            protocolType: .KUBERNETES_POD,
            settings: untrustedExec
        )
        XCTAssertTrue(safeKubeCmd.contains("'eks'\\''prod'"))
        XCTAssertTrue(safeKubeCmd.contains("'prod$(whoami)'"))
        XCTAssertTrue(safeKubeCmd.contains("'api-pod; touch /tmp/pwned'"))
    }

    // MARK: - 3. Local Darwin PTY Execution & Non-Zombie Child Process Reaping

    func testLocalPtySessionExecutesAndReapsWithoutZombie() throws {
        let pty = MacLocalPtySession()
        try pty.launchLoginShell(cols: 100, rows: 30)
        let pid = pty.processIdentifier
        XCTAssertGreaterThan(pid, 0)

        pty.sendInput(Data("echo NEXUSTS_PTY_ZOMBIE_CHECK_OK\n".utf8))

        let deadline = Date().addingTimeInterval(3.0)
        var collected = ""
        while Date() < deadline && !collected.contains("NEXUSTS_PTY_ZOMBIE_CHECK_OK") {
            let chunk = pty.drainOutput()
            if !chunk.bytes.isEmpty {
                collected += String(decoding: chunk.bytes, as: UTF8.self)
            }
            Thread.sleep(forTimeInterval: 0.025)
        }
        XCTAssertTrue(collected.contains("NEXUSTS_PTY_ZOMBIE_CHECK_OK"))

        pty.terminate()

        let reapDeadline = Date().addingTimeInterval(2.5)
        var processGone = false
        while Date() < reapDeadline {
            if Darwin.kill(pid, 0) != 0 && errno == ESRCH {
                processGone = true
                break
            }
            Thread.sleep(forTimeInterval: 0.03)
        }
        XCTAssertTrue(processGone, "Expected local PTY child pid \(pid) to be completely reaped (ESRCH)")
    }

    // MARK: - 4. Live Loopback Telnet Session + VT100 DECCKM Application Cursor Mode

    func testLiveLoopbackTelnetNegotiationAndVtDecckmCursorMode() throws {
        let listener = try Self.makeLoopbackListener()
        defer { listener.close() }

        let serverDone = expectation(description: "Loopback Telnet server completed")
        final class TelnetCapture: @unchecked Sendable {
            var receivedBytes = Data()
        }
        let capture = TelnetCapture()

        DispatchQueue.global(qos: .userInitiated).async {
            let clientFd = Self.acceptSocket(listener.fd, timeoutSeconds: 5)
            defer {
                if clientFd >= 0 { Darwin.close(clientFd) }
                serverDone.fulfill()
            }
            guard clientFd >= 0 else { return }

            // Send IAC DO TTYPE (255,253,24), IAC DO NAWS (255,253,31), banner, and DECCKM enable (\e[?1h)
            var greeting: [UInt8] = [255, 253, 24, 255, 253, 31]
            greeting.append(contentsOf: "NEXUSTS_TELNET_READY\r\n\u{1B}[?1h".utf8)
            _ = Self.sendAll(clientFd, greeting)

            let readDeadline = Date().addingTimeInterval(3.0)
            var buf = [UInt8](repeating: 0, count: 512)
            while Date() < readDeadline {
                let n = Darwin.recv(clientFd, &buf, buf.count, 0)
                if n > 0 {
                    capture.receivedBytes.append(contentsOf: buf.prefix(n))
                    let text = String(decoding: capture.receivedBytes, as: UTF8.self)
                    if text.contains("PING_FROM_MAC") {
                        break
                    }
                } else {
                    break
                }
            }
        }

        let telnet = try NativeTelnetSession.connect(
            host: "127.0.0.1",
            port: listener.port,
            cols: 120,
            rows: 36
        )
        defer { telnet.disconnect() }

        let vt = NativeVtTerminalEngine(cols: 120, rows: 36)
        XCTAssertFalse(vt.applicationCursorKeys)

        var drained = Data()
        let drainDeadline = Date().addingTimeInterval(3.0)
        while Date() < drainDeadline {
            let chunk = telnet.drainOutput()
            if !chunk.bytes.isEmpty {
                drained.append(chunk.bytes)
                _ = vt.feedAndSnapshot(chunk.bytes)
            }
            if String(decoding: drained, as: UTF8.self).contains("NEXUSTS_TELNET_READY") && vt.applicationCursorKeys {
                break
            }
            Thread.sleep(forTimeInterval: 0.02)
        }

        XCTAssertFalse(drained.contains(0xFF), "IAC negotiation bytes must be stripped from terminal stream")
        XCTAssertTrue(String(decoding: drained, as: UTF8.self).contains("NEXUSTS_TELNET_READY"))
        XCTAssertTrue(vt.applicationCursorKeys, "CSI ? 1 h must enable DECCKM application cursor keys")

        // Verify CSI ? 1 l resets DECCKM back to normal cursor mode
        _ = vt.feedAndSnapshot(Data("\u{1B}[?1l".utf8))
        XCTAssertFalse(vt.applicationCursorKeys)

        XCTAssertTrue(telnet.sendInput(Data("PING_FROM_MAC\r\n".utf8)))
        wait(for: [serverDone], timeout: 5.0)

        XCTAssertTrue(capture.receivedBytes.contains(255), "Client must send Telnet IAC negotiation responses")
        XCTAssertTrue(String(decoding: capture.receivedBytes, as: UTF8.self).contains("PING_FROM_MAC"))
    }

    // MARK: - 5. Live Loopback FTP Server Exercising NativeFtpClient End-to-End

    func testLiveLoopbackFtpUploadDownloadListRenameChmodAndDelete() throws {
        let listener = try Self.makeLoopbackListener()
        defer { listener.close() }

        let serverFinished = expectation(description: "Loopback FTP server finished")

        DispatchQueue.global(qos: .userInitiated).async {
            let ctrlFd = Self.acceptSocket(listener.fd, timeoutSeconds: 5)
            var pendingPasv: LoopbackListener? = nil
            defer {
                pendingPasv?.close()
                if ctrlFd >= 0 { Darwin.close(ctrlFd) }
                serverFinished.fulfill()
            }
            guard ctrlFd >= 0 else { return }

            var files: [String: (data: Data, mode: UInt32)] = [:]
            var dirs: Set<String> = ["/"]
            var currentDir = "/"
            var renameSource: String? = nil

            func normPath(_ raw: String) -> String {
                let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                if t.isEmpty || t == "." { return currentDir }
                return t.hasPrefix("/") ? t : (currentDir == "/" ? "/\(t)" : "\(currentDir)/\(t)")
            }

            _ = Self.sendLine(ctrlFd, "220 NexusTS Loopback FTP Ready")
            let reader = PosixLineReader(fd: ctrlFd)

            while let rawLine = reader.readLine() {
                let parts = rawLine.split(separator: " ", maxSplits: 1).map(String.init)
                let verb = (parts.first ?? "").uppercased()
                let arg = parts.count > 1 ? parts[1] : ""

                switch verb {
                case "USER":
                    _ = Self.sendLine(ctrlFd, "331 User OK, need password")
                case "PASS":
                    _ = Self.sendLine(ctrlFd, "230 Logged in")
                case "OPTS", "NOOP", "SYST", "FEAT":
                    _ = Self.sendLine(ctrlFd, "200 OK")
                case "TYPE":
                    _ = Self.sendLine(ctrlFd, "200 Type set to I")
                case "PWD", "XPWD":
                    _ = Self.sendLine(ctrlFd, "257 \"\(currentDir)\" is current directory")
                case "CWD":
                    currentDir = normPath(arg)
                    _ = Self.sendLine(ctrlFd, "250 CWD successful")
                case "MKD", "XMKD":
                    let p = normPath(arg)
                    dirs.insert(p)
                    _ = Self.sendLine(ctrlFd, "257 \"\(p)\" created")
                case "EPSV":
                    pendingPasv?.close()
                    if let pasv = try? Self.makeLoopbackListener() {
                        pendingPasv = pasv
                        _ = Self.sendLine(ctrlFd, "229 Entering Extended Passive Mode (|||\(pasv.port)|)")
                    } else {
                        _ = Self.sendLine(ctrlFd, "425 Cannot open passive connection")
                    }
                case "STOR":
                    let target = normPath(arg)
                    guard let pasv = pendingPasv else {
                        _ = Self.sendLine(ctrlFd, "425 Use EPSV first")
                        continue
                    }
                    pendingPasv = nil
                    let dataFd = Self.acceptSocket(pasv.fd, timeoutSeconds: 5)
                    pasv.close()
                    _ = Self.sendLine(ctrlFd, "150 Opening binary data connection")
                    var uploaded = Data()
                    if dataFd >= 0 {
                        var buf = [UInt8](repeating: 0, count: 4096)
                        while true {
                            let n = Darwin.recv(dataFd, &buf, buf.count, 0)
                            if n > 0 {
                                uploaded.append(contentsOf: buf.prefix(n))
                            } else {
                                break
                            }
                        }
                        Darwin.close(dataFd)
                    }
                    files[target] = (uploaded, 0o644)
                    _ = Self.sendLine(ctrlFd, "226 Transfer complete")
                case "RETR":
                    let target = normPath(arg)
                    guard let pasv = pendingPasv else {
                        _ = Self.sendLine(ctrlFd, "425 Use EPSV first")
                        continue
                    }
                    pendingPasv = nil
                    let dataFd = Self.acceptSocket(pasv.fd, timeoutSeconds: 5)
                    pasv.close()
                    guard let entry = files[target], dataFd >= 0 else {
                        if dataFd >= 0 { Darwin.close(dataFd) }
                        _ = Self.sendLine(ctrlFd, "550 File not found")
                        continue
                    }
                    _ = Self.sendLine(ctrlFd, "150 Opening binary data connection")
                    _ = Self.sendAll(dataFd, Array(entry.data))
                    Darwin.close(dataFd)
                    _ = Self.sendLine(ctrlFd, "226 Transfer complete")
                case "MLSD":
                    guard let pasv = pendingPasv else {
                        _ = Self.sendLine(ctrlFd, "425 Use EPSV first")
                        continue
                    }
                    pendingPasv = nil
                    let dataFd = Self.acceptSocket(pasv.fd, timeoutSeconds: 5)
                    pasv.close()
                    _ = Self.sendLine(ctrlFd, "150 Opening MLSD data connection")
                    if dataFd >= 0 {
                        var listing = ""
                        for d in dirs.sorted() where d != "/" {
                            let name = URL(fileURLWithPath: d).lastPathComponent
                            listing += "type=dir;UNIX.mode=0755; \(name)\r\n"
                        }
                        for (path, info) in files.sorted(by: { $0.key < $1.key }) {
                            let name = URL(fileURLWithPath: path).lastPathComponent
                            let modeStr = String(format: "%04o", info.mode)
                            listing += "type=file;size=\(info.data.count);UNIX.mode=\(modeStr); \(name)\r\n"
                        }
                        _ = Self.sendAll(dataFd, Array(listing.utf8))
                        Darwin.close(dataFd)
                    }
                    _ = Self.sendLine(ctrlFd, "226 Transfer complete")
                case "SITE":
                    // Expect: SITE CHMOD <octal> <path>
                    let siteParts = arg.split(separator: " ", maxSplits: 2).map(String.init)
                    if siteParts.count == 3,
                       siteParts[0].uppercased() == "CHMOD",
                       let mode = UInt32(siteParts[1], radix: 8) {
                        let p = normPath(siteParts[2])
                        if var existing = files[p] {
                            existing.mode = mode
                            files[p] = existing
                        }
                        _ = Self.sendLine(ctrlFd, "200 SITE CHMOD command successful")
                    } else {
                        _ = Self.sendLine(ctrlFd, "500 Unknown SITE command")
                    }
                case "RNFR":
                    renameSource = normPath(arg)
                    _ = Self.sendLine(ctrlFd, "350 Ready for RNTO")
                case "RNTO":
                    let dst = normPath(arg)
                    if let src = renameSource, let val = files.removeValue(forKey: src) {
                        files[dst] = val
                        renameSource = nil
                        _ = Self.sendLine(ctrlFd, "250 Rename successful")
                    } else {
                        _ = Self.sendLine(ctrlFd, "550 Rename failed")
                    }
                case "DELE":
                    let target = normPath(arg)
                    if files.removeValue(forKey: target) != nil {
                        _ = Self.sendLine(ctrlFd, "250 Deleted")
                    } else {
                        _ = Self.sendLine(ctrlFd, "550 Not a regular file")
                    }
                case "RMD", "XRMD":
                    let target = normPath(arg)
                    dirs.remove(target)
                    _ = Self.sendLine(ctrlFd, "250 Directory removed")
                case "QUIT":
                    _ = Self.sendLine(ctrlFd, "221 Goodbye")
                    return
                default:
                    _ = Self.sendLine(ctrlFd, "502 Command not implemented")
                }
            }
        }

        let ftp = try NativeFtpClient.connect(
            host: "127.0.0.1",
            port: listener.port,
            username: "ops",
            password: "secret"
        )
        XCTAssertEqual(try ftp.pwd(), "/")

        try ftp.createDirectory("/releases")
        let payload = Data("NexusTS v0.8.0 Wire-Verified FTP Payload\n".utf8)
        try ftp.uploadBytes(payload, to: "/artifact.txt")
        try ftp.chmod("/artifact.txt", modeOctal: 0o750)

        let entries = try ftp.listDirectory("/")
        XCTAssertTrue(entries.contains(where: { $0.name == "releases" && $0.isDirectory }))
        let fileEntry = try XCTUnwrap(entries.first(where: { $0.name == "artifact.txt" }))
        XCTAssertEqual(fileEntry.sizeBytes, UInt64(payload.count))
        XCTAssertEqual(fileEntry.unixMode, 0o750)

        let downloaded = try ftp.downloadBytes("/artifact.txt")
        XCTAssertEqual(downloaded, payload)

        try ftp.renameEntry(from: "/artifact.txt", to: "/artifact-renamed.txt")
        let downloadedRenamed = try ftp.downloadBytes("/artifact-renamed.txt")
        XCTAssertEqual(downloadedRenamed, payload)

        try ftp.removeEntry("/artifact-renamed.txt", isDirectory: false)
        let afterDelete = try ftp.listDirectory("/")
        XCTAssertFalse(afterDelete.contains(where: { $0.name == "artifact-renamed.txt" }))

        ftp.disconnect()
        wait(for: [serverFinished], timeout: 5.0)
    }

    // MARK: - 6. Live Loopback RFB 3.8 VNC Server Exercising NativeLiveVncSession

    func testLiveLoopbackRfbVncSessionFrameAndInputEvents() throws {
        let listener = try Self.makeLoopbackListener()
        defer { listener.close() }

        final class RfbEventRecorder: @unchecked Sendable {
            let lock = NSLock()
            var pointerEvents: [(mask: UInt8, x: UInt16, y: UInt16)] = []
            var keyEvents: [(down: Bool, keysym: UInt32)] = []
            var cutTexts: [String] = []
        }
        let recorder = RfbEventRecorder()
        let serverDone = expectation(description: "Loopback RFB 3.8 server completed")

        DispatchQueue.global(qos: .userInitiated).async {
            let fd = Self.acceptSocket(listener.fd, timeoutSeconds: 5)
            defer {
                if fd >= 0 { Darwin.close(fd) }
                serverDone.fulfill()
            }
            guard fd >= 0 else { return }

            // 1. ProtocolVersion handshake
            guard Self.sendAll(fd, Array("RFB 003.008\n".utf8)),
                  Self.recvExact(fd, count: 12) != nil else { return }

            // 2. Security Type 1 (None) + SecurityResult OK (0)
            guard Self.sendAll(fd, [1, 1]),
                  let chosenSec = Self.recvExact(fd, count: 1),
                  chosenSec == [1],
                  Self.sendAll(fd, [0, 0, 0, 0]) else { return }

            // 3. ClientInit (1 byte) -> ServerInit (4x4, 32bpp BGRX, name "NexusLoopbackVNC")
            guard Self.recvExact(fd, count: 1) != nil else { return }
            let nameBytes = Array("NexusLoopbackVNC".utf8)
            var serverInit: [UInt8] = [
                0, 4, // width = 4
                0, 4, // height = 4
                32, 24, 0, 1, // bpp=32, depth=24, little-endian, true-color
                0, 255, 0, 255, 0, 255, // max R, G, B = 255
                16, 8, 0, // red-shift=16, green-shift=8, blue-shift=0 -> [B, G, R, X]
                0, 0, 0, // padding
                0, 0, 0, UInt8(nameBytes.count)
            ]
            serverInit.append(contentsOf: nameBytes)
            guard Self.sendAll(fd, serverInit) else { return }

            var sentInitialFrame = false
            while let msgTypeBuf = Self.recvExact(fd, count: 1) {
                switch msgTypeBuf[0] {
                case 0: // SetPixelFormat (3 pad + 16 pixfmt = 19 bytes)
                    guard Self.recvExact(fd, count: 19) != nil else { return }
                case 2: // SetEncodings (1 pad + u16 count + count*4 bytes)
                    guard let hdr = Self.recvExact(fd, count: 3) else { return }
                    let count = (Int(hdr[1]) << 8) | Int(hdr[2])
                    guard Self.recvExact(fd, count: count * 4) != nil else { return }
                case 3: // FramebufferUpdateRequest (incremental + x + y + w + h = 9 bytes)
                    guard let req = Self.recvExact(fd, count: 9) else { return }
                    if req[0] == 0 || !sentInitialFrame {
                        sentInitialFrame = true
                        var update: [UInt8] = [
                            0, 0, 0, 1, // FramebufferUpdate, 1 rect
                            0, 0, 0, 0, // x=0, y=0
                            0, 4, 0, 4, // w=4, h=4
                            0, 0, 0, 0  // Encoding 0 (Raw)
                        ]
                        for _ in 0..<(4 * 4) {
                            update.append(contentsOf: [0x40, 0x20, 0xFF, 0x00]) // B=0x40, G=0x20, R=0xFF
                        }
                        _ = Self.sendAll(fd, update)

                        // Also push ServerCutText ("VNC_CLIP!")
                        let clip = Array("VNC_CLIP!".utf8)
                        var cutMsg: [UInt8] = [3, 0, 0, 0, 0, 0, 0, UInt8(clip.count)]
                        cutMsg.append(contentsOf: clip)
                        _ = Self.sendAll(fd, cutMsg)
                    }
                case 4: // KeyEvent (down + 2 pad + u32 keysym = 7 bytes)
                    guard let body = Self.recvExact(fd, count: 7) else { return }
                    let down = body[0] != 0
                    let sym = (UInt32(body[3]) << 24)
                        | (UInt32(body[4]) << 16)
                        | (UInt32(body[5]) << 8)
                        | UInt32(body[6])
                    recorder.lock.lock()
                    recorder.keyEvents.append((down, sym))
                    recorder.lock.unlock()
                case 5: // PointerEvent (mask + u16 x + u16 y = 5 bytes)
                    guard let body = Self.recvExact(fd, count: 5) else { return }
                    let mask = body[0]
                    let x = (UInt16(body[1]) << 8) | UInt16(body[2])
                    let y = (UInt16(body[3]) << 8) | UInt16(body[4])
                    recorder.lock.lock()
                    recorder.pointerEvents.append((mask, x, y))
                    recorder.lock.unlock()
                case 6: // ClientCutText (3 pad + u32 len = 7 bytes + payload)
                    guard let body = Self.recvExact(fd, count: 7) else { return }
                    let len = (Int(body[3]) << 24)
                        | (Int(body[4]) << 16)
                        | (Int(body[5]) << 8)
                        | Int(body[6])
                    guard let txtBytes = Self.recvExact(fd, count: len) else { return }
                    recorder.lock.lock()
                    recorder.cutTexts.append(String(decoding: txtBytes, as: UTF8.self))
                    recorder.lock.unlock()
                default:
                    return
                }
            }
        }

        let vnc = try NativeLiveVncSession.connect(
            host: "127.0.0.1",
            port: listener.port
        )
        XCTAssertEqual(vnc.desktopName(), "NexusLoopbackVNC")
        XCTAssertEqual(vnc.dimensions()?.width, 4)
        XCTAssertEqual(vnc.dimensions()?.height, 4)

        let frameDeadline = Date().addingTimeInterval(3.0)
        while Date() < frameDeadline && vnc.frameSequence == 0 {
            Thread.sleep(forTimeInterval: 0.02)
        }
        XCTAssertGreaterThan(vnc.frameSequence, 0)

        let bgraFrame = try XCTUnwrap(vnc.copyFrameBgra())
        XCTAssertEqual(bgraFrame.width, 4)
        XCTAssertEqual(bgraFrame.height, 4)
        XCTAssertEqual(Array(bgraFrame.bgra.prefix(4)), [0x40, 0x20, 0xFF, 0xFF])

        let rendered = try XCTUnwrap(vnc.copyFrameIntoPersistentBuffer(forceFull: true))
        XCTAssertEqual(rendered.image.width, 4)
        XCTAssertEqual(rendered.image.height, 4)

        // Wait briefly for ServerCutText delivery if still in flight
        var pulledClip: String? = vnc.pullClipboard()
        let clipDeadline = Date().addingTimeInterval(1.5)
        while pulledClip == nil && Date() < clipDeadline {
            Thread.sleep(forTimeInterval: 0.02)
            pulledClip = vnc.pullClipboard()
        }
        XCTAssertEqual(pulledClip, "VNC_CLIP!")

        XCTAssertTrue(vnc.sendPointer(buttonMask: 0x01, x: 2, y: 3))
        XCTAssertTrue(vnc.sendKey(down: true, keysym: 0xFF0D))
        XCTAssertTrue(vnc.sendClipboard("MAC_CLIP_OUT"))

        let deliveryDeadline = Date().addingTimeInterval(2.0)
        while Date() < deliveryDeadline {
            recorder.lock.lock()
            let done = !recorder.pointerEvents.isEmpty && !recorder.keyEvents.isEmpty && !recorder.cutTexts.isEmpty
            recorder.lock.unlock()
            if done { break }
            Thread.sleep(forTimeInterval: 0.02)
        }

        vnc.disconnect()
        wait(for: [serverDone], timeout: 5.0)

        recorder.lock.lock()
        let ptrs = recorder.pointerEvents
        let keys = recorder.keyEvents
        let clips = recorder.cutTexts
        recorder.lock.unlock()

        XCTAssertTrue(ptrs.contains(where: { $0.mask == 0x01 && $0.x == 2 && $0.y == 3 }))
        XCTAssertTrue(keys.contains(where: { $0.down && $0.keysym == 0xFF0D }))
        XCTAssertEqual(clips.first, "MAC_CLIP_OUT")
    }

    // MARK: - 7. Concurrent Port Scanner Across >256 Port Span + TCP Ping

    func testConcurrentPortScannerDiscoversEndpointsAcross300PortSpan() throws {
        var chosenBase: UInt16 = 0
        var listenerLow: LoopbackListener? = nil
        var listenerHigh: LoopbackListener? = nil

        for candidateInt in stride(from: 38_000, through: 46_000, by: 400) {
            let candidateBase = UInt16(candidateInt)
            guard let lo = try? Self.makeLoopbackListener(preferredPort: candidateBase + 5) else {
                continue
            }
            guard let hi = try? Self.makeLoopbackListener(preferredPort: candidateBase + 280) else {
                lo.close()
                continue
            }
            chosenBase = candidateBase
            listenerLow = lo
            listenerHigh = hi
            break
        }

        let lo = try XCTUnwrap(listenerLow)
        let hi = try XCTUnwrap(listenerHigh)
        defer {
            lo.close()
            hi.close()
        }

        // Accept probe sockets in the background so loopback handshakes succeed cleanly
        for srvFd in [lo.fd, hi.fd] {
            DispatchQueue.global(qos: .utility).async {
                while true {
                    let c = Darwin.accept(srvFd, nil, nil)
                    if c < 0 { break }
                    Darwin.close(c)
                }
            }
        }

        let hits = NativeNetworkDiagnostics.scanPorts(
            host: "127.0.0.1",
            startPort: chosenBase,
            endPort: chosenBase + 299,
            timeoutMs: 300,
            concurrency: 64
        )
        let openPorts = Set(hits.filter(\.open).map(\.port))
        XCTAssertTrue(openPorts.contains(lo.port), "Expected low port \(lo.port) to be discovered")
        XCTAssertTrue(openPorts.contains(hi.port), "Expected high port \(hi.port) (>256 span) to be discovered")

        let pingMs = NativeNetworkDiagnostics.tcpPingMs(host: "127.0.0.1", port: lo.port, timeoutMs: 500)
        XCTAssertGreaterThanOrEqual(pingMs, 0)
    }

    // MARK: - 8. Depth-Aware AWS EC2 & Hyper-V WinRM XML Inventory Parsers

    func testEc2AndHyperVXmlParsersPreserveFieldsAcrossNestedElements() {
        let ec2Xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <DescribeInstancesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
          <reservationSet>
            <item>
              <reservationId>r-0123456789abcdef0</reservationId>
              <instancesSet>
                <item>
                  <instanceId>i-0abc1234def567890</instanceId>
                  <instanceState>
                    <code>16</code>
                    <name>running</name>
                  </instanceState>
                  <instanceType>c7g.xlarge</instanceType>
                  <privateIpAddress>10.42.1.19</privateIpAddress>
                  <groupSet>
                    <item>
                      <groupId>sg-00112233</groupId>
                      <groupName>prod-edge-sg</groupName>
                    </item>
                  </groupSet>
                  <tagSet>
                    <item>
                      <key>Environment</key>
                      <value>production</value>
                    </item>
                    <item>
                      <key>Name</key>
                      <value>web-edge-01</value>
                    </item>
                  </tagSet>
                </item>
              </instancesSet>
            </item>
          </reservationSet>
        </DescribeInstancesResponse>
        """

        let ec2Items = CloudHypervisorClient.parseEc2InstancesXml(ec2Xml)
        XCTAssertEqual(ec2Items.count, 1)
        XCTAssertEqual(ec2Items.first?.id, "i-0abc1234def567890")
        XCTAssertEqual(ec2Items.first?.name, "web-edge-01")
        XCTAssertEqual(ec2Items.first?.powerState, "RUNNING")
        XCTAssertEqual(ec2Items.first?.cpuSummary, "c7g.xlarge")
        XCTAssertEqual(ec2Items.first?.addressOrNode, "10.42.1.19")
        XCTAssertEqual(ec2Items.first?.consoleProtocol, .SSH)

        let hyperVXml = """
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                    xmlns:n="http://schemas.xmlsoap.org/ws/2004/09/enumeration"
                    xmlns:p="http://schemas.microsoft.com/wbem/wsman/1/wmi/root/virtualization/v2/Msvm_ComputerSystem">
          <s:Body>
            <n:EnumerateResponse>
              <n:Items>
                <p:Msvm_ComputerSystem>
                  <p:Name>HV-HOST-01</p:Name>
                  <p:ElementName>HV-HOST-01</p:ElementName>
                  <p:Caption>Hosting Computer System</p:Caption>
                  <p:EnabledState>2</p:EnabledState>
                </p:Msvm_ComputerSystem>
                <p:Msvm_ComputerSystem>
                  <p:Name>8D3A21E0-1111-4B22-9999-ACDE48001122</p:Name>
                  <p:ElementName>win2022-dc01</p:ElementName>
                  <p:Caption>Virtual Machine</p:Caption>
                  <p:EnabledState>2</p:EnabledState>
                </p:Msvm_ComputerSystem>
              </n:Items>
            </n:EnumerateResponse>
          </s:Body>
        </s:Envelope>
        """

        let hvItems = CloudHypervisorClient.parseHyperVWsmanXml(hyperVXml, hostLabel: "hv01.corp.local")
        XCTAssertEqual(hvItems.count, 1)
        XCTAssertEqual(hvItems.first?.id, "8D3A21E0-1111-4B22-9999-ACDE48001122")
        XCTAssertEqual(hvItems.first?.name, "win2022-dc01")
        XCTAssertEqual(hvItems.first?.powerState, "RUNNING")
        XCTAssertEqual(hvItems.first?.addressOrNode, "hv01.corp.local")
        XCTAssertEqual(hvItems.first?.consoleProtocol, .RDP)
    }

    // MARK: - POSIX HTTP/1.1 Loopback Helpers for Cloud & Virtualization Wire Tests

    private struct CapturedHttpRequest: Sendable {
        let method: String
        let path: String
        let headers: [String: String]
        let body: String
    }

    private static func readHttpRequest(_ fd: Int32) -> CapturedHttpRequest? {
        var data = Data()
        var headerEndIndex: Int? = nil
        var chunk = [UInt8](repeating: 0, count: 2048)

        while headerEndIndex == nil {
            let n = Darwin.recv(fd, &chunk, chunk.count, 0)
            if n > 0 {
                data.append(contentsOf: chunk.prefix(n))
                if let range = data.range(of: Data([0x0D, 0x0A, 0x0D, 0x0A])) {
                    headerEndIndex = range.upperBound
                }
            } else if n < 0 && errno == EINTR {
                continue
            } else {
                return nil
            }
        }

        guard let bodyStart = headerEndIndex else { return nil }
        let headerText = String(decoding: data[..<(bodyStart - 4)], as: UTF8.self)
        let lines = headerText.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else { return nil }
        let reqParts = requestLine.split(separator: " ")
        guard reqParts.count >= 2 else { return nil }
        let method = String(reqParts[0])
        let path = String(reqParts[1])

        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            if let colon = line.firstIndex(of: ":") {
                let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
                let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                headers[key] = value
            }
        }

        let contentLength = Int(headers["content-length"] ?? "0") ?? 0
        while data.count - bodyStart < contentLength {
            let n = Darwin.recv(fd, &chunk, chunk.count, 0)
            if n > 0 {
                data.append(contentsOf: chunk.prefix(n))
            } else if n < 0 && errno == EINTR {
                continue
            } else {
                break
            }
        }

        let bodyEnd = min(data.count, bodyStart + contentLength)
        let body = String(decoding: data[bodyStart..<bodyEnd], as: UTF8.self)
        return CapturedHttpRequest(method: method, path: path, headers: headers, body: body)
    }

    private static func sendHttpResponse(
        _ fd: Int32,
        status: Int,
        statusText: String = "OK",
        contentType: String = "application/json; charset=utf-8",
        extraHeaders: [String: String] = [:],
        body: String
    ) {
        let bodyBytes = Array(body.utf8)
        var head = "HTTP/1.1 \(status) \(statusText)\r\n"
        head += "Content-Type: \(contentType)\r\n"
        head += "Content-Length: \(bodyBytes.count)\r\n"
        head += "Connection: close\r\n"
        for (k, v) in extraHeaders {
            head += "\(k): \(v)\r\n"
        }
        head += "\r\n"
        _ = sendAll(fd, Array(head.utf8))
        _ = sendAll(fd, bodyBytes)
    }

    // MARK: - 9. Standalone ESXi VIM SOAP (`/sdk`) Fallback & PowerOnVM_Task

    func testEsxiStandaloneVimSoapSdkFallbackAndPowerOnTask() async throws {
        let listener = try Self.makeLoopbackListener()
        defer { listener.close() }

        final class SoapRecorder: @unchecked Sendable {
            let lock = NSLock()
            var requests: [CapturedHttpRequest] = []
        }
        let recorder = SoapRecorder()
        let serverDone = expectation(description: "ESXi VIM SOAP loopback server finished")

        DispatchQueue.global(qos: .userInitiated).async {
            var shouldContinue = true
            while shouldContinue {
                let fd = Self.acceptSocket(listener.fd, timeoutSeconds: 5)
                if fd < 0 { break }
                guard let req = Self.readHttpRequest(fd) else {
                    Darwin.close(fd)
                    continue
                }
                recorder.lock.lock()
                recorder.requests.append(req)
                recorder.lock.unlock()

                if req.path == "/api/session" || req.path == "/rest/com/vmware/cis/session" {
                    Self.sendHttpResponse(fd, status: 404, statusText: "Not Found", body: "{}")
                } else if req.path == "/sdk" {
                    if req.body.contains("RetrieveServiceContent") {
                        let xml = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                          <soapenv:Body>
                            <RetrieveServiceContentResponse xmlns="urn:vim25">
                              <returnval>
                                <rootFolder type="Folder">ha-folder-root</rootFolder>
                                <propertyCollector type="PropertyCollector">ha-property-collector</propertyCollector>
                                <viewManager type="ViewManager">ha-view-mgr</viewManager>
                                <sessionManager type="SessionManager">ha-sessionmgr</sessionManager>
                              </returnval>
                            </RetrieveServiceContentResponse>
                          </soapenv:Body>
                        </soapenv:Envelope>
                        """
                        Self.sendHttpResponse(
                            fd,
                            status: 200,
                            contentType: "text/xml; charset=utf-8",
                            extraHeaders: ["Set-Cookie": "vmware_soap_session=\"esxi-mac-cookie\"; Path=/; HttpOnly"],
                            body: xml
                        )
                    } else if req.body.contains("<urn:Login>") {
                        let xml = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                          <soapenv:Body><LoginResponse xmlns="urn:vim25"><returnval><key>s1</key></returnval></LoginResponse></soapenv:Body>
                        </soapenv:Envelope>
                        """
                        Self.sendHttpResponse(fd, status: 200, contentType: "text/xml; charset=utf-8", body: xml)
                    } else if req.body.contains("CreateContainerView") {
                        let xml = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                          <soapenv:Body><CreateContainerViewResponse xmlns="urn:vim25"><returnval type="ContainerView">session[view-mac]</returnval></CreateContainerViewResponse></soapenv:Body>
                        </soapenv:Envelope>
                        """
                        Self.sendHttpResponse(fd, status: 200, contentType: "text/xml; charset=utf-8", body: xml)
                    } else if req.body.contains("RetrievePropertiesEx") {
                        let xml = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                          <soapenv:Body>
                            <RetrievePropertiesExResponse xmlns="urn:vim25">
                              <returnval>
                                <objects>
                                  <obj type="VirtualMachine">vm-42</obj>
                                  <propSet><name>name</name><val>prod-esxi-win2022</val></propSet>
                                  <propSet><name>runtime.powerState</name><val>poweredOn</val></propSet>
                                  <propSet><name>config.hardware.numCPU</name><val>8</val></propSet>
                                  <propSet><name>config.hardware.memoryMB</name><val>16384</val></propSet>
                                  <propSet><name>guest.ipAddress</name><val>10.99.4.25</val></propSet>
                                  <propSet><name>guest.guestFullName</name><val>Microsoft Windows Server 2022 (64-bit)</val></propSet>
                                </objects>
                              </returnval>
                            </RetrievePropertiesExResponse>
                          </soapenv:Body>
                        </soapenv:Envelope>
                        """
                        Self.sendHttpResponse(fd, status: 200, contentType: "text/xml; charset=utf-8", body: xml)
                    } else if req.body.contains("PowerOnVM_Task") {
                        let xml = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                          <soapenv:Body><PowerOnVM_TaskResponse xmlns="urn:vim25"><returnval type="Task">haTask-42</returnval></PowerOnVM_TaskResponse></soapenv:Body>
                        </soapenv:Envelope>
                        """
                        Self.sendHttpResponse(fd, status: 200, contentType: "text/xml; charset=utf-8", body: xml)
                        shouldContinue = false
                    }
                } else {
                    Self.sendHttpResponse(fd, status: 404, statusText: "Not Found", body: "{}")
                }
                Darwin.close(fd)
            }
            serverDone.fulfill()
        }

        let conn = VaultConnection(
            name: "Standalone ESXi",
            host: "127.0.0.1",
            protocolType: .VMWARE_VSPHERE,
            settings: .cloudVirtualization(CloudVirtualizationProtocolSettings(
                apiEndpointUrl: "http://127.0.0.1:\(listener.port)",
                verifyTls: false
            ))
        )
        let cred = VaultCredential(name: "ESXi Root", username: "root", secretPassword: "esxi-secret-password")

        let vms = try await CloudHypervisorClient.fetchInventory(connection: conn, credential: cred)
        XCTAssertEqual(vms.count, 1)
        XCTAssertEqual(vms.first?.id, "vm-42")
        XCTAssertEqual(vms.first?.name, "prod-esxi-win2022")
        XCTAssertEqual(vms.first?.powerState, "POWERED_ON")
        XCTAssertEqual(vms.first?.addressOrNode, "10.99.4.25")
        XCTAssertEqual(vms.first?.cpuSummary, "8 vCPU")
        XCTAssertEqual(vms.first?.memorySummary, "16384 MiB")
        XCTAssertEqual(vms.first?.consoleProtocol, .RDP)

        let msg = try await CloudHypervisorClient.performCloudPowerAction(
            connection: conn,
            credential: cred,
            vmId: "vm-42",
            vmName: "prod-esxi-win2022",
            action: .start
        )
        XCTAssertTrue(msg.contains("PowerOnVM_Task"))
        await fulfillment(of: [serverDone], timeout: 5.0)

        recorder.lock.lock()
        let captured = recorder.requests
        recorder.lock.unlock()

        XCTAssertTrue(captured.contains(where: { $0.path == "/sdk" && $0.body.contains("RetrievePropertiesEx") && ($0.headers["cookie"] ?? "").contains("esxi-mac-cookie") }))
        XCTAssertTrue(captured.contains(where: { $0.path == "/sdk" && $0.body.contains("<urn:PowerOnVM_Task><urn:_this type=\"VirtualMachine\">vm-42</urn:_this></urn:PowerOnVM_Task>") }))
    }

    // MARK: - 10. Proxmox VE API Token (`PVEAPIToken=`), Port 8006 & Container Workload Endpoints

    func testProxmoxApiTokenPort8006NormalizationAndContainerWorkloadEndpoints() async throws {
        XCTAssertEqual(
            CloudHypervisorClient.resolveProxmoxBaseUrl(apiEndpointUrl: "", connectionHost: "pve01.corp.local"),
            "https://pve01.corp.local:8006"
        )
        XCTAssertEqual(
            CloudHypervisorClient.resolveProxmoxBaseUrl(apiEndpointUrl: "https://pve02.corp.local", connectionHost: ""),
            "https://pve02.corp.local:8006"
        )
        XCTAssertEqual(
            ContainerWorkloadClient.resolveDockerHttpBaseUrl(endpoint: "tcp://10.0.0.50:2376", remoteHost: "", verifyTls: false),
            "https://10.0.0.50:2376"
        )
        XCTAssertEqual(
            ContainerWorkloadClient.resolveDockerHttpBaseUrl(endpoint: "tcp://10.0.0.50:2375", remoteHost: "", verifyTls: false),
            "http://10.0.0.50:2375"
        )
        let k8sCmd = ContainerWorkloadClient.buildSafeExecCommand(
            protocolType: .KUBERNETES_POD,
            settings: ContainerExecProtocolSettings(
                clusterOrSocketEndpoint: "https://10.0.0.10:6443",
                namespace: "prod",
                podOrContainerName: "api-pod-0",
                subContainerName: "envoy-sidecar",
                shellCommand: "/bin/sh",
                verifyTls: false
            )
        )
        XCTAssertTrue(k8sCmd.contains("--insecure-skip-tls-verify=true"))
        XCTAssertTrue(k8sCmd.contains("-c 'envoy-sidecar'"))

        let listener = try Self.makeLoopbackListener()
        defer { listener.close() }

        final class PveRecorder: @unchecked Sendable {
            let lock = NSLock()
            var requests: [CapturedHttpRequest] = []
        }
        let recorder = PveRecorder()
        let serverDone = expectation(description: "Proxmox loopback server completed")

        DispatchQueue.global(qos: .userInitiated).async {
            for idx in 0..<2 {
                let fd = Self.acceptSocket(listener.fd, timeoutSeconds: 5)
                guard fd >= 0 else { break }
                if let req = Self.readHttpRequest(fd) {
                    recorder.lock.lock()
                    recorder.requests.append(req)
                    recorder.lock.unlock()

                    if idx == 0 {
                        let json = """
                        {"data":[{"vmid":104,"name":"edge-lxc","status":"running","node":"pve01","type":"lxc","maxcpu":4,"maxmem":4294967296}]}
                        """
                        Self.sendHttpResponse(fd, status: 200, body: json)
                    } else {
                        Self.sendHttpResponse(fd, status: 200, body: "{\"data\":\"UPID:pve01:00001\"}")
                    }
                }
                Darwin.close(fd)
            }
            serverDone.fulfill()
        }

        let pveConn = VaultConnection(
            name: "Proxmox Cluster",
            host: "127.0.0.1",
            protocolType: .PROXMOX_VE,
            settings: .cloudVirtualization(CloudVirtualizationProtocolSettings(
                apiEndpointUrl: "http://127.0.0.1:\(listener.port)",
                realmOrDomain: "pam",
                verifyTls: false
            ))
        )
        let apiTokenCred = VaultCredential(
            name: "PVE Token",
            username: "root!nexus",
            secretPassword: "11111111-2222-3333-4444-555555555555"
        )

        let vms = try await CloudHypervisorClient.fetchInventory(connection: pveConn, credential: apiTokenCred)
        XCTAssertEqual(vms.count, 1)
        XCTAssertEqual(vms.first?.id, "pve01/lxc/104")
        XCTAssertEqual(vms.first?.name, "edge-lxc")

        _ = try await CloudHypervisorClient.performCloudPowerAction(
            connection: pveConn,
            credential: apiTokenCred,
            vmId: "pve01/lxc/104",
            vmName: "edge-lxc",
            action: .save
        )
        await fulfillment(of: [serverDone], timeout: 5.0)

        recorder.lock.lock()
        let reqs = recorder.requests
        recorder.lock.unlock()

        XCTAssertEqual(reqs.count, 2)
        XCTAssertEqual(reqs[0].path, "/api2/json/cluster/resources?type=vm")
        XCTAssertEqual(reqs[0].headers["authorization"], "PVEAPIToken=root@pam!nexus=11111111-2222-3333-4444-555555555555")
        XCTAssertEqual(reqs[1].path, "/api2/json/nodes/pve01/lxc/104/status/shutdown")
    }

    // MARK: - 11. AWS EC2 SigV4 Loopback + STS Token + `<platformDetails>Windows</platformDetails>` + XML Faults

    func testAwsEc2SigV4StsSessionTokenWindowsRdpAndXmlFaultHandling() async throws {
        let listener = try Self.makeLoopbackListener()
        defer { listener.close() }

        final class Ec2Recorder: @unchecked Sendable {
            let lock = NSLock()
            var requests: [CapturedHttpRequest] = []
        }
        let recorder = Ec2Recorder()
        let serverDone = expectation(description: "EC2 SigV4 loopback server completed")

        DispatchQueue.global(qos: .userInitiated).async {
            for idx in 0..<2 {
                let fd = Self.acceptSocket(listener.fd, timeoutSeconds: 5)
                guard fd >= 0 else { break }
                if let req = Self.readHttpRequest(fd) {
                    recorder.lock.lock()
                    recorder.requests.append(req)
                    recorder.lock.unlock()

                    if idx == 0 {
                        let xml = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <DescribeInstancesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
                          <reservationSet>
                            <item>
                              <instancesSet>
                                <item>
                                  <instanceId>i-09988776655443322</instanceId>
                                  <instanceState><code>16</code><name>running</name></instanceState>
                                  <instanceType>m6i.xlarge</instanceType>
                                  <ipAddress>54.210.12.34</ipAddress>
                                  <platformDetails>Windows with SQL Server Enterprise</platformDetails>
                                  <tagSet><item><key>Name</key><value>win-sql-primary</value></item></tagSet>
                                </item>
                              </instancesSet>
                            </item>
                          </reservationSet>
                        </DescribeInstancesResponse>
                        """
                        Self.sendHttpResponse(fd, status: 200, contentType: "text/xml; charset=utf-8", body: xml)
                    } else {
                        let fault = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <Response><Errors><Error><Code>AuthFailure</Code><Message>AWS was not able to validate the provided access credentials</Message></Error></Errors></Response>
                        """
                        Self.sendHttpResponse(fd, status: 401, statusText: "Unauthorized", contentType: "text/xml; charset=utf-8", body: fault)
                    }
                }
                Darwin.close(fd)
            }
            serverDone.fulfill()
        }

        let stsToken = "FwoGZXIvYXdzEBYaDH99887766554433221100AABBCCDDEEFFGGHHIIJJKKLLMMNNOOPPQQRRSSTTUUVV"
        let ec2Conn = VaultConnection(
            name: "AWS EU Central",
            host: "eu-central-1",
            protocolType: .AWS_EC2,
            settings: .cloudVirtualization(CloudVirtualizationProtocolSettings(
                apiEndpointUrl: "http://127.0.0.1:\(listener.port)",
                regionOrCluster: "eu-central-1",
                subscriptionOrAccountId: stsToken,
                verifyTls: false
            ))
        )
        let ec2Cred = VaultCredential(
            name: "STS Role",
            username: "ASIAEXAMPLEKEY123456",
            secretPassword: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )

        let instances = try await CloudHypervisorClient.fetchInventory(connection: ec2Conn, credential: ec2Cred)
        XCTAssertEqual(instances.count, 1)
        XCTAssertEqual(instances.first?.id, "i-09988776655443322")
        XCTAssertEqual(instances.first?.name, "win-sql-primary")
        XCTAssertEqual(instances.first?.addressOrNode, "54.210.12.34")
        XCTAssertEqual(instances.first?.consoleProtocol, .RDP)

        do {
            _ = try await CloudHypervisorClient.fetchInventory(connection: ec2Conn, credential: ec2Cred)
            XCTFail("Expected AuthFailure error on HTTP 401")
        } catch {
            XCTAssertTrue("\(error)".contains("AuthFailure"))
        }

        await fulfillment(of: [serverDone], timeout: 5.0)

        recorder.lock.lock()
        let firstReq = recorder.requests.first
        recorder.lock.unlock()

        let authHdr = firstReq?.headers["authorization"] ?? ""
        XCTAssertTrue(authHdr.hasPrefix("AWS4-HMAC-SHA256 Credential=ASIAEXAMPLEKEY123456/"))
        XCTAssertTrue(authHdr.contains("/eu-central-1/ec2/aws4_request"))
        XCTAssertTrue(authHdr.contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token"))
        XCTAssertEqual(firstReq?.headers["x-amz-security-token"], stsToken)
        XCTAssertEqual(firstReq?.body, "Action=DescribeInstances&Version=2016-11-15")
    }

    // MARK: - 12. Azure ARM Loopback (`Bearer ` Deduplication, `statusOnly=true` & NIC Public IP Resolution)

    func testAzureArmBearerDeduplicationStatusOnlyAndNicPublicIpResolution() async throws {
        let listener = try Self.makeLoopbackListener()
        defer { listener.close() }

        final class AzureRecorder: @unchecked Sendable {
            let lock = NSLock()
            var requests: [CapturedHttpRequest] = []
        }
        let recorder = AzureRecorder()
        let serverDone = expectation(description: "Azure ARM loopback server completed")

        DispatchQueue.global(qos: .userInitiated).async {
            for _ in 0..<3 {
                let fd = Self.acceptSocket(listener.fd, timeoutSeconds: 5)
                guard fd >= 0 else { break }
                if let req = Self.readHttpRequest(fd) {
                    recorder.lock.lock()
                    recorder.requests.append(req)
                    recorder.lock.unlock()

                    if req.path.contains("/providers/Microsoft.Compute/virtualMachines") {
                        let json = """
                        {
                          "value": [{
                            "id": "/subscriptions/sub-123/resourceGroups/rg-prod/providers/Microsoft.Compute/virtualMachines/az-win-01",
                            "name": "az-win-01",
                            "location": "westeurope",
                            "properties": {
                              "hardwareProfile": { "vmSize": "Standard_D4s_v5" },
                              "storageProfile": { "osDisk": { "osType": "Windows" } },
                              "networkProfile": {
                                "networkInterfaces": [{
                                  "id": "/subscriptions/sub-123/resourceGroups/rg-prod/providers/Microsoft.Network/networkInterfaces/nic-01"
                                }]
                              },
                              "instanceView": {
                                "statuses": [{ "code": "PowerState/running", "displayStatus": "VM running" }]
                              }
                            }
                          }]
                        }
                        """
                        Self.sendHttpResponse(fd, status: 200, body: json)
                    } else if req.path.contains("/networkInterfaces/nic-01") {
                        let json = """
                        {
                          "properties": {
                            "ipConfigurations": [{
                              "properties": {
                                "privateIPAddress": "10.20.0.4",
                                "publicIPAddress": {
                                  "id": "/subscriptions/sub-123/resourceGroups/rg-prod/providers/Microsoft.Network/publicIPAddresses/pip-01"
                                }
                              }
                            }]
                          }
                        }
                        """
                        Self.sendHttpResponse(fd, status: 200, body: json)
                    } else if req.path.contains("/publicIPAddresses/pip-01") {
                        let json = """
                        { "properties": { "ipAddress": "20.50.100.7" } }
                        """
                        Self.sendHttpResponse(fd, status: 200, body: json)
                    }
                }
                Darwin.close(fd)
            }
            serverDone.fulfill()
        }

        let rawJwt = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJhdWQiOiJodHRwczovL21hbmFnZW1lbnQuYXp1cmUuY29tIn0.sig"
        let azConn = VaultConnection(
            name: "Azure Prod Subscription",
            host: "sub-123",
            protocolType: .AZURE_VM,
            settings: .cloudVirtualization(CloudVirtualizationProtocolSettings(
                apiEndpointUrl: "http://127.0.0.1:\(listener.port)",
                regionOrCluster: "",
                subscriptionOrAccountId: "sub-123",
                verifyTls: false
            ))
        )
        let azCred = VaultCredential(
            name: "Prefixed Bearer Token",
            secretPassword: "Bearer \(rawJwt)"
        )

        let vms = try await CloudHypervisorClient.fetchInventory(connection: azConn, credential: azCred)
        await fulfillment(of: [serverDone], timeout: 5.0)

        XCTAssertEqual(vms.count, 1)
        XCTAssertEqual(vms.first?.name, "az-win-01")
        XCTAssertEqual(vms.first?.powerState, "RUNNING")
        XCTAssertEqual(vms.first?.addressOrNode, "20.50.100.7")
        XCTAssertEqual(vms.first?.cpuSummary, "Standard_D4s_v5")
        XCTAssertEqual(vms.first?.consoleProtocol, .RDP)

        recorder.lock.lock()
        let reqs = recorder.requests
        recorder.lock.unlock()

        XCTAssertEqual(reqs.count, 3)
        XCTAssertTrue(reqs[0].path.contains("statusOnly=true"))
        XCTAssertEqual(reqs[0].headers["authorization"], "Bearer \(rawJwt)")
    }

    // MARK: - 13. RFB 3.8 Security Negotiation: Standard VNC (Type 2) vs Apple Screen Sharing ARD (Type 30)

    func testRfbSecuritySelectionPrefersVncAuthWhenStandardVncAndArdWhenAppleScreenSharing() throws {
        let listener = try Self.makeLoopbackListener()
        defer { listener.close() }

        final class SecChoiceRecorder: @unchecked Sendable {
            let lock = NSLock()
            var chosenTypes: [UInt8] = []
        }
        let recorder = SecChoiceRecorder()
        let serverDone = expectation(description: "Dual-security RFB server completed")

        DispatchQueue.global(qos: .userInitiated).async {
            for _ in 0..<2 {
                let fd = Self.acceptSocket(listener.fd, timeoutSeconds: 5)
                guard fd >= 0 else { break }

                if Self.sendAll(fd, Array("RFB 003.008\n".utf8)),
                   Self.recvExact(fd, count: 12) != nil,
                   Self.sendAll(fd, [2, 30, 2]), // Offer [ARD=30, VNCAuth=2]
                   let chosen = Self.recvExact(fd, count: 1)?.first {
                    recorder.lock.lock()
                    recorder.chosenTypes.append(chosen)
                    recorder.lock.unlock()

                    if chosen == 2 {
                        // Complete DES 16-byte challenge/response + SecurityResult OK + ServerInit
                        let challenge = [UInt8](repeating: 0xAA, count: 16)
                        if Self.sendAll(fd, challenge),
                           Self.recvExact(fd, count: 16) != nil,
                           Self.sendAll(fd, [0, 0, 0, 0]),
                           Self.recvExact(fd, count: 1) != nil {
                            let serverInit: [UInt8] = [
                                0, 2, 0, 2,
                                32, 24, 0, 1,
                                0, 255, 0, 255, 0, 255,
                                16, 8, 0,
                                0, 0, 0,
                                0, 0, 0, 3,
                                0x56, 0x4E, 0x43
                            ]
                            _ = Self.sendAll(fd, serverInit)
                            _ = Self.recvExact(fd, count: 1)
                        }
                    }
                }
                Darwin.close(fd)
            }
            serverDone.fulfill()
        }

        // 1. Standard VNC passes empty username -> must pick Security Type 2 (DES VNC Auth), NOT ARD 30
        let standardVnc = try NativeLiveVncSession.connect(
            host: "127.0.0.1",
            port: listener.port,
            username: "",
            password: "vncpassword"
        )
        standardVnc.disconnect()

        // 2. Apple Screen Sharing passes username -> must pick Security Type 30 (Apple ARD DH)
        _ = try? NativeLiveVncSession.connect(
            host: "127.0.0.1",
            port: listener.port,
            username: "macadmin",
            password: "ardpassword"
        )

        wait(for: [serverDone], timeout: 5.0)
        recorder.lock.lock()
        let chosen = recorder.chosenTypes
        recorder.lock.unlock()
        XCTAssertEqual(chosen, [2, 30])
    }

    // MARK: - 14. Lossless 23-Protocol Royal TS `.rtsx` XML & `.rtsz` ZIP Archive Round-Trip

    func testAll23VaultProtocolsRoundTripLosslesslyThroughRtsxAndRtsz() throws {
        XCTAssertEqual(VaultProtocolType.allCases.count, 23)

        var rdpHv = RdpProtocolSettings(
            port: 2179,
            hyperVVmId: "8d3a21e0-1111-4b22-9999-acde48001122",
            hyperVEnhancedSession: true
        )
        rdpHv.ignoreCertificateWarnings = true

        let connections: [VaultConnection] = [
            VaultConnection(name: "01-RDP-VMConnect", host: "hv01.corp.local", protocolType: .RDP, settings: .rdp(rdpHv)),
            VaultConnection(name: "02-WTS", host: "rds01.corp.local", protocolType: .WINDOWS_TERMINAL_SERVICES, settings: .rdp(RdpProtocolSettings(port: 3390))),
            VaultConnection(name: "03-SSH", host: "bastion.corp.local", protocolType: .SSH, settings: .ssh(SshProtocolSettings(port: 2222))),
            VaultConnection(name: "04-Telnet", host: "core-sw01.corp.local", protocolType: .TELNET, settings: .ssh(SshProtocolSettings(port: 2323))),
            VaultConnection(name: "05-Serial", host: "/dev/cu.usbserial-FTDI", protocolType: .SERIAL, settings: .serialUart(SerialUartProtocolSettings(baudRate: 57600, devicePathHint: "/dev/cu.usbserial-FTDI"))),
            VaultConnection(name: "06-SFTP", host: "sftp.corp.local", protocolType: .SFTP, settings: .fileTransfer(FileTransferProtocolSettings(port: 2022, initialRemoteDirectory: "/var/log"))),
            VaultConnection(name: "07-FTP-Active", host: "ftp.corp.local", protocolType: .FTP, settings: .fileTransfer(FileTransferProtocolSettings(port: 2121, passiveMode: false))),
            VaultConnection(name: "08-FTPS-Explicit", host: "ftps.corp.local", protocolType: .FTPS, settings: .fileTransfer(FileTransferProtocolSettings(port: 21, ftpsImplicitTls: false, passiveMode: true, verifyTls: true))),
            VaultConnection(name: "09-SCP", host: "scp.corp.local", protocolType: .SCP, settings: .fileTransfer(FileTransferProtocolSettings(port: 2200, preserveTimestamps: false))),
            VaultConnection(name: "10-VNC", host: "kvm01.corp.local", protocolType: .VNC, settings: .vnc(VncProtocolSettings(port: 5901, viewOnly: true, appleArdDiffieHellmanAuth: false))),
            VaultConnection(name: "11-ARD", host: "macmini.corp.local", protocolType: .APPLE_SCREEN_SHARING, settings: .vnc(VncProtocolSettings(port: 5900, appleArdDiffieHellmanAuth: true))),
            VaultConnection(name: "12-Web", host: "https://idrac.corp.local:8443/login", protocolType: .WEB_PAGE, settings: .webPage(WebPageProtocolSettings(url: "https://idrac.corp.local:8443/login", ignoreSelfSignedInternalCert: false))),
            VaultConnection(name: "13-vSphere", host: "esxi01.corp.local", protocolType: .VMWARE_VSPHERE, settings: .cloudVirtualization(CloudVirtualizationProtocolSettings(apiEndpointUrl: "https://esxi01.corp.local", verifyTls: true))),
            VaultConnection(name: "14-Proxmox", host: "pve01.corp.local", protocolType: .PROXMOX_VE, settings: .cloudVirtualization(CloudVirtualizationProtocolSettings(apiEndpointUrl: "https://pve01.corp.local:8006", realmOrDomain: "pve", verifyTls: false))),
            VaultConnection(name: "15-HyperV", host: "hv01.corp.local", protocolType: .HYPER_V, settings: .cloudVirtualization(CloudVirtualizationProtocolSettings(apiEndpointUrl: "", realmOrDomain: "CORP", verifyTls: false))),
            VaultConnection(name: "16-AWS-EC2", host: "eu-west-1", protocolType: .AWS_EC2, settings: .cloudVirtualization(CloudVirtualizationProtocolSettings(apiEndpointUrl: "", regionOrCluster: "eu-west-1", subscriptionOrAccountId: "123456789012", verifyTls: true))),
            VaultConnection(name: "17-Azure-VM", host: "00000000-1111-2222-3333-444444444444", protocolType: .AZURE_VM, settings: .cloudVirtualization(CloudVirtualizationProtocolSettings(apiEndpointUrl: "https://management.azure.com", regionOrCluster: "rg-prod", subscriptionOrAccountId: "00000000-1111-2222-3333-444444444444", realmOrDomain: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", verifyTls: true))),
            VaultConnection(name: "18-Ping", host: "1.1.1.1", protocolType: .UTILITY_PING, settings: .netUtility(NetUtilityProtocolSettings(pingCount: 25))),
            VaultConnection(name: "19-Traceroute", host: "8.8.8.8", protocolType: .UTILITY_TRACEROUTE, settings: .netUtility(NetUtilityProtocolSettings(tracerouteMaxHops: 42))),
            VaultConnection(name: "20-PortScan", host: "10.0.0.1", protocolType: .UTILITY_PORT_SCANNER, settings: .netUtility(NetUtilityProtocolSettings(portScanRangeStart: 20, portScanRangeEnd: 443, portScanConcurrency: 64))),
            VaultConnection(name: "21-WoL", host: "192.168.10.255", protocolType: .UTILITY_WAKE_ON_LAN, settings: .netUtility(NetUtilityProtocolSettings(wolMacAddress: "AA:BB:CC:DD:EE:FF", wolBroadcastAddress: "192.168.10.255", wolUdpPort: 7))),
            VaultConnection(name: "22-K8s-Pod", host: "k8s.corp.local", protocolType: .KUBERNETES_POD, settings: .containerExec(ContainerExecProtocolSettings(clusterOrSocketEndpoint: "https://k8s.corp.local:6443", namespace: "observability", podOrContainerName: "otel-collector-0", subContainerName: "envoy-proxy", verifyTls: true))),
            VaultConnection(name: "23-Docker", host: "docker01.corp.local", protocolType: .DOCKER_CONTAINER, settings: .containerExec(ContainerExecProtocolSettings(clusterOrSocketEndpoint: "tcp://docker01.corp.local:2376", podOrContainerName: "traefik-edge", verifyTls: true)))
        ]

        XCTAssertEqual(Set(connections.map(\.protocolType)), Set(VaultProtocolType.allCases))

        let sourceDoc = NxtsVaultDocument(
            name: "All 23 Protocols Verification Vault",
            connections: connections
        )

        for (format, fileName) in [(VaultExportFormat.rtsx, "all23.rtsx"), (VaultExportFormat.rtsz, "all23.rtsz")] {
            let exported = try VaultDocumentExporter.export(document: sourceDoc, format: format)
            let summary = try EnterpriseVaultFormatImporter.importData(exported, fileName: fileName)
            let imported = summary.document

            XCTAssertEqual(imported.connections.count, 23, "Mismatch in \(fileName)")
            XCTAssertEqual(Set(imported.connections.map(\.protocolType)), Set(VaultProtocolType.allCases), "Missing protocol in \(fileName)")

            let byProto = Dictionary(uniqueKeysWithValues: imported.connections.map { ($0.protocolType, $0) })

            if case .rdp(let rdp) = byProto[.RDP]?.settings {
                XCTAssertEqual(rdp.effectiveRdpPort, 2179)
                XCTAssertEqual(rdp.hyperVVmId, "8d3a21e0-1111-4b22-9999-acde48001122")
                XCTAssertTrue(rdp.hyperVEnhancedSession)
            } else { XCTFail("Expected RDP settings in \(fileName)") }

            if case .fileTransfer(let ftp) = byProto[.FTP]?.settings {
                XCTAssertEqual(ftp.port, 2121)
                XCTAssertFalse(ftp.passiveMode)
            } else { XCTFail("Expected FTP settings in \(fileName)") }

            if case .fileTransfer(let ftps) = byProto[.FTPS]?.settings {
                XCTAssertEqual(ftps.port, 21)
                XCTAssertFalse(ftps.ftpsImplicitTls)
                XCTAssertTrue(ftps.verifyTls)
            } else { XCTFail("Expected FTPS settings in \(fileName)") }

            if case .fileTransfer(let scp) = byProto[.SCP]?.settings {
                XCTAssertEqual(scp.port, 2200)
                XCTAssertFalse(scp.preserveTimestamps)
            } else { XCTFail("Expected SCP settings in \(fileName)") }

            if case .vnc(let vnc) = byProto[.VNC]?.settings,
               case .vnc(let ard) = byProto[.APPLE_SCREEN_SHARING]?.settings {
                XCTAssertFalse(vnc.appleArdDiffieHellmanAuth)
                XCTAssertTrue(vnc.viewOnly)
                XCTAssertTrue(ard.appleArdDiffieHellmanAuth)
            } else { XCTFail("Expected VNC & ARD settings in \(fileName)") }

            if case .webPage(let wp) = byProto[.WEB_PAGE]?.settings {
                XCTAssertEqual(wp.url, "https://idrac.corp.local:8443/login")
                XCTAssertFalse(wp.ignoreSelfSignedInternalCert)
            } else { XCTFail("Expected WebPage settings in \(fileName)") }

            if case .cloudVirtualization(let hv) = byProto[.HYPER_V]?.settings,
               case .cloudVirtualization(let ec2) = byProto[.AWS_EC2]?.settings {
                XCTAssertEqual(hv.apiEndpointUrl, "")
                XCTAssertEqual(hv.realmOrDomain, "CORP")
                XCTAssertEqual(ec2.apiEndpointUrl, "")
                XCTAssertEqual(ec2.regionOrCluster, "eu-west-1")
                XCTAssertTrue(ec2.verifyTls)
            } else { XCTFail("Expected Hyper-V & EC2 settings in \(fileName)") }

            if case .containerExec(let k8s) = byProto[.KUBERNETES_POD]?.settings,
               case .containerExec(let docker) = byProto[.DOCKER_CONTAINER]?.settings {
                XCTAssertEqual(k8s.subContainerName, "envoy-proxy")
                XCTAssertTrue(k8s.verifyTls)
                XCTAssertEqual(docker.clusterOrSocketEndpoint, "tcp://docker01.corp.local:2376")
                XCTAssertTrue(docker.verifyTls)
            } else { XCTFail("Expected K8s & Docker settings in \(fileName)") }
        }
    }
}
