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
                // PROTOTYPE: the real-app capture script (RAFT_PROTO_REAL_SCENE only).
                .onAppear { RaftNativeNavProtoReal.startIfRequested() }
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

    /// Pages whose heads are mapped (every head they show publishes to the bar, every button of it
    /// is reachable there). Phase 2: the app shell (its Home / Activity / Tasks / Members / More
    /// tabs; Search keeps its own search-field head, the bar hides for it) and the pushed pages.
    private static let nativePageNames: Set<String> = [
        // App
        "compose_slock_app_shell",
        "compose_thread",
        "compose_thread_members",
        "compose_home_activity",
        "compose_home_saved",
        "compose_home_drafts",
        "compose_channel_settings",
        "compose_members",
        "compose_member_detail",
        "compose_settings_home",
        "compose_settings_account",
        "compose_settings_native_detail",
        "compose_settings_storage",
        "compose_settings_computers",
        "compose_settings_hands_feedback",
        // Login-free demo (visual fixtures)
        "compose_native_nav_proto_home",
        "compose_native_nav_proto_thread",
        "compose_channel_members_parity_visual",
        "compose_native_nav_proto_activity",
        "compose_native_nav_proto_tasks",
        "compose_native_nav_proto_more",
        "compose_native_nav_proto_settings_detail",
        "compose_native_nav_proto_saved",
        "compose_native_nav_proto_agent",
        "compose_native_nav_proto_channel_settings",
    ]

    /// iPhone only: the iPad / wide layouts keep their own top bar and panes.
    static func usesNativeNavBar(pageName: String) -> Bool {
        enabled && UIDevice.current.userInterfaceIdiom == .phone && nativePageNames.contains(pageName)
    }

    /// Pages whose title is large and collapses with their primary list (the tab root, lists).
    /// The conversation stays inline: its list is reversed (newest at the bottom), so a large
    /// title would only show after scrolling back to the first message (Messages is inline too).
    private static let largeTitlePageNames: Set<String> = [
        "compose_native_nav_proto_home",
        "compose_channel_members_parity_visual",
        "compose_thread_members",
    ]

    static func usesLargeTitle(pageName: String) -> Bool {
        largeTitleMirror && largeTitlePageNames.contains(pageName)
    }

    /// Off by default: runs 41118356672 / 41124126889 (iOS 27 simulator) showed UIKit does not
    /// track a content scroll view whose offset is written programmatically, even when it reports
    /// isTracking / isDragging: the bar height, the large-title view's frame / alpha / transform
    /// and the safe area stay put mid-collapse; past the range the title toggles to inline and on
    /// the way back the large row came back blank. A pushed page never got its large title at
    /// all (large-title view height 0). So lists use inline titles; `RAFT_PROTO_LARGE_TITLE=1`
    /// brings the scroll mirror back to reproduce that.
    static let largeTitleMirror: Bool =
        ProcessInfo.processInfo.environment["RAFT_PROTO_LARGE_TITLE"]?.trimmingCharacters(in: .whitespaces) == "1"


    /// In a capture with the prototype off, the same pages get a script-only bridge so the
    /// scripted scroll (and the real-app script's head actions) run in both modes.
    static func scriptsPage(pageName: String) -> Bool {
        (demoScene != nil || realScene != nil) && nativePageNames.contains(pageName)
    }

    /// `RAFT_PROTO_REAL_SCENE=<name>`: script the REAL signed-in app (with
    /// `RAFT_PROTO_FAKE_BACKEND=1`, on fabricated data); see RaftNativeNavProtoReal.
    static var realScene: String? {
        let scene = ProcessInfo.processInfo.environment["RAFT_PROTO_REAL_SCENE"]?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return scene.isEmpty ? nil : scene
    }

    static var demoScene: String? {
        let scene = ProcessInfo.processInfo.environment["RAFT_PROTO_SCENE"]?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return scene.isEmpty ? nil : scene
    }
}

/// Puts what the page's shared head publishes into the real navigation item, and mirrors the
/// page's primary Compose list into a hidden UIScrollView so UIKit's large title collapses with it.
final class RaftNativeNavBarHost: NSObject, RaftNativeNavBarListener {
    private weak var controller: UIViewController?
    let bridge: RaftNativeNavBarBridge
    private let hostId: String
    /// The page asks for a large title (tab root Home, the members list).
    private(set) var largeTitle: Bool

    // Mirror state
    private var mirror: RaftScrollMirror?
    private var expandedBarHeight: CGFloat = 0
    private var notFollowedChecks = 0
    private var followedChecks = 0
    private var reportedOffset: CGFloat = 0
    private var reportedContentHeight: CGFloat = 0
    private var expandedTop: CGFloat = 0
    private var collapseRange: CGFloat = 0
    private var metricsSource = "none"
    private var stuckReports = 0
    private var lastLoggedOffset: CGFloat = -1000
    private var lastExtras: [String: Any] = [:]
    private var lastTitle = ""
    private var lastSubtitle: String?
    private var lastActionsJson = "[]"

    init(controller: UIViewController, bridge: RaftNativeNavBarBridge, hostId: String, largeTitle: Bool) {
        self.controller = controller
        self.bridge = bridge
        self.hostId = hostId
        self.largeTitle = largeTitle
        super.init()
        controller.navigationItem.largeTitleDisplayMode = largeTitle ? .always : .never
    }

    func attach() {
        bridge.attachListener(listener: self)
        if let page = controller as? RaftPageViewController {
            page.raftNativeNavBarLayoutHook = { [weak self] reason in self?.pageDidLayout(reason: reason) }
        }
    }

    deinit {
        bridge.detachListener()
        RaftNativeNavBarRegistry.shared.unregister(hostId: hostId, bridge: bridge)
    }

    /// The stack's child that owns the navigation item (a tab root's UITabBarController).
    private var owner: UIViewController? {
        guard var owner = controller else { return nil }
        while let parent = owner.parent, !(parent is UINavigationController) {
            owner = parent
        }
        return owner
    }

    // MARK: Head

    func onNativeNavBarChanged(
        title: String,
        subtitle: String?,
        actionsJson: String,
        extrasJson: String,
        titleMenuJson: String,
        hasBack: Bool,
        nested: Bool
    ) {
        lastTitle = title
        lastSubtitle = subtitle
        lastActionsJson = actionsJson
        lastTitleMenuJson = titleMenuJson
        lastHasBack = hasBack
        lastNested = nested
        lastExtras = ((try? JSONSerialization.jsonObject(with: Data(extrasJson.utf8))) as? [String: Any]) ?? [:]
        clearGeneration += 1
        setHeadHidden(false)
        applyHead()
    }

