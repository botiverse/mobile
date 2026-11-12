package build.raft.designsystem

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftPaneGridColumn
import build.raft.designsystem.components.RaftPaneGridColumnBounds
import build.raft.designsystem.components.RaftPaneGridHandedUp
import build.raft.designsystem.components.RaftPaneGridHost
import build.raft.designsystem.components.RaftPaneGridMemorySizes
import build.raft.designsystem.components.RaftPaneGridRow
import build.raft.designsystem.components.RaftPaneGridConversationBounds
import build.raft.designsystem.components.raftPaneGridDragMaxDp
import build.raft.designsystem.components.planColumn
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
    fun aColumnGrowsUntilTheMainPaneIsAt360() {
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

    private fun row(roomDp: Float, own: List<RaftPaneGridColumn> = emptyList()) =
        RaftPaneGridRow(
            roomDp = roomDp,
            ownColumns = own.map { it.planColumn(RaftPaneGridMemorySizes()) },
            ownWidthsDp = 0f,
            mainBounds = RaftPaneGridConversationBounds,
        )

    @Test
    fun anEqualRowIsTheSameRowSoItsReadersAreNotInvalidated() {
        // The grid makes a new row each time it recomposes (a nested grid handing its columns up again); pages read it
        // through a composition local, which only invalidates them where the value differs.
        val outer = RaftPaneGridColumnBounds(id = "outer", defaultWidthDp = 300f, minWidthDp = 300f)
        assertEquals(row(1000f, listOf(column(outer))), row(1000f, listOf(column(outer))))
        assertFalse(row(1000f) == row(999f))
    }

    @Test
    fun theThreadFoldsWhereTheConversationAndItCantKeep360Each() {
        // #189 rule 3 (web decideFold): alone beside the conversation, the thread folds under 720.
        val host = RaftPaneGridHost()
        val sizes = RaftPaneGridMemorySizes()
        assertFalse(host.folds(row(720f), depth = 1, bounds = thread, sizes = sizes))
        assertTrue(host.folds(row(719f), depth = 1, bounds = thread, sizes = sizes))
        // Kept widths only share the room out: dragged wide, the pair still stands beside down to 720.
        sizes.setWidth(thread, 900f)
        sizes.setWidth(RaftPaneGridConversationBounds, 700f)
        assertFalse(host.folds(row(720f), depth = 1, bounds = thread, sizes = sizes))
        assertTrue(host.folds(row(719f), depth = 1, bounds = thread, sizes = sizes))
    }

    @Test
    fun theThreadFoldsBeforeAnyColumnHandedUpBesideItWouldClip() {
        val host = RaftPaneGridHost()
        val sizes = RaftPaneGridMemorySizes()
        // The conversation (two levels in) hands up Tasks / Files: folding comes before any clipping, so the thread
        // folds under 360 + 360 + 280.
        host.handUp("conversation", RaftPaneGridHandedUp(depth = 2, columns = listOf(column(cards))))
        assertFalse(host.folds(row(1000f), depth = 1, bounds = thread, sizes = sizes))
        assertTrue(host.folds(row(999f), depth = 1, bounds = thread, sizes = sizes))
        // The thread's own column, once handed up, counts once.
        host.handUp("workspace", RaftPaneGridHandedUp(depth = 1, columns = listOf(column(thread))))
        assertFalse(host.folds(row(1000f), depth = 1, bounds = thread, sizes = sizes))
        assertTrue(host.folds(row(999f), depth = 1, bounds = thread, sizes = sizes))
        // The outermost grid's own columns count too.
        val outer = RaftPaneGridColumnBounds(id = "outer", defaultWidthDp = 300f, minWidthDp = 300f)
        assertTrue(host.folds(row(1299f, listOf(column(outer))), depth = 1, bounds = thread, sizes = sizes))
        assertFalse(host.folds(row(1300f, listOf(column(outer))), depth = 1, bounds = thread, sizes = sizes))
    }

    @Test
    fun handingUpTheSameColumnsAgainWritesNothingTheRowReads() {
        // A nested grid hands up every time its page composes, with new objects. Writing the row each time made the
        // outermost grid recompose, which recomposed the page, which handed up again: a loop every frame.
        val host = RaftPaneGridHost()
        host.handUp("workspace", RaftPaneGridHandedUp(depth = 1, columns = listOf(column(thread))))
        val written = mutableListOf<Any>()
        val again = column(thread)
        val snapshot = Snapshot.takeMutableSnapshot(writeObserver = { written += it })
        try {
            snapshot.enter { host.handUp("workspace", RaftPaneGridHandedUp(depth = 1, columns = listOf(again))) }
            assertFalse(written.any { it === host.handedUp }, "the same shape again leaves the row alone")
            // Where the column is drawn it is the latest (its cells' content may have changed with the same shape).
            assertTrue(written.any { it === host.fresh }, "the latest is kept for the column's drawing")
            assertTrue(snapshot.enter { host.freshColumn("workspace", thread.id) } === again)
            // A column more changes the row.
            snapshot.enter { host.handUp("workspace", RaftPaneGridHandedUp(depth = 1, columns = listOf(again, column(cards)))) }
            assertTrue(written.any { it === host.handedUp }, "a new column is a change to the row")
        } finally {
            snapshot.dispose()
        }
    }

    @Test
    fun theMainPanesWidthIsKeptWhereTheInnermostPageNamesIt() {
        val host = RaftPaneGridHost()
        // Nobody names it: the conversation's (web slock:conversationWidth).
        assertEquals(RaftPaneGridConversationBounds, host.mainBounds(null))
        val list = RaftPaneGridColumnBounds(id = "tasksList", defaultWidthDp = 400f, minWidthDp = 360f)
        val outer = RaftPaneGridColumnBounds(id = "outerMain", defaultWidthDp = 400f, minWidthDp = 360f)
        assertEquals(outer, host.mainBounds(outer))
        // A page in the main pane naming its own (the Tasks page's list) wins, even before its column opens.
        host.handUp("tasks", RaftPaneGridHandedUp(depth = 1, columns = emptyList(), mainBounds = list))
        assertEquals(list, host.mainBounds(outer))
        host.handUp("tasks", null)
        assertEquals(outer, host.mainBounds(outer))
    }

    @Test
    fun aFocusedColumnFillsTheRowFromAnyLevel() {
        val host = RaftPaneGridHost()
        assertEquals(setOf("own"), host.fillIds("own"))
        host.handUp("workspace", RaftPaneGridHandedUp(depth = 1, columns = listOf(column(thread)), fillColumn = "thread"))
        assertEquals(setOf("thread"), host.fillIds(null))
    }
}
