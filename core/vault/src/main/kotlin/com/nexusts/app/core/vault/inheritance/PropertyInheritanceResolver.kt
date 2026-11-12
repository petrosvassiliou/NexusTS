package com.nexusts.app.core.vault.inheritance

import com.nexusts.app.core.vault.model.CommandTask
import com.nexusts.app.core.vault.model.DynamicFolder
import com.nexusts.app.core.vault.model.DynamicScriptInterpreter
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.SecureGatewayConfig
import com.nexusts.app.core.vault.model.SmartFolder
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

data class EffectiveConnectionProfile(
    val connection: VaultConnection,
    val folderBreadcrumb: List<String>,
    val effectiveCredential: VaultCredential?,
    val credentialSourceLabel: String,
    val promptForCredentialAtConnect: Boolean,
    val effectiveGateway: SecureGatewayConfig?,
    val gatewaySourceLabel: String,
    val effectiveEnvironmentBadge: String?,
    val effectivePreConnectTask: CommandTask?,
    val effectivePostDisconnectTask: CommandTask?
)

/**
 * Resolves cascading property inheritance from Document Root -> Ancestor Folders -> Connection,
 * evaluates Smart Folders dynamically, and hydrates Dynamic Folder inventories.
 */
class PropertyInheritanceResolver(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun resolveEffectiveProfile(
        document: NxtsVaultDocument,
        connection: VaultConnection
    ): EffectiveConnectionProfile {
        val folderIndex = document.folders.associateBy { it.id }
        val credentialIndex = document.credentials.associateBy { it.id }
        val gatewayIndex = document.gateways.associateBy { it.id }
        val taskIndex = document.commandTasks.associateBy { it.id }

        // Build ordered chain: Root Document -> Top Folder -> ... -> Immediate Parent -> Connection
        val ancestorFolders = mutableListOf<VaultFolder>()
        val visited = mutableSetOf<String>()
        var cursorId = connection.parentFolderId
        while (cursorId != null && visited.add(cursorId)) {
            val folder = folderIndex[cursorId] ?: break
            ancestorFolders.add(0, folder)
            cursorId = folder.parentFolderId
        }

        val chain: List<Pair<String, InheritableProperties>> = buildList {
            add(document.name to document.rootInheritable)
            ancestorFolders.forEach { add(it.name to it.inheritable) }
            add(connection.name to connection.inheritable)
        }

        // Cascade credential resolution
        var resolvedCredId: String? = null
        var credSourceLabel = "None (Direct)"
        var promptCredential = false
        for ((sourceName, props) in chain) {
            when (props.credentialMode) {
                InheritanceMode.INHERIT_FROM_PARENT -> Unit
                InheritanceMode.OVERRIDE_EXPLICIT -> {
                    resolvedCredId = props.explicitCredentialId
                    credSourceLabel = sourceName
                    promptCredential = false
                }
                InheritanceMode.NONE_DIRECT -> {
                    resolvedCredId = null
                    credSourceLabel = "$sourceName (Direct)"
                    promptCredential = false
                }
                InheritanceMode.PROMPT_AT_CONNECT -> {
                    resolvedCredId = null
                    credSourceLabel = "$sourceName (Interactive Prompt)"
                    promptCredential = true
                }
            }
        }

        // Cascade gateway resolution
        var resolvedGatewayId: String? = null
        var gatewaySourceLabel = "Direct Network"
        for ((sourceName, props) in chain) {
            when (props.gatewayMode) {
                InheritanceMode.INHERIT_FROM_PARENT -> Unit
                InheritanceMode.OVERRIDE_EXPLICIT -> {
                    resolvedGatewayId = props.explicitGatewayId
                    gatewaySourceLabel = sourceName
                }
                InheritanceMode.NONE_DIRECT, InheritanceMode.PROMPT_AT_CONNECT -> {
                    resolvedGatewayId = null
                    gatewaySourceLabel = "$sourceName (Direct)"
                }
            }
        }

        var resolvedEnv: String? = null
        var preTaskId: String? = null
        var postTaskId: String? = null
        for ((_, props) in chain) {
            if (!props.environmentBadge.isNullOrBlank()) resolvedEnv = props.environmentBadge
            if (!props.preConnectTaskId.isNullOrBlank()) preTaskId = props.preConnectTaskId
            if (!props.postDisconnectTaskId.isNullOrBlank()) postTaskId = props.postDisconnectTaskId
        }

        return EffectiveConnectionProfile(
            connection = connection,
            folderBreadcrumb = listOf(document.name) + ancestorFolders.map { it.name },
            effectiveCredential = resolvedCredId?.let { credentialIndex[it] },
            credentialSourceLabel = credSourceLabel,
            promptForCredentialAtConnect = promptCredential,
            effectiveGateway = resolvedGatewayId?.let { gatewayIndex[it] },
            gatewaySourceLabel = gatewaySourceLabel,
            effectiveEnvironmentBadge = resolvedEnv,
            effectivePreConnectTask = preTaskId?.let { taskIndex[it] },
            effectivePostDisconnectTask = postTaskId?.let { taskIndex[it] }
        )
    }

    /**
     * Evaluates a [SmartFolder] dynamically against all connections in [document].
     */
    fun evaluateSmartFolder(
        document: NxtsVaultDocument,
        smartFolder: SmartFolder
    ): List<VaultConnection> {
        val filter = smartFolder.filter
        val compiledRegex = filter.hostPatternRegex
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }

        return document.connections.filter { conn ->
            val tagsMatch = filter.includeTags.isEmpty() ||
                filter.includeTags.all { wanted -> conn.tags.any { it.equals(wanted, ignoreCase = true) } }
            val protoMatch = filter.includeProtocols.isEmpty() || conn.protocol in filter.includeProtocols
            val osMatch = filter.osFamilyFilter.isNullOrBlank() ||
                conn.osFamily.equals(filter.osFamilyFilter, ignoreCase = true)
            val hostMatch = compiledRegex == null ||
                compiledRegex.containsMatchIn(conn.host) ||
                compiledRegex.containsMatchIn(conn.name)
            val envMatch = filter.environmentFilter.isNullOrBlank() ||
                resolveEffectiveProfile(document, conn).effectiveEnvironmentBadge
                    .equals(filter.environmentFilter, ignoreCase = true)

            tagsMatch && protoMatch && osMatch && hostMatch && envMatch
        }
    }

    @Serializable
    data class DynamicInventoryPayload(
        val Objects: List<DynamicInventoryItem> = emptyList()
    )

    @Serializable
    data class DynamicInventoryItem(
        val Type: String,
        val Name: String,
        val ComputerName: String = "",
        val Port: Int? = null,
        val OS: String = "Linux",
        val Tags: List<String> = emptyList()
    )

    /**
     * Executes or fetches a [DynamicFolder] inventory (Royal TS compatible `{"Objects": [...]}` JSON schema)
     * and materializes child [VaultConnection] definitions inheriting from the DynamicFolder.
     */
    fun evaluateDynamicFolder(dynamicFolder: DynamicFolder): List<VaultConnection> {
        val rawJson = when (dynamicFolder.interpreter) {
            DynamicScriptInterpreter.JSON_HTTP_API -> {
                val payload = dynamicFolder.scriptOrEndpointPayload.trim()
                if (payload.startsWith("{")) {
                    payload
                } else {
                    val req = Request.Builder().url(payload).get().build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("DynamicFolder HTTP ${resp.code}")
                        resp.body?.string().orEmpty()
                    }
                }
            }
            DynamicScriptInterpreter.SHELL_SH -> runSandboxedProcess(
                listOf("/system/bin/sh", "-c", dynamicFolder.scriptOrEndpointPayload)
            )
            DynamicScriptInterpreter.PYTHON3 -> runSandboxedProcess(
                listOf("python3", "-c", dynamicFolder.scriptOrEndpointPayload)
            )
        }

        val parsed = json.decodeFromString(DynamicInventoryPayload.serializer(), rawJson)
        return parsed.Objects.mapIndexed { idx, obj ->
            val protocol = when (obj.Type.lowercase()) {
                "royalrdsconnection", "rdp" -> VaultProtocolType.RDP
                "royalsftpconnection", "sftp", "filetransfer" -> VaultProtocolType.SFTP
                "royalvncconnection", "vnc" -> VaultProtocolType.VNC
                "royalwebpageconnection", "web", "https" -> VaultProtocolType.WEB_PAGE
                else -> VaultProtocolType.SSH
            }
            val settings = when (protocol) {
                VaultProtocolType.RDP -> ProtocolSpecificSettings.Rdp(port = obj.Port ?: 3389)
                VaultProtocolType.SFTP -> ProtocolSpecificSettings.FileTransfer(port = obj.Port ?: 22)
                VaultProtocolType.VNC -> ProtocolSpecificSettings.Vnc(port = obj.Port ?: 5900)
                VaultProtocolType.WEB_PAGE -> ProtocolSpecificSettings.WebPage(url = obj.ComputerName)
                else -> ProtocolSpecificSettings.Ssh(port = obj.Port ?: 22)
            }
            VaultConnection(
                id = "dyn-${dynamicFolder.id}-$idx-${obj.Name.hashCode()}",
                parentFolderId = dynamicFolder.parentFolderId,
                name = obj.Name,
                host = obj.ComputerName,
                protocol = protocol,
                osFamily = obj.OS,
                tags = obj.Tags + "dynamic",
                inheritable = dynamicFolder.inheritable,
                settings = settings
            )
        }
    }

    private fun runSandboxedProcess(cmd: List<String>): String {
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("DynamicFolder script timed out after 15 seconds")
        }
        return process.inputStream.bufferedReader().use(BufferedReader::readText)
    }
}
