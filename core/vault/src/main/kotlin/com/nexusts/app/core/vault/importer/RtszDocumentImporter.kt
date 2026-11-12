package com.nexusts.app.core.vault.importer

import com.nexusts.app.core.vault.model.CommandTask
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.DynamicFolder
import com.nexusts.app.core.vault.model.DynamicScriptInterpreter
import com.nexusts.app.core.vault.model.GatewayTransportKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.KeySequenceTask
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.SecureGatewayConfig
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Full Royal TS (macOS / Windows) `.rtsz` and `.rtsx` Compatibility Importer.
 * Supports both ZIP-packaged `.rtsz` archives and raw XML `.rtsx` documents,
 * preserving folder hierarchies, property inheritance, credentials, gateways, and tasks.
 */
class RtszDocumentImporter {

    fun importRtszBytes(rawBytes: ByteArray, fallbackDocumentName: String = "Imported Royal TS Vault"): NxtsVaultDocument {
        val xmlBytes = extractXmlFromZipOrRaw(rawBytes)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Harden against XXE attacks when importing external .rtsz files
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }

        val dom = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        dom.documentElement.normalize()

        val folders = mutableListOf<VaultFolder>()
        val connections = mutableListOf<VaultConnection>()
        val credentials = mutableListOf<VaultCredential>()
        val gateways = mutableListOf<SecureGatewayConfig>()
        val commandTasks = mutableListOf<CommandTask>()
        val keyTasks = mutableListOf<KeySequenceTask>()
        val dynamicFolders = mutableListOf<DynamicFolder>()

        var documentId = UUID.randomUUID().toString()
        var docName = fallbackDocumentName

