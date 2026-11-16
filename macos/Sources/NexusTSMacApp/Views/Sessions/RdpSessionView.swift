import AppKit
import CoreGraphics
import NexusTSCore
import QuartzCore
import SwiftUI

public struct RdpSessionView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let tab: WorkspaceSessionTab

    @State private var isConnected: Bool = false
    @State private var isConnecting: Bool = true
    @State private var hasPresentedFrame: Bool = false
    @State private var lastNativeError: String = ""
    @State private var scaleToFit: Bool = true
    @State private var showHudBar: Bool = true
    @State private var frameDimensionsText: String = "1920×1080"
    @State private var sessionGeneration: Int = 0
    @State private var resizeDebounceTask: Task<Void, Never>? = nil

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    private var latestConnection: VaultConnection {
        store.activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
    }

    private var rdpSettings: RdpProtocolSettings {
        if case .rdp(let s) = latestConnection.settings { return s }
        return RdpProtocolSettings()
    }

    public var body: some View {
        let session = store.rdpController(for: tab)

        VStack(spacing: 0) {
            // External Remote Desktop Control & Telemetry Bar (strictly outside/above the RDP viewport)
            if showHudBar {
                HStack(spacing: 10) {
                    Circle()
                        .fill(
                            isConnected
                                ? Color(nsColor: .systemGreen)
                                : ((isConnecting || lastNativeError.isEmpty)
                                    ? Color(nsColor: .systemOrange)
                                    : Color(nsColor: .systemRed))
                        )
                        .frame(width: 8, height: 8)

                    Text(latestConnection.name)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)

                    Text(frameDimensionsText)
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)

                    if rdpSettings.connectToConsoleAdmin {
                        Text("/admin")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(Color.orange.opacity(0.22)))
                    }

                    if rdpSettings.redirectClipboard {
                        Label("Clipboard Sync", systemImage: "arrow.left.arrow.right.circle")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }

                    Spacer(minLength: 8)

                    Button("Ctrl+Alt+Del") {
                        session.sendCtrlAltDel()
                    }
                    .controlSize(.small)
                    .disabled(!isConnected)

                    Button {
                        let panel = NSOpenPanel()
                        panel.canChooseDirectories = true
                        panel.canChooseFiles = false
                        panel.allowsMultipleSelection = false
                        panel.prompt = "Share Folder via RDPDR"
                        if panel.runModal() == .OK, let folderURL = panel.url {
                            store.setRdpSharedMacFolder(for: latestConnection.id, folderURL: folderURL)
                        }
                    } label: {
                        Label(
                            rdpSettings.macSharedFolderPath != nil
                                ? "\\\\tsclient\\\(rdpSettings.macSharedDriveName)"
                                : "Share Folder…",
                            systemImage: "folder.badge.gearshape"
                        )
                        .font(.caption)
                    }
                    .controlSize(.small)

                    Toggle("Fit", isOn: $scaleToFit)
                        .toggleStyle(.checkbox)
                        .font(.caption)

                    Button {
                        if store.isFullScreenSessionMode {
                            store.exitFullScreenSession()
                        } else {
                            store.enterFullScreenSession(tabId: tab.id)
                        }
                    } label: {
                        Label(
                            store.isFullScreenSessionMode ? "Exit Full Screen" : "Full Screen",
                            systemImage: store.isFullScreenSessionMode
                                ? "arrow.down.right.and.arrow.up.left"
                                : "arrow.up.left.and.arrow.down.right"
                        )
                        .font(.caption)
                    }
                    .controlSize(.small)

                    Button {
                        showHudBar = false
                    } label: {
                        Image(systemName: "chevron.up")
                            .font(.caption2)
                    }
                    .buttonStyle(.plain)
                    .help("Collapse RDP Control Bar")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(.bar)
                Divider()
            } else {
                HStack {
                    Text("\(latestConnection.name) • \(frameDimensionsText)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button {
                        showHudBar = true
                    } label: {
                        Label("Show Controls", systemImage: "chevron.down")
                            .font(.caption2)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 3)
                .background(.bar)
                Divider()
            }

            GeometryReader { geo in
                ZStack {
                    Color(red: 0.06, green: 0.07, blue: 0.09)

                    // Direct 60 Hz AppKit CALayer surface — updates CALayer.contents in place without SwiftUI body churn
                    RdpInteractiveSurfaceNSViewRepresentable(
                        session: session,
                        generation: sessionGeneration,
                        scaleToFit: scaleToFit,
                        redirectClipboard: rdpSettings.redirectClipboard,
                        captureRemoteShortcuts: store.remoteKeyCaptureEnabled,
                        onTelemetryChange: { connected, connecting, hasFrame, dimsText, errorText in
                            if isConnected != connected { isConnected = connected }
                            if isConnecting != connecting { isConnecting = connecting }
                            if hasPresentedFrame != hasFrame { hasPresentedFrame = hasFrame }
                            if !dimsText.isEmpty && frameDimensionsText != dimsText {
                                frameDimensionsText = dimsText
                            }
                            if lastNativeError != errorText {
                                lastNativeError = errorText
                            }
                        }
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .opacity(hasPresentedFrame && (isConnected || lastNativeError.isEmpty) ? 1.0 : 0.0)

                    if !isConnected && !isConnecting && !lastNativeError.isEmpty {
                        let diag = NexusErrorClassifier.classifyRdpError(
                            host: latestConnection.host,
                            port: rdpSettings.port,
                            rawNativeError: lastNativeError,
                            connectionId: latestConnection.id
                        )
                        VStack(alignment: .leading, spacing: 14) {
                            HStack(spacing: 12) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .font(.system(size: 28))
                                    .foregroundStyle(Color(nsColor: .systemOrange))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(diag.title)
                                        .font(.headline)
                                        .foregroundStyle(.white)
                                    Text(diag.userMessage)
                                        .font(.subheadline)
                                        .foregroundStyle(.white.opacity(0.82))
                                }
                            }

                            if !diag.remediationHint.isEmpty {
                                Text("Suggested Fix: \(diag.remediationHint)")
                                    .font(.callout)
                                    .foregroundStyle(Color(nsColor: .systemYellow).opacity(0.95))
                            }

                            if !diag.technicalDetails.isEmpty {
                                Text(diag.technicalDetails)
                                    .font(.system(size: 11.5, design: .monospaced))
                                    .foregroundStyle(.white.opacity(0.72))
                                    .padding(10)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .background(
                                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                                            .fill(Color.black.opacity(0.35))
                                    )
                            }

                            HStack(spacing: 10) {
                                Button("Reconnect") {
                                    triggerReconnect()
                                }
                                .buttonStyle(.borderedProminent)

                                Button("Edit RDP Settings…") {
                                    store.editingConnection = latestConnection
                                }

                                Button(store.strings.copyDiagnostics) {
                                    NSPasteboard.general.clearContents()
                                    NSPasteboard.general.setString(diag.formatDiagnosticReport(), forType: .string)
                                }
                            }
                        }
                        .padding(24)
                        .frame(maxWidth: 560)
                        .background(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .fill(Color.white.opacity(0.06))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                                        .strokeBorder(Color.white.opacity(0.16), lineWidth: 1)
                                )
                        )
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .padding(28)
                    } else if !hasPresentedFrame {
                        VStack(spacing: 14) {
                            ProtocolSquircleIcon(protocolType: latestConnection.protocolType, size: 52)
                            Text("FreeRDP 3.x Native Engine • \(latestConnection.host):\(rdpSettings.port)")
                                .font(.headline)
                                .foregroundStyle(.white)

                            HStack(spacing: 8) {
                                if isConnecting || isConnected {
                                    ProgressView()
                                        .controlSize(.small)
                                }
                                Text(
                                    isConnected
                                        ? "Negotiating RDPGFX Desktop Surface…"
                                        : "Connecting with NLA / CredSSP + TLS 1.3…"
                                )
                                .font(.subheadline)
                                .foregroundStyle(.white.opacity(0.68))
                            }

                            HStack(spacing: 10) {
                                Button("Reconnect") {
                                    triggerReconnect()
                                }
                                .buttonStyle(.borderedProminent)
                                .disabled(isConnecting)

                                Button("Edit RDP Settings…") {
                                    store.editingConnection = latestConnection
                                }
                            }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .padding(28)
                    }
                }
                .onAppear {
                    isConnected = session.isConnected
                    isConnecting = session.isConnecting
                    lastNativeError = session.lastErrorMessage()
                }
                .onDisappear {
                    resizeDebounceTask?.cancel()
                    resizeDebounceTask = nil
                }
                .onChange(of: geo.size) { _, newSize in
                    resizeDebounceTask?.cancel()
                    guard rdpSettings.resizeBehavior == .DYNAMIC_DISP_DVC else { return }
                    let hiDpi = rdpSettings.hiDpiEnabled
                    resizeDebounceTask = Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 220_000_000)
                        guard !Task.isCancelled else { return }
                        let scale = NSScreen.main?.backingScaleFactor ?? 2.0
                        let pixelW = Int(newSize.width * (hiDpi ? scale : 1.0))
                        let pixelH = Int(newSize.height * (hiDpi ? scale : 1.0))
                        guard pixelW >= 320, pixelH >= 240 else { return }
                        _ = session.requestDynamicResize(
                            width: pixelW,
                            height: pixelH,
                            scalePercent: hiDpi ? 200 : 100
                        )
                    }
                }
            }
        }
    }

    private func triggerReconnect() {
        lastNativeError = ""
        hasPresentedFrame = false
        isConnecting = true
        sessionGeneration &+= 1
        _ = store.reconnectRdpSession(for: tab)
    }

    public static func makeCGImage(rgba: Data, width: Int, height: Int) -> CGImage? {
        NativeRdpSession.makeBgrxCGImage(bgrx: rgba, width: width, height: height)
    }
}

