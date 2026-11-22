import Foundation

public enum NexusAppLanguage: String, Codable, CaseIterable, Identifiable, Sendable {
    case system = "system"
    case en = "en"
    case de = "de"
    case it = "it"
    case es = "es"
    case el = "el"
    case fr = "fr"

    public var id: String { rawValue }

    public var nativeDisplayName: String {
        switch self {
        case .system: return "System Default (macOS)"
        case .en: return "English (International)"
        case .de: return "Deutsch (DACH)"
        case .it: return "Italiano (Italia)"
        case .es: return "Español (Internacional)"
        case .el: return "Ελληνικά (Ελλάδα)"
        case .fr: return "Français (International)"
        }
    }

    public var isoBadge: String {
        switch self {
        case .system: return "AUTO"
        default: return rawValue.uppercased()
        }
    }

    public var resolvedCode: String {
        if self != .system { return rawValue }
        let preferred = Locale.preferredLanguages.first?.prefix(2).lowercased() ?? "en"
        return ["en", "de", "it", "es", "el", "fr"].contains(preferred) ? preferred : "en"
    }
}

public struct LocalizedWorkspaceStrings: Equatable, Sendable {
    public let connections: String
    public let overview: String
    public let templates: String
    public let credentials: String
    public let gateways: String
    public let automationTasks: String
    public let cloudSync: String
    public let favorites: String
    public let recentSessions: String
    public let smartFolders: String
    public let dynamicFolders: String
    public let vaultHierarchy: String
    public let quickConnectPlaceholder: String
    public let newConnection: String
    public let newFolder: String
    public let localTerminal: String
    public let broadcastInput: String
    public let lockVault: String
    public let unlockVault: String
    public let unlockWithTouchId: String
    public let inspectorTitle: String
    public let auditConsole: String
    public let syncNow: String
    public let splitRight: String
    public let splitDown: String
    public let detachToWindow: String
    public let disconnectAll: String
    public let studios: String
    public let renameFolder: String
    public let startAllRdp: String
    public let deleteFolderAndContents: String
    public let deleteFolderKeepContents: String
    public let editConnection: String
    public let newSmartFolder: String
    public let editSmartFolder: String
    public let duplicateSmartFolder: String
    public let newDynamicFolder: String
    public let editDynamicFolder: String
    public let duplicateDynamicFolder: String
    public let newCredential: String
    public let editCredential: String
    public let systemCredentialsFolder: String
    public let resetDatabase: String
    public let royalImportTitle: String
    public let royalImportNewFolder: String
    public let royalImportRoot: String
    public let royalImportReplaceAll: String
    public let copyDiagnostics: String
    public let quickConnectTitle: String
    public let workspaceShortcutsTitle: String
    public let toggleSidebar: String
    public let fullScreenSession: String
    public let disconnectSession: String
    public let logOffSession: String

