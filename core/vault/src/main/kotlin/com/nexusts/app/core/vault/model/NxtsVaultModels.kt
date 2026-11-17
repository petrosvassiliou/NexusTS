package com.nexusts.app.core.vault.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class InheritanceMode {
    INHERIT_FROM_PARENT,
    OVERRIDE_EXPLICIT,
    NONE_DIRECT,
    PROMPT_AT_CONNECT
}

@Serializable
data class CustomPropertyEntry(
    val key: String,
    val value: String,
    val isProtected: Boolean = false
)

@Serializable
data class InheritableProperties(
    val credentialMode: InheritanceMode = InheritanceMode.INHERIT_FROM_PARENT,
    val explicitCredentialId: String? = null,
    val gatewayMode: InheritanceMode = InheritanceMode.INHERIT_FROM_PARENT,
    val explicitGatewayId: String? = null,
    val environmentBadge: String? = null,
    val preConnectTaskId: String? = null,
    val postDisconnectTaskId: String? = null,
    val displayColorAccentHex: String? = null,
    val customProperties: List<CustomPropertyEntry> = emptyList(),
    val requireConnectConfirmation: Boolean = false
)

enum class VaultProtocolCategory(val title: String) {
    REMOTE_DESKTOP("Remote Desktop"),
    TERMINAL_CONTAINERS("Terminal & Containers"),
    FILE_TRANSFER("File Transfer"),
    WEB_CLOUD("Web & Cloud"),
    NETWORK_UTILITIES("Network Tools")
}

@Serializable
enum class VaultProtocolType {
    RDP,
    SSH,
    TELNET,
    SERIAL,
    SFTP,
    FTP,
    FTPS,
    SCP,
    VNC,
    APPLE_SCREEN_SHARING,
    WEB_PAGE,
    VMWARE_VSPHERE,
    PROXMOX_VE,
    HYPER_V,
    AWS_EC2,
    AZURE_VM,
    WINDOWS_TERMINAL_SERVICES,
    UTILITY_PING,
    UTILITY_TRACEROUTE,
    UTILITY_PORT_SCANNER,
    UTILITY_WAKE_ON_LAN,
    KUBERNETES_POD,
    DOCKER_CONTAINER;

    val displayName: String
        get() = when (this) {
            RDP -> "Remote Desktop (RDP)"
            SSH -> "SSH Terminal"
            TELNET -> "Telnet"
            SERIAL -> "USB Serial / UART"
            SFTP -> "SFTP File Transfer"
            FTP -> "FTP"
            FTPS -> "FTPS (TLS)"
            SCP -> "SCP Transfer"
            VNC -> "VNC Remote Screen"
            APPLE_SCREEN_SHARING -> "Apple Screen Sharing"
            WEB_PAGE -> "Web Console (HTTP/HTTPS)"
            VMWARE_VSPHERE -> "VMware vSphere"
            PROXMOX_VE -> "Proxmox VE"
            HYPER_V -> "Microsoft Hyper-V"
            AWS_EC2 -> "AWS EC2"
            AZURE_VM -> "Microsoft Azure VM"
            WINDOWS_TERMINAL_SERVICES -> "Windows Terminal Services"
            UTILITY_PING -> "Ping Reachability"
            UTILITY_TRACEROUTE -> "Traceroute"
            UTILITY_PORT_SCANNER -> "TCP Port Scanner"
            UTILITY_WAKE_ON_LAN -> "Wake-on-LAN"
            KUBERNETES_POD -> "Kubernetes Pod"
            DOCKER_CONTAINER -> "Docker Container"
        }

    val shortBadge: String
        get() = when (this) {
            RDP -> "RDP"
            SSH -> "SSH"
            TELNET -> "TEL"
            SERIAL -> "UART"
            SFTP -> "SFTP"
            FTP -> "FTP"
            FTPS -> "FTPS"
            SCP -> "SCP"
            VNC -> "VNC"
            APPLE_SCREEN_SHARING -> "ARD"
            WEB_PAGE -> "WEB"
            VMWARE_VSPHERE -> "ESXi"
            PROXMOX_VE -> "PVE"
            HYPER_V -> "HV"
            AWS_EC2 -> "EC2"
            AZURE_VM -> "AZ"
            WINDOWS_TERMINAL_SERVICES -> "WTS"
            UTILITY_PING -> "PING"
            UTILITY_TRACEROUTE -> "MTR"
            UTILITY_PORT_SCANNER -> "SCAN"
            UTILITY_WAKE_ON_LAN -> "WOL"
            KUBERNETES_POD -> "K8S"
            DOCKER_CONTAINER -> "OCI"
        }

