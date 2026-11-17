package com.nexusts.app.protocols.rdp.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.view.KeyEvent
import android.view.Surface
import androidx.core.content.ContextCompat
import com.nexusts.app.core.ui.input.RawHardwareKeyPacket
import com.nexusts.app.core.vault.credentials.ResolvedSessionSecret
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.RdpAudioRedirectionMode
import com.nexusts.app.core.vault.model.RdpGfxCodecProfile
import com.nexusts.app.core.vault.model.RdpResizeBehavior
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.protocols.rdp.storage.SafRdpSharedDriveSynchronizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JNI Bridge to `libnexusts_rdp_jni.so` (FreeRDP 3.x + Adaptive RDPGFX/H.264 + AAudio + DISP_DVC + RDPDR).
 */
object FreeRdpNativeBridge {
    val isNativeLoaded: Boolean = runCatching {
        System.loadLibrary("nexusts_rdp_jni")
        true
    }.getOrDefault(false)

    // MS-RDPBCGR Pointer Event Flags
    const val PTRFLAGS_HWHEEL = 0x0400
    const val PTRFLAGS_WHEEL = 0x0200
    const val PTRFLAGS_WHEEL_NEGATIVE = 0x0100
    const val PTRFLAGS_MOVE = 0x0800
    const val PTRFLAGS_DOWN = 0x8000
    const val PTRFLAGS_BUTTON1_LEFT = 0x1000
    const val PTRFLAGS_BUTTON2_RIGHT = 0x2000
    const val PTRFLAGS_BUTTON3_MIDDLE = 0x4000

    // MS-RDPBCGR Extended Pointer Event Flags (Mouse Button 4 Back / Button 5 Forward)
    const val PTRXFLAGS_DOWN = 0x8000
    const val PTRXFLAGS_BUTTON1_BACK = 0x0001
    const val PTRXFLAGS_BUTTON2_FORWARD = 0x0002

    /**
     * Encodes MS-RDPBCGR 9-bit two's-complement wheel rotation flags.
     * Negative rotation uses `PTRFLAGS_WHEEL_NEGATIVE | ((-steps) & 0x00FF)` (e.g. `0x0188` for `-120`).
     */
    fun encodeMouseWheelFlags(steps: Int, negative: Boolean): Int {
        val magnitude = kotlin.math.abs(steps).coerceIn(1, 255)
        val rotationBits = if (negative) {
            PTRFLAGS_WHEEL_NEGATIVE or ((-magnitude) and 0x00FF)
        } else {
            magnitude and 0x00FF
        }
        return PTRFLAGS_WHEEL or rotationBits
    }

    fun encodeHorizontalMouseWheelFlags(steps: Int, negative: Boolean): Int {
        val magnitude = kotlin.math.abs(steps).coerceIn(1, 255)
        val rotationBits = if (negative) {
            PTRFLAGS_WHEEL_NEGATIVE or ((-magnitude) and 0x00FF)
        } else {
            magnitude and 0x00FF
        }
        return PTRFLAGS_HWHEEL or rotationBits
    }

    @JvmStatic
    external fun nativeCreateSession(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String,
        gatewayHost: String,
        gatewayPort: Int,
        initialWidth: Int,
        initialHeight: Int,
        connectToConsole: Boolean,
        hiDpiEnabled: Boolean,
        desktopScalePercent: Int,
        colorDepthBits: Int,
        gfxCodecMode: Int,
        enableH264Avc444: Boolean,
        enableRemoteFx: Boolean,
        enableDynamicResize: Boolean,
        multiMonitor: Boolean,
        redirectClipboard: Boolean,
        audioRedirectionMode: Int,
        redirectMic: Boolean,
        redirectDrives: Boolean,
        redirectPrinters: Boolean,
        redirectPorts: Boolean,
        redirectSmartcard: Boolean,
        enableFontSmoothing: Boolean,
        enableDesktopComposition: Boolean,
        enableWallpaper: Boolean,
        compressionEnabled: Boolean,
        nlaSecurity: Boolean,
        restrictedAdminMode: Boolean,
        ignoreCertificate: Boolean,
        remoteAppMode: Boolean,
        remoteAppProgram: String,
        remoteAppArgs: String,
        sharedDriveName: String,
        sharedDriveLocalPath: String,
        appHomeDir: String
    ): Long

    @JvmStatic
    external fun nativeConnect(handle: Long): Boolean

    @JvmStatic
    external fun nativeBindSurface(handle: Long, surface: Surface?)

    @JvmStatic
    external fun nativeRequestDynamicResize(
        handle: Long,
        widthPx: Int,
        heightPx: Int,
        desktopScalePercent: Int
    ): Boolean

    @JvmStatic
    external fun nativeSendKeyScancode(
        handle: Long,
        rdpScancode: Int,
        keyDown: Boolean,
        extended: Boolean
    )

    @JvmStatic
    external fun nativeReleaseAllKeys(handle: Long)

    @JvmStatic
    external fun nativeSendMouseEvent(
        handle: Long,
        pointerFlags: Int,
        x: Int,
        y: Int
    )

    @JvmStatic
    external fun nativeSendExtendedMouseEvent(
        handle: Long,
        xflags: Int,
        x: Int,
        y: Int
    )

    @JvmStatic
    external fun nativeSyncClipboardText(handle: Long, utf8Text: String)

