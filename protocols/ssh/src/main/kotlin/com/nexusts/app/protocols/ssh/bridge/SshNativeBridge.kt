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
import kotlinx.coroutines.delay
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
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
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

@Serializable
data class NativeRemoteFileEntryDto(
    val name: String = "",
    val full_path: String = "",
    val is_directory: Boolean = false,
    val is_symlink: Boolean = false,
    val size_bytes: Long = 0L,
    val unix_mode: Int = 0b110_100_100,
    val modified_epoch_sec: Long = 0L,
    val owner: String = "0"
)

/**
 * Stateful RFC 854 / RFC 1073 (`NAWS`) / RFC 1091 (`TTYPE`) Telnet wire protocol state machine.
 * Safely reassembles `IAC` commands and `IAC SB ... IAC SE` subnegotiation sequences split across
 * arbitrary TCP read boundaries without dropping or leaking option bytes into terminal output.
 */
class StatefulTelnetProtocolAutomaton(
    private val terminalType: String = "xterm-256color",
    initialCols: Int = 120,
    initialRows: Int = 36
) {
    data class ProcessedTelnetChunk(
        val cleanPayload: ByteArray,
        val negotiationReply: ByteArray
    )

    private enum class ParserState {
        DATA,
        SAW_IAC,
        AWAIT_OPTION,
        IN_SB,
        IN_SB_IAC
    }

    @Volatile
    var cols: Int = initialCols.coerceIn(10, 1000)
        private set

    @Volatile
    var rows: Int = initialRows.coerceIn(4, 500)
        private set

    @Volatile
    var nawsAgreed: Boolean = false
        private set

    private var state: ParserState = ParserState.DATA
    private var pendingCommand: Int = 0
    private val sbBuffer = ByteArrayOutputStream(64)

    @Synchronized
    fun feedIncomingBytes(src: ByteArray, length: Int = src.size): ProcessedTelnetChunk {
        val cleanOut = ByteArrayOutputStream(length.coerceAtLeast(0))
        val replyOut = ByteArrayOutputStream(32)
        val limit = length.coerceIn(0, src.size)

        for (i in 0 until limit) {
            val b = src[i].toInt() and 0xFF
            when (state) {
                ParserState.DATA -> {
                    if (b == IAC) {
                        state = ParserState.SAW_IAC
                    } else {
                        cleanOut.write(b)
                    }
                }

                ParserState.SAW_IAC -> {
                    when (b) {
                        IAC -> {
                            cleanOut.write(IAC)
                            state = ParserState.DATA
                        }
                        DO, DONT, WILL, WONT -> {
                            pendingCommand = b
                            state = ParserState.AWAIT_OPTION
                        }
                        SB -> {
                            sbBuffer.reset()
                            state = ParserState.IN_SB
                        }
                        else -> {
                            state = ParserState.DATA
                        }
                    }
                }

                ParserState.AWAIT_OPTION -> {
                    handleOptionCommand(pendingCommand, b, replyOut)
                    state = ParserState.DATA
                }

                ParserState.IN_SB -> {
                    if (b == IAC) {
                        state = ParserState.IN_SB_IAC
                    } else if (sbBuffer.size() < 1024) {
                        sbBuffer.write(b)
                    }
                }

                ParserState.IN_SB_IAC -> {
                    when (b) {
                        IAC -> {
                            if (sbBuffer.size() < 1024) sbBuffer.write(IAC)
                            state = ParserState.IN_SB
                        }
                        SE -> {
                            handleSubnegotiation(sbBuffer.toByteArray(), replyOut)
                            sbBuffer.reset()
                            state = ParserState.DATA
                        }
                        else -> {
                            sbBuffer.reset()
                            state = ParserState.DATA
                        }
                    }
                }
            }
        }

        return ProcessedTelnetChunk(
            cleanPayload = cleanOut.toByteArray(),
            negotiationReply = replyOut.toByteArray()
        )
    }

    @Synchronized
    fun updateWindowSize(newCols: Int, newRows: Int): ByteArray? {
        cols = newCols.coerceIn(10, 1000)
        rows = newRows.coerceIn(4, 500)
        return if (nawsAgreed) buildNawsSubnegotiation(cols, rows) else null
    }

    fun encodeOutgoingBytes(raw: ByteArray): ByteArray {
        var hasIac = false
        for (b in raw) {
            if ((b.toInt() and 0xFF) == IAC) {
                hasIac = true
                break
            }
        }
        if (!hasIac) return raw
        val escaped = ByteArrayOutputStream(raw.size + 8)
        for (b in raw) {
            val v = b.toInt() and 0xFF
            escaped.write(v)
            if (v == IAC) escaped.write(IAC)
        }
        return escaped.toByteArray()
    }

    private fun handleOptionCommand(cmd: Int, opt: Int, replyOut: ByteArrayOutputStream) {
        when (cmd) {
            DO -> when (opt) {
                OPT_TTYPE -> {
                    replyOut.write(IAC)
                    replyOut.write(WILL)
                    replyOut.write(OPT_TTYPE)
                }
                OPT_NAWS -> {
                    nawsAgreed = true
                    replyOut.write(IAC)
                    replyOut.write(WILL)
                    replyOut.write(OPT_NAWS)
                    val nawsFrame = buildNawsSubnegotiation(cols, rows)
                    replyOut.write(nawsFrame, 0, nawsFrame.size)
                }
                OPT_SGA -> {
                    replyOut.write(IAC)
                    replyOut.write(WILL)
                    replyOut.write(OPT_SGA)
                }
                else -> {
                    replyOut.write(IAC)
                    replyOut.write(WONT)
                    replyOut.write(opt)
                }
            }

            DONT -> {
                if (opt == OPT_NAWS) nawsAgreed = false
                replyOut.write(IAC)
                replyOut.write(WONT)
                replyOut.write(opt)
            }

            WILL -> {
                if (opt == OPT_ECHO || opt == OPT_SGA) {
                    replyOut.write(IAC)
                    replyOut.write(DO)
                    replyOut.write(opt)
                } else {
                    replyOut.write(IAC)
                    replyOut.write(DONT)
                    replyOut.write(opt)
                }
            }

            WONT -> {
                replyOut.write(IAC)
                replyOut.write(DONT)
                replyOut.write(opt)
            }
        }
    }

    private fun handleSubnegotiation(payload: ByteArray, replyOut: ByteArrayOutputStream) {
        if (payload.size >= 2 &&
            (payload[0].toInt() and 0xFF) == OPT_TTYPE &&
            (payload[1].toInt() and 0xFF) == TTYPE_SEND
        ) {
            val termBytes = terminalType.ifBlank { "xterm-256color" }
                .toByteArray(StandardCharsets.US_ASCII)
            replyOut.write(IAC)
            replyOut.write(SB)
            replyOut.write(OPT_TTYPE)
            replyOut.write(TTYPE_IS)
            for (tb in termBytes) {
                val v = tb.toInt() and 0xFF
                replyOut.write(v)
                if (v == IAC) replyOut.write(IAC)
            }
            replyOut.write(IAC)
            replyOut.write(SE)
        }
    }

    companion object {
        const val IAC = 255
        const val SE = 240
        const val SB = 250
        const val WILL = 251
        const val WONT = 252
        const val DO = 253
        const val DONT = 254

        const val OPT_ECHO = 1
        const val OPT_SGA = 3
        const val OPT_TTYPE = 24
        const val OPT_NAWS = 31

        const val TTYPE_IS = 0
        const val TTYPE_SEND = 1

        fun buildNawsSubnegotiation(cols: Int, rows: Int): ByteArray {
            val out = ByteArrayOutputStream(12)
            out.write(IAC)
            out.write(SB)
            out.write(OPT_NAWS)
            val dataBytes = intArrayOf(
                (cols ushr 8) and 0xFF,
                cols and 0xFF,
                (rows ushr 8) and 0xFF,
                rows and 0xFF
            )
            for (v in dataBytes) {
                out.write(v)
                if (v == IAC) out.write(IAC)
            }
            out.write(IAC)
            out.write(SE)
            return out.toByteArray()
        }
    }
}