    public static func catalog(for language: NexusAppLanguage) -> LocalizedWorkspaceStrings {
        switch language.resolvedCode {
        case "de":
            return LocalizedWorkspaceStrings(
                connections: "Verbindungen",
                overview: "Übersicht",
                templates: "Vorlagen",
                credentials: "Anmeldeinformationen",
                gateways: "Gateways",
                automationTasks: "Automatisierung",
                cloudSync: "Cloud-Sync",
                favorites: "Favoriten",
                recentSessions: "Zuletzt verwendet",
                smartFolders: "Intelligente Ordner",
                dynamicFolders: "Dynamische Ordner",
                vaultHierarchy: "Tresor-Struktur",
                quickConnectPlaceholder: "Schnellverbindung (ssh://, rdp://, vnc:// oder Host suchen)…",
                newConnection: "Neue Verbindung",
                newFolder: "Neuer Ordner",
                localTerminal: "Lokales Terminal",
                broadcastInput: "Broadcast-Eingabe",
                lockVault: "Tresor sperren",
                unlockVault: "Tresor entsperren",
                unlockWithTouchId: "Mit Touch ID entsperren",
                inspectorTitle: "Informationen",
                auditConsole: "Audit-Protokoll & Broadcast",
                syncNow: "Jetzt synchronisieren",
                splitRight: "Rechts teilen",
                splitDown: "Unten teilen",
                detachToWindow: "In neuem Fenster öffnen",
                disconnectAll: "Alle im Ordner trennen",
                studios: "Verwalten",
                renameFolder: "Ordner umbenennen…",
                startAllRdp: "Alle RDP-Verbindungen starten",
                deleteFolderAndContents: "Ordner & gesamten Inhalt löschen",
                deleteFolderKeepContents: "Nur Ordner löschen (Elemente nach oben)",
                editConnection: "Verbindung bearbeiten…",
                newSmartFolder: "Neuer intelligenter Ordner…",
                editSmartFolder: "Intelligenten Ordner bearbeiten…",
                duplicateSmartFolder: "Intelligenten Ordner duplizieren",
                newDynamicFolder: "Neuer dynamischer Ordner…",
                editDynamicFolder: "Dynamischen Ordner bearbeiten…",
                duplicateDynamicFolder: "Dynamischen Ordner duplizieren",
                newCredential: "Neue Anmeldeinformation…",
                editCredential: "Anmeldeinformation bearbeiten…",
                systemCredentialsFolder: "Credentials",
                resetDatabase: "NexusTS-Datenbank zurücksetzen…",
                royalImportTitle: "Royal TS-Datei importieren",
                royalImportNewFolder: "Neuen Ordner für importierte Struktur erstellen",
                royalImportRoot: "An der Wurzel der Hierarchie platzieren",
                royalImportReplaceAll: "Alle vorhandenen Verbindungen löschen und ersetzen",
                copyDiagnostics: "Diagnose kopieren",
                quickConnectTitle: "Schnellverbindung",
                workspaceShortcutsTitle: "macOS-Tastaturkurzbefehle",
                toggleSidebar: "Seitenleiste",
                fullScreenSession: "Vollbild-Sitzung",
                disconnectSession: "Trennen",
                logOffSession: "Sitzung abmelden"
            )
        case "it":
            return LocalizedWorkspaceStrings(
                connections: "Connessioni",
                overview: "Panoramica",
                templates: "Modelli",
                credentials: "Credenziali",
                gateways: "Gateway Sicuri",
                automationTasks: "Automazione",
                cloudSync: "Sincronizzazione Cloud",
                favorites: "Preferiti",
                recentSessions: "Sessioni Recenti",
                smartFolders: "Cartelle Intelligenti",
                dynamicFolders: "Cartelle Dinamiche",
                vaultHierarchy: "Gerarchia Vault",
                quickConnectPlaceholder: "Connessione rapida (ssh://, rdp://, vnc:// o cerca host)…",
                newConnection: "Nuova Connessione",
                newFolder: "Nuova Cartella",
                localTerminal: "Terminale Locale",
                broadcastInput: "Input Broadcast",
                lockVault: "Blocca Vault",
                unlockVault: "Sblocca Vault",
                unlockWithTouchId: "Sblocca con Touch ID",
                inspectorTitle: "Ispettore Proprietà",
                auditConsole: "Registro Audit & Broadcast",
                syncNow: "Sincronizza Ora",
                splitRight: "Dividi a Destra",
                splitDown: "Dividi in Basso",
                detachToWindow: "Stacca in Nuova Finestra",
                disconnectAll: "Disconnetti Tutto",
                studios: "Gestisci",
                renameFolder: "Rinomina Cartella…",
                startAllRdp: "Avvia Tutte le Connessioni RDP",
                deleteFolderAndContents: "Elimina Cartella e Tutto il Contenuto",
                deleteFolderKeepContents: "Elimina Solo Cartella (Sposta Elementi Sopra)",
                editConnection: "Modifica Connessione…",
                newSmartFolder: "Nuova Cartella Intelligente…",
                editSmartFolder: "Modifica Cartella Intelligente…",
                duplicateSmartFolder: "Duplica Cartella Intelligente",
                newDynamicFolder: "Nuova Cartella Dinamica…",
                editDynamicFolder: "Modifica Cartella Dinamica…",
                duplicateDynamicFolder: "Duplica Cartella Dinamica",
                newCredential: "Nuova Credenziale…",
                editCredential: "Modifica Credenziale…",
                systemCredentialsFolder: "Credentials",
                resetDatabase: "Ripristina Database NexusTS…",
                royalImportTitle: "Importa File Royal TS",
                royalImportNewFolder: "Crea una nuova cartella",
                royalImportRoot: "Posiziona alla radice della gerarchia",
                royalImportReplaceAll: "Elimina tutte le connessioni esistenti e sostituisci",
                copyDiagnostics: "Copia Diagnostica",
                quickConnectTitle: "Connessione Rapida",
                workspaceShortcutsTitle: "Scorciatoie Area di Lavoro macOS",
                toggleSidebar: "Barra Laterale",
                fullScreenSession: "Sessione a Schermo Intero",
                disconnectSession: "Disconnetti",
                logOffSession: "Chiudi Sessione (Log Off)"
            )
        case "es":
            return LocalizedWorkspaceStrings(
                connections: "Conexiones",
                overview: "Resumen",
                templates: "Plantillas",
                credentials: "Credenciales",
                gateways: "Pasarelas",
                automationTasks: "Automatización",
                cloudSync: "Sincronización Cloud",
                favorites: "Favoritos",
                recentSessions: "Recientes",
                smartFolders: "Carpetas Inteligentes",
                dynamicFolders: "Carpetas Dinámicas",
                vaultHierarchy: "Jerarquía de Bóveda",
                quickConnectPlaceholder: "Conexión rápida (ssh://, rdp://, vnc:// o buscar host)…",
                newConnection: "Nueva Conexión",
                newFolder: "Nueva Carpeta",
                localTerminal: "Terminal Local",
                broadcastInput: "Entrada Difusión",
                lockVault: "Bloquear Bóveda",
                unlockVault: "Desbloquear Bóveda",
                unlockWithTouchId: "Desbloquear con Touch ID",
                inspectorTitle: "Inspector",
                auditConsole: "Consola de Auditoría",
                syncNow: "Sincronizar Ahora",
                splitRight: "Dividir a la Derecha",
                splitDown: "Dividir Abajo",
                detachToWindow: "Abrir en Nueva Ventana",
                disconnectAll: "Desconectar Todo",
                studios: "Gestionar",
                renameFolder: "Renombrar Carpeta…",
                startAllRdp: "Iniciar Todas las Conexiones RDP",
                deleteFolderAndContents: "Eliminar Carpeta y Todo su Contenido",
                deleteFolderKeepContents: "Eliminar Solo la Carpeta (Mover Elementos Arriba)",
                editConnection: "Editar Conexión…",
                newSmartFolder: "Nueva Carpeta Inteligente…",
                editSmartFolder: "Editar Carpeta Inteligente…",
                duplicateSmartFolder: "Duplicar Carpeta Inteligente",
                newDynamicFolder: "Nueva Carpeta Dinámica…",
                editDynamicFolder: "Editar Carpeta Dinámica…",
                duplicateDynamicFolder: "Duplicar Carpeta Dinámica",
                newCredential: "Nueva Credencial…",
                editCredential: "Editar Credencial…",
                systemCredentialsFolder: "Credentials",
                resetDatabase: "Restablecer Base de Datos de NexusTS…",
                royalImportTitle: "Importar Archivo Royal TS",
                royalImportNewFolder: "Crear una nueva carpeta",
                royalImportRoot: "Colocar en la raíz de la jerarquía",
                royalImportReplaceAll: "Eliminar todas las conexiones existentes y reemplazar",
                copyDiagnostics: "Copiar Diagnóstico",
                quickConnectTitle: "Conexión Rápida",
                workspaceShortcutsTitle: "Atajos de Espacio de Trabajo macOS",
                toggleSidebar: "Barra Lateral",
                fullScreenSession: "Sesión a Pantalla Completa",
                disconnectSession: "Desconectar",
                logOffSession: "Cerrar Sesión Remota"
            )
        case "el":
            return LocalizedWorkspaceStrings(
                connections: "Συνδέσεις",
                overview: "Επισκόπηση",
                templates: "Πρότυπα",
                credentials: "Διαπιστευτήρια",
                gateways: "Πύλες Ασφαλείας",
                automationTasks: "Αυτοματισμοί",
                cloudSync: "Συγχρονισμός Cloud",
                favorites: "Αγαπημένα",
                recentSessions: "Πρόσφατες Συνεδρίες",
                smartFolders: "Έξυπνοι Φάκελοι",
                dynamicFolders: "Δυναμικοί Φάκελοι",
                vaultHierarchy: "Ιεραρχία Θησαυροφυλακίου",
                quickConnectPlaceholder: "Γρήγορη σύνδεση (ssh://, rdp://, vnc:// ή αναζήτηση)…",
                newConnection: "Νέα Σύνδεση",
                newFolder: "Νέος Φάκελος",
                localTerminal: "Τοπικό Τερματικό",
                broadcastInput: "Μετάδοση Πληκτρολόγησης",
                lockVault: "Κλείδωμα",
                unlockVault: "Ξεκλείδωμα",
                unlockWithTouchId: "Ξεκλείδωμα με Touch ID",
                inspectorTitle: "Επιθεωρητής Ιδιοτήτων",
                auditConsole: "Καταγραφή Ελέγχου",
                syncNow: "Συγχρονισμός Τώρα",
                splitRight: "Διαίρεση Δεξιά",
                splitDown: "Διαίρεση Κάτω",
                detachToWindow: "Άνοιγμα σε Νέο Παράθυρο",
                disconnectAll: "Αποσύνδεση Όλων",
                studios: "Διαχείριση",
                renameFolder: "Μετονομασία Φακέλου…",
                startAllRdp: "Εκκίνηση Όλων των Συνδέσεων RDP",
                deleteFolderAndContents: "Διαγραφή Φακέλου & Περιεχομένων",
                deleteFolderKeepContents: "Διαγραφή Μόνο Φακέλου (Μετακίνηση Πάνω)",
                editConnection: "Επεξεργασία Σύνδεσης…",
                newSmartFolder: "Νέος Έξυπνος Φάκελος…",
                editSmartFolder: "Επεξεργασία Έξυπνου Φακέλου…",
                duplicateSmartFolder: "Διπλότυπο Έξυπνου Φακέλου",
                newDynamicFolder: "Νέος Δυναμικός Φάκελος…",
                editDynamicFolder: "Επεξεργασία Δυναμικού Φακέλου…",
                duplicateDynamicFolder: "Διπλότυπο Δυναμικού Φακέλου",
                newCredential: "Νέο Διαπιστευτήριο…",
                editCredential: "Επεξεργασία Διαπιστευτηρίου…",
                systemCredentialsFolder: "Credentials",
                resetDatabase: "Επαναφορά Βάσης Δεδομένων NexusTS…",
                royalImportTitle: "Εισαγωγή Αρχείου Royal TS",
                royalImportNewFolder: "Δημιουργία νέου φακέλου",
                royalImportRoot: "Τοποθέτηση στη ρίζα της ιεραρχίας",
                royalImportReplaceAll: "Διαγραφή όλων των υπαρχουσών συνδέσεων και αντικατάσταση",
                copyDiagnostics: "Αντιγραφή Διαγνωστικών",
                quickConnectTitle: "Ταχεία Σύνδεση",
                workspaceShortcutsTitle: "Συντομεύσεις Χώρου Εργασίας macOS",
                toggleSidebar: "Πλαϊνή Στήλη",
                fullScreenSession: "Συνεδρία Πλήρους Οθόνης",
                disconnectSession: "Αποσύνδεση",
                logOffSession: "Αποσύνδεση Χρήστη (Log Off)"
            )
        case "fr":
            return LocalizedWorkspaceStrings(
                connections: "Connexions",
                overview: "Aperçu",
                templates: "Modèles",
                credentials: "Identifiants",
                gateways: "Passerelles",
                automationTasks: "Automatisation",
                cloudSync: "Synchro Cloud",
                favorites: "Favoris",
                recentSessions: "Sessions Récentes",
                smartFolders: "Dossiers Intelligents",
                dynamicFolders: "Dossiers Dynamiques",
                vaultHierarchy: "Arborescence du Coffre",
                quickConnectPlaceholder: "Connexion rapide (ssh://, rdp://, vnc:// ou rechercher)…",
                newConnection: "Nouvelle Connexion",
                newFolder: "Nouveau Dossier",
                localTerminal: "Terminal Local",
                broadcastInput: "Diffusion Clavier",
                lockVault: "Verrouiller le Coffre",
                unlockVault: "Déverrouiller",
                unlockWithTouchId: "Déverrouiller avec Touch ID",
                inspectorTitle: "Inspecteur",
                auditConsole: "Journal d’Audit",
                syncNow: "Synchroniser",
                splitRight: "Scinder à Droite",
                splitDown: "Scinder en Bas",
                detachToWindow: "Détacher dans une Fenêtre",
                disconnectAll: "Tout Déconnecter",
                studios: "Gérer",
                renameFolder: "Renommer le Dossier…",
                startAllRdp: "Démarrer Toutes les Connexions RDP",
                deleteFolderAndContents: "Supprimer le Dossier et Tout son Contenu",
                deleteFolderKeepContents: "Supprimer Uniquement le Dossier (Remonter les Éléments)",
                editConnection: "Modifier la Connexion…",
                newSmartFolder: "Nouveau Dossier Intelligent…",
                editSmartFolder: "Modifier le Dossier Intelligent…",
                duplicateSmartFolder: "Dupliquer le Dossier Intelligent",
                newDynamicFolder: "Nouveau Dossier Dynamique…",
                editDynamicFolder: "Modifier le Dossier Dynamique…",
                duplicateDynamicFolder: "Dupliquer le Dossier Dynamique",
                newCredential: "Nouvel Identifiant…",
                editCredential: "Modifier l’Identifiant…",
                systemCredentialsFolder: "Credentials",
                resetDatabase: "Réinitialiser la Base NexusTS…",
                royalImportTitle: "Importer un Fichier Royal TS",
                royalImportNewFolder: "Créer un nouveau dossier",
                royalImportRoot: "Placer à la racine de la hiérarchie",
                royalImportReplaceAll: "Supprimer toutes les connexions existantes et remplacer",
                copyDiagnostics: "Copier le Diagnostic",
                quickConnectTitle: "Connexion Rapide",
                workspaceShortcutsTitle: "Raccourcis de l’Espace de Travail macOS",
                toggleSidebar: "Barre Latérale",
                fullScreenSession: "Session Plein Écran",
                disconnectSession: "Déconnecter",
                logOffSession: "Fermer la Session (Log Off)"
            )
        default:
            return LocalizedWorkspaceStrings(
                connections: "Connections",
                overview: "Overview",
                templates: "Templates",
                credentials: "Credentials",
                gateways: "Secure Gateways",
                automationTasks: "Automation Tasks",
                cloudSync: "Cloud Sync",
                favorites: "Favorites",
                recentSessions: "Recent Sessions",
                smartFolders: "Intelligent Folders",
                dynamicFolders: "Dynamic Folders",
                vaultHierarchy: "Vault Hierarchy",
                quickConnectPlaceholder: "Quick Connect (ssh://, rdp://, vnc://, /dev/cu.*, or search hosts)…",
                newConnection: "New Connection",
                newFolder: "New Folder",
                localTerminal: "Local Terminal",
                broadcastInput: "Broadcast Input",
                lockVault: "Lock Vault",
                unlockVault: "Unlock Vault",
                unlockWithTouchId: "Unlock with Touch ID",
                inspectorTitle: "Inspector",
                auditConsole: "Audit Log & Broadcast",
                syncNow: "Sync Now",
                splitRight: "Split Right",
                splitDown: "Split Down",
                detachToWindow: "Move Tab to New Window",
                disconnectAll: "Disconnect All in Folder",
                studios: "Manage",
                renameFolder: "Rename Folder…",
                startAllRdp: "Start All RDP Connections",
                deleteFolderAndContents: "Delete Folder & All Contents",
                deleteFolderKeepContents: "Delete Folder Only (Move Items Up)",
                editConnection: "Edit Connection…",
                newSmartFolder: "New Intelligent Folder…",
                editSmartFolder: "Edit Intelligent Folder…",
                duplicateSmartFolder: "Duplicate Intelligent Folder",
                newDynamicFolder: "New Dynamic Folder…",
                editDynamicFolder: "Edit Dynamic Folder…",
                duplicateDynamicFolder: "Duplicate Dynamic Folder",
                newCredential: "New Credential…",
                editCredential: "Edit Credential…",
                systemCredentialsFolder: "Credentials",
                resetDatabase: "Reset NexusTS Database…",
                royalImportTitle: "Import Royal TS Document",
                royalImportNewFolder: "Create a new folder for imported connections",
                royalImportRoot: "Place at the root of the hierarchy",
                royalImportReplaceAll: "Delete all existing connections and replace",
                copyDiagnostics: "Copy Diagnostics",
                quickConnectTitle: "Quick Connect",
                workspaceShortcutsTitle: "macOS Workspace Shortcuts",
                toggleSidebar: "Sidebar",
                fullScreenSession: "Full-Screen Session",
                disconnectSession: "Disconnect",
                logOffSession: "Log Off Session"
            )
        }
    }
}