    val defaultPortHint: String
        get() = when (this) {
            RDP -> "TCP 3389"
            SSH -> "TCP 22"
            TELNET -> "TCP 23"
            SERIAL -> "UART / COM"
            SFTP -> "TCP 22"
            FTP -> "TCP 21"
            FTPS -> "TCP 990"
            SCP -> "TCP 22"
            VNC -> "TCP 5900"
            APPLE_SCREEN_SHARING -> "TCP 5900"
            WEB_PAGE -> "HTTPS 443"
            VMWARE_VSPHERE -> "HTTPS 443"
            PROXMOX_VE -> "HTTPS 8006"
            HYPER_V -> "WinRM 5985 / 2179"
            AWS_EC2 -> "SSH / SSM"
            AZURE_VM -> "SSH / Bastion"
            WINDOWS_TERMINAL_SERVICES -> "TCP 3389"
            UTILITY_PING -> "ICMP / Echo"
            UTILITY_TRACEROUTE -> "ICMP / UDP"
            UTILITY_PORT_SCANNER -> "TCP Scan"
            UTILITY_WAKE_ON_LAN -> "UDP 9"
            KUBERNETES_POD -> "kubectl exec"
            DOCKER_CONTAINER -> "docker exec"
        }

    val category: VaultProtocolCategory
        get() = when (this) {
            RDP,
            WINDOWS_TERMINAL_SERVICES,
            VNC,
            APPLE_SCREEN_SHARING -> VaultProtocolCategory.REMOTE_DESKTOP

            SSH,
            TELNET,
            SERIAL,
            KUBERNETES_POD,
            DOCKER_CONTAINER -> VaultProtocolCategory.TERMINAL_CONTAINERS

            SFTP,
            FTP,
            FTPS,
            SCP -> VaultProtocolCategory.FILE_TRANSFER

            WEB_PAGE,
            VMWARE_VSPHERE,
            PROXMOX_VE,
            HYPER_V,
            AWS_EC2,
            AZURE_VM -> VaultProtocolCategory.WEB_CLOUD

            UTILITY_PING,
            UTILITY_TRACEROUTE,
            UTILITY_PORT_SCANNER,
            UTILITY_WAKE_ON_LAN -> VaultProtocolCategory.NETWORK_UTILITIES
        }
}

@Serializable
enum class RdpResizeBehavior {
    DYNAMIC_DISP_DVC,
    SMART_SCALE_VIEWPORT,
    FIXED_SERVER_RESOLUTION
}

@Serializable
enum class RdpAudioRedirectionMode {
    PLAY_ON_THIS_DEVICE,
    PLAY_ON_REMOTE_HOST,
    DO_NOT_PLAY
}

@Serializable
enum class RdpGfxCodecProfile(val nativeCode: Int) {
    ADAPTIVE_PROGRESSIVE(0),
    AVC420_VIDEO(1),
    AVC444_CHROMA(2)
}

@Serializable
@SerialName("protocol_settings")
sealed interface ProtocolSpecificSettings {