    /// No head on screen (a shell tab that draws its own Compose head): hide the bar. Deferred a
    /// turn, so a tab switch (old head leaves, new head publishes in the same frame) never blinks.
    func onNativeNavBarCleared() {
        clearGeneration += 1
        let generation = clearGeneration
        DispatchQueue.main.async { [weak self] in
            guard let self, self.clearGeneration == generation else { return }
            self.setHeadHidden(true)
            RaftNativeNavProtoDemo.marker("RAFT_PROTO_HEAD cleared")
        }
    }

    private func setHeadHidden(_ hidden: Bool) {
        guard let page = controller as? RaftPageViewController, page.raftNativeNavBarHeadHidden != hidden else { return }
        page.raftNativeNavBarHeadHidden = hidden
        if let owner, let nav = owner.navigationController, nav.topViewController === owner {
            nav.setNavigationBarHidden(hidden, animated: false)
        }
    }

    private var lastTitleMenuJson = "[]"
    private var lastBarWidth: CGFloat = 0
    private var lastHasBack = false
    private var lastNested = false
    private var clearGeneration = 0

    private func applyHead() {
        guard let controller, let owner else { return }
        if owner !== controller,
           let tabs = owner as? UITabBarController,
           tabs.selectedViewController !== controller {
            // A demo tab root: only the selected tab names the root.
            return
        }
        let item = owner.navigationItem
        let isStackRoot = owner.navigationController?.viewControllers.first === owner
        item.title = lastTitle
        // Ask for the previous title on the next page's Back.
        item.backButtonTitle = lastTitle
        item.backButtonDisplayMode = .default
        item.largeTitleDisplayMode = largeTitle ? .always : .never
        let refreshing = (lastExtras["refreshing"] as? Bool) ?? false
        let badge = lastExtras["badge"] as? String
        let leading = lastExtras["leading"] as? [String: Any]
        let titleTap = (lastExtras["titleTap"] as? Bool) ?? false
        let titleMenu = RaftNativeNavBarHost.parseActions(lastTitleMenuJson)
        var subtitle = lastSubtitle
        // Left-aligned titles (artin, phase 2 review: the centred title reads wrong for Raft).
        // iOS 16+: the editor style puts the title (and its title menu chevron) right after Back,
        // with the system's own truncation. A head with extras (channel tile / avatar, count,
        // spinner, a title tap) draws them in a leading title view instead (a custom-view bar
        // item without the iOS 26 glass capsule), and the centred title is suppressed.
        var editorStyle = false
        if #available(iOS 16.0, *) {
            item.style = .editor
            editorStyle = true
        }
        var leadingTitleView: RaftNativeNavTitleView?
        if largeTitle {
            // A large title cannot carry a title view: the count goes in the subtitle line.
            item.titleView = nil
            if let badge, subtitle == nil { subtitle = badge }
        } else if !titleMenu.isEmpty && editorStyle {
            item.titleView = nil
        } else if titleMenu.isEmpty && (leading != nil || refreshing || badge != nil || titleTap || !editorStyle) {
            // A compact-height bar (landscape iPhone) has room for one line only.
            let compact = (owner.navigationController ?? owner).traitCollection.verticalSizeClass == .compact
            let view = RaftNativeNavTitleView(
                title: lastTitle, subtitle: compact ? nil : lastSubtitle, leading: leading, badge: badge, refreshing: refreshing,
                compact: compact
            )
            if titleTap {
                view.isUserInteractionEnabled = true
                view.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(titleTapped)))
                view.accessibilityTraits.insert(.button)
            }
            leadingTitleView = view
            // An empty title view keeps UIKit from drawing the title a second time in the middle.
            item.titleView = UIView(frame: .zero)
        } else {
            item.titleView = nil
        }
        // iOS 26 navigation subtitles; looked up at run time so the prototype builds on any SDK.
        // A title view carries its own subtitle (else UIKit draws it a second time under it).
        if item.responds(to: NSSelectorFromString("setSubtitle:")) {
            item.setValue(item.titleView == nil ? subtitle : nil, forKey: "subtitle")
        }

        // Title menu (Home's server switcher): the system chevron beside the title.
        var leadingItems: [UIBarButtonItem] = []
        if #available(iOS 16.0, *) {
            if titleMenu.isEmpty {
                item.titleMenuProvider = nil
            } else {
                let rows = titleMenu.map { self.menuElement($0) }
                item.titleMenuProvider = { _ in UIMenu(children: rows) }
            }
        } else if !titleMenu.isEmpty {
            let switcher = UIBarButtonItem(title: lastTitle, image: nil, primaryAction: nil, menu: UIMenu(children: titleMenu.map { menuElement($0) }))
            leadingItems.append(switcher)
        }

        // Back: a pushed page pops with the stack's Back; a head over its page (an in-page
        // sub-view) with a Back of its own runs it instead; at the stack's root the head's Back is
        // a leading button.
        var ownBackButton = false
        if lastHasBack && isStackRoot {
            let back = UIBarButtonItem(
                title: nil,
                image: UIImage(systemName: "chevron.backward"),
                primaryAction: UIAction { [weak self] _ in _ = self?.bridge.actionTapped(id: "back") },
                menu: nil
            )
            back.accessibilityLabel = "Back"
            leadingItems.insert(back, at: 0)
            ownBackButton = true
        }
        if #available(iOS 16.0, *) {
            if lastHasBack && lastNested && !isStackRoot {
                item.backAction = UIAction { [weak self] _ in _ = self?.bridge.actionTapped(id: "back") }
            } else {
                item.backAction = nil
            }
        }

        let actions = RaftNativeNavBarHost.parseActions(lastActionsJson)
        if let leadingTitleView {
            // Room left for it: the bar minus Back, the trailing buttons and the margins; it
            // truncates its title inside that (recomputed when the bar's width changes).
            let barWidth = owner.navigationController?.navigationBar.bounds.width ?? owner.view.bounds.width
            lastBarWidth = barWidth
            let trailingCount = actions.filter { $0.placement == "trailing" }.count
                + (actions.contains { $0.placement == "overflow" } ? 1 : 0)
            // iOS 26 glass: each button is ~52pt in its group, a group adds ~16pt of padding, and
            // the system drops a trailing button that does not fit (run 41186095424 lost Search).
            var room = barWidth - 40 - CGFloat(trailingCount) * 52 - (trailingCount > 0 ? 16 : 0)
            if !isStackRoot { room -= 64 }
            if ownBackButton { room -= 60 }
            leadingTitleView.maxWidth = max(80, room)
            let titleItem = UIBarButtonItem(customView: leadingTitleView)
            if #available(iOS 26.0, *) {
                titleItem.hidesSharedBackground = true
            }
            leadingItems.append(titleItem)
        }
        leadingItems.append(contentsOf: actions.filter { $0.placement == "leading" }.map { barItem($0) })
        item.leftBarButtonItems = leadingItems.isEmpty ? nil : leadingItems
        item.leftItemsSupplementBackButton = true

        var barItems: [UIBarButtonItem] = []
        let overflow = actions.filter { $0.placement == "overflow" }
        if !overflow.isEmpty {
            let menu = UIMenu(children: overflow.map { menuElement($0) })
            let more = UIBarButtonItem(title: nil, image: UIImage(systemName: "ellipsis"), primaryAction: nil, menu: menu)
            more.accessibilityLabel = "More"
            barItems.append(more)
        }
        // rightBarButtonItems run from the trailing edge in: the ⋯ outermost, then the buttons
        // so that they read in listed order.
        barItems.append(contentsOf: actions.filter { $0.placement == "trailing" }.reversed().map { barItem($0) })
        if largeTitle && refreshing {
            let spinner = UIActivityIndicatorView(style: .medium)
            spinner.startAnimating()
            barItems.append(UIBarButtonItem(customView: spinner))
        }
        item.rightBarButtonItems = barItems
        let trailingIds = actions.filter { $0.placement == "trailing" }.map(\.id).joined(separator: ",")
        let overflowIds = overflow.map(\.id).joined(separator: ",")
        RaftNativeNavProtoDemo.marker(
            "RAFT_PROTO_HEAD title=\(lastTitle) large=\(largeTitle) titleView=\(item.titleView != nil) " +
                "trailing=[\(trailingIds)] overflow=[\(overflowIds)] titleMenu=\(titleMenu.count) " +
                "ownBack=\(ownBackButton) backAction=\(lastHasBack && lastNested && !isStackRoot) refreshing=\(refreshing)"
        )
    }

    /// The page became the shown one again (a demo tab selected): apply its last head.
    func reapply() {
        if !lastTitle.isEmpty { applyHead() }
    }

    @objc private func titleTapped() {
        _ = bridge.actionTapped(id: "title")
    }

    private struct Action {
        let id: String
        let label: String
        let symbol: String?
        let badge: Int?
        let attention: Bool
        let enabled: Bool
        let checked: Bool
        let destructive: Bool
        let placement: String
        let children: [Action]
    }

    private static func parseAction(_ row: [String: Any]) -> Action? {
        guard let id = row["id"] as? String else { return nil }
        let children = (row["children"] as? [[String: Any]])?.compactMap { parseAction($0) } ?? []
        return Action(
            id: id,
            label: row["label"] as? String ?? id,
            symbol: row["systemSymbol"] as? String,
            badge: (row["badge"] as? NSNumber)?.intValue,
            attention: (row["attention"] as? Bool) ?? false,
            enabled: (row["enabled"] as? Bool) ?? true,
            checked: (row["checked"] as? Bool) ?? false,
            destructive: (row["destructive"] as? Bool) ?? false,
            placement: row["placement"] as? String ?? "trailing",
            children: children
        )
    }

    private static func parseActions(_ json: String) -> [Action] {
        guard let rows = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [[String: Any]] else {
            return []
        }
        return rows.compactMap { parseAction($0) }
    }

    /// A ⋯ / title-menu row (a submenu when it has children).
    private func menuElement(_ action: Action) -> UIMenuElement {
        let image = action.symbol.flatMap { UIImage(systemName: $0) }
        if !action.children.isEmpty {
            return UIMenu(title: action.label, image: image, children: action.children.map { menuElement($0) })
        }
        let row = UIAction(title: action.label, image: image) { [weak self] _ in
            _ = self?.bridge.actionTapped(id: action.id)
        }
        var attributes: UIMenuElement.Attributes = []
        if !action.enabled { attributes.insert(.disabled) }
        if action.destructive { attributes.insert(.destructive) }
        row.attributes = attributes
        row.state = action.checked ? .on : .off
        if #available(iOS 16.0, *) {
            if let badge = action.badge, badge > 0 {
                row.subtitle = "\(badge)"
            } else if action.attention {
                row.subtitle = "●"
            }
        }
        return row
    }

    /// A bar button: a symbol (else its label as text); a submenu opens on tap; a count badge.
    private func barItem(_ action: Action) -> UIBarButtonItem {
        let image = action.symbol.flatMap { UIImage(systemName: $0) }
        let button: UIBarButtonItem
        if !action.children.isEmpty {
            button = UIBarButtonItem(
                title: image == nil ? action.label : nil,
                image: image,
                primaryAction: nil,
                menu: UIMenu(title: action.label, children: action.children.map { menuElement($0) })
            )
        } else {
            button = UIBarButtonItem(
                title: image == nil ? action.label : nil,
                image: image,
                primaryAction: UIAction { [weak self] _ in _ = self?.bridge.actionTapped(id: action.id) },
                menu: nil
            )
        }
        button.isEnabled = action.enabled
        button.accessibilityLabel = action.label
        if let count = action.badge, count > 0 {
            if #available(iOS 26.0, *) {
                // The bridge always calls on the main thread; the Badge factory is main-actor.
                MainActor.assumeIsolated { button.badge = .count(count) }
            }
            button.accessibilityValue = "\(count)"
        }
        return button
    }

    // MARK: Large-title mirror

    func onNativeNavBarScroll(offset: Double, contentHeight: Double, dragging: Bool) {
        reportedOffset = CGFloat(offset)
        reportedContentHeight = CGFloat(contentHeight)
        if let mirror, mirror.simulatedDragging != dragging {
            mirror.simulatedDragging = dragging
            RaftNativeNavProtoDemo.marker("RAFT_PROTO_MIRROR dragging=\(dragging)")
        }
        applyMirror()
        if largeTitle, collapseRange > 0 {
            // Read back what UIKit did with the bar once it has laid out.
            DispatchQueue.main.async { [weak self] in self?.verifyMirror() }
        }
    }

    private func ensureMirror() {
        guard largeTitle, mirror == nil, let controller, controller.isViewLoaded else { return }
        let scroll = RaftScrollMirror(frame: controller.view.bounds)
        scroll.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scroll.isUserInteractionEnabled = false
        scroll.scrollsToTop = false
        scroll.showsVerticalScrollIndicator = false
        scroll.showsHorizontalScrollIndicator = false
        scroll.backgroundColor = .clear
        scroll.contentInsetAdjustmentBehavior = .always
        scroll.accessibilityElementsHidden = true
        // Behind the Compose content: never seen, never touched.
        controller.view.insertSubview(scroll, at: 0)
        mirror = scroll
        controller.setContentScrollView(scroll, for: .top)
        if let owner, owner !== controller {
            owner.setContentScrollView(scroll, for: .top)
        }
        RaftNativeNavProtoDemo.marker("RAFT_PROTO_MIRROR_ATTACHED page=\(type(of: controller))")
    }

    private func applyMirror() {
        guard let mirror, let controller else { return }
        let bounds = controller.view.bounds
        let base = expandedTop > 0 ? expandedTop : mirror.adjustedContentInset.top
        let height = max(bounds.height + collapseRange + 400, reportedContentHeight + base + 200)
        if abs(mirror.contentSize.height - height) > 1 || mirror.contentSize.width != bounds.width {
            mirror.contentSize = CGSize(width: bounds.width, height: height)
        }
        let target = -base + reportedOffset
        if abs(mirror.contentOffset.y - target) > 0.25 {
            mirror.contentOffset = CGPoint(x: 0, y: target)
        }
    }

    /// RaftPageViewController laid out / appeared.
    private func pageDidLayout(reason: String) {
        // Rotation / a new bar width: re-measure the leading title's room.
        if let bar = owner?.navigationController?.navigationBar, !lastTitle.isEmpty,
           abs(bar.bounds.width - lastBarWidth) > 1 {
            applyHead()
        }
        guard largeTitle, let controller else { return }
        ensureMirror()
        guard let mirror else { return }
        if mirror.frame != controller.view.bounds { mirror.frame = controller.view.bounds }
        measureMetricsIfNeeded(reason: reason)
        applyMirror()
        if reason == "appear" {
            // A pushed page appears while the push still runs: measure once it has settled.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
                self?.measureMetricsIfNeeded(reason: "settled")
                self?.applyMirror()
            }
        }
    }

    private var settled: Bool {
        guard let controller, let nav = controller.navigationController else { return false }
        return controller.view.window != nil && nav.transitionCoordinator == nil && !nav.isNavigationBarHidden
    }

    private func measureMetricsIfNeeded(reason: String) {
        guard collapseRange == 0, metricsSource != "failed", reportedOffset == 0, settled, let controller else { return }
        let top = controller.view.safeAreaInsets.top
        guard top > 0 else { return }
        let (range, source) = RaftNativeNavBarHost.largeTitleHeight(in: controller.navigationController?.navigationBar)
        expandedTop = top
        collapseRange = range
        metricsSource = source
        expandedBarHeight = controller.navigationController?.navigationBar.frame.height ?? 0
        bridge.setLargeTitleMetrics(expandedTop: Double(top), collapseRange: Double(range))
        RaftNativeNavProtoDemo.marker(
            "RAFT_PROTO_LARGE_TITLE expandedTop=\(top) range=\(range) source=\(source) reason=\(reason)"
        )
    }

    /// The large title's own row in the bar (UIKit's private large-title view), else the
    /// iOS 26 default; corrected below from what the bar really does once fully collapsed.
    static func largeTitleView(in bar: UINavigationBar?) -> UIView? {
        guard let bar else { return nil }
        var found: UIView?
        func walk(_ view: UIView) {
            if found != nil { return }
            if NSStringFromClass(type(of: view)).contains("LargeTitle") {
                found = view
                return
            }
            view.subviews.forEach(walk)
        }
        walk(bar)
        return found
    }

    private static func largeTitleHeight(in bar: UINavigationBar?) -> (CGFloat, String) {
        guard let bar else { return (52, "default") }
        var found: UIView?
        func walk(_ view: UIView) {
            if found != nil { return }
            let name = NSStringFromClass(type(of: view))
            if name.contains("LargeTitle"), !view.isHidden, view.bounds.height > 20, view.bounds.height < 140 {
                found = view
                return
            }
            view.subviews.forEach(walk)
        }
        walk(bar)
        if let found { return (found.bounds.height, NSStringFromClass(type(of: found))) }
        return (52, "default")
    }

    private func verifyMirror() {
        guard largeTitle, collapseRange > 0, settled, let controller else { return }
        let actual = controller.view.safeAreaInsets.top
        let expected = expandedTop - min(reportedOffset, collapseRange)
        let bar = controller.navigationController?.navigationBar
        let barHeight = bar?.frame.height ?? 0
        var largeTitleState = "-"
        if let large = RaftNativeNavBarHost.largeTitleView(in: bar) {
            largeTitleState = String(format: "h=%.1f alpha=%.2f ty=%.1f", large.frame.height, large.alpha, large.transform.ty)
        }
        if abs(reportedOffset - lastLoggedOffset) >= 6 || (reportedOffset == 0 && lastLoggedOffset != 0) {
            lastLoggedOffset = reportedOffset
            RaftNativeNavProtoDemo.marker(
                String(format: "RAFT_PROTO_MIRROR offset=%.1f expectedTop=%.1f safeTop=%.1f mirrorY=%.1f barH=%.1f largeTitle=",
                       reportedOffset, expected, actual, mirror?.contentOffset.y ?? 0, barHeight) + largeTitleState
            )
        }
        // Does UIKit follow the mirror continuously? Checked mid-collapse: the bar (or the safe
        // area) must have shrunk by part of the way. Three misses: inline title instead.
        if reportedOffset >= collapseRange * 0.4, reportedOffset <= collapseRange * 0.9, metricsSource != "failed" {
            let shrunk = (expandedBarHeight - barHeight) + (expandedTop - actual)
            if shrunk >= collapseRange * 0.15 {
                followedChecks += 1
                notFollowedChecks = 0
            } else {
                notFollowedChecks += 1
                if notFollowedChecks >= 3, followedChecks == 0 {
                    RaftNativeNavProtoDemo.marker("RAFT_PROTO_WARN mirror_not_tracked_midway offset=\(reportedOffset)")
                    fallBackToInline()
                    return
                }
            }
        }
        if reportedOffset >= collapseRange + 4 {
            if (expandedTop - actual) + (expandedBarHeight - barHeight) < 0.5 {
                // UIKit did not follow the mirror at all.
                stuckReports += 1
                if stuckReports >= 20 { fallBackToInline() }
            } else {
                stuckReports = 0
                // Fully collapsed now: the bar's real collapse range.
                let real = expandedTop - actual
                if actual < expandedTop - 0.5, abs(real - collapseRange) > 0.5 {
                    RaftNativeNavProtoDemo.marker("RAFT_PROTO_LARGE_TITLE refine range=\(collapseRange)->\(real)")
                    collapseRange = real
                    metricsSource = "observed"
                    bridge.setLargeTitleMetrics(expandedTop: Double(expandedTop), collapseRange: Double(real))
                }
            }
        }
    }

    /// The mirror did not move the bar: inline title, the page follows the live safe area again.
    private func fallBackToInline() {
        RaftNativeNavProtoDemo.marker("RAFT_PROTO_WARN mirror_not_followed fallback=inline")
        metricsSource = "failed"
        largeTitle = false
        collapseRange = 0
        bridge.setLargeTitleMetrics(expandedTop: 0, collapseRange: 0)
        owner?.navigationItem.largeTitleDisplayMode = .never
        controller?.navigationItem.largeTitleDisplayMode = .never
        applyHead()
    }

    /// Capture script: scrolls the page's primary list like a finger would.
    @discardableResult
    func scriptedScroll(dy: CGFloat) -> Bool {
        bridge.scriptedScroll(dy: Double(dy))
    }

    func scriptedScrollEnd() {
        bridge.scriptedScrollEnd()
    }
}

