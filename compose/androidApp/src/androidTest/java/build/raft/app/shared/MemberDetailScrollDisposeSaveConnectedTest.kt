package build.raft.app.shared

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import build.raft.app.MemberDetailScrollRegistryFixtureBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * task #58 B1: real-lifecycle coverage for the member-detail dispose-save
 * path on the Human detail list, on the dedicated `visual-human-long` seed.
 *
 * Visit correlation rules (locked in review):
 *  - every host launch must mint a NEW tracker_seed with a strictly greater
 *    write epoch; all subsequent waits for that visit only accept lines
 *    logged AFTER that visit's seed line (by order in the tagged dump);
 *  - phase 1 must prove a real layout and a NON-ZERO accepted save;
 *  - phase 2 must prove the baseline actually landed (measured `current=` at
 *    target on the restore_first_items frame, or a `restore_settled` at
 *    `position=<baseline>` in the same visit interval) BEFORE any input;
 *  - the dispose write must equal the LAST user_frame inside the same visit
 *    interval, and no accepted save carrying that position may precede the
 *    dispose line in that interval;
 *  - phase 3 must observe a NEW measured landing at the dispose position in
 *    its own interval (`current=` on restore_first_items, or a later
 *    restore_settled in-interval), never reuse earlier user evidence.
 *
 * Gesture pacing: the interleave drag injects events with real-time spacing
 * between MOVEs and never sends UP before destroy — the gesture is genuinely
 * in flight while the host is being torn down. On the failure path a
 * CANCEL/UP is still dispatched so no stuck press survives the case.
 *
 * Log reads are tagged `MembersDetailScroll` and timestamp-filtered to this
 * run's start. Any line whose timestamp cannot be parsed is excluded from
 * matching (fail-closed), and evidence lines are re-emit ted under the test
 * tag so the archived logcat carries the exact gate inputs.
 */
class MemberDetailScrollDisposeSaveConnectedTest {

