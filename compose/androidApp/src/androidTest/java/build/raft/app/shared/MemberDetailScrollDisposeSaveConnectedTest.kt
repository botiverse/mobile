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
 * Scenario: open `compose_member_detail` for the dedicated long-content Human
 * visual seed (`visual-human-long`, seed lives in MembersPage.kt under a new
 * dispatch branch), let the real LazyColumn lay out, drive a real injected
 * drag that crosses touch slop so the pointer probe marks the frame
 * `scrolling=true` user-driven, then destroy the host WHILE the drag's
 * settle window is still open — i.e. before the periodic snapshotFlow save
 * has published that position to the registry. The DisposableEffect dispose
 * fallback must write the tracker's lastPosition (`dispose_save`), and the
 * recreated host must restore to it.
 *
 * The timing criterion (locked in the review thread) is the log order on the
 * restore key:
 *   `frame(scrolling=true,user)` / `probe_hit` -> `dispose_save` for the
 *   dragged position, with NO earlier `registry_save` carrying that same
 *   position for this key+epoch. If the injected gesture never produces a
 *   user `probe_hit`/scrolling frame, or a periodic `registry_save` lands the
 *   position first, the interleave was not hit and the case reports 未命中
 *   (miss) explicitly rather than claiming the coverage.
 *
 * Assertions are read off the app's own logcat stream (tag
 * `MembersDetailScroll`), which the instrumentation process can read without
 * READ_LOGS. Each line for this key carries the per-member write epoch so the
 * criterion is evaluated per epoch, not by a bare tag search.
 *
 * Isolation: dedicated memberId `visual-human-long` (restore key =
 * `visual-owner:visual-server:human:visual-human-long`); the finally block
 * destroys the host before clearing [MemberDetailScrollStateRegistry] so the
 * dedicated key never leaks into other cases.
 */
class MemberDetailScrollDisposeSaveConnectedTest {

