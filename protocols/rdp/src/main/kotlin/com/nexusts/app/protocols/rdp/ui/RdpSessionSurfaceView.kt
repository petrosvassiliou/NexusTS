package com.nexusts.app.protocols.rdp.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.nexusts.app.core.ui.components.NexusActionChip
import com.nexusts.app.core.ui.input.isSystemPassThroughKeyEvent
import com.nexusts.app.core.ui.input.nexusMouseWheelHandler
import com.nexusts.app.core.ui.input.nexusTrackpadCanvasGesture
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.vault.model.RdpAudioRedirectionMode
import com.nexusts.app.core.vault.model.RdpGfxCodecProfile
import com.nexusts.app.core.vault.model.RdpResizeBehavior
import com.nexusts.app.protocols.rdp.bridge.FreeRdpNativeBridge
import com.nexusts.app.protocols.rdp.bridge.RdpManagedSession
import com.nexusts.app.protocols.rdp.bridge.RemoteClipboardKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private fun transformViewportToRemoteDesktop(
    rawX: Float,
    rawY: Float,
    viewportSize: IntSize,
    remoteDimensions: Pair<Int, Int>,
    zoomScale: Float,
    panOffsetX: Float,
    panOffsetY: Float
): Pair<Int, Int> {
    val vw = viewportSize.width.coerceAtLeast(1).toFloat()
    val vh = viewportSize.height.coerceAtLeast(1).toFloat()
    val safeZoom = zoomScale.coerceAtLeast(0.1f)

    // Invert graphicsLayer center-pivoted scale + pan translation
    val unzoomedX = ((rawX - vw / 2f - panOffsetX) / safeZoom) + vw / 2f
    val unzoomedY = ((rawY - vh / 2f - panOffsetY) / safeZoom) + vh / 2f

    val remoteW = remoteDimensions.first.coerceAtLeast(1)
    val remoteH = remoteDimensions.second.coerceAtLeast(1)

    val mappedX = ((unzoomedX / vw) * remoteW.toFloat()).toInt().coerceIn(0, remoteW - 1)
    val mappedY = ((unzoomedY / vh) * remoteH.toFloat()).toInt().coerceIn(0, remoteH - 1)
    return mappedX to mappedY
}

private fun readHostClipboardUris(manager: ClipboardManager?): List<Uri> {
    val clip = runCatching { manager?.primaryClip }.getOrNull() ?: return emptyList()
    if (clip.itemCount <= 0) return emptyList()
    val result = mutableListOf<Uri>()
    for (idx in 0 until clip.itemCount) {
        val uri = runCatching { clip.getItemAt(idx)?.uri }.getOrNull() ?: continue
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme == "content" || scheme == "file") {
            result += uri
        }
    }
    return result
}

private fun sanitizeStagedDisplayName(rawName: String, fallbackIndex: Int): String {
    val cleaned = rawName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(':', '_')
        .trim()
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
        return "clipboard_file_${fallbackIndex + 1}"
    }
    return cleaned
}

private fun stageHostClipboardUrisToLocalFiles(context: Context, uris: List<Uri>): List<String> {
    if (uris.isEmpty()) return emptyList()
    val sendRoot = File(context.cacheDir, "cliprdr_send")
    // Prune older staging directories cleanly before creating a fresh batch
    runCatching {
        sendRoot.listFiles()?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.deleteRecursively() }
    }
    val batchDir = File(sendRoot, "batch_${System.currentTimeMillis()}").apply { mkdirs() }
    val stagedPaths = mutableListOf<String>()
    val usedNames = mutableSetOf<String>()

    uris.forEachIndexed { idx, uri ->
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val directFile = uri.path?.let(::File)
            if (directFile != null && directFile.exists() && directFile.canRead()) {
                stagedPaths += directFile.absolutePath
                return@forEachIndexed
            }
        }
        val queriedName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (col >= 0) cursor.getString(col) else null
                } else {
                    null
                }
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

        var fileName = sanitizeStagedDisplayName(queriedName, idx)
        var dedup = 2
        while (!usedNames.add(fileName.lowercase())) {
            val dot = fileName.lastIndexOf('.')
            fileName = if (dot > 0) {
                "${fileName.substring(0, dot)} ($dedup)${fileName.substring(dot)}"
            } else {
                "$fileName ($dedup)"
            }
            dedup++
        }
        val destFile = File(batchDir, fileName)
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (copied && destFile.exists()) {
            stagedPaths += destFile.absolutePath
        }
    }
    return stagedPaths
}

