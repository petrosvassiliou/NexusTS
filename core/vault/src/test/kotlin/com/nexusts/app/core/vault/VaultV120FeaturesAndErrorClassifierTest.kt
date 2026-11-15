package com.nexusts.app.core.vault

import com.nexusts.app.core.vault.error.NexusErrorCategory
import com.nexusts.app.core.vault.error.NexusErrorClassifier
import com.nexusts.app.core.vault.error.VaultHierarchySanitizer
import com.nexusts.app.core.vault.importer.RoyalTsImportMerger
import com.nexusts.app.core.vault.importer.RoyalTsImportPlacementMode
import com.nexusts.app.core.vault.importer.RtszDocumentImporter
import com.nexusts.app.core.vault.inheritance.PropertyInheritanceResolver
import com.nexusts.app.core.vault.model.CommandTask
import com.nexusts.app.core.vault.model.ConnectionTemplate
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.KeySequenceTask
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.SmartFolder
import com.nexusts.app.core.vault.model.SmartFolderFilter
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.core.vault.model.VaultTodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.zip.ZipException
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLHandshakeException

class VaultV120FeaturesAndErrorClassifierTest {

    @Test
    fun sanitizerStripsLegacyAndroidAndMacDemoEntriesAndAbsorbsRootCredentialsFolder() {
        val mixedDoc = NxtsVaultDocument(
            documentId = "nxts-enterprise-core",
            name = "Production Infrastructure",
            folders = listOf(
                VaultFolder(id = "folder-prod", name = "Production Tier"), // empty legacy demo folder -> stripped
                VaultFolder(id = "folder-prod-core", name = "macOS Prod Demo"), // referenced by user conn -> preserved!
                VaultFolder(id = "fld-royal-creds", name = "Credentials"), // empty root Credentials -> absorbed!
                VaultFolder(id = "fld-user-eu", name = "EU Customer Cloud")
            ),
            connections = listOf(
                VaultConnection(
                    id = "conn-win-dc01",
                    name = "Demo DC01",
                    host = "10.0.0.1",
                    protocol = VaultProtocolType.RDP,
                    settings = ProtocolSpecificSettings.Rdp()
                ),
                VaultConnection(
                    id = "conn-bastion-01",
                    name = "macOS Demo Bastion",
                    host = "10.0.0.2",
                    protocol = VaultProtocolType.SSH,
                    settings = ProtocolSpecificSettings.Ssh()
                ),
                VaultConnection(
                    id = "conn-user-inside-demo-folder",
                    parentFolderId = "folder-prod-core",
                    name = "Customer-SQL-01",
                    host = "10.50.1.10",
                    protocol = VaultProtocolType.RDP,
                    settings = ProtocolSpecificSettings.Rdp()
                ),
                VaultConnection(
                    id = "conn-user-eu",
                    parentFolderId = "fld-user-eu",
                    name = "Customer-App-01",
                    host = "10.50.2.20",
                    protocol = VaultProtocolType.SSH,
                    settings = ProtocolSpecificSettings.Ssh()
                )
            ),
            credentials = listOf(
                VaultCredential(id = "cred-domain-admin", name = "Demo Domain Admin", username = "admin"),
                VaultCredential(id = "cred-prod-ops", name = "macOS Demo Cred", username = "ops"),
                VaultCredential(
                    id = "cred-user-real",
                    parentFolderId = "fld-royal-creds",
                    name = "Customer Domain Admin",
                    username = "CORP\\petros"
                )
            ),
            smartFolders = listOf(
                SmartFolder(
                    id = "smart-linux-prod",
                    name = "All Production Linux Nodes",
                    filter = SmartFolderFilter()
                ),
                SmartFolder(
                    id = "smart-user-custom",
                    name = "Customer SQL Servers",
                    filter = SmartFolderFilter()
                )
            ),
            commandTasks = listOf(
                CommandTask(id = "task-k8s-pods", name = "Cluster Pod Health Audit", commandTemplate = "kubectl get pods"),
                CommandTask(id = "task-user-uptime", name = "Check Uptime", commandTemplate = "uptime")
            ),
            keySequenceTasks = listOf(
                KeySequenceTask(id = "macro-win-elevate", name = "Windows Domain Elevated Sign-In + 2FA Macro", macroSequence = "{ENTER}"),
                KeySequenceTask(id = "macro-user-1", name = "Custom Login", macroSequence = "{TAB}{ENTER}")
            ),
            templates = listOf(
                ConnectionTemplate(
                    id = "tpl-hardened-rdp",
                    name = "Hardened RDP",
                    protocol = VaultProtocolType.RDP,
                    settings = ProtocolSpecificSettings.Rdp()
                ),
                ConnectionTemplate(
                    id = "tpl-user-ssh",
                    name = "Corp SSH Standard",
                    protocol = VaultProtocolType.SSH,
                    settings = ProtocolSpecificSettings.Ssh()
                )
            ),
            todoItems = listOf(
                VaultTodoItem(id = "todo-rotate-ssh", title = "Enable Kerberos ticket forwarding on EU Bastion"),
                VaultTodoItem(id = "todo-user-1", title = "Patch Customer-SQL-01 Sunday")
            )
        )

        val cleaned = VaultHierarchySanitizer.sanitizeVaultDocument(mixedDoc)

        assertEquals("nxts-default-vault", cleaned.documentId)
        assertEquals("NexusTS Vault", cleaned.name)

        // Unreferenced demo folder & root "Credentials" folder removed; user folder & user-referenced folder preserved
        assertEquals(setOf("folder-prod-core", "fld-user-eu"), cleaned.folders.map { it.id }.toSet())
        assertEquals(setOf("conn-user-inside-demo-folder", "conn-user-eu"), cleaned.connections.map { it.id }.toSet())

        // User credential preserved with normalized null parentFolderId (lives in system Credentials container)
        assertEquals(1, cleaned.credentials.size)
        assertEquals("cred-user-real", cleaned.credentials.single().id)
        assertNull(cleaned.credentials.single().parentFolderId)

        assertEquals(listOf("smart-user-custom"), cleaned.smartFolders.map { it.id })
        assertEquals(listOf("task-user-uptime"), cleaned.commandTasks.map { it.id })
        assertEquals(listOf("macro-user-1"), cleaned.keySequenceTasks.map { it.id })
        assertEquals(listOf("tpl-user-ssh"), cleaned.templates.map { it.id })
        assertEquals(listOf("todo-user-1"), cleaned.todoItems.map { it.id })

        assertTrue(VaultHierarchySanitizer.isReservedCredentialsFolderName("Credentials"))
        assertTrue(VaultHierarchySanitizer.isReservedCredentialsFolderName("  credentials  "))
        assertTrue(VaultHierarchySanitizer.isReservedCredentialsFolderName("Anmeldeinformationen"))
        assertFalse(VaultHierarchySanitizer.isReservedCredentialsFolderName("Production Servers"))
    }

