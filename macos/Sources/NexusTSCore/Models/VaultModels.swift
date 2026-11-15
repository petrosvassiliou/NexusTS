import Foundation

// MARK: - Core Enumerations (1:1 Wire Parity with Kotlin kotlinx.serialization)

public enum InheritanceMode: String, Codable, CaseIterable, Sendable {
    case INHERIT_FROM_PARENT
    case OVERRIDE_EXPLICIT
    case NONE_DIRECT
    case PROMPT_AT_CONNECT

    public var displayTitle: String {
        switch self {
        case .INHERIT_FROM_PARENT: return "Inherit from Parent Folder"
        case .OVERRIDE_EXPLICIT: return "Assign Explicit Profile"
        case .NONE_DIRECT: return "None (Direct Connection)"
        case .PROMPT_AT_CONNECT: return "Prompt on Every Connect"
        }
    }
}

public enum VaultProtocolType: String, Codable, CaseIterable, Identifiable, Sendable {
    case RDP
    case SSH
    case TELNET
    case SERIAL
    case SFTP
    case FTP
    case FTPS
    case SCP
    case VNC
    case APPLE_SCREEN_SHARING
    case WEB_PAGE
    case VMWARE_VSPHERE
    case PROXMOX_VE
    case HYPER_V
    case AWS_EC2
    case AZURE_VM
    case WINDOWS_TERMINAL_SERVICES
    case UTILITY_PING
    case UTILITY_TRACEROUTE
    case UTILITY_PORT_SCANNER
    case UTILITY_WAKE_ON_LAN
    case KUBERNETES_POD
    case DOCKER_CONTAINER

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .RDP: return "Remote Desktop (RDP)"
        case .SSH: return "SSH Terminal"
        case .TELNET: return "Telnet"
        case .SERIAL: return "USB Serial / UART"
        case .SFTP: return "SFTP File Transfer"
        case .FTP: return "FTP"
        case .FTPS: return "FTPS (TLS)"
        case .SCP: return "SCP Transfer"
        case .VNC: return "VNC Remote Screen"
        case .APPLE_SCREEN_SHARING: return "Apple Screen Sharing"
        case .WEB_PAGE: return "Web Console (Isolated)"
        case .VMWARE_VSPHERE: return "VMware vSphere"
        case .PROXMOX_VE: return "Proxmox VE"
        case .HYPER_V: return "Microsoft Hyper-V"
        case .AWS_EC2: return "AWS EC2"
        case .AZURE_VM: return "Microsoft Azure VM"
        case .WINDOWS_TERMINAL_SERVICES: return "Windows Terminal Services"
        case .UTILITY_PING: return "Ping Reachability"
        case .UTILITY_TRACEROUTE: return "Traceroute"
        case .UTILITY_PORT_SCANNER: return "TCP Port Scanner"
        case .UTILITY_WAKE_ON_LAN: return "Wake-on-LAN"
        case .KUBERNETES_POD: return "Kubernetes Pod"
        case .DOCKER_CONTAINER: return "Docker Container"
        }
    }

    public var shortBadge: String {
        switch self {
        case .RDP: return "RDP"
        case .SSH: return "SSH"
        case .TELNET: return "TEL"
        case .SERIAL: return "UART"
        case .SFTP: return "SFTP"
        case .FTP: return "FTP"
        case .FTPS: return "FTPS"
        case .SCP: return "SCP"
        case .VNC: return "VNC"
        case .APPLE_SCREEN_SHARING: return "ARD"
        case .WEB_PAGE: return "WEB"
        case .VMWARE_VSPHERE: return "ESXi"
        case .PROXMOX_VE: return "PVE"
        case .HYPER_V: return "HV"
        case .AWS_EC2: return "EC2"
        case .AZURE_VM: return "AZ"
        case .WINDOWS_TERMINAL_SERVICES: return "RDS"
        case .UTILITY_PING: return "PING"
        case .UTILITY_TRACEROUTE: return "MTR"
        case .UTILITY_PORT_SCANNER: return "SCAN"
        case .UTILITY_WAKE_ON_LAN: return "WOL"
        case .KUBERNETES_POD: return "K8S"
        case .DOCKER_CONTAINER: return "OCI"
        }
    }

    public var defaultPort: Int {
        switch self {
        case .RDP, .WINDOWS_TERMINAL_SERVICES: return 3389
        case .SSH, .SFTP, .SCP: return 22
        case .TELNET: return 23
        case .FTP: return 21
        case .FTPS: return 990
        case .VNC, .APPLE_SCREEN_SHARING: return 5900
        case .WEB_PAGE, .VMWARE_VSPHERE, .AWS_EC2, .AZURE_VM: return 443
        case .PROXMOX_VE: return 8006
        case .HYPER_V: return 5986
        case .KUBERNETES_POD: return 6443
        case .DOCKER_CONTAINER: return 2375
        case .SERIAL, .UTILITY_PING, .UTILITY_TRACEROUTE, .UTILITY_PORT_SCANNER: return 0
        case .UTILITY_WAKE_ON_LAN: return 9
        }
    }

    public func defaultSettings(host: String = "") -> ProtocolSpecificSettings {
        switch self {
        case .RDP:
            return .rdp(RdpProtocolSettings())
        case .SSH, .TELNET:
            return .ssh(SshProtocolSettings(port: defaultPort == 0 ? 22 : defaultPort))
        case .SERIAL:
            return .serialUart(SerialUartProtocolSettings())
        case .SFTP, .FTP, .FTPS, .SCP:
            return .fileTransfer(FileTransferProtocolSettings(
                port: defaultPort,
                ftpsImplicitTls: self == .FTPS
            ))
        case .VNC, .APPLE_SCREEN_SHARING:
            return .vnc(VncProtocolSettings(
                port: 5900,
                appleArdDiffieHellmanAuth: self == .APPLE_SCREEN_SHARING
            ))
        case .WEB_PAGE:
            let url = host.hasPrefix("http://") || host.hasPrefix("https://")
                ? host
                : (host.isEmpty ? "https://localhost" : "https://\(host)")
            return .webPage(WebPageProtocolSettings(url: url))
        case .VMWARE_VSPHERE, .PROXMOX_VE, .HYPER_V, .AWS_EC2, .AZURE_VM, .WINDOWS_TERMINAL_SERVICES:
            let url = host.hasPrefix("http") ? host : "https://\(host.isEmpty ? "localhost" : host):\(defaultPort)"
            return .cloudVirtualization(CloudVirtualizationProtocolSettings(apiEndpointUrl: url))
        case .UTILITY_PING, .UTILITY_TRACEROUTE, .UTILITY_PORT_SCANNER, .UTILITY_WAKE_ON_LAN:
            return .netUtility(NetUtilityProtocolSettings())
        case .KUBERNETES_POD, .DOCKER_CONTAINER:
            let endpoint = self == .DOCKER_CONTAINER ? "unix:///var/run/docker.sock" : "https://kubernetes.default.svc:6443"
            return .containerExec(ContainerExecProtocolSettings(
                clusterOrSocketEndpoint: endpoint,
                podOrContainerName: host
            ))
        }
    }
}

public enum RdpResizeBehavior: String, Codable, CaseIterable, Sendable {
    case DYNAMIC_DISP_DVC
    case SMART_SCALE_VIEWPORT
    case FIXED_SERVER_RESOLUTION
}

public enum RdpAudioRedirectionMode: String, Codable, CaseIterable, Sendable {
    case PLAY_ON_THIS_DEVICE
    case PLAY_ON_REMOTE_HOST
    case DO_NOT_PLAY

    public var nativeCode: Int32 {
        switch self {
        case .PLAY_ON_THIS_DEVICE: return 0
        case .PLAY_ON_REMOTE_HOST: return 1
        case .DO_NOT_PLAY: return 2
        }
    }
}

public enum PortForwardKind: String, Codable, CaseIterable, Sendable {
    case LOCAL
    case REMOTE
    case DYNAMIC_SOCKS5
}

public enum DynamicScriptInterpreter: String, Codable, CaseIterable, Sendable {
    case JSON_HTTP_API
    case SHELL_SH
    case PYTHON3
}

public enum CredentialSourceKind: String, Codable, CaseIterable, Sendable {
    case VAULT_INTERNAL
    case SSH_KEY_PAIR
    case ONEPASSWORD_CONNECT
    case BITWARDEN_VAULT
    case KEEPASS_KDBX
    case HARDWARE_FIDO2_PASSKEY
}

public enum GatewayTransportKind: String, Codable, CaseIterable, Sendable {
    case NEXUSTS_SECURE_GATEWAY
    case SSH_TUNNEL_BASTION
    case SOCKS5_PROXY
    case HTTP_CONNECT_PROXY
}

// MARK: - Shared Submodels

public struct CustomPropertyEntry: Codable, Equatable, Hashable, Identifiable, Sendable {
    public var id: String { key }
    public var key: String
    public var value: String
    public var isProtected: Bool

    public init(key: String, value: String, isProtected: Bool = false) {
        self.key = key
        self.value = value
        self.isProtected = isProtected
    }

