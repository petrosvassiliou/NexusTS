import AppKit
import CoreGraphics
import NexusTSCore
import QuartzCore
import SwiftUI

public struct VncScreenSharingView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let tab: WorkspaceSessionTab

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    public var body: some View {
        VncLiveWorkspaceBody(
            tab: tab,
            controller: store.vncController(for: tab)
        )
    }
}

private struct VncLiveWorkspaceBody: View {
    @EnvironmentObject private var store: WorkspaceStore
    let tab: WorkspaceSessionTab
    @ObservedObject var controller: LiveVncController

    @State private var scaleToFit: Bool = true
    @State private var hasPresentedFrame: Bool = false
    @State private var sendCommandShortcuts: Bool = false

    private var latestConnection: VaultConnection {
        store.activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
    }

    private var vncSettings: VncProtocolSettings {
        if case .vnc(let s) = latestConnection.settings { return s }
        return VncProtocolSettings(
            appleArdDiffieHellmanAuth: latestConnection.protocolType == .APPLE_SCREEN_SHARING
        )
    }

    private var isAppleScreenSharing: Bool {
        latestConnection.protocolType == .APPLE_SCREEN_SHARING || vncSettings.appleArdDiffieHellmanAuth
    }

    var body: some View {
        let effectivePort = vncSettings.port > 0 ? vncSettings.port : 5900
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Circle()
                    .fill(
                        controller.isConnected
                            ? Color(nsColor: .systemGreen)
                            : (controller.isConnecting ? Color(nsColor: .systemOrange) : Color(nsColor: .systemRed))
                    )
                    .frame(width: 8, height: 8)

                ProtocolSquircleIcon(protocolType: latestConnection.protocolType, size: 22)

                Text(verbatim: "\(latestConnection.name) (\(latestConnection.host):\(String(effectivePort)))")
                    .font(.callout.weight(.semibold))
                    .lineLimit(1)

                if isAppleScreenSharing {
                    EnvironmentCapsuleBadge("ARD DH Auth")
                }

                if vncSettings.viewOnly {
                    EnvironmentCapsuleBadge("View-Only")
                }

                Text(
                    controller.desktopName.isEmpty
                        ? controller.dimensionsText
                        : "\(controller.desktopName) • \(controller.dimensionsText)"
                )
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .lineLimit(1)

                Spacer()

                Toggle("Send ⌘", isOn: $sendCommandShortcuts)
                    .toggleStyle(.checkbox)
                    .font(.caption)
                    .help("Forward macOS Command (⌘) shortcuts to the remote Screen Sharing / VNC session")

                Toggle("Fit", isOn: $scaleToFit)
                    .toggleStyle(.checkbox)
                    .font(.caption)

                Button {
                    hasPresentedFrame = false
                    store.reconnectVncController(for: tab)
                } label: {
                    Label(controller.isConnected ? "Reconnect" : "Connect RFB", systemImage: "arrow.clockwise")
                }
                .controlSize(.small)
                .disabled(controller.isConnecting)

                Button {
                    launchNativeAppleScreenSharingApp()
                } label: {
                    Label("Open in macOS Screen Sharing.app", systemImage: "rectangle.on.rectangle.angled")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .help("Hand off directly to macOS built-in Screen Sharing.app with ARD / Kerberos / Apple ID support")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.bar)

            Divider()

            ZStack {
                Color(red: 0.08, green: 0.09, blue: 0.12)

                if let liveSession = controller.session {
                    VncInteractiveSurfaceRepresentable(
                        session: liveSession,
                        scaleToFit: scaleToFit,
                        viewOnly: vncSettings.viewOnly,
                        isAppleScreenSharing: isAppleScreenSharing,
                        captureRemoteShortcuts: sendCommandShortcuts || store.remoteKeyCaptureEnabled,
                        onRuntimeUpdate: { connected, hasFrame, dimsText, errorText in
                            if hasPresentedFrame != hasFrame {
                                hasPresentedFrame = hasFrame
                            }
                            controller.updateRuntimeStatus(
                                isConnected: connected,
                                dimensionsText: dimsText,
                                errorText: errorText
                            )
                        }
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .opacity(hasPresentedFrame ? 1.0 : 0.0)
                }

                if !hasPresentedFrame && !controller.isConnected {
                    VStack(spacing: 14) {
                        Image(systemName: latestConnection.protocolType.sfSymbolName)
                            .font(.system(size: 44, weight: .medium))
                            .foregroundStyle(Color(nsColor: .systemPurple))

                        Text(
                            isAppleScreenSharing
                                ? "Apple Remote Desktop (ARD) & Screen Sharing"
                                : "RFB 3.8 Live VNC Framebuffer Engine"
                        )
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.white)

                        if controller.isConnecting {
                            HStack(spacing: 8) {
                                ProgressView()
                                    .controlSize(.small)
                                Text("Negotiating RFB 3.8 security & desktop surface…")
                                    .font(.caption)
                                    .foregroundStyle(.white.opacity(0.75))
                            }
                        } else if let err = controller.lastErrorText, !err.isEmpty {
                            Text(err)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(Color(nsColor: .systemOrange))
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: 520)
                        } else {
                            Text("Encodings: \(vncSettings.preferredEncodings.joined(separator: " • "))")
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.65))
                        }

                        HStack(spacing: 12) {
                            Button("Connect Embedded RFB") {
                                hasPresentedFrame = false
                                store.reconnectVncController(for: tab)
                            }
                            .controlSize(.large)
                            .disabled(controller.isConnecting)

                            Button("Launch macOS Screen Sharing.app") {
                                launchNativeAppleScreenSharingApp()
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.large)
                        }
                    }
                    .padding(32)
                }
            }
        }
        .onAppear {
            sendCommandShortcuts = isAppleScreenSharing || store.remoteKeyCaptureEnabled
        }
    }

    private func launchNativeAppleScreenSharingApp() {
        let resolved = PropertyInheritanceResolver.resolve(connection: latestConnection, in: store.document)
        let user = tab.adHocUsernameOverride ?? resolved.effectiveCredential?.username ?? ""
        let pass = tab.adHocPasswordOverride ?? resolved.effectiveCredential?.secretPassword ?? ""
        var comps = URLComponents()
        comps.scheme = "vnc"
        if !user.isEmpty {
            comps.user = user
            if !pass.isEmpty {
                comps.password = pass
            }
        } else if !pass.isEmpty {
            comps.password = pass
        }
        comps.host = latestConnection.host.trimmingCharacters(in: CharacterSet(charactersIn: "[] "))
        if vncSettings.port > 0 && vncSettings.port != 5900 {
            comps.port = vncSettings.port
        }
        if let url = comps.url {
            NSWorkspace.shared.open(url)
            store.appendAudit("Screen Sharing", "Opened \(latestConnection.host):\(vncSettings.port) via native macOS Screen Sharing.app.")
        }
    }
}