    @JvmStatic
    external fun nativePullRemoteClipboardText(handle: Long): String

    @JvmStatic
    external fun nativeGetRemoteClipboardSequence(handle: Long): Long

    @JvmStatic
    external fun nativeGetLastError(handle: Long): String

    @JvmStatic
    external fun nativeIsConnected(handle: Long): Boolean

    @JvmStatic
    external fun nativeIsReconnecting(handle: Long): Boolean

    @JvmStatic
    external fun nativeGetReconnectAttempt(handle: Long): Int

    @JvmStatic
    external fun nativeGetDesktopDimensions(handle: Long): Long

    @JvmStatic
    external fun nativeDestroySession(handle: Long)
}

/**
 * Translates Android `KeyEvent` keyCodes & PC keyboard keys into PC XT Set-1 RDP Scan Codes,
 * preserving full Win/Meta, Alt, Ctrl, and Function F1–F12 key passthrough while never swallowing
 * host OS screenshot, volume, power, or system keys.
 */
object RdpScanCodeMapper {
    data class MappedScanCode(val scanCode: Int, val extended: Boolean, val needsShift: Boolean = false)

    fun mapKeyPacket(packet: RawHardwareKeyPacket): MappedScanCode? {
        if (com.nexusts.app.core.ui.input.isSystemPassThroughKey(
                keyCode = packet.keyCode,
                scanCode = packet.scanCode,
                isCtrlPressed = packet.ctrlPressed,
                isAltPressed = packet.altPressed,
                isShiftPressed = packet.shiftPressed,
                isMetaPressed = packet.metaWinCmdPressed
            )
        ) {
            return null
        }
        return mapAndroidKeyCode(packet.keyCode, packet.scanCode)
    }

