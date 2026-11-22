package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentProfileRefreshCoalescerTest {
    @Test
    fun aBurstOfAgentUpdatesReadsTheAgentsOncePerServer() = runTest {
        val reads = mutableListOf<String>()
        val coalescer = AgentProfileRefreshCoalescer(backgroundScope, windowMillis = 1_500) { reads += it }

        repeat(12) { coalescer.request("s1") }
        coalescer.request("s2")
        advanceTimeBy(1_000)
        coalescer.request("s1")
        runCurrent()
        assertEquals(emptyList(), reads)

        advanceTimeBy(501)
        runCurrent()
        assertEquals(listOf("s1", "s2"), reads.sorted())
    }

    @Test
    fun anUpdateAfterTheReadStartedSchedulesAnotherRead() = runTest {
        val reads = mutableListOf<String>()
        val coalescer = AgentProfileRefreshCoalescer(backgroundScope, windowMillis = 1_500) { reads += it }

        coalescer.request("s1")
        advanceTimeBy(1_501)
        runCurrent()
        coalescer.request("s1")
        advanceTimeBy(1_501)
        runCurrent()

        assertEquals(listOf("s1", "s1"), reads)
    }

    @Test
    fun cancelDropsPendingRefreshForOneServerWithoutAffectingOthers() = runTest {
        val reads = mutableListOf<String>()
        val coalescer = AgentProfileRefreshCoalescer(backgroundScope, windowMillis = 1_500) { reads += it }

        coalescer.request("s1")
        coalescer.request("s2")
        advanceTimeBy(500)
        coalescer.cancel("s1")
        advanceTimeBy(1_100)
        runCurrent()

        assertEquals(listOf("s2"), reads)

        coalescer.request("s1")
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(listOf("s2", "s1"), reads)
    }

    @Test
    fun cancelAllDropsEveryPendingRefreshAndAllowsSubsequentRequests() = runTest {
        val reads = mutableListOf<String>()
        val coalescer = AgentProfileRefreshCoalescer(backgroundScope, windowMillis = 1_500) { reads += it }

        coalescer.request("s1")
        coalescer.request("s2")
        advanceTimeBy(500)
        coalescer.cancelAll()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(emptyList(), reads)

        coalescer.request("s1")
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(listOf("s1"), reads)
    }

    @Test
    fun requestOnCancelledScopeDoesNotInvokeRefresh() = runTest {
        val reads = mutableListOf<String>()
        val cancelledScope = CoroutineScope(SupervisorJob() + coroutineContext)
        cancelledScope.cancel()
        val coalescer = AgentProfileRefreshCoalescer(cancelledScope, windowMillis = 1_500) { reads += it }

        coalescer.request("s1")
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(emptyList(), reads)
    }

    @Test
    fun cancelCancelsInFlightRefreshForTargetServerWithoutAffectingOthers() = runTest {
        val started = mapOf("s1" to CompletableDeferred<Unit>(), "s2" to CompletableDeferred<Unit>())
        val gates = mutableMapOf("s1" to CompletableDeferred<Unit>(), "s2" to CompletableDeferred<Unit>())
        val cancelled = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val coalescer =
            AgentProfileRefreshCoalescer(backgroundScope, windowMillis = 1_500) { serverId ->
                started[serverId]?.complete(Unit)
                try {
                    gates.getValue(serverId).await()
                    completed += serverId
                } catch (cancellation: CancellationException) {
                    cancelled += serverId
                    throw cancellation
                }
            }

        coalescer.request("s1")
        coalescer.request("s2")
        advanceTimeBy(1_501)
        runCurrent()
        started.getValue("s1").await()
        started.getValue("s2").await()

        // Cancel s1 while both s1 and s2 refreshes are actively in flight.
        coalescer.cancel("s1")
        runCurrent()
        assertEquals(listOf("s1"), cancelled)
        assertEquals(emptyList(), completed)

        // s2 finishes normally unaffected by s1's cancellation.
        gates.getValue("s2").complete(Unit)
        runCurrent()
        assertEquals(listOf("s2"), completed)
        assertEquals(listOf("s1"), cancelled)

        // Subsequent requests for s1 still run cleanly after an in-flight cancel.
        gates["s1"] = CompletableDeferred<Unit>().apply { complete(Unit) }
        coalescer.request("s1")
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(listOf("s2", "s1"), completed)
    }

    @Test
    fun cancelAllCancelsEveryInFlightAndPendingRefresh() = runTest {
        val started = mapOf("s1" to CompletableDeferred<Unit>(), "s3" to CompletableDeferred<Unit>())
        val gates = mapOf("s1" to CompletableDeferred<Unit>(), "s3" to CompletableDeferred<Unit>())
        val startedReads = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val coalescer =
            AgentProfileRefreshCoalescer(backgroundScope, windowMillis = 1_500) { serverId ->
                startedReads += serverId
                started[serverId]?.complete(Unit)
                try {
                    gates.getValue(serverId).await()
                    completed += serverId
                } catch (cancellation: CancellationException) {
                    cancelled += serverId
                    throw cancellation
                }
            }

        coalescer.request("s1")
        coalescer.request("s3")
        advanceTimeBy(1_501)
        runCurrent()
        started.getValue("s1").await()
        started.getValue("s3").await()

        // Enqueue pending follow-up on s1 plus a pending refresh on s2 while s1 and s3 are active.
        coalescer.request("s1")
        coalescer.request("s2")
        advanceTimeBy(500)
        coalescer.cancelAll()
        runCurrent()

        assertEquals(listOf("s1", "s3"), cancelled.sorted())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("s1", "s3"), startedReads.sorted())
        assertEquals(emptyList(), completed)
    }
}
