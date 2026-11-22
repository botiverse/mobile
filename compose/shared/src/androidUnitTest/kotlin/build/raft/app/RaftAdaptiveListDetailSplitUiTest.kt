package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftListPaneKind
import kotlin.test.Test
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

    private companion object {
        const val LIST = "split-list"
        const val DETAIL = "split-detail"
    }
}