/**
 * JNI Bridge to Rust `nexusts-terminal` & `nexusts-transfer` (`libnexusts_jni.so`), with pure-Kotlin
 * fallback terminal & Telnet state machines when native library is absent in JVM unit test environments.
 */
object SshNativeBridge {
    val isNativeLoaded: Boolean = runCatching {
        System.loadLibrary("nexusts_jni")
        true
    }.getOrDefault(false)

    // --- VT Terminal Buffer JNI ---
    @JvmStatic
    external fun nativeCreateTerminalBuffer(cols: Int, rows: Int, scrollback: Int): Long

    @JvmStatic
    external fun nativeResizeTerminalBuffer(handle: Long, cols: Int, rows: Int)

    @JvmStatic
    external fun nativeFeedTerminalBytes(handle: Long, data: ByteArray): String

    @JvmStatic
    external fun nativeVtApplicationCursorKeys(handle: Long): Boolean

    @JvmStatic
    external fun nativeDestroyTerminalBuffer(handle: Long)

    @JvmStatic
    external fun nativeParseFtpMlsdJson(parentDir: String, mlsdBody: String): String

    // --- Live SSHv2 (`russh`) JNI ---
    @JvmStatic
    external fun nativeSshConnect(
        host: String,
        port: Int,
        username: String,
        password: String,
        privateKeyPem: String,
        passphrase: String,
        expectedFingerprint: String,
        cols: Int,
        rows: Int,
        enableX11: Boolean,
        startupCommand: String,
        bastionHost: String,
        bastionPort: Int,
        bastionUser: String,
        bastionPassword: String,
        bastionKeyPem: String
    ): Long

