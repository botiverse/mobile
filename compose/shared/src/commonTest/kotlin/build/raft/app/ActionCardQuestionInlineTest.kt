package build.raft.app

import build.raft.app.data.ActionCardExecuteOutcome
import build.raft.app.data.ActionCardFormIssueCode
import build.raft.app.data.ActionCardFormValue
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecCard
import build.raft.app.data.ActionCardSpecChoiceField
import build.raft.app.data.CardStack
import build.raft.app.data.DataResult
import build.raft.app.data.ExecuteActionFormBody
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.actionCardFormIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Question cards answered on the card (botiverse/slock staging, question-card spec §5.9 / §5.10,
 * web `inlineQuestionForm`, `tapSubmitsForm`, `useUndoableSubmit`): which cards the server placed
 * inline, what the body keeps, and the 1.5 s Undo before an answer is sent once.
 */
class ActionCardQuestionInlineTest {
    private fun raw(text: String) = """{"key":"actionCard.text.raw","text":{"en":"{t}"},"params":{"t":"$text"}}"""

    private fun choice(name: String, multiple: Boolean = false) =
        """{"type":"Choice","name":"$name","label":${raw("Which $name?")},"required":true,"multiple":$multiple,
            "options":[{"value":"postgres","label":${raw("Postgres")}},{"value":"sqlite","label":${raw("SQLite")}}],
            "other":{"label":{"key":"actionCard.question.other","text":{"en":"Other…"}},"maxLength":500}}"""

    private val text = """{"type":"TextArea","name":"notes","label":${raw("Anything else?")},"required":true,"maxLength":1000}"""

