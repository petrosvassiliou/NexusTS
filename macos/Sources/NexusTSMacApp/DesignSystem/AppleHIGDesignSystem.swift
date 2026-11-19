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

public struct SidebarConnectionTypeIcon: View {
    public let protocolType: VaultProtocolType
    public var customHex: String? = nil
    public var size: CGFloat = 15

    public init(protocolType: VaultProtocolType, customHex: String? = nil, size: CGFloat = 15) {
        self.protocolType = protocolType
        self.customHex = customHex
        self.size = size
    }

    public var body: some View {
        let tint = HIGColorUtilities.color(fromHex: customHex) ?? protocolType.appleSystemTint
        Group {
            switch protocolType {
            case .RDP, .WINDOWS_TERMINAL_SERVICES:
                Canvas { context, canvasSize in
                    let s = min(canvasSize.width, canvasSize.height)
                    let center = CGPoint(x: canvasSize.width * 0.5, y: canvasSize.height * 0.5)
                    let ringRadius = s * 0.43
                    let ringStroke = max(1.25, s * 0.092)
                    let chevronStroke = max(1.35, s * 0.102)

                    var ring = Path()
                    ring.addArc(
                        center: center,
                        radius: ringRadius,
                        startAngle: .degrees(0),
                        endAngle: .degrees(360),
                        clockwise: false
                    )
                    context.stroke(
                        ring,
                        with: .color(tint),
                        style: StrokeStyle(lineWidth: ringStroke, lineCap: .round, lineJoin: .round)
                    )

                    // Classic Remote Desktop / Royal TSX opposing chevrons:
                    // Lower-left '>' pointing right + upper-right '<' pointing left
                    var chevrons = Path()
                    chevrons.move(to: CGPoint(x: s * 0.29, y: s * 0.44))
                    chevrons.addLine(to: CGPoint(x: s * 0.44, y: s * 0.58))
                    chevrons.addLine(to: CGPoint(x: s * 0.29, y: s * 0.72))

                    chevrons.move(to: CGPoint(x: s * 0.71, y: s * 0.28))
                    chevrons.addLine(to: CGPoint(x: s * 0.56, y: s * 0.42))
                    chevrons.addLine(to: CGPoint(x: s * 0.71, y: s * 0.56))

                    context.stroke(
                        chevrons,
                        with: .color(tint),
                        style: StrokeStyle(lineWidth: chevronStroke, lineCap: .round, lineJoin: .round)
                    )
                }
            default:
                Image(systemName: protocolType.sfSymbolName)
                    .font(.system(size: size * 0.82, weight: .semibold))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(tint)
            }
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

// MARK: - Discreet Liquid-Glass Hover Tooltip System (Toolbar + Workspace Controls)

private final class DiscreetTooltipPanel: NSPanel {
    init() {
        super.init(
            contentRect: NSRect(x: 0, y: 0, width: 120, height: 32),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: true
        )
        isFloatingPanel = true
        level = .popUpMenu
        isOpaque = false
        backgroundColor = .clear
        hasShadow = false
        ignoresMouseEvents = true
        hidesOnDeactivate = true
        isReleasedWhenClosed = false
        collectionBehavior = [.transient, .ignoresCycle, .fullScreenAuxiliary]
    }

    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

private struct DiscreetTooltipBubbleView: View {
    let spec: DiscreetTooltipSpec

    var body: some View {
        HStack(spacing: 6) {
            Text(spec.title)
                .font(.system(size: 11.5, weight: .medium))
                .foregroundStyle(.primary)
                .lineLimit(1)

            if let shortcut = spec.shortcut, !shortcut.isEmpty {
                Text(shortcut)
                    .font(.system(size: 10.5, weight: .semibold, design: .rounded))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 4.5)
                    .padding(.vertical, 1)
                    .background(
                        RoundedRectangle(cornerRadius: 3.5, style: .continuous)
                            .fill(Color.primary.opacity(0.09))
                    )
            }
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 4.5)
        .background(
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .fill(.regularMaterial)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.14), lineWidth: 0.5)
        )
        .shadow(color: Color.black.opacity(0.18), radius: 6, x: 0, y: 2.5)
        .padding(10)
        .fixedSize()
    }
}

@MainActor
public final class DiscreetTooltipController {
    public static let shared = DiscreetTooltipController()

