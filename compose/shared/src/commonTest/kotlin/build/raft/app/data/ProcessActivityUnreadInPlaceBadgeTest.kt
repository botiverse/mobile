package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Inbox refresh step 4, rule 6 (`mobile_inbox_inplace_v0`): with an Activity All list loaded, the
 * badge is the count that list publishes; the summary reads it answers (realtime, the person's read,
 * a first load, a sync change) are not made. Without it (flag off), every read is today's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessActivityUnreadInPlaceBadgeTest {
    private val owner =
        ProcessActivityUnreadOwner(environmentId = "https://api.example.test", accountId = "user-1", serverId = "server-1")

    @Test
    fun theBadgeFollowsTheLoadedListWithoutTheSummaryRequestOnlyWhenTheFlagIsOn() = runTest {
        listOf(true, false).forEach { inPlace ->
            val reads = mutableListOf<InboxFetchReason?>()
            val foreground = MutableStateFlow(true)
            val store =
                ProcessActivityUnreadStore(
                    fetchSummary = { _, _ ->
                        reads += currentCoroutineContext()[InboxFetchReasonContext]?.reason
                        DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 9, totalCount = 9))
                    },
                    ownerResolver = { serverId -> owner.takeIf { it.serverId == serverId } },
                    scope = backgroundScope,
                    throttleMillis = 2_500L,
                    nowMillis = { testScheduler.currentTime },
                    appForeground = foreground
                )
            store.followAppLifecycle()
            store.deriveBadgeFromList { candidate -> inPlace && candidate == owner }

            // What the loaded list publishes: its first load, then an in-place patch.
            store.submitFact(ActivityUnreadSummaryFact("user-1", "server-1", 2, 4, authoritative = true, source = "activity:load_initial"))
            store.submitFact(ActivityUnreadSummaryFact("user-1", "server-1", 3, 4, authoritative = false, source = "activity:realtime"))
            store.ensure(ServerId("server-1"), reason = "app_shell_server_ready")
            store.invalidate(owner, reason = "realtime:MessageNew", eventMarker = Any())
            store.invalidate(owner, reason = "read_result:1")
            store.invalidate(owner, reason = "conversation_read_all")
            advanceTimeBy(30_000L)
            runCurrent()

            if (inPlace) {
                assertEquals(emptyList<InboxFetchReason?>(), reads, "no summary request while the list counts the badge")
                assertEquals(3, store.currentProjection(ServerId("server-1")).totalUnreadCount, "the list's count")
            } else {
                assertEquals(
                    listOf<InboxFetchReason?>(InboxFetchReason.Initial, InboxFetchReason.Manual),
                    reads,
                    "today: the server-ready read, then one trailing read for the asks folded into it"
                )
            }

            // The return to the foreground still reads once (the list may be hidden then).
            val before = reads.size
            foreground.value = false
            runCurrent()
            foreground.value = true
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals(listOf<InboxFetchReason?>(InboxFetchReason.Visibility), reads.drop(before), "inPlace=$inPlace")
        }
    }
    /**
     * `inbox_row_position_v1`: while the loaded list is shown it is the badge's only writer. No
     * summary read for any reason (the return to the foreground and an explicit refresh included),
     * so the badge never jumps between the list's count and an older summary answer.
     */
    @Test
    fun theShownListIsTheBadgesOnlyWriter() = runTest {
        val reads = mutableListOf<InboxFetchReason?>()
        val foreground = MutableStateFlow(true)
        val store =
            ProcessActivityUnreadStore(
                fetchSummary = { _, _ ->
                    reads += currentCoroutineContext()[InboxFetchReasonContext]?.reason
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 9, totalCount = 9))
                },
                ownerResolver = { serverId -> owner.takeIf { it.serverId == serverId } },
                scope = backgroundScope,
                throttleMillis = 2_500L,
                nowMillis = { testScheduler.currentTime },
                appForeground = foreground
            )
        store.followAppLifecycle()
        store.deriveBadgeFromList(derives = { it == owner }, owns = { it == owner })

        store.submitFact(ActivityUnreadSummaryFact("user-1", "server-1", 2, 4, authoritative = true, source = "activity:load_initial"))
        store.ensure(ServerId("server-1"), reason = "app_shell_server_ready")
        store.refresh(ServerId("server-1"))
        store.onForeground(ServerId("server-1"), epoch = 1L, reason = "app_shell_resume")
        foreground.value = false
        runCurrent()
        foreground.value = true
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(emptyList<InboxFetchReason?>(), reads)
        assertEquals(2, store.currentProjection(ServerId("server-1")).totalUnreadCount, "the list's count")
    }

    @Test
    fun aSummaryReadThatLandsAfterTheListTookTheBadgeWritesNothing() = runTest {
        val answer = CompletableDeferred<DataResult<ActivityUnreadSummarySnapshot>>()
        var owns = false
        val store =
            ProcessActivityUnreadStore(
                fetchSummary = { _, _ -> answer.await() },
                ownerResolver = { serverId -> owner.takeIf { it.serverId == serverId } },
                scope = backgroundScope,
                throttleMillis = 2_500L,
                nowMillis = { testScheduler.currentTime },
                appForeground = MutableStateFlow(true)
            )
        store.deriveBadgeFromList(derives = { owns && it == owner }, owns = { owns && it == owner })
        store.refresh(ServerId("server-1"))
        runCurrent()

        // The list loads and is shown while the read is out.
        owns = true
        store.submitFact(ActivityUnreadSummaryFact("user-1", "server-1", 2, 4, authoritative = true, source = "activity:load_initial"))
        runCurrent()
        answer.complete(DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 9, totalCount = 9)))
        advanceTimeBy(30_000L)
        runCurrent()

        val projection = store.currentProjection(ServerId("server-1"))
        assertEquals(2, projection.totalUnreadCount, "the list's count stands")
        assertEquals(ActivityUnreadSummaryFreshness.Fresh, projection.freshness)
    }
}
