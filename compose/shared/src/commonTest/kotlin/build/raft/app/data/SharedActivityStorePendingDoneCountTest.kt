package build.raft.app.data

import build.raft.app.ActivityPendingSwipeDone
import build.raft.app.ActivitySwipeDeferredAction
import build.raft.app.ActivitySwipeDoneTransition
import build.raft.app.ActivitySwipeDoneUndoState
import build.raft.app.activitySwipeDeferredCommitIntent
import build.raft.app.projectActivityStateWithProcessSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * #proj-mobile:#229: non-object Mark as Done (and thread Unfollow) waits out an 8 s Undo window
 * before dispatching MarkDone / UnfollowThread, while hiding the row immediately. Projecting that
 * pending hide at [SharedActivityStore] drops header counts and the process unread summary at once,
 * restores them on Undo or newer activity, and transitions cleanly with no double-decrement when
 * the action commits or a server refresh lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStorePendingDoneCountTest {

    private val channelA = activityItem("channel-a", unreadCount = 2)
    private val channelB = activityItem("channel-b", unreadCount = 1)
    private val threadC = threadItem("thread-c", unreadCount = 2).copy(isFollowing = true)

    private class Panel(val store: SharedActivityStore) {
        var undo = ActivitySwipeDoneUndoState()

        fun done(item: InboxItem) = apply(undo.swipe(item))

        fun unfollow(item: InboxItem) = apply(undo.swipe(item, ActivitySwipeDeferredAction.Unfollow, "user-1", "server-1"))

        fun undoToast() {
            undo = undo.undo(undo.pending!!.token)
            syncPending()
        }

        fun toastEnds() = apply(undo.expire(undo.pending!!.token))

        private fun apply(transition: ActivitySwipeDoneTransition) {
            undo = transition.state
            transition.commit?.let(::commit)
            syncPending()
        }

        private fun commit(pending: ActivityPendingSwipeDone) {
            val current = store.state.value.items.firstOrNull { it.key == pending.item.key }
            val intent = activitySwipeDeferredCommitIntent(pending, current) ?: return
            store.dispatch(intent)
        }

        private fun syncPending() {
            val pending = undo.pending
            val filter = store.state.value.selectedFilter
            store.dispatch(
                SharedActivityIntent.ProjectPendingDeferredHide(
                    pending?.let {
                        SharedActivityPendingDeferredHide(
                            itemKey = it.item.key,
                            removesCountRow =
                                it.action == ActivitySwipeDeferredAction.Done ||
                                    !filter.listsUnfollowedActivityThreads,
                        )
                    }
                )
            )
        }
    }

    @Test
    fun deferredMarkDoneDropsHeaderAndSummaryCountsImmediatelyRestoresOnUndoAndNeverDoubleDecrementsOnCommit() = runTest {
        val repo =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(
                            items = listOf(channelA, channelB),
                            totalCount = 12,
                            totalUnreadCount = 3,
                        ),
                        snapshot(
                            items = listOf(channelB),
                            totalCount = 11,
                            totalUnreadCount = 1,
                        ),
                    )
            )
        val summaryFacts = mutableListOf<ActivityUnreadSummaryFact>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = repo,
                onSummaryFact = { summaryFacts += it },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(12 to 3, store.state.value.activityHeaderCounts())
        assertEquals(12, summaryFacts.last().totalCount)
        assertEquals(3, summaryFacts.last().totalUnreadCount)

        val panel = Panel(store)
        panel.done(channelA)
        runCurrent()
        // No server write yet during the 8 s Undo window, but both header counts and process badge drop at once.
        assertEquals(emptyList(), repo.doneChannelIds)
        assertEquals(11 to 1, store.state.value.activityHeaderCounts())
        assertEquals(11, summaryFacts.last().totalCount)
        assertEquals(1, summaryFacts.last().totalUnreadCount)
        assertFalse(summaryFacts.last().authoritative)

        // Overlay with ProcessActivityUnreadStore does not revert or double-subtract while pending.
        val staleProcessSummary =
            ActivityUnreadSummaryProjection(
                serverId = "server-1",
                totalCount = 12,
                totalUnreadCount = 3,
                hasValue = true,
                source = "background_poll",
            )
        assertEquals(
            11 to 1,
            projectActivityStateWithProcessSummary(store.state.value, staleProcessSummary).activityHeaderCounts(),
        )
        val updatedProcessSummary =
            ActivityUnreadSummaryProjection(
                serverId = "server-1",
                totalCount = 11,
                totalUnreadCount = 1,
                hasValue = true,
                source = "activity:project_pending_deferred_hide",
            )
        assertEquals(
            11 to 1,
            projectActivityStateWithProcessSummary(store.state.value, updatedProcessSummary).activityHeaderCounts(),
        )

        // Undo restores the exact counts immediately.
        panel.undoToast()
        runCurrent()
        assertEquals(emptyList(), repo.doneChannelIds)
        assertEquals(12 to 3, store.state.value.activityHeaderCounts())
        assertEquals(12, summaryFacts.last().totalCount)
        assertEquals(3, summaryFacts.last().totalUnreadCount)

        // Done again and let the 8 s Undo window commit: stays at 11 / 1 with no double-decrement.
        panel.done(channelA)
        runCurrent()
        assertEquals(11 to 1, store.state.value.activityHeaderCounts())
        panel.toastEnds()
        runCurrent()
        assertEquals(listOf(channelA.channelId), repo.doneChannelIds)
        assertEquals(null, store.state.value.pendingDeferredHide)
        assertEquals(11 to 1, store.state.value.activityHeaderCounts())
        assertEquals(11, summaryFacts.last().totalCount)
        assertEquals(1, summaryFacts.last().totalUnreadCount)
    }

    @Test
    fun secondMarkDoneCommitsFirstAndProjectsSecondWithoutDoubleDecrement() = runTest {
        val repo =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(
                            items = listOf(channelA, channelB),
                            totalCount = 5,
                            totalUnreadCount = 3,
                        ),
                        snapshot(
                            items = listOf(channelB),
                            totalCount = 4,
                            totalUnreadCount = 1,
                        ),
                    )
            )
        val store = activityStore(backgroundScope, inboxRepository = repo)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val panel = Panel(store)

        panel.done(channelA)
        runCurrent()
        assertEquals(4 to 1, store.state.value.activityHeaderCounts())

        panel.done(channelB)
        runCurrent()
        // channelA committed (5->4, 3->1) and channelB is now pending (4->3, 1->0).
        assertEquals(listOf(channelA.channelId), repo.doneChannelIds)
        assertEquals(3 to 0, store.state.value.activityHeaderCounts())

        panel.undoToast()
        runCurrent()
        assertEquals(4 to 1, store.state.value.activityHeaderCounts())
    }

    @Test
    fun serverRefreshDuringUndoWindowKeepsPendingDeductionAndAvoidsDoubleSubtractIfRowGone() = runTest {
        val stillPresent = snapshot(items = listOf(channelA, channelB), totalCount = 10, totalUnreadCount = 3)
        val alreadyGone = snapshot(items = listOf(channelB), totalCount = 9, totalUnreadCount = 1)
        val repo = FakeInboxRepository(initialSnapshots = listOf(stillPresent, stillPresent, alreadyGone))
        val summaryFacts = mutableListOf<ActivityUnreadSummaryFact>()
        val store = activityStore(backgroundScope, inboxRepository = repo, onSummaryFact = { summaryFacts += it })
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val panel = Panel(store)

        panel.done(channelA)
        runCurrent()
        assertEquals(9 to 1, store.state.value.activityHeaderCounts())

        // Background refresh returns the row still present: pending deduction holds and is not marked authoritative.
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(9 to 1, store.state.value.activityHeaderCounts())
        assertEquals(9, summaryFacts.last().totalCount)
        assertEquals(1, summaryFacts.last().totalUnreadCount)
        assertFalse(summaryFacts.last().authoritative)

        // Another refresh returns channelA already removed (e.g. marked done on another device): no double-subtract.
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(9 to 1, store.state.value.activityHeaderCounts())
        assertEquals(9, summaryFacts.last().totalCount)
        assertEquals(1, summaryFacts.last().totalUnreadCount)
    }

    @Test
    fun deferredUnfollowProjectsUnreadOnAllAndBothCountsOnUnreadFilter() = runTest {
        val repo =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(items = listOf(threadC, channelB), totalCount = 4, totalUnreadCount = 3),
                        snapshot(items = listOf(threadC, channelB), totalCount = 2, totalUnreadCount = 3),
                    )
            )
        val store = activityStore(backgroundScope, inboxRepository = repo)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val panel = Panel(store)

        // On All, unfollowed threads remain in totalCount while losing unread.
        panel.unfollow(threadC)
        runCurrent()
        assertEquals(4 to 1, store.state.value.activityHeaderCounts())
        panel.undoToast()
        runCurrent()
        assertEquals(4 to 3, store.state.value.activityHeaderCounts())

        // Switching filter clears pendingDeferredHide; on Unread, Unfollow drops both totalCount and totalUnreadCount.
        panel.unfollow(threadC)
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        runCurrent()
        assertEquals(null, store.state.value.pendingDeferredHide)
        assertEquals(2 to 3, store.state.value.activityHeaderCounts())

        panel.undo = ActivitySwipeDoneUndoState()
        panel.unfollow(threadC)
        runCurrent()
        assertEquals(1 to 1, store.state.value.activityHeaderCounts())
        panel.undoToast()
        runCurrent()
        assertEquals(2 to 3, store.state.value.activityHeaderCounts())
    }
}
