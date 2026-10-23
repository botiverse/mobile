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

    // MARK: - iOS 26: the server button beside a real system tab bar

    /// Test-only: the bar's capsule from its private view tree (production never walks it).
    private func capsuleFrame(in tabBar: UITabBar, to target: UIView) -> (CGRect, [String])? {
        var notes: [String] = []
        var best: CGRect?
        func walk(_ view: UIView) {
            for sub in view.subviews where !sub.isHidden {
                let frame = sub.convert(sub.bounds, to: target)
                if frame.height >= 40, frame.height <= 100, frame.width >= 150, notes.count < 20 {
                    notes.append("\(type(of: sub)) \(frame) r=\(sub.layer.cornerRadius)")
                    let capsule = sub.layer.cornerRadius >= frame.height * 0.4
                    if capsule, frame.width > (best?.width ?? 0) { best = frame }
                }
                walk(sub)
            }
        }
        walk(tabBar)
        return best.map { ($0, notes) } ?? (notes.isEmpty ? nil : (.null, notes))
    }

    /// Rows of [column] (points) whose pixels differ from the background in [image].
    private func span(_ image: UIImage, column x: CGFloat, from top: CGFloat, background: (Int, Int, Int)) -> (CGFloat, CGFloat)? {
        guard let cg = image.cgImage else { return nil }
        let scale = image.scale
        let width = cg.width, height = cg.height
        var data = [UInt8](repeating: 0, count: width * height * 4)
        let context = CGContext(
            data: &data, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        let px = min(width - 1, max(0, Int(x * scale)))
        var first: Int?, last: Int?
        for row in Int(top * scale)..<height {
            // CGContext memory row 0 is the top of the image.
            let o = (row * width + px) * 4
            let d = abs(Int(data[o]) - background.0) + abs(Int(data[o + 1]) - background.1) + abs(Int(data[o + 2]) - background.2)
            if d > 60 {
                if first == nil { first = row }
                last = row
            }
        }
        guard let f = first, let l = last else { return nil }
        return (CGFloat(f) / scale, CGFloat(l + 1) / scale)
    }

    func testServerButtonIsLevelWithTheSystemTabBarCapsule() throws {
        guard #available(iOS 26.0, *) else {
            throw XCTSkip("the separate server button is iOS 26 only")
        }
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow(frame: UIScreen.main.bounds)
        window.frame = scene?.screen.bounds ?? UIScreen.main.bounds
        window.overrideUserInterfaceStyle = .dark
        defer { window.isHidden = true }
        let root = UIViewController()
        let background = (255, 0, 255)
        root.view.backgroundColor = UIColor(red: 1, green: 0, blue: 1, alpha: 1)
        window.rootViewController = root
        window.isHidden = false
        let tabs = UITabBarController()
        tabs.traitOverrides.horizontalSizeClass = .compact
        tabs.viewControllers = ["house", "waveform.path.ecg", "checkmark.square", "magnifyingglass"].map { name in
            let child = UIViewController()
            child.view.backgroundColor = .clear
            child.tabBarItem = UITabBarItem(title: name.prefix(4).capitalized, image: UIImage(systemName: name), selectedImage: nil)
            return child
        }
        root.addChild(tabs)
        tabs.view.backgroundColor = .clear
        let bounds = root.view.bounds
        tabs.view.frame = CGRect(
            x: 0, y: 0,
            width: RaftIosServerButtonLayout.tabBarContainerWidth(containerWidth: bounds.width, showsButton: true),
            height: bounds.height
        )
        root.view.addSubview(tabs.view)
        tabs.didMove(toParent: root)
        let button = RaftIosServerTabButton.make()
        button.apply(serverSpec(), avatar: nil)
        root.view.addSubview(button)
        root.view.layoutIfNeeded()
        tabs.view.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.6))
        let tabBar = tabs.tabBar
        let barFrame = tabBar.convert(tabBar.bounds, to: root.view)
        button.frame = RaftIosServerButtonLayout.buttonFrame(
            containerWidth: bounds.width,
            safeAreaRight: root.view.safeAreaInsets.right,
            barFrame: barFrame,
            barBottomInset: tabBar.safeAreaInsets.bottom
        )
        button.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.6))

        let tree = capsuleFrame(in: tabBar, to: root.view)
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let top = max(0, barFrame.minY - 40)
        let capsuleColumn = (tree?.0).flatMap { $0.isNull ? nil : $0.minX + 30 } ?? 60
        let capsuleSpan = span(image, column: capsuleColumn, from: top, background: background)
        let buttonSpan = span(image, column: button.frame.midX, from: top, background: background)
        let geometry =
            "iOS \(UIDevice.current.systemVersion) screen=\(bounds.size) scale=\(image.scale) " +
            "bar=\(barFrame) barSafeBottom=\(tabBar.safeAreaInsets.bottom) viewSafe=\(root.view.safeAreaInsets) " +
            "button=\(button.frame) treeCapsule=\(String(describing: tree?.0)) " +
            "pixelCapsule=\(String(describing: capsuleSpan)) pixelButton=\(String(describing: buttonSpan))"
        print("RAFT_TABBAR_GEOM \(geometry)")
        tree?.1.forEach { print("RAFT_TABBAR_TREE \($0)") }
        // The bottom of the screen as a PNG, for a reviewer to measure (base64, 1x).
        let crop = CGRect(x: 0, y: top, width: bounds.width, height: bounds.height - top)
        let cropped = UIGraphicsImageRenderer(size: crop.size, format: {
            let f = UIGraphicsImageRendererFormat(); f.scale = 2; return f
        }()).image { _ in image.draw(at: CGPoint(x: 0, y: -top)) }
        if let png = cropped.pngData() {
            let b64 = png.base64EncodedString()
            stride(from: 0, to: b64.count, by: 2000).forEach { start in
                let a = b64.index(b64.startIndex, offsetBy: start)
                let b = b64.index(a, offsetBy: min(2000, b64.count - start))
                print("RAFT_TABBAR_PNG \(b64[a..<b])")
            }
        }
        // Level: the button's centre is the capsule's centre (within 1pt), and it is as tall.
        let capsule = try XCTUnwrap(tree.flatMap { $0.0.isNull ? nil : $0.0 }, "no capsule found: \(geometry)")
        XCTAssertEqual(button.frame.midY, capsule.midY, accuracy: 1, geometry)
        XCTAssertEqual(button.frame.height, capsule.height, accuracy: 1, geometry)
        XCTAssertGreaterThanOrEqual(button.frame.minX - capsule.maxX, RaftIosServerButtonLayout.spacing - 1, geometry)
    }
}

