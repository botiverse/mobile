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
    fun runsOnceAfterFirstDrawAndCancelsFallbackWithoutRemovingListenerInsideOnDraw() {
        val coordinator = RaftPostFirstFrameResumeCoordinator()
        val harness = Harness()
        var readyCount = 0

        harness.schedule(coordinator) { readyCount += 1 }
        // Not executed synchronously in onResume.
        assertEquals(0, readyCount)
        assertEquals(100L, harness.postedFallbackDelayMs)

        // First onDraw schedules post-frame runnable without removing the listener inside onDraw.
        val onDraw = requireNotNull(harness.drawCallback)
        onDraw()
        assertEquals(0, readyCount)
        assertFalse(harness.drawListenerRemoved)
        assertTrue(harness.postedMain != null)

        // A second onDraw before the post runs does not queue duplicate work.
        onDraw()
        val postTask = requireNotNull(harness.postedMain)
        postTask()

        assertEquals(1, readyCount)
        assertTrue(harness.drawListenerRemoved)
        assertTrue(harness.postedFallbackCancelled)
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

    @Test
    fun pauseBeforeFirstDrawCancelsPendingWorkEvenIfLateCallbacksFire() {
        val coordinator = RaftPostFirstFrameResumeCoordinator()
        val harness = Harness()
        var readyCount = 0

        harness.schedule(coordinator) { readyCount += 1 }
        val staleDraw = requireNotNull(harness.drawCallback)
        val staleFallback = requireNotNull(harness.postedFallback)

        coordinator.cancel()
        assertTrue(harness.drawListenerRemoved)
        assertTrue(harness.postedFallbackCancelled)

        // Late callbacks from an already-dispatched message must not execute onReady.
        staleDraw()
        staleFallback()
        assertEquals(0, readyCount)
    }

    @Test
    fun pauseAfterDrawBeforePostRunsCancelsPostDrawRunnable() {
        val coordinator = RaftPostFirstFrameResumeCoordinator()
        val harness = Harness()
        var readyCount = 0

        harness.schedule(coordinator) { readyCount += 1 }
        requireNotNull(harness.drawCallback).invoke()
        val stalePost = requireNotNull(harness.postedMain)

        coordinator.cancel()
        assertTrue(harness.postedMainCancelled)
        stalePost()
        assertEquals(0, readyCount)
    }

    @Test
    fun repeatedScheduleSupersedesPriorResume() {
        val coordinator = RaftPostFirstFrameResumeCoordinator()
        val harness = Harness()
        val fired = mutableListOf<String>()

        harness.schedule(coordinator) { fired += "first" }
        val firstFallback = requireNotNull(harness.postedFallback)

        harness.schedule(coordinator) { fired += "second" }
        firstFallback()
        assertEquals(emptyList<String>(), fired)

        requireNotNull(harness.drawCallback).invoke()
        requireNotNull(harness.postedMain).invoke()
        assertEquals(listOf("second"), fired)
    }

    @Test
    fun missingDrawListenerFallsBackToMainPost() {
        val coordinator = RaftPostFirstFrameResumeCoordinator()
        val harness = Harness()
        var readyCount = 0

        harness.schedule(coordinator, withDrawListener = false) { readyCount += 1 }
        assertEquals(0, readyCount)
        requireNotNull(harness.postedMain).invoke()
        assertEquals(1, readyCount)
    }

    @Test
    fun lateFallbackAfterPostDrawDoesNotRunOnReadyTwice() {
        val coordinator = RaftPostFirstFrameResumeCoordinator()
        val harness = Harness()
        var readyCount = 0

        harness.schedule(coordinator) { readyCount += 1 }
        val staleFallback = requireNotNull(harness.postedFallback)
        requireNotNull(harness.drawCallback).invoke()
        requireNotNull(harness.postedMain).invoke()
        assertEquals(1, readyCount)

        // Even if an already-dispatched fallback message executes after post-draw fired, onReady runs once.
        staleFallback()
        assertEquals(1, readyCount)
    }

    @Test
    fun lateDrawAndPostAfterFallbackDoesNotRunOnReadyTwice() {
        val coordinator = RaftPostFirstFrameResumeCoordinator()
        val harness = Harness()
        var readyCount = 0

        harness.schedule(coordinator) { readyCount += 1 }
        val staleDraw = requireNotNull(harness.drawCallback)
        requireNotNull(harness.postedFallback).invoke()
        assertEquals(1, readyCount)

        // Even if a late onDraw callback runs after fallback fired, it must not queue or invoke onReady again.
        staleDraw()
        assertEquals(null, harness.postedMain)
        assertEquals(1, readyCount)
    }
}
