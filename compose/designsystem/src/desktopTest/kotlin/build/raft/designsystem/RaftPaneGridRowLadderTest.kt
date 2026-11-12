package build.raft.designsystem

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftPaneGridSizes
import build.raft.designsystem.components.RAFT_PANE_GRID_COLUMN_TEST_TAG
import build.raft.designsystem.components.RAFT_PANE_GRID_MAIN_TEST_TAG
import build.raft.designsystem.components.RAFT_PANE_GRID_TABS_TEST_TAG
import build.raft.designsystem.components.RaftPaneGrid
import build.raft.designsystem.components.RaftPaneGridCell
import build.raft.designsystem.components.RaftPaneGridColumn
import build.raft.designsystem.components.RaftPaneGridColumnBounds
import build.raft.designsystem.components.RaftPaneGridMemorySizes
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RaftPaneGrid's row on a real composition (#189 rules 2 and 4; web rowPlan + RowTabs): the
 * default four side columns at their saved widths with room; narrower, every column gives way and
 * then clips, its content kept at its minimum; narrower still, the rightmost becomes a tab in its
 * left neighbour's column, and picking the other tab shows that column in its place.
 */
@OptIn(ExperimentalTestApi::class)
class RaftPaneGridRowLadderTest {
    private val thread = RaftPaneGridColumnBounds(id = "thread", defaultWidthDp = 400f, minWidthDp = 360f)
    private val cards = RaftPaneGridColumnBounds(id = "cards", defaultWidthDp = 360f, minWidthDp = 280f)
    private val artifact = RaftPaneGridColumnBounds(id = "artifact", defaultWidthDp = 480f, minWidthDp = 480f)
    private val profile = RaftPaneGridColumnBounds(id = "profile", defaultWidthDp = 400f, minWidthDp = 320f)

    private var rowWidth by mutableStateOf(2000f)