        val allElements = dom.documentElement.childNodes
        for (i in 0 until allElements.length) {
            val node = allElements.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val elem = node as Element
            val tag = elem.tagName

            val id = elem.childText("ID").ifBlank { UUID.randomUUID().toString() }
            val parentIdRaw = elem.childText("ParentID").takeIf { it.isNotBlank() }
            val name = elem.childText("Name").ifBlank { tag }
            val uri = elem.childText("URI")
            val credId = elem.childText("CredentialID").takeIf { it.isNotBlank() }
            val credMode = when (elem.childText("CredentialMode")) {
                "2" -> InheritanceMode.OVERRIDE_EXPLICIT
                "1" -> InheritanceMode.INHERIT_FROM_PARENT
                "3" -> InheritanceMode.PROMPT_AT_CONNECT
                else -> if (credId != null) InheritanceMode.OVERRIDE_EXPLICIT else InheritanceMode.INHERIT_FROM_PARENT
            }
            val gwId = elem.childText("SecureGatewayID").takeIf { it.isNotBlank() }
            val inheritable = InheritableProperties(
                credentialMode = credMode,
                explicitCredentialId = credId,
                gatewayMode = if (gwId != null) InheritanceMode.OVERRIDE_EXPLICIT else InheritanceMode.INHERIT_FROM_PARENT,
                explicitGatewayId = gwId
            )

            when (tag) {
                "RoyalDocument" -> {
                    documentId = id
                    docName = name
                }

                "RoyalFolder" -> {
                    if (id != documentId) {
                        folders += VaultFolder(
                            id = id,
                            parentFolderId = parentIdRaw?.takeIf { it != documentId },
                            name = name,
                            description = elem.childText("Notes"),
                            inheritable = inheritable
                        )
                    }
                }

                "RoyalRDSConnection" -> {
                    val port = elem.childText("RDPPort").toIntOrNull() ?: 3389
                    val audioModeRaw = elem.childText("AudioRedirectionMode").trim()
                    val audioMode = when {
                        audioModeRaw == "1" || audioModeRaw.contains("Remote", true) ->
                            com.nexusts.app.core.vault.model.RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST
                        audioModeRaw == "2" || audioModeRaw.contains("None", true) || audioModeRaw.contains("DoNotPlay", true) ->
                            com.nexusts.app.core.vault.model.RdpAudioRedirectionMode.DO_NOT_PLAY
                        else ->
                            com.nexusts.app.core.vault.model.RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE
                    }
                    val scaleRaw = elem.childText("DesktopScaleFactor").toIntOrNull()?.coerceIn(100, 200) ?: 100
                    val resizeModeRaw = elem.childText("ResizeMode")
                    val resizeBehavior = when {
                        resizeModeRaw.contains("SmartSizing", true) || resizeModeRaw == "1" ->
                            com.nexusts.app.core.vault.model.RdpResizeBehavior.SMART_SCALE_VIEWPORT
                        resizeModeRaw.contains("None", true) || resizeModeRaw.contains("Scrollbars", true) ->
                            com.nexusts.app.core.vault.model.RdpResizeBehavior.FIXED_SERVER_RESOLUTION
                        else ->
                            com.nexusts.app.core.vault.model.RdpResizeBehavior.DYNAMIC_DISP_DVC
                    }
                    connections += VaultConnection(
                        id = id,
                        parentFolderId = parentIdRaw?.takeIf { it != documentId },
                        name = name,
                        host = uri,
                        protocol = VaultProtocolType.RDP,
                        osFamily = "Windows",
                        notes = elem.childText("Notes"),
                        inheritable = inheritable,
                        settings = ProtocolSpecificSettings.Rdp(
                            port = port,
                            connectToConsoleAdmin = elem.childText("ConnectToAdministerOrConsole").equals("true", true) ||
                                elem.childText("ConnectToConsole").equals("true", true),
                            hiDpiEnabled = !elem.childText("AutoAdjustRetinaDisplay").equals("false", true),
                            desktopScaleFactorPercent = scaleRaw,
                            deviceScaleFactorPercent = if (scaleRaw >= 175) 180 else if (scaleRaw >= 125) 140 else 100,
                            resizeBehavior = resizeBehavior,
                            customDesktopWidth = elem.childText("DesktopWidth").toIntOrNull()?.takeIf { it >= 640 } ?: 1920,
                            customDesktopHeight = elem.childText("DesktopHeight").toIntOrNull()?.takeIf { it >= 480 } ?: 1080,
                            colorDepthBits = elem.childText("ColorDepth").toIntOrNull()?.takeIf { it in listOf(16, 24, 32) } ?: 32,
                            redirectClipboard = !elem.childText("RedirectClipboard").equals("false", true),
                            audioRedirectionMode = audioMode,
                            redirectAudioPlayback = audioMode == com.nexusts.app.core.vault.model.RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE,
                            redirectMicrophone = elem.childText("RedirectAudioCapture").equals("true", true) ||
                                elem.childText("RedirectMicrophone").equals("true", true),
                            redirectDrives = elem.childText("RedirectDrives").equals("true", true),
                            redirectPrinters = elem.childText("RedirectPrinters").equals("true", true),
                            redirectPorts = elem.childText("RedirectPorts").equals("true", true),
                            redirectSmartcardYubiKey = elem.childText("RedirectSmartCards").equals("true", true),
                            enableFontSmoothing = !elem.childText("AllowFontSmoothing").equals("false", true),
                            enableDesktopComposition = !elem.childText("AllowDesktopComposition").equals("false", true),
                            enableWallpaper = elem.childText("AllowWallpaper").equals("true", true),
                            nlaSecurityRequired = !elem.childText("NetworkLevelAuthentication").equals("false", true),
                            restrictedAdminMode = elem.childText("RestrictedAdminMode").equals("true", true),
                            ignoreCertificateWarnings = elem.childText("IgnoreCertificateWarning").equals("true", true),
                            rdGatewayHostname = elem.childText("RDGatewayHost"),
                            rdGatewayPort = elem.childText("RDGatewayPort").toIntOrNull() ?: 443
                        )
                    )
                }

                "RoyalSSHConnection" -> {
                    val isTelnet = elem.childText("IsTelnetConnection").equals("true", true)
                    val port = elem.childText("Port").toIntOrNull() ?: if (isTelnet) 23 else 22
                    connections += VaultConnection(
                        id = id,
                        parentFolderId = parentIdRaw?.takeIf { it != documentId },
                        name = name,
                        host = uri,
                        protocol = if (isTelnet) VaultProtocolType.TELNET else VaultProtocolType.SSH,
                        osFamily = "Linux",
                        notes = elem.childText("Notes"),
                        inheritable = inheritable,
                        settings = ProtocolSpecificSettings.Ssh(port = port)
                    )
                }

                "RoyalFileTransferConnection" -> {
                    val connType = elem.childText("ConnectionType").uppercase()
                    val proto = when {
                        connType.contains("SCP") -> VaultProtocolType.SCP
                        connType.contains("FTPS") -> VaultProtocolType.FTPS
                        connType.contains("FTP") -> VaultProtocolType.FTP
                        else -> VaultProtocolType.SFTP
                    }
                    val port = elem.childText("Port").toIntOrNull() ?: if (proto == VaultProtocolType.FTP) 21 else 22
                    connections += VaultConnection(
                        id = id,
                        parentFolderId = parentIdRaw?.takeIf { it != documentId },
                        name = name,
                        host = uri,
                        protocol = proto,
                        inheritable = inheritable,
                        settings = ProtocolSpecificSettings.FileTransfer(
                            port = port,
                            initialRemoteDirectory = elem.childText("InitialRemotePath").ifBlank { "/" }
                        )
                    )
                }

                "RoyalVNCConnection" -> {
                    val isArd = elem.childText("IsAppleRemoteDesktop").equals("true", true)
                    val port = elem.childText("VNCPort").toIntOrNull() ?: 5900
                    connections += VaultConnection(
                        id = id,
                        parentFolderId = parentIdRaw?.takeIf { it != documentId },
                        name = name,
                        host = uri,
                        protocol = if (isArd) VaultProtocolType.APPLE_SCREEN_SHARING else VaultProtocolType.VNC,
                        osFamily = if (isArd) "macOS" else "Linux",
                        inheritable = inheritable,
                        settings = ProtocolSpecificSettings.Vnc(
                            port = port,
                            appleArdDiffieHellmanAuth = isArd
                        )
                    )
                }

                "RoyalWebPageConnection" -> {
                    connections += VaultConnection(
                        id = id,
                        parentFolderId = parentIdRaw?.takeIf { it != documentId },
                        name = name,
                        host = uri,
                        protocol = VaultProtocolType.WEB_PAGE,
                        inheritable = inheritable,
                        settings = ProtocolSpecificSettings.WebPage(
                            url = uri.ifBlank { "https://localhost" },
                            autoFillEnabled = !elem.childText("AutoFill").equals("false", true)
                        )
                    )
                }

                "RoyalCredential" -> {
                    val keyContent = elem.childText("KeyFileContent")
                    credentials += VaultCredential(
                        id = id,
                        parentFolderId = parentIdRaw?.takeIf { it != documentId },
                        name = name,
                        sourceKind = if (keyContent.isNotBlank()) {
                            CredentialSourceKind.SSH_KEY_PAIR
                        } else {
                            CredentialSourceKind.VAULT_INTERNAL
                        },
                        username = elem.childText("UserName"),
                        secretPassword = elem.childText("Password"),
                        privateKeyPem = keyContent
                    )
                }

                "RoyalSecureGateway", "RoyalSSHGateway" -> {
                    gateways += SecureGatewayConfig(
                        id = id,
                        name = name,
                        kind = if (tag == "RoyalSSHGateway") {
                            GatewayTransportKind.SSH_TUNNEL_BASTION
                        } else {
                            GatewayTransportKind.NEXUSTS_SECURE_GATEWAY
                        },
                        host = uri,
                        port = elem.childText("Port").toIntOrNull() ?: 22,
                        credentialId = credId
                    )
                }

                "RoyalCommandTask" -> {
                    commandTasks += CommandTask(
                        id = id,
                        name = name,
                        commandTemplate = elem.childText("Command")
                    )
                }

                "RoyalKeySequenceTask" -> {
                    keyTasks += KeySequenceTask(
                        id = id,
                        name = name,
                        macroSequence = elem.childText("KeySequence")
                    )
                }

                "RoyalDynamicFolder" -> {
                    dynamicFolders += DynamicFolder(
                        id = id,
                        parentFolderId = parentIdRaw?.takeIf { it != documentId },
                        name = name,
                        interpreter = DynamicScriptInterpreter.SHELL_SH,
                        scriptOrEndpointPayload = elem.childText("Script"),
                        inheritable = inheritable
                    )
                }
            }
        }

