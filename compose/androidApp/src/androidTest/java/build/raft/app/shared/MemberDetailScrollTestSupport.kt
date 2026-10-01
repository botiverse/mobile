package build.raft.app.shared

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail

/**
 * Shared logcat/input support for the member-detail scroll connected tests
 * (task #58 B1 variants). Package-internal: the Activity-lifecycle case and
 * the composition-removal case share the same evidence vocabulary
 * (`MembersDetailScroll` tagged lines: tracker_seed / restore_first_items /
 * probe_hit / user_frame / registry_save / dispose_save / restore_settled).
 *
 * Log reads are timestamp-filtered to the run's start; any line whose
 * timestamp cannot be parsed is excluded from matching (fail-closed).
 */
internal object MemberDetailScrollTestSupport {

    internal const val ScrollLabel = "visual-server:human:visual-human-long:human"
    internal const val InitialRenderTimeoutMs = 20_000L
    internal const val ScenarioCloseTimeoutMs = 20_000L
    internal const val LogPollMs = 100L
    internal const val DragSteps = 10
    internal const val DragStepDelayMs = 36L
    internal const val DragRetryDelayMs = 400L
    internal const val ProbeHitWaitMs = 4_000L

    private const val MemberKey = "visual-owner:visual-server:human:visual-human-long"
    internal val KeyHash = MemberKey.hashCode().toString(16)

    internal data class ScrollPosition(val index: Int, val offset: Int)

    /** One visit's minted tracker_seed line plus its generation+epoch. */
    internal data class VisitSeed(val seedLine: String, val epoch: Long, val generation: Long)

    internal fun orderedLines(startCursor: Long, logTag: String): List<String> {
        var process: Process? = null
        return try {
            process =
                Runtime.getRuntime()
                    .exec(arrayOf("logcat", "-d", "-s", "MembersDetailScroll"))
            process.inputStream.bufferedReader().use { reader ->
                reader.readLines().filter { line ->
                    val ts = logcatTimestampMillis(line) ?: return@filter false
                    ts >= startCursor
                }
            }
        } catch (t: Throwable) {
            Log.w(logTag, "logcat read failed: ${t.message}")
            emptyList()
        } finally {
            process?.destroy()
        }
    }

    internal fun linesAfter(startCursor: Long, after: String, logTag: String): List<String> {
        val lines = orderedLines(startCursor, logTag)
        val idx = lines.lastIndexOf(after)
        return if (idx < 0) emptyList() else lines.subList(idx + 1, lines.size)
    }

