import shared
import UIKit

/// App-shell page content on iOS: the shell's Compose controller plus the system tab bar.
///
/// The app shell is one Compose page whose bar items (Home, Activity, Tasks, Search, More) are
/// retained inside a single composition with shared stores, realtime ingest and adaptive
/// panes, so the tabs cannot be split into one Compose controller per tab without duplicating
/// the shell. Instead this controller layers a real `UITabBarController` over the full-screen
/// Compose content:
///
/// - the system controller owns and lays out the `UITabBar` (Liquid Glass on iOS 26+, the
///   standard bar before), its tab children are empty, transparent placeholders;
/// - `RaftIosTabBarOverlayView` passes every touch outside the bar through to Compose, so
///   content scrolls and is tappable under the floating glass bar;
/// - shared publishes tabs, badges, selection and visibility through `RaftNativeTabBarBridge`
///   (registered for this page in `RaftNativeTabBarRegistry`), and taps go back through
///   `selectTab`, which runs the shell's tab handler (switch, re-tap scroll-to-top, ...);
/// - the edge the bar is laid out on and the band it covers (`RaftIosTabBarItemMapping.band`, from
///   its actual frame; a top or side bar under newer SDKs is not a bottom band) are reported to
///   shared, which insets content by it instead of the Compose bar height.
///
/// Pushed pages (thread, channel, composer, ...) are pushed over the whole shell page by the
/// navigation controller, so they cover this bar exactly as they covered the Compose one.
/// A tab bar provider that does not keep its bar alive: the bar's controller goes away with its
/// page, and the provider must not keep it (or itself) alive past that.
private final class WeakTabBarProvider {
    private weak var _bar: UIView?
    private let provider: () -> UIView?

    init(_ provider: @escaping () -> UIView?) {
        self.provider = provider
        _bar = provider()
    }

    var bar: UIView? {
        if let _bar = _bar {
            return _bar
        }
        return provider()
    }
}

final class RaftIosTabBarController: UIViewController {
    /// Only the app shell renders the bottom tabs.
    static func hostsNativeTabBar(pageName: String) -> Bool {
        pageName == "compose_slock_app_shell"
    }

    /// pageData key carrying `hostId` to the shared page (identity only).
    static var hostIdPageDataKey: String {
        RaftNativeTabBarKt.RaftNativeTabBarHostIdPageDataKey
    }

    let hostId: String
    private let content: UIViewController
    private let bridge: RaftNativeTabBarBridge
    private let systemTabBarController = RaftIosSystemTabBarController()
    /// The live tab bar of [window], for the host facts that measure it (the iPhone Duo side
    /// column needs how much of the strip the bar takes). The bar is not in the window's own
    /// view tree - it is a child of this controller, which the page hosts - so it is reached here.
    ///
    /// The bar is matched to the window it is in, so a second window (an iPad with several
    /// scenes) is never measured against another window's bar.
    @objc(liveTabBarInWindow:)
    static func liveTabBar(in window: UIWindow?) -> UIView? {
        guard let window = window else { return nil }
        // The dead providers are dropped as they are found, so the list does not grow with the
        // number of pages that have been opened.
        _liveTabBarProviders.removeAll { $0.bar == nil }
        for provider in _liveTabBarProviders {
            if provider.bar?.window === window {
                return provider.bar
            }
        }
        return nil
    }

    /// The registered bars. Each is held weakly: a bar's controller goes away with its page, and
    /// a dead provider would otherwise be kept alive here for the life of the process.
    private static var _liveTabBarProviders: [WeakTabBarProvider] = []

    /// Posted when the bar lays out; the page republishes the window facts, which include how
    /// much of the side column the bar takes.
    @objc static var didLayoutNotificationName: String { "RaftIosTabBarController.didLayout" }

    /// Registers a live bar. Each page owns one tab bar controller, so there is one per window.
    @objc static func setLiveTabBarProvider(_ provider: @escaping () -> UIView?) {
        _liveTabBarProviders.append(WeakTabBarProvider(provider))
    }
    private let overlayView = RaftIosTabBarOverlayView()
    private lazy var stateListener = RaftIosTabBarStateListener(owner: self)
    private var slotsByKey: [String: RaftIosTabSlotViewController] = [:]
    private var tabKeys: [String] = []
    private var tabBarVisible: Bool?
    private var reportedBand: RaftIosTabBarBand?
    private var tapGate = RaftIosTabTapGate()
    /// iOS 26: the bar's system tabs (`RaftIosSystemTabs`); nil before iOS 26.
    private var systemTabs: AnyObject?
    private var serverAvatarSource: RaftNativeTabBarAvatar?
    private var serverAvatarImage: UIImage?

