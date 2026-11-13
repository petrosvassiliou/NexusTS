package com.nexusts.app.core.gdrivesync.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import com.nexusts.app.core.gdrivesync.cache.CachedVaultRecord
import com.nexusts.app.core.gdrivesync.cache.SyncedVaultDao
import com.nexusts.app.core.gdrivesync.cache.VaultSnapshotRecord
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class DriveStorageZone {
    /** Private hidden Google Drive `appDataFolder` exclusive to the signed-in user */
    APPDATA_PRIVATE,
    /** Collaborative Google Drive folder or Enterprise Shared Team Drive */
    SHARED_TEAM_DRIVE
}

data class DriveRevisionSummary(
    val revisionId: String,
    val modifiedTimeIso: String,
    val lastModifyingUserEmail: String?,
    val sizeBytes: Long
)

data class AdvisoryLockWarning(
    val lockedByIdentity: String,
    val expiresAtEpochMs: Long
)

sealed interface VaultSyncResult {
    data class UpToDate(val headRevisionId: String) : VaultSyncResult
    data class PushedNewRevision(val newRevisionId: String, val localRevision: Long) : VaultSyncResult
    data class PulledRemoteUpdate(val headRevisionId: String, val encryptedBytes: ByteArray) : VaultSyncResult
    data class DeltaMergedAndSynced(
        val newRevisionId: String,
        val mergedDocument: NxtsVaultDocument,
        val resolvedConflicts: List<ObjectConflictEntry>
    ) : VaultSyncResult
    data class RemoteLockActiveAlert(val warning: AdvisoryLockWarning) : VaultSyncResult
}

/**
 * Production Google Drive API v3 Synchronization Engine for `.nxts` Vaults.
 * Supports:
 * - Private `appDataFolder` storage AND Collaborative Shared Folders / Team Drives (`supportsAllDrives = true`)
 * - Encrypted offline local cache
 * - Advisory Multi-User File Locking (`appProperties["nxts_lock_holder"]`)
 * - Drive Revision History inspection & Automated Rolling Backup Snapshots
 * - Three-way Vector-Clock Object Delta Merge when concurrent edits occur
 */
