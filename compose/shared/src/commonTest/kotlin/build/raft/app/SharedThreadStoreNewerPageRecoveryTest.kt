package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageRangeResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.model.MessageNew
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadPaging
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadNewerPageLoadOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
