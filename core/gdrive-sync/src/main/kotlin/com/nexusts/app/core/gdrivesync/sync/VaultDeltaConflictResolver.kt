package com.nexusts.app.core.gdrivesync.sync

import com.nexusts.app.core.vault.model.CommandTask
import com.nexusts.app.core.vault.model.DynamicFolder
import com.nexusts.app.core.vault.model.KeySequenceTask
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.SecureGatewayConfig
import com.nexusts.app.core.vault.model.SmartFolder
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import kotlin.math.max

enum class VectorClockRelation {
    IDENTICAL,
    LOCAL_AHEAD,
    REMOTE_AHEAD,
    CONCURRENT_DIVERGENCE
}

enum class ConflictResolutionStrategy {
    MERGE_OBJECT_LEVEL_LWW,
    KEEP_LOCAL_OVERRIDE_REMOTE,
    KEEP_REMOTE_ARCHIVE_LOCAL,
    FORK_CONFLICT_COPY
}

data class ObjectConflictEntry(
    val objectId: String,
    val objectKind: String,
    val localName: String,
    val remoteName: String,
    val localUpdatedEpochMs: Long,
    val remoteUpdatedEpochMs: Long
)

data class DeltaMergeOutcome(
    val mergedDocument: NxtsVaultDocument,
    val detectedConflicts: List<ObjectConflictEntry>
)

/**
 * Vector-clock & object-level delta merger for collaborative `.nxts` Team Drive vaults.
 * Allows multiple engineers editing disjoint folders/connections in the same `.nxts` document
 * to merge cleanly while surfacing true per-object edit collisions!
 */
object VaultDeltaConflictResolver {

    fun compareVectorClocks(
        localClock: Map<String, Long>,
        remoteClock: Map<String, Long>
    ): VectorClockRelation {
        val allActors = localClock.keys + remoteClock.keys
        var localStrictlyGreater = false
        var remoteStrictlyGreater = false

        for (actor in allActors) {
            val l = localClock[actor] ?: 0L
            val r = remoteClock[actor] ?: 0L
            if (l > r) localStrictlyGreater = true
            if (r > l) remoteStrictlyGreater = true
        }

        return when {
            !localStrictlyGreater && !remoteStrictlyGreater -> VectorClockRelation.IDENTICAL
            localStrictlyGreater && !remoteStrictlyGreater -> VectorClockRelation.LOCAL_AHEAD
            remoteStrictlyGreater && !localStrictlyGreater -> VectorClockRelation.REMOTE_AHEAD
            else -> VectorClockRelation.CONCURRENT_DIVERGENCE
        }
    }

