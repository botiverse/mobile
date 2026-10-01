package build.raft.app.shared

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import build.raft.app.MemberDetailRemovalFixtureBridge
import build.raft.app.MemberDetailScrollRegistryFixtureBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test

/**
 * task #58 composition-removal variant of the member-detail dispose-save
 * coverage, on the `visual-human-long` seed.
 *
 * Scope (locked in review): this exercises the REAL production path — the
 * detail subtree leaves composition through the normal recomposition
 * scheduler, running the production `DisposableEffect(memberScrollKey,
 * scrollIdentityReady).onDispose` which calls
 * `MemberDetailScrollStateRegistry.saveHumanListPosition`, and the re-added
 * subtree reads the position back through `key(memberScrollKey)` on a
 * strictly newer write epoch. The host Activity stays alive throughout;
 * this is NOT a native Activity-exit/lifecycle sample (that remains
 * separately uncovered).
 *
 * Sequence (the production state machine requires it): `user_frame` is only
 * emitted inside `restoreSettlePending && scrollInProgress && userScrollActive`,
 * which needs `wantsRestore=true` — i.e. a visit seeded from a NON-ZERO saved
 * position. So the case is:
 *   V1: fresh seed (0:0), baseline drag, non-zero accepted save; removal is
 *       then requested and the V2 restore expectation is derived from the
 *       LAST accepted in-interval write before v1 dispose_save (the periodic
 *       saver may advance past the earlier gate during the async remove);
 *   V2 (remove → re-add): new epoch seeded at that position, wantsRestore=true,
 *       then a paced held-press drag during the settle window produces
 *       user_frames; the last measured position must have NO same-key/gen/epoch
 *       accepted periodic save (admission check), and after the actual
 *       dispose_save the full V2 interval is re-audited for that write being
 *       the FIRST accepted persist of the dispose position;
 *   V3 (re-add): strictly newer epoch, restore targeted AND measured at the
 *       dispose position.
 *
 * Removal control is bound to a unique run token (`slockVisualDisposeProbe`
 * payload field), registered before launch, toggled through the fixture
 * bridge, and unregistered in finally — a stale command can never reach a
 * later host. The registry/generation/key is never touched between remove
 * and re-add. The held gesture keeps ownership through the entire dispose
 * wait; the finally retires it with a checked CANCEL on every path, and a
 * CANCEL failure on an already-failing path attaches to the original error
 * instead of masking it.
 */
class MemberDetailComposeRemovalConnectedTest {

    private val ScrollLabel get() = MemberDetailScrollTestSupport.ScrollLabel
    private val KeyHash get() = MemberDetailScrollTestSupport.KeyHash

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