    enum CodingKeys: String, CodingKey { case key, value, isProtected }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.key = try c.decodeIfPresent(String.self, forKey: .key) ?? ""
        self.value = try c.decodeIfPresent(String.self, forKey: .value) ?? ""
        self.isProtected = try c.decodeIfPresent(Bool.self, forKey: .isProtected) ?? false
    }
}

public struct InheritableProperties: Codable, Equatable, Hashable, Sendable {
    public var credentialMode: InheritanceMode
    public var explicitCredentialId: String?
    public var gatewayMode: InheritanceMode
    public var explicitGatewayId: String?
    public var environmentBadge: String?
    public var preConnectTaskId: String?
    public var postDisconnectTaskId: String?
    public var displayColorAccentHex: String?
    public var customProperties: [CustomPropertyEntry]
    public var requireConnectConfirmation: Bool

    public init(
        credentialMode: InheritanceMode = .INHERIT_FROM_PARENT,
        explicitCredentialId: String? = nil,
        gatewayMode: InheritanceMode = .INHERIT_FROM_PARENT,
        explicitGatewayId: String? = nil,
        environmentBadge: String? = nil,
        preConnectTaskId: String? = nil,
        postDisconnectTaskId: String? = nil,
        displayColorAccentHex: String? = nil,
        customProperties: [CustomPropertyEntry] = [],
        requireConnectConfirmation: Bool = false
    ) {
        self.credentialMode = credentialMode
        self.explicitCredentialId = explicitCredentialId
        self.gatewayMode = gatewayMode
        self.explicitGatewayId = explicitGatewayId
        self.environmentBadge = environmentBadge
        self.preConnectTaskId = preConnectTaskId
        self.postDisconnectTaskId = postDisconnectTaskId
        self.displayColorAccentHex = displayColorAccentHex
        self.customProperties = customProperties
        self.requireConnectConfirmation = requireConnectConfirmation
    }

    enum CodingKeys: String, CodingKey {
        case credentialMode, explicitCredentialId, gatewayMode, explicitGatewayId
        case environmentBadge, preConnectTaskId, postDisconnectTaskId
        case displayColorAccentHex, customProperties, requireConnectConfirmation
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.credentialMode = try c.decodeIfPresent(InheritanceMode.self, forKey: .credentialMode) ?? .INHERIT_FROM_PARENT
        self.explicitCredentialId = try c.decodeIfPresent(String.self, forKey: .explicitCredentialId)
        self.gatewayMode = try c.decodeIfPresent(InheritanceMode.self, forKey: .gatewayMode) ?? .INHERIT_FROM_PARENT
        self.explicitGatewayId = try c.decodeIfPresent(String.self, forKey: .explicitGatewayId)
        self.environmentBadge = try c.decodeIfPresent(String.self, forKey: .environmentBadge)
        self.preConnectTaskId = try c.decodeIfPresent(String.self, forKey: .preConnectTaskId)
        self.postDisconnectTaskId = try c.decodeIfPresent(String.self, forKey: .postDisconnectTaskId)
        self.displayColorAccentHex = try c.decodeIfPresent(String.self, forKey: .displayColorAccentHex)
        self.customProperties = try c.decodeIfPresent([CustomPropertyEntry].self, forKey: .customProperties) ?? []
        self.requireConnectConfirmation = try c.decodeIfPresent(Bool.self, forKey: .requireConnectConfirmation) ?? false
    }
}

public struct SshPortForwardRule: Codable, Equatable, Hashable, Identifiable, Sendable {
    public var id: String
    public var kind: PortForwardKind
    public var bindAddress: String
    public var bindPort: Int
    public var targetHost: String
    public var targetPort: Int
    public var enabled: Bool

    public init(
        id: String = UUID().uuidString.lowercased(),
        kind: PortForwardKind = .LOCAL,
        bindAddress: String = "127.0.0.1",
        bindPort: Int = 8080,
        targetHost: String = "127.0.0.1",
        targetPort: Int = 80,
        enabled: Bool = true
    ) {
        self.id = id
        self.kind = kind
        self.bindAddress = bindAddress
        self.bindPort = bindPort
        self.targetHost = targetHost
        self.targetPort = targetPort
        self.enabled = enabled
    }

    enum CodingKeys: String, CodingKey {
        case id, kind, bindAddress, bindPort, targetHost, targetPort, enabled
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString.lowercased()
        self.kind = try c.decodeIfPresent(PortForwardKind.self, forKey: .kind) ?? .LOCAL
        self.bindAddress = try c.decodeIfPresent(String.self, forKey: .bindAddress) ?? "127.0.0.1"
        self.bindPort = try c.decodeIfPresent(Int.self, forKey: .bindPort) ?? 8080
        self.targetHost = try c.decodeIfPresent(String.self, forKey: .targetHost) ?? "127.0.0.1"
        self.targetPort = try c.decodeIfPresent(Int.self, forKey: .targetPort) ?? 0
        self.enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
    }
}

// MARK: - ProtocolSpecificSettings Sealed Hierarchy ("_type" Discriminator)

public struct RdpProtocolSettings: Codable, Equatable, Hashable, Sendable {
    public var port: Int = 3389
    public var connectToConsoleAdmin: Bool = false
    public var hiDpiEnabled: Bool = true
    public var desktopScaleFactorPercent: Int = 100
    public var deviceScaleFactorPercent: Int = 100
    public var enableGfxPipeline: Bool = true
    public var enableH264Avc444: Bool = true
    public var enableRemoteFx: Bool = true
    public var resizeBehavior: RdpResizeBehavior = .DYNAMIC_DISP_DVC
    public var customDesktopWidth: Int = 1920
    public var customDesktopHeight: Int = 1080
    public var multiMonitorSpanning: Bool = false
    public var selectedMonitorIds: [Int] = []
    public var colorDepthBits: Int = 32
    public var redirectClipboard: Bool = true
    public var audioRedirectionMode: RdpAudioRedirectionMode = .PLAY_ON_THIS_DEVICE
    public var redirectAudioPlayback: Bool = true
    public var redirectMicrophone: Bool = false
    public var redirectDrives: Bool = false
    public var redirectedDriveSafUris: [String] = []
    public var redirectPrinters: Bool = false
    public var redirectPorts: Bool = false
    public var redirectSmartcardYubiKey: Bool = true
    public var enableFontSmoothing: Bool = true
    public var enableDesktopComposition: Bool = true
    public var enableWallpaper: Bool = false
    public var enableFullWindowDrag: Bool = false
    public var enableMenuAnimations: Bool = false
    public var enableThemes: Bool = true
    public var compressionEnabled: Bool = true
    public var remoteAppMode: Bool = false
    public var remoteAppProgram: String = ""
    public var remoteAppArguments: String = ""
    public var rdGatewayHostname: String = ""
    public var rdGatewayPort: Int = 443
    public var nlaSecurityRequired: Bool = true
    public var tlsSecurity: Bool = true
    public var restrictedAdminMode: Bool = false
    public var ignoreCertificateWarnings: Bool = false

    public init() {}

