package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftFreshnessEvent
import build.raft.app.data.RaftFreshnessTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SharedThreadStoreFreshnessTest {
    @Test
    fun failedCatchUpDoesNotConsumeCoalesceWindowAndRetriesImmediatelyOnNetworkRestored() = runTest {
        var nowMs = 1_000L
        val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
        val api = FakeThreadApi(bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false))
        api.catchUpResponseCodes.addLast(500)
        api.catchUpBodyTexts.addLast("{}")
        api.catchUpResponseCodes.addLast(200)
        api.catchUpBodyTexts.addLast(pageResponse(Triple("m10", 10L, "current body"), Triple("m12", 12L, "gap body")))

        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = backgroundScope,
                freshness = freshness,
                freshnessNowMillis = { nowMs },
                freshnessLiveConnectionToken = { null },
            )
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        val triggered = mutableListOf<Pair<RaftFreshnessTrigger, String>>()
        store.setFreshnessObserver(
            serverId = "server-1",
            active = true,
            onTriggered = { trigger, serverId -> triggered += trigger to serverId },
        )
        runCurrent()

        // First NetworkRestored fails (500): must NOT consume the 10 s coalesce window.
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        runCurrent()
        assertEquals(1, api.catchUpLimits.size)
        assertEquals(listOf("m10"), store.state.value.payload.messages.map { it.id })

        // Second NetworkRestored 1 s later retries immediately and merges m12.
        nowMs = 2_000L
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        runCurrent()
        assertEquals(2, api.catchUpLimits.size)
        assertEquals(listOf("m10", "m12"), store.state.value.payload.messages.map { it.id })

        // Third NetworkRestored 1 s after success is coalesced inside the 10 s window.
        nowMs = 3_000L
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        runCurrent()
        assertEquals(2, api.catchUpLimits.size)
        assertEquals(
            listOf(
                RaftFreshnessTrigger.NetworkRestored to "server-1",
                RaftFreshnessTrigger.NetworkRestored to "server-1",
            ),
            triggered,
        )
        store.clear()
    }

    @Test
    fun inactiveRouteHoldsReconnectAndDrainsWhenRouteBecomesActive() = runTest {
        val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
        val api = FakeThreadApi(bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false))
        api.catchUpResponseCodes.addLast(200)
        api.catchUpBodyTexts.addLast(pageResponse(Triple("m10", 10L, "current body"), Triple("m12", 12L, "gap body")))

        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = backgroundScope,
                freshness = freshness,
                freshnessNowMillis = { 1_000L },
                freshnessLiveConnectionToken = { null },
            )
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        val triggered = mutableListOf<Pair<RaftFreshnessTrigger, String>>()
        store.setFreshnessObserver(
            serverId = "server-1",
            active = false,
            onTriggered = { trigger, serverId -> triggered += trigger to serverId },
        )
        runCurrent()

        // Reconnect while route is hidden is held.
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = "server-1", transportEpoch = 1L))
        runCurrent()
        assertEquals(0, api.catchUpLimits.size)
        assertEquals(emptyList(), triggered)

        // Becoming active drains the held Reconnect and notifies callback (for resumeGate + room rejoin).
        store.setFreshnessObserver(
            serverId = "server-1",
            active = true,
            onTriggered = { trigger, serverId -> triggered += trigger to serverId },
        )
        runCurrent()
        assertEquals(1, api.catchUpLimits.size)
        assertEquals(listOf("m10", "m12"), store.state.value.payload.messages.map { it.id })
        assertEquals(listOf(RaftFreshnessTrigger.Reconnect to "server-1"), triggered)
        store.clear()
    }
}
