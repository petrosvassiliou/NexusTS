package com.nexusts.app.core.ui

import com.nexusts.app.core.ui.workspace.AdHocConnectionSyntaxParser
import com.nexusts.app.core.ui.workspace.PaneNode
import com.nexusts.app.core.ui.workspace.SessionProtocolKind
import com.nexusts.app.core.ui.workspace.SplitOrientation
import com.nexusts.app.core.ui.workspace.WorkspaceSessionTab
import com.nexusts.app.core.ui.workspace.WorkspaceTilingController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickConnectAndTilingTest {

    @Test
    fun `parses ssh shorthand and URI with username host and custom port`() {
        val fromUri = AdHocConnectionSyntaxParser.parseOrNull("ssh://root@10.20.0.5:2222")
        assertNotNull(fromUri)
        assertEquals(SessionProtocolKind.SSH, fromUri!!.protocol)
        assertEquals("10.20.0.5", fromUri.parsedHost)
        assertEquals(2222, fromUri.parsedPort)
        assertEquals("root", fromUri.parsedUsername)
        assertNull(fromUri.parsedDomain)

        val fromShorthand = AdHocConnectionSyntaxParser.parseOrNull("ssh deploy@prod-api.internal:2200")
        assertNotNull(fromShorthand)
        assertEquals("prod-api.internal", fromShorthand!!.parsedHost)
        assertEquals(2200, fromShorthand.parsedPort)
        assertEquals("deploy", fromShorthand.parsedUsername)
    }

    @Test
    fun `parses rdp domain backslash user and nexusts custom scheme`() {
        val rdp = AdHocConnectionSyntaxParser.parseOrNull("""rdp://CORP\admin@win-jump.corp.local:3390""")
        assertNotNull(rdp)
        assertEquals(SessionProtocolKind.RDP, rdp!!.protocol)
        assertEquals("win-jump.corp.local", rdp.parsedHost)
        assertEquals(3390, rdp.parsedPort)
        assertEquals("admin", rdp.parsedUsername)
        assertEquals("CORP", rdp.parsedDomain)

        val nexusScheme = AdHocConnectionSyntaxParser.parseOrNull("nexusts://ssh/ubuntu@10.0.0.12:2222")
        assertNotNull(nexusScheme)
        assertEquals(SessionProtocolKind.SSH, nexusScheme!!.protocol)
        assertEquals("10.0.0.12", nexusScheme.parsedHost)
        assertEquals(2222, nexusScheme.parsedPort)
        assertEquals("ubuntu", nexusScheme.parsedUsername)

        val bareSsh = AdHocConnectionSyntaxParser.parseOrNull("ops@bastion.internal:2222")
        assertNotNull(bareSsh)
        assertEquals(SessionProtocolKind.SSH, bareSsh!!.protocol)
        assertEquals("bastion.internal", bareSsh.parsedHost)
        assertEquals(2222, bareSsh.parsedPort)
        assertEquals("ops", bareSsh.parsedUsername)

        val ipv6 = AdHocConnectionSyntaxParser.parseOrNull("ssh://admin@[2001:db8::42]:2222")
        assertNotNull(ipv6)
        assertEquals("2001:db8::42", ipv6!!.parsedHost)
        assertEquals(2222, ipv6.parsedPort)
        assertEquals("admin", ipv6.parsedUsername)
    }

    @Test
    fun `tiling controller closes empty split pane cleanly without losing sibling tab`() {
        val controller = WorkspaceTilingController()
        val tab = WorkspaceSessionTab(
            tabId = "tab-1",
            connectionId = "conn-1",
            title = "Prod SSH",
            subtitleHost = "10.0.0.5",
            protocol = SessionProtocolKind.SSH
        )
        controller.openTab(tab)
        val emptyLeafId = controller.splitPane(orientation = SplitOrientation.HORIZONTAL)
        assertTrue(controller.rootNode.value is PaneNode.Split)
        assertEquals(emptyLeafId, controller.focusedPaneId.value)

        // Cannot close non-empty leaf via closeEmptyPane
        assertFalse(controller.closeEmptyPane("root-leaf"))
        // Closing empty split collapses back to single Leaf with original tab intact
        assertTrue(controller.closeEmptyPane(emptyLeafId))
        assertTrue(controller.rootNode.value is PaneNode.Leaf)
        assertEquals(1, controller.allActiveTabs().size)
        assertEquals("tab-1", controller.focusedActiveTab()?.tabId)
    }

    @Test
    fun `tiling controller reorders session tabs within strip and moves tabs across split panes`() {
        val controller = WorkspaceTilingController()
        val tabA = WorkspaceSessionTab(
            tabId = "tab-a",
            connectionId = "conn-a",
            title = "Alpha SSH",
            subtitleHost = "10.0.1.10",
            protocol = SessionProtocolKind.SSH
        )
        val tabB = WorkspaceSessionTab(
            tabId = "tab-b",
            connectionId = "conn-b",
            title = "Bravo RDP",
            subtitleHost = "10.0.1.11",
            protocol = SessionProtocolKind.RDP
        )
        val tabC = WorkspaceSessionTab(
            tabId = "tab-c",
            connectionId = "conn-c",
            title = "Charlie VNC",
            subtitleHost = "10.0.1.12",
            protocol = SessionProtocolKind.VNC
        )

        controller.openTab(tabA, "root-leaf")
        controller.openTab(tabB, "root-leaf")
        controller.openTab(tabC, "root-leaf")

        assertEquals(
            listOf("tab-a", "tab-b", "tab-c"),
            (controller.rootNode.value as PaneNode.Leaf).tabs.map { it.tabId }
        )

        // Move tab-c left -> [tab-a, tab-c, tab-b]
        assertTrue(controller.moveTabLeft("root-leaf", "tab-c"))
        assertEquals(
            listOf("tab-a", "tab-c", "tab-b"),
            (controller.rootNode.value as PaneNode.Leaf).tabs.map { it.tabId }
        )
        assertFalse(controller.moveTabLeft("root-leaf", "tab-a"))
        assertFalse(controller.moveTabRight("root-leaf", "tab-b"))

        // Drag reorder index 2 -> 0 -> [tab-b, tab-a, tab-c]
        assertTrue(controller.reorderTab("root-leaf", fromIndex = 2, toIndex = 0))
        assertEquals(
            listOf("tab-b", "tab-a", "tab-c"),
            (controller.rootNode.value as PaneNode.Leaf).tabs.map { it.tabId }
        )

        // Split pane: active tab (tab-c) automatically moves to rightPaneId; root-leaf retains [tab-b, tab-a]
        val rightPaneId = controller.splitPane(orientation = SplitOrientation.HORIZONTAL)
        assertEquals("tab-c", controller.focusedActiveTab()?.tabId)

        // Drag tab-a into rightPaneId at index 0 -> rightPaneId now has [tab-a, tab-c]
        assertTrue(controller.moveTabToPane(tabId = "tab-a", targetPaneId = rightPaneId, targetIndex = 0))
        assertEquals(rightPaneId, controller.focusedPaneId.value)
        assertEquals("tab-a", controller.focusedActiveTab()?.tabId)

        // Drag tab-a and tab-c back into root-leaf -> rightPaneId empties and automatically collapses!
        assertTrue(controller.moveTabToPane(tabId = "tab-a", targetPaneId = "root-leaf", targetIndex = 1))
        assertTrue(controller.rootNode.value is PaneNode.Split) // tab-c still in rightPaneId
        assertTrue(controller.moveTabToPane(tabId = "tab-c", targetPaneId = "root-leaf", targetIndex = 2))
        assertTrue(controller.rootNode.value is PaneNode.Leaf)
        assertEquals(
            listOf("tab-b", "tab-a", "tab-c"),
            (controller.rootNode.value as PaneNode.Leaf).tabs.map { it.tabId }
        )
    }

    @Test
    fun `tiling controller selectTabById switches active RDP tabs across single and split panes`() {
        val controller = WorkspaceTilingController()
        val rdp1 = WorkspaceSessionTab(
            tabId = "tab-rdp-1",
            connectionId = "conn-rdp-1",
            title = "RDP Server 1",
            subtitleHost = "10.0.10.11",
            protocol = SessionProtocolKind.RDP
        )
        val rdp2 = WorkspaceSessionTab(
            tabId = "tab-rdp-2",
            connectionId = "conn-rdp-2",
            title = "RDP Server 2",
            subtitleHost = "10.0.10.12",
            protocol = SessionProtocolKind.RDP
        )
        controller.openTab(rdp1, "root-leaf")
        controller.openTab(rdp2, "root-leaf")
        assertEquals("tab-rdp-2", controller.focusedOrFirstActiveTab()?.tabId)

        assertTrue(controller.selectTabById("tab-rdp-1"))
        assertEquals("tab-rdp-1", controller.focusedOrFirstActiveTab()?.tabId)

        val secondPaneId = controller.splitPane(orientation = SplitOrientation.HORIZONTAL)
        // Splitting moved active tab (tab-rdp-1) into secondPaneId; root-leaf has tab-rdp-2
        assertEquals(secondPaneId, controller.focusedPaneId.value)
        assertEquals("tab-rdp-1", controller.focusedOrFirstActiveTab()?.tabId)

        assertTrue(controller.selectTabById("tab-rdp-2"))
        assertEquals("root-leaf", controller.focusedPaneId.value)
        assertEquals("tab-rdp-2", controller.focusedOrFirstActiveTab()?.tabId)
        assertFalse(controller.selectTabById("non-existent-tab"))
    }

    @Test
    fun `isSystemPassThroughKey passes Android and Googlebook OS screenshot shortcuts to host OS`() {
        // Dedicated PrintScreen / SysRq (120) and Android 14+ KEYCODE_SCREENSHOT (318)
        assertTrue(
            com.nexusts.app.core.ui.input.isSystemPassThroughKey(
                keyCode = android.view.KeyEvent.KEYCODE_SYSRQ,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false
            )
        )
        assertTrue(
            com.nexusts.app.core.ui.input.isSystemPassThroughKey(
                keyCode = 318,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false
            )
        )
        // Googlebook / ChromeOS Show-Windows screenshot shortcuts: Ctrl+F5 and Ctrl+Shift+F5
        assertTrue(
            com.nexusts.app.core.ui.input.isSystemPassThroughKey(
                keyCode = android.view.KeyEvent.KEYCODE_F5,
                isCtrlPressed = true,
                isAltPressed = false,
                isMetaPressed = false
            )
        )
        // Android Desktop Mode Meta+Ctrl+S screenshot shortcut and hardware Volume/Power keys
        assertTrue(
            com.nexusts.app.core.ui.input.isSystemPassThroughKey(
                keyCode = android.view.KeyEvent.KEYCODE_S,
                isCtrlPressed = true,
                isAltPressed = false,
                isMetaPressed = true
            )
        )
        assertTrue(
            com.nexusts.app.core.ui.input.isSystemPassThroughKey(
                keyCode = android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false
            )
        )

        // Normal remote session keys (bare F5, Ctrl+C, letters) MUST continue routing to remote session
        assertFalse(
            com.nexusts.app.core.ui.input.isSystemPassThroughKey(
                keyCode = android.view.KeyEvent.KEYCODE_F5,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false
            )
        )
        assertFalse(
            com.nexusts.app.core.ui.input.isSystemPassThroughKey(
                keyCode = android.view.KeyEvent.KEYCODE_C,
                isCtrlPressed = true,
                isAltPressed = false,
                isMetaPressed = false
            )
        )

        // TOGGLE_FULLSCREEN_SESSION binding is registered with bypassesRemoteCapture = true
        val fullScreenBinding = com.nexusts.app.core.ui.input.NexusShortcutEngine.defaultEnterpriseBindings()
            .firstOrNull {
                it.action == com.nexusts.app.core.ui.input.NexusShortcutAction.TOGGLE_FULLSCREEN_SESSION &&
                    it.chord.ctrl && it.chord.alt
            }
        assertNotNull(fullScreenBinding)
        assertTrue(fullScreenBinding!!.bypassesRemoteCapture)
    }
}