    @JvmStatic
    external fun nativeSshConnectJson(
        connectSpecJson: String,
        bastionSpecJson: String,
        openPty: Boolean
    ): Long

    @JvmStatic
    external fun nativeSshStartLocalForward(
        handle: Long,
        bindAddr: String,
        bindPort: Int,
        targetHost: String,
        targetPort: Int
    ): Boolean

    @JvmStatic
    external fun nativeSshStartDynamicSocks5(
        handle: Long,
        bindAddr: String,
        bindPort: Int
    ): Boolean

    @JvmStatic
    external fun nativeSshWrite(handle: Long, data: ByteArray): Boolean

    @JvmStatic
    external fun nativeSshRead(handle: Long): ByteArray?

    @JvmStatic
    external fun nativeSshResize(handle: Long, cols: Int, rows: Int): Boolean

    @JvmStatic
    external fun nativeSshIsConnected(handle: Long): Boolean

    @JvmStatic
    external fun nativeSshLastError(handle: Long): String

    @JvmStatic
    external fun nativeSshDisconnect(handle: Long)

    // --- Live Stateful Telnet JNI ---
    @JvmStatic
    external fun nativeTelnetConnect(
        host: String,
        port: Int,
        cols: Int,
        rows: Int,
        termType: String
    ): Long

    @JvmStatic
    external fun nativeTelnetWrite(handle: Long, data: ByteArray): Boolean

    @JvmStatic
    external fun nativeTelnetRead(handle: Long): ByteArray?

    @JvmStatic
    external fun nativeTelnetResize(handle: Long, cols: Int, rows: Int): Boolean

    @JvmStatic
    external fun nativeTelnetIsConnected(handle: Long): Boolean

    @JvmStatic
    external fun nativeTelnetLastError(handle: Long): String

    @JvmStatic
    external fun nativeTelnetDisconnect(handle: Long)

    // --- Live Unified File Transfer JNI (SFTP / SCP / FTP) ---
    @JvmStatic
    external fun nativeTransferConnect(
        protocol: String,
        host: String,
        port: Int,
        username: String,
        password: String,
        privateKeyPem: String,
        passphrase: String,
        bastionHost: String,
        bastionPort: Int,
        bastionUser: String,
        bastionPassword: String,
        bastionKeyPem: String
    ): Long

    @JvmStatic
    external fun nativeTransferLastError(handle: Long): String

    @JvmStatic
    external fun nativeTransferPwd(handle: Long): String

    @JvmStatic
    external fun nativeTransferListDirJson(handle: Long, remotePath: String): String

    @JvmStatic
    external fun nativeTransferReadFile(handle: Long, remotePath: String): ByteArray?

    @JvmStatic
    external fun nativeTransferWriteFile(
        handle: Long,
        remotePath: String,
        content: ByteArray,
        modeOctal: Int
    ): Boolean

    @JvmStatic
    external fun nativeTransferMkdir(handle: Long, remotePath: String): Boolean

    @JvmStatic
    external fun nativeTransferRemove(handle: Long, remotePath: String, isDir: Boolean): Boolean

    @JvmStatic
    external fun nativeTransferRename(handle: Long, oldPath: String, newPath: String): Boolean

    @JvmStatic
    external fun nativeTransferChmod(handle: Long, remotePath: String, modeOctal: Int): Boolean