private struct RdpInteractiveSurfaceNSViewRepresentable: NSViewRepresentable {
    let session: NativeRdpSession
    let generation: Int
    let scaleToFit: Bool
    let redirectClipboard: Bool
    let captureRemoteShortcuts: Bool
    let onTelemetryChange: (Bool, Bool, Bool, String, String) -> Void

    func makeNSView(context: Context) -> RdpPointerAndKeyNSView {
        let view = RdpPointerAndKeyNSView()
        view.onTelemetryChange = onTelemetryChange
        view.configure(
            session: session,
            generation: generation,
            scaleToFit: scaleToFit,
            redirectClipboard: redirectClipboard,
            captureRemoteShortcuts: captureRemoteShortcuts
        )
        return view
    }

    func updateNSView(_ nsView: RdpPointerAndKeyNSView, context: Context) {
        nsView.onTelemetryChange = onTelemetryChange
        nsView.configure(
            session: session,
            generation: generation,
            scaleToFit: scaleToFit,
            redirectClipboard: redirectClipboard,
            captureRemoteShortcuts: captureRemoteShortcuts
        )
    }
}

final class RdpPointerAndKeyNSView: NSView {
    var session: NativeRdpSession?
    var remoteWidth: Int = 1920
    var remoteHeight: Int = 1080
    var scaleToFit: Bool = true
    var redirectClipboard: Bool = true
    var captureRemoteShortcuts: Bool = false
    var onTelemetryChange: ((Bool, Bool, Bool, String, String) -> Void)?

