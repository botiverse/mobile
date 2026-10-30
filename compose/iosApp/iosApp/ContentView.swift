import Foundation
import SwiftUI
import UIKit
import shared

struct ContentView: View {
    @SwiftUI.StateObject private var oauthRouteStore = RaftIosOAuthRouteStore.shared
    // task #471: shell identity of the UIWindowScene hosting this view. Starts
    // at the legacy id; ShellContextResolver replaces it once the scene's
    // RaftIosSceneDelegate context is known.
    @SwiftUI.State private var shellContext = RaftIosShellContext()

    var body: some View {
        // task #419: the visual-testing capture harness launches the app with a
        // fixture page injected via env (host side sets SIMCTL_CHILD_… so the app
        // sees SLOCK_VISUAL_FIXTURE_PAGE). When present, render that fixture page
        // as the root instead of the app shell so the Simulator screenshot
        // captures the fixture. Same pageName+pageData contract as Android.
        if let fixture = ContentView.visualFixtureLaunch() {
            RaftPageView(pageName: fixture.pageName, data: fixture.data)
                .ignoresSafeArea()
                // task #419: hide the status bar so the capture screenshot has no
                // clock/wifi/battery overlaying the fixture (the changing clock is
                // the main diff-noise source). This is applied at the SwiftUI layer
                // because the app uses the SwiftUI App lifecycle — the window's
                // UIHostingController honors this, whereas a UIKit prefers-hidden on
                // a VC embedded via a representable never gets consulted. The home
                // indicator has no SwiftUI control before iOS 16 (deployment target
                // is 14.1); left visible for now (fixed bottom bar, low diff impact).
                .onAppear {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 3) { RaftProbeViewDump.run() }
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
        _ destination: RaftIosOAuthDestination,
        shellContext: RaftIosShellContext
    ) -> [String: Any] {
        var data = destination.pageData
        data["pageName"] = destination.pageName
        data["slockSocialAuthProviders"] = raftIosOAuthProviders
        // Shell identity contract (shared/RaftShellStoreLaunch.kt). A blank or
        // absent slockShellInstanceId falls back to Legacy anyway, so injecting
        // "legacy-app-shell" for the single-window path is byte-equivalent.
        // slockShellServerId is always written — even as null — so a serialized
        // null never falls back to a feature route's legacy "serverId" field.
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

    /// Fallback renderer only: every app-shell page created through RaftPageControllerFactory
    /// gets the system UITabBarController (RaftIosTabBarController) on all iOS versions, and
    /// shared prefers that host bar over this param.
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
    @SwiftUI.ObservedObject var oauthRouteStore: RaftIosOAuthRouteStore
    @SwiftUI.ObservedObject var shellContext: RaftIosShellContext

    var body: some View {
        let destination = oauthRouteStore.destination
        // task #483: a destination stamped for another shell leaves this
        // window untouched — render the neutral app shell instead.
        let targetShellId = destination.shellInstanceId?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let effective =
            !targetShellId.isEmpty && targetShellId != shellContext.shellInstanceId
            ? RaftIosOAuthDestination(
                pageName: "compose_slock_app_shell",
                pageData: ["pageName": "compose_slock_app_shell"],
                revision: "shell-mismatch",
                shellInstanceId: nil
            )
            : destination
        RaftPageView(
            pageName: effective.pageName,
            data: ContentView.hostPageData(effective, shellContext: shellContext)
        )
            // Rebuild when either the route or the owning shell changes.
            .id("\(effective.revision)|\(shellContext.shellInstanceId)")
            .ignoresSafeArea()
            .onOpenURL { url in
                _ = RaftIosMobileOAuthHost.shared.acceptCallbackURL(
                    url,
                    shellInstanceId: shellContext.shellInstanceId,
                    source: "swiftui_open_url"
                )
            }
            .onAppear {
                RaftIosMobileOAuthHost.shared.resumeDeferredCallbackIfNeeded()
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


// PROBE (not for merge): prints every view or layer that could be a hairline near the top inset.
enum RaftProbeViewDump {
    static func run() {
        for scene in UIApplication.shared.connectedScenes {
            guard let ws = scene as? UIWindowScene else { continue }
            for window in ws.windows {
                let top = window.safeAreaInsets.top
                print("RAFT_VIEW_DUMP window=\(type(of: window)) bounds=\(window.bounds) safeTop=\(top) scale=\(window.screen.scale) statusBarHidden=\(ws.statusBarManager?.isStatusBarHidden ?? false)")
                walk(window, window: window, top: top, depth: 0)
            }
        }
    }

    private static func walk(_ view: UIView, window: UIWindow, top: CGFloat, depth: Int) {
        let f = view.convert(view.bounds, to: window)
        let near = abs(f.minY - top) < 3 || abs(f.maxY - top) < 3
        if depth < 12 || f.height <= 3 || near {
            print("RAFT_VIEW_DUMP v d=\(depth) \(type(of: view)) frame=\(f) hidden=\(view.isHidden) alpha=\(view.alpha) bg=\(String(describing: view.backgroundColor)) clips=\(view.clipsToBounds)")
        }
        for layer in view.layer.sublayers ?? [] where layer.delegate == nil {
            let lf = view.layer.convert(layer.frame, to: window.layer)
            if lf.height <= 3 || abs(lf.minY - top) < 3 || abs(lf.maxY - top) < 3 {
                print("RAFT_VIEW_DUMP l d=\(depth) owner=\(type(of: view)) \(type(of: layer)) frame=\(lf) hidden=\(layer.isHidden) opacity=\(layer.opacity) bg=\(String(describing: layer.backgroundColor))")
            }
        }
        for sub in view.subviews { walk(sub, window: window, top: top, depth: depth + 1) }
    }
}