private fun publishRemoteFilesToHostClipboard(
    context: Context,
    manager: ClipboardManager?,
    localPaths: List<String>
): String? {
    if (manager == null || localPaths.isEmpty()) return null
    val authority = "${context.packageName}.fileprovider"
    val exportedUris = localPaths.mapNotNull { rawPath ->
        val file = File(rawPath)
        if (!file.exists()) return@mapNotNull null
        if (file.isDirectory) {
            // FileProvider serves concrete files; collect top-level files when a single small folder was copied
            file.walkTopDown().filter { it.isFile }.take(32).mapNotNull { child ->
                runCatching { FileProvider.getUriForFile(context, authority, child) }.getOrNull()
            }.toList()
        } else {
            listOfNotNull(runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull())
        }
    }.flatten()

    if (exportedUris.isEmpty()) return null
    val clipData = ClipData.newUri(context.contentResolver, "NexusTS RDP Files", exportedUris.first())
    for (idx in 1 until exportedUris.size) {
        clipData.addItem(ClipData.Item(exportedUris[idx]))
    }
    val applied = runCatching {
        manager.setPrimaryClip(clipData)
        true
    }.getOrDefault(false)
    return if (applied) exportedUris.joinToString("\n") { it.toString() } else null
}

private fun readHostPlainClipboard(context: Context, manager: ClipboardManager?): String {
    val clip = runCatching { manager?.primaryClip }.getOrNull() ?: return ""
    if (clip.itemCount <= 0) return ""
    return runCatching {
        clip.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }.getOrDefault("")
}

/**
 * Hardware-accelerated FreeRDP 3.x Jetpack Compose `TextureView` Renderer.
 *
 * - Renders inside Compose's standard hardware View layer via `TextureView` so multi-tab switching in
 *   clipped Compose panes AND standard Android / Googlebook OS screenshots capture pixels reliably.
 * - Places the RDP telemetry & control toolbar strictly OUTSIDE (above) the RDP desktop viewport so it
 *   never covers remote windows or intercepts mouse clicks.
 * - Performs automatic bidirectional clipboard sync (text & files) via FreeRDP `CLIPRDR` whenever enabled
 *   in connection properties (`session.settings.redirectClipboard`).
 */
