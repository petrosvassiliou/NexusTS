package com.nexusts.app.core.vault

import com.nexusts.app.core.vault.crypto.KdfSecurityParameters
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.crypto.VaultAuthenticationException
import com.nexusts.app.core.vault.importer.RtszDocumentImporter
import com.nexusts.app.core.vault.inheritance.PropertyInheritanceResolver
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
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultCryptoAndInheritanceTest {

    @Test
    fun `nxts v2 argon2id aes256gcm seals and unseals roundtrip and rejects wrong passphrase`() {
        val serializer = NxtsVaultSerializer(
            KdfSecurityParameters(memoryKiB = 8192, iterations = 1, parallelism = 1)
        )
        val doc = NxtsVaultDocument(
            name = "ZeroKnowledgeTest.nxts",
            revision = 7L,
            connections = listOf(
                VaultConnection(
                    name = "prod-bastion",
                    host = "10.0.0.5",
                    protocol = VaultProtocolType.SSH,
                    settings = ProtocolSpecificSettings.Ssh()
                )
            )
        )

        val sealed = serializer.sealDocument(doc, "super-secret-master-key".toCharArray())
        assertEquals(7L, serializer.inspectUnencryptedRevisionHeader(sealed))

        val restored = serializer.unsealDocument(sealed, "super-secret-master-key".toCharArray())
        assertEquals(doc.name, restored.name)
        assertEquals(1, restored.connections.size)
        assertEquals("10.0.0.5", restored.connections.first().host)

        assertThrows(VaultAuthenticationException::class.java) {
            serializer.unsealDocument(sealed, "incorrect-passphrase".toCharArray())
        }
    }

    @Test
    fun `property inheritance cascades credentials from ancestor folder to child connection`() {
        val rootCred = VaultCredential(id = "cred-1", name = "Root Ops Key", username = "sre")
        val parentFolder = VaultFolder(
            id = "f-parent",
            name = "Production EU",
            inheritable = InheritableProperties(
                credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                explicitCredentialId = "cred-1",
                environmentBadge = "PRODUCTION"
            )
        )
        val childFolder = VaultFolder(
            id = "f-child",
            parentFolderId = "f-parent",
            name = "Kubernetes Nodes",
            inheritable = InheritableProperties(credentialMode = InheritanceMode.INHERIT_FROM_PARENT)
        )
        val connection = VaultConnection(
            id = "c-1",
            parentFolderId = "f-child",
            name = "k8s-worker-01",
            host = "10.10.1.20",
            protocol = VaultProtocolType.SSH,
            inheritable = InheritableProperties(credentialMode = InheritanceMode.INHERIT_FROM_PARENT),
            settings = ProtocolSpecificSettings.Ssh()
        )
        val doc = NxtsVaultDocument(
            name = "CorpVault",
            folders = listOf(parentFolder, childFolder),
            connections = listOf(connection),
            credentials = listOf(rootCred)
        )

        val resolver = PropertyInheritanceResolver()
        val profile = resolver.resolveEffectiveProfile(doc, connection)
        assertNotNull(profile.effectiveCredential)
        assertEquals("sre", profile.effectiveCredential?.username)
        assertEquals("Production EU", profile.credentialSourceLabel)
        assertEquals("PRODUCTION", profile.effectiveEnvironmentBadge)
    }

    @Test
    fun `royal ts rtsz xml importer maps folders credentials and connections`() {
        val sampleRtsx = """
            <?xml version="1.0" encoding="utf-8"?>
            <RTSZDocument>
                <RoyalDocument>
                    <ID>doc-1</ID>
                    <Name>Imported macOS Vault</Name>
                </RoyalDocument>
                <RoyalCredential>
                    <ID>cred-99</ID>
                    <Name>AdminCred</Name>
                    <UserName>corp_admin</UserName>
                    <Password>s3cr3t</Password>
                </RoyalCredential>
                <RoyalFolder>
                    <ID>fold-1</ID>
                    <ParentID>doc-1</ParentID>
                    <Name>Datacenter</Name>
                    <CredentialMode>2</CredentialMode>
                    <CredentialID>cred-99</CredentialID>
                </RoyalFolder>
                <RoyalRDSConnection>
                    <ID>rdp-1</ID>
                    <ParentID>fold-1</ParentID>
                    <Name>Core-Jumpbox</Name>
                    <URI>jump.corp.internal</URI>
                    <RDPPort>3389</RDPPort>
                </RoyalRDSConnection>
            </RTSZDocument>
        """.trimIndent().encodeToByteArray()

        val imported = RtszDocumentImporter().importRtszBytes(sampleRtsx)
        assertEquals("Imported macOS Vault", imported.name)
        assertEquals(1, imported.folders.size)
        assertEquals(1, imported.credentials.size)
        assertEquals(1, imported.connections.size)
        assertEquals(VaultProtocolType.RDP, imported.connections.first().protocol)
    }

    @Test
    fun `rfc6238 totp generator and custom property inheritance expand in automation tasks`() {
        // RFC 6238 Appendix B SHA1 vector: secret = "12345678901234567890" -> Base32 "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        // At T = 59s, 6-digit HOTP(K, 1) = 287082
        val vectorSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val snap = com.nexusts.app.core.vault.credentials.TotpCodeGenerator.generateTotp(
            base32Secret = vectorSecret,
            epochMillis = 59_000L,
            periodSeconds = 30,
            digits = 6
        )
        assertNotNull(snap)
        assertEquals("287082", snap?.code)
        assertEquals(1, snap?.secondsRemaining)

        val cred = VaultCredential(
            id = "c-totp",
            name = "Ops 2FA",
            username = "ops",
            domain = "CORP",
            totpSecretBase32 = vectorSecret
        )
        val folder = VaultFolder(
            id = "f-1",
            name = "Tier-0",
            inheritable = InheritableProperties(
                credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                explicitCredentialId = "c-totp",
                displayColorAccentHex = "#3CE6B8",
                customProperties = listOf(
                    com.nexusts.app.core.vault.model.CustomPropertyEntry("Rack", "EU-RACK-04"),
                    com.nexusts.app.core.vault.model.CustomPropertyEntry("EnvTier", "Gold")
                )
            )
        )
        val conn = VaultConnection(
            id = "conn-1",
            parentFolderId = "f-1",
            name = "bastion-01",
            host = "10.0.1.9",
            protocol = VaultProtocolType.SSH,
            inheritable = InheritableProperties(
                credentialMode = InheritanceMode.INHERIT_FROM_PARENT,
                customProperties = listOf(
                    com.nexusts.app.core.vault.model.CustomPropertyEntry("EnvTier", "Platinum")
                )
            ),
            settings = ProtocolSpecificSettings.Ssh()
        )
        val doc = NxtsVaultDocument(
            name = "ParityTest.nxts",
            folders = listOf(folder),
            connections = listOf(conn),
            credentials = listOf(cred)
        )
        val profile = PropertyInheritanceResolver().resolveEffectiveProfile(doc, conn)
        assertEquals("#3CE6B8", profile.effectiveColorAccentHex)
        assertEquals("EU-RACK-04", profile.effectiveCustomProperties["Rack"]?.value)
        assertEquals("Platinum", profile.effectiveCustomProperties["EnvTier"]?.value)

        val rendered = com.nexusts.app.core.vault.automation.AutomationTaskEngine().renderCommandTask(
            com.nexusts.app.core.vault.model.CommandTask(
                name = "Audit",
                commandTemplate = "echo \$EffectiveDomain\$/\$EffectiveUsername\$ rack=\$CustomProperty.Rack\$ tier=\$CustomProperty.EnvTier\$",
                appendNewline = false
            ),
            profile
        )
        assertEquals("echo CORP/ops rack=EU-RACK-04 tier=Platinum", rendered)
    }
}