    @Serializable
    @SerialName("rdp")
    data class Rdp(
        val port: Int = 3389,
        val connectToConsoleAdmin: Boolean = false,
        val hiDpiEnabled: Boolean = true,
        val desktopScaleFactorPercent: Int = 200,
        val deviceScaleFactorPercent: Int = 180,
        val hiDpiScaleCustomized: Boolean = false,
        val enableGfxPipeline: Boolean = true,
        val gfxCodecProfile: RdpGfxCodecProfile = RdpGfxCodecProfile.ADAPTIVE_PROGRESSIVE,
        val enableH264Avc444: Boolean = false,
        val enableRemoteFx: Boolean = true,
        val resizeBehavior: RdpResizeBehavior = RdpResizeBehavior.DYNAMIC_DISP_DVC,
        val customDesktopWidth: Int = 1920,
        val customDesktopHeight: Int = 1080,
        val multiMonitorSpanning: Boolean = false,
        val selectedMonitorIds: List<Int> = emptyList(),
        val colorDepthBits: Int = 32,
        val redirectClipboard: Boolean = true,
        val audioRedirectionMode: RdpAudioRedirectionMode = RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE,
        val redirectAudioPlayback: Boolean = true,
        val redirectMicrophone: Boolean = false,
        val redirectDrives: Boolean = false,
        val redirectedDriveSafUris: List<String> = emptyList(),
        val redirectPrinters: Boolean = false,
        val redirectPorts: Boolean = false,
        val redirectSmartcardYubiKey: Boolean = false,
        val enableFontSmoothing: Boolean = true,
        val enableDesktopComposition: Boolean = true,
        val enableWallpaper: Boolean = false,
        val enableFullWindowDrag: Boolean = false,
        val enableMenuAnimations: Boolean = false,
        val enableThemes: Boolean = true,
        val compressionEnabled: Boolean = true,
        val remoteAppMode: Boolean = false,
        val remoteAppProgram: String = "",
        val remoteAppArguments: String = "",
        val rdGatewayHostname: String = "",
        val rdGatewayPort: Int = 443,
        val nlaSecurityRequired: Boolean = true,
        val tlsSecurity: Boolean = true,
        val restrictedAdminMode: Boolean = false,
        val ignoreCertificateWarnings: Boolean = false,
        val hyperVVmId: String = "",
        val hyperVEnhancedSession: Boolean = false
    ) : ProtocolSpecificSettings {
        fun effectivePreconnectionBlob(): String {
            var cleaned = hyperVVmId.trim()
            while (cleaned.length >= 2 && (
                    (cleaned.startsWith("{") && cleaned.endsWith("}")) ||
                        (cleaned.startsWith("\"") && cleaned.endsWith("\"")) ||
                        (cleaned.startsWith("'") && cleaned.endsWith("'"))
                    )
            ) {
                cleaned = cleaned.substring(1, cleaned.length - 1).trim()
            }
            if (cleaned.isEmpty()) return ""
            return if (hyperVEnhancedSession && !cleaned.contains("EnhancedMode=", ignoreCase = true)) {
                "$cleaned;EnhancedMode=1"
            } else {
                cleaned
            }
        }

        fun effectivePort(): Int {
            if (effectivePreconnectionBlob().isNotEmpty() && (port <= 0 || port == 3389)) {
                return 2179
            }
            return if (port <= 0) 3389 else port
        }
    }

    @Serializable
    @SerialName("ssh")
    data class Ssh(
        val port: Int = 22,
        val terminalType: String = "xterm-256color",
        val bastionJumpConnectionId: String? = null,
        val enableAgentForwarding: Boolean = false,
        val enableX11Forwarding: Boolean = false,
        val x11DisplayHost: String = "127.0.0.1:0",
        val portForwards: List<SshPortForwardRule> = emptyList(),
        val startupCommand: String = "",
        val keepAliveIntervalSec: Int = 30,
        val scrollbackLines: Int = 10_000,
        val serialBaudRate: Int = 115_200,
        val serialDevicePath: String = "/dev/ttyUSB0"
    ) : ProtocolSpecificSettings

    @Serializable
    @SerialName("file_transfer")
    data class FileTransfer(
        val port: Int = 22,
        val initialLocalDirectory: String = "",
        val initialRemoteDirectory: String = "",
        val preserveTimestamps: Boolean = true,
        val showHiddenDotfiles: Boolean = true,
        val maxConcurrentTransfers: Int = 4,
        val ftpsImplicitTls: Boolean = false,
        val passiveMode: Boolean = true,
        val verifyTls: Boolean = false
    ) : ProtocolSpecificSettings

    @Serializable
    @SerialName("vnc")
    data class Vnc(
        val port: Int = 5900,
        val preferredEncodings: List<String> = listOf("ZRLE", "Tight", "CopyRect", "Hextile", "Raw"),
        val compressionLevel: Int = 6,
        val jpegQualityLevel: Int = 7,
        val viewOnly: Boolean = false,
        val sharedSession: Boolean = true,
        val appleArdDiffieHellmanAuth: Boolean = false
    ) : ProtocolSpecificSettings