private struct VncInteractiveSurfaceRepresentable: NSViewRepresentable {
    let session: NativeLiveVncSession
    let scaleToFit: Bool
    let viewOnly: Bool
    let isAppleScreenSharing: Bool
    let captureRemoteShortcuts: Bool
    let onRuntimeUpdate: (Bool, Bool, String?, String?) -> Void

    func makeNSView(context: Context) -> VncPointerAndKeyNSView {
        let view = VncPointerAndKeyNSView()
        view.onRuntimeUpdate = onRuntimeUpdate
        view.configure(
            session: session,
            scaleToFit: scaleToFit,
            viewOnly: viewOnly,
            isAppleScreenSharing: isAppleScreenSharing,
            captureRemoteShortcuts: captureRemoteShortcuts
        )
        return view
    }

    func updateNSView(_ nsView: VncPointerAndKeyNSView, context: Context) {
        nsView.onRuntimeUpdate = onRuntimeUpdate
        nsView.configure(
            session: session,
            scaleToFit: scaleToFit,
            viewOnly: viewOnly,
            isAppleScreenSharing: isAppleScreenSharing,
            captureRemoteShortcuts: captureRemoteShortcuts
        )
    }
}

final class VncPointerAndKeyNSView: NSView {
    var session: NativeLiveVncSession?
    var scaleToFit: Bool = true
    var viewOnly: Bool = false
    var isAppleScreenSharing: Bool = false
    var captureRemoteShortcuts: Bool = false
    var onRuntimeUpdate: ((Bool, Bool, String?, String?) -> Void)?

    private var remoteWidth: Int = 1280
    private var remoteHeight: Int = 800
    private var displayTimer: Timer?
    private var trackingAreaRef: NSTrackingArea?
    private var tickCounter: UInt64 = 0
    private var hasPresentedFrame: Bool = false

    private var buttonMask: UInt8 = 0
    private var activeKeysymsByKeyCode: [UInt16: UInt32] = [:]
    private var heldModifierKeyCodes: Set<UInt16> = []

