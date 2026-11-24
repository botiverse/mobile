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

    func testAppRouteOpensInstalledAppDetail() {
        let payload = RaftIosNotificationOpen.sanitizedPayload(userInfo: [
            "type": "alert",
            "id": "app_permission_update:inst-1:abc",
            "raftCategory": "system_notice",
            "serverId": "s1",
            "groupKey": "s1:app_permission_update",
            "route": ["type": "app", "serverId": "s2", "appId": "app-42"],
            "aps": ["category": "RAFT_MESSAGE"],
        ])
        XCTAssertEqual(payload, [
            "slockNotificationServerId": "s2",
            "slockNotificationTarget": "app",
            "slockNotificationAppId": "app-42",
            "slockNotificationRaftCategory": "system_notice",
            "slockNotificationId": "app_permission_update:inst-1:abc",
            "slockNotificationGroupKey": "s1:app_permission_update",
        ])

        // Distinct installed apps on the same server do not collapse in dedupeKey.
        let other = RaftIosNotificationOpen.sanitizedPayload(userInfo: [
            "serverId": "s2",
            "route": ["type": "app", "serverId": "s2", "appId": "app-99"],
        ])!
        XCTAssertNotEqual(RaftIosNotificationOpen.dedupeKey(payload!), RaftIosNotificationOpen.dedupeKey(other))

        // An app route missing appId falls back cleanly to Activity on its server.
        let missingAppId = RaftIosNotificationOpen.sanitizedPayload(userInfo: [
            "serverId": "s2",
            "route": ["type": "app", "serverId": "s2"],
        ])
        XCTAssertEqual(missingAppId?["slockNotificationTarget"], "activity")
        XCTAssertEqual(missingAppId?["slockNotificationServerId"], "s2")
    }
}
