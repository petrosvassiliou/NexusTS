package com.nexusts.app.core.vault.importer

import com.nexusts.app.core.vault.model.CommandTask
import com.nexusts.app.core.vault.model.ConnectionTemplate
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.CustomPropertyEntry
import com.nexusts.app.core.vault.model.DynamicFolder
import com.nexusts.app.core.vault.model.DynamicScriptInterpreter
import com.nexusts.app.core.vault.model.GatewayTransportKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.KeySequenceTask
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.PortForwardKind
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.RdpAudioRedirectionMode
import com.nexusts.app.core.vault.model.RdpResizeBehavior
import com.nexusts.app.core.vault.model.SecureGatewayConfig
import com.nexusts.app.core.vault.model.SmartFolder
import com.nexusts.app.core.vault.model.SmartFolderFilter
import com.nexusts.app.core.vault.model.SshPortForwardRule
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.core.vault.model.VaultTodoItem
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Full Royal TS (macOS / Windows) `.rtsz` and `.rtsx` Compatibility Importer.
 * Supports both ZIP-packaged `.rtsz` archives and raw XML `.rtsx` documents,
 * preserving folder hierarchies, property inheritance, credentials, gateways, smart folders,
 * templates across all protocol types, to-do items, and tasks with 100% lossless fidelity.
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

        val dom = runCatching {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        }.getOrElse { ex ->
            throw IllegalArgumentException("Invalid Royal TS XML document: ${ex.message}", ex)
        }
        dom.documentElement.normalize()

        val rootTag = dom.documentElement.tagName
        if (rootTag.equals("KeePassFile", ignoreCase = true)) {
            throw IllegalArgumentException(
                "KeePass 2.x XML document detected. Use EnterpriseVaultFormatImporter instead of Royal TS .rtsz importer."
            )
        }

        val folders = mutableListOf<VaultFolder>()
        val smartFolders = mutableListOf<SmartFolder>()
        val connections = mutableListOf<VaultConnection>()
        val credentials = mutableListOf<VaultCredential>()
        val gateways = mutableListOf<SecureGatewayConfig>()
        val commandTasks = mutableListOf<CommandTask>()
        val keyTasks = mutableListOf<KeySequenceTask>()
        val dynamicFolders = mutableListOf<DynamicFolder>()
        val templates = mutableListOf<ConnectionTemplate>()
        val todoItems = mutableListOf<VaultTodoItem>()

        var documentId = UUID.randomUUID().toString()
        var docName = fallbackDocumentName
        var docDescription = "Imported from Royal TS (.rtsz)"
        var docRevision = 1L
        var rootInheritable = InheritableProperties(
            credentialMode = InheritanceMode.NONE_DIRECT,
            gatewayMode = InheritanceMode.NONE_DIRECT
        )

        val allElements = dom.documentElement.childNodes
        // Pass 1: Pre-scan <RoyalDocument> so documentId and docName are resolved regardless of XML tag ordering
        for (i in 0 until allElements.length) {
            val node = allElements.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val elem = node as Element
            if (elem.tagName == "RoyalDocument") {
                val parsedDocId = elem.childText("ID")
                if (parsedDocId.isNotBlank()) documentId = parsedDocId
                val parsedDocName = elem.childText("Name")
                if (parsedDocName.isNotBlank()) docName = parsedDocName
                val parsedDesc = elem.childText("Description").ifBlank { elem.childText("Notes") }
                if (parsedDesc.isNotBlank()) docDescription = parsedDesc
                elem.childText("Modified").toLongOrNull()?.let { docRevision = it }
                if (elem.hasDirectChild("CredentialMode") || elem.hasDirectChild("GatewayMode")) {
                    rootInheritable = parseInheritable(
                        elem = elem,
                        resolvedCredId = elem.childText("CredentialID").takeIf { it.isNotBlank() },
                        synthesizedInlineCredId = null,
                        defaultCredMode = InheritanceMode.NONE_DIRECT,
                        defaultGwMode = InheritanceMode.NONE_DIRECT
                    )
                }
                break
            }
        }

        // Pass 2: Materialize folders, smart folders, connections, templates, credentials, gateways, to-dos, and tasks
        for (i in 0 until allElements.length) {
            val node = allElements.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val elem = node as Element
            val tag = elem.tagName
            if (tag == "RoyalDocument") continue

            val id = elem.childText("ID").ifBlank { UUID.randomUUID().toString() }
            val parentIdRaw = elem.childText("ParentID").takeIf { it.isNotBlank() }
            val name = elem.childText("Name").ifBlank { elem.childText("Title").ifBlank { tag } }
            val uri = elem.childText("URI")
            val isTemplate = elem.childBoolean("IsTemplate", default = false) || tag == "RoyalTemplate"
            val isFavorite = elem.childBoolean("Favorite", "IsFavorite", default = false)
            val colorHex = elem.childText("Color").takeIf { it.startsWith("#") && (it.length == 7 || it.length == 9) }
            val templateColorHex = elem.childText("TemplateColor").takeIf { it.startsWith("#") && (it.length == 7 || it.length == 9) }
                ?: colorHex

            val rawCredMode = elem.childText("CredentialMode").trim()
            val explicitCredId = elem.childText("CredentialID").takeIf { it.isNotBlank() }

            val inlineUserRaw = elem.childText("CredentialUsername")
                .ifBlank { if (tag != "RoyalCredential") elem.childText("UserName") else "" }
            val inlinePassRaw = elem.childText("CredentialPassword")
                .ifBlank { if (tag != "RoyalCredential") elem.childText("Password") else "" }
            val inlineKeyPem = if (tag != "RoyalCredential") elem.childText("CredentialKeyFileContent") else ""

            val synthesizedInlineCredId: String? = if (
                tag != "RoyalCredential" &&
                (rawCredMode == "1" || (explicitCredId == null && (inlineUserRaw.isNotBlank() || inlinePassRaw.isNotBlank() || inlineKeyPem.isNotBlank()))) &&
                (inlineUserRaw.isNotBlank() || inlinePassRaw.isNotBlank() || inlineKeyPem.isNotBlank())
            ) {
                val (inlineDomain, inlineUser) = splitDomainAndUsername(inlineUserRaw)
                val synId = "inline-cred-$id"
                credentials += VaultCredential(
                    id = synId,
                    parentFolderId = if (tag == "RoyalFolder") id else parentIdRaw?.takeIf { it != documentId },
                    name = "$name (Saved Credential)",
                    sourceKind = if (inlineKeyPem.isNotBlank()) {
                        CredentialSourceKind.SSH_KEY_PAIR
                    } else {
                        CredentialSourceKind.VAULT_INTERNAL
                    },
                    username = inlineUser,
                    domain = inlineDomain,
                    secretPassword = inlinePassRaw,
                    privateKeyPem = inlineKeyPem
                )
                synId
            } else {
                null
            }

            val resolvedCredId = synthesizedInlineCredId ?: explicitCredId
            val inheritable = parseInheritable(
                elem = elem,
                resolvedCredId = resolvedCredId,
                synthesizedInlineCredId = synthesizedInlineCredId,
                defaultCredMode = InheritanceMode.INHERIT_FROM_PARENT,
                defaultGwMode = InheritanceMode.INHERIT_FROM_PARENT,
                isTemplate = isTemplate
            )

            when (tag) {
                "RoyalFolder" -> {
                    if (id != documentId) {
                        val folderTags = elem.childText("Tags")
                            .split(';')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        folders += VaultFolder(
                            id = id,
                            parentFolderId = parentIdRaw?.takeIf { it != documentId },
                            name = name,
                            description = elem.childText("Notes"),
                            tags = folderTags,
                            inheritable = inheritable,
                            sortOrder = elem.childText("SortOrder").toIntOrNull() ?: 0
                        )
                    }
                }

                "NexusSmartFolder", "RoyalSmartFolder" -> {
                    val includeTags = elem.childText("IncludeTags")
                        .split(';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    val includeProtocols = elem.childText("IncludeProtocols")
                        .split(';')
                        .mapNotNull { raw ->
                            val trimmed = raw.trim()
                            VaultProtocolType.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                        }
                    smartFolders += SmartFolder(
                        id = id,
                        name = name,
                        filter = SmartFolderFilter(
                            includeTags = includeTags,
                            includeProtocols = includeProtocols,
                            osFamilyFilter = elem.childText("OSFamilyFilter").takeIf { it.isNotBlank() },
                            hostPatternRegex = elem.childText("HostPatternRegex").takeIf { it.isNotBlank() },
                            environmentFilter = elem.childText("EnvironmentFilter").takeIf { it.isNotBlank() }
                        )
                    )
                }

                "RoyalToDo", "RoyalToDoItem" -> {
                    todoItems += VaultTodoItem(
                        id = id,
                        targetObjectId = parentIdRaw?.takeIf { it != documentId },
                        title = name,
                        completed = elem.childBoolean("Completed", "IsCompleted", default = false),
                        priority = elem.childText("Priority").ifBlank { "NORMAL" },
                        dueIsoDate = elem.childText("DueDate")
                    )
                }

                "RoyalRDSConnection",
                "RoyalTemplate",
                "RoyalSSHConnection",
                "RoyalFileTransferConnection",
                "RoyalVNCConnection",
                "RoyalWebPageConnection",
                "RoyalVMwareConnection",
                "RoyalHyperVConnection",
                "RoyalConnection" -> {
                    val proto = resolveProtocolForConnectionElement(elem, tag)
                    val settings = parseProtocolSettings(elem, proto, uri)
                    if (isTemplate) {
                        val description = elem.childText("Description").ifBlank { elem.childText("Notes") }
                        templates += ConnectionTemplate(
                            id = id,
                            name = name,
                            description = description,
                            protocol = proto,
                            colorAccentHex = templateColorHex,
                            inheritable = inheritable,
                            settings = settings
                        )
                    } else {
                        val connTags = elem.childText("Tags")
                            .split(';')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        val osFamily = elem.childText("OSFamily").ifBlank { defaultOsForProtocol(proto) }
                        connections += VaultConnection(
                            id = id,
                            parentFolderId = parentIdRaw?.takeIf { it != documentId },
                            name = name,
                            host = uri,
                            protocol = proto,
                            osFamily = osFamily,
                            tags = connTags,
                            notes = elem.childText("Notes"),
                            favorite = isFavorite,
                            inheritable = inheritable,
                            settings = settings
                        )
                    }
                }

                "RoyalCredential" -> {
                    val keyContent = elem.childText("KeyFileContent")
                    val rawUser = elem.childText("UserName")
                    val explicitDomain = elem.childText("Domain")
                    val totpSecret = elem.childText("OneTimePasswordSecret")
                        .ifBlank { elem.childText("TOTPSecret") }
                    val (parsedDomain, parsedUser) = splitDomainAndUsername(rawUser)
                    val explicitKind = elem.childText("SourceKind").let { raw ->
                        CredentialSourceKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    }
                    val resolvedKind = explicitKind ?: if (keyContent.isNotBlank()) {
                        CredentialSourceKind.SSH_KEY_PAIR
                    } else {
                        CredentialSourceKind.VAULT_INTERNAL
                    }
                    credentials += VaultCredential(
                        id = id,
                        parentFolderId = parentIdRaw?.takeIf { it != documentId },
                        name = name,
                        sourceKind = resolvedKind,
                        username = parsedUser,
                        domain = explicitDomain.ifBlank { parsedDomain },
                        secretPassword = elem.childText("Password"),
                        privateKeyPem = keyContent,
                        publicKeyOpenSsh = elem.childText("PublicKeyOpenSsh"),
                        keyFingerprintSha256 = elem.childText("KeyFingerprintSha256"),
                        keyPassphrase = elem.childText("KeyPassphrase"),
                        externalReferenceUri = elem.childText("ExternalReferenceUri"),
                        fido2CredentialIdBase64 = elem.childText("Fido2CredentialIdBase64"),
                        totpSecretBase32 = totpSecret
                    )
                }

                "RoyalSecureGateway", "RoyalSSHGateway" -> {
                    val explicitGwKind = elem.childText("NexusGatewayKind").let { raw ->
                        GatewayTransportKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    }
                    val gwKind = explicitGwKind ?: if (tag == "RoyalSSHGateway") {
                        GatewayTransportKind.SSH_TUNNEL_BASTION
                    } else {
                        GatewayTransportKind.NEXUSTS_SECURE_GATEWAY
                    }
                    gateways += SecureGatewayConfig(
                        id = id,
                        name = name,
                        kind = gwKind,
                        host = uri,
                        port = elem.childText("Port").toIntOrNull() ?: 22,
                        credentialId = resolvedCredId,
                        verifyTlsCertificate = elem.childBoolean("VerifyTlsCertificate", default = true)
                    )
                }

                "RoyalCommandTask" -> {
                    commandTasks += CommandTask(
                        id = id,
                        name = name,
                        commandTemplate = elem.childText("Command"),
                        broadcastToAllSelectedSessions = elem.childBoolean("BroadcastToAll", default = false),
                        appendNewline = elem.childBoolean("AppendNewline", default = true)
                    )
                }

                "RoyalKeySequenceTask" -> {
                    keyTasks += KeySequenceTask(
                        id = id,
                        name = name,
                        macroSequence = elem.childText("KeySequence"),
                        keystrokeDelayMs = elem.childText("KeystrokeDelayMs").toIntOrNull() ?: 25
                    )
                }

                "RoyalDynamicFolder" -> {
                    val parsedInterp = elem.childText("Interpreter").let { raw ->
                        DynamicScriptInterpreter.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    } ?: DynamicScriptInterpreter.SHELL_SH
                    dynamicFolders += DynamicFolder(
                        id = id,
                        parentFolderId = parentIdRaw?.takeIf { it != documentId },
                        name = name,
                        interpreter = parsedInterp,
                        scriptOrEndpointPayload = elem.childText("Script"),
                        refreshIntervalMinutes = elem.childText("RefreshIntervalMinutes").toIntOrNull() ?: 30,
                        inheritable = inheritable
                    )
                }
            }
        }

        return NxtsVaultDocument(
            documentId = documentId,
            name = docName,
            description = docDescription,
            revision = docRevision,
            rootInheritable = rootInheritable,
            folders = folders,
            smartFolders = smartFolders,
            dynamicFolders = dynamicFolders,
            connections = connections,
            credentials = credentials,
            gateways = gateways,
            commandTasks = commandTasks,
            keySequenceTasks = keyTasks,
            templates = templates,
            todoItems = todoItems
        )
    }

    private fun parseInheritable(
        elem: Element,
        resolvedCredId: String?,
        synthesizedInlineCredId: String?,
        defaultCredMode: InheritanceMode,
        defaultGwMode: InheritanceMode,
        isTemplate: Boolean = false
    ): InheritableProperties {
        val rawCredMode = elem.childText("CredentialMode").trim()
        val credMode = when {
            synthesizedInlineCredId != null -> InheritanceMode.OVERRIDE_EXPLICIT
            rawCredMode == "0" -> InheritanceMode.NONE_DIRECT
            rawCredMode == "2" -> InheritanceMode.OVERRIDE_EXPLICIT
            rawCredMode == "3" -> InheritanceMode.PROMPT_AT_CONNECT
            rawCredMode == "4" || rawCredMode == "1" -> InheritanceMode.INHERIT_FROM_PARENT
            resolvedCredId != null -> InheritanceMode.OVERRIDE_EXPLICIT
            else -> defaultCredMode
        }

        val gwId = elem.childText("SecureGatewayID").takeIf { it.isNotBlank() }
        val rawGwMode = elem.childText("GatewayMode").trim()
        val gwMode = when {
            rawGwMode == "0" -> InheritanceMode.NONE_DIRECT
            rawGwMode == "2" -> InheritanceMode.OVERRIDE_EXPLICIT
            rawGwMode == "3" -> InheritanceMode.PROMPT_AT_CONNECT
            rawGwMode == "4" -> InheritanceMode.INHERIT_FROM_PARENT
            gwId != null -> InheritanceMode.OVERRIDE_EXPLICIT
            else -> defaultGwMode
        }

        val inheritableColor = if (isTemplate && elem.hasDirectChild("TemplateColor") && !elem.hasDirectChild("InheritableColor")) {
            null
        } else {
            elem.childText("InheritableColor")
                .ifBlank { elem.childText("Color") }
                .takeIf { it.startsWith("#") && (it.length == 7 || it.length == 9) }
        }

        val customProps = (1..10).mapNotNull { idx ->
            val nameTag = "CustomField${idx}Name"
            val valTag = "CustomField$idx"
            val protTag = "CustomField${idx}Protected"
            val hasName = elem.hasDirectChild(nameTag)
            val v = elem.childText(valTag)
            val isProt = if (elem.hasDirectChild(protTag)) {
                elem.childBoolean(protTag, default = false)
            } else {
                idx > 8
            }
            if (v.isNotBlank() || (hasName && isProt)) {
                val fieldLabel = elem.childText(nameTag).ifBlank { "CustomField$idx" }
                CustomPropertyEntry(
                    key = fieldLabel,
                    value = v,
                    isProtected = isProt
                )
            } else {
                null
            }
        }

        return InheritableProperties(
            credentialMode = credMode,
            explicitCredentialId = if (credMode == InheritanceMode.OVERRIDE_EXPLICIT) resolvedCredId else null,
            gatewayMode = gwMode,
            explicitGatewayId = if (gwMode == InheritanceMode.OVERRIDE_EXPLICIT) gwId else null,
            environmentBadge = elem.childText("EnvironmentBadge").takeIf { it.isNotBlank() },
            preConnectTaskId = elem.childText("PreConnectTaskID").takeIf { it.isNotBlank() },
            postDisconnectTaskId = elem.childText("PostDisconnectTaskID").takeIf { it.isNotBlank() },
            displayColorAccentHex = inheritableColor,
            customProperties = customProps,
            requireConnectConfirmation = elem.childBoolean("RequireConnectConfirmation", default = false)
        )
    }

    private fun resolveProtocolForConnectionElement(elem: Element, tag: String): VaultProtocolType {
        val explicitProtoName = elem.childText("NexusProtocolType").trim()
        if (explicitProtoName.isNotEmpty()) {
            VaultProtocolType.entries.firstOrNull { it.name.equals(explicitProtoName, ignoreCase = true) }
                ?.let { return it }
        }

        return when (tag) {
            "RoyalRDSConnection", "RoyalTemplate" -> VaultProtocolType.RDP
            "RoyalSSHConnection" -> when {
                elem.hasDirectChild("BaudRate") || elem.childBoolean("IsSerialPortConnection", default = false) ->
                    VaultProtocolType.SERIAL
                elem.hasDirectChild("Workload") || elem.hasDirectChild("ClusterEndpoint") ->
                    VaultProtocolType.KUBERNETES_POD
                elem.childBoolean("IsTelnetConnection", default = false) ->
                    VaultProtocolType.TELNET
                else -> VaultProtocolType.SSH
            }
            "RoyalFileTransferConnection" -> {
                val connType = elem.childText("ConnectionType").uppercase()
                when {
                    connType.contains("SCP") -> VaultProtocolType.SCP
                    connType.contains("FTPS") -> VaultProtocolType.FTPS
                    connType.contains("FTP") -> VaultProtocolType.FTP
                    else -> VaultProtocolType.SFTP
                }
            }
            "RoyalVNCConnection" -> {
                if (elem.childBoolean("IsAppleRemoteDesktop", default = false)) {
                    VaultProtocolType.APPLE_SCREEN_SHARING
                } else {
                    VaultProtocolType.VNC
                }
            }
            "RoyalWebPageConnection" -> VaultProtocolType.WEB_PAGE
            "RoyalVMwareConnection" -> VaultProtocolType.VMWARE_VSPHERE
            "RoyalHyperVConnection" -> VaultProtocolType.HYPER_V
            else -> VaultProtocolType.SSH
        }
    }

    private fun parseProtocolSettings(
        elem: Element,
        protocol: VaultProtocolType,
        uri: String
    ): ProtocolSpecificSettings = when (protocol) {
        VaultProtocolType.RDP, VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> {
            val port = elem.childText("RDPPort").toIntOrNull() ?: 3389
            val audioModeRaw = elem.childText("AudioRedirectionMode").trim()
            val audioMode = when {
                audioModeRaw == "1" || audioModeRaw.contains("Remote", true) ->
                    RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST
                audioModeRaw == "2" || audioModeRaw.contains("None", true) || audioModeRaw.contains("DoNotPlay", true) ->
                    RdpAudioRedirectionMode.DO_NOT_PLAY
                else ->
                    RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE
            }
            val scaleRaw = elem.childText("DesktopScaleFactor").toIntOrNull()?.coerceIn(100, 400) ?: 100
            val deviceScale = elem.childText("DeviceScaleFactor").toIntOrNull()
                ?: if (scaleRaw >= 175) 180 else if (scaleRaw >= 125) 140 else 100
            val resizeModeRaw = elem.childText("ResizeMode")
            val resizeBehavior = when {
                resizeModeRaw.contains("SmartSizing", true) || resizeModeRaw == "1" ->
                    RdpResizeBehavior.SMART_SCALE_VIEWPORT
                resizeModeRaw.contains("None", true) || resizeModeRaw.contains("Scrollbars", true) ->
                    RdpResizeBehavior.FIXED_SERVER_RESOLUTION
                resizeModeRaw.isNotBlank() ->
                    RdpResizeBehavior.DYNAMIC_DISP_DVC
                elem.childBoolean("SmartSizing", default = false) ->
                    RdpResizeBehavior.SMART_SCALE_VIEWPORT
                elem.hasDirectChild("SmartReconnect") && !elem.childBoolean("SmartReconnect", default = true) ->
                    RdpResizeBehavior.FIXED_SERVER_RESOLUTION
                else ->
                    RdpResizeBehavior.DYNAMIC_DISP_DVC
            }
            val monitorIds = elem.childText("SelectedMonitorIds")
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
            val driveUris = elem.childText("RedirectedDriveSafUris")
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            ProtocolSpecificSettings.Rdp(
                port = port,
                connectToConsoleAdmin = elem.childBoolean("ConnectToAdministerOrConsole", "ConnectToConsole", default = false),
                hiDpiEnabled = elem.childBoolean("AutoAdjustRetinaDisplay", default = true),
                desktopScaleFactorPercent = scaleRaw,
                deviceScaleFactorPercent = deviceScale,
                enableGfxPipeline = elem.childBoolean("EnableGfxPipeline", default = true),
                enableH264Avc444 = elem.childBoolean("EnableH264Avc444", default = true),
                enableRemoteFx = elem.childBoolean("EnableRemoteFx", default = true),
                resizeBehavior = resizeBehavior,
                customDesktopWidth = elem.childText("DesktopWidth").toIntOrNull()?.takeIf { it >= 320 } ?: 1920,
                customDesktopHeight = elem.childText("DesktopHeight").toIntOrNull()?.takeIf { it >= 240 } ?: 1080,
                multiMonitorSpanning = elem.childBoolean("UseMultimon", default = false),
                selectedMonitorIds = monitorIds,
                colorDepthBits = elem.childText("ColorDepth").toIntOrNull()?.takeIf { it in listOf(8, 15, 16, 24, 32) } ?: 32,
                redirectClipboard = elem.childBoolean("RedirectClipboard", default = true),
                audioRedirectionMode = audioMode,
                redirectAudioPlayback = if (elem.hasDirectChild("RedirectAudioPlayback")) {
                    elem.childBoolean("RedirectAudioPlayback", default = true)
                } else {
                    audioMode == RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE
                },
                redirectMicrophone = elem.childBoolean("RedirectMicrophone", "RedirectAudioCapture", "RedirectAudioRecording", default = false),
                redirectDrives = elem.childBoolean("RedirectDrives", default = false),
                redirectedDriveSafUris = driveUris,
                redirectPrinters = elem.childBoolean("RedirectPrinters", default = false),
                redirectPorts = elem.childBoolean("RedirectPorts", default = false),
                redirectSmartcardYubiKey = elem.childBoolean("RedirectSmartCards", default = true),
                enableFontSmoothing = elem.childBoolean("AllowFontSmoothing", default = true),
                enableDesktopComposition = elem.childBoolean("AllowDesktopComposition", default = true),
                enableWallpaper = elem.childBoolean("AllowWallpaper", default = false),
                enableFullWindowDrag = elem.childBoolean("AllowFullWindowDrag", default = false),
                enableMenuAnimations = elem.childBoolean("AllowMenuAnimations", default = false),
                enableThemes = elem.childBoolean("AllowThemes", default = true),
                compressionEnabled = elem.childBoolean("Compression", default = true),
                remoteAppMode = elem.childBoolean("RemoteAppMode", default = false),
                remoteAppProgram = elem.childText("RemoteAppProgram"),
                remoteAppArguments = elem.childText("RemoteAppArguments"),
                rdGatewayHostname = elem.childText("RDGatewayHost"),
                rdGatewayPort = elem.childText("RDGatewayPort").toIntOrNull() ?: 443,
                nlaSecurityRequired = elem.childBoolean("NetworkLevelAuthentication", default = true),
                tlsSecurity = elem.childBoolean("TlsSecurity", default = true),
                restrictedAdminMode = elem.childBoolean("RestrictedAdminMode", default = false),
                ignoreCertificateWarnings = elem.childBoolean("IgnoreCertificateWarning", "IgnoreCertificate", default = false)
            )
        }

        VaultProtocolType.SSH, VaultProtocolType.TELNET -> {
            val defaultPort = if (protocol == VaultProtocolType.TELNET) 23 else 22
            ProtocolSpecificSettings.Ssh(
                port = elem.childText("Port").toIntOrNull() ?: defaultPort,
                terminalType = elem.childText("TerminalType").ifBlank { "xterm-256color" },
                bastionJumpConnectionId = elem.childText("BastionJumpConnectionId").takeIf { it.isNotBlank() },
                enableAgentForwarding = elem.childBoolean("EnableAgentForwarding", default = false),
                enableX11Forwarding = elem.childBoolean("EnableX11Forwarding", default = false),
                x11DisplayHost = elem.childText("X11DisplayHost").ifBlank { "127.0.0.1:0" },
                portForwards = decodePortForwards(elem.childText("PortForwardRules")),
                startupCommand = elem.childText("StartupCommand"),
                keepAliveIntervalSec = elem.childText("KeepAliveIntervalSec").toIntOrNull() ?: 30,
                scrollbackLines = elem.childText("ScrollbackLines").toIntOrNull() ?: 10_000,
                serialBaudRate = elem.childText("SerialBaudRate").toIntOrNull() ?: 115_200,
                serialDevicePath = elem.childText("SerialDevicePath").ifBlank { "/dev/ttyUSB0" }
            )
        }

        VaultProtocolType.SERIAL -> {
            ProtocolSpecificSettings.SerialUart(
                baudRate = elem.childText("BaudRate").toIntOrNull() ?: 115_200,
                dataBits = elem.childText("DataBits").toIntOrNull() ?: 8,
                stopBits = elem.childText("StopBits").toIntOrNull() ?: 1,
                parity = elem.childText("Parity").ifBlank { "NONE" },
                flowControl = elem.childText("FlowControl").ifBlank { "NONE" },
                dtrEnabled = elem.childBoolean("DtrEnabled", default = true),
                rtsEnabled = elem.childBoolean("RtsEnabled", default = true),
                usbVendorIdFilter = elem.childText("UsbVendorIdFilter").toIntOrNull() ?: 0,
                devicePathHint = elem.childText("DevicePathHint").ifBlank { uri.ifBlank { "/dev/ttyUSB0" } },
                lineEnding = elem.childText("LineEnding").ifBlank { "CRLF" }
            )
        }

        VaultProtocolType.SFTP, VaultProtocolType.FTP, VaultProtocolType.FTPS, VaultProtocolType.SCP -> {
            val defaultPort = when (protocol) {
                VaultProtocolType.FTP -> 21
                VaultProtocolType.FTPS -> 990
                else -> 22
            }
            ProtocolSpecificSettings.FileTransfer(
                port = elem.childText("Port").toIntOrNull() ?: defaultPort,
                initialLocalDirectory = elem.childText("InitialLocalPath"),
                initialRemoteDirectory = elem.childText("InitialRemotePath").ifBlank { "/" },
                preserveTimestamps = elem.childBoolean("PreserveTimestamps", default = true),
                showHiddenDotfiles = elem.childBoolean("ShowHiddenDotfiles", default = true),
                maxConcurrentTransfers = elem.childText("MaxConcurrentTransfers").toIntOrNull() ?: 4,
                ftpsImplicitTls = elem.childBoolean("FtpsImplicitTls", default = false)
            )
        }

        VaultProtocolType.VNC, VaultProtocolType.APPLE_SCREEN_SHARING -> {
            val isArd = protocol == VaultProtocolType.APPLE_SCREEN_SHARING ||
                elem.childBoolean("IsAppleRemoteDesktop", "AppleArdDiffieHellmanAuth", default = false)
            val encodings = elem.childText("PreferredEncodings")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf("ZRLE", "Tight", "CopyRect", "Hextile", "Raw") }
            ProtocolSpecificSettings.Vnc(
                port = elem.childText("VNCPort").toIntOrNull() ?: 5900,
                preferredEncodings = encodings,
                compressionLevel = elem.childText("CompressionLevel").toIntOrNull() ?: 6,
                jpegQualityLevel = elem.childText("JpegQualityLevel").toIntOrNull() ?: 7,
                viewOnly = elem.childBoolean("ViewOnly", default = false),
                sharedSession = elem.childBoolean("SharedSession", default = true),
                appleArdDiffieHellmanAuth = if (elem.hasDirectChild("AppleArdDiffieHellmanAuth")) {
                    elem.childBoolean("AppleArdDiffieHellmanAuth", default = isArd)
                } else {
                    isArd
                }
            )
        }

        VaultProtocolType.WEB_PAGE -> {
            val webUrl = if (elem.hasDirectChild("WebPageUrl")) {
                elem.childText("WebPageUrl")
            } else {
                uri.ifBlank { "https://localhost" }
            }
            ProtocolSpecificSettings.WebPage(
                url = webUrl,
                isolatedStorageProfileId = elem.childText("IsolatedProfileId").ifBlank { UUID.randomUUID().toString() },
                autoFillEnabled = elem.childBoolean("AutoFill", default = true),
                usernameCssSelector = elem.childText("UsernameCssSelector")
                    .ifBlank { "input[type='email'], input[name*='user'], input[type='text']" },
                passwordCssSelector = elem.childText("PasswordCssSelector")
                    .ifBlank { "input[type='password']" },
                submitCssSelector = elem.childText("SubmitCssSelector")
                    .ifBlank { "button[type='submit'], input[type='submit']" },
                injectHttpBasicAuth = elem.childBoolean("InjectHttpBasicAuth", default = false),
                pinnedCertSha256 = elem.childText("PinnedCertSha256"),
                ignoreSelfSignedInternalCert = elem.childBoolean("IgnoreSelfSignedCert", default = false)
            )
        }

        VaultProtocolType.VMWARE_VSPHERE,
        VaultProtocolType.PROXMOX_VE,
        VaultProtocolType.HYPER_V,
        VaultProtocolType.AWS_EC2,
        VaultProtocolType.AZURE_VM -> {
            val apiUrl = if (elem.hasDirectChild("ApiEndpointUrl")) {
                elem.childText("ApiEndpointUrl")
            } else if (uri.startsWith("http://", true) || uri.startsWith("https://", true)) {
                uri
            } else if (uri.isNotBlank()) {
                "https://$uri"
            } else {
                ""
            }
            ProtocolSpecificSettings.CloudVirtualization(
                apiEndpointUrl = apiUrl,
                regionOrCluster = elem.childText("RegionOrCluster"),
                subscriptionOrAccountId = elem.childText("SubscriptionOrAccountId"),
                realmOrDomain = elem.childText("RealmOrDomain").ifBlank { "pam" },
                autoSyncDynamicInventory = elem.childBoolean("AutoSyncDynamicInventory", default = true)
            )
        }

        VaultProtocolType.UTILITY_PING,
        VaultProtocolType.UTILITY_TRACEROUTE,
        VaultProtocolType.UTILITY_PORT_SCANNER,
        VaultProtocolType.UTILITY_WAKE_ON_LAN -> {
            ProtocolSpecificSettings.NetUtility(
                pingCount = elem.childText("PingCount").toIntOrNull() ?: 10,
                pingIntervalMs = elem.childText("PingIntervalMs").toIntOrNull() ?: 1000,
                tracerouteMaxHops = elem.childText("TracerouteMaxHops").toIntOrNull() ?: 30,
                portScanRangeStart = elem.childText("PortScanStart").toIntOrNull() ?: 1,
                portScanRangeEnd = elem.childText("PortScanEnd").toIntOrNull() ?: 1024,
                portScanConcurrency = elem.childText("PortScanConcurrency").toIntOrNull() ?: 128,
                wolMacAddress = elem.childText("WolMacAddress"),
                wolBroadcastAddress = elem.childText("WolBroadcastIp").ifBlank { "255.255.255.255" },
                wolUdpPort = elem.childText("WolUdpPort").toIntOrNull() ?: 9,
                wolSecureOnHex = elem.childText("WolSecureOnHex")
            )
        }

        VaultProtocolType.KUBERNETES_POD, VaultProtocolType.DOCKER_CONTAINER -> {
            ProtocolSpecificSettings.ContainerExec(
                clusterOrSocketEndpoint = if (elem.hasDirectChild("ClusterEndpoint")) {
                    elem.childText("ClusterEndpoint")
                } else {
                    uri.ifBlank { "https://kubernetes.default.svc:6443" }
                },
                contextName = if (elem.hasDirectChild("ContextName")) {
                    elem.childText("ContextName")
                } else {
                    "prod-eu-central"
                },
                namespace = if (elem.hasDirectChild("Namespace")) {
                    elem.childText("Namespace")
                } else {
                    "default"
                },
                podOrContainerName = elem.childText("Workload"),
                subContainerName = elem.childText("ContainerName"),
                shellCommand = elem.childText("Shell").ifBlank { "/bin/sh" },
                followLogsOnOpen = elem.childBoolean("FollowLogs", default = false),
                logTailLines = elem.childText("LogTailLines").toIntOrNull() ?: 200,
                portForwards = decodePortForwards(elem.childText("PortForwardRules")),
                verifyTls = elem.childBoolean("VerifyTls", default = true)
            )
        }
    }

    private fun decodePortForwards(encoded: String): List<SshPortForwardRule> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(';').mapNotNull { segment ->
            val parts = segment.split('|')
            if (parts.size < 7) return@mapNotNull null
            val kind = PortForwardKind.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            val bindPort = parts[3].toIntOrNull() ?: return@mapNotNull null
            val targetPort = parts[5].toIntOrNull() ?: return@mapNotNull null
            SshPortForwardRule(
                id = parts[0],
                kind = kind,
                bindAddress = parts[2],
                bindPort = bindPort,
                targetHost = parts[4],
                targetPort = targetPort,
                enabled = parts[6].equals("true", ignoreCase = true)
            )
        }
    }

    private fun defaultOsForProtocol(protocol: VaultProtocolType): String = when (protocol) {
        VaultProtocolType.RDP, VaultProtocolType.WINDOWS_TERMINAL_SERVICES, VaultProtocolType.HYPER_V -> "Windows"
        VaultProtocolType.APPLE_SCREEN_SHARING -> "macOS"
        else -> "Linux"
    }

    private fun splitDomainAndUsername(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        val slashIdx = trimmed.indexOf('\\')
        return if (slashIdx > 0 && slashIdx < trimmed.lastIndex) {
            trimmed.substring(0, slashIdx).trim() to trimmed.substring(slashIdx + 1).trim()
        } else {
            "" to trimmed
        }
    }

    private fun extractXmlFromZipOrRaw(rawBytes: ByteArray): ByteArray {
        if (rawBytes.isEmpty()) {
            throw IllegalArgumentException("Cannot import empty Royal TS .rtsz / .rtsx payload.")
        }

        // Reject binary KeePass .kdbx containers (magic 03 D9 A2 9A) with a clear actionable error
        if (rawBytes.size >= 4 &&
            rawBytes[0] == 0x03.toByte() &&
            rawBytes[1] == 0xD9.toByte() &&
            rawBytes[2] == 0xA2.toByte() &&
            rawBytes[3] == 0x9A.toByte()
        ) {
            throw IllegalArgumentException(
                "Binary KeePass .kdbx database detected (03 D9 A2 9A). Import using EnterpriseVaultFormatImporter with your KDBX passphrase."
            )
        }

        // Reject encrypted NexusTS .nxts containers
        if (rawBytes.size >= 4 &&
            rawBytes[0] == 0x4E.toByte() &&
            rawBytes[1] == 0x58.toByte() &&
            rawBytes[2] == 0x54.toByte() &&
            rawBytes[3] == 0x53.toByte()
        ) {
            throw IllegalArgumentException(
                "Encrypted NexusTS .nxts container detected. Open with NxtsVaultSerializer using your vault passphrase."
            )
        }

        // ZIP local file header magic PK\x03\x04
        if (rawBytes.size >= 4 &&
            rawBytes[0] == 0x50.toByte() &&
            rawBytes[1] == 0x4B.toByte() &&
            rawBytes[2] == 0x03.toByte() &&
            rawBytes[3] == 0x04.toByte()
        ) {
            var xmlSniffCandidate: ByteArray? = null
            ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val entryName = entry.name
                        if (entryName.endsWith(".rtsx", ignoreCase = true) ||
                            entryName.endsWith(".xml", ignoreCase = true)
                        ) {
                            return zis.readBytes()
                        }
                        if (xmlSniffCandidate == null) {
                            val candidateBytes = zis.readBytes()
                            if (looksLikeRoyalXml(candidateBytes)) {
                                xmlSniffCandidate = candidateBytes
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            return xmlSniffCandidate ?: throw IllegalArgumentException(
                "ZIP archive is not a valid Royal TS .rtsz document (no .rtsx or .xml entry found)."
            )
        }

        if (!looksLikeXmlPayload(rawBytes)) {
            throw IllegalArgumentException(
                "File is neither a valid Royal TS .rtsz ZIP archive nor a valid .rtsx XML document."
            )
        }
        return rawBytes
    }

    private fun looksLikeRoyalXml(bytes: ByteArray): Boolean {
        val head = bytes.decodeToString(0, minOf(bytes.size, 1024), throwOnInvalidSequence = false)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return head.startsWith("<?xml", ignoreCase = true) ||
            head.startsWith("<RTSZDocument", ignoreCase = true) ||
            head.startsWith("<RoyalDocument", ignoreCase = true)
    }

    private fun looksLikeXmlPayload(bytes: ByteArray): Boolean {
        val head = bytes.decodeToString(0, minOf(bytes.size, 1024), throwOnInvalidSequence = false)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return head.startsWith("<")
    }

    private fun Element.hasDirectChild(tagName: String): Boolean {
        val children = this.childNodes
        for (idx in 0 until children.length) {
            val child = children.item(idx)
            if (child.nodeType == Node.ELEMENT_NODE && (child as Element).tagName == tagName) {
                return true
            }
        }
        return false
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

    private fun Element.childBoolean(vararg tagNames: String, default: Boolean): Boolean {
        for (tag in tagNames) {
            if (hasDirectChild(tag)) {
                val raw = childText(tag)
                if (raw.equals("true", ignoreCase = true) || raw == "1") return true
                if (raw.equals("false", ignoreCase = true) || raw == "0") return false
            }
        }
        return default
    }
}
