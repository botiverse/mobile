import UserNotifications
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

    // MARK: Badge accuracy telemetry helpers (#proj-mobile:#437)

    func testApsBadgeAndSeqExtraction() {
        XCTAssertEqual(RaftIosNotificationRetraction.apsBadge(["aps": ["badge": NSNumber(value: 5)]]), 5)
        XCTAssertEqual(RaftIosNotificationRetraction.apsBadge(["aps": ["badge": -2]]), 0)
        XCTAssertNil(RaftIosNotificationRetraction.apsBadge(["aps": ["badge": true]]))
        XCTAssertNil(RaftIosNotificationRetraction.apsBadge([:]))
        XCTAssertEqual(RaftIosNotificationRetraction.seq(["seq": "42"]), 42)
        XCTAssertEqual(RaftIosNotificationRetraction.seq(["seq": NSNumber(value: 7)]), 7)
        XCTAssertNil(RaftIosNotificationRetraction.seq(["seq": "not-a-number"]))
    }

    func testIsReadBeforeOpenCoversNumericWholeGroupAndColdLaunchWithoutPostedAt() {
        let watermarks = marks([("s1:c1", 10), ("s1:reminder", nil)], at: t0)
        let now = t0.addingTimeInterval(30)

        // Numeric throughSeq matches seq <= 10 only.
        XCTAssertTrue(
            RaftIosRetractWatermarks.isReadBeforeOpen(
                userInfo: ["groupKey": "s1:c1", "seq": 10],
                postedAt: t0.addingTimeInterval(-10),
                watermarks: watermarks,
                now: now
            )
        )
        XCTAssertFalse(
            RaftIosRetractWatermarks.isReadBeforeOpen(
                userInfo: ["groupKey": "s1:c1", "seq": 11],
                postedAt: t0.addingTimeInterval(5),
                watermarks: watermarks,
                now: now
            )
        )
        // Whole-group retract without postedAt (cold launch) still flags read_before_open = true.
        XCTAssertTrue(
            RaftIosRetractWatermarks.isReadBeforeOpen(
                userInfo: ["groupKey": "s1:reminder"],
                postedAt: nil,
                watermarks: watermarks,
                now: now
            )
        )
        // Newer push posted past the 60s grace window is not flagged.
        XCTAssertFalse(
            RaftIosRetractWatermarks.isReadBeforeOpen(
                userInfo: ["groupKey": "s1:reminder"],
                postedAt: t0.addingTimeInterval(120),
                watermarks: watermarks,
                now: t0.addingTimeInterval(130)
            )
        )
    }

    func testLandingTargetCoversConversationThreadTaskAndActivity() {
        let threadLanding = RaftIosNotificationOpen.landingTarget(userInfo: [
            "serverId": "s1",
            "conversationId": "th-stream-1",
            "route": [
                "type": "thread",
                "serverId": "s1",
                "parentChannelId": "ch-1",
                "parentMessageId": "pm-1",
                "messageId": "m-9",
            ],
        ])
        XCTAssertEqual(threadLanding.conversationId, "th-stream-1")
        XCTAssertEqual(threadLanding.parentMessageId, "pm-1")
        XCTAssertFalse(threadLanding.isActivityTarget)

        let taskLanding = RaftIosNotificationOpen.landingTarget(userInfo: [
            "serverId": "s1",
            "conversationId": "task-ch-7",
            "route": [
                "type": "task",
                "serverId": "s1",
                "parentChannelId": "ch-1",
                "parentChannelType": "channel",
                "taskId": "task-7",
                "messageId": "m-3",
            ],
        ])
        XCTAssertEqual(taskLanding.taskConversationChannelId, "task-ch-7")
        XCTAssertFalse(taskLanding.isActivityTarget)

        let reminderLanding = RaftIosNotificationOpen.landingTarget(userInfo: [
            "type": "alert",
            "serverId": "s1",
            "raftCategory": "reminder",
            "groupKey": "s1:reminder",
        ])
        XCTAssertTrue(reminderLanding.isActivityTarget)
    }

    // MARK: Foreground badge & alert presentation policy

    func testForegroundWillPresentNeverAppliesBadgeForVisibleOrActiveAlerts() {
        let retracted = RaftIosForegroundAlertPresentation.decide(
            isCoveredByWatermark: true,
            targetsVisibleConversation: true,
            isForegroundActive: true,
            raftCategory: "channel"
        )
        XCTAssertEqual(retracted.options, [])
        XCTAssertFalse(retracted.appliesAlertBadge)
        XCTAssertEqual(retracted.suppressReason, "retracted")

        let visible = RaftIosForegroundAlertPresentation.decide(
            isCoveredByWatermark: false,
            targetsVisibleConversation: true,
            isForegroundActive: true,
            raftCategory: "dm"
        )
        XCTAssertEqual(visible.options, [])
        XCTAssertFalse(visible.appliesAlertBadge)
        XCTAssertEqual(visible.suppressReason, "visible_conversation")

        let activeMessage = RaftIosForegroundAlertPresentation.decide(
            isCoveredByWatermark: false,
            targetsVisibleConversation: false,
            isForegroundActive: true,
            raftCategory: "mention"
        )
        XCTAssertEqual(activeMessage.options, [.list])
        XCTAssertFalse(activeMessage.appliesAlertBadge)
        XCTAssertEqual(activeMessage.suppressReason, "foreground_suppressed")

        for category in ["reminder", "system_notice", "test"] {
            let activeMessageLess = RaftIosForegroundAlertPresentation.decide(
                isCoveredByWatermark: false,
                targetsVisibleConversation: false,
                isForegroundActive: true,
                raftCategory: category
            )
            XCTAssertEqual(activeMessageLess.options, [.banner, .list, .sound])
            XCTAssertFalse(activeMessageLess.appliesAlertBadge)
            XCTAssertNil(activeMessageLess.suppressReason)
        }

        let inactive = RaftIosForegroundAlertPresentation.decide(
            isCoveredByWatermark: false,
            targetsVisibleConversation: false,
            isForegroundActive: false,
            raftCategory: "dm"
        )
        XCTAssertEqual(inactive.options, [.banner, .list, .sound, .badge])
        XCTAssertTrue(inactive.appliesAlertBadge)
        XCTAssertNil(inactive.suppressReason)
    }

    func testBackgroundPushBadgeSkippedWhileForegroundActive() {
        XCTAssertFalse(RaftIosNotificationRetraction.shouldApplyBackgroundPushBadge(badge: 5, isForegroundActive: true))
        XCTAssertFalse(RaftIosNotificationRetraction.shouldApplyBackgroundPushBadge(badge: 0, isForegroundActive: true))
        XCTAssertFalse(RaftIosNotificationRetraction.shouldApplyBackgroundPushBadge(badge: nil, isForegroundActive: false))
        XCTAssertTrue(RaftIosNotificationRetraction.shouldApplyBackgroundPushBadge(badge: 5, isForegroundActive: false))
        XCTAssertTrue(RaftIosNotificationRetraction.shouldApplyBackgroundPushBadge(badge: 0, isForegroundActive: false))
    }

    func testForegroundBadgeReconcilerRepairsInactiveDriftWithoutReapplyingPreBackgroundCount() {
        var reconciler = RaftIosForegroundBadgeReconciler()
        // Cold start before Kotlin publishes an authoritative count: never clobber.
        XCTAssertNil(reconciler.badgeToRestoreOnDidBecomeActive(currentIconBadge: 4))

        // Kotlin publishes authoritative foreground badge = 2.
        reconciler.recordAppBadge(2, isBackground: false)
        XCTAssertNil(reconciler.badgeToRestoreOnDidBecomeActive(currentIconBadge: 2))
        // Drift during transient .inactive (e.g. Notification Center pull-down): repair back to 2.
        XCTAssertEqual(reconciler.badgeToRestoreOnDidBecomeActive(currentIconBadge: 7), 2)

        // Entering background invalidates pre-background count; writes while backgrounded are ignored.
        reconciler.didEnterBackground()
        reconciler.recordAppBadge(1, isBackground: true)
        XCTAssertNil(reconciler.badgeToRestoreOnDidBecomeActive(currentIconBadge: 4))

        // Sign-out clears authoritative count.
        reconciler.recordAppBadge(3, isBackground: false)
        reconciler.didSignOut()
        XCTAssertNil(reconciler.badgeToRestoreOnDidBecomeActive(currentIconBadge: 0))
    }

    // MARK: Local read & tap sibling retraction + sign-out cleanup

    func testLocalReadRetractBuildsWholeGroupRetractAndCoversLateAlerts() {
        XCTAssertNil(RaftIosNotificationRetraction.localReadRetract(serverId: " ", conversationId: "c1"))
        XCTAssertNil(RaftIosNotificationRetraction.localReadRetract(serverId: "s1", conversationId: ""))

        let retract = RaftIosNotificationRetraction.localReadRetract(serverId: " s1 ", conversationId: " task-ch-7 ")
        XCTAssertEqual(
            retract,
            RaftIosNotificationRetract(
                serverId: "s1",
                groupKey: "s1:task-ch-7",
                conversationId: "task-ch-7",
                throughSeq: nil,
                badge: nil
            )
        )

        let delivered: [(identifier: String, userInfo: [AnyHashable: Any])] = [
            ("t1", ["groupKey": "s1:task-ch-7", "seq": 10]),
            ("t2", ["groupKey": "s1:task-ch-7", "seq": 11]),
            ("other", ["groupKey": "s1:ch-1", "seq": 1]),
        ]
        XCTAssertEqual(
            RaftIosNotificationRetraction.identifiersToRemove(delivered: delivered, retract: retract!),
            ["t1", "t2"]
        )

        let suite = "RaftIosNotificationRetractionTests.localRead.\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suite) else { return XCTFail("no defaults") }
        defer { defaults.removePersistentDomain(forName: suite) }

        let watermarks = RaftIosRetractWatermarks.record(retract!, defaults: defaults, now: t0)
        XCTAssertTrue(
            RaftIosRetractWatermarks.isCovered(
                userInfo: ["groupKey": "s1:task-ch-7", "seq": 12],
                postedAt: t0.addingTimeInterval(5),
                watermarks: watermarks,
                now: t0.addingTimeInterval(5)
            )
        )

        // Sign-out clears persisted retract watermarks.
        RaftIosRetractWatermarks.clearAll(defaults: defaults)
        XCTAssertTrue(RaftIosRetractWatermarks.load(defaults: defaults, now: t0.addingTimeInterval(10)).isEmpty)
    }

    func testTappedSiblingRetractMatchesConversationsAndSkipsMessagelessNotices() {
        let channelTap = RaftIosNotificationRetraction.tappedSiblingRetract(userInfo: [
            "type": "alert",
            "serverId": "s1",
            "channelId": "c1",
            "conversationId": "c1",
            "groupKey": "s1:c1",
            "raftCategory": "channel",
            "seq": 5,
        ])
        XCTAssertEqual(
            channelTap,
            RaftIosNotificationRetract(serverId: "s1", groupKey: "s1:c1", conversationId: "c1", throughSeq: nil, badge: nil)
        )

        let taskTap = RaftIosNotificationRetraction.tappedSiblingRetract(userInfo: [
            "type": "alert",
            "serverId": "s1",
            "channelId": "parent-ch",
            "taskConversationChannelId": "task-ch-7",
            "conversationId": "task-ch-7",
            "groupKey": "s1:task-ch-7",
            "raftCategory": "thread",
            "route": [
                "type": "task",
                "serverId": "s1",
                "parentChannelId": "parent-ch",
                "parentChannelType": "channel",
                "taskId": "task-7",
                "messageId": "m-9",
            ],
        ])
        XCTAssertEqual(
            taskTap,
            RaftIosNotificationRetract(
                serverId: "s1",
                groupKey: "s1:task-ch-7",
                conversationId: "task-ch-7",
                throughSeq: nil,
                badge: nil
            )
        )

        // Message-less notices, app routes, and background pushes do not wipe sibling notifications.
        XCTAssertNil(
            RaftIosNotificationRetraction.tappedSiblingRetract(userInfo: [
                "type": "alert",
                "serverId": "s1",
                "raftCategory": "reminder",
                "groupKey": "s1:reminder",
                "route": NSNull(),
            ])
        )
        XCTAssertNil(
            RaftIosNotificationRetraction.tappedSiblingRetract(userInfo: [
                "type": "alert",
                "serverId": "s1",
                "raftCategory": "system_notice",
                "groupKey": "s1:app_permission_update",
                "route": ["type": "app", "serverId": "s2", "appId": "app-42"],
            ])
        )
        XCTAssertNil(
            RaftIosNotificationRetraction.tappedSiblingRetract(userInfo: [
                "type": "retract",
                "serverId": "s1",
                "groupKey": "s1:c1",
                "conversationId": "c1",
            ])
        )
    }

    // MARK: Visible conversation matching (including task conversations)

    func testVisibleConversationQueryResolvesTaskConversationWithoutParentFalseMatch() {
        let taskWithAnchor = RaftIosNotificationOpen.visibleConversationQuery(userInfo: [
            "serverId": "s1",
            "channelId": "parent-ch-1",
            "messageId": "anchor-msg-1",
            "kind": "channel",
            "taskId": "task-7",
            "taskConversationChannelId": "task-ch-7",
            "taskMessageId": "reply-msg-9",
            "conversationId": "task-ch-7",
            "raftCategory": "thread",
            "aps": ["category": "RAFT_MESSAGE"],
            "route": [
                "type": "task",
                "serverId": "s1",
                "parentChannelId": "parent-ch-1",
                "parentChannelType": "channel",
                "taskId": "task-7",
                "messageId": "reply-msg-9",
            ],
        ])
        XCTAssertEqual(
            taskWithAnchor,
            RaftIosNotificationOpen.VisibleConversationQuery(
                serverId: "s1",
                kind: "task",
                channelId: "task-ch-7",
                threadId: nil,
                parentChannelId: nil,
                parentMessageId: nil,
                messageId: "reply-msg-9",
                category: "thread"
            )
        )

        // Standalone task reply (no anchor messageId at top level) still resolves taskMessageId / route.messageId.
        let standaloneTask = RaftIosNotificationOpen.visibleConversationQuery(userInfo: [
            "serverId": "s1",
            "channelId": "parent-ch-1",
            "kind": "dm",
            "conversationId": "task-ch-8",
            "raftCategory": "mention",
            "route": [
                "type": "task",
                "serverId": "s1",
                "parentChannelId": "parent-ch-1",
                "parentChannelType": "dm",
                "taskId": "task-8",
                "messageId": "reply-msg-10",
            ],
        ])
        XCTAssertEqual(standaloneTask.kind, "task")
        XCTAssertEqual(standaloneTask.channelId, "task-ch-8")
        XCTAssertEqual(standaloneTask.messageId, "reply-msg-10")
        XCTAssertNil(standaloneTask.parentChannelId)
        XCTAssertEqual(standaloneTask.category, "mention")

        // DM push prefers `raftCategory` over `aps.category` ("RAFT_MESSAGE").
        let dmQuery = RaftIosNotificationOpen.visibleConversationQuery(userInfo: [
            "serverId": "s1",
            "conversationId": "dm-1",
            "messageId": "m-1",
            "raftCategory": "dm",
            "aps": ["category": "RAFT_MESSAGE"],
        ])
        XCTAssertEqual(dmQuery.channelId, "dm-1")
        XCTAssertEqual(dmQuery.category, "dm")

        // Route-only thread push resolves kind, threadId, parentChannelId, parentMessageId, and messageId.
        let threadQuery = RaftIosNotificationOpen.visibleConversationQuery(userInfo: [
            "serverId": "s1",
            "conversationId": "th-stream-1",
            "raftCategory": "thread",
            "route": [
                "type": "thread",
                "serverId": "s1",
                "parentChannelId": "ch-1",
                "parentMessageId": "pm-1",
                "messageId": "m-4",
            ],
        ])
        XCTAssertEqual(threadQuery.kind, "thread")
        XCTAssertEqual(threadQuery.threadId, "th-stream-1")
        XCTAssertEqual(threadQuery.channelId, "th-stream-1")
        XCTAssertEqual(threadQuery.parentChannelId, "ch-1")
        XCTAssertEqual(threadQuery.parentMessageId, "pm-1")
        XCTAssertEqual(threadQuery.messageId, "m-4")

        // Message-less reminder yields nil channelId/messageId so it is never suppressed as a visible conversation.
        let reminderQuery = RaftIosNotificationOpen.visibleConversationQuery(userInfo: [
            "type": "alert",
            "serverId": "s1",
            "raftCategory": "reminder",
            "groupKey": "s1:reminder",
            "route": NSNull(),
        ])
        XCTAssertNil(reminderQuery.channelId)
        XCTAssertNil(reminderQuery.messageId)
    }
}
