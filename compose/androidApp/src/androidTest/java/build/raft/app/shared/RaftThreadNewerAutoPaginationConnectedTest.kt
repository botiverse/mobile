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
class RaftThreadNewerAutoPaginationConnectedTest {
    @Test
    fun freshTowardNewerGesturesLoadOnePagePreserveAnchorRetryAndStopAtEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val intent =
            Intent(instrumentation.targetContext, RaftCmpActivity::class.java)
                .putExtra(RaftCmpActivity.KEY_PAGE_NAME, PageName)
                .putExtra(
                    RaftCmpActivity.KEY_PAGE_DATA,
                    JSONObject().put("runNonce", nonce).toString(),
                )
                .putExtra(RaftCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<RaftCmpActivity>(intent).use { scenario ->
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
            //
            // How far one slow swipe travels depends on the platform scroll
            // physics (CMP LazyColumn: drag distance minus touch slop, with
            // almost no fling for a 650ms linear swipe) and on emulator speed,
            // so a fixed gesture budget is flaky. Keep swiping while the
            // newest visible message still advances; once the list stops
            // advancing it is pinned at the loaded tail (well inside the
            // near-end threshold), and the fresh gestures made there must
            // already have started the next page.
            val approach = approachNewerEdgeUntilSecondRequest(instrumentation, scenario, nonce)
            recordEvidence("THREAD_NEWER_APPROACH", approach.toString())
            val secondRequest = waitForAfterRequestCount(nonce, expectedCount = 2)
            assertEquals(46L, secondRequest.afterRequests[1].afterSeq)
            assertEquals(30, secondRequest.afterRequests[1].limit)
            recordEvidence("THREAD_NEWER_SECOND_REQUEST", "snapshot=$secondRequest")

            assertTrue(
                "The parked second newer request was not released as an error",
                ThreadNewerAutoPaginationVisualFixtureProbe.releaseError(nonce, callNumber = 2),
            )
            waitForReturnedCall(nonce, callNumber = 2)
            // The Retry row must be fully inside the list viewport and the
            // Back-to-bottom pill must be gone before it is clicked. A Retry
            // row that has only scrolled a few pixels into view sits under
            // the pill, so a tap on its visible strip would jump to latest
            // instead of retrying the failed page.
            val revealedRetry = revealUnobstructedRetryRow(instrumentation, scenario)
            recordEvidence("THREAD_NEWER_RETRY_REVEALED", revealedRetry.toString())
            val beforeRetry = fixtureSnapshot(nonce)
            assertEquals(
                "Revealing the failed newer boundary must not manufacture a retry request: $beforeRetry",
                2,
                beforeRetry.afterRequests.size,
            )
            val retryNode = waitForAccessibilityText(instrumentation, RetryLabel)
            assertTrue(
                "Error must expose an explicit Retry action",
                retryNode.isEnabled,
            )
            performAccessibilityClick(retryNode, RetryLabel)
            instrumentation.waitForIdleSync()

            val retryRequest = waitForRetryRequest(nonce, beforeRetry)
            assertEquals(
                "Retry must request from the same committed tail after the failed page: $retryRequest",
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

    /**
     * Performs direct toward-newer gestures until the second newer request
     * starts. The loop is bounded by observable progress, not a fixed gesture
     * count: it stops once [MaxStalledApproachGestures] consecutive gestures
     * fail to advance the newest visible message (the list is pinned at the
     * loaded tail) or after the [MaxTowardNewerApproachGestures] safety cap.
     */
    private fun approachNewerEdgeUntilSecondRequest(
        instrumentation: Instrumentation,
        scenario: ActivityScenario<RaftCmpActivity>,
        nonce: String,
    ): ApproachResult {
        var gestures = 0
        var stalledGestures = 0
        var newestVisibleSeq = newestVisibleFixtureSeq(instrumentation)
        val progress = mutableListOf(newestVisibleSeq)
        while (
            fixtureSnapshot(nonce).afterRequests.size < 2 &&
            stalledGestures < MaxStalledApproachGestures &&
            gestures < MaxTowardNewerApproachGestures
        ) {
            performUpwardGesture(instrumentation, scenario, durationMs = SecondGestureDurationMs)
            gestures += 1
            if (awaitAfterRequestCountAtLeast(nonce, expectedCount = 2, timeoutMs = ApproachRequestPollMs)) {
                break
            }
            val newest = newestVisibleFixtureSeq(instrumentation)
            progress += newest
            if (newest > newestVisibleSeq) {
                newestVisibleSeq = newest
                stalledGestures = 0
            } else {
                stalledGestures += 1
            }
        }
        return ApproachResult(
            gestures = gestures,
            newestVisibleSeqAfterEachGesture = progress,
            stalledGestures = stalledGestures,
        )
    }

    private fun newestVisibleFixtureSeq(instrumentation: Instrumentation): Long =
        visibleFixtureMarkers(instrumentation, 1L..80L).maxOfOrNull { marker -> marker.seq } ?: -1L

    private fun awaitAfterRequestCountAtLeast(nonce: String, expectedCount: Int, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            if ((fixtureSnapshotOrNull(nonce)?.afterRequests?.size ?: 0) >= expectedCount) return true
            if (System.nanoTime() >= deadline) return false
            SystemClock.sleep(ObservationPollMs)
        }
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

    /**
     * Waits for the request the Retry click produced and proves it was the
     * retry itself. A tap misrouted to Back-to-bottom reloads the latest
     * window through the initial (no cursor) request and then pages newer
     * from that window's tail, so it would otherwise surface only as a wrong
     * afterSeq.
     */
    private fun waitForRetryRequest(
        nonce: String,
        beforeRetry: ThreadNewerAutoPaginationFixtureSnapshot,
    ): ThreadNewerAutoPaginationFixtureSnapshot {
        var latest: ThreadNewerAutoPaginationFixtureSnapshot? = null
        try {
            waitUntil(RequestTimeoutMs, "Retry newer request or a misrouted latest-window reload") {
                latest = fixtureSnapshotOrNull(nonce)
                val snapshot = latest ?: return@waitUntil false
                snapshot.afterRequests.size >= 3 ||
                    snapshot.initialRequestCount != beforeRetry.initialRequestCount
            }
        } catch (failure: AssertionError) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            throw AssertionError(
                "${failure.message}; before=$beforeRetry latest=$latest; " +
                    "accessibility=${accessibilityTreeSummary(instrumentation)}",
                failure,
            )
        }
        val snapshot = requireNotNull(latest)
        assertEquals(
            "Retry click reloaded the latest window (jump-to-latest) instead of retrying the failed page: " +
                "before=$beforeRetry after=$snapshot",
            beforeRetry.initialRequestCount,
            snapshot.initialRequestCount,
        )
        assertEquals(
            "Retry click must issue exactly one newer request: before=$beforeRetry after=$snapshot",
            3,
            snapshot.afterRequests.size,
        )
        return snapshot
    }

    /**
     * Scrolls toward newer until the Retry row is fully inside the list
     * viewport and the Back-to-bottom pill is no longer in the window.
     */
    private fun revealUnobstructedRetryRow(
        instrumentation: Instrumentation,
        scenario: ActivityScenario<RaftCmpActivity>,
    ): RetryRevealState {
        // Like the approach loop, keep swiping while the list still moves
        // (newest visible message or Retry row position changes) instead of
        // relying on a fixed per-gesture travel distance.
        var gestureCount = 0
        var stalledGestures = 0
        var state = retryRevealState(instrumentation)
        var position = state.retryBounds?.top to newestVisibleFixtureSeq(instrumentation)
        while (
            !state.ready &&
            stalledGestures < MaxStalledRetryRevealGestures &&
            gestureCount < MaxRetryRevealGestures
        ) {
            performUpwardGesture(instrumentation, scenario, durationMs = SecondGestureDurationMs)
            gestureCount += 1
            state = retryRevealState(instrumentation)
            val nextPosition = state.retryBounds?.top to newestVisibleFixtureSeq(instrumentation)
            stalledGestures = if (nextPosition == position) stalledGestures + 1 else 0
            position = nextPosition
        }
        try {
            // The pill unmounts on the frame after the list reaches its end,
            // and a settling fling can still move the row; require the ready
            // state at the same bounds on consecutive samples.
            var previous: RetryRevealState? = null
            var stableSamples = 0
            waitUntil(AccessibilityTimeoutMs, "stable, fully visible, unobstructed '$RetryLabel' row") {
                state = retryRevealState(instrumentation)
                stableSamples =
                    if (state.ready && previous?.ready == true && previous?.retryBounds == state.retryBounds) {
                        stableSamples + 1
                    } else {
                        0
                    }
                previous = state
                stableSamples >= RetryRevealStableSamplesRequired
            }
        } catch (failure: AssertionError) {
            throw AssertionError(
                "${failure.message} after $gestureCount reveal gestures: $state; " +
                    "accessibility=${accessibilityTreeSummary(instrumentation)}",
                failure,
            )
        }
        return state.copy(gestureCount = gestureCount)
    }

    private fun retryRevealState(instrumentation: Instrumentation): RetryRevealState {
        val root = instrumentation.uiAutomation.rootInActiveWindow
            ?: return RetryRevealState(retryBounds = null, viewportBounds = null, backToBottomBounds = emptyList())
        val retryNode = root.raftFindNodesByText(RetryLabel).firstOrNull()
        val backToBottomBounds =
            root.raftFindNodesByText(BackToBottomLabel)
                .filter { node -> node.isVisibleToUser }
                .map { node -> Rect().also(node::getBoundsInScreen) }
                .filter { bounds -> bounds.width() > 0 && bounds.height() > 0 }
        if (retryNode == null) {
            return RetryRevealState(retryBounds = null, viewportBounds = null, backToBottomBounds = backToBottomBounds)
        }
        val target = clickableAncestorOrSelf(retryNode) ?: retryNode
        return RetryRevealState(
            retryBounds = Rect().also(target::getBoundsInScreen),
            viewportBounds = viewportBounds(target),
            backToBottomBounds = backToBottomBounds,
            minRetryHeightPx =
                RetryRowMinVisibleHeightDp * instrumentation.targetContext.resources.displayMetrics.density,
        )
    }

    private fun clickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(MaxAccessibilityClickAncestorDepth) {
            val candidate = current ?: return null
            if (candidate.isClickableForTest()) return candidate
            current = candidate.parent
        }
        return null
    }

    private fun AccessibilityNodeInfo.isClickableForTest(): Boolean =
        isClickable || actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK }

