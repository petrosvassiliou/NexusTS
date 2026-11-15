package com.nexusts.app.core.vault.importer

import com.nexusts.app.core.vault.error.VaultHierarchySanitizer
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.VaultFolder
import java.util.UUID

/**
 * Controls how an imported Royal TS / Royal TSX (`.rtsz`, `.rtsx`, `.rjson`) document is placed
 * into the user's NexusTS hierarchy.
 */
enum class RoyalTsImportPlacementMode {
    /** Creates a new top-level folder and places the imported connection hierarchy inside it. */
    CREATE_NEW_FOLDER,

    /** Places the imported folders and connections directly at the root of the existing hierarchy. */
    PLACE_AT_ROOT,

    /** Deletes all existing connections & connection folders and replaces them with the imported hierarchy. */
    REPLACE_EXISTING
}

object RoyalTsImportMerger {

    /**
     * Cleans Royal TS system wrapper folders:
     * 1. Absorbs any empty/credential-only root `"Credentials"` folder into the system Credentials store.
     * 2. Optionally unwraps Royal TS's built-in root `"Connections"` wrapper folder if it is the sole
     *    top-level connection container and holds no custom credential/gateway inheritance overrides.
     */
    fun normalizeImportedRoyalDocument(
        imported: NxtsVaultDocument,
        unwrapBuiltInConnectionsRoot: Boolean = true
    ): NxtsVaultDocument {
        val sanitized = VaultHierarchySanitizer.sanitizeVaultDocument(imported)
        if (!unwrapBuiltInConnectionsRoot) return sanitized

        val rootFolders = sanitized.folders.filter { it.parentFolderId == null }
        val rootConnections = sanitized.connections.filter { it.parentFolderId == null }
        if (rootFolders.size == 1 && rootConnections.isEmpty()) {
            val soleRoot = rootFolders.first()
            val isBuiltInConnectionsWrapper = soleRoot.name.trim().equals("Connections", ignoreCase = true) ||
                soleRoot.name.trim().equals("Verbindungen", ignoreCase = true)
            val hasCustomInheritance = soleRoot.inheritable.explicitCredentialId != null ||
                soleRoot.inheritable.explicitGatewayId != null ||
                soleRoot.inheritable.environmentBadge != null
            if (isBuiltInConnectionsWrapper && !hasCustomInheritance) {
                val unwrappedFolders = sanitized.folders
                    .filterNot { it.id == soleRoot.id }
                    .map { f ->
                        if (f.parentFolderId == soleRoot.id) f.copy(parentFolderId = null) else f
                    }
                val unwrappedConnections = sanitized.connections.map { c ->
                    if (c.parentFolderId == soleRoot.id) c.copy(parentFolderId = null) else c
                }
                val unwrappedDynamic = sanitized.dynamicFolders.map { df ->
                    if (df.parentFolderId == soleRoot.id) df.copy(parentFolderId = null) else df
                }
                return sanitized.copy(
                    folders = unwrappedFolders,
                    connections = unwrappedConnections,
                    dynamicFolders = unwrappedDynamic
                )
            }
        }
        return sanitized
    }

