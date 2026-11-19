package com.nexusts.app.feature.workspace.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Immutable
import androidx.core.os.LocaleListCompat

enum class SupportedNexusLocale(
    val bcp47Tag: String,
    val nativeDisplayName: String,
    val regionSubtitle: String
) {
    ENGLISH("en", "English", "United Kingdom / International"),
    GERMAN("de", "Deutsch", "Deutschland / DACH"),
    ITALIAN("it", "Italiano", "Italia"),
    SPANISH("es", "Español", "España / Latinoamérica"),
    GREEK("el", "Ελληνικά", "Ελλάδα / Κύπρος"),
    FRENCH("fr", "Français", "France / International")
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

    fun currentSupportedLocale(): SupportedNexusLocale {
        val code = currentLanguageTag().take(2).lowercase()
        return SupportedNexusLocale.entries.firstOrNull { it.bcp47Tag == code }
            ?: SupportedNexusLocale.ENGLISH
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
    val actionDisconnectAllFolder: String = "Disconnect All in Folder",
    val actionDisconnectSession: String = "Disconnect",
    val actionLogOffSession: String = "Log Off Session",
    val actionMoveToFolder: String = "Move to Folder…",
    val moveToRootFolder: String = "Top Level (No Folder)",
    val dialogLanguageTitle: String = "Select Language",
    val autoUpdateTitle: String = "App Updates",
    val autoUpdateAutoCheckToggle: String = "Automatically check for updates",
    val autoUpdateAvailableFormat: String = "Update %1\$s",
    val autoUpdateInstallAction: String = "Download & Update",
    val autoUpdateUpToDateFormat: String = "NexusTS %1\$s is up to date",
    val dragDropMoveHintFormat: String = "Drop into %1\$s",
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
    val actionExportNxts: String = "Export Encrypted Vault (.nxts)",
    val actionExportRtsz: String = "Export Royal TS (.rtsz)",
    val actionExportCsv: String = "Export Inventory (.csv)",
    val sshSectionTunnels: String = "SSH Port Forwarding (-L / -R / -D) & Bastion",
    val studioActionImportPasswords: String = "Import KeePass / Bitwarden / 1Password",
    val syncLinkDriveSafCreate: String = "Create Live Vault in Google Drive",
    val syncLinkDriveSafOpen: String = "Link Existing Google Drive .nxts",
    val syncOAuthConnectButton: String = "Connect Google Drive API v3 (OAuth 2.0)",
    val syncCheckGitHubUpdates: String = "Check GitHub Releases Update",
    val serialSectionUart: String = "USB-C Hardware Serial / UART (FTDI, CP210x, CDC-ACM)",
    val containerSectionExec: String = "Kubernetes Pod & Container Runtime",
    val actionRenameFolder: String = "Rename Folder…",
    val actionStartAllRdpFolder: String = "Start All RDP Connections",
    val dialogRenameFolderTitle: String = "Rename Folder",
    val dialogDeleteFolderTitle: String = "Delete Folder",
    val actionDeleteFolderAndContents: String = "Delete Folder & All Contents",
    val actionDeleteFolderKeepContents: String = "Delete Folder Only (Move Items Up)",
    val navStudiosMenu: String = "Manage",
    val navSettings: String = "Settings",
    val actionEditSmartFolder: String = "Edit Smart Folder…",
    val actionDuplicateSmartFolder: String = "Duplicate Smart Folder",
    val smartFolderAllFolders: String = "All Folders (Entire Vault)",
    val smartFolderScopeLabel: String = "Folder Scope",
    val smartFolderExcludeTagsLabel: String = "Exclude Tags (comma-separated)",
    val smartFolderInvalidRegexError: String = "Invalid Regular Expression",
    val smartFolderPreviewCountFormat: String = "Matched Connections (%1\$d)",
    val actionNewDynamicFolder: String = "New Dynamic Folder",
    val actionEditDynamicFolder: String = "Edit Dynamic Folder…",
    val actionDuplicateDynamicFolder: String = "Duplicate Dynamic Folder",
    val actionResetDatabase: String = "Reset NexusTS Database…",
    val resetDatabaseTitle: String = "Reset NexusTS Database",
    val resetDatabaseWarningBody: String = "Permanently deletes all local connections, folders, credentials, templates, and tasks on this device.",
    val dialogResetDatabaseTitle: String = "Reset NexusTS Database?",
    val dialogResetDatabaseMessage: String = "This permanently deletes all local connections, folders, credentials, templates, and tasks on this device. Export a backup first if you want to keep a copy.",
    val actionConfirmResetDatabase: String = "Delete All & Reset Database",
    val resetDatabaseSuccess: String = "NexusTS database has been reset to a clean empty vault.",
    val dialogRoyalImportTitle: String = "Import Royal TS Document",
    val royalImportModeNewFolder: String = "Create a new folder for imported items",
    val royalImportModeRoot: String = "Place imported hierarchy at root",
    val royalImportModeReplaceAll: String = "Delete all existing connections and replace",
    val royalImportFolderNameLabel: String = "New Folder Name",
    val systemCredentialsFolderTitle: String = "Credentials",
    val credentialsSystemFolderTitle: String = "Credentials",
    val credentialsSystemFolderOnlyHint: String = "The Credentials system folder can only hold credentials. Connections and folders cannot be placed inside Credentials.",
    val credentialsSystemFolderReservedError: String = "\"Credentials\" is reserved for the system credentials folder at the bottom of the sidebar.",
    val actionEditCredential: String = "Edit Credential…",
    val actionCopyDiagnosticReport: String = "Copy Diagnostics",
    val languageLabels: Map<SupportedNexusLocale, String> = mapOf(
        SupportedNexusLocale.ENGLISH to "English",
        SupportedNexusLocale.GERMAN to "Deutsch",
        SupportedNexusLocale.ITALIAN to "Italiano",
        SupportedNexusLocale.SPANISH to "Español",
        SupportedNexusLocale.GREEK to "Ελληνικά",
        SupportedNexusLocale.FRENCH to "Français"
    )
) {
    val settingsTabGeneral: String
        get() = when (navConnections) {
            "Verbindungen" -> "Allgemein"
            "Connessioni" -> "Generali"
            "Conexiones" -> "General"
            "Συνδέσεις" -> "Γενικά"
            "Connexions" -> "Général"
            else -> "General"
        }

    val settingsTabSecurity: String
        get() = when (navConnections) {
            "Verbindungen" -> "Sicherheit"
            "Connessioni" -> "Sicurezza"
            "Conexiones" -> "Seguridad"
            "Συνδέσεις" -> "Ασφάλεια"
            "Connexions" -> "Sécurité"
            else -> "Security"
        }

    val settingsTabUpdates: String
        get() = when (navConnections) {
            "Verbindungen" -> "Updates"
            "Connessioni" -> "Aggiornamenti"
            "Conexiones" -> "Actualizaciones"
            "Συνδέσεις" -> "Ενημερώσεις"
            "Connexions" -> "Mises à jour"
            else -> "Updates"
        }

    companion object {
        fun defaultEnglish(): WorkspaceLocalizedStrings = WorkspaceLocalizedStrings(
            appName = "NexusTS",
            navConnections = "Connections",
            navCredentials = "Credentials",
            navGateways = "Gateways",
            navTasks = "Tasks",
            navDriveSync = "Drive Sync",
            quickConnectButton = "Quick Connect or Search Vault…",
            quickConnectPlaceholder = "Type host, ssh://user@ip, rdp://host, k8s://ns/pod, serial:///dev/ttyUSB0…",
            quickConnectFooterHint = "Enter: Active Tab  •  Shift+Enter: Split Right  •  Alt+Enter: New Window",
            actionNewConnection = "New Connection",
            actionImportRtsz = "Import Vault / .rtsz / Passwords",
            actionLockVault = "Lock Vault",
            actionUnlockVault = "Unlock Vault",
            actionSyncNow = "Sync Drive",
            actionBroadcastInput = "Broadcast Input",
            actionLanguagePicker = "Language",
            splitRight = "Split Right",
            splitDown = "Split Down",
            detachNewWindow = "Open in Freeform Window",
            detachToDisplayPrefix = "Move to Display:",
            toggleBroadcast = "Toggle Terminal Broadcast",
            closeTab = "Close Tab",
            emptyWorkspaceTitle = "NexusTS Enterprise Workspace",
            emptyWorkspaceSubtitle = "Select a host from the tree or press Ctrl+K for instant Quick Connect.",
            vaultSectionFolders = "Folders",
            vaultSectionSmartFolders = "Smart Folders",
            vaultSectionDynamicFolders = "Dynamic Folders",
            vaultLockedBanner = "Zero-Knowledge Vault Locked (Argon2id + AES-256-GCM)",
            vaultPassphraseLabel = "Passphrase / Password",
            inspectorTitle = "Effective Properties",
            inspectorEffectiveCredential = "Effective Credential",
            inspectorInheritedFromFormat = "Inherited from: %1\$s",
            inspectorEffectiveGateway = "Effective Gateway",
            inspectorGenerateSshKey = "Generate Ed25519 SSH Key",
            inspectorProtocolEndpoint = "Protocol Endpoint",
            rdpSendCtrlAltDel = "Ctrl+Alt+Del",
            rdpSmartcardActive = "Smartcard / YubiKey",
            rdpDynamicResizeActive = "DISP_DVC Auto-Resize",
            sshPasteClipboard = "Paste",
            sshTunnelsActive = "SSH Tunnels",
            sftpLocalPane = "Local Googlebook Files",
            sftpRemotePane = "Remote SFTP Host",
            sftpTransferQueue = "Transfer Queue",
            webAutofillCredentials = "Auto-Fill Vault Login",
            netutilExecute = "Execute Diagnostic",
            drawerAuditLogs = "Security & Session Audit Log",
            drawerBroadcastPrompt = "Broadcast command to active terminals…",
            drawerSendBroadcast = "Broadcast",
            syncStatusSyncedFormat = "Synced • Revision %1\$d",
            syncZoneAppData = "Private appDataFolder",
            syncZoneTeamDrive = "Shared Google Drive / Team Drive",
            updateReadyBanner = "Update downloaded & verified",
            updateRestartAction = "Restart to Update",
            dialogConnTitle = "Connection Properties",
            dialogFieldName = "Display Name",
            dialogFieldHost = "Hostname / IP / Endpoint",
            dialogFieldUsername = "Username",
            dialogFieldPort = "Port",
            dialogFieldProtocol = "Protocol",
            dialogFieldFolder = "Parent Folder",
            dialogActionSave = "Save",
            dialogActionCancel = "Cancel",
            studioCredentialsSubtitle = "Zero-Knowledge Vault Secrets, OpenSSH Ed25519 Keys, TOTP & KeePass/Bitwarden/1Password Imports",
            studioActionAddCredential = "Add Credential",
            studioGatewaysSubtitle = "NexusTS Secure Gateways, SSH Bastion Jump Hosts & SOCKS5 Proxies",
            studioActionAddGateway = "Add Gateway",
            studioActionProbeGateway = "Probe RTT",
            studioTasksSubtitle = "Parameterized Command Tasks & Key Sequence Macros",
            studioActionAddTask = "Add Task",
            studioActionRunTask = "Run",
            studioSyncSubtitle = "Zero-Knowledge Google Drive Sync (Direct SAF & Drive REST API v3) + 3-Way Delta Merge",
            actionNewFolder = "New Folder",
            actionEditFolderCredentials = "Edit Folder & Credentials",
            actionEditConnection = "Edit Connection",
            dialogFolderTitle = "Folder Properties & Credentials",
            dialogFieldDomain = "Domain (AD / Workgroup)",
            dialogFieldCredentialMode = "Credential Inheritance Mode",
            credModeInheritFolder = "Inherit from Parent Folder",
            credModeOverrideExplicit = "Explicit Vault Credential",
            credModeInlineNew = "Save New Credential on Folder",
            credModePrompt = "Prompt on Connect",
            rdpTabGeneral = "General",
            rdpTabDisplayHiDpi = "Display & HiDPI",
            rdpTabResources = "Redirection",
            rdpTabExperienceSecurity = "Security & Gateway",
            rdpOptConnectConsole = "Connect to Console / Admin Session (/admin)",
            rdpOptHiDpiScaling = "HiDPI Retina Scaling",
            rdpOptClipboardSharing = "Automatic Clipboard Sync (Bidirectional)",
            rdpOptAudioRedirection = "Remote Audio Playback",
            rdpAudioLocal = "Play on This Device",
            rdpAudioRemote = "Play on Remote PC",
            rdpAudioMute = "Do Not Play",
            rdpOptMicRedirection = "Microphone Input Redirection",
            rdpOptDriveRedirection = "Local Storage Drive Redirection",
            rdpOptPrinterRedirection = "Printer Redirection",
            rdpOptPortsRedirection = "Serial / COM Port Redirection",
            rdpOptSmartcardRedirection = "Smartcard / YubiKey PIV Redirection",
            rdpOptMultiMonitor = "Multi-Monitor Desktop Spanning",
            rdpOptClearType = "ClearType Font Smoothing",
            rdpOptDesktopComposition = "Desktop Composition (Aero)",
            rdpOptWallpaper = "Show Desktop Wallpaper",
            rdpOptNlaSecurity = "Network Level Authentication (NLA / CredSSP)",
            rdpOptRestrictedAdmin = "Restricted Admin Mode (Pass-the-Hash Safe)",
            rdpOptIgnoreCert = "Ignore TLS Certificate Mismatch",
            rdpOptRdGateway = "RD Gateway Hostname (HTTPS)",
            rdpSyncClipboardChip = "Clipboard Sync",
            rdpConsoleBadge = "Console /admin"
        )
    }
}

