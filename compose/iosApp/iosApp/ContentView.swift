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
        if let scene = RaftNativeNavProto.demoScene {
            // PROTOTYPE: login-free native-navigation demo stack (see RaftNativeNavProtoDemo).
            RaftNativeNavProtoDemoView(scene: scene)
                .ignoresSafeArea()
        } else if let fixture = ContentView.visualFixtureLaunch() {
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
                RaftIosOpenURL.accept(
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

// MARK: - PROTOTYPE: fully native navigation (agent/lagoon/ios-native-nav-proto, not for merge)
//
// artin, 2026-10-30: "我就想知道全原生是什么感觉". Pages listed in `RaftNativeNavProto` get the real
// UINavigationBar (system Back with the previous title, Liquid Glass bar buttons, the title
// published by the page's shared head) and the system interactive pop gesture instead of
// FDFullscreenPopGesture. `RAFT_NATIVE_NAV_PROTO=0` in the launch environment switches the
// prototype off (today's behaviour) in the same build, for side-by-side captures.
//
// `RAFT_PROTO_SCENE=<home|thread|members|midswipe|video>` launches a login-free demo stack built
// from visual-fixture pages (needs -PslockVisualFixtures=true): Home (tab root) -> #design ->
// Members. It prints `RAFT_PROTO_READY scene=<scene>` when the frame to capture is on screen
// (`RAFT_PROTO_DONE` after the video script).

let RaftNativeNavBarHostIdPageDataKeyName = "slockNativeNavBarHostId"

@objc final class RaftNativeNavProto: NSObject {
    /// On by default on this branch; `RAFT_NATIVE_NAV_PROTO=0` restores today's behaviour.
    @objc static let enabled: Bool =
        ProcessInfo.processInfo.environment["RAFT_NATIVE_NAV_PROTO"]?.trimmingCharacters(in: .whitespaces) != "0"

    /// Pages whose head is hooked (shared RaftTopPageHeader / ThreadPageHeader publish to the bar).
    private static let nativePageNames: Set<String> = [
        "compose_native_nav_proto_home",
        "compose_native_nav_proto_thread",
        "compose_channel_members_parity_visual",
        "compose_thread",
        "compose_thread_members",
    ]

    static func usesNativeNavBar(pageName: String) -> Bool {
        enabled && nativePageNames.contains(pageName)
    }

    static var demoScene: String? {
        let scene = ProcessInfo.processInfo.environment["RAFT_PROTO_SCENE"]?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return scene.isEmpty ? nil : scene
    }
}

/// Puts what the page's shared head publishes into the real navigation item.
final class RaftNativeNavBarHost: NSObject, RaftNativeNavBarListener {
    private weak var controller: UIViewController?
    private let bridge: RaftNativeNavBarBridge
    private let hostId: String

    init(controller: UIViewController, bridge: RaftNativeNavBarBridge, hostId: String) {
        self.controller = controller
        self.bridge = bridge
        self.hostId = hostId
        super.init()
        // Pushed pages never take the large title (only the tab root does).
        controller.navigationItem.largeTitleDisplayMode = .never
    }

    func attach() {
        bridge.attachListener(listener: self)
    }

    deinit {
        bridge.detachListener()
        RaftNativeNavBarRegistry.shared.unregister(hostId: hostId, bridge: bridge)
    }

    func onNativeNavBarChanged(title: String, subtitle: String?, actionsJson: String) {
        guard let controller else { return }
        // The navigation item that counts is the one of the stack's child (a tab root's tab bar
        // controller owns its own items and large title; the page only names it).
        var owner: UIViewController = controller
        while let parent = owner.parent, !(parent is UINavigationController) {
            owner = parent
        }
        if owner !== controller {
            if (owner as? UITabBarController)?.selectedViewController === controller {
                owner.navigationItem.title = title
            }
            return
        }
        let item = controller.navigationItem
        item.title = title
        // iOS 26 navigation subtitles; looked up at run time so the prototype builds on any SDK.
        if item.responds(to: NSSelectorFromString("setSubtitle:")) {
            item.setValue(subtitle, forKey: "subtitle")
        }
        item.largeTitleDisplayMode = .never
        let actions = RaftNativeNavBarHost.parseActions(actionsJson)
        var barItems: [UIBarButtonItem] = []
        if actions.count > 1 {
            // Everything after the first action lives in the ⋯ menu (rightmost).
            let menu = UIMenu(children: actions.dropFirst().map { action in
                UIAction(title: action.label, image: UIImage(systemName: action.symbol)) { [weak self] _ in
                    _ = self?.bridge.actionTapped(id: action.id)
                }
            })
            let more = UIBarButtonItem(title: nil, image: UIImage(systemName: "ellipsis"), primaryAction: nil, menu: menu)
            more.accessibilityLabel = "More"
            barItems.append(more)
        }
        if let first = actions.first {
            let button = UIBarButtonItem(
                title: nil,
                image: UIImage(systemName: first.symbol),
                primaryAction: UIAction { [weak self] _ in _ = self?.bridge.actionTapped(id: first.id) },
                menu: nil
            )
            button.accessibilityLabel = first.label
            barItems.append(button)
        }
        item.rightBarButtonItems = barItems
    }

    private struct Action {
        let id: String
        let label: String
        let symbol: String
    }

    private static func parseActions(_ json: String) -> [Action] {
        guard let rows = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [[String: Any]] else {
            return []
        }
        return rows.compactMap { row in
            guard let id = row["id"] as? String, let symbol = row["systemSymbol"] as? String else { return nil }
            return Action(id: id, label: row["label"] as? String ?? id, symbol: symbol)
        }
    }
}

/// The demo's tab root: Home (a CMP page) plus placeholder tabs. With the prototype on, the root
/// takes the large native title, the server switcher as a leading menu and the bell as a trailing
/// button; off, the Home page draws its own Compose head (server pill + bell) as today.
final class RaftNativeNavProtoRootController: UITabBarController, UITabBarControllerDelegate {
    private let native: Bool

    init(native: Bool) {
        self.native = native
        super.init(nibName: nil, bundle: nil)
        let home = RaftPageControllerFactory.controller(
            pageName: "compose_native_nav_proto_home",
            pageData: ["pageName": "compose_native_nav_proto_home"]
        )
        home.tabBarItem = UITabBarItem(title: "Home", image: UIImage(systemName: "house"), selectedImage: UIImage(systemName: "house.fill"))
        viewControllers = [
            home,
            Self.placeholder("Activity", "waveform.path.ecg.rectangle"),
            Self.placeholder("Tasks", "checkmark.square"),
            Self.placeholder("Members", "person.2"),
        ]
        delegate = self
        // FD's will-appear hook shows / hides the bar by this when the root reappears.
        fd_prefersNavigationBarHidden = !native
        if native {
            navigationItem.title = "Home"
            navigationItem.largeTitleDisplayMode = .always
            let servers = UIMenu(title: "Servers", children: [
                UIAction(title: "Raft Design", image: UIImage(systemName: "checkmark")) { _ in },
                UIAction(title: "Botiverse") { _ in },
                UIAction(title: "Create Server…", image: UIImage(systemName: "plus")) { _ in },
            ])
            let switcher = UIBarButtonItem(title: "Raft Design", image: nil, primaryAction: nil, menu: servers)
            switcher.accessibilityLabel = "Switch server"
            navigationItem.leftBarButtonItem = switcher
            let bell = UIBarButtonItem(
                title: nil,
                image: UIImage(systemName: "bell"),
                primaryAction: UIAction { _ in },
                menu: nil
            )
            bell.accessibilityLabel = "Notifications"
            navigationItem.rightBarButtonItem = bell
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(!native, animated: animated)
    }

    func tabBarController(_ tabBarController: UITabBarController, didSelect viewController: UIViewController) {
        if native {
            navigationItem.title = viewController.tabBarItem.title
        }
    }

    private static func placeholder(_ title: String, _ symbol: String) -> UIViewController {
        let controller = UIViewController()
        controller.view.backgroundColor = .systemBackground
        let label = UILabel()
        label.text = "\(title) (prototype placeholder)"
        label.textColor = .secondaryLabel
        label.translatesAutoresizingMaskIntoConstraints = false
        controller.view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: controller.view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: controller.view.centerYAnchor),
        ])
        controller.tabBarItem = UITabBarItem(
            title: title,
            image: UIImage(systemName: symbol),
            selectedImage: UIImage(systemName: "\(symbol).fill")
        )
        return controller
    }
}

