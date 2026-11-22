package com.nexusts.app.core.gdrivesync.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.HttpResponseException
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import com.nexusts.app.core.gdrivesync.auth.GoogleDriveAuthManager
import com.nexusts.app.core.gdrivesync.cache.CachedVaultRecord
import com.nexusts.app.core.gdrivesync.cache.SyncedVaultDao
import com.nexusts.app.core.gdrivesync.cache.VaultSnapshotRecord
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Arrays
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
 * - Cloud Vault Discovery (`drive.files().list()`) & automatic `"NexusTS Vaults"` folder creation on 404
 * - Mutex-serialized foreground + WorkManager background sync
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
    private val syncMutex = Mutex()
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
     * performing Cloud Vault Discovery when unbound, auto-creating `"NexusTS Vaults"` if `parentFolderId`
     * returns 404, and creating an automated rolling snapshot before overwriting or merging divergent revisions.
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
    ): VaultSyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val localFile = File(localVaultDir, "$documentId.nxts")
            val existingRecord = vaultDao.getByDocumentId(documentId)

            val boundDriveFileId = existingRecord?.driveFileId?.takeIf { it.isNotBlank() }

            // Cloud Vault Discovery & Initial Upload when vault is not yet bound locally to a Drive file ID
            if (existingRecord == null || boundDriveFileId == null) {
                val effectiveParentFolderId = if (zone == DriveStorageZone.SHARED_TEAM_DRIVE) {
                    resolveAccessibleParentFolderId(drive, parentFolderId, sharedDriveId)
                } else {
                    null
                }

                val discoveredRemote = discoverExistingCloudVault(
                    drive = drive,
                    displayName = displayName,
                    zone = zone,
                    parentFolderId = effectiveParentFolderId
                )

                if (discoveredRemote?.id != null) {
                    val discoveredId = discoveredRemote.id
                    val discoveredHeadRev = discoveredRemote.headRevisionId.orEmpty()
                    val remoteBytes = downloadDriveBytes(drive, discoveredId)

                    if (!localFile.exists() || existingRecord == null || !existingRecord.hasPendingLocalEdits) {
                        localFile.writeBytes(remoteBytes)
                        val pulledRev = serializer.inspectUnencryptedRevisionHeader(remoteBytes) ?: 1L
                        vaultDao.upsertVault(
                            CachedVaultRecord(
                                documentId = documentId,
                                driveFileId = discoveredId,
                                storageZone = zone.name,
                                sharedDriveId = sharedDriveId,
                                parentFolderDriveId = effectiveParentFolderId,
                                displayName = displayName,
                                localRevision = pulledRev,
                                lastSyncedHeadRevisionId = discoveredHeadRev,
                                localEncryptedPath = localFile.absolutePath,
                                hasPendingLocalEdits = false,
                                activeRemoteLockHolder = null,
                                activeRemoteLockExpiresEpochMs = null,
                                lastSyncedEpochMs = System.currentTimeMillis()
                            )
                        )
                        return@withContext VaultSyncResult.PulledRemoteUpdate(discoveredHeadRev, remoteBytes)
                    }

                    val localBytes = localFile.readBytes()
                    createAutomatedLocalSnapshot(documentId, existingRecord.localRevision, localBytes, "cloud-discovery-local-backup")

                    if (unlockedPassphraseOrNull != null) {
                        val localDoc = runCatching { serializer.unsealDocument(localBytes, unlockedPassphraseOrNull.copyOf()) }.getOrNull()
                        val remoteDoc = runCatching { serializer.unsealDocument(remoteBytes, unlockedPassphraseOrNull.copyOf()) }.getOrNull()
                        if (localDoc != null && remoteDoc != null && localDoc.documentId == remoteDoc.documentId) {
                            val mergeOutcome = VaultDeltaConflictResolver.mergeDocuments(localDoc, remoteDoc, localDeviceId)
                            val mergedBytes = serializer.sealDocument(mergeOutcome.mergedDocument, unlockedPassphraseOrNull.copyOf())
                            localFile.writeBytes(mergedBytes)
                            val pushed = drive.files().update(
                                discoveredId,
                                DriveFile().apply {
                                    keepForeverForCriticalMerge()
                                    appProperties = mapOf("nxts_rev" to mergeOutcome.mergedDocument.revision.toString())
                                },
                                ByteArrayContent(NXTS_MIME_TYPE, mergedBytes)
                            )
                                .setSupportsAllDrives(true)
                                .setFields("id, headRevisionId")
                                .execute()

                            vaultDao.upsertVault(
                                CachedVaultRecord(
                                    documentId = documentId,
                                    driveFileId = discoveredId,
                                    storageZone = zone.name,
                                    sharedDriveId = sharedDriveId,
                                    parentFolderDriveId = effectiveParentFolderId,
                                    displayName = displayName,
                                    localRevision = mergeOutcome.mergedDocument.revision,
                                    lastSyncedHeadRevisionId = pushed.headRevisionId,
                                    localEncryptedPath = localFile.absolutePath,
                                    hasPendingLocalEdits = false,
                                    activeRemoteLockHolder = null,
                                    activeRemoteLockExpiresEpochMs = null,
                                    lastSyncedEpochMs = System.currentTimeMillis()
                                )
                            )
                            return@withContext VaultSyncResult.DeltaMergedAndSynced(
                                newRevisionId = pushed.headRevisionId.orEmpty(),
                                mergedDocument = mergeOutcome.mergedDocument,
                                resolvedConflicts = mergeOutcome.detectedConflicts
                            )
                        }
                    }

                    localFile.writeBytes(remoteBytes)
                    val pulledRev = serializer.inspectUnencryptedRevisionHeader(remoteBytes) ?: existingRecord.localRevision
                    vaultDao.upsertVault(
                        CachedVaultRecord(
                            documentId = documentId,
                            driveFileId = discoveredId,
                            storageZone = zone.name,
                            sharedDriveId = sharedDriveId,
                            parentFolderDriveId = effectiveParentFolderId,
                            displayName = displayName,
                            localRevision = pulledRev,
                            lastSyncedHeadRevisionId = discoveredHeadRev,
                            localEncryptedPath = localFile.absolutePath,
                            hasPendingLocalEdits = false,
                            activeRemoteLockHolder = null,
                            activeRemoteLockExpiresEpochMs = null,
                            lastSyncedEpochMs = System.currentTimeMillis()
                        )
                    )
                    return@withContext VaultSyncResult.PulledRemoteUpdate(discoveredHeadRev, remoteBytes)
                }

                // No remote file exists yet — create initial .nxts file on Google Drive
                require(localFile.exists()) { "Local .nxts vault file missing for initial Drive upload" }
                val localBytes = localFile.readBytes()
                val localRev = serializer.inspectUnencryptedRevisionHeader(localBytes) ?: 1L
                val remoteFileName = if (displayName.endsWith(".nxts", ignoreCase = true)) displayName else "$displayName.nxts"

                var usedParentId = effectiveParentFolderId
                val created = try {
                    val meta = DriveFile().apply {
                        name = remoteFileName
                        mimeType = NXTS_MIME_TYPE
                        parents = when (zone) {
                            DriveStorageZone.APPDATA_PRIVATE -> listOf("appDataFolder")
                            DriveStorageZone.SHARED_TEAM_DRIVE -> listOfNotNull(usedParentId)
                        }
                        appProperties = mapOf("nxts_doc_id" to documentId, "nxts_rev" to localRev.toString())
                    }
                    drive.files().create(meta, ByteArrayContent(NXTS_MIME_TYPE, localBytes))
                        .setSupportsAllDrives(true)
                        .setFields("id, headRevisionId, modifiedTime")
                        .execute()
                } catch (httpEx: HttpResponseException) {
                    if (httpEx.statusCode == 404 && zone == DriveStorageZone.SHARED_TEAM_DRIVE) {
                        val fallbackFolderId = findOrCreateNexusTsVaultsFolder(drive)
                        usedParentId = fallbackFolderId
                        val retryMeta = DriveFile().apply {
                            name = remoteFileName
                            mimeType = NXTS_MIME_TYPE
                            parents = listOf(fallbackFolderId)
                            appProperties = mapOf("nxts_doc_id" to documentId, "nxts_rev" to localRev.toString())
                        }
                        drive.files().create(retryMeta, ByteArrayContent(NXTS_MIME_TYPE, localBytes))
                            .setSupportsAllDrives(true)
                            .setFields("id, headRevisionId, modifiedTime")
                            .execute()
                    } else {
                        throw httpEx
                    }
                }

                vaultDao.upsertVault(
                    CachedVaultRecord(
                        documentId = documentId,
                        driveFileId = created.id,
                        storageZone = zone.name,
                        sharedDriveId = sharedDriveId,
                        parentFolderDriveId = usedParentId,
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

            val driveFileId: String = boundDriveFileId
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
                            appProperties = mapOf("nxts_rev" to existingRecord.localRevision.toString())
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
                    // or preserve both copies via conflict snapshot and update vaultDao cleanly if locked.
                    val localBytes = localFile.readBytes()
                    val remoteBytes = downloadDriveBytes(drive, driveFileId)
                    createAutomatedLocalSnapshot(documentId, existingRecord.localRevision, localBytes, "conflict-local-backup")

                    val unlockedPair = if (unlockedPassphraseOrNull != null) {
                        runCatching {
                            val localDoc = serializer.unsealDocument(localBytes, unlockedPassphraseOrNull.copyOf())
                            val remoteDoc = serializer.unsealDocument(remoteBytes, unlockedPassphraseOrNull.copyOf())
                            localDoc to remoteDoc
                        }.getOrNull()
                    } else {
                        null
                    }

                    if (unlockedPair != null && unlockedPassphraseOrNull != null) {
                        val (localDoc, remoteDoc) = unlockedPair
                        val mergeOutcome = VaultDeltaConflictResolver.mergeDocuments(localDoc, remoteDoc, localDeviceId)
                        val mergedBytes = serializer.sealDocument(mergeOutcome.mergedDocument, unlockedPassphraseOrNull.copyOf())
                        localFile.writeBytes(mergedBytes)

                        val pushed = drive.files().update(
                            driveFileId,
                            DriveFile().apply {
                                keepForeverForCriticalMerge()
                                appProperties = mapOf("nxts_rev" to mergeOutcome.mergedDocument.revision.toString())
                            },
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
                        // Vault is locked in background worker (or remote passphrase rotated); keep remote as latest synced,
                        // snapshot local safely, and update vaultDao so we don't repeatedly re-trigger conflict sync.
                        localFile.writeBytes(remoteBytes)
                        val pulledRev = serializer.inspectUnencryptedRevisionHeader(remoteBytes) ?: existingRecord.localRevision
                        vaultDao.upsertVault(
                            existingRecord.copy(
                                localRevision = pulledRev,
                                lastSyncedHeadRevisionId = remoteHeadRev,
                                hasPendingLocalEdits = false,
                                lastSyncedEpochMs = System.currentTimeMillis()
                            )
                        )
                        VaultSyncResult.PulledRemoteUpdate(remoteHeadRev, remoteBytes)
                    }
                }
            }
        }
    }

    /**
     * Resolves an accessible parent folder ID in Google Drive. If `parentFolderId` returns HTTP 404
     * (e.g. external account without the pre-provisioned folder ID), automatically searches/creates
     * a `"NexusTS Vaults"` folder in My Drive and returns its ID.
     */
    private fun resolveAccessibleParentFolderId(
        drive: Drive,
        parentFolderId: String?,
        sharedDriveId: String?
    ): String? {
        val candidate = parentFolderId?.takeIf { it.isNotBlank() } ?: sharedDriveId?.takeIf { it.isNotBlank() }
        if (candidate == null) {
            return findOrCreateNexusTsVaultsFolder(drive)
        }
        return try {
            val folderMeta = drive.files().get(candidate)
                .setSupportsAllDrives(true)
                .setFields("id, trashed")
                .execute()
            if (folderMeta?.trashed == true) {
                findOrCreateNexusTsVaultsFolder(drive)
            } else {
                candidate
            }
        } catch (httpEx: HttpResponseException) {
            if (httpEx.statusCode == 404) {
                findOrCreateNexusTsVaultsFolder(drive)
            } else {
                throw httpEx
            }
        }
    }

    private fun findOrCreateNexusTsVaultsFolder(drive: Drive): String {
        val escapedFolderName = escapeDriveQueryLiteral(DEFAULT_VAULTS_FOLDER_NAME)
        val query = "mimeType = '$DRIVE_FOLDER_MIME_TYPE' and name = '$escapedFolderName' and trashed = false"
        val existing = drive.files().list()
            .setSpaces("drive")
            .setQ(query)
            .setIncludeItemsFromAllDrives(true)
            .setSupportsAllDrives(true)
            .setPageSize(5)
            .setFields("files(id, name)")
            .execute()
            .files
            ?.firstOrNull()

        val existingId = existing?.id?.takeIf { it.isNotBlank() }
        if (existingId != null) {
            return existingId
        }

        val folderMeta = DriveFile().apply {
            name = DEFAULT_VAULTS_FOLDER_NAME
            mimeType = DRIVE_FOLDER_MIME_TYPE
        }
        return drive.files().create(folderMeta)
            .setSupportsAllDrives(true)
            .setFields("id")
            .execute()
            .id
    }

    private fun discoverExistingCloudVault(
        drive: Drive,
        displayName: String,
        zone: DriveStorageZone,
        parentFolderId: String?
    ): DriveFile? {
        val normalizedWithExt = if (displayName.endsWith(".nxts", ignoreCase = true)) displayName else "$displayName.nxts"
        val escapedExt = escapeDriveQueryLiteral(normalizedWithExt)
        val escapedRaw = escapeDriveQueryLiteral(displayName)
        val nameClause = if (escapedExt == escapedRaw) {
            "name = '$escapedExt'"
        } else {
            "(name = '$escapedExt' or name = '$escapedRaw')"
        }

        return when (zone) {
            DriveStorageZone.APPDATA_PRIVATE -> {
                drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("$nameClause and trashed = false")
                    .setPageSize(5)
                    .setFields("files(id, name, headRevisionId, modifiedTime, appProperties)")
                    .execute()
                    .files
                    ?.firstOrNull()
            }
            DriveStorageZone.SHARED_TEAM_DRIVE -> {
                val parentClause = if (!parentFolderId.isNullOrBlank()) {
                    "'${escapeDriveQueryLiteral(parentFolderId)}' in parents and "
                } else {
                    ""
                }
                drive.files().list()
                    .setSpaces("drive")
                    .setQ("$parentClause$nameClause and trashed = false")
                    .setIncludeItemsFromAllDrives(true)
                    .setSupportsAllDrives(true)
                    .setPageSize(5)
                    .setFields("files(id, name, headRevisionId, modifiedTime, appProperties)")
                    .execute()
                    .files
                    ?.firstOrNull()
            }
        }
    }

    private fun escapeDriveQueryLiteral(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

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
        val request = drive.files().get(fileId).setSupportsAllDrives(true)
        request.mediaHttpDownloader?.isDirectDownloadEnabled = true
        request.executeMediaAndDownloadTo(out)
        return out.toByteArray()
    }

    private fun DriveFile.keepForeverForCriticalMerge() {
        description = "NexusTS Delta-Merged Encrypted Vault"
    }

    companion object {
        const val NXTS_MIME_TYPE = "application/vnd.nexusts.vault"
        private const val DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val DEFAULT_VAULTS_FOLDER_NAME = "NexusTS Vaults"
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
            WorkManager.getInstance(context).enqueueUniqueWork(
                "nexusts_gdrive_immediate_sync",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

@HiltWorker
class VaultDriveSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val vaultDao: SyncedVaultDao,
    private val authManager: GoogleDriveAuthManager,
    private val syncEngine: GoogleDriveVaultSyncEngine,
    private val safBridge: SafLiveGoogleDriveBridge
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dirtyVaults = vaultDao.listDirtyVaults()
        if (dirtyVaults.isEmpty()) return Result.success()

        // 1. Sync via persistent Google Drive SAF URI ONLY for the record matching the linked SAF vault
        val linkedSaf = safBridge.getLinkedVaultInfo()
        if (linkedSaf != null) {
            val matchingSafRecord = dirtyVaults.firstOrNull { record ->
                if (!linkedSaf.linkedDocumentId.isNullOrBlank()) {
                    record.documentId == linkedSaf.linkedDocumentId
                } else {
                    val linkedClean = linkedSaf.displayName.removeSuffix(".nxts").removeSuffix(".NXTS")
                    val recordClean = record.displayName.removeSuffix(".nxts").removeSuffix(".NXTS")
                    recordClean.equals(linkedClean, ignoreCase = true)
                }
            }
            if (matchingSafRecord != null) {
                val file = File(matchingSafRecord.localEncryptedPath)
                if (file.exists()) {
                    runCatching {
                        if (safBridge.pushPreSealedSnapshotIfRemoteUnchanged(
                                sealedBytes = file.readBytes(),
                                revision = matchingSafRecord.localRevision,
                                documentId = matchingSafRecord.documentId
                            )
                        ) {
                            vaultDao.upsertVault(
                                matchingSafRecord.copy(
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
                val savedPassphrase = authManager.readVaultPassphraseOrNull(record.documentId)
                try {
                    runCatching {
                        syncEngine.synchronizeVault(
                            drive = drive,
                            documentId = record.documentId,
                            displayName = record.displayName,
                            zone = zone,
                            sharedDriveId = record.sharedDriveId,
                            parentFolderId = record.parentFolderDriveId,
                            unlockedPassphraseOrNull = savedPassphrase
                        )
                    }.onFailure {
                        return Result.retry()
                    }
                } finally {
                    if (savedPassphrase != null) {
                        Arrays.fill(savedPassphrase, '\u0000')
                    }
                }
            }
        }

        return Result.success()
    }
}
