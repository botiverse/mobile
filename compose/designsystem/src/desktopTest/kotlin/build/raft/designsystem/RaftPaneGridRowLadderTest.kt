package build.raft.designsystem

import build.raft.designsystem.theme.RaftControlMetrics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import build.raft.designsystem.components.LocalRaftPaneGridHeadTabs
import build.raft.designsystem.components.LocalRaftPaneGridSizes
import build.raft.designsystem.components.RAFT_PANE_GRID_COLUMN_TEST_TAG
import build.raft.designsystem.components.RAFT_PANE_GRID_TABS_TEST_TAG
import build.raft.designsystem.components.RaftPaneGrid
import build.raft.designsystem.components.RaftPaneGridCell
import build.raft.designsystem.components.RaftPaneGridColumn
import build.raft.designsystem.components.RaftPaneGridColumnBounds
import build.raft.designsystem.components.RaftPaneGridHeadRowTabs
import build.raft.designsystem.components.RaftPaneGridMemorySizes
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    private var pointer by mutableStateOf(RaftPointerInput.Touch)

    private fun ComposeUiTest.showRow() {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false, pointerInput = pointer) {
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

    @Test
    fun standaloneRowTabStripMatchesShellHeadHeightByTouchAndFinePointer() =
        runComposeUiTest {
            rowWidth = 700f
            pointer = RaftPointerInput.Touch
            showRow()
            val tabTag = RAFT_PANE_GRID_TABS_TEST_TAG + artifact.id
            val touchHeight = with(density) { onNodeWithTag(tabTag, useUnmergedTree = true).fetchSemanticsNode().size.height.toDp().value }
            assertTrue(abs(RaftControlMetrics.MobileShortHead.headHeight.value - touchHeight) <= 1f, "touch tab strip: the head's height, was $touchHeight")

            pointer = RaftPointerInput.Fine
            waitForIdle()
            val fineHeight = with(density) { onNodeWithTag(tabTag, useUnmergedTree = true).fetchSemanticsNode().size.height.toDp().value }
            assertTrue(abs(RaftControlMetrics.Desktop.headHeight.value - fineHeight) <= 1f, "fine-pointer tab strip: the head's height, was $fineHeight")
        }

    @Test
    fun mainHeadCarriesTabsHandsUpWithoutExtraStripAndOnlyTopCellReceivesHeadTabs() =
        runComposeUiTest {
            var inputMode by mutableStateOf(RaftPointerInput.Touch)
            var topCellTabsPresent: Boolean? = null
            var bottomCellTabsPresent: Boolean? = null
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false, pointerInput = inputMode) {
                    val sizes = remember { RaftPaneGridMemorySizes() }
                    CompositionLocalProvider(LocalRaftPaneGridSizes provides sizes) {
                        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(280.dp, 600.dp)) {
                            RaftPaneGrid(
                                columns =
                                    listOf(
                                        RaftPaneGridColumn(
                                            cards,
                                            "Resize cards",
                                            listOf(
                                                RaftPaneGridCell("tasks", 0.6f) {
                                                    val headTabs = LocalRaftPaneGridHeadTabs.current
                                                    topCellTabsPresent = headTabs != null
                                                    Column(Modifier.fillMaxSize()) {
                                                        Box(Modifier.fillMaxWidth().height(RaftTheme.controls.headHeight).testTag("cards-head")) {
                                                            if (headTabs != null) RaftPaneGridHeadRowTabs(headTabs, "Row tabs")
                                                        }
                                                    }
                                                },
                                                RaftPaneGridCell("files", 0.4f) {
                                                    bottomCellTabsPresent = LocalRaftPaneGridHeadTabs.current != null
                                                    Cell("files")
                                                },
                                            ),
                                            tabLabel = "Tasks · Files",
                                            headCarriesTabs = true,
                                        )
                                    ),
                                mainTabLabel = "Chat",
                            ) {
                                // Nested grid with empty own columns hands up mainHeadCarriesTabs = true to the outermost grid.
                                RaftPaneGrid(columns = emptyList(), mainHeadCarriesTabs = true) {
                                    val headTabs = LocalRaftPaneGridHeadTabs.current
                                    Column(Modifier.fillMaxSize()) {
                                        Box(Modifier.fillMaxWidth().height(RaftTheme.controls.headHeight).testTag("main-head")) {
                                            if (headTabs != null) RaftPaneGridHeadRowTabs(headTabs, "Row tabs")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            // Main header sits at y = 0 (no standalone strip pushed it down) and ends at the head's height (touch, wide).
            val mainBoundsTouch = onNodeWithTag("main-head", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(abs(0f - with(density) { mainBoundsTouch.top.toDp().value }) <= 1f, "main head top should be 0dp")
            assertTrue(abs(RaftControlMetrics.MobileShortHead.headHeight.value - with(density) { mainBoundsTouch.bottom.toDp().value }) <= 1f, "main head bottom: the head's height")

            // Switch to Tasks · Files tab: only top cell receives head tabs, bottom cell sees null.
            onNodeWithText("Tasks · Files").performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertEquals(true, topCellTabsPresent, "top stacked cell should receive LocalRaftPaneGridHeadTabs")
            assertEquals(false, bottomCellTabsPresent, "bottom stacked cell must not receive LocalRaftPaneGridHeadTabs")
            val cardsBoundsTouch = onNodeWithTag("cards-head", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(abs(0f - with(density) { cardsBoundsTouch.top.toDp().value }) <= 1f, "cards head top should be 0dp")
            assertTrue(abs(RaftControlMetrics.MobileShortHead.headHeight.value - with(density) { cardsBoundsTouch.bottom.toDp().value }) <= 1f, "cards head bottom: the head's height")

            // Fine pointer tightens both to 44dp.
            inputMode = RaftPointerInput.Fine
            waitForIdle()
            val cardsBoundsFine = onNodeWithTag("cards-head", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(abs(RaftControlMetrics.Desktop.headHeight.value - with(density) { cardsBoundsFine.bottom.toDp().value }) <= 1f, "fine cards head bottom: the head's height")
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
}
