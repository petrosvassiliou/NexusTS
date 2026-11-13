package com.nexusts.app.protocols.vnc.bridge

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
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
    val statusMessage: String = "Idle"
)

/**
 * JNI Bridge to Rust `nexusts-vnc` and `nexusts-netutils` (`libnexusts_jni.so`),
 * with automatic JVM socket fallback for local execution.
 */
object VncNativeBridge {
    const val RFB_ENCODING_RAW: Int = 0
    const val RFB_ENCODING_COPYRECT: Int = 1
    const val RFB_ENCODING_HEXTILE: Int = 5
    const val RFB_ENCODING_TIGHT: Int = 7
    const val RFB_ENCODING_ZRLE: Int = 16
    const val RFB_ENCODING_DESKTOP_SIZE: Int = -223

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
    external fun nativeTraceRouteJson(host: String, probePort: Int, maxHops: Int, timeoutMs: Int): String

    @JvmStatic
    external fun nativeGrabTcpBanner(host: String, port: Int, timeoutMs: Int): String

    private val json = Json { ignoreUnknownKeys = true }

    private val pingTimeRegex = Regex("time[=<]([0-9.]+)\\s*ms", RegexOption.IGNORE_CASE)
    private val icmpHopRegex = Regex("(?:From|from)\\s+([0-9a-fA-F:.]+)")

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
        port: Int = 443,
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
            if (pingBin != null) {
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
                var ok = false
                val elapsed = measureTimeMillis {
                    ok = try {
                        Socket().use { s -> s.connect(InetSocketAddress(resolvedIp, port), 1500) }
                        true
                    } catch (_: ConnectException) {
                        // TCP RST means the host IP stack responded directly and is alive/reachable.
                        true
                    } catch (_: Throwable) {
                        false
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
        val semaphore = Semaphore(concurrency.coerceIn(1, 256))

        (lo..hi).map { port ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    ensureActive()
                    if (isNativeLoaded) {
                        val raw = runCatching {
                            nativeProbeTcpPortJson(trimmedHost, port, timeoutMs)
                        }.getOrDefault("")
                        if (raw != "null" && raw.isNotBlank()) {
                            runCatching {
                                json.decodeFromString(PortProbeResult.serializer(), raw)
                            }.getOrNull()
                        } else {
                            null
                        }
                    } else {
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
 * Asynchronous RFB 3.8 (RealVNC / TightVNC / TigerVNC / UltraVNC / QEMU) client session
 * backed by `VncNativeBridge` (`nexusts-vnc` RGBA8888 framebuffer) with pure-JVM fallback.
 */
class VncRfbSessionClient {
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var outputStream: DataOutputStream? = null
    private val writeLock = Any()

    private val _state = MutableStateFlow(VncSessionState())
    val state: StateFlow<VncSessionState> = _state.asStateFlow()

    private val _frameBitmap = MutableStateFlow<ImageBitmap?>(null)
    val frameBitmap: StateFlow<ImageBitmap?> = _frameBitmap.asStateFlow()

    private val _remoteClipboard = MutableStateFlow("")
    val remoteClipboard: StateFlow<String> = _remoteClipboard.asStateFlow()

    fun connect(
        host: String,
        port: Int,
        password: String = "",
        preferZrle: Boolean = true
    ) {
        disconnectInternal()
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) {
            _state.value = VncSessionState(statusMessage = "Missing VNC host")
            return
        }

        sessionJob = scope.launch {
            _state.value = _state.value.copy(
                connected = false,
                connecting = true,
                authRequired = false,
                statusMessage = "Connecting to $trimmedHost:$port…"
            )

            var nativeFbHandle = 0L
            var zrleInflater: Inflater? = null
            try {
                val socket = Socket()
                activeSocket = socket
                socket.tcpNoDelay = true
                socket.soTimeout = 15_000
                socket.connect(InetSocketAddress(trimmedHost, port.coerceIn(1, 65535)), 6_000)

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

                // 2. Security negotiation (None = 1, VNC DES Auth = 2)
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
                        password.isBlank() && 1 in offered -> 1
                        2 in offered -> 2
                        1 in offered -> 1
                        else -> error("Unsupported VNC security types: $offered")
                    }
                    synchronized(writeLock) {
                        output.writeByte(picked)
                        output.flush()
                    }
                    picked
                }

                if (chosenSecType == 2) {
                    val challenge = ByteArray(16)
                    input.readFully(challenge)
                    if (password.isEmpty()) {
                        _state.value = _state.value.copy(
                            connected = false,
                            connecting = false,
                            authRequired = true,
                            statusMessage = "VNC password required by $trimmedHost:$port"
                        )
                        return@launch
                    }
                    val response = encryptVncDesChallenge(challenge, password)
                    synchronized(writeLock) {
                        output.write(response)
                        output.flush()
                    }
                }

                if (chosenSecType == 2 || negotiatedMinor >= 8) {
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
                            authRequired = chosenSecType == 2,
                            statusMessage = reason
                        )
                        return@launch
                    }
                }

                // 3. ClientInit (shared = 1) & ServerInit
                synchronized(writeLock) {
                    output.writeByte(1)
                    output.flush()
                }

                var fbWidth = input.readUnsignedShort().coerceIn(1, 8192)
                var fbHeight = input.readUnsignedShort().coerceIn(1, 8192)
                val serverPixelFormat = ByteArray(16)
                input.readFully(serverPixelFormat)
                val nameLen = input.readInt().coerceIn(0, 4096)
                val nameBytes = ByteArray(nameLen)
                input.readFully(nameBytes)
                val desktopName = String(nameBytes, StandardCharsets.UTF_8).ifBlank { "$trimmedHost:$port" }

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

                if (VncNativeBridge.isNativeLoaded) {
                    nativeFbHandle = VncNativeBridge.nativeCreateFramebuffer(fbWidth, fbHeight)
                }
                var fallbackArgb = IntArray(fbWidth * fbHeight) { 0xFF101624.toInt() }
                zrleInflater = Inflater(false)
                socket.soTimeout = 0 // Blocking read for continuous desktop updates

                _state.value = VncSessionState(
                    connected = true,
                    connecting = false,
                    authRequired = false,
                    desktopName = desktopName,
                    framebufferWidth = fbWidth,
                    framebufferHeight = fbHeight,
                    negotiatedProtocol = "RFB 3.$negotiatedMinor • ${if (preferZrle) "ZRLE" else "RAW"}",
                    statusMessage = "Connected • $desktopName (${fbWidth}x$fbHeight)"
                )

                // 5. Continuous RFB Server-to-Client message loop
                while (isActive && !socket.isClosed) {
                    when (val msgType = input.readUnsignedByte()) {
                        0 -> {
                            // FramebufferUpdate
                            input.readUnsignedByte() // padding
                            val numRects = input.readUnsignedShort()
                            var latestRgbaFromNative: ByteArray? = null

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
                                        // Force alpha channel to opaque 0xFF on 24-in-32bpp pixels
                                        var idx = 3
                                        while (idx < rawBytes.size) {
                                            rawBytes[idx] = 0xFF.toByte()
                                            idx += 4
                                        }
                                        if (nativeFbHandle != 0L) {
                                            latestRgbaFromNative = runCatching {
                                                VncNativeBridge.nativeApplyRfbRect(
                                                    nativeFbHandle,
                                                    VncNativeBridge.RFB_ENCODING_RAW,
                                                    rx, ry, rw, rh,
                                                    rawBytes
                                                )
                                            }.getOrNull() ?: latestRgbaFromNative
                                        } else {
                                            blitRgbaRectToArgb(fallbackArgb, fbWidth, fbHeight, rx, ry, rw, rh, rawBytes)
                                        }
                                    }

                                    VncNativeBridge.RFB_ENCODING_COPYRECT -> {
                                        val copyPayload = ByteArray(4)
                                        input.readFully(copyPayload)
                                        if (nativeFbHandle != 0L) {
                                            latestRgbaFromNative = runCatching {
                                                VncNativeBridge.nativeApplyRfbRect(
                                                    nativeFbHandle,
                                                    VncNativeBridge.RFB_ENCODING_COPYRECT,
                                                    rx, ry, rw, rh,
                                                    copyPayload
                                                )
                                            }.getOrNull() ?: latestRgbaFromNative
                                        } else {
                                            val sx = ((copyPayload[0].toInt() and 0xFF) shl 8) or (copyPayload[1].toInt() and 0xFF)
                                            val sy = ((copyPayload[2].toInt() and 0xFF) shl 8) or (copyPayload[3].toInt() and 0xFF)
                                            blitCopyRectArgb(fallbackArgb, fbWidth, fbHeight, rx, ry, rw, rh, sx, sy)
                                        }
                                    }

                                    VncNativeBridge.RFB_ENCODING_ZRLE -> {
                                        val zLen = input.readInt().coerceAtLeast(0)
                                        val zPayload = ByteArray(zLen)
                                        input.readFully(zPayload)
                                        val decodedRectRgba = decodeZrleRectToRgba(zrleInflater, rw, rh, zPayload)
                                        if (decodedRectRgba != null) {
                                            if (nativeFbHandle != 0L) {
                                                latestRgbaFromNative = runCatching {
                                                    VncNativeBridge.nativeApplyRfbRect(
                                                        nativeFbHandle,
                                                        VncNativeBridge.RFB_ENCODING_RAW,
                                                        rx, ry, rw, rh,
                                                        decodedRectRgba
                                                    )
                                                }.getOrNull() ?: latestRgbaFromNative
                                            } else {
                                                blitRgbaRectToArgb(fallbackArgb, fbWidth, fbHeight, rx, ry, rw, rh, decodedRectRgba)
                                            }
                                        }
                                    }

                                    VncNativeBridge.RFB_ENCODING_DESKTOP_SIZE -> {
                                        val nextW = rw.coerceIn(1, 8192)
                                        val nextH = rh.coerceIn(1, 8192)
                                        if (nextW != fbWidth || nextH != fbHeight) {
                                            fbWidth = nextW
                                            fbHeight = nextH
                                            if (nativeFbHandle != 0L) {
                                                VncNativeBridge.nativeDestroyFramebuffer(nativeFbHandle)
                                                nativeFbHandle = VncNativeBridge.nativeCreateFramebuffer(fbWidth, fbHeight)
                                            }
                                            fallbackArgb = IntArray(fbWidth * fbHeight) { 0xFF101624.toInt() }
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

                            val argbFrame = if (latestRgbaFromNative != null && latestRgbaFromNative!!.size >= fbWidth * fbHeight * 4) {
                                rgbaBytesToArgbInts(latestRgbaFromNative!!, fbWidth * fbHeight)
                            } else if (nativeFbHandle == 0L) {
                                fallbackArgb
                            } else {
                                null
                            }

                            if (argbFrame != null) {
                                val bmp = Bitmap.createBitmap(argbFrame, fbWidth, fbHeight, Bitmap.Config.ARGB_8888)
                                _frameBitmap.value = bmp.asImageBitmap()
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
                            // SetColourMapEntries
                            input.readUnsignedByte()
                            input.readUnsignedShort()
                            val numColors = input.readUnsignedShort()
                            val cmapBytes = ByteArray(numColors * 6)
                            input.readFully(cmapBytes)
                        }

                        2 -> {
                            // Bell (no payload)
                        }

                        3 -> {
                            // ServerCutText
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
                if (nativeFbHandle != 0L) {
                    runCatching { VncNativeBridge.nativeDestroyFramebuffer(nativeFbHandle) }
                }
                runCatching { activeSocket?.close() }
                outputStream = null
            }
        }
    }

    fun sendPointerEvent(buttonMask: Int, x: Int, y: Int) {
        val current = _state.value
        if (!current.connected) return
        val clampedX = x.coerceIn(0, (current.framebufferWidth - 1).coerceAtLeast(0))
        val clampedY = y.coerceIn(0, (current.framebufferHeight - 1).coerceAtLeast(0))
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
        if (!_state.value.connected) return
        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    val out = outputStream ?: return@launch
                    out.writeByte(4)
                    out.writeByte(if (down) 1 else 0)
                    out.writeShort(0)
                    out.writeInt(keysym)
                    out.flush()
                }
            }
        }
    }

    fun sendKeyTap(keysym: Int) {
        if (!_state.value.connected) return
        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    val out = outputStream ?: return@launch
                    writeKeyPacketLocked(out, true, keysym)
                    writeKeyPacketLocked(out, false, keysym)
                    out.flush()
                }
            }
        }
    }

