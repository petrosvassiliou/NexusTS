package com.nexusts.app.core.gdrivesync.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.max

data class LinkedSafDriveVault @JvmOverloads constructor(
    val uriString: String,
    val displayName: String,
    val lastSyncedRevision: Long,
    val lastSyncedRemoteSha256: String,
    val lastSyncedEpochMs: Long,
    val linkedDocumentId: String? = null
)

sealed interface SafDriveSyncOutcome {
    data class AlreadyUpToDate(val revision: Long) : SafDriveSyncOutcome
    data class PushedToDrive(val updatedDocument: NxtsVaultDocument, val bytesWritten: Int) : SafDriveSyncOutcome
    data class PulledFromDrive(val remoteDocument: NxtsVaultDocument) : SafDriveSyncOutcome
    data class ThreeWayMerged(
        val mergedDocument: NxtsVaultDocument,
        val resolvedConflicts: List<ObjectConflictEntry>
    ) : SafDriveSyncOutcome
}

interface SafByteTransport {
    fun readBytes(uriString: String): ByteArray
    fun writeBytes(uriString: String, payload: ByteArray)
    fun resolveDisplayName(uriString: String): String
}

class ContentResolverSafByteTransport(
    private val appContext: Context
) : SafByteTransport {
    override fun readBytes(uriString: String): ByteArray {
        val uri = Uri.parse(uriString)
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Google Drive provider returned null InputStream for $uriString")
        return input.use { it.readBytes() }
    }

    override fun writeBytes(uriString: String, payload: ByteArray) {
        val uri = Uri.parse(uriString)
        val out = appContext.contentResolver.openOutputStream(uri, "wt")
            ?: appContext.contentResolver.openOutputStream(uri)
            ?: error("Unable to open writable stream for Google Drive URI: $uriString")
        out.use { stream ->
            stream.write(payload)
            stream.flush()
        }
    }

    override fun resolveDisplayName(uriString: String): String {
        val uri = Uri.parse(uriString)
        runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(idx)?.trim()
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "NexusTS-Vault.nxts"
    }
}

/**
 * Zero-Cloud-Console Live Google Drive SAF Synchronization Bridge (`v1.2`).
 *
 * Enables direct-installed APKs on Googlebook OS & Android Tablets to synchronize Argon2id + AES-256-GCM
 * `.nxts` vaults directly with Personal Google Drive & Shared Team Drives (`com.google.android.apps.docs.storage`)
 * via persistent `takePersistableUriPermission` grants — with full 3-way Vector-Clock delta conflict resolution
 * and ZERO Google Cloud Console OAuth setup required!
 */