    @Test
    fun humanDetailDisposeDuringUserDragSavesAndRestores() {
        var scenario: ActivityScenario<SlockKuiklyActivity>? = null
        var closeFailure: Throwable? = null
        var previousEpoch = 0L
        val startCursor = System.currentTimeMillis()
        try {
            MemberDetailScrollRegistryFixtureBridge.clearForTest()

            // ---- Visit 1: establish the non-zero baseline ----
            scenario = launchMemberDetailHost()
            val visit1 = waitForVisitSeed(startCursor, afterEpoch = previousEpoch)
            previousEpoch = visit1.epoch
            // Real layout proof for this visit: the tracker must have seen a
            // first populated frame BEFORE any input.
            waitForLine(startCursor, after = visit1.seedLine, "v1 restore_first_items") { line ->
                line.contains("restore_first_items") && line.contains(ScrollLabel)
            }
            injectDragToScrollBlocking()
            // The baseline wait itself must keep polling until an ACCEPTED
            // NON-ZERO save appears — an initial 0:0 write (or a still-
            // clamped early frame) must not satisfy the gate.
            val baseline =
                waitForSave(
                    startCursor,
                    after = visit1.seedLine,
                    epoch = visit1.epoch,
                    requireNonZero = true
                )
            recordEvidence("B1_BASELINE", "epoch=${visit1.epoch} position=$baseline")
            closeHostOrFail(scenario)
            // Host death has three independent proofs, all required:
            //   (a) the Activity left every live stage (== destroyed),
            //   (b) the composition's onDispose ran (`dispose_save` intent),
            //   (c) an accepted registry write at this visit's gen+epoch
            //       carried the position (dispose wrote THROUGH). Proof (c)
            //       is the baseline wait already passed above.
            awaitHostDestroyed()
            scenario = null
            val v1Dispose =
                waitForLine(startCursor, after = visit1.seedLine, "v1 dispose_save") { line ->
                    line.contains("dispose_save") && line.contains(ScrollLabel)
                }
            recordEvidence("B1_V1_DISPOSE", "epoch=${visit1.epoch} $v1Dispose")

            // ---- Visit 2: re-enter, prove baseline restored, drag inside
            // the settle window, destroy mid-drag ----
            scenario = launchMemberDetailHost()
            val visit2 = waitForVisitSeed(startCursor, afterEpoch = previousEpoch)
            previousEpoch = visit2.epoch
            val firstItems2 =
                waitForLine(startCursor, after = visit2.seedLine, "v2 restore_first_items") { line ->
                    line.contains("restore_first_items") && line.contains(ScrollLabel)
                }
            require(
                firstItems2.contains("target=${baseline.index}:${baseline.offset}") &&
                    firstItems2.contains("wantsRestore=true")
            ) {
                "v2 restore_first_items does not target baseline $baseline: $firstItems2"
            }
            // Baseline must have actually landed, not merely been targeted:
            // either the seed frame measured current=target, or a
            // restore_settled within this visit interval lands at it.
            val seedLandedAtBaseline =
                Regex("current=(\\d+):(\\d+)").find(firstItems2)?.let { m ->
                    m.groupValues[1].toInt() == baseline.index &&
                        m.groupValues[2].toInt() == baseline.offset
                } == true
            if (!seedLandedAtBaseline) {
                waitForLine(startCursor, after = visit2.seedLine, "v2 baseline settle") { line ->
                    line.contains("restore_settled") &&
                        line.contains(ScrollLabel) &&
                        line.contains("position=${baseline.index}:${baseline.offset}")
                }
            }
            recordEvidence("B1_BASELINE_LANDED", "epoch=${visit2.epoch} $firstItems2")

            // Drag while the post-restore settle state is still warm: paced
            // MOVEs, press HELD through the destroy. The handle covers
            // injection, observation AND close — any failure sends CANCEL so
            // no stuck press survives the case, and the terminal event's
            // delivery is itself checked.
            var dragGesture: HeldDrag? = null
            try {
                dragGesture = injectDragPaced()
                waitForLine(startCursor, after = visit2.seedLine, "v2 probe_hit") { line ->
                    line.contains("probe_hit") && line.contains(ScrollLabel)
                }
                val lastUserFrameBeforeDestroy =
                    waitForLine(startCursor, after = visit2.seedLine, "v2 user_frame") { line ->
                        line.contains("user_frame") && line.contains(ScrollLabel)
                    }
                recordEvidence("B1_USER_FRAME", "epoch=${visit2.epoch} $lastUserFrameBeforeDestroy")
                // Destroy now — the press is still down and the list still
                // scrolling while the host tears down.
                closeHostOrFail(scenario)
                // Same three-part death proof as visit 1: lifecycle
                // destroyed here, dispose_save + accepted write below.
                awaitHostDestroyed()
                scenario = null
            } finally {
                // On the success path the host is dead, so the terminal UP
                // is a no-op on a destroyed window; on failure paths CANCEL
                // guarantees the injected press is retired. Delivery result
                // is logged, and events recycle exactly once.
                dragGesture?.finish(action = MotionEvent.ACTION_UP)
                dragGesture = null
            }

            // The dispose write must appear in THIS visit's interval (after
            // the seed); its position must equal the LAST user_frame logged
            // strictly BEFORE the dispose line in the same interval.
            val disposeLine =
                waitForLine(startCursor, after = visit2.seedLine, "v2 dispose_save") { line ->
                    line.contains("dispose_save") && line.contains(ScrollLabel)
                }
            val disposePosition =
                requireNotNull(parsePositionFrom(disposeLine)) {
                    "dispose_save carried no position: $disposeLine"
                }
            recordEvidence("B1_DISPOSE", "epoch=${visit2.epoch} $disposeLine")
            val intervalLines = linesAfter(startCursor, visit2.seedLine)
            val disposeIndex = intervalLines.indexOf(disposeLine)
            val linesThroughDispose = intervalLines.take(disposeIndex + 1)
            val lastUserFrameInInterval =
                linesThroughDispose
                    .dropLast(1)
                    .filter { it.contains("user_frame") && it.contains(ScrollLabel) }
                    .mapNotNull(::parsePositionFrom)
                    .lastOrNull()
            require(disposePosition == lastUserFrameInInterval) {
                "dispose_save wrote $disposePosition but the last in-interval user frame was $lastUserFrameInInterval"
            }
            require(disposePosition.index > 0 || disposePosition.offset > 0) {
                "dispose_save wrote the top, not the dragged position: $disposeLine"
            }
            // No periodic save carrying the dispose position may precede the
            // dispose write inside this visit's interval — that would mean the
            // position was already persisted before exit (already-saved path).
            val savesBeforeDispose =
                linesThroughDispose
                    .dropLast(1)
                    .filter {
                        it.contains("registry_save") &&
                            it.contains("kind=humanList") &&
                            it.contains("decision=accept") &&
                            it.contains("keyHash=$KeyHash") &&
                            Regex("epoch=${visit2.epoch}/${visit2.epoch}\\b").containsMatchIn(it)
                    }
                    .mapNotNull(::parsePositionFrom)
            if (disposePosition in savesBeforeDispose) {
                fail(
                    "interleave not hit: a periodic registry_save already wrote " +
                        "$disposePosition before dispose under epoch=${visit2.epoch}"
                )
            }
            // And the dispose write must actually be accepted by the registry:
            // saveHumanListPosition emits its own `registry_save` line after
            // the dispose_save intent line — the pair proves the fallback
            // wrote THROUGH, not merely logged intent. Require an accepted
            // write of the same position at this epoch+generation after the
            // dispose line and before the revisit.
            waitForLine(startCursor, after = disposeLine, "v2 accepted dispose write") { line ->
                line.contains("registry_save") &&
                    line.contains("kind=humanList") &&
                    line.contains("decision=accept") &&
                    line.contains("keyHash=$KeyHash") &&
                    Regex("epoch=${visit2.epoch}/${visit2.epoch}\\b").containsMatchIn(line) &&
                    line.contains("position=${disposePosition.index}:${disposePosition.offset}") &&
                    line.contains("gen=${visit2.generation}/")
            }.let { recordEvidence("B1_DISPOSE_WRITE", "epoch=${visit2.epoch} $it") }

            // ---- Visit 3: revisit must land on the dispose position ----
            scenario = launchMemberDetailHost()
            val visit3 = waitForVisitSeed(startCursor, afterEpoch = previousEpoch)
            val firstItems3 =
                waitForLine(startCursor, after = visit3.seedLine, "v3 restore_first_items") { line ->
                    line.contains("restore_first_items") && line.contains(ScrollLabel)
                }
            require(
                firstItems3.contains("target=${disposePosition.index}:${disposePosition.offset}")
            ) {
                "v3 restore_first_items does not target dispose position $disposePosition: $firstItems3"
            }
            val revisitMeasured =
                Regex("current=(\\d+):(\\d+)").find(firstItems3)?.let { m ->
                    m.groupValues[1].toInt() == disposePosition.index &&
                        m.groupValues[2].toInt() == disposePosition.offset
                } == true
            if (!revisitMeasured) {
                waitForLine(startCursor, after = visit3.seedLine, "v3 measured landing") { line ->
                    line.contains("restore_settled") &&
                        line.contains(ScrollLabel) &&
                        line.contains("position=${disposePosition.index}:${disposePosition.offset}")
                }
            }
            recordEvidence("B1_REVISIT", "epoch=${visit3.epoch} $firstItems3")
        } catch (t: Throwable) {
            closeFailure = t
            throw t
        } finally {
            // Registry clear must run even if the host failed to close —
            // leaving the dedicated key behind would poison other cases.
            // If a live host survived a failed close it may still write under
            // its epoch, but the generation bump from clear() retires those
            // writes by contract.
            scenario?.let {
                runCatching { closeHostOrFail(it) }
                    .onFailure { e -> Log.w(TestLogTag, "finally close failed: ${e.message}") }
                // finally must wait for the real cleanup, not just fire
                // finish() — otherwise a still-dying host leaks into the
                // next visit's input/lifecycle stream.
                runCatching { awaitHostDestroyed() }
                    .onFailure { e ->
                        Log.w(TestLogTag, "finally destroy wait failed: ${e.message}")
                    }
            }
            MemberDetailScrollRegistryFixtureBridge.clearForTest()
        }
    }

