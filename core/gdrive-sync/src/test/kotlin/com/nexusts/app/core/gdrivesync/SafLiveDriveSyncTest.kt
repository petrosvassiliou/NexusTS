package com.nexusts.app.core.gdrivesync

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.nexusts.app.core.gdrivesync.auth.GoogleDriveAuthManager
import com.nexusts.app.core.gdrivesync.cache.CachedVaultRecord
import com.nexusts.app.core.gdrivesync.cache.SyncedVaultDao
import com.nexusts.app.core.gdrivesync.cache.VaultSnapshotRecord
import com.nexusts.app.core.gdrivesync.sync.DriveStorageZone
import com.nexusts.app.core.gdrivesync.sync.GoogleDriveVaultSyncEngine
import com.nexusts.app.core.gdrivesync.sync.SafByteTransport
import com.nexusts.app.core.gdrivesync.sync.SafDriveSyncOutcome
import com.nexusts.app.core.gdrivesync.sync.SafLiveGoogleDriveBridge
import com.nexusts.app.core.gdrivesync.sync.VaultSyncResult
import com.nexusts.app.core.vault.crypto.KdfSecurityParameters
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultProtocolType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafLiveDriveSyncTest {

    private class InMemoryDriveSafTransport : SafByteTransport {
        val blobs = ConcurrentHashMap<String, ByteArray>()
        var writeCount = 0

        override fun readBytes(uriString: String): ByteArray =
            blobs[uriString]?.copyOf() ?: ByteArray(0)

        override fun writeBytes(uriString: String, payload: ByteArray) {
            writeCount++
            blobs[uriString] = payload.copyOf()
        }

        override fun resolveDisplayName(uriString: String): String =
            "NexusCorp-LiveVault.nxts"
    }

    private class InMemorySyncedVaultDao : SyncedVaultDao {
        val records = LinkedHashMap<String, CachedVaultRecord>()
        val snapshots = mutableListOf<VaultSnapshotRecord>()

        override suspend fun listAllVaults(): List<CachedVaultRecord> =
            records.values.sortedBy { it.displayName }

        override suspend fun getByDocumentId(documentId: String): CachedVaultRecord? =
            records[documentId]

        override suspend fun listDirtyVaults(): List<CachedVaultRecord> =
            records.values.filter { it.hasPendingLocalEdits }

        override suspend fun upsertVault(record: CachedVaultRecord) {
            records[record.documentId] = record
        }

        override suspend fun insertSnapshot(snapshot: VaultSnapshotRecord) {
            snapshots += snapshot
        }

        override suspend fun listSnapshots(documentId: String, limit: Int): List<VaultSnapshotRecord> =
            snapshots.filter { it.documentId == documentId }.take(limit)
    }

    @Test
    fun defaultProvisionedDriveFolderAndEncryptedPassphraseHelpersWorkCleanly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authManager = GoogleDriveAuthManager(context)

        assertEquals(
            "17C98pGqQUAoQwQ-OSLUHPj4_dtb4_wtS",
            GoogleDriveAuthManager.DEFAULT_PROVISIONED_DRIVE_FOLDER_ID
        )
        assertEquals(
            "17C98pGqQUAoQwQ-OSLUHPj4_dtb4_wtS",
            authManager.resolveConfiguredSharedFolderId()
        )

        assertNull(authManager.readVaultPassphraseOrNull("doc-1"))
        val secret = "ZeroKnowledge#Pass!2026".toCharArray()
        authManager.saveVaultPassphrasePreference(secret, rememberPassphrase = true, documentId = "doc-1")
        assertTrue(authManager.isRememberVaultPassphraseEnabled())

        val rawPrefs = context.getSharedPreferences("nexusts_oauth_config", Context.MODE_PRIVATE)
        assertFalse(rawPrefs.all.values.any { it.toString().contains("ZeroKnowledge#Pass!2026") })

        val restored = authManager.readVaultPassphraseOrNull("doc-1")
        assertNotNull(restored)
        assertArrayEquals(secret, restored)

        authManager.clearSavedVaultPassphrase()
        assertFalse(authManager.isRememberVaultPassphraseEnabled())
        assertNull(authManager.readVaultPassphraseOrNull("doc-1"))
    }

    @Test
    fun endToEndSafPushPullUpToDateAndThreeWayConflictMerge() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fastSerializer = NxtsVaultSerializer(
            KdfSecurityParameters(memoryKiB = 8_192, iterations = 1, parallelism = 1)
        )
        val transport = InMemoryDriveSafTransport()
        val bridge = SafLiveGoogleDriveBridge(
            appContext = context,
            serializer = fastSerializer,
            byteTransport = transport
        )

        val passphrase = "LiveDriveSyncMasterKey!2026".toCharArray()
        val driveUri = Uri.parse("content://com.google.android.apps.docs.storage/document/nexus-vault-01")
        val linked = bridge.linkPersistentVaultUri(driveUri, takePersistable = false)
        assertEquals("NexusCorp-LiveVault.nxts", linked.displayName)

        // 1. Initial Push to empty Google Drive .nxts file — MUST NOT double-bump revision!
        val initialDoc = NxtsVaultDocument(
            documentId = "doc-live-01",
            name = "NexusCorp Infrastructure",
            revision = 1L,
            vectorClock = mapOf("Googlebook-OS" to 1L),
            connections = listOf(
                VaultConnection(
                    id = "conn-base-rdp",
                    name = "WinServer-DC01",
                    host = "dc01.corp.internal",
                    protocol = VaultProtocolType.RDP,
                    settings = ProtocolSpecificSettings.Rdp()
                )
            )
        )

        val pushOutcome = bridge.synchronizeLinkedSafVault(
            currentDocument = initialDoc,
            vaultPassphrase = passphrase.copyOf(),
            localDeviceId = "Googlebook-OS"
        )
        assertTrue(pushOutcome is SafDriveSyncOutcome.PushedToDrive)
        val pushedDoc = (pushOutcome as SafDriveSyncOutcome.PushedToDrive).updatedDocument
        assertEquals("Initial push must preserve revision=1 without redundant bump", 1L, pushedDoc.revision)
        assertEquals(1L, pushedDoc.vectorClock["googlebook"])
        val rawDriveBlob = transport.readBytes(driveUri.toString())
        assertTrue(rawDriveBlob.size > 64)
        // Verify NXTS binary magic header (0x4E, 0x58, 0x54, 0x53 = "NXTS")
        assertEquals('N'.code.toByte(), rawDriveBlob[0])
        assertEquals('X'.code.toByte(), rawDriveBlob[1])
        assertEquals('T'.code.toByte(), rawDriveBlob[2])
        assertEquals('S'.code.toByte(), rawDriveBlob[3])

        // 2. Immediate re-sync with zero local or remote mutations -> AlreadyUpToDate
        val upToDateOutcome = bridge.synchronizeLinkedSafVault(
            currentDocument = pushedDoc,
            vaultPassphrase = passphrase.copyOf(),
            localDeviceId = "Googlebook-OS"
        )
        assertTrue(upToDateOutcome is SafDriveSyncOutcome.AlreadyUpToDate)

        // 2b. Local UI save increments revision from 1 -> 2: push MUST seal revision 2 (NEVER bump to 3!)
        val localEditRev2 = pushedDoc.copy(revision = 2L)
        val pushRev2Outcome = bridge.synchronizeLinkedSafVault(
            currentDocument = localEditRev2,
            vaultPassphrase = passphrase.copyOf(),
            localDeviceId = "Googlebook-OS"
        )
        assertTrue(pushRev2Outcome is SafDriveSyncOutcome.PushedToDrive)
        val pushedRev2Doc = (pushRev2Outcome as SafDriveSyncOutcome.PushedToDrive).updatedDocument
        assertEquals("Push of R=2 must stay at R=2 to prevent auto-sync loops", 2L, pushedRev2Doc.revision)
        assertEquals(2L, pushedRev2Doc.vectorClock["googlebook"])

        val afterRev2Outcome = bridge.synchronizeLinkedSafVault(
            currentDocument = pushedRev2Doc,
            vaultPassphrase = passphrase.copyOf(),
            localDeviceId = "Googlebook-OS"
        )
        assertTrue(afterRev2Outcome is SafDriveSyncOutcome.AlreadyUpToDate)

        // 3. Simulate a second device (Android Tablet) pushing a new connection to Google Drive while local is clean
        val tabletOnlyDoc = pushedRev2Doc.copy(
            revision = pushedRev2Doc.revision + 1L,
            vectorClock = pushedRev2Doc.vectorClock + ("Pixel-Tablet" to 1L),
            connections = pushedRev2Doc.connections + VaultConnection(
                id = "conn-tablet-ssh",
                name = "Edge-Router-SSH",
                host = "10.50.0.1",
                protocol = VaultProtocolType.SSH,
                settings = ProtocolSpecificSettings.Ssh()
            )
        )
        transport.writeBytes(
            driveUri.toString(),
            fastSerializer.sealDocument(tabletOnlyDoc, passphrase.copyOf())
        )

        val pullOutcome = bridge.synchronizeLinkedSafVault(
            currentDocument = pushedRev2Doc,
            vaultPassphrase = passphrase.copyOf(),
            localDeviceId = "Googlebook-OS"
        )
        assertTrue(pullOutcome is SafDriveSyncOutcome.PulledFromDrive)
        val pulledDoc = (pullOutcome as SafDriveSyncOutcome.PulledFromDrive).remoteDocument
        assertEquals(2, pulledDoc.connections.size)

        // 4. Simulate concurrent offline edits on BOTH Googlebook OS and Android Tablet -> ThreeWayMerged
        val googlebookConcurrentDoc = pulledDoc.copy(
            revision = pulledDoc.revision + 1L,
            vectorClock = pulledDoc.vectorClock + ("Googlebook-OS" to (pulledDoc.vectorClock["Googlebook-OS"] ?: 0L) + 1L),
            connections = pulledDoc.connections + VaultConnection(
                id = "conn-googlebook-serial",
                name = "Cisco-UART-Switch",
                host = "/dev/ttyUSB0",
                protocol = VaultProtocolType.SERIAL,
                settings = ProtocolSpecificSettings.SerialUart(baudRate = 115_200)
            )
        )

        val tabletConcurrentDoc = pulledDoc.copy(
            revision = pulledDoc.revision + 1L,
            vectorClock = pulledDoc.vectorClock + ("Pixel-Tablet" to 2L),
            connections = pulledDoc.connections + VaultConnection(
                id = "conn-tablet-k8s",
                name = "prod-eu/payment-pod",
                host = "https://k8s.corp.internal:6443",
                protocol = VaultProtocolType.KUBERNETES_POD,
                settings = ProtocolSpecificSettings.ContainerExec(
                    namespace = "prod-eu",
                    podOrContainerName = "payment-pod-01"
                )
            )
        )
        transport.writeBytes(
            driveUri.toString(),
            fastSerializer.sealDocument(tabletConcurrentDoc, passphrase.copyOf())
        )

        val mergeOutcome = bridge.synchronizeLinkedSafVault(
            currentDocument = googlebookConcurrentDoc,
            vaultPassphrase = passphrase.copyOf(),
            localDeviceId = "Googlebook-OS"
        )
        assertTrue(mergeOutcome is SafDriveSyncOutcome.ThreeWayMerged)
        val mergedDoc = (mergeOutcome as SafDriveSyncOutcome.ThreeWayMerged).mergedDocument
        val mergedIds = mergedDoc.connections.map { it.id }.toSet()
        assertTrue(mergedIds.contains("conn-base-rdp"))
        assertTrue(mergedIds.contains("conn-tablet-ssh"))
        assertTrue(mergedIds.contains("conn-googlebook-serial"))
        assertTrue(mergedIds.contains("conn-tablet-k8s"))

        // Verify the merged encrypted vault was persisted back into Google Drive
        val decryptedFromDrive = fastSerializer.unsealDocument(
            transport.readBytes(driveUri.toString()),
            passphrase.copyOf()
        )
        assertEquals(4, decryptedFromDrive.connections.size)
    }

    @Test
    fun coldStartStarterVaultAlwaysPullsRealRemoteDriveVaultAndNeverOverwrites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fastSerializer = NxtsVaultSerializer(
            KdfSecurityParameters(memoryKiB = 8_192, iterations = 1, parallelism = 1)
        )
        val transport = InMemoryDriveSafTransport()
        val bridge = SafLiveGoogleDriveBridge(
            appContext = context,
            serializer = fastSerializer,
            byteTransport = transport
        )

        val passphrase = "ColdStartSafePass!2026".toCharArray()
        val driveUri = Uri.parse("content://com.google.android.apps.docs.storage/document/prod-vault-99")
        bridge.linkPersistentVaultUri(driveUri, takePersistable = false)

        // Step 1: Sync real production vault in Session #1 so SharedPreferences caches lastSyncedRemoteSha256 & revision
        val realProdVault = NxtsVaultDocument(
            documentId = "doc-real-production-vault",
            name = "Global Production Fleet",
            revision = 7L,
            vectorClock = mapOf("Googlebook-OS" to 7L, "googlebook" to 7L),
            connections = listOf(
                VaultConnection(
                    id = "prod-bastion-01",
                    name = "EU Bastion",
                    host = "10.10.0.1",
                    protocol = VaultProtocolType.SSH,
                    settings = ProtocolSpecificSettings.Ssh()
                ),
                VaultConnection(
                    id = "prod-dc-01",
                    name = "US Domain Controller",
                    host = "10.20.0.1",
                    protocol = VaultProtocolType.RDP,
                    settings = ProtocolSpecificSettings.Rdp()
                )
            )
        )
        val firstPush = bridge.synchronizeLinkedSafVault(realProdVault, passphrase.copyOf())
        assertTrue(firstPush is SafDriveSyncOutcome.PushedToDrive)
        val writesBeforeColdStart = transport.writeCount

        // Step 2: Simulate cold-start in Session #2 where UI boots with an uninitialized starter vault
        val freshStarterVault = NxtsVaultDocument(
            documentId = "ephemeral-starter-uuid",
            name = "Starter Workspace",
            revision = 99L, // Even if starter revision were higher, it MUST NOT overwrite remote!
            connections = listOf(
                VaultConnection(
                    id = "demo-localhost",
                    name = "Demo Connection",
                    host = "127.0.0.1",
                    protocol = VaultProtocolType.SSH,
                    settings = ProtocolSpecificSettings.Ssh()
                )
            )
        )

        // Explicit localIsUninitializedStarter = true pulls remote without writing
        val coldPullExplicit = bridge.synchronizeLinkedSafVault(
            currentDocument = freshStarterVault,
            vaultPassphrase = passphrase.copyOf(),
            localIsUninitializedStarter = true
        )
        assertTrue("Expected PulledFromDrive on cold start, got $coldPullExplicit", coldPullExplicit is SafDriveSyncOutcome.PulledFromDrive)
        val pulledExplicit = (coldPullExplicit as SafDriveSyncOutcome.PulledFromDrive).remoteDocument
        assertEquals("doc-real-production-vault", pulledExplicit.documentId)
        assertEquals(2, pulledExplicit.connections.size)
        assertEquals(writesBeforeColdStart, transport.writeCount)

        // Legacy 3-arg call (localIsUninitializedStarter = false) with mismatched starter documentId ALSO pulls safely!
        val coldPullImplicit = bridge.synchronizeLinkedSafVault(
            currentDocument = freshStarterVault,
            vaultPassphrase = passphrase.copyOf()
        )
        assertTrue(coldPullImplicit is SafDriveSyncOutcome.PulledFromDrive)
        assertEquals("doc-real-production-vault", (coldPullImplicit as SafDriveSyncOutcome.PulledFromDrive).remoteDocument.documentId)
        assertEquals(writesBeforeColdStart, transport.writeCount)
    }

    @Test
    fun driveSyncEngineDiscoversExistingCloudVaultAndFallsBackFrom404ParentFolder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fastSerializer = NxtsVaultSerializer(
            KdfSecurityParameters(memoryKiB = 8_192, iterations = 1, parallelism = 1)
        )
        val dao = InMemorySyncedVaultDao()
        val engine = GoogleDriveVaultSyncEngine(context, dao, fastSerializer)
        val passphrase = "CloudDiscoveryKey!2026".toCharArray()

        val remoteCloudDoc = NxtsVaultDocument(
            documentId = "cloud-doc-01",
            name = "TeamCloudVault",
            revision = 4L,
            connections = listOf(
                VaultConnection(
                    id = "c-cloud-1",
                    name = "Cloud Bastion",
                    host = "10.88.0.1",
                    protocol = VaultProtocolType.SSH,
                    settings = ProtocolSpecificSettings.Ssh()
                )
            )
        )
        val remoteSealedBytes = fastSerializer.sealDocument(remoteCloudDoc, passphrase.copyOf())

        var createdFallbackFolder = false
        val fakeTransport = object : HttpTransport() {
            override fun buildRequest(method: String, url: String): LowLevelHttpRequest {
                return object : LowLevelHttpRequest() {
                    override fun addHeader(name: String, value: String) = Unit

                    override fun execute(): LowLevelHttpResponse {
                        val (status, contentType, bodyBytes) = when {
                            // 1. Pre-provisioned folder ID returns 404 on external Google account
                            method == "GET" && url.contains("/files/missing-folder-id") -> {
                                Triple(
                                    404,
                                    "application/json",
                                    """{"error":{"code":404,"message":"File not found"}}""".toByteArray(StandardCharsets.UTF_8)
                                )
                            }
                            // 2. Search for "NexusTS Vaults" folder returns empty -> triggers creation
                            method == "GET" && url.contains("vnd.google-apps.folder") -> {
                                Triple(
                                    200,
                                    "application/json",
                                    """{"files":[]}""".toByteArray(StandardCharsets.UTF_8)
                                )
                            }
                            // 3. Create "NexusTS Vaults" folder returns new folder ID
                            method == "POST" && url.contains("/drive/v3/files") && !url.contains("/upload/") -> {
                                createdFallbackFolder = true
                                Triple(
                                    200,
                                    "application/json",
                                    """{"id":"created-nexusts-folder-id","name":"NexusTS Vaults"}""".toByteArray(StandardCharsets.UTF_8)
                                )
                            }
                            // 4. Cloud Vault Discovery finds existing remote TeamCloudVault.nxts inside fallback folder!
                            method == "GET" && url.contains("TeamCloudVault") -> {
                                Triple(
                                    200,
                                    "application/json",
                                    """{"files":[{"id":"discovered-drive-file-id","name":"TeamCloudVault.nxts","headRevisionId":"rev-cloud-4"}]}""".toByteArray(StandardCharsets.UTF_8)
                                )
                            }
                            // 5. Media download of discovered cloud vault
                            method == "GET" && url.contains("discovered-drive-file-id") && url.contains("alt=media") -> {
                                Triple(200, "application/octet-stream", remoteSealedBytes)
                            }
                            else -> error("Unexpected Drive request: $method $url")
                        }

                        return object : LowLevelHttpResponse() {
                            override fun getContent(): InputStream = ByteArrayInputStream(bodyBytes)
                            override fun getContentEncoding(): String? = null
                            override fun getContentLength(): Long = bodyBytes.size.toLong()
                            override fun getContentType(): String = contentType
                            override fun getStatusLine(): String = "HTTP/1.1 $status OK"
                            override fun getStatusCode(): Int = status
                            override fun getReasonPhrase(): String = "OK"
                            override fun getHeaderCount(): Int = 0
                            override fun getHeaderName(index: Int): String = ""
                            override fun getHeaderValue(index: Int): String = ""
                        }
                    }
                }
            }
        }

        val drive = Drive.Builder(fakeTransport, GsonFactory.getDefaultInstance(), null)
            .setApplicationName("NexusTS-Test")
            .build()

        val result = engine.synchronizeVault(
            drive = drive,
            documentId = "cloud-doc-01",
            displayName = "TeamCloudVault",
            zone = DriveStorageZone.SHARED_TEAM_DRIVE,
            parentFolderId = "missing-folder-id",
            unlockedPassphraseOrNull = passphrase.copyOf()
        )

        assertTrue("Expected fallback NexusTS Vaults folder to be created on 404", createdFallbackFolder)
        assertTrue("Expected PulledRemoteUpdate from Cloud Vault Discovery, got $result", result is VaultSyncResult.PulledRemoteUpdate)
        val cached = dao.getByDocumentId("cloud-doc-01")
        assertNotNull(cached)
        assertEquals("discovered-drive-file-id", cached?.driveFileId)
        assertEquals("created-nexusts-folder-id", cached?.parentFolderDriveId)
        assertEquals(4L, cached?.localRevision)
        assertFalse(cached?.hasPendingLocalEdits ?: true)
    }
}
