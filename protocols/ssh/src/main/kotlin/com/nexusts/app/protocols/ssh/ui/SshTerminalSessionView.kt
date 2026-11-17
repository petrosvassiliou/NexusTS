package com.nexusts.app.protocols.ssh.ui

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import com.nexusts.app.core.ui.components.NexusActionChip
import com.nexusts.app.core.ui.components.NexusChoiceChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexusts.app.core.ui.input.nexusDesktopPointerButtons
import com.nexusts.app.core.ui.input.nexusMouseWheelHandler
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.protocols.ssh.bridge.SshNativeBridge
import com.nexusts.app.protocols.ssh.bridge.SshTerminalSessionController
import com.nexusts.app.protocols.ssh.recording.AsciicastEventFrame
import com.nexusts.app.protocols.ssh.recording.AsciicastSessionRecorder
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Builds an `AnnotatedString` for a visible terminal row using `packed_rgba_attrs` when non-zero.
 */
private fun buildColoredTerminalRow(
    lineText: String,
    rowIndexInVisible: Int,
    cols: Int,
    packedAttrs: List<Long>,
    defaultFg: Color
): AnnotatedString {
    val trimmed = lineText.trimEnd(' ')
    if (trimmed.isEmpty()) return AnnotatedString("")
    val rowOffset = rowIndexInVisible * cols
    if (rowIndexInVisible < 0 || cols <= 0 || rowOffset + trimmed.length > packedAttrs.size) {
        return AnnotatedString(trimmed, SpanStyle(color = defaultFg))
    }

    return buildAnnotatedString {
        append(trimmed)
        var runStart = 0
        while (runStart < trimmed.length) {
            val attr = packedAttrs[rowOffset + runStart]
            var runEnd = runStart + 1
            while (runEnd < trimmed.length && packedAttrs[rowOffset + runEnd] == attr) {
                runEnd++
            }
            if (attr != 0L) {
                val bold = (attr and 0x01L) != 0L
                val italic = (attr and 0x02L) != 0L
                val underline = (attr and 0x04L) != 0L
                val inverse = (attr and 0x08L) != 0L
                val rawFg24 = ((attr ushr 32).toInt()) and 0x00FF_FFFF
                val rawBg24 = ((attr ushr 8).toInt()) and 0x00FF_FFFF
                val fgRgb24 = if (inverse) rawBg24 else rawFg24
                val bgRgb24 = if (inverse) rawFg24 else rawBg24
                val fgColor = when {
                    fgRgb24 != 0 -> Color(0xFF000000.toInt() or fgRgb24)
                    inverse -> Color(0xFF0E131F)
                    else -> defaultFg
                }
                val bgColor = when {
                    bgRgb24 != 0 -> Color(0xFF000000.toInt() or bgRgb24)
                    inverse -> defaultFg
                    else -> Color.Unspecified
                }
                addStyle(
                    style = SpanStyle(
                        color = fgColor,
                        background = bgColor,
                        fontWeight = if (bold) FontWeight.Bold else null,
                        fontStyle = if (italic) FontStyle.Italic else null,
                        textDecoration = if (underline) TextDecoration.Underline else null
                    ),
                    start = runStart,
                    end = runEnd
                )
            } else {
                addStyle(
                    style = SpanStyle(color = defaultFg),
                    start = runStart,
                    end = runEnd
                )
            }
            runStart = runEnd
        }
    }
}

/**
 * GPU-accelerated `xterm-256color` & 24-bit TrueColor Jetpack Compose Terminal Canvas
 * supporting hardware keyboard + on-screen Special Key Bar + IME input bar, right-click paste,
 * Ctrl+Wheel font zoom, scrollback history, dynamic PTY resize, port-forward badges,
 * simultaneous multi-session Broadcast Input, and **asciicast v2 (`.cast`) Session Recording & Playback**.
 */
