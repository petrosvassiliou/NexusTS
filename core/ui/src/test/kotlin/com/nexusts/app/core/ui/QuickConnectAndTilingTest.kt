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
}