    @Test
    fun humanDetailComposeRemovalDuringUserDragSavesAndRestores() {
        var scenario: ActivityScenario<SlockKuiklyActivity>? = null
        var closeFailure: Throwable? = null
        val runToken = "b1-removal-${SystemClock.uptimeNanos()}"
        val startCursor = System.currentTimeMillis()
        try {
            MemberDetailScrollRegistryFixtureBridge.clearForTest()
            MemberDetailRemovalFixtureBridge.register(runToken)

            // ---- V1: fresh seed, baseline drag, non-zero accepted save ----
            scenario = launchMemberDetailHost(runToken)
            val visit1 = waitForVisitSeed(startCursor, afterEpoch = 0)
            waitForLine(startCursor, after = visit1.seedLine, "v1 restore_first_items") { line ->
                line.contains("restore_first_items") && line.contains(ScrollLabel)
            }
            MemberDetailScrollTestSupport.injectDragToScrollBlocking(
                startCursor, visit1.seedLine, TestLogTag
            )
            val baseline =
                waitForSave(
                    startCursor,
                    after = visit1.seedLine,
                    epoch = visit1.epoch,
                    requireNonZero = true
                )
            recordEvidence("CR_BASELINE", "epoch=${visit1.epoch} position=$baseline")

            // ---- V2: composition remove → re-add (new epoch seeded at
            // baseline, wantsRestore=true opens the settle window) ----
            MemberDetailRemovalFixtureBridge.setRemoved(runToken, true)
            val v1Dispose =
                waitForLine(startCursor, after = visit1.seedLine, "v1 dispose_save") { line ->
                    line.contains("dispose_save") && line.contains(ScrollLabel)
                }
            // The expected V2 restore position is the LAST accepted
            // in-interval write before v1 dispose — the baseline gate value
            // can be stale because the periodic saver keeps running until
            // recomposition actually removes the content. Same derivation
            // as the Activity-lifecycle case; if no such write exists the
            // expectation is unprovable — hard fail.
            val v1Interval = linesAfter(startCursor, visit1.seedLine)
            val v1DisposeIdx = v1Interval.indexOf(v1Dispose)
            require(v1DisposeIdx >= 0) { "v1 dispose_save not located in v1 interval" }
            val v1RestoreExpect =
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
            recordEvidence("CR_V1_RESTORE_EXPECT", "epoch=${visit1.epoch} position=$v1RestoreExpect")
            MemberDetailRemovalFixtureBridge.setRemoved(runToken, false)
            val visit2 = waitForVisitSeed(startCursor, afterEpoch = visit1.epoch)
            require(visit2.epoch > visit1.epoch) {
                "re-add did not mint a newer write epoch (${visit1.epoch} -> ${visit2.epoch})"
            }
            val firstItems2 =
                waitForLine(startCursor, after = visit2.seedLine, "v2 restore_first_items") { line ->
                    line.contains("restore_first_items") && line.contains(ScrollLabel)
                }
            // The V2 seed must both target the saved baseline and actually
            // open the settle window (wantsRestore=true) — without it no
            // user_frame can be produced and the interleave is unreach­able.
            require(
                firstItems2.contains("target=${v1RestoreExpect.index}:${v1RestoreExpect.offset}") &&
                    firstItems2.contains("wantsRestore=true")
            ) {
                "v2 restore_first_items missing restore target $v1RestoreExpect or wantsRestore: $firstItems2"
            }
            val seedLandedAtExpect =
                Regex("current=(\\d+):(\\d+)").find(firstItems2)?.let { m ->
                    m.groupValues[1].toInt() == v1RestoreExpect.index &&
                        m.groupValues[2].toInt() == v1RestoreExpect.offset
                } == true
            if (!seedLandedAtExpect) {
                waitForLine(startCursor, after = visit2.seedLine, "v2 baseline settle") { line ->
                    line.contains("restore_settled") &&
                        line.contains(ScrollLabel) &&
                        line.contains("position=${v1RestoreExpect.index}:${v1RestoreExpect.offset}")
                }
            }
            recordEvidence("CR_V2_LANDED", "epoch=${visit2.epoch} $firstItems2")

            // Held press inside the settle window: paced MOVEs, no UP. The
            // gesture keeps producing user_frames while removal is requested.
            var dragGesture: MemberDetailScrollTestSupport.HeldDrag? = null
            // The gesture stays owned through the ENTIRE dispose wait: the
            // finally retires it with a checked CANCEL whether the interleave
            // succeeded, removal never happened, or the log line never came.
            var gestureRetired = false
            var disposePosition: MemberDetailScrollTestSupport.ScrollPosition? = null
            var disposeLineForAudit: String? = null
            var gesturePrimaryFailure: Throwable? = null
            try {
                dragGesture = MemberDetailScrollTestSupport.injectDragPaced(TestLogTag)
                val lastFrame =
                    waitForLine(startCursor, after = visit2.seedLine, "v2 user_frame") { line ->
                        line.contains("user_frame") && line.contains(ScrollLabel)
                    }
                val lastFramePos =
                    requireNotNull(parsePositionFrom(lastFrame)) {
                        "user_frame carried no position: $lastFrame"
                    }
                recordEvidence("CR_LAST_FRAME", "epoch=${visit2.epoch} $lastFrame")

                // Admission check: the last measured position must not
                // already be persisted in V2 — otherwise the interleave is
                // missed and removal is pointless. The authoritative audit
                // runs after the actual dispose_save below.
                val alreadySaved =
                    linesAfter(startCursor, visit2.seedLine).any {
                        it.contains("registry_save") &&
                            it.contains("kind=humanList") &&
                            it.contains("decision=accept") &&
                            it.contains("keyHash=$KeyHash") &&
                            it.contains("gen=${visit2.generation}/") &&
                            Regex("epoch=${visit2.epoch}/${visit2.epoch}\\b").containsMatchIn(it) &&
                            it.contains("position=${lastFramePos.index}:${lastFramePos.offset}")
                    }
                if (alreadySaved) {
                    fail(
                        "interleave not hit: a periodic registry_save already " +
                            "wrote $lastFramePos before removal under epoch=${visit2.epoch}"
                    )
                }

                // Request removal; keep the gesture OWNED — terminal CANCEL
                // only runs after the actual dispose_save is observed (or on
                // the failure path). Holding the press does NOT block the
                // periodic saver; the post-dispose full-interval audit below
                // is what rules out a sneaked-in same-position write.
                MemberDetailRemovalFixtureBridge.setRemoved(runToken, true)

                val disposeLine =
                    waitForLine(startCursor, after = visit2.seedLine, "v2 dispose_save") { line ->
                        line.contains("dispose_save") && line.contains(ScrollLabel)
                    }
                disposePosition =
                    requireNotNull(parsePositionFrom(disposeLine)) {
                        "dispose_save carried no position: $disposeLine"
                    }
                disposeLineForAudit = disposeLine
                recordEvidence("CR_DISPOSE", "epoch=${visit2.epoch} $disposeLine")
            } catch (t: Throwable) {
                gesturePrimaryFailure = t
                throw t
            } finally {
                if (!gestureRetired) {
                    gestureRetired = true
                    try {
                        dragGesture?.finish(action = MotionEvent.ACTION_CANCEL)
                        dragGesture = null
                    } catch (cancelFailure: Throwable) {
                        val primary = gesturePrimaryFailure
                        if (primary != null) {
                            // Already failing: the stuck-pointer error rides
                            // along instead of masking the real failure.
                            primary.addSuppressed(cancelFailure)
                        } else {
                            // Success path: gesture retirement failure is
                            // itself the failure.
                            throw cancelFailure
                        }
                    }
                }
            }

            val disposeLine =
                requireNotNull(disposeLineForAudit) { "dispose_save was never observed" }
            val disposePos =
                requireNotNull(disposePosition) { "dispose_save carried no position" }
            val intervalLines = linesAfter(startCursor, visit2.seedLine)
            val disposeIdx = intervalLines.indexOf(disposeLine)
            require(disposeIdx >= 0) { "dispose_save not located in v2 interval" }
            val throughDispose = intervalLines.take(disposeIdx + 1)

            // (a) dispose wrote the last user frame of this visit.
            val lastUserFrameBeforeDispose =
                throughDispose
                    .dropLast(1)
                    .filter { it.contains("user_frame") && it.contains(ScrollLabel) }
                    .mapNotNull(::parsePositionFrom)
                    .lastOrNull()
            require(disposePos == lastUserFrameBeforeDispose) {
                "dispose_save wrote $disposePos but the last in-interval user frame was $lastUserFrameBeforeDispose"
            }
            require(disposePos.index > 0 || disposePos.offset > 0) {
                "dispose_save wrote the top, not the dragged position: $disposeLine"
            }
            // (b) no periodic write carried that position inside the
            // interval before dispose — the dispose write is what persisted
            // the final position.
            val savesBeforeDispose =
                throughDispose
                    .dropLast(1)
                    .filter {
                        it.contains("registry_save") &&
                            it.contains("kind=humanList") &&
                            it.contains("decision=accept") &&
                            it.contains("keyHash=$KeyHash") &&
                            it.contains("position=${disposePos.index}:${disposePos.offset}")
                    }
            require(savesBeforeDispose.isEmpty()) {
                "interleave not hit: a periodic registry_save already wrote " +
                    "$disposePos before dispose under epoch=${visit2.epoch}"
            }
            // (c) the dispose write landed: an accepted save at this visit's
            // gen+epoch carrying the dispose position, strictly after the
            // dispose line.
            waitForLine(startCursor, after = disposeLine, "v2 accepted dispose write") { line ->
                line.contains("registry_save") &&
                    line.contains("kind=humanList") &&
                    line.contains("decision=accept") &&
                    line.contains("keyHash=$KeyHash") &&
                    Regex("epoch=${visit2.epoch}/${visit2.epoch}\\b").containsMatchIn(line) &&
                    line.contains("position=${disposePos.index}:${disposePos.offset}") &&
                    line.contains("gen=${visit2.generation}/")
            }.let { recordEvidence("CR_DISPOSE_WRITE", "epoch=${visit2.epoch} $it") }

            // ---- V3 (re-add through composition, same host): strictly newer
            // epoch, restore targeted AND measured at the dispose position ----
            MemberDetailRemovalFixtureBridge.setRemoved(runToken, false)
            val visit3 = waitForVisitSeed(startCursor, afterEpoch = visit2.epoch)
            require(visit3.epoch > visit2.epoch) {
                "re-add did not mint a newer write epoch (${visit2.epoch} -> ${visit3.epoch})"
            }
            val firstItems3 =
                waitForLine(startCursor, after = visit3.seedLine, "v3 restore_first_items") { line ->
                    line.contains("restore_first_items") && line.contains(ScrollLabel)
                }
            require(
                firstItems3.contains("target=${disposePos.index}:${disposePos.offset}")
            ) {
                "v3 restore_first_items does not target dispose position $disposePos: $firstItems3"
            }
            val revisitMeasured =
                Regex("current=(\\d+):(\\d+)").find(firstItems3)?.let { m ->
                    m.groupValues[1].toInt() == disposePos.index &&
                        m.groupValues[2].toInt() == disposePos.offset
                } == true
            if (!revisitMeasured) {
                waitForLine(startCursor, after = visit3.seedLine, "v3 measured landing") { line ->
                    line.contains("restore_settled") &&
                        line.contains(ScrollLabel) &&
                        line.contains("position=${disposePos.index}:${disposePos.offset}")
                }
            }
            recordEvidence("CR_REVISIT", "epoch=${visit3.epoch} $firstItems3")
        } catch (t: Throwable) {
            closeFailure = t
            throw t
        } finally {
            // Bounded host cleanup: scenario.close() can stall in
            // waitForIdleSync, so the host is destroyed via finish() + the
            // specific instance's DESTROYED receipt. Controller unregistration
            // and registry clear run regardless (nested), and any cleanup
            // failure surfaces — a PASS cannot coexist with a live host, a
            // registered controller, or a dirty registry.
            var cleanupFailure: Throwable? = null
            scenario?.let { sc ->
                val host =
                    try {
                        closeHostOrFail(sc)
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
            }
            try {
                MemberDetailRemovalFixtureBridge.unregister(runToken)
            } catch (e: Throwable) {
                if (cleanupFailure == null) cleanupFailure = e else cleanupFailure!!.addSuppressed(e)
            }
            try {
                MemberDetailScrollRegistryFixtureBridge.clearForTest()
            } catch (e: Throwable) {
                if (cleanupFailure == null) cleanupFailure = e else cleanupFailure!!.addSuppressed(e)
            }
            cleanupFailure?.let { e ->
                if (closeFailure != null) {
                    closeFailure!!.addSuppressed(e)
                } else {
                    throw AssertionError("cleanup failed: ${e.message}", e)
                }
            }
        }
    }

    // -- Host lifecycle ---------------------------------------------------------

    private fun launchMemberDetailHost(runToken: String): ActivityScenario<SlockKuiklyActivity> {
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
                        .put("slockVisualDisposeProbe", runToken)
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
     * Bounded host teardown for cleanup: `finish()` on a worker under a
     * latch, never `scenario.close()` (whose waitForIdleSync can outlast any
     * test bound). Returns the specific host instance for the DESTROYED
     * proof.
     */
    private fun closeHostOrFail(
        scenario: ActivityScenario<SlockKuiklyActivity>
    ): SlockKuiklyActivity {
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
        return requireNotNull(hostRef[0]) { "onActivity ran but captured no host" }
    }

    /**
     * The specific host instance must reach Stage.DESTROYED in the lifecycle
     * monitor; queries run on the main thread (posted, not runOnMainSync, so
     * a jammed looper cannot outlast the latch).
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

    private companion object {
        private const val TestLogTag = "MemberDetailComposeRemoval"
    }
}
