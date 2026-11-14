package com.nexusts.app.protocols.rdp.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Bridges Android Storage Access Framework (`OpenDocumentTree` `DocumentFile` URIs) with an
 * app-private POSIX staging directory (`filesDir/rdp_shared_drives/<connectionId>`) so FreeRDP 3.x
 * `RDPDR_DTYP_FILESYSTEM` exposes `\\tsclient\<ShareName>` with zero `MANAGE_EXTERNAL_STORAGE`
 * permissions (100% Google Play Protect compliant on Googlebook OS & Android tablets).
 */
object SafRdpSharedDriveSynchronizer {

    const val DEFAULT_SHARE_NAME = "NexusTS"
    private const val MAX_TREE_DEPTH = 4
    private const val MAX_TOTAL_STAGED_BYTES = 128L * 1024L * 1024L // 128 MiB safety cap

    data class StagedFileStamp(
        val lengthBytes: Long,
        val lastModifiedEpochMs: Long
    )

    data class PreparedRdpSharedDrive(
        val shareName: String,
        val localPosixDirectory: File,
        val boundSafTreeUris: List<Uri>,
        val initialSnapshot: Map<String, StagedFileStamp>
    ) {
        val uncDisplayPath: String
            get() = "\\\\tsclient\\$shareName"
    }

    data class SharedDriveSyncReport(
        val uploadedToSafCount: Int,
        val downloadedFromSafCount: Int,
        val updatedSnapshot: Map<String, StagedFileStamp>
    )

    /**
     * Produces a clean NetBIOS/RDPDR share token (`[A-Za-z0-9_-]{1,24}`) suitable for `\\tsclient\<ShareName>`.
     */
    fun sanitizeDriveShareName(rawName: String?): String {
        if (rawName.isNullOrBlank()) return DEFAULT_SHARE_NAME
        val cleaned = buildString(rawName.length) {
            for (ch in rawName.trim()) {
                when {
                    ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_' -> append(ch)
                    ch.isWhitespace() || ch == '.' -> append('_')
                }
            }
        }.trim('_').take(24)
        return cleaned.ifBlank { DEFAULT_SHARE_NAME }
    }

