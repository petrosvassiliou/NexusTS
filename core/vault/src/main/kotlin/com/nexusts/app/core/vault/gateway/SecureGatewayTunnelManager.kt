package com.nexusts.app.core.vault.gateway

import com.nexusts.app.core.vault.model.GatewayTransportKind
import com.nexusts.app.core.vault.model.SecureGatewayConfig
import com.nexusts.app.core.vault.model.VaultCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Serializable
data class GatewayHealthReport(
    val gatewayId: String,
    val reachable: Boolean,
    val latencyMs: Long,
    val protocolBanner: String,
    val tlsVerified: Boolean,
    val checkedAtEpochMs: Long = System.currentTimeMillis()
)

data class ActiveTunnelLease(
    val leaseId: String,
    val gatewayId: String,
    val gatewayKind: GatewayTransportKind,
    val localBindHost: String,
    val localBindPort: Int,
    val remoteTargetHost: String,
    val remoteTargetPort: Int,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Enterprise Secure Gateway & Proxy Tunnel Engine (Royal Server / Secure Gateway equivalent).
 *
 * Provides transparent loopback socket relays (`127.0.0.1:<ephemeralPort>`) and direct socket
 * dialers across:
 * 1. **SOCKS5 Proxy** (RFC 1928 + RFC 1929 Username/Password sub-negotiation)
 * 2. **HTTP `CONNECT` Proxy** (RFC 9110 `CONNECT` + `Proxy-Authorization: Basic`)
 * 3. **NexusTS Secure Gateway** (Royal Server equivalent HTTPS tunnel lease + WSS binary stream bridge)
 * 4. **SSH Bastion Jump Host** (coordinates SSH `direct-tcpip` jump specifications and health probes)
 */
@Singleton
class SecureGatewayTunnelManager @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private data class RunningRelay(
        val lease: ActiveTunnelLease,
        val serverSocket: ServerSocket,
        val acceptJob: Job,
        val webSocket: WebSocket? = null
    )

    private val activeRelays = ConcurrentHashMap<String, RunningRelay>()
    private val _leasesState = MutableStateFlow<List<ActiveTunnelLease>>(emptyList())
    val activeLeases: StateFlow<List<ActiveTunnelLease>> = _leasesState.asStateFlow()

    private val verifiedHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep streaming WebSockets alive
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /**
     * Establishes a local loopback TCP relay (`127.0.0.1:<ephemeralPort>`) that tunnels traffic
     * through [gateway] to `[targetHost]:[targetPort]`. Native NDK engines (FreeRDP 3.x, `russh`,
     * RFB VNC) can connect directly to [ActiveTunnelLease.localBindHost]:[ActiveTunnelLease.localBindPort].
     */
    suspend fun openLoopbackTunnelLease(
        gateway: SecureGatewayConfig,
        credential: VaultCredential?,
        targetHost: String,
        targetPort: Int
    ): ActiveTunnelLease = withContext(Dispatchers.IO) {
        val serverSocket = ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress("127.0.0.1", 0))
        val boundPort = serverSocket.localPort
        val leaseId = UUID.randomUUID().toString()

        val lease = ActiveTunnelLease(
            leaseId = leaseId,
            gatewayId = gateway.id,
            gatewayKind = gateway.kind,
            localBindHost = "127.0.0.1",
            localBindPort = boundPort,
            remoteTargetHost = targetHost,
            remoteTargetPort = targetPort
        )

        val acceptJob = scope.launch {
            try {
                while (isActive && !serverSocket.isClosed) {
                    val localClient = serverSocket.accept()
                    localClient.tcpNoDelay = true
                    launch {
                        bridgeAcceptedClient(localClient, gateway, credential, targetHost, targetPort)
                    }
                }
            } catch (_: IOException) {
                // Relay closed intentionally via closeTunnelLease
            }
        }

        activeRelays[leaseId] = RunningRelay(lease, serverSocket, acceptJob)
        publishLeases()
        lease
    }

    /**
     * Dials an outbound TCP [Socket] to `[targetHost]:[targetPort]` directly through a SOCKS5
     * or HTTP `CONNECT` proxy.
     */
    suspend fun dialProxiedSocket(
        gateway: SecureGatewayConfig,
        credential: VaultCredential?,
        targetHost: String,
        targetPort: Int,
        connectTimeoutMs: Int = 8_000
    ): Socket = withContext(Dispatchers.IO) {
        when (gateway.kind) {
            GatewayTransportKind.SOCKS5_PROXY ->
                dialSocks5Socket(gateway, credential, targetHost, targetPort, connectTimeoutMs)
            GatewayTransportKind.HTTP_CONNECT_PROXY ->
                dialHttpConnectSocket(gateway, credential, targetHost, targetPort, connectTimeoutMs)
            GatewayTransportKind.NEXUSTS_SECURE_GATEWAY,
            GatewayTransportKind.SSH_TUNNEL_BASTION -> {
                val lease = openLoopbackTunnelLease(gateway, credential, targetHost, targetPort)
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(lease.localBindHost, lease.localBindPort), connectTimeoutMs)
                }
            }
        }
    }

    /**
     * Probes gateway reachability, handshake latency, and protocol greeting / health status.
     */
    suspend fun probeGatewayHealth(
        gateway: SecureGatewayConfig,
        credential: VaultCredential? = null
    ): GatewayHealthReport = withContext(Dispatchers.IO) {
        val startedNs = System.nanoTime()
        try {
            when (gateway.kind) {
                GatewayTransportKind.NEXUSTS_SECURE_GATEWAY -> {
                    val client = selectHttpClient(gateway.verifyTlsCertificate)
                    val healthUrl = "https://${gateway.host}:${gateway.port}/api/v2/gateway/health"
                    val reqBuilder = Request.Builder().url(healthUrl).get()
                    if (!credential?.secretPassword.isNullOrEmpty()) {
                        reqBuilder.header("Authorization", "Bearer ${credential!!.secretPassword}")
                    }
                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
                        GatewayHealthReport(
                            gatewayId = gateway.id,
                            reachable = resp.isSuccessful || resp.code == 401,
                            latencyMs = ms,
                            protocolBanner = "NexusTS Gateway HTTP ${resp.code}",
                            tlsVerified = gateway.verifyTlsCertificate
                        )
                    }
                }

                GatewayTransportKind.SSH_TUNNEL_BASTION,
                GatewayTransportKind.SOCKS5_PROXY,
                GatewayTransportKind.HTTP_CONNECT_PROXY -> {
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress(gateway.host, gateway.port), 4_000)
                        sock.soTimeout = 400
                        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
                        val banner = runCatching {
                            val buf = ByteArray(96)
                            val read = sock.getInputStream().read(buf)
                            if (read > 0) {
                                String(buf, 0, read, StandardCharsets.UTF_8).lineSequence().firstOrNull()?.trim().orEmpty()
                            } else {
                                ""
                            }
                        }.getOrDefault("")

                        GatewayHealthReport(
                            gatewayId = gateway.id,
                            reachable = true,
                            latencyMs = ms,
                            protocolBanner = banner.ifEmpty { "${gateway.kind.name} TCP:${gateway.port} READY" },
                            tlsVerified = gateway.verifyTlsCertificate
                        )
                    }
                }
            }
        } catch (e: Exception) {
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
            GatewayHealthReport(
                gatewayId = gateway.id,
                reachable = false,
                latencyMs = ms,
                protocolBanner = e.message ?: "Unreachable",
                tlsVerified = false
            )
        }
    }

    fun closeTunnelLease(leaseId: String) {
        activeRelays.remove(leaseId)?.let { relay ->
            runCatching { relay.webSocket?.close(1000, "NexusTS session closed") }
            runCatching { relay.serverSocket.close() }
            relay.acceptJob.cancel()
            publishLeases()
        }
    }

    fun closeAllLeases() {
        activeRelays.keys.toList().forEach { closeTunnelLease(it) }
    }

    private suspend fun bridgeAcceptedClient(
        localClient: Socket,
        gateway: SecureGatewayConfig,
        credential: VaultCredential?,
        targetHost: String,
        targetPort: Int
    ) {
        when (gateway.kind) {
            GatewayTransportKind.SOCKS5_PROXY -> {
                val upstream = runCatching {
                    dialSocks5Socket(gateway, credential, targetHost, targetPort, 8_000)
                }.getOrElse {
                    runCatching { localClient.close() }
                    return
                }
                pipeSocketsBidirectionally(localClient, upstream)
            }

            GatewayTransportKind.HTTP_CONNECT_PROXY -> {
                val upstream = runCatching {
                    dialHttpConnectSocket(gateway, credential, targetHost, targetPort, 8_000)
                }.getOrElse {
                    runCatching { localClient.close() }
                    return
                }
                pipeSocketsBidirectionally(localClient, upstream)
            }

            GatewayTransportKind.NEXUSTS_SECURE_GATEWAY -> {
                bridgeOverNexusSecureGatewayWss(localClient, gateway, credential, targetHost, targetPort)
            }

            GatewayTransportKind.SSH_TUNNEL_BASTION -> {
                // Direct TCP socket fallback if native russh bastion channel isn't pre-bound
                val upstream = runCatching {
                    Socket().apply {
                        tcpNoDelay = true
                        connect(InetSocketAddress(gateway.host, gateway.port), 8_000)
                    }
                }.getOrElse {
                    runCatching { localClient.close() }
                    return
                }
                pipeSocketsBidirectionally(localClient, upstream)
            }
        }
    }

    /**
     * RFC 1928 + RFC 1929 SOCKS5 `CONNECT` wire negotiation using domain-name (`ATYP = 0x03`)
     * addressing so DNS resolution occurs securely on the remote SOCKS5 proxy (preventing DNS leaks).
     */
    private fun dialSocks5Socket(
        gateway: SecureGatewayConfig,
        credential: VaultCredential?,
        targetHost: String,
        targetPort: Int,
        timeoutMs: Int
    ): Socket {
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(gateway.host, gateway.port), timeoutMs)
        sock.soTimeout = timeoutMs

        val out = sock.getOutputStream()
        val inp = DataInputStream(BufferedInputStream(sock.getInputStream()))

        val hasAuth = !credential?.username.isNullOrBlank()
        if (hasAuth) {
            // Greeting advertising NO_AUTH (0x00) and USER_PASS (0x02)
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            out.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        out.flush()

        val serverVer = inp.readUnsignedByte()
        val chosenMethod = inp.readUnsignedByte()
        if (serverVer != 0x05) {
            sock.close()
            throw IOException("Invalid SOCKS5 version byte: $serverVer")
        }

        if (chosenMethod == 0x02) {
            val uBytes = (credential?.username ?: "").toByteArray(StandardCharsets.UTF_8)
            val pBytes = (credential?.secretPassword ?: "").toByteArray(StandardCharsets.UTF_8)
            require(uBytes.size <= 255 && pBytes.size <= 255) { "SOCKS5 RFC 1929 credentials exceed 255 bytes" }

            val authPkt = ByteArray(3 + uBytes.size + pBytes.size)
            authPkt[0] = 0x01 // Subnegotiation v1
            authPkt[1] = uBytes.size.toByte()
            System.arraycopy(uBytes, 0, authPkt, 2, uBytes.size)
            authPkt[2 + uBytes.size] = pBytes.size.toByte()
            System.arraycopy(pBytes, 0, authPkt, 3 + uBytes.size, pBytes.size)
            out.write(authPkt)
            out.flush()

            val authVer = inp.readUnsignedByte()
            val authStatus = inp.readUnsignedByte()
            if (authVer != 0x01 || authStatus != 0x00) {
                sock.close()
                throw IOException("SOCKS5 RFC 1929 authentication rejected (status=$authStatus)")
            }
        } else if (chosenMethod != 0x00) {
            sock.close()
            throw IOException("SOCKS5 proxy rejected authentication methods (code=$chosenMethod)")
        }

        // Send CONNECT (0x01), RSV (0x00), ATYP=DOMAINNAME (0x03)
        val hostBytes = targetHost.toByteArray(StandardCharsets.US_ASCII)
        require(hostBytes.size in 1..255) { "Invalid SOCKS5 target hostname length" }
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05
        req[1] = 0x01 // CONNECT
        req[2] = 0x00 // RSV
        req[3] = 0x03 // ATYP Domain
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((targetPort ushr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
        out.write(req)
        out.flush()

        val respVer = inp.readUnsignedByte()
        val repCode = inp.readUnsignedByte()
        inp.readUnsignedByte() // RSV
        val bndAtyp = inp.readUnsignedByte()
        if (respVer != 0x05 || repCode != 0x00) {
            sock.close()
            throw IOException("SOCKS5 CONNECT to $targetHost:$targetPort failed (REP=$repCode)")
        }

        val skipAddrBytes = when (bndAtyp) {
            0x01 -> 4 // IPv4
            0x03 -> inp.readUnsignedByte() // Domain
            0x04 -> 16 // IPv6
            else -> throw IOException("Unknown SOCKS5 BND.ATYP=$bndAtyp")
        }
        inp.skipBytes(skipAddrBytes + 2) // BND.ADDR + BND.PORT
        sock.soTimeout = 0
        return sock
    }

    /**
     * RFC 9110 HTTP `CONNECT` tunnel establishment with optional `Proxy-Authorization: Basic`.
     */
    private fun dialHttpConnectSocket(
        gateway: SecureGatewayConfig,
        credential: VaultCredential?,
        targetHost: String,
        targetPort: Int,
        timeoutMs: Int
    ): Socket {
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(gateway.host, gateway.port), timeoutMs)
        sock.soTimeout = timeoutMs

        val authHeader = if (!credential?.username.isNullOrBlank()) {
            val raw = "${credential?.username}:${credential?.secretPassword.orEmpty()}"
            val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
            "Proxy-Authorization: Basic $encoded\r\n"
        } else {
            ""
        }

        val connectReq = buildString {
            append("CONNECT $targetHost:$targetPort HTTP/1.1\r\n")
            append("Host: $targetHost:$targetPort\r\n")
            append("User-Agent: NexusTS/2.0 (GooglebookOS)\r\n")
            append("Proxy-Connection: keep-alive\r\n")
            append(authHeader)
            append("\r\n")
        }

        val out = sock.getOutputStream()
        out.write(connectReq.toByteArray(StandardCharsets.US_ASCII))
        out.flush()

        val inp = sock.getInputStream()
        val headerBuf = StringBuilder(256)
        var crlfCount = 0
        while (headerBuf.length < 8192) {
            val b = inp.read()
            if (b == -1) {
                sock.close()
                throw IOException("HTTP CONNECT proxy closed connection before status line")
            }
            val ch = b.toChar()
            headerBuf.append(ch)
            crlfCount = when {
                crlfCount == 0 && ch == '\r' -> 1
                crlfCount == 1 && ch == '\n' -> 2
                crlfCount == 2 && ch == '\r' -> 3
                crlfCount == 3 && ch == '\n' -> 4
                ch == '\r' -> 1
                else -> 0
            }
            if (crlfCount == 4) break
        }

        val statusLine = headerBuf.lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains(" 200 ")) {
            sock.close()
            throw IOException("HTTP CONNECT rejected by proxy: $statusLine")
        }

        sock.soTimeout = 0
        return sock
    }

    /**
     * Negotiates a NexusTS Secure Gateway (Royal Server equivalent) tunnel token over HTTPS and
     * bridges binary TCP frames over `wss://<host>:<port>/api/v2/gateway/stream/<tunnelToken>`.
     */
    private fun bridgeOverNexusSecureGatewayWss(
        localClient: Socket,
        gateway: SecureGatewayConfig,
        credential: VaultCredential?,
        targetHost: String,
        targetPort: Int
    ) {
        val client = selectHttpClient(gateway.verifyTlsCertificate)
        val leasePayload = buildJsonObject {
            put("targetHost", targetHost)
            put("targetPort", targetPort)
            put("clientPlatform", "Googlebook-OS")
        }.toString()

        val leaseReqBuilder = Request.Builder()
            .url("https://${gateway.host}:${gateway.port}/api/v2/gateway/tunnels")
            .post(leasePayload.toRequestBody("application/json".toMediaType()))

        if (!credential?.secretPassword.isNullOrBlank()) {
            leaseReqBuilder.header("Authorization", "Bearer ${credential!!.secretPassword}")
        }

        val tunnelToken = runCatching {
            client.newCall(leaseReqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Gateway lease HTTP ${resp.code}")
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                obj["tunnelToken"]?.jsonPrimitive?.content ?: throw IOException("Missing tunnelToken")
            }
        }.getOrElse {
            runCatching { localClient.close() }
            return
        }

        val wsReqBuilder = Request.Builder()
            .url("wss://${gateway.host}:${gateway.port}/api/v2/gateway/stream/$tunnelToken")
        if (!credential?.secretPassword.isNullOrBlank()) {
            wsReqBuilder.header("Authorization", "Bearer ${credential!!.secretPassword}")
        }

        val ws = client.newWebSocket(wsReqBuilder.build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching {
                    localClient.getOutputStream().write(bytes.toByteArray())
                    localClient.getOutputStream().flush()
                }.onFailure {
                    webSocket.close(1001, "Local client closed")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runCatching { localClient.close() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runCatching { localClient.close() }
            }
        })

        scope.launch {
            val buf = ByteArray(16 * 1024)
            try {
                val input = localClient.getInputStream()
                while (isActive && !localClient.isClosed) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    ws.send(buf.toByteString(0, n))
                }
            } catch (_: IOException) {
            } finally {
                ws.close(1000, "EOF")
                runCatching { localClient.close() }
            }
        }
    }

    private fun pipeSocketsBidirectionally(a: Socket, b: Socket) {
        val jobAtoB = scope.launch {
            val buf = ByteArray(16 * 1024)
            try {
                val inp = a.getInputStream()
                val out = b.getOutputStream()
                while (isActive) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (_: IOException) {
            } finally {
                runCatching { b.shutdownOutput() }
            }
        }

        scope.launch {
            val buf = ByteArray(16 * 1024)
            try {
                val inp = b.getInputStream()
                val out = a.getOutputStream()
                while (isActive) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (_: IOException) {
            } finally {
                jobAtoB.cancel()
                runCatching { a.close() }
                runCatching { b.close() }
            }
        }
    }

    private fun selectHttpClient(verifyTls: Boolean): OkHttpClient {
        if (verifyTls) return verifiedHttpClient
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        return verifiedHttpClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun publishLeases() {
        _leasesState.value = activeRelays.values
            .map { it.lease }
            .sortedByDescending { it.createdAtEpochMs }
    }
}
