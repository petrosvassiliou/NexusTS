package com.nexusts.app.di

import android.content.Context
import android.provider.Settings
import com.nexusts.app.core.gdrivesync.auth.GoogleDriveAuthManager
import com.nexusts.app.core.gdrivesync.cache.OfflineEncryptedSyncDatabase
import com.nexusts.app.core.gdrivesync.cache.SyncedVaultDao
import com.nexusts.app.core.gdrivesync.sync.GoogleDriveVaultSyncEngine
import com.nexusts.app.core.ui.workspace.GooglebookWindowController
import com.nexusts.app.core.vault.automation.AutomationTaskEngine
import com.nexusts.app.core.vault.credentials.EnterpriseCredentialHub
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.inheritance.PropertyInheritanceResolver
import com.nexusts.app.updater.PlayProtectCompliantUpdateCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NexusAppModule {

    @Provides
    @Singleton
    fun provideVaultSerializer(): NxtsVaultSerializer = NxtsVaultSerializer()

    @Provides
    @Singleton
    fun provideInheritanceResolver(): PropertyInheritanceResolver = PropertyInheritanceResolver()

    @Provides
    @Singleton
    fun provideCredentialHub(): EnterpriseCredentialHub = EnterpriseCredentialHub()

    @Provides
    @Singleton
    fun provideAutomationTaskEngine(): AutomationTaskEngine = AutomationTaskEngine()

    @Provides
    @Singleton
    fun provideGooglebookWindowController(
        @ApplicationContext context: Context
    ): GooglebookWindowController = GooglebookWindowController(context)

    @Provides
    @Singleton
    fun provideEncryptedSyncDatabase(
        @ApplicationContext context: Context
    ): OfflineEncryptedSyncDatabase {
        val deviceBoundSeed = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty() + ":com.nexusts.app.sqlcipher.v1"
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(deviceBoundSeed.toByteArray())
        return OfflineEncryptedSyncDatabase.buildEncrypted(context, keyBytes)
    }

    @Provides
    @Singleton
    fun provideSyncedVaultDao(db: OfflineEncryptedSyncDatabase): SyncedVaultDao = db.syncedVaultDao()

    @Provides
    @Singleton
    fun provideGoogleDriveAuthManager(
        @ApplicationContext context: Context
    ): GoogleDriveAuthManager = GoogleDriveAuthManager(context)

    @Provides
    @Singleton
    fun provideGoogleDriveSyncEngine(
        @ApplicationContext context: Context,
        dao: SyncedVaultDao,
        serializer: NxtsVaultSerializer
    ): GoogleDriveVaultSyncEngine = GoogleDriveVaultSyncEngine(context, dao, serializer)

    @Provides
    @Singleton
    fun provideSafLiveGoogleDriveBridge(
        @ApplicationContext context: Context,
        serializer: NxtsVaultSerializer
    ): com.nexusts.app.core.gdrivesync.sync.SafLiveGoogleDriveBridge =
        com.nexusts.app.core.gdrivesync.sync.SafLiveGoogleDriveBridge(context, serializer)

    @Provides
    @Singleton
    fun providePlayProtectUpdater(
        @ApplicationContext context: Context
    ): PlayProtectCompliantUpdateCoordinator = PlayProtectCompliantUpdateCoordinator(context)
}