    /// PROTOTYPE capture script: the live shell bar, and a tab selected as a tap would.
    static weak var scriptCurrent: RaftIosTabBarController?

    func scriptSelectTab(_ key: String) -> Bool {
        bridge.selectTab(key: key)
    }

    /// [bridge] must already be registered under [hostId]; this controller unregisters it.
    init(content: UIViewController, bridge: RaftNativeTabBarBridge, hostId: String) {
        self.content = content
        self.bridge = bridge
        self.hostId = hostId
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    deinit {
        bridge.detachListener()
        RaftNativeTabBarRegistry.shared.unregister(hostId: hostId, bridge: bridge)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear

        addChild(content)
        content.view.frame = view.bounds
        content.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(content.view)
        content.didMove(toParent: self)

        overlayView.frame = view.bounds
        overlayView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(overlayView)

        let tabs = systemTabBarController
        tabs.delegate = self
        tabs.onLayout = { [weak self] in
            self?.reportBand()
            // The bar's own layout decides how much of the side column it takes, so the window
            // facts are republished once it has laid out (the iPhone Duo side column reports how
            // much of the strip the bar occupies).
            NotificationCenter.default.post(
                name: Notification.Name(RaftIosTabBarController.didLayoutNotificationName),
                object: nil
            )
        }
        addChild(tabs)
        // Always a bottom bar: iPadOS 18+ would otherwise move regular-width tabs to the top.
        if #available(iOS 17.0, *) {
            tabs.traitOverrides.horizontalSizeClass = .compact
        } else {
            setOverrideTraitCollection(UITraitCollection(horizontalSizeClass: .compact), forChild: tabs)
        }
        tabs.view.frame = overlayView.bounds
        tabs.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        tabs.view.backgroundColor = .clear
        overlayView.addSubview(tabs.view)
        tabs.didMove(toParent: self)
        overlayView.tabBar = tabs.tabBar
        RaftIosTabBarController.setLiveTabBarProvider { [weak tabs] in tabs?.tabBar }
        tabs.tabBar.tintColor = .label
        applyAppearance()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appearanceDidChange),
            name: RaftIosAppearanceHost.didChangeNotification,
            object: nil
        )
        applyVisibility(false, animated: false)

        // Replays the current shared state.
        bridge.attachListener(listener: stateListener)
        RaftIosTabBarController.scriptCurrent = self
    }

    /// The bar takes the app's theme choice itself. Inherited from the window alone, an app set
    /// to Dark on a phone in Light mode still showed a light glass bar.
    private func applyAppearance() {
        let style = RaftIosAppearanceHost.currentStyle
        systemTabBarController.overrideUserInterfaceStyle = style
        systemTabBarController.tabBar.overrideUserInterfaceStyle = style
    }

    @objc private func appearanceDidChange() {
        applyAppearance()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        reportBand()
    }

    // MARK: - Shared state -> UIKit

    fileprivate func apply(_ state: RaftNativeTabBarState) {
        if RaftNativeNavProto.realScene != nil {
            print("RAFT_PROTO_TABS keys=\(state.items.map(\.key).joined(separator: ",")) selected=\(state.selectedKey ?? "-")")
        }
        let shared = state.items.map(RaftIosTabBarItemSpec.init(shared:))
        let displayed = RaftIosTabBarItemMapping.displayItems(shared)
        let specs = displayed
        if displayed.count < shared.count {
            RaftIosDiagnosticsRecord(
                "warn",
                "RaftIosTabBar",
                "tab_items_dropped published=\(shared.count) shown=\(displayed.count)"
            )
        }
        let keys = specs.map(\.key)
        if #available(iOS 26.0, *) {
            // The system tabs; the server tab is a UISearchTab, UIKit's own round glass button.
            let tabs = (systemTabs as? RaftIosSystemTabs) ?? RaftIosSystemTabs()
            systemTabs = tabs
            let panelKey = specs.first(where: \.opensPanel)?.key
            let avatar = panelKey.flatMap { key in state.items.first { $0.key == key }?.avatar }
            tabs.apply(specs, panelImage: serverAvatar(avatar), to: systemTabBarController)
            tabs.select(state.selectedKey, in: systemTabBarController)
            applyVisibility(state.visible && !keys.isEmpty, animated: view.window != nil)
            return
        }
        if RaftIosTabBarItemMapping.needsRebuild(currentKeys: tabKeys, nextKeys: keys) {
            var slots: [String: RaftIosTabSlotViewController] = [:]
            let controllers: [RaftIosTabSlotViewController] = keys.map { key in
                let slot = slotsByKey[key] ?? RaftIosTabSlotViewController(tabKey: key)
                slots[key] = slot
                return slot
            }
            slotsByKey = slots
            tabKeys = keys
            systemTabBarController.setViewControllers(controllers, animated: false)
        }
        for spec in specs {
            slotsByKey[spec.key]?.apply(spec)
        }
        if let index = RaftIosTabBarItemMapping.selectedIndex(of: state.selectedKey, in: keys),
           systemTabBarController.selectedIndex != index {
            systemTabBarController.selectedIndex = index
        }
        applyVisibility(state.visible && !keys.isEmpty, animated: view.window != nil)
    }

    /// The server's decoded avatar, converted once per published image.
    private func serverAvatar(_ avatar: RaftNativeTabBarAvatar?) -> UIImage? {
        if avatar !== serverAvatarSource {
            serverAvatarSource = avatar
            serverAvatarImage = avatar.flatMap { avatar in
                let count = Int(avatar.argb.size)
                let pixels = (0..<count).map { avatar.argb.get(index: Int32($0)) }
                return RaftIosServerAvatarImage.image(argb: pixels, width: Int(avatar.width), height: Int(avatar.height))
            }
        }
        return serverAvatarImage
    }

    private func applyVisibility(_ visible: Bool, animated: Bool) {
        guard tabBarVisible != visible else { return }
        tabBarVisible = visible
        overlayView.isUserInteractionEnabled = visible
        systemTabBarController.view.accessibilityElementsHidden = !visible
        if #available(iOS 18.0, *) {
            systemTabBarController.setTabBarHidden(!visible, animated: animated)
        } else {
            systemTabBarController.tabBar.isHidden = !visible
        }
        if visible {
            view.setNeedsLayout()
        }
    }

    /// Reports the edge the visible bar is laid out on and the band it covers, from its actual
    /// frame (not assuming a bottom bar); skipped while hidden so a hide/show cycle does not flap
    /// the shared content inset.
    private func reportBand() {
        guard tabBarVisible == true, let container = systemTabBarController.viewIfLoaded else { return }
        let tabBar = systemTabBarController.tabBar
        guard !tabBar.isHidden, tabBar.superview != nil else { return }
        let frame = tabBar.convert(tabBar.bounds, to: container)
        guard let band = RaftIosTabBarItemMapping.band(container: container.bounds, tabBar: frame),
              band.points > 0 else { return }
        if let reported = reportedBand, reported.edge == band.edge, abs(reported.points - band.points) < 0.5 {
            return
        }
        if band.edge != .bottom, reportedBand?.edge != band.edge {
            RaftIosDiagnosticsRecord(
                "info",
                "RaftIosTabBar",
                "tab_bar_edge edge=\(band.edge.rawValue) points=\(band.points)"
            )
        }
        reportedBand = band
        bridge.updateBand(edge: band.edge.rawValue, points: Double(band.points))
    }
}

