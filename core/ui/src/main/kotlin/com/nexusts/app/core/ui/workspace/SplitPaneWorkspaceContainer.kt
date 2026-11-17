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
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.PowerSettingsNew
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nexusts.app.core.ui.components.parseHexColorOrNull
import com.nexusts.app.core.ui.input.nexusHoverTracker
import com.nexusts.app.core.ui.input.nexusResizeCursor
import com.nexusts.app.core.ui.input.rememberDesktopHoverState
import com.nexusts.app.core.ui.theme.MorphContainerState
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.ui.theme.rememberExpressiveTabShape
import com.nexusts.app.core.ui.theme.rememberMorphingContainerShape
import kotlin.math.roundToInt

@ImmutableTabStrings
data class SplitWorkspaceLabels(
    val splitRightLabel: String,
    val splitDownLabel: String,
    val detachNewWindowLabel: String,
    val detachToDisplayPrefix: String,
    val toggleBroadcastLabel: String,
    val fullScreenLabel: String = "Full Screen",
    val reconnectTabLabel: String = "Reconnect",
    val disconnectTabLabel: String = "Disconnect",
    val logOffSessionLabel: String = "Log Off Session",
    val moveTabLeftLabel: String = "Move Tab Left",
    val moveTabRightLabel: String = "Move Tab Right",
    val closeOtherTabsLabel: String = "Close Other Tabs",
    val closeTabLabel: String,
    val emptyWorkspaceTitle: String,
    val emptyWorkspaceSubtitle: String
)

private annotation class ImmutableTabStrings

private class SessionTabDragDropState {
    var draggedTab by mutableStateOf<WorkspaceSessionTab?>(null)
    var sourcePaneId by mutableStateOf<String?>(null)
    var sourceTabIndex by mutableStateOf(0)
    var pointerInRoot by mutableStateOf(Offset.Zero)
    var containerOriginInRoot by mutableStateOf(Offset.Zero)

    val paneHeaderBounds = mutableStateMapOf<String, Rect>()
    val emptyPaneBounds = mutableStateMapOf<String, Rect>()
    val tabChipBounds = mutableStateMapOf<String, Rect>() // "$paneId::$tabId" -> Rect
    val paneTabOrders = mutableStateMapOf<String, List<String>>() // paneId -> ordered tabIds

    fun chipKey(paneId: String, tabId: String): String = "$paneId::$tabId"

    fun hoveredPaneHeaderId(): String? =
        paneHeaderBounds.entries.firstOrNull { (_, rect) -> rect.inflate(10f).contains(pointerInRoot) }?.key

    fun hoveredEmptyPaneId(): String? =
        emptyPaneBounds.entries.firstOrNull { (_, rect) -> rect.contains(pointerInRoot) }?.key

    fun computeTargetIndexInPane(paneId: String): Int? {
        val orderedIds = paneTabOrders[paneId] ?: return null
        if (orderedIds.isEmpty()) return 0
        for ((idx, tabId) in orderedIds.withIndex()) {
            val rect = tabChipBounds[chipKey(paneId, tabId)] ?: continue
            if (pointerInRoot.x < rect.center.x) {
                return idx
            }
        }
        return orderedIds.lastIndex
    }

    fun isTearOffCandidate(tearOffThresholdPx: Float): Boolean {
        val activeTab = draggedTab ?: return false
        val srcPane = sourcePaneId ?: return false
        if (hoveredPaneHeaderId() != null || hoveredEmptyPaneId() != null) {
            return false
        }
        val srcHeader = paneHeaderBounds[srcPane] ?: return false
        val verticalDist = when {
            pointerInRoot.y < srcHeader.top -> srcHeader.top - pointerInRoot.y
            pointerInRoot.y > srcHeader.bottom -> pointerInRoot.y - srcHeader.bottom
            else -> 0f
        }
        return activeTab.tabId.isNotEmpty() && verticalDist >= tearOffThresholdPx
    }

    fun reset() {
        draggedTab = null
        sourcePaneId = null
        sourceTabIndex = 0
        pointerInRoot = Offset.Zero
    }
}

