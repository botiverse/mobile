package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.data.ActionCardFormValue
import build.raft.app.data.ActionCardSpecChoiceField
import build.raft.app.data.ActionCardSpecChoiceOption
import build.raft.app.data.ActionCardSpecChoiceOther
import build.raft.app.data.ActionCardSpecForm
import build.raft.app.data.ActionCardSpecFormField
import build.raft.app.data.ActionCardSpecFormStep
import build.raft.app.data.ActionCardSpecMsg
import build.raft.app.data.ActionCardSpecMsgParam
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Centered/tablet (`>= 744dp`) dialog footer parity tests for `question:ask` ([RaftActionCardFormSheet]):
 * - Lone single-choice hides the commit button while keeping Cancel visible.
 * - While waiting in the 1.5 s Undo window, "Submitted · Undo" sits in `footerStart` (left of the
 *   footer) with no duplicate inline bar, and Cancel/Back is hidden (`cancelVisible = false`).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w840dp-h900dp-xhdpi")
class ActionCardQuestionModalCenteredUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun raw(text: String) =
        ActionCardSpecMsg("actionCard.text.raw", mapOf("en" to "{t}"), mapOf("t" to ActionCardSpecMsgParam.Text(text)))

    private fun singleChoice(
        name: String,
        label: String,
        options: List<Pair<String, String>>,
        other: Boolean = true,
    ) = ActionCardSpecChoiceField(
        name = name,
        label = raw(label),
        required = true,
        multiple = false,
        options = options.map { (value, text) -> ActionCardSpecChoiceOption(value, raw(text)) },
        other = if (other) ActionCardSpecChoiceOther(ActionCardSpecMsg("actionCard.question.other", mapOf("en" to "Other…")), maxLength = 200) else null,
    )

    private fun questionForm(
        vararg fields: ActionCardSpecFormField,
        steps: List<ActionCardSpecFormStep>? = null,
        title: String = "Answer Question",
        submitLabel: String = "Answer",
    ) = ActionCardSpecForm(
        title = raw(title),
        submitLabel = raw(submitLabel),
        steps = steps,
        fields = fields.toList(),
    )

    private fun ComposeUiTest.advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeBy(millis)
        waitForIdle()
    }

    @Test
    fun loneSingleChoiceKeepsCancelVisibleInCenteredFooterWithoutCommitButton() = runRobolectricComposeUiTest {
        val form =
            questionForm(
                singleChoice("q", "Pick one?", listOf("a" to "Choice A", "b" to "Choice B")),
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                var values by remember { mutableStateOf(initialActionCardFormValues(form)) }
                RaftActionCardFormSheet(
                    form = form,
                    values = values,
                    onValuesChange = { values = it },
                    fieldErrors = emptyList(),
                    submitting = false,
                    onSubmit = {},
                    onDismiss = {},
                    question = true,
                )
            }
        }
        waitForIdle()

        // Commit button is hidden for lone single choice, while Cancel stays visible in the centered footer.
        onNodeWithText("Answer").assertDoesNotExist()
        onNodeWithText("Cancel").assertExists().assertIsEnabled()

        // Selecting Other reveals the commit button beside Cancel.
        onNodeWithText("Other").performClick()
        waitForIdle()
        onNodeWithText("Cancel").assertExists().assertIsEnabled()
        onNodeWithText("Answer").assertExists().assertIsEnabled()
    }

    @Test
    fun pendingUndoRendersOnceInCenteredFooterStartAndHidesCancelAndBack() = runRobolectricComposeUiTest {
        val form =
            questionForm(
                singleChoice("p1", "Step 1?", listOf("a" to "Alpha", "b" to "Beta"), other = false),
                singleChoice("p2", "Step 2?", listOf("c" to "Gamma", "d" to "Delta"), other = false),
                steps =
                    listOf(
                        ActionCardSpecFormStep(null, listOf("p1")),
                        ActionCardSpecFormStep(null, listOf("p2")),
                    ),
            )
        val submitted = mutableListOf<Map<String, ActionCardFormValue>>()
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                var values by remember { mutableStateOf(initialActionCardFormValues(form)) }
                RaftActionCardFormSheet(
                    form = form,
                    values = values,
                    onValuesChange = { values = it },
                    fieldErrors = emptyList(),
                    submitting = false,
                    onSubmit = { submitted += it },
                    onDismiss = {},
                    question = true,
                    nowMillis = { mainClock.currentTime },
                )
            }
        }
        advance(50)

        // Page 1 -> auto-advance to Page 2 where Back and Answer appear in the centered footer.
        onNodeWithText("Cancel").assertExists()
        onNodeWithText("Next").assertExists()
        onNodeWithText("Alpha").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithText("Question 2 of 2").assertExists()
        onNodeWithText("Back").assertExists()
        onNodeWithText("Answer").assertExists()

        // Past settle guard, tap Gamma -> enters 1.5 s Undo wait:
        // - Exactly one Undo row (in footerStart, no duplicate inline bar)
        // - Back, Cancel, and Answer are hidden
        // - Undo row sits at the start (left) of the centered dialog footer
        advance(ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS)
        onNodeWithText("Gamma").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)

        onAllNodesWithTag(ACTION_CARD_FORM_UNDO_ROW_TAG).assertCountEquals(1)
        onNodeWithText("Submitted ·").assertExists()
        onNodeWithText("Undo").assertExists()
        onNodeWithText("Back").assertDoesNotExist()
        onNodeWithText("Cancel").assertDoesNotExist()
        onNodeWithText("Answer").assertDoesNotExist()
        val undoBounds = onNodeWithTag(ACTION_CARD_FORM_UNDO_ROW_TAG).getBoundsInRoot()
        val titleBounds = onNodeWithText("Answer Question").getBoundsInRoot()
        assertTrue(undoBounds.left - titleBounds.left < 32.dp, "Undo row aligns with the dialog's left edge in footerStart")
        assertTrue(undoBounds.top > onNodeWithText("Delta").getBoundsInRoot().bottom, "Undo row sits in the footer below the options")
        assertEquals(emptyList(), submitted)

        // Undo restores Back and Answer and keeps Gamma selected.
        onNodeWithText("Undo").performClick()
        advance(50)
        onAllNodesWithTag(ACTION_CARD_FORM_UNDO_ROW_TAG).assertCountEquals(0)
        onNodeWithText("Back").assertExists().assertIsEnabled()
        onNodeWithText("Answer").assertExists().assertIsEnabled()
        onNodeWithText("Gamma").assertIsEnabled().assertIsSelected()
    }
}
