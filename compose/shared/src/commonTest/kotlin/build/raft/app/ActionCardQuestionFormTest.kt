package build.raft.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import build.raft.app.data.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure parts of the question-card form sheet (schemaVersion 8): pages, drafts, settle guard, what a Choice still lacks, Enter in "Other". */
class ActionCardQuestionFormTest {
    @AfterTest
    fun clearDrafts() {
        forgetActionCardFormDrafts()
    }
    private fun msg(text: String) = ActionCardSpecMsg("actionCard.text.raw", mapOf("en" to "{t}"), mapOf("t" to ActionCardSpecMsgParam.Text(text)))

    private fun choice(name: String, required: Boolean? = true, multiple: Boolean = false, other: Boolean = true, default: ActionCardFormValue.Choice? = null) =
        ActionCardSpecChoiceField(
            name = name,
            label = msg("Question $name?"),
            required = required,
            multiple = multiple,
            options = listOf(ActionCardSpecChoiceOption("a", msg("A")), ActionCardSpecChoiceOption("b", msg("B"))),
            other = if (other) ActionCardSpecChoiceOther(msg("Other…"), maxLength = 5) else null,
            default = default,
        )

    private val notes = ActionCardSpecTextAreaField(name = "notes", label = msg("Notes?"), maxLength = 100)

    private fun form(vararg fields: ActionCardSpecFormField, steps: List<ActionCardSpecFormStep>? = null) =
        ActionCardSpecForm(title = msg("Form"), submitLabel = msg("Send"), steps = steps, fields = fields.toList())

    @Test
    fun aFormWithoutStepsIsOnePageOfEveryField() {
        val pages = actionCardFormPages(form(choice("one"), notes))
        assertEquals(1, pages.size)
        assertEquals(listOf("one", "notes"), pages.single().fields.map { it.name })
        assertNull(pages.single().title)
    }

    @Test
    fun stepsPageTheFieldsInTheirOrderWithTheirTitles() {
        val pages =
            actionCardFormPages(
                form(
                    choice("one"), choice("two"), notes,
                    steps = listOf(ActionCardSpecFormStep(msg("First"), listOf("two", "one")), ActionCardSpecFormStep(null, listOf("notes"))),
                ),
            )
        assertEquals(listOf(listOf("two", "one"), listOf("notes")), pages.map { page -> page.fields.map { it.name } })
        assertEquals(listOf("First", null), pages.map { page -> page.title?.let { renderActionCardMsgText(it, "en") } })
    }

    @Test
    fun initialValuesAreEmptyChoicesOrTheAgentsDefault() {
        val prefilled = ActionCardFormValue.Choice(listOf("b"), "x")
        val values = initialActionCardFormValues(form(choice("plain"), choice("prefilled", multiple = true, default = prefilled)))
        assertEquals(ActionCardFormValue.Choice(emptyList()), values["plain"])
        assertEquals(prefilled, values["prefilled"])
    }

    @Test
    fun onlyARequiredUnansweredChoiceOfThePageHoldsTheCommitBack() {
        val required = choice("one")
        val optional = choice("two", required = null)
        val values = initialActionCardFormValues(form(required, optional, notes))
        assertTrue(actionCardFormHasPendingChoice(listOf(required), values))
        assertFalse(actionCardFormHasPendingChoice(listOf(optional, notes), values))
        assertFalse(actionCardFormHasPendingChoice(listOf(required), values + ("one" to ActionCardFormValue.Choice(listOf("a")))))
        // Other chosen but empty still holds it back.
        assertTrue(actionCardFormHasPendingChoice(listOf(required), values + ("one" to ActionCardFormValue.Choice(emptyList(), ""))))
        // A missing value reads as nothing chosen.
        assertTrue(actionCardFormHasPendingChoice(listOf(required), emptyMap()))
    }

    @Test
    fun theEnterThatConfirmsAnImeCompositionDoesNotSubmit() {
        assertTrue(actionCardOtherEnterConfirms(null))
        assertFalse(actionCardOtherEnterConfirms(TextRange(0, 2)))
        assertFalse(actionCardOtherEnterConfirms(TextRange(3, 3)))
    }

