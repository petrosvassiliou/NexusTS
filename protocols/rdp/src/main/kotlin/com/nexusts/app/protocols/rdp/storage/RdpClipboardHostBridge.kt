package com.nexusts.app.protocols.rdp.storage

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import java.io.File

/**
 * Bidirectional Host Clipboard Extractor & Publisher for Android / Googlebook OS / ChromeOS ARCVM.
 *
 * Handles:
 * 1. **Remote RDP -> Local Googlebook `Files` App (`publishRemoteFilesToHostClipboard`)**:
 *    Publishes `DocumentsContract` URIs backed by [NexusRdpClipboardDocumentsProvider] with
 *    `PersistableBundle("clipper:opType" = 1)` (`OPERATION_COPY`) so AOSP `DocumentsUI` (`Files`)
 *    enables right-click **Paste** and executes `Ctrl+V` copies for both files and nested folders
 *    without triggering `"File operation is not supported."`.
 * 2. **Local Googlebook `Files` App -> Remote RDP (`extractHostClipboardPayload` + `stageUrisToLocalFiles`)**:
 *    Extracts `content://` and `file://` URIs from `ClipData` items AND `text/uri-list` payloads,
 *    recursively stages `DocumentsProvider` directories (`Document.MIME_TYPE_DIR`) as well as
 *    regular and virtual files into `<cacheDir>/cliprdr_send/batch_<ts>/`, preserves exact
 *    filenames, and prevents echo loops when our own clipboard provider is currently on the host
 *    clipboard.
 */
object RdpClipboardHostBridge {

    sealed interface HostClipboardPayload {
        data object Empty : HostClipboardPayload
        /** A file URI intent was detected on the clipboard, but it belongs to our own Remote->Local provider or is unreadable. */
        data object OwnOrUnreadableFileIntent : HostClipboardPayload
        data class Text(val text: String) : HostClipboardPayload
        data class Files(val uris: List<Uri>, val signature: String) : HostClipboardPayload
    }

