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
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.nexusts.app.core.vault.credentials.ResolvedSessionSecret
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.protocols.ssh.bridge.NativeRemoteFileEntryDto
import com.nexusts.app.protocols.ssh.bridge.SshNativeBridge
import com.nexusts.app.protocols.ssh.sftp.FtpSecurityMode
import com.nexusts.app.protocols.ssh.sftp.JvmFtpFtpsTransferClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
 * Parses either an octal permission string (`"755"`, `"0755"`, `"644"`, `"4755"`) OR a 9/10-character
 * symbolic UNIX permission string (`"rwxr-xr-x"`, `"-rwxr-xr-x"`, `"drwxr-xr-x"`) into its numeric
 * UNIX permission mode (`0755` == `493`). Returns `null` if [input] is malformed.
 */
fun parseChmodPermissionsToOctal(input: String): Int? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val strippedOctal = when {
        trimmed.startsWith("0o", ignoreCase = true) -> trimmed.substring(2)
        else -> trimmed
    }
    if (strippedOctal.length in 1..4 && strippedOctal.all { it in '0'..'7' }) {
        val parsed = strippedOctal.toIntOrNull(8) ?: return null
        return parsed.takeIf { it in 0..0b111_111_111_111 }
    }

    val triplets = when (trimmed.length) {
        9 -> trimmed
        10 -> {
            if (trimmed[0] !in "-dlcbpsD") return null
            trimmed.substring(1)
        }
        else -> return null
    }
    return JvmFtpFtpsTransferClient.symbolicTripletsToOctal(triplets)
}

fun octalModeToRwx(mode: Int, isDir: Boolean): String {
    fun triplet(shift: Int, specialBit: Int, specialExecChar: Char, specialNoExecChar: Char): String {
        val bits = (mode ushr shift) and 0b111
        val r = if ((bits and 0b100) != 0) 'r' else '-'
        val w = if ((bits and 0b010) != 0) 'w' else '-'
        val hasSpecial = (mode and specialBit) != 0
        val hasExec = (bits and 0b001) != 0
        val x = when {
            hasSpecial && hasExec -> specialExecChar
            hasSpecial && !hasExec -> specialNoExecChar
            hasExec -> 'x'
            else -> '-'
        }
        return "$r$w$x"
    }
    val prefix = if (isDir) 'd' else '-'
    val u = triplet(6, 0b100_000_000_000, 's', 'S')
    val g = triplet(3, 0b010_000_000_000, 's', 'S')
    val o = triplet(0, 0b001_000_000_000, 't', 'T')
    return "$prefix$u$g$o"
}

private fun filePermissionsToRwx(file: File): String {
    val prefix = if (file.isDirectory) 'd' else '-'
    val r = if (file.canRead()) 'r' else '-'
    val w = if (file.canWrite()) 'w' else '-'
    val x = if (file.canExecute()) 'x' else '-'
    return "$prefix$r$w$x$r-$x$r-$x"
}

