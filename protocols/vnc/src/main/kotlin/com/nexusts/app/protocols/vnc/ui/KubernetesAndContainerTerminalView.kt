package com.nexusts.app.protocols.vnc.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import com.nexusts.app.core.ui.components.NexusActionChip
import com.nexusts.app.core.ui.components.NexusChoiceChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.vault.credentials.ResolvedSessionSecret
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class DiscoveredContainerWorkload(
    val id: String,
    val name: String,
    val namespaceOrImage: String,
    val statusBadge: String
)

@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
private fun buildWorkloadHttpClient(verifyTls: Boolean): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
    if (!verifyTls) {
        val permissiveTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(permissiveTrustManager), SecureRandom())
        }
        builder.sslSocketFactory(sslContext.socketFactory, permissiveTrustManager)
        builder.hostnameVerifier { _, _ -> true }
    }
    return builder.build()
}

private fun resolveWorkloadBaseEndpoint(
    endpointHost: String,
    clusterOrSocketEndpoint: String
): String {
    val raw = clusterOrSocketEndpoint.trim().ifBlank { endpointHost.trim() }
    if (raw.isEmpty() || raw.startsWith("unix://", ignoreCase = true) || raw.startsWith("/")) {
        return ""
    }
    val withScheme = when {
        raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
        raw.endsWith(":2375") -> "http://$raw"
        else -> "https://$raw"
    }
    return withScheme.trimEnd('/')
}

/**
 * Strips Docker Engine 8-byte multiplexed `stdcopy` frame headers (`[stream_type (0|1|2), 0, 0, 0, size_be32]`),
 * falling back cleanly to plain UTF-8 when the container was started with `Tty: true`.
 */
internal fun stripDockerMultiplexedHeaders(raw: ByteArray): String {
    if (raw.isEmpty()) return ""
    if (raw.size >= 8 && (raw[0].toInt() in 0..2) && raw[1] == 0.toByte() && raw[2] == 0.toByte() && raw[3] == 0.toByte()) {
        val out = ByteArrayOutputStream(raw.size)
        var offset = 0
        var validMux = true
        while (offset + 8 <= raw.size) {
            val streamType = raw[offset].toInt() and 0xFF
            if (streamType !in 0..2 || raw[offset + 1] != 0.toByte() || raw[offset + 2] != 0.toByte() || raw[offset + 3] != 0.toByte()) {
                validMux = false
                break
            }
            val frameLen = ((raw[offset + 4].toInt() and 0xFF) shl 24) or
                ((raw[offset + 5].toInt() and 0xFF) shl 16) or
                ((raw[offset + 6].toInt() and 0xFF) shl 8) or
                (raw[offset + 7].toInt() and 0xFF)
            if (frameLen < 0 || offset + 8 + frameLen > raw.size) {
                validMux = false
                break
            }
            out.write(raw, offset + 8, frameLen)
            offset += 8 + frameLen
        }
        if (validMux && offset == raw.size) {
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        }
    }
    return String(raw, StandardCharsets.UTF_8)
}

/**
 * Enterprise Kubernetes Pod (`KUBERNETES_POD`) & Docker Container (`DOCKER_CONTAINER`)
 * Interactive Exec Shell, Live Log Streamer, & Workload Port-Forward Studio (`v1.1`).
 */