    fun mapAndroidKeyCode(keyCode: Int, scanCode: Int = 0): MappedScanCode? {
        @Suppress("UNUSED_PARAMETER")
        val ignoredScan = scanCode
        return when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> MappedScanCode(0x01, false)
            KeyEvent.KEYCODE_1 -> MappedScanCode(0x02, false)
            KeyEvent.KEYCODE_2 -> MappedScanCode(0x03, false)
            KeyEvent.KEYCODE_3 -> MappedScanCode(0x04, false)
            KeyEvent.KEYCODE_4 -> MappedScanCode(0x05, false)
            KeyEvent.KEYCODE_5 -> MappedScanCode(0x06, false)
            KeyEvent.KEYCODE_6 -> MappedScanCode(0x07, false)
            KeyEvent.KEYCODE_7 -> MappedScanCode(0x08, false)
            KeyEvent.KEYCODE_8 -> MappedScanCode(0x09, false)
            KeyEvent.KEYCODE_9 -> MappedScanCode(0x0A, false)
            KeyEvent.KEYCODE_0 -> MappedScanCode(0x0B, false)
            KeyEvent.KEYCODE_MINUS -> MappedScanCode(0x0C, false)
            KeyEvent.KEYCODE_EQUALS -> MappedScanCode(0x0D, false)
            KeyEvent.KEYCODE_DEL -> MappedScanCode(0x0E, false)
            KeyEvent.KEYCODE_TAB -> MappedScanCode(0x0F, false)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> MappedScanCode(0x1C, keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            KeyEvent.KEYCODE_CTRL_LEFT -> MappedScanCode(0x1D, false)
            KeyEvent.KEYCODE_CTRL_RIGHT -> MappedScanCode(0x1D, true)
            KeyEvent.KEYCODE_SHIFT_LEFT -> MappedScanCode(0x2A, false)
            KeyEvent.KEYCODE_SHIFT_RIGHT -> MappedScanCode(0x36, false)
            KeyEvent.KEYCODE_ALT_LEFT -> MappedScanCode(0x38, false)
            KeyEvent.KEYCODE_ALT_RIGHT -> MappedScanCode(0x38, true)
            KeyEvent.KEYCODE_META_LEFT -> MappedScanCode(0x5B, true)  // Left Windows / Meta / Search
            KeyEvent.KEYCODE_META_RIGHT -> MappedScanCode(0x5C, true) // Right Windows / Cmd
            KeyEvent.KEYCODE_CAPS_LOCK -> MappedScanCode(0x3A, false)
            KeyEvent.KEYCODE_NUM_LOCK -> MappedScanCode(0x45, false)
            KeyEvent.KEYCODE_SCROLL_LOCK -> MappedScanCode(0x46, false)
            KeyEvent.KEYCODE_SPACE -> MappedScanCode(0x39, false)
            KeyEvent.KEYCODE_GRAVE -> MappedScanCode(0x29, false)
            KeyEvent.KEYCODE_LEFT_BRACKET -> MappedScanCode(0x1A, false)
            KeyEvent.KEYCODE_RIGHT_BRACKET -> MappedScanCode(0x1B, false)
            KeyEvent.KEYCODE_BACKSLASH -> MappedScanCode(0x2B, false)
            KeyEvent.KEYCODE_SEMICOLON -> MappedScanCode(0x27, false)
            KeyEvent.KEYCODE_APOSTROPHE -> MappedScanCode(0x28, false)
            KeyEvent.KEYCODE_COMMA -> MappedScanCode(0x33, false)
            KeyEvent.KEYCODE_PERIOD -> MappedScanCode(0x34, false)
            KeyEvent.KEYCODE_SLASH -> MappedScanCode(0x35, false)
            KeyEvent.KEYCODE_F1 -> MappedScanCode(0x3B, false)
            KeyEvent.KEYCODE_F2 -> MappedScanCode(0x3C, false)
            KeyEvent.KEYCODE_F3 -> MappedScanCode(0x3D, false)
            KeyEvent.KEYCODE_F4 -> MappedScanCode(0x3E, false)
            KeyEvent.KEYCODE_F5 -> MappedScanCode(0x3F, false)
            KeyEvent.KEYCODE_F6 -> MappedScanCode(0x40, false)
            KeyEvent.KEYCODE_F7 -> MappedScanCode(0x41, false)
            KeyEvent.KEYCODE_F8 -> MappedScanCode(0x42, false)
            KeyEvent.KEYCODE_F9 -> MappedScanCode(0x43, false)
            KeyEvent.KEYCODE_F10 -> MappedScanCode(0x44, false)
            KeyEvent.KEYCODE_F11 -> MappedScanCode(0x57, false)
            KeyEvent.KEYCODE_F12 -> MappedScanCode(0x58, false)
            KeyEvent.KEYCODE_DPAD_UP -> MappedScanCode(0x48, true)
            KeyEvent.KEYCODE_DPAD_LEFT -> MappedScanCode(0x4B, true)
            KeyEvent.KEYCODE_DPAD_RIGHT -> MappedScanCode(0x4D, true)
            KeyEvent.KEYCODE_DPAD_DOWN -> MappedScanCode(0x50, true)
            KeyEvent.KEYCODE_INSERT -> MappedScanCode(0x52, true)
            KeyEvent.KEYCODE_FORWARD_DEL -> MappedScanCode(0x53, true)
            KeyEvent.KEYCODE_MOVE_HOME -> MappedScanCode(0x47, true)
            KeyEvent.KEYCODE_MOVE_END -> MappedScanCode(0x4F, true)
            KeyEvent.KEYCODE_PAGE_UP -> MappedScanCode(0x49, true)
            KeyEvent.KEYCODE_PAGE_DOWN -> MappedScanCode(0x51, true)
            KeyEvent.KEYCODE_NUMPAD_0 -> MappedScanCode(0x52, false)
            KeyEvent.KEYCODE_NUMPAD_1 -> MappedScanCode(0x4F, false)
            KeyEvent.KEYCODE_NUMPAD_2 -> MappedScanCode(0x50, false)
            KeyEvent.KEYCODE_NUMPAD_3 -> MappedScanCode(0x51, false)
            KeyEvent.KEYCODE_NUMPAD_4 -> MappedScanCode(0x4B, false)
            KeyEvent.KEYCODE_NUMPAD_5 -> MappedScanCode(0x4C, false)
            KeyEvent.KEYCODE_NUMPAD_6 -> MappedScanCode(0x4D, false)
            KeyEvent.KEYCODE_NUMPAD_7 -> MappedScanCode(0x47, false)
            KeyEvent.KEYCODE_NUMPAD_8 -> MappedScanCode(0x48, false)
            KeyEvent.KEYCODE_NUMPAD_9 -> MappedScanCode(0x49, false)
            KeyEvent.KEYCODE_NUMPAD_DOT -> MappedScanCode(0x53, false)
            KeyEvent.KEYCODE_NUMPAD_ADD -> MappedScanCode(0x4E, false)
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> MappedScanCode(0x4A, false)
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> MappedScanCode(0x37, false)
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> MappedScanCode(0x35, true)
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                val qwertyMap = intArrayOf(
                    0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24,
                    0x25, 0x26, 0x32, 0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14,
                    0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C
                )
                MappedScanCode(qwertyMap[keyCode - KeyEvent.KEYCODE_A], false)
            }
            else -> if (ignoredScan == -1) null else null
        }
    }

    fun mapCharToScanCode(ch: Char): MappedScanCode? {
        val lower = ch.lowercaseChar()
        if (lower in 'a'..'z') {
            val base = mapAndroidKeyCode(KeyEvent.KEYCODE_A + (lower - 'a')) ?: return null
            return base.copy(needsShift = ch.isUpperCase())
        }
        return when (ch) {
            '1' -> MappedScanCode(0x02, false, false)
            '!' -> MappedScanCode(0x02, false, true)
            '2' -> MappedScanCode(0x03, false, false)
            '@' -> MappedScanCode(0x03, false, true)
            '3' -> MappedScanCode(0x04, false, false)
            '#' -> MappedScanCode(0x04, false, true)
            '4' -> MappedScanCode(0x05, false, false)
            '$' -> MappedScanCode(0x05, false, true)
            '5' -> MappedScanCode(0x06, false, false)
            '%' -> MappedScanCode(0x06, false, true)
            '6' -> MappedScanCode(0x07, false, false)
            '^' -> MappedScanCode(0x07, false, true)
            '7' -> MappedScanCode(0x08, false, false)
            '&' -> MappedScanCode(0x08, false, true)
            '8' -> MappedScanCode(0x09, false, false)
            '*' -> MappedScanCode(0x09, false, true)
            '9' -> MappedScanCode(0x0A, false, false)
            '(' -> MappedScanCode(0x0A, false, true)
            '0' -> MappedScanCode(0x0B, false, false)
            ')' -> MappedScanCode(0x0B, false, true)
            '-' -> MappedScanCode(0x0C, false, false)
            '_' -> MappedScanCode(0x0C, false, true)
            '=' -> MappedScanCode(0x0D, false, false)
            '+' -> MappedScanCode(0x0D, false, true)
            '\b' -> MappedScanCode(0x0E, false, false)
            '\t' -> MappedScanCode(0x0F, false, false)
            '\n', '\r' -> MappedScanCode(0x1C, false, false)
            ' ' -> MappedScanCode(0x39, false, false)
            '`' -> MappedScanCode(0x29, false, false)
            '~' -> MappedScanCode(0x29, false, true)
            '[' -> MappedScanCode(0x1A, false, false)
            '{' -> MappedScanCode(0x1A, false, true)
            ']' -> MappedScanCode(0x1B, false, false)
            '}' -> MappedScanCode(0x1B, false, true)
            '\\' -> MappedScanCode(0x2B, false, false)
            '|' -> MappedScanCode(0x2B, false, true)
            ';' -> MappedScanCode(0x27, false, false)
            ':' -> MappedScanCode(0x27, false, true)
            '\'' -> MappedScanCode(0x28, false, false)
            '"' -> MappedScanCode(0x28, false, true)
            ',' -> MappedScanCode(0x33, false, false)
            '<' -> MappedScanCode(0x33, false, true)
            '.' -> MappedScanCode(0x34, false, false)
            '>' -> MappedScanCode(0x34, false, true)
            '/' -> MappedScanCode(0x35, false, false)
            '?' -> MappedScanCode(0x35, false, true)
            else -> null
        }
    }
}

