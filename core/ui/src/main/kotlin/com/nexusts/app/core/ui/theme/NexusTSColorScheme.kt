package com.nexusts.app.core.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Material 3 Expressive Brand Palette for NexusTS ("Nordic Sapphire & Arctic Sky-Slate").
 * Engineered for modern, calm, high-density Googlebook OS & Android tablet engineering sessions
 * with crisp sapphire primary actions and cool sky-slate tonal surfaces (zero green/mint tint).
 */
object NexusBrandTokens {
    // Primary: Electric Royal Sapphire
    val Sapphire10 = Color(0xFF081A4B)
    val Sapphire20 = Color(0xFF0F296B)
    val Sapphire30 = Color(0xFF1B3B8B)
    val Sapphire40 = Color(0xFF1D4ED8)
    val Sapphire80 = Color(0xFF8AB4FF)
    val Sapphire90 = Color(0xFFDBEAFE)
    val Sapphire95 = Color(0xFFEFF6FF)

    // Secondary: Nordic Sky-Slate & Cerulean Steel
    val SkySlate10 = Color(0xFF0C223F)
    val SkySlate20 = Color(0xFF143159)
    val SkySlate30 = Color(0xFF1E3658)
    val SkySlate40 = Color(0xFF335C8D)
    val SkySlate80 = Color(0xFF9AC5FF)
    val SkySlate90 = Color(0xFFE0ECFF)

    // Tertiary: Warm Amber-Copper Accent
    val AmberCopper10 = Color(0xFF3B1802)
    val AmberCopper20 = Color(0xFF5C2805)
    val AmberCopper30 = Color(0xFF78350F)
    val AmberCopper40 = Color(0xFFB45309)
    val AmberCopper80 = Color(0xFFFBBF24)
    val AmberCopper90 = Color(0xFFFEF3C7)

    // Architectural Slate Surfaces with subtle cool sapphire undertone
    val SurfaceDarkLowest = Color(0xFF060911)
    val SurfaceDarkDim = Color(0xFF090D16)
    val SurfaceDark = Color(0xFF0C111D)
    val SurfaceDarkContainerLow = Color(0xFF111827)
    val SurfaceDarkContainer = Color(0xFF161F33)
    val SurfaceDarkContainerHigh = Color(0xFF1E2942)
    val SurfaceDarkContainerHighest = Color(0xFF283655)

    val SurfaceLightLowest = Color(0xFFFFFFFF)
    val SurfaceLight = Color(0xFFF8FAFC)
    val SurfaceLightContainerLow = Color(0xFFF1F5F9)
    val SurfaceLightContainer = Color(0xFFE9EFF8)
    val SurfaceLightContainerHigh = Color(0xFFE1E8F3)
    val SurfaceLightContainerHighest = Color(0xFFD6DFEE)
}

val NexusExpressiveDarkColorScheme: ColorScheme = darkColorScheme(
    primary = NexusBrandTokens.Sapphire80,
    onPrimary = NexusBrandTokens.Sapphire20,
    primaryContainer = NexusBrandTokens.Sapphire30,
    onPrimaryContainer = NexusBrandTokens.Sapphire90,
    secondary = NexusBrandTokens.SkySlate80,
    onSecondary = NexusBrandTokens.SkySlate20,
    secondaryContainer = NexusBrandTokens.SkySlate30,
    onSecondaryContainer = NexusBrandTokens.SkySlate90,
    tertiary = NexusBrandTokens.AmberCopper80,
    onTertiary = NexusBrandTokens.AmberCopper20,
    tertiaryContainer = NexusBrandTokens.AmberCopper30,
    onTertiaryContainer = NexusBrandTokens.AmberCopper90,
    background = NexusBrandTokens.SurfaceDark,
    onBackground = Color(0xFFE2E8F5),
    surface = NexusBrandTokens.SurfaceDark,
    onSurface = Color(0xFFE2E8F5),
    surfaceVariant = Color(0xFF39445B),
    onSurfaceVariant = Color(0xFFBEC7DE),
    surfaceTint = NexusBrandTokens.Sapphire80,
    inverseSurface = Color(0xFFE2E8F5),
    inverseOnSurface = Color(0xFF182030),
    inversePrimary = NexusBrandTokens.Sapphire40,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8691AA),
    outlineVariant = Color(0xFF39445B),
    scrim = Color(0xCC03060C),
    surfaceBright = Color(0xFF303D5A),
    surfaceDim = NexusBrandTokens.SurfaceDarkDim,
    surfaceContainer = NexusBrandTokens.SurfaceDarkContainer,
    surfaceContainerHigh = NexusBrandTokens.SurfaceDarkContainerHigh,
    surfaceContainerHighest = NexusBrandTokens.SurfaceDarkContainerHighest,
    surfaceContainerLow = NexusBrandTokens.SurfaceDarkContainerLow,
    surfaceContainerLowest = NexusBrandTokens.SurfaceDarkLowest
)

