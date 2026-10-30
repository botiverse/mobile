import XCTest

final class RaftIosNotificationRetractionTests: XCTestCase {
    // MARK: Parser

    func testSyncPushCarriesBadge() {
        let push = RaftIosNotificationRetraction.parseBackgroundPush([
            "aps": ["content-available": 1],
            "type": "sync",
            "badge": NSNumber(value: 7),
        ])
        XCTAssertEqual(push, .sync(badge: 7))
    }

    func testSyncPushWithoutBadge() {
        XCTAssertEqual(RaftIosNotificationRetraction.parseBackgroundPush(["type": "sync"]), .sync(badge: nil))
    }

    func testRetractPushWithNumericThroughSeq() {
        let push = RaftIosNotificationRetraction.parseBackgroundPush([
            "aps": ["content-available": 1],
            "type": "retract",
            "badge": 3,
            "serverId": "s1",
            "groupKey": "s1:c1",
            "conversationId": "c1",
            "throughSeq": NSNumber(value: 42),
        ])
        XCTAssertEqual(
            push,
            .retract(RaftIosNotificationRetract(serverId: "s1", groupKey: "s1:c1", conversationId: "c1", throughSeq: 42, badge: 3))
        )
    }

    func testRetractPushWithNullOrMissingThroughSeqCoversWholeGroup() {
        let withNull = RaftIosNotificationRetraction.parseBackgroundPush([
            "type": "retract", "badge": 0, "serverId": "s1", "groupKey": "s1:c1", "conversationId": "c1",
            "throughSeq": NSNull(),
        ])
        let missing = RaftIosNotificationRetraction.parseBackgroundPush([
            "type": "retract", "badge": 0, "serverId": "s1", "groupKey": "s1:c1", "conversationId": "c1",
        ])
        let expected = RaftIosBackgroundPush.retract(
            RaftIosNotificationRetract(serverId: "s1", groupKey: "s1:c1", conversationId: "c1", throughSeq: nil, badge: 0)
        )
        XCTAssertEqual(withNull, expected)
        XCTAssertEqual(missing, expected)
    }

    func testRetractToleratesStringNumbers() {
        let push = RaftIosNotificationRetraction.parseBackgroundPush([
            "type": "retract", "badge": "5", "serverId": "s1", "conversationId": "c1", "throughSeq": " 12 ",
        ])
        // groupKey rebuilt from serverId + conversationId.
        XCTAssertEqual(
            push,
            .retract(RaftIosNotificationRetract(serverId: "s1", groupKey: "s1:c1", conversationId: "c1", throughSeq: 12, badge: 5))
        )
    }

    func testMalformedPayloadsAreRejected() {
        XCTAssertNil(RaftIosNotificationRetraction.parseBackgroundPush([:]))
        XCTAssertNil(RaftIosNotificationRetraction.parseBackgroundPush(["type": "alert", "badge": 1]))
        XCTAssertNil(RaftIosNotificationRetraction.parseBackgroundPush(["type": "unknown"]))
        // No group at all.
        XCTAssertNil(RaftIosNotificationRetraction.parseBackgroundPush(["type": "retract", "throughSeq": 3]))
        // throughSeq present but not an integer.
        XCTAssertNil(RaftIosNotificationRetraction.parseBackgroundPush([
            "type": "retract", "groupKey": "s1:c1", "throughSeq": "abc",
        ]))
        XCTAssertNil(RaftIosNotificationRetraction.parseBackgroundPush([
            "type": "retract", "groupKey": "s1:c1", "throughSeq": true,
        ]))
        XCTAssertNil(RaftIosNotificationRetraction.parseSocketRetract(rawPayload: "not json"))
    }

