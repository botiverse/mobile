package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftPostFirstFrameResumeCoordinatorTest {
    private class Harness {
        var drawCallback: (() -> Unit)? = null
        var drawListenerRemoved = false
        var postedMain: (() -> Unit)? = null
        var postedMainCancelled = false
        var postedFallback: (() -> Unit)? = null
        var postedFallbackDelayMs: Long? = null
        var postedFallbackCancelled = false

        fun schedule(
            coordinator: RaftPostFirstFrameResumeCoordinator,
            withDrawListener: Boolean = true,
            fallbackDelayMillis: Long = 100L,
            onReady: () -> Unit,
        ) {
            drawCallback = null
            drawListenerRemoved = false
            postedMain = null
            postedMainCancelled = false
            postedFallback = null
            postedFallbackDelayMs = null
            postedFallbackCancelled = false
            coordinator.schedule(
                attachDrawListener =
                    if (!withDrawListener) {
                        null
                    } else {
                        { callback ->
                            drawCallback = callback
                            RaftPostFirstFrameResumeCoordinator.CancelHandle {
                                drawListenerRemoved = true
                                drawCallback = null
                            }
                        }
                    },
                postMain = { task ->
                    postedMain = task
                    RaftPostFirstFrameResumeCoordinator.CancelHandle {
                        postedMainCancelled = true
                        postedMain = null
                    }
                },
                postMainDelayed = { delayMs, task ->
                    postedFallbackDelayMs = delayMs
                    postedFallback = task
                    RaftPostFirstFrameResumeCoordinator.CancelHandle {
                        postedFallbackCancelled = true
                        postedFallback = null
                    }
                },
                fallbackDelayMillis = fallbackDelayMillis,
                onReady = onReady,
            )
        }
    }

    @Test
    fun fallbackRunsOnceWhenNoDrawOccurs() {
        val coordinator = RaftPostFirstFrameResumeCoordinator()
        val harness = Harness()
        var readyCount = 0

        harness.schedule(coordinator, fallbackDelayMillis = 80L) { readyCount += 1 }
        assertEquals(0, readyCount)

        val fallback = requireNotNull(harness.postedFallback)
        fallback()

        assertEquals(1, readyCount)
        assertTrue(harness.drawListenerRemoved)
    }
}
