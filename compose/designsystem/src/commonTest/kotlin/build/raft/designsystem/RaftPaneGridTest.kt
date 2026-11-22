package build.raft.designsystem

import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftPaneGridArrangement
import build.raft.designsystem.components.RaftPaneGridArrangementColumn
import build.raft.designsystem.components.RaftPaneGridColumn
import build.raft.designsystem.components.RaftPaneGridSlot
import build.raft.designsystem.components.raftPaneGridArrange
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

    private fun column(bounds: RaftPaneGridColumnBounds) = RaftPaneGridColumn(bounds = bounds, resizeLabel = bounds.id, cells = emptyList())

    private val thread = RaftPaneGridColumnBounds(id = "thread", defaultWidthDp = 400f, minWidthDp = 360f)
    private val cards = RaftPaneGridColumnBounds(id = "sideCards", defaultWidthDp = 360f, minWidthDp = 280f)

    // #189 step 3: the app's profile / details column, declared with the thread one level in, stands after Tasks / Files.
    @Test
    fun aTrailingColumnStandsAfterEveryOtherWhereverItIsDeclared() {
        val host = RaftPaneGridHost()
        val profile = RaftPaneGridColumnBounds(id = "profile", defaultWidthDp = 400f, minWidthDp = 320f)
        val trailing = RaftPaneGridColumn(bounds = profile, resizeLabel = "profile", cells = emptyList(), trailing = true)
        host.handUp("conversation", RaftPaneGridHandedUp(depth = 2, columns = listOf(column(cards))))
        host.handUp("workspace", RaftPaneGridHandedUp(depth = 1, columns = listOf(column(thread), trailing)))
        assertEquals(listOf("thread", "sideCards", "profile"), host.placed(emptyList(), 0.dp).map { it.column.bounds.id })
        // An outermost grid's own trailing column too.
        val own = RaftPaneGridHost()
        own.handUp("conversation", RaftPaneGridHandedUp(depth = 1, columns = listOf(column(cards))))
        assertEquals(listOf("thread", "sideCards", "profile"), own.placed(listOf(column(thread), trailing), 0.dp).map { it.column.bounds.id })
        // Whether it trails is part of what the row is laid out by: a change re-lays the row.
        val plain = RaftPaneGridColumn(bounds = profile, resizeLabel = "profile", cells = emptyList())
        assertTrue(RaftPaneGridHandedUp(depth = 1, columns = listOf(plain)).shape != RaftPaneGridHandedUp(depth = 1, columns = listOf(trailing)).shape)
    }

    private fun row(roomDp: Float, own: List<RaftPaneGridColumn> = emptyList()) =
        RaftPaneGridRow(
            roomDp = roomDp,
            ownColumns = own.map { it.planColumn(RaftPaneGridMemorySizes()) },
            ownWidthsDp = 0f,
            mainBounds = RaftPaneGridConversationBounds,
        )

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

    // #189 step 4 (web channelSideLayout): the outermost grid stands and stacks the cells by an arrangement.
    private val sideLayout =
        RaftPaneGridArrangement(
            listOf(
                RaftPaneGridArrangementColumn(listOf("thread"), 400f),
                RaftPaneGridArrangementColumn(listOf("tasks", "files"), 360f),
                RaftPaneGridArrangementColumn(listOf("artifact"), 480f),
                RaftPaneGridArrangementColumn(listOf("profile", "context", "details"), 400f),
            )
        )

    @Test
    fun theArrangementStandsDeclaredColumnsInItsOrder() {
        // Declared: the Tasks page's task (no section), the thread, Tasks / Files, the profile column, the Artifact.
        val declared = listOf(listOf("task"), listOf("thread"), listOf("tasks", "files"), listOf("profile", "details"), listOf("artifact"))
        assertEquals(
            listOf(0, 1, 2, 4, 3).map { RaftPaneGridSlot.Declared(it) },
            raftPaneGridArrange(declared, sideLayout),
            "a column with no arranged cell first, then the arrangement's order; each declared column kept as it is",
        )
        // Only some of a column's cells open (Files alone, the details alone): still the declared column.
        assertEquals(
            listOf(RaftPaneGridSlot.Declared(1), RaftPaneGridSlot.Declared(0)),
            raftPaneGridArrange(listOf(listOf("details"), listOf("files")), sideLayout),
        )
        // No arrangement: as declared.
        assertEquals(declared.indices.map { RaftPaneGridSlot.Declared(it) }, raftPaneGridArrange(declared, null))
    }
}
