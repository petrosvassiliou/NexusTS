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

// MARK: - Apple Liquid Glass Design System (macOS Tahoe / Latest Apple HIG)

public enum LiquidGlassProminence: Sendable {
    case subtle
    case regular
    case elevated
}

public enum LiquidGlassMetrics {
    public static let windowCornerRadius: CGFloat = 18
    public static let modalCornerRadius: CGFloat = 20
    public static let cardCornerRadius: CGFloat = 14
    public static let rowCornerRadius: CGFloat = 8
    public static let hoverAnimation: Animation = .spring(response: 0.22, dampingFraction: 0.84)

    public static func specularRimGradient(colorScheme: ColorScheme, intensity: Double = 1.0) -> LinearGradient {
        let topStop = (colorScheme == .dark ? 0.36 : 0.72) * intensity
        let midStop = (colorScheme == .dark ? 0.08 : 0.18) * intensity
        let bottomStop = (colorScheme == .dark ? 0.18 : 0.32) * intensity
        return LinearGradient(
            stops: [
                .init(color: Color.white.opacity(topStop), location: 0.0),
                .init(color: Color.white.opacity(midStop), location: 0.48),
                .init(color: Color.white.opacity(bottomStop), location: 1.0)
            ],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }

    public static func topRefractionSheen(colorScheme: ColorScheme, isHovered: Bool = false) -> LinearGradient {
        let boost = isHovered ? 1.35 : 1.0
        let topAlpha = (colorScheme == .dark ? 0.10 : 0.34) * boost
        return LinearGradient(
            colors: [
                Color.white.opacity(topAlpha),
                Color.white.opacity(0.0)
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }
}

private struct LiquidGlassSurfaceModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme
    let cornerRadius: CGFloat
    let tint: Color?
    let prominence: LiquidGlassProminence
    let isHovered: Bool
    let isSelected: Bool

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        let baseMaterial: Material = {
            switch prominence {
            case .subtle: return .ultraThinMaterial
            case .regular: return .thinMaterial
            case .elevated: return .regularMaterial
            }
        }()
        let tintOpacity: Double = {
            if isSelected { return isHovered ? 0.22 : 0.16 }
            if isHovered { return 0.11 }
            return tint == nil ? 0.0 : 0.07
        }()
        let shadowRadius: CGFloat = {
            switch prominence {
            case .subtle: return isHovered ? 8 : 4
            case .regular: return isHovered ? 14 : 9
            case .elevated: return 26
            }
        }()
        let shadowOpacity: Double = {
            switch prominence {
            case .subtle: return colorScheme == .dark ? 0.18 : 0.06
            case .regular: return colorScheme == .dark ? 0.24 : 0.09
            case .elevated: return colorScheme == .dark ? 0.36 : 0.16
            }
        }()

        content
            .background {
                ZStack {
                    shape.fill(baseMaterial)
                    if let tint {
                        shape.fill(tint.opacity(tintOpacity))
                    } else if isHovered {
                        shape.fill(Color.primary.opacity(colorScheme == .dark ? 0.06 : 0.04))
                    }
                    shape.fill(LiquidGlassMetrics.topRefractionSheen(colorScheme: colorScheme, isHovered: isHovered))
                }
            }
            .overlay {
                shape.strokeBorder(
                    LiquidGlassMetrics.specularRimGradient(
                        colorScheme: colorScheme,
                        intensity: isSelected || isHovered ? 1.25 : 0.9
                    ),
                    lineWidth: isSelected ? 1.0 : 0.8
                )
            }
            .shadow(color: Color.black.opacity(shadowOpacity), radius: shadowRadius, y: shadowRadius * 0.38)
    }
}

private struct LiquidGlassCapsuleModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme
    let tint: Color?
    let isSelected: Bool
    let isHovered: Bool

    func body(content: Content) -> some View {
        let activeTint = tint ?? Color.accentColor
        content
            .background {
                ZStack {
                    Capsule(style: .continuous)
                        .fill(.ultraThinMaterial)
                    if isSelected {
                        Capsule(style: .continuous)
                            .fill(activeTint.opacity(isHovered ? 0.24 : 0.17))
                    } else if isHovered {
                        Capsule(style: .continuous)
                            .fill(Color.primary.opacity(colorScheme == .dark ? 0.10 : 0.06))
                    }
                    Capsule(style: .continuous)
                        .fill(LiquidGlassMetrics.topRefractionSheen(colorScheme: colorScheme, isHovered: isHovered))
                }
            }
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(
                        isSelected
                            ? AnyShapeStyle(activeTint.opacity(0.46))
                            : AnyShapeStyle(
                                LiquidGlassMetrics.specularRimGradient(
                                    colorScheme: colorScheme,
                                    intensity: isHovered ? 1.15 : 0.78
                                )
                            ),
                        lineWidth: 0.8
                    )
            }
    }
}

