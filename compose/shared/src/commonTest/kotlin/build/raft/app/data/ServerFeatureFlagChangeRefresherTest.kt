package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ServerFeaturesChanged
import build.raft.app.model.ServerId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * `server:features-changed` (botiverse/slock feat/workspace-artifacts 542e922d6,
 * `packages/shared/src/featuresChanged.ts`): jitter, coalescing, reconnect refetch, and a failed
 * refetch keeping the values. Virtual time only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerFeatureFlagChangeRefresherTest {
    private val server = ServerId("11111111-1111-4111-8111-111111111111")

    @Test
    fun mapsTheUpstreamPayload() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.ServerFeaturesChanged,
                """{"serverId":"${server.value}","keys":["workspace_artifacts_v0","read_receipts_v0"]}""",
            )
        assertEquals(ServerFeaturesChanged(server, listOf("workspace_artifacts_v0", "read_receipts_v0")), event)
        assertEquals("server:features-changed", RealtimeSocketEvents.ServerFeaturesChanged)
        assertTrue(RealtimeSocketEvents.ServerFeaturesChanged in RealtimeSocketEvents.ServerWireEvents)
        assertNull(RealtimeEventMapper().map(RealtimeSocketEvents.ServerFeaturesChanged, """{"keys":["x"]}"""))
    }

    @Test
    fun jitterStaysWithinZeroToTenSeconds() {
        val random = Random(7)
        val samples = List(2_000) { serverFeaturesChangedJitterMillis(random) }
        assertTrue(samples.all { it in 0L..SERVER_FEATURES_CHANGED_MAX_JITTER_MILLIS }, "samples out of 0..10s")
        // Spread over the window, not a constant.
        assertTrue(samples.min() < 1_000L && samples.max() > 9_000L, "min=${samples.min()} max=${samples.max()}")
    }

    @Test
    fun refetchWaitsForTheJitterThenFetchesOnce() = runTest {
        val fetched = mutableListOf<Pair<ServerId, Long>>()
        val refresher =
            ServerFeatureFlagChangeRefresher(
                scope = backgroundScope,
                refetch = { fetched += it to testScheduler.currentTime },
                jitterMillis = { 7_000L },
            )
        refresher.onFeaturesChanged(server, listOf("workspace_artifacts_v0"))
        advanceTimeBy(6_999L)
        runCurrent()
        assertEquals(emptyList(), fetched)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(server to 7_000L), fetched)
    }

    @Test
    fun jitterAboveTheMaximumIsClampedToTenSeconds() = runTest {
        val fetchedAt = mutableListOf<Long>()
        val refresher =
            ServerFeatureFlagChangeRefresher(backgroundScope, refetch = { fetchedAt += testScheduler.currentTime }, jitterMillis = { 60_000L })
        refresher.onFeaturesChanged(server)
        advanceTimeBy(SERVER_FEATURES_CHANGED_MAX_JITTER_MILLIS + 1)
        assertEquals(listOf(SERVER_FEATURES_CHANGED_MAX_JITTER_MILLIS), fetchedAt)
    }

    @Test
    fun noticesWhileWaitingCoalesceAndOneDuringTheFetchAsksOnceMore() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val refresher =
            ServerFeatureFlagChangeRefresher(
                scope = backgroundScope,
                refetch = {
                    calls += 1
                    if (calls == 1) gate.await()
                },
                jitterMillis = { 1_000L },
            )
        repeat(5) { refresher.onFeaturesChanged(server, listOf("k$it")) }
        refresher.onReconnected(server)
        advanceTimeBy(1_001L)
        assertEquals(1, calls, "six notices in the window: one refetch")
        // A notice while that refetch is on its way: its answer may predate the change.
        refresher.onFeaturesChanged(server)
        refresher.onFeaturesChanged(server)
        gate.complete(Unit)
        advanceTimeBy(1_001L)
        assertEquals(2, calls, "one more refetch after the in-flight one, however many notices")
        advanceTimeBy(30_000L)
        assertEquals(2, calls)
        // Settled: the next notice starts a new round.
        refresher.onFeaturesChanged(server)
        advanceTimeBy(1_001L)
        assertEquals(3, calls)
    }

    @Test
    fun everyReconnectRefetchesAndServersAreIndependent() = runTest {
        val fetched = mutableListOf<String>()
        val other = ServerId("22222222-2222-4222-8222-222222222222")
        val refresher = ServerFeatureFlagChangeRefresher(backgroundScope, refetch = { fetched += it.value }, jitterMillis = { 500L })
        refresher.onReconnected(server)
        refresher.onFeaturesChanged(other)
        advanceTimeBy(501L)
        refresher.onReconnected(server)
        advanceTimeBy(501L)
        assertEquals(listOf(server.value, other.value, server.value), fetched)
    }

    @Test
    fun aFailedRefetchKeepsTheValuesAndTheNextNoticeTriesAgain() = runTest {
        val responses =
            ArrayDeque(
                listOf<() -> HomeNetworkResponse>(
                    { HomeNetworkResponse(200, """{"evaluations":[{"key":"$READ_RECEIPTS_FEATURE_FLAG_KEY","enabled":true}]}""") },
                    { HomeNetworkResponse(503, null) },
                    { error("network down") },
                    { HomeNetworkResponse(200, """{"evaluations":[{"key":"$READ_RECEIPTS_FEATURE_FLAG_KEY","enabled":false}]}""") },
                ),
            )
        val flags =
            SharedServerFeatureFlags(
                object : RaftFeatureFlagsApi {
                    override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                        responses.removeFirst().invoke()
                },
            )
        flags.prefetch(server)
        assertTrue(flags.isEnabled(server, READ_RECEIPTS_FEATURE_FLAG_KEY))
        val logs = mutableListOf<String>()
        val refresher =
            ServerFeatureFlagChangeRefresher(
                scope = backgroundScope,
                refetch = { flags.prefetch(it, forceRefresh = true) },
                jitterMillis = { 0L },
                logger = logs::add,
            )
        refresher.onFeaturesChanged(server)
        advanceTimeBy(1L)
        assertTrue(flags.isEnabled(server, READ_RECEIPTS_FEATURE_FLAG_KEY), "503 keeps the old value")
        refresher.onReconnected(server)
        advanceTimeBy(1L)
        assertTrue(flags.isEnabled(server, READ_RECEIPTS_FEATURE_FLAG_KEY), "a thrown refetch keeps the old value")
        assertTrue(logs.any { it.startsWith("feature_flags change_refetch_failed") }, "logs=$logs")
        refresher.onFeaturesChanged(server)
        advanceTimeBy(1L)
        assertEquals(false, flags.isEnabled(server, READ_RECEIPTS_FEATURE_FLAG_KEY), "a later good answer applies")
        assertTrue(responses.isEmpty())
    }

    @Test
    fun latchKeepsAFeatureOnceSeenOn() {
        val latch = ServerFeatureLatch()
        assertEquals(false, latch.read(false))
        assertEquals(true, latch.read(true))
        assertEquals(true, latch.read(false), "a flag turned off does not tear down what is shown")
    }
}
