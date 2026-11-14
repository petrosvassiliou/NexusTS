import AppKit
import NexusTSCore
import SwiftUI
import UniformTypeIdentifiers

public struct TerminalSessionView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let tab: WorkspaceSessionTab

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    public var body: some View {
        TerminalLiveWorkspaceBody(
            tab: tab,
            controller: store.terminalController(for: tab)
        )
    }
}

private struct TerminalLiveWorkspaceBody: View {
    @EnvironmentObject private var store: WorkspaceStore
    let tab: WorkspaceSessionTab
    @ObservedObject var controller: LiveTerminalController

    @State private var isShowingPortForwardsPopover = false
    @State private var newFwdKind: PortForwardKind = .LOCAL
    @State private var newFwdBindPort: Int = 8080
    @State private var newFwdTargetHost: String = "127.0.0.1"
    @State private var newFwdTargetPort: Int = 80
    @State private var forwardErrorText: String? = nil

    @State private var castExportDoc: ExportedCastFile? = nil
    @State private var isExportingCast = false
    @State private var fontScaleOffset: Double = 0.0

    private var effectiveFontSize: Double {
        min(28, max(9, store.terminalFontSize + fontScaleOffset))
    }

    var body: some View {
        VStack(spacing: 0) {
            terminalSessionHeaderBar
            Divider()

            GeometryReader { geo in
                let charW = max(6.0, effectiveFontSize * 0.61)
                let lineH = max(12.0, effectiveFontSize * 1.32)
                let cols = max(24, Int((geo.size.width - 28) / charW))
                let rows = max(8, Int((geo.size.height - 20) / lineH))

                ZStack(alignment: .topLeading) {
                    Color(red: 0.075, green: 0.085, blue: 0.115)

                    ScrollViewReader { proxy in
                        ScrollView(.vertical) {
                            LazyVStack(alignment: .leading, spacing: 0) {
                                ForEach(Array(controller.snapshot.scrollbackLines.enumerated()), id: \.offset) { idx, line in
                                    Text(line.isEmpty ? " " : line)
                                        .font(.system(size: effectiveFontSize, weight: .regular, design: .monospaced))
                                        .foregroundStyle(Color(white: 0.72))
                                        .textSelection(.enabled)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .frame(height: lineH)
                                        .id("sb-\(idx)")
                                }

                                ForEach(Array(controller.snapshot.visibleLines.enumerated()), id: \.offset) { rowIdx, line in
                                    TerminalRowLineView(
                                        line: line,
                                        rowIndex: rowIdx,
                                        cursorCol: controller.snapshot.cursorCol,
                                        isCursorRow: controller.snapshot.cursorVisible && controller.snapshot.cursorRow == rowIdx,
                                        fontSize: effectiveFontSize,
                                        lineHeight: lineH
                                    )
                                    .id("vis-\(rowIdx)")
                                }
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                        }
                        .onChange(of: controller.snapshot.cursorRow) { _, newRow in
                            proxy.scrollTo("vis-\(newRow)", anchor: .bottom)
                        }
                        .onChange(of: controller.snapshot.visibleLines) { _, _ in
                            proxy.scrollTo("vis-\(max(0, controller.snapshot.cursorRow))", anchor: .bottom)
                        }
                    }

                    MacHardwareTerminalKeyResponder(
                        onSendBytes: { bytes in
                            controller.sendBytes(bytes)
                            store.broadcastTerminalInput(bytes, originatingTabId: tab.id)
                        },
                        onPasteString: { pasted in
                            controller.sendText(pasted, bracketedIfActive: true)
                            if store.broadcastInputEnabled {
                                store.broadcastTerminalInput(Data(pasted.utf8), originatingTabId: tab.id)
                            }
                        },
                        onCopyScreenText: {
                            let combined = (controller.snapshot.scrollbackLines + controller.snapshot.visibleLines)
                                .joined(separator: "\n")
                                .trimmingCharacters(in: .whitespacesAndNewlines)
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(combined, forType: .string)
                        }
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .onAppear {
                    controller.resizeGrid(cols: cols, rows: rows)
                }
                .onChange(of: geo.size) { _, newSize in
                    let nextCols = max(24, Int((newSize.width - 28) / charW))
                    let nextRows = max(8, Int((newSize.height - 20) / lineH))
                    controller.resizeGrid(cols: nextCols, rows: nextRows)
                }
            }
        }
        .fileExporter(
            isPresented: $isExportingCast,
            document: castExportDoc,
            contentType: .json,
            defaultFilename: "\(tab.title.replacingOccurrences(of: " ", with: "-")).cast"
        ) { _ in
            castExportDoc = nil
        }
    }

    private var terminalSessionHeaderBar: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(controller.isConnected ? Color(nsColor: .systemGreen) : Color(nsColor: .systemOrange))
                .frame(width: 8, height: 8)

            Text(controller.statusBanner)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)

            if !controller.snapshot.title.isEmpty {
                Text("• \(controller.snapshot.title)")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
            }

            Spacer()

            Text("\(controller.snapshot.cols)×\(controller.snapshot.rows)")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.secondary)

            if !tab.isLocalShell {
                Button {
                    isShowingPortForwardsPopover.toggle()
                } label: {
                    Label(
                        controller.activeForwardsCount > 0 ? "Tunnels (\(controller.activeForwardsCount))" : "Tunnels",
                        systemImage: "arrow.left.arrow.right.circle"
                    )
                    .font(.caption)
                }
                .controlSize(.small)
                .popover(isPresented: $isShowingPortForwardsPopover, arrowEdge: .bottom) {
                    sshPortForwardPopover
                }
            }

            Button {
                if let exported = controller.toggleRecording() {
                    castExportDoc = ExportedCastFile(content: exported)
                    isExportingCast = true
                }
            } label: {
                Label(
                    controller.isRecordingCast ? "Stop & Export .cast" : "Record .cast",
                    systemImage: controller.isRecordingCast ? "record.circle.fill" : "record.circle"
                )
                .font(.caption)
                .foregroundStyle(controller.isRecordingCast ? Color(nsColor: .systemRed) : .primary)
            }
            .controlSize(.small)

            HStack(spacing: 2) {
                Button {
                    fontScaleOffset = max(-4, fontScaleOffset - 1)
                } label: {
                    Image(systemName: "textformat.size.smaller")
                }
                .buttonStyle(.borderless)
                .help("Decrease Terminal Font Size")

                Button {
                    fontScaleOffset = 0
                } label: {
                    Text("\(Int(effectiveFontSize))pt")
                        .font(.caption2.monospacedDigit())
                }
                .buttonStyle(.borderless)

                Button {
                    fontScaleOffset = min(12, fontScaleOffset + 1)
                } label: {
                    Image(systemName: "textformat.size.larger")
                }
                .buttonStyle(.borderless)
                .help("Increase Terminal Font Size")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.bar)
    }

