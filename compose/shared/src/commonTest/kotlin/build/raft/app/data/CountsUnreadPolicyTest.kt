package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageConversationContext
import build.raft.app.model.MessageId
import build.raft.app.model.MessageMention
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageType
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CountsUnreadPolicyTest {
    private val me =
        MemberProfile(
            id = MemberId("user-1"),
            displayName = "Me",
            handle = "me",
            kind = MemberKind.Human,
            avatarLabel = "ME",
            presence = Presence.Active,
        )
    private val system =
        MemberProfile(
            id = MemberId("system"),
            displayName = "System",
            handle = "system",
            kind = MemberKind.System,
            avatarLabel = "SY",
            presence = Presence.Offline,
        )

    private fun homeSnapshot(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("srv-1"), name = "Raft", unreadCount = 0),
            currentMember = me,
            channels =
                listOf(
                    HomeChannelRow(
                        channelId = ChannelId("ch-1"),
                        threadId = ThreadId("ch-1"),
                        name = "general",
                        unreadCount = 0,
                        unreadCountKnown = true,
                        mentionCount = 0,
                        mentionCountKnown = true,
                        lastMessageId = MessageId("m-1"),
                        lastMessageSeq = 1L,
                    )
                ),
            directMessages =
                listOf(
                    HomeDirectMessageRow(
                        channelId = ChannelId("dm-1"),
                        threadId = ThreadId("dm-1"),
                        name = "Agent",
                        unreadCount = 0,
                        unreadCountKnown = true,
                        mentionCount = 0,
                        mentionCountKnown = true,
                        lastMessageId = MessageId("d-1"),
                        lastMessageSeq = 1L,
                        lounge =
                            AgentLoungeSummary(
                                channelId = ChannelId("lounge-1"),
                                joined = true,
                                unreadCount = 0,
                                mentionCount = 0,
                                lastMessageId = MessageId("l-1"),
                                lastMessageSeq = 1L,
                            ),
                    )
                ),
            followedThreadUnread =
                mapOf(
                    "th-1" to
                        HomeFollowedThreadUnread(
                            parentChannelId = ChannelId("lounge-1"),
                            unreadCount = 0,
                            hasMention = false,
                        )
                ),
            unreadTotal = 0,
            serverUnreadCounts = mapOf("srv-1" to 0),
        )

    private fun historyOnlySystemMessage(
        id: String,
        channelId: String,
        seq: Long,
        countsUnread: Boolean,
        mentionViewer: Boolean = false,
        context: MessageConversationContext? = null,
    ): Message =
        Message(
            id = MessageId(id),
            threadId = ThreadId(channelId),
            channelId = ChannelId(channelId),
            seq = seq,
            author = system,
            timestampLabel = "2026-11-24T19:00:00Z",
            body = "Alice added Agent",
            messageType = MessageType.System,
            countsUnread = countsUnread,
            mentions = if (mentionViewer) listOf(MessageMention(type = "user", id = "user-1")) else emptyList(),
            conversationContext = context,
        )

    @Test
    fun realtimeEventMapperDecodesCountsUnreadAndDefaultsAbsentToTrue() {
        val mapper = RealtimeEventMapper()
        val falseEvent =
            assertIs<MessageNew>(
                mapper.map(
                    RealtimeSocketEvents.MessageNew,
                    """
                    {
                      "id": "m-10",
                      "channelId": "ch-1",
                      "seq": 10,
                      "senderType": "user",
                      "senderId": "system",
                      "messageType": "system",
                      "content": "added",
                      "systemSubtype": "channel.agent_membership",
                      "causalActorType": "user",
                      "causalActorId": "user-2",
                      "countsUnread": false
                    }
                    """.trimIndent(),
                )
            )
        assertFalse(falseEvent.message.countsUnread)
        assertEquals(false, falseEvent.systemProvenance?.countsUnread)

        val omittedEvent =
            assertIs<MessageNew>(
                mapper.map(
                    RealtimeSocketEvents.MessageNew,
                    """
                    {
                      "id": "m-11",
                      "channelId": "ch-1",
                      "seq": 11,
                      "senderType": "user",
                      "senderId": "system",
                      "messageType": "system",
                      "content": "added",
                      "systemSubtype": "channel.agent_membership",
                      "causalActorType": "user",
                      "causalActorId": "user-2"
                    }
                    """.trimIndent(),
                )
            )
        assertTrue(omittedEvent.message.countsUnread)
        assertNull(omittedEvent.systemProvenance?.countsUnread)
    }

    @Test
    fun activityUnreadPredicateSuppressesCountsUnreadFalseWhileKeepingViewerMention() {
        assertTrue(messageCountsAsUnreadActivity(countsUnread = null, systemSubtype = "channel.agent_membership"))
        assertTrue(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "channel.agent_membership"))
        assertFalse(messageCountsAsUnreadActivity(countsUnread = false, systemSubtype = "channel.agent_membership"))
        assertFalse(messageCountsAsUnreadActivity(countsUnread = true, systemSubtype = "task.deleted_summary"))

        val viewer = ActivityUnreadViewer(type = "user", id = "user-1")
        val msg =
            ActivityUnreadMessage(
                senderType = "user",
                senderId = "system",
                messageType = "system",
                causalActorType = "user",
                causalActorId = "user-2",
                systemSubtype = "channel.agent_membership",
                countsUnread = false,
                mentions = listOf(ActivityUnreadTarget(type = "user", id = "user-1")),
            )
        for (kind in listOf(ActivityUnreadRowKind.Channel, ActivityUnreadRowKind.Thread)) {
            assertEquals(
                ActivityUnreadVerdict(counts = false, mention = true, moves = true),
                activityLiveUnread(msg, viewer, ActivityUnreadScope(rowKind = kind)),
            )
            assertEquals(
                true,
                activityLiveUnread(msg.copy(countsUnread = true), viewer, ActivityUnreadScope(rowKind = kind)).counts,
            )
        }
    }

    @Test
    fun homeRealtimeMessagesDoNotIncrementUnreadOrBadgeForCountsUnreadFalseWhileKeepingMention() {
        val base = homeSnapshot()

        // History-only channel system message without mention: 0 unread, 0 server/app badge, 0 mention.
        val afterPlain =
            base.withRealtimeMessages(
                HomeRealtimeMessagePatch(
                    messages = listOf(historyOnlySystemMessage("m-2", "ch-1", seq = 2L, countsUnread = false)),
                    incrementUnread = true,
                ),
                activeConversationId = null,
            )
        assertEquals(0, afterPlain.channels.single().unreadCount)
        assertEquals(0, afterPlain.channels.single().mentionCount)
        assertEquals(0, afterPlain.server.unreadCount)
        assertEquals(0, afterPlain.unreadTotal)
        assertEquals(0, afterPlain.serverUnreadCounts["srv-1"])

        // History-only channel system message mentioning viewer: still 0 unread/badge, mention lights up.
        val afterMention =
            afterPlain.withRealtimeMessages(
                HomeRealtimeMessagePatch(
                    messages = listOf(historyOnlySystemMessage("m-3", "ch-1", seq = 3L, countsUnread = false, mentionViewer = true)),
                    incrementUnread = true,
                ),
                activeConversationId = null,
            )
        assertEquals(0, afterMention.channels.single().unreadCount)
        assertEquals(1, afterMention.channels.single().mentionCount)
        assertEquals(0, afterMention.server.unreadCount)
        assertEquals(0, afterMention.unreadTotal)

        // Agent Lounge history-only notice with mention: 0 unread/badge, lounge mentionCount = 1.
        val afterLounge =
            base.withRealtimeMessages(
                HomeRealtimeMessagePatch(
                    messages = listOf(historyOnlySystemMessage("l-2", "lounge-1", seq = 2L, countsUnread = false, mentionViewer = true)),
                    incrementUnread = true,
                ),
                activeConversationId = null,
            )
        assertEquals(0, afterLounge.directMessages.single().lounge?.unreadCount)
        assertEquals(1, afterLounge.directMessages.single().lounge?.mentionCount)
        assertEquals(0, afterLounge.server.unreadCount)
        assertEquals(0, afterLounge.unreadTotal)
    }

    @Test
    fun loungeThreadReplyHistoryOnlyNoticeSkipsUnreadBumpWhilePreservingMention() {
        val base = homeSnapshot()
        val ctx =
            MessageConversationContext(
                channelKind = ConversationKind.Thread,
                parentChannelId = ChannelId("lounge-1"),
            )
        val plain = historyOnlySystemMessage("r-2", "th-1", seq = 2L, countsUnread = false, mentionViewer = false, context = ctx)
        assertEquals(
            HomeThreadReplyDecision.Ignore,
            base.decideLoungeThreadReplyMessage(plain, openConversationIds = emptySet(), loungeFeatureEnabled = true),
        )

        val mentioned = historyOnlySystemMessage("r-3", "th-1", seq = 3L, countsUnread = false, mentionViewer = true, context = ctx)
        assertEquals(
            HomeThreadReplyDecision.Bump,
            base.decideLoungeThreadReplyMessage(mentioned, openConversationIds = emptySet(), loungeFeatureEnabled = true),
        )
        val updated = base.withLoungeThreadReplyMessage(mentioned)
        assertEquals(0, updated.followedThreadUnread["th-1"]?.unreadCount)
        assertEquals(true, updated.followedThreadUnread["th-1"]?.hasMention)
    }

    @Test
    fun threadUpdatedLatestReplyCountsUnreadFalseSkipsUnreadBumpWhileKeepingMention() {
        val mapper = RealtimeEventMapper()
        val mappedFalse =
            assertIs<ThreadUpdated>(
                mapper.map(
                    RealtimeSocketEvents.ThreadUpdated,
                    """
                    {
                      "parentMessageId": "p-1",
                      "threadChannelId": "th-2",
                      "replyCount": 2,
                      "latestReply": {
                        "id": "r-10",
                        "seq": 10,
                        "content": "system notice",
                        "senderId": "system",
                        "senderType": "user",
                        "senderName": "System",
                        "messageType": "system",
                        "createdAt": "2026-11-24T19:00:00Z",
                        "countsUnread": false
                      }
                    }
                    """.trimIndent(),
                )
            )
        assertFalse(mappedFalse.latestReplyCountsUnread)

        val mappedDefault =
            assertIs<ThreadUpdated>(
                mapper.map(
                    RealtimeSocketEvents.ThreadUpdated,
                    """
                    {
                      "parentMessageId": "p-1",
                      "threadChannelId": "th-2",
                      "replyCount": 3,
                      "latestReply": {
                        "id": "r-11",
                        "seq": 11,
                        "content": "hello",
                        "senderId": "user-2",
                        "senderType": "user",
                        "senderName": "Bob",
                        "messageType": "chat",
                        "createdAt": "2026-11-24T19:01:00Z"
                      }
                    }
                    """.trimIndent(),
                )
            )
        assertTrue(mappedDefault.latestReplyCountsUnread)

        val base =
            homeSnapshot().copy(
                followedThreadUnread =
                    mapOf(
                        "th-2" to
                            HomeFollowedThreadUnread(
                                parentChannelId = ChannelId("ch-1"),
                                unreadCount = 1,
                                hasMention = false,
                            )
                    )
            )
        val plain =
            ThreadUpdated(
                parentMessageId = MessageId("p-1"),
                threadChannelId = ChannelId("th-2"),
                replyCount = 2,
                lastReplyAt = "2026-11-24T19:00:00Z",
                participantIds = emptyList(),
                latestReply =
                    ReplyPreview(
                        messageId = "r-10",
                        seq = 10L,
                        content = "notice",
                        senderId = "system",
                        senderType = "system",
                        senderName = "System",
                        createdAt = "2026-11-24T19:00:00Z",
                    ),
                latestReplyCountsUnread = false,
            )
        assertEquals(HomeThreadReplyDecision.Ignore, base.decideThreadReply(plain, openConversationId = null))

        val mentioned = plain.copy(latestReplyMentions = listOf(MessageMention(type = "user", id = "user-1")))
        assertEquals(HomeThreadReplyDecision.MentionOnly, base.decideThreadReply(mentioned, openConversationId = null))
        val afterMention = base.withFollowedThreadMentionOnly(mentioned)
        assertEquals(1, afterMention.followedThreadUnread["th-2"]?.unreadCount)
        assertEquals(true, afterMention.followedThreadUnread["th-2"]?.hasMention)

        val afterDefenseInDepth = base.withFollowedThreadReply(mentioned)
        assertEquals(1, afterDefenseInDepth.followedThreadUnread["th-2"]?.unreadCount)
        assertEquals(true, afterDefenseInDepth.followedThreadUnread["th-2"]?.hasMention)
    }
}
