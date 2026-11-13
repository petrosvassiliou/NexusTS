package com.nexusts.app.core.ui.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.components.parseHexColorOrNull
import com.nexusts.app.core.ui.input.nexusDesktopPointerButtons
import com.nexusts.app.core.ui.input.nexusHoverTracker
import com.nexusts.app.core.ui.input.nexusResizeCursor
import com.nexusts.app.core.ui.input.rememberDesktopHoverState
import com.nexusts.app.core.ui.theme.MorphContainerState
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.ui.theme.rememberExpressiveTabShape
import com.nexusts.app.core.ui.theme.rememberMorphingContainerShape

@ImmutableTabStrings
data class SplitWorkspaceLabels(
    val splitRightLabel: String,
    val splitDownLabel: String,
    val detachNewWindowLabel: String,
    val detachToDisplayPrefix: String,
    val toggleBroadcastLabel: String,
    val reconnectTabLabel: String = "Reconnect",
    val closeTabLabel: String,
    val emptyWorkspaceTitle: String,
    val emptyWorkspaceSubtitle: String
)

private annotation class ImmutableTabStrings

/**
 * Recursive Multi-Pane & Multi-Tab Tiling Renderer built on Material 3 Expressive motion & morphing shapes.
 */
@Composable
fun SplitPaneWorkspaceContainer(
    node: PaneNode,
    focusedPaneId: String,
    connectedDisplays: List<ExternalDisplayDescriptor>,
    labels: SplitWorkspaceLabels,
    onFocusPane: (String) -> Unit,
    onSelectTab: (paneId: String, tabId: String) -> Unit,
    onCloseTab: (tabId: String) -> Unit,
    onSplitPane: (paneId: String, orientation: SplitOrientation) -> Unit,
    onResizeSplit: (splitId: String, newRatio: Float) -> Unit,
    onDetachTabToWindow: (tab: WorkspaceSessionTab, displayId: Int?) -> Unit,
    onToggleTabBroadcast: (tab: WorkspaceSessionTab) -> Unit,
    onReconnectTab: ((tab: WorkspaceSessionTab) -> Unit)? = null,
    onCloseEmptyPane: ((paneId: String) -> Unit)? = null,
    isInsideSplit: Boolean = false,
    emptyStateContent: @Composable () -> Unit,
    sessionRenderer: @Composable (WorkspaceSessionTab, isPaneFocused: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    when (node) {
        is PaneNode.Leaf -> {
            if (node.tabs.isEmpty()) {
                val isFocused = node.nodeId == focusedPaneId
                val focusBorder = if (isFocused && isInsideSplit) {
                    NexusTheme.extendedColors.activePaneFocusRing.copy(alpha = 0.65f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                }
                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .border(
                            width = if (isFocused && isInsideSplit) 1.5.dp else 1.dp,
                            color = focusBorder,
                            shape = MaterialTheme.shapes.large
                        )
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onFocusPane(node.nodeId) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    emptyStateContent()
                    if (isInsideSplit && onCloseEmptyPane != null) {
                        IconButton(
                            onClick = { onCloseEmptyPane(node.nodeId) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = labels.closeTabLabel,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LeafSessionPane(
                    leaf = node,
                    isFocused = node.nodeId == focusedPaneId,
                    connectedDisplays = connectedDisplays,
                    labels = labels,
                    onFocus = { onFocusPane(node.nodeId) },
                    onSelectTab = { tabId -> onSelectTab(node.nodeId, tabId) },
                    onCloseTab = onCloseTab,
                    onSplit = { orientation -> onSplitPane(node.nodeId, orientation) },
                    onDetachToWindow = onDetachTabToWindow,
                    onToggleBroadcast = onToggleTabBroadcast,
                    onReconnectTab = onReconnectTab,
                    sessionRenderer = sessionRenderer,
                    modifier = modifier
                )
            }
        }

        is PaneNode.Split -> {
            val density = LocalDensity.current
            BoxWithConstraints(modifier = modifier.fillMaxSize()) {
                val totalWidthPx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
                val totalHeightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }

                if (node.orientation == SplitOrientation.HORIZONTAL) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        SplitPaneWorkspaceContainer(
                            node = node.first,
                            focusedPaneId = focusedPaneId,
                            connectedDisplays = connectedDisplays,
                            labels = labels,
                            onFocusPane = onFocusPane,
                            onSelectTab = onSelectTab,
                            onCloseTab = onCloseTab,
                            onSplitPane = onSplitPane,
                            onResizeSplit = onResizeSplit,
                            onDetachTabToWindow = onDetachTabToWindow,
                            onToggleTabBroadcast = onToggleTabBroadcast,
                            onReconnectTab = onReconnectTab,
                            onCloseEmptyPane = onCloseEmptyPane,
                            isInsideSplit = true,
                            emptyStateContent = emptyStateContent,
                            sessionRenderer = sessionRenderer,
                            modifier = Modifier
                                .weight(node.splitRatio)
                                .fillMaxHeight()
                        )

                        ExpressiveSplitDivider(
                            isHorizontalSplit = true,
                            onDragDeltaPx = { dx ->
                                val nextRatio = node.splitRatio + (dx / totalWidthPx)
                                onResizeSplit(node.nodeId, nextRatio)
                            },
                            onDoubleClickReset = { onResizeSplit(node.nodeId, 0.5f) }
                        )

                        SplitPaneWorkspaceContainer(
                            node = node.second,
                            focusedPaneId = focusedPaneId,
                            connectedDisplays = connectedDisplays,
                            labels = labels,
                            onFocusPane = onFocusPane,
                            onSelectTab = onSelectTab,
                            onCloseTab = onCloseTab,
                            onSplitPane = onSplitPane,
                            onResizeSplit = onResizeSplit,
                            onDetachTabToWindow = onDetachTabToWindow,
                            onToggleTabBroadcast = onToggleTabBroadcast,
                            onReconnectTab = onReconnectTab,
                            onCloseEmptyPane = onCloseEmptyPane,
                            isInsideSplit = true,
                            emptyStateContent = emptyStateContent,
                            sessionRenderer = sessionRenderer,
                            modifier = Modifier
                                .weight(1f - node.splitRatio)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        SplitPaneWorkspaceContainer(
                            node = node.first,
                            focusedPaneId = focusedPaneId,
                            connectedDisplays = connectedDisplays,
                            labels = labels,
                            onFocusPane = onFocusPane,
                            onSelectTab = onSelectTab,
                            onCloseTab = onCloseTab,
                            onSplitPane = onSplitPane,
                            onResizeSplit = onResizeSplit,
                            onDetachTabToWindow = onDetachTabToWindow,
                            onToggleTabBroadcast = onToggleTabBroadcast,
                            onReconnectTab = onReconnectTab,
                            onCloseEmptyPane = onCloseEmptyPane,
                            isInsideSplit = true,
                            emptyStateContent = emptyStateContent,
                            sessionRenderer = sessionRenderer,
                            modifier = Modifier
                                .weight(node.splitRatio)
                                .fillMaxWidth()
                        )

                        ExpressiveSplitDivider(
                            isHorizontalSplit = false,
                            onDragDeltaPx = { dy ->
                                val nextRatio = node.splitRatio + (dy / totalHeightPx)
                                onResizeSplit(node.nodeId, nextRatio)
                            },
                            onDoubleClickReset = { onResizeSplit(node.nodeId, 0.5f) }
                        )

                        SplitPaneWorkspaceContainer(
                            node = node.second,
                            focusedPaneId = focusedPaneId,
                            connectedDisplays = connectedDisplays,
                            labels = labels,
                            onFocusPane = onFocusPane,
                            onSelectTab = onSelectTab,
                            onCloseTab = onCloseTab,
                            onSplitPane = onSplitPane,
                            onResizeSplit = onResizeSplit,
                            onDetachTabToWindow = onDetachTabToWindow,
                            onToggleTabBroadcast = onToggleTabBroadcast,
                            onReconnectTab = onReconnectTab,
                            onCloseEmptyPane = onCloseEmptyPane,
                            isInsideSplit = true,
                            emptyStateContent = emptyStateContent,
                            sessionRenderer = sessionRenderer,
                            modifier = Modifier
                                .weight(1f - node.splitRatio)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LeafSessionPane(
    leaf: PaneNode.Leaf,
    isFocused: Boolean,
    connectedDisplays: List<ExternalDisplayDescriptor>,
    labels: SplitWorkspaceLabels,
    onFocus: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onSplit: (SplitOrientation) -> Unit,
    onDetachToWindow: (WorkspaceSessionTab, Int?) -> Unit,
    onToggleBroadcast: (WorkspaceSessionTab) -> Unit,
    onReconnectTab: ((WorkspaceSessionTab) -> Unit)? = null,
    sessionRenderer: @Composable (WorkspaceSessionTab, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val extendedColors = NexusTheme.extendedColors
    val motion = NexusTheme.motion
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) {
            extendedColors.activePaneFocusRing.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        },
        animationSpec = createColorSpec(),
        label = "PaneFocusBorderColor"
    )
    val paneShape = rememberMorphingContainerShape(
        state = if (isFocused) MorphContainerState.DockedTile else MorphContainerState.Resting,
        restingRadius = 16.dp,
        dockedRadius = 14.dp
    )

    Surface(
        modifier = modifier
            .padding(4.dp)
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = borderColor,
                shape = paneShape
            )
            .clip(paneShape)
            .clickable(interactionSource = null, indication = null, onClick = onFocus),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = paneShape
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Expressive Multi-Tab Header Strip + Tiling Action Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    leaf.tabs.forEach { tab ->
                        val isSelected = tab.tabId == leaf.activeTab?.tabId
                        ExpressiveSessionTabChip(
                            tab = tab,
                            isSelected = isSelected,
                            connectedDisplays = connectedDisplays,
                            labels = labels,
                            onSelect = { onSelectTab(tab.tabId) },
                            onClose = { onCloseTab(tab.tabId) },
                            onSplitRight = {
                                onSelectTab(tab.tabId)
                                onSplit(SplitOrientation.HORIZONTAL)
                            },
                            onSplitDown = {
                                onSelectTab(tab.tabId)
                                onSplit(SplitOrientation.VERTICAL)
                            },
                            onDetachToWindow = { displayId -> onDetachToWindow(tab, displayId) },
                            onToggleBroadcast = { onToggleBroadcast(tab) },
                            onReconnect = onReconnectTab?.let { cb -> { cb(tab) } }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                leaf.activeTab?.let { active ->
                    if (onReconnectTab != null) {
                        IconButton(
                            onClick = { onReconnectTab(active) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = labels.reconnectTabLabel,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { onSplit(SplitOrientation.HORIZONTAL) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VerticalSplit,
                        contentDescription = labels.splitRightLabel,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { onSplit(SplitOrientation.VERTICAL) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ViewStream,
                        contentDescription = labels.splitDownLabel,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                leaf.activeTab?.let { active ->
                    IconButton(
                        onClick = { onDetachToWindow(active, null) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = labels.detachNewWindowLabel,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Environment Guardrail & Broadcast Input Banner
            val activeTab = leaf.activeTab
            if (activeTab != null) {
                val isProd = activeTab.environmentTag.equals("PROD", ignoreCase = true) ||
                    activeTab.environmentTag.equals("PRODUCTION", ignoreCase = true)
                if (isProd || activeTab.broadcastEnabled) {
                    val bannerBg = if (isProd) {
                        extendedColors.envProductionBanner.copy(alpha = 0.22f)
                    } else {
                        extendedColors.broadcastActiveBanner.copy(alpha = 0.14f)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bannerBg)
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (isProd) {
                            Text(
                                text = "PRODUCTION ENVIRONMENT • ${activeTab.subtitleHost}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFB3C0)
                            )
                        } else {
                            Text(
                                text = activeTab.subtitleHost,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (activeTab.broadcastEnabled) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Podcasts,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = extendedColors.broadcastActiveBanner
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "BROADCAST",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = extendedColors.broadcastActiveBanner
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    sessionRenderer(activeTab, isFocused)
                }
            }
        }
    }
}

@Composable
private fun ExpressiveSessionTabChip(
    tab: WorkspaceSessionTab,
    isSelected: Boolean,
    connectedDisplays: List<ExternalDisplayDescriptor>,
    labels: SplitWorkspaceLabels,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onSplitRight: () -> Unit,
    onSplitDown: () -> Unit,
    onDetachToWindow: (Int?) -> Unit,
    onToggleBroadcast: () -> Unit,
    onReconnect: (() -> Unit)? = null
) {
    val hoverState = rememberDesktopHoverState()
    var contextMenuOpen by remember { mutableStateOf(false) }
    val customAccent = parseHexColorOrNull(tab.customColorHex)
    val tabShape = rememberExpressiveTabShape(
        isSelected = isSelected,
        isHovered = hoverState.value.isHovered
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected && customAccent != null -> customAccent.copy(alpha = 0.20f)
            isSelected -> MaterialTheme.colorScheme.secondaryContainer
            hoverState.value.isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
            customAccent != null -> customAccent.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        animationSpec = createColorSpec(),
        label = "TabChipBg"
    )

    Box {
        Surface(
            modifier = Modifier
                .height(32.dp)
                .nexusHoverTracker(hoverState, PointerIcon.Hand)
                .nexusDesktopPointerButtons(
                    onSecondaryClick = { contextMenuOpen = true },
                    onTertiaryMiddleClick = { onClose() }
                )
                .clip(tabShape)
                .clickable(onClick = onSelect),
            shape = tabShape,
            color = bgColor,
            border = if (isSelected) {
                BorderStroke(1.dp, (customAccent ?: MaterialTheme.colorScheme.secondary).copy(alpha = 0.55f))
            } else {
                null
            }
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (customAccent != null) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(customAccent)
                    )
                }

                // Live Status Indicator Dot
                val statusColor = statusDotColor(tab.status)
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )

                Icon(
                    imageVector = protocolIcon(tab.protocol),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = customAccent ?: protocolAccentColor(tab.protocol)
                )

                Text(
                    text = tab.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                if (tab.broadcastEnabled) {
                    Icon(
                        imageVector = Icons.Outlined.Podcasts,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = NexusTheme.extendedColors.broadcastActiveBanner
                    )
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = labels.closeTabLabel,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        DropdownMenu(
            expanded = contextMenuOpen,
            onDismissRequest = { contextMenuOpen = false }
        ) {
            if (onReconnect != null) {
                DropdownMenuItem(
                    text = { Text(labels.reconnectTabLabel) },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = {
                        contextMenuOpen = false
                        onReconnect()
                    }
                )
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text(labels.splitRightLabel) },
                leadingIcon = { Icon(Icons.Outlined.VerticalSplit, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onSplitRight()
                }
            )
            DropdownMenuItem(
                text = { Text(labels.splitDownLabel) },
                leadingIcon = { Icon(Icons.Outlined.ViewStream, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onSplitDown()
                }
            )
            DropdownMenuItem(
                text = { Text(labels.detachNewWindowLabel) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onDetachToWindow(null)
                }
            )
            connectedDisplays.filter { !it.isDefaultDisplay }.forEach { externalDisplay ->
                DropdownMenuItem(
                    text = { Text("${labels.detachToDisplayPrefix} ${externalDisplay.name}") },
                    leadingIcon = { Icon(Icons.Outlined.Computer, contentDescription = null) },
                    onClick = {
                        contextMenuOpen = false
                        onDetachToWindow(externalDisplay.displayId)
                    }
                )
            }
            if (tab.protocol == SessionProtocolKind.SSH ||
                tab.protocol == SessionProtocolKind.TELNET ||
                tab.protocol == SessionProtocolKind.SERIAL ||
                tab.protocol == SessionProtocolKind.KUBERNETES_POD ||
                tab.protocol == SessionProtocolKind.DOCKER_CONTAINER
            ) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(labels.toggleBroadcastLabel) },
                    leadingIcon = { Icon(Icons.Outlined.Podcasts, contentDescription = null) },
                    onClick = {
                        contextMenuOpen = false
                        onToggleBroadcast()
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(labels.closeTabLabel) },
                leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onClose()
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpressiveSplitDivider(
    isHorizontalSplit: Boolean,
    onDragDeltaPx: (Float) -> Unit,
    onDoubleClickReset: () -> Unit
) {
    val hoverState = rememberDesktopHoverState()
    var isDragging by remember { mutableStateOf(false) }
    val active = hoverState.value.isHovered || isDragging

    val pillThickness by animateDpAsState(
        targetValue = if (active) 5.dp else 2.dp,
        animationSpec = NexusTheme.motion.spatialFastDp,
        label = "SplitterPillThickness"
    )
    val pillColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.secondary
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        },
        animationSpec = createColorSpec(),
        label = "SplitterPillColor"
    )

    Box(
        modifier = if (isHorizontalSplit) {
            Modifier
                .width(10.dp)
                .fillMaxHeight()
        } else {
            Modifier
                .height(10.dp)
                .fillMaxWidth()
        }
            .nexusHoverTracker(hoverState)
            .nexusResizeCursor(horizontalResize = isHorizontalSplit)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onDoubleClick = onDoubleClickReset
            )
            .pointerInput(isHorizontalSplit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragDeltaPx(if (isHorizontalSplit) dragAmount.x else dragAmount.y)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = if (isHorizontalSplit) {
                Modifier
                    .width(pillThickness)
                    .height(44.dp)
            } else {
                Modifier
                    .height(pillThickness)
                    .width(44.dp)
            }
                .clip(RoundedCornerShape(99.dp))
                .background(pillColor)
        )
    }
}

@Composable
fun protocolIcon(protocol: SessionProtocolKind): ImageVector = when (protocol) {
    SessionProtocolKind.RDP, SessionProtocolKind.WINDOWS_TS -> Icons.Outlined.Computer
    SessionProtocolKind.SSH, SessionProtocolKind.TELNET, SessionProtocolKind.SERIAL,
    SessionProtocolKind.NET_UTILITY -> Icons.Outlined.Terminal
    SessionProtocolKind.SFTP, SessionProtocolKind.FTP, SessionProtocolKind.FTPS,
    SessionProtocolKind.SCP -> Icons.Outlined.FolderShared
    SessionProtocolKind.VNC, SessionProtocolKind.APPLE_SCREEN_SHARING -> Icons.Outlined.SmartDisplay
    SessionProtocolKind.WEB_PAGE -> Icons.Outlined.Language
    SessionProtocolKind.VMWARE_VSPHERE, SessionProtocolKind.PROXMOX_VE,
    SessionProtocolKind.HYPER_V, SessionProtocolKind.AWS_EC2,
    SessionProtocolKind.AZURE_VM, SessionProtocolKind.KUBERNETES_POD,
    SessionProtocolKind.DOCKER_CONTAINER -> Icons.Outlined.CloudQueue
}

@Composable
fun protocolAccentColor(protocol: SessionProtocolKind): Color {
    val ext = NexusTheme.extendedColors
    return when (protocol) {
        SessionProtocolKind.RDP, SessionProtocolKind.WINDOWS_TS -> ext.rdpAccent
        SessionProtocolKind.SSH, SessionProtocolKind.TELNET,
        SessionProtocolKind.SERIAL, SessionProtocolKind.NET_UTILITY -> ext.sshAccent
        SessionProtocolKind.SFTP, SessionProtocolKind.FTP,
        SessionProtocolKind.FTPS, SessionProtocolKind.SCP -> ext.sftpAccent
        SessionProtocolKind.VNC, SessionProtocolKind.APPLE_SCREEN_SHARING -> ext.vncAccent
        SessionProtocolKind.WEB_PAGE -> ext.webAccent
        SessionProtocolKind.VMWARE_VSPHERE, SessionProtocolKind.PROXMOX_VE,
        SessionProtocolKind.HYPER_V, SessionProtocolKind.AWS_EC2,
        SessionProtocolKind.AZURE_VM, SessionProtocolKind.KUBERNETES_POD,
        SessionProtocolKind.DOCKER_CONTAINER -> ext.cloudAccent
    }
}

@Composable
private fun statusDotColor(status: SessionLifecycleStatus): Color {
    val ext = NexusTheme.extendedColors
    return when (status) {
        SessionLifecycleStatus.CONNECTED -> ext.statusConnected
        SessionLifecycleStatus.CONNECTING, SessionLifecycleStatus.RECONNECTING -> ext.statusConnecting
        SessionLifecycleStatus.DISCONNECTED -> ext.statusDisconnected
        SessionLifecycleStatus.AUTH_FAILED, SessionLifecycleStatus.ERROR -> ext.statusError
    }
}

private fun createColorSpec() = androidx.compose.animation.core.spring<Color>(
    dampingRatio = 1.0f,
    stiffness = 1400f
)
