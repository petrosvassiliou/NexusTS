import Foundation
import NexusTSCore
import SwiftUI

public struct SerialConsoleView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let tab: WorkspaceSessionTab

    @State private var discoveredPorts: [DiscoveredSerialPort] = []
    @State private var selectedDevicePath: String = "/dev/cu.usbserial"
    @State private var baudRate: Int = 115200
    @State private var dataBits: Int = 8
    @State private var stopBits: Int = 1
    @State private var parity: String = "NONE"
    @State private var flowControl: String = "NONE"
    @State private var dtrEnabled: Bool = true
    @State private var rtsEnabled: Bool = true
    @State private var hexDisplayMode: Bool = false

    @State private var serialSession = MacSerialPortSession()
    @State private var isOpen: Bool = false
    @State private var consoleLines: [String] = []
    @State private var outboundLine: String = ""
    @State private var statusBanner: String = "Ready — Select IOKit /dev/cu.* UART Adapter"

    private let readTimer = Timer.publish(every: 1.0 / 30.0, on: .main, in: .common).autoconnect()

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Circle()
                    .fill(isOpen ? Color(nsColor: .systemGreen) : Color(nsColor: .systemOrange))
                    .frame(width: 8, height: 8)

                Picker("Port", selection: $selectedDevicePath) {
                    if discoveredPorts.isEmpty {
                        Text(selectedDevicePath).tag(selectedDevicePath)
                    } else {
                        ForEach(discoveredPorts) { port in
                            Text("\(port.calloutPath) (\(port.driverKind))").tag(port.calloutPath)
                        }
                    }
                }
                .frame(maxWidth: 260)
                .controlSize(.small)

                Button {
                    refreshSerialDevices()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .controlSize(.small)
                .help("Rescan IOKit USB-Serial Devices")

                Picker("Baud", selection: $baudRate) {
                    ForEach(MacSerialPortSession.supportedBaudRates, id: \.self) { rate in
                        Text("\(rate)").tag(rate)
                    }
                }
                .frame(width: 115)
                .controlSize(.small)

                Toggle("DTR", isOn: $dtrEnabled)
                    .toggleStyle(.checkbox)
                    .font(.caption)
                    .onChange(of: dtrEnabled) { _, newVal in
                        serialSession.setControlLines(dtr: newVal, rts: rtsEnabled)
                    }

                Toggle("RTS", isOn: $rtsEnabled)
                    .toggleStyle(.checkbox)
                    .font(.caption)
                    .onChange(of: rtsEnabled) { _, newVal in
                        serialSession.setControlLines(dtr: dtrEnabled, rts: newVal)
                    }

                Toggle("HEX", isOn: $hexDisplayMode)
                    .toggleStyle(.checkbox)
                    .font(.caption)

                Button("BREAK") {
                    serialSession.sendBreak()
                    consoleLines.append("[TX BREAK 250ms]")
                }
                .controlSize(.small)
                .disabled(!isOpen)

                Spacer()

                Button(isOpen ? "Disconnect" : "Open UART") {
                    toggleConnection()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.bar)

            Divider()

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(consoleLines.enumerated()), id: \.offset) { idx, line in
                            Text(line)
                                .font(.system(size: store.terminalFontSize, design: .monospaced))
                                .foregroundStyle(Color(red: 0.55, green: 0.96, blue: 0.68))
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .id(idx)
                        }
                    }
                    .padding(12)
                }
                .background(Color(red: 0.06, green: 0.08, blue: 0.07))
                .onChange(of: consoleLines.count) { _, newCount in
                    if newCount > 0 {
                        proxy.scrollTo(newCount - 1, anchor: .bottom)
                    }
                }
            }

            Divider()

            HStack(spacing: 8) {
                TextField(hexDisplayMode ? "Send hex bytes (e.g. 01 03 00 00)…" : "Send serial command (CRLF)…", text: $outboundLine)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 12, design: .monospaced))
                    .onSubmit {
                        transmitInput()
                    }

                Button("Send") {
                    transmitInput()
                }
                .controlSize(.small)
                .disabled(!isOpen || outboundLine.isEmpty)

                Button("Clear") {
                    consoleLines.removeAll()
                }
                .controlSize(.small)

                Spacer()

                Text(statusBanner)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.bar)
        }
        .onAppear {
            if case .serialUart(let cfg) = tab.connection.settings {
                selectedDevicePath = cfg.devicePathHint
                baudRate = cfg.baudRate
                dataBits = cfg.dataBits
                stopBits = cfg.stopBits
                parity = cfg.parity
                flowControl = cfg.flowControl
                dtrEnabled = cfg.dtrEnabled
                rtsEnabled = cfg.rtsEnabled
            }
            refreshSerialDevices()
        }
        .onDisappear {
            serialSession.close()
        }
        .onReceive(readTimer) { _ in
            guard isOpen else { return }
            let incoming = serialSession.drainAvailableBytes()
            guard !incoming.isEmpty else { return }
            if hexDisplayMode {
                let hex = incoming.map { String(format: "%02X", $0) }.joined(separator: " ")
                consoleLines.append("RX  \(hex)")
            } else {
                let text = String(decoding: incoming, as: UTF8.self)
                let split = text.replacingOccurrences(of: "\r\n", with: "\n")
                    .replacingOccurrences(of: "\r", with: "\n")
                    .components(separatedBy: "\n")
                for seg in split where !seg.isEmpty {
                    consoleLines.append(seg)
                }
            }
        }
    }

    private func refreshSerialDevices() {
        let ports = MacSerialPortSession.discoverSerialPorts()
        discoveredPorts = ports
        if let first = ports.first, !ports.contains(where: { $0.calloutPath == selectedDevicePath }) {
            selectedDevicePath = first.calloutPath
        }
    }

    private func toggleConnection() {
        if isOpen {
            serialSession.close()
            isOpen = false
            statusBanner = "Closed \(selectedDevicePath)"
        } else {
            var cfg = SerialUartProtocolSettings()
            cfg.devicePathHint = selectedDevicePath
            cfg.baudRate = baudRate
            cfg.dataBits = dataBits
            cfg.stopBits = stopBits
            cfg.parity = parity
            cfg.flowControl = flowControl
            cfg.dtrEnabled = dtrEnabled
            cfg.rtsEnabled = rtsEnabled

            do {
                try serialSession.open(settings: cfg)
                isOpen = true
                statusBanner = "Connected \(selectedDevicePath) @ \(baudRate) \(dataBits)N\(stopBits)"
                consoleLines.append("● Opened \(selectedDevicePath) @ \(baudRate) baud")
            } catch {
                statusBanner = error.localizedDescription
                consoleLines.append("✖ \(error.localizedDescription)")
            }
        }
    }

    private func transmitInput() {
        guard isOpen, !outboundLine.isEmpty else { return }
        if hexDisplayMode {
            let clean = outboundLine.split(whereSeparator: \.isWhitespace)
            let bytes = clean.compactMap { UInt8($0, radix: 16) }
            serialSession.write(Data(bytes))
            consoleLines.append("TX  \(bytes.map { String(format: "%02X", $0) }.joined(separator: " "))")
        } else {
            let payload = Data((outboundLine + "\r\n").utf8)
            serialSession.write(payload)
            consoleLines.append("› \(outboundLine)")
        }
        outboundLine = ""
    }
}
