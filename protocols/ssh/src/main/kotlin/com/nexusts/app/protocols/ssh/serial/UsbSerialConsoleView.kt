package com.nexusts.app.protocols.ssh.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
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
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val MAX_CONSOLE_LINES = 2_000

data class UsbUartAdapterDescriptor(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val chipsetLabel: String,
    val hasPermission: Boolean
)

/**
 * Hardware USB-C Serial / UART Driver for Googlebook OS & Android Tablets (`v1.1`).
 * Supports CDC-ACM (`0x02`), FTDI (`0x0403`), Silicon Labs CP210x (`0x10C4`),
 * Prolific PL2303 (`0x067B`), and QinHeng CH340 (`0x1A86`) console cables.
 */
class UsbSerialPortDriver(
    private val context: Context
) {
    val usbManager: UsbManager? = context.getSystemService(UsbManager::class.java)
    @Volatile
    private var connection: UsbDeviceConnection? = null
    @Volatile
    private var claimedInterface: UsbInterface? = null
    @Volatile
    private var bulkIn: UsbEndpoint? = null
    @Volatile
    private var bulkOut: UsbEndpoint? = null
    @Volatile
    private var activeVendorId: Int = 0
    @Volatile
    private var ftdiDataControlWord: Int = 0x0008
    @Volatile
    var pendingPermissionDeviceName: String? = null
        private set
    private var readJob: Job? = null

    fun listCompatibleAdapters(): List<UsbUartAdapterDescriptor> {
        val mgr = usbManager ?: return emptyList()
        return mgr.deviceList.values.mapNotNull { dev ->
            val chipset = identifyChipset(dev) ?: return@mapNotNull null
            UsbUartAdapterDescriptor(
                deviceName = dev.deviceName,
                vendorId = dev.vendorId,
                productId = dev.productId,
                chipsetLabel = chipset,
                hasPermission = mgr.hasPermission(dev)
            )
        }
    }

    fun openAndConfigure(
        adapterName: String,
        settings: ProtocolSpecificSettings.SerialUart,
        onBytesReceived: (ByteArray) -> Unit
    ): Result<String> = runCatching {
        val mgr = usbManager ?: error("Android UsbManager unavailable")
        val device = mgr.deviceList[adapterName] ?: error("USB UART device '$adapterName' disconnected")
        if (!mgr.hasPermission(device)) {
            pendingPermissionDeviceName = device.deviceName
            val intent = Intent(ACTION_USB_SERIAL_PERMISSION)
                .setPackage(context.packageName)
                .putExtra(EXTRA_ADAPTER_NAME, device.deviceName)
            val pi = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            mgr.requestPermission(device, pi)
            error("Requested Android USB permission for ${identifyChipset(device)} — will auto-open once granted.")
        }
        pendingPermissionDeviceName = null

        close()
        val conn = mgr.openDevice(device) ?: error("Unable to open USB device ${device.deviceName}")
        var selectedIntf: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var candidateIn: UsbEndpoint? = null
            var candidateOut: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) candidateIn = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) candidateOut = ep
                }
            }
            if (candidateIn != null && candidateOut != null) {
                selectedIntf = intf
                inEp = candidateIn
                outEp = candidateOut
                break
            }
        }

        val dataIntf = selectedIntf ?: error("No bulk UART endpoints found on ${device.deviceName}")
        check(conn.claimInterface(dataIntf, true)) { "Failed to claim USB UART interface" }

        connection = conn
        claimedInterface = dataIntf
        bulkIn = inEp
        bulkOut = outEp
        activeVendorId = device.vendorId

        if (device.vendorId == VENDOR_QINHENG_CH340) {
            // QinHeng CH340/CH341 controller initialization (vendor request 0xA1)
            conn.controlTransfer(0x40, CH340_REQ_SERIAL_INIT, 0, 0, null, 0, 500)
        } else if (device.vendorId == VENDOR_FTDI) {
            // FTDI_SIO_RESET (0x00)
            conn.controlTransfer(0x40, 0x00, 0, 0, null, 0, 500)
        }

        applyLineCoding(settings.baudRate, settings.dataBits, settings.stopBits, settings.parity)
        setModemLines(dtr = settings.dtrEnabled, rts = settings.rtsEnabled)

        "${identifyChipset(device)} (${device.deviceName}) @ ${settings.baudRate} ${framingSummary(settings)}"
    }

    suspend fun startReadLoop(onBytes: suspend (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext
        val epIn = bulkIn ?: return@withContext
        val buf = ByteArray(epIn.maxPacketSize.coerceAtLeast(64))
        while (isActive && connection === conn) {
            val read = conn.bulkTransfer(epIn, buf, buf.size, 200)
            if (read > 0) {
                // FTDI prepends 2 modem-status bytes on every bulk-IN packet
                val payload = if (activeVendorId == VENDOR_FTDI) {
                    if (read > 2) buf.copyOfRange(2, read) else ByteArray(0)
                } else {
                    buf.copyOf(read)
                }
                if (payload.isNotEmpty()) {
                    onBytes(payload)
                }
            } else {
                delay(15)
            }
        }
    }

    fun writeBytes(payload: ByteArray): Int {
        val conn = connection ?: return -1
        val epOut = bulkOut ?: return -1
        return conn.bulkTransfer(epOut, payload, payload.size, 1_000)
    }

    fun applyLineCoding(baudRate: Int, dataBits: Int, stopBits: Int, parity: String) {
        val conn = connection ?: return
        when (activeVendorId) {
            VENDOR_FTDI -> {
                val (baudValue, baudIndex) = encodeFtdiFractionalBaudDivisor(baudRate)
                conn.controlTransfer(0x40, FTDI_SIO_SET_BAUDRATE, baudValue, baudIndex, null, 0, 500)

                val parityCode = when (parity.uppercase()) {
                    "ODD" -> 1
                    "EVEN" -> 2
                    "MARK" -> 3
                    "SPACE" -> 4
                    else -> 0
                }
                val stopCode = if (stopBits == 2) 2 else 0
                val dataWord = dataBits.coerceIn(7, 8) or (parityCode shl 8) or (stopCode shl 11)
                ftdiDataControlWord = dataWord
                conn.controlTransfer(0x40, FTDI_SIO_SET_DATA, dataWord, 0, null, 0, 500)
            }
            VENDOR_QINHENG_CH340 -> {
                val (reg1312, reg0f2c) = encodeCh340BaudRegisters(baudRate)
                val dataCode = (dataBits.coerceIn(5, 8) - 5) and 0x03
                val stopBitFlag = if (stopBits == 2) 0x04 else 0x00
                val parityFlags = when (parity.uppercase()) {
                    "ODD" -> 0x08
                    "EVEN" -> 0x18
                    "MARK" -> 0x28
                    "SPACE" -> 0x38
                    else -> 0x00
                }
                val lcr = CH340_LCR_ENABLE_RX or CH340_LCR_ENABLE_TX or parityFlags or stopBitFlag or dataCode
                conn.controlTransfer(0x40, CH340_REQ_SERIAL_INIT, (lcr shl 8) or 0x9C, (reg0f2c shl 8) or (reg1312 and 0xFF), null, 0, 500)
                conn.controlTransfer(0x40, CH340_REQ_WRITE_REG, 0x1312, reg1312, null, 0, 500)
                conn.controlTransfer(0x40, CH340_REQ_WRITE_REG, 0x0F2C, reg0f2c, null, 0, 500)
                conn.controlTransfer(0x40, CH340_REQ_WRITE_REG, 0x2518, lcr, null, 0, 500)
            }
            VENDOR_SILABS_CP210X -> {
                conn.controlTransfer(0x41, 0x00, 0x0001, 0, null, 0, 500) // IFC_ENABLE
                val baudBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(baudRate).array()
                conn.controlTransfer(0x41, 0x1E, 0, 0, baudBuf, 4, 500) // SET_BAUDRATE
                val parityBits = when (parity.uppercase()) {
                    "ODD" -> 1
                    "EVEN" -> 2
                    "MARK" -> 3
                    "SPACE" -> 4
                    else -> 0
                }
                val stopCode = if (stopBits == 2) 2 else 0
                val lineCtl = (dataBits.coerceIn(5, 8) shl 8) or (parityBits shl 4) or stopCode
                conn.controlTransfer(0x41, 0x03, lineCtl, 0, null, 0, 500) // SET_LINE_CTL
            }
            else -> {
                // Standard USB CDC-ACM SET_LINE_CODING (0x20)
                val stopCode: Byte = if (stopBits == 2) 2 else 0
                val parityCode: Byte = when (parity.uppercase()) {
                    "ODD" -> 1
                    "EVEN" -> 2
                    else -> 0
                }
                val coding = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(baudRate)
                    .put(stopCode)
                    .put(parityCode)
                    .put(dataBits.coerceIn(5, 8).toByte())
                    .array()
                conn.controlTransfer(0x21, 0x20, 0, 0, coding, coding.size, 500)
            }
        }
    }

    fun setModemLines(dtr: Boolean, rts: Boolean) {
        val conn = connection ?: return
        when (activeVendorId) {
            VENDOR_FTDI -> {
                val value = (if (dtr) 1 else 0) or (if (rts) 2 else 0) or (0x0100 or 0x0200)
                conn.controlTransfer(0x40, 0x01, value, 0, null, 0, 500)
            }
            VENDOR_QINHENG_CH340 -> {
                // CH340 active-low modem control (0xA4): bit 5 = DTR (0x20), bit 6 = RTS (0x40)
                val assertedMask = (if (dtr) 0x20 else 0x00) or (if (rts) 0x40 else 0x00)
                val invertedValue = assertedMask.inv() and 0xFF
                conn.controlTransfer(0x40, CH340_REQ_MODEM_CTRL, invertedValue, 0, null, 0, 500)
            }
            VENDOR_SILABS_CP210X -> {
                val value = (if (dtr) 0x0001 else 0) or (if (rts) 0x0002 else 0) or 0x0300
                conn.controlTransfer(0x41, 0x07, value, 0, null, 0, 500)
            }
            else -> {
                val mask = (if (dtr) 0x01 else 0x00) or (if (rts) 0x02 else 0x00)
                conn.controlTransfer(0x21, 0x22, mask, 0, null, 0, 500)
            }
        }
    }

    suspend fun sendBreakSignal(durationMs: Int = 250) = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext
        when (activeVendorId) {
            VENDOR_FTDI -> {
                val baseWord = ftdiDataControlWord and 0x3FFF
                conn.controlTransfer(0x40, FTDI_SIO_SET_DATA, baseWord or 0x4000, 0, null, 0, 500)
                delay(durationMs.toLong())
                conn.controlTransfer(0x40, FTDI_SIO_SET_DATA, baseWord, 0, null, 0, 500)
            }
            VENDOR_QINHENG_CH340 -> {
                conn.controlTransfer(0x40, CH340_REQ_WRITE_REG, 0x1805, 0x0000, null, 0, 500)
                delay(durationMs.toLong())
                conn.controlTransfer(0x40, CH340_REQ_WRITE_REG, 0x1805, 0x0041, null, 0, 500)
            }
            else -> {
                // USB CDC-ACM SEND_BREAK (0x23)
                conn.controlTransfer(0x21, 0x23, durationMs.coerceIn(1, 5000), 0, null, 0, 500)
                delay(durationMs.toLong())
            }
        }
    }

    fun close() {
        readJob?.cancel()
        readJob = null
        val conn = connection
        val intf = claimedInterface
        connection = null
        claimedInterface = null
        bulkIn = null
        bulkOut = null
        if (conn != null) {
            if (intf != null) runCatching { conn.releaseInterface(intf) }
            runCatching { conn.close() }
        }
    }

    companion object {
        const val ACTION_USB_SERIAL_PERMISSION = "com.nexusts.app.USB_SERIAL_PERMISSION"
        const val EXTRA_ADAPTER_NAME = "adapter_name"

        const val VENDOR_FTDI = 0x0403 // 1027
        const val VENDOR_SILABS_CP210X = 0x10C4 // 4292
        const val VENDOR_PROLIFIC_PL2303 = 0x067B // 1659
        const val VENDOR_QINHENG_CH340 = 0x1A86 // 6790

        private const val FTDI_SIO_SET_BAUDRATE = 0x03
        private const val FTDI_SIO_SET_DATA = 0x04

        private const val CH340_REQ_WRITE_REG = 0x9A
        private const val CH340_REQ_SERIAL_INIT = 0xA1
        private const val CH340_REQ_MODEM_CTRL = 0xA4
        private const val CH340_LCR_ENABLE_RX = 0x80
        private const val CH340_LCR_ENABLE_TX = 0x40

        /**
         * Encodes FTDI FT232BM/FT232R/FT231X fractional 1/8th baud divisor into `(wValue, wIndex)`.
         */
        fun encodeFtdiFractionalBaudDivisor(baudRate: Int): Pair<Int, Int> {
            val safeBaud = baudRate.coerceIn(300, 3_000_000)
            if (safeBaud >= 3_000_000) return 0 to 0
            if (safeBaud >= 2_000_000) return 1 to 0

            val subEighthsCode = intArrayOf(0, 3, 2, 4, 1, 5, 6, 7)
            val divisorEighths = ((24_000_000L + (safeBaud / 2)) / safeBaud)
                .coerceIn(16L, 131_071L)
                .toInt()
            val integerDivisor = divisorEighths ushr 3
            val fracCode = subEighthsCode[divisorEighths and 0x07]

            val encoded = integerDivisor or (fracCode shl 14)
            val wValue = encoded and 0xFFFF
            val wIndex = (encoded ushr 16) and 0xFFFF
            return wValue to wIndex
        }

        /**
         * Encodes QinHeng CH340/CH341 `(reg_0x1312, reg_0x0F2C)` baud generator prescaler & factor.
         */
        fun encodeCh340BaudRegisters(baudRate: Int): Pair<Int, Int> {
            val safeBaud = baudRate.coerceIn(300, 2_000_000)
            val factor: Long
            val prescaler: Int
            if (safeBaud == 921_600) {
                prescaler = 7
                factor = 0xF300L
                val a = (factor.toInt() and 0xFF00) or prescaler or 0x0080
                return a to 0
            }
            val baudBaseFactor = 1_532_620_800L
            var rawFactor = baudBaseFactor / safeBaud
            var div = 3
            while (rawFactor > 0xFFF0L && div > 0) {
                rawFactor = rawFactor shr 3
                div--
            }
            if (rawFactor > 0xFFF0L) rawFactor = 0xFFF0L
            factor = 0x10000L - rawFactor
            prescaler = div
            val reg1312 = ((factor.toInt() and 0xFF00) or prescaler or 0x0080) and 0xFFFF
            val reg0f2c = (factor.toInt() and 0x00FF)
            return reg1312 to reg0f2c
        }

        fun identifyChipset(device: UsbDevice): String? {
            return when (device.vendorId) {
                VENDOR_FTDI -> "FTDI FT232R/FT231X UART"
                VENDOR_SILABS_CP210X -> "Silicon Labs CP210x UART"
                VENDOR_PROLIFIC_PL2303 -> "Prolific PL2303 UART"
                VENDOR_QINHENG_CH340 -> "QinHeng CH340/CH341 UART"
                else -> {
                    val hasCdc = (0 until device.interfaceCount).any { idx ->
                        val c = device.getInterface(idx).interfaceClass
                        c == UsbConstants.USB_CLASS_COMM || c == UsbConstants.USB_CLASS_CDC_DATA
                    }
                    if (hasCdc) "USB CDC-ACM Serial Console" else null
                }
            }
        }

        fun framingSummary(settings: ProtocolSpecificSettings.SerialUart): String {
            val pChar = settings.parity.firstOrNull()?.uppercaseChar() ?: 'N'
            return "${settings.dataBits}${pChar}${settings.stopBits}"
        }
    }
}

