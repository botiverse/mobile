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
}
