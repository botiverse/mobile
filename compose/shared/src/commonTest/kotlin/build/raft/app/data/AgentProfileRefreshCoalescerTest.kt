package build.raft.app.data

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
}