    private var sshPortForwardPopover: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Live SSH Port Forwarding")
                .font(.headline)

            Picker("Forward Type", selection: $newFwdKind) {
                Text("Local (-L)").tag(PortForwardKind.LOCAL)
                Text("Dynamic SOCKS5 (-D)").tag(PortForwardKind.DYNAMIC_SOCKS5)
            }
            .pickerStyle(.segmented)

            HStack {
                Text("Local Port:")
                    .font(.caption)
                TextField("8080", value: $newFwdBindPort, format: .number)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 80)
            }

            if newFwdKind == .LOCAL {
                HStack {
                    Text("Target:")
                        .font(.caption)
                    TextField("127.0.0.1", text: $newFwdTargetHost)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 130)
                    Text(":")
                    TextField("80", value: $newFwdTargetPort, format: .number)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 68)
                }
            }

            if let err = forwardErrorText {
                Text(err)
                    .font(.caption2)
                    .foregroundStyle(Color(nsColor: .systemRed))
            }

            HStack {
                Spacer()
                Button("Bind Tunnel") {
                    let rule = SshPortForwardRule(
                        kind: newFwdKind,
                        bindAddress: "127.0.0.1",
                        bindPort: newFwdBindPort,
                        targetHost: newFwdTargetHost,
                        targetPort: newFwdTargetPort,
                        enabled: true
                    )
                    do {
                        try controller.bindAdditionalForward(rule)
                        forwardErrorText = nil
                        isShowingPortForwardsPopover = false
                    } catch {
                        forwardErrorText = error.localizedDescription
                    }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
        }
        .padding(14)
        .frame(width: 310)
    }
}

private struct TerminalRowLineView: View {
    let line: String
    let rowIndex: Int
    let cursorCol: Int
    let isCursorRow: Bool
    let fontSize: Double
    let lineHeight: Double

    var body: some View {
        ZStack(alignment: .leading) {
            Text(line.isEmpty ? " " : line)
                .font(.system(size: fontSize, weight: .regular, design: .monospaced))
                .foregroundStyle(Color(red: 0.90, green: 0.93, blue: 0.98))
                .frame(maxWidth: .infinity, alignment: .leading)

            if isCursorRow {
                let charWidth = max(6.0, fontSize * 0.602)
                Rectangle()
                    .fill(Color.accentColor.opacity(0.72))
                    .frame(width: charWidth, height: lineHeight - 2)
                    .offset(x: CGFloat(max(0, cursorCol)) * charWidth)
            }
        }
        .frame(height: lineHeight)
    }
}