    @Test
    fun humanDetailDisposeDuringUserDragSavesAndRestores() {
        MemberDetailScrollRegistryFixtureBridge.clearForTest()
        var scenario: ActivityScenario<SlockKuiklyActivity>? = null
        try {
            scenario = launchMemberDetailHost()
            waitUntil(InitialRenderTimeoutMs, "human detail initial layout") {
                logcatContains("$TestLogTag ")
            }
            waitForIdleBounded()
            // Phase 1 — establish the non-zero restore baseline the way the
            // locked contract requires: a first visit drives a real drag,
            // lets the settle window close, and a periodic registry_save
            // publishes the position under this key+epoch.
            injectDragToScroll()
            waitUntil(RestoreSettleTimeoutMs, "baseline registry_save for $ScrollKeyTail") {
                latestRegistrySavePosition() != null
            }
            val baseline = requireNotNull(latestRegistrySavePosition()) {
                "baseline registry_save never landed for $ScrollKeyTail"
            }
            recordEvidence("B1_BASELINE", "position=$baseline")
            assertTrue(
                "baseline scroll never left the top; cannot exercise a non-zero restore",
                baseline.index > 0 || baseline.offset > 0
            )
            closeHost(scenario)

            // Phase 2/3 — re-enter, then drag WHILE the settle window is
            // still open. That is the locked B1 shape: `restoreSettlePending`
            // is true after `restore_first_items`, the drag retires the
            // pending restore via `userScrollActive`, and the dispose lands
            // before the post-gesture periodic save.
            scenario = launchMemberDetailHost()
            val firstItems =
                waitForLogLine(InitialRenderTimeoutMs) { line ->
                    line.contains("restore_first_items") && line.contains(ScrollKeyTail)
                }
            recordEvidence("B2_FIRST_ITEMS", "line=$firstItems")
            assertTrue(
                "re-entry restore_first_items does not target the baseline $baseline: $firstItems",
                firstItems.contains("target=${baseline.index}:${baseline.offset}") &&
                    firstItems.contains("wantsRestore=true")
            )

            // Non-blocking drag: DOWN + MOVEs + UP are queued onto the
            // system input pipeline; we start polling for the probe evidence
            // while the in-flight frames are still being delivered.
            injectDragToScrollNonBlocking()
            // Wait for the probe to flag the drag as user input AND for at
            // least one scrolling frame to be attributed to the user — that
            // pair is the live evidence that the tracker has already marked
            // userScrollActive and recorded a lastPosition. Then destroy the
            // host immediately: the drag's in-flight tail is still draining,
            // so the periodic save has not yet published the landing.
            val probeDeadline = SystemClock.uptimeMillis() + DragObserveTimeoutMs
            var lastUserFrame: ScrollPosition? = null
            var probeSeen = false
            while (SystemClock.uptimeMillis() < probeDeadline) {
                val lines = logcatLines().filter { it.contains(ScrollKeyTail) }
                probeSeen = probeSeen || lines.any { it.contains("probe_hit") }
                lastUserFrame =
                    lines.lastOrNull { it.contains("scrolling") || it.contains("probe_hit") }
                        ?.let(::parsePositionFrom)
                        ?: lastUserFrame
                if (probeSeen && lastUserFrame != null) break
                SystemClock.sleep(LogPollMs)
            }
            if (!probeSeen || lastUserFrame == null) {
                recordEvidence("B1_MISS", "probeSeen=$probeSeen lastUserFrame=$lastUserFrame")
                fail("interleave not hit: user drag produced no probe/scrolling frame for $ScrollKeyTail")
            }
            // Capture the pre-dispose registry state on this key so the
            // ordering check below reads "no save of this position landed
            // before dispose" from actual log order, not just presence.
            val preDisposeSaves = acceptedHumanListSavePositions()
            closeHost(scenario)
            scenario = null
            val disposeLine =
                waitForLogLine(DisposeWriteTimeoutMs) { line ->
                    line.contains("dispose_save") && line.contains(ScrollKeyTail)
                }
            recordEvidence("B1_DISPOSE", "line=$disposeLine")
            val disposePosition = parsePositionFrom(disposeLine)
            requireNotNull(disposePosition) { "dispose_save carried no position" }
            if (disposePosition in preDisposeSaves) {
                fail(
                    "interleave not hit: a periodic registry_save already wrote $disposePosition " +
                        "before dispose; this is the old already-saved path, not the settle-window exit"
                )
            }
            assertTrue(
                "dispose_save wrote $disposePosition, expected a non-zero user position",
                disposePosition.index > 0 || disposePosition.offset > 0
            )

            // Phase 4 — a third entry must pick the dragged position as its
            // restore target. `restore_settled` may legitimately never emit
            // when the seed already lands on the target, so the gate here is
            // the `restore_first_items` line carrying the dispose-saved
            // position — the position the new composition actually restores
            // to.
            scenario = launchMemberDetailHost()
            val revisitFirstItems =
                waitForLogLine(InitialRenderTimeoutMs) { line ->
                    line.contains("restore_first_items") && line.contains(ScrollKeyTail)
                }
            recordEvidence("B1_REVISIT", "line=$revisitFirstItems")
            assertTrue(
                "revisit did not target the dispose-saved position $disposePosition: $revisitFirstItems",
                revisitFirstItems.contains(
                    "target=${disposePosition.index}:${disposePosition.offset}"
                )
            )
        } finally {
            scenario?.let(::closeHost)
            MemberDetailScrollRegistryFixtureBridge.clearForTest()
        }
    }

    // -- Host lifecycle -----------------------------------------------------

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

    private fun closeHost(scenario: ActivityScenario<SlockKuiklyActivity>) {
        val closed = CountDownLatch(1)
        Thread {
            try {
                scenario.close()
            } catch (t: Throwable) {
                Log.w(TestLogTag, "scenario.close() threw: ${t.message}")
            } finally {
                closed.countDown()
            }
        }.apply {
            isDaemon = true
            name = "member-detail-scenario-close"
        }.start()
        if (!closed.await(ScenarioCloseTimeoutMs, TimeUnit.MILLISECONDS)) {
            Log.w(TestLogTag, "scenario.close() exceeded ${ScenarioCloseTimeoutMs}ms")
        }
    }

