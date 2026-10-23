import CoreGraphics
import UIKit
import XCTest

final class RaftIosTabBarItemMappingTests: XCTestCase {
    private func spec(_ key: String, badge: String? = nil, enabled: Bool = true) -> RaftIosTabBarItemSpec {
        RaftIosTabBarItemSpec(
            key: key,
            title: "title-\(key)",
            systemImageName: "symbol-\(key)",
            selectedSystemImageName: "symbol-\(key).fill",
            badgeValue: badge,
            enabled: enabled
        )
    }

    func testBadgeValueKeepsSharedTextAndHidesEmptyOrZero() {
        let cases: [(String?, String?)] = [
            (nil, nil),
            ("", nil),
            ("  ", nil),
            ("0", nil),
            ("7", "7"),
            ("99", "99"),
            ("99+", "99+"),
        ]
        for (shared, expected) in cases {
            XCTAssertEqual(
                RaftIosTabBarItemMapping.badgeValue(fromShared: shared),
                expected,
                "shared=\(String(describing: shared))"
            )
            XCTAssertEqual(spec("activity", badge: shared).badgeValue, expected)
        }
    }

    func testAttentionDotIsTheHostsSmallDotNeverAnEmptySystemBadge() {
        // UIKit draws badgeValue "" as a full-size badge: the dot must never become one.
        XCTAssertNil(RaftIosTabBarItemMapping.badgeValue(fromShared: nil))
        XCTAssertTrue(RaftIosTabBarItemMapping.showsDot(fromShared: nil, attentionDot: true))
        XCTAssertTrue(RaftIosTabBarItemMapping.showsDot(fromShared: "0", attentionDot: true))
        XCTAssertFalse(RaftIosTabBarItemMapping.showsDot(fromShared: nil, attentionDot: false))
        // A count wins over the dot.
        XCTAssertFalse(RaftIosTabBarItemMapping.showsDot(fromShared: "3", attentionDot: true))
        let dotted = RaftIosTabBarItemSpec(
            key: "activity",
            title: "Activity",
            systemImageName: "waveform.path.ecg.rectangle",
            selectedSystemImageName: "waveform.path.ecg.rectangle.fill",
            badgeValue: nil,
            enabled: true,
            attentionDot: true
        )
        XCTAssertNil(dotted.badgeValue)
        XCTAssertTrue(dotted.showsDot)
        let counted = RaftIosTabBarItemSpec(
            key: "activity",
            title: "Activity",
            systemImageName: "bell",
            selectedSystemImageName: "bell.fill",
            badgeValue: "99+",
            enabled: true,
            attentionDot: true
        )
        XCTAssertEqual(counted.badgeValue, "99+")
        XCTAssertFalse(counted.showsDot)
        XCTAssertFalse(spec("home").showsDot)
    }

    func testDotIsAnEightPointSquareOnTheIconsTopRightCorner() {
        let icon = CGRect(x: 100, y: 10, width: 25, height: 25)
        let dot = RaftIosTabBarItemMapping.dotFrame(iconFrame: icon)
        XCTAssertEqual(dot.size, CGSize(width: 8, height: 8))
        XCTAssertEqual(dot.midX, icon.maxX + 1, accuracy: 0.5)
        XCTAssertEqual(dot.midY, icon.minY + 1, accuracy: 0.5)
        XCTAssertEqual(dot.origin.x, dot.origin.x.rounded())
        XCTAssertEqual(dot.origin.y, dot.origin.y.rounded())
    }

    func testTapGateAdmitsOneActionPerTouchSequence() {
        var gate = RaftIosTabTapGate()
        // UIKit asking twice for one tap on the server tab: only the first toggles the panel.
        XCTAssertTrue(gate.admit(key: "more", now: 10.00, touchBeganAt: 9.90))
        XCTAssertFalse(gate.admit(key: "more", now: 10.01, touchBeganAt: 9.90))
        // A slow press asking at touch-down and touch-up is still one tap.
        XCTAssertFalse(gate.admit(key: "more", now: 11.00, touchBeganAt: 9.90))
        // A second, quick tap (new finger-down) closes it again.
        XCTAssertTrue(gate.admit(key: "more", now: 11.10, touchBeganAt: 11.05))
        // Another tab is never held back.
        XCTAssertTrue(gate.admit(key: "home", now: 11.11, touchBeganAt: 11.05))
        XCTAssertTrue(gate.admit(key: "more", now: 11.12, touchBeganAt: 11.05))
    }

