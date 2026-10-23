package build.raft.app.data

import build.raft.app.SharedThreadFreshnessLedger
import build.raft.app.FakeChannelRepository
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
import build.raft.app.model.MessageUpdated
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
import build.raft.app.network.sync.ReadStateV1Fact
import build.raft.app.network.sync.ReadStateV1ProductionCanary
import build.raft.app.network.sync.ReadStateV1ProductionOwner
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Mirrors the store's private SharedActivityReplyPrewarmConcurrency (Semaphore(2)); the tail reuses
// that same shared Semaphore, so total prewarm concurrency must never exceed it.
private const val SharedActivityReplyPrewarmConcurrencyForTest = 2

class SharedActivityStoreTest {
    // atomicfu `synchronized` (Kotlin/Native) only locks a SynchronizedObject, never a plain list.
    private val testLogsLock = SynchronizedObject()

    @Test
    fun flagOffActivityFinalStateCheckpointAndEffectTraceMatchLegacy() = runBlocking {
        val legacyScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val canaryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val legacyInbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(emptyList())))
        val canaryInbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(emptyList())))
        val legacyVersionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val canaryVersionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        var legacyEffects = 0
        var canaryEffects = 0
        val legacy =
            activityStore(
                scope = legacyScope,
                inboxRepository = legacyInbox,
                perReceiverStateVersionStore = legacyVersionStore,
                readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("production", accountId, serverId) },
                onUnreadCountsChanged = { legacyEffects += 1 },
            )
        val flagOff =
            activityStore(
                scope = canaryScope,
                inboxRepository = canaryInbox,
                perReceiverStateVersionStore = canaryVersionStore,
                readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("production", accountId, serverId) },
                readStateV1Canary =
                    ReadStateV1ProductionCanary(
                        isEnabledForServer = { false },
                        store = TestReadStateV1Store(),
                        onProjection = { _, _, _ -> },
                        onRebaseline = { _, _, _ -> },
                    ),
                onUnreadCountsChanged = { canaryEffects += 1 },
            )
        legacy.dispatch(SharedActivityIntent.LoadInitial)
        flagOff.dispatch(SharedActivityIntent.LoadInitial)
        val event =
            SharedActivityIntent.ApplyRealtimeEvent(
                PerReceiverReadStateChanged(
                    ServerId("server-1"),
                    listOf(PerReceiverReadStateScope(ChannelId("channel-1"), 5, 2)),
                )
            )

        legacy.acceptRealtime(event)
        flagOff.acceptRealtime(event)

        assertEquals(legacy.state.value, flagOff.state.value)
        assertEquals(legacyEffects, canaryEffects)
        assertEquals(
            legacyVersionStore.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1")),
            canaryVersionStore.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1")),
        )
        assertEquals(legacyInbox.initialLoadCount, canaryInbox.initialLoadCount)
        legacyScope.cancel()
        canaryScope.cancel()
    }

    @Test
    fun selectedReadStateRealtimeUsesSynchronousPatchOnlyProjectionWithoutLegacyCheckpoint() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(emptyList()), snapshot(emptyList())))
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        versionStore.writeReadStateCheckpoint(
            ServerId("server-1"),
            MemberId("user-1"),
            ChannelId("channel-1"),
            build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(99, 99),
        )
        lateinit var store: SharedActivityStore
        var project = false
        var projectionNotifications = 0
        val durable = TestReadStateV1Store()
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { true },
                store = durable,
                onProjection = { owner, _, fact -> if (project) store.applyReadStateV1Projection(owner, fact) },
                onRebaseline = { owner, _, channelId -> store.requestReadStateV1Rebaseline(owner, channelId) },
            )
        store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                perReceiverStateVersionStore = versionStore,
                readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("production", accountId, serverId) },
                readStateV1Canary = canary,
                onUnreadCountsChanged = { projectionNotifications += 1 },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        store.applyReadStateV1Projection(
            ReadStateV1ProductionOwner("staging", "user-1", "server-1"),
            ReadStateV1Fact("channel-1", 50, 50),
        )
        assertEquals(0, projectionNotifications)
        canary.applyHydrate(ReadStateV1ProductionOwner("production", "user-1", "server-1"), "channel-1", 1, 10)
        project = true

        store.acceptRealtime(
            SharedActivityIntent.ApplyRealtimeEvent(
                PerReceiverReadStateChanged(
                    ServerId("server-1"),
                    listOf(PerReceiverReadStateScope(ChannelId("channel-1"), 5, 2)),
                )
            )
        )

        assertEquals(ReadStateV1Fact("channel-1", 2, 5), durable.facts.values.single())
        assertEquals(
            build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(99, 99),
            versionStore.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1")),
        )
        assertEquals(1, projectionNotifications)
        assertEquals(1, inbox.initialLoadCount, "read-state projection must not own a full-list refresh")

        store.requestReadStateV1Rebaseline(
            ReadStateV1ProductionOwner("production", "user-1", "server-1"),
            "channel-1",
        )
        assertEquals(2, projectionNotifications)
        assertEquals(1, inbox.initialLoadCount, "rebaseline emission must preserve visible patch-only Activity")
        scope.cancel()
    }

    @Test
    fun visibleReplyPrewarmDedupesStableGenerationAndRefetchesAfterLedgerAdvance() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val ledger = SharedThreadFreshnessLedger()
        val store =
            activityStore(
                scope = scope,
                replyThreadFreshnessLedger = ledger,
                prewarmReplyThread = { marker ->
                    calls += marker
                    DataResult.Content(Unit)
                }
            )
        val marker = replyPrewarmMarker()

        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))
        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))
        ledger.advanceAllOwners(marker.key.serverId, marker.key.threadChannelId)
        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))

        assertEquals(listOf(marker.key, marker.key), calls.map { it.key })
        assertTrue(calls[0].contentGeneration !== calls[1].contentGeneration)
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
    fun visibleReplyPrewarmRunsLatestGenerationAfterOlderInFlightCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val ledger = SharedThreadFreshnessLedger()
        val marker = replyPrewarmMarker()
        val store =
            activityStore(
                scope = scope,
                replyThreadFreshnessLedger = ledger,
                prewarmReplyThread = { marker ->
                    calls += marker
                    if (calls.size == 1) releaseFirst.await()
                    DataResult.Content(Unit)
                }
            )

        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))
        ledger.advanceAllOwners(marker.key.serverId, marker.key.threadChannelId)
        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))
        assertEquals(1, calls.size)

        releaseFirst.complete(Unit)
        assertEquals(2, calls.size)
        assertTrue(calls[0].contentGeneration !== calls[1].contentGeneration)
        scope.cancel()
    }

    @Test
    fun resetSerializesWithConcurrentReplyPrewarmRegistrationAndCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store =
            activityStore(
                scope = scope,
                prewarmReplyThread = { DataResult.Content(Unit) }
            )
        val markerSets =
            (0 until 8).map { generation ->
                (0 until 24)
                    .map { index -> keyedMarker("reset-race-$generation-$index") }
                    .toSet()
            }

        // Production hit this exact ownership split: Kuikly reset iterated replyPrewarmJobs while
        // app-scope completion removed entries in finally. Exercise both sides from real worker
        // threads; any unsynchronized HashMap size/copy race escapes the child and fails the test.
        val workers =
            List(4) { worker ->
                launch(Dispatchers.Default) {
                    repeat(200) { iteration ->
                        if ((worker + iteration) % 3 == 0) {
                            store.reset()
                        } else {
                            store.dispatch(
                                SharedActivityIntent.PrewarmVisibleReplyThreads(
                                    markerSets[(worker + iteration) % markerSets.size]
                                )
                            )
                        }
                    }
                }
            }
        workers.joinAll()

        store.reset()
        assertEquals(emptyList(), store.state.value.items)
        scope.cancel()
    }

    @Test
    fun twoActivityShellOwnersShareTheSameOpaqueAppFreshnessGeneration() = runBlocking {
        val ledger = SharedThreadFreshnessLedger()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstCalls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val secondCalls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val firstStore =
            activityStore(
                scope = firstScope,
                replyThreadFreshnessLedger = ledger,
                prewarmReplyThread = { marker ->
                    firstCalls += marker
                    DataResult.Content(Unit)
                }
            )
        val secondStore =
            activityStore(
                scope = secondScope,
                replyThreadFreshnessLedger = ledger,
                prewarmReplyThread = { marker ->
                    secondCalls += marker
                    DataResult.Content(Unit)
                }
            )
        val marker = replyPrewarmMarker()

        firstStore.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))
        secondStore.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))
        assertTrue(firstCalls.single().contentGeneration === secondCalls.single().contentGeneration)

        ledger.advanceAllOwners(marker.key.serverId, marker.key.threadChannelId)
        firstStore.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))
        secondStore.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))
        assertTrue(firstCalls.last().contentGeneration === secondCalls.last().contentGeneration)
        assertTrue(firstCalls.first().contentGeneration !== firstCalls.last().contentGeneration)
        firstScope.cancel()
        secondScope.cancel()
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

        assertEquals(ordered.map { it.key }, calls.map { it.key })
        scope.cancel()
    }

    @Test
    fun activityTailKeepsRunningAfterTheActivityViewportDetaches() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        val first = keyedMarker("row-1")
        val second = keyedMarker("row-2")
        val store =
            activityStore(
                scope = scope,
                prewarmReplyThread = { marker ->
                    calls += marker
                    if (marker.key == first.key) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                    DataResult.Content(Unit)
                }
            )

        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(first, second)))
        firstStarted.await()

        // Opening the clicked Thread removes the Activity viewport owner, but the store-owned
        // click-progressive tail must keep draining outside that route.
        store.dispatch(SharedActivityIntent.DetachPrewarmViewport)
        releaseFirst.complete(Unit)

        assertEquals(listOf(first.key, second.key), calls.map { it.key })
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

        assertEquals(ordered.take(3).map { it.key }, calls.map { it.key })
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

        assertEquals(listOf(fresh.key), calls.map { it.key })
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
                    if (marker.key == firstVisible.key || marker.key == secondVisible.key) releaseVisible.await()
                    DataResult.Content(Unit)
                }
            )

        // Two visible markers saturate the shared Semaphore(2).
        store.dispatch(
            SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(firstVisible, secondVisible))
        )
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(tail)))

        // Tail cannot acquire a permit while the visible set holds both.
        assertEquals(setOf(firstVisible.key, secondVisible.key), calls.map { it.key }.toSet())
        assertTrue(calls.none { it.key == tail.key })

        releaseVisible.complete(Unit)
        assertTrue(calls.any { it.key == tail.key })
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
                    if (marker.key == dropped.key) {
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
        assertEquals(listOf(dropped.key), started.map { it.key })

        // New ordered set no longer contains "dropped" -> its in-flight job is cancelled.
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(kept)))

        assertEquals(listOf(dropped.key), cancelled.map { it.key })
        assertTrue(started.any { it.key == kept.key })
        scope.cancel()
    }

    @Test
    fun activityTailReplanReschedulesInFlightItemWithoutExceedingOneActive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gates = mutableMapOf<ActivityReplyThreadPrewarmMarker, CompletableDeferred<Unit>>()
        val calls = mutableListOf<ActivityReplyThreadPrewarmMarker>()
        var active = 0
        var maxActive = 0
        val ledger = SharedThreadFreshnessLedger()
        val firstVersion = keyedMarker("A")
        val below = keyedMarker("B")
        val storeMarkerKey = firstVersion.key
        val secondVersion = firstVersion
        val store =
            activityStore(
                scope = scope,
                replyThreadFreshnessLedger = ledger,
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
        // A(v1) is warmed at the top of the tail and suspends in-flight on its gate.
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(firstVersion)))
        assertEquals(1, calls.size)

        // Replan while A(v1) is still in-flight: A is re-versioned to v2 and B appears below it.
        ledger.advanceAllOwners(storeMarkerKey.serverId, storeMarkerKey.threadChannelId)
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(secondVersion, below)))

        // The superseded A(v1) is unwound; the tail then reschedules A(v2) and warms B, one at a
        // time. Release the gates so the serial tail can drain.
        val latestVersion = calls.last { it.key == storeMarkerKey }
        gates.getOrPut(latestVersion) { CompletableDeferred() }.complete(Unit)
        val actualBelow = calls.last { it.key == below.key }
        gates.getOrPut(actualBelow) { CompletableDeferred() }.complete(Unit)

        // A(v2) must be rescheduled (no permanent warm miss) and B must be warmed.
        assertEquals(2, calls.count { it.key == storeMarkerKey })
        assertTrue(calls.any { it.key == below.key })
        // The tail never runs more than one prewarm concurrently across the replan.
        assertTrue(maxActive <= 1)
        // A(v1)'s stale result is discarded; only the latest version is recorded as warmed.
        assertTrue(calls.filter { it.key == storeMarkerKey }.map { it.contentGeneration }.distinctBy { it }.size == 2)
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
        val ledger = SharedThreadFreshnessLedger()
        val firstVersion = keyedMarker("A")
        val store =
            activityStore(
                scope = scope,
                replyThreadFreshnessLedger = ledger,
                prewarmReplyThread = { marker ->
                    calls += marker
                    active += 1
                    if (active > maxActive) maxActive = active
                    try {
                        if (marker.key == firstVersion.key && calls.size == 1) {
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
        val secondVersion = keyedMarker("A")
        val secondBelow = keyedMarker("B")
        val thirdVersion = keyedMarker("A")
        val thirdBelow = keyedMarker("C")

        // A(v1) is warmed at the top of the tail and is now in-flight.
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(firstVersion)))
        running.await()
        assertEquals(listOf(firstVersion.key), calls.map { it.key })

        // Two rapid consecutive replans arrive while A(v1) is still SLOWLY draining.
        ledger.advanceAllOwners(firstVersion.key.serverId, firstVersion.key.threadChannelId)
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(secondVersion, secondBelow)))
        ledger.advanceAllOwners(firstVersion.key.serverId, firstVersion.key.threadChannelId)
        store.dispatch(SharedActivityIntent.PrewarmActivityTail(listOf(thirdVersion, thirdBelow)))

        // The drain barrier holds every later generation: nothing new may start while A(v1) drains.
        assertEquals(listOf(firstVersion.key), calls.map { it.key })
        assertTrue(maxActive <= 1)

        // Once the slow drain finishes, only the LATEST generation (v3) runs, one item at a time.
        drainGate.complete(Unit)

        assertEquals(2, calls.count { it.key == thirdVersion.key })
        assertTrue(calls.any { it.key == thirdBelow.key })
        // The superseded v2 generation never launched (its loop was cancelled before it ran).
        assertTrue(calls.none { it.key == secondBelow.key })
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
    fun setFilterRetainsCachedFilterSnapshotsForKeepAliveSwipe() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val allSnap = snapshot(listOf(activityItem("channel-all", unreadCount = 1)), totalUnreadCount = 1)
        val unreadSnap = snapshot(listOf(activityItem("channel-unread", unreadCount = 1)), totalUnreadCount = 1)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(allSnap, unreadSnap, allSnap))
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        assertEquals(listOf("channel:channel-all"), store.state.value.items.map { it.key })
        assertEquals(listOf("channel:channel-all"), store.state.value.filterSnapshots[InboxFilter.All]?.items?.map { it.key })

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter)
        assertEquals(listOf("channel:channel-unread"), store.state.value.items.map { it.key })
        assertEquals(listOf("channel:channel-all"), store.state.value.filterSnapshots[InboxFilter.All]?.items?.map { it.key })
        assertEquals(listOf("channel:channel-unread"), store.state.value.filterSnapshots[InboxFilter.Unread]?.items?.map { it.key })
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
    fun activityFilterProjectionRetainsUnfollowedOnlyInAllAndNeverCountsItsAttention() {
        val unfollowed =
            threadItem("thread-unfollowed", unreadCount = 5).copy(
                isFollowing = false,
                hasMention = true
            )
        val followed =
            threadItem("thread-followed", unreadCount = 2).copy(
                isFollowing = true,
                hasMention = true
            )
        val raw = snapshot(listOf(unfollowed, followed), totalCount = 2, totalUnreadCount = 2)

        val all = raw.projectActivityFilter(InboxFilter.All)
        assertEquals(listOf("thread:thread-unfollowed", "thread:thread-followed"), all.items.map(InboxItem::key))
        assertEquals(0, all.items.first().unreadCount)
        assertEquals(false, all.items.first().hasMention)
        assertEquals(2, all.totalUnreadCount)
        assertEquals(2, all.totalCount)

        val unread = raw.projectActivityFilter(InboxFilter.Unread)
        assertEquals(listOf("thread:thread-followed"), unread.items.map(InboxItem::key))
        assertEquals(2, unread.totalUnreadCount)
        assertEquals(1, unread.totalCount)

        val mentions = raw.projectActivityFilter(InboxFilter.Mentions)
        assertEquals(listOf("thread:thread-followed"), mentions.items.map(InboxItem::key))
        assertEquals(2, mentions.totalUnreadCount)
        assertEquals(1, mentions.totalCount)
    }

    @Test
    fun unfollowedRowSurvivesPaginationRefreshAndPersistedReentryWithoutDuplicates() = runBlocking {
        val persistStorage = InMemoryPersistStorage()
        val persistSlice = createSharedActivityPersistSlice(persistStorage)
        val unfollowed =
            threadItem("thread-unfollowed", unreadCount = 4).copy(
                isFollowing = false,
                hasMention = true
            )
        val followed = threadItem("thread-followed").copy(isFollowing = true)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(
                            listOf(unfollowed),
                            hasMore = true,
                            nextOffset = 1,
                            totalCount = 2,
                            totalUnreadCount = 0 /* compliant server: unfollowed attention is zero */
                        ),
                        snapshot(
                            listOf(unfollowed, followed),
                            hasMore = false,
                            nextOffset = 2,
                            totalCount = 2,
                            totalUnreadCount = 0 /* compliant server: unfollowed attention is zero */
                        )
                    ),
                moreSnapshots =
                    listOf(
                        snapshot(
                            listOf(followed),
                            hasMore = false,
                            nextOffset = 2,
                            totalCount = 2,
                            totalUnreadCount = 0
                        )
                    )
            )
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstStore =
            activityStore(
                scope = firstScope,
                inboxRepository = inbox,
                persistSlices = listOf(persistSlice)
            )

        firstStore.dispatch(SharedActivityIntent.LoadInitial)
        firstStore.dispatch(SharedActivityIntent.LoadMore)
        firstStore.dispatch(SharedActivityIntent.Refresh)

        assertEquals(
            listOf("thread:thread-unfollowed", "thread:thread-followed"),
            firstStore.state.value.items.map(InboxItem::key)
        )
        val refreshedUnfollowed = firstStore.state.value.items.first()
        assertEquals(false, refreshedUnfollowed.isFollowing)
        assertEquals(0, refreshedUnfollowed.unreadCount)
        assertEquals(false, refreshedUnfollowed.hasMention)
        assertEquals(0, firstStore.state.value.totalUnreadCount)

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pendingInbox = DeferredInboxRepository()
        val reenteredStore =
            activityStore(
                scope = secondScope,
                inboxRepository = pendingInbox,
                persistSlices = listOf(persistSlice)
            )

        reenteredStore.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(true, reenteredStore.state.value.refreshing)
        assertEquals(
            listOf("thread:thread-unfollowed", "thread:thread-followed"),
            reenteredStore.state.value.items.map(InboxItem::key)
        )
        val hydratedUnfollowed = reenteredStore.state.value.items.first()
        assertEquals(false, hydratedUnfollowed.isFollowing)
        assertEquals(0, hydratedUnfollowed.unreadCount)
        assertEquals(false, hydratedUnfollowed.hasMention)
        firstScope.cancel()
        secondScope.cancel()
    }

    @Test
    fun unfollowedThreadRemainsInAllAndRealtimeRepliesStayAttentionless() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val unfollowed =
            threadItem("thread-unfollowed", unreadCount = 0).copy(
                isFollowing = false,
                replyCount = 1,
                hasMention = false
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots = listOf(snapshot(listOf(unfollowed), totalUnreadCount = 0))
                    )
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("unfollowed-reply", channelId = "thread-unfollowed"))
            )
        )

        val row = store.state.value.items.single()
        assertEquals(false, row.isFollowing)
        assertEquals(2, row.replyCount)
        assertEquals(0, row.unreadCount)
        assertEquals(false, row.hasMention)
        assertEquals(null, row.firstUnreadMessageId)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun unfollowedThreadUpdatedEventCannotRestoreAttention() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val unfollowed =
            threadItem("thread-unfollowed").copy(
                isFollowing = false,
                replyCount = 1
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(unfollowed))))
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("parent-message"),
                    threadChannelId = ChannelId("thread-unfollowed"),
                    replyCount = 2,
                    lastReplyAt = "2026-09-01T00:00:00Z",
                    participantIds = emptyList(),
                    unreadCount = 5,
                    firstUnreadMessageId = MessageId("reply")
                )
            )
        )

        assertEquals(0, store.state.value.items.single().unreadCount)
        assertEquals(null, store.state.value.items.single().firstUnreadMessageId)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun activityUnfollowKeepsRowAndClearsAttentionThroughSharedTransport() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val conversation = FakeConversationRepository().apply {
            unfollowResult = DataResult.Content(ThreadActionResult(ChannelId("thread-followed"), changed = true))
        }
        val followed =
            threadItem("thread-followed", unreadCount = 3).copy(
                isFollowing = true,
                hasMention = true
            )
        val store =
            activityStore(
                scope = scope,
                conversationRepository = conversation,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(followed), totalUnreadCount = 3)))
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.UnfollowThread(followed))

        val row = store.state.value.items.single()
        assertEquals(false, row.isFollowing)
        assertEquals(0, row.unreadCount)
        assertEquals(false, row.hasMention)
        assertEquals(null, row.firstUnreadMessageId)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(listOf(ChannelId("thread-followed")), conversation.unfollowedThreadChannelIds)
        scope.cancel()
    }

    @Test
    fun activityUnfollowFailureRefreshesBackToTheServerFollowedRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val followed =
            threadItem("thread-followed", unreadCount = 3).copy(
                isFollowing = true,
                hasMention = true
            )
        val conversation = FakeConversationRepository().apply {
            unfollowResult = DataResult.Error(ScreenError("offline"))
        }
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(followed), totalUnreadCount = 3),
                        snapshot(listOf(followed), totalUnreadCount = 3)
                    )
            )
        val store =
            activityStore(
                scope = scope,
                conversationRepository = conversation,
                inboxRepository = inbox
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.UnfollowThread(followed))

        val row = store.state.value.items.single()
        assertEquals(true, row.isFollowing)
        assertEquals(3, row.unreadCount)
        assertEquals(true, row.hasMention)
        assertEquals(2, inbox.initialLoadCount)
        assertEquals(listOf(ChannelId("thread-followed")), conversation.unfollowedThreadChannelIds)
        scope.cancel()
    }

    @Test
    fun activityFollowRemovesChipStateWithoutFabricatingAttention() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val conversation = FakeConversationRepository().apply {
            followResult = DataResult.Content(ThreadActionResult(ChannelId("thread-unfollowed"), changed = true))
        }
        val unfollowed =
            threadItem("thread-unfollowed").copy(
                isFollowing = false,
                unreadCount = 0,
                hasMention = false
            )
        val store =
            activityStore(
                scope = scope,
                conversationRepository = conversation,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(unfollowed))))
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.FollowThread(unfollowed))

        val row = store.state.value.items.single()
        assertEquals(true, row.isFollowing)
        assertEquals(0, row.unreadCount)
        assertEquals(false, row.hasMention)
        assertEquals(listOf(MessageId("parent-message")), conversation.followedParentMessageIds)
        scope.cancel()
    }

    @Test
    fun activityFollowFailureLeavesServerProjectedUnfollowedRowAfterRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val conversation = FakeConversationRepository().apply {
            followResult = DataResult.Error(ScreenError("offline"))
        }
        val unfollowed =
            threadItem("thread-unfollowed").copy(
                isFollowing = false,
                unreadCount = 0,
                hasMention = false
            )
        val store =
            activityStore(
                scope = scope,
                conversationRepository = conversation,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(unfollowed))))
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.FollowThread(unfollowed))

        val row = store.state.value.items.single()
        assertEquals(false, row.isFollowing)
        assertEquals(0, row.unreadCount)
        assertEquals(false, row.hasMention)
        assertEquals(listOf(MessageId("parent-message")), conversation.followedParentMessageIds)
        scope.cancel()
    }

    @Test
    fun directMentionReplyDoesNotGuessRefollowBeforeCanonicalStateArrives() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val unfollowed =
            threadItem("thread-unfollowed").copy(
                isFollowing = false,
                unreadCount = 0,
                hasMention = false
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(unfollowed))))
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "direct-mention-reply",
                        channelId = "thread-unfollowed",
                        body = "@user-1 mention",
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

        assertEquals(false, store.state.value.items.single().isFollowing)
        assertEquals(0, store.state.value.items.single().unreadCount)
        assertEquals(false, store.state.value.items.single().hasMention)
        scope.cancel()
    }

    @Test
    fun canonicalServerRefollowMakesChipDisappearAndRestoresOnlyServerAttention() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val unfollowed =
            threadItem("thread-unfollowed").copy(
                isFollowing = false,
                replyCount = 1,
                unreadCount = 0,
                hasMention = false
            )
        val serverRefollowed =
            unfollowed.copy(
                isFollowing = true,
                replyCount = 2,
                latestActivityMessageId = MessageId("direct-mention-reply"),
                unreadCount = 1,
                hasMention = true,
                firstUnreadMessageId = MessageId("direct-mention-reply")
            )
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(unfollowed)),
                        snapshot(listOf(serverRefollowed), totalUnreadCount = 1)
                    )
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.acceptRealtime(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("direct-mention-reply", channelId = "thread-unfollowed"))
            )
        )

        val row = store.state.value.items.single()
        assertEquals(true, row.isFollowing)
        assertEquals(1, row.unreadCount)
        assertEquals(true, row.hasMention)
        assertEquals(MessageId("direct-mention-reply"), row.firstUnreadMessageId)
        assertEquals(1, store.state.value.totalUnreadCount)
        assertEquals(2, inbox.initialLoadCount)
        scope.cancel()
    }

    @Test
    fun canonicalRefreshRequestsLoadedDepthAndPreservesLoadedTailOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val head = (1..30).map { index -> activityItem("channel-$index") }
        val tail = (31..60).map { index -> activityItem("channel-$index") }
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(head, hasMore = true, nextOffset = 30, totalCount = 60),
                        snapshot(head + tail, hasMore = false, nextOffset = 60, totalCount = 60)
                    ),
                moreSnapshots =
                    listOf(
                        snapshot(tail, hasMore = false, nextOffset = 60, totalCount = 60)
                    )
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.LoadMore)
        val loadedTailKeys = store.state.value.items.drop(30).map { item -> item.key }

        store.dispatch(SharedActivityIntent.Refresh)

        assertEquals(listOf(30, 60), inbox.initialLoadLimits)
        assertEquals(60, store.state.value.items.size)
        assertEquals(loadedTailKeys, store.state.value.items.drop(30).map { item -> item.key })
        assertEquals(false, store.state.value.loading)
        assertEquals(false, store.state.value.refreshing)
        scope.cancel()
    }

    @Test
    fun canonicalRefreshContinuesPastServerPageCapToPreserveDeepLoadedTail() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rows = (1..120).map { index -> activityItem("channel-$index") }
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(rows.take(30), hasMore = true, nextOffset = 30, totalCount = 120),
                        snapshot(rows.take(100), hasMore = true, nextOffset = 100, totalCount = 120)
                    ),
                moreSnapshots =
                    listOf(
                        snapshot(rows.slice(30 until 60), hasMore = true, nextOffset = 60, totalCount = 120),
                        snapshot(rows.slice(60 until 90), hasMore = true, nextOffset = 90, totalCount = 120),
                        snapshot(rows.slice(90 until 120), hasMore = false, nextOffset = 120, totalCount = 120),
                        snapshot(rows.slice(99 until 119), hasMore = true, nextOffset = 120, totalCount = 120),
                        snapshot(rows.slice(119 until 120), hasMore = false, nextOffset = 121, totalCount = 120)
                    )
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        repeat(3) { store.dispatch(SharedActivityIntent.LoadMore) }
        val deepTailKeys = store.state.value.items.drop(100).map { item -> item.key }

        store.dispatch(SharedActivityIntent.Refresh)

        assertEquals(listOf(30, 100), inbox.initialLoadLimits)
        assertEquals(listOf(30, 60, 90, 100, 120), inbox.loadMoreOffsets)
        assertEquals(120, store.state.value.items.size)
        assertEquals(deepTailKeys, store.state.value.items.drop(100).map { item -> item.key })
        scope.cancel()
    }

    @Test
    fun failedRefreshContinuationKeepsThePreviouslyLoadedDeepSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rows = (1..120).map { index -> activityItem("channel-$index") }
        var initialLoads = 0
        var moreLoads = 0
        val inbox =
            object : InboxRepository {
                override suspend fun loadInitial(
                    serverId: ServerId,
                    filter: InboxFilter,
                    limit: Int
                ): DataResult<InboxSnapshot> {
                    initialLoads += 1
                    return DataResult.Content(
                        if (initialLoads == 1) {
                            snapshot(rows.take(30), hasMore = true, nextOffset = 30, totalCount = 120)
                        } else {
                            snapshot(rows.take(100), hasMore = true, nextOffset = 100, totalCount = 120)
                        }
                    )
                }

                override suspend fun loadMore(
                    serverId: ServerId,
                    filter: InboxFilter,
                    offset: Int,
                    limit: Int
                ): DataResult<InboxSnapshot> {
                    moreLoads += 1
                    return when (moreLoads) {
                        1 -> DataResult.Content(snapshot(rows.slice(30 until 60), hasMore = true, nextOffset = 60, totalCount = 120))
                        2 -> DataResult.Content(snapshot(rows.slice(60 until 90), hasMore = true, nextOffset = 90, totalCount = 120))
                        3 -> DataResult.Content(snapshot(rows.slice(90 until 120), hasMore = false, nextOffset = 120, totalCount = 120))
                        else -> DataResult.Error(ScreenError("tail_failed"))
                    }
                }

                override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> =
                    DataResult.Content(InboxDoneResult(channelId))
            }
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        repeat(3) { store.dispatch(SharedActivityIntent.LoadMore) }
        val stableKeys = store.state.value.items.map { item -> item.key }

        store.dispatch(SharedActivityIntent.Refresh)

        assertEquals(stableKeys, store.state.value.items.map { item -> item.key })
        assertEquals(120, store.state.value.items.size)
        assertEquals("tail_failed", store.state.value.errorCode)
        assertEquals(false, store.state.value.refreshing)
        scope.cancel()
    }

    @Test
    fun advancingDuplicateRefreshPagesAreBoundedAndKeepThePreviousSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rows = (1..30).map { index -> activityItem("channel-$index") }
        var initialLoads = 0
        var continuationLoads = 0
        val inbox =
            object : InboxRepository {
                override suspend fun loadInitial(
                    serverId: ServerId,
                    filter: InboxFilter,
                    limit: Int
                ): DataResult<InboxSnapshot> {
                    initialLoads += 1
                    return DataResult.Content(
                        if (initialLoads == 1) {
                            snapshot(rows, hasMore = false, nextOffset = 30, totalCount = 30)
                        } else {
                            snapshot(rows.take(1), hasMore = true, nextOffset = 1, totalCount = 30)
                        }
                    )
                }

                override suspend fun loadMore(
                    serverId: ServerId,
                    filter: InboxFilter,
                    offset: Int,
                    limit: Int
                ): DataResult<InboxSnapshot> {
                    continuationLoads += 1
                    return DataResult.Content(
                        snapshot(
                            rows.take(1),
                            hasMore = true,
                            nextOffset = offset + 1,
                            totalCount = 30
                        )
                    )
                }

                override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> =
                    DataResult.Content(InboxDoneResult(channelId))
            }
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        val stableKeys = store.state.value.items.map { item -> item.key }
        store.dispatch(SharedActivityIntent.Refresh)

        assertEquals(3, continuationLoads)
        assertEquals(stableKeys, store.state.value.items.map { item -> item.key })
        assertEquals("activity_pagination_stalled", store.state.value.errorCode)
        assertEquals(false, store.state.value.refreshing)
        scope.cancel()
    }

    @Test
    fun nonAdvancingRefreshOffsetKeepsThePreviousSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rows = (1..30).map { index -> activityItem("channel-$index") }
        var initialLoads = 0
        val inbox =
            object : InboxRepository {
                override suspend fun loadInitial(
                    serverId: ServerId,
                    filter: InboxFilter,
                    limit: Int
                ): DataResult<InboxSnapshot> {
                    initialLoads += 1
                    return DataResult.Content(
                        if (initialLoads == 1) {
                            snapshot(rows, hasMore = false, nextOffset = 30, totalCount = 30)
                        } else {
                            snapshot(rows.take(10), hasMore = true, nextOffset = 10, totalCount = 30)
                        }
                    )
                }

                override suspend fun loadMore(
                    serverId: ServerId,
                    filter: InboxFilter,
                    offset: Int,
                    limit: Int
                ): DataResult<InboxSnapshot> =
                    DataResult.Content(
                        snapshot(
                            rows.slice(10 until 30),
                            hasMore = true,
                            nextOffset = offset,
                            totalCount = 30
                        )
                    )

                override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> =
                    DataResult.Content(InboxDoneResult(channelId))
            }
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        val stableKeys = store.state.value.items.map { item -> item.key }
        store.dispatch(SharedActivityIntent.Refresh)

        assertEquals(stableKeys, store.state.value.items.map { item -> item.key })
        assertEquals("activity_pagination_stalled", store.state.value.errorCode)
        assertEquals(false, store.state.value.refreshing)
        scope.cancel()
    }

    @Test
    fun visibleRefreshBurstKeepsContentAndCoalescesWhileCanonicalRefreshIsInFlight() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNext(activityItem("channel-1"))

        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(SharedActivityIntent.Refresh)

        assertEquals(1, inbox.pendingCount)
        assertEquals(false, store.state.value.loading)
        assertEquals(true, store.state.value.refreshing)
        assertEquals(listOf("channel-1"), store.state.value.items.map { item -> item.channelId.value })

        inbox.completeNext(activityItem("channel-1"))
        assertEquals(false, store.state.value.refreshing)
        scope.cancel()
    }

    @Test
    fun realtimeAndReadStateBurstUsesOneBackgroundReconcileWithoutReadStateReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val head = (1..30).map { index -> activityItem("channel-$index") }
        val tail = (31..60).map { index -> activityItem("channel-$index") }
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(head, hasMore = true, nextOffset = 30, totalCount = 60),
                        snapshot(head + tail, hasMore = false, nextOffset = 60, totalCount = 60)
                    ),
                moreSnapshots = listOf(snapshot(tail, hasMore = false, nextOffset = 60, totalCount = 60))
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.LoadMore)
        store.acceptRealtime(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-burst", channelId = "channel-1"))
            )
        )
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-1", version = 1, maxReadSeq = 10)
            )
        )
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-2", version = 1, maxReadSeq = 20)
            )
        )

        assertEquals(2, inbox.initialLoadCount)
        assertEquals(listOf(30, 60), inbox.initialLoadLimits)
        assertEquals(60, store.state.value.items.size)
        assertEquals((31..60).map { index -> "channel:channel-$index" }, store.state.value.items.drop(30).map { it.key })
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
    fun unreadFilterMarkReadKeepsTheRowShownReadAndCallsReadAll() = runBlocking {
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

        // The row just read stays listed, read, until the user moves on (RetainedReadRowTest).
        assertEquals(listOf("channel:channel-1" to 0), store.state.value.items.map { it.key to it.unreadCount })
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
    fun staleUnreadFilterRefreshAfterMarkReadKeepsTheRetainedRowRead() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)
        val unreadRow = activityItem("channel-1", unreadCount = 2)

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        inbox.completeNextSnapshot(snapshot(listOf(unreadRow), totalCount = 1, totalUnreadCount = 2))
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        inbox.completeNextSnapshot(snapshot(listOf(unreadRow), totalCount = 1, totalUnreadCount = 2))

        // The lagging copy cannot bring the unread state back; the row stays only as the retained read row.
        assertEquals(listOf("channel:channel-1" to 0), store.state.value.items.map { it.key to it.unreadCount })
        assertEquals(0, store.state.value.totalCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun processReadPredictionClearsActivityAndOlderFailureCannotRestoreIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 2)), totalUnreadCount = 2))
            )
        val ownerFactory = { serverId: String, accountId: String ->
            ReadReceiptOwner("production", accountId, serverId)
        }
        val store = activityStore(scope, inbox, readReceiptOwnerFactory = ownerFactory)
        val owner = ReadReceiptOwner("production", "user-1", "server-1")
        val prediction =
            ReadReceiptPrediction(
                owner = owner,
                channelId = ChannelId("channel-1"),
                revision = 2L,
                target = ReadReceiptTarget.All
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.applyReadReceiptPrediction(
            prediction.copy(
                owner = owner.copy(environmentId = "staging"),
                revision = 1L
            )
        )
        assertEquals(2, store.state.value.items.single().unreadCount)
        store.applyReadReceiptPrediction(prediction)
        store.handleReadReceiptFailure(
            ReadReceiptRequest.All(
                channelId = ChannelId("channel-1"),
                owner = owner,
                revision = 1L
            )
        )

        assertEquals(0, store.state.value.items.single().unreadCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(1, inbox.initialLoadCount)
        scope.cancel()
    }

    @Test
    fun processReadPredictionSerializesWithRealtimePatchWithoutLosingEitherTransition() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val realtimeOwnerHeld = CompletableDeferred<Unit>()
        val releaseRealtimeOwner = CompletableDeferred<Unit>()
        val predictionEnteredOwner = CompletableDeferred<Unit>()
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(
                            listOf(threadItem("thread-channel-1", unreadCount = 2)),
                            totalUnreadCount = 2
                        )
                    )
            )
        val ownerFactory = { serverId: String, accountId: String ->
            ReadReceiptOwner("production", accountId, serverId)
        }
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                readReceiptOwnerFactory = ownerFactory,
                onRealtimePatchOwnerEntered = {
                    realtimeOwnerHeld.complete(Unit)
                    runBlocking { releaseRealtimeOwner.await() }
                },
                onReadReceiptPredictionOwnerEntered = {
                    predictionEnteredOwner.complete(Unit)
                }
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        // LoadInitial is asynchronous. A realtime event that arrives before the row exists has nothing to
        // patch and is left to the reconcile reload, which this fake inbox cannot answer with the message.
        withTimeout(5_000L) { while (store.state.value.items.isEmpty()) delay(10L) }
        val owner = ReadReceiptOwner("production", "user-1", "server-1")

        val realtimeJob =
            scope.launch {
                store.dispatch(
                    SharedActivityIntent.ApplyRealtimeEvent(
                        MessageNew(
                            message(
                                id = "message-concurrent",
                                channelId = "thread-channel-1",
                                body = "Concurrent realtime preview"
                            )
                        )
                    )
                )
            }
        withTimeout(2_000L) { realtimeOwnerHeld.await() }

        val predictionJob =
            scope.launch {
                store.dispatch(
                    SharedActivityIntent.ApplyReadReceiptPrediction(
                        ReadReceiptPrediction(
                            owner = owner,
                            channelId = ChannelId("thread-channel-1"),
                            revision = 1L,
                            target = ReadReceiptTarget.All
                        )
                    )
                )
            }
        val enteredWhileRealtimeHeld =
            withTimeoutOrNull(200L) {
                predictionEnteredOwner.await()
            }
        assertEquals(null, enteredWhileRealtimeHeld)

        releaseRealtimeOwner.complete(Unit)
        realtimeJob.join()
        predictionJob.join()

        val item = store.state.value.items.single()
        assertEquals(MessageId("message-concurrent"), item.latestActivityMessageId)
        assertEquals("Concurrent realtime preview", item.latestActivityPreview)
        assertEquals(0, item.unreadCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertTrue(predictionEnteredOwner.isCompleted)
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
                firstUnreadMessageId = MessageId("first-channel-1-new"),
                latestActivityMessageId = MessageId("latest-channel-1-new")
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
    fun markUnreadUsesOptimisticPatchWithoutRefreshingActivity() = runBlocking {
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
        assertEquals(1, inbox.initialLoadCount)
        assertEquals(1, store.state.value.items.single().unreadCount)
        assertEquals(1, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun markAllReadCommitsOptimisticPatchThenRefreshesOnceLikeWeb() = runBlocking {
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
        assertEquals(2, inbox.initialLoadCount, "web inboxStore.ts:1405-1408 refreshes after read-all")
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
    fun markAllReadKeepsReadRowsInMentionsFilter() = runBlocking {
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

        assertEquals(listOf(row.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)), store.state.value.items, "slock channelService.ts:13136-13168")
        assertEquals(1, store.state.value.totalCount)
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

        val staleSnapshot = store.state.value.snapshot
        store.dispatch(SharedActivityIntent.PullRefresh)

        assertEquals(false, store.state.value.loading)
        assertEquals(true, store.state.value.refreshing)
        assertEquals(true, store.state.value.pullRefreshing)
        assertEquals(staleSnapshot, store.state.value.snapshot)
        assertEquals(listOf("channel-2"), store.state.value.items.map { it.channelId.value })
        inbox.completeNext(activityItem("channel-3"))
        assertEquals(false, store.state.value.refreshing)
        assertEquals(false, store.state.value.pullRefreshing)
        assertEquals(listOf("channel-3"), store.state.value.items.map { it.channelId.value })
        scope.cancel()
    }

    @Test
    fun scopedRealtimeRefreshRequestsDoNotReplaceVisibleActivitySnapshot() = runBlocking {
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

            assertEquals(1, inbox.initialLoadCount, "scope=$refreshScope")
            assertEquals(index + 1, store.state.value.totalUnreadCount, "scope=$refreshScope")
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
    fun readStateFactBeforeInitialRequestRecordsCheckpointWithoutTakingListLoadOwnership() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 0)), totalUnreadCount = 0))
            )
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
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

        assertEquals(0, inbox.initialLoadCount)
        assertEquals(1, homeReconcileRequests)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(null, store.state.value.snapshot)
        assertEquals(
            build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 1, maxReadSeq = 10),
            versionStore.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1"))
        )
        scope.cancel()
    }

    @Test
    fun readStateFactAlreadyAcceptedByHomeDoesNotRefreshActivity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 0)), totalUnreadCount = 0))
            )
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        versionStore.writeReadStateCheckpoint(
            serverId = ServerId("server-1"),
            memberId = MemberId("user-1"),
            scopeId = ChannelId("channel-1"),
            checkpoint = build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 3, maxReadSeq = 30)
        )
        val store = activityStore(scope, inboxRepository = inbox, perReceiverStateVersionStore = versionStore)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-1", version = 3, maxReadSeq = 30)
            )
        )
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-1", version = 3, maxReadSeq = 30)
            )
        )

        assertEquals(0, inbox.initialLoadCount)
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
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
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
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
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
    fun higherVersionReadStateRewindUpdatesCheckpointWithoutRefreshingVisibleActivity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 0)), totalUnreadCount = 0),
                        snapshot(listOf(activityItem("channel-1", unreadCount = 2)), totalUnreadCount = 2)
                    )
            )
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
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
                readStateEvent(scopeId = "channel-1", version = 6, maxReadSeq = 20)
            )
        )

        assertEquals(1, inbox.initialLoadCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(
            build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 6, maxReadSeq = 20),
            versionStore.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1"))
        )
        scope.cancel()
    }

    @Test
    fun readStateBulkScopesFoldIndependentlyWithoutSchedulingActivityRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(emptyList())))
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        versionStore.writeReadStateCheckpoint(
            serverId = ServerId("server-1"),
            memberId = MemberId("user-1"),
            scopeId = ChannelId("channel-a"),
            checkpoint = build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(version = 5, maxReadSeq = 50)
        )
        val store = activityStore(scope, inboxRepository = inbox, perReceiverStateVersionStore = versionStore)

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

        assertEquals(0, inbox.initialLoadCount)
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
    fun acceptedReadStateFactDoesNotReplaceInFlightRouteBaseline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inboxRepository = inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-1", version = 1, maxReadSeq = 10)
            )
        )
        inbox.completeNextSnapshot(
            snapshot(listOf(activityItem("channel-1", unreadCount = 3)), totalUnreadCount = 3)
        )
        assertEquals(3, store.state.value.totalUnreadCount)
        assertEquals(0, inbox.pendingCount)
        scope.cancel()
    }

    @Test
    fun readStateFactDoesNotRestartExplicitCanonicalRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inboxRepository = inbox)
        val visibleUnread = activityItem("channel-1", unreadCount = 3).copy(hasMention = true)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(visibleUnread), totalUnreadCount = 3))
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "channel-1", version = 1, maxReadSeq = 10)
            )
        )

        assertEquals(3, store.state.value.totalUnreadCount)
        assertEquals(true, store.state.value.items.single().hasMention)

        inbox.completeNextSnapshot(
            snapshot(listOf(activityItem("channel-1", unreadCount = 0)), totalUnreadCount = 0)
        )
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(false, store.state.value.items.single().hasMention)
        assertEquals(0, inbox.pendingCount)
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
                inboxRepository = inbox
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
        assertEquals(1, patched.unreadCount, "web never adds unread per reply")
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
                    )
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
        assertEquals(1, patched.unreadCount, "unread is the server's")
        scope.cancel()
    }

    @Test
    fun threadUpdatedPatchesMonotonicReplyMetaButNeverOverwritesUnread() = runBlocking {
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
                    )
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
        assertEquals(1, patched.unreadCount, "web updateThreadActivityMeta never overwrites unread")
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
        assertEquals(1, store.state.value.items.single().unreadCount)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("parent-message"),
                    threadChannelId = ChannelId("thread-channel-1"),
                    replyCount = 3,
                    lastReplyAt = "2026-07-13T03:05:00Z",
                    participantIds = emptyList(),
                    unreadCount = 0,
                    firstUnreadMessageId = MessageId("must-be-cleared")
                )
            )
        )

        assertEquals(1, store.state.value.items.single().unreadCount)
        assertEquals(MessageId("first-thread-channel-1"), store.state.value.items.single().firstUnreadMessageId)
        scope.cancel()
    }

    @Test
    fun threadUpdatedAdvancesFreshnessOnlyForAcceptedHighWaterChange() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ledger = SharedThreadFreshnessLedger()
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots =
                            listOf(snapshot(listOf(threadItem("thread-channel-1").copy(replyCount = 2))))
                    ),
                replyThreadFreshnessLedger = ledger
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        val initial = ledger.captureContent("server-1", "thread-channel-1")
        val accepted =
            ThreadUpdated(
                parentMessageId = MessageId("parent-message"),
                threadChannelId = ChannelId("thread-channel-1"),
                replyCount = 3,
                lastReplyAt = "2026-07-13T03:05:00Z",
                participantIds = emptyList()
            )

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(accepted))
        val advanced = ledger.captureContent("server-1", "thread-channel-1")
        assertTrue(initial !== advanced)

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(accepted))
        assertTrue(advanced === ledger.captureContent("server-1", "thread-channel-1"))

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                accepted.copy(replyCount = 2, lastReplyAt = "2026-07-13T03:01:00Z")
            )
        )
        assertTrue(advanced === ledger.captureContent("server-1", "thread-channel-1"))
        scope.cancel()
    }

    @Test
    fun messageUpdatedAdvancesOnceForSameMessageContentMutation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ledger = SharedThreadFreshnessLedger()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = FakeInboxRepository(listOf(snapshot(listOf(threadItem("thread-channel-1"))))),
                replyThreadFreshnessLedger = ledger
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        val initial = ledger.captureContent("server-1", "thread-channel-1")
        val update =
            MessageUpdated(
                message(
                    id = "reply-1",
                    channelId = "thread-channel-1",
                    body = "edited body"
                )
            )

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(update))
        val advanced = ledger.captureContent("server-1", "thread-channel-1")
        assertTrue(initial !== advanced)

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(update))
        assertTrue(advanced === ledger.captureContent("server-1", "thread-channel-1"))
        scope.cancel()
    }

    @Test
    fun canonicalReconcileAdvancesOnlyWhenAcceptedThreadContentChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ledger = SharedThreadFreshnessLedger()
        val original = threadItem("thread-channel-1").copy(replyCount = 2)
        val changed = original.copy(replyCount = 3, latestActivityAt = "2026-07-13T03:05:00Z")
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots =
                            listOf(snapshot(listOf(original)), snapshot(listOf(original)), snapshot(listOf(changed)))
                    ),
                replyThreadFreshnessLedger = ledger
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        val initial = ledger.captureContent("server-1", "thread-channel-1")
        store.dispatch(SharedActivityIntent.Refresh)
        assertTrue(initial === ledger.captureContent("server-1", "thread-channel-1"))

        store.dispatch(SharedActivityIntent.Refresh)
        assertTrue(initial !== ledger.captureContent("server-1", "thread-channel-1"))
        scope.cancel()
    }

    @Test
    fun staleCanonicalReconcileCannotReverseRealtimeFreshnessHighWater() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ledger = SharedThreadFreshnessLedger()
        val original = threadItem("thread-channel-1").copy(replyCount = 2)
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots = listOf(snapshot(listOf(original)), snapshot(listOf(original)))
                    ),
                replyThreadFreshnessLedger = ledger
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("parent-message"),
                    threadChannelId = ChannelId("thread-channel-1"),
                    replyCount = 3,
                    lastReplyAt = "2026-07-13T03:05:00Z",
                    participantIds = emptyList()
                )
            )
        )
        val realtimeGeneration = ledger.captureContent("server-1", "thread-channel-1")

        store.dispatch(SharedActivityIntent.Refresh)

        assertEquals(3, store.state.value.items.single().replyCount)
        assertTrue(realtimeGeneration === ledger.captureContent("server-1", "thread-channel-1"))
        scope.cancel()
    }

    @Test
    fun syncResumeAndGapRefreshInvalidateThreadFreshnessCandidates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ledger = SharedThreadFreshnessLedger()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = FakeInboxRepository(listOf(snapshot(listOf(threadItem("thread-channel-1"))))),
                replyThreadFreshnessLedger = ledger
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        val initial = ledger.captureContent("server-1", "thread-channel-1")

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                SyncResumeResponse(messages = emptyList(), currentSeq = 10L, hasMore = false)
            )
        )
        val afterResume = ledger.captureContent("server-1", "thread-channel-1")
        assertTrue(initial !== afterResume)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                RealtimeRefreshRequired(
                    scope = RealtimeRefreshScope.Home,
                    reason = "seq_gap",
                    serverId = ServerId("server-1")
                )
            )
        )
        assertTrue(afterResume !== ledger.captureContent("server-1", "thread-channel-1"))
        scope.cancel()
    }

    @Test
    fun unknownChannelDmAndThreadRowsEnterThroughBackgroundReconcileWithoutRefreshChrome() = runBlocking {
        val existing = activityItem("channel-1")
        val direct =
            activityItem("dm-2").copy(
                key = "dm:dm-2",
                kind = InboxItemKind.DirectMessage,
                channelKind = ConversationKind.DirectMessage
            )
        val cases =
            listOf(
                Triple("channel", activityItem("channel-2"), message("message-channel", channelId = "channel-2")),
                Triple("dm", direct, message("message-dm", channelId = "dm-2")),
                Triple(
                    "thread",
                    threadItem("thread-2"),
                    message(
                        "message-thread",
                        channelId = "thread-2",
                        conversationContext = MessageConversationContext(channelKind = ConversationKind.Thread)
                    )
                )
            )

        cases.forEach { (label, arrived, realtimeMessage) ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val inbox =
                FakeInboxRepository(
                    initialSnapshots =
                        listOf(
                            snapshot(listOf(existing)),
                            snapshot(listOf(arrived, existing))
                        )
                )
            val store =
                activityStore(
                    scope = scope,
                    inboxRepository = inbox,
                    realtimeBackgroundRefreshIntervalMillis = 0L
                )

            store.dispatch(SharedActivityIntent.LoadInitial)
            store.acceptRealtime(
                SharedActivityIntent.ApplyRealtimeEvent(MessageNew(realtimeMessage))
            )

            assertEquals(2, inbox.initialLoadCount, label)
            assertEquals(listOf(arrived.key, existing.key), store.state.value.items.map { it.key }, label)
            assertEquals(false, store.state.value.refreshing, label)
            assertEquals(false, store.state.value.pullRefreshing, label)
            scope.cancel()
        }
    }

    @Test
    fun realtimeBackgroundReconcileKeepsRefreshChromeOffWhileRequestIsInFlight() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )
        val stale = threadItem("thread-channel-1", unreadCount = 0).copy(replyCount = 2)
        val canonical =
            stale.copy(
                latestActivityMessageId = MessageId("message-new"),
                latestActivityPreview = "Realtime preview",
                replyCount = 3,
                unreadCount = 1,
                firstUnreadMessageId = MessageId("message-new")
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 0))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "message-new",
                        channelId = "thread-channel-1",
                        body = "Realtime preview",
                        conversationContext = MessageConversationContext(channelKind = ConversationKind.Thread)
                    )
                )
            )
        )

        awaitPendingCount(inbox, 1)
        assertEquals(false, store.state.value.loading)
        assertEquals(false, store.state.value.refreshing)
        assertEquals(false, store.state.value.pullRefreshing)
        assertEquals(MessageId("message-new"), store.state.value.items.single().latestActivityMessageId)
        assertEquals(0, store.state.value.items.single().unreadCount, "unread arrives with the canonical row")
        assertEquals(0, store.state.value.totalUnreadCount)

        inbox.completeNextSnapshot(snapshot(listOf(canonical), totalUnreadCount = 1))
        awaitState(store) { state -> state.items.singleOrNull()?.latestActivityMessageId == MessageId("message-new") }
        assertEquals(false, store.state.value.refreshing)
        assertEquals(1, store.state.value.items.single().unreadCount)
        scope.cancel()
    }

    @Test
    fun realtimeBackgroundReconcileFailureDoesNotPolluteVisibleErrorOrChrome() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )
        val stale = threadItem("thread-channel-1").copy(replyCount = 2)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale)))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-new", channelId = "thread-channel-1"))
            )
        )

        awaitPendingCount(inbox, 1)
        inbox.completeNextResult(DataResult.Error(ScreenError("background_failed")))
        delay(1L)

        assertEquals(null, store.state.value.errorCode)
        assertEquals(false, store.state.value.loading)
        assertEquals(false, store.state.value.refreshing)
        assertEquals(false, store.state.value.pullRefreshing)
        assertEquals(MessageId("message-new"), store.state.value.items.single().latestActivityMessageId)
        scope.cancel()
    }

    @Test
    fun unreadFilterWaitsForCanonicalMembershipBeforeReorderingOrRemovingRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )
        val head = threadItem("thread-head", unreadCount = 1).copy(replyCount = 1)
        val target = threadItem("thread-target", unreadCount = 1).copy(replyCount = 2)

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        inbox.completeNextSnapshot(snapshot(listOf(head, target), totalUnreadCount = 2))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-new", channelId = "thread-target"))
            )
        )

        awaitPendingCount(inbox, 1)
        assertEquals(listOf(head.key, target.key), store.state.value.items.map { it.key })
        assertEquals(target.latestActivityMessageId, store.state.value.items[1].latestActivityMessageId)

        inbox.completeNextSnapshot(snapshot(listOf(head), totalUnreadCount = 1))
        awaitState(store) { state -> state.items.map { it.key } == listOf(head.key) }
        assertEquals(false, store.state.value.refreshing)
        scope.cancel()
    }

    @Test
    fun mentionsFilterWaitsForCanonicalMembershipWithoutApplyingMessageGuess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )
        val target = threadItem("thread-target", unreadCount = 1).copy(replyCount = 2, hasMention = true)

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Mentions))
        inbox.completeNextSnapshot(snapshot(listOf(target), totalUnreadCount = 1))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-new", channelId = "thread-target"))
            )
        )

        awaitPendingCount(inbox, 1)
        assertEquals(target, store.state.value.items.single())

        inbox.completeNextSnapshot(snapshot(emptyList(), totalUnreadCount = 0))
        awaitState(store) { state -> state.items.isEmpty() }
        assertEquals(false, store.state.value.refreshing)
        scope.cancel()
    }

    @Test
    fun selfAuthoredMessagePatchKeepsAllRowRead() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 60_000L
            )
        val stale = threadItem("thread-channel-1", unreadCount = 0).copy(replyCount = 2)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 0))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "self-message",
                        channelId = "thread-channel-1",
                        authorId = "user-1"
                    )
                )
            )
        )

        val patched = store.state.value.items.single()
        assertEquals(MessageId("self-message"), patched.latestActivityMessageId)
        assertEquals(0, patched.unreadCount)
        assertEquals(null, patched.firstUnreadMessageId)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun staleBackgroundProjectionCannotDropPatchedAllRowBeforeCanonicalMarkerCatchesUp() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )
        val stale = threadItem("thread-channel-1").copy(replyCount = 2)
        val canonical =
            stale.copy(
                latestActivityMessageId = MessageId("message-new"),
                latestActivityPreview = "message message-new",
                replyCount = 3,
                unreadCount = 1,
                firstUnreadMessageId = MessageId("message-new")
            )
        val laggingReadProjection = canonical.copy(unreadCount = 0, firstUnreadMessageId = null)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale)))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-new", channelId = "thread-channel-1"))
            )
        )

        awaitPendingCount(inbox, 1)
        // The aggregate count may already include the realtime message even
        // while the serving page still omits its row. Preserve the visible
        // floor without adding the row unread a second time.
        inbox.completeNextSnapshot(snapshot(emptyList(), totalUnreadCount = 1))
        delay(1L)
        assertEquals(listOf(stale.key), store.state.value.items.map { it.key })
        assertEquals(0, store.state.value.items.single().unreadCount, "patches never invent unread")
        assertEquals(1, store.state.value.totalUnreadCount)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                SyncResumeResponse(messages = emptyList(), currentSeq = 10L, hasMore = false)
            )
        )
        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(snapshot(listOf(laggingReadProjection), totalUnreadCount = 0))
        delay(1L)
        assertEquals(0, store.state.value.items.single().unreadCount, "canonical reached the patch marker")
        assertEquals(0, store.state.value.totalUnreadCount)

        store.dispatch(SharedActivityIntent.Refresh)
        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(snapshot(listOf(laggingReadProjection), totalUnreadCount = 0))
        awaitState(store) { state -> state.items.singleOrNull()?.unreadCount == 0 }

        store.dispatch(SharedActivityIntent.Refresh)
        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(snapshot(emptyList()))
        awaitState(store) { state -> state.items.isEmpty() }
        scope.cancel()
    }

    @Test
    fun explicitRefreshMayRemoveRowStillHeldOnlyByBackgroundHighWater() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 60_000L
            )
        val stale = threadItem("thread-channel-1").copy(replyCount = 2)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale)))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-new", channelId = "thread-channel-1"))
            )
        )

        store.dispatch(SharedActivityIntent.Refresh)
        inbox.completeNextSnapshot(snapshot(emptyList()))

        assertTrue(store.state.value.items.isEmpty())
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun backgroundEmptyResultCannotDropRowStillHeldByRealtimeHighWater() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )
        val stale = threadItem("thread-channel-1").copy(replyCount = 2)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale)))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-new", channelId = "thread-channel-1"))
            )
        )

        awaitPendingCount(inbox, 1)
        inbox.completeNextResult(DataResult.Empty(EmptyReason.NoMessages))
        delay(1L)

        assertEquals(listOf(stale.key), store.state.value.items.map { it.key })
        assertEquals(MessageId("message-new"), store.state.value.items.single().latestActivityMessageId)
        assertEquals(0, store.state.value.totalUnreadCount, "no local unread inflation")
        scope.cancel()
    }

    @Test
    fun twoRetainedRowsDoNotDoubleCountCanonicalAggregateThatAlreadyCaughtUp() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )
        val first = threadItem("thread-first").copy(replyCount = 2)
        val second = threadItem("thread-second").copy(replyCount = 4)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(first, second), totalUnreadCount = 0))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-first", channelId = "thread-first"))
            )
        )
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-second", channelId = "thread-second"))
            )
        )

        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(snapshot(emptyList(), totalUnreadCount = 2))
        delay(1L)

        assertEquals(2, store.state.value.items.size)
        assertEquals(listOf(0, 0), store.state.value.items.map { it.unreadCount })
        assertEquals(2, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun authoritativeThreadUnreadClearsBackgroundPreservedHighWaterWithoutExplicitRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )
        val stale = threadItem("thread-channel-1").copy(replyCount = 2)
        val sameMarkerRead =
            stale.copy(
                latestActivityMessageId = MessageId("message-new"),
                latestActivityPreview = "message message-new",
                replyCount = 3,
                unreadCount = 0,
                firstUnreadMessageId = null
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 0))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-new", channelId = "thread-channel-1"))
            )
        )

        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(snapshot(listOf(sameMarkerRead), totalUnreadCount = 0))
        delay(1L)
        assertEquals(0, store.state.value.items.single().unreadCount)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("parent-message"),
                    threadChannelId = ChannelId("thread-channel-1"),
                    replyCount = 3,
                    lastReplyAt = sameMarkerRead.latestActivityAt,
                    participantIds = emptyList(),
                    unreadCount = 0,
                    firstUnreadMessageId = null
                )
            )
        )

        assertEquals(0, store.state.value.items.single().unreadCount)
        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(snapshot(listOf(sameMarkerRead), totalUnreadCount = 0))
        delay(1L)
        assertEquals(0, store.state.value.items.single().unreadCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun filterSwitchFencesOlderAllBackgroundProjectionAndStartsCleanMembershipLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )
        val stale = threadItem("thread-channel-1").copy(replyCount = 2)
        val patchedCanonical =
            stale.copy(
                latestActivityMessageId = MessageId("message-new"),
                latestActivityPreview = "message message-new",
                replyCount = 3,
                unreadCount = 1,
                firstUnreadMessageId = MessageId("message-new")
            )
        val unreadOnly = threadItem("thread-unread", unreadCount = 1)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 0))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-new", channelId = "thread-channel-1"))
            )
        )
        awaitPendingCount(inbox, 1)

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        awaitPendingCount(inbox, 2)
        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter)
        assertTrue(store.state.value.items.isEmpty())

        inbox.completeNextSnapshot(snapshot(listOf(patchedCanonical), totalUnreadCount = 1))
        delay(1L)
        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter)
        assertTrue(store.state.value.items.isEmpty())

        inbox.completeNextSnapshot(snapshot(listOf(unreadOnly), totalUnreadCount = 1))
        awaitState(store) { state -> state.items.map { it.key } == listOf(unreadOnly.key) }
        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter)
        scope.cancel()
    }

    @Test
    fun messagePatchSurvivesStaleExplicitRefreshUntilBackgroundReconcileConfirmsIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )
        val stale = threadItem("thread-channel-1", unreadCount = 1).copy(replyCount = 2)
        val canonical =
            stale.copy(
                latestActivityMessageId = MessageId("message-new"),
                latestActivityPreview = "Realtime preview",
                latestActivityAt = "2026-07-13T03:03:00Z",
                replyCount = 3,
                unreadCount = 2
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
        assertEquals(1, store.state.value.items.single().unreadCount)

        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 1))

        assertEquals(MessageId("message-new"), store.state.value.items.single().latestActivityMessageId)
        assertEquals(3, store.state.value.items.single().replyCount)
        assertEquals(1, store.state.value.items.single().unreadCount)
        assertEquals(1, store.state.value.totalUnreadCount)
        assertEquals(false, store.state.value.refreshing)

        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(snapshot(listOf(canonical), totalUnreadCount = 2))
        awaitState(store) { state -> !state.refreshing && state.items.singleOrNull()?.replyCount == 3 }

        assertEquals(0, inbox.pendingCount)
        assertEquals(MessageId("message-new"), store.state.value.items.single().latestActivityMessageId)
        scope.cancel()
    }

    @Test
    fun realtimeHighWaterPreservesLocallyClearedUnreadState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox
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
    fun readStateEventWithoutARowFrontierTakesOneCanonicalReconcile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stale = threadItem("thread-channel-1", unreadCount = 2).copy(replyCount = 2)
        val canonical =
            stale.copy(
                latestActivityMessageId = MessageId("message-new"),
                latestActivityPreview = "Realtime preview",
                replyCount = 3,
                unreadCount = 3
            )
        val inbox =
            FakeInboxRepository(
                listOf(
                    snapshot(listOf(stale), totalUnreadCount = 2),
                    snapshot(listOf(canonical), totalUnreadCount = 3)
                )
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.acceptRealtime(
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
        val loadCountAfterRealtime = inbox.initialLoadCount

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                readStateEvent(scopeId = "thread-channel-1", version = 1, maxReadSeq = 10)
            )
        )
        assertEquals(2, loadCountAfterRealtime)
        assertEquals(loadCountAfterRealtime + 1, inbox.initialLoadCount, "no row frontier: one reconcile")
        val patched = store.state.value.items.single()
        assertEquals(MessageId("message-new"), patched.latestActivityMessageId)
        assertEquals("Realtime preview", patched.latestActivityPreview)
        assertEquals(3, patched.replyCount)
        assertEquals(3, patched.unreadCount)
        assertEquals(3, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun messageThreadAndResumeBurstPatchImmediatelyAndCoalesceOneBackgroundReconcile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stale = threadItem("thread-channel-1").copy(replyCount = 2)
        val canonical =
            stale.copy(
                latestActivityMessageId = MessageId("message-1"),
                latestActivityPreview = "message message-1",
                replyCount = 4,
                latestActivityAt = "2026-07-13T03:04:00Z",
                unreadCount = 1,
                firstUnreadMessageId = MessageId("message-1")
            )
        val inbox =
            FakeInboxRepository(
                listOf(
                    snapshot(listOf(stale), totalUnreadCount = 0),
                    snapshot(listOf(canonical), totalUnreadCount = 1)
                )
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 75L,
                // Legacy real-dispatcher case: the 75ms throttle window is waited
                // out with a real delay below, so it needs the real clock.
                nowMillis = activityMonotonicClock()
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-1", channelId = "thread-channel-1"))
            )
        )
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("parent-message"),
                    threadChannelId = ChannelId("thread-channel-1"),
                    replyCount = 4,
                    lastReplyAt = "2026-07-13T03:04:00Z",
                    participantIds = emptyList(),
                    unreadCount = 1,
                    firstUnreadMessageId = MessageId("message-1")
                )
            )
        )
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                SyncResumeResponse(messages = emptyList(), currentSeq = 10L, hasMore = false)
            )
        )

        assertEquals(4, store.state.value.items.single().replyCount)
        assertEquals(MessageId("message-1"), store.state.value.items.single().latestActivityMessageId)
        assertEquals(0, store.state.value.items.single().unreadCount)
        assertEquals(false, store.state.value.refreshing)
        delay(150L)
        assertEquals(2, inbox.initialLoadCount)
        assertEquals(false, store.state.value.refreshing)
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
        assertEquals(1, reconciled.unreadCount, "unread stays the server's")
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
    fun loadInitialAppliesResultWhenAccessTokenRotatedMidFlight() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val authStore = InMemoryRaftAuthSessionStore(sampleSession())
        val inbox = DeferredInboxRepository()
        val diagnostics = mutableListOf<String>()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                authStore = authStore,
                diagnosticsLogger = { diagnostics += it }
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(true, store.state.value.loading)
        assertEquals(1, inbox.initialLoadCount)

        // A 401 inside the request rotates the tokens; the retried request still
        // returns fresh data for the same user + server and must be applied.
        authStore.updateTokens("rotated-access", "rotated-refresh")
        inbox.completeNext(activityItem("channel-1"))

        assertEquals(false, store.state.value.loading)
        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key })
        assertTrue(diagnostics.none { it.contains("stale_session") })
        scope.cancel()
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession(userId = "user-2")),
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
        tasksDataStore.upsertTask(
            serverId = "server-1",
            task =
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
        scope.cancel()
    }

    @Test
    fun activityThreadRowsReplaceStaleEmbeddedStatusWithSharedTaskProjection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tasksDataStore = SharedTasksDataStore()
        tasksDataStore.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("parent-channel")),
            snapshot =
                TaskSnapshot(
                    tasks =
                        listOf(
                            TaskSummary(
                                id = TaskId("task-339"),
                                title = "Canonical task",
                                status = TaskStatus.Done,
                                taskNumber = 339,
                                messageId = MessageId("parent-message"),
                                channelId = ChannelId("parent-channel"),
                                updatedAtLabel = "now"
                            )
                        ),
                    summaryCounts = emptyMap()
                )
        )
        val staleRow =
            threadItem("thread-channel-1").copy(
                taskNumber = 339,
                taskStatus = TaskStatus.InProgress,
                taskClaimedByName = "stale assignee"
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(staleRow)))),
                tasksDataStore = tasksDataStore
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        val item = store.state.value.items.single()
        assertEquals(339, item.taskNumber)
        assertEquals(TaskStatus.Done, item.taskStatus)
        assertEquals(null, item.taskClaimedByName)
        scope.cancel()
    }

    @Test
    fun activityTaskProjectionBindsOnlyPageRowsWithExplicitOverrideOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tasksDataStore = SharedTasksDataStore()
        fun task(id: String, number: Int, messageId: String?, parentMessageId: String? = null, status: TaskStatus = TaskStatus.Todo) =
            TaskSummary(
                id = TaskId(id),
                title = "Task $number",
                status = status,
                taskNumber = number,
                messageId = messageId?.let(::MessageId),
                parentMessageId = parentMessageId?.let(::MessageId),
                channelId = ChannelId("parent-channel"),
                updatedAtLabel = "now"
            )
        // A large Server task list where only a handful of tasks touch this page.
        val unrelated = (1..3000).map { task("task-unrelated-$it", 10_000 + it, "unrelated-message-$it") }
        val tasks =
            unrelated.take(1500) +
                // Two tasks share one parent message: the later one in Server order wins.
                task("task-shared-first", 11, "shared-parent", status = TaskStatus.Todo) +
                task("task-shared-second", 12, "shared-parent", status = TaskStatus.InReview) +
                // Bound through parentMessageId rather than messageId.
                task("task-via-parent", 21, "own-message", parentMessageId = "parent-link", status = TaskStatus.Done) +
                unrelated.drop(1500)
        tasksDataStore.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot = TaskSnapshot(tasks = tasks, summaryCounts = emptyMap())
        )
        val sharedRow = threadItem("thread-shared").copy(parentMessageId = MessageId("shared-parent"))
        val parentLinkRow = threadItem("thread-parent-link").copy(parentMessageId = MessageId("parent-link"))
        val unboundRow = threadItem("thread-unbound").copy(parentMessageId = MessageId("no-task-here"), taskNumber = 99)
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots = listOf(snapshot(listOf(sharedRow, parentLinkRow, unboundRow)))
                    ),
                tasksDataStore = tasksDataStore
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        val items = store.state.value.items.associateBy { it.key }
        assertEquals(12, items.getValue("thread:thread-shared").taskNumber)
        assertEquals(TaskStatus.InReview, items.getValue("thread:thread-shared").taskStatus)
        assertEquals(21, items.getValue("thread:thread-parent-link").taskNumber)
        assertEquals(TaskStatus.Done, items.getValue("thread:thread-parent-link").taskStatus)
        assertEquals(99, items.getValue("thread:thread-unbound").taskNumber)
        assertEquals(null, items.getValue("thread:thread-unbound").taskStatus)
        scope.cancel()
    }

    @Test
    fun activityTaskProjectionLeavesRowsUntouchedForConflictedTaskFacts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tasksDataStore = SharedTasksDataStore()
        val fact =
            TaskSummary(
                id = TaskId("task-conflict"),
                title = "Conflicted task",
                status = TaskStatus.Todo,
                taskNumber = 55,
                messageId = MessageId("parent-message"),
                channelId = ChannelId("parent-channel"),
                revision = 3L,
                updatedAtLabel = "now"
            )
        tasksDataStore.upsertTask("server-1", fact)
        tasksDataStore.upsertTask("server-2", fact.copy(status = TaskStatus.Done, title = "Disagreeing payload"))
        val row = threadItem("thread-conflict").copy(taskNumber = 55, taskStatus = TaskStatus.InProgress)
        val store =
            activityStore(
                scope = scope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(row)))),
                tasksDataStore = tasksDataStore
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        val item = store.state.value.items.single()
        assertEquals(55, item.taskNumber)
        assertEquals(TaskStatus.InProgress, item.taskStatus)
        scope.cancel()
    }

    @Test
    fun jointTaskActivityFactDedupesAndOpensThroughEachServerLocalRoute() = runBlocking {
        val tasksDataStore = SharedTasksDataStore()
        val canonicalTask =
            TaskSummary(
                id = TaskId("task-joint"),
                title = "Joint task",
                status = TaskStatus.InProgress,
                taskNumber = 441,
                messageId = MessageId("joint-parent-message"),
                channelId = ChannelId("host-channel"),
                revision = 7L,
                updatedAtLabel = "now"
            )
        tasksDataStore.upsertTask("server-host", canonicalTask)
        tasksDataStore.upsertTask(
            "server-peer",
            canonicalTask.copy(channelId = ChannelId("peer-channel"), canClaim = false)
        )
        val hostCandidate =
            threadItem("thread-host")
                .copy(
                    parentChannelId = ChannelId("host-channel"),
                    parentMessageId = canonicalTask.messageId
                )
        val peerCandidate =
            threadItem("thread-peer")
                .copy(
                    parentChannelId = ChannelId("peer-channel"),
                    parentMessageId = canonicalTask.messageId
                )

        suspend fun load(serverId: String): InboxItem {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val store =
                activityStore(
                    scope = scope,
                    authStore = InMemoryRaftAuthSessionStore(sampleSession(serverId = serverId)),
                    inboxRepository =
                        FakeInboxRepository(
                            initialSnapshots =
                                listOf(
                                    snapshot(
                                        items = listOf(peerCandidate, hostCandidate),
                                        serverId = serverId
                                    )
                                )
                        ),
                    tasksDataStore = tasksDataStore
                )
            store.dispatch(SharedActivityIntent.LoadInitial)
            return store.state.value.items.single().also { scope.cancel() }
        }

        val hostRow = load("server-host")
        val peerRow = load("server-peer")

        assertEquals("thread:thread-host", hostRow.key)
        assertEquals(ChannelId("host-channel"), hostRow.parentChannelId)
        assertEquals("thread:thread-peer", peerRow.key)
        assertEquals(ChannelId("peer-channel"), peerRow.parentChannelId)
        assertEquals(441, hostRow.taskNumber)
        assertEquals(441, peerRow.taskNumber)
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession(serverId = "server-2")),
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
        val processStore = InMemoryRaftAuthSessionStore(processSession)
        val authA =
            RaftShellAuthSessionStore(
                processStore,
                SelectedServerStore(RaftShellStoreScope.create("shell-a", "server-1"))
            )
        val authB =
            RaftShellAuthSessionStore(
                processStore,
                SelectedServerStore(RaftShellStoreScope.create("shell-b", "server-2"))
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

    @Test
    fun canonicalLoadAndOptimisticMutationEmitTypedProcessSummaryFacts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val facts = mutableListOf<ActivityUnreadSummaryFact>()
        val item = activityItem("channel-1", unreadCount = 2)
        val store =
            activityStore(
                scope = scope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(item)))),
                onSummaryFact = facts::add
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        val canonical = facts.last()
        assertEquals("user-1", canonical.accountId)
        assertEquals("server-1", canonical.serverId)
        assertEquals(2, canonical.totalUnreadCount)
        assertTrue(canonical.authoritative)

        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        val optimistic = facts.last()
        assertEquals(0, optimistic.totalUnreadCount)
        assertFalse(optimistic.authoritative)
        assertEquals("activity:mark_read", optimistic.source)
        scope.cancel()
    }

    @Test
    fun internalJointStopLossKeepsCanonicalRowsButRemovesSummaryCountGhosts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val facts = mutableListOf<ActivityUnreadSummaryFact>()
        val safe = activityItem("channel-safe", unreadCount = 2)
        val unsafe =
            InboxItem(
                key = "thread:reply-stream",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("reply-stream"),
                channelName = "joint-storage-parent",
                threadChannelId = ChannelId("reply-stream"),
                parentChannelId = ChannelId("joint-storage-parent"),
                parentChannelName = "joint-storage-parent",
                parentChannelKind = ConversationKind.Joint,
                parentMessageId = MessageId("parent-message"),
                unreadCount = 3,
                latestActivityMessageId = MessageId("reply-1")
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots =
                            listOf(
                                snapshot(
                                    items = listOf(safe, unsafe),
                                    totalCount = 5,
                                    totalUnreadCount = 10
                                )
                            )
                    ),
                onSummaryFact = facts::add
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(listOf("channel:channel-safe", "thread:reply-stream"), store.state.value.items.map(InboxItem::key))
        assertEquals(5, store.state.value.totalCount)
        assertEquals(10, store.state.value.totalUnreadCount)
        val projectedFact = facts.last()
        assertEquals(4, projectedFact.totalCount)
        assertEquals(7, projectedFact.totalUnreadCount)
        assertTrue(projectedFact.authoritative)
        scope.cancel()
    }

    @Test
    fun nonAllFilterNeverPublishesAbsoluteProcessCountsAndMutationsOnlyInvalidate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val facts = mutableListOf<ActivityUnreadSummaryFact>()
        val invalidations = mutableListOf<ActivityUnreadSummaryInvalidation>()
        val allItem = activityItem("channel-all", unreadCount = 9)
        val mentionItem = activityItem("channel-mention", unreadCount = 2)
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots =
                            listOf(
                                snapshot(listOf(allItem), totalUnreadCount = 9),
                                snapshot(listOf(mentionItem), totalUnreadCount = 2)
                            )
                    ),
                onSummaryFact = facts::add,
                onSummaryInvalidated = invalidations::add
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        assertEquals(9, facts.single().totalUnreadCount)

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Mentions))
        assertEquals(9, facts.single().totalUnreadCount)
        assertTrue(invalidations.isEmpty())

        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        assertEquals(9, facts.single().totalUnreadCount)
        assertEquals("activity:mark_read", invalidations.single().source)
        scope.cancel()
    }

    @Test
    fun pullRefreshRecordsStartAndTerminalDiagnosticsCarryingPullRefreshInput() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lines = mutableListOf<String>()
        val store = activityStore(scope = scope, diagnosticsLogger = { message -> lines.add(message) })

        store.dispatch(SharedActivityIntent.LoadInitial)
        lines.clear()
        store.dispatch(SharedActivityIntent.PullRefresh)

        // task #436: without a start line, "no terminal line" cannot be told apart
        // from "the load never began", so absence is unreadable as evidence.
        val startLines = lines.filter { it.contains("phase=start") }
        assertEquals(1, startLines.size, "pull refresh must record exactly one start line; actual=$lines")
        assertTrue(
            startLines[0].contains("pullRefresh=true"),
            "start line must carry the pull-refresh input; actual=${startLines[0]}"
        )

        val terminalLines = lines.filter { it.contains("result=") }
        assertEquals(1, terminalLines.size, "pull refresh must record exactly one terminal line; actual=$lines")
        assertTrue(
            terminalLines[0].contains("pullRefresh=true"),
            "terminal line must carry the pull-refresh input; actual=${terminalLines[0]}"
        )
        assertTrue(
            terminalLines[0].contains("pullRefreshingAfter=false"),
            "a terminated pull refresh must publish pullRefreshing=false; actual=${terminalLines[0]}"
        )
        scope.cancel()
    }

    @Test
    fun postStartExitViaMissingServerStillRecordsATerminalLine() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lines = mutableListOf<String>()
        // currentServerId resolves to a server absent from the session's list, which
        // reaches the `server == null` exit.
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user-1@slock.local", displayName = "User One"),
                accessToken = "access",
                refreshToken = "refresh",
                selectedServerId = "server-absent",
                servers = listOf(StoredAuthServer(id = "server-1", name = "Slock"))
            )
        val store =
            activityStore(
                scope = scope,
                authStore = InMemoryRaftAuthSessionStore(session),
                diagnosticsLogger = { message -> lines.add(message) }
            )

        store.dispatch(SharedActivityIntent.PullRefresh)

        // task #436: this exit used to return silently. After a start line, silence is
        // the stuck signature, so an unlogged exit makes the diagnostic manufacture the
        // false positive it exists to rule out.
        val startLines = lines.filter { it.contains("phase=start") }
        assertEquals(1, startLines.size, "expected exactly one start line; actual=$lines")
        val terminalLines = lines.filter { it.contains("result=") }
        assertEquals(1, terminalLines.size, "a post-start exit must record a terminal line; actual=$lines")
        assertTrue(
            terminalLines[0].contains("code=server_missing"),
            "terminal line must identify the exit; actual=${terminalLines[0]}"
        )
        assertTrue(
            terminalLines[0].contains("pullRefresh=true") && terminalLines[0].contains("generation="),
            "terminal line must carry generation and the pull-refresh input; actual=${terminalLines[0]}"
        )
        scope.cancel()
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

    @Test
    fun replyPrewarmQueuedDuringForegroundOpenStartsOnlyAfterTheOpenReleases() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = SharedForegroundLoadGate()
        val calls = mutableListOf<ActivityReplyThreadPrewarmKey>()
        val logs = mutableListOf<String>()
        val store =
            activityStore(
                scope = scope,
                foregroundLoadGate = gate,
                prewarmReplyThread = { marker ->
                    synchronized(testLogsLock) { calls += marker.key }
                    DataResult.Content(Unit)
                },
                diagnosticsLogger = { line -> synchronized(testLogsLock) { logs += line } }
            )
        val open = gate.acquire(SharedForegroundThreadKey(serverId = "server-1", threadId = "thread-opened"))
        val marker = keyedMarker("queued")

        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))
        delay(50L)

        assertEquals(emptyList(), synchronized(testLogsLock) { calls.toList() }, "prewarm must not start during a foreground open")
        assertTrue(
            synchronized(testLogsLock) { logs.any { it.startsWith("activity_reply_prewarm_deferred reason=foreground_open queued=1 ") } },
            synchronized(testLogsLock) { logs.toString() }
        )

        assertTrue(open.release())
        withTimeout(5_000L) { while (synchronized(testLogsLock) { calls.isEmpty() }) delay(5L) }
        assertEquals(listOf(marker.key), synchronized(testLogsLock) { calls.toList() })
        assertTrue(
            synchronized(testLogsLock) { logs.any { it.startsWith("activity_reply_prewarm_resumed reason=released ") } },
            synchronized(testLogsLock) { logs.toString() }
        )
        scope.cancel()
    }

    @Test
    fun replyPrewarmWithoutForegroundOpenStartsImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<ActivityReplyThreadPrewarmKey>()
        val logs = mutableListOf<String>()
        val store =
            activityStore(
                scope = scope,
                foregroundLoadGate = SharedForegroundLoadGate(),
                prewarmReplyThread = { marker ->
                    calls += marker.key
                    DataResult.Content(Unit)
                },
                diagnosticsLogger = { line -> logs += line }
            )
        val marker = keyedMarker("idle")

        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))

        assertEquals(listOf(marker.key), calls)
        assertTrue(logs.none { it.startsWith("activity_reply_prewarm_deferred") })
        scope.cancel()
    }

    @Test
    fun replyPrewarmResumesWhenAForegroundLeaseIsNeverReleased() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = SharedForegroundLoadGate(leaseTimeoutMillis = 100L)
        val calls = mutableListOf<ActivityReplyThreadPrewarmKey>()
        val logs = mutableListOf<String>()
        val store =
            activityStore(
                scope = scope,
                foregroundLoadGate = gate,
                prewarmReplyThread = { marker ->
                    synchronized(testLogsLock) { calls += marker.key }
                    DataResult.Content(Unit)
                },
                diagnosticsLogger = { line -> synchronized(testLogsLock) { logs += line } }
            )
        // A lost release: the lease is never released by its owner.
        gate.acquire(SharedForegroundThreadKey(serverId = "server-1", threadId = "thread-leaked"))
        val marker = keyedMarker("after-leak")

        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(marker)))
        assertEquals(emptyList(), synchronized(testLogsLock) { calls.toList() })

        withTimeout(5_000L) { while (synchronized(testLogsLock) { calls.isEmpty() }) delay(5L) }
        assertEquals(listOf(marker.key), synchronized(testLogsLock) { calls.toList() })
        assertEquals(0, gate.activeCount())
        assertTrue(
            synchronized(testLogsLock) { logs.any { it.startsWith("activity_reply_prewarm_resumed reason=timeout ") } },
            synchronized(testLogsLock) { logs.toString() }
        )
        scope.cancel()
    }

    @Test
    fun queuedReplyPrewarmIsDroppedWhenTheUserOpenedThatThreadAndItsContentIsCached() = runBlocking {
        val calls = queuedPrewarmAfterTappedOpen(tappedContentCached = true)
        // Negative twin in the same queue: the untapped row still prewarms.
        assertEquals(listOf(keyedMarker("other").key), calls.prewarmed)
        assertTrue(
            calls.logs.any {
                it.startsWith("activity_reply_prewarm_result result=skipped reason=foreground_open_owns_load ") &&
                    it.contains("threadChannelId=${keyedMarker("tapped").key.threadChannelId}")
            },
            calls.logs.toString()
        )
        // Same generation is settled: a later viewport pass does not re-issue it.
        assertEquals(listOf(keyedMarker("other").key), calls.prewarmedAfterSecondPass)
    }

    @Test
    fun queuedReplyPrewarmStillRunsWhenTheTappedOpenLeftNoCachedContent() = runBlocking {
        // Twin: identical queue, identical tap; the open failed / closed before loading.
        val calls = queuedPrewarmAfterTappedOpen(tappedContentCached = false)
        assertEquals(
            setOf(keyedMarker("tapped").key, keyedMarker("other").key),
            calls.prewarmed.toSet()
        )
        assertTrue(calls.logs.none { it.contains("result=skipped") }, calls.logs.toString())
    }

    private class QueuedPrewarmRun(
        val prewarmed: List<ActivityReplyThreadPrewarmKey>,
        val prewarmedAfterSecondPass: List<ActivityReplyThreadPrewarmKey>,
        val logs: List<String>,
    )

    private suspend fun queuedPrewarmAfterTappedOpen(tappedContentCached: Boolean): QueuedPrewarmRun {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = 1_000L
        val gate = SharedForegroundLoadGate(nowMillis = { now })
        val calls = mutableListOf<ActivityReplyThreadPrewarmKey>()
        val logs = mutableListOf<String>()
        val tapped = keyedMarker("tapped")
        val other = keyedMarker("other")
        val store =
            activityStore(
                scope = scope,
                foregroundLoadGate = gate,
                replyThreadContentCached = { marker -> tappedContentCached && marker.key == tapped.key },
                prewarmReplyThread = { marker ->
                    synchronized(testLogsLock) { calls += marker.key }
                    DataResult.Content(Unit)
                },
                diagnosticsLogger = { line -> synchronized(testLogsLock) { logs += line } }
            )
        val earlierOpen = gate.acquire(SharedForegroundThreadKey(serverId = "server-1", threadId = "thread-earlier"))
        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(tapped, other)))
        assertEquals(emptyList(), synchronized(testLogsLock) { calls.toList() })

        // The user taps the queued row after it was queued.
        now = 2_000L
        val tappedOpen =
            gate.acquire(
                SharedForegroundThreadKey(
                    serverId = "server-1",
                    threadId = tapped.key.threadChannelId,
                    parentChannelId = tapped.key.parentChannelId,
                    parentMessageId = tapped.key.parentMessageId,
                )
            )
        earlierOpen.release()
        tappedOpen.release()

        withTimeout(5_000L) { while (synchronized(testLogsLock) { calls.isEmpty() }) delay(5L) }
        delay(50L)
        val prewarmed = synchronized(testLogsLock) { calls.toList() }
        store.dispatch(SharedActivityIntent.PrewarmVisibleReplyThreads(setOf(tapped, other)))
        delay(20L)
        val afterSecondPass = synchronized(testLogsLock) { calls.toList() }
        scope.cancel()
        return QueuedPrewarmRun(prewarmed, afterSecondPass, synchronized(testLogsLock) { logs.toList() })
    }

    private fun replyPrewarmMarker(): ActivityReplyThreadPrewarmMarker =
        ActivityReplyThreadPrewarmMarker(
            key =
                ActivityReplyThreadPrewarmKey(
                    serverId = "server-1",
                    parentChannelId = "parent-channel",
                    parentMessageId = "parent-message",
                    threadChannelId = "thread-channel"
                )
        )

    private fun keyedMarker(id: String): ActivityReplyThreadPrewarmMarker =
        ActivityReplyThreadPrewarmMarker(
            key =
                ActivityReplyThreadPrewarmKey(
                    serverId = "server-1",
                    parentChannelId = "parent-$id",
                    parentMessageId = "message-$id",
                    threadChannelId = "thread-$id"
                )
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

        override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> =
            DataResult.Content(InboxDoneResult(channelId))

        fun completeNext(item: InboxItem) {
            completeNextSnapshot(snapshot(listOf(item)))
        }

        fun completeNextSnapshot(snapshot: InboxSnapshot) {
            completeNextResult(DataResult.Content(snapshot))
        }

        fun completeNextResult(result: DataResult<InboxSnapshot>) {
            val next = synchronized(lock) { pending.removeAt(0) }
            next.complete(result)
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

        override suspend fun deleteAgent(priority: DataRequestPriority, agentId: MemberId, workspaceChoice: AgentArchiveWorkspaceChoice): DataResult<AgentLifecycleActionResult> =
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

    @Test
    fun dmParentThreadRowsHydratePeerNamesFromDirectMessages() = runBlocking {
        // task #399: an inbox Thread row whose parent is a DM resolves the peer
        // display name through the Home DM list (channels.name is not a
        // reliable peer projection); channel-parent rows never trigger it.
        val dmThread =
            InboxItem(
                key = "thread:dm-thread-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("dm-thread-1"),
                channelName = "thread-stream",
                parentChannelId = ChannelId("dm-1"),
                parentChannelName = "raw-dm-name",
                parentChannelKind = ConversationKind.DirectMessage,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "DM root preview"
            )
        val channelThread =
            InboxItem(
                key = "thread:ch-thread-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("ch-thread-1"),
                channelName = "thread-stream-2",
                parentChannelId = ChannelId("channel-9"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-2"),
                parentMessagePreview = "Channel root preview"
            )
        val channelRepository =
            FakeChannelRepository(
                directMessagesResult = { serverId ->
                    DataResult.Content(
                        DirectMessageSnapshot(
                            server = ServerSummary(id = serverId, name = "Slock"),
                            directMessages =
                                listOf(
                                    HomeDirectMessageRow(
                                        threadId = ThreadId("dm-1"),
                                        channelId = ChannelId("dm-1"),
                                        name = "Jiacheng"
                                    )
                                )
                        )
                    )
                }
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots = listOf(snapshot(listOf(dmThread, channelThread)))
                    ),
                channelRepository = channelRepository
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        withTimeout(5_000L) {
            while (store.state.value.dmPeerNames["dm-1"] == null) {
                delay(1L)
            }
        }
        assertEquals(mapOf("dm-1" to "Jiacheng"), store.state.value.dmPeerNames)
        scope.cancel()
    }

    @Test
    fun dmPeerNameHydrationRetriesAfterFetchErrorAndParksTrueMisses() = runBlocking {
        // task #399 review (Mahua #1): a failed getDirectMessages must NOT
        // park the missing ids — the next load/patch trigger retries and
        // hydration recovers. Only a successful fetch that returns no row
        // parks the id, so later loads don't re-fetch for it.
        val dmThread =
            InboxItem(
                key = "thread:dm-thread-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("dm-thread-1"),
                channelName = "thread-stream",
                parentChannelId = ChannelId("dm-1"),
                parentChannelName = "raw-dm-name",
                parentChannelKind = ConversationKind.DirectMessage,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "DM root preview"
            )
        var failDirectMessages = true
        val channelRepository =
            FakeChannelRepository(
                directMessagesResult = { serverId ->
                    if (failDirectMessages) {
                        DataResult.Error(ScreenError("dm_list_failed"))
                    } else {
                        DataResult.Content(
                            DirectMessageSnapshot(
                                server = ServerSummary(id = serverId, name = "Slock"),
                                directMessages =
                                    listOf(
                                        HomeDirectMessageRow(
                                            threadId = ThreadId("dm-1"),
                                            channelId = ChannelId("dm-1"),
                                            name = "Jiacheng"
                                        )
                                    )
                            )
                        )
                    }
                }
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots = listOf(snapshot(listOf(dmThread)))
                    ),
                channelRepository = channelRepository
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        delay(50L)
        // Error → nothing parked, nothing resolved.
        assertEquals(1, channelRepository.directMessageCalls)
        assertEquals(emptyMap(), store.state.value.dmPeerNames)

        failDirectMessages = false
        store.dispatch(SharedActivityIntent.LoadInitial)
        withTimeout(5_000L) {
            while (store.state.value.dmPeerNames["dm-1"] == null) {
                delay(1L)
            }
        }
        assertEquals(2, channelRepository.directMessageCalls)
        assertEquals(mapOf("dm-1" to "Jiacheng"), store.state.value.dmPeerNames)
        scope.cancel()
    }

    @Test
    fun channelOnlySnapshotsNeverFetchDirectMessages() = runBlocking {
        val channelRepository =
            FakeChannelRepository(
                directMessagesResult = { error("unused") }
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1"))))
                    ),
                channelRepository = channelRepository
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        delay(50L)

        assertEquals(0, channelRepository.directMessageCalls)
        assertEquals(emptyMap(), store.state.value.dmPeerNames)
        scope.cancel()
    }

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

    private suspend fun awaitInitialLoadCount(
        inbox: FakeInboxRepository,
        expected: Int
    ) {
        withTimeout(5_000L) {
            while (inbox.initialLoadCount != expected) {
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
}
