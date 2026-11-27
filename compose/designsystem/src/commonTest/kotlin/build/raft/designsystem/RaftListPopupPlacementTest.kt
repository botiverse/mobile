package build.raft.designsystem

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import build.raft.designsystem.components.RaftListPopupPlacement
import build.raft.designsystem.components.RaftListPopupSide
import build.raft.designsystem.components.RaftPopupMargins
import build.raft.designsystem.components.raftListPopupPlacement
import build.raft.designsystem.components.raftMenuPosition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * raft-ui 0.106.2 "List popups shrink and scroll on their side before flipping; the side axis is
 * the last resort". Sizes in px at density 1; 188 is `--popup-list-min-height` by touch.
 */
class RaftListPopupPlacementTest {
    private val min = 188

    /** A window band 8..792 (an 800px window, 8px margins), the anchor [top]..[bottom], a 6px gap. */
    private fun place(top: Int, bottom: Int, content: Int) =
        raftListPopupPlacement(anchorTop = top, anchorBottom = bottom, gap = 6, minY = 8, maxY = 792, contentHeight = content, minListHeight = min)

    @Test
    fun aListThatFitsBelowOpensBelowAtItsHeight() {
        assertEquals(RaftListPopupPlacement(RaftListPopupSide.Below, 792 - 106), place(top = 60, bottom = 100, content = 300))
    }

    @Test
    fun aLongListStaysBelowAndShrinksWhileThatHoldsFiveRows() {
        // 300px under the trigger, a 900px list: below, capped to the 300px (Base UI used to flip it above).
        val at = place(top = 446, bottom = 486, content = 900)
        assertEquals(RaftListPopupPlacement(RaftListPopupSide.Below, 792 - 492), at)
    }

    @Test
    fun withLessThanFiveRowsBelowItFlipsAboveAndShrinksThere() {
        // 150px below (< 188), 640 above.
        val at = place(top = 630, bottom = 636, content = 900)
        assertEquals(RaftListPopupPlacement(RaftListPopupSide.Above, 630 - 6 - 8), at)
    }

    @Test
    fun aShortListThatFitsAShortSideStaysThere() {
        // Three rows (116px) in the 150px below: no flip although 150 < 188.
        assertEquals(RaftListPopupSide.Below, place(top = 630, bottom = 636, content = 116).side)
    }

    @Test
    fun onlyWhenNeitherSideHoldsFiveRowsDoesItOpenBeside() {
        // A tall anchor (a long message): 100 above, 100 below.
        val at = place(top = 114, bottom = 680, content = 900)
        assertEquals(RaftListPopupPlacement(RaftListPopupSide.Beside, 792 - 8), at)
        // Four rows fit above it: there, not beside.
        assertEquals(RaftListPopupSide.Above, place(top = 160, bottom = 700, content = 140).side)
    }

    @Test
    fun besideOpensAtTheEndSideLevelWithTheAnchorAndFlipsToTheStart() {
        val noShadow = RaftPopupMargins(0, 0, 0)
        val window = IntSize(1000, 800)
        val anchor = IntRect(100, 114, 300, 680)
        // End side: 6px right of the anchor, its top level with the anchor's.
        assertEquals(
            IntOffset(306, 114),
            raftMenuPosition(anchor, window, IntSize(240, 500), gap = 6, windowMargin = 8, insets = noShadow, side = RaftListPopupSide.Beside),
        )
        // Right to left the end side is the left one; no room there (100 - 6 - 240 < 8): the right one.
        assertEquals(
            IntOffset(306, 114),
            raftMenuPosition(anchor, window, IntSize(240, 500), gap = 6, windowMargin = 8, insets = noShadow, rtl = true, side = RaftListPopupSide.Beside),
        )
        // Taller than the room under the anchor's top: slid up into the window.
        assertEquals(
            IntOffset(306, 792 - 700),
            raftMenuPosition(anchor, window, IntSize(240, 700), gap = 6, windowMargin = 8, insets = noShadow, side = RaftListPopupSide.Beside),
        )
    }

    @Test
    fun besideWithNoRoomAcrossFallsBackOverTheAnchor() {
        // A phone: the anchor takes the width, no side has 240px. As before: the side with more
        // room (below), moved into the window over the anchor.
        val noShadow = RaftPopupMargins(0, 0, 0)
        val at = raftMenuPosition(IntRect(0, 114, 400, 680), IntSize(400, 800), IntSize(240, 784), gap = 6, windowMargin = 8, insets = noShadow, side = RaftListPopupSide.Beside)
        assertEquals(IntOffset(8, 8), at)
    }

    @Test
    fun aPickedSideIsKeptWhenThePopupWasShrunkToIt() {
        val noShadow = RaftPopupMargins(0, 0, 0)
        // Below with 300px of room, shrunk to it: placed below, not pushed up over the trigger.
        val at = raftMenuPosition(IntRect(20, 446, 140, 486), IntSize(400, 800), IntSize(240, 300), gap = 6, windowMargin = 8, insets = noShadow, side = RaftListPopupSide.Below)
        assertEquals(IntOffset(20, 492), at)
        // Above, shrunk to the room above.
        val up = raftMenuPosition(IntRect(20, 630, 140, 636), IntSize(400, 800), IntSize(240, 616), gap = 6, windowMargin = 8, insets = noShadow, side = RaftListPopupSide.Above)
        assertEquals(IntOffset(20, 8), up)
    }
}
