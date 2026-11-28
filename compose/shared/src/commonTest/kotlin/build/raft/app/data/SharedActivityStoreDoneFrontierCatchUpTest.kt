package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Storage-frontier catch-up Done and `slock.activity.item_reappeared` /
 * `slock.activity.done_committed` telemetry parity (`#proj-o11y:#53`, mirrors
 * `botiverse/slock` PR `#16943` `inboxDoneFrontierCatchUp.test.ts`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreDoneFrontierCatchUpTest {
    @Test
    fun caughtUpFrontierAfterInPlacePatchKeepsRowHiddenAndWritesSecondDone() = runTest {
        val initialRow =
            storageRow(
                channelId = "ch-1",
                unread = 1,
                latestActivitySeq = "10",
                readStateSeq = "10",
                doneFrontierSeq = "10",
            )
        val inbox = RecordingDoneInbox(snapshot(listOf(initialRow)))
        val traces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val store = testStore(inbox, traces)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        // Realtime message patches latestActivitySeq to 12 while storage frontier & marker stay at 10.
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("msg-12", channelId = "ch-1", seq = 12L)),
            ),
        )
        assertEquals("12", store.state.value.items.single().latestActivitySeq)
        assertEquals("10", store.state.value.items.single().doneFrontierSeq)

        // User marks Done: first Done goes out through the served frontier 10.
        inbox.serving =
            snapshot(
                listOf(
                    storageRow(
                        channelId = "ch-1",
                        unread = 1,
                        latestActivitySeq = "12",
                        readStateSeq = "12",
                        doneFrontierSeq = "12",
                    ),
                ),
            )
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()

        // Row stayed hidden and a single catch-up Done was written through frontier 12.
        assertEquals(emptyList<InboxItem>(), store.state.value.items)
        assertEquals(
            listOf<Pair<ChannelId, InboxDoneFrontier>>(
                ChannelId("ch-1") to InboxDoneFrontier.Storage("10"),
                ChannelId("ch-1") to InboxDoneFrontier.Storage("12"),
            ),
            inbox.doneCalls.toList(),
        )
        assertEquals(2, traces.size)
        val primaryCommitted = traces[0].second as ActivityDoneTraceEvent.DoneCommitted
        assertEquals(ActivityDoneCommittedTraceName, primaryCommitted.traceName)
        assertEquals(ActivityDoneCommitTrigger.NoToast, primaryCommitted.trigger)
        assertEquals(ActivityDoneTraceRowKind.Channel, primaryCommitted.rowKind)
        assertEquals(true, primaryCommitted.wasUnreadAtClick)
        assertEquals(false, primaryCommitted.frontierCaughtUp)
        assertEquals(0L, primaryCommitted.waitMs)

        val committed = traces[1].second as ActivityDoneTraceEvent.DoneCommitted
        assertEquals(ActivityDoneCommittedTraceName, committed.traceName)
        assertEquals(ActivityDoneCommitTrigger.FrontierCatchUp, committed.trigger)
        assertEquals(ActivityDoneTraceRowKind.Channel, committed.rowKind)
        assertEquals(true, committed.wasUnreadAtClick)
        assertEquals(true, committed.frontierCaughtUp)
        val attrs = committed.toTraceAttrs()
        assertEquals("frontier_catch_up", attrs["trigger"]?.jsonPrimitive?.content)
        assertEquals("channel", attrs["row_kind"]?.jsonPrimitive?.content)
        assertEquals(true, attrs["was_unread_at_click"]?.jsonPrimitive?.boolean)
        assertEquals(true, attrs["frontier_caught_up"]?.jsonPrimitive?.boolean)
        assertEquals(0L, attrs["wait_ms"]?.jsonPrimitive?.long)

        // A subsequent refresh with the same caught-up row does NOT trigger a third Done.
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(emptyList<InboxItem>(), store.state.value.items)
        assertEquals(2, inbox.doneCalls.size)
    }

    @Test
    fun genuinelyNewerActivityLiftsHoldAndEmitsItemReappearedBoth() = runTest {
        val initialRow =
            storageRow(
                channelId = "ch-1",
                unread = 2,
                latestActivitySeq = "10",
                readStateSeq = "10",
                doneFrontierSeq = "10",
            )
        val inbox = RecordingDoneInbox(snapshot(listOf(initialRow)))
        val traces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val store = testStore(inbox, traces)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("msg-12", channelId = "ch-1", seq = 12L)),
            ),
        )
        // Server answers with seq 13 > shown seq 12 -> genuine newer activity.
        inbox.serving =
            snapshot(
                listOf(
                    storageRow(
                        channelId = "ch-1",
                        unread = 1,
                        latestActivitySeq = "13",
                        readStateSeq = "13",
                        doneFrontierSeq = "13",
                    ),
                ),
            )
        advanceTimeBy(250L)
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()

        assertEquals(listOf("channel:ch-1"), store.state.value.items.map { it.key })
        assertEquals(1, inbox.doneCalls.size, "newer activity must never trigger a catch-up Done")
        assertEquals(2, traces.size)
        assertEquals(ActivityDoneCommitTrigger.NoToast, (traces[0].second as ActivityDoneTraceEvent.DoneCommitted).trigger)
        val reappeared = traces[1].second as ActivityDoneTraceEvent.ItemReappeared
        assertEquals(ActivityItemReappearedTraceName, reappeared.traceName)
        assertEquals(ActivityReappearCause.SuppressionMarkerNewer, reappeared.cause)
        assertEquals(ActivityDoneTraceRowKind.Channel, reappeared.rowKind)
        assertEquals(true, reappeared.wasUnreadAtClick)
        assertEquals(ActivitySeqChange.Both, reappeared.seqChanged)
    }

    @Test
    fun frontierOrMarkerPastShownSeqLiftsHoldWithSeqChangedFrontierAndNoSecondDone() = runTest {
        listOf(
            "frontier_ahead" to ("13" to "12"),
            "marker_ahead" to ("12" to "13"),
        ).forEach { (label, pair) ->
            val (servedFrontier, servedReadMarker) = pair
            val initialRow =
                storageRow(
                    channelId = "ch-1",
                    unread = 1,
                    latestActivitySeq = "10",
                    readStateSeq = "10",
                    doneFrontierSeq = "10",
                )
            val inbox = RecordingDoneInbox(snapshot(listOf(initialRow)))
            val traces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
            val store = testStore(inbox, traces)
            store.dispatch(SharedActivityIntent.LoadInitial)
            runCurrent()

            store.dispatch(
                SharedActivityIntent.ApplyRealtimeEvent(
                    MessageNew(message("msg-12", channelId = "ch-1", seq = 12L)),
                ),
            )
            inbox.serving =
                snapshot(
                    listOf(
                        storageRow(
                            channelId = "ch-1",
                            unread = 1,
                            latestActivitySeq = "12",
                            readStateSeq = servedReadMarker,
                            doneFrontierSeq = servedFrontier,
                        ),
                    ),
                )
            store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
            runCurrent()

            assertEquals(listOf("channel:ch-1"), store.state.value.items.map { it.key }, label)
            assertEquals(1, inbox.doneCalls.size, "$label must not write a catch-up Done")
            assertEquals(2, traces.size, label)
            assertEquals(ActivityDoneCommitTrigger.NoToast, (traces[0].second as ActivityDoneTraceEvent.DoneCommitted).trigger, label)
            val reappeared = traces[1].second as ActivityDoneTraceEvent.ItemReappeared
            assertEquals(ActivityReappearCause.SuppressionMarkerNewer, reappeared.cause, label)
            assertEquals(ActivitySeqChange.Frontier, reappeared.seqChanged, label)
        }
    }

    @Test
    fun jointRowNeverTriggersFrontierCatchUpDone() = runTest {
        val jointRow =
            storageRow(
                channelId = "joint-1",
                unread = 1,
                latestActivitySeq = "10",
                readStateSeq = "10",
                doneFrontierSeq = "10",
                channelKind = ConversationKind.Joint,
                channelType = "joint",
            )
        val inbox = RecordingDoneInbox(snapshot(listOf(jointRow)))
        val traces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val store = testStore(inbox, traces)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("msg-12", channelId = "joint-1", seq = 12L)),
            ),
        )
        inbox.serving =
            snapshot(
                listOf(
                    jointRow.copy(
                        latestActivitySeq = "12",
                        readStateLatestActivitySeq = "12",
                        doneFrontierSeq = "12",
                    ),
                ),
            )
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()

        assertEquals(listOf("channel:joint-1"), store.state.value.items.map { it.key })
        assertEquals(1, inbox.doneCalls.size)
        assertEquals(2, traces.size)
        assertEquals(ActivityDoneCommitTrigger.NoToast, (traces[0].second as ActivityDoneTraceEvent.DoneCommitted).trigger)
        val reappeared = traces[1].second as ActivityDoneTraceEvent.ItemReappeared
        assertEquals(ActivityReappearCause.SuppressionMarkerNewer, reappeared.cause)
        assertEquals(ActivitySeqChange.Frontier, reappeared.seqChanged)
    }

    @Test
    fun failedDoneAndMarkerNullEmitItemReappeared() = runTest {
        // Case 1: failed Done emits done_committed then done_failed.
        val failingInbox =
            RecordingDoneInbox(
                snapshot(
                    listOf(
                        storageRow(
                            channelId = "ch-1",
                            unread = 1,
                            latestActivitySeq = "10",
                            readStateSeq = "10",
                            doneFrontierSeq = "10",
                        ),
                    ),
                ),
            ).apply { failDone = true }
        val failTraces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val failStore = testStore(failingInbox, failTraces)
        failStore.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        failStore.dispatch(SharedActivityIntent.MarkDone(failStore.state.value.items.single()))
        runCurrent()

        assertEquals(2, failTraces.size)
        assertEquals(ActivityDoneCommitTrigger.NoToast, (failTraces[0].second as ActivityDoneTraceEvent.DoneCommitted).trigger)
        val failedEvent = failTraces[1].second as ActivityDoneTraceEvent.ItemReappeared
        assertEquals(ActivityReappearCause.DoneFailed, failedEvent.cause)
        assertEquals(ActivityDoneTraceRowKind.Channel, failedEvent.rowKind)
        assertEquals(true, failedEvent.wasUnreadAtClick)
        assertEquals(ActivitySeqChange.None, failedEvent.seqChanged)

        // Case 2: row with no marker and no anchor emits done_committed then marker_null.
        val noMarkerRow =
            activityItem("ch-2", unreadCount = 0).copy(
                readStateLatestActivitySeq = null,
                latestActivityMessageId = null,
                lastMessageId = null,
                parentMessageId = null,
                doneFrontierSeqPresent = false,
            )
        val noMarkerInbox = RecordingDoneInbox(snapshot(listOf(noMarkerRow)))
        val noMarkerTraces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val noMarkerStore = testStore(noMarkerInbox, noMarkerTraces)
        noMarkerStore.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        noMarkerStore.dispatch(SharedActivityIntent.MarkDone(noMarkerStore.state.value.items.single()))
        runCurrent()

        assertEquals(2, noMarkerTraces.size)
        assertEquals(ActivityDoneCommitTrigger.NoToast, (noMarkerTraces[0].second as ActivityDoneTraceEvent.DoneCommitted).trigger)
        val nullMarkerEvent = noMarkerTraces[1].second as ActivityDoneTraceEvent.ItemReappeared
        assertEquals(ActivityReappearCause.MarkerNull, nullMarkerEvent.cause)
        assertEquals(ActivityDoneTraceRowKind.Channel, nullMarkerEvent.rowKind)
        assertEquals(false, nullMarkerEvent.wasUnreadAtClick)
        assertEquals(ActivitySeqChange.None, nullMarkerEvent.seqChanged)
    }

    @Test
    fun primaryMarkDoneEmitsCommitMetadataAndPreservesWaitMsInStoreClockDomain() = runTest {
        val row =
            storageRow(
                channelId = "ch-1",
                unread = 0,
                latestActivitySeq = "10",
                readStateSeq = "10",
                doneFrontierSeq = "10",
            )
        val inbox = RecordingDoneInbox(snapshot(listOf(row))).apply { failDone = true }
        val traces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val store = testStore(inbox, traces)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        advanceTimeBy(10_000L)
        store.dispatch(
            SharedActivityIntent.MarkDone(
                item = store.state.value.items.single(),
                commitTrigger = ActivityDoneCommitTrigger.ToastClosed,
                waitMs = 8_000L,
                wasUnreadAtClick = true,
                frontierCaughtUp = true,
            ),
        )
        runCurrent()

        assertEquals(2, traces.size)
        val committed = traces[0].second as ActivityDoneTraceEvent.DoneCommitted
        assertEquals(ActivityDoneCommitTrigger.ToastClosed, committed.trigger)
        assertEquals(ActivityDoneTraceRowKind.Channel, committed.rowKind)
        assertEquals(true, committed.wasUnreadAtClick)
        assertEquals(8_000L, committed.waitMs)
        assertEquals(true, committed.frontierCaughtUp)

        val failed = traces[1].second as ActivityDoneTraceEvent.ItemReappeared
        assertEquals(ActivityReappearCause.DoneFailed, failed.cause)
        assertEquals(true, failed.wasUnreadAtClick)
        assertEquals(8_000L, failed.msSinceClick)
    }

    @Test
    fun nonConversationRowEmitsNoDoneCommittedAndReportDoneTraceForwardsEvent() = runTest {
        val mentionRow =
            activityItem("ch-mention", unreadCount = 1).copy(
                key = "mention:m-1",
                kind = InboxItemKind.MentionAction,
            )
        val inbox = RecordingDoneInbox(snapshot(listOf(mentionRow)))
        val traces = mutableListOf<Pair<String, ActivityDoneTraceEvent>>()
        val store = testStore(inbox, traces)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(
            SharedActivityIntent.MarkDone(
                item = store.state.value.items.single(),
                commitTrigger = ActivityDoneCommitTrigger.ToastClosed,
                waitMs = 8_000L,
            ),
        )
        runCurrent()
        assertEquals(emptyList<Pair<String, ActivityDoneTraceEvent>>(), traces)

        val undoEvent: ActivityDoneTraceEvent =
            ActivityDoneTraceEvent.ItemReappeared(
                cause = ActivityReappearCause.Undo,
                rowKind = ActivityDoneTraceRowKind.Dm,
                wasUnreadAtClick = true,
                msSinceClick = 1_234L,
                seqChanged = ActivitySeqChange.None,
            )
        store.dispatch(SharedActivityIntent.ReportDoneTrace(undoEvent))
        assertEquals(listOf("server-1" to undoEvent), traces)
    }

    private fun TestScope.testStore(
        inbox: RecordingDoneInbox,
        traces: MutableList<Pair<String, ActivityDoneTraceEvent>>,
    ): SharedActivityStore =
        activityStore(
            scope = backgroundScope,
            inboxRepository = inbox,
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            nowMillis = { testScheduler.currentTime },
            realtimeBackgroundRefreshIntervalMillis = 60_000L,
            activityDoneTraceSink = { serverId, event -> traces += serverId to event },
        )

    private fun storageRow(
        channelId: String,
        unread: Int,
        latestActivitySeq: String,
        readStateSeq: String,
        doneFrontierSeq: String,
        channelKind: ConversationKind = ConversationKind.Channel,
        channelType: String = "channel",
    ): InboxItem =
        activityItem(channelId, unreadCount = unread).copy(
            kind = InboxItemKind.Channel,
            channelKind = channelKind,
            channelType = channelType,
            latestActivitySeq = latestActivitySeq,
            readStateLatestActivitySeq = readStateSeq,
            doneFrontierSeq = doneFrontierSeq,
            doneFrontierSeqPresent = true,
            lastMessageId = MessageId("msg-$latestActivitySeq"),
            latestActivityMessageId = MessageId("msg-$latestActivitySeq"),
        )

    private class RecordingDoneInbox(var serving: InboxSnapshot) : InboxRepository {
        var failDone = false
        val doneCalls = mutableListOf<Pair<ChannelId, InboxDoneFrontier>>()

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> =
            DataResult.Content(serving.copy(server = serving.server.copy(id = serverId)))

        override suspend fun loadMore(serverId: ServerId, filter: InboxFilter, offset: Int, limit: Int): DataResult<InboxSnapshot> =
            DataResult.Empty(EmptyReason.NoInboxItems)

        override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> {
            doneCalls += channelId to frontier
            return if (failDone) {
                DataResult.Error(ScreenError("done_failed"))
            } else {
                DataResult.Content(InboxDoneResult(channelId))
            }
        }
    }
}
