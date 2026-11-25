package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftDisplayPreferenceRuntime
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Appearance › "Show member descriptions" (botiverse/slock #16597): turning the
 * setting off hides descriptions after the name on both human and agent sidebar DM rows, and
 * turning it back on restores them live.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeSidebarDmDescriptionSettingUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun showMemberDescriptionsControlsPersonAndAgentSidebarDmRows() =
        runRobolectricComposeUiTest {
            val previous = RaftDisplayPreferenceRuntime.showSenderDescription
            try {
                RaftDisplayPreferenceRuntime.setShowSenderDescription(true)
                setContent {
                    RaftDesignSystemTheme {
                        Column(Modifier.fillMaxWidth()) {
                            HomeSidebarDirectMessageRow(
                                name = "Ada",
                                description = "Ships the web client",
                                avatarLabel = "AD",
                                peerType = "human",
                                memberId = "u2",
                            )
                            HomeSidebarDirectMessageRow(
                                name = "Scout",
                                description = "Triages incoming bugs",
                                avatarLabel = "SC",
                                peerType = "agent",
                                memberId = "a1",
                            )
                        }
                    }
                }

                waitForIdle()
                onNodeWithText("Ada", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithText("Scout", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithText("Ships the web client", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithText("Triages incoming bugs", useUnmergedTree = true).assertIsDisplayed()

                runOnIdle { RaftDisplayPreferenceRuntime.setShowSenderDescription(false) }
                waitForIdle()
                onNodeWithText("Ada", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithText("Scout", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithText("Ships the web client", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithText("Triages incoming bugs", useUnmergedTree = true).assertDoesNotExist()

                runOnIdle { RaftDisplayPreferenceRuntime.setShowSenderDescription(true) }
                waitForIdle()
                onNodeWithText("Ships the web client", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithText("Triages incoming bugs", useUnmergedTree = true).assertIsDisplayed()
            } finally {
                RaftDisplayPreferenceRuntime.setShowSenderDescription(previous)
            }
        }
}