@Composable
fun SshTerminalSessionView(
    controller: SshTerminalSessionController,
    isFocused: Boolean,
    broadcastActive: Boolean,
    pasteActionLabel: String,
    tunnelsActiveLabel: String,
    externalZoomFactor: Float = 1.0f,
    onZoomFactorChanged: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snapshot by controller.viewport.collectAsState()
    val activeBoundTunnels by controller.activeBoundPortForwardsCount.collectAsState()
    val isRecording by controller.asciicastRecorder.isRecording.collectAsState()
    val recordedFrames by controller.asciicastRecorder.frameCount.collectAsState()
    val lastCastPayload by controller.asciicastRecorder.lastExportedCast.collectAsState()
    var replayModalVisible by remember { mutableStateOf(false) }

    val canvasFocusRequester = remember { FocusRequester() }
    var stickyCtrl by remember { mutableStateOf(false) }
    var stickyAlt by remember { mutableStateOf(false) }
    var showImeInputBar by remember { mutableStateOf(true) }
    var imeCommandText by remember { mutableStateOf("") }

    val textMeasurer = rememberTextMeasurer()
    val clipboard = LocalClipboardManager.current
    val baseMonoStyle = NexusTheme.monospace.terminalDefault
    var fontScale by remember(externalZoomFactor) { mutableFloatStateOf(externalZoomFactor) }
    var scrollOffsetLines by remember { mutableIntStateOf(0) }

    val allLines = remember(snapshot.scrollback_lines, snapshot.visible_lines) {
        snapshot.scrollback_lines + snapshot.lines
    }
    val maxScrollLines = remember(allLines.size, snapshot.rows) {
        (allLines.size - snapshot.rows).coerceAtLeast(0)
    }
    val clampedScrollOffset = scrollOffsetLines.coerceIn(0, maxScrollLines)

    LaunchedEffect(isFocused) {
        if (isFocused) {
            runCatching { canvasFocusRequester.requestFocus() }
        }
    }

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

    val dispatchTerminalBytes: (ByteArray) -> Unit = { bytes ->
        scrollOffsetLines = 0
        controller.sendUserBytes(bytes)
    }

    if (replayModalVisible && !lastCastPayload.isNullOrBlank()) {
        AsciicastPlaybackDialog(
            castPayload = lastCastPayload.orEmpty(),
            onCopyCast = { clipboard.setText(AnnotatedString(lastCastPayload.orEmpty())) },
            onDismiss = { replayModalVisible = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexusTheme.extendedColors.terminalCanvasBackground)
    ) {
        // Compact Terminal Session Status Strip (Port Forwards, Broadcast, Asciicast .cast Recorder, Paste)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "${snapshot.cols}×${snapshot.rows} • xterm-256color" +
                            if (clampedScrollOffset > 0) " • ↑$clampedScrollOffset" else "",
                        style = NexusTheme.monospace.keycapShortcut,
                        color = NexusTheme.extendedColors.sshAccent
                    )
                    if (activeBoundTunnels > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.DeviceHub,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = NexusTheme.extendedColors.gatewayAccent
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "$activeBoundTunnels $tunnelsActiveLabel",
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

                Spacer(modifier = Modifier.width(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    NexusActionChip(
                        onClick = { showImeInputBar = !showImeInputBar },
                        label = "⌨ Input",
                        leadingIcon = Icons.Outlined.Keyboard
                    )

                    // Asciicast v2 (.cast) Record / Stop Toggle Chip
                    NexusActionChip(
                        onClick = {
                            if (isRecording) {
                                val castText = controller.stopRecordingCast()
                                clipboard.setText(AnnotatedString(castText))
                            } else {
                                controller.startRecordingCast()
                            }
                        },
                        label = if (isRecording) "REC ($recordedFrames)" else "REC .cast",
                        leadingIcon = if (isRecording) Icons.Outlined.Stop else Icons.Outlined.FiberManualRecord,
                        leadingIconTint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                    )

                    if (!lastCastPayload.isNullOrBlank() && !isRecording) {
                        NexusActionChip(
                            onClick = { replayModalVisible = true },
                            label = "Replay .cast",
                            leadingIcon = Icons.Outlined.PlayArrow
                        )
                    }

                    NexusActionChip(
                        onClick = {
                            clipboard.getText()?.text?.let {
                                scrollOffsetLines = 0
                                controller.pasteClipboardText(it)
                            }
                        },
                        label = pasteActionLabel,
                        leadingIcon = Icons.Outlined.ContentPaste
                    )
                }
            }
        }

        val cursorColor = NexusTheme.extendedColors.sshAccent
        val defaultTextColor = Color(0xFFE2E6F3)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .focusRequester(canvasFocusRequester)
                .focusable()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    runCatching { canvasFocusRequester.requestFocus() }
                }
                .onKeyEvent { keyEvent ->
                    val nativeEv = keyEvent.nativeKeyEvent
                    if (nativeEv.action == KeyEvent.ACTION_DOWN) {
                        val effectiveCtrl = nativeEv.isCtrlPressed || stickyCtrl
                        val effectiveAlt = nativeEv.isAltPressed || stickyAlt
                        val ansi = SshNativeBridge.translateHardwareKeyToAnsi(
                            keyCode = nativeEv.keyCode,
                            unicodeChar = nativeEv.unicodeChar,
                            ctrlPressed = effectiveCtrl,
                            altPressed = effectiveAlt,
                            applicationCursorKeys = controller.isApplicationCursorKeys()
                        )
                        if (ansi != null) {
                            stickyCtrl = false
                            stickyAlt = false
                            dispatchTerminalBytes(ansi)
                            return@onKeyEvent true
                        }
                    } else if (nativeEv.action == KeyEvent.ACTION_MULTIPLE && !nativeEv.characters.isNullOrEmpty()) {
                        dispatchTerminalBytes(nativeEv.characters.toByteArray(Charsets.UTF_8))
                        return@onKeyEvent true
                    }
                    false
                }
                .onSizeChanged { size ->
                    val cols = max(20, (size.width / cellWidthPx).toInt())
                    val rows = max(6, (size.height / cellHeightPx).toInt())
                    if (cols != snapshot.cols || rows != snapshot.rows) {
                        controller.resizePty(cols, rows)
                    }
                }
                .nexusDesktopPointerButtons(
                    onSecondaryClick = {
                        clipboard.getText()?.text?.let {
                            scrollOffsetLines = 0
                            controller.pasteClipboardText(it)
                        }
                    }
                )
                .pointerInput(maxScrollLines, cellHeightPx) {
                    var dragRemainder = 0f
                    detectVerticalDragGestures { _, dragAmount ->
                        dragRemainder += dragAmount
                        val deltaLines = (dragRemainder / cellHeightPx).toInt()
                        if (deltaLines != 0) {
                            dragRemainder -= deltaLines * cellHeightPx
                            scrollOffsetLines = (scrollOffsetLines + deltaLines).coerceIn(0, maxScrollLines)
                        }
                    }
                }
                .nexusMouseWheelHandler(
                    onScroll = { _, dy, _ ->
                        scrollOffsetLines = (scrollOffsetLines - dy.toInt()).coerceIn(0, maxScrollLines)
                    },
                    onZoom = { delta, _ ->
                        val nextScale = (fontScale + delta).coerceIn(0.75f, 2.4f)
                        fontScale = nextScale
                        onZoomFactorChanged(nextScale)
                    }
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val totalCount = allLines.size
                val endExclusive = (totalCount - clampedScrollOffset).coerceAtLeast(0)
                val startInclusive = (endExclusive - snapshot.rows).coerceAtLeast(0)
                val windowLines = if (startInclusive < endExclusive) {
                    allLines.subList(startInclusive, endExclusive)
                } else {
                    emptyList()
                }
                val scrollbackCount = snapshot.scrollback_lines.size

                windowLines.forEachIndexed { rowIndex, lineText ->
                    val yPx = rowIndex * cellHeightPx
                    if (yPx + cellHeightPx <= size.height && lineText.isNotEmpty()) {
                        val globalRow = startInclusive + rowIndex
                        val visibleRowIdx = globalRow - scrollbackCount
                        val styledText = buildColoredTerminalRow(
                            lineText = lineText,
                            rowIndexInVisible = visibleRowIdx,
                            cols = snapshot.cols,
                            packedAttrs = snapshot.packed_rgba_attrs,
                            defaultFg = defaultTextColor
                        )
                        if (styledText.isNotEmpty()) {
                            drawText(
                                textMeasurer = textMeasurer,
                                text = styledText,
                                topLeft = Offset(0f, yPx),
                                style = scaledStyle
                            )
                        }
                    }
                }

                // Draw expressive block/beam terminal cursor when viewing live bottom viewport
                if (snapshot.cursor_visible && clampedScrollOffset == 0) {
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

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        // On-Screen Special Key Bar (ESC, TAB, sticky CTRL/ALT, Arrows, Home/End, PgUp/PgDn, |, /, -)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TerminalSpecialKeyButton("ESC") { dispatchTerminalBytes("\u001b".toByteArray()) }
                TerminalSpecialKeyButton("TAB") { dispatchTerminalBytes("\t".toByteArray()) }
                NexusChoiceChip(
                    selected = stickyCtrl,
                    onClick = { stickyCtrl = !stickyCtrl },
                    label = "CTRL",
                    monospace = true,
                    showCheckmarkWhenSelected = false
                )
                NexusChoiceChip(
                    selected = stickyAlt,
                    onClick = { stickyAlt = !stickyAlt },
                    label = "ALT",
                    monospace = true,
                    showCheckmarkWhenSelected = false
                )
                TerminalSpecialKeyButton("↑") {
                    dispatchTerminalBytes((if (controller.isApplicationCursorKeys()) "\u001bOA" else "\u001b[A").toByteArray())
                }
                TerminalSpecialKeyButton("↓") {
                    dispatchTerminalBytes((if (controller.isApplicationCursorKeys()) "\u001bOB" else "\u001b[B").toByteArray())
                }
                TerminalSpecialKeyButton("←") {
                    dispatchTerminalBytes((if (controller.isApplicationCursorKeys()) "\u001bOD" else "\u001b[D").toByteArray())
                }
                TerminalSpecialKeyButton("→") {
                    dispatchTerminalBytes((if (controller.isApplicationCursorKeys()) "\u001bOC" else "\u001b[C").toByteArray())
                }
                TerminalSpecialKeyButton("Home") {
                    dispatchTerminalBytes((if (controller.isApplicationCursorKeys()) "\u001bOH" else "\u001b[H").toByteArray())
                }
                TerminalSpecialKeyButton("End") {
                    dispatchTerminalBytes((if (controller.isApplicationCursorKeys()) "\u001bOF" else "\u001b[F").toByteArray())
                }
                TerminalSpecialKeyButton("PgUp") {
                    scrollOffsetLines = (scrollOffsetLines + (snapshot.rows / 2).coerceAtLeast(1)).coerceIn(0, maxScrollLines)
                    controller.sendUserBytes("\u001b[5~".toByteArray())
                }
                TerminalSpecialKeyButton("PgDn") {
                    scrollOffsetLines = (scrollOffsetLines - (snapshot.rows / 2).coerceAtLeast(1)).coerceIn(0, maxScrollLines)
                    controller.sendUserBytes("\u001b[6~".toByteArray())
                }
                TerminalSpecialKeyButton("|") { dispatchTerminalBytes("|".toByteArray()) }
                TerminalSpecialKeyButton("/") { dispatchTerminalBytes("/".toByteArray()) }
                TerminalSpecialKeyButton("-") { dispatchTerminalBytes("-".toByteArray()) }
            }
        }

        // Interactive IME Command / Text Input Bar for Android Tablets & Touchscreens
        if (showImeInputBar) {
            val submitImeCommand: () -> Unit = {
                val current = imeCommandText
                if (current.isEmpty()) {
                    dispatchTerminalBytes("\r".toByteArray(Charsets.UTF_8))
                } else {
                    if (stickyCtrl && current.length == 1 && current[0].lowercaseChar() in 'a'..'z') {
                        val ctrlByte = ((current[0].lowercaseChar() - 'a') + 1).toByte()
                        stickyCtrl = false
                        dispatchTerminalBytes(
                            if (stickyAlt) byteArrayOf(0x1B, ctrlByte).also { stickyAlt = false }
                            else byteArrayOf(ctrlByte)
                        )
                    } else {
                        val payload = (current + "\r").toByteArray(Charsets.UTF_8)
                        stickyCtrl = false
                        stickyAlt = false
                        dispatchTerminalBytes(payload)
                    }
                    imeCommandText = ""
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = imeCommandText,
                        onValueChange = { updated ->
                            if (stickyCtrl && updated.length == 1 && updated[0].lowercaseChar() in 'a'..'z') {
                                val ctrlByte = ((updated[0].lowercaseChar() - 'a') + 1).toByte()
                                stickyCtrl = false
                                val out = if (stickyAlt) {
                                    stickyAlt = false
                                    byteArrayOf(0x1B, ctrlByte)
                                } else {
                                    byteArrayOf(ctrlByte)
                                }
                                dispatchTerminalBytes(out)
                                imeCommandText = ""
                            } else {
                                imeCommandText = updated
                            }
                        },
                        placeholder = {
                            Text(
                                text = if (stickyCtrl) "Press a-z for Ctrl+<key>…" else "Type command or text…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        textStyle = NexusTheme.monospace.logEntry,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitImeCommand() }),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = submitImeCommand) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = "Send Command",
                            tint = NexusTheme.extendedColors.sshAccent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalSpecialKeyButton(
    label: String,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(28.dp)
    ) {
        Text(text = label, style = NexusTheme.monospace.keycapShortcut)
    }
}

/**
 * Renders asciicast output frames up to `frameCount`, cleanly applying `\r` carriage-return overwrites,
 * `\b` backspaces, and screen/line erasures.
 */
private fun renderAsciicastFramesCleanly(frames: List<AsciicastEventFrame>): String {
    val lines = mutableListOf(StringBuilder())
    var row = 0
    var col = 0

    for (frame in frames) {
        if (frame.eventType != "o") continue
        val text = frame.data
            .replace("\u001B[2J", "\u000C")
            .replace(Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)"), "")
            .replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")

        for (ch in text) {
            when (ch) {
                '\u000C' -> {
                    lines.clear()
                    lines.add(StringBuilder())
                    row = 0
                    col = 0
                }
                '\r' -> {
                    col = 0
                }
                '\n' -> {
                    row++
                    col = 0
                    while (lines.size <= row) {
                        lines.add(StringBuilder())
                    }
                }
                '\b' -> {
                    if (col > 0) col--
                }
                else -> {
                    if (ch >= ' ' || ch == '\t') {
                        while (lines.size <= row) {
                            lines.add(StringBuilder())
                        }
                        val lineBuf = lines[row]
                        while (lineBuf.length < col) {
                            lineBuf.append(' ')
                        }
                        if (col < lineBuf.length) {
                            lineBuf.setCharAt(col, ch)
                        } else {
                            lineBuf.append(ch)
                        }
                        col++
                    }
                }
            }
        }
    }
    return lines.joinToString("\n") { it.toString() }
}

/**
 * Interactive Asciicast v2 (`.cast`) Session Audit Playback Player with `1x` / `2x` / `4x` speed & timeline scrubber.
 */
@Composable
private fun AsciicastPlaybackDialog(
    castPayload: String,
    onCopyCast: () -> Unit,
    onDismiss: () -> Unit
) {
    val parsed = remember(castPayload) { AsciicastSessionRecorder.parseCastDocument(castPayload) }
    val frames = parsed?.frames.orEmpty()
    var currentIndex by remember(frames) { mutableIntStateOf(0) }
    var isPlaying by remember(frames) { mutableStateOf(frames.isNotEmpty()) }
    var speedMultiplier by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(isPlaying, speedMultiplier, frames) {
        if (!isPlaying || frames.isEmpty()) return@LaunchedEffect
        if (currentIndex >= frames.size) currentIndex = 0
        while (isPlaying && currentIndex < frames.size) {
            val prevSec = if (currentIndex == 0) 0.0 else frames[currentIndex - 1].elapsedSeconds
            val nextSec = frames[currentIndex].elapsedSeconds
            val deltaMs = (((nextSec - prevSec).coerceAtLeast(0.0) * 1000.0) / speedMultiplier)
                .toLong()
                .coerceAtMost(500L)
            if (deltaMs > 0L) {
                delay(deltaMs)
            }
            currentIndex++
        }
        isPlaying = false
    }

    val renderedOutput = remember(frames, currentIndex) {
        renderAsciicastFramesCleanly(frames.take(currentIndex))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Asciicast v2 Audit Replay • ${parsed?.header?.title ?: "Session.cast"}")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = {
                            if (!isPlaying && currentIndex >= frames.size) {
                                currentIndex = 0
                            }
                            isPlaying = !isPlaying
                        }
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isPlaying) "Pause" else "Play")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1f to "1x", 2f to "2x", 4f to "4x").forEach { (spd, lbl) ->
                            NexusChoiceChip(
                                selected = speedMultiplier == spd,
                                onClick = { speedMultiplier = spd },
                                label = lbl,
                                showCheckmarkWhenSelected = false
                            )
                        }
                    }
                }

                if (frames.isNotEmpty()) {
                    Slider(
                        value = currentIndex.toFloat(),
                        onValueChange = {
                            isPlaying = false
                            currentIndex = it.toInt().coerceIn(0, frames.size)
                        },
                        valueRange = 0f..frames.size.toFloat()
                    )
                }

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = NexusTheme.extendedColors.terminalCanvasBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = renderedOutput.ifBlank { "[Frame $currentIndex/${frames.size} — press Play to start]" },
                            style = NexusTheme.monospace.logEntry,
                            color = Color(0xFFE2E6F3)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onCopyCast) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy .cast JSONL")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
