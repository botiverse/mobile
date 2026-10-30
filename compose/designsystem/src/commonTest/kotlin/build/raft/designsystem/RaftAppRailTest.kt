package build.raft.designsystem

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftAppRailMetrics
import build.raft.designsystem.components.RaftTooltipSide
import build.raft.designsystem.components.raftAppRailCompact
import build.raft.designsystem.components.raftAppRailMetrics
import build.raft.designsystem.components.raftAppRailTooltipSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** raft-ui AppRail's compact size and web LeftRail's tooltip side, on the app. */
class RaftAppRailTest {
    @Test
    fun aWindowUpTo600TallIsCompact() {
        // raft-ui `[@media(max-height:600px)]`: inclusive.
        assertTrue(raftAppRailCompact(600.dp))
        assertTrue(raftAppRailCompact(430.dp))
        assertFalse(raftAppRailCompact(600.5.dp))
        assertFalse(raftAppRailCompact(800.dp))
    }

    @Test
    fun aWindowWithNoHeightYetIsNotCompact() {
        assertFalse(raftAppRailCompact(0.dp))
    }

    @Test
    fun compactShrinksTheColumnTheHeaderAndTheItemsButNotTheIconOrTheGap() {
        val regular = raftAppRailMetrics(compact = false)
        val compact = raftAppRailMetrics(compact = true)
        assertSame(RaftAppRailMetrics.Regular, regular)
        assertSame(RaftAppRailMetrics.Compact, compact)
        // raft-ui app-rail.recipe: w-[57px] / w-[49px] with the seam, h-14 / h-12, size-10 / size-9.
        assertEquals(56.dp, regular.width)
        assertEquals(48.dp, compact.width)
        assertEquals(56.dp, regular.headerHeight)
        assertEquals(48.dp, compact.headerHeight)
        assertEquals(40.dp, regular.itemSize)
        assertEquals(36.dp, compact.itemSize)
        assertEquals(20.dp, compact.iconSize)
        assertEquals(regular.iconSize, compact.iconSize)
        assertEquals(6.dp, compact.itemGap)
        assertEquals(regular.itemGap, compact.itemGap)
        // nav pt-2.5 (under the header) / first:pt-2 / compact pt-1.5; footer pb-2 / pb-1.5.
        assertEquals(10.dp, regular.navTopUnderHeader)
        assertEquals(8.dp, regular.navTop)
        assertEquals(6.dp, compact.navTopUnderHeader)
        assertEquals(6.dp, compact.navTop)
        assertEquals(8.dp, regular.footerBottom)
        assertEquals(6.dp, compact.footerBottom)
        assertEquals(8.dp, compact.navBottom)
    }

    @Test
    fun theTooltipOpensAwayFromTheRailsEdge() {
        assertEquals(RaftTooltipSide.Right, raftAppRailTooltipSide(LayoutDirection.Ltr))
        assertEquals(RaftTooltipSide.Left, raftAppRailTooltipSide(LayoutDirection.Rtl))
    }
}
