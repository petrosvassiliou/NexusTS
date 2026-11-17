package com.nexusts.app

import com.nexusts.app.core.ui.workspace.AdHocConnectionSyntaxParser
import com.nexusts.app.core.ui.workspace.SessionProtocolKind
import com.nexusts.app.core.vault.credentials.ResolvedSessionSecret
import com.nexusts.app.core.vault.exporter.VaultDocumentExporter
import com.nexusts.app.core.vault.importer.RtszDocumentImporter
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.feature.workspace.NexusWorkspaceViewModel
import com.nexusts.app.feature.workspace.toProtocolKind
import com.nexusts.app.protocols.ssh.bridge.SshTerminalSessionController
import com.nexusts.app.protocols.ssh.sftp.FtpSecurityMode
import com.nexusts.app.protocols.ssh.sftp.JvmFtpFtpsTransferClient
import com.nexusts.app.protocols.ssh.ui.parseChmodPermissionsToOctal
import com.nexusts.app.protocols.vnc.bridge.VncRfbSessionClient
import com.nexusts.app.protocols.vnc.cloud.CloudVirtualizationHubClient
import com.nexusts.app.protocols.vnc.cloud.ProxmoxVeApiClient
import com.nexusts.app.protocols.vnc.cloud.VmPowerAction
import com.nexusts.app.protocols.vnc.ui.KubernetesExecClient
import com.nexusts.app.protocols.vnc.ui.normalizeWebSessionUrl
import com.nexusts.app.protocols.vnc.ui.resolveWorkloadBaseEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Live `127.0.0.1:0` loopback wire-protocol verification suite for Android/Googlebook v0.8.0:
 * 1. `WINDOWS_TERMINAL_SERVICES` -> `RDP` routing, `TELNET` default port `23`, ad-hoc subtype preservation, & `chmod` parser
 * 2. Explicit FTPS (`AUTH TLS` -> `234` -> TLS upgrade -> `PBSZ 0` -> `PROT P` -> TLS-wrapped passive `EPSV` `MLSD`/`STOR`/`RETR`/`CHMOD`)
 * 3. Kubernetes `v4.channel.k8s.io` WebSocket Exec over self-signed TLS (`0x00` stdin, `0x01` stdout, `0x02` stderr, `0x03` status, `0x04` resize)
 * 4. Proxmox VE REST API over self-signed HTTPS (`POST /api2/json/access/ticket` -> `PVEAuthCookie` + `CSRFPreventionToken`)
 * 5. Stateful Telnet RFC 854 / TTYPE (`24`) / NAWS (`31`) split-packet negotiation & live PTY resize over loopback TCP
 */
class ProtocolWireLoopbackTest {