    /**
     * Sanitizes a single relative path segment, returning `null` if empty, `.` / `..`, or containing separators/NUL.
     */
    fun sanitizeChildSegment(rawSegment: String?): String? {
        if (rawSegment.isNullOrBlank()) return null
        val trimmed = rawSegment.trim()
        if (trimmed == "." || trimmed == "..") return null
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains('\u0000')) {
            return null
        }
        return trimmed
    }

    fun sanitizeConnectionDirectoryName(connectionId: String): String {
        val cleaned = connectionId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
        return cleaned.ifBlank { "default_session" }
    }

    /**
     * Resolves a relative POSIX path safely inside [rootDir], rejecting any symlink/parent traversal escape.
     */
    fun resolveSafeChild(rootDir: File, relativePath: String): File? {
        val normalizedParts = relativePath
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() }
        if (normalizedParts.isEmpty()) return null
        var current = rootDir
        for (part in normalizedParts) {
            val safe = sanitizeChildSegment(part) ?: return null
            current = File(current, safe)
        }
        val rootCanonical = runCatching { rootDir.canonicalFile }.getOrElse { return null }
        val targetCanonical = runCatching { current.canonicalFile }.getOrElse { return null }
        val rootPrefix = rootCanonical.path.trimEnd(File.separatorChar) + File.separator
        return if (targetCanonical.path.startsWith(rootPrefix)) targetCanonical else null
    }

    /**
     * Captures a deterministic snapshot of all regular files under [rootDir] keyed by forward-slash relative path.
     */
    fun captureDirectorySnapshot(rootDir: File): Map<String, StagedFileStamp> {
        if (!rootDir.exists() || !rootDir.isDirectory) return emptyMap()
        val result = linkedMapOf<String, StagedFileStamp>()
        val rootCanonical = runCatching { rootDir.canonicalFile }.getOrElse { return emptyMap() }

        fun walkDir(dir: File, relativePrefix: String, depth: Int) {
            if (depth > MAX_TREE_DEPTH) return
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                val safeName = sanitizeChildSegment(child.name) ?: continue
                val childCanonical = runCatching { child.canonicalFile }.getOrNull() ?: continue
                val rootPathPrefix = rootCanonical.path.trimEnd(File.separatorChar) + File.separator
                if (!childCanonical.path.startsWith(rootPathPrefix)) continue

                val relPath = if (relativePrefix.isEmpty()) safeName else "$relativePrefix/$safeName"
                if (child.isDirectory) {
                    walkDir(child, relPath, depth + 1)
                } else if (child.isFile) {
                    result[relPath] = StagedFileStamp(
                        lengthBytes = child.length(),
                        lastModifiedEpochMs = child.lastModified()
                    )
                }
            }
        }

        walkDir(rootCanonical, "", 0)
        return result
    }

    /**
     * Computes relative paths of files in [currentSnapshot] that were newly created or modified relative to [baseline].
     */
    fun computeModifiedRelativePaths(
        baseline: Map<String, StagedFileStamp>,
        currentSnapshot: Map<String, StagedFileStamp>
    ): List<String> {
        return currentSnapshot.entries
            .filter { (relPath, nowStamp) ->
                val prev = baseline[relPath]
                prev == null ||
                    prev.lengthBytes != nowStamp.lengthBytes ||
                    prev.lastModifiedEpochMs != nowStamp.lastModifiedEpochMs
            }
            .map { it.key }
            .sorted()
    }

    /**
     * Mirrors modified/new files from [sourceDir] into [targetDir] safely (used by JVM unit tests & local folder sync).
     */
    fun syncPosixDirectories(
        sourceDir: File,
        targetDir: File,
        baseline: Map<String, StagedFileStamp> = emptyMap()
    ): Int {
        if (!sourceDir.isDirectory) return 0
        targetDir.mkdirs()
        val current = captureDirectorySnapshot(sourceDir)
        val changedPaths = computeModifiedRelativePaths(baseline, current)
        var copied = 0
        var stagedBytes = 0L
        for (relPath in changedPaths) {
            val srcFile = resolveSafeChild(sourceDir, relPath) ?: continue
            val dstFile = resolveSafeChild(targetDir, relPath) ?: continue
            if (!srcFile.isFile) continue
            if (stagedBytes + srcFile.length() > MAX_TOTAL_STAGED_BYTES) break
            dstFile.parentFile?.mkdirs()
            srcFile.inputStream().use { input ->
                dstFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            dstFile.setLastModified(srcFile.lastModified())
            stagedBytes += srcFile.length()
            copied++
        }
        return copied
    }

    /**
     * Prepares the local POSIX directory for a FreeRDP session and stages files from any configured SAF folder URIs.
     */
    fun prepareSessionDrive(
        context: Context?,
        connectionId: String,
        redirectDrivesEnabled: Boolean,
        safUriStrings: List<String>,
        explicitBaseDir: File? = null
    ): PreparedRdpSharedDrive? {
        if (!redirectDrivesEnabled && safUriStrings.isEmpty()) {
            return null
        }
        val rootParent = explicitBaseDir
            ?: context?.let { File(it.filesDir, "rdp_shared_drives") }
            ?: return null
        val sessionStagingDir = File(rootParent, sanitizeConnectionDirectoryName(connectionId))
        sessionStagingDir.mkdirs()

        val parsedUris = safUriStrings
            .mapNotNull { raw -> raw.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Uri.parse(it) }.getOrNull() } }

        var resolvedShareName = DEFAULT_SHARE_NAME
        if (context != null && parsedUris.isNotEmpty()) {
            // Reset clean staging tree before mirroring current SAF tree contents
            sessionStagingDir.listFiles()?.forEach { it.deleteRecursively() }
            var remainingBudget = MAX_TOTAL_STAGED_BYTES

            parsedUris.forEachIndexed { index, treeUri ->
                val docTree = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
                if (docTree != null && docTree.exists()) {
                    val treeDisplayName = sanitizeDriveShareName(docTree.name)
                    if (index == 0) {
                        resolvedShareName = treeDisplayName
                    }
                    val targetRoot = if (parsedUris.size == 1) {
                        sessionStagingDir
                    } else {
                        File(sessionStagingDir, treeDisplayName.ifBlank { "Folder_${index + 1}" }).also { it.mkdirs() }
                    }
                    remainingBudget = copyDocumentTreeToPosix(
                        context = context,
                        sourceTree = docTree,
                        targetDir = targetRoot,
                        depth = 0,
                        remainingBudgetBytes = remainingBudget
                    )
                }
            }
        } else if (sessionStagingDir.listFiles().isNullOrEmpty()) {
            val readme = File(sessionStagingDir, "README-NexusTS-SharedDrive.txt")
            runCatching {
                readme.writeText(
                    "NexusTS Shared RDP Drive (\\\\tsclient\\$DEFAULT_SHARE_NAME)\r\n" +
                        "Files placed in this folder are accessible between your Googlebook OS / Android workspace and the remote desktop session.\r\n"
                )
            }
        }

        val initialSnapshot = captureDirectorySnapshot(sessionStagingDir)
        return PreparedRdpSharedDrive(
            shareName = resolvedShareName,
            localPosixDirectory = sessionStagingDir,
            boundSafTreeUris = parsedUris,
            initialSnapshot = initialSnapshot
        )
    }

    /**
     * Synchronizes any newly created or modified files from the POSIX staging folder back to the user's SAF folder,
     * and optionally pulls newly added files from SAF into the POSIX staging folder.
     */
    fun syncSessionDriveWithSaf(
        context: Context,
        preparedDrive: PreparedRdpSharedDrive,
        baselineSnapshot: Map<String, StagedFileStamp>,
        pullInboundFromSaf: Boolean = false
    ): SharedDriveSyncReport {
        val stagingRoot = preparedDrive.localPosixDirectory
        if (!stagingRoot.isDirectory || preparedDrive.boundSafTreeUris.isEmpty()) {
            return SharedDriveSyncReport(
                uploadedToSafCount = 0,
                downloadedFromSafCount = 0,
                updatedSnapshot = captureDirectorySnapshot(stagingRoot)
            )
        }

        val currentSnapshot = captureDirectorySnapshot(stagingRoot)
        val modifiedRelPaths = computeModifiedRelativePaths(baselineSnapshot, currentSnapshot)
        var uploaded = 0

        if (preparedDrive.boundSafTreeUris.size == 1) {
            val rootDoc = runCatching {
                DocumentFile.fromTreeUri(context, preparedDrive.boundSafTreeUris.first())
            }.getOrNull()
            if (rootDoc != null && rootDoc.exists()) {
                for (relPath in modifiedRelPaths) {
                    val localFile = resolveSafeChild(stagingRoot, relPath) ?: continue
                    if (writeLocalFileToDocumentTree(context, rootDoc, relPath, localFile)) {
                        uploaded++
                    }
                }
            }
        } else {
            preparedDrive.boundSafTreeUris.forEachIndexed { index, uri ->
                val rootDoc = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return@forEachIndexed
                if (!rootDoc.exists()) return@forEachIndexed
                val prefix = sanitizeDriveShareName(rootDoc.name).ifBlank { "Folder_${index + 1}" } + "/"
                for (relPath in modifiedRelPaths) {
                    if (relPath.startsWith(prefix)) {
                        val subRel = relPath.removePrefix(prefix)
                        val localFile = resolveSafeChild(stagingRoot, relPath) ?: continue
                        if (subRel.isNotEmpty() && writeLocalFileToDocumentTree(context, rootDoc, subRel, localFile)) {
                            uploaded++
                        }
                    }
                }
            }
        }

        var downloaded = 0
        if (pullInboundFromSaf && preparedDrive.boundSafTreeUris.size == 1) {
            val rootDoc = runCatching {
                DocumentFile.fromTreeUri(context, preparedDrive.boundSafTreeUris.first())
            }.getOrNull()
            if (rootDoc != null && rootDoc.exists()) {
                val beforePull = captureDirectorySnapshot(stagingRoot)
                copyDocumentTreeToPosix(
                    context = context,
                    sourceTree = rootDoc,
                    targetDir = stagingRoot,
                    depth = 0,
                    remainingBudgetBytes = MAX_TOTAL_STAGED_BYTES,
                    onlyMissingOrNewer = true
                )
                val afterPull = captureDirectorySnapshot(stagingRoot)
                downloaded = computeModifiedRelativePaths(beforePull, afterPull).size
            }
        }

        return SharedDriveSyncReport(
            uploadedToSafCount = uploaded,
            downloadedFromSafCount = downloaded,
            updatedSnapshot = captureDirectorySnapshot(stagingRoot)
        )
    }

    private fun copyDocumentTreeToPosix(
        context: Context,
        sourceTree: DocumentFile,
        targetDir: File,
        depth: Int,
        remainingBudgetBytes: Long,
        onlyMissingOrNewer: Boolean = false
    ): Long {
        if (depth > MAX_TREE_DEPTH || remainingBudgetBytes <= 0L) return remainingBudgetBytes
        targetDir.mkdirs()
        var budget = remainingBudgetBytes
        val children = runCatching { sourceTree.listFiles() }.getOrNull() ?: return budget
        for (doc in children) {
            val safeName = sanitizeChildSegment(doc.name) ?: continue
            val targetChild = resolveSafeChild(targetDir, safeName) ?: continue
            if (doc.isDirectory) {
                budget = copyDocumentTreeToPosix(
                    context = context,
                    sourceTree = doc,
                    targetDir = targetChild,
                    depth = depth + 1,
                    remainingBudgetBytes = budget,
                    onlyMissingOrNewer = onlyMissingOrNewer
                )
            } else if (doc.isFile) {
                val docLen = doc.length().coerceAtLeast(0L)
                if (docLen > budget) continue
                if (onlyMissingOrNewer && targetChild.isFile) {
                    val docMod = doc.lastModified()
                    if (docMod <= targetChild.lastModified() && docLen == targetChild.length()) {
                        continue
                    }
                }
                val copiedOk = runCatching {
                    context.contentResolver.openInputStream(doc.uri)?.use { input ->
                        targetChild.parentFile?.mkdirs()
                        targetChild.outputStream().use { output ->
                            input.copyTo(output)
                        }
                        true
                    } ?: false
                }.getOrDefault(false)
                if (copiedOk) {
                    val mod = doc.lastModified()
                    if (mod > 0L) {
                        targetChild.setLastModified(mod)
                    }
                    budget -= targetChild.length().coerceAtLeast(0L)
                }
            }
        }
        return budget
    }

    private fun writeLocalFileToDocumentTree(
        context: Context,
        rootTree: DocumentFile,
        relativePath: String,
        localFile: File
    ): Boolean {
        if (!localFile.isFile) return false
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false

        var currentDir = rootTree
        for (i in 0 until (parts.size - 1)) {
            val dirName = sanitizeChildSegment(parts[i]) ?: return false
            val existing = currentDir.findFile(dirName)
            currentDir = when {
                existing != null && existing.isDirectory -> existing
                existing == null -> currentDir.createDirectory(dirName) ?: return false
                else -> return false
            }
        }

        val leafName = sanitizeChildSegment(parts.last()) ?: return false
        val existingLeaf = currentDir.findFile(leafName)
        val targetDoc = when {
            existingLeaf != null && existingLeaf.isFile -> existingLeaf
            existingLeaf == null -> currentDir.createFile("application/octet-stream", leafName) ?: return false
            else -> return false
        }

        return runCatching {
            context.contentResolver.openOutputStream(targetDoc.uri, "wt")?.use { out ->
                localFile.inputStream().use { input ->
                    input.copyTo(out)
                }
                true
            } ?: false
        }.getOrDefault(false)
    }
}