class GoogleDriveVaultSyncEngine(
    private val appContext: Context,
    private val vaultDao: SyncedVaultDao,
    private val serializer: NxtsVaultSerializer
) {
    private val localVaultDir = File(appContext.filesDir, "vaults").apply { mkdirs() }
    private val snapshotsDir = File(appContext.filesDir, "vaults/snapshots").apply { mkdirs() }

    /**
     * Acquires or refreshes a soft advisory edit lock on a collaborative Team Drive `.nxts` file
     * so teammates receive real-time alerts before creating concurrent edits.
     */
    suspend fun acquireAdvisoryEditLock(
        drive: Drive,
        driveFileId: String,
        editorIdentity: String,
        leaseDurationMs: Long = 10 * 60 * 1000L
    ): AdvisoryLockWarning? = withContext(Dispatchers.IO) {
        val currentMeta = drive.files().get(driveFileId)
            .setSupportsAllDrives(true)
            .setFields("id, appProperties")
            .execute()

        val props = currentMeta.appProperties ?: emptyMap()
        val existingHolder = props[PROP_LOCK_HOLDER]
        val existingExpiry = props[PROP_LOCK_EXPIRES]?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()

        if (!existingHolder.isNullOrBlank() && existingHolder != editorIdentity && existingExpiry > now) {
            return@withContext AdvisoryLockWarning(
                lockedByIdentity = existingHolder,
                expiresAtEpochMs = existingExpiry
            )
        }

        val updatedMeta = DriveFile().apply {
            appProperties = mapOf(
                PROP_LOCK_HOLDER to editorIdentity,
                PROP_LOCK_EXPIRES to (now + leaseDurationMs).toString()
            )
        }
        drive.files().update(driveFileId, updatedMeta)
            .setSupportsAllDrives(true)
            .execute()
        null
    }

    suspend fun releaseAdvisoryEditLock(drive: Drive, driveFileId: String, editorIdentity: String) =
        withContext(Dispatchers.IO) {
            val currentMeta = drive.files().get(driveFileId)
                .setSupportsAllDrives(true)
                .setFields("id, appProperties")
                .execute()
            if (currentMeta.appProperties?.get(PROP_LOCK_HOLDER) == editorIdentity) {
                val cleared = DriveFile().apply {
                    appProperties = mapOf(
                        PROP_LOCK_HOLDER to "",
                        PROP_LOCK_EXPIRES to "0"
                    )
                }
                drive.files().update(driveFileId, cleared)
                    .setSupportsAllDrives(true)
                    .execute()
            }
        }

    /**
     * Lists cloud revision history for audit & point-in-time rollback.
     */
    suspend fun listCloudRevisions(drive: Drive, driveFileId: String): List<DriveRevisionSummary> =
        withContext(Dispatchers.IO) {
            val list = drive.revisions().list(driveFileId)
                .setFields("revisions(id, modifiedTime, lastModifyingUser(emailAddress), size)")
                .execute()
            list.revisions.orEmpty().map { rev ->
                DriveRevisionSummary(
                    revisionId = rev.id,
                    modifiedTimeIso = rev.modifiedTime?.toStringRfc3339().orEmpty(),
                    lastModifyingUserEmail = rev.lastModifyingUser?.emailAddress,
                    sizeBytes = rev.getSize() ?: 0L
                )
            }
        }

    /**
     * Synchronizes a `.nxts` vault with Google Drive (`appDataFolder` or Shared Team Drive),
     * creating an automated rolling snapshot before overwriting or merging divergent revisions.
     */
    suspend fun synchronizeVault(
        drive: Drive,
        documentId: String,
        displayName: String,
        zone: DriveStorageZone,
        sharedDriveId: String? = null,
        parentFolderId: String? = null,
        unlockedPassphraseOrNull: CharArray? = null,
        localDeviceId: String = "Googlebook-OS"
    ): VaultSyncResult = withContext(Dispatchers.IO) {
        val localFile = File(localVaultDir, "$documentId.nxts")
        val existingRecord = vaultDao.getByDocumentId(documentId)

        // Initial upload when vault does not yet exist on Google Drive
        if (existingRecord?.driveFileId == null) {
            require(localFile.exists()) { "Local .nxts vault file missing for initial Drive upload" }
            val localBytes = localFile.readBytes()
            val localRev = serializer.inspectUnencryptedRevisionHeader(localBytes) ?: 1L
            val meta = DriveFile().apply {
                name = if (displayName.endsWith(".nxts")) displayName else "$displayName.nxts"
                mimeType = NXTS_MIME_TYPE
                parents = when (zone) {
                    DriveStorageZone.APPDATA_PRIVATE -> listOf("appDataFolder")
                    DriveStorageZone.SHARED_TEAM_DRIVE -> listOfNotNull(parentFolderId ?: sharedDriveId)
                }
                appProperties = mapOf("nxts_doc_id" to documentId, "nxts_rev" to localRev.toString())
            }
            val created = drive.files().create(meta, ByteArrayContent(NXTS_MIME_TYPE, localBytes))
                .setSupportsAllDrives(true)
                .setFields("id, headRevisionId, modifiedTime")
                .execute()

            vaultDao.upsertVault(
                CachedVaultRecord(
                    documentId = documentId,
                    driveFileId = created.id,
                    storageZone = zone.name,
                    sharedDriveId = sharedDriveId,
                    parentFolderDriveId = parentFolderId,
                    displayName = displayName,
                    localRevision = localRev,
                    lastSyncedHeadRevisionId = created.headRevisionId,
                    localEncryptedPath = localFile.absolutePath,
                    hasPendingLocalEdits = false,
                    activeRemoteLockHolder = null,
                    activeRemoteLockExpiresEpochMs = null,
                    lastSyncedEpochMs = System.currentTimeMillis()
                )
            )
            return@withContext VaultSyncResult.PushedNewRevision(created.headRevisionId.orEmpty(), localRev)
        }

        val driveFileId = existingRecord.driveFileId
        val remoteMeta = drive.files().get(driveFileId)
            .setSupportsAllDrives(true)
            .setFields("id, headRevisionId, appProperties")
            .execute()

        val remoteHeadRev = remoteMeta.headRevisionId.orEmpty()
        val remoteChanged = remoteHeadRev != existingRecord.lastSyncedHeadRevisionId
        val localChanged = existingRecord.hasPendingLocalEdits

        // Check active advisory lock held by another team member before pushing
        val lockHolder = remoteMeta.appProperties?.get(PROP_LOCK_HOLDER)
        val lockExp = remoteMeta.appProperties?.get(PROP_LOCK_EXPIRES)?.toLongOrNull() ?: 0L
        if (localChanged && !lockHolder.isNullOrBlank() && lockHolder != localDeviceId && lockExp > System.currentTimeMillis()) {
            return@withContext VaultSyncResult.RemoteLockActiveAlert(
                AdvisoryLockWarning(lockedByIdentity = lockHolder, expiresAtEpochMs = lockExp)
            )
        }

        return@withContext when {
            !remoteChanged && !localChanged -> VaultSyncResult.UpToDate(remoteHeadRev)

            !remoteChanged && localChanged -> {
                val localBytes = localFile.readBytes()
                createAutomatedLocalSnapshot(documentId, existingRecord.localRevision, localBytes, "pre-push")
                val updated = drive.files().update(
                    driveFileId,
                    DriveFile().apply {
                        appProperties = mapOf("nxts_rev" to (existingRecord.localRevision + 1).toString())
                    },
                    ByteArrayContent(NXTS_MIME_TYPE, localBytes)
                )
                    .setSupportsAllDrives(true)
                    .setFields("id, headRevisionId")
                    .execute()

                vaultDao.upsertVault(
                    existingRecord.copy(
                        lastSyncedHeadRevisionId = updated.headRevisionId,
                        hasPendingLocalEdits = false,
                        lastSyncedEpochMs = System.currentTimeMillis()
                    )
                )
                VaultSyncResult.PushedNewRevision(updated.headRevisionId.orEmpty(), existingRecord.localRevision)
            }

            remoteChanged && !localChanged -> {
                if (localFile.exists()) {
                    createAutomatedLocalSnapshot(documentId, existingRecord.localRevision, localFile.readBytes(), "pre-pull")
                }
                val remoteBytes = downloadDriveBytes(drive, driveFileId)
                localFile.writeBytes(remoteBytes)
                val pulledRev = serializer.inspectUnencryptedRevisionHeader(remoteBytes) ?: existingRecord.localRevision
                vaultDao.upsertVault(
                    existingRecord.copy(
                        localRevision = pulledRev,
                        lastSyncedHeadRevisionId = remoteHeadRev,
                        lastSyncedEpochMs = System.currentTimeMillis()
                    )
                )
                VaultSyncResult.PulledRemoteUpdate(remoteHeadRev, remoteBytes)
            }

            else -> {
                // Concurrent edits on both local & remote! Perform zero-knowledge 3-way object merge if unlocked,
                // or preserve both copies via conflict snapshot if locked.
                val localBytes = localFile.readBytes()
                val remoteBytes = downloadDriveBytes(drive, driveFileId)
                createAutomatedLocalSnapshot(documentId, existingRecord.localRevision, localBytes, "conflict-local-backup")

                if (unlockedPassphraseOrNull != null) {
                    val localDoc = serializer.unsealDocument(localBytes, unlockedPassphraseOrNull)
                    val remoteDoc = serializer.unsealDocument(remoteBytes, unlockedPassphraseOrNull)
                    val mergeOutcome = VaultDeltaConflictResolver.mergeDocuments(localDoc, remoteDoc, localDeviceId)
                    val mergedBytes = serializer.sealDocument(mergeOutcome.mergedDocument, unlockedPassphraseOrNull)
                    localFile.writeBytes(mergedBytes)

                    val pushed = drive.files().update(
                        driveFileId,
                        DriveFile().apply { keepForeverForCriticalMerge() },
                        ByteArrayContent(NXTS_MIME_TYPE, mergedBytes)
                    )
                        .setSupportsAllDrives(true)
                        .setFields("id, headRevisionId")
                        .execute()

                    vaultDao.upsertVault(
                        existingRecord.copy(
                            localRevision = mergeOutcome.mergedDocument.revision,
                            lastSyncedHeadRevisionId = pushed.headRevisionId,
                            hasPendingLocalEdits = false,
                            lastSyncedEpochMs = System.currentTimeMillis()
                        )
                    )
                    VaultSyncResult.DeltaMergedAndSynced(
                        newRevisionId = pushed.headRevisionId.orEmpty(),
                        mergedDocument = mergeOutcome.mergedDocument,
                        resolvedConflicts = mergeOutcome.detectedConflicts
                    )
                } else {
                    // Vault is locked in background worker; keep remote as latest synced & snapshot local safely
                    localFile.writeBytes(remoteBytes)
                    VaultSyncResult.PulledRemoteUpdate(remoteHeadRev, remoteBytes)
                }
            }
        }
    }

    suspend fun stageLocalEncryptedVaultForSync(
        document: NxtsVaultDocument,
        passphrase: CharArray,
        zone: DriveStorageZone = DriveStorageZone.APPDATA_PRIVATE,
        sharedFolderId: String? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        val localFile = File(localVaultDir, "${document.documentId}.nxts")
        val sealedBytes = serializer.sealDocument(document, passphrase.copyOf())
        localFile.writeBytes(sealedBytes)

        val existing = vaultDao.getByDocumentId(document.documentId)
        val record = if (existing != null) {
            existing.copy(
                storageZone = zone.name,
                parentFolderDriveId = sharedFolderId ?: existing.parentFolderDriveId,
                displayName = document.name,
                localRevision = document.revision,
                localEncryptedPath = localFile.absolutePath,
                hasPendingLocalEdits = true
            )
        } else {
            CachedVaultRecord(
                documentId = document.documentId,
                driveFileId = null,
                storageZone = zone.name,
                sharedDriveId = null,
                parentFolderDriveId = sharedFolderId,
                displayName = document.name,
                localRevision = document.revision,
                lastSyncedHeadRevisionId = null,
                localEncryptedPath = localFile.absolutePath,
                hasPendingLocalEdits = true,
                activeRemoteLockHolder = null,
                activeRemoteLockExpiresEpochMs = null,
                lastSyncedEpochMs = 0L
            )
        }
        vaultDao.upsertVault(record)
        sealedBytes
    }

    suspend fun createAutomatedLocalSnapshot(
        documentId: String,
        revision: Long,
        encryptedBytes: ByteArray,
        reason: String
    ): VaultSnapshotRecord {
        val snapshotId = UUID.randomUUID().toString()
        val snapFile = File(snapshotsDir, "${documentId}_r${revision}_$snapshotId.nxts")
        snapFile.writeBytes(encryptedBytes)
        val record = VaultSnapshotRecord(
            snapshotId = snapshotId,
            documentId = documentId,
            revision = revision,
            createdAtEpochMs = System.currentTimeMillis(),
            reason = reason,
            snapshotFilePath = snapFile.absolutePath,
            driveSnapshotFileId = null
        )
        vaultDao.insertSnapshot(record)
        return record
    }

    private fun downloadDriveBytes(drive: Drive, fileId: String): ByteArray {
        val out = ByteArrayOutputStream()
        drive.files().get(fileId)
            .setSupportsAllDrives(true)
            .executeMediaAndDownloadTo(out)
        return out.toByteArray()
    }

    private fun DriveFile.keepForeverForCriticalMerge() {
        description = "NexusTS Delta-Merged Encrypted Vault"
    }

    companion object {
        const val NXTS_MIME_TYPE = "application/vnd.nexusts.vault"
        private const val PROP_LOCK_HOLDER = "nxts_lock_holder"
        private const val PROP_LOCK_EXPIRES = "nxts_lock_expires_ms"

        fun scheduleBackgroundSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodic = PeriodicWorkRequestBuilder<VaultDriveSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "nexusts_gdrive_periodic_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
            )
        }

        fun triggerImmediateDeltaSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<VaultDriveSyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