/**
 * USB CCID Smartcard / YubiKey PIV APDU Redirection Transport over Android `UsbManager`.
 */
class UsbSmartcardApduBridge(context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var activeConnection: UsbDeviceConnection? = null
    private var ccidInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var sequenceNumber: Byte = 0

    fun attachFirstAvailableSmartcard(): UsbDevice? {
        val device = usbManager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any { idx ->
                dev.getInterface(idx).interfaceClass == UsbConstants.USB_CLASS_CSCID
            }
        } ?: return null

        if (!usbManager.hasPermission(device)) return null
        val intf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .first { it.interfaceClass == UsbConstants.USB_CLASS_CSCID }

        val conn = usbManager.openDevice(device) ?: return null
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            return null
        }

        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep else bulkOut = ep
            }
        }
        activeConnection = conn
        ccidInterface = intf
        return device
    }

    /**
     * Wraps an ISO 7816-4 APDU inside a USB CCID `PC_to_RDR_XfrBlock` (0x6F) frame and reads `RDR_to_PC_DataBlock` (0x80).
     */
    fun transmitApdu(commandApdu: ByteArray): ByteArray {
        val conn = activeConnection ?: return byteArrayOf(0x6A.toByte(), 0x82.toByte())
        val outEp = bulkOut ?: return byteArrayOf(0x6F.toByte(), 0x00.toByte())
        val inEp = bulkIn ?: return byteArrayOf(0x6F.toByte(), 0x00.toByte())

        val len = commandApdu.size
        val ccidFrame = ByteArray(10 + len)
        ccidFrame[0] = 0x6F // PC_to_RDR_XfrBlock
        ccidFrame[1] = (len and 0xFF).toByte()
        ccidFrame[2] = ((len shr 8) and 0xFF).toByte()
        ccidFrame[3] = ((len shr 16) and 0xFF).toByte()
        ccidFrame[4] = ((len shr 24) and 0xFF).toByte()
        ccidFrame[5] = 0x00 // Slot 0
        ccidFrame[6] = sequenceNumber++
        System.arraycopy(commandApdu, 0, ccidFrame, 10, len)

        val written = conn.bulkTransfer(outEp, ccidFrame, ccidFrame.size, 3000)
        if (written < 0) return byteArrayOf(0x6F.toByte(), 0x00.toByte())

        val responseBuf = ByteArray(4096)
        val read = conn.bulkTransfer(inEp, responseBuf, responseBuf.size, 5000)
        if (read <= 10) return byteArrayOf(0x6F.toByte(), 0x00.toByte())

        return responseBuf.copyOfRange(10, read)
    }

    fun close() {
        ccidInterface?.let { activeConnection?.releaseInterface(it) }
        activeConnection?.close()
        activeConnection = null
        ccidInterface = null
    }
}

