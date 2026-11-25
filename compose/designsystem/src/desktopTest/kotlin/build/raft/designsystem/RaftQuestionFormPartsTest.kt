package build.raft.designsystem

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftDialogSheetCenteredSurface
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftFieldDescriptionPosition
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The design-system parts of the web's question-card form (ActionCardFormSheet,
 * ActionCardChoiceField): a field's description under its label next to an error, and the centred
 * dialog's footer with a start slot and without Cancel.
 */
@OptIn(ExperimentalTestApi::class)
class RaftQuestionFormPartsTest {
    @Test
    fun aDescriptionBelowTheLabelStaysWithTheErrorAboveTheControl() =
        runComposeUiTest {
            setContent {
                RaftTheme {
                    RaftField(
                        label = "Which time zone?",
                        description = "All times are UTC.",
                        error = "Choose an option first.",
                        descriptionPosition = RaftFieldDescriptionPosition.BelowLabel,
                    ) { RaftTextField("", {}, Modifier.testTag("control")) }
                }
            }
            val description = onNodeWithText("All times are UTC.").getBoundsInRoot()
            val control = onNodeWithTag("control").getBoundsInRoot()
            val error = onNodeWithText("Choose an option first.").getBoundsInRoot()
            assertTrue(description.bottom <= control.top, "the description is above the control")
            assertTrue(error.top >= control.bottom, "the error is under the control")
        }

    @Test
    fun theDefaultDescriptionGivesWayToTheError() =
        runComposeUiTest {
            setContent {
                RaftTheme {
                    RaftField(label = "Name", description = "Shown in chat.", error = "Enter a name") { RaftTextField("", {}) }
                }
            }
            onNodeWithText("Enter a name").assertExists()
            onNodeWithText("Shown in chat.").assertDoesNotExist()
        }

    @Test
    fun theCentredFooterTakesAStartSlotAndCanLeaveCancelOut() =
        runComposeUiTest {
            setContent {
                RaftTheme {
                    RaftDialogSheetCenteredSurface(
                        title = "Which time zone?",
                        commitLabel = null,
                        onCommit = {},
                        onClose = {},
                        modifier = Modifier.width(448.dp),
                        cancelLabel = "Cancel",
                        cancelVisible = false,
                        footerStart = { RaftText("Submitted", Modifier.testTag("start")) },
                    ) {}
                }
            }
            onNodeWithText("Cancel").assertDoesNotExist()
            val start = onNodeWithTag("start").getBoundsInRoot()
            assertTrue(start.left < 448.dp / 4, "the slot sits at the footer's start")
        }

    @Test
    fun cancelStaysWithoutACommitButton() =
        runComposeUiTest {
            setContent {
                RaftTheme {
                    RaftDialogSheetCenteredSurface(
                        title = "Which time zone?",
                        commitLabel = null,
                        onCommit = {},
                        onClose = {},
                        cancelLabel = "Cancel",
                    ) {}
                }
            }
            onNodeWithText("Cancel").assertExists()
        }
}
