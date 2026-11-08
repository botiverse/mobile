package build.raft.designsystem

import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftPaneGridColumn
import build.raft.designsystem.components.RaftPaneGridColumnBounds
import build.raft.designsystem.components.RaftPaneGridHandedUp
import build.raft.designsystem.components.RaftPaneGridHost
import build.raft.designsystem.components.RaftPaneGridMemorySizes
import build.raft.designsystem.components.raftPaneGridDragMaxDp
import build.raft.designsystem.components.raftPaneGridFitWidths
import build.raft.designsystem.components.raftPaneGridMoveSeam
import build.raft.designsystem.components.raftPaneGridSharePerDp
import build.raft.designsystem.components.raftPaneGridStack
import build.raft.designsystem.components.raftPaneGridStackMinHeightDp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RaftPaneGrid's layout rules (web ChannelSideArea, panelBounds.ts) and its in-memory sizes. */
class RaftPaneGridTest {
    private fun assertNear(expected: Float, actual: Float, message: String? = null) =
        assertTrue(abs(expected - actual) < 0.001f, message ?: "expected $expected, was $actual")

    @Test
    fun columnsGiveWayRightmostFirstAndGrowUntilTheMainPaneIsAt360() {
        // Thread 400 + cards 360 beside a 360 main pane: 1120 fits all.
        assertEquals(listOf(400f, 360f), raftPaneGridFitWidths(1120f, listOf(400f to 360f, 360f to 280f)))
        // 1040: the rightmost gives way first (360 -> 280).
        assertEquals(listOf(400f, 280f), raftPaneGridFitWidths(1040f, listOf(400f to 360f, 360f to 280f)))
        // 1000: then the one before it.
        assertEquals(listOf(360f, 280f), raftPaneGridFitWidths(1000f, listOf(400f to 360f, 360f to 280f)))
        // Never under a minimum.
        assertEquals(listOf(280f), raftPaneGridFitWidths(500f, listOf(360f to 280f)))
        // Web growToMainMinMax: a 360 column beside a 600 main pane may grow to 600.
        assertEquals(600f, raftPaneGridDragMaxDp(280f, 360f, 600f))
        assertEquals(280f, raftPaneGridDragMaxDp(280f, 280f, 300f))
    }

    @Test
    fun aStackSplitsByShareAndFoldsToHeads() {
        // 600 tall, 60/40: the seam takes 1, Tasks 359.4, Files 239.6.
        val open = raftPaneGridStack(600f, listOf(0.6f, 0.4f), listOf(false, false))
        assertNear(359.4f, open.heights[0])
        assertNear(239.6f, open.heights[1])
        assertEquals(listOf(false, true), open.seamAbove)
        // Tasks folded: its head, no seam under it, Files the rest.
        val tasksFolded = raftPaneGridStack(600f, listOf(0.6f, 0.4f), listOf(true, false))
        assertEquals(listOf(56f, 544f), tasksFolded.heights)
        assertEquals(listOf(false, false), tasksFolded.seamAbove)
        // Files folded: its head at the bottom, under the seam.
        val filesFolded = raftPaneGridStack(600f, listOf(0.6f, 0.4f), listOf(false, true))
        assertEquals(listOf(543f, 56f), filesFolded.heights)
        // Both folded: two heads at the top.
        assertEquals(listOf(56f, 56f), raftPaneGridStack(600f, listOf(0.6f, 0.4f), listOf(true, true)).heights)
        // Web sideCardsStackMinHeight: two cards need 321.
        assertEquals(321f, raftPaneGridStackMinHeightDp(2))
    }

    /** Under a fine pointer a folded cell is the 44dp head (sizing v12, raft-ui 0.65 desktop density). */
    @Test
    fun aStackFoldsToThePointerHead() {
        val tasksFolded = raftPaneGridStack(600f, listOf(0.6f, 0.4f), listOf(true, false), headDp = 44f)
        assertEquals(listOf(44f, 556f), tasksFolded.heights)
        assertEquals(listOf(44f, 44f), raftPaneGridStack(600f, listOf(0.6f, 0.4f), listOf(true, true), headDp = 44f).heights)
    }

    @Test
    fun aSeamDragKeepsEachCardAt160() {
        val none = listOf(false, false)
        // 601 tall: 600 to split, 160dp is 0.2667 of it.
        val up = raftPaneGridMoveSeam(listOf(0.6f, 0.4f), 0, 0f, 601f, none)
        assertNear(160f / 600f, up[0])
        assertNear(1f, up[0] + up[1], "the pair keeps its total")
        val down = raftPaneGridMoveSeam(listOf(0.6f, 0.4f), 0, 1f, 601f, none)
        assertNear(1f - 160f / 600f, down[0])
        // A short stack: 45% of the pair at least.
        val short = raftPaneGridMoveSeam(listOf(0.6f, 0.4f), 0, 0f, 301f, none)
        assertNear(0.45f, short[0])
        // A pixel is worth 1/600 of the open shares.
        assertNear(1f / 600f, raftPaneGridSharePerDp(601f, listOf(0.6f, 0.4f), none))
    }

