package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ThreadUpdated
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * artin, Android 1.23.0: under Today, three #proj-mobile threads (25 min, 28 min, 1 h) sat above
 * "just now" rows. Rows held from realtime went above every served row, and a thread row whose
 * `thread:updated` came before the `message:new` of the same reply counted it twice, so the
 * server's row (one reply fewer) never released the hold.
 */
class SharedActivityStoreHeldRowOrderTest {

    private fun thread(name: String, replyCount: Int, seq: Long, at: String): InboxItem =
        threadItem("thread-$name").copy(
            parentMessageId = MessageId("parent-$name"),
            replyCount = replyCount,
            latestActivityMessageId = MessageId("$name-$replyCount"),
            latestActivityPreview = "$name reply $replyCount",
            latestActivitySeq = seq.toString(),
            latestActivityAt = at
        )

    @Test
    fun aThreadRowCountedTwiceByRealtimeGoesUnderNewerActivityOnceTheServerCaughtUp() = runTest {
        val lagoon = thread("lagoon", replyCount = 5, seq = 100, at = "2026-11-11T03:00:00Z")
        val lagoonServed = thread("lagoon", replyCount = 6, seq = 200, at = "2026-11-11T03:23:00Z")
        val deck = thread("deck", replyCount = 2, seq = 90, at = "2026-11-11T02:00:00Z")
        val deckServed = thread("deck", replyCount = 3, seq = 300, at = "2026-11-11T03:48:00Z")
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(lagoon, deck)),
                        snapshot(listOf(lagoonServed, deck)),
                        snapshot(listOf(deckServed, lagoonServed))
                    )
            )
        val store = activityStore(backgroundScope, inbox, nowMillis = { testScheduler.currentTime })
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        // The reply's `thread:updated` arrives before its `message:new`.
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("parent-lagoon"),
                    threadChannelId = ChannelId("thread-lagoon"),
                    replyCount = 6,
                    lastReplyAt = "2026-11-11T03:23:00Z",
                    participantIds = emptyList()
                )
            )
        )
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        "lagoon-6",
                        channelId = "thread-lagoon",
                        body = "lagoon reply 6",
                        timestamp = "2026-11-11T03:23:00Z",
                        seq = 200L
                    )
                )
            )
        )
        runCurrent()
        assertEquals(listOf(lagoon.key, deck.key), store.state.value.items.map(InboxItem::key))

        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(6, store.state.value.items.first().replyCount, "the server's count replaces the twice-counted one")

        // Newer activity in another thread, served by the next read.
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()

        assertEquals(
            listOf(deck.key, lagoon.key),
            store.state.value.items.map(InboxItem::key),
            "the newer row is on top"
        )
    }

    @Test
    fun aHeldRowGoesAboveOnlyTheServedRowsOlderThanIt() {
        val newest = thread("newest", replyCount = 1, seq = 300, at = "2026-11-11T03:48:00Z")
        val held = thread("held", replyCount = 1, seq = 200, at = "2026-11-11T03:23:00Z")
        val older = thread("older", replyCount = 1, seq = 100, at = "2026-11-11T02:48:00Z")
        val noTime = threadItem("thread-no-time")

        assertEquals(
            listOf(newest, held, older, noTime).map(InboxItem::key),
            activityRowsWithHeldRowsAtTheirTime(served = listOf(newest, older, noTime), held = listOf(held))
                .map(InboxItem::key),
            "under the newer row, above the older one"
        )
        assertEquals(
            listOf(newest, held, noTime).map(InboxItem::key),
            activityRowsWithHeldRowsAtTheirTime(served = listOf(noTime), held = listOf(held, newest))
                .map(InboxItem::key),
            "held rows in time order, above a row with no time"
        )
        assertEquals(
            listOf(noTime, newest, older).map(InboxItem::key),
            activityRowsWithHeldRowsAtTheirTime(served = listOf(newest, older), held = listOf(noTime))
                .map(InboxItem::key),
            "a held row with no time stays on top"
        )
        assertEquals(
            listOf(newest, held, older).map(InboxItem::key),
            activityRowsWithHeldRowsAtTheirTime(served = listOf(newest, held), held = listOf(older))
                .map(InboxItem::key),
            "an older held row goes last"
        )
    }
}
