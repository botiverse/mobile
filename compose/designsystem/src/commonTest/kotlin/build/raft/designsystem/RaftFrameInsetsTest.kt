package build.raft.designsystem

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftFrameInsets
import build.raft.designsystem.components.RaftFramePx
import build.raft.designsystem.components.RaftPopupMargins
import build.raft.designsystem.components.RaftSafeArea
import build.raft.designsystem.components.RaftTooltipSide
import build.raft.designsystem.components.clearOf
import build.raft.designsystem.components.raftMenuPosition
import build.raft.designsystem.components.raftTooltipClearOfFrame
import build.raft.designsystem.components.raftTooltipSidePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** raft-ui #860 on the app: popups keep clear of the host's frame insets ([RaftFrameInsets]). */
class RaftFrameInsetsTest {
    private val density = Density(1f)
    private val window = IntSize(400, 800)
    private val noShadow = RaftPopupMargins(0, 0, 0)

    @Test
    fun noInsetsLeaveTheSafeAreaAsItIs() {
        val area = RaftSafeArea(left = 0, top = 47, right = 0, bottom = 34)
        assertSame(area, area.clearOf(RaftFrameInsets.Zero, density))
    }

    @Test
    fun anInsetGrowsTheSafeAreaOnItsSideOnly() {
        val area = RaftSafeArea(left = 0, top = 47, right = 0, bottom = 34)
        assertEquals(
            RaftSafeArea(left = 0, top = 47, right = 57, bottom = 34),
            area.clearOf(RaftFrameInsets(top = 20.dp, right = 57.dp), density),
        )
    }

    @Test
    fun aMenuStaysEightFromARightInset() {
        // A 200 wide menu under an anchor at the right edge, with a 57 column on the right.
        val area = RaftSafeArea().clearOf(RaftFrameInsets(right = 57.dp), density)
        val at = raftMenuPosition(IntRect(330, 100, 370, 140), window, IntSize(200, 120), gap = 4, windowMargin = 8, insets = noShadow, safeArea = area)
        assertEquals(400 - 57 - 8 - 200, at.x)
    }

    @Test
    fun aTooltipAboveFlipsBelowOutOfTheTopBand() {
        val anchor = IntRect(100, 50, 140, 90)
        // Material put it above, at y 10: inside a 48 band.
        val at = raftTooltipClearOfFrame(IntOffset(80, 10), anchor, window, IntSize(80, 32), spacing = 8, frame = RaftFramePx(top = 48))
        assertEquals(IntOffset(80, 98), at)
    }

    @Test
    fun aTooltipSlidesOutOfASideInset() {
        val anchor = IntRect(340, 300, 380, 340)
        val at = raftTooltipClearOfFrame(IntOffset(320, 260), anchor, window, IntSize(80, 32), spacing = 8, frame = RaftFramePx(right = 57))
        assertEquals(IntOffset(400 - 57 - 80, 260), at)
    }

    @Test
    fun noInsetsLeaveATooltipWhereMaterialPutIt() {
        val placed = IntOffset(320, -4)
        assertEquals(placed, raftTooltipClearOfFrame(placed, IntRect(340, 0, 380, 40), window, IntSize(80, 32), 8, RaftFramePx.Zero))
    }

    @Test
    fun aSideTooltipFlipsAwayFromAnInsetOnItsSide() {
        // Fits beside the anchor in the window, not beside an 80 inset on the right.
        val anchor = IntRect(220, 300, 260, 340)
        val (_, open) = raftTooltipSidePosition(anchor, window, IntSize(100, 32), 8, RaftTooltipSide.Right)
        assertEquals(RaftTooltipSide.Right, open)
        val (at, framed) = raftTooltipSidePosition(anchor, window, IntSize(100, 32), 8, RaftTooltipSide.Right, RaftFramePx(right = 80))
        assertEquals(RaftTooltipSide.Left, framed)
        assertEquals(220 - 8 - 100, at.x)
    }
}
