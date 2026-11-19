import Foundation
import NexusTSCore
import SwiftUI

// MARK: - 1. Kubernetes Pod & Docker Container Workload Studio

public struct ContainerWorkloadStudioView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let tab: WorkspaceSessionTab

    private enum ContainerMode: String, CaseIterable, Identifiable {
        case logs = "Live Logs"
        case workloads = "Cluster / Socket Workloads"
        case shellInfo = "Exec Command"
        var id: String { rawValue }
    }

    @State private var mode: ContainerMode = .logs
    @State private var workloads: [ContainerWorkloadSummary] = []
    @State private var selectedWorkloadId: ContainerWorkloadSummary.ID? = nil
    @State private var logsOutput: String = ""
    @State private var statusText: String = "Ready"
    @State private var isLoading: Bool = false

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    private var latestConnection: VaultConnection {
        store.activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
    }

    private var containerSettings: ContainerExecProtocolSettings {
        if case .containerExec(let s) = latestConnection.settings { return s }
        return ContainerExecProtocolSettings(
            clusterOrSocketEndpoint: latestConnection.protocolType == .DOCKER_CONTAINER
                ? "unix:///var/run/docker.sock"
                : "https://kubernetes.default.svc:6443",
            podOrContainerName: latestConnection.protocolType == .DOCKER_CONTAINER ? "" : latestConnection.host
        )
    }

    private var effectiveContainerSettings: ContainerExecProtocolSettings {
        var cfg = containerSettings
        if let selId = selectedWorkloadId,
           let item = workloads.first(where: { $0.id == selId }) {
            cfg.podOrContainerName = item.name
            if latestConnection.protocolType == .KUBERNETES_POD && !item.namespaceOrImage.isEmpty {
                cfg.namespace = item.namespaceOrImage
            }
        }
        return cfg
    }

    private func resolvedCredential() -> VaultCredential? {
        let resolved = PropertyInheritanceResolver.resolve(connection: latestConnection, in: store.document)
        var cred = resolved.effectiveCredential
        if tab.adHocUsernameOverride != nil || tab.adHocPasswordOverride != nil || tab.adHocDomainOverride != nil {
            var copy = cred ?? VaultCredential(name: "Ad-Hoc")
            if let u = tab.adHocUsernameOverride, !u.isEmpty { copy.username = u }
            if let p = tab.adHocPasswordOverride, !p.isEmpty { copy.secretPassword = p }
            if let d = tab.adHocDomainOverride, !d.isEmpty { copy.domain = d }
            cred = copy
        }
        return cred
    }

    public var body: some View {
        let cred = resolvedCredential()

        VStack(spacing: 0) {
            HStack(spacing: 12) {
                ProtocolSquircleIcon(protocolType: latestConnection.protocolType, size: 22)
                Text("\(latestConnection.name) • \(effectiveContainerSettings.clusterOrSocketEndpoint)")
                    .font(.callout.weight(.semibold))
                    .lineLimit(1)

                Spacer()

                Picker("View", selection: $mode) {
                    ForEach(ContainerMode.allCases) { m in
                        Text(m.rawValue).tag(m)
                    }
                }
                .pickerStyle(.segmented)
                .frame(width: 360)

                Button {
                    refreshData(credential: cred)
                } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
                .controlSize(.small)
                .disabled(isLoading)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.bar)

            Divider()

            switch mode {
            case .logs:
                ScrollView {
                    Text(logsOutput.isEmpty ? "No container log lines fetched yet. Select a workload or click Refresh." : logsOutput)
                        .font(.system(size: store.terminalFontSize, design: .monospaced))
                        .foregroundStyle(Color(white: 0.90))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                }
                .background(Color(red: 0.07, green: 0.08, blue: 0.11))

            case .workloads:
                Table(workloads, selection: $selectedWorkloadId) {
                    TableColumn("Workload") { item in
                        Text(item.name).font(.body.weight(.medium))
                    }
                    TableColumn(latestConnection.protocolType == .DOCKER_CONTAINER ? "OCI Image" : "Namespace") { item in
                        Text(item.namespaceOrImage)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                    TableColumn("State") { item in
                        EnvironmentCapsuleBadge(item.status)
                    }
                    .width(120)
                }

            case .shellInfo:
                VStack(alignment: .leading, spacing: 14) {
                    Label("Interactive Container Shell", systemImage: "terminal.fill")
                        .font(.title3.weight(.semibold))

                    let cliCmd = ContainerWorkloadClient.buildSafeExecCommand(
                        protocolType: latestConnection.protocolType,
                        settings: effectiveContainerSettings,
                        remoteSshHost: latestConnection.host,
                        remoteSshUser: cred?.username ?? ""
                    )

                    Text(cliCmd)
                        .font(.system(.body, design: .monospaced))
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Color(nsColor: .controlBackgroundColor)))

                    Button {
                        store.openLocalTerminal(inSplit: .horizontal)
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
                            if let lastTab = store.paneTree.allTabs.last {
                                store.terminalController(for: lastTab).sendText(cliCmd + "\r")
                            }
                        }
                    } label: {
                        Label("Launch Interactive Exec in Split macOS Shell", systemImage: "rectangle.split.2x1.fill")
                    }
                    .buttonStyle(.borderedProminent)

                    Spacer()
                }
                .padding(24)
            }

            Divider()
            HStack {
                if isLoading { ProgressView().controlSize(.small) }
                Text(statusText).font(.caption).foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 5)
            .background(.bar)
        }
        .onAppear {
            refreshData(credential: cred)
        }
        .onChange(of: selectedWorkloadId) { _, newId in
            guard newId != nil else { return }
            fetchSelectedWorkloadLogs(credential: cred)
        }
    }

    private func fetchSelectedWorkloadLogs(credential: VaultCredential?) {
        let proto = latestConnection.protocolType
        let remoteHost = latestConnection.host
        let cfg = effectiveContainerSettings
        let bearer = credential?.secretPassword ?? ""
        guard !cfg.podOrContainerName.isEmpty else { return }
        isLoading = true
        Task {
            do {
                let fetched = try await ContainerWorkloadClient.fetchLogs(
                    protocolType: proto,
                    settings: cfg,
                    bearerToken: bearer,
                    remoteHost: remoteHost,
                    sshCredential: credential
                )
                logsOutput = fetched
                statusText = "Loaded logs for \(cfg.podOrContainerName)"
            } catch {
                statusText = error.localizedDescription
            }
            isLoading = false
        }
    }

    private func refreshData(credential: VaultCredential?) {
        isLoading = true
        let proto = latestConnection.protocolType
        let remoteHost = latestConnection.host
        let baseCfg = effectiveContainerSettings
        let bearer = credential?.secretPassword ?? ""
        Task {
            do {
                let w = try await ContainerWorkloadClient.listWorkloads(
                    protocolType: proto,
                    settings: baseCfg,
                    bearerToken: bearer,
                    remoteHost: remoteHost,
                    sshCredential: credential
                )
                workloads = w
                var logCfg = baseCfg
                if logCfg.podOrContainerName.isEmpty, let first = w.first {
                    selectedWorkloadId = first.id
                    logCfg.podOrContainerName = first.name
                    if proto == .KUBERNETES_POD && !first.namespaceOrImage.isEmpty {
                        logCfg.namespace = first.namespaceOrImage
                    }
                }
                if !logCfg.podOrContainerName.isEmpty {
                    logsOutput = (try? await ContainerWorkloadClient.fetchLogs(
                        protocolType: proto,
                        settings: logCfg,
                        bearerToken: bearer,
                        remoteHost: remoteHost,
                        sshCredential: credential
                    )) ?? ""
                }
                statusText = "Loaded \(w.count) workload(s)"
            } catch {
                statusText = error.localizedDescription
            }
            isLoading = false
        }
    }
}

