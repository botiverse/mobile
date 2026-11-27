package build.raft.app.shared

import build.raft.app.compat.ui.RaftHostImeInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidImeAnimationTrackerTest {
    private class FakeFrameClock {
        var nowMs: Long = 1_000L
        var liveRootWindowImePx: Int? = null
        private var pendingFrame: (() -> Unit)? = null
        var scheduledCount: Int = 0
        var cancelledCount: Int = 0
        var stateChangedCount: Int = 0

        val hasPendingFrame: Boolean
            get() = pendingFrame != null

        fun schedule(onFrame: () -> Unit) {
            scheduledCount++
            pendingFrame = onFrame
        }

        fun cancel() {
            if (pendingFrame != null) {
                cancelledCount++
                pendingFrame = null
            }
        }

        fun stepVsync(stepMs: Long = 8L) {
            nowMs += stepMs
            val callback = pendingFrame ?: return
            pendingFrame = null
            callback()
        }
    }

    private fun newTracker(
        clock: FakeFrameClock,
        initialImePx: Int = 0,
    ): Pair<RaftHostImeInsets, AndroidImeAnimationTracker<String>> {
        val state = RaftHostImeInsets().apply { imeBottomPx = initialImePx }
        val tracker =
            AndroidImeAnimationTracker<String>(
                state = state,
                nowMillis = { clock.nowMs },
                scheduleNextFrame = clock::schedule,
                cancelNextFrame = clock::cancel,
                readSettledWindowImePx = { clock.liveRootWindowImePx },
                onStateChanged = { clock.stateChangedCount++ },
            )
        return state to tracker
    }

    @Test
    fun nonAnimatedWindowInsetsWriteImeBottomPxDirectlyWithAnimationFlagAlwaysFalse() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock)

        assertFalse(state.imeAnimationRunning)
        assertEquals(0, clock.stateChangedCount)

        tracker.onWindowInsetsApplied(850)
        assertEquals(850, state.imeBottomPx)
        assertEquals(1, clock.stateChangedCount)
        assertFalse(state.imeAnimationRunning)
        assertFalse(clock.hasPendingFrame)

        // Writing the same value again must not emit a redundant snapshot notification.
        tracker.onWindowInsetsApplied(850)
        assertEquals(1, clock.stateChangedCount)

        tracker.onWindowInsetsApplied(0)
        assertEquals(0, state.imeBottomPx)
        assertEquals(2, clock.stateChangedCount)
        assertFalse(state.imeAnimationRunning)
    }

    @Test
    fun normalShowAndHideDriveImeBottomPxFrameByFrameWithoutSnappingOnApply() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock)

        // Show: 0 -> 850
        tracker.onPrepare("show")
        tracker.onWindowInsetsApplied(850)
        // Deferred while animation is active — must not snap to 850 before onProgress starts.
        assertEquals(0, state.imeBottomPx)
        assertFalse(state.imeAnimationRunning)

        tracker.onStart("show", durationMillis = 285L)
        assertEquals(0, state.imeBottomPx)

        val showSteps = listOf(120, 340, 620, 810, 850)
        for (px in showSteps) {
            tracker.onProgress(px)
            assertEquals(px, state.imeBottomPx)
            clock.stepVsync(16L)
        }

        val showSummary = tracker.onEnd("show", durationMillis = 285L)
        assertNotNull(showSummary)
        assertFalse(showSummary!!.timedOut)
        assertEquals(5, showSummary.progressFrames)
        assertEquals(850, showSummary.maxProgressImePx)
        assertEquals(850, showSummary.windowImePx)
        assertEquals(850, state.imeBottomPx)
        assertFalse(clock.hasPendingFrame)

        // Hide: 850 -> 0
        tracker.onPrepare("hide")
        tracker.onWindowInsetsApplied(0)
        // Deferred while hide animation is active — must not snap to 0 before onProgress starts.
        assertEquals(850, state.imeBottomPx)
        assertFalse(state.imeAnimationRunning)

        tracker.onStart("hide", durationMillis = 250L)
        val hideSteps = listOf(700, 480, 210, 40, 0)
        for (px in hideSteps) {
            tracker.onProgress(px)
            assertEquals(px, state.imeBottomPx)
            clock.stepVsync(16L)
        }

        val hideSummary = tracker.onEnd("hide", durationMillis = 250L)
        assertNotNull(hideSummary)
        assertFalse(hideSummary!!.timedOut)
        assertEquals(5, hideSummary.progressFrames)
        assertEquals(700, hideSummary.maxProgressImePx)
        assertEquals(0, hideSummary.windowImePx)
        assertEquals(0, state.imeBottomPx)
        assertFalse(clock.hasPendingFrame)
    }

    @Test
    fun hyperOs120HzLongSpringCurveCompletesWithoutTimeoutRegardlessOfElapsedDuration() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock)

        // Simulates Xiaomi HyperOS 120Hz (Hands 5f4a14be): durationMillis=285, ~60Hz spring steps
        // on a 120Hz display (1 onProgress every 2 vsyncs) running ~900ms, and extended past 3s
        // without any wall-clock cap or premature fraction-based settlement.
        tracker.onPrepare("hyperos-show")
        tracker.onWindowInsetsApplied(900)
        tracker.onStart("hyperos-show", durationMillis = 285L)

        for (frame in 1..220) {
            val px = (900 * frame) / 220
            tracker.onProgress(px)
            assertEquals(px, state.imeBottomPx)
            clock.stepVsync(8L)
            clock.stepVsync(8L) // 120Hz panel receiving 60Hz spring updates (> 3.5s total)
        }

        val summary = tracker.onEnd("hyperos-show", durationMillis = 285L)
        assertNotNull(summary)
        assertFalse(summary!!.timedOut)
        assertEquals(220, summary.progressFrames)
        assertEquals(900, summary.windowImePx)
        assertEquals(900, state.imeBottomPx)
        assertFalse(clock.hasPendingFrame)
    }

    @Test
    fun vsyncRunningBeforeOnProgressOnEveryFrameNeverSpuriouslySettles() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock)

        tracker.onPrepare("anim")
        tracker.onWindowInsetsApplied(800)
        tracker.onStart("anim", durationMillis = 285L)

        // Even if Choreographer's callback runs before onProgress on every single vsync:
        for (frame in 1..60) {
            clock.stepVsync(8L)
            val px = (800 * frame) / 60
            tracker.onProgress(px)
            assertEquals(px, state.imeBottomPx)
        }

        val summary = tracker.onEnd("anim", durationMillis = 285L)
        assertNotNull(summary)
        assertFalse(summary!!.timedOut)
        assertEquals(60, summary.progressFrames)
    }

    @Test
    fun lowerRateAnimationOnHighRefreshDisplayDoesNotSettlePrematurely() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock)

        tracker.onPrepare("30hz-on-120hz")
        tracker.onWindowInsetsApplied(700)
        tracker.onStart("30hz-on-120hz", durationMillis = 300L)

        // 1 onProgress every 4 vsync frames (30Hz animation on 120Hz panel):
        for (step in 1..10) {
            tracker.onProgress(step * 70)
            assertEquals(step * 70, state.imeBottomPx)
            repeat(3) { clock.stepVsync(8L) }
            clock.stepVsync(8L)
        }

        val summary = tracker.onEnd("30hz-on-120hz", durationMillis = 300L)
        assertNotNull(summary)
        assertFalse(summary!!.timedOut)
        assertEquals(700, state.imeBottomPx)
    }

    @Test
    fun reversalMidAnimationTransitionsSmoothlyWithoutSnapping() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock, initialImePx = 850)

        tracker.onPrepare("hide")
        tracker.onWindowInsetsApplied(0)
        tracker.onStart("hide", durationMillis = 250L)
        tracker.onProgress(600)
        clock.stepVsync(8L)
        tracker.onProgress(400)
        assertEquals(400, state.imeBottomPx)

        // Show reverses hide while hide is still running.
        tracker.onPrepare("show")
        tracker.onWindowInsetsApplied(850)
        // Must stay smoothly at 400 (not snap to 850 or 0) when hide ends while show is prepared.
        assertNull(tracker.onEnd("hide", durationMillis = 250L))
        assertEquals(400, state.imeBottomPx)

        tracker.onStart("show", durationMillis = 285L)
        tracker.onProgress(550)
        assertEquals(550, state.imeBottomPx)
        clock.stepVsync(8L)
        tracker.onProgress(850)
        assertEquals(850, state.imeBottomPx)

        val summary = tracker.onEnd("show", durationMillis = 285L)
        assertNotNull(summary)
        assertFalse(summary!!.timedOut)
        assertEquals(850, state.imeBottomPx)
    }

    @Test
    fun orphanedOnPrepareWithoutOnStartRecoversWithinPreparedVsyncFrames() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock)

        // Huawei 1af517cc: onPrepare + onApplyWindowInsets(850), but onStart never fires.
        tracker.onPrepare("orphan-prepare")
        tracker.onWindowInsetsApplied(850)
        assertEquals(0, state.imeBottomPx)
        assertEquals(0, clock.stateChangedCount)

        clock.stepVsync(8L) // frame 1: still waiting for cross-frame onStart
        assertEquals(0, state.imeBottomPx)
        assertEquals(0, clock.stateChangedCount)

        clock.stepVsync(8L) // frame 2: settles orphaned prepare and notifies Compose synchronously
        assertEquals(850, state.imeBottomPx)
        assertEquals(1, clock.stateChangedCount)
        assertFalse(clock.hasPendingFrame)

        // Late onEnd reports timedOut=true and leaves state settled.
        val late = tracker.onEnd("orphan-prepare", durationMillis = 285L)
        assertNotNull(late)
        assertTrue(late!!.timedOut)
        assertEquals(850, state.imeBottomPx)
    }

    @Test
    fun droppedOnEndAtFinalHeightSettlesAfterIdleVsyncsWithoutVisualJump() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock)

        tracker.onPrepare("drop-end")
        tracker.onWindowInsetsApplied(850)
        tracker.onStart("drop-end", durationMillis = 285L)
        tracker.onProgress(400)
        clock.stepVsync(8L)
        tracker.onProgress(850)
        assertEquals(850, state.imeBottomPx)

        // First vsync clears sawProgressSinceLastFrame; next idle vsyncs settle uniformly with no jump.
        clock.stepVsync(8L)
        repeat(IME_IDLE_VSYNC_FRAMES_TO_SETTLE - 1) {
            clock.stepVsync(8L)
            assertTrue(clock.hasPendingFrame)
            assertEquals(850, state.imeBottomPx)
        }
        clock.stepVsync(8L)
        assertFalse(clock.hasPendingFrame)
        assertEquals(850, state.imeBottomPx)

        // Subsequent non-animated inset dispatch works immediately.
        tracker.onWindowInsetsApplied(0)
        assertEquals(0, state.imeBottomPx)

        val late = tracker.onEnd("drop-end", durationMillis = 285L)
        assertNotNull(late)
        assertTrue(late!!.timedOut)
    }

    @Test
    fun droppedOnEndMidAnimationSettlesAfterIdleVsyncsAndNeverResurrects() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock)

        tracker.onPrepare("stalled")
        tracker.onWindowInsetsApplied(850)
        tracker.onStart("stalled", durationMillis = 285L)
        tracker.onProgress(320)
        assertEquals(320, state.imeBottomPx)
        assertEquals(1, clock.stateChangedCount)

        // First vsync clears sawProgressSinceLastFrame; next 4 idle vsyncs settle to window inset 850.
        clock.stepVsync(8L)
        repeat(IME_IDLE_VSYNC_FRAMES_TO_SETTLE - 1) {
            clock.stepVsync(8L)
            assertEquals(320, state.imeBottomPx)
        }
        clock.stepVsync(8L)
        assertEquals(850, state.imeBottomPx)
        assertEquals(2, clock.stateChangedCount)
        assertFalse(clock.hasPendingFrame)

        // Stale late onProgress must NOT snap state back to 320.
        tracker.onProgress(320)
        assertEquals(850, state.imeBottomPx)
        assertEquals(2, clock.stateChangedCount)
    }

    @Test
    fun staleUntrackedOnEndDuringActiveAnimationDoesNotResetIdleCounters() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock)

        tracker.onPrepare("active")
        tracker.onWindowInsetsApplied(850)
        tracker.onStart("active", durationMillis = 285L)
        tracker.onProgress(300)

        // Advance to one frame short of settling:
        clock.stepVsync(8L) // clears sawProgressSinceLastFrame
        repeat(IME_IDLE_VSYNC_FRAMES_TO_SETTLE - 1) { clock.stepVsync(8L) }
        assertEquals(300, state.imeBottomPx)

        // An unrelated / stale token ending must not disturb the active animation's idle count.
        assertNull(tracker.onEnd("unknown-stale-token", durationMillis = 285L))
        assertEquals(300, state.imeBottomPx)

        // The very next idle vsync must settle without having been delayed.
        clock.stepVsync(8L)
        assertEquals(850, state.imeBottomPx)
        assertFalse(clock.hasPendingFrame)
    }

    @Test
    fun stateChangedCallbackFiresSynchronouslyOnEndReconciliation() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock)

        tracker.onPrepare("anim")
        tracker.onWindowInsetsApplied(850)
        tracker.onStart("anim", durationMillis = 285L)
        tracker.onProgress(400)
        assertEquals(1, clock.stateChangedCount)

        // Reconciling 400 -> 850 on onEnd must flush snapshot notifications immediately.
        val summary = tracker.onEnd("anim", durationMillis = 285L)
        assertNotNull(summary)
        assertEquals(850, state.imeBottomPx)
        assertEquals(2, clock.stateChangedCount)
    }

    @Test
    fun userDrivenAnimationSuspendsIdleVsyncSettlementUntilOnEnd() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock, initialImePx = 850)

        tracker.onPrepare("drag")
        tracker.onWindowInsetsApplied(0)
        tracker.onStart("drag", durationMillis = -1L)
        assertFalse(clock.hasPendingFrame)

        tracker.onProgress(420)
        assertEquals(420, state.imeBottomPx)
        assertFalse(clock.hasPendingFrame)

        // User holds finger still for many frames — never settles early.
        repeat(50) { clock.stepVsync(16L) }
        assertEquals(420, state.imeBottomPx)

        clock.liveRootWindowImePx = 0
        val summary = tracker.onEnd("drag", durationMillis = -1L)
        assertNotNull(summary)
        assertFalse(summary!!.timedOut)
        assertEquals(0, state.imeBottomPx)
    }

    @Test
    fun overlappingUserDrivenEndReArmsVsyncWatcherForRemainingTimedAnimation() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock)

        tracker.onPrepare("timed")
        tracker.onWindowInsetsApplied(850)
        tracker.onStart("timed", durationMillis = 285L)

        tracker.onPrepare("drag")
        tracker.onStart("drag", durationMillis = -1L)
        assertFalse(clock.hasPendingFrame)

        // User gesture ends while timed animation is still orphaned.
        assertNull(tracker.onEnd("drag", durationMillis = -1L))
        assertTrue(clock.hasPendingFrame)

        clock.stepVsync(8L)
        repeat(IME_IDLE_VSYNC_FRAMES_TO_SETTLE) { clock.stepVsync(8L) }
        assertEquals(850, state.imeBottomPx)
        assertFalse(clock.hasPendingFrame)
    }

    @Test
    fun onEndPrefersLiveRootWindowInsetsOverStaleAppliedSnapshot() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock)

        tracker.onPrepare("cancelled-show")
        tracker.onWindowInsetsApplied(850)
        tracker.onStart("cancelled-show", durationMillis = 285L)
        tracker.onProgress(200)
        // Platform cancelled back to 0 without re-dispatching onApplyWindowInsets.
        clock.liveRootWindowImePx = 0

        val summary = tracker.onEnd("cancelled-show", durationMillis = 285L)
        assertNotNull(summary)
        assertEquals(0, summary!!.windowImePx)
        assertEquals(0, state.imeBottomPx)
    }

    @Test
    fun disposeCancelsPendingFrameCallbackAndIgnoresSubsequentEvents() {
        val clock = FakeFrameClock()
        val (state, tracker) = newTracker(clock)

        tracker.onPrepare("anim")
        tracker.onWindowInsetsApplied(850)
        assertTrue(clock.hasPendingFrame)

        tracker.dispose()
        assertFalse(clock.hasPendingFrame)
        assertEquals(1, clock.cancelledCount)
        assertFalse(state.imeAnimationRunning)

        tracker.onWindowInsetsApplied(500)
        tracker.onProgress(300)
        assertNull(tracker.onEnd("anim", durationMillis = 285L))
        assertEquals(0, state.imeBottomPx)
    }
}