/// Canonical macOS keyboard shortcut catalog shared across the Menu Bar, Toolbar tooltips,
/// and the Overview shortcut legend so labels and key chords can never drift or collide.
public enum MacWorkspaceShortcut: String, CaseIterable, Identifiable, Sendable {
    case quickConnect
    case localTerminal
    case newConnection
    case newFolder
    case splitRight
    case splitDown
    case broadcastInput
    case toggleSidebar
    case toggleInspector
    case toggleAuditConsole
    case fullScreenSession
    case syncNow
    case lockVault

    public var id: String { rawValue }

    public var keycap: String {
        switch self {
        case .quickConnect: return "⌘K"
        case .localTerminal: return "⌘T"
        case .newConnection: return "⌘N"
        case .newFolder: return "⇧⌘N"
        case .splitRight: return "⌘D"
        case .splitDown: return "⇧⌘D"
        case .broadcastInput: return "⇧⌘B"
        case .toggleSidebar: return "⌃⌘S"
        case .toggleInspector: return "⌥⌘I"
        case .toggleAuditConsole: return "⇧⌘J"
        case .fullScreenSession: return "⇧⌘F"
        case .syncNow: return "⇧⌘S"
        case .lockVault: return "⇧⌘L"
        }
    }

    public func title(in strings: LocalizedWorkspaceStrings) -> String {
        switch self {
        case .quickConnect: return strings.quickConnectTitle
        case .localTerminal: return strings.localTerminal
        case .newConnection: return strings.newConnection
        case .newFolder: return strings.newFolder
        case .splitRight: return strings.splitRight
        case .splitDown: return strings.splitDown
        case .broadcastInput: return strings.broadcastInput
        case .toggleSidebar: return strings.toggleSidebar
        case .toggleInspector: return strings.inspectorTitle
        case .toggleAuditConsole: return strings.auditConsole
        case .fullScreenSession: return strings.fullScreenSession
        case .syncNow: return strings.syncNow
        case .lockVault: return strings.lockVault
        }
    }