    @Serializable
    @SerialName("web_page")
    data class WebPage(
        val url: String,
        val isolatedStorageProfileId: String = UUID.randomUUID().toString(),
        val autoFillEnabled: Boolean = true,
        val usernameCssSelector: String = "input[type='email'], input[name*='user'], input[type='text']",
        val passwordCssSelector: String = "input[type='password']",
        val submitCssSelector: String = "button[type='submit'], input[type='submit']",
        val injectHttpBasicAuth: Boolean = true,
        val pinnedCertSha256: String = "",
        val ignoreSelfSignedInternalCert: Boolean = true
    ) : ProtocolSpecificSettings

    @Serializable
    @SerialName("cloud_virtualization")
    data class CloudVirtualization(
        val apiEndpointUrl: String,
        val regionOrCluster: String = "",
        val subscriptionOrAccountId: String = "",
        val realmOrDomain: String = "pam",
        val autoSyncDynamicInventory: Boolean = true,
        val verifyTls: Boolean = false
    ) : ProtocolSpecificSettings

    @Serializable
    @SerialName("net_utility")
    data class NetUtility(
        val pingCount: Int = 10,
        val pingIntervalMs: Int = 1000,
        val tracerouteMaxHops: Int = 30,
        val portScanRangeStart: Int = 1,
        val portScanRangeEnd: Int = 1024,
        val portScanConcurrency: Int = 128,
        val wolMacAddress: String = "",
        val wolBroadcastAddress: String = "255.255.255.255",
        val wolUdpPort: Int = 9,
        val wolSecureOnHex: String = ""
    ) : ProtocolSpecificSettings

    @Serializable
    @SerialName("serial_uart")
    data class SerialUart(
        val baudRate: Int = 115_200,
        val dataBits: Int = 8,
        val stopBits: Int = 1,
        val parity: String = "NONE",
        val flowControl: String = "NONE",
        val dtrEnabled: Boolean = true,
        val rtsEnabled: Boolean = true,
        val usbVendorIdFilter: Int = 0,
        val devicePathHint: String = "/dev/ttyUSB0",
        val lineEnding: String = "CRLF"
    ) : ProtocolSpecificSettings

    @Serializable
    @SerialName("container_exec")
    data class ContainerExec(
        val clusterOrSocketEndpoint: String = "https://kubernetes.default.svc:6443",
        val contextName: String = "",
        val namespace: String = "default",
        val podOrContainerName: String = "",
        val subContainerName: String = "",
        val shellCommand: String = "/bin/sh",
        val followLogsOnOpen: Boolean = false,
        val logTailLines: Int = 200,
        val portForwards: List<SshPortForwardRule> = emptyList(),
        val verifyTls: Boolean = false
    ) : ProtocolSpecificSettings
}

@Serializable
enum class PortForwardKind {
    LOCAL,
    REMOTE,
    DYNAMIC_SOCKS5
}

@Serializable
data class SshPortForwardRule(
    val id: String = UUID.randomUUID().toString(),
    val kind: PortForwardKind,
    val bindAddress: String = "127.0.0.1",
    val bindPort: Int,
    val targetHost: String = "127.0.0.1",
    val targetPort: Int = 0,
    val enabled: Boolean = true
)

