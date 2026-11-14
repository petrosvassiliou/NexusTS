package com.nexusts.app.core.ui.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.input.LocalNexusShortcutEngine
import com.nexusts.app.core.ui.input.NexusShortcutAction
import com.nexusts.app.core.ui.input.nexusRemoteKeyPassthrough
import com.nexusts.app.core.ui.input.nexusResizeCursor
import com.nexusts.app.core.ui.input.RawHardwareKeyPacket
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
    destinations: List<NexusRailDestination>,
    selectedDestinationId: String,
    onSelectDestination: (String) -> Unit,
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
    initialSidebarWidth: Dp = 324.dp,
    initialInspectorWidth: Dp = 340.dp,
    initialBottomDrawerHeight: Dp = 230.dp
) {
    val shortcutEngine = LocalNexusShortcutEngine.current
    val density = LocalDensity.current
    val motion = NexusTheme.motion

    var sidebarWidthDp by remember { mutableFloatStateOf(initialSidebarWidth.value) }
    var inspectorWidthDp by remember { mutableFloatStateOf(initialInspectorWidth.value) }
    var bottomHeightDp by remember { mutableFloatStateOf(initialBottomDrawerHeight.value) }

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
                // Spacious Google Material 3 Navigation Rail with 24.dp Icons & Clear Labels
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(96.dp)
                ) {
                    Spacer(modifier = Modifier.height(14.dp))
                    destinations.forEach { dest ->
                        val selected = dest.id == selectedDestinationId
                        NavigationRailItem(
                            selected = selected,
                            onClick = { onSelectDestination(dest.id) },
                            icon = {
                                if (dest.badgeCount > 0) {
                                    androidx.compose.material3.BadgedBox(
                                        badge = {
                                            androidx.compose.material3.Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text(dest.badgeCount.toString())
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = dest.icon,
                                            contentDescription = dest.label,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = dest.label,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = dest.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                            ),
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                    }
                }

                // Collapsible & Drag-Resizable Vault Document / Folder Tree Sidebar
                AnimatedVisibility(
                    visible = isSidebarVisible,
                    enter = expandHorizontally(animationSpec = motion.spatialIntSize) + fadeIn(),
                    exit = shrinkHorizontally(animationSpec = motion.spatialIntSize) + fadeOut()
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        Surface(
                            modifier = Modifier
                                .width(animatedSidebarWidth)
                                .fillMaxHeight()
                                .padding(top = 6.dp, bottom = 6.dp, start = 4.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 1.dp
                        ) {
                            sidebarTreeContent()
                        }

                        // Sidebar Resize Handle
                        PanelResizeHandle(
                            horizontal = true,
                            onDeltaDp = { delta ->
                                sidebarWidthDp = (sidebarWidthDp + delta).coerceIn(250f, 560f)
                            },
                            densityPxPerDp = density.density
                        )
                    }
                }

                // Center Workspace Column (Split-Pane Session Canvas + Bottom Log/Transfer Drawer)
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
                                onDeltaDp = { delta ->
                                    bottomHeightDp = (bottomHeightDp - delta).coerceIn(150f, 480f)
                                },
                                densityPxPerDp = density.density
                            )
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(animatedBottomHeight)
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 2.dp
                            ) {
                                bottomStatusDrawerContent()
                            }
                        }
                    }
                }

                // Right-Hand Contextual Property Inspector
                AnimatedVisibility(
                    visible = isInspectorVisible,
                    enter = expandHorizontally(animationSpec = motion.spatialIntSize) + fadeIn(),
                    exit = shrinkHorizontally(animationSpec = motion.spatialIntSize) + fadeOut()
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        PanelResizeHandle(
                            horizontal = true,
                            onDeltaDp = { delta ->
                                inspectorWidthDp = (inspectorWidthDp - delta).coerceIn(270f, 540f)
                            },
                            densityPxPerDp = density.density
                        )
                        Surface(
                            modifier = Modifier
                                .width(animatedInspectorWidth)
                                .fillMaxHeight()
                                .padding(top = 6.dp, bottom = 6.dp, end = 8.dp),
                            shape = RoundedCornerShape(20.dp),
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

@Composable
private fun PanelResizeHandle(
    horizontal: Boolean,
    onDeltaDp: (Float) -> Unit,
    densityPxPerDp: Float
) {
    val pillColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        label = "ResizeHandleTint"
    )
    Box(
        modifier = if (horizontal) {
            Modifier
                .width(8.dp)
                .fillMaxHeight()
        } else {
            Modifier
                .height(8.dp)
                .fillMaxWidth()
        }
            .nexusResizeCursor(horizontalResize = horizontal)
            .pointerInput(horizontal, densityPxPerDp) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val px = if (horizontal) dragAmount.x else dragAmount.y
                    onDeltaDp(px / densityPxPerDp.coerceAtLeast(1f))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = if (horizontal) {
                Modifier
                    .width(2.dp)
                    .height(32.dp)
            } else {
                Modifier
                    .height(2.dp)
                    .width(32.dp)
            }
                .clip(RoundedCornerShape(99.dp))
                .background(pillColor)
        )
    }
}
