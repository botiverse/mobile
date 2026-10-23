package build.raft.app.image

import androidx.compose.runtime.MonotonicFrameClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest

class RaftAnimatedFramePlayerTest {
    /** The fixture's real delays: 100ms, 200ms and a 0ms frame that plays at 100ms. */
    private val fixtureDelays = RaftAnimatedImageFixtures.gifFrameDelaysMillis(RaftAnimatedImageFixtures.threeFrameGif)

    @Test
    fun stepsThroughTheFixtureFramesOnAFakeClock() {
        val player = RaftAnimatedFramePlayer(fixtureDelays, repetitionCount = -1)
        assertEquals(listOf(100L, 200L, 100L), (0 until player.frameCount).map(player::frameDurationMillis))
        assertFalse(player.advance(10_000), "nothing plays before resume")

        player.resume(nowMillis = 0)
        val steps =
            listOf(99L, 100L, 299L, 300L, 399L, 400L).map { now -> now to player.advance(now).let { player.frameIndex } }
        assertEquals(listOf(99L to 0, 100L to 1, 299L to 1, 300L to 2, 399L to 2, 400L to 0), steps)

        // A late tick moves one frame (no decoding of skipped frames) and reschedules from now.
        assertTrue(player.advance(1_000))
        assertEquals(1, player.frameIndex)
        assertEquals(1_200L, player.deadlineMillis)

        // Paused, time passes without frames; resuming gives the current frame its full duration.
        player.pause()
        assertFalse(player.advance(5_000))
        player.resume(nowMillis = 5_000)
        assertEquals(5_200L, player.deadlineMillis)
        assertFalse(player.advance(5_199))
        assertTrue(player.advance(5_200))
        assertEquals(2, player.frameIndex)
    }

    @Test
    fun finiteRepetitionHoldsTheLastFrame() {
        // Repetition count 0 (no NETSCAPE loop block): the sequence plays once.
        val player = RaftAnimatedFramePlayer(fixtureDelays, repetitionCount = 0)
        player.resume(nowMillis = 0)
        listOf(100L, 300L).forEach { assertTrue(player.advance(it)) }
        assertFalse(player.advance(400))
        assertTrue(player.finished)
        assertEquals(2, player.frameIndex)
        assertNull(player.deadlineMillis)

        val once = RaftAnimatedFramePlayer(listOf(50, 50), repetitionCount = 1)
        once.resume(0)
        val indices = (1..6).map { once.advance(it * 50L); once.frameIndex }
        assertEquals(listOf(1, 0, 1, 1, 1, 1), indices)
        assertTrue(once.finished)
    }

    @Test
    fun singleFrameNeverPlays() {
        val player = RaftAnimatedFramePlayer(listOf(100), repetitionCount = -1)
        assertTrue(player.finished)
        player.resume(0)
        assertFalse(player.advance(1_000))
    }

    @Test
    fun playShowsEachFrameOnTimeAndStopsWhenCancelled() = runTest {
        val player = RaftAnimatedFramePlayer(fixtureDelays, repetitionCount = -1)
        val shown = mutableListOf<Pair<Long, Int>>()
        val job =
            launch(FakeFrameClock(testScheduler)) {
                player.play { index -> shown += testScheduler.currentTime to index }
            }
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()

        // First vsync at 16ms starts the clock; frames then end at 100, 200, 100, 100, ... ms.
        val start = 16L
        val expectedIndices = listOf(1, 2, 0, 1, 2, 0, 1)
        assertEquals(expectedIndices, shown.map { it.second }, "shown=$shown")
        var nominal = start
        var current = 0
        shown.forEach { (time, index) ->
            nominal += player.frameDurationMillis(current)
            assertTrue(time in nominal until nominal + 2 * FakeFrameClock.FRAME_MILLIS, "frame $index at $time, due $nominal; shown=$shown")
            current = index
        }

        job.cancel()
        testScheduler.advanceUntilIdle()
        val held = player.frameIndex
        assertNull(player.deadlineMillis, "cancelling pauses the player")
        testScheduler.advanceTimeBy(1_000)
        assertEquals(expectedIndices.size, shown.size, "no frames after cancel")
        assertEquals(held, player.frameIndex)
    }

    /** A 60Hz-ish frame clock on the test scheduler's virtual time. */
    private class FakeFrameClock(private val scheduler: TestCoroutineScheduler) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(FRAME_MILLIS)
            return onFrame(scheduler.currentTime * 1_000_000)
        }

        companion object {
            const val FRAME_MILLIS = 16L
        }
    }
}