    /**
     * Bounds of the container that clips [node]: the nearest scrollable
     * ancestor, or else the nearest ancestor that extends past the node
     * vertically (the Kuikly list host does not always report itself as
     * scrollable). Accessibility bounds are already intersected with that
     * clip, so a node that touches one of its vertical edges has been cut
     * by it and is treated as partially scrolled out.
     */
    private fun viewportBounds(node: AccessibilityNodeInfo): Rect {
        // A scrolling ancestor is the real clip; prefer it when the tree exposes one.
        var scrollable: AccessibilityNodeInfo? = node.parent
        var scrollDepth = 0
        while (scrollable != null && scrollDepth < MaxAccessibilityTreeDepth) {
            if (scrollable.isScrollable) return Rect().also(scrollable::getBoundsInScreen)
            scrollable = scrollable.parent
            scrollDepth += 1
        }
        val nodeBounds = Rect().also(node::getBoundsInScreen)
        var current: AccessibilityNodeInfo? = node.parent
        var outermost: AccessibilityNodeInfo = node
        var depth = 0
        while (current != null && depth < MaxAccessibilityTreeDepth) {
            val bounds = Rect().also(current::getBoundsInScreen)
            if (current.isScrollable || bounds.top < nodeBounds.top || bounds.bottom > nodeBounds.bottom) {
                return bounds
            }
            outermost = current
            current = current.parent
            depth += 1
        }
        return Rect().also(outermost::getBoundsInScreen)
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
        return root.raftFindNodesByText(label)
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
                    ?.raftFindNodesByText(text)
                    ?.firstOrNull()
            node != null
        }
        return requireNotNull(node)
    }

    private fun performAccessibilityClick(node: AccessibilityNodeInfo, label: String) {
        var current: AccessibilityNodeInfo? = node
        var tapTarget: AccessibilityNodeInfo? = null
        repeat(MaxAccessibilityClickAncestorDepth) {
            val candidate = current ?: return@repeat
            if (candidate.isClickableForTest()) {
                if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    return
                }
                val bounds = Rect()
                candidate.getBoundsInScreen(bounds)
                if (tapTarget == null && bounds.width() > 0 && bounds.height() > 0) {
                    tapTarget = candidate
                }
            }
            current = candidate.parent
        }
        val target = tapTarget ?: throw AssertionError("No clickable accessibility ancestor for '$label'")
        val bounds = Rect().also(target::getBoundsInScreen)
        // A raw tap goes to whatever is on top at that point. Only fall back
        // to it when the target is entirely on screen and nothing clickable is
        // drawn over its centre; otherwise the tap would silently drive a
        // different control (for example the Back-to-bottom pill).
        val viewport = viewportBounds(target)
        if (!isFullyInside(bounds, viewport)) {
            throw AssertionError(
                "Refusing to tap '$label': target bounds=$bounds are clipped by viewport=$viewport",
            )
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val obstruction = clickableNodeDrawnOver(instrumentation, target, bounds.centerX(), bounds.centerY())
        if (obstruction != null) {
            throw AssertionError(
                "Refusing to tap '$label' at (${bounds.centerX()}, ${bounds.centerY()}): target bounds=$bounds " +
                    "is covered by $obstruction",
            )
        }
        val command = "input touchscreen tap ${bounds.centerX()} ${bounds.centerY()}"
        val output =
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).bufferedReader().use { reader -> reader.readText() }
        println("THREAD_NEWER_TAP label=$label bounds=$bounds command=$command output=$output")
        instrumentation.waitForIdleSync()
        SystemClock.sleep(AccessibilityTapSettleMs)
    }

    /**
     * Returns a description of a clickable node that contains ([x], [y]) and
     * is drawn above [target], or null when the point belongs to [target].
     * Z-order is resolved at the lowest common ancestor by drawing order,
     * then child index (later siblings draw on top).
     */
    private fun clickableNodeDrawnOver(
        instrumentation: Instrumentation,
        target: AccessibilityNodeInfo,
        x: Int,
        y: Int,
    ): String? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return "<no-root>"
        val targetPath = pathFromRoot(target)
        var obstruction: String? = null
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || obstruction != null || depth > MaxAccessibilityTreeDepth) return
            val bounds = Rect().also(node::getBoundsInScreen)
            if (!bounds.contains(x, y)) return
            if (node.isVisibleToUser && node.isClickableForTest()) {
                val nodePath = pathFromRoot(node)
                if (isDrawnAbove(nodePath, targetPath)) {
                    obstruction =
                        "node(text=${node.text} description=${node.contentDescription} " +
                        "class=${node.className} bounds=$bounds)"
                    return
                }
            }
            for (index in 0 until node.childCount) {
                visit(node.getChild(index), depth + 1)
            }
        }
        visit(root, 0)
        return obstruction
    }

    private fun pathFromRoot(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val path = ArrayDeque<AccessibilityNodeInfo>()
        var current: AccessibilityNodeInfo? = node
        while (current != null && path.size <= MaxAccessibilityTreeDepth) {
            path.addFirst(current)
            current = current.parent
        }
        return path.toList()
    }

    /** True when [nodePath]'s node is drawn over [targetPath]'s node and is not its ancestor or descendant. */
    private fun isDrawnAbove(
        nodePath: List<AccessibilityNodeInfo>,
        targetPath: List<AccessibilityNodeInfo>,
    ): Boolean {
        val shared = nodePath.zip(targetPath).takeWhile { (a, b) -> a == b }.size
        if (shared == 0 || shared == nodePath.size || shared == targetPath.size) return false
        val parent = nodePath[shared - 1]
        val nodeBranch = nodePath[shared]
        val targetBranch = targetPath[shared]
        val nodeOrder = nodeBranch.drawingOrder to childIndex(parent, nodeBranch)
        val targetOrder = targetBranch.drawingOrder to childIndex(parent, targetBranch)
        return compareValuesBy(nodeOrder, targetOrder, { it.first }, { it.second }) > 0
    }

    private fun childIndex(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Int =
        (0 until parent.childCount).firstOrNull { index -> parent.getChild(index) == child } ?: -1

    private fun hasAccessibilityText(instrumentation: Instrumentation, text: String): Boolean =
        instrumentation.uiAutomation.rootInActiveWindow
            ?.raftFindNodesByText(text)
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
        scenario: ActivityScenario<RaftCmpActivity>,
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
        scenario: ActivityScenario<RaftCmpActivity>,
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

    private data class RetryRevealState(
        val retryBounds: Rect?,
        val viewportBounds: Rect?,
        val backToBottomBounds: List<Rect>,
        val gestureCount: Int = 0,
        // Accessibility bounds are clipped, so a Retry target this short is cut off whichever
        // ancestor the viewport heuristic picked (a misrouted run showed a 26px strip).
        val minRetryHeightPx: Float = 0f,
    ) {
        val fullyVisible: Boolean
            get() = retryBounds != null &&
                viewportBounds != null &&
                retryBounds.height() >= minRetryHeightPx &&
                isFullyInside(retryBounds, viewportBounds)

        val ready: Boolean
            get() = fullyVisible && backToBottomBounds.isEmpty()

        override fun toString(): String =
            "RetryRevealState(retry=$retryBounds viewport=$viewportBounds fullyVisible=$fullyVisible " +
                "backToBottom=$backToBottomBounds gestures=$gestureCount)"
    }

    private data class ApproachResult(
        val gestures: Int,
        val newestVisibleSeqAfterEachGesture: List<Long>,
        val stalledGestures: Int,
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
        // Well under the button's laid-out height, well over the clipped strips seen in flakes.
        private const val RetryRowMinVisibleHeightDp = 24f
        private const val GestureSettleMs = 250L
        private const val AccessibilityTapSettleMs = 300L
        private const val FirstGestureDurationMs = 650L
        private const val SecondGestureDurationMs = 650L
        // Safety cap only: the approach loop is bounded by observed progress,
        // and every non-stalled gesture advances at least one of the 46
        // loaded rows.
        private const val MaxTowardNewerApproachGestures = 50
        private const val MaxStalledApproachGestures = 3
        private const val ApproachRequestPollMs = 400L
        private const val FocusedHistoryGestureXFraction = 0.85f
        private const val TailRepositionGestureXFraction = 0.50f
        private const val RepositionGestureDurationMs = 650L
        private const val MaxAccessibilitySummaryEntries = 120
        private const val MaxAccessibilitySummaryChars = 8_000
        private const val MaxAccessibilityClickAncestorDepth = 8
        private const val MaxAccessibilityTreeDepth = 64
        // Safety cap only; the reveal loop stops once gestures stop moving the list.
        private const val MaxRetryRevealGestures = 50
        private const val MaxStalledRetryRevealGestures = 3
        private const val RetryRevealStableSamplesRequired = 2
        private const val EvidenceStatusCode = 2
    }
}

/**
 * Accessibility bounds are intersected with the clipping viewport, so a node
 * that touches a vertical viewport edge is treated as partially scrolled out.
 */
private fun isFullyInside(bounds: Rect, viewport: Rect): Boolean =
    bounds.width() > 0 &&
        bounds.height() > 0 &&
        bounds.top > viewport.top &&
        bounds.bottom < viewport.bottom &&
        bounds.left >= viewport.left &&
        bounds.right <= viewport.right
