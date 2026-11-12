package com.nexusts.app.protocols.vnc.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

@Serializable
data class PortProbeResult(
    val port: Int,
    val open: Boolean,
    val connect_latency_ms: Long,
    val guessed_service: String
)

data class PingSample(
    val sequence: Int,
    val targetIp: String,
    val latencyMs: Long,
    val reachable: Boolean
)

data class TracerouteHop(
    val hopNumber: Int,
    val routerAddress: String,
    val rttMs: Long
)

/**
 * JNI Bridge to Rust `nexusts-vnc` and `nexusts-netutils` (`libnexusts_jni.so`),
 * with automatic JVM socket fallback for local execution.
 */
object VncNativeBridge {
    val isNativeLoaded: Boolean = runCatching {
        System.loadLibrary("nexusts_jni")
        true
    }.getOrDefault(false)

    @JvmStatic
    external fun nativeCreateFramebuffer(width: Int, height: Int): Long

    @JvmStatic
    external fun nativeSendWakeOnLan(mac: String, broadcastIp: String, port: Int): Int

    @JvmStatic
    external fun nativeProbeTcpPortJson(host: String, port: Int, timeoutMs: Int): String

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun wakeOnLan(macAddress: String, broadcastAddress: String = "255.255.255.255", port: Int = 9): Int =
        withContext(Dispatchers.IO) {
            if (isNativeLoaded) {
                return@withContext nativeSendWakeOnLan(macAddress, broadcastAddress, port)
            }
            val macParts = macAddress.split(":", "-").filter { it.isNotBlank() }
            require(macParts.size == 6) { "Invalid MAC address: $macAddress" }
            val macBytes = ByteArray(6) { idx -> macParts[idx].toInt(16).toByte() }
            val packetBytes = ByteArray(102)
            for (i in 0 until 6) packetBytes[i] = 0xFF.toByte()
            for (rep in 0 until 16) {
                System.arraycopy(macBytes, 0, packetBytes, 6 + rep * 6, 6)
            }
            DatagramSocket().use { sock ->
                sock.broadcast = true
                val packet = DatagramPacket(
                    packetBytes,
                    packetBytes.size,
                    InetAddress.getByName(broadcastAddress),
                    port
                )
                sock.send(packet)
            }
            packetBytes.size
        }

    suspend fun runTcpPing(host: String, port: Int = 443, count: Int = 5): List<PingSample> =
        withContext(Dispatchers.IO) {
            val resolvedIp = runCatching { InetAddress.getByName(host).hostAddress ?: host }
                .getOrDefault(host)
            (1..count).map { seq ->
                var ok = false
                val elapsed = measureTimeMillis {
                    ok = runCatching {
                        Socket().use { s -> s.connect(InetSocketAddress(host, port), 1500) }
                        true
                    }.getOrDefault(false)
                }
                PingSample(sequence = seq, targetIp = resolvedIp, latencyMs = elapsed, reachable = ok)
            }
        }

    suspend fun scanPortRange(
        host: String,
        startPort: Int,
        endPort: Int,
        timeoutMs: Int = 350
    ): List<PortProbeResult> = coroutineScope {
        val ports = (startPort..endPort).toList()
        ports.chunked(64).flatMap { batch ->
            batch.map { port ->
                async(Dispatchers.IO) {
                    if (isNativeLoaded) {
                        val raw = nativeProbeTcpPortJson(host, port, timeoutMs)
                        if (raw != "null" && raw.isNotBlank()) {
                            return@async json.decodeFromString(PortProbeResult.serializer(), raw)
                        }
                        null
                    } else {
                        var connected = false
                        val ms = measureTimeMillis {
                            connected = runCatching {
                                Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                                true
                            }.getOrDefault(false)
                        }
                        if (connected) {
                            PortProbeResult(
                                port = port,
                                open = true,
                                connect_latency_ms = ms,
                                guessed_service = guessService(port)
                            )
                        } else {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun guessService(port: Int): String = when (port) {
        21 -> "FTP"
        22 -> "SSH / SFTP"
        23 -> "Telnet"
        53 -> "DNS"
        80 -> "HTTP"
        443 -> "HTTPS"
        445 -> "SMB"
        3389 -> "RDP"
        5900 -> "VNC / ARD"
        8006 -> "Proxmox VE"
        else -> "TCP"
    }
}
