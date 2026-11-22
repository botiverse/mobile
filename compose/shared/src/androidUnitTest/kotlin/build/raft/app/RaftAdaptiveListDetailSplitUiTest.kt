package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftListPaneKind
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The list column of the wide split is the pane grid's leading column on the grid's splitter
 * (RaftPaneGrid.kt): set from a screen reader or dragged, the list takes the width, the shell
 * hears the drag start and end once each, and the width is kept under the kind's own key
 * (`mobile:adaptive_list_pane_width_dp:sidebar`, as before the grid) within the kind's limits.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class RaftAdaptiveListDetailSplitUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    /**
     * #4394: a collapsed list with nothing open still shows (the page is otherwise only "Select a
     * conversation"), and hides again once something opens, as the collapse is kept.
     */
    @Test
    fun aCollapsedListShowsWhileNothingIsOpenAndHidesAgainWhenSomethingOpens() =
        runRobolectricComposeUiTest {
            var hasSelection by mutableStateOf(false)
            setContent {
                RaftAdaptiveListDetailSplit(
                    kind = RaftListPaneKind.Sidebar,
                    widths = remember { RaftListPaneWidths(KeyValueChatLayoutPreferenceStore(InMemoryRaftKeyValueStore())) },
                    dualPane = true,
                    active = true,
                    hasSelection = hasSelection,
                    detailBottomInset = 0.dp,
                    listPane = { Box(Modifier.fillMaxSize().testTag(LIST)) },
                    detailPane = { modifier -> Box(modifier.testTag(DETAIL)) },
                    collapsed = true,
                    onCollapsedChange = {},
                )
            }
            onNodeWithTag(LIST).assertExists()
            onNodeWithTag(DETAIL).assertExists()

            hasSelection = true
            waitForIdle()
            onNodeWithTag(LIST).assertDoesNotExist()
            onNodeWithTag(DETAIL).assertExists()
        }

    /**
     * By touch the list's edge is a 24dp touch target beside its rows, so collapsing it takes a deliberate gesture
     * ([raftListPaneDragCollapses]), and a collapsed list leaves the rail toggle's Expand sidebar where it was
     * (web SidebarToggleRailItem). Let go of near the rail, the edge collapses the list, which keeps the width it had;
     * Expand sidebar brings it back; a drag that only overshoots its minimum resizes it, stopping there.
     */
    @Test
    fun byTouchADeliberateReleaseCollapsesTheListAndExpandSidebarWhereItWasBringsItBack() =
        runRobolectricComposeUiTest {
            val sidebar = RaftListPaneKind.Sidebar
            val widths = RaftListPaneWidths(KeyValueChatLayoutPreferenceStore(InMemoryRaftKeyValueStore()))
            var collapsed by mutableStateOf(false)
            setContent { Split(RaftPointerInput.Touch, widths, collapsed) { collapsed = it } }
            // From its default 240dp, 200dp toward the rail (less the touch slop), let go there.
            dragEdgeLeft(200)
            assertTrue(collapsed, "let go near the rail, the list collapses")
            onNodeWithTag(LIST).assertDoesNotExist()
            assertEquals(sidebar.defaultDp, widths.preferredDp(sidebar), "it keeps the width it had before the drag")

            onNodeWithContentDescription("Expand sidebar").assertIsDisplayed().performClick()
            waitForIdle()
            assertFalse(collapsed, "Expand sidebar brings the list back")
            onNodeWithTag(LIST).assertWidthIsEqualTo(240.dp)
            onNodeWithTag(RAFT_LIST_PANE_EXPAND_TEST_TAG).assertDoesNotExist()

            // 100dp: past the minimum (and the web's 40dp snap line), not near the rail.
            dragEdgeLeft(100)
            assertFalse(collapsed, "an overshoot does not collapse the list")
            onNodeWithTag(LIST).assertWidthIsEqualTo(sidebar.minDp.dp)
        }

    /**
     * With a mouse or trackpad the web's snap stays (ColumnResizeHandle SNAP_PAST_END): 40dp past its minimum the list
     * collapses while the edge is still held, and dragged back over the line before letting go it shows again.
     */
    @Test
    fun withAFinePointerTheListSnapsClosedWhileHeldPastItsMinimum() =
        runRobolectricComposeUiTest {
            val widths = RaftListPaneWidths(KeyValueChatLayoutPreferenceStore(InMemoryRaftKeyValueStore()))
            var collapsed by mutableStateOf(false)
            setContent { Split(RaftPointerInput.Fine, widths, collapsed) { collapsed = it } }
            // From its default 240dp, 100dp to the left (less the touch slop), still held: past the line at 160.
            onNodeWithContentDescription("Resize sidebar").performTouchInput {
                down(center)
                repeat(10) { moveBy(Offset(-10f, 0f)) }
            }
            waitForIdle()
            assertTrue(collapsed, "past the line, the list collapses while the edge is held")
            onNodeWithTag(LIST).assertDoesNotExist()
            // 50dp back, over the line, then let go: the list shows again and stays.
            onNodeWithContentDescription("Resize sidebar").performTouchInput {
                repeat(5) { moveBy(Offset(10f, 0f)) }
                up()
            }
            waitForIdle()
            assertFalse(collapsed, "dragged back over the line before letting go, the list stays")
            onNodeWithTag(LIST).assertExists()
        }

    /** The split as the shell lays it out beside an open conversation, with [pointerInput] the input in use. */
    @Composable
    private fun Split(pointerInput: RaftPointerInput, widths: RaftListPaneWidths, collapsed: Boolean, onCollapsedChange: (Boolean) -> Unit) {
        RaftTheme(appearance = RaftAppearance.Light, pointerInput = pointerInput) {
            RaftAdaptiveListDetailSplit(
                kind = RaftListPaneKind.Sidebar,
                widths = widths,
                dualPane = true,
                active = true,
                hasSelection = true,
                detailBottomInset = 0.dp,
                listPane = { Box(Modifier.fillMaxSize().testTag(LIST)) },
                detailPane = { modifier -> Box(modifier.testTag(DETAIL)) },
                collapsed = collapsed,
                onCollapsedChange = onCollapsedChange,
            )
        }
    }

    /** Drags the list's edge [dp] to the left in 10dp steps and lets go. */
    private fun ComposeUiTest.dragEdgeLeft(dp: Int) {
        onNodeWithContentDescription("Resize sidebar").performTouchInput {
            down(center)
            repeat(dp / 10) { moveBy(Offset(-10f, 0f)) }
            up()
        }
        waitForIdle()
    }

    private companion object {
        const val LIST = "split-list"
        const val DETAIL = "split-detail"
    }
}
