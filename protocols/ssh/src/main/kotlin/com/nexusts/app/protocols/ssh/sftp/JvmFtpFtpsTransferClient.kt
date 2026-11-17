package com.nexusts.app.protocols.ssh.sftp

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

enum class FtpSecurityMode {
    PLAIN_FTP,
    EXPLICIT_FTPS,
    IMPLICIT_FTPS
}

data class RemoteTransferEntry(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean = false,
    val sizeBytes: Long = 0L,
    val unixMode: Int = if (isDirectory) 0b111_101_101 else 0b110_100_100,
    val modifiedIso: String = "",
    val owner: String = "0"
) {
    fun formatRwxPermissions(): String {
        val kind = when {
            isDirectory -> 'd'
            isSymlink -> 'l'
            else -> '-'
        }
        fun triplet(shift: Int): String {
            val bits = (unixMode ushr shift) and 0b111
            val r = if ((bits and 0b100) != 0) 'r' else '-'
            val w = if ((bits and 0b010) != 0) 'w' else '-'
            val x = if ((bits and 0b001) != 0) 'x' else '-'
            return "$r$w$x"
        }
        return "$kind${triplet(6)}${triplet(3)}${triplet(0)}"
    }
}

data class FtpReply(
    val code: Int,
    val message: String,
    val lines: List<String>
) {
    val isPositivePreliminary: Boolean get() = code in 100..199
    val isPositiveCompletion: Boolean get() = code in 200..299
    val isPositiveIntermediate: Boolean get() = code in 300..399
}

/**
 * Pure-Kotlin RFC 959 / RFC 2428 (`EPSV`) / RFC 3659 (`MLSD`) / RFC 4217 (`AUTH TLS`, `PBSZ 0`, `PROT P`)
 * FTP, Explicit FTPS, and Implicit FTPS client powered by `java.net.Socket` & `javax.net.ssl.SSLSocketFactory`.
 */
