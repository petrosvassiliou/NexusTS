package com.nexusts.app.core.vault.error

import com.nexusts.app.core.vault.model.NxtsVaultDocument
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.regex.PatternSyntaxException
import java.util.zip.ZipException
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

enum class NexusErrorCategory {
    NETWORK_UNREACHABLE,
    AUTHENTICATION_FAILED,
    TLS_CERTIFICATE,
    PORT_CONFLICT,
    VAULT_CRYPTO_OR_CORRUPTION,
    IMPORT_EXPORT_FORMAT,
    CLOUD_SYNC,
    VALIDATION,
    INTERNAL
}

data class NexusAppError(
    val id: String = UUID.randomUUID().toString(),
    val category: NexusErrorCategory,
    val title: String,
    val userMessage: String,
    val remediationHint: String,
    val technicalDetails: String = "",
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val recoveryConnectionId: String? = null
) {
    fun formatDiagnosticReport(): String = buildString {
        appendLine("NexusTS Diagnostic Report")
        appendLine("Category: ${category.name}")
        appendLine("Title: $title")
        appendLine("Summary: $userMessage")
        if (remediationHint.isNotBlank()) {
            appendLine("Suggested Fix: $remediationHint")
        }
        if (technicalDetails.isNotBlank()) {
            appendLine("Technical Details: $technicalDetails")
        }
        appendLine("TimestampEpochMs: $timestampEpochMs")
    }.trim()
}

object NexusErrorClassifier {