    internal fun sanitizeDisplayName(rawName: String, fallbackIndex: Int): String {
        val cleaned = rawName
            .substringAfterLast(':')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace('\u0000', '_')
            .trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            return "clipboard_file_${fallbackIndex + 1}"
        }
        return cleaned
    }

    fun isOwnClipboardUri(context: Context, uri: Uri): Boolean {
        val auth = uri.authority.orEmpty()
        if (auth.isEmpty()) return false
        val pkg = context.packageName
        return auth.equals("$pkg${NexusRdpClipboardDocumentsProvider.AUTHORITY_SUFFIX}", ignoreCase = true) ||
            auth.equals("$pkg.fileprovider", ignoreCase = true)
    }

    fun extractHostClipboardPayload(
        context: Context,
        manager: ClipboardManager?
    ): HostClipboardPayload {
        val clip = runCatching { manager?.primaryClip }.getOrNull() ?: return HostClipboardPayload.Empty
        return extractClipDataPayload(context, clip)
    }

    fun extractClipDataPayload(
        context: Context,
        clip: ClipData?
    ): HostClipboardPayload {
        if (clip == null || clip.itemCount <= 0) return HostClipboardPayload.Empty

        val desc = clip.description
        val hasUriListMime = desc != null && (
            desc.hasMimeType("text/uri-list") ||
                desc.hasMimeType("x-special/gnome-copied-files")
            )

        val candidateUris = mutableListOf<Uri>()
        var sawOwnOrFileIntent = false

        for (idx in 0 until clip.itemCount) {
            val item = runCatching { clip.getItemAt(idx) }.getOrNull() ?: continue
            val directUri = item.uri
            if (directUri != null) {
                val scheme = directUri.scheme?.lowercase().orEmpty()
                if (scheme == "content" || scheme == "file") {
                    if (isOwnClipboardUri(context, directUri)) {
                        sawOwnOrFileIntent = true
                    } else {
                        candidateUris += directUri
                    }
                    continue
                }
            }

            // Also inspect text/uri-list or file:// / content:// lines placed in item.text by desktop bridges
            val rawText = item.text?.toString()?.trim().orEmpty()
            if (rawText.isNotEmpty() && (hasUriListMime || looksLikeFileOrContentUriList(rawText))) {
                val parsedFromText = parseUriListText(rawText)
                if (parsedFromText.isNotEmpty()) {
                    sawOwnOrFileIntent = true
                    for (u in parsedFromText) {
                        if (!isOwnClipboardUri(context, u)) {
                            candidateUris += u
                        }
                    }
                    continue
                }
            }
        }

        val deduplicatedUris = candidateUris.distinct()
        if (deduplicatedUris.isNotEmpty()) {
            val sig = computeUriListSignature(context, deduplicatedUris)
            return HostClipboardPayload.Files(uris = deduplicatedUris, signature = sig)
        }
        if (sawOwnOrFileIntent) {
            return HostClipboardPayload.OwnOrUnreadableFileIntent
        }

        val plainText = runCatching {
            clip.getItemAt(0)?.text?.toString().orEmpty()
        }.getOrDefault("")
        return if (plainText.isNotEmpty()) {
            HostClipboardPayload.Text(plainText)
        } else {
            HostClipboardPayload.Empty
        }
    }

    internal fun looksLikeFileOrContentUriList(text: String): Boolean {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty() || lines.size > 128) return false
        val bodyLines = if (lines.first() == "copy" || lines.first() == "cut") lines.drop(1) else lines
        if (bodyLines.isEmpty()) return false
        return bodyLines.all { line ->
            line.startsWith("file://", ignoreCase = true) ||
                line.startsWith("content://", ignoreCase = true)
        }
    }

    internal fun parseUriListText(text: String): List<Uri> {
        val out = mutableListOf<Uri>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line == "copy" || line == "cut") continue
            if (line.startsWith("file://", ignoreCase = true) ||
                line.startsWith("content://", ignoreCase = true)
            ) {
                runCatching { Uri.parse(line) }.getOrNull()?.let { out += it }
            }
        }
        return out
    }

    private fun computeUriListSignature(context: Context, uris: List<Uri>): String {
        val sb = StringBuilder()
        for (uri in uris) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(uri.toString())
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val f = uri.path?.let(::File)
                if (f != null && f.exists()) {
                    sb.append('|').append(f.length()).append('|').append(f.lastModified())
                }
            } else if (uri.scheme.equals("content", ignoreCase = true)) {
                val meta = queryContentMetadata(context, uri)
                if (meta != null) {
                    sb.append('|').append(meta.displayName)
                        .append('|').append(meta.size)
                        .append('|').append(meta.lastModified)
                        .append('|').append(meta.mimeType)
                }
            }
        }
        return sb.toString()
    }

    private data class QueriedContentMeta(
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long
    )

    private fun queryContentMetadata(context: Context, uri: Uri): QueriedContentMeta? {
        return runCatching {
            val projection = arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_LAST_MODIFIED
            )
            val cursor = runCatching {
                context.contentResolver.query(uri, projection, null, null, null)
            }.getOrNull() ?: context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )
            cursor?.use { c ->
                if (!c.moveToFirst()) return@runCatching null
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                val mimeIdx = c.getColumnIndex(Document.COLUMN_MIME_TYPE)
                val modIdx = c.getColumnIndex(Document.COLUMN_LAST_MODIFIED)

                val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx).orEmpty() else ""
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L
                val mime = if (mimeIdx >= 0 && !c.isNull(mimeIdx)) {
                    c.getString(mimeIdx).orEmpty()
                } else {
                    context.contentResolver.getType(uri).orEmpty()
                }
                val lastMod = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else -1L
                QueriedContentMeta(
                    displayName = name,
                    mimeType = mime,
                    size = size,
                    lastModified = lastMod
                )
            }
        }.getOrNull()
    }

    /**
     * Stages local `content://` or `file://` URIs (including recursive `DocumentsProvider` folders)
     * into `<cacheDir>/cliprdr_send/batch_<ts>_<seq>/` so C++20 `RdpVirtualChannelHub::on_local_clipboard_files`
     * can serve `FileGroupDescriptorW` and `FileContents` streams to Windows Explorer.
     */
    fun stageUrisToLocalFiles(context: Context, uris: List<Uri>): List<String> {
        if (uris.isEmpty()) return emptyList()
        val sendRoot = File(context.cacheDir, "cliprdr_send")
        runCatching {
            sendRoot.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(8)
                ?.forEach { it.deleteRecursively() }
        }
        val batchDir = File(sendRoot, "batch_${System.currentTimeMillis()}_${System.nanoTime() and 0xFFFF}")
            .apply { mkdirs() }
        val stagedTopPaths = mutableListOf<String>()
        val usedRootNames = mutableSetOf<String>()

        uris.forEachIndexed { idx, uri ->
            if (isOwnClipboardUri(context, uri)) return@forEachIndexed

            if (uri.scheme.equals("file", ignoreCase = true)) {
                val directFile = uri.path?.let(::File)
                if (directFile != null && directFile.exists() && directFile.canRead()) {
                    stagedTopPaths += directFile.absolutePath
                    return@forEachIndexed
                }
            }

            val meta = queryContentMetadata(context, uri)
            val rawName = meta?.displayName?.takeIf { it.isNotBlank() }
                ?: runCatching { DocumentsContract.getDocumentId(uri).substringAfterLast('/').substringAfterLast(':') }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment.orEmpty()
            val uniqueName = allocateUniqueName(sanitizeDisplayName(rawName, idx), usedRootNames)
            val target = File(batchDir, uniqueName)

            val mimeType = meta?.mimeType?.takeIf { it.isNotBlank() }
                ?: runCatching { context.contentResolver.getType(uri).orEmpty() }.getOrDefault("")

            if (mimeType == Document.MIME_TYPE_DIR) {
                if (stageDocumentDirectoryRecursively(context, uri, target, depth = 0)) {
                    stagedTopPaths += target.absolutePath
                }
            } else {
                if (copyContentUriToFile(context, uri, target)) {
                    if (meta != null && meta.lastModified > 0L) {
                        runCatching { target.setLastModified(meta.lastModified) }
                    }
                    stagedTopPaths += target.absolutePath
                }
            }
        }
        return stagedTopPaths
    }

    private fun stageDocumentDirectoryRecursively(
        context: Context,
        dirUri: Uri,
        destDir: File,
        depth: Int
    ): Boolean {
        if (depth > 16) return false
        if (!destDir.exists() && !destDir.mkdirs()) return false
        val authority = dirUri.authority ?: return true
        val docId = runCatching { DocumentsContract.getDocumentId(dirUri) }.getOrNull() ?: return true
        val childrenUri = runCatching {
            if (DocumentsContract.isTreeUri(dirUri)) {
                DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, docId)
            } else {
                DocumentsContract.buildChildDocumentsUri(authority, docId)
            }
        }.getOrNull() ?: return true

        val usedChildNames = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_LAST_MODIFIED
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
                val modCol = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
                var childIdx = 0
                while (cursor.moveToNext()) {
                    val childDocId = if (idCol >= 0) cursor.getString(idCol) else null
                    if (childDocId.isNullOrBlank()) continue
                    val rawChildName = if (nameCol >= 0 && !cursor.isNull(nameCol)) {
                        cursor.getString(nameCol).orEmpty()
                    } else {
                        childDocId.substringAfterLast('/')
                    }
                    val childMime = if (mimeCol >= 0 && !cursor.isNull(mimeCol)) {
                        cursor.getString(mimeCol).orEmpty()
                    } else {
                        ""
                    }
                    val childMod = if (modCol >= 0 && !cursor.isNull(modCol)) cursor.getLong(modCol) else -1L
                    val childUri = if (DocumentsContract.isTreeUri(dirUri)) {
                        DocumentsContract.buildDocumentUriUsingTree(dirUri, childDocId)
                    } else {
                        DocumentsContract.buildDocumentUri(authority, childDocId)
                    }
                    val safeName = allocateUniqueName(sanitizeDisplayName(rawChildName, childIdx++), usedChildNames)
                    val destChild = File(destDir, safeName)
                    if (childMime == Document.MIME_TYPE_DIR) {
                        stageDocumentDirectoryRecursively(context, childUri, destChild, depth + 1)
                    } else if (copyContentUriToFile(context, childUri, destChild) && childMod > 0L) {
                        runCatching { destChild.setLastModified(childMod) }
                    }
                }
            }
        }
        return destDir.exists()
    }

    private fun copyContentUriToFile(context: Context, uri: Uri, destFile: File): Boolean {
        destFile.parentFile?.mkdirs()
        val copiedDirect = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (copiedDirect && destFile.exists()) return true

        // Fallback for virtual documents (e.g. Google Docs / Drive streamable exports)
        val copiedTyped = runCatching {
            val streamTypes = context.contentResolver.getStreamTypes(uri, "*/*")
            val preferredMime = streamTypes?.firstOrNull() ?: "*/*"
            context.contentResolver.openTypedAssetFileDescriptor(uri, preferredMime, null)?.use { afd ->
                afd.createInputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
        return copiedTyped && destFile.exists()
    }

    private fun allocateUniqueName(baseName: String, usedLowerNames: MutableSet<String>): String {
        var candidate = baseName
        var dedup = 2
        while (!usedLowerNames.add(candidate.lowercase())) {
            val dot = baseName.lastIndexOf('.')
            candidate = if (dot > 0) {
                "${baseName.substring(0, dot)} ($dedup)${baseName.substring(dot)}"
            } else {
                "$baseName ($dedup)"
            }
            dedup++
        }
        return candidate
    }

    /**
     * Publishes files and directories staged in `cliprdr_recv` onto the Android / Googlebook OS
     * system clipboard as `DocumentsContract` URIs with `PersistableBundle("clipper:opType" = 1)`
     * so `DocumentsUI` (`Files` app) pastes them cleanly without `"File operation is not supported."`.
     *
     * @return Published URI list signature if successful, or `null` if nothing could be published.
     */
    fun publishRemoteFilesToHostClipboard(
        context: Context,
        manager: ClipboardManager?,
        localPaths: List<String>
    ): String? {
        if (manager == null || localPaths.isEmpty()) return null
        val docEntries = localPaths.mapNotNull { rawPath ->
            val file = File(rawPath)
            if (!file.exists()) return@mapNotNull null
            val uri = NexusRdpClipboardDocumentsProvider.buildDocumentUriForStagedFile(context, file)
                ?: return@mapNotNull null
            val mime = NexusRdpClipboardDocumentsProvider.resolveMimeType(file)
            uri to mime
        }
        if (docEntries.isEmpty()) return null

        val mimeTypes = docEntries.map { it.second }.distinct().toTypedArray()
        val description = ClipDescription("NexusTS RDP Files", mimeTypes).apply {
            extras = PersistableBundle().apply {
                putInt(
                    NexusRdpClipboardDocumentsProvider.DOCUMENTS_UI_OP_TYPE_KEY,
                    NexusRdpClipboardDocumentsProvider.DOCUMENTS_UI_OPERATION_COPY
                )
            }
        }
        val clipData = ClipData(description, ClipData.Item(docEntries.first().first))
        for (idx in 1 until docEntries.size) {
            clipData.addItem(ClipData.Item(docEntries[idx].first))
        }
        val docTreePrefixUri = Uri.parse(
            "content://${NexusRdpClipboardDocumentsProvider.authority(context)}/document/"
        )
        for (documentsUiPkg in listOf("com.google.android.documentsui", "com.android.documentsui")) {
            runCatching {
                context.grantUriPermission(
                    documentsUiPkg,
                    docTreePrefixUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }
        }
        val applied = runCatching {
            manager.setPrimaryClip(clipData)
            true
        }.getOrDefault(false)
        return if (applied) docEntries.joinToString("\n") { it.first.toString() } else null
    }
}
