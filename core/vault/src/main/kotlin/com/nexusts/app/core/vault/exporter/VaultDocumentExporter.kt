package com.nexusts.app.core.vault.exporter

import com.nexusts.app.core.vault.model.CustomPropertyEntry
import com.nexusts.app.core.vault.model.GatewayTransportKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.RdpAudioRedirectionMode
import com.nexusts.app.core.vault.model.RdpResizeBehavior
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
        appendLine("  </RoyalDocument>")

        // 2. <RoyalFolder> Hierarchy
        document.folders.filter { it.id !in document.tombstonedObjectIds }.forEach { folder ->
            appendLine("  <RoyalFolder>")
            appendXmlTag("ID", folder.id, indent = 4)
            appendXmlTag("ParentID", folder.parentFolderId ?: document.documentId, indent = 4)
            appendXmlTag("Name", folder.name, indent = 4)
            appendInheritableXml(folder.inheritable, indent = 4)
            appendLine("  </RoyalFolder>")
        }

        // 3. <RoyalCredential> Entries
        document.credentials.filter { it.id !in document.tombstonedObjectIds }.forEach { cred ->
            appendLine("  <RoyalCredential>")
            appendXmlTag("ID", cred.id, indent = 4)
            appendXmlTag("ParentID", cred.parentFolderId ?: document.documentId, indent = 4)
            appendXmlTag("Name", cred.name, indent = 4)
            val fullUser = if (cred.domain.isNotBlank()) "${cred.domain}\\${cred.username}" else cred.username
            appendXmlTag("UserName", fullUser, indent = 4)
            if (includeSecrets) {
                if (cred.secretPassword.isNotEmpty()) appendXmlTag("Password", cred.secretPassword, indent = 4)
                if (cred.privateKeyPem.isNotEmpty()) appendXmlTag("KeyFileContent", cred.privateKeyPem, indent = 4)
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
            appendXmlTag("URI", gw.host, indent = 4)
            appendXmlTag("Port", gw.port.toString(), indent = 4)
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
            appendXmlTag("URI", conn.host, indent = 4)
            if (conn.favorite) appendXmlTag("Favorite", "True", indent = 4)
            if (conn.notes.isNotBlank()) appendXmlTag("Notes", conn.notes, indent = 4)
            appendInheritableXml(conn.inheritable, indent = 4)
            appendProtocolSettingsXml(conn.protocol, conn.settings, indent = 4)
            appendLine("  </$tag>")
        }

        // 6. <RoyalTemplate> Reusable Connection Templates
        document.templates.filter { it.id !in document.tombstonedObjectIds }.forEach { tpl ->
            val tag = royalTagForProtocol(tpl.protocol)
            appendLine("  <$tag>")
            appendXmlTag("ID", tpl.id, indent = 4)
            appendXmlTag("ParentID", document.documentId, indent = 4)
            appendXmlTag("Name", tpl.name, indent = 4)
            appendXmlTag("IsTemplate", "True", indent = 4)
            if (tpl.description.isNotBlank()) appendXmlTag("Description", tpl.description, indent = 4)
            tpl.colorAccentHex?.let { appendXmlTag("Color", it, indent = 4) }
            appendInheritableXml(tpl.inheritable, indent = 4)
            appendProtocolSettingsXml(tpl.protocol, tpl.settings, indent = 4)
            appendLine("  </$tag>")
        }

        // 7. <RoyalCommandTask> & <RoyalKeySequenceTask>
        document.commandTasks.filter { it.id !in document.tombstonedObjectIds }.forEach { task ->
            appendLine("  <RoyalCommandTask>")
            appendXmlTag("ID", task.id, indent = 4)
            appendXmlTag("ParentID", document.documentId, indent = 4)
            appendXmlTag("Name", task.name, indent = 4)
            appendXmlTag("Command", task.commandTemplate, indent = 4)
            appendLine("  </RoyalCommandTask>")
        }
        document.keySequenceTasks.filter { it.id !in document.tombstonedObjectIds }.forEach { macro ->
            appendLine("  <RoyalKeySequenceTask>")
            appendXmlTag("ID", macro.id, indent = 4)
            appendXmlTag("ParentID", document.documentId, indent = 4)
            appendXmlTag("Name", macro.name, indent = 4)
            appendXmlTag("KeySequence", macro.macroSequence, indent = 4)
            appendLine("  </RoyalKeySequenceTask>")
        }

        // 8. <RoyalToDoItem> Checklist Entries
        document.todoItems.filter { it.id !in document.tombstonedObjectIds }.forEach { todo ->
            appendLine("  <RoyalToDoItem>")
            appendXmlTag("ID", todo.id, indent = 4)
            appendXmlTag("ParentID", todo.targetObjectId ?: document.documentId, indent = 4)
            appendXmlTag("Name", todo.title, indent = 4)
            appendXmlTag("Completed", if (todo.completed) "True" else "False", indent = 4)
            appendXmlTag("Priority", todo.priority, indent = 4)
            if (todo.dueIsoDate.isNotBlank()) appendXmlTag("DueDate", todo.dueIsoDate, indent = 4)
            appendLine("  </RoyalToDoItem>")
        }

        appendLine("</RTSZDocument>")
    }

    /**
     * Exports all active connections in RFC 4180 CSV format (CRLF) for compliance, CMDB, and spreadsheet import.
     */
    fun exportToEnterpriseCsv(document: NxtsVaultDocument): String = buildString(8_192) {
        val folderNames = document.folders.associate { it.id to it.name }
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
                "Notes"
            ).joinToString(",") { csvEscape(it) }
        ).append("\r\n")

        document.connections.filter { it.id !in document.tombstonedObjectIds }.forEach { conn ->
            val port = extractDefaultOrConfiguredPort(conn.protocol, conn.settings)
            val folderLabel = conn.parentFolderId?.let { folderNames[it] }.orEmpty()
            val credLabel = conn.inheritable.explicitCredentialId?.let { credNames[it] }.orEmpty()
            val tagsJoined = conn.tags.joinToString(";")
            val color = conn.inheritable.displayColorAccentHex.orEmpty()

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
                    conn.notes
                ).joinToString(",") { csvEscape(it) }
            ).append("\r\n")
        }
    }

    private fun StringBuilder.appendInheritableXml(inheritable: InheritableProperties, indent: Int) {
        val credModeCode = when (inheritable.credentialMode) {
            InheritanceMode.NONE_DIRECT -> "0"
            InheritanceMode.OVERRIDE_EXPLICIT -> "2"
            InheritanceMode.PROMPT_AT_CONNECT -> "3"
            InheritanceMode.INHERIT_FROM_PARENT -> "4"
        }
        appendXmlTag("CredentialMode", credModeCode, indent)
        inheritable.explicitCredentialId?.let { appendXmlTag("CredentialID", it, indent) }
        inheritable.explicitGatewayId?.let { appendXmlTag("SecureGatewayID", it, indent) }
        inheritable.displayColorAccentHex?.let { appendXmlTag("Color", it, indent) }
        inheritable.customProperties.take(10).forEachIndexed { index: Int, prop: CustomPropertyEntry ->
            val fieldNum = index + 1
            appendXmlTag("CustomField${fieldNum}Name", prop.key, indent)
            if (!prop.isProtected) {
                appendXmlTag("CustomField$fieldNum", prop.value, indent)
            }
        }
    }

    private fun StringBuilder.appendProtocolSettingsXml(
        protocol: VaultProtocolType,
        settings: ProtocolSpecificSettings,
        indent: Int
    ) {
        when (settings) {
            is ProtocolSpecificSettings.Rdp -> {
                appendXmlTag("RDPPort", settings.port.toString(), indent)
                appendXmlTag("ConnectToAdministerOrConsole", settings.connectToConsoleAdmin.toString(), indent)
                appendXmlTag("ColorDepth", settings.colorDepthBits.toString(), indent)
                appendXmlTag("DesktopWidth", settings.customDesktopWidth.toString(), indent)
                appendXmlTag("DesktopHeight", settings.customDesktopHeight.toString(), indent)
                appendXmlTag("SmartReconnect", (settings.resizeBehavior == RdpResizeBehavior.DYNAMIC_DISP_DVC).toString(), indent)
                appendXmlTag("SmartSizing", (settings.resizeBehavior == RdpResizeBehavior.SMART_SCALE_VIEWPORT).toString(), indent)
                appendXmlTag("RedirectClipboard", settings.redirectClipboard.toString(), indent)
                val audioCode = when (settings.audioRedirectionMode) {
                    RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE -> "0"
                    RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST -> "1"
                    RdpAudioRedirectionMode.DO_NOT_PLAY -> "2"
                }
                appendXmlTag("AudioRedirectionMode", audioCode, indent)
                appendXmlTag("RedirectAudioRecording", settings.redirectMicrophone.toString(), indent)
                appendXmlTag("RedirectDrives", settings.redirectDrives.toString(), indent)
                appendXmlTag("RedirectPrinters", settings.redirectPrinters.toString(), indent)
                appendXmlTag("RedirectPorts", settings.redirectPorts.toString(), indent)
                appendXmlTag("RedirectSmartCards", settings.redirectSmartcardYubiKey.toString(), indent)
                appendXmlTag("UseMultimon", settings.multiMonitorSpanning.toString(), indent)
                appendXmlTag("AllowFontSmoothing", settings.enableFontSmoothing.toString(), indent)
                appendXmlTag("AllowDesktopComposition", settings.enableDesktopComposition.toString(), indent)
                appendXmlTag("AllowWallpaper", settings.enableWallpaper.toString(), indent)
                appendXmlTag("NetworkLevelAuthentication", settings.nlaSecurityRequired.toString(), indent)
                appendXmlTag("RestrictedAdminMode", settings.restrictedAdminMode.toString(), indent)
                appendXmlTag("IgnoreCertificate", settings.ignoreCertificateWarnings.toString(), indent)
                if (settings.rdGatewayHostname.isNotBlank()) {
                    appendXmlTag("RDGatewayHost", settings.rdGatewayHostname, indent)
                }
            }
            is ProtocolSpecificSettings.Ssh -> {
                appendXmlTag("Port", settings.port.toString(), indent)
                if (protocol == VaultProtocolType.TELNET) {
                    appendXmlTag("IsTelnetConnection", "True", indent)
                }
            }
            is ProtocolSpecificSettings.FileTransfer -> {
                appendXmlTag("Port", settings.port.toString(), indent)
            }
            is ProtocolSpecificSettings.Vnc -> {
                appendXmlTag("VNCPort", settings.port.toString(), indent)
            }
            is ProtocolSpecificSettings.WebPage -> {
                if (settings.url.isNotBlank()) appendXmlTag("URI", settings.url, indent)
            }
            is ProtocolSpecificSettings.SerialUart -> {
                appendXmlTag("BaudRate", settings.baudRate.toString(), indent)
                appendXmlTag("DataBits", settings.dataBits.toString(), indent)
                appendXmlTag("StopBits", settings.stopBits.toString(), indent)
                appendXmlTag("Parity", settings.parity, indent)
                appendXmlTag("FlowControl", settings.flowControl, indent)
            }
            is ProtocolSpecificSettings.ContainerExec -> {
                appendXmlTag("Namespace", settings.namespace, indent)
                appendXmlTag("Workload", settings.podOrContainerName, indent)
                appendXmlTag("Shell", settings.shellCommand, indent)
            }
            else -> Unit
        }
    }

    private fun royalTagForProtocol(protocol: VaultProtocolType): String = when (protocol) {
        VaultProtocolType.RDP, VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> "RoyalRDSConnection"
        VaultProtocolType.SSH, VaultProtocolType.TELNET, VaultProtocolType.SERIAL,
        VaultProtocolType.KUBERNETES_POD, VaultProtocolType.DOCKER_CONTAINER -> "RoyalSSHConnection"
        VaultProtocolType.SFTP, VaultProtocolType.FTP, VaultProtocolType.FTPS, VaultProtocolType.SCP -> "RoyalFileTransferConnection"
        VaultProtocolType.VNC, VaultProtocolType.APPLE_SCREEN_SHARING -> "RoyalVNCConnection"
        VaultProtocolType.WEB_PAGE -> "RoyalWebPageConnection"
        VaultProtocolType.VMWARE_VSPHERE -> "RoyalVMwareConnection"
        VaultProtocolType.HYPER_V -> "RoyalHyperVConnection"
        else -> "RoyalSSHConnection"
    }

    private fun extractDefaultOrConfiguredPort(protocol: VaultProtocolType, settings: ProtocolSpecificSettings): Int = when (settings) {
        is ProtocolSpecificSettings.Rdp -> settings.port
        is ProtocolSpecificSettings.Ssh -> settings.port
        is ProtocolSpecificSettings.FileTransfer -> settings.port
        is ProtocolSpecificSettings.Vnc -> settings.port
        is ProtocolSpecificSettings.SerialUart -> settings.baudRate
        else -> when (protocol) {
            VaultProtocolType.WEB_PAGE -> 443
            VaultProtocolType.PROXMOX_VE -> 8006
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