    fun classifyThrowable(
        throwable: Throwable,
        operationTitle: String,
        recoveryConnectionId: String? = null
    ): NexusAppError {
        val root = unwrapRootCause(throwable)
        val rawMsg = (throwable.message ?: root.message ?: root::class.java.simpleName)
            .trim()
            .ifBlank { root::class.java.simpleName }
        val lower = rawMsg.lowercase()
        val tech = buildString {
            append("${root::class.java.name}: $rawMsg")
            if (root !== throwable && !throwable.message.isNullOrBlank() && throwable.message != root.message) {
                append(" (outer: ${throwable::class.java.simpleName}: ${throwable.message})")
            }
        }

        return when {
            root is AEADBadTagException ||
                lower.contains("authentication tag") ||
                lower.contains("wrong password") ||
                lower.contains("invalid passphrase") ||
                lower.contains("mac check in gcm failed") -> NexusAppError(
                category = NexusErrorCategory.VAULT_CRYPTO_OR_CORRUPTION,
                title = operationTitle,
                userMessage = "Decryption failed because the passphrase/password is incorrect or the encrypted container was modified.",
                remediationHint = "Double-check your master passphrase or archive password and try again.",
                technicalDetails = tech,
                recoveryConnectionId = recoveryConnectionId
            )

            root is UnknownHostException ||
                lower.contains("nodename nor servname") ||
                lower.contains("name or service not known") ||
                lower.contains("dns") && lower.contains("failed") -> NexusAppError(
                category = NexusErrorCategory.NETWORK_UNREACHABLE,
                title = operationTitle,
                userMessage = "Could not resolve the server hostname.",
                remediationHint = "Verify the hostname/FQDN spelling, VPN/DNS connectivity, or use an IP address.",
                technicalDetails = tech,
                recoveryConnectionId = recoveryConnectionId
            )

            root is SocketTimeoutException ||
                root is ConnectException ||
                root is NoRouteToHostException ||
                lower.contains("connection refused") ||
                lower.contains("timed out") ||
                lower.contains("network is unreachable") -> NexusAppError(
                category = NexusErrorCategory.NETWORK_UNREACHABLE,
                title = operationTitle,
                userMessage = "The target host could not be reached over the network.",
                remediationHint = "Confirm the host is powered on, check the port number and firewall rules, or verify your VPN / Secure Gateway tunnel.",
                technicalDetails = tech,
                recoveryConnectionId = recoveryConnectionId
            )

            root is SSLHandshakeException ||
                root is SSLException ||
                lower.contains("certificate") && (lower.contains("verify") || lower.contains("untrusted") || lower.contains("rejected")) -> NexusAppError(
                category = NexusErrorCategory.TLS_CERTIFICATE,
                title = operationTitle,
                userMessage = "TLS/SSL certificate verification failed for the remote endpoint.",
                remediationHint = "Install or trust the server's CA certificate, or enable 'Ignore Certificate Warnings' in the connection settings for self-signed lab hosts.",
                technicalDetails = tech,
                recoveryConnectionId = recoveryConnectionId
            )

            lower.contains("address already in use") || lower.contains("eaddrinuse") -> NexusAppError(
                category = NexusErrorCategory.PORT_CONFLICT,
                title = operationTitle,
                userMessage = "Local port is already in use by another session or application.",
                remediationHint = "Choose an unused local bind port in the connection's Port Forwarding settings or close the conflicting session.",
                technicalDetails = tech,
                recoveryConnectionId = recoveryConnectionId
            )

            lower.contains("auth") && (lower.contains("fail") || lower.contains("denied") || lower.contains("rejected")) ||
                lower.contains("permission denied") ||
                lower.contains("logon_failed") ||
                lower.contains("nla") && lower.contains("fail") -> NexusAppError(
                category = NexusErrorCategory.AUTHENTICATION_FAILED,
                title = operationTitle,
                userMessage = "Authentication was rejected by the remote host.",
                remediationHint = "Verify the assigned credential's username, domain, password, or SSH private key.",
                technicalDetails = tech,
                recoveryConnectionId = recoveryConnectionId
            )

            root is PatternSyntaxException -> NexusAppError(
                category = NexusErrorCategory.VALIDATION,
                title = operationTitle,
                userMessage = "Invalid regular expression pattern.",
                remediationHint = "Ensure all brackets '[...]' and parentheses '(...)' are balanced in the regex filter.",
                technicalDetails = tech,
                recoveryConnectionId = recoveryConnectionId
            )

            root is ZipException ||
                root is EOFException ||
                lower.contains("xml") ||
                lower.contains("corrupt") ||
                lower.contains("unsupported") ||
                lower.contains("format") -> NexusAppError(
                category = NexusErrorCategory.IMPORT_EXPORT_FORMAT,
                title = operationTitle,
                userMessage = rawMsg,
                remediationHint = "Verify that the selected file is a valid, uncorrupted .nxts, .rtsz, .rtsx, .rjson, .kdbx, Bitwarden, 1Password, or CSV export.",
                technicalDetails = tech,
                recoveryConnectionId = recoveryConnectionId
            )

            lower.contains("oauth") || lower.contains("google drive") || lower.contains("saf") -> NexusAppError(
                category = NexusErrorCategory.CLOUD_SYNC,
                title = operationTitle,
                userMessage = rawMsg,
                remediationHint = "Check network connectivity or re-link the Google Drive vault file in Cloud Sync Studio.",
                technicalDetails = tech,
                recoveryConnectionId = recoveryConnectionId
            )

            else -> NexusAppError(
                category = NexusErrorCategory.INTERNAL,
                title = operationTitle,
                userMessage = rawMsg,
                remediationHint = "Review the technical details below or retry the operation.",
                technicalDetails = tech,
                recoveryConnectionId = recoveryConnectionId
            )
        }
    }