/// The hidden scroll view UIKit's large title tracks. It is never touched; while the page's
/// Compose list is being dragged / flung it reports itself as tracking and dragging, as a real
/// scroll view under a finger would (UIKit tracks a dragged content scroll view interactively).
final class RaftScrollMirror: UIScrollView {
    var simulatedDragging = false
    override var isTracking: Bool { simulatedDragging || super.isTracking }
    override var isDragging: Bool { simulatedDragging || super.isDragging }
}

/// The conversation's title: channel glyph tile / DM avatar, title over subtitle, the refresh
/// spinner, and a count badge (what the Compose head carried beside its title).
final class RaftNativeNavTitleView: UIStackView {
    private let compact: Bool

    init(title: String, subtitle: String?, leading: [String: Any]?, badge: String?, refreshing: Bool, compact: Bool = false) {
        self.compact = compact
        super.init(frame: .zero)
        axis = .horizontal
        alignment = .center
        spacing = 8
        if let leading, let view = Self.leadingView(leading, size: compact ? 22 : 28) {
            addArrangedSubview(view)
        }
        let text = UIStackView()
        text.axis = .vertical
        text.alignment = .leading
        text.spacing = 0
        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .systemFont(ofSize: 17, weight: .semibold)
        titleLabel.textColor = .label
        titleLabel.lineBreakMode = .byTruncatingTail
        titleLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        text.addArrangedSubview(titleLabel)
        if let subtitle, !subtitle.isEmpty {
            let sub = UILabel()
            sub.text = subtitle
            sub.font = .systemFont(ofSize: 12)
            sub.textColor = .secondaryLabel
            sub.lineBreakMode = .byTruncatingTail
            sub.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            text.addArrangedSubview(sub)
        }
        addArrangedSubview(text)
        if let badge {
            let label = UILabel()
            label.text = " \(badge) "
            label.font = .systemFont(ofSize: 12, weight: .semibold)
            label.textColor = .secondaryLabel
            label.layer.borderColor = UIColor.separator.cgColor
            label.layer.borderWidth = 1
            label.layer.cornerRadius = 9
            label.textAlignment = .center
            label.heightAnchor.constraint(equalToConstant: 18).isActive = true
            label.widthAnchor.constraint(greaterThanOrEqualToConstant: 22).isActive = true
            addArrangedSubview(label)
        }
        if refreshing {
            let spinner = UIActivityIndicatorView(style: .medium)
            spinner.transform = CGAffineTransform(scaleX: 0.8, y: 0.8)
            spinner.startAnimating()
            addArrangedSubview(spinner)
        }
        accessibilityLabel = [title, subtitle].compactMap { $0 }.joined(separator: ", ")
    }