    /**
     * Applies [mode] to merge or replace [existingDoc] with [rawImportedDoc], remapping colliding IDs
     * when merging so repeated imports into separate folders never clobber earlier imports.
     */
    fun applyPlacement(
        existingDoc: NxtsVaultDocument,
        rawImportedDoc: NxtsVaultDocument,
        mode: RoyalTsImportPlacementMode,
        newFolderName: String = "",
        actorDevice: String = "googlebook"
    ): NxtsVaultDocument {
        val cleanExisting = VaultHierarchySanitizer.sanitizeVaultDocument(existingDoc)
        val normalizedImport = normalizeImportedRoyalDocument(
            imported = rawImportedDoc,
            unwrapBuiltInConnectionsRoot = true
        )

        val now = System.currentTimeMillis()
        val nextRev = maxOf(cleanExisting.revision, normalizedImport.revision) + 1L
        val nextClock = cleanExisting.vectorClock.toMutableMap().apply {
            put(actorDevice, nextRev)
        }

        if (mode == RoyalTsImportPlacementMode.REPLACE_EXISTING) {
            return cleanExisting.copy(
                name = normalizedImport.name.ifBlank { cleanExisting.name },
                description = normalizedImport.description.ifBlank { cleanExisting.description },
                revision = nextRev,
                updatedAtEpochMs = now,
                vectorClock = nextClock,
                rootInheritable = normalizedImport.rootInheritable,
                folders = normalizedImport.folders,
                connections = normalizedImport.connections,
                smartFolders = mergeById(cleanExisting.smartFolders, normalizedImport.smartFolders) { it.id },
                dynamicFolders = normalizedImport.dynamicFolders,
                credentials = mergeById(cleanExisting.credentials, normalizedImport.credentials) { it.id },
                gateways = mergeById(cleanExisting.gateways, normalizedImport.gateways) { it.id },
                commandTasks = mergeById(cleanExisting.commandTasks, normalizedImport.commandTasks) { it.id },
                keySequenceTasks = mergeById(cleanExisting.keySequenceTasks, normalizedImport.keySequenceTasks) { it.id },
                templates = mergeById(cleanExisting.templates, normalizedImport.templates) { it.id },
                todoItems = mergeById(cleanExisting.todoItems, normalizedImport.todoItems) { it.id },
                recentConnectionIds = emptyList(),
                tombstonedObjectIds = emptySet()
            )
        }

        // Remap colliding folder/connection IDs when merging into an existing non-empty vault
        val existingFolderIds = cleanExisting.folders.map { it.id }.toSet()
        val existingConnIds = cleanExisting.connections.map { it.id }.toSet()

        val folderIdMap = mutableMapOf<String, String>()
        for (f in normalizedImport.folders) {
            folderIdMap[f.id] = if (f.id in existingFolderIds) UUID.randomUUID().toString() else f.id
        }

        val maxRootFolderSort = cleanExisting.folders
            .filter { it.parentFolderId == null }
            .maxOfOrNull { it.sortOrder } ?: 0
        val maxRootConnSort = cleanExisting.connections
            .filter { it.parentFolderId == null }
            .maxOfOrNull { it.sortOrder } ?: 0

        val wrapperFolder: VaultFolder? = if (mode == RoyalTsImportPlacementMode.CREATE_NEW_FOLDER) {
            val resolvedName = newFolderName.trim()
                .ifBlank { normalizedImport.name.trim() }
                .ifBlank { "Imported Connections" }
            val safeName = if (VaultHierarchySanitizer.isReservedCredentialsFolderName(resolvedName)) {
                "$resolvedName (Imported)"
            } else {
                resolvedName
            }
            VaultFolder(
                id = UUID.randomUUID().toString(),
                parentFolderId = null,
                name = safeName,
                description = normalizedImport.description,
                inheritable = normalizedImport.rootInheritable,
                sortOrder = maxRootFolderSort + 10,
                updatedAtEpochMs = now
            )
        } else {
            null
        }

        var rootFolderCounter = 1
        val remappedFolders = normalizedImport.folders.map { folder ->
            val mappedId = folderIdMap.getValue(folder.id)
            val mappedParent = folder.parentFolderId?.let { folderIdMap[it] ?: it } ?: wrapperFolder?.id
            val adjustedSort = if (folder.parentFolderId == null && wrapperFolder == null && folder.sortOrder == 0) {
                maxRootFolderSort + (rootFolderCounter++ * 10)
            } else {
                folder.sortOrder
            }
            val safeFolderName = if (mappedParent == null && VaultHierarchySanitizer.isReservedCredentialsFolderName(folder.name)) {
                "${folder.name} (Connections)"
            } else {
                folder.name
            }
            folder.copy(
                id = mappedId,
                parentFolderId = mappedParent,
                name = safeFolderName,
                sortOrder = adjustedSort,
                updatedAtEpochMs = now
            )
        }

        var rootConnCounter = 1
        val remappedConnections = normalizedImport.connections.map { conn ->
            val mappedConnId = if (conn.id in existingConnIds) UUID.randomUUID().toString() else conn.id
            val mappedParent = conn.parentFolderId?.let { folderIdMap[it] ?: it } ?: wrapperFolder?.id
            val adjustedSort = if (conn.parentFolderId == null && wrapperFolder == null && conn.sortOrder == 0) {
                maxRootConnSort + (rootConnCounter++ * 10)
            } else {
                conn.sortOrder
            }
            conn.copy(
                id = mappedConnId,
                parentFolderId = mappedParent,
                sortOrder = adjustedSort,
                updatedAtEpochMs = now
            )
        }

        val remappedDynamicFolders = normalizedImport.dynamicFolders.map { df ->
            val mappedParent = df.parentFolderId?.let { folderIdMap[it] ?: it } ?: wrapperFolder?.id
            df.copy(parentFolderId = mappedParent, updatedAtEpochMs = now)
        }

        val combinedFolders = buildList {
            addAll(cleanExisting.folders)
            if (wrapperFolder != null) add(wrapperFolder)
            addAll(remappedFolders)
        }
        val combinedConnections = cleanExisting.connections + remappedConnections

        return cleanExisting.copy(
            revision = nextRev,
            updatedAtEpochMs = now,
            vectorClock = nextClock,
            folders = combinedFolders,
            connections = combinedConnections,
            smartFolders = mergeById(cleanExisting.smartFolders, normalizedImport.smartFolders) { it.id },
            dynamicFolders = mergeById(cleanExisting.dynamicFolders, remappedDynamicFolders) { it.id },
            credentials = mergeById(cleanExisting.credentials, normalizedImport.credentials.map { it.copy(parentFolderId = null) }) { it.id },
            gateways = mergeById(cleanExisting.gateways, normalizedImport.gateways) { it.id },
            commandTasks = mergeById(cleanExisting.commandTasks, normalizedImport.commandTasks) { it.id },
            keySequenceTasks = mergeById(cleanExisting.keySequenceTasks, normalizedImport.keySequenceTasks) { it.id },
            templates = mergeById(cleanExisting.templates, normalizedImport.templates) { it.id },
            todoItems = mergeById(cleanExisting.todoItems, normalizedImport.todoItems) { it.id },
            tombstonedObjectIds = cleanExisting.tombstonedObjectIds -
                combinedFolders.map { it.id }.toSet() -
                combinedConnections.map { it.id }.toSet()
        )
    }

    private fun <T> mergeById(existing: List<T>, incoming: List<T>, idSelector: (T) -> String): List<T> {
        val map = LinkedHashMap<String, T>()
        existing.forEach { map[idSelector(it)] = it }
        incoming.forEach { map[idSelector(it)] = it }
        return map.values.toList()
    }
}
