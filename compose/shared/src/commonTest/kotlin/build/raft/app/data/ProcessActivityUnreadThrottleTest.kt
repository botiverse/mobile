package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Task 228: the badge's `channels/inbox?filter=all&limit=1` read follows the Activity list's rules
 * (2.5 s leading + trailing throttle, nothing in the background, one visibility read on return), and
 * a mark-read costs one summary read. Virtual time throughout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessActivityUnreadThrottleTest {
    private val owner =
        ProcessActivityUnreadOwner(
            environmentId = "https://api.example.test",
            accountId = "account-1",
            serverId = "server-1"
        )

    private class Fixture(val store: ProcessActivityUnreadStore, val reads: MutableList<Pair<Long, InboxFetchReason?>>)

    private fun TestScope.fixture(foreground: MutableStateFlow<Boolean> = MutableStateFlow(true)): Fixture {
        val reads = mutableListOf<Pair<Long, InboxFetchReason?>>()
        val store =
            ProcessActivityUnreadStore(
                fetchSummary = { _, _ ->
                    reads += testScheduler.currentTime to currentCoroutineContext()[InboxFetchReasonContext]?.reason
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 3, totalCount = 5))
                },
                ownerResolver = { serverId -> owner.takeIf { it.serverId == serverId } },
                scope = backgroundScope,
                throttleMillis = 2_500L,
                nowMillis = { testScheduler.currentTime },
                appForeground = foreground
            )
        store.followAppLifecycle()
        return Fixture(store, reads)
    }

    private fun Fixture.realtime(event: String = "MessageNew") =
        store.invalidate(owner, reason = "realtime:$event", eventMarker = Any())

    @Test
    fun theFirstReadRunsAtOnceAndReadsInsideTheWindowCollapseIntoOneTrailingRead() = runTest {
        val f = fixture()

        f.realtime()
        runCurrent()
        assertEquals(listOf(0L), f.reads.map { it.first }, "leading read: no wait after a quiet window")

        advanceTimeBy(1_000L)
        repeat(6) { f.realtime(); advanceTimeBy(100L) }
        runCurrent()
        assertEquals(1, f.reads.size, "nothing inside the window")

        advanceTimeBy(1_899L) // t = 3 499: the window ended at 2 500
        runCurrent()
        assertEquals(listOf(0L, 2_500L), f.reads.map { it.first }, "ONE trailing read at the window's end")

        advanceTimeBy(20_000L)
        assertEquals(2, f.reads.size, "no further reads without a new request")

        // A quiet window later the next request is a leading read again.
        f.realtime()
        runCurrent()
        assertEquals(3, f.reads.size)
        assertEquals(testSchedulerNow(), f.reads.last().first)
    }

    private fun TestScope.testSchedulerNow(): Long = testScheduler.currentTime

    @Test
    fun aBurstOfTwentyEventsReadsAtMostOncePerWindow() = runTest {
        val f = fixture()
        repeat(20) {
            f.realtime()
            advanceTimeBy(500L)
        }
        advanceTimeBy(5_000L)
        runCurrent()
        // 10 s of events: leading + one trailing per 2.5 s window, never one per event.
        assertEquals(true, f.reads.size <= 5, "reads=${f.reads.map { it.first }}")
        f.reads.zipWithNext().forEach { (a, b) -> assertEquals(true, b.first - a.first >= 2_500L, "reads=${f.reads.map { it.first }}") }
    }

    @Test
    fun aMarkReadCostsOneSummaryRead() = runTest {
        // The prediction (optimistic, local) no longer invalidates the store; only its result does.
        val prediction =
            ReadReceiptPrediction(
                owner = ReadReceiptOwner("production", "user-1", "server-1"),
                channelId = build.raft.app.model.ChannelId("channel-1"),
                revision = 1L,
                target = ReadReceiptTarget.All
            )
        assertFalse(
            SharedActivityIntent.ApplyReadReceiptPrediction(prediction).invalidatesGlobalActivitySummary(),
            "the prediction adjusts the count locally and asks for no summary read"
        )
        val f = fixture()
        f.store.invalidate(owner, reason = "read_result:1")
        runCurrent()
        advanceTimeBy(60_000L)
        assertEquals(1, f.reads.size)
        assertEquals(InboxFetchReason.Manual, f.reads.single().second)
    }

    @Test
    fun inTheBackgroundNothingReadsEvenWhenAChainIsQueued() = runTest {
        val foreground = MutableStateFlow(true)
        val f = fixture(foreground)
        f.realtime()
        runCurrent()
        advanceTimeBy(1_000L)
        f.realtime() // queued for the window's end (t = 2 500)
        runCurrent()

        foreground.value = false // the 17:30:31 case: the app leaves with a read already queued
        runCurrent()
        repeat(5) {
            f.realtime()
            f.store.ensure(ServerId("server-1"), reason = "thread_route_attach")
            advanceTimeBy(10_000L)
            runCurrent()
        }
        assertEquals(1, f.reads.size, "no read at all while in the background: ${f.reads}")
    }

    @Test
    fun theReturnReadsOnceWithTheVisibilityReason() = runTest {
        val foreground = MutableStateFlow(true)
        val f = fixture(foreground)
        f.realtime()
        runCurrent() // registers the owner: one read at t = 0
        foreground.value = false
        runCurrent()
        repeat(3) { f.realtime(); advanceTimeBy(5_000L) }
        assertEquals(1, f.reads.size)

        foreground.value = true
        runCurrent()
        // The shell's own resume read arrives in the same moment: it is the same read.
        f.store.onForeground(ServerId("server-1"), epoch = 1L, reason = "app_shell_resume")
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(2, f.reads.size, "exactly one read for the return: ${f.reads}")
        assertEquals(InboxFetchReason.Visibility, f.reads.last().second)
    }
}
