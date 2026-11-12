package com.nexusts.app.core.ui.input

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Canonical workspace & session actions bindable to global or local keyboard shortcuts.
 */
@Serializable
enum class NexusShortcutAction {
    QUICK_CONNECT_PALETTE,
    NEW_CONNECTION_DIALOG,
    NEW_LOCAL_TERMINAL,
    OPEN_VAULT_DOCUMENT,
    LOCK_ALL_VAULTS,
    SYNC_GOOGLE_DRIVE_NOW,
    CLOSE_ACTIVE_TAB,
    NEXT_TAB,
    PREVIOUS_TAB,
    SPLIT_PANE_VERTICAL,
    SPLIT_PANE_HORIZONTAL,
    DETACH_TAB_TO_NEW_WINDOW,
    FOCUS_NEXT_PANE,
    TOGGLE_SIDEBAR_TREE,
    TOGGLE_PROPERTY_INSPECTOR,
    TOGGLE_BOTTOM_LOG_DRAWER,
    TOGGLE_BROADCAST_INPUT,
    RELEASE_REMOTE_KEY_CAPTURE,
    ZOOM_IN_SESSION,
    ZOOM_OUT_SESSION,
    ZOOM_RESET_SESSION
}

/**
 * Serializable key chord supporting Ctrl, Alt, Shift, and Meta (Googlebook Search/Launcher/Win/Cmd).
 * When [primaryOrMeta] is true, matches either Ctrl OR Meta/Cmd for seamless Royal TS macOS & PC muscle memory.
 */
@Immutable
@Serializable
data class KeyChord(
    val androidKeyCode: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
    val primaryOrMeta: Boolean = false
) {
    fun matches(event: KeyEvent): Boolean {
        val nativeCode = event.nativeKeyEvent.keyCode
        if (nativeCode != androidKeyCode) return false

        val hasPrimary = event.isCtrlPressed || event.isMetaPressed
        if (primaryOrMeta) {
            if (!hasPrimary) return false
        } else {
            if (ctrl != event.isCtrlPressed) return false
            if (meta != event.isMetaPressed) return false
        }
        if (alt != event.isAltPressed) return false
        if (shift != event.isShiftPressed) return false
        return true
    }

    fun formatDisplayLabel(preferMacSymbols: Boolean = false): String {
        val parts = mutableListOf<String>()
        if (primaryOrMeta) {
            parts += if (preferMacSymbols) "⌘/Ctrl" else "Ctrl"
        } else {
            if (ctrl) parts += "Ctrl"
            if (meta) parts += if (preferMacSymbols) "⌘" else "Meta"
        }
        if (alt) parts += if (preferMacSymbols) "⌥" else "Alt"
        if (shift) parts += if (preferMacSymbols) "⇧" else "Shift"
        parts += keyCodeDisplayName(androidKeyCode)
        return parts.joinToString(if (preferMacSymbols) "" else "+")
    }

    companion object {
        fun keyCodeDisplayName(keyCode: Int): String = when (keyCode) {
            AndroidKeyEvent.KEYCODE_K -> "K"
            AndroidKeyEvent.KEYCODE_N -> "N"
            AndroidKeyEvent.KEYCODE_T -> "T"
            AndroidKeyEvent.KEYCODE_O -> "O"
            AndroidKeyEvent.KEYCODE_L -> "L"
            AndroidKeyEvent.KEYCODE_S -> "S"
            AndroidKeyEvent.KEYCODE_W -> "W"
            AndroidKeyEvent.KEYCODE_B -> "B"
            AndroidKeyEvent.KEYCODE_I -> "I"
            AndroidKeyEvent.KEYCODE_J -> "J"
            AndroidKeyEvent.KEYCODE_M -> "M"
            AndroidKeyEvent.KEYCODE_D -> "D"
            AndroidKeyEvent.KEYCODE_TAB -> "Tab"
            AndroidKeyEvent.KEYCODE_BACKSLASH -> "\\"
            AndroidKeyEvent.KEYCODE_MINUS -> "-"
            AndroidKeyEvent.KEYCODE_EQUALS -> "+"
            AndroidKeyEvent.KEYCODE_0 -> "0"
            AndroidKeyEvent.KEYCODE_MOVE_HOME -> "Home"
            AndroidKeyEvent.KEYCODE_LEFT_BRACKET -> "["
            AndroidKeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
            else -> AndroidKeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }
    }
}

@Immutable
@Serializable
data class ShortcutBinding(
    val action: NexusShortcutAction,
    val chord: KeyChord,
    val bypassesRemoteCapture: Boolean = false
)

/**
 * Production Keyboard Shortcut Registry & Dispatcher with customizable keymaps,
 * conflict validation, and remote-capture escape handling.
 */