@Serializable
data class VaultFolder(
    val id: String = UUID.randomUUID().toString(),
    val parentFolderId: String? = null,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val inheritable: InheritableProperties = InheritableProperties(),
    val sortOrder: Int = 0,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class SmartFolderFilter(
    val includeTags: List<String> = emptyList(),
    val excludeTags: List<String> = emptyList(),
    val includeProtocols: List<VaultProtocolType> = emptyList(),
    val scopeFolderId: String? = null,
    val osFamilyFilter: String? = null,
    val hostPatternRegex: String? = null,
    val environmentFilter: String? = null
)

@Serializable
data class SmartFolder(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val filter: SmartFolderFilter,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
enum class DynamicScriptInterpreter {
    JSON_HTTP_API,
    SHELL_SH,
    PYTHON3
}

@Serializable
data class DynamicFolder(
    val id: String = UUID.randomUUID().toString(),
    val parentFolderId: String? = null,
    val name: String,
    val interpreter: DynamicScriptInterpreter = DynamicScriptInterpreter.JSON_HTTP_API,
    val scriptOrEndpointPayload: String,
    val refreshIntervalMinutes: Int = 30,
    val inheritable: InheritableProperties = InheritableProperties(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class VaultConnection(
    val id: String = UUID.randomUUID().toString(),
    val parentFolderId: String? = null,
    val name: String,
    val host: String,
    val protocol: VaultProtocolType,
    val osFamily: String = "Linux",
    val tags: List<String> = emptyList(),
    val notes: String = "",
    val favorite: Boolean = false,
    val sortOrder: Int = 0,
    val inheritable: InheritableProperties = InheritableProperties(),
    val settings: ProtocolSpecificSettings,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
enum class CredentialSourceKind {
    VAULT_INTERNAL,
    SSH_KEY_PAIR,
    ONEPASSWORD_CONNECT,
    BITWARDEN_VAULT,
    KEEPASS_KDBX,
    HARDWARE_FIDO2_PASSKEY
}

@Serializable
data class VaultCredential(
    val id: String = UUID.randomUUID().toString(),
    val parentFolderId: String? = null,
    val name: String,
    val sourceKind: CredentialSourceKind = CredentialSourceKind.VAULT_INTERNAL,
    val username: String = "",
    val domain: String = "",
    val secretPassword: String = "",
    val privateKeyPem: String = "",
    val publicKeyOpenSsh: String = "",
    val keyFingerprintSha256: String = "",
    val keyPassphrase: String = "",
    val externalReferenceUri: String = "",
    val fido2CredentialIdBase64: String = "",
    val totpSecretBase32: String = "",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
enum class GatewayTransportKind {
    NEXUSTS_SECURE_GATEWAY,
    SSH_TUNNEL_BASTION,
    SOCKS5_PROXY,
    HTTP_CONNECT_PROXY
}

@Serializable
data class SecureGatewayConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: GatewayTransportKind,
    val host: String,
    val port: Int,
    val credentialId: String? = null,
    val verifyTlsCertificate: Boolean = true,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class CommandTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val commandTemplate: String,
    val broadcastToAllSelectedSessions: Boolean = false,
    val appendNewline: Boolean = true,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class KeySequenceTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Macro sequence supporting tokens such as `{WAIT:300}`, `{ENTER}`, `{TAB}`, `{CTRL+ALT+DEL}`, `$EffectiveUsername$` */
    val macroSequence: String,
    val keystrokeDelayMs: Int = 25,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class ConnectionTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val protocol: VaultProtocolType,
    val colorAccentHex: String? = null,
    val inheritable: InheritableProperties = InheritableProperties(),
    val settings: ProtocolSpecificSettings,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class VaultTodoItem(
    val id: String = UUID.randomUUID().toString(),
    val targetObjectId: String? = null,
    val title: String,
    val completed: Boolean = false,
    val priority: String = "NORMAL",
    val dueIsoDate: String = "",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Root Zero-Knowledge `.nxts` Encrypted Vault Document Model.
 */
@Serializable
data class NxtsVaultDocument(
    val documentId: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val schemaVersion: Int = 2,
    val revision: Long = 1L,
    val lastModifiedByDevice: String = "Googlebook-OS",
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val vectorClock: Map<String, Long> = mapOf("local" to 1L),
    val rootInheritable: InheritableProperties = InheritableProperties(
        credentialMode = InheritanceMode.NONE_DIRECT,
        gatewayMode = InheritanceMode.NONE_DIRECT
    ),
    val folders: List<VaultFolder> = emptyList(),
    val smartFolders: List<SmartFolder> = emptyList(),
    val dynamicFolders: List<DynamicFolder> = emptyList(),
    val connections: List<VaultConnection> = emptyList(),
    val credentials: List<VaultCredential> = emptyList(),
    val gateways: List<SecureGatewayConfig> = emptyList(),
    val commandTasks: List<CommandTask> = emptyList(),
    val keySequenceTasks: List<KeySequenceTask> = emptyList(),
    val templates: List<ConnectionTemplate> = emptyList(),
    val todoItems: List<VaultTodoItem> = emptyList(),
    val recentConnectionIds: List<String> = emptyList(),
    val tombstonedObjectIds: Set<String> = emptySet()
)
