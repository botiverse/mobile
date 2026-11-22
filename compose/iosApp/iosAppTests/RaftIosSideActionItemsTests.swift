import UIKit
import XCTest
import shared

final class RaftIosSideActionItemsTests: XCTestCase {
    override func setUpWithError() throws {
        // RaftIosSideActionItems is iOS 27.1 only (the host draws columns there).
        guard #available(iOS 27.1, *) else { throw XCTSkip("iOS 27.1 or later") }
    }

    private let column = """
    {"version":1,
     "primary":[{"id":"back","label":"Back","systemSymbol":"chevron.backward","enabled":true,"attention":false}],
     "actions":[{"id":"thread-search","label":"Search","systemSymbol":"magnifyingglass","enabled":true,"attention":false},
                {"id":"thread-settings","label":"Settings","systemSymbol":"gearshape","enabled":true,"attention":true,"attentionDescription":"2 new"},
                {"id":"thread-timeline","label":"Timeline","systemSymbol":"list.bullet","enabled":true,"attention":false},
                {"id":"thread-follow","label":"Follow","systemSymbol":"bell","enabled":false,"attention":true,"attentionDescription":"New replies"},
                {"id":"thread-delete","label":"Delete","systemSymbol":"trash","enabled":true,"attention":false}],
     "visibleActionIds":["thread-search","thread-settings"],
     "overflowActionIds":["thread-timeline","thread-follow","thread-delete"],
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
        XCTAssertEqual(items.trailing[1].accessibilityValue, "2 new", "VoiceOver reads why")
        XCTAssertNil(items.trailing[0].badge)
        XCTAssertNil(items.trailing[0].accessibilityValue)

        let more = items.trailing[2]
        XCTAssertNil(more.primaryAction)
        XCTAssertEqual(more.accessibilityLabel, "More Actions")
        XCTAssertNil(more.badge, "overflowAttention is false in this column")
        let rows = try XCTUnwrap(more.menu?.children as? [UIAction])
        XCTAssertEqual(rows.map(\.identifier.rawValue), ["thread-timeline", "thread-follow", "thread-delete"])
        XCTAssertEqual(rows.map(\.title), ["Timeline", "Follow", "Delete"])
        XCTAssertFalse(rows[0].attributes.contains(.disabled))
        XCTAssertTrue(rows[1].attributes.contains(.disabled))
        XCTAssertEqual(rows.map(\.subtitle), [nil, "New replies", nil], "a row that wants attention says so")
        XCTAssertEqual(rows.map { $0.attributes.contains(.destructive) }, [false, false, true])

        for item in items.leading + items.trailing {
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

    /// A symbol the system lacks makes no items, so the page keeps drawing its column (Back too).
    @available(iOS 27.1, *)
    func testAnUnknownSymbolMakesNoItems() {
        let json = """
        {"primary":[{"id":"back","label":"Back","systemSymbol":"chevron.backward","enabled":true}],
         "actions":[{"id":"x","label":"X","systemSymbol":"no.such.symbol.raft","enabled":true}],
         "visibleActionIds":[],"overflowActionIds":["x"]}
        """
        XCTAssertNil(RaftIosSideActionItems.make(json: json, onTap: { _ in }))
    }
}