/// A pan whose state and translation the demo script sets: handed to UIKit's own interactive pop
/// handler (the private target FDFullscreenPopGesture also forwards to), it drives the same
/// interactive transition a finger swipe from the left edge does, without a finger.
private final class RaftProtoScriptedPan: UIPanGestureRecognizer {
    var scriptedState: UIGestureRecognizer.State = .possible
    var scriptedTranslationX: CGFloat = 0
    var scriptedVelocityX: CGFloat = 0

    override var state: UIGestureRecognizer.State {
        get { scriptedState }
        set { scriptedState = newValue }
    }

    override func translation(in view: UIView?) -> CGPoint { CGPoint(x: scriptedTranslationX, y: 0) }
    override func velocity(in view: UIView?) -> CGPoint { CGPoint(x: scriptedVelocityX, y: 0) }
    override func location(in view: UIView?) -> CGPoint { CGPoint(x: 8 + scriptedTranslationX, y: 420) }
    override func location(ofTouch touchIndex: Int, in view: UIView?) -> CGPoint { location(in: view) }
    override var numberOfTouches: Int { 1 }
}

enum RaftNativeNavProtoDemo {
    static func marker(_ text: String) {
        print(text)
        fflush(stdout)
    }

    private static func push(_ pageName: String, on nav: UINavigationController, animated: Bool) {
        let page = RaftPageControllerFactory.controller(pageName: pageName, pageData: ["pageName": pageName])
        nav.pushViewController(page, animated: animated)
    }

