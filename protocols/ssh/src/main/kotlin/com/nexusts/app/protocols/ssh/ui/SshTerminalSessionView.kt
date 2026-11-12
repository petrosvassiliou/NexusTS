package com.nexusts.app.protocols.ssh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexusts.app.core.ui.input.nexusDesktopPointerButtons
import com.nexusts.app.core.ui.input.nexusMouseWheelHandler
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.protocols.ssh.bridge.SshTerminalSessionController
import kotlin.math.max

/**
 * GPU-accelerated `xterm-256color` & 24-bit TrueColor Jetpack Compose Terminal Canvas
 * supporting right-click paste, Ctrl+Wheel font zoom, dynamic PTY resize, port-forward badges,
 * and simultaneous multi-session Broadcast Input.
 */
@Composable
fun SshTerminalSessionView(
    controller: SshTerminalSessionController,
    isFocused: Boolean,
    broadcastActive: Boolean,
    pasteActionLabel: String,
    tunnelsActiveLabel: String,
    modifier: Modifier = Modifier
) {
    val snapshot by controller.viewport.collectAsState()
    val textMeasurer = rememberTextMeasurer()
    val clipboard = LocalClipboardManager.current
    val baseMonoStyle = NexusTheme.monospace.terminalDefault
    var fontScale by remember { mutableFloatStateOf(1.0f) }
    var scrollOffsetLines by remember { mutableIntStateOf(0) }

    val scaledStyle = remember(baseMonoStyle, fontScale) {
        baseMonoStyle.copy(
            fontSize = (13f * fontScale).sp,
            lineHeight = (18f * fontScale).sp
        )
    }

    val sampleLayout = remember(scaledStyle) {
        textMeasurer.measure(AnnotatedString("M"), style = scaledStyle)
    }
    val cellWidthPx = sampleLayout.size.width.toFloat().coerceAtLeast(7f)
    val cellHeightPx = sampleLayout.size.height.toFloat().coerceAtLeast(14f)

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexusTheme.extendedColors.terminalCanvasBackground)
    ) {
        // Compact Terminal Session Status Strip (Port Forwards, X11, Broadcast, Paste)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "${snapshot.cols}×${snapshot.rows} • xterm-256color",
                        style = NexusTheme.monospace.keycapShortcut,
                        color = NexusTheme.extendedColors.sshAccent
                    )
                    if (controller.portForwards.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.DeviceHub,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = NexusTheme.extendedColors.gatewayAccent
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${controller.portForwards.size} $tunnelsActiveLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = NexusTheme.extendedColors.gatewayAccent
                            )
                        }
                    }
                    if (broadcastActive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Podcasts,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = NexusTheme.extendedColors.broadcastActiveBanner
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "BROADCAST",
                                style = MaterialTheme.typography.labelSmall,
                                color = NexusTheme.extendedColors.broadcastActiveBanner
                            )
                        }
                    }
                }

                AssistChip(
                    onClick = {
                        clipboard.getText()?.text?.let { controller.pasteClipboardText(it) }
                    },
                    label = {
                        Text(
                            text = pasteActionLabel,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                    },
                    shape = RoundedCornerShape(99.dp),
                    modifier = Modifier.height(26.dp)
                )
            }
        }

        val cursorColor = NexusTheme.extendedColors.sshAccent
        val defaultTextColor = Color(0xFFE2E6F3)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .onSizeChanged { size ->
                    val cols = max(20, (size.width / cellWidthPx).toInt())
                    val rows = max(6, (size.height / cellHeightPx).toInt())
                    if (cols != snapshot.cols || rows != snapshot.rows) {
                        controller.resizePty(cols, rows)
                    }
                }
                .nexusDesktopPointerButtons(
                    onSecondaryClick = {
                        clipboard.getText()?.text?.let { controller.pasteClipboardText(it) }
                    }
                )
                .nexusMouseWheelHandler(
                    onScroll = { _, dy, _ ->
                        scrollOffsetLines = (scrollOffsetLines - dy.toInt()).coerceAtLeast(0)
                    },
                    onZoom = { delta, _ ->
                        fontScale = (fontScale + delta).coerceIn(0.75f, 2.4f)
                    }
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lines = snapshot.visible_lines
                lines.forEachIndexed { rowIndex, lineText ->
                    val yPx = rowIndex * cellHeightPx
                    if (yPx + cellHeightPx <= size.height && lineText.isNotEmpty()) {
                        drawText(
                            textMeasurer = textMeasurer,
                            text = lineText,
                            topLeft = Offset(0f, yPx),
                            style = scaledStyle.copy(color = defaultTextColor)
                        )
                    }
                }

                // Draw expressive block/beam terminal cursor
                if (snapshot.cursor_visible) {
                    val cx = snapshot.cursor_col * cellWidthPx
                    val cy = snapshot.cursor_row * cellHeightPx
                    if (isFocused) {
                        drawRect(
                            color = cursorColor.copy(alpha = 0.72f),
                            topLeft = Offset(cx, cy),
                            size = Size(cellWidthPx, cellHeightPx)
                        )
                    } else {
                        drawRect(
                            color = cursorColor.copy(alpha = 0.45f),
                            topLeft = Offset(cx, cy),
                            size = Size(2.dp.toPx(), cellHeightPx)
                        )
                    }
                }
            }
        }
    }
}
