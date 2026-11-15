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
                studios: "Studios",
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
                copyDiagnostics: "Diagnose kopieren"
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
                studios: "Studio",
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
                copyDiagnostics: "Copia Diagnostica"
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
                studios: "Estudios",
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
                copyDiagnostics: "Copiar Diagnóstico"
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
                studios: "Στούντιο",
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
                copyDiagnostics: "Αντιγραφή Διαγνωστικών"
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
                studios: "Studios",
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
                copyDiagnostics: "Copier le Diagnostic"
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
                studios: "Studios",
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
                copyDiagnostics: "Copy Diagnostics"
            )
        }
    }
}
