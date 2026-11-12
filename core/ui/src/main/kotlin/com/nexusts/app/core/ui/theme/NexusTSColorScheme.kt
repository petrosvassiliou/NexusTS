package com.nexusts.app.core.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import android.content.Context

/**
 * Material 3 Expressive Brand Palette for NexusTS ("Quantum Cobalt & Cyber Mint").
 * Engineered for extended high-density Googlebook OS desktop engineering sessions.
 */
object NexusBrandTokens {
    val QuantumCobalt10 = Color(0xFF050B1F)
    val QuantumCobalt20 = Color(0xFF0E1B42)
    val QuantumCobalt30 = Color(0xFF1B2E6B)
    val QuantumCobalt40 = Color(0xFF2B469C)
    val QuantumCobalt80 = Color(0xFFADC2FF)
    val QuantumCobalt90 = Color(0xFFD8E2FF)
    val QuantumCobalt95 = Color(0xFFEEF2FF)

    val CyberMint10 = Color(0xFF00201A)
    val CyberMint20 = Color(0xFF00382E)
    val CyberMint30 = Color(0xFF005144)
    val CyberMint40 = Color(0xFF006B5A)
    val CyberMint80 = Color(0xFF47E2C2)
    val CyberMint90 = Color(0xFF75F8DB)

    val SolarCoral10 = Color(0xFF340D00)
    val SolarCoral20 = Color(0xFF551A00)
    val SolarCoral30 = Color(0xFF782905)
    val SolarCoral40 = Color(0xFF9B3A12)
    val SolarCoral80 = Color(0xFFFFB59A)
    val SolarCoral90 = Color(0xFFFFDBCE)

    // Expressive Neutral Surfaces with subtle sapphire undertone
    val SurfaceDarkLowest = Color(0xFF070A12)
    val SurfaceDarkDim = Color(0xFF0B0F1A)
    val SurfaceDark = Color(0xFF0E1320)
    val SurfaceDarkContainerLow = Color(0xFF141A29)
    val SurfaceDarkContainer = Color(0xFF192032)
    val SurfaceDarkContainerHigh = Color(0xFF212A40)
    val SurfaceDarkContainerHighest = Color(0xFF2B3652)

    val SurfaceLightLowest = Color(0xFFFFFFFF)
    val SurfaceLight = Color(0xFFF7F9FF)
    val SurfaceLightContainerLow = Color(0xFFF0F3FC)
    val SurfaceLightContainer = Color(0xFFE9EEF9)
    val SurfaceLightContainerHigh = Color(0xFFE1E7F5)
    val SurfaceLightContainerHighest = Color(0xFFD9E0F0)
}

val NexusExpressiveDarkColorScheme: ColorScheme = darkColorScheme(
    primary = NexusBrandTokens.QuantumCobalt80,
    onPrimary = NexusBrandTokens.QuantumCobalt20,
    primaryContainer = NexusBrandTokens.QuantumCobalt30,
    onPrimaryContainer = NexusBrandTokens.QuantumCobalt90,
    secondary = NexusBrandTokens.CyberMint80,
    onSecondary = NexusBrandTokens.CyberMint20,
    secondaryContainer = NexusBrandTokens.CyberMint30,
    onSecondaryContainer = NexusBrandTokens.CyberMint90,
    tertiary = NexusBrandTokens.SolarCoral80,
    onTertiary = NexusBrandTokens.SolarCoral20,
    tertiaryContainer = NexusBrandTokens.SolarCoral30,
    onTertiaryContainer = NexusBrandTokens.SolarCoral90,
    background = NexusBrandTokens.SurfaceDark,
    onBackground = Color(0xFFE2E6F3),
    surface = NexusBrandTokens.SurfaceDark,
    onSurface = Color(0xFFE2E6F3),
    surfaceVariant = Color(0xFF3E465C),
    onSurfaceVariant = Color(0xFFC1C7DD),
    surfaceTint = NexusBrandTokens.QuantumCobalt80,
    inverseSurface = Color(0xFFE2E6F3),
    inverseOnSurface = Color(0xFF1A1F2E),
    inversePrimary = NexusBrandTokens.QuantumCobalt40,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8B91A7),
    outlineVariant = Color(0xFF3E465C),
    scrim = Color(0xCC03050A),
    surfaceBright = Color(0xFF333E5C),
    surfaceDim = NexusBrandTokens.SurfaceDarkDim,
    surfaceContainer = NexusBrandTokens.SurfaceDarkContainer,
    surfaceContainerHigh = NexusBrandTokens.SurfaceDarkContainerHigh,
    surfaceContainerHighest = NexusBrandTokens.SurfaceDarkContainerHighest,
    surfaceContainerLow = NexusBrandTokens.SurfaceDarkContainerLow,
    surfaceContainerLowest = NexusBrandTokens.SurfaceDarkLowest
)