    enum CodingKeys: String, CodingKey {
        case port, connectToConsoleAdmin, hiDpiEnabled, desktopScaleFactorPercent, deviceScaleFactorPercent
        case enableGfxPipeline, enableH264Avc444, enableRemoteFx, resizeBehavior
        case customDesktopWidth, customDesktopHeight, multiMonitorSpanning, selectedMonitorIds
        case colorDepthBits, redirectClipboard, audioRedirectionMode, redirectAudioPlayback, redirectMicrophone
        case redirectDrives, redirectedDriveSafUris, redirectPrinters, redirectPorts, redirectSmartcardYubiKey
        case enableFontSmoothing, enableDesktopComposition, enableWallpaper, enableFullWindowDrag
        case enableMenuAnimations, enableThemes, compressionEnabled, remoteAppMode, remoteAppProgram
        case remoteAppArguments, rdGatewayHostname, rdGatewayPort, nlaSecurityRequired, tlsSecurity
        case restrictedAdminMode, ignoreCertificateWarnings
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.port = try c.decodeIfPresent(Int.self, forKey: .port) ?? 3389
        self.connectToConsoleAdmin = try c.decodeIfPresent(Bool.self, forKey: .connectToConsoleAdmin) ?? false
        self.hiDpiEnabled = try c.decodeIfPresent(Bool.self, forKey: .hiDpiEnabled) ?? true
        self.desktopScaleFactorPercent = try c.decodeIfPresent(Int.self, forKey: .desktopScaleFactorPercent) ?? 100
        self.deviceScaleFactorPercent = try c.decodeIfPresent(Int.self, forKey: .deviceScaleFactorPercent) ?? 100
        self.enableGfxPipeline = try c.decodeIfPresent(Bool.self, forKey: .enableGfxPipeline) ?? true
        self.enableH264Avc444 = try c.decodeIfPresent(Bool.self, forKey: .enableH264Avc444) ?? true
        self.enableRemoteFx = try c.decodeIfPresent(Bool.self, forKey: .enableRemoteFx) ?? true
        self.resizeBehavior = try c.decodeIfPresent(RdpResizeBehavior.self, forKey: .resizeBehavior) ?? .DYNAMIC_DISP_DVC
        self.customDesktopWidth = try c.decodeIfPresent(Int.self, forKey: .customDesktopWidth) ?? 1920
        self.customDesktopHeight = try c.decodeIfPresent(Int.self, forKey: .customDesktopHeight) ?? 1080
        self.multiMonitorSpanning = try c.decodeIfPresent(Bool.self, forKey: .multiMonitorSpanning) ?? false
        self.selectedMonitorIds = try c.decodeIfPresent([Int].self, forKey: .selectedMonitorIds) ?? []
        self.colorDepthBits = try c.decodeIfPresent(Int.self, forKey: .colorDepthBits) ?? 32
        self.redirectClipboard = try c.decodeIfPresent(Bool.self, forKey: .redirectClipboard) ?? true
        self.audioRedirectionMode = try c.decodeIfPresent(RdpAudioRedirectionMode.self, forKey: .audioRedirectionMode) ?? .PLAY_ON_THIS_DEVICE
        self.redirectAudioPlayback = try c.decodeIfPresent(Bool.self, forKey: .redirectAudioPlayback) ?? true
        self.redirectMicrophone = try c.decodeIfPresent(Bool.self, forKey: .redirectMicrophone) ?? false
        self.redirectDrives = try c.decodeIfPresent(Bool.self, forKey: .redirectDrives) ?? false
        self.redirectedDriveSafUris = try c.decodeIfPresent([String].self, forKey: .redirectedDriveSafUris) ?? []
        self.redirectPrinters = try c.decodeIfPresent(Bool.self, forKey: .redirectPrinters) ?? false
        self.redirectPorts = try c.decodeIfPresent(Bool.self, forKey: .redirectPorts) ?? false
        self.redirectSmartcardYubiKey = try c.decodeIfPresent(Bool.self, forKey: .redirectSmartcardYubiKey) ?? true
        self.enableFontSmoothing = try c.decodeIfPresent(Bool.self, forKey: .enableFontSmoothing) ?? true
        self.enableDesktopComposition = try c.decodeIfPresent(Bool.self, forKey: .enableDesktopComposition) ?? true
        self.enableWallpaper = try c.decodeIfPresent(Bool.self, forKey: .enableWallpaper) ?? false
        self.enableFullWindowDrag = try c.decodeIfPresent(Bool.self, forKey: .enableFullWindowDrag) ?? false
        self.enableMenuAnimations = try c.decodeIfPresent(Bool.self, forKey: .enableMenuAnimations) ?? false
        self.enableThemes = try c.decodeIfPresent(Bool.self, forKey: .enableThemes) ?? true
        self.compressionEnabled = try c.decodeIfPresent(Bool.self, forKey: .compressionEnabled) ?? true
        self.remoteAppMode = try c.decodeIfPresent(Bool.self, forKey: .remoteAppMode) ?? false
        self.remoteAppProgram = try c.decodeIfPresent(String.self, forKey: .remoteAppProgram) ?? ""
        self.remoteAppArguments = try c.decodeIfPresent(String.self, forKey: .remoteAppArguments) ?? ""
        self.rdGatewayHostname = try c.decodeIfPresent(String.self, forKey: .rdGatewayHostname) ?? ""
        self.rdGatewayPort = try c.decodeIfPresent(Int.self, forKey: .rdGatewayPort) ?? 443
        self.nlaSecurityRequired = try c.decodeIfPresent(Bool.self, forKey: .nlaSecurityRequired) ?? true
        self.tlsSecurity = try c.decodeIfPresent(Bool.self, forKey: .tlsSecurity) ?? true
        self.restrictedAdminMode = try c.decodeIfPresent(Bool.self, forKey: .restrictedAdminMode) ?? false
        self.ignoreCertificateWarnings = try c.decodeIfPresent(Bool.self, forKey: .ignoreCertificateWarnings) ?? false
    }

    public var macSharedFolderPath: String? {
        for uri in redirectedDriveSafUris {
            if uri.hasPrefix("file://"), let url = URL(string: uri), url.isFileURL {
                return url.path
            }
            if uri.hasPrefix("/") {
                return uri
            }
        }
        return nil
    }

    public var macSharedDriveName: String {
        guard let path = macSharedFolderPath, !path.isEmpty else { return "NexusTS" }
        let last = (path as NSString).lastPathComponent
        return last.isEmpty ? "Mac" : last
    }
}

public struct SshProtocolSettings: Codable, Equatable, Hashable, Sendable {
    public var port: Int = 22
    public var terminalType: String = "xterm-256color"
    public var bastionJumpConnectionId: String? = nil
    public var enableAgentForwarding: Bool = false
    public var enableX11Forwarding: Bool = false
    public var x11DisplayHost: String = "127.0.0.1:0"
    public var portForwards: [SshPortForwardRule] = []
    public var startupCommand: String = ""
    public var keepAliveIntervalSec: Int = 30
    public var scrollbackLines: Int = 10000
    public var serialBaudRate: Int = 115200
    public var serialDevicePath: String = "/dev/cu.usbserial"

    public init(port: Int = 22) {
        self.port = port
    }

    enum CodingKeys: String, CodingKey {
        case port, terminalType, bastionJumpConnectionId, enableAgentForwarding
        case enableX11Forwarding, x11DisplayHost, portForwards, startupCommand
        case keepAliveIntervalSec, scrollbackLines, serialBaudRate, serialDevicePath
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.port = try c.decodeIfPresent(Int.self, forKey: .port) ?? 22
        self.terminalType = try c.decodeIfPresent(String.self, forKey: .terminalType) ?? "xterm-256color"
        self.bastionJumpConnectionId = try c.decodeIfPresent(String.self, forKey: .bastionJumpConnectionId)
        self.enableAgentForwarding = try c.decodeIfPresent(Bool.self, forKey: .enableAgentForwarding) ?? false
        self.enableX11Forwarding = try c.decodeIfPresent(Bool.self, forKey: .enableX11Forwarding) ?? false
        self.x11DisplayHost = try c.decodeIfPresent(String.self, forKey: .x11DisplayHost) ?? "127.0.0.1:0"
        self.portForwards = try c.decodeIfPresent([SshPortForwardRule].self, forKey: .portForwards) ?? []
        self.startupCommand = try c.decodeIfPresent(String.self, forKey: .startupCommand) ?? ""
        self.keepAliveIntervalSec = try c.decodeIfPresent(Int.self, forKey: .keepAliveIntervalSec) ?? 30
        self.scrollbackLines = try c.decodeIfPresent(Int.self, forKey: .scrollbackLines) ?? 10000
        self.serialBaudRate = try c.decodeIfPresent(Int.self, forKey: .serialBaudRate) ?? 115200
        self.serialDevicePath = try c.decodeIfPresent(String.self, forKey: .serialDevicePath) ?? "/dev/ttyUSB0"
    }
}

public struct FileTransferProtocolSettings: Codable, Equatable, Hashable, Sendable {
    public var port: Int = 22
    public var initialLocalDirectory: String = ""
    public var initialRemoteDirectory: String = "/"
    public var preserveTimestamps: Bool = true
    public var showHiddenDotfiles: Bool = true
    public var maxConcurrentTransfers: Int = 4
    public var ftpsImplicitTls: Bool = false

    public init(port: Int = 22, ftpsImplicitTls: Bool = false) {
        self.port = port
        self.ftpsImplicitTls = ftpsImplicitTls
    }

    enum CodingKeys: String, CodingKey {
        case port, initialLocalDirectory, initialRemoteDirectory
        case preserveTimestamps, showHiddenDotfiles, maxConcurrentTransfers, ftpsImplicitTls
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.port = try c.decodeIfPresent(Int.self, forKey: .port) ?? 22
        self.initialLocalDirectory = try c.decodeIfPresent(String.self, forKey: .initialLocalDirectory) ?? ""
        self.initialRemoteDirectory = try c.decodeIfPresent(String.self, forKey: .initialRemoteDirectory) ?? "/"
        self.preserveTimestamps = try c.decodeIfPresent(Bool.self, forKey: .preserveTimestamps) ?? true
        self.showHiddenDotfiles = try c.decodeIfPresent(Bool.self, forKey: .showHiddenDotfiles) ?? true
        self.maxConcurrentTransfers = try c.decodeIfPresent(Int.self, forKey: .maxConcurrentTransfers) ?? 4
        self.ftpsImplicitTls = try c.decodeIfPresent(Bool.self, forKey: .ftpsImplicitTls) ?? false
    }
}

public struct VncProtocolSettings: Codable, Equatable, Hashable, Sendable {
    public var port: Int = 5900
    public var preferredEncodings: [String] = ["ZRLE", "Tight", "CopyRect", "Hextile", "Raw"]
    public var compressionLevel: Int = 6
    public var jpegQualityLevel: Int = 7
    public var viewOnly: Bool = false
    public var sharedSession: Bool = true
    public var appleArdDiffieHellmanAuth: Bool = false