    companion object {
        // Self-signed RSA-2048 PKCS#12 keystore (password = "nexusts", SAN = IP:127.0.0.1, DNS:localhost)
        private const val LOOPBACK_PKCS12_BASE64 =
            "MIIKQAIBAzCCCfYGCSqGSIb3DQEHAaCCCecEggnjMIIJ3zCCBDIGCSqGSIb3DQEHBqCCBCMwggQfAgEAMIIEGAYJKoZIhvcNAQcBMFcGCSqGSIb3DQEFDTBKMCkGCSqGSIb3DQEFDDAcBAgE0vcGT4p/CwICCAAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEEoBUOkiCY2F8e9tiOdBjOOAggOwxzjOdXlk1OBRCJ3LIPZ54afsf8hlIKPvJqRdYrhOpYbUak64dNJDJy8NtKhmXnjnIp7WrmxXbBZvqGBtzDnYi+AfmghF97c8kqTIF6KVrbPfg/aJXgJcLnsSDqwClgoe39SsQm8uoYQs+rj0fjK/iEgFBiNbggPasHPWixmpqzvU/35zHpaRyumfyoByOH6mrNPDL/Cs+ilCkK+I+VtjIUQpb79gyhsdAHZTok11UrOIC97/L1arg//DxyoEpUplPJSRPZKA375txWfRvSqRx4wjfSSBThUH+FOO4IRcqyHIp2dq2gwvbE0Hn3z+L6qWDogrWzPt+QjmCmIDJw1nUTwF0vFPlbrLRbvYP3aAUienVw67IHHmOlX/n1gqH/K9nZvpSn62heq9JMDA7as4hham2HdQE18Yh4Az1PVthd0V866e2mxYBVa81e2cWlQjJfiSnJeloA5Y6jkYDwDuUCiVwCDEDgk1Djx7tVjLt1vb5diW6zfiK3LxFOHMlBG6GspcVNaVgwL4AtYv0i3nqRkoUfx2wsqS7nJ5iF97cUDST+TbO5ng0VR7RIujBkF18/TsAecq+FDTwi9hz5mtwFLuYeyG9KKnaRolLJ8IZO97sYkyry+YsZHwbxpJNH0ZiGYNdgoE8lI9SrV03iZXwC/yLsz3uHj9Vr7h8BYpHwGK3H4g4uMNEsH/UmMCa9Jhj3xPZWpQEkrLW24pCClIgAJm5SipMznQwXasq39ZF5AyrrahwcxEMysv99TNOfFS5u8/3UKChQS6QXV9wLMsibbarkkLgNV+zNrdGlMLSWK+AMTAcZV+rVqyv4WlaWX5GUuO3MZlDhX8DM2elodoLfHJ4+J70SpKIegT1IBlSU+AoGyrbltqoINMp6T1qTeHp1pRhI1jk9Lo6lZ0j4DmA23HWSemHP1k72QvwtkGgw0tQZ/xvIE3kPK0z49kQkF8t8woNayw42oeYPcTPUDhTSzvkzDzIwpOSfixX+/2UQ0QecjYPS44xC8kfOyxEMWwm3kCsiU0+FRZYZN/OgaOk6WjkIcivwnv9Ix4bIms5MUjpGp1ehRpFR//SUpZgrvcwPB9yU8RAKJeLZAP+XmGn0Npn5L81hq6CMZTTNaADJ6r6XgeKU2+95bOndDAwCF2s+gsghXbahPIZVZpouRPl4XUL71P6XBTptsTyzyBvx3IV7bv/qhl3qXf9ErUPkxQF9LIgootHY4CW4QG412a9N+ZgMJpTgmWmqQ8EPAD2fUwggWlBgkqhkiG9w0BBwGgggWWBIIFkjCCBY4wggWKBgsqhkiG9w0BDAoBAqCCBTEwggUtMFcGCSqGSIb3DQEFDTBKMCkGCSqGSIb3DQEFDDAcBAg1LOcZvjpZDwICCAAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEB6LjrWmYId1OVxXlIbnaLcEggTQN/V4I2KbnkhUI+cpFpjZHRJiUK3EKnQsVXV9WMw37sYutKjJ3OB8EDgpOZBo1n27a7kHHSx/iULk8s8Awlx6sTQNrQS9zQAz5DTOeJKmbH2sl4HlqPJijDszehQZq6iVD5qG29WmfygNTj4A+FqMnya6LhJZ7GyeTrSQCgI5gBjz+rFElH57T7LFqJzHwbgjgZRtiM/A2HlEVIntYk918ZJAFQRfy85gLANrbOeGxDz46quQOKNhGSRd+E62p51lu8xB3N8Y0NMSqDaPLtIYB9cmfYxwn5UHhFeDn0+/0Fz4CYxXi9uiCU3WKJNgFYhVKbp74roABkJ0TW+mC+SXH8kOQvalp8KTEJp8UeRP1VbjLGUF6AqGl12najilEybvfascTShWOxfAboj9nIaQ/a0VTFOLWmYB9ueNKgs7BiulsHXByAJDKjNmXHSdQPaXVYj9h01QQNSS/Ej9btWV2I5qj2kitTIsx3bWK3ETJZnvdwxhlYTDJyadNZ/ZGlRf+E4H5CEa0uHyrPDyUKguvkgpEQuqQ6GDXNi8Lcg4HYLvyRKroyQLI0SrtAFsPm80GNwkN+910Z50NLekL8ZmkhuDBUcs5B3/9+UCEbqmGw/Plfw9l0aTJ5VpTkoEBYRBqHG+xbqifqVBM2Lfs3+uI+FX96lnIcPujsmJV2QhXjybAsSfoJG5fkQ98mYJ/qCsOaupxMUjhk3Nf5bZI34Yh3XlLa8EPCsNeZjmoiXVYFcJcqX3eJN7y2J15wJQdGmD4eEl2IdYXjKqdse8iSd42cqOoDOD00zi6YLRkUE1d0UJMQ7rl/MYPYCG0Zjofh0i7A2NlMDIq7Kgvh4g7dl7DphN2O3HltwW8PfSCgxsyusGShNT+H0lCQeZc04ID7rne2l++8J11PQBg1HN9II4a+O5bOyYRQgMavrofIhlRLQoDVlqs1XF3OeZkxTh+mbelCUR09urmZKsOP/6C2rFlMRkjdExf1BFjO4cIfiArr3Fsrm3b1wtdOmcVBcMbqdLHxI2MfKvTlPOf6/3ojbB6vl2K4qfFntnOkNk30hnzYNmoCcGFuT7TG/Q2HeIX7YztijaF4dSPIl+LXCKwkgi8dmMpDFhhrJ3UdRxiICU1Jk9ocXC2Rk9D9HA8b7MkIVod27XJNR7jQyPnUJFToRy/xrtBJ5LfmQcL5a4HhmVCLbKtW05irXP4VEAzfvzmBaEXPKM/mw4tRz2kj0wbT4LuURD3UmRtXKeKD2+N5/GA7UrIpkMfSdzj7lU/sqLkb47xDSBtN5tfUHL1z+md0CnVzF7SRvlG43Sa4FBTW6fAUX9JKRzt3mwFzrM4lkDviXR+LRYI7mara7fu7ottkp52CNsw41zWnCp7rUgVE7G8mlswI46FB3BNIEk6nJQHrvycWOQnVmAfMOTfWHJm98yIhCHkheUHiu/9tDYbe0T1CQvD0szorwRCPw8FnrAwP69atHmowtC83QOq6BOXb0RArhTQdaD8F9fYje+P8NFahilFpx0KTdZ3NuqnmkKt38QmDXr9frDE9KPYXYyvuXEJz/G78Fm4mDp7nvbRokNcNdTPKZp/DffL3nNubsRPczF//3WMjY/+Z31eYiT4qhzT42z6zFFSyHZUvGVAK3jYX0xRjAfBgkqhkiG9w0BCRQxEh4QAGwAbwBvAHAAYgBhAGMAazAjBgkqhkiG9w0BCRUxFgQU8T9JjSASxWRp3WpxnJ/3YlHaj9UwQTAxMA0GCWCGSAFlAwQCAQUABCCSfHdFLfzNY6Mvc4+3gsFsViJN+4mHwdN6pNxr1VXZ6AQIKXdQbYlUuQUCAggA"

        private val serverSslContext: SSLContext by lazy {
            val password = "nexusts".toCharArray()
            val ks = KeyStore.getInstance("PKCS12").apply {
                load(ByteArrayInputStream(Base64.getDecoder().decode(LOOPBACK_PKCS12_BASE64)), password)
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(ks, password)
            }
            SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, null, SecureRandom())
            }
        }

        private val loopbackAddr: InetAddress
            get() = InetAddress.getByName("127.0.0.1")
    }

    @Test
    fun windowsTerminalServicesRoutingTelnetPort23AdHocSubtypesAndChmodOctalParserWork() {
        // 1. WINDOWS_TERMINAL_SERVICES must route to SessionProtocolKind.RDP
        assertEquals(SessionProtocolKind.RDP, VaultProtocolType.WINDOWS_TERMINAL_SERVICES.toProtocolKind())
        assertEquals(
            SessionProtocolKind.RDP,
            NexusWorkspaceViewModel.mapProtocolToUiKind(VaultProtocolType.WINDOWS_TERMINAL_SERVICES)
        )

        // 2. TELNET connections default to TCP port 23 even when Ssh() carries default port = 22
        val telnetWithDefaultSshSettings = VaultConnection(
            name = "Core Router Telnet",
            host = "10.0.0.1",
            protocol = VaultProtocolType.TELNET,
            settings = ProtocolSpecificSettings.Ssh()
        )
        assertEquals(
            23,
            NexusWorkspaceViewModel.resolveEffectiveTelnetOrSshPort(
                conn = telnetWithDefaultSshSettings,
                tabProtocol = SessionProtocolKind.TELNET
            )
        )

        val telnetWithCustomPort = VaultConnection(
            name = "Console Server Telnet",
            host = "10.0.0.2",
            protocol = VaultProtocolType.TELNET,
            settings = ProtocolSpecificSettings.Ssh(port = 2323)
        )
        assertEquals(
            2323,
            NexusWorkspaceViewModel.resolveEffectiveTelnetOrSshPort(
                conn = telnetWithCustomPort,
                tabProtocol = SessionProtocolKind.TELNET
            )
        )

        val standardSsh = VaultConnection(
            name = "Prod SSH",
            host = "10.0.0.3",
            protocol = VaultProtocolType.SSH,
            settings = ProtocolSpecificSettings.Ssh()
        )
        assertEquals(
            22,
            NexusWorkspaceViewModel.resolveEffectiveTelnetOrSshPort(
                conn = standardSsh,
                tabProtocol = SessionProtocolKind.SSH
            )
        )

        // 3. Ad-hoc quick connect preserves utility & remote desktop subtypes without collapsing into ping
        val pingCand = AdHocConnectionSyntaxParser.parseOrNull("ping://1.1.1.1")
        assertNotNull(pingCand)
        assertEquals(SessionProtocolKind.NET_UTILITY, pingCand!!.protocol)
        assertEquals("UTILITY_PING", pingCand.parsedProtocolSubtype)

        val traceCand = AdHocConnectionSyntaxParser.parseOrNull("traceroute://8.8.8.8")
        assertNotNull(traceCand)
        assertEquals(SessionProtocolKind.NET_UTILITY, traceCand!!.protocol)
        assertEquals("UTILITY_TRACEROUTE", traceCand.parsedProtocolSubtype)

        val scanCand = AdHocConnectionSyntaxParser.parseOrNull("portscan://10.10.0.5")
        assertNotNull(scanCand)
        assertEquals(SessionProtocolKind.NET_UTILITY, scanCand!!.protocol)
        assertEquals("UTILITY_PORT_SCANNER", scanCand.parsedProtocolSubtype)

        val wolCand = AdHocConnectionSyntaxParser.parseOrNull("wol://00:11:22:33:44:55")
        assertNotNull(wolCand)
        assertEquals(SessionProtocolKind.NET_UTILITY, wolCand!!.protocol)
        assertEquals("UTILITY_WAKE_ON_LAN", wolCand.parsedProtocolSubtype)
        assertEquals("00:11:22:33:44:55", wolCand.parsedHost)

        val ardCand = AdHocConnectionSyntaxParser.parseOrNull("ard://mac-mini.local:5900")
        assertNotNull(ardCand)
        assertEquals(SessionProtocolKind.APPLE_SCREEN_SHARING, ardCand!!.protocol)
        assertEquals("APPLE_SCREEN_SHARING", ardCand.parsedProtocolSubtype)

        val wtsCand = AdHocConnectionSyntaxParser.parseOrNull("wts://rds-farm.corp.local")
        assertNotNull(wtsCand)
        assertEquals(SessionProtocolKind.RDP, wtsCand!!.protocol)
        assertEquals("WINDOWS_TERMINAL_SERVICES", wtsCand.parsedProtocolSubtype)

        val telnetCand = AdHocConnectionSyntaxParser.parseOrNull("telnet://switch.lab.local")
        assertNotNull(telnetCand)
        assertEquals(SessionProtocolKind.TELNET, telnetCand!!.protocol)
        assertEquals("TELNET", telnetCand.parsedProtocolSubtype)

        // 4. Chmod parser handles both octal and symbolic 9/10-char UNIX permission strings
        assertEquals(493, parseChmodPermissionsToOctal("755"))
        assertEquals(493, parseChmodPermissionsToOctal("0755"))
        assertEquals(493, parseChmodPermissionsToOctal("rwxr-xr-x"))
        assertEquals(493, parseChmodPermissionsToOctal("-rwxr-xr-x"))
        assertEquals(493, parseChmodPermissionsToOctal("drwxr-xr-x"))
        assertEquals(420, parseChmodPermissionsToOctal("644"))
        assertEquals(420, parseChmodPermissionsToOctal("-rw-r--r--"))
        assertEquals(384, parseChmodPermissionsToOctal("600"))
        assertNull(parseChmodPermissionsToOctal("999"))
        assertNull(parseChmodPermissionsToOctal("not-a-mode"))
    }

    @Test
    fun explicitFtpsClientNegotiatesAuthTlsPbszProtPAndTransfersOverTlsPassiveDataSockets() {
        val inMemoryFiles = ConcurrentHashMap<String, ByteArray>()
        inMemoryFiles["/readme.txt"] = "Hello over Explicit FTPS!\n".toByteArray(StandardCharsets.UTF_8)
        val inMemoryDirs = Collections.synchronizedSet(mutableSetOf("/", "/logs"))
        val recordedCommands = CopyOnWriteArrayList<String>()
        val controlUpgradedToTls = AtomicReference(false)
        val dataSocketsHandshakeCount = java.util.concurrent.atomic.AtomicInteger(0)

        ServerSocket(0, 50, loopbackAddr).use { controlListener ->
            controlListener.soTimeout = 8_000
            val serverThread = Thread {
                controlListener.accept().use { plainSocket ->
                    plainSocket.soTimeout = 8_000
                    var activeSocket: Socket = plainSocket
                    var reader = BufferedReader(InputStreamReader(activeSocket.getInputStream(), StandardCharsets.UTF_8))
                    var out = BufferedOutputStream(activeSocket.getOutputStream())

                    fun sendReply(line: String) {
                        out.write("$line\r\n".toByteArray(StandardCharsets.UTF_8))
                        out.flush()
                    }

                    var pendingDataServer: ServerSocket? = null
                    var renameFromPath: String? = null
                    var cwd = "/"

                    sendReply("220 NexusTS Explicit FTPS Test Server Ready")

                    while (true) {
                        val rawLine = reader.readLine() ?: break
                        recordedCommands.add(rawLine)
                        val verb = rawLine.substringBefore(' ').uppercase()
                        val arg = rawLine.substringAfter(' ', "").trim()

                        when (verb) {
                            "AUTH" -> {
                                sendReply("234 Proceed with TLSv1.3/TLSv1.2 negotiation")
                                val sslSock = (serverSslContext.socketFactory.createSocket(
                                    plainSocket,
                                    "127.0.0.1",
                                    plainSocket.port,
                                    true
                                ) as SSLSocket).apply {
                                    useClientMode = false
                                    startHandshake()
                                }
                                controlUpgradedToTls.set(true)
                                activeSocket = sslSock
                                reader = BufferedReader(InputStreamReader(sslSock.inputStream, StandardCharsets.UTF_8))
                                out = BufferedOutputStream(sslSock.outputStream)
                            }
                            "USER" -> sendReply("331 User $arg OK, password required")
                            "PASS" -> sendReply("230 User authenticated over TLS")
                            "PBSZ" -> sendReply("200 PBSZ=0 successful")
                            "PROT" -> sendReply("200 Protection level set to $arg")
                            "TYPE" -> sendReply("200 Type set to $arg")
                            "FEAT" -> {
                                out.write("211-Features:\r\n UTF8\r\n MLSD\r\n EPSV\r\n PBSZ\r\n PROT\r\n211 End\r\n".toByteArray(StandardCharsets.UTF_8))
                                out.flush()
                            }
                            "OPTS" -> sendReply("200 UTF8 mode enabled")
                            "PWD", "XPWD" -> sendReply("257 \"$cwd\" is current directory")
                            "CWD" -> {
                                val target = if (arg.startsWith("/")) arg else "$cwd/$arg".replace("//", "/")
                                if (target in inMemoryDirs) {
                                    cwd = target
                                    sendReply("250 CWD successful")
                                } else {
                                    sendReply("550 Directory not found")
                                }
                            }
                            "EPSV" -> {
                                pendingDataServer?.close()
                                val ds = ServerSocket(0, 10, loopbackAddr).apply { soTimeout = 8_000 }
                                pendingDataServer = ds
                                sendReply("229 Entering Extended Passive Mode (|||${ds.localPort}|)")
                            }
                            "MLSD" -> {
                                val ds = pendingDataServer ?: error("EPSV not opened before MLSD")
                                pendingDataServer = null
                                sendReply("150 Opening TLS passive data connection for MLSD")
                                ds.use { listener ->
                                    listener.accept().use { rawData ->
                                        val tlsData = (serverSslContext.socketFactory.createSocket(
                                            rawData,
                                            "127.0.0.1",
                                            rawData.port,
                                            true
                                        ) as SSLSocket).apply {
                                            useClientMode = false
                                            startHandshake()
                                        }
                                        dataSocketsHandshakeCount.incrementAndGet()
                                        val listing = buildString {
                                            val prefix = if (cwd == "/") "/" else "$cwd/"
                                            inMemoryDirs.filter { it != cwd && it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                                                .forEach { dir ->
                                                    val name = dir.substringAfterLast('/')
                                                    append("type=dir;UNIX.mode=0755;modify=20261116010000; $name\r\n")
                                                }
                                            inMemoryFiles.keys.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                                                .forEach { filePath ->
                                                    val name = filePath.substringAfterLast('/')
                                                    val sz = inMemoryFiles[filePath]?.size ?: 0
                                                    append("type=file;size=$sz;UNIX.mode=0644;modify=20261116010500; $name\r\n")
                                                }
                                        }
                                        tlsData.outputStream.write(listing.toByteArray(StandardCharsets.UTF_8))
                                        tlsData.outputStream.flush()
                                        tlsData.close()
                                    }
                                }
                                sendReply("226 MLSD complete")
                            }
                            "STOR" -> {
                                val ds = pendingDataServer ?: error("EPSV not opened before STOR")
                                pendingDataServer = null
                                val path = if (arg.startsWith("/")) arg else "${cwd.trimEnd('/')}/$arg"
                                sendReply("150 Opening TLS data channel for STOR $path")
                                ds.use { listener ->
                                    listener.accept().use { rawData ->
                                        val tlsData = (serverSslContext.socketFactory.createSocket(
                                            rawData,
                                            "127.0.0.1",
                                            rawData.port,
                                            true
                                        ) as SSLSocket).apply {
                                            useClientMode = false
                                            startHandshake()
                                        }
                                        dataSocketsHandshakeCount.incrementAndGet()
                                        inMemoryFiles[path] = tlsData.inputStream.readBytes()
                                        tlsData.close()
                                    }
                                }
                                sendReply("226 Transfer complete")
                            }
                            "RETR" -> {
                                val ds = pendingDataServer ?: error("EPSV not opened before RETR")
                                pendingDataServer = null
                                val path = if (arg.startsWith("/")) arg else "${cwd.trimEnd('/')}/$arg"
                                val payload = inMemoryFiles[path]
                                if (payload == null) {
                                    ds.close()
                                    sendReply("550 File not found")
                                } else {
                                    sendReply("150 Opening TLS data channel for RETR $path")
                                    ds.use { listener ->
                                        listener.accept().use { rawData ->
                                            val tlsData = (serverSslContext.socketFactory.createSocket(
                                                rawData,
                                                "127.0.0.1",
                                                rawData.port,
                                                true
                                            ) as SSLSocket).apply {
                                                useClientMode = false
                                                startHandshake()
                                            }
                                            dataSocketsHandshakeCount.incrementAndGet()
                                            tlsData.outputStream.write(payload)
                                            tlsData.outputStream.flush()
                                            tlsData.close()
                                        }
                                    }
                                    sendReply("226 Transfer complete")
                                }
                            }
                            "MKD", "XMKD" -> {
                                val path = if (arg.startsWith("/")) arg else "${cwd.trimEnd('/')}/$arg"
                                inMemoryDirs.add(path)
                                sendReply("257 \"$path\" created")
                            }
                            "RNFR" -> {
                                renameFromPath = arg
                                sendReply("350 Ready for RNTO")
                            }
                            "RNTO" -> {
                                val src = renameFromPath
                                renameFromPath = null
                                if (src != null && inMemoryFiles.containsKey(src)) {
                                    inMemoryFiles[arg] = inMemoryFiles.remove(src)!!
                                    sendReply("250 Rename successful")
                                } else {
                                    sendReply("550 Rename failed")
                                }
                            }
                            "SITE" -> sendReply("200 SITE $arg applied")
                            "DELE" -> {
                                inMemoryFiles.remove(arg)
                                sendReply("250 Deleted $arg")
                            }
                            "QUIT" -> {
                                sendReply("221 Goodbye")
                                break
                            }
                            else -> sendReply("502 Command not implemented")
                        }
                    }
                    pendingDataServer?.close()
                }
            }.apply {
                isDaemon = true
                start()
            }

            JvmFtpFtpsTransferClient(
                host = "127.0.0.1",
                port = controlListener.localPort,
                username = "ops",
                password = "s3cr3t!",
                securityMode = FtpSecurityMode.EXPLICIT_FTPS,
                allowInsecureTls = true
            ).use { client ->
                client.connect()
                assertTrue(client.isConnected)
                assertEquals("/", client.pwd())

                val rootEntries = client.listDirectory("/")
                assertEquals(2, rootEntries.size)
                assertTrue(rootEntries.any { it.name == "logs" && it.isDirectory })
                assertTrue(rootEntries.any { it.name == "readme.txt" && !it.isDirectory })

                assertTrue(client.mkdir("/releases"))
                val uploadedPayload = ByteArray(4096) { idx -> ((idx * 37) and 0xFF).toByte() }
                assertTrue(client.uploadFile("/releases/app-v0.8.0.bin", uploadedPayload))

                val downloadedPayload = client.downloadFile("/releases/app-v0.8.0.bin")
                assertArrayEquals(uploadedPayload, downloadedPayload)

                assertTrue(client.chmod("/releases/app-v0.8.0.bin", 493)) // 0755
                assertTrue(client.rename("/releases/app-v0.8.0.bin", "/releases/app-signed.bin"))
                assertTrue(client.remove("/releases/app-signed.bin", isDirectory = false))
            }

            serverThread.join(5_000L)
        }

        assertTrue("Control channel must upgrade via AUTH TLS", controlUpgradedToTls.get())
        assertTrue("Passive EPSV data channels must complete TLS handshakes", dataSocketsHandshakeCount.get() >= 3)
        assertTrue(recordedCommands.any { it.equals("AUTH TLS", ignoreCase = true) })
        assertTrue(recordedCommands.any { it.equals("PBSZ 0", ignoreCase = true) })
        assertTrue(recordedCommands.any { it.equals("PROT P", ignoreCase = true) })
        assertTrue(recordedCommands.any { it.equals("SITE CHMOD 0755 /releases/app-v0.8.0.bin", ignoreCase = true) })
    }

    @Test
    fun kubernetesExecClientStreamsV4ChannelFramesOverSelfSignedTlsWebSocket() {
        val observedPathAndQuery = AtomicReference("")
        val observedAuthHeader = AtomicReference("")
        val observedSubprotocol = AtomicReference("")
        val observedResizeJson = AtomicReference("")
        val observedStdinText = AtomicReference("")
        val serverDone = CountDownLatch(1)

        (serverSslContext.serverSocketFactory.createServerSocket(0, 50, loopbackAddr) as SSLServerSocket).use { tlsListener ->
            tlsListener.soTimeout = 8_000
            val serverThread = Thread {
                try {
                    (tlsListener.accept() as SSLSocket).use { sock ->
                        sock.soTimeout = 8_000
                        sock.useClientMode = false
                        sock.startHandshake()

                        val input = sock.inputStream
                        val output = sock.outputStream

                        // Read HTTP/1.1 Upgrade header block byte-by-byte so no WebSocket frames are buffered past \r\n\r\n
                        val headerBytes = ByteArrayOutputStream()
                        var state = 0
                        while (true) {
                            val b = input.read()
                            if (b < 0) break
                            headerBytes.write(b)
                            state = when {
                                state == 0 && b == '\r'.code -> 1
                                state == 1 && b == '\n'.code -> 2
                                state == 2 && b == '\r'.code -> 3
                                state == 3 && b == '\n'.code -> 4
                                else -> 0
                            }
                            if (state == 4) break
                        }
                        val headerLines = headerBytes.toByteArray().toString(StandardCharsets.US_ASCII).split("\r\n")
                        val requestLine = headerLines.firstOrNull().orEmpty()
                        observedPathAndQuery.set(requestLine.split(' ').getOrElse(1) { "" })

                        var wsKey = ""
                        for (line in headerLines.drop(1)) {
                            val idx = line.indexOf(':')
                            if (idx <= 0) continue
                            val name = line.substring(0, idx).trim().lowercase()
                            val value = line.substring(idx + 1).trim()
                            when (name) {
                                "sec-websocket-key" -> wsKey = value
                                "authorization" -> observedAuthHeader.set(value)
                                "sec-websocket-protocol" -> observedSubprotocol.set(value)
                            }
                        }

                        val acceptHash = MessageDigest.getInstance("SHA-1").digest(
                            (wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII)
                        )
                        val acceptKey = Base64.getEncoder().encodeToString(acceptHash)
                        val handshakeResponse = buildString {
                            append("HTTP/1.1 101 Switching Protocols\r\n")
                            append("Upgrade: websocket\r\n")
                            append("Connection: Upgrade\r\n")
                            append("Sec-WebSocket-Accept: $acceptKey\r\n")
                            append("Sec-WebSocket-Protocol: v4.channel.k8s.io\r\n")
                            append("\r\n")
                        }
                        output.write(handshakeResponse.toByteArray(StandardCharsets.US_ASCII))
                        output.flush()

                        // Read 2 binary client frames: resize (0x04) and stdin (0x00)
                        repeat(2) {
                            val frame = readWebSocketFrame(input) ?: return@repeat
                            if (frame.first == 0x02 && frame.second.isNotEmpty()) {
                                val ch = frame.second[0].toInt() and 0xFF
                                val body = String(frame.second, 1, frame.second.size - 1, StandardCharsets.UTF_8)
                                when (ch) {
                                    4 -> observedResizeJson.set(body)
                                    0 -> observedStdinText.set(body)
                                }
                            }
                        }

                        // Emit stdout (0x01), stderr (0x02), and exit status JSON (0x03), then Close (0x88)
                        writeWebSocketBinaryFrame(
                            output,
                            byteArrayOf(0x01) + "Linux api-pod-7f9 6.6.12-gke\n".toByteArray(StandardCharsets.UTF_8)
                        )
                        writeWebSocketBinaryFrame(
                            output,
                            byteArrayOf(0x02) + "warn: debug trace active\n".toByteArray(StandardCharsets.UTF_8)
                        )
                        writeWebSocketBinaryFrame(
                            output,
                            byteArrayOf(0x03) + """{"metadata":{},"status":"Success"}""".toByteArray(StandardCharsets.UTF_8)
                        )
                        output.write(byteArrayOf(0x88.toByte(), 0x00))
                        output.flush()
                    }
                } finally {
                    serverDone.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }

            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()
            val statusRef = AtomicReference("")
            val closedLatch = CountDownLatch(1)

            val execClient = KubernetesExecClient(verifyTls = false)
            val session = execClient.openSession(
                clusterEndpoint = "https://127.0.0.1:${tlsListener.localPort}",
                namespace = "prod",
                podName = "api-pod-7f9",
                command = listOf("/bin/sh", "-c", "uname -a"),
                bearerToken = "k8s-sa-token-xyz",
                container = "app-sidecar",
                tty = true,
                onStdout = { stdoutBuf.append(it) },
                onStderr = { stderrBuf.append(it) },
                onStatus = { statusRef.set(it) },
                onClosed = { closedLatch.countDown() }
            )

            assertTrue("WebSocket v4.channel.k8s.io session must open", session.awaitOpen(5_000L))
            assertEquals("v4.channel.k8s.io", session.negotiatedSubprotocol)
            assertTrue(session.sendResize(132, 42))
            assertTrue(session.sendStdin("uname -a\n"))
            assertTrue("Session must receive close after status frame", closedLatch.await(5_000L, TimeUnit.MILLISECONDS))
            session.close()
            assertTrue(serverDone.await(5_000L, TimeUnit.MILLISECONDS))
            serverThread.join(3_000L)

            assertTrue(observedPathAndQuery.get().startsWith("/api/v1/namespaces/prod/pods/api-pod-7f9/exec?"))
            assertTrue(observedPathAndQuery.get().contains("container=app-sidecar"))
            assertEquals("Bearer k8s-sa-token-xyz", observedAuthHeader.get())
            assertEquals("v4.channel.k8s.io", observedSubprotocol.get())
            assertEquals("""{"Width":132,"Height":42}""", observedResizeJson.get())
            assertEquals("uname -a\n", observedStdinText.get())
            assertEquals("Linux api-pod-7f9 6.6.12-gke\n", stdoutBuf.toString())
            assertEquals("warn: debug trace active\n", stderrBuf.toString())
            assertTrue(statusRef.get().contains("\"Success\""))
        }
    }

    @Test
    fun proxmoxVeApiClientAuthenticatesTicketAndSendsCookiePlusCsrfTokenOverSelfSignedHttps() {
        val observedTicketForm = AtomicReference("")
        val observedInventoryCookie = AtomicReference("")
        val observedPowerCookie = AtomicReference("")
        val observedPowerCsrf = AtomicReference("")
        val observedPowerPath = AtomicReference("")

        (serverSslContext.serverSocketFactory.createServerSocket(0, 50, loopbackAddr) as SSLServerSocket).use { tlsListener ->
            tlsListener.soTimeout = 8_000
            val serverThread = Thread {
                // Handle 4 HTTPS calls:
                // 1) POST /access/ticket (for fetchInventory)
                // 2) GET /cluster/resources?type=vm
                // 3) POST /access/ticket (for controlVm)
                // 4) POST /nodes/pve-eu2/lxc/202/status/start
                repeat(4) {
                    val sock = runCatching { tlsListener.accept() as SSLSocket }.getOrNull() ?: return@repeat
                    sock.use { tlsSock ->
                        tlsSock.soTimeout = 8_000
                        tlsSock.useClientMode = false
                        tlsSock.startHandshake()

                        val req = readSimpleHttpRequest(tlsSock.inputStream) ?: return@use
                        val (method, path, headers, body) = req
                        val responseJson = when {
                            method == "POST" && path == "/api2/json/access/ticket" -> {
                                observedTicketForm.set(body)
                                """{"data":{"ticket":"PVE:root@pam:6700ABCD::sig","CSRFPreventionToken":"6700ABCD:csrf-token-999","username":"root@pam"}}"""
                            }
                            method == "GET" && path.startsWith("/api2/json/cluster/resources") -> {
                                observedInventoryCookie.set(headers["cookie"].orEmpty())
                                """{"data":[""" +
                                    """{"vmid":101,"name":"prod-db-01","node":"pve-eu1","status":"running","maxcpu":8,"maxmem":17179869184,"type":"qemu"},""" +
                                    """{"vmid":202,"name":"redis-cache","node":"pve-eu2","status":"stopped","maxcpu":4,"maxmem":4294967296,"type":"lxc"}""" +
                                    """]}"""
                            }
                            method == "POST" && path.endsWith("/status/start") -> {
                                observedPowerPath.set(path)
                                observedPowerCookie.set(headers["cookie"].orEmpty())
                                observedPowerCsrf.set(headers["csrfpreventiontoken"].orEmpty())
                                """{"data":"UPID:pve-eu2:00001A2B:00099C:start"}"""
                            }
                            else -> """{"data":null}"""
                        }

                        val bodyBytes = responseJson.toByteArray(StandardCharsets.UTF_8)
                        val httpResp = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: application/json\r\n")
                            append("Content-Length: ${bodyBytes.size}\r\n")
                            append("Connection: close\r\n")
                            append("\r\n")
                        }.toByteArray(StandardCharsets.US_ASCII)
                        tlsSock.outputStream.write(httpResp)
                        tlsSock.outputStream.write(bodyBytes)
                        tlsSock.outputStream.flush()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            val pveClient = ProxmoxVeApiClient() // default allowSelfSignedTls = true
            assertTrue(pveClient.allowSelfSignedTls)

            val settings = ProtocolSpecificSettings.CloudVirtualization(
                apiEndpointUrl = "https://127.0.0.1:${tlsListener.localPort}",
                realmOrDomain = "pam"
            )
            val secret = ResolvedSessionSecret(
                username = "root",
                password = "pve-secret-password"
            )

            val inventory = pveClient.fetchInventory(
                host = "127.0.0.1",
                settings = settings,
                secret = secret
            )
            assertEquals(2, inventory.size)
            assertEquals("qemu/101", inventory[0].id)
            assertEquals("prod-db-01", inventory[0].name)
            assertEquals("RUNNING", inventory[0].powerState)
            assertEquals(VaultProtocolType.VNC, inventory[0].preferredConsoleProtocol)

            assertEquals("lxc/202", inventory[1].id)
            assertEquals("redis-cache", inventory[1].name)
            assertEquals("STOPPED", inventory[1].powerState)
            assertEquals(VaultProtocolType.SSH, inventory[1].preferredConsoleProtocol)

            val upid = pveClient.controlVm(
                host = "127.0.0.1",
                settings = settings,
                secret = secret,
                resource = inventory[1],
                action = VmPowerAction.START
            )
            assertEquals("UPID:pve-eu2:00001A2B:00099C:start", upid)

            serverThread.join(5_000L)
        }

        assertTrue(observedTicketForm.get().contains("username=root%40pam"))
        assertTrue(observedTicketForm.get().contains("password=pve-secret-password"))
        assertEquals("PVEAuthCookie=PVE:root@pam:6700ABCD::sig", observedInventoryCookie.get())
        assertEquals("/api2/json/nodes/pve-eu2/lxc/202/status/start", observedPowerPath.get())
        assertEquals("PVEAuthCookie=PVE:root@pam:6700ABCD::sig", observedPowerCookie.get())
        assertEquals("6700ABCD:csrf-token-999", observedPowerCsrf.get())
    }

    @Test
    fun statefulTelnetControllerHandlesSplitPacketIacNegotiationTtypeNawsAndCleanTerminalOutput() {
        val serverReceivedBytes = ByteArrayOutputStream()
        val initialNegotiationDone = CountDownLatch(1)
        val resizeAndInputReceived = CountDownLatch(1)

        ServerSocket(0, 50, loopbackAddr).use { listener ->
            listener.soTimeout = 8_000
            val serverThread = Thread {
                listener.accept().use { sock ->
                    sock.soTimeout = 8_000
                    sock.tcpNoDelay = true
                    val out = sock.getOutputStream()
                    val input = sock.getInputStream()

                    // Deliberately split IAC DO TTYPE (FF FD 18) and IAC DO NAWS (FF FD 1F) across tiny packets
                    out.write(byteArrayOf(0xFF.toByte()))
                    out.flush()
                    Thread.sleep(15)
                    out.write(byteArrayOf(0xFD.toByte(), 24))
                    out.flush()
                    Thread.sleep(15)
                    out.write(byteArrayOf(0xFF.toByte(), 0xFD.toByte()))
                    out.flush()
                    Thread.sleep(15)
                    out.write(byteArrayOf(31))
                    out.flush()

                    // Split subnegotiation IAC SB TTYPE SEND IAC SE (FF FA 18 01 FF F0) + clean prompt
                    Thread.sleep(20)
                    out.write(byteArrayOf(0xFF.toByte(), 0xFA.toByte(), 24))
                    out.flush()
                    Thread.sleep(15)
                    out.write(byteArrayOf(1, 0xFF.toByte(), 0xF0.toByte()))
                    out.write("CoreSwitch-01 login: ".toByteArray(StandardCharsets.UTF_8))
                    out.flush()

                    val buf = ByteArray(256)
                    val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
                    while (System.nanoTime() < deadlineNs) {
                        val n = runCatching { input.read(buf) }.getOrDefault(-1)
                        if (n <= 0) break
                        var finished = false
                        synchronized(serverReceivedBytes) {
                            serverReceivedBytes.write(buf, 0, n)
                            val current = serverReceivedBytes.toByteArray()
                            val expectedTtype = "xterm-256color".toByteArray(StandardCharsets.US_ASCII)
                            if (containsSubsequence(current, expectedTtype)) {
                                initialNegotiationDone.countDown()
                            }
                            // Wait for live NAWS 132x43 (FF FA 1F 00 84 00 2B FF F0) AND escaped 0xFF 0xFF user byte
                            val resizedNaws = byteArrayOf(
                                0xFF.toByte(), 0xFA.toByte(), 31,
                                0x00, 132.toByte(), 0x00, 43.toByte(),
                                0xFF.toByte(), 0xF0.toByte()
                            )
                            val escapedData = byteArrayOf('o'.code.toByte(), 0xFF.toByte(), 0xFF.toByte(), '\n'.code.toByte())
                            if (containsSubsequence(current, resizedNaws) && containsSubsequence(current, escapedData)) {
                                resizeAndInputReceived.countDown()
                                finished = true
                            }
                        }
                        if (finished) break
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            val controller = SshTerminalSessionController(
                title = "CoreSwitch Telnet",
                host = "127.0.0.1",
                port = listener.localPort,
                username = "admin",
                isLocalShell = false,
                isTelnet = true,
                terminalType = "xterm-256color"
            )
            try {
                assertTrue(
                    "Client must complete split-packet TTYPE + initial NAWS negotiation",
                    initialNegotiationDone.await(5_000L, TimeUnit.MILLISECONDS)
                )

                // Wait briefly for terminal buffer to render clean prompt text
                val promptDeadline = System.currentTimeMillis() + 3_000L
                while (System.currentTimeMillis() < promptDeadline &&
                    controller.viewport.value.visible_lines.none { it.contains("CoreSwitch-01 login:") }
                ) {
                    Thread.sleep(25)
                }

                val rendered = controller.viewport.value.visible_lines.joinToString("\n")
                assertTrue("Terminal viewport must contain clean server prompt", rendered.contains("CoreSwitch-01 login:"))
                assertFalse("Telnet IAC bytes must never leak into terminal text", rendered.contains('\u00FF'))

                // Trigger live PTY resize -> must emit updated NAWS subnegotiation (132x43)
                controller.resizePty(cols = 132, rows = 43)
                // Send user bytes containing 0xFF -> must be IAC-escaped as 0xFF 0xFF on wire
                controller.sendUserBytes(byteArrayOf('o'.code.toByte(), 0xFF.toByte(), '\n'.code.toByte()))

                assertTrue(
                    "Server must receive updated NAWS (132x43) and IAC-escaped 0xFF 0xFF payload",
                    resizeAndInputReceived.await(5_000L, TimeUnit.MILLISECONDS)
                )
            } finally {
                controller.close()
            }

            serverThread.join(3_000L)
        }

        val wireBytes = synchronized(serverReceivedBytes) { serverReceivedBytes.toByteArray() }
        // Verify IAC WILL TTYPE (FF FB 18) and IAC WILL NAWS (FF FB 1F)
        assertTrue(containsSubsequence(wireBytes, byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 24)))
        assertTrue(containsSubsequence(wireBytes, byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 31)))
        // Verify IAC SB TTYPE IS "xterm-256color" IAC SE
        val expectedTtypeSb = ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xFA.toByte(), 24, 0))
            write("xterm-256color".toByteArray(StandardCharsets.US_ASCII))
            write(byteArrayOf(0xFF.toByte(), 0xF0.toByte()))
        }.toByteArray()
        assertTrue(containsSubsequence(wireBytes, expectedTtypeSb))
    }

    private data class ParsedHttpReq(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readSimpleHttpRequest(input: InputStream): ParsedHttpReq? {
        val headerBuf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (headerBuf.size() == 0) null else break
            headerBuf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                else -> 0
            }
            if (state == 4) break
        }
        val lines = headerBuf.toByteArray().toString(StandardCharsets.US_ASCII).split("\r\n")
        val reqParts = lines.firstOrNull().orEmpty().split(' ')
        if (reqParts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val n = input.read(bodyBytes, offset, contentLength - offset)
            if (n <= 0) break
            offset += n
        }
        return ParsedHttpReq(
            method = reqParts[0].uppercase(),
            path = reqParts[1],
            headers = headers,
            body = String(bodyBytes, 0, offset, StandardCharsets.UTF_8)
        )
    }

    private fun readWebSocketFrame(input: InputStream): Pair<Int, ByteArray>? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        val dis = DataInputStream(input)
        if (len == 126L) {
            len = dis.readUnsignedShort().toLong()
        } else if (len == 127L) {
            len = dis.readLong()
        }
        val maskKey = ByteArray(4)
        if (masked) {
            dis.readFully(maskKey)
        }
        val payload = ByteArray(len.toInt())
        dis.readFully(payload)
        if (masked) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return opcode to payload
    }

    private fun writeWebSocketBinaryFrame(out: OutputStream, payload: ByteArray) {
        out.write(0x82) // FIN + binary frame
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size <= 65535 -> {
                out.write(126)
                out.write((payload.size ushr 8) and 0xFF)
                out.write(payload.size and 0xFF)
            }
            else -> error("Test payload exceeds 64KB")
        }
        out.write(payload)
        out.flush()
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    @Test
    fun hyperVEndpointSanitizerWsmanXmlAndPowerShellJsonParsersWork() {
        assertEquals(
            "",
            com.nexusts.app.protocols.vnc.cloud.CloudVirtualizationHubClient.sanitizedHyperVEndpoint(
                "https://localhost:8006",
                "hv01.corp.local"
            )
        )
        assertEquals(
            "",
            com.nexusts.app.protocols.vnc.cloud.CloudVirtualizationHubClient.sanitizedHyperVEndpoint(
                "https://hv01.corp.local",
                "hv01.corp.local"
            )
        )
        assertEquals(
            "http://hv01.corp.local:5985/wsman",
            com.nexusts.app.protocols.vnc.cloud.CloudVirtualizationHubClient.sanitizedHyperVEndpoint(
                "http://hv01.corp.local:5985/wsman",
                "hv01.corp.local"
            )
        )

        val wsmanXml = """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:p="http://schemas.microsoft.com/wbem/wsman/1/wmi/root/virtualization/v2/Msvm_ComputerSystem">
              <s:Body>
                <p:Msvm_ComputerSystem>
                  <p:Name>HV01</p:Name>
                  <p:ElementName>HV01</p:ElementName>
                  <p:Caption>Hosting Computer System</p:Caption>
                  <p:EnabledState>2</p:EnabledState>
                </p:Msvm_ComputerSystem>
                <p:Msvm_ComputerSystem>
                  <p:Name>11111111-2222-3333-4444-555555555555</p:Name>
                  <p:ElementName>Prod-SQL-01</p:ElementName>
                  <p:Caption>Virtual Machine</p:Caption>
                  <p:EnabledState>2</p:EnabledState>
                </p:Msvm_ComputerSystem>
                <p:Msvm_ComputerSystem>
                  <p:Name>66666666-7777-8888-9999-000000000000</p:Name>
                  <p:ElementName>Lab-DC-02</p:ElementName>
                  <p:Caption>Virtual Machine</p:Caption>
                  <p:EnabledState>32769</p:EnabledState>
                </p:Msvm_ComputerSystem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val parsedXml = com.nexusts.app.protocols.vnc.cloud.CloudVirtualizationHubClient.parseHyperVWsmanXml(
            wsmanXml,
            "hv01.corp.local"
        )
        assertEquals(2, parsedXml.size)
        assertEquals("11111111-2222-3333-4444-555555555555", parsedXml[0].id)
        assertEquals("Prod-SQL-01", parsedXml[0].name)
        assertEquals("RUNNING", parsedXml[0].powerState)
        assertEquals("SAVED", parsedXml[1].powerState)

        val singlePsJson = """{"Id":"aaaa1111-bbbb-cccc-dddd-eeeeffff0001","Name":"Single-VM","State":"Running","CpuCount":4,"MemAssignedMB":8192,"MemStartupMB":4096}"""
        val parsedSingle = com.nexusts.app.protocols.vnc.cloud.CloudVirtualizationHubClient.parseHyperVPowerShellJson(
            singlePsJson,
            "hv01.corp.local"
        )
        assertEquals(1, parsedSingle.size)
        assertEquals("Single-VM", parsedSingle[0].name)
        assertEquals("RUNNING", parsedSingle[0].powerState)

        val arrayPsJson = """[{"Id":"1","Name":"VM-A","State":"Off","CpuCount":2,"MemAssignedMB":0,"MemStartupMB":2048},{"Id":"2","Name":"VM-B","State":"Saved","CpuCount":8,"MemAssignedMB":16384,"MemStartupMB":16384}]"""
        val parsedArray = com.nexusts.app.protocols.vnc.cloud.CloudVirtualizationHubClient.parseHyperVPowerShellJson(
            arrayPsJson,
            "hv01.corp.local"
        )
        assertEquals(2, parsedArray.size)
        assertEquals("STOPPED", parsedArray[0].powerState)
        assertEquals("SAVED", parsedArray[1].powerState)
    }

    @Test
    fun esxiStandaloneVimSoapSdkFallbackDiscoversVmsAndExecutesPowerOnTask() {
        val observedSoapBodies = CopyOnWriteArrayList<String>()
        val observedSessionCookies = CopyOnWriteArrayList<String>()

        (serverSslContext.serverSocketFactory.createServerSocket(0, 50, loopbackAddr) as SSLServerSocket).use { tlsListener ->
            tlsListener.soTimeout = 8_000
            val serverThread = Thread {
                // Sequence across listInventory + executePowerOrSessionAction:
                // 1) POST /api/session -> 404, 2) POST /rest/com/vmware/cis/session -> 404,
                // 3) RetrieveServiceContent, 4) Login (Set-Cookie), 5) CreateContainerView, 6) RetrievePropertiesEx,
                // 7) POST /api/session -> 404, 8) POST /rest/com/vmware/cis/session -> 404,
                // 9) RetrieveServiceContent, 10) Login, 11) PowerOnVM_Task
                repeat(11) {
                    val sock = runCatching { tlsListener.accept() as SSLSocket }.getOrNull() ?: return@repeat
                    sock.use { tlsSock ->
                        tlsSock.soTimeout = 8_000
                        tlsSock.useClientMode = false
                        tlsSock.startHandshake()

                        val req = readSimpleHttpRequest(tlsSock.inputStream) ?: return@use
                        val (_, path, headers, body) = req
                        var statusLine = "HTTP/1.1 200 OK"
                        var contentType = "text/xml; charset=utf-8"
                        var setCookieHeader = ""
                        val respBody = when {
                            path == "/api/session" || path == "/rest/com/vmware/cis/session" -> {
                                statusLine = "HTTP/1.1 404 Not Found"
                                contentType = "application/json"
                                """{"error":"Standalone ESXi does not expose vCenter REST"}"""
                            }
                            path == "/sdk" && body.contains("<RetrieveServiceContent") -> {
                                observedSoapBodies += "RetrieveServiceContent"
                                """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns="urn:vim25">
                                  <soapenv:Body>
                                    <RetrieveServiceContentResponse>
                                      <returnval>
                                        <rootFolder type="Folder">ha-folder-root</rootFolder>
                                        <propertyCollector type="PropertyCollector">ha-property-collector</propertyCollector>
                                        <viewManager type="ViewManager">ViewManager</viewManager>
                                        <sessionManager type="SessionManager">ha-sessionmgr</sessionManager>
                                      </returnval>
                                    </RetrieveServiceContentResponse>
                                  </soapenv:Body>
                                </soapenv:Envelope>
                                """.trimIndent()
                            }
                            path == "/sdk" && body.contains("<Login") -> {
                                observedSoapBodies += "Login"
                                setCookieHeader = "Set-Cookie: vmware_soap_session=\"esxi-soap-session-777\"; Path=/; HttpOnly; Secure\r\n"
                                """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns="urn:vim25">
                                  <soapenv:Body><LoginResponse><returnval><key>esxi-777</key></returnval></LoginResponse></soapenv:Body>
                                </soapenv:Envelope>
                                """.trimIndent()
                            }
                            path == "/sdk" && body.contains("<CreateContainerView") -> {
                                observedSoapBodies += "CreateContainerView"
                                observedSessionCookies += headers["cookie"].orEmpty()
                                """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns="urn:vim25">
                                  <soapenv:Body><CreateContainerViewResponse><returnval type="ContainerView">session[777]view</returnval></CreateContainerViewResponse></soapenv:Body>
                                </soapenv:Envelope>
                                """.trimIndent()
                            }
                            path == "/sdk" && body.contains("<RetrievePropertiesEx") -> {
                                observedSoapBodies += "RetrievePropertiesEx"
                                observedSessionCookies += headers["cookie"].orEmpty()
                                """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns="urn:vim25">
                                  <soapenv:Body>
                                    <RetrievePropertiesExResponse>
                                      <returnval>
                                        <objects>
                                          <obj type="VirtualMachine">14</obj>
                                          <propSet><name>name</name><val>win2022-dc01</val></propSet>
                                          <propSet><name>runtime.powerState</name><val>poweredOn</val></propSet>
                                          <propSet><name>config.hardware.numCPU</name><val>4</val></propSet>
                                          <propSet><name>config.hardware.memoryMB</name><val>8192</val></propSet>
                                          <propSet><name>guest.ipAddress</name><val>10.20.0.10</val></propSet>
                                          <propSet><name>guest.guestFullName</name><val>Microsoft Windows Server 2022 (64-bit)</val></propSet>
                                        </objects>
                                        <objects>
                                          <obj type="VirtualMachine">15</obj>
                                          <propSet><name>name</name><val>vyos-edge</val></propSet>
                                          <propSet><name>runtime.powerState</name><val>poweredOff</val></propSet>
                                          <propSet><name>config.hardware.numCPU</name><val>2</val></propSet>
                                          <propSet><name>config.hardware.memoryMB</name><val>2048</val></propSet>
                                          <propSet><name>guest.ipAddress</name><val></val></propSet>
                                          <propSet><name>guest.guestFullName</name><val>Other 6.x Linux (64-bit)</val></propSet>
                                        </objects>
                                      </returnval>
                                    </RetrievePropertiesExResponse>
                                  </soapenv:Body>
                                </soapenv:Envelope>
                                """.trimIndent()
                            }
                            path == "/sdk" && body.contains("<PowerOnVM_Task") -> {
                                observedSoapBodies += "PowerOnVM_Task"
                                observedSessionCookies += headers["cookie"].orEmpty()
                                """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns="urn:vim25">
                                  <soapenv:Body><PowerOnVM_TaskResponse><returnval type="Task">haTask-15-vim.VirtualMachine.powerOn</returnval></PowerOnVM_TaskResponse></soapenv:Body>
                                </soapenv:Envelope>
                                """.trimIndent()
                            }
                            else -> "<soapenv:Envelope/>"
                        }
                        val bodyBytes = respBody.toByteArray(StandardCharsets.UTF_8)
                        val hdr = (
                            "$statusLine\r\n" +
                                "Content-Type: $contentType\r\n" +
                                setCookieHeader +
                                "Content-Length: ${bodyBytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(StandardCharsets.US_ASCII)
                        tlsSock.outputStream.write(hdr)
                        tlsSock.outputStream.write(bodyBytes)
                        tlsSock.outputStream.flush()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            val baseEndpoint = "https://127.0.0.1:${tlsListener.localPort}"
            val settings = ProtocolSpecificSettings.CloudVirtualization(
                apiEndpointUrl = baseEndpoint,
                verifyTls = false
            )
            val secret = ResolvedSessionSecret(username = "root", password = "esxi-password!")

            val vms = runBlocking {
                CloudVirtualizationHubClient.listInventory(
                    protocol = VaultProtocolType.VMWARE_VSPHERE,
                    endpointHost = "127.0.0.1",
                    settings = settings,
                    secret = secret,
                    allowSelfSignedTls = true
                ).getOrThrow()
            }
            assertEquals(2, vms.size)
            assertEquals("14", vms[0].id)
            assertEquals("win2022-dc01", vms[0].name)
            assertEquals("RUNNING", vms[0].powerState)
            assertEquals("10.20.0.10", vms[0].addressOrSessionHost)
            assertEquals(VaultProtocolType.RDP, vms[0].preferredConsoleProtocol)

            assertEquals("15", vms[1].id)
            assertEquals("vyos-edge", vms[1].name)
            assertEquals("STOPPED", vms[1].powerState)
            assertEquals("$baseEndpoint/ui", vms[1].addressOrSessionHost)
            assertEquals(VaultProtocolType.WEB_PAGE, vms[1].preferredConsoleProtocol)

            val powerStatus = runBlocking {
                CloudVirtualizationHubClient.executePowerOrSessionAction(
                    protocol = VaultProtocolType.VMWARE_VSPHERE,
                    endpointHost = "127.0.0.1",
                    settings = settings,
                    secret = secret,
                    resource = vms[1],
                    action = VmPowerAction.START,
                    allowSelfSignedTls = true
                ).getOrThrow()
            }
            assertTrue(powerStatus.contains("PowerOnVM_Task"))
            serverThread.join(5_000L)
        }

        assertTrue(observedSoapBodies.containsAll(listOf("RetrieveServiceContent", "Login", "CreateContainerView", "RetrievePropertiesEx", "PowerOnVM_Task")))
        assertTrue(observedSessionCookies.all { it.contains("vmware_soap_session=\"esxi-soap-session-777\"") })
    }

    @Test
    fun proxmoxVeApiTokenHeaderPort8006NormalizationAndWorkloadEndpointResolutionWork() {
        val observedAuthHeaders = CopyOnWriteArrayList<String>()
        val observedPaths = CopyOnWriteArrayList<String>()

        (serverSslContext.serverSocketFactory.createServerSocket(0, 50, loopbackAddr) as SSLServerSocket).use { tlsListener ->
            tlsListener.soTimeout = 8_000
            val serverThread = Thread {
                repeat(2) {
                    val sock = runCatching { tlsListener.accept() as SSLSocket }.getOrNull() ?: return@repeat
                    sock.use { tlsSock ->
                        tlsSock.soTimeout = 8_000
                        tlsSock.useClientMode = false
                        tlsSock.startHandshake()
                        val req = readSimpleHttpRequest(tlsSock.inputStream) ?: return@use
                        observedPaths += "${req.method} ${req.path}"
                        observedAuthHeaders += req.headers["authorization"].orEmpty()
                        val json = if (req.method == "GET") {
                            """{"data":[{"vmid":301,"name":"k3s-worker","node":"pve-01","status":"running","maxcpu":4,"maxmem":8589934592,"type":"qemu"}]}"""
                        } else {
                            """{"data":"UPID:pve-01:0000ABCD:reboot"}"""
                        }
                        val bodyBytes = json.toByteArray(StandardCharsets.UTF_8)
                        val hdr = (
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                            ).toByteArray(StandardCharsets.US_ASCII)
                        tlsSock.outputStream.write(hdr)
                        tlsSock.outputStream.write(bodyBytes)
                        tlsSock.outputStream.flush()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            val client = ProxmoxVeApiClient(allowSelfSignedTls = true)
            val settings = ProtocolSpecificSettings.CloudVirtualization(
                apiEndpointUrl = "https://127.0.0.1:${tlsListener.localPort}",
                realmOrDomain = "pam"
            )
            // Unqualified user!tokenId -> must automatically qualify realm ("root@pam!nexus-ci=tok-secret-123") without hitting /access/ticket
            val tokenSecret = ResolvedSessionSecret(username = "root!nexus-ci", password = "tok-secret-123")
            val vms = client.fetchInventory("127.0.0.1", settings, tokenSecret)
            assertEquals(1, vms.size)
            assertEquals("qemu/301", vms[0].id)
            val upid = client.controlVm("127.0.0.1", settings, tokenSecret, vms[0], VmPowerAction.REBOOT)
            assertEquals("UPID:pve-01:0000ABCD:reboot", upid)
            serverThread.join(5_000L)
        }

        assertEquals(2, observedPaths.size)
        assertTrue(observedPaths.none { it.contains("/access/ticket") })
        assertTrue(observedAuthHeaders.all { it == "PVEAPIToken=root@pam!nexus-ci=tok-secret-123" })

        // Verify QuickConnect AWS/Azure & Proxmox/Docker/K8s/IPv6 Web endpoint normalization
        val ec2Quick = AdHocConnectionSyntaxParser.parseOrNull("ec2://eu-west-1")
        assertNotNull(ec2Quick)
        assertEquals(SessionProtocolKind.AWS_EC2, ec2Quick!!.protocol)
        val azureQuick = AdHocConnectionSyntaxParser.parseOrNull("azure://sub-guid-001")
        assertNotNull(azureQuick)
        assertEquals(SessionProtocolKind.AZURE_VM, azureQuick!!.protocol)

        assertEquals("https://[2001:db8::42]:8443", normalizeWebSessionUrl("", adHocHost = "2001:db8::42", adHocPort = 8443))
        assertEquals("https://[2001:db8::1]", normalizeWebSessionUrl("2001:db8::1"))
        assertEquals(
            "https://k8s.prod.internal:6443",
            resolveWorkloadBaseEndpoint(
                endpointHost = "k8s.prod.internal:6443",
                clusterOrSocketEndpoint = "unix:///var/run/docker.sock",
                isKubernetes = true
            )
        )
        assertEquals(
            "http://10.0.0.5:2375",
            resolveWorkloadBaseEndpoint(
                endpointHost = "",
                clusterOrSocketEndpoint = "tcp://10.0.0.5:2375",
                isKubernetes = false
            )
        )
        assertEquals(
            "https://10.0.0.5:2376",
            resolveWorkloadBaseEndpoint(
                endpointHost = "tcp://10.0.0.5:2376",
                clusterOrSocketEndpoint = "unix:///var/run/docker.sock",
                isKubernetes = false
            )
        )
    }

    @Test
    fun awsEc2SigV4LoopbackVerifiesSignedHeadersSessionTokenWindowsRdpAndXmlFaultParsing() {
        val observedAuth = AtomicReference("")
        val observedStsToken = AtomicReference("")
        val observedBodies = CopyOnWriteArrayList<String>()

        ServerSocket(0, 50, loopbackAddr).use { listener ->
            listener.soTimeout = 8_000
            val serverThread = Thread {
                repeat(3) { callIdx ->
                    val sock = runCatching { listener.accept() }.getOrNull() ?: return@repeat
                    sock.use { s ->
                        s.soTimeout = 8_000
                        val req = readSimpleHttpRequest(s.inputStream) ?: return@use
                        observedAuth.set(req.headers["authorization"].orEmpty())
                        observedStsToken.set(req.headers["x-amz-security-token"].orEmpty())
                        observedBodies += req.body

                        val (status, xml) = when (callIdx) {
                            0 -> "HTTP/1.1 200 OK" to """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <DescribeInstancesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
                                  <reservationSet>
                                    <item>
                                      <instancesSet>
                                        <item>
                                          <instanceId>i-0win2022abcdef01</instanceId>
                                          <instanceState><code>80</code><name>stopped</name></instanceState>
                                          <instanceType>m6i.xlarge</instanceType>
                                          <privateIpAddress>172.31.10.44</privateIpAddress>
                                          <platformDetails>Windows</platformDetails>
                                          <tagSet><item><key>Name</key><value>prod-ad-dc01</value></item></tagSet>
                                        </item>
                                      </instancesSet>
                                    </item>
                                  </reservationSet>
                                </DescribeInstancesResponse>
                            """.trimIndent()
                            1 -> "HTTP/1.1 200 OK" to """
                                <StartInstancesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
                                  <instancesSet><item><instanceId>i-0win2022abcdef01</instanceId></item></instancesSet>
                                </StartInstancesResponse>
                            """.trimIndent()
                            else -> "HTTP/1.1 403 Forbidden" to """
                                <Response><Errors><Error><Code>UnauthorizedOperation</Code><Message>You are not authorized to perform RebootInstances.</Message></Error></Errors></Response>
                            """.trimIndent()
                        }
                        val bytes = xml.toByteArray(StandardCharsets.UTF_8)
                        val hdr = "$status\r\nContent-Type: text/xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            .toByteArray(StandardCharsets.US_ASCII)
                        s.outputStream.write(hdr)
                        s.outputStream.write(bytes)
                        s.outputStream.flush()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            val settings = ProtocolSpecificSettings.CloudVirtualization(
                apiEndpointUrl = "http://127.0.0.1:${listener.localPort}",
                regionOrCluster = "eu-central-1",
                verifyTls = false
            )
            val secret = ResolvedSessionSecret(
                username = "AKIAIOSFODNN7EXAMPLE",
                password = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                keyPassphrase = "IQoJb3JpZ2luX2VjEJr//////////wEaCXVzLWVhc3QtMSJHMEUC"
            )

            val instances = runBlocking {
                CloudVirtualizationHubClient.listInventory(
                    protocol = VaultProtocolType.AWS_EC2,
                    endpointHost = "",
                    settings = settings,
                    secret = secret,
                    allowSelfSignedTls = true
                ).getOrThrow()
            }
            assertEquals(1, instances.size)
            assertEquals("i-0win2022abcdef01", instances[0].id)
            assertEquals("prod-ad-dc01", instances[0].name)
            assertEquals("STOPPED", instances[0].powerState)
            assertEquals(VaultProtocolType.RDP, instances[0].preferredConsoleProtocol)

            val startRes = runBlocking {
                CloudVirtualizationHubClient.executePowerOrSessionAction(
                    protocol = VaultProtocolType.AWS_EC2,
                    endpointHost = "",
                    settings = settings,
                    secret = secret,
                    resource = instances[0],
                    action = VmPowerAction.START,
                    allowSelfSignedTls = true
                ).getOrThrow()
            }
            assertTrue(startRes.contains("StartInstances"))

            val rebootFailure = runBlocking {
                CloudVirtualizationHubClient.executePowerOrSessionAction(
                    protocol = VaultProtocolType.AWS_EC2,
                    endpointHost = "",
                    settings = settings,
                    secret = secret,
                    resource = instances[0],
                    action = VmPowerAction.REBOOT,
                    allowSelfSignedTls = true
                ).exceptionOrNull()
            }
            assertNotNull(rebootFailure)
            assertTrue(rebootFailure!!.message.orEmpty().contains("UnauthorizedOperation"))
            assertTrue(rebootFailure.message.orEmpty().contains("You are not authorized"))

            serverThread.join(5_000L)
        }

        assertTrue(observedAuth.get().startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/"))
        assertTrue(observedAuth.get().contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token"))
        assertEquals("IQoJb3JpZ2luX2VjEJr//////////wEaCXVzLWVhc3QtMSJHMEUC", observedStsToken.get())
        assertTrue(observedBodies.any { it.contains("Action=StartInstances&InstanceId.1=i-0win2022abcdef01") })
    }

    @Test
    fun azureArmLoopbackDeduplicatesBearerFallsBackToStatusOnlyAndResolvesNicPublicIp() {
        val observedAuthHeaders = CopyOnWriteArrayList<String>()
        val observedPaths = CopyOnWriteArrayList<String>()

        ServerSocket(0, 50, loopbackAddr).use { listener ->
            listener.soTimeout = 8_000
            val serverThread = Thread {
                // 1) GET virtualMachines?$expand=instanceView -> 400 Bad Request
                // 2) GET virtualMachines?statusOnly=true -> 200 OK (with NIC id)
                // 3) GET networkInterfaces/nic-web-01 -> 200 OK (with PublicIP id)
                // 4) GET publicIPAddresses/pip-web-01 -> 200 OK (20.99.88.77)
                // 5) POST .../virtualMachines/az-win-01/start -> 202 Accepted
                repeat(5) {
                    val sock = runCatching { listener.accept() }.getOrNull() ?: return@repeat
                    sock.use { s ->
                        s.soTimeout = 8_000
                        val req = readSimpleHttpRequest(s.inputStream) ?: return@use
                        observedAuthHeaders += req.headers["authorization"].orEmpty()
                        observedPaths += "${req.method} ${req.path}"

                        val (status, json) = when {
                            req.path.contains("expand=instanceView") ->
                                "HTTP/1.1 400 Bad Request" to """{"error":{"code":"InvalidParameter","message":"Use statusOnly=true"}}"""
                            req.path.contains("statusOnly=true") ->
                                "HTTP/1.1 200 OK" to """
                                    {"value":[{
                                      "id":"/subscriptions/sub-001/resourceGroups/rg-prod/providers/Microsoft.Compute/virtualMachines/az-win-01",
                                      "name":"az-win-01",
                                      "location":"westeurope",
                                      "properties":{
                                        "hardwareProfile":{"vmSize":"Standard_D4s_v5"},
                                        "storageProfile":{"osDisk":{"osType":"Windows"}},
                                        "networkProfile":{"networkInterfaces":[{"id":"/subscriptions/sub-001/resourceGroups/rg-prod/providers/Microsoft.Network/networkInterfaces/nic-web-01"}]},
                                        "instanceView":{"statuses":[{"code":"PowerState/running"}]}
                                      }
                                    }]}
                                """.trimIndent()
                            req.path.contains("/networkInterfaces/nic-web-01") ->
                                "HTTP/1.1 200 OK" to """
                                    {"properties":{"ipConfigurations":[{"properties":{
                                      "privateIPAddress":"10.50.0.4",
                                      "publicIPAddress":{"id":"/subscriptions/sub-001/resourceGroups/rg-prod/providers/Microsoft.Network/publicIPAddresses/pip-web-01"}
                                    }}]}}
                                """.trimIndent()
                            req.path.contains("/publicIPAddresses/pip-web-01") ->
                                "HTTP/1.1 200 OK" to """{"properties":{"ipAddress":"20.99.88.77"}}"""
                            req.method == "POST" && req.path.contains("/virtualMachines/az-win-01/start") ->
                                "HTTP/1.1 202 Accepted" to """{}"""
                            else -> "HTTP/1.1 404 Not Found" to """{}"""
                        }
                        val bytes = json.toByteArray(StandardCharsets.UTF_8)
                        val hdr = "$status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            .toByteArray(StandardCharsets.US_ASCII)
                        s.outputStream.write(hdr)
                        s.outputStream.write(bytes)
                        s.outputStream.flush()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            val settings = ProtocolSpecificSettings.CloudVirtualization(
                apiEndpointUrl = "http://127.0.0.1:${listener.localPort}",
                subscriptionOrAccountId = "sub-001",
                realmOrDomain = ""
            )
            // Supply token already prefixed with "Bearer " -> must NOT send "Bearer Bearer ..."
            val secret = ResolvedSessionSecret(
                username = "",
                password = "Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.azure-test"
            )

            val vms = runBlocking {
                CloudVirtualizationHubClient.listInventory(
                    protocol = VaultProtocolType.AZURE_VM,
                    endpointHost = "",
                    settings = settings,
                    secret = secret,
                    allowSelfSignedTls = true
                ).getOrThrow()
            }
            assertEquals(1, vms.size)
            assertEquals("az-win-01", vms[0].name)
            assertEquals("RUNNING", vms[0].powerState)
            assertEquals("20.99.88.77", vms[0].addressOrSessionHost)
            assertEquals(VaultProtocolType.RDP, vms[0].preferredConsoleProtocol)

            val startOut = runBlocking {
                CloudVirtualizationHubClient.executePowerOrSessionAction(
                    protocol = VaultProtocolType.AZURE_VM,
                    endpointHost = "",
                    settings = settings,
                    secret = secret,
                    resource = vms[0],
                    action = VmPowerAction.START,
                    allowSelfSignedTls = true
                ).getOrThrow()
            }
            assertTrue(startOut.contains("start", ignoreCase = true))
            serverThread.join(5_000L)
        }

        assertEquals(5, observedPaths.size)
        assertTrue(observedAuthHeaders.all { it == "Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.azure-test" })
    }

    @Test
    fun explicitFtpsResumesTlsSessionOnPassiveDataSocketHandlesPasvNatAndBlankListDir() {
        // Verify NAT-safe PASV IP resolution directly
        assertEquals("203.0.113.10", JvmFtpFtpsTransferClient.resolveNatSafePasvHost("192.168.1.50", "203.0.113.10"))
        assertEquals("127.0.0.1", JvmFtpFtpsTransferClient.resolveNatSafePasvHost("10.254.0.99", "127.0.0.1"))
        assertEquals("127.0.0.1", JvmFtpFtpsTransferClient.resolveNatSafePasvHost("0.0.0.0", "127.0.0.1"))

        val recordedCommands = CopyOnWriteArrayList<String>()
        val serverControlSessionHex = AtomicReference("")
        val serverDataSessionHex = AtomicReference("")

        ServerSocket(0, 50, loopbackAddr).use { controlListener ->
            controlListener.soTimeout = 8_000
            val serverThread = Thread {
                controlListener.accept().use { plainSocket ->
                    plainSocket.soTimeout = 8_000
                    var reader = BufferedReader(InputStreamReader(plainSocket.getInputStream(), StandardCharsets.UTF_8))
                    var out = BufferedOutputStream(plainSocket.getOutputStream())
                    fun reply(line: String) {
                        out.write("$line\r\n".toByteArray(StandardCharsets.UTF_8))
                        out.flush()
                    }
                    var pendingPasv: ServerSocket? = null
                    reply("220 FTPS TLS Reuse + NAT PASV Ready")
                    while (true) {
                        val line = reader.readLine() ?: break
                        recordedCommands += line
                        val verb = line.substringBefore(' ').uppercase()
                        when (verb) {
                            "AUTH" -> {
                                reply("234 AUTH TLS OK")
                                val ssl = (serverSslContext.socketFactory.createSocket(
                                    plainSocket,
                                    "127.0.0.1",
                                    plainSocket.port,
                                    true
                                ) as SSLSocket).apply {
                                    useClientMode = false
                                    enabledProtocols = arrayOf("TLSv1.2")
                                    startHandshake()
                                }
                                serverControlSessionHex.set(ssl.session.id.joinToString("") { "%02x".format(it) })
                                reader = BufferedReader(InputStreamReader(ssl.inputStream, StandardCharsets.UTF_8))
                                out = BufferedOutputStream(ssl.outputStream)
                            }
                            "USER" -> reply("331 Password required")
                            "PASS" -> reply("230 Logged into /home/deploy")
                            "PBSZ", "PROT", "TYPE" -> reply("200 OK")
                            "PWD" -> reply("257 \"/home/deploy\" is home directory")
                            "EPSV" -> reply("502 EPSV disabled by firewall")
                            "PASV" -> {
                                pendingPasv?.close()
                                val ds = ServerSocket(0, 10, loopbackAddr).apply { soTimeout = 8_000 }
                                pendingPasv = ds
                                val p1 = (ds.localPort ushr 8) and 0xFF
                                val p2 = ds.localPort and 0xFF
                                // Advertise unroutable RFC1918 NAT IP 10.254.0.99 -> client must substitute control peer 127.0.0.1
                                reply("227 Entering Passive Mode (10,254,0,99,$p1,$p2)")
                            }
                            "MLSD" -> {
                                val ds = pendingPasv ?: error("PASV not opened")
                                pendingPasv = null
                                reply("150 Opening TLS data socket")
                                ds.use { listener ->
                                    listener.accept().use { rawData ->
                                        val tlsData = (serverSslContext.socketFactory.createSocket(
                                            rawData,
                                            "127.0.0.1",
                                            rawData.port,
                                            true
                                        ) as SSLSocket).apply {
                                            useClientMode = false
                                            enabledProtocols = arrayOf("TLSv1.2")
                                            startHandshake()
                                        }
                                        serverDataSessionHex.set(tlsData.session.id.joinToString("") { "%02x".format(it) })
                                        tlsData.outputStream.write(
                                            "type=file;size=42;UNIX.mode=0640;modify=20261117093000; deploy-key.pub\r\n"
                                                .toByteArray(StandardCharsets.UTF_8)
                                        )
                                        tlsData.outputStream.flush()
                                        tlsData.close()
                                    }
                                }
                                reply("226 MLSD complete")
                            }
                            "QUIT" -> {
                                reply("221 Bye")
                                break
                            }
                            else -> reply("550 Unexpected command $line")
                        }
                    }
                    pendingPasv?.close()
                }
            }.apply {
                isDaemon = true
                start()
            }

            JvmFtpFtpsTransferClient(
                host = "127.0.0.1",
                port = controlListener.localPort,
                username = "deploy",
                password = "pw",
                securityMode = FtpSecurityMode.EXPLICIT_FTPS,
                allowInsecureTls = true
            ).use { client ->
                client.connect()
                // Blank listDirectory("") must resolve home via PWD ("/home/deploy") without sending CWD /
                val entries = client.listDirectory("")
                assertEquals(1, entries.size)
                assertEquals("/home/deploy/deploy-key.pub", entries[0].fullPath)
                assertTrue("TLSv1.2 control session ID must be non-empty", client.controlSessionIdHex.isNotEmpty())
                assertEquals(
                    "Passive FTPS data channel must resume the control channel TLS session",
                    client.controlSessionIdHex,
                    client.lastDataSessionIdHex
                )
            }
            serverThread.join(5_000L)
        }

        assertEquals(serverControlSessionHex.get(), serverDataSessionHex.get())
        assertTrue(recordedCommands.none { it.startsWith("CWD", ignoreCase = true) })
    }

    @Test
    fun rfbSecurityNegotiationSelectsVncType2WhenStandardVncHasUsernameAndArdType30WhenAppleScreenSharing() {
        fun runSingleOfferProbe(isAppleScreenSharing: Boolean): Int {
            val chosenSecurityByte = java.util.concurrent.atomic.AtomicInteger(-1)
            val chosenLatch = CountDownLatch(1)
            ServerSocket(0, 10, loopbackAddr).use { listener ->
                listener.soTimeout = 5_000
                val serverThread = Thread {
                    runCatching {
                        listener.accept().use { sock ->
                            sock.soTimeout = 5_000
                            val out = sock.getOutputStream()
                            val inp = DataInputStream(sock.getInputStream())
                            out.write("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII))
                            out.flush()
                            val clientVer = ByteArray(12)
                            inp.readFully(clientVer)
                            // Offer BOTH Apple ARD (30) AND standard VNC DES (2)
                            out.write(byteArrayOf(2, 30, 2))
                            out.flush()
                            val picked = inp.readUnsignedByte()
                            chosenSecurityByte.set(picked)
                            chosenLatch.countDown()
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

                VncRfbSessionClient().use { vncClient ->
                    vncClient.connect(
                        host = "127.0.0.1",
                        port = listener.localPort,
                        username = "admin",
                        password = "secret",
                        isAppleScreenSharing = isAppleScreenSharing,
                        forceReconnect = true
                    )
                    assertTrue(chosenLatch.await(5_000L, TimeUnit.MILLISECONDS))
                }
                serverThread.join(3_000L)
            }
            return chosenSecurityByte.get()
        }

        // Standard VNC connection carrying an inherited vault username MUST pick VNC Type 2 (not ARD Type 30!)
        assertEquals(2, runSingleOfferProbe(isAppleScreenSharing = false))
        // Explicit Apple Screen Sharing connection MUST pick ARD Type 30
        assertEquals(30, runSingleOfferProbe(isAppleScreenSharing = true))
    }

    @Test
    fun all23VaultProtocolsRoundTripLosslesslyThroughRtsxXmlAndRtszArchive() {
        val connections = VaultProtocolType.entries.mapIndexed { idx, proto ->
            val settings: ProtocolSpecificSettings = when (proto) {
                VaultProtocolType.RDP -> ProtocolSpecificSettings.Rdp(
                    port = 2179,
                    desktopScaleFactorPercent = 200,
                    hiDpiScaleCustomized = true,
                    ignoreCertificateWarnings = true,
                    hyperVVmId = "11111111-2222-3333-4444-555555555555",
                    hyperVEnhancedSession = true
                )
                VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> ProtocolSpecificSettings.Rdp(
                    port = 3390,
                    connectToConsoleAdmin = true
                )
                VaultProtocolType.SSH -> ProtocolSpecificSettings.Ssh(
                    port = 2222,
                    terminalType = "xterm-256color",
                    enableAgentForwarding = true
                )
                VaultProtocolType.TELNET -> ProtocolSpecificSettings.Ssh(
                    port = 2323,
                    terminalType = "vt100"
                )
                VaultProtocolType.SERIAL -> ProtocolSpecificSettings.SerialUart(
                    baudRate = 9600,
                    dataBits = 8,
                    stopBits = 1,
                    parity = "EVEN",
                    flowControl = "RTS_CTS",
                    devicePathHint = "/dev/ttyUSB1",
                    lineEnding = "LF"
                )
                VaultProtocolType.SFTP -> ProtocolSpecificSettings.FileTransfer(
                    port = 22,
                    initialRemoteDirectory = "/var/www/html",
                    showHiddenDotfiles = false
                )
                VaultProtocolType.SCP -> ProtocolSpecificSettings.FileTransfer(
                    port = 2200,
                    initialRemoteDirectory = "/srv/releases"
                )
                VaultProtocolType.FTP -> ProtocolSpecificSettings.FileTransfer(
                    port = 21,
                    initialRemoteDirectory = "/pub/incoming",
                    passiveMode = false
                )
                VaultProtocolType.FTPS -> ProtocolSpecificSettings.FileTransfer(
                    port = 21,
                    initialRemoteDirectory = "/secure/drop",
                    ftpsImplicitTls = false,
                    passiveMode = true,
                    verifyTls = true
                )
                VaultProtocolType.VNC -> ProtocolSpecificSettings.Vnc(
                    port = 5901,
                    viewOnly = true,
                    sharedSession = false,
                    appleArdDiffieHellmanAuth = false
                )
                VaultProtocolType.APPLE_SCREEN_SHARING -> ProtocolSpecificSettings.Vnc(
                    port = 5900,
                    viewOnly = false,
                    sharedSession = true,
                    appleArdDiffieHellmanAuth = true
                )
                VaultProtocolType.WEB_PAGE -> ProtocolSpecificSettings.WebPage(
                    url = "https://idrac.corp.local:8443/login",
                    autoFillEnabled = false,
                    injectHttpBasicAuth = false,
                    ignoreSelfSignedInternalCert = false,
                    pinnedCertSha256 = "AA:BB:CC:DD"
                )
                VaultProtocolType.VMWARE_VSPHERE -> ProtocolSpecificSettings.CloudVirtualization(
                    apiEndpointUrl = "https://vcenter.corp.local",
                    regionOrCluster = "DC-EU",
                    realmOrDomain = "vsphere.local",
                    verifyTls = true
                )
                VaultProtocolType.PROXMOX_VE -> ProtocolSpecificSettings.CloudVirtualization(
                    apiEndpointUrl = "https://pve01.corp.local:8006",
                    regionOrCluster = "pve-cluster",
                    realmOrDomain = "pve",
                    verifyTls = false
                )
                VaultProtocolType.HYPER_V -> ProtocolSpecificSettings.CloudVirtualization(
                    apiEndpointUrl = "",
                    realmOrDomain = "CORP",
                    verifyTls = false
                )
                VaultProtocolType.AWS_EC2 -> ProtocolSpecificSettings.CloudVirtualization(
                    apiEndpointUrl = "",
                    regionOrCluster = "eu-central-1",
                    subscriptionOrAccountId = "123456789012",
                    realmOrDomain = "",
                    verifyTls = true
                )
                VaultProtocolType.AZURE_VM -> ProtocolSpecificSettings.CloudVirtualization(
                    apiEndpointUrl = "https://management.azure.com",
                    regionOrCluster = "rg-prod",
                    subscriptionOrAccountId = "sub-uuid-9999",
                    realmOrDomain = "tenant-uuid-8888",
                    verifyTls = true
                )
                VaultProtocolType.UTILITY_PING -> ProtocolSpecificSettings.NetUtility(
                    pingCount = 15,
                    pingIntervalMs = 500
                )
                VaultProtocolType.UTILITY_TRACEROUTE -> ProtocolSpecificSettings.NetUtility(
                    tracerouteMaxHops = 24
                )
                VaultProtocolType.UTILITY_PORT_SCANNER -> ProtocolSpecificSettings.NetUtility(
                    portScanRangeStart = 20,
                    portScanRangeEnd = 9000,
                    portScanConcurrency = 96
                )
                VaultProtocolType.UTILITY_WAKE_ON_LAN -> ProtocolSpecificSettings.NetUtility(
                    wolMacAddress = "AA:BB:CC:11:22:33",
                    wolBroadcastAddress = "192.168.10.255",
                    wolUdpPort = 7,
                    wolSecureOnHex = "010203040506"
                )
                VaultProtocolType.KUBERNETES_POD -> ProtocolSpecificSettings.ContainerExec(
                    clusterOrSocketEndpoint = "https://k8s.corp.local:6443",
                    contextName = "prod-eks",
                    namespace = "payments",
                    podOrContainerName = "checkout-api-7f8b9",
                    subContainerName = "istio-proxy",
                    shellCommand = "/bin/bash",
                    followLogsOnOpen = true,
                    logTailLines = 500,
                    verifyTls = true
                )
                VaultProtocolType.DOCKER_CONTAINER -> ProtocolSpecificSettings.ContainerExec(
                    clusterOrSocketEndpoint = "tcp://docker01.corp.local:2376",
                    podOrContainerName = "",
                    shellCommand = "/bin/sh",
                    verifyTls = true
                )
            }
            val host = when (proto) {
                VaultProtocolType.AWS_EC2 -> ""
                VaultProtocolType.AZURE_VM -> ""
                VaultProtocolType.UTILITY_WAKE_ON_LAN -> "AA:BB:CC:11:22:33"
                else -> "host-$idx.corp.local"
            }
            VaultConnection(
                id = "conn-${proto.name.lowercase()}",
                name = "Conn ${proto.displayName}",
                host = host,
                protocol = proto,
                settings = settings
            )
        }

        assertEquals(23, connections.size)
        val originalDoc = NxtsVaultDocument(
            documentId = "doc-all-23-protocols",
            name = "All 23 Protocols Verification Vault",
            connections = connections
        )

        val exporter = VaultDocumentExporter()
        val importer = RtszDocumentImporter()
        val rtszBytes = exporter.exportToRtszBytes(originalDoc, includeSecrets = true)
        val reimported = importer.importRtszBytes(rtszBytes)

        assertEquals(23, reimported.connections.size)
        val byProto = reimported.connections.associateBy { it.protocol }
        assertEquals(VaultProtocolType.entries.toSet(), byProto.keys)

        // Spot-check critical non-default protocol settings across every family
        val rdp = byProto.getValue(VaultProtocolType.RDP).settings as ProtocolSpecificSettings.Rdp
        assertEquals(2179, rdp.effectivePort())
        assertEquals("11111111-2222-3333-4444-555555555555", rdp.hyperVVmId)
        assertTrue(rdp.hyperVEnhancedSession)

        val ftps = byProto.getValue(VaultProtocolType.FTPS).settings as ProtocolSpecificSettings.FileTransfer
        assertEquals(21, ftps.port)
        assertFalse("Explicit FTPS on port 21 must remain ftpsImplicitTls=false", ftps.ftpsImplicitTls)
        assertTrue(ftps.verifyTls)
        assertEquals("/secure/drop", ftps.initialRemoteDirectory)

        val ftp = byProto.getValue(VaultProtocolType.FTP).settings as ProtocolSpecificSettings.FileTransfer
        assertFalse(ftp.passiveMode)

        val vnc = byProto.getValue(VaultProtocolType.VNC).settings as ProtocolSpecificSettings.Vnc
        assertFalse(vnc.appleArdDiffieHellmanAuth)
        assertTrue(vnc.viewOnly)
        assertFalse(vnc.sharedSession)

        val ard = byProto.getValue(VaultProtocolType.APPLE_SCREEN_SHARING).settings as ProtocolSpecificSettings.Vnc
        assertTrue(ard.appleArdDiffieHellmanAuth)

        val web = byProto.getValue(VaultProtocolType.WEB_PAGE).settings as ProtocolSpecificSettings.WebPage
        assertFalse(web.ignoreSelfSignedInternalCert)
        assertFalse(web.autoFillEnabled)
        assertFalse(web.injectHttpBasicAuth)

        val ec2 = byProto.getValue(VaultProtocolType.AWS_EC2).settings as ProtocolSpecificSettings.CloudVirtualization
        assertEquals("", ec2.apiEndpointUrl)
        assertEquals("eu-central-1", ec2.regionOrCluster)
        assertTrue(ec2.verifyTls)

        val pve = byProto.getValue(VaultProtocolType.PROXMOX_VE).settings as ProtocolSpecificSettings.CloudVirtualization
        assertEquals("https://pve01.corp.local:8006", pve.apiEndpointUrl)
        assertEquals("pve", pve.realmOrDomain)

        val hv = byProto.getValue(VaultProtocolType.HYPER_V).settings as ProtocolSpecificSettings.CloudVirtualization
        assertEquals("", hv.apiEndpointUrl)
        assertEquals("CORP", hv.realmOrDomain)

        val k8s = byProto.getValue(VaultProtocolType.KUBERNETES_POD).settings as ProtocolSpecificSettings.ContainerExec
        assertEquals("https://k8s.corp.local:6443", k8s.clusterOrSocketEndpoint)
        assertEquals("payments", k8s.namespace)
        assertEquals("checkout-api-7f8b9", k8s.podOrContainerName)
        assertEquals("istio-proxy", k8s.subContainerName)
        assertTrue(k8s.verifyTls)

        val docker = byProto.getValue(VaultProtocolType.DOCKER_CONTAINER).settings as ProtocolSpecificSettings.ContainerExec
        assertEquals("tcp://docker01.corp.local:2376", docker.clusterOrSocketEndpoint)
        assertEquals("", docker.podOrContainerName)
        assertTrue(docker.verifyTls)

        val scan = byProto.getValue(VaultProtocolType.UTILITY_PORT_SCANNER).settings as ProtocolSpecificSettings.NetUtility
        assertEquals(20, scan.portScanRangeStart)
        assertEquals(9000, scan.portScanRangeEnd)

        val wol = byProto.getValue(VaultProtocolType.UTILITY_WAKE_ON_LAN).settings as ProtocolSpecificSettings.NetUtility
        assertEquals("AA:BB:CC:11:22:33", wol.wolMacAddress)
        assertEquals("192.168.10.255", wol.wolBroadcastAddress)
        assertEquals(7, wol.wolUdpPort)
    }
}