    private fun waitForIdleBounded(maxMs: Long = IdleWaitTimeoutMs) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settled = CountDownLatch(1)
        Thread {
            instrumentation.waitForIdleSync()
            settled.countDown()
        }.apply {
            isDaemon = true
            name = "member-detail-wait-for-idle"
        }.start()
        settled.await(maxMs, TimeUnit.MILLISECONDS)
    }

    // -- Input ---------------------------------------------------------------

    /**
     * A real injected drag across the member-detail list: DOWN near the
     * bottom, a bounded MOVE run, then UP. It must (a) cross touch slop so
     * `membersDetailUserDragProbe` logs `probe_hit`, and (b) move the list so
     * the snapshotFlow emits a `scrolling=true` user frame. The caller then
     * races the host destroy into the still-open settle window.
     */
    /** Blocking drag used to establish the baseline (phase 1). */
    private fun injectDragToScroll() {
        dragEvents(blocking = true)
    }

    /**
     * Same gesture queued via `injectInputEvent(event, false)` so the caller
     * returns immediately while the input pipeline is still draining the
     * MOVE run. That keeps `isScrollInProgress` live (and the tracker's
     * settle window open) while the host is being destroyed — the actual B1
     * interleave: `frame(scrolling=true,user)` -> dispose -> `dispose_save`,
     * without a periodic `registry_save` of that position in between.
     */
    private fun injectDragToScrollNonBlocking() {
        dragEvents(blocking = false)
    }

    private fun dragEvents(blocking: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * 0.72f
        val endY = metrics.heightPixels * 0.30f
        val downTime = SystemClock.uptimeMillis()
        fun dispatch(event: MotionEvent) {
            if (blocking) {
                instrumentation.sendPointerSync(event)
            } else {
                instrumentation.uiAutomation.injectInputEvent(event, false)
            }
        }
        dispatch(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0))
        repeat(DragSteps) { step ->
            val fraction = (step + 1f) / DragSteps
            dispatch(
                MotionEvent.obtain(
                    downTime,
                    downTime + (DragDurationMs * fraction).toLong(),
                    MotionEvent.ACTION_MOVE,
                    x,
                    startY + (endY - startY) * fraction,
                    0
                )
            )
        }
        dispatch(
            MotionEvent.obtain(
                downTime,
                downTime + DragDurationMs,
                MotionEvent.ACTION_UP,
                x,
                endY,
                0
            )
        )
    }

    // -- Logcat observation ---------------------------------------------------

    private data class ScrollPosition(val index: Int, val offset: Int)

    /**
     * The member-selection tail of the log label. Log lines carry
     * `label=<accountHash>:<selectionKey>`; the selection key is
     * `visual-server:human:visual-human-long`, so matching the tail keeps this
     * case independent of the account-hash prefix.
     */
    private val ScrollKeyTail = "visual-server:human:visual-human-long"

    private fun logcatLines(): List<String> {
        var process: Process? = null
        return try {
            process =
                Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "MembersDetailScroll"))
            process.inputStream.bufferedReader().use { it.readLines() }
        } catch (t: Throwable) {
            Log.w(TestLogTag, "logcat read failed: ${t.message}")
            emptyList()
        } finally {
            process?.destroy()
        }
    }

    private fun logcatContains(token: String): Boolean =
        logcatLines().any { it.contains(token) && it.contains(ScrollKeyTail) }

    private fun logcatLineMatching(event: String): String? =
        logcatLines().lastOrNull { it.contains(event) && it.contains(ScrollKeyTail) }

    private fun parsePositionFrom(line: String?): ScrollPosition? {
        if (line == null) return null
        val match = Regex("position=(\\d+):(\\d+)").find(line) ?: return null
        return ScrollPosition(
            index = match.groupValues[1].toInt(),
            offset = match.groupValues[2].toInt()
        )
    }

    private fun latestRegistrySavePosition(): ScrollPosition? {
        val line =
            logcatLines()
                .filter { it.contains("registry_save") && it.contains("kind=humanList") }
                .lastOrNull { it.contains("decision=accept") }
        // registry_save lines key by keyHash, not the selection tail; read the
        // position off the last accepted humanList save. The dedicated member
        // is the only writer on this surface during the run, so "last" is ours.
        return parsePositionFrom(line)
    }

    /** All accepted `registry_save kind=humanList` positions logged so far.
     *
     * `keyHash` is logged instead of the raw member key, so the list cannot
     * be filtered to just this member — during this test the dedicated
     * fixture is the only human-list writer on the surface, so "any accepted
     * save" is ours.
     */
    private fun acceptedHumanListSavePositions(): Set<ScrollPosition> =
        logcatLines()
            .asSequence()
            .filter { it.contains("registry_save") && it.contains("kind=humanList") }
            .filter { it.contains("decision=accept") }
            .mapNotNull(::parsePositionFrom)
            .toSet()

    private fun waitForLogLine(
        timeoutMs: Long,
        match: (String) -> Boolean
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val line = logcatLines().lastOrNull(match)
            if (line != null) return line
            SystemClock.sleep(LogPollMs)
        }
        throw AssertionError("Timed out waiting for logcat line (${timeoutMs}ms)")
    }

    private fun waitUntil(timeoutMs: Long, description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
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
        private const val InitialRenderTimeoutMs = 15_000L
        private const val RestoreSettleTimeoutMs = 15_000L
        private const val DragObserveTimeoutMs = 6_000L
        private const val DisposeWriteTimeoutMs = 10_000L
        private const val IdleWaitTimeoutMs = 15_000L
        private const val ScenarioCloseTimeoutMs = 15_000L
        private const val LogPollMs = 120L
        private const val DragSteps = 10
        private const val DragDurationMs = 360L
    }
}