    public init(port: Int = 5900, appleArdDiffieHellmanAuth: Bool = false) {
        self.port = port
        self.appleArdDiffieHellmanAuth = appleArdDiffieHellmanAuth
    }

    enum CodingKeys: String, CodingKey {
        case port, preferredEncodings, compressionLevel, jpegQualityLevel
        case viewOnly, sharedSession, appleArdDiffieHellmanAuth
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.port = try c.decodeIfPresent(Int.self, forKey: .port) ?? 5900
        self.preferredEncodings = try c.decodeIfPresent([String].self, forKey: .preferredEncodings)
            ?? ["ZRLE", "Tight", "CopyRect", "Hextile", "Raw"]
        self.compressionLevel = try c.decodeIfPresent(Int.self, forKey: .compressionLevel) ?? 6
        self.jpegQualityLevel = try c.decodeIfPresent(Int.self, forKey: .jpegQualityLevel) ?? 7
        self.viewOnly = try c.decodeIfPresent(Bool.self, forKey: .viewOnly) ?? false
        self.sharedSession = try c.decodeIfPresent(Bool.self, forKey: .sharedSession) ?? true
        self.appleArdDiffieHellmanAuth = try c.decodeIfPresent(Bool.self, forKey: .appleArdDiffieHellmanAuth) ?? false
    }
}

public struct WebPageProtocolSettings: Codable, Equatable, Hashable, Sendable {
    public var url: String
    public var isolatedStorageProfileId: String
    public var autoFillEnabled: Bool
    public var usernameCssSelector: String
    public var passwordCssSelector: String
    public var submitCssSelector: String
    public var injectHttpBasicAuth: Bool
    public var pinnedCertSha256: String
    public var ignoreSelfSignedInternalCert: Bool

    public init(
        url: String = "https://localhost",
        isolatedStorageProfileId: String = UUID().uuidString.lowercased(),
        autoFillEnabled: Bool = true,
        usernameCssSelector: String = "input[type='email'], input[name*='user'], input[type='text']",
        passwordCssSelector: String = "input[type='password']",
        submitCssSelector: String = "button[type='submit'], input[type='submit']",
        injectHttpBasicAuth: Bool = false,
        pinnedCertSha256: String = "",
        ignoreSelfSignedInternalCert: Bool = false
    ) {
        self.url = url
        self.isolatedStorageProfileId = isolatedStorageProfileId
        self.autoFillEnabled = autoFillEnabled
        self.usernameCssSelector = usernameCssSelector
        self.passwordCssSelector = passwordCssSelector
        self.submitCssSelector = submitCssSelector
        self.injectHttpBasicAuth = injectHttpBasicAuth
        self.pinnedCertSha256 = pinnedCertSha256
        self.ignoreSelfSignedInternalCert = ignoreSelfSignedInternalCert
    }

    enum CodingKeys: String, CodingKey {
        case url, isolatedStorageProfileId, autoFillEnabled, usernameCssSelector
        case passwordCssSelector, submitCssSelector, injectHttpBasicAuth
        case pinnedCertSha256, ignoreSelfSignedInternalCert
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.url = try c.decodeIfPresent(String.self, forKey: .url) ?? "https://localhost"
        self.isolatedStorageProfileId = try c.decodeIfPresent(String.self, forKey: .isolatedStorageProfileId) ?? UUID().uuidString.lowercased()
        self.autoFillEnabled = try c.decodeIfPresent(Bool.self, forKey: .autoFillEnabled) ?? true
        self.usernameCssSelector = try c.decodeIfPresent(String.self, forKey: .usernameCssSelector) ?? "input[type='email'], input[name*='user'], input[type='text']"
        self.passwordCssSelector = try c.decodeIfPresent(String.self, forKey: .passwordCssSelector) ?? "input[type='password']"
        self.submitCssSelector = try c.decodeIfPresent(String.self, forKey: .submitCssSelector) ?? "button[type='submit'], input[type='submit']"
        self.injectHttpBasicAuth = try c.decodeIfPresent(Bool.self, forKey: .injectHttpBasicAuth) ?? false
        self.pinnedCertSha256 = try c.decodeIfPresent(String.self, forKey: .pinnedCertSha256) ?? ""
        self.ignoreSelfSignedInternalCert = try c.decodeIfPresent(Bool.self, forKey: .ignoreSelfSignedInternalCert) ?? false
    }
}

public struct CloudVirtualizationProtocolSettings: Codable, Equatable, Hashable, Sendable {
    public var apiEndpointUrl: String
    public var regionOrCluster: String
    public var subscriptionOrAccountId: String
    public var realmOrDomain: String
    public var autoSyncDynamicInventory: Bool

    public init(
        apiEndpointUrl: String = "https://localhost:8006",
        regionOrCluster: String = "",
        subscriptionOrAccountId: String = "",
        realmOrDomain: String = "pam",
        autoSyncDynamicInventory: Bool = true
    ) {
        self.apiEndpointUrl = apiEndpointUrl
        self.regionOrCluster = regionOrCluster
        self.subscriptionOrAccountId = subscriptionOrAccountId
        self.realmOrDomain = realmOrDomain
        self.autoSyncDynamicInventory = autoSyncDynamicInventory
    }

    enum CodingKeys: String, CodingKey {
        case apiEndpointUrl, regionOrCluster, subscriptionOrAccountId, realmOrDomain, autoSyncDynamicInventory
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.apiEndpointUrl = try c.decodeIfPresent(String.self, forKey: .apiEndpointUrl) ?? ""
        self.regionOrCluster = try c.decodeIfPresent(String.self, forKey: .regionOrCluster) ?? ""
        self.subscriptionOrAccountId = try c.decodeIfPresent(String.self, forKey: .subscriptionOrAccountId) ?? ""
        self.realmOrDomain = try c.decodeIfPresent(String.self, forKey: .realmOrDomain) ?? "pam"
        self.autoSyncDynamicInventory = try c.decodeIfPresent(Bool.self, forKey: .autoSyncDynamicInventory) ?? true
    }
}

public struct NetUtilityProtocolSettings: Codable, Equatable, Hashable, Sendable {
    public var pingCount: Int = 10
    public var pingIntervalMs: Int = 1000
    public var tracerouteMaxHops: Int = 30
    public var portScanRangeStart: Int = 1
    public var portScanRangeEnd: Int = 1024
    public var portScanConcurrency: Int = 128
    public var wolMacAddress: String = ""
    public var wolBroadcastAddress: String = "255.255.255.255"
    public var wolUdpPort: Int = 9
    public var wolSecureOnHex: String = ""

    public init() {}

    enum CodingKeys: String, CodingKey {
        case pingCount, pingIntervalMs, tracerouteMaxHops, portScanRangeStart
        case portScanRangeEnd, portScanConcurrency, wolMacAddress, wolBroadcastAddress
        case wolUdpPort, wolSecureOnHex
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.pingCount = try c.decodeIfPresent(Int.self, forKey: .pingCount) ?? 10
        self.pingIntervalMs = try c.decodeIfPresent(Int.self, forKey: .pingIntervalMs) ?? 1000
        self.tracerouteMaxHops = try c.decodeIfPresent(Int.self, forKey: .tracerouteMaxHops) ?? 30
        self.portScanRangeStart = try c.decodeIfPresent(Int.self, forKey: .portScanRangeStart) ?? 1
        self.portScanRangeEnd = try c.decodeIfPresent(Int.self, forKey: .portScanRangeEnd) ?? 1024
        self.portScanConcurrency = try c.decodeIfPresent(Int.self, forKey: .portScanConcurrency) ?? 128
        self.wolMacAddress = try c.decodeIfPresent(String.self, forKey: .wolMacAddress) ?? ""
        self.wolBroadcastAddress = try c.decodeIfPresent(String.self, forKey: .wolBroadcastAddress) ?? "255.255.255.255"
        self.wolUdpPort = try c.decodeIfPresent(Int.self, forKey: .wolUdpPort) ?? 9
        self.wolSecureOnHex = try c.decodeIfPresent(String.self, forKey: .wolSecureOnHex) ?? ""
    }
}

public struct SerialUartProtocolSettings: Codable, Equatable, Hashable, Sendable {
    public var baudRate: Int = 115200
    public var dataBits: Int = 8
    public var stopBits: Int = 1
    public var parity: String = "NONE"
    public var flowControl: String = "NONE"
    public var dtrEnabled: Bool = true
    public var rtsEnabled: Bool = true
    public var usbVendorIdFilter: Int = 0
    public var devicePathHint: String = "/dev/cu.usbserial"
    public var lineEnding: String = "CRLF"

    public init() {}

