package com.nexusts.app.core.vault

import com.nexusts.app.core.vault.exporter.VaultDocumentExporter
import com.nexusts.app.core.vault.importer.DetectedExternalVaultFormat
import com.nexusts.app.core.vault.importer.EncryptedZipExtractor
import com.nexusts.app.core.vault.importer.EnterpriseVaultFormatImporter
import com.nexusts.app.core.vault.importer.RtszDocumentImporter
import com.nexusts.app.core.vault.importer.SecureXmlDomParser
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.GatewayTransportKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

class EnterpriseVaultImporterTest {

    private val importer = EnterpriseVaultFormatImporter()
    private val exporter = VaultDocumentExporter()

    @Test
    fun detectAndImportKeePass2xXmlWithNestedGroupsTotpAndSshKeys() {
        val keepassXml = """
            <?xml version="1.0" encoding="utf-8" standalone="yes"?>
            <KeePassFile>
              <Root>
                <Group>
                  <UUID>root-uuid</UUID>
                  <Name>Corporate KeePass</Name>
                  <Group>
                    <UUID>grp-win-ad</UUID>
                    <Name>Windows AD Tier-0</Name>
                    <Notes>Domain Controllers</Notes>
                    <Entry>
                      <UUID>ent-dc01</UUID>
                      <String><Key>Title</Key><Value>DC01 Domain Admin</Value></String>
                      <String><Key>UserName</Key><Value>CORP\DomainAdmin</Value></String>
                      <String><Key>Password</Key><Value>Sup3rS3cr3t!2026</Value></String>
                      <String><Key>URL</Key><Value>rdp://dc01.corp.internal:3389</Value></String>
                      <String><Key>otp</Key><Value>otpauth://totp/NexusTS:DomainAdmin?secret=JBSWY3DPEHPK3PXP&amp;issuer=NexusTS</Value></String>
                    </Entry>
                  </Group>
                  <Group>
                    <UUID>grp-linux-sre</UUID>
                    <Name>Production Linux Fleet</Name>
                    <Entry>
                      <UUID>ent-k8s-bastion</UUID>
                      <String><Key>Title</Key><Value>K8s Bastion Root</Value></String>
                      <String><Key>UserName</Key><Value>root</Value></String>
                      <String><Key>URL</Key><Value>ssh://root@10.24.0.10:2222</Value></String>
                      <String><Key>SSHKey</Key><Value>-----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmU=
            -----END OPENSSH PRIVATE KEY-----</Value></String>
                    </Entry>
                  </Group>
                  <Group>
                    <UUID>grp-recycle</UUID>
                    <Name>Recycle Bin</Name>
                    <Entry>
                      <UUID>ent-deleted</UUID>
                      <String><Key>Title</Key><Value>Old Retired Host</Value></String>
                    </Entry>
                  </Group>
                </Group>
              </Root>
            </KeePassFile>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val format = importer.detectFormat("corp-secrets.xml", keepassXml)
        assertEquals(DetectedExternalVaultFormat.KEEPASS_XML_2X, format)

        val doc = importer.importExternalFormat(format, keepassXml, "corp-secrets.xml")
        assertEquals("corp-secrets", doc.name)
        assertEquals(2, doc.folders.size)
        assertTrue(doc.folders.none { it.name.equals("Recycle Bin", ignoreCase = true) })

        assertEquals(2, doc.credentials.size)
        val winCred = doc.credentials.first { it.name == "DC01 Domain Admin" }
        assertEquals("CORP", winCred.domain)
        assertEquals("DomainAdmin", winCred.username)
        assertEquals("JBSWY3DPEHPK3PXP", winCred.totpSecretBase32)
        assertEquals(CredentialSourceKind.KEEPASS_KDBX, winCred.sourceKind)

        val sshCred = doc.credentials.first { it.name == "K8s Bastion Root" }
        assertEquals(CredentialSourceKind.SSH_KEY_PAIR, sshCred.sourceKind)
        assertTrue(sshCred.privateKeyPem.contains("BEGIN OPENSSH PRIVATE KEY"))

        assertEquals(2, doc.connections.size)
        val rdpConn = doc.connections.first { it.protocol == VaultProtocolType.RDP }
        assertEquals("dc01.corp.internal", rdpConn.host)
        assertEquals(3389, (rdpConn.settings as ProtocolSpecificSettings.Rdp).port)

        val sshConn = doc.connections.first { it.protocol == VaultProtocolType.SSH }
        assertEquals("10.24.0.10", sshConn.host)
        assertEquals(2222, (sshConn.settings as ProtocolSpecificSettings.Ssh).port)
    }

    @Test
    fun detectAndImportBitwardenVaultJson() {
        val bitwardenJson = """
            {
              "encrypted": false,
              "folders": [
                { "id": "bw-folder-k8s", "name": "Cloud & Kubernetes" }
              ],
              "items": [
                {
                  "id": "item-api-pod",
                  "folderId": "bw-folder-k8s",
                  "type": 1,
                  "name": "EU Payment Worker Pod",
                  "favorite": true,
                  "notes": "Primary EU K8s pod shell",
                  "login": {
                    "username": "sre-oncall",
                    "password": "bw-token-value",
                    "totp": "GEZDGNBVGY3TQOJQ",
                    "uris": [
                      { "uri": "k8s://prod-eu/payment-worker-7c9d" }
                    ]
                  }
                }
              ]
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val detected = importer.detectFormat("bitwarden_export.json", bitwardenJson)
        assertEquals(DetectedExternalVaultFormat.BITWARDEN_JSON, detected)

        val doc = importer.importExternalFormat(detected, bitwardenJson, "bitwarden_export.json")
        assertEquals(1, doc.folders.size)
        assertEquals("Cloud & Kubernetes", doc.folders.single().name)

        val cred = doc.credentials.single()
        assertEquals(CredentialSourceKind.BITWARDEN_VAULT, cred.sourceKind)
        assertEquals("sre-oncall", cred.username)
        assertEquals("GEZDGNBVGY3TQOJQ", cred.totpSecretBase32)

        val k8sConn = doc.connections.single()
        assertEquals(VaultProtocolType.KUBERNETES_POD, k8sConn.protocol)
        assertTrue(k8sConn.favorite)
        val execSettings = k8sConn.settings as ProtocolSpecificSettings.ContainerExec
        assertEquals("prod-eu", execSettings.namespace)
        assertEquals("payment-worker-7c9d", execSettings.podOrContainerName)
    }

    @Test
    fun rejectEncryptedBitwardenJsonExportWithActionableError() {
        val encryptedBw = """
            {
              "encrypted": true,
              "passwordProtected": true,
              "data": "2.iv|ciphertext|mac"
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val format = importer.detectFormat("bitwarden_encrypted.json", encryptedBw)
        assertEquals(DetectedExternalVaultFormat.BITWARDEN_JSON, format)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            importer.importExternalFormat(format, encryptedBw, "bitwarden_encrypted.json")
        }
        assertTrue(ex.message.orEmpty().contains("This Bitwarden JSON export is encrypted"))
    }

    @Test
    fun detectAndImportOnePassword1PuxZipArchive() {
        val exportDataJson = """
            {
              "accounts": [
                {
                  "attrs": { "accountName": "NexusCorp" },
                  "vaults": [
                    {
                      "attrs": { "uuid": "v-infra", "name": "Core NetOps" },
                      "items": [
                        {
                          "uuid": "op-switch-uart",
                          "overview": {
                            "title": "Core Spine Switch UART",
                            "url": "serial:///dev/ttyUSB0"
                          },
                          "details": {
                            "notesPlain": "ROMMON console cable",
                            "loginFields": [
                              { "name": "username", "designation": "username", "value": "netadmin" },
                              { "name": "password", "designation": "password", "value": "EnableSecret!99" }
                            ],
                            "sections": [
                              {
                                "title": "Security",
                                "fields": [
                                  {
                                    "title": "One-Time Password",
                                    "value": { "totp": "otpauth://totp/Spine?secret=MFRGGZDFMZTWQ2LK" }
                                  }
                                ]
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val zipBaos = ByteArrayOutputStream()
        ZipOutputStream(zipBaos).use { zos ->
            zos.putNextEntry(ZipEntry("export.attributes"))
            zos.write("""{"version":3}""".toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("export.data"))
            zos.write(exportDataJson)
            zos.closeEntry()
        }
        val onePuxBytes = zipBaos.toByteArray()

        val detected = importer.detectFormat("netops.1pux", onePuxBytes)
        assertEquals(DetectedExternalVaultFormat.ONEPASSWORD_1PUX_OR_JSON, detected)

        val doc = importer.importExternalFormat(detected, onePuxBytes, "netops.1pux")
        assertEquals(1, doc.folders.size)
        assertEquals("Core NetOps", doc.folders.single().name)

        val cred = doc.credentials.single()
        assertEquals(CredentialSourceKind.ONEPASSWORD_CONNECT, cred.sourceKind)
        assertEquals("netadmin", cred.username)
        assertEquals("MFRGGZDFMZTWQ2LK", cred.totpSecretBase32)

        val serialConn = doc.connections.single()
        assertEquals(VaultProtocolType.SERIAL, serialConn.protocol)
        val serialSettings = serialConn.settings as ProtocolSpecificSettings.SerialUart
        assertEquals(115_200, serialSettings.baudRate)
        assertEquals("/dev/ttyUSB0", serialSettings.devicePathHint)
    }

    @Test
    fun detectAndImportOnePassword1PifMultiRecordExport() {
        val pifContent = """
            {"uuid":"item-web-01","title":"Intranet Portal","typeName":"webforms.WebForm","location":"intranet.corp.local","secureContents":{"notesPlain":"Internal SSO","fields":[{"name":"username","designation":"username","value":"CORP\\alice"},{"name":"password","designation":"password","value":"WebPass!1"}],"sections":[{"title":"2FA","fields":[{"t":"one-time password","n":"TOTP_1","k":"concealed","v":"otpauth://totp/Corp?secret=JBSWY3DPEHPK3PXP"}]}]}}
            ***5642bee8-a5ff-11dc-8314-0800200c9a66***
            {"uuid":"item-ssh-02","title":"IPv6 Bastion SSH","typeName":"wallet.computer.UnixServer","location":"ssh://root@[2001:db8::1]:2222/shell","secureContents":{"username":"root","password":"","sections":[{"title":"Keys","fields":[{"t":"private key","n":"ssh_key","k":"concealed","v":"-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmU=\n-----END OPENSSH PRIVATE KEY-----"}]}]}}
            ***5642bee8-a5ff-11dc-8314-0800200c9a66***
            {"uuid":"item-trashed-03","title":"Retired Entry","trashed":true,"typeName":"webforms.WebForm","secureContents":{}}
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val detected = importer.detectFormat("export.1pif", pifContent)
        assertEquals(DetectedExternalVaultFormat.ONEPASSWORD_1PUX_OR_JSON, detected)

        val doc = importer.importExternalFormat(detected, pifContent, "export.1pif")
        assertEquals(2, doc.credentials.size)
        assertEquals(2, doc.connections.size)

        val webConn = doc.connections.first { it.name == "Intranet Portal" }
        assertEquals(VaultProtocolType.WEB_PAGE, webConn.protocol)
        assertEquals("https://intranet.corp.local", (webConn.settings as ProtocolSpecificSettings.WebPage).url)

        val sshConn = doc.connections.first { it.name == "IPv6 Bastion SSH" }
        assertEquals(VaultProtocolType.SSH, sshConn.protocol)
        assertEquals("2001:db8::1", sshConn.host)
        assertEquals(2222, (sshConn.settings as ProtocolSpecificSettings.Ssh).port)
    }

    @Test
    fun parseHostPortHandlesIpv6LiteralsAndBarePasswordManagerWebUris() {
        val bracketedWithPort = importer.parseHostPort("[2001:db8::1]:2222", VaultProtocolType.SSH, 22)
        assertEquals("2001:db8::1", bracketedWithPort.second)
        assertEquals(2222, bracketedWithPort.third)

        val bareIpv6 = importer.parseHostPort("2001:db8::1", VaultProtocolType.SSH, 22)
        assertEquals("2001:db8::1", bareIpv6.second)
        assertEquals(22, bareIpv6.third)

        val bwJson = """
            {
              "encrypted": false,
              "folders": [
                { "id": "f-nested", "name": "Prod/EU/Linux" }
              ],
              "items": [
                {
                  "id": "i-1",
                  "folderId": "f-nested",
                  "name": "Router Admin UI",
                  "login": { "username": "admin", "password": "pw", "uris": [{ "uri": "10.0.0.12/login" }] }
                },
                {
                  "id": "i-2",
                  "folderId": "f-nested",
                  "name": "Core Jumpbox SSH",
                  "login": { "username": "ops", "password": "pw", "uris": [{ "uri": "10.0.0.12/login" }] }
                }
              ]
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val doc = importer.importBitwardenJson(bwJson, "NestedBw")
        assertEquals(3, doc.folders.size)
        val prod = doc.folders.first { it.name == "Prod" }
        val eu = doc.folders.first { it.name == "EU" }
        val linux = doc.folders.first { it.name == "Linux" }
        assertNull(prod.parentFolderId)
        assertEquals(prod.id, eu.parentFolderId)
        assertEquals(eu.id, linux.parentFolderId)

        val routerWeb = doc.connections.first { it.name == "Router Admin UI" }
        assertEquals(VaultProtocolType.WEB_PAGE, routerWeb.protocol)
        assertEquals("https://10.0.0.12/login", (routerWeb.settings as ProtocolSpecificSettings.WebPage).url)

        val jumpSsh = doc.connections.first { it.name == "Core Jumpbox SSH" }
        assertEquals(VaultProtocolType.SSH, jumpSsh.protocol)
        assertEquals("10.0.0.12", jumpSsh.host)
        assertEquals(linux.id, jumpSsh.parentFolderId)
    }

    @Test
    fun detectBinaryKdbxMagicAndRejectInvalidZipInRtszImporter() {
        val fakeKdbxHeader = byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(), 0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte())
        assertEquals(
            DetectedExternalVaultFormat.KEEPASS_KDBX_BINARY,
            importer.detectFormat("secrets.kdbx", fakeKdbxHeader)
        )
        val missingPassEx = assertThrows(IllegalArgumentException::class.java) {
            importer.importExternalFormat(DetectedExternalVaultFormat.KEEPASS_KDBX_BINARY, fakeKdbxHeader, "secrets.kdbx")
        }
        assertTrue(missingPassEx.message.orEmpty().contains("passphrase", ignoreCase = true))

        assertThrows(IllegalArgumentException::class.java) {
            RtszDocumentImporter().importRtszBytes(fakeKdbxHeader)
        }

        // Non-RTSZ ZIP archive containing binary PNG instead of .rtsx/.xml must throw IllegalArgumentException
        val nonRtszZip = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("assets/icon.png"))
                zos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                zos.closeEntry()
            }
        }.toByteArray()
        assertEquals(DetectedExternalVaultFormat.UNKNOWN, importer.detectFormat("archive.zip", nonRtszZip))
        assertThrows(IllegalArgumentException::class.java) {
            RtszDocumentImporter().importRtszBytes(nonRtszZip)
        }
    }

    @Test
    fun importRfc4180CsvAndExportXmlRoundTripForSerialAndContainerSettings() {
        val csvText = listOf(
            "name,folder,protocol,host,port,username,password,totp,notes",
            "\"Juniper Edge, Rack 4\",OOB Consoles,SERIAL,/dev/ttyACM0,9600,root,\"Pass,With,Commas\",JBSWY3DPEHPK3PXP,\"9600 8N1 \"\"Console\"\"\"",
            "\"Redis Cache Container\",Containers,DOCKER_CONTAINER,prod/redis-primary,2376,ops,dockertoken,,Live container exec"
        ).joinToString("\r\n").toByteArray(StandardCharsets.UTF_8)

        val detected = importer.detectFormat("cmdb-inventory.csv", csvText)
        assertEquals(DetectedExternalVaultFormat.CSV_INVENTORY_OR_PASSWORDS, detected)

        val doc = importer.importExternalFormat(detected, csvText, "cmdb-inventory.csv")
        assertEquals(2, doc.folders.size)
        assertEquals(2, doc.credentials.size)
        assertEquals(2, doc.connections.size)

        val serialConn = doc.connections.first { it.protocol == VaultProtocolType.SERIAL }
        assertEquals("Juniper Edge, Rack 4", serialConn.name)
        assertEquals(9600, (serialConn.settings as ProtocolSpecificSettings.SerialUart).baudRate)

        val dockerConn = doc.connections.first { it.protocol == VaultProtocolType.DOCKER_CONTAINER }
        val dockerExec = dockerConn.settings as ProtocolSpecificSettings.ContainerExec
        assertEquals("redis-primary", dockerExec.podOrContainerName)

        val exportedXml = exporter.exportToRoyalTsXml(doc, includeSecrets = true)
        assertNotNull(exportedXml)
        assertTrue(exportedXml.contains("<BaudRate>9600</BaudRate>"))
        assertTrue(exportedXml.contains("<Workload>redis-primary</Workload>"))
    }

    @Test
    fun enterpriseCsvExportToImportLosslessRoundTripPreservesHierarchyMetadataAndContainers() {
        val fProd = VaultFolder(id = "f-prod", name = "Prod")
        val fEu = VaultFolder(id = "f-eu", parentFolderId = "f-prod", name = "EU")
        val fK8s = VaultFolder(id = "f-k8s", parentFolderId = "f-eu", name = "Linux")
        val cred = VaultCredential(id = "cred-sre", name = "SRE Cluster Admin", username = "sre")

        val k8sConn = VaultConnection(
            id = "conn-k8s-eu-01",
            parentFolderId = "f-k8s",
            name = "Checkout API Pod",
            host = "https://k8s.eu.corp.internal:6443",
            protocol = VaultProtocolType.KUBERNETES_POD,
            osFamily = "Debian-12",
            tags = listOf("pci", "tier-1", "eu-central"),
            notes = "Canary pod exec",
            favorite = true,
            inheritable = InheritableProperties(
                credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                explicitCredentialId = "cred-sre",
                displayColorAccentHex = "#00E5FF"
            ),
            settings = ProtocolSpecificSettings.ContainerExec(
                clusterOrSocketEndpoint = "https://k8s.eu.corp.internal:6443",
                contextName = "gke-eu-west1",
                namespace = "checkout-prod",
                podOrContainerName = "checkout-api-6d4f",
                subContainerName = "istio-proxy",
                shellCommand = "/bin/bash",
                followLogsOnOpen = true
            )
        )

        val promptSshConn = VaultConnection(
            id = "conn-ssh-prompt",
            parentFolderId = "f-eu",
            name = "Breakglass Host",
            host = "10.99.0.5",
            protocol = VaultProtocolType.SSH,
            osFamily = "FreeBSD",
            tags = listOf("breakglass"),
            favorite = false,
            inheritable = InheritableProperties(
                credentialMode = InheritanceMode.PROMPT_AT_CONNECT,
                displayColorAccentHex = "#FF5252"
            ),
            settings = ProtocolSpecificSettings.Ssh(port = 2222)
        )

        val originalDoc = NxtsVaultDocument(
            name = "EnterpriseCMDB",
            folders = listOf(fProd, fEu, fK8s),
            credentials = listOf(cred),
            connections = listOf(k8sConn, promptSshConn)
        )

        val csvString = exporter.exportToEnterpriseCsv(originalDoc)
        val roundTripped = importer.importCsvVault(csvString.toByteArray(StandardCharsets.UTF_8), "EnterpriseCMDB")

        assertEquals(3, roundTripped.folders.size)
        val rtProd = roundTripped.folders.first { it.name == "Prod" }
        val rtEu = roundTripped.folders.first { it.name == "EU" }
        val rtLinux = roundTripped.folders.first { it.name == "Linux" }
        assertNull(rtProd.parentFolderId)
        assertEquals(rtProd.id, rtEu.parentFolderId)
        assertEquals(rtEu.id, rtLinux.parentFolderId)

        val rtK8s = roundTripped.connections.first { it.id == "conn-k8s-eu-01" }
        assertEquals("Debian-12", rtK8s.osFamily)
        assertTrue(rtK8s.favorite)
        assertEquals(listOf("pci", "tier-1", "eu-central"), rtK8s.tags)
        assertEquals("#00E5FF", rtK8s.inheritable.displayColorAccentHex)
        assertEquals(InheritanceMode.OVERRIDE_EXPLICIT, rtK8s.inheritable.credentialMode)
        assertEquals(rtLinux.id, rtK8s.parentFolderId)
        val rtExec = rtK8s.settings as ProtocolSpecificSettings.ContainerExec
        assertEquals("https://k8s.eu.corp.internal:6443", rtExec.clusterOrSocketEndpoint)
        assertEquals("gke-eu-west1", rtExec.contextName)
        assertEquals("checkout-prod", rtExec.namespace)
        assertEquals("checkout-api-6d4f", rtExec.podOrContainerName)
        assertEquals("istio-proxy", rtExec.subContainerName)
        assertEquals("/bin/bash", rtExec.shellCommand)
        assertTrue(rtExec.followLogsOnOpen)

        val rtSsh = roundTripped.connections.first { it.id == "conn-ssh-prompt" }
        assertEquals("FreeBSD", rtSsh.osFamily)
        assertEquals(InheritanceMode.PROMPT_AT_CONNECT, rtSsh.inheritable.credentialMode)
        assertEquals("#FF5252", rtSsh.inheritable.displayColorAccentHex)
        assertEquals(2222, (rtSsh.settings as ProtocolSpecificSettings.Ssh).port)
    }

    @Test
    fun detectAndImportGzippedRoyalTsRtszWithNestedFoldersTerminalWebAndTrashExclusion() {
        val royalXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <RTSZDocument>
              <RoyalDocument>
                <ID>doc-corp-root</ID>
                <Name>Corporate Infrastructure V7</Name>
                <Modified>42</Modified>
                <Objects>
                  <RoyalFolder>
                    <ID>fld-win</ID>
                    <Name>Windows Fleet</Name>
                    <Objects>
                      <RoyalFolder>
                        <ID>fld-win-dc</ID>
                        <Name>Tier-0 Domain Controllers</Name>
                        <Objects>
                          <RoyalRDSConnection>
                            <ID>conn-dc01</ID>
                            <Name>CORP-DC01</Name>
                            <URI>dc01.corp.internal</URI>
                            <RDPPort>3389</RDPPort>
                            <CredentialID>cred-dom-admin</CredentialID>
                            <CredentialMode>2</CredentialMode>
                          </RoyalRDSConnection>
                        </Objects>
                      </RoyalFolder>
                    </Objects>
                  </RoyalFolder>
                  <RoyalFolder>
                    <ID>fld-ops</ID>
                    <Name>Linux &amp; Consoles</Name>
                    <Objects>
                      <RoyalSSHConnection>
                        <ID>conn-ssh-core</ID>
                        <Name>Core Linux Bastion</Name>
                        <ComputerName>10.20.30.40</ComputerName>
                        <Port>2222</Port>
                      </RoyalSSHConnection>
                      <RoyalTerminalConnection>
                        <ID>conn-tsx-serial</ID>
                        <Name>Rack PDU Serial</Name>
                        <IsSerialPort>True</IsSerialPort>
                        <SerialPort>/dev/ttyUSB1</SerialPort>
                        <BaudRate>115200</BaudRate>
                      </RoyalTerminalConnection>
                      <RoyalTerminalConnection>
                        <ID>conn-tsx-ssh</ID>
                        <Name>macOS TSX Jumpbox</Name>
                        <ConnectionType>SSH;22</ConnectionType>
                        <URI>tsx-jump.corp.internal</URI>
                      </RoyalTerminalConnection>
                      <RoyalWebConnection>
                        <ID>conn-web-grafana</ID>
                        <Name>Grafana Observability</Name>
                        <URI>https://grafana.corp.internal:8443/d/main</URI>
                      </RoyalWebConnection>
                    </Objects>
                  </RoyalFolder>
                  <RoyalCredential>
                    <ID>cred-dom-admin</ID>
                    <Name>Corp Domain Admin</Name>
                    <UserName>CORP\Administrator</UserName>
                    <Password>R0yalSecret!2026</Password>
                  </RoyalCredential>
                  <RoyalRDSGateway>
                    <ID>gw-rdp-edge</ID>
                    <Name>RD Gateway Zurich</Name>
                    <URI>rdgw.zrh.corp.internal</URI>
                    <Port>443</Port>
                  </RoyalRDSGateway>
                  <RoyalTrash>
                    <ID>trash-bin-id</ID>
                    <Name>Trash</Name>
                    <Objects>
                      <RoyalRDSConnection>
                        <ID>conn-deleted-nested</ID>
                        <Name>Deleted Legacy Server</Name>
                        <URI>legacy-dead.corp.internal</URI>
                      </RoyalRDSConnection>
                    </Objects>
                  </RoyalTrash>
                </Objects>
              </RoyalDocument>
              <RoyalSSHConnection>
                <ID>conn-deleted-flat</ID>
                <ParentID>trash-bin-id</ParentID>
                <Name>Deleted Flat SSH</Name>
                <URI>10.0.99.99</URI>
              </RoyalSSHConnection>
            </RTSZDocument>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val gzippedRtsz = ByteArrayOutputStream().also { baos ->
            GZIPOutputStream(baos).use { gos -> gos.write(royalXml) }
        }.toByteArray()

        assertEquals(0x1F.toByte(), gzippedRtsz[0])
        assertEquals(0x8B.toByte(), gzippedRtsz[1])

        val detected = importer.detectFormat("corporate.rtsz", gzippedRtsz)
        assertEquals(DetectedExternalVaultFormat.ROYAL_TS_RTSZ_OR_RTSX, detected)

        val doc = importer.importExternalFormat(detected, gzippedRtsz, "corporate.rtsz")
        assertEquals("Corporate Infrastructure V7", doc.name)
        assertEquals(3, doc.folders.size)
        val winFolder = doc.folders.first { it.id == "fld-win" }
        val dcFolder = doc.folders.first { it.id == "fld-win-dc" }
        assertNull(winFolder.parentFolderId)
        assertEquals("fld-win", dcFolder.parentFolderId)

        // Verify <RoyalTrash> exclusions (both nested and flat ParentID)
        assertFalse(doc.connections.any { it.id == "conn-deleted-nested" })
        assertFalse(doc.connections.any { it.id == "conn-deleted-flat" })
        assertEquals(5, doc.connections.size)

        val rdp = doc.connections.first { it.id == "conn-dc01" }
        assertEquals(VaultProtocolType.RDP, rdp.protocol)
        assertEquals("dc01.corp.internal", rdp.host)
        assertEquals("fld-win-dc", rdp.parentFolderId)
        assertEquals("cred-dom-admin", rdp.inheritable.explicitCredentialId)

        val sshCore = doc.connections.first { it.id == "conn-ssh-core" }
        assertEquals(VaultProtocolType.SSH, sshCore.protocol)
        assertEquals("10.20.30.40", sshCore.host)
        assertEquals(2222, (sshCore.settings as ProtocolSpecificSettings.Ssh).port)

        val tsxSerial = doc.connections.first { it.id == "conn-tsx-serial" }
        assertEquals(VaultProtocolType.SERIAL, tsxSerial.protocol)
        assertEquals(115_200, (tsxSerial.settings as ProtocolSpecificSettings.SerialUart).baudRate)

        val tsxSsh = doc.connections.first { it.id == "conn-tsx-ssh" }
        assertEquals(VaultProtocolType.SSH, tsxSsh.protocol)
        assertEquals("tsx-jump.corp.internal", tsxSsh.host)

        val web = doc.connections.first { it.id == "conn-web-grafana" }
        assertEquals(VaultProtocolType.WEB_PAGE, web.protocol)
        assertEquals("https://grafana.corp.internal:8443/d/main", (web.settings as ProtocolSpecificSettings.WebPage).url)

        val cred = doc.credentials.first { it.id == "cred-dom-admin" }
        assertEquals("CORP", cred.domain)
        assertEquals("Administrator", cred.username)
        assertEquals("R0yalSecret!2026", cred.secretPassword)

        val gw = doc.gateways.first { it.id == "gw-rdp-edge" }
        assertEquals(GatewayTransportKind.HTTP_CONNECT_PROXY, gw.kind)
        assertEquals("rdgw.zrh.corp.internal", gw.host)
    }

    @Test
    fun importUtf16LeRoyalTsXmlWithDotNetControlCharactersAndRoyalJson() {
        val rawControlChar = '\u0008'
        val utf16XmlString = """
            <?xml version="1.0" encoding="utf-16"?>
            <RTSZDocument>
              <RoyalDocument>
                <ID>doc-utf16</ID>
                <Name>UTF16 Windows Export</Name>
              </RoyalDocument>
              <RoyalRDSConnection>
                <ID>conn-utf16-rdp</ID>
                <ParentID>doc-utf16</ParentID>
                <Name>WinServer&#x1B;ANSI-Note</Name>
                <ComputerName>win2025.corp.internal</ComputerName>
                <Notes>Contains raw backspace:${rawControlChar}end</Notes>
              </RoyalRDSConnection>
            </RTSZDocument>
        """.trimIndent()

        val utf16LeWithBom = ByteArrayOutputStream().also { baos ->
            baos.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            baos.write(utf16XmlString.toByteArray(StandardCharsets.UTF_16LE))
        }.toByteArray()

        val detectedUtf16 = importer.detectFormat("windows_export.rtsx", utf16LeWithBom)
        assertEquals(DetectedExternalVaultFormat.ROYAL_TS_RTSZ_OR_RTSX, detectedUtf16)

        val utf16Doc = importer.importExternalFormat(detectedUtf16, utf16LeWithBom, "windows_export.rtsx")
        assertEquals("UTF16 Windows Export", utf16Doc.name)
        assertEquals(1, utf16Doc.connections.size)
        assertEquals("win2025.corp.internal", utf16Doc.connections.single().host)

        // RoyalJSON (.rjson) detection & import
        val rjsonBytes = """
            {
              "Name": "Dynamic RoyalJSON Inventory",
              "Objects": [
                {
                  "Type": "Folder",
                  "ID": "rj-folder-1",
                  "Name": "Hybrid Cloud",
                  "Objects": [
                    {
                      "Type": "RemoteDesktopConnection",
                      "ID": "rj-rdp-1",
                      "Name": "SQL Primary Node",
                      "ComputerName": "10.80.1.25",
                      "Port": 3390,
                      "Username": "CORP\\sqladmin",
                      "Password": "SqlPass!2026"
                    }
                  ]
                }
              ]
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val detectedRjson = importer.detectFormat("dynamic.rjson", rjsonBytes)
        assertEquals(DetectedExternalVaultFormat.ROYAL_JSON, detectedRjson)

        val rjsonDoc = importer.importExternalFormat(detectedRjson, rjsonBytes, "dynamic.rjson")
        assertEquals(1, rjsonDoc.folders.size)
        assertEquals("Hybrid Cloud", rjsonDoc.folders.single().name)
        val rjsonConn = rjsonDoc.connections.single()
        assertEquals(VaultProtocolType.RDP, rjsonConn.protocol)
        assertEquals("10.80.1.25", rjsonConn.host)
        assertEquals(3390, (rjsonConn.settings as ProtocolSpecificSettings.Rdp).port)
        assertEquals(1, rjsonDoc.credentials.size)
        assertEquals("CORP", rjsonDoc.credentials.single().domain)
        assertEquals("sqladmin", rjsonDoc.credentials.single().username)

        // Random binary bytes must NEVER be misclassified as CSV even with a .csv extension
        val randomBinary = byteArrayOf(
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
            0x00, 0x11, 0x02, 0x03
        )
        assertEquals(DetectedExternalVaultFormat.UNKNOWN, importer.detectFormat("corrupt.csv", randomBinary))
        assertEquals(DetectedExternalVaultFormat.UNKNOWN, importer.detectFormat("unknown.bin", randomBinary))
    }

    @Test
    fun decryptWinZipAes256AndZipCryptoProtectedRtszArchives() {
        val sampleRtsx = """
            <?xml version="1.0" encoding="utf-8"?>
            <RTSZDocument>
              <RoyalDocument>
                <ID>doc-locked</ID>
                <Name>Lockdown Production Vault</Name>
              </RoyalDocument>
              <RoyalRDSConnection>
                <ID>conn-locked-rdp</ID>
                <ParentID>doc-locked</ParentID>
                <Name>PAM Jump Host</Name>
                <URI>pam.corp.internal</URI>
              </RoyalRDSConnection>
            </RTSZDocument>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val password = "LockdownPassword!2026".toCharArray()
        val aesZip = buildWinZipAes256Zip("document.rtsx", sampleRtsx, password)

        assertTrue(EncryptedZipExtractor.isEncryptedZip(aesZip))
        assertEquals(
            DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE,
            importer.detectFormat("locked_aes.rtsz", aesZip)
        )

        assertThrows(IllegalArgumentException::class.java) {
            importer.importExternalFormat(
                DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE,
                aesZip,
                "locked_aes.rtsz",
                "WrongPassword".toCharArray()
            )
        }

        val unlockedAesDoc = importer.importExternalFormat(
            DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE,
            aesZip,
            "locked_aes.rtsz",
            password.copyOf()
        )
        assertEquals("Lockdown Production Vault", unlockedAesDoc.name)
        assertEquals("pam.corp.internal", unlockedAesDoc.connections.single().host)

        // Also verify Traditional PKWARE ZipCrypto .rtsz decryption
        val zipCryptoBytes = buildZipCryptoStoreZip("document.rtsx", sampleRtsx, password)
        assertTrue(EncryptedZipExtractor.isEncryptedZip(zipCryptoBytes))
        assertEquals(
            DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE,
            importer.detectFormat("locked_zipcrypto.rtsz", zipCryptoBytes)
        )
        assertThrows(IllegalArgumentException::class.java) {
            importer.importExternalFormat(
                DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE,
                zipCryptoBytes,
                "locked_zipcrypto.rtsz",
                "BadPass".toCharArray()
            )
        }
        val unlockedZipCryptoDoc = importer.importExternalFormat(
            DetectedExternalVaultFormat.ROYAL_TS_ENCRYPTED_ARCHIVE,
            zipCryptoBytes,
            "locked_zipcrypto.rtsz",
            password.copyOf()
        )
        assertEquals("Lockdown Production Vault", unlockedZipCryptoDoc.name)
        assertEquals("pam.corp.internal", unlockedZipCryptoDoc.connections.single().host)
    }

    private fun buildWinZipAes256Zip(entryName: String, plainBytes: ByteArray, password: CharArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val deflated = try {
            deflater.setInput(plainBytes)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) out.write(buf, 0, n) else break
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }

        val salt = ByteArray(16) { idx -> (0xA0 + idx).toByte() }
        val derivedBits = (32 * 2 + 2) * 8
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(password, salt, 1000, derivedBits))
            .encoded
        val aesKey = derived.copyOfRange(0, 32)
        val hmacKey = derived.copyOfRange(32, 64)
        val verifier = derived.copyOfRange(64, 66)

        val ciphertext = EncryptedZipExtractor.aesCtrLittleEndianCrypt(aesKey, deflated)
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(hmacKey, "HmacSHA1"))
        }
        val authCode10 = mac.doFinal(ciphertext).copyOfRange(0, 10)

        val encryptedPayload = ByteArrayOutputStream().apply {
            write(salt)
            write(verifier)
            write(ciphertext)
            write(authCode10)
        }.toByteArray()

        val aesExtra = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x9901.toShort())
            putShort(7.toShort())
            putShort(2.toShort()) // AE-2
            put('A'.code.toByte())
            put('E'.code.toByte())
            put(3.toByte()) // AES-256
            putShort(8.toShort()) // Deflate
        }.array()

        return assembleSingleEntryZip(
            entryName = entryName,
            gpFlags = 0x0001,
            method = 99,
            crc32 = 0L,
            uncompressedSize = plainBytes.size,
            extra = aesExtra,
            payload = encryptedPayload
        )
    }

    private fun buildZipCryptoStoreZip(entryName: String, plainBytes: ByteArray, password: CharArray): ByteArray {
        val crc = CRC32().apply { update(plainBytes) }.value
        val keys = intArrayOf(0x12345678, 0x23456789, 0x34567890)
        fun crc32Byte(c0: Int, b: Int): Int {
            var c = c0 xor (b and 0xFF)
            repeat(8) {
                c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else (c ushr 1)
            }
            return c
        }
        fun updateKeys(byteVal: Int) {
            keys[0] = crc32Byte(keys[0], byteVal)
            keys[1] = (keys[1] + (keys[0] and 0xFF)) * 134775813 + 1
            keys[2] = crc32Byte(keys[2], keys[1] ushr 24)
        }
        fun streamByte(): Int {
            val temp = (keys[2] or 2) and 0xFFFF
            return ((temp * (temp xor 1)) ushr 8) and 0xFF
        }
        for (ch in password) updateKeys(ch.code and 0xFF)

        val headerPlain = ByteArray(12) { idx ->
            if (idx == 11) ((crc ushr 24) and 0xFFL).toByte() else (0x11 + idx).toByte()
        }
        val encryptedOut = ByteArray(12 + plainBytes.size)
        for (i in 0 until 12) {
            val p = headerPlain[i].toInt() and 0xFF
            val c = p xor streamByte()
            updateKeys(p)
            encryptedOut[i] = c.toByte()
        }
        for (i in plainBytes.indices) {
            val p = plainBytes[i].toInt() and 0xFF
            val c = p xor streamByte()
            updateKeys(p)
            encryptedOut[12 + i] = c.toByte()
        }

        return assembleSingleEntryZip(
            entryName = entryName,
            gpFlags = 0x0001,
            method = 0,
            crc32 = crc,
            uncompressedSize = plainBytes.size,
            extra = ByteArray(0),
            payload = encryptedOut
        )
    }

    private fun assembleSingleEntryZip(
        entryName: String,
        gpFlags: Int,
        method: Int,
        crc32: Long,
        uncompressedSize: Int,
        extra: ByteArray,
        payload: ByteArray
    ): ByteArray {
        val nameBytes = entryName.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream()

        val locHeader = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x04034B50)
            putShort(51.toShort())
            putShort(gpFlags.toShort())
            putShort(method.toShort())
            putShort(0x4A21.toShort())
            putShort(0x5421.toShort())
            putInt(crc32.toInt())
            putInt(payload.size)
            putInt(uncompressedSize)
            putShort(nameBytes.size.toShort())
            putShort(extra.size.toShort())
        }.array()
        out.write(locHeader)
        out.write(nameBytes)
        out.write(extra)
        out.write(payload)

        val cdOffset = out.size()
        val cenHeader = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x02014B50)
            putShort(51.toShort())
            putShort(51.toShort())
            putShort(gpFlags.toShort())
            putShort(method.toShort())
            putShort(0x4A21.toShort())
            putShort(0x5421.toShort())
            putInt(crc32.toInt())
            putInt(payload.size)
            putInt(uncompressedSize)
            putShort(nameBytes.size.toShort())
            putShort(extra.size.toShort())
            putShort(0.toShort())
            putShort(0.toShort())
            putShort(0.toShort())
            putInt(0)
            putInt(0) // localHeaderOffset = 0
        }.array()
        out.write(cenHeader)
        out.write(nameBytes)
        out.write(extra)
        val cdSize = out.size() - cdOffset

        val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x06054B50)
            putShort(0.toShort())
            putShort(0.toShort())
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(cdSize)
            putInt(cdOffset)
            putShort(0.toShort())
        }.array()
        out.write(eocd)
        return out.toByteArray()
    }

    @Test
    fun androidLibcoreExpatDocumentBuilderFactoryRejectingXercesFeaturesImportsRtszAndBlocksXxe() {
        // Reproduces Android ART's org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl (libcore Expat),
        // which ONLY supports http://xml.org/sax/features/namespaces and http://xml.org/sax/features/namespace-prefixes,
        // throwing ParserConfigurationException("http://apache.org/xml/features/disallow-doctype-decl") on Xerces URIs.
        fun createAndroidLikeDocumentBuilderFactory(): DocumentBuilderFactory {
            val delegate = DocumentBuilderFactory.newInstance()
            return object : DocumentBuilderFactory() {
                override fun newDocumentBuilder(): DocumentBuilder = delegate.newDocumentBuilder()

                override fun setAttribute(name: String, value: Any?) {
                    throw IllegalArgumentException(name)
                }

                override fun getAttribute(name: String): Any {
                    throw IllegalArgumentException(name)
                }

                override fun setFeature(name: String, value: Boolean) {
                    if (name != "http://xml.org/sax/features/namespaces" &&
                        name != "http://xml.org/sax/features/namespace-prefixes"
                    ) {
                        throw ParserConfigurationException(name)
                    }
                    delegate.setFeature(name, value)
                }

                override fun getFeature(name: String): Boolean {
                    if (name != "http://xml.org/sax/features/namespaces" &&
                        name != "http://xml.org/sax/features/namespace-prefixes"
                    ) {
                        throw ParserConfigurationException(name)
                    }
                    return delegate.getFeature(name)
                }
            }
        }

        val lsgXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <RTSZDocument>
              <RoyalDocument>
                <ID>7b6e9a10-4110-4c9a-9f12-101010101010</ID>
                <Name>LSG</Name>
              </RoyalDocument>
              <RoyalFolder>
                <ID>22222222-3333-4444-5555-666666666666</ID>
                <ParentID>7b6e9a10-4110-4c9a-9f12-101010101010</ParentID>
                <Name>LSG Server</Name>
              </RoyalFolder>
              <RoyalRDSConnection>
                <ID>aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee</ID>
                <ParentID>22222222-3333-4444-5555-666666666666</ParentID>
                <Name>DC01</Name>
                <URI>dc01.lsg.local</URI>
              </RoyalRDSConnection>
              <RoyalDynamicFolder>
                <ID>dfdfdfdf-1111-2222-3333-444444444444</ID>
                <ParentID>7b6e9a10-4110-4c9a-9f12-101010101010</ParentID>
                <Name>HTML Dynamic Inventory</Name>
                <Script><![CDATA[<!DOCTYPE html><html><body>RoyalJSON generator</body></html>]]></Script>
              </RoyalDynamicFolder>
            </RTSZDocument>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val lsgRtszGzip = ByteArrayOutputStream().also { baos ->
            GZIPOutputStream(baos).use { gz -> gz.write(lsgXml) }
        }.toByteArray()

        val rtszImporter = RtszDocumentImporter()
        val importedDoc = rtszImporter.importRtszBytesInternal(
            rawBytes = lsgRtszGzip,
            fallbackDocumentName = "LSG",
            factoryProvider = ::createAndroidLikeDocumentBuilderFactory
        )

        assertEquals("LSG", importedDoc.name)
        assertEquals(1, importedDoc.folders.size)
        assertEquals("LSG Server", importedDoc.folders.single().name)
        assertEquals(1, importedDoc.connections.size)
        assertEquals("dc01.lsg.local", importedDoc.connections.single().host)
        assertEquals(1, importedDoc.dynamicFolders.size)
        assertTrue(importedDoc.dynamicFolders.single().scriptOrEndpointPayload.contains("<!DOCTYPE html>"))

        // Verify XXE DOCTYPE / ENTITY payloads are strictly blocked even on Android's Expat factory
        val xxeXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE rtsz [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <RTSZDocument>
              <RoyalDocument>
                <ID>doc-xxe</ID>
                <Name>&xxe;</Name>
              </RoyalDocument>
            </RTSZDocument>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        assertThrows(IllegalArgumentException::class.java) {
            SecureXmlDomParser.parseXmlBytes(
                xmlBytes = xxeXml,
                factoryProvider = ::createAndroidLikeDocumentBuilderFactory
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            rtszImporter.importRtszBytesInternal(
                rawBytes = xxeXml,
                fallbackDocumentName = "XXE",
                factoryProvider = ::createAndroidLikeDocumentBuilderFactory
            )
        }
    }

    @Test
    fun importOfficialRoyalTsV7GuidEmptyCredentialMode3And4AndBase64KeyAndTemplate() {
        val sampleOpenSshPem = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
        val base64EncodedPem = Base64.getEncoder().encodeToString(sampleOpenSshPem.toByteArray(StandardCharsets.UTF_8))

        val v7Xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <RTSZDocument>
              <RoyalDocument>
                <ID>11111111-1111-1111-1111-111111111111</ID>
                <Name>LSG Enterprise V7</Name>
                <CredentialID>00000000-0000-0000-0000-000000000000</CredentialID>
                <SecureGatewayID>{00000000-0000-0000-0000-000000000000}</SecureGatewayID>
              </RoyalDocument>
              <RoyalFolder>
                <ID>f01de700-0000-4000-8000-000000000001</ID>
                <ParentID>11111111-1111-1111-1111-111111111111</ParentID>
                <Name>Tier-0 Domain Controllers</Name>
                <CredentialMode>3</CredentialMode>
                <CredentialID>c7ed0000-0000-4000-8000-000000000001</CredentialID>
              </RoyalFolder>
              <RoyalRDSConnection>
                <ID>c0000000-0000-4000-8000-000000000001</ID>
                <ParentID>f01de700-0000-4000-8000-000000000001</ParentID>
                <Name>LSG-DC01 (From Parent + Empty GUIDs)</Name>
                <URI>lsg-dc01.corp.local</URI>
                <CredentialFromParent>True</CredentialFromParent>
                <CredentialID>00000000-0000-0000-0000-000000000000</CredentialID>
                <RDSGatewayID>00000000-0000-0000-0000-000000000000</RDSGatewayID>
                <RDSGatewayFromParent>True</RDSGatewayFromParent>
                <PreConnectTaskID>00000000-0000-0000-0000-000000000000</PreConnectTaskID>
                <PostDisconnectTaskID>{00000000-0000-0000-0000-000000000000}</PostDisconnectTaskID>
                <ScaleFactor>150</ScaleFactor>
                <EnableGraphicsPipeline>False</EnableGraphicsPipeline>
                <GatewayHostName>rdgw.lsg.local</GatewayHostName>
                <TLSEncryption>False</TLSEncryption>
              </RoyalRDSConnection>
              <RoyalRDSConnection>
                <ID>c0000000-0000-4000-8000-000000000002</ID>
                <ParentID>f01de700-0000-4000-8000-000000000001</ParentID>
                <Name>LSG-SQL01 (Credential By Name Mode 4)</Name>
                <URI>lsg-sql01.corp.local</URI>
                <CredentialMode>4</CredentialMode>
                <CredentialName>LSG SQL Service Admin</CredentialName>
                <CredentialID>00000000-0000-0000-0000-000000000000</CredentialID>
              </RoyalRDSConnection>
              <RoyalRDSConnection>
                <ID>c0000000-0000-4000-8000-000000000003</ID>
                <ParentID>f01de700-0000-4000-8000-000000000001</ParentID>
                <Name>LSG-JUMP01 (Prompt Mode 3 Without CredentialID)</Name>
                <URI>lsg-jump01.corp.local</URI>
                <CredentialMode>3</CredentialMode>
                <CredentialID>00000000-0000-0000-0000-000000000000</CredentialID>
              </RoyalRDSConnection>
              <RoyalRDSConnection>
                <ID>7e391a7e-0000-4000-8000-000000000099</ID>
                <ParentID>11111111-1111-1111-1111-111111111111</ParentID>
                <Name>Standard LSG Windows 2025 Template</Name>
                <IsConnectionTemplate>True</IsConnectionTemplate>
                <DesktopWidth>2560</DesktopWidth>
                <DesktopHeight>1440</DesktopHeight>
              </RoyalRDSConnection>
              <RoyalCredential>
                <ID>c7ed0000-0000-4000-8000-000000000001</ID>
                <ParentID>11111111-1111-1111-1111-111111111111</ParentID>
                <Name>LSG Domain Admin</Name>
                <UserName>LSG\da_petros</UserName>
                <Password>Tier0Secret!2026</Password>
              </RoyalCredential>
              <RoyalCredential>
                <ID>c7ed0000-0000-4000-8000-000000000002</ID>
                <ParentID>11111111-1111-1111-1111-111111111111</ParentID>
                <Name>LSG SQL Service Admin</Name>
                <UserName>LSG\svc_sql</UserName>
                <Password>SqlVault!2026</Password>
              </RoyalCredential>
              <RoyalCredential>
                <ID>c7ed0000-0000-4000-8000-000000000003</ID>
                <ParentID>00000000-0000-0000-0000-000000000000</ParentID>
                <Name>LSG Linux Bastion Key</Name>
                <UserName>ubuntu</UserName>
                <KeyFileContent>$base64EncodedPem</KeyFileContent>
              </RoyalCredential>
            </RTSZDocument>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val doc = RtszDocumentImporter().importRtszBytes(v7Xml, "LSG")

        // Root empty GUIDs must NOT produce explicit overrides
        assertNull(doc.rootInheritable.explicitCredentialId)
        assertNull(doc.rootInheritable.explicitGatewayId)

        // Folder uses Royal TS CredentialMode=3 + explicit CredentialID -> OVERRIDE_EXPLICIT
        val folder = doc.folders.single()
        assertEquals(InheritanceMode.OVERRIDE_EXPLICIT, folder.inheritable.credentialMode)
        assertEquals("c7ed0000-0000-4000-8000-000000000001", folder.inheritable.explicitCredentialId)

        // Connection 1: CredentialFromParent=True + Guid.Empty -> INHERIT_FROM_PARENT (never "00000000-...")
        val dc01 = doc.connections.first { it.id == "c0000000-0000-4000-8000-000000000001" }
        assertEquals(InheritanceMode.INHERIT_FROM_PARENT, dc01.inheritable.credentialMode)
        assertNull(dc01.inheritable.explicitCredentialId)
        assertEquals(InheritanceMode.INHERIT_FROM_PARENT, dc01.inheritable.gatewayMode)
        assertNull(dc01.inheritable.explicitGatewayId)
        assertNull(dc01.inheritable.preConnectTaskId)
        assertNull(dc01.inheritable.postDisconnectTaskId)
        val dc01Rdp = dc01.settings as ProtocolSpecificSettings.Rdp
        assertEquals(150, dc01Rdp.desktopScaleFactorPercent)
        assertFalse(dc01Rdp.enableGfxPipeline)
        assertEquals("rdgw.lsg.local", dc01Rdp.rdGatewayHostname)
        assertFalse(dc01Rdp.tlsSecurity)

        // Connection 2: CredentialMode=4 + CredentialName (even though RoyalCredential appeared later in XML!)
        val sql01 = doc.connections.first { it.id == "c0000000-0000-4000-8000-000000000002" }
        assertEquals(InheritanceMode.OVERRIDE_EXPLICIT, sql01.inheritable.credentialMode)
        assertEquals("c7ed0000-0000-4000-8000-000000000002", sql01.inheritable.explicitCredentialId)

        // Connection 3: CredentialMode=3 without non-empty CredentialID -> PROMPT_AT_CONNECT
        val jump01 = doc.connections.first { it.id == "c0000000-0000-4000-8000-000000000003" }
        assertEquals(InheritanceMode.PROMPT_AT_CONNECT, jump01.inheritable.credentialMode)
        assertNull(jump01.inheritable.explicitCredentialId)

        // IsConnectionTemplate=True -> imported as ConnectionTemplate, excluded from active connections
        assertEquals(3, doc.connections.size)
        assertEquals(1, doc.templates.size)
        assertEquals("Standard LSG Windows 2025 Template", doc.templates.single().name)

        // Base64 PEM KeyFileContent -> decoded to UTF-8 PEM & classified as SSH_KEY_PAIR with null root parent
        val sshCred = doc.credentials.first { it.id == "c7ed0000-0000-4000-8000-000000000003" }
        assertNull(sshCred.parentFolderId)
        assertEquals(CredentialSourceKind.SSH_KEY_PAIR, sshCred.sourceKind)
        assertTrue(sshCred.privateKeyPem.contains("-----BEGIN OPENSSH PRIVATE KEY-----"))
    }
}
