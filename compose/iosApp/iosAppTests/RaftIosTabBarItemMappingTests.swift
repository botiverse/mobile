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

    func testPanelTabLeavesTheBarOnlyWhenTheButtonIsSeparate() {
        let items = ["home", "activity", "tasks", "search"].map { spec($0) } + [serverSpec()]
        let separate = RaftIosTabBarItemMapping.splitPanelButton(items, separatePanelButton: true)
        XCTAssertEqual(separate.tabs.map(\.key), ["home", "activity", "tasks", "search"])
        XCTAssertEqual(separate.panel?.key, "more")
        // Before iOS 26 it stays the last tab.
        let classic = RaftIosTabBarItemMapping.splitPanelButton(items, separatePanelButton: false)
        XCTAssertEqual(classic.tabs.map(\.key), ["home", "activity", "tasks", "search", "more"])
        XCTAssertNil(classic.panel)
        // A plain More tab (no server loaded) is a page tab: it stays in the bar.
        let plain = RaftIosTabBarItemMapping.splitPanelButton(items.dropLast() + [spec("more")], separatePanelButton: true)
        XCTAssertEqual(plain.tabs.count, 5)
        XCTAssertNil(plain.panel)
    }

    func testBarContainerGivesUpTheButtonsWidthSoTheyNeverOverlap() {
        for width: CGFloat in [320, 375, 402, 440, 744] {
            let container = RaftIosServerButtonLayout.tabBarContainerWidth(containerWidth: width, showsButton: true)
            let button = RaftIosServerButtonLayout.buttonFrame(
                containerWidth: width,
                safeAreaRight: 0,
                barFrame: CGRect(x: 0, y: 700, width: container, height: 96),
                barBottomInset: 34
            )
            // The pill lives inside the container; the button starts after it, plus the gap.
            XCTAssertGreaterThanOrEqual(button.minX, container - RaftIosServerButtonLayout.trailingMargin, "width \(width)")
            XCTAssertLessThanOrEqual(button.maxX, width - RaftIosServerButtonLayout.trailingMargin, "width \(width)")
            XCTAssertEqual(button.width, button.height)
            // Level with the pill: centred in the bar above its bottom safe area.
            XCTAssertEqual(button.midY, 700 + (96 - 34) / 2, accuracy: 0.5)
        }
        XCTAssertEqual(RaftIosServerButtonLayout.tabBarContainerWidth(containerWidth: 402, showsButton: false), 402)
        // Landscape: the right safe area moves the button in.
        let landscape = RaftIosServerButtonLayout.buttonFrame(
            containerWidth: 874, safeAreaRight: 62, barFrame: CGRect(x: 0, y: 340, width: 804, height: 62), barBottomInset: 0
        )
        XCTAssertEqual(landscape.maxX, 874 - 62 - RaftIosServerButtonLayout.trailingMargin, accuracy: 0.5)
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

    func testServerButtonIsAnAccessibleGlassButtonWithoutABadge() throws {
        guard #available(iOS 26.0, *) else {
            throw XCTSkip("the separate server button is iOS 26 only")
        }
        let button = RaftIosServerTabButton.make()
        button.apply(serverSpec(), avatar: nil)
        XCTAssertNotNil(button.configuration, "system glass configuration")
        XCTAssertTrue(button.isAccessibilityElement)
        XCTAssertTrue(button.accessibilityTraits.contains(.button))
        XCTAssertEqual(button.accessibilityLabel, "Current server: Botiverse")
        XCTAssertEqual(button.accessibilityIdentifier, "raft-tab-more")
        XCTAssertNil(button.accessibilityValue, "no badge on the server button")
        button.frame = CGRect(x: 0, y: 0, width: RaftIosServerButtonLayout.diameter, height: RaftIosServerButtonLayout.diameter)
        button.layoutIfNeeded()
        let avatar = try XCTUnwrap(button.subviews.compactMap { $0 as? UIImageView }.first { $0.image?.size.width == RaftIosServerButtonLayout.avatarDiameter })
        XCTAssertEqual(avatar.frame.midX, button.bounds.midX, accuracy: 0.5)
        XCTAssertEqual(avatar.frame.midY, button.bounds.midY, accuracy: 0.5)
        XCTAssertFalse(avatar.isUserInteractionEnabled)
        // One tap = one action: a plain button, not a tab.
        var taps = 0
        button.addAction(UIAction { _ in taps += 1 }, for: .primaryActionTriggered)
        button.sendActions(for: .primaryActionTriggered)
        XCTAssertEqual(taps, 1)
    }
}

