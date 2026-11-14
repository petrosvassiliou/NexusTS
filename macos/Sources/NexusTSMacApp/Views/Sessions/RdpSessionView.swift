import AppKit
import CoreGraphics
import NexusTSCore
import SwiftUI

public struct RdpSessionView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let tab: WorkspaceSessionTab

    @State private var currentFrameImage: CGImage? = nil
    @State private var lastSeq: UInt64 = 0
    @State private var isConnected: Bool = false
    @State private var scaleToFit: Bool = true
    @State private var showHudBar: Bool = true
    @State private var frameDimensionsText: String = "1920×1080"

    private let frameTimer = Timer.publish(every: 1.0 / 30.0, on: .main, in: .common).autoconnect()

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    private var rdpSettings: RdpProtocolSettings {
        if case .rdp(let s) = tab.connection.settings { return s }
        return RdpProtocolSettings()
    }

    public var body: some View {
        let session = store.rdpController(for: tab)

        GeometryReader { geo in
            ZStack(alignment: .top) {
                Color(red: 0.06, green: 0.07, blue: 0.09)

                if let cgImage = currentFrameImage {
                    Image(decorative: cgImage, scale: 1.0)
                        .resizable()
                        .aspectRatio(contentMode: scaleToFit ? .fit : .fill)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .overlay {
                            RdpInteractiveSurfaceNSViewRepresentable(
                                session: session,
                                remoteWidth: cgImage.width,
                                remoteHeight: cgImage.height
                            )
                        }
                } else {
                    VStack(spacing: 14) {
                        ProtocolSquircleIcon(protocolType: .RDP, size: 52)
                        Text("FreeRDP 3.x Native Engine • \(tab.connection.host):\(rdpSettings.port)")
                            .font(.headline)
                            .foregroundStyle(.white)
                        Text(
                            isConnected
                                ? "Negotiating RDPGFX / H.264 AVC444 Desktop Surface…"
                                : "Connecting with NLA / CredSSP + TLS 1.3…"
                        )
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.68))

                        HStack(spacing: 10) {
                            Button("Reconnect") {
                                _ = session.connect()
                            }
                            .buttonStyle(.borderedProminent)

                            Button("Edit RDP Settings…") {
                                store.editingConnection = tab.connection
                            }
                        }
                    }
                    .padding(28)
                }

                // Translucent Apple HIG Floating Remote Desktop HUD Pill
                if showHudBar {
                    HStack(spacing: 12) {
                        Circle()
                            .fill(isConnected ? Color(nsColor: .systemGreen) : Color(nsColor: .systemOrange))
                            .frame(width: 8, height: 8)

                        Text(tab.connection.name)
                            .font(.caption.weight(.semibold))

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

                        Divider().frame(height: 14)

                        Button("Ctrl+Alt+Del") {
                            session.sendCtrlAltDel()
                        }
                        .controlSize(.small)

                        Button {
                            if let str = NSPasteboard.general.string(forType: .string), !str.isEmpty {
                                session.pushLocalClipboard(str)
                            }
                        } label: {
                            Label("Push Clipboard", systemImage: "doc.on.clipboard")
                                .font(.caption)
                        }
                        .controlSize(.small)

                        Toggle("Fit", isOn: $scaleToFit)
                            .toggleStyle(.checkbox)
                            .font(.caption)

                        Button {
                            showHudBar = false
                        } label: {
                            Image(systemName: "chevron.up")
                                .font(.caption2)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(.ultraThinMaterial, in: Capsule())
                    .overlay(Capsule().strokeBorder(Color.primary.opacity(0.15), lineWidth: 0.75))
                    .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
                    .padding(.top, 10)
                } else {
                    Button {
                        showHudBar = true
                    } label: {
                        Image(systemName: "chevron.down.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.white.opacity(0.65))
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 6)
                }
            }
            .onChange(of: geo.size) { _, newSize in
                guard rdpSettings.resizeBehavior == .DYNAMIC_DISP_DVC else { return }
                let scale = NSScreen.main?.backingScaleFactor ?? 2.0
                let pixelW = Int(newSize.width * (rdpSettings.hiDpiEnabled ? scale : 1.0))
                let pixelH = Int(newSize.height * (rdpSettings.hiDpiEnabled ? scale : 1.0))
                _ = session.requestDynamicResize(
                    width: pixelW,
                    height: pixelH,
                    scalePercent: rdpSettings.hiDpiEnabled ? 200 : 100
                )
            }
            .onReceive(frameTimer) { _ in
                isConnected = session.isConnected
                let seq = session.frameSequence
                guard seq != lastSeq else { return }
                lastSeq = seq
                if let frame = session.copyLatestFrame(),
                   let img = Self.makeCGImage(rgba: frame.rgba, width: frame.width, height: frame.height) {
                    currentFrameImage = img
                    frameDimensionsText = "\(frame.width)×\(frame.height)"
                }
            }
        }
    }

    static func makeCGImage(rgba: Data, width: Int, height: Int) -> CGImage? {
        guard width > 0, height > 0, rgba.count >= width * height * 4 else { return nil }
        guard let provider = CGDataProvider(data: rgba as CFData) else { return nil }
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
        return CGImage(
            width: width,
            height: height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: bitmapInfo,
            provider: provider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        )
    }
}

private struct RdpInteractiveSurfaceNSViewRepresentable: NSViewRepresentable {
    let session: NativeRdpSession
    let remoteWidth: Int
    let remoteHeight: Int

    func makeNSView(context: Context) -> RdpPointerAndKeyNSView {
        let view = RdpPointerAndKeyNSView()
        view.session = session
        view.remoteWidth = remoteWidth
        view.remoteHeight = remoteHeight
        return view
    }

    func updateNSView(_ nsView: RdpPointerAndKeyNSView, context: Context) {
        nsView.session = session
        nsView.remoteWidth = remoteWidth
        nsView.remoteHeight = remoteHeight
    }
}

final class RdpPointerAndKeyNSView: NSView {
    var session: NativeRdpSession?
    var remoteWidth: Int = 1920
    var remoteHeight: Int = 1080

    override var acceptsFirstResponder: Bool { true }

    private func scaledPoint(for event: NSEvent) -> (Int, Int) {
        let local = convert(event.locationInWindow, from: nil)
        let w = max(1.0, bounds.width)
        let h = max(1.0, bounds.height)
        let xRatio = min(1.0, max(0.0, local.x / w))
        let yRatio = min(1.0, max(0.0, 1.0 - (local.y / h)))
        return (Int(xRatio * CGFloat(remoteWidth)), Int(yRatio * CGFloat(remoteHeight)))
    }

    override func mouseMoved(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x0800, x: x, y: y) // PTR_FLAGS_MOVE
    }

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x8000 | 0x1000, x: x, y: y) // DOWN | BUTTON1
    }

    override func mouseUp(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x1000, x: x, y: y) // BUTTON1 release
    }

    override func rightMouseDown(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x8000 | 0x2000, x: x, y: y) // DOWN | BUTTON2
    }

    override func rightMouseUp(with event: NSEvent) {
        let (x, y) = scaledPoint(for: event)
        session?.sendPointerEvent(flags: 0x2000, x: x, y: y)
    }

    override func keyDown(with event: NSEvent) {
        session?.sendScancode(event.keyCode, keyDown: true, extended: false)
    }

    override func keyUp(with event: NSEvent) {
        session?.sendScancode(event.keyCode, keyDown: false, extended: false)
    }
}
