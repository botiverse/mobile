import Foundation
import SwiftUI
import shared

struct ContentView: View {
    @StateObject private var oauthRouteStore = SlockIosOAuthRouteStore.shared

    var body: some View {
        // task #419: the visual-testing capture harness launches the app with a
        // fixture page injected via env (host side sets SIMCTL_CHILD_… so the app
        // sees SLOCK_VISUAL_FIXTURE_PAGE). When present, render that fixture page
        // as the root instead of the app shell so the Simulator screenshot
        // captures the fixture. Same pageName+pageData contract as Android.
        if let fixture = ContentView.visualFixtureLaunch() {
            fixturePage(fixture)
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
                data: ContentView.hostPageData(destination)
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

    @ViewBuilder
    private func fixturePage(_ fixture: VisualFixtureLaunch) -> some View {
#if DEBUG
        if ProcessInfo.processInfo.environment["SLOCK_QA_HUMAN_REVISIT"] == "1",
           fixture.pageName == "compose_member_detail",
           let payload = fixture.data["payload"] as? [String: Any],
           payload["memberId"] as? String == "visual-human-long" {
            HumanFixtureRevisitHost(pageName: fixture.pageName, data: fixture.data)
        } else {
            KuiklyRenderViewPage(pageName: fixture.pageName, data: fixture.data)
        }
#else
        KuiklyRenderViewPage(pageName: fixture.pageName, data: fixture.data)
#endif
    }

    private static func hostPageData(_ destination: SlockIosOAuthDestination) -> [String: Any] {
        var data = destination.pageData
        data["pageName"] = destination.pageName
        data["slockSocialAuthProviders"] = slockIosOAuthProviders
        if destination.pageName == "compose_slock_app_shell" {
            data["slockNativeTabBarRenderer"] = appTabBarRenderer()
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

#if DEBUG
// QA branch only: exercise ordinary in-process host removal/recreation. This
// does not inject registry state or claim a pre-periodic-save interleaving.
private struct HumanFixtureRevisitHost: View {
    let pageName: String
    let data: [String: Any]
    @State private var opened = true
    @State private var visit = 1

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Human fixture • visit \(visit)")
                Spacer()
                Button(opened ? "Close fixture" : "Reopen fixture") {
                    if opened {
                        print("JONY_HUMAN_QA close_requested visit=\(visit)")
                        opened = false
                    } else {
                        visit += 1
                        print("JONY_HUMAN_QA reopen_requested visit=\(visit)")
                        opened = true
                    }
                }
                .accessibilityIdentifier("jonyHumanFixtureToggle")
            }
            .padding(.horizontal, 20)
            .frame(height: 44)
            if opened {
                KuiklyRenderViewPage(pageName: pageName, data: data)
                    .id(visit)
                    .onDisappear {
                        // View disappearance is only a host event; shared
                        // dispose_save and the next epoch prove the data path.
                        print("JONY_HUMAN_QA view_disappeared")
                    }
            } else {
                Text("Fixture closed; process retained")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }
}
#endif

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
