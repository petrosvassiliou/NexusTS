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
import com.nexusts.app.core.ui.components.NexusActionChip
import com.nexusts.app.core.ui.components.NexusChoiceChip
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
internal fun buildWorkloadHttpClient(verifyTls: Boolean): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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

internal fun resolveWorkloadBaseEndpoint(
    endpointHost: String,
    clusterOrSocketEndpoint: String
): String {
    val raw = clusterOrSocketEndpoint.trim().ifBlank { endpointHost.trim() }
    if (raw.isEmpty() || raw.startsWith("unix://", ignoreCase = true) || raw.startsWith("/")) {
        return ""
    }
    val withScheme = when {
        raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true) ||
            raw.startsWith("ws://", ignoreCase = true) ||
            raw.startsWith("wss://", ignoreCase = true) -> raw
        raw.endsWith(":2375") -> "http://$raw"
        else -> "https://$raw"
    }
    return withScheme.trimEnd('/')
}

/**
 * Kubernetes `v4.channel.k8s.io` WebSocket Exec Client supporting:
 * - Channel `0x00`: stdin
 * - Channel `0x01`: stdout
 * - Channel `0x02`: stderr
 * - Channel `0x03`: error / exit status JSON
 * - Channel `0x04`: PTY terminal resize (`{"Width":cols,"Height":rows}`)
 * - Multi-container pod targeting (`&container=<subContainerName>`)
 * - Self-signed cluster CA support (`verifyTls == false`)
 */
