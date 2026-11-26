package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [ServerSwitcherPanel] and [RaftServerSwitcherSelectionPanel] open with the currently
 * selected server already scrolled into view when the server list exceeds 6 visible rows
 * (dm:@artin:#172).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class HomeServerSwitcherScrollUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val twelveServers =
        (1..12).map { index ->
            RaftServerSwitcherItem(
                id = "srv-$index",
                name = "Workspace $index",
                slug = "workspace-$index",
            )
        }

    @Test
    fun homeServerSwitcherOpensWithBottomSelectedServerVisible() =
        runRobolectricComposeUiTest {
            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        ServerSwitcherPanel(
                            servers = twelveServers,
                            currentServerId = "srv-12",
                            pendingServerId = null,
                            prefersChineseCommunity = false,
                            labels = RaftHomeShellLabels(),
                            onServerSelected = {},
                            onServerOrderChange = {},
                            onCreateServerClick = {},
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithText("Workspace 12").assertIsDisplayed()
            onNodeWithText("/workspace-12").assertIsDisplayed()
        }

    @Test
    fun selectionPanelOpensWithMiddleBelowFoldSelectedServerVisible() =
        runRobolectricComposeUiTest {
            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        RaftServerSwitcherSelectionPanel(
                            servers = twelveServers,
                            selectedServerId = "srv-9",
                            onSelect = {},
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithText("Workspace 9").assertIsDisplayed()
            onNodeWithText("/workspace-9").assertIsDisplayed()
        }
}
