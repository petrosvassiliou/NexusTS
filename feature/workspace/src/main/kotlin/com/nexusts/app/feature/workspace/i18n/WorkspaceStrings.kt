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
    val languageLabels: Map<SupportedNexusLocale, String>
)
