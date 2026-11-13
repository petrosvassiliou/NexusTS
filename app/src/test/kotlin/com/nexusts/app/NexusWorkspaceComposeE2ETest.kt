package com.nexusts.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.nexusts.app.core.ui.input.NexusShortcutAction
import com.nexusts.app.core.ui.theme.NexusTSTheme
import com.nexusts.app.core.ui.workspace.AdHocConnectionSyntaxParser
import com.nexusts.app.core.ui.workspace.PaneNode
import com.nexusts.app.core.ui.workspace.SessionProtocolKind
import com.nexusts.app.core.vault.automation.AutomationTaskEngine
import com.nexusts.app.core.vault.credentials.EnterpriseCredentialHub
import com.nexusts.app.core.vault.crypto.KdfSecurityParameters
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.gateway.SecureGatewayTunnelManager
import com.nexusts.app.core.vault.inheritance.PropertyInheritanceResolver
import com.nexusts.app.core.vault.model.CommandTask
import com.nexusts.app.core.vault.model.ConnectionTemplate
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.CustomPropertyEntry
import com.nexusts.app.core.vault.model.DynamicFolder
import com.nexusts.app.core.vault.model.DynamicScriptInterpreter
import com.nexusts.app.core.vault.model.GatewayTransportKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.KeySequenceTask
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.SecureGatewayConfig
import com.nexusts.app.core.vault.model.SmartFolder
import com.nexusts.app.core.vault.model.SmartFolderFilter
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.feature.workspace.ActiveVaultSessionStore
import com.nexusts.app.feature.workspace.NexusWorkspaceScreen
import com.nexusts.app.feature.workspace.NexusWorkspaceViewModel
import com.nexusts.app.feature.workspace.i18n.WorkspaceLocalizedStrings
import com.nexusts.app.protocols.ssh.bridge.SshNativeBridge
import com.nexusts.app.updater.GitHubReleaseUpdateChecker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1440dp-h900dp-xhdpi", application = Application::class)
class NexusWorkspaceComposeE2ETest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun buildFastSerializer(): NxtsVaultSerializer = NxtsVaultSerializer(
        KdfSecurityParameters(memoryKiB = 8_192, iterations = 1, parallelism = 1)
    )

    private fun buildRealViewModel(
        serializer: NxtsVaultSerializer = buildFastSerializer(),
        store: ActiveVaultSessionStore = ActiveVaultSessionStore()
    ): NexusWorkspaceViewModel {
        return NexusWorkspaceViewModel(
            vaultSerializer = serializer,
            inheritanceResolver = PropertyInheritanceResolver(),
            credentialHub = EnterpriseCredentialHub(),
            automationEngine = AutomationTaskEngine(),
            gatewayTunnelManager = SecureGatewayTunnelManager(),
            activeVaultStore = store
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

    private fun assertTextNotRendered(substringText: String) {
        val nodes = composeRule
            .onAllNodesWithText(substringText, substring = true)
            .fetchSemanticsNodes()
        assertTrue(
            "Expected Compose semantics tree NOT to expose '$substringText' while shielded",
            nodes.isEmpty()
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

    @Test
    fun vaultLockShieldIsolatesWorkspaceAndPersistsVaultRoundTrip() {
        val serializer = buildFastSerializer()
        val vm = buildRealViewModel(serializer = serializer)
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
        assertTextRendered("WinServer-2025-DC01")

        // Seal & lock vault -> FullScreenVaultUnlockShield hides all connections, sidebar, and inspector metadata
        composeRule.runOnIdle {
            vm.saveConnectionToVault(
                VaultConnection(
                    id = "conn-persisted-host",
                    name = "Persisted-ZeroKnowledge-Node",
                    host = "zk01.nexusts.internal",
                    protocol = VaultProtocolType.SSH,
                    settings = ProtocolSpecificSettings.Ssh()
                )
            )
            vm.lockVaultWithPassphrase("MasterSecret#2026".toCharArray())
        }
        composeRule.waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.uiState.value.isVaultLocked
        }
        composeRule.waitForIdle()

        assertTextRendered(strings.vaultLockedBanner)
        assertTextNotRendered("WinServer-2025-DC01")
        assertTextNotRendered("Persisted-ZeroKnowledge-Node")
        assertTrue(vm.buildQuickConnectCandidates().isEmpty())

        // Invalid passphrase surfaces an unlock error while keeping the vault shielded
        composeRule.runOnIdle {
            vm.unlockVaultWithPassphrase("WrongPassphrase!".toCharArray())
        }
        composeRule.waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !vm.uiState.value.vaultUnlockError.isNullOrBlank()
        }
        composeRule.waitForIdle()
        assertTrue(vm.uiState.value.isVaultLocked)
        assertTextNotRendered("WinServer-2025-DC01")

        // Valid passphrase cleanly unseals the vault and restores the full workspace
        composeRule.runOnIdle {
            vm.unlockVaultWithPassphrase("MasterSecret#2026".toCharArray())
        }
        composeRule.waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !vm.uiState.value.isVaultLocked
        }
        composeRule.waitForIdle()
        assertTextRendered("WinServer-2025-DC01")
        assertTextRendered("Persisted-ZeroKnowledge-Node")

        // Verify atomic disk persistence -> cold-start restoration round-trip
        val tempDir = Files.createTempDirectory("nexusts-vault-persist").toFile()
        try {
            val savedFile = vm.sessionStore.persistVaultToDisk(
                filesDir = tempDir,
                serializer = serializer,
                explicitPassphrase = "DiskVaultKey!2026".toCharArray()
            )
            assertTrue(savedFile.isFile && savedFile.length() > 64L)

            val coldStartStore = ActiveVaultSessionStore()
            val loaded = coldStartStore.loadPersistedVaultIfNeeded(
                filesDir = tempDir,
                serializer = serializer,
                candidatePassphrases = listOf("DiskVaultKey!2026".toCharArray())
            )
            assertTrue(loaded)
            assertFalse(coldStartStore.snapshot.value.isStarterUninitialized)
            assertTrue(
                coldStartStore.snapshot.value.vaultDocument.connections.any {
                    it.id == "conn-persisted-host" && it.name == "Persisted-ZeroKnowledge-Node"
                }
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun liveSessionsSurviveUnrelatedVaultEditsAndSupportTearOffReDockAndEmptyPaneClose() {
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

        var launchedTabId = ""
        composeRule.runOnIdle {
            val sshConn = vm.uiState.value.vaultDocument.connections.first {
                it.protocol == VaultProtocolType.SSH
            }
            vm.launchConnection(sshConn)
            val activeTab = vm.tilingController.allActiveTabs().first()
            launchedTabId = activeTab.tabId
        }
        composeRule.waitForIdle()

        val initialController = vm.sessionStore.sshControllers[launchedTabId]
        assertNotNull("Expected live SSH controller to be registered", initialController)

        // Unrelated vault mutations must NOT evict the live SSH session
        composeRule.runOnIdle {
            vm.addTodoItem("Rotate bastion host certificates")
            vm.addCredentialToVault(
                VaultCredential(
                    id = "cred-unrelated-test",
                    name = "Unrelated Audit Identity",
                    username = "auditor",
                    secretPassword = "audit-pass"
                )
            )
            vm.saveFolderAndCredentialToVault(
                VaultFolder(id = "folder-unrelated", name = "Unrelated Lab Folder")
            )
        }
        composeRule.waitForIdle()
        assertSame(
            "Live SSH session controller must survive unrelated vault mutations",
            initialController,
            vm.sessionStore.sshControllers[launchedTabId]
        )

        // Tear-off into detached freeform window keeps live controller alive while removing tab from main pane
        var detachedTab = vm.tilingController.allActiveTabs().firstOrNull()
        composeRule.runOnIdle {
            detachedTab = vm.transferTabToDetachedWindow(launchedTabId)
        }
        composeRule.waitForIdle()
        assertNotNull(detachedTab)
        assertTrue(vm.tilingController.allActiveTabs().none { it.tabId == launchedTabId })
        assertSame(initialController, vm.sessionStore.sshControllers[launchedTabId])

        // Re-dock from DetachedSessionActivity restores the exact same live session into main workspace
        composeRule.runOnIdle {
            vm.sessionStore.enqueueReDockTab(detachedTab!!)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            vm.tilingController.allActiveTabs().any { it.tabId == launchedTabId }
        }
        composeRule.waitForIdle()
        assertSame(initialController, vm.sessionStore.sshControllers[launchedTabId])

        // Split pane -> empty right pane focused -> CLOSE_ACTIVE_TAB collapses empty split pane cleanly
        composeRule.runOnIdle {
            vm.handleShortcut(NexusShortcutAction.SPLIT_PANE_VERTICAL) {}
        }
        composeRule.waitForIdle()
        assertTrue(vm.tilingController.rootNode.value is PaneNode.Split)

        composeRule.runOnIdle {
            vm.handleShortcut(NexusShortcutAction.CLOSE_ACTIVE_TAB) {}
        }
        composeRule.waitForIdle()
        assertTrue(vm.tilingController.rootNode.value is PaneNode.Leaf)
    }

    @Test
    fun nestedFoldersHardwareAnsiKeysAndFullStudioCrudOperationsWorkEndToEnd() {
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

        // 1. Recursive nested folders + safe re-parenting on deleteFolder
        composeRule.runOnIdle {
            vm.saveFolderAndCredentialToVault(
                VaultFolder(id = "folder-region-eu", name = "EU Sovereign Region")
            )
            vm.saveFolderAndCredentialToVault(
                VaultFolder(
                    id = "folder-az-fra1",
                    parentFolderId = "folder-region-eu",
                    name = "Frankfurt AZ-1 Subfolder"
                )
            )
            vm.saveConnectionToVault(
                VaultConnection(
                    id = "conn-nested-az1",
                    parentFolderId = "folder-az-fra1",
                    name = "FRA1-Core-Router",
                    host = "10.49.1.1",
                    protocol = VaultProtocolType.SSH,
                    settings = ProtocolSpecificSettings.Ssh()
                )
            )
        }
        composeRule.waitForIdle()

        // Delete child folder -> FRA1-Core-Router re-parents to folder-region-eu without losing connection
        composeRule.runOnIdle {
            vm.deleteFolder("folder-az-fra1")
        }
        composeRule.waitForIdle()
        val reparented = vm.uiState.value.vaultDocument.connections.first { it.id == "conn-nested-az1" }
        assertEquals("folder-region-eu", reparented.parentFolderId)
        assertTrue("folder-az-fra1" in vm.uiState.value.vaultDocument.tombstonedObjectIds)

        // 2. Full CRUD across Credentials, Gateways, SmartFolders, DynamicFolders, Templates, Tasks, Macros, CustomProps, To-Dos
        composeRule.runOnIdle {
            val cred = VaultCredential(
                id = "cred-crud-1",
                name = "CRUD Service Account",
                domain = "CORP",
                username = "svc.deploy",
                secretPassword = "InitialPassword"
            )
            vm.addCredentialToVault(cred)
            vm.updateCredentialInVault(cred.copy(name = "CRUD Service Account v2", username = "svc.deploy2"))
        }
        composeRule.waitForIdle()
        assertEquals(
            "svc.deploy2",
            vm.uiState.value.vaultDocument.credentials.first { it.id == "cred-crud-1" }.username
        )

        composeRule.runOnIdle {
            vm.deleteCredentialFromVault("cred-crud-1")
            val gw = SecureGatewayConfig(
                id = "gw-crud-1",
                name = "Zurich SOCKS5 Relay",
                kind = GatewayTransportKind.SOCKS5_PROXY,
                host = "socks.zrh.internal",
                port = 1080
            )
            vm.addGatewayToVault(gw)
            vm.updateGatewayInVault(gw.copy(port = 1085))
        }
        composeRule.waitForIdle()
        assertFalse(vm.uiState.value.vaultDocument.credentials.any { it.id == "cred-crud-1" })
        assertEquals(1085, vm.uiState.value.vaultDocument.gateways.first { it.id == "gw-crud-1" }.port)

        composeRule.runOnIdle {
            vm.deleteGatewayFromVault("gw-crud-1")

            val sf = SmartFolder(
                id = "sf-crud-1",
                name = "All SSH Routers",
                filter = SmartFolderFilter(includeProtocols = listOf(VaultProtocolType.SSH))
            )
            vm.saveSmartFolderToVault(sf)
            vm.deleteSmartFolderFromVault("sf-crud-1")

            val df = DynamicFolder(
                id = "df-crud-1",
                name = "Dynamic Lab Inventory",
                interpreter = DynamicScriptInterpreter.JSON_HTTP_API,
                scriptOrEndpointPayload = """[{"name":"dyn-lab-01","host":"10.99.0.10","protocol":"SSH"}]"""
            )
            vm.saveDynamicFolderToVault(df)
            vm.deleteDynamicFolderFromVault("df-crud-1")

            val tpl = ConnectionTemplate(
                id = "tpl-crud-1",
                name = "VNC Retina Console",
                protocol = VaultProtocolType.VNC,
                settings = ProtocolSpecificSettings.Vnc()
            )
            vm.saveTemplateToVault(tpl)
            vm.deleteTemplateFromVault("tpl-crud-1")

            val cmdTask = CommandTask(
                id = "task-crud-1",
                name = "Check Kernel Version",
                commandTemplate = "uname -a",
                broadcastToAllSelectedSessions = true
            )
            vm.addCommandTaskToVault(cmdTask)
            vm.deleteCommandTask("task-crud-1")

            val macro = KeySequenceTask(
                id = "macro-crud-1",
                name = "Send Enter Macro",
                macroSequence = "{ENTER}"
            )
            vm.addKeySequenceTaskToVault(macro)
            vm.deleteKeySequenceTask("macro-crud-1")

            vm.updateConnectionNotesAndCustomProps(
                connectionId = "conn-nested-az1",
                notes = "Primary Frankfurt BGP edge",
                customProps = listOf(CustomPropertyEntry("RackUnit", "RU-42")),
                colorAccentHex = "#3CE6B8"
            )
            vm.deleteCustomProperty("conn-nested-az1", "RackUnit")

            vm.addTodoItem("Verify BGP peerings", targetObjectId = "conn-nested-az1")
            val addedTodoId = vm.uiState.value.vaultDocument.todoItems.first {
                it.title == "Verify BGP peerings"
            }.id
            vm.toggleTodoItem(addedTodoId)
            vm.deleteTodoItem(addedTodoId)
        }
        composeRule.waitForIdle()

        val docAfterCrud = vm.uiState.value.vaultDocument
        assertFalse(docAfterCrud.gateways.any { it.id == "gw-crud-1" })
        assertFalse(docAfterCrud.smartFolders.any { it.id == "sf-crud-1" })
        assertFalse(docAfterCrud.dynamicFolders.any { it.id == "df-crud-1" })
        assertFalse(docAfterCrud.templates.any { it.id == "tpl-crud-1" })
        assertFalse(docAfterCrud.commandTasks.any { it.id == "task-crud-1" })
        assertFalse(docAfterCrud.keySequenceTasks.any { it.id == "macro-crud-1" })
        assertTrue(
            docAfterCrud.connections.first { it.id == "conn-nested-az1" }.inheritable.customProperties.isEmpty()
        )
        assertFalse(docAfterCrud.todoItems.any { it.title == "Verify BGP peerings" })

        // 3. Verify hardware keyboard -> xterm ANSI escape sequence translator
        assertArrayEquals(
            byteArrayOf(0x03),
            SshNativeBridge.translateHardwareKeyToAnsi(KeyEvent.KEYCODE_C, 'c'.code, ctrlPressed = true, altPressed = false)
        )
        assertArrayEquals(
            "\u001b[A".toByteArray(StandardCharsets.UTF_8),
            SshNativeBridge.translateHardwareKeyToAnsi(KeyEvent.KEYCODE_DPAD_UP, 0, ctrlPressed = false, altPressed = false)
        )
        assertArrayEquals(
            "\u001bf".toByteArray(StandardCharsets.UTF_8),
            SshNativeBridge.translateHardwareKeyToAnsi(KeyEvent.KEYCODE_F, 'f'.code, ctrlPressed = false, altPressed = true)
        )
    }
}
