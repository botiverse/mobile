import Foundation
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
            SlockIosDiagnosticsRecord(
                "info",
                "RaftIosShellWindow",
                "scene_identity_resolved source=user_activity shell=\(context.shellInstanceId)"
            )
        }
        RaftIosShellWindowRegistry.register(session: session, context: shellContext)

        // With a custom scene delegate the App's WindowGroup is inert; the
        // delegate owns window creation.
        if let windowScene = scene as? UIWindowScene {
            let window = UIWindow(windowScene: windowScene)
            window.rootViewController = UIHostingController(rootView: ContentView())
            self.window = window
            window.makeKeyAndVisible()
        }

        for context in connectionOptions.urlContexts {
            _ = SlockIosMobileOAuthHost.shared.acceptCallbackURL(
                context.url,
                source: "scene_connect"
            )
        }
        if let response = connectionOptions.notificationResponse {
            let content = response.notification.request.content
            SlockIosPushHost.acceptSystemNotificationOpen(
                userInfo: content.userInfo,
                categoryIdentifier: content.categoryIdentifier,
                source: "scene_connect"
            )
        }
        if let shortcutItem = connectionOptions.shortcutItem {
            SlockIosDesktopShortcutHost.acceptShortcutItem(shortcutItem, source: "scene_connect")
        }
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        for context in URLContexts {
            _ = SlockIosMobileOAuthHost.shared.acceptCallbackURL(context.url, source: "scene_open_url")
        }
    }

    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        guard let context = RaftIosShellWindowContract.context(from: userActivity) else {
            return
        }
        shellContext.apply(context)
        SlockIosDiagnosticsRecord(
            "info",
            "RaftIosShellWindow",
            "scene_identity_updated source=continue_activity shell=\(context.shellInstanceId)"
        )
    }

    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        SlockIosDesktopShortcutHost.acceptShortcutItem(shortcutItem, source: "scene_perform_action")
        completionHandler(true)
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        let context = RaftIosShellWindowRegistry.unregister(session: scene.session)
        guard let shellInstanceId = context?.shellInstanceId,
              shellInstanceId != RaftIosShellWindowContract.legacyShellInstanceId else {
            return
        }
        // Only document shells get their shared-store scope released; the legacy
        // shell scope is process-wide and stays alive across scene drops.
        SlockShellStoreLaunchKt.releaseSlockShellStoreScope(
            shellInstanceId: shellInstanceId,
            serverId: context?.serverId
        )
        SlockIosDiagnosticsRecord(
            "info",
            "RaftIosShellWindow",
            "scene_released shell=\(shellInstanceId)"
        )
    }
}
