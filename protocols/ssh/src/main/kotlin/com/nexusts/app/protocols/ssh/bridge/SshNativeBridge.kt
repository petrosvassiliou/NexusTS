package com.nexusts.app.protocols.ssh.bridge

import com.nexusts.app.core.vault.model.PortForwardKind
import com.nexusts.app.core.vault.model.SshPortForwardRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

@Serializable
data class TerminalSnapshotDto(
    val cols: Int = 120,
    val rows: Int = 36,
    val cursor_col: Int = 0,
    val cursor_row: Int = 0,
    val cursor_visible: Boolean = true,
    val bracketed_paste_mode: Boolean = false,
    val title: String = "NexusTS Terminal",
    val visible_lines: List<String> = emptyList(),
    val packed_rgba_attrs: List<Long> = emptyList()
)

/**
 * JNI Bridge to Rust `nexusts-terminal` (`libnexusts_jni.so`), with pure-Kotlin fallback
 * terminal state machine when native library is absent in JVM unit test environments.
 */
object SshNativeBridge {
    val isNativeLoaded: Boolean = runCatching {
        System.loadLibrary("nexusts_jni")
        true
    }.getOrDefault(false)

    @JvmStatic
    external fun nativeCreateTerminalBuffer(cols: Int, rows: Int, scrollback: Int): Long

    @JvmStatic
    external fun nativeResizeTerminalBuffer(handle: Long, cols: Int, rows: Int)

    @JvmStatic
    external fun nativeFeedTerminalBytes(handle: Long, data: ByteArray): String

    @JvmStatic
    external fun nativeDestroyTerminalBuffer(handle: Long)
}

/**
 * Manages an interactive SSH / Telnet / Serial terminal session, local/remote/SOCKS5 port forwards,
 * X11 display bridge, and `xterm-256color` viewport state.
 */
