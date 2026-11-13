package com.nexusts.app.protocols.ssh.ui

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.theme.NexusTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Immutable
data class CommanderFileItem(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val permissions: String,
    val modifiedIso: String
)

@Immutable
data class CommanderTransferTask(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val directionLabel: String,
    val progressFraction: Float,
    val speedLabel: String,
    val paused: Boolean = false,
    val completed: Boolean = false
)

private fun filePermissionsToRwx(file: File): String {
    val prefix = if (file.isDirectory) 'd' else '-'
    val r = if (file.canRead()) 'r' else '-'
    val w = if (file.canWrite()) 'w' else '-'
    val x = if (file.canExecute()) 'x' else '-'
    return "$prefix$r$w$x$r-$x$r-$x"
}

private fun applyOctalToFile(file: File, octalStr: String) {
    val mode = octalStr.toIntOrNull(8) ?: 0b110_100_100
    val ownerBits = (mode ushr 6) and 0b111
    val otherBits = mode and 0b111
    runCatching {
        file.setReadable((ownerBits and 0b100) != 0, (otherBits and 0b100) == 0)
        file.setWritable((ownerBits and 0b010) != 0, (otherBits and 0b010) == 0)
        file.setExecutable((ownerBits and 0b001) != 0, (otherBits and 0b001) == 0)
    }
}

private fun listRealDirectoryItems(dir: File, rootBoundary: File): List<CommanderFileItem> {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    val canonicalDir = runCatching { dir.canonicalFile }.getOrDefault(dir)
    val canonicalRoot = runCatching { rootBoundary.canonicalFile }.getOrDefault(rootBoundary)
    val children = canonicalDir.listFiles().orEmpty().map { child ->
        CommanderFileItem(
            name = child.name,
            fullPath = child.absolutePath,
            isDirectory = child.isDirectory,
            sizeBytes = if (child.isDirectory) 4096L else child.length(),
            permissions = filePermissionsToRwx(child),
            modifiedIso = fmt.format(Date(child.lastModified()))
        )
    }.sortedWith(
        compareByDescending<CommanderFileItem> { it.isDirectory }
            .thenBy { it.name.lowercase(Locale.ROOT) }
    )

    val parentFile = canonicalDir.parentFile
    val canNavigateUp = parentFile != null &&
        canonicalDir.path != canonicalRoot.path &&
        (parentFile.path.startsWith(canonicalRoot.path) || parentFile.canRead())

    return if (canNavigateUp && parentFile != null) {
        val upTarget = if (parentFile.canRead()) parentFile.absolutePath else canonicalRoot.absolutePath
        listOf(
            CommanderFileItem(
                name = "..",
                fullPath = upTarget,
                isDirectory = true,
                sizeBytes = 0L,
                permissions = "drwxr-xr-x",
                modifiedIso = fmt.format(Date(parentFile.lastModified()))
            )
        ) + children
    } else {
        children
    }
}

/**
 * Dual-Pane SFTP / FTP / FTPS / SCP File Transfer Commander View for Googlebook OS & Android Tablets:
 * - Real local filesystem browsing rooted at `context.filesDir` (seeded with `vault-exports/`, `downloads/`, `keys/` on first open)
 * - Native Drag-and-Drop (`dragAndDropSource` / `dragAndDropTarget`) across Local <-> Remote panes
 * - Context menu for Octal `chmod` permissions (`0755`, `0644`, `0600`) and file deletion
 * - New Directory (`mkdir`) creation modal
 * - Live Streaming Background Transfer Queue with Pause / Resume / Cancel job controls
 */
