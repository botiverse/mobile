package build.raft.app.thread

import androidx.compose.runtime.MonotonicFrameClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class AwaitNextThreadFrameTest {
    @Test
    fun sameFrameCancellationAfterClockResumeThrowsCancellationException() = runTest {
        var resumedAfterAwait = false
        val clock =
            object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                    val result = onFrame(16_000_000L)
                    // Mirrors TestMonotonicFrameClock / AndroidUiFrameClock when a sibling awaiter
                    // (the Recomposer) cancels the owning LaunchedEffect on the same vsync after
                    // withFrameNanos has already committed its resumption.
                    currentCoroutineContext()[Job]?.cancel()
                    return result
                }
            }

        val job =
            launch(clock) {
                awaitNextThreadFrame()
                resumedAfterAwait = true
            }
        job.join()

        assertTrue(job.isCancelled, "same-vsync cancellation must propagate out of awaitNextThreadFrame")
        assertFalse(resumedAfterAwait, "cancelled effect must not continue past awaitNextThreadFrame")
    }

    @Test
    fun liveFrameClockUsesWithFrameNanosWithoutVirtualDelay() = runTest {
        var frames = 0
        val clock =
            object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                    frames += 1
                    return onFrame(frames * 8_333_333L)
                }
            }

        withContext(clock) {
            awaitNextThreadFrame()
        }

        assertEquals(1, frames)
        assertEquals(0L, currentTime)
    }

    @Test
    fun headlessFallbackAdvancesOneVirtualFrameWithoutFrameClock() = runTest {
        awaitNextThreadFrame()

        assertEquals(16L, currentTime)
    }

    @Test
    fun shareCaptureFramesStopsAfterSameFrameCancellation() = runTest {
        var frames = 0
        var completed = false
        val clock =
            object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                    frames += 1
                    val result = onFrame(frames * 16_000_000L)
                    currentCoroutineContext()[Job]?.cancel()
                    return result
                }
            }

        val job =
            launch(clock) {
                awaitThreadShareCaptureFrames(frames = 2)
                completed = true
            }
        job.join()

        assertEquals(1, frames, "second capture frame must not be awaited after same-vsync cancellation")
        assertFalse(completed, "cancelled capture effect must not proceed to capture")
    }
}
