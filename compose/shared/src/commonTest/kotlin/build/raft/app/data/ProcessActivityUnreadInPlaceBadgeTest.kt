package build.raft.app.data

import build.raft.app.model.ServerId
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
}