class JvmFtpFtpsTransferClient(
    val host: String,
    val port: Int = 21,
    val username: String = "anonymous",
    val password: String = "anonymous@",
    val securityMode: FtpSecurityMode = FtpSecurityMode.PLAIN_FTP,
    val allowInsecureTls: Boolean = true,
    val connectTimeoutMs: Int = 8_000,
    val readTimeoutMs: Int = 15_000,
    customSslSocketFactory: SSLSocketFactory? = null
) : Closeable {

    private val sslSocketFactory: SSLSocketFactory = customSslSocketFactory ?: buildSslSocketFactory(allowInsecureTls)

    private var controlSocket: Socket? = null
    private var controlReader: BufferedReader? = null
    private var controlWriter: BufferedOutputStream? = null
    private var dataChannelProtected: Boolean = false
    private var activeControlPort: Int = 21
    private var controlPeerAddress: InetAddress? = null
    private var negotiatedControlTlsProtocol: String = ""
    private var epsvSupported: Boolean = true
    private var mlsdSupported: Boolean = true

    @Volatile
    var controlSessionIdHex: String = ""
        private set

    @Volatile
    var lastDataSessionIdHex: String = ""
        private set

    val isConnected: Boolean
        get() = controlSocket?.let { it.isConnected && !it.isClosed } == true

    @Synchronized
    fun connect() {
        close()
        val trimmedHost = host.trim()
        require(trimmedHost.isNotEmpty()) { "FTP/FTPS host cannot be empty" }
        val targetPort = when {
            port > 0 -> port
            securityMode == FtpSecurityMode.IMPLICIT_FTPS -> 990
            else -> 21
        }
        activeControlPort = targetPort
        epsvSupported = true
        mlsdSupported = true

        val rawTcp = Socket()
        rawTcp.tcpNoDelay = true
        rawTcp.soTimeout = readTimeoutMs
        rawTcp.connect(InetSocketAddress(trimmedHost, targetPort), connectTimeoutMs)
        controlPeerAddress = rawTcp.inetAddress

        if (securityMode == FtpSecurityMode.IMPLICIT_FTPS) {
            val sslSock = wrapSocketWithTls(rawTcp, trimmedHost, targetPort)
            negotiatedControlTlsProtocol = sslSock.session.protocol.orEmpty()
            controlSessionIdHex = sslSock.session.id.joinToString("") { "%02x".format(it) }
            attachControlStreams(sslSock)
            val greeting = readControlReply()
            if (!greeting.isPositiveCompletion) {
                close()
                throw IOException("Implicit FTPS greeting rejected: ${greeting.code} ${greeting.message}")
            }
            authenticateAndNegotiateProtection()
        } else {
            attachControlStreams(rawTcp)
            val greeting = readControlReply()
            if (!greeting.isPositiveCompletion) {
                close()
                throw IOException("FTP greeting rejected: ${greeting.code} ${greeting.message}")
            }
            if (securityMode == FtpSecurityMode.EXPLICIT_FTPS) {
                val authReply = sendCommand("AUTH TLS")
                if (authReply.code != 234) {
                    close()
                    throw IOException("Explicit FTPS AUTH TLS rejected: ${authReply.code} ${authReply.message}")
                }
                val sslSock = wrapSocketWithTls(rawTcp, trimmedHost, targetPort)
                negotiatedControlTlsProtocol = sslSock.session.protocol.orEmpty()
                controlSessionIdHex = sslSock.session.id.joinToString("") { "%02x".format(it) }
                attachControlStreams(sslSock)
            }
            authenticateAndNegotiateProtection()
        }

        val typeReply = sendCommand("TYPE I")
        if (!typeReply.isPositiveCompletion) {
            throw IOException("FTP TYPE I failed: ${typeReply.code} ${typeReply.message}")
        }
    }

    private fun authenticateAndNegotiateProtection() {
        val effectiveUser = username.ifBlank { "anonymous" }
        val userReply = sendCommand("USER $effectiveUser")
        when {
            userReply.isPositiveCompletion -> Unit
            userReply.isPositiveIntermediate -> {
                val passReply = sendCommand("PASS $password")
                if (!passReply.isPositiveCompletion) {
                    close()
                    throw IOException("FTP authentication failed: ${passReply.code} ${passReply.message}")
                }
            }
            else -> {
                close()
                throw IOException("FTP USER rejected: ${userReply.code} ${userReply.message}")
            }
        }

        if (securityMode == FtpSecurityMode.EXPLICIT_FTPS || securityMode == FtpSecurityMode.IMPLICIT_FTPS) {
            val pbszReply = sendCommand("PBSZ 0")
            if (!pbszReply.isPositiveCompletion) {
                close()
                throw IOException("FTPS PBSZ 0 rejected: ${pbszReply.code} ${pbszReply.message}")
            }
            val protReply = sendCommand("PROT P")
            if (!protReply.isPositiveCompletion) {
                close()
                throw IOException("FTPS PROT P rejected: ${protReply.code} ${protReply.message}")
            }
            dataChannelProtected = true
        } else {
            dataChannelProtected = false
        }
    }

    private fun wrapSocketWithTls(
        plainSocket: Socket,
        peerHost: String,
        peerPort: Int,
        lockedProtocol: String? = null
    ): SSLSocket {
        val ssl = sslSocketFactory.createSocket(plainSocket, peerHost, peerPort, true) as SSLSocket
        ssl.useClientMode = true
        ssl.soTimeout = readTimeoutMs
        if (!lockedProtocol.isNullOrBlank() && ssl.supportedProtocols.contains(lockedProtocol)) {
            ssl.enabledProtocols = arrayOf(lockedProtocol)
        }
        if (!allowInsecureTls) {
            val params: SSLParameters = ssl.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = params
        }
        ssl.startHandshake()
        return ssl
    }

    private fun wrapDataSocketWithControlTlsSession(rawData: Socket): SSLSocket {
        val logicalPeerSocket = ControlSessionPeerSocket(
            delegate = rawData,
            logicalPeerAddress = controlPeerAddress ?: rawData.inetAddress,
            logicalPeerPort = activeControlPort
        )
        return wrapSocketWithTls(
            plainSocket = logicalPeerSocket,
            peerHost = host.trim(),
            peerPort = activeControlPort,
            lockedProtocol = negotiatedControlTlsProtocol.takeIf { it.isNotBlank() }
        )
    }

    private fun attachControlStreams(socket: Socket) {
        controlSocket = socket
        controlReader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        controlWriter = BufferedOutputStream(socket.getOutputStream())
    }

    @Synchronized
    fun pwd(): String {
        ensureConnected()
        val reply = sendCommand("PWD")
        if (!reply.isPositiveCompletion) {
            throw IOException("FTP PWD failed: ${reply.code} ${reply.message}")
        }
        return extractQuotedPath(reply.message) ?: "/"
    }

    @Synchronized
    fun cwd(remotePath: String): Boolean {
        ensureConnected()
        val normalized = remotePath.ifBlank { "/" }
        val reply = sendCommand("CWD $normalized")
        return reply.isPositiveCompletion
    }

    @Synchronized
    fun listDirectory(remotePath: String = ""): List<RemoteTransferEntry> {
        ensureConnected()
        val targetPath = if (remotePath.isBlank()) {
            pwd()
        } else {
            cwd(remotePath)
            remotePath
        }

        // Attempt RFC 3659 MLSD first when server supports it
        if (mlsdSupported) {
            val mlsdResult = runCatching {
                val rawBytes = executePassiveDownloadCommand("MLSD")
                val text = rawBytes.toString(StandardCharsets.UTF_8)
                parseMlsdListing(targetPath, text)
            }
            if (mlsdResult.isSuccess) {
                return mlsdResult.getOrThrow()
            }
            val msg = mlsdResult.exceptionOrNull()?.message.orEmpty()
            if (msg.contains(" 500 ") || msg.contains(" 501 ") || msg.contains(" 502 ") || msg.contains(" 504 ")) {
                mlsdSupported = false
            }
        }

        // Fallback to standard LIST (UNIX ls -la or Windows DOS directory format)
        val listBytes = executePassiveDownloadCommand("LIST")
        val listText = listBytes.toString(StandardCharsets.UTF_8)
        return parseAutoDetectedList(targetPath, listText)
    }

    @Synchronized
    fun downloadFile(remotePath: String): ByteArray {
        ensureConnected()
        return executePassiveDownloadCommand("RETR $remotePath")
    }

    @Synchronized
    fun uploadFile(remotePath: String, content: ByteArray): Boolean {
        ensureConnected()
        val (passiveHost, passivePort) = negotiatePassiveDataEndpoint()
        val rawData = connectPassiveDataSocket(passiveHost, passivePort)
        try {
            val prelim = sendCommand("STOR $remotePath")
            if (!prelim.isPositivePreliminary && !prelim.isPositiveCompletion) {
                runCatching { rawData.close() }
                throw IOException("FTP STOR rejected: ${prelim.code} ${prelim.message}")
            }

            // Wrap rawData in ControlSessionPeerSocket + lock negotiated TLS protocol so JSSE/Conscrypt
            // reuses the control TLS session required by vsftpd (require_ssl_reuse=YES), ProFTPD, & FileZilla!
            val activeDataSocket: Socket = if (dataChannelProtected) {
                wrapDataSocketWithControlTlsSession(rawData).also { ssl ->
                    lastDataSessionIdHex = ssl.session.id.joinToString("") { "%02x".format(it) }
                }
            } else {
                rawData
            }

            activeDataSocket.use { ds ->
                val out = ds.getOutputStream()
                out.write(content)
                out.flush()
            }

            if (prelim.isPositivePreliminary) {
                val done = readControlReply()
                if (!done.isPositiveCompletion) {
                    throw IOException("FTP STOR transfer completion failed: ${done.code} ${done.message}")
                }
            }
            return true
        } catch (t: Throwable) {
            runCatching { rawData.close() }
            throw t
        }
    }

    @Synchronized
    fun mkdir(remotePath: String): Boolean {
        ensureConnected()
        val reply = sendCommand("MKD $remotePath")
        return reply.isPositiveCompletion
    }

    @Synchronized
    fun remove(remotePath: String, isDirectory: Boolean = false): Boolean {
        ensureConnected()
        return if (isDirectory) {
            sendCommand("RMD $remotePath").isPositiveCompletion
        } else {
            val dele = sendCommand("DELE $remotePath")
            if (dele.isPositiveCompletion) {
                true
            } else {
                sendCommand("RMD $remotePath").isPositiveCompletion
            }
        }
    }

    @Synchronized
    fun rename(oldPath: String, newPath: String): Boolean {
        ensureConnected()
        val rnfr = sendCommand("RNFR $oldPath")
        if (!rnfr.isPositiveIntermediate && !rnfr.isPositiveCompletion) {
            return false
        }
        val rnto = sendCommand("RNTO $newPath")
        return rnto.isPositiveCompletion
    }

    @Synchronized
    fun chmod(remotePath: String, modeOctal: Int): Boolean {
        ensureConnected()
        val octalDigits = String.format(Locale.US, "%04o", modeOctal and 0b111_111_111_111)
        val reply = sendCommand("SITE CHMOD $octalDigits $remotePath")
        return reply.isPositiveCompletion
    }

    private fun executePassiveDownloadCommand(command: String): ByteArray {
        val (passiveHost, passivePort) = negotiatePassiveDataEndpoint()
        val rawData = connectPassiveDataSocket(passiveHost, passivePort)
        try {
            val prelim = sendCommand(command)
            if (!prelim.isPositivePreliminary && !prelim.isPositiveCompletion) {
                runCatching { rawData.close() }
                throw IOException("FTP command '$command' rejected: ${prelim.code} ${prelim.message}")
            }

            // Wrap rawData in ControlSessionPeerSocket + lock negotiated TLS protocol so JSSE/Conscrypt
            // reuses the control TLS session!
            val activeDataSocket: Socket = if (dataChannelProtected) {
                wrapDataSocketWithControlTlsSession(rawData).also { ssl ->
                    lastDataSessionIdHex = ssl.session.id.joinToString("") { "%02x".format(it) }
                }
            } else {
                rawData
            }

            val out = ByteArrayOutputStream(4096)
            activeDataSocket.use { ds ->
                val inp = ds.getInputStream()
                val buf = ByteArray(16 * 1024)
                try {
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        if (r > 0) out.write(buf, 0, r)
                    }
                } catch (sslEx: SSLException) {
                    // Tolerate benign missing close_notify from minimal FTPS servers if payload already arrived
                    val msg = sslEx.message.orEmpty().lowercase(Locale.ROOT)
                    if (!msg.contains("close_notify") && !msg.contains("closing inbound")) {
                        throw sslEx
                    }
                }
            }

            if (prelim.isPositivePreliminary) {
                val done = readControlReply()
                if (!done.isPositiveCompletion) {
                    throw IOException("FTP '$command' transfer completion failed: ${done.code} ${done.message}")
                }
            }
            return out.toByteArray()
        } catch (t: Throwable) {
            runCatching { rawData.close() }
            throw t
        }
    }

    private fun connectPassiveDataSocket(targetHost: String, passivePort: Int): Socket {
        val primary = Socket()
        primary.tcpNoDelay = true
        primary.soTimeout = readTimeoutMs
        try {
            primary.connect(InetSocketAddress(targetHost, passivePort), connectTimeoutMs)
            return primary
        } catch (primaryErr: IOException) {
            runCatching { primary.close() }
            val fallbackHost = controlSocket?.inetAddress?.hostAddress ?: host.trim()
            if (fallbackHost.isBlank() || fallbackHost == targetHost) {
                throw primaryErr
            }
            val secondary = Socket()
            secondary.tcpNoDelay = true
            secondary.soTimeout = readTimeoutMs
            secondary.connect(InetSocketAddress(fallbackHost, passivePort), connectTimeoutMs)
            return secondary
        }
    }

    private fun negotiatePassiveDataEndpoint(): Pair<String, Int> {
        val controlPeerIp = controlSocket?.inetAddress?.hostAddress?.takeIf { it.isNotBlank() } ?: host.trim()
        var epsvCode = 0
        if (epsvSupported) {
            val epsv = sendCommand("EPSV")
            epsvCode = epsv.code
            if (epsv.code == 229) {
                parseEpsvPort(epsv.message)?.let { return controlPeerIp to it }
            } else if (epsv.code in listOf(500, 501, 502, 522)) {
                epsvSupported = false
            }
        }
        val pasv = sendCommand("PASV")
        if (pasv.code == 227) {
            parsePasvEndpoint(pasv.message)?.let { (pasvIp, pasvPort) ->
                val resolvedIp = resolveNatSafePasvHost(pasvIp, controlPeerIp)
                return resolvedIp to pasvPort
            }
        }
        throw IOException("Failed to negotiate passive data port (EPSV=$epsvCode, PASV=${pasv.code})")
    }

    private fun ensureConnected() {
        if (!isConnected) {
            connect()
        }
    }

    @Synchronized
    fun sendCommand(commandLine: String): FtpReply {
        val writer = controlWriter ?: throw IOException("FTP control channel is not open")
        val cleanCmd = commandLine.replace("\r", "").replace("\n", "")
        writer.write("$cleanCmd\r\n".toByteArray(StandardCharsets.UTF_8))
        writer.flush()
        return readControlReply()
    }

    private fun readControlReply(): FtpReply {
        val reader = controlReader ?: throw IOException("FTP control reader is not open")
        val lines = mutableListOf<String>()
        val firstLine = reader.readLine() ?: throw IOException("FTP server closed control connection unexpectedly")
        lines += firstLine
        if (firstLine.length < 3) {
            throw IOException("Malformed FTP reply line: $firstLine")
        }
        val code = firstLine.substring(0, 3).toIntOrNull()
            ?: throw IOException("Invalid FTP numeric reply code in: $firstLine")

        if (firstLine.length >= 4 && firstLine[3] == '-') {
            val endPrefix = "$firstLine".substring(0, 3) + " "
            while (true) {
                val next = reader.readLine() ?: throw IOException("EOF during multi-line FTP reply $code")
                lines += next
                if (next.startsWith(endPrefix)) break
            }
        }
        val finalMsg = lines.last().let { if (it.length > 4) it.substring(4) else "" }
        return FtpReply(code = code, message = finalMsg, lines = lines)
    }

    @Synchronized
    override fun close() {
        val sock = controlSocket
        controlSocket = null
        if (sock != null && !sock.isClosed) {
            runCatching {
                controlWriter?.write("QUIT\r\n".toByteArray(StandardCharsets.US_ASCII))
                controlWriter?.flush()
            }
            runCatching { sock.close() }
        }
        controlReader = null
        controlWriter = null
        dataChannelProtected = false
        controlPeerAddress = null
        negotiatedControlTlsProtocol = ""
    }

    companion object {
        fun buildSslSocketFactory(allowInsecureTls: Boolean): SSLSocketFactory {
            if (!allowInsecureTls) {
                return SSLContext.getDefault().socketFactory
            }
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            ctx.clientSessionContext?.sessionCacheSize = 64
            ctx.clientSessionContext?.sessionTimeout = 3600
            return ctx.socketFactory
        }

        fun parseEpsvPort(message: String): Int? {
            val openIdx = message.indexOf('(')
            val closeIdx = message.indexOf(')', openIdx + 1)
            if (openIdx < 0 || closeIdx <= openIdx + 4) return null
            val inside = message.substring(openIdx + 1, closeIdx)
            val delim = inside.firstOrNull() ?: return null
            val parts = inside.split(delim)
            if (parts.size < 4) return null
            return parts[3].trim().toIntOrNull()?.takeIf { it in 1..65535 }
        }

        fun parsePasvEndpoint(message: String): Pair<String, Int>? {
            val match = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").find(message) ?: return null
            val h1 = match.groupValues[1].toIntOrNull() ?: return null
            val h2 = match.groupValues[2].toIntOrNull() ?: return null
            val h3 = match.groupValues[3].toIntOrNull() ?: return null
            val h4 = match.groupValues[4].toIntOrNull() ?: return null
            val p1 = match.groupValues[5].toIntOrNull() ?: return null
            val p2 = match.groupValues[6].toIntOrNull() ?: return null
            val port = (p1 shl 8) + p2
            if (port !in 1..65535) return null
            return "$h1.$h2.$h3.$h4" to port
        }

        fun parsePasvPort(message: String): Int? = parsePasvEndpoint(message)?.second

        fun resolveNatSafePasvHost(pasvIp: String, controlPeerIp: String): String {
            if (pasvIp == "0.0.0.0" || pasvIp.startsWith("0.")) return controlPeerIp
            if (pasvIp == controlPeerIp) return pasvIp
            val pasvUnroutable = isPrivateOrLoopbackIpv4(pasvIp)
            val controlPrivate = isPrivateOrLoopbackIpv4(controlPeerIp)
            // If server advertises an internal RFC1918/loopback IP over NAT while control peer is public
            // OR advertises a different RFC1918 subnet/loopback than the connected control peer, prefer controlPeerIp.
            if (pasvUnroutable && (!controlPrivate || pasvIp.startsWith("127.") != controlPeerIp.startsWith("127."))) {
                return controlPeerIp
            }
            return pasvIp
        }

        private fun isPrivateOrLoopbackIpv4(ip: String): Boolean {
            val parts = ip.split('.').map { it.toIntOrNull() ?: return false }
            if (parts.size != 4) return false
            val (a, b) = parts[0] to parts[1]
            return a == 10 ||
                a == 127 ||
                (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) ||
                (a == 169 && b == 254)
        }

        fun extractQuotedPath(message: String): String? {
            val start = message.indexOf('"')
            if (start < 0) return null
            val sb = StringBuilder()
            var i = start + 1
            while (i < message.length) {
                val ch = message[i]
                if (ch == '"') {
                    if (i + 1 < message.length && message[i + 1] == '"') {
                        sb.append('"')
                        i += 2
                        continue
                    }
                    return sb.toString().ifBlank { "/" }
                }
                sb.append(ch)
                i++
            }
            return null
        }

        fun parseMlsdListing(parentDir: String, mlsdBody: String): List<RemoteTransferEntry> {
            val normalizedParent = normalizeRemoteDir(parentDir)
            val results = mutableListOf<RemoteTransferEntry>()
            val mlsdDateFmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val displayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

            for (rawLine in mlsdBody.lineSequence()) {
                val line = rawLine.trimEnd('\r', '\n')
                if (line.isBlank()) continue
                val spaceIdx = line.indexOf(' ')
                if (spaceIdx <= 0 || spaceIdx >= line.lastIndex) continue
                val factsPart = line.substring(0, spaceIdx)
                val fileName = line.substring(spaceIdx + 1).trim()
                if (fileName.isEmpty() || fileName == "." || fileName == "..") continue

                val facts = mutableMapOf<String, String>()
                for (token in factsPart.split(';')) {
                    val eq = token.indexOf('=')
                    if (eq > 0) {
                        facts[token.substring(0, eq).trim().lowercase(Locale.ROOT)] = token.substring(eq + 1).trim()
                    }
                }

                val typeVal = facts["type"].orEmpty().lowercase(Locale.ROOT)
                if (typeVal == "cdir" || typeVal == "pdir") continue
                val isDir = typeVal == "dir"
                val isSymlink = typeVal.contains("symlink") || typeVal.contains("os.unix=slink")
                val sizeBytes = facts["size"]?.toLongOrNull() ?: facts["sizd"]?.toLongOrNull() ?: 0L
                val mode = facts["unix.mode"]?.trimStart('0')?.ifEmpty { "0" }?.toIntOrNull(8)
                    ?: if (isDir) 0b111_101_101 else 0b110_100_100
                val owner = facts["unix.owner"] ?: facts["unix.uid"] ?: "0"
                val modifyRaw = facts["modify"].orEmpty().take(14)
                val modifiedIso = if (modifyRaw.length == 14) {
                    runCatching {
                        val parsed: Date? = mlsdDateFmt.parse(modifyRaw)
                        if (parsed != null) displayFmt.format(parsed) else ""
                    }.getOrDefault("")
                } else {
                    ""
                }

                results += RemoteTransferEntry(
                    name = fileName,
                    fullPath = joinRemotePath(normalizedParent, fileName),
                    isDirectory = isDir,
                    isSymlink = isSymlink,
                    sizeBytes = sizeBytes,
                    unixMode = mode,
                    modifiedIso = modifiedIso,
                    owner = owner
                )
            }
            return results
        }

        fun parseAutoDetectedList(parentDir: String, listBody: String): List<RemoteTransferEntry> {
            val nonBlank = listBody.lineSequence().map { it.trimEnd('\r', '\n') }.filter { it.isNotBlank() }.toList()
            if (nonBlank.isEmpty()) return emptyList()
            val looksLikeDos = nonBlank.any { line ->
                Regex("^\\d{2}-\\d{2}-\\d{2,4}\\s+\\d{1,2}:\\d{2}(AM|PM)?\\s+", RegexOption.IGNORE_CASE)
                    .containsMatchIn(line.trimStart())
            }
            return if (looksLikeDos) {
                parseDosListListing(parentDir, listBody)
            } else {
                parseUnixListListing(parentDir, listBody)
            }
        }

        fun parseUnixListListing(parentDir: String, listBody: String): List<RemoteTransferEntry> {
            val normalizedParent = normalizeRemoteDir(parentDir)
            val results = mutableListOf<RemoteTransferEntry>()
            val unixRegex = Regex(
                "^([dlcbps-])([rwxstST-]{9})[@+]?\\s+\\d+\\s+(\\S+)\\s+.+?\\s+(\\d+)\\s+([A-Za-z]{3}\\s+\\d{1,2}\\s+(?:\\d{4}|\\d{1,2}:\\d{2}))\\s+(.+)$"
            )
            for (rawLine in listBody.lineSequence()) {
                val line = rawLine.trimEnd('\r', '\n').trim()
                if (line.isEmpty() || line.startsWith("total ")) continue
                val match = unixRegex.matchEntire(line) ?: continue
                val kindChar = match.groupValues[1][0]
                val permTriplets = match.groupValues[2]
                val owner = match.groupValues[3]
                val sizeBytes = match.groupValues[4].toLongOrNull() ?: 0L
                val datePart = match.groupValues[5]
                val rawName = match.groupValues[6]
                val isDir = kindChar == 'd'
                val isSymlink = kindChar == 'l'
                val cleanName = if (isSymlink && rawName.contains(" -> ")) {
                    rawName.substringBefore(" -> ").trim()
                } else {
                    rawName.trim()
                }
                if (cleanName.isEmpty() || cleanName == "." || cleanName == "..") continue
                val mode = symbolicTripletsToOctal(permTriplets) ?: if (isDir) 0b111_101_101 else 0b110_100_100
                results += RemoteTransferEntry(
                    name = cleanName,
                    fullPath = joinRemotePath(normalizedParent, cleanName),
                    isDirectory = isDir,
                    isSymlink = isSymlink,
                    sizeBytes = sizeBytes,
                    unixMode = mode,
                    modifiedIso = datePart,
                    owner = owner
                )
            }
            return results
        }

        fun parseDosListListing(parentDir: String, listBody: String): List<RemoteTransferEntry> {
            val normalizedParent = normalizeRemoteDir(parentDir)
            val results = mutableListOf<RemoteTransferEntry>()
            val dosRegex = Regex(
                "^(\\d{2}-\\d{2}-\\d{2,4}\\s+\\d{1,2}:\\d{2}(?:AM|PM)?)\\s+(<DIR>|\\d+)\\s+(.+)$",
                RegexOption.IGNORE_CASE
            )
            for (rawLine in listBody.lineSequence()) {
                val line = rawLine.trimEnd('\r', '\n').trimStart()
                if (line.isBlank()) continue
                val match = dosRegex.matchEntire(line) ?: continue
                val dateStr = match.groupValues[1].trim()
                val dirOrSize = match.groupValues[2].trim()
                val name = match.groupValues[3].trim()
                if (name.isEmpty() || name == "." || name == "..") continue
                val isDir = dirOrSize.equals("<DIR>", ignoreCase = true)
                val sizeBytes = if (isDir) 0L else dirOrSize.toLongOrNull() ?: 0L
                results += RemoteTransferEntry(
                    name = name,
                    fullPath = joinRemotePath(normalizedParent, name),
                    isDirectory = isDir,
                    isSymlink = false,
                    sizeBytes = sizeBytes,
                    unixMode = if (isDir) 0b111_101_101 else 0b110_100_100,
                    modifiedIso = dateStr,
                    owner = "ftp"
                )
            }
            return results
        }

        internal fun symbolicTripletsToOctal(triplets: String): Int? {
            if (triplets.length != 9) return null
            var mode = 0
            // Owner
            when (triplets[0]) {
                'r' -> mode = mode or 0b100_000_000
                '-' -> Unit
                else -> return null
            }
            when (triplets[1]) {
                'w' -> mode = mode or 0b010_000_000
                '-' -> Unit
                else -> return null
            }
            when (triplets[2]) {
                'x' -> mode = mode or 0b001_000_000
                's' -> mode = mode or 0b001_000_000 or 0b100_000_000_000
                'S' -> mode = mode or 0b100_000_000_000
                '-' -> Unit
                else -> return null
            }
            // Group
            when (triplets[3]) {
                'r' -> mode = mode or 0b000_100_000
                '-' -> Unit
                else -> return null
            }
            when (triplets[4]) {
                'w' -> mode = mode or 0b000_010_000
                '-' -> Unit
                else -> return null
            }
            when (triplets[5]) {
                'x' -> mode = mode or 0b000_001_000
                's' -> mode = mode or 0b000_001_000 or 0b010_000_000_000
                'S' -> mode = mode or 0b010_000_000_000
                '-' -> Unit
                else -> return null
            }
            // Other
            when (triplets[6]) {
                'r' -> mode = mode or 0b000_000_100
                '-' -> Unit
                else -> return null
            }
            when (triplets[7]) {
                'w' -> mode = mode or 0b000_000_010
                '-' -> Unit
                else -> return null
            }
            when (triplets[8]) {
                'x' -> mode = mode or 0b000_000_001
                't' -> mode = mode or 0b000_000_001 or 0b001_000_000_000
                'T' -> mode = mode or 0b001_000_000_000
                '-' -> Unit
                else -> return null
            }
            return mode
        }

        private fun normalizeRemoteDir(dir: String): String {
            val trimmed = dir.trim()
            if (trimmed.isEmpty() || trimmed == "/") return "/"
            return "/" + trimmed.trim('/')
        }

        private fun joinRemotePath(parentDir: String, childName: String): String {
            val cleanParent = normalizeRemoteDir(parentDir)
            val cleanChild = childName.trimStart('/')
            return if (cleanParent == "/") "/$cleanChild" else "$cleanParent/$cleanChild"
        }
    }
}