    private static let outerShadowMargin: CGFloat = 10
    private static let coldDelayNanoseconds: UInt64 = 360_000_000
    private static let warmDelayNanoseconds: UInt64 = 90_000_000
    private static let warmReuseWindowSeconds: TimeInterval = 0.38
    private static let autoDismissNanoseconds: UInt64 = 4_500_000_000

    public var stringsProvider: @MainActor () -> LocalizedWorkspaceStrings = {
        LocalizedWorkspaceStrings.catalog(for: .system)
    }

    private var panel: DiscreetTooltipPanel?
    private var hostingView: NSHostingView<DiscreetTooltipBubbleView>?
    private var activeSourceKey: String?
    private var activeAnchorScreenRect: NSRect = .zero
    private var isTrackingViewSource: Bool = false
    private var showTask: Task<Void, Never>?
    private var autoHideTask: Task<Void, Never>?
    private var isCurrentlyVisible: Bool = false
    private var lastDismissTimestamp: TimeInterval = 0
    private var localEventMonitor: Any?
    private var observers: [NSObjectProtocol] = []
    private var harvestedControlTooltips: [ObjectIdentifier: String] = [:]

    private init() {}

    public func installIfNeeded() {
        guard localEventMonitor == nil,
              !CommandLine.arguments.contains("--self-test") else {
            return
        }

        for win in NSApp.windows where !(win is DiscreetTooltipPanel) {
            win.acceptsMouseMovedEvents = true
        }

        let nc = NotificationCenter.default
        observers.append(
            nc.addObserver(forName: NSWindow.didBecomeKeyNotification, object: nil, queue: .main) { note in
                let win = note.object as? NSWindow
                MainActor.assumeIsolated {
                    if let win, !(win is DiscreetTooltipPanel) {
                        win.acceptsMouseMovedEvents = true
                    }
                }
            }
        )
        observers.append(
            nc.addObserver(forName: NSWindow.didResignKeyNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    self?.hideImmediately()
                }
            }
        )
        observers.append(
            nc.addObserver(forName: NSApplication.didResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    self?.hideImmediately()
                }
            }
        )
        observers.append(
            nc.addObserver(forName: NSMenu.didBeginTrackingNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    self?.hideImmediately()
                }
            }
        )

        localEventMonitor = NSEvent.addLocalMonitorForEvents(
            matching: [.mouseMoved, .leftMouseDown, .rightMouseDown, .otherMouseDown, .scrollWheel, .keyDown]
        ) { [weak self] event in
            MainActor.assumeIsolated {
                self?.handleLocalEvent(event)
            }
            return event
        }
    }

    fileprivate func scheduleTrackingViewTooltip(
        spec: DiscreetTooltipSpec,
        anchorScreenRect: NSRect,
        sourceKey: String
    ) {
        guard !spec.title.isEmpty, anchorScreenRect.width > 0, anchorScreenRect.height > 0 else { return }
        schedule(spec: spec, anchorScreenRect: anchorScreenRect, sourceKey: sourceKey, fromTrackingView: true)
    }

    fileprivate func cancelTrackingViewTooltip(sourceKey: String) {
        guard activeSourceKey == sourceKey else { return }
        hideImmediately(recordWarmTimestamp: true)
    }

    public func hideImmediately(recordWarmTimestamp: Bool = false) {
        showTask?.cancel()
        showTask = nil
        autoHideTask?.cancel()
        autoHideTask = nil
        activeSourceKey = nil
        activeAnchorScreenRect = .zero
        isTrackingViewSource = false
        if isCurrentlyVisible {
            if recordWarmTimestamp {
                lastDismissTimestamp = ProcessInfo.processInfo.systemUptime
            }
            isCurrentlyVisible = false
        }
        panel?.alphaValue = 0
        panel?.orderOut(nil)
    }

    private func handleLocalEvent(_ event: NSEvent) {
        switch event.type {
        case .leftMouseDown, .rightMouseDown, .otherMouseDown, .scrollWheel, .keyDown:
            hideImmediately()
        case .mouseMoved:
            if let win = event.window, !(win is DiscreetTooltipPanel) {
                win.acceptsMouseMovedEvents = true
            }
            let mouseScreen = NSEvent.mouseLocation
            if isTrackingViewSource {
                if !activeAnchorScreenRect.insetBy(dx: -3, dy: -3).contains(mouseScreen) {
                    hideImmediately(recordWarmTimestamp: true)
                } else {
                    return
                }
            }

            guard NSApp.isActive,
                  let targetWindow = findFrontmostAppWindow(containingScreenPoint: mouseScreen) else {
                if activeSourceKey != nil {
                    hideImmediately()
                }
                return
            }

            if let match = resolveToolbarHover(atScreenPoint: mouseScreen, in: targetWindow) {
                schedule(
                    spec: match.spec,
                    anchorScreenRect: match.anchorScreenRect,
                    sourceKey: match.sourceKey,
                    fromTrackingView: false
                )
            } else if activeSourceKey != nil && !isTrackingViewSource {
                hideImmediately(recordWarmTimestamp: true)
            }
        default:
            break
        }
    }

    private func schedule(
        spec: DiscreetTooltipSpec,
        anchorScreenRect: NSRect,
        sourceKey: String,
        fromTrackingView: Bool
    ) {
        if activeSourceKey == sourceKey {
            activeAnchorScreenRect = anchorScreenRect
            return
        }

        let wasWarm = isCurrentlyVisible ||
            (ProcessInfo.processInfo.systemUptime - lastDismissTimestamp) <= Self.warmReuseWindowSeconds

        showTask?.cancel()
        autoHideTask?.cancel()
        activeSourceKey = sourceKey
        activeAnchorScreenRect = anchorScreenRect
        isTrackingViewSource = fromTrackingView

        let delay = wasWarm ? Self.warmDelayNanoseconds : Self.coldDelayNanoseconds
        showTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled, let self, self.activeSourceKey == sourceKey else { return }
            self.presentPanel(spec: spec, anchorScreenRect: self.activeAnchorScreenRect)
        }
    }

    private func presentPanel(spec: DiscreetTooltipSpec, anchorScreenRect: NSRect) {
        let tooltipPanel: DiscreetTooltipPanel
        if let existing = panel {
            tooltipPanel = existing
        } else {
            let created = DiscreetTooltipPanel()
            panel = created
            tooltipPanel = created
        }

        let bubble = DiscreetTooltipBubbleView(spec: spec)
        if let host = hostingView {
            host.rootView = bubble
        } else {
            let host = NSHostingView(rootView: bubble)
            tooltipPanel.contentView = host
            hostingView = host
        }

        guard let host = hostingView else { return }
        host.layoutSubtreeIfNeeded()
        let fitting = host.fittingSize
        guard fitting.width > 0, fitting.height > 0 else { return }

        let screen = NSScreen.screens.first(where: { $0.frame.intersects(anchorScreenRect) })
            ?? NSScreen.main
            ?? NSScreen.screens.first
        let visible = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1920, height: 1080)
        let pad = Self.outerShadowMargin

        var originX = (anchorScreenRect.midX - fitting.width * 0.5).rounded()
        let minAllowedX = visible.minX + 6 - pad
        let maxAllowedX = visible.maxX - 6 - (fitting.width - pad)
        if minAllowedX <= maxAllowedX {
            originX = min(max(originX, minAllowedX), maxAllowedX)
        }

        // Default: place visual top of capsule 5 pt below anchorScreenRect.minY
        var originY = (anchorScreenRect.minY + 5 - fitting.height).rounded()
        if originY + pad < visible.minY + 6 {
            // Flip above anchor when near bottom of screen
            originY = (anchorScreenRect.maxY - 5).rounded()
        }

        tooltipPanel.setFrame(NSRect(origin: NSPoint(x: originX, y: originY), size: fitting), display: true)
        if !isCurrentlyVisible {
            tooltipPanel.alphaValue = 0
            tooltipPanel.orderFrontRegardless()
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.12
                tooltipPanel.animator().alphaValue = 1.0
            }
        } else {
            tooltipPanel.alphaValue = 1.0
            tooltipPanel.orderFrontRegardless()
        }
        isCurrentlyVisible = true

        autoHideTask?.cancel()
        autoHideTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: Self.autoDismissNanoseconds)
            guard !Task.isCancelled else { return }
            self?.hideImmediately()
        }
    }

    private func findFrontmostAppWindow(containingScreenPoint pt: NSPoint) -> NSWindow? {
        for win in NSApp.orderedWindows {
            if win is DiscreetTooltipPanel { continue }
            guard win.isVisible, !win.isMiniaturized, win.alphaValue > 0.05 else { continue }
            if win.frame.contains(pt) {
                return win
            }
        }
        return nil
    }

    private func resolveToolbarHover(
        atScreenPoint mouseScreen: NSPoint,
        in window: NSWindow
    ) -> (spec: DiscreetTooltipSpec, anchorScreenRect: NSRect, sourceKey: String)? {
        let winPt = window.convertPoint(fromScreen: mouseScreen)
        // Restrict automatic inspection strictly to the window's unified toolbar / titlebar band
        guard winPt.y >= window.contentLayoutRect.maxY - 3,
              winPt.y <= window.frame.height else {
            return nil
        }

        // Ignore standard macOS window traffic-light buttons (Close / Minimize / Zoom)
        for btnType in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] {
            if let stdBtn = window.standardWindowButton(btnType) {
                let rectInWin = stdBtn.convert(stdBtn.bounds, to: nil)
                if rectInWin.insetBy(dx: -3, dy: -3).contains(winPt) {
                    return nil
                }
            }
        }

        let strings = stringsProvider()
        suppressToolbarNativeTooltips(in: window)

        // 1. Primary path: AppKit / SwiftUI NSAccessibility hit-test (works with macOS 26 Tahoe Liquid Glass pills + macOS 14/15 NSToolbar)
        if let hitAny = window.accessibilityHitTest(mouseScreen) {
            var current: AnyObject? = hitAny as AnyObject
            for _ in 0..<5 {
                guard let obj = current else { break }
                if let ax = obj as? NSAccessibilityProtocol {
                    let subrole = ax.accessibilitySubrole()
                    if subrole == .closeButton || subrole == .minimizeButton || subrole == .fullScreenButton || subrole == .zoomButton {
                        return nil
                    }
                    let role = ax.accessibilityRole()
                    if role == .button || role == .checkBox || role == .radioButton || role == .popUpButton || role == .menuButton {
                        let axRect = ax.accessibilityFrame()
                        if axRect.width >= 12, axRect.width <= 180,
                           axRect.height >= 12, axRect.height <= 52,
                           axRect.insetBy(dx: -3, dy: -3).contains(mouseScreen) {
                            let isNarrowChevron = (role == .popUpButton || role == .menuButton) && axRect.width <= 29
                            let candidates: [String] = [
                                ax.accessibilityIdentifier(),
                                ax.accessibilityHelp() ?? "",
                                ax.accessibilityLabel() ?? "",
                                ax.accessibilityTitle() ?? ""
                            ]
                            let symbolHint = (obj as? NSButton)?.image?.description ?? ""
                            if isNarrowChevron,
                               let chevronSpec = DiscreetTooltipSpec.resolveToolbarTooltip(
                                   candidateText: candidates.first(where: { !$0.isEmpty }) ?? "",
                                   sfSymbolHint: symbolHint,
                                   isNarrowMenuChevron: true,
                                   strings: strings
                               ) {
                                let key = "toolbar:chevron:\(Int(axRect.midX.rounded()))"
                                return (chevronSpec, axRect, key)
                            }
                            for raw in candidates where !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                if let spec = DiscreetTooltipSpec.resolveToolbarTooltip(
                                    candidateText: raw,
                                    sfSymbolHint: symbolHint,
                                    isNarrowMenuChevron: isNarrowChevron,
                                    strings: strings
                                ) {
                                    let key = "toolbar:\(spec.title):\(Int(axRect.midX.rounded()))"
                                    return (spec, axRect, key)
                                }
                            }
                        }
                    }
                    current = ax.accessibilityParent().map { $0 as AnyObject }
                } else {
                    break
                }
            }
        }

        // 2. Fallback path: NSThemeFrame NSControl subview walk (NSSegmentedControl / NSButton)
        guard let themeFrame = window.contentView?.superview else { return nil }
        if let control = findDeepestToolbarControl(in: themeFrame, windowPoint: winPt) {
            if let seg = control as? NSSegmentedControl, seg.segmentCount > 0 {
                let localPt = seg.convert(winPt, from: nil)
                let (segIdx, segBounds) = resolveHoveredSegment(in: seg, localPoint: localPt)
                let segWinRect = seg.convert(segBounds, to: nil)
                let segScreenRect = window.convertToScreen(segWinRect)
                let isChevron = seg.segmentCount >= 2 && segIdx == seg.segmentCount - 1 && segBounds.width <= 29
                let label = seg.label(forSegment: segIdx)
                    ?? seg.toolTip(forSegment: segIdx)
                    ?? harvestedControlTooltips[ObjectIdentifier(seg)]
                    ?? seg.accessibilityLabel()
                    ?? ""
                let imgDesc = seg.image(forSegment: segIdx)?.description ?? ""
                if let spec = DiscreetTooltipSpec.resolveToolbarTooltip(
                    candidateText: label,
                    sfSymbolHint: imgDesc,
                    isNarrowMenuChevron: isChevron,
                    strings: strings
                ) {
                    return (spec, segScreenRect, "toolbar:seg:\(ObjectIdentifier(seg)):\(segIdx)")
                }
            } else {
                let winRect = control.convert(control.bounds, to: nil)
                let screenRect = window.convertToScreen(winRect)
                let harvested = harvestedControlTooltips[ObjectIdentifier(control)] ?? ""
                let btn = control as? NSButton
                let imgDesc = btn?.image?.description ?? ""
                let candidates = [
                    harvested,
                    control.accessibilityIdentifier(),
                    control.accessibilityHelp() ?? "",
                    control.accessibilityLabel() ?? "",
                    btn?.title ?? "",
                    control.superview?.accessibilityLabel() ?? ""
                ]
                let isChevron = winRect.width <= 26 && (imgDesc.lowercased().contains("chevron") || (btn?.title ?? "").isEmpty)
                for raw in candidates where !raw.isEmpty || !imgDesc.isEmpty {
                    if let spec = DiscreetTooltipSpec.resolveToolbarTooltip(
                        candidateText: raw,
                        sfSymbolHint: imgDesc,
                        isNarrowMenuChevron: isChevron,
                        strings: strings
                    ) {
                        return (spec, screenRect, "toolbar:ctrl:\(ObjectIdentifier(control))")
                    }
                }
            }
        }
        return nil
    }

    private func suppressToolbarNativeTooltips(in window: NSWindow) {
        if let items = window.toolbar?.items {
            for item in items {
                if let group = item as? NSToolbarItemGroup {
                    for sub in group.subitems where sub.toolTip != nil {
                        sub.toolTip = nil
                    }
                }
                if item.toolTip != nil {
                    item.toolTip = nil
                }
            }
        }
        guard let themeFrame = window.contentView?.superview else { return }
        let minToolbarY = window.contentLayoutRect.maxY - 3
        func strip(_ view: NSView) {
            let rectInWin = view.convert(view.bounds, to: nil)
            if rectInWin.maxY >= minToolbarY {
                if let tip = view.toolTip, !tip.isEmpty {
                    harvestedControlTooltips[ObjectIdentifier(view)] = tip
                    view.toolTip = nil
                }
                if let seg = view as? NSSegmentedControl {
                    for idx in 0..<seg.segmentCount {
                        if let tip = seg.toolTip(forSegment: idx), !tip.isEmpty {
                            harvestedControlTooltips[ObjectIdentifier(seg)] = tip
                            seg.setToolTip(nil, forSegment: idx)
                        }
                    }
                }
            }
            for sub in view.subviews {
                strip(sub)
            }
        }
        strip(themeFrame)
    }

    private func findDeepestToolbarControl(in root: NSView, windowPoint: NSPoint) -> NSControl? {
        guard !root.isHidden, root.alphaValue > 0.01 else { return nil }
        for sub in root.subviews.reversed() {
            if let deeper = findDeepestToolbarControl(in: sub, windowPoint: windowPoint) {
                return deeper
            }
        }
        if let ctrl = root as? NSControl {
            let rectInWin = ctrl.convert(ctrl.bounds, to: nil)
            if rectInWin.width >= 12, rectInWin.width <= 180,
               rectInWin.height >= 12, rectInWin.height <= 52,
               rectInWin.contains(windowPoint) {
                return ctrl
            }
        }
        return nil
    }

    private func resolveHoveredSegment(in seg: NSSegmentedControl, localPoint: NSPoint) -> (Int, NSRect) {
        let count = max(1, seg.segmentCount)
        if count == 1 {
            return (0, seg.bounds)
        }
        var widths: [CGFloat] = (0..<count).map { seg.width(forSegment: $0) }
        let explicitSum = widths.reduce(0, +)
        if explicitSum <= 1 {
            if count == 2 {
                // Split menu toolbar item (`+ | ⌄`): primary action ~62%, menu chevron ~38%
                widths = [seg.bounds.width * 0.62, seg.bounds.width * 0.38]
            } else {
                let unit = seg.bounds.width / CGFloat(count)
                widths = Array(repeating: unit, count: count)
            }
        } else if explicitSum < seg.bounds.width {
            let zeroCount = widths.filter { $0 <= 0 }.count
            if zeroCount > 0 {
                let rem = (seg.bounds.width - explicitSum) / CGFloat(zeroCount)
                widths = widths.map { $0 <= 0 ? rem : $0 }
            }
        }
        var cursorX: CGFloat = seg.bounds.minX
        for i in 0..<count {
            let w = widths[i]
            let segRect = NSRect(x: cursorX, y: seg.bounds.minY, width: w, height: seg.bounds.height)
            if localPoint.x <= segRect.maxX || i == count - 1 {
                return (i, segRect)
            }
            cursorX += w
        }
        return (0, seg.bounds)
    }
}