    @Test
    fun otherIsClippedToItsMaxLengthInCodePointsNeverSplittingAPair() {
        assertEquals("abc", actionCardClipCodePoints("abc", 5))
        assertEquals("abcde", actionCardClipCodePoints("abcdefg", 5))
        assertEquals("😀😀😀", actionCardClipCodePoints("😀😀😀😀😀", 3))
        assertEquals("a😀", actionCardClipCodePoints("a😀b", 2))
        assertEquals("", actionCardClipCodePoints("abc", 0))
    }

    @Test
    fun theAppDeclaresFieldChoiceOnceTheSheetRendersIt() {
        assertTrue("field.choice" in RAFT_APP_ACTION_CARD_CAPABILITIES)
    }

    @Test
    fun draftKeyNamesUserMessageAndFormAndIsNullWithoutUser() {
        assertEquals(
            "raft.actionCardFormDraft.v1.user-1.msg-42.answers",
            actionCardFormDraftKey("user-1", "msg-42", "answers"),
        )
        assertNull(actionCardFormDraftKey(null, "msg-42", "answers"))
        assertNull(actionCardFormDraftKey("   ", "msg-42", "answers"))
        assertNull(actionCardFormDraftKey("user-1", "", "answers"))
    }

    @Test
    fun draftStorePersistsRestoresClampsPageAndClearsByMessageOrSession() {
        val keyA = assertNotNull(actionCardFormDraftKey("user-1", "msg-1", "answers"))
        val keyB = assertNotNull(actionCardFormDraftKey("user-2", "msg-1", "answers"))
        val keyOther = assertNotNull(actionCardFormDraftKey("user-1", "msg-2", "answers"))
        val saved = ActionCardFormDraft(page = 1, values = mapOf("one" to ActionCardFormValue.Choice(listOf("a"))))
        assertTrue(writeActionCardFormDraft(keyA, saved))
        assertTrue(writeActionCardFormDraft(keyB, saved))
        assertTrue(writeActionCardFormDraft(keyOther, saved))
        assertEquals(saved, readActionCardFormDraft(keyA))

        // Clearing by message removes every user's draft for that card, leaving other cards alone.
        clearActionCardFormDrafts("msg-1")
        assertNull(readActionCardFormDraft(keyA))
        assertNull(readActionCardFormDraft(keyB))
        assertEquals(saved, readActionCardFormDraft(keyOther))

        // Forget clears every remaining draft (sign-out).
        forgetActionCardFormDrafts()
        assertNull(readActionCardFormDraft(keyOther))
    }

    @Test
    fun draftRestoreValidatesFieldShapesAndClampsOutOfRangePage() {
        val pagedForm =
            form(
                choice("one"),
                notes,
                steps = listOf(ActionCardSpecFormStep(null, listOf("one")), ActionCardSpecFormStep(null, listOf("notes"))),
            )
        val draft =
            ActionCardFormDraft(
                page = 9,
                values =
                    mapOf(
                        "one" to ActionCardFormValue.Text("wrong-shape"),
                        "notes" to ActionCardFormValue.Text("kept note"),
                    ),
            )
        val restored = restoreActionCardFormDraft(pagedForm, draft)
        assertEquals(1, restored.page)
        assertEquals(ActionCardFormValue.Choice(emptyList()), restored.values["one"])
        assertEquals(ActionCardFormValue.Text("kept note"), restored.values["notes"])
    }

    @Test
    fun sameActionCardFormValueMatchesListsIgnoringOrderAndExactOtherText() {
        assertTrue(
            sameActionCardFormValue(
                ActionCardFormValue.Choice(listOf("a", "b"), "x"),
                ActionCardFormValue.Choice(listOf("b", "a"), "x"),
            ),
        )
        assertFalse(
            sameActionCardFormValue(
                ActionCardFormValue.Choice(listOf("a"), "x"),
                ActionCardFormValue.Choice(listOf("a"), "y"),
            ),
        )
        assertFalse(
            sameActionCardFormValue(
                ActionCardFormValue.Choice(listOf("a"), null),
                ActionCardFormValue.Choice(listOf("a"), ""),
            ),
        )
        assertTrue(
            sameActionCardFormValue(
                ActionCardFormValue.Ids(listOf("u1", "u2")),
                ActionCardFormValue.Ids(listOf("u2", "u1")),
            ),
        )
    }