class RdpManagedSession(
    val connection: VaultConnection,
    val settings: ProtocolSpecificSettings.Rdp,
    val secret: ResolvedSessionSecret
) {
    companion object {
        fun usesH264Cap(settings: ProtocolSpecificSettings.Rdp): Boolean =
            settings.enableRemoteFx && settings.gfxCodecProfile != RdpGfxCodecProfile.ADAPTIVE_PROGRESSIVE

        fun clampSurfaceDimensions(
            widthPx: Int,
            heightPx: Int,
            h264Enabled: Boolean
        ): Pair<Int, Int> {
            val maxW = if (h264Enabled) 3840.0 else 8192.0
            val maxH = if (h264Enabled) 2160.0 else 8192.0
            var w = widthPx.coerceAtLeast(320).toDouble()
            var h = heightPx.coerceAtLeast(240).toDouble()
            if (w > maxW || h > maxH) {
                val scale = kotlin.math.min(maxW / w, maxH / h)
                w = (w * scale).coerceAtLeast(320.0)
                h = (h * scale).coerceAtLeast(240.0)
            }
            val alignedW = w.toInt().coerceIn(320, maxW.toInt()) and -2
            val alignedH = h.toInt().coerceIn(240, maxH.toInt()) and -2
            return alignedW to alignedH
        }
    }

    @Volatile
    private var nativeHandle: Long = 0L
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val surfaceLock = Any()
    private val driveSyncLock = Any()
    private var pendingSurface: Surface? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var preparedSharedDrive: SafRdpSharedDriveSynchronizer.PreparedRdpSharedDrive? = null

    @Volatile
    private var sharedDriveBaseline: Map<String, SafRdpSharedDriveSynchronizer.StagedFileStamp> = emptyMap()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _lastError = MutableStateFlow<com.nexusts.app.core.vault.error.NexusAppError?>(null)
    val lastError: StateFlow<com.nexusts.app.core.vault.error.NexusAppError?> = _lastError.asStateFlow()

    private val _liveResizeBehavior = MutableStateFlow(settings.resizeBehavior)
    val liveResizeBehavior: StateFlow<RdpResizeBehavior> = _liveResizeBehavior.asStateFlow()

    private val _sharedDriveUncLabel = MutableStateFlow<String?>(
        if (settings.redirectDrives || settings.redirectedDriveSafUris.isNotEmpty()) {
            "\\\\tsclient\\${SafRdpSharedDriveSynchronizer.DEFAULT_SHARE_NAME}"
        } else {
            null
        }
    )
    val sharedDriveUncLabel: StateFlow<String?> = _sharedDriveUncLabel.asStateFlow()

    private val _remoteDesktopDimensions = MutableStateFlow(
        clampSurfaceDimensions(
            widthPx = settings.customDesktopWidth,
            heightPx = settings.customDesktopHeight,
            h264Enabled = usesH264Cap(settings)
        )
    )
    val remoteDesktopDimensions: StateFlow<Pair<Int, Int>> = _remoteDesktopDimensions.asStateFlow()

    @Volatile
    var isMicrophoneRedirected: Boolean = false
        private set

    fun reconnect(
        initialWidthPx: Int,
        initialHeightPx: Int,
        effectiveScalePercent: Int = settings.desktopScaleFactorPercent,
        context: Context? = appContext
    ): Boolean {
        val prevHandle = nativeHandle
        nativeHandle = 0L
        started.set(false)
        _isConnected.value = false
        _isReconnecting.value = false
        _reconnectAttempt.value = 0
        _lastError.value = null
        if (prevHandle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            runCatching { FreeRdpNativeBridge.nativeDestroySession(prevHandle) }
        }
        return start(
            initialWidthPx = initialWidthPx,
            initialHeightPx = initialHeightPx,
            effectiveScalePercent = effectiveScalePercent,
            context = context
        )
    }

    fun syncServerDesktopDimensions() {
        val handle = nativeHandle
        if (handle == 0L || !FreeRdpNativeBridge.isNativeLoaded) return
        val packed = runCatching { FreeRdpNativeBridge.nativeGetDesktopDimensions(handle) }.getOrDefault(0L)
        if (packed != 0L) {
            val w = (packed ushr 32).toInt()
            val h = (packed and 0xFFFFFFFFL).toInt()
            if (w >= 320 && h >= 240 && _remoteDesktopDimensions.value != (w to h)) {
                _remoteDesktopDimensions.value = w to h
            }
        }
    }

    fun pollSessionHealth() {
        val handle = nativeHandle
        if (handle == 0L || !FreeRdpNativeBridge.isNativeLoaded || _isConnecting.value) return

        val reconnecting = runCatching { FreeRdpNativeBridge.nativeIsReconnecting(handle) }.getOrDefault(false)
        val attempt = if (reconnecting) {
            runCatching { FreeRdpNativeBridge.nativeGetReconnectAttempt(handle) }.getOrDefault(0)
        } else {
            0
        }
        _isReconnecting.value = reconnecting
        _reconnectAttempt.value = attempt
        if (reconnecting) {
            return
        }

        val stillAlive = runCatching { FreeRdpNativeBridge.nativeIsConnected(handle) }.getOrDefault(true)
        if (!stillAlive) {
            if (_isConnected.value) {
                val rawErr = runCatching { FreeRdpNativeBridge.nativeGetLastError(handle) }.getOrDefault("")
                _isConnected.value = false
                started.set(false)
                if (rawErr.isNotBlank()) {
                    _lastError.value = com.nexusts.app.core.vault.error.NexusErrorClassifier.classifyRdpError(
                        host = connection.host,
                        port = settings.port,
                        rawNativeError = rawErr,
                        connectionId = connection.id
                    )
                }
            }
        } else {
            if (!_isConnected.value) {
                _isConnected.value = true
                _lastError.value = null
            }
            syncServerDesktopDimensions()
        }
    }

    fun start(
        initialWidthPx: Int,
        initialHeightPx: Int,
        effectiveScalePercent: Int = settings.desktopScaleFactorPercent,
        context: Context? = null,
        micPermissionGranted: Boolean = context?.let {
            ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        } ?: false
    ): Boolean {
        context?.applicationContext?.let { appContext = it }
        if (connection.host.isBlank()) {
            _isConnecting.value = false
            _isConnected.value = false
            _lastError.value = com.nexusts.app.core.vault.error.NexusErrorClassifier.classifyRdpError(
                host = "(empty)",
                port = settings.port,
                rawNativeError = "DNS_NAME_NOT_FOUND: Hostname is empty.",
                connectionId = connection.id
            )
            return false
        }
        val wantsSharedDrive = settings.redirectDrives || settings.redirectedDriveSafUris.isNotEmpty()
        val stagedDrive = if (wantsSharedDrive) {
            SafRdpSharedDriveSynchronizer.prepareSessionDrive(
                context = appContext ?: context,
                connectionId = connection.id,
                redirectDrivesEnabled = wantsSharedDrive,
                safUriStrings = settings.redirectedDriveSafUris
            )?.also { prepared ->
                synchronized(driveSyncLock) {
                    preparedSharedDrive = prepared
                    sharedDriveBaseline = prepared.initialSnapshot
                }
                _sharedDriveUncLabel.value = prepared.uncDisplayPath
            }
        } else {
            null
        }

        if (!FreeRdpNativeBridge.isNativeLoaded) {
            _isConnecting.value = false
            _isConnected.value = false
            _lastError.value = com.nexusts.app.core.vault.error.NexusAppError(
                category = com.nexusts.app.core.vault.error.NexusErrorCategory.INTERNAL,
                title = "FreeRDP Native Engine Unavailable",
                userMessage = "The native FreeRDP 3.x shared library could not be loaded for this CPU ABI.",
                remediationHint = "Install the matching ABI APK (arm64-v8a / x86_64) or universal APK from GitHub Releases.",
                technicalDetails = "FreeRdpNativeBridge.isNativeLoaded == false",
                recoveryConnectionId = connection.id
            )
            return false
        }
        if (!started.compareAndSet(false, true)) {
            return nativeHandle != 0L && _isConnected.value
        }
        _lastError.value = null
        _isReconnecting.value = false
        _reconnectAttempt.value = 0
        _isConnecting.value = true
        return try {
            val useFixedSize = _liveResizeBehavior.value == RdpResizeBehavior.FIXED_SERVER_RESOLUTION
            val rawW = if (useFixedSize) settings.customDesktopWidth else initialWidthPx
            val rawH = if (useFixedSize) settings.customDesktopHeight else initialHeightPx
            val (startW, startH) = clampSurfaceDimensions(
                widthPx = rawW,
                heightPx = rawH,
                h264Enabled = usesH264Cap(settings)
            )
            _remoteDesktopDimensions.value = startW to startH

            val audioModeCode = when (settings.audioRedirectionMode) {
                RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE -> 0
                RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST -> 1
                RdpAudioRedirectionMode.DO_NOT_PLAY -> 2
            }
            val effectiveMicRedirect = settings.redirectMicrophone && micPermissionGranted
            isMicrophoneRedirected = effectiveMicRedirect

            val runtimeHomeDir = (appContext ?: context)?.filesDir
                ?.let { java.io.File(it, "freerdp_runtime").apply { mkdirs() }.absolutePath }
                .orEmpty()

            val trimmedHostRaw = connection.host.trim()
            val colonCount = trimmedHostRaw.count { it == ':' }
            val parsedHostAndPort = if (colonCount == 1) {
                val hostPart = trimmedHostRaw.substringBefore(':').trim()
                val portPart = trimmedHostRaw.substringAfter(':').trim().toIntOrNull()
                if (hostPart.isNotEmpty() && portPart != null && portPart in 1..65535) {
                    hostPart to portPart
                } else {
                    trimmedHostRaw to settings.port
                }
            } else {
                trimmedHostRaw.removeSurrounding("[", "]") to settings.port
            }

            // Negotiate DISP_DVC channel capability at connect time so live resize mode toggles work without reconnecting
            val createdHandle = FreeRdpNativeBridge.nativeCreateSession(
                host = parsedHostAndPort.first,
                port = parsedHostAndPort.second,
                username = secret.username.trim(),
                password = secret.password,
                domain = secret.domain.trim(),
                gatewayHost = settings.rdGatewayHostname.trim(),
                gatewayPort = settings.rdGatewayPort,
                initialWidth = startW,
                initialHeight = startH,
                connectToConsole = settings.connectToConsoleAdmin,
                hiDpiEnabled = settings.hiDpiEnabled,
                desktopScalePercent = if (settings.hiDpiEnabled) effectiveScalePercent.coerceIn(100, 200) else 100,
                colorDepthBits = settings.colorDepthBits,
                gfxCodecMode = settings.gfxCodecProfile.nativeCode,
                enableH264Avc444 = settings.gfxCodecProfile == RdpGfxCodecProfile.AVC444_CHROMA,
                enableRemoteFx = settings.enableRemoteFx,
                enableDynamicResize = true,
                multiMonitor = settings.multiMonitorSpanning,
                redirectClipboard = settings.redirectClipboard,
                audioRedirectionMode = audioModeCode,
                redirectMic = effectiveMicRedirect,
                redirectDrives = wantsSharedDrive,
                redirectPrinters = settings.redirectPrinters,
                redirectPorts = settings.redirectPorts,
                redirectSmartcard = settings.redirectSmartcardYubiKey,
                enableFontSmoothing = settings.enableFontSmoothing,
                enableDesktopComposition = settings.enableDesktopComposition,
                enableWallpaper = settings.enableWallpaper,
                compressionEnabled = settings.compressionEnabled,
                nlaSecurity = settings.nlaSecurityRequired,
                restrictedAdminMode = settings.restrictedAdminMode,
                ignoreCertificate = settings.ignoreCertificateWarnings,
                remoteAppMode = settings.remoteAppMode,
                remoteAppProgram = settings.remoteAppProgram,
                remoteAppArgs = settings.remoteAppArguments,
                sharedDriveName = stagedDrive?.shareName.orEmpty(),
                sharedDriveLocalPath = stagedDrive?.localPosixDirectory?.absolutePath.orEmpty(),
                appHomeDir = runtimeHomeDir
            )
            nativeHandle = createdHandle
            synchronized(surfaceLock) {
                if (createdHandle != 0L && pendingSurface != null) {
                    FreeRdpNativeBridge.nativeBindSurface(createdHandle, pendingSurface)
                }
            }
            val connected = createdHandle != 0L && FreeRdpNativeBridge.nativeConnect(createdHandle)
            _isConnected.value = connected
            if (connected) {
                syncServerDesktopDimensions()
            } else {
                val rawNativeErr = if (createdHandle != 0L) {
                    runCatching { FreeRdpNativeBridge.nativeGetLastError(createdHandle) }.getOrDefault("")
                } else {
                    "nativeCreateSession returned 0"
                }
                _lastError.value = com.nexusts.app.core.vault.error.NexusErrorClassifier.classifyRdpError(
                    host = connection.host,
                    port = settings.port,
                    rawNativeError = rawNativeErr,
                    connectionId = connection.id
                )
                started.set(false)
            }
            connected
        } catch (t: Throwable) {
            _isConnected.value = false
            started.set(false)
            _lastError.value = com.nexusts.app.core.vault.error.NexusErrorClassifier.classifyThrowable(
                throwable = t,
                operationTitle = "RDP Session Error (${connection.host}:${settings.port})",
                recoveryConnectionId = connection.id
            )
            false
        } finally {
            _isConnecting.value = false
        }
    }

    fun setLiveResizeBehavior(
        mode: RdpResizeBehavior,
        viewportWidthPx: Int = 0,
        viewportHeightPx: Int = 0,
        scalePercent: Int = settings.desktopScaleFactorPercent
    ) {
        _liveResizeBehavior.value = mode
        when (mode) {
            RdpResizeBehavior.DYNAMIC_DISP_DVC -> {
                if (viewportWidthPx >= 320 && viewportHeightPx >= 240) {
                    onViewportResized(viewportWidthPx, viewportHeightPx, scalePercent, forceDynamic = true)
                }
            }
            RdpResizeBehavior.FIXED_SERVER_RESOLUTION -> {
                onViewportResized(
                    widthPx = settings.customDesktopWidth,
                    heightPx = settings.customDesktopHeight,
                    scalePercent = scalePercent,
                    forceDynamic = true
                )
            }
            RdpResizeBehavior.SMART_SCALE_VIEWPORT -> Unit
        }
    }

    fun syncSharedDrivesNow(
        context: Context? = appContext,
        pullInboundFromSaf: Boolean = true
    ): SafRdpSharedDriveSynchronizer.SharedDriveSyncReport? {
        val ctx = context ?: appContext ?: return null
        synchronized(driveSyncLock) {
            val prepared = preparedSharedDrive ?: return null
            val report = SafRdpSharedDriveSynchronizer.syncSessionDriveWithSaf(
                context = ctx,
                preparedDrive = prepared,
                baselineSnapshot = sharedDriveBaseline,
                pullInboundFromSaf = pullInboundFromSaf
            )
            sharedDriveBaseline = report.updatedSnapshot
            return report
        }
    }

    fun bindSurface(surface: Surface?) {
        synchronized(surfaceLock) {
            pendingSurface = surface
            val handle = nativeHandle
            if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
                FreeRdpNativeBridge.nativeBindSurface(handle, surface)
            }
        }
    }

    /**
     * Detaches [expectedSurface] only if no newer [Surface] has already been bound to this session
     * (prevents tab-switch or full-screen transition disposal from clobbering a newly mounted TextureView).
     */
    fun unbindSurface(expectedSurface: Surface?) {
        synchronized(surfaceLock) {
            if (expectedSurface == null || pendingSurface === expectedSurface) {
                pendingSurface = null
                val handle = nativeHandle
                if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
                    FreeRdpNativeBridge.nativeBindSurface(handle, null)
                }
            }
        }
    }

    fun onViewportResized(
        widthPx: Int,
        heightPx: Int,
        scalePercent: Int = settings.desktopScaleFactorPercent,
        forceDynamic: Boolean = false
    ) {
        val (alignedW, alignedH) = clampSurfaceDimensions(
            widthPx = widthPx,
            heightPx = heightPx,
            h264Enabled = usesH264Cap(settings)
        )
        val shouldApplyDisp = _liveResizeBehavior.value == RdpResizeBehavior.DYNAMIC_DISP_DVC || forceDynamic
        if (!shouldApplyDisp) return

        val handle = nativeHandle
        if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            val effectiveScale = if (settings.hiDpiEnabled || forceDynamic) scalePercent.coerceIn(100, 200) else 100
            FreeRdpNativeBridge.nativeRequestDynamicResize(handle, alignedW, alignedH, effectiveScale)
            syncServerDesktopDimensions()
        } else {
            _remoteDesktopDimensions.value = alignedW to alignedH
        }
    }

    fun syncClipboardToRemote(utf8Text: String) {
        if (!settings.redirectClipboard || utf8Text.isEmpty()) return
        val handle = nativeHandle
        if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            FreeRdpNativeBridge.nativeSyncClipboardText(handle, utf8Text)
        }
    }

    fun remoteClipboardSequence(): Long {
        if (!settings.redirectClipboard) return 0L
        val handle = nativeHandle
        if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            return runCatching { FreeRdpNativeBridge.nativeGetRemoteClipboardSequence(handle) }.getOrDefault(0L)
        }
        return 0L
    }

    fun pullRemoteClipboardText(): String {
        if (!settings.redirectClipboard) return ""
        val handle = nativeHandle
        if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            return FreeRdpNativeBridge.nativePullRemoteClipboardText(handle)
        }
        return ""
    }

    fun sendHardwareKey(packet: RawHardwareKeyPacket): Boolean {
        val mapped = RdpScanCodeMapper.mapKeyPacket(packet) ?: return false
        val handle = nativeHandle
        if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            FreeRdpNativeBridge.nativeSendKeyScancode(
                handle = handle,
                rdpScancode = mapped.scanCode,
                keyDown = packet.isKeyDown,
                extended = mapped.extended
            )
        }
        return true
    }

    fun sendAndroidKeyEvent(keyCode: Int, scanCode: Int = 0, isKeyDown: Boolean): Boolean {
        val mapped = RdpScanCodeMapper.mapAndroidKeyCode(keyCode, scanCode) ?: return false
        val handle = nativeHandle
        if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            FreeRdpNativeBridge.nativeSendKeyScancode(
                handle = handle,
                rdpScancode = mapped.scanCode,
                keyDown = isKeyDown,
                extended = mapped.extended
            )
        }
        return true
    }

    fun releaseAllKeys() {
        val handle = nativeHandle
        if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            runCatching { FreeRdpNativeBridge.nativeReleaseAllKeys(handle) }
        }
    }

    fun sendKeyTap(rdpScancode: Int, extended: Boolean = false) {
        val handle = nativeHandle
        if (handle == 0L || !FreeRdpNativeBridge.isNativeLoaded) return
        FreeRdpNativeBridge.nativeSendKeyScancode(handle, rdpScancode, true, extended)
        FreeRdpNativeBridge.nativeSendKeyScancode(handle, rdpScancode, false, extended)
    }

    fun sendTextInput(text: String) {
        if (text.isEmpty()) return
        val handle = nativeHandle
        if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            var anyUnmapped = false
            for (ch in text) {
                val mapped = RdpScanCodeMapper.mapCharToScanCode(ch)
                if (mapped != null) {
                    if (mapped.needsShift) {
                        FreeRdpNativeBridge.nativeSendKeyScancode(handle, 0x2A, true, false)
                    }
                    FreeRdpNativeBridge.nativeSendKeyScancode(handle, mapped.scanCode, true, mapped.extended)
                    FreeRdpNativeBridge.nativeSendKeyScancode(handle, mapped.scanCode, false, mapped.extended)
                    if (mapped.needsShift) {
                        FreeRdpNativeBridge.nativeSendKeyScancode(handle, 0x2A, false, false)
                    }
                } else {
                    anyUnmapped = true
                }
            }
            if (anyUnmapped && settings.redirectClipboard) {
                FreeRdpNativeBridge.nativeSyncClipboardText(handle, text)
            }
        }
    }

    fun sendCtrlAltDel() {
        val handle = nativeHandle
        if (handle == 0L || !FreeRdpNativeBridge.isNativeLoaded) return
        FreeRdpNativeBridge.nativeSendKeyScancode(handle, 0x1D, true, false)  // Ctrl down
        FreeRdpNativeBridge.nativeSendKeyScancode(handle, 0x38, true, false)  // Alt down
        FreeRdpNativeBridge.nativeSendKeyScancode(handle, 0x53, true, true)   // Del down
        FreeRdpNativeBridge.nativeSendKeyScancode(handle, 0x53, false, true)  // Del up
        FreeRdpNativeBridge.nativeSendKeyScancode(handle, 0x38, false, false) // Alt up
        FreeRdpNativeBridge.nativeSendKeyScancode(handle, 0x1D, false, false) // Ctrl up
    }

    fun sendMouse(flags: Int, x: Int, y: Int) {
        val handle = nativeHandle
        if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            FreeRdpNativeBridge.nativeSendMouseEvent(handle, flags, x, y)
        }
    }

    fun sendExtendedMouse(xflags: Int, x: Int, y: Int) {
        val handle = nativeHandle
        if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            FreeRdpNativeBridge.nativeSendExtendedMouseEvent(handle, xflags, x, y)
        }
    }

    fun close() {
        started.set(false)
        _isConnecting.value = false
        _isConnected.value = false
        _isReconnecting.value = false
        _reconnectAttempt.value = 0
        val handle = nativeHandle
        nativeHandle = 0L
        synchronized(surfaceLock) {
            pendingSurface = null
        }
        if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            FreeRdpNativeBridge.nativeDestroySession(handle)
        }
        runCatching { syncSharedDrivesNow(pullInboundFromSaf = false) }
    }
}
