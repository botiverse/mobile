import Foundation
import SwiftUI
import shared

struct ContentView: View {
    @StateObject private var permalinkHost = SlockIosPermalinkSceneHost(
        visibilitySink: ContentView.publishSceneVisibility
    )
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var oauthRouteStore = SlockIosOAuthRouteStore.shared

    var body: some View {
        // task #419: the visual-testing capture harness launches the app with a
        // fixture page injected via env (host side sets SIMCTL_CHILD_… so the app
        // sees SLOCK_VISUAL_FIXTURE_PAGE). When present, render that fixture page
        // as the root instead of the app shell so the Simulator screenshot
        // captures the fixture. Same pageName+pageData contract as Android.
        Group {
            if let fixture = ContentView.visualFixtureLaunch() {
                KuiklyRenderViewPage(pageName: fixture.pageName, data: fixturePageData(fixture))
                    .ignoresSafeArea()
                    // task #419: hide the status bar so the capture screenshot has no
                    // clock/wifi/battery overlaying the fixture (the changing clock is
                    // the main diff-noise source). This is applied at the SwiftUI layer
                    // because the app uses the SwiftUI App lifecycle — the window's
                    // UIHostingController honors this, whereas a UIKit prefers-hidden on
                    // a VC embedded via a representable never gets consulted. The home
                    // indicator has no SwiftUI control before iOS 16 (deployment target
                    // is 14.1); left visible for now (fixed bottom bar, low diff impact).
                    .statusBar(hidden: true)
                    .onAppear {
                        // Settle signal for the capture job: it reads this line via
                        // `simctl launch --console` (+ a small buffer) before taking
                        // the screenshot, so it never catches the launch splash.
                        print("SLOCK_VISUAL_FIXTURE_READY page=\(fixture.pageName)")
                    }
            } else {
                let destination = oauthRouteStore.destination
                KuiklyRenderViewPage(
                    pageName: destination.pageName,
                    data: hostPageData(destination)
                )
                    .id(destination.revision)
                    .ignoresSafeArea()
                    .onOpenURL { url in
                        _ = SlockIosMobileOAuthHost.shared.acceptCallbackURL(url, source: "swiftui_open_url")
                    }
                    .onAppear {
                        SlockIosMobileOAuthHost.shared.resumeDeferredCallbackIfNeeded()
                    }
            }
        }
        .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
            permalinkHost.accept(activity)
        }
        .onAppear { permalinkHost.setVisible(scenePhase == .active) }
        .onChange(of: scenePhase) { phase in
            permalinkHost.setVisible(phase == .active)
        }
    }

    private static func publishSceneVisibility(scope: String, visible: Bool, epoch: Int) {
        SlockHostVisibilityKt.updateSlockHostVisibility(shellInstanceId: scope, visible: visible)
        NotificationCenter.default.post(
            name: Notification.Name(GeneratedRealtimeContract.Notify.eventName),
            object: nil,
            userInfo: [
                GeneratedRealtimeContract.PayloadKey.action: "appLifecycle",
                "event": visible ? "show" : "hide", "foregroundEpoch": epoch,
                "pageName": "compose_slock_app_shell", "shellInstanceId": scope,
            ]
        )
    }

    private func fixturePageData(_ fixture: VisualFixtureLaunch) -> [String: Any] {
        var data = fixture.data
        if fixture.pageName == "compose_slock_app_shell" {
            data["slockShellInstanceId"] = permalinkHost.bindRoot(revision: "fixture")
        }
        return data
    }

    private func hostPageData(_ destination: SlockIosOAuthDestination) -> [String: Any] {
        let scope = permalinkHost.bindRoot(revision: destination.revision)
        var data = destination.pageData
        data["pageName"] = destination.pageName
        data["slockSocialAuthProviders"] = slockIosOAuthProviders
        // Authentication roots retain their own receiver ownership for cancellation.
        data["slockShellInstanceId"] = scope
        if destination.pageName == "compose_slock_app_shell" {
            data["slockNativeTabBarRenderer"] = Self.appTabBarRenderer()
            data["slockShellInstanceId"] = scope
        }
        return data
    }

    private static func appTabBarRenderer() -> String {
        if #available(iOS 26.0, *) {
            return "iosLiquidGlass"
        }
        return "sharedCompose"
    }

    private struct VisualFixtureLaunch {
        let pageName: String
        let data: [String: Any]
    }

    private static func visualFixtureLaunch() -> VisualFixtureLaunch? {
        let env = ProcessInfo.processInfo.environment
        guard let rawPage = env["SLOCK_VISUAL_FIXTURE_PAGE"] else { return nil }
        let pageName = rawPage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !pageName.isEmpty else { return nil }
        // Default pageData mirrors Android's convention ({"pageName": <name>});
        // an explicit SLOCK_VISUAL_FIXTURE_PAGE_DATA JSON object overrides it.
        var data: [String: Any] = ["pageName": pageName]
        if let rawData = env["SLOCK_VISUAL_FIXTURE_PAGE_DATA"],
           let bytes = rawData.data(using: .utf8),
           let parsed = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] {
            data = parsed
        }
        return VisualFixtureLaunch(pageName: pageName, data: data)
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
