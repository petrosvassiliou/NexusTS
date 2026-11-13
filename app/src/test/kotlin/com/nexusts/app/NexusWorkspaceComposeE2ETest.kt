package com.nexusts.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.nexusts.app.core.ui.theme.NexusTSTheme
import com.nexusts.app.core.ui.workspace.AdHocConnectionSyntaxParser
import com.nexusts.app.core.ui.workspace.SessionProtocolKind
import com.nexusts.app.core.vault.automation.AutomationTaskEngine
import com.nexusts.app.core.vault.credentials.EnterpriseCredentialHub
import com.nexusts.app.core.vault.crypto.KdfSecurityParameters
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.gateway.SecureGatewayTunnelManager
import com.nexusts.app.core.vault.inheritance.PropertyInheritanceResolver
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.feature.workspace.ActiveVaultSessionStore
import com.nexusts.app.feature.workspace.NexusWorkspaceScreen
import com.nexusts.app.feature.workspace.NexusWorkspaceViewModel
import com.nexusts.app.feature.workspace.i18n.WorkspaceLocalizedStrings
import com.nexusts.app.updater.GitHubReleaseUpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1440dp-h900dp-xhdpi", application = Application::class)
class NexusWorkspaceComposeE2ETest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun buildRealViewModel(): NexusWorkspaceViewModel {
        return NexusWorkspaceViewModel(
            vaultSerializer = NxtsVaultSerializer(
                KdfSecurityParameters(memoryKiB = 8_192, iterations = 1, parallelism = 1)
            ),
            inheritanceResolver = PropertyInheritanceResolver(),
            credentialHub = EnterpriseCredentialHub(),
            automationEngine = AutomationTaskEngine(),
            gatewayTunnelManager = SecureGatewayTunnelManager(),
            activeVaultStore = ActiveVaultSessionStore()
        )
    }

    private fun assertTextRendered(substringText: String) {
        val nodes = composeRule
            .onAllNodesWithText(substringText, substring = true)
            .fetchSemanticsNodes()
        assertTrue(
            "Expected Compose semantics tree to contain '$substringText'",
            nodes.isNotEmpty()
        )
    }

    @Test
    fun allSixBundledEnterpriseLocalesResolveStringsWithoutMissingKeys() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val supportedTags = listOf("en", "de", "it", "es", "el", "fr")

        for (tag in supportedTags) {
            val cfg = Configuration(baseContext.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            val localizedCtx = baseContext.createConfigurationContext(cfg)
            val res = localizedCtx.resources

            assertTrue(res.getString(R.string.app_name).isNotBlank())
            assertTrue(res.getString(R.string.studio_action_import_passwords).isNotBlank())
            assertTrue(res.getString(R.string.sync_link_drive_saf_create).isNotBlank())
            assertTrue(res.getString(R.string.sync_link_drive_saf_open).isNotBlank())
            assertTrue(res.getString(R.string.sync_oauth_connect_button).isNotBlank())
            assertTrue(res.getString(R.string.sync_check_github_updates).isNotBlank())
            assertTrue(res.getString(R.string.serial_section_uart).isNotBlank())
            assertTrue(res.getString(R.string.container_section_exec).isNotBlank())
        }
    }

    @Test
    fun desktopWorkspaceRendersInheritedFolderCredentialsAndSupportsFolderInlineSave() {
        val vm = buildRealViewModel()
        val strings = WorkspaceLocalizedStrings.defaultEnglish()

        composeRule.setContent {
            NexusTSTheme {
                NexusWorkspaceScreen(
                    viewModel = vm,
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

        // Starter enterprise vault renders WinServer-2025-DC01 and its inherited folder credential in the Inspector
        assertTextRendered("WinServer-2025-DC01")
        assertTextRendered("CORP\\DomainAdmin + YubiKey PIV")

        // Create a new DMZ folder with an inline folder-level credential and a child RDP connection inheriting from it
        val dmzFolder = VaultFolder(
            id = "folder-dmz-tier",
            name = "DMZ Jump Tier",
            description = "Perimeter RDP Jump Hosts"
        )
        val dmzCred = VaultCredential(
            id = "cred-dmz-jump",
            name = "DMZ\\BastionOperator",
            sourceKind = CredentialSourceKind.VAULT_INTERNAL,
            domain = "DMZ",
            username = "bastion.op",
            secretPassword = "DmzPassword!2026"
        )
        composeRule.runOnIdle {
            vm.saveFolderAndCredentialToVault(dmzFolder, inlineNewCredential = dmzCred)
            vm.saveConnectionToVault(
                VaultConnection(
                    id = "conn-dmz-rdp-01",
                    parentFolderId = dmzFolder.id,
                    name = "DMZ-JumpBox-01",
                    host = "jump01.dmz.nexusts.internal",
                    protocol = VaultProtocolType.RDP,
                    inheritable = InheritableProperties(credentialMode = InheritanceMode.INHERIT_FROM_PARENT),
                    settings = ProtocolSpecificSettings.Rdp(
                        connectToConsoleAdmin = true,
                        hiDpiEnabled = true,
                        desktopScaleFactorPercent = 150
                    )
                )
            )
            vm.selectConnectionInTree("conn-dmz-rdp-01")
        }
        composeRule.waitForIdle()

        assertTextRendered("DMZ-JumpBox-01")
        assertTextRendered("DMZ\\BastionOperator")
    }

    @Test
    fun enterprisePowerSessionsRenderUsbSerialUartAndKubernetesPodTerminals() {
        val vm = buildRealViewModel()
        val strings = WorkspaceLocalizedStrings.defaultEnglish()

        composeRule.setContent {
            NexusTSTheme {
                NexusWorkspaceScreen(
                    viewModel = vm,
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

        // 1. Launch Out-of-Band USB-C Hardware Serial Console session
        composeRule.runOnIdle {
            val serialConn = vm.uiState.value.vaultDocument.connections.first {
                it.protocol == VaultProtocolType.SERIAL
            }
            vm.launchConnection(serialConn)
        }
        composeRule.waitForIdle()

        assertTextRendered("115200 8N1")
        assertTextRendered("Send BREAK")
        composeRule.onAllNodesWithText("Send BREAK").onFirst().performClick()
        composeRule.waitForIdle()
        assertTextRendered("[UART BREAK]")

        // 2. Launch Kubernetes Pod Exec & Live Log Streamer session
        composeRule.runOnIdle {
            val k8sConn = vm.uiState.value.vaultDocument.connections.first {
                it.protocol == VaultProtocolType.KUBERNETES_POD
            }
            vm.launchConnection(k8sConn)
        }
        composeRule.waitForIdle()

        assertTextRendered("K8S: prod-eu/api-gateway-7d8f9c-xk2p")
        assertTextRendered("Exec Shell")
        assertTextRendered("Live Logs")

        // 3. Verify Quick Connect (Ctrl+K) ad-hoc URI parser for new v1.1 enterprise schemes
        val k8sAdHoc = AdHocConnectionSyntaxParser.parseOrNull("k8s://prod-eu/payment-worker")
        assertNotNull(k8sAdHoc)
        assertEquals(SessionProtocolKind.KUBERNETES_POD, k8sAdHoc?.protocol)

        val dockerAdHoc = AdHocConnectionSyntaxParser.parseOrNull("docker://10.0.0.8/redis-cache")
        assertNotNull(dockerAdHoc)
        assertEquals(SessionProtocolKind.DOCKER_CONTAINER, dockerAdHoc?.protocol)

        val serialAdHoc = AdHocConnectionSyntaxParser.parseOrNull("serial:///dev/ttyACM0")
        assertNotNull(serialAdHoc)
        assertEquals(SessionProtocolKind.SERIAL, serialAdHoc?.protocol)
    }

    @Test
    fun liveDriveSyncStudioCredentialsImporterAndGitHubUpdaterEndToEnd() {
        val vm = buildRealViewModel()
        val strings = WorkspaceLocalizedStrings.defaultEnglish()
        val safCreateClicked = AtomicBoolean(false)
        val importPickerClicked = AtomicBoolean(false)
        val githubCheckClicked = AtomicBoolean(false)

        composeRule.setContent {
            NexusTSTheme {
                NexusWorkspaceScreen(
                    viewModel = vm,
                    strings = strings,
                    connectedDisplays = emptyList(),
                    flexibleUpdateDownloaded = false,
                    onCompleteFlexibleUpdate = {},
                    onRequestImportFilePicker = { importPickerClicked.set(true) },
                    onRequestGoogleDriveSync = {},
                    onDetachTabToNativeWindow = { _, _ -> },
                    onCreateLiveSafDriveVault = { safCreateClicked.set(true) },
                    onCheckGitHubReleases = { githubCheckClicked.set(true) }
                )
            }
        }
        composeRule.waitForIdle()

        // Navigate to Google Drive Sync Studio
        composeRule.runOnIdle {
            vm.selectRailDestination("sync")
        }
        composeRule.waitForIdle()

        assertTextRendered("17C98pGqQUAoQwQ-OSLUHPj4_dtb4_wtS")
        assertTextRendered(strings.syncLinkDriveSafCreate)
        assertTextRendered(strings.syncCheckGitHubUpdates)

        composeRule.onAllNodesWithText(strings.syncLinkDriveSafCreate).onFirst().performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(safCreateClicked.get())

        composeRule.onAllNodesWithText(strings.syncCheckGitHubUpdates).onFirst().performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(githubCheckClicked.get())

        // Navigate to Credentials Studio and verify external password manager import
        composeRule.runOnIdle {
            vm.selectRailDestination("credentials")
        }
        composeRule.waitForIdle()

        assertTextRendered(strings.studioActionImportPasswords)
        composeRule.onAllNodesWithText(strings.studioActionImportPasswords).onFirst().performClick()
        composeRule.waitForIdle()
        assertTrue(importPickerClicked.get())

        val bitwardenPayload = """
            {
              "folders": [],
              "items": [
                {
                  "id": "bw-e2e-1",
                  "name": "Bitwarden Production Postgres",
                  "login": {
                    "username": "dbadmin",
                    "password": "pg-secret",
                    "totp": "JBSWY3DPEHPK3PXP",
                    "uris": [{ "uri": "ssh://dbadmin@pg01.internal:22" }]
                  }
                }
              ]
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        composeRule.runOnIdle {
            vm.importVaultOrRtszBytes("bitwarden.json", bitwardenPayload)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText("Bitwarden Production Postgres", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTextRendered("Bitwarden Production Postgres")

        // Verify semantic version comparator in Play-Protect-safe GitHub Release Checker
        assertTrue(GitHubReleaseUpdateChecker.isNewerSemanticVersion("1.2.0", "1.1.0"))
        assertFalse(GitHubReleaseUpdateChecker.isNewerSemanticVersion("1.1.0", "1.1.0"))
        assertFalse(GitHubReleaseUpdateChecker.isNewerSemanticVersion("1.0.9", "1.1.0"))
    }
}