    @Test
    fun royalTsImportPlacementSupportsNewFolderRootUnwrapAndReplaceExisting() {
        val rtszXmlWithBuiltInContainers = """
            <?xml version="1.0" encoding="utf-8"?>
            <RTSZDocument>
              <RoyalDocument>
                <ID>doc-royal-1</ID>
                <Name>Acme Datacenter</Name>
              </RoyalDocument>
              <RoyalFolder>
                <ID>fld-royal-connections-root</ID>
                <ParentID>doc-royal-1</ParentID>
                <Name>Connections</Name>
              </RoyalFolder>
              <RoyalFolder>
                <ID>fld-royal-credentials-root</ID>
                <ParentID>doc-royal-1</ParentID>
                <Name>Credentials</Name>
              </RoyalFolder>
              <RoyalFolder>
                <ID>fld-royal-berlin</ID>
                <ParentID>fld-royal-connections-root</ParentID>
                <Name>Berlin Tier</Name>
              </RoyalFolder>
              <RoyalRDSConnection>
                <ID>conn-royal-dc01</ID>
                <ParentID>fld-royal-berlin</ParentID>
                <Name>BER-DC01</Name>
                <URI>10.77.1.10</URI>
              </RoyalRDSConnection>
              <RoyalSSHConnection>
                <ID>conn-royal-gw01</ID>
                <ParentID>fld-royal-connections-root</ParentID>
                <Name>BER-GW01</Name>
                <URI>10.77.1.1</URI>
              </RoyalSSHConnection>
              <RoyalCredential>
                <ID>cred-royal-da</ID>
                <ParentID>fld-royal-credentials-root</ParentID>
                <Name>Berlin Domain Admin</Name>
                <UserName>ACME\da_berlin</UserName>
                <Password>TopSecret#1</Password>
              </RoyalCredential>
            </RTSZDocument>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val importedDoc = RtszDocumentImporter().importRtszBytes(rtszXmlWithBuiltInContainers, "Acme")

        // Verify RtszDocumentImporter absorbed RoyalFolder "Credentials" into system credentials
        assertFalse(importedDoc.folders.any { it.name.equals("Credentials", ignoreCase = true) })
        assertEquals(1, importedDoc.credentials.size)
        assertNull(importedDoc.credentials.single().parentFolderId)

        val existingDoc = NxtsVaultDocument(
            documentId = "vault-existing",
            name = "Existing Fleet",
            revision = 4L,
            folders = listOf(VaultFolder(id = "fld-existing", name = "Existing Munich", sortOrder = 10)),
            connections = listOf(
                VaultConnection(
                    id = "conn-existing",
                    parentFolderId = "fld-existing",
                    name = "MUC-APP01",
                    host = "10.10.0.5",
                    protocol = VaultProtocolType.SSH,
                    settings = ProtocolSpecificSettings.Ssh()
                )
            )
        )

        // Mode 1: CREATE_NEW_FOLDER -> wraps imported items in new folder, unwraps built-in "Connections", keeps existing
        val inNewFolder = RoyalTsImportMerger.applyPlacement(
            existingDoc = existingDoc,
            rawImportedDoc = importedDoc,
            mode = RoyalTsImportPlacementMode.CREATE_NEW_FOLDER,
            newFolderName = "Acme Imported"
        )
        val wrapper = inNewFolder.folders.firstOrNull { it.name == "Acme Imported" && it.parentFolderId == null }
        assertNotNull(wrapper)
        assertFalse(inNewFolder.folders.any { it.name.equals("Connections", ignoreCase = true) })
        assertTrue(inNewFolder.folders.any { it.id == "fld-existing" })
        val berlinInWrapper = inNewFolder.folders.first { it.name == "Berlin Tier" }
        assertEquals(wrapper!!.id, berlinInWrapper.parentFolderId)
        val gwInWrapper = inNewFolder.connections.first { it.name == "BER-GW01" }
        assertEquals(wrapper.id, gwInWrapper.parentFolderId)
        assertEquals(3, inNewFolder.connections.size)

        // Importing the same Royal TS file a SECOND time into another folder remaps colliding IDs instead of clobbering!
        val secondImport = RoyalTsImportMerger.applyPlacement(
            existingDoc = inNewFolder,
            rawImportedDoc = importedDoc,
            mode = RoyalTsImportPlacementMode.CREATE_NEW_FOLDER,
            newFolderName = "Acme Imported DR Copy"
        )
        assertEquals(5, secondImport.connections.size)
        val berlinFolders = secondImport.folders.filter { it.name == "Berlin Tier" }
        assertEquals(2, berlinFolders.size)
        assertNotEquals(berlinFolders[0].id, berlinFolders[1].id)

        // Mode 2: PLACE_AT_ROOT -> places Berlin Tier & BER-GW01 at root alongside Existing Munich
        val atRoot = RoyalTsImportMerger.applyPlacement(
            existingDoc = existingDoc,
            rawImportedDoc = importedDoc,
            mode = RoyalTsImportPlacementMode.PLACE_AT_ROOT
        )
        assertTrue(atRoot.folders.any { it.id == "fld-existing" && it.parentFolderId == null })
        assertTrue(atRoot.folders.any { it.name == "Berlin Tier" && it.parentFolderId == null })
        assertTrue(atRoot.connections.any { it.name == "BER-GW01" && it.parentFolderId == null })
        assertEquals(3, atRoot.connections.size)

        // Mode 3: REPLACE_EXISTING -> deletes all existing connection folders & connections and replaces with imported
        val replaced = RoyalTsImportMerger.applyPlacement(
            existingDoc = existingDoc,
            rawImportedDoc = importedDoc,
            mode = RoyalTsImportPlacementMode.REPLACE_EXISTING
        )
        assertFalse(replaced.folders.any { it.id == "fld-existing" })
        assertFalse(replaced.connections.any { it.id == "conn-existing" })
        assertEquals(listOf("Berlin Tier"), replaced.folders.map { it.name })
        assertEquals(setOf("BER-DC01", "BER-GW01"), replaced.connections.map { it.name }.toSet())
        assertEquals(1, replaced.credentials.size)
    }

    @Test
    fun smartFolderEvaluatesSubtreeScopeExcludeTagsMultiProtocolAndValidatesRegex() {
        val resolver = PropertyInheritanceResolver()

        assertNull(resolver.validateSmartFolderRegex("^prod-(eu|us)-\\d+$"))
        assertNotNull(resolver.validateSmartFolderRegex("[unclosed-bracket"))

        val euRoot = VaultFolder(
            id = "fld-eu",
            name = "Europe",
            inheritable = InheritableProperties(environmentBadge = "PROD")
        )
        val euSub = VaultFolder(
            id = "fld-eu-k8s",
            parentFolderId = "fld-eu",
            name = "EU K8s Workers"
        )
        val usRoot = VaultFolder(
            id = "fld-us",
            name = "US East",
            inheritable = InheritableProperties(environmentBadge = "PROD")
        )

        val connEuMatch = VaultConnection(
            id = "c-eu-1",
            parentFolderId = "fld-eu-k8s",
            name = "eu-worker-01",
            host = "10.20.1.11",
            protocol = VaultProtocolType.SSH,
            tags = listOf("linux", "k8s"),
            settings = ProtocolSpecificSettings.Ssh()
        )
        val connEuExcludedTag = VaultConnection(
            id = "c-eu-decomm",
            parentFolderId = "fld-eu-k8s",
            name = "eu-worker-legacy",
            host = "10.20.1.99",
            protocol = VaultProtocolType.SSH,
            tags = listOf("linux", "k8s", "deprecated"),
            settings = ProtocolSpecificSettings.Ssh()
        )
        val connEuRdp = VaultConnection(
            id = "c-eu-rdp",
            parentFolderId = "fld-eu",
            name = "eu-jump-win",
            host = "10.20.0.10",
            protocol = VaultProtocolType.RDP,
            osFamily = "Windows",
            tags = listOf("jump"),
            settings = ProtocolSpecificSettings.Rdp()
        )
        val connUsOutsideScope = VaultConnection(
            id = "c-us-1",
            parentFolderId = "fld-us",
            name = "us-worker-01",
            host = "10.30.1.11",
            protocol = VaultProtocolType.SSH,
            tags = listOf("linux", "k8s"),
            settings = ProtocolSpecificSettings.Ssh()
        )

        val doc = NxtsVaultDocument(
            name = "Smart Folder Test Vault",
            folders = listOf(euRoot, euSub, usRoot),
            connections = listOf(connEuMatch, connEuExcludedTag, connEuRdp, connUsOutsideScope)
        )

        val scopedSmart = SmartFolder(
            id = "sf-eu-active",
            name = "Active EU Fleet (SSH + RDP, No Deprecated)",
            filter = SmartFolderFilter(
                includeProtocols = listOf(VaultProtocolType.SSH, VaultProtocolType.RDP),
                excludeTags = listOf("deprecated"),
                scopeFolderId = "fld-eu",
                environmentFilter = "PROD"
            )
        )

        val matched = resolver.evaluateSmartFolder(doc, scopedSmart)
        assertEquals(listOf("c-eu-1", "c-eu-rdp"), matched.map { it.id })

        // Invalid regex safely returns empty list instead of throwing or matching everything
        val brokenSmart = SmartFolder(
            id = "sf-broken",
            name = "Broken Regex",
            filter = SmartFolderFilter(hostPatternRegex = "(unbalanced[")
        )
        assertTrue(resolver.evaluateSmartFolder(doc, brokenSmart).isEmpty())
    }

    @Test
    fun nexusErrorClassifierMapsNetworkAuthTlsPortCryptoAndFreeRdpCodesAccurately() {
        val cryptoErr = NexusErrorClassifier.classifyThrowable(
            AEADBadTagException("Tag mismatch!"),
            "Unlock Vault"
        )
        assertEquals(NexusErrorCategory.VAULT_CRYPTO_OR_CORRUPTION, cryptoErr.category)
        assertTrue(cryptoErr.formatDiagnosticReport().contains("VAULT_CRYPTO_OR_CORRUPTION"))

        val dnsErr = NexusErrorClassifier.classifyThrowable(
            UnknownHostException("dc01.nonexistent.internal"),
            "SSH Connect",
            recoveryConnectionId = "conn-1"
        )
        assertEquals(NexusErrorCategory.NETWORK_UNREACHABLE, dnsErr.category)
        assertEquals("conn-1", dnsErr.recoveryConnectionId)

        val refusedErr = NexusErrorClassifier.classifyThrowable(
            ConnectException("Connection refused"),
            "TCP Connect"
        )
        assertEquals(NexusErrorCategory.NETWORK_UNREACHABLE, refusedErr.category)

        val tlsErr = NexusErrorClassifier.classifyThrowable(
            SSLHandshakeException("PKIX path building failed: untrusted certificate"),
            "HTTPS Connect"
        )
        assertEquals(NexusErrorCategory.TLS_CERTIFICATE, tlsErr.category)

        val portErr = NexusErrorClassifier.classifyThrowable(
            BindException("Address already in use (EADDRINUSE)"),
            "SSH Port Forward"
        )
        assertEquals(NexusErrorCategory.PORT_CONFLICT, portErr.category)

        val authErr = NexusErrorClassifier.classifyThrowable(
            IllegalStateException("SSH authentication failed: permission denied (publickey,password)"),
            "SSH Handshake"
        )
        assertEquals(NexusErrorCategory.AUTHENTICATION_FAILED, authErr.category)

        val zipErr = NexusErrorClassifier.classifyThrowable(
            ZipException("invalid CEN header (corrupted zip)"),
            "Import Archive"
        )
        assertEquals(NexusErrorCategory.IMPORT_EXPORT_FORMAT, zipErr.category)

        // Native FreeRDP error string classification
        val rdpAuth = NexusErrorClassifier.classifyRdpError(
            host = "10.0.1.10",
            port = 3389,
            rawNativeError = "[0x00020009] ERRCONNECT_AUTHENTICATION_FAILED: NLA CredSSP logon rejected",
            connectionId = "rdp-1"
        )
        assertEquals(NexusErrorCategory.AUTHENTICATION_FAILED, rdpAuth.category)
        assertEquals("rdp-1", rdpAuth.recoveryConnectionId)

        val rdpDns = NexusErrorClassifier.classifyRdpError(
            host = "badhost.local",
            port = 3389,
            rawNativeError = "[0x00020014] ERRCONNECT_DNS_NAME_NOT_FOUND"
        )
        assertEquals(NexusErrorCategory.NETWORK_UNREACHABLE, rdpDns.category)

        val rdpTls = NexusErrorClassifier.classifyRdpError(
            host = "10.0.1.20",
            port = 3389,
            rawNativeError = "[0x0002000C] ERRCONNECT_TLS_CONNECT_FAILED"
        )
        assertEquals(NexusErrorCategory.TLS_CERTIFICATE, rdpTls.category)

        val rdpLocked = NexusErrorClassifier.classifyRdpError(
            host = "10.0.1.30",
            port = 3389,
            rawNativeError = "[0x00020015] ERRCONNECT_ACCOUNT_LOCKED_OUT"
        )
        assertEquals(NexusErrorCategory.AUTHENTICATION_FAILED, rdpLocked.category)

        val rdpInternal = NexusErrorClassifier.classifyRdpError(
            host = "10.2.200.27",
            port = 3389,
            rawNativeError = "INTERNAL: freerdp_context_new() initialization failed."
        )
        assertEquals(NexusErrorCategory.INTERNAL, rdpInternal.category)

        val rdpCertChanged = NexusErrorClassifier.classifyRdpError(
            host = "10.2.200.27",
            port = 3389,
            rawNativeError = "TLS_CERTIFICATE_CHANGED: X.509 certificate fingerprint changed for 10.2.200.27:3389"
        )
        assertEquals(NexusErrorCategory.TLS_CERTIFICATE, rdpCertChanged.category)

        val rdpHybridRequired = NexusErrorClassifier.classifyRdpError(
            host = "10.2.200.27",
            port = 3389,
            rawNativeError = "HYBRID_REQUIRED_BY_SERVER (0x0002000C)"
        )
        assertEquals(NexusErrorCategory.AUTHENTICATION_FAILED, rdpHybridRequired.category)
    }
}