    @Test
    fun memorySizesKeepTheDefaultsUntilChangedWithinBounds() {
        val bounds = RaftPaneGridColumnBounds(id = "side", defaultWidthDp = 360f, minWidthDp = 280f)
        val sizes = RaftPaneGridMemorySizes()
        assertEquals(360f, sizes.widthDp(bounds))
        sizes.setWidth(bounds, 100f)
        assertEquals(280f, sizes.widthDp(bounds), "never under the minimum")
        assertEquals(listOf(0.5f, 0.5f), sizes.shares("a+b", listOf(0.5f, 0.5f)))
        sizes.setShares("a+b", listOf(0.7f, 0.3f))
        assertEquals(listOf(0.7f, 0.3f), sizes.shares("a+b", listOf(0.5f, 0.5f)))
        // Kept shares for another number of cells are not used.
        assertEquals(listOf(0.2f, 0.3f, 0.5f), sizes.shares("a+b", listOf(0.2f, 0.3f, 0.5f)))
        assertFalse(sizes.isCollapsed("a"))
        sizes.setCollapsed("a", true)
        assertTrue(sizes.isCollapsed("a"))
    }

    private fun column(bounds: RaftPaneGridColumnBounds) = RaftPaneGridColumn(bounds = bounds, resizeLabel = bounds.id, cells = emptyList())

    private val thread = RaftPaneGridColumnBounds(id = "thread", defaultWidthDp = 400f, minWidthDp = 360f)
    private val cards = RaftPaneGridColumnBounds(id = "sideCards", defaultWidthDp = 360f, minWidthDp = 280f)

    @Test
    fun nestedGridsHandTheirColumnsUpOuterLevelsFirst() {
        val host = RaftPaneGridHost()
        val outer = RaftPaneGridColumnBounds(id = "outer", defaultWidthDp = 300f, minWidthDp = 300f)
        // The conversation (two levels in) hands up Tasks / Files before the workspace hands up the thread.
        host.handUp("conversation", RaftPaneGridHandedUp(depth = 2, columns = listOf(column(cards)), topInset = 24.dp))
        host.handUp("workspace", RaftPaneGridHandedUp(depth = 1, columns = listOf(column(thread))))
        // Web order: conversation | thread | Tasks / Files; the outermost grid's own columns first.
        assertEquals(listOf("thread", "sideCards"), host.placed(emptyList(), 0.dp).map { it.column.bounds.id })
        assertEquals(listOf("outer", "thread", "sideCards"), host.placed(listOf(column(outer)), 0.dp).map { it.column.bounds.id })
        // Each keeps the top inset of the grid that declared it.
        assertEquals(listOf(0.dp, 24.dp), host.placed(emptyList(), 0.dp).map { it.topInset })
        // A grid with nothing left to hand up leaves the row.
        host.handUp("conversation", RaftPaneGridHandedUp(depth = 2, columns = emptyList()))
        assertEquals(listOf("thread"), host.placed(emptyList(), 0.dp).map { it.column.bounds.id })
        host.handUp("workspace", null)
        assertEquals(emptyList(), host.placed(emptyList(), 0.dp))
    }

    @Test
    fun theRightmostColumnGivesWayFirstAndTheConversationKeeps360() {
        val host = RaftPaneGridHost()
        host.handUp("workspace", RaftPaneGridHandedUp(depth = 1, columns = listOf(column(thread))))
        host.handUp("conversation", RaftPaneGridHandedUp(depth = 2, columns = listOf(column(cards))))
        val placed = host.placed(emptyList(), 0.dp).map { it.column.bounds.defaultWidthDp to it.column.bounds.minWidthDp }
        // Row right of a 300 list (and its seam) in a 1500 window: 1199 holds 360 + 400 + 360 and more.
        assertEquals(listOf(400f, 360f), raftPaneGridFitWidths(1500f - 301f, placed))
        // 1080: the cards (rightmost) give way first, to 320; the thread keeps 400.
        assertEquals(listOf(400f, 320f), raftPaneGridFitWidths(1080f, placed))
        // 1020: the cards are at 280, the thread gives way to 380; the conversation keeps 360.
        val fitted = raftPaneGridFitWidths(1020f, placed)
        assertEquals(listOf(380f, 280f), fitted)
        assertEquals(360f, 1020f - fitted.sum())
        // Before the outermost grid is measured nobody can tell.
        assertEquals(null, host.spareDp(2))
        // The cards (two levels in) may count on what is left with the conversation at 360 and the thread at its 360.
        host.roomDp = 1000f
        assertEquals(280f, host.spareDp(2))
        host.roomDp = 999f
        assertEquals(279f, host.spareDp(2))
        // The thread itself (one level out) does not count the cards.
        assertEquals(639f, host.spareDp(1))
    }

    @Test
    fun aFocusedColumnFillsTheRowFromAnyLevel() {
        val host = RaftPaneGridHost()
        assertEquals(setOf("own"), host.fillIds("own"))
        host.handUp("workspace", RaftPaneGridHandedUp(depth = 1, columns = listOf(column(thread)), fillColumn = "thread"))
        assertEquals(setOf("thread"), host.fillIds(null))
    }
}
