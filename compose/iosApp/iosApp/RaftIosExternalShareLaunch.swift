import Foundation
import UIKit
import shared

enum RaftIosExternalShareLaunch {
    @discardableResult
    static func accept(_ url: URL, shellInstanceId: String? = nil, source: String) -> Bool {
        let configuredScheme = Bundle.main.object(forInfoDictionaryKey: "SlockOAuthCallbackScheme") as? String
        guard let token = RaftExternalShareContract.hostLaunchToken(
            from: url,
            configuredScheme: configuredScheme
        ) else { return false }
        submit(token: token, shellInstanceId: shellInstanceId, source: source)
        return true
    }

    static func resumePendingIfNeeded(shellInstanceId: String? = nil) {
        guard let token = RaftExternalShareEnvelopeStorage.shared.pendingToken() else { return }
        submit(token: token, shellInstanceId: shellInstanceId, source: "pending_resume")
    }

    private static func submit(token: String, shellInstanceId: String?, source: String) {
        let deliver = {
            let admitted = RaftExternalShareLaunchIngress.shared.submit(
                token: token,
                targetShellInstanceId: shellInstanceId
            )
            guard admitted else { return }
            revealLiveAppShellIfPresent(shellInstanceId: shellInstanceId)
            RaftIosDiagnosticsRecord(
                "info",
                "RaftIosExternalShare",
                "external_share_launch_admitted source=\(source)"
            )
        }
        if Thread.isMainThread {
            deliver()
        } else {
            DispatchQueue.main.async(execute: deliver)
        }
    }

    /// Surfaces the mounted AppShell when an external share arrives while the user is inside a
    /// pushed detail page (such as ThreadPage or Settings). Leaves non-shell roots (e.g. login)
    /// untouched so `ExternalShareLaunchHost` consumes the queued token once sign-in finishes.
    private static func revealLiveAppShellIfPresent(shellInstanceId: String?) {
        guard let nav = findLiveAppShellNavigationController(shellInstanceId: shellInstanceId) else {
            return
        }
        if nav.presentedViewController != nil {
            nav.dismiss(animated: false)
        }
        if nav.viewControllers.count > 1 {
            nav.popToRootViewController(animated: false)
        }
    }

    private static func findLiveAppShellNavigationController(
        shellInstanceId: String?
    ) -> UINavigationController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let matchingScenes: [UIWindowScene]
        if let targetId = shellInstanceId?.trimmingCharacters(in: .whitespacesAndNewlines),
            !targetId.isEmpty {
            let exact = scenes.filter { scene in
                RaftIosShellWindowRegistry.context(forSessionId: scene.session.persistentIdentifier)?
                    .shellInstanceId == targetId
            }
            matchingScenes = exact.isEmpty ? scenes : exact
        } else {
            matchingScenes = scenes
        }
        let windows = matchingScenes.flatMap(\.windows)
        let ordered = windows.filter(\.isKeyWindow) + windows.filter { !$0.isKeyWindow }
        for window in ordered {
            if let nav = findAppShellNav(from: window.rootViewController) {
                return nav
            }
        }
        return nil
    }

    private static func findAppShellNav(from controller: UIViewController?) -> UINavigationController? {
        guard let controller else { return nil }
        if let nav = controller as? UINavigationController,
            let root = nav.viewControllers.first as? RaftPageViewController,
            root.raftIsAppShellPage {
            return nav
        }
        for child in controller.children {
            if let found = findAppShellNav(from: child) {
                return found
            }
        }
        return findAppShellNav(from: controller.presentedViewController)
    }
}
