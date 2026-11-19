import Foundation
import NexusTSCore
import SwiftUI

public struct SerialConsoleView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let tab: WorkspaceSessionTab

    @State private var discoveredPorts: [DiscoveredSerialPort] = []
    @State private var selectedDevicePath: String = "/dev/cu.usbserial"
    @State private var isEditingCustomDevicePath: Bool = false
    @State private var baudRate: Int = 115200
    @State private var dataBits: Int = 8
    @State private var stopBits: Int = 1
    @State private var parity: String = "NONE"
    @State private var flowControl: String = "NONE"
    @State private var lineEnding: String = "CRLF"
    @State private var dtrEnabled: Bool = true
    @State private var rtsEnabled: Bool = true
    @State private var hexDisplayMode: Bool = false

    @State private var serialSession = MacSerialPortSession()
    @State private var isOpen: Bool = false
    @State private var consoleLines: [String] = []
    @State private var rxPartialBuffer = Data()
    @State private var outboundLine: String = ""
    @State private var statusBanner: String = "Ready — Select IOKit /dev/cu.* UART Adapter"

    private let readTimer = Timer.publish(every: 1.0 / 30.0, on: .main, in: .common).autoconnect()

    private static let timestampFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    private var resolvedLineEndingBytes: String {
        switch lineEnding.uppercased() {
        case "CR": return "\r"
        case "LF": return "\n"
        default:   return "\r\n"
        }
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Circle()
                    .fill(isOpen ? Color(nsColor: .systemGreen) : Color(nsColor: .systemOrange))
                    .frame(width: 8, height: 8)

                Picker("Port", selection: $selectedDevicePath) {
                    if !discoveredPorts.contains(where: { $0.calloutPath == selectedDevicePath }) && !selectedDevicePath.isEmpty {
                        Text("\(selectedDevicePath) (Custom)").tag(selectedDevicePath)
                    }
                    ForEach(discoveredPorts) { port in
                        Text("\(port.calloutPath) (\(port.driverKind))").tag(port.calloutPath)
                    }
                }
                .frame(maxWidth: 250)
                .controlSize(.small)

                Button {
                    isEditingCustomDevicePath = true
                } label: {
                    Image(systemName: "pencil")
                }
                .controlSize(.small)
                .discreetTooltip("Enter Custom /dev/cu.* or /dev/ttys* Path")
                .popover(isPresented: $isEditingCustomDevicePath) {
                    HStack(spacing: 8) {
                        TextField("/dev/cu.usbserial", text: $selectedDevicePath)
                            .textFieldStyle(.roundedBorder)
                            .font(.system(size: 12, design: .monospaced))
                            .frame(width: 220)
                            .onSubmit {
                                isEditingCustomDevicePath = false
                            }
                        Button("Done") {
                            isEditingCustomDevicePath = false
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                    }
                    .padding(10)
                }

                Button {
                    refreshSerialDevices()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .controlSize(.small)
                .discreetTooltip("Rescan IOKit USB-Serial Devices")

                Picker("Baud", selection: $baudRate) {
                    ForEach(MacSerialPortSession.supportedBaudRates, id: \.self) { rate in
                        Text("\(rate)").tag(rate)
                    }
                }
                .frame(width: 112)
                .controlSize(.small)

                Picker("EOL", selection: $lineEnding) {
                    Text("CRLF").tag("CRLF")
                    Text("LF").tag("LF")
                    Text("CR").tag("CR")
                }
                .frame(width: 92)
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
                    consoleLines.append("[\(Self.timestampFormatter.string(from: Date()))] [TX BREAK 250ms]")
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
                TextField(
                    hexDisplayMode
                        ? "Send hex bytes (e.g. 01 03 00 00)…"
                        : "Send serial command (\(lineEnding))…",
                    text: $outboundLine
                )
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

                HStack(spacing: 4) {
                    Button("Ctrl+C") {
                        serialSession.write(Data([0x03]))
                    }
                    .discreetTooltip("Send ETX (0x03 / SIGINT)", shortcut: "⌃C")
                    Button("ESC") {
                        serialSession.write(Data([0x1B]))
                    }
                    .discreetTooltip("Send Escape (0x1B)", shortcut: "⎋")
                    Button("TAB") {
                        serialSession.write(Data([0x09]))
                    }
                    .discreetTooltip("Send Tab Completion (0x09)", shortcut: "⇥")
                }
                .controlSize(.mini)
                .disabled(!isOpen)

                Button("Clear") {
                    consoleLines.removeAll()
                    rxPartialBuffer.removeAll(keepingCapacity: true)
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
                let hint = cfg.devicePathHint.trimmingCharacters(in: .whitespacesAndNewlines)
                if !hint.isEmpty {
                    selectedDevicePath = hint
                } else if !tab.connection.host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    selectedDevicePath = tab.connection.host
                }
                baudRate = cfg.baudRate
                dataBits = cfg.dataBits
                stopBits = cfg.stopBits
                parity = cfg.parity
                flowControl = cfg.flowControl
                lineEnding = cfg.lineEnding.uppercased()
                dtrEnabled = cfg.dtrEnabled
                rtsEnabled = cfg.rtsEnabled
            }
            refreshSerialDevices()
        }
        .onDisappear {
            flushPartialRxBuffer()
            serialSession.close()
        }
        .onReceive(readTimer) { _ in
            guard isOpen else { return }
            let incoming = serialSession.drainAvailableBytes()
            guard !incoming.isEmpty else { return }
            let ts = Self.timestampFormatter.string(from: Date())
            if hexDisplayMode {
                let hex = incoming.map { String(format: "%02X", $0) }.joined(separator: " ")
                consoleLines.append("[\(ts)] RX  \(hex)")
            } else {
                rxPartialBuffer.append(incoming)
                drainCompleteLines(timestamp: ts)
            }
        }
    }

    private static func stripAnsiEscapeCodes(_ raw: String) -> String {
        guard raw.contains("\u{1B}") else { return raw }
        return raw.replacingOccurrences(
            of: "\\x1B\\[[0-9;?]*[A-Za-z]",
            with: "",
            options: .regularExpression
        )
    }

    private func drainCompleteLines(timestamp: String) {
        var cursor = 0
        let bytes = Array(rxPartialBuffer)
        let total = bytes.count
        var lineStart = 0

        while cursor < total {
            let b = bytes[cursor]
            if b == 0x0D || b == 0x0A {
                let slice = bytes[lineStart..<cursor]
                let text = Self.stripAnsiEscapeCodes(String(decoding: slice, as: UTF8.self))
                consoleLines.append("[\(timestamp)] \(text)")
                if b == 0x0D && (cursor + 1) < total && bytes[cursor + 1] == 0x0A {
                    cursor += 2
                } else {
                    cursor += 1
                }
                lineStart = cursor
            } else {
                cursor += 1
            }
        }

        if lineStart > 0 {
            rxPartialBuffer = Data(bytes[lineStart..<total])
        }
    }

    private func flushPartialRxBuffer() {
        guard !rxPartialBuffer.isEmpty else { return }
        let ts = Self.timestampFormatter.string(from: Date())
        let tail = Self.stripAnsiEscapeCodes(String(decoding: rxPartialBuffer, as: UTF8.self))
        rxPartialBuffer.removeAll(keepingCapacity: true)
        if !tail.isEmpty {
            consoleLines.append("[\(ts)] \(tail)")
        }
    }

    private func refreshSerialDevices() {
        let ports = MacSerialPortSession.discoverSerialPorts()
        discoveredPorts = ports
        let trimmedCurrent = selectedDevicePath.trimmingCharacters(in: .whitespacesAndNewlines)
        if (trimmedCurrent.isEmpty || trimmedCurrent == "/dev/cu.usbserial"),
           let first = ports.first,
           !ports.contains(where: { $0.calloutPath == trimmedCurrent }) {
            selectedDevicePath = first.calloutPath
        }
    }

    private func toggleConnection() {
        if isOpen {
            flushPartialRxBuffer()
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
            cfg.lineEnding = lineEnding
            cfg.dtrEnabled = dtrEnabled
            cfg.rtsEnabled = rtsEnabled

            let parityLetter: String = {
                switch parity.uppercased() {
                case "EVEN": return "E"
                case "ODD":  return "O"
                default:     return "N"
                }
            }()

            do {
                try serialSession.open(settings: cfg)
                isOpen = true
                statusBanner = "Connected \(selectedDevicePath) @ \(baudRate) \(dataBits)\(parityLetter)\(stopBits) (\(lineEnding))"
                consoleLines.append("● Opened \(selectedDevicePath) @ \(baudRate) \(dataBits)\(parityLetter)\(stopBits) (\(lineEnding))")
            } catch {
                statusBanner = error.localizedDescription
                consoleLines.append("✖ \(error.localizedDescription)")
            }
        }
    }

    private func transmitInput() {
        guard isOpen, !outboundLine.isEmpty else { return }
        let ts = Self.timestampFormatter.string(from: Date())
        if hexDisplayMode {
            let clean = outboundLine.split(whereSeparator: \.isWhitespace)
            let bytes = clean.compactMap { UInt8($0, radix: 16) }
            serialSession.write(Data(bytes))
            consoleLines.append("[\(ts)] TX  \(bytes.map { String(format: "%02X", $0) }.joined(separator: " "))")
        } else {
            let payload = Data((outboundLine + resolvedLineEndingBytes).utf8)
            serialSession.write(payload)
            consoleLines.append("[\(ts)] › \(outboundLine)")
        }
        outboundLine = ""
    }
}
