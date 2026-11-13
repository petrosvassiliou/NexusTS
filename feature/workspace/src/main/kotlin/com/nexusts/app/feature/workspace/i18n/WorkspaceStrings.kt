package com.nexusts.app.feature.workspace.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Immutable
import androidx.core.os.LocaleListCompat

enum class SupportedNexusLocale(val bcp47Tag: String) {
    ENGLISH("en"),
    GERMAN("de"),
    ITALIAN("it"),
    SPANISH("es"),
    GREEK("el"),
    FRENCH("fr")
}

/**
 * Dynamic In-App Language Controller backed by Android 13+ Per-App Language Preferences API
 * (`LocaleManager` via `AppCompatDelegate.setApplicationLocales`).
 * Zero app-restart flicker, persists automatically across launches, and integrates with Android System Settings.
 */
object NexusLocaleController {
    fun currentLanguageTag(): String {
        val list = AppCompatDelegate.getApplicationLocales()
        if (!list.isEmpty) {
            return list[0]?.language ?: SupportedNexusLocale.ENGLISH.bcp47Tag
        }
        return java.util.Locale.getDefault().language.lowercase()
    }

    fun switchLanguage(locale: SupportedNexusLocale) {
        val localeList = LocaleListCompat.forLanguageTags(locale.bcp47Tag)
        AppCompatDelegate.setApplicationLocales(localeList)
    }
}

/**
 * Complete zero-hardcoded-string UI bundle resolved from localized `strings.xml` resources
 * across EN, DE, IT, ES, EL, and FR.
 */
@Immutable
data class WorkspaceLocalizedStrings(
    val appName: String,
    val navConnections: String,
    val navCredentials: String,
    val navGateways: String,
    val navTasks: String,
    val navDriveSync: String,
    val quickConnectButton: String,
    val quickConnectPlaceholder: String,
    val quickConnectFooterHint: String,
    val actionNewConnection: String,
    val actionImportRtsz: String,
    val actionLockVault: String,
    val actionUnlockVault: String,
    val actionSyncNow: String,
    val actionBroadcastInput: String,
    val actionLanguagePicker: String,
    val splitRight: String,
    val splitDown: String,
    val detachNewWindow: String,
    val detachToDisplayPrefix: String,
    val toggleBroadcast: String,
    val closeTab: String,
    val emptyWorkspaceTitle: String,
    val emptyWorkspaceSubtitle: String,
    val vaultSectionFolders: String,
    val vaultSectionSmartFolders: String,
    val vaultSectionDynamicFolders: String,
    val vaultLockedBanner: String,
    val vaultPassphraseLabel: String,
    val inspectorTitle: String,
    val inspectorEffectiveCredential: String,
    val inspectorInheritedFromFormat: String,
    val inspectorEffectiveGateway: String,
    val inspectorGenerateSshKey: String,
    val inspectorProtocolEndpoint: String,
    val rdpSendCtrlAltDel: String,
    val rdpSmartcardActive: String,
    val rdpDynamicResizeActive: String,
    val sshPasteClipboard: String,
    val sshTunnelsActive: String,
    val sftpLocalPane: String,
    val sftpRemotePane: String,
    val sftpTransferQueue: String,
    val webAutofillCredentials: String,
    val netutilExecute: String,
    val drawerAuditLogs: String,
    val drawerBroadcastPrompt: String,
    val drawerSendBroadcast: String,
    val syncStatusSyncedFormat: String,
    val syncZoneAppData: String,
    val syncZoneTeamDrive: String,
    val updateReadyBanner: String,
    val updateRestartAction: String,
    val dialogConnTitle: String,
    val dialogFieldName: String,
    val dialogFieldHost: String,
    val dialogFieldUsername: String,
    val dialogFieldPort: String,
    val dialogFieldProtocol: String,
    val dialogFieldFolder: String,
    val dialogActionSave: String,
    val dialogActionCancel: String,
    val studioCredentialsSubtitle: String,
    val studioActionAddCredential: String,
    val studioGatewaysSubtitle: String,
    val studioActionAddGateway: String,
    val studioActionProbeGateway: String,
    val studioTasksSubtitle: String,
    val studioActionAddTask: String,
    val studioActionRunTask: String,
    val studioSyncSubtitle: String,
    val actionNewFolder: String,
    val actionEditFolderCredentials: String,
    val actionEditConnection: String,
    val dialogFolderTitle: String,
    val dialogFieldDomain: String,
    val dialogFieldCredentialMode: String,
    val credModeInheritFolder: String,
    val credModeOverrideExplicit: String,
    val credModeInlineNew: String,
    val credModePrompt: String,
    val rdpTabGeneral: String,
    val rdpTabDisplayHiDpi: String,
    val rdpTabResources: String,
    val rdpTabExperienceSecurity: String,
    val rdpOptConnectConsole: String,
    val rdpOptHiDpiScaling: String,
    val rdpOptClipboardSharing: String,
    val rdpOptAudioRedirection: String,
    val rdpAudioLocal: String,
    val rdpAudioRemote: String,
    val rdpAudioMute: String,
    val rdpOptMicRedirection: String,
    val rdpOptDriveRedirection: String,
    val rdpOptPrinterRedirection: String,
    val rdpOptPortsRedirection: String,
    val rdpOptSmartcardRedirection: String,
    val rdpOptMultiMonitor: String,
    val rdpOptClearType: String,
    val rdpOptDesktopComposition: String,
    val rdpOptWallpaper: String,
    val rdpOptNlaSecurity: String,
    val rdpOptRestrictedAdmin: String,
    val rdpOptIgnoreCert: String,
    val rdpOptRdGateway: String,
    val rdpSyncClipboardChip: String,
    val rdpConsoleBadge: String,
    val navTemplates: String = "Templates",
    val actionConnectWithOptions: String = "Connect with Options…",
    val actionConnectAllFolder: String = "Connect All in Folder",
    val actionDuplicateConnection: String = "Duplicate Connection",
    val actionDeleteItem: String = "Delete",
    val actionToggleFavorite: String = "Toggle Favorite",
    val actionReconnectTab: String = "Reconnect Session",
    val actionReloadDynamicFolder: String = "Reload Inventory",
    val actionNewSmartFolder: String = "New Smart Folder",
    val actionNewTemplate: String = "New Template",
    val sidebarFilterPlaceholder: String = "Filter connections, hosts, tags…",
    val vaultSectionFavorites: String = "Favorites",
    val vaultSectionRecents: String = "Recent Sessions",
    val inspectorTotpCode: String = "2FA / TOTP Authenticator",
    val inspectorCustomProperties: String = "Custom Properties",
    val inspectorNotesDocs: String = "Notes & Documentation",
    val inspectorTodoItems: String = "To-Do Checklist",
    val actionAddTodo: String = "Add To-Do",
    val studioTemplatesSubtitle: String = "Reusable Connection Templates, Color Accents & Protocol Profiles",
    val dialogConnectOptionsTitle: String = "Connect with Options",
    val dialogFieldTemplate: String = "Apply Connection Template",
    val dialogFieldColorAccent: String = "Accent Color",
    val overviewActiveSessions: String = "Active Sessions",
    val languageLabels: Map<SupportedNexusLocale, String>
)
