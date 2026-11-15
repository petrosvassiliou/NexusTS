import AppKit
import NexusTSCore
import SwiftUI

// MARK: - 1. Full Protocol Connection Editor Sheet (.formStyle(.grouped))

public struct ConnectionEditorSheet: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.dismiss) private var dismiss

    public let existing: VaultConnection?
    public let initialFolderId: String?
    public let initialProtocol: VaultProtocolType

    @State private var name: String = ""
    @State private var host: String = ""
    @State private var protocolType: VaultProtocolType = .SSH
    @State private var parentFolderId: String = ""
    @State private var osFamily: String = "Linux"
    @State private var tagsText: String = ""
    @State private var notes: String = ""
    @State private var favorite: Bool = false

    // Inheritance
    @State private var credentialMode: InheritanceMode = .INHERIT_FROM_PARENT
    @State private var explicitCredentialId: String = ""
    @State private var colorHex: String = ""

    // Protocol Specifics
    @State private var sshSettings = SshProtocolSettings()
    @State private var rdpSettings = RdpProtocolSettings()
    @State private var transferSettings = FileTransferProtocolSettings()
    @State private var vncSettings = VncProtocolSettings()
    @State private var webSettings = WebPageProtocolSettings()
    @State private var serialSettings = SerialUartProtocolSettings()
    @State private var containerSettings = ContainerExecProtocolSettings()
    @State private var cloudSettings = CloudVirtualizationProtocolSettings()
    @State private var netSettings = NetUtilityProtocolSettings()

    public init(
        existing: VaultConnection?,
        initialFolderId: String?,
        initialProtocol: VaultProtocolType
    ) {
        self.existing = existing
        self.initialFolderId = initialFolderId
        self.initialProtocol = initialProtocol
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                ProtocolSquircleIcon(protocolType: protocolType, customHex: colorHex.isEmpty ? nil : colorHex, size: 30)
                VStack(alignment: .leading, spacing: 1) {
                    Text(existing == nil ? "New \(protocolType.displayName) Connection" : "Edit \(name)")
                        .font(.headline)
                    Text("Encrypted inside .nxts v2 Zero-Knowledge Vault")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
            .background(.bar)

            Divider()

            Form {
                Section("General & Hierarchy") {
                    TextField("Display Name", text: $name)
                    Picker("Protocol", selection: $protocolType) {
                        ForEach(VaultProtocolType.allCases) { p in
                            Text(p.displayName).tag(p)
                        }
                    }
                    TextField(protocolType == .SERIAL ? "Device Hint (/dev/cu.*)" : "Hostname / IP / URI", text: $host)

                    Picker("Parent Folder", selection: $parentFolderId) {
                        Text("Vault Root (Top Level)").tag("")
                        ForEach(store.activeFolders) { folder in
                            Text(folder.name).tag(folder.id)
                        }
                    }

                    TextField("OS Family", text: $osFamily)
                    TextField("Tags (comma-separated)", text: $tagsText)
                    Toggle("Pin to Favorites", isOn: $favorite)
                }

                Section("Credential Inheritance") {
                    Picker("Authentication Mode", selection: $credentialMode) {
                        ForEach(InheritanceMode.allCases, id: \.self) { mode in
                            Text(mode.displayTitle).tag(mode)
                        }
                    }

                    if credentialMode == .OVERRIDE_EXPLICIT {
                        Picker("Vault Credential", selection: $explicitCredentialId) {
                            Text("Select Credential…").tag("")
                            ForEach(store.document.credentials) { cred in
                                Text("\(cred.name) (\(cred.username))").tag(cred.id)
                            }
                        }
                    }

                    Picker("Finder Accent Tag", selection: $colorHex) {
                        ForEach(HIGColorUtilities.finderTagPalette, id: \.label) { item in
                            Text(item.label).tag(item.hex ?? "")
                        }
                    }
                }

                protocolSpecificFormSection

                Section("Notes") {
                    TextField("Runbook or operational notes…", text: $notes, axis: .vertical)
                        .lineLimit(3...6)
                }
            }
            .formStyle(.grouped)

            Divider()

            HStack {
                Button("Cancel", role: .cancel) {
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)

                Spacer()

                Button(existing == nil ? "Create Connection" : "Save Changes") {
                    saveConnection()
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .background(.bar)
        }
        .frame(width: 600, height: 640)
        .onAppear(perform: populateInitialState)
    }

    @ViewBuilder
    private var protocolSpecificFormSection: some View {
        switch protocolType {
        case .RDP, .WINDOWS_TERMINAL_SERVICES:
            Section("FreeRDP 3.x Display & Performance") {
                TextField("TCP Port", value: $rdpSettings.port, format: .number)
                Picker("Resize Behavior", selection: $rdpSettings.resizeBehavior) {
                    Text("Dynamic Retina Resize (DISP_DVC)").tag(RdpResizeBehavior.DYNAMIC_DISP_DVC)
                    Text("Smart Scale Viewport").tag(RdpResizeBehavior.SMART_SCALE_VIEWPORT)
                    Text("Fixed Server Resolution").tag(RdpResizeBehavior.FIXED_SERVER_RESOLUTION)
                }
                Toggle("Retina HiDPI Scaling", isOn: $rdpSettings.hiDpiEnabled)
                Toggle("RDPGFX + H.264 AVC444 Hardware Pipeline", isOn: $rdpSettings.enableH264Avc444)
                Toggle("Multi-Monitor Spanning", isOn: $rdpSettings.multiMonitorSpanning)
                Toggle("Connect to Admin / Console Session (/admin)", isOn: $rdpSettings.connectToConsoleAdmin)
            }
            Section("FreeRDP Redirection & NLA Security") {
                Toggle("Bidirectional Clipboard Sync", isOn: $rdpSettings.redirectClipboard)
                Toggle("Smartcard / YubiKey PIV Redirection", isOn: $rdpSettings.redirectSmartcardYubiKey)
                Toggle("Network Level Authentication (NLA / CredSSP)", isOn: $rdpSettings.nlaSecurityRequired)
                Toggle("Restricted Admin Mode (Pass-the-Hash Guard)", isOn: $rdpSettings.restrictedAdminMode)
                Toggle("Ignore Self-Signed TLS Certificate Warnings", isOn: $rdpSettings.ignoreCertificateWarnings)
                TextField("RD Gateway Host (Optional)", text: $rdpSettings.rdGatewayHostname)
            }

        case .SSH, .TELNET:
            Section("SSHv2 / Terminal Engine") {
                TextField("Port", value: $sshSettings.port, format: .number)
                TextField("TERM Type", text: $sshSettings.terminalType)
                Picker("Bastion Jump Host (-J)", selection: Binding(
                    get: { sshSettings.bastionJumpConnectionId ?? "" },
                    set: { sshSettings.bastionJumpConnectionId = $0.isEmpty ? nil : $0 }
                )) {
                    Text("Direct (No Bastion)").tag("")
                    ForEach(store.activeConnections.filter { $0.protocolType == .SSH && $0.id != existing?.id }) { sshConn in
                        Text("\(sshConn.name) (\(sshConn.host))").tag(sshConn.id)
                    }
                }
                Toggle("Forward In-Memory Ed25519 SSH Agent", isOn: $sshSettings.enableAgentForwarding)
                Toggle("X11 Forwarding (XQuartz compatible)", isOn: $sshSettings.enableX11Forwarding)
                TextField("Startup Command", text: $sshSettings.startupCommand)
                TextField("Scrollback Lines", value: $sshSettings.scrollbackLines, format: .number)
            }

        case .SFTP, .FTP, .FTPS, .SCP:
            Section("Dual-Pane File Transfer") {
                TextField("Port", value: $transferSettings.port, format: .number)
                TextField("Initial Local macOS Directory", text: $transferSettings.initialLocalDirectory)
                TextField("Initial Remote Directory", text: $transferSettings.initialRemoteDirectory)
                Toggle("Show Hidden Dotfiles", isOn: $transferSettings.showHiddenDotfiles)
                Toggle("Preserve Timestamps", isOn: $transferSettings.preserveTimestamps)
            }

        case .VNC, .APPLE_SCREEN_SHARING:
            Section("VNC / Apple Remote Desktop (ARD)") {
                TextField("RFB Port", value: $vncSettings.port, format: .number)
                Toggle("Apple Remote Desktop Diffie-Hellman Auth", isOn: $vncSettings.appleArdDiffieHellmanAuth)
                Toggle("Shared Desktop Session", isOn: $vncSettings.sharedSession)
                Toggle("View-Only Mode", isOn: $vncSettings.viewOnly)
            }

        case .SERIAL:
            Section("IOKit Hardware UART / Serial") {
                TextField("Callout Device (/dev/cu.*)", text: $serialSettings.devicePathHint)
                Picker("Baud Rate", selection: $serialSettings.baudRate) {
                    ForEach(MacSerialPortSession.supportedBaudRates, id: \.self) { b in
                        Text("\(b) baud").tag(b)
                    }
                }
                Toggle("Assert DTR", isOn: $serialSettings.dtrEnabled)
                Toggle("Assert RTS", isOn: $serialSettings.rtsEnabled)
            }

        case .WEB_PAGE:
            Section("Isolated Web Console") {
                TextField("Target HTTPS URL", text: $webSettings.url)
                Toggle("Enable Vault Credential JS Auto-Fill", isOn: $webSettings.autoFillEnabled)
                Toggle("Allow Internal Self-Signed TLS Certificates", isOn: $webSettings.ignoreSelfSignedInternalCert)
            }

        case .KUBERNETES_POD, .DOCKER_CONTAINER:
            Section("Kubernetes / Docker Socket") {
                TextField("API / Socket (e.g. unix:///var/run/docker.sock)", text: $containerSettings.clusterOrSocketEndpoint)
                TextField("Namespace", text: $containerSettings.namespace)
                TextField("Pod or Container Name", text: $containerSettings.podOrContainerName)
                TextField("Exec Shell", text: $containerSettings.shellCommand)
            }

        case .VMWARE_VSPHERE, .PROXMOX_VE, .HYPER_V, .AWS_EC2, .AZURE_VM:
            Section("Cloud / Hypervisor API") {
                TextField("API Endpoint URL", text: $cloudSettings.apiEndpointUrl)
                TextField("Region or Cluster (e.g. eu-central-1)", text: $cloudSettings.regionOrCluster)
                TextField("Subscription / Account ID", text: $cloudSettings.subscriptionOrAccountId)
            }

        case .UTILITY_PING, .UTILITY_TRACEROUTE, .UTILITY_PORT_SCANNER, .UTILITY_WAKE_ON_LAN:
            Section("Network Diagnostic Parameters") {
                TextField("Port Scan Start", value: $netSettings.portScanRangeStart, format: .number)
                TextField("Port Scan End", value: $netSettings.portScanRangeEnd, format: .number)
                TextField("Wake-on-LAN MAC Address", text: $netSettings.wolMacAddress)
            }
        }
    }

    private func populateInitialState() {
        if let conn = existing {
            name = conn.name
            host = conn.host
            protocolType = conn.protocolType
            parentFolderId = conn.parentFolderId ?? ""
            osFamily = conn.osFamily
            tagsText = conn.tags.joined(separator: ", ")
            notes = conn.notes
            favorite = conn.favorite
            credentialMode = conn.inheritable.credentialMode
            explicitCredentialId = conn.inheritable.explicitCredentialId ?? ""
            colorHex = conn.inheritable.displayColorAccentHex ?? ""

            switch conn.settings {
            case .rdp(let s): rdpSettings = s
            case .ssh(let s): sshSettings = s
            case .fileTransfer(let s): transferSettings = s
            case .vnc(let s): vncSettings = s
            case .webPage(let s): webSettings = s
            case .serialUart(let s): serialSettings = s
            case .containerExec(let s): containerSettings = s
            case .cloudVirtualization(let s): cloudSettings = s
            case .netUtility(let s): netSettings = s
            }
        } else {
            protocolType = initialProtocol
            let safeInitialFolder = (initialFolderId == VaultHierarchySanitizer.systemCredentialsFolderId) ? nil : initialFolderId
            parentFolderId = safeInitialFolder ?? ""
            sshSettings.port = initialProtocol == .TELNET ? 23 : 22
            transferSettings.port = initialProtocol.defaultPort
            if initialProtocol == .APPLE_SCREEN_SHARING {
                vncSettings.appleArdDiffieHellmanAuth = true
            }
        }
    }

    private func assembledSettings() -> ProtocolSpecificSettings {
        switch protocolType {
        case .RDP, .WINDOWS_TERMINAL_SERVICES:
            return .rdp(rdpSettings)
        case .SSH, .TELNET:
            return .ssh(sshSettings)
        case .SFTP, .FTP, .FTPS, .SCP:
            return .fileTransfer(transferSettings)
        case .VNC, .APPLE_SCREEN_SHARING:
            return .vnc(vncSettings)
        case .WEB_PAGE:
            if webSettings.url.isEmpty {
                webSettings.url = host.hasPrefix("http") ? host : "https://\(host)"
            }
            return .webPage(webSettings)
        case .SERIAL:
            if !host.isEmpty { serialSettings.devicePathHint = host }
            return .serialUart(serialSettings)
        case .KUBERNETES_POD, .DOCKER_CONTAINER:
            return .containerExec(containerSettings)
        case .VMWARE_VSPHERE, .PROXMOX_VE, .HYPER_V, .AWS_EC2, .AZURE_VM:
            return .cloudVirtualization(cloudSettings)
        case .UTILITY_PING, .UTILITY_TRACEROUTE, .UTILITY_PORT_SCANNER, .UTILITY_WAKE_ON_LAN:
            return .netUtility(netSettings)
        }
    }

    private func saveConnection() {
        let parsedTags = tagsText
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        var inheritable = existing?.inheritable ?? InheritableProperties()
        inheritable.credentialMode = credentialMode
        inheritable.explicitCredentialId = credentialMode == .OVERRIDE_EXPLICIT && !explicitCredentialId.isEmpty ? explicitCredentialId : nil
        inheritable.displayColorAccentHex = colorHex.isEmpty ? nil : colorHex

        let resolvedParentId: String? = {
            if parentFolderId.isEmpty || parentFolderId == VaultHierarchySanitizer.systemCredentialsFolderId {
                return nil
            }
            return parentFolderId
        }()

        let nextSortOrder: Int = {
            if let existing, existing.parentFolderId == resolvedParentId {
                return existing.sortOrder
            }
            return (store.activeConnections
                .filter { $0.parentFolderId == resolvedParentId }
                .map(\.sortOrder)
                .max() ?? -1) + 1
        }()

        let built = VaultConnection(
            id: existing?.id ?? UUID().uuidString.lowercased(),
            parentFolderId: resolvedParentId,
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            host: host.trimmingCharacters(in: .whitespacesAndNewlines),
            protocolType: protocolType,
            osFamily: osFamily,
            tags: parsedTags,
            notes: notes,
            favorite: favorite,
            sortOrder: nextSortOrder,
            inheritable: inheritable,
            settings: assembledSettings(),
            updatedAtEpochMs: VaultClock.nowMs()
        )

        store.commitMutation(existing == nil ? "Created connection \(built.name)" : "Updated connection \(built.name)") { doc in
            if let idx = doc.connections.firstIndex(where: { $0.id == built.id }) {
                doc.connections[idx] = built
            } else {
                doc.connections.append(built)
            }
        }
    }
}

// MARK: - 2. Folder & Cascading Inheritance Editor Sheet

public struct FolderInheritanceEditorSheet: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.dismiss) private var dismiss

    public let existing: VaultFolder?

    @State private var name: String = ""
    @State private var parentFolderId: String = ""
    @State private var environmentBadge: String = ""
    @State private var credentialMode: InheritanceMode = .INHERIT_FROM_PARENT
    @State private var explicitCredentialId: String = ""
    @State private var colorHex: String = ""

    public init(existing: VaultFolder?) {
        self.existing = existing
    }

    private var isReservedName: Bool {
        VaultHierarchySanitizer.isReservedCredentialsFolderName(name)
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack {
                Image(systemName: "folder.fill.badge.gearshape")
                    .font(.title2)
                    .foregroundStyle(Color.accentColor)
                Text(existing == nil ? "New Folder & Inheritance Scope" : "Folder Properties: \(name)")
                    .font(.headline)
                Spacer()
            }
            .padding(18)
            .background(.bar)

            Divider()

            Form {
                Section("Folder Hierarchy") {
                    TextField("Folder Name", text: $name)
                    if isReservedName {
                        Text("\"Credentials\" is reserved for the bottom-pinned system Credentials folder.")
                            .font(.caption)
                            .foregroundStyle(Color(nsColor: .systemRed))
                    }
                    Picker("Parent Folder", selection: $parentFolderId) {
                        Text("Vault Root").tag("")
                        ForEach(store.activeFolders.filter { candidate in
                            guard let existingId = existing?.id else { return true }
                            return store.canMoveFolder(existingId, toParentFolderId: candidate.id)
                        }) { f in
                            Text(f.name).tag(f.id)
                        }
                    }
                    TextField("Environment Badge (e.g. PROD, STAGING, LAB)", text: $environmentBadge)
                    Picker("Folder Color Accent", selection: $colorHex) {
                        ForEach(HIGColorUtilities.finderTagPalette, id: \.label) { item in
                            Text(item.label).tag(item.hex ?? "")
                        }
                    }
                }

                Section("Cascading Credential Inheritance (Applies to All Descendant Connections)") {
                    Picker("Folder Credential Policy", selection: $credentialMode) {
                        ForEach(InheritanceMode.allCases, id: \.self) { m in
                            Text(m.displayTitle).tag(m)
                        }
                    }
                    if credentialMode == .OVERRIDE_EXPLICIT {
                        Picker("Inherited Credential", selection: $explicitCredentialId) {
                            Text("Select Credential…").tag("")
                            ForEach(store.activeCredentials) { cred in
                                Text("\(cred.name) (\(cred.username))").tag(cred.id)
                            }
                        }
                    }
                }
            }
            .formStyle(.grouped)

            Divider()

            HStack {
                Button("Cancel", role: .cancel) {
                    store.newFolderInitialParentId = nil
                    dismiss()
                }
                Spacer()
                Button(existing == nil ? "Create Folder" : "Save Folder") {
                    guard !isReservedName else { return }
                    var inh = existing?.inheritable ?? InheritableProperties()
                    inh.environmentBadge = environmentBadge.trimmingCharacters(in: .whitespaces).isEmpty ? nil : environmentBadge
                    inh.credentialMode = credentialMode
                    inh.explicitCredentialId = credentialMode == .OVERRIDE_EXPLICIT && !explicitCredentialId.isEmpty ? explicitCredentialId : nil
                    inh.displayColorAccentHex = colorHex.isEmpty ? nil : colorHex

                    let resolvedParent: String? = {
                        if parentFolderId.isEmpty || parentFolderId == VaultHierarchySanitizer.systemCredentialsFolderId {
                            return nil
                        }
                        return parentFolderId
                    }()

                    let folder = VaultFolder(
                        id: existing?.id ?? UUID().uuidString.lowercased(),
                        parentFolderId: resolvedParent,
                        name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                        description: existing?.description ?? "",
                        tags: existing?.tags ?? [],
                        inheritable: inh,
                        sortOrder: existing?.sortOrder ?? ((store.activeFolders.filter {
                            $0.parentFolderId == resolvedParent
                        }.map(\.sortOrder).max() ?? -1) + 1),
                        updatedAtEpochMs: VaultClock.nowMs()
                    )
                    store.commitMutation(existing == nil ? "Created folder \(folder.name)" : "Updated folder \(folder.name)") { doc in
                        if let idx = doc.folders.firstIndex(where: { $0.id == folder.id }) {
                            doc.folders[idx] = folder
                        } else {
                            doc.folders.append(folder)
                        }
                    }
                    store.newFolderInitialParentId = nil
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || isReservedName)
            }
            .padding(14)
            .background(.bar)
        }
        .frame(width: 500, height: 450)
        .onAppear {
            if let f = existing {
                name = f.name
                parentFolderId = f.parentFolderId ?? ""
                environmentBadge = f.inheritable.environmentBadge ?? ""
                credentialMode = f.inheritable.credentialMode
                explicitCredentialId = f.inheritable.explicitCredentialId ?? ""
                colorHex = f.inheritable.displayColorAccentHex ?? ""
            } else {
                let candidateParent = store.newFolderInitialParentId ?? store.selectedFolderId
                parentFolderId = (candidateParent == VaultHierarchySanitizer.systemCredentialsFolderId) ? "" : (candidateParent ?? "")
            }
        }
    }
}

