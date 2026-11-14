import NexusTSCore
import SwiftUI
import WebKit

public struct IsolatedWebSessionView: View {
    @EnvironmentObject private var store: WorkspaceStore
    public let tab: WorkspaceSessionTab

    @StateObject private var browserController = IsolatedWebController()
    @State private var urlInputText: String = "https://localhost"

    public init(tab: WorkspaceSessionTab) {
        self.tab = tab
    }

    private var webSettings: WebPageProtocolSettings {
        if case .webPage(let s) = tab.connection.settings { return s }
        let fallback = tab.connection.host.hasPrefix("http") ? tab.connection.host : "https://\(tab.connection.host)"
        return WebPageProtocolSettings(url: fallback)
    }

    public var body: some View {
        let resolved = PropertyInheritanceResolver.resolve(connection: tab.connection, in: store.document)

        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button {
                    browserController.webView?.goBack()
                } label: {
                    Image(systemName: "chevron.left")
                }
                .buttonStyle(.borderless)
                .disabled(!browserController.canGoBack)

                Button {
                    browserController.webView?.goForward()
                } label: {
                    Image(systemName: "chevron.right")
                }
                .buttonStyle(.borderless)
                .disabled(!browserController.canGoForward)

                Button {
                    browserController.webView?.reload()
                } label: {
                    Image(systemName: browserController.isLoading ? "xmark" : "arrow.clockwise")
                }
                .buttonStyle(.borderless)

                HStack(spacing: 6) {
                    Image(systemName: "lock.shield")
                        .foregroundStyle(Color(nsColor: .systemGreen))
                        .font(.caption)
                    TextField("https://", text: $urlInputText)
                        .textFieldStyle(.plain)
                        .font(.system(size: 12))
                        .onSubmit {
                            browserController.load(urlString: urlInputText)
                        }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .fill(Color(nsColor: .controlBackgroundColor))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.75)
                )

                EnvironmentCapsuleBadge("Isolated DataStore")

                Button {
                    browserController.injectCredentials(
                        settings: webSettings,
                        username: tab.adHocUsernameOverride ?? resolved.effectiveCredential?.username ?? "",
                        password: tab.adHocPasswordOverride ?? resolved.effectiveCredential?.secretPassword ?? ""
                    )
                } label: {
                    Label("Auto-Fill Vault Login", systemImage: "key.fill")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .disabled(resolved.effectiveCredential == nil && tab.adHocUsernameOverride == nil)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.bar)

            Divider()

            IsolatedWKWebViewRepresentable(
                controller: browserController,
                settings: webSettings
            )
        }
        .onAppear {
            urlInputText = webSettings.url
            browserController.onUrlChanged = { updated in
                urlInputText = updated
            }
            browserController.load(urlString: webSettings.url)
        }
    }
}

@MainActor
final class IsolatedWebController: NSObject, ObservableObject, WKNavigationDelegate {
    @Published var canGoBack: Bool = false
    @Published var canGoForward: Bool = false
    @Published var isLoading: Bool = false

    var webView: WKWebView?
    var ignoreSelfSignedCert: Bool = false
    var onUrlChanged: ((String) -> Void)?

    func load(urlString: String) {
        let normalized = urlString.contains("://") ? urlString : "https://\(urlString)"
        guard let url = URL(string: normalized) else { return }
        webView?.load(URLRequest(url: url))
    }

    func injectCredentials(settings: WebPageProtocolSettings, username: String, password: String) {
        guard let webView else { return }
        guard let userJson = try? JSONEncoder().encode(username),
              let passJson = try? JSONEncoder().encode(password),
              let uSelJson = try? JSONEncoder().encode(settings.usernameCssSelector),
              let pSelJson = try? JSONEncoder().encode(settings.passwordCssSelector),
              let subSelJson = try? JSONEncoder().encode(settings.submitCssSelector),
              let uLit = String(data: userJson, encoding: .utf8),
              let pLit = String(data: passJson, encoding: .utf8),
              let uSel = String(data: uSelJson, encoding: .utf8),
              let pSel = String(data: pSelJson, encoding: .utf8),
              let sSel = String(data: subSelJson, encoding: .utf8) else { return }

        let script = """
        (function() {
            function setNativeValue(el, val) {
                if (!el) return;
                el.focus();
                el.value = val;
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            }
            const u = document.querySelector(\(uSel));
            const p = document.querySelector(\(pSel));
            setNativeValue(u, \(uLit));
            setNativeValue(p, \(pLit));
            const btn = document.querySelector(\(sSel));
            if (btn) btn.focus();
        })();
        """
        webView.evaluateJavaScript(script, completionHandler: nil)
    }

    nonisolated func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        Task { @MainActor in
            self.isLoading = true
        }
    }

    nonisolated func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        Task { @MainActor in
            self.isLoading = false
            self.canGoBack = webView.canGoBack
            self.canGoForward = webView.canGoForward
            if let current = webView.url?.absoluteString {
                self.onUrlChanged?(current)
            }
        }
    }

    nonisolated func webView(
        _ webView: WKWebView,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
           let trust = challenge.protectionSpace.serverTrust {
            Task { @MainActor in
                if self.ignoreSelfSignedCert {
                    completionHandler(.useCredential, URLCredential(trust: trust))
                } else {
                    completionHandler(.performDefaultHandling, nil)
                }
            }
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }
}

private struct IsolatedWKWebViewRepresentable: NSViewRepresentable {
    @ObservedObject var controller: IsolatedWebController
    let settings: WebPageProtocolSettings

    func makeNSView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        let profileUUID = UUID(uuidString: settings.isolatedStorageProfileId) ?? UUID()
        config.websiteDataStore = WKWebsiteDataStore(forIdentifier: profileUUID)

        let wk = WKWebView(frame: .zero, configuration: config)
        controller.ignoreSelfSignedCert = settings.ignoreSelfSignedInternalCert
        wk.navigationDelegate = controller
        controller.webView = wk
        return wk
    }

    func updateNSView(_ nsView: WKWebView, context: Context) {
        controller.ignoreSelfSignedCert = settings.ignoreSelfSignedInternalCert
    }
}
