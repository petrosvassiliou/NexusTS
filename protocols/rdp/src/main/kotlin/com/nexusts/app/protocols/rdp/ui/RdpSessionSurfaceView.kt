package com.nexusts.app.protocols.rdp.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nexusts.app.core.ui.input.nexusMouseWheelHandler
import com.nexusts.app.core.ui.input.nexusTrackpadCanvasGesture
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.vault.model.RdpAudioRedirectionMode
import com.nexusts.app.protocols.rdp.bridge.FreeRdpNativeBridge
import com.nexusts.app.protocols.rdp.bridge.RdpManagedSession
import kotlinx.coroutines.delay

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
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val clipboardManager = LocalClipboardManager.current
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

    // Debounce window drag resizing (120ms) and HiDPI scale changes so DISP_DVC cleanly renegotiates H.264 surface geometry
    LaunchedEffect(viewportSize, activeScalePercent) {
        if (viewportSize.width >= 320 && viewportSize.height >= 240) {
            delay(120L)
            session.onViewportResized(viewportSize.width, viewportSize.height, scalePercent = activeScalePercent)
        }
    }

    DisposableEffect(session) {
        session.start(viewportSize.width, viewportSize.height, effectiveScalePercent = activeScalePercent)
        onDispose {
            // Detach render surface when switching tabs/views without tearing down the live RDP session.
            session.bindSurface(null)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NexusTheme.extendedColors.terminalCanvasBackground)
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
                    val flags = FreeRdpNativeBridge.PTRFLAGS_WHEEL or
                        if (dy > 0f) FreeRdpNativeBridge.PTRFLAGS_WHEEL_NEGATIVE else 0
                    session.sendMouse(flags or 0x0078, viewportSize.width / 2, viewportSize.height / 2)
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
                        val x = change.position.x.toInt().coerceAtLeast(0)
                        val y = change.position.y.toInt().coerceAtLeast(0)
                        when (ev.type) {
                            PointerEventType.Move -> {
                                session.sendMouse(FreeRdpNativeBridge.PTRFLAGS_MOVE, x, y)
                            }
                            PointerEventType.Press -> {
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
                    text = "${viewportSize.width and -2}×${viewportSize.height and -2} • ${session.settings.colorDepthBits}b • $codecTag • $credUser",
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
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }

                if (session.settings.redirectDrives || session.settings.redirectedDriveSafUris.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Outlined.FolderShared,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary
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

                if (session.settings.hiDpiEnabled) {
                    AssistChip(
                        onClick = {
                            activeScalePercent = when (activeScalePercent) {
                                100 -> 125
                                125 -> 150
                                150 -> 175
                                175 -> 200
                                else -> 100
                            }
                        },
                        label = {
                            Text(
                                text = "HiDPI ${activeScalePercent}%",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.AspectRatio,
                                contentDescription = dynamicResizeLabel,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }

                if (session.settings.redirectClipboard) {
                    AssistChip(
                        onClick = {
                            val localText = clipboardManager.getText()?.text.orEmpty()
                            if (localText.isNotEmpty()) {
                                session.syncClipboardToRemote(localText)
                            } else {
                                val remoteText = session.pullRemoteClipboardText()
                                if (remoteText.isNotEmpty()) {
                                    clipboardManager.setText(AnnotatedString(remoteText))
                                }
                            }
                        },
                        label = {
                            Text(
                                text = clipboardSyncLabel,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }

                AssistChip(
                    onClick = { session.sendCtrlAltDel() },
                    label = {
                        Text(
                            text = ctrlAltDelLabel,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Keyboard,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }
        }
    }
}
