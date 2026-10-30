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

    // MARK: Watermarks (late alert after its retract)

    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    private func marks(_ entries: [(String, Int64?)], at date: Date) -> [String: RaftIosRetractWatermark] {
        entries.reduce(into: [:]) { result, entry in
            result = RaftIosRetractWatermarks.recording(groupKey: entry.0, throughSeq: entry.1, into: result, now: date)
        }
    }

    func testWatermarkCoversOlderOrEqualSeqOnlyInItsGroup() {
        let watermarks = marks([("s1:c1", 5)], at: t0)
        let now = t0.addingTimeInterval(600)
        XCTAssertTrue(RaftIosRetractWatermarks.isCovered(groupKey: "s1:c1", seq: 4, watermarks: watermarks, now: now))
        XCTAssertTrue(RaftIosRetractWatermarks.isCovered(groupKey: "s1:c1", seq: 5, watermarks: watermarks, now: now))
        XCTAssertFalse(RaftIosRetractWatermarks.isCovered(groupKey: "s1:c1", seq: 6, watermarks: watermarks, now: now))
        XCTAssertFalse(RaftIosRetractWatermarks.isCovered(groupKey: "s1:c2", seq: 1, watermarks: watermarks, now: now))
        XCTAssertFalse(RaftIosRetractWatermarks.isCovered(groupKey: nil, seq: 1, watermarks: watermarks, now: now))
    }

    func testWatermarkKeepsLargestThroughSeq() {
        let watermarks = marks([("s1:c1", 9), ("s1:c1", 3)], at: t0)
        XCTAssertEqual(watermarks["s1:c1"]?.throughSeq, 9)
        XCTAssertTrue(RaftIosRetractWatermarks.isCovered(groupKey: "s1:c1", seq: 8, watermarks: watermarks, now: t0))
    }

    func testWatermarkExpiresAfterThreeDays() {
        let watermarks = marks([("s1:c1", 5), ("s1:c2", nil)], at: t0)
        let justBefore = t0.addingTimeInterval(3 * 24 * 60 * 60 - 1)
        let after = t0.addingTimeInterval(3 * 24 * 60 * 60 + 1)
        XCTAssertTrue(RaftIosRetractWatermarks.isCovered(groupKey: "s1:c1", seq: 5, watermarks: watermarks, now: justBefore))
        XCTAssertFalse(RaftIosRetractWatermarks.isCovered(groupKey: "s1:c1", seq: 5, watermarks: watermarks, now: after))
        XCTAssertFalse(RaftIosRetractWatermarks.isCovered(groupKey: "s1:c2", seq: nil, postedAt: t0, watermarks: watermarks, now: after))
        XCTAssertTrue(RaftIosRetractWatermarks.pruned(watermarks, now: after).isEmpty)
    }

    func testAllWatermarkCoversWhatWasPostedUpToTheRetractPlusGrace() {
        let watermarks = marks([("s1:reminder", nil)], at: t0)
        let now = t0.addingTimeInterval(30)
        // A late alert, with or without seq, posted right after the retract: covered.
        XCTAssertTrue(RaftIosRetractWatermarks.isCovered(
            groupKey: "s1:reminder", seq: nil, postedAt: t0.addingTimeInterval(20), watermarks: watermarks, now: now
        ))
        XCTAssertTrue(RaftIosRetractWatermarks.isCovered(
            groupKey: "s1:reminder", seq: 99, postedAt: t0.addingTimeInterval(-5), watermarks: watermarks, now: now
        ))
        // A genuinely new alert later on: not covered. Unknown post date: not covered.
        XCTAssertFalse(RaftIosRetractWatermarks.isCovered(
            groupKey: "s1:reminder", seq: nil, postedAt: t0.addingTimeInterval(3600), watermarks: watermarks, now: t0.addingTimeInterval(3600)
        ))
        XCTAssertFalse(RaftIosRetractWatermarks.isCovered(groupKey: "s1:reminder", seq: 1, watermarks: watermarks, now: now))
    }

    func testNumericWatermarkCoversSeqlessNotificationOnlyByDate() {
        let watermarks = marks([("s1:c1", 5)], at: t0)
        let now = t0.addingTimeInterval(3600)
        XCTAssertTrue(RaftIosRetractWatermarks.isCovered(
            groupKey: "s1:c1", seq: nil, postedAt: t0.addingTimeInterval(-60), watermarks: watermarks, now: now
        ))
        XCTAssertFalse(RaftIosRetractWatermarks.isCovered(
            groupKey: "s1:c1", seq: nil, postedAt: t0.addingTimeInterval(600), watermarks: watermarks, now: now
        ))
        XCTAssertFalse(RaftIosRetractWatermarks.isCovered(groupKey: "s1:c1", seq: nil, watermarks: watermarks, now: now))
    }

    func testWatermarkStoreEvictsOldestBeyondBound() {
        var watermarks: [String: RaftIosRetractWatermark] = [:]
        let total = RaftIosRetractWatermarks.maxEntries + 5
        for index in 0..<total {
            watermarks = RaftIosRetractWatermarks.recording(
                groupKey: "s1:c\(index)", throughSeq: 1, into: watermarks, now: t0.addingTimeInterval(Double(index))
            )
        }
        XCTAssertEqual(watermarks.count, RaftIosRetractWatermarks.maxEntries)
        XCTAssertNil(watermarks["s1:c0"])
        XCTAssertNil(watermarks["s1:c4"])
        XCTAssertNotNil(watermarks["s1:c5"])
        XCTAssertNotNil(watermarks["s1:c\(total - 1)"])
    }

    func testWatermarkPersistenceRoundTrip() {
        let suite = "RaftIosNotificationRetractionTests.\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suite) else { return XCTFail("no defaults") }
        defer { defaults.removePersistentDomain(forName: suite) }
        RaftIosRetractWatermarks.record(retract("s1:c1", through: 7), defaults: defaults, now: t0)
        RaftIosRetractWatermarks.record(retract("s1:c2", through: nil), defaults: defaults, now: t0)
        let loaded = RaftIosRetractWatermarks.load(defaults: defaults, now: t0.addingTimeInterval(10))
        XCTAssertEqual(loaded["s1:c1"], RaftIosRetractWatermark(throughSeq: 7, recordedAt: t0))
        XCTAssertEqual(loaded["s1:c2"], RaftIosRetractWatermark(throughSeq: nil, recordedAt: t0))
        XCTAssertTrue(RaftIosRetractWatermarks.load(defaults: defaults, now: t0.addingTimeInterval(4 * 24 * 60 * 60)).isEmpty)
    }

    func testCoveredDeliveredIdentifiers() {
        let watermarks = marks([("s1:c1", 5)], at: t0)
        let delivered: [(identifier: String, userInfo: [AnyHashable: Any], date: Date)] = [
            ("late-old", ["groupKey": "s1:c1", "seq": 3], t0.addingTimeInterval(5)),
            ("new", ["groupKey": "s1:c1", "seq": 6], t0.addingTimeInterval(5)),
            ("rebuilt", ["serverId": "s1", "conversationId": "c1", "seq": "5"], t0),
            ("other", ["groupKey": "s2:c1", "seq": 1], t0),
        ]
        XCTAssertEqual(
            RaftIosRetractWatermarks.coveredIdentifiers(delivered: delivered, watermarks: watermarks, now: t0.addingTimeInterval(10)),
            ["late-old", "rebuilt"]
        )
    }

    // MARK: Open payload

    func testMessagelessReminderWithNullRouteOpensActivity() {
        let payload = RaftIosNotificationOpen.sanitizedPayload(userInfo: [
            "type": "alert", "id": "reminder:r1:1700", "raftCategory": "reminder", "serverId": "s1",
            "groupKey": "s1:reminder", "route": NSNull(), "routeUrl": "/s/acme/activity",
            "messageId": NSNull(), "channelId": NSNull(), "kind": NSNull(), "seq": NSNull(),
            "aps": ["category": "RAFT_MESSAGE"],
        ])
        XCTAssertEqual(payload, [
            "slockNotificationServerId": "s1",
            "slockNotificationTarget": "activity",
            "slockNotificationRaftCategory": "reminder",
            "slockNotificationId": "reminder:r1:1700",
            "slockNotificationGroupKey": "s1:reminder",
        ])
    }

    func testAgentRouteOpensActivityWithShell() {
        let payload = RaftIosNotificationOpen.sanitizedPayload(
            userInfo: [
                "id": "notice:n1", "raftCategory": "agent_attention",
                "route": ["type": "agent", "serverId": "s2", "agentId": "a1"],
            ],
            shellInstanceId: "shell-1"
        )
        XCTAssertEqual(payload?["slockNotificationServerId"], "s2")
        XCTAssertEqual(payload?["slockNotificationTarget"], "activity")
        XCTAssertEqual(payload?["slockNotificationRaftCategory"], "agent_attention")
        XCTAssertEqual(payload?["slockShellInstanceId"], "shell-1")
        XCTAssertNil(payload?["slockNotificationMessageId"])
    }

    func testDmRouteStillOpensConversation() {
        let payload = RaftIosNotificationOpen.sanitizedPayload(
            userInfo: [
                "id": "message:m1", "raftCategory": "dm", "groupKey": "s1:d1",
                "route": ["type": "dm", "serverId": "s1", "channelId": "d1", "messageId": "m1"],
            ],
            categoryIdentifier: "RAFT_MESSAGE"
        )
        XCTAssertEqual(payload, [
            "slockNotificationServerId": "s1",
            "slockNotificationThreadId": "d1",
            "slockNotificationChannelId": "d1",
            "slockNotificationMessageId": "m1",
            "slockNotificationKind": "dm",
            "slockNotificationRaftCategory": "dm",
            "slockNotificationCategory": "RAFT_MESSAGE",
        ])
    }

    func testOpenPayloadRejectsBackgroundPushesAndServerless() {
        XCTAssertNil(RaftIosNotificationOpen.sanitizedPayload(userInfo: ["type": "retract", "serverId": "s1", "groupKey": "s1:c1"]))
        XCTAssertNil(RaftIosNotificationOpen.sanitizedPayload(userInfo: ["type": "sync", "serverId": "s1"]))
        XCTAssertNil(RaftIosNotificationOpen.sanitizedPayload(userInfo: ["raftCategory": "reminder", "route": NSNull()]))
    }

    func testActivityOpensDoNotCollapseInDedupeKey() {
        let first = RaftIosNotificationOpen.sanitizedPayload(userInfo: ["serverId": "s1", "id": "reminder:r1:1"])!
        let second = RaftIosNotificationOpen.sanitizedPayload(userInfo: ["serverId": "s1", "id": "reminder:r2:2"])!
        XCTAssertNotEqual(RaftIosNotificationOpen.dedupeKey(first), RaftIosNotificationOpen.dedupeKey(second))
        let message = RaftIosNotificationOpen.sanitizedPayload(userInfo: [
            "serverId": "s1", "channelId": "c1", "messageId": "m1", "kind": "channel",
        ])!
        XCTAssertEqual(RaftIosNotificationOpen.dedupeKey(message), "s1|c1|m1")
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