    public func menuTitle(in strings: LocalizedWorkspaceStrings) -> String {
        switch self {
        case .quickConnect, .newConnection, .newFolder:
            let base = title(in: strings)
            return base.hasSuffix("…") ? base : "\(base)…"
        default:
            return title(in: strings)
        }
    }

    public static let overviewCheatSheetItems: [MacWorkspaceShortcut] = [
        .quickConnect,
        .localTerminal,
        .newConnection,
        .splitRight,
        .splitDown,
        .broadcastInput,
        .toggleInspector,
        .toggleSidebar
    ]
}

public struct DiscreetTooltipSpec: Equatable, Sendable {
    public let title: String
    public let shortcut: String?

    public init(title: String, shortcut: String? = nil) {
        self.title = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanShortcut = shortcut?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.shortcut = (cleanShortcut?.isEmpty == false) ? cleanShortcut : nil
    }

    /// Splits a combined label such as `"Local Terminal (⌘T)"` into `title: "Local Terminal"` and `shortcut: "⌘T"`.
    public static func parse(_ raw: String, explicitShortcut: String? = nil) -> DiscreetTooltipSpec {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let exp = explicitShortcut?.trimmingCharacters(in: .whitespacesAndNewlines), !exp.isEmpty {
            return DiscreetTooltipSpec(title: trimmed, shortcut: exp)
        }
        guard trimmed.hasSuffix(")"),
              let openIdx = trimmed.lastIndex(of: "("),
              openIdx > trimmed.startIndex else {
            return DiscreetTooltipSpec(title: trimmed, shortcut: nil)
        }
        let inside = String(trimmed[trimmed.index(after: openIdx)..<trimmed.index(before: trimmed.endIndex)])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let prefix = String(trimmed[..<openIdx]).trimmingCharacters(in: .whitespacesAndNewlines)
        let hasShortcutGlyph = inside.contains("⌘")
            || inside.contains("⌃")
            || inside.contains("⌥")
            || inside.contains("⇧")
            || inside.hasPrefix("Ctrl+")
            || inside.hasPrefix("Cmd+")
        if hasShortcutGlyph && !prefix.isEmpty && inside.count <= 14 {
            return DiscreetTooltipSpec(title: prefix, shortcut: inside)
        }
        return DiscreetTooltipSpec(title: trimmed, shortcut: nil)
    }