    private var lastReportedConnected: Bool?
    private var lastReportedHasFrame: Bool?
    private var lastReportedDims: String = ""
    private var lastReportedError: String = ""
    private var lastPasteboardChangeCount: Int = NSPasteboard.general.changeCount
    private var lastSyncedClipboardText: String = ""

    override var acceptsFirstResponder: Bool { true }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        setupLayer()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupLayer()
    }

    deinit {
        displayTimer?.invalidate()
    }

    private func setupLayer() {
        wantsLayer = true
        layer?.backgroundColor = NSColor(calibratedRed: 0.08, green: 0.09, blue: 0.12, alpha: 1.0).cgColor
        layer?.magnificationFilter = .trilinear
        layer?.minificationFilter = .trilinear
        layer?.contentsGravity = .resizeAspect
        layer?.actions = [
            "contents": NSNull(),
            "bounds": NSNull(),
            "contentsScale": NSNull(),
            "contentsGravity": NSNull()
        ]
    }

    func configure(
        session: NativeLiveVncSession,
        scaleToFit: Bool,
        viewOnly: Bool,
        isAppleScreenSharing: Bool,
        captureRemoteShortcuts: Bool
    ) {
        let changed = self.session !== session
        self.session = session
        self.viewOnly = viewOnly
        self.isAppleScreenSharing = isAppleScreenSharing
        self.captureRemoteShortcuts = captureRemoteShortcuts

        if self.scaleToFit != scaleToFit {
            self.scaleToFit = scaleToFit
            layer?.contentsGravity = scaleToFit ? .resizeAspect : .resizeAspectFill
        }

        if changed {
            releaseAllHeldKeys()
            hasPresentedFrame = false
            buttonMask = 0
            layer?.contents = nil
            lastPasteboardChangeCount = NSPasteboard.general.changeCount
            pollTick(forceSlow: true)
        }
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if let win = window {
            layer?.contentsScale = win.backingScaleFactor
            startTimerIfNeeded()
            pollTick(forceSlow: true)
        } else {
            stopTimer()
        }
    }

    override func viewWillMove(toWindow newWindow: NSWindow?) {
        if newWindow == nil {
            releaseAllHeldKeys()
            stopTimer()
        }
        super.viewWillMove(toWindow: newWindow)
    }

    override func updateTrackingAreas() {
        if let existing = trackingAreaRef {
            removeTrackingArea(existing)
        }
        let area = NSTrackingArea(
            rect: .zero,
            options: [.activeInKeyWindow, .inVisibleRect, .mouseMoved],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(area)
        trackingAreaRef = area
        super.updateTrackingAreas()
    }

    override func resignFirstResponder() -> Bool {
        releaseAllHeldKeys()
        return super.resignFirstResponder()
    }

    private func startTimerIfNeeded() {
        guard displayTimer == nil else { return }
        let t = Timer(timeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in
            self?.pollTick(forceSlow: false)
        }
        RunLoop.main.add(t, forMode: .common)
        displayTimer = t
    }

    private func stopTimer() {
        displayTimer?.invalidate()
        displayTimer = nil
    }

    private func pollTick(forceSlow: Bool) {
        guard let session else { return }
        tickCounter &+= 1

        if let updated = session.copyFrameIntoPersistentBuffer(forceFull: !hasPresentedFrame) {
            layer?.contents = updated.image
            hasPresentedFrame = true
            remoteWidth = updated.width
            remoteHeight = updated.height
            lastReportedDims = "\(updated.width)×\(updated.height)"
        }

        let connected = session.isConnected
        let runSlow = forceSlow || (connected != lastReportedConnected) || (tickCounter % 30 == 0)
        var errText = lastReportedError

        if runSlow {
            errText = session.lastErrorMessage()
            if connected && !viewOnly {
                syncClipboard(session: session)
            }
        }

        if connected != lastReportedConnected
            || hasPresentedFrame != lastReportedHasFrame
            || errText != lastReportedError {
            lastReportedConnected = connected
            lastReportedHasFrame = hasPresentedFrame
            lastReportedError = errText
            onRuntimeUpdate?(
                connected,
                hasPresentedFrame,
                lastReportedDims.isEmpty ? nil : lastReportedDims,
                errText.isEmpty ? nil : errText
            )
        }
    }

    private func syncClipboard(session: NativeLiveVncSession) {
        let pbCount = NSPasteboard.general.changeCount
        if pbCount != lastPasteboardChangeCount {
            lastPasteboardChangeCount = pbCount
            if let localStr = NSPasteboard.general.string(forType: .string),
               !localStr.isEmpty,
               localStr != lastSyncedClipboardText {
                lastSyncedClipboardText = localStr
                _ = session.sendClipboard(localStr)
            }
        }
        if let remoteStr = session.pullClipboard(),
           !remoteStr.isEmpty,
           remoteStr != lastSyncedClipboardText {
            lastSyncedClipboardText = remoteStr
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(remoteStr, forType: .string)
            lastPasteboardChangeCount = NSPasteboard.general.changeCount
        }
    }

    // MARK: - Pointer & Wheel Events (RFB Button Mask)

    private func scaledPoint(for event: NSEvent) -> (Int, Int) {
        let local = convert(event.locationInWindow, from: nil)
        return MacRdpScancodeMapper.mapViewportPoint(
            localX: local.x,
            localY: local.y,
            viewWidth: bounds.width,
            viewHeight: bounds.height,
            remoteWidth: remoteWidth,
            remoteHeight: remoteHeight,
            scaleToFit: scaleToFit,
            originBottomLeft: true
        )
    }

    private func dispatchPointer(for event: NSEvent) {
        guard !viewOnly, let session else { return }
        let (x, y) = scaledPoint(for: event)
        _ = session.sendPointer(buttonMask: buttonMask, x: x, y: y)
    }

    override func mouseMoved(with event: NSEvent) {
        dispatchPointer(for: event)
    }

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        buttonMask |= 0x01
        dispatchPointer(for: event)
    }

    override func mouseUp(with event: NSEvent) {
        buttonMask &= ~0x01
        dispatchPointer(for: event)
    }

    override func mouseDragged(with event: NSEvent) {
        dispatchPointer(for: event)
    }

    override func rightMouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        buttonMask |= 0x04
        dispatchPointer(for: event)
    }

    override func rightMouseUp(with event: NSEvent) {
        buttonMask &= ~0x04
        dispatchPointer(for: event)
    }

    override func rightMouseDragged(with event: NSEvent) {
        dispatchPointer(for: event)
    }

    override func otherMouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        buttonMask |= 0x02
        dispatchPointer(for: event)
    }

    override func otherMouseUp(with event: NSEvent) {
        buttonMask &= ~0x02
        dispatchPointer(for: event)
    }

    override func otherMouseDragged(with event: NSEvent) {
        dispatchPointer(for: event)
    }

    override func scrollWheel(with event: NSEvent) {
        guard !viewOnly, let session else { return }
        let (x, y) = scaledPoint(for: event)
        let dy = event.scrollingDeltaY
        if abs(dy) > 0.01 {
            let wheelBit: UInt8 = dy > 0 ? 0x08 : 0x10
            _ = session.sendPointer(buttonMask: buttonMask | wheelBit, x: x, y: y)
            _ = session.sendPointer(buttonMask: buttonMask, x: x, y: y)
        }
    }

    // MARK: - X11 Keysym Translation

    private static func specialKeysym(for keyCode: UInt16) -> UInt32? {
        switch keyCode {
        case 36, 76: return 0xFF0D // Return / Enter
        case 51:     return 0xFF08 // BackSpace
        case 117:    return 0xFFFF // Delete
        case 114:    return 0xFF63 // Insert
        case 48:     return 0xFF09 // Tab
        case 53:     return 0xFF1B // Escape
        case 115:    return 0xFF50 // Home
        case 123:    return 0xFF51 // Left
        case 126:    return 0xFF52 // Up
        case 124:    return 0xFF53 // Right
        case 125:    return 0xFF54 // Down
        case 116:    return 0xFF55 // Page_Up
        case 121:    return 0xFF56 // Page_Down
        case 119:    return 0xFF57 // End
        case 122:    return 0xFFBE // F1
        case 120:    return 0xFFBF // F2
        case 99:     return 0xFFC0 // F3
        case 118:    return 0xFFC1 // F4
        case 96:     return 0xFFC2 // F5
        case 97:     return 0xFFC3 // F6
        case 98:     return 0xFFC4 // F7
        case 100:    return 0xFFC5 // F8
        case 101:    return 0xFFC6 // F9
        case 109:    return 0xFFC7 // F10
        case 103:    return 0xFFC8 // F11
        case 111:    return 0xFFC9 // F12
        default:     return nil
        }
    }

    private func modifierKeysym(for keyCode: UInt16) -> UInt32? {
        switch keyCode {
        case 0x38: return 0xFFE1 // Shift_L
        case 0x3C: return 0xFFE2 // Shift_R
        case 0x3B: return 0xFFE3 // Control_L
        case 0x3E: return 0xFFE4 // Control_R
        case 0x3A: return 0xFFE9 // Alt_L (Option)
        case 0x3D: return 0xFFEA // Alt_R (Option)
        case 0x37:
            guard captureRemoteShortcuts else { return nil }
            return isAppleScreenSharing ? 0xFFE7 : 0xFFEB // Meta_L (macOS ARD Command) vs Super_L
        case 0x36:
            guard captureRemoteShortcuts else { return nil }
            return isAppleScreenSharing ? 0xFFE8 : 0xFFEC // Meta_R vs Super_R
        case 0x39: return 0xFFE5 // Caps_Lock
        default:   return nil
        }
    }

    private func resolveKeysym(for event: NSEvent) -> UInt32? {
        if let special = Self.specialKeysym(for: event.keyCode) {
            return special
        }
        let candidate: String? = {
            if event.modifierFlags.contains(.control)
                || event.modifierFlags.contains(.option)
                || event.modifierFlags.contains(.command) {
                return event.charactersIgnoringModifiers
            }
            return event.characters ?? event.charactersIgnoringModifiers
        }()
        guard let scalar = candidate?.unicodeScalars.first else { return nil }
        let v = scalar.value
        if v >= 0x20 && v <= 0xFF {
            return v
        } else if v > 0xFF {
            return 0x0100_0000 | v
        }
        return nil
    }

    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        if !viewOnly && captureRemoteShortcuts && window?.firstResponder === self && event.modifierFlags.contains(.command) {
            keyDown(with: event)
            return true
        }
        return super.performKeyEquivalent(with: event)
    }

    override func keyDown(with event: NSEvent) {
        guard !viewOnly else { return }
        if !captureRemoteShortcuts && event.modifierFlags.contains(.command) {
            super.keyDown(with: event)
            return
        }
        guard let keysym = resolveKeysym(for: event) else { return }
        activeKeysymsByKeyCode[event.keyCode] = keysym
        _ = session?.sendKey(down: true, keysym: keysym)
    }

    override func keyUp(with event: NSEvent) {
        guard !viewOnly else { return }
        if !captureRemoteShortcuts && event.modifierFlags.contains(.command) {
            super.keyUp(with: event)
            return
        }
        let keysym = activeKeysymsByKeyCode.removeValue(forKey: event.keyCode) ?? resolveKeysym(for: event)
        if let keysym {
            _ = session?.sendKey(down: false, keysym: keysym)
        }
    }

    override func flagsChanged(with event: NSEvent) {
        guard !viewOnly else { return }
        let code = event.keyCode
        guard let sym = modifierKeysym(for: code) else {
            super.flagsChanged(with: event)
            return
        }
        if code == 0x39 {
            _ = session?.sendKey(down: true, keysym: sym)
            _ = session?.sendKey(down: false, keysym: sym)
            return
        }
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let active: Bool
        switch code {
        case 0x38, 0x3C: active = flags.contains(.shift)
        case 0x3B, 0x3E: active = flags.contains(.control)
        case 0x3A, 0x3D: active = flags.contains(.option)
        case 0x37, 0x36: active = flags.contains(.command)
        default: active = false
        }
        if !active || heldModifierKeyCodes.contains(code) {
            heldModifierKeyCodes.remove(code)
            _ = session?.sendKey(down: false, keysym: sym)
        } else {
            heldModifierKeyCodes.insert(code)
            _ = session?.sendKey(down: true, keysym: sym)
        }
    }

    private func releaseAllHeldKeys() {
        if let session {
            for (_, sym) in activeKeysymsByKeyCode {
                _ = session.sendKey(down: false, keysym: sym)
            }
            for code in heldModifierKeyCodes {
                let sym: UInt32? = {
                    switch code {
                    case 0x38: return 0xFFE1
                    case 0x3C: return 0xFFE2
                    case 0x3B: return 0xFFE3
                    case 0x3E: return 0xFFE4
                    case 0x3A: return 0xFFE9
                    case 0x3D: return 0xFFEA
                    case 0x37: return isAppleScreenSharing ? 0xFFE7 : 0xFFEB
                    case 0x36: return isAppleScreenSharing ? 0xFFE8 : 0xFFEC
                    default:   return nil
                    }
                }()
                if let sym {
                    _ = session.sendKey(down: false, keysym: sym)
                }
            }
        }
        activeKeysymsByKeyCode.removeAll()
        heldModifierKeyCodes.removeAll()
        buttonMask = 0
    }
}
