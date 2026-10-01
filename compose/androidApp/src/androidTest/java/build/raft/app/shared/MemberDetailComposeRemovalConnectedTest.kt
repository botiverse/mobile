package build.raft.app.shared

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import build.raft.app.MemberDetailRemovalFixtureBridge
import build.raft.app.MemberDetailScrollRegistryFixtureBridge
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
 * Gates (all hard-fail):
 *  - visit 1 establishes a non-zero accepted save, then a paced held-press
 *    drag produces user_frames while the press stays down;
 *  - BEFORE removal is requested, the last in-interval user_frame position
 *    must have NO same-key/gen/epoch accepted registry_save — otherwise the
 *    interleave was missed and the test fails as such;
 *  - after removal completes (dispose_save logged), the WHOLE old visit
 *    interval is re-audited: no accepted periodic write at the dispose
 *    position may appear between the interval start and the dispose line,
 *    and the dispose position must equal the last user_frame strictly
 *    before dispose in that interval;
 *  - re-add must mint a strictly newer tracker epoch and both target AND
 *    measurably land at the dispose position.
 *
 * The removal control is bound to a unique run token (passed via the
 * `slockVisualDisposeProbe` payload field), registered before launch,
 * toggled through the fixture bridge, and unregistered in finally — a stale
 * command can never reach a later host. The registry is never touched
 * between remove and re-add.
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

            // ---- Visit 1: non-zero baseline, then a held drag whose last
            // frame is measured but NOT yet periodically saved ----
            scenario = launchMemberDetailHost(runToken)
            val visit1 = waitForVisitSeed(startCursor, afterEpoch = 0)
            waitForLine(startCursor, after = visit1.seedLine, "restore_first_items") { line ->
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

            // Held press: paced MOVEs still streaming, no UP. The gesture
            // keeps producing user_frames while we request removal.
            var dragGesture: MemberDetailScrollTestSupport.HeldDrag? = null
            try {
                dragGesture = MemberDetailScrollTestSupport.injectDragPaced(TestLogTag)
                val lastFrame =
                    waitForLine(startCursor, after = visit1.seedLine, "user_frame") { line ->
                        line.contains("user_frame") && line.contains(ScrollLabel)
                    }
                val lastFramePos =
                    requireNotNull(parsePositionFrom(lastFrame)) {
                        "user_frame carried no position: $lastFrame"
                    }
                recordEvidence("CR_LAST_FRAME", "epoch=${visit1.epoch} $lastFrame")

                // Admission check only: the last measured position must not
                // already be persisted — otherwise the interleave is missed.
                // The authoritative re-audit runs AFTER dispose_save below.
                val alreadySaved =
                    linesAfter(startCursor, visit1.seedLine).any {
                        it.contains("registry_save") &&
                            it.contains("kind=humanList") &&
                            it.contains("decision=accept") &&
                            it.contains("keyHash=$KeyHash") &&
                            it.contains("gen=${visit1.generation}/") &&
                            Regex("epoch=${visit1.epoch}/${visit1.epoch}\\b").containsMatchIn(it) &&
                            it.contains("position=${lastFramePos.index}:${lastFramePos.offset}")
                    }
                if (alreadySaved) {
                    fail(
                        "interleave not hit: a periodic registry_save already " +
                            "wrote $lastFramePos before removal under epoch=${visit1.epoch}"
                    )
                }

                // Request removal through the fixture controller. The flip
                // goes through the real recomposition scheduler — the proof
                // of removal is the dispose_save line, not this call.
                MemberDetailRemovalFixtureBridge.setRemoved(runToken, true)
            } finally {
                // Retire the held press before removal can complete: a stuck
                // pointer must not reach a re-added subtree. Terminal event
                // delivery is hard-checked inside finish().
                dragGesture?.finish(action = MotionEvent.ACTION_CANCEL)
                dragGesture = null
            }

            // ---- Removal evidence: dispose_save at the LAST user_frame,
            // with no same-position periodic accept anywhere before it ----
            val disposeLine =
                waitForLine(startCursor, after = visit1.seedLine, "dispose_save") { line ->
                    line.contains("dispose_save") && line.contains(ScrollLabel)
                }
            val disposePosition =
                requireNotNull(parsePositionFrom(disposeLine)) {
                    "dispose_save carried no position: $disposeLine"
                }
            recordEvidence("CR_DISPOSE", "epoch=${visit1.epoch} $disposeLine")

            val intervalLines = linesAfter(startCursor, visit1.seedLine)
            val disposeIdx = intervalLines.indexOf(disposeLine)
            require(disposeIdx >= 0) { "dispose_save not located in visit-1 interval" }
            val throughDispose = intervalLines.take(disposeIdx + 1)

            // (a) dispose wrote the last user frame of this visit.
            val lastUserFrameBeforeDispose =
                throughDispose
                    .dropLast(1)
                    .filter { it.contains("user_frame") && it.contains(ScrollLabel) }
                    .mapNotNull(::parsePositionFrom)
                    .lastOrNull()
            require(disposePosition == lastUserFrameBeforeDispose) {
                "dispose_save wrote $disposePosition but the last in-interval user frame was $lastUserFrameBeforeDispose"
            }
            require(disposePosition.index > 0 || disposePosition.offset > 0) {
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
                            it.contains("position=${disposePosition.index}:${disposePosition.offset}")
                    }
            require(savesBeforeDispose.isEmpty()) {
                "interleave not hit: a periodic registry_save already wrote " +
                    "$disposePosition before dispose under epoch=${visit1.epoch}"
            }
            // (c) the dispose write landed: an accepted save at this visit's
            // gen+epoch carrying the dispose position, strictly after the
            // dispose line.
            waitForLine(startCursor, after = disposeLine, "accepted dispose write") { line ->
                line.contains("registry_save") &&
                    line.contains("kind=humanList") &&
                    line.contains("decision=accept") &&
                    line.contains("keyHash=$KeyHash") &&
                    Regex("epoch=${visit1.epoch}/${visit1.epoch}\\b").containsMatchIn(line) &&
                    line.contains("position=${disposePosition.index}:${disposePosition.offset}") &&
                    line.contains("gen=${visit1.generation}/")
            }.let { recordEvidence("CR_DISPOSE_WRITE", "epoch=${visit1.epoch} $it") }

            // ---- Visit 2 (re-add through composition, same host): new
            // epoch, restore targeted AND measured at the dispose position ----
            MemberDetailRemovalFixtureBridge.setRemoved(runToken, false)
            val visit2 = waitForVisitSeed(startCursor, afterEpoch = visit1.epoch)
            require(visit2.epoch > visit1.epoch) {
                "re-add did not mint a newer write epoch (${visit1.epoch} -> ${visit2.epoch})"
            }
            val firstItems2 =
                waitForLine(startCursor, after = visit2.seedLine, "v2 restore_first_items") { line ->
                    line.contains("restore_first_items") && line.contains(ScrollLabel)
                }
            require(
                firstItems2.contains("target=${disposePosition.index}:${disposePosition.offset}")
            ) {
                "v2 restore_first_items does not target dispose position $disposePosition: $firstItems2"
            }
            val revisitMeasured =
                Regex("current=(\\d+):(\\d+)").find(firstItems2)?.let { m ->
                    m.groupValues[1].toInt() == disposePosition.index &&
                        m.groupValues[2].toInt() == disposePosition.offset
                } == true
            if (!revisitMeasured) {
                waitForLine(startCursor, after = visit2.seedLine, "v2 measured landing") { line ->
                    line.contains("restore_settled") &&
                        line.contains(ScrollLabel) &&
                        line.contains("position=${disposePosition.index}:${disposePosition.offset}")
                }
            }
            recordEvidence("CR_REVISIT", "epoch=${visit2.epoch} $firstItems2")
        } catch (t: Throwable) {
            closeFailure = t
            throw t
        } finally {
            // Cleanup failures surface: a PASS cannot coexist with a live
            // controller, a live host, or a dirty registry. Registered first,
            // so unregister runs even when launch itself failed.
            var cleanupFailure: Throwable? = null
            try {
                scenario?.close()
            } catch (e: Throwable) {
                cleanupFailure = e
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

    private companion object {
        private const val TestLogTag = "MemberDetailComposeRemoval"
    }
}