private fun applyOctalModeToLocalFile(file: File, mode: Int) {
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

    return if (parentFile != null && canNavigateUp) {
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
 * Stateful transfer session controller retained inside `ActiveVaultSessionStore` across split-pane
 * tab switches and window detaches. Drives live SFTP/SCP/FTP over Rust JNI (`SshNativeBridge.nativeTransfer*`)
 * and live FTP / Explicit FTPS / Implicit FTPS over `JvmFtpFtpsTransferClient`.
 */
@Stable
class TransferCommanderSessionController(
    val protocolType: VaultProtocolType,
    val host: String,
    val port: Int,
    val secret: ResolvedSessionSecret = ResolvedSessionSecret(),
    val initialLocalPathHint: String = "/home/googlebook/Downloads",
    val initialRemotePathHint: String = "",
    val ftpsImplicitTls: Boolean = false,
    val allowInsecureTls: Boolean = true,
    val bastionHost: String = "",
    val bastionPort: Int = 22,
    val bastionUsername: String = "",
    val bastionPassword: String = "",
    val bastionPrivateKeyPem: String = "",
    private val tunnelLease: Closeable? = null
) : Closeable {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    var localPath by mutableStateOf("")
    var remotePath by mutableStateOf(
        initialRemotePathHint.trim().takeIf { it.isNotEmpty() }?.let { "/" + it.trim('/') } ?: "/"
    )
    var selectedLocal by mutableStateOf<CommanderFileItem?>(null)
    var selectedRemote by mutableStateOf<CommanderFileItem?>(null)
    var remoteStatusBanner by mutableStateOf("")

    val localEntries = mutableStateListOf<CommanderFileItem>()
    val remoteEntries = mutableStateListOf<CommanderFileItem>()
    val transferQueue = mutableStateListOf<CommanderTransferTask>()
    val activeTransferJobs = ConcurrentHashMap<String, Job>()

    private var localInitialized: Boolean = false
    private var remoteInitialized: Boolean = false

    @Volatile
    private var nativeHandle: Long = 0L

    @Volatile
    private var jvmFtpClient: JvmFtpFtpsTransferClient? = null

    private val usesJvmFtpBackend: Boolean
        get() = protocolType == VaultProtocolType.FTPS ||
            (protocolType == VaultProtocolType.FTP && !SshNativeBridge.isNativeLoaded)

    private val usesNativeRustBackend: Boolean
        get() = SshNativeBridge.isNativeLoaded &&
            protocolType in setOf(VaultProtocolType.SFTP, VaultProtocolType.SCP, VaultProtocolType.FTP)

    fun initializeLocalIfNeeded(localRootDir: File) {
        if (!localInitialized) {
            localInitialized = true
            val candidate = File(initialLocalPathHint)
            localPath = if (candidate.exists() && candidate.isDirectory && candidate.canRead()) {
                candidate.absolutePath
            } else {
                localRootDir.absolutePath
            }
        }
    }

    suspend fun refreshLocalPane(localRootDir: File, path: String = localPath) {
        val items = withContext(Dispatchers.IO) {
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
        withContext(Dispatchers.Main.immediate) {
            localEntries.clear()
            localEntries.addAll(items)
        }
    }

    private suspend fun ensureRemoteConnectedLocked(): String {
        if (usesJvmFtpBackend) {
            val existing = jvmFtpClient
            if (existing != null && existing.isConnected) {
                return remotePath
            }
            val mode = when {
                protocolType == VaultProtocolType.FTPS && (ftpsImplicitTls || port == 990) ->
                    FtpSecurityMode.IMPLICIT_FTPS
                protocolType == VaultProtocolType.FTPS ->
                    FtpSecurityMode.EXPLICIT_FTPS
                else ->
                    FtpSecurityMode.PLAIN_FTP
            }
            val client = JvmFtpFtpsTransferClient(
                host = host,
                port = port,
                username = secret.username.ifBlank { "anonymous" },
                password = secret.password,
                securityMode = mode,
                allowInsecureTls = allowInsecureTls
            )
            client.connect()
            jvmFtpClient = client
            if (!remoteInitialized) {
                remoteInitialized = true
                if (initialRemotePathHint.isBlank()) {
                    val serverPwd = runCatching { client.pwd() }.getOrDefault("/")
                    remotePath = serverPwd.ifBlank { "/" }
                }
            }
            return remotePath
        }

        if (usesNativeRustBackend) {
            if (nativeHandle > 0L) {
                return remotePath
            }
            val protoName = when (protocolType) {
                VaultProtocolType.SCP -> "SCP"
                VaultProtocolType.FTP -> "FTP"
                else -> "SFTP"
            }
            val handle = SshNativeBridge.nativeTransferConnect(
                protocol = protoName,
                host = host.trim(),
                port = port,
                username = secret.username.ifBlank { if (protoName == "FTP") "anonymous" else "root" },
                password = secret.password,
                privateKeyPem = secret.privateKeyPem,
                passphrase = secret.keyPassphrase,
                bastionHost = bastionHost,
                bastionPort = bastionPort,
                bastionUser = bastionUsername,
                bastionPassword = bastionPassword,
                bastionKeyPem = bastionPrivateKeyPem
            )
            if (handle <= 0L) {
                val err = SshNativeBridge.nativeTransferLastError(0L).ifBlank {
                    "$protoName connection to $host:$port failed"
                }
                throw IOException(err)
            }
            nativeHandle = handle
            if (!remoteInitialized) {
                remoteInitialized = true
                if (initialRemotePathHint.isBlank()) {
                    val pwd = SshNativeBridge.nativeTransferPwd(handle)
                    if (pwd.isNotBlank()) {
                        remotePath = pwd
                    }
                }
            }
            return remotePath
        }

        throw IOException("Native ${protocolType.name} transport library is not loaded on this host JVM")
    }

    suspend fun refreshRemotePane(targetPath: String = remotePath) {
        val outcome = withContext(Dispatchers.IO) {
            ioMutex.withLock {
                runCatching {
                    val initialResolved = ensureRemoteConnectedLocked()
                    val effectiveTarget = if (!remoteInitialized || targetPath.isBlank()) {
                        initialResolved
                    } else {
                        targetPath
                    }
                    val normalizedPath = if (effectiveTarget.startsWith("/")) {
                        effectiveTarget
                    } else {
                        "/$effectiveTarget"
                    }
                    val children: List<CommanderFileItem> = when {
                        usesJvmFtpBackend -> {
                            val client = jvmFtpClient ?: error("FTP/FTPS session disconnected")
                            client.listDirectory(normalizedPath).map { entry ->
                                CommanderFileItem(
                                    name = entry.name,
                                    fullPath = entry.fullPath,
                                    isDirectory = entry.isDirectory,
                                    sizeBytes = if (entry.isDirectory) 4096L else entry.sizeBytes,
                                    permissions = entry.formatRwxPermissions(),
                                    modifiedIso = entry.modifiedIso
                                )
                            }
                        }
                        usesNativeRustBackend -> {
                            val handle = nativeHandle
                            val rawJson = SshNativeBridge.nativeTransferListDirJson(handle, normalizedPath)
                            val err = SshNativeBridge.nativeTransferLastError(handle)
                            if (rawJson == "[]" && err.isNotBlank()) {
                                throw IOException(err)
                            }
                            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                            val dtos = json.decodeFromString(
                                ListSerializer(NativeRemoteFileEntryDto.serializer()),
                                rawJson.ifBlank { "[]" }
                            )
                            dtos.map { dto ->
                                val modIso = if (dto.modified_epoch_sec > 0L) {
                                    fmt.format(Date(dto.modified_epoch_sec * 1000L))
                                } else {
                                    ""
                                }
                                CommanderFileItem(
                                    name = dto.name,
                                    fullPath = dto.full_path,
                                    isDirectory = dto.is_directory,
                                    sizeBytes = if (dto.is_directory) 4096L else dto.size_bytes,
                                    permissions = octalModeToRwx(dto.unix_mode, dto.is_directory),
                                    modifiedIso = modIso
                                )
                            }
                        }
                        else -> emptyList()
                    }.sortedWith(
                        compareByDescending<CommanderFileItem> { it.isDirectory }
                            .thenBy { it.name.lowercase(Locale.ROOT) }
                    )

                    val withUp = if (normalizedPath != "/") {
                        val parent = normalizedPath.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
                        listOf(
                            CommanderFileItem(
                                name = "..",
                                fullPath = parent,
                                isDirectory = true,
                                sizeBytes = 0L,
                                permissions = "drwxr-xr-x",
                                modifiedIso = ""
                            )
                        ) + children
                    } else {
                        children
                    }
                    normalizedPath to withUp
                }
            }
        }

        withContext(Dispatchers.Main.immediate) {
            outcome.onSuccess { (resolvedPath, items) ->
                remotePath = resolvedPath
                remoteStatusBanner = ""
                remoteEntries.clear()
                remoteEntries.addAll(items)
            }.onFailure { err ->
                remoteStatusBanner = err.message ?: "Remote ${protocolType.name} connection failed"
                remoteEntries.clear()
            }
        }
    }

    fun enqueueUpload(localFileItem: CommanderFileItem) {
        if (localFileItem.isDirectory) return
        val taskId = UUID.randomUUID().toString()
        val destRemoteDir = remotePath.trimEnd('/').ifEmpty { "/" }
        val destRemotePath = if (destRemoteDir == "/") {
            "/${localFileItem.name}"
        } else {
            "$destRemoteDir/${localFileItem.name}"
        }

        transferQueue.add(
            0,
            CommanderTransferTask(
                id = taskId,
                fileName = localFileItem.name,
                directionLabel = "UPLOAD → $host:$port",
                progressFraction = 0f,
                speedLabel = "Starting…",
                completed = false
            )
        )

        val job = scope.launch {
            val startedNs = System.nanoTime()
            val result = runCatching {
                val srcFile = File(localFileItem.fullPath)
                if (!srcFile.exists() || !srcFile.isFile) {
                    throw IOException("Local file '${srcFile.name}' not found")
                }
                waitWhilePaused(taskId) ?: return@runCatching 0L
                val bytes = srcFile.readBytes()
                val modeOctal = parseChmodPermissionsToOctal(localFileItem.permissions) ?: 0b110_100_100
                updateTaskProgress(taskId, 0.35f, "${formatHumanBytes(bytes.size.toLong())} • Uploading…")

                ioMutex.withLock {
                    ensureRemoteConnectedLocked()
                    when {
                        usesJvmFtpBackend -> {
                            val ok = jvmFtpClient?.uploadFile(destRemotePath, bytes) ?: false
                            if (!ok) throw IOException("FTP/FTPS STOR failed for $destRemotePath")
                        }
                        usesNativeRustBackend -> {
                            val ok = SshNativeBridge.nativeTransferWriteFile(
                                nativeHandle,
                                destRemotePath,
                                bytes,
                                modeOctal
                            )
                            if (!ok) {
                                val err = SshNativeBridge.nativeTransferLastError(nativeHandle)
                                throw IOException(err.ifBlank { "Upload failed for $destRemotePath" })
                            }
                        }
                        else -> throw IOException("Remote ${protocolType.name} transport unavailable")
                    }
                }
                bytes.size.toLong()
            }

            activeTransferJobs.remove(taskId)
            val elapsedSec = ((System.nanoTime() - startedNs) / 1_000_000_000.0).coerceAtLeast(0.001)
            withContext(Dispatchers.Main.immediate) {
                val idx = transferQueue.indexOfFirst { it.id == taskId }
                if (idx >= 0) {
                    result.onSuccess { total ->
                        val mbps = (total / 1_048_576.0) / elapsedSec
                        transferQueue[idx] = transferQueue[idx].copy(
                            progressFraction = 1.0f,
                            speedLabel = "${formatHumanBytes(total)} • ${String.format(Locale.US, "%.1f MB/s", mbps)} • Done",
                            paused = false,
                            completed = true
                        )
                    }.onFailure { err ->
                        transferQueue[idx] = transferQueue[idx].copy(
                            speedLabel = "Failed: ${err.message ?: "I/O error"}",
                            paused = false
                        )
                    }
                }
            }
            if (result.isSuccess) {
                refreshRemotePane(remotePath)
            }
        }
        activeTransferJobs[taskId] = job
    }

    fun enqueueDownload(remoteFileItem: CommanderFileItem, localRootDir: File) {
        if (remoteFileItem.isDirectory) return
        val taskId = UUID.randomUUID().toString()
        val dstFile = File(localPath, remoteFileItem.name)

        transferQueue.add(
            0,
            CommanderTransferTask(
                id = taskId,
                fileName = remoteFileItem.name,
                directionLabel = "DOWNLOAD ← $host:$port",
                progressFraction = 0f,
                speedLabel = "Starting…",
                completed = false
            )
        )

        val job = scope.launch {
            val startedNs = System.nanoTime()
            val result = runCatching {
                waitWhilePaused(taskId) ?: return@runCatching 0L
                updateTaskProgress(taskId, 0.25f, "Downloading…")
                val downloaded: ByteArray = ioMutex.withLock {
                    ensureRemoteConnectedLocked()
                    when {
                        usesJvmFtpBackend -> {
                            jvmFtpClient?.downloadFile(remoteFileItem.fullPath)
                                ?: throw IOException("FTP/FTPS session not connected")
                        }
                        usesNativeRustBackend -> {
                            SshNativeBridge.nativeTransferReadFile(nativeHandle, remoteFileItem.fullPath)
                                ?: throw IOException(
                                    SshNativeBridge.nativeTransferLastError(nativeHandle)
                                        .ifBlank { "Download failed for ${remoteFileItem.fullPath}" }
                                )
                        }
                        else -> throw IOException("Remote ${protocolType.name} transport unavailable")
                    }
                }
                waitWhilePaused(taskId) ?: return@runCatching 0L
                dstFile.parentFile?.mkdirs()
                dstFile.writeBytes(downloaded)
                downloaded.size.toLong()
            }

            activeTransferJobs.remove(taskId)
            val elapsedSec = ((System.nanoTime() - startedNs) / 1_000_000_000.0).coerceAtLeast(0.001)
            withContext(Dispatchers.Main.immediate) {
                val idx = transferQueue.indexOfFirst { it.id == taskId }
                if (idx >= 0) {
                    result.onSuccess { total ->
                        val mbps = (total / 1_048_576.0) / elapsedSec
                        transferQueue[idx] = transferQueue[idx].copy(
                            progressFraction = 1.0f,
                            speedLabel = "${formatHumanBytes(total)} • ${String.format(Locale.US, "%.1f MB/s", mbps)} • Done",
                            paused = false,
                            completed = true
                        )
                    }.onFailure { err ->
                        transferQueue[idx] = transferQueue[idx].copy(
                            speedLabel = "Failed: ${err.message ?: "I/O error"}",
                            paused = false
                        )
                    }
                }
            }
            if (result.isSuccess) {
                refreshLocalPane(localRootDir, localPath)
            }
        }
        activeTransferJobs[taskId] = job
    }

    private suspend fun waitWhilePaused(taskId: String): Unit? {
        while (scope.isActive) {
            val task = withContext(Dispatchers.Main.immediate) {
                transferQueue.firstOrNull { it.id == taskId }
            } ?: return null
            if (!task.paused) return Unit
            delay(80L)
        }
        return null
    }

    private suspend fun updateTaskProgress(taskId: String, progress: Float, label: String) {
        withContext(Dispatchers.Main.immediate) {
            val idx = transferQueue.indexOfFirst { it.id == taskId }
            if (idx >= 0) {
                transferQueue[idx] = transferQueue[idx].copy(
                    progressFraction = progress.coerceIn(0f, 1f),
                    speedLabel = label
                )
            }
        }
    }

    fun createRemoteFolder(dirName: String) {
        val safeName = dirName.replace("/", "_").trim()
        if (safeName.isEmpty() || safeName == "." || safeName == "..") return
        val base = remotePath.trimEnd('/').ifEmpty { "/" }
        val fullRemote = if (base == "/") "/$safeName" else "$base/$safeName"
        scope.launch {
            val err = ioMutex.withLock {
                runCatching {
                    ensureRemoteConnectedLocked()
                    val ok = when {
                        usesJvmFtpBackend -> jvmFtpClient?.mkdir(fullRemote) == true
                        usesNativeRustBackend -> SshNativeBridge.nativeTransferMkdir(nativeHandle, fullRemote)
                        else -> false
                    }
                    if (!ok) {
                        throw IOException(
                            if (usesNativeRustBackend) {
                                SshNativeBridge.nativeTransferLastError(nativeHandle).ifBlank { "mkdir failed" }
                            } else {
                                "Remote MKD failed for $fullRemote"
                            }
                        )
                    }
                }.exceptionOrNull()
            }
            if (err != null) {
                withContext(Dispatchers.Main.immediate) {
                    remoteStatusBanner = err.message ?: "mkdir failed"
                }
            } else {
                refreshRemotePane(remotePath)
            }
        }
    }

    fun chmodRemoteItem(item: CommanderFileItem, rawPermInput: String) {
        val mode = parseChmodPermissionsToOctal(rawPermInput) ?: return
        scope.launch {
            val err = ioMutex.withLock {
                runCatching {
                    ensureRemoteConnectedLocked()
                    val ok = when {
                        usesJvmFtpBackend -> jvmFtpClient?.chmod(item.fullPath, mode) == true
                        usesNativeRustBackend -> SshNativeBridge.nativeTransferChmod(nativeHandle, item.fullPath, mode)
                        else -> false
                    }
                    if (!ok) {
                        throw IOException(
                            if (usesNativeRustBackend) {
                                SshNativeBridge.nativeTransferLastError(nativeHandle).ifBlank { "chmod failed" }
                            } else {
                                "Remote CHMOD failed for ${item.fullPath}"
                            }
                        )
                    }
                }.exceptionOrNull()
            }
            if (err != null) {
                withContext(Dispatchers.Main.immediate) {
                    remoteStatusBanner = err.message ?: "chmod failed"
                }
            } else {
                refreshRemotePane(remotePath)
            }
        }
    }

    fun renameRemoteItem(item: CommanderFileItem, newName: String) {
        val safeName = newName.replace("/", "_").trim()
        if (safeName.isEmpty() || safeName == "." || safeName == ".." || safeName == item.name) return
        val parent = item.fullPath.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        val newFullPath = if (parent == "/") "/$safeName" else "$parent/$safeName"
        scope.launch {
            val err = ioMutex.withLock {
                runCatching {
                    ensureRemoteConnectedLocked()
                    val ok = when {
                        usesJvmFtpBackend -> jvmFtpClient?.rename(item.fullPath, newFullPath) == true
                        usesNativeRustBackend -> SshNativeBridge.nativeTransferRename(nativeHandle, item.fullPath, newFullPath)
                        else -> false
                    }
                    if (!ok) {
                        throw IOException(
                            if (usesNativeRustBackend) {
                                SshNativeBridge.nativeTransferLastError(nativeHandle).ifBlank { "rename failed" }
                            } else {
                                "Remote rename failed for ${item.fullPath}"
                            }
                        )
                    }
                }.exceptionOrNull()
            }
            if (err != null) {
                withContext(Dispatchers.Main.immediate) {
                    remoteStatusBanner = err.message ?: "rename failed"
                }
            } else {
                refreshRemotePane(remotePath)
            }
        }
    }

    fun deleteRemoteItem(item: CommanderFileItem) {
        if (item.name == "..") return
        scope.launch {
            val err = ioMutex.withLock {
                runCatching {
                    ensureRemoteConnectedLocked()
                    val ok = when {
                        usesJvmFtpBackend -> jvmFtpClient?.remove(item.fullPath, item.isDirectory) == true
                        usesNativeRustBackend -> SshNativeBridge.nativeTransferRemove(nativeHandle, item.fullPath, item.isDirectory)
                        else -> false
                    }
                    if (!ok) {
                        throw IOException(
                            if (usesNativeRustBackend) {
                                SshNativeBridge.nativeTransferLastError(nativeHandle).ifBlank { "remove failed" }
                            } else {
                                "Remote delete failed for ${item.fullPath}"
                            }
                        )
                    }
                }.exceptionOrNull()
            }
            if (err != null) {
                withContext(Dispatchers.Main.immediate) {
                    remoteStatusBanner = err.message ?: "delete failed"
                }
            } else {
                withContext(Dispatchers.Main.immediate) {
                    if (selectedRemote?.fullPath == item.fullPath) {
                        selectedRemote = null
                    }
                }
                refreshRemotePane(remotePath)
            }
        }
    }

    override fun close() {
        activeTransferJobs.values.forEach { it.cancel() }
        activeTransferJobs.clear()
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle > 0L && SshNativeBridge.isNativeLoaded) {
            runCatching { SshNativeBridge.nativeTransferDisconnect(handle) }
        }
        val ftp = jvmFtpClient
        jvmFtpClient = null
        runCatching { ftp?.close() }
        runCatching { tunnelLease?.close() }
        scope.cancel()
    }
}

/**
 * Dual-Pane SFTP / FTP / FTPS / SCP File Transfer Commander View for Googlebook OS & Android Tablets:
 * - Real local filesystem browsing rooted at `context.filesDir`
 * - Live remote SFTP, SCP, FTP (Rust JNI + JVM FTP fallback) & Explicit/Implicit FTPS (`JvmFtpFtpsTransferClient`)
 * - Persistent session & directory state across split-pane tab switches via [sessionController]
 * - Native Drag-and-Drop (`dragAndDropSource` / `dragAndDropTarget`) across Local <-> Remote panes
 * - Context menu for Octal & Symbolic `chmod` permissions (`0755`, `rwxr-xr-x`, `0644`), `mv` rename, and `rm` deletion
 * - New Directory (`mkdir`) creation modal and Live Background Transfer Queue with Pause / Resume / Cancel
 */
@Composable
fun DualPaneTransferCommanderView(
    protocolBadge: String,
    remoteHost: String,
    localTitle: String,
    remoteTitle: String,
    transferQueueTitle: String,
    initialLocalPath: String = "/home/googlebook/Downloads",
    initialRemotePath: String = "",
    connection: VaultConnection? = null,
    secret: ResolvedSessionSecret = ResolvedSessionSecret(),
    adHocPort: Int? = null,
    controller: TransferCommanderSessionController? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val localRootDir = remember(context) { context.filesDir }

    val resolvedController = remember(controller, protocolBadge, remoteHost, connection?.id, adHocPort) {
        controller ?: run {
            val proto = connection?.protocol ?: when (protocolBadge.uppercase(Locale.ROOT)) {
                "SCP" -> VaultProtocolType.SCP
                "FTP" -> VaultProtocolType.FTP
                "FTPS" -> VaultProtocolType.FTPS
                else -> VaultProtocolType.SFTP
            }
            val ftSettings = connection?.settings as? ProtocolSpecificSettings.FileTransfer
            val defaultPort = when (proto) {
                VaultProtocolType.FTP -> 21
                VaultProtocolType.FTPS -> if (ftSettings?.ftpsImplicitTls == true) 990 else 21
                else -> 22
            }
            TransferCommanderSessionController(
                protocolType = proto,
                host = connection?.host?.takeIf { it.isNotBlank() } ?: remoteHost,
                port = adHocPort ?: ftSettings?.port?.takeIf { it > 0 } ?: defaultPort,
                secret = secret,
                initialLocalPathHint = ftSettings?.initialLocalDirectory?.ifBlank { initialLocalPath } ?: initialLocalPath,
                initialRemotePathHint = ftSettings?.initialRemoteDirectory?.ifBlank { initialRemotePath } ?: initialRemotePath,
                ftpsImplicitTls = ftSettings?.ftpsImplicitTls ?: false
            )
        }
    }

    LaunchedEffect(resolvedController, localRootDir) {
        resolvedController.initializeLocalIfNeeded(localRootDir)
        resolvedController.refreshLocalPane(localRootDir, resolvedController.localPath)
        if (resolvedController.remoteEntries.isEmpty()) {
            resolvedController.refreshRemotePane(resolvedController.remotePath)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (resolvedController.remoteStatusBanner.isNotBlank()) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${resolvedController.protocolType.name} (${resolvedController.host}:${resolvedController.port}): ${resolvedController.remoteStatusBanner}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(
                        onClick = {
                            uiScope.launch { resolvedController.refreshRemotePane(resolvedController.remotePath) }
                        }
                    ) {
                        Text("Retry")
                    }
                }
            }
        }

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
                currentPath = resolvedController.localPath,
                entries = resolvedController.localEntries,
                selectedItem = resolvedController.selectedLocal,
                accentColor = MaterialTheme.colorScheme.primary,
                onSelect = { item ->
                    if (item.isDirectory) {
                        val nextPath = item.fullPath.ifBlank { localRootDir.absolutePath }
                        resolvedController.localPath = nextPath
                        resolvedController.selectedLocal = null
                        uiScope.launch { resolvedController.refreshLocalPane(localRootDir, nextPath) }
                    } else {
                        resolvedController.selectedLocal = item
                    }
                },
                onRefreshPane = {
                    resolvedController.selectedLocal = null
                    uiScope.launch { resolvedController.refreshLocalPane(localRootDir, resolvedController.localPath) }
                },
                onCreateFolder = { dirName ->
                    val safeName = dirName.replace("/", "_").trim()
                    if (safeName.isNotEmpty() && safeName != "." && safeName != "..") {
                        uiScope.launch {
                            withContext(Dispatchers.IO) {
                                File(resolvedController.localPath, safeName).mkdirs()
                            }
                            resolvedController.refreshLocalPane(localRootDir, resolvedController.localPath)
                        }
                    }
                },
                onChmodItem = { item, rawPermInput ->
                    val mode = parseChmodPermissionsToOctal(rawPermInput)
                    if (mode != null) {
                        uiScope.launch {
                            withContext(Dispatchers.IO) {
                                applyOctalModeToLocalFile(File(item.fullPath), mode)
                            }
                            resolvedController.refreshLocalPane(localRootDir, resolvedController.localPath)
                        }
                    }
                },
                onRenameItem = { item, newName ->
                    val safeName = newName.replace("/", "_").trim()
                    if (safeName.isNotEmpty() && safeName != "." && safeName != "..") {
                        uiScope.launch {
                            withContext(Dispatchers.IO) {
                                val src = File(item.fullPath)
                                val dst = File(src.parentFile ?: localRootDir, safeName)
                                src.renameTo(dst)
                            }
                            resolvedController.refreshLocalPane(localRootDir, resolvedController.localPath)
                        }
                    }
                },
                onDeleteItem = { item ->
                    if (item.name != "..") {
                        uiScope.launch {
                            withContext(Dispatchers.IO) {
                                File(item.fullPath).deleteRecursively()
                            }
                            if (resolvedController.selectedLocal?.fullPath == item.fullPath) {
                                resolvedController.selectedLocal = null
                            }
                            resolvedController.refreshLocalPane(localRootDir, resolvedController.localPath)
                        }
                    }
                },
                onReceiveDroppedFile = { srcPane, droppedName, sizeBytes, perms ->
                    if (srcPane == "REMOTE") {
                        val cleanRemote = "/" + resolvedController.remotePath.trim('/')
                        val remoteFull = if (cleanRemote == "/") "/$droppedName" else "$cleanRemote/$droppedName"
                        resolvedController.enqueueDownload(
                            CommanderFileItem(droppedName, remoteFull, false, sizeBytes, perms, ""),
                            localRootDir
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
                    enabled = resolvedController.selectedLocal != null && resolvedController.selectedLocal?.isDirectory == false,
                    onClick = { resolvedController.selectedLocal?.let(resolvedController::enqueueUpload) }
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = "Upload")
                }

                Spacer(modifier = Modifier.height(12.dp))

                FilledTonalIconButton(
                    enabled = resolvedController.selectedRemote != null && resolvedController.selectedRemote?.isDirectory == false,
                    onClick = {
                        resolvedController.selectedRemote?.let {
                            resolvedController.enqueueDownload(it, localRootDir)
                        }
                    }
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = "Download")
                }
            }

            // Right Pane: Live Remote SFTP / SCP / FTP / FTPS Endpoint
            CommanderPaneCard(
                paneId = "REMOTE",
                paneTitle = "$remoteTitle ($protocolBadge • ${resolvedController.host}:${resolvedController.port})",
                currentPath = resolvedController.remotePath,
                entries = resolvedController.remoteEntries,
                selectedItem = resolvedController.selectedRemote,
                accentColor = NexusTheme.extendedColors.sftpAccent,
                onSelect = { item ->
                    if (item.isDirectory) {
                        val nextRemote = item.fullPath.ifBlank { "/" }
                        resolvedController.remotePath = nextRemote
                        resolvedController.selectedRemote = null
                        uiScope.launch { resolvedController.refreshRemotePane(nextRemote) }
                    } else {
                        resolvedController.selectedRemote = item
                    }
                },
                onRefreshPane = {
                    resolvedController.selectedRemote = null
                    uiScope.launch { resolvedController.refreshRemotePane(resolvedController.remotePath) }
                },
                onCreateFolder = { dirName ->
                    resolvedController.createRemoteFolder(dirName)
                },
                onChmodItem = { item, rawPermInput ->
                    resolvedController.chmodRemoteItem(item, rawPermInput)
                },
                onRenameItem = { item, newName ->
                    resolvedController.renameRemoteItem(item, newName)
                },
                onDeleteItem = { item ->
                    resolvedController.deleteRemoteItem(item)
                },
                onReceiveDroppedFile = { srcPane, droppedName, sizeBytes, perms ->
                    if (srcPane == "LOCAL") {
                        resolvedController.enqueueUpload(
                            CommanderFileItem(
                                droppedName,
                                File(resolvedController.localPath, droppedName).absolutePath,
                                false,
                                sizeBytes,
                                perms,
                                ""
                            )
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
                            text = "$transferQueueTitle (${resolvedController.transferQueue.size})",
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
                    items(resolvedController.transferQueue, key = { it.id }) { item ->
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
                                        val idx = resolvedController.transferQueue.indexOfFirst { it.id == item.id }
                                        if (idx >= 0) {
                                            resolvedController.transferQueue[idx] = item.copy(paused = !item.paused)
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
                                    resolvedController.activeTransferJobs.remove(item.id)?.cancel()
                                    resolvedController.transferQueue.removeAll { it.id == item.id }
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
    onRenameItem: (CommanderFileItem, String) -> Unit,
    onDeleteItem: (CommanderFileItem) -> Unit,
    onReceiveDroppedFile: (srcPane: String, fileName: String, sizeBytes: Long, perms: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragHovering by remember { mutableStateOf(false) }
    var showMkdirDialog by remember { mutableStateOf(false) }
    var mkdirName by remember { mutableStateOf("") }
    var chmodTarget by remember { mutableStateOf<CommanderFileItem?>(null) }
    var permInput by remember { mutableStateOf("0644") }
    var renameTarget by remember { mutableStateOf<CommanderFileItem?>(null) }
    var renameInput by remember { mutableStateOf("") }

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
        val parsedOctal = parseChmodPermissionsToOctal(permInput)
        AlertDialog(
            onDismissRequest = { chmodTarget = null },
            title = { Text("chmod • ${item.name}", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = permInput,
                        onValueChange = { next ->
                            permInput = next.filter { ch -> ch in "01234567rwxstSTdl-" }.take(10)
                        },
                        label = { Text("Octal (e.g. 0755) or Symbolic (rwxr-xr-x)") },
                        singleLine = true,
                        isError = permInput.isNotBlank() && parsedOctal == null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (parsedOctal != null) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Resolved: %04o (%s)",
                                parsedOctal,
                                octalModeToRwx(parsedOctal, item.isDirectory)
                            ),
                            style = NexusTheme.monospace.keycapShortcut,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = parsedOctal != null,
                    onClick = {
                        onChmodItem(item, permInput)
                        chmodTarget = null
                    }
                ) {
                    Text("chmod")
                }
            }
        )
    }

    renameTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("mv • ${item.name}", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameInput.isNotBlank() && renameInput.trim() != item.name,
                    onClick = {
                        onRenameItem(item, renameInput.trim())
                        renameTarget = null
                    }
                ) {
                    Text("Rename")
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
                items(entries, key = { it.fullPath + "|" + it.name }) { item ->
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
                                text = { Text("chmod (Octal / rwx)") },
                                leadingIcon = { Icon(Icons.Outlined.LockOpen, contentDescription = null) },
                                onClick = {
                                    rowMenuExpanded = false
                                    val currentOct = parseChmodPermissionsToOctal(item.permissions)
                                    permInput = if (currentOct != null) {
                                        String.format(Locale.US, "%04o", currentOct)
                                    } else {
                                        "0644"
                                    }
                                    chmodTarget = item
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("mv • Rename") },
                                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                                onClick = {
                                    rowMenuExpanded = false
                                    renameInput = item.name
                                    renameTarget = item
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

private fun formatHumanBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
