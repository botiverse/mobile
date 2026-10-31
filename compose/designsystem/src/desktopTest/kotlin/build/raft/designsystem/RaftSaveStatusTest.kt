package build.raft.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSaveStatusState
import build.raft.designsystem.components.RaftSelectTrigger
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** raft-ui 0.54.0 SaveStatus: the line is reserved, so its result never resizes the setting or moves its switch. */
@OptIn(ExperimentalTestApi::class)
class RaftSaveStatusTest {
    private val states =
        listOf(
            RaftSaveStatusState.Idle,
            RaftSaveStatusState.Saved,
            RaftSaveStatusState.Failed("Couldn't save. Try again."),
            RaftSaveStatusState.Idle,
        )

    @Test
    fun aSettingsRowKeepsItsHeightAndItsSwitchInEveryState() =
        runComposeUiTest {
            var state: RaftSaveStatusState by mutableStateOf(RaftSaveStatusState.Idle)
            setContent {
                RaftTheme {
                    RaftListRow(
                        title = "Mute this server",
                        modifier = Modifier.testTag("row"),
                        variant = RaftListRowVariant.Plain,
                        description = "No notifications from its channels.",
                        saveStatus = state,
                        trailing = { RaftSwitch(checked = true, onCheckedChange = {}, modifier = Modifier.testTag("switch")) },
                    )
                }
            }
            val row = onNodeWithTag("row").getBoundsInRoot()
            val switch = onNodeWithTag("switch").getBoundsInRoot()
            states.forEach {
                state = it
                waitForIdle()
                assertEquals(row, onNodeWithTag("row").getBoundsInRoot(), "row bounds in $it")
                assertEquals(switch, onNodeWithTag("switch").getBoundsInRoot(), "switch bounds in $it")
            }
        }

    @Test
    fun aFieldKeepsItsHeightInEveryStateAndSaysSaved() =
        runComposeUiTest {
            var state: RaftSaveStatusState by mutableStateOf(RaftSaveStatusState.Idle)
            setContent {
                RaftTheme {
                    RaftField(label = "Time zone", modifier = Modifier.testTag("field"), description = "Used for times in messages.", saveStatus = state) {
                        RaftSelectTrigger(value = "Asia/Shanghai", onClick = {})
                    }
                }
            }
            val field = onNodeWithTag("field").getBoundsInRoot()
            states.forEach {
                state = it
                waitForIdle()
                assertEquals(field, onNodeWithTag("field").getBoundsInRoot(), "field bounds in $it")
                if (it == RaftSaveStatusState.Saved) onNodeWithText("Saved").assertExists()
            }
        }
}
