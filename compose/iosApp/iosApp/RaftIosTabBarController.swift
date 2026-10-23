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
/// - the bar's bottom band is reported to shared, which insets content by it instead of the
///   Compose bar height.
///
/// Pushed pages (thread, channel, composer, ...) are pushed over the whole shell page by the
/// navigation controller, so they cover this bar exactly as they covered the Compose one.
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
    private let overlayView = RaftIosTabBarOverlayView()
    private lazy var stateListener = RaftIosTabBarStateListener(owner: self)
    private var slotsByKey: [String: RaftIosTabSlotViewController] = [:]
    private var tabKeys: [String] = []
    private var tabBarVisible: Bool?
    private var reportedBottomBand: CGFloat = -1
    private var tapGate = RaftIosTabTapGate()
    /// Our attention dots, over the bar (never hit-testable). Keyed by tab key.
    private let dotsView = RaftIosTabBarDotsView()
    private var dotViews: [String: UIView] = [:]
    private var dotKeys: Set<String> = []

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
            self?.reportBottomBand()
            self?.layoutDots()
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
        dotsView.frame = tabs.tabBar.bounds
        dotsView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        tabs.tabBar.addSubview(dotsView)
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
        reportBottomBand()
    }

    // MARK: - Shared state -> UIKit

    fileprivate func apply(_ state: RaftNativeTabBarState) {
        let shared = state.items.map(RaftIosTabBarItemSpec.init(shared:))
        let specs = RaftIosTabBarItemMapping.displayItems(shared)
        if specs.count < shared.count {
            RaftIosDiagnosticsRecord(
                "warn",
                "RaftIosTabBar",
                "tab_items_dropped published=\(shared.count) shown=\(specs.count)"
            )
        }
        let keys = specs.map(\.key)
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
        dotKeys = Set(specs.filter(\.showsDot).map(\.key))
        layoutDots()
        // UIKit lays the bar's buttons out after this pass (new items, new titles): place again.
        DispatchQueue.main.async { [weak self] in self?.layoutDots() }
        if let index = RaftIosTabBarItemMapping.selectedIndex(of: state.selectedKey, in: keys),
           systemTabBarController.selectedIndex != index {
            systemTabBarController.selectedIndex = index
        }
        applyVisibility(state.visible && !keys.isEmpty, animated: view.window != nil)
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

    /// Puts each dotted tab's small dot on its icon's top-right corner. A tab whose icon cannot
    /// be found once the bar is laid out falls back to UIKit's own (larger) badge rather than
    /// losing the signal.
    private func layoutDots() {
        let tabBar = systemTabBarController.tabBar
        for (key, dot) in dotViews where !dotKeys.contains(key) {
            dot.removeFromSuperview()
            dotViews[key] = nil
        }
        for (key, slot) in slotsByKey where !dotKeys.contains(key) {
            slot.setDotFallback(false)
        }
        guard !dotKeys.isEmpty else { return }
        let laidOut = tabBar.window != nil && !tabBar.isHidden && tabBar.bounds.width > 0
        guard laidOut else { return }
        tabBar.layoutIfNeeded()
        dotsView.frame = tabBar.bounds
        tabBar.bringSubviewToFront(dotsView)
        for key in dotKeys {
            guard let slot = slotsByKey[key] else { continue }
            let iconFrame = RaftIosTabBarIconLocator.iconFrame(
                identifier: RaftIosTabSlotViewController.tabIdentifier(forKey: key),
                item: slot.tabBarItem,
                in: tabBar
            )
            guard let iconFrame = iconFrame else {
                dotViews[key]?.removeFromSuperview()
                dotViews[key] = nil
                if !slot.dotFallback {
                    RaftIosDiagnosticsRecord("warn", "RaftIosTabBar", "tab_dot_icon_not_found key=\(key)")
                }
                slot.setDotFallback(true)
                continue
            }
            slot.setDotFallback(false)
            let dot = dotViews[key] ?? RaftIosTabBarDotsView.makeDot()
            if dot.superview !== dotsView { dotsView.addSubview(dot) }
            dotViews[key] = dot
            dot.frame = dotsView.convert(RaftIosTabBarItemMapping.dotFrame(iconFrame: iconFrame), from: tabBar)
        }
    }

    /// Reports the band the visible bar covers; skipped while hidden so a hide/show cycle does
    /// not flap the shared content inset.
    private func reportBottomBand() {
        guard tabBarVisible == true, let container = systemTabBarController.viewIfLoaded else { return }
        let tabBar = systemTabBarController.tabBar
        guard !tabBar.isHidden, tabBar.superview != nil else { return }
        let frame = tabBar.convert(tabBar.bounds, to: container)
        let band = RaftIosTabBarItemMapping.bottomBand(
            containerHeight: container.bounds.height,
            tabBarMinY: frame.minY
        )
        guard band > 0, abs(band - reportedBottomBand) >= 0.5 else { return }
        reportedBottomBand = band
        bridge.updateBottomInset(points: Double(band))
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
        // One action per tap: UIKit may ask more than once for one tap, which toggled the server
        // panel open and shut again (the last tab "could not be tapped").
        guard tapGate.admit(
            key: slot.tabKey,
            now: ProcessInfo.processInfo.systemUptime,
            touchBeganAt: overlayView.lastTouchBeganAt
        ) else {
            RaftIosDiagnosticsRecord("info", "RaftIosTabBar", "tab_tap_repeat_dropped key=\(slot.tabKey)")
            return false
        }
        if slot.searchAction {
            RaftIosDiagnosticsRecord("info", "RaftIosTabBar", "search_action_tap")
        }
        if !bridge.selectTab(key: slot.tabKey) {
            RaftIosDiagnosticsRecord("warn", "RaftIosTabBar", "tab_tap_unhandled key=\(slot.tabKey)")
        }
        return false
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
            attentionDot: item.attentionDot,
            searchAction: item.searchAction,
            imageAssetName: item.imageAssetName
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
              let tabBar = tabBar,
              hit.isDescendant(of: tabBar) else {
            return nil
        }
        if let event = event, event.type == .touches {
            lastTouchBeganAt = event.timestamp
        }
        return hit
    }
}

