package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.joinedHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.virtualForegroundLoadGate
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageNodeV2
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableLastKnownFrame
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.TopLevelScopeRefV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * #proj-mobile:#265 Phase 1 regression tests:
 * 1. Opening a `supersededMemory` cached thread/channel renders cached messages synchronously on
 *    Frame 1 without transitioning through `"loading"`, even while SQLite is still reading, then
 *    seamlessly applies newer disk or canonical network messages.
 * 2. Opening a cold-start or LRU-evicted SQLite-cached conversation withholds `ThreadLoadingState`
 *    (`loadingIndicatorRevealed == false`) during [ThreadInitialLoadingRevealDelayMs], while a
 *    genuinely slow/uncached load reveals the loading indicator once the grace window elapses.
 * 3. Switching conversations, servers, or accounts never leaks stale cached messages or leaves a
 *    stale loading-delay timer active.
 */
class SharedThreadCachedOpenNoFlashTest {
    @Test
    fun supersededMemoryRendersSynchronouslyOnFrame1BeforeDiskAndRefreshesSeamlessly() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "m1" to 1L, "m2" to 2L),
            )
        val reader = ControllableDurableReader(frames = mapOf(partition(ownerA) to durableFrame("channel-1", 1..1)))
        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = reader,
                identity = { ownerA },
            )
        val firstStore = SharedThreadStore(dataSource = dataSource, scope = scope)
        firstStore.accept(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2"), firstStore.state.value.payload.messages.map { it.id })
        firstStore.clear()

        // Background activity/summary advances freshness generation -> in-memory entry becomes superseded.
        val provenance = assertNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        payloadStore.freshnessLedger.advance(
            serverId = "server-1",
            threadId = "channel-1",
            ownerScopeId = provenance.threadPayloadOwnerScopeId.orEmpty(),
            cause = "background_message",
        )
        assertNull(dataSource.cachedPayloadIfCurrent("channel-1", provenance))
        assertNotNull(dataSource.supersededCachedPayloadIfCurrent("channel-1", provenance))

        // Park the SQLite read AND delay network refresh so we observe Frame 1 synchronously while
        // SQLite is still suspended.
        reader.parkNextRead()
        repository.threadResult = channelSnapshot("channel-1", "m1" to 1L, "m2" to 2L, "m3" to 3L)
        repository.threadDelayMillis = 80L

        val reopened = SharedThreadStore(dataSource = dataSource, scope = scope)
        val observedStatuses = mutableListOf<String>()
        val observedReveal = mutableListOf<Boolean>()
        val collector =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                reopened.state.collect { state ->
                    observedStatuses += state.payload.status
                    observedReveal += state.loadingIndicatorRevealed
                }
            }
        observedStatuses.clear()
        observedReveal.clear()

        // Frame 1 dispatch (inline up to first suspension, matching ensureStarted() on Main.immediate).
        reopened.dispatch(SharedThreadIntent.Start(channelRoute("channel-1")))
        assertTrue(reader.readStarted.isCompleted, "SQLite read should be in flight")

        val frame1 = reopened.state.value
        assertEquals("content", frame1.payload.status, "Frame 1 must render superseded memory immediately")
        assertEquals(listOf("m1", "m2"), frame1.payload.messages.map { it.id })
        assertEquals(
            ThreadRouteMountPlan.FeatureSurfaces,
            frame1.payload.threadRouteMountPlan(frame1.timelineSlice, frame1.accessResolution),
        )
        assertEquals(ThreadAccessResolution.Resolved, frame1.accessResolution, "resolved access kept on reopen")
        assertFalse(frame1.loadingIndicatorRevealed)
        assertFalse(frame1.timelineSlice.loadingIndicatorRevealed)
        assertFalse("loading" in observedStatuses, "must never transition through loading: $observedStatuses")

        // Disk finishes (older seq=1 < memory seq=2, so memory wins) and network refresh lands with m3.
        reader.releaseRead.complete(Unit)
        advanceUntilIdle()

        val refreshed = reopened.state.value
        assertEquals("content", refreshed.payload.status)
        assertEquals(listOf("m1", "m2", "m3"), refreshed.payload.messages.map { it.id })
        assertFalse(refreshed.payload.durableLastKnown)
        assertFalse(refreshed.payload.backgroundRefreshing)
        assertFalse("loading" in observedStatuses, "status history must stay content: $observedStatuses")
        assertTrue(observedReveal.none { it }, "loading indicator must never reveal: $observedReveal")

        collector.cancel()
        scopeJob.cancelAndJoin()
    }

    @Test
    fun supersededMemoryRendersFrame1ThenUpgradesToNewerDiskAndCanonicalNetwork() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "m1" to 1L, "m2" to 2L),
            )
        val reader = ControllableDurableReader(frames = mapOf(partition(ownerA) to durableFrame("channel-1", 1..4)))
        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = reader,
                identity = { ownerA },
            )
        val firstStore = SharedThreadStore(dataSource = dataSource, scope = scope)
        firstStore.accept(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceUntilIdle()
        firstStore.clear()

        val provenance = assertNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        payloadStore.freshnessLedger.advance(
            serverId = "server-1",
            threadId = "channel-1",
            ownerScopeId = provenance.threadPayloadOwnerScopeId.orEmpty(),
            cause = "realtime_disk_ahead",
        )
        reader.parkNextRead()
        repository.threadResult = channelSnapshot("channel-1", "m1" to 1L, "m2" to 2L, "m3" to 3L, "m4" to 4L, "m5" to 5L)
        repository.getThreadStarted = CompletableDeferred()
        repository.releaseGetThread = CompletableDeferred()

        val reopened = SharedThreadStore(dataSource = dataSource, scope = scope)
        reopened.dispatch(SharedThreadIntent.Start(channelRoute("channel-1")))

        // Frame 1: superseded memory renders immediately while SQLite is still parked.
        assertEquals("content", reopened.state.value.payload.status)
        assertEquals(listOf("m1", "m2"), reopened.state.value.payload.messages.map { it.id })

        // SQLite finishes with seq 1..4 (newer than memory seq 1..2): upgrades seamlessly without loading.
        reader.releaseRead.complete(Unit)
        advanceTimeBy(30L)
        runCurrent()
        assertEquals("content", reopened.state.value.payload.status)
        assertEquals(listOf("v2-1", "v2-2", "v2-3", "v2-4"), reopened.state.value.payload.messages.map { it.id })
        assertTrue(reopened.state.value.payload.durableLastKnown)
        assertFalse(reopened.state.value.loadingIndicatorRevealed)

        // Canonical network finishes with m1..m5.
        repository.releaseGetThread?.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), reopened.state.value.payload.messages.map { it.id })
        assertFalse(reopened.state.value.payload.durableLastKnown)
        scopeJob.cancelAndJoin()
    }

    @Test
    fun supersededMemoryFocusedAnchorRendersImmediatelyWhenPresentAndPreservesAnchorPendingWhenMissing() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "m1" to 1L, "m2" to 2L, "m3" to 3L),
            )
        val reader = ControllableDurableReader(frames = emptyMap())
        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = reader,
                identity = { ownerA },
            )
        val seedStore = SharedThreadStore(dataSource = dataSource, scope = scope)
        seedStore.accept(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceUntilIdle()
        seedStore.clear()

        val provenance = assertNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        payloadStore.freshnessLedger.advance(
            serverId = "server-1",
            threadId = "channel-1",
            ownerScopeId = provenance.threadPayloadOwnerScopeId.orEmpty(),
            cause = "summary_advance",
        )

        // 1. Anchor IS in supersededMemory: renders synchronously on Frame 1 with focusedMessageId preserved (#5244).
        reader.parkNextRead()
        repository.messageContextResult = channelSnapshot("channel-1", "m1" to 1L, "m2" to 2L, "m3" to 3L)
        repository.messageContextDelayMillis = 200L
        val presentStore = SharedThreadStore(dataSource = dataSource, scope = scope)
        presentStore.dispatch(SharedThreadIntent.Start(channelRoute("channel-1").copy(focusedMessageId = "m2")))
        assertEquals("content", presentStore.state.value.payload.status)
        assertEquals("m2", presentStore.state.value.payload.focusedMessageId)
        assertEquals(listOf("m1", "m2", "m3"), presentStore.state.value.payload.messages.map { it.id })
        reader.releaseRead.complete(Unit)
        presentStore.clear()

        // 2. Anchor is MISSING from supersededMemory (and no Activity seed): preserves Hands 7e198cab anchorPending
        //    contract (stays loading, never paints the stale window lacking the anchor).
        reader.parkNextRead()
        repository.messageContextResult = channelSnapshot("channel-1", "m2" to 2L, "m3" to 3L, "m9" to 9L)
        repository.messageContextDelayMillis = 300L
        val missingStore = SharedThreadStore(dataSource = dataSource, scope = scope)
        missingStore.dispatch(SharedThreadIntent.Start(channelRoute("channel-1").copy(focusedMessageId = "m9")))
        assertEquals("loading", missingStore.state.value.payload.status)
        assertTrue(missingStore.state.value.payload.messages.isEmpty())
        assertFalse(missingStore.state.value.loadingIndicatorRevealed, "grace window suppresses flash")
        reader.releaseRead.complete(Unit)
        advanceTimeBy(ThreadInitialLoadingRevealDelayMs)
        runCurrent()
        assertTrue(missingStore.state.value.loadingIndicatorRevealed, "slow anchor fetch reveals loading at grace threshold")
        advanceUntilIdle()
        assertEquals("content", missingStore.state.value.payload.status)
        assertEquals(listOf("m2", "m3", "m9"), missingStore.state.value.payload.messages.map { it.id })
        assertFalse(missingStore.state.value.loadingIndicatorRevealed)
        scopeJob.cancelAndJoin()
    }

    @Test
    fun supersededMemoryCombinedWithActivitySeedSatisfiesFocusedAnchorOnFrame1() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "m1" to 1L, "m2" to 2L),
            )
        val reader = ControllableDurableReader(frames = emptyMap())
        val activitySeedSource =
            ThreadActivityOpenSeedSource(
                ownerServerId = "server-1",
                ownerUserId = "u-a",
                items =
                    listOf(
                        InboxItem(
                            key = "channel-1",
                            kind = InboxItemKind.Channel,
                            channelId = ChannelId("channel-1"),
                            channelName = "general",
                            lastMessageId = MessageId("m3"),
                            lastMessagePreview = "brand new activity message",
                            lastMessageAt = "2026-11-22T12:00:00Z",
                            lastMessageSenderId = "u-a",
                            lastMessageSenderName = "Owner A",
                            lastMessageSenderKind = MemberKind.Human,
                        ),
                    ),
            )
        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = reader,
                identity = { ownerA },
                activitySeed = { activitySeedSource },
            )
        val seedStore = SharedThreadStore(dataSource = dataSource, scope = scope)
        seedStore.accept(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceUntilIdle()
        seedStore.clear()

        val provenance = assertNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        payloadStore.freshnessLedger.advance(
            serverId = "server-1",
            threadId = "channel-1",
            ownerScopeId = provenance.threadPayloadOwnerScopeId.orEmpty(),
            cause = "activity_new_message",
        )
        reader.parkNextRead()
        repository.messageContextResult = channelSnapshot("channel-1", "m1" to 1L, "m2" to 2L, "m3" to 3L)
        repository.messageContextDelayMillis = 200L

        val reopened = SharedThreadStore(dataSource = dataSource, scope = scope)
        reopened.dispatch(SharedThreadIntent.Start(channelRoute("channel-1").copy(focusedMessageId = "m3")))

        // Frame 1 combines supersededMemory (m1, m2) + Activity seed (m3) synchronously before SQLite!
        assertEquals("content", reopened.state.value.payload.status)
        assertEquals(listOf("m1", "m2", "m3"), reopened.state.value.payload.messages.map { it.id })
        assertFalse(reopened.state.value.loadingIndicatorRevealed)
        reader.releaseRead.complete(Unit)
        advanceUntilIdle()
        scopeJob.cancelAndJoin()
    }

    @Test
    fun fastSqliteDurableCacheHitNeverRevealsLoadingIndicatorWithinGraceWindow() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "m1" to 1L, "m2" to 2L, "m3" to 3L),
                threadDelayMillis = 250L,
            )
        // Cold start / LRU eviction: memory cache is empty; SQLite returns in 30 ms (< 120 ms grace).
        val reader =
            ControllableDurableReader(
                frames = mapOf(partition(ownerA) to durableFrame("channel-1", 1..2)),
                readDelayMillis = 30L,
            )
        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = reader,
                identity = { ownerA },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val revealHistory = mutableListOf<Boolean>()
        val sliceRevealHistory = mutableListOf<Boolean>()
        val collector =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                store.state.collect { state ->
                    revealHistory += state.loadingIndicatorRevealed
                    sliceRevealHistory += state.timelineSlice.loadingIndicatorRevealed
                }
            }

        store.dispatch(SharedThreadIntent.Start(channelRoute("channel-1")))
        runCurrent()
        // At t=0 (during 30 ms SQLite read): status is loading, but spinner is withheld by grace delay.
        assertEquals("loading", store.state.value.payload.status)
        assertFalse(store.state.value.loadingIndicatorRevealed)
        assertFalse(store.state.value.timelineSlice.loadingIndicatorRevealed)

        // At t=30 ms: SQLite resolves and cancels the grace timer before 120 ms.
        advanceTimeBy(35L)
        runCurrent()
        assertEquals("content", store.state.value.payload.status)
        assertTrue(store.state.value.payload.durableLastKnown)
        assertEquals(listOf("v2-1", "v2-2"), store.state.value.payload.messages.map { it.id })
        assertFalse(store.state.value.loadingIndicatorRevealed)

        // Even past 120 ms while network refresh is still in flight, spinner must never appear.
        advanceTimeBy(150L)
        runCurrent()
        assertFalse(store.state.value.loadingIndicatorRevealed)

        advanceUntilIdle()
        assertEquals(listOf("m1", "m2", "m3"), store.state.value.payload.messages.map { it.id })
        assertTrue(revealHistory.none { it }, "fast SQLite hit must never reveal loading indicator: $revealHistory")
        assertTrue(sliceRevealHistory.none { it }, "timeline slice must never reveal loading indicator: $sliceRevealHistory")

        collector.cancel()
        scopeJob.cancelAndJoin()
    }

    @Test
    fun slowUncachedLoadRevealsLoadingIndicatorAfterGraceThresholdThenClearsOnContent() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "m1" to 1L),
                threadDelayMillis = 400L,
            )
        val reader = ControllableDurableReader(frames = emptyMap(), readDelayMillis = 20L)
        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = reader,
                identity = { ownerA },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(channelRoute("channel-1")))
        runCurrent()
        assertEquals("loading", store.state.value.payload.status)
        assertFalse(store.state.value.loadingIndicatorRevealed)

        // Still inside grace window at 119 ms (after disk missed at 20 ms).
        advanceTimeBy(ThreadInitialLoadingRevealDelayMs - 1L)
        runCurrent()
        assertEquals("loading", store.state.value.payload.status)
        assertFalse(store.state.value.loadingIndicatorRevealed)
        assertFalse(store.state.value.timelineSlice.loadingIndicatorRevealed)

        // At exactly 120 ms: loading indicator reveals.
        advanceTimeBy(1L)
        runCurrent()
        assertEquals("loading", store.state.value.payload.status)
        assertTrue(store.state.value.loadingIndicatorRevealed)
        assertTrue(store.state.value.timelineSlice.loadingIndicatorRevealed)

        // Network arrives: content renders and loadingIndicatorRevealed resets to false.
        advanceUntilIdle()
        assertEquals("content", store.state.value.payload.status)
        assertEquals(listOf("m1"), store.state.value.payload.messages.map { it.id })
        assertFalse(store.state.value.loadingIndicatorRevealed)
        assertFalse(store.state.value.timelineSlice.loadingIndicatorRevealed)
        scopeJob.cancelAndJoin()
    }

    @Test
    fun slowDiskMissPastGraceThresholdKeepsRevealedLoadingIndicatorWithoutBlinkingOff() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "m1" to 1L),
                threadDelayMillis = 300L,
            )
        // Degraded disk read slower than 120 ms that misses.
        val reader = ControllableDurableReader(frames = emptyMap(), readDelayMillis = 160L)
        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = reader,
                identity = { ownerA },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceTimeBy(ThreadInitialLoadingRevealDelayMs)
        runCurrent()
        assertTrue(store.state.value.loadingIndicatorRevealed, "revealed at 120 ms while disk still reading")

        // Disk finishes and misses at 160 ms: must stay revealed (no blink off!).
        advanceTimeBy(50L)
        runCurrent()
        assertEquals("loading", store.state.value.payload.status)
        assertTrue(store.state.value.loadingIndicatorRevealed, "must stay revealed after slow disk miss")

        advanceUntilIdle()
        assertEquals("content", store.state.value.payload.status)
        assertFalse(store.state.value.loadingIndicatorRevealed)
        scopeJob.cancelAndJoin()
    }

    @Test
    fun durableEmptyStandInRespectsGraceWindowAndClearsOnCanonicalEmptyOrError() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val emptyDurableFrame =
            ThreadDurableLastKnownFrame(
                scope = SyncScopeKey(partition(ownerA), "channel-1", "channel"),
                envelopes = emptyList(),
                cursor = PersistedCursor(null, null, null, hasLoadedOldest = true),
            )
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Empty(EmptyReason.NoMessages),
                threadDelayMillis = 300L,
            )
        val reader = ControllableDurableReader(frames = mapOf(partition(ownerA) to emptyDurableFrame), readDelayMillis = 20L)
        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = reader,
                identity = { ownerA },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceTimeBy(40L)
        runCurrent()
        assertEquals("empty", store.state.value.payload.status)
        assertTrue(store.state.value.payload.durableLastKnown)
        assertFalse(store.state.value.loadingIndicatorRevealed, "grace window suppresses flash on empty stand-in")

        advanceTimeBy(ThreadInitialLoadingRevealDelayMs - 40L)
        runCurrent()
        assertTrue(store.state.value.loadingIndicatorRevealed, "reveals at 120 ms while first canonical load is in flight")

        advanceUntilIdle()
        assertEquals("empty", store.state.value.payload.status)
        assertFalse(store.state.value.payload.durableLastKnown)
        assertFalse(store.state.value.loadingIndicatorRevealed)

        // Also verify failure clears loading indicator cleanly.
        repository.threadResult = DataResult.Error(ScreenError(code = "offline", retryable = true))
        val failingStore = SharedThreadStore(dataSource = dataSource, scope = scope)
        failingStore.dispatch(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceTimeBy(ThreadInitialLoadingRevealDelayMs)
        runCurrent()
        assertTrue(failingStore.state.value.loadingIndicatorRevealed)
        advanceUntilIdle()
        assertTrue(failingStore.state.value.payload.mainLoadFailed)
        assertFalse(failingStore.state.value.loadingIndicatorRevealed)
        scopeJob.cancelAndJoin()
    }

    @Test
    fun switchingConversationsCancelsStaleLoadingTimerAndGivesFreshGraceWindow() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResultsById =
                    mapOf(
                        ThreadId("channel-a") to channelSnapshot("channel-a", "a1" to 1L),
                        ThreadId("channel-b") to channelSnapshot("channel-b", "b1" to 1L),
                        ThreadId("channel-c") to channelSnapshot("channel-c", "c1" to 1L),
                    ),
                threadDelayMillis = 500L,
            )
        val reader =
            ControllableDurableReader(
                frames = mapOf(partition(ownerA) to durableFrame("channel-b", 1..2)),
                readDelayMillis = 30L,
            )
        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = reader,
                identity = { ownerA },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        // Open uncached slow channel A; at t=80 ms switch to SQLite-cached channel B (30 ms read -> t=110 ms).
        store.dispatch(SharedThreadIntent.Start(channelRoute("channel-a")))
        advanceTimeBy(80L)
        runCurrent()
        assertFalse(store.state.value.loadingIndicatorRevealed)

        store.dispatch(SharedThreadIntent.Start(channelRoute("channel-b")))
        advanceTimeBy(35L)
        runCurrent()
        assertEquals("channel-b", store.state.value.payload.sourceThreadId)
        assertEquals("content", store.state.value.payload.status)
        assertFalse(store.state.value.loadingIndicatorRevealed)

        // Pass t=120 ms (where channel A's old timer would have fired): must stay false!
        advanceTimeBy(30L)
        runCurrent()
        assertFalse(store.state.value.loadingIndicatorRevealed)

        // Now switch to slow uncached channel C: must get a full fresh 120 ms grace window.
        store.dispatch(SharedThreadIntent.Start(channelRoute("channel-c")))
        advanceTimeBy(ThreadInitialLoadingRevealDelayMs - 1L)
        runCurrent()
        assertEquals("channel-c", store.state.value.payload.sourceThreadId)
        assertFalse(store.state.value.loadingIndicatorRevealed, "channel C must keep its own fresh grace window")

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(store.state.value.loadingIndicatorRevealed, "channel C reveals at its own grace threshold")

        // Leaving (clear) cancels any pending timer cleanly.
        store.dispatch(SharedThreadIntent.Start(channelRoute("channel-a")))
        runCurrent()
        assertFalse(store.state.value.loadingIndicatorRevealed)
        store.clear()
        advanceTimeBy(ThreadInitialLoadingRevealDelayMs * 2L)
        runCurrent()
        assertFalse(store.state.value.loadingIndicatorRevealed, "clear() must cancel pending reveal timer")
        scopeJob.cancelAndJoin()
    }

    @Test
    fun accountSwitchDuringDiskReadEvictsSupersededMemoryAndNeverLeaksAcrossOwners() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        var activeIdentity = ownerA
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "a1" to 1L),
            )
        val reader = ControllableDurableReader(frames = mapOf(partition(ownerA) to durableFrame("channel-1", 1..1)))
        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = reader,
                identity = { activeIdentity },
            )
        val firstStore = SharedThreadStore(dataSource = dataSource, scope = scope)
        firstStore.accept(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceUntilIdle()
        firstStore.clear()

        val ownerAProvenance = assertNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        payloadStore.freshnessLedger.advance(
            serverId = "server-1",
            threadId = "channel-1",
            ownerScopeId = ownerAProvenance.threadPayloadOwnerScopeId.orEmpty(),
            cause = "summary_advance",
        )

        // 1. Account switch BEFORE open: Owner B must never see Owner A's supersededMemory.
        activeIdentity = ownerB
        repository.threadResult = channelSnapshot("channel-1", "b1" to 1L)
        repository.getThreadStarted = CompletableDeferred()
        repository.releaseGetThread = CompletableDeferred()
        val ownerBStore = SharedThreadStore(dataSource = dataSource, scope = scope)
        ownerBStore.dispatch(SharedThreadIntent.Start(channelRoute("channel-1")))
        assertEquals("loading", ownerBStore.state.value.payload.status)
        assertTrue(ownerBStore.state.value.payload.messages.isEmpty())
        assertFalse(ownerBStore.state.value.loadingIndicatorRevealed)
        repository.releaseGetThread?.complete(Unit)
        ownerBStore.clear()

        // 2. Account switch WHILE Owner A's SQLite read is suspended after Frame-1 supersededMemory publish:
        //    Owner A's messages must be immediately evicted once SQLite resumes and sees provenance moved.
        activeIdentity = ownerA
        val restoredOwnerA = assertNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        assertNotNull(
            dataSource.supersededCachedPayloadIfCurrent("channel-1", restoredOwnerA),
            "precondition: Owner A's superseded entry is still present for Owner A",
        )
        reader.parkNextRead()
        repository.getThreadStarted = CompletableDeferred()
        repository.releaseGetThread = CompletableDeferred()
        val racingStore = SharedThreadStore(dataSource = dataSource, scope = scope)
        racingStore.dispatch(SharedThreadIntent.Start(channelRoute("channel-1")))
        assertEquals(listOf("a1"), racingStore.state.value.payload.messages.map { it.id })

        // Switch to Owner B while Owner A's SQLite read is still parked.
        activeIdentity = ownerB
        assertNotNull(dataSource.captureReplyCacheProducerContext())
        reader.releaseRead.complete(Unit)
        runCurrent()
        assertEquals("loading", racingStore.state.value.payload.status)
        assertTrue(
            racingStore.state.value.payload.messages.isEmpty(),
            "Owner A's superseded messages must be evicted immediately on owner switch",
        )
        assertFalse(
            repository.getThreadStarted!!.isCompleted,
            "provenance-invalidated start must return early instead of fetching old owner's channel under Owner B",
        )
        repository.releaseGetThread?.complete(Unit)

        // 3. Account switch + new Start(channel-2) while Owner A's SQLite read is parked must NOT
        //    allow Owner A's post_durable_reconcile admitted == null branch to clobber Owner B's channel-2.
        activeIdentity = ownerA
        repository.threadResult = channelSnapshot("channel-1", "a1" to 1L)
        reader.parkNextRead()
        val clobberStore = SharedThreadStore(dataSource = dataSource, scope = scope)
        clobberStore.dispatch(SharedThreadIntent.Start(channelRoute("channel-1")))
        assertEquals(listOf("a1"), clobberStore.state.value.payload.messages.map { it.id })

        activeIdentity = ownerB
        repository.threadResult = channelSnapshot("channel-2", "b2" to 1L)
        repository.getThreadStarted = null
        repository.releaseGetThread = null
        clobberStore.dispatch(SharedThreadIntent.Start(channelRoute("channel-2")))
        advanceUntilIdle()
        assertEquals("channel-2", clobberStore.state.value.payload.sourceThreadId)
        assertEquals(listOf("b2"), clobberStore.state.value.payload.messages.map { it.id })

        reader.releaseRead.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            "channel-2",
            clobberStore.state.value.payload.sourceThreadId,
            "stale Owner A reconcile must never clobber Owner B's active channel-2",
        )
        assertEquals(listOf("b2"), clobberStore.state.value.payload.messages.map { it.id })
        scopeJob.cancelAndJoin()
    }

    @Test
    fun equalSeqDiskDoesNotDowngradeSupersededMemoryAndNewerDiskPreservesMetadata() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "m1" to 1L, "m2" to 2L),
            )
        // Case A: disk seq (2) == memory seq (2) -> must NOT downgrade durableLastKnown to true or replace m1..m2.
        val equalReader = ControllableDurableReader(frames = mapOf(partition(ownerA) to durableFrame("channel-1", 1..2)))
        val equalDataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = equalReader,
                identity = { ownerA },
            )
        val seedStore = SharedThreadStore(dataSource = equalDataSource, scope = scope)
        seedStore.accept(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceUntilIdle()
        seedStore.clear()

        val provenance = assertNotNull(equalDataSource.captureReplyCacheProducerContext()).provenance
        payloadStore.freshnessLedger.advance(
            serverId = "server-1",
            threadId = "channel-1",
            ownerScopeId = provenance.threadPayloadOwnerScopeId.orEmpty(),
            cause = "summary_advance",
        )
        equalReader.parkNextRead()
        repository.getThreadStarted = CompletableDeferred()
        repository.releaseGetThread = CompletableDeferred()
        val equalReopen = SharedThreadStore(dataSource = equalDataSource, scope = scope)
        equalReopen.dispatch(SharedThreadIntent.Start(channelRoute("channel-1")))
        assertEquals(listOf("m1", "m2"), equalReopen.state.value.payload.messages.map { it.id })
        assertFalse(equalReopen.state.value.payload.durableLastKnown)

        equalReader.releaseRead.complete(Unit)
        advanceTimeBy(30L)
        runCurrent()
        assertEquals(
            listOf("m1", "m2"),
            equalReopen.state.value.payload.messages.map { it.id },
            "equal-seq disk must not downgrade Frame-1 memory copy",
        )
        assertFalse(equalReopen.state.value.payload.durableLastKnown)
        repository.releaseGetThread?.complete(Unit)
        equalReopen.clear()

        // Case B: disk seq (4) > memory seq (2) -> upgrades messages while preserving live originMessage & metadata.
        val cachedPayload =
            assertNotNull(
                equalDataSource.cachedPayloadIfCurrent("channel-1", provenance)
                    ?: equalDataSource.supersededCachedPayloadIfCurrent("channel-1", provenance),
            )
        val richOrigin = RaftThreadMessageItem(id = "origin-1", authorId = "u-a", authorName = "Owner A", body = "parent post")
        val enriched =
            cachedPayload.copy(
                originMessage = richOrigin,
                originStatus = "ready",
                subtitle = "Project channel",
                channelArchived = true,
            )
        payloadStore.put("server-1", "channel-1", enriched, provenance.threadPayloadOwnerScopeId.orEmpty())
        payloadStore.freshnessLedger.advance(
            serverId = "server-1",
            threadId = "channel-1",
            ownerScopeId = provenance.threadPayloadOwnerScopeId.orEmpty(),
            cause = "disk_ahead",
        )
        val newerReader = ControllableDurableReader(frames = mapOf(partition(ownerA) to durableFrame("channel-1", 1..4)))
        val newerDataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = newerReader,
                identity = { ownerA },
            )
        newerReader.parkNextRead()
        repository.getThreadStarted = CompletableDeferred()
        repository.releaseGetThread = CompletableDeferred()
        val newerReopen = SharedThreadStore(dataSource = newerDataSource, scope = scope)
        newerReopen.dispatch(SharedThreadIntent.Start(channelRoute("channel-1")))
        assertEquals("origin-1", newerReopen.state.value.payload.originMessage?.id)

        newerReader.releaseRead.complete(Unit)
        advanceTimeBy(30L)
        runCurrent()
        assertEquals(listOf("v2-1", "v2-2", "v2-3", "v2-4"), newerReopen.state.value.payload.messages.map { it.id })
        assertEquals("origin-1", newerReopen.state.value.payload.originMessage?.id, "originMessage must survive disk upgrade")
        assertEquals("Project channel", newerReopen.state.value.payload.subtitle, "subtitle metadata must survive disk upgrade")
        assertTrue(newerReopen.state.value.payload.channelArchived, "live channelArchived must survive disk upgrade")
        repository.releaseGetThread?.complete(Unit)
        scopeJob.cancelAndJoin()
    }

    @Test
    fun explicitRetryRevealsLoadingImmediatelyAndRemoveEvictsSupersededCache() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Error(ScreenError("network_down")),
            )
        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = ControllableDurableReader(frames = emptyMap()),
                identity = { ownerA },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceUntilIdle()
        assertEquals("error", store.state.value.payload.status)
        assertFalse(store.state.value.loadingIndicatorRevealed)

        // Explicit Retry (Refresh) after error must reveal loading immediately without a 120ms blank flash.
        repository.threadResult = channelSnapshot("channel-1", "m1" to 1L)
        repository.getThreadStarted = CompletableDeferred()
        repository.releaseGetThread = CompletableDeferred()
        store.dispatch(SharedThreadIntent.Refresh)
        runCurrent()
        assertEquals("loading", store.state.value.payload.status)
        assertTrue(store.state.value.loadingIndicatorRevealed, "explicit Retry must reveal loading immediately")
        repository.releaseGetThread?.complete(Unit)
        advanceUntilIdle()
        assertEquals("content", store.state.value.payload.status)
        assertFalse(store.state.value.loadingIndicatorRevealed)
        store.clear()

        // Advance generation so cached entry becomes superseded, then call payloadStore.remove(...) (e.g. NoPermission):
        // remove() must evict the superseded entry too so the next open never flashes revoked messages on Frame 1.
        val provenance = assertNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val ownerScope = provenance.threadPayloadOwnerScopeId.orEmpty()
        payloadStore.freshnessLedger.advance(
            serverId = "server-1",
            threadId = "channel-1",
            ownerScopeId = ownerScope,
            cause = "stale_before_revoke",
        )
        assertNotNull(dataSource.supersededCachedPayloadIfCurrent("channel-1", provenance))
        payloadStore.remove("server-1", "channel-1", ownerScope)
        assertNull(
            dataSource.supersededCachedPayloadIfCurrent("channel-1", provenance),
            "payloadStore.remove must evict superseded entries as well as current ones",
        )
        scopeJob.cancelAndJoin()
    }

    @Test
    fun awaitsInitialLoadingIndicatorMatchesAllLoadingAndTerminalStates() {
        assertTrue(
            SharedThreadState(
                payload = channelRoute("channel-1").copy(status = "loading"),
                accessResolution = ThreadAccessResolution.Loading,
            ).awaitsInitialLoadingIndicator(),
        )
        assertTrue(
            SharedThreadState(
                payload =
                    channelRoute("channel-1").copy(
                        status = "empty",
                        durableLastKnown = false,
                        backgroundRefreshing = true,
                        tasks = listOf(RaftThreadTaskItem(id = "t1", title = "Task 1", status = "open")),
                    ),
                accessResolution = ThreadAccessResolution.Resolved,
            ).awaitsInitialLoadingIndicator(),
            "backgroundRefreshing empty chat with tasks renders LoadingShell and must reveal loading indicator",
        )
        assertTrue(
            SharedThreadState(
                payload = channelRoute("channel-1").copy(status = "empty", durableLastKnown = true, mainLoadFailed = false),
                accessResolution = ThreadAccessResolution.Resolved,
            ).awaitsInitialLoadingIndicator(),
        )
        assertFalse(
            SharedThreadState(
                payload = channelRoute("channel-1").copy(status = "empty", durableLastKnown = true, mainLoadFailed = true),
                accessResolution = ThreadAccessResolution.Resolved,
            ).awaitsInitialLoadingIndicator(),
        )
        assertFalse(
            SharedThreadState(
                payload =
                    channelRoute("channel-1").copy(
                        status = "content",
                        messages = listOf(RaftThreadMessageItem(id = "m1", authorId = "u-a", authorName = "Owner A", body = "hi")),
                    ),
                accessResolution = ThreadAccessResolution.Loading,
            ).awaitsInitialLoadingIndicator(),
        )
        assertTrue(
            SharedThreadState(
                payload =
                    channelRoute("channel-1").copy(
                        status = "content",
                        selectedTab = "tasks",
                        messages = listOf(RaftThreadMessageItem(id = "m1", authorId = "u-a", authorName = "Owner A", body = "hi")),
                    ),
                accessResolution = ThreadAccessResolution.Loading,
            ).awaitsInitialLoadingIndicator(),
        )
        assertFalse(
            SharedThreadState(
                payload = channelRoute("channel-1").copy(status = "empty", durableLastKnown = false),
                accessResolution = ThreadAccessResolution.Resolved,
            ).awaitsInitialLoadingIndicator(),
        )
        assertFalse(
            SharedThreadState(
                payload = channelRoute("channel-1").copy(status = "error"),
                accessResolution = ThreadAccessResolution.Error,
            ).awaitsInitialLoadingIndicator(),
        )
        assertFalse(
            SharedThreadState(
                payload = channelRoute("channel-1").copy(status = "no_permission"),
                accessResolution = ThreadAccessResolution.NoPermission,
            ).awaitsInitialLoadingIndicator(),
        )
        assertFalse(
            SharedThreadState(
                payload = channelRoute("channel-1").copy(status = "loading"),
                accessResolution = ThreadAccessResolution.NoPermission,
            ).awaitsInitialLoadingIndicator(),
        )
    }

    private class ControllableDurableReader(
        private val frames: Map<DurablePartitionKey, ThreadDurableLastKnownFrame>,
        private val readDelayMillis: Long = 0L,
    ) : ThreadDurableLastKnownReader {
        private var parkRead = false
        var readStarted = CompletableDeferred<Unit>()
            private set
        var releaseRead = CompletableDeferred<Unit>()
            private set

        fun parkNextRead() {
            parkRead = true
            readStarted = CompletableDeferred()
            releaseRead = CompletableDeferred()
        }

        override suspend fun read(
            partition: DurablePartitionKey,
            channelId: SyncScopeId,
        ): ThreadDurableLastKnownFrame? {
            if (parkRead) {
                parkRead = false
                readStarted.complete(Unit)
                releaseRead.await()
            } else if (readDelayMillis > 0L) {
                delay(readDelayMillis)
            }
            val frame = frames[partition] ?: return null
            return frame.takeIf { it.scope.scopeId == channelId }
        }
    }

    private fun TestScope.makeDataSource(
        payloadStore: SharedThreadPayloadStore,
        repository: FakeConversationRepository,
        reader: ThreadDurableLastKnownReader,
        identity: () -> ThreadReplyCacheIdentitySnapshot,
        activitySeed: (() -> ThreadActivityOpenSeedSource?)? = null,
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            threadParseDispatcher = backgroundScope.testDispatcher(),
            messageStore = SharedThreadMessageStore(scope = backgroundScope),
            payloadStore = payloadStore,
            networkApi = FakeThreadApi(failChannelMessages = true),
            conversationRepository = repository,
            channelRepository = FakeChannelRepository { DataResult.Content(joinedHomeSnapshot()) },
            sessionStore = InMemoryRaftAuthSessionStore(identity().session),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            replyCacheIdentityAuthority = { identity() },
            durableLastKnownReader = reader,
            activityOpenSeedSource = activitySeed,
        )

    private fun channelRoute(channelId: String): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-$channelId",
            title = channelId,
            sourceThreadId = channelId,
            streamChannelId = channelId,
            status = "loading",
            canSend = true,
        )

    private fun channelSnapshot(
        channelId: String,
        vararg messages: Pair<String, Long>,
    ) = DataResult.Content(
        threadSnapshot(
            threadId = channelId,
            messages =
                messages.map { (id, seq) ->
                    message(
                        threadId = channelId,
                        channelId = channelId,
                        id = id,
                        seq = seq,
                        body = "body $id",
                    )
                },
            conversation = conversationSummary(channelId).copy(joined = true),
        ),
    )

    private fun durableFrame(channelId: String, seqRange: IntRange): ThreadDurableLastKnownFrame =
        ThreadDurableLastKnownFrame(
            scope = SyncScopeKey(partition(ownerA), channelId, "channel"),
            envelopes =
                seqRange.map { index ->
                    CanonicalMessageEnvelopeV2(
                        fact =
                            CanonicalMessageNodeV2(
                                id = "v2-$index",
                                seq = index.toLong(),
                                senderType = "user",
                                senderId = "u-a",
                                content = "durable $index",
                                messageType = "chat",
                                createdAt = "2026-11-22T10:00:${index.toString().padStart(2, '0')}Z",
                                senderName = "Owner A",
                            ),
                        context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-1", channelId)),
                    )
                },
            cursor =
                PersistedCursor(
                    latestSeq = seqRange.last.toLong(),
                    oldestSeq = seqRange.first.toLong(),
                    newestSeq = seqRange.last.toLong(),
                    hasLoadedOldest = true,
                    epoch = "epoch-v2",
                ),
        )

    private fun partition(identity: ThreadReplyCacheIdentitySnapshot): DurablePartitionKey =
        DurablePartitionKey(
            serverId = identity.serverId,
            principalId = identity.principalId,
            consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            environmentId = identity.environmentId,
        )

    private fun session(userId: String, name: String): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@example.test", displayName = name),
            accessToken = "access-$userId",
            refreshToken = "refresh-$userId",
            selectedServerId = "server-1",
        )

    private val ownerA =
        ThreadReplyCacheIdentitySnapshot(
            session = session("u-a", "Owner A"),
            environmentId = TestThreadEnvironmentId,
            principalId = "u-a",
            serverId = "server-1",
            authGeneration = 1L,
            selectionGeneration = 1L,
            bindingGeneration = 1L,
        )

    private val ownerB =
        ThreadReplyCacheIdentitySnapshot(
            session = session("u-b", "Owner B"),
            environmentId = TestThreadEnvironmentId,
            principalId = "u-b",
            serverId = "server-1",
            authGeneration = 2L,
            selectionGeneration = 1L,
            bindingGeneration = 2L,
        )
}