    func testSocketRetractPayload() {
        let retract = RaftIosNotificationRetraction.parseSocketRetract(
            rawPayload: #"{"serverId":"s1","groupKey":"s1:c1","conversationId":"c1","throughSeq":9}"#
        )
        XCTAssertEqual(
            retract,
            RaftIosNotificationRetract(serverId: "s1", groupKey: "s1:c1", conversationId: "c1", throughSeq: 9, badge: nil)
        )
        let all = RaftIosNotificationRetraction.parseSocketRetract(
            rawPayload: #"{"serverId":"s1","groupKey":"s1:c1","conversationId":"c1","throughSeq":null}"#
        )
        XCTAssertEqual(all?.throughSeq, nil)
        XCTAssertEqual(all?.groupKey, "s1:c1")
    }

    // MARK: Matcher

    private func retract(_ groupKey: String, through: Int64?) -> RaftIosNotificationRetract {
        RaftIosNotificationRetract(serverId: "s1", groupKey: groupKey, conversationId: "c1", throughSeq: through, badge: nil)
    }

    func testMatcherRemovesOlderOrEqualSeqInGroupOnly() {
        let delivered: [(identifier: String, userInfo: [AnyHashable: Any])] = [
            ("older", ["groupKey": "s1:c1", "seq": NSNumber(value: 4)]),
            ("equal", ["groupKey": "s1:c1", "seq": 5]),
            ("newer", ["groupKey": "s1:c1", "seq": 6]),
            ("other-group", ["groupKey": "s1:c2", "seq": 1]),
            ("no-seq", ["groupKey": "s1:c1"]),
            ("legacy-no-group", ["serverId": "s1", "channelId": "c1", "seq": 1]),
        ]
        let removed = RaftIosNotificationRetraction.identifiersToRemove(delivered: delivered, retract: retract("s1:c1", through: 5))
        XCTAssertEqual(removed, ["older", "equal", "no-seq"])
    }

    func testMatcherNullThroughSeqRemovesWholeGroup() {
        let delivered: [(identifier: String, userInfo: [AnyHashable: Any])] = [
            ("a", ["groupKey": "s1:c1", "seq": 100]),
            ("b", ["groupKey": "s1:c1", "seq": "200"]),
            ("c", ["groupKey": "s2:c1", "seq": 1]),
        ]
        let removed = RaftIosNotificationRetraction.identifiersToRemove(delivered: delivered, retract: retract("s1:c1", through: nil))
        XCTAssertEqual(removed, ["a", "b"])
    }

    func testMatcherRebuildsGroupKeyFromServerAndConversation() {
        let delivered: [(identifier: String, userInfo: [AnyHashable: Any])] = [
            ("rebuilt", ["serverId": "s1", "conversationId": "c1", "seq": 2]),
            ("rebuilt-newer", ["serverId": "s1", "conversationId": "c1", "seq": 9]),
            ("other-server", ["serverId": "s2", "conversationId": "c1", "seq": 2]),
        ]
        let removed = RaftIosNotificationRetraction.identifiersToRemove(delivered: delivered, retract: retract("s1:c1", through: 3))
        XCTAssertEqual(removed, ["rebuilt"])
    }

    // MARK: Presence

    func testPresenceActivityPayloads() {
        XCTAssertEqual(RaftIosPresenceActivity.eventName, "presence:activity")
        XCTAssertEqual(RaftIosPresenceActivity.heartbeatInterval, 60)

        let active = RaftIosPresenceActivity.payload(active: true)
        XCTAssertEqual(active["active"] as? Bool, true)
        XCTAssertEqual(active["idleSeconds"] as? Int, 0)

        let inactive = RaftIosPresenceActivity.payload(active: false)
        XCTAssertEqual(inactive["active"] as? Bool, false)
        XCTAssertTrue(inactive["idleSeconds"] is NSNull)
        XCTAssertTrue(inactive.keys.contains("idleSeconds"), "idleSeconds is sent as an explicit null")

        let json = try? JSONSerialization.data(withJSONObject: inactive, options: [.sortedKeys])
        XCTAssertEqual(json.flatMap { String(data: $0, encoding: .utf8) }, #"{"active":false,"idleSeconds":null}"#)
    }
}
