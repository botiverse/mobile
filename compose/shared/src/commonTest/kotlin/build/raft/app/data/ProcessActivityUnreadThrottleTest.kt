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
