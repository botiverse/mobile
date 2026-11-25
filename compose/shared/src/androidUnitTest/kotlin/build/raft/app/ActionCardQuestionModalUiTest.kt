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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import build.raft.app.data.ActionCardFormIssue
import build.raft.app.data.ActionCardFormIssueCode
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Web parity tests for `question:ask` modal sheets ([RaftActionCardFormSheet], `#wg-mobile-feedback:#301`):
 * single-choice 150 ms highlight + auto-advance, same-page multi-field focus, `Other` focus + Done,
 * multi-choice manual Next, Back answer preservation, 500 ms `justTurned` settle guard, 1.5 s
 * "Submitted · Undo" + close flush, lone single-choice commit hiding, local draft/page persistence,
 * and discard confirmation when no draft key is held.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionCardQuestionModalUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @AfterTest
    fun clearDrafts() {
        forgetActionCardFormDrafts()
    }

    private val vectorQuestionForm by lazy { checkNotNull(ActionCardSpecFixtures.card("question_card").forms?.get("answers")) }

    private fun raw(text: String) =
        ActionCardSpecMsg("actionCard.text.raw", mapOf("en" to "{t}"), mapOf("t" to ActionCardSpecMsgParam.Text(text)))

    private fun singleChoice(
        name: String,
        label: String,
        options: List<Pair<String, String>>,
        other: Boolean = true,
        required: Boolean = true,
    ) = ActionCardSpecChoiceField(
        name = name,
        label = raw(label),
        required = required,
        multiple = false,
        options = options.map { (value, text) -> ActionCardSpecChoiceOption(value, raw(text)) },
        other = if (other) ActionCardSpecChoiceOther(ActionCardSpecMsg("actionCard.question.other", mapOf("en" to "Other…")), maxLength = 200) else null,
    )

    private fun questionForm(
        vararg fields: ActionCardSpecFormField,
        steps: List<ActionCardSpecFormStep>? = null,
        title: String = "Answer Questions",
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
    fun singleChoiceAutoAdvancesAcrossMultiPageModalAndSubmitsAfterUndoWait() = runRobolectricComposeUiTest {
        val form =
            questionForm(
                singleChoice("env", "Which environment?", listOf("staging" to "Staging", "prod" to "Production")),
                singleChoice("region", "Which region?", listOf("us" to "US East", "eu" to "EU West")),
                singleChoice("confirm", "Ready to deploy?", listOf("go" to "Ship it"), other = false),
                steps =
                    listOf(
                        ActionCardSpecFormStep(null, listOf("env")),
                        ActionCardSpecFormStep(null, listOf("region")),
                        ActionCardSpecFormStep(null, listOf("confirm")),
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

        // Page 1: tap Staging -> highlighted for 150 ms, then advances to Page 2.
        onNodeWithText("Question 1 of 3").assertExists()
        onNodeWithText("Staging").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS - 50)
        onNodeWithText("Question 1 of 3").assertExists()
        onNodeWithText("Staging").assertIsSelected()
        advance(100)
        onNodeWithText("Question 2 of 3").assertExists()

        // Wait past 500 ms page-turn settle guard, then tap EU West -> advances to Page 3.
        advance(ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS)
        onNodeWithText("EU West").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithText("Question 3 of 3").assertExists()

        // Page 3 (confirm button): wait past settle, tap Ship it -> 150 ms -> enters 1.5 s Undo wait.
        advance(ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS)
        onNodeWithText("Ship it").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithTag(ACTION_CARD_FORM_UNDO_ROW_TAG).assertExists()
        onNodeWithText("Submitted ·").assertExists()
        onNodeWithText("Undo").assertExists()
        assertEquals(emptyList(), submitted, "nothing sent while Undo wait runs")

        // Still waiting before 1500 ms; sent once after 1500 ms with all 3 pages' answers.
        advance(QUESTION_SUBMIT_UNDO_MS - 150)
        assertEquals(emptyList(), submitted)
        advance(200)
        assertEquals(
            listOf(
                mapOf<String, ActionCardFormValue>(
                    "env" to ActionCardFormValue.Choice(listOf("staging")),
                    "region" to ActionCardFormValue.Choice(listOf("eu")),
                    "confirm" to ActionCardFormValue.Choice(listOf("go")),
                ),
            ),
            submitted,
        )
    }

    @Test
    fun samePageMultipleFieldsAdvancesToNextFieldBeforeSubmitting() = runRobolectricComposeUiTest {
        val form =
            questionForm(
                singleChoice("q1", "First question?", listOf("a" to "Alpha", "b" to "Beta")),
                singleChoice("q2", "Second question?", listOf("x" to "Xray", "y" to "Yankee")),
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

        // Tapping q1 does NOT submit because q2 is still on the same page.
        onNodeWithText("Alpha").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 300)
        onNodeWithTag(ACTION_CARD_FORM_UNDO_ROW_TAG).assertDoesNotExist()
        assertEquals(emptyList(), submitted)

        // Tapping q2 (last field on the page) starts the Undoable submit.
        onNodeWithText("Yankee").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithTag(ACTION_CARD_FORM_UNDO_ROW_TAG).assertExists()
        advance(QUESTION_SUBMIT_UNDO_MS + 50)
        assertEquals(
            listOf(
                mapOf<String, ActionCardFormValue>(
                    "q1" to ActionCardFormValue.Choice(listOf("a")),
                    "q2" to ActionCardFormValue.Choice(listOf("y")),
                ),
            ),
            submitted,
        )
    }

    @Test
    fun otherDoesNotAutoAdvanceAndDoneAdvancesImmediately() = runRobolectricComposeUiTest {
        val submitted = mutableListOf<Map<String, ActionCardFormValue>>()
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                var values by remember { mutableStateOf(initialActionCardFormValues(vectorQuestionForm)) }
                RaftActionCardFormSheet(
                    form = vectorQuestionForm,
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

        // Choosing Other does NOT auto-advance even well past 150 ms.
        onNodeWithText("Other").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS * 4)
        onNodeWithText("Question 1 of 3").assertExists()
        onNode(hasSetTextAction()).assertExists()

        // Pressing Done while empty shows "Write your answer first." and stays on Page 1.
        onNode(hasSetTextAction()).performImeAction()
        advance(50)
        onNodeWithText("Write your answer first.").assertExists()
        onNodeWithText("Question 1 of 3").assertExists()

        // Typing text and pressing Done advances immediately to Page 2 (no 150 ms wait).
        onNode(hasSetTextAction()).performTextInput("Friday morning")
        advance(50)
        onNode(hasSetTextAction()).performImeAction()
        advance(50)
        onNodeWithText("Question 2 of 3").assertExists()
        assertEquals(emptyList(), submitted)
    }

    @Test
    fun multiChoiceDoesNotAutoAdvanceAndNextAdvances() = runRobolectricComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                var values by remember { mutableStateOf(initialActionCardFormValues(vectorQuestionForm)) }
                RaftActionCardFormSheet(
                    form = vectorQuestionForm,
                    values = values,
                    onValuesChange = { values = it },
                    fieldErrors = emptyList(),
                    submitting = false,
                    onSubmit = {},
                    onDismiss = {},
                    question = true,
                    nowMillis = { mainClock.currentTime },
                )
            }
        }
        advance(50)

        // Advance from Page 1 to Page 2 (multi-choice).
        onNodeWithText("周二 10:00").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithText("Question 2 of 3").assertExists()
        advance(ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS)

        // Ticking checkboxes does NOT auto-advance.
        onNodeWithText("staging").performClick()
        onNodeWithText("canary（5%）").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS * 5)
        onNodeWithText("Question 2 of 3").assertExists()

        // Tapping Next advances to Page 3.
        onNodeWithText("Next").performClick()
        advance(50)
        onNodeWithText("Question 3 of 3").assertExists()
    }

    @Test
    fun backPreservesAnswersAcrossPagesAndSubmitsEditedEarlierAnswer() = runRobolectricComposeUiTest {
        val submitted = mutableListOf<Map<String, ActionCardFormValue>>()
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                var values by remember { mutableStateOf(initialActionCardFormValues(vectorQuestionForm)) }
                RaftActionCardFormSheet(
                    form = vectorQuestionForm,
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

        // Page 1: pick 周二 10:00 -> auto-advance to Page 2.
        onNodeWithText("周二 10:00").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithText("Question 2 of 3").assertExists()
        advance(ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS)

        // Page 2: tick staging, then go Back to Page 1.
        onNodeWithText("staging").performClick()
        advance(50)
        onNodeWithText("staging").assertIsOn()
        onNodeWithContentDescription("Back").performClick()
        advance(50)
        onNodeWithText("Question 1 of 3").assertExists()
        onNodeWithText("周二 10:00").assertIsSelected()

        // Wait past settle window, change Page 1 to 周三 15:00 -> auto-advances back to Page 2 with staging still checked!
        advance(ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS)
        onNodeWithText("周三 15:00").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithText("Question 2 of 3").assertExists()
        onNodeWithText("staging").assertIsOn()
        onNodeWithText("canary（5%）").assertIsOff()

        // Proceed to Page 3 and submit -> payload carries the edited Page 1 + preserved Page 2 answers.
        advance(ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS)
        onNodeWithText("Next").performClick()
        advance(ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS + 50)
        onNodeWithText("Question 3 of 3").assertExists()
        onNodeWithText("Answer").performClick()
        advance(QUESTION_SUBMIT_UNDO_MS + 50)
        assertEquals(1, submitted.size)
        assertEquals(ActionCardFormValue.Choice(listOf("wed_pm")), submitted.single()["window"])
        assertEquals(ActionCardFormValue.Choice(listOf("staging")), submitted.single()["envs"])
    }

    @Test
    fun justTurnedPreventsAccidentalDoubleTapAcrossPageTurn() = runRobolectricComposeUiTest {
        val form =
            questionForm(
                singleChoice("p1", "Page one?", listOf("a" to "Option A", "b" to "Option B")),
                singleChoice("p2", "Page two?", listOf("c" to "Option C", "d" to "Option D")),
                singleChoice("p3", "Page three?", listOf("e" to "Option E", "f" to "Option F")),
                steps =
                    listOf(
                        ActionCardSpecFormStep(null, listOf("p1")),
                        ActionCardSpecFormStep(null, listOf("p2")),
                        ActionCardSpecFormStep(null, listOf("p3")),
                    ),
            )
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
                    onSubmit = {},
                    onDismiss = {},
                    question = true,
                    nowMillis = { mainClock.currentTime },
                )
            }
        }
        advance(50)

        // Page 1 -> Page 2.
        onNodeWithText("Option A").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 10)
        onNodeWithText("Question 2 of 3").assertExists()

        // Within 500 ms settle window: tapping Option C selects it, but does NOT advance to Page 3; tapping Next also does nothing.
        advance(100)
        onNodeWithText("Option C").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithText("Question 2 of 3").assertExists()
        onNodeWithText("Option C").assertIsSelected()
        onNodeWithText("Next").performClick()
        advance(50)
        onNodeWithText("Question 2 of 3").assertExists()

        // Once >= 500 ms has elapsed since the turn, re-tapping Option C advances cleanly to Page 3.
        advance(ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS)
        onNodeWithText("Option C").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithText("Question 3 of 3").assertExists()
    }

    @Test
    fun undoCancelsSubmitAndClosingWhileWaitingFlushesImmediately() = runRobolectricComposeUiTest {
        val form =
            questionForm(
                singleChoice("q", "Pick one?", listOf("a" to "Choice A", "b" to "Choice B")),
            )
        val submitted = mutableListOf<Map<String, ActionCardFormValue>>()
        var dismissed = false
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
                    onDismiss = { dismissed = true },
                    question = true,
                    nowMillis = { mainClock.currentTime },
                )
            }
        }
        advance(50)

        // Tap Choice A -> 150 ms -> Undo wait starts; choices disabled; nothing sent.
        onNodeWithText("Choice A").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithTag(ACTION_CARD_FORM_UNDO_ROW_TAG).assertExists()
        onNodeWithText("Choice A").assertIsNotEnabled()
        onNodeWithText("Choice B").assertIsNotEnabled()
        assertEquals(emptyList(), submitted)

        // Tap Undo -> editing restored with choice kept, nothing sent even after waiting.
        onNodeWithText("Undo").performClick()
        advance(50)
        onNodeWithTag(ACTION_CARD_FORM_UNDO_ROW_TAG).assertDoesNotExist()
        onNodeWithText("Choice A").assertIsEnabled().assertIsSelected()
        onNodeWithText("Choice B").assertIsEnabled()
        advance(QUESTION_SUBMIT_UNDO_MS * 2)
        assertEquals(emptyList(), submitted, "undone answer is never sent")

        // Tap Choice B -> Undo wait starts -> tap Close during the 1.5 s window -> flushes immediately once.
        onNodeWithText("Choice B").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithTag(ACTION_CARD_FORM_UNDO_ROW_TAG).assertExists()
        onNodeWithContentDescription("Close").performClick()
        advance(50)
        assertEquals(listOf(mapOf<String, ActionCardFormValue>("q" to ActionCardFormValue.Choice(listOf("b")))), submitted)
        assertFalse(dismissed, "closing during Undo flushes submit rather than dismissing without sending")
        advance(QUESTION_SUBMIT_UNDO_MS * 2)
        assertEquals(1, submitted.size, "flushed answer is never sent twice")
    }

    @Test
    fun loneSingleChoiceHidesCommitButtonUntilOtherIsSelectedOrSubmitting() = runRobolectricComposeUiTest {
        val form =
            questionForm(
                singleChoice("q", "Pick one?", listOf("a" to "Choice A", "b" to "Choice B")),
                submitLabel = "Answer",
            )
        var submitting by mutableStateOf(false)
        var question by mutableStateOf(true)
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                var values by remember { mutableStateOf(initialActionCardFormValues(form)) }
                RaftActionCardFormSheet(
                    form = form,
                    values = values,
                    onValuesChange = { values = it },
                    fieldErrors = emptyList(),
                    submitting = submitting,
                    onSubmit = {},
                    onDismiss = {},
                    question = question,
                )
            }
        }
        waitForIdle()

        // Lone single-choice question modal hides redundant primary commit button.
        onNodeWithText("Answer").assertDoesNotExist()

        // Choosing Other reveals the primary commit button.
        onNodeWithText("Other").performClick()
        waitForIdle()
        onNodeWithText("Answer").assertExists().assertIsEnabled()

        // Switching back to a regular option hides the primary commit button again (until submitting).
        onNodeWithText("Choice A").performClick()
        waitForIdle()
        onNodeWithText("Answer").assertDoesNotExist()

        // While submitting is true, the commit button appears to display the spinner.
        submitting = true
        waitForIdle()
        onNodeWithText("Answer").assertExists()

        // Non-question forms always show their commit button.
        submitting = false
        question = false
        waitForIdle()
        onNodeWithText("Answer").assertExists().assertIsEnabled()
    }

    @Test
    fun closingMidWayPersistsDraftAndPageReopenRestoresBothAndSubmitClearsDraft() = runRobolectricComposeUiTest {
        val draftKey = assertNotNull(actionCardFormDraftKey("user-1", "msg-draft-ui", "answers"))
        val submitted = mutableListOf<Map<String, ActionCardFormValue>>()
        var open by mutableStateOf(true)
        var values by mutableStateOf(initialActionCardFormValues(vectorQuestionForm))
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                if (open) {
                    RaftActionCardFormSheet(
                        form = vectorQuestionForm,
                        values = values,
                        onValuesChange = { values = it },
                        fieldErrors = emptyList(),
                        submitting = false,
                        onSubmit = {
                            submitted += it
                            open = false
                        },
                        onDismiss = { open = false },
                        draftKey = draftKey,
                        question = true,
                        nowMillis = { mainClock.currentTime },
                    )
                }
            }
        }
        advance(50)

        // Page 1 -> auto-advance to Page 2 -> tick staging -> close mid-way (no discard dialog).
        onNodeWithText("周二 10:00").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithText("Question 2 of 3").assertExists()
        advance(ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS)
        onNodeWithText("staging").performClick()
        advance(50)
        // Return to Page 1 only to verify close? No: close via system-style reopen simulation on Page 2!
        // Verify draft was persisted for Page 2 with both answers.
        val persisted = assertNotNull(readActionCardFormDraft(draftKey))
        assertEquals(1, persisted.page)
        assertEquals(ActionCardFormValue.Choice(listOf("tue_am")), persisted.values["window"])
        assertEquals(ActionCardFormValue.Choice(listOf("staging")), persisted.values["envs"])

        // Simulate dismissing and reopening with fresh initial values (as ThreadActionCardSpecStore.Press does).
        open = false
        values = initialActionCardFormValues(vectorQuestionForm)
        advance(50)
        open = true
        advance(100)

        // Reopened directly on Page 2 with staging ticked and Page 1 answer intact!
        onNodeWithTag(ACTION_CARD_FORM_DISCARD_DIALOG_TAG).assertDoesNotExist()
        onNodeWithText("Question 2 of 3").assertExists()
        onNodeWithText("staging").assertIsOn()

        // Step forward to Page 3 and submit -> draft is cleared!
        onNodeWithText("Next").performClick()
        advance(ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS + 50)
        onNodeWithText("Question 3 of 3").assertExists()
        onNodeWithText("Answer").performClick()
        advance(QUESTION_SUBMIT_UNDO_MS + 50)
        assertEquals(1, submitted.size)
        assertEquals(ActionCardFormValue.Choice(listOf("tue_am")), submitted.single()["window"])
        assertEquals(ActionCardFormValue.Choice(listOf("staging")), submitted.single()["envs"])
        assertNull(readActionCardFormDraft(draftKey), "successful submit clears draft")

        // Reopening after submit starts fresh on Page 1.
        values = initialActionCardFormValues(vectorQuestionForm)
        open = true
        advance(50)
        onNodeWithText("Question 1 of 3").assertExists()
    }

    @Test
    fun closingDirtyFormWithoutDraftKeyConfirmsDiscard() = runRobolectricComposeUiTest {
        val form =
            questionForm(
                singleChoice("q", "Pick one?", listOf("a" to "Choice A", "b" to "Choice B")),
            )
        var dismissed = false
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
                    onDismiss = { dismissed = true },
                    draftKey = null,
                    question = false,
                )
            }
        }
        waitForIdle()

        // Make form dirty, then tap Close -> Discard confirmation opens.
        onNodeWithText("Choice A").performClick()
        waitForIdle()
        onNodeWithContentDescription("Close").performClick()
        waitForIdle()
        onNodeWithTag(ACTION_CARD_FORM_DISCARD_DIALOG_TAG).assertExists()
        onNodeWithText("Discard changes?").assertExists()
        assertFalse(dismissed)

        // Tap Keep editing -> stays open.
        onNodeWithText("Keep editing").performClick()
        waitForIdle()
        onNodeWithTag(ACTION_CARD_FORM_DISCARD_DIALOG_TAG).assertDoesNotExist()
        onNodeWithText("Choice A").assertIsSelected()
        assertFalse(dismissed)

        // Tap Close again, then Discard -> dismisses.
        onNodeWithContentDescription("Close").performClick()
        waitForIdle()
        onNodeWithText("Discard").performClick()
        waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun closingDirtyFormWithDraftKeyClosesImmediatelyWithoutDiscardDialog() = runRobolectricComposeUiTest {
        val draftKey = assertNotNull(actionCardFormDraftKey("user-1", "msg-no-discard", "answers"))
        var dismissed = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                var values by remember { mutableStateOf(initialActionCardFormValues(vectorQuestionForm)) }
                RaftActionCardFormSheet(
                    form = vectorQuestionForm,
                    values = values,
                    onValuesChange = { values = it },
                    fieldErrors = emptyList(),
                    submitting = false,
                    onSubmit = {},
                    onDismiss = { dismissed = true },
                    draftKey = draftKey,
                    question = true,
                )
            }
        }
        waitForIdle()

        // Choose Other and type text -> dirty, then tap Close -> closes directly with no discard prompt and draft kept.
        onNodeWithText("Other").performClick()
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("Later today")
        waitForIdle()
        onNodeWithContentDescription("Close").performClick()
        waitForIdle()
        onNodeWithTag(ACTION_CARD_FORM_DISCARD_DIALOG_TAG).assertDoesNotExist()
        assertTrue(dismissed)
        assertEquals(
            ActionCardFormValue.Choice(emptyList(), "Later today"),
            assertNotNull(readActionCardFormDraft(draftKey)).values["window"],
        )
    }

    @Test
    fun reTappingAlreadySelectedOptionAfterGoingBackAdvances() = runRobolectricComposeUiTest {
        val form =
            questionForm(
                singleChoice("p1", "Page one?", listOf("a" to "Alpha", "b" to "Beta")),
                singleChoice("p2", "Page two?", listOf("c" to "Gamma", "d" to "Delta")),
                steps =
                    listOf(
                        ActionCardSpecFormStep(null, listOf("p1")),
                        ActionCardSpecFormStep(null, listOf("p2")),
                    ),
            )
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
                    onSubmit = {},
                    onDismiss = {},
                    question = true,
                    nowMillis = { mainClock.currentTime },
                )
            }
        }
        advance(50)

        // Page 1 -> pick Alpha -> Page 2.
        onNodeWithText("Alpha").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithText("Question 2 of 2").assertExists()

        // Go Back to Page 1 -> Alpha is still selected.
        onNodeWithContentDescription("Back").performClick()
        advance(50)
        onNodeWithText("Question 1 of 2").assertExists()
        onNodeWithText("Alpha").assertIsSelected()

        // Past settle guard, re-tapping the already-selected Alpha still triggers onItemClick -> advances to Page 2.
        advance(ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS)
        onNodeWithText("Alpha").performClick()
        advance(QUESTION_CHOICE_PICK_HIGHLIGHT_MS + 50)
        onNodeWithText("Question 2 of 2").assertExists()
    }

    @Test
    fun multiChoiceAutoSearchShowsOnlyAboveFiveItems() = runRobolectricComposeUiTest {
        fun multiField(count: Int) =
            ActionCardSpecChoiceField(
                name = "tags",
                label = raw("Select tags"),
                required = true,
                multiple = true,
                options = (1..count).map { ActionCardSpecChoiceOption("o$it", raw("Tag $it")) },
            )
        var itemCount by mutableStateOf(5)
        var latestValues by mutableStateOf<Map<String, ActionCardFormValue>>(emptyMap())
        setContent {
            val form = remember(itemCount) { questionForm(multiField(itemCount)) }
            RaftTheme(appearance = RaftAppearance.Light) {
                var values by remember(form) { mutableStateOf(initialActionCardFormValues(form)) }
                RaftActionCardFormSheet(
                    form = form,
                    values = values,
                    onValuesChange = {
                        values = it
                        latestValues = it
                    },
                    fieldErrors = emptyList(),
                    submitting = false,
                    onSubmit = {},
                    onDismiss = {},
                    question = true,
                )
            }
        }
        waitForIdle()

        // 5 options: no search box (`RaftChoiceListSearch.Auto`).
        onAllNodes(hasSetTextAction()).assertCountEquals(0)
        onNodeWithText("Tag 1").performClick()
        waitForIdle()
        assertEquals(ActionCardFormValue.Choice(listOf("o1")), latestValues["tags"])

        // 6 options: search box appears, filters rows, and ticking still works inside scrolling form (`lazy = false`).
        itemCount = 6
        waitForIdle()
        onAllNodes(hasSetTextAction()).assertCountEquals(1)
        onNode(hasSetTextAction()).performTextInput("6")
        waitForIdle()
        onNodeWithText("Tag 1").assertDoesNotExist()
        onNodeWithText("Tag 6").performClick()
        waitForIdle()
        assertEquals(ActionCardFormValue.Choice(listOf("o6")), latestValues["tags"])
    }

    @Test
    fun choiceDescriptionSitsBelowLabelAboveOptionsAndStaysVisibleWithError() = runRobolectricComposeUiTest {
        val field =
            ActionCardSpecChoiceField(
                name = "tz",
                label = raw("Which time zone?"),
                description = raw("All times are UTC."),
                required = true,
                multiple = false,
                options =
                    listOf(
                        ActionCardSpecChoiceOption("utc", raw("UTC")),
                        ActionCardSpecChoiceOption("local", raw("My time zone")),
                    ),
            )
        val form = questionForm(field)
        val issues =
            listOf(
                ActionCardFormIssue(
                    field = "tz",
                    code = ActionCardFormIssueCode.Required,
                    message = raw("Pick one option."),
                ),
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                var values by remember { mutableStateOf(initialActionCardFormValues(form)) }
                RaftActionCardFormSheet(
                    form = form,
                    values = values,
                    onValuesChange = { values = it },
                    fieldErrors = issues,
                    submitting = false,
                    onSubmit = {},
                    onDismiss = {},
                    question = true,
                )
            }
        }
        waitForIdle()

        val labelBounds = onNodeWithText("Which time zone?").getBoundsInRoot()
        val descriptionBounds = onNodeWithText("All times are UTC.").getBoundsInRoot()
        val optionBounds = onNodeWithText("UTC").getBoundsInRoot()
        val errorBounds = onNodeWithText("Pick one option.").getBoundsInRoot()

        assertTrue(descriptionBounds.top >= labelBounds.bottom, "description sits below question label")
        assertTrue(descriptionBounds.bottom <= optionBounds.top, "description sits above options card")
        assertTrue(errorBounds.top >= optionBounds.bottom, "validation error sits below options card while description stays visible")
    }
}
