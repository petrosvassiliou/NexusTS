package com.nexusts.app.core.gdrivesync.cache

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SupportFactory

@Entity(tableName = "synced_vaults")
data class CachedVaultRecord(
    @PrimaryKey val documentId: String,
    val driveFileId: String?,
    val storageZone: String, // "APPDATA_PRIVATE" or "SHARED_TEAM_DRIVE"
    val sharedDriveId: String?,
    val parentFolderDriveId: String?,
    val displayName: String,
    val localRevision: Long,
    val lastSyncedHeadRevisionId: String?,
    val localEncryptedPath: String,
    val hasPendingLocalEdits: Boolean,
    val activeRemoteLockHolder: String?,
    val activeRemoteLockExpiresEpochMs: Long?,
    val lastSyncedEpochMs: Long
)

@Entity(tableName = "vault_backup_snapshots")
data class VaultSnapshotRecord(
    @PrimaryKey val snapshotId: String,
    val documentId: String,
    val revision: Long,
    val createdAtEpochMs: Long,
    val reason: String,
    val snapshotFilePath: String,
    val driveSnapshotFileId: String?
)

@Dao
interface SyncedVaultDao {
    @Query("SELECT * FROM synced_vaults ORDER BY displayName ASC")
    suspend fun listAllVaults(): List<CachedVaultRecord>

    @Query("SELECT * FROM synced_vaults WHERE documentId = :documentId LIMIT 1")
    suspend fun getByDocumentId(documentId: String): CachedVaultRecord?

    @Query("SELECT * FROM synced_vaults WHERE hasPendingLocalEdits = 1")
    suspend fun listDirtyVaults(): List<CachedVaultRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVault(record: CachedVaultRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: VaultSnapshotRecord)

    @Query("SELECT * FROM vault_backup_snapshots WHERE documentId = :documentId ORDER BY createdAtEpochMs DESC LIMIT :limit")
    suspend fun listSnapshots(documentId: String, limit: Int = 20): List<VaultSnapshotRecord>
}

@Database(
    entities = [CachedVaultRecord::class, VaultSnapshotRecord::class],
    version = 1,
    exportSchema = false
)
abstract class OfflineEncryptedSyncDatabase : RoomDatabase() {
    abstract fun syncedVaultDao(): SyncedVaultDao

    companion object {
        fun buildEncrypted(context: Context, sqlCipherPassphrase: ByteArray): OfflineEncryptedSyncDatabase {
            val supportFactory = SupportFactory(sqlCipherPassphrase.copyOf(), null, false)
            return Room.databaseBuilder(
                context.applicationContext,
                OfflineEncryptedSyncDatabase::class.java,
                "nexusts_gdrive_sync_meta.db"
            )
                .openHelperFactory(supportFactory)
                .fallbackToDestructiveMigration(dropAllTables = false)
                .build()
        }
    }
}
