package com.nexusts.app.core.vault

import com.nexusts.app.core.vault.automation.AutomationTaskEngine
import com.nexusts.app.core.vault.automation.MacroStep
import com.nexusts.app.core.vault.credentials.EnterpriseCredentialHub
import com.nexusts.app.core.vault.credentials.KeePassKdbx4Reader
import com.nexusts.app.core.vault.exporter.VaultDocumentExporter
import com.nexusts.app.core.vault.importer.DetectedExternalVaultFormat
import com.nexusts.app.core.vault.importer.EnterpriseVaultFormatImporter
import com.nexusts.app.core.vault.importer.RtszDocumentImporter
import com.nexusts.app.core.vault.inheritance.PropertyInheritanceResolver
import com.nexusts.app.core.vault.model.ConnectionTemplate
import com.nexusts.app.core.vault.model.CustomPropertyEntry
import com.nexusts.app.core.vault.model.DynamicFolder
import com.nexusts.app.core.vault.model.DynamicScriptInterpreter
import com.nexusts.app.core.vault.model.GatewayTransportKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.KeySequenceTask
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.RdpAudioRedirectionMode
import com.nexusts.app.core.vault.model.RdpResizeBehavior
import com.nexusts.app.core.vault.model.SecureGatewayConfig
import com.nexusts.app.core.vault.model.SmartFolder
import com.nexusts.app.core.vault.model.SmartFolderFilter
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.core.vault.model.VaultTodoItem
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class VaultQualitySweepTest {

    @Test
    fun rtszRoundTripIs100PercentLosslessAcrossAllProtocolTypesTemplatesSmartFoldersAndTodos() {
        val exporter = VaultDocumentExporter()
        val importer = RtszDocumentImporter()

        fun customSettingsFor(protocol: VaultProtocolType): ProtocolSpecificSettings = when (protocol) {
            VaultProtocolType.RDP, VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> ProtocolSpecificSettings.Rdp(
                port = 3391,
                connectToConsoleAdmin = true,
                desktopScaleFactorPercent = 150,
                deviceScaleFactorPercent = 140,
                resizeBehavior = RdpResizeBehavior.SMART_SCALE_VIEWPORT,
                customDesktopWidth = 2560,
                customDesktopHeight = 1440,
                audioRedirectionMode = RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST,
                redirectAudioPlayback = false,
                redirectMicrophone = true,
                redirectDrives = true,
                ignoreCertificateWarnings = true,
                rdGatewayHostname = "rdgw.corp.internal",
                rdGatewayPort = 8443
            )
            VaultProtocolType.SSH, VaultProtocolType.TELNET -> ProtocolSpecificSettings.Ssh(
                port = if (protocol == VaultProtocolType.TELNET) 2323 else 2222,
                terminalType = "xterm-kitty",
                enableAgentForwarding = true,
                startupCommand = "tmux attach -t sre"
            )
            VaultProtocolType.SERIAL -> ProtocolSpecificSettings.SerialUart(
                baudRate = 57_600,
                dataBits = 7,
                stopBits = 2,
                parity = "EVEN",
                flowControl = "RTS_CTS",
                dtrEnabled = false,
                rtsEnabled = true,
                devicePathHint = "/dev/ttyACM1",
                lineEnding = "LF"
            )
            VaultProtocolType.SFTP, VaultProtocolType.FTP, VaultProtocolType.FTPS, VaultProtocolType.SCP ->
                ProtocolSpecificSettings.FileTransfer(
                    port = 2121,
                    initialLocalDirectory = "/tmp/uploads",
                    initialRemoteDirectory = "/srv/releases",
                    preserveTimestamps = false,
                    ftpsImplicitTls = protocol == VaultProtocolType.FTPS
                )
            VaultProtocolType.VNC, VaultProtocolType.APPLE_SCREEN_SHARING -> ProtocolSpecificSettings.Vnc(
                port = 5902,
                compressionLevel = 4,
                jpegQualityLevel = 8,
                viewOnly = true,
                appleArdDiffieHellmanAuth = protocol == VaultProtocolType.APPLE_SCREEN_SHARING
            )
            VaultProtocolType.WEB_PAGE -> ProtocolSpecificSettings.WebPage(
                url = "https://console.corp.internal:8443/dashboard",
                isolatedStorageProfileId = "profile-fixed-01",
                autoFillEnabled = false,
                injectHttpBasicAuth = true,
                ignoreSelfSignedInternalCert = true
            )
            VaultProtocolType.VMWARE_VSPHERE,
            VaultProtocolType.PROXMOX_VE,
            VaultProtocolType.HYPER_V,
            VaultProtocolType.AWS_EC2,
            VaultProtocolType.AZURE_VM -> ProtocolSpecificSettings.CloudVirtualization(
                apiEndpointUrl = "https://virt-${protocol.name.lowercase()}.corp.internal/api",
                regionOrCluster = "eu-central-cluster-01",
                subscriptionOrAccountId = "sub-9942",
                realmOrDomain = "corp.ad",
                autoSyncDynamicInventory = false
            )
            VaultProtocolType.UTILITY_PING,
            VaultProtocolType.UTILITY_TRACEROUTE,
            VaultProtocolType.UTILITY_PORT_SCANNER,
            VaultProtocolType.UTILITY_WAKE_ON_LAN -> ProtocolSpecificSettings.NetUtility(
                pingCount = 25,
                portScanRangeStart = 20,
                portScanRangeEnd = 9090,
                wolMacAddress = "AA:BB:CC:DD:EE:FF",
                wolBroadcastAddress = "10.20.255.255",
                wolUdpPort = 7,
                wolSecureOnHex = "010203040506"
            )
            VaultProtocolType.KUBERNETES_POD, VaultProtocolType.DOCKER_CONTAINER ->
                ProtocolSpecificSettings.ContainerExec(
                    clusterOrSocketEndpoint = "https://k8s.eu.internal:6443",
                    contextName = "prod-eu-01",
                    namespace = "payments",
                    podOrContainerName = "ledger-worker-0",
                    subContainerName = "ledger-app",
                    shellCommand = "/bin/zsh",
                    followLogsOnOpen = true,
                    logTailLines = 500
                )
        }

        val allProtocols = VaultProtocolType.entries
        assertTrue("Expected at least 20 protocols, found ${allProtocols.size}", allProtocols.size >= 20)

        val connections = allProtocols.mapIndexed { idx, proto ->
            VaultConnection(
                id = "conn-${proto.name.lowercase()}",
                name = "Endpoint ${proto.name}",
                host = "host-$idx.corp.internal",
                protocol = proto,
                osFamily = if (proto == VaultProtocolType.RDP) "Windows" else "Linux",
                tags = listOf("sweep", proto.name.lowercase()),
                notes = "Notes for ${proto.name}",
                favorite = idx % 2 == 0,
                settings = customSettingsFor(proto)
            )
        }

        val templates = allProtocols.map { proto ->
            ConnectionTemplate(
                id = "tpl-${proto.name.lowercase()}",
                name = "Template ${proto.name}",
                description = "Standard ${proto.name} enterprise baseline",
                protocol = proto,
                colorAccentHex = "#3CE6B8",
                settings = customSettingsFor(proto)
            )
        }

        val smartFolder = SmartFolder(
            id = "sf-prod-ssh",
            name = "Prod SSH & K8s",
            filter = SmartFolderFilter(
                includeTags = listOf("sweep"),
                includeProtocols = listOf(VaultProtocolType.SSH, VaultProtocolType.KUBERNETES_POD),
                osFamilyFilter = "Linux",
                hostPatternRegex = "^host-\\d+\\.corp",
                environmentFilter = "PROD"
            )
        )

        val todo = VaultTodoItem(
            id = "todo-audit-1",
            targetObjectId = connections.first().id,
            title = "Rotate RD Gateway TLS cert",
            completed = true,
            priority = "HIGH",
            dueIsoDate = "2026-12-01"
        )

        val gw = SecureGatewayConfig(
            id = "gw-socks",
            name = "ZeroTrust SOCKS5",
            kind = GatewayTransportKind.SOCKS5_PROXY,
            host = "socks.corp.internal",
            port = 1080,
            verifyTlsCertificate = false
        )

        val sourceDoc = NxtsVaultDocument(
            documentId = "doc-all-protocols",
            name = "Full Protocol Sweep Vault",
            description = "23-protocol lossless verification",
            revision = 12L,
            smartFolders = listOf(smartFolder),
            connections = connections,
            templates = templates,
            gateways = listOf(gw),
            todoItems = listOf(todo)
        )

        val rtszBytes = exporter.exportToRtszBytes(sourceDoc, includeSecrets = true)
        val imported = importer.importRtszBytes(rtszBytes)

        assertEquals(sourceDoc.documentId, imported.documentId)
        assertEquals(sourceDoc.name, imported.name)
        assertEquals(12L, imported.revision)
        assertEquals(allProtocols.size, imported.connections.size)
        assertEquals(allProtocols.size, imported.templates.size)
        assertEquals(1, imported.smartFolders.size)
        assertEquals(1, imported.todoItems.size)
        assertEquals(1, imported.gateways.size)
        assertEquals(GatewayTransportKind.SOCKS5_PROXY, imported.gateways.single().kind)

        for (proto in allProtocols) {
            val origConn = sourceDoc.connections.first { it.protocol == proto }
            val impConn = imported.connections.first { it.id == origConn.id }
            assertEquals("Protocol mismatch for ${origConn.id}", proto, impConn.protocol)
            assertEquals("Settings mismatch for connection $proto", origConn.settings, impConn.settings)
            assertEquals(origConn.tags, impConn.tags)
            assertEquals(origConn.favorite, impConn.favorite)

            val origTpl = sourceDoc.templates.first { it.protocol == proto }
            val impTpl = imported.templates.first { it.id == origTpl.id }
            assertEquals("Template protocol mismatch for $proto", proto, impTpl.protocol)
            assertEquals("Template description mismatch for $proto", origTpl.description, impTpl.description)
            assertEquals("Template settings mismatch for $proto", origTpl.settings, impTpl.settings)
        }

        val impSf = imported.smartFolders.single()
        assertEquals(smartFolder.name, impSf.name)
        assertEquals(smartFolder.filter, impSf.filter)

        val impTodo = imported.todoItems.single()
        assertEquals("Rotate RD Gateway TLS cert", impTodo.title)
        assertTrue(impTodo.completed)
        assertEquals("HIGH", impTodo.priority)
        assertEquals("2026-12-01", impTodo.dueIsoDate)
    }

    @Test
    fun keySequenceMacroPreservesBracesInPasswordsAndResolvesCaseInsensitiveCustomProperties() {
        val cred = VaultCredential(
            id = "c-brace",
            name = "Complex Secret",
            username = "admin_{ESC}",
            secretPassword = "P@ss{ENTER}{WAIT:9999}{CTRL+ALT+DEL}\$1"
        )
        val folder = VaultFolder(
            id = "f-macro",
            name = "Macro Folder",
            inheritable = InheritableProperties(
                credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                explicitCredentialId = "c-brace",
                customProperties = listOf(
                    CustomPropertyEntry(key = "BastionToken", value = "tok-{TAB}-99")
                )
            )
        )
        val conn = VaultConnection(
            id = "conn-macro",
            parentFolderId = "f-macro",
            name = "Target",
            host = "10.0.5.9",
            protocol = VaultProtocolType.SSH,
            settings = ProtocolSpecificSettings.Ssh()
        )
        val doc = NxtsVaultDocument(
            name = "MacroTest",
            folders = listOf(folder),
            credentials = listOf(cred),
            connections = listOf(conn)
        )

        val profile = PropertyInheritanceResolver().resolveEffectiveProfile(doc, conn)
        val engine = AutomationTaskEngine()
        val steps = engine.parseKeySequence(
            KeySequenceTask(
                name = "Login",
                macroSequence = "\$EffectiveUsername\${TAB}\$EffectivePassword\${WAIT:200}\$CustomProperty.bastiontoken\${ENTER}"
            ),
            profile
        )

        assertEquals(
            listOf(
                MacroStep.TextPayload("admin_{ESC}"),
                MacroStep.SpecialKey("TAB"),
                MacroStep.TextPayload("P@ss{ENTER}{WAIT:9999}{CTRL+ALT+DEL}\$1"),
                MacroStep.PauseDelay(200L),
                MacroStep.TextPayload("tok-{TAB}-99"),
                MacroStep.SpecialKey("ENTER")
            ),
            steps
        )
    }

    @Test
    fun kdbx4ReaderAdvancesChaCha20OverHistoryEntriesDecodesUuidHexAndImportsBinaryKdbx() {
        val innerStreamKey = ByteArray(32) { (it + 7).toByte() }
        val sha512 = MessageDigest.getInstance("SHA-512").digest(innerStreamKey)
        val encryptor = ChaCha7539Engine().apply {
            init(
                true,
                ParametersWithIV(
                    KeyParameter(sha512.copyOfRange(0, 32)),
                    sha512.copyOfRange(32, 44)
                )
            )
        }

        fun protect(plain: String): String {
            val raw = plain.toByteArray(StandardCharsets.UTF_8)
            val out = ByteArray(raw.size)
            encryptor.processBytes(raw, 0, raw.size, out, 0)
            return Base64.getEncoder().encodeToString(out)
        }

        val uuid1Bytes = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        val uuid2Bytes = ByteArray(16) { (0xA0 + it).toByte() }
        val uuid1B64 = Base64.getEncoder().encodeToString(uuid1Bytes)
        val uuid2B64 = Base64.getEncoder().encodeToString(uuid2Bytes)

        // Encrypt in exact XML document order: Entry 1 active -> Entry 1 History #1 -> Entry 1 History #2 -> Entry 2 active
        val encActivePass1 = protect("CurrentProdPass!2026")
        val encHistoryOldPass1 = protect("VeryLongRotatedLegacyPasswordFrom2024_DoNotUse!")
        val encHistoryOldPass2 = protect("AnotherHistoricalPassword!")
        val encActivePass2 = protect("SecondEntryExactPass#42")

        val kdbxXmlBytes = """
            <?xml version="1.0" encoding="utf-8" standalone="yes"?>
            <KeePassFile>
              <Root>
                <Group>
                  <Name>Root</Name>
                  <Group>
                    <Name>Core Infra</Name>
                    <Entry>
                      <UUID>$uuid1B64</UUID>
                      <String><Key>Title</Key><Value>Primary DB</Value></String>
                      <String><Key>UserName</Key><Value>postgres</Value></String>
                      <String><Key>Password</Key><Value Protected="True">$encActivePass1</Value></String>
                      <String><Key>URL</Key><Value>ssh://10.10.0.50:22</Value></String>
                      <History>
                        <Entry>
                          <UUID>$uuid1B64</UUID>
                          <String><Key>Title</Key><Value>Old Renamed DB</Value></String>
                          <String><Key>Password</Key><Value Protected="True">$encHistoryOldPass1</Value></String>
                        </Entry>
                        <Entry>
                          <UUID>$uuid1B64</UUID>
                          <String><Key>Password</Key><Value Protected="True">$encHistoryOldPass2</Value></String>
                        </Entry>
                      </History>
                    </Entry>
                    <Entry>
                      <UUID>$uuid2B64</UUID>
                      <String><Key>Title</Key><Value>Secondary Bastion</Value></String>
                      <String><Key>UserName</Key><Value>sre</Value></String>
                      <String><Key>Password</Key><Value Protected="True">$encActivePass2</Value></String>
                      <String><Key>URL</Key><Value>rdp://10.10.0.51:3389</Value></String>
                    </Entry>
                  </Group>
                </Group>
              </Root>
            </KeePassFile>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val entries = KeePassKdbx4Reader.parseKeePassXml(kdbxXmlBytes, innerStreamKey)
        assertEquals(2, entries.size)
        assertEquals("00112233445566778899aabbccddeeff", entries[0].uuidHex)
        assertEquals("Primary DB", entries[0].title)
        assertEquals("CurrentProdPass!2026", entries[0].password)

        assertEquals("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", entries[1].uuidHex)
        assertEquals("Secondary Bastion", entries[1].title)
        assertEquals("SecondEntryExactPass#42", entries[1].password)

        // Also wrap into a full KDBX 4.0 binary container and verify EnterpriseVaultFormatImporter end-to-end
        val masterPass = "KdbxMaster!2026".toCharArray()
        val kdbxBinary = buildTestKdbx4Container(kdbxXmlBytes, innerStreamKey, masterPass)
        val importer = EnterpriseVaultFormatImporter()
        assertEquals(DetectedExternalVaultFormat.KEEPASS_KDBX_BINARY, importer.detectFormat("infra.kdbx", kdbxBinary))

        val importedDoc = importer.importExternalFormat(
            DetectedExternalVaultFormat.KEEPASS_KDBX_BINARY,
            kdbxBinary,
            "infra.kdbx",
            masterPass
        )
        assertEquals(2, importedDoc.credentials.size)
        assertEquals(2, importedDoc.connections.size)
        assertEquals("CurrentProdPass!2026", importedDoc.credentials[0].secretPassword)
        assertEquals("SecondEntryExactPass#42", importedDoc.credentials[1].secretPassword)
    }

    @Test
    fun ed25519JvmFallbackEmitsValidOpenSshPrivateKeyV1Format() {
        val hub = EnterpriseCredentialHub()
        val generated = hub.generateEd25519SshKeyPair("sre@nexusts.internal")
        assertTrue(generated.public_key_openssh.startsWith("ssh-ed25519 "))
        assertTrue(generated.public_key_openssh.endsWith(" sre@nexusts.internal"))
        assertTrue(generated.private_key_openssh_pem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(generated.private_key_openssh_pem.trimEnd().endsWith("-----END OPENSSH PRIVATE KEY-----"))

        val b64Body = generated.private_key_openssh_pem
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        val decoded = Base64.getDecoder().decode(b64Body)
        val magic = "openssh-key-v1\u0000".toByteArray(StandardCharsets.US_ASCII)
        assertArrayEquals(magic, decoded.copyOfRange(0, magic.size))

        val buf = ByteBuffer.wrap(decoded, magic.size, decoded.size - magic.size).order(ByteOrder.BIG_ENDIAN)
        fun readSshStr(): ByteArray {
            val len = buf.int
            return ByteArray(len).also { buf.get(it) }
        }
        assertEquals("none", String(readSshStr(), StandardCharsets.US_ASCII))
        assertEquals("none", String(readSshStr(), StandardCharsets.US_ASCII))
        assertEquals(0, readSshStr().size)
        assertEquals(1, buf.int)
        val pubWire = readSshStr()
        assertEquals(51, pubWire.size)
        val privBlob = readSshStr()
        assertEquals(0, privBlob.size % 8)

        val privBuf = ByteBuffer.wrap(privBlob).order(ByteOrder.BIG_ENDIAN)
        val check1 = privBuf.int
        val check2 = privBuf.int
        assertEquals(check1, check2)
    }

    @Test
    fun dynamicFolderGatesScriptExecutionByDefaultAndSmartFolderRejectsInvalidRegex() {
        val resolver = PropertyInheritanceResolver()
        val marker = File(System.getProperty("java.io.tmpdir"), "nexusts_dyn_guard_${System.nanoTime()}.tmp")
        marker.delete()

        val untrustedDf = DynamicFolder(
            name = "Untrusted Shell",
            interpreter = DynamicScriptInterpreter.SHELL_SH,
            scriptOrEndpointPayload = "touch ${marker.absolutePath}; echo '{\"Objects\":[{\"Type\":\"SSH\",\"Name\":\"bad\",\"ComputerName\":\"10.0.0.1\"}]}'"
        )

        // Default allowLocalScriptExecution = false MUST NOT spawn shell process
        val blockedResult = resolver.evaluateDynamicFolder(untrustedDf)
        assertTrue(blockedResult.isEmpty())
        assertFalse("Untrusted script must never execute when allowLocalScriptExecution=false", marker.exists())
        assertNotNull(resolver.getDynamicFolderExecutionNotice(untrustedDf, allowLocalScriptExecution = false))

        // Embedded JSON payload is safely parsed without spawning a process
        val embeddedJsonDf = DynamicFolder(
            name = "Embedded JSON",
            interpreter = DynamicScriptInterpreter.SHELL_SH,
            scriptOrEndpointPayload = """{"Objects":[{"Type":"SSH","Name":"safe-node","ComputerName":"10.0.0.9"}]}"""
        )
        val safeParsed = resolver.evaluateDynamicFolder(embeddedJsonDf)
        assertEquals(1, safeParsed.size)
        assertEquals("safe-node", safeParsed.single().name)

        // Invalid SmartFolder regex must return empty/false rather than matching all hosts
        val conn = VaultConnection(
            name = "prod-01",
            host = "10.0.0.1",
            protocol = VaultProtocolType.SSH,
            settings = ProtocolSpecificSettings.Ssh()
        )
        val doc = NxtsVaultDocument(name = "RegexVault", connections = listOf(conn))
        val invalidRegexFolder = SmartFolder(
            name = "Broken Regex",
            filter = SmartFolderFilter(hostPatternRegex = "[unclosed-group(")
        )
        assertFalse(resolver.matchesSmartFolder(doc, conn, invalidRegexFolder))
        assertTrue(resolver.evaluateSmartFolder(doc, invalidRegexFolder).isEmpty())
    }

    private fun buildTestKdbx4Container(
        xmlBytes: ByteArray,
        innerStreamKey: ByteArray,
        masterPassword: CharArray
    ): ByteArray {
        val salt = ByteArray(32) { (0x10 + it).toByte() }
        val iv = ByteArray(16) { (0x20 + it).toByte() }

        // VariantDictionary (v1.0): S (byte[]), M (u64 = 8 MiB), I (u64 = 1), P (u32 = 1)
        val kdfDict = ByteArrayOutputStream().apply {
            fun putLeShort(v: Int) = write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
            fun putLeInt(v: Int) = write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            fun putEntry(type: Int, name: String, valueBytes: ByteArray) {
                write(type)
                val nb = name.toByteArray(StandardCharsets.UTF_8)
                putLeInt(nb.size)
                write(nb)
                putLeInt(valueBytes.size)
                write(valueBytes)
            }
            putLeShort(0x0100)
            putEntry(0x42, "S", salt)
            putEntry(0x05, "M", ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(8L * 1024L * 1024L).array())
            putEntry(0x05, "I", ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(1L).array())
            putEntry(0x04, "P", ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
            write(0)
        }.toByteArray()

        val headerBytes = ByteArrayOutputStream().apply {
            val sig = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x9AA2D903.toInt())
                .putInt(0xB54BFB67.toInt())
                .putShort(0)
                .putShort(4)
                .array()
            write(sig)
            fun writeField(id: Int, data: ByteArray) {
                write(id)
                write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
                write(data)
            }
            writeField(3, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array()) // GZip compression = 1
            writeField(7, iv)
            writeField(11, kdfDict)
            writeField(0, ByteArray(4))
        }.toByteArray()

        val headerSha256 = MessageDigest.getInstance("SHA-256").digest(headerBytes)
        val dummyHeaderHmac = ByteArray(32) { 0x5A }

        val passSha = MessageDigest.getInstance("SHA-256").digest(String(masterPassword).toByteArray(StandardCharsets.UTF_8))
        val compositeKey = MessageDigest.getInstance("SHA-256").digest(passSha)
        val transformedKey = ByteArray(32)
        Argon2BytesGenerator().apply {
            init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt)
                    .withMemoryAsKB(8192)
                    .withIterations(1)
                    .withParallelism(1)
                    .build()
            )
            generateBytes(compositeKey, transformedKey)
        }
        val masterKey = MessageDigest.getInstance("SHA-256").apply {
            update(salt)
            update(transformedKey)
        }.digest()

        val innerPayload = ByteArrayOutputStream().apply {
            write(2)
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(innerStreamKey.size).array())
            write(innerStreamKey)
            write(0)
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())
            write(xmlBytes)
        }.toByteArray()

        val gzipped = ByteArrayOutputStream().also { baos ->
            GZIPOutputStream(baos).use { it.write(innerPayload) }
        }.toByteArray()

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), IvParameterSpec(iv))
        val encryptedPayload = cipher.doFinal(gzipped)

        return ByteArrayOutputStream().apply {
            write(headerBytes)
            write(headerSha256)
            write(dummyHeaderHmac)
            // Single HMAC block + terminal empty block
            write(ByteArray(32) { 0x33 })
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(encryptedPayload.size).array())
            write(encryptedPayload)
            write(ByteArray(32) { 0x00 })
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())
        }.toByteArray()
    }
}
