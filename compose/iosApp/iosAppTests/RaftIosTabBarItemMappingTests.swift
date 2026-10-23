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

    func testBadgeIsNeverAnEmptySystemBadge() {
        // UIKit draws badgeValue "" as a full-size empty badge (the "dots too big" report):
        // a tab without a count shows no badge; shared sends the count behind a dot.
        for shared in [nil, "", " ", "0"] as [String?] {
            XCTAssertNil(spec("activity", badge: shared).badgeValue, "shared=\(String(describing: shared))")
        }
        XCTAssertEqual(spec("activity", badge: "3").badgeValue, "3")
        XCTAssertEqual(spec("more", badge: "99+").badgeValue, "99+")
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

    private func pixel(_ image: UIImage, _ point: CGPoint) -> (r: Int, g: Int, b: Int, a: Int) {
        let width = Int(image.size.width), height = Int(image.size.height)
        var data = [UInt8](repeating: 0, count: width * height * 4)
        let context = CGContext(
            data: &data, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        UIGraphicsPushContext(context)
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        image.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
        UIGraphicsPopContext()
        let offset = (Int(point.y) * width + Int(point.x)) * 4
        return (Int(data[offset]), Int(data[offset + 1]), Int(data[offset + 2]), Int(data[offset + 3]))
    }

    func testAvatarPixelsBecomeACircleClippedImage() throws {
        // 2x2 opaque red, as shared publishes it (non-premultiplied ARGB).
        let red = Int32(bitPattern: 0xFFFF0000)
        let source = try XCTUnwrap(RaftIosServerAvatarImage.image(argb: [red, red, red, red], width: 2, height: 2))
        XCTAssertNil(RaftIosServerAvatarImage.image(argb: [red], width: 2, height: 2), "size mismatch")
        let circle = RaftIosServerAvatarImage.circle(source, fallbackLabel: "B", diameter: 40)
        XCTAssertEqual(circle.size, CGSize(width: 40, height: 40))
        XCTAssertEqual(circle.renderingMode, .alwaysOriginal)
        let center = pixel(circle, CGPoint(x: 20, y: 20))
        XCTAssertTrue(center.r > 240 && center.g < 15 && center.b < 15 && center.a > 240, "avatar fills the circle: \(center)")
        XCTAssertLessThan(pixel(circle, CGPoint(x: 1, y: 1)).a, 20, "clipped to a circle")
    }

    func testNoAvatarDrawsTheInitialTile() {
        let tile = RaftIosServerAvatarImage.circle(nil, fallbackLabel: "B", diameter: 40)
        // Ink near the edge of the circle (left of the letter), transparent outside it.
        let ink = pixel(tile, CGPoint(x: 4, y: 20))
        XCTAssertTrue(abs(ink.r - 20) < 6 && abs(ink.g - 17) < 6 && abs(ink.b - 17) < 6 && ink.a > 240, "ink: \(ink)")
        XCTAssertLessThan(pixel(tile, CGPoint(x: 1, y: 1)).a, 20)
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
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow(frame: UIScreen.main.bounds)
        window.frame = scene?.screen.bounds ?? UIScreen.main.bounds
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

    /// Rows of [column] (points) whose pixels differ from magenta, from [top] down.
    private func span(_ image: UIImage, column x: CGFloat, from top: CGFloat) -> (CGFloat, CGFloat)? {
        guard let cg = image.cgImage else { return nil }
        let scale = image.scale, width = cg.width, height = cg.height
        var data = [UInt8](repeating: 0, count: width * height * 4)
        let context = CGContext(
            data: &data, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        let px = min(width - 1, max(0, Int(x * scale)))
        var first: Int?, last: Int?
        for row in Int(top * scale)..<height {
            let o = (row * width + px) * 4
            let d = abs(Int(data[o]) - 255) + Int(data[o + 1]) + abs(Int(data[o + 2]) - 255)
            if d > 60 {
                if first == nil { first = row }
                last = row
            }
        }
        guard let f = first, let l = last else { return nil }
        return (CGFloat(f) / scale, CGFloat(l + 1) / scale)
    }

    private func printPNG(_ image: UIImage, top: CGFloat, tag: String) {
        let size = CGSize(width: image.size.width, height: image.size.height - top)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2
        let crop = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(at: CGPoint(x: 0, y: -top)) }
        guard let b64 = crop.pngData()?.base64EncodedString() else { return }
        stride(from: 0, to: b64.count, by: 2000).forEach { start in
            let a = b64.index(b64.startIndex, offsetBy: start)
            let b = b64.index(a, offsetBy: min(2000, b64.count - start))
            print("\(tag) \(b64[a..<b])")
        }
    }

    func testServerTabIsTheSystemsRoundButtonLevelWithTheBar() throws {
        guard #available(iOS 26.0, *) else { throw XCTSkip("the separate server button is iOS 26 only") }
        let bar = makeSystemBar()
        defer { bar.window.isHidden = true }
        let tabBar = bar.controller.tabBar
        // The server tab is the system search tab, with the avatar in colour and no badge.
        let server = try XCTUnwrap(bar.tabs.tab(for: "more"))
        XCTAssertTrue(server is UISearchTab)
        XCTAssertNil(server.badgeValue)
        XCTAssertEqual(server.accessibilityLabel, "Current server: Botiverse")
        XCTAssertEqual(server.image?.renderingMode, .alwaysOriginal)
        XCTAssertEqual(bar.tabs.tab(for: "activity")?.badgeValue, "76")
        XCTAssertEqual(bar.controller.selectedTab?.identifier, "home")
        // UIKit lays the round button out beside the capsule: same top, same height.
        let frames = platters(in: tabBar, to: bar.window)
        XCTAssertEqual(frames.count, 2, "capsule + round button: \(frames)")
        guard frames.count == 2 else { return }
        let capsule = frames[0], button = frames[1]
        let image = screenshot(bar.window)
        let top = max(0, tabBar.frame.minY - 40)
        let capsuleSpan = span(image, column: capsule.minX + 30, from: top)
        let buttonSpan = span(image, column: button.midX, from: top)
        let scale = image.scale
        let geometry =
            "iOS \(UIDevice.current.systemVersion) scale=\(scale) bar=\(tabBar.frame) capsule=\(capsule) button=\(button) " +
            "pixelCapsule=\(String(describing: capsuleSpan)) pixelButton=\(String(describing: buttonSpan))"
        print("RAFT_SYSTEMTAB_GEOM \(geometry)")
        printPNG(image, top: top, tag: "RAFT_SYSTEMTAB_PNG")
        XCTAssertEqual(button.midY, capsule.midY, accuracy: 0.5, geometry)
        XCTAssertEqual(button.height, capsule.height, accuracy: 0.5, geometry)
        XCTAssertGreaterThan(button.minX, capsule.maxX, geometry)
        let c = try XCTUnwrap(capsuleSpan, geometry), b = try XCTUnwrap(buttonSpan, geometry)
        let capsuleCentre = (c.0 + c.1) / 2 * scale, buttonCentre = (b.0 + b.1) / 2 * scale
        print("RAFT_SYSTEMTAB_CENTRES capsule=\(capsuleCentre)px button=\(buttonCentre)px diff=\(buttonCentre - capsuleCentre)px")
        XCTAssertEqual(buttonCentre, capsuleCentre, accuracy: 2, "pixel centres: \(geometry)")
        // The page tabs' titles are laid out whole, inside the capsule.
        func labels(_ view: UIView) -> [UILabel] {
            view.subviews.flatMap { sub -> [UILabel] in ((sub as? UILabel).map { [$0] } ?? []) + labels(sub) }
        }
        let titles = labels(tabBar).filter { !$0.isHidden && $0.window != nil && ($0.text?.isEmpty == false) }
        print("RAFT_SYSTEMTAB_LABELS " + titles.map { "\($0.text ?? "") \($0.convert($0.bounds, to: bar.window).integral) fit=\($0.intrinsicContentSize.width)" }.joined(separator: " | "))
        for label in titles {
            let frame = label.convert(label.bounds, to: bar.window)
            guard capsule.insetBy(dx: -1, dy: -1).intersects(frame), frame.width > 0 else { continue }
            XCTAssertGreaterThanOrEqual(frame.width + 0.5, label.intrinsicContentSize.width, "\(label.text ?? "") truncated: \(frame)")
            XCTAssertLessThanOrEqual(frame.maxY, capsule.maxY + 0.5, "\(label.text ?? "") below the capsule: \(frame)")
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

