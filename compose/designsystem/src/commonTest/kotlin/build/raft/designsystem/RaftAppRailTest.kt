package build.raft.designsystem

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftTooltipSide
import build.raft.designsystem.components.raftAppRailCompact
import build.raft.designsystem.components.raftAppRailTooltipSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun theTooltipOpensAwayFromTheRailsEdge() {
        assertEquals(RaftTooltipSide.Right, raftAppRailTooltipSide(LayoutDirection.Ltr))
        assertEquals(RaftTooltipSide.Left, raftAppRailTooltipSide(LayoutDirection.Rtl))
    }
}
