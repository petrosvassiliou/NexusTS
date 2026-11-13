package com.nexusts.app.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.input.nexusHoverTracker
import com.nexusts.app.core.ui.input.rememberDesktopHoverState
import com.nexusts.app.core.ui.theme.MorphContainerState
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.ui.theme.rememberExpressiveSplitLeadingShape
import com.nexusts.app.core.ui.theme.rememberExpressiveSplitTrailingShape
import com.nexusts.app.core.ui.theme.rememberMorphingContainerShape

/**
 * Parses `#RRGGBB` or `#AARRGGBB` hex strings into a Compose [Color] for Royal TS color-coded folders and tabs.
 */
fun parseHexColorOrNull(hex: String?): Color? {
    val clean = hex?.trim()?.removePrefix("#") ?: return null
    return when (clean.length) {
        6 -> clean.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
        8 -> clean.toLongOrNull(16)?.let { Color(it) }
        else -> null
    }
}

/**
 * Material 3 Expressive Split-Action Button (`[ Icon + Label | ▾ ]`) with spring-morphing inner seam.
 */
@Composable
fun ExpressiveSplitActionButton(
    label: String,
    leadingIcon: ImageVector,
    onPrimaryClick: () -> Unit,
    onTrailingMenuClick: () -> Unit,
    isMenuExpanded: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    modifier: Modifier = Modifier
) {
    val leadHover = rememberDesktopHoverState()
    val trailHover = rememberDesktopHoverState()
    val leadShape = rememberExpressiveSplitLeadingShape(leadHover.value.isHovered)
    val trailShape = rememberExpressiveSplitTrailingShape(trailHover.value.isHovered || isMenuExpanded)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Surface(
            modifier = Modifier
                .height(36.dp)
                .nexusHoverTracker(leadHover, PointerIcon.Hand)
                .clip(leadShape)
                .clickable(onClick = onPrimaryClick),
            shape = leadShape,
            color = containerColor,
            contentColor = contentColor
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }

        Surface(
            modifier = Modifier
                .height(36.dp)
                .width(30.dp)
                .nexusHoverTracker(trailHover, PointerIcon.Hand)
                .clip(trailShape)
                .clickable(onClick = onTrailingMenuClick),
            shape = trailShape,
            color = containerColor,
            contentColor = contentColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

/**
 * Material 3 Expressive Morphing Toolbar Icon Button:
 * Resting circular pill -> Active squircle (`12.dp`) with subtle spring scale.
 */
@Composable
fun ExpressiveMorphingIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    isActive: Boolean = false,
    activeTint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    activeContainer: Color = MaterialTheme.colorScheme.secondaryContainer,
    modifier: Modifier = Modifier
) {
    val hover = rememberDesktopHoverState()
    val motion = NexusTheme.motion
    val cornerRadius by animateDpAsState(
        targetValue = when {
            isActive -> 12.dp
            hover.value.isHovered -> 14.dp
            else -> 18.dp
        },
        animationSpec = motion.spatialFastDp,
        label = "IconBtnMorphRadius"
    )
    val scale by animateFloatAsState(
        targetValue = if (hover.value.isHovered) 1.04f else 1.0f,
        animationSpec = motion.spatialFastFloat,
        label = "IconBtnScale"
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            isActive -> activeContainer
            hover.value.isHovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        },
        label = "IconBtnBg"
    )

    Surface(
        modifier = modifier
            .size(36.dp)
            .scale(scale)
            .nexusHoverTracker(hover, PointerIcon.Hand)
            .clip(RoundedCornerShape(cornerRadius))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(cornerRadius),
        color = bgColor
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isActive) activeTint else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

/**
 * Material 3 Expressive Tonal Metric Tile used in the Vault & Folder Overview Dashboard.
 */
@Composable
fun ExpressiveMetricCard(
    label: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val hover = rememberDesktopHoverState()
    val cardShape = rememberMorphingContainerShape(
        state = if (hover.value.isHovered) MorphContainerState.Hovered else MorphContainerState.Resting,
        restingRadius = 22.dp,
        hoveredRadius = 28.dp
    )
    Surface(
        modifier = modifier
            .nexusHoverTracker(hover, if (onClick != null) PointerIcon.Hand else PointerIcon.Default)
            .clip(cardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = if (hover.value.isHovered) {
                accentColor.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = NexusTheme.monospace.keycapShortcut,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Crisp custom vector-drawn NexusTS Remote Desktop Display + SSH Terminal + Server Hub emblem
 * matching the Android Adaptive Launcher Icon (`ic_launcher_foreground.xml`).
 */
@Composable
fun NexusBrandEmblem(
    size: Dp = 32.dp,
    cornerRadius: Dp = 10.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = Color(0xFF0A182D),
        border = BorderStroke(1.dp, Color(0xFF2CE5B5).copy(alpha = 0.28f)),
        modifier = modifier.size(size)
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height

            // Subtle ambient sapphire-teal background gradient
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF0D213F), Color(0xFF071120)),
                    start = Offset.Zero,
                    end = Offset(w, h)
                )
            )

            // Remote Desktop Monitor Outer Frame
            val monLeft = w * 0.17f
            val monTop = h * 0.18f
            val monW = w * 0.66f
            val monH = h * 0.48f
            drawRoundRect(
                color = Color(0xFFDCEBFF),
                topLeft = Offset(monLeft, monTop),
                size = Size(monW, monH),
                cornerRadius = CornerRadius(w * 0.09f, w * 0.09f)
            )

            // Inner Dark Screen
            val pad = w * 0.055f
            val scrLeft = monLeft + pad
            val scrTop = monTop + pad
            val scrW = monW - pad * 2f
            val scrH = monH - pad * 2f
            drawRoundRect(
                color = Color(0xFF081426),
                topLeft = Offset(scrLeft, scrTop),
                size = Size(scrW, scrH),
                cornerRadius = CornerRadius(w * 0.05f, w * 0.05f)
            )

            // Left Terminal Chevron `>_`
            val chevPath = Path().apply {
                moveTo(scrLeft + scrW * 0.14f, scrTop + scrH * 0.30f)
                lineTo(scrLeft + scrW * 0.34f, scrTop + scrH * 0.54f)
                lineTo(scrLeft + scrW * 0.14f, scrTop + scrH * 0.78f)
            }
            drawPath(
                path = chevPath,
                color = Color(0xFF2CE5B5),
                style = Stroke(
                    width = (w * 0.065f).coerceAtLeast(1.6f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawLine(
                color = Color(0xFF2CE5B5),
                start = Offset(scrLeft + scrW * 0.40f, scrTop + scrH * 0.76f),
                end = Offset(scrLeft + scrW * 0.56f, scrTop + scrH * 0.76f),
                strokeWidth = (w * 0.06f).coerceAtLeast(1.5f),
                cap = StrokeCap.Round
            )

            // Right Remote Desktop Pane Card
            drawRoundRect(
                color = Color(0xFF7AA8FF),
                topLeft = Offset(scrLeft + scrW * 0.64f, scrTop + scrH * 0.25f),
                size = Size(scrW * 0.26f, scrH * 0.52f),
                cornerRadius = CornerRadius(w * 0.035f, w * 0.035f)
            )

            // Stand Neck + Server Hub Base + Active Node Dot
            drawRect(
                color = Color(0xFF9BBDF2),
                topLeft = Offset(w * 0.45f, monTop + monH),
                size = Size(w * 0.10f, h * 0.10f)
            )
            drawRoundRect(
                color = Color(0xFFDCEBFF),
                topLeft = Offset(w * 0.30f, h * 0.75f),
                size = Size(w * 0.40f, h * 0.075f),
                cornerRadius = CornerRadius(w * 0.04f, w * 0.04f)
            )
            drawCircle(
                color = Color(0xFF2CE5B5),
                radius = (w * 0.048f).coerceAtLeast(1.5f),
                center = Offset(w * 0.50f, h * 0.788f)
            )
        }
    }
}

