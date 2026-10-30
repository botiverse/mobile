package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mobile half of botiverse/slock `docs/contracts/notifications.md` (SHA in
 * `contract-cases/notification-levels/SOURCE_SHA`). Payloads below are the contract's §4 / §5
 * shapes; the level rules themselves run against the shared fixture in
 * `NotificationLevelContractCasesTest` (androidUnitTest).
 */
class RaftNotificationContractTest {
    @Test
    fun inheritedLevelForDefaultLabels() {
        assertEquals(
            RaftNotificationLevel.Mentions,
            inheritedNotificationLevel(RaftNotificationLevelScope.Channel, RaftNotificationLevel.Mentions, RaftNotificationLevelSetting.None)
        )
        assertEquals(
            RaftNotificationLevel.None,
            inheritedNotificationLevel(RaftNotificationLevelScope.Thread, RaftNotificationLevel.All, RaftNotificationLevelSetting.None)
        )
        assertEquals(
            RaftNotificationLevel.All,
            inheritedNotificationLevel(RaftNotificationLevelScope.Thread, RaftNotificationLevel.All, RaftNotificationLevelSetting.Default)
        )
    }

    @Test
    fun androidChannelFollowsTheContractCategoryMapping() {
        val expected =
            mapOf(
                "dm" to RaftAndroidNotificationChannelKind.DirectMessage,
                "mention" to RaftAndroidNotificationChannelKind.Mention,
                "task_assigned" to RaftAndroidNotificationChannelKind.Mention,
                "agent_attention" to RaftAndroidNotificationChannelKind.Mention,
                "reminder" to RaftAndroidNotificationChannelKind.Mention,
                "system_notice" to RaftAndroidNotificationChannelKind.Mention,
                "test" to RaftAndroidNotificationChannelKind.Mention,
                "task_status" to RaftAndroidNotificationChannelKind.Channel,
                "thread" to RaftAndroidNotificationChannelKind.Channel,
                "channel" to RaftAndroidNotificationChannelKind.Channel,
            )
        assertEquals(RaftNotificationCategory.entries.map { it.wireName }.toSet(), expected.keys, "every category is mapped")
        for ((category, kind) in expected) {
            assertEquals(kind, raftAndroidNotificationChannelKind(category), category)
        }
    }

    @Test
    fun androidChannelForOlderPayloads() {
        assertEquals(RaftAndroidNotificationChannelKind.Channel, raftAndroidNotificationChannelKind("RAFT_MESSAGE"))
        assertEquals(RaftAndroidNotificationChannelKind.Mention, raftAndroidNotificationChannelKind("RAFT_MESSAGE", mentioned = true))
        assertEquals(RaftAndroidNotificationChannelKind.DirectMessage, raftAndroidNotificationChannelKind("direct_message"))
        assertEquals(RaftAndroidNotificationChannelKind.Channel, raftAndroidNotificationChannelKind("channel_unread"))
        assertEquals(RaftAndroidNotificationChannelKind.Channel, raftAndroidNotificationChannelKind(null))
    }

    @Test
    fun pushTypeDefaultsToAlertAndDropsUnknownTypes() {
        assertEquals(RaftPushMessageType.Alert, RaftPushMessageType.fromWire(null))
        assertEquals(RaftPushMessageType.Alert, RaftPushMessageType.fromWire(""))
        assertEquals(RaftPushMessageType.Sync, RaftPushMessageType.fromWire("sync"))
        assertEquals(RaftPushMessageType.Retract, RaftPushMessageType.fromWire("retract"))
        assertNull(RaftPushMessageType.fromWire("future_type"))
    }

    @Test
    fun fcmRetractParsesStringsAndEmptyThroughSeqMeansAll() {
        val partial =
            parseRaftFcmNotificationRetract(
                mapOf("type" to "retract", "serverId" to "s1", "conversationId" to "c1", "groupKey" to "s1:c1", "throughSeq" to "42", "badge" to "3")
            )
        assertEquals(RaftNotificationRetract("s1", "s1:c1", "c1", 42L), partial)
        val all = parseRaftFcmNotificationRetract(mapOf("serverId" to "s1", "conversationId" to "c1", "throughSeq" to ""))
        assertEquals(RaftNotificationRetract("s1", "s1:c1", "c1", null), all)
        assertNull(parseRaftFcmNotificationRetract(mapOf("serverId" to "s1", "conversationId" to "")))
        assertNull(parseRaftFcmNotificationRetract(mapOf("serverId" to "s1", "conversationId" to "c1", "throughSeq" to "x")))
    }

    @Test
    fun socketRetractPayload() {
        assertEquals(
            RaftNotificationRetract("s1", "s1:c1", "c1", 7L),
            parseRaftNotificationRetractPayload("""{"serverId":"s1","groupKey":"s1:c1","conversationId":"c1","throughSeq":7}""")
        )
        assertEquals(
            RaftNotificationRetract("s1", "s1:c1", "c1", null),
            parseRaftNotificationRetractPayload("""{"serverId":"s1","groupKey":"s1:c1","conversationId":"c1","throughSeq":null}""")
        )
        assertNull(parseRaftNotificationRetractPayload("""{"serverId":"s1","groupKey":"s1:c1","conversationId":"c1","throughSeq":"7"}"""))
        assertNull(parseRaftNotificationRetractPayload("""{"serverId":"s1","conversationId":"c1"}"""))
    }

