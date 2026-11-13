package build.raft.app

import build.raft.app.data.ActionCardSpecDisplayElement
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardQuestion
import build.raft.app.data.questionLabelColumns
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
    fun aFormGoneCardMeasuresItsStoredLabelsInColumnsAsTheServerDoes() {
        // Columns as questionAskPlacement counts them: CJK and fullwidth characters are 2.
        assertEquals(32, questionLabelColumns("x".repeat(32)))
        assertEquals(34, questionLabelColumns("部".repeat(17)))
        assertEquals(32, questionLabelColumns("部".repeat(16)))
        val stored = { label: String, state: String ->
            card(state = state, questions = listOf(ThreadActionCardQuestion("single", options = 3, widestOptionColumns = questionLabelColumns(label))))
                .questionCardLayout(spec(ActionCardSpecFixtures.questionCardAnsweredJson()))
        }
        for (state in listOf("executed", "expired", "withdrawn")) {
            assertEquals(QuestionCardLayout.ButtonRow, stored("x".repeat(32), state), "32 ASCII, $state")
            assertEquals(QuestionCardLayout.SlimCard, stored("x".repeat(33), state), "33 ASCII, $state")
            assertEquals(QuestionCardLayout.SlimCard, stored("部".repeat(17), state), "17 CJK = 34 columns, $state")
            assertEquals(QuestionCardLayout.ButtonRow, stored("部".repeat(16), state), "16 CJK = 32 columns, $state")
        }
        // A paged action is never inline, however short.
        val paged = card(state = "executed", questions = listOf(ThreadActionCardQuestion("single", 2, widestOptionColumns = 1))).copy(questionHasPages = true)
        assertEquals(QuestionCardLayout.SlimCard, paged.questionCardLayout(spec(ActionCardSpecFixtures.questionCardAnsweredJson())))
        assertEquals(QuestionCardLayout.SlimCard, paged.copy(state = "expired").questionCardLayout(spec(ActionCardSpecFixtures.questionCardAnsweredJson())))
    }

    @Test
    fun anOpenCardTrustsPlacementAndNeverReMeasuresItsLabels() {
        // `placement: "inline"` is the server's call (no pages, labels within 32 columns): not measured again.
        val long = "An option label far longer than thirty-two characters"
        assertEquals(QuestionCardLayout.ButtonRow, card().questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson(options = listOf(long, "B")))))
        assertEquals(QuestionCardLayout.ButtonRow, card().questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson(options = listOf("部".repeat(17), "B")))))
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
        // A card waiting on Confirm again is B.
        assertEquals(
            QuestionCardLayout.SlimCard,
            card(state = "reconfirm_required", questions = listOf(ThreadActionCardQuestion("single", 3, widestOptionColumns = 10)))
                .questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson())),
        )
    }

    @Test
    fun expiredAndWithdrawnCardsKeepTheShapeTheyHadWhenAskedFromTheStoredQuestions() {
        val short = listOf(ThreadActionCardQuestion("single", 3, widestOptionColumns = 26))
        for (state in listOf("expired", "withdrawn")) {
            // The stored questions decide, as for an answered card, whatever the spec still holds.
            assertEquals(QuestionCardLayout.ButtonRow, card(state, short).questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson())), state)
            assertEquals(QuestionCardLayout.ButtonRow, card(state, short).questionCardLayout(spec(ActionCardSpecFixtures.questionCardAnsweredJson())), state)
            assertEquals(QuestionCardLayout.SlimCard, card(state, listOf(ThreadActionCardQuestion("single", 5, widestOptionColumns = 10))).questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson())), state)
            assertEquals(QuestionCardLayout.SlimCard, card(state, listOf(ThreadActionCardQuestion("single", 3, widestOptionColumns = 33))).questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson())), state)
            assertEquals(QuestionCardLayout.SlimCard, card(state, listOf(ThreadActionCardQuestion("multiple", 3, widestOptionColumns = 10))).questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson())), state)
            assertEquals(QuestionCardLayout.SlimCard, card(state, short + short).questionCardLayout(ActionCardSpecFixtures.card("question_card")), state)
            assertEquals(QuestionCardLayout.SlimCard, card(state).questionCardLayout(spec(ActionCardSpecFixtures.questionCardJson())), "$state without stored questions")
        }
    }

    @Test
    fun theWaitIsSaidInItsLargestWholeUnit() {
        assertEquals(QuestionWait.Days(3), questionWait(3 * 1_440))
        assertEquals(QuestionWait.Days(7), questionWait(null), "an older card waited the default 7 days")
        assertEquals(QuestionWait.Days(7), questionWait(0))
        assertEquals(QuestionWait.Hours(12), questionWait(720))
        assertEquals(QuestionWait.Hours(36), questionWait(36 * 60))
        assertEquals(QuestionWait.Minutes(90), questionWait(90))
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
                       "expiresInMinutes":4320,"expiresAt":"2026-11-16T10:03:00.000Z"}}
                    """.trimIndent(),
                ).toThreadActionCard(),
            )
        assertEquals("u-maya", user.questionAddresseeUserId)
        assertEquals("Maya", user.questionAddresseeHandle)
        assertFalse(user.questionAsksAnyone)
        assertEquals(listOf(ThreadActionCardQuestion("single", 2, widestOptionColumns = 1)), user.questions)
        assertEquals("2026-11-16T10:03:00.000Z", user.expiresAt)
        assertEquals(4_320, user.questionExpiresInMinutes)
        assertFalse(user.questionHasPages)
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

        val paged =
            assertNotNull(
                json.decodeFromString(
                    WireActionCardMetadata.serializer(),
                    """{"kind":"action-card","action":{"type":"question:ask","questions":[{"id":"a","kind":"single","text":"A?","options":[{"value":"x","label":"部署"}]}],"pages":[["a"]]}}""",
                ).toThreadActionCard(),
            )
        assertTrue(paged.questionHasPages)
        assertEquals(listOf(ThreadActionCardQuestion("single", 1, widestOptionColumns = 4)), paged.questions)
        assertEquals(paged, actionCardFromMessageMetadata(paged.toMessageMetadata()))
    }

    @Test
    fun aRenderedQuestionCardKeepsItsFormForTheButtonRow() {
        val card = ThreadActionCard(type = "question:ask", presentationCardJson = ActionCardSpecFixtures.questionCardJson())
        val spec = assertNotNull(card.actionCardSpecToRender(capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
        assertEquals(QuestionCardLayout.ButtonRow, card.questionCardLayout(spec))
    }
}
