package build.raft.app.data

import build.raft.app.SharedActivityFooterSlot
import build.raft.app.activityFooterSlots
import build.raft.app.activityTaskBadgeText
import build.raft.app.toConversationSummary
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageConversationContext
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.network.sync.MessageDomainStore
import build.raft.app.network.sync.MessageReadStateCheckpoint
import build.raft.app.network.sync.MessageReadStateProjection
import build.raft.app.network.sync.MessageReceiverKind
import build.raft.app.network.sync.MessageScopeRebaselineToken
import build.raft.app.network.sync.SyncIngestOutcome
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Mirrors the store's private SharedActivityReplyPrewarmConcurrency (Semaphore(2)); the tail reuses
// that same shared Semaphore, so total prewarm concurrency must never exceed it.
private const val SharedActivityReplyPrewarmConcurrencyForTest = 2

class SharedActivityStoreTest {
    @Test
    fun visibleReplyPrewarmDedupesStableMarkerAndRefetchesNewActivityVersion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val store =
            activityStore(
                scope = scope,
                prewarmReplyThread = { marker ->
                    calls += marker
                    DataResult.Content(Unit)
                }
            )
        val first = replyPrewarmMarker(activityVersion = "1:first:latest-1")
        val updated = first.copy(activityVersion = "2:first:latest-2")

        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(first)))
        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(first)))
        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(updated)))

        assertEquals(listOf(first, updated), calls)
        scope.cancel()
    }

    @Test
    fun failedVisibleReplyPrewarmRetriesOnlyAfterViewportReentry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val store =
            activityStore(
                scope = scope,
                prewarmReplyThread = {
                    calls += 1
                    DataResult.Error(ScreenError(code = "offline", retryable = true))
                }
            )
        val marker = replyPrewarmMarker()

        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))
        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))
        assertEquals(1, calls)

        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(emptySet()))
        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))
        assertEquals(2, calls)
        scope.cancel()
    }

    @Test
    fun visibleReplyPrewarmRunsLatestVersionAfterOlderInFlightCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val first = replyPrewarmMarker(activityVersion = "1:first:latest-1")
        val updated = first.copy(activityVersion = "2:first:latest-2")
        val store =
            activityStore(
                scope = scope,
                prewarmReplyThread = { marker ->
                    calls += marker
                    if (marker == first) releaseFirst.await()
                    DataResult.Content(Unit)
                }
            )

        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(first)))
        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(updated)))
        assertEquals(listOf(first), calls)

        releaseFirst.complete(Unit)
        assertEquals(listOf(first, updated), calls)
        scope.cancel()
    }

    @Test
    fun activityTailPrewarmsBelowFoldInTopFirstOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val store =
            activityStore(
                scope = scope,
                prewarmReplyThread = { marker ->
                    calls += marker
                    DataResult.Content(Unit)
                }
            )
        val ordered = (1..4).map { keyedMarker("row-$it") }

        store.dispatch(SharedActivityIntent.PrewarmActivityTail(ordered))

        assertEquals(ordered, calls)
        scope.cancel()
    }

    @Test
    fun activityTailPrewarmHonorsDepthCap() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val store =
            activityStore(
                scope = scope,
                tailPrewarmDepthCap = 3,
                prewarmReplyThread = { marker ->
                    calls += marker
                    DataResult.Content(Unit)
                }
            )
        val ordered = (1..6).map { keyedMarker("row-$it") }

        store.dispatch(SharedActivityIntent.PrewarmActivityTail(ordered))

        assertEquals(ordered.take(3), calls)
        scope.cancel()
    }

    @Test
    fun activityTailPrewarmSkipsVisibleAndCompletedKeys() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val store =
            activityStore(
                scope = scope,
                prewarmReplyThread = { marker ->
                    calls += marker
                    DataResult.Content(Unit)
                }
            )
        val completed = keyedMarker("row-1")
        val visible = keyedMarker("row-2")
        val fresh = keyedMarker("row-3")

        // "completed" warms via the visible path and records completion.
        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(completed)))
        // "visible" stays in the current viewport while the tail runs.
        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(visible)))
        calls.clear()

        store.dispatch(
            SharedActivityIntent.PrewarmActivityTail(listOf(visible, completed, fresh))
        )

        assertEquals(listOf(fresh), calls)
        scope.cancel()
    }

    @Test
    fun activityTailPrewarmNeverExceedsSharedConcurrencyCap() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var inFlight = 0
        var maxInFlight = 0
        val store =
            activityStore(
                scope = scope,
                prewarmReplyThread = {
                    inFlight += 1
                    if (inFlight > maxInFlight) maxInFlight = inFlight
                    inFlight -= 1
                    DataResult.Content(Unit)
                }
            )
        val ordered = (1..12).map { keyedMarker("row-$it") }

        store.dispatch(SharedActivityIntent.PrewarmActivityTail(ordered))

        assertTrue(maxInFlight <= SharedActivityReplyPrewarmConcurrencyForTest)
        scope.cancel()
    }

    @Test
    fun visibleReplyPrewarmTakesPriorityOverActivityTail() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseVisible = CompletableDeferred<Unit>()
        val calls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val firstVisible = keyedMarker("visible-1")
        val secondVisible = keyedMarker("visible-2")
        val tail = keyedMarker("tail-1")
        val store =
            activityStore(
                scope = scope,
                prewarmReplyThread = { marker ->
                    calls += marker
                    if (marker == firstVisible || marker == secondVisible) releaseVisible.await()
                    DataResult.Content(Unit)
                }
            )

        // Two visible markers saturate the shared Semaphore(2).
        store.dispatch(
            SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(firstVisible, secondVisible))
        )
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(tail)))

        // Tail cannot acquire a permit while the visible set holds both.
        assertEquals(setOf(firstVisible, secondVisible), calls.toSet())
        assertTrue(tail !in calls)

        releaseVisible.complete(Unit)
        assertTrue(tail in calls)
        scope.cancel()
    }

    @Test
    fun activityTailPrewarmCancelsKeysDroppedFromOrderedSet() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val cancelled = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val dropped = keyedMarker("dropped")
        val kept = keyedMarker("kept")
        val store =
            activityStore(
                scope = scope,
                prewarmReplyThread = { marker ->
                    started += marker
                    if (marker == dropped) {
                        try {
                            awaitCancellation()
                        } catch (cancellation: CancellationException) {
                            cancelled += marker
                            throw cancellation
                        }
                    }
                    DataResult.Content(Unit)
                }
            )

        // "dropped" is warmed first (top of the list) and stays in-flight.
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(dropped, kept)))
        assertEquals(listOf(dropped), started)

        // New ordered set no longer contains "dropped" -> its in-flight job is cancelled.
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(kept)))

        assertEquals(listOf(dropped), cancelled)
        assertTrue(kept in started)
        scope.cancel()
    }

    @Test
    fun activityTailReplanReschedulesInFlightItemWithoutExceedingOneActive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gates = mutableMapOf<ActivityReplyThreadPrewarmMarker, CompletableDeferred<Unit>>()
        val calls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        var active = 0
        var maxActive = 0
        val store =
            activityStore(
                scope = scope,
                prewarmReplyThread = { marker ->
                    calls += marker
                    active += 1
                    if (active > maxActive) maxActive = active
                    try {
                        gates.getOrPut(marker) { CompletableDeferred() }.await()
                    } finally {
                        active -= 1
                    }
                    DataResult.Content(Unit)
                }
            )
        val firstVersion = keyedMarker("A", activityVersion = "v1")
        val secondVersion = keyedMarker("A", activityVersion = "v2")
        val below = keyedMarker("B")

        // A(v1) is warmed at the top of the tail and suspends in-flight on its gate.
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(firstVersion)))
        assertEquals(listOf(firstVersion), calls)

        // Replan while A(v1) is still in-flight: A is re-versioned to v2 and B appears below it.
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(secondVersion, below)))

        // The superseded A(v1) is unwound; the tail then reschedules A(v2) and warms B, one at a
        // time. Release the gates so the serial tail can drain.
        gates.getOrPut(secondVersion) { CompletableDeferred() }.complete(Unit)
        gates.getOrPut(below) { CompletableDeferred() }.complete(Unit)

        // A(v2) must be rescheduled (no permanent warm miss) and B must be warmed.
        assertTrue(secondVersion in calls)
        assertTrue(below in calls)
        // The tail never runs more than one prewarm concurrently across the replan.
        assertTrue(maxActive <= 1)
        // A(v1)'s stale result is discarded; only the latest version is recorded as warmed.
        assertEquals(1, calls.count { it == firstVersion })
        scope.cancel()
    }

    @Test
    fun activityTailRapidReplansSerializeBehindSlowDrainingJob() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val running = CompletableDeferred<Unit>()
        val drainGate = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        val firstVersion = keyedMarker("A", activityVersion = "v1")
        val store =
            activityStore(
                scope = scope,
                prewarmReplyThread = { marker ->
                    calls += marker
                    active += 1
                    if (active > maxActive) maxActive = active
                    try {
                        if (marker == firstVersion) {
                            running.complete(Unit)
                            try {
                                awaitCancellation()
                            } catch (cancellation: CancellationException) {
                                // Simulate a SLOW drain: the cancelled job stays alive (and still
                                // counts as active) until the drain gate releases.
                                withContext(NonCancellable) { drainGate.await() }
                                throw cancellation
                            }
                        }
                    } finally {
                        active -= 1
                    }
                    DataResult.Content(Unit)
                }
            )
        val secondVersion = keyedMarker("A", activityVersion = "v2")
        val secondBelow = keyedMarker("B")
        val thirdVersion = keyedMarker("A", activityVersion = "v3")
        val thirdBelow = keyedMarker("C")

        // A(v1) is warmed at the top of the tail and is now in-flight.
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(firstVersion)))
        running.await()
        assertEquals(listOf(firstVersion), calls)

        // Two rapid consecutive replans arrive while A(v1) is still SLOWLY draining.
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(secondVersion, secondBelow)))
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(thirdVersion, thirdBelow)))

        // The drain barrier holds every later generation: nothing new may start while A(v1) drains.
        assertEquals(listOf(firstVersion), calls)
        assertTrue(maxActive <= 1)

        // Once the slow drain finishes, only the LATEST generation (v3) runs, one item at a time.
        drainGate.complete(Unit)

        assertTrue(thirdVersion in calls)
        assertTrue(thirdBelow in calls)
        // The superseded v2 generation never launched (its loop was cancelled before it ran).
        assertTrue(secondVersion !in calls)
        assertTrue(secondBelow !in calls)
        // Concurrency never exceeded one across the two consecutive replans and the drain.
        assertTrue(maxActive <= 1)
        scope.cancel()
    }

    @Test
    fun clearSummaryDropsLoadedActivityCountsButKeepsFilter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 2)), totalUnreadCount = 2)))
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        assertEquals(2, store.state.value.totalUnreadCount)

        store.dispatch(SharedActivityIntent.ClearSummary)

        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter)
        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(false, store.state.value.loaded)
        scope.cancel()
    }

    @Test
    fun loadMoreMergesWithoutDuplicateRows() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1"), activityItem("channel-2")), hasMore = true, nextOffset = 2, totalCount = 3),
                    ),
                moreSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-2"), activityItem("channel-3")), hasMore = false, nextOffset = 4, totalCount = 3)
                    )
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.LoadMore)

        assertEquals(listOf("channel:channel-1", "channel:channel-2", "channel:channel-3"), store.state.value.items.map { it.key })
        assertEquals(listOf(2), inbox.loadMoreOffsets)
        assertEquals(false, store.state.value.hasMore)
        scope.cancel()
    }

    @Test
    fun loadInitialDedupesRowsWithSameActivityKey() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val duplicateFirst = activityItem("channel-1", unreadCount = 1)
        val duplicateSecond =
            activityItem("channel-1", unreadCount = 2)
                .copy(
                    firstUnreadMessageId = MessageId("first-channel-1-duplicate"),
                    latestActivityMessageId = MessageId("latest-channel-1-duplicate")
                )
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(
                            listOf(
                                duplicateFirst,
                                duplicateSecond,
                                activityItem("channel-2", unreadCount = 1),
                                activityItem("channel-2", unreadCount = 1)
                            ),
                            totalCount = 4,
                            totalUnreadCount = 5
                        )
                    )
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(listOf("channel:channel-1", "channel:channel-2"), store.state.value.items.map { it.key })
        assertEquals(MessageId("latest-channel-1"), store.state.value.items.first().latestActivityMessageId)
        assertEquals(4, store.state.value.totalCount)
        assertEquals(5, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun unreadFilterMarkReadRemovesRowAndCallsReadAll() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 2)), totalUnreadCount = 2)))
        val read = FakeReadReceiptRepository()
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(homeSnapshot(activityItem("channel-1", unreadCount = 2).toConversationSummary(), unreadTotal = 2))
        val store =
            activityStore(
                scope,
                inbox,
                readReceiptRepository = read,
            )

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))

        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(listOf(ChannelId("channel-1")), read.readAllChannelIds)
        assertEquals(2, localStore.readHome(ServerId("server-1"))?.channels?.single()?.unreadCount)
        assertEquals(2, localStore.readHome(ServerId("server-1"))?.unreadTotal)
        scope.cancel()
    }

    @Test
    fun staleActivityRefreshAfterMarkReadDoesNotRestoreUnread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)
        val unreadRow = activityItem("channel-1", unreadCount = 2)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(unreadRow), totalUnreadCount = 2))
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        inbox.completeNextSnapshot(snapshot(listOf(unreadRow), totalUnreadCount = 2))

        val row = store.state.value.items.single()
        assertEquals(0, row.unreadCount)
        assertEquals(null, row.firstUnreadMessageId)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun staleUnreadFilterRefreshAfterMarkReadDoesNotRestoreRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)
        val unreadRow = activityItem("channel-1", unreadCount = 2)

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        inbox.completeNextSnapshot(snapshot(listOf(unreadRow), totalCount = 1, totalUnreadCount = 2))
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        inbox.completeNextSnapshot(snapshot(listOf(unreadRow), totalCount = 1, totalUnreadCount = 2))

        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun markReadAllowsLaterUnreadWithDifferentAnchor() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)
        val unreadRow = activityItem("channel-1", unreadCount = 1)
        val laterUnreadRow =
            unreadRow.copy(
                unreadCount = 1,
                firstUnreadMessageId = MessageId("first-channel-1-new")
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(unreadRow), totalUnreadCount = 1))
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        inbox.completeNextSnapshot(snapshot(listOf(laterUnreadRow), totalUnreadCount = 1))

        val row = store.state.value.items.single()
        assertEquals(1, row.unreadCount)
        assertEquals(MessageId("first-channel-1-new"), row.firstUnreadMessageId)
        assertEquals(1, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun markUnreadCallsUnreadEndpointAndRefreshes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val read = FakeReadReceiptRepository()
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 0)), totalUnreadCount = 0),
                        snapshot(listOf(activityItem("channel-1", unreadCount = 1)), totalUnreadCount = 1)
                    )
            )
        val store = activityStore(scope, inbox, readReceiptRepository = read)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.MarkUnread(store.state.value.items.single()))

        assertEquals(listOf(ChannelId("channel-1")), read.unreadChannelIds)
        assertEquals(1, store.state.value.items.single().unreadCount)
        assertEquals(1, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun markAllReadRequestsUnreadCountRefreshAfterCommit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val read = FakeReadReceiptRepository()
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 1)), totalUnreadCount = 1),
                        snapshot(emptyList(), totalUnreadCount = 0)
                    )
            )
        var unreadRefreshRequests = 0
        val store =
            activityStore(
                scope,
                inbox,
                readReceiptRepository = read,
                onUnreadCountsChanged = { unreadRefreshRequests += 1 }
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.MarkAllRead)

        assertEquals(listOf(ServerId("server-1")), read.inboxReadAllServerIds)
        assertEquals(1, unreadRefreshRequests)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(2, inbox.initialLoadCount)
        scope.cancel()
    }

    @Test
    fun markAllReadKeepsRowsAndClearsCountsInAllFilter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pendingReadAll = CompletableDeferred<DataResult<ReadReceiptResult>>()
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 3)), totalCount = 8, totalUnreadCount = 3)
                    )
            )
        val store =
            activityStore(
                scope,
                inbox,
                readReceiptRepository = FakeReadReceiptRepository(pendingInboxReadAllResult = pendingReadAll)
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.MarkAllRead)

        val item = store.state.value.items.single()
        assertEquals(0, item.unreadCount)
        assertEquals(null, item.firstUnreadMessageId)
        assertEquals(false, item.hasMention)
        assertEquals(8, store.state.value.totalCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        pendingReadAll.complete(DataResult.Content(ReadReceiptResult()))
        scope.cancel()
    }

    @Test
    fun markAllReadRemovesRowsInUnreadFilter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pendingReadAll = CompletableDeferred<DataResult<ReadReceiptResult>>()
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 3)), totalCount = 1, totalUnreadCount = 3)
                    )
            )
        val store =
            activityStore(
                scope,
                inbox,
                readReceiptRepository = FakeReadReceiptRepository(pendingInboxReadAllResult = pendingReadAll)
            )

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        store.dispatch(SharedActivityIntent.MarkAllRead)

        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        pendingReadAll.complete(DataResult.Content(ReadReceiptResult()))
        scope.cancel()
    }

    @Test
    fun markAllReadRemovesRowsInMentionsFilter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pendingReadAll = CompletableDeferred<DataResult<ReadReceiptResult>>()
        val row = activityItem("channel-1", unreadCount = 2).copy(hasMention = true)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(row), totalCount = 1, totalUnreadCount = 2)
                    )
            )
        val store =
            activityStore(
                scope,
                inbox,
                readReceiptRepository = FakeReadReceiptRepository(pendingInboxReadAllResult = pendingReadAll)
            )

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Mentions))
        store.dispatch(SharedActivityIntent.MarkAllRead)

        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        pendingReadAll.complete(DataResult.Content(ReadReceiptResult()))
        scope.cancel()
    }

    @Test
    fun activitySilentRefreshDoesNotDrivePullRefreshState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNext(activityItem("channel-1"))

        store.dispatch(SharedActivityIntent.Refresh)

        assertEquals(false, store.state.value.loading)
        assertEquals(true, store.state.value.refreshing)
        assertEquals(false, store.state.value.pullRefreshing)
        inbox.completeNext(activityItem("channel-2"))

        store.dispatch(SharedActivityIntent.PullRefresh)

        assertEquals(false, store.state.value.loading)
        assertEquals(true, store.state.value.refreshing)
        assertEquals(true, store.state.value.pullRefreshing)
        inbox.completeNext(activityItem("channel-3"))
        assertEquals(false, store.state.value.refreshing)
        assertEquals(false, store.state.value.pullRefreshing)
        scope.cancel()
    }

    @Test
    fun scopedRealtimeRefreshesActivitySummaryCounts() = runBlocking {
        val refreshScopes =
            listOf(
                RealtimeRefreshScope.Home,
                RealtimeRefreshScope.ChannelMembers,
                RealtimeRefreshScope.ServerMembers,
                RealtimeRefreshScope.Agents,
                RealtimeRefreshScope.Machines,
                RealtimeRefreshScope.Settings
            )

        refreshScopes.forEachIndexed { index, refreshScope ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val inbox =
                FakeInboxRepository(
                    initialSnapshots =
                        listOf(
                            snapshot(listOf(activityItem("channel-$index", unreadCount = index + 1)), totalUnreadCount = index + 1),
                            snapshot(listOf(activityItem("channel-$index", unreadCount = index + 2)), totalUnreadCount = index + 2)
                        )
                )
            val store = activityStore(scope, inbox)

            store.dispatch(SharedActivityIntent.LoadInitial)
            store.dispatch(
                SharedActivityIntent.ApplyRealtimeEvent(
                    RealtimeRefreshRequired(
                        scope = refreshScope,
                        reason = "test"
                    )
                )
            )

            assertEquals(2, inbox.initialLoadCount, "scope=$refreshScope")
            assertEquals(index + 2, store.state.value.totalUnreadCount, "scope=$refreshScope")
            scope.cancel()
        }
    }

    @Test
    fun announcementRealtimeDoesNotRefreshActivitySummaryCounts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 1)), totalUnreadCount = 1),
                        snapshot(listOf(activityItem("channel-1", unreadCount = 2)), totalUnreadCount = 2)
                    )
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                RealtimeRefreshRequired(
                    scope = RealtimeRefreshScope.Announcements,
                    reason = "announcement:new"
                )
            )
        )

        assertEquals(1, inbox.initialLoadCount)
        assertEquals(1, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun readStateFactWithoutEstablishedOwnerFailsClosedUntilCanonicalLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 0)), totalUnreadCount = 0))
            )
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        var homeReconcileRequests = 0
        val store =
            activityStore(
                scope,
                inboxRepository = inbox,
                perReceiverStateVersionStore = versionStore,
                onUnreadCountsChanged = { homeReconcileRequests += 1 }
            )

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-1", version = 1, maxReadSeq = 10)
            )
        )
        store.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(1, inbox.initialLoadCount)
        assertEquals(0, homeReconcileRequests)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(
            build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 0, maxReadSeq = 0),
            versionStore.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1"))
        )
        scope.cancel()
    }

    @Test
    fun acceptedReadStateUsesGlobalProjectionAndFencesStaleInboxReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stale = activityItem("channel-1", unreadCount = 3).copy(hasMention = false)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(stale)), snapshot(listOf(stale))))
        val domain =
            FakeMessageDomainStore { _, checkpoint ->
                MessageReadStateProjection(
                    readStateVersion = checkpoint.readStateVersion,
                    maxReadSeq = checkpoint.maxReadSeq,
                    unreadCount = 1,
                    hasMention = true,
                    firstUnreadMessageId = "message-31",
                    firstMentionMessageId = "message-31",
                    complete = true,
                )
            }
        val store = activityStore(scope, inboxRepository = inbox, messageDomainStore = domain)
        store.dispatch(SharedActivityIntent.LoadInitial)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-1", version = 4, maxReadSeq = 30)
            )
        )

        assertEquals(2, inbox.initialLoadCount)
        assertEquals(1, store.state.value.totalUnreadCount)
        assertEquals(1, store.state.value.items.single().unreadCount)
        assertTrue(store.state.value.items.single().hasMention)
        assertEquals(MessageId("message-31"), store.state.value.items.single().firstUnreadMessageId)
        scope.cancel()
    }

    @Test
    fun incompleteGlobalProjectionHydratesOnlyAffectedScopeThenProjects() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = activityItem("channel-1", unreadCount = 3)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(initial)), snapshot(listOf(initial))))
        val conversation = FakeConversationRepository()
        var projections = 0
        val domain =
            FakeMessageDomainStore { _, checkpoint ->
                projections += 1
                MessageReadStateProjection(
                    readStateVersion = checkpoint.readStateVersion,
                    maxReadSeq = checkpoint.maxReadSeq,
                    unreadCount = 0,
                    hasMention = false,
                    firstUnreadMessageId = null,
                    firstMentionMessageId = null,
                    complete = projections > 1,
                )
            }
        val store =
            activityStore(
                scope,
                inboxRepository = inbox,
                conversationRepository = conversation,
                messageDomainStore = domain,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-1", version = 2, maxReadSeq = 30)
            )
        )

        assertEquals(listOf(ThreadId("channel-1")), conversation.loadedThreadIds)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(0, store.state.value.items.single().unreadCount)
        scope.cancel()
    }

    @Test
    fun sameServerUserSwitchDropsPausedProjectionAndDoesNotReplayItOnNewOwner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val hydrateEntered = CompletableDeferred<Unit>()
        val releaseHydrate = CompletableDeferred<Unit>()
        val conversation = FakeConversationRepository(threadGate = releaseHydrate, threadEntered = hydrateEntered)
        var projections = 0
        val domain =
            FakeMessageDomainStore { _, checkpoint ->
                projections += 1
                MessageReadStateProjection(
                    readStateVersion = checkpoint.readStateVersion,
                    maxReadSeq = checkpoint.maxReadSeq,
                    unreadCount = 0,
                    hasMention = false,
                    firstUnreadMessageId = null,
                    firstMentionMessageId = null,
                    complete = projections > 1,
                )
            }
        val store =
            activityStore(
                scope = scope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 3))))),
                conversationRepository = conversation,
                messageDomainStore = domain,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readStateEvent("channel-1", version = 3, maxReadSeq = 30)))
        hydrateEntered.await()

        val serverBInbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 7)), totalUnreadCount = 7))
            )
        store.updateRuntimeIntegrations(
            authStore = InMemorySlockAuthSessionStore(sampleSession(userId = "user-2", serverId = "server-1")),
            inboxRepository = serverBInbox,
            readReceiptRepository = FakeReadReceiptRepository(),
            conversationRepository = conversation,
            messageDomainStore = domain,
        )
        store.dispatch(SharedActivityIntent.LoadInitial)
        assertEquals("server-1", store.state.value.ownerServerId)
        assertEquals("user-2", store.state.value.ownerUserId)
        assertEquals(7, store.state.value.totalUnreadCount)

        releaseHydrate.complete(Unit)
        store.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals("server-1", store.state.value.ownerServerId)
        assertEquals("user-2", store.state.value.ownerUserId)
        assertEquals(7, store.state.value.totalUnreadCount)
        assertEquals(7, store.state.value.items.single().unreadCount)
        scope.cancel()
    }

    @Test
    fun staleDispatchOwnerTokenCannotAttributeUserAReadStateToUserB() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        var projectionCalls = 0
        val domain =
            FakeMessageDomainStore { _, checkpoint ->
                projectionCalls += 1
                MessageReadStateProjection(
                    readStateVersion = checkpoint.readStateVersion,
                    maxReadSeq = checkpoint.maxReadSeq,
                    unreadCount = 0,
                    hasMention = false,
                    firstUnreadMessageId = null,
                    firstMentionMessageId = null,
                    complete = true,
                )
            }
        val store =
            activityStore(
                scope = scope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 3))))),
                perReceiverStateVersionStore = versionStore,
                messageDomainStore = domain,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        val userAToken = requireNotNull(store.state.value.realtimeOwnerTokenFor("server-1"))

        val userBInbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 7)), totalUnreadCount = 7))
            )
        store.updateRuntimeIntegrations(
            authStore = InMemorySlockAuthSessionStore(sampleSession(userId = "user-2", serverId = "server-1")),
            inboxRepository = userBInbox,
            readReceiptRepository = FakeReadReceiptRepository(),
            conversationRepository = FakeConversationRepository(),
            messageDomainStore = domain,
        )
        store.dispatch(SharedActivityIntent.LoadInitial)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                event = readStateEvent("channel-1", version = 4, maxReadSeq = 40),
                ownerToken = userAToken,
            )
        )

        assertEquals(
            build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 0, maxReadSeq = 0),
            versionStore.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-2"), ChannelId("channel-1"))
        )
        assertEquals(0, projectionCalls)
        assertEquals(1, userBInbox.initialLoadCount)
        assertEquals(7, store.state.value.totalUnreadCount)
        assertEquals(7, store.state.value.items.single().unreadCount)
        scope.cancel()
    }

    @Test
    fun resetDoesNotReuseSamePrincipalOwnerGenerationOrAcceptOldEvent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 3))),
                        snapshot(listOf(activityItem("channel-1", unreadCount = 3))),
                    )
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                perReceiverStateVersionStore = versionStore,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        val oldToken = requireNotNull(store.state.value.realtimeOwnerTokenFor("server-1"))

        store.reset()
        store.dispatch(SharedActivityIntent.LoadInitial)
        val newToken = requireNotNull(store.state.value.realtimeOwnerTokenFor("server-1"))
        assertTrue(newToken.generation > oldToken.generation)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                event = readStateEvent("channel-1", version = 4, maxReadSeq = 40),
                ownerToken = oldToken,
            )
        )

        assertEquals(
            build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 0, maxReadSeq = 0),
            versionStore.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1"))
        )
        assertEquals(3, store.state.value.items.single().unreadCount)
        scope.cancel()
    }

    @Test
    fun exactObservedReadStateProjectsActivityOnceWithoutDuplicateReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 0))))
            )
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        versionStore.writeReadStateCheckpoint(
            serverId = ServerId("server-1"),
            memberId = MemberId("user-1"),
            scopeId = ChannelId("channel-1"),
            checkpoint = build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 3, maxReadSeq = 30),
        )
        val store = activityStore(scope, inboxRepository = inbox, perReceiverStateVersionStore = versionStore)
        val event = readStateEvent(scopeId = "channel-1", version = 3, maxReadSeq = 30)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(event))
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(event))

        assertEquals(2, inbox.initialLoadCount)
        scope.cancel()
    }

    @Test
    fun completeGlobalProjectionClearsVisibleChannelAndSurvivesCanonicalReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = activityItem("channel-1", unreadCount = 3)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(initial), totalUnreadCount = 3)))
        val store = activityStore(scope, inboxRepository = inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-1", version = 4, maxReadSeq = 30)
            )
        )

        assertEquals(2, inbox.initialLoadCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(0, store.state.value.items.single().unreadCount)
        assertEquals(null, store.state.value.items.single().firstUnreadMessageId)
        scope.cancel()
    }

    @Test
    fun completeGlobalProjectionIsVisibleBeforeCanonicalReloadReturns() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inboxRepository = inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(
            snapshot(
                listOf(
                    activityItem(
                        "channel-1",
                        unreadCount = 3,
                    )
                )
            )
        )
        awaitState(store) { state -> state.loaded && state.totalUnreadCount == 3 }

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-1", version = 4, maxReadSeq = 30)
            )
        )

        awaitPendingCount(inbox, 1)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(0, store.state.value.items.single().unreadCount)
        scope.cancel()
    }

    @Test
    fun completeGlobalProjectionClearsVisibleThreadAndSurvivesCanonicalReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = threadItem("thread-1", unreadCount = 2)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(initial)), snapshot(listOf(initial))))
        val store = activityStore(scope, inboxRepository = inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "thread-1", version = 2, maxReadSeq = 20)
            )
        )

        assertEquals(2, inbox.initialLoadCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(0, store.state.value.items.single().unreadCount)
        scope.cancel()
    }

    @Test
    fun equalVersionConflictingCursorFailsClosedWithoutActivityRefreshOrHomeNotify() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 2)), totalUnreadCount = 2),
                        snapshot(listOf(activityItem("channel-1", unreadCount = 0)), totalUnreadCount = 0)
                    )
            )
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        versionStore.writeReadStateCheckpoint(
            serverId = ServerId("server-1"),
            memberId = MemberId("user-1"),
            scopeId = ChannelId("channel-1"),
            checkpoint = build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 3, maxReadSeq = 30)
        )
        var homeReconcileRequests = 0
        val store =
            activityStore(
                scope,
                inboxRepository = inbox,
                perReceiverStateVersionStore = versionStore,
                onUnreadCountsChanged = { homeReconcileRequests += 1 }
            )
        store.dispatch(SharedActivityIntent.LoadInitial)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-1", version = 3, maxReadSeq = 29)
            )
        )

        assertEquals(1, inbox.initialLoadCount)
        assertEquals(0, homeReconcileRequests)
        assertEquals(2, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun staleReadStateFactDoesNotRefreshActivity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 2)), totalUnreadCount = 2),
                        snapshot(listOf(activityItem("channel-1", unreadCount = 0)), totalUnreadCount = 0)
                    )
            )
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        versionStore.writeReadStateCheckpoint(
            serverId = ServerId("server-1"),
            memberId = MemberId("user-1"),
            scopeId = ChannelId("channel-1"),
            checkpoint = build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 5, maxReadSeq = 50)
        )
        val store = activityStore(scope, inboxRepository = inbox, perReceiverStateVersionStore = versionStore)
        store.dispatch(SharedActivityIntent.LoadInitial)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-1", version = 4, maxReadSeq = 40)
            )
        )

        assertEquals(1, inbox.initialLoadCount)
        assertEquals(2, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun higherVersionReadStateRewindReconcilesActivity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 0)), totalUnreadCount = 0),
                        snapshot(listOf(activityItem("channel-1", unreadCount = 2)), totalUnreadCount = 2)
                    )
            )
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        versionStore.writeReadStateCheckpoint(
            serverId = ServerId("server-1"),
            memberId = MemberId("user-1"),
            scopeId = ChannelId("channel-1"),
            checkpoint = build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 5, maxReadSeq = 50)
        )
        val domain =
            FakeMessageDomainStore { _, checkpoint ->
                MessageReadStateProjection(
                    readStateVersion = checkpoint.readStateVersion,
                    maxReadSeq = checkpoint.maxReadSeq,
                    unreadCount = 2,
                    hasMention = true,
                    firstUnreadMessageId = "rewind-21",
                    firstMentionMessageId = "rewind-21",
                    complete = true,
                )
            }
        val store =
            activityStore(
                scope,
                inboxRepository = inbox,
                perReceiverStateVersionStore = versionStore,
                messageDomainStore = domain,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-1", version = 6, maxReadSeq = 20)
            )
        )

        assertEquals(2, inbox.initialLoadCount)
        assertEquals(2, store.state.value.totalUnreadCount)
        assertTrue(store.state.value.items.single().hasMention)
        assertEquals(MessageId("rewind-21"), store.state.value.items.single().firstUnreadMessageId)
        assertEquals(
            build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 6, maxReadSeq = 20),
            versionStore.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1"))
        )
        scope.cancel()
    }

    @Test
    fun readStateBulkScopesFoldIndependentlyAndScheduleOneReconcile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(emptyList())))
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        versionStore.writeReadStateCheckpoint(
            serverId = ServerId("server-1"),
            memberId = MemberId("user-1"),
            scopeId = ChannelId("channel-a"),
            checkpoint = build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 5, maxReadSeq = 50)
        )
        val store = activityStore(scope, inboxRepository = inbox, perReceiverStateVersionStore = versionStore)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                PerReceiverReadStateChanged(
                    serverId = ServerId("server-1"),
                    scopes =
                        listOf(
                            PerReceiverReadStateScope(ChannelId("channel-a"), maxReadSeq = 40, readStateVersion = 4),
                            PerReceiverReadStateScope(ChannelId("channel-b"), maxReadSeq = 7, readStateVersion = 1)
                        )
                )
            )
        )

        assertEquals(2, inbox.initialLoadCount)
        assertEquals(
            build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 5, maxReadSeq = 50),
            versionStore.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-a"))
        )
        assertEquals(
            build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 1, maxReadSeq = 7),
            versionStore.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-b"))
        )
        scope.cancel()
    }

    @Test
    fun messageNewPatchesExistingActivityRowImmediatelyAndDedupesByMessageId() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(
                            listOf(threadItem("thread-channel-1", unreadCount = 1).copy(replyCount = 2)),
                            totalCount = 1,
                            totalUnreadCount = 1
                        )
                    )
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeReconcileDelayMillis = 60_000L
            )
        val event =
            MessageNew(
                message(
                    id = "message-new",
                    channelId = "thread-channel-1",
                    body = "Realtime preview",
                    timestamp = "2026-07-13T03:01:00Z",
                    conversationContext = MessageConversationContext(channelKind = ConversationKind.Thread)
                )
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(event))

        val patched = store.state.value.items.single()
        assertEquals(MessageId("message-new"), patched.latestActivityMessageId)
        assertEquals("Realtime preview", patched.latestActivityPreview)
        assertEquals("User user-2", patched.latestActivitySenderName)
        assertEquals(3, patched.replyCount)
        assertEquals(1, patched.unreadCount)
        assertEquals(1, store.state.value.totalUnreadCount)
        assertEquals(1, inbox.initialLoadCount)

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(event))

        assertEquals(3, store.state.value.items.single().replyCount)
        assertEquals(1, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun threadMessagePatchUpdatesAndReordersExistingThreadRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val thread = threadItem("thread-channel-1", unreadCount = 1).copy(replyCount = 3)
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots =
                            listOf(
                                snapshot(
                                    listOf(activityItem("channel-1"), thread),
                                    totalCount = 2,
                                    totalUnreadCount = 1
                                )
                            )
                    ),
                realtimeReconcileDelayMillis = 60_000L
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "reply-new",
                        channelId = "thread-channel-1",
                        body = "New reply",
                        timestamp = "2026-07-13T03:02:00Z",
                        conversationContext =
                            MessageConversationContext(
                                channelKind = ConversationKind.Thread,
                                parentMessageId = MessageId("parent-message"),
                                parentChannelId = ChannelId("parent-channel"),
                                parentChannelKind = ConversationKind.Channel
                            )
                    )
                )
            )
        )

        val patched = store.state.value.items.first()
        assertEquals("thread:thread-channel-1", patched.key)
        assertEquals(MessageId("reply-new"), patched.latestActivityMessageId)
        assertEquals("New reply", patched.latestActivityPreview)
        assertEquals(4, patched.replyCount)
        assertEquals(1, patched.unreadCount)
        scope.cancel()
    }

    @Test
    fun threadUpdatedPatchesOnlyMonotonicReplyActivityMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots =
                            listOf(
                                snapshot(
                                    listOf(
                                        threadItem("thread-channel-1", unreadCount = 1)
                                            .copy(replyCount = 2, latestActivityAt = "2026-07-13T03:00:00Z")
                                    ),
                                    totalUnreadCount = 1
                                )
                            )
                    ),
                realtimeReconcileDelayMillis = 60_000L
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("parent-message"),
                    threadChannelId = ChannelId("thread-channel-1"),
                    replyCount = 3,
                    lastReplyAt = "2026-07-13T03:05:00Z",
                    participantIds = emptyList(),
                    unreadCount = 9,
                    firstUnreadMessageId = MessageId("server-first-unread")
                )
            )
        )

        val patched = store.state.value.items.single()
        assertEquals(3, patched.replyCount)
        assertEquals("2026-07-13T03:05:00Z", patched.latestActivityAt)
        assertEquals(1, patched.unreadCount)
        assertEquals(MessageId("first-thread-channel-1"), patched.firstUnreadMessageId)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("parent-message"),
                    threadChannelId = ChannelId("thread-channel-1"),
                    replyCount = 2,
                    lastReplyAt = "2026-07-13T03:01:00Z",
                    participantIds = emptyList()
                )
            )
        )

        assertEquals(3, store.state.value.items.single().replyCount)
        assertEquals("2026-07-13T03:05:00Z", store.state.value.items.single().latestActivityAt)
        scope.cancel()
    }

    @Test
    fun unknownMessageRowFallsBackToCanonicalRealtimeReconcile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1"))),
                        snapshot(listOf(activityItem("channel-2"), activityItem("channel-1")))
                    )
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeReconcileDelayMillis = 0L
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-new", channelId = "channel-2"))
            )
        )

        assertEquals(2, inbox.initialLoadCount)
        assertEquals(listOf("channel:channel-2", "channel:channel-1"), store.state.value.items.map { it.key })
        scope.cancel()
    }

    @Test
    fun messagePatchSurvivesStaleInFlightRefreshAndTriggersFollowUpReconcile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeReconcileDelayMillis = 0L
            )
        val stale = threadItem("thread-channel-1", unreadCount = 1).copy(replyCount = 2)
        val canonical =
            stale.copy(
                latestActivityMessageId = MessageId("message-new"),
                latestActivityPreview = "Realtime preview",
                latestActivityAt = "2026-07-13T03:03:00Z",
                replyCount = 3
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 1))
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "message-new",
                        channelId = "thread-channel-1",
                        body = "Realtime preview",
                        timestamp = "2026-07-13T03:03:00Z",
                        conversationContext = MessageConversationContext(channelKind = ConversationKind.Thread)
                    )
                )
            )
        )

        assertEquals(MessageId("message-new"), store.state.value.items.single().latestActivityMessageId)
        assertEquals(3, store.state.value.items.single().replyCount)

        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 1))

        assertEquals(MessageId("message-new"), store.state.value.items.single().latestActivityMessageId)
        assertEquals(3, store.state.value.items.single().replyCount)
        assertEquals(1, inbox.pendingCount)

        inbox.completeNextSnapshot(snapshot(listOf(canonical), totalUnreadCount = 1))

        assertEquals(MessageId("message-new"), store.state.value.items.single().latestActivityMessageId)
        assertEquals(false, store.state.value.refreshing)
        assertEquals(0, inbox.pendingCount)
        scope.cancel()
    }

    @Test
    fun realtimeHighWaterPreservesLocallyClearedUnreadState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeReconcileDelayMillis = 60_000L
            )
        val stale = threadItem("thread-channel-1", unreadCount = 2).copy(replyCount = 2)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 2))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "message-new",
                        channelId = "thread-channel-1",
                        body = "Realtime preview",
                        timestamp = "2026-07-13T03:03:00Z",
                        conversationContext = MessageConversationContext(channelKind = ConversationKind.Thread)
                    )
                )
            )
        )
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        store.dispatch(SharedActivityIntent.Refresh)
        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 2))

        val reconciled = store.state.value.items.single()
        assertEquals(MessageId("message-new"), reconciled.latestActivityMessageId)
        assertEquals("Realtime preview", reconciled.latestActivityPreview)
        assertEquals(3, reconciled.replyCount)
        assertEquals(0, reconciled.unreadCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun readStateCanonicalClearSurvivesRealtimeThreadHighWater() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeReconcileDelayMillis = 60_000L
            )
        val stale = threadItem("thread-channel-1", unreadCount = 2).copy(replyCount = 2)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 2))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "message-new",
                        channelId = "thread-channel-1",
                        body = "Realtime preview",
                        timestamp = "2026-07-13T03:03:00Z",
                        conversationContext = MessageConversationContext(channelKind = ConversationKind.Thread)
                    )
                )
            )
        )

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "thread-channel-1", version = 1, maxReadSeq = 10)
            )
        )
        inbox.completeNextSnapshot(
            snapshot(
                listOf(stale.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)),
                totalUnreadCount = 0
            )
        )

        val reconciled = store.state.value.items.single()
        assertEquals(MessageId("message-new"), reconciled.latestActivityMessageId)
        assertEquals("Realtime preview", reconciled.latestActivityPreview)
        assertEquals(3, reconciled.replyCount)
        assertEquals(0, reconciled.unreadCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun realtimeBurstDuringRefreshCoalescesToOneFollowUpReconcile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeReconcileDelayMillis = 0L
            )
        val stale = threadItem("thread-channel-1").copy(replyCount = 2)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale)))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-1", channelId = "thread-channel-1"))
            )
        )
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-2", channelId = "thread-channel-1"))
            )
        )
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-3", channelId = "thread-channel-1"))
            )
        )

        assertEquals(1, inbox.pendingCount)
        assertEquals(5, store.state.value.items.single().replyCount)
        inbox.completeNextSnapshot(snapshot(listOf(stale)))

        assertEquals(1, inbox.pendingCount)
        assertEquals(5, store.state.value.items.single().replyCount)
        val canonical =
            stale.copy(
                latestActivityMessageId = MessageId("message-3"),
                latestActivityPreview = "message message-3",
                replyCount = 5
            )
        inbox.completeNextSnapshot(snapshot(listOf(canonical)))

        assertEquals(3, inbox.initialLoadCount)
        assertEquals(0, inbox.pendingCount)
        assertEquals(MessageId("message-3"), store.state.value.items.single().latestActivityMessageId)
        scope.cancel()
    }

    @Test
    fun realtimeEventAfterTerminalDecisionStartsSuccessorReconcile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inbox = DeferredInboxRepository()
        lateinit var store: SharedActivityStore
        var injectAtTerminal = true
        store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeReconcileDelayMillis = 0L,
                afterRealtimeReconcileTerminalDecision = {
                    if (injectAtTerminal) {
                        injectAtTerminal = false
                        store.dispatch(
                            SharedActivityIntent.ApplyRealtimeEvent(
                                MessageNew(message("message-2", channelId = "thread-channel-1"))
                            )
                        )
                    }
                }
            )
        val stale = threadItem("thread-channel-1").copy(replyCount = 2)

        store.dispatch(SharedActivityIntent.LoadInitial)
        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(snapshot(listOf(stale)))
        awaitState(store) { state -> state.loaded && !state.refreshing }

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-1", channelId = "thread-channel-1"))
            )
        )
        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(snapshot(listOf(stale)))

        awaitPendingCount(inbox, 1)
        assertEquals(4, store.state.value.items.single().replyCount)
        val canonical =
            stale.copy(
                latestActivityMessageId = MessageId("message-2"),
                latestActivityPreview = "message message-2",
                replyCount = 4
            )
        inbox.completeNextSnapshot(snapshot(listOf(canonical)))
        awaitState(store) { state ->
            !state.refreshing && state.items.singleOrNull()?.latestActivityMessageId == MessageId("message-2")
        }

        assertEquals(3, inbox.initialLoadCount)
        assertEquals(0, inbox.pendingCount)
        scope.cancel()
    }

    @Test
    fun realtimePatchCannotEnterOwnerDuringStaleLoadFoldAndKeepsHighWater() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inbox = DeferredInboxRepository()
        lateinit var store: SharedActivityStore
        val foldHeld = CompletableDeferred<Unit>()
        val releaseFold = CompletableDeferred<Unit>()
        val secondPatchEnteredOwner = CompletableDeferred<Unit>()
        val patchEntryLock = SynchronizedObject()
        var loadResultCount = 0
        var patchEntryCount = 0
        store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeReconcileDelayMillis = 60_000L,
                beforeRealtimeHighWaterFold = {
                    loadResultCount += 1
                    if (loadResultCount == 2) {
                        foldHeld.complete(Unit)
                        runBlocking { releaseFold.await() }
                    }
                },
                onRealtimePatchOwnerEntered = {
                    synchronized(patchEntryLock) {
                        patchEntryCount += 1
                        if (patchEntryCount == 2) secondPatchEnteredOwner.complete(Unit)
                    }
                }
            )
        val stale = threadItem("thread-channel-1", unreadCount = 1).copy(replyCount = 2)

        store.dispatch(SharedActivityIntent.LoadInitial)
        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 1))
        awaitState(store) { state -> state.loaded && !state.refreshing }

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "message-1",
                        channelId = "thread-channel-1",
                        body = "First realtime preview"
                    )
                )
            )
        )
        assertEquals(MessageId("message-1"), store.state.value.items.single().latestActivityMessageId)

        store.dispatch(SharedActivityIntent.Refresh)
        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 1))
        withTimeout(2_000L) { foldHeld.await() }

        val secondPatch =
            scope.launch {
                store.dispatch(
                    SharedActivityIntent.ApplyRealtimeEvent(
                        MessageNew(
                            message(
                                id = "message-new",
                                channelId = "thread-channel-1",
                                body = "Realtime preview"
                            )
                        )
                    )
                )
            }
        val enteredWhileFoldHeld =
            withTimeoutOrNull(200L) {
                secondPatchEnteredOwner.await()
            }
        assertEquals(null, enteredWhileFoldHeld)

        releaseFold.complete(Unit)
        secondPatch.join()
        awaitState(store) { state ->
            !state.refreshing && state.items.singleOrNull()?.latestActivityMessageId == MessageId("message-new")
        }

        store.dispatch(SharedActivityIntent.Refresh)
        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 1))
        awaitState(store) { state ->
            !state.refreshing && state.items.singleOrNull()?.latestActivityMessageId == MessageId("message-new")
        }

        val reconciled = store.state.value.items.single()
        assertEquals("Realtime preview", reconciled.latestActivityPreview)
        assertEquals(4, reconciled.replyCount)
        assertEquals(1, reconciled.unreadCount)
        assertTrue(secondPatchEnteredOwner.isCompleted)
        scope.cancel()
    }

    @Test
    fun homeSummaryRealtimeIncrementsUnreadWithoutRefreshingActivityRows() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 1)), totalCount = 1, totalUnreadCount = 1),
                        snapshot(listOf(activityItem("channel-1", unreadCount = 9)), totalCount = 1, totalUnreadCount = 9)
                    )
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeSummaryEvent(
                MessageNew(message("message-new", channelId = "channel-1", authorId = "user-2")),
                activeConversationId = null
            )
        )

        assertEquals(1, inbox.initialLoadCount)
        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key })
        assertEquals(1, store.state.value.items.single().unreadCount)
        assertEquals(2, store.state.value.totalUnreadCount)
        assertEquals(1, store.state.value.totalCount)
        scope.cancel()
    }

    @Test
    fun homeSummaryRealtimeSkipsSelfAuthoredAndActiveConversationMessages() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(
                            listOf(activityItem("channel-1", unreadCount = 1), activityItem("channel-2", unreadCount = 1)),
                            totalCount = 2,
                            totalUnreadCount = 2
                        )
                    )
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeSummaryEvent(
                SyncResumeResponse(
                    messages =
                        listOf(
                            message("self-message", channelId = "channel-1", authorId = "user-1"),
                            message("active-message", channelId = "channel-2", authorId = "user-2"),
                            message("inactive-message", channelId = "channel-1", authorId = "user-2")
                        ),
                    currentSeq = 3L,
                    hasMore = false
                ),
                activeConversationId = "channel-2"
            )
        )

        assertEquals(1, inbox.initialLoadCount)
        assertEquals(3, store.state.value.totalUnreadCount)
        assertEquals(listOf("channel:channel-1", "channel:channel-2"), store.state.value.items.map { it.key })
        scope.cancel()
    }

    @Test
    fun loadInitialSeedsColdStoreFromMatchingActivityPersistSlice() = runBlocking {
        val persistStorage = InMemoryPersistStorage()
        val persistSlice = createSharedActivityPersistSlice(persistStorage)
        val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val preloadStore =
            activityStore(
                scope = preloadScope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1"))))),
                persistSlices = listOf(persistSlice)
            )
        preloadStore.dispatch(SharedActivityIntent.LoadInitial)

        val standaloneScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val standaloneInbox = DeferredInboxRepository()
        val standaloneStore =
            activityStore(
                scope = standaloneScope,
                inboxRepository = standaloneInbox,
                persistSlices = listOf(persistSlice)
            )

        standaloneStore.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(false, standaloneStore.state.value.loading)
        assertEquals(true, standaloneStore.state.value.refreshing)
        assertEquals(listOf("channel:channel-1"), standaloneStore.state.value.items.map { it.key })
        standaloneInbox.completeNext(activityItem("channel-2"))
        assertEquals(false, standaloneStore.state.value.refreshing)
        assertEquals(listOf("channel:channel-2"), standaloneStore.state.value.items.map { it.key })
        preloadScope.cancel()
        standaloneScope.cancel()
    }

    @Test
    fun loadInitialDedupesHydratedActivityPersistRows() = runBlocking {
        val persistStorage = InMemoryPersistStorage()
        val persistSlice = createSharedActivityPersistSlice(persistStorage)
        val duplicateRow = activityItem("channel-1")
        val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val preloadStore =
            activityStore(
                scope = preloadScope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots =
                            listOf(
                                snapshot(
                                    listOf(
                                        duplicateRow,
                                        duplicateRow.copy(latestActivityMessageId = MessageId("latest-channel-1-duplicate")),
                                        activityItem("channel-2")
                                    )
                                )
                            )
                    ),
                persistSlices = listOf(persistSlice)
            )
        preloadStore.dispatch(SharedActivityIntent.LoadInitial)

        val standaloneScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val standaloneStore =
            activityStore(
                scope = standaloneScope,
                inboxRepository = DeferredInboxRepository(),
                persistSlices = listOf(persistSlice)
            )

        standaloneStore.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(listOf("channel:channel-1", "channel:channel-2"), standaloneStore.state.value.items.map { it.key })
        assertEquals(true, standaloneStore.state.value.refreshing)
        preloadScope.cancel()
        standaloneScope.cancel()
    }

    @Test
    fun loadInitialIgnoresActivityPersistSliceForDifferentUser() = runBlocking {
        val persistStorage = InMemoryPersistStorage()
        val persistSlice = createSharedActivityPersistSlice(persistStorage)
        val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val preloadStore =
            activityStore(
                scope = preloadScope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1"))))),
                persistSlices = listOf(persistSlice)
            )
        preloadStore.dispatch(SharedActivityIntent.LoadInitial)

        val standaloneScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val standaloneStore =
            activityStore(
                scope = standaloneScope,
                authStore = InMemorySlockAuthSessionStore(sampleSession(userId = "user-2")),
                inboxRepository = DeferredInboxRepository(),
                persistSlices = listOf(persistSlice)
            )

        standaloneStore.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(true, standaloneStore.state.value.loading)
        assertEquals(emptyList(), standaloneStore.state.value.items.map { it.key })
        preloadScope.cancel()
        standaloneScope.cancel()
    }

    @Test
    fun activityThreadRowsResolveMissingSenderNameFromMembers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val row =
            threadItem("thread-channel-1", unreadCount = 1)
                .copy(
                    latestActivitySenderId = "agent-1",
                    latestActivitySenderName = "@agent-one",
                    latestActivityPreview = "Latest reply"
                )
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(row))))
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                memberRepository =
                    FakeMemberRepository(
                        members =
                            listOf(
                                MemberProfile(
                                    id = MemberId("agent-1"),
                                    displayName = "Agent One",
                                    handle = "agent-one",
                                    kind = MemberKind.Agent
                                )
                            )
                    )
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        val item = store.state.value.items.single()
        assertEquals("@agent-one", item.latestActivitySenderName)
        assertEquals("Agent One", store.state.value.senderProfiles.getValue("agent-1").displayName)
        scope.cancel()
    }

    @Test
    fun activityThreadRowsMergeTaskProjectionWhenInboxProjectionIsMissingTaskFields() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tasksDataStore = SharedTasksDataStore()
        tasksDataStore.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot =
                TaskSnapshot(
                    tasks =
                        listOf(
                            TaskSummary(
                                id = TaskId("task-177"),
                                title = "Thread task",
                                status = TaskStatus.InReview,
                                taskNumber = 177,
                                messageId = MessageId("parent-message"),
                                channelId = ChannelId("parent-channel"),
                                assignee =
                                    MemberProfile(
                                        id = MemberId("agent-1"),
                                        displayName = "Claude Assistant",
                                        handle = "claude",
                                        kind = MemberKind.Agent
                                    ),
                                updatedAtLabel = "now"
                            )
                        ),
                    summaryCounts = emptyMap()
                )
        )
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(threadItem("thread-channel-1")))))
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                tasksDataStore = tasksDataStore
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        val item = store.state.value.items.single()
        assertEquals(177, item.taskNumber)
        assertEquals(TaskStatus.InReview, item.taskStatus)
        assertEquals("Claude Assistant", item.taskClaimedByName)
        assertEquals("#177 @Claude Assistant", item.activityTaskBadgeText())
        assertEquals(
            listOf(SharedActivityFooterSlot.Replies, SharedActivityFooterSlot.Task),
            item.activityFooterSlots()
        )
        scope.cancel()
    }

    @Test
    fun persistedActivityRowsUseMemberStoreSenderNamesBeforeRefreshCompletes() = runBlocking {
        val persistStorage = InMemoryPersistStorage()
        val persistSlice = createSharedActivityPersistSlice(persistStorage)
        val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val row =
            threadItem("thread-channel-1", unreadCount = 1)
                .copy(
                    latestActivitySenderId = "agent-1",
                    latestActivitySenderName = null,
                    latestActivityPreview = "Latest reply"
                )
        val preloadStore =
            activityStore(
                scope = preloadScope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(row)))),
                persistSlices = listOf(persistSlice)
            )
        preloadStore.dispatch(SharedActivityIntent.LoadInitial)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val agentOne =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Agent One",
                handle = "agent-one",
                kind = MemberKind.Agent
            )
        val membersStore =
            SharedServerMembersStore(
                FakeMemberRepository(
                    members = listOf(agentOne)
                )
            )
        membersStore.seedProfiles(
            ServerId("server-1"),
            listOf(agentOne),
            server = ServerSummary(id = ServerId("server-1"), name = "Slock")
        )
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                serverMembersStore = membersStore,
                persistSlices = listOf(persistSlice)
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(true, store.state.value.refreshing)
        assertEquals("Agent One", store.state.value.senderProfiles.getValue("agent-1").displayName)
        preloadScope.cancel()
        scope.cancel()
    }

    @Test
    fun reusedActivityStoreUpdatesRuntimeRepositoriesAndDropsPreviousServerSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstInbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1")))))
        val store =
            activityStore(
                scope = scope,
                inboxRepository = firstInbox
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key })

        val secondInbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-2")))))
        store.updateRuntimeIntegrations(
            authStore = InMemorySlockAuthSessionStore(sampleSession(serverId = "server-2")),
            inboxRepository = secondInbox,
            readReceiptRepository = FakeReadReceiptRepository(),
            conversationRepository = FakeConversationRepository()
        )
        store.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(1, firstInbox.initialLoadCount)
        assertEquals(1, secondInbox.initialLoadCount)
        assertEquals("server-2", store.state.value.ownerServerId)
        assertEquals(listOf("channel:channel-2"), store.state.value.items.map { it.key })
        scope.cancel()
    }

    @Test
    fun doneUsesInboxDoneForChannelsAndThreadDoneForThreads() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(
                            listOf(
                                activityItem("channel-1"),
                                threadItem("thread-channel-1")
                            ),
                            totalCount = 2
                        )
                    )
            )
        val conversation = FakeConversationRepository()
        val store = activityStore(scope, inbox, conversationRepository = conversation)

        store.dispatch(SharedActivityIntent.LoadInitial)
        val channel = store.state.value.items.first { it.kind == InboxItemKind.Channel }
        val thread = store.state.value.items.first { it.kind == InboxItemKind.Thread }
        store.dispatch(SharedActivityIntent.MarkDone(channel))
        store.dispatch(SharedActivityIntent.MarkDone(thread))

        assertEquals(listOf(ChannelId("channel-1")), inbox.doneChannelIds)
        assertEquals(listOf(ChannelId("thread-channel-1")), conversation.doneThreadChannelIds)
        assertEquals(emptyList(), store.state.value.items)
        scope.cancel()
    }

    @Test
    fun mentionActionDoneRemovesRowWithoutCallingInboxDone() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val row = mentionActionItem("resolution-1")
        val inbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(row), totalCount = 1, totalUnreadCount = 0))
            )
        val conversation = FakeConversationRepository()
        val store = activityStore(scope, inbox, conversationRepository = conversation)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))

        assertEquals(emptyList(), store.state.value.items)
        assertEquals(emptyList(), inbox.doneChannelIds)
        assertEquals(emptyList(), conversation.doneThreadChannelIds)
        scope.cancel()
    }

    @Test
    fun failedDoneRefreshesAndRestoresRows() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val row = activityItem("channel-1")
        val inbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(row)), snapshot(listOf(row))),
                failDone = true
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))

        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key })
        assertEquals(2, inbox.initialLoadCount)
        scope.cancel()
    }

    @Test
    fun staleActivityRefreshAfterMarkDoneDoesNotRestoreRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)
        val row = activityItem("channel-1", unreadCount = 1)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(row), totalCount = 1, totalUnreadCount = 1))
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        inbox.completeNextSnapshot(snapshot(listOf(row), totalCount = 1, totalUnreadCount = 1))

        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun markDoneAllowsLaterActivityWithDifferentAnchor() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)
        val row = activityItem("channel-1", unreadCount = 1)
        val laterRow =
            row.copy(
                unreadCount = 1,
                firstUnreadMessageId = MessageId("first-channel-1-new"),
                latestActivityMessageId = MessageId("latest-channel-1-new")
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(row), totalCount = 1, totalUnreadCount = 1))
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        inbox.completeNextSnapshot(snapshot(listOf(laterRow), totalCount = 1, totalUnreadCount = 1))

        val visible = store.state.value.items.single()
        assertEquals(MessageId("first-channel-1-new"), visible.firstUnreadMessageId)
        assertEquals(1, store.state.value.totalCount)
        assertEquals(1, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun threadActionsRequireExplicitThreadChannelId() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val row = threadItem(channelId = "parent-or-compat", threadChannelId = null, unreadCount = 1)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(row), totalUnreadCount = 1),
                        snapshot(listOf(row), totalUnreadCount = 1),
                        snapshot(listOf(row), totalUnreadCount = 1),
                        snapshot(listOf(row), totalUnreadCount = 1)
                    )
            )
        val read = FakeReadReceiptRepository()
        val conversation = FakeConversationRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                readReceiptRepository = read,
                conversationRepository = conversation
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        store.dispatch(SharedActivityIntent.MarkUnread(store.state.value.items.single()))
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))

        assertEquals(emptyList(), read.readAllChannelIds)
        assertEquals(emptyList(), read.unreadChannelIds)
        assertEquals(emptyList(), inbox.doneChannelIds)
        assertEquals(emptyList(), conversation.doneThreadChannelIds)
        assertEquals(listOf("thread:parent-or-compat"), store.state.value.items.map { it.key })
        assertEquals(4, inbox.initialLoadCount)
        scope.cancel()
    }

    @Test
    fun shellScopedActivityStoresKeepOwnerServerAndRequestsIndependent() = runBlocking {
        val processSession =
            sampleSession().copy(
                servers =
                    listOf(
                        StoredAuthServer(id = "server-1", name = "One"),
                        StoredAuthServer(id = "server-2", name = "Two")
                    )
            )
        val processStore = InMemorySlockAuthSessionStore(processSession)
        val authA =
            SlockShellAuthSessionStore(
                processStore,
                SelectedServerStore(SlockShellStoreScope.create("shell-a", "server-1"))
            )
        val authB =
            SlockShellAuthSessionStore(
                processStore,
                SelectedServerStore(SlockShellStoreScope.create("shell-b", "server-2"))
            )
        val inboxA = FakeInboxRepository(initialSnapshots = listOf(snapshot(emptyList())))
        val inboxB = FakeInboxRepository(initialSnapshots = listOf(snapshot(emptyList())))
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storeA = activityStore(scopeA, inboxA, authStore = authA)
        val storeB = activityStore(scopeB, inboxB, authStore = authB)

        storeA.dispatch(SharedActivityIntent.LoadInitial)
        storeB.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals("server-1", storeA.state.value.ownerServerId)
        assertEquals("server-2", storeB.state.value.ownerServerId)
        assertEquals(listOf(ServerId("server-1")), inboxA.initialLoadServerIds)
        assertEquals(listOf(ServerId("server-2")), inboxB.initialLoadServerIds)
        scopeA.cancel()
        scopeB.cancel()
    }

    private fun activityStore(
        scope: CoroutineScope,
        inboxRepository: InboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(emptyList()))),
        readReceiptRepository: FakeReadReceiptRepository = FakeReadReceiptRepository(),
        conversationRepository: FakeConversationRepository = FakeConversationRepository(),
        messageDomainStore: MessageDomainStore = FakeMessageDomainStore(),
        memberRepository: MemberRepository? = null,
        serverMembersStore: SharedServerMembersStore? = null,
        persistSlices: List<MviPersistSlice<SharedActivityState, SharedActivityIntent>> = emptyList(),
        tasksDataStore: SharedTasksDataStore? = null,
        perReceiverStateVersionStore: PerReceiverStateVersionStore =
            PerReceiverStateVersionStore(InMemorySlockKeyValueStore()),
        authStore: SlockAuthSessionStore = InMemorySlockAuthSessionStore(sampleSession()),
        prewarmReplyThread: suspend (ActivityReplyThreadPrewarmMarker) -> DataResult<Unit> = {
            DataResult.Empty(EmptyReason.NoMessages)
        },
        tailPrewarmDepthCap: Int = 20,
        onUnreadCountsChanged: () -> Unit = {},
        realtimeReconcileDelayMillis: Long = 150L,
        afterRealtimeReconcileTerminalDecision: suspend () -> Unit = {},
        beforeRealtimeHighWaterFold: () -> Unit = {},
        onRealtimePatchOwnerEntered: () -> Unit = {}
    ): SharedActivityStore =
        SharedActivityStore(
            authStore = authStore,
            inboxRepository = inboxRepository,
            readReceiptRepository = readReceiptRepository,
            conversationRepository = conversationRepository,
            messageDomainStore = messageDomainStore,
            memberRepository = memberRepository,
            serverMembersStore = serverMembersStore,
            tasksDataStore = tasksDataStore,
            perReceiverStateVersionStore = perReceiverStateVersionStore,
            persistSlices = persistSlices,
            prewarmReplyThread = prewarmReplyThread,
            tailPrewarmDepthCap = tailPrewarmDepthCap,
            onUnreadCountsChanged = onUnreadCountsChanged,
            scope = scope,
            realtimeReconcileDelayMillis = realtimeReconcileDelayMillis,
            afterRealtimeReconcileTerminalDecision = afterRealtimeReconcileTerminalDecision,
            beforeRealtimeHighWaterFold = beforeRealtimeHighWaterFold,
            onRealtimePatchOwnerEntered = onRealtimePatchOwnerEntered
        )

    private class FakeMessageDomainStore(
        private val projection: (String, MessageReadStateCheckpoint) -> MessageReadStateProjection = { _, checkpoint ->
            MessageReadStateProjection(
                readStateVersion = checkpoint.readStateVersion,
                maxReadSeq = checkpoint.maxReadSeq,
                unreadCount = 0,
                hasMention = false,
                firstUnreadMessageId = null,
                firstMentionMessageId = null,
                complete = true,
            )
        },
    ) : MessageDomainStore {
        override fun beginAuthoritativeRebaseline(
            serverId: String,
            memberId: String,
            scopeId: String,
        ): MessageScopeRebaselineToken? = null

        override fun completeAuthoritativeRebaseline(
            token: MessageScopeRebaselineToken,
            memberId: String,
            receiverKind: MessageReceiverKind,
            rows: List<build.raft.app.canonical.message.CanonicalMessageRow>,
            coveredAfterSeq: Long,
            coveredFromSeq: Long,
            coveredThroughSeq: Long,
            remoteHighWaterSeq: Long,
            hasGap: Boolean,
            hasNewer: Boolean,
        ): SyncIngestOutcome? = null

        override fun invalidateAuthoritativeRebaseline(token: MessageScopeRebaselineToken) = Unit

        override fun projectReadState(
            serverId: String,
            memberId: String,
            receiverKind: MessageReceiverKind,
            scopeId: String,
            checkpoint: MessageReadStateCheckpoint,
        ): MessageReadStateProjection = projection(scopeId, checkpoint)
    }

    private fun readStateEvent(
        scopeId: String,
        version: Long,
        maxReadSeq: Long
    ): PerReceiverReadStateChanged =
        PerReceiverReadStateChanged(
            serverId = ServerId("server-1"),
            scopes =
                listOf(
                    PerReceiverReadStateScope(
                        scopeId = ChannelId(scopeId),
                        maxReadSeq = maxReadSeq,
                        readStateVersion = version
                    )
                )
        )

    private fun replyPrewarmMarker(
        activityVersion: String = "1:first:latest"
    ): ActivityReplyThreadPrewarmMarker =
        ActivityReplyThreadPrewarmMarker(
            key =
                ActivityReplyThreadPrewarmKey(
                    serverId = "server-1",
                    parentChannelId = "parent-channel",
                    parentMessageId = "parent-message",
                    threadChannelId = "thread-channel"
                ),
            activityVersion = activityVersion
        )

    private fun keyedMarker(
        id: String,
        activityVersion: String = "1:$id"
    ): ActivityReplyThreadPrewarmMarker =
        ActivityReplyThreadPrewarmMarker(
            key =
                ActivityReplyThreadPrewarmKey(
                    serverId = "server-1",
                    parentChannelId = "parent-$id",
                    parentMessageId = "message-$id",
                    threadChannelId = "thread-$id"
                ),
            activityVersion = activityVersion
        )

    private class InMemoryPersistStorage : MviPersistStorage {
        private val values = mutableMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private class FakeInboxRepository(
        private val initialSnapshots: List<InboxSnapshot>,
        private val moreSnapshots: List<InboxSnapshot> = emptyList(),
        private val failDone: Boolean = false
    ) : InboxRepository {
        var initialLoadCount = 0
        var moreLoadCount = 0
        val initialLoadServerIds = mutableListOf<ServerId>()
        val loadMoreOffsets = mutableListOf<Int>()
        val doneChannelIds = mutableListOf<ChannelId>()

        override suspend fun loadInitial(
            serverId: ServerId,
            filter: InboxFilter,
            limit: Int
        ): DataResult<InboxSnapshot> =
            DataResult.Content(
                initialSnapshots.getOrElse(initialLoadCount++) { initialSnapshots.last() }
                    .also { initialLoadServerIds += serverId }
            )

        override suspend fun loadMore(
            serverId: ServerId,
            filter: InboxFilter,
            offset: Int,
            limit: Int
        ): DataResult<InboxSnapshot> {
            loadMoreOffsets += offset
            return DataResult.Content(moreSnapshots.getOrElse(moreLoadCount++) { moreSnapshots.last() })
        }

        override suspend fun markDone(channelId: ChannelId): DataResult<InboxDoneResult> {
            doneChannelIds += channelId
            return if (failDone) {
                DataResult.Error(ScreenError("done_failed"))
            } else {
                DataResult.Content(InboxDoneResult(channelId))
            }
        }
    }

    private class FakeReadReceiptRepository(
        private val pendingInboxReadAllResult: CompletableDeferred<DataResult<ReadReceiptResult>>? = null
    ) : ReadReceiptRepository {
        val readAllChannelIds = mutableListOf<ChannelId>()
        val unreadChannelIds = mutableListOf<ChannelId>()
        val inboxReadAllServerIds = mutableListOf<ServerId>()

        override suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot> =
            error("unused")

        override suspend fun markChannelReadToSeq(channelId: ChannelId, seq: Long): DataResult<ReadReceiptResult> =
            error("unused")

        override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> {
            readAllChannelIds += channelId
            return DataResult.Content(ReadReceiptResult(channelId = channelId))
        }

        override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> {
            unreadChannelIds += channelId
            return DataResult.Content(ReadReceiptResult(channelId = channelId, unreadCount = 1))
        }

        override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> {
            inboxReadAllServerIds += serverId
            pendingInboxReadAllResult?.let { return it.await() }
            return DataResult.Content(ReadReceiptResult())
        }
    }

    private inner class DeferredInboxRepository : InboxRepository {
        private val lock = SynchronizedObject()
        private val pending = mutableListOf<CompletableDeferred<DataResult<InboxSnapshot>>>()
        private var initialLoads = 0
        val initialLoadCount: Int
            get() = synchronized(lock) { initialLoads }
        val pendingCount: Int
            get() = synchronized(lock) { pending.size }

        override suspend fun loadInitial(
            serverId: ServerId,
            filter: InboxFilter,
            limit: Int
        ): DataResult<InboxSnapshot> {
            val result = CompletableDeferred<DataResult<InboxSnapshot>>()
            synchronized(lock) {
                initialLoads += 1
                pending += result
            }
            return withContext(NonCancellable) { result.await() }
        }

        override suspend fun loadMore(
            serverId: ServerId,
            filter: InboxFilter,
            offset: Int,
            limit: Int
        ): DataResult<InboxSnapshot> =
            DataResult.Empty(EmptyReason.NoInboxItems)

        override suspend fun markDone(channelId: ChannelId): DataResult<InboxDoneResult> =
            DataResult.Content(InboxDoneResult(channelId))

        fun completeNext(item: InboxItem) {
            completeNextSnapshot(snapshot(listOf(item)))
        }

        fun completeNextSnapshot(snapshot: InboxSnapshot) {
            val next = synchronized(lock) { pending.removeAt(0) }
            next.complete(DataResult.Content(snapshot))
        }
    }

    private class FakeMemberRepository(
        private val members: List<MemberProfile>
    ) : MemberRepository {
        override suspend fun getMembers(
            priority: DataRequestPriority,
            serverId: ServerId
        ): DataResult<MemberSnapshot> =
            DataResult.Content(MemberSnapshot(ServerSummary(id = serverId, name = "Slock"), members))

        override suspend fun getMemberProfile(priority: DataRequestPriority, memberId: MemberId, serverId: ServerId?): DataResult<MemberProfile> =
            error("unused")

        override suspend fun updateAgentProfile(priority: DataRequestPriority, request: AgentProfileUpdateRequest): DataResult<MemberProfile> =
            error("unused")

        override suspend fun uploadAgentAvatar(priority: DataRequestPriority, request: AgentAvatarUploadRequest): DataResult<MemberProfile> =
            error("unused")

        override suspend fun getAgentDetail(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDetailSnapshot> =
            error("unused")

        override suspend fun getAgentDirectMessages(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDmSnapshot> =
            error("unused")

        override suspend fun getAgentReminders(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentReminderSnapshot> =
            error("unused")

        override suspend fun getAgentActivity(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentActivitySnapshot> =
            error("unused")

        override suspend fun getAgentWorkspaces(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceSnapshot> =
            error("unused")

        override suspend fun getAgentWorkspaceTree(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceTreeSnapshot> =
            error("unused")

        override suspend fun getAgentWorkspaceChildren(
            priority: DataRequestPriority,
            agentId: MemberId,
            dirPath: String
        ): DataResult<AgentWorkspaceChildrenSnapshot> =
            error("unused")

        override suspend fun readAgentWorkspaceFile(
            priority: DataRequestPriority,
            agentId: MemberId,
            path: String
        ): DataResult<AgentWorkspaceFilePreviewSnapshot> =
            error("unused")

        override suspend fun getAgentSkills(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentSkillsSnapshot> =
            error("unused")

        override suspend fun startAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun stopAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun resetAgent(priority: DataRequestPriority, request: AgentResetRequest): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun deleteAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun assignAgentMachine(
            priority: DataRequestPriority,
            request: AgentMachineAssignmentRequest
        ): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun getChannelMembers(priority: DataRequestPriority, channelId: ChannelId): DataResult<ChannelMemberSnapshot> =
            error("unused")

        override suspend fun addChannelMember(priority: DataRequestPriority, request: AddChannelMemberRequest): DataResult<ChannelMemberActionResult> =
            error("unused")

        override suspend fun removeChannelMember(
            priority: DataRequestPriority,
            request: RemoveChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> =
            error("unused")
    }

    private class FakeConversationRepository(
        private val threadGate: CompletableDeferred<Unit>? = null,
        private val threadEntered: CompletableDeferred<Unit>? = null,
    ) : ConversationRepository {
        val doneThreadChannelIds = mutableListOf<ChannelId>()
        val loadedThreadIds = mutableListOf<ThreadId>()

        override suspend fun getThread(threadId: ThreadId, includeFollowedSummaries: Boolean, limit: Int): DataResult<ThreadSnapshot> {
            loadedThreadIds += threadId
            threadEntered?.complete(Unit)
            threadGate?.await()
            return DataResult.Empty(EmptyReason.NoMessages)
        }

        override suspend fun getReplyThread(parentChannelId: ChannelId, parentMessageId: MessageId, includeFollowedSummaries: Boolean, limit: Int): DataResult<ThreadSnapshot> =
            error("unused")

        override suspend fun getMessageContext(messageId: MessageId, channelId: ChannelId?): DataResult<ThreadSnapshot> =
            error("unused")

        override suspend fun loadOlderMessages(threadId: ThreadId, beforeSeq: Long, limit: Int): DataResult<ThreadSnapshot> =
            error("unused")

        override suspend fun loadNewerMessages(threadId: ThreadId, afterSeq: Long, limit: Int): DataResult<ThreadSnapshot> =
            error("unused")

        override suspend fun syncNewerMessages(threadId: ThreadId, sinceSeq: Long, limit: Int): DataResult<ThreadSnapshot> =
            error("unused")

        override suspend fun getChannelThreads(channelId: ChannelId, limit: Int): DataResult<ThreadListSnapshot> =
            error("unused")

        override suspend fun getFollowedThreads(serverId: ServerId, limit: Int): DataResult<ThreadListSnapshot> =
            error("unused")

        override suspend fun createOrOpenThread(channelId: ChannelId, parentMessageId: MessageId): DataResult<ThreadActionResult> =
            error("unused")

        override suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> =
            error("unused")

        override suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
            error("unused")

        override suspend fun markThreadDone(threadChannelId: ChannelId): DataResult<ThreadActionResult> {
            doneThreadChannelIds += threadChannelId
            return DataResult.Content(ThreadActionResult(threadChannelId = threadChannelId, changed = true))
        }

        override suspend fun markThreadUndone(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
            error("unused")

        override suspend fun uploadAttachments(request: AttachmentUploadRequest): DataResult<AttachmentUploadResult> =
            error("unused")

        override suspend fun getAttachmentOpenTarget(attachmentId: AttachmentId): DataResult<AttachmentOpenTarget> =
            error("unused")

        override suspend fun getChannelFiles(channelId: ChannelId, limit: Int, cursor: String?): DataResult<ChannelFilesSnapshot> =
            error("unused")

        override suspend fun sendMessage(
            channelId: ChannelId,
            body: String,
            attachmentIds: List<AttachmentId>,
            asTask: Boolean,
            randomId: String?
        ): DataResult<SendMessageResult> =
            error("unused")
    }

    private fun activityItem(
        channelId: String,
        unreadCount: Int = 0,
    ): InboxItem =
        InboxItem(
            key = "channel:$channelId",
            kind = InboxItemKind.Channel,
            channelId = ChannelId(channelId),
            channelName = channelId,
            unreadCount = unreadCount,
            firstUnreadMessageId = if (unreadCount > 0) MessageId("first-$channelId") else null,
            lastMessageId = MessageId("last-$channelId"),
            latestActivityMessageId = MessageId("latest-$channelId")
        )

    private fun message(
        id: String,
        channelId: String = "channel-1",
        authorId: String = "user-2",
        body: String = "message $id",
        timestamp: String = "2026-07-13T03:00:00Z",
        seq: Long? = null,
        conversationContext: MessageConversationContext? = null
    ): Message =
        Message(
            id = MessageId(id),
            threadId = ThreadId(channelId),
            channelId = ChannelId(channelId),
            author =
                MemberProfile(
                    id = MemberId(authorId),
                    displayName = "User $authorId",
                    handle = authorId,
                    kind = MemberKind.Human
                ),
            timestampLabel = timestamp,
            body = body,
            seq = seq,
            conversationContext = conversationContext
        )

    private fun mentionActionItem(resolutionId: String): InboxItem =
        InboxItem(
            key = "mention_action:$resolutionId",
            kind = InboxItemKind.MentionAction,
            channelId = ChannelId("channel-1"),
            channelName = "android-dev",
            channelKind = ConversationKind.Channel,
            lastMessageId = MessageId("message-1"),
            lastMessagePreview = "Can someone ping @missing-human?",
            lastMessageAt = "2026-06-26T02:40:00Z",
            mentionActionId = resolutionId,
            pendingMentionActions =
                listOf(
                    PendingMentionAction(
                        resolutionId = resolutionId,
                        messageId = MessageId("message-1"),
                        channelId = ChannelId("channel-1"),
                        channelName = "android-dev",
                        targetType = "user",
                        targetHandle = "missing-human",
                        availableActions = listOf("notify", "invite")
                    )
                )
        )

    private fun threadItem(
        channelId: String,
        threadChannelId: String? = channelId,
        unreadCount: Int = 0,
    ): InboxItem =
        InboxItem(
            key = "thread:$channelId",
            kind = InboxItemKind.Thread,
            channelId = ChannelId(channelId),
            channelName = "general",
            threadChannelId = threadChannelId?.let(::ChannelId),
            parentChannelId = ChannelId("parent-channel"),
            parentMessageId = MessageId("parent-message"),
            unreadCount = unreadCount,
            firstUnreadMessageId = if (unreadCount > 0) MessageId("first-$channelId") else null,
            latestActivityMessageId = MessageId("latest-thread")
        )

    private fun snapshot(
        items: List<InboxItem>,
        hasMore: Boolean = false,
        nextOffset: Int? = null,
        totalCount: Int = items.size,
        totalUnreadCount: Int = items.sumOf { it.unreadCount }
    ): InboxSnapshot =
        InboxSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            items = items,
            totalCount = totalCount,
            totalUnreadCount = totalUnreadCount,
            hasMore = hasMore,
            nextOffset = nextOffset
        )

    private suspend fun awaitPendingCount(
        inbox: DeferredInboxRepository,
        expected: Int
    ) {
        withTimeout(5_000L) {
            while (inbox.pendingCount != expected) {
                delay(1L)
            }
        }
    }

    private suspend fun awaitState(
        store: SharedActivityStore,
        predicate: (SharedActivityState) -> Boolean
    ) {
        withTimeout(5_000L) {
            while (!predicate(store.state.value)) {
                delay(1L)
            }
        }
    }

    private fun homeSnapshot(
        conversation: ConversationSummary,
        unreadTotal: Int
    ): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock", unreadCount = unreadTotal),
            currentMember =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "User One",
                    handle = "user",
                    kind = MemberKind.Human
                ),
            channels = listOf(conversation.toHomeChannelRow()),
            directMessages = emptyList(),
            unreadTotal = unreadTotal
        )

    private fun sampleSession(
        userId: String = "user-1",
        serverId: String = "server-1"
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@slock.local", displayName = "User One"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = serverId,
            servers = listOf(StoredAuthServer(id = serverId, name = "Slock"))
        )
}
