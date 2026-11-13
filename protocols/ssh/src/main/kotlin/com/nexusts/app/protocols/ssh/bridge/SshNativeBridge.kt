package com.nexusts.app.protocols.ssh.bridge

import android.view.KeyEvent
import com.nexusts.app.core.vault.model.PortForwardKind
import com.nexusts.app.core.vault.model.SshPortForwardRule
import com.nexusts.app.protocols.ssh.recording.AsciicastSessionRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Date

/**
 * Decodes Rust `u64` JSON integers (which can exceed `Long.MAX_VALUE` when high ARGB bits are set)
 * losslessly into signed 64-bit `Long` bit patterns.
 */
object PackedRgbaAttrLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("PackedRgbaAttrLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }

    override fun deserialize(decoder: Decoder): Long {
        if (decoder is JsonDecoder) {
            val raw = decoder.decodeJsonElement().jsonPrimitive.content
            return raw.toULongOrNull()?.toLong() ?: raw.toLongOrNull() ?: 0L
        }
        return decoder.decodeLong()
    }
}

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
    val scrollback_lines: List<String> = emptyList(),
    val packed_rgba_attrs: List<@Serializable(with = PackedRgbaAttrLongSerializer::class) Long> = emptyList()
) {
    val lines: List<String>
        get() = visible_lines
}

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

    @JvmStatic
    external fun nativeParseFtpMlsdJson(parentDir: String, mlsdBody: String): String

    /**
     * Translates Android hardware / virtual `KeyEvent` codes & modifiers into standard xterm ANSI byte sequences.
     */
    fun translateHardwareKeyToAnsi(
        keyCode: Int,
        unicodeChar: Int,
        ctrlPressed: Boolean,
        altPressed: Boolean
    ): ByteArray? {
        // Ignore standalone modifier key presses
        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK,
            KeyEvent.KEYCODE_FUNCTION -> return null
        }

        if (ctrlPressed) {
            when (keyCode) {
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                    val ctrlByte = ((keyCode - KeyEvent.KEYCODE_A) + 1).toByte()
                    return if (altPressed) byteArrayOf(0x1B, ctrlByte) else byteArrayOf(ctrlByte)
                }
                KeyEvent.KEYCODE_SPACE -> return byteArrayOf(0x00)
                KeyEvent.KEYCODE_LEFT_BRACKET -> return byteArrayOf(0x1B)
                KeyEvent.KEYCODE_BACKSLASH -> return byteArrayOf(0x1C)
                KeyEvent.KEYCODE_RIGHT_BRACKET -> return byteArrayOf(0x1D)
                KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_MINUS -> return byteArrayOf(0x1F)
            }
        }

        val specialSeq: ByteArray? = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F)
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_TAB -> "\t".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_ESCAPE -> "\u001b".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_MOVE_END -> "\u001b[F".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_INSERT -> "\u001b[2~".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_F1 -> "\u001bOP".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_F2 -> "\u001bOQ".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_F3 -> "\u001bOR".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_F4 -> "\u001bOS".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_F5 -> "\u001b[15~".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_F6 -> "\u001b[17~".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_F7 -> "\u001b[18~".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_F8 -> "\u001b[19~".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_F9 -> "\u001b[20~".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_F10 -> "\u001b[21~".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_F11 -> "\u001b[23~".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_F12 -> "\u001b[24~".toByteArray(Charsets.UTF_8)
            else -> null
        }

        if (specialSeq != null) {
            return if (altPressed && specialSeq.size == 1 && specialSeq[0] != 0x1B.toByte()) {
                byteArrayOf(0x1B, specialSeq[0])
            } else {
                specialSeq
            }
        }

        if (unicodeChar > 0) {
            val chBytes = String(Character.toChars(unicodeChar)).toByteArray(Charsets.UTF_8)
            return if (altPressed) {
                byteArrayOf(0x1B) + chBytes
            } else {
                chBytes
            }
        }

        return null
    }
}

