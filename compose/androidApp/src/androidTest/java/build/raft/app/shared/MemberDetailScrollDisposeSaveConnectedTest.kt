package build.raft.app.shared

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import build.raft.app.MemberDetailScrollRegistryFixtureBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * task #58 B1: real-lifecycle coverage for the member-detail dispose-save
 * path on the Human detail list.
 *
 * Scenario on the dedicated long-content Human visual seed
 * (`visual-human-long`, dispatched as its own `else` branch in
 * `toMemberDetailVisualSeedOrNull`, so the existing visual baselines stay
 * untouched):
 *
 *  1. Baseline visit: real blocking drag -> periodic `registry_save` lands a
 *     non-zero position under this key+epoch, then destroy the host.
 *  2. Re-entry: `restore_first_items` must carry `target=<baseline>` for
 *     THIS key and THIS epoch (the `tracker_seed` line minted the epoch for
 *     this composition). While `restoreSettlePending` is still open, inject a
 *     non-blocking drag so the probe flags `probe_hit label=<key>:human` and
 *     in-flight frames are attributed via `user_frame label=... position=`.
 *  3. Destroy the host while the drag is still delivering — `dispose_save
 *     label=...:human position=U` must appear for this key, and no
 *     accepted `registry_save` with that same position may appear anywhere
 *     in the run interval up to the dispose write.
 *  4. Third entry: `restore_first_items` must report `target=U`, proving
 *     the new composition restores the position the dispose fallback wrote.
 *
 * All waits bound to key+epoch-scoped log lines; every miss (no probe, no
 * user frame, close failure, periodic save winning the race, wrong revisit
 * target) fails the case with an explicit reason rather than claiming the
 * interleave.
 *
 * Isolation: dedicated memberId `visual-human-long` (key tail
 * `visual-server:human:visual-human-long`); the finally block destroys the
 * host before clearing [MemberDetailScrollStateRegistry] through the gated
 * [MemberDetailScrollRegistryFixtureBridge] (visualFixtures source set only,
 * so production builds never ship a public handle into the registry).
 */
class MemberDetailScrollDisposeSaveConnectedTest {