/**
 * Zero-reflection delegating [Socket] that presents the FTPS control channel's `(InetAddress, port)`
 * to `SSLSocketFactory.createSocket(Socket, host, port, autoClose)`.
 *
 * OpenJDK's `BaseSSLSocketImpl.getPort()` (and `SSLSocketImpl.getPeerPort()`) delegates directly to
 * the underlying `Socket.getPort()` rather than using the `port` argument passed to `createSocket(...)`,
 * which otherwise causes JSSE's `sessionHostPortCache` to miss the cached control TLS session on
 * ephemeral `PASV`/`EPSV` data ports (`vsftpd` `require_ssl_reuse=YES`, ProFTPD, FileZilla Server).
 */
private class ControlSessionPeerSocket(
    private val delegate: Socket,
    private val logicalPeerAddress: InetAddress?,
    private val logicalPeerPort: Int
) : Socket() {
    override fun getPort(): Int = logicalPeerPort
    override fun getInetAddress(): InetAddress? = logicalPeerAddress ?: delegate.inetAddress
    override fun getRemoteSocketAddress(): SocketAddress =
        InetSocketAddress(logicalPeerAddress ?: delegate.inetAddress, logicalPeerPort)
    override fun getLocalAddress(): InetAddress = delegate.localAddress
    override fun getLocalPort(): Int = delegate.localPort
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
    override fun getChannel(): SocketChannel? = delegate.channel
    override fun getInputStream(): InputStream = delegate.inputStream
    override fun getOutputStream(): OutputStream = delegate.outputStream
    override fun setTcpNoDelay(on: Boolean) {
        delegate.tcpNoDelay = on
    }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)
    override fun getSoLinger(): Int = delegate.soLinger
    override fun setSoTimeout(timeout: Int) {
        delegate.soTimeout = timeout
    }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setSendBufferSize(size: Int) {
        delegate.sendBufferSize = size
    }
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun setReceiveBufferSize(size: Int) {
        delegate.receiveBufferSize = size
    }
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun setKeepAlive(on: Boolean) {
        delegate.keepAlive = on
    }
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isBound(): Boolean = delegate.isBound
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
    @Synchronized
    override fun close() = delegate.close()
}

typealias JvmFtpsTransferClient = JvmFtpFtpsTransferClient