    @JvmStatic
    external fun nativeTransferDisconnect(handle: Long)

    /**
     * Translates Android hardware / virtual `KeyEvent` codes & modifiers into standard xterm ANSI byte sequences,
     * honoring `DECCKM` (`applicationCursorKeys`: `\u001bOA..D` vs `\u001b[A..D`) when active.
     */
    @JvmOverloads
    fun translateHardwareKeyToAnsi(
        keyCode: Int,
        unicodeChar: Int,
        ctrlPressed: Boolean,
        altPressed: Boolean,
        applicationCursorKeys: Boolean = false
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
            KeyEvent.KEYCODE_DPAD_UP ->
                (if (applicationCursorKeys) "\u001bOA" else "\u001b[A").toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_DPAD_DOWN ->
                (if (applicationCursorKeys) "\u001bOB" else "\u001b[B").toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                (if (applicationCursorKeys) "\u001bOC" else "\u001b[C").toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_DPAD_LEFT ->
                (if (applicationCursorKeys) "\u001bOD" else "\u001b[D").toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_MOVE_HOME ->
                (if (applicationCursorKeys) "\u001bOH" else "\u001b[H").toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_MOVE_END ->
                (if (applicationCursorKeys) "\u001bOF" else "\u001b[F").toByteArray(Charsets.UTF_8)
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

@JvmOverloads
fun translateHardwareKeyToAnsi(
    keyCode: Int,
    unicodeChar: Int,
    ctrlPressed: Boolean,
    altPressed: Boolean,
    applicationCursorKeys: Boolean = false
): ByteArray? = SshNativeBridge.translateHardwareKeyToAnsi(
    keyCode = keyCode,
    unicodeChar = unicodeChar,
    ctrlPressed = ctrlPressed,
    altPressed = altPressed,
    applicationCursorKeys = applicationCursorKeys
)

/**
 * Manages an interactive SSHv2 (`russh`), Telnet (RFC 854/NAWS/TTYPE), or Local Shell terminal session,
 * local/dynamic SOCKS5 port forwards, X11 display bridge, and `xterm-256color` viewport state.
 */
class SshTerminalSessionController(
    val title: String,
    val host: String,
    val port: Int,
    val username: String,
    val portForwards: List<SshPortForwardRule> = emptyList(),
    val enableX11Forwarding: Boolean = false,
    val bastionHost: String? = null,
    val isLocalShell: Boolean = false,
    val isTelnet: Boolean = false,
    val password: String = "",
    val privateKeyPem: String = "",
    val privateKeyPassphrase: String = "",
    val expectedHostFingerprint: String = "",
    val startupCommand: String = "",
    val terminalType: String = "xterm-256color",
    val scrollbackLines: Int = 10_000,
    val bastionPort: Int = 22,
    val bastionUsername: String = "",
    val bastionPassword: String = "",
    val bastionPrivateKeyPem: String = "",
    private val tunnelLease: Closeable? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var vtHandle: Long = if (SshNativeBridge.isNativeLoaded) {
        SshNativeBridge.nativeCreateTerminalBuffer(120, 36, scrollbackLines.coerceAtLeast(500))
    } else {
        0L
    }

    @Volatile
    private var nativeSshHandle: Long = 0L

    @Volatile
    private var nativeTelnetHandle: Long = 0L

    @Volatile
    private var fallbackApplicationCursorKeys: Boolean = false

    @Volatile
    private var telnetAutomaton: StatefulTelnetProtocolAutomaton? = null

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
            title = if (isLocalShell) title else "$username@$host:$port",
            visible_lines = buildInitialBanner()
        )
    )
    val viewport: StateFlow<TerminalSnapshotDto> = _viewport.asStateFlow()

    init {
        val bannerBytes = (buildInitialBanner().joinToString("\r\n") + "\r\n").encodeToByteArray()
        onBytesReceived(bannerBytes)
        startSessionTransport()
    }

