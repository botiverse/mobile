package build.raft.app.data

import build.raft.app.model.ActivityChanged
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageNew
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `#proj-o11y:#53` part 2 (`botiverse/slock#16951`): `slock.activity.unread_state_flipped`
 * parity tests covering wire attributes, caps, LRU open tracking, store transition sources,
 * user-action exclusions, and privacy mode gating.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreUnreadFlipTraceTest {
    private data class CapturedFlip(val serverId: String, val event: ActivityUnreadStateFlipped)

    @Test
    fun toTraceAttrsSerializesExactWireContractAndCapsCounts() {
        val withOpen =
            ActivityUnreadStateFlipped(
                direction = ActivityUnreadFlipDirection.ToUnread,
                source = ActivityUnreadFlipSource.BackgroundReread,
                cause = ActivityUnreadFlipCause.OpenReadReverted,
                rowKind = ActivityUnreadFlipRowKind.Thread,
                msSinceOpen = 1_250L,
                seqChanged = ActivityUnreadSeqChange.Both,
                unreadBefore = 0,
                unreadAfter = 250,
                flipsInChange = 25_000,
            ).toTraceAttrs()

        assertEquals("to_unread", withOpen["direction"]?.jsonPrimitive?.content)
        assertEquals("background_reread", withOpen["source"]?.jsonPrimitive?.content)
        assertEquals("open_read_reverted", withOpen["cause"]?.jsonPrimitive?.content)
        assertEquals("thread", withOpen["row_kind"]?.jsonPrimitive?.content)
        assertEquals(1_250L, withOpen["ms_since_open"]?.jsonPrimitive?.long)
        assertEquals("both", withOpen["seq_changed"]?.jsonPrimitive?.content)
        assertEquals(0, withOpen["unread_before"]?.jsonPrimitive?.int)
        assertEquals(ActivityUnreadFlipCountCap, withOpen["unread_after"]?.jsonPrimitive?.int)
        assertEquals(ActivityUnreadFlipsInChangeCap, withOpen["flips_in_change"]?.jsonPrimitive?.int)

        val withoutOpen =
            ActivityUnreadStateFlipped(
                direction = ActivityUnreadFlipDirection.ToRead,
                source = ActivityUnreadFlipSource.ListRefresh,
                cause = ActivityUnreadFlipCause.None,
                rowKind = ActivityUnreadFlipRowKind.Dm,
                msSinceOpen = null,
                seqChanged = ActivityUnreadSeqChange.None,
                unreadBefore = 150,
                unreadAfter = 0,
                flipsInChange = 1,
            ).toTraceAttrs()

        assertFalse("ms_since_open" in withoutOpen)
        assertEquals("dm", withoutOpen["row_kind"]?.jsonPrimitive?.content)
        assertEquals(ActivityUnreadFlipCountCap, withoutOpen["unread_before"]?.jsonPrimitive?.int)
        assertEquals(0, withoutOpen["unread_after"]?.jsonPrimitive?.int)
        assertEquals(1, withoutOpen["flips_in_change"]?.jsonPrimitive?.int)
    }

    @Test
    fun detectFlipsCapsAt20ReportsTotalIgnoresNonConversationsAndEvictsOldestOpenAt200() {
        var now = 1_000L
        val trace = ActivityUnreadFlipTrace(nowMillis = { now })

        // Cap at 20 events per change while preserving full flips_in_change (30).
        val beforeMany = (1..30).map { index -> activityItem("ch-$index", unreadCount = 0) }
        val afterMany = (1..30).map { index -> activityItem("ch-$index", unreadCount = 2) }
        val capped = trace.detectFlips(beforeMany, afterMany, ActivityUnreadFlipSource.ListRefresh)
        assertEquals(ActivityUnreadMaxFlipEventsPerChange, capped.size)
        assertTrue(capped.all { it.flipsInChange == 30 })
        assertTrue(capped.all { it.direction == ActivityUnreadFlipDirection.ToUnread })

        // UserAction emits nothing.
        assertEquals(
            emptyList<ActivityUnreadStateFlipped>(),
            trace.detectFlips(beforeMany, afterMany, ActivityUnreadFlipSource.UserAction),
        )

        // Non-conversation rows (MentionAction) and rows entering/leaving emit nothing.
        val mention = mentionActionItem("res-1")
        val addedRow = activityItem("added-1", unreadCount = 3)
        val removedRow = activityItem("removed-1", unreadCount = 3)
        assertEquals(
            emptyList<ActivityUnreadStateFlipped>(),
            trace.detectFlips(
                previous = listOf(mention, removedRow),
                next = listOf(mention.copy(unreadCount = 2), addedRow),
                source = ActivityUnreadFlipSource.BackgroundReread,
            ),
        )

        // DM row kind and seq_changed = latest / marker / both / none.
        val dmRead =
            activityItem("dm-1", unreadCount = 0).copy(
                key = "dm:dm-1",
                kind = InboxItemKind.DirectMessage,
                latestActivitySeq = "10",
                readStateLatestActivitySeq = "10",
            )
        val dmLatestOnly = dmRead.copy(unreadCount = 1, latestActivitySeq = "11")
        val dmMarkerOnly = dmRead.copy(unreadCount = 1, readStateLatestActivitySeq = "11")
        assertEquals(
            ActivityUnreadSeqChange.Latest,
            trace.detectFlips(listOf(dmRead), listOf(dmLatestOnly), ActivityUnreadFlipSource.BackgroundReread)
                .single()
                .also { assertEquals(ActivityUnreadFlipRowKind.Dm, it.rowKind) }
                .seqChanged,
        )
        assertEquals(
            ActivityUnreadSeqChange.Marker,
            trace.detectFlips(listOf(dmRead), listOf(dmMarkerOnly), ActivityUnreadFlipSource.BackgroundReread)
                .single()
                .seqChanged,
        )

        // LRU eviction at ActivityUnreadFlipMaxOpens (200).
        trace.reset()
        for (index in 1..(ActivityUnreadFlipMaxOpens + 1)) {
            now = index.toLong() * 10L
            trace.noteOpened(activityItem("open-$index", unreadCount = 0).copy(latestActivitySeq = "5"))
        }
        now = 5_000L
        val evictedBefore = activityItem("open-1", unreadCount = 0).copy(latestActivitySeq = "5")
        val evictedAfter = evictedBefore.copy(unreadCount = 1)
        val evictedEvent =
            trace.detectFlips(listOf(evictedBefore), listOf(evictedAfter), ActivityUnreadFlipSource.BackgroundReread).single()
        assertNull(evictedEvent.msSinceOpen)
        assertEquals(ActivityUnreadFlipCause.None, evictedEvent.cause)

        val retainedBefore = activityItem("open-2", unreadCount = 0).copy(latestActivitySeq = "5")
        val retainedAfter = retainedBefore.copy(unreadCount = 1)
        val retainedEvent =
            trace.detectFlips(listOf(retainedBefore), listOf(retainedAfter), ActivityUnreadFlipSource.BackgroundReread).single()
        assertEquals(4_980L, retainedEvent.msSinceOpen)
        assertEquals(ActivityUnreadFlipCause.OpenReadReverted, retainedEvent.cause)
    }

    @Test
    fun openingUnreadRowAndStaleBackgroundRefreshEmitsOpenReadThenOpenReadReverted() = runTest {
        val unreadRow = activityItem("channel-1", unreadCount = 2).copy(latestActivitySeq = "42", readStateLatestActivitySeq = "42")
        val inbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(unreadRow)), snapshot(listOf(unreadRow))),
            )
        val captured = mutableListOf<CapturedFlip>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 1_000L,
                nowMillis = { testScheduler.currentTime },
                localMutationHoldMillis = 2_000L,
                unreadFlipSink = { serverId, event -> captured += CapturedFlip(serverId, event) },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertTrue(captured.isEmpty())

        // Opening the unread row clears it locally and tags the flip open_read.
        advanceTimeBy(500L)
        store.dispatch(SharedActivityIntent.MarkRead(unreadRow, fromOpen = true))
        runCurrent()
        val openRead = captured.single()
        assertEquals("server-1", openRead.serverId)
        assertEquals(ActivityUnreadFlipDirection.ToRead, openRead.event.direction)
        assertEquals(ActivityUnreadFlipSource.OpenRead, openRead.event.source)
        assertEquals(ActivityUnreadFlipCause.None, openRead.event.cause)
        assertEquals(ActivityUnreadFlipRowKind.Channel, openRead.event.rowKind)
        assertEquals(0L, openRead.event.msSinceOpen)
        assertEquals(2, openRead.event.unreadBefore)
        assertEquals(0, openRead.event.unreadAfter)

        // After the local hold expires, a background re-read still serving the same seq unread flips back.
        captured.clear()
        advanceTimeBy(3_000L)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ActivityChanged(serverId = ServerId("server-1"), source = "stale_bg_reread")
            )
        )
        runCurrent()

        val reverted = captured.single()
        assertEquals("server-1", reverted.serverId)
        assertEquals(ActivityUnreadFlipDirection.ToUnread, reverted.event.direction)
        assertEquals(ActivityUnreadFlipSource.BackgroundReread, reverted.event.source)
        assertEquals(ActivityUnreadFlipCause.OpenReadReverted, reverted.event.cause)
        assertEquals(ActivityUnreadSeqChange.None, reverted.event.seqChanged)
        assertEquals(3_000L, reverted.event.msSinceOpen)
        assertEquals(0, reverted.event.unreadBefore)
        assertEquals(2, reverted.event.unreadAfter)
    }

    @Test
    fun openingReadRowRecordsManualOpenWhileAutomaticReopenDoesNot() = runTest {
        val manualRow = activityItem("channel-1", unreadCount = 0).copy(latestActivitySeq = "10")
        val autoRow = activityItem("channel-2", unreadCount = 0).copy(latestActivitySeq = "10")
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(manualRow, autoRow)),
                        snapshot(listOf(manualRow.copy(unreadCount = 1), autoRow.copy(unreadCount = 1))),
                    ),
            )
        val captured = mutableListOf<CapturedFlip>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 500L,
                nowMillis = { testScheduler.currentTime },
                unreadFlipSink = { serverId, event -> captured += CapturedFlip(serverId, event) },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        advanceTimeBy(200L)
        store.dispatch(SharedActivityIntent.RowOpened(manualRow, automatic = false))
        store.dispatch(SharedActivityIntent.RowOpened(autoRow, automatic = true))
        runCurrent()

        advanceTimeBy(800L)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ActivityChanged(serverId = ServerId("server-1"), source = "reread")
            )
        )
        runCurrent()

        assertEquals(2, captured.size)
        val manualEvent = captured[0].event
        assertEquals(ActivityUnreadFlipCause.OpenReadReverted, manualEvent.cause)
        assertEquals(800L, manualEvent.msSinceOpen)
        assertEquals(2, manualEvent.flipsInChange)

        val autoEvent = captured[1].event
        assertEquals(ActivityUnreadFlipCause.None, autoEvent.cause)
        assertNull(autoEvent.msSinceOpen)
        assertEquals(2, autoEvent.flipsInChange)
    }

    @Test
    fun backgroundRefreshWithNewerSeqEmitsNewerActivityAndPullRefreshEmitsListRefresh() = runTest {
        val initialRow =
            threadItem("thread-1", unreadCount = 0).copy(latestActivitySeq = "10", readStateLatestActivitySeq = "10")
        val newerRow =
            threadItem("thread-1", unreadCount = 3).copy(latestActivitySeq = "12", readStateLatestActivitySeq = "12")
        val readAgainRow = newerRow.copy(unreadCount = 0, firstUnreadMessageId = null)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(initialRow)),
                        snapshot(listOf(newerRow)),
                        snapshot(listOf(readAgainRow)),
                    ),
            )
        val captured = mutableListOf<CapturedFlip>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 500L,
                nowMillis = { testScheduler.currentTime },
                unreadFlipSink = { serverId, event -> captured += CapturedFlip(serverId, event) },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        advanceTimeBy(1_000L)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ActivityChanged(serverId = ServerId("server-1"), source = "bg_newer")
            )
        )
        runCurrent()

        val newerFlip = captured.single().event
        assertEquals(ActivityUnreadFlipDirection.ToUnread, newerFlip.direction)
        assertEquals(ActivityUnreadFlipSource.BackgroundReread, newerFlip.source)
        assertEquals(ActivityUnreadFlipCause.NewerActivity, newerFlip.cause)
        assertEquals(ActivityUnreadFlipRowKind.Thread, newerFlip.rowKind)
        assertEquals(ActivityUnreadSeqChange.Both, newerFlip.seqChanged)
        assertNull(newerFlip.msSinceOpen)

        captured.clear()
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()

        val refreshFlip = captured.single().event
        assertEquals(ActivityUnreadFlipDirection.ToRead, refreshFlip.direction)
        assertEquals(ActivityUnreadFlipSource.ListRefresh, refreshFlip.source)
        assertEquals(ActivityUnreadFlipCause.None, refreshFlip.cause)
        assertEquals(3, refreshFlip.unreadBefore)
        assertEquals(0, refreshFlip.unreadAfter)
    }

    @Test
    fun userActionsMarkReadMarkUnreadAndMarkAllReadEmitNoFlips() = runTest {
        val unreadRow = activityItem("channel-1", unreadCount = 2).copy(latestActivitySeq = "10")
        val readRow = activityItem("channel-2", unreadCount = 0).copy(latestActivitySeq = "10")
        val secondUnread = activityItem("channel-3", unreadCount = 1).copy(latestActivitySeq = "10")
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(unreadRow, readRow, secondUnread)),
                        snapshot(
                            listOf(
                                unreadRow.copy(unreadCount = 0, firstUnreadMessageId = null),
                                readRow.copy(unreadCount = 0, markedUnread = false),
                                secondUnread.copy(unreadCount = 0, firstUnreadMessageId = null),
                            )
                        ),
                    ),
            )
        val readRepo =
            FakeReadReceiptRepository(
                unreadResult = { channelId ->
                    ReadReceiptResult(
                        channelId = channelId,
                        unreadCount = 0,
                        readStateVersion = 2L,
                        maxReadSeq = 10L,
                        markedUnread = true,
                    )
                },
            )
        val captured = mutableListOf<CapturedFlip>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                readReceiptRepository = readRepo,
                nowMillis = { testScheduler.currentTime },
                unreadFlipSink = { serverId, event -> captured += CapturedFlip(serverId, event) },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        // Explicit Mark as Read (not open).
        store.dispatch(SharedActivityIntent.MarkRead(unreadRow, fromOpen = false))
        runCurrent()
        // Explicit Mark as Unread whose answer is Marked (reverts optimistic count to 0 + markedUnread = true).
        store.dispatch(SharedActivityIntent.MarkUnread(readRow))
        runCurrent()
        // Explicit Mark All Read.
        store.dispatch(SharedActivityIntent.MarkAllRead)
        runCurrent()

        assertEquals(emptyList<CapturedFlip>(), captured)
    }

    @Test
    fun coordinatorOpenReadTagsOpenReadWhileExternalPredictionTagsLocalRead() = runTest {
        val row1 = activityItem("channel-1", unreadCount = 2).copy(latestActivitySeq = "20", readStateLatestActivitySeq = "20")
        val row2 = activityItem("channel-2", unreadCount = 1).copy(latestActivitySeq = "30", readStateLatestActivitySeq = "30")
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(row1, row2))))
        val readRepo = FakeReadReceiptRepository()
        val captured = mutableListOf<CapturedFlip>()
        val owner = ReadReceiptOwner(environmentId = "test-env", accountId = "user-1", serverId = "server-1")

        lateinit var store: SharedActivityStore
        val coordinator =
            ReadReceiptCoordinator(
                scope = backgroundScope,
                repository = readRepo,
                onPrediction = { prediction -> store.applyReadReceiptPrediction(prediction) },
            )
        store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                readReceiptRepository = readRepo,
                readReceiptOwnerFactory = { serverId, accountId ->
                    ReadReceiptOwner(environmentId = "test-env", accountId = accountId, serverId = serverId)
                },
                nowMillis = { testScheduler.currentTime },
                unreadFlipSink = { serverId, event -> captured += CapturedFlip(serverId, event) },
            )
        store.updateRuntimeIntegrations(readReceiptCoordinator = coordinator)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        // MarkRead(fromOpen = true) routed through ReadReceiptCoordinator must stay open_read, never local_read.
        store.dispatch(SharedActivityIntent.MarkRead(row1, fromOpen = true))
        runCurrent()
        val openEvent = captured.single().event
        assertEquals(ActivityUnreadFlipDirection.ToRead, openEvent.direction)
        assertEquals(ActivityUnreadFlipSource.OpenRead, openEvent.source)

        // A prediction originating outside Activity (e.g. from an open conversation page) tags local_read.
        captured.clear()
        store.dispatch(
            SharedActivityIntent.ApplyReadReceiptPrediction(
                ReadReceiptPrediction(
                    owner = owner,
                    channelId = ChannelId("channel-2"),
                    revision = 1L,
                    target = ReadReceiptTarget.All,
                )
            )
        )
        runCurrent()
        val localReadEvent = captured.single().event
        assertEquals(ActivityUnreadFlipDirection.ToRead, localReadEvent.direction)
        assertEquals(ActivityUnreadFlipSource.LocalRead, localReadEvent.source)
    }

    @Test
    fun inPlacePatchAndRemoteReadStateTagTheirSourcesAndServerSwitchResetsOpens() = runTest {
        val row = activityItem("channel-1", unreadCount = 0).copy(latestActivitySeq = "10", readStateLatestActivitySeq = "10")
        val otherServerSession =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user-1@slock.local", displayName = "User One"),
                accessToken = "access",
                refreshToken = "refresh",
                selectedServerId = "server-1",
                servers =
                    listOf(
                        StoredAuthServer(id = "server-1", name = "Slock"),
                        StoredAuthServer(id = "server-2", name = "Ops"),
                    ),
            )
        val authStore = InMemoryRaftAuthSessionStore(otherServerSession)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(row), serverId = "server-1"),
                        snapshot(listOf(row.copy(unreadCount = 0)), serverId = "server-2"),
                        snapshot(listOf(row.copy(unreadCount = 1)), serverId = "server-2"),
                    ),
            )
        val captured = mutableListOf<CapturedFlip>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                authStore = authStore,
                realtimeBackgroundRefreshIntervalMillis = 500L,
                nowMillis = { testScheduler.currentTime },
                inboxInPlaceEnabled = { true },
                unreadFlipSink = { serverId, event -> captured += CapturedFlip(serverId, event) },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        // Record an open on server-1.
        store.dispatch(SharedActivityIntent.RowOpened(row))
        runCurrent()

        // Live MessageNew patches unread in place -> in_place_patch + newer_activity.
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("m-11", channelId = "channel-1", authorId = "user-2", seq = 11L))
            )
        )
        runCurrent()
        val patchedFlip = captured.single().event
        assertEquals(ActivityUnreadFlipDirection.ToUnread, patchedFlip.direction)
        assertEquals(ActivityUnreadFlipSource.InPlacePatch, patchedFlip.source)
        assertEquals(ActivityUnreadFlipCause.NewerActivity, patchedFlip.cause)

        // Remote read_state:updated clears unread -> realtime_event.
        captured.clear()
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                PerReceiverReadStateChanged(
                    serverId = ServerId("server-1"),
                    scopes = listOf(PerReceiverReadStateScope(ChannelId("channel-1"), maxReadSeq = 11L, readStateVersion = 2L)),
                )
            )
        )
        runCurrent()
        val remoteReadFlip = captured.single().event
        assertEquals(ActivityUnreadFlipDirection.ToRead, remoteReadFlip.direction)
        assertEquals(ActivityUnreadFlipSource.RealtimeEvent, remoteReadFlip.source)

        // Switching servers resets remembered opens and emits zero flips across the server boundary.
        captured.clear()
        authStore.saveSession(otherServerSession.copy(selectedServerId = "server-2"))
        store.dispatch(SharedActivityIntent.ServerSelected("server-2"))
        runCurrent()
        assertEquals(emptyList<CapturedFlip>(), captured)

        // A later unread flip on server-2 must not attribute to server-1's open.
        advanceTimeBy(1_000L)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ActivityChanged(serverId = ServerId("server-2"), source = "server2_bg")
            )
        )
        runCurrent()
        val server2Flip = captured.single()
        assertEquals("server-2", server2Flip.serverId)
        assertNull(server2Flip.event.msSinceOpen)
        assertEquals(ActivityUnreadFlipCause.None, server2Flip.event.cause)
    }

    @Test
    fun clientTraceSenderRespectsPrivacyModeForUnreadFlipEvent() = runTest {
        val batches = mutableListOf<RaftWebTraceBatchRequest>()
        val transport =
            object : RaftClientTraceTransport {
                override suspend fun currentAccount(): String = "user-1"

                override suspend fun attest(serverId: ServerId): RaftScopeAttestationAnswer =
                    RaftScopeAttestationAnswer(
                        status = 200,
                        response = RaftScopeAttestationResponse(attestation = "att-1", expiresAt = "2026-11-11T10:10:00Z"),
                    )

                override suspend fun send(batch: RaftWebTraceBatchRequest): Int {
                    batches += batch
                    return 200
                }
            }
        val baseEpoch = 1_794_391_200_000L
        val privacy = MutableStateFlow(RaftPrivacyMode.Off)
        val sender =
            RaftClientTraceEventSender(
                scope = backgroundScope,
                nowEpochMillis = { baseEpoch + currentTime },
                defaultAttrs = { mapOf("clientPlatform" to "android") },
                privacyMode = privacy,
                foreground = MutableStateFlow(true),
            ).apply {
                bindTransport(transport)
                setAccount("user-1")
            }
        val event =
            ActivityUnreadStateFlipped(
                direction = ActivityUnreadFlipDirection.ToUnread,
                source = ActivityUnreadFlipSource.BackgroundReread,
                cause = ActivityUnreadFlipCause.OpenReadReverted,
                rowKind = ActivityUnreadFlipRowKind.Channel,
                msSinceOpen = 900L,
                seqChanged = ActivityUnreadSeqChange.None,
                unreadBefore = 0,
                unreadAfter = 1,
                flipsInChange = 1,
            )

        // Privacy mode off -> event flushes under slock.activity.unread_state_flipped.
        sender.emit("server-1", RaftActivityUnreadStateFlippedTraceName, event.toTraceAttrs())
        advanceTimeBy(RaftClientTraceFlushDelayMillis + 1L)
        runCurrent()
        val sentEvent = batches.single().events.single()
        assertEquals(RaftActivityUnreadStateFlippedTraceName, sentEvent.name)
        assertEquals("to_unread", sentEvent.attrs["direction"]?.jsonPrimitive?.content)
        assertEquals("open_read_reverted", sentEvent.attrs["cause"]?.jsonPrimitive?.content)

        // Privacy mode on -> dropped and never sent.
        batches.clear()
        privacy.value = RaftPrivacyMode.On
        runCurrent()
        sender.emit("server-1", RaftActivityUnreadStateFlippedTraceName, event.toTraceAttrs())
        advanceTimeBy(RaftClientTraceFlushDelayMillis + 1L)
        runCurrent()
        assertEquals(0, sender.queuedCount)
        assertTrue(batches.isEmpty())
    }
}
