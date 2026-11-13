package com.nexusts.app.core.ui.input

import android.view.KeyEvent as AndroidKeyEvent
import android.view.PointerIcon as AndroidPointerIcon
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext

/**
 * Hardware scan-code + modifier packet forwarded directly to native FreeRDP 3.x / russh / RFB engines.
 * Preserves Alt, Ctrl, Win/Meta, Cmd, Fn, CapsLock, NumLock, ScrollLock, and XT/evdev scan codes.
 */
@Immutable
data class RawHardwareKeyPacket(
    val keyCode: Int,
    val scanCode: Int,
    val unicodeChar: Int,
    val isKeyDown: Boolean,
    val isRepeat: Boolean,
    val altPressed: Boolean,
    val ctrlPressed: Boolean,
    val shiftPressed: Boolean,
    val metaWinCmdPressed: Boolean,
    val fnPressed: Boolean,
    val capsLockOn: Boolean,
    val numLockOn: Boolean,
    val scrollLockOn: Boolean,
    val eventTimeMs: Long
)

/**
 * Captures desktop mouse right-click (secondary button) and middle-click (tertiary button)
 * at exact local pointer coordinates without interfering with primary click or drag gestures.
 */
fun Modifier.nexusDesktopPointerButtons(
    onSecondaryClick: ((Offset) -> Unit)? = null,
    onTertiaryMiddleClick: ((Offset) -> Unit)? = null
): Modifier = pointerInput(onSecondaryClick, onTertiaryMiddleClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.type == PointerEventType.Press && event.changes.none { it.isConsumed }) {
                val position = event.changes.firstOrNull()?.position ?: Offset.Zero
                when {
                    event.buttons.isSecondaryPressed && onSecondaryClick != null -> {
                        event.changes.forEach { it.consume() }
                        onSecondaryClick(position)
                    }
                    event.buttons.isTertiaryPressed && onTertiaryMiddleClick != null -> {
                        event.changes.forEach { it.consume() }
                        onTertiaryMiddleClick(position)
                    }
                }
            }
        }
    }
}

/**
 * Tracks precise pointer hover state and cursor position on Googlebook OS / external mouse & trackpad.
 */
@Immutable
data class DesktopHoverInfo(
    val isHovered: Boolean = false,
    val pointerOffset: Offset = Offset.Zero
)

@Composable
fun rememberDesktopHoverState(): MutableState<DesktopHoverInfo> =
    remember { mutableStateOf(DesktopHoverInfo()) }

fun Modifier.nexusHoverTracker(
    state: MutableState<DesktopHoverInfo>,
    cursorIcon: PointerIcon = PointerIcon.Default
): Modifier = this
    .pointerHoverIcon(cursorIcon)
    .pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pos = event.changes.firstOrNull()?.position ?: state.value.pointerOffset
                when (event.type) {
                    PointerEventType.Enter -> state.value = DesktopHoverInfo(true, pos)
                    PointerEventType.Move -> if (state.value.isHovered) {
                        state.value = DesktopHoverInfo(true, pos)
                    }
                    PointerEventType.Exit -> state.value = DesktopHoverInfo(false, pos)
                }
            }
        }
    }

/**
 * Platform resize cursors for Googlebook OS split-pane dividers and resizable sidebars.
 */
fun Modifier.nexusResizeCursor(horizontalResize: Boolean): Modifier = composed {
    val context = LocalContext.current
    val iconType = if (horizontalResize) {
        AndroidPointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
    } else {
        AndroidPointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
    }
    val icon = remember(context, iconType) {
        PointerIcon(AndroidPointerIcon.getSystemIcon(context, iconType))
    }
    this.pointerHoverIcon(icon)
}

/**
 * Handles desktop mouse wheel scrolling (vertical, Shift+Wheel horizontal, and Ctrl/Cmd+Wheel zoom)
 * with modifier awareness for terminal scrollback and remote desktop canvas scaling.
 */
fun Modifier.nexusMouseWheelHandler(
    onScroll: (deltaX: Float, deltaY: Float, altPressed: Boolean) -> Unit,
    onZoom: ((zoomDelta: Float, focalPoint: Offset) -> Unit)? = null
): Modifier = pointerInput(onScroll, onZoom) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.type == PointerEventType.Scroll) {
                val change = event.changes.firstOrNull() ?: continue
                val scroll = change.scrollDelta
                val ctrlOrMeta = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                if (ctrlOrMeta && onZoom != null) {
                    change.consume()
                    onZoom(-scroll.y * 0.1f, change.position)
                } else {
                    val shift = event.keyboardModifiers.isShiftPressed
                    val dx = if (shift && scroll.x == 0f) scroll.y else scroll.x
                    val dy = if (shift && scroll.x == 0f) 0f else scroll.y
                    change.consume()
                    onScroll(dx, dy, event.keyboardModifiers.isAltPressed)
                }
            }
        }
    }
}

/**
 * Trackpad pinch-to-zoom and two-finger pan gesture modifier for RDP and VNC remote framebuffers.
 */
fun Modifier.nexusTrackpadCanvasGesture(
    enabled: Boolean = true,
    onPanAndZoom: (centroid: Offset, pan: Offset, zoomChange: Float) -> Unit
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(Unit) {
        detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, _ ->
            onPanAndZoom(centroid, pan, zoom)
        }
    }
}

/**
 * Full Modifier Key Passthrough for active remote sessions (RDP, SSH, VNC).
 * Forwards Alt, Ctrl, Win/Meta, Cmd, Fn, Function keys, and hardware scan codes directly
 * to the native protocol engine unless intercepted by an explicit host escape chord.
 */
fun Modifier.nexusRemoteKeyPassthrough(
    captureEnabled: Boolean,
    shortcutEngine: NexusShortcutEngine,
    onHostShortcut: (NexusShortcutAction) -> Unit,
    onForwardToRemote: (RawHardwareKeyPacket) -> Boolean
): Modifier = onPreviewKeyEvent { keyEvent: KeyEvent ->
    if (shortcutEngine.dispatchKeyEvent(
            event = keyEvent,
            remoteCaptureActive = captureEnabled,
            onAction = { action ->
                onHostShortcut(action)
                true
            }
        )
    ) {
        return@onPreviewKeyEvent true
    }

    if (!captureEnabled) return@onPreviewKeyEvent false

    val native = keyEvent.nativeKeyEvent
    val isDown = native.action == AndroidKeyEvent.ACTION_DOWN
    val isUp = native.action == AndroidKeyEvent.ACTION_UP
    if (!isDown && !isUp) return@onPreviewKeyEvent false

    val packet = RawHardwareKeyPacket(
        keyCode = native.keyCode,
        scanCode = native.scanCode,
        unicodeChar = native.getUnicodeChar(native.metaState),
        isKeyDown = isDown,
        isRepeat = native.repeatCount > 0,
        altPressed = native.isAltPressed,
        ctrlPressed = native.isCtrlPressed,
        shiftPressed = native.isShiftPressed,
        metaWinCmdPressed = native.isMetaPressed,
        fnPressed = native.isFunctionPressed,
        capsLockOn = native.isCapsLockOn,
        numLockOn = native.isNumLockOn,
        scrollLockOn = native.isScrollLockOn,
        eventTimeMs = native.eventTime
    )
    onForwardToRemote(packet)
}
