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
import androidx.compose.ui.unit.height
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftSaveStatusHolder
import build.raft.designsystem.components.RaftSaveStatusState
import build.raft.designsystem.components.RaftSelectTrigger
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * raft-ui 0.84.0 SaveStatus: only a failure shows. Idle, a setting is exactly as tall as one
 * without a SaveStatus; a failure grows it by its line, 4dp under the control or description.
 */
@OptIn(ExperimentalTestApi::class)
class RaftSaveStatusTest {
    private val failure = "Couldn't save. Try again."

    @Test
    fun aFieldSaysNothingUntilAChangeFails() =
        runComposeUiTest {
            var state by mutableStateOf<RaftSaveStatusState?>(null)
            setContent {
                RaftTheme {
                    RaftField(label = "Time zone", modifier = Modifier.testTag("field"), description = "Used for times in messages.", saveStatus = state) {
                        RaftSelectTrigger(value = "Asia/Shanghai", onClick = {})
                    }
                }
            }
            val without = onNodeWithTag("field").getBoundsInRoot().height
            state = RaftSaveStatusState.Idle
            waitForIdle()
            assertEquals(without, onNodeWithTag("field").getBoundsInRoot().height, "idle takes no room")
            onNodeWithText("Saved").assertDoesNotExist()
            state = RaftSaveStatusState.Failed(failure)
            waitForIdle()
            onNodeWithText(failure).assertExists()
            assertTrue(onNodeWithTag("field").getBoundsInRoot().height > without, "a failure grows the field")
        }

    @Test
    fun theHolderEmptiesTheLineOnceSavedAndKeepsAFailureUntilTheNextChange() {
        val holder = RaftSaveStatusHolder()
        holder.saving()
        assertEquals(RaftSaveStatusState.Idle, holder.state)
        holder.saved()
        assertEquals(RaftSaveStatusState.Idle, holder.state)
        holder.failed(failure)
        assertEquals(RaftSaveStatusState.Failed(failure), holder.state)
        holder.saving()
        assertEquals(RaftSaveStatusState.Idle, holder.state)
    }
}
