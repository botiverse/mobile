package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftCollapsibleColumn
import build.raft.app.data.RaftListPaneKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rail's Collapse / Expand Sidebar on a real composition, wired as SettingsHomePage wires it:
 * the toggle hides the list column (it leaves composition, the detail stays) and saves the state;
 * pressing it again brings the list back. See AGENTS.md "Compose UI tests".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class RaftSidebarCollapseUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun theRailToggleHidesAndBringsBackTheSidebarAndRemembersIt() =
        runRobolectricComposeUiTest {
            val values = InMemoryRaftKeyValueStore()
            val collapse = RaftSidebarCollapseState(KeyValueChatLayoutPreferenceStore(values))
            val column = RaftCollapsibleColumn.Sidebar
            setContent {
                Row(Modifier.fillMaxSize()) {
                    RaftNavigationRailTabBar(
                        items = listOf(RaftBottomTabItemPayload("home", "Home", RaftLucideIconName.House, selected = true)),
                        onSelect = {},
                        modifier = Modifier.fillMaxHeight(),
                        sidebarToggle = RaftRailSidebarToggle(hidden = collapse.isHidden(column), onHiddenChange = { collapse.setHidden(column, it) }),
                    )
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        RaftAdaptiveListDetailSplit(
                            kind = RaftListPaneKind.Sidebar,
                            widths = remember { RaftListPaneWidths(store = null) },
                            dualPane = true,
                            active = true,
                            hasSelection = true,
                            detailBottomInset = 0.dp,
                            listPane = { Box(Modifier.fillMaxSize().testTag(LIST)) },
                            detailPane = { modifier -> Box(modifier.testTag(DETAIL)) },
                            collapsed = collapse.isHidden(column),
                        )
                    }
                }
            }
            onNodeWithTag(LIST).assertExists()

            onNodeWithContentDescription("Collapse Sidebar").performClick()
            onNodeWithTag(LIST).assertDoesNotExist()
            onNodeWithTag(DETAIL).assertExists()
            assertEquals("true", values.getString(column.storageKey))

            onNodeWithContentDescription("Expand Sidebar").performClick()
            onNodeWithTag(LIST).assertExists()
            assertNull(values.getString(column.storageKey))
        }

    private companion object {
        const val LIST = "sidebar-list"
        const val DETAIL = "detail"
    }
}
