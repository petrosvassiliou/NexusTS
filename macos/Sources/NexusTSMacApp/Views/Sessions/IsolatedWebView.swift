import CryptoKit
import Foundation
import NexusTSCore
import Security
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

    private var latestConnection: VaultConnection {
        store.activeConnections.first(where: { $0.id == tab.connection.id }) ?? tab.connection
    }

    private var webSettings: WebPageProtocolSettings {
        if case .webPage(let s) = latestConnection.settings { return s }
        let fallback = latestConnection.host.hasPrefix("http") ? latestConnection.host : "https://\(latestConnection.host)"
        return WebPageProtocolSettings(url: fallback)
    }

    public var body: some View {
        let resolved = PropertyInheritanceResolver.resolve(connection: latestConnection, in: store.document)
        let effectiveUser = tab.adHocUsernameOverride ?? resolved.effectiveCredential?.username ?? ""
        let effectivePass = tab.adHocPasswordOverride ?? resolved.effectiveCredential?.secretPassword ?? ""

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
                    if browserController.isLoading {
                        browserController.webView?.stopLoading()
                    } else if browserController.webView?.url != nil {
                        browserController.webView?.reload()
                    } else {
                        browserController.load(urlString: urlInputText)
                    }
                } label: {
                    Image(systemName: browserController.isLoading ? "xmark" : "arrow.clockwise")
                }
                .buttonStyle(.borderless)

                HStack(spacing: 6) {
                    Image(systemName: webSettings.pinnedCertSha256.isEmpty ? "lock.shield" : "checkmark.shield.fill")
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
                        username: effectiveUser,
                        password: effectivePass
                    )
                } label: {
                    Label("Auto-Fill Vault Login", systemImage: "key.fill")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .disabled(effectiveUser.isEmpty && effectivePass.isEmpty)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.bar)

            if let navError = browserController.lastNavigationError {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(Color(nsColor: .systemOrange))
                    Text(navError)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer()
                    if browserController.canBypassCertificateError {
                        Button("Trust Certificate & Reload") {
                            browserController.trustCertificateForSessionAndReload(fallbackUrlString: urlInputText)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.mini)
                    }
                    Button("Retry") {
                        browserController.load(urlString: urlInputText)
                    }
                    .controlSize(.mini)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 4)
                .background(Color(nsColor: .controlBackgroundColor))
                Divider()
            } else {
                Divider()
            }

            IsolatedWKWebViewRepresentable(
                controller: browserController,
                settings: webSettings,
                effectiveUsername: effectiveUser,
                effectivePassword: effectivePass
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
    @Published var lastNavigationError: String? = nil
    @Published var canBypassCertificateError: Bool = false

    var webView: WKWebView?
    private var pendingURL: URL? = nil
    var settings = WebPageProtocolSettings()
    var effectiveUsername: String = ""
    var effectivePassword: String = ""
    var runtimeTrustSelfSignedCert: Bool = false
    var onUrlChanged: ((String) -> Void)?

    func attach(webView: WKWebView) {
        self.webView = webView
        if let url = pendingURL {
            pendingURL = nil
            lastNavigationError = nil
            webView.load(URLRequest(url: url))
        }
    }

    static func normalizeWebUrlString(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        if trimmed.contains("://") { return trimmed }
        let colonCount = trimmed.filter { $0 == ":" }.count
        if colonCount >= 2 && !trimmed.hasPrefix("[") {
            return "https://[\(trimmed)]"
        }
        return "https://\(trimmed)"
    }

    func trustCertificateForSessionAndReload(fallbackUrlString: String) {
        runtimeTrustSelfSignedCert = true
        canBypassCertificateError = false
        lastNavigationError = nil
        if let currentUrl = webView?.url {
            webView?.load(URLRequest(url: currentUrl))
        } else {
            load(urlString: fallbackUrlString)
        }
    }

    func load(urlString: String) {
        let normalized = Self.normalizeWebUrlString(urlString)
        guard !normalized.isEmpty, let url = URL(string: normalized) else { return }
        lastNavigationError = nil
        canBypassCertificateError = false
        if let webView {
            pendingURL = nil
            webView.load(URLRequest(url: url))
        } else {
            pendingURL = url
        }
    }

    func injectCredentials(settings: WebPageProtocolSettings, username: String, password: String) {
        guard let webView else { return }
        guard !username.isEmpty || !password.isEmpty else { return }
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
                const proto = Object.getPrototypeOf(el);
                const desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
                if (desc && desc.set) {
                    desc.set.call(el, val);
                } else {
                    el.value = val;
                }
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

    private static func normalizeSha256Pin(_ raw: String) -> String {
        var cleaned = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if cleaned.hasPrefix("sha256/") {
            cleaned.removeFirst("sha256/".count)
        } else if cleaned.hasPrefix("sha256:") {
            cleaned.removeFirst("sha256:".count)
        }
        return cleaned
            .replacingOccurrences(of: ":", with: "")
            .replacingOccurrences(of: " ", with: "")
    }

    private static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func matchesPinnedCertificate(trust: SecTrust, expectedPin: String) -> Bool {
        let normalizedPin = normalizeSha256Pin(expectedPin)
        guard !normalizedPin.isEmpty else { return true }
        guard let chain = SecTrustCopyCertificateChain(trust),
              CFArrayGetCount(chain) > 0,
              let rawLeaf = CFArrayGetValueAtIndex(chain, 0) else {
            return false
        }
        let leaf = unsafeBitCast(rawLeaf, to: SecCertificate.self)
        let certDer = SecCertificateCopyData(leaf) as Data
        if sha256Hex(certDer) == normalizedPin || certDer.base64EncodedString().lowercased() == normalizedPin {
            return true
        }
        if let pubKey = SecCertificateCopyKey(leaf),
           let keyData = SecKeyCopyExternalRepresentation(pubKey, nil) as Data? {
            if sha256Hex(keyData) == normalizedPin || keyData.base64EncodedString().lowercased() == normalizedPin {
                return true
            }
        }
        return false
    }

    nonisolated func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        Task { @MainActor in
            self.isLoading = true
            self.lastNavigationError = nil
            self.canBypassCertificateError = false
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
            if self.settings.autoFillEnabled && (!self.effectiveUsername.isEmpty || !self.effectivePassword.isEmpty) {
                self.injectCredentials(
                    settings: self.settings,
                    username: self.effectiveUsername,
                    password: self.effectivePassword
                )
            }
        }
    }

    nonisolated func webView(
        _ webView: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation!,
        withError error: Error
    ) {
        let nsErr = error as NSError
        Task { @MainActor in
            self.isLoading = false
            if nsErr.code != NSURLErrorCancelled {
                self.lastNavigationError = error.localizedDescription
                let isCertErr = (-1206 ... -1201).contains(nsErr.code)
                if isCertErr && self.settings.pinnedCertSha256.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    self.canBypassCertificateError = true
                }
            }
        }
    }

    nonisolated func webView(
        _ webView: WKWebView,
        didFail navigation: WKNavigation!,
        withError error: Error
    ) {
        let nsErr = error as NSError
        Task { @MainActor in
            self.isLoading = false
            if nsErr.code != NSURLErrorCancelled {
                self.lastNavigationError = error.localizedDescription
            }
        }
    }

    nonisolated func webView(
        _ webView: WKWebView,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let method = challenge.protectionSpace.authenticationMethod
        if method == NSURLAuthenticationMethodHTTPBasic || method == NSURLAuthenticationMethodHTTPDigest {
            let failureCount = challenge.previousFailureCount
            Task { @MainActor in
                if self.settings.injectHttpBasicAuth,
                   failureCount == 0,
                   (!self.effectiveUsername.isEmpty || !self.effectivePassword.isEmpty) {
                    let cred = URLCredential(
                        user: self.effectiveUsername,
                        password: self.effectivePassword,
                        persistence: .forSession
                    )
                    completionHandler(.useCredential, cred)
                } else {
                    completionHandler(.performDefaultHandling, nil)
                }
            }
        } else if method == NSURLAuthenticationMethodServerTrust,
                  let trust = challenge.protectionSpace.serverTrust {
            Task { @MainActor in
                let pinned = self.settings.pinnedCertSha256.trimmingCharacters(in: .whitespacesAndNewlines)
                if !pinned.isEmpty {
                    if Self.matchesPinnedCertificate(trust: trust, expectedPin: pinned) {
                        completionHandler(.useCredential, URLCredential(trust: trust))
                    } else {
                        self.lastNavigationError = "TLS certificate SHA-256 fingerprint did not match pinned hash."
                        completionHandler(.cancelAuthenticationChallenge, nil)
                    }
                } else if self.settings.ignoreSelfSignedInternalCert || self.runtimeTrustSelfSignedCert {
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
    let effectiveUsername: String
    let effectivePassword: String

    func makeNSView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        let profileUUID = UUID(uuidString: settings.isolatedStorageProfileId) ?? UUID()
        config.websiteDataStore = WKWebsiteDataStore(forIdentifier: profileUUID)

        let wk = WKWebView(frame: .zero, configuration: config)
        controller.settings = settings
        controller.effectiveUsername = effectiveUsername
        controller.effectivePassword = effectivePassword
        wk.navigationDelegate = controller
        controller.attach(webView: wk)
        return wk
    }

    func updateNSView(_ nsView: WKWebView, context: Context) {
        controller.settings = settings
        controller.effectiveUsername = effectiveUsername
        controller.effectivePassword = effectivePassword
    }
}