@Stable
class NexusShortcutEngine(
    initialBindings: List<ShortcutBinding> = defaultEnterpriseBindings()
) {
    private val _bindings = MutableStateFlow(initialBindings.associateBy { it.action })
    val bindings: StateFlow<Map<NexusShortcutAction, ShortcutBinding>> = _bindings.asStateFlow()

    private val _triggeredActions = MutableSharedFlow<NexusShortcutAction>(extraBufferCapacity = 32)
    val triggeredActions: SharedFlow<NexusShortcutAction> = _triggeredActions.asSharedFlow()

    fun bindingFor(action: NexusShortcutAction): ShortcutBinding? = _bindings.value[action]

    /**
     * Rebinds [action] to [newChord], returning a conflicting action if another binding already uses [newChord].
     */
    fun updateBinding(
        action: NexusShortcutAction,
        newChord: KeyChord,
        bypassesRemoteCapture: Boolean = action == NexusShortcutAction.RELEASE_REMOTE_KEY_CAPTURE ||
            action == NexusShortcutAction.QUICK_CONNECT_PALETTE
    ): NexusShortcutAction? {
        val current = _bindings.value
        val conflict = current.values.firstOrNull { it.action != action && it.chord == newChord }?.action
        val updated = current.toMutableMap()
        if (conflict != null) {
            updated.remove(conflict)
        }
        updated[action] = ShortcutBinding(
            action = action,
            chord = newChord,
            bypassesRemoteCapture = bypassesRemoteCapture
        )
        _bindings.value = updated
        return conflict
    }

    fun resetToDefaults() {
        _bindings.value = defaultEnterpriseBindings().associateBy { it.action }
    }

    /**
     * Evaluates a hardware [KeyEvent]. When [remoteCaptureActive] is true (e.g., full keyboard passthrough
     * inside an RDP or SSH session), only bindings marked `bypassesRemoteCapture` (like Ctrl+Alt+Home or Ctrl+Cmd+K)
     * are intercepted by the host NexusTS shell; all other combinations pass straight to the remote OS.
     */
    fun dispatchKeyEvent(
        event: KeyEvent,
        remoteCaptureActive: Boolean,
        onAction: ((NexusShortcutAction) -> Boolean)? = null
    ): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // Ignore bare modifier presses
        if (event.key == Key.CtrlLeft || event.key == Key.CtrlRight ||
            event.key == Key.ShiftLeft || event.key == Key.ShiftRight ||
            event.key == Key.AltLeft || event.key == Key.AltRight ||
            event.key == Key.MetaLeft || event.key == Key.MetaRight
        ) {
            return false
        }

        val match = _bindings.value.values.firstOrNull { binding ->
            (!remoteCaptureActive || binding.bypassesRemoteCapture) && binding.chord.matches(event)
        } ?: return false

        val handledInline = onAction?.invoke(match.action) ?: false
        _triggeredActions.tryEmit(match.action)
        return handledInline || true
    }

    companion object {
        fun defaultEnterpriseBindings(): List<ShortcutBinding> = listOf(
            ShortcutBinding(
                action = NexusShortcutAction.QUICK_CONNECT_PALETTE,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_K, primaryOrMeta = true),
                bypassesRemoteCapture = true
            ),
            ShortcutBinding(
                action = NexusShortcutAction.RELEASE_REMOTE_KEY_CAPTURE,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_MOVE_HOME, ctrl = true, alt = true),
                bypassesRemoteCapture = true
            ),
            ShortcutBinding(
                action = NexusShortcutAction.NEW_CONNECTION_DIALOG,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_N, primaryOrMeta = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.NEW_LOCAL_TERMINAL,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_T, primaryOrMeta = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.OPEN_VAULT_DOCUMENT,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_O, primaryOrMeta = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.LOCK_ALL_VAULTS,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_L, primaryOrMeta = true, shift = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.SYNC_GOOGLE_DRIVE_NOW,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_S, primaryOrMeta = true, shift = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.CLOSE_ACTIVE_TAB,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_W, primaryOrMeta = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.NEXT_TAB,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_RIGHT_BRACKET, primaryOrMeta = true, shift = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.PREVIOUS_TAB,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_LEFT_BRACKET, primaryOrMeta = true, shift = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.SPLIT_PANE_VERTICAL,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_D, primaryOrMeta = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.SPLIT_PANE_HORIZONTAL,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_D, primaryOrMeta = true, shift = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.DETACH_TAB_TO_NEW_WINDOW,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_O, primaryOrMeta = true, shift = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.FOCUS_NEXT_PANE,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_BACKSLASH, primaryOrMeta = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.TOGGLE_SIDEBAR_TREE,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_B, primaryOrMeta = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.TOGGLE_PROPERTY_INSPECTOR,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_I, primaryOrMeta = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.TOGGLE_BOTTOM_LOG_DRAWER,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_J, primaryOrMeta = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.TOGGLE_BROADCAST_INPUT,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_M, primaryOrMeta = true, shift = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.ZOOM_IN_SESSION,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_EQUALS, primaryOrMeta = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.ZOOM_OUT_SESSION,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_MINUS, primaryOrMeta = true)
            ),
            ShortcutBinding(
                action = NexusShortcutAction.ZOOM_RESET_SESSION,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_0, primaryOrMeta = true)
            )
        )
    }
}

val LocalNexusShortcutEngine = staticCompositionLocalOf { NexusShortcutEngine() }
