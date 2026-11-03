package build.raft.app.data

import build.raft.app.model.ActivityChanged
import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Another device's Done, from incremental sync: web `inboxStore.applyRemoteDone`
 * (botiverse/slock bab9dd8c0, packages/web/src/store/inboxStore.ts:2592) — the row leaves when
 * `done.doneFrontierSeq` covers its `latestActivitySeq` (exact decimal compare, equal = covered),
 * stays when it shows newer activity, and a list that can't tell reads again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreRemoteDoneTest {

    private class Fixture(val store: SharedActivityStore, val inbox: FakeInboxRepository, val facts: List<ActivityUnreadSummaryFact>)

    private fun TestScope.fixture(
        vararg snapshots: InboxSnapshot,
        appForeground: MutableStateFlow<Boolean> = MutableStateFlow(true)
    ): Fixture {
        val inbox = FakeInboxRepository(initialSnapshots = snapshots.toList())
        val facts = mutableListOf<ActivityUnreadSummaryFact>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                onSummaryFact = { facts += it },
                realtimeBackgroundRefreshIntervalMillis = 2_500L,
                nowMillis = { testScheduler.currentTime },
                appForeground = appForeground
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        return Fixture(store, inbox, facts)
    }

    private fun row(channelId: String, latestActivitySeq: String?, unreadCount: Int = 0): InboxItem =
        activityItem(channelId, unreadCount).copy(latestActivitySeq = latestActivitySeq, readStateLatestActivitySeq = latestActivitySeq)

    @Test
    fun aDoneThatCoversTheRowRemovesItWithoutARequestAndALaggingListKeepsItOut() = runTest {
        // (row latestActivitySeq, done.doneFrontierSeq): equal counts as covered (web `> 0` keeps);
        // "99" vs "100" is wrong if compared as text; the last pair is past Long and 2^53.
        val covered =
            listOf(
                "41" to "41",
                "41" to "42",
                "99" to "100",
                "92233720368547758069" to "92233720368547758070",
            )
        for ((latest, frontier) in covered) {
            val done = row("channel-done", latest, unreadCount = 2)
            val other = row("channel-other", "7", unreadCount = 1)
            // The second read is a lagging list that still serves the Done row.
            val f = fixture(snapshot(listOf(done, other)), snapshot(listOf(done, other)))

            assertEquals(ActivityRemoteDoneOutcome.Removed, f.store.applyRemoteDone("channel-done", frontier), "$latest vs $frontier")
            assertEquals(listOf("channel:channel-other"), f.store.state.value.items.map { it.key }, "$latest vs $frontier")
            assertEquals(1, f.store.state.value.totalUnreadCount)
            assertEquals(1, f.store.state.value.totalCount)
            assertEquals(1, f.facts.last().totalUnreadCount, "the Activity badge moves with the row, as for a local Done")
            runCurrent()
            assertEquals(1, f.inbox.initialLoadCount, "no request for a covered Done ($latest vs $frontier)")

            f.store.dispatch(SharedActivityIntent.Refresh)
            runCurrent()
            assertEquals(2, f.inbox.initialLoadCount)
            assertEquals(listOf("channel:channel-other"), f.store.state.value.items.map { it.key }, "held like a local Done")
        }
    }

    @Test
    fun newerActivityKeepsTheRowAsShownAndAsksForNothing() = runTest {
        val newer = listOf("42" to "41", "100" to "99", "92233720368547758070" to "92233720368547758069")
        for ((latest, frontier) in newer) {
            val kept = row("channel-1", latest, unreadCount = 3)
            val f = fixture(snapshot(listOf(kept)))

            assertEquals(ActivityRemoteDoneOutcome.KeptNewerActivity, f.store.applyRemoteDone("channel-1", frontier), "$latest vs $frontier")
            advanceTimeBy(10_000L)
            runCurrent()

            assertEquals(listOf(kept), f.store.state.value.items)
            assertEquals(3, f.store.state.value.totalUnreadCount)
            assertEquals(1, f.inbox.initialLoadCount)
        }
    }

    @Test
    fun aListThatCannotTellReadsAgainOnceThroughTheSingleFlight() = runTest {
        // (rows, channel, frontier): no such row, no frontier, a thread row (its Done comes as
        // `follow`), a row without latestActivitySeq.
        val cases =
            listOf(
                Triple(listOf(row("channel-1", "5")), "channel-unknown", "9"),
                Triple(listOf(row("channel-1", "5")), "channel-1", null),
                Triple(listOf(threadItem("thread-1").copy(latestActivitySeq = "5")), "thread-1", "9"),
                Triple(listOf(row("channel-1", null)), "channel-1", "9"),
            )
        for ((rows, channelId, frontier) in cases) {
            val f = fixture(snapshot(rows))
            assertEquals(ActivityRemoteDoneOutcome.CannotTell, f.store.applyRemoteDone(channelId, frontier), "$channelId/$frontier")
            assertEquals(rows, f.store.state.value.items)

            // Dispatched (or asked twice in one window): one list read.
            f.store.dispatch(SharedActivityIntent.ApplyRemoteDone(channelId, frontier))
            f.store.dispatch(SharedActivityIntent.ApplyRemoteDone(channelId, frontier))
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(2, f.inbox.initialLoadCount, "exactly one refetch for $channelId/$frontier")
        }
    }

    @Test
    fun activityChangedForThisServerReadsTheListAgainOnce() = runTest {
        // The router's refetch (and `activity:changed`) for the store's server; another server's is not ours.
        val f = fixture(snapshot(listOf(row("channel-1", "5"))))
        f.store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(ServerId("server-other"), "sync_changes")))
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, f.inbox.initialLoadCount)

        repeat(2) {
            f.store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(ServerId("server-1"), "sync_changes")))
        }
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(2, f.inbox.initialLoadCount)
    }

    @Test
    fun inTheBackgroundNothingReadsAndTheReturnReadsOnce() = runTest {
        val foreground = MutableStateFlow(true)
        val f = fixture(snapshot(listOf(row("channel-1", "5"))), appForeground = foreground)
        val changed = SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(ServerId("server-1"), "sync_changes"))

        // A reconcile already waiting when the app leaves reads nothing; nor do events after it.
        f.store.dispatch(changed)
        foreground.value = false
        runCurrent()
        repeat(3) {
            f.store.dispatch(changed)
            f.store.dispatch(SharedActivityIntent.ApplyRemoteDone("channel-unknown", "9"))
            advanceTimeBy(60_000L)
            runCurrent()
        }
        assertEquals(1, f.inbox.initialLoadCount, "no Activity read while in the background")

        // Back: the sync pull's refetch right after folds into the one owed read.
        foreground.value = true
        runCurrent()
        f.store.dispatch(changed)
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(2, f.inbox.initialLoadCount, "exactly one read for the return")
    }

    @Test
    fun theResumeRefreshOfAVisibleActivityAnswersTheOwedRead() = runTest {
        val foreground = MutableStateFlow(true)
        val f = fixture(snapshot(listOf(row("channel-1", "5"))), appForeground = foreground)
        foreground.value = false
        runCurrent()
        f.store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(ServerId("server-1"), "sync_changes")))
        advanceTimeBy(60_000L)
        runCurrent()

        foreground.value = true
        runCurrent()
        // The visible page refreshes on resume, then the sync pull's refetch arrives during that read.
        f.store.dispatch(SharedActivityIntent.Refresh)
        f.store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(ServerId("server-1"), "sync_changes")))
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(2, f.inbox.initialLoadCount, "the resume refresh is the one read")
    }
}
