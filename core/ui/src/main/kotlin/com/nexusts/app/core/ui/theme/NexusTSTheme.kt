package com.nexusts.app.core.ui.theme

import android.content.Context
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class NexusThemeMode(val storageKey: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorage(raw: String?): NexusThemeMode =
            entries.firstOrNull { it.storageKey.equals(raw, ignoreCase = true) } ?: SYSTEM
    }
}

object NexusThemeModeController {
    private const val PREFS_NAME = "nexusts_ui_theme_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    private val _themeMode = MutableStateFlow<NexusThemeMode?>(null)

    fun currentMode(context: Context): NexusThemeMode {
        _themeMode.value?.let { return it }
        val loaded = runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            NexusThemeMode.fromStorage(prefs.getString(KEY_THEME_MODE, null))
        }.getOrDefault(NexusThemeMode.SYSTEM)
        _themeMode.value = loaded
        return loaded
    }

    @Composable
    fun observeThemeMode(): NexusThemeMode {
        val context = LocalContext.current
        val initial = remember(context) { currentMode(context) }
        val observed by _themeMode.collectAsState()
        return observed ?: initial
    }

    fun setThemeMode(context: Context, mode: NexusThemeMode) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME_MODE, mode.storageKey)
                .apply()
        }
        _themeMode.value = mode
    }
}

/**
 * Pure-Compose, single-pass full-surface desktop hover & press indication that avoids Android's
 * `RippleHostView` / `StateLayer` transparent-`RenderNode` partial-clear bug on ChromeOS ARCVM
 * (which otherwise leaves a white/light-gray rectangular strip behind button text on mouse hover).
 */
private class NexusUniformDesktopIndication(
    private val tintColor: Color
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        NexusUniformIndicationNode(interactionSource, tintColor)

    override fun hashCode(): Int = tintColor.hashCode()

    override fun equals(other: Any?): Boolean =
        other is NexusUniformDesktopIndication && other.tintColor == tintColor

    private class NexusUniformIndicationNode(
        private val interactionSource: InteractionSource,
        private val tintColor: Color
    ) : Modifier.Node(), DrawModifierNode {
        private var isPressed = false
        private var isHovered = false
        private var isFocused = false

        override fun onAttach() {
            coroutineScope.launch {
                val presses = mutableSetOf<PressInteraction.Press>()
                val hovers = mutableSetOf<HoverInteraction.Enter>()
                val focuses = mutableSetOf<FocusInteraction.Focus>()
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> presses.add(interaction)
                        is PressInteraction.Release -> presses.remove(interaction.press)
                        is PressInteraction.Cancel -> presses.remove(interaction.press)
                        is HoverInteraction.Enter -> hovers.add(interaction)
                        is HoverInteraction.Exit -> hovers.remove(interaction.enter)
                        is FocusInteraction.Focus -> focuses.add(interaction)
                        is FocusInteraction.Unfocus -> focuses.remove(interaction.focus)
                    }
                    val newPressed = presses.isNotEmpty()
                    val newHovered = hovers.isNotEmpty()
                    val newFocused = focuses.isNotEmpty()
                    if (isPressed != newPressed || isHovered != newHovered || isFocused != newFocused) {
                        isPressed = newPressed
                        isHovered = newHovered
                        isFocused = newFocused
                        invalidateDraw()
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            val overlayAlpha = when {
                isPressed -> 0.11f
                isHovered -> 0.07f
                isFocused -> 0.05f
                else -> 0f
            }
            if (overlayAlpha > 0f) {
                drawRect(color = tintColor.copy(alpha = overlayAlpha), size = size)
            }
            drawContent()
        }
    }
}

/**
 * Root Material 3 Expressive Theme for NexusTS (`com.nexusts.app`).
 * Provides Expressive color schemes, typography, spatial/effects spring physics,
 * morphing container shapes, artifact-free desktop hover highlights, and multi-protocol extended colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexusTSTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = false,
    highContrast: Boolean = false,
    terminalFontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val persistedMode = NexusThemeModeController.observeThemeMode()
    val systemDark = isSystemInDarkTheme()
    val resolvedDarkTheme = darkTheme ?: when (persistedMode) {
        NexusThemeMode.LIGHT -> false
        NexusThemeMode.DARK -> true
        NexusThemeMode.SYSTEM -> systemDark
    }
    val colorScheme = resolveNexusColorScheme(
        context = context,
        darkTheme = resolvedDarkTheme,
        dynamicColor = dynamicColor,
        highContrast = highContrast
    )
    val extendedColors = if (resolvedDarkTheme) DarkNexusExtendedColors else LightNexusExtendedColors
    val defaultMono = NexusMonospaceTypography()
    val scaledMono = defaultMono.copy(
        terminalDefault = defaultMono.terminalDefault.copy(
            fontSize = defaultMono.terminalDefault.fontSize * terminalFontScale,
            lineHeight = defaultMono.terminalDefault.lineHeight * terminalFontScale
        ),
        terminalBold = defaultMono.terminalBold.copy(
            fontSize = defaultMono.terminalBold.fontSize * terminalFontScale,
            lineHeight = defaultMono.terminalBold.lineHeight * terminalFontScale
        )
    )
    // Disable Material3's AndroidRippleNode hover/focus StateLayer (`hoveredAlpha = 0f`, `focusedAlpha = 0f`)
    // so transparent-surface chips & outlined buttons never suffer RenderNode inner text-box stripe artifacts!
    val cleanPressOnlyRipple = remember(colorScheme.primary) {
        RippleConfiguration(
            color = colorScheme.primary,
            rippleAlpha = RippleAlpha(
                pressedAlpha = 0.09f,
                focusedAlpha = 0f,
                hoveredAlpha = 0f,
                draggedAlpha = 0.06f
            )
        )
    }
    val uniformIndication = remember(colorScheme.primary) {
        NexusUniformDesktopIndication(tintColor = colorScheme.primary)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NexusExpressiveTypography,
        shapes = NexusExpressiveShapes
    ) {
        CompositionLocalProvider(
            LocalNexusExtendedColors provides extendedColors,
            LocalNexusMonospaceTypography provides scaledMono,
            LocalNexusMotion provides NexusMotionScheme(),
            LocalRippleConfiguration provides cleanPressOnlyRipple,
            LocalIndication provides uniformIndication,
            content = content
        )
    }
}

object NexusTheme {
    val extendedColors: NexusExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNexusExtendedColors.current

    val monospace: NexusMonospaceTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalNexusMonospaceTypography.current

    val motion: NexusMotionScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalNexusMotion.current
}
