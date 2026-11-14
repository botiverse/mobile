package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftPaneGridSizes
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
 * RaftPaneGrid's splitters as people use them (web DS Resizable, ColumnResizeHandle, StackSeam):
 * the keyboard (arrows step 16, Shift 64; Home / End to the ends), a double click back to the
 * default, and screen readers (an adjustable value over the column's range that they can set).
 * A 1000dp planned row: a seam takes the side column, or the main pane, down to 160 (#189 rule 2, their content
 * clips), so the side column may be 160 to 840.
 */
@OptIn(ExperimentalTestApi::class)
class RaftPaneGridInteractionTest {
    private val side = RaftPaneGridColumnBounds(id = "side", defaultWidthDp = 360f, minWidthDp = 280f)

    private fun ComposeUiTest.showGrid(sizes: RaftPaneGridMemorySizes) {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                CompositionLocalProvider(LocalRaftPaneGridSizes provides sizes) {
                    Box(Modifier.size(1000.dp, 600.dp)) {
                        RaftPaneGrid(
                            columns =
                                listOf(
                                    RaftPaneGridColumn(
                                        bounds = side,
                                        resizeLabel = "Resize side",
                                        splitLabel = "Split side",
                                        cells =
                                            listOf(
                                                RaftPaneGridCell("tasks", defaultShare = 0.6f) { Cell("Tasks") },
                                                RaftPaneGridCell("files", defaultShare = 0.4f) { Cell("Files") },
                                            ),
                                    )
                                ),
                        ) { Cell("Main") }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun assertNear(expected: Float, actual: Float, message: String) = assertTrue(abs(expected - actual) < 0.01f, "$message: expected $expected, was $actual")

    @Test
    fun theColumnSeamStepsWithTheKeyboard() =
        runComposeUiTest {
            val sizes = RaftPaneGridMemorySizes()
            showGrid(sizes)
            val seam = onNodeWithContentDescription("Resize side")
            seam.requestFocus()
            // The seam is the column's left edge: Left widens it.
            seam.performKeyInput { pressKey(Key.DirectionLeft) }
            waitForIdle()
            assertEquals(376f, sizes.widthDp(side))
            seam.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionLeft) } }
            waitForIdle()
            assertEquals(440f, sizes.widthDp(side))
            seam.performKeyInput { pressKey(Key.DirectionRight) }
            waitForIdle()
            assertEquals(424f, sizes.widthDp(side))
            // Home: 160 (its content keeps 280 and clips); End: as wide as the main pane's 160 allows.
            seam.performKeyInput { pressKey(Key.MoveHome) }
            waitForIdle()
            assertEquals(160f, sizes.widthDp(side))
            seam.performKeyInput { pressKey(Key.MoveEnd) }
            waitForIdle()
            assertEquals(840f, sizes.widthDp(side))
        }

    @Test
    fun aDoubleClickRestoresTheDefaultWidth() =
        runComposeUiTest {
            val sizes = RaftPaneGridMemorySizes()
            sizes.setWidth(side, 500f)
            showGrid(sizes)
            onNodeWithContentDescription("Resize side").performMouseInput { doubleClick() }
            waitForIdle()
            assertEquals(360f, sizes.widthDp(side))
        }

    @Test
    fun screenReadersReadAndSetTheWidthAsAnAdjustableValue() =
        runComposeUiTest {
            val sizes = RaftPaneGridMemorySizes()
            showGrid(sizes)
            val seam = onNodeWithContentDescription("Resize side")
            val info = seam.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(360f, info.current)
            assertEquals(160f, info.range.start)
            assertEquals(840f, info.range.endInclusive)
            seam.performSemanticsAction(SemanticsActions.SetProgress) { it(500f) }
            waitForIdle()
            assertEquals(500f, sizes.widthDp(side))
        }

    @Test
    fun theStackSeamMovesTheSplitWithTheKeyboardAndKeepsEachCellAt160() =
        runComposeUiTest {
            val sizes = RaftPaneGridMemorySizes()
            showGrid(sizes)
            val seam = onNodeWithContentDescription("Split side")
            val before = seam.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
            assertNear(0.6f, before, "the upper cell's part of the pair")
            seam.requestFocus()
            seam.performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            val down = sizes.shares("tasks+files", listOf(0.6f, 0.4f))
            assertTrue(down[0] > 0.6f, "Down grows the upper cell: $down")
            assertNear(1f, down[0] + down[1], "the pair keeps its total")
            // Home: the upper cell at its 160dp minimum of the 599dp it shares.
            seam.performKeyInput { pressKey(Key.MoveHome) }
            waitForIdle()
            val home = sizes.shares("tasks+files", listOf(0.6f, 0.4f))
            assertNear(160f / 599f, home[0], "the upper cell at 160dp")
        }

    @Composable
    private fun Cell(text: String) {
        RaftText(text)
    }
}