// MARK: - 2b. Quick Rename Folder Sheet

public struct RenameFolderSheet: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.dismiss) private var dismiss

    public let folder: VaultFolder
    @State private var folderName: String = ""
    @FocusState private var isFocused: Bool

    public init(folder: VaultFolder) {
        self.folder = folder
    }

    private var isReservedName: Bool {
        VaultHierarchySanitizer.isReservedCredentialsFolderName(folderName)
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Image(systemName: "folder.fill")
                    .font(.title3)
                    .foregroundStyle(
                        HIGColorUtilities.color(fromHex: folder.inheritable.displayColorAccentHex)
                            ?? Color.accentColor
                    )
                Text(store.strings.renameFolder)
                    .font(.headline)
                Spacer()
            }

            TextField("Folder Name", text: $folderName)
                .textFieldStyle(.roundedBorder)
                .focused($isFocused)
                .onSubmit {
                    let trimmed = folderName.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty && !isReservedName {
                        store.renameFolder(folder.id, newName: trimmed)
                        dismiss()
                    }
                }

            if isReservedName {
                Text("\"Credentials\" is reserved for the bottom-pinned system Credentials folder.")
                    .font(.caption)
                    .foregroundStyle(Color(nsColor: .systemRed))
            }

            HStack {
                Button("Cancel", role: .cancel) {
                    store.renamingFolder = nil
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)
                Spacer()
                Button("Save") {
                    store.renameFolder(folder.id, newName: folderName)
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(folderName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isReservedName)
            }
        }
        .padding(18)
        .frame(width: 380)
        .onAppear {
            folderName = folder.name
            isFocused = true
        }
    }
}