    func testTapGateWithoutTouchesDropsOnlyAQuickRepeat() {
        var gate = RaftIosTabTapGate()
        // VoiceOver / keyboard activations carry no touch.
        XCTAssertTrue(gate.admit(key: "more", now: 1.0, touchBeganAt: nil))
        XCTAssertFalse(gate.admit(key: "more", now: 1.2, touchBeganAt: nil))
        XCTAssertTrue(gate.admit(key: "more", now: 1.0 + RaftIosTabTapGate.repeatWindow + 0.1, touchBeganAt: nil))
        // A stale touch long after the last call is a new activation.
        var touched = RaftIosTabTapGate()
        XCTAssertTrue(touched.admit(key: "more", now: 5.0, touchBeganAt: 4.9))
        XCTAssertTrue(touched.admit(key: "more", now: 5.0 + RaftIosTabTapGate.sameTouchWindow + 0.1, touchBeganAt: 4.9))
    }

    func testDisplayItemsKeepSharedOrderIncludingAFifthTab() {
        // Phone bar (stdrc 2026-10-21): four tabs with the Search action directly left of More.
        let keys = ["home", "activity", "tasks", "search", "more"]

        let shown = RaftIosTabBarItemMapping.displayItems(keys.map { spec($0) })

        XCTAssertEqual(shown.map(\.key), keys)
        XCTAssertEqual(shown.map(\.title), keys.map { "title-\($0)" })
    }

    func testDisplayItemsDropDuplicatesBlankKeysAndTabsBeyondTheSystemLimit() {
        let items = ["home", "home", "", "dms", "activity", "tasks", "members", "settings"].map { spec($0) }

        let shown = RaftIosTabBarItemMapping.displayItems(items)

        XCTAssertEqual(shown.map(\.key), ["home", "dms", "activity", "tasks", "members"])
        XCTAssertEqual(shown.count, RaftIosTabBarItemMapping.maximumTabCount)
    }

    func testSearchActionIsCarriedOnTheSpecAndNeverAPublishedSelection() {
        let search = RaftIosTabBarItemSpec(
            key: "search",
            title: "Search",
            systemImageName: "magnifyingglass",
            selectedSystemImageName: "magnifyingglass",
            badgeValue: nil,
            enabled: true,
            searchAction: true
        )
        XCTAssertTrue(search.searchAction)
        XCTAssertFalse(spec("home").searchAction)
        // Shared never publishes "search" as the selection; a nil selection keeps the current tab.
        let keys = ["home", "activity", "tasks", "search", "more"]
        XCTAssertNil(RaftIosTabBarItemMapping.selectedIndex(of: nil, in: keys))
    }

    func testSelectedIndexFollowsSharedKeyAndIgnoresNonTabDestinations() {
        let keys = ["home", "activity", "tasks", "members", "settings"]

        XCTAssertEqual(RaftIosTabBarItemMapping.selectedIndex(of: "home", in: keys), 0)
        XCTAssertEqual(RaftIosTabBarItemMapping.selectedIndex(of: "members", in: keys), 3)
        XCTAssertNil(RaftIosTabBarItemMapping.selectedIndex(of: "rail_search", in: keys))
        XCTAssertNil(RaftIosTabBarItemMapping.selectedIndex(of: nil, in: keys))
    }

    func testOnlyKeyListChangesRebuildTheTabChildren() {
        let four = ["home", "tasks", "members", "settings"]

        XCTAssertFalse(RaftIosTabBarItemMapping.needsRebuild(currentKeys: four, nextKeys: four))
        XCTAssertTrue(RaftIosTabBarItemMapping.needsRebuild(
            currentKeys: four,
            nextKeys: ["home", "activity", "tasks", "members", "settings"]
        ))
        XCTAssertTrue(RaftIosTabBarItemMapping.needsRebuild(
            currentKeys: four,
            nextKeys: ["tasks", "home", "members", "settings"]
        ))
    }

    func testImageCandidatesPreferSelectedSymbolThenBaseThenCircle() {
        XCTAssertEqual(
            RaftIosTabBarItemMapping.imageNameCandidates(preferred: "bell.fill", fallback: "bell"),
            ["bell.fill", "bell", "circle"]
        )
        XCTAssertEqual(
            RaftIosTabBarItemMapping.imageNameCandidates(preferred: "ellipsis", fallback: "ellipsis"),
            ["ellipsis", "circle"]
        )
        XCTAssertEqual(RaftIosTabBarItemMapping.imageNameCandidates(preferred: ""), ["circle"])
    }

