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
    @State private var isReconnecting: Bool = false
    @State private var reconnectAttempt: Int = 0
    @State private var hasPresentedFrame: Bool = false
    @State private var lastNativeError: String = ""
    @State private var scaleToFit: Bool = true
    @State private var retina2xEnabled: Bool = true
    @State private var didInitializeRetinaPref: Bool = false
    @State private var syncedGeneration: Int = -1
    @State private var showHudBar: Bool = true
    @State private var frameDimensionsText: String = "1920×1080"
    @State private var sessionGeneration: Int = 0
    @State private var lastViewportSize: CGSize = .zero
    @State private var lastSentResizeSpec: (w: Int, h: Int, scale: Int)? = nil
    @State private var resizeDebounceTask: Task<Void, Never>? = nil
    @State private var showDelayedSpinner: Bool = false
    @State private var showStalledReconnect: Bool = false

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

    private var configuredHiDpiPercent: Int {
        rdpSettings.desktopScaleFactorPercent > 100
            ? rdpSettings.desktopScaleFactorPercent
            : NativeRdpSession.defaultHiDpiScalePercent
    }

    private var gfxBadgeLabel: String {
        guard rdpSettings.enableGfxPipeline else { return "GDI" }
        switch rdpSettings.gfxCodecProfile {
        case .ADAPTIVE_PROGRESSIVE: return "GFX"
        case .AVC420_VIDEO:         return "AVC420"
        case .AVC444_CHROMA:        return "AVC444"
        }
    }

    public var body: some View {
        let existingSession = store.existingRdpController(for: tab)
        let hudReconnecting = isReconnecting || (existingSession?.isReconnecting ?? false)
        let hudConnected = !hudReconnecting && (isConnected || (existingSession?.isConnected ?? false))

        VStack(spacing: 0) {
            // External Remote Desktop Control & Telemetry Bar (strictly outside/above the RDP viewport)
            if showHudBar {
                HStack(spacing: 10) {
                    Circle()
                        .fill(
                            hudReconnecting
                                ? Color(nsColor: .systemOrange)
                                : (hudConnected
                                    ? Color(nsColor: .systemGreen)
                                    : ((isConnecting || lastNativeError.isEmpty)
                                        ? Color(nsColor: .systemOrange)
                                        : Color(nsColor: .systemRed)))
                        )
                        .frame(width: 8, height: 8)

                    Text(latestConnection.name)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)

                    Text(verbatim: "\(frameDimensionsText) • \(gfxBadgeLabel)")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)

                    if !rdpSettings.effectivePreconnectionBlob.isEmpty {
                        Text(rdpSettings.hyperVEnhancedSession ? "VMConnect • Enhanced" : "VMConnect • \(rdpSettings.effectiveRdpPort)")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(Color.cyan.opacity(0.22)))
                    }

                    if rdpSettings.connectToConsoleAdmin {
                        Text("/admin")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(Color.orange.opacity(0.22)))
                    }

                    if rdpSettings.redirectClipboard {
                        Label("Clipboard Sync (⌘C / ⌘V)", systemImage: "arrow.left.arrow.right.circle")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }

                    Spacer(minLength: 8)

                    Button("Start") {
                        existingSession?.sendScancode(0x5B, keyDown: true, extended: true)
                        existingSession?.sendScancode(0x5B, keyDown: false, extended: true)
                    }
                    .controlSize(.small)
                    .disabled(!hudConnected)
                    .help("Open Remote Windows Start Menu")

                    Button("Ctrl+Alt+Del") {
                        existingSession?.sendCtrlAltDel()
                    }
                    .controlSize(.small)
                    .disabled(!hudConnected)

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

                    Toggle("HiDPI (\(configuredHiDpiPercent)%)", isOn: $retina2xEnabled)
                        .toggleStyle(.checkbox)
                        .font(.caption)
                        .help("Render remote Windows desktop in crisp Retina HiDPI (\(configuredHiDpiPercent)% scale) or 1× logical resolution")

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
                    Text(verbatim: "\(latestConnection.name) • \(frameDimensionsText) • \(gfxBadgeLabel)")
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
                let backingScale = NSScreen.main?.backingScaleFactor ?? 2.0
                let desiredRetina = didInitializeRetinaPref
                    ? retina2xEnabled
                    : (rdpSettings.hiDpiEnabled && (backingScale >= 1.5 || rdpSettings.desktopScaleFactorPercent > 100))
                let viewportReady = geo.size.width >= 320 && geo.size.height >= 240
                let session: NativeRdpSession? = store.existingRdpController(for: tab) ?? (
                    viewportReady
                        ? store.rdpController(
                            for: tab,
                            initialViewportSize: geo.size,
                            backingScaleFactor: backingScale,
                            useRetinaOverride: desiredRetina
                        )
                        : nil
                )
                let sessionReconnecting = isReconnecting || (session?.isReconnecting ?? false)
                let sessionAttempt = max(reconnectAttempt, session?.reconnectAttempt ?? 0)
                let effectiveConnected = isConnected || (session?.isConnected ?? false)
                let effectiveHasFrame = hasPresentedFrame || (session?.hasValidFrame ?? false)

                ZStack {
                    Color(red: 0.06, green: 0.07, blue: 0.09)

                    if let session {
                        // Direct off-main-thread Metal CAMetalLayer surface — always live at opacity 1.0
                        RdpInteractiveSurfaceNSViewRepresentable(
                            session: session,
                            generation: sessionGeneration,
                            scaleToFit: scaleToFit,
                            retina2xEnabled: desiredRetina,
                            redirectClipboard: rdpSettings.redirectClipboard,
                            captureRemoteShortcuts: store.remoteKeyCaptureEnabled,
                            onTelemetryChange: { connected, connecting, reconnecting, attempt, hasFrame, dimsText, errorText in
                                if isConnected != connected { isConnected = connected }
                                if isConnecting != connecting { isConnecting = connecting }
                                if isReconnecting != reconnecting { isReconnecting = reconnecting }
                                if reconnectAttempt != attempt { reconnectAttempt = attempt }
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
                    }

                    if sessionReconnecting {
                        VStack {
                            HStack(spacing: 10) {
                                ProgressView()
                                    .controlSize(.small)
                                Text("Reconnecting to \(latestConnection.name) (attempt \(max(1, sessionAttempt))/8)…")
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(.white)
                                Button("Reconnect Now") {
                                    triggerReconnect()
                                }
                                .controlSize(.small)
                                .buttonStyle(.borderedProminent)
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(
                                Capsule(style: .continuous)
                                    .fill(Color.black.opacity(0.78))
                                    .overlay(
                                        Capsule(style: .continuous)
                                            .strokeBorder(Color.orange.opacity(0.55), lineWidth: 1)
                                    )
                            )
                            .padding(.top, 12)
                            Spacer()
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    } else if !effectiveConnected && !isConnecting && !lastNativeError.isEmpty {
                        let diag = NexusErrorClassifier.classifyRdpError(
                            host: latestConnection.host,
                            port: rdpSettings.effectiveRdpPort,
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
                    } else if !effectiveHasFrame && showDelayedSpinner {
                        let showActions = showStalledReconnect || (!isConnecting && !effectiveConnected)
                        VStack(spacing: 12) {
                            HStack(spacing: 8) {
                                ProgressView()
                                    .controlSize(.small)
                                Text("Connecting to \(latestConnection.name)…")
                                    .font(.callout)
                                    .foregroundStyle(.white.opacity(0.72))
                            }

                            if showActions {
                                HStack(spacing: 10) {
                                    Button("Reconnect") {
                                        triggerReconnect()
                                    }
                                    .controlSize(.small)
                                    .buttonStyle(.borderedProminent)

                                    Button("Edit RDP Settings…") {
                                        store.editingConnection = latestConnection
                                    }
                                    .controlSize(.small)
                                }
                            }
                        }
                        .allowsHitTesting(showActions)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .task(id: "\(sessionGeneration)-\(effectiveHasFrame)") {
                    if effectiveHasFrame {
                        showDelayedSpinner = false
                        showStalledReconnect = false
                        return
                    }
                    showDelayedSpinner = false
                    showStalledReconnect = false
                    try? await Task.sleep(nanoseconds: 350_000_000)
                    guard !Task.isCancelled, !effectiveHasFrame else { return }
                    showDelayedSpinner = true
                    try? await Task.sleep(nanoseconds: 7_650_000_000)
                    guard !Task.isCancelled, !effectiveHasFrame else { return }
                    showStalledReconnect = true
                }
                .onAppear {
                    lastViewportSize = geo.size
                    if let session {
                        syncSessionInitialStateIfNeeded(session: session)
                        scheduleDynamicResize(for: geo.size, session: session)
                    }
                }
                .onDisappear {
                    resizeDebounceTask?.cancel()
                    resizeDebounceTask = nil
                }
                .onChange(of: geo.size) { _, newSize in
                    lastViewportSize = newSize
                    if let session {
                        syncSessionInitialStateIfNeeded(session: session)
                        scheduleDynamicResize(for: newSize, session: session)
                    }
                }
                .onChange(of: retina2xEnabled) { _, _ in
                    if let session {
                        scheduleDynamicResize(for: lastViewportSize, session: session, immediate: true)
                    }
                }
                .onChange(of: isConnected) { _, nowConnected in
                    if nowConnected, let session {
                        scheduleDynamicResize(for: lastViewportSize, session: session, immediate: true)
                    }
                }
            }
        }
    }

    private func syncSessionInitialStateIfNeeded(session: NativeRdpSession) {
        guard syncedGeneration != sessionGeneration else { return }
        syncedGeneration = sessionGeneration
        if !didInitializeRetinaPref {
            didInitializeRetinaPref = true
            retina2xEnabled = session.initialHiDpiActive
        }
        isConnected = session.isConnected
        isConnecting = session.isConnecting
        isReconnecting = session.isReconnecting
        reconnectAttempt = session.reconnectAttempt
        hasPresentedFrame = session.hasValidFrame
        lastNativeError = session.lastErrorMessage()
        if !session.hasValidFrame {
            frameDimensionsText = "\(session.initialPixelWidth)×\(session.initialPixelHeight)"
        }
        if lastSentResizeSpec == nil {
            lastSentResizeSpec = (session.initialPixelWidth, session.initialPixelHeight, session.initialScalePercent)
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
        let backingScale = NSScreen.main?.backingScaleFactor ?? 2.0
        let factor = useRetina ? backingScale : 1.0
        let rawW = max(640, Int((viewportSize.width * factor).rounded()))
        let rawH = max(480, Int((viewportSize.height * factor).rounded()))
        let (pixelW, pixelH) = NativeRdpSession.clampSurfacePixelDimensions(
            width: rawW,
            height: rawH,
            h264Enabled: NativeRdpSession.usesH264Cap(settings: rdpSettings)
        )
        let scalePercent: Int = {
            guard useRetina else { return 100 }
            var effective = rdpSettings
            effective.hiDpiEnabled = true
            if effective.desktopScaleFactorPercent <= 100 {
                effective.desktopScaleFactorPercent = NativeRdpSession.defaultHiDpiScalePercent
            }
            return NativeRdpSession.resolveEffectiveHiDpiScalePercent(
                settings: effective,
                backingScaleFactor: max(2.0, Double(backingScale))
            )
        }()

        if let prev = lastSentResizeSpec,
           abs(pixelW - prev.w) <= 2,
           abs(pixelH - prev.h) <= 2,
           scalePercent == prev.scale {
            return
        }

        resizeDebounceTask = Task { @MainActor in
            if !immediate {
                try? await Task.sleep(nanoseconds: 120_000_000)
            }
            guard !Task.isCancelled else { return }
            let dispatched = session.requestDynamicResize(
                width: pixelW,
                height: pixelH,
                scalePercent: scalePercent
            )
            if dispatched {
                lastSentResizeSpec = (pixelW, pixelH, scalePercent)
            }
        }
    }

    private func triggerReconnect() {
        lastNativeError = ""
        hasPresentedFrame = false
        isConnecting = true
        isReconnecting = false
        reconnectAttempt = 0
        showDelayedSpinner = false
        showStalledReconnect = false
        sessionGeneration &+= 1
        let reconnected = store.reconnectRdpSession(
            for: tab,
            initialViewportSize: lastViewportSize,
            backingScaleFactor: NSScreen.main?.backingScaleFactor ?? 2.0,
            useRetinaOverride: retina2xEnabled
        )
        syncedGeneration = sessionGeneration
        lastSentResizeSpec = (reconnected.initialPixelWidth, reconnected.initialPixelHeight, reconnected.initialScalePercent)
        frameDimensionsText = "\(reconnected.initialPixelWidth)×\(reconnected.initialPixelHeight)"
    }

    public static func makeCGImage(rgba: Data, width: Int, height: Int) -> CGImage? {
        NativeRdpSession.makeBgrxCGImage(bgrx: rgba, width: width, height: height)
    }
}

// MARK: - Off-Main-Thread Metal Presenter & Shared Pipeline

private struct RdpMetalScaleUniforms {
    var scale: SIMD2<Float>
    var exactPixelMatch: UInt32
    var pad: UInt32
}

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
        uint exactPixelMatch;
        uint pad;
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
        constant ScaleUniforms& u [[buffer(0)]],
        texture2d<half, access::sample> tex [[texture(0)]]
    ) {
        if (u.exactPixelMatch != 0u) {
            uint2 coord = uint2(in.position.xy);
            half3 rgb = tex.read(coord).rgb;
            return half4(rgb, 1.0h);
        }
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

/// Dedicated `.userInteractive` off-main-thread Metal uploader & `CAMetalLayer` presenter.
/// Uploads individual `cinvalid` dirty rectangles under `surface_mutex_` and calls `nextDrawable()`
/// strictly off the Main Thread so AppKit mouse/keyboard/scroll events never block on WindowServer vsync.
private final class RdpAsyncMetalPresenter: @unchecked Sendable {
    private let metalQueue = DispatchQueue(
        label: "com.nexusts.rdp.metal-presenter",
        qos: .userInteractive
    )
    private let stateLock = NSLock()

    private weak var metalLayer: CAMetalLayer?
    private var session: NativeRdpSession?
    private var scaleToFit: Bool = true
    private var redirectClipboard: Bool = true
    private var targetDrawableSize: CGSize = .zero
    private var renderPending: Bool = false
    private var forceFullNext: Bool = false
    private var forceRedrawNext: Bool = false

    // Metal-queue-exclusive resources
    private var desktopTexture: MTLTexture?
    private var lastUploadedFrameSeq: UInt64 = 0

    // Telemetry snapshot mirrored back to Main Thread only when non-frame state changes
    private var presentedWidth: Int = 1920
    private var presentedHeight: Int = 1080
    private var hasPresentedFirstFrame: Bool = false
    private var lastNotifiedCursorSeq: UInt64 = 0
    private var lastNotifiedClipSeq: UInt64 = 0
    private var lastNotifiedConnected: Bool?
    private var lastNotifiedConnecting: Bool?
    private var lastNotifiedReconnecting: Bool?
    private var lastNotifiedAttempt: Int = -1

    private let mainWakeSignal: MainThreadRdpFrameSignal

    init(metalLayer: CAMetalLayer, mainWakeSignal: MainThreadRdpFrameSignal) {
        self.metalLayer = metalLayer
        self.mainWakeSignal = mainWakeSignal
    }

    func snapshotPresentationState() -> (hasFrame: Bool, width: Int, height: Int) {
        stateLock.lock()
        defer { stateLock.unlock() }
        return (hasPresentedFirstFrame, presentedWidth, presentedHeight)
    }

    func bindSession(
        _ newSession: NativeRdpSession?,
        scaleToFit: Bool,
        redirectClipboard: Bool,
        resetState: Bool
    ) {
        stateLock.lock()
        self.session = newSession
        self.scaleToFit = scaleToFit
        self.redirectClipboard = redirectClipboard
        if resetState {
            self.hasPresentedFirstFrame = false
            self.forceFullNext = true
            self.forceRedrawNext = true
            self.lastNotifiedCursorSeq = 0
            self.lastNotifiedClipSeq = 0
            self.lastNotifiedConnected = nil
            self.lastNotifiedConnecting = nil
            self.lastNotifiedReconnecting = nil
            self.lastNotifiedAttempt = -1
        }
        stateLock.unlock()

        if resetState {
            metalQueue.async { [weak self] in
                guard let self else { return }
                self.desktopTexture = nil
                self.lastUploadedFrameSeq = 0
                self.performRenderPassOnMetalQueue()
            }
        }
    }

    func updateViewport(drawableSize: CGSize, scaleToFit: Bool, redirectClipboard: Bool) {
        stateLock.lock()
        let sizeChanged = self.targetDrawableSize != drawableSize
        let fitChanged = self.scaleToFit != scaleToFit
        self.targetDrawableSize = drawableSize
        self.scaleToFit = scaleToFit
        self.redirectClipboard = redirectClipboard
        if sizeChanged || fitChanged {
            self.forceRedrawNext = true
        }
        stateLock.unlock()

        if sizeChanged || fitChanged {
            scheduleRenderPass(forceFull: false, checkNonFrameWakeup: false)
        }
    }

    func requestPresent(forceFull: Bool) {
        scheduleRenderPass(forceFull: forceFull, checkNonFrameWakeup: true)
    }

    func handleNativeCallback() {
        scheduleRenderPass(forceFull: false, checkNonFrameWakeup: true)
    }

    private func scheduleRenderPass(forceFull: Bool, checkNonFrameWakeup: Bool) {
        var needsMainWake = false
        var shouldDispatchMetal = false

        stateLock.lock()
        if forceFull {
            self.forceFullNext = true
            self.forceRedrawNext = true
        }
        if !self.renderPending {
            self.renderPending = true
            shouldDispatchMetal = true
        }
        let activeSession = self.session
        let checkClip = self.redirectClipboard
        if checkNonFrameWakeup, let s = activeSession {
            let curSeq = s.cursorSequence
            let clipSeq = checkClip ? s.remoteClipboardSequence : self.lastNotifiedClipSeq
            let conn = s.isConnected
            let connecting = s.isConnecting
            let reconn = s.isReconnecting
            let attempt = s.reconnectAttempt
            if curSeq != self.lastNotifiedCursorSeq
                || (checkClip && clipSeq != self.lastNotifiedClipSeq)
                || self.lastNotifiedConnected != conn
                || self.lastNotifiedConnecting != connecting
                || self.lastNotifiedReconnecting != reconn
                || self.lastNotifiedAttempt != attempt {
                self.lastNotifiedCursorSeq = curSeq
                self.lastNotifiedClipSeq = clipSeq
                self.lastNotifiedConnected = conn
                self.lastNotifiedConnecting = connecting
                self.lastNotifiedReconnecting = reconn
                self.lastNotifiedAttempt = attempt
                needsMainWake = true
            }
        }
        stateLock.unlock()

        if needsMainWake {
            mainWakeSignal.signalFromAnyThread()
        }
        if shouldDispatchMetal {
            metalQueue.async { [weak self] in
                self?.performRenderPassOnMetalQueue()
            }
        }
    }

    private func performRenderPassOnMetalQueue() {
        guard let pipeline = RdpMetalSharedPipeline.shared else {
            stateLock.lock()
            renderPending = false
            stateLock.unlock()
            mainWakeSignal.signalFromAnyThread()
            return
        }

        stateLock.lock()
        renderPending = false
        let activeSession = session
        let mLayer = metalLayer
        let fit = scaleToFit
        let drawSize = targetDrawableSize
        let forceFull = forceFullNext
        let forceRedraw = forceRedrawNext
        forceFullNext = false
        forceRedrawNext = false
        stateLock.unlock()

        guard let activeSession, let mLayer else { return }

        var didUploadPixels = false
        var notifyMainAfterPresent = false
        let currentSeq = activeSession.frameSequence

        if currentSeq > 0 && (forceFull || currentSeq != lastUploadedFrameSeq || desktopTexture == nil) {
            var uploadedW = 0
            var uploadedH = 0
            var uploadedSeq = lastUploadedFrameSeq

            // Hold C++ surface_mutex_ ONLY during fast shared-memory dirty-rect memcpy
            let consumed = activeSession.consumeDirtyRects(forceFull: forceFull || desktopTexture == nil) { slice in
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
                if recreated || forceFull {
                    tex.replace(
                        region: MTLRegionMake2D(0, 0, w, h),
                        mipmapLevel: 0,
                        withBytes: slice.fullFramePixels,
                        bytesPerRow: slice.strideBytes
                    )
                    didUploadPixels = true
                } else {
                    for rect in slice.dirtyRects {
                        let rx = Int(rect.x)
                        let ry = Int(rect.y)
                        let rw = min(Int(rect.w), w - rx)
                        let rh = min(Int(rect.h), h - ry)
                        guard rx >= 0, ry >= 0, rw > 0, rh > 0 else { continue }
                        let byteOffset = ry * slice.strideBytes + rx * 4
                        tex.replace(
                            region: MTLRegionMake2D(rx, ry, rw, rh),
                            mipmapLevel: 0,
                            withBytes: slice.fullFramePixels.advanced(by: byteOffset),
                            bytesPerRow: slice.strideBytes
                        )
                        didUploadPixels = true
                    }
                }

                uploadedW = w
                uploadedH = h
                uploadedSeq = slice.sequence
            }

            if consumed, desktopTexture != nil, uploadedW > 0, uploadedH > 0 {
                lastUploadedFrameSeq = uploadedSeq
                stateLock.lock()
                if !hasPresentedFirstFrame || presentedWidth != uploadedW || presentedHeight != uploadedH {
                    hasPresentedFirstFrame = true
                    presentedWidth = uploadedW
                    presentedHeight = uploadedH
                    notifyMainAfterPresent = true
                }
                stateLock.unlock()
            }
        }

        if (didUploadPixels || forceRedraw),
           let tex = desktopTexture,
           drawSize.width >= 2.0,
           drawSize.height >= 2.0 {
            if mLayer.drawableSize != drawSize {
                mLayer.drawableSize = drawSize
            }
            if let drawable = mLayer.nextDrawable(),
               let cmdBuf = pipeline.commandQueue.makeCommandBuffer() {
                let drawW = max(1.0, Float(drawable.texture.width))
                let drawH = max(1.0, Float(drawable.texture.height))
                let texW = max(1.0, Float(tex.width))
                let texH = max(1.0, Float(tex.height))
                let viewAspect = drawW / drawH
                let texAspect = texW / texH

                let scaleVec: SIMD2<Float>
                if fit {
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

                let fullBleed = scaleVec.x >= 0.9999 && scaleVec.y >= 0.9999
                let exactMatch = fullBleed
                    && drawable.texture.width == tex.width
                    && drawable.texture.height == tex.height

                let passDesc = MTLRenderPassDescriptor()
                passDesc.colorAttachments[0].texture = drawable.texture
                passDesc.colorAttachments[0].loadAction = fullBleed ? .dontCare : .clear
                passDesc.colorAttachments[0].clearColor = MTLClearColor(red: 0.06, green: 0.07, blue: 0.09, alpha: 1.0)
                passDesc.colorAttachments[0].storeAction = .store

                if let enc = cmdBuf.makeRenderCommandEncoder(descriptor: passDesc) {
                    var uniforms = RdpMetalScaleUniforms(
                        scale: scaleVec,
                        exactPixelMatch: exactMatch ? 1 : 0,
                        pad: 0
                    )
                    enc.setRenderPipelineState(pipeline.pipelineState)
                    enc.setVertexBytes(&uniforms, length: MemoryLayout<RdpMetalScaleUniforms>.stride, index: 0)
                    enc.setFragmentBytes(&uniforms, length: MemoryLayout<RdpMetalScaleUniforms>.stride, index: 0)
                    enc.setFragmentTexture(tex, index: 0)
                    enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
                    enc.endEncoding()
                    cmdBuf.present(drawable)
                    cmdBuf.commit()
                }
            }
        }

        if notifyMainAfterPresent {
            mainWakeSignal.signalFromAnyThread()
        }

        // Catch any frame that arrived while we were encoding/presenting
        if activeSession.frameSequence != lastUploadedFrameSeq {
            scheduleRenderPass(forceFull: false, checkNonFrameWakeup: false)
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
    let onTelemetryChange: (Bool, Bool, Bool, Int, Bool, String, String) -> Void

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
    var onTelemetryChange: ((Bool, Bool, Bool, Int, Bool, String, String) -> Void)?

    private var activeGeneration: Int = -1
    private var housekeepingTimer: Timer?
    private var trackingAreaRef: NSTrackingArea?
    private var hasPresentedFrame: Bool = false
    private var frameWakeSignal: MainThreadRdpFrameSignal?

    private var metalSublayer: CAMetalLayer?
    private var asyncMetalPresenter: RdpAsyncMetalPresenter?
    private var lastUploadedFrameSeq: UInt64 = 0
    private var lastDrawablePixelSize: CGSize = .zero

    private var lastAppliedCursorSeq: UInt64 = 0
    private var activeRemoteCursor: NSCursor = .arrow
    private var isPointerInsideView: Bool = false

    private var scrollAccumY: CGFloat = 0
    private var scrollAccumX: CGFloat = 0

    private var lastReportedConnected: Bool?
    private var lastReportedConnecting: Bool?
    private var lastReportedReconnecting: Bool?
    private var lastReportedAttempt: Int = -1
    private var lastReportedHasFrame: Bool?
    private var lastReportedDims: String = ""
    private var cachedNativeError: String = ""
    private var lastReportedError: String = ""

    private var lastPasteboardChangeCount: Int = -1
    private var lastRemoteClipboardSeq: UInt64 = 0
    private var lastPushedPayload: MacPasteboardPayload? = nil
    private var appDidBecomeActiveObserver: NSObjectProtocol?
    private var appDidResignActiveObserver: NSObjectProtocol?
    private var windowDidBecomeKeyObserver: NSObjectProtocol?
    private var windowDidResignKeyObserver: NSObjectProtocol?

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

    deinit {
        if let obs = appDidBecomeActiveObserver {
            NotificationCenter.default.removeObserver(obs)
        }
        if let obs = appDidResignActiveObserver {
            NotificationCenter.default.removeObserver(obs)
        }
        if let obs = windowDidBecomeKeyObserver {
            NotificationCenter.default.removeObserver(obs)
        }
        if let obs = windowDidResignKeyObserver {
            NotificationCenter.default.removeObserver(obs)
        }
    }

    private func setupBackingLayer() {
        wantsLayer = true
        registerForDraggedTypes([
            .fileURL,
            NSPasteboard.PasteboardType("public.file-url"),
            NSPasteboard.PasteboardType("NSFilenamesPboardType")
        ])
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

        let wakeSignal = MainThreadRdpFrameSignal { [weak self] in
            self?.handleEventDrivenWakeup()
        }
        frameWakeSignal = wakeSignal

        if let pipeline = RdpMetalSharedPipeline.shared, let rootLayer = layer {
            let mLayer = CAMetalLayer()
            mLayer.device = pipeline.device
            mLayer.pixelFormat = .bgra8Unorm
            mLayer.framebufferOnly = true
            mLayer.isOpaque = true
            mLayer.presentsWithTransaction = false
            mLayer.displaySyncEnabled = true
            mLayer.maximumDrawableCount = 2
            mLayer.allowsNextDrawableTimeout = false
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
            asyncMetalPresenter = RdpAsyncMetalPresenter(
                metalLayer: mLayer,
                mainWakeSignal: wakeSignal
            )
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
            scrollAccumY = 0
            scrollAccumX = 0
            lastUploadedFrameSeq = 0
            lastAppliedCursorSeq = 0
            activeRemoteCursor = .arrow
            hasPresentedFrame = false
            cachedNativeError = ""
            lastReportedConnected = nil
            lastReportedConnecting = nil
            lastReportedReconnecting = nil
            lastReportedAttempt = -1
            lastReportedHasFrame = nil
            lastReportedDims = ""
            lastReportedError = ""
            layer?.contents = nil
            lastPasteboardChangeCount = -1
            lastPushedPayload = nil
            lastRemoteClipboardSeq = 0

            if redirectClipboard {
                _ = syncLocalClipboardToRemoteIfNeeded(session: session, forcePushToNative: true)
            }

            if let presenter = asyncMetalPresenter {
                presenter.bindSession(
                    session,
                    scaleToFit: scaleToFit,
                    redirectClipboard: redirectClipboard,
                    resetState: true
                )
                session.setFrameReadyHandler { [weak presenter] in
                    presenter?.handleNativeCallback()
                }
            } else {
                let signal = frameWakeSignal
                session.setFrameReadyHandler { [weak signal] in
                    signal?.signalFromAnyThread()
                }
            }
            refreshFrameCursorAndTelemetry(forceFullUpload: true, runSlowHousekeeping: true)
            if let win = window, !(win.firstResponder is NSText), win.firstResponder !== self {
                DispatchQueue.main.async { [weak self, weak win] in
                    guard let self, let win, self.window === win else { return }
                    if !(win.firstResponder is NSText), win.firstResponder !== self {
                        win.makeFirstResponder(self)
                    }
                }
            }
        } else {
            if retinaChanged {
                lastAppliedCursorSeq = 0
                refreshHardwareCursorIfNeeded(session: session)
            }
            if let presenter = asyncMetalPresenter {
                presenter.updateViewport(
                    drawableSize: lastDrawablePixelSize,
                    scaleToFit: scaleToFit,
                    redirectClipboard: redirectClipboard
                )
            } else if fitChanged && !hasPresentedFrame && session.hasValidFrame {
                refreshFrameCursorAndTelemetry(forceFullUpload: true, runSlowHousekeeping: false)
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
            if let session {
                if let presenter = asyncMetalPresenter {
                    session.setFrameReadyHandler { [weak presenter] in
                        presenter?.handleNativeCallback()
                    }
                } else if let signal = frameWakeSignal {
                    session.setFrameReadyHandler { [weak signal] in
                        signal?.signalFromAnyThread()
                    }
                }
            }
            startAppActivationObserverIfNeeded()
            startHousekeepingTimerIfNeeded()
            updateMetalDrawableSizeAndRedrawIfNeeded()
            refreshFrameCursorAndTelemetry(forceFullUpload: !hasPresentedFrame, runSlowHousekeeping: true)
            DispatchQueue.main.async { [weak self, weak win] in
                guard let self, let win, self.window === win else { return }
                if !(win.firstResponder is NSText), win.firstResponder !== self {
                    win.makeFirstResponder(self)
                }
            }
        } else {
            stopAppActivationObserver()
            stopHousekeepingTimer()
        }
    }

    override func viewWillMove(toWindow newWindow: NSWindow?) {
        if newWindow == nil {
            releaseAllHeldModifiers()
            stopAppActivationObserver()
            stopHousekeepingTimer()
            session?.setFrameReadyHandler(nil)
        }
        super.viewWillMove(toWindow: newWindow)
    }

    private func restoreFirstResponderIfAppropriate() {
        guard let win = window,
              win.isKeyWindow,
              win.attachedSheet == nil,
              !isHiddenOrHasHiddenAncestor else {
            return
        }
        if !(win.firstResponder is NSText),
           !(win.firstResponder is RdpPointerAndKeyNSView),
           !(win.firstResponder is TerminalKeyCaptureNSView),
           !(win.firstResponder is VncPointerAndKeyNSView) {
            win.makeFirstResponder(self)
        }
    }

    private func startAppActivationObserverIfNeeded() {
        if appDidBecomeActiveObserver == nil {
            appDidBecomeActiveObserver = NotificationCenter.default.addObserver(
                forName: NSApplication.didBecomeActiveNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                guard let self else { return }
                self.restoreFirstResponderIfAppropriate()
                if self.redirectClipboard, let session = self.session {
                    self.pullRemoteClipboardIfUpdated(session: session)
                    _ = self.syncLocalClipboardToRemoteIfNeeded(session: session, forcePushToNative: false)
                }
            }
        }
        if appDidResignActiveObserver == nil {
            appDidResignActiveObserver = NotificationCenter.default.addObserver(
                forName: NSApplication.didResignActiveNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                guard let self else { return }
                if self.redirectClipboard, let session = self.session {
                    self.pullRemoteClipboardIfUpdated(session: session)
                }
                self.releaseAllHeldModifiers()
            }
        }
        if windowDidBecomeKeyObserver == nil, let win = window {
            windowDidBecomeKeyObserver = NotificationCenter.default.addObserver(
                forName: NSWindow.didBecomeKeyNotification,
                object: win,
                queue: .main
            ) { [weak self] _ in
                guard let self else { return }
                self.restoreFirstResponderIfAppropriate()
                if self.redirectClipboard, let session = self.session {
                    self.pullRemoteClipboardIfUpdated(session: session)
                    _ = self.syncLocalClipboardToRemoteIfNeeded(session: session, forcePushToNative: false)
                }
            }
        }
        if windowDidResignKeyObserver == nil, let win = window {
            windowDidResignKeyObserver = NotificationCenter.default.addObserver(
                forName: NSWindow.didResignKeyNotification,
                object: win,
                queue: .main
            ) { [weak self] _ in
                self?.releaseAllHeldModifiers()
            }
        }
    }

    private func stopAppActivationObserver() {
        if let obs = appDidBecomeActiveObserver {
            NotificationCenter.default.removeObserver(obs)
            appDidBecomeActiveObserver = nil
        }
        if let obs = appDidResignActiveObserver {
            NotificationCenter.default.removeObserver(obs)
            appDidResignActiveObserver = nil
        }
        if let obs = windowDidBecomeKeyObserver {
            NotificationCenter.default.removeObserver(obs)
            windowDidBecomeKeyObserver = nil
        }
        if let obs = windowDidResignKeyObserver {
            NotificationCenter.default.removeObserver(obs)
            windowDidResignKeyObserver = nil
        }
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
        if redirectClipboard, let session {
            _ = syncLocalClipboardToRemoteIfNeeded(session: session, forcePushToNative: false)
        }
    }

    override func mouseExited(with event: NSEvent) {
        isPointerInsideView = false
        NSCursor.arrow.set()
    }

    override func becomeFirstResponder() -> Bool {
        let accepted = super.becomeFirstResponder()
        if accepted, redirectClipboard, let session {
            _ = syncLocalClipboardToRemoteIfNeeded(session: session, forcePushToNative: false)
        }
        return accepted
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
            if let presenter = asyncMetalPresenter {
                presenter.updateViewport(
                    drawableSize: targetSize,
                    scaleToFit: scaleToFit,
                    redirectClipboard: redirectClipboard
                )
            } else {
                mLayer.drawableSize = targetSize
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
            let divisor: CGFloat = (bounds.width >= 64.0 && remoteWidth > 0)
                ? max(1.0, CGFloat(remoteWidth) / bounds.width)
                : (retina2xEnabled ? 2.0 : 1.0)
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

        if let presenter = asyncMetalPresenter {
            if forceFullUpload && session.hasValidFrame {
                presenter.requestPresent(forceFull: true)
            }
            let snap = presenter.snapshotPresentationState()
            if snap.hasFrame {
                hasPresentedFrame = true
                if remoteWidth != snap.width || remoteHeight != snap.height || lastReportedDims.isEmpty {
                    remoteWidth = snap.width
                    remoteHeight = snap.height
                    lastReportedDims = "\(snap.width)×\(snap.height)"
                }
            }
        } else {
            let currentSeq = session.frameSequence
            if currentSeq > 0 && (forceFullUpload || currentSeq != lastUploadedFrameSeq) {
                if let fallback = session.copyDirtyFrameIntoPersistentBuffer(forceFull: forceFullUpload || !hasPresentedFrame) {
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
        }

        let connected = session.isConnected
        let connecting = session.isConnecting
        let reconnecting = session.isReconnecting
        let attempt = session.reconnectAttempt
        let stateTransitioned = (connected != lastReportedConnected)
            || (connecting != lastReportedConnecting)
            || (reconnecting != lastReportedReconnecting)
        let justConnected = connected && (lastReportedConnected != true)
        let reconnectedNow = connected && !reconnecting && (lastReportedReconnecting == true)
        let forceInitialClipSync = (justConnected && session.remoteClipboardSequence == 0) || reconnectedNow

        // Pull remote clipboard immediately on event-driven wakeup (<1ms after Windows Ctrl+C / Cmd+C)
        if connected && redirectClipboard {
            pullRemoteClipboardIfUpdated(session: session)
        }

        if runSlowHousekeeping || stateTransitioned {
            cachedNativeError = session.lastErrorMessage()
            if connected && redirectClipboard {
                _ = syncLocalClipboardToRemoteIfNeeded(
                    session: session,
                    forcePushToNative: forceInitialClipSync
                )
                pullRemoteClipboardIfUpdated(session: session)
            }
        }

        let currentDims = lastReportedDims.isEmpty ? "\(remoteWidth)×\(remoteHeight)" : lastReportedDims
        if lastReportedConnected != connected
            || lastReportedConnecting != connecting
            || lastReportedReconnecting != reconnecting
            || lastReportedAttempt != attempt
            || lastReportedHasFrame != hasPresentedFrame
            || lastReportedError != cachedNativeError {
            lastReportedConnected = connected
            lastReportedConnecting = connecting
            lastReportedReconnecting = reconnecting
            lastReportedAttempt = attempt
            lastReportedHasFrame = hasPresentedFrame
            lastReportedError = cachedNativeError

            let snapConnected = connected
            let snapConnecting = connecting
            let snapReconnecting = reconnecting
            let snapAttempt = attempt
            let snapHasFrame = hasPresentedFrame
            let snapDims = currentDims
            let snapError = cachedNativeError
            // Always dispatch asynchronously outside makeNSView/updateNSView so SwiftUI never discards @State mutations
            DispatchQueue.main.async { [weak self] in
                self?.onTelemetryChange?(
                    snapConnected,
                    snapConnecting,
                    snapReconnecting,
                    snapAttempt,
                    snapHasFrame,
                    snapDims,
                    snapError
                )
            }
        }
    }

    @discardableResult
    private func syncLocalClipboardToRemoteIfNeeded(
        session: NativeRdpSession,
        forcePushToNative: Bool
    ) -> Bool {
        let pb = NSPasteboard.general
        let pbCount = pb.changeCount
        let changeCountAdvanced = (pbCount != lastPasteboardChangeCount)
        guard forcePushToNative || changeCountAdvanced else {
            return false
        }
        lastPasteboardChangeCount = pbCount

        let payload = MacPasteboardClipboardExtractor.extractClipboardPayload(from: pb)
        switch payload {
        case .files(let filePaths):
            if changeCountAdvanced || forcePushToNative || lastPushedPayload != payload {
                lastPushedPayload = payload
                session.pushLocalClipboardFiles(filePaths)
                return true
            }
            return false
        case .text(let localText):
            if changeCountAdvanced || forcePushToNative || lastPushedPayload != payload {
                lastPushedPayload = payload
                session.pushLocalClipboard(localText)
                return true
            }
            return false
        case .fileIntentEmpty, .empty:
            // Never fall back to Finder's bare basename .string when .fileURL is present for a missing/unmounted item
            return false
        }
    }

    private func pullRemoteClipboardIfUpdated(session: NativeRdpSession) {
        let remoteSeq = session.remoteClipboardSequence
        guard remoteSeq != lastRemoteClipboardSeq else { return }
        lastRemoteClipboardSeq = remoteSeq

        switch session.remoteClipboardKind {
        case .files:
            let stagedPaths = session.pullRemoteClipboardFiles()
            guard !stagedPaths.isEmpty else { return }
            let existingPaths = stagedPaths.filter { FileManager.default.fileExists(atPath: $0) }
            guard !existingPaths.isEmpty else { return }
            let existingUrls: [NSURL] = existingPaths.map { NSURL(fileURLWithPath: $0) }
            let pb = NSPasteboard.general
            pb.clearContents()
            pb.writeObjects(existingUrls)
            lastPasteboardChangeCount = pb.changeCount
            lastPushedPayload = .files(existingPaths)
        case .text:
            let remoteText = session.pullRemoteClipboard()
            guard !remoteText.isEmpty else { return }
            let pb = NSPasteboard.general
            if pb.string(forType: .string) != remoteText {
                pb.clearContents()
                pb.setString(remoteText, forType: .string)
            }
            lastPasteboardChangeCount = pb.changeCount
            lastPushedPayload = .text(remoteText)
        case .none:
            // Remote clipboard changed ownership (e.g. remote screenshot or huge >16 GiB file);
            // preserve lastPasteboardChangeCount so pressing Cmd+V / Ctrl+V inside Windows does not
            // overwrite the remote clipboard unless the user actually copies a new item on macOS.
            lastPushedPayload = nil
        }
    }

    // MARK: - Finder File & Folder Drag-and-Drop onto Remote Desktop

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        draggingUpdated(sender)
    }

    override func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation {
        guard redirectClipboard,
              let session,
              session.isConnected,
              case .files(let paths) = MacPasteboardClipboardExtractor.extractClipboardPayload(from: sender.draggingPasteboard),
              !paths.isEmpty else {
            return []
        }
        let local = convert(sender.draggingLocation, from: nil)
        let (rx, ry) = MacRdpScancodeMapper.mapViewportPoint(
            localX: local.x,
            localY: local.y,
            viewWidth: bounds.width,
            viewHeight: bounds.height,
            remoteWidth: remoteWidth,
            remoteHeight: remoteHeight,
            scaleToFit: scaleToFit,
            originBottomLeft: true
        )
        session.sendPointerEvent(flags: 0x0800, x: rx, y: ry)
        return .copy
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        guard redirectClipboard,
              let session,
              session.isConnected,
              case .files(let paths) = MacPasteboardClipboardExtractor.extractClipboardPayload(from: sender.draggingPasteboard),
              !paths.isEmpty else {
            return false
        }
        window?.makeFirstResponder(self)
        let local = convert(sender.draggingLocation, from: nil)
        let (rx, ry) = MacRdpScancodeMapper.mapViewportPoint(
            localX: local.x,
            localY: local.y,
            viewWidth: bounds.width,
            viewHeight: bounds.height,
            remoteWidth: remoteWidth,
            remoteHeight: remoteHeight,
            scaleToFit: scaleToFit,
            originBottomLeft: true
        )
        session.sendPointerEvent(flags: 0x0800, x: rx, y: ry)

        let nsUrls: [NSURL] = paths.map { NSURL(fileURLWithPath: $0) }
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.writeObjects(nsUrls)
        lastPasteboardChangeCount = pb.changeCount
        lastPushedPayload = .files(paths)

        session.pushLocalClipboardFiles(paths)
        session.requestSynchronizedPaste()
        return true
    }

    // MARK: - Aspect-Aware Coordinate Mapping & Zero-Jitter Native Coalesced Pointer Input

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
        if redirectClipboard, let session {
            _ = syncLocalClipboardToRemoteIfNeeded(session: session, forcePushToNative: false)
        }
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
        if redirectClipboard, let session {
            _ = syncLocalClipboardToRemoteIfNeeded(session: session, forcePushToNative: false)
        }
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
        switch event.buttonNumber {
        case 3:
            // Mouse Button 4 (Browser Back -> PTRXFLAGS_DOWN | PTRXFLAGS_BUTTON1)
            session?.sendExtendedPointerEvent(xflags: 0x8000 | 0x0001, x: x, y: y)
        case 4:
            // Mouse Button 5 (Browser Forward -> PTRXFLAGS_DOWN | PTRXFLAGS_BUTTON2)
            session?.sendExtendedPointerEvent(xflags: 0x8000 | 0x0002, x: x, y: y)
        default:
            session?.sendPointerEvent(flags: 0x8000 | 0x4000, x: x, y: y)
        }
    }

    override func otherMouseUp(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        switch event.buttonNumber {
        case 3:
            session?.sendExtendedPointerEvent(xflags: 0x0001, x: x, y: y)
        case 4:
            session?.sendExtendedPointerEvent(xflags: 0x0002, x: x, y: y)
        default:
            session?.sendPointerEvent(flags: 0x4000, x: x, y: y)
        }
    }

    override func otherMouseDragged(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x0800, x: x, y: y)
    }

    override func scrollWheel(with event: NSEvent) {
        if event.phase == .began || event.momentumPhase == .began {
            scrollAccumY = 0
            scrollAccumX = 0
        }

        let (x, y) = scaledPoint(for: event)
        let rawY = event.scrollingDeltaY
        if abs(rawY) > 0.001 {
            if event.hasPreciseScrollingDeltas {
                scrollAccumY += rawY * 3.0
                if abs(scrollAccumY) >= 4.0 {
                    let emitY = Int(scrollAccumY.rounded(.towardZero))
                    scrollAccumY -= CGFloat(emitY)
                    if let flags = MacRdpScancodeMapper.encodeWheelFlags(delta: emitY, horizontal: false) {
                        session?.sendPointerEvent(flags: flags, x: x, y: y)
                    }
                }
            } else {
                scrollAccumY = 0
                let s = Int((rawY * 120.0).rounded())
                let discreteY = s == 0 ? (rawY > 0 ? 120 : -120) : s
                if let flags = MacRdpScancodeMapper.encodeWheelFlags(delta: discreteY, horizontal: false) {
                    session?.sendPointerEvent(flags: flags, x: x, y: y)
                }
            }
        }

        let rawX = -event.scrollingDeltaX
        if abs(rawX) > 0.001 {
            if event.hasPreciseScrollingDeltas {
                scrollAccumX += rawX * 3.0
                if abs(scrollAccumX) >= 4.0 {
                    let emitX = Int(scrollAccumX.rounded(.towardZero))
                    scrollAccumX -= CGFloat(emitX)
                    if let flags = MacRdpScancodeMapper.encodeWheelFlags(delta: emitX, horizontal: true) {
                        session?.sendPointerEvent(flags: flags, x: x, y: y)
                    }
                }
            } else {
                scrollAccumX = 0
                let s = Int((rawX * 120.0).rounded())
                let discreteX = s == 0 ? (rawX > 0 ? 120 : -120) : s
                if let flags = MacRdpScancodeMapper.encodeWheelFlags(delta: discreteX, horizontal: true) {
                    session?.sendPointerEvent(flags: flags, x: x, y: y)
                }
            }
        }
    }

    // MARK: - macOS Command (⌘C / ⌘V / ⌘X / ⌘A / ⌘Z) -> Windows Ctrl Chord & Edit Menu Actions

    private func resolveMacCommandShortcut(for event: NSEvent) -> MacRdpScancodeMapper.MacCommandRemoteShortcut? {
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        return MacRdpScancodeMapper.mapMacCommandShortcut(
            keyCode: event.keyCode,
            charactersIgnoringModifiers: event.charactersIgnoringModifiers,
            hasCommand: flags.contains(.command),
            hasShift: flags.contains(.shift),
            hasControl: flags.contains(.control),
            hasOption: flags.contains(.option)
        )
    }

    private func isPlainControlPaste(for event: NSEvent) -> Bool {
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        guard flags.contains(.control),
              !flags.contains(.command),
              !flags.contains(.option),
              !flags.contains(.shift) else {
            return false
        }
        if event.keyCode == 0x09 {
            return true
        }
        return event.charactersIgnoringModifiers?.lowercased() == "v"
    }

    private func sendRemoteCtrlChord(scancode: UInt16) {
        guard let session else { return }
        for code: UInt16 in [0x37, 0x36, 0x38, 0x3C] where heldModifierKeyCodes.remove(code) != nil {
            if let rel = MacRdpScancodeMapper.mapModifierKeyCode(code, captureRemoteShortcuts: true) {
                session.sendScancode(rel.scancode, keyDown: false, extended: rel.extended)
            }
        }
        let physicalCtrlHeld = heldModifierKeyCodes.contains(0x3B) || heldModifierKeyCodes.contains(0x3E)
        if !physicalCtrlHeld {
            session.sendScancode(0x1D, keyDown: true, extended: false)
        }
        session.sendScancode(scancode, keyDown: true, extended: false)
        session.sendScancode(scancode, keyDown: false, extended: false)
        if !physicalCtrlHeld {
            session.sendScancode(0x1D, keyDown: false, extended: false)
        }
    }

    private func executeSynchronizedPaste() {
        guard let session else { return }
        if redirectClipboard {
            for code: UInt16 in [0x37, 0x36, 0x38, 0x3C] where heldModifierKeyCodes.remove(code) != nil {
                if let rel = MacRdpScancodeMapper.mapModifierKeyCode(code, captureRemoteShortcuts: true) {
                    session.sendScancode(rel.scancode, keyDown: false, extended: rel.extended)
                }
            }
            _ = syncLocalClipboardToRemoteIfNeeded(session: session, forcePushToNative: false)
            session.requestSynchronizedPaste()
        } else {
            sendRemoteCtrlChord(scancode: MacRdpScancodeMapper.MacCommandRemoteShortcut.paste.windowsCtrlScancode)
        }
    }

    func executeRemoteCommandShortcut(_ shortcut: MacRdpScancodeMapper.MacCommandRemoteShortcut) {
        if shortcut == .paste {
            executeSynchronizedPaste()
            return
        }
        sendRemoteCtrlChord(scancode: shortcut.windowsCtrlScancode)
    }

    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        guard window?.firstResponder === self else {
            return super.performKeyEquivalent(with: event)
        }
        if let shortcut = resolveMacCommandShortcut(for: event) {
            executeRemoteCommandShortcut(shortcut)
            return true
        }
        return super.performKeyEquivalent(with: event)
    }

    @objc func copy(_ sender: Any?) {
        executeRemoteCommandShortcut(.copy)
    }

    @objc func paste(_ sender: Any?) {
        executeRemoteCommandShortcut(.paste)
    }

    @objc func cut(_ sender: Any?) {
        executeRemoteCommandShortcut(.cut)
    }

    override func selectAll(_ sender: Any?) {
        executeRemoteCommandShortcut(.selectAll)
    }

    @objc func undo(_ sender: Any?) {
        executeRemoteCommandShortcut(.undo)
    }

    @objc func redo(_ sender: Any?) {
        executeRemoteCommandShortcut(.redo)
    }

    // MARK: - PC XT Set-1 Scancode & Modifier Translation

    override func keyDown(with event: NSEvent) {
        if let shortcut = resolveMacCommandShortcut(for: event) {
            executeRemoteCommandShortcut(shortcut)
            return
        }
        if redirectClipboard && isPlainControlPaste(for: event) {
            executeSynchronizedPaste()
            return
        }
        if !captureRemoteShortcuts && event.modifierFlags.contains(.command) {
            super.keyDown(with: event)
            return
        }
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        guard let mapped = MacRdpScancodeMapper.mapKeyCode(
            event.keyCode,
            captureRemoteShortcuts: captureRemoteShortcuts
        ) else {
            return
        }
        if flags.contains(.control) && heldModifierKeyCodes.isDisjoint(with: [0x3B, 0x3E]) {
            heldModifierKeyCodes.insert(0x3B)
            session?.sendScancode(0x1D, keyDown: true, extended: false)
        }
        if captureRemoteShortcuts && flags.contains(.command) && heldModifierKeyCodes.isDisjoint(with: [0x37, 0x36]) {
            heldModifierKeyCodes.insert(0x37)
            session?.sendScancode(0x5B, keyDown: true, extended: true)
        }
        session?.sendScancode(mapped.scancode, keyDown: true, extended: mapped.extended)
    }

    override func keyUp(with event: NSEvent) {
        if resolveMacCommandShortcut(for: event) != nil {
            return
        }
        if redirectClipboard && isPlainControlPaste(for: event) {
            return
        }
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
        // Never send VK_LWIN down on bare Command (0x37/0x36) flagsChanged: on macOS, Command is the
        // host shortcut and Ctrl-chord modifier (⌘C, ⌘V, ⌘X, ⌘A, ⌘Z, ⌘Tab). Only release VK_LWIN here
        // if an explicit Win+<Key> chord (e.g. ⌘E / ⌘R) pressed it in keyDown(with:).
        if keyCode == 0x37 || keyCode == 0x36 {
            let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
            if !flags.contains(.command) {
                for code: UInt16 in [0x37, 0x36] where heldModifierKeyCodes.remove(code) != nil {
                    if let rel = MacRdpScancodeMapper.mapModifierKeyCode(code, captureRemoteShortcuts: true) {
                        session?.sendScancode(rel.scancode, keyDown: false, extended: rel.extended)
                    }
                }
            }
            return
        }

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
        if !heldModifierKeyCodes.isEmpty {
            let toRelease = heldModifierKeyCodes
            heldModifierKeyCodes.removeAll()
            for code in toRelease {
                if let rel = MacRdpScancodeMapper.mapModifierKeyCode(code, captureRemoteShortcuts: true) {
                    session?.sendScancode(rel.scancode, keyDown: false, extended: rel.extended)
                }
            }
        }
        session?.releaseAllKeys()
    }
}