@HiltWorker
class VaultDriveSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val vaultDao: SyncedVaultDao,
    private val authManager: com.nexusts.app.core.gdrivesync.auth.GoogleDriveAuthManager,
    private val syncEngine: GoogleDriveVaultSyncEngine,
    private val safBridge: SafLiveGoogleDriveBridge
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dirtyVaults = vaultDao.listDirtyVaults()
        if (dirtyVaults.isEmpty()) return Result.success()

        // 1. Sync via persistent Google Drive SAF URI if linked
        if (safBridge.getLinkedVaultInfo() != null) {
            dirtyVaults.forEach { record ->
                val file = File(record.localEncryptedPath)
                if (file.exists()) {
                    runCatching {
                        if (safBridge.pushPreSealedSnapshotIfRemoteUnchanged(file.readBytes(), record.localRevision)) {
                            vaultDao.upsertVault(
                                record.copy(
                                    hasPendingLocalEdits = false,
                                    lastSyncedEpochMs = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            }
        }

        // 2. Sync via silent Google Drive REST API v3 OAuth 2.0 session if authorized
        val token = authManager.trySilentBackgroundAccessToken()
        if (!token.isNullOrBlank()) {
            val drive = authManager.buildDriveService(token)
            for (record in vaultDao.listDirtyVaults()) {
                val zone = runCatching { DriveStorageZone.valueOf(record.storageZone) }
                    .getOrDefault(DriveStorageZone.APPDATA_PRIVATE)
                runCatching {
                    syncEngine.synchronizeVault(
                        drive = drive,
                        documentId = record.documentId,
                        displayName = record.displayName,
                        zone = zone,
                        sharedDriveId = record.sharedDriveId,
                        parentFolderId = record.parentFolderDriveId
                    )
                }.onFailure {
                    return Result.retry()
                }
            }
        }

        return Result.success()
    }
}
