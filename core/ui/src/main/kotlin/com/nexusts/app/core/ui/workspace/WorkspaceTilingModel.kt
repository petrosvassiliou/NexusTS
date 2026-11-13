package com.nexusts.app.core.ui.workspace

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class SessionProtocolKind {
    RDP,
    SSH,
    TELNET,
    SERIAL,
    SFTP,
    FTP,
    FTPS,
    SCP,
    VNC,
    APPLE_SCREEN_SHARING,
    WEB_PAGE,
    VMWARE_VSPHERE,
    PROXMOX_VE,
    HYPER_V,
    AWS_EC2,
    AZURE_VM,
    WINDOWS_TS,
    NET_UTILITY,
    KUBERNETES_POD,
    DOCKER_CONTAINER
}

@Serializable
enum class SessionLifecycleStatus {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    AUTH_FAILED,
    ERROR
}

@Immutable
@Serializable
data class WorkspaceSessionTab(
    val tabId: String = UUID.randomUUID().toString(),
    val connectionId: String,
    val title: String,
    val subtitleHost: String,
    val protocol: SessionProtocolKind,
    val status: SessionLifecycleStatus = SessionLifecycleStatus.CONNECTING,
    val environmentTag: String? = null,
    val customColorHex: String? = null,
    val broadcastEnabled: Boolean = false,
    val zoomFactor: Float = 1.0f,
    val latencyMs: Int? = null,
    val unreadBellOrActivity: Boolean = false
)

@Serializable
enum class SplitOrientation {
    /** Left | Right side-by-side tiling */
    HORIZONTAL,
    /** Top / Bottom stacked tiling */
    VERTICAL
}

/**
 * Recursive Binary Space Partitioning (BSP) tree representing a tiled multi-tabbed desktop workspace.
 */
@Immutable
@Serializable
sealed interface PaneNode {
    val nodeId: String

    @Immutable
    @Serializable
    data class Leaf(
        override val nodeId: String = UUID.randomUUID().toString(),
        val tabs: List<WorkspaceSessionTab> = emptyList(),
        val activeTabId: String? = tabs.firstOrNull()?.tabId
    ) : PaneNode {
        val activeTab: WorkspaceSessionTab?
            get() = tabs.firstOrNull { it.tabId == activeTabId } ?: tabs.firstOrNull()
    }

    @Immutable
    @Serializable
    data class Split(
        override val nodeId: String = UUID.randomUUID().toString(),
        val orientation: SplitOrientation,
        val splitRatio: Float = 0.5f,
        val first: PaneNode,
        val second: PaneNode
    ) : PaneNode
}

/**
 * Reactive controller managing multi-tabbed split panes, magnetic divider resizing,
 * tab movement across splits, broadcast terminal selection, and window tear-off extraction.
 */
