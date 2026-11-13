package com.nexusts.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexusts.app.core.ui.theme.NexusTSTheme
import com.nexusts.app.core.vault.automation.AutomationTaskEngine
import com.nexusts.app.core.vault.credentials.EnterpriseCredentialHub
import com.nexusts.app.core.vault.crypto.KdfSecurityParameters
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.gateway.SecureGatewayTunnelManager
import com.nexusts.app.core.vault.inheritance.PropertyInheritanceResolver
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.feature.workspace.ActiveVaultSessionStore
import com.nexusts.app.feature.workspace.NexusWorkspaceScreen
import com.nexusts.app.feature.workspace.NexusWorkspaceViewModel
import com.nexusts.app.feature.workspace.i18n.WorkspaceLocalizedStrings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NexusWorkspaceInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun googlebookDesktopWorkspaceLaunchesEnterpriseProtocolsAndDriveSync() {
        val viewModel = NexusWorkspaceViewModel(
            vaultSerializer = NxtsVaultSerializer(
                KdfSecurityParameters(memoryKiB = 8_192, iterations = 1, parallelism = 1)
            ),
            inheritanceResolver = PropertyInheritanceResolver(),
            credentialHub = EnterpriseCredentialHub(),
            automationEngine = AutomationTaskEngine(),
            gatewayTunnelManager = SecureGatewayTunnelManager(),
            activeVaultStore = ActiveVaultSessionStore()
        )
        val strings = WorkspaceLocalizedStrings.defaultEnglish()

        composeRule.setContent {
            NexusTSTheme {
                NexusWorkspaceScreen(
                    viewModel = viewModel,
                    strings = strings,
                    connectedDisplays = emptyList(),
                    flexibleUpdateDownloaded = false,
                    onCompleteFlexibleUpdate = {},
                    onRequestImportFilePicker = {},
                    onRequestGoogleDriveSync = {},
                    onDetachTabToNativeWindow = { _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()

        assertTrue(viewModel.uiState.value.vaultDocument.connections.isEmpty())
        assertFalse(viewModel.uiState.value.isInspectorVisible)
        assertTrue(
            composeRule.onAllNodesWithText(strings.emptyWorkspaceTitle, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )

        composeRule.runOnIdle {
            val k8sConn = VaultConnection(
                id = "conn-k8s-inst-01",
                name = "prod-eu/api-gateway",
                host = "k8s.eu-central.internal",
                protocol = VaultProtocolType.KUBERNETES_POD,
                settings = ProtocolSpecificSettings.ContainerExec(
                    namespace = "prod-eu",
                    podOrContainerName = "api-gateway-7d8f9c-xk2p"
                )
            )
            viewModel.saveConnectionToVault(k8sConn)
            viewModel.launchConnection(k8sConn)
        }
        composeRule.waitForIdle()

        assertTrue(
            composeRule.onAllNodesWithText("Exec Shell", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )

        composeRule.runOnIdle {
            viewModel.selectRailDestination("sync")
        }
        composeRule.waitForIdle()

        assertTrue(
            composeRule.onAllNodesWithText("17C98pGqQUAoQwQ-OSLUHPj4_dtb4_wtS", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }
}
