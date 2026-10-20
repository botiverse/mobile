package build.raft.app.data.realtime

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.model.ChannelId
import build.raft.app.model.Heartbeat
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ServerId
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.ThreadId
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
    fun namedVisibleStreamCursorIgnoresGlobalAppliedSeq() {
        val sinceSeq =
            RealtimeCursorPolicy.visibleStreamSinceSeq(
                visibleMaxSeq = 10,
                globalAppliedSeq = 90
            )

        assertEquals(10L, sinceSeq)
        assertTrue(
            RealtimeCursorPolicy.shouldSyncVisibleStream(
                channelId = ChannelId("channel-1"),
                visibleMaxSeq = sinceSeq,
                serverSeq = 90
            )
        )
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

    @Test
    fun appliedSeqCursorPersistsPerServerAndNeverRegresses() {
        val store = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore())
        val serverOne = ServerId("server-1")
        val serverTwo = ServerId("server-2")

        store.saveAppliedLastSeq(serverOne, 42)
        store.saveAppliedLastSeq(serverOne, 41)
        store.saveAppliedLastSeq(serverTwo, 7)

        assertEquals(42L, store.readAppliedLastSeq(serverOne))
        assertEquals(7L, store.readAppliedLastSeq(serverTwo))
    }

    @Test
    fun appliedSeqCursorResetsIntoNewEpochPerServer() {
        val store = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore())
        val serverOne = ServerId("server-1")
        val serverTwo = ServerId("server-2")

        store.saveAppliedLastSeq(serverOne, 42)
        store.saveAppliedLastSeq(serverTwo, 9)
        val nextEpoch = store.reset(serverOne)

        assertEquals(nextEpoch, store.currentEpoch(serverOne))
        assertEquals(0L, store.readAppliedLastSeq(serverOne))
        store.saveAppliedLastSeq(serverOne, 5)
        assertEquals(5L, store.readAppliedLastSeq(serverOne, nextEpoch))
        assertEquals(42L, store.readAppliedLastSeq(serverOne, DefaultMessageSeqEpoch))
        assertEquals(9L, store.readAppliedLastSeq(serverTwo))
    }

    @Test
    fun appliedSeqCursorCanReadLegacyDefaultEpochKey() {
        val keyValueStore = InMemoryRaftKeyValueStore()
        val store = RealtimeAppliedSeqCursorStore(keyValueStore)
        val serverId = ServerId("server-1")
        keyValueStore.putString("realtime.cursor.appliedLastSeq.server-1", "42")

        assertEquals(42L, store.readAppliedLastSeq(serverId))
        store.saveAppliedLastSeq(serverId, 43)
        assertEquals("43", keyValueStore.getString("realtime.cursor.appliedLastSeq.server-1"))
        assertEquals(43L, store.readAppliedLastSeq(serverId, DefaultMessageSeqEpoch))
    }

    @Test
    fun rebaselineDropsInMemoryWatermarkAndAdvancesEpoch() {
        val state = RealtimeCursorPolicy.fromAppliedSeq(42, epoch = "epoch-a")

        val update = RealtimeCursorPolicy.rebaseline(state, nextEpoch = "epoch-b")

        assertEquals(0L, update.state.appliedLastSeq)
        assertEquals(0L, update.state.serverMaxSeq)
        assertEquals("epoch-b", update.state.epoch)
        assertTrue(update.rebaselineRequired)
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