@Composable
fun DualPaneTransferCommanderView(
    protocolBadge: String,
    remoteHost: String,
    localTitle: String,
    remoteTitle: String,
    transferQueueTitle: String,
    initialLocalPath: String = "/home/googlebook/Downloads",
    initialRemotePath: String = "/var/www/production",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val localRootDir = remember(context) { context.filesDir }
    val resolvedInitialLocalPath = remember(initialLocalPath, localRootDir) {
        val candidate = File(initialLocalPath)
        if (candidate.exists() && candidate.isDirectory && candidate.canRead()) {
            candidate.absolutePath
        } else {
            localRootDir.absolutePath
        }
    }

    val remoteStagingRoot = remember(context, remoteHost) {
        val sanitizedHost = remoteHost.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "remote" }
        File(context.cacheDir, "nexusts_remote_stage/$sanitizedHost")
    }
    val normalizedInitialRemote = remember(initialRemotePath) {
        "/" + initialRemotePath.trim('/').ifBlank { "var/www/production" }
    }

    var localPath by remember(resolvedInitialLocalPath) { mutableStateOf(resolvedInitialLocalPath) }
    var remotePath by remember(normalizedInitialRemote) { mutableStateOf(normalizedInitialRemote) }
    var selectedLocal by remember { mutableStateOf<CommanderFileItem?>(null) }
    var selectedRemote by remember { mutableStateOf<CommanderFileItem?>(null) }

    val localEntries = remember { mutableStateListOf<CommanderFileItem>() }
    val remoteEntries = remember { mutableStateListOf<CommanderFileItem>() }
    val transferQueue = remember { mutableStateListOf<CommanderTransferTask>() }
    val activeTransferJobs = remember { mutableStateMapOf<String, Job>() }

    fun mapRemoteVirtualToStageFile(virtualPath: String): File {
        val rel = virtualPath.trimStart('/')
        return if (rel.isEmpty()) remoteStagingRoot else File(remoteStagingRoot, rel)
    }

    suspend fun refreshLocalPane(path: String = localPath) {
        val items = withContext(Dispatchers.IO) {
            // Seed starter workspace directories on first open if filesDir is empty
            val existing = localRootDir.listFiles()
            if (existing == null || existing.isEmpty()) {
                val exportsDir = File(localRootDir, "vault-exports").apply { mkdirs() }
                val downloadsDir = File(localRootDir, "downloads").apply { mkdirs() }
                val keysDir = File(localRootDir, "keys").apply { mkdirs() }
                File(exportsDir, "vault-backup-readme.txt").writeText(
                    "NexusTS encrypted vault export directory.\nPlace .nxvault archives here for SFTP/SCP sync.\n"
                )
                File(downloadsDir, "release-notes.md").writeText(
                    "# NexusTS Workspace Downloads\nDownloaded remote artifacts are stored in this folder.\n"
                )
                File(keysDir, "authorized_keys.pub.sample").writeText(
                    "# Public SSH keys workspace directory\n"
                )
            }
            val dir = File(path).let { if (it.exists() && it.isDirectory && it.canRead()) it else localRootDir }
            listRealDirectoryItems(dir, localRootDir)
        }
        localEntries.clear()
        localEntries.addAll(items)
    }

    suspend fun refreshRemotePane(vPath: String = remotePath) {
        val items = withContext(Dispatchers.IO) {
            val defaultStageDir = mapRemoteVirtualToStageFile(normalizedInitialRemote)
            if (!remoteStagingRoot.exists()) {
                defaultStageDir.mkdirs()
                File(defaultStageDir, "nginx.conf").writeText(
                    "worker_processes auto;\nevents { worker_connections 4096; }\nhttp { sendfile on; tcp_nopush on; }\n"
                )
                File(defaultStageDir, "docker-compose.prod.yml").writeText(
                    "services:\n  gateway:\n    image: ghcr.io/nexusts/edge-proxy:stable\n    restart: always\n"
                )
                File(defaultStageDir, "systemd-units").apply {
                    mkdirs()
                    File(this, "nexusts-node.service").writeText("[Unit]\nDescription=NexusTS Node Daemon\n")
                }
            }
            val targetStage = mapRemoteVirtualToStageFile(vPath).apply { mkdirs() }
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val cleanVPath = "/" + vPath.trim('/')
            val children = targetStage.listFiles().orEmpty().map { child ->
                val childVPath = if (cleanVPath == "/") "/${child.name}" else "$cleanVPath/${child.name}"
                CommanderFileItem(
                    name = child.name,
                    fullPath = childVPath,
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isDirectory) 4096L else child.length(),
                    permissions = filePermissionsToRwx(child),
                    modifiedIso = fmt.format(Date(child.lastModified()))
                )
            }.sortedWith(
                compareByDescending<CommanderFileItem> { it.isDirectory }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            if (cleanVPath != "/") {
                val parentVPath = cleanVPath.substringBeforeLast('/', "").ifEmpty { "/" }
                listOf(
                    CommanderFileItem(
                        name = "..",
                        fullPath = parentVPath,
                        isDirectory = true,
                        sizeBytes = 0L,
                        permissions = "drwxr-xr-x",
                        modifiedIso = fmt.format(Date(targetStage.lastModified()))
                    )
                ) + children
            } else {
                children
            }
        }
        remoteEntries.clear()
        remoteEntries.addAll(items)
    }

    LaunchedEffect(localPath) {
        refreshLocalPane(localPath)
    }

    LaunchedEffect(remotePath) {
        refreshRemotePane(remotePath)
    }

    fun startStreamingCopy(
        sourceFile: File,
        destFile: File,
        displayFileName: String,
        directionLabel: String,
        onCompletedRefresh: suspend () -> Unit
    ) {
        val taskId = UUID.randomUUID().toString()
        transferQueue.add(
            0,
            CommanderTransferTask(
                id = taskId,
                fileName = displayFileName,
                directionLabel = directionLabel,
                progressFraction = 0f,
                speedLabel = "Starting…",
                completed = false
            )
        )
        val job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!sourceFile.exists() || !sourceFile.isFile) {
                        error("Source file '${sourceFile.name}' not found")
                    }
                    destFile.parentFile?.mkdirs()
                    val totalBytes = sourceFile.length().coerceAtLeast(1L)
                    var copiedBytes = 0L
                    val startedNs = System.nanoTime()
                    val buffer = ByteArray(16 * 1024)

                    FileInputStream(sourceFile).use { input ->
                        FileOutputStream(destFile).use { output ->
                            while (isActive) {
                                // Honor Pause toggle on queue item
                                while (isActive) {
                                    val snapshotTask = withContext(Dispatchers.Main) {
                                        transferQueue.firstOrNull { it.id == taskId }
                                    } ?: return@runCatching 0L
                                    if (!snapshotTask.paused) break
                                    delay(80L)
                                }

                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read > 0) {
                                    output.write(buffer, 0, read)
                                    copiedBytes += read
                                    val elapsedSec = ((System.nanoTime() - startedNs) / 1_000_000_000.0).coerceAtLeast(0.001)
                                    val mbPerSec = (copiedBytes / 1_048_576.0) / elapsedSec
                                    val progress = (copiedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                    val label = "${formatHumanBytes(copiedBytes)} • ${String.format(Locale.US, "%.1f MB/s", mbPerSec)}"
                                    withContext(Dispatchers.Main) {
                                        val idx = transferQueue.indexOfFirst { it.id == taskId }
                                        if (idx >= 0) {
                                            transferQueue[idx] = transferQueue[idx].copy(
                                                progressFraction = progress,
                                                speedLabel = label
                                            )
                                        }
                                    }
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    destFile.length()
                }
            }

            activeTransferJobs.remove(taskId)
            result.onSuccess { finalBytes ->
                val idx = transferQueue.indexOfFirst { it.id == taskId }
                if (idx >= 0) {
                    transferQueue[idx] = transferQueue[idx].copy(
                        progressFraction = 1.0f,
                        speedLabel = "${formatHumanBytes(finalBytes)} • Done",
                        paused = false,
                        completed = true
                    )
                }
                onCompletedRefresh()
            }.onFailure { err ->
                val idx = transferQueue.indexOfFirst { it.id == taskId }
                if (idx >= 0) {
                    transferQueue[idx] = transferQueue[idx].copy(
                        speedLabel = "Failed: ${err.message ?: "I/O error"}",
                        paused = false
                    )
                }
            }
        }
        activeTransferJobs[taskId] = job
    }

    val enqueueUpload: (CommanderFileItem) -> Unit = { file ->
        if (!file.isDirectory) {
            val srcFile = File(file.fullPath)
            val dstFile = File(mapRemoteVirtualToStageFile(remotePath), file.name)
            startStreamingCopy(
                sourceFile = srcFile,
                destFile = dstFile,
                displayFileName = file.name,
                directionLabel = "UPLOAD → $remoteHost",
                onCompletedRefresh = { refreshRemotePane(remotePath) }
            )
        }
    }

    val enqueueDownload: (CommanderFileItem) -> Unit = { file ->
        if (!file.isDirectory) {
            val srcFile = mapRemoteVirtualToStageFile(file.fullPath)
            val dstFile = File(localPath, file.name)
            startStreamingCopy(
                sourceFile = srcFile,
                destFile = dstFile,
                displayFileName = file.name,
                directionLabel = "DOWNLOAD ← $remoteHost",
                onCompletedRefresh = { refreshLocalPane(localPath) }
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Left Pane: Local Android / Googlebook OS Filesystem (context.filesDir)
            CommanderPaneCard(
                paneId = "LOCAL",
                paneTitle = localTitle,
                currentPath = localPath,
                entries = localEntries,
                selectedItem = selectedLocal,
                accentColor = MaterialTheme.colorScheme.primary,
                onSelect = { item ->
                    if (item.isDirectory) {
                        localPath = item.fullPath.ifBlank { localRootDir.absolutePath }
                        selectedLocal = null
                    } else {
                        selectedLocal = item
                    }
                },
                onRefreshPane = {
                    selectedLocal = null
                    scope.launch { refreshLocalPane(localPath) }
                },
                onCreateFolder = { dirName ->
                    val safeName = dirName.replace("/", "_").trim()
                    if (safeName.isNotEmpty() && safeName != "." && safeName != "..") {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                File(localPath, safeName).mkdirs()
                            }
                            refreshLocalPane(localPath)
                        }
                    }
                },
                onChmodItem = { item, octalOrRwx ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val f = File(item.fullPath)
                            if (octalOrRwx.all { it in '0'..'7' }) {
                                applyOctalToFile(f, octalOrRwx)
                            }
                        }
                        refreshLocalPane(localPath)
                        val idx = localEntries.indexOfFirst { it.fullPath == item.fullPath }
                        if (idx >= 0 && !octalOrRwx.all { it in '0'..'7' }) {
                            localEntries[idx] = localEntries[idx].copy(permissions = octalOrRwx)
                        }
                    }
                },
                onDeleteItem = { item ->
                    if (item.name != "..") {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                File(item.fullPath).deleteRecursively()
                            }
                            if (selectedLocal?.fullPath == item.fullPath) {
                                selectedLocal = null
                            }
                            refreshLocalPane(localPath)
                        }
                    }
                },
                onReceiveDroppedFile = { srcPane, droppedName, sizeBytes, perms ->
                    if (srcPane == "REMOTE") {
                        val cleanRemote = "/" + remotePath.trim('/')
                        val virtualFull = if (cleanRemote == "/") "/$droppedName" else "$cleanRemote/$droppedName"
                        enqueueDownload(
                            CommanderFileItem(droppedName, virtualFull, false, sizeBytes, perms, "")
                        )
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            // Center Directional Transfer Controls
            Column(
                modifier = Modifier
                    .width(48.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FilledTonalIconButton(
                    enabled = selectedLocal != null && selectedLocal?.isDirectory == false,
                    onClick = { selectedLocal?.let(enqueueUpload) }
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = "Upload")
                }

                Spacer(modifier = Modifier.height(12.dp))

                FilledTonalIconButton(
                    enabled = selectedRemote != null && selectedRemote?.isDirectory == false,
                    onClick = { selectedRemote?.let(enqueueDownload) }
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = "Download")
                }
            }

            // Right Pane: Remote SFTP / FTPS / SCP Endpoint
            CommanderPaneCard(
                paneId = "REMOTE",
                paneTitle = "$remoteTitle ($protocolBadge • $remoteHost)",
                currentPath = remotePath,
                entries = remoteEntries,
                selectedItem = selectedRemote,
                accentColor = NexusTheme.extendedColors.sftpAccent,
                onSelect = { item ->
                    if (item.isDirectory) {
                        remotePath = item.fullPath.ifBlank { "/" }
                        selectedRemote = null
                    } else {
                        selectedRemote = item
                    }
                },
                onRefreshPane = {
                    selectedRemote = null
                    scope.launch { refreshRemotePane(remotePath) }
                },
                onCreateFolder = { dirName ->
                    val safeName = dirName.replace("/", "_").trim()
                    if (safeName.isNotEmpty() && safeName != "." && safeName != "..") {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                File(mapRemoteVirtualToStageFile(remotePath), safeName).mkdirs()
                            }
                            refreshRemotePane(remotePath)
                        }
                    }
                },
                onChmodItem = { item, rwx ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val stageFile = mapRemoteVirtualToStageFile(item.fullPath)
                            if (rwx.all { it in '0'..'7' }) {
                                applyOctalToFile(stageFile, rwx)
                            }
                        }
                        val idx = remoteEntries.indexOfFirst { it.fullPath == item.fullPath }
                        if (idx >= 0) remoteEntries[idx] = item.copy(permissions = rwx)
                    }
                },
                onDeleteItem = { item ->
                    if (item.name != "..") {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                mapRemoteVirtualToStageFile(item.fullPath).deleteRecursively()
                            }
                            if (selectedRemote?.fullPath == item.fullPath) {
                                selectedRemote = null
                            }
                            refreshRemotePane(remotePath)
                        }
                    }
                },
                onReceiveDroppedFile = { srcPane, droppedName, sizeBytes, perms ->
                    if (srcPane == "LOCAL") {
                        enqueueUpload(
                            CommanderFileItem(droppedName, File(localPath, droppedName).absolutePath, false, sizeBytes, perms, "")
                        )
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        // Background Transfer Queue Strip with Pause / Resume / Cancel controls
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.DriveFileMove,
                            contentDescription = null,
                            tint = NexusTheme.extendedColors.sftpAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$transferQueueTitle (${transferQueue.size})",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Text(
                        text = protocolBadge,
                        style = NexusTheme.monospace.keycapShortcut,
                        color = NexusTheme.extendedColors.sftpAccent
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(transferQueue, key = { it.id }) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = item.fileName,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(180.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.directionLabel,
                                style = NexusTheme.monospace.keycapShortcut,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(165.dp),
                                maxLines = 1
                            )
                            LinearProgressIndicator(
                                progress = { item.progressFraction },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(99.dp)),
                                color = if (item.paused) {
                                    MaterialTheme.colorScheme.outline
                                } else {
                                    NexusTheme.extendedColors.sftpAccent
                                }
                            )
                            Text(
                                text = if (item.paused) "PAUSED" else item.speedLabel,
                                style = NexusTheme.monospace.keycapShortcut,
                                color = if (item.paused) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    NexusTheme.extendedColors.statusConnected
                                }
                            )
                            if (!item.completed) {
                                IconButton(
                                    onClick = {
                                        val idx = transferQueue.indexOfFirst { it.id == item.id }
                                        if (idx >= 0) {
                                            transferQueue[idx] = item.copy(paused = !item.paused)
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = if (item.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    activeTransferJobs.remove(item.id)?.cancel()
                                    transferQueue.removeAll { it.id == item.id }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommanderPaneCard(
    paneId: String,
    paneTitle: String,
    currentPath: String,
    entries: List<CommanderFileItem>,
    selectedItem: CommanderFileItem?,
    accentColor: Color,
    onSelect: (CommanderFileItem) -> Unit,
    onRefreshPane: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onChmodItem: (CommanderFileItem, String) -> Unit,
    onDeleteItem: (CommanderFileItem) -> Unit,
    onReceiveDroppedFile: (srcPane: String, fileName: String, sizeBytes: Long, perms: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragHovering by remember { mutableStateOf(false) }
    var showMkdirDialog by remember { mutableStateOf(false) }
    var mkdirName by remember { mutableStateOf("") }
    var chmodTarget by remember { mutableStateOf<CommanderFileItem?>(null) }
    var octalInput by remember { mutableStateOf("0644") }

    val dropTarget = remember(paneId) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                isDragHovering = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isDragHovering = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                isDragHovering = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDragHovering = false
                val clip = event.toAndroidDragEvent().clipData ?: return false
                if (clip.itemCount == 0) return false
                val raw = clip.getItemAt(0).text?.toString().orEmpty()
                val parts = raw.split('|')
                if (parts.size >= 4 && parts[0] != paneId) {
                    onReceiveDroppedFile(
                        parts[0],
                        parts[1],
                        parts[2].toLongOrNull() ?: 0L,
                        parts[3]
                    )
                    return true
                }
                return false
            }
        }
    }

    if (showMkdirDialog) {
        AlertDialog(
            onDismissRequest = { showMkdirDialog = false },
            title = { Text("mkdir • $currentPath", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = mkdirName,
                    onValueChange = { mkdirName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (mkdirName.isNotBlank()) {
                            onCreateFolder(mkdirName.trim())
                            mkdirName = ""
                        }
                        showMkdirDialog = false
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }

    chmodTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { chmodTarget = null },
            title = { Text("chmod • ${item.name}", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = octalInput,
                    onValueChange = { octalInput = it.filter { ch -> ch in '0'..'7' }.take(4) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onChmodItem(item, octalToRwx(octalInput, item.isDirectory))
                        chmodTarget = null
                    }
                ) {
                    Text("chmod")
                }
            }
        )
    }

    Surface(
        modifier = modifier
            .then(
                if (isDragHovering) {
                    Modifier.border(2.dp, accentColor, MaterialTheme.shapes.large)
                } else {
                    Modifier
                }
            )
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN)
                },
                target = dropTarget
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = paneTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentPath,
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row {
                    IconButton(onClick = { showMkdirDialog = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onRefreshPane, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(entries, key = { it.fullPath + it.name }) { item ->
                    val isSelected = selectedItem?.fullPath == item.fullPath && selectedItem.name == item.name
                    var rowMenuExpanded by remember { mutableStateOf(false) }

                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                    else Color.Transparent
                                )
                                .then(
                                    if (!item.isDirectory && item.name != "..") {
                                        Modifier.dragAndDropSource(
                                            transferData = {
                                                val payload = "$paneId|${item.name}|${item.sizeBytes}|${item.permissions}"
                                                DragAndDropTransferData(
                                                    clipData = ClipData.newPlainText("nexusts-sftp", payload)
                                                )
                                            }
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { onSelect(item) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (item.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                                contentDescription = null,
                                tint = if (item.isDirectory) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.permissions,
                                style = NexusTheme.monospace.keycapShortcut,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(84.dp)
                            )
                            Text(
                                text = if (item.isDirectory) "DIR" else formatHumanBytes(item.sizeBytes),
                                style = NexusTheme.monospace.keycapShortcut,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (item.name != "..") {
                                IconButton(
                                    onClick = { rowMenuExpanded = true },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.MoreVert,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        DropdownMenu(
                            expanded = rowMenuExpanded,
                            onDismissRequest = { rowMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("chmod (Octal)") },
                                leadingIcon = { Icon(Icons.Outlined.LockOpen, contentDescription = null) },
                                onClick = {
                                    rowMenuExpanded = false
                                    chmodTarget = item
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("rm • ${item.name}") },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                onClick = {
                                    rowMenuExpanded = false
                                    onDeleteItem(item)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun octalToRwx(octalStr: String, isDir: Boolean): String {
    val mode = octalStr.toIntOrNull(8) ?: 0b110_100_100
    fun triplet(shift: Int): String {
        val bits = (mode ushr shift) and 0b111
        val r = if ((bits and 0b100) != 0) 'r' else '-'
        val w = if ((bits and 0b010) != 0) 'w' else '-'
        val x = if ((bits and 0b001) != 0) 'x' else '-'
        return "$r$w$x"
    }
    val prefix = if (isDir) 'd' else '-'
    return "$prefix${triplet(6)}${triplet(3)}${triplet(0)}"
}

private fun formatHumanBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
