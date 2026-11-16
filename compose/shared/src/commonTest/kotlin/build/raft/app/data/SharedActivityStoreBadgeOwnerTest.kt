package build.raft.app.data

import build.raft.app.model.MessageNew
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
 * `inbox_row_position_v1` (#triage:#244, B3, Tenny's decision 1): a list a fresh answer loaded this
 * session is the badge's only writer, shown or hidden. Every return to the foreground re-reads it (the
 * summary read it suppresses was the only other read), live messages off the primary surface still
 * reach it (the hidden +1 path), and those +1s add onto the re-read total. Flag off: today's paths.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreBadgeOwnerTest {
    private val owner =
        ProcessActivityUnreadOwner(environmentId = "https://api.example.test", accountId = "user-1", serverId = "server-1")

    private class RecordingLists(private val lists: List<DataResult<InboxSnapshot>>) :
        InboxRepository by FakeInboxRepository(listOf(snapshot(emptyList()))) {
        val reasons = mutableListOf<InboxFetchReason?>()

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
            reasons += currentCoroutineContext()[InboxFetchReasonContext]?.reason
            return lists.getOrElse(reasons.size - 1) { lists.last() }
        }
    }

    private class Fixture(
        val activity: SharedActivityStore,
        val summary: ProcessActivityUnreadStore,
        val lists: RecordingLists,
        val summaryReads: MutableList<InboxFetchReason?>,
        val foreground: MutableStateFlow<Boolean>
    ) {
        val badge: Int get() = summary.currentProjection(ServerId("server-1")).totalUnreadCount
    }

    private fun TestScope.fixture(lists: List<DataResult<InboxSnapshot>>, rowPosition: Boolean = true): Fixture {
        val foreground = MutableStateFlow(true)
        val summaryReads = mutableListOf<InboxFetchReason?>()
        val summary =
            ProcessActivityUnreadStore(
                fetchSummary = { _, _ ->
                    summaryReads += currentCoroutineContext()[InboxFetchReasonContext]?.reason
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 9, totalCount = 9))
                },
                ownerResolver = { serverId -> owner.takeIf { it.serverId == serverId } },
                scope = backgroundScope,
                throttleMillis = 2_500L,
                nowMillis = { testScheduler.currentTime },
                appForeground = foreground
            )
        val inbox = RecordingLists(lists)
        val activity =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                appForeground = foreground,
                onSummaryFact = summary::submitFact,
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { rowPosition }
            )
        summary.followAppLifecycle()
        // As RaftSharedStoreProvider wires it (the flags are on for the server here).
        summary.deriveBadgeFromList(
            derives = { activity.state.value.derivesBadgeFor(it) },
            owns = { rowPosition && activity.ownsBadgeFor(it) }
        )
        activity.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        return Fixture(activity, summary, inbox, summaryReads, foreground)
    }

    private val row = activityItem("channel-1", unreadCount = 2).copy(latestActivitySeq = "10", readStateLatestActivitySeq = "10")

    private fun TestScope.returnToForeground(f: Fixture) {
        f.foreground.value = false
        runCurrent()
        f.foreground.value = true
        // The shell's own foreground summary read, as the app shell asks for it on the return.
        f.summary.onForeground(ServerId("server-1"), epoch = 1L, reason = "app_shell_resume")
        advanceTimeBy(30_000L)
        runCurrent()
    }

    private fun hiddenLive(id: String, seq: Long) =
        SharedActivityIntent.ApplyRealtimeSummaryEvent(
            MessageNew(message(id, channelId = "channel-1", seq = seq)),
            activeConversationId = null,
            primarySurfaceVisible = false
        )

    @Test
    fun aHiddenListReadsItselfOnTheReturnAndItsPlusOnesAddOntoThatTotal() = runTest {
        val f =
            fixture(
                listOf(
                    DataResult.Content(snapshot(listOf(row))),
                    DataResult.Content(snapshot(listOf(row.copy(unreadCount = 5, latestActivitySeq = "13", readStateLatestActivitySeq = "13"))))
                )
            )
        f.activity.dispatch(SharedActivityIntent.SurfaceHidden)
        runCurrent()
        assertEquals(true, f.activity.ownsBadgeFor(owner), "hidden, it still owns the badge")

        returnToForeground(f)
        assertEquals(listOf<InboxFetchReason?>(InboxFetchReason.Initial, InboxFetchReason.Visibility), f.lists.reasons, "the list re-read itself")
        assertEquals(emptyList<InboxFetchReason?>(), f.summaryReads, "the summary read wrote nothing (it was not made)")
        assertEquals(5, f.badge, "the re-read list's total")

        // A live message while hidden, off the primary surface: +1 on the fresh total.
        f.activity.dispatch(hiddenLive("m-14", seq = 14L))
        runCurrent()
        assertEquals(6, f.activity.state.value.totalUnreadCount)
        assertEquals(6, f.badge)
    }

    @Test
    fun aListWithoutAFreshAnswerDoesNotOwnTheBadge() = runTest {
        // A remembered (`X-Raft-Stale`) first page is shown but is not this session's answer.
        val stale = snapshot(listOf(row)).copy(staleReason = RaftStaleReason.entries.first())
        val f = fixture(listOf(DataResult.Content(stale), DataResult.Content(snapshot(listOf(row)))))
        assertEquals(false, f.activity.ownsBadgeFor(owner))
        f.activity.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(true, f.activity.ownsBadgeFor(owner))
    }

    @Test
    fun theFlagOffKeepsTodaysReturnAndDropsLiveMessagesOffThePrimarySurface() = runTest {
        val f = fixture(listOf(DataResult.Content(snapshot(listOf(row)))), rowPosition = false)
        f.activity.dispatch(SharedActivityIntent.SurfaceHidden)
        runCurrent()

        returnToForeground(f)
        assertEquals(listOf<InboxFetchReason?>(InboxFetchReason.Initial), f.lists.reasons, "nothing owed: the hidden list reads nothing")
        assertEquals(listOf<InboxFetchReason?>(InboxFetchReason.Visibility), f.summaryReads, "the summary read counts")

        f.activity.dispatch(hiddenLive("m-11", seq = 11L))
        runCurrent()
        assertEquals(2, f.activity.state.value.totalUnreadCount, "off the primary surface: dropped, as before")
    }
}
