package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.emptyMessagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageRangeResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.ParkableReadSessionStore
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY
import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.model.MessageNew
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class SharedThreadStoreNewerPageRecoveryTest {
    @Test
    fun threadPageSizeAcceptsTwentyThirtyAndFiftyButRejectsUnsafeValues() {
        assertEquals(20, SharedThreadPaging.resolve(20))
        assertEquals(30, SharedThreadPaging.resolve(30))
        assertEquals(50, SharedThreadPaging.resolve(50))
        assertEquals(30, SharedThreadPaging.resolve(null))
        assertEquals(30, SharedThreadPaging.resolve(0))
        assertEquals(30, SharedThreadPaging.resolve(9))
        assertEquals(30, SharedThreadPaging.resolve(51))
    }
    @Test
    fun threadPageSizeVariantRequiresEnabledFlagAndSafeNumericRange() {
        val enabledTwenty =
            ServerFeatureFlagSnapshot(
                serverId = ServerId("server-1"),
                resolved = true,
                values = mapOf(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY to true),
                variants = mapOf(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY to "20")
            )
        val disabledFifty =
            enabledTwenty.copy(
                values = mapOf(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY to false),
                variants = mapOf(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY to "50")
            )
        val malformed =
            enabledTwenty.copy(variants = mapOf(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY to "fast"))

        assertEquals(20, SharedThreadPaging.resolve(enabledTwenty.mobileThreadPageSize()))
        assertEquals(30, SharedThreadPaging.resolve(disabledFifty.mobileThreadPageSize()))
        assertEquals(30, SharedThreadPaging.resolve(malformed.mobileThreadPageSize()))
    }
    @Test
    fun loadNewerMessagesMergesAfterVisiblePayload() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = messagesResponse("newer body", id = "m12", seq = 12, historyLimited = false)
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        val outcome = store.loadNewer()

        val payload = store.state.value.payload
        assertEquals(ThreadNewerPageLoadOutcome.Applied(listOf("m12")), outcome)
        assertEquals(listOf("current body", "newer body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.afterSeqs)
        assertEquals(0, api.catchUpCalls)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun realtimeAppendDuringFailedNewerPageDoesNotBecomeAppliedPageOutcome() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerResponseCode = 500
            )
        api.afterMessagesStarted = CompletableDeferred()
        api.releaseAfterMessages = CompletableDeferred()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        val pageOutcome = async { store.loadNewer() }
        api.afterMessagesStarted?.await()
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m11", seq = 11, body = "realtime body"))
            )
        )
        api.releaseAfterMessages?.complete(Unit)

        assertEquals(ThreadNewerPageLoadOutcome.Failed, pageOutcome.await())
        assertEquals(listOf("m10", "m11"), store.state.value.payload.messages.map { it.id })
        assertEquals("error", store.state.value.payload.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun emptyNewerPageReturnsEmptyOutcomeWithoutAppliedIdentities() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = emptyMessagesResponse()
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        assertEquals(ThreadNewerPageLoadOutcome.Empty, store.loadNewer())
        assertEquals(listOf("m10"), store.state.value.payload.messages.map { it.id })
        assertEquals(false, store.state.value.payload.canLoadMoreAfter)
        scope.cancel()
    }
    @Test
    fun deduplicatedNewerPageReturnsEmptyOutcomeWithoutAppliedIdentities() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val currentResponse = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false)
        val api =
            FakeThreadApi(
                bodyText = currentResponse,
                newerBodyText = currentResponse
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        assertEquals(ThreadNewerPageLoadOutcome.Empty, store.loadNewer())
        assertEquals(listOf("m10"), store.state.value.payload.messages.map { it.id })
        scope.cancel()
    }
    @Test
    fun realtimeGapDuringManualLoadNewerHandsOffToRecoveryWithoutUsingLoadingAsOwner() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = messagesResponse("manual page", id = "m11", seq = 11, historyLimited = false),
            )
        api.afterMessagesStarted = CompletableDeferred()
        api.releaseAfterMessages = CompletableDeferred()
        api.catchUpBodyTexts.addLast(pageRangeResponse(11L..100L))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        val pagination = launch { store.accept(SharedThreadIntent.LoadNewer) }
        api.afterMessagesStarted?.await()
        assertEquals("loading", store.state.value.payload.newerMessagesStatus)
        assertEquals("loading", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)

        val realtime = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m100", seq = 100, body = "future during manual pagination")),
                ),
            )
        }
        realtime.join()
        api.releaseAfterMessages?.complete(Unit)
        pagination.join()

        assertEquals(listOf(10L), api.afterSeqs)
        assertEquals(
            1,
            api.catchUpCalls,
            "manual pagination must hand its pending gap to ONE real catch-up after the page commits",
        )
        assertEquals((10L..100L).toList(), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals((10L..100L).toList(), dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq })
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun realtimeGapRecoverySyncsFromVisiblePayloadSeqWhenPagingGateIsClosed() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageResponse(Triple("m10", 10L, "current body"), Triple("m12", 12L, "gap body"))
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 90))
        gapCatchUps.joinAll()

        val payload = store.state.value.payload
        assertEquals(listOf("current body", "gap body"), payload.messages.map { it.body })
        assertEquals(1, api.catchUpCalls)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun refreshLatestAfterVisibleMaxSeqSyncsFromVisiblePayloadSeq() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageResponse(Triple("m10", 10L, "current body"), Triple("m11", 11L, "resumed body"))
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)

        val payload = store.state.value.payload
        assertEquals(listOf("current body", "resumed body"), payload.messages.map { it.body })
        assertEquals(1, api.catchUpCalls)
        assertEquals(emptyList(), api.afterSeqs)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun channelRealtimeMessageNewGapSyncsFromVisibleTailWhenPagingGateIsClosed() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageResponse(Triple("m10", 10L, "current body"), Triple("m11", 11L, "gap body"))
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m12", seq = 12, body = "latest across gap"))
            )
        )
        gapCatchUps.joinAll()

        val payload = store.state.value.payload
        assertEquals(listOf("current body", "gap body"), payload.messages.map { it.body })
        assertEquals(1, api.catchUpCalls)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun currentRealtimeEventDuringGapRecoveryIsAbsorbedWithoutStartingDuplicateSync() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageResponse(Triple("m10", 10L, "current body"), Triple("m12", 12L, "recovered body")),
            )
        api.catchUpStarted = CompletableDeferred()
        api.releaseCatchUp = CompletableDeferred()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val first = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "gap trigger")),
                ),
            )
        }
        api.catchUpStarted?.await()
        assertEquals("loading", store.state.value.payload.newerMessagesStatus)
        assertEquals("loading", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)

        val second = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m13", seq = 13, body = "arrived during recovery")),
                ),
            )
        }
        second.join()
        assertEquals(
            1,
            api.catchUpCalls,
            "a current event must join the in-flight recovery instead of starting a second sync",
        )
        assertEquals("loading", store.state.value.payload.newerMessagesStatus)

        api.releaseCatchUp?.complete(Unit)
        first.join()
        gapCatchUps.joinAll()

        assertEquals(
            1,
            api.catchUpCalls,
            "the absorbed event must not start a delayed duplicate sync after recovery completes",
        )
        assertEquals(1, store.state.value.payload.messages.count { it.id == "m12" })
        assertEquals(
            1,
            store.state.value.payload.messages.count { it.id == "m13" },
            "the event that arrived during recovery must land exactly once rather than being dropped",
        )
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals(
            listOf(10L, 12L, 13L),
            dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq },
        )
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun realtimeGapAfterSuccessfulTerminalStartsFreshRecoveryWithoutDuplicateOwner() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        api.catchUpBodyTexts.addLast(pageRangeResponse(10L..12L))
        api.catchUpBodyTexts.addLast(pageRangeResponse(10L..14L))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m12", seq = 12, body = "first gap")),
            ),
        )
        gapCatchUps.joinAll()
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m14", seq = 14, body = "terminal-first successor")),
            ),
        )
        gapCatchUps.joinAll()

        assertEquals(
            2,
            api.catchUpCalls,
            "once the first terminal clears ownership, the next gap must start one fresh recovery",
        )
        assertEquals((10L..14L).toList(), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals((10L..14L).toList(), dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq })
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun duplicateAndOutOfOrderRealtimeEventsDoNotExtendActiveRecoveryTarget() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        api.catchUpBodyTexts.addLast(pageRangeResponse(10L..12L))
        api.catchUpStarted = CompletableDeferred()
        api.releaseCatchUp = CompletableDeferred()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val recovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "gap trigger")),
                ),
            )
        }
        api.catchUpStarted?.await()

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m13", seq = 13, body = "current duplicate target")),
            ),
        )
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m13", seq = 13, body = "duplicate delivery")),
            ),
        )
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m10", seq = 10, body = "out of order duplicate")),
            ),
        )

        api.releaseCatchUp?.complete(Unit)
        recovery.join()
        gapCatchUps.joinAll()

        assertEquals(
            1,
            api.catchUpCalls,
            "duplicate or lower realtime sequences must not bump target revision into another fetch",
        )
        assertEquals(1, store.state.value.payload.messages.count { it.id == "m10" })
        assertEquals(1, store.state.value.payload.messages.count { it.id == "m13" })
        assertEquals(listOf(10L, 11L, 12L, 13L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals(
            listOf(10L, 11L, 12L, 13L),
            dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq },
        )
        scope.cancel()
    }
    @Test
    fun nonGapRealtimeEventSuspendedBeforeLinearizationMergesIntoLatestRecoveryPayload() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val sessionStore = ParkableReadSessionStore(sampleSession())
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageResponse(Triple("m10", 10L, "current body"), Triple("m12", 12L, "recovered body")),
            )
        api.catchUpStarted = CompletableDeferred()
        api.releaseCatchUp = CompletableDeferred()
        val dataSource = testSharedThreadDataSource(storeScope = backgroundScope, networkApi = api, sessionStore = sessionStore, cacheEnvironmentIdProvider = { TestThreadEnvironmentId })
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val recovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "gap trigger")),
                ),
            )
        }
        api.catchUpStarted?.await()

        sessionStore.parkNextRead()
        val nonGap = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m11", seq = 11, body = "non-gap while recovery lands")),
                ),
            )
        }
        sessionStore.nextReadStarted.await()

        api.releaseCatchUp?.complete(Unit)
        recovery.join()
        gapCatchUps.joinAll()
        assertEquals(listOf(10L, 12L), store.state.value.payload.messages.mapNotNull { it.seq })

        sessionStore.releaseNextRead.complete(Unit)
        nonGap.join()

        assertEquals(
            listOf(10L, 11L, 12L),
            store.state.value.payload.messages.mapNotNull { it.seq },
            "a non-gap event must reduce against the latest recovery payload, not overwrite it from its pre-read snapshot",
        )
        assertEquals(1, store.state.value.payload.messages.count { it.id == "m11" })
        assertEquals(1, store.state.value.payload.messages.count { it.id == "m12" })
        assertEquals(1, api.catchUpCalls)
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals(
            listOf(10L, 11L, 12L),
            dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq },
        )
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun futureRealtimeEventDuringCatchUpLandsOnceWithoutASecondRequest() = runTest {
        // Task #168 step 1: the catch-up is ONE latest-page request. A live event that arrives while
        // it is in flight is folded directly and survives the page merge; it never schedules a
        // follow-up request ("do not loop to fully catch up").
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        api.catchUpBodyTexts.addLast(pageRangeResponse(10L..99L))
        api.catchUpStarted = CompletableDeferred()
        api.releaseCatchUp = CompletableDeferred()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val recovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m61", seq = 61, body = "gap trigger")),
                ),
            )
        }
        api.catchUpStarted?.await()

        val future = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m100", seq = 100, body = "future during recovery")),
                ),
            )
        }
        future.join()
        assertEquals(1, api.catchUpCalls)

        api.releaseCatchUp?.complete(Unit)
        recovery.join()
        gapCatchUps.joinAll()

        assertEquals(1, api.catchUpCalls, "a target that advances mid-flight must not loop the catch-up")
        assertEquals(0, api.messageSyncCalls)
        assertEquals(
            (10L..100L).toList(),
            store.state.value.payload.messages.mapNotNull { it.seq },
            "the page and the future realtime event must both survive exactly once",
        )
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals((10L..100L).toList(), dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq })
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun newGenerationRecoveryReplacesParkedOldGenerationBeforeOldTerminal() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val oldStarted = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("old baseline", id = "m10", seq = 10, historyLimited = false),
            )
        api.catchUpBodyTexts.addLast(pageRangeResponse(10L..12L))
        api.catchUpBodyTexts.addLast(pageRangeResponse(20L..22L))
        api.catchUpStartedSignals.addLast(oldStarted)
        api.catchUpStartedSignals.addLast(newStarted)
        api.catchUpReleases.addLast(releaseOld)
        api.catchUpReleases.addLast(releaseNew)
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val oldRecovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "old route gap")),
                ),
            )
        }
        oldStarted.await()

        api.bodyText = messagesResponse("new baseline", id = "m20", seq = 20, historyLimited = false)
        store.accept(SharedThreadIntent.Refresh)
        assertEquals(listOf(20L), store.state.value.payload.messages.mapNotNull { it.seq })

        val newRecovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m22", seq = 22, body = "new route gap")),
                ),
            )
        }
        newStarted.await()
        assertEquals(
            2,
            api.catchUpCalls,
            "the new route generation must start its own recovery before the parked old generation terminates",
        )

        releaseNew.complete(Unit)
        newRecovery.join()
        gapCatchUps.join(1)
        assertEquals(listOf(20L, 21L, 22L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)

        releaseOld.complete(Unit)
        oldRecovery.join()
        gapCatchUps.joinAll()
        assertEquals(
            listOf(20L, 21L, 22L),
            store.state.value.payload.messages.mapNotNull { it.seq },
            "the old route terminal must neither clear nor overwrite the successor recovery state",
        )
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals(
            listOf(20L, 21L, 22L),
            dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq },
        )
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun newRouteRecoveryReplacesParkedOldRouteBeforeOldTerminal() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val oldStarted = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("old baseline", id = "m10", seq = 10, historyLimited = false),
            )
        api.bodyTextByThreadId["channel-2"] =
            messagesResponse(
                "new route baseline",
                id = "m20",
                seq = 20,
                historyLimited = false,
                channelId = "channel-2",
            )
        api.catchUpBodyTexts.addLast(pageRangeResponse(10L..12L))
        api.catchUpBodyTexts.addLast(pageRangeResponse(20L..22L, channelId = "channel-2"))
        api.catchUpStartedSignals.addLast(oldStarted)
        api.catchUpStartedSignals.addLast(newStarted)
        api.catchUpReleases.addLast(releaseOld)
        api.catchUpReleases.addLast(releaseNew)
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val oldRecovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "old route gap")),
                ),
            )
        }
        oldStarted.await()

        val newRoute =
            basePayload().copy(
                instanceId = "shared-shell-thread-channel-2",
                title = "channel-2",
                sourceThreadId = "channel-2",
                streamChannelId = "channel-2",
                canLoadMoreAfter = false,
            )
        store.accept(SharedThreadIntent.Start(newRoute))
        assertEquals(listOf(20L), store.state.value.payload.messages.mapNotNull { it.seq })

        val newRecovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(
                        message(
                            threadId = "channel-2",
                            id = "m22",
                            seq = 22,
                            body = "new route gap",
                        ),
                    ),
                ),
            )
        }
        newStarted.await()
        assertEquals(2, api.catchUpCalls)

        releaseNew.complete(Unit)
        newRecovery.join()
        gapCatchUps.join(1)
        assertEquals(listOf(20L, 21L, 22L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)

        releaseOld.complete(Unit)
        oldRecovery.join()
        gapCatchUps.joinAll()
        assertEquals(listOf(20L, 21L, 22L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals(
            listOf(20L, 21L, 22L),
            dataSource.cachedPayload("channel-2")?.messages?.mapNotNull { it.seq },
        )
        assertEquals("idle", dataSource.cachedPayload("channel-2")?.newerMessagesStatus)
        scope.cancel()
    }
}