    @available(*, unavailable)
    required init(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    private var widthConstraint: NSLayoutConstraint?
    private var heightConstraint: NSLayoutConstraint?

    /// The most it may take in the bar (set by the host); the title truncates inside it. Pinned by
    /// constraints: a bar item wider than its room makes UIKit drop the title and other buttons.
    var maxWidth: CGFloat = .greatestFiniteMagnitude {
        didSet {
            invalidateIntrinsicContentSize()
            let size = intrinsicContentSize
            translatesAutoresizingMaskIntoConstraints = false
            widthConstraint?.isActive = false
            heightConstraint?.isActive = false
            widthConstraint = widthAnchor.constraint(equalToConstant: size.width)
            heightConstraint = heightAnchor.constraint(equalToConstant: min(size.height, compact ? 30 : 40))
            widthConstraint?.isActive = true
            heightConstraint?.isActive = true
            frame.size = CGSize(width: size.width, height: min(size.height, compact ? 30 : 40))
        }
    }

    /// Its own fitting size up to [maxWidth], so the bar keeps it at the leading edge.
    override var intrinsicContentSize: CGSize {
        // Summed from the arranged views (asking the stack itself would recurse into this).
        let sizes = arrangedSubviews.map { $0.systemLayoutSizeFitting(UIView.layoutFittingCompressedSize) }
        let width = sizes.reduce(0) { $0 + $1.width } + spacing * CGFloat(max(0, sizes.count - 1))
        return CGSize(width: min(ceil(width), maxWidth), height: ceil(sizes.map(\.height).max() ?? 0))
    }

    private static func color(_ argb: Any?, fallback: UIColor) -> UIColor {
        guard var hex = argb as? String else { return fallback }
        hex = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        guard hex.count == 8, let value = UInt32(hex, radix: 16) else { return fallback }
        return UIColor(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: CGFloat((value >> 24) & 0xFF) / 255
        )
    }

    private static func leadingView(_ leading: [String: Any], size: CGFloat) -> UIView? {
        switch leading["kind"] as? String {
        case "channel":
            let tile = UIView()
            tile.backgroundColor = color(leading["tileArgb"], fallback: .systemYellow)
            tile.layer.cornerRadius = 7
            tile.layer.borderWidth = 1.5
            let ink = color(leading["inkArgb"], fallback: .label)
            tile.layer.borderColor = ink.cgColor
            let symbol: String
            if (leading["publicPage"] as? Bool) == true {
                symbol = "globe"
            } else {
                switch leading["channelKind"] as? String {
                case "private": symbol = "lock"
                case "joint": symbol = "arrow.triangle.branch"
                default: symbol = "number"
                }
            }
            let image = UIImageView(image: UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 13, weight: .bold)))
            image.tintColor = ink
            image.translatesAutoresizingMaskIntoConstraints = false
            tile.addSubview(image)
            tile.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                tile.widthAnchor.constraint(equalToConstant: size),
                tile.heightAnchor.constraint(equalToConstant: size),
                image.centerXAnchor.constraint(equalTo: tile.centerXAnchor),
                image.centerYAnchor.constraint(equalTo: tile.centerYAnchor),
            ])
            return tile
        case "avatar":
            let circle = UIView()
            circle.backgroundColor = color(leading["fillArgb"], fallback: .systemPurple)
            circle.layer.cornerRadius = size / 2
            circle.clipsToBounds = true
            circle.translatesAutoresizingMaskIntoConstraints = false
            let label = UILabel()
            let words = ((leading["label"] as? String) ?? "").split(separator: " ").prefix(2)
            label.text = words.compactMap { $0.first.map(String.init) }.joined().uppercased()
            label.font = .systemFont(ofSize: 12, weight: .bold)
            label.textColor = .white
            label.translatesAutoresizingMaskIntoConstraints = false
            circle.addSubview(label)
            let image = UIImageView()
            image.contentMode = .scaleAspectFill
            image.translatesAutoresizingMaskIntoConstraints = false
            circle.addSubview(image)
            NSLayoutConstraint.activate([
                circle.widthAnchor.constraint(equalToConstant: size),
                circle.heightAnchor.constraint(equalToConstant: size),
                label.centerXAnchor.constraint(equalTo: circle.centerXAnchor),
                label.centerYAnchor.constraint(equalTo: circle.centerYAnchor),
                image.leadingAnchor.constraint(equalTo: circle.leadingAnchor),
                image.trailingAnchor.constraint(equalTo: circle.trailingAnchor),
                image.topAnchor.constraint(equalTo: circle.topAnchor),
                image.bottomAnchor.constraint(equalTo: circle.bottomAnchor),
            ])
            if let urlText = leading["imageUrl"] as? String, let url = URL(string: urlText) {
                URLSession.shared.dataTask(with: url) { data, _, _ in
                    guard let data, let loaded = UIImage(data: data) else { return }
                    DispatchQueue.main.async { image.image = loaded }
                }.resume()
            }
            return circle
        default:
            return nil
        }
    }
}

