package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.MessageNew
import build.raft.app.model.ThreadUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract parity tests for botiverse/slock #16677 (`AGENT_ONLY_SYSTEM_SUBTYPES`, `readerType` on
 * `messageCountsAsUnreadActivity`) and #16678 (`messages.agent_only`).
 */
class ActivityUnreadPredicateTest {
    @Test
    fun agentOnlySystemSubtypesMatchSlock16677() {
        assertEquals(setOf("agent.server_role_hint", "channel.self_unfollow_thread"), AGENT_ONLY_SYSTEM_SUBTYPES)
    }

    @Test
    fun agentOnlySystemSubtypeCountsOnlyForAgentsNotPeople() {
        // Default readerType ("user") preserves human-client callers and never counts agents-only rows.
        assertFalse(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "agent.server_role_hint"))
        assertFalse(messageCountsAsUnreadActivity(countsUnread = null, systemSubtype = "agent.server_role_hint"))
        assertFalse(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "agent.server_role_hint", readerType = "user"))
        assertTrue(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "agent.server_role_hint", readerType = "agent"))
        assertTrue(messageCountsAsUnreadActivity(countsUnread = null, systemSubtype = "agent.server_role_hint", readerType = "agent"))
        assertFalse(messageCountsAsUnreadActivity(countsUnread = false, systemSubtype = "agent.server_role_hint", readerType = "agent"))

        // An old unfollow row (counts_unread default true) stays noise for agents too (slock #16677).
        assertFalse(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "channel.self_unfollow_thread", readerType = "user"))
        assertFalse(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "channel.self_unfollow_thread", readerType = "agent"))
        assertFalse(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "task.deleted_summary", readerType = "user"))
        assertFalse(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "task.deleted_summary", readerType = "agent"))

        // Ordinary system notices still count for both readers unless countsUnread == false.
        assertTrue(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "channel.agent_membership", readerType = "user"))
        assertTrue(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "channel.agent_membership", readerType = "agent"))
        assertTrue(messageCountsAsUnreadActivity(countsUnread = null, systemSubtype = null, readerType = "user"))
    }

    @Test
    fun agentOnlyWireFlagSuppressesUnreadForUsersWhileLeavingAgentsIntact() {
        // Defense-in-depth for slock #16678 `messages.agent_only`: even an unknown future subtype
        // flagged agentOnly=true never counts for a human reader.
        assertFalse(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "agent.future_hint", readerType = "user", agentOnly = true))
        assertTrue(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "agent.future_hint", readerType = "agent", agentOnly = true))
        assertFalse(messageCountsAsUnreadActivity(countsUnread = false, systemSubtype = "agent.future_hint", readerType = "agent", agentOnly = true))
        assertTrue(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "channel.agent_membership", readerType = "user", agentOnly = false))
    }

    @Test
    fun activityLiveUnreadRespectsViewerTypeForAgentOnlySubtypesAndAgentOnlyFlag() {
        val userViewer = ActivityUnreadViewer(type = "user", id = "user-1")
        val agentViewer = ActivityUnreadViewer(type = "agent", id = "agent-1")
        val hint =
            ActivityUnreadMessage(
                senderType = "user",
                senderId = "system",
                messageType = "system",
                causalActorType = "user",
                causalActorId = "user-2",
                systemSubtype = "agent.server_role_hint",
                countsUnread = true,
                agentOnly = true,
            )

        for (kind in listOf(ActivityUnreadRowKind.Channel, ActivityUnreadRowKind.Dm, ActivityUnreadRowKind.Thread)) {
            assertEquals(
                ActivityUnreadVerdict(counts = false, mention = false, moves = true),
                activityLiveUnread(hint, userViewer, ActivityUnreadScope(rowKind = kind)),
                "user reader must not count agent.server_role_hint on $kind",
            )
            assertEquals(
                ActivityUnreadVerdict(counts = true, mention = false, moves = true),
                activityLiveUnread(hint, agentViewer, ActivityUnreadScope(rowKind = kind)),
                "agent reader counts agent.server_role_hint on $kind",
            )
        }

        // Even if an agents-only notice carried a personal mention of a user, unread stays false while mention still lights `@`.
        val mentionedHint = hint.copy(mentions = listOf(ActivityUnreadTarget(type = "user", id = "user-1")))
        assertEquals(
            ActivityUnreadVerdict(counts = false, mention = true, moves = true),
            activityLiveUnread(mentionedHint, userViewer, ActivityUnreadScope(rowKind = ActivityUnreadRowKind.Dm)),
        )

        // Agent-caused or self-caused rule still applies to agents reading agent.server_role_hint.
        assertEquals(
            false,
            activityLiveUnread(
                hint.copy(causalActorType = "agent", causalActorId = "agent-1"),
                agentViewer,
                ActivityUnreadScope(rowKind = ActivityUnreadRowKind.Channel),
            ).counts,
        )

        // Future agents-only frame with agentOnly=true does not count for a user even without subtype provenance.
        val futureAgentOnly =
            ActivityUnreadMessage(
                senderType = "user",
                senderId = "system",
                messageType = "system",
                carriesSystemProvenance = false,
                countsUnread = true,
                agentOnly = true,
            )
        assertEquals(
            false,
            activityLiveUnread(futureAgentOnly, userViewer, ActivityUnreadScope(rowKind = ActivityUnreadRowKind.Channel)).counts,
        )
    }

    @Test
    fun realtimeEventMapperDecodesAgentOnlyProvenanceAndThreadsToUnreadMessage() {
        val mapper = RealtimeEventMapper()
        val agentOnlyEvent =
            assertIs<MessageNew>(
                mapper.map(
                    RealtimeSocketEvents.MessageNew,
                    """
                    {
                      "id": "m-hint-1",
                      "channelId": "dm-1",
                      "seq": 12,
                      "senderType": "user",
                      "senderId": "system",
                      "messageType": "system",
                      "content": "run raft server info",
                      "systemSubtype": "agent.server_role_hint",
                      "causalActorType": "user",
                      "causalActorId": "user-2",
                      "countsUnread": true,
                      "agentOnly": true
                    }
                    """.trimIndent(),
                )
            )
        assertEquals("agent.server_role_hint", agentOnlyEvent.systemProvenance?.systemSubtype)
        assertEquals(true, agentOnlyEvent.systemProvenance?.agentOnly)

        val unreadMessage = agentOnlyEvent.message.toActivityUnreadMessage(agentOnlyEvent.systemProvenance)
        assertEquals(true, unreadMessage.agentOnly)
        assertEquals(
            false,
            activityLiveUnread(
                unreadMessage,
                ActivityUnreadViewer(type = "user", id = "user-1"),
                ActivityUnreadScope(rowKind = ActivityUnreadRowKind.Dm),
            ).counts,
        )
        assertEquals(
            true,
            activityLiveUnread(
                unreadMessage,
                ActivityUnreadViewer(type = "agent", id = "agent-1"),
                ActivityUnreadScope(rowKind = ActivityUnreadRowKind.Dm),
            ).counts,
        )

        val omittedEvent =
            assertIs<MessageNew>(
                mapper.map(
                    RealtimeSocketEvents.MessageNew,
                    """
                    {
                      "id": "m-plain-1",
                      "channelId": "ch-1",
                      "seq": 13,
                      "senderType": "user",
                      "senderId": "system",
                      "messageType": "system",
                      "content": "notice",
                      "systemSubtype": "channel.agent_membership",
                      "causalActorType": "user",
                      "causalActorId": "user-2"
                    }
                    """.trimIndent(),
                )
            )
        assertNull(omittedEvent.systemProvenance?.agentOnly)
    }

    @Test
    fun quietForPeopleSuppressesUnreadForUsersWhileLeavingAgentsIntact() {
        assertFalse(
            messageCountsAsUnreadActivity(
                countsUnread = true,
                systemSubtype = "task.lifecycle_thread",
                readerType = "user",
                quietForPeople = true,
            )
        )
        assertTrue(
            messageCountsAsUnreadActivity(
                countsUnread = true,
                systemSubtype = "task.lifecycle_thread",
                readerType = "agent",
                quietForPeople = true,
            )
        )
        assertTrue(
            messageCountsAsUnreadActivity(
                countsUnread = true,
                systemSubtype = "task.lifecycle_thread",
                readerType = "user",
                quietForPeople = false,
            )
        )
        assertTrue(
            messageCountsAsUnreadActivity(
                countsUnread = true,
                systemSubtype = "task.lifecycle_thread",
                readerType = "user",
                quietForPeople = null,
            )
        )
    }

    @Test
    fun peopleActivityRowMessageMatchesSlockStep2cPredicate() {
        val claim =
            ActivityUnreadMessage(
                senderType = "user",
                senderId = "system",
                messageType = "system",
                systemSubtype = "task.lifecycle_thread",
                countsUnread = true,
                quietForPeople = true,
                mentions = emptyList(),
            )
        assertFalse(peopleActivityRowMessage(claim, "u1"))
        assertTrue(peopleActivityRowMessage(claim.copy(mentions = listOf(ActivityUnreadTarget("user", "u1"))), "u1"))
        assertFalse(peopleActivityRowMessage(claim.copy(mentions = listOf(ActivityUnreadTarget("agent", "u1"))), "u1"))
        assertFalse(peopleActivityRowMessage(claim.copy(mentions = listOf(ActivityUnreadTarget("user", "u2"))), "u1"))
        assertTrue(peopleActivityRowMessage(claim.copy(quietForPeople = false), "u1"))
        assertTrue(
            peopleActivityRowMessage(
                ActivityUnreadMessage(senderType = "user", senderId = "u1", countsUnread = false, systemSubtype = null),
                "u1",
            )
        )
        assertTrue(
            peopleActivityRowMessage(
                ActivityUnreadMessage(senderType = "agent", senderId = "a1", systemSubtype = null),
                "u1",
            )
        )
        assertFalse(
            peopleActivityRowMessage(
                ActivityUnreadMessage(
                    senderType = "user",
                    senderId = "system",
                    countsUnread = true,
                    systemSubtype = "agent.server_role_hint",
                ),
                "u1",
            )
        )
        assertFalse(
            peopleActivityRowMessage(
                ActivityUnreadMessage(
                    senderType = "user",
                    senderId = "system",
                    countsUnread = false,
                    systemSubtype = "channel.rename",
                ),
                "u1",
            )
        )
    }

    @Test
    fun activityLiveUnreadMovesOnTaskRowFollowsPeopleActivityPredicate() {
        val viewer = ActivityUnreadViewer(type = "user", id = "u1")
        val claim =
            ActivityUnreadMessage(
                senderType = "user",
                senderId = "system",
                messageType = "system",
                causalActorType = "agent",
                causalActorId = "a1",
                systemSubtype = "task.lifecycle_thread",
                countsUnread = true,
                quietForPeople = true,
                mentions = emptyList(),
            )
        assertEquals(
            ActivityUnreadVerdict(counts = false, mention = false, moves = false),
            activityLiveUnread(claim, viewer, ActivityUnreadScope(rowKind = ActivityUnreadRowKind.Task)),
        )
        assertEquals(
            false,
            activityLiveUnread(claim, viewer, ActivityUnreadScope(rowKind = ActivityUnreadRowKind.Task, readOnArrival = true)).moves,
        )
        assertEquals(
            ActivityUnreadVerdict(counts = false, mention = true, moves = true),
            activityLiveUnread(
                claim.copy(mentions = listOf(ActivityUnreadTarget("user", "u1"))),
                viewer,
                ActivityUnreadScope(rowKind = ActivityUnreadRowKind.Task),
            ),
        )
        assertEquals(
            true,
            activityLiveUnread(claim.copy(quietForPeople = false), viewer, ActivityUnreadScope(rowKind = ActivityUnreadRowKind.Task)).moves,
        )
        assertEquals(
            true,
            activityLiveUnread(
                ActivityUnreadMessage(senderType = "agent", senderId = "a1", messageType = "chat", systemSubtype = null),
                viewer,
                ActivityUnreadScope(rowKind = ActivityUnreadRowKind.Task),
            ).moves,
        )
        // #proj-raft-ui:#319 (slock #17075): a quiet line leaves thread, channel and DM rows in place too.
        for (rowKind in listOf(ActivityUnreadRowKind.Thread, ActivityUnreadRowKind.Channel, ActivityUnreadRowKind.Dm)) {
            assertEquals(
                ActivityUnreadVerdict(counts = false, mention = false, moves = false),
                activityLiveUnread(claim, viewer, ActivityUnreadScope(rowKind = rowKind)),
            )
            assertEquals(
                ActivityUnreadVerdict(counts = false, mention = true, moves = true),
                activityLiveUnread(
                    claim.copy(mentions = listOf(ActivityUnreadTarget("user", "u1"))),
                    viewer,
                    ActivityUnreadScope(rowKind = rowKind),
                ),
            )
            assertEquals(
                true,
                activityLiveUnread(claim, ActivityUnreadViewer(type = "agent", id = "a2"), ActivityUnreadScope(rowKind = rowKind)).moves,
            )
        }
        // Only task rows apply the whole people predicate; history-only and noise lines still move thread/channel/DM rows.
        val historyOnly =
            ActivityUnreadMessage(
                senderType = "user",
                senderId = "system",
                countsUnread = false,
                systemSubtype = "channel.rename",
                mentions = emptyList(),
            )
        assertFalse(peopleRowMessageMoves(historyOnly, "u1", ActivityUnreadRowKind.Task))
        for (rowKind in listOf(ActivityUnreadRowKind.Thread, ActivityUnreadRowKind.Channel, ActivityUnreadRowKind.Dm)) {
            assertTrue(peopleRowMessageMoves(historyOnly, "u1", rowKind))
            assertTrue(
                peopleRowMessageMoves(
                    historyOnly.copy(countsUnread = true, systemSubtype = "task.deleted_summary"),
                    "u1",
                    rowKind,
                )
            )
        }
        // Channel mute still wins first on a quiet line mentioning the viewer.
        assertEquals(
            false,
            activityLiveUnread(
                claim.copy(mentions = listOf(ActivityUnreadTarget("user", "u1"))),
                viewer,
                ActivityUnreadScope(rowKind = ActivityUnreadRowKind.Channel, activityMuted = true),
            ).moves,
        )
        assertEquals(
            true,
            activityLiveUnread(claim, ActivityUnreadViewer(type = "agent", id = "a2"), ActivityUnreadScope(rowKind = ActivityUnreadRowKind.Task)).moves,
        )
    }

    @Test
    fun realtimeEventMapperDecodesQuietForPeopleAndThreadsToUnreadMessage() {
        val mapper = RealtimeEventMapper()
        val quietEvent =
            assertIs<MessageNew>(
                mapper.map(
                    RealtimeSocketEvents.MessageNew,
                    """
                    {
                      "id": "m-quiet-1",
                      "channelId": "task-conv-1",
                      "seq": 101,
                      "senderType": "user",
                      "senderId": "system",
                      "messageType": "system",
                      "content": "Agent took the task",
                      "systemSubtype": "task.lifecycle_thread",
                      "causalActorType": "agent",
                      "causalActorId": "agent-1",
                      "countsUnread": true,
                      "quietForPeople": true
                    }
                    """.trimIndent(),
                )
            )
        assertTrue(quietEvent.message.quietForPeople)
        assertEquals(true, quietEvent.systemProvenance?.quietForPeople)

        val unreadMessage = quietEvent.message.toActivityUnreadMessage(quietEvent.systemProvenance)
        assertEquals(true, unreadMessage.quietForPeople)
        assertEquals(
            ActivityUnreadVerdict(counts = false, mention = false, moves = false),
            activityLiveUnread(
                unreadMessage,
                ActivityUnreadViewer(type = "user", id = "user-1"),
                ActivityUnreadScope(rowKind = ActivityUnreadRowKind.Task),
            ),
        )

        val notQuietEvent =
            assertIs<MessageNew>(
                mapper.map(
                    RealtimeSocketEvents.MessageNew,
                    """
                    {
                      "id": "m-quiet-2",
                      "channelId": "task-conv-1",
                      "seq": 102,
                      "senderType": "user",
                      "senderId": "system",
                      "messageType": "system",
                      "content": "Agent took the task",
                      "systemSubtype": "task.lifecycle_thread",
                      "causalActorType": "agent",
                      "causalActorId": "agent-1",
                      "quietForPeople": false
                    }
                    """.trimIndent(),
                )
            )
        assertFalse(notQuietEvent.message.quietForPeople)
        assertEquals(false, notQuietEvent.systemProvenance?.quietForPeople)
        assertEquals(false, notQuietEvent.message.toActivityUnreadMessage(notQuietEvent.systemProvenance).quietForPeople)

        val omittedEvent =
            assertIs<MessageNew>(
                mapper.map(
                    RealtimeSocketEvents.MessageNew,
                    """
                    {
                      "id": "m-quiet-3",
                      "channelId": "task-conv-1",
                      "seq": 103,
                      "senderType": "user",
                      "senderId": "system",
                      "messageType": "system",
                      "content": "Agent took the task",
                      "systemSubtype": "task.lifecycle_thread"
                    }
                    """.trimIndent(),
                )
            )
        assertFalse(omittedEvent.message.quietForPeople)
        assertNull(omittedEvent.systemProvenance?.quietForPeople)
        assertNull(omittedEvent.message.toActivityUnreadMessage(omittedEvent.systemProvenance).quietForPeople)

        val quietThreadUpdated =
            assertIs<ThreadUpdated>(
                mapper.map(
                    RealtimeSocketEvents.ThreadUpdated,
                    """
                    {
                      "parentMessageId": "p-1",
                      "threadChannelId": "task-conv-1",
                      "replyCount": 2,
                      "latestReply": {
                        "id": "r-101",
                        "seq": 101,
                        "content": "Agent took the task",
                        "senderId": "system",
                        "senderType": "user",
                        "senderName": "System",
                        "messageType": "system",
                        "createdAt": "2026-11-26T10:00:00Z",
                        "quietForPeople": true
                      }
                    }
                    """.trimIndent(),
                )
            )
        assertTrue(quietThreadUpdated.latestReplyQuietForPeople)
    }
}