val NexusExpressiveLightColorScheme: ColorScheme = lightColorScheme(
    primary = NexusBrandTokens.QuantumCobalt40,
    onPrimary = Color.White,
    primaryContainer = NexusBrandTokens.QuantumCobalt90,
    onPrimaryContainer = NexusBrandTokens.QuantumCobalt10,
    secondary = NexusBrandTokens.CyberMint40,
    onSecondary = Color.White,
    secondaryContainer = NexusBrandTokens.CyberMint90,
    onSecondaryContainer = NexusBrandTokens.CyberMint10,
    tertiary = NexusBrandTokens.SolarCoral40,
    onTertiary = Color.White,
    tertiaryContainer = NexusBrandTokens.SolarCoral90,
    onTertiaryContainer = NexusBrandTokens.SolarCoral10,
    background = NexusBrandTokens.SurfaceLight,
    onBackground = Color(0xFF161B28),
    surface = NexusBrandTokens.SurfaceLight,
    onSurface = Color(0xFF161B28),
    surfaceVariant = Color(0xFFDDE2F3),
    onSurfaceVariant = Color(0xFF424758),
    surfaceTint = NexusBrandTokens.QuantumCobalt40,
    inverseSurface = Color(0xFF2B3040),
    inverseOnSurface = Color(0xFFEEF0FA),
    inversePrimary = NexusBrandTokens.QuantumCobalt80,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF72778A),
    outlineVariant = Color(0xFFC1C7D9),
    scrim = Color(0x99050811),
    surfaceBright = NexusBrandTokens.SurfaceLight,
    surfaceDim = Color(0xFFD6DCF0),
    surfaceContainer = NexusBrandTokens.SurfaceLightContainer,
    surfaceContainerHigh = NexusBrandTokens.SurfaceLightContainerHigh,
    surfaceContainerHighest = NexusBrandTokens.SurfaceLightContainerHighest,
    surfaceContainerLow = NexusBrandTokens.SurfaceLightContainerLow,
    surfaceContainerLowest = NexusBrandTokens.SurfaceLightLowest
)

val NexusHighContrastDarkColorScheme: ColorScheme = NexusExpressiveDarkColorScheme.copy(
    primary = Color(0xFFD8E4FF),
    secondary = Color(0xFF8CFFE5),
    background = Color(0xFF04060B),
    surface = Color(0xFF04060B),
    surfaceContainer = Color(0xFF101522),
    onSurface = Color(0xFFFFFFFF),
    outline = Color(0xFFB8C0D8)
)

/**
 * Extended semantic colors for multi-protocol badges, environment guardrails, and terminal surfaces.
 */
@Immutable
data class NexusExtendedColors(
    val rdpAccent: Color,
    val sshAccent: Color,
    val sftpAccent: Color,
    val vncAccent: Color,
    val webAccent: Color,
    val cloudAccent: Color,
    val gatewayAccent: Color,
    val statusConnected: Color,
    val statusConnecting: Color,
    val statusDisconnected: Color,
    val statusError: Color,
    val envProductionBanner: Color,
    val envStagingBanner: Color,
    val terminalCanvasBackground: Color,
    val terminalSelectionHighlight: Color,
    val activePaneFocusRing: Color,
    val broadcastActiveBanner: Color
)

val DarkNexusExtendedColors = NexusExtendedColors(
    rdpAccent = Color(0xFF6EA2FF),
    sshAccent = Color(0xFF3CE6B8),
    sftpAccent = Color(0xFFFFB86B),
    vncAccent = Color(0xFFC58FFF),
    webAccent = Color(0xFF59D2FE),
    cloudAccent = Color(0xFFFF88B5),
    gatewayAccent = Color(0xFFFFDF6B),
    statusConnected = Color(0xFF3CE69D),
    statusConnecting = Color(0xFFFFCA58),
    statusDisconnected = Color(0xFF7A839E),
    statusError = Color(0xFFFF6E6E),
    envProductionBanner = Color(0xFFD92D4B),
    envStagingBanner = Color(0xFFD9821E),
    terminalCanvasBackground = Color(0xFF090D16),
    terminalSelectionHighlight = Color(0x554A7BFF),
    activePaneFocusRing = Color(0xFF58DFC0),
    broadcastActiveBanner = Color(0xFFE0486D)
)

val LightNexusExtendedColors = NexusExtendedColors(
    rdpAccent = Color(0xFF1F57C3),
    sshAccent = Color(0xFF007960),
    sftpAccent = Color(0xFFB35900),
    vncAccent = Color(0xFF6E35B1),
    webAccent = Color(0xFF006784),
    cloudAccent = Color(0xFFA6265E),
    gatewayAccent = Color(0xFF8A6800),
    statusConnected = Color(0xFF0E8A56),
    statusConnecting = Color(0xFFB87400),
    statusDisconnected = Color(0xFF646C82),
    statusError = Color(0xFFC62828),
    envProductionBanner = Color(0xFFC41E3A),
    envStagingBanner = Color(0xFFBE6B00),
    terminalCanvasBackground = Color(0xFF0B101C),
    terminalSelectionHighlight = Color(0x443B6EF5),
    activePaneFocusRing = Color(0xFF007960),
    broadcastActiveBanner = Color(0xFFC41E4A)
)

val LocalNexusExtendedColors = staticCompositionLocalOf { DarkNexusExtendedColors }

fun resolveNexusColorScheme(
    context: Context,
    darkTheme: Boolean,
    dynamicColor: Boolean,
    highContrast: Boolean
): ColorScheme {
    if (highContrast && darkTheme) {
        return NexusHighContrastDarkColorScheme
    }
    if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return if (darkTheme) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
    }
    return if (darkTheme) {
        NexusExpressiveDarkColorScheme
    } else {
        NexusExpressiveLightColorScheme
    }
}