/// Holds the attention dots over the tab bar; never takes a touch or an accessibility focus.
private final class RaftIosTabBarDotsView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    /// raft-ui `Status sm` attention pixel, as the Compose bar draws it: 8pt, 2pt corners,
    /// `attention` (#FF99C3) with a 1.5pt `sticker-ink` (#141111) edge, the same in Light and Dark.
    static func makeDot() -> UIView {
        let dot = UIView(frame: CGRect(x: 0, y: 0, width: RaftIosTabBarItemMapping.dotEdge, height: RaftIosTabBarItemMapping.dotEdge))
        dot.isUserInteractionEnabled = false
        dot.backgroundColor = UIColor(red: 1.0, green: 0x99 / 255.0, blue: 0xC3 / 255.0, alpha: 1)
        dot.layer.cornerRadius = 2
        dot.layer.borderWidth = 1.5
        dot.layer.borderColor = UIColor(red: 0x14 / 255.0, green: 0x11 / 255.0, blue: 0x11 / 255.0, alpha: 1).cgColor
        return dot
    }
}

/// Empty, non-interactive tab child: it only carries the `UITabBarItem` for one shared tab.
private final class RaftIosTabSlotViewController: UIViewController {
    let tabKey: String
    /// The Search action slot: tapping it opens search, it never becomes the selection.
    private(set) var searchAction = false
    /// The dot's icon could not be found: UIKit's own badge stands in for it.
    private(set) var dotFallback = false
    private var badgeValue: String?

    static func tabIdentifier(forKey key: String) -> String {
        "raft-tab-\(key)"
    }

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
        item.accessibilityLabel = spec.title
        item.accessibilityIdentifier = Self.tabIdentifier(forKey: spec.key)
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
        badgeValue = spec.badgeValue
        if !spec.showsDot { dotFallback = false }
        applyBadge()
        if item.isEnabled != spec.enabled {
            item.isEnabled = spec.enabled
        }
    }

    func setDotFallback(_ fallback: Bool) {
        guard dotFallback != fallback else { return }
        dotFallback = fallback
        applyBadge()
    }

    private func applyBadge() {
        let value = badgeValue ?? (dotFallback ? "" : nil)
        if tabBarItem.badgeValue != value {
            tabBarItem.badgeValue = value
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
