package build.raft.app.markdown

import build.raft.app.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftInlineBoxStyleTest {

    @Test
    fun singleLineChipGeometryMatchesExplicitPaddingExactly() {
        val taskBox = raftMarkdownTaskChipStyle(TaskStatus.InProgress).box
        assertEquals(0f, taskBox.paddingStart.value)
        assertEquals(0f, taskBox.paddingEnd.value)
        val (taskLeft, taskRight) =
            raftInlineBoxSegmentHorizontalBounds(
                segmentLeft = 24f,
                segmentRight = 180f,
                isFirst = true,
                isLast = true,
                paddingStartPx = 0f,
                paddingEndPx = 0f,
                splitEdgePaddingPx = 9f,
            )
        assertEquals(24f, taskLeft)
        assertEquals(180f, taskRight)

        val (refLeft, refRight) =
            raftInlineBoxSegmentHorizontalBounds(
                segmentLeft = 30f,
                segmentRight = 140f,
                isFirst = true,
                isLast = true,
                paddingStartPx = 18f,
                paddingEndPx = 18f,
                splitEdgePaddingPx = 9f,
            )
        assertEquals(12f, refLeft)
        assertEquals(158f, refRight)
    }

    @Test
    fun wrappedChipSegmentsKeepOuterPaddingAndAddBreathingRoomOnBrokenInnerEdges() {
        val splitPx = 9f
        // First wrapped fragment: keeps start padding (0f for task chip), adds breathing room on broken right edge.
        val (firstLeft, firstRight) =
            raftInlineBoxSegmentHorizontalBounds(
                segmentLeft = 20f,
                segmentRight = 280f,
                isFirst = true,
                isLast = false,
                paddingStartPx = 0f,
                paddingEndPx = 0f,
                splitEdgePaddingPx = splitPx,
            )
        assertEquals(20f, firstLeft)
        assertEquals(289f, firstRight)

        // Middle wrapped fragment: breathing room on both broken inner edges.
        val (midLeft, midRight) =
            raftInlineBoxSegmentHorizontalBounds(
                segmentLeft = 0f,
                segmentRight = 260f,
                isFirst = false,
                isLast = false,
                paddingStartPx = 18f,
                paddingEndPx = 18f,
                splitEdgePaddingPx = splitPx,
            )
        assertEquals(-9f, midLeft)
        assertEquals(269f, midRight)

        // Last wrapped fragment: breathing room on broken left edge, keeps explicit end padding.
        val (lastLeft, lastRight) =
            raftInlineBoxSegmentHorizontalBounds(
                segmentLeft = 0f,
                segmentRight = 120f,
                isFirst = false,
                isLast = true,
                paddingStartPx = 18f,
                paddingEndPx = 18f,
                splitEdgePaddingPx = splitPx,
            )
        assertEquals(-9f, lastLeft)
        assertEquals(138f, lastRight)
    }

    @Test
    fun wrappedInlineCodeWithoutChipMarginKeepsFlushInnerEdges() {
        val (firstLeft, firstRight) =
            raftInlineBoxSegmentHorizontalBounds(
                segmentLeft = 20f,
                segmentRight = 280f,
                isFirst = true,
                isLast = false,
                paddingStartPx = 12f,
                paddingEndPx = 12f,
                splitEdgePaddingPx = 0f,
            )
        assertEquals(8f, firstLeft)
        assertEquals(280f, firstRight)

        val (lastLeft, lastRight) =
            raftInlineBoxSegmentHorizontalBounds(
                segmentLeft = 0f,
                segmentRight = 100f,
                isFirst = false,
                isLast = true,
                paddingStartPx = 12f,
                paddingEndPx = 12f,
                splitEdgePaddingPx = 0f,
            )
        assertEquals(0f, lastLeft)
        assertEquals(112f, lastRight)
    }
}
