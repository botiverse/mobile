package build.raft.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import build.raft.designsystem.components.RaftTaskCompactList
import build.raft.designsystem.components.RaftTaskCompactRow
import build.raft.designsystem.components.RaftTaskStatus
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A task row's accessibility (web `TaskChipList`: the button's `aria-label` is all a screen reader
 * reads): one button node with the label, none of its texts read again.
 */
@OptIn(ExperimentalTestApi::class)
class RaftTaskCompactRowSemanticsTest {
    @Test
    fun rowIsOneButtonReadByItsLabelOnly() = runComposeUiTest {
        var clicks by mutableStateOf(0)
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                RaftTaskCompactList {
                    RaftTaskCompactRow(
                        number = "#11",
                        title = "Ship the preview build",
                        onClick = { clicks++ },
                        status = RaftTaskStatus.InReview,
                        statusText = "In Review",
                        replyCount = 3,
                        replyCountDescription = "3 messages in this task",
                        contentDescription = "Open task #11: Ship the preview build",
                    )
                }
            }
        }
        onNodeWithContentDescription("Open task #11: Ship the preview build")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        assertEquals(1, clicks)
        // What a screen reader reads (the merged tree): the label only. Before, the row also merged
        // its texts (the title read again after the label), and the title stood on its own.
        onNodeWithContentDescription("Open task #11: Ship the preview build")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        listOf("Ship the preview build", "#11", "In Review", "3").forEach { text ->
            onAllNodesWithText(text).assertCountEquals(0)
        }
    }
}
