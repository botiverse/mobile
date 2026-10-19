package build.raft.app.shared

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import build.raft.app.ThreadNewerAutoPaginationFixtureSnapshot
import build.raft.app.ThreadNewerAutoPaginationVisualFixtureProbe
import build.raft.app.threadNewerAutoMessageLabel
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected acceptance for task #692.
 *
 * This launches the production Thread route and its real LazyColumn. The
 * fixture only owns the typed HTTP response so the test can hold an append in
 * flight, inspect the currently visible stable message through Android
 * accessibility, and then let the real MVI/store/UI cycle settle.
 */
@RunWith(AndroidJUnit4::class)
class SlockThreadNewerAutoPaginationConnectedTest {
    @Test
    fun freshTowardNewerGesturesLoadOnePagePreserveAnchorRetryAndStopAtEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val intent =
            Intent(instrumentation.targetContext, SlockKuiklyActivity::class.java)
                .putExtra(SlockCmpActivity.KEY_PAGE_NAME, PageName)
                .putExtra(
                    SlockCmpActivity.KEY_PAGE_DATA,
                    JSONObject().put("runNonce", nonce).toString(),
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            dismissAndroidCompatibilityDialog(instrumentation)
            waitForInitialFocusedHistory(nonce)
            val initial = fixtureSnapshot(nonce)
            assertTrue("Expected at least one canonical initial Thread request: $initial", initial.initialRequestCount >= 1)
            assertTrue(
                "Programmatic focus/initial layout must not authorize newer pagination: $initial",
                initial.afterRequests.isEmpty(),
            )

            performUpwardGesture(instrumentation, scenario, durationMs = FirstGestureDurationMs)
            val firstRequest = waitForAfterRequestCount(nonce, expectedCount = 1)
            assertEquals(16L, firstRequest.afterRequests.single().afterSeq)
            assertEquals(30, firstRequest.afterRequests.single().limit)

            val retainedMarker = waitForStableVisibleInitialMarker(instrumentation)
            assertTrue(
                "Expected a visible message-owned anchor after the first direct gesture, got $retainedMarker",
                retainedMarker.seq in 1L..16L,
            )

            assertTrue(
                "The parked first newer request was not released",
                ThreadNewerAutoPaginationVisualFixtureProbe.releaseSuccess(
                    runNonce = nonce,
                    callNumber = 1,
                    messageCount = 30,
                ),
            )
            waitForReturnedCall(nonce, callNumber = 1)
            SystemClock.sleep(PageApplySettleMs)

            val retainedAfterAppend = waitForStableMarker(instrumentation, retainedMarker.seq)
            val density = instrumentation.targetContext.resources.displayMetrics.density
            assertTrue(
                "The first 30-row append changed the visible stable message: before=$retainedMarker after=$retainedAfterAppend",
                retainedAfterAppend.seq == retainedMarker.seq,
            )
            assertTrue(
                "The first 30-row append displaced the reading anchor: before=$retainedMarker after=$retainedAfterAppend",
                abs(retainedAfterAppend.bounds.top - retainedMarker.bounds.top) <= AnchorToleranceDp * density,
            )
            recordEvidence(
                key = "THREAD_NEWER_ANCHOR",
                value = "firstRequest=${firstRequest.afterRequests.single()} " +
                    "before=$retainedMarker after=$retainedAfterAppend " +
                    "deltaPx=${retainedAfterAppend.bounds.top - retainedMarker.bounds.top} " +
                    "tolerancePx=${AnchorToleranceDp * density}",
            )

            assertAfterRequestCountRemains(
                nonce = nonce,
                expectedCount = 1,
                durationMs = NoCascadeObservationMs,
                reason = "settling the first 16→46 append without another gesture must not cascade",
            )
            recordEvidence("THREAD_NEWER_NO_CASCADE", "snapshot=${fixtureSnapshot(nonce)}")

            // Back-to-bottom now owns a canonical-tail request rather than a
            // local reposition inside the loaded window. Keep this pagination
            // gate on its actual contract: fresh direct toward-newer gestures
            // may advance through the committed 30-row page until one of them
            // crosses the near-end threshold and starts exactly one next page.
            var approachGestureCount = 0
            while (
                fixtureSnapshot(nonce).afterRequests.size < 2 &&
                approachGestureCount < MaxTowardNewerApproachGestures
            ) {
                performUpwardGesture(instrumentation, scenario, durationMs = SecondGestureDurationMs)
                approachGestureCount += 1
            }
            val secondRequest = waitForAfterRequestCount(nonce, expectedCount = 2)
            assertEquals(46L, secondRequest.afterRequests[1].afterSeq)
            assertEquals(30, secondRequest.afterRequests[1].limit)
            recordEvidence("THREAD_NEWER_SECOND_REQUEST", "snapshot=$secondRequest")

            assertTrue(
                "The parked second newer request was not released as an error",
                ThreadNewerAutoPaginationVisualFixtureProbe.releaseError(nonce, callNumber = 2),
            )
            waitForReturnedCall(nonce, callNumber = 2)
            var retryRevealGestureCount = 0
            while (
                !hasAccessibilityText(instrumentation, RetryLabel) &&
                retryRevealGestureCount < MaxTowardNewerApproachGestures
            ) {
                performUpwardGesture(instrumentation, scenario, durationMs = SecondGestureDurationMs)
                retryRevealGestureCount += 1
            }
            assertEquals(
                "Revealing the failed newer boundary must not manufacture a retry request",
                2,
                fixtureSnapshot(nonce).afterRequests.size,
            )
            val retryNode = waitForAccessibilityText(instrumentation, RetryLabel)
            assertTrue(
                "Error must expose an explicit Retry action",
                retryNode.isEnabled,
            )
            performAccessibilityClick(retryNode, RetryLabel)
            instrumentation.waitForIdleSync()

            val retryRequest = waitForAfterRequestCount(nonce, expectedCount = 3)
            assertEquals(
                "Retry must request from the same committed tail after the failed page",
                46L,
                retryRequest.afterRequests[2].afterSeq,
            )
            recordEvidence("THREAD_NEWER_RETRY_REQUEST", "snapshot=$retryRequest")
            assertTrue(
                "The parked Retry request was not released",
                ThreadNewerAutoPaginationVisualFixtureProbe.releaseSuccess(
                    runNonce = nonce,
                    callNumber = 3,
                    messageCount = 4,
                ),
            )
            waitForReturnedCall(nonce, callNumber = 3)
            SystemClock.sleep(PageApplySettleMs)
            if (!hasAccessibilityText(instrumentation, threadNewerAutoMessageLabel(50L))) {
                performAccessibilityClick(
                    waitForAccessibilityText(instrumentation, BackToBottomLabel),
                    "Back to bottom after terminal page",
                )
            }
            waitForAccessibilityText(instrumentation, threadNewerAutoMessageLabel(50L))
            assertAfterRequestCountRemains(
                nonce = nonce,
                expectedCount = 3,
                durationMs = ProgrammaticTailObservationMs,
                reason = "revealing the committed terminal tail must not create a fourth request",
            )
            assertFalse(
                "Terminal short page must remove the Retry/newer boundary",
                hasAccessibilityText(instrumentation, RetryLabel),
            )

            performDownwardGesture(instrumentation, scenario)
            waitForMarkerToLeaveViewport(instrumentation, seq = 50L)
            assertAfterRequestCountRemains(
                nonce = nonce,
                expectedCount = 3,
                durationMs = OppositeDirectionObservationMs,
                reason = "moving away from a terminal newer boundary must stay terminal",
            )
            performUpwardGesture(instrumentation, scenario, durationMs = SecondGestureDurationMs)
            assertAfterRequestCountRemains(
                nonce = nonce,
                expectedCount = 3,
                durationMs = EndObservationMs,
                reason = "a terminal short page must disable all later newer requests",
            )
            recordEvidence("THREAD_NEWER_TERMINAL", "snapshot=${fixtureSnapshot(nonce)}")
        }
    }

    private fun recordEvidence(key: String, value: String) {
        println("$key $value")
        InstrumentationRegistry.getInstrumentation().sendStatus(
            EvidenceStatusCode,
            Bundle().apply { putString("stream", "$key $value\n") },
        )
    }

    private fun waitForInitialFocusedHistory(nonce: String) {
        try {
            waitUntil(InitialRenderTimeoutMs, "initial 16-row focused Thread history") {
                val snapshot = fixtureSnapshotOrNull(nonce) ?: return@waitUntil false
                snapshot.initialRequestCount >= 1 &&
                    hasAccessibilityText(
                        InstrumentationRegistry.getInstrumentation(),
                        threadNewerAutoMessageLabel(8L),
                    )
            }
        } catch (failure: AssertionError) {
            throw AssertionError(
                "${failure.message}; snapshot=${fixtureSnapshotOrNull(nonce)}; " +
                    "accessibility=${accessibilityTreeSummary(InstrumentationRegistry.getInstrumentation())}",
                failure,
            )
        }
        SystemClock.sleep(InitialAnchorSettleMs)
    }

    private fun waitForAfterRequestCount(
        nonce: String,
        expectedCount: Int,
    ): ThreadNewerAutoPaginationFixtureSnapshot {
        var latest: ThreadNewerAutoPaginationFixtureSnapshot? = null
        try {
            waitUntil(RequestTimeoutMs, "newer request count $expectedCount") {
                latest = fixtureSnapshotOrNull(nonce)
                latest?.afterRequests?.size == expectedCount
            }
        } catch (failure: AssertionError) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            throw AssertionError(
                "${failure.message}; latest=$latest; " +
                    "visibleMarkers=${visibleFixtureMarkers(instrumentation, 1L..80L)}; " +
                    "accessibility=${accessibilityTreeSummary(instrumentation)}",
                failure,
            )
        }
        return requireNotNull(latest)
    }

    private fun waitForReturnedCall(nonce: String, callNumber: Int) {
        waitUntil(RequestTimeoutMs, "newer response return $callNumber") {
            fixtureSnapshotOrNull(nonce)?.returnedAfterCalls?.contains(callNumber) == true
        }
    }

    private fun assertAfterRequestCountRemains(
        nonce: String,
        expectedCount: Int,
        durationMs: Long,
        reason: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + durationMs
        while (SystemClock.uptimeMillis() < deadline) {
            val snapshot = fixtureSnapshot(nonce)
            assertEquals("$reason: $snapshot", expectedCount, snapshot.afterRequests.size)
            SystemClock.sleep(ObservationPollMs)
        }
    }

    private fun waitForStableVisibleInitialMarker(instrumentation: Instrumentation): VisibleMarker {
        var previous: VisibleMarker? = null
        var stableSamples = 0
        var latest: VisibleMarker? = null
        waitUntil(AccessibilityTimeoutMs, "stable visible initial message anchor") {
            latest = visibleFixtureMarkers(instrumentation, 1L..16L).minByOrNull { it.bounds.top }
            val current = latest
            val prior = previous
            stableSamples =
                if (
                    current != null &&
                    prior != null &&
                    current.seq == prior.seq &&
                    abs(current.bounds.top - prior.bounds.top) <= StablePixelTolerance
                ) {
                    stableSamples + 1
                } else {
                    0
                }
            previous = current
            if (stableSamples < StableSamplesRequired) {
                SystemClock.sleep(StableSampleIntervalMs)
            }
            stableSamples >= StableSamplesRequired
        }
        return requireNotNull(latest)
    }

    private fun waitForStableMarker(instrumentation: Instrumentation, seq: Long): VisibleMarker {
        var previous: VisibleMarker? = null
        var stableSamples = 0
        var latest: VisibleMarker? = null
        waitUntil(AccessibilityTimeoutMs, "stable retained message ${threadNewerAutoMessageLabel(seq)}") {
            latest = visibleFixtureMarker(instrumentation, seq)
            val current = latest
            val prior = previous
            stableSamples =
                if (
                    current != null &&
                    prior != null &&
                    abs(current.bounds.top - prior.bounds.top) <= StablePixelTolerance
                ) {
                    stableSamples + 1
                } else {
                    0
                }
            previous = current
            if (stableSamples < StableSamplesRequired) {
                SystemClock.sleep(StableSampleIntervalMs)
            }
            stableSamples >= StableSamplesRequired
        }
        return requireNotNull(latest)
    }

    private fun visibleFixtureMarkers(
        instrumentation: Instrumentation,
        seqRange: LongRange,
    ): List<VisibleMarker> = seqRange.mapNotNull { seq -> visibleFixtureMarker(instrumentation, seq) }

    private fun visibleFixtureMarker(instrumentation: Instrumentation, seq: Long): VisibleMarker? {
        val label = threadNewerAutoMessageLabel(seq)
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        return root.findAccessibilityNodeInfosByText(label)
            .mapNotNull { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() <= 0 || bounds.height() <= 0) {
                    null
                } else {
                    VisibleMarker(seq = seq, bounds = bounds)
                }
            }
            .minByOrNull { marker -> marker.bounds.width() * marker.bounds.height() }
    }

    private fun waitForAccessibilityText(
        instrumentation: Instrumentation,
        text: String,
    ): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        waitUntil(AccessibilityTimeoutMs, "accessibility text '$text'") {
            node =
                instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText(text)
                    ?.firstOrNull()
            node != null
        }
        return requireNotNull(node)
    }

    private fun performAccessibilityClick(node: AccessibilityNodeInfo, label: String) {
        var current: AccessibilityNodeInfo? = node
        var tapBounds: Rect? = null
        repeat(MaxAccessibilityClickAncestorDepth) {
            val candidate = current ?: return@repeat
            if (
                candidate.isClickable ||
                candidate.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK }
            ) {
                if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    return
                }
                val bounds = Rect()
                candidate.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    tapBounds = bounds
                }
            }
            current = candidate.parent
        }
        val bounds = tapBounds ?: throw AssertionError("No clickable accessibility ancestor for '$label'")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val command = "input touchscreen tap ${bounds.centerX()} ${bounds.centerY()}"
        val output =
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).bufferedReader().use { reader -> reader.readText() }
        println("THREAD_NEWER_TAP label=$label bounds=$bounds command=$command output=$output")
        instrumentation.waitForIdleSync()
        SystemClock.sleep(AccessibilityTapSettleMs)
    }

    private fun hasAccessibilityText(instrumentation: Instrumentation, text: String): Boolean =
        instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText(text)
            ?.isNotEmpty() == true

    private fun dismissAndroidCompatibilityDialog(instrumentation: Instrumentation) {
        val deadline = SystemClock.uptimeMillis() + CompatibilityDialogWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val button =
                root?.findAccessibilityNodeInfosByText("Don't Show Again")?.firstOrNull()
                    ?: root?.findAccessibilityNodeInfosByText("OK")?.firstOrNull()
            if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(CompatibilityDialogSettleMs)
                return
            }
            SystemClock.sleep(ObservationPollMs)
        }
    }

    private fun accessibilityTreeSummary(instrumentation: Instrumentation): String {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return "<no-root>"
        val entries = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || entries.size >= MaxAccessibilitySummaryEntries) return
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            if (text.isNotBlank() || description.isNotBlank()) {
                entries += "d=$depth text=$text description=$description class=${node.className}"
            }
            for (index in 0 until node.childCount) {
                visit(node.getChild(index), depth + 1)
            }
        }
        visit(root, 0)
        return entries.joinToString(" | ").take(MaxAccessibilitySummaryChars)
    }

    private fun performUpwardGesture(
        instrumentation: Instrumentation,
        scenario: ActivityScenario<SlockKuiklyActivity>,
        durationMs: Long,
        xFraction: Float = FocusedHistoryGestureXFraction,
    ) {
        val bottomVisibleMarker =
            visibleFixtureMarkers(instrumentation, 1L..80L)
                .maxByOrNull { marker -> marker.bounds.bottom }
        var geometry: GestureGeometry? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView.rootView
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            val rootTop = location[1].toFloat()
            val markerStart = bottomVisibleMarker?.bounds?.centerY()?.toFloat()
            val resolved =
                GestureGeometry(
                    // The focused-history state also shows the centered
                    // Back-to-bottom overlay. Start in the message row's right
                    // text gutter so that overlay cannot own ACTION_DOWN.
                    x = location[0] + root.width * xFraction,
                    startY =
                        (markerStart ?: (rootTop + root.height * 0.78f))
                            .coerceIn(rootTop + root.height * 0.30f, rootTop + root.height * 0.88f),
                    endY = rootTop + root.height * 0.15f,
                )
            geometry = resolved
        }
        val resolved = requireNotNull(geometry) { "Activity gesture geometry was unavailable" }
        val command =
            "input touchscreen swipe ${resolved.x.toInt()} ${resolved.startY.toInt()} " +
                "${resolved.x.toInt()} ${resolved.endY.toInt()} $durationMs"
        val output =
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).bufferedReader().use { reader -> reader.readText() }
        println(
            "THREAD_NEWER_GESTURE marker=$bottomVisibleMarker geometry=$resolved command=$command output=$output",
        )
        instrumentation.waitForIdleSync()
        SystemClock.sleep(GestureSettleMs)
    }

    private fun performDownwardGesture(
        instrumentation: Instrumentation,
        scenario: ActivityScenario<SlockKuiklyActivity>,
    ) {
        var geometry: GestureGeometry? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView.rootView
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            val rootTop = location[1].toFloat()
            geometry =
                GestureGeometry(
                    x = location[0] + root.width * TailRepositionGestureXFraction,
                    startY = rootTop + root.height * 0.32f,
                    endY = rootTop + root.height * 0.78f,
                )
        }
        val resolved = requireNotNull(geometry) { "Activity gesture geometry was unavailable" }
        val command =
            "input touchscreen swipe ${resolved.x.toInt()} ${resolved.startY.toInt()} " +
                "${resolved.x.toInt()} ${resolved.endY.toInt()} $RepositionGestureDurationMs"
        val output =
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).bufferedReader().use { reader -> reader.readText() }
        println("THREAD_NEWER_REPOSITION geometry=$resolved command=$command output=$output")
        instrumentation.waitForIdleSync()
        SystemClock.sleep(GestureSettleMs)
    }

    private fun waitForMarkerToLeaveViewport(instrumentation: Instrumentation, seq: Long) {
        waitUntil(AccessibilityTimeoutMs, "message ${threadNewerAutoMessageLabel(seq)} to leave viewport") {
            visibleFixtureMarker(instrumentation, seq) == null
        }
    }

    private fun fixtureSnapshot(nonce: String): ThreadNewerAutoPaginationFixtureSnapshot =
        ThreadNewerAutoPaginationVisualFixtureProbe.snapshot(nonce)

    private fun fixtureSnapshotOrNull(nonce: String): ThreadNewerAutoPaginationFixtureSnapshot? =
        runCatching { fixtureSnapshot(nonce) }.getOrNull()

    private fun waitUntil(timeoutMs: Long, description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            SystemClock.sleep(ObservationPollMs)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private data class VisibleMarker(
        val seq: Long,
        val bounds: Rect,
    )

    private data class GestureGeometry(
        val x: Float,
        val startY: Float,
        val endY: Float,
    )

    private companion object {
        private const val PageName = "compose_thread_newer_auto_pagination_visual"
        private const val RetryLabel = "Retry new messages"
        private const val BackToBottomLabel = "Back to bottom"
        private const val InitialRenderTimeoutMs = 20_000L
        private const val InitialAnchorSettleMs = 1_500L
        private const val CompatibilityDialogSettleMs = 500L
        private const val CompatibilityDialogWaitMs = 3_000L
        private const val RequestTimeoutMs = 12_000L
        private const val AccessibilityTimeoutMs = 10_000L
        private const val PageApplySettleMs = 1_200L
        private const val NoCascadeObservationMs = 1_800L
        private const val ProgrammaticTailObservationMs = 700L
        private const val OppositeDirectionObservationMs = 500L
        private const val EndObservationMs = 1_500L
        private const val ObservationPollMs = 100L
        private const val StableSampleIntervalMs = 120L
        private const val StableSamplesRequired = 3
        private const val StablePixelTolerance = 2
        private const val AnchorToleranceDp = 10f
        private const val GestureSettleMs = 250L
        private const val AccessibilityTapSettleMs = 300L
        private const val FirstGestureDurationMs = 650L
        private const val SecondGestureDurationMs = 650L
        private const val MaxTowardNewerApproachGestures = 6
        private const val FocusedHistoryGestureXFraction = 0.85f
        private const val TailRepositionGestureXFraction = 0.50f
        private const val RepositionGestureDurationMs = 650L
        private const val MaxAccessibilitySummaryEntries = 120
        private const val MaxAccessibilitySummaryChars = 8_000
        private const val MaxAccessibilityClickAncestorDepth = 8
        private const val EvidenceStatusCode = 2
    }
}