    enum CodingKeys: String, CodingKey {
        case baudRate, dataBits, stopBits, parity, flowControl
        case dtrEnabled, rtsEnabled, usbVendorIdFilter, devicePathHint, lineEnding
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.baudRate = try c.decodeIfPresent(Int.self, forKey: .baudRate) ?? 115200
        self.dataBits = try c.decodeIfPresent(Int.self, forKey: .dataBits) ?? 8
        self.stopBits = try c.decodeIfPresent(Int.self, forKey: .stopBits) ?? 1
        self.parity = try c.decodeIfPresent(String.self, forKey: .parity) ?? "NONE"
        self.flowControl = try c.decodeIfPresent(String.self, forKey: .flowControl) ?? "NONE"
        self.dtrEnabled = try c.decodeIfPresent(Bool.self, forKey: .dtrEnabled) ?? true
        self.rtsEnabled = try c.decodeIfPresent(Bool.self, forKey: .rtsEnabled) ?? true
        self.usbVendorIdFilter = try c.decodeIfPresent(Int.self, forKey: .usbVendorIdFilter) ?? 0
        self.devicePathHint = try c.decodeIfPresent(String.self, forKey: .devicePathHint) ?? "/dev/ttyUSB0"
        self.lineEnding = try c.decodeIfPresent(String.self, forKey: .lineEnding) ?? "CRLF"
    }
}

public struct ContainerExecProtocolSettings: Codable, Equatable, Hashable, Sendable {
    public var clusterOrSocketEndpoint: String = "https://kubernetes.default.svc:6443"
    public var contextName: String = "prod-eu-central"
    public var namespace: String = "default"
    public var podOrContainerName: String = ""
    public var subContainerName: String = ""
    public var shellCommand: String = "/bin/sh"
    public var followLogsOnOpen: Bool = false
    public var logTailLines: Int = 200
    public var portForwards: [String] = []
    public var verifyTls: Bool = true

    public init(
        clusterOrSocketEndpoint: String = "https://kubernetes.default.svc:6443",
        podOrContainerName: String = ""
    ) {
        self.clusterOrSocketEndpoint = clusterOrSocketEndpoint
        self.podOrContainerName = podOrContainerName
    }

    enum CodingKeys: String, CodingKey {
        case clusterOrSocketEndpoint, contextName, namespace, podOrContainerName
        case subContainerName, shellCommand, followLogsOnOpen, logTailLines, portForwards, verifyTls
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.clusterOrSocketEndpoint = try c.decodeIfPresent(String.self, forKey: .clusterOrSocketEndpoint) ?? "https://kubernetes.default.svc:6443"
        self.contextName = try c.decodeIfPresent(String.self, forKey: .contextName) ?? "prod-eu-central"
        self.namespace = try c.decodeIfPresent(String.self, forKey: .namespace) ?? "default"
        self.podOrContainerName = try c.decodeIfPresent(String.self, forKey: .podOrContainerName) ?? ""
        self.subContainerName = try c.decodeIfPresent(String.self, forKey: .subContainerName) ?? ""
        self.shellCommand = try c.decodeIfPresent(String.self, forKey: .shellCommand) ?? "/bin/sh"
        self.followLogsOnOpen = try c.decodeIfPresent(Bool.self, forKey: .followLogsOnOpen) ?? false
        self.logTailLines = try c.decodeIfPresent(Int.self, forKey: .logTailLines) ?? 200
        self.portForwards = try c.decodeIfPresent([String].self, forKey: .portForwards) ?? []
        self.verifyTls = try c.decodeIfPresent(Bool.self, forKey: .verifyTls) ?? true
    }
}

public enum ProtocolSpecificSettings: Codable, Equatable, Hashable, Sendable {
    case rdp(RdpProtocolSettings)
    case ssh(SshProtocolSettings)
    case fileTransfer(FileTransferProtocolSettings)
    case vnc(VncProtocolSettings)
    case webPage(WebPageProtocolSettings)
    case cloudVirtualization(CloudVirtualizationProtocolSettings)
    case netUtility(NetUtilityProtocolSettings)
    case serialUart(SerialUartProtocolSettings)
    case containerExec(ContainerExecProtocolSettings)

    private enum DiscriminatorKey: String, CodingKey {
        case type = "_type"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: DiscriminatorKey.self)
        let tag = try c.decodeIfPresent(String.self, forKey: .type) ?? "ssh"
        switch tag {
        case "rdp":
            self = .rdp(try RdpProtocolSettings(from: decoder))
        case "ssh":
            self = .ssh(try SshProtocolSettings(from: decoder))
        case "file_transfer":
            self = .fileTransfer(try FileTransferProtocolSettings(from: decoder))
        case "vnc":
            self = .vnc(try VncProtocolSettings(from: decoder))
        case "web_page":
            self = .webPage(try WebPageProtocolSettings(from: decoder))
        case "cloud_virtualization":
            self = .cloudVirtualization(try CloudVirtualizationProtocolSettings(from: decoder))
        case "net_utility":
            self = .netUtility(try NetUtilityProtocolSettings(from: decoder))
        case "serial_uart":
            self = .serialUart(try SerialUartProtocolSettings(from: decoder))
        case "container_exec":
            self = .containerExec(try ContainerExecProtocolSettings(from: decoder))
        default:
            self = .ssh(try SshProtocolSettings(from: decoder))
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: DiscriminatorKey.self)
        switch self {
        case .rdp(let v):
            try c.encode("rdp", forKey: .type)
            try v.encode(to: encoder)
        case .ssh(let v):
            try c.encode("ssh", forKey: .type)
            try v.encode(to: encoder)
        case .fileTransfer(let v):
            try c.encode("file_transfer", forKey: .type)
            try v.encode(to: encoder)
        case .vnc(let v):
            try c.encode("vnc", forKey: .type)
            try v.encode(to: encoder)
        case .webPage(let v):
            try c.encode("web_page", forKey: .type)
            try v.encode(to: encoder)
        case .cloudVirtualization(let v):
            try c.encode("cloud_virtualization", forKey: .type)
            try v.encode(to: encoder)
        case .netUtility(let v):
            try c.encode("net_utility", forKey: .type)
            try v.encode(to: encoder)
        case .serialUart(let v):
            try c.encode("serial_uart", forKey: .type)
            try v.encode(to: encoder)
        case .containerExec(let v):
            try c.encode("container_exec", forKey: .type)
            try v.encode(to: encoder)
        }
    }

    public var effectivePort: Int {
        switch self {
        case .rdp(let s): return s.port
        case .ssh(let s): return s.port
        case .fileTransfer(let s): return s.port
        case .vnc(let s): return s.port
        case .webPage: return 443
        case .cloudVirtualization: return 443
        case .netUtility(let s): return s.wolUdpPort
        case .serialUart(let s): return s.baudRate
        case .containerExec: return 6443
        }
    }
}

// MARK: - Vault Document & Collection Entities

public protocol VaultTimestampedEntity: Identifiable, Codable, Equatable, Sendable {
    var id: String { get }
    var updatedAtEpochMs: Int64 { get }
}

public struct VaultFolder: VaultTimestampedEntity, Hashable {
    public var id: String
    public var parentFolderId: String?
    public var name: String
    public var description: String
    public var tags: [String]
    public var inheritable: InheritableProperties
    public var sortOrder: Int
    public var updatedAtEpochMs: Int64

    public init(
        id: String = UUID().uuidString.lowercased(),
        parentFolderId: String? = nil,
        name: String,
        description: String = "",
        tags: [String] = [],
        inheritable: InheritableProperties = InheritableProperties(),
        sortOrder: Int = 0,
        updatedAtEpochMs: Int64 = VaultClock.nowMs()
    ) {
        self.id = id
        self.parentFolderId = parentFolderId
        self.name = name
        self.description = description
        self.tags = tags
        self.inheritable = inheritable
        self.sortOrder = sortOrder
        self.updatedAtEpochMs = updatedAtEpochMs
    }

    enum CodingKeys: String, CodingKey {
        case id, parentFolderId, name, description, tags, inheritable, sortOrder, updatedAtEpochMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.parentFolderId = try c.decodeIfPresent(String.self, forKey: .parentFolderId)
        self.name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Folder"
        self.description = try c.decodeIfPresent(String.self, forKey: .description) ?? ""
        self.tags = try c.decodeIfPresent([String].self, forKey: .tags) ?? []
        self.inheritable = try c.decodeIfPresent(InheritableProperties.self, forKey: .inheritable) ?? InheritableProperties()
        self.sortOrder = try c.decodeIfPresent(Int.self, forKey: .sortOrder) ?? 0
        self.updatedAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .updatedAtEpochMs) ?? 0
    }
}

public struct SmartFolderFilter: Codable, Equatable, Hashable, Sendable {
    public var includeTags: [String]
    public var excludeTags: [String]
    public var includeProtocols: [VaultProtocolType]
    public var osFamilyFilter: String?
    public var hostPatternRegex: String?
    public var environmentFilter: String?
    public var scopeFolderId: String?

    public init(
        includeTags: [String] = [],
        excludeTags: [String] = [],
        includeProtocols: [VaultProtocolType] = [],
        osFamilyFilter: String? = nil,
        hostPatternRegex: String? = nil,
        environmentFilter: String? = nil,
        scopeFolderId: String? = nil
    ) {
        self.includeTags = includeTags
        self.excludeTags = excludeTags
        self.includeProtocols = includeProtocols
        self.osFamilyFilter = osFamilyFilter
        self.hostPatternRegex = hostPatternRegex
        self.environmentFilter = environmentFilter
        self.scopeFolderId = scopeFolderId
    }