    @Test
    fun retractCoversItsGroupUpToThroughSeq() {
        val retract = RaftNotificationRetract("s1", "s1:c1", "c1", 10L)
        assertTrue(retract.covers("s1:c1", 10L))
        assertTrue(retract.covers("s1:c1", 3L))
        assertFalse(retract.covers("s1:c1", 11L), "a newer message stays")
        assertFalse(retract.covers("s1:c2", 3L), "another conversation stays")
        assertFalse(retract.covers(null, 3L))
        assertTrue(retract.covers("s1:c1", null), "a notification without seq predates the read")
        assertTrue(RaftNotificationRetract("s1", "s1:c1", "c1", null).covers("s1:c1", 999L))
    }

    @Test
    fun routesParseAllFiveTypes() {
        assertEquals(
            RaftNotificationRoute.Channel("s1", "c1", "m1"),
            parseRaftNotificationRoute("""{"type":"channel","serverId":"s1","channelId":"c1","messageId":"m1"}""")
        )
        assertEquals(
            RaftNotificationRoute.DirectMessage("s1", "d1", null),
            parseRaftNotificationRoute("""{"type":"dm","serverId":"s1","channelId":"d1","messageId":null}""")
        )
        assertEquals(
            RaftNotificationRoute.Thread("s1", "d1", parentChannelIsDm = true, parentMessageId = "pm", messageId = "m2"),
            parseRaftNotificationRoute(
                """{"type":"thread","serverId":"s1","parentChannelId":"d1","parentChannelType":"dm","parentMessageId":"pm","messageId":"m2"}"""
            )
        )
        assertEquals(
            RaftNotificationRoute.Task("s1", "c1", parentChannelIsDm = false, taskId = "t1", messageId = null),
            parseRaftNotificationRoute("""{"type":"task","serverId":"s1","parentChannelId":"c1","parentChannelType":"channel","taskId":"t1","messageId":null}""")
        )
        assertEquals(RaftNotificationRoute.Agent("s1", "a1"), parseRaftNotificationRoute("""{"type":"agent","serverId":"s1","agentId":"a1"}"""))
    }

    @Test
    fun routesRejectIncompleteOrUnknownShapes() {
        assertNull(parseRaftNotificationRoute(null))
        assertNull(parseRaftNotificationRoute(""))
        assertNull(parseRaftNotificationRoute("not json"))
        assertNull(parseRaftNotificationRoute("""{"type":"channel","channelId":"c1"}"""), "serverId is required")
        assertNull(parseRaftNotificationRoute("""{"type":"thread","serverId":"s1","parentChannelId":"c1","parentMessageId":"pm"}"""))
        assertNull(parseRaftNotificationRoute("""{"type":"inbox","serverId":"s1"}"""))
    }

    @Test
    fun routeLegacyOpenFieldsKeepTheServer() {
        val thread = RaftNotificationRoute.Thread("s2", "c1", false, "pm", "m2").legacyOpenFields()
        assertEquals("s2", thread["serverId"])
        assertEquals("thread", thread["kind"])
        assertEquals("c1", thread["parentChannelId"])
        assertEquals("pm", thread["parentMessageId"])
        val task = RaftNotificationRoute.Task("s2", "d1", true, "t9", null).legacyOpenFields()
        assertEquals("dm", task["kind"])
        assertEquals("d1", task["channelId"])
        assertEquals("t9", task["taskId"])
        assertEquals(mapOf("serverId" to "s2", "agentId" to "a1"), RaftNotificationRoute.Agent("s2", "a1").legacyOpenFields())
    }

    @Test
    fun badgeSumsEveryServerExceptNone() {
        assertEquals(
            7,
            raftNotificationBadgeCount(
                listOf(
                    RaftNotificationLevel.All to 3,
                    RaftNotificationLevel.Mentions to 4,
                    RaftNotificationLevel.None to 100,
                    null to 0,
                    RaftNotificationLevel.All to -2,
                )
            )
        )
        assertEquals(5, raftNotificationBadgeCount(listOf(null to 5)), "an unknown level counts (default all)")
    }

    @Test
    fun appBadgeCountsEveryServerNotOnlyTheSelectedOne() {
        // Design §6 / contract §1.4: the in-app badge is the same sum as the APNs badge.
        val badge =
            raftAppBadgeAcrossServers(
                currentServerId = "a",
                currentServerCount = 2,
                otherServerCounts = mapOf("a" to 99L, "b" to 3L, "c" to 5L, "d" to 7L),
                serverPushMuted = mapOf("c" to true),
                levels = mapOf("d" to RaftNotificationLevel.None, "b" to RaftNotificationLevel.Mentions)
            )
        // a: the live count (2), not the summary's 99; b counts; c muted (none) and d none are left out.
        assertEquals(5, badge)
        assertEquals(
            0,
            raftAppBadgeAcrossServers("a", 4, emptyMap(), emptyMap(), mapOf("a" to RaftNotificationLevel.None)),
            "the selected server at none is left out too"
        )
        assertEquals(4, raftAppBadgeAcrossServers("a", 4, emptyMap(), mapOf("a" to true), mapOf("a" to RaftNotificationLevel.All)),
            "a known level wins over the summary's mute flag")
    }

    @Test
    fun presencePayloads() {
        assertEquals("""{"active":true,"idleSeconds":0}""", buildRaftPresenceActivityPayload(active = true))
        assertEquals("""{"active":false,"idleSeconds":null}""", buildRaftPresenceActivityPayload(active = false))
    }

    @Test
    fun pushScalars() {
        assertEquals(3, parseRaftPushBadge("3"))
        assertNull(parseRaftPushBadge(""))
        assertNull(parseRaftPushBadge("-1"))
        assertEquals(12L, parseRaftPushSeq("12"))
        assertNull(parseRaftPushSeq(""))
        assertNull(parseRaftPushSeq("null"))
    }
}
