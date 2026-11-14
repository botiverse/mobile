package build.raft.designsystem

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftPaneGridArrangement
import build.raft.designsystem.components.RaftPaneGridArrangementColumn
import build.raft.designsystem.components.RaftPaneGridCell
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
        assertEquals(160f, sizes.widthDp(bounds), "never under 160 (#189 rule 2: the content keeps its 280 and clips)")
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
            assertTrue(written.any { it === host.freshState("workspace") }, "the latest is kept for the column's drawing")
            assertTrue(snapshot.enter { host.freshColumn("workspace", thread.id) } === again)
            // A column more changes the row.
            snapshot.enter { host.handUp("workspace", RaftPaneGridHandedUp(depth = 1, columns = listOf(again, column(cards)))) }
            assertTrue(written.any { it === host.handedUp }, "a new column is a change to the row")
        } finally {
            snapshot.dispose()
        }
    }

    @Test
    fun aHandUpWritesOnlyItsOwnGridsLatestAndNothingForTheSameObjects() {
        // Lagoon (#4661): the latest hand-ups were one map, observed whole, so the conversation's grid handing up (every
        // time it composes) redrew the thread's column too. Each grid has its own now, and the same objects again
        // write nothing.
        val host = RaftPaneGridHost()
        val threadColumn = column(thread)
        host.handUp("workspace", RaftPaneGridHandedUp(depth = 1, columns = listOf(threadColumn)))
        host.handUp("conversation", RaftPaneGridHandedUp(depth = 2, columns = listOf(column(cards))))
        val written = mutableListOf<Any>()
        val snapshot = Snapshot.takeMutableSnapshot(writeObserver = { written += it })
        try {
            val newCards = column(cards)
            snapshot.enter { host.handUp("conversation", RaftPaneGridHandedUp(depth = 2, columns = listOf(newCards))) }
            assertTrue(written.any { it === host.freshState("conversation") }, "the conversation's own latest is written")
            assertFalse(written.any { it === host.freshState("workspace") }, "the thread's column is left alone")
            assertTrue(snapshot.enter { host.freshColumn("conversation", cards.id) } === newCards)
            // The workspace hands up the very same column object again (it remembers it): nothing is written.
            written.clear()
            snapshot.enter { host.handUp("workspace", RaftPaneGridHandedUp(depth = 1, columns = listOf(threadColumn))) }
            assertTrue(written.isEmpty(), "the same objects again write nothing: $written")
            // Gone, its latest goes too.
            snapshot.enter { host.handUp("conversation", null) }
            assertEquals(null, snapshot.enter { host.freshColumn("conversation", cards.id) })
            assertEquals(null, host.freshState("conversation"))
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

    @Test
    fun theArrangementStacksCellsOfDifferentColumnsAndSplitsOne() {
        val stackedThread =
            RaftPaneGridArrangement(
                listOf(
                    RaftPaneGridArrangementColumn(listOf("thread", "tasks"), 400f),
                    RaftPaneGridArrangementColumn(listOf("files"), 360f),
                    RaftPaneGridArrangementColumn(listOf("artifact"), 480f),
                    RaftPaneGridArrangementColumn(listOf("profile", "context", "details"), 400f),
                )
            )
        assertEquals(
            listOf(
                RaftPaneGridSlot.Stacked("thread+tasks", listOf(0 to "thread", 1 to "tasks"), 400f),
                RaftPaneGridSlot.Stacked("files", listOf(1 to "files"), 360f),
            ),
            raftPaneGridArrange(listOf(listOf("thread"), listOf("tasks", "files")), stackedThread),
        )
        // A declared column whose cells the arrangement orders otherwise is drawn in that order; a cell it doesn't
        // name goes last with its column's first arranged one.
        val filesFirst = RaftPaneGridArrangement(listOf(RaftPaneGridArrangementColumn(listOf("files", "tasks"), 360f)))
        assertEquals(
            listOf(RaftPaneGridSlot.Stacked("files+tasks", listOf(0 to "files", 0 to "tasks", 0 to "extra"), 360f)),
            raftPaneGridArrange(listOf(listOf("tasks", "extra", "files")), filesFirst),
        )
    }

    private fun cellsColumn(bounds: RaftPaneGridColumnBounds, vararg cells: String, trailing: Boolean = false) =
        RaftPaneGridColumn(bounds = bounds, resizeLabel = bounds.id, cells = cells.map { RaftPaneGridCell(id = it) {} }, tabLabel = bounds.id, trailing = trailing)

    @Test
    fun theOutermostGridPlacesHandedUpColumnsByTheArrangement() {
        val host = RaftPaneGridHost()
        val profile = RaftPaneGridColumnBounds(id = "profile", defaultWidthDp = 400f, minWidthDp = 320f)
        val artifact = RaftPaneGridColumnBounds(id = "artifact", defaultWidthDp = 480f, minWidthDp = 480f)
        host.handUp("conversation", RaftPaneGridHandedUp(depth = 2, columns = listOf(cellsColumn(cards, "tasks", "files"))))
        host.handUp(
            "workspace",
            RaftPaneGridHandedUp(
                depth = 1,
                columns = listOf(cellsColumn(thread, "thread"), cellsColumn(profile, "profile", trailing = true), cellsColumn(artifact, "artifact")),
                fillColumn = "thread",
            ),
        )
        // The default layout: thread | Tasks / Files | Artifact | profile, the declared columns as they are.
        val arranged = host.arranged(host.placed(emptyList(), 0.dp), sideLayout)
        assertEquals(listOf("thread", "sideCards", "artifact", "profile"), arranged.map { it.column.bounds.id })
        assertEquals(listOf("conversation" to "sideCards"), arranged.filter { it.token == "conversation" }.map { it.token to it.column.bounds.id })
        // The thread stacked over Tasks: one column made for them, at least the widest minimum, named by both; Focus
        // on the thread still finds it.
        val stacked =
            RaftPaneGridArrangement(
                listOf(
                    RaftPaneGridArrangementColumn(listOf("thread", "tasks", "files"), 420f),
                    RaftPaneGridArrangementColumn(listOf("profile", "context", "details", "artifact"), 400f),
                )
            )
        val merged = host.arranged(host.placed(emptyList(), 0.dp), stacked)
        assertEquals(listOf("thread+tasks+files", "profile+context+details+artifact"), merged.map { it.column.bounds.id })
        assertEquals(listOf("thread", "tasks", "files"), merged[0].column.cells.map { it.id })
        assertEquals(RaftPaneGridColumnBounds("thread+tasks+files", 420f, 360f), merged[0].column.bounds)
        // Made no narrower by default than the Artifact's 480, though the layout says 400.
        assertEquals(RaftPaneGridColumnBounds("profile+context+details+artifact", 480f, 480f), merged[1].column.bounds)
        assertEquals(setOf("thread", "sideCards"), merged[0].sourceIds)
        assertEquals("thread · sideCards", merged[0].column.tabLabel)
        assertTrue("thread" in host.fillIds(null) && merged[0].sourceIds.any { it in host.fillIds(null) })
        assertFalse(merged[1].sourceIds.any { it in host.fillIds(null) })
    }
}