        return NxtsVaultDocument(
            documentId = documentId,
            name = docName,
            description = "Imported from Royal TS (.rtsz)",
            folders = folders,
            dynamicFolders = dynamicFolders,
            connections = connections,
            credentials = credentials,
            gateways = gateways,
            commandTasks = commandTasks,
            keySequenceTasks = keyTasks
        )
    }

    private fun extractXmlFromZipOrRaw(rawBytes: ByteArray): ByteArray {
        // ZIP local file header magic PK\x03\x04
        if (rawBytes.size >= 4 &&
            rawBytes[0] == 0x50.toByte() &&
            rawBytes[1] == 0x4B.toByte() &&
            rawBytes[2] == 0x03.toByte() &&
            rawBytes[3] == 0x04.toByte()
        ) {
            ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory &&
                        (entry.name.endsWith(".xml", true) || entry.name.endsWith(".rtsx", true) || zis.available() > 0)
                    ) {
                        return zis.readBytes()
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return rawBytes
    }

    private fun Element.childText(tagName: String): String {
        val children = this.childNodes
        for (idx in 0 until children.length) {
            val child = children.item(idx)
            if (child.nodeType == Node.ELEMENT_NODE && (child as Element).tagName == tagName) {
                return child.textContent?.trim().orEmpty()
            }
        }
        return ""
    }
}