class SafLiveGoogleDriveBridge(
    private val appContext: Context,
    private val serializer: NxtsVaultSerializer,
    private val byteTransport: SafByteTransport = ContentResolverSafByteTransport(appContext)
) {
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val localBackupDir by lazy {
        File(appContext.filesDir, "vaults/saf-backups").apply { mkdirs() }
    }

    @JvmOverloads
    fun linkPersistentVaultUri(
        uri: Uri,
        takePersistable: Boolean = true,
        documentId: String? = null
    ): LinkedSafDriveVault {
        if (takePersistable) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                appContext.contentResolver.takePersistableUriPermission(uri, flags)
            }
        }
        val uriStr = uri.toString()
        val displayName = byteTransport.resolveDisplayName(uriStr)
        prefs.edit()
            .putString(KEY_URI_STRING, uriStr)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putLong(KEY_LAST_SYNCED_REV, 0L)
            .putString(KEY_LAST_REMOTE_SHA256, "")
            .putLong(KEY_LAST_SYNCED_EPOCH_MS, 0L)
            .putString(KEY_LAST_SYNCED_DOC_ID, documentId.orEmpty())
            .apply()

        return LinkedSafDriveVault(
            uriString = uriStr,
            displayName = displayName,
            lastSyncedRevision = 0L,
            lastSyncedRemoteSha256 = "",
            lastSyncedEpochMs = 0L,
            linkedDocumentId = documentId?.takeIf { it.isNotBlank() }
        )
    }

    fun unlinkPersistentVaultUri() {
        val existingUri = prefs.getString(KEY_URI_STRING, null)
        if (!existingUri.isNullOrBlank()) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                appContext.contentResolver.releasePersistableUriPermission(Uri.parse(existingUri), flags)
            }
        }
        prefs.edit().clear().apply()
    }

    fun getLinkedVaultInfo(): LinkedSafDriveVault? {
        val uriStr = prefs.getString(KEY_URI_STRING, null)?.takeIf { it.isNotBlank() } ?: return null
        return LinkedSafDriveVault(
            uriString = uriStr,
            displayName = prefs.getString(KEY_DISPLAY_NAME, "NexusTS-Vault.nxts").orEmpty(),
            lastSyncedRevision = prefs.getLong(KEY_LAST_SYNCED_REV, 0L),
            lastSyncedRemoteSha256 = prefs.getString(KEY_LAST_REMOTE_SHA256, "").orEmpty(),
            lastSyncedEpochMs = prefs.getLong(KEY_LAST_SYNCED_EPOCH_MS, 0L),
            linkedDocumentId = prefs.getString(KEY_LAST_SYNCED_DOC_ID, null)?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun hasRemoteContentChanged(): Boolean = withContext(Dispatchers.IO) {
        val linked = getLinkedVaultInfo() ?: return@withContext false
        val remoteBytes = runCatching { byteTransport.readBytes(linked.uriString) }.getOrDefault(ByteArray(0))
        if (remoteBytes.isEmpty()) return@withContext false
        val currentSha = sha256Hex(remoteBytes)
        linked.lastSyncedRemoteSha256.isBlank() || currentSha != linked.lastSyncedRemoteSha256
    }

    @JvmOverloads
    suspend fun synchronizeLinkedSafVault(
        currentDocument: NxtsVaultDocument,
        vaultPassphrase: CharArray,
        localDeviceId: String = "Googlebook-OS",
        localIsUninitializedStarter: Boolean = false
    ): SafDriveSyncOutcome = PROCESS_SAF_SYNC_MUTEX.withLock {
        withContext(Dispatchers.IO) {
            val linked = getLinkedVaultInfo()
                ?: error("No persistent Google Drive .nxts file is linked yet.")

            val hadPriorRemoteSync = linked.lastSyncedRemoteSha256.isNotBlank() || linked.lastSyncedRevision > 0L
            val remoteBytes = try {
                byteTransport.readBytes(linked.uriString)
            } catch (fnf: FileNotFoundException) {
                if (hadPriorRemoteSync) {
                    throw IOException(
                        "Linked Google Drive vault '${linked.displayName}' is temporarily unavailable (DriveFS may still be mounting). Sync aborted to protect remote data.",
                        fnf
                    )
                }
                ByteArray(0)
            }

            // Case 1: Newly created empty .nxts document in Google Drive
            // Guard: NEVER treat a 0-byte read as an empty new vault if this URI already has prior sync history!
            if (remoteBytes.isEmpty()) {
                if (hadPriorRemoteSync) {
                    throw IOException(
                        "Linked Google Drive vault '${linked.displayName}' returned 0 bytes despite prior sync history (DriveFS may still be hydrating). Sync aborted to prevent overwriting remote vault."
                    )
                }
                val docToSeal = alignLocalClockWithoutExtraRevisionBump(currentDocument, localDeviceId)
                val sealed = serializer.sealDocument(docToSeal, vaultPassphrase.copyOf())
                writeSafBytesWithBackup(linked.uriString, sealed, previousRemoteBytes = null, docId = docToSeal.documentId)
                recordSyncSuccess(docToSeal.revision, sha256Hex(sealed), docToSeal.documentId)
                return@withContext SafDriveSyncOutcome.PushedToDrive(docToSeal, sealed.size)
            }

            val remoteSha = sha256Hex(remoteBytes)

            // Case 2: Fast-path when remote file matches last synced hash AND local vault is the active synced document
            // (Never take this fast-path on a cold-started starter vault — always unseal and pull the real Drive vault!)
            val sameKnownDocId = !localIsUninitializedStarter &&
                !linked.linkedDocumentId.isNullOrBlank() &&
                currentDocument.documentId == linked.linkedDocumentId

            if (sameKnownDocId && linked.lastSyncedRemoteSha256.isNotBlank() && remoteSha == linked.lastSyncedRemoteSha256) {
                return@withContext if (currentDocument.revision > linked.lastSyncedRevision) {
                    val docToSeal = alignLocalClockWithoutExtraRevisionBump(currentDocument, localDeviceId)
                    val sealed = serializer.sealDocument(docToSeal, vaultPassphrase.copyOf())
                    writeSafBytesWithBackup(linked.uriString, sealed, previousRemoteBytes = remoteBytes, docId = docToSeal.documentId)
                    recordSyncSuccess(docToSeal.revision, sha256Hex(sealed), docToSeal.documentId)
                    SafDriveSyncOutcome.PushedToDrive(docToSeal, sealed.size)
                } else {
                    SafDriveSyncOutcome.AlreadyUpToDate(currentDocument.revision)
                }
            }

            // Unseal remote Google Drive .nxts container
            val remoteDoc = serializer.unsealDocument(remoteBytes, vaultPassphrase.copyOf())

            // Cold-start / uninitialized starter guard: never overwrite or pollute an existing remote Drive vault!
            if (localIsUninitializedStarter || currentDocument.documentId != remoteDoc.documentId) {
                recordSyncSuccess(remoteDoc.revision, remoteSha, remoteDoc.documentId)
                return@withContext SafDriveSyncOutcome.PulledFromDrive(remoteDoc)
            }

            // If remote SHA matched lastSyncedRemoteSha256 (e.g. linkedDocumentId was migrated from legacy prefs)
            if (linked.lastSyncedRemoteSha256.isNotBlank() && remoteSha == linked.lastSyncedRemoteSha256) {
                return@withContext if (currentDocument.revision > linked.lastSyncedRevision) {
                    val docToSeal = alignLocalClockWithoutExtraRevisionBump(currentDocument, localDeviceId)
                    val sealed = serializer.sealDocument(docToSeal, vaultPassphrase.copyOf())
                    writeSafBytesWithBackup(linked.uriString, sealed, previousRemoteBytes = remoteBytes, docId = docToSeal.documentId)
                    recordSyncSuccess(docToSeal.revision, sha256Hex(sealed), docToSeal.documentId)
                    SafDriveSyncOutcome.PushedToDrive(docToSeal, sealed.size)
                } else {
                    recordSyncSuccess(remoteDoc.revision, remoteSha, remoteDoc.documentId)
                    SafDriveSyncOutcome.AlreadyUpToDate(currentDocument.revision)
                }
            }

            // Case 3: Initial link to an existing Google Drive .nxts vault with the same documentId
            if (linked.lastSyncedRemoteSha256.isBlank()) {
                if (currentDocument == remoteDoc) {
                    recordSyncSuccess(remoteDoc.revision, remoteSha, remoteDoc.documentId)
                    return@withContext SafDriveSyncOutcome.PulledFromDrive(remoteDoc)
                }
                val relation = VaultDeltaConflictResolver.compareVectorClocks(
                    currentDocument.vectorClock,
                    remoteDoc.vectorClock
                )
                return@withContext when (relation) {
                    VectorClockRelation.IDENTICAL, VectorClockRelation.REMOTE_AHEAD -> {
                        recordSyncSuccess(remoteDoc.revision, remoteSha, remoteDoc.documentId)
                        SafDriveSyncOutcome.PulledFromDrive(remoteDoc)
                    }
                    VectorClockRelation.LOCAL_AHEAD -> {
                        val docToSeal = alignLocalClockWithoutExtraRevisionBump(currentDocument, localDeviceId)
                        val sealed = serializer.sealDocument(docToSeal, vaultPassphrase.copyOf())
                        writeSafBytesWithBackup(linked.uriString, sealed, previousRemoteBytes = remoteBytes, docId = docToSeal.documentId)
                        recordSyncSuccess(docToSeal.revision, sha256Hex(sealed), docToSeal.documentId)
                        SafDriveSyncOutcome.PushedToDrive(docToSeal, sealed.size)
                    }
                    VectorClockRelation.CONCURRENT_DIVERGENCE -> {
                        val merge = VaultDeltaConflictResolver.mergeDocuments(currentDocument, remoteDoc, localDeviceId)
                        val sealed = serializer.sealDocument(merge.mergedDocument, vaultPassphrase.copyOf())
                        writeSafBytesWithBackup(linked.uriString, sealed, previousRemoteBytes = remoteBytes, docId = merge.mergedDocument.documentId)
                        recordSyncSuccess(merge.mergedDocument.revision, sha256Hex(sealed), merge.mergedDocument.documentId)
                        SafDriveSyncOutcome.ThreeWayMerged(merge.mergedDocument, merge.detectedConflicts)
                    }
                }
            }

            // Case 4: Remote changed since last sync — pull cleanly if local unchanged, else 3-way merge!
            val localHasEdits = currentDocument.revision > linked.lastSyncedRevision
            if (!localHasEdits) {
                recordSyncSuccess(remoteDoc.revision, remoteSha, remoteDoc.documentId)
                return@withContext SafDriveSyncOutcome.PulledFromDrive(remoteDoc)
            }

            val mergeOutcome = VaultDeltaConflictResolver.mergeDocuments(currentDocument, remoteDoc, localDeviceId)
            val mergedBytes = serializer.sealDocument(mergeOutcome.mergedDocument, vaultPassphrase.copyOf())
            writeSafBytesWithBackup(linked.uriString, mergedBytes, previousRemoteBytes = remoteBytes, docId = mergeOutcome.mergedDocument.documentId)
            recordSyncSuccess(mergeOutcome.mergedDocument.revision, sha256Hex(mergedBytes), mergeOutcome.mergedDocument.documentId)
            SafDriveSyncOutcome.ThreeWayMerged(mergeOutcome.mergedDocument, mergeOutcome.detectedConflicts)
        }
    }

    @JvmOverloads
    suspend fun pushPreSealedSnapshotIfRemoteUnchanged(
        sealedBytes: ByteArray,
        revision: Long,
        documentId: String? = null
    ): Boolean = PROCESS_SAF_SYNC_MUTEX.withLock {
        withContext(Dispatchers.IO) {
            val linked = getLinkedVaultInfo() ?: return@withContext false
            if (!linked.linkedDocumentId.isNullOrBlank() &&
                !documentId.isNullOrBlank() &&
                linked.linkedDocumentId != documentId
            ) {
                return@withContext false
            }
            val hadPriorRemoteSync = linked.lastSyncedRemoteSha256.isNotBlank() || linked.lastSyncedRevision > 0L
            val remoteBytes = try {
                byteTransport.readBytes(linked.uriString)
            } catch (_: FileNotFoundException) {
                if (hadPriorRemoteSync) return@withContext false
                ByteArray(0)
            }
            if (remoteBytes.isEmpty() && hadPriorRemoteSync) {
                return@withContext false
            }
            val remoteUnchanged = remoteBytes.isEmpty() ||
                linked.lastSyncedRemoteSha256.isBlank() ||
                sha256Hex(remoteBytes) == linked.lastSyncedRemoteSha256
            if (!remoteUnchanged) return@withContext false

            writeSafBytesWithBackup(
                uriString = linked.uriString,
                payload = sealedBytes,
                previousRemoteBytes = remoteBytes.takeIf { it.isNotEmpty() },
                docId = documentId ?: linked.linkedDocumentId.orEmpty()
            )
            recordSyncSuccess(revision, sha256Hex(sealedBytes), documentId ?: linked.linkedDocumentId)
            true
        }
    }

    private fun writeSafBytesWithBackup(
        uriString: String,
        payload: ByteArray,
        previousRemoteBytes: ByteArray?,
        docId: String
    ) {
        if (previousRemoteBytes != null && previousRemoteBytes.isNotEmpty()) {
            runCatching {
                val safeId = docId.ifBlank { "vault" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                File(localBackupDir, "$safeId-prewrite.nxts.bak").writeBytes(previousRemoteBytes)
            }
        }
        byteTransport.writeBytes(uriString, payload)
    }

    /**
     * Aligns the local vector clock at `doc.revision` WITHOUT incrementing `revision` a second time,
     * preventing `R+1 -> R+2` auto-sync loops when callers already incremented `doc.revision` on save.
     */
    private fun alignLocalClockWithoutExtraRevisionBump(
        doc: NxtsVaultDocument,
        deviceId: String
    ): NxtsVaultDocument {
        val targetRev = max(doc.revision, 1L)
        val updatedClock = doc.vectorClock.toMutableMap()
        updatedClock[deviceId] = max(updatedClock[deviceId] ?: 0L, targetRev)
        if (deviceId.equals("Googlebook-OS", ignoreCase = true) || deviceId.equals("googlebook", ignoreCase = true)) {
            updatedClock["googlebook"] = max(updatedClock["googlebook"] ?: 0L, targetRev)
        }
        return doc.copy(
            revision = targetRev,
            lastModifiedByDevice = deviceId,
            vectorClock = updatedClock
        )
    }

    private fun recordSyncSuccess(revision: Long, remoteSha256: String, documentId: String? = null) {
        val editor = prefs.edit()
            .putLong(KEY_LAST_SYNCED_REV, revision)
            .putString(KEY_LAST_REMOTE_SHA256, remoteSha256)
            .putLong(KEY_LAST_SYNCED_EPOCH_MS, System.currentTimeMillis())
        if (!documentId.isNullOrBlank()) {
            editor.putString(KEY_LAST_SYNCED_DOC_ID, documentId)
        }
        editor.apply()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** Process-wide mutex serializing MainActivity foreground SAF syncs and WorkManager background SAF syncs. */
        private val PROCESS_SAF_SYNC_MUTEX = Mutex()
        private const val PREFS_NAME = "nexusts_saf_drive_bridge"
        private const val KEY_URI_STRING = "linked_saf_uri"
        private const val KEY_DISPLAY_NAME = "linked_display_name"
        private const val KEY_LAST_SYNCED_REV = "last_synced_revision"
        private const val KEY_LAST_REMOTE_SHA256 = "last_remote_sha256"
        private const val KEY_LAST_SYNCED_EPOCH_MS = "last_synced_epoch_ms"
        private const val KEY_LAST_SYNCED_DOC_ID = "last_synced_doc_id"
    }
}
