package com.nexusts.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

/**
 * Root Material 3 Expressive Theme for NexusTS (`com.nexusts.app`).
 * Provides Expressive color schemes, typography, spatial/effects spring physics,
 * morphing container shapes, and multi-protocol extended colors.
 */
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

    CompositionLocalProvider(
        LocalNexusExtendedColors provides extendedColors,
        LocalNexusMonospaceTypography provides scaledMono,
        LocalNexusMotion provides NexusMotionScheme()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NexusExpressiveTypography,
            shapes = NexusExpressiveShapes,
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