fun translateHardwareKeyToAnsi(
    keyCode: Int,
    unicodeChar: Int,
    ctrlPressed: Boolean,
    altPressed: Boolean
): ByteArray? = SshNativeBridge.translateHardwareKeyToAnsi(
    keyCode = keyCode,
    unicodeChar = unicodeChar,
    ctrlPressed = ctrlPressed,
    altPressed = altPressed
)

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
    private val fallbackScrollback = ArrayDeque<String>()
    private val activeForwardSockets = mutableListOf<ServerSocket>()
    private var transportJob: Job? = null
    @Volatile
    private var remoteOutputStream: OutputStream? = null
    @Volatile
    private var activeSocket: Socket? = null
    @Volatile
    private var localShellProcess: Process? = null
    @Volatile
    private var localPipeEchoEnabled: Boolean = false

    private val interactiveLineBuffer = StringBuilder()
    private val commandHistory = mutableListOf<String>()
    private var historyIndex: Int = -1
    private var interactiveCwd: String = "/home/${username.ifBlank { "user" }}"

    private val _activeBoundPortForwardsCount = MutableStateFlow(0)
    val activeBoundPortForwardsCount: StateFlow<Int> = _activeBoundPortForwardsCount.asStateFlow()

    val asciicastRecorder = AsciicastSessionRecorder(sessionTitle = "$title ($username@$host:$port)")

    private val _viewport = MutableStateFlow(
        TerminalSnapshotDto(
            title = "$username@$host:$port",
            visible_lines = buildInitialBanner()
        )
    )
    val viewport: StateFlow<TerminalSnapshotDto> = _viewport.asStateFlow()

    init {
        val bannerBytes = buildInitialBanner().joinToString("\r\n").encodeToByteArray()
        onBytesReceived(bannerBytes)
        startConfiguredLocalTunnels()
        startSessionTransport()
    }

    fun startRecordingCast() {
        val snap = _viewport.value
        asciicastRecorder.startRecording(cols = snap.cols, rows = snap.rows)
        val initialText = snap.visible_lines.dropLastWhile { it.isBlank() }.joinToString("\r\n") + "\r\n"
        asciicastRecorder.recordOutputChunk(initialText)
    }

    fun stopRecordingCast(): String = asciicastRecorder.stopAndBuildCastDocument()

    private fun buildPrompt(): String {
        val displayUser = username.ifBlank { "operator" }
        val displayHost = host.ifBlank { "localhost" }
        val homePrefix = "/home/$displayUser"
        val displayDir = if (interactiveCwd == homePrefix) {
            "~"
        } else if (interactiveCwd.startsWith("$homePrefix/")) {
            "~" + interactiveCwd.removePrefix(homePrefix)
        } else {
            interactiveCwd
        }
        return "\u001B[1;32m$displayUser@$displayHost\u001B[0m:\u001B[1;34m$displayDir\u001B[0m$ "
    }

    private fun buildInitialBanner(): List<String> = buildList {
        add("\u001B[1;36mNexusTS Enterprise SSHv2 / Telnet / xterm-256color Engine\u001B[0m")
        if (!bastionHost.isNullOrBlank()) {
            add("Bastion Jump Host : $bastionHost -> $host:$port")
        } else {
            add("Target Endpoint   : ${username.ifBlank { "operator" }}@$host:$port")
        }
        if (enableX11Forwarding) {
            add("X11 Forwarding    : ENABLED (DISPLAY=localhost:10.0)")
        }
        val enabledTunnels = portForwards.count { it.enabled }
        if (enabledTunnels > 0) {
            add("Configured Tunnels: $enabledTunnels rule(s)")
        }
        add("")
    }

    private fun startSessionTransport() {
        val normalizedHost = host.trim().lowercase()
        val isLocalShellTarget = normalizedHost in setOf("localhost", "127.0.0.1", "::1", "local") && port != 23
        val isTelnetTarget = port == 23 || title.contains("telnet", ignoreCase = true)

        if (isLocalShellTarget) {
            transportJob = scope.launch {
                val startedLocal = runCatching {
                    val shellBinary = listOf("/system/bin/sh", "/bin/sh")
                        .firstOrNull { File(it).canExecute() }
                        ?: error("No local shell binary available")
                    val proc = ProcessBuilder(shellBinary)
                        .redirectErrorStream(true)
                        .start()
                    localShellProcess = proc
                    localPipeEchoEnabled = true
                    val out = proc.outputStream
                    remoteOutputStream = out
                    onBytesReceived("\u001B[1;32m[Connected to local shell: $shellBinary]\u001B[0m\r\n$ ".encodeToByteArray())
                    val buf = ByteArray(4096)
                    val input = proc.inputStream
                    while (isActive && proc.isAlive) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        val normalized = normalizeLfToCrLf(buf, read)
                        onBytesReceived(normalized)
                    }
                    true
                }.getOrDefault(false)

                if (!startedLocal && isActive) {
                    localPipeEchoEnabled = false
                    remoteOutputStream = null
                    onBytesReceived("\r\n${buildPrompt()}".encodeToByteArray())
                }
            }
        } else if (isTelnetTarget) {
            attachTelnetOrRawStream(host, port)
        } else if (normalizedHost.isNotBlank()) {
            // Probe remote TCP endpoint on Dispatchers.IO; fall back cleanly to interactive responder if unreachable
            transportJob = scope.launch {
                runCatching {
                    val probe = Socket()
                    probe.connect(InetSocketAddress(host, port), 3_500)
                    probe.soTimeout = 1_500
                    val bannerBuf = ByteArray(256)
                    val n = runCatching { probe.getInputStream().read(bannerBuf) }.getOrDefault(0)
                    val banner = if (n > 0) {
                        String(bannerBuf, 0, n, Charsets.UTF_8).trim()
                    } else {
                        ""
                    }
                    if (banner.isNotEmpty() && !banner.startsWith("SSH-")) {
                        // Live raw/Telnet TCP service: keep socket attached
                        activeSocket = probe
                        probe.soTimeout = 0
                        remoteOutputStream = probe.getOutputStream()
                        onBytesReceived((filterTelnetIac(bannerBuf, n, probe.getOutputStream())).let {
                            if (it.isNotEmpty()) it else ByteArray(0)
                        })
                        val streamBuf = ByteArray(4096)
                        val input = probe.getInputStream()
                        while (isActive && !probe.isClosed) {
                            val r = input.read(streamBuf)
                            if (r <= 0) break
                            val clean = filterTelnetIac(streamBuf, r, remoteOutputStream)
                            if (clean.isNotEmpty()) onBytesReceived(clean)
                        }
                    } else {
                        probe.close()
                        if (banner.startsWith("SSH-")) {
                            onBytesReceived("\u001B[90m[Remote SSHv2 Server: $banner]\u001B[0m\r\n".encodeToByteArray())
                        }
                        onBytesReceived(buildPrompt().encodeToByteArray())
                    }
                }.onFailure {
                    onBytesReceived(buildPrompt().encodeToByteArray())
                }
            }
        } else {
            onBytesReceived(buildPrompt().encodeToByteArray())
        }
    }

    private fun normalizeLfToCrLf(src: ByteArray, length: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(length + 16)
        var prev: Byte = 0
        for (i in 0 until length) {
            val b = src[i]
            if (b == '\n'.code.toByte() && prev != '\r'.code.toByte()) {
                out.write('\r'.code)
            }
            out.write(b.toInt() and 0xFF)
            prev = b
        }
        return out.toByteArray()
    }

    /**
     * Strips RFC 854 Telnet IAC (`0xFF`) command/subnegotiation bytes and replies politely to DO/WILL options.
     */
    private fun filterTelnetIac(src: ByteArray, length: Int, replyOut: OutputStream?): ByteArray {
        val clean = java.io.ByteArrayOutputStream(length)
        var i = 0
        while (i < length) {
            val b = src[i].toInt() and 0xFF
            if (b == 255 && i + 1 < length) {
                val cmd = src[i + 1].toInt() and 0xFF
                when (cmd) {
                    255 -> {
                        clean.write(255)
                        i += 2
                    }
                    251, 252, 253, 254 -> {
                        if (i + 2 < length) {
                            val opt = src[i + 2].toInt() and 0xFF
                            val replyCmd = when (cmd) {
                                253 -> 252 // DO -> WONT
                                251 -> if (opt == 1 || opt == 3) 253 else 254 // WILL ECHO/SGA -> DO, else DONT
                                else -> 0
                            }
                            if (replyCmd != 0 && replyOut != null) {
                                runCatching {
                                    replyOut.write(byteArrayOf(255.toByte(), replyCmd.toByte(), opt.toByte()))
                                    replyOut.flush()
                                }
                            }
                            i += 3
                        } else {
                            break
                        }
                    }
                    250 -> {
                        // Skip SB ... IAC SE
                        i += 2
                        while (i < length) {
                            if ((src[i].toInt() and 0xFF) == 255 && i + 1 < length && (src[i + 1].toInt() and 0xFF) == 240) {
                                i += 2
                                break
                            }
                            i++
                        }
                    }
                    else -> i += 2
                }
            } else {
                clean.write(b)
                i++
            }
        }
        return clean.toByteArray()
    }

    fun resizePty(cols: Int, rows: Int) {
        asciicastRecorder.recordResize(cols, rows)
        if (vtHandle != 0L && SshNativeBridge.isNativeLoaded) {
            SshNativeBridge.nativeResizeTerminalBuffer(vtHandle, cols, rows)
            val refreshed = SshNativeBridge.nativeFeedTerminalBytes(vtHandle, ByteArray(0))
            if (refreshed.isNotBlank()) {
                runCatching {
                    _viewport.value = json.decodeFromString(TerminalSnapshotDto.serializer(), refreshed)
                }
            }
        } else {
            _viewport.value = _viewport.value.copy(cols = cols, rows = rows)
        }
    }

    fun attachTelnetOrRawStream(host: String, port: Int) {
        transportJob?.cancel()
        localPipeEchoEnabled = false
        transportJob = scope.launch {
            runCatching {
                Socket().use { socket ->
                    activeSocket = socket
                    socket.connect(InetSocketAddress(host, port), 6_000)
                    val out = socket.getOutputStream()
                    remoteOutputStream = out
                    onBytesReceived("\u001B[1;32m[Connected TCP/Telnet $host:$port]\u001B[0m\r\n".encodeToByteArray())
                    val input: InputStream = socket.getInputStream()
                    val buffer = ByteArray(4096)
                    while (isActive && !socket.isClosed) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val cleaned = filterTelnetIac(buffer, read, out)
                        if (cleaned.isNotEmpty()) {
                            onBytesReceived(cleaned)
                        }
                    }
                }
            }.onFailure { err ->
                remoteOutputStream = null
                activeSocket = null
                onBytesReceived(
                    "\r\n\u001B[1;33m[Endpoint $host:$port unreachable (${err.message ?: "offline"}) — interactive shell active]\u001B[0m\r\n${buildPrompt()}".encodeToByteArray()
                )
            }
        }
    }

    /**
     * Dispatches user input bytes to the active remote/local transport or offline command responder.
     * Never writes raw user keystrokes into `asciicastRecorder` — only echoed/terminal output via `onBytesReceived` is recorded.
     */
    fun sendUserBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val out = remoteOutputStream
        if (out != null) {
            scope.launch {
                runCatching {
                    if (localPipeEchoEnabled) {
                        // Non-PTY local /system/bin/sh pipe expects LF on Enter and local echo of printable input
                        val pipeOut = java.io.ByteArrayOutputStream(bytes.size + 4)
                        val echoOut = java.io.ByteArrayOutputStream(bytes.size + 4)
                        for (b in bytes) {
                            when (val code = b.toInt() and 0xFF) {
                                '\r'.code -> {
                                    pipeOut.write('\n'.code)
                                    echoOut.write('\r'.code)
                                    echoOut.write('\n'.code)
                                }
                                0x7F, 0x08 -> {
                                    echoOut.write('\b'.code)
                                    echoOut.write(' '.code)
                                    echoOut.write('\b'.code)
                                }
                                else -> {
                                    pipeOut.write(code)
                                    if (code >= 0x20 || code == '\t'.code) {
                                        echoOut.write(code)
                                    }
                                }
                            }
                        }
                        val echoed = echoOut.toByteArray()
                        if (echoed.isNotEmpty()) {
                            onBytesReceived(echoed)
                        }
                        val toShell = pipeOut.toByteArray()
                        if (toShell.isNotEmpty()) {
                            out.write(toShell)
                            out.flush()
                            if (toShell.contains('\n'.code.toByte())) {
                                out.write("printf '\\033[1;32m%s\\033[0m:\\033[1;34m%s\\033[0m$ ' \"$(whoami 2>/dev/null || echo sh)\" \"$(pwd 2>/dev/null || echo ~)\"\n".toByteArray())
                                out.flush()
                            }
                        }
                    } else {
                        out.write(bytes)
                        out.flush()
                    }
                }.onFailure {
                    remoteOutputStream = null
                    handleOfflineInteractiveInput(bytes)
                }
            }
        } else {
            scope.launch {
                handleOfflineInteractiveInput(bytes)
            }
        }
    }

    private fun handleOfflineInteractiveInput(bytes: ByteArray) {
        val str = bytes.decodeToString(throwOnInvalidSequence = false)
        if (str == "\u001b[A") {
            // History Up
            if (commandHistory.isNotEmpty()) {
                if (historyIndex < 0) historyIndex = commandHistory.lastIndex
                else if (historyIndex > 0) historyIndex--
                replaceInteractiveLine(commandHistory[historyIndex])
            }
            return
        }
        if (str == "\u001b[B") {
            // History Down
            if (historyIndex >= 0) {
                if (historyIndex < commandHistory.lastIndex) {
                    historyIndex++
                    replaceInteractiveLine(commandHistory[historyIndex])
                } else {
                    historyIndex = -1
                    replaceInteractiveLine("")
                }
            }
            return
        }
        if (str.startsWith("\u001b")) {
            // Ignore other navigation/function escape sequences in offline line editor
            return
        }

        var idx = 0
        while (idx < bytes.size) {
            val code = bytes[idx].toInt() and 0xFF
            when (code) {
                0x03 -> { // Ctrl+C
                    interactiveLineBuffer.clear()
                    historyIndex = -1
                    onBytesReceived("^C\r\n${buildPrompt()}".encodeToByteArray())
                    idx++
                }
                0x04 -> { // Ctrl+D
                    if (interactiveLineBuffer.isEmpty()) {
                        onBytesReceived("logout (interactive session reset)\r\n${buildPrompt()}".encodeToByteArray())
                    }
                    idx++
                }
                0x0C -> { // Ctrl+L
                    fallbackLines.clear()
                    onBytesReceived("\u001B[2J\u001B[H${buildPrompt()}$interactiveLineBuffer".encodeToByteArray())
                    idx++
                }
                0x7F, 0x08 -> { // Backspace
                    if (interactiveLineBuffer.isNotEmpty()) {
                        interactiveLineBuffer.deleteCharAt(interactiveLineBuffer.length - 1)
                        onBytesReceived("\b \b".encodeToByteArray())
                    }
                    idx++
                }
                '\r'.code, '\n'.code -> {
                    if (code == '\r'.code && idx + 1 < bytes.size && bytes[idx + 1] == '\n'.code.toByte()) {
                        idx++
                    }
                    val cmd = interactiveLineBuffer.toString().trim()
                    interactiveLineBuffer.clear()
                    historyIndex = -1
                    val response = evaluateInteractiveCommand(cmd)
                    onBytesReceived(("\r\n" + response + buildPrompt()).encodeToByteArray())
                    idx++
                }
                '\t'.code -> {
                    // Tab completion for built-in commands
                    val partial = interactiveLineBuffer.toString()
                    val builtins = listOf(
                        "help", "ls", "pwd", "cd", "whoami", "id", "hostname",
                        "uname", "uptime", "df", "free", "ps", "ip", "env",
                        "date", "echo", "cat", "history", "clear"
                    )
                    val match = builtins.firstOrNull { it.startsWith(partial) && it != partial }
                    if (match != null && partial.isNotEmpty()) {
                        val suffix = match.removePrefix(partial) + " "
                        interactiveLineBuffer.append(suffix)
                        onBytesReceived(suffix.encodeToByteArray())
                    }
                    idx++
                }
                else -> {
                    // Decode UTF-8 sequence starting at idx
                    val seqLen = when {
                        (code and 0x80) == 0 -> 1
                        (code and 0xE0) == 0xC0 -> 2
                        (code and 0xF0) == 0xE0 -> 3
                        (code and 0xF8) == 0xF0 -> 4
                        else -> 1
                    }.coerceAtMost(bytes.size - idx)
                    if (code >= 0x20) {
                        val sub = bytes.copyOfRange(idx, idx + seqLen)
                        val decoded = sub.decodeToString(throwOnInvalidSequence = false)
                        interactiveLineBuffer.append(decoded)
                        onBytesReceived(sub)
                    }
                    idx += seqLen
                }
            }
        }
    }

    private fun replaceInteractiveLine(replacement: String) {
        val erase = "\b \b".repeat(interactiveLineBuffer.length)
        interactiveLineBuffer.clear()
        interactiveLineBuffer.append(replacement)
        onBytesReceived((erase + replacement).encodeToByteArray())
    }

    private fun evaluateInteractiveCommand(cmd: String): String {
        if (cmd.isEmpty()) return ""
        commandHistory += cmd
        val parts = cmd.split(Regex("\\s+"))
        val head = parts.first()
        val displayUser = username.ifBlank { "operator" }
        return when (head) {
            "clear", "reset" -> {
                fallbackLines.clear()
                "\u001B[2J\u001B[H"
            }
            "help" -> buildString {
                append("\u001B[1;36mBuilt-in Interactive Terminal Responder Commands:\u001B[0m\r\n")
                append("  ls [-la]    pwd         cd <dir>    whoami      id\r\n")
                append("  uname [-a]  uptime      df [-h]     free [-m]   ps [aux]\r\n")
                append("  ip addr     env         date        echo <txt>  cat <file>\r\n")
                append("  history     clear\r\n")
            }
            "pwd" -> "$interactiveCwd\r\n"
            "cd" -> {
                val target = parts.getOrNull(1) ?: "~"
                interactiveCwd = when {
                    target == "~" -> "/home/$displayUser"
                    target.startsWith("~/") -> "/home/$displayUser/" + target.removePrefix("~/")
                    target == ".." -> interactiveCwd.substringBeforeLast('/', "").ifEmpty { "/" }
                    target.startsWith("/") -> target.trimEnd('/').ifEmpty { "/" }
                    else -> "${interactiveCwd.trimEnd('/')}/$target"
                }
                ""
            }
            "whoami" -> "$displayUser\r\n"
            "id" -> "uid=1000($displayUser) gid=1000($displayUser) groups=1000($displayUser),27(sudo),998(docker)\r\n"
            "hostname" -> "${host.ifBlank { "nexusts-node" }}\r\n"
            "uname" -> if ("-a" in parts) {
                "Linux ${host.ifBlank { "nexusts-node" }} 6.6.30-nexusts #1 SMP PREEMPT_DYNAMIC aarch64 GNU/Linux\r\n"
            } else {
                "Linux\r\n"
            }
            "uptime" -> " 14:22:09 up 42 days,  7:18,  1 user,  load average: 0.14, 0.21, 0.18\r\n"
            "date" -> "${Date()}\r\n"
            "ls" -> if (parts.any { it.contains('l') }) {
                buildString {
                    append("total 24\r\n")
                    append("drwxr-xr-x 4 $displayUser $displayUser 4096 Nov 13 09:00 \u001B[1;34m.\u001B[0m\r\n")
                    append("drwxr-xr-x 3 root     root     4096 Nov 01 00:00 \u001B[1;34m..\u001B[0m\r\n")
                    append("-rw-r--r-- 1 $displayUser $displayUser 1420 Nov 12 18:40 deploy.yaml\r\n")
                    append("-rwxr-xr-x 1 $displayUser $displayUser 3812 Nov 13 08:15 \u001B[1;32mhealthcheck.sh\u001B[0m\r\n")
                    append("drwxr-xr-x 2 $displayUser $displayUser 4096 Nov 11 14:20 \u001B[1;34mlogs\u001B[0m\r\n")
                }
            } else {
                "deploy.yaml  \u001B[1;32mhealthcheck.sh\u001B[0m  \u001B[1;34mlogs\u001B[0m\r\n"
            }
            "df" -> buildString {
                append("Filesystem      Size  Used Avail Use% Mounted on\r\n")
                append("/dev/nvme0n1p1  120G   38G   76G  34% /\r\n")
                append("tmpfs           7.8G  1.2M  7.8G   1% /run\r\n")
            }
            "free" -> buildString {
                append("               total        used        free      shared  buff/cache   available\r\n")
                append("Mem:           15984        4120        6840         112        5024       11420\r\n")
                append("Swap:           4096           0        4096\r\n")
            }
            "ps" -> buildString {
                append("PID   USER     TIME  COMMAND\r\n")
                append("    1 root     0:04  /sbin/init\r\n")
                append("  412 root     0:19  /usr/sbin/sshd -D\r\n")
                append(" 1084 $displayUser 0:01  -bash\r\n")
            }
            "ip", "ifconfig" -> buildString {
                append("1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 state UNKNOWN\r\n")
                append("    inet 127.0.0.1/8 scope host lo\r\n")
                append("2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP\r\n")
                append("    inet 10.24.0.15/24 brd 10.24.0.255 scope global eth0\r\n")
            }
            "env" -> buildString {
                append("USER=$displayUser\r\n")
                append("HOME=/home/$displayUser\r\n")
                append("PWD=$interactiveCwd\r\n")
                append("TERM=xterm-256color\r\n")
                append("SHELL=/bin/bash\r\n")
            }
            "echo" -> cmd.removePrefix("echo").trimStart().trim('"', '\'') + "\r\n"
            "cat" -> {
                val fileArg = parts.getOrNull(1).orEmpty()
                when {
                    fileArg.isEmpty() -> "cat: missing file operand\r\n"
                    fileArg.endsWith("deploy.yaml") -> "apiVersion: apps/v1\r\nkind: Deployment\r\nmetadata:\r\n  name: nexusts-gateway\r\n"
                    fileArg.endsWith("healthcheck.sh") -> "#!/usr/bin/env bash\r\ncurl -fsS http://127.0.0.1:8080/healthz\r\n"
                    else -> "cat: $fileArg: No such file or directory\r\n"
                }
            }
            "history" -> buildString {
                commandHistory.forEachIndexed { i, entry ->
                    append("  ${i + 1}  $entry\r\n")
                }
            }
            else -> "$head: command executed in offline terminal responder (type 'help' for built-ins)\r\n"
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
        if (bytes.isEmpty()) return
        asciicastRecorder.recordOutputChunk(bytes.decodeToString(throwOnInvalidSequence = false))
        if (vtHandle != 0L && SshNativeBridge.isNativeLoaded) {
            val snapJson = SshNativeBridge.nativeFeedTerminalBytes(vtHandle, bytes)
            if (snapJson.isNotBlank()) {
                runCatching {
                    _viewport.value = json.decodeFromString(TerminalSnapshotDto.serializer(), snapJson)
                    return
                }
            }
        }

        // Pure-Kotlin terminal fallback for local JVM previews/tests
        val raw = bytes.decodeToString(throwOnInvalidSequence = false)
        if (raw.contains("\u001B[2J")) {
            fallbackLines.clear()
        }
        val cleanAnsi = raw
            .replace(Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)"), "")
            .replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")
        if (fallbackLines.isEmpty()) {
            fallbackLines.addLast("")
        }
        var i = 0
        while (i < cleanAnsi.length) {
            when (val ch = cleanAnsi[i]) {
                '\r' -> {
                    if (i + 1 < cleanAnsi.length && cleanAnsi[i + 1] == '\n') {
                        // Handled on next char '\n'
                    }
                }
                '\n' -> {
                    fallbackLines.addLast("")
                    while (fallbackLines.size > _viewport.value.rows) {
                        val evicted = fallbackLines.removeFirst()
                        fallbackScrollback.addLast(evicted)
                        while (fallbackScrollback.size > 2_000) {
                            fallbackScrollback.removeFirst()
                        }
                    }
                }
                '\b' -> {
                    val cur = fallbackLines.removeLast()
                    if (cur.isNotEmpty()) {
                        fallbackLines.addLast(cur.dropLast(1))
                    } else {
                        fallbackLines.addLast("")
                    }
                    if (i + 2 < cleanAnsi.length && cleanAnsi[i + 1] == ' ' && cleanAnsi[i + 2] == '\b') {
                        i += 2
                    }
                }
                else -> {
                    if (ch >= ' ' || ch == '\t') {
                        val cur = fallbackLines.removeLast()
                        fallbackLines.addLast(cur + ch)
                    }
                }
            }
            i++
        }
        val lines = fallbackLines.toList()
        _viewport.value = _viewport.value.copy(
            visible_lines = lines,
            scrollback_lines = fallbackScrollback.toList(),
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
                var boundServer: ServerSocket? = null
                try {
                    val server = ServerSocket()
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(rule.bindAddress, rule.bindPort))
                    boundServer = server
                    synchronized(activeForwardSockets) {
                        activeForwardSockets += server
                        _activeBoundPortForwardsCount.value = activeForwardSockets.count { !it.isClosed }
                    }
                    while (isActive && !server.isClosed) {
                        val client = server.accept()
                        launch { relayForwardedSocket(client, rule) }
                    }
                } catch (_: Throwable) {
                    // Rule failed to bind (e.g., port in use); activeBoundPortForwardsCount is NOT incremented
                } finally {
                    boundServer?.let { srv ->
                        runCatching { srv.close() }
                        synchronized(activeForwardSockets) {
                            activeForwardSockets.remove(srv)
                            _activeBoundPortForwardsCount.value = activeForwardSockets.count { !it.isClosed }
                        }
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

    private fun readExactBytes(input: java.io.InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read <= 0) return buffer.copyOf(offset)
            offset += read
        }
        return buffer
    }

    private fun handleSocks5HandshakeAndRelay(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val ver = input.read()
        if (ver != 0x05) return
        val nMethods = input.read()
        if (nMethods > 0) readExactBytes(input, nMethods)
        // Respond NO AUTH REQUIRED (0x05, 0x00)
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        val header = readExactBytes(input, 4)
        if (header.size < 4 || header[1].toInt() != 0x01) return
        val targetHost = when (header[3].toInt()) {
            0x01 -> {
                val ipv4 = readExactBytes(input, 4)
                if (ipv4.size < 4) return
                ipv4.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                val len = input.read()
                if (len <= 0) return
                val domainBytes = readExactBytes(input, len)
                if (domainBytes.size < len) return
                String(domainBytes, Charsets.US_ASCII)
            }
            else -> return
        }
        val portBytes = readExactBytes(input, 2)
        if (portBytes.size < 2) return
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
        remoteOutputStream = null
        runCatching { activeSocket?.close() }
        activeSocket = null
        runCatching { localShellProcess?.destroyForcibly() }
        localShellProcess = null
        synchronized(activeForwardSockets) {
            activeForwardSockets.forEach { runCatching { it.close() } }
            activeForwardSockets.clear()
        }
        _activeBoundPortForwardsCount.value = 0
        scope.cancel()
        if (vtHandle != 0L && SshNativeBridge.isNativeLoaded) {
            SshNativeBridge.nativeDestroyTerminalBuffer(vtHandle)
            vtHandle = 0L
        }
    }
}
