package com.nexusts.app.feature.workspace

import com.nexusts.app.core.vault.model.NxtsVaultDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class SharedVaultSessionSnapshot(
    val vaultDocument: NxtsVaultDocument = NexusWorkspaceViewModel.createEnterpriseStarterVault(),
    val isVaultLocked: Boolean = false,
    val sealedLockedVaultSnapshot: ByteArray? = null,
    val mutationRevision: Long = 0L
)

/**
 * Process-wide reactive Vault session store shared between [com.nexusts.app.MainActivity] and all
 * freeform multi-monitor [com.nexusts.app.DetachedSessionActivity] windows on Googlebook OS so
 * folder credentials, RDP settings, and vault lock state remain 100% synchronized in real time.
 */
@Singleton
class ActiveVaultSessionStore @Inject constructor() {

    private val _snapshot = MutableStateFlow(SharedVaultSessionSnapshot())
    val snapshot: StateFlow<SharedVaultSessionSnapshot> = _snapshot.asStateFlow()

    fun updateVaultDocument(transform: (NxtsVaultDocument) -> NxtsVaultDocument) {
        _snapshot.update { current ->
            current.copy(
                vaultDocument = transform(current.vaultDocument),
                mutationRevision = current.mutationRevision + 1L
            )
        }
    }

    fun replaceUnlockedVault(document: NxtsVaultDocument) {
        _snapshot.update { current ->
            current.copy(
                vaultDocument = document,
                isVaultLocked = false,
                sealedLockedVaultSnapshot = null,
                mutationRevision = current.mutationRevision + 1L
            )
        }
    }

    fun lockWithSealedBytes(sealedBytes: ByteArray) {
        _snapshot.update { current ->
            current.copy(
                isVaultLocked = true,
                sealedLockedVaultSnapshot = sealedBytes,
                mutationRevision = current.mutationRevision + 1L
            )
        }
    }

    fun stageEncryptedBytesForUnlock(sealedBytes: ByteArray, pendingFileName: String) {
        _snapshot.update { current ->
            current.copy(
                vaultDocument = current.vaultDocument.copy(name = pendingFileName),
                isVaultLocked = true,
                sealedLockedVaultSnapshot = sealedBytes,
                mutationRevision = current.mutationRevision + 1L
            )
        }
    }

    fun clearLockWithoutBlob() {
        _snapshot.update { current ->
            current.copy(
                isVaultLocked = false,
                mutationRevision = current.mutationRevision + 1L
            )
        }
    }
}
