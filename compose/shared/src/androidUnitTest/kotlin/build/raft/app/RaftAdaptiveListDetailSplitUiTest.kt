package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftListPaneKind
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun theSidebarSplitterResizesTheListAndKeepsItsWidthUnderTheSameKey() =
        runRobolectricComposeUiTest {
            val values = InMemoryRaftKeyValueStore()
            val resizing = mutableListOf<Boolean>()
            lateinit var widths: RaftListPaneWidths
            setContent {
                widths = remember { RaftListPaneWidths(KeyValueChatLayoutPreferenceStore(values)) }
                RaftAdaptiveListDetailSplit(
                    kind = RaftListPaneKind.Sidebar,
                    widths = widths,
                    dualPane = true,
                    active = true,
                    hasSelection = true,
                    detailBottomInset = 0.dp,
                    listPane = { Box(Modifier.fillMaxSize().testTag(LIST)) },
                    detailPane = { modifier -> Box(modifier.testTag(DETAIL)) },
                    onResizingChange = { resizing += it },
                )
            }
            onNodeWithTag(LIST).assertWidthIsEqualTo(240.dp)

            onNodeWithContentDescription("Resize sidebar").performSemanticsAction(SemanticsActions.SetProgress) { it(320f) }
            onNodeWithTag(LIST).assertWidthIsEqualTo(320.dp)
            assertEquals("320", values.getString(RaftListPaneKind.Sidebar.storageKey))
            // Never past the sidebar's 400 (web COLUMN_BOUNDS.sidebar).
            onNodeWithContentDescription("Resize sidebar").performSemanticsAction(SemanticsActions.SetProgress) { it(900f) }
            assertEquals("400", values.getString(RaftListPaneKind.Sidebar.storageKey))

            onNodeWithContentDescription("Resize sidebar").performTouchInput {
                down(center)
                repeat(10) { moveBy(Offset(-12f, 0f)) }
                up()
            }
            runOnIdle {
                val dragged = widths.preferredDp(RaftListPaneKind.Sidebar)
                assertTrue(dragged in 280f..392f, "dragged 120 left (less the touch slop) from 400, was $dragged")
                assertEquals(dragged.toInt().toString(), values.getString(RaftListPaneKind.Sidebar.storageKey))
                assertEquals(listOf(true, false), resizing)
            }
            onNodeWithTag(DETAIL).assertExists()
        }

    private companion object {
        const val LIST = "split-list"
        const val DETAIL = "split-detail"
    }
}
