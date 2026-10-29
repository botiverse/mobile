package build.raft.app.data

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
}
