package build.raft.designsystem

import androidx.compose.ui.geometry.Rect
import build.raft.designsystem.components.raftSegmentIndicatorBox
import build.raft.designsystem.components.raftSegmentedPagerPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The segmented pager's position and the switch's sliding sticker, as pure functions. */
class RaftSegmentedPagerTest {
    // Three segments of different widths, 3px apart, as a track lays them out.
    private val boxes = listOf(Rect(3f, 3f, 83f, 35f), Rect(86f, 3f, 146f, 35f), Rect(149f, 3f, 269f, 35f))

    @Test
    fun atAWholePositionTheStickerIsThatSegment() {
        assertEquals(boxes[0], raftSegmentIndicatorBox(0f, boxes))
        assertEquals(boxes[1], raftSegmentIndicatorBox(1f, boxes))
        assertEquals(boxes[2], raftSegmentIndicatorBox(2f, boxes))
    }

    @Test
    fun midDragTheStickerEdgesAreInterpolated() {
        // 40% of the way from the first segment to the second.
        val box = raftSegmentIndicatorBox(0.4f, boxes)!!
        assertEquals(3f + (86f - 3f) * 0.4f, box.left, 0.001f)
        assertEquals(83f + (146f - 83f) * 0.4f, box.right, 0.001f)
        assertEquals(3f, box.top)
        assertEquals(35f, box.bottom)
        // Halfway from the second to the third: its width is between theirs.
        val half = raftSegmentIndicatorBox(1.5f, boxes)!!
        assertEquals((60f + 120f) / 2f, half.width, 0.001f)
    }

    @Test
    fun pastEitherEndTheStickerStaysOnTheEndSegment() {
        assertEquals(boxes[0], raftSegmentIndicatorBox(-0.3f, boxes))
        assertEquals(boxes[2], raftSegmentIndicatorBox(2.4f, boxes))
        assertEquals(boxes[0], raftSegmentIndicatorBox(Float.NaN, boxes))
        assertNull(raftSegmentIndicatorBox(1f, emptyList()))
    }

    @Test
    fun thePositionIsThePagePlusItsOffsetWithinTheSegments() {
        assertEquals(1.4f, raftSegmentedPagerPosition(page = 1, offsetFraction = 0.4f, pageCount = 3), 0.0001f)
        assertEquals(0.6f, raftSegmentedPagerPosition(page = 1, offsetFraction = -0.4f, pageCount = 3), 0.0001f)
        // An overscroll past the ends never moves the sticker past the end segments.
        assertEquals(0f, raftSegmentedPagerPosition(page = 0, offsetFraction = -0.2f, pageCount = 3))
        assertEquals(2f, raftSegmentedPagerPosition(page = 2, offsetFraction = 0.2f, pageCount = 3))
        assertEquals(0f, raftSegmentedPagerPosition(page = 0, offsetFraction = 0f, pageCount = 0))
    }
}