@Stable
class WorkspaceTilingController(
    initialRoot: PaneNode = PaneNode.Leaf(nodeId = "root-leaf")
) {
    private val _rootNode = MutableStateFlow(initialRoot)
    val rootNode: StateFlow<PaneNode> = _rootNode.asStateFlow()

    private val _focusedPaneId = MutableStateFlow(firstLeafId(initialRoot))
    val focusedPaneId: StateFlow<String> = _focusedPaneId.asStateFlow()

    fun focusPane(paneId: String) {
        if (findLeaf(_rootNode.value, paneId) != null) {
            _focusedPaneId.value = paneId
        }
    }

    fun focusNextPane() {
        val leaves = collectLeaves(_rootNode.value)
        if (leaves.size <= 1) return
        val currentIdx = leaves.indexOfFirst { it.nodeId == _focusedPaneId.value }
        val nextIdx = (currentIdx + 1) % leaves.size
        _focusedPaneId.value = leaves[nextIdx].nodeId
    }

    fun openTab(tab: WorkspaceSessionTab, targetPaneId: String = _focusedPaneId.value) {
        val updated = transformLeaf(_rootNode.value, targetPaneId) { leaf ->
            val existing = leaf.tabs.firstOrNull { it.tabId == tab.tabId }
            if (existing != null) {
                leaf.copy(activeTabId = existing.tabId)
            } else {
                leaf.copy(
                    tabs = leaf.tabs + tab,
                    activeTabId = tab.tabId
                )
            }
        }
        _rootNode.value = updated
        _focusedPaneId.value = targetPaneId
    }

    fun selectTab(paneId: String, tabId: String) {
        _rootNode.value = transformLeaf(_rootNode.value, paneId) { leaf ->
            if (leaf.tabs.any { it.tabId == tabId }) {
                leaf.copy(
                    tabs = leaf.tabs.map {
                        if (it.tabId == tabId) it.copy(unreadBellOrActivity = false) else it
                    },
                    activeTabId = tabId
                )
            } else {
                leaf
            }
        }
        _focusedPaneId.value = paneId
    }

    fun selectNextTabInFocusedPane() {
        val leaf = findLeaf(_rootNode.value, _focusedPaneId.value) ?: return
        if (leaf.tabs.size <= 1) return
        val currentIdx = leaf.tabs.indexOfFirst { it.tabId == leaf.activeTab?.tabId }.coerceAtLeast(0)
        val nextTab = leaf.tabs[(currentIdx + 1) % leaf.tabs.size]
        selectTab(leaf.nodeId, nextTab.tabId)
    }

    fun selectPreviousTabInFocusedPane() {
        val leaf = findLeaf(_rootNode.value, _focusedPaneId.value) ?: return
        if (leaf.tabs.size <= 1) return
        val currentIdx = leaf.tabs.indexOfFirst { it.tabId == leaf.activeTab?.tabId }.coerceAtLeast(0)
        val prevTab = leaf.tabs[(currentIdx - 1 + leaf.tabs.size) % leaf.tabs.size]
        selectTab(leaf.nodeId, prevTab.tabId)
    }

    fun adjustFocusedTabZoom(deltaOrNullToReset: Float?) {
        val leaf = findLeaf(_rootNode.value, _focusedPaneId.value) ?: return
        val active = leaf.activeTab ?: return
        updateTab(active.tabId) { current ->
            val updatedZoom = if (deltaOrNullToReset == null) {
                1.0f
            } else {
                (current.zoomFactor + deltaOrNullToReset).coerceIn(0.6f, 3.0f)
            }
            current.copy(zoomFactor = updatedZoom)
        }
    }

    fun updateTab(tabId: String, updater: (WorkspaceSessionTab) -> WorkspaceSessionTab) {
        _rootNode.value = mapAllLeaves(_rootNode.value) { leaf ->
            if (leaf.tabs.any { it.tabId == tabId }) {
                leaf.copy(tabs = leaf.tabs.map { if (it.tabId == tabId) updater(it) else it })
            } else {
                leaf
            }
        }
    }

    /**
     * Closes [tabId] and automatically collapses empty split panes so sibling panes reclaim space.
     */
    fun closeTab(tabId: String): WorkspaceSessionTab? {
        var removed: WorkspaceSessionTab? = null
        val updated = mapAllLeaves(_rootNode.value) { leaf ->
            val target = leaf.tabs.firstOrNull { it.tabId == tabId }
            if (target == null) {
                leaf
            } else {
                removed = target
                val remaining = leaf.tabs.filterNot { it.tabId == tabId }
                val nextActive = if (leaf.activeTabId == tabId) {
                    remaining.lastOrNull()?.tabId
                } else {
                    leaf.activeTabId
                }
                leaf.copy(tabs = remaining, activeTabId = nextActive)
            }
        }
        val pruned = pruneEmptySplits(updated)
        _rootNode.value = pruned
        if (findLeaf(pruned, _focusedPaneId.value) == null) {
            _focusedPaneId.value = firstLeafId(pruned)
        }
        return removed
    }

    /**
     * Collapses an empty split leaf pane (`tabs.isEmpty()`) when more than one pane is open,
     * allowing users to dismiss accidental splits without closing active tabs in sibling panes.
     */
    fun closeEmptyPane(paneId: String = _focusedPaneId.value): Boolean {
        val currentRoot = _rootNode.value
        val leaves = collectLeaves(currentRoot)
        if (leaves.size <= 1) return false
        val target = leaves.firstOrNull { it.nodeId == paneId } ?: return false
        if (target.tabs.isNotEmpty()) return false
        val collapsed = removeSpecificLeaf(currentRoot, paneId) ?: return false
        _rootNode.value = collapsed
        if (findLeaf(collapsed, _focusedPaneId.value) == null) {
            _focusedPaneId.value = firstLeafId(collapsed)
        }
        return true
    }

    fun focusedActiveTab(): WorkspaceSessionTab? =
        findLeaf(_rootNode.value, _focusedPaneId.value)?.activeTab

    /**
     * Splits [paneId] along [orientation], moving [tabForNewPane] into the newly created sibling pane.
     */
    fun splitPane(
        paneId: String = _focusedPaneId.value,
        orientation: SplitOrientation,
        tabForNewPane: WorkspaceSessionTab? = null
    ): String {
        val newLeafId = UUID.randomUUID().toString()
        val updated = replaceLeafWithNode(_rootNode.value, paneId) { currentLeaf ->
            val (updatedFirstLeaf, newLeafTabs) = when {
                tabForNewPane != null -> currentLeaf to listOf(tabForNewPane)
                currentLeaf.tabs.size > 1 && currentLeaf.activeTab != null -> {
                    val active = currentLeaf.activeTab!!
                    val rest = currentLeaf.tabs.filterNot { it.tabId == active.tabId }
                    currentLeaf.copy(tabs = rest, activeTabId = rest.lastOrNull()?.tabId) to listOf(active)
                }
                else -> currentLeaf to emptyList()
            }
            PaneNode.Split(
                orientation = orientation,
                splitRatio = 0.5f,
                first = updatedFirstLeaf,
                second = PaneNode.Leaf(
                    nodeId = newLeafId,
                    tabs = newLeafTabs,
                    activeTabId = newLeafTabs.firstOrNull()?.tabId
                )
            )
        }
        _rootNode.value = updated
        _focusedPaneId.value = newLeafId
        return newLeafId
    }

    /**
     * Updates a split divider ratio with magnetic snap-to-center (0.50) within 2% threshold.
     */
    fun updateSplitRatio(splitNodeId: String, rawRatio: Float) {
        val clamped = rawRatio.coerceIn(0.15f, 0.85f)
        val snapped = if (kotlin.math.abs(clamped - 0.5f) < 0.02f) 0.5f else clamped
        _rootNode.value = transformSplit(_rootNode.value, splitNodeId) { split ->
            split.copy(splitRatio = snapped)
        }
    }

    /**
     * Extracts a tab from this workspace window so it can be launched as an independent native
     * Googlebook OS freeform window on the current or an external monitor.
     */
    fun extractTabForDetachedWindow(tabId: String): WorkspaceSessionTab? = closeTab(tabId)

    fun closeAllTabs(): List<WorkspaceSessionTab> {
        val all = allActiveTabs()
        _rootNode.value = PaneNode.Leaf(nodeId = "root-leaf")
        _focusedPaneId.value = "root-leaf"
        return all
    }

    fun allActiveTabs(): List<WorkspaceSessionTab> =
        collectLeaves(_rootNode.value).flatMap { it.tabs }

    fun toggleBroadcastOnAllTerminals(enabled: Boolean) {
        _rootNode.value = mapAllLeaves(_rootNode.value) { leaf ->
            leaf.copy(
                tabs = leaf.tabs.map { tab ->
                    if (tab.protocol == SessionProtocolKind.SSH ||
                        tab.protocol == SessionProtocolKind.TELNET ||
                        tab.protocol == SessionProtocolKind.SERIAL ||
                        tab.protocol == SessionProtocolKind.KUBERNETES_POD ||
                        tab.protocol == SessionProtocolKind.DOCKER_CONTAINER
                    ) {
                        tab.copy(broadcastEnabled = enabled)
                    } else {
                        tab
                    }
                }
            )
        }
    }

    private fun collectLeaves(node: PaneNode): List<PaneNode.Leaf> = when (node) {
        is PaneNode.Leaf -> listOf(node)
        is PaneNode.Split -> collectLeaves(node.first) + collectLeaves(node.second)
    }

    private fun firstLeafId(node: PaneNode): String = collectLeaves(node).first().nodeId

    private fun findLeaf(node: PaneNode, paneId: String): PaneNode.Leaf? =
        collectLeaves(node).firstOrNull { it.nodeId == paneId }

    private fun transformLeaf(
        node: PaneNode,
        targetId: String,
        transform: (PaneNode.Leaf) -> PaneNode.Leaf
    ): PaneNode = when (node) {
        is PaneNode.Leaf -> if (node.nodeId == targetId) transform(node) else node
        is PaneNode.Split -> node.copy(
            first = transformLeaf(node.first, targetId, transform),
            second = transformLeaf(node.second, targetId, transform)
        )
    }

    private fun replaceLeafWithNode(
        node: PaneNode,
        targetId: String,
        replacement: (PaneNode.Leaf) -> PaneNode
    ): PaneNode = when (node) {
        is PaneNode.Leaf -> if (node.nodeId == targetId) replacement(node) else node
        is PaneNode.Split -> node.copy(
            first = replaceLeafWithNode(node.first, targetId, replacement),
            second = replaceLeafWithNode(node.second, targetId, replacement)
        )
    }

    private fun transformSplit(
        node: PaneNode,
        splitId: String,
        transform: (PaneNode.Split) -> PaneNode.Split
    ): PaneNode = when (node) {
        is PaneNode.Leaf -> node
        is PaneNode.Split -> {
            val childrenUpdated = node.copy(
                first = transformSplit(node.first, splitId, transform),
                second = transformSplit(node.second, splitId, transform)
            )
            if (childrenUpdated.nodeId == splitId) transform(childrenUpdated) else childrenUpdated
        }
    }

    private fun mapAllLeaves(
        node: PaneNode,
        transform: (PaneNode.Leaf) -> PaneNode.Leaf
    ): PaneNode = when (node) {
        is PaneNode.Leaf -> transform(node)
        is PaneNode.Split -> node.copy(
            first = mapAllLeaves(node.first, transform),
            second = mapAllLeaves(node.second, transform)
        )
    }

    private fun removeSpecificLeaf(node: PaneNode, targetLeafId: String): PaneNode? = when (node) {
        is PaneNode.Leaf -> if (node.nodeId == targetLeafId) null else node
        is PaneNode.Split -> {
            val nextFirst = removeSpecificLeaf(node.first, targetLeafId)
            val nextSecond = removeSpecificLeaf(node.second, targetLeafId)
            when {
                nextFirst == null -> nextSecond
                nextSecond == null -> nextFirst
                else -> node.copy(first = nextFirst, second = nextSecond)
            }
        }
    }

    private fun pruneEmptySplits(node: PaneNode): PaneNode = when (node) {
        is PaneNode.Leaf -> node
        is PaneNode.Split -> {
            val firstPruned = pruneEmptySplits(node.first)
            val secondPruned = pruneEmptySplits(node.second)
            val firstEmpty = firstPruned is PaneNode.Leaf && firstPruned.tabs.isEmpty()
            val secondEmpty = secondPruned is PaneNode.Leaf && secondPruned.tabs.isEmpty()
            when {
                firstEmpty && !secondEmpty -> secondPruned
                secondEmpty && !firstEmpty -> firstPruned
                firstEmpty && secondEmpty -> firstPruned
                else -> node.copy(first = firstPruned, second = secondPruned)
            }
        }
    }
}
