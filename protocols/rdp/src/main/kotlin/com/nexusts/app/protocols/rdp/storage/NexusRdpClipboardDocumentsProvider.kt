package com.nexusts.app.protocols.rdp.storage

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Zero-Knowledge Scoped [DocumentsProvider] serving files and directories copied from a remote
 * Windows RDP session (`CLIPRDR` `FileGroupDescriptorW` / `FileContents`) to the host Android /
 * Googlebook OS `Files` app (`DocumentsUI`) and other desktop applications.
 *
 * Why a [DocumentsProvider] instead of `androidx.core.content.FileProvider`:
 * - Android / Googlebook OS `DocumentsUI` (`RuntimeDocumentClipper`) requires every pasted URI to
 *   satisfy [DocumentsContract.isDocumentUri] AND carry `PersistableBundle("clipper:opType" = 1)`
 *   on `ClipDescription.extras`. Plain `FileProvider` URIs fail both checks, causing the `Files`
 *   app to display *"File operation is not supported."* (`R.string.file_operation_rejected`).
 * - Supporting [Document.MIME_TYPE_DIR] + [queryChildDocuments] allows pasting entire remote
 *   folders (including nested subdirectories) into the Googlebook `Files` app with full hierarchy
 *   preservation, matching macOS Finder CLIPRDR behavior 1:1.
 *
 * Security Invariant:
 * - Strictly canonicalizes every `documentId` and rejects any path outside
 *   `<filesDir>/freerdp_runtime/tmp/cliprdr_recv`. Vault files and credentials are never reachable.
 */
class NexusRdpClipboardDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        // Keep the system Files app left-hand root drawer clean; document URIs on the clipboard
        // resolve directly through queryDocument / queryChildDocuments / openDocument.
        return MatrixCursor(resolveRootProjection(projection))
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val file = resolveScopedFile(documentId)
        val cursor = MatrixCursor(resolveDocumentProjection(projection))
        includeFileRow(cursor, documentId ?: DOC_ID_ROOT, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val parent = resolveScopedFile(parentDocumentId)
        if (!parent.isDirectory) {
            throw FileNotFoundException("Not a directory: $parentDocumentId")
        }
        val cursor = MatrixCursor(resolveDocumentProjection(projection))
        val baseDir = recvBaseDirectory(attachedContext())
        val children = parent.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()
        for (child in children) {
            val childDocId = buildDocIdFromCanonicalFile(baseDir, child) ?: continue
            includeFileRow(cursor, childDocId, child)
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        return runCatching {
            val parent = resolveScopedFile(parentDocumentId)
            val child = resolveScopedFile(documentId)
            parent.isDirectory &&
                (child.path == parent.path || child.path.startsWith(parent.path + File.separator))
        }.getOrDefault(false)
    }

    override fun findDocumentPath(
        parentDocumentId: String?,
        childDocumentId: String?
    ): DocumentsContract.Path {
        val baseDir = recvBaseDirectory(attachedContext())
        val startFile = if (parentDocumentId.isNullOrBlank()) {
            baseDir
        } else {
            resolveScopedFile(parentDocumentId)
        }
        val childFile = resolveScopedFile(childDocumentId)
        if (childFile.path != startFile.path &&
            !childFile.path.startsWith(startFile.path + File.separator)
        ) {
            throw FileNotFoundException("Child $childDocumentId is not under $parentDocumentId")
        }

        val segments = mutableListOf<String>()
        var cursorFile: File? = childFile
        while (cursorFile != null) {
            val docId = buildDocIdFromCanonicalFile(baseDir, cursorFile)
                ?: throw FileNotFoundException("Outside cliprdr_recv root")
            segments.add(docId)
            if (cursorFile.path == startFile.path) break
            cursorFile = cursorFile.parentFile
        }
        segments.reverse()
        return DocumentsContract.Path(null, segments)
    }

    override fun getDocumentType(documentId: String?): String {
        val file = resolveScopedFile(documentId)
        return resolveMimeType(file)
    }

    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? {
        return runCatching {
            val docId = DocumentsContract.getDocumentId(uri)
            val file = resolveScopedFile(docId)
            if (file.isDirectory) return null
            val mime = resolveMimeType(file)
            if (mimeMatchesFilter(mime, mimeTypeFilter)) arrayOf(mime) else null
        }.getOrNull()
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val parsedMode = ParcelFileDescriptor.parseMode(mode ?: "r")
        if (parsedMode != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw SecurityException("NexusRdpClipboardDocumentsProvider is strictly read-only.")
        }
        val file = resolveScopedFile(documentId)
        if (!file.isFile) {
            throw FileNotFoundException("Cannot open non-regular file: $documentId")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun attachedContext(): Context =
        context ?: throw IllegalStateException("NexusRdpClipboardDocumentsProvider not attached")

    private fun resolveScopedFile(documentId: String?): File {
        val ctx = attachedContext()
        val baseDir = recvBaseDirectory(ctx)
        val rawId = documentId?.trim().orEmpty()
        if (rawId.isEmpty() || rawId == DOC_ID_ROOT) {
            if (!baseDir.exists()) baseDir.mkdirs()
            return baseDir
        }
        if (!rawId.startsWith(DOC_ID_PREFIX) || rawId.contains('\u0000')) {
            throw FileNotFoundException("Invalid clipboard documentId: $rawId")
        }
        val relPath = rawId.removePrefix(DOC_ID_PREFIX).trimStart('/')
        if (relPath.isEmpty()) {
            if (!baseDir.exists()) baseDir.mkdirs()
            return baseDir
        }
        val segments = relPath.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw SecurityException("Rejected path traversal in clipboard documentId: $rawId")
        }
        val candidate = File(baseDir, relPath).canonicalFile
        val allowedPrefix = baseDir.path + File.separator
        if (candidate.path != baseDir.path && !candidate.path.startsWith(allowedPrefix)) {
            throw SecurityException("Rejected out-of-scope clipboard document path.")
        }
        if (!candidate.exists()) {
            throw FileNotFoundException("Staged RDP clipboard file no longer exists: $relPath")
        }
        return candidate
    }

    private fun includeFileRow(cursor: MatrixCursor, docId: String, file: File) {
        val row = cursor.newRow()
        val isDir = file.isDirectory
        val displayName = if (docId == DOC_ID_ROOT) "NexusTS RDP Clipboard" else file.name
        val mime = resolveMimeType(file)
        val size = if (isDir) 0L else file.length()
        val lastMod = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()

        for (col in cursor.columnNames) {
            when (col) {
                Document.COLUMN_DOCUMENT_ID -> row.add(docId)
                Document.COLUMN_DISPLAY_NAME -> row.add(displayName)
                Document.COLUMN_MIME_TYPE -> row.add(mime)
                Document.COLUMN_SIZE -> row.add(size)
                Document.COLUMN_LAST_MODIFIED -> row.add(lastMod)
                Document.COLUMN_FLAGS -> row.add(0)
                else -> row.add(null)
            }
        }
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".rdp.clipboard.documents"
        const val DOC_ID_ROOT = "cliprdr:"
        private const val DOC_ID_PREFIX = "cliprdr:"

        /**
         * AOSP `DocumentsUI` (`RuntimeDocumentClipper.OP_TYPE_KEY` & `FileOperationService.OPERATION_COPY`).
         */
        const val DOCUMENTS_UI_OP_TYPE_KEY = "clipper:opType"
        const val DOCUMENTS_UI_OPERATION_COPY = 1

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )

        private val FALLBACK_EXTENSION_MIMES = mapOf(
            "txt" to "text/plain",
            "log" to "text/plain",
            "ps1" to "text/plain",
            "psm1" to "text/plain",
            "psd1" to "text/plain",
            "bat" to "text/plain",
            "cmd" to "text/plain",
            "sh" to "text/x-shellscript",
            "csv" to "text/csv",
            "tsv" to "text/tab-separated-values",
            "json" to "application/json",
            "xml" to "text/xml",
            "yaml" to "text/yaml",
            "yml" to "text/yaml",
            "md" to "text/markdown",
            "ini" to "text/plain",
            "conf" to "text/plain",
            "cfg" to "text/plain",
            "reg" to "text/plain",
            "sql" to "application/sql",
            "rdp" to "application/x-rdp",
            "pdf" to "application/pdf",
            "zip" to "application/zip",
            "7z" to "application/x-7z-compressed",
            "gz" to "application/gzip",
            "tar" to "application/x-tar",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "svg" to "image/svg+xml",
            "mp4" to "video/mp4",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "apk" to "application/vnd.android.package-archive",
            "exe" to "application/vnd.microsoft.portable-executable",
            "msi" to "application/x-msi",
            "dll" to "application/vnd.microsoft.portable-executable",
            "iso" to "application/x-iso9660-image"
        )

        fun authority(context: Context): String = "${context.packageName}$AUTHORITY_SUFFIX"

        fun recvBaseDirectory(context: Context): File =
            File(context.filesDir, "freerdp_runtime/tmp/cliprdr_recv").apply { mkdirs() }.canonicalFile

        fun buildDocumentUriForStagedFile(context: Context, rawFile: File): Uri? {
            if (!rawFile.exists()) return null
            val baseDir = recvBaseDirectory(context)
            val docId = buildDocIdFromCanonicalFile(baseDir, rawFile) ?: return null
            return DocumentsContract.buildDocumentUri(authority(context), docId)
        }

        internal fun buildDocIdFromCanonicalFile(baseDir: File, file: File): String? {
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
            if (canonical.path == baseDir.path) return DOC_ID_ROOT
            val prefix = baseDir.path + File.separator
            if (!canonical.path.startsWith(prefix)) return null
            val rel = canonical.path.removePrefix(prefix).replace(File.separatorChar, '/')
            if (rel.isEmpty()) return DOC_ID_ROOT
            return "$DOC_ID_PREFIX$rel"
        }

        fun resolveMimeType(file: File): String {
            if (file.isDirectory) return Document.MIME_TYPE_DIR
            val ext = file.extension.lowercase()
            if (ext.isNotEmpty()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { it.isNotBlank() }?.let {
                    return it
                }
                FALLBACK_EXTENSION_MIMES[ext]?.let { return it }
            }
            return "application/octet-stream"
        }

        private fun resolveRootProjection(projection: Array<out String>?): Array<out String> =
            if (projection.isNullOrEmpty()) DEFAULT_ROOT_PROJECTION else projection

        private fun resolveDocumentProjection(projection: Array<out String>?): Array<out String> =
            if (projection.isNullOrEmpty()) DEFAULT_DOCUMENT_PROJECTION else projection

        private fun mimeMatchesFilter(concreteMime: String, filter: String): Boolean {
            if (filter == "*/*" || filter.equals(concreteMime, ignoreCase = true)) return true
            if (filter.endsWith("/*")) {
                val prefix = filter.substringBefore('/')
                return concreteMime.startsWith("$prefix/", ignoreCase = true)
            }
            return false
        }
    }
}
