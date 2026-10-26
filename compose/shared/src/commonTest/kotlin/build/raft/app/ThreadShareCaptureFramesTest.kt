package build.raft.app

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadShareCaptureFramesTest {
    @Test
    fun withoutAFrameClockTheCaptureIsRefused() = runTest {
        // Fail closed: no clock means the capture-mode recomposition can't be awaited, so the
        // screenshot could still hold viewer-only previews (Tenny on #3737).
        assertFalse(awaitThreadShareCaptureFrames())
    }

    @Test
    fun withAFrameClockItWaitsTheFramesAndAllowsTheCapture() = runTest {
        var frames = 0
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                frames++
                return onFrame(frames.toLong())
            }
        }
        val allowed = withContext(clock) { awaitThreadShareCaptureFrames() }
        assertTrue(allowed)
        assertEquals(2, frames)
    }
}
