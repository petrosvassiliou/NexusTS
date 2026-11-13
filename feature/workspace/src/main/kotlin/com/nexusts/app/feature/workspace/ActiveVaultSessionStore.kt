package com.nexusts.app.feature.workspace

import com.nexusts.app.core.ui.workspace.WorkspaceSessionTab
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.protocols.rdp.bridge.RdpManagedSession
import com.nexusts.app.protocols.ssh.bridge.SshTerminalSessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class AdHocLaunchOverride(
    val overrideCredentialId: String? = null,
    val adHocUsername: String = "",
    val adHocDomain: String = "",
    val adHocPassword: String = "",
    val forceRdpConsoleAdmin: Boolean? = null,
    val appliedTemplateId: String? = null,
    val adHocHost: String? = null,
    val adHocPort: Int? = null
)

data class SharedVaultSessionSnapshot(
    val vaultDocument: NxtsVaultDocument = NexusWorkspaceViewModel.createEnterpriseStarterVault(),
    val isVaultLocked: Boolean = false,
    val sealedLockedVaultSnapshot: ByteArray? = null,
    val pendingImportFileName: String? = null,
    val hasPreImportBackup: Boolean = false,
    val vaultUnlockError: String? = null,
    val isStarterUninitialized: Boolean = true,
    val mutationRevision: Long = 0L
)

/**
 * Process-wide reactive Vault & Live Session store shared between [com.nexusts.app.MainActivity]
 * and all freeform multi-monitor [com.nexusts.app.DetachedSessionActivity] windows on Googlebook OS
 * so folder credentials, RDP/SSH sessions, re-docking, encrypted disk persistence, and Zero-Knowledge
 * vault lock state remain 100% synchronized in real time.
 */
@Singleton
class ActiveVaultSessionStore @Inject constructor() {

    private val _snapshot = MutableStateFlow(SharedVaultSessionSnapshot())
    val snapshot: StateFlow<SharedVaultSessionSnapshot> = _snapshot.asStateFlow()

    val sshControllers = ConcurrentHashMap<String, SshTerminalSessionController>()
    val rdpSessions = ConcurrentHashMap<String, RdpManagedSession>()
    val adHocOverrides = ConcurrentHashMap<String, AdHocLaunchOverride>()

    private val _pendingReDockTabs = MutableStateFlow<List<WorkspaceSessionTab>>(emptyList())
    val pendingReDockTabs: StateFlow<List<WorkspaceSessionTab>> = _pendingReDockTabs.asStateFlow()

    private val passphraseLock = Any()
    private var activePassphraseChars: CharArray? = null

    private var preImportBackupDocument: NxtsVaultDocument? = null
    private var preImportWasStarter: Boolean = true

    fun setActivePassphrase(passphrase: CharArray?) {
        synchronized(passphraseLock) {
            activePassphraseChars?.let { Arrays.fill(it, '\u0000') }
            activePassphraseChars = passphrase?.takeIf { it.isNotEmpty() }?.copyOf()
        }
    }

    fun copyActivePassphraseOrNull(): CharArray? = synchronized(passphraseLock) {
        activePassphraseChars?.copyOf()
    }

    fun copyActivePassphraseOrDefault(
        defaultFallback: String = DEFAULT_LOCAL_VAULT_PASSPHRASE
    ): CharArray = synchronized(passphraseLock) {
        activePassphraseChars?.copyOf() ?: defaultFallback.toCharArray()
    }

    fun clearActivePassphrase() {
        synchronized(passphraseLock) {
            activePassphraseChars?.let { Arrays.fill(it, '\u0000') }
            activePassphraseChars = null
        }
    }

    fun updateVaultDocument(transform: (NxtsVaultDocument) -> NxtsVaultDocument) {
        _snapshot.update { current ->
            current.copy(
                vaultDocument = transform(current.vaultDocument),
                isStarterUninitialized = false,
                vaultUnlockError = null,
                mutationRevision = current.mutationRevision + 1L
            )
        }
    }

    /**
     * Updates non-structural UI metadata (such as [NxtsVaultDocument.recentConnectionIds]) without
     * marking a cold-start starter vault as user-initialized, ensuring starter vaults never overwrite
     * remote Google Drive vaults before first sync.
     */
    fun updateMetadataWithoutMarkingInitialized(transform: (NxtsVaultDocument) -> NxtsVaultDocument) {
        _snapshot.update { current ->
            current.copy(
                vaultDocument = transform(current.vaultDocument),
                mutationRevision = current.mutationRevision + 1L
            )
        }
    }

