package ai.slock.compose.data.realtime

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockRealtimeAppScopePolicyTest {
    @Test
    fun connectPlanResumesSameSessionFromHighestKnownSeq() {
        val state = SlockRealtimeAppScopeState()

        val first = state.prepareConnect(ServerId("server-1"), "token-1", requestedLastSeq = 10)
        state.updateFromEvent(MessageNew(message(seq = 42)))
        val second = state.prepareConnect(ServerId("server-1"), "token-1", requestedLastSeq = 20)

        assertFalse(first.sessionChanged)
        assertEquals(10L, first.resumeSeq)
        assertFalse(second.sessionChanged)
        assertEquals(42L, second.resumeSeq)
    }

    @Test
    fun connectPlanResetsSeqAndJoinedChannelsWhenSessionChanges() {
        val state = SlockRealtimeAppScopeState()

        state.prepareConnect(ServerId("server-1"), "token-1", requestedLastSeq = 10)
        state.updateFromEvent(MessageNew(message(seq = 42)))
        assertTrue(state.markChannelJoined(ChannelId("channel-1")))

        val changed = state.prepareConnect(ServerId("server-2"), "token-2", requestedLastSeq = 99)

        assertTrue(changed.sessionChanged)
        assertEquals(0L, changed.resumeSeq)
        assertEquals(0L, state.lastSeq)
        assertEquals(0, state.joinedChannelCount)
    }

    @Test
    fun joinedChannelStateDedupesRooms() {
        val state = SlockRealtimeAppScopeState()

        assertTrue(state.markChannelJoined(ChannelId("channel-1")))
        assertFalse(state.markChannelJoined(ChannelId("channel-1")))
        assertFalse(state.markChannelJoined(ChannelId("")))
        assertEquals(1, state.joinedChannelCount)
    }

    @Test
    fun sessionEndReasonsAreExplicit() {
        assertTrue(SlockRealtimeAppScopePolicy.isSessionEndDisconnectReason("logout"))
        assertTrue(SlockRealtimeAppScopePolicy.isSessionEndDisconnectReason("server_switch"))
        assertFalse(SlockRealtimeAppScopePolicy.isSessionEndDisconnectReason("temporary_background"))
    }

    @Test
    fun backgroundUnreadNotificationPolicyIsShared() {
        assertTrue(
            SlockRealtimeAppScopePolicy.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = false,
                preferenceEnabled = true,
                isAppInForeground = false
            )
        )
        assertFalse(
            SlockRealtimeAppScopePolicy.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = true,
                preferenceEnabled = true,
                isAppInForeground = false
            )
        )
    }

    @Test
    fun backgroundUnreadNotificationKindTreatsThreadMessagesAsThread() {
        assertEquals(
            ConversationKind.Thread,
            SlockRealtimeAppScopePolicy.backgroundUnreadNotificationConversationKind(
                message(
                    channelId = "thread-channel",
                    threadChannelId = "thread-channel",
                    parentMessageId = "parent-message"
                )
            )
        )
    }

    private fun message(
        seq: Long = 1L,
        channelId: String? = "channel-1",
        threadChannelId: String? = null,
        parentMessageId: String? = null
    ): Message =
        Message(
            id = MessageId("message-$seq"),
            threadId = ThreadId(threadChannelId ?: channelId ?: "thread-1"),
            channelId = channelId?.let(::ChannelId),
            threadChannelId = threadChannelId?.let(::ChannelId),
            seq = seq,
            author =
                MemberProfile(
                    id = MemberId("member-1"),
                    displayName = "Member One",
                    handle = "member-one",
                    kind = MemberKind.Human
                ),
            timestampLabel = "12:00",
            body = "Message $seq",
            parentMessageId = parentMessageId?.let(::MessageId)
        )
}
