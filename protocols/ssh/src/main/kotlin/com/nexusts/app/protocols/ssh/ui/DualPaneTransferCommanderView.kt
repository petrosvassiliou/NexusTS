package com.nexusts.app.protocols.ssh.ui

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.theme.NexusTheme
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

/**
 * Dual-Pane SFTP / FTP / FTPS / SCP File Transfer Commander View for Googlebook OS & Android Tablets:
 * - Native Drag-and-Drop (`dragAndDropSource` / `dragAndDropTarget`) across Local <-> Remote panes
 * - Context menu for Octal `chmod` permissions (`0755`, `0644`, `0600`) and file deletion
 * - New Directory (`mkdir`) creation modal
 * - Live Background Transfer Queue with Pause / Resume / Cancel job controls
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
    var localPath by remember { mutableStateOf(initialLocalPath) }
    var remotePath by remember { mutableStateOf(initialRemotePath) }
    var selectedLocal by remember { mutableStateOf<CommanderFileItem?>(null) }
    var selectedRemote by remember { mutableStateOf<CommanderFileItem?>(null) }

    val localEntries = remember(localPath) {
        mutableStateListOf(
            CommanderFileItem("..", localPath.substringBeforeLast('/', "/"), true, 0L, "drwxr-xr-x", "2026-11-12"),
            CommanderFileItem("release-bundle-v1.0.tar.zst", "$localPath/release-bundle-v1.0.tar.zst", false, 48_291_840L, "-rw-r--r--", "2026-11-12"),
            CommanderFileItem("k8s-cluster-values.yaml", "$localPath/k8s-cluster-values.yaml", false, 14_920L, "-rw-------", "2026-11-11"),
            CommanderFileItem("certs", "$localPath/certs", true, 4096L, "drwx------", "2026-11-10")
        )
    }

    val remoteEntries = remember(remotePath) {
        mutableStateListOf(
            CommanderFileItem("..", remotePath.substringBeforeLast('/', "/"), true, 0L, "drwxr-xr-x", "2026-11-12"),
            CommanderFileItem("nginx.conf", "$remotePath/nginx.conf", false, 6_420L, "-rw-r--r--", "2026-11-12"),
            CommanderFileItem("systemd-units", "$remotePath/systemd-units", true, 4096L, "drwxr-xr-x", "2026-11-09"),
            CommanderFileItem("docker-compose.prod.yml", "$remotePath/docker-compose.prod.yml", false, 9_812L, "-rw-r-----", "2026-11-12")
        )
    }

    val transferQueue = remember { mutableStateListOf<CommanderTransferTask>() }

    val enqueueUpload: (CommanderFileItem) -> Unit = { file ->
        if (remoteEntries.none { it.name == file.name }) {
            remoteEntries.add(file.copy(fullPath = "$remotePath/${file.name}"))
        }
        transferQueue.add(
            0,
            CommanderTransferTask(
                fileName = file.name,
                directionLabel = "UPLOAD → $remoteHost",
                progressFraction = 1.0f,
                speedLabel = "84.2 MB/s",
                completed = true
            )
        )
    }

    val enqueueDownload: (CommanderFileItem) -> Unit = { file ->
        if (localEntries.none { it.name == file.name }) {
            localEntries.add(file.copy(fullPath = "$localPath/${file.name}"))
        }
        transferQueue.add(
            0,
            CommanderTransferTask(
                fileName = file.name,
                directionLabel = "DOWNLOAD ← $remoteHost",
                progressFraction = 1.0f,
                speedLabel = "92.6 MB/s",
                completed = true
            )
        )
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
            // Left Pane: Local Googlebook OS / SAF Filesystem
            CommanderPaneCard(
                paneId = "LOCAL",
                paneTitle = localTitle,
                currentPath = localPath,
                entries = localEntries,
                selectedItem = selectedLocal,
                accentColor = MaterialTheme.colorScheme.primary,
                onSelect = { item ->
                    if (item.isDirectory) {
                        localPath = item.fullPath.ifBlank { "/" }
                        selectedLocal = null
                    } else {
                        selectedLocal = item
                    }
                },
                onCreateFolder = { dirName ->
                    localEntries.add(
                        CommanderFileItem(dirName, "$localPath/$dirName", true, 4096L, "drwxr-xr-x", "2026-11-12")
                    )
                },
                onChmodItem = { item, rwx ->
                    val idx = localEntries.indexOfFirst { it.fullPath == item.fullPath }
                    if (idx >= 0) localEntries[idx] = item.copy(permissions = rwx)
                },
                onDeleteItem = { item -> localEntries.removeAll { it.fullPath == item.fullPath } },
                onReceiveDroppedFile = { srcPane, droppedName, sizeBytes, perms ->
                    if (srcPane == "REMOTE") {
                        enqueueDownload(
                            CommanderFileItem(droppedName, "$remotePath/$droppedName", false, sizeBytes, perms, "2026-11-12")
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
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                }

                Spacer(modifier = Modifier.height(12.dp))

                FilledTonalIconButton(
                    enabled = selectedRemote != null && selectedRemote?.isDirectory == false,
                    onClick = { selectedRemote?.let(enqueueDownload) }
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null)
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
                onCreateFolder = { dirName ->
                    remoteEntries.add(
                        CommanderFileItem(dirName, "$remotePath/$dirName", true, 4096L, "drwxr-xr-x", "2026-11-12")
                    )
                },
                onChmodItem = { item, rwx ->
                    val idx = remoteEntries.indexOfFirst { it.fullPath == item.fullPath }
                    if (idx >= 0) remoteEntries[idx] = item.copy(permissions = rwx)
                },
                onDeleteItem = { item -> remoteEntries.removeAll { it.fullPath == item.fullPath } },
                onReceiveDroppedFile = { srcPane, droppedName, sizeBytes, perms ->
                    if (srcPane == "LOCAL") {
                        enqueueUpload(
                            CommanderFileItem(droppedName, "$localPath/$droppedName", false, sizeBytes, perms, "2026-11-12")
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
                            IconButton(
                                onClick = { transferQueue.removeAll { it.id == item.id } },
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
                    IconButton(onClick = {}, modifier = Modifier.size(28.dp)) {
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
                                        Modifier.dragAndDropSource {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    val payload = "$paneId|${item.name}|${item.sizeBytes}|${item.permissions}"
                                                    startTransfer(
                                                        DragAndDropTransferData(
                                                            clipData = ClipData.newPlainText("nexusts-sftp", payload)
                                                        )
                                                    )
                                                },
                                                onDrag = { _, _ -> }
                                            )
                                        }
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
