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

    func testDisplayItemsKeepSharedOrderIncludingAFifthTab() {
        // Phone bar (stdrc 2026-10-21): four tabs with the Search action directly left of More.
        let keys = ["home", "activity", "tasks", "search", "more"]

        let shown = RaftIosTabBarItemMapping.displayItems(keys.map { spec($0) })

        XCTAssertEqual(shown.map(\.key), keys)
        XCTAssertEqual(shown.map(\.title), keys.map { "title-\($0)" })
    }

    func testSelectedIndexFollowsSharedKeyAndIgnoresNonTabDestinations() {
        let keys = ["home", "activity", "tasks", "members", "settings"]

        XCTAssertEqual(RaftIosTabBarItemMapping.selectedIndex(of: "home", in: keys), 0)
        XCTAssertEqual(RaftIosTabBarItemMapping.selectedIndex(of: "members", in: keys), 3)
        XCTAssertNil(RaftIosTabBarItemMapping.selectedIndex(of: "rail_search", in: keys))
        XCTAssertNil(RaftIosTabBarItemMapping.selectedIndex(of: nil, in: keys))
    }

    func testBandOfABottomBarSpansFromItsTopToTheContainerBottom() {
        let container = CGRect(x: 0, y: 0, width: 390, height: 844)
        let band = RaftIosTabBarItemMapping.band(
            container: container,
            tabBar: CGRect(x: 0, y: 761, width: 390, height: 83)
        )
        XCTAssertEqual(band, RaftIosTabBarBand(edge: .bottom, points: 83))
    }

    func testBandOfSideBarsUsesTheirPhysicalEdge() {
        let container = CGRect(x: 0, y: 0, width: 951, height: 669)
        XCTAssertEqual(
            RaftIosTabBarItemMapping.band(container: container, tabBar: CGRect(x: 851, y: 0, width: 60, height: 669)),
            RaftIosTabBarBand(edge: .right, points: 100)
        )
        XCTAssertEqual(
            RaftIosTabBarItemMapping.band(container: container, tabBar: CGRect(x: 0, y: 0, width: 80, height: 669)),
            RaftIosTabBarBand(edge: .left, points: 80)
        )
    }

    // MARK: - iOS 26 server button

    private func serverSpec() -> RaftIosTabBarItemSpec {
        RaftIosTabBarItemSpec(
            key: "more",
            title: "Botiverse",
            systemImageName: "server.rack",
            selectedSystemImageName: "server.rack",
            badgeValue: nil,
            enabled: true,
            opensPanel: true,
            accessibilityLabel: "Current server: Botiverse",
            avatarFallbackLabel: "B"
        )
    }

    // MARK: - iOS 26: the server tab is the system's own round glass button

    @available(iOS 26.0, *)
    private final class SystemTabsDelegate: NSObject, UITabBarControllerDelegate {
        let tabs: RaftIosSystemTabs
        var asked: [String] = []
        init(tabs: RaftIosSystemTabs) { self.tabs = tabs }
        func tabBarController(_ tabBarController: UITabBarController, shouldSelectTab tab: UITab) -> Bool {
            asked.append(tabs.key(for: tab) ?? "?")
            return false
        }
    }

    @available(iOS 26.0, *)
    private struct LiveBar {
        let window: UIWindow
        let controller: UITabBarController
        let tabs: RaftIosSystemTabs
        let delegate: SystemTabsDelegate
    }

    @available(iOS 26.0, *)
    private func makeSystemBar() -> LiveBar {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        // Sized from the test host's scene, not UIScreen.main (one screen is not one window).
        let fallbackBounds = CGRect(x: 0, y: 0, width: 390, height: 844)
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow(frame: fallbackBounds)
        window.frame = scene?.coordinateSpace.bounds ?? fallbackBounds
        window.overrideUserInterfaceStyle = .dark
        let controller = UITabBarController()
        controller.traitOverrides.horizontalSizeClass = .compact
        controller.view.backgroundColor = UIColor(red: 1, green: 0, blue: 1, alpha: 1)
        let tabs = RaftIosSystemTabs()
        tabs.makeContent = { _ in
            let content = UIViewController()
            content.view.backgroundColor = UIColor(red: 1, green: 0, blue: 1, alpha: 1)
            return content
        }
        let pages = [
            ("home", "house"), ("activity", "waveform.path.ecg"), ("tasks", "checkmark.square"), ("search", "magnifyingglass"),
        ].map { key, symbol in
            RaftIosTabBarItemSpec(
                key: key, title: key.capitalized, systemImageName: symbol, selectedSystemImageName: symbol,
                badgeValue: key == "activity" ? "76" : nil, enabled: true
            )
        }
        let red = Int32(bitPattern: 0xFFE04040)
        let avatar = RaftIosServerAvatarImage.image(argb: Array(repeating: red, count: 16), width: 4, height: 4)
        tabs.apply(pages + [serverSpec()], panelImage: avatar, to: controller)
        tabs.select("home", in: controller)
        let delegate = SystemTabsDelegate(tabs: tabs)
        controller.delegate = delegate
        window.rootViewController = controller
        window.isHidden = false
        controller.view.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.8))
        return LiveBar(window: window, controller: controller, tabs: tabs, delegate: delegate)
    }

    /// Test-only: the bar's glass platters (the capsule and the round button) from its view tree.
    private func platters(in tabBar: UIView, to target: UIView) -> [CGRect] {
        var frames: [CGRect] = []
        func walk(_ view: UIView) {
            for sub in view.subviews where !sub.isHidden {
                if String(describing: type(of: sub)).contains("Platter") {
                    frames.append(sub.convert(sub.bounds, to: target))
                } else {
                    walk(sub)
                }
            }
        }
        walk(tabBar)
        return frames.sorted { $0.minX < $1.minX }
    }

    private func controls(in view: UIView) -> [UIControl] {
        view.subviews.flatMap { sub -> [UIControl] in ((sub as? UIControl).map { [$0] } ?? []) + controls(in: sub) }
    }

    private func screenshot(_ window: UIWindow) -> UIImage {
        UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
    }

    private func printPNG(_ image: UIImage, top: CGFloat, tag: String) {
        let size = CGSize(width: image.size.width, height: image.size.height - top)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2
        let crop = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(at: CGPoint(x: 0, y: -top)) }
        // Also written to a file so the bar can be looked at, not only measured.
        if let data = crop.pngData() {
            try? data.write(to: URL(fileURLWithPath: "/tmp/\(tag).png"))
        }
        guard let b64 = crop.pngData()?.base64EncodedString() else { return }
        stride(from: 0, to: b64.count, by: 2000).forEach { start in
            let a = b64.index(b64.startIndex, offsetBy: start)
            let b = b64.index(a, offsetBy: min(2000, b64.count - start))
            print("\(tag) \(b64[a..<b])")
        }
    }

    func testTappingTheServerTabAsksTheDelegateAndSelectsNothing() throws {
        guard #available(iOS 26.0, *) else { throw XCTSkip("the separate server button is iOS 26 only") }
        let bar = makeSystemBar()
        defer { bar.window.isHidden = true }
        let tabBar = bar.controller.tabBar
        let before = platters(in: tabBar, to: bar.window)
        let round = try XCTUnwrap(before.last, "no round button")
        // Test-only: the system's control for the round button (the control filling its platter).
        let control = try XCTUnwrap(
            controls(in: tabBar).first { $0.convert($0.bounds, to: bar.window).midX > round.minX },
            "no control in the round button"
        )
        control.sendActions(for: .touchUpInside)
        if bar.delegate.asked.isEmpty { control.sendActions(for: .primaryActionTriggered) }
        if bar.delegate.asked.isEmpty { _ = control.accessibilityActivate() }
        RunLoop.main.run(until: Date().addingTimeInterval(0.8))
        print("RAFT_SYSTEMTAB_TAP control=\(type(of: control)) asked=\(bar.delegate.asked) selected=\(bar.controller.selectedTab?.identifier ?? "nil")")
        let image = screenshot(bar.window)
        printPNG(image, top: max(0, tabBar.frame.minY - 40), tag: "RAFT_SYSTEMTAB_TAP_PNG")
        // The server tab's tap reached the delegate (which runs the shell's handler: the panel).
        XCTAssertEqual(bar.delegate.asked.first, "more", "asked=\(bar.delegate.asked)")
        // Nothing moved: Home is still selected, no search UI took over the bar.
        XCTAssertEqual(bar.controller.selectedTab?.identifier, "home")
        XCTAssertNil(bar.controller.presentedViewController)
        XCTAssertEqual(platters(in: tabBar, to: bar.window), before)
    }
}

