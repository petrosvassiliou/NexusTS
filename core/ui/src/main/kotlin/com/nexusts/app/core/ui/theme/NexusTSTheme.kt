package com.nexusts.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Root Material 3 Expressive Theme for NexusTS (`com.nexusts.app`).
 * Provides Expressive color schemes, typography, spatial/effects spring physics,
 * morphing container shapes, subtle desktop hover ripple tuning, and multi-protocol extended colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexusTSTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    highContrast: Boolean = false,
    terminalFontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = resolveNexusColorScheme(
        context = context,
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        highContrast = highContrast
    )
    val extendedColors = if (darkTheme) DarkNexusExtendedColors else LightNexusExtendedColors
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
    val subtleDesktopRipple = remember(colorScheme.primary) {
        RippleConfiguration(
            color = colorScheme.primary,
            rippleAlpha = RippleAlpha(
                pressedAlpha = 0.10f,
                focusedAlpha = 0.05f,
                hoveredAlpha = 0.04f,
                draggedAlpha = 0.08f
            )
        )
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
            LocalRippleConfiguration provides subtleDesktopRipple,
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