    enum CodingKeys: String, CodingKey {
        case includeTags, excludeTags, includeProtocols, osFamilyFilter, hostPatternRegex, environmentFilter, scopeFolderId
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.includeTags = try c.decodeIfPresent([String].self, forKey: .includeTags) ?? []
        self.excludeTags = try c.decodeIfPresent([String].self, forKey: .excludeTags) ?? []
        self.includeProtocols = try c.decodeIfPresent([VaultProtocolType].self, forKey: .includeProtocols) ?? []
        self.osFamilyFilter = try c.decodeIfPresent(String.self, forKey: .osFamilyFilter)
        self.hostPatternRegex = try c.decodeIfPresent(String.self, forKey: .hostPatternRegex)
        self.environmentFilter = try c.decodeIfPresent(String.self, forKey: .environmentFilter)
        self.scopeFolderId = try c.decodeIfPresent(String.self, forKey: .scopeFolderId)
    }
}

public struct SmartFolder: VaultTimestampedEntity, Hashable {
    public var id: String
    public var name: String
    public var filter: SmartFolderFilter
    public var updatedAtEpochMs: Int64

    public init(
        id: String = UUID().uuidString.lowercased(),
        name: String,
        filter: SmartFolderFilter = SmartFolderFilter(),
        updatedAtEpochMs: Int64 = VaultClock.nowMs()
    ) {
        self.id = id
        self.name = name
        self.filter = filter
        self.updatedAtEpochMs = updatedAtEpochMs
    }

    enum CodingKeys: String, CodingKey { case id, name, filter, updatedAtEpochMs }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Smart Folder"
        self.filter = try c.decodeIfPresent(SmartFolderFilter.self, forKey: .filter) ?? SmartFolderFilter()
        self.updatedAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .updatedAtEpochMs) ?? 0
    }
}

public struct DynamicFolder: VaultTimestampedEntity, Hashable {
    public var id: String
    public var parentFolderId: String?
    public var name: String
    public var interpreter: DynamicScriptInterpreter
    public var scriptOrEndpointPayload: String
    public var refreshIntervalMinutes: Int
    public var inheritable: InheritableProperties
    public var updatedAtEpochMs: Int64

    public init(
        id: String = UUID().uuidString.lowercased(),
        parentFolderId: String? = nil,
        name: String,
        interpreter: DynamicScriptInterpreter = .JSON_HTTP_API,
        scriptOrEndpointPayload: String = "{\"Objects\":[]}",
        refreshIntervalMinutes: Int = 30,
        inheritable: InheritableProperties = InheritableProperties(),
        updatedAtEpochMs: Int64 = VaultClock.nowMs()
    ) {
        self.id = id
        self.parentFolderId = parentFolderId
        self.name = name
        self.interpreter = interpreter
        self.scriptOrEndpointPayload = scriptOrEndpointPayload
        self.refreshIntervalMinutes = refreshIntervalMinutes
        self.inheritable = inheritable
        self.updatedAtEpochMs = updatedAtEpochMs
    }

    enum CodingKeys: String, CodingKey {
        case id, parentFolderId, name, interpreter, scriptOrEndpointPayload
        case refreshIntervalMinutes, inheritable, updatedAtEpochMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.parentFolderId = try c.decodeIfPresent(String.self, forKey: .parentFolderId)
        self.name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Dynamic Folder"
        self.interpreter = try c.decodeIfPresent(DynamicScriptInterpreter.self, forKey: .interpreter) ?? .JSON_HTTP_API
        self.scriptOrEndpointPayload = try c.decodeIfPresent(String.self, forKey: .scriptOrEndpointPayload) ?? "{\"Objects\":[]}"
        self.refreshIntervalMinutes = try c.decodeIfPresent(Int.self, forKey: .refreshIntervalMinutes) ?? 30
        self.inheritable = try c.decodeIfPresent(InheritableProperties.self, forKey: .inheritable) ?? InheritableProperties()
        self.updatedAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .updatedAtEpochMs) ?? 0
    }
}

public struct VaultConnection: VaultTimestampedEntity, Hashable {
    public var id: String
    public var parentFolderId: String?
    public var name: String
    public var host: String
    public var protocolType: VaultProtocolType
    public var osFamily: String
    public var tags: [String]
    public var notes: String
    public var favorite: Bool
    public var sortOrder: Int
    public var inheritable: InheritableProperties
    public var settings: ProtocolSpecificSettings
    public var updatedAtEpochMs: Int64

    public init(
        id: String = UUID().uuidString.lowercased(),
        parentFolderId: String? = nil,
        name: String,
        host: String,
        protocolType: VaultProtocolType,
        osFamily: String = "Linux",
        tags: [String] = [],
        notes: String = "",
        favorite: Bool = false,
        sortOrder: Int = 0,
        inheritable: InheritableProperties = InheritableProperties(),
        settings: ProtocolSpecificSettings? = nil,
        updatedAtEpochMs: Int64 = VaultClock.nowMs()
    ) {
        self.id = id
        self.parentFolderId = parentFolderId
        self.name = name
        self.host = host
        self.protocolType = protocolType
        self.osFamily = osFamily
        self.tags = tags
        self.notes = notes
        self.favorite = favorite
        self.sortOrder = sortOrder
        self.inheritable = inheritable
        self.settings = settings ?? protocolType.defaultSettings(host: host)
        self.updatedAtEpochMs = updatedAtEpochMs
    }

    enum CodingKeys: String, CodingKey {
        case id, parentFolderId, name, host
        case protocolType = "protocol"
        case osFamily, tags, notes, favorite, sortOrder, inheritable, settings, updatedAtEpochMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.parentFolderId = try c.decodeIfPresent(String.self, forKey: .parentFolderId)
        self.name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Connection"
        let hostVal = try c.decodeIfPresent(String.self, forKey: .host) ?? ""
        self.host = hostVal
        let proto = try c.decodeIfPresent(VaultProtocolType.self, forKey: .protocolType) ?? .SSH
        self.protocolType = proto
        self.osFamily = try c.decodeIfPresent(String.self, forKey: .osFamily) ?? "Linux"
        self.tags = try c.decodeIfPresent([String].self, forKey: .tags) ?? []
        self.notes = try c.decodeIfPresent(String.self, forKey: .notes) ?? ""
        self.favorite = try c.decodeIfPresent(Bool.self, forKey: .favorite) ?? false
        self.sortOrder = try c.decodeIfPresent(Int.self, forKey: .sortOrder) ?? 0
        self.inheritable = try c.decodeIfPresent(InheritableProperties.self, forKey: .inheritable) ?? InheritableProperties()
        self.settings = try c.decodeIfPresent(ProtocolSpecificSettings.self, forKey: .settings) ?? proto.defaultSettings(host: hostVal)
        self.updatedAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .updatedAtEpochMs) ?? 0
    }
}

public struct VaultCredential: VaultTimestampedEntity, Hashable {
    public var id: String
    public var parentFolderId: String?
    public var name: String
    public var sourceKind: CredentialSourceKind
    public var username: String
    public var domain: String
    public var secretPassword: String
    public var privateKeyPem: String
    public var publicKeyOpenSsh: String
    public var keyFingerprintSha256: String
    public var keyPassphrase: String
    public var externalReferenceUri: String
    public var fido2CredentialIdBase64: String
    public var totpSecretBase32: String
    public var updatedAtEpochMs: Int64

    public init(
        id: String = UUID().uuidString.lowercased(),
        parentFolderId: String? = nil,
        name: String,
        sourceKind: CredentialSourceKind = .VAULT_INTERNAL,
        username: String = "",
        domain: String = "",
        secretPassword: String = "",
        privateKeyPem: String = "",
        publicKeyOpenSsh: String = "",
        keyFingerprintSha256: String = "",
        keyPassphrase: String = "",
        externalReferenceUri: String = "",
        fido2CredentialIdBase64: String = "",
        totpSecretBase32: String = "",
        updatedAtEpochMs: Int64 = VaultClock.nowMs()
    ) {
        self.id = id
        self.parentFolderId = parentFolderId
        self.name = name
        self.sourceKind = sourceKind
        self.username = username
        self.domain = domain
        self.secretPassword = secretPassword
        self.privateKeyPem = privateKeyPem
        self.publicKeyOpenSsh = publicKeyOpenSsh
        self.keyFingerprintSha256 = keyFingerprintSha256
        self.keyPassphrase = keyPassphrase
        self.externalReferenceUri = externalReferenceUri
        self.fido2CredentialIdBase64 = fido2CredentialIdBase64
        self.totpSecretBase32 = totpSecretBase32
        self.updatedAtEpochMs = updatedAtEpochMs
    }

