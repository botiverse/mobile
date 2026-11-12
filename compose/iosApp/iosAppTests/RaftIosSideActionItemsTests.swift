import UIKit
import XCTest

final class RaftIosSideActionItemsTests: XCTestCase {
    override func setUpWithError() throws {
        // Vertical bar items (`axisBehavior`) are iOS 27.1 API.
        guard #available(iOS 27.1, *) else { throw XCTSkip("iOS 27.1 or later") }
    }

    private let column = """
    {"version":1,
     "primary":[{"id":"back","label":"Back","systemSymbol":"chevron.backward","enabled":true,"attention":false}],
     "actions":[{"id":"thread-search","label":"Search","systemSymbol":"magnifyingglass","enabled":true,"attention":false},
                {"id":"thread-settings","label":"Settings","systemSymbol":"gearshape","enabled":true,"attention":true},
                {"id":"thread-timeline","label":"Timeline","systemSymbol":"list.bullet","enabled":true,"attention":false},
                {"id":"thread-follow","label":"Follow","systemSymbol":"bell","enabled":false,"attention":false}],
     "visibleActionIds":["thread-search","thread-settings"],
     "overflowActionIds":["thread-timeline","thread-follow"],
     "overflowLabel":"More Actions",
     "overflowAttention":false}
    """

    @available(iOS 27.1, *)
    func testBackLeadsAndTheActionsFollowInListedOrderWithTheOverflowLast() throws {
        let items = try XCTUnwrap(RaftIosSideActionItems.make(json: column, onTap: { _ in }))

        XCTAssertEqual(items.leading.map(\.primaryAction?.identifier.rawValue), ["back"])
        XCTAssertEqual(items.leading.map(\.accessibilityLabel), ["Back"])
        XCTAssertEqual(items.trailing.count, 3)
        XCTAssertEqual(items.trailing.prefix(2).map(\.primaryAction?.identifier.rawValue), ["thread-search", "thread-settings"])
        XCTAssertNotNil(items.trailing[1].badge, "an action that wants attention wears the indicator")
        XCTAssertNil(items.trailing[0].badge)

        let more = items.trailing[2]
        XCTAssertNil(more.primaryAction)
        XCTAssertEqual(more.accessibilityLabel, "More Actions")
        XCTAssertNil(more.badge)
        let rows = try XCTUnwrap(more.menu?.children as? [UIAction])
        XCTAssertEqual(rows.map(\.identifier.rawValue), ["thread-timeline", "thread-follow"])
        XCTAssertEqual(rows.map(\.title), ["Timeline", "Follow"])
        XCTAssertFalse(rows[0].attributes.contains(.disabled))
        XCTAssertTrue(rows[1].attributes.contains(.disabled))

        for item in items.leading + items.trailing {
            XCTAssertEqual(item.axisBehavior, .verticalPreferred)
            XCTAssertNotNil(item.image)
        }
    }

    @available(iOS 27.1, *)
    func testNoOverflowNoEllipsisAndADisabledButtonStaysDisabled() throws {
        let json = """
        {"primary":[{"id":"back","label":"Back","systemSymbol":"chevron.backward","enabled":false}],
         "actions":[{"id":"home-bell","label":"Notifications","systemSymbol":"bell","enabled":true}],
         "visibleActionIds":["home-bell"],"overflowActionIds":[]}
        """
        let items = try XCTUnwrap(RaftIosSideActionItems.make(json: json, onTap: { _ in }))

        XCTAssertFalse(items.leading[0].isEnabled)
        XCTAssertEqual(items.trailing.map(\.primaryAction?.identifier.rawValue), ["home-bell"])
    }

    @available(iOS 27.1, *)
    func testAColumnThatIsNotJsonMakesNoItems() {
        XCTAssertNil(RaftIosSideActionItems.make(json: "not json", onTap: { _ in }))
    }
}
