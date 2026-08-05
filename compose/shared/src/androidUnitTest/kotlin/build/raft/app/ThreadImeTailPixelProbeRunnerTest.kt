package build.raft.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task #990: the probe schedules observation around a settlement that also
 * performs product state writes.
 *
 * A previous attempt ran the whole probe body at several phases, which moved
 * those writes earlier and repeated them — so the package could not represent
 * the race it was built to observe, whether it went white or not. That was not
 * visible in the diff, only in reading what the extracted body did, so the
 * ordering is pinned here instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadImeTailPixelProbeRunnerTest {

    @Test
    fun observationPhasesRunBeforeSettlementAndSideEffectsOnlyAfter() = runTest {
        val order = mutableListOf<String>()
        var settlementWrites = 0

        val runner = ThreadImeTailPixelProbeRunner(this)
        runner.launch(
            awaitSettlement = {
                order += "sample:onset"
                order += "sample:frame1"
                order += "await:settlement"
            },
            runProbe = {
                // stands in for pendingKeyboardBottomAnchor = false and the
                // settled log/publish, which must stay on this side
                settlementWrites += 1
                order += "sideEffect"
                order += "sample:settled"
            }
        )
        advanceUntilIdle()

        assertEquals(
            listOf("sample:onset", "sample:frame1", "await:settlement", "sideEffect", "sample:settled"),
            order,
            "observation must precede settlement and side effects must follow it"
        )
        assertEquals(1, settlementWrites, "settlement writes must happen exactly once")
    }

    @Test
    fun sideEffectsDoNotRunWhenTheJobIsCancelledBeforeSettlement() = runTest {
        var settlementWrites = 0
        val runner = ThreadImeTailPixelProbeRunner(this)

        val job = runner.launch(
            awaitSettlement = { kotlinx.coroutines.awaitCancellation() },
            runProbe = { settlementWrites += 1 }
        )
        job.cancel()
        advanceUntilIdle()

        assertEquals(0, settlementWrites, "leaving the scope must not perform the settlement writes")
    }

    @Test
    fun eachScheduleRunsItsSettlementWritesOnce() = runTest {
        var settlementWrites = 0
        val runner = ThreadImeTailPixelProbeRunner(this)

        repeat(3) {
            runner.launch(awaitSettlement = {}, runProbe = { settlementWrites += 1 })
        }
        advanceUntilIdle()

        // One schedule per composer inset effect is enforced by the incident
        // constraint; this pins that a schedule never double-runs its own body.
        assertEquals(3, settlementWrites, "each schedule runs its body exactly once")
    }
}
