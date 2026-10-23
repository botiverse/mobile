package build.raft.designsystem

import androidx.compose.runtime.MonotonicFrameClock
import build.raft.designsystem.components.RaftLocatedHoldMillis
import build.raft.designsystem.components.RaftLocatedMillis
import build.raft.designsystem.components.RaftLocatedState
import build.raft.designsystem.components.raftLocatedStrengthAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** raft-ui 0.22.1 located highlight (#719): `info-soft`, held 1.2s, ease-out, gone at 2s. */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftLocatedTest {
    @Test
    fun strengthHoldsThenEasesOutAndIsGoneAtTwoSeconds() {
        assertEquals(2000, RaftLocatedMillis)
        assertEquals(1200, RaftLocatedHoldMillis)
        assertEquals(1f, raftLocatedStrengthAt(0))
        assertEquals(1f, raftLocatedStrengthAt(1200))
        // ease-out (cubic-bezier(0, 0, 0.58, 1)) at half way is past half faded.
        val mid = raftLocatedStrengthAt(1600)
        assertTrue(mid < 0.5f && mid > 0.1f, "mid-fade strength=$mid")
        assertTrue(raftLocatedStrengthAt(1999) < 0.01f)
        assertEquals(0f, raftLocatedStrengthAt(2000))
        assertEquals(0f, raftLocatedStrengthAt(-1))
        // Monotonic over the fade.
        var last = 1f
        for (t in 1200L..2000L step 50) {
            val s = raftLocatedStrengthAt(t)
            assertTrue(s <= last + 1e-4f, "strength rose at t=$t: $s > $last")
            last = s
        }
    }

    @Test
    fun reducedMotionIsHeldStillThenOff() {
        assertEquals(1f, raftLocatedStrengthAt(0, reducedMotion = true))
        assertEquals(1f, raftLocatedStrengthAt(1800, reducedMotion = true))
        assertEquals(1f, raftLocatedStrengthAt(1999, reducedMotion = true))
        assertEquals(0f, raftLocatedStrengthAt(2000, reducedMotion = true))
    }

    @Test
    fun locateRunsTwoSecondsOnItsItemThenClears() =
        runTest {
            val clock = VirtualFrameClock(testScheduler)
            val state = RaftLocatedState()
            val job = launch(clock) { state.locate("row-2") }
            runCurrent()
            assertEquals("row-2", state.key)
            assertEquals(1f, state.strengthFor("row-2"))
            assertEquals(0f, state.strengthFor("row-1"))
            advanceTimeBy(1000)
            assertEquals(1f, state.strengthFor("row-2"), "held at 1s")
            advanceTimeBy(600)
            val fading = state.strengthFor("row-2")
            assertTrue(fading > 0f && fading < 1f, "fading at 1.6s: $fading")
            advanceTimeBy(500)
            runCurrent()
            assertTrue(job.isCompleted, "locate still running at ${testScheduler.currentTime}ms")
            assertNull(state.key)
            assertEquals(0f, state.strengthFor("row-2"))
        }

    @Test
    fun aNewLocateRestartsAndCancellingClears() =
        runTest {
            val clock = VirtualFrameClock(testScheduler)
            val state = RaftLocatedState()
            val first = launch(clock) { state.locate("a") }
            advanceTimeBy(1500)
            // A second jump (here to another item) takes over; the first's cleanup leaves it.
            first.cancel()
            val second = launch(clock) { state.locate("b") }
            runCurrent()
            assertEquals("b", state.key)
            assertEquals(0f, state.strengthFor("a"))
            advanceTimeBy(1500)
            assertTrue(state.strengthFor("b") > 0f, "b restarted its own 2s")
            second.cancel()
            runCurrent()
            assertNull(state.key, "cancelled locate leaves no highlight")
        }

    @Test
    fun reducedMotionStateStaysFullUntilTheEnd() =
        runTest {
            val clock = VirtualFrameClock(testScheduler)
            val state = RaftLocatedState(reducedMotion = true)
            launch(clock) { state.locate(1) }
            advanceTimeBy(1900)
            assertEquals(1f, state.strengthFor(1))
            advanceTimeBy(200)
            runCurrent()
            assertNull(state.key)
        }
}

/** A 60fps frame clock on the test's virtual time. */
private class VirtualFrameClock(private val scheduler: TestCoroutineScheduler) : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        delay(16)
        return onFrame(scheduler.currentTime * 1_000_000L)
    }
}
