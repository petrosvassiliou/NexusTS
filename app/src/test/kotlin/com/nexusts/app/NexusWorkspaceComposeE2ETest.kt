package com.nexusts.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.nexusts.app.core.gdrivesync.sync.SafByteTransport
import com.nexusts.app.core.gdrivesync.sync.SafDriveSyncOutcome
import com.nexusts.app.core.gdrivesync.sync.SafLiveGoogleDriveBridge
import com.nexusts.app.core.ui.input.NexusShortcutAction
import kotlinx.coroutines.runBlocking
import com.nexusts.app.core.ui.theme.NexusTSTheme
import com.nexusts.app.core.ui.workspace.AdHocConnectionSyntaxParser
import com.nexusts.app.core.ui.workspace.PaneNode
import com.nexusts.app.core.ui.workspace.SessionProtocolKind
import com.nexusts.app.core.vault.automation.AutomationTaskEngine
import com.nexusts.app.core.vault.credentials.EnterpriseCredentialHub
import com.nexusts.app.core.vault.crypto.KdfSecurityParameters
import com.nexusts.app.core.vault.crypto.NxtsVaultSerializer
import com.nexusts.app.core.vault.error.VaultHierarchySanitizer
import com.nexusts.app.core.vault.gateway.SecureGatewayTunnelManager
import com.nexusts.app.core.vault.importer.RoyalTsImportPlacementMode
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
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.SecureGatewayConfig
import com.nexusts.app.core.vault.model.SmartFolder
import com.nexusts.app.core.vault.model.SmartFolderFilter
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.feature.workspace.ActiveVaultSessionStore
import com.nexusts.app.feature.workspace.FolderDropPlacement
import com.nexusts.app.feature.workspace.NexusWorkspaceScreen
import com.nexusts.app.feature.workspace.NexusWorkspaceViewModel
import com.nexusts.app.feature.workspace.SidebarQuickFilter
import com.nexusts.app.feature.workspace.i18n.WorkspaceLocalizedStrings
import com.nexusts.app.protocols.ssh.bridge.SshNativeBridge
import com.nexusts.app.security.BiometricVaultUnlockManager
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
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

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
            assertTrue(res.getString(R.string.action_disconnect_all_folder).isNotBlank())
            assertTrue(res.getString(R.string.action_disconnect_session).isNotBlank())
            assertTrue(res.getString(R.string.action_move_to_folder).isNotBlank())
            assertTrue(res.getString(R.string.move_to_root_folder).isNotBlank())
            assertTrue(res.getString(R.string.dialog_language_title).isNotBlank())
            assertTrue(res.getString(R.string.auto_update_title).isNotBlank())
            assertTrue(res.getString(R.string.auto_update_auto_check).isNotBlank())
            assertTrue(res.getString(R.string.auto_update_available_badge).isNotBlank())
            assertTrue(res.getString(R.string.auto_update_install_action).isNotBlank())
            assertTrue(res.getString(R.string.auto_update_up_to_date).isNotBlank())
            assertTrue(res.getString(R.string.drag_drop_move_hint).isNotBlank())
            assertTrue(res.getString(R.string.action_rename_folder).isNotBlank())
            assertTrue(res.getString(R.string.action_start_all_rdp_folder).isNotBlank())
            assertTrue(res.getString(R.string.dialog_rename_folder_title).isNotBlank())
            assertTrue(res.getString(R.string.dialog_delete_folder_title).isNotBlank())
            assertTrue(res.getString(R.string.action_delete_folder_and_contents).isNotBlank())
            assertTrue(res.getString(R.string.action_delete_folder_keep_contents).isNotBlank())
            assertTrue(res.getString(R.string.nav_studios_menu).isNotBlank())
            assertFalse(res.getString(R.string.nav_studios_menu).equals("Studios", ignoreCase = true))
            // v1.2.0 localization keys
            assertTrue(res.getString(R.string.action_edit_smart_folder).isNotBlank())
            assertTrue(res.getString(R.string.action_duplicate_smart_folder).isNotBlank())
            assertTrue(res.getString(R.string.smart_folder_all_folders).isNotBlank())
            assertTrue(res.getString(R.string.smart_folder_scope_label).isNotBlank())
            assertTrue(res.getString(R.string.smart_folder_exclude_tags_label).isNotBlank())
            assertTrue(res.getString(R.string.action_new_dynamic_folder).isNotBlank())
            assertTrue(res.getString(R.string.action_reset_database).isNotBlank())
            assertTrue(res.getString(R.string.dialog_reset_database_title).isNotBlank())
            assertTrue(res.getString(R.string.action_confirm_reset_database).isNotBlank())
            assertTrue(res.getString(R.string.dialog_royal_import_title).isNotBlank())
            assertTrue(res.getString(R.string.royal_import_mode_new_folder).isNotBlank())
            assertTrue(res.getString(R.string.royal_import_mode_root).isNotBlank())
            assertTrue(res.getString(R.string.royal_import_mode_replace_all).isNotBlank())
            assertTrue(res.getString(R.string.system_credentials_folder_title).isNotBlank())
            assertTrue(res.getString(R.string.action_edit_credential).isNotBlank())
            assertTrue(res.getString(R.string.action_copy_diagnostic_report).isNotBlank())
        }
    }

    @Test
    fun desktopWorkspaceRendersInheritedFolderCredentialsAndSupportsFolderInlineSave() {
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

        // Clean empty default vault: zero fake connections, Inspector hidden by default, 4-card Vault Overview Dashboard rendered
        assertTrue(vm.uiState.value.vaultDocument.connections.isEmpty())
        assertTrue(vm.uiState.value.vaultDocument.folders.isEmpty())
        assertFalse(vm.uiState.value.isInspectorVisible)
        assertTextRendered(vm.uiState.value.vaultDocument.name)
        assertTextRendered("Credentials & TOTP")

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
            vm.handleShortcut(NexusShortcutAction.TOGGLE_PROPERTY_INSPECTOR) {}
        }
        composeRule.waitForIdle()

        assertTrue(vm.uiState.value.isInspectorVisible)
        assertTextRendered("DMZ-JumpBox-01")
        assertTextRendered("DMZ\\BastionOperator")

        // Verify upgrade migration automatically discards a persisted pure-demo v1.1.1 starter vault
        val upgradeDir = Files.createTempDirectory("nexusts-legacy-upgrade").toFile()
        try {
            val legacyDemoDoc = NxtsVaultDocument(
                documentId = "nxts-enterprise-core",
                name = "Legacy Starter",
                connections = listOf(
                    VaultConnection(
                        id = "conn-win-rds-01",
                        name = "LegacyDemoServer",
                        host = "10.0.0.1",
                        protocol = VaultProtocolType.RDP,
                        settings = ProtocolSpecificSettings.Rdp()
                    )
                )
            )
            val legacyFile = java.io.File(upgradeDir, ActiveVaultSessionStore.LOCAL_VAULT_FILE_NAME)
            legacyFile.writeBytes(
                serializer.sealDocument(
                    legacyDemoDoc,
                    ActiveVaultSessionStore.DEFAULT_LOCAL_VAULT_PASSPHRASE.toCharArray()
                )
            )
            val upgradedStore = ActiveVaultSessionStore()
            val restored = upgradedStore.loadPersistedVaultIfNeeded(upgradeDir, serializer)
            assertFalse("Pure legacy v1.1.1 demo vault must be discarded on upgrade", restored)
            assertFalse("Stale legacy demo vault file must be deleted", legacyFile.exists())
            assertTrue(upgradedStore.snapshot.value.vaultDocument.connections.isEmpty())
        } finally {
            upgradeDir.deleteRecursively()
        }
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

        // 1. Create and launch Out-of-Band USB-C Hardware Serial Console session
        composeRule.runOnIdle {
            val serialConn = VaultConnection(
                id = "conn-serial-e2e-01",
                name = "CoreSwitch-UART0",
                host = "/dev/ttyUSB0",
                protocol = VaultProtocolType.SERIAL,
                settings = ProtocolSpecificSettings.SerialUart(
                    baudRate = 115_200,
                    dataBits = 8,
                    stopBits = 1,
                    parity = "NONE"
                )
            )
            vm.saveConnectionToVault(serialConn)
            vm.launchConnection(serialConn)
        }
        composeRule.waitForIdle()

        assertTextRendered("115200 8N1")
        assertTextRendered("Send BREAK")
        composeRule.onAllNodesWithText("Send BREAK").onFirst().performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText("[UART BREAK]", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTextRendered("[UART BREAK]")

        // 2. Create and launch Kubernetes Pod Exec & Live Log Streamer session
        composeRule.runOnIdle {
            val k8sConn = VaultConnection(
                id = "conn-k8s-e2e-01",
                name = "prod-eu/api-gateway-7d8f9c-xk2p",
                host = "https://k8s.prod-eu.internal:6443",
                protocol = VaultProtocolType.KUBERNETES_POD,
                settings = ProtocolSpecificSettings.ContainerExec(
                    namespace = "prod-eu",
                    podOrContainerName = "api-gateway-7d8f9c-xk2p"
                )
            )
            vm.saveConnectionToVault(k8sConn)
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

        // Navigate to simplified Google Drive Sync tab inside Unified Settings
        composeRule.runOnIdle {
            vm.selectRailDestination("sync")
        }
        composeRule.waitForIdle()

        assertEquals(
            "Simplified Google Drive Sync UI must not expose cryptic raw Folder IDs",
            0,
            composeRule.onAllNodesWithText("17C98pGqQUAoQwQ-OSLUHPj4_dtb4_wtS", substring = true).fetchSemanticsNodes().size
        )
        assertTextRendered(strings.syncLinkDriveSafCreate)
        assertTextRendered(strings.syncLinkDriveSafOpen)

        composeRule.onAllNodesWithText(strings.syncLinkDriveSafCreate).onFirst().performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(safCreateClicked.get())

        // Switch to Updates tab inside Unified Settings and verify GitHub Releases check
        composeRule.onAllNodesWithText(strings.settingsTabUpdates, substring = false).onFirst().performClick()
        composeRule.waitForIdle()
        assertTextRendered(strings.syncCheckGitHubUpdates)
        composeRule.onAllNodesWithText(strings.syncCheckGitHubUpdates).onFirst().performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(githubCheckClicked.get())

        // Navigate to Credentials tab and verify external password manager import
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

        // Verify semantic version comparator in Play-Protect-safe GitHub Release Checker (0.3.x beta series + legacy tag filtering)
        assertTrue(GitHubReleaseUpdateChecker.isNewerSemanticVersion("0.3.0-beta.2", "0.3.0-beta.1"))
        assertTrue(GitHubReleaseUpdateChecker.isNewerSemanticVersion("0.3.0-beta.3", "0.3.0-beta.2"))
        assertTrue(GitHubReleaseUpdateChecker.isNewerSemanticVersion("0.3.0-beta.10", "0.3.0-beta.3"))
        assertTrue(GitHubReleaseUpdateChecker.isNewerSemanticVersion("0.3.0", "0.3.0-beta.1"))
        assertTrue(GitHubReleaseUpdateChecker.isNewerSemanticVersion("0.4.0", "0.3.0"))
        assertTrue(GitHubReleaseUpdateChecker.isNewerSemanticVersion("1.3.0", "0.3.0"))
        assertFalse(GitHubReleaseUpdateChecker.isNewerSemanticVersion("0.3.0-beta.1", "0.3.0"))
        assertFalse(GitHubReleaseUpdateChecker.isNewerSemanticVersion("0.3.0-beta.1", "0.3.0-beta.1"))
        assertTrue(GitHubReleaseUpdateChecker.isNewerSemanticVersion("v1.0.0", "0.9.9"))
        // Historical pre-rebase tags (1.1.0–1.2.1-beta.2) must never be flagged as updates
        assertFalse(GitHubReleaseUpdateChecker.isNewerSemanticVersion("v1.2.0", "0.3.0-beta.1"))
        assertFalse(GitHubReleaseUpdateChecker.isNewerSemanticVersion("v1.2.1-beta.2", "1.0.0"))
        assertFalse(GitHubReleaseUpdateChecker.isNewerSemanticVersion("1.1.9", "1.0.0"))
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
        assertTrue(vm.uiState.value.vaultDocument.connections.isEmpty())

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
        }
        composeRule.waitForIdle()
        assertTextRendered("Persisted-ZeroKnowledge-Node")

        // Seal & lock vault -> FullScreenVaultUnlockShield hides all connections, sidebar, and inspector metadata
        composeRule.runOnIdle {
            vm.lockVaultWithPassphrase("MasterSecret#2026".toCharArray())
        }
        composeRule.waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.uiState.value.isVaultLocked
        }
        composeRule.waitForIdle()

        assertTextRendered(strings.vaultLockedBanner)
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
        assertTextNotRendered("Persisted-ZeroKnowledge-Node")

        // Valid passphrase cleanly unseals the vault and restores the full workspace
        composeRule.runOnIdle {
            vm.unlockVaultWithPassphrase("MasterSecret#2026".toCharArray())
        }
        composeRule.waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !vm.uiState.value.isVaultLocked
        }
        composeRule.waitForIdle()
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
            val sshConn = VaultConnection(
                id = "conn-ssh-live-e2e-01",
                name = "prod-bastion-01",
                host = "10.20.0.10",
                protocol = VaultProtocolType.SSH,
                settings = ProtocolSpecificSettings.Ssh()
            )
            vm.saveConnectionToVault(sshConn)
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

    @Test
    fun hardwareCtrlKShortcutFolderInlineRdpPlusButtonAndRtszMergeImportWorkEndToEnd() {
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

        // 1. Cold-start hardware Ctrl+K with ZERO focused Compose node opens Quick Connect & deduplicates same event
        assertFalse(vm.uiState.value.isQuickConnectVisible)
        var actionDispatchCount = 0
        composeRule.runOnIdle {
            val ctrlKEvent = KeyEvent(
                100L,
                105L,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_K,
                0,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
            val handledFirst = vm.shortcutEngine.dispatchAndroidKeyEvent(
                native = ctrlKEvent,
                remoteCaptureActive = vm.uiState.value.remoteKeyCaptureEnabled
            ) { action ->
                actionDispatchCount++
                vm.handleShortcut(action)
                true
            }
            assertTrue(handledFirst)

            // Same (downTime, eventTime, keyCode) from Compose onPreviewKeyEvent must be deduplicated (no double-toggle)
            val handledDuplicate = vm.shortcutEngine.dispatchAndroidKeyEvent(
                native = ctrlKEvent,
                remoteCaptureActive = vm.uiState.value.remoteKeyCaptureEnabled
            ) { action ->
                actionDispatchCount++
                vm.handleShortcut(action)
                true
            }
            assertTrue(handledDuplicate)
        }
        composeRule.waitForIdle()
        assertEquals(1, actionDispatchCount)
        assertTrue(vm.uiState.value.isQuickConnectVisible)
        assertTextRendered(strings.quickConnectPlaceholder)

        // Escape dismisses Quick Connect Command Palette
        composeRule.runOnIdle {
            vm.dismissQuickConnect()
        }
        composeRule.waitForIdle()
        assertFalse(vm.uiState.value.isQuickConnectVisible)

        // 2. Create folder -> click inline '+' RDP icon button -> verify RDP & folder pre-populated -> save RDP connection
        composeRule.runOnIdle {
            vm.saveFolderAndCredentialToVault(
                VaultFolder(id = "folder-prod-win", name = "Production Windows Tier")
            )
        }
        composeRule.waitForIdle()

        composeRule
            .onAllNodesWithContentDescription("New RDP Connection in Production Windows Tier")
            .onFirst()
            .performClick()
        composeRule.waitForIdle()

        assertTrue(vm.uiState.value.isNewConnectionDialogVisible)
        assertEquals("folder-prod-win", vm.uiState.value.newConnectionDefaultFolderId)
        assertEquals(VaultProtocolType.RDP, vm.uiState.value.newConnectionDefaultProtocol)

        composeRule.runOnIdle {
            vm.dismissNewConnectionDialog()
            vm.saveConnectionToVault(
                VaultConnection(
                    id = "conn-existing-win",
                    parentFolderId = "folder-prod-win",
                    name = "Existing-Prod-DC01",
                    host = "10.10.0.10",
                    protocol = VaultProtocolType.RDP,
                    settings = ProtocolSpecificSettings.Rdp()
                )
            )
        }
        composeRule.waitForIdle()
        assertEquals(1, vm.uiState.value.vaultDocument.connections.size)

        // 3. Import GZIP-compressed Royal TS .rtsz archive -> verify non-destructive merge keeps existing + adds imported
        val rtszXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <RTSZDocument>
              <RoyalDocument>
                <ID>doc-rtsz-e2e</ID>
                <Name>Imported Corp RTSZ</Name>
              </RoyalDocument>
              <RoyalFolder>
                <ID>fld-imported-eu</ID>
                <ParentID>doc-rtsz-e2e</ParentID>
                <Name>Imported EU Cluster</Name>
              </RoyalFolder>
              <RoyalRDSConnection>
                <ID>conn-imported-rdp</ID>
                <ParentID>fld-imported-eu</ParentID>
                <Name>Imported-Royal-RDP</Name>
                <URI>rdp-eu.corp.internal</URI>
              </RoyalRDSConnection>
            </RTSZDocument>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val gzipBytes = ByteArrayOutputStream().also { baos ->
            GZIPOutputStream(baos).use { gos -> gos.write(rtszXml) }
        }.toByteArray()

        composeRule.runOnIdle {
            vm.importVaultOrRtszBytes("corp_import.rtsz", gzipBytes)
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.uiState.value.pendingRoyalTsImport != null
        }
        composeRule.waitForIdle()
        assertTextRendered(strings.dialogRoyalImportTitle)

        composeRule.runOnIdle {
            vm.confirmRoyalTsImport(RoyalTsImportPlacementMode.PLACE_AT_ROOT)
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.uiState.value.vaultDocument.connections.any { it.id == "conn-imported-rdp" }
        }
        composeRule.waitForIdle()

        assertFalse(vm.uiState.value.isVaultLocked)
        assertTrue(vm.uiState.value.vaultDocument.connections.any { it.id == "conn-existing-win" })
        assertTrue(vm.uiState.value.vaultDocument.connections.any { it.id == "conn-imported-rdp" })
        assertTrue(vm.uiState.value.vaultDocument.folders.any { it.id == "fld-imported-eu" })
        assertTrue(vm.uiState.value.userSnackbarMessage.orEmpty().contains("Imported"))

        // 4. Import unsupported binary file -> NEVER locks vault behind shield; shows dismissible error dialog
        composeRule.runOnIdle {
            vm.importVaultOrRtszBytes("broken.bin", byteArrayOf(0x00, 0xFF.toByte(), 0x13, 0x37))
        }
        composeRule.waitForIdle()

        assertFalse(vm.uiState.value.isVaultLocked)
        assertNotNull(vm.uiState.value.importErrorDialogMessage)
        assertTrue(vm.uiState.value.importErrorDialogMessage.orEmpty().contains("broken.bin"))

        composeRule.runOnIdle {
            vm.dismissImportErrorDialog()
        }
        composeRule.waitForIdle()
        assertEquals(null, vm.uiState.value.importErrorDialogMessage)
    }

    @Test
    fun dragDropMoveDisconnectAllInFolderLanguageFlagsAndPlayProtectAutoUpdateWorkEndToEnd() {
        val vm = buildRealViewModel()
        val strings = WorkspaceLocalizedStrings.defaultEnglish()
        val appContext = ApplicationProvider.getApplicationContext<Context>()

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

        // 1. Create Folder A -> Subfolder A1 and Folder B, with 3 SSH connections, and launch all 3 sessions
        composeRule.runOnIdle {
            vm.saveFolderAndCredentialToVault(VaultFolder(id = "folder-a", name = "Folder A"))
            vm.saveFolderAndCredentialToVault(VaultFolder(id = "folder-a1", parentFolderId = "folder-a", name = "Subfolder A1"))
            vm.saveFolderAndCredentialToVault(VaultFolder(id = "folder-b", name = "Folder B"))

            val connA = VaultConnection(
                id = "conn-a",
                parentFolderId = "folder-a",
                name = "Host-Alpha",
                host = "10.20.1.10",
                protocol = VaultProtocolType.SSH,
                settings = ProtocolSpecificSettings.Ssh()
            )
            val connA1 = VaultConnection(
                id = "conn-a1",
                parentFolderId = "folder-a1",
                name = "Host-Alpha-Sub",
                host = "10.20.1.11",
                protocol = VaultProtocolType.SSH,
                settings = ProtocolSpecificSettings.Ssh()
            )
            val connB = VaultConnection(
                id = "conn-b",
                parentFolderId = "folder-b",
                name = "Host-Bravo",
                host = "10.20.2.10",
                protocol = VaultProtocolType.SSH,
                settings = ProtocolSpecificSettings.Ssh()
            )

            vm.saveConnectionToVault(connA)
            vm.saveConnectionToVault(connA1)
            vm.saveConnectionToVault(connB)

            vm.launchConnection(connA)
            vm.launchConnection(connA1)
            vm.launchConnection(connB)

            // Hydrate live SSH session controllers for all 3 tabs
            vm.tilingController.allActiveTabs().forEach { tab ->
                vm.getOrCreateSshController(tab)
            }
        }
        composeRule.waitForIdle()

        assertEquals(3, vm.tilingController.allActiveTabs().size)
        assertEquals(3, vm.sessionStore.sshControllers.size)

        // Verify inline folder disconnect button renders on Folder A and closes Folder A + Subfolder A1 while keeping Folder B alive
        composeRule
            .onAllNodesWithContentDescription("${strings.actionDisconnectAllFolder}: Folder A")
            .onFirst()
            .performClick()
        composeRule.waitForIdle()

        val remainingTabs = vm.tilingController.allActiveTabs()
        assertEquals(1, remainingTabs.size)
        assertEquals("conn-b", remainingTabs.single().connectionId)
        assertEquals(1, vm.sessionStore.sshControllers.size)

        // 2. Drag-and-drop / Move connection between folders AND back to root without dropping live session
        val liveControllerBeforeMove = vm.sessionStore.sshControllers.values.single()
        val revBeforeMove = vm.uiState.value.vaultDocument.revision

        composeRule.runOnIdle {
            vm.moveConnectionToFolder("conn-b", "folder-a")
        }
        composeRule.waitForIdle()

        val movedToFolderA = vm.uiState.value.vaultDocument.connections.first { it.id == "conn-b" }
        assertEquals("folder-a", movedToFolderA.parentFolderId)
        assertTrue(vm.uiState.value.vaultDocument.revision > revBeforeMove)
        assertSame(liveControllerBeforeMove, vm.sessionStore.sshControllers.values.single())

        // Move back to Top Level via MoveConnectionToFolderDialog
        composeRule.runOnIdle {
            vm.openMoveConnectionDialog(movedToFolderA)
        }
        composeRule.waitForIdle()

        assertTextRendered(strings.moveToRootFolder)
        composeRule
            .onAllNodesWithText(strings.moveToRootFolder, substring = false)
            .onFirst()
            .performClick()
        composeRule.waitForIdle()

        val movedToRoot = vm.uiState.value.vaultDocument.connections.first { it.id == "conn-b" }
        assertEquals(null, movedToRoot.parentFolderId)
        assertSame(liveControllerBeforeMove, vm.sessionStore.sshControllers.values.single())

        // 3. Anchored Language Selection DropdownMenu with Vector Flags renders all 6 native language names
        composeRule
            .onAllNodesWithContentDescription(strings.actionLanguagePicker)
            .onFirst()
            .performClick()
        composeRule.waitForIdle()

        assertTrue(vm.uiState.value.isLanguageMenuExpanded)
        assertTextRendered(strings.dialogLanguageTitle)
        assertTextRendered("English")
        assertTextRendered("Deutsch")
        assertTextRendered("Italiano")
        assertTextRendered("Español")
        assertTextRendered("Ελληνικά")
        assertTextRendered("Français")

        composeRule.runOnIdle {
            vm.dismissLanguageMenu()
        }
        composeRule.waitForIdle()
        assertFalse(vm.uiState.value.isLanguageMenuExpanded)
        assertTextNotRendered("Ελληνικά")

        // 4. Google Play Protect Compliance & Auto-Update Verification
        val pkgInfo = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        )
        val requestedPerms = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertFalse(
            "App must NEVER request REQUEST_INSTALL_PACKAGES to remain 100% Google Play Protect compatible",
            "android.permission.REQUEST_INSTALL_PACKAGES" in requestedPerms
        )

        assertTrue(
            GitHubReleaseUpdateChecker.isAllowedGitHubHttpsUrl(
                "https://github.com/petrosvassiliou/NexusTS/releases/download/v1.1.5/NexusTS-v1.1.5-googlebook-android-release.apk"
            )
        )
        assertFalse(GitHubReleaseUpdateChecker.isAllowedGitHubHttpsUrl("http://github.com/petrosvassiliou/NexusTS/app.apk"))
        assertFalse(GitHubReleaseUpdateChecker.isAllowedGitHubHttpsUrl("https://evil.example.com/NexusTS-release.apk"))

        val sampleSha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val sumsText = "$sampleSha  NexusTS-v1.2.0-googlebook-android-release.apk\n"
        assertEquals(
            sampleSha,
            GitHubReleaseUpdateChecker.parseSha256SumsForAsset(
                sumsText,
                "NexusTS-v1.2.0-googlebook-android-release.apk"
            )
        )

        composeRule.runOnIdle {
            vm.applyAutoUpdateCheckOutcome(
                isUpdateAvailable = true,
                latestTag = "v1.2.0",
                currentVersion = "1.1.5",
                releaseTitle = "NexusTS v1.2.0",
                releaseNotes = "Verified auto-update release",
                sha256Hex = sampleSha,
                apkSizeBytes = 25_165_824L,
                publishedAtIso = "2026-11-13T16:00:00Z",
                apkDownloadUrl = "https://github.com/petrosvassiliou/NexusTS/releases/download/v1.2.0/NexusTS-v1.2.0-googlebook-android-release.apk",
                releaseHtmlUrl = "https://github.com/petrosvassiliou/NexusTS/releases/tag/v1.2.0",
                bannerMessage = "Update available: v1.2.0",
                openDialogAutomatically = true
            )
        }
        composeRule.waitForIdle()

        assertTrue(vm.uiState.value.isAutoUpdateDialogVisible)
        assertTextRendered("Google Play Protect Verified Architecture")
        assertTextRendered("v1.2.0")
        assertTextRendered("SHA-256: ${sampleSha.take(24)}")
        assertTextRendered(strings.autoUpdateInstallAction)
    }

    @Test
    fun biometricUnlockQuickLockIdleTimeoutNestedFolderSortingAndRdpSafDrivesWorkEndToEnd() {
        val vm = buildRealViewModel()
        val strings = WorkspaceLocalizedStrings.defaultEnglish()
        val appContext = ApplicationProvider.getApplicationContext<Context>()

        // Verify MANAGE_EXTERNAL_STORAGE is NEVER requested (100% SAF-only Google Play Protect compliant)
        val pkgInfo = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        )
        val requestedPerms = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertFalse(
            "App must NEVER request MANAGE_EXTERNAL_STORAGE; RDPDR uses SAF OpenDocumentTree exclusively",
            "android.permission.MANAGE_EXTERNAL_STORAGE" in requestedPerms
        )

        // 1. Milestone A: BiometricVaultUnlockManager AES-256-GCM enrollment & Idle Auto-Lock preferences
        val bioManager = BiometricVaultUnlockManager(appContext)
        bioManager.clearEnrolledPassphrase()
        assertFalse(bioManager.hasEnrolledPassphrase())
        assertFalse(bioManager.isBiometricUnlockEnabled())

        val enrolledOk = bioManager.enrollPassphrase("NexusTS-BioVault-2026!".toCharArray(), enableBiometricUnlock = true)
        assertTrue(enrolledOk)
        assertTrue(bioManager.hasEnrolledPassphrase())
        assertTrue(bioManager.isBiometricUnlockEnabled())
        assertArrayEquals(
            "NexusTS-BioVault-2026!".toCharArray(),
            bioManager.copyEnrolledPassphraseOrNull()
        )

        bioManager.setIdleAutoLockMinutes(15)
        assertEquals(15, bioManager.getIdleAutoLockMinutes())
        bioManager.setIdleAutoLockMinutes(999) // unsupported -> normalizes to 0 (Off)
        assertEquals(0, bioManager.getIdleAutoLockMinutes())

        val quickLockInvoked = AtomicBoolean(false)
        val biometricUnlockInvoked = AtomicBoolean(false)
        val selectedIdleMinutes = AtomicInteger(0)
        val safSampleUri = "content://com.android.externalstorage.documents/tree/primary%3AEngineeringShare"

        composeRule.runOnIdle {
            vm.updateBiometricAndAutoLockState(
                hardwareAvailable = true,
                biometricEnabled = true,
                hasSavedPassphrase = true,
                idleAutoLockMinutes = 5
            )
        }

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
                    onDetachTabToNativeWindow = { _, _ -> },
                    onRequestBiometricUnlock = {
                        biometricUnlockInvoked.set(true)
                        val unsealed = bioManager.copyEnrolledPassphraseOrNull()
                        if (unsealed != null) {
                            vm.unlockVaultWithPassphrase(unsealed)
                        }
                    },
                    onQuickLockVault = {
                        quickLockInvoked.set(true)
                        val unsealed = bioManager.copyEnrolledPassphraseOrNull()
                        if (unsealed != null) {
                            vm.lockVaultWithPassphrase(unsealed)
                        }
                    },
                    onToggleBiometricUnlock = { enabled ->
                        bioManager.setBiometricUnlockEnabled(enabled)
                        vm.updateBiometricAndAutoLockState(biometricEnabled = bioManager.isBiometricUnlockEnabled())
                    },
                    onEnrollBiometricPassphrase = { chars, bioChecked ->
                        bioManager.enrollPassphrase(chars, enableBiometricUnlock = bioChecked)
                    },
                    onChangeIdleAutoLockMinutes = { minutes ->
                        selectedIdleMinutes.set(minutes)
                        bioManager.setIdleAutoLockMinutes(minutes)
                        vm.updateBiometricAndAutoLockState(idleAutoLockMinutes = minutes)
                    },
                    onPickRdpSharedSafFolder = { onFolderPicked ->
                        onFolderPicked(safSampleUri)
                    }
                )
            }
        }
        composeRule.waitForIdle()

        // 2. Milestone B: Nested folder drag/move, cycle rejection, MoveFolderToParentDialog, and manual sorting
        composeRule.runOnIdle {
            vm.saveFolderAndCredentialToVault(VaultFolder(id = "fld-alpha", name = "Alpha Cluster"))
            vm.saveFolderAndCredentialToVault(VaultFolder(id = "fld-beta", name = "Beta Cluster"))
            vm.saveFolderAndCredentialToVault(VaultFolder(id = "fld-gamma", name = "Gamma Cluster"))
        }
        composeRule.waitForIdle()

        // Cannot move folder into itself
        assertFalse(vm.canMoveFolderToParent("fld-alpha", "fld-alpha"))
        assertTrue(vm.canMoveFolderToParent("fld-beta", "fld-alpha"))

        // Nest Beta inside Alpha, then verify Alpha CANNOT move into its own descendant Beta (cycle prevention!)
        composeRule.runOnIdle {
            assertTrue(vm.moveFolderToParent("fld-beta", "fld-alpha"))
        }
        composeRule.waitForIdle()
        assertEquals(
            "fld-alpha",
            vm.uiState.value.vaultDocument.folders.first { it.id == "fld-beta" }.parentFolderId
        )
        assertFalse(vm.canMoveFolderToParent("fld-alpha", "fld-beta"))
        assertFalse(vm.moveFolderToParent("fld-alpha", "fld-beta"))

        // Move Beta back to Top Level via MoveFolderToParentDialog
        composeRule.runOnIdle {
            val beta = vm.uiState.value.vaultDocument.folders.first { it.id == "fld-beta" }
            vm.openMoveFolderDialog(beta)
        }
        composeRule.waitForIdle()
        assertTextRendered("Move Folder")
        assertTextRendered(strings.moveToRootFolder)

        composeRule
            .onAllNodesWithText(strings.moveToRootFolder, substring = false)
            .onFirst()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(
            null,
            vm.uiState.value.vaultDocument.folders.first { it.id == "fld-beta" }.parentFolderId
        )

        // Manual folder sorting: Gamma is after Alpha & Beta; move Gamma up -> swaps ahead of Beta
        composeRule.runOnIdle {
            assertTrue(vm.reorderFolder("fld-gamma", moveUp = true))
        }
        composeRule.waitForIdle()
        val gammaSort = vm.uiState.value.vaultDocument.folders.first { it.id == "fld-gamma" }.sortOrder
        val betaSort = vm.uiState.value.vaultDocument.folders.first { it.id == "fld-beta" }.sortOrder
        assertTrue("Expected Gamma ($gammaSort) < Beta ($betaSort) after Move Up", gammaSort < betaSort)

        // Manual connection sorting & drag-onto-connection relative insertion
        composeRule.runOnIdle {
            vm.saveConnectionToVault(
                VaultConnection(
                    id = "conn-1",
                    parentFolderId = "fld-alpha",
                    name = "Node-01",
                    host = "10.30.0.1",
                    protocol = VaultProtocolType.SSH,
                    settings = ProtocolSpecificSettings.Ssh()
                )
            )
            vm.saveConnectionToVault(
                VaultConnection(
                    id = "conn-2",
                    parentFolderId = "fld-alpha",
                    name = "Node-02",
                    host = "10.30.0.2",
                    protocol = VaultProtocolType.SSH,
                    settings = ProtocolSpecificSettings.Ssh()
                )
            )
            vm.saveConnectionToVault(
                VaultConnection(
                    id = "conn-3",
                    parentFolderId = "fld-beta",
                    name = "Node-03",
                    host = "10.30.0.3",
                    protocol = VaultProtocolType.SSH,
                    settings = ProtocolSpecificSettings.Ssh()
                )
            )
        }
        composeRule.waitForIdle()

        // Drag conn-3 from fld-beta BEFORE conn-1 in fld-alpha -> moves folder AND places first
        composeRule.runOnIdle {
            assertTrue(vm.reorderConnectionRelativeTo("conn-3", "conn-1", placeAfter = false))
        }
        composeRule.waitForIdle()
        val alphaConnsOrdered = vm.uiState.value.vaultDocument.connections
            .filter { it.parentFolderId == "fld-alpha" }
            .sortedBy { it.sortOrder }
            .map { it.id }
        assertEquals(listOf("conn-3", "conn-1", "conn-2"), alphaConnsOrdered)

        // Move conn-2 up -> [conn-3, conn-2, conn-1]
        composeRule.runOnIdle {
            assertTrue(vm.reorderConnection("conn-2", moveUp = true))
        }
        composeRule.waitForIdle()
        val alphaConnsAfterMoveUp = vm.uiState.value.vaultDocument.connections
            .filter { it.parentFolderId == "fld-alpha" }
            .sortedBy { it.sortOrder }
            .map { it.id }
        assertEquals(listOf("conn-3", "conn-2", "conn-1"), alphaConnsAfterMoveUp)

        // 3. Milestone C: RDP SAF Shared Folder Redirection picker & Inspector summary
        composeRule.runOnIdle {
            val rdpConn = VaultConnection(
                id = "conn-rdp-saf",
                parentFolderId = "fld-alpha",
                name = "Win-Eng-Workstation",
                host = "10.40.0.50",
                protocol = VaultProtocolType.RDP,
                settings = ProtocolSpecificSettings.Rdp(redirectDrives = true)
            )
            vm.saveConnectionToVault(rdpConn)
            vm.openEditConnectionDialog(rdpConn)
        }
        composeRule.waitForIdle()

        // Switch to RDP Resources tab & pick SAF shared folder
        composeRule
            .onAllNodesWithText(strings.rdpTabResources, substring = false)
            .onFirst()
            .performClick()
        composeRule.waitForIdle()

        composeRule
            .onAllNodesWithText("Select Shared Folder (SAF)…", substring = false)
            .onFirst()
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        assertTextRendered("EngineeringShare")

        composeRule
            .onAllNodesWithText(strings.dialogActionSave, substring = false)
            .onFirst()
            .performClick()
        composeRule.waitForIdle()

        val savedRdp = vm.uiState.value.vaultDocument.connections.first { it.id == "conn-rdp-saf" }
        val rdpSettings = savedRdp.settings as ProtocolSpecificSettings.Rdp
        assertTrue(rdpSettings.redirectDrives)
        assertEquals(listOf(safSampleUri), rdpSettings.redirectedDriveSafUris)

        // Select RDP connection in tree, open Inspector, and verify Inspector displays "1 SAF Drive(s)"
        composeRule.runOnIdle {
            vm.selectConnectionInTree("conn-rdp-saf")
            if (!vm.uiState.value.isInspectorVisible) {
                vm.handleShortcut(NexusShortcutAction.TOGGLE_PROPERTY_INSPECTOR) {}
            }
        }
        composeRule.waitForIdle()
        assertTextRendered("1 SAF Drive(s)")

        // 4. Milestone A UI flow: Open Lock Vault dialog -> select 15m idle timeout -> Quick Lock Now -> Biometric Unlock!
        composeRule.runOnIdle {
            vm.openLockVaultDialog()
        }
        composeRule.waitForIdle()

        assertTextRendered("Quick Lock Now")
        assertTextRendered("Biometric / Passkey Unlock")
        assertTextRendered("Idle Auto-Lock Timeout")

        composeRule
            .onAllNodesWithText("15m", substring = false)
            .onFirst()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(15, selectedIdleMinutes.get())
        assertEquals(15, vm.uiState.value.idleAutoLockMinutes)

        composeRule
            .onAllNodesWithText("Quick Lock Now", substring = false)
            .onFirst()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.uiState.value.isVaultLocked
        }
        composeRule.waitForIdle()
        assertTrue(quickLockInvoked.get())
        assertTrue(vm.uiState.value.isVaultLocked)

        // FullScreenVaultUnlockShield renders "Unlock with Biometrics / Passkey" -> click to unseal & restore vault!
        assertTextRendered("Unlock with Biometrics / Passkey")
        composeRule
            .onAllNodesWithText("Unlock with Biometrics / Passkey", substring = false)
            .onFirst()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !vm.uiState.value.isVaultLocked
        }
        composeRule.waitForIdle()

        assertTrue(biometricUnlockInvoked.get())
        assertFalse(vm.uiState.value.isVaultLocked)
        assertTrue(vm.uiState.value.vaultDocument.connections.any { it.id == "conn-rdp-saf" })
    }

    @Test
    fun compactFlushLeftSidebarFolderContextMenusStartAllRdpRenameDeleteAndV117UpgradeParity() {
        assertEquals(11000, BuildConfig.VERSION_CODE)
        assertTrue(
            "Expected BuildConfig.VERSION_NAME (${BuildConfig.VERSION_NAME}) to start with 1.0.0",
            BuildConfig.VERSION_NAME.startsWith("1.0.0")
        )
        assertEquals("Manage", WorkspaceLocalizedStrings.defaultEnglish().navStudiosMenu)

        val serializer = buildFastSerializer()
        val upgradeDir = Files.createTempDirectory("nexusts-v117-to-v118-upgrade").toFile()
        try {
            // 1. Simulate an existing v1.1.7 user vault persisted on disk with nested folders, RDP, WTS & SSH hosts
            val v117Doc = NxtsVaultDocument(
                schemaVersion = 2,
                documentId = "vault-prod-v117",
                name = "Prod Fleet v1.1.7",
                revision = 7L,
                folders = listOf(
                    VaultFolder(
                        id = "fld-dc-root",
                        parentFolderId = null,
                        name = "EU-Central Datacenter",
                        sortOrder = 10
                    ),
                    VaultFolder(
                        id = "fld-dc-win",
                        parentFolderId = "fld-dc-root",
                        name = "Windows RDS Tier",
                        sortOrder = 10
                    ),
                    VaultFolder(
                        id = "fld-staging-keep",
                        parentFolderId = null,
                        name = "Staging Wrapper",
                        sortOrder = 20
                    )
                ),
                connections = listOf(
                    VaultConnection(
                        id = "conn-rdp-core",
                        parentFolderId = "fld-dc-root",
                        name = "DC-Core-RDP-01",
                        host = "10.80.1.10",
                        protocol = VaultProtocolType.RDP,
                        favorite = true,
                        sortOrder = 10,
                        settings = ProtocolSpecificSettings.Rdp()
                    ),
                    VaultConnection(
                        id = "conn-wts-sub",
                        parentFolderId = "fld-dc-win",
                        name = "RDS-Farm-Node-02",
                        host = "10.80.2.20",
                        protocol = VaultProtocolType.WINDOWS_TERMINAL_SERVICES,
                        sortOrder = 20,
                        settings = ProtocolSpecificSettings.Rdp()
                    ),
                    VaultConnection(
                        id = "conn-ssh-sub",
                        parentFolderId = "fld-dc-win",
                        name = "Linux-Bastion-SSH-01",
                        host = "10.80.2.99",
                        protocol = VaultProtocolType.SSH,
                        sortOrder = 30,
                        settings = ProtocolSpecificSettings.Ssh()
                    ),
                    VaultConnection(
                        id = "conn-staging-ssh",
                        parentFolderId = "fld-staging-keep",
                        name = "Staging-App-01",
                        host = "10.90.0.15",
                        protocol = VaultProtocolType.SSH,
                        sortOrder = 10,
                        settings = ProtocolSpecificSettings.Ssh()
                    )
                )
            )
            val persistedVaultFile = java.io.File(upgradeDir, ActiveVaultSessionStore.LOCAL_VAULT_FILE_NAME)
            persistedVaultFile.writeBytes(
                serializer.sealDocument(
                    v117Doc,
                    ActiveVaultSessionStore.DEFAULT_LOCAL_VAULT_PASSPHRASE.toCharArray()
                )
            )

            // Cold-start v1.1.8 store over the v1.1.7 vault file -> seamless in-place upgrade
            val store = ActiveVaultSessionStore()
            assertTrue(
                "v1.1.8 must seamlessly unseal & restore existing v1.1.7 .nxts v2 vault in place",
                store.loadPersistedVaultIfNeeded(upgradeDir, serializer)
            )
            val vm = buildRealViewModel(serializer = serializer, store = store)
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

            // 2. Verify flush far-left sidebar renders Folders & Connections immediately + Quick Filters + Top Settings button
            assertEquals("connections", vm.uiState.value.activeRailId)
            assertEquals(SidebarQuickFilter.ALL, vm.uiState.value.sidebarQuickFilter)
            assertTextRendered("EU-Central Datacenter")
            assertTextRendered("Windows RDS Tier")
            assertTextRendered("DC-Core-RDP-01")
            assertTextRendered(strings.navSettings)
            // Verify Dynamic Folders UI has been completely removed from the workspace sidebar
            assertEquals(
                "Dynamic Folders section header must never be rendered in the sidebar",
                0,
                composeRule.onAllNodesWithText(strings.vaultSectionDynamicFolders, substring = false).fetchSemanticsNodes().size
            )
            assertEquals(
                "New Dynamic Folder action must never be rendered in the sidebar",
                0,
                composeRule.onAllNodesWithText(strings.actionNewDynamicFolder, substring = false).fetchSemanticsNodes().size
            )
            // Verify folders appear ONLY once (in the left sidebar) and NEVER duplicated in the center overview screen
            assertEquals(
                "Folder row must appear strictly once (in sidebar, never duplicated in center overview)",
                1,
                composeRule.onAllNodesWithText("EU-Central Datacenter", substring = false).fetchSemanticsNodes().size
            )
            assertEquals(
                "Center overview must not render Connect All in Folder cards",
                0,
                composeRule.onAllNodesWithText(strings.actionConnectAllFolder, substring = false).fetchSemanticsNodes().size
            )
            // Connection list must NEVER display IP addresses next to connection names
            for (ip in listOf("10.80.1.10", "10.80.2.20", "10.80.2.99", "10.90.0.15")) {
                assertEquals(
                    "Sidebar connection list must not render IP address $ip",
                    0,
                    composeRule.onAllNodesWithText(ip, substring = true).fetchSemanticsNodes().size
                )
            }

            // Switch sidebar quick filter to Favorites -> shows favorite connection, hides non-favorite folder rows everywhere
            composeRule.runOnIdle {
                vm.setSidebarQuickFilter(SidebarQuickFilter.FAVORITES)
            }
            composeRule.waitForIdle()
            assertEquals(SidebarQuickFilter.FAVORITES, vm.uiState.value.sidebarQuickFilter)
            assertTextRendered("DC-Core-RDP-01")
            assertEquals(
                "No folder rows should appear in center overview when sidebar is filtered to Favorites",
                0,
                composeRule.onAllNodesWithText("EU-Central Datacenter", substring = false).fetchSemanticsNodes().size
            )

            composeRule.runOnIdle {
                vm.setSidebarQuickFilter(SidebarQuickFilter.ALL)
            }
            composeRule.waitForIdle()

            // Switch to Unified Settings via top toolbar Settings button, select Templates tab, and return via "Connections" back chip
            composeRule
                .onAllNodesWithText(strings.navSettings, substring = false)
                .onFirst()
                .performClick()
            composeRule.waitForIdle()
            composeRule
                .onAllNodesWithText(strings.navTemplates, substring = false)
                .onFirst()
                .performClick()
            composeRule.waitForIdle()
            assertEquals("templates", vm.uiState.value.activeRailId)

            composeRule
                .onAllNodesWithText(strings.navConnections, substring = false)
                .onFirst()
                .performClick()
            composeRule.waitForIdle()
            assertEquals("connections", vm.uiState.value.activeRailId)

            // 3. Verify recursive RDP count & "Start All RDP Connections" inside parent folder subtree
            assertEquals(2, vm.countRdpConnectionsInSubtree("fld-dc-root"))
            var launchedRdpCount = 0
            composeRule.runOnIdle {
                launchedRdpCount = vm.connectAllRdpInFolder("fld-dc-root")
            }
            composeRule.waitForIdle()
            assertEquals(2, launchedRdpCount)
            val activeTabConnIds = vm.tilingController.allActiveTabs().map { it.connectionId }.toSet()
            assertEquals(setOf("conn-rdp-core", "conn-wts-sub"), activeTabConnIds)
            assertFalse(
                "SSH host inside subfolder must NOT be launched by Start All RDP Connections",
                "conn-ssh-sub" in activeTabConnIds
            )

            // 4. Verify RenameFolderDialog & live non-disruptive folder rename (keeps active RDP tabs open!)
            val rootFolder = vm.uiState.value.vaultDocument.folders.first { it.id == "fld-dc-root" }
            val revBeforeRename = vm.uiState.value.vaultDocument.revision
            composeRule.runOnIdle {
                vm.openRenameFolderDialog(rootFolder)
            }
            composeRule.waitForIdle()
            assertTextRendered(strings.dialogRenameFolderTitle)

            composeRule.runOnIdle {
                assertTrue(vm.renameFolder("fld-dc-root", "EU-Frankfurt Prod"))
            }
            composeRule.waitForIdle()
            assertEquals(
                "EU-Frankfurt Prod",
                vm.uiState.value.vaultDocument.folders.first { it.id == "fld-dc-root" }.name
            )
            assertTrue(vm.uiState.value.vaultDocument.revision > revBeforeRename)
            assertEquals(2, vm.tilingController.allActiveTabs().size)

            // 5. Verify DeleteFolderConfirmationDialog Option B: Delete folder only (move contents up)
            val stagingFolder = vm.uiState.value.vaultDocument.folders.first { it.id == "fld-staging-keep" }
            composeRule.runOnIdle {
                vm.requestDeleteFolderConfirmation(stagingFolder)
            }
            composeRule.waitForIdle()
            assertTextRendered(strings.dialogDeleteFolderTitle)
            assertTextRendered(strings.actionDeleteFolderAndContents)
            assertTextRendered(strings.actionDeleteFolderKeepContents)

            composeRule
                .onAllNodesWithText(strings.actionDeleteFolderKeepContents, substring = false)
                .onFirst()
                .performClick()
            composeRule.waitForIdle()
            assertFalse(vm.uiState.value.vaultDocument.folders.any { it.id == "fld-staging-keep" })
            val reparentedStagingConn = vm.uiState.value.vaultDocument.connections.first { it.id == "conn-staging-ssh" }
            assertEquals(null, reparentedStagingConn.parentFolderId)

            // 6. Verify DeleteFolderConfirmationDialog Option A: Delete folder & all connections/subfolders inside
            val renamedRoot = vm.uiState.value.vaultDocument.folders.first { it.id == "fld-dc-root" }
            composeRule.runOnIdle {
                vm.requestDeleteFolderConfirmation(renamedRoot)
            }
            composeRule.waitForIdle()
            composeRule
                .onAllNodesWithText(strings.actionDeleteFolderAndContents, substring = false)
                .onFirst()
                .performClick()
            composeRule.waitForIdle()

            val finalDoc = vm.uiState.value.vaultDocument
            assertFalse(finalDoc.folders.any { it.id == "fld-dc-root" || it.id == "fld-dc-win" })
            assertFalse(
                finalDoc.connections.any {
                    it.id in setOf("conn-rdp-core", "conn-wts-sub", "conn-ssh-sub")
                }
            )
            assertTrue(
                finalDoc.tombstonedObjectIds.containsAll(
                    setOf("fld-dc-root", "fld-dc-win", "conn-rdp-core", "conn-wts-sub", "conn-ssh-sub")
                )
            )
            assertTrue(
                "Active RDP tabs inside deleted folder subtree must be closed cleanly",
                vm.tilingController.allActiveTabs().isEmpty()
            )
            assertTrue(
                "Unrelated connection outside deleted subtree must remain intact",
                finalDoc.connections.any { it.id == "conn-staging-ssh" }
            )
        } finally {
            upgradeDir.deleteRecursively()
        }
    }

    @Test
    fun intelligentSidebarProtocolGroupingSuppressesSingleTypeAndGroupsMixedTypes() {
        val rdpA = VaultConnection(
            id = "g-rdp-a",
            name = "RDS-Broker-02",
            host = "10.80.2.22",
            protocol = VaultProtocolType.RDP,
            sortOrder = 20,
            settings = ProtocolSpecificSettings.Rdp()
        )
        val wtsB = VaultConnection(
            id = "g-wts-b",
            name = "RDS-Broker-01",
            host = "10.80.2.21",
            protocol = VaultProtocolType.WINDOWS_TERMINAL_SERVICES,
            sortOrder = 10,
            settings = ProtocolSpecificSettings.Rdp()
        )
        val sshC = VaultConnection(
            id = "g-ssh-c",
            name = "bastion-fra-01",
            host = "10.80.9.10",
            protocol = VaultProtocolType.SSH,
            sortOrder = 5,
            settings = ProtocolSpecificSettings.Ssh()
        )

        // 1. Homogeneous folder (only RDP + WTS -> both normalize to "RDP") must NOT show an "RDP" group header
        val rdpOnlyGroups = com.nexusts.app.feature.workspace.groupConnectionsIntelligently(listOf(rdpA, wtsB))
        assertEquals(1, rdpOnlyGroups.size)
        assertEquals("RDP", rdpOnlyGroups[0].badge)
        assertFalse(
            "Single-protocol folder must suppress redundant RDP header",
            rdpOnlyGroups[0].showsHeader
        )
        assertEquals(listOf("g-wts-b", "g-rdp-a"), rdpOnlyGroups[0].connections.map { it.id })

        // 2. Mixed folder (RDP + SSH) must group into canonical ["RDP", "SSH"] order with headers enabled
        val mixedGroups = com.nexusts.app.feature.workspace.groupConnectionsIntelligently(listOf(sshC, rdpA, wtsB))
        assertEquals(2, mixedGroups.size)
        assertEquals(listOf("RDP", "SSH"), mixedGroups.map { it.badge })
        assertTrue(mixedGroups.all { it.showsHeader })
        assertEquals(listOf("g-wts-b", "g-rdp-a"), mixedGroups[0].connections.map { it.id })
        assertEquals(listOf("g-ssh-c"), mixedGroups[1].connections.map { it.id })
    }

    @Test
    fun v120RoyalTsPlacementSystemCredentialsDragDropIntelligentFoldersResetDbAndCorruptQuarantineWorkEndToEnd() {
        val serializer = buildFastSerializer()
        val store = ActiveVaultSessionStore()
        val vm = buildRealViewModel(serializer = serializer, store = store)
        val strings = WorkspaceLocalizedStrings.defaultEnglish()
        val resetDir = Files.createTempDirectory("nexusts-v120-reset-and-quarantine").toFile()

        try {
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
                        onDetachTabToNativeWindow = { _, _ -> },
                        onResetNexusDatabase = {
                            val fresh = store.resetLocalVaultDatabase(resetDir, serializer)
                            vm.resetWorkspaceStateAfterDatabaseWipe(fresh, strings.resetDatabaseSuccess)
                        }
                    )
                }
            }
            composeRule.waitForIdle()

            // 1. Clean empty vault + bottom-pinned system "Credentials" folder always rendered at bottom of sidebar
            assertTrue(vm.uiState.value.vaultDocument.connections.isEmpty())
            assertTrue(vm.uiState.value.vaultDocument.folders.isEmpty())
            assertTrue(vm.uiState.value.vaultDocument.credentials.isEmpty())
            assertTextRendered(strings.systemCredentialsFolderTitle)

            // Root folder named "Credentials" is reserved & blocked; system Credentials folder cannot hold connections/folders
            assertNotNull(vm.validateFolderName("Credentials", parentFolderId = null))
            composeRule.runOnIdle {
                vm.saveFolderAndCredentialToVault(VaultFolder(id = "bad-creds-folder", name = "Credentials"))
            }
            composeRule.waitForIdle()
            assertFalse(vm.uiState.value.vaultDocument.folders.any { it.id == "bad-creds-folder" })

            // Create two real folders (Empty Folder + Active Folder) and verify clicking an empty folder NEVER opens Folder Properties
            composeRule.runOnIdle {
                vm.saveFolderAndCredentialToVault(VaultFolder(id = "fld-empty", name = "Empty Staging Folder", sortOrder = 10))
                vm.saveFolderAndCredentialToVault(VaultFolder(id = "fld-ops", name = "Ops Production", sortOrder = 20))
                vm.saveFolderAndCredentialToVault(VaultFolder(id = "fld-dr", name = "DR Failover", sortOrder = 30))
                vm.saveConnectionToVault(
                    VaultConnection(
                        id = "conn-ops-01",
                        parentFolderId = "fld-ops",
                        name = "Ops-Core-Jump",
                        host = "10.220.44.88",
                        protocol = VaultProtocolType.RDP,
                        tags = listOf("prod", "jump"),
                        settings = ProtocolSpecificSettings.Rdp()
                    )
                )
            }
            composeRule.waitForIdle()

            // Sidebar connection row shows ONLY the connection name, NEVER the IP address
            assertTextRendered("Ops-Core-Jump")
            assertEquals(
                "Connection item must never display IP address in sidebar",
                0,
                composeRule.onAllNodesWithText("10.220.44.88", substring = true).fetchSemanticsNodes().size
            )

            // Left-clicking empty folder expands/collapses & selects it WITHOUT opening Folder Properties dialog!
            composeRule
                .onAllNodesWithText("Empty Staging Folder", substring = false)
                .onFirst()
                .performClick()
            composeRule.waitForIdle()
            assertFalse(
                "Clicking an empty folder must NEVER auto-open the Folder Properties dialog",
                vm.uiState.value.isFolderDialogVisible
            )

            // 2. Folder drag & drop BEFORE / INSIDE / AFTER + blocking drops into system "Credentials" folder
            assertFalse(
                vm.moveOrReorderFolder(
                    draggedFolderId = "fld-dr",
                    targetFolderId = VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID,
                    placement = FolderDropPlacement.INSIDE
                )
            )
            assertFalse(
                vm.moveConnectionToFolder("conn-ops-01", VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID)
            )

            composeRule.runOnIdle {
                // Reorder DR Failover BEFORE Empty Staging Folder
                assertTrue(
                    vm.moveOrReorderFolder(
                        draggedFolderId = "fld-dr",
                        targetFolderId = "fld-empty",
                        placement = FolderDropPlacement.BEFORE
                    )
                )
            }
            composeRule.waitForIdle()
            val rootOrdered = NexusWorkspaceViewModel.sortedSiblingFolders(
                vm.uiState.value.vaultDocument.folders,
                parentFolderId = null
            ).map { it.id }
            assertEquals(listOf("fld-dr", "fld-empty", "fld-ops"), rootOrdered)

            // 3. Edit existing connection dialog opens with exact connection populated
            composeRule.runOnIdle {
                val target = vm.uiState.value.vaultDocument.connections.first { it.id == "conn-ops-01" }
                vm.openEditConnectionDialog(target)
            }
            composeRule.waitForIdle()
            assertTrue(vm.uiState.value.isNewConnectionDialogVisible)
            assertEquals("conn-ops-01", vm.uiState.value.editingConnection?.id)
            assertFalse(vm.uiState.value.isFolderDialogVisible)
            composeRule.runOnIdle {
                vm.dismissNewConnectionDialog()
            }
            composeRule.waitForIdle()

            // 4. Intelligent Folders (SmartFolder) creation, editing, duplication, live matching, and invalid regex rejection
            composeRule.runOnIdle {
                vm.openNewSmartFolderDialog()
            }
            composeRule.waitForIdle()
            assertTrue(vm.uiState.value.isNewSmartFolderDialogVisible)
            assertTextRendered(strings.smartFolderScopeLabel)

            // Invalid regex triggers structured validation error instead of saving corrupt filter
            composeRule.runOnIdle {
                vm.saveSmartFolderToVault(
                    SmartFolder(
                        id = "sf-invalid",
                        name = "Broken Filter",
                        filter = SmartFolderFilter(hostPatternRegex = "[unclosed")
                    )
                )
            }
            composeRule.waitForIdle()
            assertNotNull(vm.uiState.value.activeAppError)
            assertFalse(vm.uiState.value.vaultDocument.smartFolders.any { it.id == "sf-invalid" })
            composeRule.runOnIdle { vm.dismissActiveAppError() }
            composeRule.waitForIdle()

            // Save valid scoped Intelligent Folder & duplicate it
            val validSmart = SmartFolder(
                id = "sf-ops-rdp",
                name = "Ops RDP Jump Hosts",
                filter = SmartFolderFilter(
                    includeProtocols = listOf(VaultProtocolType.RDP),
                    includeTags = listOf("prod"),
                    excludeTags = listOf("retired"),
                    scopeFolderId = "fld-ops"
                )
            )
            composeRule.runOnIdle {
                vm.saveSmartFolderToVault(validSmart)
            }
            composeRule.waitForIdle()
            assertEquals(1, vm.evaluateSmartFolderCount(validSmart))
            assertTextRendered("Ops RDP Jump Hosts")

            composeRule.runOnIdle {
                vm.duplicateSmartFolder(validSmart)
            }
            composeRule.waitForIdle()
            assertEquals(2, vm.uiState.value.vaultDocument.smartFolders.size)

            // 5. Royal TS import placement modes: CREATE_NEW_FOLDER and REPLACE_EXISTING
            val rtsxBytes = """
                <?xml version="1.0" encoding="utf-8"?>
                <RTSZDocument>
                  <RoyalDocument>
                    <ID>doc-rtsx-120</ID>
                    <Name>Vienna Branch</Name>
                  </RoyalDocument>
                  <RoyalFolder>
                    <ID>fld-rtsx-conns</ID>
                    <ParentID>doc-rtsx-120</ParentID>
                    <Name>Connections</Name>
                  </RoyalFolder>
                  <RoyalFolder>
                    <ID>fld-rtsx-creds</ID>
                    <ParentID>doc-rtsx-120</ParentID>
                    <Name>Credentials</Name>
                  </RoyalFolder>
                  <RoyalRDSConnection>
                    <ID>conn-vienna-01</ID>
                    <ParentID>fld-rtsx-conns</ParentID>
                    <Name>VIE-DC01</Name>
                    <URI>10.43.1.10</URI>
                  </RoyalRDSConnection>
                  <RoyalCredential>
                    <ID>cred-vienna-da</ID>
                    <ParentID>fld-rtsx-creds</ParentID>
                    <Name>Vienna Admin</Name>
                    <UserName>VIE\admin</UserName>
                    <Password>Secret!</Password>
                  </RoyalCredential>
                </RTSZDocument>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)

            // Import into a brand-new folder "Vienna Imported"
            composeRule.runOnIdle {
                vm.importVaultOrRtszBytes("vienna.rtsx", rtsxBytes)
            }
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                vm.uiState.value.pendingRoyalTsImport != null
            }
            composeRule.runOnIdle {
                vm.confirmRoyalTsImport(RoyalTsImportPlacementMode.CREATE_NEW_FOLDER, "Vienna Imported")
            }
            composeRule.waitForIdle()

            val wrapperFolder = vm.uiState.value.vaultDocument.folders.firstOrNull { it.name == "Vienna Imported" }
            assertNotNull(wrapperFolder)
            assertEquals(
                wrapperFolder!!.id,
                vm.uiState.value.vaultDocument.connections.first { it.name == "VIE-DC01" }.parentFolderId
            )
            assertTrue(vm.uiState.value.vaultDocument.connections.any { it.id == "conn-ops-01" })
            assertTrue(vm.uiState.value.vaultDocument.credentials.any { it.name == "Vienna Admin" && it.parentFolderId == null })

            // Import with REPLACE_EXISTING -> deletes all existing connection folders & connections
            composeRule.runOnIdle {
                vm.importVaultOrRtszBytes("vienna.rtsx", rtsxBytes)
            }
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                vm.uiState.value.pendingRoyalTsImport != null
            }
            composeRule.runOnIdle {
                vm.confirmRoyalTsImport(RoyalTsImportPlacementMode.REPLACE_EXISTING)
            }
            composeRule.waitForIdle()
            assertFalse(vm.uiState.value.vaultDocument.connections.any { it.id == "conn-ops-01" })
            assertEquals(listOf("VIE-DC01"), vm.uiState.value.vaultDocument.connections.map { it.name })

            // 6. Reset NexusTS Database via confirmation dialog -> wipes vault back to clean empty state
            store.persistVaultToDisk(resetDir, serializer)
            composeRule.runOnIdle {
                vm.openResetDatabaseDialog()
            }
            composeRule.waitForIdle()
            assertTextRendered(strings.dialogResetDatabaseTitle)

            // Safety guard: clicking reset before checking confirmation leaves vault intact
            composeRule
                .onAllNodesWithText(strings.actionConfirmResetDatabase, substring = false)
                .onFirst()
                .performClick()
            composeRule.waitForIdle()
            assertFalse(vm.uiState.value.vaultDocument.connections.isEmpty())

            // Check the confirmation checkbox row, then click confirm reset
            composeRule
                .onAllNodesWithText(strings.resetDatabaseWarningBody, substring = false)
                .onFirst()
                .performClick()
            composeRule.waitForIdle()

            composeRule
                .onAllNodesWithText(strings.actionConfirmResetDatabase, substring = false)
                .onFirst()
                .performClick()
            composeRule.waitForIdle()

            assertTrue(vm.uiState.value.vaultDocument.connections.isEmpty())
            assertTrue(vm.uiState.value.vaultDocument.folders.isEmpty())
            assertTrue(vm.uiState.value.vaultDocument.credentials.isEmpty())
            assertTrue(vm.uiState.value.vaultDocument.smartFolders.isEmpty())

            // 7. Corrupt local .nxts file on cold start is quarantined as .corrupt-*.nxts.bak and surfaces startupRecoveryError
            val corruptDir = Files.createTempDirectory("nexusts-corrupt-quarantine").toFile()
            try {
                val targetVaultFile = java.io.File(corruptDir, ActiveVaultSessionStore.LOCAL_VAULT_FILE_NAME)
                targetVaultFile.writeBytes("CORRUPT-PARTIAL-WRITE-BYTES".toByteArray(StandardCharsets.UTF_8))

                val coldRecoveryStore = ActiveVaultSessionStore()
                val loaded = coldRecoveryStore.loadPersistedVaultIfNeeded(corruptDir, serializer)
                assertFalse(loaded)
                assertFalse("Corrupted primary vault file must be moved aside", targetVaultFile.exists())
                val quarantinedFiles = corruptDir.listFiles()?.filter { it.name.contains(".corrupt-") && it.name.endsWith(".bak") }.orEmpty()
                assertEquals("Corrupted file must be preserved as a .corrupt-*.bak backup", 1, quarantinedFiles.size)
                assertNotNull(coldRecoveryStore.snapshot.value.startupRecoveryError)
            } finally {
                corruptDir.deleteRecursively()
            }
        } finally {
            resetDir.deleteRecursively()
        }
    }

    @Test
    fun v121Beta2DoubleClickOnlyMultiTabRdpSwitchingHamburgerAndFullScreenSession() {
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

        val dcFolder = VaultFolder(
            id = "folder-dc-eu",
            name = "DataCenter EU"
        )
        val rdpConn1 = VaultConnection(
            id = "conn-rdp-beta2-01",
            parentFolderId = dcFolder.id,
            name = "WinServer-Alpha",
            host = "10.20.30.11",
            protocol = VaultProtocolType.RDP,
            favorite = true,
            settings = ProtocolSpecificSettings.Rdp(redirectClipboard = true)
        )
        val rdpConn2 = VaultConnection(
            id = "conn-rdp-beta2-02",
            parentFolderId = dcFolder.id,
            name = "WinServer-Bravo",
            host = "10.20.30.12",
            protocol = VaultProtocolType.RDP,
            favorite = false,
            settings = ProtocolSpecificSettings.Rdp(redirectClipboard = true)
        )
        composeRule.runOnIdle {
            vm.saveFolderAndCredentialToVault(dcFolder)
            vm.saveConnectionToVault(rdpConn1)
            vm.saveConnectionToVault(rdpConn2)
        }
        composeRule.waitForIdle()

        // 1. Hamburger menu button toggles sidebar visibility cleanly
        assertTrue(vm.uiState.value.isSidebarVisible)
        composeRule.onAllNodesWithContentDescription("Toggle Sidebar").onFirst().performClick()
        composeRule.waitForIdle()
        assertFalse(vm.uiState.value.isSidebarVisible)

        composeRule.onAllNodesWithContentDescription("Toggle Sidebar").onFirst().performClick()
        composeRule.waitForIdle()
        assertTrue(vm.uiState.value.isSidebarVisible)

        // 2. Single-clicking a connection in the sidebar ONLY selects it and NEVER initiates an RDP session
        composeRule
            .onAllNodesWithText("WinServer-Alpha", substring = false)
            .onFirst()
            .performClick()
        composeRule.waitForIdle()
        assertEquals("conn-rdp-beta2-01", vm.uiState.value.selectedConnectionId)
        assertTrue(
            "Single-click MUST NOT open a session tab",
            vm.tilingController.allActiveTabs().isEmpty()
        )

        // Wait past doubleClickIntervalMs (420ms) by advancing system wall clock? No: Thread.sleep(450) ensures next pair starts fresh!
        Thread.sleep(460L)

        // 3. Double-clicking WinServer-Alpha opens the 1st RDP session tab; double-clicking WinServer-Bravo opens the 2nd RDP session tab
        composeRule
            .onAllNodesWithText("WinServer-Alpha", substring = false)
            .onFirst()
            .performClick()
        composeRule
            .onAllNodesWithText("WinServer-Alpha", substring = false)
            .onFirst()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(1, vm.tilingController.allActiveTabs().size)
        assertEquals("conn-rdp-beta2-01", vm.tilingController.focusedActiveTab()?.connectionId)

        composeRule
            .onAllNodesWithText("WinServer-Bravo", substring = false)
            .onFirst()
            .performClick()
        composeRule
            .onAllNodesWithText("WinServer-Bravo", substring = false)
            .onFirst()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(2, vm.tilingController.allActiveTabs().size)
        assertEquals("conn-rdp-beta2-02", vm.tilingController.focusedActiveTab()?.connectionId)
        assertTextRendered("WinServer-Bravo • ")
        assertEquals(
            "Inactive 1st RDP session must not stay displayed when 2nd tab is active",
            0,
            composeRule.onAllNodesWithText("WinServer-Alpha • ", substring = true).fetchSemanticsNodes().size
        )

        // 4. External RDP control bar shows automatic "Clipboard Sync" status (no manual Push/Pull Clipboard buttons)
        assertTextRendered("Clipboard Sync")
        assertEquals(0, composeRule.onAllNodesWithText("Push Clipboard", substring = false).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Pull Clipboard", substring = false).fetchSemanticsNodes().size)

        // 5. Clicking the 1st tab chip directly in the main-window tabbed interface switches display to WinServer-Alpha
        val alphaTabId = vm.tilingController.allActiveTabs().first { it.connectionId == "conn-rdp-beta2-01" }.tabId
        val bravoTabId = vm.tilingController.allActiveTabs().first { it.connectionId == "conn-rdp-beta2-02" }.tabId
        composeRule
            .onAllNodesWithText("WinServer-Alpha", substring = false)
            .onLast()
            .performClick()
        composeRule.waitForIdle()
        assertEquals("conn-rdp-beta2-01", vm.tilingController.focusedActiveTab()?.connectionId)
        assertTextRendered("WinServer-Alpha • ")
        assertEquals(
            0,
            composeRule.onAllNodesWithText("WinServer-Bravo • ", substring = true).fetchSemanticsNodes().size
        )

        // Switch back to WinServer-Bravo tab chip via direct UI click on the tab chip
        composeRule
            .onAllNodesWithText("WinServer-Bravo", substring = false)
            .onLast()
            .performClick()
        composeRule.waitForIdle()
        assertEquals("conn-rdp-beta2-02", vm.tilingController.focusedActiveTab()?.connectionId)
        assertTextRendered("WinServer-Bravo • ")

        // 6. Enter Full-Screen Session Mode: active session switches cleanly and Folder Structure launcher opens
        composeRule.runOnIdle {
            vm.enterFullScreenForTab(bravoTabId)
        }
        composeRule.waitForIdle()
        assertTrue(vm.uiState.value.isFullScreenSessionMode)
        assertTextRendered("Folders & Servers")

        // Switch to WinServer-Alpha directly from the Full-Screen top session bar
        composeRule
            .onAllNodesWithText("WinServer-Alpha", substring = false)
            .onFirst()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(alphaTabId, vm.tilingController.focusedOrFirstActiveTab()?.tabId)
        assertTextRendered("WinServer-Alpha • ")

        // Open Full-Screen Folder Structure Server Launcher and verify hierarchical folder tree is displayed
        composeRule
            .onAllNodesWithText("Folders & Servers", substring = false)
            .onFirst()
            .performClick()
        composeRule.waitForIdle()
        assertTextRendered("Double-click a server to connect")
        assertTextRendered("DataCenter EU")

        // Exit Full-Screen Session Mode via Ctrl+Alt+F shortcut action
        composeRule.runOnIdle {
            vm.handleShortcut(NexusShortcutAction.TOGGLE_FULLSCREEN_SESSION) {}
        }
        composeRule.waitForIdle()
        assertFalse(vm.uiState.value.isFullScreenSessionMode)
    }

    @Test
    fun v030Beta2UnclippedProtocolSelectorAndFullScreenServerReadability() {
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

        // 1. Open New Connection dialog and open the Protocol modal picker
        composeRule.runOnIdle {
            vm.openNewConnectionDialog()
        }
        composeRule.waitForIdle()
        assertTrue(vm.uiState.value.isNewConnectionDialogVisible)
        assertTextRendered("${strings.dialogFieldProtocol}: ${VaultProtocolType.RDP.displayName}")

        composeRule
            .onAllNodesWithText("${strings.dialogFieldProtocol}: ${VaultProtocolType.RDP.displayName}", substring = false)
            .onFirst()
            .performClick()
        composeRule.waitForIdle()

        // Every single one of the 23 protocols (including the bottom items previously clipped by AlertDialog Popup bounds)
        // must be present and reachable via scroll in the top-level modal picker.
        assertEquals(23, VaultProtocolType.entries.size)
        VaultProtocolType.entries.forEach { proto ->
            assertTrue(
                "Protocol option '${proto.displayName}' (${proto.name}) must be present in modal picker",
                composeRule.onAllNodesWithText(proto.displayName, substring = false).fetchSemanticsNodes().isNotEmpty()
            )
        }

        // Specifically scroll to and select Wake-on-LAN and Docker Container (previously cut off in DropdownMenu)
        composeRule
            .onAllNodesWithText(VaultProtocolType.UTILITY_WAKE_ON_LAN.displayName, substring = false)
            .onFirst()
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertTextRendered("${strings.dialogFieldProtocol}: ${VaultProtocolType.UTILITY_WAKE_ON_LAN.displayName}")

        composeRule
            .onAllNodesWithText("${strings.dialogFieldProtocol}: ${VaultProtocolType.UTILITY_WAKE_ON_LAN.displayName}", substring = false)
            .onFirst()
            .performClick()
        composeRule.waitForIdle()

        composeRule
            .onAllNodesWithText(VaultProtocolType.DOCKER_CONTAINER.displayName, substring = false)
            .onFirst()
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertTextRendered("${strings.dialogFieldProtocol}: ${VaultProtocolType.DOCKER_CONTAINER.displayName}")

        composeRule.runOnIdle {
            vm.dismissNewConnectionDialog()
        }
        composeRule.waitForIdle()

        // 2. Verify Full-Screen server list renders server names cleanly without prominent "RDP • <host>" subtitle
        val srvFolder = VaultFolder(id = "fld-srv-kb", name = "Servers")
        val rdpServer = VaultConnection(
            id = "conn-aadsrv01",
            parentFolderId = srvFolder.id,
            name = "AADSRV01.kreis-bergstrasse.net",
            host = "10.2.200.32",
            protocol = VaultProtocolType.RDP,
            settings = ProtocolSpecificSettings.Rdp()
        )
        val ftpServer = VaultConnection(
            id = "conn-ftp-archive",
            parentFolderId = srvFolder.id,
            name = "hp-msrv01-ftp",
            host = "10.2.200.41",
            protocol = VaultProtocolType.FTP,
            settings = ProtocolSpecificSettings.FileTransfer(port = 21)
        )
        composeRule.runOnIdle {
            vm.saveFolderAndCredentialToVault(srvFolder)
            vm.saveConnectionToVault(rdpServer)
            vm.saveConnectionToVault(ftpServer)
            vm.launchConnection(rdpServer)
        }
        composeRule.waitForIdle()

        val activeTabId = vm.tilingController.focusedOrFirstActiveTab()!!.tabId
        composeRule.runOnIdle {
            vm.enterFullScreenForTab(activeTabId)
        }
        composeRule.waitForIdle()
        assertTrue(vm.uiState.value.isFullScreenSessionMode)

        composeRule
            .onAllNodesWithContentDescription("Browse Folders & Servers")
            .onFirst()
            .performClick()
        composeRule.waitForIdle()

        assertTextRendered("AADSRV01.kreis-bergstrasse.net")
        assertTextRendered("hp-msrv01-ftp")
        assertEquals(
            "Noisy 'RDP • 10.2.200.32' subtitle must not compete with server names in Full-Screen list",
            0,
            composeRule.onAllNodesWithText("RDP • 10.2.200.32", substring = true).fetchSemanticsNodes().size
        )
        assertEquals(
            "Noisy 'FTP • 10.2.200.41' subtitle must not compete with server names in Full-Screen list",
            0,
            composeRule.onAllNodesWithText("FTP • 10.2.200.41", substring = true).fetchSemanticsNodes().size
        )
    }

    @Test
    fun v0814SidebarActiveConnectionFocusAndRemoteDriveChangeDetection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = buildRealViewModel()

        val server1 = VaultConnection(
            id = "conn-srv-1",
            name = "Server1",
            host = "10.10.0.1",
            protocol = VaultProtocolType.SSH,
            settings = ProtocolSpecificSettings.Ssh(port = 22)
        )
        val server2 = VaultConnection(
            id = "conn-srv-2",
            name = "Server2",
            host = "10.10.0.2",
            protocol = VaultProtocolType.SSH,
            settings = ProtocolSpecificSettings.Ssh(port = 22)
        )
        val inactiveServer3 = VaultConnection(
            id = "conn-srv-3",
            name = "Server3-Inactive",
            host = "10.10.0.3",
            protocol = VaultProtocolType.SSH,
            settings = ProtocolSpecificSettings.Ssh(port = 22)
        )

        vm.saveConnectionToVault(server1)
        vm.saveConnectionToVault(server2)
        vm.saveConnectionToVault(inactiveServer3)

        // Open both Server1 and Server2, then focus Server1 so Server1 is currently active
        vm.launchConnection(server1)
        vm.launchConnection(server2)
        assertTrue(vm.tilingController.activateConnectionIfPresent(server1.id))
        assertEquals(server1.id, vm.tilingController.focusedOrFirstActiveTab()?.connectionId)

        // Clicking active Server2 in the sidebar switches the main workspace tab to Server2 (even if Settings was open)
        vm.selectRailDestination("settings")
        assertEquals("settings", vm.uiState.value.activeRailId)
        vm.selectConnectionInTree(server2.id)
        assertEquals("connections", vm.uiState.value.activeRailId)
        assertEquals(server2.id, vm.tilingController.focusedOrFirstActiveTab()?.connectionId)

        // Clicking inactive Server3 in the sidebar leaves Server2 active and does NOT launch Server3
        vm.selectConnectionInTree(inactiveServer3.id)
        assertEquals(server2.id, vm.tilingController.focusedOrFirstActiveTab()?.connectionId)
        assertEquals(
            setOf(server1.id, server2.id),
            vm.tilingController.allActiveTabs().map { it.connectionId }.toSet()
        )

        // Verify SafLiveGoogleDriveBridge live remote change detection & automatic pull
        val serializer = buildFastSerializer()
        val passphrase = "test-vault-passphrase".toCharArray()
        var remoteBytes = ByteArray(0)
        val fakeTransport = object : SafByteTransport {
            override fun readBytes(uriString: String): ByteArray = remoteBytes.copyOf()
            override fun writeBytes(uriString: String, payload: ByteArray) {
                remoteBytes = payload.copyOf()
            }
            override fun resolveDisplayName(uriString: String): String = "NexusTS-Vault.nxts"
        }
        val bridge = SafLiveGoogleDriveBridge(context, serializer, fakeTransport)
        val uri = Uri.parse("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dv0814")
        bridge.linkPersistentVaultUri(uri, takePersistable = false, documentId = vm.uiState.value.vaultDocument.documentId)

        runBlocking {
            val initialPush = bridge.synchronizeLinkedSafVault(
                currentDocument = vm.uiState.value.vaultDocument,
                vaultPassphrase = passphrase,
                localDeviceId = "Chromebook-A"
            )
            assertTrue(initialPush is SafDriveSyncOutcome.PushedToDrive)
            val syncedDoc = (initialPush as SafDriveSyncOutcome.PushedToDrive).updatedDocument
            assertFalse("No remote change right after push", bridge.hasRemoteContentChanged())

            // Simulate another device updating the same .nxts file in Google Drive
            val peerConn = VaultConnection(
                id = "conn-from-mac",
                name = "MacBook-Peer-Server",
                host = "10.99.0.50",
                protocol = VaultProtocolType.RDP,
                settings = ProtocolSpecificSettings.Rdp(port = 3389)
            )
            val remoteUpdatedDoc = syncedDoc.copy(
                revision = syncedDoc.revision + 1,
                vectorClock = syncedDoc.vectorClock + ("MacBook-Pro" to 1L),
                connections = syncedDoc.connections + peerConn
            )
            remoteBytes = serializer.sealDocument(remoteUpdatedDoc, passphrase.copyOf())

            assertTrue("Remote change must be detected immediately", bridge.hasRemoteContentChanged())
            val pullOutcome = bridge.synchronizeLinkedSafVault(
                currentDocument = syncedDoc,
                vaultPassphrase = passphrase,
                localDeviceId = "Chromebook-A"
            )
            assertTrue(pullOutcome is SafDriveSyncOutcome.PulledFromDrive)
            val pulled = (pullOutcome as SafDriveSyncOutcome.PulledFromDrive).remoteDocument
            assertTrue(pulled.connections.any { it.id == "conn-from-mac" })
            assertFalse("Remote change flag resets after pull", bridge.hasRemoteContentChanged())
        }
        bridge.unlinkPersistentVaultUri()
    }

    @Test
    fun pre10QualityAssuranceShortcutsEmptySplitGuardAnd6LocaleOverviewParity() {
        val vm = buildRealViewModel()

        // 1. Splitting panes when 0 session tabs are open opens Quick Connect instead of cloning empty Overview panes
        assertTrue(vm.tilingController.allActiveTabs().isEmpty())
        vm.handleShortcut(NexusShortcutAction.SPLIT_PANE_VERTICAL) {}
        assertTrue(vm.uiState.value.isQuickConnectVisible)
        assertTrue(vm.tilingController.rootNode.value is PaneNode.Leaf)

        vm.dismissQuickConnect()
        vm.handleShortcut(NexusShortcutAction.SPLIT_PANE_HORIZONTAL) {}
        assertTrue(vm.uiState.value.isQuickConnectVisible)
        assertTrue(vm.tilingController.rootNode.value is PaneNode.Leaf)

        // 2. NexusShortcutEngine.dispatchAndroidKeyEvent returns false when inline handler declines action (DetachedSessionActivity passthrough)
        val engine = com.nexusts.app.core.ui.input.NexusShortcutEngine()
        val ctrlKDown = KeyEvent(
            10L,
            15L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_K,
            0,
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        )
        assertFalse(
            "Declined shortcut must return false so terminal/RDP receives Ctrl+K",
            engine.dispatchAndroidKeyEvent(native = ctrlKDown, remoteCaptureActive = true) { false }
        )

        // Verify Ctrl+Shift+B and Ctrl+Shift+M both dispatch TOGGLE_BROADCAST_INPUT
        var matchedBroadcast = false
        val ctrlShiftB = KeyEvent(
            20L,
            25L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_B,
            0,
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        )
        assertTrue(
            engine.dispatchAndroidKeyEvent(native = ctrlShiftB, remoteCaptureActive = false) { act: NexusShortcutAction ->
                matchedBroadcast = (act == NexusShortcutAction.TOGGLE_BROADCAST_INPUT)
                true
            }
        )
        assertTrue(matchedBroadcast)

        // 3. Verify 6-locale Overview Dashboard & TopBar helpers
        val deStrings = WorkspaceLocalizedStrings.defaultEnglish().copy(navConnections = "Verbindungen")
        assertEquals("Lokales Terminal", deStrings.localTerminalLabel)
        assertEquals("System-Tastenkürzel abfangen", deStrings.remoteKeyCaptureLabel)
        assertEquals("3 Ordner", deStrings.ovFoldersCount(3))
        assertEquals("Verknüpft", deStrings.ovLinked)
        assertEquals("Lokal", deStrings.ovLocal)
    }

    @Test
    fun v099RdpBidirectionalFileAndFolderClipboardDocumentsProviderAndHostStagingWorkEndToEnd() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        val ownAuthority = com.nexusts.app.protocols.rdp.storage.NexusRdpClipboardDocumentsProvider.authority(context)
        val ownProviderClass = com.nexusts.app.protocols.rdp.storage.NexusRdpClipboardDocumentsProvider::class.java
        val ownProviderInfo = android.content.pm.ProviderInfo().apply {
            packageName = context.packageName
            name = ownProviderClass.name
            authority = ownAuthority
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        }
        Shadows.shadowOf(context.packageManager).apply {
            addOrUpdateProvider(ownProviderInfo)
            addIntentFilterForProvider(
                android.content.ComponentName(context.packageName, ownProviderClass.name),
                android.content.IntentFilter("android.content.action.DOCUMENTS_PROVIDER")
            )
        }
        org.robolectric.Robolectric.buildContentProvider(ownProviderClass).create(ownProviderInfo)

        // 1. Remote RDP -> Local Googlebook Files (DocumentsUI): file + nested folder + clipper:opType=1
        val recvBase = com.nexusts.app.protocols.rdp.storage.NexusRdpClipboardDocumentsProvider.recvBaseDirectory(context)
        val clipBatchDir = java.io.File(recvBase, "clip_test_099").apply {
            deleteRecursively()
            mkdirs()
        }
        val expectedPdfBytes = "%PDF-1.7 NexusTS Remote Report Payload\n".toByteArray(StandardCharsets.UTF_8)
        val stagedPdf = java.io.File(clipBatchDir, "Q4_Report.pdf").apply {
            writeBytes(expectedPdfBytes)
        }
        val stagedFolder = java.io.File(clipBatchDir, "Deploy_Scripts").apply { mkdirs() }
        val expectedPs1 = "Write-Host 'Deploying NexusTS 0.9.9'\r\n"
        java.io.File(stagedFolder, "bootstrap.ps1").writeText(expectedPs1, StandardCharsets.UTF_8)
        val nestedDir = java.io.File(stagedFolder, "nested").apply { mkdirs() }
        java.io.File(nestedDir, "config.json").writeText("{\"env\":\"prod\"}", StandardCharsets.UTF_8)

        val publishedSig = com.nexusts.app.protocols.rdp.storage.RdpClipboardHostBridge.publishRemoteFilesToHostClipboard(
            context = context,
            manager = clipboardManager,
            localPaths = listOf(stagedPdf.absolutePath, stagedFolder.absolutePath)
        )
        assertNotNull("Expected remote files to publish onto host ClipboardManager", publishedSig)

        val primaryClip = clipboardManager.primaryClip
        assertNotNull(primaryClip)
        assertEquals(2, primaryClip!!.itemCount)
        assertNotNull(
            "DocumentsUI requires PersistableBundle extras with clipper:opType = 1 (OPERATION_COPY)",
            primaryClip.description.extras
        )
        assertEquals(
            1,
            primaryClip.description.extras!!.getInt("clipper:opType", -1)
        )

        val pdfUri = primaryClip.getItemAt(0).uri
        val folderUri = primaryClip.getItemAt(1).uri
        assertTrue(android.provider.DocumentsContract.isDocumentUri(context, pdfUri))
        assertTrue(android.provider.DocumentsContract.isDocumentUri(context, folderUri))

        // Query & stream single file exactly as AOSP DocumentsUI CopyJob does (API 26+ Bundle query)
        context.contentResolver.query(pdfUri, null, android.os.Bundle.EMPTY, null).use { c ->
            assertNotNull(c)
            assertTrue(c!!.moveToFirst())
            assertEquals(
                "Q4_Report.pdf",
                c.getString(c.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            )
            assertEquals(
                "application/pdf",
                c.getString(c.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE))
            )
            assertEquals(
                expectedPdfBytes.size.toLong(),
                c.getLong(c.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_SIZE))
            )
        }
        val readPdfBytes = context.contentResolver.openInputStream(pdfUri)!!.use { it.readBytes() }
        assertArrayEquals(expectedPdfBytes, readPdfBytes)

        // Query folder & recursive children exactly as AOSP DocumentsUI CopyJob does
        context.contentResolver.query(folderUri, null, android.os.Bundle.EMPTY, null).use { c ->
            assertNotNull(c)
            assertTrue(c!!.moveToFirst())
            assertEquals(
                "Deploy_Scripts",
                c.getString(c.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            )
            assertEquals(
                android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
                c.getString(c.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE))
            )
        }
        val folderDocId = android.provider.DocumentsContract.getDocumentId(folderUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUri(ownAuthority, folderDocId)
        val childEntries = mutableMapOf<String, Pair<String, String>>()
        context.contentResolver.query(childrenUri, null, android.os.Bundle.EMPTY, null).use { c ->
            assertNotNull(c)
            while (c!!.moveToNext()) {
                val id = c.getString(c.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                val name = c.getString(c.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                val mime = c.getString(c.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE))
                childEntries[name] = id to mime
            }
        }
        assertEquals(setOf("nested", "bootstrap.ps1"), childEntries.keys)
        assertEquals(android.provider.DocumentsContract.Document.MIME_TYPE_DIR, childEntries.getValue("nested").second)
        val ps1Uri = android.provider.DocumentsContract.buildDocumentUri(ownAuthority, childEntries.getValue("bootstrap.ps1").first)
        assertEquals(
            expectedPs1,
            context.contentResolver.openInputStream(ps1Uri)!!.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        )

        // Security invariant: path traversal outside cliprdr_recv and write mode must throw SecurityException
        val traversalUri = android.provider.DocumentsContract.buildDocumentUri(ownAuthority, "cliprdr:../../vaults/default.nxts")
        val blockedTraversal = runCatching {
            context.contentResolver.query(traversalUri, null, android.os.Bundle.EMPTY, null)?.close()
        }.exceptionOrNull()
        assertTrue(
            "Expected SecurityException for path traversal, got $blockedTraversal",
            blockedTraversal is SecurityException
        )
        val blockedWrite = runCatching {
            context.contentResolver.openOutputStream(pdfUri, "w")?.close()
        }.exceptionOrNull()
        assertTrue(
            "Expected SecurityException for write mode, got $blockedWrite",
            blockedWrite is SecurityException
        )

        // Echo-loop prevention: own provider URIs on host clipboard must NOT re-stage back to remote
        val echoCheck = com.nexusts.app.protocols.rdp.storage.RdpClipboardHostBridge.extractHostClipboardPayload(
            context = context,
            manager = clipboardManager
        )
        assertSame(
            com.nexusts.app.protocols.rdp.storage.RdpClipboardHostBridge.HostClipboardPayload.OwnOrUnreadableFileIntent,
            echoCheck
        )

        // 2. Local Googlebook Files (DocumentsUI) -> Remote RDP: file + recursive folder staging
        val extRoot = Files.createTempDirectory("nexusts-googlebook-files-ext").toFile()
        try {
            TestExternalFilesDocumentsProvider.rootDir = extRoot
            val extAuthority = "com.android.externalstorage.documents.test099"
            val extProviderInfo = android.content.pm.ProviderInfo().apply {
                packageName = context.packageName
                name = TestExternalFilesDocumentsProvider::class.java.name
                authority = extAuthority
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            }
            org.robolectric.Robolectric.buildContentProvider(
                TestExternalFilesDocumentsProvider::class.java
            ).create(extProviderInfo)

            val invoiceBytes = "Invoice-2026-Total: 4200 EUR".toByteArray(StandardCharsets.UTF_8)
            java.io.File(extRoot, "Invoice_2026.csv").writeBytes(invoiceBytes)
            val assetsDir = java.io.File(extRoot, "Project_Assets").apply { mkdirs() }
            java.io.File(assetsDir, "logo.svg").writeText("<svg/>", StandardCharsets.UTF_8)
            val subDir = java.io.File(assetsDir, "sub").apply { mkdirs() }
            java.io.File(subDir, "notes.txt").writeText("Hello from Googlebook Files!", StandardCharsets.UTF_8)

            val extFileUri = android.provider.DocumentsContract.buildDocumentUri(extAuthority, "Invoice_2026.csv")
            val extFolderUri = android.provider.DocumentsContract.buildDocumentUri(extAuthority, "Project_Assets")
            val localCopyClip = android.content.ClipData(
                android.content.ClipDescription(
                    "Copied from Files",
                    arrayOf("text/csv", android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                ),
                android.content.ClipData.Item(extFileUri)
            ).apply {
                addItem(android.content.ClipData.Item(extFolderUri))
            }
            clipboardManager.setPrimaryClip(localCopyClip)

            val extracted = com.nexusts.app.protocols.rdp.storage.RdpClipboardHostBridge.extractHostClipboardPayload(
                context = context,
                manager = clipboardManager
            )
            assertTrue("Expected Files payload, got $extracted", extracted is com.nexusts.app.protocols.rdp.storage.RdpClipboardHostBridge.HostClipboardPayload.Files)
            val filesPayload = extracted as com.nexusts.app.protocols.rdp.storage.RdpClipboardHostBridge.HostClipboardPayload.Files
            assertEquals(2, filesPayload.uris.size)

            val stagedPaths = com.nexusts.app.protocols.rdp.storage.RdpClipboardHostBridge.stageUrisToLocalFiles(
                context = context,
                uris = filesPayload.uris
            )
            assertEquals(2, stagedPaths.size)
            val stagedInvoice = java.io.File(stagedPaths[0])
            val stagedAssets = java.io.File(stagedPaths[1])
            assertEquals("Invoice_2026.csv", stagedInvoice.name)
            assertArrayEquals(invoiceBytes, stagedInvoice.readBytes())
            assertEquals("Project_Assets", stagedAssets.name)
            assertTrue(stagedAssets.isDirectory)
            assertEquals("<svg/>", java.io.File(stagedAssets, "logo.svg").readText(StandardCharsets.UTF_8))
            assertEquals(
                "Hello from Googlebook Files!",
                java.io.File(stagedAssets, "sub/notes.txt").readText(StandardCharsets.UTF_8)
            )

            // Also verify text/uri-list payload extraction from desktop/Wayland bridges
            val directLocalFile = java.io.File(extRoot, "waybridge_readme.md").apply {
                writeText("# Bridged File", StandardCharsets.UTF_8)
            }
            val uriListClip = android.content.ClipData(
                android.content.ClipDescription("URI List", arrayOf("text/uri-list")),
                android.content.ClipData.Item(Uri.fromFile(directLocalFile).toString())
            )
            val uriListExtracted = com.nexusts.app.protocols.rdp.storage.RdpClipboardHostBridge.extractClipDataPayload(
                context = context,
                clip = uriListClip
            )
            assertTrue(uriListExtracted is com.nexusts.app.protocols.rdp.storage.RdpClipboardHostBridge.HostClipboardPayload.Files)
            val stagedUriList = com.nexusts.app.protocols.rdp.storage.RdpClipboardHostBridge.stageUrisToLocalFiles(
                context = context,
                uris = (uriListExtracted as com.nexusts.app.protocols.rdp.storage.RdpClipboardHostBridge.HostClipboardPayload.Files).uris
            )
            assertEquals(1, stagedUriList.size)
            assertEquals("# Bridged File", java.io.File(stagedUriList.first()).readText(StandardCharsets.UTF_8))
        } finally {
            extRoot.deleteRecursively()
            clipBatchDir.deleteRecursively()
        }

        // 3. VaultHierarchySanitizer self-heals uncustomized legacy redirectClipboard=false while preserving explicit false
        val legacyVault = NxtsVaultDocument(
            name = "Legacy Clipboard Vault",
            connections = listOf(
                VaultConnection(
                    id = "rdp-legacy-uninit",
                    name = "Legacy RDP",
                    host = "10.0.0.11",
                    protocol = VaultProtocolType.RDP,
                    settings = ProtocolSpecificSettings.Rdp(
                        redirectClipboard = false,
                        redirectClipboardCustomized = false
                    )
                ),
                VaultConnection(
                    id = "rdp-explicit-off",
                    name = "Locked Down Jumpbox",
                    host = "10.0.0.12",
                    protocol = VaultProtocolType.RDP,
                    settings = ProtocolSpecificSettings.Rdp(
                        redirectClipboard = false,
                        redirectClipboardCustomized = true
                    )
                )
            )
        )
        val healed = VaultHierarchySanitizer.sanitizeVaultDocument(legacyVault)
        val healedLegacy = healed.connections.first { it.id == "rdp-legacy-uninit" }.settings as ProtocolSpecificSettings.Rdp
        val healedExplicit = healed.connections.first { it.id == "rdp-explicit-off" }.settings as ProtocolSpecificSettings.Rdp
        assertTrue("Uncustomized legacy redirectClipboard=false must self-heal to true", healedLegacy.redirectClipboard)
        assertFalse("Explicitly disabled redirectClipboard must remain false", healedExplicit.redirectClipboard)
    }
}

class TestExternalFilesDocumentsProvider : android.provider.DocumentsProvider() {
    companion object {
        @Volatile
        var rootDir: java.io.File = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp")
    }

    private val defaultDocCols = arrayOf(
        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
        android.provider.DocumentsContract.Document.COLUMN_SIZE,
        android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        android.provider.DocumentsContract.Document.COLUMN_FLAGS
    )

    override fun onCreate(): Boolean = true

    override fun getDocumentType(documentId: String?): String =
        if (resolve(documentId).isDirectory) {
            android.provider.DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            "application/octet-stream"
        }

    override fun queryRoots(projection: Array<out String>?): android.database.Cursor =
        android.database.MatrixCursor(
            projection ?: arrayOf(
                android.provider.DocumentsContract.Root.COLUMN_ROOT_ID,
                android.provider.DocumentsContract.Root.COLUMN_DOCUMENT_ID
            )
        )

    override fun queryDocument(documentId: String?, projection: Array<out String>?): android.database.Cursor {
        val f = resolve(documentId)
        val c = android.database.MatrixCursor(projection ?: defaultDocCols)
        addRow(c, documentId.orEmpty(), f)
        return c
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): android.database.Cursor {
        val dir = resolve(parentDocumentId)
        val c = android.database.MatrixCursor(projection ?: defaultDocCols)
        val base = rootDir.canonicalFile
        for (child in dir.listFiles().orEmpty()) {
            val rel = child.canonicalFile.path.removePrefix(base.path + java.io.File.separator)
                .replace(java.io.File.separatorChar, '/')
            addRow(c, rel, child)
        }
        return c
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: android.os.CancellationSignal?
    ): android.os.ParcelFileDescriptor =
        android.os.ParcelFileDescriptor.open(resolve(documentId), android.os.ParcelFileDescriptor.MODE_READ_ONLY)

    private fun resolve(docId: String?): java.io.File =
        if (docId.isNullOrEmpty()) rootDir else java.io.File(rootDir, docId)

    private fun addRow(cursor: android.database.MatrixCursor, id: String, file: java.io.File) {
        val row = cursor.newRow()
        val mime = if (file.isDirectory) {
            android.provider.DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            "application/octet-stream"
        }
        for (col in cursor.columnNames) {
            when (col) {
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(id)
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(file.name)
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(mime)
                android.provider.DocumentsContract.Document.COLUMN_SIZE -> row.add(if (file.isDirectory) 0L else file.length())
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(file.lastModified())
                android.provider.DocumentsContract.Document.COLUMN_FLAGS -> row.add(0)
                else -> row.add(null)
            }
        }
    }
}