private final class DiscreetTooltipAnchorNSView: NSView {
    var spec: DiscreetTooltipSpec
    private var trackingAreaRef: NSTrackingArea?

    init(spec: DiscreetTooltipSpec) {
        self.spec = spec
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func hitTest(_ point: NSPoint) -> NSView? {
        nil
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let existing = trackingAreaRef {
            removeTrackingArea(existing)
        }
        let area = NSTrackingArea(
            rect: .zero,
            options: [.mouseEnteredAndExited, .activeInKeyWindow, .inVisibleRect],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(area)
        trackingAreaRef = area
    }

    private var sourceKey: String {
        "anchor:\(ObjectIdentifier(self))"
    }

    override func mouseEntered(with event: NSEvent) {
        guard let win = window else { return }
        let rectInWin = convert(bounds, to: nil)
        let screenRect = win.convertToScreen(rectInWin)
        let currentSpec = spec
        let key = sourceKey
        MainActor.assumeIsolated {
            DiscreetTooltipController.shared.scheduleTrackingViewTooltip(
                spec: currentSpec,
                anchorScreenRect: screenRect,
                sourceKey: key
            )
        }
    }

    override func mouseExited(with event: NSEvent) {
        let key = sourceKey
        MainActor.assumeIsolated {
            DiscreetTooltipController.shared.cancelTrackingViewTooltip(sourceKey: key)
        }
    }

    override func viewWillMove(toWindow newWindow: NSWindow?) {
        if newWindow == nil {
            let key = sourceKey
            MainActor.assumeIsolated {
                DiscreetTooltipController.shared.cancelTrackingViewTooltip(sourceKey: key)
            }
        }
        super.viewWillMove(toWindow: newWindow)
    }
}

private struct DiscreetTooltipTrackingAnchor: NSViewRepresentable {
    let spec: DiscreetTooltipSpec

    func makeNSView(context: Context) -> DiscreetTooltipAnchorNSView {
        DiscreetTooltipAnchorNSView(spec: spec)
    }

    func updateNSView(_ nsView: DiscreetTooltipAnchorNSView, context: Context) {
        nsView.spec = spec
    }

    static func dismantleNSView(_ nsView: DiscreetTooltipAnchorNSView, coordinator: ()) {
        let key = "anchor:\(ObjectIdentifier(nsView))"
        MainActor.assumeIsolated {
            DiscreetTooltipController.shared.cancelTrackingViewTooltip(sourceKey: key)
        }
    }
}

public extension View {
    /// Displays a sleek, discreet Liquid-Glass hover tooltip without AppKit's slow system tooltip box.
    func discreetTooltip(_ text: String, shortcut: String? = nil) -> some View {
        let spec = DiscreetTooltipSpec.parse(text, explicitShortcut: shortcut)
        return self
            .accessibilityHint(spec.title)
            .background(DiscreetTooltipTrackingAnchor(spec: spec))
    }
}

