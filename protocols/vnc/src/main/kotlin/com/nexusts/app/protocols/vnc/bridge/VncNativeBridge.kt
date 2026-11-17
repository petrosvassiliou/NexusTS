package com.nexusts.app.protocols.vnc.bridge

import android.graphics.Bitmap
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nexusts.app.core.ui.input.RawHardwareKeyPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong
import kotlin.system.measureTimeMillis

@Serializable
data class PortProbeResult(
    val port: Int,
    val open: Boolean,
    val connect_latency_ms: Long,
    val guessed_service: String,
    val service_banner: String? = null
)

data class PingSample(
    val sequence: Int,
    val targetIp: String,
    val latencyMs: Long,
    val reachable: Boolean
)

@Serializable
data class NativeTracerouteHopDto(
    val ttl: Int,
    val responder_addr: String,
    val rtt_ms: Double,
    val reached_destination: Boolean,
    val timed_out: Boolean
)

data class TracerouteHop(
    val hopNumber: Int,
    val routerAddress: String,
    val rttMs: Double,
    val reachedDestination: Boolean,
    val timedOut: Boolean
)

data class VncSessionState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val authRequired: Boolean = false,
    val desktopName: String = "",
    val framebufferWidth: Int = 1280,
    val framebufferHeight: Int = 720,
    val negotiatedProtocol: String = "RFB 3.8",
    val statusMessage: String = "Idle",
    val viewOnly: Boolean = false
)

/**
 * JNI Bridge to Rust `nexusts-vnc` and `nexusts-netutils` (`libnexusts_jni.so`),
 * with automatic JVM socket fallback for local execution and host unit tests.
 */
object VncNativeBridge {
    const val RFB_ENCODING_RAW: Int = 0
    const val RFB_ENCODING_COPYRECT: Int = 1
    const val RFB_ENCODING_HEXTILE: Int = 5
    const val RFB_ENCODING_TIGHT: Int = 7
    const val RFB_ENCODING_ZRLE: Int = 16
    const val RFB_ENCODING_DESKTOP_SIZE: Int = -223

    const val XK_SHIFT_L: Int = 0xFFE1
    const val XK_SHIFT_R: Int = 0xFFE2
    const val XK_CONTROL_L: Int = 0xFFE3
    const val XK_CONTROL_R: Int = 0xFFE4
    const val XK_ALT_L: Int = 0xFFE9
    const val XK_ALT_R: Int = 0xFFEA
    const val XK_SUPER_L: Int = 0xFFEB
    const val XK_SUPER_R: Int = 0xFFEC

    private val SHIFTED_ASCII_CODEPOINTS: Set<Int> = setOf(
        '~'.code, '!'.code, '@'.code, '#'.code, '$'.code, '%'.code, '^'.code,
        '&'.code, '*'.code, '('.code, ')'.code, '_'.code, '+'.code, '{'.code,
        '}'.code, '|'.code, ':'.code, '"'.code, '<'.code, '>'.code, '?'.code
    )

    val isNativeLoaded: Boolean = runCatching {
        System.loadLibrary("nexusts_jni")
        true
    }.getOrDefault(false)

    @JvmStatic
    external fun nativeCreateFramebuffer(width: Int, height: Int): Long

    @JvmStatic
    external fun nativeApplyRfbRect(
        handle: Long,
        encoding: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        payload: ByteArray
    ): ByteArray?

    @JvmStatic
    external fun nativeDestroyFramebuffer(handle: Long)

    // --- Live Async RFB 3.3 / 3.7 / 3.8 & Apple ARD Type 30 Session JNI ---

    @JvmStatic
    external fun nativeVncSessionConnect(
        host: String,
        port: Int,
        username: String,
        password: String,
        sharedSession: Boolean,
        viewOnly: Boolean
    ): Long

    @JvmStatic
    external fun nativeVncSessionIsConnected(handle: Long): Boolean

    @JvmStatic
    external fun nativeVncSessionLastError(handle: Long): String

    @JvmStatic
    external fun nativeVncSessionDesktopName(handle: Long): String

    @JvmStatic
    external fun nativeVncSessionFrameSequence(handle: Long): Long

    @JvmStatic
    external fun nativeVncSessionGetDimensions(handle: Long): IntArray?

    @JvmStatic
    external fun nativeVncSessionCopyFrameRgba(handle: Long, dstRgba: ByteArray): Int

    @JvmStatic
    external fun nativeVncSessionGetFrameRgba(handle: Long): ByteArray?

    @JvmStatic
    external fun nativeVncSessionSendPointer(handle: Long, buttonMask: Int, x: Int, y: Int): Boolean

    @JvmStatic
    external fun nativeVncSessionSendKey(handle: Long, down: Boolean, keysym: Int): Boolean

    @JvmStatic
    external fun nativeVncSessionSendClipboard(handle: Long, text: String): Boolean

    @JvmStatic
    external fun nativeVncSessionPullClipboard(handle: Long): String?

    @JvmStatic
    external fun nativeVncSessionDisconnect(handle: Long)

    // --- Network Utilities JNI ---

    @JvmStatic
    external fun nativeSendWakeOnLan(mac: String, broadcastIp: String, port: Int): Int

    @JvmStatic
    external fun nativeSendWakeOnLanWithSecureOn(
        macAddress: String,
        broadcastIp: String,
        port: Int,
        secureOnHex: String
    ): Int

    @JvmStatic
    external fun nativeProbeTcpPortJson(host: String, port: Int, timeoutMs: Int): String

    @JvmStatic
    external fun nativeScanPortsJson(
        host: String,
        startPort: Int,
        endPort: Int,
        timeoutMs: Int,
        concurrency: Int
    ): String

    @JvmStatic
    external fun nativeTcpLatencyPingMs(host: String, port: Int, timeoutMs: Int): Long

    @JvmStatic
    external fun nativeTraceRouteJson(host: String, probePort: Int, maxHops: Int, timeoutMs: Int): String

    @JvmStatic
    external fun nativeGrabTcpBanner(host: String, port: Int, timeoutMs: Int): String

    // --- WinRM NTLMv2 + [MS-WSMV] SPNEGO Session Encryption & One-Shot SSH Exec JNI ---

    @JvmStatic
    external fun nativeWinrmWsmanPost(
        host: String,
        port: Int,
        path: String,
        username: String,
        password: String,
        domain: String,
        soapXml: String,
        timeoutMs: Int
    ): String

    @JvmStatic
    external fun nativeNtlmBuildNegotiate(): String

    @JvmStatic
    external fun nativeNtlmBuildAuthenticate(
        challengeB64: String,
        username: String,
        password: String,
        domain: String,
        spnHost: String
    ): String

    @JvmStatic
    external fun nativeSshExecCommand(
        host: String,
        port: Int,
        username: String,
        password: String,
        privateKeyPem: String,
        passphrase: String,
        command: String,
        timeoutSecs: Int
    ): String

    private val json = Json { ignoreUnknownKeys = true }

    private val pingTimeRegex = Regex("time[=<]([0-9.]+)\\s*ms", RegexOption.IGNORE_CASE)
    private val icmpHopRegex = Regex("(?:From|from)\\s+([0-9a-fA-F:.]+)")

    fun requiresShiftModifier(keysym: Int): Boolean =
        (keysym in 'A'.code..'Z'.code) || (keysym in SHIFTED_ASCII_CODEPOINTS)

    fun mapAndroidKeyCodeToX11Keysym(keyCode: Int, unicodeChar: Int): Int? {
        return when (keyCode) {
            AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> 0xFF0D
            AndroidKeyEvent.KEYCODE_DEL -> 0xFF08
            AndroidKeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF
            AndroidKeyEvent.KEYCODE_TAB -> 0xFF09
            AndroidKeyEvent.KEYCODE_ESCAPE -> 0xFF1B
            AndroidKeyEvent.KEYCODE_DPAD_UP -> 0xFF52
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
            AndroidKeyEvent.KEYCODE_MOVE_HOME -> 0xFF50
            AndroidKeyEvent.KEYCODE_MOVE_END -> 0xFF57
            AndroidKeyEvent.KEYCODE_PAGE_UP -> 0xFF55
            AndroidKeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56
            AndroidKeyEvent.KEYCODE_INSERT -> 0xFF63
            AndroidKeyEvent.KEYCODE_SHIFT_LEFT -> XK_SHIFT_L
            AndroidKeyEvent.KEYCODE_SHIFT_RIGHT -> XK_SHIFT_R
            AndroidKeyEvent.KEYCODE_CTRL_LEFT -> XK_CONTROL_L
            AndroidKeyEvent.KEYCODE_CTRL_RIGHT -> XK_CONTROL_R
            AndroidKeyEvent.KEYCODE_ALT_LEFT -> XK_ALT_L
            AndroidKeyEvent.KEYCODE_ALT_RIGHT -> XK_ALT_R
            AndroidKeyEvent.KEYCODE_META_LEFT -> XK_SUPER_L
            AndroidKeyEvent.KEYCODE_META_RIGHT -> XK_SUPER_R
            in AndroidKeyEvent.KEYCODE_F1..AndroidKeyEvent.KEYCODE_F12 ->
                0xFFBE + (keyCode - AndroidKeyEvent.KEYCODE_F1)
            else -> when {
                unicodeChar in 0x20..0x7E || unicodeChar in 0xA0..0xFF -> unicodeChar
                unicodeChar > 0xFF -> 0x01000000 or unicodeChar
                keyCode in AndroidKeyEvent.KEYCODE_A..AndroidKeyEvent.KEYCODE_Z ->
                    'a'.code + (keyCode - AndroidKeyEvent.KEYCODE_A)
                keyCode in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 ->
                    '0'.code + (keyCode - AndroidKeyEvent.KEYCODE_0)
                else -> null
            }
        }
    }

