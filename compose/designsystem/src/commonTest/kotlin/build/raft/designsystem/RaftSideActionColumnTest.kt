package build.raft.designsystem

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import build.raft.designsystem.components.RaftSideColumnMetrics
import build.raft.designsystem.components.RaftSideColumnPlan
import build.raft.designsystem.components.RaftTooltipSide
import build.raft.designsystem.components.raftSideColumnNaturalHeight
import build.raft.designsystem.components.raftSideColumnPlan
import build.raft.designsystem.components.raftTooltipSidePosition
import kotlin.test.Test
import kotlin.test.assertEquals

/** The side action column's fit and overflow (RaftSideActionColumn), in dp at density 1. */
class RaftSideActionColumnTest {
    // RaftSideActionColumnDefaults: 44 buttons, 4 apart, 8 from the ends, 16 after the primaries.
    private val metrics = RaftSideColumnMetrics(item = 44, gap = 4, inset = 8, sectionGap = 16)

    @Test
    fun aTallColumnShowsEveryActionStackedAgainstTheBottom() {
        val plan = raftSideColumnPlan(height = 400, metrics = metrics, primaryCount = 0, ranks = listOf(0, 0, 0))
        // Bottom slot 400 - 8 - 44 = 348, then 48 apart upwards; listed order reads top to bottom.
        assertEquals(RaftSideColumnPlan(emptyList(), listOf(0, 1, 2), listOf(252, 300, 348), emptyList(), null), plan)
    }

    @Test
    fun aShortColumnKeepsTheBottomSlotsAndPutsTheRestBehindTheOverflow() {
        // Three slots fit (8 + 3 * 44 + 2 * 4 + 8 = 156): two actions and the overflow above them.
        val plan = raftSideColumnPlan(height = 160, metrics = metrics, primaryCount = 0, ranks = listOf(0, 0, 0, 0, 0))
        assertEquals(listOf(0, 1), plan.shown, "ties keep the actions listed first")
        assertEquals(listOf(2, 3, 4), plan.overflowed)
        assertEquals(listOf(60, 108), plan.shownTops)
        assertEquals(12, plan.overflowTop)
    }

    @Test
    fun priorityAndKeepVisibleDecideWhatStaysButNotTheOrder() {
        val plan = raftSideColumnPlan(height = 160, metrics = metrics, primaryCount = 0, ranks = listOf(0, 5, 0, Int.MAX_VALUE))
        assertEquals(listOf(1, 3), plan.shown, "shown in listed order")
        assertEquals(listOf(0, 2), plan.overflowed)
    }

    @Test
    fun primariesArePinnedAtTheTopAndNeverOverflow() {
        // 100 tall: the primary takes 8..52, actions may start at 68, and none fits below 92.
        val plan = raftSideColumnPlan(height = 100, metrics = metrics, primaryCount = 1, ranks = listOf(0))
        assertEquals(listOf(8), plan.primaryTops)
        assertEquals(emptyList(), plan.shown)
        assertEquals(listOf(0), plan.overflowed)
        assertEquals(48, plan.overflowTop, "no slot left: the overflow takes the lowest one anyway")
        val tiny = raftSideColumnPlan(height = 20, metrics = metrics, primaryCount = 2, ranks = emptyList())
        assertEquals(listOf(8, 56), tiny.primaryTops)
    }

    @Test
    fun backAllowsAtMostTwoActionsWhateverTheHeight() {
        val plan = raftSideColumnPlan(height = 800, metrics = metrics, primaryCount = 1, ranks = listOf(0, 0, 0), maxActions = 2)
        assertEquals(listOf(0, 1), plan.shown)
        assertEquals(listOf(2), plan.overflowed)
        assertEquals(listOf(700, 748), plan.shownTops)
        assertEquals(652, plan.overflowTop)
        val two = raftSideColumnPlan(height = 800, metrics = metrics, primaryCount = 1, ranks = listOf(0, 0), maxActions = 2)
        assertEquals(null, two.overflowTop)
    }

    @Test
    fun reservedBandsAndKeepOutsMoveTheButtons() {
        val reserved = metrics.copy(topReserved = 50, bottomReserved = 83, keepOut = listOf(60 to 100, 200 to 260))
        // No primary: from 500 - 83 - 8 = 409 up, 365, 317, 269, then 213 would cross 200..260,
        // so 260 - 44 = 156 (still under the top band's 50 + 8).
        val open = raftSideColumnPlan(height = 500, metrics = reserved, primaryCount = 0, ranks = listOf(0, 0, 0, 0))
        assertEquals(listOf(156, 269, 317, 365), open.shownTops)
        assertEquals(null, open.overflowTop)
        // With Back: from 58 it crosses 60..100, so it drops to 100; the actions now stop at
        // 100 + 44 + 16 = 160, so 156 is gone and the overflow takes 269.
        val withBack = raftSideColumnPlan(height = 500, metrics = reserved, primaryCount = 1, ranks = listOf(0, 0, 0, 1))
        assertEquals(listOf(100), withBack.primaryTops)
        assertEquals(listOf(0, 3), withBack.shown)
        assertEquals(listOf(317, 365), withBack.shownTops)
        assertEquals(listOf(1, 2), withBack.overflowed)
        assertEquals(269, withBack.overflowTop)
    }

    @Test
    fun naturalHeightFitsTheButtons() {
        assertEquals(8 + 44 + 16 + 44 + 4 + 44 + 8, raftSideColumnNaturalHeight(metrics, primaryCount = 1, actionCount = 2, maxActions = 2))
        // Over the cap: two actions and the overflow.
        assertEquals(8 + 44 + 16 + 3 * 44 + 2 * 4 + 8, raftSideColumnNaturalHeight(metrics, primaryCount = 1, actionCount = 5, maxActions = 2))
        assertEquals(16, raftSideColumnNaturalHeight(metrics, primaryCount = 0, actionCount = 0, maxActions = Int.MAX_VALUE))
    }

    @Test
    fun aSideTooltipOpensTowardTheContentAndFlipsWithoutRoom() {
        val window = IntSize(400, 800)
        val popup = IntSize(100, 30)
        val anchor = IntRect(350, 100, 394, 144)
        assertEquals(IntOffset(242, 107) to RaftTooltipSide.Left, raftTooltipSidePosition(anchor, window, popup, 8, RaftTooltipSide.Left))
        val nearLeft = IntRect(6, 0, 50, 44)
        assertEquals(IntOffset(58, 7) to RaftTooltipSide.Right, raftTooltipSidePosition(nearLeft, window, popup, 8, RaftTooltipSide.Left))
        val atBottom = IntRect(350, 780, 394, 824)
        assertEquals(770, raftTooltipSidePosition(atBottom, window, popup, 8, RaftTooltipSide.Left).first.y, "kept inside the window")
    }
}
