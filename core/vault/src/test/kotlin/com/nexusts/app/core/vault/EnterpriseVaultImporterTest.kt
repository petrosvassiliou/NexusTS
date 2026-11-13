package com.nexusts.app.core.vault

import com.nexusts.app.core.vault.exporter.VaultDocumentExporter
import com.nexusts.app.core.vault.importer.DetectedExternalVaultFormat
import com.nexusts.app.core.vault.importer.EnterpriseVaultFormatImporter
import com.nexusts.app.core.vault.importer.RtszDocumentImporter
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
}
