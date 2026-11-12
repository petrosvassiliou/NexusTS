package com.nexusts.app.core.gdrivesync

import com.nexusts.app.core.gdrivesync.sync.VaultDeltaConflictResolver
import com.nexusts.app.core.gdrivesync.sync.VectorClockRelation
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultDeltaSyncTest {

    @Test
    fun `vector clock detects concurrent team divergence and merges disjoint connections`() {
        val baseConn = VaultConnection(
            id = "c-shared",
            name = "shared-db",
            host = "10.0.0.1",
            protocol = VaultProtocolType.SSH,
            updatedAtEpochMs = 1000L,
            settings = ProtocolSpecificSettings.Ssh()
        )
        val localAdded = VaultConnection(
            id = "c-local",
            name = "alice-node",
            host = "10.0.0.2",
            protocol = VaultProtocolType.SSH,
            updatedAtEpochMs = 2000L,
            settings = ProtocolSpecificSettings.Ssh()
        )
        val remoteAdded = VaultConnection(
            id = "c-remote",
            name = "bob-rdp",
            host = "10.0.0.3",
            protocol = VaultProtocolType.RDP,
            updatedAtEpochMs = 2100L,
            settings = ProtocolSpecificSettings.Rdp()
        )

        val localDoc = NxtsVaultDocument(
            name = "Team.nxts",
            revision = 4L,
            vectorClock = mapOf("alice" to 2L, "bob" to 1L),
            connections = listOf(baseConn, localAdded)
        )
        val remoteDoc = NxtsVaultDocument(
            name = "Team.nxts",
            revision = 4L,
            vectorClock = mapOf("alice" to 1L, "bob" to 2L),
            connections = listOf(baseConn, remoteAdded)
        )

        val relation = VaultDeltaConflictResolver.compareVectorClocks(localDoc.vectorClock, remoteDoc.vectorClock)
        assertEquals(VectorClockRelation.CONCURRENT_DIVERGENCE, relation)

        val outcome = VaultDeltaConflictResolver.mergeDocuments(localDoc, remoteDoc, "alice")
        assertTrue(outcome.detectedConflicts.isEmpty())
        assertEquals(3, outcome.mergedDocument.connections.size)
        assertEquals(5L, outcome.mergedDocument.revision)
    }
}