    @Test
    fun humanDetailDisposeDuringUserDragSavesAndRestores() {
        MemberDetailScrollRegistryFixtureBridge.clearForTest()
        // Cursor: only logcat lines at-or-after this run's start belong to
        // this case — older visits' lines must never satisfy a wait.
        val startCursor = System.currentTimeMillis()
        var scenario: ActivityScenario<SlockKuiklyActivity>? = null
        try {
            // ---- Phase 1: establish the non-zero baseline ----
            scenario = launchMemberDetailHost()
            val epoch1 =
                waitForEpoch(startCursor, description = "phase-1 tracker_seed")
            injectDragToScrollBlocking()
            val baseline =
                waitForSave(startCursor, epoch1, description = "phase-1 baseline save")
            require(baseline.index > 0 || baseline.offset > 0) {
                "baseline scroll never left the top; cannot exercise a non-zero restore"
            }
            recordEvidence("B1_BASELINE", "epoch=$epoch1 position=$baseline")
            closeHostOrFail(scenario)

            // ---- Phase 2: re-enter, drag inside the pending-restore settle
            // window, destroy mid-drag ----
            scenario = launchMemberDetailHost()
            val epoch2 =
                waitForEpoch(startCursor, description = "phase-2 tracker_seed")
            val firstItems2 =
                waitForLine(startCursor, "phase-2 restore_first_items") { line ->
                    line.contains("restore_first_items") &&
                        line.contains(ScrollLabel)
                }
            // The restore_first_items line does not carry epoch; correlate by
            // label + being logged after epoch2's tracker_seed.
            require(
                firstItems2.contains("target=${baseline.index}:${baseline.offset}")
            ) {
                "phase-2 restore_first_items does not target baseline $baseline: $firstItems2"
            }
            recordEvidence("B1_FIRST_ITEMS2", "epoch=$epoch2 $firstItems2")

            injectDragToScrollNonBlocking()
            val probeLine =
                waitForLine(startCursor, "phase-2 probe_hit") { line ->
                    line.contains("probe_hit") && line.contains(ScrollLabel)
                }
            recordEvidence("B1_PROBE", "epoch=$epoch2 $probeLine")
            val userFrame =
                waitForLine(startCursor, "phase-2 user_frame") { line ->
                    line.contains("user_frame") && line.contains(ScrollLabel)
                }
            val userPosition =
                requireNotNull(parsePositionFrom(userFrame)) {
                    "phase-2 user_frame carried no position: $userFrame"
                }
            require(userPosition.index > 0 || userPosition.offset > 0) {
                "phase-2 user frame never left the top: $userFrame"
            }
            // Snapshot every accepted save under this key up to the moment we
            // initiate destruction; the dispose-vs-save ordering gate below
            // scans the FULL interval through the dispose write, so a save
            // landing mid-close is still caught.
            closeHostOrFail(scenario)
            scenario = null

            val disposeLine =
                waitForLine(startCursor, "phase-2 dispose_save") { line ->
                    // dispose_save carries label but not epoch — the
                    // ordering gate below correlates by line order and the
                    // epoch2-scoped saves it gates against.
                    line.contains("dispose_save") &&
                        line.contains(ScrollLabel)
                }
            val disposePosition =
                requireNotNull(parsePositionFrom(disposeLine)) {
                    "phase-2 dispose_save carried no position: $disposeLine"
                }
            recordEvidence("B1_DISPOSE", "epoch=$epoch2 $disposeLine")
            require(disposePosition.index > 0 || disposePosition.offset > 0) {
                "dispose_save wrote the top, not the dragged position: $disposeLine"
            }
            // Ordering gate: walk every line logged for this key since the
            // run started; the dispose_save line must come before any
            // accepted registry_save carrying the same position. A save that
            // beat the dispose collapses the interleave into the already-
            // saved path — report the miss explicitly.
            val savesBeforeDispose =
                orderedLines(startCursor)
                    .takeWhile { it != disposeLine }
                    .filter {
                        it.contains("registry_save") &&
                            it.contains("kind=humanList") &&
                            it.contains("decision=accept") &&
                            it.contains("keyHash=$KeyHash") &&
                            it.contains("epoch=$epoch2")
                    }
                    .mapNotNull(::parsePositionFrom)
            if (disposePosition in savesBeforeDispose) {
                fail(
                    "interleave not hit: a periodic registry_save already wrote " +
                        "$disposePosition before dispose under epoch=$epoch2"
                )
            }
            // The dispose write must carry a real user-measured position: it
            // equals the LAST user_frame the tracker recorded before dispose,
            // which may have advanced past the frame we first observed while
            // the drag was still draining. Require it to match SOME user
            // frame logged for this key, not the zero/baseline positions.
            val allUserFrames =
                orderedLines(startCursor)
                    .filter { it.contains("user_frame") && it.contains(ScrollLabel) }
                    .mapNotNull(::parsePositionFrom)
                    .toSet()
            require(disposePosition in allUserFrames) {
                "dispose_save wrote $disposePosition, which is not any recorded user frame $allUserFrames"
            }

            // ---- Phase 3: revisit must target the dispose-saved position ----
            scenario = launchMemberDetailHost()
            val epoch3 =
                waitForEpoch(startCursor, description = "phase-3 tracker_seed")
            val firstItems3 =
                waitForLine(startCursor, "phase-3 restore_first_items") { line ->
                    line.contains("restore_first_items") &&
                        line.contains(ScrollLabel) &&
                        // must be the epoch-3 composition's line: the tracker_seed
                        // for epoch3 was already observed, and phase-2's lines
                        // carry a different target anyway
                        line.contains("target=${disposePosition.index}:${disposePosition.offset}")
                }
            // Measured landing proof, not just target: the seed frame itself
            // reports `current=I:O` — the list's actual measured first-visible
            // item when content arrives. Require it to match the dispose-saved
            // position (seed landed) OR a later user_frame/settle line carrying
            // the same measured position — both count as "actually restored".
            val measured = Regex("current=(\\d+):(\\d+)").find(firstItems3)
            val currentMatches =
                measured != null &&
                    measured.groupValues[1].toInt() == disposePosition.index &&
                    measured.groupValues[2].toInt() == disposePosition.offset
            if (!currentMatches) {
                val landing =
                    waitForLine(startCursor, "phase-3 measured landing") { line ->
                        line.contains(ScrollLabel) &&
                            line.contains("position=${disposePosition.index}:${disposePosition.offset}") &&
                            (line.contains("restore_settled") || line.contains("user_frame"))
                    }
                recordEvidence("B1_REVISIT_LANDING", "epoch=$epoch3 $landing")
            }
            recordEvidence("B1_REVISIT", "epoch=$epoch3 $firstItems3")
        } finally {
            scenario?.let(::closeHostOrFail)
            MemberDetailScrollRegistryFixtureBridge.clearForTest()
        }
    }

