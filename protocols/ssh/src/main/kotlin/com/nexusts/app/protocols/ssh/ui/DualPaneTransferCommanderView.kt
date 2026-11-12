package com.nexusts.app.protocols.ssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.theme.NexusTheme
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
    val completed: Boolean
)

/**
 * Dual-Pane SFTP / FTP / FTPS / SCP File Transfer Commander View with Local <-> Remote panes,
 * one-click/drag directional transfers, permissions column, and live Background Transfer Queue.
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
        listOf(
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
                    onClick = {
                        val file = selectedLocal ?: return@FilledTonalIconButton
                        remoteEntries.add(file.copy(fullPath = "$remotePath/${file.name}"))
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
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                }

                Spacer(modifier = Modifier.height(12.dp))

                FilledTonalIconButton(
                    enabled = selectedRemote != null && selectedRemote?.isDirectory == false,
                    onClick = {
                        val file = selectedRemote ?: return@FilledTonalIconButton
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
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                }
            }

            // Right Pane: Remote SFTP / FTPS / SCP Endpoint
            CommanderPaneCard(
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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        // Background Transfer Queue Strip
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
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
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = item.fileName,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(190.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.directionLabel,
                                style = NexusTheme.monospace.keycapShortcut,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(180.dp),
                                maxLines = 1
                            )
                            LinearProgressIndicator(
                                progress = { item.progressFraction },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(99.dp)),
                                color = NexusTheme.extendedColors.sftpAccent
                            )
                            Text(
                                text = item.speedLabel,
                                style = NexusTheme.monospace.keycapShortcut,
                                color = NexusTheme.extendedColors.statusConnected
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommanderPaneCard(
    paneTitle: String,
    currentPath: String,
    entries: List<CommanderFileItem>,
    selectedItem: CommanderFileItem?,
    accentColor: androidx.compose.ui.graphics.Color,
    onSelect: (CommanderFileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
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
                    IconButton(onClick = {}, modifier = Modifier.size(28.dp)) {
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .clickable { onSelect(item) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
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
                            modifier = Modifier.width(86.dp)
                        )
                        Text(
                            text = if (item.isDirectory) "DIR" else formatHumanBytes(item.sizeBytes),
                            style = NexusTheme.monospace.keycapShortcut,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun formatHumanBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format("%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