    fun classifyRdpError(
        host: String,
        port: Int,
        rawNativeError: String,
        connectionId: String? = null
    ): NexusAppError {
        val cleaned = rawNativeError.trim()
        val upper = cleaned.uppercase()
        return when {
            cleaned.isEmpty() -> NexusAppError(
                category = NexusErrorCategory.NETWORK_UNREACHABLE,
                title = "RDP Connection Failed ($host:$port)",
                userMessage = "Could not establish an RDP session with $host:$port.",
                remediationHint = "Verify the host/IP, TCP port $port, assigned credentials, and NLA / TLS certificate settings.",
                technicalDetails = "FreeRDP nativeConnect returned false ($host:$port)",
                recoveryConnectionId = connectionId
            )

            upper.startsWith("INTERNAL:") ||
                upper.contains("FREERDP_CONTEXT_NEW") ||
                upper.contains("FREERDP_NEW") ||
                upper.contains("GDI_INIT") -> NexusAppError(
                category = NexusErrorCategory.INTERNAL,
                title = "RDP Native Engine Error ($host:$port)",
                userMessage = "The local FreeRDP 3.x session engine failed to initialize.",
                remediationHint = "Close and reopen the session tab or restart NexusTS. Technical diagnostics are included below.",
                technicalDetails = cleaned,
                recoveryConnectionId = connectionId
            )

            upper.contains("DNS_NAME_NOT_FOUND") || upper.contains("NAME_NOT_FOUND") -> NexusAppError(
                category = NexusErrorCategory.NETWORK_UNREACHABLE,
                title = "Hostname Not Found ($host)",
                userMessage = "DNS lookup failed for '$host'.",
                remediationHint = "Verify the hostname spelling, DNS/VPN configuration, or connect via IP address.",
                technicalDetails = cleaned,
                recoveryConnectionId = connectionId
            )

            upper.contains("HYBRID_REQUIRED") -> NexusAppError(
                category = NexusErrorCategory.AUTHENTICATION_FAILED,
                title = "Windows NLA Credentials Required ($host)",
                userMessage = "This Windows host requires Network Level Authentication (NLA / CredSSP) before displaying the desktop.",
                remediationHint = "Assign a username and password credential to this connection (or parent folder) and click Reconnect.",
                technicalDetails = cleaned,
                recoveryConnectionId = connectionId
            )

            upper.contains("AUTHENTICATION_FAILED") ||
                upper.contains("LOGON_FAILURE") ||
                upper.contains("WRONG_PASSWORD") ||
                upper.contains("STATUS_LOGON_FAILURE") ||
                upper.contains("SEC_E_INVALID_TOKEN") -> NexusAppError(
                category = NexusErrorCategory.AUTHENTICATION_FAILED,
                title = "RDP Authentication Failed ($host)",
                userMessage = "Windows / NLA (CredSSP) rejected the username, domain, or password.",
                remediationHint = "Edit the connection or its assigned credential in the Credentials folder, or verify DOMAIN\\username format and NLA settings.",
                technicalDetails = cleaned,
                recoveryConnectionId = connectionId
            )

            upper.contains("ACCOUNT_LOCKED_OUT") || upper.contains("ACCOUNT_DISABLED") || upper.contains("PASSWORD_EXPIRED") -> NexusAppError(
                category = NexusErrorCategory.AUTHENTICATION_FAILED,
                title = "Remote Account Restricted ($host)",
                userMessage = "The Windows account is locked out, disabled, or its password has expired.",
                remediationHint = "Unlock or reset the Windows / Active Directory account before reconnecting.",
                technicalDetails = cleaned,
                recoveryConnectionId = connectionId
            )

            upper.contains("TLS_CERTIFICATE_CHANGED") -> NexusAppError(
                category = NexusErrorCategory.TLS_CERTIFICATE,
                title = "RDP Host Certificate Changed ($host)",
                userMessage = "The X.509 certificate fingerprint presented by $host:$port changed since a previous connection.",
                remediationHint = "Verify that the Windows server certificate was intentionally rotated (no MITM), or enable 'Ignore Certificate Warnings' in RDP Security settings to accept the rotated certificate.",
                technicalDetails = cleaned,
                recoveryConnectionId = connectionId
            )

            upper.contains("TLS_CONNECT_FAILED") || upper.contains("CERT") || upper.contains("SSL") -> NexusAppError(
                category = NexusErrorCategory.TLS_CERTIFICATE,
                title = "TLS / Certificate Handshake Rejected ($host)",
                userMessage = "The RDP server's TLS handshake or certificate could not be verified.",
                remediationHint = "Enable 'Ignore Certificate Warnings' in the connection's RDP Security settings if this host uses a custom/legacy certificate.",
                technicalDetails = cleaned,
                recoveryConnectionId = connectionId
            )

            upper.contains("ERRCONNECT_CONNECT_TRANSPORT_FAILED") -> NexusAppError(
                category = NexusErrorCategory.NETWORK_UNREACHABLE,
                title = "RDP Transport Negotiation Dropped ($host:$port)",
                userMessage = "The RDP server closed the connection during security or capability activation.",
                remediationHint = "Verify NLA / TLS settings, ensure the account has Remote Desktop log-on rights on $host, or toggle 'Ignore Certificate Warnings' in RDP settings.",
                technicalDetails = cleaned,
                recoveryConnectionId = connectionId
            )

            upper.contains("ERRCONNECT_CONNECT_FAILED") ||
                upper.contains("CONNECT_TRANSPORT_FAILED") ||
                upper.contains("CONNECT_CANCELLED") ||
                upper.contains("MCS_CONNECT_INITIAL_ERROR") ||
                upper.contains("PEER_DISCONNECTED") -> NexusAppError(
                category = NexusErrorCategory.NETWORK_UNREACHABLE,
                title = "Host Unreachable ($host:$port)",
                userMessage = "TCP connection to $host:$port failed or timed out.",
                remediationHint = "Ensure Remote Desktop is enabled on the target host, port $port is reachable, and your VPN/Gateway is active.",
                technicalDetails = cleaned,
                recoveryConnectionId = connectionId
            )

            else -> NexusAppError(
                category = NexusErrorCategory.NETWORK_UNREACHABLE,
                title = "RDP Session Disconnected ($host:$port)",
                userMessage = "The remote desktop session ended with an error.",
                remediationHint = "Check the FreeRDP diagnostic code below, adjust RDP settings or credentials, and click Reconnect.",
                technicalDetails = cleaned,
                recoveryConnectionId = connectionId
            )
        }
    }