    enum CodingKeys: String, CodingKey {
        case id, parentFolderId, name, sourceKind, username, domain, secretPassword
        case privateKeyPem, publicKeyOpenSsh, keyFingerprintSha256, keyPassphrase
        case externalReferenceUri, fido2CredentialIdBase64, totpSecretBase32, updatedAtEpochMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.parentFolderId = try c.decodeIfPresent(String.self, forKey: .parentFolderId)
        self.name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Credential"
        self.sourceKind = try c.decodeIfPresent(CredentialSourceKind.self, forKey: .sourceKind) ?? .VAULT_INTERNAL
        self.username = try c.decodeIfPresent(String.self, forKey: .username) ?? ""
        self.domain = try c.decodeIfPresent(String.self, forKey: .domain) ?? ""
        self.secretPassword = try c.decodeIfPresent(String.self, forKey: .secretPassword) ?? ""
        self.privateKeyPem = try c.decodeIfPresent(String.self, forKey: .privateKeyPem) ?? ""
        self.publicKeyOpenSsh = try c.decodeIfPresent(String.self, forKey: .publicKeyOpenSsh) ?? ""
        self.keyFingerprintSha256 = try c.decodeIfPresent(String.self, forKey: .keyFingerprintSha256) ?? ""
        self.keyPassphrase = try c.decodeIfPresent(String.self, forKey: .keyPassphrase) ?? ""
        self.externalReferenceUri = try c.decodeIfPresent(String.self, forKey: .externalReferenceUri) ?? ""
        self.fido2CredentialIdBase64 = try c.decodeIfPresent(String.self, forKey: .fido2CredentialIdBase64) ?? ""
        self.totpSecretBase32 = try c.decodeIfPresent(String.self, forKey: .totpSecretBase32) ?? ""
        self.updatedAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .updatedAtEpochMs) ?? 0
    }
}

public struct SecureGatewayConfig: VaultTimestampedEntity, Hashable {
    public var id: String
    public var name: String
    public var kind: GatewayTransportKind
    public var host: String
    public var port: Int
    public var credentialId: String?
    public var verifyTlsCertificate: Bool
    public var updatedAtEpochMs: Int64

    public init(
        id: String = UUID().uuidString.lowercased(),
        name: String,
        kind: GatewayTransportKind = .SSH_TUNNEL_BASTION,
        host: String,
        port: Int = 22,
        credentialId: String? = nil,
        verifyTlsCertificate: Bool = true,
        updatedAtEpochMs: Int64 = VaultClock.nowMs()
    ) {
        self.id = id
        self.name = name
        self.kind = kind
        self.host = host
        self.port = port
        self.credentialId = credentialId
        self.verifyTlsCertificate = verifyTlsCertificate
        self.updatedAtEpochMs = updatedAtEpochMs
    }

    enum CodingKeys: String, CodingKey {
        case id, name, kind, host, port, credentialId, verifyTlsCertificate, updatedAtEpochMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Gateway"
        self.kind = try c.decodeIfPresent(GatewayTransportKind.self, forKey: .kind) ?? .SSH_TUNNEL_BASTION
        self.host = try c.decodeIfPresent(String.self, forKey: .host) ?? ""
        self.port = try c.decodeIfPresent(Int.self, forKey: .port) ?? 443
        self.credentialId = try c.decodeIfPresent(String.self, forKey: .credentialId)
        self.verifyTlsCertificate = try c.decodeIfPresent(Bool.self, forKey: .verifyTlsCertificate) ?? true
        self.updatedAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .updatedAtEpochMs) ?? 0
    }
}

public struct CommandTask: VaultTimestampedEntity, Hashable {
    public var id: String
    public var name: String
    public var commandTemplate: String
    public var broadcastToAllSelectedSessions: Bool
    public var appendNewline: Bool
    public var updatedAtEpochMs: Int64

    public init(
        id: String = UUID().uuidString.lowercased(),
        name: String,
        commandTemplate: String,
        broadcastToAllSelectedSessions: Bool = false,
        appendNewline: Bool = true,
        updatedAtEpochMs: Int64 = VaultClock.nowMs()
    ) {
        self.id = id
        self.name = name
        self.commandTemplate = commandTemplate
        self.broadcastToAllSelectedSessions = broadcastToAllSelectedSessions
        self.appendNewline = appendNewline
        self.updatedAtEpochMs = updatedAtEpochMs
    }

    enum CodingKeys: String, CodingKey {
        case id, name, commandTemplate, broadcastToAllSelectedSessions, appendNewline, updatedAtEpochMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Command Task"
        self.commandTemplate = try c.decodeIfPresent(String.self, forKey: .commandTemplate) ?? ""
        self.broadcastToAllSelectedSessions = try c.decodeIfPresent(Bool.self, forKey: .broadcastToAllSelectedSessions) ?? false
        self.appendNewline = try c.decodeIfPresent(Bool.self, forKey: .appendNewline) ?? true
        self.updatedAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .updatedAtEpochMs) ?? 0
    }
}

public struct KeySequenceTask: VaultTimestampedEntity, Hashable {
    public var id: String
    public var name: String
    public var macroSequence: String
    public var keystrokeDelayMs: Int64
    public var updatedAtEpochMs: Int64

    public init(
        id: String = UUID().uuidString.lowercased(),
        name: String,
        macroSequence: String,
        keystrokeDelayMs: Int64 = 25,
        updatedAtEpochMs: Int64 = VaultClock.nowMs()
    ) {
        self.id = id
        self.name = name
        self.macroSequence = macroSequence
        self.keystrokeDelayMs = keystrokeDelayMs
        self.updatedAtEpochMs = updatedAtEpochMs
    }

    enum CodingKeys: String, CodingKey {
        case id, name, macroSequence, keystrokeDelayMs, updatedAtEpochMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Key Sequence"
        self.macroSequence = try c.decodeIfPresent(String.self, forKey: .macroSequence) ?? ""
        self.keystrokeDelayMs = try c.decodeIfPresent(Int64.self, forKey: .keystrokeDelayMs) ?? 25
        self.updatedAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .updatedAtEpochMs) ?? 0
    }
}

public struct ConnectionTemplate: VaultTimestampedEntity, Hashable {
    public var id: String
    public var name: String
    public var description: String
    public var protocolType: VaultProtocolType
    public var colorAccentHex: String?
    public var inheritable: InheritableProperties
    public var settings: ProtocolSpecificSettings
    public var updatedAtEpochMs: Int64

    public init(
        id: String = UUID().uuidString.lowercased(),
        name: String,
        description: String = "",
        protocolType: VaultProtocolType = .SSH,
        colorAccentHex: String? = nil,
        inheritable: InheritableProperties = InheritableProperties(),
        settings: ProtocolSpecificSettings? = nil,
        updatedAtEpochMs: Int64 = VaultClock.nowMs()
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.protocolType = protocolType
        self.colorAccentHex = colorAccentHex
        self.inheritable = inheritable
        self.settings = settings ?? protocolType.defaultSettings()
        self.updatedAtEpochMs = updatedAtEpochMs
    }

    enum CodingKeys: String, CodingKey {
        case id, name, description
        case protocolType = "protocol"
        case colorAccentHex, inheritable, settings, updatedAtEpochMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Template"
        self.description = try c.decodeIfPresent(String.self, forKey: .description) ?? ""
        let proto = try c.decodeIfPresent(VaultProtocolType.self, forKey: .protocolType) ?? .SSH
        self.protocolType = proto
        self.colorAccentHex = try c.decodeIfPresent(String.self, forKey: .colorAccentHex)
        self.inheritable = try c.decodeIfPresent(InheritableProperties.self, forKey: .inheritable) ?? InheritableProperties()
        self.settings = try c.decodeIfPresent(ProtocolSpecificSettings.self, forKey: .settings) ?? proto.defaultSettings()
        self.updatedAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .updatedAtEpochMs) ?? 0
    }
}

public struct VaultTodoItem: VaultTimestampedEntity, Hashable {
    public var id: String
    public var targetObjectId: String?
    public var title: String
    public var completed: Bool
    public var priority: String
    public var dueIsoDate: String
    public var updatedAtEpochMs: Int64

    public init(
        id: String = UUID().uuidString.lowercased(),
        targetObjectId: String? = nil,
        title: String,
        completed: Bool = false,
        priority: String = "NORMAL",
        dueIsoDate: String = "",
        updatedAtEpochMs: Int64 = VaultClock.nowMs()
    ) {
        self.id = id
        self.targetObjectId = targetObjectId
        self.title = title
        self.completed = completed
        self.priority = priority
        self.dueIsoDate = dueIsoDate
        self.updatedAtEpochMs = updatedAtEpochMs
    }

    enum CodingKeys: String, CodingKey {
        case id, targetObjectId, title, completed, priority, dueIsoDate, updatedAtEpochMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.targetObjectId = try c.decodeIfPresent(String.self, forKey: .targetObjectId)
        self.title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        self.completed = try c.decodeIfPresent(Bool.self, forKey: .completed) ?? false
        self.priority = try c.decodeIfPresent(String.self, forKey: .priority) ?? "NORMAL"
        self.dueIsoDate = try c.decodeIfPresent(String.self, forKey: .dueIsoDate) ?? ""
        self.updatedAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .updatedAtEpochMs) ?? 0
    }
}

