import Foundation
import SwiftUI
import UIKit
import shared

struct ContentView: View {
    @SwiftUI.StateObject private var oauthRouteStore = SlockIosOAuthRouteStore.shared
    // task #471: shell identity of the UIWindowScene hosting this view. Starts
    // at the legacy id; ShellContextResolver replaces it once the scene's
    // RaftIosSceneDelegate context is known.
    @SwiftUI.State private var shellContext: RaftIosShellContext

    init(shellContext: RaftIosShellContext = RaftIosShellContext()) {
        _shellContext = SwiftUI.State(initialValue: shellContext)
    }

    var body: some View {
        // task #419: the visual-testing capture harness launches the app with a
        // fixture page injected via env (host side sets SIMCTL_CHILD_… so the app
        // sees SLOCK_VISUAL_FIXTURE_PAGE). When present, render that fixture page
        // as the root instead of the app shell so the Simulator screenshot
        // captures the fixture. Same pageName+pageData contract as Android.
        if let fixture = ContentView.visualFixtureLaunch() {
            KuiklyRenderViewPage(pageName: fixture.pageName, data: fixture.data)
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
            ShellHostView(oauthRouteStore: oauthRouteStore, shellContext: shellContext)
                .background(ShellContextResolver(context: $shellContext))
        }
    }

    fileprivate static func hostPageData(
        _ destination: SlockIosOAuthDestination,
        shellContext: RaftIosShellContext
    ) -> [String: Any] {
        var data = destination.pageData
        data["pageName"] = destination.pageName
        data["slockSocialAuthProviders"] = slockIosOAuthProviders
        // Browser web login is available; the shared gate decides whether it replaces the login page.
        data["slockWebLoginSupported"] = true
        // Shell identity contract (shared/SlockShellStoreLaunch.kt). A blank or
        // absent slockShellInstanceId falls back to Legacy anyway, so injecting
        // "legacy-app-shell" for the single-window path is byte-equivalent.
        // slockShellServerId is always written — even as null — so a serialized
        // null never falls back to a feature route's legacy "serverId" field.
        if let sceneId = shellContext.sceneId {
            data["slockSceneId"] = sceneId
        }
        data["slockShellInstanceId"] = shellContext.shellInstanceId
        data["slockShellServerId"] = shellContext.serverId ?? NSNull()
        if let owner = shellContext.routeOwner {
            data["slockRouteEnvironmentId"] = owner.environmentId
            data["slockRouteAccountId"] = owner.accountId
            data["slockRouteServerId"] = owner.serverId
        }
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

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}

// task #471: the app-shell host for one window. Lives outside ContentView so
// the resolved RaftIosShellContext is observed (@ObservedObject) — a scene
// arriving via scene(_:continue:) mutates the context's @Published fields and
// must re-render with the new shell identity + route owner.
private struct ShellHostView: View {
    @SwiftUI.ObservedObject var oauthRouteStore: SlockIosOAuthRouteStore
    @SwiftUI.ObservedObject var shellContext: RaftIosShellContext

    var body: some View {
        let destination = oauthRouteStore.destination
        KuiklyRenderViewPage(
            pageName: destination.pageName,
            data: ContentView.hostPageData(destination, shellContext: shellContext)
        )
            // Rebuild when either the route or the owning shell changes.
            .id("\(destination.revision)|\(shellContext.sceneId ?? "")|\(shellContext.shellInstanceId)")
            .ignoresSafeArea()
            .onOpenURL { url in
                _ = SlockIosMobileOAuthHost.shared.acceptCallbackURL(url, source: "swiftui_open_url")
            }
            .onAppear {
                SlockIosMobileOAuthHost.shared.resumeDeferredCallbackIfNeeded()
            }
    }
}

// task #471: zero-size probe that walks to the hosting UIWindowScene and binds
// this view to that scene's RaftIosShellContext (registered by
// RaftIosSceneDelegate under session.persistentIdentifier). Falls back to the
// legacy shell when no scene is attached (e.g. previews).
private struct ShellContextResolver: UIViewRepresentable {
    @SwiftUI.Binding var context: RaftIosShellContext

    func makeUIView(context: Context) -> ProbeView {
        ProbeView(onResolve: { resolved in
            DispatchQueue.main.async {
                self.context = resolved
            }
        })
    }

    func updateUIView(_ uiView: ProbeView, context: Context) {
        uiView.resolveIfNeeded()
    }

    final class ProbeView: UIView {
        private let onResolve: (RaftIosShellContext) -> Void
        private var resolvedSessionId: String?

        init(onResolve: @escaping (RaftIosShellContext) -> Void) {
            self.onResolve = onResolve
            super.init(frame: .zero)
            isHidden = true
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) {
            fatalError("init(coder:) is not supported")
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            resolveIfNeeded()
        }

        func resolveIfNeeded() {
            guard let session = window?.windowScene?.session,
                  session.persistentIdentifier != resolvedSessionId else {
                return
            }
            resolvedSessionId = session.persistentIdentifier
            let resolved =
                RaftIosShellWindowRegistry.context(forSessionId: session.persistentIdentifier)
                    ?? RaftIosShellContext()
            onResolve(resolved)
        }
    }
}
