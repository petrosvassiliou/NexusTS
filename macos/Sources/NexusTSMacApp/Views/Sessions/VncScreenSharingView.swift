import AppKit
import CoreGraphics
import Network
import NexusTSCore
import SwiftUI

public struct VncScreenSharingView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let tab: WorkspaceSessionTab

    @State private var framebuffer = NativeVncFramebuffer(width: 1280, height: 800)
    @State private var renderedImage: CGImage? = nil
    @State private var statusMessage: String = "Ready for RFB 3.8 / Apple Screen Sharing"
    @State private var isConnecting: Bool = false
    @State private var serverBanner: String = ""

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    private var vncSettings: VncProtocolSettings {
        if case .vnc(let s) = tab.connection.settings { return s }
        return VncProtocolSettings(appleArdDiffieHellmanAuth: tab.connection.protocolType == .APPLE_SCREEN_SHARING)
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                ProtocolSquircleIcon(protocolType: tab.connection.protocolType, size: 22)

                Text("\(tab.connection.name) (\(tab.connection.host):\(vncSettings.port))")
                    .font(.callout.weight(.semibold))

                if vncSettings.appleArdDiffieHellmanAuth {
                    EnvironmentCapsuleBadge("ARD DH Auth")
                }

                Text(statusMessage)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                Spacer()

                Button {
                    probeRfbHandshake()
                } label: {
                    Label("Handshake RFB", systemImage: "bolt.horizontal.circle")
                }
                .controlSize(.small)
                .disabled(isConnecting)

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

                if let cgImage = renderedImage {
                    Image(decorative: cgImage, scale: 1.0)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .padding(8)
                } else {
                    VStack(spacing: 14) {
                        Image(systemName: tab.connection.protocolType.sfSymbolName)
                            .font(.system(size: 44, weight: .medium))
                            .foregroundStyle(Color(nsColor: .systemPurple))

                        Text(
                            tab.connection.protocolType == .APPLE_SCREEN_SHARING
                                ? "Apple Remote Desktop (ARD) & Screen Sharing"
                                : "RFB 3.8 VNC Framebuffer Engine"
                        )
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.white)

                        if !serverBanner.isEmpty {
                            Text("Remote RFB Banner: \(serverBanner)")
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(Color(nsColor: .systemGreen))
                        } else {
                            Text("Encodings: \(vncSettings.preferredEncodings.joined(separator: " • "))")
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.65))
                        }

                        HStack(spacing: 12) {
                            Button("Connect Embedded RFB") {
                                probeRfbHandshake()
                            }
                            .controlSize(.large)

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
            probeRfbHandshake()
        }
    }

    private func launchNativeAppleScreenSharingApp() {
        let resolved = PropertyInheritanceResolver.resolve(connection: tab.connection, in: store.document)
        let user = tab.adHocUsernameOverride ?? resolved.effectiveCredential?.username ?? ""
        var comps = URLComponents()
        comps.scheme = "vnc"
        if !user.isEmpty {
            comps.user = user
        }
        comps.host = tab.connection.host
        if vncSettings.port != 5900 {
            comps.port = vncSettings.port
        }
        if let url = comps.url {
            NSWorkspace.shared.open(url)
            store.appendAudit("Screen Sharing", "Opened \(url.absoluteString) via native macOS Screen Sharing.app.")
        }
    }

    private func probeRfbHandshake() {
        guard !tab.connection.host.isEmpty else { return }
        isConnecting = true
        statusMessage = "Probing RFB \(tab.connection.host):\(vncSettings.port)…"
        let host = tab.connection.host
        let port = UInt16(clamping: vncSettings.port)

        Task.detached(priority: .userInitiated) {
            let banner = NativeNetworkDiagnostics.grabServiceBanner(host: host, port: port, timeoutMs: 850)
            await MainActor.run {
                isConnecting = false
                if banner.hasPrefix("RFB") {
                    serverBanner = banner
                    statusMessage = "Negotiated \(banner)"
                    renderPreviewFramebuffer()
                } else if let hit = NativeNetworkDiagnostics.probeTcpPort(host: host, port: port, timeoutMs: 600), hit.open {
                    statusMessage = "TCP \(port) reachable (\(hit.connectLatencyMs) ms)"
                    renderPreviewFramebuffer()
                } else {
                    statusMessage = "Host \(host):\(port) unreachable or awaiting Screen Sharing handoff"
                }
            }
        }
    }

    private func renderPreviewFramebuffer() {
        let pixels = framebuffer.copyRgbaPixels()
        renderedImage = RdpSessionView.makeCGImage(
            rgba: pixels,
            width: framebuffer.width,
            height: framebuffer.height
        )
    }
}