    private fun unwrapRootCause(t: Throwable): Throwable {
        var cur = t
        val seen = mutableSetOf<Throwable>()
        while (cur.cause != null && cur.cause !== cur && seen.add(cur)) {
            cur = cur.cause!!
        }
        return cur
    }
}

object VaultHierarchySanitizer {

    const val SYSTEM_CREDENTIALS_FOLDER_ID: String = "__NEXUSTS_SYSTEM_CREDENTIALS__"

    val KNOWN_STARTER_DEMO_IDS: Set<String> = setOf(
        // Android / Googlebook OS legacy demo IDs (v1.1.0 & v1.1.1)
        "folder-prod",
        "folder-eu-core",
        "folder-lab",
        "folder-win-ad",
        "folder-hybrid-cloud",
        "conn-win-dc01",
        "conn-k8s-bastion",
        "conn-sftp-vault",
        "conn-proxmox-ve",
        "conn-win-rds-01",
        "conn-win-rds-02",
        "conn-k8s-bastion-ssh",
        "conn-prod-sftp",
        "conn-macos-ard",
        "conn-net-scanner",
        "conn-serial-switch-01",
        "conn-k8s-pod-exec-01",
        "cred-domain-admin",
        "cred-ops-ed25519",
        "cred-ops-root",
        "cred-corp-domain",
        "gw-eu-zero-trust",
        "gw-eu-bastion",
        "smart-linux-prod",
        "smart-all-rdp",
        "dyn-cloud-fleet",
        "tpl-hardened-rdp",
        "tpl-bastion-ssh",
        "tpl-win-console-hidpi",
        "task-apt-sec",
        "task-k8s-pods",
        "macro-win-elevate",
        "todo-rotate-ssh",
        "todo-audit-rdp",
        "todo-cert-rotate",
        "todo-k8s-etcd",
        // macOS sampleStarterVault IDs
        "folder-prod-core",
        "folder-homelab",
        "conn-bastion-01",
        "conn-dc-01",
        "conn-pve-01",
        "conn-mac-build-01",
        "cred-prod-ops",
        "smart-prod-all",
        "tpl-hardened-ssh",
        "task-fleet-health"
    )

