import AuthenticationServices
import CryptoKit
import Foundation
import Security
import SwiftUI
import UIKit
import shared

let slockIosOAuthProviderIds = ["google", "github", "apple"]
let slockIosOAuthProviders = slockIosOAuthProviderIds.joined(separator: ",")
private let slockIosOAuthLoginPage = "compose_login"
private let slockIosOAuthLegalPage = "compose_auth_legal_acceptance"
private let slockIosOAuthChooseServerPage = "compose_auth_choose_server"
private let slockIosOAuthAppShellPage = "compose_slock_app_shell"

struct SlockIosOAuthDestination {
    let pageName: String
    let pageData: [String: Any]
    let revision: String
}

final class SlockIosOAuthRouteStore: ObservableObject {
    static let shared = SlockIosOAuthRouteStore()

    @Published private(set) var destination = SlockIosOAuthDestination(
        pageName: slockIosOAuthAppShellPage,
        pageData: ["pageName": slockIosOAuthAppShellPage],
        revision: UUID().uuidString
    )

    private init() {}

    func apply(_ result: SlockIosMobileOAuthCompletionResult) {
        runOnMain { [weak self] in
            guard let self else { return }
            switch result.status {
            case "app_shell":
                self.replace(
                    pageName: slockIosOAuthAppShellPage,
                    pageData: [
                        "pageName": slockIosOAuthAppShellPage,
                        "slockSuppressRestoreLanding": true,
                        "slockAuthGateInstanceId": UUID().uuidString,
                    ]
                )
            case "choose_server":
                self.replace(
                    pageName: slockIosOAuthChooseServerPage,
                    pageData: ["pageName": slockIosOAuthChooseServerPage]
                )
            case "legal_acceptance":
                guard
                    let provider = result.provider, !provider.isEmpty,
                    let handoffCode = result.handoffCode, !handoffCode.isEmpty,
                    let codeVerifier = result.codeVerifier, !codeVerifier.isEmpty
                else {
                    self.replaceWithLoginError("Sign-in request expired. Try again.")
                    return
                }
                self.replace(
                    pageName: slockIosOAuthLegalPage,
                    pageData: [
                        "pageName": slockIosOAuthLegalPage,
                        "slockAuthLegalProvider": provider,
                        "slockAuthLegalPlatform": "ios",
                        "slockAuthLegalCode": handoffCode,
                        "slockAuthLegalCodeVerifier": codeVerifier,
                        "slockReplaceCurrentPage": true,
                    ]
                )
            case "login_error":
                self.replaceWithLoginError(result.message ?? "Mobile sign-in failed. Try again.")
            case "settings_account":
                self.replace(
                    pageName: slockIosOAuthAppShellPage,
                    pageData: [
                        "pageName": slockIosOAuthAppShellPage,
                        "slockSuppressRestoreLanding": true,
                        "slockAuthGateInstanceId": UUID().uuidString,
                        "slockInitialTab": "settings",
                        "slockInitialSettingsDestination": "account",
                    ]
                )
                if let message = result.message, !message.isEmpty {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                        SlockIosMobileOAuthHost.shared.presentNotice(message)
                    }
                }
            case "ignored", "retry":
                break
            default:
                self.replaceWithLoginError("Mobile sign-in failed. Try again.")
            }
        }
    }

    func showLoginError(_ message: String) {
        runOnMain { [weak self] in
            self?.replaceWithLoginError(message)
        }
    }

    private func replaceWithLoginError(_ message: String) {
        replace(
            pageName: slockIosOAuthLoginPage,
            pageData: [
                "pageName": slockIosOAuthLoginPage,
                "slockAuthMessage": message,
                "slockReplaceCurrentPage": true,
            ]
        )
    }

    private func replace(pageName: String, pageData: [String: Any]) {
        destination = SlockIosOAuthDestination(
            pageName: pageName,
            pageData: pageData,
            revision: UUID().uuidString
        )
    }

    private func runOnMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.async(execute: block)
        }
    }
}