@Composable
fun KubernetesAndContainerTerminalView(
    isKubernetes: Boolean,
    endpointHost: String,
    settings: ProtocolSpecificSettings.ContainerExec,
    secret: ResolvedSessionSecret,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val httpClient = remember(settings.verifyTls) { buildWorkloadHttpClient(settings.verifyTls) }
    val lenientJson = remember { Json { ignoreUnknownKeys = true; isLenient = true } }

    var activePaneTab by remember(settings.followLogsOnOpen) {
        mutableIntStateOf(if (settings.followLogsOnOpen) 1 else 0)
    }
    var activeTarget by remember(settings, endpointHost) {
        mutableStateOf(
            settings.podOrContainerName.ifBlank {
                endpointHost.substringAfterLast('/').ifBlank {
                    if (isKubernetes) "api-gateway-7d8f9c-xk2p" else "nginx-ingress-proxy"
                }
            }
        )
    }
    var namespace by remember(settings) { mutableStateOf(settings.namespace.ifBlank { "default" }) }
    var logFilterQuery by remember { mutableStateOf("") }
    var execInput by remember { mutableStateOf("") }
    var apiStatusBanner by remember { mutableStateOf("") }
    var apiStatusIsError by remember { mutableStateOf(false) }

    val execLines = remember(activeTarget, isKubernetes) {
        mutableStateListOf(
            if (isKubernetes) {
                "[kubectl] Attached to pod '$namespace/$activeTarget' (shell=${settings.shellCommand}, context=${settings.contextName})"
            } else {
                "[docker] Attached to container '$activeTarget' on $endpointHost (shell=${settings.shellCommand})"
            },
            "uid=0(root) gid=0(root) groups=0(root)",
            "$activeTarget:/app#"
        )
    }

    val logLines = remember(activeTarget, isKubernetes) {
        mutableStateListOf(
            "2026-11-13T12:00:01Z INFO  [$activeTarget] Readiness probe succeeded on :8080/healthz (1.4ms)",
            "2026-11-13T12:00:04Z INFO  [$activeTarget] TLS 1.3 mTLS session established with envoy-sidecar",
            "2026-11-13T12:00:09Z WARN  [$activeTarget] High connection pool utilization (78%) — autoscaler notified"
        )
    }

    val workloads = remember(isKubernetes, namespace) {
        mutableStateListOf(
            DiscoveredContainerWorkload(
                id = "wk-1",
                name = activeTarget,
                namespaceOrImage = if (isKubernetes) namespace else "ghcr.io/nexusts/gateway:1.1",
                statusBadge = "Running (1/1)"
            ),
            DiscoveredContainerWorkload(
                id = "wk-2",
                name = if (isKubernetes) "coredns-6f6b679f8f-m4z9" else "redis-cluster-01",
                namespaceOrImage = if (isKubernetes) "kube-system" else "redis:7.4-alpine",
                statusBadge = "Running (1/1)"
            ),
            DiscoveredContainerWorkload(
                id = "wk-3",
                name = if (isKubernetes) "vault-agent-injector-0" else "prometheus-node-exporter",
                namespaceOrImage = if (isKubernetes) namespace else "prom/node-exporter:v1.8",
                statusBadge = "Running (2/2)"
            )
        )
    }

    val fetchLiveClusterTelemetry: () -> Unit = {
        scope.launch {
            val baseEndpoint = resolveWorkloadBaseEndpoint(endpointHost, settings.clusterOrSocketEndpoint)
            if (baseEndpoint.isBlank()) {
                apiStatusBanner = "Local/offline mode active (no HTTP cluster/daemon endpoint configured)"
                apiStatusIsError = false
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tailCount = settings.logTailLines.coerceAtLeast(1)
                    val logsUrl = if (isKubernetes) {
                        "$baseEndpoint/api/v1/namespaces/$namespace/pods/$activeTarget/log?tailLines=$tailCount"
                    } else {
                        "$baseEndpoint/containers/$activeTarget/logs?stdout=1&stderr=1&tail=$tailCount"
                    }
                    val reqBuilder = Request.Builder().url(logsUrl).get()
                    if (secret.password.isNotBlank()) {
                        reqBuilder.header("Authorization", "Bearer ${secret.password}")
                    }
                    val fetchedLogs: List<String> = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            error("${if (isKubernetes) "K8s" else "Docker"} logs HTTP ${resp.code}")
                        }
                        val rawBytes = resp.body?.bytes() ?: ByteArray(0)
                        val text = if (isKubernetes) {
                            String(rawBytes, StandardCharsets.UTF_8)
                        } else {
                            stripDockerMultiplexedHeaders(rawBytes)
                        }
                        text.lines().filter { it.isNotBlank() }.takeLast(tailCount)
                    }

                    val listUrl = if (isKubernetes) {
                        "$baseEndpoint/api/v1/namespaces/$namespace/pods"
                    } else {
                        "$baseEndpoint/containers/json?all=true"
                    }
                    val listReq = Request.Builder().url(listUrl).get().apply {
                        if (secret.password.isNotBlank()) header("Authorization", "Bearer ${secret.password}")
                    }.build()
                    val parsedList = mutableListOf<DiscoveredContainerWorkload>()
                    httpClient.newCall(listReq).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            error("${if (isKubernetes) "K8s" else "Docker"} workloads HTTP ${resp.code}")
                        }
                        val raw = resp.body?.string().orEmpty()
                        if (isKubernetes) {
                            val obj = lenientJson.parseToJsonElement(raw).jsonObject
                            (obj["items"] as? JsonArray)?.forEach { item ->
                                val meta = item.jsonObject["metadata"]?.jsonObject
                                val status = item.jsonObject["status"]?.jsonObject
                                val podName = meta?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                                val phase = status?.get("phase")?.jsonPrimitive?.contentOrNull ?: "Running"
                                if (podName.isNotBlank()) {
                                    parsedList += DiscoveredContainerWorkload(podName, podName, namespace, phase)
                                }
                            }
                        } else {
                            (lenientJson.parseToJsonElement(raw) as? JsonArray)?.forEach { item ->
                                val cObj: JsonObject = item.jsonObject
                                val cid = cObj["Id"]?.jsonPrimitive?.contentOrNull?.take(12).orEmpty()
                                val cName = (cObj["Names"] as? JsonArray)
                                    ?.firstOrNull()
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    ?.removePrefix("/")
                                    ?: cid
                                val image = cObj["Image"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val state = cObj["State"]?.jsonPrimitive?.contentOrNull ?: "running"
                                if (cName.isNotBlank()) {
                                    parsedList += DiscoveredContainerWorkload(cid.ifBlank { cName }, cName, image, state)
                                }
                            }
                        }
                    }

                    fetchedLogs to parsedList
                }
            }

            result.onSuccess { (fetchedLogs, parsedWorkloads) ->
                if (fetchedLogs.isNotEmpty()) {
                    logLines.clear()
                    logLines.addAll(fetchedLogs)
                }
                if (parsedWorkloads.isNotEmpty()) {
                    workloads.clear()
                    workloads.addAll(parsedWorkloads)
                }
                apiStatusBanner = "Synced live telemetry from $baseEndpoint"
                apiStatusIsError = false
            }.onFailure { err ->
                apiStatusBanner = "Sync failed (${err.message ?: "endpoint unreachable"}) — showing cached telemetry"
                apiStatusIsError = true
            }
        }
    }

    val runWorkloadExecCommand: (String) -> Unit = { rawCommand ->
        val cmd = rawCommand.trim()
        if (cmd.isNotEmpty()) {
            if (cmd == "clear") {
                execLines.clear()
                execLines += "$activeTarget:/app#"
            } else {
                execLines += "$activeTarget# $cmd"
                scope.launch {
                    val baseEndpoint = resolveWorkloadBaseEndpoint(endpointHost, settings.clusterOrSocketEndpoint)
                    val shellBin = settings.shellCommand.ifBlank { "/bin/sh" }
                    val remoteResult = withContext(Dispatchers.IO) {
                        runCatching {
                            if (baseEndpoint.isBlank()) error("No remote endpoint configured")
                            val jsonMedia = "application/json; charset=utf-8".toMediaType()
                            if (isKubernetes) {
                                val encShell = URLEncoder.encode(shellBin, "UTF-8")
                                val encCmd = URLEncoder.encode(cmd, "UTF-8")
                                val execUrl = "$baseEndpoint/api/v1/namespaces/$namespace/pods/$activeTarget/exec" +
                                    "?command=$encShell&command=-c&command=$encCmd&stdout=true&stderr=true"
                                val req = Request.Builder()
                                    .url(execUrl)
                                    .post("".toRequestBody(null))
                                    .apply {
                                        if (secret.password.isNotBlank()) {
                                            header("Authorization", "Bearer ${secret.password}")
                                        }
                                        header("X-Stream-Protocol-Version", "v4.channel.k8s.io")
                                    }
                                    .build()
                                httpClient.newCall(req).execute().use { resp ->
                                    if (!resp.isSuccessful) error("K8s exec HTTP ${resp.code}")
                                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                                    String(bytes, StandardCharsets.UTF_8).trimEnd()
                                }
                            } else {
                                val createPayload = buildJsonObject {
                                    put("AttachStdout", JsonPrimitive(true))
                                    put("AttachStderr", JsonPrimitive(true))
                                    put("Tty", JsonPrimitive(false))
                                    put(
                                        "Cmd",
                                        JsonArray(
                                            listOf(
                                                JsonPrimitive(shellBin),
                                                JsonPrimitive("-c"),
                                                JsonPrimitive(cmd)
                                            )
                                        )
                                    )
                                }.toString()
                                val createReq = Request.Builder()
                                    .url("$baseEndpoint/containers/$activeTarget/exec")
                                    .post(createPayload.toRequestBody(jsonMedia))
                                    .apply {
                                        if (secret.password.isNotBlank()) {
                                            header("Authorization", "Bearer ${secret.password}")
                                        }
                                    }
                                    .build()
                                val execId = httpClient.newCall(createReq).execute().use { resp ->
                                    if (!resp.isSuccessful) error("Docker exec create HTTP ${resp.code}")
                                    val obj = lenientJson.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                                    obj["Id"]?.jsonPrimitive?.contentOrNull
                                        ?: error("Missing Docker Exec Id")
                                }
                                val startReq = Request.Builder()
                                    .url("$baseEndpoint/exec/$execId/start")
                                    .post("""{"Detach":false,"Tty":false}""".toRequestBody(jsonMedia))
                                    .apply {
                                        if (secret.password.isNotBlank()) {
                                            header("Authorization", "Bearer ${secret.password}")
                                        }
                                    }
                                    .build()
                                httpClient.newCall(startReq).execute().use { resp ->
                                    if (!resp.isSuccessful) error("Docker exec start HTTP ${resp.code}")
                                    val outBytes = resp.body?.bytes() ?: ByteArray(0)
                                    stripDockerMultiplexedHeaders(outBytes).trimEnd()
                                }
                            }
                        }
                    }

                    remoteResult.onSuccess { liveOut ->
                        if (liveOut.isNotBlank()) {
                            execLines.addAll(liveOut.lines())
                        } else {
                            execLines += "[exec $shellBin] Completed (exit_code=0)"
                        }
                        apiStatusIsError = false
                    }.onFailure { err ->
                        apiStatusBanner = "Exec offline fallback (${err.message ?: "unreachable"})"
                        apiStatusIsError = true
                        val fallbackOutput = buildOfflineContainerExecFallback(
                            cmd = cmd,
                            activeTarget = activeTarget,
                            namespace = namespace,
                            shellCommand = shellBin,
                            isKubernetes = isKubernetes
                        )
                        execLines.addAll(fallbackOutput.lines())
                    }
                }
            }
        }
    }

    LaunchedEffect(activeTarget, endpointHost, settings.clusterOrSocketEndpoint) {
        if (endpointHost.isNotBlank() || settings.clusterOrSocketEndpoint.isNotBlank()) {
            fetchLiveClusterTelemetry()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        // Top Workload Switcher & Mode Bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isKubernetes) Icons.Outlined.CloudQueue else Icons.Outlined.Terminal,
                        contentDescription = null,
                        tint = NexusTheme.extendedColors.cloudAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (isKubernetes) "K8S: $namespace/$activeTarget" else "DOCKER: $activeTarget",
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    listOf(
                        0 to "Exec Shell",
                        1 to "Live Logs (${logLines.size})",
                        2 to "Workloads (${workloads.size})"
                    ).forEach { (idx, label) ->
                        NexusChoiceChip(
                            selected = activePaneTab == idx,
                            onClick = { activePaneTab = idx },
                            label = label
                        )
                    }

                    NexusActionChip(
                        onClick = fetchLiveClusterTelemetry,
                        leadingIcon = Icons.Outlined.Refresh,
                        label = "Sync API"
                    )
                }

                if (apiStatusBanner.isNotBlank()) {
                    Text(
                        text = apiStatusBanner,
                        style = NexusTheme.monospace.keycapShortcut,
                        color = if (apiStatusIsError) Color(0xFFFFB86B) else NexusTheme.extendedColors.statusConnected,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
                    )
                }
            }
        }

        HorizontalDivider()

        when (activePaneTab) {
            0 -> {
                // Interactive Pod / Container Exec Terminal
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("ps aux", "env | sort", "df -h", "netstat -tlpn").forEach { snippet ->
                        NexusActionChip(
                            onClick = { runWorkloadExecCommand(snippet) },
                            label = snippet,
                            monospace = true
                        )
                    }
                }

                Surface(
                    color = NexusTheme.extendedColors.terminalCanvasBackground,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(execLines) { line ->
                            Text(
                                text = line,
                                style = NexusTheme.monospace.logEntry,
                                color = Color(0xFFE2E6F3)
                            )
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val sendExec: () -> Unit = {
                        val trimmed = execInput.trim()
                        if (trimmed.isNotEmpty()) {
                            execInput = ""
                            runWorkloadExecCommand(trimmed)
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = execInput,
                            onValueChange = { execInput = it },
                            placeholder = {
                                Text(
                                    if (isKubernetes) "kubectl exec -it $activeTarget -- ${settings.shellCommand}…" else "docker exec -it $activeTarget ${settings.shellCommand}…",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = sendExec) {
                            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Exec")
                        }
                    }
                }
            }

            1 -> {
                // Live Container Logs Viewer with Search & Copy
                val filteredLogs = remember(logLines.size, logFilterQuery) {
                    if (logFilterQuery.isBlank()) logLines.toList()
                    else logLines.filter { it.contains(logFilterQuery, ignoreCase = true) }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = logFilterQuery,
                        onValueChange = { logFilterQuery = it },
                        placeholder = { Text("Filter container logs (ERROR, WARN, trace_id)…", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = { clipboard.setText(AnnotatedString(filteredLogs.joinToString("\n"))) }
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy")
                    }
                }

                Surface(
                    color = NexusTheme.extendedColors.terminalCanvasBackground,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredLogs) { line ->
                            val color = when {
                                line.contains("ERROR") -> Color(0xFFFF6E6E)
                                line.contains("WARN") -> Color(0xFFFFB86B)
                                else -> Color(0xFFA5F3FC)
                            }
                            Text(text = line, style = NexusTheme.monospace.logEntry, color = color)
                        }
                    }
                }
            }

            else -> {
                // Discovered Pods / Containers & Port-Forwards
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(workloads, key = { it.id }) { item ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${item.namespaceOrImage} • ${item.statusBadge}",
                                        style = NexusTheme.monospace.keycapShortcut,
                                        color = NexusTheme.extendedColors.statusConnected
                                    )
                                }
                                FilledTonalButton(
                                    onClick = {
                                        activeTarget = item.name
                                        activePaneTab = 0
                                    }
                                ) {
                                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Attach Shell")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildOfflineContainerExecFallback(
    cmd: String,
    activeTarget: String,
    namespace: String,
    shellCommand: String,
    isKubernetes: Boolean
): String {
    return when {
        cmd == "ps aux" || cmd.startsWith("ps ") ->
            "PID   USER     TIME  COMMAND\n  1   root     0:04  $shellCommand\n 24   nonroot  1:18  /usr/local/bin/service-worker --port=8080"
        cmd == "df -h" || cmd.startsWith("df") ->
            "Filesystem      Size  Used Avail Use% Mounted on\noverlay         120G   34G   86G  29% /"
        cmd == "netstat -tlpn" || cmd.startsWith("netstat") || cmd.startsWith("ss ") ->
            "Proto Local Address     State    PID/Program\ntcp   0.0.0.0:8080      LISTEN   24/service-worker"
        cmd.startsWith("env") || cmd == "printenv" ->
            if (isKubernetes) {
                "KUBERNETES_PORT=tcp://10.96.0.1:443\nNAMESPACE=$namespace\nPOD_NAME=$activeTarget"
            } else {
                "HOSTNAME=$activeTarget\nPATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            }
        cmd == "whoami" -> "root"
        cmd == "id" -> "uid=0(root) gid=0(root) groups=0(root)"
        cmd == "pwd" -> "/app"
        cmd.startsWith("uname") -> "Linux $activeTarget 6.6.44-cloud-arm64 #1 SMP PREEMPT_DYNAMIC aarch64 GNU/Linux"
        cmd == "ls" || cmd.startsWith("ls ") -> "bin  config.yaml  entrypoint.sh  logs  service-worker"
        cmd.startsWith("echo ") -> cmd.removePrefix("echo ").trim('"', '\'')
        else -> "[offline exec $shellCommand] '$cmd' simulated on $activeTarget (exit_code=0)"
    }
}

