package build.raft.app

import build.raft.app.data.ActionCardSpecDisplayElement
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardQuestion
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadActionCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The question-card redesign's rules (Jasper, 2026-11): which cards are C (a button row) and which
 * B (a slim card), who is asked, how much time is left, and what the card reads off the wire.
 */
class ActionCardQuestionCardTest {
    private fun card(state: String = "prepared", questions: List<ThreadActionCardQuestion> = emptyList()) =
        ThreadActionCard(type = "question:ask", state = state, questions = questions)

    private fun spec(json: String) = ActionCardSpecFixtures.cardFromJson(json)

    @Test
    fun oneInlineSingleChoiceWithFourOptionsAtMostIsTheButtonRow() {
        assertEquals(QuestionCardLayout.ButtonRow, card().questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson())))
        assertEquals(
            QuestionCardLayout.ButtonRow,
            card().questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson(options = listOf("A", "B", "C", "D")))),
        )
    }

    @Test
    fun anOptionOverThirtyTwoCharactersMakesItTheSlimCard() {
        val thirtyTwo = "x".repeat(32)
        assertEquals(
            QuestionCardLayout.ButtonRow,
            card().questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson(options = listOf("A", thirtyTwo)))),
        )
        assertEquals(
            QuestionCardLayout.SlimCard,
            card().questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson(options = listOf("A", thirtyTwo + "x")))),
        )
        // Characters, not UTF-16 units: 32 emoji are 32 characters.
        assertEquals(32, build.raft.app.data.codePointLength("😀".repeat(32)))
        // Answered: the stored question's longest label decides.
        val answered = { longest: Int ->
            card(state = "executed", questions = listOf(ThreadActionCardQuestion("single", options = 3, longestOption = longest)))
                .questionCardLayout(ActionCardSpecFixtures.card("question_card"))
        }
        assertEquals(QuestionCardLayout.ButtonRow, answered(32))
        assertEquals(QuestionCardLayout.SlimCard, answered(33))
    }

    @Test
    fun everyOtherQuestionCardIsTheSlimCard() {
        // Five options, a multiple choice, the sheet's placement, several questions.
        assertEquals(
            QuestionCardLayout.SlimCard,
            card().questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson(options = listOf("A", "B", "C", "D", "E")))),
        )
        assertEquals(QuestionCardLayout.SlimCard, card().questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson(multiple = true))))
        assertEquals(QuestionCardLayout.SlimCard, card().questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson(inline = false))))
        assertEquals(QuestionCardLayout.SlimCard, card().questionCardLayout(ActionCardSpecFixtures.card("question_card")))
        // Ended cards keep the slim card's existing Expired / Withdrawn states.
        assertEquals(QuestionCardLayout.SlimCard, card(state = "expired").questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson())))
        assertEquals(QuestionCardLayout.SlimCard, card(state = "withdrawn").questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson())))
    }

    @Test
    fun theClientNeverMeasuresLabelsItTrustsPlacement() {
        val long = "An option label far longer than thirty-two characters"
        assertEquals(QuestionCardLayout.ButtonRow, card().questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson(options = listOf(long, "B")))))
    }

    @Test
    fun anAnsweredCardHasNoFormSoTheStoredQuestionsDecide() {
        val answered = spec(ActionCardSpecFixtures.questionCardAnsweredJson())
        assertEquals(QuestionCardLayout.ButtonRow, card("executed", listOf(ThreadActionCardQuestion("single", 3))).questionCardLayout(answered))
        assertEquals(QuestionCardLayout.SlimCard, card("executed", listOf(ThreadActionCardQuestion("single", 5))).questionCardLayout(answered))
        assertEquals(QuestionCardLayout.SlimCard, card("executed", listOf(ThreadActionCardQuestion("multiple", 3))).questionCardLayout(answered))
        assertEquals(QuestionCardLayout.SlimCard, card("executed").questionCardLayout(answered))
    }

    @Test
    fun otherCardsHaveNoQuestionLayout() {
        assertNull(ThreadActionCard(type = "channel:create").questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson())))
    }

    @Test
    fun theAddresseeIsAskedAnyoneMeansEveryoneAndNoAddresseeLeavesItToTheServer() {
        val maya = ThreadActionCard(type = "question:ask", questionAddresseeUserId = "U-MAYA")
        assertTrue(maya.questionAsksViewer("u-maya"))
        assertFalse(maya.questionAsksViewer("u-ada"))
        assertFalse(maya.questionAsksViewer(null))
        assertTrue(ThreadActionCard(type = "question:ask", questionAsksAnyone = true).questionAsksViewer("u-ada"))
        assertTrue(ThreadActionCard(type = "question:ask").questionAsksViewer("u-ada"))
    }

    @Test
    fun timeLeftCountsDaysThenHoursThenLessThanAnHour() {
        val now = 1_800_000_000_000L
        val hour = 3_600_000L
        val day = 24 * hour
        assertEquals(QuestionTimeLeft.Days(7), questionTimeLeft(now + 7 * day - 5 * 60_000, now), "a 7-day card just asked")
        assertEquals(QuestionTimeLeft.Days(2), questionTimeLeft(now + day + 13 * hour, now))
        assertEquals(QuestionTimeLeft.Days(1), questionTimeLeft(now + day + 1, now))
        assertEquals(QuestionTimeLeft.Hours(24), questionTimeLeft(now + day, now))
        assertEquals(QuestionTimeLeft.Hours(23), questionTimeLeft(now + day - 1, now))
        assertEquals(QuestionTimeLeft.Hours(1), questionTimeLeft(now + hour, now))
        assertEquals(QuestionTimeLeft.UnderAnHour, questionTimeLeft(now + hour - 1, now))
        assertEquals(QuestionTimeLeft.UnderAnHour, questionTimeLeft(now + 1, now))
        assertNull(questionTimeLeft(now, now), "expired: the existing expired state")
        assertNull(questionTimeLeft(now - day, now))
        assertNull(questionTimeLeft(null, now))
    }

    @Test
    fun theHintJoinsTheTimeLeftOnlyWhenThereIsOne() {
        assertEquals("Fixer is asking you · 7 days left", questionLineWithTimeLeft("Fixer is asking you", "7 days left"))
        assertEquals("Fixer is asking you", questionLineWithTimeLeft("Fixer is asking you", null))
    }

    @Test
    fun theSlimCardDropsTheForRowAndASingleQuestionsRow() {
        val tree = spec(ActionCardSpecFixtures.questionCardJson(inline = false)).spec
        val single = tree.withoutSlimQuestionRows(singleQuestion = true)
        assertNull(single.elements["for"])
        assertNull(single.elements["q_how"])
        assertNull(single.elements["properties"], "the Stack left empty goes too")
        assertNotNull(single.elements["answer"], "the sheet's button stays")
        val several = ActionCardSpecFixtures.card("question_card").spec.withoutSlimQuestionRows(singleQuestion = false)
        assertNull(several.elements["for"])
        assertTrue(several.elements["q_window"] is ActionCardSpecDisplayElement.Field, "several questions keep their numbered rows")
    }

    @Test
    fun theQuestionAndTheAnswerAreReadFromTheFormOrTheRecord() {
        val open = spec(ActionCardSpecFixtures.questionCardJson())
        assertEquals("How should I go about them?", open.singleQuestionText("en"))
        assertNull(open.questionAnswerText("en"))
        assertEquals("fixer", open.questionAskerRef()?.fallback)
        val answered = spec(ActionCardSpecFixtures.questionCardAnsweredJson())
        assertEquals("How should I go about them?", answered.singleQuestionText("en"))
        assertEquals("Fix all 4 on a branch", answered.questionAnswerText("en"))
        assertNull(ActionCardSpecFixtures.card("question_card").singleQuestionText("en"))
    }

    @Test
    fun theWireAddresseeAndQuestionsAreReadAndSurviveTheMessageMetadata() {
        val json = Json { ignoreUnknownKeys = true }
        val user =
            assertNotNull(
                json.decodeFromString(
                    WireActionCardMetadata.serializer(),
                    """
                    {"kind":"action-card","state":"prepared","expiresAt":"2026-11-16T10:03:00.000Z",
                     "action":{"type":"question:ask","to":"maya","addressee":{"kind":"user","userId":"u-maya","handle":"Maya"},
                       "questions":[{"id":"how","kind":"single","text":"How?","options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]}],
                       "expiresAt":"2026-11-16T10:03:00.000Z"}}
                    """.trimIndent(),
                ).toThreadActionCard(),
            )
        assertEquals("u-maya", user.questionAddresseeUserId)
        assertEquals("Maya", user.questionAddresseeHandle)
        assertFalse(user.questionAsksAnyone)
        assertEquals(listOf(ThreadActionCardQuestion("single", 2, longestOption = 1)), user.questions)
        assertEquals("2026-11-16T10:03:00.000Z", user.expiresAt)
        assertEquals(user, actionCardFromMessageMetadata(user.toMessageMetadata()))

        val anyone =
            assertNotNull(
                json.decodeFromString(
                    WireActionCardMetadata.serializer(),
                    """{"kind":"action-card","action":{"type":"question:ask","addressee":{"kind":"anyone"},"questions":[{"id":"n","kind":"text","text":"Notes?"}]}}""",
                ).toThreadActionCard(),
            )
        assertTrue(anyone.questionAsksAnyone)
        assertNull(anyone.questionAddresseeUserId)
        assertEquals(listOf(ThreadActionCardQuestion("text", 0)), anyone.questions)
        assertEquals(anyone, actionCardFromMessageMetadata(anyone.toMessageMetadata()))
    }

    @Test
    fun aRenderedQuestionCardKeepsItsFormForTheButtonRow() {
        val card = ThreadActionCard(type = "question:ask", presentationCardJson = ActionCardSpecFixtures.questionCardJson())
        val spec = assertNotNull(card.actionCardSpecToRender(specEnabled = true, capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
        assertEquals(QuestionCardLayout.ButtonRow, card.questionCardLayout(spec))
    }
}
