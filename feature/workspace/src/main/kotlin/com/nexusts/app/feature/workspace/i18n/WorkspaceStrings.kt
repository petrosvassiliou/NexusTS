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

    val localTerminalLabel: String
        get() = when (navConnections) {
            "Verbindungen" -> "Lokales Terminal"
            "Connessioni" -> "Terminale locale"
            "Conexiones" -> "Terminal local"
            "Συνδέσεις" -> "Τοπικό τερματικό"
            "Connexions" -> "Terminal local"
            else -> "Local Terminal"
        }

    val remoteKeyCaptureLabel: String
        get() = when (navConnections) {
            "Verbindungen" -> "System-Tastenkürzel abfangen"
            "Connessioni" -> "Cattura scorciatoie di sistema"
            "Conexiones" -> "Capturar atajos del sistema"
            "Συνδέσεις" -> "Δέσμευση συντομεύσεων συστήματος"
            "Connexions" -> "Capturer les raccourcis système"
            else -> "Remote Key Capture"
        }

    fun ovFoldersCount(count: Int): String = when (navConnections) {
        "Verbindungen" -> "$count Ordner"
        "Connessioni" -> "$count cartelle"
        "Conexiones" -> "$count carpetas"
        "Συνδέσεις" -> "$count φάκελοι"
        "Connexions" -> "$count dossiers"
        else -> "$count folders"
    }

    fun ovDetachedWindows(count: Int): String = when (navConnections) {
        "Verbindungen" -> "$count gelöste Fenster"
        "Connessioni" -> "$count finestre staccate"
        "Conexiones" -> "$count ventanas separadas"
        "Συνδέσεις" -> "$count αποσπασμένα παράθυρα"
        "Connexions" -> "$count fenêtres détachées"
        else -> "$count detached windows"
    }

    val ovCredentialsTotp: String
        get() = when (navConnections) {
            "Verbindungen" -> "Anmeldedaten & TOTP"
            "Connessioni" -> "Credenziali & TOTP"
            "Conexiones" -> "Credenciales y TOTP"
            "Συνδέσεις" -> "Διαπιστευτήρια & TOTP"
            "Connexions" -> "Identifiants & TOTP"
            else -> "Credentials & TOTP"
        }

    val ovSshAndPasswords: String
        get() = when (navConnections) {
            "Verbindungen" -> "SSH-Schlüssel & Passwörter"
            "Connessioni" -> "Chiavi SSH & password"
            "Conexiones" -> "Claves SSH y contraseñas"
            "Συνδέσεις" -> "Κλειδιά SSH & κωδικοί"
            "Connexions" -> "Clés SSH & mots de passe"
            else -> "SSH Keys & Passwords"
        }

    val ovLinked: String
        get() = when (navConnections) {
            "Verbindungen" -> "Verknüpft"
            "Connessioni" -> "Collegata"
            "Conexiones" -> "Vinculada"
            "Συνδέσεις" -> "Συνδεδεμένο"
            "Connexions" -> "Lié"
            else -> "Linked"
        }

    val ovLocal: String
        get() = when (navConnections) {
            "Verbindungen" -> "Lokal"
            "Connessioni" -> "Locale"
            "Conexiones" -> "Local"
            "Συνδέσεις" -> "Τοπικό"
            "Connexions" -> "Local"
            else -> "Local"
        }

    val ovLocalEncryptedVault: String
        get() = when (navConnections) {
            "Verbindungen" -> "Lokaler verschlüsselter Tresor"
            "Connessioni" -> "Cassaforte locale cifrata"
            "Conexiones" -> "Bóveda cifrada local"
            "Συνδέσεις" -> "Τοπικό κρυπτογραφημένο θησαυροφυλάκιο"
            "Connexions" -> "Coffre chiffré local"
            else -> "Local encrypted vault"
        }

    val appearanceTitle: String
        get() = when (navConnections) {
            "Verbindungen" -> "Erscheinungsbild"
            "Connessioni" -> "Aspetto"
            "Conexiones" -> "Apariencia"
            "Συνδέσεις" -> "Εμφάνιση"
            "Connexions" -> "Apparence"
            else -> "Appearance"
        }

    val themeModeSystem: String
        get() = when (navConnections) {
            "Verbindungen" -> "System"
            "Connessioni" -> "Sistema"
            "Conexiones" -> "Sistema"
            "Συνδέσεις" -> "Σύστημα"
            "Connexions" -> "Système"
            else -> "System"
        }

    val themeModeLight: String
        get() = when (navConnections) {
            "Verbindungen" -> "Hell"
            "Connessioni" -> "Chiaro"
            "Conexiones" -> "Claro"
            "Συνδέσεις" -> "Φωτεινό"
            "Connexions" -> "Clair"
            else -> "Light"
        }

    val themeModeDark: String
        get() = when (navConnections) {
            "Verbindungen" -> "Dunkel"
            "Connessioni" -> "Scuro"
            "Conexiones" -> "Oscuro"
            "Συνδέσεις" -> "Σκοτεινό"
            "Connexions" -> "Sombre"
            else -> "Dark"
        }

    val statusActiveBadge: String
        get() = when (navConnections) {
            "Verbindungen" -> "Aktiv"
            "Connessioni" -> "Attivo"
            "Conexiones" -> "Activo"
            "Συνδέσεις" -> "Ενεργό"
            "Connexions" -> "Actif"
            else -> "Active"
        }

    val statusSyncingLabel: String
        get() = when (navConnections) {
            "Verbindungen" -> "Synchronisiere…"
            "Connessioni" -> "Sincronizzazione…"
            "Conexiones" -> "Sincronizando…"
            "Συνδέσεις" -> "Συγχρονισμός…"
            "Connexions" -> "Synchronisation…"
            else -> "Syncing…"
        }

    val driveSyncAutoSubtitle: String
        get() = when (navConnections) {
            "Verbindungen" -> "Synchronisiert automatisch beim Start, beim Speichern von Änderungen und bei erkannten Änderungen in Google Drive."
            "Connessioni" -> "Sincronizza automaticamente all'avvio, al salvataggio delle modifiche e quando vengono rilevate modifiche in Google Drive."
            "Conexiones" -> "Sincroniza automáticamente al iniciar, al guardar cambios y al detectar cambios remotos en Google Drive."
            "Συνδέσεις" -> "Συγχρονίζει αυτόματα κατά την εκκίνηση, την αποθήκευση αλλαγών και τον εντοπισμό αλλαγών στο Google Drive."
            "Connexions" -> "Synchronise automatiquement au démarrage, à l'enregistrement et lors de modifications détectées sur Google Drive."
            else -> "Automatically syncs on startup, when you save changes, and when remote changes are detected in Google Drive."
        }

    val driveSyncOnboardingBody: String
        get() = when (navConnections) {
            "Verbindungen" -> "Wähle oder erstelle einmalig eine Tresor-Datei in Google Drive. NexusTS hält alle Verbindungen, Ordner und Anmeldedaten automatisch verschlüsselt über Chromebook, Android und macOS synchron."
            "Connessioni" -> "Scegli o crea una volta un file cassaforte in Google Drive. NexusTS mantiene connessioni, cartelle e credenziali cifrate e sincronizzate su Chromebook, Android e macOS."
            "Conexiones" -> "Elige o crea un archivo de bóveda en Google Drive una sola vez. NexusTS mantiene tus conexiones, carpetas y credenciales cifradas y sincronizadas en Chromebook, Android y macOS."
            "Συνδέσεις" -> "Επιλέξτε ή δημιουργήστε ένα αρχείο θησαυροφυλακίου στο Google Drive μία φορά. Το NexusTS διατηρεί συνδέσεις, φακέλους και διαπιστευτήρια κρυπτογραφημένα και συγχρονισμένα σε Chromebook, Android και macOS."
            "Connexions" -> "Choisissez ou créez un fichier coffre dans Google Drive une seule fois. NexusTS garde vos connexions, dossiers et identifiants chiffrés et synchronisés sur Chromebook, Android et macOS."
            else -> "Pick or create a vault file in Google Drive once. NexusTS keeps all your connections, folders, and credentials encrypted and synchronized automatically across Chromebook, Android, and macOS."
        }

    val savePassphraseHardwareKeystore: String
        get() = when (navConnections) {
            "Verbindungen" -> "Passphrase im Hardware-Keystore speichern"
            "Connessioni" -> "Salva passphrase nell'Hardware Keystore"
            "Conexiones" -> "Guardar frase de contraseña en Hardware Keystore"
            "Συνδέσεις" -> "Αποθήκευση φράσης πρόσβασης στο Hardware Keystore"
            "Connexions" -> "Enregistrer la phrase secrète dans le Hardware Keystore"
            else -> "Save Passphrase in Hardware Keystore"
        }

    fun localizeDriveSyncBanner(rawBanner: String): String {
        if (rawBanner.isBlank() || navConnections == "Connections") return rawBanner
        val needsUnlock = rawBanner.contains("Failed to unlock", ignoreCase = true) ||
            rawBanner.contains("Master passphrase required", ignoreCase = true)
        if (needsUnlock) {
            return when (navConnections) {
                "Verbindungen" -> "Master-Passphrase erforderlich, um den Google-Drive-Tresor (.nxts) zu entsperren."
                "Connessioni" -> "Passphrase principale richiesta per sbloccare la cassaforte Google Drive (.nxts)."
                "Conexiones" -> "Se requiere la frase maestra para desbloquear la bóveda de Google Drive (.nxts)."
                "Συνδέσεις" -> "Απαιτείται κύρια φράση πρόσβασης για το ξεκλείδωμα του θησαυροφυλακίου Google Drive (.nxts)."
                "Connexions" -> "Phrase secrète principale requise pour déverrouiller le coffre Google Drive (.nxts)."
                else -> rawBanner
            }
        }
        if (rawBanner.startsWith("Unlinked persistent", ignoreCase = true)) {
            return when (navConnections) {
                "Verbindungen" -> "Verknüpfung zum Google-Drive-Tresor aufgehoben."
                "Connessioni" -> "Collegamento alla cassaforte Google Drive rimosso."
                "Conexiones" -> "Bóveda de Google Drive desvinculada."
                "Συνδέσεις" -> "Αποσυνδέθηκε το θησαυροφυλάκιο Google Drive."
                "Connexions" -> "Coffre Google Drive dissocié."
                else -> rawBanner
            }
        }
        return rawBanner
    }

    val applyPassphraseAction: String
        get() = when (navConnections) {
            "Verbindungen" -> "Passphrase anwenden"
            "Connessioni" -> "Applica passphrase"
            "Conexiones" -> "Aplicar frase de contraseña"
            "Συνδέσεις" -> "Εφαρμογή φράσης πρόσβασης"
            "Connexions" -> "Appliquer la phrase secrète"
            else -> "Apply Passphrase"
        }

    val clearSavedPassphraseAction: String
        get() = when (navConnections) {
            "Verbindungen" -> "Gespeicherte Passphrase löschen"
            "Connessioni" -> "Cancella passphrase salvata"
            "Conexiones" -> "Borrar frase guardada"
            "Συνδέσεις" -> "Διαγραφή αποθηκευμένης φράσης"
            "Connexions" -> "Effacer la phrase enregistrée"
            else -> "Clear Saved Passphrase"
        }

    val passphraseUpdatedNotice: String
        get() = when (navConnections) {
            "Verbindungen" -> "Tresor-Passphrase aktualisiert."
            "Connessioni" -> "Passphrase della cassaforte aggiornata."
            "Conexiones" -> "Frase de la bóveda actualizada."
            "Συνδέσεις" -> "Η φράση πρόσβασης ενημερώθηκε."
            "Connexions" -> "Phrase secrète du coffre mise à jour."
            else -> "Vault passphrase updated."
        }

    val biometricDeviceUnlockLabel: String
        get() = when (navConnections) {
            "Verbindungen" -> "Biometrische / Geräte-Entsperrung"
            "Connessioni" -> "Sblocco biometrico / dispositivo"
            "Conexiones" -> "Desbloqueo biométrico / del dispositivo"
            "Συνδέσεις" -> "Βιομετρικό ξεκλείδωμα συσκευής"
            "Connexions" -> "Déverrouillage biométrique / appareil"
            else -> "Biometric / Device Unlock"
        }

    val idleAutoLockTitle: String
        get() = when (navConnections) {
            "Verbindungen" -> "Automatische Sperre bei Inaktivität"
            "Connessioni" -> "Blocco automatico per inattività"
            "Conexiones" -> "Bloqueo automático por inactividad"
            "Συνδέσεις" -> "Αυτόματο κλείδωμα αδράνειας"
            "Connexions" -> "Verrouillage automatique d'inactivité"
            else -> "Idle Auto-Lock"
        }

    val idleOffLabel: String
        get() = when (navConnections) {
            "Verbindungen" -> "Aus"
            "Connessioni" -> "Disattivato"
            "Conexiones" -> "Desactivado"
            "Συνδέσεις" -> "Ανενεργό"
            "Connexions" -> "Désactivé"
            else -> "Off"
        }

    fun idleMinutesLabel(mins: Int): String = when (navConnections) {
        "Verbindungen" -> "$mins Min."
        "Συνδέσεις" -> "$mins λεπ."
        else -> "$mins min"
    }

    val importExportVaultTitle: String
        get() = when (navConnections) {
            "Verbindungen" -> "Tresor importieren & exportieren"
            "Connessioni" -> "Importa ed esporta cassaforte"
            "Conexiones" -> "Importar y exportar bóveda"
            "Συνδέσεις" -> "Εισαγωγή & εξαγωγή θησαυροφυλακίου"
            "Connexions" -> "Importer et exporter le coffre"
            else -> "Import & Export Vault"
        }

    val checkingUpdatesLabel: String
        get() = when (navConnections) {
            "Verbindungen" -> "Prüfe…"
            "Connessioni" -> "Verifica…"
            "Conexiones" -> "Comprobando…"
            "Συνδέσεις" -> "Έλεγχος…"
            "Connexions" -> "Vérification…"
            else -> "Checking…"
        }

    fun downloadVersionLabel(tag: String): String = when (navConnections) {
        "Verbindungen" -> "$tag herunterladen"
        "Connessioni" -> "Scarica $tag"
        "Conexiones" -> "Descargar $tag"
        "Συνδέσεις" -> "Λήψη $tag"
        "Connexions" -> "Télécharger $tag"
        else -> "Download $tag"
    }

    val releaseNotesAction: String
        get() = when (navConnections) {
            "Verbindungen" -> "Versionshinweise"
            "Connessioni" -> "Note di rilascio"
            "Conexiones" -> "Notas de la versión"
            "Συνδέσεις" -> "Σημειώσεις έκδοσης"
            "Connexions" -> "Notes de version"
            else -> "Release Notes"
        }

    val verifiedApkDownloadAction: String
        get() = when (navConnections) {
            "Verbindungen" -> "Verifizierter APK-Download"
            "Connessioni" -> "Download APK verificato"
            "Conexiones" -> "Descarga APK verificada"
            "Συνδέσεις" -> "Επαληθευμένη λήψη APK"
            "Connexions" -> "Téléchargement APK vérifié"
            else -> "Verified APK Download"
        }

    val skipAction: String
        get() = when (navConnections) {
            "Verbindungen" -> "Überspringen"
            "Connessioni" -> "Salta"
            "Conexiones" -> "Omitir"
            "Συνδέσεις" -> "Παράλειψη"
            "Connexions" -> "Ignorer"
            else -> "Skip"
        }

    val checkAgainAction: String
        get() = when (navConnections) {
            "Verbindungen" -> "Erneut prüfen"
            "Connessioni" -> "Controlla di nuovo"
            "Conexiones" -> "Comprobar de nuevo"
            "Συνδέσεις" -> "Έλεγχος ξανά"
            "Connexions" -> "Vérifier à nouveau"
            else -> "Check Again"
        }

    val actionConnect: String
        get() = when (navConnections) {
            "Verbindungen" -> "Verbinden"
            "Connessioni" -> "Connetti"
            "Conexiones" -> "Conectar"
            "Συνδέσεις" -> "Σύνδεση"
            "Connexions" -> "Connecter"
            else -> "Connect"
        }

    val actionNewRdpConnection: String
        get() = when (navConnections) {
            "Verbindungen" -> "Neue RDP-Verbindung"
            "Connessioni" -> "Nuova connessione RDP"
            "Conexiones" -> "Nueva conexión RDP"
            "Συνδέσεις" -> "Νέα σύνδεση RDP"
            "Connexions" -> "Nouvelle connexion RDP"
            else -> "New RDP Connection"
        }

    val actionNewSshConnection: String
        get() = when (navConnections) {
            "Verbindungen" -> "Neue SSH-Verbindung"
            "Connessioni" -> "Nuova connessione SSH"
            "Conexiones" -> "Nueva conexión SSH"
            "Συνδέσεις" -> "Νέα σύνδεση SSH"
            "Connexions" -> "Nouvelle connexion SSH"
            else -> "New SSH Connection"
        }

    val actionNewSubfolder: String
        get() = when (navConnections) {
            "Verbindungen" -> "Neuer Unterordner"
            "Connessioni" -> "Nuova sottocartella"
            "Conexiones" -> "Nueva subcarpeta"
            "Συνδέσεις" -> "Νέος υποφάκελος"
            "Connexions" -> "Nouveau sous-dossier"
            else -> "New Subfolder"
        }

    val actionMoveFolderTo: String
        get() = when (navConnections) {
            "Verbindungen" -> "Ordner verschieben nach…"
            "Connessioni" -> "Sposta cartella in…"
            "Conexiones" -> "Mover carpeta a…"
            "Συνδέσεις" -> "Μετακίνηση φακέλου σε…"
            "Connexions" -> "Déplacer le dossier vers…"
            else -> "Move Folder to…"
        }

    val actionMoveUp: String
        get() = when (navConnections) {
            "Verbindungen" -> "Nach oben"
            "Connessioni" -> "Sposta su"
            "Conexiones" -> "Mover arriba"
            "Συνδέσεις" -> "Μετακίνηση πάνω"
            "Connexions" -> "Monter"
            else -> "Move Up"
        }

    val actionMoveDown: String
        get() = when (navConnections) {
            "Verbindungen" -> "Nach unten"
            "Connessioni" -> "Sposta giù"
            "Conexiones" -> "Mover abajo"
            "Συνδέσεις" -> "Μετακίνηση κάτω"
            "Connexions" -> "Descendre"
            else -> "Move Down"
        }

    val actionDuplicateFolder: String
        get() = when (navConnections) {
            "Verbindungen" -> "Ordner duplizieren"
            "Connessioni" -> "Duplica cartella"
            "Conexiones" -> "Duplicar carpeta"
            "Συνδέσεις" -> "Διπλότυπο φακέλου"
            "Connexions" -> "Dupliquer le dossier"
            else -> "Duplicate Folder"
        }

    val actionExpandSubfolders: String
        get() = when (navConnections) {
            "Verbindungen" -> "Unterordner ausklappen"
            "Connessioni" -> "Espandi sottocartelle"
            "Conexiones" -> "Expandir subcarpetas"
            "Συνδέσεις" -> "Ανάπτυξη υποφακέλων"
            "Connexions" -> "Développer les sous-dossiers"
            else -> "Expand Subfolders"
        }

    val actionCollapseSubfolders: String
        get() = when (navConnections) {
            "Verbindungen" -> "Unterordner einklappen"
            "Connessioni" -> "Comprimi sottocartelle"
            "Conexiones" -> "Contraer subcarpetas"
            "Συνδέσεις" -> "Σύμπτυξη υποφακέλων"
            "Connexions" -> "Réduire les sous-dossiers"
            else -> "Collapse Subfolders"
        }

    val actionCopyHost: String
        get() = when (navConnections) {
            "Verbindungen" -> "Host kopieren"
            "Connessioni" -> "Copia host"
            "Conexiones" -> "Copiar host"
            "Συνδέσεις" -> "Αντιγραφή host"
            "Connexions" -> "Copier l'hôte"
            else -> "Copy Host"
        }

    val actionCopyUsername: String
        get() = when (navConnections) {
            "Verbindungen" -> "Benutzername kopieren"
            "Connessioni" -> "Copia nome utente"
            "Conexiones" -> "Copiar usuario"
            "Συνδέσεις" -> "Αντιγραφή ονόματος χρήστη"
            "Connexions" -> "Copier l'utilisateur"
            else -> "Copy Username"
        }

    val actionCopyPassword: String
        get() = when (navConnections) {
            "Verbindungen" -> "Passwort kopieren"
            "Connessioni" -> "Copia password"
            "Conexiones" -> "Copiar contraseña"
            "Συνδέσεις" -> "Αντιγραφή κωδικού"
            "Connexions" -> "Copier le mot de passe"
            else -> "Copy Password"
        }

    val actionCopySshPublicKey: String
        get() = when (navConnections) {
            "Verbindungen" -> "Öffentlichen SSH-Schlüssel kopieren"
            "Connessioni" -> "Copia chiave pubblica SSH"
            "Conexiones" -> "Copiar clave pública SSH"
            "Συνδέσεις" -> "Αντιγραφή δημόσιου κλειδιού SSH"
            "Connexions" -> "Copier la clé publique SSH"
            else -> "Copy SSH Public Key"
        }

    val actionDuplicateShort: String
        get() = when (navConnections) {
            "Verbindungen" -> "Duplizieren"
            "Connessioni" -> "Duplica"
            "Conexiones" -> "Duplicar"
            "Συνδέσεις" -> "Διπλότυπο"
            "Connexions" -> "Dupliquer"
            else -> "Duplicate"
        }

    val actionUnlockBiometrics: String
        get() = when (navConnections) {
            "Verbindungen" -> "Mit Biometrie / Passkey entsperren"
            "Connessioni" -> "Sblocca con biometria / passkey"
            "Conexiones" -> "Desbloquear con biometría / passkey"
            "Συνδέσεις" -> "Ξεκλείδωμα με βιομετρικά / passkey"
            "Connexions" -> "Déverrouiller par biométrie / passkey"
            else -> "Unlock with Biometrics / Passkey"
        }

    val actionQuickLockNow: String
        get() = when (navConnections) {
            "Verbindungen" -> "Jetzt schnell sperren"
            "Connessioni" -> "Blocca subito"
            "Conexiones" -> "Bloquear ahora"
            "Συνδέσεις" -> "Άμεσο κλείδωμα"
            "Connexions" -> "Verrouiller maintenant"
            else -> "Quick Lock Now"
        }

    val actionCopyAll: String
        get() = when (navConnections) {
            "Verbindungen" -> "Alles kopieren"
            "Connessioni" -> "Copia tutto"
            "Conexiones" -> "Copiar todo"
            "Συνδέσεις" -> "Αντιγραφή όλων"
            "Connexions" -> "Tout copier"
            else -> "Copy All"
        }

    val actionClear: String
        get() = when (navConnections) {
            "Verbindungen" -> "Leeren"
            "Connessioni" -> "Pulisci"
            "Conexiones" -> "Limpiar"
            "Συνδέσεις" -> "Εκκαθάριση"
            "Connexions" -> "Effacer"
            else -> "Clear"
        }

    val actionCopyLogEntry: String
        get() = when (navConnections) {
            "Verbindungen" -> "Eintrag kopieren"
            "Connessioni" -> "Copia voce"
            "Conexiones" -> "Copiar entrada"
            "Συνδέσεις" -> "Αντιγραφή εγγραφής"
            "Connexions" -> "Copier l'entrée"
            else -> "Copy Log Entry"
        }

    val actionCopyAllLogs: String
        get() = when (navConnections) {
            "Verbindungen" -> "Gesamtes Protokoll kopieren"
            "Connessioni" -> "Copia tutti i log"
            "Conexiones" -> "Copiar todos los registros"
            "Συνδέσεις" -> "Αντιγραφή όλων των αρχείων καταγραφής"
            "Connexions" -> "Copier tous les journaux"
            else -> "Copy All Logs"
        }

    val actionClearAuditLog: String
        get() = when (navConnections) {
            "Verbindungen" -> "Audit-Protokoll leeren"
            "Connessioni" -> "Cancella registro audit"
            "Conexiones" -> "Limpiar registro de auditoría"
            "Συνδέσεις" -> "Εκκαθάριση αρχείου καταγραφής"
            "Connexions" -> "Effacer le journal d'audit"
            else -> "Clear Audit Log"
        }

    val dialogUnlockEncryptedImportTitle: String
        get() = when (navConnections) {
            "Verbindungen" -> "Verschlüsselten Import entsperren"
            "Connessioni" -> "Sblocca importazione cifrata"
            "Conexiones" -> "Desbloquear importación cifrada"
            "Συνδέσεις" -> "Ξεκλείδωμα κρυπτογραφημένης εισαγωγής"
            "Connexions" -> "Déverrouiller l'importation chiffrée"
            else -> "Unlock Encrypted Import"
        }

    val dialogImportFailedTitle: String
        get() = when (navConnections) {
            "Verbindungen" -> "Import fehlgeschlagen"
            "Connessioni" -> "Importazione non riuscita"
            "Conexiones" -> "Error al importar"
            "Συνδέσεις" -> "Αποτυχία εισαγωγής"
            "Connexions" -> "Échec de l'importation"
            else -> "Import Failed"
        }

    val filterPlaceholderShort: String
        get() = when (navConnections) {
            "Verbindungen" -> "Filtern…"
            "Connessioni" -> "Filtra…"
            "Conexiones" -> "Filtrar…"
            "Συνδέσεις" -> "Φιλτράρισμα…"
            "Connexions" -> "Filtrer…"
            else -> "Filter…"
        }

    val tabFullScreen: String
        get() = when (navConnections) {
            "Verbindungen" -> "Vollbild"
            "Connessioni" -> "Schermo intero"
            "Conexiones" -> "Pantalla completa"
            "Συνδέσεις" -> "Πλήρης οθόνη"
            "Connexions" -> "Plein écran"
            else -> "Full Screen"
        }

    val tabMoveLeft: String
        get() = when (navConnections) {
            "Verbindungen" -> "Tab nach links"
            "Connessioni" -> "Sposta scheda a sinistra"
            "Conexiones" -> "Mover pestaña a la izquierda"
            "Συνδέσεις" -> "Μετακίνηση καρτέλας αριστερά"
            "Connexions" -> "Déplacer l'onglet à gauche"
            else -> "Move Tab Left"
        }

    val tabMoveRight: String
        get() = when (navConnections) {
            "Verbindungen" -> "Tab nach rechts"
            "Connessioni" -> "Sposta scheda a destra"
            "Conexiones" -> "Mover pestaña a la derecha"
            "Συνδέσεις" -> "Μετακίνηση καρτέλας δεξιά"
            "Connexions" -> "Déplacer l'onglet à droite"
            else -> "Move Tab Right"
        }

    val tabCloseOthers: String
        get() = when (navConnections) {
            "Verbindungen" -> "Andere Tabs schließen"
            "Connessioni" -> "Chiudi altre schede"
            "Conexiones" -> "Cerrar otras pestañas"
            "Συνδέσεις" -> "Κλείσιμο άλλων καρτελών"
            "Connexions" -> "Fermer les autres onglets"
            else -> "Close Other Tabs"
        }

    fun cascadesToChildrenLabel(count: Int): String = when (navConnections) {
        "Verbindungen" -> "Wird automatisch an $count untergeordnete Verbindung(en) mit '${credModeInheritFolder}' vererbt."
        "Connessioni" -> "Si propaga automaticamente a $count connessioni figlie che usano '${credModeInheritFolder}'."
        "Conexiones" -> "Se hereda automáticamente en $count conexión(es) hija(s) con '${credModeInheritFolder}'."
        "Συνδέσεις" -> "Εφαρμόζεται αυτόματα σε $count θυγατρικές συνδέσεις με '${credModeInheritFolder}'."
        "Connexions" -> "S'applique automatiquement à $count connexion(s) enfant(s) utilisant '${credModeInheritFolder}'."
        else -> "Cascades automatically to $count child connection(s) using '${credModeInheritFolder}'."
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

