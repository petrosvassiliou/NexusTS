package com.nexusts.app.protocols.rdp.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Usb
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nexusts.app.core.ui.input.nexusMouseWheelHandler
import com.nexusts.app.core.ui.input.nexusTrackpadCanvasGesture
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.protocols.rdp.bridge.FreeRdpNativeBridge
import com.nexusts.app.protocols.rdp.bridge.RdpManagedSession
import kotlinx.coroutines.delay

/**
 * Hardware-accelerated FreeRDP 3.x Jetpack Compose Surface Renderer.
 * Automatically triggers debounced `DISP_DVC` dynamic resolution updates when the user drags
 * Googlebook OS freeform window edges or split-pane dividers!
 */
@Composable
fun RdpSessionSurfaceView(
    session: RdpManagedSession,
    isFocused: Boolean,
    ctrlAltDelLabel: String,
    smartcardActiveLabel: String,
    dynamicResizeLabel: String,
    modifier: Modifier = Modifier
) {
    var viewportSize by remember { mutableStateOf(IntSize(1920, 1080)) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    // Debounce window drag resizing (120ms) so DISP_DVC cleanly renegotiates H.264 surface geometry
    LaunchedEffect(viewportSize) {
        if (viewportSize.width >= 320 && viewportSize.height >= 240) {
            delay(120L)
            session.onViewportResized(viewportSize.width, viewportSize.height, scalePercent = 100)
        }
    }

    DisposableEffect(session) {
        session.start(viewportSize.width, viewportSize.height)
        onDispose {
            session.close()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NexusTheme.extendedColors.terminalCanvasBackground)
            .onSizeChanged { newSize -> viewportSize = newSize }
            .nexusTrackpadCanvasGesture { _, pan, zoom ->
                zoomScale = (zoomScale * zoom).coerceIn(0.75f, 3.0f)
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
                    zoomScale = (zoomScale + delta).coerceIn(0.75f, 3.0f)
                }
            )
            .pointerInput(session) {
                awaitPointerEventScope {
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
                                session.sendMouse(FreeRdpNativeBridge.PTRFLAGS_DOWN or btn, x, y)
                            }
                            PointerEventType.Release -> {
                                session.sendMouse(FreeRdpNativeBridge.PTRFLAGS_BUTTON1_LEFT, x, y)
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

        // Floating Expressive RDP Session Telemetry & Action Strip
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            shape = RoundedCornerShape(99.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.DesktopWindows,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = NexusTheme.extendedColors.rdpAccent
                )
                Text(
                    text = "${viewportSize.width and -2}×${viewportSize.height and -2} • AVC444",
                    style = NexusTheme.monospace.keycapShortcut,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (session.settings.redirectSmartcardYubiKey) {
                    Icon(
                        imageVector = Icons.Outlined.Usb,
                        contentDescription = smartcardActiveLabel,
                        modifier = Modifier.size(15.dp),
                        tint = NexusTheme.extendedColors.sshAccent
                    )
                }

                Icon(
                    imageVector = Icons.Outlined.AspectRatio,
                    contentDescription = dynamicResizeLabel,
                    modifier = Modifier.size(15.dp),
                    tint = if (isFocused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
                )

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