    private fun ComposeUiTest.showRow() {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                val sizes = remember { RaftPaneGridMemorySizes() }
                CompositionLocalProvider(LocalRaftPaneGridSizes provides sizes) {
                    // Wider than the test window: laid out from its start, unclipped by the window.
                    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(rowWidth.dp, 600.dp)) {
                        RaftPaneGrid(
                            columns =
                                listOf(
                                    RaftPaneGridColumn(thread, "Resize thread", listOf(RaftPaneGridCell("thread") { Cell("thread") }), tabLabel = "Thread"),
                                    RaftPaneGridColumn(
                                        cards,
                                        "Resize cards",
                                        listOf(RaftPaneGridCell("tasks", 0.6f) { Cell("tasks") }, RaftPaneGridCell("files", 0.4f) { Cell("files") }),
                                        tabLabel = "Tasks · Files",
                                    ),
                                    RaftPaneGridColumn(artifact, "Resize artifact", listOf(RaftPaneGridCell("artifact") { Cell("artifact") }), tabLabel = "Artifact"),
                                    RaftPaneGridColumn(
                                        profile,
                                        "Resize profile",
                                        listOf(RaftPaneGridCell("profile") { Cell("profile") }, RaftPaneGridCell("details") { Cell("details") }),
                                        tabLabel = "Profile · Details",
                                    ),
                                ),
                            mainTabLabel = "Chat",
                        ) { Cell("main") }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.widthDp(tag: String): Float =
        with(density) { onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().size.width.toDp().value }

    private fun ComposeUiTest.assertWidth(expected: Float, tag: String) {
        val actual = widthDp(tag)
        assertTrue(abs(expected - actual) <= 1f, "$tag: expected $expected, was $actual")
    }

    private fun column(bounds: RaftPaneGridColumnBounds) = RAFT_PANE_GRID_COLUMN_TEST_TAG + bounds.id

    @Test
    fun withRoomEveryColumnShowsItsSavedWidthAndTheMainPaneTheRest() =
        runComposeUiTest {
            rowWidth = 2000f
            showRow()
            assertWidth(360f, RAFT_PANE_GRID_MAIN_TEST_TAG)
            assertWidth(400f, column(thread))
            assertWidth(360f, column(cards))
            assertWidth(480f, column(artifact))
            assertWidth(400f, column(profile))
        }

    @Test
    fun narrowerTheColumnsGiveWayThenClipWithTheirContentKeptAtItsMinimum() =
        runComposeUiTest {
            rowWidth = 1800f
            showRow()
            // 200 short: each gives what it has above its minimum; the Artifact has nothing to give.
            assertWidth(360f, column(thread))
            assertWidth(280f, column(cards))
            assertWidth(480f, column(artifact))
            assertWidth(320f, column(profile))
            rowWidth = 1600f
            waitForIdle()
            // Past the minimums all shrink in proportion and clip.
            assertWidth(320f, RAFT_PANE_GRID_MAIN_TEST_TAG)
            assertWidth(426.67f, column(artifact))
            // The clipped column's content keeps its 480 (less its 1dp seam), cut at the column's edge.
            assertWidth(479f, "cell-artifact")
            // So does the main pane's: 360.
            assertWidth(360f, "cell-main")
        }

    @Test
    fun pastEveryColumnAt160TheRightmostBecomesATabAndPickingItShowsItInstead() =
        runComposeUiTest {
            rowWidth = 700f
            showRow()
            // Four columns of at least 160 fit in 700, five don't: the profile is a tab in the Artifact's column.
            onNodeWithTag(RAFT_PANE_GRID_TABS_TEST_TAG + artifact.id, useUnmergedTree = true).assertExists()
            assertEquals(0, onAllTabs(profile).size, "the profile shows no strip of its own while it is the tab not shown")
            val group = widthDp(column(artifact))
            assertTrue(group >= 160f, "the group's column keeps 160: $group")
            onNodeWithText("Profile · Details").performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            onNodeWithTag(RAFT_PANE_GRID_TABS_TEST_TAG + profile.id, useUnmergedTree = true).assertExists()
            assertEquals(0, onAllTabs(artifact).size)
            assertWidth(group, column(profile))
            // Back to the Artifact.
            onNodeWithText("Artifact").performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            onNodeWithTag(RAFT_PANE_GRID_TABS_TEST_TAG + artifact.id, useUnmergedTree = true).assertExists()
        }

    private fun ComposeUiTest.onAllTabs(bounds: RaftPaneGridColumnBounds) =
        onAllNodes(hasTestTag(RAFT_PANE_GRID_TABS_TEST_TAG + bounds.id), useUnmergedTree = true).fetchSemanticsNodes()

    /** Each cell's first placement, in dp: its left edge in the row and its width. */
    private val firstPlaced = mutableMapOf<String, Pair<Float, Float>>()

    @Composable
    private fun Cell(name: String) {
        val density = LocalDensity.current.density
        Box(
            Modifier.fillMaxSize().testTag("cell-$name").onPlaced { coordinates ->
                if (name !in firstPlaced) firstPlaced[name] = coordinates.positionInRoot().x / density to coordinates.size.width / density
            }
        ) { RaftText(name) }
    }

    /** The row's units' widths, left to right as shown (a tab not shown is 0), and that they fill the row exactly. */
    private fun ComposeUiTest.assertRow(expected: List<Float>, shown: List<RaftPaneGridColumnBounds>) {
        val tags = listOf(RAFT_PANE_GRID_MAIN_TEST_TAG) + shown.map { column(it) }
        tags.zip(expected).forEach { (tag, width) -> assertWidth(width, tag) }
        val total = tags.sumOf { widthDp(it).toDouble() }.toFloat()
        assertTrue(abs(total - rowWidth) <= 1f, "the shown columns fill the row: $total of $rowWidth")
        tags.forEach { tag ->
            val bounds = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val right = with(density) { bounds.right.toDp().value }
            assertTrue(right <= rowWidth + 1f, "$tag ends inside the row: $right of $rowWidth")
        }
    }

    @Test
    fun theFirstFrameIsAlreadyPlannedNothingStartsAtItsSavedWidthOrPastTheRow() =
        runComposeUiTest {
            // The pane_grid_clipped screenshot's row, which renders one frame: every cell's first placement counts.
            rowWidth = 1160f
            firstPlaced.clear()
            showRow()
            // Planned: thread 232, so its cell is laid out at its 360 less the seam and clipped (unplanned: 399).
            val threadFirst = firstPlaced.getValue("thread").second
            assertTrue(abs(threadFirst - 359f) <= 1f, "the thread's first width: $threadFirst")
            // Unplanned, the columns stood at 400 + 360 + 480 and the profile started past the row's end (1240).
            listOf("thread", "tasks", "artifact", "profile", "details").forEach { cell ->
                val (x, _) = firstPlaced.getValue(cell)
                assertTrue(x < rowWidth, "$cell first placed inside the row, at $x")
            }
            assertTrue(firstPlaced.getValue("main").first < 1f, "the main pane first placed at the row's start")
        }

    @Test
    fun theScreenshotRowsArePlannedAsTheRulesSay() =
        runComposeUiTest {
            // 1160: 840 short of the saved widths and only 200 above the minimums: all five clip in proportion.
            rowWidth = 1160f
            showRow()
            assertRow(listOf(232f, 232f, 180.4f, 309.3f, 206.2f), listOf(thread, cards, artifact, profile))
            // 640: five columns of 160 don't fit, four do: the profile is a tab beside the Artifact, all at 160.
            rowWidth = 640f
            waitForIdle()
            assertRow(listOf(160f, 160f, 160f, 160f), listOf(thread, cards, artifact))
            onNodeWithTag(RAFT_PANE_GRID_TABS_TEST_TAG + artifact.id, useUnmergedTree = true).assertExists()
        }
}
