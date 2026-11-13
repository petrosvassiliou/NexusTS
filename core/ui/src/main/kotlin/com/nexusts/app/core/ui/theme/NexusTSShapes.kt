package com.nexusts.app.core.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive Shape Hierarchy & Dynamic Morphing Container Primitives.
 */
val NexusExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

enum class MorphContainerState {
    Resting,
    Hovered,
    Pressed,
    ActiveSelected,
    DockedTile
}

/**
 * Expressive container shape that smoothly morphs individual corner radii using M3 spatial springs
 * based on desktop pointer hover, press, selection, or split-pane docking state.
 */
@Composable
fun rememberMorphingContainerShape(
    state: MorphContainerState,
    restingRadius: Dp = 18.dp,
    hoveredRadius: Dp = 22.dp,
    pressedRadius: Dp = 10.dp,
    selectedRadius: Dp = 28.dp,
    dockedRadius: Dp = 12.dp
): Shape {
    val motion = LocalNexusMotion.current
    val targetRadius = when (state) {
        MorphContainerState.Resting -> restingRadius
        MorphContainerState.Hovered -> hoveredRadius
        MorphContainerState.Pressed -> pressedRadius
        MorphContainerState.ActiveSelected -> selectedRadius
        MorphContainerState.DockedTile -> dockedRadius
    }

    val animatedCorner by animateDpAsState(
        targetValue = targetRadius,
        animationSpec = motion.spatialFastDp,
        label = "NexusExpressiveMorphRadius"
    )
    return RoundedCornerShape(animatedCorner)
}

/**
 * Asymmetric morphing tab shape for desktop session tabs (pill when active/hovered, subtle top-arch when backgrounded).
 */
@Composable
fun rememberExpressiveTabShape(
    isSelected: Boolean,
    isHovered: Boolean
): Shape {
    val motion = LocalNexusMotion.current
    val topRadius by animateDpAsState(
        targetValue = when {
            isSelected -> 16.dp
            isHovered -> 14.dp
            else -> 10.dp
        },
        animationSpec = motion.spatialFastDp,
        label = "TabTopRadius"
    )
    val bottomRadius by animateDpAsState(
        targetValue = when {
            isSelected -> 16.dp
            isHovered -> 10.dp
            else -> 6.dp
        },
        animationSpec = motion.spatialFastDp,
        label = "TabBottomRadius"
    )
    return RoundedCornerShape(
        topStart = topRadius,
        topEnd = topRadius,
        bottomStart = bottomRadius,
        bottomEnd = bottomRadius
    )
}

/**
 * Material 3 Expressive Split-Button leading segment shape:
 * Full pill outer start (24.dp), crisp 6.dp inner seam that morphs to 14.dp on hover/press.
 */
@Composable
fun rememberExpressiveSplitLeadingShape(isActiveOrHovered: Boolean): Shape {
    val motion = LocalNexusMotion.current
    val innerSeam by animateDpAsState(
        targetValue = if (isActiveOrHovered) 14.dp else 6.dp,
        animationSpec = motion.spatialFastDp,
        label = "SplitLeadingSeam"
    )
    return RoundedCornerShape(
        topStart = 24.dp,
        bottomStart = 24.dp,
        topEnd = innerSeam,
        bottomEnd = innerSeam
    )
}

/**
 * Material 3 Expressive Split-Button trailing chevron segment shape:
 * Crisp 6.dp inner seam morphing to 14.dp on hover/open, full pill outer end (24.dp).
 */
@Composable
fun rememberExpressiveSplitTrailingShape(isMenuExpandedOrHovered: Boolean): Shape {
    val motion = LocalNexusMotion.current
    val innerSeam by animateDpAsState(
        targetValue = if (isMenuExpandedOrHovered) 14.dp else 6.dp,
        animationSpec = motion.spatialFastDp,
        label = "SplitTrailingSeam"
    )
    return RoundedCornerShape(
        topStart = innerSeam,
        bottomStart = innerSeam,
        topEnd = 24.dp,
        bottomEnd = 24.dp
    )
}
