package com.nexusts.app.core.vault

import com.nexusts.app.core.vault.exporter.VaultDocumentExporter
import com.nexusts.app.core.vault.importer.DetectedExternalVaultFormat
import com.nexusts.app.core.vault.importer.EnterpriseVaultFormatImporter
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun importRfc4180CsvAndExportXmlRoundTripForSerialAndContainerSettings() {
        val csvText = """
            name,folder,protocol,host,port,username,password,totp,notes
            "Juniper Edge, Rack 4",OOB Consoles,SERIAL,/dev/ttyACM0,9600,root,"Pass,With,Commas",JBSWY3DPEHPK3PXP,"9600 8N1 ""Console"""
            "Redis Cache Container",Containers,DOCKER_CONTAINER,prod/redis-primary,2376,ops,dockertoken,,Live container exec
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

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
}
