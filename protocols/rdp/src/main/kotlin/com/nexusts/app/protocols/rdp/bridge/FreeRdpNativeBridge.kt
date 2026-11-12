package com.nexusts.app.protocols.rdp.bridge

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.view.KeyEvent
import android.view.Surface
import com.nexusts.app.core.ui.input.RawHardwareKeyPacket
import com.nexusts.app.core.vault.credentials.ResolvedSessionSecret
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.RdpResizeBehavior
import com.nexusts.app.core.vault.model.VaultConnection

/**
 * JNI Bridge to `libnexusts_rdp_jni.so` (FreeRDP 3.x + AMediaCodec H.264/AVC444 + AAudio + DISP_DVC).
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
        enableH264Avc444: Boolean,
        enableRemoteFx: Boolean,
        enableDynamicResize: Boolean,
        multiMonitor: Boolean,
        redirectClipboard: Boolean,
        redirectSmartcard: Boolean,
        redirectAudio: Boolean,
        redirectMic: Boolean,
        remoteAppMode: Boolean,
        remoteAppProgram: String,
        remoteAppArgs: String
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
    external fun nativeDestroySession(handle: Long)
}

/**
 * Translates Android `KeyEvent` keyCodes & Linux evdev scanCodes into PC XT Set-1 RDP Scan Codes,
 * preserving full Win/Meta, Alt, Ctrl, Fn, and Function F1–F12 key passthrough.
 */
object RdpScanCodeMapper {
    data class MappedScanCode(val scanCode: Int, val extended: Boolean)

    fun mapKeyPacket(packet: RawHardwareKeyPacket): MappedScanCode? {
        return when (packet.keyCode) {
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
            KeyEvent.KEYCODE_ENTER -> MappedScanCode(0x1C, false)
            KeyEvent.KEYCODE_CTRL_LEFT -> MappedScanCode(0x1D, false)
            KeyEvent.KEYCODE_CTRL_RIGHT -> MappedScanCode(0x1D, true)
            KeyEvent.KEYCODE_SHIFT_LEFT -> MappedScanCode(0x2A, false)
            KeyEvent.KEYCODE_SHIFT_RIGHT -> MappedScanCode(0x36, false)
            KeyEvent.KEYCODE_ALT_LEFT -> MappedScanCode(0x38, false)
            KeyEvent.KEYCODE_ALT_RIGHT -> MappedScanCode(0x38, true)
            KeyEvent.KEYCODE_META_LEFT -> MappedScanCode(0x5B, true)  // Left Windows / Meta / Search
            KeyEvent.KEYCODE_META_RIGHT -> MappedScanCode(0x5C, true) // Right Windows / Cmd
            KeyEvent.KEYCODE_SPACE -> MappedScanCode(0x39, false)
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
                MappedScanCode(qwertyMap[packet.keyCode - KeyEvent.KEYCODE_A], false)
            }
            else -> if (packet.scanCode in 1..127) MappedScanCode(packet.scanCode, false) else null
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
    private var nativeHandle: Long = 0L
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start(initialWidthPx: Int, initialHeightPx: Int): Boolean {
        if (!FreeRdpNativeBridge.isNativeLoaded) return false
        if (!started.compareAndSet(false, true)) {
            return nativeHandle != 0L
        }
        nativeHandle = FreeRdpNativeBridge.nativeCreateSession(
            host = connection.host,
            port = settings.port,
            username = secret.username,
            password = secret.password,
            domain = secret.domain,
            gatewayHost = settings.rdGatewayHostname,
            gatewayPort = settings.rdGatewayPort,
            initialWidth = initialWidthPx,
            initialHeight = initialHeightPx,
            enableH264Avc444 = settings.enableH264Avc444,
            enableRemoteFx = settings.enableRemoteFx,
            enableDynamicResize = settings.resizeBehavior == RdpResizeBehavior.DYNAMIC_DISP_DVC,
            multiMonitor = settings.multiMonitorSpanning,
            redirectClipboard = settings.redirectClipboard,
            redirectSmartcard = settings.redirectSmartcardYubiKey,
            redirectAudio = settings.redirectAudioPlayback,
            redirectMic = settings.redirectMicrophone,
            remoteAppMode = settings.remoteAppMode,
            remoteAppProgram = settings.remoteAppProgram,
            remoteAppArgs = settings.remoteAppArguments
        )
        return FreeRdpNativeBridge.nativeConnect(nativeHandle)
    }

    fun bindSurface(surface: Surface?) {
        if (nativeHandle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            FreeRdpNativeBridge.nativeBindSurface(nativeHandle, surface)
        }
    }

    fun onViewportResized(widthPx: Int, heightPx: Int, scalePercent: Int = 100) {
        if (nativeHandle != 0L &&
            FreeRdpNativeBridge.isNativeLoaded &&
            settings.resizeBehavior == RdpResizeBehavior.DYNAMIC_DISP_DVC
        ) {
            FreeRdpNativeBridge.nativeRequestDynamicResize(nativeHandle, widthPx, heightPx, scalePercent)
        }
    }

    fun sendHardwareKey(packet: RawHardwareKeyPacket): Boolean {
        val mapped = RdpScanCodeMapper.mapKeyPacket(packet) ?: return false
        if (nativeHandle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            FreeRdpNativeBridge.nativeSendKeyScancode(
                handle = nativeHandle,
                rdpScancode = mapped.scanCode,
                keyDown = packet.isKeyDown,
                extended = mapped.extended
            )
        }
        return true
    }

    fun sendCtrlAltDel() {
        if (nativeHandle == 0L || !FreeRdpNativeBridge.isNativeLoaded) return
        FreeRdpNativeBridge.nativeSendKeyScancode(nativeHandle, 0x1D, true, false)  // Ctrl down
        FreeRdpNativeBridge.nativeSendKeyScancode(nativeHandle, 0x38, true, false)  // Alt down
        FreeRdpNativeBridge.nativeSendKeyScancode(nativeHandle, 0x53, true, true)   // Del down
        FreeRdpNativeBridge.nativeSendKeyScancode(nativeHandle, 0x53, false, true)  // Del up
        FreeRdpNativeBridge.nativeSendKeyScancode(nativeHandle, 0x38, false, false) // Alt up
        FreeRdpNativeBridge.nativeSendKeyScancode(nativeHandle, 0x1D, false, false) // Ctrl up
    }

    fun sendMouse(flags: Int, x: Int, y: Int) {
        if (nativeHandle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            FreeRdpNativeBridge.nativeSendMouseEvent(nativeHandle, flags, x, y)
        }
    }

    fun close() {
        started.set(false)
        if (nativeHandle != 0L && FreeRdpNativeBridge.isNativeLoaded) {
            FreeRdpNativeBridge.nativeDestroySession(nativeHandle)
            nativeHandle = 0L
        }
    }
}
