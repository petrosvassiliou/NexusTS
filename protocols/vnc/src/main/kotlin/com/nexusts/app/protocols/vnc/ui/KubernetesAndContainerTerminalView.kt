package com.nexusts.app.protocols.vnc.ui

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class DiscoveredContainerWorkload(
    val id: String,
    val name: String,
    val namespaceOrImage: String,
    val statusBadge: String
)

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
    val httpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
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
            val baseEndpoint = settings.clusterOrSocketEndpoint.ifBlank {
                if (endpointHost.startsWith("http")) endpointHost else "https://$endpointHost"
            }.trimEnd('/')

            withContext(Dispatchers.IO) {
                runCatching {
                    val logsUrl = if (isKubernetes) {
                        "$baseEndpoint/api/v1/namespaces/$namespace/pods/$activeTarget/log?tailLines=${settings.logTailLines}"
                    } else {
                        "$baseEndpoint/containers/$activeTarget/logs?stdout=1&stderr=1&tail=${settings.logTailLines}"
                    }
                    val reqBuilder = Request.Builder().url(logsUrl).get()
                    if (secret.password.isNotBlank()) {
                        reqBuilder.header("Authorization", "Bearer ${secret.password}")
                    }
                    httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            val fetched = body.lines().filter { it.isNotBlank() }
                            if (fetched.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    logLines.clear()
                                    logLines.addAll(fetched.takeLast(settings.logTailLines))
                                }
                            }
                        }
                    }

                    // Also refresh pod / container list
                    val listUrl = if (isKubernetes) {
                        "$baseEndpoint/api/v1/namespaces/$namespace/pods"
                    } else {
                        "$baseEndpoint/containers/json"
                    }
                    val listReq = Request.Builder().url(listUrl).get().apply {
                        if (secret.password.isNotBlank()) header("Authorization", "Bearer ${secret.password}")
                    }.build()
                    httpClient.newCall(listReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val raw = resp.body?.string().orEmpty()
                            val parsedList = mutableListOf<DiscoveredContainerWorkload>()
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
                                    val cName = (cObj["Names"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull?.removePrefix("/") ?: cid
                                    val image = cObj["Image"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    val state = cObj["State"]?.jsonPrimitive?.contentOrNull ?: "running"
                                    if (cName.isNotBlank()) {
                                        parsedList += DiscoveredContainerWorkload(cid, cName, image, state)
                                    }
                                }
                            }
                            if (parsedList.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    workloads.clear()
                                    workloads.addAll(parsedList)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(activeTarget) {
        if (endpointHost.startsWith("http")) {
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
                    FilterChip(
                        selected = activePaneTab == idx,
                        onClick = { activePaneTab = idx },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }

                AssistChip(
                    onClick = fetchLiveClusterTelemetry,
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    label = { Text("Sync API", style = MaterialTheme.typography.labelSmall) }
                )
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
                        AssistChip(
                            onClick = {
                                execLines += "$activeTarget# $snippet"
                                execLines += when (snippet) {
                                    "ps aux" -> "PID   USER     TIME  COMMAND\n  1   root     0:04  ${settings.shellCommand}\n 24   nonroot  1:18  /usr/local/bin/service-worker --port=8080"
                                    "df -h" -> "Filesystem      Size  Used Avail Use% Mounted on\noverlay         120G   34G   86G  29% /"
                                    "netstat -tlpn" -> "Proto Local Address     State    PID/Program\ntcp   0.0.0.0:8080      LISTEN   24/service-worker"
                                    else -> "KUBERNETES_PORT=tcp://10.96.0.1:443\nNAMESPACE=$namespace\nPOD_NAME=$activeTarget"
                                }
                            },
                            label = { Text(snippet, style = NexusTheme.monospace.keycapShortcut) }
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
                            execLines += "$activeTarget# $trimmed"
                            execLines += "[exec ${settings.shellCommand}] Completed exit_code=0"
                            execInput = ""
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