    suspend fun wakeOnLan(
        macAddress: String,
        broadcastAddress: String = "255.255.255.255",
        port: Int = 9,
        secureOnHex: String = ""
    ): Int = withContext(Dispatchers.IO) {
        val trimmedTarget = broadcastAddress.ifBlank { "255.255.255.255" }.trim()
        val trimmedSecureOn = secureOnHex.trim()
        if (isNativeLoaded) {
            return@withContext if (trimmedSecureOn.isNotBlank()) {
                nativeSendWakeOnLanWithSecureOn(macAddress.trim(), trimmedTarget, port, trimmedSecureOn)
            } else {
                nativeSendWakeOnLan(macAddress.trim(), trimmedTarget, port)
            }
        }

        val macBytes = parseSixHexBytes(macAddress, "MAC address")
        val secureOnBytes = if (trimmedSecureOn.isNotBlank()) {
            parseSixHexBytes(trimmedSecureOn, "SecureOn password")
        } else {
            null
        }
        val packetBytes = ByteArray(if (secureOnBytes != null) 108 else 102)
        for (i in 0 until 6) packetBytes[i] = 0xFF.toByte()
        for (rep in 0 until 16) {
            System.arraycopy(macBytes, 0, packetBytes, 6 + rep * 6, 6)
        }
        if (secureOnBytes != null) {
            System.arraycopy(secureOnBytes, 0, packetBytes, 102, 6)
        }
        DatagramSocket().use { sock ->
            sock.broadcast = true
            val packet = DatagramPacket(
                packetBytes,
                packetBytes.size,
                InetAddress.getByName(trimmedTarget),
                port.coerceIn(1, 65535)
            )
            sock.send(packet)
        }
        packetBytes.size
    }

    private fun parseSixHexBytes(input: String, label: String): ByteArray {
        val cleaned = input.trim()
        val parts = if (cleaned.contains(':') || cleaned.contains('-')) {
            cleaned.split(':', '-').filter { it.isNotBlank() }
        } else if (cleaned.length == 12) {
            cleaned.chunked(2)
        } else {
            emptyList()
        }
        require(parts.size == 6) { "Invalid $label: $input" }
        return ByteArray(6) { idx ->
            val v = parts[idx].toIntOrNull(16)
                ?: throw IllegalArgumentException("Invalid hex byte '${parts[idx]}' in $label")
            require(v in 0..255) { "Hex byte out of range in $label: ${parts[idx]}" }
            v.toByte()
        }
    }

    private fun resolveSystemPingBinary(): String? {
        return listOf("/system/bin/ping", "/bin/ping", "/usr/bin/ping")
            .firstOrNull { path -> File(path).canExecute() }
    }

    suspend fun runTcpPing(
        host: String,
        port: Int = 0,
        count: Int = 5,
        intervalMs: Long = 500L
    ): List<PingSample> = withContext(Dispatchers.IO) {
        val trimmedHost = host.trim()
        require(trimmedHost.isNotEmpty()) { "Target host is required" }
        val resolvedIp = runCatching { InetAddress.getByName(trimmedHost).hostAddress ?: trimmedHost }
            .getOrDefault(trimmedHost)
        val totalSamples = count.coerceIn(1, 100)
        val pauseMs = intervalMs.coerceIn(0L, 10_000L)
        val pingBin = resolveSystemPingBinary()
        val samples = ArrayList<PingSample>(totalSamples)

        for (seq in 1..totalSamples) {
            coroutineContext.ensureActive()
            var icmpSample: PingSample? = null
            if (pingBin != null && port <= 0) {
                icmpSample = runCatching {
                    val startedNs = System.nanoTime()
                    val proc = ProcessBuilder(pingBin, "-n", "-c", "1", "-W", "2", resolvedIp)
                        .redirectErrorStream(true)
                        .start()
                    val output = proc.inputStream.bufferedReader().use { it.readText() }
                    val exitCode = proc.waitFor()
                    if (exitCode == 0) {
                        val parsedMs = pingTimeRegex.find(output)?.groupValues?.get(1)?.toDoubleOrNull()
                        val rtt = parsedMs?.roundToLong()
                            ?: ((System.nanoTime() - startedNs) / 1_000_000L)
                        PingSample(sequence = seq, targetIp = resolvedIp, latencyMs = rtt, reachable = true)
                    } else {
                        null
                    }
                }.getOrNull()
            }

            val sample = icmpSample ?: run {
                if (isNativeLoaded) {
                    val nativeRtt = runCatching {
                        nativeTcpLatencyPingMs(resolvedIp, port.coerceIn(0, 65535), 1500)
                    }.getOrDefault(-1L)
                    if (nativeRtt >= 0L) {
                        return@run PingSample(
                            sequence = seq,
                            targetIp = resolvedIp,
                            latencyMs = nativeRtt,
                            reachable = true
                        )
                    }
                }
                val fallbackPorts = if (port > 0) intArrayOf(port) else intArrayOf(443, 80, 22, 3389)
                var ok = false
                val elapsed = measureTimeMillis {
                    for (candidatePort in fallbackPorts) {
                        val reached = try {
                            Socket().use { s -> s.connect(InetSocketAddress(resolvedIp, candidatePort), 1200) }
                            true
                        } catch (_: ConnectException) {
                            // TCP RST means the host IP stack responded directly and is alive/reachable.
                            true
                        } catch (_: Throwable) {
                            false
                        }
                        if (reached) {
                            ok = true
                            break
                        }
                    }
                }
                PingSample(sequence = seq, targetIp = resolvedIp, latencyMs = elapsed, reachable = ok)
            }

            samples += sample
            if (seq < totalSamples && pauseMs > 0L) {
                delay(pauseMs)
            }
        }
        samples
    }

    suspend fun runTraceroute(
        host: String,
        probePort: Int = 443,
        maxHops: Int = 16,
        perHopTimeoutMs: Int = 450
    ): List<TracerouteHop> = withContext(Dispatchers.IO) {
        val trimmedHost = host.trim()
        require(trimmedHost.isNotEmpty()) { "Target host is required" }
        val boundedHops = maxHops.coerceIn(1, 30)
        val destAddress = runCatching { InetAddress.getByName(trimmedHost).hostAddress ?: trimmedHost }
            .getOrDefault(trimmedHost)

        // 1. Prefer real hop-by-hop TTL probing via system ping utility when available
        val pingBin = resolveSystemPingBinary()
        if (pingBin != null) {
            val icmpHops = mutableListOf<TracerouteHop>()
            var anyHopResponded = false
            val isIpv6 = destAddress.contains(':')
            for (ttl in 1..boundedHops) {
                coroutineContext.ensureActive()
                val startedNs = System.nanoTime()
                val hopResult = runCatching {
                    val cmd = if (isIpv6) {
                        listOf(pingBin, "-6", "-n", "-c", "1", "-t", ttl.toString(), "-W", "1", destAddress)
                    } else {
                        listOf(pingBin, "-n", "-c", "1", "-t", ttl.toString(), "-W", "1", destAddress)
                    }
                    val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                    val out = proc.inputStream.bufferedReader().use { it.readText() }
                    val exitCode = proc.waitFor()
                    val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000.0
                    val responder = icmpHopRegex.find(out)?.groupValues?.get(1)?.trimEnd(':', ',')
                    val parsedRtt = pingTimeRegex.find(out)?.groupValues?.get(1)?.toDoubleOrNull() ?: elapsedMs

                    if (exitCode == 0) {
                        anyHopResponded = true
                        TracerouteHop(
                            hopNumber = ttl,
                            routerAddress = responder ?: destAddress,
                            rttMs = parsedRtt,
                            reachedDestination = true,
                            timedOut = false
                        )
                    } else if (!responder.isNullOrBlank()) {
                        anyHopResponded = true
                        val reached = responder.equals(destAddress, ignoreCase = true)
                        TracerouteHop(
                            hopNumber = ttl,
                            routerAddress = responder,
                            rttMs = parsedRtt,
                            reachedDestination = reached,
                            timedOut = false
                        )
                    } else {
                        TracerouteHop(
                            hopNumber = ttl,
                            routerAddress = "*",
                            rttMs = elapsedMs,
                            reachedDestination = false,
                            timedOut = true
                        )
                    }
                }.getOrNull()

                if (hopResult != null) {
                    icmpHops += hopResult
                    if (hopResult.reachedDestination) {
                        return@withContext icmpHops
                    }
                }
            }
            if (anyHopResponded) {
                return@withContext icmpHops
            }
        }

        // 2. Native Rust UDP/ICMP TTL probe
        if (isNativeLoaded) {
            val raw = runCatching {
                nativeTraceRouteJson(trimmedHost, probePort, boundedHops, perHopTimeoutMs)
            }.getOrDefault("[]")
            val dtos = runCatching {
                json.decodeFromString<List<NativeTracerouteHopDto>>(raw)
            }.getOrDefault(emptyList())
            if (dtos.isNotEmpty() && dtos.any { !it.timed_out || it.reached_destination }) {
                return@withContext dtos.map {
                    TracerouteHop(
                        hopNumber = it.ttl,
                        routerAddress = it.responder_addr,
                        rttMs = it.rtt_ms,
                        reachedDestination = it.reached_destination,
                        timedOut = it.timed_out
                    )
                }
            }
        }

        // 3. JVM TCP reachability hop fallback
        val hops = mutableListOf<TracerouteHop>()
        for (ttl in 1..boundedHops.coerceAtMost(20)) {
            coroutineContext.ensureActive()
            val startedNs = System.nanoTime()
            val reached = try {
                Socket().use { s ->
                    s.trafficClass = 0x10
                    s.connect(InetSocketAddress(destAddress, probePort), perHopTimeoutMs)
                }
                true
            } catch (_: ConnectException) {
                true
            } catch (_: Throwable) {
                false
            }
            val rtt = (System.nanoTime() - startedNs) / 1_000_000.0
            if (reached) {
                hops += TracerouteHop(
                    hopNumber = ttl,
                    routerAddress = destAddress,
                    rttMs = rtt,
                    reachedDestination = true,
                    timedOut = false
                )
                break
            } else {
                hops += TracerouteHop(
                    hopNumber = ttl,
                    routerAddress = "*",
                    rttMs = rtt,
                    reachedDestination = false,
                    timedOut = true
                )
            }
        }
        hops
    }