    public static func resolveToolbarTooltip(
        candidateText: String,
        sfSymbolHint: String? = nil,
        isNarrowMenuChevron: Bool = false,
        strings: LocalizedWorkspaceStrings
    ) -> DiscreetTooltipSpec? {
        let cleanText = candidateText.trimmingCharacters(in: .whitespacesAndNewlines)
        let lowerSymbol = (sfSymbolHint ?? "").lowercased()

        if isNarrowMenuChevron && (
            cleanText.isEmpty ||
            cleanText == strings.newConnection ||
            cleanText.hasPrefix(strings.newConnection) ||
            lowerSymbol.contains("chevron") ||
            cleanText.lowercased() == "menu"
        ) {
            return DiscreetTooltipSpec(title: "\(strings.newConnection) • \(strings.newFolder) • Vault", shortcut: nil)
        }

        // 1. Check explicit shortcut matches across all workspace shortcuts
        for item in MacWorkspaceShortcut.allCases {
            let localizedTitle = item.title(in: strings)
            let combined = "\(localizedTitle) (\(item.keycap))"
            if cleanText == localizedTitle || cleanText == combined || cleanText == "nexusts.toolbar.\(item.rawValue)" {
                return DiscreetTooltipSpec(title: localizedTitle, shortcut: item.keycap)
            }
        }

        // 2. Navigation / Settings / Studios / Re-dock toolbar controls
        if cleanText == strings.settings || cleanText == "\(strings.settings) (⌘,)" || cleanText == "nexusts.toolbar.settings" {
            return DiscreetTooltipSpec(title: strings.settings, shortcut: "⌘,")
        }
        if cleanText == strings.studios || cleanText == "\(strings.studios) (⌘1–⌘7)" || cleanText == "nexusts.toolbar.studios" {
            return DiscreetTooltipSpec(title: strings.studios, shortcut: "⌘1–⌘7")
        }
        for studioTitle in [
            strings.overview,
            strings.templates,
            strings.credentials,
            strings.gateways,
            strings.automationTasks,
            strings.cloudSync
        ] where cleanText == studioTitle {
            return DiscreetTooltipSpec(title: "\(strings.settings): \(studioTitle)", shortcut: "⌘,")
        }
        if cleanText == strings.connections || cleanText == "\(strings.connections) (⌘1)" || cleanText == "nexusts.toolbar.backConnections" {
            return DiscreetTooltipSpec(title: strings.connections, shortcut: "⌘1")
        }
        if cleanText == "Re-Dock in Main Window" || cleanText == "nexusts.toolbar.reDock" {
            return DiscreetTooltipSpec(title: "Move Session Back to Main Window", shortcut: nil)
        }

        // 3. Fallback via SF Symbol hint from NSControl.image
        if !lowerSymbol.isEmpty {
            if lowerSymbol.contains("gearshape") {
                return DiscreetTooltipSpec(title: strings.settings, shortcut: "⌘,")
            }
            if lowerSymbol.contains("sparkle.magnifyingglass") {
                return DiscreetTooltipSpec(title: MacWorkspaceShortcut.quickConnect.title(in: strings), shortcut: MacWorkspaceShortcut.quickConnect.keycap)
            }
            if lowerSymbol.contains("rectangle.split.2x1") {
                return DiscreetTooltipSpec(title: MacWorkspaceShortcut.splitRight.title(in: strings), shortcut: MacWorkspaceShortcut.splitRight.keycap)
            }
            if lowerSymbol.contains("rectangle.split.1x2") {
                return DiscreetTooltipSpec(title: MacWorkspaceShortcut.splitDown.title(in: strings), shortcut: MacWorkspaceShortcut.splitDown.keycap)
            }
            if lowerSymbol.contains("dot.radiowaves.left.and.right") {
                return DiscreetTooltipSpec(title: MacWorkspaceShortcut.broadcastInput.title(in: strings), shortcut: MacWorkspaceShortcut.broadcastInput.keycap)
            }
            if lowerSymbol.contains("arrow.triangle.2.circlepath.icloud") {
                return DiscreetTooltipSpec(title: MacWorkspaceShortcut.syncNow.title(in: strings), shortcut: MacWorkspaceShortcut.syncNow.keycap)
            }
            if lowerSymbol.contains("sidebar.trailing") {
                return DiscreetTooltipSpec(title: MacWorkspaceShortcut.toggleInspector.title(in: strings), shortcut: MacWorkspaceShortcut.toggleInspector.keycap)
            }
            if lowerSymbol.contains("line.3.horizontal") || lowerSymbol.contains("sidebar.leading") {
                return DiscreetTooltipSpec(title: MacWorkspaceShortcut.toggleSidebar.title(in: strings), shortcut: MacWorkspaceShortcut.toggleSidebar.keycap)
            }
            if lowerSymbol.contains("square.grid.2x2") {
                return DiscreetTooltipSpec(title: strings.studios, shortcut: "⌘1–⌘7")
            }
            if lowerSymbol.contains("terminal") {
                return DiscreetTooltipSpec(title: MacWorkspaceShortcut.localTerminal.title(in: strings), shortcut: MacWorkspaceShortcut.localTerminal.keycap)
            }
            if lowerSymbol.contains("lock") {
                return DiscreetTooltipSpec(title: MacWorkspaceShortcut.lockVault.title(in: strings), shortcut: MacWorkspaceShortcut.lockVault.keycap)
            }
            if lowerSymbol == "plus" || lowerSymbol.contains(" symbol = plus") || lowerSymbol.hasSuffix("plus") {
                return DiscreetTooltipSpec(title: MacWorkspaceShortcut.newConnection.title(in: strings), shortcut: MacWorkspaceShortcut.newConnection.keycap)
            }
        }

        if !cleanText.isEmpty {
            return parse(cleanText)
        }
        return nil
    }
}

