package com.nexusts.app.core.gdrivesync.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlin.math.max

data class LinkedSafDriveVault(
    val uriString: String,
    val displayName: String,
    val lastSyncedRevision: Long,
    val lastSyncedRemoteSha256: String,
    val lastSyncedEpochMs: Long
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
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        } catch (_: FileNotFoundException) {
            ByteArray(0)
        }
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
 * Zero-Cloud-Console Live Google Drive SAF Synchronization Bridge (`v1.1`).
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

    fun linkPersistentVaultUri(uri: Uri, takePersistable: Boolean = true): LinkedSafDriveVault {
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
            .apply()

        return LinkedSafDriveVault(
            uriString = uriStr,
            displayName = displayName,
            lastSyncedRevision = 0L,
            lastSyncedRemoteSha256 = "",
            lastSyncedEpochMs = 0L
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
            lastSyncedEpochMs = prefs.getLong(KEY_LAST_SYNCED_EPOCH_MS, 0L)
        )
    }

    suspend fun synchronizeLinkedSafVault(
        currentDocument: NxtsVaultDocument,
        vaultPassphrase: CharArray,
        localDeviceId: String = "Googlebook-OS"
    ): SafDriveSyncOutcome = withContext(Dispatchers.IO) {
        val linked = getLinkedVaultInfo()
            ?: error("No persistent Google Drive .nxts file is linked yet.")

        val remoteBytes = byteTransport.readBytes(linked.uriString)

        // Case 1: Newly created empty .nxts document in Google Drive
        if (remoteBytes.isEmpty()) {
            val bumpedDoc = bumpLocalVectorClock(currentDocument, localDeviceId)
            val sealed = serializer.sealDocument(bumpedDoc, vaultPassphrase.copyOf())
            byteTransport.writeBytes(linked.uriString, sealed)
            recordSyncSuccess(bumpedDoc.revision, sha256Hex(sealed))
            return@withContext SafDriveSyncOutcome.PushedToDrive(bumpedDoc, sealed.size)
        }

        val remoteSha = sha256Hex(remoteBytes)

        // Case 2: Remote file matches last synced hash — check if local edits need pushing
        if (linked.lastSyncedRemoteSha256.isNotBlank() && remoteSha == linked.lastSyncedRemoteSha256) {
            return@withContext if (currentDocument.revision > linked.lastSyncedRevision) {
                val bumpedDoc = bumpLocalVectorClock(currentDocument, localDeviceId)
                val sealed = serializer.sealDocument(bumpedDoc, vaultPassphrase.copyOf())
                byteTransport.writeBytes(linked.uriString, sealed)
                recordSyncSuccess(bumpedDoc.revision, sha256Hex(sealed))
                SafDriveSyncOutcome.PushedToDrive(bumpedDoc, sealed.size)
            } else {
                SafDriveSyncOutcome.AlreadyUpToDate(currentDocument.revision)
            }
        }

        // Unseal remote Google Drive .nxts container
        val remoteDoc = serializer.unsealDocument(remoteBytes, vaultPassphrase.copyOf())

        // Case 3: Initial link to an existing Google Drive .nxts vault
        if (linked.lastSyncedRemoteSha256.isBlank()) {
            if (currentDocument.documentId != remoteDoc.documentId || currentDocument == remoteDoc) {
                recordSyncSuccess(remoteDoc.revision, remoteSha)
                return@withContext SafDriveSyncOutcome.PulledFromDrive(remoteDoc)
            }
            val relation = VaultDeltaConflictResolver.compareVectorClocks(
                currentDocument.vectorClock,
                remoteDoc.vectorClock
            )
            return@withContext when (relation) {
                VectorClockRelation.IDENTICAL, VectorClockRelation.REMOTE_AHEAD -> {
                    recordSyncSuccess(remoteDoc.revision, remoteSha)
                    SafDriveSyncOutcome.PulledFromDrive(remoteDoc)
                }
                VectorClockRelation.LOCAL_AHEAD -> {
                    val sealed = serializer.sealDocument(currentDocument, vaultPassphrase.copyOf())
                    byteTransport.writeBytes(linked.uriString, sealed)
                    recordSyncSuccess(currentDocument.revision, sha256Hex(sealed))
                    SafDriveSyncOutcome.PushedToDrive(currentDocument, sealed.size)
                }
                VectorClockRelation.CONCURRENT_DIVERGENCE -> {
                    val merge = VaultDeltaConflictResolver.mergeDocuments(currentDocument, remoteDoc, localDeviceId)
                    val sealed = serializer.sealDocument(merge.mergedDocument, vaultPassphrase.copyOf())
                    byteTransport.writeBytes(linked.uriString, sealed)
                    recordSyncSuccess(merge.mergedDocument.revision, sha256Hex(sealed))
                    SafDriveSyncOutcome.ThreeWayMerged(merge.mergedDocument, merge.detectedConflicts)
                }
            }
        }

        // Case 4: Remote changed since last sync — pull cleanly if local unchanged, else 3-way merge!
        val localHasEdits = currentDocument.revision > linked.lastSyncedRevision
        if (!localHasEdits) {
            recordSyncSuccess(remoteDoc.revision, remoteSha)
            return@withContext SafDriveSyncOutcome.PulledFromDrive(remoteDoc)
        }

        val mergeOutcome = VaultDeltaConflictResolver.mergeDocuments(currentDocument, remoteDoc, localDeviceId)
        val mergedBytes = serializer.sealDocument(mergeOutcome.mergedDocument, vaultPassphrase.copyOf())
        byteTransport.writeBytes(linked.uriString, mergedBytes)
        recordSyncSuccess(mergeOutcome.mergedDocument.revision, sha256Hex(mergedBytes))
        SafDriveSyncOutcome.ThreeWayMerged(mergeOutcome.mergedDocument, mergeOutcome.detectedConflicts)
    }

    suspend fun pushPreSealedSnapshotIfRemoteUnchanged(
        sealedBytes: ByteArray,
        revision: Long
    ): Boolean = withContext(Dispatchers.IO) {
        val linked = getLinkedVaultInfo() ?: return@withContext false
        val remoteBytes = byteTransport.readBytes(linked.uriString)
        val remoteUnchanged = remoteBytes.isEmpty() ||
            linked.lastSyncedRemoteSha256.isBlank() ||
            sha256Hex(remoteBytes) == linked.lastSyncedRemoteSha256
        if (!remoteUnchanged) return@withContext false

        byteTransport.writeBytes(linked.uriString, sealedBytes)
        recordSyncSuccess(revision, sha256Hex(sealedBytes))
        true
    }

    private fun bumpLocalVectorClock(doc: NxtsVaultDocument, deviceId: String): NxtsVaultDocument {
        val prev = doc.vectorClock[deviceId] ?: 0L
        val nextRev = max(doc.revision, 1L) + 1L
        return doc.copy(
            revision = nextRev,
            lastModifiedByDevice = deviceId,
            updatedAtEpochMs = System.currentTimeMillis(),
            vectorClock = doc.vectorClock + (deviceId to (prev + 1L))
        )
    }

    private fun recordSyncSuccess(revision: Long, remoteSha256: String) {
        prefs.edit()
            .putLong(KEY_LAST_SYNCED_REV, revision)
            .putString(KEY_LAST_REMOTE_SHA256, remoteSha256)
            .putLong(KEY_LAST_SYNCED_EPOCH_MS, System.currentTimeMillis())
            .apply()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFS_NAME = "nexusts_saf_drive_bridge"
        private const val KEY_URI_STRING = "linked_saf_uri"
        private const val KEY_DISPLAY_NAME = "linked_display_name"
        private const val KEY_LAST_SYNCED_REV = "last_synced_revision"
        private const val KEY_LAST_REMOTE_SHA256 = "last_remote_sha256"
        private const val KEY_LAST_SYNCED_EPOCH_MS = "last_synced_epoch_ms"
    }
}