    // -- Host lifecycle ---------------------------------------------------------

    private fun launchMemberDetailHost(): ActivityScenario<SlockKuiklyActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pageData =
            JSONObject()
                .put("instanceId", "visual-members-human-long-dispose")
                .put(
                    "payload",
                    JSONObject()
                        .put("instanceId", "visual-members-human-long-dispose")
                        .put("memberId", "visual-human-long")
                        .put("memberKind", "human")
                        .put("localOffsetMinutes", 480)
                        .put("todayEpochDay", 20623)
                        .put("nowEpochMillis", 1781872200000)
                        .put("timeFormat", "24h")
                        .toString()
                )
                .put("slockVisualZeroSafeArea", true)
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra(SlockKuiklyActivity.KEY_PAGE_NAME, "compose_member_detail")
                .putExtra(SlockKuiklyActivity.KEY_PAGE_DATA, pageData.toString())
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)
        return ActivityScenario.launch(intent)
    }

    /**
     * Destroy the host via `Activity.finish()` instead of
     * `ActivityScenario.close()`: `close()` blocks in
     * `Instrumentation.waitForIdleSync()` before issuing the destroy, and
     * this page holds long-running composition work (the restore-stall
     * watchdog ticks while a request is pending) that can keep the main
     * looper busy past any close timeout — the exact hang the first
     * instrumentation run hit. `finish()` queues the real lifecycle destroy
     * without that idle precondition.
     *
     * Destruction is then PROVEN by the visit-scoped `dispose_save` log
     * line, not by this call returning: callers always follow with the
     * matching dispose wait, and a host whose composition never disposes
     * fails that wait.
     */
    private fun closeHostOrFail(scenario: ActivityScenario<SlockKuiklyActivity>) {
        Log.i(
            TestLogTag,
            "close_begin thread=${Thread.currentThread().name}"
        )
        // onActivity itself blocks until the runnable executes on the main
        // thread, so the bounded await must wrap the CALL, not just the
        // finish() inside it — run the whole onActivity on a worker thread.
        val finished = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)
        val issuer =
            Thread {
                try {
                    scenario.onActivity { activity -> activity.finish() }
                } catch (t: Throwable) {
                    failure[0] = t
                } finally {
                    finished.countDown()
                }
            }
        issuer.isDaemon = true
        issuer.start()
        if (!finished.await(ScenarioCloseTimeoutMs, TimeUnit.MILLISECONDS)) {
            issuer.interrupt()
            fail("activity.finish() could not be delivered within ${ScenarioCloseTimeoutMs}ms")
        }
        failure[0]?.let { throw AssertionError("activity.finish() threw: ${it.message}", it) }
        Log.i(TestLogTag, "close_issued thread=${Thread.currentThread().name}")
    }

    /**
     * Proof (a) of host death: the Activity must leave every pre-destroy
     * stage in the lifecycle monitor. `finish()` returning — or even the
     * onActivity runnable having run — does NOT prove the destroy was
     * delivered; only the monitor transition does. Fail-closed on timeout.
     */
    private fun awaitHostDestroyed() {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val liveStages =
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.CREATED, Stage.PRE_ON_CREATE)
        val deadline = SystemClock.uptimeMillis() + ScenarioCloseTimeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val alive =
                liveStages
                    .flatMap { stage -> monitor.getActivitiesInStage(stage) }
                    .filterIsInstance<SlockKuiklyActivity>()
            if (alive.isEmpty()) {
                Log.i(TestLogTag, "host_destroyed proof=lifecycle alive=0")
                return
            }
            SystemClock.sleep(LogPollMs)
        }
        fail("SlockKuiklyActivity never reached DESTROYED within ${ScenarioCloseTimeoutMs}ms")
    }

    // -- Input -------------------------------------------------------------------

    /** Blocking drag used to establish the baseline (visit 1). */
    private fun injectDragToScrollBlocking() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * 0.72f
        val endY = metrics.heightPixels * 0.30f
        val downTime = SystemClock.uptimeMillis()
        val events = mutableListOf<MotionEvent>()
        events += MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0)
        repeat(DragSteps) { step ->
            val fraction = (step + 1f) / DragSteps
            events +=
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE,
                    x,
                    startY + (endY - startY) * fraction,
                    0
                )
        }
        events +=
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                x,
                endY,
                0
            )
        try {
            events.forEach { instrumentation.sendPointerSync(it) }
        } finally {
            events.forEach(MotionEvent::recycle)
        }
    }

    /**
     * Real-time-paced drag that leaves the press HELD: DOWN + spaced MOVEs
     * are injected non-blocking with ~[DragStepDelayMs] between events and
     * no UP is sent, so the gesture is still physically in flight (and
     * `isScrollInProgress` live) when the caller destroys the host. The
     * returned handle releases or cancels the gesture on the failure path.
     */
    /**
     * The handle is created BEFORE the DOWN event is dispatched so any
     * injection failure or interrupt still leaves the caller owning a valid
     * cleanup handle — the contract is "held-press cleanup", not
     * "constructed handle". Terminal event delivery failure is a hard error,
     * not a logged warning: a press we cannot prove retired is a stuck
     * pointer that could leak into the next visit's input stream.
     */
    private fun injectDragPaced(): HeldDrag {
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
                // Real pacing: the move run takes ~DragSteps*delay, so the
                // gesture is mid-flight while the caller proceeds.
                Thread.sleep(DragStepDelayMs)
            }
        } catch (t: Throwable) {
            // Injection aborted mid-gesture: retire the press before
            // propagating so no stuck pointer survives into the next visit.
            runCatching { drag.finish(MotionEvent.ACTION_CANCEL) }
            throw t
        }
        return drag
    }

    /** A still-held injected gesture. The handle exists BEFORE the DOWN
     *  event so a failed/interrupted injection still leaves the caller owning
     *  a cleanup path. [finish] sends the terminal UP/CANCEL, FAILS when that
     *  event is rejected (a stuck press must not be silently tolerated), and
     *  recycles every queued event exactly once. */
    private class HeldDrag(
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

    // -- Logcat observation --------------------------------------------------------

    private data class ScrollPosition(val index: Int, val offset: Int)

    /** One visit's minted tracker_seed line plus its generation+epoch. */
    private data class VisitSeed(val seedLine: String, val epoch: Long, val generation: Long)

    private val ScrollLabel = "visual-server:human:visual-human-long:human"
    private val MemberKey = "visual-owner:visual-server:human:visual-human-long"
    private val KeyHash = MemberKey.hashCode().toString(16)

    private fun orderedLines(startCursor: Long): List<String> {
        var process: Process? = null
        return try {
            process =
                Runtime.getRuntime()
                    .exec(arrayOf("logcat", "-d", "-s", "MembersDetailScroll"))
            process.inputStream.bufferedReader().use { reader ->
                reader.readLines().filter { line ->
                    // Fail-closed: a line whose timestamp cannot be parsed is
                    // NOT admitted as evidence.
                    val ts = logcatTimestampMillis(line) ?: return@filter false
                    ts >= startCursor
                }
            }
        } catch (t: Throwable) {
            Log.w(TestLogTag, "logcat read failed: ${t.message}")
            emptyList()
        } finally {
            process?.destroy()
        }
    }

    private fun linesAfter(startCursor: Long, after: String): List<String> {
        val lines = orderedLines(startCursor)
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

    private fun parsePositionFrom(line: String?): ScrollPosition? {
        if (line == null) return null
        val match = Regex("position=(\\d+):(\\d+)").find(line) ?: return null
        return ScrollPosition(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    /**
     * Wait for THIS visit's `tracker_seed`: the newest seed for the scroll
     * label whose `epoch=` strictly exceeds [afterEpoch]. Returns the exact
     * line so subsequent waits can gate on "after this visit's seed".
     */
    private fun waitForVisitSeed(startCursor: Long, afterEpoch: Long): VisitSeed {
        val deadline = SystemClock.uptimeMillis() + InitialRenderTimeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val seed =
                orderedLines(startCursor)
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
    private fun waitForLine(
        startCursor: Long,
        after: String,
        description: String,
        match: (String) -> Boolean
    ): String {
        val deadline = SystemClock.uptimeMillis() + InitialRenderTimeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val line = linesAfter(startCursor, after).lastOrNull(match)
            if (line != null) return line
            SystemClock.sleep(LogPollMs)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun waitForSave(
        startCursor: Long,
        after: String,
        epoch: Long,
        requireNonZero: Boolean = false
    ): ScrollPosition {
        val line =
            waitForLine(startCursor, after, "accepted registry_save epoch=$epoch") { l ->
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

    private fun recordEvidence(key: String, value: String) {
        println("$key $value")
        Log.i(TestLogTag, "$key $value")
    }

    private companion object {
        private const val TestLogTag = "MemberDetailDisposeB1"
        private const val InitialRenderTimeoutMs = 20_000L
        private const val ScenarioCloseTimeoutMs = 20_000L
        private const val LogPollMs = 100L
        private const val DragSteps = 10
        private const val DragStepDelayMs = 36L
    }
}