public extension View {
    func liquidGlassSurface(
        cornerRadius: CGFloat = LiquidGlassMetrics.cardCornerRadius,
        tint: Color? = nil,
        prominence: LiquidGlassProminence = .regular,
        isHovered: Bool = false,
        isSelected: Bool = false
    ) -> some View {
        modifier(
            LiquidGlassSurfaceModifier(
                cornerRadius: cornerRadius,
                tint: tint,
                prominence: prominence,
                isHovered: isHovered,
                isSelected: isSelected
            )
        )
    }

    func liquidGlassCapsule(
        tint: Color? = nil,
        isSelected: Bool = false,
        isHovered: Bool = false
    ) -> some View {
        modifier(
            LiquidGlassCapsuleModifier(
                tint: tint,
                isSelected: isSelected,
                isHovered: isHovered
            )
        )
    }
}

public struct LiquidGlassBackdropAura: View {
    @Environment(\.colorScheme) private var colorScheme

    public init() {}

    public var body: some View {
        ZStack {
            MacVibrancySurface(material: .underWindowBackground, blendingMode: .behindWindow)
            RadialGradient(
                colors: [
                    Color.accentColor.opacity(colorScheme == .dark ? 0.11 : 0.07),
                    Color.clear
                ],
                center: .topLeading,
                startRadius: 20,
                endRadius: 520
            )
            RadialGradient(
                colors: [
                    Color(nsColor: .systemTeal).opacity(colorScheme == .dark ? 0.08 : 0.05),
                    Color.clear
                ],
                center: .bottomTrailing,
                startRadius: 30,
                endRadius: 480
            )
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}

// MARK: - Reusable Apple Liquid Glass Components

public struct ProtocolSquircleIcon: View {
    @Environment(\.colorScheme) private var colorScheme
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
        let radius = size * 0.28
        ZStack {
            RoundedRectangle(cornerRadius: radius, style: .continuous)
                .fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: radius, style: .continuous)
                .fill(tint.opacity(0.16))
            RoundedRectangle(cornerRadius: radius, style: .continuous)
                .fill(LiquidGlassMetrics.topRefractionSheen(colorScheme: colorScheme))
            RoundedRectangle(cornerRadius: radius, style: .continuous)
                .strokeBorder(tint.opacity(0.34), lineWidth: 0.75)
            Image(systemName: protocolType.sfSymbolName)
                .font(.system(size: size * 0.46, weight: .semibold))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(tint)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

public struct ProtocolHoverGlassPill: View {
    public let protocolType: VaultProtocolType
    public var customHex: String? = nil

    public init(protocolType: VaultProtocolType, customHex: String? = nil) {
        self.protocolType = protocolType
        self.customHex = customHex
    }

    public var body: some View {
        let tint = HIGColorUtilities.color(fromHex: customHex) ?? protocolType.appleSystemTint
        Text(IntelligentConnectionGrouper.groupBadge(for: protocolType))
            .font(.system(size: 9.5, weight: .semibold, design: .rounded))
            .foregroundStyle(.primary)
            .padding(.horizontal, 6)
            .padding(.vertical, 1.5)
            .liquidGlassCapsule(tint: tint, isSelected: true, isHovered: false)
    }
}

public struct SidebarProtocolGroupHeader: View {
    public let title: String
    public let protocolType: VaultProtocolType
    public let count: Int

    public init(title: String, protocolType: VaultProtocolType, count: Int) {
        self.title = title
        self.protocolType = protocolType
        self.count = count
    }

    public var body: some View {
        HStack(spacing: 5) {
            Text(title)
                .font(.system(size: 9.5, weight: .semibold, design: .rounded))
                .tracking(0.4)
                .foregroundStyle(.secondary)
            Rectangle()
                .fill(Color.primary.opacity(0.08))
                .frame(height: 0.75)
            Text("\(count)")
                .font(.system(size: 9.5, weight: .medium, design: .monospaced))
                .foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 4)
        .padding(.top, 4)
        .padding(.bottom, 1)
        .accessibilityElement(children: .combine)
    }
}

public struct KeycapBadge: View {
    public let shortcut: String

    public init(_ shortcut: String) {
        self.shortcut = shortcut
    }

    public var body: some View {
        Text(shortcut)
            .font(.system(size: 10.5, weight: .medium, design: .rounded))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 6.5)
            .padding(.vertical, 2)
            .liquidGlassCapsule()
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
            .font(.system(size: 9.0, weight: .bold, design: .rounded))
            .foregroundStyle(.primary)
            .padding(.horizontal, 6)
            .padding(.vertical, 1.5)
            .liquidGlassCapsule(tint: badgeTint, isSelected: true)
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
