package build.raft.app

import androidx.compose.ui.text.TextRange
import build.raft.app.data.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure parts of the question-card form sheet (schemaVersion 8): pages, what a Choice still lacks, Enter in "Other". */
class ActionCardQuestionFormTest {
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
    fun aStepNamingNoDrawnFieldIsSkippedAndFewerThanTwoPagesIsOnePage() {
        val skipped = actionCardFormPages(form(choice("one"), notes, steps = listOf(ActionCardSpecFormStep(null, listOf("gone")), ActionCardSpecFormStep(null, listOf("one", "notes")))))
        assertEquals(1, skipped.size)
        assertEquals(listOf("one", "notes"), skipped.single().fields.map { it.name })
        // Without a title: a lone remaining step is the whole form, as a form without steps.
        assertNull(skipped.single().title)
    }

    @Test
    fun initialValuesAreEmptyChoicesOrTheAgentsDefault() {
        val prefilled = ActionCardFormValue.Choice(listOf("b"), "x")
        val values = initialActionCardFormValues(form(choice("plain"), choice("prefilled", multiple = true, default = prefilled)))
        assertEquals(ActionCardFormValue.Choice(emptyList()), values["plain"])
        assertEquals(prefilled, values["prefilled"])
    }

    @Test
    fun aRequiredChoiceLacksAnOptionOrTheOtherText() {
        assertEquals(ActionCardChoicePending.Choose, actionCardChoicePending(ActionCardFormValue.Choice(emptyList())))
        assertEquals(ActionCardChoicePending.Write, actionCardChoicePending(ActionCardFormValue.Choice(emptyList(), "")))
        assertEquals(ActionCardChoicePending.Write, actionCardChoicePending(ActionCardFormValue.Choice(listOf("a"), "  ")))
        assertNull(actionCardChoicePending(ActionCardFormValue.Choice(listOf("a"))))
        assertNull(actionCardChoicePending(ActionCardFormValue.Choice(emptyList(), "mine")))
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
}
