package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.joinedHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.virtualForegroundLoadGate
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
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
                    ).copy(
                        createdAtEpochMillis =
                            build.raft.app.model.parseRaftTimestampEpochMillisOrNull("2026-05-19T16:00:00Z"),
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
                    oldestLoadedSeq = seqRange.first.toLong(),
                    newestLoadedSeq = seqRange.last.toLong(),
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
