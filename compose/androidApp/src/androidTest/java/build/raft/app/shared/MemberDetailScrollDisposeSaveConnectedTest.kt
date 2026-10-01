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
            var dragGesture: MemberDetailScrollTestSupport.HeldDrag? = null
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
        if (!finished.await(MemberDetailScrollTestSupport.ScenarioCloseTimeoutMs, TimeUnit.MILLISECONDS)) {
            issuer.interrupt()
            fail("activity.finish() could not be delivered within ${MemberDetailScrollTestSupport.ScenarioCloseTimeoutMs}ms")
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
     * by [MemberDetailScrollTestSupport.ScenarioCloseTimeoutMs]. Fail-closed on timeout.
     */
    private fun awaitHostDestroyed(host: SlockKuiklyActivity) {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val deadline = SystemClock.uptimeMillis() + MemberDetailScrollTestSupport.ScenarioCloseTimeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val destroyed = queryStageOnMain(monitor, Stage.DESTROYED).contains(host)
            if (destroyed) {
                Log.i(TestLogTag, "host_destroyed proof=lifecycle stage=DESTROYED")
                return
            }
            SystemClock.sleep(MemberDetailScrollTestSupport.LogPollMs)
        }
        val stage = currentStageOnMain(monitor, host)
        fail("SlockKuiklyActivity never reached DESTROYED within ${MemberDetailScrollTestSupport.ScenarioCloseTimeoutMs}ms (stage=$stage)")
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
        if (!latch.await(MemberDetailScrollTestSupport.ScenarioCloseTimeoutMs, TimeUnit.MILLISECONDS)) {
            fail("lifecycle monitor query for $stage did not run on main within ${MemberDetailScrollTestSupport.ScenarioCloseTimeoutMs}ms")
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

    // Shared logcat/input helpers live in MemberDetailScrollTestSupport;
    // thin aliases keep the scenario body readable.
    private val ScrollLabel get() = MemberDetailScrollTestSupport.ScrollLabel
    private val KeyHash get() = MemberDetailScrollTestSupport.KeyHash

    private fun orderedLines(startCursor: Long) =
        MemberDetailScrollTestSupport.orderedLines(startCursor, TestLogTag)

    private fun linesAfter(startCursor: Long, after: String) =
        MemberDetailScrollTestSupport.linesAfter(startCursor, after, TestLogTag)

    private fun parsePositionFrom(line: String?) =
        MemberDetailScrollTestSupport.parsePositionFrom(line)

    private fun waitForVisitSeed(startCursor: Long, afterEpoch: Long) =
        MemberDetailScrollTestSupport.waitForVisitSeed(startCursor, afterEpoch, TestLogTag)

    private fun waitForLine(
        startCursor: Long,
        after: String,
        description: String,
        match: (String) -> Boolean
    ) = MemberDetailScrollTestSupport.waitForLine(startCursor, after, description, TestLogTag, match)

    private fun waitForSave(
        startCursor: Long,
        after: String,
        epoch: Long,
        requireNonZero: Boolean = false
    ) = MemberDetailScrollTestSupport.waitForSave(startCursor, after, epoch, TestLogTag, requireNonZero)

    private fun recordEvidence(key: String, value: String) =
        MemberDetailScrollTestSupport.recordEvidence(TestLogTag, key, value)

    private fun injectDragToScrollBlocking(startCursor: Long, after: String) =
        MemberDetailScrollTestSupport.injectDragToScrollBlocking(startCursor, after, TestLogTag)

    private fun injectDragPaced() =
        MemberDetailScrollTestSupport.injectDragPaced(TestLogTag)
    private companion object {
        private const val TestLogTag = "MemberDetailDisposeB1"
    }
}