private val LocalSessionTabDragDropState = compositionLocalOf<SessionTabDragDropState?> { null }

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
    onEnterFullScreen: ((tab: WorkspaceSessionTab) -> Unit)? = null,
    onReconnectTab: ((tab: WorkspaceSessionTab) -> Unit)? = null,
    onLogOffTab: ((tab: WorkspaceSessionTab) -> Unit)? = null,
    onCloseEmptyPane: ((paneId: String) -> Unit)? = null,
    onReorderTab: ((paneId: String, fromIndex: Int, toIndex: Int) -> Unit)? = null,
    onMoveTabToPane: ((tabId: String, targetPaneId: String, targetIndex: Int?) -> Unit)? = null,
    isInsideSplit: Boolean = false,
    emptyStateContent: @Composable () -> Unit,
    sessionRenderer: @Composable (WorkspaceSessionTab, isPaneFocused: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val inheritedDragState = LocalSessionTabDragDropState.current
    val dragState = inheritedDragState ?: remember { SessionTabDragDropState() }
    val density = LocalDensity.current
    val tearOffThresholdPx = with(density) { 56.dp.toPx() }

    val content: @Composable () -> Unit = {
        when (node) {
            is PaneNode.Leaf -> {
                if (node.tabs.isEmpty()) {
                    DisposableEffect(node.nodeId) {
                        onDispose { dragState.emptyPaneBounds.remove(node.nodeId) }
                    }
                    val isFocused = node.nodeId == focusedPaneId
                    val isDropHovered = dragState.draggedTab != null && dragState.hoveredEmptyPaneId() == node.nodeId
                    val focusBorder = when {
                        isDropHovered -> MaterialTheme.colorScheme.primary
                        isFocused && isInsideSplit -> NexusTheme.extendedColors.activePaneFocusRing.copy(alpha = 0.65f)
                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    }
                    Box(
                        modifier = modifier
                            .fillMaxSize()
                            .padding(6.dp)
                            .onGloballyPositioned { coords ->
                                dragState.emptyPaneBounds[node.nodeId] = coords.boundsInRoot()
                            }
                            .border(
                                width = if (isDropHovered) 2.dp else if (isFocused && isInsideSplit) 1.5.dp else 1.dp,
                                color = focusBorder,
                                shape = MaterialTheme.shapes.large
                            )
                            .clip(MaterialTheme.shapes.large)
                            .background(
                                if (isDropHovered) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLowest
                                }
                            )
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
                        dragState = dragState,
                        tearOffThresholdPx = tearOffThresholdPx,
                        onFocus = { onFocusPane(node.nodeId) },
                        onSelectTab = { tabId -> onSelectTab(node.nodeId, tabId) },
                        onCloseTab = onCloseTab,
                        onSplit = { orientation -> onSplitPane(node.nodeId, orientation) },
                        onDetachToWindow = onDetachTabToWindow,
                        onToggleBroadcast = onToggleTabBroadcast,
                        onEnterFullScreen = onEnterFullScreen,
                        onReconnectTab = onReconnectTab,
                        onLogOffTab = onLogOffTab,
                        onReorderTab = onReorderTab,
                        onMoveTabToPane = onMoveTabToPane,
                        sessionRenderer = sessionRenderer,
                        modifier = modifier
                    )
                }
            }

            is PaneNode.Split -> {
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
                                onEnterFullScreen = onEnterFullScreen,
                                onReconnectTab = onReconnectTab,
                                onLogOffTab = onLogOffTab,
                                onCloseEmptyPane = onCloseEmptyPane,
                                onReorderTab = onReorderTab,
                                onMoveTabToPane = onMoveTabToPane,
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
                                onEnterFullScreen = onEnterFullScreen,
                                onReconnectTab = onReconnectTab,
                                onLogOffTab = onLogOffTab,
                                onCloseEmptyPane = onCloseEmptyPane,
                                onReorderTab = onReorderTab,
                                onMoveTabToPane = onMoveTabToPane,
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
                                onEnterFullScreen = onEnterFullScreen,
                                onReconnectTab = onReconnectTab,
                                onLogOffTab = onLogOffTab,
                                onCloseEmptyPane = onCloseEmptyPane,
                                onReorderTab = onReorderTab,
                                onMoveTabToPane = onMoveTabToPane,
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
                                onEnterFullScreen = onEnterFullScreen,
                                onReconnectTab = onReconnectTab,
                                onLogOffTab = onLogOffTab,
                                onCloseEmptyPane = onCloseEmptyPane,
                                onReorderTab = onReorderTab,
                                onMoveTabToPane = onMoveTabToPane,
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

    if (inheritedDragState == null) {
        CompositionLocalProvider(LocalSessionTabDragDropState provides dragState) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        dragState.containerOriginInRoot = coords.boundsInRoot().topLeft
                    }
            ) {
                content()

                val draggingTab = dragState.draggedTab
                if (draggingTab != null) {
                    val isTearOff = dragState.isTearOffCandidate(tearOffThresholdPx)
                    val localPos = dragState.pointerInRoot - dragState.containerOriginInRoot
                    Surface(
                        modifier = Modifier
                            .zIndex(50f)
                            .offset {
                                IntOffset(
                                    x = (localPos.x + 14f).roundToInt(),
                                    y = (localPos.y + 14f).roundToInt()
                                )
                            },
                        shape = RoundedCornerShape(99.dp),
                        color = if (isTearOff) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isTearOff) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        ),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isTearOff) {
                                    Icons.AutoMirrored.Outlined.OpenInNew
                                } else {
                                    protocolIcon(draggingTab.protocol)
                                },
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (isTearOff) {
                                    "${labels.detachNewWindowLabel} • ${draggingTab.title}"
                                } else {
                                    draggingTab.title
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    } else {
        content()
    }
}

@Composable
private fun LeafSessionPane(
    leaf: PaneNode.Leaf,
    isFocused: Boolean,
    connectedDisplays: List<ExternalDisplayDescriptor>,
    labels: SplitWorkspaceLabels,
    dragState: SessionTabDragDropState,
    tearOffThresholdPx: Float,
    onFocus: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onSplit: (SplitOrientation) -> Unit,
    onDetachToWindow: (WorkspaceSessionTab, Int?) -> Unit,
    onToggleBroadcast: (WorkspaceSessionTab) -> Unit,
    onEnterFullScreen: ((WorkspaceSessionTab) -> Unit)? = null,
    onReconnectTab: ((WorkspaceSessionTab) -> Unit)? = null,
    onLogOffTab: ((WorkspaceSessionTab) -> Unit)? = null,
    onReorderTab: ((String, Int, Int) -> Unit)? = null,
    onMoveTabToPane: ((String, String, Int?) -> Unit)? = null,
    sessionRenderer: @Composable (WorkspaceSessionTab, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val extendedColors = NexusTheme.extendedColors
    val latestIsFocused by rememberUpdatedState(isFocused)
    val latestOnFocus by rememberUpdatedState(onFocus)
    val isHeaderDropTarget = dragState.draggedTab != null &&
        dragState.hoveredPaneHeaderId() == leaf.nodeId &&
        dragState.sourcePaneId != leaf.nodeId

    DisposableEffect(leaf.nodeId, leaf.tabs) {
        dragState.paneTabOrders[leaf.nodeId] = leaf.tabs.map { it.tabId }
        onDispose {
            dragState.paneHeaderBounds.remove(leaf.nodeId)
            dragState.paneTabOrders.remove(leaf.nodeId)
        }
    }

    val borderColor by animateColorAsState(
        targetValue = when {
            isHeaderDropTarget -> MaterialTheme.colorScheme.primary
            isFocused -> extendedColors.activePaneFocusRing.copy(alpha = 0.72f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
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
                width = if (isHeaderDropTarget) 2.dp else if (isFocused) 1.5.dp else 1.dp,
                color = borderColor,
                shape = paneShape
            )
            .clip(paneShape)
            .pointerInput(leaf.nodeId) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.type == PointerEventType.Press && !latestIsFocused) {
                            latestOnFocus()
                        }
                    }
                }
            },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = paneShape
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Expressive Multi-Tab Header Strip + Tiling Action Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .onGloballyPositioned { coords ->
                        dragState.paneHeaderBounds[leaf.nodeId] = coords.boundsInRoot()
                    }
                    .background(
                        if (isHeaderDropTarget) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        }
                    )
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
                    val hoveredHeaderPane = dragState.hoveredPaneHeaderId()
                    val previewDropIndex = if (dragState.draggedTab != null && hoveredHeaderPane == leaf.nodeId) {
                        dragState.computeTargetIndexInPane(leaf.nodeId)
                    } else {
                        null
                    }

                    leaf.tabs.forEachIndexed { tabIndex, tab ->
                        val isSelected = tab.tabId == leaf.activeTab?.tabId
                        ExpressiveSessionTabChip(
                            paneId = leaf.nodeId,
                            tab = tab,
                            tabIndex = tabIndex,
                            totalTabs = leaf.tabs.size,
                            isSelected = isSelected,
                            isDropSlotHighlighted = previewDropIndex == tabIndex &&
                                dragState.draggedTab?.tabId != tab.tabId,
                            dragState = dragState,
                            tearOffThresholdPx = tearOffThresholdPx,
                            connectedDisplays = connectedDisplays,
                            labels = labels,
                            onSelect = { onSelectTab(tab.tabId) },
                            onClose = { onCloseTab(tab.tabId) },
                            onMoveLeft = if (tabIndex > 0 && onReorderTab != null) {
                                { onReorderTab(leaf.nodeId, tabIndex, tabIndex - 1) }
                            } else {
                                null
                            },
                            onMoveRight = if (tabIndex < leaf.tabs.lastIndex && onReorderTab != null) {
                                { onReorderTab(leaf.nodeId, tabIndex, tabIndex + 1) }
                            } else {
                                null
                            },
                            onReorderTab = onReorderTab,
                            onMoveTabToPane = onMoveTabToPane,
                            onCloseOtherTabs = if (leaf.tabs.size > 1) {
                                {
                                    leaf.tabs
                                        .filter { it.tabId != tab.tabId }
                                        .forEach { other -> onCloseTab(other.tabId) }
                                }
                            } else {
                                null
                            },
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
                            onEnterFullScreen = onEnterFullScreen?.let { cb ->
                                {
                                    onSelectTab(tab.tabId)
                                    cb(tab)
                                }
                            },
                            onReconnect = onReconnectTab?.let { cb -> { cb(tab) } },
                            onLogOff = onLogOffTab?.let { cb -> { cb(tab) } }
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
                    if (onEnterFullScreen != null) {
                        IconButton(
                            onClick = { onEnterFullScreen(active) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Fullscreen,
                                contentDescription = labels.fullScreenLabel,
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

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

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    key(activeTab.tabId) {
                        sessionRenderer(activeTab, isFocused)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpressiveSessionTabChip(
    paneId: String,
    tab: WorkspaceSessionTab,
    tabIndex: Int,
    totalTabs: Int,
    isSelected: Boolean,
    isDropSlotHighlighted: Boolean,
    dragState: SessionTabDragDropState,
    tearOffThresholdPx: Float,
    connectedDisplays: List<ExternalDisplayDescriptor>,
    labels: SplitWorkspaceLabels,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onMoveLeft: (() -> Unit)? = null,
    onMoveRight: (() -> Unit)? = null,
    onReorderTab: ((String, Int, Int) -> Unit)? = null,
    onMoveTabToPane: ((String, String, Int?) -> Unit)? = null,
    onCloseOtherTabs: (() -> Unit)? = null,
    onSplitRight: () -> Unit,
    onSplitDown: () -> Unit,
    onDetachToWindow: (Int?) -> Unit,
    onToggleBroadcast: () -> Unit,
    onEnterFullScreen: (() -> Unit)? = null,
    onReconnect: (() -> Unit)? = null,
    onLogOff: (() -> Unit)? = null
) {
    @Suppress("UNUSED_PARAMETER")
    val ignoredTotalTabs = totalTabs
    val hoverState = rememberDesktopHoverState()
    var contextMenuOpen by remember { mutableStateOf(false) }
    var chipCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val customAccent = parseHexColorOrNull(tab.customColorHex)
    val isBeingDragged = dragState.draggedTab?.tabId == tab.tabId

    val latestTab by rememberUpdatedState(tab)
    val latestTabIndex by rememberUpdatedState(tabIndex)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnClose by rememberUpdatedState(onClose)
    val latestOnReorderTab by rememberUpdatedState(onReorderTab)
    val latestOnMoveTabToPane by rememberUpdatedState(onMoveTabToPane)
    val latestOnDetachToWindow by rememberUpdatedState(onDetachToWindow)

    DisposableEffect(paneId, tab.tabId) {
        onDispose {
            dragState.tabChipBounds.remove(dragState.chipKey(paneId, tab.tabId))
        }
    }

    val tabShape = rememberExpressiveTabShape(
        isSelected = isSelected,
        isHovered = hoverState.value.isHovered
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            isDropSlotHighlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
            isSelected && customAccent != null -> customAccent.copy(alpha = 0.20f)
            isSelected -> MaterialTheme.colorScheme.secondaryContainer
            hoverState.value.isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
            customAccent != null -> customAccent.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        animationSpec = createColorSpec(),
        label = "TabChipBg"
    )

    val tabBorder = when {
        isDropSlotHighlighted -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        isSelected -> BorderStroke(1.dp, (customAccent ?: MaterialTheme.colorScheme.secondary).copy(alpha = 0.55f))
        else -> null
    }

    Box {
        Row(
            modifier = Modifier
                .height(32.dp)
                .alpha(if (isBeingDragged && ignoredTotalTabs >= 0) 0.45f else 1f)
                .onGloballyPositioned { coords ->
                    chipCoordinates = coords
                    dragState.tabChipBounds[dragState.chipKey(paneId, tab.tabId)] = coords.boundsInRoot()
                }
                .clip(tabShape)
                .background(bgColor, tabShape)
                .then(
                    if (tabBorder != null) {
                        Modifier.border(tabBorder, tabShape)
                    } else {
                        Modifier
                    }
                )
                .semantics(mergeDescendants = true) {
                    onClick {
                        latestOnSelect()
                        true
                    }
                }
                .nexusHoverTracker(hoverState, PointerIcon.Hand)
                .pointerInput(paneId, tab.tabId) {
                    val slopPx = viewConfiguration.touchSlop
                    awaitPointerEventScope {
                        var primaryDownPos: Offset? = null
                        var draggingActive = false
                        var swallowRelease = false

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val first = event.changes.firstOrNull() ?: continue
                            val pos = first.position

                            when (event.type) {
                                PointerEventType.Press -> {
                                    if (event.changes.any { it.isConsumed }) {
                                        primaryDownPos = null
                                        draggingActive = false
                                        continue
                                    }
                                    when {
                                        event.buttons.isSecondaryPressed -> {
                                            event.changes.forEach { it.consume() }
                                            primaryDownPos = null
                                            draggingActive = false
                                            swallowRelease = true
                                            contextMenuOpen = true
                                        }
                                        event.buttons.isTertiaryPressed -> {
                                            event.changes.forEach { it.consume() }
                                            primaryDownPos = null
                                            draggingActive = false
                                            swallowRelease = true
                                            latestOnClose()
                                        }
                                        else -> {
                                            primaryDownPos = pos
                                            draggingActive = false
                                        }
                                    }
                                }

                                PointerEventType.Move -> {
                                    val startPos = primaryDownPos
                                    if (startPos != null) {
                                        if (!draggingActive && (pos - startPos).getDistance() > slopPx) {
                                            draggingActive = true
                                            val rootOrigin = chipCoordinates?.boundsInRoot()?.topLeft ?: Offset.Zero
                                            dragState.draggedTab = latestTab
                                            dragState.sourcePaneId = paneId
                                            dragState.sourceTabIndex = latestTabIndex
                                            dragState.pointerInRoot = rootOrigin + pos
                                            event.changes.forEach { it.consume() }
                                        } else if (draggingActive) {
                                            val rootOrigin = chipCoordinates?.boundsInRoot()?.topLeft ?: Offset.Zero
                                            dragState.pointerInRoot = rootOrigin + pos
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                }

                                PointerEventType.Release -> {
                                    if (swallowRelease) {
                                        event.changes.forEach { it.consume() }
                                        swallowRelease = false
                                        primaryDownPos = null
                                        draggingActive = false
                                        continue
                                    }
                                    val hadPrimaryDown = primaryDownPos != null
                                    val wasDragging = draggingActive
                                    primaryDownPos = null
                                    draggingActive = false

                                    if (wasDragging) {
                                        event.changes.forEach { it.consume() }
                                        val dragged = dragState.draggedTab
                                        val srcPane = dragState.sourcePaneId
                                        val srcIdx = dragState.sourceTabIndex
                                        if (dragged != null && srcPane != null) {
                                            val targetHeaderPane = dragState.hoveredPaneHeaderId()
                                            val targetEmptyPane = dragState.hoveredEmptyPaneId()
                                            when {
                                                targetHeaderPane != null && targetHeaderPane == srcPane -> {
                                                    val destIdx = dragState.computeTargetIndexInPane(srcPane)
                                                    if (destIdx != null && destIdx != srcIdx) {
                                                        latestOnReorderTab?.invoke(srcPane, srcIdx, destIdx)
                                                            ?: latestOnMoveTabToPane?.invoke(dragged.tabId, srcPane, destIdx)
                                                    }
                                                }
                                                targetHeaderPane != null && targetHeaderPane != srcPane -> {
                                                    val destIdx = dragState.computeTargetIndexInPane(targetHeaderPane)
                                                    latestOnMoveTabToPane?.invoke(dragged.tabId, targetHeaderPane, destIdx)
                                                }
                                                targetEmptyPane != null && targetEmptyPane != srcPane -> {
                                                    latestOnMoveTabToPane?.invoke(dragged.tabId, targetEmptyPane, 0)
                                                }
                                                dragState.isTearOffCandidate(tearOffThresholdPx) -> {
                                                    latestOnDetachToWindow(null)
                                                }
                                            }
                                        }
                                        dragState.reset()
                                    } else if (hadPrimaryDown && event.changes.none { it.isConsumed }) {
                                        val inBounds = pos.x in 0f..size.width.toFloat() &&
                                            pos.y in 0f..size.height.toFloat()
                                        if (inBounds) {
                                            event.changes.forEach { it.consume() }
                                            latestOnSelect()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(start = 10.dp, end = 6.dp),
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

            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = labels.closeTabLabel,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = contextMenuOpen,
            onDismissRequest = { contextMenuOpen = false }
        ) {
            if (onEnterFullScreen != null) {
                DropdownMenuItem(
                    text = { Text(labels.fullScreenLabel) },
                    leadingIcon = { Icon(Icons.Outlined.Fullscreen, contentDescription = null) },
                    onClick = {
                        contextMenuOpen = false
                        onEnterFullScreen()
                    }
                )
                HorizontalDivider()
            }
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
            if (onMoveLeft != null || onMoveRight != null) {
                if (onMoveLeft != null) {
                    DropdownMenuItem(
                        text = { Text(labels.moveTabLeftLabel) },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null)
                        },
                        onClick = {
                            contextMenuOpen = false
                            onMoveLeft()
                        }
                    )
                }
                if (onMoveRight != null) {
                    DropdownMenuItem(
                        text = { Text(labels.moveTabRightLabel) },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                        },
                        onClick = {
                            contextMenuOpen = false
                            onMoveRight()
                        }
                    )
                }
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
                text = { Text(labels.disconnectTabLabel) },
                leadingIcon = { Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null) },
                onClick = {
                    contextMenuOpen = false
                    onClose()
                }
            )
            if (onLogOff != null &&
                (tab.protocol == SessionProtocolKind.RDP || tab.protocol == SessionProtocolKind.WINDOWS_TS)
            ) {
                DropdownMenuItem(
                    text = { Text(labels.logOffSessionLabel, color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        contextMenuOpen = false
                        onLogOff()
                    }
                )
            }
            HorizontalDivider()
            if (onCloseOtherTabs != null) {
                DropdownMenuItem(
                    text = { Text(labels.closeOtherTabsLabel) },
                    leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null) },
                    onClick = {
                        contextMenuOpen = false
                        onCloseOtherTabs()
                    }
                )
            }
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