public extension LocalizedWorkspaceStrings {
    var settings: String {
        switch connections {
        case "Verbindungen": return "Einstellungen"
        case "Connessioni": return "Impostazioni"
        case "Conexiones": return "Ajustes"
        case "Συνδέσεις": return "Ρυθμίσεις"
        case "Connexions": return "Paramètres"
        default: return "Settings"
        }
    }

    var generalSettings: String {
        switch connections {
        case "Verbindungen": return "Allgemein"
        case "Connessioni": return "Generali"
        case "Conexiones": return "General"
        case "Συνδέσεις": return "Γενικά"
        case "Connexions": return "Général"
        default: return "General"
        }
    }

    var securitySettings: String {
        switch connections {
        case "Verbindungen": return "Sicherheit"
        case "Connessioni": return "Sicurezza"
        case "Conexiones": return "Seguridad"
        case "Συνδέσεις": return "Ασφάλεια"
        case "Connexions": return "Sécurité"
        default: return "Security"
        }
    }

    var updatesSettings: String {
        switch connections {
        case "Verbindungen": return "Updates"
        case "Connessioni": return "Aggiornamenti"
        case "Conexiones": return "Actualizaciones"
        case "Συνδέσεις": return "Ενημερώσεις"
        case "Connexions": return "Mises à jour"
        default: return "Updates"
        }
    }

