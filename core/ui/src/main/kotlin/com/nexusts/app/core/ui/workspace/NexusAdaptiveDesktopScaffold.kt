package com.nexusts.app.core.ui.workspace

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.input.LocalNexusShortcutEngine
import com.nexusts.app.core.ui.input.NexusShortcutAction
import com.nexusts.app.core.ui.input.nexusHoverTracker
import com.nexusts.app.core.ui.input.nexusRemoteKeyPassthrough
import com.nexusts.app.core.ui.input.nexusResizeCursor
import com.nexusts.app.core.ui.input.RawHardwareKeyPacket
import com.nexusts.app.core.ui.input.rememberDesktopHoverState
import com.nexusts.app.core.ui.theme.NexusTheme

@Immutable
data class NexusRailDestination(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val badgeCount: Int = 0
)

/**
 * Adaptive Googlebook OS & Tablet Desktop Window Scaffold implementing:
 * - Expressive Navigation Rail
 * - Collapsible & drag-resizable Sidebar Document/Folder Tree View
 * - Central Multi-Tabbed & Split-Pane Tiling Canvas
 * - Collapsible & drag-resizable Contextual Property Inspector
 * - Collapsible & drag-resizable Bottom Status / Audit Log / Transfer Queue / Broadcast Drawer
 * - Floating Quick-Connect Command Palette (`Ctrl/Cmd + K`)
 */