    private val KNOWN_STARTER_SMART_FOLDER_NAMES: Set<String> = setOf(
        "All Production Linux Nodes",
        "All Remote Desktop (RDP/VNC)",
        "All Production Hosts"
    )

    private val KNOWN_STARTER_TASK_NAMES: Set<String> = setOf(
        "Cluster Pod Health Audit",
        "System Health & Uptime Snapshot"
    )

    private val KNOWN_STARTER_MACRO_NAMES: Set<String> = setOf(
        "Windows Domain Elevated Sign-In + 2FA Macro"
    )

    private val KNOWN_STARTER_TODO_TITLES: Set<String> = setOf(
        "Enable Kerberos ticket forwarding on EU Bastion",
        "Rotate Kerberos krbtgt keys during Sunday maintenance window"
    )

    fun isReservedCredentialsFolderName(name: String): Boolean =
        name.trim().equals("Credentials", ignoreCase = true) ||
            name.trim().equals("Anmeldeinformationen", ignoreCase = true)

    /**
     * Strips any leftover starter/demo objects across all platforms and absorbs any empty or
     * credential-only `VaultFolder` named `"Credentials"` into the bottom-pinned system
     * Credentials container so `"Credentials"` never appears as a regular connection folder.
     */
    fun sanitizeVaultDocument(doc: NxtsVaultDocument): NxtsVaultDocument {
        val activeConnsWithoutDemo = doc.connections.filterNot { it.id in KNOWN_STARTER_DEMO_IDS }
        val userReferencedFolderIds = activeConnsWithoutDemo.mapNotNull { it.parentFolderId }.toSet()
        val activeFoldersWithoutDemo = doc.folders.filterNot {
            it.id in KNOWN_STARTER_DEMO_IDS && it.id !in userReferencedFolderIds
        }

        // Identify any root folder named "Credentials" (and its empty subfolders) that holds 0 connections
        val folderChildrenMap = activeFoldersWithoutDemo.groupBy { it.parentFolderId }
        val connsByFolder = activeConnsWithoutDemo.groupBy { it.parentFolderId }

        fun subtreeHasAnyConnections(folderId: String, visited: MutableSet<String> = mutableSetOf()): Boolean {
            if (!visited.add(folderId)) return false
            if (!connsByFolder[folderId].isNullOrEmpty()) return true
            return folderChildrenMap[folderId].orEmpty().any { subtreeHasAnyConnections(it.id, visited) }
        }

        fun collectSubtreeIds(folderId: String, out: MutableSet<String>) {
            if (!out.add(folderId)) return
            folderChildrenMap[folderId].orEmpty().forEach { collectSubtreeIds(it.id, out) }
        }

        val absorbedCredentialFolderIds = mutableSetOf<String>()
        for (folder in activeFoldersWithoutDemo) {
            if (folder.parentFolderId == null &&
                isReservedCredentialsFolderName(folder.name) &&
                !subtreeHasAnyConnections(folder.id)
            ) {
                collectSubtreeIds(folder.id, absorbedCredentialFolderIds)
            }
        }

        val cleanedFolders = activeFoldersWithoutDemo.filterNot {
            it.id in absorbedCredentialFolderIds || it.id == SYSTEM_CREDENTIALS_FOLDER_ID
        }
        val validFolderIds = cleanedFolders.map { it.id }.toSet()

        val cleanedConnections = activeConnsWithoutDemo.map { conn ->
            val normalizedParent = if (conn.parentFolderId != null && conn.parentFolderId !in validFolderIds) {
                null
            } else {
                conn.parentFolderId
            }
            val healedSettings = when (val s = conn.settings) {
                is com.nexusts.app.core.vault.model.ProtocolSpecificSettings.Rdp -> {
                    val healClip = !s.redirectClipboard && !s.redirectClipboardCustomized
                    val healHv = s.hyperVVmId.isNotBlank() && !s.hyperVEnhancedSession && !s.hyperVEnhancedSessionCustomized
                    if (healClip || healHv) {
                        s.copy(
                            redirectClipboard = if (healClip) true else s.redirectClipboard,
                            hyperVEnhancedSession = if (healHv) true else s.hyperVEnhancedSession
                        )
                    } else {
                        s
                    }
                }
                else -> s
            }
            if (normalizedParent != conn.parentFolderId || healedSettings != conn.settings) {
                conn.copy(parentFolderId = normalizedParent, settings = healedSettings)
            } else {
                conn
            }
        }

        val cleanedCredentials = doc.credentials
            .filterNot { it.id in KNOWN_STARTER_DEMO_IDS }
            .map { cred ->
                if (cred.parentFolderId != null) {
                    cred.copy(parentFolderId = null)
                } else {
                    cred
                }
            }

        val cleanedSmartFolders = doc.smartFolders.filterNot {
            it.id in KNOWN_STARTER_DEMO_IDS || it.name in KNOWN_STARTER_SMART_FOLDER_NAMES
        }
        val cleanedDynamicFolders = doc.dynamicFolders.filterNot { it.id in KNOWN_STARTER_DEMO_IDS }
        val cleanedGateways = doc.gateways.filterNot { it.id in KNOWN_STARTER_DEMO_IDS }
        val cleanedTasks = doc.commandTasks.filterNot {
            it.id in KNOWN_STARTER_DEMO_IDS || it.name in KNOWN_STARTER_TASK_NAMES
        }
        val cleanedMacros = doc.keySequenceTasks.filterNot {
            it.id in KNOWN_STARTER_DEMO_IDS || it.name in KNOWN_STARTER_MACRO_NAMES
        }
        val cleanedTemplates = doc.templates.filterNot { it.id in KNOWN_STARTER_DEMO_IDS }
        val cleanedTodos = doc.todoItems.filterNot {
            it.id in KNOWN_STARTER_DEMO_IDS || it.title in KNOWN_STARTER_TODO_TITLES
        }
        val validConnIds = cleanedConnections.map { it.id }.toSet()
        val cleanedRecents = doc.recentConnectionIds.filter { it in validConnIds }

        val cleanDocId = if (doc.documentId == "nxts-enterprise-core") "nxts-default-vault" else doc.documentId
        val cleanName = if (doc.name == "Production Infrastructure" || doc.documentId == "nxts-enterprise-core") {
            "NexusTS Vault"
        } else {
            doc.name
        }

        val changed = cleanDocId != doc.documentId ||
            cleanName != doc.name ||
            cleanedFolders.size != doc.folders.size ||
            cleanedConnections != doc.connections ||
            cleanedCredentials != doc.credentials ||
            cleanedSmartFolders.size != doc.smartFolders.size ||
            cleanedDynamicFolders.size != doc.dynamicFolders.size ||
            cleanedGateways.size != doc.gateways.size ||
            cleanedTasks.size != doc.commandTasks.size ||
            cleanedMacros.size != doc.keySequenceTasks.size ||
            cleanedTemplates.size != doc.templates.size ||
            cleanedTodos.size != doc.todoItems.size ||
            cleanedRecents != doc.recentConnectionIds

        if (!changed) return doc

        return doc.copy(
            documentId = cleanDocId,
            name = cleanName,
            folders = cleanedFolders,
            smartFolders = cleanedSmartFolders,
            dynamicFolders = cleanedDynamicFolders,
            connections = cleanedConnections,
            credentials = cleanedCredentials,
            gateways = cleanedGateways,
            commandTasks = cleanedTasks,
            keySequenceTasks = cleanedMacros,
            templates = cleanedTemplates,
            todoItems = cleanedTodos,
            recentConnectionIds = cleanedRecents
        )
    }
}