/// DEMO SHELL, not the production one: the real app's tab bar is RaftIosTabBarController over the
/// signed-in app shell page (5 tabs with Search and the server tab), which needs a session and is
/// untouched by the prototype. This login-free stand-in only hosts the fixture pages.
/// The demo's tab root: the Home, Activity, Tasks and More tabs as CMP fixture pages (each
/// publishes its own head: Home the server title menu and the bell). With the prototype off every
/// page draws its own Compose head as today.
final class RaftNativeNavProtoRootController: UITabBarController, UITabBarControllerDelegate {
    private let native: Bool

    init(native: Bool) {
        self.native = native
        super.init(nibName: nil, bundle: nil)
        viewControllers = [
            Self.tab("compose_native_nav_proto_home", "Home", "house"),
            Self.tab("compose_native_nav_proto_activity", "Activity", "waveform.path.ecg.rectangle"),
            Self.tab("compose_native_nav_proto_tasks", "Tasks", "checkmark.square"),
            Self.tab("compose_native_nav_proto_more", "More", "ellipsis.circle"),
        ]
        delegate = self
        // FD's will-appear hook shows / hides the bar by this when the root reappears.
        fd_prefersNavigationBarHidden = !native
        if native {
            navigationItem.title = "Home"
            navigationItem.backButtonDisplayMode = .default
            navigationItem.largeTitleDisplayMode = RaftNativeNavProto.largeTitleMirror ? .always : .never
        }
    }