public struct NxtsVaultDocument: Codable, Equatable, Sendable {
    public var documentId: String
    public var name: String
    public var description: String
    public var schemaVersion: Int
    public var revision: Int64
    public var lastModifiedByDevice: String
    public var updatedAtEpochMs: Int64
    public var vectorClock: [String: Int64]
    public var rootInheritable: InheritableProperties
    public var folders: [VaultFolder]
    public var smartFolders: [SmartFolder]
    public var dynamicFolders: [DynamicFolder]
    public var connections: [VaultConnection]
    public var credentials: [VaultCredential]
    public var gateways: [SecureGatewayConfig]
    public var commandTasks: [CommandTask]
    public var keySequenceTasks: [KeySequenceTask]
    public var templates: [ConnectionTemplate]
    public var todoItems: [VaultTodoItem]
    public var recentConnectionIds: [String]
    public var tombstonedObjectIds: Set<String>

    public init(
        documentId: String = "nxts-default-vault",
        name: String = "NexusTS Vault",
        description: String = "Zero-Knowledge Argon2id + AES-256-GCM Encrypted Workspace",
        schemaVersion: Int = 2,
        revision: Int64 = 1,
        lastModifiedByDevice: String = "macOS",
        updatedAtEpochMs: Int64 = VaultClock.nowMs(),
        vectorClock: [String: Int64] = ["macos": 1],
        rootInheritable: InheritableProperties = InheritableProperties(
            credentialMode: .NONE_DIRECT,
            gatewayMode: .NONE_DIRECT
        ),
        folders: [VaultFolder] = [],
        smartFolders: [SmartFolder] = [],
        dynamicFolders: [DynamicFolder] = [],
        connections: [VaultConnection] = [],
        credentials: [VaultCredential] = [],
        gateways: [SecureGatewayConfig] = [],
        commandTasks: [CommandTask] = [],
        keySequenceTasks: [KeySequenceTask] = [],
        templates: [ConnectionTemplate] = [],
        todoItems: [VaultTodoItem] = [],
        recentConnectionIds: [String] = [],
        tombstonedObjectIds: Set<String> = []
    ) {
        self.documentId = documentId
        self.name = name
        self.description = description
        self.schemaVersion = schemaVersion
        self.revision = revision
        self.lastModifiedByDevice = lastModifiedByDevice
        self.updatedAtEpochMs = updatedAtEpochMs
        self.vectorClock = vectorClock
        self.rootInheritable = rootInheritable
        self.folders = folders
        self.smartFolders = smartFolders
        self.dynamicFolders = dynamicFolders
        self.connections = connections
        self.credentials = credentials
        self.gateways = gateways
        self.commandTasks = commandTasks
        self.keySequenceTasks = keySequenceTasks
        self.templates = templates
        self.todoItems = todoItems
        self.recentConnectionIds = recentConnectionIds
        self.tombstonedObjectIds = tombstonedObjectIds
    }

    enum CodingKeys: String, CodingKey {
        case documentId, name, description, schemaVersion, revision
        case lastModifiedByDevice, updatedAtEpochMs, vectorClock, rootInheritable
        case folders, smartFolders, dynamicFolders, connections, credentials
        case gateways, commandTasks, keySequenceTasks, templates, todoItems
        case recentConnectionIds, tombstonedObjectIds
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.documentId = try c.decodeIfPresent(String.self, forKey: .documentId) ?? UUID().uuidString.lowercased()
        self.name = try c.decodeIfPresent(String.self, forKey: .name) ?? "NexusTS Vault"
        self.description = try c.decodeIfPresent(String.self, forKey: .description) ?? ""
        self.schemaVersion = try c.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 2
        self.revision = try c.decodeIfPresent(Int64.self, forKey: .revision) ?? 1
        self.lastModifiedByDevice = try c.decodeIfPresent(String.self, forKey: .lastModifiedByDevice) ?? "macOS"
        self.updatedAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .updatedAtEpochMs) ?? VaultClock.nowMs()
        self.vectorClock = try c.decodeIfPresent([String: Int64].self, forKey: .vectorClock) ?? ["local": 1]
        self.rootInheritable = try c.decodeIfPresent(InheritableProperties.self, forKey: .rootInheritable)
            ?? InheritableProperties(credentialMode: .NONE_DIRECT, gatewayMode: .NONE_DIRECT)
        self.folders = try c.decodeIfPresent([VaultFolder].self, forKey: .folders) ?? []
        self.smartFolders = try c.decodeIfPresent([SmartFolder].self, forKey: .smartFolders) ?? []
        self.dynamicFolders = try c.decodeIfPresent([DynamicFolder].self, forKey: .dynamicFolders) ?? []
        self.connections = try c.decodeIfPresent([VaultConnection].self, forKey: .connections) ?? []
        self.credentials = try c.decodeIfPresent([VaultCredential].self, forKey: .credentials) ?? []
        self.gateways = try c.decodeIfPresent([SecureGatewayConfig].self, forKey: .gateways) ?? []
        self.commandTasks = try c.decodeIfPresent([CommandTask].self, forKey: .commandTasks) ?? []
        self.keySequenceTasks = try c.decodeIfPresent([KeySequenceTask].self, forKey: .keySequenceTasks) ?? []
        self.templates = try c.decodeIfPresent([ConnectionTemplate].self, forKey: .templates) ?? []
        self.todoItems = try c.decodeIfPresent([VaultTodoItem].self, forKey: .todoItems) ?? []
        self.recentConnectionIds = try c.decodeIfPresent([String].self, forKey: .recentConnectionIds) ?? []
        self.tombstonedObjectIds = try c.decodeIfPresent(Set<String>.self, forKey: .tombstonedObjectIds) ?? []
    }

    public mutating func bumpRevision(actor: String = "macos") {
        let next = revision + 1
        revision = next
        updatedAtEpochMs = VaultClock.nowMs()
        lastModifiedByDevice = "macOS"
        vectorClock[actor] = max(vectorClock[actor] ?? 0, next)
    }

    public static func sampleStarterVault() -> NxtsVaultDocument {
        NxtsVaultDocument(
            documentId: "nxts-default-vault",
            name: "NexusTS Vault",
            description: "Zero-Knowledge Argon2id + AES-256-GCM Encrypted Workspace"
        )
    }
}

public enum VaultClock {
    public static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000.0)
    }
}

// MARK: - Intelligent Sidebar Connection-Type Grouping

public struct IntelligentConnectionProtocolGroup: Identifiable, Equatable, Sendable {
    public var id: String { groupBadge }
    public let groupBadge: String
    public let primaryProtocol: VaultProtocolType
    public let connections: [VaultConnection]
    public let showsHeader: Bool

    public init(
        groupBadge: String,
        primaryProtocol: VaultProtocolType,
        connections: [VaultConnection],
        showsHeader: Bool
    ) {
        self.groupBadge = groupBadge
        self.primaryProtocol = primaryProtocol
        self.connections = connections
        self.showsHeader = showsHeader
    }
}

public enum IntelligentConnectionGrouper {
    private static let canonicalBadgeOrder: [String] = [
        "RDP", "SSH", "VNC", "ARD", "SFTP", "SCP", "FTP", "FTPS",
        "TEL", "UART", "WEB", "K8S", "OCI", "PVE", "ESXi", "HV",
        "EC2", "AZ", "PING", "MTR", "SCAN", "WOL"
    ]

    public static func groupBadge(for protocolType: VaultProtocolType) -> String {
        switch protocolType {
        case .RDP, .WINDOWS_TERMINAL_SERVICES:
            return "RDP"
        default:
            return protocolType.shortBadge
        }
    }

    private static func rank(for badge: String) -> Int {
        canonicalBadgeOrder.firstIndex(of: badge) ?? canonicalBadgeOrder.count
    }

    public static func group(
        _ connections: [VaultConnection],
        preserveInputOrderWithinGroups: Bool = false
    ) -> [IntelligentConnectionProtocolGroup] {
        guard !connections.isEmpty else { return [] }

        var buckets: [String: [VaultConnection]] = [:]
        var firstProtocolByBadge: [String: VaultProtocolType] = [:]

        for conn in connections {
            let badge = groupBadge(for: conn.protocolType)
            if firstProtocolByBadge[badge] == nil {
                firstProtocolByBadge[badge] = conn.protocolType == .WINDOWS_TERMINAL_SERVICES ? .RDP : conn.protocolType
            }
            buckets[badge, default: []].append(conn)
        }

        let sortedBadges = buckets.keys.sorted { lhs, rhs in
            let lRank = rank(for: lhs)
            let rRank = rank(for: rhs)
            if lRank != rRank { return lRank < rRank }
            return lhs.localizedCaseInsensitiveCompare(rhs) == .orderedAscending
        }

        let showsHeader = sortedBadges.count > 1

        return sortedBadges.compactMap { badge in
            guard let rawItems = buckets[badge], let proto = firstProtocolByBadge[badge] else { return nil }
            let orderedItems: [VaultConnection]
            if preserveInputOrderWithinGroups {
                orderedItems = rawItems
            } else {
                orderedItems = rawItems.sorted {
                    if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
                    return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
                }
            }
            return IntelligentConnectionProtocolGroup(
                groupBadge: badge,
                primaryProtocol: proto,
                connections: orderedItems,
                showsHeader: showsHeader
            )
        }
    }
}

