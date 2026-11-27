package build.raft.app.shared

import build.raft.app.compat.ui.RaftHostImeInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidImeAnimationTrackerTest {
    private class FakeClockAndScheduler {
        var nowMs: Long = 1_000L
        var scheduledAtMs: Long? = null
        var scheduledAction: (() -> Unit)? = null
        var scheduleCalls: Int = 0
        var cancelCalls: Int = 0

        fun schedule(delayMs: Long, action: () -> Unit) {
            scheduleCalls++
            scheduledAtMs = nowMs + delayMs
            scheduledAction = action
        }

        fun cancel() {
            cancelCalls++
            scheduledAtMs = null
            scheduledAction = null
        }

        fun advanceTo(targetMs: Long) {
            while (true) {
                val fireAt = scheduledAtMs ?: break
                if (fireAt > targetMs) break
                nowMs = fireAt
                val action = scheduledAction
                scheduledAtMs = null
                scheduledAction = null
                action?.invoke()
            }
            nowMs = targetMs
        }

        fun advanceBy(deltaMs: Long) = advanceTo(nowMs + deltaMs)
    }

    private fun createTracker(
        clock: FakeClockAndScheduler,
        state: RaftHostImeInsets = RaftHostImeInsets(),
    ): AndroidImeAnimationTracker<String> =
        AndroidImeAnimationTracker(
            state = state,
            nowMillis = { clock.nowMs },
            scheduleExpiry = clock::schedule,
            cancelExpiry = clock::cancel,
        )

    @Test
    fun hyperOsLongSpringAnimationStaysActiveAcrossProgressFramesWithoutPrematureTimeout() {
        val clock = FakeClockAndScheduler()
        val state = RaftHostImeInsets().apply { imeBottomPx = 0 }
        val tracker = createTracker(clock, state)

        tracker.onPrepare("hide-1")
        clock.advanceBy(16L)
        tracker.onStart("hide-1", durationMillis = 285L)
        assertTrue(state.imeAnimationRunning)

        // Simulate Xiaomi HyperOS 120Hz spring animation: 54 progress frames over ~846ms while
        // declared durationMillis is only 285ms (previously timed out at 435ms).
        for (frame in 1..54) {
            clock.advanceBy(15L)
            val height = ((54 - frame + 1) * 1158) / 54
            tracker.onProgress(imeBottomPx = height)
            assertTrue("frame $frame at elapsed=${clock.nowMs - 1_000L}ms must not time out", state.imeAnimationRunning)
        }

        clock.advanceBy(20L)
        val summary = tracker.onEnd("hide-1", durationMillis = 285L)
        assertNotNull(summary)
        assertFalse("normal onEnd after 54 frames must not be marked timedOut", summary!!.timedOut)
        assertEquals(54, summary.progressFrames)
        assertEquals(285L, summary.durationMs)
        assertEquals(16L + 54L * 15L + 20L, summary.elapsedMs)
        assertEquals(1158, summary.maxProgressImePx)
        assertEquals(0, summary.windowImePx)
        assertFalse(state.imeAnimationRunning)
        assertNull(clock.scheduledAtMs)
    }

    @Test
    fun droppedOnEndAfterProgressStopsExpiresAfterIdleGrace() {
        val clock = FakeClockAndScheduler()
        val state = RaftHostImeInsets().apply { imeBottomPx = 1158 }
        val tracker = createTracker(clock, state)

        tracker.onPrepare("show-1")
        tracker.onStart("show-1", durationMillis = 285L)

        // Progress runs up to t+600ms, then stops without onEnd.
        repeat(30) {
            clock.advanceBy(20L)
            tracker.onProgress(imeBottomPx = 800)
        }
        assertTrue(state.imeAnimationRunning)

        clock.advanceBy(IME_ANIMATION_PROGRESS_IDLE_GRACE_MILLIS - 1L)
        assertTrue(state.imeAnimationRunning)

        clock.advanceBy(1L)
        assertFalse("stalled animation must expire after progress idle grace", state.imeAnimationRunning)

        // A late onProgress after timeout must NOT resurrect imeAnimationRunning.
        tracker.onProgress(imeBottomPx = 900)
        assertFalse(state.imeAnimationRunning)

        // A very late onEnd still reports timedOut = true with accurate telemetry.
        val summary = tracker.onEnd("show-1", durationMillis = 285L)
        assertNotNull(summary)
        assertTrue(summary!!.timedOut)
        assertEquals(600L + IME_ANIMATION_PROGRESS_IDLE_GRACE_MILLIS, summary.elapsedMs)
        assertEquals(31, summary.progressFrames)
        assertEquals(900, summary.maxProgressImePx)
        assertEquals(1158, summary.windowImePx)
        assertFalse(state.imeAnimationRunning)
    }

    @Test
    fun runawayProgressSpamStillExpiresAtAbsoluteHardCap() {
        val clock = FakeClockAndScheduler()
        val state = RaftHostImeInsets()
        val tracker = createTracker(clock, state)

        tracker.onPrepare("spam-1")
        tracker.onStart("spam-1", durationMillis = 285L)

        // Keep emitting progress frames every 50ms forever without onEnd.
        var elapsed = 0L
        while (elapsed < IME_ANIMATION_HARD_CAP_MILLIS - 50L) {
            clock.advanceBy(50L)
            elapsed += 50L
            tracker.onProgress(imeBottomPx = 600)
            assertTrue("should stay active before hard cap at elapsed=$elapsed", state.imeAnimationRunning)
        }

        clock.advanceBy(50L)
        assertFalse("must expire at hard cap even if progress keeps arriving", state.imeAnimationRunning)
        tracker.onProgress(imeBottomPx = 600)
        assertFalse(state.imeAnimationRunning)
    }

    @Test
    fun progressArrivingAtOrPastHardCapBeforeTimerFiresExpiresImmediately() {
        val clock = FakeClockAndScheduler()
        val state = RaftHostImeInsets()
        val tracker = createTracker(clock, state)

        tracker.onPrepare("cap-direct")
        tracker.onStart("cap-direct", durationMillis = 285L)

        // Simulate main-thread stall where clock jumps past hard cap before queued runnable fires.
        clock.nowMs += IME_ANIMATION_HARD_CAP_MILLIS
        tracker.onProgress(imeBottomPx = 500)
        assertFalse(state.imeAnimationRunning)
        assertNull(clock.scheduledAtMs)
    }

    @Test
    fun zeroAndHugeDurationMillisAreClampedWithoutOverflowOrHardCapBypass() {
        val clock = FakeClockAndScheduler()
        val state = RaftHostImeInsets()
        val tracker = createTracker(clock, state)

        // durationMillis = 0 still gets endGraceMillis.
        tracker.onStart("zero-1", durationMillis = 0L)
        assertTrue(state.imeAnimationRunning)
        clock.advanceBy(IME_ANIMATION_END_GRACE_MILLIS - 1L)
        assertTrue(state.imeAnimationRunning)
        clock.advanceBy(1L)
        assertFalse(state.imeAnimationRunning)

        // Huge OEM duration (Long.MAX_VALUE / 60_000L) must neither overflow nor bypass hardCapMillis.
        for (huge in listOf(60_000L, Long.MAX_VALUE)) {
            tracker.onStart("huge-$huge", durationMillis = huge)
            assertTrue("huge=$huge must stay active on start", state.imeAnimationRunning)
            clock.advanceBy(IME_ANIMATION_HARD_CAP_MILLIS - 1L)
            assertTrue("huge=$huge must stay active until hard cap", state.imeAnimationRunning)
            clock.advanceBy(1L)
            assertFalse("huge=$huge must expire at hard cap", state.imeAnimationRunning)
        }
    }

    @Test
    fun timedAnimationStartedDuringUserDrivenGesturePreservesFullInitialSpanWhenGestureEnds() {
        val clock = FakeClockAndScheduler()
        val state = RaftHostImeInsets()
        val tracker = createTracker(clock, state)

        tracker.onPrepare("gesture")
        tracker.onStart("gesture", durationMillis = -1L)
        assertNull(clock.scheduledAtMs)

        clock.advanceBy(500L)
        tracker.onPrepare("settle")
        tracker.onStart("settle", durationMillis = 400L)
        assertNull(clock.scheduledAtMs)

        clock.advanceBy(5L)
        assertNull(tracker.onEnd("gesture", durationMillis = -1L))
        assertTrue(state.imeAnimationRunning)
        // Settle started at t=1500 with duration 400 + endGrace 150 -> deadline must be t=2050,
        // NOT shortened to 1505 + 250 = 1755.
        assertEquals(1_000L + 500L + 400L + IME_ANIMATION_END_GRACE_MILLIS, clock.scheduledAtMs)
        clock.advanceTo(1_000L + 500L + 400L + IME_ANIMATION_END_GRACE_MILLIS - 1L)
        assertTrue(state.imeAnimationRunning)
        clock.advanceBy(1L)
        assertFalse(state.imeAnimationRunning)
    }

    @Test
    fun untrackedDuplicateAndPostBatchOnEndDoNotEmitBogusTimedOutTelemetry() {
        val clock = FakeClockAndScheduler()
        val state = RaftHostImeInsets()
        val tracker = createTracker(clock, state)

        // Never-prepared token while idle -> null (no bogus uptime elapsedMs / timedOut=true).
        assertNull(tracker.onEnd("stray", durationMillis = 285L))

        // Clean completion followed by duplicate onEnd -> second returns null.
        tracker.onPrepare("a")
        tracker.onStart("a", durationMillis = 285L)
        assertFalse(tracker.onEnd("a", durationMillis = 285L)!!.timedOut)
        assertNull(tracker.onEnd("a", durationMillis = 285L))

        // Batch 1 times out, then Batch 2 runs & completes cleanly -> late onEnd from Batch 1 is ignored.
        tracker.onPrepare("old")
        tracker.onStart("old", durationMillis = 285L)
        clock.advanceBy(285L + IME_ANIMATION_END_GRACE_MILLIS)
        assertFalse(state.imeAnimationRunning)

        tracker.onPrepare("fresh")
        tracker.onStart("fresh", durationMillis = 285L)
        assertFalse(tracker.onEnd("fresh", durationMillis = 285L)!!.timedOut)
        assertNull(tracker.onEnd("old", durationMillis = 285L))

        // Two overlapping animations both time out -> only the last late onEnd emits timedOut=true.
        tracker.onPrepare("ov-1")
        tracker.onStart("ov-1", durationMillis = 285L)
        tracker.onPrepare("ov-2")
        tracker.onStart("ov-2", durationMillis = 285L)
        clock.advanceBy(285L + IME_ANIMATION_END_GRACE_MILLIS)
        assertFalse(state.imeAnimationRunning)
        assertNull(tracker.onEnd("ov-1", durationMillis = 285L))
        assertTrue(tracker.onEnd("ov-2", durationMillis = 285L)!!.timedOut)
        assertNull(tracker.onEnd("ov-2", durationMillis = 285L))
    }

    @Test
    fun earlyProgressFramesDoNotShortenInitialDurationPlusEndGraceDeadline() {
        val clock = FakeClockAndScheduler()
        val state = RaftHostImeInsets()
        val tracker = createTracker(clock, state)

        tracker.onPrepare("normal-1")
        tracker.onStart("normal-1", durationMillis = 285L)
        val initialSchedules = clock.scheduleCalls

        // Progress frame at +10ms has now+250=260ms < 435ms: must not shorten or reschedule.
        clock.advanceBy(10L)
        tracker.onProgress(imeBottomPx = 200)
        assertEquals(initialSchedules, clock.scheduleCalls)

        // Even with no further progress, it stays alive until 285 + 150 = 435ms.
        clock.advanceTo(1_000L + 285L + IME_ANIMATION_END_GRACE_MILLIS - 1L)
        assertTrue(state.imeAnimationRunning)
        clock.advanceBy(1L)
        assertFalse(state.imeAnimationRunning)
    }

    @Test
    fun preparedAnimationThatNeverStartsExpiresAfterStartGrace() {
        val clock = FakeClockAndScheduler()
        val state = RaftHostImeInsets()
        val tracker = createTracker(clock, state)

        tracker.onPrepare("orphan-1")
        assertTrue(state.imeAnimationRunning)

        clock.advanceBy(IME_ANIMATION_START_GRACE_MILLIS - 1L)
        assertTrue(state.imeAnimationRunning)
        clock.advanceBy(1L)
        assertFalse(state.imeAnimationRunning)
    }

    @Test
    fun overlappingPrepareNearPriorHardCapGetsFreshStartGrace() {
        val clock = FakeClockAndScheduler()
        val state = RaftHostImeInsets()
        val tracker = createTracker(clock, state)

        tracker.onPrepare("first")
        tracker.onStart("first", durationMillis = 285L)
        repeat(59) {
            clock.advanceBy(50L)
            tracker.onProgress(imeBottomPx = 600)
        }
        // Now 50ms before first's hard cap; reversal prepares -> must get full startGraceMillis.
        tracker.onPrepare("reversal")
        clock.advanceBy(IME_ANIMATION_START_GRACE_MILLIS - 1L)
        assertTrue(state.imeAnimationRunning)
    }

    @Test
    fun userDrivenNegativeDurationIsNotTimedOutUntilOnEnd() {
        val clock = FakeClockAndScheduler()
        val state = RaftHostImeInsets()
        val tracker = createTracker(clock, state)

        tracker.onPrepare("gesture-1")
        tracker.onStart("gesture-1", durationMillis = -1L)
        assertNull(clock.scheduledAtMs)

        clock.advanceBy(10_000L)
        tracker.onProgress(imeBottomPx = 400)
        assertTrue(state.imeAnimationRunning)

        val summary = tracker.onEnd("gesture-1", durationMillis = -1L)
        assertNotNull(summary)
        assertFalse(summary!!.timedOut)
        assertFalse(state.imeAnimationRunning)
    }

    @Test
    fun overlappingReversalKeepsFlagAliveUntilLastAnimationEnds() {
        val clock = FakeClockAndScheduler()
        val state = RaftHostImeInsets()
        val tracker = createTracker(clock, state)

        tracker.onPrepare("hide-1")
        tracker.onStart("hide-1", durationMillis = 285L)
        clock.advanceBy(200L)
        tracker.onProgress(imeBottomPx = 500)

        // Show reverses hide while hide is still running.
        tracker.onPrepare("show-2")
        tracker.onStart("show-2", durationMillis = 285L)
        assertNull(tracker.onEnd("hide-1", durationMillis = 285L))
        assertTrue(state.imeAnimationRunning)

        clock.advanceBy(400L)
        tracker.onProgress(imeBottomPx = 1158)
        assertTrue(state.imeAnimationRunning)

        val summary = tracker.onEnd("show-2", durationMillis = 285L)
        assertNotNull(summary)
        assertFalse(summary!!.timedOut)
        assertFalse(state.imeAnimationRunning)
    }

    @Test
    fun disposeCancelsPendingTimerAndIgnoresLateCallbacks() {
        val clock = FakeClockAndScheduler()
        val state = RaftHostImeInsets()
        val tracker = createTracker(clock, state)

        tracker.onPrepare("show-1")
        tracker.onStart("show-1", durationMillis = 285L)
        assertNotNull(clock.scheduledAtMs)

        tracker.dispose()
        assertFalse(state.imeAnimationRunning)
        assertNull(clock.scheduledAtMs)

        // Late teardown callbacks after dispose must be complete no-ops.
        tracker.onPrepare("late")
        tracker.onStart("late", durationMillis = 285L)
        tracker.onProgress(imeBottomPx = 500)
        assertNull(tracker.onEnd("show-1", durationMillis = 285L))
        assertNull(tracker.onEnd("late", durationMillis = 285L))
        assertFalse(state.imeAnimationRunning)
        assertNull(clock.scheduledAtMs)
    }
}