    private static func tab(_ pageName: String, _ title: String, _ symbol: String) -> UIViewController {
        let page = RaftPageControllerFactory.controller(pageName: pageName, pageData: ["pageName": pageName])
        page.tabBarItem = UITabBarItem(title: title, image: UIImage(systemName: symbol), selectedImage: UIImage(systemName: "\(symbol).fill"))
        return page
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
        // The selected tab's head names the root.
        ((viewController as? RaftPageViewController)?.raftNativeNavBarHost as? RaftNativeNavBarHost)?.reapply()
    }

    /// Demo script: select tab [index] as a tap on the bar would.
    func selectTab(_ index: Int) {
        guard let controllers = viewControllers, index < controllers.count else { return }
        selectedIndex = index
        tabBarController(self, didSelect: controllers[index])
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
        guard RaftNativeNavProto.demoScene != nil || RaftNativeNavProto.realScene != nil else { return }
        print(text)
        fflush(stdout)
    }

    static func push(_ pageName: String, on nav: UINavigationController, animated: Bool) {
        let page = RaftPageControllerFactory.controller(pageName: pageName, pageData: ["pageName": pageName])
        nav.pushViewController(page, animated: animated)
    }

    static func after(_ seconds: Double, _ work: @escaping () -> Void) {
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
                    // Run 41084382910: an `.ended` scripted pan made UIKit cancel the pop (it bounced
                    // back in both modes). The handler's target is the navigation controller's
                    // percent-driven interactive transition: finish it the way a release past the
                    // threshold does.
                    if let interaction = target as? UIPercentDrivenInteractiveTransition {
                        interaction.finish()
                    } else {
                        pan.scriptedState = .ended
                        pan.scriptedVelocityX = 900
                        target.perform(handler, with: pan)
                    }
                    marker("RAFT_PROTO_SWIPE_END finished_via=\(target is UIPercentDrivenInteractiveTransition ? "interaction" : "gesture") class=\(type(of: target))")
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

    /// The native-bar host of the page on top (a tab root's selected page).
    static func topHost(_ nav: UINavigationController) -> RaftNativeNavBarHost? {
        var top = nav.topViewController
        if let tabs = top as? UITabBarController { top = tabs.selectedViewController }
        return (top as? RaftPageViewController)?.raftNativeNavBarHost as? RaftNativeNavBarHost
    }

    /// Drags the top page's primary list by [distance] points (positive: the finger moves up)
    /// over [duration] seconds, through the page's nested-scroll chain, then releases it.
    static func scriptedScroll(
        _ nav: UINavigationController,
        distance: CGFloat,
        duration: Double,
        completion: @escaping () -> Void = {}
    ) {
        guard let host = topHost(nav) else {
            marker("RAFT_PROTO_WARN scripted_scroll_unavailable")
            completion()
            return
        }
        let steps = max(1, Int(duration * 60))
        var done: CGFloat = 0
        func step(_ index: Int) {
            if index > steps {
                host.scriptedScrollEnd()
                marker("RAFT_PROTO_SCROLL_END distance=\(distance)")
                after(0.4) { completion() }
                return
            }
            let t = CGFloat(index) / CGFloat(steps)
            // ease in-out, like a finger starting and slowing down
            let eased = t < 0.5 ? 2 * t * t : 1 - pow(-2 * t + 2, 2) / 2
            let target = distance * eased
            if !host.scriptedScroll(dy: target - done) {
                marker("RAFT_PROTO_WARN scripted_scroll_no_list")
                completion()
                return
            }
            done = target
            after(1.0 / 60.0) { step(index + 1) }
        }
        after(1.0 / 60.0) { step(1) }
    }

    private static func root(_ nav: UINavigationController) -> RaftNativeNavProtoRootController? {
        nav.viewControllers.first as? RaftNativeNavProtoRootController
    }

    private static func pop(_ nav: UINavigationController) {
        nav.popViewController(animated: true)
    }

    /// Runs [steps] (delay before each, work) one after the other.
    static func sequence(_ steps: [(Double, () -> Void)], done: @escaping () -> Void = {}) {
        guard let first = steps.first else {
            done()
            return
        }
        after(first.0) {
            first.1()
            sequence(Array(steps.dropFirst()), done: done)
        }
    }

    /// Phase 2 video: the four tabs, a Settings page and back, Saved and back, the conversation,
    /// its members, a swipe back, channel settings and back, an agent profile and back.
    private static func video(_ nav: UINavigationController) {
        sequence([
            (2.5, { root(nav)?.selectTab(1) }),
            (2.2, { root(nav)?.selectTab(2) }),
            (2.2, { root(nav)?.selectTab(3) }),
            (2.2, { push("compose_native_nav_proto_settings_detail", on: nav, animated: true) }),
            (2.5, { pop(nav) }),
            (1.6, { root(nav)?.selectTab(0) }),
            (1.8, { push("compose_native_nav_proto_saved", on: nav, animated: true) }),
            (2.5, { pop(nav) }),
            (1.6, { push("compose_native_nav_proto_thread", on: nav, animated: true) }),
            (3.0, { push("compose_channel_members_parity_visual", on: nav, animated: true) }),
            (2.8, { scriptedSwipeBack(nav, progress: 0.75, duration: 1.1, finish: true) }),
            (2.6, { push("compose_native_nav_proto_channel_settings", on: nav, animated: true) }),
            (2.5, { pop(nav) }),
            (1.6, { pop(nav) }),
            (1.6, { push("compose_native_nav_proto_agent", on: nav, animated: true) }),
            (3.0, { pop(nav) }),
        ]) {
            after(2.0) { marker("RAFT_PROTO_DONE scene=video") }
        }
    }

    static func run(scene: String, nav: UINavigationController) {
        let mode = RaftNativeNavProto.enabled ? "native" : "now"
        marker("RAFT_PROTO_START scene=\(scene) mode=\(mode)")
        func ready(_ seconds: Double) {
            after(seconds) { marker("RAFT_PROTO_READY scene=\(scene)") }
        }
        switch scene {
        case "home":
            ready(4)
        case "activity", "tasks", "more":
            let index = ["activity": 1, "tasks": 2, "more": 3][scene] ?? 0
            after(1.5) { root(nav)?.selectTab(index) }
            ready(5)
        case "settings":
            after(1.5) { root(nav)?.selectTab(3) }
            after(2.5) { push("compose_native_nav_proto_settings_detail", on: nav, animated: false) }
            ready(6)
        case "saved":
            after(1) { push("compose_native_nav_proto_saved", on: nav, animated: false) }
            ready(5)
        case "agent":
            after(1) { push("compose_native_nav_proto_agent", on: nav, animated: false) }
            ready(5)
        case "thread":
            after(1) { push("compose_native_nav_proto_thread", on: nav, animated: false) }
            ready(5)
        case "members":
            // The conversation publishes its title first, so Members' Back can name it.
            after(1) { push("compose_native_nav_proto_thread", on: nav, animated: false) }
            after(3) { push("compose_channel_members_parity_visual", on: nav, animated: true) }
            ready(7)
        case "channelsettings":
            after(1) { push("compose_native_nav_proto_thread", on: nav, animated: false) }
            after(3) { push("compose_native_nav_proto_channel_settings", on: nav, animated: true) }
            ready(7)
        case "midswipe":
            after(1) { push("compose_native_nav_proto_thread", on: nav, animated: false) }
            after(5) {
                scriptedSwipeBack(nav, progress: 0.45, duration: 0.6, finish: false) {
                    after(0.8) { marker("RAFT_PROTO_READY scene=midswipe") }
                }
            }
        case "video":
            video(nav)
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
            nav.navigationBar.prefersLargeTitles = RaftNativeNavProto.largeTitleMirror
        } else {
            nav.setNavigationBarHidden(true, animated: false)
        }
        RaftNativeNavProtoDemo.run(scene: scene, nav: nav)
        return nav
    }

    func updateUIViewController(_ uiViewController: UINavigationController, context: Context) {}
}

// MARK: - PROTOTYPE: the REAL signed-in app, scripted (capture only)

/// Drives the real app shell (production tab bar, real pages) on the capture's fabricated data
/// (`RAFT_PROTO_FAKE_BACKEND=1`) the way a person would: the head actions run through the page's
/// bar bridge (the same handlers the bar buttons / title menu rows run; with the prototype off
/// the page registers them there without drawing anything), pages open with the pageData the
/// app's own navigation builds, tabs switch through the production tab bar's own selection path.
/// Prints `RAFT_PROTO_SHOT name=<n>` when a frame is ready to capture.
enum RaftNativeNavProtoReal {
    private static var started = false