    fun replaceUnlockedVault(document: NxtsVaultDocument, markInitialized: Boolean = true) {
        preImportBackupDocument = null
        _snapshot.update { current ->
            current.copy(
                vaultDocument = document,
                isVaultLocked = false,
                sealedLockedVaultSnapshot = null,
                pendingImportFileName = null,
                hasPreImportBackup = false,
                vaultUnlockError = null,
                isStarterUninitialized = !markInitialized,
                mutationRevision = current.mutationRevision + 1L
            )
        }
    }

    fun lockWithSealedBytes(sealedBytes: ByteArray) {
        clearActivePassphrase()
        closeAllLiveSessions()
        preImportBackupDocument = null
        _snapshot.update { current ->
            val scrubbedShell = NxtsVaultDocument(
                documentId = current.vaultDocument.documentId,
                name = current.vaultDocument.name,
                revision = current.vaultDocument.revision
            )
            current.copy(
                vaultDocument = scrubbedShell,
                isVaultLocked = true,
                sealedLockedVaultSnapshot = sealedBytes,
                pendingImportFileName = null,
                hasPreImportBackup = false,
                vaultUnlockError = null,
                isStarterUninitialized = false,
                mutationRevision = current.mutationRevision + 1L
            )
        }
    }

    fun stageEncryptedBytesForUnlock(sealedBytes: ByteArray, pendingFileName: String) {
        _snapshot.update { current ->
            if (!current.hasPreImportBackup) {
                preImportBackupDocument = current.vaultDocument
                preImportWasStarter = current.isStarterUninitialized
            }
            val scrubbedShell = NxtsVaultDocument(
                documentId = current.vaultDocument.documentId,
                name = pendingFileName,
                revision = current.vaultDocument.revision
            )
            current.copy(
                vaultDocument = scrubbedShell,
                isVaultLocked = true,
                sealedLockedVaultSnapshot = sealedBytes,
                pendingImportFileName = pendingFileName,
                hasPreImportBackup = preImportBackupDocument != null,
                vaultUnlockError = null,
                mutationRevision = current.mutationRevision + 1L
            )
        }
    }

    fun cancelPendingImport(): NxtsVaultDocument {
        val restored = preImportBackupDocument ?: _snapshot.value.vaultDocument
        val restoredStarter = preImportWasStarter
        preImportBackupDocument = null
        _snapshot.update { current ->
            current.copy(
                vaultDocument = restored,
                isVaultLocked = false,
                sealedLockedVaultSnapshot = null,
                pendingImportFileName = null,
                hasPreImportBackup = false,
                vaultUnlockError = null,
                isStarterUninitialized = restoredStarter,
                mutationRevision = current.mutationRevision + 1L
            )
        }
        return restored
    }

    fun setUnlockError(message: String?) {
        _snapshot.update { current ->
            current.copy(vaultUnlockError = message)
        }
    }

    fun clearLockWithoutBlob() {
        _snapshot.update { current ->
            current.copy(
                isVaultLocked = false,
                vaultUnlockError = null,
                mutationRevision = current.mutationRevision + 1L
            )
        }
    }

    fun closeTabResources(tabId: String) {
        adHocOverrides.remove(tabId)
        sshControllers.remove(tabId)?.let { runCatching { it.close() } }
        rdpSessions.remove(tabId)?.let { runCatching { it.close() } }
    }

    fun evictSessionsForConnectionIds(connectionIds: Set<String>, matchingTabIds: Set<String> = emptySet()) {
        if (connectionIds.isEmpty() && matchingTabIds.isEmpty()) return
        val rdpToEvict = rdpSessions.entries
            .filter { (tabId, session) -> session.connection.id in connectionIds || tabId in matchingTabIds }
            .map { it.key }
        rdpToEvict.forEach { tabId ->
            rdpSessions.remove(tabId)?.let { runCatching { it.close() } }
        }
        matchingTabIds.forEach { tabId ->
            sshControllers.remove(tabId)?.let { runCatching { it.close() } }
        }
    }