    var emptyWorkspaceTitle: String {
        switch connections {
        case "Verbindungen": return "Bereit für direkte Verbindungen"
        case "Connessioni": return "Pronto per le connessioni dirette"
        case "Conexiones": return "Listo para conexiones directas"
        case "Συνδέσεις": return "Έτοιμο για απευθείας συνδέσεις"
        case "Connexions": return "Prêt pour les connexions directes"
        default: return "Ready for Direct Connections"
        }
    }

    var emptyWorkspaceHint: String {
        switch connections {
        case "Verbindungen": return "Doppelklicke links auf einen Server, drücke ⌘K für die Schnellverbindung oder erstelle eine neue RDP-, SSH-, SFTP- oder VNC-Verbindung."
        case "Connessioni": return "Fai doppio clic su un server nella barra laterale, premi ⌘K per Connessione rapida o crea una nuova connessione RDP, SSH, SFTP o VNC."
        case "Conexiones": return "Haz doble clic en un servidor de la barra lateral, pulsa ⌘K para Conexión rápida o crea una nueva conexión RDP, SSH, SFTP o VNC."
        case "Συνδέσεις": return "Κάντε διπλό κλικ σε διακομιστή στην πλαϊνή μπάρα, πατήστε ⌘K για Γρήγορη σύνδεση ή δημιουργήστε νέα σύνδεση RDP, SSH, SFTP ή VNC."
        case "Connexions": return "Double-cliquez sur un serveur dans la barre latérale, appuyez sur ⌘K pour Connexion rapide ou créez une connexion RDP, SSH, SFTP ou VNC."
        default: return "Double-click any server in the sidebar, press ⌘K for Quick Connect, or create a new RDP, SSH, SFTP, or VNC connection."
        }
    }

