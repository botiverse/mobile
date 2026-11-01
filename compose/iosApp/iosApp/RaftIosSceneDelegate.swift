import Foundation
import SafariServices
import shared
import SwiftUI
import UIKit

// task #471: scene plumbing for iPad multi-window.
//
// Declared via UIApplicationSceneManifest in Info.plist (class name
// `$(PRODUCT_MODULE_NAME).RaftIosSceneDelegate`). One instance exists per
// UISceneSession; each registers its RaftIosShellContext in
// RaftIosShellWindowRegistry so ContentView and the contract can resolve the
// owning shell identity. With a single scene the context stays
// "legacy-app-shell" — identical to the pre-scene behavior.
//
// Because the manifest names a custom scene delegate, SwiftUI no longer creates
// the window for `WindowGroup` — willConnectTo installs the UIHostingController
// itself.
final class RaftIosSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    /// Shell identity resolved for this scene. ContentView reaches it through
    /// the registry keyed by session.persistentIdentifier.
    let shellContext = RaftIosShellContext()

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        if let activity = connectionOptions.userActivities.first(where: {
            $0.activityType == RaftIosShellWindowContract.shellUserActivityType
        }), let context = RaftIosShellWindowContract.context(from: activity) {
            shellContext.apply(context)
            RaftIosDiagnosticsRecord(
                "info",
                "RaftIosShellWindow",
                "scene_identity_resolved source=user_activity shell=\(context.shellInstanceId)"
            )
        }
        RaftIosShellWindowRegistry.register(session: session, context: shellContext)
        RaftIosTopBandDiagnostics.install()

        // With a custom scene delegate the App's WindowGroup is inert; the
        // delegate owns window creation.
        if let windowScene = scene as? UIWindowScene {
            let window = UIWindow(windowScene: windowScene)
            window.rootViewController = UIHostingController(rootView: ContentView())
            // Settings > Appearance > Theme: an explicit Light/Dark choice overrides the OS
            // style for the whole window (keyboards, system menus, the native tab bar);
            // System leaves it unspecified. The dark canvas behind the first frame avoids a
            // white flash.
            window.backgroundColor = UIColor(raftArgb: RaftAppAppearanceRuntime.shared.canvasArgb)
            window.overrideUserInterfaceStyle = UIUserInterfaceStyle(
                rawValue: Int(RaftAppAppearanceRuntime.shared.hostStyleCode)
            ) ?? .unspecified
            self.window = window
            RaftIosAppearanceHost.attach()
            window.makeKeyAndVisible()
        }

        // Product analytics: a launch for a URL or a link is not a plain launcher open.
        if !connectionOptions.urlContexts.isEmpty || connectionOptions.userActivities.contains(where: {
            $0.activityType != RaftIosShellWindowContract.shellUserActivityType
        }) {
            RaftIosProductEventsHost.shared.noteOtherLaunch()
        }
        for context in connectionOptions.urlContexts {
            RaftIosOpenURL.accept(
                context.url,
                shellInstanceId: shellInstanceId(for: scene),
                source: "scene_connect"
            )
        }
        if let response = connectionOptions.notificationResponse {
            let content = response.notification.request.content
            RaftIosPushHost.acceptSystemNotificationOpen(
                userInfo: content.userInfo,
                categoryIdentifier: content.categoryIdentifier,
                shellInstanceId: shellInstanceId(for: scene),
                source: "scene_connect"
            )
        }
        // Cold launch from a Universal Link (a Raft message link).
        for activity in connectionOptions.userActivities
        where activity.activityType == NSUserActivityTypeBrowsingWeb {
            acceptBrowsingWebActivity(activity, source: "scene_connect")
        }
        if let shortcutItem = connectionOptions.shortcutItem {
            RaftIosDesktopShortcutHost.acceptShortcutItem(
                shortcutItem,
                shellInstanceId: shellInstanceId(for: scene),
                source: "scene_connect"
            )
        }
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        for context in URLContexts {
            RaftIosOpenURL.accept(
                context.url,
                shellInstanceId: shellInstanceId(for: scene),
                source: "scene_open_url"
            )
        }
    }

    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        if userActivity.activityType == NSUserActivityTypeBrowsingWeb {
            acceptBrowsingWebActivity(userActivity, source: "scene_continue")
            return
        }
        guard let context = RaftIosShellWindowContract.context(from: userActivity) else {
            return
        }
        shellContext.apply(context)
        RaftIosDiagnosticsRecord(
            "info",
            "RaftIosShellWindow",
            "scene_identity_updated source=continue_activity shell=\(context.shellInstanceId)"
        )
    }

    /// #proj-mobile #26: a Universal Link on app.raft.build / open.raft.build (or the Developer
    /// environment's web host). The shared inbox keeps a message link until the app shell has a
    /// ready session (through sign-in), then opens it through the same window navigation queue as
    /// a notification tap. Anything else the AASA routed here goes back to the web, in an in-app
    /// Safari view: `UIApplication.open` on our own Universal Link could route it straight back.
    private func acceptBrowsingWebActivity(_ activity: NSUserActivity, source: String) {
        guard let url = activity.webpageURL else { return }
        if RaftMessageLinkIngress.shared.accept(url: url.absoluteString, source: "ios_\(source)", configuredWebUrl: nil) {
            RaftIosDiagnosticsRecord("info", "RaftMessageLink", "universal_link_accepted source=\(source)")
            return
        }
        RaftIosDiagnosticsRecord("info", "RaftMessageLink", "universal_link_not_claimed source=\(source)")
        guard url.scheme == "https" else { return }
        // The sign-up email of an account created in this app's web sign-in, naming the request
        // the app is still waiting for: reopen it in a new web sign-in sheet (replacing the one
        // left on "check your email"; the pending request survives). It shares that sheet's
        // browser session, so the web verifies, finishes the pending sign-in and calls back here;
        // the default browser would have no session and ask to sign in again. Any other request
        // (a Raft Desktop sign-up, an expired one) goes to in-app Safari below.
        if RaftMessageLinkIngress.shared.resumesPendingAppLogin(url: url.absoluteString, configuredWebUrl: nil) {
            DispatchQueue.main.async { [weak self] in
                RaftIosMobileOAuthHost.shared.openAppLogin(url: url.absoluteString, presentingWindow: self?.window) { result in
                    let started = (result["status"] as? String) == "started"
                    RaftIosDiagnosticsRecord("info", "SlockIosAppLogin", "verification_link_resume source=\(source) started=\(started)")
                    if !started { self?.presentInAppSafari(url) }
                }
            }
            return
        }
        DispatchQueue.main.async { [weak self] in
            self?.presentInAppSafari(url)
        }
    }

    private func presentInAppSafari(_ url: URL) {
        guard let root = window?.rootViewController else { return }
        var presenter = root
        while let presented = presenter.presentedViewController {
            presenter = presented
        }
        presenter.present(SFSafariViewController(url: url), animated: true)
    }

    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        RaftIosDesktopShortcutHost.acceptShortcutItem(
            shortcutItem,
            shellInstanceId: shellInstanceId(for: windowScene),
            source: "scene_perform_action"
        )
        completionHandler(true)
    }

    /// task #483: shell owning this scene, resolved live from the registry so a
    /// `scene(_:continue:)` retarget is honored; `shellContext` is the fallback.
    private func shellInstanceId(for scene: UIScene) -> String {
        RaftIosShellWindowRegistry.context(forSessionId: scene.session.persistentIdentifier)?
            .shellInstanceId ?? shellContext.shellInstanceId
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        let context = RaftIosShellWindowRegistry.unregister(session: scene.session)
        guard let shellInstanceId = context?.shellInstanceId,
              shellInstanceId != RaftIosShellWindowContract.legacyShellInstanceId else {
            return
        }
        // Only document shells get their shared-store scope released; the legacy
        // shell scope is process-wide and stays alive across scene drops.
        RaftShellStoreLaunchKt.releaseRaftShellStoreScope(
            shellInstanceId: shellInstanceId,
            serverId: context?.serverId
        )
        RaftIosDiagnosticsRecord(
            "info",
            "RaftIosShellWindow",
            "scene_released shell=\(shellInstanceId)"
        )
    }
}

