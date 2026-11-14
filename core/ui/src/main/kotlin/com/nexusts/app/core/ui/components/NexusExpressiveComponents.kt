package com.nexusts.app.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
 * Desktop-grade single-surface Choice Chip (`NexusChoiceChip`) replacing Material 3 `FilterChip`.
 * Renders a crisp stadium pill with smooth tonal hover transitions and ZERO inner text-box gray bars
 * or muddy platform hover ripples in Light and Dark themes.
 */
@Composable
fun NexusChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    leadingIcon: ImageVector? = null,
    leadingIconTint: Color? = null,
    showCheckmarkWhenSelected: Boolean = false,
    enabled: Boolean = true,
    monospace: Boolean = false,
    accentColor: Color = MaterialTheme.colorScheme.secondary,
    modifier: Modifier = Modifier
) {
    val hover = rememberDesktopHoverState()
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered = hover.value.isHovered && enabled
    val pillShape = RoundedCornerShape(99.dp)

    val bgColor by animateColorAsState(
        targetValue = when {
            selected && isHovered -> lerp(MaterialTheme.colorScheme.secondaryContainer, accentColor, 0.14f)
            selected -> MaterialTheme.colorScheme.secondaryContainer
            isHovered -> lerp(MaterialTheme.colorScheme.surfaceContainerLow, accentColor, 0.10f)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "ChoiceChipBg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> accentColor.copy(alpha = if (isHovered) 0.72f else 0.48f)
            isHovered -> accentColor.copy(alpha = 0.48f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        },
        label = "ChoiceChipBorder"
    )
    val fgColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.onSecondaryContainer
            isHovered -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "ChoiceChipFg"
    )

    val effectiveIcon = leadingIcon ?: if (selected && showCheckmarkWhenSelected) Icons.Outlined.Check else null

    Surface(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .semantics {
                this.selected = selected
                this.role = Role.RadioButton
            }
            .nexusHoverTracker(hover, if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clip(pillShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = pillShape,
        color = bgColor,
        contentColor = fgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (effectiveIcon != null) {
                Icon(
                    imageVector = effectiveIcon,
                    contentDescription = null,
                    tint = leadingIconTint ?: if (selected) accentColor else fgColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = label,
                style = if (monospace) NexusTheme.monospace.keycapShortcut else MaterialTheme.typography.labelMedium,
                color = fgColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Desktop-grade single-surface Action Chip (`NexusActionChip`) replacing Material 3 `AssistChip`.
 * Renders a crisp pill button with smooth hover border/container tinting and zero nested gray boxes.
 */
@Composable
fun NexusActionChip(
    onClick: () -> Unit,
    label: String,
    leadingIcon: ImageVector? = null,
    leadingIconTint: Color? = null,
    enabled: Boolean = true,
    monospace: Boolean = false,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    val hover = rememberDesktopHoverState()
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered = hover.value.isHovered && enabled
    val pillShape = RoundedCornerShape(99.dp)

    val bgColor by animateColorAsState(
        targetValue = if (isHovered) {
            lerp(MaterialTheme.colorScheme.surfaceContainerLow, accentColor, 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "ActionChipBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isHovered) {
            accentColor.copy(alpha = 0.50f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        },
        label = "ActionChipBorder"
    )

    Surface(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .semantics { this.role = Role.Button }
            .nexusHoverTracker(hover, if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clip(pillShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = pillShape,
        color = bgColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = leadingIconTint ?: accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = label,
                style = if (monospace) NexusTheme.monospace.keycapShortcut else MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
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
    val leadInteraction = remember { MutableInteractionSource() }
    val trailInteraction = remember { MutableInteractionSource() }
    val leadShape = rememberExpressiveSplitLeadingShape(leadHover.value.isHovered)
    val trailShape = rememberExpressiveSplitTrailingShape(trailHover.value.isHovered || isMenuExpanded)
    val leadBg by animateColorAsState(
        targetValue = if (leadHover.value.isHovered) {
            lerp(containerColor, contentColor, 0.08f)
        } else {
            containerColor
        },
        label = "SplitLeadBg"
    )
    val trailBg by animateColorAsState(
        targetValue = if (trailHover.value.isHovered || isMenuExpanded) {
            lerp(containerColor, contentColor, 0.10f)
        } else {
            containerColor
        },
        label = "SplitTrailBg"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Surface(
            modifier = Modifier
                .height(40.dp)
                .nexusHoverTracker(leadHover, PointerIcon.Hand)
                .clip(leadShape)
                .clickable(
                    interactionSource = leadInteraction,
                    indication = null,
                    onClick = onPrimaryClick
                ),
            shape = leadShape,
            color = leadBg,
            contentColor = contentColor
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
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
                .height(40.dp)
                .width(34.dp)
                .nexusHoverTracker(trailHover, PointerIcon.Hand)
                .clip(trailShape)
                .clickable(
                    interactionSource = trailInteraction,
                    indication = null,
                    onClick = onTrailingMenuClick
                ),
            shape = trailShape,
            color = trailBg,
            contentColor = contentColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp)
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
    val interactionSource = remember { MutableInteractionSource() }
    val motion = NexusTheme.motion
    val cornerRadius by animateDpAsState(
        targetValue = when {
            isActive -> 13.dp
            hover.value.isHovered -> 15.dp
            else -> 20.dp
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
            isActive && hover.value.isHovered -> lerp(activeContainer, activeTint, 0.08f)
            isActive -> activeContainer
            hover.value.isHovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        },
        label = "IconBtnBg"
    )

    Surface(
        modifier = modifier
            .size(40.dp)
            .scale(scale)
            .nexusHoverTracker(hover, PointerIcon.Hand)
            .clip(RoundedCornerShape(cornerRadius))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(cornerRadius),
        color = bgColor
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isActive) activeTint else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp)
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
    val interactionSource = remember { MutableInteractionSource() }
    val cardShape = rememberMorphingContainerShape(
        state = if (hover.value.isHovered) MorphContainerState.Hovered else MorphContainerState.Resting,
        restingRadius = 22.dp,
        hoveredRadius = 28.dp
    )
    val bgColor by animateColorAsState(
        targetValue = if (hover.value.isHovered && onClick != null) {
            lerp(MaterialTheme.colorScheme.surfaceContainerLow, accentColor, 0.06f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "MetricCardBg"
    )
    Surface(
        modifier = modifier
            .nexusHoverTracker(hover, if (onClick != null) PointerIcon.Hand else PointerIcon.Default)
            .clip(cardShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        shape = cardShape,
        color = bgColor,
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
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
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
        color = Color(0xFF0A1630),
        border = BorderStroke(1.dp, Color(0xFF60A5FA).copy(alpha = 0.24f)),
        modifier = modifier.size(size)
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height

            // Subtle ambient midnight-sapphire background gradient
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF10244A), Color(0xFF081124)),
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
                color = Color(0xFFE2EEFF),
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
                color = Color(0xFF081328),
                topLeft = Offset(scrLeft, scrTop),
                size = Size(scrW, scrH),
                cornerRadius = CornerRadius(w * 0.05f, w * 0.05f)
            )

            // Left Terminal Chevron `>_` in Electric Sky-Cyan
            val chevPath = Path().apply {
                moveTo(scrLeft + scrW * 0.14f, scrTop + scrH * 0.30f)
                lineTo(scrLeft + scrW * 0.34f, scrTop + scrH * 0.54f)
                lineTo(scrLeft + scrW * 0.14f, scrTop + scrH * 0.78f)
            }
            drawPath(
                path = chevPath,
                color = Color(0xFF38BDF8),
                style = Stroke(
                    width = (w * 0.065f).coerceAtLeast(1.6f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawLine(
                color = Color(0xFF38BDF8),
                start = Offset(scrLeft + scrW * 0.40f, scrTop + scrH * 0.76f),
                end = Offset(scrLeft + scrW * 0.56f, scrTop + scrH * 0.76f),
                strokeWidth = (w * 0.06f).coerceAtLeast(1.5f),
                cap = StrokeCap.Round
            )

            // Right Remote Desktop Pane Card in Royal Sapphire
            drawRoundRect(
                color = Color(0xFF3B82F6),
                topLeft = Offset(scrLeft + scrW * 0.64f, scrTop + scrH * 0.25f),
                size = Size(scrW * 0.26f, scrH * 0.52f),
                cornerRadius = CornerRadius(w * 0.035f, w * 0.035f)
            )

            // Stand Neck + Server Hub Base + Active Sky-Blue Node Dot
            drawRect(
                color = Color(0xFF93C5FD),
                topLeft = Offset(w * 0.45f, monTop + monH),
                size = Size(w * 0.10f, h * 0.10f)
            )
            drawRoundRect(
                color = Color(0xFFE2EEFF),
                topLeft = Offset(w * 0.30f, h * 0.75f),
                size = Size(w * 0.40f, h * 0.075f),
                cornerRadius = CornerRadius(w * 0.04f, w * 0.04f)
            )
            drawCircle(
                color = Color(0xFF38BDF8),
                radius = (w * 0.048f).coerceAtLeast(1.5f),
                center = Offset(w * 0.50f, h * 0.788f)
            )
        }
    }
}

/**
 * Hardware-accelerated crisp vector Country/Language Flag Badge rendered directly on Compose [Canvas]
 * so flags appear sharp at every DPI across all Googlebook OS & Android Tablet builds regardless of
 * system colour-emoji font coverage (`en`, `de`, `it`, `es`, `el`, `fr`).
 */
@Composable
fun NexusLanguageFlagBadge(
    languageCode: String,
    width: Dp = 30.dp,
    height: Dp = 20.dp,
    cornerRadius: Dp = 5.dp,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        shape = shape,
        color = Color.White,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier
            .width(width)
            .height(height)
            .clip(shape)
    ) {
        Canvas(
            modifier = Modifier
                .width(width)
                .height(height)
        ) {
            val w = size.width
            val h = size.height
            when (languageCode.lowercase().take(2)) {
                "de" -> {
                    val band = h / 3f
                    drawRect(color = Color(0xFF14181F), topLeft = Offset(0f, 0f), size = Size(w, band))
                    drawRect(color = Color(0xFFDD0000), topLeft = Offset(0f, band), size = Size(w, band))
                    drawRect(color = Color(0xFFFFCE00), topLeft = Offset(0f, band * 2f), size = Size(w, band))
                }
                "it" -> {
                    val band = w / 3f
                    drawRect(color = Color(0xFF009246), topLeft = Offset(0f, 0f), size = Size(band, h))
                    drawRect(color = Color(0xFFFFFFFF), topLeft = Offset(band, 0f), size = Size(band, h))
                    drawRect(color = Color(0xFFCE2B37), topLeft = Offset(band * 2f, 0f), size = Size(band, h))
                }
                "fr" -> {
                    val band = w / 3f
                    drawRect(color = Color(0xFF002395), topLeft = Offset(0f, 0f), size = Size(band, h))
                    drawRect(color = Color(0xFFFFFFFF), topLeft = Offset(band, 0f), size = Size(band, h))
                    drawRect(color = Color(0xFFED2939), topLeft = Offset(band * 2f, 0f), size = Size(band, h))
                }
                "es" -> {
                    drawRect(color = Color(0xFFAA151B), topLeft = Offset(0f, 0f), size = Size(w, h * 0.25f))
                    drawRect(color = Color(0xFFF1BF00), topLeft = Offset(0f, h * 0.25f), size = Size(w, h * 0.50f))
                    drawRect(color = Color(0xFFAA151B), topLeft = Offset(0f, h * 0.75f), size = Size(w, h * 0.25f))
                    // Subtle heraldic shield accent on the left third
                    drawRoundRect(
                        color = Color(0xFFAA151B),
                        topLeft = Offset(w * 0.24f, h * 0.36f),
                        size = Size(w * 0.11f, h * 0.28f),
                        cornerRadius = CornerRadius(w * 0.04f, w * 0.04f)
                    )
                }
                "el" -> {
                    val blue = Color(0xFF0D5EAF)
                    val stripeH = h / 9f
                    for (i in 0 until 9) {
                        drawRect(
                            color = if (i % 2 == 0) blue else Color.White,
                            topLeft = Offset(0f, i * stripeH),
                            size = Size(w, stripeH + 0.5f)
                        )
                    }
                    val cantonSize = stripeH * 5f
                    drawRect(color = blue, topLeft = Offset(0f, 0f), size = Size(cantonSize, cantonSize))
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(cantonSize * 0.4f, 0f),
                        size = Size(cantonSize * 0.2f, cantonSize)
                    )
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(0f, cantonSize * 0.4f),
                        size = Size(cantonSize, cantonSize * 0.2f)
                    )
                }
                else -> {
                    // "en": Crisp Union Jack
                    val navy = Color(0xFF012169)
                    val red = Color(0xFFC8102E)
                    drawRect(color = navy, topLeft = Offset.Zero, size = Size(w, h))
                    // White diagonal saltires
                    drawLine(
                        color = Color.White,
                        start = Offset(0f, 0f),
                        end = Offset(w, h),
                        strokeWidth = h * 0.22f
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(w, 0f),
                        end = Offset(0f, h),
                        strokeWidth = h * 0.22f
                    )
                    // Red diagonal saltires
                    drawLine(
                        color = red,
                        start = Offset(0f, 0f),
                        end = Offset(w, h),
                        strokeWidth = h * 0.10f
                    )
                    drawLine(
                        color = red,
                        start = Offset(w, 0f),
                        end = Offset(0f, h),
                        strokeWidth = h * 0.10f
                    )
                    // White central cross
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(0f, h * 0.33f),
                        size = Size(w, h * 0.34f)
                    )
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(w * 0.39f, 0f),
                        size = Size(w * 0.22f, h)
                    )
                    // Red St George's cross
                    drawRect(
                        color = red,
                        topLeft = Offset(0f, h * 0.40f),
                        size = Size(w, h * 0.20f)
                    )
                    drawRect(
                        color = red,
                        topLeft = Offset(w * 0.43f, 0f),
                        size = Size(w * 0.14f, h)
                    )
                }
            }
        }
    }
}