val NexusExpressiveLightColorScheme: ColorScheme = lightColorScheme(
    primary = NexusBrandTokens.Sapphire40,
    onPrimary = Color.White,
    primaryContainer = NexusBrandTokens.Sapphire90,
    onPrimaryContainer = NexusBrandTokens.Sapphire10,
    secondary = NexusBrandTokens.SkySlate40,
    onSecondary = Color.White,
    secondaryContainer = NexusBrandTokens.SkySlate90,
    onSecondaryContainer = NexusBrandTokens.SkySlate10,
    tertiary = NexusBrandTokens.AmberCopper40,
    onTertiary = Color.White,
    tertiaryContainer = NexusBrandTokens.AmberCopper90,
    onTertiaryContainer = NexusBrandTokens.AmberCopper10,
    background = NexusBrandTokens.SurfaceLight,
    onBackground = Color(0xFF0F172A),
    surface = NexusBrandTokens.SurfaceLight,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFDCE4F2),
    onSurfaceVariant = Color(0xFF414B5F),
    surfaceTint = NexusBrandTokens.Sapphire40,
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF1F5F9),
    inversePrimary = NexusBrandTokens.Sapphire80,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6E788E),
    outlineVariant = Color(0xFFCBD5E1),
    scrim = Color(0x99050914),
    surfaceBright = NexusBrandTokens.SurfaceLightLowest,
    surfaceDim = Color(0xFFD6DFEE),
    surfaceContainer = NexusBrandTokens.SurfaceLightContainer,
    surfaceContainerHigh = NexusBrandTokens.SurfaceLightContainerHigh,
    surfaceContainerHighest = NexusBrandTokens.SurfaceLightContainerHighest,
    surfaceContainerLow = NexusBrandTokens.SurfaceLightContainerLow,
    surfaceContainerLowest = NexusBrandTokens.SurfaceLightLowest
)

val NexusHighContrastDarkColorScheme: ColorScheme = NexusExpressiveDarkColorScheme.copy(
    primary = Color(0xFFDCE8FF),
    secondary = Color(0xFFB3D4FF),
    background = Color(0xFF04060B),
    surface = Color(0xFF04060B),
    surfaceContainer = Color(0xFF0E1422),
    onSurface = Color(0xFFFFFFFF),
    outline = Color(0xFFB8C2DA)
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
    rdpAccent = Color(0xFF60A5FA),
    sshAccent = Color(0xFF38BDF8),
    sftpAccent = Color(0xFFFBBF24),
    vncAccent = Color(0xFF818CF8),
    webAccent = Color(0xFF22D3EE),
    cloudAccent = Color(0xFFF472B6),
    gatewayAccent = Color(0xFFFACC15),
    statusConnected = Color(0xFF38BDF8),
    statusConnecting = Color(0xFFFBBF24),
    statusDisconnected = Color(0xFF7A859E),
    statusError = Color(0xFFFF6E6E),
    envProductionBanner = Color(0xFFD92D4B),
    envStagingBanner = Color(0xFFD9821E),
    terminalCanvasBackground = Color(0xFF080C15),
    terminalSelectionHighlight = Color(0x553B82F6),
    activePaneFocusRing = Color(0xFF60A5FA),
    broadcastActiveBanner = Color(0xFFE0486D)
)

val LightNexusExtendedColors = NexusExtendedColors(
    rdpAccent = Color(0xFF1D4ED8),
    sshAccent = Color(0xFF0284C7),
    sftpAccent = Color(0xFFB45309),
    vncAccent = Color(0xFF4338CA),
    webAccent = Color(0xFF0E7490),
    cloudAccent = Color(0xFFBE185D),
    gatewayAccent = Color(0xFF92400E),
    statusConnected = Color(0xFF0284C7),
    statusConnecting = Color(0xFFB45309),
    statusDisconnected = Color(0xFF64748B),
    statusError = Color(0xFFC62828),
    envProductionBanner = Color(0xFFC41E3A),
    envStagingBanner = Color(0xFFBE6B00),
    terminalCanvasBackground = Color(0xFF0A101D),
    terminalSelectionHighlight = Color(0x442563EB),
    activePaneFocusRing = Color(0xFF2563EB),
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
