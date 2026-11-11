package build.raft.app.data

import build.raft.app.model.ActivityChanged
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.MessageMention
import build.raft.app.model.MessageNew
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Inbox refresh step 4 (`mobile_inbox_inplace_v0`, #triage 6db2bd32): a live message for a row the
 * Activity list shows patches it in place (unread and mention included) and reads nothing; a row it
 * lacks asks for one throttled read; a hidden list reads nothing until it shows (`visibility`);
 * patched rows are re-read at most once a minute (`inplace_reconcile`). Flag off, today's reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreInboxInPlaceTest {
    private data class Read(val atMillis: Long, val reason: InboxFetchReason?, val trigger: InboxRefreshTriggerCount?)

    private class RecordingInbox(private val inner: FakeInboxRepository) : InboxRepository by inner {
        var now: () -> Long = { 0L }
        val reads = mutableListOf<Read>()

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
            val context = currentCoroutineContext()[InboxFetchReasonContext]
            reads += Read(now(), context?.reason, context?.trigger)
            return inner.loadInitial(serverId, filter, limit)
        }
    }

    private class Fixture(val store: SharedActivityStore, val inbox: RecordingInbox)

    private fun TestScope.loadedStore(rows: List<InboxItem>, inPlace: Boolean): Fixture {
        val inbox = RecordingInbox(FakeInboxRepository(initialSnapshots = listOf(snapshot(rows))))
        inbox.now = { testScheduler.currentTime }
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                inboxInPlaceEnabled = { inPlace }
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(listOf<InboxFetchReason?>(InboxFetchReason.Initial), inbox.reads.map { it.reason })
        return Fixture(store, inbox)
    }

    private val channelRow = activityItem("channel-1").copy(latestActivitySeq = "10")
    private val otherRow = activityItem("channel-2").copy(latestActivitySeq = "5")
    private val threadRow = threadItem("thread-1").copy(replyCount = 2, latestActivitySeq = "20")

    private fun live(id: String, channelId: String, seq: Long, mentionsMe: Boolean = false) =
        SharedActivityIntent.ApplyRealtimeEvent(
            MessageNew(
                message(id, channelId = channelId, seq = seq).copy(
                    mentions = if (mentionsMe) listOf(MessageMention(type = "user", id = "user-1")) else emptyList()
                )
            )
        )

    private fun reply(id: String, seq: Long, replyCount: Int) =
        SharedActivityIntent.ApplyRealtimeEvent(
            ThreadUpdated(
                parentMessageId = MessageId("parent-message"),
                threadChannelId = ChannelId("thread-1"),
                replyCount = replyCount,
                lastReplyAt = "2026-11-11T10:00:00Z",
                participantIds = emptyList(),
                latestReply =
                    ReplyPreview(
                        messageId = id,
                        seq = seq,
                        content = "reply $id",
                        senderId = "user-2",
                        senderType = "user",
                        senderName = "user-2",
                        createdAt = "2026-11-11T10:00:00Z"
                    )
            )
        )

    @Test
    fun messageNewOnAShownChannelRowPatchesPreviewUnreadAndMentionInPlaceWithoutARead() = runTest {
        val f = loadedStore(listOf(otherRow, channelRow), inPlace = true)

        f.store.dispatch(live("m-11", channelId = "channel-1", seq = 11L, mentionsMe = true))
        advanceTimeBy(30_000L)
        runCurrent()

        val row = f.store.state.value.items.first()
        assertEquals(channelRow.key, row.key, "the patched row moves to the top")
        assertEquals("message m-11", row.lastMessagePreview)
        assertEquals(1 to MessageId("m-11"), row.unreadCount to row.firstUnreadMessageId)
        assertEquals(true to MessageId("m-11"), row.hasMention to row.firstMentionMessageId)
        assertEquals(1, f.store.state.value.totalUnreadCount)
        assertEquals(1, f.inbox.reads.size, "no read for a row the list shows")

        // The reader's own message and a repeat delivery move no unread and read nothing.
        f.store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("m-12", channelId = "channel-1", authorId = "user-1", seq = 12L)))
        )
        f.store.dispatch(live("m-11", channelId = "channel-1", seq = 11L))
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals("message m-12", f.store.state.value.items.first().lastMessagePreview)
        assertEquals(1, f.store.state.value.items.first().unreadCount)
        assertEquals(1, f.inbox.reads.size)
    }

    @Test
    fun threadUpdatedAndMessageNewOfTheSameReplyCountItOnceInPlace() = runTest {
        val f = loadedStore(listOf(otherRow, threadRow), inPlace = true)

        f.store.dispatch(reply("r-21", seq = 21L, replyCount = 3))
        // The same reply's `message:new` (the thread room) comes second: seq-guarded, no change.
        f.store.dispatch(live("r-21", channelId = "thread-1", seq = 21L))
        advanceTimeBy(30_000L)
        runCurrent()

        val row = f.store.state.value.items.first()
        assertEquals(threadRow.key, row.key)
        assertEquals(MessageId("r-21") to "21", row.latestActivityMessageId to row.latestActivitySeq)
        assertEquals(3, row.replyCount)
        assertEquals(1 to MessageId("r-21"), row.unreadCount to row.firstUnreadMessageId)
        assertEquals(1, f.store.state.value.totalUnreadCount)
        assertEquals(1, f.inbox.reads.size, "no read for a row the list shows")
    }

    @Test
    fun aRowTheListLacksAsksForOneCoalescedRead() = runTest {
        val f = loadedStore(listOf(channelRow), inPlace = true)

        f.store.dispatch(live("m-1", channelId = "channel-9", seq = 1L))
        f.store.dispatch(live("m-2", channelId = "channel-9", seq = 2L))
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 1L)
        runCurrent()

        assertEquals(2, f.inbox.reads.size)
        val read = f.inbox.reads.last()
        assertEquals(InboxFetchReason.RealtimeFallback, read.reason)
        assertEquals(InboxRefreshTriggerCount(InboxRefreshTrigger.MessageNew, coalesced = 2), read.trigger)
        assertEquals(ActivityRealtimeBackgroundRefreshIntervalMillis, read.atMillis, "the throttle's trailing edge")
    }

    @Test
    fun aHiddenListReadsNothingAndShowingItReadsOnceForVisibility() = runTest {
        val f = loadedStore(listOf(channelRow), inPlace = true)

        f.store.dispatch(SharedActivityIntent.SurfaceHidden)
        f.store.dispatch(live("m-1", channelId = "channel-9", seq = 1L))
        f.store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(ServerId("server-1"), "activity_changed")))
        advanceTimeBy(120_000L)
        runCurrent()
        assertEquals(1, f.inbox.reads.size, "nothing reads a hidden list")

        f.store.dispatch(SharedActivityIntent.Refresh)
        advanceTimeBy(120_000L)
        runCurrent()
        assertEquals(listOf<InboxFetchReason?>(InboxFetchReason.Initial, InboxFetchReason.Visibility), f.inbox.reads.map { it.reason })
    }

    @Test
    fun patchedRowsAreReconciledAtMostOncePerMinute() = runTest {
        val f = loadedStore(listOf(otherRow, channelRow), inPlace = true)
        val interval = ActivityInPlaceReconcileIntervalMillis

        // Patches every 10 s for three minutes.
        var seq = 11L
        while (testScheduler.currentTime < 3 * interval) {
            f.store.dispatch(live("m-$seq", channelId = "channel-1", seq = seq))
            seq += 1
            advanceTimeBy(10_000L)
            runCurrent()
        }
        advanceTimeBy(5 * interval)
        runCurrent()

        val reconciles = f.inbox.reads.filter { it.reason == InboxFetchReason.InPlaceReconcile }.map { it.atMillis }
        assertEquals(f.inbox.reads.size - 1, reconciles.size, "no other read than the reconcile")
        assertEquals(listOf(interval, 2 * interval, 3 * interval), reconciles, "one a minute while patches come, none after")

        // A read of another kind after the patch covers it.
        f.store.dispatch(live("m-$seq", channelId = "channel-1", seq = seq))
        advanceTimeBy(1_000L)
        f.store.dispatch(SharedActivityIntent.PullRefresh)
        advanceTimeBy(2 * interval)
        runCurrent()
        assertEquals(InboxFetchReason.Manual, f.inbox.reads.last().reason)
        assertEquals(reconciles.size, f.inbox.reads.count { it.reason == InboxFetchReason.InPlaceReconcile })
    }

    @Test
    fun flagOffKeepsTodaysReadsAndLeavesUnreadToTheServer() = runTest {
        val f = loadedStore(listOf(otherRow, channelRow), inPlace = false)

        f.store.dispatch(live("m-11", channelId = "channel-1", seq = 11L, mentionsMe = true))
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 1L)
        runCurrent()

        assertEquals(
            listOf<InboxFetchReason?>(InboxFetchReason.Initial, InboxFetchReason.RealtimeFallback),
            f.inbox.reads.map { it.reason },
            "a shown row still asks for the throttled read"
        )
        assertEquals(InboxRefreshTriggerCount(InboxRefreshTrigger.MessageNew), f.inbox.reads.last().trigger)
        val row = f.store.state.value.items.first { it.key == channelRow.key }
        assertEquals(0 to false, row.unreadCount to row.hasMention, "unread stays the server's")

        // Hidden (no hidden window configured): the read still runs.
        f.store.dispatch(SharedActivityIntent.SurfaceHidden)
        advanceTimeBy(10_000L)
        f.store.dispatch(live("m-1", channelId = "channel-9", seq = 1L))
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(3, f.inbox.reads.size)
        assertEquals(0, f.inbox.reads.count { it.reason == InboxFetchReason.InPlaceReconcile })
    }
}
