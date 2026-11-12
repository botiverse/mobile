package build.raft.designsystem

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftPaneGridSizes
import build.raft.designsystem.components.RAFT_PANE_GRID_COLUMN_TEST_TAG
import build.raft.designsystem.components.RAFT_PANE_GRID_MAIN_TEST_TAG
import build.raft.designsystem.components.RaftPaneGrid
import build.raft.designsystem.components.RaftPaneGridCell
import build.raft.designsystem.components.RaftPaneGridColumn
import build.raft.designsystem.components.RaftPaneGridColumnBounds
import build.raft.designsystem.components.RaftPaneGridConversationBounds
import build.raft.designsystem.components.RaftPaneGridMemorySizes
import build.raft.designsystem.components.raftPaneGridFolds
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pane grid's row in the app's shape (#189, web rowLayout / rowSeams / rowPlan `decideFold`),
 * on a real composition:
 * - a seam let go saves every shown column's width as shown and the main pane's, so the row stays
 *   exactly as it was let go (Lagoon's review of #4658: saving only the dragged column made the
 *   others jump);
 * - a column that is a tab not shown takes no focus from Tab (web `inert`);
 * - the thread's column, declared one level in, folds by the plan: under 720 alone, under 1000 with
 *   Tasks / Files handed up from the conversation two levels in.
 */
@OptIn(ExperimentalTestApi::class)
class RaftPaneGridRowFoldTest {
    private val thread = RaftPaneGridColumnBounds(id = "thread", defaultWidthDp = 400f, minWidthDp = 360f)
    private val cards = RaftPaneGridColumnBounds(id = "cards", defaultWidthDp = 360f, minWidthDp = 280f)
    private val artifact = RaftPaneGridColumnBounds(id = "artifact", defaultWidthDp = 480f, minWidthDp = 480f)
    private val profile = RaftPaneGridColumnBounds(id = "profile", defaultWidthDp = 400f, minWidthDp = 320f)

    private var rowWidth by mutableStateOf(1100f)

    @Composable
    private fun Frame(sizes: RaftPaneGridMemorySizes, content: @Composable () -> Unit) {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
            CompositionLocalProvider(LocalRaftPaneGridSizes provides sizes) {
                // Wider than the test window: laid out from its start, unclipped by the window.
                Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(rowWidth.dp, 600.dp)) { content() }
            }
        }
    }

    private fun column(bounds: RaftPaneGridColumnBounds, vararg cells: String) =
        RaftPaneGridColumn(bounds, "Resize ${bounds.id}", cells.map { cell -> RaftPaneGridCell(cell) { Cell(cell) } }, tabLabel = bounds.id)

    private fun ComposeUiTest.widthDp(tag: String): Float =
        with(density) { onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().size.width.toDp().value }

    private fun near(expected: Float, actual: Float, what: String) = assertTrue(abs(expected - actual) <= 1f, "$what: expected $expected, was $actual")

    @Test
    fun lettingASeamGoSavesEveryShownWidthSoNothingJumps() =
        runComposeUiTest {
            // Lagoon's row: 1100 for the conversation (none kept: 400), the thread (400) and Tasks / Files (360): 60
            // short, so each gives way in proportion above its minimum (385, 385, 330).
            rowWidth = 1100f
            val sizes = RaftPaneGridMemorySizes()
            setContent { Frame(sizes) { RaftPaneGrid(listOf(column(thread, "thread"), column(cards, "tasks", "files"))) { Cell("main") } } }
            waitForIdle()
            near(385f, widthDp(RAFT_PANE_GRID_COLUMN_TEST_TAG + thread.id), "thread before")
            near(330f, widthDp(RAFT_PANE_GRID_COLUMN_TEST_TAG + cards.id), "cards before")
            // Drag the cards' seam right (narrower), and look at the row before letting go.
            val seam = onNodeWithContentDescription("Resize cards")
            val step = with(density) { 10.dp.toPx() }
            seam.performMouseInput {
                moveTo(center)
                press()
                repeat(5) { moveBy(Offset(step, 0f)) }
            }
            waitForIdle()
            val held =
                listOf(RAFT_PANE_GRID_MAIN_TEST_TAG, RAFT_PANE_GRID_COLUMN_TEST_TAG + thread.id, RAFT_PANE_GRID_COLUMN_TEST_TAG + cards.id)
                    .associateWith { widthDp(it) }
            assertTrue(held.getValue(RAFT_PANE_GRID_COLUMN_TEST_TAG + cards.id) < 329f, "the drag narrowed the cards: $held")
            seam.performMouseInput { release() }
            waitForIdle()
            // Let go, the row is the one held: no column moves (saving the cards' alone moved the thread and the main pane).
            held.forEach { (tag, width) -> near(width, widthDp(tag), "$tag after release") }
            // Every shown width is kept, the conversation's too, and together they are the row.
            val conversation = assertNotNull(sizes.savedWidthDp(RaftPaneGridConversationBounds), "the conversation's width is kept")
            near(held.getValue(RAFT_PANE_GRID_MAIN_TEST_TAG), conversation, "kept conversation")
            near(held.getValue(RAFT_PANE_GRID_COLUMN_TEST_TAG + thread.id), sizes.widthDp(thread), "kept thread")
            near(held.getValue(RAFT_PANE_GRID_COLUMN_TEST_TAG + cards.id), sizes.widthDp(cards), "kept cards")
            near(rowWidth, conversation + sizes.widthDp(thread) + sizes.widthDp(cards), "kept widths fill the row")
        }

    @Test
    fun aKeyOnASeamSavesTheRowAsShownToo() =
        runComposeUiTest {
            rowWidth = 1100f
            val sizes = RaftPaneGridMemorySizes()
            setContent { Frame(sizes) { RaftPaneGrid(listOf(column(thread, "thread"), column(cards, "tasks", "files"))) { Cell("main") } } }
            waitForIdle()
            val seam = onNodeWithContentDescription("Resize cards")
            seam.requestFocus()
            // Right narrows the column 16: from 330 to 314; the thread keeps its 385, the main pane takes the rest.
            seam.performKeyInput { pressKey(Key.DirectionRight) }
            waitForIdle()
            near(314f, widthDp(RAFT_PANE_GRID_COLUMN_TEST_TAG + cards.id), "cards")
            near(385f, widthDp(RAFT_PANE_GRID_COLUMN_TEST_TAG + thread.id), "thread")
            near(401f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "main")
            near(401f, assertNotNull(sizes.savedWidthDp(RaftPaneGridConversationBounds)), "kept conversation")
        }

    /** Each focusable cell that has had focus. */
    private val focused = mutableSetOf<String>()

    @Composable
    private fun Cell(name: String) {
        Box(Modifier.fillMaxSize().testTag("cell-$name").onFocusChanged { if (it.isFocused) focused += name }.focusable()) { RaftText(name) }
    }

    @Test
    fun aColumnThatIsATabNotShownTakesNoFocus() =
        runComposeUiTest {
            // 700: five columns of 160 don't fit, four do: the profile is a tab in the Artifact's column, not shown.
            rowWidth = 700f
            focused.clear()
            val sizes = RaftPaneGridMemorySizes()
            setContent {
                Frame(sizes) {
                    RaftPaneGrid(
                        listOf(column(thread, "thread"), column(cards, "tasks", "files"), column(artifact, "artifact"), column(profile, "profile", "details")),
                        mainTabLabel = "Chat",
                    ) { Cell("main") }
                }
            }
            waitForIdle()
            assertEquals(0f, widthDp(RAFT_PANE_GRID_COLUMN_TEST_TAG + profile.id), "the profile is the tab not shown")
            val main = onNodeWithTag("cell-main", useUnmergedTree = true)
            main.requestFocus()
            // Tab round the whole row, more than once.
            main.performKeyInput { repeat(40) { pressKey(Key.Tab) } }
            waitForIdle()
            assertTrue("artifact" in focused, "Tab reaches the column shown in the group: $focused")
            assertFalse("profile" in focused || "details" in focused, "Tab never enters the tab not shown: $focused")
            // Round past it and back to the start: the hidden tab doesn't trap the focus either.
            focused.clear()
            main.performKeyInput { repeat(40) { pressKey(Key.Tab) } }
            waitForIdle()
            assertTrue("main" in focused && "thread" in focused, "Tab goes round past the tab not shown: $focused")
        }

    private var folds: Boolean? = null
    private var cardsOpen by mutableStateOf(false)

    /** The workspace's shape: the split's grid, the detail's grid one level in (the thread), the conversation's two in. */
    private fun ComposeUiTest.showWorkspace() {
        val sizes = RaftPaneGridMemorySizes()
        setContent {
            Frame(sizes) {
                RaftPaneGrid(columns = emptyList()) {
                    val threadFolds = raftPaneGridFolds(thread)
                    folds = threadFolds
                    RaftPaneGrid(columns = if (threadFolds == false) listOf(column(thread, "thread")) else emptyList()) {
                        RaftPaneGrid(columns = if (cardsOpen) listOf(column(cards, "tasks", "files")) else emptyList()) { Cell("main") }
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun theThreadFoldsByThePlanNotAFixedWidth() =
        runComposeUiTest {
            cardsOpen = false
            rowWidth = 720f
            showWorkspace()
            assertEquals(false, folds, "at 720 the conversation and the thread keep 360 each")
            rowWidth = 719f
            waitForIdle()
            assertEquals(true, folds, "under 720 the thread folds")
            // Tasks / Files beside the conversation: the thread folds before anything would clip, under 360 + 360 + 280.
            cardsOpen = true
            rowWidth = 1000f
            waitForIdle()
            assertEquals(false, folds, "at 1000 all three stand side by side")
            near(360f, widthDp(RAFT_PANE_GRID_COLUMN_TEST_TAG + thread.id), "thread at 1000")
            near(280f, widthDp(RAFT_PANE_GRID_COLUMN_TEST_TAG + cards.id), "cards at 1000")
            rowWidth = 999f
            waitForIdle()
            assertEquals(true, folds, "under 1000 the thread folds")
            // Folded, the cards still stand beside the conversation, at their 360 (the conversation has what is left).
            near(360f, widthDp(RAFT_PANE_GRID_COLUMN_TEST_TAG + cards.id), "cards beside the folded conversation")
            near(639f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "the conversation beside them")
            // Wider again the plan lets the thread stand beside (the app's fold is the page's to undo).
            rowWidth = 1200f
            waitForIdle()
            assertEquals(false, folds)
        }
}
