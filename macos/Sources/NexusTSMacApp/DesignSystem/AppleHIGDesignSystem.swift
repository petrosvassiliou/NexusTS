import AppKit
import NexusTSCore
import SwiftUI

// MARK: - Apple Human Interface Guidelines Semantic Tokens & Protocol Mapping

public extension VaultProtocolType {
    var sfSymbolName: String {
        switch self {
        case .RDP, .WINDOWS_TERMINAL_SERVICES:
            return "display"
        case .SSH:
            return "terminal"
        case .TELNET:
            return "apple.terminal"
        case .SERIAL:
            return "cable.connector.horizontal"
        case .SFTP, .FTP, .FTPS, .SCP:
            return "arrow.left.arrow.right.square"
        case .VNC:
            return "rectangle.on.rectangle"
        case .APPLE_SCREEN_SHARING:
            return "macwindow.on.rectangle"
        case .WEB_PAGE:
            return "safari"
        case .VMWARE_VSPHERE, .PROXMOX_VE, .HYPER_V:
            return "server.rack"
        case .AWS_EC2, .AZURE_VM:
            return "cloud"
        case .KUBERNETES_POD, .DOCKER_CONTAINER:
            return "shippingbox"
        case .UTILITY_PING, .UTILITY_TRACEROUTE, .UTILITY_PORT_SCANNER, .UTILITY_WAKE_ON_LAN:
            return "waveform.path.ecg"
        }
    }

    var appleSystemTint: Color {
        switch self {
        case .RDP, .WINDOWS_TERMINAL_SERVICES:
            return Color(nsColor: .systemBlue)
        case .SSH, .TELNET:
            return Color(nsColor: .systemIndigo)
        case .SERIAL:
            return Color(nsColor: .systemGreen)
        case .SFTP, .FTP, .FTPS, .SCP:
            return Color(nsColor: .systemOrange)
        case .VNC, .APPLE_SCREEN_SHARING:
            return Color(nsColor: .systemPink)
        case .WEB_PAGE:
            return Color(nsColor: .systemPurple)
        case .VMWARE_VSPHERE, .PROXMOX_VE, .HYPER_V, .AWS_EC2, .AZURE_VM:
            return Color(nsColor: .systemCyan)
        case .KUBERNETES_POD, .DOCKER_CONTAINER:
            return Color(nsColor: .systemTeal)
        case .UTILITY_PING, .UTILITY_TRACEROUTE, .UTILITY_PORT_SCANNER, .UTILITY_WAKE_ON_LAN:
            return Color(nsColor: .systemMint)
        }
    }
}

public enum HIGColorUtilities {
    public static let finderTagPalette: [(label: String, hex: String?)] = [
        ("Automatic", nil),
        ("Blue", "#0A84FF"),
        ("Indigo", "#5E5CE6"),
        ("Purple", "#BF5AF2"),
        ("Pink", "#FF375F"),
        ("Orange", "#FF9F0A"),
        ("Green", "#30D158"),
        ("Teal", "#64D2FF")
    ]

    public static func color(fromHex hex: String?) -> Color? {
        guard let hex = hex?.trimmingCharacters(in: .whitespacesAndNewlines),
              !hex.isEmpty else { return nil }
        let cleaned = hex.replacingOccurrences(of: "#", with: "")
        guard cleaned.count == 6, let rgb = UInt64(cleaned, radix: 16) else { return nil }
        let r = Double((rgb >> 16) & 0xFF) / 255.0
        let g = Double((rgb >> 8) & 0xFF) / 255.0
        let b = Double(rgb & 0xFF) / 255.0
        return Color(red: r, green: g, blue: b)
    }
}

// MARK: - Reusable Apple HIG Components

public struct ProtocolSquircleIcon: View {
    public let protocolType: VaultProtocolType
    public var customHex: String? = nil
    public var size: CGFloat = 26

    public init(protocolType: VaultProtocolType, customHex: String? = nil, size: CGFloat = 26) {
        self.protocolType = protocolType
        self.customHex = customHex
        self.size = size
    }

    public var body: some View {
        let tint = HIGColorUtilities.color(fromHex: customHex) ?? protocolType.appleSystemTint
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
                .fill(tint.opacity(0.15))
                .overlay(
                    RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
                        .strokeBorder(tint.opacity(0.28), lineWidth: 0.75)
                )
            Image(systemName: protocolType.sfSymbolName)
                .font(.system(size: size * 0.48, weight: .semibold))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(tint)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

public struct KeycapBadge: View {
    public let shortcut: String

    public init(_ shortcut: String) {
        self.shortcut = shortcut
    }

    public var body: some View {
        Text(shortcut)
            .font(.system(size: 11, weight: .medium, design: .rounded))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(Color(nsColor: .quaternaryLabelColor).opacity(0.35))
            )
    }
}

public struct EnvironmentCapsuleBadge: View {
    public let text: String

    public init(_ text: String) {
        self.text = text
    }

    private var badgeTint: Color {
        let upper = text.uppercased()
        if upper.contains("PROD") { return Color(nsColor: .systemRed) }
        if upper.contains("STAG") { return Color(nsColor: .systemOrange) }
        return Color(nsColor: .systemTeal)
    }

    public var body: some View {
        Text(text.uppercased())
            .font(.system(size: 9.5, weight: .bold, design: .rounded))
            .foregroundStyle(badgeTint)
            .padding(.horizontal, 5.5)
            .padding(.vertical, 1.5)
            .background(
                Capsule()
                    .fill(badgeTint.opacity(0.14))
            )
    }
}

public struct MacVibrancySurface: NSViewRepresentable {
    public var material: NSVisualEffectView.Material
    public var blendingMode: NSVisualEffectView.BlendingMode

    public init(
        material: NSVisualEffectView.Material = .hudWindow,
        blendingMode: NSVisualEffectView.BlendingMode = .withinWindow
    ) {
        self.material = material
        self.blendingMode = blendingMode
    }

    public func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = material
        view.blendingMode = blendingMode
        view.state = .active
        return view
    }

    public func updateNSView(_ nsView: NSVisualEffectView, context: Context) {
        nsView.material = material
        nsView.blendingMode = blendingMode
    }
}
