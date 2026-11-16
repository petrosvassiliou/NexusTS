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
            podOrContainerName: latestConnection.host
        )
    }

    public var body: some View {
        let resolved = PropertyInheritanceResolver.resolve(connection: latestConnection, in: store.document)
        let bearer = tab.adHocPasswordOverride ?? resolved.effectiveCredential?.secretPassword ?? ""

        VStack(spacing: 0) {
            HStack(spacing: 12) {
                ProtocolSquircleIcon(protocolType: latestConnection.protocolType, size: 22)
                Text("\(latestConnection.name) • \(containerSettings.clusterOrSocketEndpoint)")
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
                    refreshData(bearer: bearer)
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
                    Text(logsOutput.isEmpty ? "No container log lines fetched yet. Click Refresh or verify socket/API endpoint." : logsOutput)
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
                        settings: containerSettings
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
            refreshData(bearer: bearer)
        }
    }

    private func refreshData(bearer: String) {
        isLoading = true
        let proto = latestConnection.protocolType
        let cfg = containerSettings
        Task {
            do {
                async let fetchedLogs = ContainerWorkloadClient.fetchLogs(protocolType: proto, settings: cfg, bearerToken: bearer)
                async let fetchedList = ContainerWorkloadClient.listWorkloads(protocolType: proto, settings: cfg, bearerToken: bearer)
                let (l, w) = try await (fetchedLogs, fetchedList)
                logsOutput = l
                workloads = w
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
    @State private var statusMessage: String = "Ready to query hypervisor API"

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    private var latestConnection: VaultConnection {
        store.activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                ProtocolSquircleIcon(protocolType: latestConnection.protocolType, size: 22)
                Text("\(latestConnection.protocolType.displayName) • \(latestConnection.name)")
                    .font(.callout.weight(.semibold))

                Spacer()

                if let selectedId = selectedVmId,
                   let vm = inventory.first(where: { $0.id == selectedId }) {
                    Button {
                        let child = VaultConnection(
                            name: vm.name,
                            host: vm.addressOrNode.isEmpty ? latestConnection.host : vm.addressOrNode,
                            protocolType: vm.consoleProtocol,
                            osFamily: vm.consoleProtocol == .RDP ? "Windows" : "Linux"
                        )
                        store.openConnection(child, inSplit: .horizontal)
                    } label: {
                        Label("Open Console in Split Pane", systemImage: "rectangle.split.2x1")
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

            Divider()

            if inventory.isEmpty && !isLoading {
                ContentUnavailableView {
                    Label("\(latestConnection.protocolType.displayName) Inventory", systemImage: latestConnection.protocolType.sfSymbolName)
                } description: {
                    Text(statusMessage)
                } actions: {
                    Button("Query Live VM Inventory") {
                        loadInventory()
                    }
                    .buttonStyle(.borderedProminent)
                }
            } else {
                Table(inventory, selection: $selectedVmId) {
                    TableColumn("Instance / VM") { vm in
                        Text(vm.name).font(.body.weight(.medium))
                    }
                    TableColumn("State") { vm in
                        EnvironmentCapsuleBadge(vm.powerState)
                    }
                    .width(120)
                    TableColumn("Node / IP") { vm in
                        Text(vm.addressOrNode)
                            .font(.system(.caption, design: .monospaced))
                    }
                    TableColumn("Compute") { vm in
                        Text("\(vm.cpuSummary) • \(vm.memorySummary)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .onAppear {
            loadInventory()
        }
    }

    private func loadInventory() {
        isLoading = true
        let conn = latestConnection
        statusMessage = "Querying \(conn.protocolType.displayName)…"
        let resolved = PropertyInheritanceResolver.resolve(connection: conn, in: store.document)
        var cred = resolved.effectiveCredential
        if tab.adHocUsernameOverride != nil || tab.adHocPasswordOverride != nil || tab.adHocDomainOverride != nil {
            var copy = cred ?? VaultCredential(name: "Ad-Hoc")
            if let u = tab.adHocUsernameOverride, !u.isEmpty { copy.username = u }
            if let p = tab.adHocPasswordOverride, !p.isEmpty { copy.secretPassword = p }
            if let d = tab.adHocDomainOverride, !d.isEmpty { copy.domain = d }
            cred = copy
        }
        Task {
            do {
                let items = try await CloudHypervisorClient.fetchInventory(connection: conn, credential: cred)
                inventory = items
                statusMessage = items.isEmpty
                    ? "Connected — 0 virtual machines returned or credentials required."
                    : "Discovered \(items.count) virtual machine(s)."
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
        case ping = "TCP Ping"
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
                let candidatePorts: [UInt16] = [443, 80, 22, 3389]
                for seq in 1...count {
                    var matchedPort: UInt16? = nil
                    var matchedRtt: Int64 = -1
                    for p in candidatePorts {
                        let rtt = NativeNetworkDiagnostics.tcpPingMs(host: host, port: p, timeoutMs: 750)
                        if rtt >= 0 {
                            matchedPort = p
                            matchedRtt = rtt
                            break
                        }
                    }
                    if let p = matchedPort {
                        results.append("seq=\(seq)  host=\(host):\(p)  rtt=\(matchedRtt) ms  status=OPEN")
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