    static func startIfRequested() {
        guard let scene = RaftNativeNavProto.realScene, !started else { return }
        started = true
        RaftNativeNavProtoDemo.marker("RAFT_PROTO_START scene=\(scene) mode=\(RaftNativeNavProto.enabled ? "native" : "now") real=1")
        waitForShell(attempt: 0)
    }

    /// Shots the workflow has taken (it drops `raft-shot-<name>.done` into the app's tmp dir).
    private static func shotTaken(_ name: String) -> Bool {
        FileManager.default.fileExists(atPath: NSTemporaryDirectory() + "raft-shot-\(name).done")
    }

    private static func shot(_ name: String) {
        RaftNativeNavProtoDemo.marker("RAFT_PROTO_SHOT name=\(name) dir=\(NSTemporaryDirectory())")
    }

    /// Like [RaftNativeNavProtoDemo.sequence], but a step whose delay is negative is a shot: the
    /// script waits (up to 15 s) until the workflow has taken it, so no frame is ever early or late.
    private static func steps(_ list: [(Double, () -> Void)], shots: [Int: String], index: Int = 0, done: @escaping () -> Void) {
        guard index < list.count else {
            done()
            return
        }
        let (delay, work) = list[index]
        RaftNativeNavProtoDemo.after(delay) {
            work()
            if let name = shots[index] {
                waitShot(name, attempt: 0) { steps(list, shots: shots, index: index + 1, done: done) }
            } else {
                steps(list, shots: shots, index: index + 1, done: done)
            }
        }
    }

    private static func waitShot(_ name: String, attempt: Int, then: @escaping () -> Void) {
        if shotTaken(name) || attempt >= 75 {
            if attempt >= 75 { RaftNativeNavProtoDemo.marker("RAFT_PROTO_WARN shot_timeout name=\(name)") }
            RaftNativeNavProtoDemo.after(0.3, then)
            return
        }
        RaftNativeNavProtoDemo.after(0.2) { waitShot(name, attempt: attempt + 1, then: then) }
    }

