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
import com.nexusts.app.core.vault.model.RdpResizeBehavior
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.protocols.rdp.storage.SafRdpSharedDriveSynchronizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JNI Bridge to `libnexusts_rdp_jni.so` (FreeRDP 3.x + AMediaCodec H.264/AVC444 + AAudio + DISP_DVC + RDPDR).
 */
object FreeRdpNativeBridge {
    val isNativeLoaded: Boolean = runCatching {
        System.loadLibrary("nexusts_rdp_jni")
        true
    }.getOrDefault(false)

    // MS-RDPBCGR Pointer Event Flags
    const val PTRFLAGS_WHEEL = 0x0200
    const val PTRFLAGS_WHEEL_NEGATIVE = 0x0100
    const val PTRFLAGS_MOVE = 0x0800
    const val PTRFLAGS_DOWN = 0x8000
    const val PTRFLAGS_BUTTON1_LEFT = 0x1000
    const val PTRFLAGS_BUTTON2_RIGHT = 0x2000
    const val PTRFLAGS_BUTTON3_MIDDLE = 0x4000

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
        sharedDriveLocalPath: String
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
    external fun nativeSendMouseEvent(
        handle: Long,
        pointerFlags: Int,
        x: Int,
        y: Int
    )

    @JvmStatic
    external fun nativeSyncClipboardText(handle: Long, utf8Text: String)

    @JvmStatic
    external fun nativePullRemoteClipboardText(handle: Long): String

    @JvmStatic
    external fun nativeDestroySession(handle: Long)
}

/**
 * Translates Android `KeyEvent` keyCodes & Linux evdev scanCodes into PC XT Set-1 RDP Scan Codes,
 * preserving full Win/Meta, Alt, Ctrl, Fn, and Function F1–F12 key passthrough.
 */
object RdpScanCodeMapper {
    data class MappedScanCode(val scanCode: Int, val extended: Boolean, val needsShift: Boolean = false)

    fun mapKeyPacket(packet: RawHardwareKeyPacket): MappedScanCode? =
        mapAndroidKeyCode(packet.keyCode, packet.scanCode)

    fun mapAndroidKeyCode(keyCode: Int, scanCode: Int = 0): MappedScanCode? {
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
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                val qwertyMap = intArrayOf(
                    0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24,
                    0x25, 0x26, 0x32, 0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14,
                    0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C
                )
                MappedScanCode(qwertyMap[keyCode - KeyEvent.KEYCODE_A], false)
            }
            else -> if (scanCode in 1..127) MappedScanCode(scanCode, false) else null
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
        (settings.customDesktopWidth.coerceAtLeast(320) and -2) to
            (settings.customDesktopHeight.coerceAtLeast(240) and -2)
    )
    val remoteDesktopDimensions: StateFlow<Pair<Int, Int>> = _remoteDesktopDimensions.asStateFlow()

    @Volatile
    var isMicrophoneRedirected: Boolean = false
        private set

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
            return false
        }
        if (!started.compareAndSet(false, true)) {
            return nativeHandle != 0L
        }
        _isConnecting.value = true
        return try {
            val useFixedSize = _liveResizeBehavior.value == RdpResizeBehavior.FIXED_SERVER_RESOLUTION
            val startW = (if (useFixedSize) settings.customDesktopWidth else initialWidthPx).coerceAtLeast(320) and -2
            val startH = (if (useFixedSize) settings.customDesktopHeight else initialHeightPx).coerceAtLeast(240) and -2
            _remoteDesktopDimensions.value = startW to startH

            val audioModeCode = when (settings.audioRedirectionMode) {
                RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE -> 0
                RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST -> 1
                RdpAudioRedirectionMode.DO_NOT_PLAY -> 2
            }
            val effectiveMicRedirect = settings.redirectMicrophone && micPermissionGranted
            isMicrophoneRedirected = effectiveMicRedirect

            // Negotiate DISP_DVC channel capability at connect time so live resize mode toggles work without reconnecting
            val createdHandle = FreeRdpNativeBridge.nativeCreateSession(
                host = connection.host,
                port = settings.port,
                username = secret.username,
                password = secret.password,
                domain = secret.domain,
                gatewayHost = settings.rdGatewayHostname,
                gatewayPort = settings.rdGatewayPort,
                initialWidth = startW,
                initialHeight = startH,
                connectToConsole = settings.connectToConsoleAdmin,
                hiDpiEnabled = settings.hiDpiEnabled,
                desktopScalePercent = if (settings.hiDpiEnabled) effectiveScalePercent.coerceIn(100, 200) else 100,
                colorDepthBits = settings.colorDepthBits,
                enableH264Avc444 = settings.enableH264Avc444,
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
                sharedDriveLocalPath = stagedDrive?.localPosixDirectory?.absolutePath.orEmpty()
            )
            nativeHandle = createdHandle
            synchronized(surfaceLock) {
                if (createdHandle != 0L && pendingSurface != null) {
                    FreeRdpNativeBridge.nativeBindSurface(createdHandle, pendingSurface)
                }
            }
            val connected = createdHandle != 0L && FreeRdpNativeBridge.nativeConnect(createdHandle)
            _isConnected.value = connected
            connected
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

    fun onViewportResized(
        widthPx: Int,
        heightPx: Int,
        scalePercent: Int = settings.desktopScaleFactorPercent,
        forceDynamic: Boolean = false
    ) {
        val alignedW = widthPx.coerceAtLeast(320) and -2
        val alignedH = heightPx.coerceAtLeast(240) and -2
        val shouldApplyDisp = _liveResizeBehavior.value == RdpResizeBehavior.DYNAMIC_DISP_DVC || forceDynamic
        if (!shouldApplyDisp) return

        val handle = nativeHandle
        if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            val effectiveScale = if (settings.hiDpiEnabled || forceDynamic) scalePercent.coerceIn(100, 200) else 100
            if (FreeRdpNativeBridge.nativeRequestDynamicResize(handle, alignedW, alignedH, effectiveScale)) {
                _remoteDesktopDimensions.value = alignedW to alignedH
            }
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

    fun pullRemoteClipboardText(): String {
        if (!settings.redirectClipboard) return ""
        val handle = nativeHandle
        if (handle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            return FreeRdpNativeBridge.nativePullRemoteClipboardText(handle)
        }
        return ""
    }

    fun sendHardwareKey(packet: RawHardwareKeyPacket): Boolean =
        sendAndroidKeyEvent(packet.keyCode, packet.scanCode, packet.isKeyDown)

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

    fun close() {
        started.set(false)
        _isConnecting.value = false
        _isConnected.value = false
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
