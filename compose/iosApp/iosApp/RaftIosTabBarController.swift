import shared
import UIKit

/// App-shell page content on iOS: the shell's Compose controller plus the system tab bar.
///
/// The app shell is one Compose page whose tabs (Home, Activity, Tasks, More, ...) are
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
        tabs.onLayout = { [weak self] in self?.reportBottomBand() }
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
        tabs.tabBar.tintColor = .label
        applyVisibility(false, animated: false)

        // Replays the current shared state.
        bridge.attachListener(listener: stateListener)
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
    /// through the published state.
    func tabBarController(
        _ tabBarController: UITabBarController,
        shouldSelect viewController: UIViewController
    ) -> Bool {
        guard let slot = viewController as? RaftIosTabSlotViewController else { return false }
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
            attentionDot: item.attentionDot
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
        return hit
    }
}

/// Empty, non-interactive tab child: it only carries the `UITabBarItem` for one shared tab.
private final class RaftIosTabSlotViewController: UIViewController {
    let tabKey: String

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
        let item: UITabBarItem = tabBarItem
        if item.title != spec.title {
            item.title = spec.title
        }
        item.accessibilityLabel = spec.title
        item.accessibilityIdentifier = "raft-tab-\(spec.key)"
        item.image = Self.symbol(RaftIosTabBarItemMapping.imageNameCandidates(preferred: spec.systemImageName))
        item.selectedImage = Self.symbol(
            RaftIosTabBarItemMapping.imageNameCandidates(
                preferred: spec.selectedSystemImageName,
                fallback: spec.systemImageName
            )
        )
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