    private static func findNav(_ controller: UIViewController?) -> UINavigationController? {
        guard let controller else { return nil }
        if let nav = controller as? UINavigationController,
           let root = nav.viewControllers.first as? RaftPageViewController, root.raftIsAppShellPage {
            return nav
        }
        for child in controller.children {
            if let found = findNav(child) { return found }
        }
        return findNav(controller.presentedViewController)
    }

    private static var window: UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow } ?? UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first
    }

    private static func waitForShell(attempt: Int) {
        if let nav = findNav(window?.rootViewController), RaftIosTabBarController.scriptCurrent != nil {
            RaftNativeNavProtoDemo.marker("RAFT_PROTO_REAL shell_ready attempt=\(attempt)")
            RaftNativeNavProtoDemo.after(6) { run(nav) }
            return
        }
        if attempt > 120 {
            RaftNativeNavProtoDemo.marker("RAFT_PROTO_WARN real_shell_missing")
            RaftNativeNavProtoDemo.marker("RAFT_PROTO_DONE scene=real")
            return
        }
        RaftNativeNavProtoDemo.after(0.5) { waitForShell(attempt: attempt + 1) }
    }

    private static func host(_ controller: UIViewController?) -> RaftNativeNavBarHost? {
        (controller as? RaftPageViewController)?.raftNativeNavBarHost as? RaftNativeNavBarHost
    }

    /// Runs a head action of the page on top (the shell page when nothing is pushed).
    private static func tap(_ nav: UINavigationController, _ id: String) {
        let ok = host(nav.topViewController)?.bridge.actionTapped(id: id) ?? false
        RaftNativeNavProtoDemo.marker("RAFT_PROTO_REAL tap id=\(id) ok=\(ok)")
    }

    private static func open(_ nav: UINavigationController, _ target: String, _ pageName: String) {
        guard let json = RaftCaptureBackend.shared.pageDataJson(target: target),
              let data = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [AnyHashable: Any],
              let top = nav.topViewController
        else {
            RaftNativeNavProtoDemo.marker("RAFT_PROTO_WARN real_open_failed target=\(target)")
            return
        }
        RaftPageRouter().openPage(withName: pageName, pageData: data, controller: top)
    }

    private static func selectTab(_ key: String) {
        let ok = RaftIosTabBarController.scriptCurrent?.scriptSelectTab(key) ?? false
        RaftNativeNavProtoDemo.marker("RAFT_PROTO_REAL tab key=\(key) ok=\(ok)")
    }

    private static func rotate(_ orientation: UIInterfaceOrientationMask) {
        guard #available(iOS 16.0, *), let scene = window?.windowScene else { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: orientation)) { error in
            RaftNativeNavProtoDemo.marker("RAFT_PROTO_WARN rotate_failed \(error.localizedDescription)")
        }
        window?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }

    /// Native bar: opens the title menu the way a tap on the title does (if UIKit lets the
    /// script); with the prototype off the Compose server switcher opens instead.
    private static func openServerMenu(_ nav: UINavigationController) {
        guard RaftNativeNavProto.enabled else {
            tap(nav, "server-all")
            return
        }
        var opened = false
        func walk(_ view: UIView) {
            if opened { return }
            if let control = view as? UIControl, control.showsMenuAsPrimaryAction, control.contextMenuInteraction != nil {
                if #available(iOS 17.4, *) {
                    control.performPrimaryAction()
                    opened = true
                    return
                }
            }
            view.subviews.forEach(walk)
        }
        walk(nav.navigationBar)
        RaftNativeNavProtoDemo.marker("RAFT_PROTO_REAL title_menu_opened=\(opened)")
    }

    private static func closeServerMenu(_ nav: UINavigationController) {
        guard RaftNativeNavProto.enabled else { return }
        func walk(_ view: UIView) {
            if let control = view as? UIControl, control.showsMenuAsPrimaryAction {
                control.contextMenuInteraction?.dismissMenu()
            }
            view.subviews.forEach(walk)
        }
        walk(nav.navigationBar)
    }

    private static func run(_ nav: UINavigationController) {
        let pop: () -> Void = { nav.popViewController(animated: true) }
        let list: [(Double, () -> Void)] = [
            (0.0, { shot("home") }),
            (1.5, { openServerMenu(nav) }),
            (1.5, { shot("servermenu") }),
            (1.5, { closeServerMenu(nav); tap(nav, "server:\(RaftCaptureFakeServerIds.bots)") }),
            (3.5, { shot("server2") }),
            (1.5, { tap(nav, "server:\(RaftCaptureFakeServerIds.design)") }),
            (3.5, { tap(nav, "home-bell") }),
            (1.5, { shot("bell") }),
            (1.5, { tap(nav, "home-bell") }),
            (1.5, { open(nav, "thread:ch_capture_design", "compose_thread") }),
            (3.5, { shot("thread") }),
            (1.5, { tap(nav, "members") }),
            (3.0, { shot("members") }),
            (1.5, pop),
            (2.0, { tap(nav, "thread-settings") }),
            (3.0, { shot("channelsettings") }),
            (1.5, pop),
            (2.0, { RaftNativeNavProtoDemo.scriptedSwipeBack(nav, progress: 0.75, duration: 1.1, finish: true) }),
            (2.5, { shot("swipeback") }),
            (1.5, { open(nav, "thread:ch_capture_long", "compose_thread") }),
            (3.5, { shot("longname") }),
            (1.5, { rotate(.landscapeRight) }),
            (3.0, { shot("rotated") }),
            (1.5, { rotate(.portrait) }),
            (3.0, pop),
            (2.0, { open(nav, "drafts", "compose_home_drafts") }),
            (3.0, { shot("drafts") }),
            (1.5, pop),
            (2.0, { open(nav, "settings-account", "compose_settings_account") }),
            (3.0, { shot("settings") }),
            (1.5, pop),
            (2.0, { selectTab("search") }),
            (2.5, { shot("search") }),
            (1.5, { selectTab("home") }),
            (2.5, { shot("homeagain") }),
        ]
        steps(list, shots: [0: "home", 2: "servermenu", 4: "server2", 7: "bell", 10: "thread", 12: "members", 15: "channelsettings", 18: "swipeback", 20: "longname", 22: "rotated", 26: "drafts", 29: "settings", 32: "search", 34: "homeagain"]) {
            RaftNativeNavProtoDemo.after(1.0) { RaftNativeNavProtoDemo.marker("RAFT_PROTO_DONE scene=real") }
        }
    }
}

/// The capture's fabricated server ids (RaftCaptureFakeServer in the fixture build).
enum RaftCaptureFakeServerIds {
    static let design = "srv_capture_design"
    static let bots = "srv_capture_bots"
}