// MARK: - UIKit -> shared

extension RaftIosTabBarController: UITabBarControllerDelegate {
    /// Shared owns the selection: every tap (including a re-tap of the selected tab) goes to the
    /// shell's tab handler and UIKit never switches on its own; the new selection arrives
    /// through the published state. Search is an ordinary tab (stdrc 2026-10-21, option B).
    func tabBarController(
        _ tabBarController: UITabBarController,
        shouldSelect viewController: UIViewController
    ) -> Bool {
        guard let slot = viewController as? RaftIosTabSlotViewController else { return false }
        handleTabTap(key: slot.tabKey, searchAction: slot.searchAction)
        return false
    }

    /// The system-tab path (iOS 18+ asks this; the iOS 26 bar is built from `UITab`s, the server
    /// being a `UISearchTab`). Refusing it keeps the selection, and the separate search button
    /// never takes it either, so pressing the server tab moves nothing in the bar.
    @available(iOS 18.0, *)
    func tabBarController(_ tabBarController: UITabBarController, shouldSelectTab tab: UITab) -> Bool {
        var key: String?
        if #available(iOS 26.0, *) {
            key = (systemTabs as? RaftIosSystemTabs)?.key(for: tab)
        }
        if key == nil, let slot = tab.viewController as? RaftIosTabSlotViewController {
            key = slot.tabKey
        }
        guard let key = key else { return false }
        handleTabTap(key: key, searchAction: false)
        return false
    }

    /// One action per tap: UIKit may ask more than once for one tap (both delegate paths, or
    /// twice on one), which toggled the server panel open and shut again.
    private func handleTabTap(key: String, searchAction: Bool) {
        guard tapGate.admit(
            key: key,
            now: ProcessInfo.processInfo.systemUptime,
            touchBeganAt: overlayView.lastTouchBeganAt
        ) else {
            RaftIosDiagnosticsRecord("info", "RaftIosTabBar", "tab_tap_repeat_dropped key=\(key)")
            return
        }
        if searchAction {
            RaftIosDiagnosticsRecord("info", "RaftIosTabBar", "search_action_tap")
        }
        if !bridge.selectTab(key: key) {
            RaftIosDiagnosticsRecord("warn", "RaftIosTabBar", "tab_tap_unhandled key=\(key)")
        }
        RaftIosTopBandDiagnostics.noteTabSwitch()
    }
}

