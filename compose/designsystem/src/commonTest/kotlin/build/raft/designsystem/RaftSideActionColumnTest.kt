package build.raft.designsystem

import build.raft.designsystem.components.RaftSideColumnMetrics
import build.raft.designsystem.components.raftSideColumnPlan
import kotlin.test.Test
import kotlin.test.assertEquals

/** The side action column's fit and overflow (RaftSideActionColumn), in dp at density 1. */
class RaftSideActionColumnTest {
    // RaftSideActionColumnDefaults: 44 buttons, 4 apart, 8 from the ends, 16 after the primaries.
    private val metrics = RaftSideColumnMetrics(item = 44, gap = 4, inset = 8, sectionGap = 16)

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
        // 100 tall: the primary takes 8..52, actions may start at 68, and none ends above 92.
        val plan = raftSideColumnPlan(height = 100, metrics = metrics, primaryCount = 1, ranks = listOf(0))
        assertEquals(listOf(8), plan.primaryTops)
        assertEquals(emptyList(), plan.shown)
        assertEquals(listOf(0), plan.overflowed)
        assertEquals(68, plan.overflowTop, "no slot left: the overflow takes the next one anyway")
        val tiny = raftSideColumnPlan(height = 20, metrics = metrics, primaryCount = 2, ranks = emptyList())
        assertEquals(listOf(8, 56), tiny.primaryTops)
    }

    @Test
    fun backAllowsAtMostTwoActionsWhateverTheHeight() {
        val plan = raftSideColumnPlan(height = 800, metrics = metrics, primaryCount = 1, ranks = listOf(0, 0, 0), maxActions = 2)
        assertEquals(listOf(0, 1), plan.shown)
        assertEquals(listOf(2), plan.overflowed)
        assertEquals(listOf(68, 116), plan.shownTops)
        assertEquals(164, plan.overflowTop, "under the actions")
        val two = raftSideColumnPlan(height = 800, metrics = metrics, primaryCount = 1, ranks = listOf(0, 0), maxActions = 2)
        assertEquals(null, two.overflowTop)
    }
}
