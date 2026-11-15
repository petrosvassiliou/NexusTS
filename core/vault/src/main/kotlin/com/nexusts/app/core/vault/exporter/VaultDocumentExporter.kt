package com.nexusts.app.core.vault.exporter

import com.nexusts.app.core.vault.model.CustomPropertyEntry
import com.nexusts.app.core.vault.model.GatewayTransportKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.RdpAudioRedirectionMode
import com.nexusts.app.core.vault.model.RdpResizeBehavior
import com.nexusts.app.core.vault.model.SshPortForwardRule
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class VaultExportFormat(val extension: String, val mimeType: String) {
    NXTS_VAULT("nxts", "application/vnd.nexusts.vault"),
    RTSZ_ROYAL_ARCHIVE("rtsz", "application/octet-stream"),
    ENTERPRISE_CSV("csv", "text/csv")
}

/**
 * Enterprise Multi-Format Exporter for [NxtsVaultDocument]:
 * 1. Royal TS `.rtsz` ZIP-compressed XML archive (100% round-trip compatible with Royal TS for macOS/Windows & [com.nexusts.app.core.vault.importer.RtszDocumentImporter]).
 * 2. Royal TS `.rtsx` raw XML document.
 * 3. RFC 4180 `.csv` inventory spreadsheet export for security compliance & asset audits.
 */
class VaultDocumentExporter {