    fun mergeDocuments(
        local: NxtsVaultDocument,
        remote: NxtsVaultDocument,
        localDeviceId: String
    ): DeltaMergeOutcome {
        val tombstones = local.tombstonedObjectIds + remote.tombstonedObjectIds
        val conflicts = mutableListOf<ObjectConflictEntry>()

        val mergedFolders = mergeById(
            kind = "Folder",
            localList = local.folders,
            remoteList = remote.folders,
            tombstones = tombstones,
            idOf = VaultFolder::id,
            nameOf = VaultFolder::name,
            timeOf = VaultFolder::updatedAtEpochMs,
            conflictsOut = conflicts
        )

        val mergedSmart = mergeById(
            kind = "SmartFolder",
            localList = local.smartFolders,
            remoteList = remote.smartFolders,
            tombstones = tombstones,
            idOf = SmartFolder::id,
            nameOf = SmartFolder::name,
            timeOf = SmartFolder::updatedAtEpochMs,
            conflictsOut = conflicts
        )

        val mergedDynamic = mergeById(
            kind = "DynamicFolder",
            localList = local.dynamicFolders,
            remoteList = remote.dynamicFolders,
            tombstones = tombstones,
            idOf = DynamicFolder::id,
            nameOf = DynamicFolder::name,
            timeOf = DynamicFolder::updatedAtEpochMs,
            conflictsOut = conflicts
        )

        val mergedConnections = mergeById(
            kind = "Connection",
            localList = local.connections,
            remoteList = remote.connections,
            tombstones = tombstones,
            idOf = VaultConnection::id,
            nameOf = VaultConnection::name,
            timeOf = VaultConnection::updatedAtEpochMs,
            conflictsOut = conflicts
        )

        val mergedCredentials = mergeById(
            kind = "Credential",
            localList = local.credentials,
            remoteList = remote.credentials,
            tombstones = tombstones,
            idOf = VaultCredential::id,
            nameOf = VaultCredential::name,
            timeOf = VaultCredential::updatedAtEpochMs,
            conflictsOut = conflicts
        )

        val mergedGateways = mergeById(
            kind = "SecureGateway",
            localList = local.gateways,
            remoteList = remote.gateways,
            tombstones = tombstones,
            idOf = SecureGatewayConfig::id,
            nameOf = SecureGatewayConfig::name,
            timeOf = SecureGatewayConfig::updatedAtEpochMs,
            conflictsOut = conflicts
        )

        val mergedCmdTasks = mergeById(
            kind = "CommandTask",
            localList = local.commandTasks,
            remoteList = remote.commandTasks,
            tombstones = tombstones,
            idOf = CommandTask::id,
            nameOf = CommandTask::name,
            timeOf = CommandTask::updatedAtEpochMs,
            conflictsOut = conflicts
        )

        val mergedKeyTasks = mergeById(
            kind = "KeySequenceTask",
            localList = local.keySequenceTasks,
            remoteList = remote.keySequenceTasks,
            tombstones = tombstones,
            idOf = KeySequenceTask::id,
            nameOf = KeySequenceTask::name,
            timeOf = KeySequenceTask::updatedAtEpochMs,
            conflictsOut = conflicts
        )

        val allActors = local.vectorClock.keys + remote.vectorClock.keys + localDeviceId
        val nextVectorClock = allActors.associateWith { actor ->
            val base = max(local.vectorClock[actor] ?: 0L, remote.vectorClock[actor] ?: 0L)
            if (actor == localDeviceId) base + 1L else base
        }

        val mergedDoc = local.copy(
            revision = max(local.revision, remote.revision) + 1L,
            lastModifiedByDevice = localDeviceId,
            updatedAtEpochMs = System.currentTimeMillis(),
            vectorClock = nextVectorClock,
            folders = mergedFolders,
            smartFolders = mergedSmart,
            dynamicFolders = mergedDynamic,
            connections = mergedConnections,
            credentials = mergedCredentials,
            gateways = mergedGateways,
            commandTasks = mergedCmdTasks,
            keySequenceTasks = mergedKeyTasks,
            tombstonedObjectIds = tombstones
        )

        return DeltaMergeOutcome(mergedDocument = mergedDoc, detectedConflicts = conflicts)
    }

    private fun <T> mergeById(
        kind: String,
        localList: List<T>,
        remoteList: List<T>,
        tombstones: Set<String>,
        idOf: (T) -> String,
        nameOf: (T) -> String,
        timeOf: (T) -> Long,
        conflictsOut: MutableList<ObjectConflictEntry>
    ): List<T> {
        val localMap = localList.associateBy(idOf)
        val remoteMap = remoteList.associateBy(idOf)
        val allIds = (localMap.keys + remoteMap.keys).filterNot { it in tombstones }

        return allIds.mapNotNull { id ->
            val l = localMap[id]
            val r = remoteMap[id]
            when {
                l != null && r == null -> l
                l == null && r != null -> r
                l != null && r != null -> {
                    if (l == r) {
                        l
                    } else {
                        conflictsOut += ObjectConflictEntry(
                            objectId = id,
                            objectKind = kind,
                            localName = nameOf(l),
                            remoteName = nameOf(r),
                            localUpdatedEpochMs = timeOf(l),
                            remoteUpdatedEpochMs = timeOf(r)
                        )
                        if (timeOf(l) >= timeOf(r)) l else r
                    }
                }
                else -> null
            }
        }
    }
}