@objcMembers
final class SlockIosMobileOAuthHost: NSObject,
    ASWebAuthenticationPresentationContextProviding,
    ASAuthorizationControllerDelegate,
    ASAuthorizationControllerPresentationContextProviding {
    static let shared = SlockIosMobileOAuthHost()
    private static let appleUserIdentifierDefaultsKey = "slock.nativeApple.userIdentifier"

    private struct Pkce {
        let verifier: String
        let challenge: String
    }

    private struct ParsedCallback {
        let scheme: String
        let host: String
        let path: String
        let provider: String?
        let mode: String?
        let code: String?
        let error: String?
    }

    private struct DeferredCallback {
        let callback: ParsedCallback
        let expectedGeneration: String?
        let source: String
        let attempt: Int
    }

    private let loginCancellation = SlockIosLoginCancellationRelay()
    private var session: ASWebAuthenticationSession?
    private var appleAuthorizationController: ASAuthorizationController?
    private weak var presentationWindow: UIWindow?
    private var pendingStartGeneration: String?
    private var activeGeneration: String?
    private var activeAppleGeneration: String?
    private var supersededGenerations: Set<String> = []
    private var deferredCallback: DeferredCallback?
    private var deferredRetryWorkItem: DispatchWorkItem?
    private var nativeAppleRetryWorkItem: DispatchWorkItem?
    private var appleCredentialRevocationObserver: NSObjectProtocol?

    private override init() {
        super.init()
        appleCredentialRevocationObserver = NotificationCenter.default.addObserver(
            forName: ASAuthorizationAppleIDProvider.credentialRevokedNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.clearCachedAppleUserIdentifier(reason: "revoked_notification")
        }
    }

    deinit {
        if let appleCredentialRevocationObserver {
            NotificationCenter.default.removeObserver(appleCredentialRevocationObserver)
        }
    }

    @objc(startWithProvider:mode:shellScopeId:completion:)
    func start(
        provider: String,
        mode: String,
        shellScopeId: String?,
        completion: @escaping (NSDictionary) -> Void
    ) {
        runOnMain { [weak self] in
            guard let self else { return }
            let normalizedProvider = provider.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let normalizedMode = mode.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard slockIosOAuthProviderIds.contains(normalizedProvider), ["login", "link"].contains(normalizedMode) else {
                completion(self.startError(code: "ios_mobile_oauth_invalid_request", message: "This sign-in provider is unavailable."))
                return
            }
            guard self.pendingStartGeneration == nil, self.activeAppleGeneration == nil else {
                completion(self.startError(
                    code: "ios_mobile_oauth_start_in_progress",
                    message: "A sign-in request is already starting."
                ))
                return
            }
            guard let pkce = self.createPkce() else {
                completion(self.startError(code: "ios_mobile_oauth_pkce_failed", message: "Mobile sign-in could not be prepared."))
                return
            }
            guard let callbackScheme = self.configuredCallbackScheme() else {
                completion(self.startError(code: "ios_mobile_oauth_scheme_unavailable", message: "Mobile sign-in could not be prepared."))
                return
            }
            let callbackReturnUri = "\(callbackScheme)://oauth/callback"

            self.supersedeActiveSession()
            let generation = UUID().uuidString.lowercased()
            self.pendingStartGeneration = generation
            self.loginCancellation.begin(generation: generation, mode: normalizedMode, scope: shellScopeId)
            let handleStart: (SlockIosMobileOAuthStartResult) -> Void = { [weak self] result in
                self?.runOnMain {
                    guard let self else { return }
                    guard self.pendingStartGeneration == generation else {
                        self.cancelPending(generation: generation, error: "provider_cancelled", applyRoute: false)
                        completion(self.startError(
                            code: "ios_mobile_oauth_start_superseded",
                            message: "This sign-in request was superseded."
                        ))
                        return
                    }
                    self.pendingStartGeneration = nil
                    if normalizedProvider == "apple" {
                        guard
                            result.status == "started",
                            result.presentation == "native_apple",
                            result.authorizationUrl == nil
                        else {
                            completion(self.startError(
                                code: result.errorCode ?? "ios_native_apple_start_failed",
                                message: result.message ?? "Mobile sign-in could not be started."
                            ))
                            return
                        }
                        guard let anchor = self.currentPresentationWindow() else {
                            self.cancelPending(generation: generation, error: "provider_failed", applyRoute: false)
                            completion(self.startError(
                                code: "ios_mobile_oauth_window_unavailable",
                                message: "Mobile sign-in could not be started."
                            ))
                            return
                        }

                        self.refreshCachedAppleCredentialState()
                        let request = ASAuthorizationAppleIDProvider().createRequest()
                        request.requestedScopes = [.email]
                        request.nonce = pkce.challenge
                        let controller = ASAuthorizationController(authorizationRequests: [request])
                        controller.delegate = self
                        controller.presentationContextProvider = self
                        self.presentationWindow = anchor
                        self.activeAppleGeneration = generation
                        self.appleAuthorizationController = controller
                        controller.performRequests()
                        SlockIosDiagnosticsRecord(
                            "info",
                            "SlockIosOAuth",
                            "oauth_native_apple_started mode=\(normalizedMode)"
                        )
                        completion(["status": "started", "code": 202])
                        return
                    }
                    guard
                        result.status == "started",
                        result.presentation == "browser",
                        let authorizationUrlText = result.authorizationUrl,
                        let authorizationUrl = URL(string: authorizationUrlText),
                        authorizationUrl.scheme?.lowercased() == "https",
                        let resultCallbackScheme = result.callbackScheme,
                        resultCallbackScheme == callbackScheme
                    else {
                        completion(self.startError(
                            code: result.errorCode ?? "ios_mobile_oauth_start_failed",
                            message: result.message ?? "Mobile sign-in could not be started."
                        ))
                        return
                    }
                    guard let anchor = self.currentPresentationWindow() else {
                        self.cancelPending(generation: generation, error: "browser_failed", applyRoute: false)
                        completion(self.startError(
                            code: "ios_mobile_oauth_window_unavailable",
                            message: "Unable to present the sign-in browser."
                        ))
                        return
                    }

                    let authSession = ASWebAuthenticationSession(
                        url: authorizationUrl,
                        callbackURLScheme: resultCallbackScheme
                    ) { [weak self] callbackUrl, error in
                        self?.runOnMain {
                            guard let self else { return }
                            if self.activeGeneration == generation {
                                self.session = nil
                                self.presentationWindow = nil
                                self.activeGeneration = nil
                            }
                            if let callbackUrl {
                                if self.supersededGenerations.remove(generation) != nil {
                                    self.cancelPending(
                                        generation: generation,
                                        error: "provider_cancelled",
                                        applyRoute: false
                                    )
                                    return
                                }
                                _ = self.acceptCallback(
                                    callbackUrl,
                                    expectedGeneration: generation,
                                    source: "web_auth_session"
                                )
                                return
                            }
                            let cancelled =
                                (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
                            self.cancelPending(
                                generation: generation,
                                error: cancelled ? "provider_cancelled" : "browser_failed",
                                applyRoute: self.supersededGenerations.remove(generation) == nil
                            )
                        }
                    }
                    authSession.presentationContextProvider = self
                    authSession.prefersEphemeralWebBrowserSession = false
                    self.presentationWindow = anchor
                    self.activeGeneration = generation
                    self.session = authSession
                    guard authSession.start() else {
                        self.session = nil
                        self.presentationWindow = nil
                        self.activeGeneration = nil
                        self.cancelPending(generation: generation, error: "browser_failed", applyRoute: false)
                        completion(self.startError(
                            code: "ios_mobile_oauth_browser_start_failed",
                            message: "Unable to open the sign-in browser."
                        ))
                        return
                    }
                    SlockIosDiagnosticsRecord(
                        "info",
                        "SlockIosOAuth",
                        "oauth_browser_started provider=\(normalizedProvider) mode=\(normalizedMode)"
                    )
                    completion(["status": "started", "code": 202])
                }
            }
            if normalizedProvider == "apple" {
                SlockIosMobileOAuthBridge.shared.startNativeApple(
                    mode: normalizedMode,
                    generation: generation,
                    returnUri: callbackReturnUri,
                    codeVerifier: pkce.verifier,
                    codeChallenge: pkce.challenge
                ) { result in
                    handleStart(result)
                }
            } else {
                SlockIosMobileOAuthBridge.shared.startBrowser(
                    provider: normalizedProvider,
                    mode: normalizedMode,
                    generation: generation,
                    returnUri: callbackReturnUri,
                    codeVerifier: pkce.verifier,
                    codeChallenge: pkce.challenge
                ) { result in
                    handleStart(result)
                }
            }
        }
    }

    @discardableResult
    func acceptCallbackURL(_ url: URL, source: String) -> Bool {
        acceptCallback(url, expectedGeneration: nil, source: source)
    }

    func resumeDeferredCallbackIfNeeded() {
        runOnMain { [weak self] in
            guard let self, let deferred = self.deferredCallback else { return }
            self.deferredCallback = nil
            self.process(deferred)
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        presentationWindow ?? currentPresentationWindow() ?? UIWindow(frame: UIScreen.main.bounds)
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        presentationWindow ?? currentPresentationWindow() ?? UIWindow(frame: UIScreen.main.bounds)
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        runOnMain { [weak self] in
            guard
                let self,
                self.appleAuthorizationController === controller,
                let generation = self.activeAppleGeneration,
                let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
                !credential.user.isEmpty,
                let authorizationCodeData = credential.authorizationCode,
                let identityTokenData = credential.identityToken,
                let authorizationCode = String(data: authorizationCodeData, encoding: .utf8),
                !authorizationCode.isEmpty,
                let identityToken = String(data: identityTokenData, encoding: .utf8),
                !identityToken.isEmpty
            else {
                self?.finishNativeAppleWithError(
                    controller: controller,
                    error: "provider_failed"
                )
                return
            }
            self.appleAuthorizationController = nil
            self.presentationWindow = nil
            self.processNativeAppleCredential(
                generation: generation,
                authorizationCode: authorizationCode,
                identityToken: identityToken,
                appleUserIdentifier: credential.user,
                attempt: 0
            )
        }
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        let cancelled = (error as? ASAuthorizationError)?.code == .canceled
        finishNativeAppleWithError(
            controller: controller,
            error: cancelled ? "provider_cancelled" : "provider_failed"
        )
    }

    func presentNotice(_ message: String) {
        runOnMain {
            guard let controller = Self.topViewController(), controller.presentedViewController == nil else {
                return
            }
            let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            controller.present(alert, animated: true)
        }
    }

    private func acceptCallback(
        _ url: URL,
        expectedGeneration: String?,
        source: String
    ) -> Bool {
        guard let callback = parseCallback(url) else { return false }
        runOnMain { [weak self] in
            guard let self else { return }
            // ASWebAuthenticationSession owns warm callbacks and supplies the exact generation.
            // Ignore duplicate AppDelegate/SwiftUI delivery while a retained session is active;
            // generation-less admission is reserved for genuine cold-process recovery.
            if expectedGeneration == nil,
               self.pendingStartGeneration != nil ||
               self.activeGeneration != nil ||
               self.activeAppleGeneration != nil {
                SlockIosDiagnosticsRecord(
                    "info",
                    "SlockIosOAuth",
                    "oauth_callback_ignored source=\(self.safeSource(source)) reason=active_session"
                )
                return
            }
            self.process(
                DeferredCallback(
                    callback: callback,
                    expectedGeneration: expectedGeneration,
                    source: source,
                    attempt: 0
                )
            )
        }
        return true
    }

    private func process(_ deferred: DeferredCallback) {
        deferredRetryWorkItem?.cancel()
        deferredRetryWorkItem = nil
        SlockIosMobileOAuthBridge.shared.completeCallback(
            scheme: deferred.callback.scheme,
            host: deferred.callback.host,
            path: deferred.callback.path,
            provider: deferred.callback.provider,
            mode: deferred.callback.mode,
            code: deferred.callback.code,
            error: deferred.callback.error,
            expectedGeneration: deferred.expectedGeneration
        ) { [weak self] result in
            self?.runOnMain {
                guard let self else { return }
                if result.status == "retry" {
                    self.deferRetry(
                        DeferredCallback(
                            callback: deferred.callback,
                            expectedGeneration: deferred.expectedGeneration ?? result.generation,
                            source: deferred.source,
                            attempt: deferred.attempt
                        )
                    )
                    return
                }
                self.deferredCallback = nil
                SlockIosDiagnosticsRecord(
                    "info",
                    "SlockIosOAuth",
                    "oauth_callback_consumed source=\(self.safeSource(deferred.source)) status=\(result.status)"
                )
                self.loginCancellation.finish(
                    generation: deferred.expectedGeneration,
                    error: deferred.callback.error,
                    status: result.status,
                    applyRoute: true
                )
                SlockIosOAuthRouteStore.shared.apply(result)
            }
        }
    }

    private func deferRetry(_ deferred: DeferredCallback) {
        let nextAttempt = deferred.attempt + 1
        guard nextAttempt <= 40 else {
            deferredCallback = nil
            if let generation = deferred.expectedGeneration, !generation.isEmpty {
                cancelPending(generation: generation, error: "runtime_not_ready", applyRoute: true)
            } else {
                SlockIosOAuthRouteStore.shared.showLoginError("Raft is still starting. Try sign-in again.")
            }
            return
        }
        let next = DeferredCallback(
            callback: deferred.callback,
            expectedGeneration: deferred.expectedGeneration,
            source: deferred.source,
            attempt: nextAttempt
        )
        deferredCallback = next
        let workItem = DispatchWorkItem { [weak self] in
            guard let self, self.deferredCallback?.attempt == nextAttempt else { return }
            self.deferredCallback = nil
            self.process(next)
        }
        deferredRetryWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: workItem)
    }

    private func processNativeAppleCredential(
        generation: String,
        authorizationCode: String,
        identityToken: String,
        appleUserIdentifier: String,
        attempt: Int
    ) {
        nativeAppleRetryWorkItem?.cancel()
        nativeAppleRetryWorkItem = nil
        SlockIosMobileOAuthBridge.shared.completeNativeApple(
            generation: generation,
            authorizationCode: authorizationCode,
            identityToken: identityToken
        ) { [weak self] result in
            self?.runOnMain {
                guard let self, self.activeAppleGeneration == generation else { return }
                if result.status == "retry" {
                    self.deferNativeAppleCredential(
                        generation: generation,
                        authorizationCode: authorizationCode,
                        identityToken: identityToken,
                        appleUserIdentifier: appleUserIdentifier,
                        attempt: attempt
                    )
                    return
                }
                self.nativeAppleRetryWorkItem?.cancel()
                self.nativeAppleRetryWorkItem = nil
                self.activeAppleGeneration = nil
                self.appleAuthorizationController = nil
                self.presentationWindow = nil
                if ["app_shell", "choose_server", "legal_acceptance", "settings_account"].contains(result.status) {
                    self.cacheAppleUserIdentifier(appleUserIdentifier)
                }
                SlockIosOAuthRouteStore.shared.apply(result)
            }
        }
    }

    private func deferNativeAppleCredential(
        generation: String,
        authorizationCode: String,
        identityToken: String,
        appleUserIdentifier: String,
        attempt: Int
    ) {
        let nextAttempt = attempt + 1
        guard nextAttempt <= 40 else {
            nativeAppleRetryWorkItem = nil
            activeAppleGeneration = nil
            appleAuthorizationController = nil
            presentationWindow = nil
            cancelPending(generation: generation, error: "runtime_not_ready", applyRoute: true)
            return
        }
        let workItem = DispatchWorkItem { [weak self] in
            guard let self, self.activeAppleGeneration == generation else { return }
            self.nativeAppleRetryWorkItem = nil
            self.processNativeAppleCredential(
                generation: generation,
                authorizationCode: authorizationCode,
                identityToken: identityToken,
                appleUserIdentifier: appleUserIdentifier,
                attempt: nextAttempt
            )
        }
        nativeAppleRetryWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: workItem)
    }

    private func finishNativeAppleWithError(
        controller: ASAuthorizationController,
        error: String
    ) {
        runOnMain { [weak self] in
            guard
                let self,
                self.appleAuthorizationController === controller,
                let generation = self.activeAppleGeneration
            else {
                return
            }
            self.nativeAppleRetryWorkItem?.cancel()
            self.nativeAppleRetryWorkItem = nil
            self.appleAuthorizationController = nil
            self.activeAppleGeneration = nil
            self.presentationWindow = nil
            self.cancelPending(generation: generation, error: error, applyRoute: true)
        }
    }

    private func cacheAppleUserIdentifier(_ identifier: String) {
        guard !identifier.isEmpty else { return }
        UserDefaults.standard.set(identifier, forKey: Self.appleUserIdentifierDefaultsKey)
    }

    private func clearCachedAppleUserIdentifier(reason: String) {
        UserDefaults.standard.removeObject(forKey: Self.appleUserIdentifierDefaultsKey)
        SlockIosDiagnosticsRecord(
            "info",
            "SlockIosOAuth",
            "oauth_native_apple_credential_state_cleared reason=\(safeSource(reason))"
        )
    }

    private func refreshCachedAppleCredentialState() {
        guard
            let identifier = UserDefaults.standard.string(forKey: Self.appleUserIdentifierDefaultsKey),
            !identifier.isEmpty
        else {
            return
        }
        ASAuthorizationAppleIDProvider().getCredentialState(forUserID: identifier) { [weak self] state, error in
            guard error == nil else { return }
            switch state {
            case .authorized:
                break
            case .revoked:
                self?.clearCachedAppleUserIdentifier(reason: "revoked")
            case .notFound:
                self?.clearCachedAppleUserIdentifier(reason: "not_found")
            case .transferred:
                self?.clearCachedAppleUserIdentifier(reason: "transferred")
            @unknown default:
                self?.clearCachedAppleUserIdentifier(reason: "unknown")
            }
        }
    }

    private func cancelPending(generation: String, error: String, applyRoute: Bool) {
        SlockIosMobileOAuthBridge.shared.cancel(generation: generation, error: error) { [weak self] result in
            self?.runOnMain {
                // The shared coordinator first validates the OAuth generation.
                // An ignored/stale callback must not clear a newer login's links.
                self?.loginCancellation.finish(
                    generation: generation, error: error,
                    status: result.status, applyRoute: applyRoute
                )
                if applyRoute {
                    SlockIosOAuthRouteStore.shared.apply(result)
                }
            }
        }
    }

    private func supersedeActiveSession() {
        guard let generation = activeGeneration else { return }
        supersededGenerations.insert(generation)
        session?.cancel()
        session = nil
        presentationWindow = nil
        activeGeneration = nil
    }

    private func createPkce() -> Pkce? {
        var bytes = [UInt8](repeating: 0, count: 32)
        let randomStatus = bytes.withUnsafeMutableBytes { buffer -> Int32 in
            guard let baseAddress = buffer.baseAddress else { return errSecParam }
            return SecRandomCopyBytes(kSecRandomDefault, buffer.count, baseAddress)
        }
        guard randomStatus == errSecSuccess else {
            return nil
        }
        let verifier = base64Url(Data(bytes))
        let challenge = base64Url(Data(SHA256.hash(data: Data(verifier.utf8))))
        guard verifier.count >= 43, challenge.count >= 43 else { return nil }
        return Pkce(verifier: verifier, challenge: challenge)
    }

    private func base64Url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private func parseCallback(_ url: URL) -> ParsedCallback? {
        guard
            let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
            let scheme = components.scheme?.lowercased(),
            scheme == configuredCallbackScheme(),
            components.host?.lowercased() == "oauth",
            components.path == "/callback"
        else {
            return nil
        }
        let admittedNames = Set(["provider", "mode", "code", "error", "error_description"])
        var values: [String: String] = [:]
        for item in components.queryItems ?? [] where admittedNames.contains(item.name) {
            guard values[item.name] == nil else { return nil }
            values[item.name] = item.value ?? ""
        }
        if values["error"] != nil, values["error_description"] != nil { return nil }
        return ParsedCallback(
            scheme: scheme,
            host: "oauth",
            path: "/callback",
            provider: values["provider"],
            mode: values["mode"],
            code: values["code"],
            error: values["error"] ?? values["error_description"]
        )
    }

    private func configuredCallbackScheme() -> String? {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "SlockOAuthCallbackScheme") as? String else {
            return nil
        }
        let scheme = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return ["raft", "raft-alpha", "raft-beta", "raft-debug"].contains(scheme) ? scheme : nil
    }

    private func startError(code: String, message: String) -> NSDictionary {
        ["status": "error", "code": code, "message": message]
    }

    private func currentPresentationWindow() -> UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }
            .flatMap { $0.windows }
            .first(where: { $0.isKeyWindow })
            ?? UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .filter { $0.activationState == .foregroundActive }
                .flatMap { $0.windows }
                .first
    }

    private static func topViewController() -> UIViewController? {
        guard let root = shared.currentPresentationWindow()?.rootViewController else { return nil }
        var current = root
        while let presented = current.presentedViewController {
            current = presented
        }
        if let navigation = current as? UINavigationController {
            return navigation.visibleViewController ?? navigation
        }
        return current
    }

    private func safeSource(_ source: String) -> String {
        let safe = source.lowercased().filter { $0.isLetter || $0 == "_" }.prefix(32)
        return safe.isEmpty ? "unknown" : String(safe)
    }

    private func runOnMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.async(execute: block)
        }
    }
}
