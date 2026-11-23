package build.raft.app.thread.experiment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualTimelineCoreUnitTest {
    @Test
    fun fenwickHeightIndexMatchesLinearGroundTruthAcrossHeterogeneousUpdates() {
        val count = 400
        val estimatedPx = 72
        val index = VirtualTimelineHeightIndex(initialEstimatedHeightPx = estimatedPx)
        val keys = List(count) { i -> "msg:m$i" }
        index.syncKeys(keys)

        assertEquals(count * estimatedPx, index.totalHeightPx)
        assertEquals(0, index.offsetOf(0))
        assertEquals(250 * estimatedPx, index.offsetOf(250))

        // Mutate a heterogeneous subset of heights (48px..420px) like real chat rows.
        val expectedHeights = IntArray(count) { estimatedPx }
        val pattern = intArrayOf(48, 72, 128, 240, 340, 420)
        for (i in 0 until count step 3) {
            val h = pattern[i % pattern.size]
            expectedHeights[i] = h
            index.recordMeasuredHeight(i, keys[i], h)
        }

        // Verify prefix sums and O(log N) binary lifting against exact O(N) ground truth.
        var runningOffset = 0
        for (i in 0 until count) {
            assertEquals(runningOffset, index.offsetOf(i), "offsetOf($i) mismatch")
            assertEquals(expectedHeights[i], index.heightOf(i), "heightOf($i) mismatch")
            assertEquals(i, index.indexAtOffset(runningOffset), "start boundary indexAtOffset($runningOffset)")
            assertEquals(
                i,
                index.indexAtOffset(runningOffset + expectedHeights[i] - 1),
                "end boundary indexAtOffset for row $i",
            )
            runningOffset += expectedHeights[i]
        }
        assertEquals(runningOffset, index.totalHeightPx)
        assertTrue(index.lastBinaryLiftSteps <= 10, "400 items must resolve in <= 10 BIT steps, got ${index.lastBinaryLiftSteps}")

        // Verify zero-allocation visibleRange output matches linear scan.
        val range = IntArray(2)
        val vpTop = index.offsetOf(180) + 12
        val vpBottom = vpTop + 800
        assertTrue(index.visibleRange(vpTop, vpBottom, range))
        assertEquals(180, range[0])
        var linearLast = 180
        while (linearLast + 1 < count && index.offsetOf(linearLast + 1) < vpBottom) {
            linearLast += 1
        }
        assertEquals(linearLast, range[1])
    }

    @Test
    fun heightIndexPreservesCachedHeightsAcrossPrepend() {
        val index = VirtualTimelineHeightIndex(initialEstimatedHeightPx = 72)
        val initialKeys = List(50) { i -> "msg:m${100 + i}" }
        index.syncKeys(initialKeys)

        index.recordMeasuredHeight(0, "msg:m100", 310)
        index.recordMeasuredHeight(5, "msg:m105", 420)
        assertTrue(index.hasMeasuredHeight("msg:m100"))
        assertEquals(2, index.cachedMeasuredCount())

        // Prepend 40 older messages.
        val prependedKeys = List(40) { i -> "msg:m${60 + i}" } + initialKeys
        assertTrue(index.syncKeys(prependedKeys))

        assertEquals(40, index.indexOfKey("msg:m100"))
        assertEquals(45, index.indexOfKey("msg:m105"))
        assertEquals(310, index.heightOf(40), "cached measured height must persist across prepend")
        assertEquals(420, index.heightOf(45), "cached measured height must persist across prepend")
        assertEquals(40 * 72, index.offsetOf(40))
    }

    @Test
    fun collapsedSystemRowsCoalesce100EventsIntoOneVirtualRowAndResolveFoldedJumpKeys() {
        val messages = buildList {
            for (i in 0 until 20) {
                add(VirtualTimelineMessage(id = "u$i", author = "Alice", body = "User message $i"))
            }
            for (i in 0 until 100) {
                add(
                    VirtualTimelineMessage(
                        id = "sys$i",
                        author = "System",
                        body = "Agent moved #$i to Done",
                        isSystemFoldable = true,
                    )
                )
            }
            for (i in 20 until 40) {
                add(VirtualTimelineMessage(id = "u$i", author = "Bob", body = "User message $i"))
            }
        }

        val collapsed = coalesceVirtualTimelineRows(messages)
        assertEquals(140, collapsed.sourceMessageCount)
        assertEquals(41, collapsed.rows.size, "20 + 1 coalesced group + 20 = 41 virtual rows")
        assertEquals(99, collapsed.collapsedZeroHeightRowsEliminated)

        val groupRow = collapsed.rows[20] as VirtualTimelineRow.CollapsedSystemGroupRow
        assertEquals(100, groupRow.summaryCount)
        assertEquals(groupRow.key, collapsed.resolveRowKeyForMessageId("sys0"))
        assertEquals(groupRow.key, collapsed.resolveRowKeyForMessageId("sys57"))
        assertEquals(groupRow.key, collapsed.resolveRowKeyForMessageId("sys99"))
        assertEquals(groupRow.groupId, collapsed.groupIdForFoldedMessage("sys57"))

        // Expanding the group restores all 100 individual rows with zero eliminated placeholders.
        val expanded = coalesceVirtualTimelineRows(messages, expandedGroupIds = setOf(groupRow.groupId))
        assertEquals(140, expanded.rows.size)
        assertEquals(0, expanded.collapsedZeroHeightRowsEliminated)
        assertEquals("msg:sys57", expanded.resolveRowKeyForMessageId("sys57"))
        assertNull(expanded.groupIdForFoldedMessage("sys57"))
    }

    @Test
    fun searchCoordinatorMatchesFullUnmountedCorpusAndRequestsContextLoad() {
        val corpus =
            List(320) { i ->
                val body =
                    if (i in setOf(8, 95, 182, 265, 312)) {
                        "Important needle match at row $i"
                    } else {
                        "Routine message body $i"
                    }
                VirtualTimelineMessage(id = "m$i", author = "User${i % 5}", body = body)
            }

        val coordinator = VirtualTimelineSearchCoordinator()
        coordinator.updateCorpus(corpus)
        coordinator.setQuery("NEEDLE")

        assertEquals(listOf("m8", "m95", "m182", "m265", "m312"), coordinator.state.matches)
        assertEquals("m8", coordinator.state.activeMatchMessageId)
        assertEquals("1/5", coordinator.state.countLabel)

        // Only tail messages 220..319 are currently loaded in the timeline window.
        val tailProjection = coalesceVirtualTimelineRows(corpus.subList(220, 320))
        val timelineState = VirtualMessageTimelineState(initialEstimatedItemHeightPx = 72)
        timelineState.syncKeys(tailProjection.rowKeys, triggerMeasure = false)

        var requestedContextId: String? = null
        val outcome =
            coordinator.jumpToActiveMatch(
                timelineState = timelineState,
                projection = tailProjection,
                onLoadContextWindow = { id -> requestedContextId = id },
            )
        assertEquals(VirtualSearchJumpOutcome.RequestedContextLoad, outcome)
        assertEquals("m8", requestedContextId)
        assertTrue(timelineState.isAutoLoadSuppressed, "pending context search jump must suppress edge auto-load")
        assertTrue(timelineState.isJumpSettling)

        // Hydrate full window and verify coordinator resolves the pending jump cleanly.
        val fullProjection = coalesceVirtualTimelineRows(corpus)
        timelineState.syncKeys(fullProjection.rowKeys, triggerMeasure = false)
        assertTrue(coordinator.onContextWindowLoaded(timelineState, fullProjection))
        assertNull(coordinator.state.pendingContextLoadMessageId)

        // Stepping wraps cleanly forward and backward.
        assertEquals("m95", coordinator.nextMatch())
        assertEquals("2/5", coordinator.state.countLabel)
        assertEquals("m8", coordinator.previousMatch())
        assertEquals("m312", coordinator.previousMatch())
        assertEquals("5/5", coordinator.state.countLabel)

        coordinator.close()
        assertFalse(coordinator.state.open)
        assertNull(coordinator.state.activeMatchMessageId)
        assertNotNull(coordinator.state.matches.firstOrNull())
    }

    @Test
    fun stabilityTracerClassifiesCompensatedStateChangesAndDetectsUnexpectedJumps() {
        val tracer = VirtualTimelineStabilityTracer()
        val logged = mutableListOf<VirtualTimelineStabilityEvent>()

        // Pass 1: Initial open at mid-history (scroll=1000, maxScroll=3000), anchor m20 at offset=100.
        val pass1Visible =
            listOf(
                VirtualTimelineVisibleItem(index = 19, key = "msg:m19", offsetPx = -20, sizePx = 120),
                VirtualTimelineVisibleItem(index = 20, key = "msg:m20", offsetPx = 100, sizePx = 140),
                VirtualTimelineVisibleItem(index = 21, key = "msg:m21", offsetPx = 240, sizePx = 140),
            )
        val e1 =
            tracer.onMeasureFinished(
                passNumber = 1,
                count = 60,
                viewportHeightPx = 1000,
                topPaddingPx = 0,
                bottomPaddingPx = 0,
                finalScrollInt = 1000,
                finalMaxScroll = 3000,
                sparseAnchorBottom = true,
                followBottom = false,
                isScrollInProgress = false,
                wasProgrammaticJump = true,
                anyMeasuredRowHeightChanged = false,
                measuredItemCount = 8,
                pinnedReadingAnchorKey = null,
                visibleSnapshot = pass1Visible,
                onEvent = logged::add,
            )
        assertNotNull(e1)
        assertEquals(VirtualTimelineTransitionCause.InitialOpen, e1.cause)
        assertFalse(e1.unexpectedJump)
        assertEquals("msg:m20", tracer.trackedAnchorKey)

        // Pass 2: Prepend 15 older rows (+1080px compensated), m20 stays at offsetPx=100 -> driftPx=0, ok.
        tracer.noteKeyListChange(VirtualTimelineTransitionCause.Prepend)
        val pass2Visible =
            listOf(
                VirtualTimelineVisibleItem(index = 34, key = "msg:m19", offsetPx = -20, sizePx = 120),
                VirtualTimelineVisibleItem(index = 35, key = "msg:m20", offsetPx = 100, sizePx = 140),
                VirtualTimelineVisibleItem(index = 36, key = "msg:m21", offsetPx = 240, sizePx = 140),
            )
        val e2 =
            tracer.onMeasureFinished(
                passNumber = 2,
                count = 75,
                viewportHeightPx = 1000,
                topPaddingPx = 0,
                bottomPaddingPx = 0,
                finalScrollInt = 2080,
                finalMaxScroll = 4080,
                sparseAnchorBottom = true,
                followBottom = false,
                isScrollInProgress = false,
                wasProgrammaticJump = false,
                anyMeasuredRowHeightChanged = false,
                measuredItemCount = 9,
                pinnedReadingAnchorKey = null,
                visibleSnapshot = pass2Visible,
                onEvent = logged::add,
            )
        assertNotNull(e2)
        assertEquals(VirtualTimelineTransitionCause.Prepend, e2.cause)
        assertEquals(0, e2.anchorDriftPx)
        assertEquals(1080, e2.compensatedScrollDeltaPx)
        assertFalse(e2.unexpectedJump)
        assertTrue(e2.toDiagnosticsLine().contains("status=ok cause=prepend"))

        // Pass 3: User scroll moves m20 by -250px -> NOT a jump and emits no spam log.
        tracer.noteUserScrollDelta()
        val pass3Visible =
            listOf(
                VirtualTimelineVisibleItem(index = 35, key = "msg:m20", offsetPx = -150, sizePx = 140),
                VirtualTimelineVisibleItem(index = 36, key = "msg:m21", offsetPx = -10, sizePx = 140),
                VirtualTimelineVisibleItem(index = 37, key = "msg:m22", offsetPx = 130, sizePx = 140),
            )
        val e3 =
            tracer.onMeasureFinished(
                passNumber = 3,
                count = 75,
                viewportHeightPx = 1000,
                topPaddingPx = 0,
                bottomPaddingPx = 0,
                finalScrollInt = 2330,
                finalMaxScroll = 4080,
                sparseAnchorBottom = true,
                followBottom = false,
                isScrollInProgress = true,
                wasProgrammaticJump = false,
                anyMeasuredRowHeightChanged = false,
                measuredItemCount = 9,
                pinnedReadingAnchorKey = null,
                visibleSnapshot = pass3Visible,
                onEvent = logged::add,
            )
        assertNull(e3)
        assertEquals("msg:m22", tracer.trackedAnchorKey)

        // Pass 4: Synthetic uncompensated row-height jump (+64px shift on m22) -> flagged as JUMP!
        val pass4Visible =
            listOf(
                VirtualTimelineVisibleItem(index = 37, key = "msg:m22", offsetPx = 194, sizePx = 140),
            )
        val e4 =
            tracer.onMeasureFinished(
                passNumber = 4,
                count = 75,
                viewportHeightPx = 1000,
                topPaddingPx = 0,
                bottomPaddingPx = 0,
                finalScrollInt = 2330,
                finalMaxScroll = 4144,
                sparseAnchorBottom = true,
                followBottom = false,
                isScrollInProgress = false,
                wasProgrammaticJump = false,
                anyMeasuredRowHeightChanged = true,
                measuredItemCount = 8,
                pinnedReadingAnchorKey = null,
                visibleSnapshot = pass4Visible,
                onEvent = logged::add,
            )
        assertNotNull(e4)
        assertEquals(VirtualTimelineTransitionCause.RowHeightChange, e4.cause)
        assertEquals(64, e4.anchorDriftPx)
        assertTrue(e4.unexpectedJump)
        assertTrue(e4.toDiagnosticsLine().contains("status=JUMP cause=row_height_change"))

        val summary = tracer.summary(maxJumpAlignmentErrorPx = 0)
        assertEquals(4, summary.totalPasses)
        assertEquals(1, summary.unexpectedJumpCount)
        assertEquals(64, summary.maxAnchorDriftPx)
        assertEquals(1080, summary.maxCompensatedPx)
        assertTrue(summary.toDiagnosticsLine().contains("vtl_session_summary"))
    }

    @Test
    fun stabilityTracerDetectsOffScreenTrackedAnchorJumpViaFallbackOffset() {
        val tracer = VirtualTimelineStabilityTracer()
        // Pass 1: Initial open with m20 anchored at offsetPx=40.
        tracer.onMeasureFinished(
            passNumber = 1,
            count = 80,
            viewportHeightPx = 1000,
            topPaddingPx = 0,
            bottomPaddingPx = 0,
            finalScrollInt = 1400,
            finalMaxScroll = 4000,
            sparseAnchorBottom = true,
            followBottom = false,
            isScrollInProgress = false,
            wasProgrammaticJump = true,
            anyMeasuredRowHeightChanged = false,
            measuredItemCount = 8,
            pinnedReadingAnchorKey = null,
            visibleSnapshot =
                listOf(
                    VirtualTimelineVisibleItem(index = 20, key = "msg:m20", offsetPx = 40, sizePx = 120),
                    VirtualTimelineVisibleItem(index = 21, key = "msg:m21", offsetPx = 160, sizePx = 120),
                ),
        )
        assertEquals("msg:m20", tracer.trackedAnchorKey)

        // Pass 2: Uncompensated prepend pushes m20 completely off-screen (+1440px below viewport).
        // visibleSnapshot no longer contains m20, but trackedAnchorFallbackOffsetPx reports 1480.
        tracer.noteKeyListChange(VirtualTimelineTransitionCause.Prepend)
        val e2 =
            tracer.onMeasureFinished(
                passNumber = 2,
                count = 100,
                viewportHeightPx = 1000,
                topPaddingPx = 0,
                bottomPaddingPx = 0,
                finalScrollInt = 1400,
                finalMaxScroll = 5440,
                sparseAnchorBottom = true,
                followBottom = false,
                isScrollInProgress = false,
                wasProgrammaticJump = false,
                anyMeasuredRowHeightChanged = false,
                measuredItemCount = 8,
                pinnedReadingAnchorKey = null,
                visibleSnapshot =
                    listOf(
                        VirtualTimelineVisibleItem(index = 20, key = "msg:m0", offsetPx = 40, sizePx = 120),
                    ),
                trackedAnchorFallbackOffsetPx = 1480,
            )
        assertNotNull(e2)
        assertEquals(VirtualTimelineTransitionCause.Prepend, e2.cause)
        assertEquals(1440, e2.anchorDriftPx)
        assertTrue(e2.unexpectedJump, "Off-screen tracked anchor shift must be flagged as JUMP")
        assertEquals(1, tracer.summary().unexpectedJumpCount)
        assertEquals(1440, tracer.summary().maxAnchorDriftPx)
    }

    @Test
    fun stabilityTracerHandlesEmptyPassTopPaddingToggleTopEdgeClampProgrammaticScrollAndZeroHeightTail() {
        val tracer = VirtualTimelineStabilityTracer()

        // 1. Empty/0-height pass even when a jump is pending stays Steady and emits no event.
        val e0 =
            tracer.onMeasureFinished(
                passNumber = 1,
                count = 0,
                viewportHeightPx = 0,
                topPaddingPx = 0,
                bottomPaddingPx = 0,
                finalScrollInt = 0,
                finalMaxScroll = 0,
                sparseAnchorBottom = true,
                followBottom = true,
                isScrollInProgress = false,
                wasProgrammaticJump = true,
                anyMeasuredRowHeightChanged = false,
                measuredItemCount = 0,
                pinnedReadingAnchorKey = null,
                visibleSnapshot = emptyList(),
            )
        assertNull(e0)

        // 2. Initial open scrolled slightly down from top (scroll=30, maxScroll=2000), m5 at offsetPx=50.
        val e1 =
            tracer.onMeasureFinished(
                passNumber = 2,
                count = 40,
                viewportHeightPx = 800,
                topPaddingPx = 0,
                bottomPaddingPx = 0,
                finalScrollInt = 30,
                finalMaxScroll = 2000,
                sparseAnchorBottom = true,
                followBottom = false,
                isScrollInProgress = false,
                wasProgrammaticJump = true,
                anyMeasuredRowHeightChanged = false,
                measuredItemCount = 8,
                pinnedReadingAnchorKey = null,
                visibleSnapshot = listOf(VirtualTimelineVisibleItem(index = 5, key = "msg:m5", offsetPx = 50, sizePx = 100)),
            )
        assertNotNull(e1)
        assertEquals(VirtualTimelineTransitionCause.InitialOpen, e1.cause)

        // 3. topPaddingPx changes (e.g., inline search bar opens: 0 -> 64) while content offsetPx stays 50 -> 0 drift.
        val e2 =
            tracer.onMeasureFinished(
                passNumber = 3,
                count = 40,
                viewportHeightPx = 800,
                topPaddingPx = 64,
                bottomPaddingPx = 0,
                finalScrollInt = 30,
                finalMaxScroll = 2064,
                sparseAnchorBottom = true,
                followBottom = false,
                isScrollInProgress = false,
                wasProgrammaticJump = false,
                anyMeasuredRowHeightChanged = false,
                measuredItemCount = 8,
                pinnedReadingAnchorKey = null,
                visibleSnapshot = listOf(VirtualTimelineVisibleItem(index = 5, key = "msg:m5", offsetPx = 50, sizePx = 100)),
            )
        assertNotNull(e2)
        assertEquals(VirtualTimelineTransitionCause.ViewportResize, e2.cause)
        assertEquals(0, e2.anchorDriftPx)
        assertFalse(e2.unexpectedJump)

        // 4. Shrinking a row above m5 clamps scroll from 30 -> 0 (clampedAtTopEdge) -> NOT an unexpected jump.
        val e3 =
            tracer.onMeasureFinished(
                passNumber = 4,
                count = 40,
                viewportHeightPx = 800,
                topPaddingPx = 64,
                bottomPaddingPx = 0,
                finalScrollInt = 0,
                finalMaxScroll = 1980,
                sparseAnchorBottom = true,
                followBottom = false,
                isScrollInProgress = false,
                wasProgrammaticJump = false,
                anyMeasuredRowHeightChanged = true,
                measuredItemCount = 8,
                pinnedReadingAnchorKey = null,
                visibleSnapshot = listOf(VirtualTimelineVisibleItem(index = 5, key = "msg:m5", offsetPx = 10, sizePx = 100)),
            )
        assertNotNull(e3)
        assertEquals(VirtualTimelineTransitionCause.RowHeightChange, e3.cause)
        assertFalse(e3.unexpectedJump, "Top-edge scroll clamp (30 -> 0) must not trigger false JUMP")

        // 5. Non-anchorable leading header row height change while already at top (scroll 0 -> 0)
        // shifts m5 from 10 -> 50 (anchorDriftPx = 40), suppressed solely by clampedAtTopEdge.
        val eHeader =
            tracer.onMeasureFinished(
                passNumber = 5,
                count = 40,
                viewportHeightPx = 800,
                topPaddingPx = 64,
                bottomPaddingPx = 0,
                finalScrollInt = 0,
                finalMaxScroll = 2020,
                sparseAnchorBottom = true,
                followBottom = false,
                isScrollInProgress = false,
                wasProgrammaticJump = false,
                anyMeasuredRowHeightChanged = true,
                measuredItemCount = 8,
                pinnedReadingAnchorKey = null,
                visibleSnapshot =
                    listOf(
                        VirtualTimelineVisibleItem(index = 0, key = "origin", offsetPx = 0, sizePx = 120, anchorable = false),
                        VirtualTimelineVisibleItem(index = 5, key = "msg:m5", offsetPx = 50, sizePx = 100, anchorable = true),
                    ),
            )
        assertNotNull(eHeader)
        assertEquals(VirtualTimelineTransitionCause.RowHeightChange, eHeader.cause)
        assertEquals(40, eHeader.anchorDriftPx)
        assertFalse(eHeader.unexpectedJump, "Leading non-anchorable header growth at scroll=0 must not trigger false JUMP")

        // 6. bottomPaddingPx change classifies as ViewportResize.
        val eBottomPad =
            tracer.onMeasureFinished(
                passNumber = 6,
                count = 40,
                viewportHeightPx = 800,
                topPaddingPx = 64,
                bottomPaddingPx = 48,
                finalScrollInt = 0,
                finalMaxScroll = 2068,
                sparseAnchorBottom = true,
                followBottom = false,
                isScrollInProgress = false,
                wasProgrammaticJump = false,
                anyMeasuredRowHeightChanged = false,
                measuredItemCount = 8,
                pinnedReadingAnchorKey = null,
                visibleSnapshot = listOf(VirtualTimelineVisibleItem(index = 5, key = "msg:m5", offsetPx = 50, sizePx = 100)),
            )
        assertNotNull(eBottomPad)
        assertEquals(VirtualTimelineTransitionCause.ViewportResize, eBottomPad.cause)
        assertFalse(eBottomPad.unexpectedJump)

        // 7. Programmatic scroll delta classifies cleanly as ProgrammaticScroll (no false JUMP, no spam).
        tracer.noteProgrammaticScrollDelta()
        val e4 =
            tracer.onMeasureFinished(
                passNumber = 7,
                count = 40,
                viewportHeightPx = 800,
                topPaddingPx = 64,
                bottomPaddingPx = 0,
                finalScrollInt = 1980,
                finalMaxScroll = 1980,
                sparseAnchorBottom = true,
                followBottom = true,
                isScrollInProgress = false,
                wasProgrammaticJump = false,
                anyMeasuredRowHeightChanged = false,
                measuredItemCount = 8,
                pinnedReadingAnchorKey = null,
                visibleSnapshot = listOf(VirtualTimelineVisibleItem(index = 38, key = "msg:m38", offsetPx = 636, sizePx = 100)),
                tailFallbackBottomPx = 736,
            )
        assertNull(e4)

        // 8. Zero-height tail row omitted from visibleSnapshot while following bottom uses tailFallbackBottomPx -> 0 tail drift.
        val e5 =
            tracer.onMeasureFinished(
                passNumber = 8,
                count = 40,
                viewportHeightPx = 800,
                topPaddingPx = 64,
                bottomPaddingPx = 0,
                finalScrollInt = 1980,
                finalMaxScroll = 1980,
                sparseAnchorBottom = true,
                followBottom = true,
                isScrollInProgress = false,
                wasProgrammaticJump = false,
                anyMeasuredRowHeightChanged = true,
                measuredItemCount = 8,
                pinnedReadingAnchorKey = null,
                visibleSnapshot = listOf(VirtualTimelineVisibleItem(index = 38, key = "msg:m38", offsetPx = 636, sizePx = 100)),
                tailFallbackBottomPx = 736,
            )
        assertNotNull(e5)
        assertEquals(VirtualTimelineTransitionCause.RowHeightChange, e5.cause)
        assertEquals(0, e5.tailDriftPx)
        assertFalse(e5.unexpectedJump)
        assertEquals(0, tracer.summary().unexpectedJumpCount)
    }
}
