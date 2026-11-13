package com.nexusts.app.core.gdrivesync

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.nexusts.app.core.gdrivesync.auth.GoogleDriveAuthManager
import com.nexusts.app.core.gdrivesync.sync.SafByteTransport
import com.nexusts.app.core.gdrivesync.sync.SafDriveSyncOutcome
import com.nexusts.app.core.gdrivesync.sync.SafLiveGoogleDriveBridge
import com.nexusts.app.core.vault.crypto.KdfSecurityParameters
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultProtocolType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafLiveDriveSyncTest {

    private class InMemoryDriveSafTransport : SafByteTransport {
        val blobs = ConcurrentHashMap<String, ByteArray>()

        override fun readBytes(uriString: String): ByteArray =
            blobs[uriString]?.copyOf() ?: ByteArray(0)

        override fun writeBytes(uriString: String, payload: ByteArray) {
            blobs[uriString] = payload.copyOf()
        }

        override fun resolveDisplayName(uriString: String): String =
            "NexusCorp-LiveVault.nxts"
    }

    @Test
    fun defaultProvisionedDriveFolderMatchesUserNexusTsVaultsFolder() {
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

        // 1. Initial Push to empty Google Drive .nxts file
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

        // 3. Simulate a second device (Android Tablet) pushing a new connection to Google Drive while local is clean
        val tabletOnlyDoc = pushedDoc.copy(
            revision = pushedDoc.revision + 1L,
            vectorClock = pushedDoc.vectorClock + ("Pixel-Tablet" to 1L),
            connections = pushedDoc.connections + VaultConnection(
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
            currentDocument = pushedDoc,
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
}