    fun exportToRtszBytes(
        document: NxtsVaultDocument,
        includeSecrets: Boolean = false
    ): ByteArray {
        val xmlBytes = exportToRoyalTsXml(document, includeSecrets).toByteArray(StandardCharsets.UTF_8)
        val baos = ByteArrayOutputStream(xmlBytes.size)
        ZipOutputStream(baos, StandardCharsets.UTF_8).use { zip ->
            val entry = ZipEntry("document.rtsx")
            entry.time = document.updatedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
            zip.putNextEntry(entry)
            zip.write(xmlBytes)
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    fun exportToRoyalTsXml(
        document: NxtsVaultDocument,
        includeSecrets: Boolean = false
    ): String = buildString(16_384) {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("<RTSZDocument>")

        // 1. Root <RoyalDocument>
        appendLine("  <RoyalDocument>")
        appendXmlTag("ID", document.documentId, indent = 4)
        appendXmlTag("Name", document.name, indent = 4)
        appendXmlTag("Modified", document.revision.toString(), indent = 4)
        if (document.description.isNotBlank()) {
            appendXmlTag("Description", document.description, indent = 4)
        }
        appendInheritableXml(document.rootInheritable, includeSecrets = includeSecrets, indent = 4)
        appendLine("  </RoyalDocument>")

        // 2. <RoyalFolder> Hierarchy
        document.folders.filter { it.id !in document.tombstonedObjectIds }.forEach { folder ->
            appendLine("  <RoyalFolder>")
            appendXmlTag("ID", folder.id, indent = 4)
            appendXmlTag("ParentID", folder.parentFolderId ?: document.documentId, indent = 4)
            appendXmlTag("Name", folder.name, indent = 4)
            if (folder.description.isNotBlank()) appendXmlTag("Notes", folder.description, indent = 4)
            if (folder.tags.isNotEmpty()) appendXmlTag("Tags", folder.tags.joinToString(";"), indent = 4)
            appendXmlTag("SortOrder", folder.sortOrder.toString(), indent = 4)
            appendInheritableXml(folder.inheritable, includeSecrets = includeSecrets, indent = 4)
            appendLine("  </RoyalFolder>")
        }

        // 2b. <NexusSmartFolder> Dynamic Filter Definitions
        document.smartFolders.filter { it.id !in document.tombstonedObjectIds }.forEach { sf ->
            appendLine("  <NexusSmartFolder>")
            appendXmlTag("ID", sf.id, indent = 4)
            appendXmlTag("ParentID", document.documentId, indent = 4)
            appendXmlTag("Name", sf.name, indent = 4)
            if (sf.filter.includeTags.isNotEmpty()) {
                appendXmlTag("IncludeTags", sf.filter.includeTags.joinToString(";"), indent = 4)
            }
            if (sf.filter.excludeTags.isNotEmpty()) {
                appendXmlTag("ExcludeTags", sf.filter.excludeTags.joinToString(";"), indent = 4)
            }
            if (sf.filter.includeProtocols.isNotEmpty()) {
                appendXmlTag("IncludeProtocols", sf.filter.includeProtocols.joinToString(";") { it.name }, indent = 4)
            }
            sf.filter.scopeFolderId?.takeIf { it.isNotBlank() }?.let {
                appendXmlTag("ScopeFolderId", it, indent = 4)
            }
            sf.filter.osFamilyFilter?.takeIf { it.isNotBlank() }?.let {
                appendXmlTag("OSFamilyFilter", it, indent = 4)
            }
            sf.filter.hostPatternRegex?.takeIf { it.isNotBlank() }?.let {
                appendXmlTag("HostPatternRegex", it, indent = 4)
            }
            sf.filter.environmentFilter?.takeIf { it.isNotBlank() }?.let {
                appendXmlTag("EnvironmentFilter", it, indent = 4)
            }
            appendLine("  </NexusSmartFolder>")
        }

        // 2c. <RoyalDynamicFolder> Dynamic Inventory Folders
        document.dynamicFolders.filter { it.id !in document.tombstonedObjectIds }.forEach { df ->
            appendLine("  <RoyalDynamicFolder>")
            appendXmlTag("ID", df.id, indent = 4)
            appendXmlTag("ParentID", df.parentFolderId ?: document.documentId, indent = 4)
            appendXmlTag("Name", df.name, indent = 4)
            appendXmlTag("Interpreter", df.interpreter.name, indent = 4)
            appendXmlTag("Script", df.scriptOrEndpointPayload, indent = 4)
            appendXmlTag("RefreshIntervalMinutes", df.refreshIntervalMinutes.toString(), indent = 4)
            appendInheritableXml(df.inheritable, includeSecrets = includeSecrets, indent = 4)
            appendLine("  </RoyalDynamicFolder>")
        }

        // 3. <RoyalCredential> Entries
        document.credentials.filter { it.id !in document.tombstonedObjectIds }.forEach { cred ->
            appendLine("  <RoyalCredential>")
            appendXmlTag("ID", cred.id, indent = 4)
            appendXmlTag("ParentID", cred.parentFolderId ?: document.documentId, indent = 4)
            appendXmlTag("Name", cred.name, indent = 4)
            appendXmlTag("SourceKind", cred.sourceKind.name, indent = 4)
            val fullUser = if (cred.domain.isNotBlank()) "${cred.domain}\\${cred.username}" else cred.username
            appendXmlTag("UserName", fullUser, indent = 4)
            if (cred.domain.isNotBlank()) appendXmlTag("Domain", cred.domain, indent = 4)
            if (cred.publicKeyOpenSsh.isNotBlank()) appendXmlTag("PublicKeyOpenSsh", cred.publicKeyOpenSsh, indent = 4)
            if (cred.keyFingerprintSha256.isNotBlank()) appendXmlTag("KeyFingerprintSha256", cred.keyFingerprintSha256, indent = 4)
            if (cred.externalReferenceUri.isNotBlank()) appendXmlTag("ExternalReferenceUri", cred.externalReferenceUri, indent = 4)
            if (cred.fido2CredentialIdBase64.isNotBlank()) appendXmlTag("Fido2CredentialIdBase64", cred.fido2CredentialIdBase64, indent = 4)
            if (includeSecrets) {
                if (cred.secretPassword.isNotEmpty()) appendXmlTag("Password", cred.secretPassword, indent = 4)
                if (cred.privateKeyPem.isNotEmpty()) appendXmlTag("KeyFileContent", cred.privateKeyPem, indent = 4)
                if (cred.keyPassphrase.isNotEmpty()) appendXmlTag("KeyPassphrase", cred.keyPassphrase, indent = 4)
                if (cred.totpSecretBase32.isNotEmpty()) appendXmlTag("OneTimePasswordSecret", cred.totpSecretBase32, indent = 4)
            }
            appendLine("  </RoyalCredential>")
        }

        // 4. <RoyalSecureGateway> / <RoyalSSHGateway> Gateways
        document.gateways.filter { it.id !in document.tombstonedObjectIds }.forEach { gw ->
            val gwTag = if (gw.kind == GatewayTransportKind.NEXUSTS_SECURE_GATEWAY) "RoyalSecureGateway" else "RoyalSSHGateway"
            appendLine("  <$gwTag>")
            appendXmlTag("ID", gw.id, indent = 4)
            appendXmlTag("ParentID", document.documentId, indent = 4)
            appendXmlTag("Name", gw.name, indent = 4)
            appendXmlTag("NexusGatewayKind", gw.kind.name, indent = 4)
            appendXmlTag("URI", gw.host, indent = 4)
            appendXmlTag("Port", gw.port.toString(), indent = 4)
            appendXmlTag("VerifyTlsCertificate", gw.verifyTlsCertificate.toString(), indent = 4)
            gw.credentialId?.let { appendXmlTag("CredentialID", it, indent = 4) }
            appendLine("  </$gwTag>")
        }

        // 5. Protocol Connections
        document.connections.filter { it.id !in document.tombstonedObjectIds }.forEach { conn ->
            val tag = royalTagForProtocol(conn.protocol)
            appendLine("  <$tag>")
            appendXmlTag("ID", conn.id, indent = 4)
            appendXmlTag("ParentID", conn.parentFolderId ?: document.documentId, indent = 4)
            appendXmlTag("Name", conn.name, indent = 4)
            appendXmlTag("NexusProtocolType", conn.protocol.name, indent = 4)
            appendXmlTag("URI", conn.host, indent = 4)
            appendXmlTag("OSFamily", conn.osFamily, indent = 4)
            if (conn.tags.isNotEmpty()) appendXmlTag("Tags", conn.tags.joinToString(";"), indent = 4)
            if (conn.favorite) appendXmlTag("Favorite", "True", indent = 4)
            if (conn.notes.isNotBlank()) appendXmlTag("Notes", conn.notes, indent = 4)
            appendInheritableXml(conn.inheritable, includeSecrets = includeSecrets, indent = 4)
            appendProtocolSettingsXml(conn.protocol, conn.settings, isTemplate = false, indent = 4)
            appendLine("  </$tag>")
        }

        // 6. Reusable Connection Templates across ALL protocol types
        document.templates.filter { it.id !in document.tombstonedObjectIds }.forEach { tpl ->
            val tag = royalTagForProtocol(tpl.protocol)
            appendLine("  <$tag>")
            appendXmlTag("ID", tpl.id, indent = 4)
            appendXmlTag("ParentID", document.documentId, indent = 4)
            appendXmlTag("Name", tpl.name, indent = 4)
            appendXmlTag("IsTemplate", "True", indent = 4)
            appendXmlTag("NexusProtocolType", tpl.protocol.name, indent = 4)
            if (tpl.description.isNotBlank()) appendXmlTag("Description", tpl.description, indent = 4)
            tpl.colorAccentHex?.let {
                appendXmlTag("TemplateColor", it, indent = 4)
                if (tpl.inheritable.displayColorAccentHex == null) {
                    appendXmlTag("Color", it, indent = 4)
                }
            }
            appendInheritableXml(tpl.inheritable, includeSecrets = includeSecrets, indent = 4)
            appendProtocolSettingsXml(tpl.protocol, tpl.settings, isTemplate = true, indent = 4)
            appendLine("  </$tag>")
        }

        // 7. <RoyalCommandTask> & <RoyalKeySequenceTask>
        document.commandTasks.filter { it.id !in document.tombstonedObjectIds }.forEach { task ->
            appendLine("  <RoyalCommandTask>")
            appendXmlTag("ID", task.id, indent = 4)
            appendXmlTag("ParentID", document.documentId, indent = 4)
            appendXmlTag("Name", task.name, indent = 4)
            appendXmlTag("Command", task.commandTemplate, indent = 4)
            appendXmlTag("BroadcastToAll", task.broadcastToAllSelectedSessions.toString(), indent = 4)
            appendXmlTag("AppendNewline", task.appendNewline.toString(), indent = 4)
            appendLine("  </RoyalCommandTask>")
        }
        document.keySequenceTasks.filter { it.id !in document.tombstonedObjectIds }.forEach { macro ->
            appendLine("  <RoyalKeySequenceTask>")
            appendXmlTag("ID", macro.id, indent = 4)
            appendXmlTag("ParentID", document.documentId, indent = 4)
            appendXmlTag("Name", macro.name, indent = 4)
            appendXmlTag("KeySequence", macro.macroSequence, indent = 4)
            appendXmlTag("KeystrokeDelayMs", macro.keystrokeDelayMs.toString(), indent = 4)
            appendLine("  </RoyalKeySequenceTask>")
        }

        // 8. <RoyalToDo> Checklist Entries
        document.todoItems.filter { it.id !in document.tombstonedObjectIds }.forEach { todo ->
            appendLine("  <RoyalToDo>")
            appendXmlTag("ID", todo.id, indent = 4)
            appendXmlTag("ParentID", todo.targetObjectId ?: document.documentId, indent = 4)
            appendXmlTag("Name", todo.title, indent = 4)
            appendXmlTag("Completed", if (todo.completed) "True" else "False", indent = 4)
            appendXmlTag("Priority", todo.priority, indent = 4)
            if (todo.dueIsoDate.isNotBlank()) appendXmlTag("DueDate", todo.dueIsoDate, indent = 4)
            appendLine("  </RoyalToDo>")
        }

        appendLine("</RTSZDocument>")
    }

    /**
     * Exports all active connections in RFC 4180 CSV format (CRLF) for compliance, CMDB, and spreadsheet import.
     */
    fun exportToEnterpriseCsv(document: NxtsVaultDocument): String = buildString(8_192) {
        val folderIndex = document.folders.associateBy { it.id }
        val credNames = document.credentials.associate { it.id to it.name }

        append(
            listOf(
                "ConnectionID",
                "Name",
                "Protocol",
                "Host",
                "Port",
                "Folder",
                "OSFamily",
                "Favorite",
                "CredentialMode",
                "AssignedCredential",
                "Tags",
                "ColorHex",
                "Notes",
                "ContainerCluster",
                "ContainerContext",
                "ContainerNamespace",
                "ContainerWorkload",
                "ContainerSubContainer",
                "ContainerShell",
                "ContainerFollowLogs"
            ).joinToString(",") { csvEscape(it) }
        ).append("\r\n")

        document.connections.filter { it.id !in document.tombstonedObjectIds }.forEach { conn ->
            val port = extractDefaultOrConfiguredPort(conn.protocol, conn.settings)
            val folderLabel = resolveFolderHierarchyPath(conn.parentFolderId, folderIndex)
            val credLabel = conn.inheritable.explicitCredentialId?.let { credNames[it] }.orEmpty()
            val tagsJoined = conn.tags.joinToString(";")
            val color = conn.inheritable.displayColorAccentHex.orEmpty()
            val containerExec = conn.settings as? ProtocolSpecificSettings.ContainerExec

            append(
                listOf(
                    conn.id,
                    conn.name,
                    conn.protocol.name,
                    conn.host,
                    port.toString(),
                    folderLabel,
                    conn.osFamily,
                    conn.favorite.toString(),
                    conn.inheritable.credentialMode.name,
                    credLabel,
                    tagsJoined,
                    color,
                    conn.notes,
                    containerExec?.clusterOrSocketEndpoint.orEmpty(),
                    containerExec?.contextName.orEmpty(),
                    containerExec?.namespace.orEmpty(),
                    containerExec?.podOrContainerName.orEmpty(),
                    containerExec?.subContainerName.orEmpty(),
                    containerExec?.shellCommand.orEmpty(),
                    containerExec?.followLogsOnOpen?.toString().orEmpty()
                ).joinToString(",") { csvEscape(it) }
            ).append("\r\n")
        }
    }

    private fun resolveFolderHierarchyPath(folderId: String?, folderIndex: Map<String, VaultFolder>): String {
        if (folderId.isNullOrBlank()) return ""
        val segments = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var cursor: String? = folderId
        while (cursor != null && visited.add(cursor)) {
            val folder = folderIndex[cursor] ?: break
            segments.add(0, folder.name)
            cursor = folder.parentFolderId
        }
        return segments.joinToString("/")
    }

    private fun StringBuilder.appendInheritableXml(
        inheritable: InheritableProperties,
        includeSecrets: Boolean,
        indent: Int
    ) {
        val credModeCode = when (inheritable.credentialMode) {
            InheritanceMode.NONE_DIRECT -> "0"
            InheritanceMode.OVERRIDE_EXPLICIT -> "2"
            InheritanceMode.PROMPT_AT_CONNECT -> "3"
            InheritanceMode.INHERIT_FROM_PARENT -> "4"
        }
        appendXmlTag("CredentialMode", credModeCode, indent)
        inheritable.explicitCredentialId?.let { appendXmlTag("CredentialID", it, indent) }

        val gwModeCode = when (inheritable.gatewayMode) {
            InheritanceMode.NONE_DIRECT -> "0"
            InheritanceMode.OVERRIDE_EXPLICIT -> "2"
            InheritanceMode.PROMPT_AT_CONNECT -> "3"
            InheritanceMode.INHERIT_FROM_PARENT -> "4"
        }
        appendXmlTag("GatewayMode", gwModeCode, indent)
        inheritable.explicitGatewayId?.let { appendXmlTag("SecureGatewayID", it, indent) }
        inheritable.environmentBadge?.takeIf { it.isNotBlank() }?.let { appendXmlTag("EnvironmentBadge", it, indent) }
        inheritable.preConnectTaskId?.takeIf { it.isNotBlank() }?.let { appendXmlTag("PreConnectTaskID", it, indent) }
        inheritable.postDisconnectTaskId?.takeIf { it.isNotBlank() }?.let { appendXmlTag("PostDisconnectTaskID", it, indent) }
        if (inheritable.requireConnectConfirmation) {
            appendXmlTag("RequireConnectConfirmation", "True", indent)
        }
        inheritable.displayColorAccentHex?.let {
            appendXmlTag("Color", it, indent)
            appendXmlTag("InheritableColor", it, indent)
        }
        inheritable.customProperties.take(10).forEachIndexed { index: Int, prop: CustomPropertyEntry ->
            val fieldNum = index + 1
            appendXmlTag("CustomField${fieldNum}Name", prop.key, indent)
            if (prop.isProtected) {
                appendXmlTag("CustomField${fieldNum}Protected", "True", indent)
            }
            if (!prop.isProtected || includeSecrets) {
                appendXmlTag("CustomField$fieldNum", prop.value, indent)
            }
        }
    }

    private fun StringBuilder.appendProtocolSettingsXml(
        protocol: VaultProtocolType,
        settings: ProtocolSpecificSettings,
        isTemplate: Boolean,
        indent: Int
    ) {
        when (settings) {
            is ProtocolSpecificSettings.Rdp -> {
                appendXmlTag("RDPPort", settings.port.toString(), indent)
                appendXmlTag("ConnectToAdministerOrConsole", settings.connectToConsoleAdmin.toString(), indent)
                appendXmlTag("AutoAdjustRetinaDisplay", settings.hiDpiEnabled.toString(), indent)
                appendXmlTag("DesktopScaleFactor", settings.desktopScaleFactorPercent.toString(), indent)
                appendXmlTag("DeviceScaleFactor", settings.deviceScaleFactorPercent.toString(), indent)
                appendXmlTag("EnableGfxPipeline", settings.enableGfxPipeline.toString(), indent)
                appendXmlTag("EnableH264Avc444", settings.enableH264Avc444.toString(), indent)
                appendXmlTag("EnableRemoteFx", settings.enableRemoteFx.toString(), indent)
                val resizeModeStr = when (settings.resizeBehavior) {
                    RdpResizeBehavior.DYNAMIC_DISP_DVC -> "DynamicDisp"
                    RdpResizeBehavior.SMART_SCALE_VIEWPORT -> "SmartSizing"
                    RdpResizeBehavior.FIXED_SERVER_RESOLUTION -> "Scrollbars"
                }
                appendXmlTag("ResizeMode", resizeModeStr, indent)
                appendXmlTag("SmartReconnect", (settings.resizeBehavior == RdpResizeBehavior.DYNAMIC_DISP_DVC).toString(), indent)
                appendXmlTag("SmartSizing", (settings.resizeBehavior == RdpResizeBehavior.SMART_SCALE_VIEWPORT).toString(), indent)
                appendXmlTag("ColorDepth", settings.colorDepthBits.toString(), indent)
                appendXmlTag("DesktopWidth", settings.customDesktopWidth.toString(), indent)
                appendXmlTag("DesktopHeight", settings.customDesktopHeight.toString(), indent)
                appendXmlTag("UseMultimon", settings.multiMonitorSpanning.toString(), indent)
                if (settings.selectedMonitorIds.isNotEmpty()) {
                    appendXmlTag("SelectedMonitorIds", settings.selectedMonitorIds.joinToString(","), indent)
                }
                appendXmlTag("RedirectClipboard", settings.redirectClipboard.toString(), indent)
                val audioCode = when (settings.audioRedirectionMode) {
                    RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE -> "0"
                    RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST -> "1"
                    RdpAudioRedirectionMode.DO_NOT_PLAY -> "2"
                }
                appendXmlTag("AudioRedirectionMode", audioCode, indent)
                appendXmlTag("RedirectAudioPlayback", settings.redirectAudioPlayback.toString(), indent)
                appendXmlTag("RedirectMicrophone", settings.redirectMicrophone.toString(), indent)
                appendXmlTag("RedirectAudioRecording", settings.redirectMicrophone.toString(), indent)
                appendXmlTag("RedirectDrives", settings.redirectDrives.toString(), indent)
                if (settings.redirectedDriveSafUris.isNotEmpty()) {
                    appendXmlTag("RedirectedDriveSafUris", settings.redirectedDriveSafUris.joinToString(";"), indent)
                }
                appendXmlTag("RedirectPrinters", settings.redirectPrinters.toString(), indent)
                appendXmlTag("RedirectPorts", settings.redirectPorts.toString(), indent)
                appendXmlTag("RedirectSmartCards", settings.redirectSmartcardYubiKey.toString(), indent)
                appendXmlTag("AllowFontSmoothing", settings.enableFontSmoothing.toString(), indent)
                appendXmlTag("AllowDesktopComposition", settings.enableDesktopComposition.toString(), indent)
                appendXmlTag("AllowWallpaper", settings.enableWallpaper.toString(), indent)
                appendXmlTag("AllowFullWindowDrag", settings.enableFullWindowDrag.toString(), indent)
                appendXmlTag("AllowMenuAnimations", settings.enableMenuAnimations.toString(), indent)
                appendXmlTag("AllowThemes", settings.enableThemes.toString(), indent)
                appendXmlTag("Compression", settings.compressionEnabled.toString(), indent)
                appendXmlTag("RemoteAppMode", settings.remoteAppMode.toString(), indent)
                if (settings.remoteAppProgram.isNotBlank()) {
                    appendXmlTag("RemoteAppProgram", settings.remoteAppProgram, indent)
                }
                if (settings.remoteAppArguments.isNotBlank()) {
                    appendXmlTag("RemoteAppArguments", settings.remoteAppArguments, indent)
                }
                appendXmlTag("NetworkLevelAuthentication", settings.nlaSecurityRequired.toString(), indent)
                appendXmlTag("TlsSecurity", settings.tlsSecurity.toString(), indent)
                appendXmlTag("RestrictedAdminMode", settings.restrictedAdminMode.toString(), indent)
                appendXmlTag("IgnoreCertificateWarning", settings.ignoreCertificateWarnings.toString(), indent)
                appendXmlTag("IgnoreCertificate", settings.ignoreCertificateWarnings.toString(), indent)
                if (settings.rdGatewayHostname.isNotBlank()) {
                    appendXmlTag("RDGatewayHost", settings.rdGatewayHostname, indent)
                }
                appendXmlTag("RDGatewayPort", settings.rdGatewayPort.toString(), indent)
            }
            is ProtocolSpecificSettings.Ssh -> {
                appendXmlTag("Port", settings.port.toString(), indent)
                if (protocol == VaultProtocolType.TELNET) {
                    appendXmlTag("IsTelnetConnection", "True", indent)
                }
                appendXmlTag("TerminalType", settings.terminalType, indent)
                settings.bastionJumpConnectionId?.takeIf { it.isNotBlank() }?.let {
                    appendXmlTag("BastionJumpConnectionId", it, indent)
                }
                appendXmlTag("EnableAgentForwarding", settings.enableAgentForwarding.toString(), indent)
                appendXmlTag("EnableX11Forwarding", settings.enableX11Forwarding.toString(), indent)
                appendXmlTag("X11DisplayHost", settings.x11DisplayHost, indent)
                if (settings.portForwards.isNotEmpty()) {
                    appendXmlTag("PortForwardRules", encodePortForwards(settings.portForwards), indent)
                }
                if (settings.startupCommand.isNotBlank()) {
                    appendXmlTag("StartupCommand", settings.startupCommand, indent)
                }
                appendXmlTag("KeepAliveIntervalSec", settings.keepAliveIntervalSec.toString(), indent)
                appendXmlTag("ScrollbackLines", settings.scrollbackLines.toString(), indent)
                appendXmlTag("SerialBaudRate", settings.serialBaudRate.toString(), indent)
                appendXmlTag("SerialDevicePath", settings.serialDevicePath, indent)
            }
            is ProtocolSpecificSettings.FileTransfer -> {
                appendXmlTag("Port", settings.port.toString(), indent)
                appendXmlTag("ConnectionType", protocol.name, indent)
                if (settings.initialLocalDirectory.isNotBlank()) {
                    appendXmlTag("InitialLocalPath", settings.initialLocalDirectory, indent)
                }
                appendXmlTag("InitialRemotePath", settings.initialRemoteDirectory, indent)
                appendXmlTag("PreserveTimestamps", settings.preserveTimestamps.toString(), indent)
                appendXmlTag("ShowHiddenDotfiles", settings.showHiddenDotfiles.toString(), indent)
                appendXmlTag("MaxConcurrentTransfers", settings.maxConcurrentTransfers.toString(), indent)
                appendXmlTag("FtpsImplicitTls", settings.ftpsImplicitTls.toString(), indent)
            }
            is ProtocolSpecificSettings.Vnc -> {
                appendXmlTag("VNCPort", settings.port.toString(), indent)
                if (protocol == VaultProtocolType.APPLE_SCREEN_SHARING || settings.appleArdDiffieHellmanAuth) {
                    appendXmlTag("IsAppleRemoteDesktop", "True", indent)
                }
                appendXmlTag("AppleArdDiffieHellmanAuth", settings.appleArdDiffieHellmanAuth.toString(), indent)
                if (settings.preferredEncodings.isNotEmpty()) {
                    appendXmlTag("PreferredEncodings", settings.preferredEncodings.joinToString(","), indent)
                }
                appendXmlTag("CompressionLevel", settings.compressionLevel.toString(), indent)
                appendXmlTag("JpegQualityLevel", settings.jpegQualityLevel.toString(), indent)
                appendXmlTag("ViewOnly", settings.viewOnly.toString(), indent)
                appendXmlTag("SharedSession", settings.sharedSession.toString(), indent)
            }
            is ProtocolSpecificSettings.WebPage -> {
                if (isTemplate && settings.url.isNotBlank()) {
                    appendXmlTag("URI", settings.url, indent)
                }
                appendXmlTag("WebPageUrl", settings.url, indent)
                appendXmlTag("IsolatedProfileId", settings.isolatedStorageProfileId, indent)
                appendXmlTag("AutoFill", settings.autoFillEnabled.toString(), indent)
                appendXmlTag("UsernameCssSelector", settings.usernameCssSelector, indent)
                appendXmlTag("PasswordCssSelector", settings.passwordCssSelector, indent)
                appendXmlTag("SubmitCssSelector", settings.submitCssSelector, indent)
                appendXmlTag("InjectHttpBasicAuth", settings.injectHttpBasicAuth.toString(), indent)
                if (settings.pinnedCertSha256.isNotBlank()) {
                    appendXmlTag("PinnedCertSha256", settings.pinnedCertSha256, indent)
                }
                appendXmlTag("IgnoreSelfSignedCert", settings.ignoreSelfSignedInternalCert.toString(), indent)
            }
            is ProtocolSpecificSettings.SerialUart -> {
                appendXmlTag("BaudRate", settings.baudRate.toString(), indent)
                appendXmlTag("DataBits", settings.dataBits.toString(), indent)
                appendXmlTag("StopBits", settings.stopBits.toString(), indent)
                appendXmlTag("Parity", settings.parity, indent)
                appendXmlTag("FlowControl", settings.flowControl, indent)
                appendXmlTag("DtrEnabled", settings.dtrEnabled.toString(), indent)
                appendXmlTag("RtsEnabled", settings.rtsEnabled.toString(), indent)
                appendXmlTag("UsbVendorIdFilter", settings.usbVendorIdFilter.toString(), indent)
                appendXmlTag("DevicePathHint", settings.devicePathHint, indent)
                appendXmlTag("LineEnding", settings.lineEnding, indent)
            }
            is ProtocolSpecificSettings.ContainerExec -> {
                appendXmlTag("ClusterEndpoint", settings.clusterOrSocketEndpoint, indent)
                appendXmlTag("ContextName", settings.contextName, indent)
                appendXmlTag("Namespace", settings.namespace, indent)
                appendXmlTag("Workload", settings.podOrContainerName, indent)
                appendXmlTag("ContainerName", settings.subContainerName, indent)
                appendXmlTag("Shell", settings.shellCommand, indent)
                appendXmlTag("FollowLogs", settings.followLogsOnOpen.toString(), indent)
                appendXmlTag("LogTailLines", settings.logTailLines.toString(), indent)
                if (settings.portForwards.isNotEmpty()) {
                    appendXmlTag("PortForwardRules", encodePortForwards(settings.portForwards), indent)
                }
                appendXmlTag("VerifyTls", settings.verifyTls.toString(), indent)
            }
            is ProtocolSpecificSettings.CloudVirtualization -> {
                appendXmlTag("ApiEndpointUrl", settings.apiEndpointUrl, indent)
                appendXmlTag("RegionOrCluster", settings.regionOrCluster, indent)
                if (settings.subscriptionOrAccountId.isNotBlank()) {
                    appendXmlTag("SubscriptionOrAccountId", settings.subscriptionOrAccountId, indent)
                }
                appendXmlTag("RealmOrDomain", settings.realmOrDomain, indent)
                appendXmlTag("AutoSyncDynamicInventory", settings.autoSyncDynamicInventory.toString(), indent)
            }
            is ProtocolSpecificSettings.NetUtility -> {
                appendXmlTag("PingCount", settings.pingCount.toString(), indent)
                appendXmlTag("PingIntervalMs", settings.pingIntervalMs.toString(), indent)
                appendXmlTag("TracerouteMaxHops", settings.tracerouteMaxHops.toString(), indent)
                appendXmlTag("PortScanStart", settings.portScanRangeStart.toString(), indent)
                appendXmlTag("PortScanEnd", settings.portScanRangeEnd.toString(), indent)
                appendXmlTag("PortScanConcurrency", settings.portScanConcurrency.toString(), indent)
                if (settings.wolMacAddress.isNotBlank()) {
                    appendXmlTag("WolMacAddress", settings.wolMacAddress, indent)
                }
                appendXmlTag("WolBroadcastIp", settings.wolBroadcastAddress, indent)
                appendXmlTag("WolUdpPort", settings.wolUdpPort.toString(), indent)
                if (settings.wolSecureOnHex.isNotBlank()) {
                    appendXmlTag("WolSecureOnHex", settings.wolSecureOnHex, indent)
                }
            }
        }
    }

    private fun encodePortForwards(rules: List<SshPortForwardRule>): String =
        rules.joinToString(";") { r ->
            listOf(
                r.id,
                r.kind.name,
                r.bindAddress,
                r.bindPort.toString(),
                r.targetHost,
                r.targetPort.toString(),
                r.enabled.toString()
            ).joinToString("|")
        }

    private fun royalTagForProtocol(protocol: VaultProtocolType): String = when (protocol) {
        VaultProtocolType.RDP, VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> "RoyalRDSConnection"
        VaultProtocolType.SSH, VaultProtocolType.TELNET, VaultProtocolType.SERIAL,
        VaultProtocolType.KUBERNETES_POD, VaultProtocolType.DOCKER_CONTAINER,
        VaultProtocolType.UTILITY_PING, VaultProtocolType.UTILITY_TRACEROUTE,
        VaultProtocolType.UTILITY_PORT_SCANNER, VaultProtocolType.UTILITY_WAKE_ON_LAN -> "RoyalSSHConnection"
        VaultProtocolType.SFTP, VaultProtocolType.FTP, VaultProtocolType.FTPS, VaultProtocolType.SCP -> "RoyalFileTransferConnection"
        VaultProtocolType.VNC, VaultProtocolType.APPLE_SCREEN_SHARING -> "RoyalVNCConnection"
        VaultProtocolType.WEB_PAGE -> "RoyalWebPageConnection"
        VaultProtocolType.VMWARE_VSPHERE, VaultProtocolType.PROXMOX_VE,
        VaultProtocolType.AWS_EC2, VaultProtocolType.AZURE_VM -> "RoyalVMwareConnection"
        VaultProtocolType.HYPER_V -> "RoyalHyperVConnection"
    }

    private fun extractDefaultOrConfiguredPort(protocol: VaultProtocolType, settings: ProtocolSpecificSettings): Int = when (settings) {
        is ProtocolSpecificSettings.Rdp -> settings.port
        is ProtocolSpecificSettings.Ssh -> settings.port
        is ProtocolSpecificSettings.FileTransfer -> settings.port
        is ProtocolSpecificSettings.Vnc -> settings.port
        is ProtocolSpecificSettings.SerialUart -> settings.baudRate
        is ProtocolSpecificSettings.NetUtility -> when (protocol) {
            VaultProtocolType.UTILITY_WAKE_ON_LAN -> settings.wolUdpPort
            VaultProtocolType.UTILITY_PORT_SCANNER -> settings.portScanRangeStart
            else -> 0
        }
        else -> when (protocol) {
            VaultProtocolType.WEB_PAGE -> 443
            VaultProtocolType.PROXMOX_VE -> 8006
            VaultProtocolType.VMWARE_VSPHERE -> 443
            VaultProtocolType.HYPER_V -> 5986
            VaultProtocolType.KUBERNETES_POD -> 6443
            VaultProtocolType.DOCKER_CONTAINER -> 2376
            else -> 0
        }
    }

    private fun StringBuilder.appendXmlTag(tag: String, value: String, indent: Int) {
        val pad = " ".repeat(indent)
        append(pad).append('<').append(tag).append('>')
        append(xmlEscape(value))
        append("</").append(tag).appendLine('>')
    }

    private fun xmlEscape(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private fun csvEscape(raw: String): String {
        val needsQuotes = raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return raw
        return "\"" + raw.replace("\"", "\"\"") + "\""
    }
}
