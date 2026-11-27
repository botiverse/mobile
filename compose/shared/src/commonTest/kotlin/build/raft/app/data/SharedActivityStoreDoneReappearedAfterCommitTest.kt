package build.raft.app.data

import build.raft.app.model.ActivityDoneRowKind
import build.raft.app.model.ActivityDoneUpdated
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for `#wg-mobile-sync:#346` (`botiverse/slock` `#17177`
 * `inboxDoneReappearedAfterCommit.test.ts`): `slock.activity.item_reappeared` after a committed
 * Done (`after_commit_sync_reread`, `after_commit_new_message`, `after_commit_other_device`,
 * `unknown`), deduplication with `suppression_marker_newer` / `done_failed`, frontier catch-up
 * `was_unread_at_click` preservation, bounded 200-entry map, and server/reset cleanup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreDoneReappearedAfterCommitTest {
    private val serverId = ServerId("server-1")

    private fun storageRow(
        channelId: String = "ch-1",
        unread: Int = 1,
        latestActivitySeq: String = "10",
        readStateSeq: String = "10",
        doneFrontierSeq: String = "10",
    ): InboxItem =
        InboxItem(
            key = "channel:$channelId",
            kind = InboxItemKind.Channel,
            channelId = ChannelId(channelId),
            channelName = channelId,
            channelKind = ConversationKind.Channel,
            unreadCount = unread,
            firstUnreadMessageId = if (unread > 0) MessageId("m-$latestActivitySeq") else null,
            lastMessageId = MessageId("m-$latestActivitySeq"),
            latestActivityMessageId = MessageId("m-$latestActivitySeq"),
            latestActivitySeq = latestActivitySeq,
            readStateLatestActivitySeq = readStateSeq,
            doneFrontierSeq = doneFrontierSeq,
            doneFrontierSeqPresent = true,
        )

    private class MutableInboxRepository(
        var serving: InboxSnapshot,
        var failNextDone: Boolean = false,
        var failOnDoneCallNumber: Int? = null,
        var doneGate: CompletableDeferred<Unit>? = null,
    ) : InboxRepository {
        val doneCalls = mutableListOf<Pair<ChannelId, InboxDoneFrontier>>()

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> =
            DataResult.Content(serving)

        override suspend fun loadMore(serverId: ServerId, filter: InboxFilter, offset: Int, limit: Int): DataResult<InboxSnapshot> =
            DataResult.Content(serving)

        override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> {
            doneCalls += channelId to frontier
            doneGate?.await()
            val shouldFail = failNextDone || (failOnDoneCallNumber != null && doneCalls.size == failOnDoneCallNumber)
            return if (shouldFail) {
                DataResult.Error(ScreenError("done_failed"))
            } else {
                DataResult.Content(InboxDoneResult(channelId))
            }
        }
    }

    private fun TestScope.testStore(
        inbox: InboxRepository,
        traces: MutableList<Pair<String, ActivityDoneTraceEvent>>,
        holdMillis: Long = ActivityLocalMutationHoldMillis,
    ): SharedActivityStore =
        activityStore(
            scope = backgroundScope,
            inboxRepository = inbox,
            inboxInPlaceEnabled = { true },
            inboxRowPositionEnabled = { true },
            localMutationHoldMillis = holdMillis,
            realtimeBackgroundRefreshIntervalMillis = 0L,
            activityDoneTraceSink = { sid, event -> traces += sid to event },
        )

    private fun List<Pair<String, ActivityDoneTraceEvent>>.reappeared(): List<ActivityDoneTraceEvent.ItemReappeared> =
        mapNotNull { it.second as? ActivityDoneTraceEvent.ItemReappeared }

    @Test
    fun staleListRereadPastHoldReportsAfterCommitSyncRereadOnce() = runTest {
        val row = storageRow(unread = 2, latestActivitySeq = "10", readStateSeq = "10", doneFrontierSeq = "10")
        val inbox = MutableInboxRepository(snapshot(listOf(row)))
        val traces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val store = testStore(inbox, traces)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        advanceTimeBy(1_000L)
        store.dispatch(SharedActivityIntent.MarkDone(row))
        runCurrent()
        assertTrue(store.state.value.items.isEmpty())
        assertTrue(traces.reappeared().isEmpty())

        // Past the 90s hold, server still lists the same row with unchanged seq.
        advanceTimeBy(ActivityLocalMutationHoldMillis + 5_000L)
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()

        val events = traces.reappeared()
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(ActivityReappearCause.AfterCommitSyncReread, event.cause)
        assertEquals(ActivityDoneTraceRowKind.Channel, event.rowKind)
        assertEquals(true, event.wasUnreadAtClick)
        assertEquals(ActivitySeqChange.None, event.seqChanged)
        assertEquals(ActivityLocalMutationHoldMillis + 5_000L, event.msSinceClick)

        // A second refresh must not report again.
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(1, traces.reappeared().size)
    }

    @Test
    fun newerLatestActivitySeqPastHoldReportsAfterCommitNewMessage() = runTest {
        val row = storageRow(unread = 1, latestActivitySeq = "10", readStateSeq = "10", doneFrontierSeq = "10")
        val inbox = MutableInboxRepository(snapshot(listOf(row)))
        val traces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val store = testStore(inbox, traces)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkDone(row))
        runCurrent()

        advanceTimeBy(ActivityLocalMutationHoldMillis + 1_000L)
        inbox.serving = snapshot(listOf(storageRow(unread = 1, latestActivitySeq = "11", readStateSeq = "11", doneFrontierSeq = "11")))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()

        val event = traces.reappeared().single()
        assertEquals(ActivityReappearCause.AfterCommitNewMessage, event.cause)
        assertEquals(ActivitySeqChange.Both, event.seqChanged)
    }

    @Test
    fun remoteUndoFlagsAfterCommitOtherDeviceAndSubsequentRemoteDoneClearsFlag() = runTest {
        val row = storageRow(channelId = "ch-1", unread = 1, latestActivitySeq = "10", readStateSeq = "10", doneFrontierSeq = "10")
        val row2 = storageRow(channelId = "ch-2", unread = 1, latestActivitySeq = "20", readStateSeq = "20", doneFrontierSeq = "20")
        val inbox = MutableInboxRepository(snapshot(listOf(row, row2)))
        val traces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val store = testStore(inbox, traces)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkDone(row))
        store.dispatch(SharedActivityIntent.MarkDone(row2))
        runCurrent()
        assertTrue(store.state.value.items.isEmpty())

        // Remote undo on ch-1 -> releases hold and marks otherDeviceCleared = true.
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ActivityDoneUpdated(
                    serverId = serverId,
                    rowKind = ActivityDoneRowKind.Channel,
                    rowId = "ch-1",
                    done = false,
                    position = null,
                )
            )
        )
        // Remote undo then remote done on ch-2 -> clears otherDeviceCleared back to false.
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ActivityDoneUpdated(
                    serverId = serverId,
                    rowKind = ActivityDoneRowKind.Channel,
                    rowId = "ch-2",
                    done = false,
                    position = null,
                )
            )
        )
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ActivityDoneUpdated(
                    serverId = serverId,
                    rowKind = ActivityDoneRowKind.Channel,
                    rowId = "ch-2",
                    done = true,
                    position = "20",
                )
            )
        )
        runCurrent()

        // Reload serves both rows with unchanged seqs past hold.
        advanceTimeBy(ActivityLocalMutationHoldMillis + 1_000L)
        inbox.serving = snapshot(listOf(row, row2))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()

        val events = traces.reappeared()
        assertEquals(2, events.size)
        assertEquals(ActivityReappearCause.AfterCommitOtherDevice, events[0].cause)
        assertEquals(ActivitySeqChange.None, events[0].seqChanged)
        assertEquals(ActivityReappearCause.AfterCommitSyncReread, events[1].cause)
        assertEquals(ActivitySeqChange.None, events[1].seqChanged)
    }

    @Test
    fun holdLiftedDuringFlightOrAfterCommitReportsSuppressionMarkerNewerOnlyOnce() = runTest {
        val row = storageRow(channelId = "ch-1", unread = 1, latestActivitySeq = "10", readStateSeq = "10", doneFrontierSeq = "10")
        val row2 = storageRow(channelId = "ch-2", unread = 1, latestActivitySeq = "20", readStateSeq = "20", doneFrontierSeq = "20")
        val gate = CompletableDeferred<Unit>()
        val inbox = MutableInboxRepository(snapshot(listOf(row, row2)), doneGate = gate)
        val traces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val store = testStore(inbox, traces)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        // 1) Start Done on ch-1 (in flight), then a newer row lifts the hold before Done confirms.
        store.dispatch(SharedActivityIntent.MarkDone(row))
        runCurrent()
        val newerRow1 = storageRow(channelId = "ch-1", unread = 1, latestActivitySeq = "11", readStateSeq = "11", doneFrontierSeq = "11")
        inbox.serving = snapshot(listOf(newerRow1, row2))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()

        // Hold lifted mid-flight: reported once as suppression_marker_newer.
        assertEquals(listOf(ActivityReappearCause.SuppressionMarkerNewer), traces.reappeared().map { it.cause })

        // Now the in-flight Done finishes: confirm() returns false, so committedDones does NOT record ch-1.
        gate.complete(Unit)
        inbox.doneGate = null
        runCurrent()
        assertEquals(listOf(ActivityReappearCause.SuppressionMarkerNewer), traces.reappeared().map { it.cause })

        // Empty then re-list ch-1 -> proves committedDones stayed empty for ch-1 (no after_commit_*).
        inbox.serving = snapshot(listOf(row2))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        inbox.serving = snapshot(listOf(newerRow1, row2))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf(ActivityReappearCause.SuppressionMarkerNewer), traces.reappeared().map { it.cause })

        // 2) Post-commit hold lift on ch-2: Done commits cleanly, then within hold a newer row lifts hold.
        store.dispatch(SharedActivityIntent.MarkDone(row2))
        runCurrent()
        val newerRow2 = storageRow(channelId = "ch-2", unread = 1, latestActivitySeq = "21", readStateSeq = "21", doneFrontierSeq = "21")
        inbox.serving = snapshot(listOf(newerRow1, newerRow2))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(
            listOf(ActivityReappearCause.SuppressionMarkerNewer, ActivityReappearCause.SuppressionMarkerNewer),
            traces.reappeared().map { it.cause },
        )

        // Empty then re-list ch-2 -> proves post-commit lift forgot ch-2 from committedDones (no double-report).
        inbox.serving = snapshot(listOf(newerRow1))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        inbox.serving = snapshot(listOf(newerRow1, newerRow2))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(
            listOf(ActivityReappearCause.SuppressionMarkerNewer, ActivityReappearCause.SuppressionMarkerNewer),
            traces.reappeared().map { it.cause },
        )
    }

    @Test
    fun frontierCatchUpPreservesOriginalWasUnreadAtClickOnFailureAndOnLaterReread() = runTest {
        val initialRow = storageRow(channelId = "ch-1", unread = 1, latestActivitySeq = "10", readStateSeq = "10", doneFrontierSeq = "10")
        val inbox = MutableInboxRepository(snapshot(listOf(initialRow)))
        val traces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val store = testStore(inbox, traces)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        // 1) Catch-up success: in-place message moves latestActivitySeq to 12 while storage frontier stays 10.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("msg-12", channelId = "ch-1", seq = 12L))))
        // Server returns caught-up row with unread=0 (already read) and frontier=12.
        val caughtUpRow = storageRow(channelId = "ch-1", unread = 0, latestActivitySeq = "12", readStateSeq = "12", doneFrontierSeq = "12")
        inbox.serving = snapshot(listOf(caughtUpRow))
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()
        assertTrue(store.state.value.items.isEmpty())
        assertEquals(2, inbox.doneCalls.size)

        // Past hold, server still lists the caught-up row -> after_commit_sync_reread with ORIGINAL wasUnreadAtClick=true.
        advanceTimeBy(ActivityLocalMutationHoldMillis + 1_000L)
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()

        val rereadEvent = traces.reappeared().single()
        assertEquals(ActivityReappearCause.AfterCommitSyncReread, rereadEvent.cause)
        assertEquals(true, rereadEvent.wasUnreadAtClick)
        assertEquals(ActivitySeqChange.None, rereadEvent.seqChanged)

        // 2) Catch-up failure: fails on the catch-up re-Done -> done_failed preserves ORIGINAL wasUnreadAtClick=true, no after_commit_*.
        val failInitial = storageRow(channelId = "ch-2", unread = 1, latestActivitySeq = "20", readStateSeq = "20", doneFrontierSeq = "20")
        inbox.serving = snapshot(listOf(failInitial))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        traces.clear()
        inbox.doneCalls.clear()
        inbox.failOnDoneCallNumber = 2

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("msg-22", channelId = "ch-2", seq = 22L))))
        val failCaughtUp = storageRow(channelId = "ch-2", unread = 0, latestActivitySeq = "22", readStateSeq = "22", doneFrontierSeq = "22")
        inbox.serving = snapshot(listOf(failCaughtUp))
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()

        assertEquals(2, inbox.doneCalls.size)
        val failEvents = traces.reappeared()
        assertEquals(1, failEvents.size)
        assertEquals(ActivityReappearCause.DoneFailed, failEvents.single().cause)
        assertEquals(true, failEvents.single().wasUnreadAtClick)
    }

    @Test
    fun resetClearsCommittedDoneTraceSoLaterLoadEmitsNothing() = runTest {
        val row = storageRow(channelId = "ch-1", unread = 1, latestActivitySeq = "10", readStateSeq = "10", doneFrontierSeq = "10")
        val inbox = MutableInboxRepository(snapshot(listOf(row)))
        val traces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val store = testStore(inbox, traces)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkDone(row))
        runCurrent()
        assertTrue(store.state.value.items.isEmpty())

        // Server switch / reset clears committed trace before next load.
        store.reset()
        advanceTimeBy(ActivityLocalMutationHoldMillis + 1_000L)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        assertTrue(traces.reappeared().isEmpty())
    }

    @Test
    fun pureTraceUnitChecksUserActionUnknownCapAndNonConversationExclusion() {
        var now = 10_000L
        val trace = ActivityDoneReappearTrace(nowMillis = { now }, maxCommitted = 2)
        val meta =
            ActivityLocalDoneMeta(
                shownLatestActivitySeq = "10",
                isJoint = false,
                rowKind = ActivityDoneTraceRowKind.Channel,
                wasUnreadAtClick = true,
                clickedAtMillis = 5_000L,
            )

        // Bounded cap (maxCommitted = 2): oldest key ch-1 evicted when ch-3 is added.
        val r1 = storageRow("ch-1")
        val r2 = storageRow("ch-2")
        val r3 = storageRow("ch-3")
        trace.noteCommitted(r1.key, r1, meta)
        trace.noteCommitted(r2.key, r2, meta)
        trace.noteCommitted(r3.key, r3, meta)

        // UserAction consumes ch-2 without emitting.
        assertTrue(trace.detectReappeared(emptyList(), listOf(r1, r2), ActivityDoneRowChange.UserAction).isEmpty())
        // ch-2 already consumed; ch-3 under Other reports unknown.
        val unknown = trace.detectReappeared(emptyList(), listOf(r2, r3), ActivityDoneRowChange.Other)
        assertEquals(1, unknown.size)
        assertEquals(ActivityReappearCause.Unknown, unknown.single().cause)
        assertEquals(5_000L, unknown.single().msSinceClick)

        // Object & mention rows are ignored.
        val obj =
            InboxItem(
                key = "object:artifact:pt_9",
                kind = InboxItemKind.Object,
                channelId = ChannelId("ch-1"),
                channelName = "general",
            )
        trace.noteCommitted(obj.key, obj, meta)
        assertTrue(trace.detectReappeared(emptyList(), listOf(obj), ActivityDoneRowChange.ListRead).isEmpty())
    }
}