    fun isApplicationCursorKeys(): Boolean {
        if (vtHandle != 0L && SshNativeBridge.isNativeLoaded) {
            return SshNativeBridge.nativeVtApplicationCursorKeys(vtHandle)
        }
        return fallbackApplicationCursorKeys
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
        when {
            isLocalShell -> {
                add("\u001B[1;36mNexusTS Local Shell ($terminalType)\u001B[0m")
            }
            isTelnet -> {
                add("\u001B[1;36mNexusTS Stateful Telnet ($terminalType)\u001B[0m")
                add("Target Endpoint   : $host:$port")
            }
            else -> {
                add("\u001B[1;36mNexusTS Enterprise SSHv2 / $terminalType Engine\u001B[0m")
                if (!bastionHost.isNullOrBlank()) {
                    add("Bastion Jump Host : $bastionHost:$bastionPort -> $host:$port")
                } else {
                    add("Target Endpoint   : ${username.ifBlank { "root" }}@$host:$port")
                }
                if (enableX11Forwarding) {
                    add("X11 Forwarding    : ENABLED (DISPLAY=localhost:10.0)")
                }
                val enabledTunnels = portForwards.count { it.enabled }
                if (enabledTunnels > 0) {
                    add("Configured Tunnels: $enabledTunnels rule(s)")
                }
            }
        }
    }