    fun sendCtrlAltDel() {
        if (!_state.value.connected) return
        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    val out = outputStream ?: return@launch
                    writeKeyPacketLocked(out, true, 0xFFE3)  // XK_Control_L
                    writeKeyPacketLocked(out, true, 0xFFE9)  // XK_Alt_L
                    writeKeyPacketLocked(out, true, 0xFFFF)  // XK_Delete
                    writeKeyPacketLocked(out, false, 0xFFFF)
                    writeKeyPacketLocked(out, false, 0xFFE9)
                    writeKeyPacketLocked(out, false, 0xFFE3)
                    out.flush()
                }
            }
        }
    }

    fun sendText(text: String) {
        if (!_state.value.connected || text.isEmpty()) return
        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    val out = outputStream ?: return@launch
                    for (ch in text) {
                        val keysym = when (ch) {
                            '\n', '\r' -> 0xFF0D
                            '\t' -> 0xFF09
                            '\b' -> 0xFF08
                            else -> if (ch.code in 0x20..0x7E || ch.code in 0xA0..0xFF) {
                                ch.code
                            } else {
                                0x01000000 or ch.code
                            }
                        }
                        writeKeyPacketLocked(out, true, keysym)
                        writeKeyPacketLocked(out, false, keysym)
                    }
                    out.flush()
                }
            }
        }
    }

    fun sendClientCutText(text: String) {
        if (!_state.value.connected) return
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
        outputStream = null
        runCatching { activeSocket?.close() }
        activeSocket = null
    }

    fun close() {
        disconnectInternal()
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
                        // Raw 3-byte CPIXEL
                        for (row in 0 until th) {
                            for (col in 0 until tw) {
                                if (cursor + 3 > data.size) return rectRgba
                                putRectPixel(rectRgba, w, tx + col, ty + row, data[cursor], data[cursor + 1], data[cursor + 2])
                                cursor += 3
                            }
                        }
                    }

                    sub == 1 -> {
                        // Solid tile
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
                        // Packed palette tile
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
                        // Plain RLE
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
                        // Palette RLE
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

    private fun rgbaBytesToArgbInts(rgba: ByteArray, pixelCount: Int): IntArray {
        val out = IntArray(pixelCount)
        var byteIdx = 0
        for (i in 0 until pixelCount) {
            val r = rgba[byteIdx].toInt() and 0xFF
            val g = rgba[byteIdx + 1].toInt() and 0xFF
            val b = rgba[byteIdx + 2].toInt() and 0xFF
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            byteIdx += 4
        }
        return out
    }

    private fun blitRgbaRectToArgb(
        dst: IntArray,
        fbW: Int,
        fbH: Int,
        rx: Int,
        ry: Int,
        rw: Int,
        rh: Int,
        rgba: ByteArray
    ) {
        var srcOff = 0
        for (row in 0 until rh) {
            val dy = ry + row
            if (dy !in 0 until fbH) {
                srcOff += rw * 4
                continue
            }
            for (col in 0 until rw) {
                val dx = rx + col
                if (dx in 0 until fbW && srcOff + 2 < rgba.size) {
                    val r = rgba[srcOff].toInt() and 0xFF
                    val g = rgba[srcOff + 1].toInt() and 0xFF
                    val b = rgba[srcOff + 2].toInt() and 0xFF
                    dst[dy * fbW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                srcOff += 4
            }
        }
    }

    private fun blitCopyRectArgb(
        dst: IntArray,
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
        val scratch = IntArray(w * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val srcX = (sx + col).coerceIn(0, fbW - 1)
                val srcY = (sy + row).coerceIn(0, fbH - 1)
                scratch[row * w + col] = dst[srcY * fbW + srcX]
            }
        }
        for (row in 0 until h) {
            val targetY = dy + row
            if (targetY !in 0 until fbH) continue
            for (col in 0 until w) {
                val targetX = dx + col
                if (targetX in 0 until fbW) {
                    dst[targetY * fbW + targetX] = scratch[row * w + col]
                }
            }
        }
    }
}
