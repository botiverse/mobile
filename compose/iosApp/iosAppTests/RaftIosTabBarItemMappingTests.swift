import CoreGraphics
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

    func testAttentionDotIsAnEmptyBadgeAndANumberStillWins() {
        XCTAssertEqual(RaftIosTabBarItemMapping.badgeValue(fromShared: nil, attentionDot: true), "")
        XCTAssertEqual(RaftIosTabBarItemMapping.badgeValue(fromShared: "3", attentionDot: true), "3")
        XCTAssertNil(RaftIosTabBarItemMapping.badgeValue(fromShared: nil, attentionDot: false))
        let dotted = RaftIosTabBarItemSpec(
            key: "activity",
            title: "Activity",
            systemImageName: "waveform.path.ecg",
            selectedSystemImageName: "waveform.path.ecg",
            badgeValue: nil,
            enabled: true,
            attentionDot: true
        )
        XCTAssertEqual(dotted.badgeValue, "")
    }

    func testDisplayItemsKeepSharedOrderIncludingAFifthTab() {
        // Phone bar: four tabs plus the Search action as the fifth item.
        let keys = ["home", "activity", "tasks", "more", "search"]

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
}