// MARK: - 3. Ad-Hoc Connect With Options Sheet

public struct ConnectWithOptionsSheet: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismiss) private var dismiss

    public let connection: VaultConnection

    @State private var overrideUsername: String = ""
    @State private var overridePassword: String = ""
    @State private var overrideDomain: String = ""
    @State private var targetDestination: Int = 0 // 0 = Tab, 1 = Split Right, 2 = New Window

    public init(connection: VaultConnection) {
        self.connection = connection
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                ProtocolSquircleIcon(protocolType: connection.protocolType, size: 28)
                VStack(alignment: .leading) {
                    Text("Connect with Options — \(connection.name)")
                        .font(.headline)
                    Text("One-time credential & layout override (does not mutate vault)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(16)
            .background(.bar)

            Divider()

            Form {
                Section("One-Time Credential Override (Leave blank to use inherited credential)") {
                    TextField("Username Override", text: $overrideUsername)
                    SecureField("Password Override", text: $overridePassword)
                    TextField("Domain Override", text: $overrideDomain)
                }

                Section("Launch Target") {
                    Picker("Open Session In", selection: $targetDestination) {
                        Text("Active Tab Strip").tag(0)
                        Text("Split Pane Right").tag(1)
                        Text("Detached macOS Window").tag(2)
                    }
                    .pickerStyle(.radioGroup)
                }
            }
            .formStyle(.grouped)

            Divider()

            HStack {
                Button("Cancel", role: .cancel) { dismiss() }
                Spacer()
                Button("Connect Now") {
                    let u = overrideUsername.isEmpty ? nil : overrideUsername
                    let p = overridePassword.isEmpty ? nil : overridePassword
                    let d = overrideDomain.isEmpty ? nil : overrideDomain

                    if targetDestination == 2 {
                        let tab = WorkspaceSessionTab(
                            connection: connection,
                            adHocUsernameOverride: u,
                            adHocPasswordOverride: p,
                            adHocDomainOverride: d
                        )
                        store.detachedWindowTabs[tab.id] = tab
                        openWindow(id: "detached-session", value: tab.id)
                    } else {
                        store.openConnection(
                            connection,
                            inSplit: targetDestination == 1 ? .horizontal : nil,
                            adHocUsername: u,
                            adHocPassword: p,
                            adHocDomain: d
                        )
                    }
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
            }
            .padding(14)
            .background(.bar)
        }
        .frame(width: 460, height: 360)
    }
}

// MARK: - 4. Intelligent Folder (SmartFolder) Editor Sheet

public struct SmartFolderEditorSheet: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.dismiss) private var dismiss

    public let existing: SmartFolder?

    @State private var name: String = ""
    @State private var scopeFolderId: String = ""
    @State private var selectedProtocols: Set<VaultProtocolType> = []
    @State private var includeTagsText: String = ""
    @State private var excludeTagsText: String = ""
    @State private var osFamilyFilter: String = ""
    @State private var environmentFilter: String = ""
    @State private var hostPatternRegex: String = ""

    public init(existing: SmartFolder?) {
        self.existing = existing
    }

    private var regexValidationError: String? {
        PropertyInheritanceResolver.validateSmartFolderRegex(hostPatternRegex)
    }

    private func buildCandidateSmartFolder() -> SmartFolder {
        let includeTags = includeTagsText
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let excludeTags = excludeTagsText
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let trimmedOs = osFamilyFilter.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedEnv = environmentFilter.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedRegex = hostPatternRegex.trimmingCharacters(in: .whitespacesAndNewlines)

        let filter = SmartFolderFilter(
            includeTags: includeTags,
            excludeTags: excludeTags,
            includeProtocols: VaultProtocolType.allCases.filter { selectedProtocols.contains($0) },
            osFamilyFilter: trimmedOs.isEmpty ? nil : trimmedOs,
            hostPatternRegex: trimmedRegex.isEmpty ? nil : trimmedRegex,
            environmentFilter: trimmedEnv.isEmpty ? nil : trimmedEnv,
            scopeFolderId: scopeFolderId.isEmpty ? nil : scopeFolderId
        )
        return SmartFolder(
            id: existing?.id ?? "smart-\(UUID().uuidString.lowercased())",
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            filter: filter,
            updatedAtEpochMs: VaultClock.nowMs()
        )
    }

    public var body: some View {
        let candidate = buildCandidateSmartFolder()
        let liveMatches = regexValidationError == nil
            ? PropertyInheritanceResolver.evaluateSmartFolder(candidate, in: store.document)
            : []

        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Image(systemName: "sparkles.rectangle.stack.fill")
                    .font(.title2)
                    .foregroundStyle(Color(nsColor: .systemPurple))
                VStack(alignment: .leading, spacing: 2) {
                    Text(existing == nil ? store.strings.newSmartFolder : store.strings.editSmartFolder)
                        .font(.headline)
                    Text("Dynamically evaluates connections across your vault by protocol, folder scope, tags, OS, and regex")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(18)
            .background(.bar)

            Divider()

            Form {
                Section("General & Scope") {
                    TextField("Intelligent Folder Name", text: $name)
                    Picker("Folder Scope", selection: $scopeFolderId) {
                        Text("Entire Vault (All Folders)").tag("")
                        ForEach(store.activeFolders) { folder in
                            Text(folder.name).tag(folder.id)
                        }
                    }
                }

                Section("Protocol Filter (Select zero or more; empty matches any protocol)") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 8)], spacing: 6) {
                        ForEach(VaultProtocolType.allCases) { proto in
                            let isChosen = selectedProtocols.contains(proto)
                            Button {
                                if isChosen {
                                    selectedProtocols.remove(proto)
                                } else {
                                    selectedProtocols.insert(proto)
                                }
                            } label: {
                                HStack(spacing: 5) {
                                    Image(systemName: isChosen ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(isChosen ? Color.accentColor : .secondary)
                                    Text(proto.shortBadge)
                                        .font(.caption.weight(isChosen ? .semibold : .regular))
                                    Spacer(minLength: 0)
                                }
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(
                                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                                        .fill(isChosen ? Color.accentColor.opacity(0.14) : Color.primary.opacity(0.04))
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    if !selectedProtocols.isEmpty {
                        Button("Clear Protocol Filter (Match All)") {
                            selectedProtocols.removeAll()
                        }
                        .controlSize(.small)
                    }
                }

                Section("Tag, OS, Environment & Regex Rules") {
                    TextField("Require Tags (comma-separated, all must match)", text: $includeTagsText)
                    TextField("Exclude Tags (comma-separated, none may match)", text: $excludeTagsText)
                    TextField("OS Family Contains (e.g. Windows, Linux, macOS)", text: $osFamilyFilter)
                    TextField("Environment Badge (e.g. PROD, STAGING, LAB)", text: $environmentFilter)
                    TextField("Host or Connection Name Regex (e.g. ^dc-\\d+)", text: $hostPatternRegex)
                        .font(.system(.body, design: .monospaced))
                    if let err = regexValidationError {
                        Text(err)
                            .font(.caption)
                            .foregroundStyle(Color(nsColor: .systemRed))
                    }
                }

                Section("Live Match Preview (\(liveMatches.count) connection\(liveMatches.count == 1 ? "" : "s"))") {
                    if liveMatches.isEmpty {
                        Text("No existing connections currently match these filter criteria.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(liveMatches.prefix(10)) { conn in
                            HStack(spacing: 8) {
                                ProtocolSquircleIcon(protocolType: conn.protocolType, size: 18)
                                Text(conn.name)
                                    .font(.caption.weight(.medium))
                                Spacer()
                                Text(conn.protocolType.shortBadge)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        if liveMatches.count > 10 {
                            Text("+ \(liveMatches.count - 10) more matching connections")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .formStyle(.grouped)

            Divider()

            HStack {
                if let existing {
                    Button(role: .destructive) {
                        store.deleteSmartFolder(existing.id)
                        dismiss()
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
                Button("Cancel", role: .cancel) {
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)

                Spacer()

                Button(existing == nil ? "Create Intelligent Folder" : "Save Changes") {
                    store.saveSmartFolder(buildCandidateSmartFolder())
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || regexValidationError != nil)
            }
            .padding(14)
            .background(.bar)
        }
        .frame(width: 600, height: 640)
        .onAppear {
            if let sf = existing {
                name = sf.name
                scopeFolderId = sf.filter.scopeFolderId ?? ""
                selectedProtocols = Set(sf.filter.includeProtocols)
                includeTagsText = sf.filter.includeTags.joined(separator: ", ")
                excludeTagsText = sf.filter.excludeTags.joined(separator: ", ")
                osFamilyFilter = sf.filter.osFamilyFilter ?? ""
                environmentFilter = sf.filter.environmentFilter ?? ""
                hostPatternRegex = sf.filter.hostPatternRegex ?? ""
            }
        }
    }
}

// MARK: - 5. Credential Editor Sheet (Pinned System "Credentials" Folder)

public struct CredentialEditorSheet: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.dismiss) private var dismiss

    public let existing: VaultCredential?

    @State private var name: String = ""
    @State private var username: String = ""
    @State private var domain: String = ""
    @State private var secretPassword: String = ""
    @State private var revealPassword: Bool = false
    @State private var totpSecretBase32: String = ""
    @State private var privateKeyPem: String = ""
    @State private var publicKeyOpenSsh: String = ""
    @State private var keyFingerprintSha256: String = ""
    @State private var keyPassphrase: String = ""

    public init(existing: VaultCredential?) {
        self.existing = existing
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Image(systemName: "key.fill")
                    .font(.title2)
                    .foregroundStyle(Color(nsColor: .systemOrange))
                VStack(alignment: .leading, spacing: 2) {
                    Text(existing == nil ? store.strings.newCredential : store.strings.editCredential)
                        .font(.headline)
                    Text("Stored inside the bottom-pinned Credentials system folder (.nxts AES-256-GCM)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(18)
            .background(.bar)

            Divider()

            Form {
                Section("Identity & Password") {
                    TextField("Credential Name", text: $name)
                    TextField("Username", text: $username)
                    TextField("Domain (AD / NetBIOS, Optional)", text: $domain)
                    HStack {
                        if revealPassword {
                            TextField("Password / API Secret", text: $secretPassword)
                        } else {
                            SecureField("Password / API Secret", text: $secretPassword)
                        }
                        Button {
                            revealPassword.toggle()
                        } label: {
                            Image(systemName: revealPassword ? "eye.slash" : "eye")
                        }
                        .buttonStyle(.borderless)
                    }
                    TextField("TOTP 2FA Secret (Base32 RFC 6238, Optional)", text: $totpSecretBase32)
                }

                Section("SSH Private Key (Optional)") {
                    HStack {
                        Button {
                            do {
                                let pair = try NativeSshCryptoBridge.generateEd25519Keypair(
                                    comment: "\(username.isEmpty ? NSUserName() : username)@NexusTS"
                                )
                                privateKeyPem = pair.privateKeyOpenSshPem
                                publicKeyOpenSsh = pair.publicKeyOpenSsh
                                keyFingerprintSha256 = pair.sha256Fingerprint
                                if name.trimmingCharacters(in: .whitespaces).isEmpty {
                                    name = "Ed25519 SSH Key (\(pair.keyId.prefix(6)))"
                                }
                            } catch {
                                store.reportError(error, operationTitle: "SSH Key Generation Failed")
                            }
                        } label: {
                            Label("Generate Ed25519 Keypair", systemImage: "wand.and.stars")
                        }
                        .controlSize(.small)

                        if !publicKeyOpenSsh.isEmpty {
                            Button("Copy Public Key") {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(publicKeyOpenSsh, forType: .string)
                            }
                            .controlSize(.small)
                        }
                    }

                    if !keyFingerprintSha256.isEmpty {
                        LabeledContent("Fingerprint", value: keyFingerprintSha256)
                            .font(.caption.monospaced())
                    }

                    TextEditor(text: $privateKeyPem)
                        .font(.system(size: 11, design: .monospaced))
                        .frame(minHeight: 90)
                    SecureField("Private Key Passphrase (Optional)", text: $keyPassphrase)
                }
            }
            .formStyle(.grouped)

            Divider()

            HStack {
                if let existing {
                    Button(role: .destructive) {
                        store.deleteCredential(existing.id)
                        dismiss()
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Spacer()
                Button(existing == nil ? "Create Credential" : "Save Credential") {
                    let sourceKind: CredentialSourceKind = !privateKeyPem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        ? .SSH_KEY_PAIR
                        : (existing?.sourceKind ?? .VAULT_INTERNAL)
                    let built = VaultCredential(
                        id: existing?.id ?? "cred-\(UUID().uuidString.lowercased())",
                        parentFolderId: nil,
                        name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                        sourceKind: sourceKind,
                        username: username.trimmingCharacters(in: .whitespacesAndNewlines),
                        domain: domain.trimmingCharacters(in: .whitespacesAndNewlines),
                        secretPassword: secretPassword,
                        privateKeyPem: privateKeyPem,
                        publicKeyOpenSsh: publicKeyOpenSsh,
                        keyFingerprintSha256: keyFingerprintSha256,
                        keyPassphrase: keyPassphrase,
                        externalReferenceUri: existing?.externalReferenceUri ?? "",
                        fido2CredentialIdBase64: existing?.fido2CredentialIdBase64 ?? "",
                        totpSecretBase32: totpSecretBase32.trimmingCharacters(in: .whitespacesAndNewlines),
                        updatedAtEpochMs: VaultClock.nowMs()
                    )
                    store.saveCredential(built)
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(14)
            .background(.bar)
        }
        .frame(width: 540, height: 540)
        .onAppear {
            if let cred = existing {
                name = cred.name
                username = cred.username
                domain = cred.domain
                secretPassword = cred.secretPassword
                totpSecretBase32 = cred.totpSecretBase32
                privateKeyPem = cred.privateKeyPem
                publicKeyOpenSsh = cred.publicKeyOpenSsh
                keyFingerprintSha256 = cred.keyFingerprintSha256
                keyPassphrase = cred.keyPassphrase
            }
        }
    }
}

// MARK: - 7. Royal TS Import Placement Picker Sheet (.rtsz / .rtsx / .rjson)

public struct RoyalTsImportPlacementSheet: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.dismiss) private var dismiss

    public let pending: PendingRoyalTsImport

    @State private var selectedMode: RoyalTsImportPlacementMode = .createNewFolder
    @State private var newFolderName: String = ""

    public init(pending: PendingRoyalTsImport) {
        self.pending = pending
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: "square.and.arrow.down.on.square.fill")
                    .font(.title2)
                    .foregroundStyle(Color.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text(store.strings.royalImportTitle)
                        .font(.headline)
                    Text(
                        "\(pending.summary.detectedFormat) • \(pending.summary.importedConnections) connections, \(pending.summary.importedFolders) folders, \(pending.summary.importedCredentials) credentials"
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(18)
            .background(.bar)

            Divider()

            Form {
                Section("Choose How to Place the Imported Royal TS Hierarchy") {
                    Picker("Import Placement", selection: $selectedMode) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(store.strings.royalImportNewFolder)
                                .font(.body.weight(.medium))
                            Text("Places the imported folder and connection structure inside a dedicated new folder.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .tag(RoyalTsImportPlacementMode.createNewFolder)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(store.strings.royalImportRoot)
                                .font(.body.weight(.medium))
                            Text("Places the imported folders and connections directly at the top level alongside your existing items.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .tag(RoyalTsImportPlacementMode.placeAtRoot)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(store.strings.royalImportReplaceAll)
                                .font(.body.weight(.medium))
                                .foregroundStyle(Color(nsColor: .systemRed))
                            Text("Deletes all existing connections, folders, and credentials before importing this Royal TS file.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .tag(RoyalTsImportPlacementMode.replaceExisting)
                    }
                    .pickerStyle(.radioGroup)

                    if selectedMode == .createNewFolder {
                        TextField("New Folder Name", text: $newFolderName)
                    }
                }

                Section {
                    Label(
                        "Imported credentials are automatically placed into the bottom-pinned \"Credentials\" system folder.",
                        systemImage: "key.fill"
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
            .formStyle(.grouped)

            Divider()

            HStack {
                Button("Cancel", role: .cancel) {
                    store.pendingRoyalImport = nil
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)

                Spacer()

                Button(selectedMode == .replaceExisting ? "Delete Existing & Import" : "Import Royal TS") {
                    store.confirmRoyalTsImport(mode: selectedMode, folderName: newFolderName)
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .tint(selectedMode == .replaceExisting ? Color(nsColor: .systemRed) : Color.accentColor)
                .keyboardShortcut(.defaultAction)
                .disabled(selectedMode == .createNewFolder && newFolderName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(14)
            .background(.bar)
        }
        .frame(width: 560, height: 420)
        .onAppear {
            newFolderName = pending.suggestedFolderName
        }
    }
}

// MARK: - 8. Reset NexusTS Database Confirmation Sheet

public struct ResetDatabaseConfirmationSheet: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.dismiss) private var dismiss

    @State private var confirmedUnderstanding: Bool = false
    @State private var backupStatusText: String? = nil

    public init() {}

    public var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 28))
                    .foregroundStyle(Color(nsColor: .systemRed))
                VStack(alignment: .leading, spacing: 2) {
                    Text(store.strings.resetDatabase)
                        .font(.headline)
                    Text("Permanently erases all local connections, folders, credentials, tasks, and saved vault passphrases.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if !store.isVaultLocked {
                HStack(spacing: 10) {
                    Button {
                        exportBackupBeforeReset()
                    } label: {
                        Label("Export Encrypted .nxts Backup First…", systemImage: "square.and.arrow.up")
                    }
                    if let backupStatusText {
                        Text(backupStatusText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Toggle(
                "I understand that resetting the NexusTS database permanently deletes all local vault items and cannot be undone.",
                isOn: $confirmedUnderstanding
            )
            .toggleStyle(.checkbox)
            .font(.callout)

            Divider()

            HStack {
                Button("Cancel", role: .cancel) {
                    store.isPresentingResetDatabaseSheet = false
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)

                Spacer()

                Button(role: .destructive) {
                    store.resetVaultDatabase()
                    dismiss()
                } label: {
                    Text(store.strings.resetDatabase)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color(nsColor: .systemRed))
                .disabled(!confirmedUnderstanding)
            }
        }
        .padding(22)
        .frame(width: 500)
    }

    private func exportBackupBeforeReset() {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "NexusTS-Backup-\(VaultClock.nowMs()).nxts"
        panel.canCreateDirectories = true
        if panel.runModal() == .OK, let destURL = panel.url {
            do {
                let data = try VaultDocumentExporter.export(document: store.document, format: .nxts)
                try data.write(to: destURL, options: .atomic)
                backupStatusText = "Saved backup to \(destURL.lastPathComponent)"
            } catch {
                backupStatusText = "Backup failed: \(error.localizedDescription)"
            }
        }
    }
}

// MARK: - 9. Structured Application Error Diagnostic Sheet

public struct NexusAppErrorSheet: View {
    @EnvironmentObject private var store: WorkspaceStore
    @Environment(\.dismiss) private var dismiss

    public let error: NexusAppError

    public init(error: NexusAppError) {
        self.error = error
    }

    private var categorySymbol: String {
        switch error.category {
        case .networkUnreachable: return "wifi.exclamationmark"
        case .authenticationFailed: return "key.slash.fill"
        case .tlsCertificate: return "lock.trianglebadge.exclamationmark.fill"
        case .portConflict: return "arrow.left.arrow.right.circle.fill"
        case .vaultCryptoOrCorruption: return "externaldrive.badge.exclamationmark"
        case .importExportFormat: return "doc.badge.gearshape.fill"
        case .cloudSync: return "icloud.slash.fill"
        case .validation: return "exclamationmark.circle.fill"
        case .internal: return "ladybug.fill"
        }
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: categorySymbol)
                    .font(.system(size: 26))
                    .foregroundStyle(Color(nsColor: .systemOrange))
                VStack(alignment: .leading, spacing: 2) {
                    Text(error.title)
                        .font(.headline)
                    Text(error.userMessage)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }

            if !error.remediationHint.isEmpty {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "lightbulb.fill")
                        .foregroundStyle(Color(nsColor: .systemYellow))
                    Text(error.remediationHint)
                        .font(.callout)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(Color.primary.opacity(0.05))
                )
            }

            if !error.technicalDetails.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Technical Diagnostics")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    ScrollView {
                        Text(error.technicalDetails)
                            .font(.system(size: 11, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 110)
                    .padding(8)
                    .background(
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .fill(Color.black.opacity(0.06))
                    )
                }
            }

            Divider()

            HStack(spacing: 10) {
                Button(store.strings.copyDiagnostics) {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(error.formatDiagnosticReport(), forType: .string)
                }

                if let connId = error.recoveryConnectionId,
                   let conn = store.activeConnections.first(where: { $0.id == connId }) {
                    Button(store.strings.editConnection) {
                        store.activeAppError = nil
                        store.editingConnection = conn
                        dismiss()
                    }
                }

                Spacer()

                Button("OK") {
                    store.activeAppError = nil
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 520)
    }
}