/// Applies the app theme choice (`RaftAppAppearanceRuntime`) to every window of the app.
enum RaftIosAppearanceHost {
    static func attach() {
        RaftAppAppearanceRuntime.shared.setHostListener { code in
            let style = UIUserInterfaceStyle(rawValue: Int(truncating: code)) ?? .unspecified
            let canvas = UIColor(raftArgb: RaftAppAppearanceRuntime.shared.canvasArgb)
            for scene in UIApplication.shared.connectedScenes {
                guard let windowScene = scene as? UIWindowScene else { continue }
                for window in windowScene.windows {
                    window.overrideUserInterfaceStyle = style
                    window.backgroundColor = canvas
                }
            }
            NotificationCenter.default.post(name: didChangeNotification, object: nil)
        }
    }

    /// Posted after every theme choice is applied to the windows.
    static let didChangeNotification = Notification.Name("RaftIosAppearanceDidChange")

    /// The app's explicit Light/Dark choice, or `.unspecified` to follow the system.
    static var currentStyle: UIUserInterfaceStyle {
        UIUserInterfaceStyle(rawValue: Int(RaftAppAppearanceRuntime.shared.hostStyleCode)) ?? .unspecified
    }
}

extension UIColor {
    /// `0xAARRGGBB` as produced by Compose `Color.toArgb()`.
    convenience init(raftArgb argb: Int32) {
        let value = UInt32(bitPattern: argb)
        self.init(
            red: CGFloat((value >> 16) & 0xFF) / 255.0,
            green: CGFloat((value >> 8) & 0xFF) / 255.0,
            blue: CGFloat(value & 0xFF) / 255.0,
            alpha: CGFloat((value >> 24) & 0xFF) / 255.0
        )
    }
}

/// A URL opened into the app (`raft://…`): a sign-in callback, else a Raft Desktop message link
/// (`raft:///s/{slug}/channel/{cid}?msg={mid}`), which the shared ingress turns into the Developer
/// environment's web link and queues like a Universal Link. Anything else is ignored, as before.
enum RaftIosOpenURL {
    static func accept(_ url: URL, shellInstanceId: String? = nil, source: String) {
        if RaftIosMobileOAuthHost.shared.acceptCallbackURL(url, shellInstanceId: shellInstanceId, source: source) {
            return
        }
        let accepted = RaftMessageLinkIngress.shared.accept(
            url: url.absoluteString,
            source: "ios_\(source)",
            configuredWebUrl: nil
        )
        RaftIosDiagnosticsRecord(
            "info",
            "RaftMessageLink",
            "app_scheme_link_\(accepted ? "accepted" : "not_claimed") source=\(source)"
        )
    }
}
