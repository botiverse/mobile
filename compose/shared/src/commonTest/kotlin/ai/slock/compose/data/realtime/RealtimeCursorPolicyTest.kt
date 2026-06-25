package ai.slock.compose.data.realtime

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.SyncResumeResponse
import ai.slock.compose.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealtimeCursorPolicyTest {
    @Test
    fun heartbeatOnlyUpdatesServerMaxSeq() {
        val state = RealtimeCursorPolicy.fromAppliedSeq(41)

        val update = RealtimeCursorPolicy.updateFromEvent(state, Heartbeat(seq = 44))

        assertEquals(41L, update.state.appliedLastSeq)
        assertEquals(44L, update.state.serverMaxSeq)
        assertEquals(41L, update.state.resumeLastSeq)
        assertTrue(update.serverAhead)
    }

    @Test
    fun appliedEventsAdvanceResumeSeq() {
        val afterMessage =
            RealtimeCursorPolicy.updateFromEvent(
                RealtimeCursorPolicy.fromAppliedSeq(41),
                MessageNew(message(seq = 42))
            ).state

        val afterResume =
            RealtimeCursorPolicy.updateFromEvent(
                afterMessage,
                SyncResumeResponse(messages = emptyList(), currentSeq = 45, hasMore = false)
            ).state

        assertEquals(45L, afterResume.appliedLastSeq)
        assertEquals(45L, afterResume.serverMaxSeq)
        assertEquals(45L, afterResume.resumeLastSeq)
    }

    @Test
    fun visibleGapRequestUsesVisibleStreamSeq() {
        val request =
            RealtimeVisibleStreamGapPolicy.syncRequest(
                channelId = ChannelId("channel-1"),
                visibleMaxSeq = 10,
                serverSeq = 90
            )

        assertEquals(ChannelId("channel-1"), request?.channelId)
        assertEquals(10L, request?.sinceSeq)
        assertEquals(90L, request?.serverSeq)
    }

    @Test
    fun visibleGapRequestSkipsWhenVisibleStreamCaughtUpOrLoading() {
        assertEquals(
            null,
            RealtimeVisibleStreamGapPolicy.syncRequest(
                channelId = ChannelId("channel-1"),
                visibleMaxSeq = 90,
                serverSeq = 90
            )
        )
        assertEquals(
            null,
            RealtimeVisibleStreamGapPolicy.syncRequest(
                channelId = ChannelId("channel-1"),
                visibleMaxSeq = 10,
                serverSeq = 90,
                loading = true
            )
        )
    }

    private fun message(seq: Long): Message =
        Message(
            id = MessageId("message-$seq"),
            threadId = ThreadId("thread-1"),
            channelId = ChannelId("channel-1"),
            seq = seq,
            author =
                MemberProfile(
                    id = MemberId("member-1"),
                    displayName = "Member One",
                    handle = "member-one",
                    kind = MemberKind.Human
                ),
            timestampLabel = "12:00",
            body = "Message $seq"
        )
}
