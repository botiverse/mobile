/*
 * OwnerCommitGate — pure gate semantics (backend-neutral). The real-driver linearization proofs live in
 * androidUnitTest/OwnerCommitLinearizationTest; these pin the gate's own contract: strictly-monotonic
 * rotation, fail-closed stale rejection BEFORE the body runs, and result/exception passthrough.
 */
package build.raft.app.network.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class OwnerCommitGateTest {

    @Test
    fun rotationIsStrictlyMonotonic() = runBlocking {
        val gate = OwnerCommitGate()
        assertEquals(OwnerCommitGate.FIRST_GENERATION, gate.currentGeneration())
        assertEquals(OwnerCommitGate.FIRST_GENERATION + 1, gate.rotate())
        assertEquals(OwnerCommitGate.FIRST_GENERATION + 2, gate.rotate())
        assertEquals(OwnerCommitGate.FIRST_GENERATION + 2, gate.currentGeneration())
    }

    @Test
    fun currentGenerationRunsBodyAndPassesResultThrough() = runBlocking {
        val gate = OwnerCommitGate()
        val out = gate.withCurrentGeneration(OwnerCommitGate.FIRST_GENERATION) { "committed" }
        assertEquals("committed", out)
    }

    @Test
    fun staleGenerationRejectsBeforeBodyRuns() = runBlocking {
        val gate = OwnerCommitGate()
        gate.rotate()
        var bodyRan = false
        val failure = assertFailsWith<StaleOwnerGeneration> {
            gate.withCurrentGeneration(OwnerCommitGate.FIRST_GENERATION) { bodyRan = true }
        }
        assertFalse(bodyRan, "a stale generation must fail closed before the body runs")
        assertEquals(OwnerCommitGate.FIRST_GENERATION, failure.expected)
        assertEquals(OwnerCommitGate.FIRST_GENERATION + 1, failure.current)
    }

    @Test
    fun bodyFailurePropagatesAndDoesNotBurnTheGeneration() = runBlocking {
        val gate = OwnerCommitGate()
        assertFailsWith<IllegalStateException> {
            gate.withCurrentGeneration(OwnerCommitGate.FIRST_GENERATION) { error("commit failed") }
        }
        // The owner is still the owner: same generation runs again.
        assertEquals("retried", gate.withCurrentGeneration(OwnerCommitGate.FIRST_GENERATION) { "retried" })
    }
}
