package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftPaneGridSizes
import build.raft.designsystem.components.LocalRaftSafeAreaInsets
import build.raft.designsystem.components.RAFT_PANE_GRID_COLUMN_TEST_TAG
import build.raft.designsystem.components.RAFT_PANE_GRID_MAIN_TEST_TAG
import build.raft.designsystem.components.RaftPaneGrid
import build.raft.designsystem.components.RaftPaneGridCell
import build.raft.designsystem.components.RaftPaneGridColumn
import build.raft.designsystem.components.RaftPaneGridColumnBounds
import build.raft.designsystem.components.RaftPaneGridMemorySizes
import build.raft.designsystem.components.RaftSafeAreaInsets
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phone landscape (Lagoon, #proj-mobile:dcf007d7): the horizontal safe area is padded only against the screen's
 * edges. Beside a seam inside the row the main pane and the columns are away from the edge, so content that pads the
 * safe area (a list row, a toolbar) pads nothing there; the outermost sides still pad it.
 */
@OptIn(ExperimentalTestApi::class)
class RaftPaneGridSafeAreaTest {
    private val thread = RaftPaneGridColumnBounds(id = "thread", defaultWidthDp = 400f, minWidthDp = 360f)
    private val cards = RaftPaneGridColumnBounds(id = "cards", defaultWidthDp = 360f, minWidthDp = 280f)
    private val inset = 40f

    @Composable
    private fun Padded(name: String) {
        Box(Modifier.fillMaxSize().testTag("cell-$name")) {
            Box(Modifier.raftSafeAreaHorizontalPadding().fillMaxSize().testTag("content-$name"))
        }
    }

    private fun column(bounds: RaftPaneGridColumnBounds) =
        RaftPaneGridColumn(bounds, "Resize ${bounds.id}", listOf(RaftPaneGridCell(bounds.id) { Padded(bounds.id) }), tabLabel = bounds.id)

    private fun ComposeUiTest.bounds(tag: String): DpRect = onAllNodesWithTag(tag, useUnmergedTree = true)[0].getBoundsInRoot()

    private fun near(expected: Float, actual: Float, what: String) = assertTrue(abs(expected - actual) <= 0.5f, "$what: expected $expected, was $actual")

    private fun ComposeUiTest.grid(columns: List<RaftPaneGridColumn>) {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                CompositionLocalProvider(
                    LocalRaftPaneGridSizes provides RaftPaneGridMemorySizes(),
                    LocalRaftSafeAreaInsets provides RaftSafeAreaInsets(left = inset.dp, right = inset.dp),
                ) {
                    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(1400.dp, 600.dp)) {
                        RaftPaneGrid(columns) { Padded("main") }
                    }
                }
            }
        }
        waitForIdle()
    }

    /** Padding of [name]'s content inside its cell: start, end. */
    private fun ComposeUiTest.padding(name: String): Pair<Float, Float> {
        val cell = bounds("cell-$name")
        val content = bounds("content-$name")
        return (content.left - cell.left).value to (cell.right - content.right).value
    }

    @Test
    fun seamsInsideTheRowPadNoSafeArea() =
        runComposeUiTest {
            grid(listOf(column(thread), column(cards)))
            assertTrue(bounds(RAFT_PANE_GRID_COLUMN_TEST_TAG + cards.id).right.value > 0f, "the columns are shown")
            val (mainStart, mainEnd) = padding("main")
            near(inset, mainStart, "main pane start (screen edge)")
            near(0f, mainEnd, "main pane end (seam)")
            val (threadStart, threadEnd) = padding(thread.id)
            near(0f, threadStart, "middle column start (seam)")
            near(0f, threadEnd, "middle column end (seam)")
            val (cardsStart, cardsEnd) = padding(cards.id)
            near(0f, cardsStart, "last column start (seam)")
            near(inset, cardsEnd, "last column end (screen edge)")
        }

    @Test
    fun theMainPaneAlonePadsBothEdges() =
        runComposeUiTest {
            grid(emptyList())
            val (start, end) = padding("main")
            near(inset, start, "start")
            near(inset, end, "end")
            near(1400f, bounds(RAFT_PANE_GRID_MAIN_TEST_TAG).let { it.right - it.left }.value, "main fills the row")
        }
}
