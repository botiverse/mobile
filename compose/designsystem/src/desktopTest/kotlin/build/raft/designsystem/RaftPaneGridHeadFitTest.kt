package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftPaneGridSizes
import build.raft.designsystem.components.RAFT_PANE_GRID_COLUMN_TEST_TAG
import build.raft.designsystem.components.RAFT_PANE_GRID_HEAD_CLOSE_SUFFIX
import build.raft.designsystem.components.RAFT_PANE_GRID_HEAD_EXPAND_SUFFIX
import build.raft.designsystem.components.RAFT_PANE_GRID_HEAD_MORE_SUFFIX
import build.raft.designsystem.components.RAFT_PANE_GRID_HEAD_TEST_TAG
import build.raft.designsystem.components.RAFT_PANE_GRID_HEAD_TITLE_FLOOR_DP
import build.raft.designsystem.components.RAFT_PANE_GRID_HEAD_TITLE_SUFFIX
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftPaneGrid
import build.raft.designsystem.components.RaftPaneGridCell
import build.raft.designsystem.components.RaftPaneGridCellScope
import build.raft.designsystem.components.RaftPaneGridColumn
import build.raft.designsystem.components.RaftPaneGridColumnBounds
import build.raft.designsystem.components.RaftPaneGridColumnHead
import build.raft.designsystem.components.RaftPaneGridExpand
import build.raft.designsystem.components.RaftPaneGridHeadAction
import build.raft.designsystem.components.RaftPaneGridHeadLabels
import build.raft.designsystem.components.RaftPaneGridMemorySizes
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #189 rule 6 on a real composition: a column's head always fits it. A column clipped under its minimum (rule 2)
 * lays its cells out at the minimum and cuts them at its edge; its head lays itself out at what shows instead, so its
 * own actions fold into ⋯ there, ⋯, Expand and Close stay in sight, and no button of it stands past its column (the
 * pane_grid_heads golden of run 43352669929 had the Artifact's New on the seam and every Expand and Close cut off).
 */
@OptIn(ExperimentalTestApi::class)
class RaftPaneGridHeadFitTest {
    private val thread = RaftPaneGridColumnBounds(id = "thread", defaultWidthDp = 400f, minWidthDp = 360f)
    private val cards = RaftPaneGridColumnBounds(id = "cards", defaultWidthDp = 360f, minWidthDp = 280f)
    private val artifact = RaftPaneGridColumnBounds(id = "artifact", defaultWidthDp = 480f, minWidthDp = 480f)
    private val profile = RaftPaneGridColumnBounds(id = "profile", defaultWidthDp = 400f, minWidthDp = 320f)
    private val columns = listOf(thread, cards, artifact, profile)
    private val cells = mapOf(thread to listOf("thread"), cards to listOf("tasks", "files"), artifact to listOf("artifact"), profile to listOf("profile", "details"))

    private var rowWidth by mutableStateOf(1800f)

    private fun ComposeUiTest.showRow() {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                val sizes = remember { RaftPaneGridMemorySizes() }
                val expand = remember { RaftPaneGridExpand() }
                CompositionLocalProvider(LocalRaftPaneGridSizes provides sizes) {
                    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(rowWidth.dp, 600.dp)) {
                        RaftPaneGrid(
                            columns = columns.map { bounds -> RaftPaneGridColumn(bounds, "Resize", cells.getValue(bounds).map { id -> RaftPaneGridCell(id) { Head(id) } }) },
                            expand = expand,
                        ) { Box(Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        waitForIdle()
    }

    /**
     * Every head's buttons (its own action, ⋯, Expand, Close) lie inside its column, as laid out (not as clipped), and
     * its title keeps at least its floor and its whole text; with [allControls], ⋯, Expand and Close are in the bar.
     */
    private fun ComposeUiTest.assertHeadsInsideTheirColumns(at: Float, allControls: Boolean = true) {
        columns.forEach { bounds ->
            val column = onNodeWithTag(RAFT_PANE_GRID_COLUMN_TEST_TAG + bounds.id, useUnmergedTree = true).fetchSemanticsNode()
            val left = column.positionInRoot.x
            val right = left + column.size.width
            val buttons = onAllNodes(hasAnyAncestor(hasTestTag(RAFT_PANE_GRID_COLUMN_TEST_TAG + bounds.id)) and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue(buttons.isNotEmpty(), "${bounds.id} at $at has its head's buttons")
            buttons.forEach { button ->
                val x = button.positionInRoot.x
                assertTrue(x >= left - 0.5f && x + button.size.width <= right + 0.5f, "${bounds.id} at $at: a head button at $x..${x + button.size.width} past its column $left..$right")
            }
            cells.getValue(bounds).forEach { id ->
                // The title never vanishes: at least its floor wide, its node the whole title (the screen reader's name).
                val title = onNodeWithTag(RAFT_PANE_GRID_HEAD_TEST_TAG + id + RAFT_PANE_GRID_HEAD_TITLE_SUFFIX, useUnmergedTree = true).fetchSemanticsNode()
                val floor = with(density) { RAFT_PANE_GRID_HEAD_TITLE_FLOOR_DP.dp.toPx() }
                assertTrue(title.size.width >= floor - 1f, "$id at $at: its title is ${title.size.width}px, under its floor $floor")
                onNode(hasText(id) and isHeading(), useUnmergedTree = true).assertExists()
                // ⋯ never folds; with room, Expand and Close stand beside it.
                val suffixes = if (allControls) listOf(RAFT_PANE_GRID_HEAD_MORE_SUFFIX, RAFT_PANE_GRID_HEAD_EXPAND_SUFFIX, RAFT_PANE_GRID_HEAD_CLOSE_SUFFIX) else listOf(RAFT_PANE_GRID_HEAD_MORE_SUFFIX)
                suffixes.forEach { suffix -> onNodeWithTag(RAFT_PANE_GRID_HEAD_TEST_TAG + id + suffix, useUnmergedTree = true).assertExists() }
            }
        }
    }

    @Test
    fun aHeadInAClippedColumnFitsWhatShows() =
        runComposeUiTest {
            showRow()
            // 1800: every column gives way above its minimum, the Artifact (480 minimum) clips.
            assertHeadsInsideTheirColumns(1800f)
            // 1160: every column clips in proportion (rule 2), down towards 160.
            rowWidth = 1160f
            waitForIdle()
            assertHeadsInsideTheirColumns(1160f)
            // 800: every column at 160 (rule 2's floor); by touch Expand, and with a fold chevron Close, go into ⋯
            // before the title would.
            rowWidth = 800f
            waitForIdle()
            assertHeadsInsideTheirColumns(800f, allControls = false)
        }
}

/** A head with an own action (New), a ⋯ command (Move Left), Expand and Close. */
@Composable
private fun RaftPaneGridCellScope.Head(id: String) {
    Column(Modifier.fillMaxSize()) {
        RaftPaneGridColumnHead(
            title = id,
            labels = RaftPaneGridHeadLabels(more = "More Actions", expand = "Expand", restore = "Restore", tabs = "Columns"),
            cellId = cellId,
            closeLabel = "Close",
            onClose = {},
            ownActions = listOf(RaftPaneGridHeadAction(id = "new", label = "New", onSelect = {}, glyph = RaftGlyph.Plus)),
            menuItems = listOf(RaftPaneGridHeadAction(id = "move-left", label = "Move Left", onSelect = {}, glyph = RaftGlyph.MoveLeft)),
        )
    }
}