    fun closeAllLiveSessions() {
        adHocOverrides.clear()
        sshControllers.values.forEach { runCatching { it.close() } }
        sshControllers.clear()
        rdpSessions.values.forEach { runCatching { it.close() } }
        rdpSessions.clear()
    }

    fun enqueueReDockTab(tab: WorkspaceSessionTab) {
        _pendingReDockTabs.update { it + tab }
    }

    fun consumePendingReDockTabs(): List<WorkspaceSessionTab> {
        var drained: List<WorkspaceSessionTab> = emptyList()
        _pendingReDockTabs.update { current ->
            drained = current
            emptyList()
        }
        return drained
    }

    /**
     * Atomically seals and persists the active vault container to `filesDir/nexusts_local_vault.nxts`.
     */
    fun persistVaultToDisk(
        filesDir: File,
        serializer: NxtsVaultSerializer,
        explicitPassphrase: CharArray? = null
    ): File {
        filesDir.mkdirs()
        val targetFile = File(filesDir, LOCAL_VAULT_FILE_NAME)
        val tempFile = File(filesDir, "$LOCAL_VAULT_FILE_NAME.tmp")
        val snap = _snapshot.value

        val sealedBytes = if (snap.isVaultLocked && snap.sealedLockedVaultSnapshot != null && !snap.hasPreImportBackup) {
            snap.sealedLockedVaultSnapshot
        } else {
            val pass = explicitPassphrase?.copyOf() ?: copyActivePassphraseOrDefault()
            try {
                serializer.sealDocument(snap.vaultDocument, pass.copyOf())
            } finally {
                Arrays.fill(pass, '\u0000')
            }
        }

        tempFile.writeBytes(sealedBytes)
        if (!tempFile.renameTo(targetFile)) {
            targetFile.writeBytes(sealedBytes)
            tempFile.delete()
        }
        return targetFile
    }

    /**
     * Restores `filesDir/nexusts_local_vault.nxts` on cold start if the store still holds the uninitialized
     * starter vault. If sealed with a custom passphrase not present in [candidatePassphrases], stages the
     * encrypted container in locked mode so the user unlocks it on the Full-Screen Vault Lock Shield.
     */
    fun loadPersistedVaultIfNeeded(
        filesDir: File,
        serializer: NxtsVaultSerializer,
        candidatePassphrases: List<CharArray> = emptyList()
    ): Boolean {
        if (!_snapshot.value.isStarterUninitialized) return false
        val targetFile = File(filesDir, LOCAL_VAULT_FILE_NAME)
        if (!targetFile.isFile || targetFile.length() <= 0L) return false

        val sealedBytes = runCatching { targetFile.readBytes() }.getOrNull() ?: return false
        if (sealedBytes.isEmpty()) return false

        val allCandidates = buildList {
            candidatePassphrases.filter { it.isNotEmpty() }.forEach { add(it.copyOf()) }
            add(DEFAULT_LOCAL_VAULT_PASSPHRASE.toCharArray())
        }

        try {
            for (candidate in allCandidates) {
                val doc = runCatching { serializer.unsealDocument(sealedBytes, candidate.copyOf()) }.getOrNull()
                if (doc != null) {
                    setActivePassphrase(candidate)
                    replaceUnlockedVault(doc, markInitialized = true)
                    return true
                }
            }
        } finally {
            allCandidates.forEach { Arrays.fill(it, '\u0000') }
        }

        // Custom user passphrase required — present Full-Screen Zero-Knowledge Unlock Shield
        preImportBackupDocument = null
        _snapshot.update { current ->
            current.copy(
                vaultDocument = NxtsVaultDocument(
                    documentId = "locked-persisted-vault",
                    name = LOCAL_VAULT_FILE_NAME,
                    revision = 1L
                ),
                isVaultLocked = true,
                sealedLockedVaultSnapshot = sealedBytes,
                pendingImportFileName = null,
                hasPreImportBackup = false,
                vaultUnlockError = null,
                isStarterUninitialized = false,
                mutationRevision = current.mutationRevision + 1L
            )
        }
        return true
    }

    companion object {
        const val LOCAL_VAULT_FILE_NAME = "nexusts_local_vault.nxts"
        const val DEFAULT_LOCAL_VAULT_PASSPHRASE = "NexusTS-LiveDriveSync-ZeroKnowledge-v1"
    }
}