    private var activeGeneration: Int = -1
    private var displayTimer: Timer?
    private var trackingAreaRef: NSTrackingArea?
    private var tickCounter: UInt64 = 0
    private var hasPresentedFrame: Bool = false

    private var lastReportedConnected: Bool?
    private var lastReportedConnecting: Bool?
    private var lastReportedHasFrame: Bool?
    private var lastReportedDims: String = ""
    private var cachedNativeError: String = ""
    private var lastReportedError: String = ""

    private var lastPasteboardChangeCount: Int = NSPasteboard.general.changeCount
    private var lastRemoteClipboardSeq: UInt64 = 0
    private var lastSyncedClipboardText: String = ""

    private var heldModifierKeyCodes: Set<UInt16> = []

    override var acceptsFirstResponder: Bool { true }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        setupBackingLayer()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupBackingLayer()
    }

    deinit {
        displayTimer?.invalidate()
    }

    private func setupBackingLayer() {
        wantsLayer = true
        layer?.backgroundColor = NSColor(calibratedRed: 0.06, green: 0.07, blue: 0.09, alpha: 1.0).cgColor
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
        session: NativeRdpSession,
        generation: Int,
        scaleToFit: Bool,
        redirectClipboard: Bool,
        captureRemoteShortcuts: Bool
    ) {
        let sessionChanged = (self.session !== session) || (self.activeGeneration != generation)
        self.session = session
        self.activeGeneration = generation
        self.redirectClipboard = redirectClipboard
        self.captureRemoteShortcuts = captureRemoteShortcuts

        if self.scaleToFit != scaleToFit {
            self.scaleToFit = scaleToFit
            layer?.contentsGravity = scaleToFit ? .resizeAspect : .resizeAspectFill
        } else if layer?.contentsGravity == nil {
            layer?.contentsGravity = scaleToFit ? .resizeAspect : .resizeAspectFill
        }

        if sessionChanged {
            releaseAllHeldModifiers()
            hasPresentedFrame = false
            cachedNativeError = ""
            layer?.contents = nil
            lastPasteboardChangeCount = NSPasteboard.general.changeCount
            lastRemoteClipboardSeq = session.remoteClipboardSequence
            pollFrameAndTelemetry(forceSlowCheck: true)
        }
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if let win = window {
            layer?.contentsScale = win.backingScaleFactor
            startDisplayTimerIfNeeded()
            pollFrameAndTelemetry(forceSlowCheck: true)
        } else {
            stopDisplayTimer()
        }
    }

    override func viewWillMove(toWindow newWindow: NSWindow?) {
        if newWindow == nil {
            releaseAllHeldModifiers()
            stopDisplayTimer()
        }
        super.viewWillMove(toWindow: newWindow)
    }

    override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        if let scale = window?.backingScaleFactor {
            layer?.contentsScale = scale
        }
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
        releaseAllHeldModifiers()
        return super.resignFirstResponder()
    }

    private func startDisplayTimerIfNeeded() {
        guard displayTimer == nil else { return }
        let timer = Timer(timeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in
            self?.pollFrameAndTelemetry(forceSlowCheck: false)
        }
        RunLoop.main.add(timer, forMode: .common)
        displayTimer = timer
    }

    private func stopDisplayTimer() {
        displayTimer?.invalidate()
        displayTimer = nil
    }

    private func pollFrameAndTelemetry(forceSlowCheck: Bool) {
        guard let session else { return }
        tickCounter &+= 1

        let connected = session.isConnected
        let connecting = session.isConnecting
        let stateTransitioned = (connected != lastReportedConnected) || (connecting != lastReportedConnecting)
        let shouldRunSlowCheck = forceSlowCheck || stateTransitioned || (tickCounter % 30 == 0)

        if let updated = session.copyDirtyFrameIntoPersistentBuffer(forceFull: !hasPresentedFrame) {
            layer?.contents = updated.image
            hasPresentedFrame = true
            if remoteWidth != updated.width || remoteHeight != updated.height || lastReportedDims.isEmpty {
                remoteWidth = updated.width
                remoteHeight = updated.height
                lastReportedDims = "\(updated.width)×\(updated.height)"
            }
        }

        if shouldRunSlowCheck {
            cachedNativeError = session.lastErrorMessage()
            if connected && redirectClipboard {
                syncClipboard(session: session)
            }
        }

        let currentDims = lastReportedDims.isEmpty ? "\(remoteWidth)×\(remoteHeight)" : lastReportedDims
        if lastReportedConnected != connected
            || lastReportedConnecting != connecting
            || lastReportedHasFrame != hasPresentedFrame
            || lastReportedError != cachedNativeError {
            lastReportedConnected = connected
            lastReportedConnecting = connecting
            lastReportedHasFrame = hasPresentedFrame
            lastReportedError = cachedNativeError
            onTelemetryChange?(connected, connecting, hasPresentedFrame, currentDims, cachedNativeError)
        }
    }

    private func syncClipboard(session: NativeRdpSession) {
        let pbCount = NSPasteboard.general.changeCount
        if pbCount != lastPasteboardChangeCount {
            lastPasteboardChangeCount = pbCount
            if let localText = NSPasteboard.general.string(forType: .string),
               !localText.isEmpty,
               localText != lastSyncedClipboardText {
                lastSyncedClipboardText = localText
                session.pushLocalClipboard(localText)
            }
        }

        let remoteSeq = session.remoteClipboardSequence
        if remoteSeq != lastRemoteClipboardSeq {
            lastRemoteClipboardSeq = remoteSeq
            let remoteText = session.pullRemoteClipboard()
            if !remoteText.isEmpty && remoteText != lastSyncedClipboardText {
                lastSyncedClipboardText = remoteText
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(remoteText, forType: .string)
                lastPasteboardChangeCount = NSPasteboard.general.changeCount
            }
        }
    }

    // MARK: - Aspect-Aware Coordinate Mapping & Full Mouse Input

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

    override func mouseMoved(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x0800, x: x, y: y)
    }

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x8000 | 0x1000, x: x, y: y)
    }

    override func mouseUp(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x1000, x: x, y: y)
    }

    override func mouseDragged(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x0800, x: x, y: y)
    }

    override func rightMouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x8000 | 0x2000, x: x, y: y)
    }

    override func rightMouseUp(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x2000, x: x, y: y)
    }

    override func rightMouseDragged(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x0800, x: x, y: y)
    }

    override func otherMouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x8000 | 0x4000, x: x, y: y)
    }

    override func otherMouseUp(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x4000, x: x, y: y)
    }

    override func otherMouseDragged(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x0800, x: x, y: y)
    }

    override func scrollWheel(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        let rawY = event.scrollingDeltaY
        if abs(rawY) > 0.001 {
            let scaledY: Int
            if event.hasPreciseScrollingDeltas {
                let s = Int((rawY * 4.0).rounded())
                scaledY = s == 0 ? (rawY > 0 ? 16 : -16) : s
            } else {
                let s = Int((rawY * 120.0).rounded())
                scaledY = s == 0 ? (rawY > 0 ? 120 : -120) : s
            }
            if let flags = MacRdpScancodeMapper.encodeWheelFlags(delta: scaledY, horizontal: false) {
                session?.sendPointerEvent(flags: flags, x: x, y: y)
            }
        }

        let rawX = -event.scrollingDeltaX
        if abs(rawX) > 0.001 {
            let scaledX: Int
            if event.hasPreciseScrollingDeltas {
                let s = Int((rawX * 4.0).rounded())
                scaledX = s == 0 ? (rawX > 0 ? 16 : -16) : s
            } else {
                let s = Int((rawX * 120.0).rounded())
                scaledX = s == 0 ? (rawX > 0 ? 120 : -120) : s
            }
            if let flags = MacRdpScancodeMapper.encodeWheelFlags(delta: scaledX, horizontal: true) {
                session?.sendPointerEvent(flags: flags, x: x, y: y)
            }
        }
    }

    // MARK: - PC XT Set-1 Scancode & Modifier Translation

    override func keyDown(with event: NSEvent) {
        if !captureRemoteShortcuts && event.modifierFlags.contains(.command) {
            super.keyDown(with: event)
            return
        }
        guard let mapped = MacRdpScancodeMapper.mapKeyCode(
            event.keyCode,
            captureRemoteShortcuts: captureRemoteShortcuts
        ) else {
            return
        }
        session?.sendScancode(mapped.scancode, keyDown: true, extended: mapped.extended)
    }

    override func keyUp(with event: NSEvent) {
        if !captureRemoteShortcuts && event.modifierFlags.contains(.command) {
            super.keyUp(with: event)
            return
        }
        guard let mapped = MacRdpScancodeMapper.mapKeyCode(
            event.keyCode,
            captureRemoteShortcuts: captureRemoteShortcuts
        ) else {
            return
        }
        session?.sendScancode(mapped.scancode, keyDown: false, extended: mapped.extended)
    }

    override func flagsChanged(with event: NSEvent) {
        let keyCode = event.keyCode
        guard let mapped = MacRdpScancodeMapper.mapModifierKeyCode(
            keyCode,
            captureRemoteShortcuts: captureRemoteShortcuts
        ) else {
            super.flagsChanged(with: event)
            return
        }

        if mapped.isCapsLock {
            session?.sendScancode(mapped.scancode, keyDown: true, extended: mapped.extended)
            session?.sendScancode(mapped.scancode, keyDown: false, extended: mapped.extended)
            return
        }

        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let familyActive: Bool
        let familyCodes: [UInt16]
        switch keyCode {
        case 0x38, 0x3C:
            familyActive = flags.contains(.shift)
            familyCodes = [0x38, 0x3C]
        case 0x3B, 0x3E:
            familyActive = flags.contains(.control)
            familyCodes = [0x3B, 0x3E]
        case 0x3A, 0x3D:
            familyActive = flags.contains(.option)
            familyCodes = [0x3A, 0x3D]
        case 0x37, 0x36:
            familyActive = flags.contains(.command)
            familyCodes = [0x37, 0x36]
        default:
            familyActive = false
            familyCodes = [keyCode]
        }

        if !familyActive {
            for code in familyCodes where heldModifierKeyCodes.remove(code) != nil {
                if let rel = MacRdpScancodeMapper.mapModifierKeyCode(code, captureRemoteShortcuts: true) {
                    session?.sendScancode(rel.scancode, keyDown: false, extended: rel.extended)
                }
            }
        } else if heldModifierKeyCodes.contains(keyCode) {
            heldModifierKeyCodes.remove(keyCode)
            session?.sendScancode(mapped.scancode, keyDown: false, extended: mapped.extended)
        } else {
            heldModifierKeyCodes.insert(keyCode)
            session?.sendScancode(mapped.scancode, keyDown: true, extended: mapped.extended)
        }
    }

    private func releaseAllHeldModifiers() {
        guard !heldModifierKeyCodes.isEmpty else { return }
        let toRelease = heldModifierKeyCodes
        heldModifierKeyCodes.removeAll()
        for code in toRelease {
            if let rel = MacRdpScancodeMapper.mapModifierKeyCode(code, captureRemoteShortcuts: true) {
                session?.sendScancode(rel.scancode, keyDown: false, extended: rel.extended)
            }
        }
    }
}
