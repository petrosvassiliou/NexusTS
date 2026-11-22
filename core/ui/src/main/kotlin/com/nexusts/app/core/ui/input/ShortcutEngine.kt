package com.nexusts.app.core.ui.input

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
    TOGGLE_FULLSCREEN_SESSION,
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
    fun matches(event: KeyEvent): Boolean = matchesAndroidKeyEvent(event.nativeKeyEvent)

    fun matchesAndroidKeyEvent(native: AndroidKeyEvent): Boolean {
        val codeMatches = when (androidKeyCode) {
            AndroidKeyEvent.KEYCODE_EQUALS ->
                native.keyCode == AndroidKeyEvent.KEYCODE_EQUALS ||
                    native.keyCode == AndroidKeyEvent.KEYCODE_PLUS ||
                    native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ADD
            AndroidKeyEvent.KEYCODE_MINUS ->
                native.keyCode == AndroidKeyEvent.KEYCODE_MINUS ||
                    native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_SUBTRACT
            AndroidKeyEvent.KEYCODE_0 ->
                native.keyCode == AndroidKeyEvent.KEYCODE_0 ||
                    native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_0
            else -> native.keyCode == androidKeyCode
        }
        if (!codeMatches) return false

        val isCtrl = native.isCtrlPressed
        val isMeta = native.isMetaPressed
        val isAlt = native.isAltPressed
        val isShift = native.isShiftPressed

        // Allow Shift on QWERTZ/ISO keyboards when producing '+' (e.g. Ctrl+Shift+Equals / KEYCODE_PLUS)
        val ignoreShiftForPlus = androidKeyCode == AndroidKeyEvent.KEYCODE_EQUALS && !shift &&
            (native.keyCode == AndroidKeyEvent.KEYCODE_PLUS || native.keyCode == AndroidKeyEvent.KEYCODE_EQUALS)

        val hasPrimary = isCtrl || isMeta
        if (primaryOrMeta) {
            if (!hasPrimary) return false
        } else {
            if (ctrl != isCtrl) return false
            if (meta != isMeta) return false
        }
        if (alt != isAlt) return false
        if (!ignoreShiftForPlus && shift != isShift) return false
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
            AndroidKeyEvent.KEYCODE_F -> "F"
            AndroidKeyEvent.KEYCODE_F11 -> "F11"
            AndroidKeyEvent.KEYCODE_TAB -> "Tab"
            AndroidKeyEvent.KEYCODE_BACKSLASH -> "\\"
            AndroidKeyEvent.KEYCODE_MINUS -> "-"
            AndroidKeyEvent.KEYCODE_EQUALS, AndroidKeyEvent.KEYCODE_PLUS -> "+"
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
 * conflict validation, Activity-level global dispatch, and remote-capture escape handling.
 */
@Stable
class NexusShortcutEngine(
    initialBindings: List<ShortcutBinding> = defaultEnterpriseBindings()
) {
    private val _bindings = MutableStateFlow(initialBindings.associateBy { it.action })
    val bindings: StateFlow<Map<NexusShortcutAction, ShortcutBinding>> = _bindings.asStateFlow()

    private val _triggeredActions = MutableSharedFlow<NexusShortcutAction>(extraBufferCapacity = 32)
    val triggeredActions: SharedFlow<NexusShortcutAction> = _triggeredActions.asSharedFlow()

    private var lastHandledDownTime: Long = -1L
    private var lastHandledEventTime: Long = -1L
    private var lastHandledKeyCode: Int = -1

    fun bindingFor(action: NexusShortcutAction): ShortcutBinding? = _bindings.value[action]

    /**
     * Rebinds [action] to [newChord], returning a conflicting action if another binding already uses [newChord].
     */
    fun updateBinding(
        action: NexusShortcutAction,
        newChord: KeyChord,
        bypassesRemoteCapture: Boolean = action == NexusShortcutAction.RELEASE_REMOTE_KEY_CAPTURE ||
            action == NexusShortcutAction.QUICK_CONNECT_PALETTE ||
            action == NexusShortcutAction.TOGGLE_FULLSCREEN_SESSION
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
     * Evaluates a Compose [KeyEvent]. Delegates to [dispatchAndroidKeyEvent] with hardware event deduplication.
     */
    fun dispatchKeyEvent(
        event: KeyEvent,
        remoteCaptureActive: Boolean,
        onAction: ((NexusShortcutAction) -> Boolean)? = null
    ): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return dispatchAndroidKeyEvent(
            native = event.nativeKeyEvent,
            remoteCaptureActive = remoteCaptureActive,
            onAction = onAction
        )
    }

    /**
     * Evaluates a native Android [AndroidKeyEvent] directly (usable from `Activity.dispatchKeyEvent`
     * even when zero Compose focus target holds focus, as well as from Compose `onPreviewKeyEvent`).
     */
    fun dispatchAndroidKeyEvent(
        native: AndroidKeyEvent,
        remoteCaptureActive: Boolean,
        onAction: ((NexusShortcutAction) -> Boolean)? = null
    ): Boolean {
        if (native.action != AndroidKeyEvent.ACTION_DOWN) return false
        if (isSystemPassThroughKeyEvent(native)) return false
        // Prevent accidental key-repeat toggling when holding a shortcut chord like Ctrl+K
        if (native.repeatCount > 0) return false

        // Ignore bare modifier presses
        when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_CTRL_LEFT,
            AndroidKeyEvent.KEYCODE_CTRL_RIGHT,
            AndroidKeyEvent.KEYCODE_SHIFT_LEFT,
            AndroidKeyEvent.KEYCODE_SHIFT_RIGHT,
            AndroidKeyEvent.KEYCODE_ALT_LEFT,
            AndroidKeyEvent.KEYCODE_ALT_RIGHT,
            AndroidKeyEvent.KEYCODE_META_LEFT,
            AndroidKeyEvent.KEYCODE_META_RIGHT -> return false
        }

        // Deduplicate if both Activity.dispatchKeyEvent and Compose onPreviewKeyEvent inspect the same event
        if (native.eventTime > 0L &&
            native.downTime == lastHandledDownTime &&
            native.eventTime == lastHandledEventTime &&
            native.keyCode == lastHandledKeyCode
        ) {
            return true
        }

        val matchedAction: NexusShortcutAction = _bindings.value.values.firstOrNull { binding ->
            (!remoteCaptureActive || binding.bypassesRemoteCapture) && binding.chord.matchesAndroidKeyEvent(native)
        }?.action ?: run {
            // Secondary universal desktop tab-switching chords: Ctrl+Tab, Ctrl+Shift+Tab, and F11 fullscreen
            if (!remoteCaptureActive &&
                native.keyCode == AndroidKeyEvent.KEYCODE_TAB &&
                native.isCtrlPressed &&
                !native.isAltPressed &&
                !native.isMetaPressed
            ) {
                if (native.isShiftPressed) NexusShortcutAction.PREVIOUS_TAB else NexusShortcutAction.NEXT_TAB
            } else if (!remoteCaptureActive &&
                native.keyCode == AndroidKeyEvent.KEYCODE_F11 &&
                !native.isCtrlPressed &&
                !native.isAltPressed &&
                !native.isMetaPressed &&
                !native.isShiftPressed
            ) {
                NexusShortcutAction.TOGGLE_FULLSCREEN_SESSION
            } else {
                return false
            }
        }

        if (native.eventTime > 0L) {
            lastHandledDownTime = native.downTime
            lastHandledEventTime = native.eventTime
            lastHandledKeyCode = native.keyCode
        }

        val handledInline = onAction?.invoke(matchedAction)
        if (handledInline == false) {
            return false
        }
        _triggeredActions.tryEmit(matchedAction)
        return true
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
                action = NexusShortcutAction.TOGGLE_FULLSCREEN_SESSION,
                chord = KeyChord(AndroidKeyEvent.KEYCODE_F, ctrl = true, alt = true),
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
                chord = KeyChord(AndroidKeyEvent.KEYCODE_B, primaryOrMeta = true, shift = true)
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