    @Test
    fun tapSubmitsFormHidesCommitOnlyForALoneSingleChoiceWithoutOther() {
        val single = choice("one")
        val multi = choice("one", multiple = true)
        assertTrue(actionCardTapSubmitsForm(listOf(single), emptyMap()))
        assertTrue(actionCardTapSubmitsForm(listOf(single), mapOf("one" to ActionCardFormValue.Choice(listOf("a")))))
        assertFalse(actionCardTapSubmitsForm(listOf(single), mapOf("one" to ActionCardFormValue.Choice(emptyList(), ""))))
        assertFalse(actionCardTapSubmitsForm(listOf(multi), emptyMap()))
        assertFalse(actionCardTapSubmitsForm(listOf(single, notes), emptyMap()))
        assertFalse(actionCardTapSubmitsForm(listOf(notes), emptyMap()))
    }

    @Test
    fun pageTurnSettleGuardBlocksCommitWithinWindowAndBackResetsWindow() {
        var now = 1_000L
        val pagedForm =
            form(
                choice("one"),
                choice("two"),
                steps = listOf(ActionCardSpecFormStep(null, listOf("one")), ActionCardSpecFormStep(null, listOf("two"))),
            )
        val state =
            ActionCardFormSheetState(
                form = pagedForm,
                serverIssues = emptyList(),
                clientIssuesState = mutableStateOf(emptyList()),
                pageState = mutableStateOf(0),
                triedPagesState = mutableStateOf(emptySet()),
                turnRef = ActionCardPageTurnRef(),
                nowMillis = { now },
            )
        val answered =
            mapOf<String, ActionCardFormValue>(
                "one" to ActionCardFormValue.Choice(listOf("a")),
                "two" to ActionCardFormValue.Choice(listOf("b")),
            )
        val submitted = mutableListOf<Map<String, ActionCardFormValue>>()

        // Freshly opened sheet: not just turned, commit advances from page 0 to page 1.
        assertFalse(state.justTurned())
        state.commit(answered, submitting = false) { submitted += it }
        assertEquals(1, state.page)
        assertTrue(state.justTurned())

        // 499 ms after turning: commit is blocked so a double-tap cannot accidentally submit.
        now += ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS - 1
        assertTrue(state.justTurned())
        state.commit(answered, submitting = false) { submitted += it }
        assertEquals(emptyList(), submitted)

        // Back works immediately and starts a fresh settle window on page 0.
        state.back()
        assertEquals(0, state.page)
        assertTrue(state.justTurned())
        now += ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS - 1
        state.commit(answered, submitting = false) { submitted += it }
        assertEquals(0, state.page)

        // At >= 500 ms: advances to page 1; wait 500 ms again -> submits.
        now += 1
        assertFalse(state.justTurned())
        state.commit(answered, submitting = false) { submitted += it }
        assertEquals(1, state.page)
        now += ACTION_CARD_FORM_PAGE_TURN_SETTLE_MS
        state.commit(answered, submitting = false) { submitted += it }
        assertEquals(listOf(answered), submitted)
    }

    @Test
    fun pendingChoiceAlsoValidatesOtherFieldsOnTheSamePage() {
        val requiredText = ActionCardSpecTextInputField(name = "reason", label = msg("Reason"), required = true, maxLength = 50)
        val mixedForm = form(choice("one"), requiredText)
        val state =
            ActionCardFormSheetState(
                form = mixedForm,
                serverIssues = emptyList(),
                clientIssuesState = mutableStateOf(emptyList()),
                pageState = mutableStateOf(0),
                triedPagesState = mutableStateOf(emptySet()),
            )
        val emptyValues = initialActionCardFormValues(mixedForm)
        state.commit(emptyValues, submitting = false) {}
        assertTrue(state.pendingShown)
        assertNull(state.issueFor("one"), "empty required Choice speaks through pending hint, not client issue")
        assertEquals(ActionCardFormIssueCode.Required, state.issueFor("reason")?.code)
    }
}
