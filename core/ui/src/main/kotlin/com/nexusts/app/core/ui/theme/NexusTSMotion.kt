package com.nexusts.app.core.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Material 3 Expressive Physics Motion System.
 *
 * Strictly separates:
 * - Spatial Springs: Underdamped (0.68..0.78) for tactile physical movement, container shape morphing,
 *   split-pane magnetic snapping, and Quick-Connect palette spring-in.
 * - Effects Springs: Critically damped (1.0) for opacity, color, and elevation transitions so colors
 *   never overshoot or flicker.
 */
@Immutable
data class NexusMotionScheme(
    val spatialFastFloat: SpringSpec<Float> = spring(
        dampingRatio = 0.72f,
        stiffness = 1400f
    ),
    val spatialDefaultFloat: SpringSpec<Float> = spring(
        dampingRatio = 0.74f,
        stiffness = 750f
    ),
    val spatialSlowFloat: SpringSpec<Float> = spring(
        dampingRatio = 0.78f,
        stiffness = 340f
    ),
    val spatialFastDp: SpringSpec<Dp> = spring(
        dampingRatio = 0.72f,
        stiffness = 1400f
    ),
    val spatialDefaultDp: SpringSpec<Dp> = spring(
        dampingRatio = 0.75f,
        stiffness = 750f
    ),
    val spatialIntOffset: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = 0.76f,
        stiffness = 800f
    ),
    val spatialIntSize: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = 0.78f,
        stiffness = 700f
    ),
    val spatialOffset: SpringSpec<Offset> = spring(
        dampingRatio = 0.74f,
        stiffness = 900f
    ),
    val effectsFastFloat: SpringSpec<Float> = spring(
        dampingRatio = 1.0f,
        stiffness = 2800f
    ),
    val effectsDefaultFloat: SpringSpec<Float> = spring(
        dampingRatio = 1.0f,
        stiffness = 1200f
    )
)

val LocalNexusMotion = staticCompositionLocalOf { NexusMotionScheme() }
