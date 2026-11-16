import AppKit
import CoreGraphics
import Metal
import NexusTSCore
import QuartzCore
import simd
import SwiftUI

public struct RdpSessionView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let tab: WorkspaceSessionTab

    @State private var isConnected: Bool = false
    @State private var isConnecting: Bool = true
    @State private var hasPresentedFrame: Bool = false
    @State private var lastNativeError: String = ""
    @State private var scaleToFit: Bool = true
    @State private var retina2xEnabled: Bool = false
    @State private var didInitializeRetinaPref: Bool = false
    @State private var showHudBar: Bool = true
    @State private var frameDimensionsText: String = "1920×1080"
    @State private var sessionGeneration: Int = 0
    @State private var lastViewportSize: CGSize = .zero
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
        let effectiveConnected = isConnected || session.isConnected
        let effectiveHasFrame = hasPresentedFrame || session.hasValidFrame

        VStack(spacing: 0) {
            // External Remote Desktop Control & Telemetry Bar (strictly outside/above the RDP viewport)
            if showHudBar {
                HStack(spacing: 10) {
                    Circle()
                        .fill(
                            effectiveConnected
                                ? Color(nsColor: .systemGreen)
                                : ((isConnecting || lastNativeError.isEmpty)
                                    ? Color(nsColor: .systemOrange)
                                    : Color(nsColor: .systemRed))
                        )
                        .frame(width: 8, height: 8)

                    Text(latestConnection.name)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)

                    Text(verbatim: frameDimensionsText)
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
                    .disabled(!effectiveConnected)

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

                    Toggle("2× Retina", isOn: $retina2xEnabled)
                        .toggleStyle(.checkbox)
                        .font(.caption)
                        .help("Toggle between fast 1× native logical resolution (Royal TSX default) and 2× Retina HiDPI scaling")

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
                    Text(verbatim: "\(latestConnection.name) • \(frameDimensionsText)")
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

                    // Direct Metal CAMetalLayer surface — partial dirty-rect GPU upload + 120 Hz event-driven push
                    RdpInteractiveSurfaceNSViewRepresentable(
                        session: session,
                        generation: sessionGeneration,
                        scaleToFit: scaleToFit,
                        retina2xEnabled: retina2xEnabled,
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
                    .opacity(effectiveHasFrame && (effectiveConnected || lastNativeError.isEmpty) ? 1.0 : 0.0)

                    if !effectiveConnected && !isConnecting && !lastNativeError.isEmpty {
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
                    } else if !effectiveHasFrame {
                        VStack(spacing: 14) {
                            ProtocolSquircleIcon(protocolType: latestConnection.protocolType, size: 52)
                            Text(verbatim: "FreeRDP 3.x Native Engine • \(latestConnection.host):\(String(rdpSettings.port))")
                                .font(.headline)
                                .foregroundStyle(.white)

                            HStack(spacing: 8) {
                                if isConnecting || effectiveConnected {
                                    ProgressView()
                                        .controlSize(.small)
                                }
                                Text(
                                    effectiveConnected
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
                    if !didInitializeRetinaPref {
                        didInitializeRetinaPref = true
                        retina2xEnabled = rdpSettings.hiDpiEnabled && rdpSettings.desktopScaleFactorPercent >= 150
                    }
                    isConnected = session.isConnected
                    isConnecting = session.isConnecting
                    hasPresentedFrame = session.hasValidFrame
                    lastNativeError = session.lastErrorMessage()
                    lastViewportSize = geo.size
                    scheduleDynamicResize(for: geo.size, session: session)
                }
                .onDisappear {
                    resizeDebounceTask?.cancel()
                    resizeDebounceTask = nil
                }
                .onChange(of: geo.size) { _, newSize in
                    lastViewportSize = newSize
                    scheduleDynamicResize(for: newSize, session: session)
                }
                .onChange(of: retina2xEnabled) { _, _ in
                    scheduleDynamicResize(for: lastViewportSize, session: session, immediate: true)
                }
            }
        }
    }

    private func scheduleDynamicResize(
        for viewportSize: CGSize,
        session: NativeRdpSession,
        immediate: Bool = false
    ) {
        resizeDebounceTask?.cancel()
        guard rdpSettings.resizeBehavior == .DYNAMIC_DISP_DVC else { return }
        guard viewportSize.width >= 320, viewportSize.height >= 240 else { return }
        let useRetina = retina2xEnabled
        resizeDebounceTask = Task { @MainActor in
            if !immediate {
                try? await Task.sleep(nanoseconds: 180_000_000)
            }
            guard !Task.isCancelled else { return }
            let backingScale = NSScreen.main?.backingScaleFactor ?? 2.0
            let factor = useRetina ? backingScale : 1.0
            let pixelW = Int((viewportSize.width * factor).rounded())
            let pixelH = Int((viewportSize.height * factor).rounded())
            guard pixelW >= 320, pixelH >= 240 else { return }
            _ = session.requestDynamicResize(
                width: pixelW,
                height: pixelH,
                scalePercent: useRetina ? 200 : 100
            )
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

// MARK: - Shared Metal Pipeline & Coalesced Main-Thread Wakeup

private final class RdpMetalSharedPipeline: @unchecked Sendable {
    static let shared: RdpMetalSharedPipeline? = RdpMetalSharedPipeline()

    let device: MTLDevice
    let commandQueue: MTLCommandQueue
    let pipelineState: MTLRenderPipelineState

    private static let shaderSource = """
    #include <metal_stdlib>
    using namespace metal;

    struct VertexOut {
        float4 position [[position]];
        float2 uv;
    };

    struct ScaleUniforms {
        float2 scale;
    };

    vertex VertexOut nexusts_rdp_vs(
        uint vid [[vertex_id]],
        constant ScaleUniforms& u [[buffer(0)]]
    ) {
        const float2 pos[4] = {
            float2(-1.0, -1.0),
            float2( 1.0, -1.0),
            float2(-1.0,  1.0),
            float2( 1.0,  1.0)
        };
        const float2 uvs[4] = {
            float2(0.0, 1.0),
            float2(1.0, 1.0),
            float2(0.0, 0.0),
            float2(1.0, 0.0)
        };
        VertexOut out;
        out.position = float4(pos[vid] * u.scale, 0.0, 1.0);
        out.uv = uvs[vid];
        return out;
    }

    fragment half4 nexusts_rdp_fs(
        VertexOut in [[stage_in]],
        texture2d<half, access::sample> tex [[texture(0)]]
    ) {
        constexpr sampler s(coord::normalized, address::clamp_to_edge, filter::linear);
        half3 rgb = tex.sample(s, in.uv).rgb;
        return half4(rgb, 1.0h);
    }
    """

    private init?() {
        guard let dev = MTLCreateSystemDefaultDevice(),
              let queue = dev.makeCommandQueue(),
              let lib = try? dev.makeLibrary(source: Self.shaderSource, options: nil),
              let vs = lib.makeFunction(name: "nexusts_rdp_vs"),
              let fs = lib.makeFunction(name: "nexusts_rdp_fs") else {
            return nil
        }
        let desc = MTLRenderPipelineDescriptor()
        desc.vertexFunction = vs
        desc.fragmentFunction = fs
        desc.colorAttachments[0].pixelFormat = .bgra8Unorm
        desc.colorAttachments[0].isBlendingEnabled = false
        guard let pso = try? dev.makeRenderPipelineState(descriptor: desc) else {
            return nil
        }
        self.device = dev
        self.commandQueue = queue
        self.pipelineState = pso
    }
}

private final class MainThreadRdpFrameSignal: @unchecked Sendable {
    private let lock = NSLock()
    private var isPending = false
    private let onWakeMain: @MainActor () -> Void

    init(onWakeMain: @escaping @MainActor () -> Void) {
        self.onWakeMain = onWakeMain
    }

    func signalFromAnyThread() {
        lock.lock()
        if isPending {
            lock.unlock()
            return
        }
        isPending = true
        lock.unlock()

        let wake = onWakeMain
        DispatchQueue.main.async { [weak self] in
            if let self {
                self.lock.lock()
                self.isPending = false
                self.lock.unlock()
            }
            MainActor.assumeIsolated {
                wake()
            }
        }
    }
}

private struct RdpInteractiveSurfaceNSViewRepresentable: NSViewRepresentable {
    let session: NativeRdpSession
    let generation: Int
    let scaleToFit: Bool
    let retina2xEnabled: Bool
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
            retina2xEnabled: retina2xEnabled,
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
            retina2xEnabled: retina2xEnabled,
            redirectClipboard: redirectClipboard,
            captureRemoteShortcuts: captureRemoteShortcuts
        )
    }
}

final class RdpPointerAndKeyNSView: NSView {
    private static let transparentHiddenCursor: NSCursor = {
        let img = NSImage(size: NSSize(width: 16, height: 16))
        img.lockFocus()
        NSColor.clear.set()
        NSRect(x: 0, y: 0, width: 16, height: 16).fill()
        img.unlockFocus()
        return NSCursor(image: img, hotSpot: NSPoint(x: 0, y: 0))
    }()

    var session: NativeRdpSession?
    var remoteWidth: Int = 1920
    var remoteHeight: Int = 1080
    var scaleToFit: Bool = true
    var retina2xEnabled: Bool = false
    var redirectClipboard: Bool = true
    var captureRemoteShortcuts: Bool = false
    var onTelemetryChange: ((Bool, Bool, Bool, String, String) -> Void)?

    private var activeGeneration: Int = -1
    private var housekeepingTimer: Timer?
    private var trackingAreaRef: NSTrackingArea?
    private var hasPresentedFrame: Bool = false
    private var frameWakeSignal: MainThreadRdpFrameSignal?

    private var metalSublayer: CAMetalLayer?
    private var desktopTexture: MTLTexture?
    private var lastUploadedFrameSeq: UInt64 = 0
    private var lastDrawablePixelSize: CGSize = .zero

    private var lastAppliedCursorSeq: UInt64 = 0
    private var activeRemoteCursor: NSCursor = .arrow
    private var isPointerInsideView: Bool = false

    private var lastMouseMoveSentTime: CFTimeInterval = 0
    private var pendingMouseMovePoint: (Int, Int)?
    private var trailingMouseMoveItem: DispatchWorkItem?

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
    override var isOpaque: Bool { true }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        setupBackingLayer()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupBackingLayer()
    }

    private func setupBackingLayer() {
        wantsLayer = true
        let darkBg = NSColor(calibratedRed: 0.06, green: 0.07, blue: 0.09, alpha: 1.0).cgColor
        layer?.backgroundColor = darkBg
        layer?.magnificationFilter = .trilinear
        layer?.minificationFilter = .trilinear
        layer?.contentsGravity = .resizeAspect
        layer?.actions = [
            "contents": NSNull(),
            "bounds": NSNull(),
            "contentsScale": NSNull(),
            "contentsGravity": NSNull(),
            "sublayers": NSNull()
        ]

        if let pipeline = RdpMetalSharedPipeline.shared, let rootLayer = layer {
            let mLayer = CAMetalLayer()
            mLayer.device = pipeline.device
            mLayer.pixelFormat = .bgra8Unorm
            mLayer.framebufferOnly = true
            mLayer.isOpaque = true
            mLayer.presentsWithTransaction = false
            mLayer.displaySyncEnabled = true
            mLayer.backgroundColor = darkBg
            mLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
            mLayer.frame = bounds
            mLayer.actions = [
                "bounds": NSNull(),
                "position": NSNull(),
                "contentsScale": NSNull(),
                "contents": NSNull()
            ]
            rootLayer.addSublayer(mLayer)
            metalSublayer = mLayer
        }

        frameWakeSignal = MainThreadRdpFrameSignal { [weak self] in
            self?.handleEventDrivenWakeup()
        }
    }

    func configure(
        session: NativeRdpSession,
        generation: Int,
        scaleToFit: Bool,
        retina2xEnabled: Bool,
        redirectClipboard: Bool,
        captureRemoteShortcuts: Bool
    ) {
        let previousSession = self.session
        let sessionChanged = (previousSession !== session) || (self.activeGeneration != generation)
        let fitChanged = (self.scaleToFit != scaleToFit)
        let retinaChanged = (self.retina2xEnabled != retina2xEnabled)

        self.session = session
        self.activeGeneration = generation
        self.scaleToFit = scaleToFit
        self.retina2xEnabled = retina2xEnabled
        self.redirectClipboard = redirectClipboard
        self.captureRemoteShortcuts = captureRemoteShortcuts
        layer?.contentsGravity = scaleToFit ? .resizeAspect : .resizeAspectFill

        if sessionChanged {
            if previousSession !== session {
                previousSession?.setFrameReadyHandler(nil)
            }
            releaseAllHeldModifiers()
            cancelPendingMouseMove()
            desktopTexture = nil
            lastUploadedFrameSeq = 0
            lastAppliedCursorSeq = 0
            activeRemoteCursor = .arrow
            hasPresentedFrame = false
            cachedNativeError = ""
            lastReportedConnected = nil
            lastReportedConnecting = nil
            lastReportedHasFrame = nil
            lastReportedDims = ""
            lastReportedError = ""
            layer?.contents = nil
            lastPasteboardChangeCount = NSPasteboard.general.changeCount
            lastRemoteClipboardSeq = session.remoteClipboardSequence

            let signal = frameWakeSignal
            session.setFrameReadyHandler { [weak signal] in
                signal?.signalFromAnyThread()
            }
            refreshFrameCursorAndTelemetry(forceFullUpload: true, runSlowHousekeeping: true)
        } else {
            if retinaChanged {
                lastAppliedCursorSeq = 0
                refreshHardwareCursorIfNeeded(session: session)
            }
            if fitChanged {
                if metalSublayer != nil {
                    renderMetalDrawable()
                }
            }
            if !hasPresentedFrame && session.hasValidFrame {
                refreshFrameCursorAndTelemetry(forceFullUpload: true, runSlowHousekeeping: false)
            }
        }
    }

    override func layout() {
        super.layout()
        updateMetalDrawableSizeAndRedrawIfNeeded()
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if let win = window {
            let scale = win.backingScaleFactor
            layer?.contentsScale = scale
            metalSublayer?.contentsScale = scale
            if let session, let signal = frameWakeSignal {
                session.setFrameReadyHandler { [weak signal] in
                    signal?.signalFromAnyThread()
                }
            }
            startHousekeepingTimerIfNeeded()
            updateMetalDrawableSizeAndRedrawIfNeeded()
            refreshFrameCursorAndTelemetry(forceFullUpload: !hasPresentedFrame, runSlowHousekeeping: true)
        } else {
            stopHousekeepingTimer()
        }
    }

    override func viewWillMove(toWindow newWindow: NSWindow?) {
        if newWindow == nil {
            releaseAllHeldModifiers()
            cancelPendingMouseMove()
            stopHousekeepingTimer()
            session?.setFrameReadyHandler(nil)
        }
        super.viewWillMove(toWindow: newWindow)
    }

    override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        if let scale = window?.backingScaleFactor {
            layer?.contentsScale = scale
            metalSublayer?.contentsScale = scale
            updateMetalDrawableSizeAndRedrawIfNeeded()
        }
    }

    override func resetCursorRects() {
        super.resetCursorRects()
        addCursorRect(bounds, cursor: activeRemoteCursor)
    }

    override func cursorUpdate(with event: NSEvent) {
        activeRemoteCursor.set()
    }

    override func updateTrackingAreas() {
        if let existing = trackingAreaRef {
            removeTrackingArea(existing)
        }
        let area = NSTrackingArea(
            rect: .zero,
            options: [.activeInKeyWindow, .inVisibleRect, .mouseMoved, .mouseEnteredAndExited, .cursorUpdate],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(area)
        trackingAreaRef = area
        super.updateTrackingAreas()
    }

    override func mouseEntered(with event: NSEvent) {
        isPointerInsideView = true
        activeRemoteCursor.set()
    }

    override func mouseExited(with event: NSEvent) {
        isPointerInsideView = false
        NSCursor.arrow.set()
    }

    override func resignFirstResponder() -> Bool {
        releaseAllHeldModifiers()
        return super.resignFirstResponder()
    }

    private func startHousekeepingTimerIfNeeded() {
        guard housekeepingTimer == nil else { return }
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.refreshFrameCursorAndTelemetry(forceFullUpload: false, runSlowHousekeeping: true)
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        housekeepingTimer = timer
    }

    private func stopHousekeepingTimer() {
        housekeepingTimer?.invalidate()
        housekeepingTimer = nil
    }

    private func handleEventDrivenWakeup() {
        refreshFrameCursorAndTelemetry(forceFullUpload: false, runSlowHousekeeping: false)
    }

    private func updateMetalDrawableSizeAndRedrawIfNeeded() {
        guard let mLayer = metalSublayer else { return }
        mLayer.frame = bounds
        let scale = window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 2.0
        mLayer.contentsScale = scale
        let pixelW = max(1.0, (bounds.width * scale).rounded())
        let pixelH = max(1.0, (bounds.height * scale).rounded())
        let targetSize = CGSize(width: pixelW, height: pixelH)
        if lastDrawablePixelSize != targetSize {
            lastDrawablePixelSize = targetSize
            mLayer.drawableSize = targetSize
            if desktopTexture != nil {
                renderMetalDrawable()
            }
        }
    }

    private func refreshHardwareCursorIfNeeded(session: NativeRdpSession) {
        let curSeq = session.cursorSequence
        guard curSeq != lastAppliedCursorSeq else { return }
        lastAppliedCursorSeq = curSeq

        switch session.copyRemoteCursor() {
        case .hidden:
            activeRemoteCursor = Self.transparentHiddenCursor
        case .systemDefault:
            activeRemoteCursor = .arrow
        case .custom(let cgImage, let w, let h, let hotX, let hotY):
            let divisor: CGFloat = retina2xEnabled ? 2.0 : 1.0
            let pointSize = NSSize(
                width: max(1.0, CGFloat(w) / divisor),
                height: max(1.0, CGFloat(h) / divisor)
            )
            let nsImage = NSImage(cgImage: cgImage, size: pointSize)
            let spot = NSPoint(
                x: min(pointSize.width - 1.0, max(0.0, CGFloat(hotX) / divisor)),
                y: min(pointSize.height - 1.0, max(0.0, CGFloat(hotY) / divisor))
            )
            activeRemoteCursor = NSCursor(image: nsImage, hotSpot: spot)
        }

        window?.invalidateCursorRects(for: self)
        if isPointerInsideView {
            activeRemoteCursor.set()
        }
    }

    private func refreshFrameCursorAndTelemetry(forceFullUpload: Bool, runSlowHousekeeping: Bool) {
        guard let session else { return }

        refreshHardwareCursorIfNeeded(session: session)

        let currentSeq = session.frameSequence
        if currentSeq > 0 && (forceFullUpload || currentSeq != lastUploadedFrameSeq || desktopTexture == nil) {
            if let pipeline = RdpMetalSharedPipeline.shared, metalSublayer != nil {
                var uploadedW = remoteWidth
                var uploadedH = remoteHeight
                var uploadedSeq = lastUploadedFrameSeq
                var didUpload = false

                let consumed = session.consumeDirtyRegion(forceFull: forceFullUpload || desktopTexture == nil) { slice in
                    let w = slice.frameWidth
                    let h = slice.frameHeight
                    guard w > 0, h > 0 else { return }

                    var recreated = false
                    if desktopTexture == nil || desktopTexture?.width != w || desktopTexture?.height != h {
                        let desc = MTLTextureDescriptor.texture2DDescriptor(
                            pixelFormat: .bgra8Unorm,
                            width: w,
                            height: h,
                            mipmapped: false
                        )
                        desc.usage = [.shaderRead]
                        desc.storageMode = .shared
                        desktopTexture = pipeline.device.makeTexture(descriptor: desc)
                        recreated = true
                    }

                    guard let tex = desktopTexture else { return }
                    if recreated || forceFullUpload {
                        tex.replace(
                            region: MTLRegionMake2D(0, 0, w, h),
                            mipmapLevel: 0,
                            withBytes: slice.fullFramePixels,
                            bytesPerRow: slice.strideBytes
                        )
                        didUpload = true
                    } else if slice.dirtyWidth > 0 && slice.dirtyHeight > 0 {
                        let byteOffset = slice.dirtyY * slice.strideBytes + slice.dirtyX * 4
                        tex.replace(
                            region: MTLRegionMake2D(slice.dirtyX, slice.dirtyY, slice.dirtyWidth, slice.dirtyHeight),
                            mipmapLevel: 0,
                            withBytes: slice.fullFramePixels.advanced(by: byteOffset),
                            bytesPerRow: slice.strideBytes
                        )
                        didUpload = true
                    }

                    uploadedW = w
                    uploadedH = h
                    uploadedSeq = slice.sequence
                }

                if consumed && desktopTexture != nil {
                    lastUploadedFrameSeq = uploadedSeq
                    hasPresentedFrame = true
                    if remoteWidth != uploadedW || remoteHeight != uploadedH || lastReportedDims.isEmpty {
                        remoteWidth = uploadedW
                        remoteHeight = uploadedH
                        lastReportedDims = "\(uploadedW)×\(uploadedH)"
                    }
                    if didUpload || forceFullUpload {
                        renderMetalDrawable()
                    }
                }
            } else if let fallback = session.copyDirtyFrameIntoPersistentBuffer(forceFull: forceFullUpload || !hasPresentedFrame) {
                layer?.contents = fallback.image
                lastUploadedFrameSeq = fallback.sequence
                hasPresentedFrame = true
                if remoteWidth != fallback.width || remoteHeight != fallback.height || lastReportedDims.isEmpty {
                    remoteWidth = fallback.width
                    remoteHeight = fallback.height
                    lastReportedDims = "\(fallback.width)×\(fallback.height)"
                }
            }
        }

        let connected = session.isConnected
        let connecting = session.isConnecting
        let stateTransitioned = (connected != lastReportedConnected) || (connecting != lastReportedConnecting)

        if runSlowHousekeeping || stateTransitioned {
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

            let snapConnected = connected
            let snapConnecting = connecting
            let snapHasFrame = hasPresentedFrame
            let snapDims = currentDims
            let snapError = cachedNativeError
            // Always dispatch asynchronously outside makeNSView/updateNSView so SwiftUI never discards @State mutations
            DispatchQueue.main.async { [weak self] in
                self?.onTelemetryChange?(snapConnected, snapConnecting, snapHasFrame, snapDims, snapError)
            }
        }
    }

    private func renderMetalDrawable() {
        guard let pipeline = RdpMetalSharedPipeline.shared,
              let mLayer = metalSublayer,
              let tex = desktopTexture,
              bounds.width >= 2.0,
              bounds.height >= 2.0 else {
            return
        }
        if mLayer.drawableSize.width < 2.0 || mLayer.drawableSize.height < 2.0 {
            let scale = window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 2.0
            let dw = max(2.0, (bounds.width * scale).rounded())
            let dh = max(2.0, (bounds.height * scale).rounded())
            mLayer.drawableSize = CGSize(width: dw, height: dh)
            lastDrawablePixelSize = mLayer.drawableSize
        }

        guard let drawable = mLayer.nextDrawable(),
              let cmdBuf = pipeline.commandQueue.makeCommandBuffer() else {
            return
        }

        let passDesc = MTLRenderPassDescriptor()
        passDesc.colorAttachments[0].texture = drawable.texture
        passDesc.colorAttachments[0].loadAction = .clear
        passDesc.colorAttachments[0].clearColor = MTLClearColor(red: 0.06, green: 0.07, blue: 0.09, alpha: 1.0)
        passDesc.colorAttachments[0].storeAction = .store

        guard let enc = cmdBuf.makeRenderCommandEncoder(descriptor: passDesc) else {
            return
        }

        let drawW = max(1.0, Float(drawable.texture.width))
        let drawH = max(1.0, Float(drawable.texture.height))
        let texW = max(1.0, Float(tex.width))
        let texH = max(1.0, Float(tex.height))
        let viewAspect = drawW / drawH
        let texAspect = texW / texH

        var scaleVec: SIMD2<Float>
        if scaleToFit {
            if texAspect > viewAspect {
                scaleVec = SIMD2<Float>(1.0, viewAspect / texAspect)
            } else {
                scaleVec = SIMD2<Float>(texAspect / viewAspect, 1.0)
            }
        } else {
            if texAspect > viewAspect {
                scaleVec = SIMD2<Float>(texAspect / viewAspect, 1.0)
            } else {
                scaleVec = SIMD2<Float>(1.0, viewAspect / texAspect)
            }
        }

        enc.setRenderPipelineState(pipeline.pipelineState)
        enc.setVertexBytes(&scaleVec, length: MemoryLayout<SIMD2<Float>>.stride, index: 0)
        enc.setFragmentTexture(tex, index: 0)
        enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        enc.endEncoding()

        cmdBuf.present(drawable)
        cmdBuf.commit()
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

    // MARK: - Aspect-Aware Coordinate Mapping & Coalesced Mouse Input (~125 Hz)

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

    private func cancelPendingMouseMove() {
        trailingMouseMoveItem?.cancel()
        trailingMouseMoveItem = nil
        pendingMouseMovePoint = nil
    }

    private func sendCoalescedMouseMove(x: Int, y: Int) {
        let now = CACurrentMediaTime()
        let minInterval: CFTimeInterval = 0.008 // ~125 Hz ceiling matching Royal TSX
        let elapsed = now - lastMouseMoveSentTime
        if elapsed >= minInterval {
            cancelPendingMouseMove()
            lastMouseMoveSentTime = now
            session?.sendPointerEvent(flags: 0x0800, x: x, y: y)
        } else {
            pendingMouseMovePoint = (x, y)
            if trailingMouseMoveItem == nil {
                let delay = max(0.001, minInterval - elapsed)
                let item = DispatchWorkItem { [weak self] in
                    guard let self else { return }
                    self.trailingMouseMoveItem = nil
                    if let pt = self.pendingMouseMovePoint {
                        self.pendingMouseMovePoint = nil
                        self.lastMouseMoveSentTime = CACurrentMediaTime()
                        self.session?.sendPointerEvent(flags: 0x0800, x: pt.0, y: pt.1)
                    }
                }
                trailingMouseMoveItem = item
                DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
            }
        }
    }

    override func mouseMoved(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        sendCoalescedMouseMove(x: x, y: y)
    }

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        cancelPendingMouseMove()
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x8000 | 0x1000, x: x, y: y)
    }

    override func mouseUp(with event: NSEvent) {
        cancelPendingMouseMove()
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x1000, x: x, y: y)
    }

    override func mouseDragged(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        sendCoalescedMouseMove(x: x, y: y)
    }

    override func rightMouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        cancelPendingMouseMove()
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x8000 | 0x2000, x: x, y: y)
    }

    override func rightMouseUp(with event: NSEvent) {
        cancelPendingMouseMove()
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x2000, x: x, y: y)
    }

    override func rightMouseDragged(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        sendCoalescedMouseMove(x: x, y: y)
    }

    override func otherMouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        cancelPendingMouseMove()
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x8000 | 0x4000, x: x, y: y)
    }

    override func otherMouseUp(with event: NSEvent) {
        cancelPendingMouseMove()
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x4000, x: x, y: y)
    }

    override func otherMouseDragged(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        sendCoalescedMouseMove(x: x, y: y)
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