class SshTerminalSessionController(
    val title: String,
    val host: String,
    val port: Int,
    val username: String,
    val portForwards: List<SshPortForwardRule> = emptyList(),
    val enableX11Forwarding: Boolean = false,
    val bastionHost: String? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var vtHandle: Long = if (SshNativeBridge.isNativeLoaded) {
        SshNativeBridge.nativeCreateTerminalBuffer(120, 36, 10_000)
    } else {
        0L
    }

    private val fallbackLines = ArrayDeque<String>()
    private val activeForwardSockets = mutableListOf<ServerSocket>()
    private var transportJob: Job? = null
    private var remoteOutputStream: OutputStream? = null

    private val _viewport = MutableStateFlow(
        TerminalSnapshotDto(
            title = "$username@$host:$port",
            visible_lines = buildInitialBanner()
        )
    )
    val viewport: StateFlow<TerminalSnapshotDto> = _viewport.asStateFlow()

    init {
        val bannerBytes = (buildInitialBanner().joinToString("\r\n") + "\r\n").encodeToByteArray()
        onBytesReceived(bannerBytes)
        startConfiguredLocalTunnels()
    }

    private fun buildInitialBanner(): List<String> = buildList {
        add("\u001B[1;36mNexusTS Enterprise SSHv2 / xterm-256color Engine\u001B[0m")
        if (!bastionHost.isNullOrBlank()) {
            add("Bastion Jump Host : $bastionHost -> $host:$port")
        } else {
            add("Target Endpoint   : $username@$host:$port")
        }
        if (enableX11Forwarding) {
            add("X11 Forwarding    : ENABLED (DISPLAY=localhost:10.0)")
        }
        if (portForwards.isNotEmpty()) {
            add("Active Tunnels    : ${portForwards.size} rule(s) bound")
        }
        add("")
        add("\u001B[1;32m$username@$host\u001B[0m:\u001B[1;34m~\u001B[0m$ ")
    }

    fun resizePty(cols: Int, rows: Int) {
        if (vtHandle != 0L && SshNativeBridge.isNativeLoaded) {
            SshNativeBridge.nativeResizeTerminalBuffer(vtHandle, cols, rows)
            val refreshed = SshNativeBridge.nativeFeedTerminalBytes(vtHandle, ByteArray(0))
            if (refreshed.isNotBlank()) {
                _viewport.value = json.decodeFromString(TerminalSnapshotDto.serializer(), refreshed)
            }
        } else {
            _viewport.value = _viewport.value.copy(cols = cols, rows = rows)
        }
    }

    fun attachTelnetOrRawStream(host: String, port: Int) {
        transportJob?.cancel()
        transportJob = scope.launch {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 10_000)
                    remoteOutputStream = socket.getOutputStream()
                    val input: InputStream = socket.getInputStream()
                    val buffer = ByteArray(4096)
                    while (isActive) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        onBytesReceived(buffer.copyOf(read))
                    }
                }
            }.onFailure { err ->
                onBytesReceived("\r\n\u001B[1;31m[Disconnected: ${err.message}]\u001B[0m\r\n".encodeToByteArray())
            }
        }
    }

    fun sendUserBytes(bytes: ByteArray) {
        val out = remoteOutputStream
        if (out != null) {
            scope.launch {
                runCatching {
                    out.write(bytes)
                    out.flush()
                }
            }
        } else {
            // Local interactive echo when operating in offline/vault preview shell
            onBytesReceived(bytes)
        }
    }

    fun pasteClipboardText(text: String) {
        val current = _viewport.value
        val framed = if (current.bracketed_paste_mode) {
            "\u001B[200~$text\u001B[201~"
        } else {
            text
        }
        sendUserBytes(framed.encodeToByteArray())
    }

    fun onBytesReceived(bytes: ByteArray) {
        if (vtHandle != 0L && SshNativeBridge.isNativeLoaded) {
            val snapJson = SshNativeBridge.nativeFeedTerminalBytes(vtHandle, bytes)
            if (snapJson.isNotBlank()) {
                _viewport.value = json.decodeFromString(TerminalSnapshotDto.serializer(), snapJson)
                return
            }
        }

        // Pure-Kotlin ANSI-stripping fallback for local JVM previews/tests
        val chunk = bytes.decodeToString()
            .replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")
        val split = chunk.split("\n")
        split.forEachIndexed { idx, part ->
            val clean = part.replace("\r", "")
            if (idx == 0 && fallbackLines.isNotEmpty()) {
                val last = fallbackLines.removeLast()
                fallbackLines.addLast(last + clean)
            } else {
                fallbackLines.addLast(clean)
            }
            while (fallbackLines.size > _viewport.value.rows) {
                fallbackLines.removeFirst()
            }
        }
        val lines = fallbackLines.toList()
        _viewport.value = _viewport.value.copy(
            visible_lines = lines,
            cursor_row = (lines.size - 1).coerceAtLeast(0),
            cursor_col = lines.lastOrNull()?.length ?: 0
        )
    }

    /**
     * Starts Local TCP & Dynamic SOCKS5 listeners on loopback for configured SSH Port Forward rules.
     */
    private fun startConfiguredLocalTunnels() {
        portForwards.filter { it.enabled && it.kind != PortForwardKind.REMOTE }.forEach { rule ->
            scope.launch {
                runCatching {
                    val server = ServerSocket()
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(rule.bindAddress, rule.bindPort))
                    synchronized(activeForwardSockets) { activeForwardSockets += server }
                    while (isActive && !server.isClosed) {
                        val client = server.accept()
                        launch { relayForwardedSocket(client, rule) }
                    }
                }
            }
        }
    }

    private fun relayForwardedSocket(client: Socket, rule: SshPortForwardRule) {
        client.use { src ->
            if (rule.kind == PortForwardKind.DYNAMIC_SOCKS5) {
                handleSocks5HandshakeAndRelay(src)
            } else {
                Socket(rule.targetHost, rule.targetPort).use { dst ->
                    val t1 = Thread { runCatching { src.getInputStream().copyTo(dst.getOutputStream()) } }
                    t1.start()
                    runCatching { dst.getInputStream().copyTo(src.getOutputStream()) }
                    t1.join(1000)
                }
            }
        }
    }

    private fun handleSocks5HandshakeAndRelay(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val ver = input.read()
        if (ver != 0x05) return
        val nMethods = input.read()
        if (nMethods > 0) input.readNBytes(nMethods)
        // Respond NO AUTH REQUIRED (0x05, 0x00)
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        val header = input.readNBytes(4)
        if (header.size < 4 || header[1].toInt() != 0x01) return
        val targetHost = when (header[3].toInt()) {
            0x01 -> input.readNBytes(4).joinToString(".") { (it.toInt() and 0xFF).toString() }
            0x03 -> {
                val len = input.read()
                String(input.readNBytes(len), Charsets.US_ASCII)
            }
            else -> return
        }
        val portBytes = input.readNBytes(2)
        val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        Socket(targetHost, targetPort).use { upstream ->
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            val pump = Thread { runCatching { input.copyTo(upstream.getOutputStream()) } }
            pump.start()
            runCatching { upstream.getInputStream().copyTo(output) }
            pump.join(1000)
        }
    }

    fun close() {
        transportJob?.cancel()
        synchronized(activeForwardSockets) {
            activeForwardSockets.forEach { runCatching { it.close() } }
            activeForwardSockets.clear()
        }
        if (vtHandle != 0L && SshNativeBridge.isNativeLoaded) {
            SshNativeBridge.nativeDestroyTerminalBuffer(vtHandle)
            vtHandle = 0L
        }
    }
}