private extension RaftIosTabBarItemSpec {
    init(shared item: RaftNativeTabBarItem) {
        self.init(
            key: item.key,
            title: item.title,
            systemImageName: item.systemImageName,
            selectedSystemImageName: item.selectedSystemImageName,
            badgeValue: item.badgeValue,
            enabled: item.enabled,
            searchAction: item.searchAction,
            imageAssetName: item.imageAssetName,
            opensPanel: item.opensPanel,
            accessibilityLabel: item.accessibilityLabel,
            avatarFallbackLabel: item.avatarFallbackLabel
        )
    }
}

/// Weak forwarder: the bridge (kept alive by the registry) must not retain the controller.
private final class RaftIosTabBarStateListener: NSObject, RaftNativeTabBarListener {
    weak var owner: RaftIosTabBarController?

    init(owner: RaftIosTabBarController) {
        self.owner = owner
    }

    func onNativeTabBarStateChanged(state: RaftNativeTabBarState) {
        if Thread.isMainThread {
            owner?.apply(state)
        } else {
            let target = owner
            DispatchQueue.main.async { [weak target] in target?.apply(state) }
        }
    }
}

/// The system tab bar controller; reports layout so the bar band can be published.
private final class RaftIosSystemTabBarController: UITabBarController {
    var onLayout: (() -> Void)?

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        onLayout?()
    }
}

/// Hosts the system tab bar controller above the Compose content and only claims touches that
/// land on the tab bar itself.
private final class RaftIosTabBarOverlayView: UIView {
    weak var tabBar: UITabBar?
    /// Timestamp (system uptime) of the last touch-down on the bar: one tap = one touch sequence.
    private(set) var lastTouchBeganAt: TimeInterval?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard let hit = super.hitTest(point, with: event),
              let tabBar = tabBar else {
            return nil
        }
        guard hit.isDescendant(of: tabBar) else { return nil }
        if let event = event, event.type == .touches {
            lastTouchBeganAt = event.timestamp
        }
        return hit
    }
}

/// Empty, non-interactive tab child: it only carries the `UITabBarItem` for one shared tab.
private final class RaftIosTabSlotViewController: UIViewController {
    let tabKey: String
    /// The Search action slot: tapping it opens search, it never becomes the selection.
    private(set) var searchAction = false

    init(tabKey: String) {
        self.tabKey = tabKey
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func loadView() {
        let view = UIView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        self.view = view
    }

    func apply(_ spec: RaftIosTabBarItemSpec) {
        searchAction = spec.searchAction
        let item: UITabBarItem = tabBarItem
        if item.title != spec.title {
            item.title = spec.title
        }
        item.accessibilityLabel = spec.accessibilityLabel ?? spec.title
        item.accessibilityIdentifier = "raft-tab-\(spec.key)"
        // The web's Lucide glyph (template asset, tinted by the bar) when the app ships it;
        // Lucide has no filled variants, so selection is the tint alone. SF Symbols otherwise.
        if let assetName = spec.imageAssetName,
           let asset = UIImage(named: assetName)?.withRenderingMode(.alwaysTemplate) {
            item.image = asset
            item.selectedImage = asset
        } else {
            item.image = Self.symbol(RaftIosTabBarItemMapping.imageNameCandidates(preferred: spec.systemImageName))
            item.selectedImage = Self.symbol(
                RaftIosTabBarItemMapping.imageNameCandidates(
                    preferred: spec.selectedSystemImageName,
                    fallback: spec.systemImageName
                )
            )
        }
        // The system badge, counts only (a dot's count comes from shared; see the spec).
        if item.badgeValue != spec.badgeValue {
            item.badgeValue = spec.badgeValue
        }
        if item.isEnabled != spec.enabled {
            item.isEnabled = spec.enabled
        }
    }

    private static func symbol(_ names: [String]) -> UIImage? {
        for name in names {
            if let image = UIImage(systemName: name) {
                return image
            }
        }
        return nil
    }
}