// MARK: - 2. Cloud & Hypervisor Inventory Session View (Proxmox, vSphere, Hyper-V WinRM, AWS EC2 SigV4, Azure)

public struct CloudHypervisorSessionView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let tab: WorkspaceSessionTab

    @State private var inventory: [CloudVmInventoryItem] = []
    @State private var selectedVmId: CloudVmInventoryItem.ID? = nil
    @State private var isLoading: Bool = false
    @State private var isRunningPowerAction: Bool = false
    @State private var statusMessage: String = "Ready to query hypervisor API"
    @State private var enhancedSessionMode: Bool = true
    @State private var manualVmGuid: String = ""
    @State private var portDiagnostics: [HyperVPortDiagnostic] = []

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    private var latestConnection: VaultConnection {
        store.activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
    }

    private var isHyperV: Bool {
        latestConnection.protocolType == .HYPER_V
    }

    private var selectedVm: CloudVmInventoryItem? {
        guard let selectedId = selectedVmId else { return nil }
        return inventory.first(where: { $0.id == selectedId })
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                ProtocolSquircleIcon(protocolType: latestConnection.protocolType, size: 22)
                VStack(alignment: .leading, spacing: 1) {
                    Text("\(latestConnection.protocolType.displayName) • \(latestConnection.name)")
                        .font(.callout.weight(.semibold))
                    if !latestConnection.host.isEmpty {
                        Text(latestConnection.host)
                            .font(.caption2.monospaced())
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                if let vm = selectedVm {
                    HStack(spacing: 4) {
                        Button {
                            performVmPower(vm: vm, action: .start)
                        } label: {
                            Label("Start", systemImage: "play.fill")
                        }
                        .help("Start VM (\(vm.name))")

                        Button {
                            performVmPower(vm: vm, action: .stop)
                        } label: {
                            Label("Turn Off", systemImage: "stop.fill")
                        }
                        .help("Stop / Power Off VM (\(vm.name))")

                        Button {
                            performVmPower(vm: vm, action: .save)
                        } label: {
                            Label(
                                isHyperV ? "Save" : (latestConnection.protocolType == .AZURE_VM ? "Deallocate" : "Suspend"),
                                systemImage: "tray.and.arrow.down.fill"
                            )
                        }
                        .help("Save / Suspend / Deallocate VM (\(vm.name))")

                        Button {
                            performVmPower(vm: vm, action: .reboot)
                        } label: {
                            Label("Restart", systemImage: "arrow.triangle.2.circlepath")
                        }
                        .help("Restart VM (\(vm.name))")
                    }
                    .controlSize(.small)
                    .disabled(isRunningPowerAction)

                    Button {
                        openVmConsole(vm: vm, inSplit: nil)
                    } label: {
                        Label(isHyperV ? "Connect VM (2179)" : "Open Console", systemImage: "display")
                    }
                    .controlSize(.small)

                    Button {
                        openVmConsole(vm: vm, inSplit: .horizontal)
                    } label: {
                        Label("Split Console", systemImage: "rectangle.split.2x1")
                    }
                    .controlSize(.small)
                }

                if isHyperV {
                    Button {
                        openHostDesktopRdp(inSplit: nil)
                    } label: {
                        Label("Host Desktop (3389)", systemImage: "pc")
                    }
                    .controlSize(.small)
                }

                Button {
                    loadInventory()
                } label: {
                    Label("Refresh Inventory", systemImage: "arrow.clockwise")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .disabled(isLoading)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.bar)

            if isHyperV {
                Divider()
                hyperVQuickConnectAndDiagnosticsBar
            }

            Divider()

            if isLoading && inventory.isEmpty {
                VStack(spacing: 12) {
                    ProgressView()
                    Text(statusMessage)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if inventory.isEmpty {
                ContentUnavailableView {
                    Label("\(latestConnection.protocolType.displayName) Inventory", systemImage: latestConnection.protocolType.sfSymbolName)
                } description: {
                    Text(statusMessage)
                } actions: {
                    HStack(spacing: 10) {
                        Button("Query Live VM Inventory") {
                            loadInventory()
                        }
                        .buttonStyle(.borderedProminent)

                        Button("Edit Connection & Credentials…") {
                            store.editingConnection = latestConnection
                        }

                        if isHyperV {
                            Button("Open Host RDP (3389)") {
                                openHostDesktopRdp(inSplit: nil)
                            }
                        }
                    }
                }
            } else {
                VStack(spacing: 0) {
                    Table(inventory, selection: $selectedVmId) {
                        TableColumn("Instance / VM") { vm in
                            Text(vm.name).font(.body.weight(.medium))
                        }
                        TableColumn("State") { vm in
                            EnvironmentCapsuleBadge(vm.powerState)
                        }
                        .width(110)
                        TableColumn(isHyperV ? "VM GUID / Host" : "Node / IP") { vm in
                            Text(isHyperV ? vm.id : vm.addressOrNode)
                                .font(.system(.caption, design: .monospaced))
                                .textSelection(.enabled)
                        }
                        TableColumn("Compute") { vm in
                            Text("\(vm.cpuSummary) • \(vm.memorySummary)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        TableColumn("Actions") { vm in
                            HStack(spacing: 6) {
                                Button(isHyperV ? "VMConnect" : "Console") {
                                    openVmConsole(vm: vm, inSplit: nil)
                                }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.mini)

                                Button("Start") {
                                    performVmPower(vm: vm, action: .start)
                                }
                                .controlSize(.mini)
                                .disabled(isRunningPowerAction || vm.powerState.uppercased().contains("RUN") || vm.powerState.uppercased().contains("ON"))

                                Button("Off") {
                                    performVmPower(vm: vm, action: .stop)
                                }
                                .controlSize(.mini)
                                .disabled(isRunningPowerAction || vm.powerState.uppercased().contains("STOP") || vm.powerState.uppercased().contains("OFF") || vm.powerState.uppercased().contains("DEALLOC"))
                            }
                        }
                        .width(195)
                    }
                    .contextMenu(forSelectionType: CloudVmInventoryItem.ID.self) { ids in
                        if let firstId = ids.first, let vm = inventory.first(where: { $0.id == firstId }) {
                            Button("Open VM Console") {
                                openVmConsole(vm: vm, inSplit: nil)
                            }
                            Button("Open VM Console in Split Pane") {
                                openVmConsole(vm: vm, inSplit: .horizontal)
                            }
                            Divider()
                            Button("Start VM") { performVmPower(vm: vm, action: .start) }
                            Button("Stop / Turn Off VM") { performVmPower(vm: vm, action: .stop) }
                            Button(isHyperV ? "Save VM State" : (latestConnection.protocolType == .AZURE_VM ? "Deallocate VM" : "Suspend VM")) {
                                performVmPower(vm: vm, action: .save)
                            }
                            Button("Restart VM") { performVmPower(vm: vm, action: .reboot) }
                            if isHyperV {
                                Divider()
                                Button("Save VM as Vault RDP Connection") {
                                    saveHyperVVmToVault(vm: vm)
                                }
                                Button("Copy VM GUID") {
                                    NSPasteboard.general.clearContents()
                                    NSPasteboard.general.setString(vm.id, forType: .string)
                                }
                            } else {
                                Divider()
                                Button("Copy VM ID") {
                                    NSPasteboard.general.clearContents()
                                    NSPasteboard.general.setString(vm.id, forType: .string)
                                }
                            }
                        }
                    } primaryAction: { ids in
                        if let firstId = ids.first, let vm = inventory.first(where: { $0.id == firstId }) {
                            openVmConsole(vm: vm, inSplit: nil)
                        }
                    }

                    Divider()
                    HStack {
                        Text(statusMessage)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                        if isHyperV {
                            Text(enhancedSessionMode ? "VMConnect Mode: Enhanced Session (VMBus)" : "VMConnect Mode: Basic Console (TCP 2179)")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 5)
                    .background(.bar)
                }
            }
        }
        .onAppear {
            loadInventory()
        }
    }

    private var hyperVQuickConnectAndDiagnosticsBar: some View {
        HStack(spacing: 10) {
            HStack(spacing: 6) {
                ForEach(portDiagnostics) { diag in
                    HStack(spacing: 4) {
                        Circle()
                            .fill(diag.isOpen ? Color(nsColor: .systemGreen) : Color.secondary.opacity(0.35))
                            .frame(width: 6, height: 6)
                        Text(verbatim: "\(diag.serviceLabel) :\(diag.port)")
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(diag.isOpen ? .primary : .secondary)
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(
                        Capsule()
                            .fill(diag.isOpen ? Color.green.opacity(0.12) : Color.secondary.opacity(0.08))
                    )
                }
            }

            Spacer(minLength: 8)

            Toggle("Enhanced Session", isOn: $enhancedSessionMode)
                .toggleStyle(.checkbox)
                .controlSize(.small)
                .font(.caption)
                .help("Request Hyper-V Enhanced Session Mode (VMBus clipboard & dynamic resize) over TCP 2179")

            TextField("Direct VM GUID (e.g. 8f2c1a40-…)", text: $manualVmGuid)
                .textFieldStyle(.roundedBorder)
                .controlSize(.small)
                .font(.system(.caption, design: .monospaced))
                .frame(width: 220)

            Button("Connect GUID (2179)") {
                let trimmed = manualVmGuid.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { return }
                let item = CloudVmInventoryItem(
                    id: trimmed,
                    name: "\(latestConnection.name) • \(trimmed.prefix(8))",
                    powerState: "RUNNING",
                    addressOrNode: latestConnection.host,
                    cpuSummary: "VMConnect 2179",
                    memorySummary: trimmed,
                    consoleProtocol: .RDP
                )
                openVmConsole(vm: item, inSplit: nil)
            }
            .controlSize(.small)
            .disabled(manualVmGuid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color(nsColor: .controlBackgroundColor).opacity(0.65))
    }

    private func resolveEffectiveCredential(for conn: VaultConnection) -> VaultCredential? {
        let resolved = PropertyInheritanceResolver.resolve(connection: conn, in: store.document)
        var cred = resolved.effectiveCredential
        if tab.adHocUsernameOverride != nil || tab.adHocPasswordOverride != nil || tab.adHocDomainOverride != nil {
            var copy = cred ?? VaultCredential(name: "Ad-Hoc")
            if let u = tab.adHocUsernameOverride, !u.isEmpty { copy.username = u }
            if let p = tab.adHocPasswordOverride, !p.isEmpty { copy.secretPassword = p }
            if let d = tab.adHocDomainOverride, !d.isEmpty { copy.domain = d }
            cred = copy
        }
        return cred
    }

    private func openVmConsole(vm: CloudVmInventoryItem, inSplit orientation: SplitOrientation?) {
        let parent = latestConnection
        if isHyperV {
            var rdpCfg = RdpProtocolSettings(port: 2179)
            rdpCfg.hyperVVmId = vm.id
            rdpCfg.hyperVEnhancedSession = enhancedSessionMode
            rdpCfg.ignoreCertificateWarnings = true
            let child = VaultConnection(
                parentFolderId: parent.parentFolderId,
                name: vm.name,
                host: parent.host,
                protocolType: .RDP,
                osFamily: "Windows",
                inheritable: parent.inheritable,
                settings: .rdp(rdpCfg)
            )
            store.openConnection(
                child,
                inSplit: orientation,
                adHocUsername: tab.adHocUsernameOverride,
                adHocPassword: tab.adHocPasswordOverride,
                adHocDomain: tab.adHocDomainOverride
            )
            return
        }

        let cloudCfg: CloudVirtualizationProtocolSettings = {
            if case .cloudVirtualization(let c) = parent.settings { return c }
            return CloudVirtualizationProtocolSettings()
        }()

        if parent.protocolType == .PROXMOX_VE {
            let base = CloudHypervisorClient.resolveProxmoxBaseUrl(
                apiEndpointUrl: cloudCfg.apiEndpointUrl,
                connectionHost: parent.host
            )
            let parts = vm.id.split(separator: "/").map(String.init)
            let node = parts.count >= 3 ? parts[0] : (vm.addressOrNode.isEmpty ? parent.host : vm.addressOrNode)
            let kind = (parts.count >= 3 && parts[1] == "lxc") ? "lxc" : "kvm"
            let numericId = parts.last ?? vm.id
            let encodedName = vm.name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? vm.name
            let consoleUrl = "\(base)/?console=\(kind)&novnc=1&vmid=\(numericId)&vmname=\(encodedName)&node=\(node)"
            let webCfg = WebPageProtocolSettings(url: consoleUrl, ignoreSelfSignedInternalCert: true)
            let child = VaultConnection(
                parentFolderId: parent.parentFolderId,
                name: "\(vm.name) (noVNC)",
                host: consoleUrl,
                protocolType: .WEB_PAGE,
                osFamily: "Linux",
                inheritable: parent.inheritable,
                settings: .webPage(webCfg)
            )
            store.openConnection(child, inSplit: orientation)
            return
        }

        if parent.protocolType == .VMWARE_VSPHERE && vm.consoleProtocol == .WEB_PAGE {
            let base = CloudHypervisorClient.resolveVsphereBaseUrl(
                apiEndpointUrl: cloudCfg.apiEndpointUrl,
                connectionHost: parent.host
            )
            let uiUrl = "\(base)/ui/#/host/vms/\(vm.id)"
            let webCfg = WebPageProtocolSettings(url: uiUrl, ignoreSelfSignedInternalCert: true)
            let child = VaultConnection(
                parentFolderId: parent.parentFolderId,
                name: "\(vm.name) (Web Console)",
                host: uiUrl,
                protocolType: .WEB_PAGE,
                osFamily: "Linux",
                inheritable: parent.inheritable,
                settings: .webPage(webCfg)
            )
            store.openConnection(child, inSplit: orientation)
            return
        }

        let targetHost = vm.addressOrNode.isEmpty ? parent.host : vm.addressOrNode
        let isCloudApiParent = parent.protocolType == .AWS_EC2 || parent.protocolType == .AZURE_VM
        let guestInheritable = isCloudApiParent
            ? InheritableProperties(credentialMode: .PROMPT_AT_CONNECT)
            : parent.inheritable
        let child = VaultConnection(
            parentFolderId: parent.parentFolderId,
            name: vm.name,
            host: targetHost,
            protocolType: vm.consoleProtocol,
            osFamily: vm.consoleProtocol == .RDP ? "Windows" : "Linux",
            inheritable: guestInheritable,
            settings: vm.consoleProtocol.defaultSettings(host: targetHost)
        )
        store.openConnection(
            child,
            inSplit: orientation,
            adHocUsername: isCloudApiParent ? nil : tab.adHocUsernameOverride,
            adHocPassword: isCloudApiParent ? nil : tab.adHocPasswordOverride,
            adHocDomain: isCloudApiParent ? nil : tab.adHocDomainOverride
        )
    }

    private func openHostDesktopRdp(inSplit orientation: SplitOrientation?) {
        let parent = latestConnection
        var rdpCfg = RdpProtocolSettings(port: 3389)
        rdpCfg.ignoreCertificateWarnings = true
        let hostConn = VaultConnection(
            parentFolderId: parent.parentFolderId,
            name: "\(parent.name) (Host Desktop)",
            host: parent.host,
            protocolType: .RDP,
            osFamily: "Windows",
            inheritable: parent.inheritable,
            settings: .rdp(rdpCfg)
        )
        store.openConnection(
            hostConn,
            inSplit: orientation,
            adHocUsername: tab.adHocUsernameOverride,
            adHocPassword: tab.adHocPasswordOverride,
            adHocDomain: tab.adHocDomainOverride
        )
    }

    private func saveHyperVVmToVault(vm: CloudVmInventoryItem) {
        let parent = latestConnection
        var rdpCfg = RdpProtocolSettings(port: 2179)
        rdpCfg.hyperVVmId = vm.id
        rdpCfg.hyperVEnhancedSession = enhancedSessionMode
        rdpCfg.hyperVEnhancedSessionCustomized = true
        rdpCfg.ignoreCertificateWarnings = true
        let saved = VaultConnection(
            parentFolderId: parent.parentFolderId,
            name: vm.name,
            host: parent.host,
            protocolType: .RDP,
            osFamily: "Windows",
            tags: ["hyper-v", "vmconnect"],
            notes: "Hyper-V VM GUID: \(vm.id) on host \(parent.host)",
            inheritable: parent.inheritable,
            settings: .rdp(rdpCfg)
        )
        store.commitMutation("Saved Hyper-V VM \(saved.name)") { doc in
            doc.connections.append(saved)
        }
        statusMessage = "Saved '\(vm.name)' (TCP 2179 VMConnect) to Vault."
    }

    private func performVmPower(vm: CloudVmInventoryItem, action: HyperVPowerAction) {
        let conn = latestConnection
        let cred = resolveEffectiveCredential(for: conn)
        isRunningPowerAction = true
        statusMessage = "Sending \(action.displayTitle) to \(vm.name)…"
        Task {
            do {
                let msg = try await CloudHypervisorClient.performCloudPowerAction(
                    connection: conn,
                    credential: cred,
                    vmId: vm.id,
                    vmName: vm.name,
                    action: action
                )
                statusMessage = msg
                try? await Task.sleep(nanoseconds: 900_000_000)
                let refreshed = try await CloudHypervisorClient.fetchInventory(connection: conn, credential: cred)
                inventory = refreshed
            } catch {
                statusMessage = error.localizedDescription
            }
            isRunningPowerAction = false
        }
    }

    private func loadInventory() {
        isLoading = true
        let conn = latestConnection
        statusMessage = "Querying \(conn.protocolType.displayName) (\(conn.host))…"
        let cred = resolveEffectiveCredential(for: conn)
        Task {
            if conn.protocolType == .HYPER_V {
                let hostForDiag = conn.host
                Task {
                    let diags = await CloudHypervisorClient.diagnoseHyperVPorts(host: hostForDiag)
                    await MainActor.run {
                        portDiagnostics = diags
                    }
                }
            }
            do {
                let items = try await CloudHypervisorClient.fetchInventory(connection: conn, credential: cred)
                inventory = items
                if selectedVmId == nil {
                    selectedVmId = items.first?.id
                }
                statusMessage = items.isEmpty
                    ? "Connected to \(conn.host) — 0 virtual machines found."
                    : "Discovered \(items.count) virtual machine(s) on \(conn.host)."
            } catch {
                statusMessage = error.localizedDescription
            }
            isLoading = false
        }
    }
}

// MARK: - 3. 6-Tool Network Diagnostics Suite (Ping, Traceroute, Port Scan, WoL, DNS, Subnet Calc)

public struct NetworkDiagnosticsSessionView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let tab: WorkspaceSessionTab

    private enum NetToolKind: String, CaseIterable, Identifiable {
        case portScan = "Port Scan"
        case traceroute = "Traceroute"
        case ping = "Ping"
        case wol = "Wake-on-LAN"
        case dns = "DNS Lookup"
        case subnet = "Subnet CIDR"
        var id: String { rawValue }
    }

    @State private var selectedTool: NetToolKind = .portScan
    @State private var targetHost: String = "127.0.0.1"
    @State private var portRangeStart: Int = 20
    @State private var portRangeEnd: Int = 1024
    @State private var macAddressInput: String = "AA:BB:CC:DD:EE:FF"
    @State private var cidrInput: String = "10.42.16.0/22"

    @State private var scanHits: [PortProbeHit] = []
    @State private var traceHops: [TracerouteHop] = []
    @State private var outputLines: [String] = []
    @State private var subnetResult: SubnetCalculationResult? = nil
    @State private var isRunning: Bool = false

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    private var latestConnection: VaultConnection {
        store.activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
    }

    private var netSettings: NetUtilityProtocolSettings {
        if case .netUtility(let cfg) = latestConnection.settings { return cfg }
        return NetUtilityProtocolSettings()
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Picker("Diagnostic Tool", selection: $selectedTool) {
                    ForEach(NetToolKind.allCases) { tool in
                        Text(tool.rawValue).tag(tool)
                    }
                }
                .pickerStyle(.segmented)

                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(.bar)

            Divider()

            HStack(spacing: 10) {
                if selectedTool == .subnet {
                    TextField("IPv4 CIDR (e.g. 10.24.0.0/22)", text: $cidrInput)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 240)
                    Button("Calculate") {
                        subnetResult = NetworkCalculators.calculateIpv4Subnet(cidrInput: cidrInput)
                    }
                    .buttonStyle(.borderedProminent)
                } else if selectedTool == .wol {
                    TextField("MAC Address (AA:BB:CC:DD:EE:FF)", text: $macAddressInput)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 240)
                    Button("Broadcast Magic Packet") {
                        dispatchWakeOnLan()
                    }
                    .buttonStyle(.borderedProminent)
                } else {
                    TextField("Hostname or IP", text: $targetHost)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 220)

                    if selectedTool == .portScan {
                        TextField("From", value: $portRangeStart, format: .number)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 68)
                        Text("–")
                        TextField("To", value: $portRangeEnd, format: .number)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 68)
                    }

                    Button(isRunning ? "Running…" : "Run \(selectedTool.rawValue)") {
                        runSelectedDiagnostic()
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isRunning || targetHost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }

                Spacer()
                if isRunning { ProgressView().controlSize(.small) }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)

            Divider()

            switch selectedTool {
            case .portScan:
                Table(scanHits) {
                    TableColumn("TCP Port") { hit in
                        Text("\(hit.port)").font(.system(.body, design: .monospaced).weight(.semibold))
                    }
                    .width(85)
                    TableColumn("Service") { hit in
                        Text(hit.guessedService)
                    }
                    .width(120)
                    TableColumn("RTT") { hit in
                        Text("\(hit.connectLatencyMs) ms").font(.caption.monospacedDigit())
                    }
                    .width(80)
                    TableColumn("Banner") { hit in
                        Text(hit.serviceBanner ?? "—")
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                }

            case .traceroute:
                Table(traceHops) {
                    TableColumn("TTL") { hop in
                        Text("\(hop.ttl)").font(.caption.monospacedDigit())
                    }
                    .width(55)
                    TableColumn("Responder") { hop in
                        Text(hop.timedOut ? "* * * (timeout)" : hop.responderAddr)
                            .font(.system(.body, design: .monospaced))
                    }
                    TableColumn("RTT") { hop in
                        Text(hop.timedOut ? "—" : String(format: "%.2f ms", hop.rttMs))
                            .font(.caption.monospacedDigit())
                    }
                    .width(100)
                }

            case .subnet:
                if let res = subnetResult ?? NetworkCalculators.calculateIpv4Subnet(cidrInput: cidrInput) {
                    Form {
                        LabeledContent("Canonical CIDR", value: res.cidrNotation)
                        LabeledContent("Network Address", value: res.networkAddress)
                        LabeledContent("Subnet Mask", value: res.subnetMask)
                        LabeledContent("Cisco Wildcard Mask", value: res.wildcardMask)
                        LabeledContent("Broadcast Address", value: res.broadcastAddress)
                        LabeledContent("Usable Host Range", value: res.usableRange)
                        LabeledContent("Usable Host Capacity", value: "\(res.usableHostCount)")
                    }
                    .formStyle(.grouped)
                }

            case .ping, .wol, .dns:
                List(Array(outputLines.enumerated()), id: \.offset) { _, line in
                    Text(line)
                        .font(.system(size: 12, design: .monospaced))
                        .textSelection(.enabled)
                }
            }
        }
        .onAppear {
            if !latestConnection.host.isEmpty {
                targetHost = latestConnection.host
            }
            let cfg = netSettings
            portRangeStart = cfg.portScanRangeStart
            portRangeEnd = cfg.portScanRangeEnd
            if !cfg.wolMacAddress.isEmpty { macAddressInput = cfg.wolMacAddress }
            switch latestConnection.protocolType {
            case .UTILITY_PING: selectedTool = .ping
            case .UTILITY_TRACEROUTE: selectedTool = .traceroute
            case .UTILITY_WAKE_ON_LAN: selectedTool = .wol
            default: selectedTool = .portScan
            }
        }
    }

    private func dispatchWakeOnLan() {
        let cfg = netSettings
        let broadcastAddr = cfg.wolBroadcastAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let sent = try NativeNetworkDiagnostics.sendWakeOnLan(
                macAddress: macAddressInput,
                broadcastIp: broadcastAddr.isEmpty ? "255.255.255.255" : broadcastAddr,
                udpPort: UInt16(clamping: cfg.wolUdpPort > 0 ? cfg.wolUdpPort : 9),
                secureOnHex: cfg.wolSecureOnHex
            )
            outputLines.insert(
                "✓ Broadcast \(sent)-byte IEEE 802.3 Magic Packet to \(macAddressInput) via \(broadcastAddr.isEmpty ? "255.255.255.255" : broadcastAddr):\(cfg.wolUdpPort)",
                at: 0
            )
        } catch {
            outputLines.insert("✖ \(error.localizedDescription)", at: 0)
        }
    }

    private func runSelectedDiagnostic() {
        let host = targetHost.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty else { return }
        let cfg = netSettings
        isRunning = true

        switch selectedTool {
        case .dns:
            Task.detached {
                let records = NetworkCalculators.resolveDnsRecords(hostname: host)
                await MainActor.run {
                    self.outputLines = records.isEmpty ? ["No A/AAAA records resolved for \(host)"] : records
                    self.isRunning = false
                }
            }

        case .traceroute:
            let maxHops = UInt8(clamping: max(1, min(64, cfg.tracerouteMaxHops)))
            Task.detached {
                let hops = NativeNetworkDiagnostics.traceRoute(
                    host: host,
                    probePort: 443,
                    maxHops: maxHops,
                    timeoutMs: 400
                )
                await MainActor.run {
                    self.traceHops = hops
                    self.isRunning = false
                }
            }

        case .ping:
            let count = max(1, min(50, cfg.pingCount))
            let intervalNs = UInt64(max(50, min(10_000, cfg.pingIntervalMs))) * 1_000_000
            Task.detached {
                var results: [String] = []
                for seq in 1...count {
                    let rtt = NativeNetworkDiagnostics.tcpPingMs(host: host, port: 0, timeoutMs: 900)
                    if rtt >= 0 {
                        results.append("seq=\(seq)  host=\(host)  rtt=\(rtt) ms  status=ALIVE")
                    } else {
                        results.append("seq=\(seq)  host=\(host)  timeout")
                    }
                    if seq < count {
                        try? await Task.sleep(nanoseconds: intervalNs)
                    }
                }
                let finalResults = results
                await MainActor.run {
                    self.outputLines = finalResults
                    self.isRunning = false
                }
            }

        case .portScan:
            let lo = max(1, min(portRangeStart, portRangeEnd))
            let hi = min(65535, max(portRangeStart, portRangeEnd))
            let concurrency = UInt32(max(1, min(512, cfg.portScanConcurrency)))
            scanHits.removeAll()
            Task.detached(priority: .userInitiated) {
                let rawHits = NativeNetworkDiagnostics.scanPorts(
                    host: host,
                    startPort: UInt16(lo),
                    endPort: UInt16(hi),
                    timeoutMs: 250,
                    concurrency: concurrency
                )
                let enrichedHits: [PortProbeHit]
                if rawHits.count <= 32 {
                    enrichedHits = rawHits.map { hit in
                        if let existingBanner = hit.serviceBanner, !existingBanner.isEmpty {
                            return hit
                        }
                        let banner = NativeNetworkDiagnostics.grabServiceBanner(
                            host: host,
                            port: hit.port,
                            timeoutMs: 220
                        )
                        return PortProbeHit(
                            port: hit.port,
                            open: hit.open,
                            connectLatencyMs: hit.connectLatencyMs,
                            guessedService: hit.guessedService,
                            serviceBanner: banner.isEmpty ? nil : banner
                        )
                    }
                } else {
                    enrichedHits = rawHits
                }
                await MainActor.run {
                    self.scanHits = enrichedHits
                    self.isRunning = false
                }
            }

        default:
            isRunning = false
        }
    }
}