    /** A question card as the server builds it: For, a `q_<id>` row per question, one Answer button opening `answers`. */
    private fun questionCardJson(placement: String? = "inline", fields: List<Pair<String, String>> = listOf("db" to choice("db"))): String {
        val rows = fields.joinToString(",") { (name, _) -> """"q_$name":{"type":"Field","props":{"label":{"key":"actionCard.question.question","text":{"en":"Question"}},"value":"Which $name?"}}""" }
        val placementJson = placement?.let { ""","placement":"$it"""" }.orEmpty()
        return """
            {"catalog":"action-card.v1","icon":"message","title":{"key":"actionCard.question.title.one","text":{"en":"Answer Question"}},
             "spec":{"root":"root","elements":{
               "root":{"type":"Stack","props":{},"children":["properties","actions"]},
               "properties":{"type":"Stack","props":{},"children":["for",${fields.joinToString(",") { "\"q_${it.first}\"" }}]},
               "for":{"type":"Field","props":{"label":{"key":"actionCard.question.for","text":{"en":"For"}},"value":{"key":"actionCard.question.anyone","text":{"en":"Anyone in this channel"}}}},
               $rows,
               "actions":{"type":"Stack","props":{"direction":"row","wrap":true,"role":"actions"},"children":["answer"]},
               "answer":{"type":"Button","props":{"label":{"key":"actionCard.question.answer.one","text":{"en":"Answer Question"}},"emphasis":"primary","action":{"kind":"open_form","form":"answers"}}}}},
             "forms":{"answers":{"title":{"key":"actionCard.question.form.title.one","text":{"en":"Answer Question"}},
               "submitLabel":{"key":"actionCard.question.form.submit","text":{"en":"Answer"}}$placementJson,
               "fields":[${fields.joinToString(",") { it.second }}]}}}
        """.trimIndent()
    }

    private fun card(state: String = "prepared", type: String = "question:ask", json: String = questionCardJson()) =
        ThreadActionCard(type = type, state = state, confirmationVersion = 3, presentationCardJson = json)

    private fun ThreadActionCard.spec(): ActionCardSpecCard = assertNotNull(actionCardSpecToRender(specEnabled = true, capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))

    private fun ThreadActionCard.inline() = inlineQuestionForm(spec())

    @Test
    fun onlyAFormTheServerPlacedInlineWithOnlyChoicesIsAnsweredOnTheCard() {
        val inline = assertNotNull(card().inline())
        assertEquals(Triple("answers", "answer", listOf("db")), Triple(inline.formId, inline.buttonId, inline.fields.map { it.name }))
        assertEquals("Answer Question", inline.buttonLabel.text.getValue("en"))
        // Three short choice questions, single or multiple: still inline.
        assertNotNull(card(json = questionCardJson(fields = listOf("a" to choice("a"), "b" to choice("b", multiple = true), "c" to choice("c")))).inline())

        // Today's form: no placement, or `sheet`, keeps the Answer button and the sheet.
        assertNull(card(json = questionCardJson(placement = null)).inline(), "no placement")
        assertNull(card(json = questionCardJson(placement = "sheet")).inline(), "sheet")
        // An unknown placement reads as absent (the sheet).
        assertNull(card(json = questionCardJson(placement = "popover")).inline(), "unknown placement")
        // A text question is never answered inline, even if a card said so.
        assertNull(card(json = questionCardJson(fields = listOf("db" to choice("db"), "notes" to text))).inline(), "text question")
        // Only question cards.
        assertNull(card(type = "channel:create").inline(), "another type")
    }

    @Test
    fun theFormIsGoneOnceTheCardIsAnsweredEndedOrWaitsOnConfirmAgainButAFrozenCardKeepsIt() {
        for (state in listOf("executed", "expired", "withdrawn", "reconfirm_required")) {
            assertNull(card(state = state).inline(), state)
        }
        // Frozen: drawn with its options disabled (canSubmit false), not taken away.
        assertNotNull(card(state = "frozen").inline())
    }

    @Test
    fun theBodyDropsTheButtonAndTheQuestionRowsTheFormDrawsAndKeepsTheForRow() {
        val spec = card().spec()
        val inline = assertNotNull(spec.inlineQuestionForm())
        val body = spec.spec.withoutInlineQuestions(inline)
        assertNull(body.element("answer"))
        assertNull(body.element("q_db"))
        assertNotNull(body.element("for"))
        assertEquals(listOf("for"), (body.element("properties") as CardStack).children)
        // The actions row held only the button: gone with it.
        assertNull(body.element("actions"))
        assertEquals(listOf("properties"), (body.element(body.root) as CardStack).children)
        assertTrue(body.elements.values.none { it is ActionCardSpecButton })
    }

    @Test
    fun aTapAnswersTheFormOnlyForALoneSingleChoiceWithoutOther() {
        val single = assertNotNull(card().inline()).fields
        assertTrue(actionCardTapSubmitsForm(single, emptyMap()))
        assertTrue(actionCardTapSubmitsForm(single, mapOf("db" to ActionCardFormValue.Choice(listOf("postgres")))))
        // Other… chosen (even empty): the typed answer needs the button.
        assertFalse(actionCardTapSubmitsForm(single, mapOf("db" to ActionCardFormValue.Choice(emptyList(), other = ""))))
        val multiple = assertNotNull(card(json = questionCardJson(fields = listOf("db" to choice("db", multiple = true)))).inline()).fields
        assertFalse(actionCardTapSubmitsForm(multiple, emptyMap()))
        val two = assertNotNull(card(json = questionCardJson(fields = listOf("a" to choice("a"), "b" to choice("b")))).inline()).fields
        assertFalse(actionCardTapSubmitsForm(two, emptyMap()))
    }

    @Test
    fun anAnswerWaitsOneAndAHalfSecondsThenIsSentOnce() = runTest {
        assertEquals(1_500L, QUESTION_SUBMIT_UNDO_MS)
        val sent = mutableListOf<String>()
        val undoable = QuestionUndoableSubmit<String>(backgroundScope) { sent += it }
        undoable.start("postgres")
        assertTrue(undoable.waiting)
        advanceTimeBy(1_499)
        runCurrent()
        assertEquals(emptyList(), sent, "still in the Undo window")
        // A second submit while one waits sends nothing more.
        undoable.start("sqlite")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("postgres"), sent)
        assertFalse(undoable.waiting)
        // Nothing left to send: a flush or a later tick sends nothing again.
        undoable.flush()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("postgres"), sent)
    }

    @Test
    fun undoTakesTheAnswerBackAndNothingIsSent() = runTest {
        val sent = mutableListOf<String>()
        val undoable = QuestionUndoableSubmit<String>(backgroundScope) { sent += it }
        undoable.start("postgres")
        advanceTimeBy(1_000)
        undoable.undo()
        assertFalse(undoable.waiting)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(emptyList(), sent)
        // Answering again starts a new wait, sent once at its end.
        undoable.start("sqlite")
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(listOf("sqlite"), sent)
    }

    @Test
    fun leavingSendsTheWaitingAnswerAtOnceAndOnlyOnce() = runTest {
        val sent = mutableListOf<String>()
        val undoable = QuestionUndoableSubmit<String>(backgroundScope) { sent += it }
        undoable.start("postgres")
        advanceTimeBy(200)
        undoable.flush()
        assertEquals(listOf("postgres"), sent)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("postgres"), sent)
    }

    private class Harness(scope: kotlinx.coroutines.test.TestScope, vararg answers: DataResult<ActionCardExecuteOutcome>) {
        val calls = mutableListOf<Pair<String, ExecuteActionFormBody?>>()
        val applied = mutableListOf<ThreadActionCard>()
        var approvals = 0
        private val queue = ArrayDeque(answers.toList())
        val store =
            ThreadActionCardSpecStore(
                scope = scope.backgroundScope,
                execute = { messageId, _, form ->
                    calls += messageId to form
                    queue.removeFirst()
                },
                applyCard = { _, card -> applied += card },
                onApproved = { approvals++ },
            )
    }

    private val postgres = mapOf<String, ActionCardFormValue>("db" to ActionCardFormValue.Choice(listOf("postgres")))

    @Test
    fun theStoreSendsAnInlineAnswerWithItsFormOnlyWhileTheCardCanBeAnswered() = runTest {
        val answered = card(state = "executed")
        val harness = Harness(this, DataResult.Content(ActionCardExecuteOutcome.Executed(answered)))
        harness.store.dispatch(ThreadActionCardSpecIntent.InlineValuesChanged("m-q", postgres))
        assertEquals(postgres, harness.store.state.value.slice("m-q").inlineValues)
        harness.store.dispatch(ThreadActionCardSpecIntent.SubmitInlineForm("m-q", card(), "answers", "answer", postgres))
        runCurrent()
        assertEquals(listOf<Pair<String, ExecuteActionFormBody?>>("m-q" to ExecuteActionFormBody("answers", postgres)), harness.calls)
        assertEquals(listOf(answered), harness.applied)
        assertEquals(1, harness.approvals, "an answered question card is an approval (rating prompt)")
        assertNull(harness.store.state.value.slice("m-q").inlineValues)

        // Frozen while the answer waited: nothing is sent; the card says so and keeps the choices.
        val frozen = Harness(this)
        frozen.store.dispatch(ThreadActionCardSpecIntent.InlineValuesChanged("m-q", postgres))
        frozen.store.dispatch(ThreadActionCardSpecIntent.SubmitInlineForm("m-q", card(state = "frozen"), "answers", "answer", postgres))
        runCurrent()
        assertEquals(emptyList(), frozen.calls)
        assertEquals(0, frozen.approvals)
        assertEquals(ThreadActionCardSpecError.QuestionNotSent, frozen.store.state.value.slice("m-q").error)
        assertEquals(postgres, frozen.store.state.value.slice("m-q").inlineValues)

        // Answered elsewhere or withdrawn meanwhile: nothing is sent and nothing is said (the card shows it).
        for (state in listOf("executed", "withdrawn", "expired")) {
            val ended = Harness(this)
            ended.store.dispatch(ThreadActionCardSpecIntent.SubmitInlineForm("m-q", card(state = state), "answers", "answer", postgres))
            runCurrent()
            assertEquals(emptyList(), ended.calls, state)
            assertNull(ended.store.state.value.slice("m-q").error, state)
        }
    }

    @Test
    fun aRefusedInlineAnswerPutsTheIssueAndItsTraceIdUnderItsQuestionUntilItIsEdited() = runTest {
        val issue = actionCardFormIssue("db", ActionCardFormIssueCode.InvalidOption)
        val harness = Harness(this, DataResult.Content(ActionCardExecuteOutcome.FormInvalid(listOf(issue), null, TRACE_ID)))
        harness.store.dispatch(ThreadActionCardSpecIntent.SubmitInlineForm("m-q", card(), "answers", "answer", postgres))
        runCurrent()
        assertEquals(listOf(issue), harness.store.state.value.slice("m-q").inlineIssues)
        assertEquals(TRACE_ID, harness.store.state.value.slice("m-q").inlineIssuesTraceId, "the 422's trace id goes with its issues")
        assertNull(harness.store.state.value.slice("m-q").busy)
        harness.store.dispatch(ThreadActionCardSpecIntent.InlineValuesChanged("m-q", mapOf("db" to ActionCardFormValue.Choice(listOf("sqlite")))))
        assertEquals(emptyList(), harness.store.state.value.slice("m-q").inlineIssues)
        assertNull(harness.store.state.value.slice("m-q").inlineIssuesTraceId, "no issue left, no trace id")
    }

    /** slock #14497: a question of one option comes as a single Choice of that option without Other… (shared `questionField`). */
    private fun confirmField(required: Boolean = true, multiple: Boolean = false, other: Boolean = false): ActionCardSpecChoiceField {
        val otherJson = if (other) ""","other":{"label":{"key":"actionCard.question.other","text":{"en":"Other…"}},"maxLength":500}""" else ""
        val field = """{"type":"Choice","name":"ack","label":${raw("Read the runbook?")},"required":$required,"multiple":$multiple,"options":[{"value":"ok","label":${raw("Got it")}}]$otherJson}"""
        return assertNotNull(card(json = questionCardJson(fields = listOf("ack" to field))).spec().inlineQuestionForm()).fields.single()
    }

    @Test
    fun aChoiceOfOneOptionWithoutOtherIsAConfirm() {
        assertTrue(confirmField().isConfirm)
        assertTrue(actionCardLoneConfirm(listOf(confirmField())))
        // web isConfirmChoice: a multiple one, or one with Other…, is drawn as before.
        assertFalse(confirmField(multiple = true).isConfirm)
        assertFalse(confirmField(other = true).isConfirm)
        assertFalse(actionCardLoneConfirm(listOf(confirmField(), confirmField())))
    }

    @Test
    fun pressingAConfirmChoosesItAndPressingAnOptionalOneAgainTakesItBack() {
        val none = ActionCardFormValue.Choice(emptyList())
        val chosen = ActionCardFormValue.Choice(listOf("ok"))
        assertEquals(chosen, confirmField().confirmPressed(none))
        assertNull(confirmField().confirmPressed(chosen), "a required one stays chosen")
        assertEquals(none, confirmField(required = false).confirmPressed(chosen))
    }

    @Test
    fun aRefusedFormsTraceIdShowsOnceUnderItsBannersElseUnderTheFirstIssue() {
        val shown = setOf("b", "c")
        assertEquals(ActionCardTraceIdSpot.UnderBanners, actionCardTraceIdSpot(TRACE_ID, bannerShown = true, listOf("a", "b", "c")) { it in shown })
        assertEquals(ActionCardTraceIdSpot.UnderField("b"), actionCardTraceIdSpot(TRACE_ID, bannerShown = false, listOf("a", "b", "c")) { it in shown })
        // Every issue edited away, or no (valid) trace id: no note.
        assertNull(actionCardTraceIdSpot(TRACE_ID, bannerShown = false, listOf("a", "b", "c")) { false })
        assertNull(actionCardTraceIdSpot(null, bannerShown = true, listOf("a")) { true })
        assertNull(actionCardTraceIdSpot("not-a-trace", bannerShown = true, listOf("a")) { true })
    }

    private companion object {
        const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
    }
}