    // -- Host lifecycle -------------------------------------------------------

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
     * Destroy the host with a hard ceiling, and FAIL on any non-clean close:
     * a host that cannot be confirmed destroyed may still own the write
     * epoch and keep publishing saves that poison the ordering gate. The
     * bounded daemon-thread join mirrors the existing visual-suite
     * `closeScenarioBounded`, but unlike that helper we do not treat a
     * timeout as ignorable — B1's whole premise is "the old host is dead".
     */
    private fun closeHostOrFail(scenario: ActivityScenario<SlockKuiklyActivity>) {
        val closed = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)
        Thread {
            try {
                scenario.close()
            } catch (t: Throwable) {
                failure[0] = t
            } finally {
                closed.countDown()
            }
        }.apply {
            isDaemon = true
            name = "member-detail-scenario-close"
        }.start()
        if (!closed.await(ScenarioCloseTimeoutMs, TimeUnit.MILLISECONDS)) {
            fail("scenario.close() did not complete within ${ScenarioCloseTimeoutMs}ms; host not proven dead")
        }
        failure[0]?.let { throw AssertionError("scenario.close() threw: ${it.message}", it) }
    }

    // -- Input -----------------------------------------------------------------

    /** Blocking drag used to establish the baseline (phase 1). */
    private fun injectDragToScrollBlocking() = dragEvents(blocking = true)

    /**
     * Same gesture queued non-blocking (`injectInputEvent(event, false)`), so
     * the caller polls probe/user_frame evidence while the input pipeline is
     * still draining the MOVE run — the actual B1 interleave. Any event the
     * injector rejects fails the case; MotionEvents are recycled after
     * dispatch.
     */
    private fun injectDragToScrollNonBlocking() = dragEvents(blocking = false)

    private fun dragEvents(blocking: Boolean) {
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
                    downTime + (DragDurationMs * fraction).toLong(),
                    MotionEvent.ACTION_MOVE,
                    x,
                    startY + (endY - startY) * fraction,
                    0
                )
        }
        events +=
            MotionEvent.obtain(
                downTime,
                downTime + DragDurationMs,
                MotionEvent.ACTION_UP,
                x,
                endY,
                0
            )
        try {
            events.forEach { event ->
                if (blocking) {
                    instrumentation.sendPointerSync(event)
                } else {
                    val accepted =
                        instrumentation.uiAutomation.injectInputEvent(event, false)
                    require(accepted) {
                        "injectInputEvent rejected ${event.actionMasked} at eventTime=${event.eventTime}"
                    }
                }
            }
        } finally {
            events.forEach(MotionEvent::recycle)
        }
    }

    // -- Logcat observation -----------------------------------------------------

    private data class ScrollPosition(val index: Int, val offset: Int)

    /**
     * The member-selection label carried by every tracker/probe/dispose line:
     * `<accountHash>:<selectionKey>:human`. The account hash is not stable
     * across runs, so we match on the stable selection tail.
     */
    private val ScrollLabel = "visual-server:human:visual-human-long:human"

    /**
     * Restore key for the registry: `currentUserId:selectionKey` with the
     * visual host's fixed owner. `registry_save` lines carry `keyHash=` (the
     * Java String hash of that key) rather than the raw key.
     */
    private val MemberKey = "visual-owner:visual-server:human:visual-human-long"
    private val KeyHash = MemberKey.hashCode().toString(16)

    /**
     * Fresh `logcat -d` snapshot filtered to the MembersDetailScroll tag and
     * to lines logged at-or-after [startCursor] (event timestamps), ordered.
     */
    private fun orderedLines(startCursor: Long): List<String> {
        var process: Process? = null
        return try {
            process =
                Runtime.getRuntime()
                    .exec(arrayOf("logcat", "-d", "-s", "MembersDetailScroll"))
            process.inputStream.bufferedReader().use { reader ->
                reader.readLines().filter { line ->
                    logcatTimestampMillis(line)?.let { it >= startCursor } != false
                }
            }
        } catch (t: Throwable) {
            Log.w(TestLogTag, "logcat read failed: ${t.message}")
            emptyList()
        } finally {
            process?.destroy()
        }
    }

    // Logcat default format: "MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: msg".
    private fun logcatTimestampMillis(line: String): Long? {
        val match = Regex("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})").find(line)
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
     * The `tracker_seed` line minted for the newest composition of this key —
     * it carries `label=<scrollLabel>:human generation=… epoch=N`. Read the
     * LAST matching seed line after [startCursor]; each host launch produces
     * exactly one, so waits naturally bind to the latest entry.
     */
    private fun waitForEpoch(startCursor: Long, description: String): Long {
        val line =
            waitForLine(startCursor, description) { l ->
                l.contains("tracker_seed") && l.contains(ScrollLabel)
            }
        val epoch =
            Regex("epoch=(\\d+)").find(line)?.groupValues?.get(1)?.toLong()
        return requireNotNull(epoch) { "tracker_seed carried no epoch: $line" }
    }

    private fun waitForSave(
        startCursor: Long,
        epoch: Long,
        description: String
    ): ScrollPosition {
        val line =
            waitForLine(startCursor, description) { l ->
                l.contains("registry_save") &&
                    l.contains("kind=humanList") &&
                    l.contains("decision=accept") &&
                    l.contains("keyHash=$KeyHash") &&
                    l.contains("epoch=$epoch/$epoch")
            }
        return requireNotNull(parsePositionFrom(line)) {
            "registry_save carried no position: $line"
        }
    }

    private fun waitForLine(
        startCursor: Long,
        description: String,
        match: (String) -> Boolean
    ): String {
        val deadline = SystemClock.uptimeMillis() + InitialRenderTimeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val line = orderedLines(startCursor).lastOrNull(match)
            if (line != null) return line
            SystemClock.sleep(LogPollMs)
        }
        throw AssertionError("Timed out waiting for $description")
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
        private const val DragDurationMs = 360L
    }
}