    private fun startSessionTransport() {
        val trimmedHost = host.trim()
        val effectiveIsTelnet = !isLocalShell && (isTelnet || (port == 23 && title.contains("telnet", ignoreCase = true)))

        if (isLocalShell) {
            transportJob = scope.launch {
                val startedLocal = runCatching {
                    val shellBinary = listOf("/system/bin/sh", "/bin/sh")
                        .firstOrNull { File(it).canExecute() }
                        ?: error("No local shell binary available")
                    val proc = ProcessBuilder(shellBinary)
                        .redirectErrorStream(true)
                        .apply { environment()["TERM"] = terminalType.ifBlank { "xterm-256color" } }
                        .start()
                    localShellProcess = proc
                    localPipeEchoEnabled = true
                    remoteOutputStream = proc.outputStream
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
            return
        }

        if (effectiveIsTelnet) {
            attachTelnetOrRawStream(trimmedHost, if (port > 0) port else 23)
            return
        }

        // Live SSHv2 transport
        if (SshNativeBridge.isNativeLoaded && trimmedHost.isNotEmpty()) {
            transportJob = scope.launch {
                val snap = _viewport.value
                val handle = SshNativeBridge.nativeSshConnect(
                    host = trimmedHost,
                    port = if (port > 0) port else 22,
                    username = username.ifBlank { "root" },
                    password = password,
                    privateKeyPem = privateKeyPem,
                    passphrase = privateKeyPassphrase,
                    expectedFingerprint = expectedHostFingerprint,
                    cols = snap.cols,
                    rows = snap.rows,
                    enableX11 = enableX11Forwarding,
                    startupCommand = startupCommand,
                    bastionHost = bastionHost.orEmpty(),
                    bastionPort = if (bastionPort > 0) bastionPort else 22,
                    bastionUser = bastionUsername,
                    bastionPassword = bastionPassword,
                    bastionKeyPem = bastionPrivateKeyPem
                )

                if (handle <= 0L) {
                    val errMsg = SshNativeBridge.nativeSshLastError(0L).ifBlank {
                        "SSHv2 connection failed for $trimmedHost:$port"
                    }
                    onBytesReceived("\u001B[1;31m[SSH Error] $errMsg\u001B[0m\r\n".encodeToByteArray())
                    return@launch
                }

                nativeSshHandle = handle
                var startedNativeForwards = 0
                portForwards.filter { it.enabled }.forEach { rule ->
                    val ok = when (rule.kind) {
                        PortForwardKind.LOCAL -> SshNativeBridge.nativeSshStartLocalForward(
                            handle = handle,
                            bindAddr = rule.bindAddress.ifBlank { "127.0.0.1" },
                            bindPort = rule.bindPort,
                            targetHost = rule.targetHost.ifBlank { "127.0.0.1" },
                            targetPort = rule.targetPort
                        )
                        PortForwardKind.DYNAMIC_SOCKS5 -> SshNativeBridge.nativeSshStartDynamicSocks5(
                            handle = handle,
                            bindAddr = rule.bindAddress.ifBlank { "127.0.0.1" },
                            bindPort = rule.bindPort
                        )
                        PortForwardKind.REMOTE -> false
                    }
                    if (ok) {
                        startedNativeForwards++
                    } else if (rule.kind != PortForwardKind.REMOTE) {
                        val fwdErr = SshNativeBridge.nativeSshLastError(handle).ifBlank { "bind failed" }
                        onBytesReceived(
                            "\u001B[1;33m[Port Forward Warning] ${rule.bindAddress}:${rule.bindPort} ($fwdErr)\u001B[0m\r\n".encodeToByteArray()
                        )
                    }
                }
                _activeBoundPortForwardsCount.value = startedNativeForwards

                while (isActive && nativeSshHandle == handle) {
                    val chunk = SshNativeBridge.nativeSshRead(handle) ?: break
                    if (chunk.isNotEmpty()) {
                        onBytesReceived(chunk)
                    } else {
                        delay(12L)
                    }
                }

                if (isActive && nativeSshHandle == handle) {
                    val tailErr = SshNativeBridge.nativeSshLastError(handle)
                    if (tailErr.isNotBlank()) {
                        onBytesReceived("\r\n\u001B[1;31m[SSH Disconnected] $tailErr\u001B[0m\r\n".encodeToByteArray())
                    } else {
                        onBytesReceived("\r\n\u001B[90m[SSH session closed]\u001B[0m\r\n".encodeToByteArray())
                    }
                }
            }
            return
        }

        // Pure-Kotlin JVM fallback when libnexusts_jni.so is not loaded (e.g. Robolectric / host JVM unit tests)
        startConfiguredLocalTunnels()
        if (trimmedHost.isNotEmpty()) {
            transportJob = scope.launch {
                runCatching {
                    val probe = Socket()
                    probe.connect(InetSocketAddress(trimmedHost, if (port > 0) port else 22), 3_500)
                    probe.soTimeout = 1_500
                    val bannerBuf = ByteArray(256)
                    val n = runCatching { probe.getInputStream().read(bannerBuf) }.getOrDefault(0)
                    val banner = if (n > 0) {
                        String(bannerBuf, 0, n, Charsets.UTF_8).trim()
                    } else {
                        ""
                    }
                    if (banner.isNotEmpty() && !banner.startsWith("SSH-")) {
                        val automaton = StatefulTelnetProtocolAutomaton(
                            terminalType = terminalType,
                            initialCols = _viewport.value.cols,
                            initialRows = _viewport.value.rows
                        )
                        telnetAutomaton = automaton
                        activeSocket = probe
                        probe.soTimeout = 0
                        val out = probe.getOutputStream()
                        remoteOutputStream = out
                        val firstChunk = automaton.feedIncomingBytes(bannerBuf, n)
                        if (firstChunk.negotiationReply.isNotEmpty()) {
                            out.write(firstChunk.negotiationReply)
                            out.flush()
                        }
                        if (firstChunk.cleanPayload.isNotEmpty()) {
                            onBytesReceived(firstChunk.cleanPayload)
                        }
                        val streamBuf = ByteArray(4096)
                        val input = probe.getInputStream()
                        while (isActive && !probe.isClosed) {
                            val r = input.read(streamBuf)
                            if (r <= 0) break
                            val step = automaton.feedIncomingBytes(streamBuf, r)
                            if (step.negotiationReply.isNotEmpty()) {
                                out.write(step.negotiationReply)
                                out.flush()
                            }
                            if (step.cleanPayload.isNotEmpty()) {
                                onBytesReceived(step.cleanPayload)
                            }
                        }
                    } else {
                        probe.close()
                        if (banner.startsWith("SSH-")) {
                            onBytesReceived("\u001B[90m[Remote SSHv2 Server: $banner]\u001B[0m\r\n".encodeToByteArray())
                        }
                        onBytesReceived(buildPrompt().encodeToByteArray())
                    }
                }.onFailure { err ->
                    val reason = err.message?.takeIf { it.isNotBlank() } ?: err::class.java.simpleName
                    onBytesReceived(
                        "\u001B[1;33m[Transport Notice] Could not reach $trimmedHost:$port ($reason).\u001B[0m\r\n".encodeToByteArray()
                    )
                    onBytesReceived(buildPrompt().encodeToByteArray())
                }
            }
        } else {
            onBytesReceived(buildPrompt().encodeToByteArray())
        }
    }

    private fun normalizeLfToCrLf(src: ByteArray, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length + 16)
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

    fun resizePty(cols: Int, rows: Int) {
        val safeCols = cols.coerceAtLeast(10)
        val safeRows = rows.coerceAtLeast(4)
        asciicastRecorder.recordResize(safeCols, safeRows)

        val sshH = nativeSshHandle
        if (sshH != 0L && SshNativeBridge.isNativeLoaded) {
            SshNativeBridge.nativeSshResize(sshH, safeCols, safeRows)
        }
        val telnetH = nativeTelnetHandle
        if (telnetH != 0L && SshNativeBridge.isNativeLoaded) {
            SshNativeBridge.nativeTelnetResize(telnetH, safeCols, safeRows)
        }
        telnetAutomaton?.updateWindowSize(safeCols, safeRows)?.let { nawsBytes ->
            remoteOutputStream?.let { out ->
                scope.launch {
                    runCatching {
                        synchronized(out) {
                            out.write(nawsBytes)
                            out.flush()
                        }
                    }
                }
            }
        }

        if (vtHandle != 0L && SshNativeBridge.isNativeLoaded) {
            SshNativeBridge.nativeResizeTerminalBuffer(vtHandle, safeCols, safeRows)
            val refreshed = SshNativeBridge.nativeFeedTerminalBytes(vtHandle, ByteArray(0))
            if (refreshed.isNotBlank()) {
                runCatching {
                    _viewport.value = json.decodeFromString(TerminalSnapshotDto.serializer(), refreshed)
                }
            }
        } else {
            _viewport.value = _viewport.value.copy(cols = safeCols, rows = safeRows)
        }
    }

    fun attachTelnetOrRawStream(host: String, port: Int) {
        transportJob?.cancel()
        localPipeEchoEnabled = false

        if (SshNativeBridge.isNativeLoaded) {
            transportJob = scope.launch {
                val snap = _viewport.value
                val handle = SshNativeBridge.nativeTelnetConnect(
                    host = host.trim(),
                    port = if (port > 0) port else 23,
                    cols = snap.cols,
                    rows = snap.rows,
                    termType = terminalType.ifBlank { "xterm-256color" }
                )
                if (handle <= 0L) {
                    val err = SshNativeBridge.nativeTelnetLastError(0L).ifBlank { "Telnet connect failed" }
                    onBytesReceived("\r\n\u001B[1;31m[Telnet Error] $err\u001B[0m\r\n".encodeToByteArray())
                    return@launch
                }
                nativeTelnetHandle = handle
                onBytesReceived("\u001B[1;32m[Connected Telnet $host:$port]\u001B[0m\r\n".encodeToByteArray())
                while (isActive && nativeTelnetHandle == handle) {
                    val chunk = SshNativeBridge.nativeTelnetRead(handle) ?: break
                    if (chunk.isNotEmpty()) {
                        onBytesReceived(chunk)
                    } else {
                        delay(12L)
                    }
                }
                if (isActive && nativeTelnetHandle == handle) {
                    val tailErr = SshNativeBridge.nativeTelnetLastError(handle)
                    if (tailErr.isNotBlank()) {
                        onBytesReceived("\r\n\u001B[1;33m[Telnet Disconnected] $tailErr\u001B[0m\r\n".encodeToByteArray())
                    }
                }
            }
            return
        }

        // Pure-Kotlin stateful RFC 854 / NAWS / TTYPE Telnet socket fallback
        transportJob = scope.launch {
            runCatching {
                val snap = _viewport.value
                val automaton = StatefulTelnetProtocolAutomaton(
                    terminalType = terminalType.ifBlank { "xterm-256color" },
                    initialCols = snap.cols,
                    initialRows = snap.rows
                )
                telnetAutomaton = automaton
                Socket().use { socket ->
                    activeSocket = socket
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, if (port > 0) port else 23), 6_000)
                    val out = socket.getOutputStream()
                    remoteOutputStream = out
                    onBytesReceived("\u001B[1;32m[Connected Telnet $host:$port]\u001B[0m\r\n".encodeToByteArray())
                    val input: InputStream = socket.getInputStream()
                    val buffer = ByteArray(4096)
                    while (isActive && !socket.isClosed) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val processed = automaton.feedIncomingBytes(buffer, read)
                        if (processed.negotiationReply.isNotEmpty()) {
                            synchronized(out) {
                                out.write(processed.negotiationReply)
                                out.flush()
                            }
                        }
                        if (processed.cleanPayload.isNotEmpty()) {
                            onBytesReceived(processed.cleanPayload)
                        }
                    }
                }
            }.onFailure { err ->
                remoteOutputStream = null
                activeSocket = null
                telnetAutomaton = null
                onBytesReceived(
                    "\r\n\u001B[1;33m[Endpoint $host:$port unreachable (${err.message ?: "offline"}) — interactive shell active]\u001B[0m\r\n${buildPrompt()}".encodeToByteArray()
                )
            }
        }
    }

    /**
     * Dispatches user input bytes to the active remote SSH/Telnet session, local shell, or offline responder.
     * Never writes raw user keystrokes into `asciicastRecorder` — only echoed/terminal output via `onBytesReceived` is recorded.
     */
    fun sendUserBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return

        val sshH = nativeSshHandle
        if (sshH != 0L && SshNativeBridge.isNativeLoaded) {
            scope.launch {
                SshNativeBridge.nativeSshWrite(sshH, bytes)
            }
            return
        }

        val telnetH = nativeTelnetHandle
        if (telnetH != 0L && SshNativeBridge.isNativeLoaded) {
            scope.launch {
                SshNativeBridge.nativeTelnetWrite(telnetH, bytes)
            }
            return
        }

        val out = remoteOutputStream
        if (out != null) {
            scope.launch {
                runCatching {
                    if (localPipeEchoEnabled) {
                        // Clean local /system/bin/sh pipe forwarding without corrupting interactive programs via injected printf
                        val pipeOut = ByteArrayOutputStream(bytes.size + 4)
                        val echoOut = ByteArrayOutputStream(bytes.size + 4)
                        for (b in bytes) {
                            when (val code = b.toInt() and 0xFF) {
                                '\r'.code -> {
                                    pipeOut.write('\n'.code)
                                    echoOut.write('\r'.code)
                                    echoOut.write('\n'.code)
                                }
                                0x7F, 0x08 -> {
                                    pipeOut.write(code)
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
                            synchronized(out) {
                                out.write(toShell)
                                out.flush()
                            }
                        }
                    } else {
                        val wireBytes = telnetAutomaton?.encodeOutgoingBytes(bytes) ?: bytes
                        synchronized(out) {
                            out.write(wireBytes)
                            out.flush()
                        }
                    }
                }.onFailure {
                    remoteOutputStream = null
                    handleOfflineInteractiveInput(bytes)
                }
            }
        } else if (!SshNativeBridge.isNativeLoaded) {
            scope.launch {
                handleOfflineInteractiveInput(bytes)
            }
        }
    }

    private fun handleOfflineInteractiveInput(bytes: ByteArray) {
        val str = bytes.decodeToString(throwOnInvalidSequence = false)
        if (str == "\u001b[A" || str == "\u001bOA") {
            if (commandHistory.isNotEmpty()) {
                if (historyIndex < 0) historyIndex = commandHistory.lastIndex
                else if (historyIndex > 0) historyIndex--
                replaceInteractiveLine(commandHistory[historyIndex])
            }
            return
        }
        if (str == "\u001b[B" || str == "\u001bOB") {
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
                append("TERM=$terminalType\r\n")
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
        if (raw.contains("\u001B[?1h")) {
            fallbackApplicationCursorKeys = true
        }
        if (raw.contains("\u001B[?1l")) {
            fallbackApplicationCursorKeys = false
        }
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
     * Starts Local TCP & Dynamic SOCKS5 listeners on loopback when native `russh` is unavailable.
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
                } catch (bindErr: Throwable) {
                    if (boundServer == null) {
                        val reason = bindErr.message?.takeIf { it.isNotBlank() } ?: bindErr::class.java.simpleName
                        onBytesReceived(
                            "\u001B[1;31m[Port Forward Error] Could not bind ${rule.bindAddress}:${rule.bindPort}: $reason\u001B[0m\r\n".encodeToByteArray()
                        )
                    }
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

    private fun readExactBytes(input: InputStream, length: Int): ByteArray {
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
        val sshH = nativeSshHandle
        nativeSshHandle = 0L
        if (sshH != 0L && SshNativeBridge.isNativeLoaded) {
            runCatching { SshNativeBridge.nativeSshDisconnect(sshH) }
        }

        val telnetH = nativeTelnetHandle
        nativeTelnetHandle = 0L
        if (telnetH != 0L && SshNativeBridge.isNativeLoaded) {
            runCatching { SshNativeBridge.nativeTelnetDisconnect(telnetH) }
        }

        remoteOutputStream = null
        telnetAutomaton = null
        runCatching { activeSocket?.close() }
        activeSocket = null
        runCatching { localShellProcess?.destroyForcibly() }
        localShellProcess = null
        runCatching { tunnelLease?.close() }
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