    private static func after(_ seconds: Double, _ work: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    /// Swipes the top page back with UIKit's interactive pop: to [progress] (0...1) over
    /// [duration] seconds, then finishes (or holds there when [finish] is false).
    static func scriptedSwipeBack(
        _ nav: UINavigationController,
        progress: CGFloat,
        duration: Double,
        finish: Bool,
        completion: @escaping () -> Void = {}
    ) {
        guard let pop = nav.interactivePopGestureRecognizer,
              let targets = pop.value(forKey: "targets") as? [NSObject],
              let target = targets.first?.value(forKey: "target") as? NSObject
        else {
            marker("RAFT_PROTO_WARN scripted_swipe_unavailable")
            completion()
            return
        }
        let handler = NSSelectorFromString("handleNavigationTransition:")
        let pan = RaftProtoScriptedPan(target: nil, action: nil)
        pan.cancelsTouchesInView = false
        nav.view.addGestureRecognizer(pan)
        let width = nav.view.bounds.width
        pan.scriptedState = .began
        target.perform(handler, with: pan)
        let steps = max(1, Int(duration * 60))
        func step(_ index: Int) {
            if index > steps {
                if finish {
                    pan.scriptedState = .ended
                    pan.scriptedVelocityX = 900
                    target.perform(handler, with: pan)
                    after(0.8) {
                        nav.view.removeGestureRecognizer(pan)
                        completion()
                    }
                } else {
                    completion()
                }
                return
            }
            let t = CGFloat(index) / CGFloat(steps)
            // ease-out, like a finger slowing down
            let eased = 1 - (1 - t) * (1 - t)
            pan.scriptedTranslationX = width * progress * eased
            pan.scriptedVelocityX = 300
            pan.scriptedState = .changed
            target.perform(handler, with: pan)
            after(1.0 / 60.0) { step(index + 1) }
        }
        after(1.0 / 60.0) { step(1) }
    }

    static func run(scene: String, nav: UINavigationController) {
        let mode = RaftNativeNavProto.enabled ? "native" : "now"
        marker("RAFT_PROTO_START scene=\(scene) mode=\(mode)")
        switch scene {
        case "home":
            after(4) { marker("RAFT_PROTO_READY scene=home") }
        case "thread":
            after(1) { push("compose_native_nav_proto_thread", on: nav, animated: false) }
            after(5) { marker("RAFT_PROTO_READY scene=thread") }
        case "members":
            after(1) {
                push("compose_native_nav_proto_thread", on: nav, animated: false)
                push("compose_channel_members_parity_visual", on: nav, animated: false)
            }
            after(5) { marker("RAFT_PROTO_READY scene=members") }
        case "midswipe":
            after(1) { push("compose_native_nav_proto_thread", on: nav, animated: false) }
            after(5) {
                scriptedSwipeBack(nav, progress: 0.45, duration: 0.6, finish: false) {
                    after(0.8) { marker("RAFT_PROTO_READY scene=midswipe") }
                }
            }
        case "video":
            after(3) { push("compose_native_nav_proto_thread", on: nav, animated: true) }
            after(6.5) { push("compose_channel_members_parity_visual", on: nav, animated: true) }
            after(10) {
                scriptedSwipeBack(nav, progress: 0.75, duration: 1.1, finish: true) {
                    after(2) {
                        scriptedSwipeBack(nav, progress: 0.75, duration: 1.1, finish: true) {
                            after(2.5) { marker("RAFT_PROTO_DONE scene=video") }
                        }
                    }
                }
            }
        default:
            marker("RAFT_PROTO_WARN unknown_scene=\(scene)")
        }
    }
}

struct RaftNativeNavProtoDemoView: UIViewControllerRepresentable {
    var scene: String

    func makeUIViewController(context: Context) -> UINavigationController {
        let root = RaftNativeNavProtoRootController(native: RaftNativeNavProto.enabled)
        let nav = UINavigationController(rootViewController: root)
        if RaftNativeNavProto.enabled {
            nav.navigationBar.prefersLargeTitles = true
        } else {
            nav.setNavigationBarHidden(true, animated: false)
        }
        RaftNativeNavProtoDemo.run(scene: scene, nav: nav)
        return nav
    }

    func updateUIViewController(_ uiViewController: UINavigationController, context: Context) {}
}
