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
import build.raft.app.data.InboxItem
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageNodeV2
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.MessageRefV2
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.ReplyMembershipV2
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.ReplyScopeRefV2
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableLastKnownFrame
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.ThreadDurableTimelineAnchor
import build.raft.app.network.sync.ThreadDurableTimelinePage
import build.raft.app.network.sync.TopLevelScopeRefV2
import build.raft.app.thread.ActivityPreviewEllipsis
import build.raft.app.thread.ActivityPreviewMaxLength
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadMessageStore
import build.raft.app.thread.SharedThreadPayloadStore
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadAccessResolution
import build.raft.app.thread.ThreadActivityOpenSeedSource
import build.raft.app.thread.ThreadInitialLoadingRevealDelayMs
import build.raft.app.thread.channelTaskWritesShown
import build.raft.app.thread.dispatch
import build.raft.app.thread.key
import build.raft.app.thread.threadFooterShowsArchived
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
 * #proj-mobile:#265 Phase 1 & Phase 2 regression tests:
 * 1. Opening a `supersededMemory` cached thread/channel renders cached messages synchronously on
 *    Frame 1 without transitioning through `"loading"`, even while SQLite is still reading, then
 *    seamlessly applies newer disk or canonical network messages.
 * 2. Opening a cold-start or LRU-evicted SQLite-cached conversation withholds `ThreadLoadingState`
 *    (`loadingIndicatorRevealed == false`) during [ThreadInitialLoadingRevealDelayMs], while a
 *    genuinely slow/uncached load reveals the loading indicator once the grace window elapses.
 * 3. Opening a cached channel/thread at a focused message renders immediately when Activity holds
 *    the newer anchor (and falls back to the Activity seed for older anchors on SQLite miss), while
 *    SQLite anchored islands overlay onto cached/superseded metadata without wiping originMessage,
 *    archived state, or verified composer access.
 * 4. Switching conversations, servers, or accounts never leaks stale cached messages or leaves a
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

    @Test
    fun hotMemoryCacheWithNewerActivityAnchorRendersFrame1ThenUpgradesToDurableIslandAndCanonical() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "m1" to 1L, "m2" to 2L),
            )
        val seedStore =
            SharedThreadStore(
                dataSource =
                    makeDataSource(
                        payloadStore = payloadStore,
                        repository = repository,
                        reader = ControllableDurableReader(frames = emptyMap()),
                        identity = { ownerA },
                    ),
                scope = scope,
            )
        seedStore.accept(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceUntilIdle()
        seedStore.clear()

        val probeDs =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = ControllableDurableReader(frames = emptyMap()),
                identity = { ownerA },
            )
        val provenance = assertNotNull(probeDs.captureReplyCacheProducerContext()).provenance
        val cachedEntry = assertNotNull(probeDs.cachedPayloadIfCurrent("channel-1", provenance))
        val richOrigin = RaftThreadMessageItem(id = "origin-1", authorId = "u-a", authorName = "Owner A", body = "parent")
        payloadStore.put(
            serverId = "server-1",
            threadId = "channel-1",
            payload =
                cachedEntry.copy(
                    subtitle = "Core channel",
                    originMessage = richOrigin,
                    originStatus = "ready",
                    channelArchived = false,
                ),
            ownerScopeId = provenance.threadPayloadOwnerScopeId.orEmpty(),
        )

        val anchorActivitySource =
            activitySeedSource(
                channelActivityRow(
                    channelId = "channel-1",
                    messageId = "m3",
                    preview = "newer from activity",
                    at = "2026-05-19T16:05:00Z",
                ),
            )
        val reader =
            ControllableDurableReader(
                frames = emptyMap(),
                timelinePages = { part, channelId, anchor ->
                    if (
                        part == partition(ownerA) &&
                        channelId == "channel-1" &&
                        anchor is ThreadDurableTimelineAnchor.AroundMessageId &&
                        anchor.messageId == "m3"
                    ) {
                        durableTimelinePage(
                            channelId = "channel-1",
                            items = listOf("m2" to 2L to "durable m2", "m3" to 3L to "durable full m3"),
                        )
                    } else {
                        null
                    }
                },
            )
        reader.parkNextRead()
        repository.messageContextResult = channelSnapshot("channel-1", "m1" to 1L, "m2" to 2L, "m3" to 3L)
        repository.messageContextDelayMillis = 400L

        val logs = mutableListOf<String>()
        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = reader,
                identity = { ownerA },
                activitySeed = { anchorActivitySource },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = { logs += it })
        store.dispatch(SharedThreadIntent.Start(channelRoute("channel-1").copy(focusedMessageId = "m3")))

        // Frame 1 (while SQLite is still parked): renders [m1, m2, m3] synchronously with metadata & resolved access.
        assertEquals("content", store.state.value.payload.status)
        assertEquals(listOf("m1", "m2", "m3"), store.state.value.payload.messages.map { it.id })
        assertEquals("newer from activity", store.state.value.payload.messages.last().body)
        assertEquals("Core channel", store.state.value.payload.subtitle)
        assertEquals("origin-1", store.state.value.payload.originMessage?.id)
        assertEquals(ThreadAccessResolution.Resolved, store.state.value.accessResolution)
        assertTrue(store.state.value.writeActionsEnabled)
        assertFalse(store.state.value.loadingIndicatorRevealed)
        assertTrue(logs.any { "thread_start_cached_anchor_seed" in it && "anchor=m3" in it }, logs.toString())

        // SQLite resolves with the anchored island around m3: overlays full durable messages while preserving metadata & access.
        reader.releaseRead.complete(Unit)
        advanceTimeBy(10L)
        runCurrent()
        assertEquals("content", store.state.value.payload.status)
        assertEquals(listOf("m2", "m3"), store.state.value.payload.messages.map { it.id })
        assertEquals("durable full m3", store.state.value.payload.messages.last().body)
        assertEquals("Core channel", store.state.value.payload.subtitle)
        assertEquals("origin-1", store.state.value.payload.originMessage?.id)
        assertEquals(ThreadAccessResolution.Resolved, store.state.value.accessResolution)
        assertTrue(store.state.value.writeActionsEnabled)
        assertFalse(store.state.value.loadingIndicatorRevealed)
        assertEquals(1, reader.touchedScopes.size, "adopted SQLite anchored island must touch user access")

        // Canonical network finishes with full m1..m3.
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2", "m3"), store.state.value.payload.messages.map { it.id })
        assertFalse(store.state.value.payload.durableLastKnown)
        scopeJob.cancelAndJoin()
    }

    @Test
    fun hotMemoryCacheWithNewerActivityAnchorStaysRenderedWhenSqliteMissesAndSettlesOnOfflineError() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "m1" to 1L, "m2" to 2L),
            )
        val seedStore =
            SharedThreadStore(
                dataSource =
                    makeDataSource(
                        payloadStore = payloadStore,
                        repository = repository,
                        reader = ControllableDurableReader(frames = emptyMap()),
                        identity = { ownerA },
                    ),
                scope = scope,
            )
        seedStore.accept(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceUntilIdle()
        seedStore.clear()

        val truncatedPreview = "${"a".repeat(ActivityPreviewMaxLength)}$ActivityPreviewEllipsis"
        val anchorActivitySource =
            activitySeedSource(
                channelActivityRow(
                    channelId = "channel-1",
                    messageId = "m3",
                    preview = truncatedPreview,
                    at = "2026-05-19T16:05:00Z",
                ),
            )
        val reader = ControllableDurableReader(frames = emptyMap(), timelinePages = { _, _, _ -> null })
        reader.parkNextRead()
        val offlineError = DataResult.Error(ScreenError(code = "thread_network", retryable = true))
        repository.messageContextResult = offlineError
        repository.threadResult = offlineError
        repository.messageContextDelayMillis = 200L

        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = reader,
                identity = { ownerA },
                activitySeed = { anchorActivitySource },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.dispatch(SharedThreadIntent.Start(channelRoute("channel-1").copy(focusedMessageId = "m3")))

        // Frame 1: renders [m1, m2, m3(truncated)] without flashing loading.
        assertEquals("content", store.state.value.payload.status)
        assertEquals(listOf("m1", "m2", "m3"), store.state.value.payload.messages.map { it.id })
        assertTrue(store.state.value.payload.messages.last().activityPreviewTruncated)
        assertFalse(store.state.value.loadingIndicatorRevealed)

        // SQLite misses -> keeps [m1, m2, m3] visible without dropping back to loading.
        reader.releaseRead.complete(Unit)
        advanceTimeBy(10L)
        runCurrent()
        assertEquals("content", store.state.value.payload.status)
        assertEquals(listOf("m1", "m2", "m3"), store.state.value.payload.messages.map { it.id })
        assertTrue(reader.touchedScopes.isEmpty(), "SQLite miss must not touch user access")

        // Offline network error settles truncated preview cleanly while preserving content.
        advanceUntilIdle()
        val failed = store.state.value.payload
        assertEquals("content", failed.status)
        assertTrue(failed.mainLoadFailed)
        assertEquals(listOf("m1", "m2", "m3"), failed.messages.map { it.id })
        assertFalse(failed.messages.last().activityPreviewTruncated)
        assertTrue(failed.messages.last().activityPreviewLoadFailed)
        scopeJob.cancelAndJoin()
    }

    @Test
    fun hotMemoryCacheWithOlderActivityAnchorFallsBackToActivitySeedWhenSqliteMisses() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "m10" to 10L, "m11" to 11L),
            )
        val seedStore =
            SharedThreadStore(
                dataSource =
                    makeDataSource(
                        payloadStore = payloadStore,
                        repository = repository,
                        reader = ControllableDurableReader(frames = emptyMap()),
                        identity = { ownerA },
                    ),
                scope = scope,
            )
        seedStore.accept(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceUntilIdle()
        seedStore.clear()

        // MentionAction row pointing to an older message m5 (earlier than m10..m11 in hot memory cache).
        val olderActivitySource =
            activitySeedSource(
                channelActivityRow(
                    channelId = "channel-1",
                    messageId = "m5",
                    preview = "older mention target",
                    at = "2026-05-19T15:00:00Z",
                    kind = InboxItemKind.MentionAction,
                ),
            )
        repository.messageContextResult = channelSnapshot("channel-1", "m4" to 4L, "m5" to 5L, "m6" to 6L)
        repository.messageContextDelayMillis = 400L

        val logs = mutableListOf<String>()
        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = ControllableDurableReader(frames = emptyMap(), timelinePages = { _, _, _ -> null }),
                identity = { ownerA },
                activitySeed = { olderActivitySource },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = { logs += it })
        store.dispatch(SharedThreadIntent.Start(channelRoute("channel-1").copy(focusedMessageId = "m5")))
        runCurrent()

        // Hot memory lacks older m5, SQLite misses, but Activity seed holds m5 -> renders m5 instead of blank spinner.
        assertEquals("content", store.state.value.payload.status)
        assertEquals(listOf("m5"), store.state.value.payload.messages.map { it.id })
        assertEquals("older mention target", store.state.value.payload.messages.single().body)
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        assertFalse(store.state.value.writeActionsEnabled)
        assertFalse(store.state.value.loadingIndicatorRevealed)
        assertTrue(
            logs.any { "thread_start_anchor_pending" in it && "anchor=m5" in it && "candidate=memory" in it && "seed=true" in it },
            logs.toString(),
        )

        advanceUntilIdle()
        assertEquals(listOf("m4", "m5", "m6"), store.state.value.payload.messages.map { it.id })
        scopeJob.cancelAndJoin()
    }

    @Test
    fun durableAnchoredIslandOverCachedAndSupersededMemoryPreservesMetadataAndResolvedAccess() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "m10" to 10L, "m11" to 11L),
            )
        val seedStore =
            SharedThreadStore(
                dataSource =
                    makeDataSource(
                        payloadStore = payloadStore,
                        repository = repository,
                        reader = ControllableDurableReader(frames = emptyMap()),
                        identity = { ownerA },
                    ),
                scope = scope,
            )
        seedStore.accept(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceUntilIdle()
        seedStore.clear()

        val probeDs =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = ControllableDurableReader(frames = emptyMap()),
                identity = { ownerA },
            )
        val provenance = assertNotNull(probeDs.captureReplyCacheProducerContext()).provenance
        val cachedEntry = assertNotNull(probeDs.cachedPayloadIfCurrent("channel-1", provenance))
        val richOrigin = RaftThreadMessageItem(id = "origin-meta", authorId = "u-a", authorName = "Owner A", body = "origin")
        payloadStore.put(
            serverId = "server-1",
            threadId = "channel-1",
            payload =
                cachedEntry.copy(
                    subtitle = "Ops channel",
                    originMessage = richOrigin,
                    originStatus = "ready",
                    channelArchived = false,
                ),
            ownerScopeId = provenance.threadPayloadOwnerScopeId.orEmpty(),
        )

        val islandReader =
            ControllableDurableReader(
                frames = emptyMap(),
                readDelayMillis = 25L,
                timelinePages = { part, channelId, anchor ->
                    if (
                        part == partition(ownerA) &&
                        channelId == "channel-1" &&
                        anchor is ThreadDurableTimelineAnchor.AroundMessageId &&
                        anchor.messageId == "v2-2"
                    ) {
                        durableTimelinePage(
                            channelId = "channel-1",
                            items = listOf("v2-1" to 1L to "old 1", "v2-2" to 2L to "target 2", "v2-3" to 3L to "old 3"),
                        )
                    } else {
                        null
                    }
                },
            )
        repository.messageContextResult = channelSnapshot("channel-1", "v2-1" to 1L, "v2-2" to 2L, "v2-3" to 3L)
        repository.messageContextDelayMillis = 400L

        // Case A: hot memory cache (`cached`) lacks older anchor v2-2; SQLite returns anchored island.
        val cachedDataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = islandReader,
                identity = { ownerA },
            )
        val cachedStore = SharedThreadStore(dataSource = cachedDataSource, scope = scope)
        cachedStore.dispatch(SharedThreadIntent.Start(channelRoute("channel-1").copy(focusedMessageId = "v2-2")))
        advanceTimeBy(30L)
        runCurrent()
        assertEquals("content", cachedStore.state.value.payload.status)
        assertEquals(listOf("v2-1", "v2-2", "v2-3"), cachedStore.state.value.payload.messages.map { it.id })
        assertEquals("Ops channel", cachedStore.state.value.payload.subtitle)
        assertEquals("origin-meta", cachedStore.state.value.payload.originMessage?.id)
        assertEquals(ThreadAccessResolution.Resolved, cachedStore.state.value.accessResolution)
        assertTrue(cachedStore.state.value.writeActionsEnabled)
        assertFalse(cachedStore.state.value.loadingIndicatorRevealed)
        assertEquals(1, islandReader.touchedScopes.size)
        cachedStore.clear()

        // Case B: superseded memory cache lacks anchor v2-2; SQLite returns anchored island (`durableSatisfiesMissingMemoryAnchor`).
        payloadStore.freshnessLedger.advance(
            serverId = "server-1",
            threadId = "channel-1",
            ownerScopeId = provenance.threadPayloadOwnerScopeId.orEmpty(),
            cause = "summary_advance",
        )
        val supersededStore = SharedThreadStore(dataSource = cachedDataSource, scope = scope)
        supersededStore.dispatch(SharedThreadIntent.Start(channelRoute("channel-1").copy(focusedMessageId = "v2-2")))
        advanceTimeBy(30L)
        runCurrent()
        assertEquals("content", supersededStore.state.value.payload.status)
        assertEquals(listOf("v2-1", "v2-2", "v2-3"), supersededStore.state.value.payload.messages.map { it.id })
        assertEquals("Ops channel", supersededStore.state.value.payload.subtitle)
        assertEquals("origin-meta", supersededStore.state.value.payload.originMessage?.id)
        assertEquals(ThreadAccessResolution.Resolved, supersededStore.state.value.accessResolution)
        assertTrue(supersededStore.state.value.writeActionsEnabled)
        assertFalse(supersededStore.state.value.loadingIndicatorRevealed)
        assertEquals(2, islandReader.touchedScopes.size)
        supersededStore.clear()

        // Case C: archived cached channel overlaid with SQLite anchored island must keep read-only
        // archived payload capabilities even when the incoming route defaults canSend=true.
        payloadStore.put(
            serverId = "server-1",
            threadId = "channel-1",
            payload =
                cachedEntry.copy(
                    subtitle = "Archived ops",
                    originMessage = richOrigin,
                    originStatus = "ready",
                    channelArchived = true,
                    canSend = false,
                    composer = cachedEntry.composer.copy(enabled = false),
                ),
            ownerScopeId = provenance.threadPayloadOwnerScopeId.orEmpty(),
        )
        val archivedStore = SharedThreadStore(dataSource = cachedDataSource, scope = scope)
        archivedStore.dispatch(SharedThreadIntent.Start(channelRoute("channel-1").copy(focusedMessageId = "v2-2")))
        advanceTimeBy(30L)
        runCurrent()
        val archivedPayload = archivedStore.state.value.payload
        assertEquals("content", archivedPayload.status)
        assertEquals(listOf("v2-1", "v2-2", "v2-3"), archivedPayload.messages.map { it.id })
        assertEquals("Archived ops", archivedPayload.subtitle)
        assertTrue(archivedPayload.channelArchived)
        assertTrue(threadFooterShowsArchived(archivedPayload))
        assertFalse(archivedPayload.channelTaskWritesShown())
        assertFalse(archivedPayload.canSend, "archived cached channel must not inherit route canSend=true")
        assertFalse(archivedPayload.composer.enabled, "archived cached channel composer must stay disabled")
        archivedStore.clear()
        scopeJob.cancelAndJoin()
    }

    @Test
    fun supersededMemoryWithReplyPreviewSeedAtSameSeqUpgradesToFullDurableIslandAndProtectsLiveRealtime() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val replyParent = ThreadDurableReplyParent(parentChannelId = "channel-1", rootMessageId = "p1")
        val replyRoute =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-reply-thread-1",
                title = "Thread",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-1",
                streamChannelId = "thread-1",
                parentChannelId = "channel-1",
                parentMessageId = "p1",
                status = "loading",
                canSend = true,
            )
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("thread-1", "r1" to 1L, "r2" to 2L),
            )
        val seedStore =
            SharedThreadStore(
                dataSource =
                    makeDataSource(
                        payloadStore = payloadStore,
                        repository = repository,
                        reader = ControllableDurableReader(frames = emptyMap()),
                        identity = { ownerA },
                    ),
                scope = scope,
            )
        seedStore.accept(SharedThreadIntent.Start(replyRoute))
        advanceUntilIdle()
        seedStore.clear()

        val probeDs =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = ControllableDurableReader(frames = emptyMap()),
                identity = { ownerA },
            )
        val provenance = assertNotNull(probeDs.captureReplyCacheProducerContext()).provenance
        val cachedEntry = assertNotNull(probeDs.cachedPayloadIfCurrent("thread-1", provenance))
        val parentOrigin = RaftThreadMessageItem(id = "p1", authorId = "u-a", authorName = "Owner A", body = "parent post")
        payloadStore.put(
            serverId = "server-1",
            threadId = "thread-1",
            payload = cachedEntry.copy(originMessage = parentOrigin, originStatus = "ready"),
            ownerScopeId = provenance.threadPayloadOwnerScopeId.orEmpty(),
        )
        payloadStore.freshnessLedger.advance(
            serverId = "server-1",
            threadId = "thread-1",
            ownerScopeId = provenance.threadPayloadOwnerScopeId.orEmpty(),
            cause = "reply_arrived",
        )

        val threadActivitySource =
            activitySeedSource(
                InboxItem(
                    key = "thread-row-1",
                    kind = InboxItemKind.Thread,
                    channelId = ChannelId("channel-1"),
                    channelName = "general",
                    threadChannelId = ChannelId("thread-1"),
                    parentChannelId = ChannelId("channel-1"),
                    parentMessageId = MessageId("p1"),
                    latestActivityMessageId = MessageId("r3"),
                    latestActivityPreview = null,
                    latestActivityAt = "2026-05-19T16:05:00Z",
                    latestActivitySenderId = "u-a",
                    latestActivitySenderName = "Owner A",
                    latestActivitySenderKind = MemberKind.Human,
                    latestReplies =
                        listOf(
                            ReplyPreview(
                                messageId = "r3",
                                seq = 3L,
                                content = "cut reply preview…",
                                truncated = true,
                                senderId = "u-a",
                                senderType = "user",
                                senderName = "Owner A",
                                createdAt = "2026-05-19T16:05:00Z",
                            ),
                        ),
                ),
            )
        val durablePage =
            durableTimelinePage(
                channelId = "thread-1",
                scopeKind = "thread",
                replyParent = replyParent,
                items = listOf("r1" to 1L to "durable r1", "r2" to 2L to "durable r2", "r3" to 3L to "durable full r3"),
            )

        // Subcase A: ReplyPreview seed already carries seq=3 (equal to diskSeq=3); SQLite still upgrades to full durable r3.
        val upgradeReader =
            ControllableDurableReader(
                frames = emptyMap(),
                timelinePages = { _, channelId, _ -> durablePage.takeIf { channelId == "thread-1" } },
            )
        upgradeReader.parkNextRead()
        repository.messageContextResult = channelSnapshot("thread-1", "r1" to 1L, "r2" to 2L, "r3" to 3L)
        repository.messageContextDelayMillis = 400L

        val upgradeDs =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = upgradeReader,
                identity = { ownerA },
                activitySeed = { threadActivitySource },
            )
        val upgradeStore = SharedThreadStore(dataSource = upgradeDs, scope = scope)
        upgradeStore.dispatch(SharedThreadIntent.Start(replyRoute.copy(focusedMessageId = "r3")))

        // Frame 1: superseded memory + truncated ReplyPreview r3 (seq=3).
        assertEquals(listOf("r1", "r2", "r3"), upgradeStore.state.value.payload.messages.map { it.id })
        assertTrue(upgradeStore.state.value.payload.messages.last().activityPreviewTruncated)
        assertEquals("p1", upgradeStore.state.value.payload.originMessage?.id)

        // SQLite returns diskSeq=3 (== liveSeq=3): upgrades truncated seed to full durable envelope while keeping originMessage.
        upgradeReader.releaseRead.complete(Unit)
        advanceTimeBy(10L)
        runCurrent()
        assertEquals(listOf("r1", "r2", "r3"), upgradeStore.state.value.payload.messages.map { it.id })
        assertEquals("durable full r3", upgradeStore.state.value.payload.messages.last().body)
        assertFalse(upgradeStore.state.value.payload.messages.last().activityPreviewTruncated)
        assertEquals("p1", upgradeStore.state.value.payload.originMessage?.id)
        upgradeStore.clear()

        // Subcase B: Live realtime r4 (seq=4) arrives while SQLite (diskSeq=3) is parked -> disk must NOT clobber r4,
        // and still enriches truncated r3 body from disk.
        val realtimeReader =
            ControllableDurableReader(
                frames = emptyMap(),
                timelinePages = { _, channelId, _ -> durablePage.takeIf { channelId == "thread-1" } },
            )
        realtimeReader.parkNextRead()
        val realtimeDs =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = realtimeReader,
                identity = { ownerA },
                activitySeed = { threadActivitySource },
            )
        val realtimeStore = SharedThreadStore(dataSource = realtimeDs, scope = scope)
        realtimeStore.dispatch(SharedThreadIntent.Start(replyRoute.copy(focusedMessageId = "r3")))
        assertEquals(listOf("r1", "r2", "r3"), realtimeStore.state.value.payload.messages.map { it.id })
        assertTrue(realtimeStore.state.value.payload.messages.last().activityPreviewTruncated)

        realtimeStore.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        threadId = "thread-1",
                        channelId = "thread-1",
                        id = "r4",
                        seq = 4L,
                        body = "live realtime r4",
                    ),
                ),
            ),
        )
        assertEquals(listOf("r1", "r2", "r3", "r4"), realtimeStore.state.value.payload.messages.map { it.id })

        realtimeReader.releaseRead.complete(Unit)
        advanceTimeBy(10L)
        runCurrent()
        assertEquals(
            listOf("r1", "r2", "r3", "r4"),
            realtimeStore.state.value.payload.messages.map { it.id },
            "older SQLite diskSeq=3 must never clobber newer realtime r4",
        )
        val r3AfterDisk = realtimeStore.state.value.payload.messages.first { it.id == "r3" }
        assertEquals("durable full r3", r3AfterDisk.body)
        assertFalse(r3AfterDisk.activityPreviewTruncated)
        realtimeStore.clear()
        scopeJob.cancelAndJoin()
    }

    @Test
    fun accountSwitchDuringParkedSqliteAfterCachedAnchorSeedEvictsImmediately() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        var activeIdentity = ownerA
        val repository =
            FakeConversationRepository(
                threadResult = channelSnapshot("channel-1", "a1" to 1L),
            )
        val seedStore =
            SharedThreadStore(
                dataSource =
                    makeDataSource(
                        payloadStore = payloadStore,
                        repository = repository,
                        reader = ControllableDurableReader(frames = emptyMap()),
                        identity = { activeIdentity },
                    ),
                scope = scope,
            )
        seedStore.accept(SharedThreadIntent.Start(channelRoute("channel-1")))
        advanceUntilIdle()
        seedStore.clear()

        val ownerASeed =
            activitySeedSource(
                channelActivityRow(
                    channelId = "channel-1",
                    messageId = "a2",
                    preview = "owner A anchor",
                    at = "2026-05-19T16:05:00Z",
                ),
            )
        val reader = ControllableDurableReader(frames = emptyMap(), timelinePages = { _, _, _ -> null })
        reader.parkNextRead()
        repository.messageContextResult = channelSnapshot("channel-1", "b1" to 1L)
        repository.messageContextDelayMillis = 400L

        val dataSource =
            makeDataSource(
                payloadStore = payloadStore,
                repository = repository,
                reader = reader,
                identity = { activeIdentity },
                activitySeed = { ownerASeed },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.dispatch(SharedThreadIntent.Start(channelRoute("channel-1").copy(focusedMessageId = "a2")))
        assertEquals(listOf("a1", "a2"), store.state.value.payload.messages.map { it.id })

        // Switch account while SQLite anchored lookup is parked -> Owner A's frame must be evicted immediately.
        activeIdentity = ownerB
        assertNotNull(dataSource.captureReplyCacheProducerContext())
        reader.releaseRead.complete(Unit)
        runCurrent()
        assertEquals("loading", store.state.value.payload.status)
        assertTrue(store.state.value.payload.messages.isEmpty())
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        assertFalse(store.state.value.writeActionsEnabled)
        scopeJob.cancelAndJoin()
    }

    private class ControllableDurableReader(
        private val frames: Map<DurablePartitionKey, ThreadDurableLastKnownFrame>,
        private val readDelayMillis: Long = 0L,
        private val timelinePages: ((DurablePartitionKey, SyncScopeId, ThreadDurableTimelineAnchor) -> ThreadDurableTimelinePage?)? = null,
    ) : ThreadDurableLastKnownReader {
        private var parkRead = false
        val touchedScopes = mutableListOf<SyncScopeKey>()
        var readStarted = CompletableDeferred<Unit>()
            private set
        var releaseRead = CompletableDeferred<Unit>()
            private set

        fun parkNextRead() {
            parkRead = true
            readStarted = CompletableDeferred()
            releaseRead = CompletableDeferred()
        }

        private suspend fun awaitReadGate() {
            if (parkRead) {
                parkRead = false
                readStarted.complete(Unit)
                releaseRead.await()
            } else if (readDelayMillis > 0L) {
                delay(readDelayMillis)
            }
        }

        override suspend fun read(
            partition: DurablePartitionKey,
            channelId: SyncScopeId,
        ): ThreadDurableLastKnownFrame? {
            awaitReadGate()
            val frame = frames[partition] ?: return null
            return frame.takeIf { it.scope.scopeId == channelId }
        }

        override suspend fun readTimeline(
            partition: DurablePartitionKey,
            channelId: SyncScopeId,
            anchor: ThreadDurableTimelineAnchor,
            touchUserAccess: Boolean,
        ): ThreadDurableTimelinePage? {
            val provider = timelinePages ?: return null
            awaitReadGate()
            return provider(partition, channelId, anchor)
        }

        override suspend fun readReplyThreadTimeline(
            partition: DurablePartitionKey,
            threadChannelId: SyncScopeId,
            replyParent: ThreadDurableReplyParent,
            anchor: ThreadDurableTimelineAnchor,
            touchUserAccess: Boolean,
        ): ThreadDurableTimelinePage? {
            val provider = timelinePages ?: return null
            awaitReadGate()
            return provider(partition, threadChannelId, anchor)
        }

        override suspend fun touchUserAccess(scope: SyncScopeKey) {
            touchedScopes += scope
        }
    }

    private fun activitySeedSource(
        vararg items: InboxItem,
        serverId: String = "server-1",
        userId: String = "u-a",
    ): ThreadActivityOpenSeedSource =
        ThreadActivityOpenSeedSource(
            ownerServerId = serverId,
            ownerUserId = userId,
            items = items.toList(),
        )

    private fun channelActivityRow(
        channelId: String,
        messageId: String,
        preview: String,
        at: String,
        kind: InboxItemKind = InboxItemKind.Channel,
    ): InboxItem =
        InboxItem(
            key = "$kind-$channelId-$messageId",
            kind = kind,
            channelId = ChannelId(channelId),
            channelName = channelId,
            lastMessageId = MessageId(messageId),
            lastMessagePreview = preview,
            lastMessageAt = at,
            lastMessageSenderId = "u-a",
            lastMessageSenderName = "Owner A",
            lastMessageSenderKind = MemberKind.Human,
        )

    private fun durableTimelinePage(
        channelId: String,
        items: List<Pair<Pair<String, Long>, String>>,
        scopeKind: String = "channel",
        replyParent: ThreadDurableReplyParent? = null,
    ): ThreadDurableTimelinePage {
        val sorted = items.sortedBy { it.first.second }
        val envelopes =
            sorted.map { (idAndSeq, body) ->
                val (id, seq) = idAndSeq
                CanonicalMessageEnvelopeV2(
                    fact =
                        CanonicalMessageNodeV2(
                            id = id,
                            seq = seq,
                            senderType = "user",
                            senderId = "u-a",
                            content = body,
                            messageType = "chat",
                            createdAt = "2026-05-19T16:05:${seq.toString().padStart(2, '0')}Z",
                            senderName = "Owner A",
                        ),
                    context =
                        if (replyParent != null) {
                            CanonicalConversationContextV2.Thread(
                                replyScope = ReplyScopeRefV2("server-1", channelId),
                                parentTopLevelScope = TopLevelScopeRefV2.Channel("server-1", replyParent.parentChannelId),
                                rootMessageRef = MessageRefV2("server-1", replyParent.rootMessageId),
                            )
                        } else {
                            CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-1", channelId))
                        },
                    replyMembership =
                        replyParent?.let {
                            ReplyMembershipV2(
                                root = MessageRefV2("server-1", it.rootMessageId),
                                parentScope = ReplyScopeRefV2("server-1", channelId),
                            )
                        },
                )
            }
        return ThreadDurableTimelinePage(
            scope = SyncScopeKey(partition(ownerA), channelId, scopeKind),
            envelopes = envelopes,
            cursor =
                PersistedCursor(
                    latestSeq = sorted.maxOf { it.first.second },
                    oldestLoadedSeq = sorted.minOf { it.first.second },
                    newestLoadedSeq = sorted.maxOf { it.first.second },
                    hasLoadedOldest = true,
                    hasLoadedNewest = true,
                    epoch = "epoch-v2",
                    parentScopeKind = if (replyParent != null) "channel" else null,
                    parentScopeId = replyParent?.parentChannelId,
                    rootMessageId = replyParent?.rootMessageId,
                ),
            segments = emptyList(),
            hasOlderStored = false,
            hasNewerStored = false,
            reachesOldest = true,
            reachesHead = true,
            insideProvenSegment = true,
        )
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
            durableDbDispatcher = backgroundScope.testDispatcher(),
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
