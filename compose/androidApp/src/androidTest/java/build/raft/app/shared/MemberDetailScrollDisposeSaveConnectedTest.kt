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
            // The previous run showed a DOWN that produced no `probe_hit` —
            // where the input was dropped is unproven (hypothesis: window not
            // yet focusable). Retry the drag until the in-page probe
            // acknowledges input; a bounded absence is a hard fail, not a
            // silent scroll miss.
            injectDragToScrollBlocking(startCursor, after = visit1.seedLine)
            // The baseline wait itself must keep polling until an ACCEPTED
            // NON-ZERO save appears — an initial 0:0 write (or a still-
            // clamped early frame) must not satisfy the gate.
            val baselineGate =
                waitForSave(
                    startCursor,
                    after = visit1.seedLine,
                    epoch = visit1.epoch,
                    requireNonZero = true
                )
            recordEvidence("B1_BASELINE", "epoch=${visit1.epoch} position=$baselineGate")
            val host1 = closeHostOrFail(scenario)
            // Host death has two required proofs at this point:
            //   (a) the specific host instance reached DESTROYED in the
            //       lifecycle monitor (finish() returning does NOT prove
            //       destruction);
            //   (b) the composition's onDispose ran (`dispose_save` intent)
            //       within this visit's interval.
            // The baseline `waitForSave` above only proves the PRE-close
            // periodic save persisted — it is NOT the dispose write's
            // accepted-receipt proof (visit 2 carries that gate).
            awaitHostDestroyed(host1)
            scenario = null
            val v1Dispose =
                waitForLine(startCursor, after = visit1.seedLine, "v1 dispose_save") { line ->
                    line.contains("dispose_save") && line.contains(ScrollLabel)
                }
            recordEvidence("B1_V1_DISPOSE", "epoch=${visit1.epoch} $v1Dispose")
            // The expected restore position is the LAST user-driven position
            // in flight at destroy time, not the baseline gate: drag saves
            // keep streaming during the close window, so the gate value can
            // be stale. Take the latest accepted registry_save in this
            // visit's interval strictly before dispose_save, scoped to this
            // visit's gen+epoch so a cross-visit line can never satisfy it.
            // If the interval lost every such save the expectation is
            // unprovable — hard fail rather than fall back to the dispose
            // position, which would mask an exit-time write bug.
            val v1Interval = linesAfter(startCursor, visit1.seedLine)
            val v1DisposeIdx = v1Interval.indexOf(v1Dispose)
            val v1BaselineRestore =
                requireNotNull(
                    v1Interval
                        .take(v1DisposeIdx)
                        .filter {
                            it.contains("registry_save") &&
                                it.contains("kind=humanList") &&
                                it.contains("decision=accept") &&
                                it.contains("keyHash=$KeyHash") &&
                                it.contains("gen=${visit1.generation}/") &&
                                Regex("epoch=${visit1.epoch}/${visit1.epoch}\\b")
                                    .containsMatchIn(it)
                        }
                        .mapNotNull(::parsePositionFrom)
                        .lastOrNull()
                ) {
                    "no accepted in-interval registry_save precedes v1 dispose_save; restore expectation unprovable"
                }
            recordEvidence(
                "B1_V1_RESTORE_EXPECT",
                "epoch=${visit1.epoch} position=$v1BaselineRestore"
            )

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
                firstItems2.contains(
                    "target=${v1BaselineRestore.index}:${v1BaselineRestore.offset}"
                ) && firstItems2.contains("wantsRestore=true")
            ) {
                "v2 restore_first_items does not target baseline $v1BaselineRestore: $firstItems2"
            }
            // Baseline must have actually landed, not merely been targeted:
            // either the seed frame measured current=target, or a
            // restore_settled within this visit interval lands at it.
            val seedLandedAtBaseline =
                Regex("current=(\\d+):(\\d+)").find(firstItems2)?.let { m ->
                    m.groupValues[1].toInt() == v1BaselineRestore.index &&
                        m.groupValues[2].toInt() == v1BaselineRestore.offset
                } == true
            if (!seedLandedAtBaseline) {
                waitForLine(startCursor, after = visit2.seedLine, "v2 baseline settle") { line ->
                    line.contains("restore_settled") &&
                        line.contains(ScrollLabel) &&
                        line.contains("position=${v1BaselineRestore.index}:${v1BaselineRestore.offset}")
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
                val host2 = closeHostOrFail(scenario)
                // Same proof (a) as visit 1: this specific host instance
                // must reach DESTROYED. dispose_save + accepted write gates
                // follow below.
                awaitHostDestroyed(host2)
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
            // Cleanup failure is NOT swallowed: if the case already failed we
            // attach it as suppressed; otherwise a surviving host fails the
            // test on its own — a PASS must never coexist with a live host.
            try {
                scenario?.let {
                    var cleanupFailure: Throwable? = null
                    val host =
                        try {
                            closeHostOrFail(it)
                        } catch (e: Throwable) {
                            cleanupFailure = e
                            null
                        }
                    if (host != null) {
                        try {
                            awaitHostDestroyed(host)
                        } catch (e: Throwable) {
                            if (cleanupFailure == null) cleanupFailure = e else cleanupFailure!!.addSuppressed(e)
                        }
                    }
                    cleanupFailure?.let { e ->
                        if (closeFailure != null) {
                            closeFailure!!.addSuppressed(e)
                        } else {
                            throw AssertionError("host cleanup failed: ${e.message}", e)
                        }
                    }
                }
            } finally {
                // Registry clear must run even when the cleanup-failure throw
                // above fires — otherwise a live-host failure would also
                // leave the dedicated key behind and poison other cases.
                MemberDetailScrollRegistryFixtureBridge.clearForTest()
            }
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
     * `Instrumentation.waitForIdleSync()` before issuing the destroy, and a
     * page that keeps the main looper busy can stall it past any close
     * timeout. (Hypothesis for the first instrumentation-run hang — a busy
     * main looper is consistent with the observed stall but NOT proven as
     * its root cause.) `finish()` queues the real lifecycle destroy without
     * that idle precondition.
     *
     * Destruction is then proven by [awaitHostDestroyed] plus the
     * visit-scoped `dispose_save` log line, not by this call returning:
     * callers always follow with the matching dispose wait, and a host
     * whose composition never disposes fails that wait.
     */
    /**
     * Issues `finish()` on the scenario's activity and returns the specific
     * host instance, which the caller must pass to [awaitHostDestroyed] —
     * the DESTROYED proof is bound to that instance, not to generic stage
     * emptiness.
     */
    private fun closeHostOrFail(
        scenario: ActivityScenario<SlockKuiklyActivity>
    ): SlockKuiklyActivity {
        Log.i(
            TestLogTag,
            "close_begin thread=${Thread.currentThread().name}"
        )
        // onActivity itself blocks until the runnable executes on the main
        // thread, so the bounded await must wrap the CALL, not just the
        // finish() inside it — run the whole onActivity on a worker thread.
        val finished = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)
        val hostRef = arrayOfNulls<SlockKuiklyActivity>(1)
        val issuer =
            Thread {
                try {
                    scenario.onActivity { activity ->
                        hostRef[0] = activity
                        activity.finish()
                    }
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
        return requireNotNull(hostRef[0]) { "onActivity ran but captured no host" }
    }

    /**
     * Proof (a) of host death: the SPECIFIC host Activity instance must be
     * observed in Stage.DESTROYED by the lifecycle monitor. Absence from a
     * subset of stages is not a DESTROYED receipt (a STOPPED/RESTARTED host
     * would otherwise pass), so the exact instance captured at close time
     * is matched explicitly.
     *
     * Monitor queries must run on the main thread, so each poll is
     * dispatched through `runOnMainSync` and the whole wait stays bounded
     * by [ScenarioCloseTimeoutMs]. Fail-closed on timeout.
     */
    private fun awaitHostDestroyed(host: SlockKuiklyActivity) {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val deadline = SystemClock.uptimeMillis() + ScenarioCloseTimeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val destroyed = queryStageOnMain(monitor, Stage.DESTROYED).contains(host)
            if (destroyed) {
                Log.i(TestLogTag, "host_destroyed proof=lifecycle stage=DESTROYED")
                return
            }
            SystemClock.sleep(LogPollMs)
        }
        val stage = currentStageOnMain(monitor, host)
        fail("SlockKuiklyActivity never reached DESTROYED within ${ScenarioCloseTimeoutMs}ms (stage=$stage)")
    }

    /**
     * Runs a monitor stage query on the main thread, as required. Uses
     * `Handler(Looper.getMainLooper()).post` rather than `runOnMainSync`
     * so a jammed main looper cannot block the test thread past the latch
     * bound — the query failing to dispatch is itself a failure.
     */
    private fun queryStageOnMain(
        monitor: androidx.test.runner.lifecycle.ActivityLifecycleMonitor,
        stage: Stage
    ): Set<android.app.Activity> {
        val result = arrayOfNulls<Set<android.app.Activity>>(1)
        val queryFailure = arrayOfNulls<Throwable>(1)
        val latch = CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                result[0] = monitor.getActivitiesInStage(stage).toSet()
            } catch (t: Throwable) {
                queryFailure[0] = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(ScenarioCloseTimeoutMs, TimeUnit.MILLISECONDS)) {
            fail("lifecycle monitor query for $stage did not run on main within ${ScenarioCloseTimeoutMs}ms")
        }
        queryFailure[0]?.let { throw AssertionError("lifecycle monitor query for $stage threw: ${it.message}", it) }
        return result[0] ?: emptySet()
    }

    private fun currentStageOnMain(
        monitor: androidx.test.runner.lifecycle.ActivityLifecycleMonitor,
        host: android.app.Activity
    ): String {
        for (stage in Stage.values()) {
            if (queryStageOnMain(monitor, stage).contains(host)) return stage.name
        }
        return "UNTRACKED"
    }

    // -- Input -------------------------------------------------------------------

    /**
     * Blocking drag used to establish the baseline (visit 1). Retries the
     * whole gesture until the in-page `probe_hit` log appears after
     * [after]: a `sendPointerSync` drop leaves no `probe_hit` (where the
     * input was lost is unproven) and an unacknowledged drag would leave
     * the baseline gate polling for a save that can never arrive.
     */
    private fun injectDragToScrollBlocking(startCursor: Long, after: String) {
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
                        dispatchBlockingDrag(instrumentation, x, startY, endY, stop)
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
                    // The injector is still alive — say so explicitly (the
                    // process boundary isolates it, but a test fail is not
                    // proof the process exited) and preserve whatever
                    // terminal failure the worker recorded.
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
                linesAfter(startCursor, after).lastOrNull {
                    it.contains("probe_hit") && it.contains(ScrollLabel)
                }
            if (hit != null) {
                recordEvidence("B1_PROBE", "attempt=$attempt $hit")
                return
            }
            SystemClock.sleep(DragRetryDelayMs)
        }
        fail("baseline drag produced no probe_hit after $attempt attempt(s)")
    }

    /**
     * Dispatches DOWN + DragSteps MOVEs; checks [stop] before each event so
     * an aborted attempt cannot keep writing into a later window. The
     * gesture owns a press from the moment DOWN is sent, so a terminal
     * CANCEL (or UP on clean completion) is always attempted in finally —
     * delivery result is logged, events recycle exactly once on the worker.
     */
    private fun dispatchBlockingDrag(
        instrumentation: android.app.Instrumentation,
        x: Float,
        startY: Float,
        endY: Float,
        stop: java.util.concurrent.atomic.AtomicBoolean
    ) {
        val downTime = SystemClock.uptimeMillis()
        val events = mutableListOf<MotionEvent>()
        // Owned from BEFORE the DOWN call: sendPointerSync can throw after
        // the OS already accepted the event, so "may have been sent" — not
        // "call returned" — is the ownership boundary for press retirement.
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
            // If DOWN may have gone out but the gesture did not complete,
            // retire the press with CANCEL — a stuck pointer must never
            // survive an aborted attempt. CANCEL failure is NOT swallowed:
            // it propagates to the caller (suppressed onto the in-flight
            // error when one exists), and the cancel event always recycles.
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
                    Log.i(TestLogTag, "baseline_drag_terminated cancelDelivered=$delivered")
                } catch (t: Throwable) {
                    terminateFailure = t
                    Log.w(TestLogTag, "baseline_drag_terminate_failed: ${t.message}")
                } finally {
                    cancel.recycle()
                }
            }
            events.forEach(MotionEvent::recycle)
            // CANCEL failure must not mask or be masked by the gesture's own
            // error: attach it to the in-flight exception, or surface it as
            // the failure when the gesture body itself completed abruptly
            // without throwing (stop-flag return).
            terminateFailure?.let { term ->
                primaryFailure?.addSuppressed(term) ?: throw term
            }
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
        private const val DragRetryDelayMs = 400L
    }
}
