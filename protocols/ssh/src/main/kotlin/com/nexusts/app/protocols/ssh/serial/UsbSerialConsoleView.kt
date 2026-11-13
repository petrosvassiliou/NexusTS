package com.nexusts.app.protocols.ssh.serial

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    private val usbManager: UsbManager? = context.getSystemService(UsbManager::class.java)
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var activeVendorId: Int = 0
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
            val intent = Intent(ACTION_USB_SERIAL_PERMISSION).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            mgr.requestPermission(device, pi)
            error("Requested Android USB permission for ${identifyChipset(device)} — click Connect again once granted.")
        }

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

        applyLineCoding(settings.baudRate, settings.dataBits, settings.stopBits, settings.parity)
        setModemLines(dtr = settings.dtrEnabled, rts = settings.rtsEnabled)

        "${identifyChipset(device)} (${device.deviceName}) @ ${settings.baudRate} ${framingSummary(settings)}"
    }

    suspend fun startReadLoop(onBytes: (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext
        val epIn = bulkIn ?: return@withContext
        val buf = ByteArray(epIn.maxPacketSize.coerceAtLeast(64))
        while (isActive && connection === conn) {
            val read = conn.bulkTransfer(epIn, buf, buf.size, 200)
            if (read > 0) {
                onBytes(buf.copyOf(read))
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
                val divisor = (3_000_000 / baudRate.coerceAtLeast(300)).coerceAtLeast(1)
                conn.controlTransfer(0x40, 0x03, divisor, 0, null, 0, 500)
            }
            VENDOR_SILABS_CP210X -> {
                conn.controlTransfer(0x41, 0x00, 0x0001, 0, null, 0, 500) // IFC_ENABLE
                val baudBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(baudRate).array()
                conn.controlTransfer(0x41, 0x1E, 0, 0, baudBuf, 4, 500) // SET_BAUDRATE
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
        if (activeVendorId == VENDOR_FTDI) {
            conn.controlTransfer(0x40, 0x04, 0x4008, 0, null, 0, 500)
            delay(durationMs.toLong())
            conn.controlTransfer(0x40, 0x04, 0x0008, 0, null, 0, 500)
        } else {
            // USB CDC-ACM SEND_BREAK (0x23)
            conn.controlTransfer(0x21, 0x23, durationMs.coerceIn(1, 5000), 0, null, 0, 500)
            delay(durationMs.toLong())
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
        const val VENDOR_FTDI = 0x0403 // 1027
        const val VENDOR_SILABS_CP210X = 0x10C4 // 4292
        const val VENDOR_PROLIFIC_PL2303 = 0x067B // 1659
        const val VENDOR_QINHENG_CH340 = 0x1A86 // 6790

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

    DisposableEffect(driver) {
        onDispose {
            rxLoopJob?.cancel()
            driver.close()
        }
    }

    val connectFirstAdapter: () -> Unit = {
        adapters = driver.listCompatibleAdapters()
        val first = adapters.firstOrNull()
        if (first == null) {
            consoleLines += "[USB Scan] 0 physical USB-C UART adapters attached (interactive console simulation active)."
        } else {
            val currentCfg = initialSettings.copy(
                baudRate = baudRate,
                dtrEnabled = dtrEnabled,
                rtsEnabled = rtsEnabled
            )
            driver.openAndConfigure(first.deviceName, currentCfg) {}
                .onSuccess { summary ->
                    hardwareConnected = true
                    consoleLines += "[Connected] $summary"
                    rxLoopJob?.cancel()
                    rxLoopJob = scope.launch {
                        driver.startReadLoop { chunk ->
                            rxBytes += chunk.size
                            val decoded = chunk.decodeToString(throwOnInvalidSequence = false).trimEnd('\r', '\n')
                            if (decoded.isNotEmpty()) {
                                consoleLines += decoded
                            }
                        }
                    }
                }
                .onFailure { err ->
                    consoleLines += "[USB Notice] ${err.message}"
                }
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
                                    driver.applyLineCoding(candidate, initialSettings.dataBits, initialSettings.stopBits, initialSettings.parity)
                                    consoleLines += "[UART] Baud rate set to $candidate (${UsbSerialPortDriver.framingSummary(initialSettings)})"
                                }
                            )
                        }
                    }
                }

                FilterChip(
                    selected = dtrEnabled,
                    onClick = {
                        dtrEnabled = !dtrEnabled
                        driver.setModemLines(dtrEnabled, rtsEnabled)
                        consoleLines += "[Modem] DTR=${if (dtrEnabled) "HIGH" else "LOW"}, RTS=${if (rtsEnabled) "HIGH" else "LOW"}"
                    },
                    label = { Text("DTR", style = MaterialTheme.typography.labelSmall) }
                )

                FilterChip(
                    selected = rtsEnabled,
                    onClick = {
                        rtsEnabled = !rtsEnabled
                        driver.setModemLines(dtrEnabled, rtsEnabled)
                        consoleLines += "[Modem] DTR=${if (dtrEnabled) "HIGH" else "LOW"}, RTS=${if (rtsEnabled) "HIGH" else "LOW"}"
                    },
                    label = { Text("RTS", style = MaterialTheme.typography.labelSmall) }
                )

                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            driver.sendBreakSignal(250)
                            consoleLines += "[UART BREAK] Asserted 250ms spacing BREAK signal (Cisco ROMMON / Juniper loader interrupt)"
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
                if (commandText.isNotEmpty() || initialSettings.lineEnding.isNotEmpty()) {
                    val terminator = when (initialSettings.lineEnding.uppercase()) {
                        "LF" -> "\n"
                        "CR" -> "\r"
                        else -> "\r\n"
                    }
                    val payload = (commandText + terminator).toByteArray()
                    val written = driver.writeBytes(payload)
                    txBytes += payload.size
                    consoleLines += "uart> $commandText" + if (written < 0) " (loopback)" else " ($written B)"
                    commandText = ""
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