class KubernetesExecClient(
    private val verifyTls: Boolean = true,
    private val httpClientOverride: OkHttpClient? = null
) {
    private val httpClient: OkHttpClient = httpClientOverride ?: buildWorkloadHttpClient(verifyTls)

    data class ExecResult(
        val stdout: String,
        val stderr: String,
        val statusJson: String,
        val negotiatedSubprotocol: String
    )

    class ExecSession internal constructor(
        private val webSocket: WebSocket,
        private val openLatch: CountDownLatch,
        private val closeLatch: CountDownLatch,
        private val failureRef: AtomicReference<Throwable?>,
        private val subprotocolRef: AtomicReference<String>
    ) : Closeable {

        val negotiatedSubprotocol: String
            get() = subprotocolRef.get()

        val failure: Throwable?
            get() = failureRef.get()

        fun awaitOpen(timeoutMs: Long = 5_000L): Boolean {
            val ok = openLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            val err = failureRef.get()
            if (err != null) throw err
            return ok
        }

        fun awaitClose(timeoutMs: Long = 5_000L): Boolean {
            return closeLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        fun sendStdin(bytes: ByteArray): Boolean {
            val frame = ByteArray(1 + bytes.size)
            frame[0] = CHANNEL_STDIN
            System.arraycopy(bytes, 0, frame, 1, bytes.size)
            return webSocket.send(frame.toByteString(0, frame.size))
        }

        fun sendStdin(text: String): Boolean =
            sendStdin(text.toByteArray(StandardCharsets.UTF_8))

        fun sendResize(cols: Int, rows: Int): Boolean {
            val jsonBytes = """{"Width":${cols.coerceAtLeast(1)},"Height":${rows.coerceAtLeast(1)}}"""
                .toByteArray(StandardCharsets.UTF_8)
            val frame = ByteArray(1 + jsonBytes.size)
            frame[0] = CHANNEL_RESIZE
            System.arraycopy(jsonBytes, 0, frame, 1, jsonBytes.size)
            return webSocket.send(frame.toByteString(0, frame.size))
        }

        override fun close() {
            runCatching { webSocket.close(1000, "Session closed") }
        }
    }

    fun buildExecHttpUrl(
        clusterEndpoint: String,
        namespace: String,
        podName: String,
        command: List<String>,
        container: String = "",
        stdin: Boolean = true,
        stdout: Boolean = true,
        stderr: Boolean = true,
        tty: Boolean = false
    ): String {
        val trimmed = clusterEndpoint.trim().trimEnd('/')
        val httpBase = when {
            trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
            trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
            trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
        val encNs = urlEncode(namespace.ifBlank { "default" })
        val encPod = urlEncode(podName)
        val sb = StringBuilder()
        sb.append(httpBase)
        sb.append("/api/v1/namespaces/").append(encNs).append("/pods/").append(encPod).append("/exec")
        sb.append("?stdin=").append(stdin)
        sb.append("&stdout=").append(stdout)
        sb.append("&stderr=").append(stderr)
        sb.append("&tty=").append(tty)
        if (container.isNotBlank()) {
            sb.append("&container=").append(urlEncode(container.trim()))
        }
        for (arg in command) {
            sb.append("&command=").append(urlEncode(arg))
        }
        return sb.toString()
    }

    fun openSession(
        clusterEndpoint: String,
        namespace: String,
        podName: String,
        command: List<String>,
        bearerToken: String = "",
        container: String = "",
        stdin: Boolean = true,
        stdout: Boolean = true,
        stderr: Boolean = true,
        tty: Boolean = false,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {},
        onStatus: (String) -> Unit = {},
        onClosed: () -> Unit = {}
    ): ExecSession {
        val url = buildExecHttpUrl(
            clusterEndpoint = clusterEndpoint,
            namespace = namespace,
            podName = podName,
            command = command,
            container = container,
            stdin = stdin,
            stdout = stdout,
            stderr = stderr,
            tty = tty
        )
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL_V4)
            .header("X-Stream-Protocol-Version", SUBPROTOCOL_V4)
        if (bearerToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $bearerToken")
        }

        val openLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        val failureRef = AtomicReference<Throwable?>(null)
        val subprotocolRef = AtomicReference("")

        val ws = httpClient.newWebSocket(
            reqBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    subprotocolRef.set(response.header("Sec-WebSocket-Protocol").orEmpty())
                    openLatch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size == 0) return
                    val ch = bytes[0]
                    val payloadText = if (bytes.size > 1) {
                        bytes.substring(1).string(StandardCharsets.UTF_8)
                    } else {
                        ""
                    }
                    when (ch) {
                        CHANNEL_STDOUT -> if (payloadText.isNotEmpty()) onStdout(payloadText)
                        CHANNEL_STDERR -> if (payloadText.isNotEmpty()) onStderr(payloadText)
                        CHANNEL_ERROR_STATUS -> if (payloadText.isNotEmpty()) onStatus(payloadText)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.isNotEmpty()) onStdout(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    openLatch.countDown()
                    closeLatch.countDown()
                    onClosed()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val detailed = if (response != null) {
                        IllegalStateException("K8s exec WebSocket HTTP ${response.code}: ${t.message}", t)
                    } else {
                        t
                    }
                    failureRef.compareAndSet(null, detailed)
                    openLatch.countDown()
                    closeLatch.countDown()
                    onClosed()
                }
            }
        )

        return ExecSession(
            webSocket = ws,
            openLatch = openLatch,
            closeLatch = closeLatch,
            failureRef = failureRef,
            subprotocolRef = subprotocolRef
        )
    }

    suspend fun executeCommand(
        clusterEndpoint: String,
        namespace: String,
        podName: String,
        command: List<String>,
        bearerToken: String = "",
        container: String = "",
        stdinPayload: String? = null,
        initialCols: Int = 120,
        initialRows: Int = 36,
        timeoutMs: Long = 10_000L
    ): ExecResult = withContext(Dispatchers.IO) {
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        val statusBuf = StringBuilder()
        val lock = Any()

        val session = openSession(
            clusterEndpoint = clusterEndpoint,
            namespace = namespace,
            podName = podName,
            command = command,
            bearerToken = bearerToken,
            container = container,
            stdin = stdinPayload != null,
            stdout = true,
            stderr = true,
            tty = false,
            onStdout = { chunk -> synchronized(lock) { stdoutBuf.append(chunk) } },
            onStderr = { chunk -> synchronized(lock) { stderrBuf.append(chunk) } },
            onStatus = { chunk -> synchronized(lock) { statusBuf.append(chunk) } }
        )

        session.use { active ->
            if (!active.awaitOpen(timeoutMs)) {
                error("Timed out opening Kubernetes v4.channel.k8s.io exec WebSocket")
            }
            active.sendResize(initialCols, initialRows)
            if (stdinPayload != null) {
                active.sendStdin(stdinPayload)
            }
            active.awaitClose(timeoutMs)
            active.failure?.let { throw it }
            synchronized(lock) {
                ExecResult(
                    stdout = stdoutBuf.toString(),
                    stderr = stderrBuf.toString(),
                    statusJson = statusBuf.toString(),
                    negotiatedSubprotocol = active.negotiatedSubprotocol
                )
            }
        }
    }

    companion object {
        const val SUBPROTOCOL_V4: String = "v4.channel.k8s.io"
        const val CHANNEL_STDIN: Byte = 0x00
        const val CHANNEL_STDOUT: Byte = 0x01
        const val CHANNEL_STDERR: Byte = 0x02
        const val CHANNEL_ERROR_STATUS: Byte = 0x03
        const val CHANNEL_RESIZE: Byte = 0x04

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }
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
 * Interactive Exec Shell (`v4.channel.k8s.io` WebSocket + Docker Exec REST), Live Log Streamer,
 * & Workload Inventory Studio.
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
    val k8sExecClient = remember(settings.verifyTls, httpClient) {
        KubernetesExecClient(verifyTls = settings.verifyTls, httpClientOverride = httpClient)
    }
    val lenientJson = remember { Json { ignoreUnknownKeys = true; isLenient = true } }

    var activePaneTab by remember(settings.followLogsOnOpen) {
        mutableIntStateOf(if (settings.followLogsOnOpen) 1 else 0)
    }
    var activeTarget by remember(settings, endpointHost) {
        mutableStateOf(
            settings.podOrContainerName.ifBlank {
                endpointHost.substringAfterLast('/').takeIf { !it.contains(':') }.orEmpty()
            }
        )
    }
    var namespace by remember(settings) { mutableStateOf(settings.namespace.ifBlank { "default" }) }
    var logFilterQuery by remember { mutableStateOf("") }
    var execInput by remember { mutableStateOf("") }
    var apiStatusBanner by remember { mutableStateOf("") }
    var apiStatusIsError by remember { mutableStateOf(false) }

    val execLines = remember(activeTarget, isKubernetes, settings.subContainerName) {
        val targetDisplay = activeTarget.ifBlank { "<select workload>" }
        val containerSuffix = settings.subContainerName.takeIf { it.isNotBlank() }?.let { " (container=$it)" }.orEmpty()
        mutableStateListOf(
            if (isKubernetes) {
                "[kubectl] Ready for pod '$namespace/$targetDisplay'$containerSuffix (shell=${settings.shellCommand}, protocol=v4.channel.k8s.io)"
            } else {
                "[docker] Ready for container '$targetDisplay' on ${endpointHost.ifBlank { settings.clusterOrSocketEndpoint }} (shell=${settings.shellCommand})"
            }
        )
    }

    val logLines = remember(activeTarget, isKubernetes) {
        mutableStateListOf<String>()
    }

    val workloads = remember(isKubernetes, namespace) {
        mutableStateListOf<DiscoveredContainerWorkload>()
    }

    val fetchLiveClusterTelemetry: () -> Unit = {
        scope.launch {
            val baseEndpoint = resolveWorkloadBaseEndpoint(endpointHost, settings.clusterOrSocketEndpoint)
            if (baseEndpoint.isBlank()) {
                apiStatusBanner = "No HTTP/HTTPS cluster or Docker daemon TCP endpoint configured."
                apiStatusIsError = true
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val listUrl = if (isKubernetes) {
                        "$baseEndpoint/api/v1/namespaces/${URLEncoder.encode(namespace, "UTF-8")}/pods"
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
                                val phase = status?.get("phase")?.jsonPrimitive?.contentOrNull ?: "Unknown"
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
                                val state = cObj["State"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                                if (cName.isNotBlank()) {
                                    parsedList += DiscoveredContainerWorkload(cid.ifBlank { cName }, cName, image, state)
                                }
                            }
                        }
                    }

                    val targetName = activeTarget.ifBlank { parsedList.firstOrNull()?.name.orEmpty() }
                    val fetchedLogs: List<String> = if (targetName.isNotBlank()) {
                        val tailCount = settings.logTailLines.coerceAtLeast(1)
                        val containerQuery = if (isKubernetes && settings.subContainerName.isNotBlank()) {
                            "&container=${URLEncoder.encode(settings.subContainerName.trim(), "UTF-8")}"
                        } else {
                            ""
                        }
                        val logsUrl = if (isKubernetes) {
                            "$baseEndpoint/api/v1/namespaces/${URLEncoder.encode(namespace, "UTF-8")}/pods/${URLEncoder.encode(targetName, "UTF-8")}/log?tailLines=$tailCount$containerQuery"
                        } else {
                            "$baseEndpoint/containers/${URLEncoder.encode(targetName, "UTF-8")}/logs?stdout=1&stderr=1&tail=$tailCount"
                        }
                        val reqBuilder = Request.Builder().url(logsUrl).get()
                        if (secret.password.isNotBlank()) {
                            reqBuilder.header("Authorization", "Bearer ${secret.password}")
                        }
                        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
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
                    } else {
                        emptyList()
                    }

                    Triple(targetName, fetchedLogs, parsedList)
                }
            }

            result.onSuccess { (resolvedTarget, fetchedLogs, parsedWorkloads) ->
                if (activeTarget.isBlank() && resolvedTarget.isNotBlank()) {
                    activeTarget = resolvedTarget
                }
                logLines.clear()
                logLines.addAll(fetchedLogs)
                workloads.clear()
                workloads.addAll(parsedWorkloads)
                apiStatusBanner = "Synced ${parsedWorkloads.size} workload(s) from $baseEndpoint"
                apiStatusIsError = false
            }.onFailure { err ->
                apiStatusBanner = "Telemetry sync failed: ${err.message ?: "endpoint unreachable"}"
                apiStatusIsError = true
            }
        }
    }

    val runWorkloadExecCommand: (String) -> Unit = { rawCommand ->
        val cmd = rawCommand.trim()
        if (cmd.isNotEmpty()) {
            if (cmd == "clear") {
                execLines.clear()
            } else {
                val targetName = activeTarget.trim()
                if (targetName.isEmpty()) {
                    execLines += "[exec error] No target pod/container selected. Sync API or enter a workload name."
                } else {
                    execLines += "$targetName# $cmd"
                    scope.launch {
                        val baseEndpoint = resolveWorkloadBaseEndpoint(endpointHost, settings.clusterOrSocketEndpoint)
                        val shellBin = settings.shellCommand.ifBlank { "/bin/sh" }
                        val remoteResult = withContext(Dispatchers.IO) {
                            runCatching {
                                if (baseEndpoint.isBlank()) error("No remote cluster/daemon endpoint configured")
                                if (isKubernetes) {
                                    val execRes = k8sExecClient.executeCommand(
                                        clusterEndpoint = baseEndpoint,
                                        namespace = namespace,
                                        podName = targetName,
                                        command = listOf(shellBin, "-c", cmd),
                                        bearerToken = secret.password,
                                        container = settings.subContainerName
                                    )
                                    buildString {
                                        if (execRes.stdout.isNotBlank()) append(execRes.stdout.trimEnd())
                                        if (execRes.stderr.isNotBlank()) {
                                            if (isNotEmpty()) append('\n')
                                            append(execRes.stderr.trimEnd())
                                        }
                                    }
                                } else {
                                    val jsonMedia = "application/json; charset=utf-8".toMediaType()
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
                                        .url("$baseEndpoint/containers/${URLEncoder.encode(targetName, "UTF-8")}/exec")
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
                            val msg = err.message ?: "endpoint unreachable"
                            apiStatusBanner = "Exec error: $msg"
                            apiStatusIsError = true
                            execLines += "[exec error] $msg"
                        }
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
                    val targetBadge = activeTarget.ifBlank { "unselected" }
                    Text(
                        text = if (isKubernetes) "K8S: $namespace/$targetBadge" else "DOCKER: $targetBadge",
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
                                color = if (line.startsWith("[exec error]")) Color(0xFFFF6E6E) else Color(0xFFE2E6F3)
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
                                val tgt = activeTarget.ifBlank { "<pod>" }
                                Text(
                                    if (isKubernetes) "kubectl exec -it $tgt -- ${settings.shellCommand}…" else "docker exec -it $tgt ${settings.shellCommand}…",
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
                    if (filteredLogs.isEmpty()) {
                        Text(
                            text = if (apiStatusIsError) apiStatusBanner else "No log lines received yet. Click 'Sync API' to stream live logs.",
                            style = NexusTheme.monospace.logEntry,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(14.dp)
                        )
                    } else {
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
            }

            else -> {
                // Discovered Pods / Containers
                if (workloads.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Text(
                            text = if (apiStatusIsError) apiStatusBanner else "No active workloads discovered. Click 'Sync API' to query the cluster/daemon.",
                            style = NexusTheme.monospace.keycapShortcut,
                            color = if (apiStatusIsError) Color(0xFFFFB86B) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
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
}