@Composable
fun NexusAdaptiveDesktopScaffold(
    destinations: List<NexusRailDestination> = emptyList(),
    selectedDestinationId: String = "connections",
    onSelectDestination: (String) -> Unit = {},
    isSidebarVisible: Boolean,
    isInspectorVisible: Boolean,
    isBottomDrawerVisible: Boolean,
    remoteKeyCaptureActive: Boolean,
    onShortcutTriggered: (NexusShortcutAction) -> Unit,
    onForwardRawKeyToActiveSession: (RawHardwareKeyPacket) -> Boolean,
    topWindowBar: @Composable () -> Unit,
    sidebarTreeContent: @Composable () -> Unit,
    mainWorkspaceContent: @Composable () -> Unit,
    propertyInspectorContent: @Composable () -> Unit,
    bottomStatusDrawerContent: @Composable () -> Unit,
    floatingOverlayContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    initialSidebarWidth: Dp = 248.dp,
    initialInspectorWidth: Dp = 320.dp,
    initialBottomDrawerHeight: Dp = 220.dp
) {
    val shortcutEngine = LocalNexusShortcutEngine.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val motion = NexusTheme.motion
    val layoutPrefs = remember(context) {
        runCatching {
            context.applicationContext.getSharedPreferences("nexusts_desktop_layout_prefs", Context.MODE_PRIVATE)
        }.getOrNull()
    }

    var sidebarWidthDp by remember(layoutPrefs) {
        mutableFloatStateOf(
            (layoutPrefs?.getFloat("sidebar_width_dp", initialSidebarWidth.value) ?: initialSidebarWidth.value)
                .coerceIn(190f, 440f)
        )
    }
    var inspectorWidthDp by remember(layoutPrefs) {
        mutableFloatStateOf(
            (layoutPrefs?.getFloat("inspector_width_dp", initialInspectorWidth.value) ?: initialInspectorWidth.value)
                .coerceIn(270f, 540f)
        )
    }
    var bottomHeightDp by remember(layoutPrefs) {
        mutableFloatStateOf(
            (layoutPrefs?.getFloat("bottom_height_dp", initialBottomDrawerHeight.value) ?: initialBottomDrawerHeight.value)
                .coerceIn(150f, 480f)
        )
    }

    var isDraggingSidebar by remember { mutableStateOf(false) }
    var isDraggingInspector by remember { mutableStateOf(false) }
    var isDraggingBottom by remember { mutableStateOf(false) }

    val animatedSidebarWidth by animateDpAsState(
        targetValue = sidebarWidthDp.dp,
        animationSpec = motion.spatialFastDp,
        label = "SidebarWidth"
    )
    val animatedInspectorWidth by animateDpAsState(
        targetValue = inspectorWidthDp.dp,
        animationSpec = motion.spatialFastDp,
        label = "InspectorWidth"
    )
    val animatedBottomHeight by animateDpAsState(
        targetValue = bottomHeightDp.dp,
        animationSpec = motion.spatialFastDp,
        label = "BottomDrawerHeight"
    )

    val effectiveSidebarWidth = if (isDraggingSidebar) sidebarWidthDp.dp else animatedSidebarWidth
    val effectiveInspectorWidth = if (isDraggingInspector) inspectorWidthDp.dp else animatedInspectorWidth
    val effectiveBottomHeight = if (isDraggingBottom) bottomHeightDp.dp else animatedBottomHeight
    val hairlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nexusRemoteKeyPassthrough(
                captureEnabled = remoteKeyCaptureActive,
                shortcutEngine = shortcutEngine,
                onHostShortcut = onShortcutTriggered,
                onForwardToRemote = onForwardRawKeyToActiveSession
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            topWindowBar()

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Flush Far-Left Collapsible & Drag-Resizable Connection & Folder Explorer
                AnimatedVisibility(
                    visible = isSidebarVisible,
                    enter = expandHorizontally(animationSpec = motion.spatialIntSize) + fadeIn(),
                    exit = shrinkHorizontally(animationSpec = motion.spatialIntSize) + fadeOut()
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        Surface(
                            modifier = Modifier
                                .width(effectiveSidebarWidth)
                                .fillMaxHeight(),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 1.dp
                        ) {
                            sidebarTreeContent()
                        }

                        PanelResizeHandle(
                            horizontal = true,
                            hairlineColor = hairlineColor,
                            onDragActiveChanged = { active ->
                                isDraggingSidebar = active
                                if (!active) {
                                    layoutPrefs?.edit()?.putFloat("sidebar_width_dp", sidebarWidthDp)?.apply()
                                }
                            },
                            onDeltaDp = { delta ->
                                sidebarWidthDp = (sidebarWidthDp + delta).coerceIn(190f, 440f)
                            },
                            onDoubleClickReset = {
                                sidebarWidthDp = initialSidebarWidth.value
                                layoutPrefs?.edit()?.putFloat("sidebar_width_dp", sidebarWidthDp)?.apply()
                            },
                            densityPxPerDp = density.density
                        )
                    }
                }

                // Center Workspace Column (Split-Pane Session Canvas + Flush Docked Bottom Log/Transfer Drawer)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        mainWorkspaceContent()
                    }

                    AnimatedVisibility(
                        visible = isBottomDrawerVisible,
                        enter = expandVertically(animationSpec = motion.spatialIntSize) + fadeIn(),
                        exit = shrinkVertically(animationSpec = motion.spatialIntSize) + fadeOut()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            PanelResizeHandle(
                                horizontal = false,
                                hairlineColor = hairlineColor,
                                onDragActiveChanged = { active ->
                                    isDraggingBottom = active
                                    if (!active) {
                                        layoutPrefs?.edit()?.putFloat("bottom_height_dp", bottomHeightDp)?.apply()
                                    }
                                },
                                onDeltaDp = { delta ->
                                    bottomHeightDp = (bottomHeightDp - delta).coerceIn(150f, 480f)
                                },
                                onDoubleClickReset = {
                                    bottomHeightDp = initialBottomDrawerHeight.value
                                    layoutPrefs?.edit()?.putFloat("bottom_height_dp", bottomHeightDp)?.apply()
                                },
                                densityPxPerDp = density.density
                            )
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(effectiveBottomHeight),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                tonalElevation = 1.dp
                            ) {
                                bottomStatusDrawerContent()
                            }
                        }
                    }
                }

                // Flush Right-Hand Contextual Property Inspector
                AnimatedVisibility(
                    visible = isInspectorVisible,
                    enter = expandHorizontally(animationSpec = motion.spatialIntSize) + fadeIn(),
                    exit = shrinkHorizontally(animationSpec = motion.spatialIntSize) + fadeOut()
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        PanelResizeHandle(
                            horizontal = true,
                            hairlineColor = hairlineColor,
                            onDragActiveChanged = { active ->
                                isDraggingInspector = active
                                if (!active) {
                                    layoutPrefs?.edit()?.putFloat("inspector_width_dp", inspectorWidthDp)?.apply()
                                }
                            },
                            onDeltaDp = { delta ->
                                inspectorWidthDp = (inspectorWidthDp - delta).coerceIn(270f, 540f)
                            },
                            onDoubleClickReset = {
                                inspectorWidthDp = initialInspectorWidth.value
                                layoutPrefs?.edit()?.putFloat("inspector_width_dp", inspectorWidthDp)?.apply()
                            },
                            densityPxPerDp = density.density
                        )
                        Surface(
                            modifier = Modifier
                                .width(effectiveInspectorWidth)
                                .fillMaxHeight(),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 1.dp
                        ) {
                            propertyInspectorContent()
                        }
                    }
                }
            }
        }

        // Floating Quick-Connect Command Palette & Modal Overlays
        floatingOverlayContent()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelResizeHandle(
    horizontal: Boolean,
    hairlineColor: androidx.compose.ui.graphics.Color,
    onDragActiveChanged: (Boolean) -> Unit,
    onDeltaDp: (Float) -> Unit,
    onDoubleClickReset: () -> Unit,
    densityPxPerDp: Float
) {
    val hover = rememberDesktopHoverState()
    var isDragging by remember { mutableStateOf(false) }
    val isHighlighted = hover.value.isHovered || isDragging
    val pillColor by animateColorAsState(
        targetValue = if (isHighlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        },
        label = "ResizeHandleTint"
    )
    val trackTint by animateColorAsState(
        targetValue = if (isHighlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        } else {
            androidx.compose.ui.graphics.Color.Transparent
        },
        label = "ResizeTrackTint"
    )

    Box(
        modifier = if (horizontal) {
            Modifier
                .width(7.dp)
                .fillMaxHeight()
        } else {
            Modifier
                .height(7.dp)
                .fillMaxWidth()
        }
            .background(trackTint)
            .nexusHoverTracker(hover, PointerIcon.Hand)
            .nexusResizeCursor(horizontalResize = horizontal)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onDoubleClick = onDoubleClickReset
            )
            .pointerInput(horizontal, densityPxPerDp) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        onDragActiveChanged(true)
                    },
                    onDragEnd = {
                        isDragging = false
                        onDragActiveChanged(false)
                    },
                    onDragCancel = {
                        isDragging = false
                        onDragActiveChanged(false)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val px = if (horizontal) dragAmount.x else dragAmount.y
                    onDeltaDp(px / densityPxPerDp.coerceAtLeast(1f))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Crisp 1dp structural divider line across the full seam
        Box(
            modifier = if (horizontal) {
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
            } else {
                Modifier
                    .height(1.dp)
                    .fillMaxWidth()
            }
                .background(if (isHighlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.38f) else hairlineColor)
        )

        // Centered tactile grip pill
        Box(
            modifier = if (horizontal) {
                Modifier
                    .width(if (isHighlighted) 3.dp else 2.dp)
                    .height(if (isHighlighted) 40.dp else 28.dp)
            } else {
                Modifier
                    .height(if (isHighlighted) 3.dp else 2.dp)
                    .width(if (isHighlighted) 40.dp else 28.dp)
            }
                .clip(RoundedCornerShape(99.dp))
                .background(pillColor)
        )
    }
}