/**
 * Interactive Material Design 3 Expressive USB-C Serial / UART Console View (`VaultProtocolType.SERIAL`).
 */
@Composable
fun UsbSerialConsoleView(
    endpointLabel: String,
    initialSettings: ProtocolSpecificSettings.SerialUart,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val driver = remember { UsbSerialPortDriver(context) }

    var baudRate by remember(initialSettings) { mutableIntStateOf(initialSettings.baudRate) }
    var dtrEnabled by remember(initialSettings) { mutableStateOf(initialSettings.dtrEnabled) }
    var rtsEnabled by remember(initialSettings) { mutableStateOf(initialSettings.rtsEnabled) }
    var baudMenuExpanded by remember { mutableStateOf(false) }
    var adapters by remember { mutableStateOf(driver.listCompatibleAdapters()) }
    var hardwareConnected by remember { mutableStateOf(false) }
    var rxBytes by remember { mutableLongStateOf(0L) }
    var txBytes by remember { mutableLongStateOf(0L) }
    var commandText by remember { mutableStateOf("") }
    var rxLoopJob by remember { mutableStateOf<Job?>(null) }

    val consoleLines = remember(endpointLabel) {
        mutableStateListOf(
            "[NexusTS UART] USB-C Hardware Serial Console ready for $endpointLabel",
            "[Framing] ${initialSettings.baudRate} baud • ${UsbSerialPortDriver.framingSummary(initialSettings)} • Flow=${initialSettings.flowControl}",
            "[Tip] Attach FTDI / CP210x / PL2303 / CH340 / CDC-ACM adapter or use 'Send BREAK' for Cisco/Juniper ROMMON."
        )
    }

    fun appendConsoleLineOnMain(line: String) {
        consoleLines += line
        val overflow = consoleLines.size - MAX_CONSOLE_LINES
        if (overflow > 0) {
            consoleLines.removeRange(0, overflow)
        }
    }

    val latestBaudRate by rememberUpdatedState(baudRate)
    val latestDtrEnabled by rememberUpdatedState(dtrEnabled)
    val latestRtsEnabled by rememberUpdatedState(rtsEnabled)
    val latestInitialSettings by rememberUpdatedState(initialSettings)

    fun openAdapterSessionAsync(targetDeviceName: String) {
        scope.launch {
            val currentCfg = latestInitialSettings.copy(
                baudRate = latestBaudRate,
                dtrEnabled = latestDtrEnabled,
                rtsEnabled = latestRtsEnabled
            )
            val result = withContext(Dispatchers.IO) {
                driver.openAndConfigure(targetDeviceName, currentCfg) {}
            }
            val refreshed = withContext(Dispatchers.IO) { driver.listCompatibleAdapters() }
            adapters = refreshed
            result
                .onSuccess { summary ->
                    hardwareConnected = true
                    appendConsoleLineOnMain("[Connected] $summary")
                    rxLoopJob?.cancel()
                    rxLoopJob = scope.launch {
                        driver.startReadLoop { chunk ->
                            val decodedLines = chunk
                                .decodeToString(throwOnInvalidSequence = false)
                                .split('\n')
                                .map { it.trimEnd('\r') }
                                .filter { it.isNotEmpty() }
                            withContext(Dispatchers.Main) {
                                rxBytes += chunk.size
                                for (line in decodedLines) {
                                    appendConsoleLineOnMain(line)
                                }
                            }
                        }
                    }
                }
                .onFailure { err ->
                    hardwareConnected = false
                    appendConsoleLineOnMain("[USB Notice] ${err.message}")
                }
        }
    }

    val connectFirstAdapter: () -> Unit = {
        scope.launch {
            val discovered = withContext(Dispatchers.IO) { driver.listCompatibleAdapters() }
            adapters = discovered
            val first = discovered.firstOrNull()
            if (first == null) {
                hardwareConnected = false
                appendConsoleLineOnMain("[USB Scan] 0 physical USB-C UART adapters attached (interactive console simulation active).")
            } else {
                openAdapterSessionAsync(first.deviceName)
            }
        }
    }

    DisposableEffect(context, driver) {
        val permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != UsbSerialPortDriver.ACTION_USB_SERIAL_PERMISSION) return
                val mgr = driver.usbManager ?: return
                val extraDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val fallbackName = intent.getStringExtra(UsbSerialPortDriver.EXTRA_ADAPTER_NAME)
                    ?: driver.pendingPermissionDeviceName
                val device = extraDevice ?: fallbackName?.let { mgr.deviceList[it] }
                if (device != null && mgr.hasPermission(device)) {
                    openAdapterSessionAsync(device.deviceName)
                } else {
                    scope.launch(Dispatchers.Main) {
                        appendConsoleLineOnMain("[USB Permission] Permission denied for ${device?.deviceName ?: "USB UART adapter"}.")
                    }
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            IntentFilter(UsbSerialPortDriver.ACTION_USB_SERIAL_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            runCatching { context.unregisterReceiver(permissionReceiver) }
            rxLoopJob?.cancel()
            driver.close()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        // Hardware UART Control Strip
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
                    imageVector = Icons.Outlined.Usb,
                    contentDescription = null,
                    tint = NexusTheme.extendedColors.sshAccent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (hardwareConnected) "USB-UART LIVE" else endpointLabel,
                    style = NexusTheme.monospace.keycapShortcut,
                    color = if (hardwareConnected) NexusTheme.extendedColors.statusConnected else MaterialTheme.colorScheme.onSurface
                )

                // Baud Rate Selector
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { baudMenuExpanded = true }) {
                        Text("$baudRate ${UsbSerialPortDriver.framingSummary(initialSettings)}", style = MaterialTheme.typography.labelSmall)
                    }
                    DropdownMenu(
                        expanded = baudMenuExpanded,
                        onDismissRequest = { baudMenuExpanded = false }
                    ) {
                        listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600).forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text("$candidate baud") },
                                onClick = {
                                    baudRate = candidate
                                    baudMenuExpanded = false
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            driver.applyLineCoding(
                                                candidate,
                                                initialSettings.dataBits,
                                                initialSettings.stopBits,
                                                initialSettings.parity
                                            )
                                        }
                                        appendConsoleLineOnMain(
                                            "[UART] Baud rate set to $candidate (${UsbSerialPortDriver.framingSummary(initialSettings)})"
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                FilterChip(
                    selected = dtrEnabled,
                    onClick = {
                        val nextDtr = !dtrEnabled
                        val currentRts = rtsEnabled
                        dtrEnabled = nextDtr
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                driver.setModemLines(nextDtr, currentRts)
                            }
                            appendConsoleLineOnMain(
                                "[Modem] DTR=${if (nextDtr) "HIGH" else "LOW"}, RTS=${if (currentRts) "HIGH" else "LOW"}"
                            )
                        }
                    },
                    label = { Text("DTR", style = MaterialTheme.typography.labelSmall) }
                )

                FilterChip(
                    selected = rtsEnabled,
                    onClick = {
                        val currentDtr = dtrEnabled
                        val nextRts = !rtsEnabled
                        rtsEnabled = nextRts
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                driver.setModemLines(currentDtr, nextRts)
                            }
                            appendConsoleLineOnMain(
                                "[Modem] DTR=${if (currentDtr) "HIGH" else "LOW"}, RTS=${if (nextRts) "HIGH" else "LOW"}"
                            )
                        }
                    },
                    label = { Text("RTS", style = MaterialTheme.typography.labelSmall) }
                )

                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            driver.sendBreakSignal(250)
                            appendConsoleLineOnMain(
                                "[UART BREAK] Asserted 250ms spacing BREAK signal (Cisco ROMMON / Juniper loader interrupt)"
                            )
                        }
                    }
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Send BREAK", style = MaterialTheme.typography.labelSmall)
                }

                AssistChip(
                    onClick = connectFirstAdapter,
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    label = { Text("Scan USB (${adapters.size})", style = MaterialTheme.typography.labelSmall) }
                )

                Text(
                    text = "RX ${rxBytes}B / TX ${txBytes}B",
                    style = NexusTheme.monospace.keycapShortcut,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider()

        // Monospace Terminal Output Viewport
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
                items(consoleLines) { line ->
                    Text(
                        text = line,
                        style = NexusTheme.monospace.logEntry,
                        color = if (line.startsWith("[UART BREAK]")) {
                            Color(0xFFFFB86B)
                        } else {
                            Color(0xFF8BE9FD)
                        }
                    )
                }
            }
        }

        // Interactive Serial Frame Input Bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            val dispatchSerialFrame: () -> Unit = {
                val textSnapshot = commandText
                if (textSnapshot.isNotEmpty() || initialSettings.lineEnding.isNotEmpty()) {
                    commandText = ""
                    val terminator = when (initialSettings.lineEnding.uppercase()) {
                        "LF" -> "\n"
                        "CR" -> "\r"
                        else -> "\r\n"
                    }
                    val payload = (textSnapshot + terminator).toByteArray(Charsets.UTF_8)
                    scope.launch {
                        val written = withContext(Dispatchers.IO) {
                            driver.writeBytes(payload)
                        }
                        txBytes += payload.size
                        appendConsoleLineOnMain(
                            "uart> $textSnapshot" + if (written < 0) " (loopback)" else " ($written B)"
                        )
                    }
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
                    value = commandText,
                    onValueChange = { commandText = it },
                    placeholder = { Text("Send serial command (e.g. show version, confreg 0x2142)…", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = dispatchSerialFrame) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("TX")
                }
            }
        }
    }
}