    private fun logcatTimestampMillis(line: String): Long? {
        val match =
            Regex("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})").find(line)
                ?: return null
        val now = java.util.Calendar.getInstance()
        val cal =
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.MONTH, match.groupValues[1].toInt() - 1)
                set(java.util.Calendar.DAY_OF_MONTH, match.groupValues[2].toInt())
                set(java.util.Calendar.HOUR_OF_DAY, match.groupValues[3].toInt())
                set(java.util.Calendar.MINUTE, match.groupValues[4].toInt())
                set(java.util.Calendar.SECOND, match.groupValues[5].toInt())
                set(java.util.Calendar.MILLISECOND, match.groupValues[6].toInt())
                set(java.util.Calendar.YEAR, now.get(java.util.Calendar.YEAR))
            }
        return cal.timeInMillis
    }

    internal fun parsePositionFrom(line: String?): ScrollPosition? {
        if (line == null) return null
        val match = Regex("position=(\\d+):(\\d+)").find(line) ?: return null
        return ScrollPosition(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    /**
     * Wait for THIS visit's `tracker_seed`: the newest seed for the scroll
     * label whose `epoch=` strictly exceeds [afterEpoch]. Returns the exact
     * line so subsequent waits can gate on "after this visit's seed".
     */
    internal fun waitForVisitSeed(startCursor: Long, afterEpoch: Long, logTag: String): VisitSeed {
        val deadline = SystemClock.uptimeMillis() + InitialRenderTimeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val seed =
                orderedLines(startCursor, logTag)
                    .filter { it.contains("tracker_seed") && it.contains(ScrollLabel) }
                    .mapNotNull { line ->
                        val epoch =
                            Regex("epoch=(\\d+)").find(line)?.groupValues?.get(1)?.toLong()
                        val generation =
                            Regex("generation=(\\d+)").find(line)?.groupValues?.get(1)?.toLong()
                        if (epoch != null && epoch > afterEpoch) {
                            VisitSeed(line, epoch, generation ?: -1L)
                        } else {
                            null
                        }
                    }
                    .lastOrNull()
            if (seed != null) return seed
            SystemClock.sleep(LogPollMs)
        }
        throw AssertionError("Timed out waiting for tracker_seed epoch>$afterEpoch")
    }

    /** Latest line matching [match] strictly after [after]'s position. */
    internal fun waitForLine(
        startCursor: Long,
        after: String,
        description: String,
        logTag: String,
        match: (String) -> Boolean
    ): String {
        val deadline = SystemClock.uptimeMillis() + InitialRenderTimeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val line = linesAfter(startCursor, after, logTag).lastOrNull(match)
            if (line != null) return line
            SystemClock.sleep(LogPollMs)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    internal fun waitForSave(
        startCursor: Long,
        after: String,
        epoch: Long,
        logTag: String,
        requireNonZero: Boolean = false
    ): ScrollPosition {
        val line =
            waitForLine(startCursor, after, "accepted registry_save epoch=$epoch", logTag) { l ->
                l.contains("registry_save") &&
                    l.contains("kind=humanList") &&
                    l.contains("decision=accept") &&
                    l.contains("keyHash=$KeyHash") &&
                    Regex("epoch=$epoch/$epoch\\b").containsMatchIn(l) &&
                    (!requireNonZero ||
                        parsePositionFrom(l)?.let { it.index > 0 || it.offset > 0 } == true)
            }
        return requireNotNull(parsePositionFrom(line)) {
            "registry_save carried no position: $line"
        }
    }

    internal fun recordEvidence(logTag: String, key: String, value: String) {
        println("$key $value")
        Log.i(logTag, "$key $value")
    }

    /**
     * Blocking drag that retries the whole gesture until the in-page
     * `probe_hit` log appears after [after]: a `sendPointerSync` drop leaves
     * no `probe_hit` (where the input was lost is unproven) and an
     * unacknowledged drag would leave the baseline gate polling for a save
     * that can never arrive.
     */
    internal fun injectDragToScrollBlocking(startCursor: Long, after: String, logTag: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * 0.72f
        val endY = metrics.heightPixels * 0.30f
        val deadline = SystemClock.uptimeMillis() + InitialRenderTimeoutMs
        var attempt = 0
        while (SystemClock.uptimeMillis() < deadline) {
            attempt++
            // sendPointerSync blocks on the UiAutomation connection — a stuck
            // injector would hang the test thread past the loop bound, so each
            // attempt's dispatch runs on a worker under its own latch. The
            // worker checks `stop` between events and ALWAYS ends the gesture
            // with a terminal CANCEL in its own finally: on timeout we set the
            // flag instead of interrupting, then await the worker's real exit
            // so no leftover injector can still be writing MOVE/UP into the
            // next window (the gesture had ownership since before DOWN).
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val dispatchDone = CountDownLatch(1)
            val dispatchFailure = arrayOfNulls<Throwable>(1)
            val injector =
                Thread {
                    try {
                        dispatchBlockingDrag(instrumentation, x, startY, endY, stop, logTag)
                    } catch (t: Throwable) {
                        dispatchFailure[0] = t
                    } finally {
                        dispatchDone.countDown()
                    }
                }
            injector.isDaemon = true
            injector.start()
            if (!dispatchDone.await(ScenarioCloseTimeoutMs, TimeUnit.MILLISECONDS)) {
                // Stop cooperatively; the worker's finally retires the press
                // with CANCEL once the blocked call unwinds.
                stop.set(true)
                if (!dispatchDone.await(ScenarioCloseTimeoutMs, TimeUnit.MILLISECONDS)) {
                    val err =
                        AssertionError(
                            "baseline drag injector (attempt $attempt) did not exit " +
                                "within ${ScenarioCloseTimeoutMs}ms of stop; a live " +
                                "injector is still attached (isolation relies on the " +
                                "instrumentation process ending, which this fail does " +
                                "not prove)"
                        )
                    dispatchFailure[0]?.let(err::addSuppressed)
                    throw err
                }
                val err =
                    AssertionError(
                        "baseline drag dispatch (attempt $attempt) blocked " +
                            ">${ScenarioCloseTimeoutMs}ms"
                    )
                dispatchFailure[0]?.let(err::addSuppressed)
                throw err
            }
            dispatchFailure[0]?.let {
                throw AssertionError("baseline drag dispatch failed (attempt $attempt): ${it.message}", it)
            }
            val hit =
                linesAfter(startCursor, after, logTag).lastOrNull {
                    it.contains("probe_hit") && it.contains(ScrollLabel)
                }
            if (hit != null) {
                recordEvidence(logTag, "PROBE", "attempt=$attempt $hit")
                return
            }
            SystemClock.sleep(DragRetryDelayMs)
        }
        fail("baseline drag produced no probe_hit after $attempt attempt(s)")
    }

    /**
     * Dispatches DOWN + DragSteps MOVEs; checks [stop] before each event so
     * an aborted attempt cannot keep writing into a later window. The
     * gesture owns a press from the moment DOWN may have been sent, so a
     * terminal CANCEL (or UP on clean completion) is always attempted in
     * finally — delivery failure propagates, events recycle once.
     */
    private fun dispatchBlockingDrag(
        instrumentation: android.app.Instrumentation,
        x: Float,
        startY: Float,
        endY: Float,
        stop: java.util.concurrent.atomic.AtomicBoolean,
        logTag: String
    ) {
        val downTime = SystemClock.uptimeMillis()
        val events = mutableListOf<MotionEvent>()
        var downPossiblySent = false
        var cleanEnd = false
        var terminateFailure: Throwable? = null
        var primaryFailure: Throwable? = null
        try {
            if (stop.get()) return
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0)
            events += down
            downPossiblySent = true
            instrumentation.sendPointerSync(down)
            var lastY = startY
            repeat(DragSteps) { step ->
                if (stop.get()) return
                val fraction = (step + 1f) / DragSteps
                lastY = startY + (endY - startY) * fraction
                val move =
                    MotionEvent.obtain(
                        downTime,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE,
                        x,
                        lastY,
                        0
                    )
                events += move
                instrumentation.sendPointerSync(move)
            }
            if (stop.get()) return
            val up =
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    x,
                    endY,
                    0
                )
            events += up
            instrumentation.sendPointerSync(up)
            cleanEnd = true
        } catch (t: Throwable) {
            primaryFailure = t
            throw t
        } finally {
            if (downPossiblySent && !cleanEnd) {
                val cancel =
                    MotionEvent.obtain(
                        downTime,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_CANCEL,
                        x,
                        if (events.size > 1) endY else startY,
                        0
                    )
                try {
                    val delivered =
                        instrumentation.uiAutomation.injectInputEvent(cancel, false)
                    if (!delivered) {
                        terminateFailure =
                            AssertionError("gesture CANCEL was rejected by injectInputEvent")
                    }
                    Log.i(logTag, "baseline_drag_terminated cancelDelivered=$delivered")
                } catch (t: Throwable) {
                    terminateFailure = t
                    Log.w(logTag, "baseline_drag_terminate_failed: ${t.message}")
                } finally {
                    cancel.recycle()
                }
            }
            events.forEach(MotionEvent::recycle)
            terminateFailure?.let { term ->
                primaryFailure?.addSuppressed(term) ?: throw term
            }
        }
    }

    /**
     * Real-time-paced drag that leaves the press HELD: DOWN + spaced MOVEs
     * are injected non-blocking with ~[DragStepDelayMs] between events and
     * no UP is sent, so the gesture is still physically in flight (and
     * `isScrollInProgress` live) when the caller proceeds. The returned
     * handle releases or cancels the gesture on the failure path; terminal
     * delivery failure is a hard error.
     */
    internal fun injectDragPaced(logTag: String): HeldDrag {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * 0.72f
        val endY = metrics.heightPixels * 0.30f
        val downTime = SystemClock.uptimeMillis()
        var currentY = startY
        val drag =
            HeldDrag(
                downTime = downTime,
                x = x,
                instrumentation = instrumentation
            )
        fun dispatch(event: MotionEvent) {
            val accepted =
                instrumentation.uiAutomation.injectInputEvent(event, false)
            drag.track(event)
            if (!accepted) {
                throw AssertionError(
                    "injectInputEvent rejected action=${event.actionMasked}"
                )
            }
        }
        try {
            dispatch(
                MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0)
            )
            repeat(DragSteps) { step ->
                val fraction = (step + 1f) / DragSteps
                currentY = startY + (endY - startY) * fraction
                drag.updateY(currentY)
                dispatch(
                    MotionEvent.obtain(
                        downTime,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE,
                        x,
                        currentY,
                        0
                    )
                )
                Thread.sleep(DragStepDelayMs)
            }
        } catch (t: Throwable) {
            // A failed dispatch still owns the press until CANCEL lands —
            // a swallowed cancel failure would leave a stuck pointer, so it
            // rides along on the dispatch error instead.
            try {
                drag.finish(MotionEvent.ACTION_CANCEL)
            } catch (cancelFailure: Throwable) {
                t.addSuppressed(cancelFailure)
            }
            throw t
        }
        return drag
    }

    /**
     * Held-drag variant with delivery evidence: injects a paced held drag
     * and verifies the in-page `probe_hit` line appears after [after] —
     * the gesture provably engaged the real scroll surface. A dispatch
     * that lands before the (re)composed subtree's pointerInput attaches
     * produces no probe_hit; that attempt is retired with ACTION_CANCEL
     * and retried within the shared bound. Never returns a gesture that
     * produced no probe_hit.
     */
    internal fun injectDragPacedUntilProbeHit(
        startCursor: Long,
        after: String,
        logTag: String
    ): HeldDrag {
        val deadline = SystemClock.uptimeMillis() + InitialRenderTimeoutMs
        var attempt = 0
        while (SystemClock.uptimeMillis() < deadline) {
            attempt++
            Log.i(logTag, "held_drag_dispatch attempt=$attempt at=${SystemClock.uptimeMillis()}")
            // Attempt isolation: only probe_hit lines strictly AFTER this
            // attempt's snapshot count — a stale hit from an earlier attempt
            // can never satisfy a later dispatch.
            val lineCountBefore =
                linesAfter(startCursor, after, logTag).size
            var drag: HeldDrag? = null
            var primaryFailure: Throwable? = null
            var hit: String? = null
            try {
                drag = injectDragPaced(logTag)
                val probeDeadline =
                    minOf(SystemClock.uptimeMillis() + ProbeHitWaitMs, deadline)
                while (SystemClock.uptimeMillis() < probeDeadline) {
                    hit =
                        linesAfter(startCursor, after, logTag)
                            .drop(lineCountBefore)
                            .lastOrNull {
                                it.contains("probe_hit") && it.contains(ScrollLabel)
                            }
                    if (hit != null) break
                    SystemClock.sleep(LogPollMs)
                }
            } catch (t: Throwable) {
                primaryFailure = t
            }
            if (hit != null) {
                recordEvidence(logTag, "PROBE", "attempt=$attempt $hit")
                return requireNotNull(drag)
            }
            // Any non-hit outcome (no probe evidence, or an exception while
            // holding the press) retires this attempt's pointer before the
            // failure/ retry continues — at most one pointer is ever held.
            drag?.let { held ->
                try {
                    held.finish(MotionEvent.ACTION_CANCEL)
                    Log.i(
                        logTag,
                        "held_drag_retired attempt=$attempt cancel " +
                            "at=${SystemClock.uptimeMillis()} " +
                            "reason=${if (primaryFailure != null) "dispatch_error" else "no probe_hit"}"
                    )
                } catch (cancelFailure: Throwable) {
                    // Retirement failure on an already-failing attempt rides
                    // along; otherwise it is the attempt's failure.
                    primaryFailure?.addSuppressed(cancelFailure)
                        ?: run { primaryFailure = cancelFailure }
                }
            }
            primaryFailure?.let { throw it }
            SystemClock.sleep(DragRetryDelayMs)
        }
        throw AssertionError("held drag produced no probe_hit after $attempt attempt(s)")
    }

    /** A still-held injected gesture. The handle exists BEFORE the DOWN
     *  event so a failed/interrupted injection still leaves the caller owning
     *  a cleanup path. [finish] sends the terminal UP/CANCEL, FAILS when that
     *  event is rejected (a stuck press must not be silently tolerated), and
     *  recycles every queued event exactly once. */
    internal class HeldDrag(
        private val downTime: Long,
        private val x: Float,
        private val instrumentation: android.app.Instrumentation
    ) {
        private var closed = false
        private var currentY = 0f
        private val events = mutableListOf<MotionEvent>()

        fun track(event: MotionEvent) {
            events += event
        }

        fun updateY(value: Float) {
            currentY = value
        }

        fun finish(action: Int) {
            if (closed) return
            closed = true
            val terminal =
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, currentY, 0)
            try {
                val accepted =
                    instrumentation.uiAutomation.injectInputEvent(terminal, false)
                if (!accepted) {
                    throw AssertionError(
                        "terminal gesture event rejected (action=$action); press may still be held"
                    )
                }
            } finally {
                terminal.recycle()
                events.forEach(MotionEvent::recycle)
            }
        }
    }
}