// MARK: - AppKit NSView Hardware Keyboard Responder for VT100 / xterm-256color Input

private struct MacHardwareTerminalKeyResponder: NSViewRepresentable {
    let onSendBytes: (Data) -> Void
    let onPasteString: (String) -> Void
    let onCopyScreenText: () -> Void

    func makeNSView(context: Context) -> TerminalKeyCaptureNSView {
        let view = TerminalKeyCaptureNSView()
        view.onSendBytes = onSendBytes
        view.onPasteString = onPasteString
        view.onCopyScreenText = onCopyScreenText
        return view
    }

    func updateNSView(_ nsView: TerminalKeyCaptureNSView, context: Context) {
        nsView.onSendBytes = onSendBytes
        nsView.onPasteString = onPasteString
        nsView.onCopyScreenText = onCopyScreenText
    }
}

final class TerminalKeyCaptureNSView: NSView {
    var onSendBytes: ((Data) -> Void)?
    var onPasteString: ((String) -> Void)?
    var onCopyScreenText: (() -> Void)?

    override var acceptsFirstResponder: Bool { true }

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        super.mouseDown(with: event)
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        DispatchQueue.main.async { [weak self] in
            guard let self, let win = self.window else { return }
            win.makeFirstResponder(self)
        }
    }

    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        if flags == .command, let chars = event.charactersIgnoringModifiers?.lowercased() {
            if chars == "v" {
                if let str = NSPasteboard.general.string(forType: .string), !str.isEmpty {
                    onPasteString?(str)
                }
                return true
            } else if chars == "c" {
                onCopyScreenText?()
                return true
            }
        }
        return super.performKeyEquivalent(with: event)
    }

    override func keyDown(with event: NSEvent) {
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)

        // Special Function & Navigation Keys
        switch event.keyCode {
        case 36, 76: // Return / Enter
            onSendBytes?(Data([0x0D]))
            return
        case 51: // Backspace / Delete
            onSendBytes?(Data([0x7F]))
            return
        case 117: // Forward Delete
            onSendBytes?(Data("\u{1B}[3~".utf8))
            return
        case 48: // Tab
            if flags.contains(.shift) {
                onSendBytes?(Data("\u{1B}[Z".utf8))
            } else {
                onSendBytes?(Data([0x09]))
            }
            return
        case 53: // Escape
            onSendBytes?(Data([0x1B]))
            return
        case 126: // Up Arrow
            onSendBytes?(Data("\u{1B}[A".utf8))
            return
        case 125: // Down Arrow
            onSendBytes?(Data("\u{1B}[B".utf8))
            return
        case 124: // Right Arrow
            onSendBytes?(Data("\u{1B}[C".utf8))
            return
        case 123: // Left Arrow
            onSendBytes?(Data("\u{1B}[D".utf8))
            return
        case 115: // Home
            onSendBytes?(Data("\u{1B}[H".utf8))
            return
        case 119: // End
            onSendBytes?(Data("\u{1B}[F".utf8))
            return
        case 116: // Page Up
            onSendBytes?(Data("\u{1B}[5~".utf8))
            return
        case 121: // Page Down
            onSendBytes?(Data("\u{1B}[6~".utf8))
            return
        default:
            break
        }

        // Control+A .. Control+Z (0x01..0x1A)
        if flags.contains(.control),
           let raw = event.charactersIgnoringModifiers?.lowercased(),
           let scalar = raw.unicodeScalars.first {
            let v = scalar.value
            if v >= 97 && v <= 122 {
                onSendBytes?(Data([UInt8(v - 96)]))
                return
            } else if v == 91 { // Ctrl+[
                onSendBytes?(Data([0x1B]))
                return
            } else if v == 99 { // Ctrl+C
                onSendBytes?(Data([0x03]))
                return
            }
        }

        if let chars = event.characters, !chars.isEmpty {
            if flags.contains(.option) {
                // Meta/Alt ESC prefix for emacs/readline navigation on macOS
                onSendBytes?(Data("\u{1B}\(chars)".utf8))
            } else {
                onSendBytes?(Data(chars.utf8))
            }
        }
    }
}

private struct ExportedCastFile: FileDocument {
    static var readableContentTypes: [UTType] { [.json, .plainText] }
    var content: String

    init(content: String) {
        self.content = content
    }

    init(configuration: ReadConfiguration) throws {
        self.content = configuration.file.regularFileContents.flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(content.utf8))
    }
}