    var quickConnectLaunchpadHint: String {
        switch connections {
        case "Verbindungen": return "Schnellverbindung — Server, Hosts oder Protokolle suchen…"
        case "Connessioni": return "Connessione rapida — cerca server, host o protocolli…"
        case "Conexiones": return "Conexión rápida — buscar servidores, hosts o protocolos…"
        case "Συνδέσεις": return "Γρήγορη σύνδεση — αναζήτηση διακομιστών, hosts ή πρωτοκόλλων…"
        case "Connexions": return "Connexion rapide — rechercher serveurs, hôtes ou protocoles…"
        default: return "Quick Connect — search servers, hosts, or protocols…"
        }
    }

    var importShortCta: String {
        switch connections {
        case "Verbindungen": return ".rtsz importieren"
        case "Connessioni": return "Importa .rtsz"
        case "Conexiones": return "Importar .rtsz"
        case "Συνδέσεις": return "Εισαγωγή .rtsz"
        case "Connexions": return "Importer .rtsz"
        default: return "Import .rtsz"
        }
    }

    var activeSessionsLower: String {
        switch connections {
        case "Verbindungen": return "aktive Sitzungen"
        case "Connessioni": return "sessioni attive"
        case "Conexiones": return "sesiones activas"
        case "Συνδέσεις": return "ενεργές συνεδρίες"
        case "Connexions": return "sessions actives"
        default: return "active sessions"
        }
    }

    var addTodoCta: String {
        switch connections {
        case "Verbindungen": return "Aufgabe hinzufügen"
        case "Connessioni": return "Aggiungi attività"
        case "Conexiones": return "Añadir tarea"
        case "Συνδέσεις": return "Προσθήκη εργασίας"
        case "Connexions": return "Ajouter une tâche"
        default: return "Add To-Do"
        }
    }

    var todoTitle: String {
        switch connections {
        case "Verbindungen": return "Checkliste"
        case "Connessioni": return "Elenco attività"
        case "Conexiones": return "Lista de tareas"
        case "Συνδέσεις": return "Λίστα εργασιών"
        case "Connexions": return "Liste de tâches"
        default: return "Workspace Checklist"
        }
    }

    var favoritesEmptyHint: String {
        switch connections {
        case "Verbindungen": return "Doppelklicke links auf einen Server zum Verbinden oder markiere eine Verbindung als Favorit."
        case "Connessioni": return "Fai doppio clic su un server nella barra laterale o aggiungi una connessione ai Preferiti."
        case "Conexiones": return "Haz doble clic en un servidor de la barra lateral o marca una conexión como favorita."
        case "Συνδέσεις": return "Κάντε διπλό κλικ σε διακομιστή στην πλαϊνή μπάρα ή προσθέστε σύνδεση στα Αγαπημένα."
        case "Connexions": return "Double-cliquez sur un serveur dans la barre latérale ou épinglez une connexion en favori."
        default: return "Double-click any server in the sidebar to launch a session, or star a connection to pin it to Favorites."
        }
    }

    var newTemplate: String {
        switch connections {
        case "Verbindungen": return "Neue Vorlage…"
        case "Connessioni": return "Nuovo modello…"
        case "Conexiones": return "Nueva plantilla…"
        case "Συνδέσεις": return "Νέο πρότυπο…"
        case "Connexions": return "Nouveau modèle…"
        default: return "New Template…"
        }
    }

    func syncedRevLabel(_ rev: UInt64) -> String {
        switch connections {
        case "Verbindungen": return "Synchron (Rev \(rev))"
        case "Connessioni": return "Sincronizzato (rev \(rev))"
        case "Conexiones": return "Sincronizado (rev \(rev))"
        case "Συνδέσεις": return "Συγχρονισμένο (έκδ. \(rev))"
        case "Connexions": return "Synchronisé (rév \(rev))"
        default: return "Synced (rev \(rev))"
        }
    }
}