@Composable
fun RdpSessionSurfaceView(
    session: RdpManagedSession,
    isFocused: Boolean,
    ctrlAltDelLabel: String,
    smartcardActiveLabel: String,
    dynamicResizeLabel: String,
    clipboardSyncLabel: String = "Clipboard",
    consoleBadgeLabel: String = "/admin",
    externalZoomFactor: Float = 1.0f,
    onZoomFactorChanged: (Float) -> Unit = {},
    onEditConnection: (() -> Unit)? = null,
    isFullScreen: Boolean = false,
    onToggleFullScreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val composeClipboardManager = LocalClipboardManager.current
    val systemClipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    val hasMicPermission = remember(context, isFocused) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    val initialScale = remember(session, density) {
        when {
            !session.settings.hiDpiEnabled -> 100
            session.settings.desktopScaleFactorPercent > 100 -> session.settings.desktopScaleFactorPercent
            density >= 1.5f -> 200
            density >= 1.25f -> 125
            else -> 100
        }
    }

    var viewportSize by remember(session) {
        mutableStateOf(
            IntSize(session.settings.customDesktopWidth, session.settings.customDesktopHeight)
        )
    }
    var hasMeasuredViewport by remember(session) { mutableStateOf(false) }
    var activeScalePercent by remember(session) { mutableIntStateOf(initialScale) }
    var zoomScale by remember(externalZoomFactor) { mutableFloatStateOf(externalZoomFactor) }
    var panOffsetX by remember(session) { mutableFloatStateOf(0f) }
    var panOffsetY by remember(session) { mutableFloatStateOf(0f) }
    var showKeyboardBar by remember(session) { mutableStateOf(false) }
    var softKeyboardText by remember(session) { mutableStateOf("") }
    var driveSyncFeedback by remember(session) { mutableStateOf<String?>(null) }
    var showErrorDetails by remember(session) { mutableStateOf(false) }

    // Automated Bidirectional Clipboard Sync loop-prevention trackers
    var lastSyncedClipText by remember(session) { mutableStateOf<String?>(null) }
    var lastSyncedClipFilesSig by remember(session) { mutableStateOf<String?>(null) }
    var lastObservedRemoteClipSeq by remember(session) { mutableLongStateOf(0L) }

    val isConnecting by session.isConnecting.collectAsState()
    val isConnected by session.isConnected.collectAsState()
    val isReconnecting by session.isReconnecting.collectAsState()
    val reconnectAttempt by session.reconnectAttempt.collectAsState()
    val lastError by session.lastError.collectAsState()
    val remoteDesktopDimensions by session.remoteDesktopDimensions.collectAsState()
    val liveResizeBehavior by session.liveResizeBehavior.collectAsState()
    val sharedDriveUncLabel by session.sharedDriveUncLabel.collectAsState()
    var lastRemoteCursor by remember(remoteDesktopDimensions) {
        mutableStateOf((remoteDesktopDimensions.first / 2) to (remoteDesktopDimensions.second / 2))
    }

    val latestViewportSize by rememberUpdatedState(viewportSize)
    val latestRemoteDimensions by rememberUpdatedState(remoteDesktopDimensions)
    val latestZoomScale by rememberUpdatedState(zoomScale)
    val latestPanOffsetX by rememberUpdatedState(panOffsetX)
    val latestPanOffsetY by rememberUpdatedState(panOffsetY)

    val syncHostClipboardToRemote: () -> Unit = remember(session, context, systemClipboardManager) {
        {
            if (session.settings.redirectClipboard && session.isConnected.value) {
                val hostUris = readHostClipboardUris(systemClipboardManager)
                if (hostUris.isNotEmpty()) {
                    val uriSig = hostUris.joinToString("\n") { it.toString() }
                    if (uriSig != lastSyncedClipFilesSig) {
                        lastSyncedClipFilesSig = uriSig
                        lastSyncedClipText = null
                        coroutineScope.launch(Dispatchers.IO) {
                            val staged = stageHostClipboardUrisToLocalFiles(context, hostUris)
                            if (staged.isNotEmpty()) {
                                session.syncClipboardFilesToRemote(staged)
                            }
                        }
                    }
                } else {
                    val hostText = readHostPlainClipboard(context, systemClipboardManager)
                    if (hostText.isNotEmpty() && hostText != lastSyncedClipText) {
                        lastSyncedClipText = hostText
                        lastSyncedClipFilesSig = null
                        coroutineScope.launch(Dispatchers.IO) {
                            session.syncClipboardToRemote(hostText)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(isFocused, isConnected) {
        if (isFocused) {
            if (!showKeyboardBar) {
                runCatching { focusRequester.requestFocus() }
            }
            if (isConnected && session.settings.redirectClipboard) {
                syncHostClipboardToRemote()
            }
        } else if (isConnected) {
            withContext(Dispatchers.IO) {
                session.releaseAllKeys()
            }
        }
    }

    // Listen for local Android / Googlebook OS clipboard changes and announce immediately over CLIPRDR
    DisposableEffect(session, isConnected, systemClipboardManager) {
        if (!session.settings.redirectClipboard || !isConnected || systemClipboardManager == null) {
            onDispose { }
        } else {
            syncHostClipboardToRemote()
            val listener = ClipboardManager.OnPrimaryClipChangedListener {
                syncHostClipboardToRemote()
            }
            runCatching { systemClipboardManager.addPrimaryClipChangedListener(listener) }
            onDispose {
                runCatching { systemClipboardManager.removePrimaryClipChangedListener(listener) }
            }
        }
    }

    // Connect asynchronously on Dispatchers.IO after initial pane measurement (48ms fast fallback for headless tests)
    LaunchedEffect(session) {
        if (liveResizeBehavior == RdpResizeBehavior.DYNAMIC_DISP_DVC && !hasMeasuredViewport) {
            withTimeoutOrNull(48L) {
                snapshotFlow { hasMeasuredViewport }.first { it }
            }
        }
        withContext(Dispatchers.IO) {
            session.start(
                initialWidthPx = latestViewportSize.width,
                initialHeightPx = latestViewportSize.height,
                effectiveScalePercent = activeScalePercent,
                context = context,
                micPermissionGranted = hasMicPermission
            )
        }
    }

    // Health monitor + server-confirmed GDI dimension sync + automatic remote-to-local CLIPRDR pull
    LaunchedEffect(session, isConnected, isReconnecting) {
        var tick = 0
        while (isConnected || isReconnecting) {
            delay(if (isReconnecting) 250L else 380L)
            tick++
            var pulledKind: RemoteClipboardKind? = null
            var pulledText: String? = null
            var pulledFiles: List<String> = emptyList()
            withContext(Dispatchers.IO) {
                if (isReconnecting || tick % 2 == 0) {
                    session.pollSessionHealth()
                } else {
                    session.syncServerDesktopDimensions()
                }
                if (session.settings.redirectClipboard && !isReconnecting) {
                    val seq = session.remoteClipboardSequence()
                    if (seq > lastObservedRemoteClipSeq) {
                        lastObservedRemoteClipSeq = seq
                        val kind = session.remoteClipboardKind()
                        pulledKind = kind
                        when (kind) {
                            RemoteClipboardKind.FILES -> pulledFiles = session.pullRemoteClipboardFiles()
                            RemoteClipboardKind.TEXT -> pulledText = session.pullRemoteClipboardText()
                            RemoteClipboardKind.NONE -> Unit
                        }
                    }
                }
            }
            when (pulledKind) {
                RemoteClipboardKind.FILES -> {
                    if (pulledFiles.isNotEmpty()) {
                        val publishedSig = publishRemoteFilesToHostClipboard(
                            context = context,
                            manager = systemClipboardManager,
                            localPaths = pulledFiles
                        )
                        if (publishedSig != null) {
                            lastSyncedClipFilesSig = publishedSig
                            lastSyncedClipText = null
                        }
                    }
                }
                RemoteClipboardKind.TEXT -> {
                    if (!pulledText.isNullOrEmpty() && pulledText != lastSyncedClipText) {
                        lastSyncedClipText = pulledText
                        lastSyncedClipFilesSig = null
                        runCatching {
                            systemClipboardManager?.setPrimaryClip(
                                ClipData.newPlainText("NexusTS RDP", pulledText)
                            )
                        }
                    }
                }
                RemoteClipboardKind.NONE -> {
                    // Remote clipboard took ownership with a non-pulled format or fresh sequence:
                    // clear local mirror caches so re-copying identical local data later re-announces CB_FORMAT_LIST.
                    lastSyncedClipText = null
                    lastSyncedClipFilesSig = null
                }
                null -> Unit
            }
        }
    }

    // Debounce window drag resizing (220ms) and HiDPI scale changes so DISP_DVC never floods Windows mid-drag
    LaunchedEffect(session, viewportSize, activeScalePercent, liveResizeBehavior, isConnected) {
        if (isConnected &&
            hasMeasuredViewport &&
            liveResizeBehavior == RdpResizeBehavior.DYNAMIC_DISP_DVC &&
            viewportSize.width >= 320 &&
            viewportSize.height >= 240
        ) {
            delay(220L)
            withContext(Dispatchers.IO) {
                session.onViewportResized(
                    widthPx = viewportSize.width,
                    heightPx = viewportSize.height,
                    scalePercent = activeScalePercent
                )
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexusTheme.extendedColors.terminalCanvasBackground)
    ) {
        // External RDP Session Telemetry & Action Bar (completely outside the RDP connection viewport)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.DesktopWindows,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = NexusTheme.extendedColors.rdpAccent
                )
                val codecTag = when {
                    !session.settings.enableRemoteFx -> "GDI"
                    session.settings.gfxCodecProfile == RdpGfxCodecProfile.ADAPTIVE_PROGRESSIVE -> "GFX"
                    session.settings.gfxCodecProfile == RdpGfxCodecProfile.AVC420_VIDEO -> "AVC420"
                    else -> "AVC444"
                }
                val credUser = when {
                    session.secret.domain.isNotBlank() && session.secret.username.isNotBlank() ->
                        "${session.secret.domain}\\${session.secret.username}"
                    session.secret.username.isNotBlank() -> session.secret.username
                    else -> session.connection.host
                }
                Text(
                    text = "${session.connection.name} • ${remoteDesktopDimensions.first}×${remoteDesktopDimensions.second} • ${session.settings.colorDepthBits}b • $codecTag • $credUser",
                    style = NexusTheme.monospace.keycapShortcut,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (session.settings.connectToConsoleAdmin) {
                    Icon(
                        imageVector = Icons.Outlined.AdminPanelSettings,
                        contentDescription = consoleBadgeLabel,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = consoleBadgeLabel,
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                when (session.settings.audioRedirectionMode) {
                    RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE -> Icon(
                        imageVector = Icons.Outlined.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST -> Icon(
                        imageVector = Icons.Outlined.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    RdpAudioRedirectionMode.DO_NOT_PLAY -> Icon(
                        imageVector = Icons.Outlined.VolumeOff,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }

                if (session.settings.redirectMicrophone) {
                    Icon(
                        imageVector = if (hasMicPermission) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = if (hasMicPermission) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
                    )
                }

                if (session.settings.redirectClipboard) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = clipboardSyncLabel,
                            modifier = Modifier.size(14.dp),
                            tint = NexusTheme.extendedColors.sshAccent
                        )
                        Text(
                            text = clipboardSyncLabel,
                            style = NexusTheme.monospace.keycapShortcut,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (sharedDriveUncLabel != null ||
                    session.settings.redirectDrives ||
                    session.settings.redirectedDriveSafUris.isNotEmpty()
                ) {
                    val chipText = driveSyncFeedback ?: (sharedDriveUncLabel ?: "Shared Drive")
                    NexusActionChip(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                val report = session.syncSharedDrivesNow(context, pullInboundFromSaf = true)
                                val msg = if (report != null) {
                                    "Synced ↑${report.uploadedToSafCount} ↓${report.downloadedFromSafCount}"
                                } else {
                                    "Drive Ready"
                                }
                                withContext(Dispatchers.Main) {
                                    driveSyncFeedback = msg
                                }
                                delay(2200L)
                                withContext(Dispatchers.Main) {
                                    driveSyncFeedback = null
                                }
                            }
                        },
                        label = chipText,
                        leadingIcon = Icons.Outlined.FolderShared
                    )
                }

                if (session.settings.redirectSmartcardYubiKey) {
                    Icon(
                        imageVector = Icons.Outlined.Usb,
                        contentDescription = smartcardActiveLabel,
                        modifier = Modifier.size(15.dp),
                        tint = NexusTheme.extendedColors.sshAccent
                    )
                }

                val resizeChipText = when (liveResizeBehavior) {
                    RdpResizeBehavior.DYNAMIC_DISP_DVC ->
                        if (dynamicResizeLabel.isNotBlank()) dynamicResizeLabel else "Auto-Fit DISP"
                    RdpResizeBehavior.SMART_SCALE_VIEWPORT -> "Smart Scale"
                    RdpResizeBehavior.FIXED_SERVER_RESOLUTION ->
                        "Fixed ${session.settings.customDesktopWidth}×${session.settings.customDesktopHeight}"
                }
                NexusActionChip(
                    onClick = {
                        val nextMode = when (liveResizeBehavior) {
                            RdpResizeBehavior.DYNAMIC_DISP_DVC -> RdpResizeBehavior.SMART_SCALE_VIEWPORT
                            RdpResizeBehavior.SMART_SCALE_VIEWPORT -> RdpResizeBehavior.FIXED_SERVER_RESOLUTION
                            RdpResizeBehavior.FIXED_SERVER_RESOLUTION -> RdpResizeBehavior.DYNAMIC_DISP_DVC
                        }
                        zoomScale = 1.0f
                        panOffsetX = 0f
                        panOffsetY = 0f
                        onZoomFactorChanged(1.0f)
                        coroutineScope.launch(Dispatchers.IO) {
                            session.setLiveResizeBehavior(
                                mode = nextMode,
                                viewportWidthPx = latestViewportSize.width,
                                viewportHeightPx = latestViewportSize.height,
                                scalePercent = activeScalePercent
                            )
                        }
                    },
                    label = resizeChipText,
                    leadingIcon = Icons.Outlined.AspectRatio
                )

                if (session.settings.hiDpiEnabled) {
                    NexusActionChip(
                        onClick = {
                            val nextScale = when (activeScalePercent) {
                                100 -> 125
                                125 -> 150
                                150 -> 175
                                175 -> 200
                                else -> 100
                            }
                            activeScalePercent = nextScale
                            coroutineScope.launch(Dispatchers.IO) {
                                session.onViewportResized(
                                    widthPx = latestViewportSize.width,
                                    heightPx = latestViewportSize.height,
                                    scalePercent = nextScale,
                                    forceDynamic = true
                                )
                            }
                        },
                        label = "HiDPI ${activeScalePercent}%",
                        leadingIcon = Icons.Outlined.AspectRatio
                    )
                }

                NexusActionChip(
                    onClick = { showKeyboardBar = !showKeyboardBar },
                    label = "⌨ Keys",
                    leadingIcon = Icons.Outlined.Keyboard
                )

                NexusActionChip(
                    onClick = { session.sendCtrlAltDel() },
                    label = ctrlAltDelLabel,
                    leadingIcon = Icons.Outlined.Keyboard
                )

                if (onToggleFullScreen != null) {
                    NexusActionChip(
                        onClick = onToggleFullScreen,
                        label = if (isFullScreen) "Exit Full Screen" else "Full Screen",
                        leadingIcon = if (isFullScreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

        // Optional Soft-Key Input Strip (also outside the RDP viewport so it never obscures remote windows)
        if (showKeyboardBar) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(onClick = { session.sendKeyTap(0x01, false) }) {
                            Text("Esc", style = MaterialTheme.typography.labelSmall)
                        }
                        FilledTonalButton(onClick = { session.sendKeyTap(0x0F, false) }) {
                            Text("Tab", style = MaterialTheme.typography.labelSmall)
                        }
                        FilledTonalButton(onClick = { session.sendKeyTap(0x5B, true) }) {
                            Text("Win", style = MaterialTheme.typography.labelSmall)
                        }
                        FilledTonalButton(onClick = { session.sendCtrlAltDel() }) {
                            Text(ctrlAltDelLabel, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = softKeyboardText,
                            onValueChange = { softKeyboardText = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Type text to send to remote desktop…") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    val toSend = softKeyboardText
                                    if (toSend.isNotEmpty()) {
                                        session.sendTextInput(toSend)
                                        softKeyboardText = ""
                                    }
                                    session.sendKeyTap(0x1C, false)
                                }
                            )
                        )
                        IconButton(
                            onClick = {
                                val toSend = softKeyboardText
                                if (toSend.isNotEmpty()) {
                                    session.sendTextInput(toSend)
                                    softKeyboardText = ""
                                } else {
                                    session.sendKeyTap(0x1C, false)
                                }
                            }
                        ) {
                            Icon(Icons.Outlined.Send, contentDescription = "Send Keys")
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        }

        // Unobstructed RDP Remote Desktop Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .background(NexusTheme.extendedColors.terminalCanvasBackground)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (showKeyboardBar) return@onKeyEvent false
                    val nativeEv = keyEvent.nativeKeyEvent
                    if (isSystemPassThroughKeyEvent(nativeEv)) return@onKeyEvent false
                    when (nativeEv.action) {
                        KeyEvent.ACTION_DOWN -> session.sendAndroidKeyEvent(
                            keyCode = nativeEv.keyCode,
                            scanCode = nativeEv.scanCode,
                            isKeyDown = true,
                            ctrlPressed = nativeEv.isCtrlPressed,
                            altPressed = nativeEv.isAltPressed,
                            metaPressed = nativeEv.isMetaPressed
                        )
                        KeyEvent.ACTION_UP -> session.sendAndroidKeyEvent(
                            keyCode = nativeEv.keyCode,
                            scanCode = nativeEv.scanCode,
                            isKeyDown = false,
                            ctrlPressed = nativeEv.isCtrlPressed,
                            altPressed = nativeEv.isAltPressed,
                            metaPressed = nativeEv.isMetaPressed
                        )
                        else -> false
                    }
                }
                .onSizeChanged { newSize ->
                    viewportSize = newSize
                    if (newSize.width >= 320 && newSize.height >= 240) {
                        hasMeasuredViewport = true
                    }
                }
                .nexusTrackpadCanvasGesture { _, pan, zoom ->
                    val nextZoom = (zoomScale * zoom).coerceIn(0.75f, 3.0f)
                    zoomScale = nextZoom
                    onZoomFactorChanged(nextZoom)
                    if (zoomScale > 1.01f) {
                        panOffsetX += pan.x
                        panOffsetY += pan.y
                    } else {
                        panOffsetX = 0f
                        panOffsetY = 0f
                    }
                }
                .nexusMouseWheelHandler(
                    onScroll = { dx, dy, _ ->
                        val (wx, wy) = lastRemoteCursor
                        if (dy != 0f) {
                            val wheelSteps = (kotlin.math.abs(dy) * 120f).toInt().coerceIn(15, 360)
                            val wheelFlags = FreeRdpNativeBridge.encodeMouseWheelFlags(
                                steps = wheelSteps,
                                negative = dy > 0f
                            )
                            session.sendMouse(wheelFlags, wx, wy)
                        }
                        if (dx != 0f) {
                            val hWheelSteps = (kotlin.math.abs(dx) * 120f).toInt().coerceIn(15, 360)
                            val hWheelFlags = FreeRdpNativeBridge.encodeHorizontalMouseWheelFlags(
                                steps = hWheelSteps,
                                negative = dx < 0f
                            )
                            session.sendMouse(hWheelFlags, wx, wy)
                        }
                    },
                    onZoom = { delta, _ ->
                        val nextZoom = (zoomScale + delta).coerceIn(0.75f, 3.0f)
                        zoomScale = nextZoom
                        onZoomFactorChanged(nextZoom)
                    }
                )
                .pointerInput(session) {
                    awaitPointerEventScope {
                        var lastPressedButtonFlag = FreeRdpNativeBridge.PTRFLAGS_BUTTON1_LEFT
                        var lastExtendedButtonFlag = 0
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull() ?: continue
                            val (x, y) = transformViewportToRemoteDesktop(
                                rawX = change.position.x,
                                rawY = change.position.y,
                                viewportSize = latestViewportSize,
                                remoteDimensions = latestRemoteDimensions,
                                zoomScale = latestZoomScale,
                                panOffsetX = latestPanOffsetX,
                                panOffsetY = latestPanOffsetY
                            )
                            lastRemoteCursor = x to y
                            when (ev.type) {
                                PointerEventType.Move -> {
                                    session.sendMouse(FreeRdpNativeBridge.PTRFLAGS_MOVE, x, y)
                                }
                                PointerEventType.Press -> {
                                    if (!showKeyboardBar) {
                                        runCatching { focusRequester.requestFocus() }
                                    }
                                    when {
                                        ev.buttons.isBackPressed -> {
                                            lastExtendedButtonFlag = FreeRdpNativeBridge.PTRXFLAGS_BUTTON1_BACK
                                            session.sendExtendedMouse(
                                                FreeRdpNativeBridge.PTRXFLAGS_DOWN or FreeRdpNativeBridge.PTRXFLAGS_BUTTON1_BACK,
                                                x,
                                                y
                                            )
                                        }
                                        ev.buttons.isForwardPressed -> {
                                            lastExtendedButtonFlag = FreeRdpNativeBridge.PTRXFLAGS_BUTTON2_FORWARD
                                            session.sendExtendedMouse(
                                                FreeRdpNativeBridge.PTRXFLAGS_DOWN or FreeRdpNativeBridge.PTRXFLAGS_BUTTON2_FORWARD,
                                                x,
                                                y
                                            )
                                        }
                                        else -> {
                                            lastExtendedButtonFlag = 0
                                            val btn = when {
                                                ev.buttons.isSecondaryPressed -> FreeRdpNativeBridge.PTRFLAGS_BUTTON2_RIGHT
                                                ev.buttons.isTertiaryPressed -> FreeRdpNativeBridge.PTRFLAGS_BUTTON3_MIDDLE
                                                else -> FreeRdpNativeBridge.PTRFLAGS_BUTTON1_LEFT
                                            }
                                            lastPressedButtonFlag = btn
                                            session.sendMouse(FreeRdpNativeBridge.PTRFLAGS_DOWN or btn, x, y)
                                        }
                                    }
                                }
                                PointerEventType.Release -> {
                                    if (lastExtendedButtonFlag != 0) {
                                        session.sendExtendedMouse(lastExtendedButtonFlag, x, y)
                                        lastExtendedButtonFlag = 0
                                    } else {
                                        session.sendMouse(lastPressedButtonFlag, x, y)
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            key(session) {
                val activeBoundSurface = remember(session) { mutableStateOf<Surface?>(null) }

                DisposableEffect(session) {
                    onDispose {
                        val old = activeBoundSurface.value
                        activeBoundSurface.value = null
                        session.unbindSurface(old)
                        runCatching { old?.release() }
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            isOpaque = true
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    surfaceTexture: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    activeBoundSurface.value?.let { prev ->
                                        session.unbindSurface(prev)
                                        runCatching { prev.release() }
                                    }
                                    val freshSurface = Surface(surfaceTexture)
                                    activeBoundSurface.value = freshSurface
                                    session.bindSurface(freshSurface)
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surfaceTexture: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    val current = activeBoundSurface.value
                                    if (current != null && current.isValid) {
                                        session.bindSurface(current)
                                    }
                                }

                                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                    val old = activeBoundSurface.value
                                    activeBoundSurface.value = null
                                    session.unbindSurface(old)
                                    runCatching { old?.release() }
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                            }

                            if (isAvailable && surfaceTexture != null && activeBoundSurface.value == null) {
                                val freshSurface = Surface(surfaceTexture)
                                activeBoundSurface.value = freshSurface
                                session.bindSurface(freshSurface)
                            }
                        }
                    },
                    update = { view ->
                        if (view.isAvailable && view.surfaceTexture != null) {
                            val current = activeBoundSurface.value
                            if (current == null || !current.isValid) {
                                runCatching { current?.release() }
                                val freshSurface = Surface(view.surfaceTexture)
                                activeBoundSurface.value = freshSurface
                                session.bindSurface(freshSurface)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoomScale
                            scaleY = zoomScale
                            translationX = panOffsetX
                            translationY = panOffsetY
                        }
                )
            }

            if (isConnecting) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                    tonalElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = NexusTheme.extendedColors.rdpAccent
                        )
                        Column {
                            Text(
                                text = "Connecting…",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${session.connection.host}:${session.settings.port}",
                                style = NexusTheme.monospace.keycapShortcut,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (isReconnecting) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                    tonalElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = NexusTheme.extendedColors.rdpAccent
                        )
                        Text(
                            text = "Reconnecting (attempt ${maxOf(1, reconnectAttempt)}/8)…",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FilledTonalButton(
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    session.reconnect(
                                        initialWidthPx = latestViewportSize.width,
                                        initialHeightPx = latestViewportSize.height,
                                        effectiveScalePercent = activeScalePercent,
                                        context = context
                                    )
                                }
                            }
                        ) {
                            Text("Reconnect Now")
                        }
                    }
                }
            } else if (!isConnected && !isReconnecting && lastError != null) {
                val err = lastError!!
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = err.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = err.userMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (err.remediationHint.isNotBlank()) {
                            Text(
                                text = err.remediationHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (showErrorDetails && err.technicalDetails.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest
                            ) {
                                Text(
                                    text = err.technicalDetails,
                                    modifier = Modifier.padding(10.dp),
                                    style = NexusTheme.monospace.keycapShortcut,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        session.reconnect(
                                            initialWidthPx = latestViewportSize.width,
                                            initialHeightPx = latestViewportSize.height,
                                            effectiveScalePercent = activeScalePercent,
                                            context = context
                                        )
                                    }
                                }
                            ) {
                                Text("Reconnect")
                            }
                            if (onEditConnection != null) {
                                FilledTonalButton(onClick = onEditConnection) {
                                    Text("Edit Connection…")
                                }
                            }
                            if (err.technicalDetails.isNotBlank()) {
                                FilledTonalButton(onClick = { showErrorDetails = !showErrorDetails }) {
                                    Text(if (showErrorDetails) "Hide Details" else "Details")
                                }
                                FilledTonalButton(
                                    onClick = {
                                        composeClipboardManager.setText(AnnotatedString(err.formatDiagnosticReport()))
                                    }
                                ) {
                                    Text("Copy Diagnostics")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
