package build.raft.app.data

import build.raft.app.ActivityPendingSwipeDone
import build.raft.app.ActivitySwipeDeferredAction
import build.raft.app.ActivitySwipeDoneDecision
import build.raft.app.ActivitySwipeDoneTransition
import build.raft.app.ActivitySwipeDoneUndoState
import build.raft.app.activitySwipeDeferredCommitIntent
import build.raft.app.evaluateActivitySwipeDone
import build.raft.app.model.MessageId
import build.raft.app.projectActivityStateWithProcessSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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

    private class Panel(
        val store: SharedActivityStore,
        val nowMillis: () -> Long = { 0L },
    ) {
        var undo = ActivitySwipeDoneUndoState()

        fun done(item: InboxItem) =
            apply(undo.swipe(item, clickedAtMillis = nowMillis()), ActivityDoneCommitTrigger.NextRow)

        fun unfollow(item: InboxItem) =
            apply(
                undo.swipe(item, ActivitySwipeDeferredAction.Unfollow, "user-1", "server-1"),
                ActivityDoneCommitTrigger.NextRow,
            )

        fun undoToast() {
            val pending = undo.pending!!
            if (pending.action == ActivitySwipeDeferredAction.Done) {
                pending.item.activityDoneTraceRowKind()?.let { kind ->
                    store.dispatch(
                        SharedActivityIntent.ReportDoneTrace(
                            ActivityDoneTraceEvent.ItemReappeared(
                                cause = ActivityReappearCause.Undo,
                                rowKind = kind,
                                wasUnreadAtClick = pending.wasUnreadAtClick,
                                msSinceClick = (nowMillis() - pending.clickedAtMillis).coerceAtLeast(0L),
                                seqChanged = ActivitySeqChange.None,
                            )
                        )
                    )
                }
            }
            undo = undo.undo(pending.token)
            syncPending()
        }

        fun toastEnds() = apply(undo.expire(undo.pending!!.token), ActivityDoneCommitTrigger.ToastClosed)

        fun flushPageHidden() {
            if (undo.pending != null) {
                apply(undo.flush(), ActivityDoneCommitTrigger.PageHidden)
            }
        }

        fun checkImmediateCallOff() {
            val pending = undo.pending ?: return
            if (pending.action != ActivitySwipeDeferredAction.Done) return
            val current = store.state.value.items.firstOrNull { it.key == pending.item.key } ?: return
            val decision = evaluateActivitySwipeDone(pending.item, current) as? ActivitySwipeDoneDecision.CallOff ?: return
            pending.item.activityDoneTraceRowKind()?.let { kind ->
                store.dispatch(
                    SharedActivityIntent.ReportDoneTrace(
                        ActivityDoneTraceEvent.ItemReappeared(
                            cause = decision.cause,
                            rowKind = kind,
                            wasUnreadAtClick = pending.wasUnreadAtClick,
                            msSinceClick = (nowMillis() - pending.clickedAtMillis).coerceAtLeast(0L),
                            seqChanged = decision.seqChanged,
                        )
                    )
                )
            }
            undo = undo.undo(pending.token)
            syncPending()
        }

        private fun apply(transition: ActivitySwipeDoneTransition, trigger: ActivityDoneCommitTrigger) {
            undo = transition.state
            transition.commit?.let { pending -> commit(pending, trigger) }
            syncPending()
        }

        private fun commit(pending: ActivityPendingSwipeDone, trigger: ActivityDoneCommitTrigger) {
            val current = store.state.value.items.firstOrNull { it.key == pending.item.key }
            if (pending.action == ActivitySwipeDeferredAction.Done) {
                val decision = evaluateActivitySwipeDone(pending.item, current)
                if (decision is ActivitySwipeDoneDecision.CallOff) {
                    pending.item.activityDoneTraceRowKind()?.let { kind ->
                        store.dispatch(
                            SharedActivityIntent.ReportDoneTrace(
                                ActivityDoneTraceEvent.ItemReappeared(
                                    cause = decision.cause,
                                    rowKind = kind,
                                    wasUnreadAtClick = pending.wasUnreadAtClick,
                                    msSinceClick = (nowMillis() - pending.clickedAtMillis).coerceAtLeast(0L),
                                    seqChanged = decision.seqChanged,
                                )
                            )
                        )
                    }
                    return
                }
            }
            val intent = activitySwipeDeferredCommitIntent(pending, current, trigger = trigger, nowMillis = nowMillis()) ?: return
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
    fun failedMarkDoneAndUnfollowRollbackDoNotResurrectStalePendingHide() = runTest {
        val initial = snapshot(items = listOf(channelA, channelB), totalCount = 5, totalUnreadCount = 3)
        val failDoneRepo = FakeInboxRepository(initialSnapshots = listOf(initial, initial), failDone = true)
        val doneStore = activityStore(backgroundScope, inboxRepository = failDoneRepo)
        doneStore.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val donePanel = Panel(doneStore)

        donePanel.done(channelA)
        runCurrent()
        assertEquals(4 to 1, doneStore.state.value.activityHeaderCounts())

        // Toast expires -> MarkDone fails -> rollback + refresh must NOT restore stale pendingDeferredHide.
        donePanel.toastEnds()
        runCurrent()
        assertNull(doneStore.state.value.pendingDeferredHide)
        assertEquals(5 to 3, doneStore.state.value.activityHeaderCounts())

        // Same for a failed UnfollowThread.
        val unfollowInitial = snapshot(items = listOf(threadC, channelB), totalCount = 4, totalUnreadCount = 3)
        val failConvRepo =
            FakeConversationRepository().apply {
                unfollowResult = DataResult.Error(ScreenError("unfollow_failed"))
            }
        val unfollowStore =
            activityStore(
                scope = backgroundScope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(unfollowInitial, unfollowInitial)),
                conversationRepository = failConvRepo
            )
        unfollowStore.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val unfollowPanel = Panel(unfollowStore)

        unfollowPanel.unfollow(threadC)
        runCurrent()
        assertEquals(4 to 1, unfollowStore.state.value.activityHeaderCounts())

        unfollowPanel.toastEnds()
        runCurrent()
        assertNull(unfollowStore.state.value.pendingDeferredHide)
        assertEquals(4 to 3, unfollowStore.state.value.activityHeaderCounts())
    }

    @Test
    fun newerActivityDuringUndoWindowSkipsDoneAndRestoresUpdatedCountsOnExpiry() = runTest {
        val initialA =
            channelA.copy(
                latestActivityMessageId = MessageId("m-10"),
                latestActivitySeq = "10",
                doneFrontierSeqPresent = true,
                doneFrontierSeq = "10",
                latestActivityAt = "2026-10-01T00:00:00Z",
            )
        val newerA =
            initialA.copy(
                unreadCount = 3,
                latestActivityMessageId = MessageId("m-11"),
                latestActivitySeq = "11",
                doneFrontierSeqPresent = true,
                doneFrontierSeq = "11",
                latestActivityAt = "2026-10-01T00:05:00Z",
            )
        val repo =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(items = listOf(initialA, channelB), totalCount = 4, totalUnreadCount = 3),
                        snapshot(items = listOf(newerA, channelB), totalCount = 4, totalUnreadCount = 4),
                    )
            )
        val summaryFacts = mutableListOf<ActivityUnreadSummaryFact>()
        val store = activityStore(backgroundScope, inboxRepository = repo, onSummaryFact = { summaryFacts += it })
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val panel = Panel(store)

        panel.done(initialA)
        runCurrent()
        assertEquals(3 to 1, store.state.value.activityHeaderCounts())

        // Newer activity arrives for channelA while the 8 s toast is still active.
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()

        // When the toast expires, activitySwipeDeferredCommitIntent sees newer activity and skips MarkDone;
        // clearing pendingDeferredHide restores the updated counts (4 total, 4 unread) with no server write.
        panel.toastEnds()
        runCurrent()
        assertEquals(emptyList(), repo.doneChannelIds)
        assertNull(store.state.value.pendingDeferredHide)
        assertEquals(4 to 4, store.state.value.activityHeaderCounts())
        assertEquals(4, summaryFacts.last().totalCount)
        assertEquals(4, summaryFacts.last().totalUnreadCount)
    }

    @Test
    fun objectCountsStayInHeaderWhileConversationUnreadExcludesObjectsForMarkAllRead() = runTest {
        val objectCounts =
            ActivityObjectCounts(
                objectUnreadCount = 2,
                objectMentionUnreadCount = 1,
                objectCount = 3,
            )
        val repo =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(items = listOf(channelA), totalCount = 1, totalUnreadCount = 2)
                            .copy(objectCounts = objectCounts)
                    )
            )
        val store = activityStore(backgroundScope, inboxRepository = repo)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(4 to 4, store.state.value.activityHeaderCounts())
        assertEquals(2, store.state.value.activityConversationUnreadCount())

        // Hiding the only unread conversation drops header counts to objects-only (3 / 2) and
        // drops conversation-only unread (Mark all read) to 0.
        val panel = Panel(store)
        panel.done(channelA)
        runCurrent()
        assertEquals(3 to 2, store.state.value.activityHeaderCounts())
        assertEquals(0, store.state.value.activityConversationUnreadCount())
    }

    @Test
    fun deferredDoneEmitsDoneCommittedForAllTriggersAndUndoOrCallOffReappearedTraces() = runTest {
        val initialA =
            channelA.copy(
                latestActivitySeq = "10",
                doneFrontierSeq = "10",
                doneFrontierSeqPresent = true,
            )
        val initialB =
            channelB.copy(
                latestActivitySeq = "20",
                doneFrontierSeq = "20",
                doneFrontierSeqPresent = true,
            )
        val newerB = initialB.copy(latestActivitySeq = "21", doneFrontierSeq = "21")
        val repo =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(items = listOf(initialA, initialB), totalCount = 2, totalUnreadCount = 3),
                        snapshot(items = listOf(newerB), totalCount = 1, totalUnreadCount = 1),
                    )
            )
        var now = 1_000L
        val traces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = repo,
                nowMillis = { now },
                activityDoneTraceSink = { sid, event -> traces += sid to event },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val panel = Panel(store, nowMillis = { now })

        // 1) Undo emits ItemReappeared(Undo).
        panel.done(initialA)
        now += 1_500L
        panel.undoToast()
        runCurrent()
        val undoEvent = traces.single().second as ActivityDoneTraceEvent.ItemReappeared
        assertEquals(ActivityReappearCause.Undo, undoEvent.cause)
        assertEquals(1_500L, undoEvent.msSinceClick)
        assertEquals(true, undoEvent.wasUnreadAtClick)

        // 2) Next row commits A with NextRow trigger, then newer activity on B calls B off immediately.
        traces.clear()
        panel.done(initialA)
        now += 2_000L
        panel.done(initialB)
        runCurrent()
        val nextRowCommit = traces.single().second as ActivityDoneTraceEvent.DoneCommitted
        assertEquals(ActivityDoneCommitTrigger.NextRow, nextRowCommit.trigger)
        assertEquals(2_000L, nextRowCommit.waitMs)

        traces.clear()
        now += 500L
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        panel.checkImmediateCallOff()
        runCurrent()
        val calloff = traces.single().second as ActivityDoneTraceEvent.ItemReappeared
        assertEquals(ActivityReappearCause.CalloffNewerActivity, calloff.cause)
        assertEquals(ActivitySeqChange.Both, calloff.seqChanged)
        assertEquals(500L, calloff.msSinceClick)

        // 3) PageHidden flush commits waiting Done with PageHidden trigger.
        traces.clear()
        panel.done(newerB)
        now += 3_000L
        panel.flushPageHidden()
        runCurrent()
        val pageHiddenCommit = traces.single().second as ActivityDoneTraceEvent.DoneCommitted
        assertEquals(ActivityDoneCommitTrigger.PageHidden, pageHiddenCommit.trigger)
        assertEquals(3_000L, pageHiddenCommit.waitMs)
    }
}