    func testBottomBandSpansFromBarTopToContainerBottom() {
        XCTAssertEqual(RaftIosTabBarItemMapping.bottomBand(containerHeight: 844, tabBarMinY: 761), 83)
        XCTAssertEqual(RaftIosTabBarItemMapping.bottomBand(containerHeight: 844, tabBarMinY: 900), 0)
        XCTAssertEqual(RaftIosTabBarItemMapping.bottomBand(containerHeight: 844, tabBarMinY: -10), 844)
        XCTAssertEqual(RaftIosTabBarItemMapping.bottomBand(containerHeight: 844, tabBarMinY: .nan), 0)
    }

    // MARK: - A real system tab bar (the hosted simulator's iOS)

    private final class CountingDelegate: NSObject, UITabBarControllerDelegate {
        var asked: [String] = []
        var acted: [String] = []
        var gate = RaftIosTabTapGate()

        func tabBarController(_ tabBarController: UITabBarController, shouldSelect viewController: UIViewController) -> Bool {
            let key = viewController.tabBarItem.accessibilityIdentifier ?? ""
            asked.append(key)
            if gate.admit(key: key, now: ProcessInfo.processInfo.systemUptime, touchBeganAt: nil) {
                acted.append(key)
            }
            return false
        }
    }

    private let liveKeys = ["home", "activity", "tasks", "search", "more"]

    private func makeLiveTabBar() -> (UIWindow, UITabBarController) {
        let window: UIWindow
        if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            window = UIWindow(windowScene: scene)
        } else {
            window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        }
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        let tabs = UITabBarController()
        tabs.viewControllers = liveKeys.map { key in
            let child = UIViewController()
            child.tabBarItem.title = key == "more" ? "xteam" : key
            child.tabBarItem.image = UIImage(systemName: key == "more" ? "server.rack" : "house")
            child.tabBarItem.accessibilityIdentifier = "raft-tab-\(key)"
            return child
        }
        window.rootViewController = tabs
        window.isHidden = false
        tabs.view.layoutIfNeeded()
        tabs.tabBar.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        tabs.tabBar.layoutIfNeeded()
        return (window, tabs)
    }

    func testIconLocatorFindsEveryTabsIconOnTheSystemBar() {
        let (window, tabs) = makeLiveTabBar()
        defer { window.isHidden = true }
        let tabBar = tabs.tabBar
        var frames: [CGRect] = []
        for (index, key) in liveKeys.enumerated() {
            let item = tabs.viewControllers![index].tabBarItem!
            let frame = RaftIosTabBarIconLocator.iconFrame(identifier: "raft-tab-\(key)", item: item, in: tabBar)
            XCTAssertNotNil(frame, "icon of \(key) not found on iOS \(UIDevice.current.systemVersion)")
            guard let frame = frame else { continue }
            XCTAssertTrue(tabBar.bounds.insetBy(dx: -1, dy: -1).contains(frame), "\(key) icon \(frame) outside bar \(tabBar.bounds)")
            XCTAssertLessThan(frame.width, 60, "\(key) matched a view larger than an icon: \(frame)")
            frames.append(frame)
        }
        // Left to right in tab order, the last tab's icon on the trailing side.
        XCTAssertEqual(frames.map(\.midX), frames.map(\.midX).sorted())
        if let last = frames.last {
            let dot = RaftIosTabBarItemMapping.dotFrame(iconFrame: last)
            XCTAssertTrue(tabBar.bounds.contains(dot.origin), "last tab dot \(dot) off the bar \(tabBar.bounds)")
        }
    }

    func testLastTabActivationReachesTheDelegateAndActsOnce() throws {
        let (window, tabs) = makeLiveTabBar()
        defer { window.isHidden = true }
        let delegate = CountingDelegate()
        tabs.delegate = delegate
        let lastId = "raft-tab-more"
        func find(_ root: UIView) -> UIView? {
            for sub in root.subviews {
                if sub.accessibilityIdentifier == lastId { return sub }
                if let found = find(sub) { return found }
            }
            return nil
        }
        guard let button = find(tabs.tabBar) else {
            throw XCTSkip("no view carries \(lastId) on iOS \(UIDevice.current.systemVersion)")
        }
        if let control = button as? UIControl {
            control.sendActions(for: .touchUpInside)
        } else {
            _ = button.accessibilityActivate()
        }
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        print("RAFT_TABBAR_PROBE iOS=\(UIDevice.current.systemVersion) asked=\(delegate.asked.count) acted=\(delegate.acted.count)")
        if delegate.asked.isEmpty {
            throw XCTSkip("this iOS does not activate a tab from code (asked 0 times)")
        }
        XCTAssertTrue(delegate.asked.allSatisfy { $0 == lastId })
        XCTAssertEqual(delegate.acted, [lastId], "asked \(delegate.asked.count) times for one activation")
        XCTAssertEqual(tabs.selectedIndex, 0, "the delegate refused the selection")
    }
}
