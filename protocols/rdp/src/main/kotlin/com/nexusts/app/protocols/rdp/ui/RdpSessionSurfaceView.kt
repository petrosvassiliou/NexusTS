package com.nexusts.app.protocols.rdp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import com.nexusts.app.core.ui.components.NexusActionChip
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
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
import androidx.core.content.ContextCompat
import com.nexusts.app.core.ui.input.nexusMouseWheelHandler
import com.nexusts.app.core.ui.input.nexusTrackpadCanvasGesture
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.vault.model.RdpAudioRedirectionMode
import com.nexusts.app.core.vault.model.RdpResizeBehavior
import com.nexusts.app.protocols.rdp.bridge.FreeRdpNativeBridge
import com.nexusts.app.protocols.rdp.bridge.RdpManagedSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/**
 * Hardware-accelerated FreeRDP 3.x Jetpack Compose Surface Renderer.
 * Automatically triggers debounced `DISP_DVC` dynamic resolution & HiDPI scale updates when the user
 * drags Googlebook OS freeform window edges, split-pane dividers, or cycles HiDPI scale!
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val clipboardManager = LocalClipboardManager.current
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
            density >= 2.25f -> 200
            density >= 1.6f -> 150
            density >= 1.25f -> 125
            else -> 100
        }
    }

    var viewportSize by remember {
        mutableStateOf(
            IntSize(session.settings.customDesktopWidth, session.settings.customDesktopHeight)
        )
    }
    var activeScalePercent by remember(session) { mutableIntStateOf(initialScale) }
    var zoomScale by remember(externalZoomFactor) { mutableFloatStateOf(externalZoomFactor) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }
    var showKeyboardBar by remember { mutableStateOf(false) }
    var softKeyboardText by remember { mutableStateOf("") }
    var driveSyncFeedback by remember { mutableStateOf<String?>(null) }
    var showErrorDetails by remember(session) { mutableStateOf(false) }

    val isConnecting by session.isConnecting.collectAsState()
    val isConnected by session.isConnected.collectAsState()
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

    LaunchedEffect(isFocused) {
        if (isFocused && !showKeyboardBar) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    // Connect asynchronously on Dispatchers.IO so slow network / NLA handshakes never block the Main thread
    LaunchedEffect(session) {
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

    LaunchedEffect(session, isConnected) {
        while (isConnected) {
            delay(1500L)
            withContext(Dispatchers.IO) {
                session.pollSessionHealth()
            }
        }
    }

    // Debounce window drag resizing (120ms) and HiDPI scale changes so DISP_DVC cleanly renegotiates H.264 surface geometry
    LaunchedEffect(viewportSize, liveResizeBehavior) {
        if (liveResizeBehavior == RdpResizeBehavior.DYNAMIC_DISP_DVC &&
            viewportSize.width >= 320 &&
            viewportSize.height >= 240
        ) {
            delay(120L)
            withContext(Dispatchers.IO) {
                session.onViewportResized(
                    widthPx = viewportSize.width,
                    heightPx = viewportSize.height,
                    scalePercent = activeScalePercent
                )
            }
        }
    }

    DisposableEffect(session) {
        onDispose {
            // Detach render surface when switching tabs/views without tearing down the live RDP session.
            session.bindSurface(null)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NexusTheme.extendedColors.terminalCanvasBackground)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (showKeyboardBar) return@onKeyEvent false
                val nativeEv = keyEvent.nativeKeyEvent
                when (nativeEv.action) {
                    KeyEvent.ACTION_DOWN -> session.sendAndroidKeyEvent(
                        keyCode = nativeEv.keyCode,
                        scanCode = nativeEv.scanCode,
                        isKeyDown = true
                    )
                    KeyEvent.ACTION_UP -> session.sendAndroidKeyEvent(
                        keyCode = nativeEv.keyCode,
                        scanCode = nativeEv.scanCode,
                        isKeyDown = false
                    )
                    else -> false
                }
            }
            .onSizeChanged { newSize -> viewportSize = newSize }
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
                onScroll = { _, dy, _ ->
                    if (dy != 0f) {
                        val steps = 120
                        val wheelFlags = FreeRdpNativeBridge.encodeMouseWheelFlags(
                            steps = steps,
                            negative = dy > 0f
                        )
                        val (wx, wy) = lastRemoteCursor
                        session.sendMouse(wheelFlags, wx, wy)
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
                                val btn = when {
                                    ev.buttons.isSecondaryPressed -> FreeRdpNativeBridge.PTRFLAGS_BUTTON2_RIGHT
                                    ev.buttons.isTertiaryPressed -> FreeRdpNativeBridge.PTRFLAGS_BUTTON3_MIDDLE
                                    else -> FreeRdpNativeBridge.PTRFLAGS_BUTTON1_LEFT
                                }
                                lastPressedButtonFlag = btn
                                session.sendMouse(FreeRdpNativeBridge.PTRFLAGS_DOWN or btn, x, y)
                            }
                            PointerEventType.Release -> {
                                session.sendMouse(lastPressedButtonFlag, x, y)
                            }
                        }
                    }
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            session.bindSurface(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            session.bindSurface(holder.surface)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            session.bindSurface(null)
                        }
                    })
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
        } else if (!isConnected && lastError != null) {
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
                                    clipboardManager.setText(AnnotatedString(err.formatDiagnosticReport()))
                                }
                            ) {
                                Text("Copy Diagnostics")
                            }
                        }
                    }
                }
            }
        }

        // Floating Expressive RDP Session Telemetry & Classic Controls Strip
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            shape = RoundedCornerShape(99.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f),
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.DesktopWindows,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = NexusTheme.extendedColors.rdpAccent
                )
                val codecTag = if (session.settings.enableH264Avc444) "AVC444" else "RFX"
                val credUser = if (session.secret.domain.isNotBlank()) {
                    "${session.secret.domain}\\${session.secret.username}"
                } else {
                    session.secret.username
                }
                Text(
                    text = "${remoteDesktopDimensions.first}×${remoteDesktopDimensions.second} • ${session.settings.colorDepthBits}b • $codecTag • $credUser",
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

                if (session.settings.redirectClipboard) {
                    NexusActionChip(
                        onClick = {
                            val localText = clipboardManager.getText()?.text.orEmpty()
                            if (localText.isNotEmpty()) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    session.syncClipboardToRemote(localText)
                                }
                            }
                        },
                        label = if (clipboardSyncLabel == "Clipboard") "Push Clipboard" else "Push $clipboardSyncLabel",
                        leadingIcon = Icons.Outlined.ContentPaste
                    )
                    NexusActionChip(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                val remoteText = session.pullRemoteClipboardText()
                                if (remoteText.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        clipboardManager.setText(AnnotatedString(remoteText))
                                    }
                                }
                            }
                        },
                        label = if (clipboardSyncLabel == "Clipboard") "Pull Clipboard" else "Pull $clipboardSyncLabel",
                        leadingIcon = Icons.Outlined.ContentCopy
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
            }
        }

        if (showKeyboardBar) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                tonalElevation = 6.dp
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
        }
    }
}