    suspend fun scanPortRange(
        host: String,
        startPort: Int,
        endPort: Int,
        timeoutMs: Int = 350,
        concurrency: Int = 64
    ): List<PortProbeResult> = coroutineScope {
        val trimmedHost = host.trim()
        require(trimmedHost.isNotEmpty()) { "Target host is required" }
        val lo = minOf(startPort, endPort).coerceIn(1, 65535)
        val hi = maxOf(startPort, endPort).coerceIn(lo, 65535)

        if (isNativeLoaded) {
            return@coroutineScope withContext(Dispatchers.IO) {
                val raw = runCatching {
                    nativeScanPortsJson(
                        trimmedHost,
                        lo,
                        hi,
                        timeoutMs.coerceAtLeast(20),
                        concurrency.coerceIn(1, 512)
                    )
                }.getOrDefault("[]")
                runCatching {
                    json.decodeFromString<List<PortProbeResult>>(raw)
                }.getOrDefault(emptyList()).filter { it.open }.sortedBy { it.port }
            }
        }

        val semaphore = Semaphore(concurrency.coerceIn(1, 256))
        (lo..hi).map { port ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    ensureActive()
                    var connected = false
                    var banner: String? = null
                    val ms = measureTimeMillis {
                        connected = runCatching {
                            Socket().use { sock ->
                                sock.connect(InetSocketAddress(trimmedHost, port), timeoutMs)
                                sock.soTimeout = 120
                                val buf = ByteArray(128)
                                val n = runCatching { sock.getInputStream().read(buf) }.getOrDefault(-1)
                                if (n > 0) {
                                    banner = String(buf, 0, n, StandardCharsets.UTF_8)
                                        .lineSequence()
                                        .firstOrNull()
                                        ?.trim()
                                }
                            }
                            true
                        }.getOrDefault(false)
                    }
                    if (connected) {
                        PortProbeResult(
                            port = port,
                            open = true,
                            connect_latency_ms = ms,
                            guessed_service = guessService(port),
                            service_banner = banner
                        )
                    } else {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().sortedBy { it.port }
    }

    suspend fun resolveDnsRecords(hostOrIp: String): List<String> = withContext(Dispatchers.IO) {
        val query = hostOrIp.trim()
        require(query.isNotEmpty()) { "Hostname or IP address is required" }
        val elapsedNsStart = System.nanoTime()
        val addresses = InetAddress.getAllByName(query)
        val elapsedMs = String.format(Locale.US, "%.2f", (System.nanoTime() - elapsedNsStart) / 1_000_000.0)
        val lines = mutableListOf<String>()
        lines += "[DNS] Query '$query' resolved ${addresses.size} record(s) in $elapsedMs ms:"
        for (addr in addresses) {
            coroutineContext.ensureActive()
            val recordType = when (addr) {
                is Inet4Address -> "A   "
                is Inet6Address -> "AAAA"
                else -> "ADDR"
            }
            val ip = addr.hostAddress ?: query
            val canonical = runCatching { addr.canonicalHostName }.getOrDefault(ip)
            val ptrSuffix = if (!canonical.equals(ip, ignoreCase = true)) "  PTR=$canonical" else ""
            val scopeTag = when {
                addr.isLoopbackAddress -> " [loopback]"
                addr.isSiteLocalAddress -> " [private/RFC1918]"
                addr.isLinkLocalAddress -> " [link-local]"
                else -> ""
            }
            lines += "  $recordType  $ip$ptrSuffix$scopeTag"
        }
        lines
    }

    fun calculateIpv4Subnet(cidrInput: String): List<String> {
        val raw = cidrInput.trim()
        require(raw.isNotEmpty()) { "Enter IPv4 CIDR (e.g. 192.168.1.10/24 or 10.0.0.1/255.255.252.0)" }
        val parts = raw.split('/', limit = 2)
        val ipInt = parseIpv4ToUInt(parts[0].trim())
        val prefix = if (parts.size == 2) {
            val right = parts[1].trim()
            if (right.contains('.')) {
                val maskInt = parseIpv4ToUInt(right)
                val inv = maskInt.inv()
                require((inv and (inv + 1u)) == 0u) { "Non-contiguous subnet mask: $right" }
                maskInt.countOneBits()
            } else {
                right.toIntOrNull()?.takeIf { it in 0..32 }
                    ?: throw IllegalArgumentException("Prefix length must be 0..32: /$right")
            }
        } else {
            24
        }

        val netmask: UInt = if (prefix == 0) 0u else (0xFFFFFFFFu shl (32 - prefix))
        val wildcard: UInt = netmask.inv()
        val network: UInt = ipInt and netmask
        val broadcast: UInt = network or wildcard
        val totalAddresses: Long = 1L shl (32 - prefix)
        val usableHosts: Long = when (prefix) {
            32 -> 1L
            31 -> 2L
            else -> (totalAddresses - 2L).coerceAtLeast(0L)
        }
        val firstHost: UInt = when (prefix) {
            32, 31 -> network
            else -> network + 1u
        }
        val lastHost: UInt = when (prefix) {
            32, 31 -> broadcast
            else -> broadcast - 1u
        }

        return listOf(
            "[Subnet] ${formatUIntIpv4(ipInt)}/$prefix",
            "  Network Address : ${formatUIntIpv4(network)}/$prefix",
            "  Subnet Mask     : ${formatUIntIpv4(netmask)}",
            "  Wildcard Mask   : ${formatUIntIpv4(wildcard)}",
            "  Broadcast Addr  : ${formatUIntIpv4(broadcast)}",
            "  Usable Range    : ${formatUIntIpv4(firstHost)} - ${formatUIntIpv4(lastHost)}",
            "  Total / Usable  : $totalAddresses total  •  $usableHosts usable host(s)"
        )
    }

    private fun parseIpv4ToUInt(ip: String): UInt {
        val octets = ip.split('.')
        require(octets.size == 4) { "Invalid IPv4 address: $ip" }
        var acc = 0u
        for (part in octets) {
            val v = part.toIntOrNull()?.takeIf { it in 0..255 }
                ?: throw IllegalArgumentException("Invalid IPv4 octet '$part' in $ip")
            acc = (acc shl 8) or v.toUInt()
        }
        return acc
    }

    private fun formatUIntIpv4(value: UInt): String =
        "${(value shr 24) and 0xFFu}.${(value shr 16) and 0xFFu}.${(value shr 8) and 0xFFu}.${value and 0xFFu}"

    private fun guessService(port: Int): String = when (port) {
        21 -> "FTP"
        22 -> "SSH / SFTP"
        23 -> "Telnet"
        53 -> "DNS"
        80 -> "HTTP"
        443 -> "HTTPS"
        445 -> "SMB"
        2375, 2376 -> "Docker API"
        3306 -> "MySQL"
        3389 -> "RDP"
        5432 -> "PostgreSQL"
        5900, 5901 -> "VNC / ARD"
        5985, 5986 -> "WinRM"
        6443 -> "Kubernetes API"
        8006 -> "Proxmox VE"
        8080, 8443 -> "HTTP Alt / Admin"
        else -> "TCP"
    }
}

/**
 * Asynchronous RFB 3.3 / 3.7 / 3.8 & Apple Screen Sharing (ARD Type 30 DH-AES) client session
 * backed by `VncNativeBridge.nativeVncSession*` (`nexusts-vnc`) when native JNI is loaded,
 * and a stateful pure-JVM RFB + ARD Type 30 fallback for JVM execution and unit tests.
 *
 * Reuses a single `ByteArray` + `ByteBuffer` + mutable `Bitmap` across frame updates so
 * high-refresh desktop sessions never allocate per-frame heap bitmaps.
 */
class VncRfbSessionClient(
    val initialHost: String = "",
    val initialPort: Int = 5900,
    val initialUsername: String = "",
    val initialPassword: String = "",
    val initialSharedSession: Boolean = true,
    val initialViewOnly: Boolean = false,
    val initialAppleScreenSharing: Boolean = false,
    private val tunnelLease: Closeable? = null
) : Closeable {

    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null

    @Volatile
    private var nativeSessionHandle: Long = 0L

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var outputStream: DataOutputStream? = null
    private val writeLock = Any()

    @Volatile
    private var activeViewOnly: Boolean = initialViewOnly

    @Volatile
    private var activeTargetKey: String = ""

    @Volatile
    private var remoteShiftHeld: Boolean = false

    @Volatile
    private var syntheticShiftActive: Boolean = false

    // Single reusable RGBA frame pipeline (reallocated only when remote dimensions change)
    private val frameBufferLock = Any()
    private var reusableWidth: Int = 0
    private var reusableHeight: Int = 0
    private var reusableRgbaBytes: ByteArray? = null
    private var reusableByteBuffer: ByteBuffer? = null
    private var reusableBitmap: Bitmap? = null
    private var reusableImageBitmap: ImageBitmap? = null

    private val _state = MutableStateFlow(VncSessionState(viewOnly = initialViewOnly))
    val state: StateFlow<VncSessionState> = _state.asStateFlow()

    private val _frameBitmap = MutableStateFlow<ImageBitmap?>(null)
    val frameBitmap: StateFlow<ImageBitmap?> = _frameBitmap.asStateFlow()

    private val _frameRevision = MutableStateFlow(0L)
    val frameRevision: StateFlow<Long> = _frameRevision.asStateFlow()

    private val _remoteClipboard = MutableStateFlow("")
    val remoteClipboard: StateFlow<String> = _remoteClipboard.asStateFlow()

    val isViewOnly: Boolean
        get() = activeViewOnly

    fun latestRgbaSnapshot(): ByteArray? = synchronized(frameBufferLock) {
        reusableRgbaBytes?.copyOf()
    }

    @JvmOverloads
    fun connect(
        host: String = initialHost,
        port: Int = initialPort,
        password: String = initialPassword,
        preferZrle: Boolean = true,
        username: String = initialUsername,
        sharedSession: Boolean = initialSharedSession,
        viewOnly: Boolean = initialViewOnly,
        isAppleScreenSharing: Boolean = initialAppleScreenSharing,
        forceReconnect: Boolean = false
    ) {
        val trimmedHost = host.trim()
        val safePort = port.takeIf { it in 1..65535 } ?: 5900
        val candidateKey = "$trimmedHost:$safePort:$username:${password.hashCode()}:$sharedSession:$viewOnly:$isAppleScreenSharing:$preferZrle"
        val cur = _state.value
        if (!forceReconnect && activeTargetKey == candidateKey && (cur.connected || cur.connecting)) {
            return
        }

        disconnectInternal()
        activeViewOnly = viewOnly
        activeTargetKey = candidateKey

        if (trimmedHost.isEmpty()) {
            _state.value = VncSessionState(statusMessage = "Missing VNC host", viewOnly = viewOnly)
            return
        }

        sessionJob = scope.launch {
            _state.value = _state.value.copy(
                connected = false,
                connecting = true,
                authRequired = false,
                viewOnly = viewOnly,
                statusMessage = "Connecting to $trimmedHost:$safePort…"
            )

            if (VncNativeBridge.isNativeLoaded) {
                runNativeVncSessionLoop(
                    host = trimmedHost,
                    port = safePort,
                    username = username,
                    password = password,
                    sharedSession = sharedSession,
                    viewOnly = viewOnly,
                    isAppleScreenSharing = isAppleScreenSharing
                )
            } else {
                runJvmFallbackRfbLoop(
                    host = trimmedHost,
                    port = safePort,
                    username = username,
                    password = password,
                    preferZrle = preferZrle,
                    sharedSession = sharedSession,
                    viewOnly = viewOnly,
                    isAppleScreenSharing = isAppleScreenSharing
                )
            }
        }
    }

    private suspend fun runNativeVncSessionLoop(
        host: String,
        port: Int,
        username: String,
        password: String,
        sharedSession: Boolean,
        viewOnly: Boolean,
        isAppleScreenSharing: Boolean
    ) {
        var handle = 0L
        try {
            handle = VncNativeBridge.nativeVncSessionConnect(
                host = host,
                port = port,
                username = if (isAppleScreenSharing) username else "",
                password = password,
                sharedSession = sharedSession,
                viewOnly = viewOnly
            )
            if (handle <= 0L) {
                val err = runCatching { VncNativeBridge.nativeVncSessionLastError(0L) }
                    .getOrDefault("Unable to establish VNC session")
                    .ifBlank { "Unable to establish VNC session to $host:$port" }
                val lower = err.lowercase(Locale.ROOT)
                val needsAuth = lower.contains("password") || lower.contains("auth") || lower.contains("credential") || lower.contains("ard")
                _state.value = _state.value.copy(
                    connected = false,
                    connecting = false,
                    authRequired = needsAuth,
                    viewOnly = viewOnly,
                    statusMessage = err
                )
                return
            }

            nativeSessionHandle = handle
            val dims = runCatching { VncNativeBridge.nativeVncSessionGetDimensions(handle) }.getOrNull()
            var fbWidth = (dims?.getOrNull(0) ?: 1280).coerceIn(1, 8192)
            var fbHeight = (dims?.getOrNull(1) ?: 720).coerceIn(1, 8192)
            var desktopName = runCatching { VncNativeBridge.nativeVncSessionDesktopName(handle) }
                .getOrDefault("")
                .ifBlank { "$host:$port" }

            _state.value = VncSessionState(
                connected = true,
                connecting = false,
                authRequired = false,
                desktopName = desktopName,
                framebufferWidth = fbWidth,
                framebufferHeight = fbHeight,
                negotiatedProtocol = if (isAppleScreenSharing) "RFB 3.8 • ARD DH-AES" else "RFB 3.8 • Native",
                statusMessage = "Connected • $desktopName (${fbWidth}x$fbHeight)",
                viewOnly = viewOnly
            )

            var lastFrameSeq = -1L
            while (coroutineContext.isActive && VncNativeBridge.nativeVncSessionIsConnected(handle)) {
                val seq = VncNativeBridge.nativeVncSessionFrameSequence(handle)
                if (seq != lastFrameSeq) {
                    val liveDims = VncNativeBridge.nativeVncSessionGetDimensions(handle)
                    if (liveDims != null && liveDims.size >= 2) {
                        val nextW = liveDims[0].coerceIn(1, 8192)
                        val nextH = liveDims[1].coerceIn(1, 8192)
                        if (nextW != fbWidth || nextH != fbHeight) {
                            fbWidth = nextW
                            fbHeight = nextH
                            val latestName = VncNativeBridge.nativeVncSessionDesktopName(handle).ifBlank { desktopName }
                            desktopName = latestName
                            _state.value = _state.value.copy(
                                desktopName = desktopName,
                                framebufferWidth = fbWidth,
                                framebufferHeight = fbHeight,
                                statusMessage = "Connected • $desktopName (${fbWidth}x$fbHeight)"
                            )
                        }
                    }

                    var copied = 0
                    synchronized(frameBufferLock) {
                        ensureReusableFrameBuffersLocked(fbWidth, fbHeight)
                        val dst = reusableRgbaBytes
                        if (dst != null) {
                            copied = VncNativeBridge.nativeVncSessionCopyFrameRgba(handle, dst)
                            if (copied > 0) {
                                blitReusableBufferLocked()
                            }
                        }
                    }
                    if (copied > 0) {
                        lastFrameSeq = seq
                        _frameRevision.value = seq
                    }
                }

                val pulledClip = VncNativeBridge.nativeVncSessionPullClipboard(handle)
                if (!pulledClip.isNullOrEmpty() && pulledClip != _remoteClipboard.value) {
                    _remoteClipboard.value = pulledClip
                }

                delay(16L)
            }

            if (coroutineContext.isActive) {
                val disconnectErr = runCatching { VncNativeBridge.nativeVncSessionLastError(handle) }
                    .getOrDefault("")
                _state.value = _state.value.copy(
                    connected = false,
                    connecting = false,
                    statusMessage = if (disconnectErr.isNotBlank()) "Disconnected: $disconnectErr" else "Session closed"
                )
            }
        } catch (t: Throwable) {
            if (coroutineContext.isActive) {
                _state.value = _state.value.copy(
                    connected = false,
                    connecting = false,
                    statusMessage = "Disconnected: ${t.message ?: t::class.simpleName}"
                )
            }
        } finally {
            if (handle > 0L) {
                if (nativeSessionHandle == handle) {
                    nativeSessionHandle = 0L
                }
                runCatching { VncNativeBridge.nativeVncSessionDisconnect(handle) }
            }
        }
    }

    private suspend fun runJvmFallbackRfbLoop(
        host: String,
        port: Int,
        username: String,
        password: String,
        preferZrle: Boolean,
        sharedSession: Boolean,
        viewOnly: Boolean,
        isAppleScreenSharing: Boolean
    ) {
        var zrleInflater: Inflater? = null
        try {
            val socket = Socket()
            activeSocket = socket
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress(host, port), 6_000)

            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 16 * 1024))
            outputStream = output

            // 1. RFB ProtocolVersion handshake (12 bytes: "RFB xxx.yyy\n")
            val versionBytes = ByteArray(12)
            input.readFully(versionBytes)
            val serverBanner = String(versionBytes, StandardCharsets.US_ASCII)
            if (!serverBanner.startsWith("RFB ")) {
                error("Invalid RFB banner: ${serverBanner.trim()}")
            }
            val minorVer = serverBanner.substring(8, 11).toIntOrNull() ?: 8
            val negotiatedMinor = when {
                minorVer >= 8 -> 8
                minorVer == 7 -> 7
                else -> 3
            }
            val clientVersion = String.format(Locale.US, "RFB 003.%03d\n", negotiatedMinor)
            synchronized(writeLock) {
                output.write(clientVersion.toByteArray(StandardCharsets.US_ASCII))
                output.flush()
            }

            // 2. Security negotiation (None = 1, VNC DES Auth = 2, Apple ARD DH-AES = 30)
            val chosenSecType: Int = if (negotiatedMinor == 3) {
                val sec = input.readInt()
                if (sec == 0) {
                    error(readRfbReasonString(input))
                }
                sec
            } else {
                val count = input.readUnsignedByte()
                if (count == 0) {
                    error(readRfbReasonString(input))
                }
                val types = ByteArray(count)
                input.readFully(types)
                val offered = types.map { it.toInt() and 0xFF }
                val picked = when {
                    isAppleScreenSharing && 30 in offered -> 30
                    password.isBlank() && 1 in offered -> 1
                    2 in offered -> 2
                    30 in offered -> 30
                    1 in offered -> 1
                    else -> error("Unsupported VNC security types: $offered")
                }
                synchronized(writeLock) {
                    output.writeByte(picked)
                    output.flush()
                }
                picked
            }

            when (chosenSecType) {
                2 -> {
                    val challenge = ByteArray(16)
                    input.readFully(challenge)
                    if (password.isEmpty()) {
                        _state.value = _state.value.copy(
                            connected = false,
                            connecting = false,
                            authRequired = true,
                            statusMessage = "VNC password required by $host:$port"
                        )
                        return
                    }
                    val response = encryptVncDesChallenge(challenge, password)
                    synchronized(writeLock) {
                        output.write(response)
                        output.flush()
                    }
                }

                30 -> {
                    // Apple Remote Desktop (ARD) Diffie-Hellman + AES-128-ECB Security Type 30
                    val generator = input.readUnsignedShort()
                    val keyLength = input.readUnsignedShort().coerceIn(16, 1024)
                    val primeBytes = ByteArray(keyLength)
                    val serverPubBytes = ByteArray(keyLength)
                    input.readFully(primeBytes)
                    input.readFully(serverPubBytes)

                    if (username.isEmpty() && password.isEmpty()) {
                        _state.value = _state.value.copy(
                            connected = false,
                            connecting = false,
                            authRequired = true,
                            statusMessage = "Apple Screen Sharing username & password required by $host:$port"
                        )
                        return
                    }

                    val (cipherCredentials, clientPubBytes) = computeAppleArdDhAesHandshake(
                        generator = generator,
                        keyLength = keyLength,
                        primeBytes = primeBytes,
                        serverPubBytes = serverPubBytes,
                        username = username,
                        password = password
                    )
                    synchronized(writeLock) {
                        output.write(cipherCredentials)
                        output.write(clientPubBytes)
                        output.flush()
                    }
                }
            }

            if (chosenSecType == 2 || chosenSecType == 30 || negotiatedMinor >= 8) {
                val secResult = input.readInt()
                if (secResult != 0) {
                    val reason = if (negotiatedMinor >= 8) {
                        runCatching { readRfbReasonString(input) }.getOrDefault("Authentication failed")
                    } else {
                        "VNC authentication rejected"
                    }
                    _state.value = _state.value.copy(
                        connected = false,
                        connecting = false,
                        authRequired = chosenSecType == 2 || chosenSecType == 30,
                        statusMessage = reason
                    )
                    return
                }
            }

            // 3. ClientInit (shared = 1 or exclusive = 0) & ServerInit
            synchronized(writeLock) {
                output.writeByte(if (sharedSession) 1 else 0)
                output.flush()
            }

            var fbWidth = input.readUnsignedShort().coerceIn(1, 8192)
            var fbHeight = input.readUnsignedShort().coerceIn(1, 8192)
            val serverPixelFormat = ByteArray(16)
            input.readFully(serverPixelFormat)
            val nameLen = input.readInt().coerceIn(0, 4096)
            val nameBytes = ByteArray(nameLen)
            input.readFully(nameBytes)
            val desktopName = String(nameBytes, StandardCharsets.UTF_8).ifBlank { "$host:$port" }

            // 4. Request RGBA8888 little-endian pixel format & advertise ZRLE / CopyRect / Raw / DesktopSize
            val setPixelFormat = byteArrayOf(
                0, 0, 0, 0,
                32, 24, 0, 1,
                0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(),
                0, 8, 16,
                0, 0, 0
            )
            val encodings = if (preferZrle) {
                intArrayOf(
                    VncNativeBridge.RFB_ENCODING_ZRLE,
                    VncNativeBridge.RFB_ENCODING_COPYRECT,
                    VncNativeBridge.RFB_ENCODING_RAW,
                    VncNativeBridge.RFB_ENCODING_DESKTOP_SIZE
                )
            } else {
                intArrayOf(
                    VncNativeBridge.RFB_ENCODING_COPYRECT,
                    VncNativeBridge.RFB_ENCODING_RAW,
                    VncNativeBridge.RFB_ENCODING_ZRLE,
                    VncNativeBridge.RFB_ENCODING_DESKTOP_SIZE
                )
            }
            synchronized(writeLock) {
                output.write(setPixelFormat)
                output.writeByte(2) // SetEncodings
                output.writeByte(0)
                output.writeShort(encodings.size)
                for (enc in encodings) {
                    output.writeInt(enc)
                }
                writeFramebufferUpdateRequestLocked(output, incremental = false, x = 0, y = 0, w = fbWidth, h = fbHeight)
                output.flush()
            }

            synchronized(frameBufferLock) {
                ensureReusableFrameBuffersLocked(fbWidth, fbHeight)
            }
            zrleInflater = Inflater(false)
            socket.soTimeout = 0

            val protoBadge = if (chosenSecType == 30) {
                "RFB 3.$negotiatedMinor • ARD DH-AES"
            } else {
                "RFB 3.$negotiatedMinor • ${if (preferZrle) "ZRLE" else "RAW"}"
            }
            _state.value = VncSessionState(
                connected = true,
                connecting = false,
                authRequired = false,
                desktopName = desktopName,
                framebufferWidth = fbWidth,
                framebufferHeight = fbHeight,
                negotiatedProtocol = protoBadge,
                statusMessage = "Connected • $desktopName (${fbWidth}x$fbHeight)",
                viewOnly = viewOnly
            )

            var jvmFrameSeq = 0L

            // 5. Continuous RFB Server-to-Client message loop
            while (coroutineContext.isActive && !socket.isClosed) {
                when (val msgType = input.readUnsignedByte()) {
                    0 -> {
                        input.readUnsignedByte() // padding
                        val numRects = input.readUnsignedShort()
                        var frameMutated = false

                        repeat(numRects) {
                            val rx = input.readUnsignedShort()
                            val ry = input.readUnsignedShort()
                            val rw = input.readUnsignedShort()
                            val rh = input.readUnsignedShort()
                            val encoding = input.readInt()

                            when (encoding) {
                                VncNativeBridge.RFB_ENCODING_RAW -> {
                                    val byteLen = rw * rh * 4
                                    val rawBytes = ByteArray(byteLen)
                                    input.readFully(rawBytes)
                                    var idx = 3
                                    while (idx < rawBytes.size) {
                                        rawBytes[idx] = 0xFF.toByte()
                                        idx += 4
                                    }
                                    synchronized(frameBufferLock) {
                                        ensureReusableFrameBuffersLocked(fbWidth, fbHeight)
                                        reusableRgbaBytes?.let { dst ->
                                            blitRgbaRectToRgbaBuffer(dst, fbWidth, fbHeight, rx, ry, rw, rh, rawBytes)
                                            frameMutated = true
                                        }
                                    }
                                }

                                VncNativeBridge.RFB_ENCODING_COPYRECT -> {
                                    val copyPayload = ByteArray(4)
                                    input.readFully(copyPayload)
                                    val sx = ((copyPayload[0].toInt() and 0xFF) shl 8) or (copyPayload[1].toInt() and 0xFF)
                                    val sy = ((copyPayload[2].toInt() and 0xFF) shl 8) or (copyPayload[3].toInt() and 0xFF)
                                    synchronized(frameBufferLock) {
                                        ensureReusableFrameBuffersLocked(fbWidth, fbHeight)
                                        reusableRgbaBytes?.let { dst ->
                                            blitCopyRectRgbaBuffer(dst, fbWidth, fbHeight, rx, ry, rw, rh, sx, sy)
                                            frameMutated = true
                                        }
                                    }
                                }

                                VncNativeBridge.RFB_ENCODING_ZRLE -> {
                                    val zLen = input.readInt().coerceAtLeast(0)
                                    val zPayload = ByteArray(zLen)
                                    input.readFully(zPayload)
                                    val decodedRectRgba = decodeZrleRectToRgba(zrleInflater, rw, rh, zPayload)
                                    if (decodedRectRgba != null) {
                                        synchronized(frameBufferLock) {
                                            ensureReusableFrameBuffersLocked(fbWidth, fbHeight)
                                            reusableRgbaBytes?.let { dst ->
                                                blitRgbaRectToRgbaBuffer(dst, fbWidth, fbHeight, rx, ry, rw, rh, decodedRectRgba)
                                                frameMutated = true
                                            }
                                        }
                                    }
                                }

                                VncNativeBridge.RFB_ENCODING_DESKTOP_SIZE -> {
                                    val nextW = rw.coerceIn(1, 8192)
                                    val nextH = rh.coerceIn(1, 8192)
                                    if (nextW != fbWidth || nextH != fbHeight) {
                                        fbWidth = nextW
                                        fbHeight = nextH
                                        synchronized(frameBufferLock) {
                                            ensureReusableFrameBuffersLocked(fbWidth, fbHeight)
                                        }
                                        _state.value = _state.value.copy(
                                            framebufferWidth = fbWidth,
                                            framebufferHeight = fbHeight,
                                            statusMessage = "Connected • $desktopName (${fbWidth}x$fbHeight)"
                                        )
                                    }
                                }

                                else -> Unit
                            }
                        }

                        if (frameMutated) {
                            synchronized(frameBufferLock) {
                                blitReusableBufferLocked()
                            }
                            jvmFrameSeq++
                            _frameRevision.value = jvmFrameSeq
                        }

                        synchronized(writeLock) {
                            writeFramebufferUpdateRequestLocked(
                                output,
                                incremental = true,
                                x = 0,
                                y = 0,
                                w = fbWidth,
                                h = fbHeight
                            )
                            output.flush()
                        }
                    }

                    1 -> {
                        input.readUnsignedByte()
                        input.readUnsignedShort()
                        val numColors = input.readUnsignedShort()
                        val cmapBytes = ByteArray(numColors * 6)
                        input.readFully(cmapBytes)
                    }

                    2 -> Unit

                    3 -> {
                        val pad = ByteArray(3)
                        input.readFully(pad)
                        val cutLen = input.readInt().coerceIn(0, 1_048_576)
                        val cutBytes = ByteArray(cutLen)
                        input.readFully(cutBytes)
                        _remoteClipboard.value = String(cutBytes, StandardCharsets.ISO_8859_1)
                    }

                    else -> error("Unhandled RFB server message type $msgType")
                }
            }
        } catch (t: Throwable) {
            if (coroutineContext.isActive) {
                _state.value = _state.value.copy(
                    connected = false,
                    connecting = false,
                    statusMessage = "Disconnected: ${t.message ?: t::class.simpleName}"
                )
            }
        } finally {
            zrleInflater?.end()
            runCatching { activeSocket?.close() }
            outputStream = null
        }
    }

    private fun ensureReusableFrameBuffersLocked(width: Int, height: Int) {
        val w = width.coerceIn(1, 8192)
        val h = height.coerceIn(1, 8192)
        val requiredBytes = w * h * 4
        if (reusableWidth == w && reusableHeight == h && reusableRgbaBytes?.size == requiredBytes) {
            return
        }
        reusableWidth = w
        reusableHeight = h
        val bytes = ByteArray(requiredBytes)
        // Initialize dark slate opaque background (#101624)
        var idx = 0
        while (idx + 3 < bytes.size) {
            bytes[idx] = 0x10
            bytes[idx + 1] = 0x16
            bytes[idx + 2] = 0x24
            bytes[idx + 3] = 0xFF.toByte()
            idx += 4
        }
        reusableRgbaBytes = bytes
        reusableByteBuffer = ByteBuffer.wrap(bytes)
        val bmp = runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        reusableBitmap = bmp
        reusableImageBitmap = bmp?.asImageBitmap()
        _frameBitmap.value = reusableImageBitmap
    }

    private fun blitReusableBufferLocked() {
        val buf = reusableByteBuffer ?: return
        val bmp = reusableBitmap ?: return
        runCatching {
            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
        }
        if (_frameBitmap.value !== reusableImageBitmap) {
            _frameBitmap.value = reusableImageBitmap
        }
    }

    fun sendPointerEvent(buttonMask: Int, x: Int, y: Int) {
        val current = _state.value
        if (!current.connected || activeViewOnly) return
        val clampedX = x.coerceIn(0, (current.framebufferWidth - 1).coerceAtLeast(0))
        val clampedY = y.coerceIn(0, (current.framebufferHeight - 1).coerceAtLeast(0))
        val handle = nativeSessionHandle
        if (handle > 0L && VncNativeBridge.isNativeLoaded) {
            scope.launch {
                runCatching {
                    VncNativeBridge.nativeVncSessionSendPointer(handle, buttonMask and 0xFF, clampedX, clampedY)
                }
            }
            return
        }
        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    val out = outputStream ?: return@launch
                    out.writeByte(5)
                    out.writeByte(buttonMask and 0xFF)
                    out.writeShort(clampedX)
                    out.writeShort(clampedY)
                    out.flush()
                }
            }
        }
    }

    fun sendKeyEvent(down: Boolean, keysym: Int) {
        if (!_state.value.connected || activeViewOnly) return
        if (keysym == VncNativeBridge.XK_SHIFT_L || keysym == VncNativeBridge.XK_SHIFT_R) {
            remoteShiftHeld = down
        }
        val needsAutoShift = VncNativeBridge.requiresShiftModifier(keysym)
        val handle = nativeSessionHandle
        if (handle > 0L && VncNativeBridge.isNativeLoaded) {
            scope.launch {
                runCatching {
                    if (down && needsAutoShift && !remoteShiftHeld && !syntheticShiftActive) {
                        syntheticShiftActive = true
                        VncNativeBridge.nativeVncSessionSendKey(handle, true, VncNativeBridge.XK_SHIFT_L)
                    }
                    VncNativeBridge.nativeVncSessionSendKey(handle, down, keysym)
                    if (!down && syntheticShiftActive) {
                        syntheticShiftActive = false
                        VncNativeBridge.nativeVncSessionSendKey(handle, false, VncNativeBridge.XK_SHIFT_L)
                    }
                }
            }
            return
        }
        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    val out = outputStream ?: return@launch
                    if (down && needsAutoShift && !remoteShiftHeld && !syntheticShiftActive) {
                        syntheticShiftActive = true
                        writeKeyPacketLocked(out, true, VncNativeBridge.XK_SHIFT_L)
                    }
                    writeKeyPacketLocked(out, down, keysym)
                    if (!down && syntheticShiftActive) {
                        syntheticShiftActive = false
                        writeKeyPacketLocked(out, false, VncNativeBridge.XK_SHIFT_L)
                    }
                    out.flush()
                }
            }
        }
    }

    fun sendHardwareKey(packet: RawHardwareKeyPacket): Boolean {
        if (!_state.value.connected || activeViewOnly) return false
        val keysym = VncNativeBridge.mapAndroidKeyCodeToX11Keysym(packet.keyCode, packet.unicodeChar)
            ?: return false
        val isModifierKey = keysym in 0xFFE1..0xFFEE
        if (keysym == VncNativeBridge.XK_SHIFT_L || keysym == VncNativeBridge.XK_SHIFT_R) {
            remoteShiftHeld = packet.isKeyDown
        }
        val wrapShift = !isModifierKey && (packet.shiftPressed || VncNativeBridge.requiresShiftModifier(keysym))

        val handle = nativeSessionHandle
        if (handle > 0L && VncNativeBridge.isNativeLoaded) {
            scope.launch {
                runCatching {
                    if (packet.isKeyDown && wrapShift && !remoteShiftHeld && !syntheticShiftActive) {
                        syntheticShiftActive = true
                        VncNativeBridge.nativeVncSessionSendKey(handle, true, VncNativeBridge.XK_SHIFT_L)
                    }
                    VncNativeBridge.nativeVncSessionSendKey(handle, packet.isKeyDown, keysym)
                    if (!packet.isKeyDown && syntheticShiftActive) {
                        syntheticShiftActive = false
                        VncNativeBridge.nativeVncSessionSendKey(handle, false, VncNativeBridge.XK_SHIFT_L)
                    }
                }
            }
            return true
        }

        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    val out = outputStream ?: return@launch
                    if (packet.isKeyDown && wrapShift && !remoteShiftHeld && !syntheticShiftActive) {
                        syntheticShiftActive = true
                        writeKeyPacketLocked(out, true, VncNativeBridge.XK_SHIFT_L)
                    }
                    writeKeyPacketLocked(out, packet.isKeyDown, keysym)
                    if (!packet.isKeyDown && syntheticShiftActive) {
                        syntheticShiftActive = false
                        writeKeyPacketLocked(out, false, VncNativeBridge.XK_SHIFT_L)
                    }
                    out.flush()
                }
            }
        }
        return true
    }

    fun sendKeyTap(keysym: Int) {
        if (!_state.value.connected || activeViewOnly) return
        val needsShift = VncNativeBridge.requiresShiftModifier(keysym) && !remoteShiftHeld
        val handle = nativeSessionHandle
        if (handle > 0L && VncNativeBridge.isNativeLoaded) {
            scope.launch {
                runCatching {
                    if (needsShift) VncNativeBridge.nativeVncSessionSendKey(handle, true, VncNativeBridge.XK_SHIFT_L)
                    VncNativeBridge.nativeVncSessionSendKey(handle, true, keysym)
                    VncNativeBridge.nativeVncSessionSendKey(handle, false, keysym)
                    if (needsShift) VncNativeBridge.nativeVncSessionSendKey(handle, false, VncNativeBridge.XK_SHIFT_L)
                }
            }
            return
        }
        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    val out = outputStream ?: return@launch
                    if (needsShift) writeKeyPacketLocked(out, true, VncNativeBridge.XK_SHIFT_L)
                    writeKeyPacketLocked(out, true, keysym)
                    writeKeyPacketLocked(out, false, keysym)
                    if (needsShift) writeKeyPacketLocked(out, false, VncNativeBridge.XK_SHIFT_L)
                    out.flush()
                }
            }
        }
    }

    fun sendCtrlAltDel() {
        if (!_state.value.connected || activeViewOnly) return
        val handle = nativeSessionHandle
        if (handle > 0L && VncNativeBridge.isNativeLoaded) {
            scope.launch {
                runCatching {
                    VncNativeBridge.nativeVncSessionSendKey(handle, true, VncNativeBridge.XK_CONTROL_L)
                    VncNativeBridge.nativeVncSessionSendKey(handle, true, VncNativeBridge.XK_ALT_L)
                    VncNativeBridge.nativeVncSessionSendKey(handle, true, 0xFFFF)
                    VncNativeBridge.nativeVncSessionSendKey(handle, false, 0xFFFF)
                    VncNativeBridge.nativeVncSessionSendKey(handle, false, VncNativeBridge.XK_ALT_L)
                    VncNativeBridge.nativeVncSessionSendKey(handle, false, VncNativeBridge.XK_CONTROL_L)
                }
            }
            return
        }
        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    val out = outputStream ?: return@launch
                    writeKeyPacketLocked(out, true, VncNativeBridge.XK_CONTROL_L)
                    writeKeyPacketLocked(out, true, VncNativeBridge.XK_ALT_L)
                    writeKeyPacketLocked(out, true, 0xFFFF)
                    writeKeyPacketLocked(out, false, 0xFFFF)
                    writeKeyPacketLocked(out, false, VncNativeBridge.XK_ALT_L)
                    writeKeyPacketLocked(out, false, VncNativeBridge.XK_CONTROL_L)
                    out.flush()
                }
            }
        }
    }

    fun sendText(text: String) {
        if (!_state.value.connected || activeViewOnly || text.isEmpty()) return
        val handle = nativeSessionHandle
        if (handle > 0L && VncNativeBridge.isNativeLoaded) {
            scope.launch {
                runCatching {
                    for (ch in text) {
                        val keysym = charToX11Keysym(ch)
                        val needsShift = VncNativeBridge.requiresShiftModifier(keysym)
                        if (needsShift) {
                            VncNativeBridge.nativeVncSessionSendKey(handle, true, VncNativeBridge.XK_SHIFT_L)
                        }
                        VncNativeBridge.nativeVncSessionSendKey(handle, true, keysym)
                        VncNativeBridge.nativeVncSessionSendKey(handle, false, keysym)
                        if (needsShift) {
                            VncNativeBridge.nativeVncSessionSendKey(handle, false, VncNativeBridge.XK_SHIFT_L)
                        }
                    }
                }
            }
            return
        }
        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    val out = outputStream ?: return@launch
                    for (ch in text) {
                        val keysym = charToX11Keysym(ch)
                        val needsShift = VncNativeBridge.requiresShiftModifier(keysym)
                        if (needsShift) {
                            writeKeyPacketLocked(out, true, VncNativeBridge.XK_SHIFT_L)
                        }
                        writeKeyPacketLocked(out, true, keysym)
                        writeKeyPacketLocked(out, false, keysym)
                        if (needsShift) {
                            writeKeyPacketLocked(out, false, VncNativeBridge.XK_SHIFT_L)
                        }
                    }
                    out.flush()
                }
            }
        }
    }

    private fun charToX11Keysym(ch: Char): Int = when (ch) {
        '\n', '\r' -> 0xFF0D
        '\t' -> 0xFF09
        '\b' -> 0xFF08
        else -> if (ch.code in 0x20..0x7E || ch.code in 0xA0..0xFF) {
            ch.code
        } else {
            0x01000000 or ch.code
        }
    }

    fun sendClientCutText(text: String) {
        if (!_state.value.connected) return
        val handle = nativeSessionHandle
        if (handle > 0L && VncNativeBridge.isNativeLoaded) {
            scope.launch {
                runCatching { VncNativeBridge.nativeVncSessionSendClipboard(handle, text) }
            }
            return
        }
        scope.launch {
            runCatching {
                val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
                synchronized(writeLock) {
                    val out = outputStream ?: return@launch
                    out.writeByte(6)
                    out.writeByte(0)
                    out.writeShort(0)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    private fun disconnectInternal() {
        sessionJob?.cancel()
        sessionJob = null
        val prevHandle = nativeSessionHandle
        nativeSessionHandle = 0L
        if (prevHandle > 0L && VncNativeBridge.isNativeLoaded) {
            runCatching { VncNativeBridge.nativeVncSessionDisconnect(prevHandle) }
        }
        outputStream = null
        runCatching { activeSocket?.close() }
        activeSocket = null
        remoteShiftHeld = false
        syntheticShiftActive = false
    }

    override fun close() {
        activeTargetKey = ""
        disconnectInternal()
        runCatching { tunnelLease?.close() }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun writeKeyPacketLocked(out: DataOutputStream, down: Boolean, keysym: Int) {
        out.writeByte(4)
        out.writeByte(if (down) 1 else 0)
        out.writeShort(0)
        out.writeInt(keysym)
    }

    private fun writeFramebufferUpdateRequestLocked(
        out: DataOutputStream,
        incremental: Boolean,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        out.writeByte(3)
        out.writeByte(if (incremental) 1 else 0)
        out.writeShort(x)
        out.writeShort(y)
        out.writeShort(w)
        out.writeShort(h)
    }

    private fun readRfbReasonString(input: DataInputStream): String {
        val len = input.readInt().coerceIn(0, 4096)
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8).ifBlank { "RFB server closed handshake" }
    }

    private fun encryptVncDesChallenge(challenge16: ByteArray, password: String): ByteArray {
        val pwBytes = password.toByteArray(StandardCharsets.ISO_8859_1)
        val keyBytes = ByteArray(8)
        for (i in 0 until minOf(8, pwBytes.size)) {
            var v = pwBytes[i].toInt() and 0xFF
            var rev = 0
            for (bit in 0 until 8) {
                rev = (rev shl 1) or (v and 1)
                v = v ushr 1
            }
            keyBytes[i] = rev.toByte()
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "DES"))
        return cipher.doFinal(challenge16)
    }

    internal fun computeAppleArdDhAesHandshake(
        generator: Int,
        keyLength: Int,
        primeBytes: ByteArray,
        serverPubBytes: ByteArray,
        username: String,
        password: String
    ): Pair<ByteArray, ByteArray> {
        val rng = SecureRandom()
        val p = BigInteger(1, primeBytes)
        val g = BigInteger.valueOf(generator.toLong())
        val serverPub = BigInteger(1, serverPubBytes)
        var secretExp: BigInteger
        do {
            secretExp = BigInteger(256, rng)
        } while (secretExp <= BigInteger.ONE)

        val clientPub = g.modPow(secretExp, p)
        val sharedSecret = serverPub.modPow(secretExp, p)

        val sharedBytes = toFixedUnsignedBytes(sharedSecret, keyLength)
        val clientPubFixed = toFixedUnsignedBytes(clientPub, keyLength)
        val aesKey16 = MessageDigest.getInstance("MD5").digest(sharedBytes)

        val credPlain = ByteArray(128)
        rng.nextBytes(credPlain)
        val userUtf8 = username.toByteArray(StandardCharsets.UTF_8)
        val passUtf8 = password.toByteArray(StandardCharsets.UTF_8)
        val uLen = minOf(userUtf8.size, 63)
        val pLen = minOf(passUtf8.size, 63)
        System.arraycopy(userUtf8, 0, credPlain, 0, uLen)
        credPlain[uLen] = 0
        System.arraycopy(passUtf8, 0, credPlain, 64, pLen)
        credPlain[64 + pLen] = 0

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey16, "AES"))
        val encrypted = cipher.doFinal(credPlain)
        return encrypted to clientPubFixed
    }

    private fun toFixedUnsignedBytes(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        if (raw.size == length) return raw
        val out = ByteArray(length)
        if (raw.size > length) {
            System.arraycopy(raw, raw.size - length, out, 0, length)
        } else {
            System.arraycopy(raw, 0, out, length - raw.size, raw.size)
        }
        return out
    }

    private fun decodeZrleRectToRgba(inflater: Inflater, w: Int, h: Int, zPayload: ByteArray): ByteArray? {
        if (w <= 0 || h <= 0 || zPayload.isEmpty()) return null
        inflater.setInput(zPayload)
        val bos = ByteArrayOutputStream(w * h * 3 + 64)
        val scratch = ByteArray(16 * 1024)
        while (!inflater.needsInput() && !inflater.finished()) {
            val count = inflater.inflate(scratch)
            if (count <= 0) break
            bos.write(scratch, 0, count)
        }
        val data = bos.toByteArray()
        val rectRgba = ByteArray(w * h * 4)
        var cursor = 0
        var ty = 0
        while (ty < h) {
            val th = minOf(64, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = minOf(64, w - tx)
                if (cursor >= data.size) return rectRgba
                val sub = data[cursor++].toInt() and 0xFF
                when {
                    sub == 0 -> {
                        for (row in 0 until th) {
                            for (col in 0 until tw) {
                                if (cursor + 3 > data.size) return rectRgba
                                putRectPixel(rectRgba, w, tx + col, ty + row, data[cursor], data[cursor + 1], data[cursor + 2])
                                cursor += 3
                            }
                        }
                    }

                    sub == 1 -> {
                        if (cursor + 3 > data.size) return rectRgba
                        val r = data[cursor]
                        val g = data[cursor + 1]
                        val b = data[cursor + 2]
                        cursor += 3
                        for (row in 0 until th) {
                            for (col in 0 until tw) {
                                putRectPixel(rectRgba, w, tx + col, ty + row, r, g, b)
                            }
                        }
                    }

                    sub in 2..16 -> {
                        val palSize = sub
                        if (cursor + palSize * 3 > data.size) return rectRgba
                        val palR = ByteArray(palSize)
                        val palG = ByteArray(palSize)
                        val palB = ByteArray(palSize)
                        for (p in 0 until palSize) {
                            palR[p] = data[cursor]
                            palG[p] = data[cursor + 1]
                            palB[p] = data[cursor + 2]
                            cursor += 3
                        }
                        val bpp = when {
                            palSize == 2 -> 1
                            palSize <= 4 -> 2
                            else -> 4
                        }
                        val mask = (1 shl bpp) - 1
                        for (row in 0 until th) {
                            var shift = 8 - bpp
                            var packedByte = 0
                            for (col in 0 until tw) {
                                if (shift == 8 - bpp) {
                                    if (cursor >= data.size) return rectRgba
                                    packedByte = data[cursor++].toInt() and 0xFF
                                }
                                val idx = ((packedByte ushr shift) and mask).coerceIn(0, palSize - 1)
                                putRectPixel(rectRgba, w, tx + col, ty + row, palR[idx], palG[idx], palB[idx])
                                shift -= bpp
                                if (shift < 0) shift = 8 - bpp
                            }
                        }
                    }

                    sub == 128 -> {
                        var remaining = tw * th
                        var pxIdx = 0
                        while (remaining > 0 && cursor + 3 <= data.size) {
                            val r = data[cursor]
                            val g = data[cursor + 1]
                            val b = data[cursor + 2]
                            cursor += 3
                            var runLen = 1
                            while (cursor < data.size) {
                                val lenByte = data[cursor++].toInt() and 0xFF
                                runLen += lenByte
                                if (lenByte != 255) break
                            }
                            val count = minOf(runLen, remaining)
                            repeat(count) {
                                val col = pxIdx % tw
                                val row = pxIdx / tw
                                putRectPixel(rectRgba, w, tx + col, ty + row, r, g, b)
                                pxIdx++
                            }
                            remaining -= count
                        }
                    }

                    sub in 130..255 -> {
                        val palSize = sub - 128
                        if (cursor + palSize * 3 > data.size) return rectRgba
                        val palR = ByteArray(palSize)
                        val palG = ByteArray(palSize)
                        val palB = ByteArray(palSize)
                        for (p in 0 until palSize) {
                            palR[p] = data[cursor]
                            palG[p] = data[cursor + 1]
                            palB[p] = data[cursor + 2]
                            cursor += 3
                        }
                        var remaining = tw * th
                        var pxIdx = 0
                        while (remaining > 0 && cursor < data.size) {
                            val rawIdx = data[cursor++].toInt() and 0xFF
                            val palIdx = (rawIdx and 0x7F).coerceIn(0, palSize - 1)
                            var runLen = 1
                            if ((rawIdx and 0x80) != 0) {
                                while (cursor < data.size) {
                                    val lenByte = data[cursor++].toInt() and 0xFF
                                    runLen += lenByte
                                    if (lenByte != 255) break
                                }
                            }
                            val count = minOf(runLen, remaining)
                            repeat(count) {
                                val col = pxIdx % tw
                                val row = pxIdx / tw
                                putRectPixel(rectRgba, w, tx + col, ty + row, palR[palIdx], palG[palIdx], palB[palIdx])
                                pxIdx++
                            }
                            remaining -= count
                        }
                    }

                    else -> return rectRgba
                }
                tx += 64
            }
            ty += 64
        }
        return rectRgba
    }

    private fun putRectPixel(buf: ByteArray, rectW: Int, x: Int, y: Int, r: Byte, g: Byte, b: Byte) {
        val off = (y * rectW + x) * 4
        if (off + 3 < buf.size) {
            buf[off] = r
            buf[off + 1] = g
            buf[off + 2] = b
            buf[off + 3] = 0xFF.toByte()
        }
    }

    private fun blitRgbaRectToRgbaBuffer(
        dst: ByteArray,
        fbW: Int,
        fbH: Int,
        rx: Int,
        ry: Int,
        rw: Int,
        rh: Int,
        srcRgba: ByteArray
    ) {
        var srcOff = 0
        val rowBytes = rw * 4
        for (row in 0 until rh) {
            val dy = ry + row
            if (dy !in 0 until fbH) {
                srcOff += rowBytes
                continue
            }
            for (col in 0 until rw) {
                val dx = rx + col
                if (dx in 0 until fbW && srcOff + 3 < srcRgba.size) {
                    val dstOff = (dy * fbW + dx) * 4
                    if (dstOff + 3 < dst.size) {
                        dst[dstOff] = srcRgba[srcOff]
                        dst[dstOff + 1] = srcRgba[srcOff + 1]
                        dst[dstOff + 2] = srcRgba[srcOff + 2]
                        dst[dstOff + 3] = 0xFF.toByte()
                    }
                }
                srcOff += 4
            }
        }
    }

    private fun blitCopyRectRgbaBuffer(
        dst: ByteArray,
        fbW: Int,
        fbH: Int,
        dx: Int,
        dy: Int,
        w: Int,
        h: Int,
        sx: Int,
        sy: Int
    ) {
        if (w <= 0 || h <= 0) return
        val scratch = ByteArray(w * h * 4)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val srcX = (sx + col).coerceIn(0, fbW - 1)
                val srcY = (sy + row).coerceIn(0, fbH - 1)
                val srcOff = (srcY * fbW + srcX) * 4
                val tmpOff = (row * w + col) * 4
                if (srcOff + 3 < dst.size) {
                    System.arraycopy(dst, srcOff, scratch, tmpOff, 4)
                }
            }
        }
        for (row in 0 until h) {
            val targetY = dy + row
            if (targetY !in 0 until fbH) continue
            for (col in 0 until w) {
                val targetX = dx + col
                if (targetX in 0 until fbW) {
                    val dstOff = (targetY * fbW + targetX) * 4
                    val tmpOff = (row * w + col) * 4
                    if (dstOff + 3 < dst.size) {
                        System.arraycopy(scratch, tmpOff, dst, dstOff, 4)
                    }
                }
            }
        }
    }
}
