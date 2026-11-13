package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import build.raft.app.data.ActionCardExecuteOutcome
import build.raft.app.data.ActionCardFormIssueCode
import build.raft.app.data.ActionCardFormValue
import build.raft.app.data.DataResult
import build.raft.app.data.ExecuteActionFormBody
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardQuestion
import build.raft.app.data.actionCardFormIssue
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A question card the server placed on the card (`placement: "inline"`, question-card spec §5.9)
 * answered in the thread. One short single choice is the redesign's C, a row of buttons: a tap
 * says "Submitted · Undo" for 1.5 s (§5.10) and sends nothing; Undo sends nothing; another tap is
 * sent once, with the form, when the wait is over. A viewer who isn't asked sees the buttons
 * disabled and who answers. Every other question card is B, a slim card without icon or
 * "Answer Question" title: the question is the title, "For" is the line under it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadActionCardQuestionInlineUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    // The web's numbers, written out (questionAdvance.ts, questionSubmitUndo.ts after slock #13455).
    private val PICK_MS = 150L
    private val UNDO_MS = 1_500L

    private fun raw(text: String) = """{"key":"actionCard.text.raw","text":{"en":"{t}"},"params":{"t":"$text"}}"""

    /** One single-choice question with Other…, as questionAskPlacement places it (the vector's question_card_single_anyone, inline). */
    private val cardJson =
        """
        {"catalog":"action-card.v1","icon":"message","title":${raw("Scout asks")},
         "spec":{"root":"root","elements":{
           "root":{"type":"Stack","props":{},"children":["properties","actions"]},
           "properties":{"type":"Stack","props":{},"children":["for","q_db"]},
           "for":{"type":"Field","props":{"label":{"key":"actionCard.question.for","text":{"en":"For"}},"value":{"key":"actionCard.question.anyone","text":{"en":"Anyone in this channel"}}}},
           "q_db":{"type":"Field","props":{"label":{"key":"actionCard.question.question","text":{"en":"Question"}},"value":"Which database should the billing service use?"}},
           "actions":{"type":"Stack","props":{"direction":"row","wrap":true,"role":"actions"},"children":["answer"]},
           "answer":{"type":"Button","props":{"label":{"key":"actionCard.question.answer.one","text":{"en":"Answer Question"}},"emphasis":"primary","action":{"kind":"open_form","form":"answers"}}}}},
         "forms":{"answers":{"title":{"key":"actionCard.question.form.title.one","text":{"en":"Answer Question"}},
           "submitLabel":{"key":"actionCard.question.form.submit","text":{"en":"Answer"}},"placement":"inline",
           "fields":[{"type":"Choice","name":"db","label":${raw("Pick a database")},"required":true,"multiple":false,
             "options":[{"value":"postgres","label":${raw("Postgres")}},{"value":"sqlite","label":${raw("SQLite")}}],
             "other":{"label":{"key":"actionCard.question.other","text":{"en":"Other…"}},"maxLength":500}}]}}}
        """.trimIndent()

    /** With the clock held: let a write from outside composition recompose, then move the clock on. */
    private fun ComposeUiTest.advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeBy(millis)
        waitForIdle()
    }

    @Test
    fun aSingleChoiceTapWaitsForUndoAndIsSentOnceAfterOneAndAHalfSeconds() = runRobolectricComposeUiTest {
        val sent = mutableListOf<ExecuteActionFormBody?>()
        val pressed = mutableListOf<String>()
        val store =
            ThreadActionCardSpecStore(
                scope = CoroutineScope(Dispatchers.Unconfined),
                execute = { _, _, form ->
                    sent += form
                    DataResult.Content(ActionCardExecuteOutcome.Executed(null))
                },
                applyCard = { _, _ -> },
            )
        val card = ThreadActionCard(type = "question:ask", state = "prepared", confirmationVersion = 3, presentationCardJson = cardJson)
        val spec = assertNotNull(card.actionCardSpecToRender(capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalThreadActionCardSpecHost provides ThreadActionCardSpecHost(state = store.state, onIntent = store::dispatch)) {
                    ThreadActionCardSpecView(
                        messageId = "m-q",
                        card = card,
                        spec = spec,
                        commitEnabled = true,
                        typedPending = false,
                        executedTimeLabel = null,
                        onAction = { pressed += it },
                    )
                }
            }
        }
        advance(100)

        // In the card: the question over its options, no "Answer Question" button to a sheet.
        onNodeWithText("Pick a database", substring = true).assertExists()
        onNodeWithText("Answer Question").assertDoesNotExist()
        onNodeWithText("Postgres").performClick()
        advance(PICK_MS + 50)
        onNodeWithText("Undo").assertExists()
        onNodeWithText("Submitted", substring = true).assertExists()
        assertEquals(emptyList(), sent, "nothing is sent while Undo shows")

        // Undo: answering again, the choice kept, nothing sent however long it waits.
        onNodeWithText("Undo").performClick()
        advance(100)
        onNodeWithText("Undo").assertDoesNotExist()
        onNodeWithText("Postgres").assertIsEnabled()
        onNodeWithText("SQLite").assertIsEnabled()
        advance(UNDO_MS * 3)
        assertEquals(emptyList(), sent, "an undone answer is never sent")

        // Another tap: sent once the 1.5 s are over, with the form.
        onNodeWithText("SQLite").performClick()
        advance(PICK_MS + 50)
        onNodeWithText("Undo").assertExists()
        advance(UNDO_MS - 100)
        assertEquals(emptyList(), sent, "still in the Undo window")
        advance(200)
        assertEquals(listOf<ExecuteActionFormBody?>(ExecuteActionFormBody("answers", mapOf("db" to ActionCardFormValue.Choice(listOf("sqlite"))))), sent)
        advance(UNDO_MS * 3)
        assertEquals(1, sent.size, "sent once")
        assertEquals(emptyList(), pressed, "no button went to the sheet")
    }

    // 2026-11-09 10:03 UTC; the cards expire 7 days later.
    private val nowMillis = 1_794_218_580_000L
    private val expiresAt = "2026-11-16T10:03:00.000Z"
    private val names = ThreadActionCardNameLookup { kind, id -> if (kind == ThreadActionCardEntityKind.Human && id == "u-maya") "Maya" else null }

    private fun ComposeUiTest.showCard(card: ThreadActionCard, viewer: String, store: ThreadActionCardSpecStore? = null, sender: String = "Fixer") {
        val spec = assertNotNull(card.actionCardSpecToRender(capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
        val timeContext = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = raftEpochDayForDate(2026, 11, 9), nowEpochMillis = nowMillis)
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(
                    LocalThreadActionCardSpecHost provides
                        ThreadActionCardSpecHost(
                            state = store?.state ?: kotlinx.coroutines.flow.MutableStateFlow(ThreadActionCardSpecState()),
                            onIntent = store?.let { it::dispatch } ?: {},
                            viewerUserId = viewer,
                        ),
                    LocalThreadActionCardNames provides names,
                    LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
                ) {
                    ThreadActionCardSpecView(
                        messageId = "m-q",
                        card = card,
                        spec = spec,
                        commitEnabled = true,
                        typedPending = false,
                        executedTimeLabel = null,
                        askerName = sender,
                        onAction = {},
                    )
                }
            }
        }
    }

    private fun mayaCard(json: String = ActionCardSpecFixtures.questionCardJson(), state: String = "prepared", executedBy: String? = null) =
        ThreadActionCard(
            type = "question:ask",
            state = state,
            confirmationVersion = 3,
            presentationCardJson = json,
            expiresAt = expiresAt,
            questionAddresseeUserId = "u-maya",
            questionAddresseeHandle = "maya",
            questions = listOf(ThreadActionCardQuestion("single", 3)),
            executedByUserName = executedBy,
        )

    @Test
    fun theAskedPersonTapsAButtonAndItIsSentAfterTheUndoWindow() = runRobolectricComposeUiTest {
        val sent = mutableListOf<ExecuteActionFormBody?>()
        val store =
            ThreadActionCardSpecStore(
                scope = CoroutineScope(Dispatchers.Unconfined),
                execute = { _, _, form ->
                    sent += form
                    DataResult.Content(ActionCardExecuteOutcome.Executed(null))
                },
                applyCard = { _, _ -> },
            )
        mainClock.autoAdvance = false
        showCard(mayaCard(), viewer = "u-maya", store = store)
        advance(100)

        // C: the question, a button per option and Other (no "…"), the hint; no card title or "For".
        onNodeWithText("How should I go about them?").assertExists()
        onNodeWithText("Answer Question").assertDoesNotExist()
        onNodeWithText("For").assertDoesNotExist()
        onNodeWithText("Other").assertIsEnabled()
        onNodeWithText("Other…").assertDoesNotExist()
        onNodeWithText("Fixer is waiting for your answer · 7 days left").assertExists()

        onNodeWithText("One at a time, ask me each").assertIsEnabled().performClick()
        advance(PICK_MS + 50)
        onNodeWithText("Undo").assertExists()
        assertEquals(emptyList(), sent, "nothing is sent while Undo shows")
        advance(UNDO_MS + 100)
        assertEquals(listOf<ExecuteActionFormBody?>(ExecuteActionFormBody("answers", mapOf("how" to ActionCardFormValue.Choice(listOf("o1"))))), sent)
    }

    @Test
    fun theAskedPersonTapsOtherAndWritesTheirOwnAnswer() = runRobolectricComposeUiTest {
        val store =
            ThreadActionCardSpecStore(
                scope = CoroutineScope(Dispatchers.Unconfined),
                execute = { _, _, _ -> DataResult.Content(ActionCardExecuteOutcome.Executed(null)) },
                applyCard = { _, _ -> },
            )
        showCard(mayaCard(), viewer = "u-maya", store = store)
        onNodeWithText("Answer").assertDoesNotExist()
        onNodeWithText("Other").performClick()
        waitForIdle()
        // The one-line input for their own answer, and its own button to send it.
        onNode(hasSetTextAction()).assertExists()
        onNodeWithText("Answer").assertIsEnabled()
    }

    @Test
    fun anyoneElseSeesTheButtonsDisabledAndWhoIsAsked() = runRobolectricComposeUiTest {
        showCard(mayaCard(), viewer = "u-ada")
        onNodeWithText("Fix all 4 on a branch").assertIsNotEnabled()
        onNodeWithText("Just list them for me").assertIsNotEnabled()
        onNodeWithText("Other").assertIsNotEnabled()
        onNodeWithText("Waiting for Maya to answer").assertExists()
        onNodeWithText("Fixer is waiting for your answer", substring = true).assertDoesNotExist()
    }

    @Test
    fun anAnsweredButtonRowIsOneLine() = runRobolectricComposeUiTest {
        showCard(mayaCard(ActionCardSpecFixtures.questionCardAnsweredJson(), state = "executed", executedBy = "Maya"), viewer = "u-ada")
        onNodeWithText("How should I go about them?").assertExists()
        onNodeWithText("Maya answered Fix all 4 on a branch").assertExists()
        onNodeWithText("Other").assertDoesNotExist()
    }

    @Test
    fun theSlimCardHasNoHeaderTitleTheQuestionIsTheTitle() = runRobolectricComposeUiTest {
        // Five options: B, the options in the card.
        val json = ActionCardSpecFixtures.questionCardJson(options = listOf("Fix all 4 on a branch", "One at a time, ask me each", "Just list them for me", "Skip them", "Ask Ada"))
        showCard(mayaCard(json), viewer = "u-maya")
        onNodeWithText("Answer Question").assertDoesNotExist()
        onNodeWithText("For").assertDoesNotExist()
        onNodeWithText("How should I go about them?").assertExists()
        onNodeWithText("Fixer is asking you · 7 days left").assertExists()
        onNodeWithText("Skip them").assertIsEnabled()
        onNodeWithText("Other").assertExists()
        // One question: no required asterisk, no label repeating the title.
        onNodeWithText("How should I go about them? *").assertDoesNotExist()
        onNodeWithText("Choose an option first.").assertDoesNotExist()
    }

    @Test
    fun aSlimCardWithSeveralQuestionsIsTitledByTheAskerAndTheCount() = runRobolectricComposeUiTest {
        val json = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), ActionCardSpecFixtures.rawCard("question_card"))
        val card = mayaCard(json).copy(questions = listOf(ThreadActionCardQuestion("single", 3), ThreadActionCardQuestion("multiple", 3), ThreadActionCardQuestion("text")))
        showCard(card, viewer = "u-maya")
        onNodeWithText("Fixer has 3 questions").assertExists()
        onNodeWithText("Answer Questions").assertExists() // the sheet's button, not the title
        onNodeWithText("For").assertDoesNotExist()
        onNodeWithText("Fixer is asking you · 7 days left").assertExists()
    }

    @Test
    fun theSlimCardForSomeoneElseIsTheSameCardWithItsControlsDisabled() = runRobolectricComposeUiTest {
        // Who is asked changes only what can be pressed, never the shape: no fold, no "View only".
        showCard(mayaCard(ActionCardSpecFixtures.questionCardJson(options = listOf("Fix all 4 on a branch", "One at a time, ask me each", "Just list them for me", "Skip them", "Ask Ada"))).copy(questions = listOf(ThreadActionCardQuestion("single", 5))), viewer = "u-ada")
        onNodeWithText("How should I go about them?").assertExists()
        onNodeWithText("Waiting for Maya · 7 days left").assertExists()
        onNodeWithText("Skip them").assertIsNotEnabled()
        onNodeWithText("Other").assertIsNotEnabled()
        onNodeWithText("View only").assertDoesNotExist()
        onNodeWithContentDescription("Show or hide details").assertDoesNotExist()
    }

    @Test
    fun aSheetSlimCardForSomeoneElseKeepsItsButtonDisabled() = runRobolectricComposeUiTest {
        showCard(mayaCard(ActionCardSpecFixtures.questionCardJson(inline = false)), viewer = "u-ada")
        onNodeWithText("Waiting for Maya · 7 days left").assertExists()
        onNodeWithText("Answer Question").assertIsNotEnabled()
        onNodeWithContentDescription("Show or hide details").assertDoesNotExist()
    }

    @Test
    fun anAnsweredSlimCardKeepsItsTitleAndSaysWhoAnsweredWhereTheControlsWere() = runRobolectricComposeUiTest {
        val card = mayaCard(ActionCardSpecFixtures.questionCardAnsweredJson(), state = "executed", executedBy = "Maya").copy(questions = listOf(ThreadActionCardQuestion("single", 6)))
        showCard(card, viewer = "u-maya")
        onNodeWithText("How should I go about them?").assertExists()
        onNodeWithText("Maya answered Fix all 4 on a branch").assertExists()
        // No fold into a row, no status tag, no record rows repeating the answer.
        onNodeWithText("Answered").assertDoesNotExist()
        onNodeWithText("Done").assertDoesNotExist()
        onNodeWithText("Maya: Fix all 4 on a branch").assertDoesNotExist()
        onNodeWithContentDescription("Show or hide details").assertDoesNotExist()
        onNodeWithText("Answer Question").assertDoesNotExist()
    }

    @Test
    fun anExpiredButtonRowKeepsTheQuestionAndSaysWhoDidntAnswerWithNoButtons() = runRobolectricComposeUiTest {
        showCard(mayaCard(state = "expired").copy(questionExpiresInMinutes = 3 * 1_440), viewer = "u-maya")
        onNodeWithText("How should I go about them?").assertExists()
        onNodeWithText("Maya didn't answer within 3 days. Fixer was told.").assertExists()
        // No buttons, not even disabled ones, and no "waiting" hint.
        onNodeWithText("Fix all 4 on a branch").assertDoesNotExist()
        onNodeWithText("Other").assertDoesNotExist()
        onNodeWithText("Fixer is waiting for your answer", substring = true).assertDoesNotExist()
    }

    @Test
    fun aWithdrawnButtonRowSaysTheAgentWithdrewIt() = runRobolectricComposeUiTest {
        showCard(mayaCard(state = "withdrawn"), viewer = "u-ada")
        onNodeWithText("How should I go about them?").assertExists()
        onNodeWithText("Fixer withdrew this question.").assertExists()
        onNodeWithText("Just list them for me").assertDoesNotExist()
    }

    @Test
    fun anExpiredSlimCardKeepsItsTitleAndNamesWhoDidntAnswer() = runRobolectricComposeUiTest {
        val card = mayaCard(ActionCardSpecFixtures.questionCardJson(options = listOf("Fix all 4 on a branch", "One at a time, ask me each", "Just list them for me", "Skip them", "Ask Ada")), state = "expired").copy(questions = listOf(ThreadActionCardQuestion("single", 5)))
        showCard(card, viewer = "u-maya")
        onNodeWithText("How should I go about them?").assertExists()
        onNodeWithText("Maya didn't answer within 7 days. Fixer was told.").assertExists()
        onNodeWithText("Expired").assertDoesNotExist()
        onNodeWithText("Skip them").assertDoesNotExist()
        onNodeWithContentDescription("Show or hide details").assertDoesNotExist()
    }

    // A confirm (slock #14497): a question of one option, drawn as one bordered button with its label.
    private fun confirmCard(state: String = "prepared", executedBy: String? = null) =
        mayaCard(
            if (state == "executed") ActionCardSpecFixtures.questionCardAnsweredJson(answer = "Got it", question = "The deploy is done. Seen it?") else ActionCardSpecFixtures.questionConfirmCardJson(),
            state = state,
            executedBy = executedBy,
        ).copy(questions = listOf(ThreadActionCardQuestion("single", 1)))

    private fun answering(outcome: ActionCardExecuteOutcome = ActionCardExecuteOutcome.Executed(null), sent: MutableList<ExecuteActionFormBody?> = mutableListOf()) =
        ThreadActionCardSpecStore(
            scope = CoroutineScope(Dispatchers.Unconfined),
            execute = { _, _, form ->
                sent += form
                DataResult.Content(outcome)
            },
            applyCard = { _, _ -> },
        )

    @Test
    fun aConfirmIsOneButtonWithNoOtherCheckedWhenPressedAndSentAfterTheUndoWindow() = runRobolectricComposeUiTest {
        val sent = mutableListOf<ExecuteActionFormBody?>()
        mainClock.autoAdvance = false
        showCard(confirmCard(), viewer = "u-maya", store = answering(sent = sent))
        advance(100)

        onNodeWithText("The deploy is done. Seen it?").assertExists()
        onNodeWithText("Got it").assertIsEnabled().assertIsNotSelected()
        onNodeWithText("Other").assertDoesNotExist()
        onNodeWithText("Answer").assertDoesNotExist()
        onNodeWithText("Answer Question").assertDoesNotExist()

        onNodeWithText("Got it").performClick()
        advance(PICK_MS + 50)
        onNodeWithText("Got it").assertIsSelected()
        onNodeWithText("Undo").assertExists()
        assertEquals(emptyList(), sent, "nothing is sent while Undo shows")

        // Undo keeps it pressed; pressing it again (required) answers.
        onNodeWithText("Undo").performClick()
        advance(100)
        onNodeWithText("Got it").assertIsSelected()
        onNodeWithText("Got it").performClick()
        advance(PICK_MS + UNDO_MS + 100)
        assertEquals(listOf<ExecuteActionFormBody?>(ExecuteActionFormBody("answers", mapOf("how" to ActionCardFormValue.Choice(listOf("o0"))))), sent)
    }

    @Test
    fun aConfirmAmongOtherQuestionsSitsUnderItsQuestionAndAnOptionalOnePressedAgainIsTakenBack() = runRobolectricComposeUiTest {
        val card = mayaCard(ActionCardSpecFixtures.questionConfirmAmongOthersJson()).copy(questions = listOf(ThreadActionCardQuestion("single", 1), ThreadActionCardQuestion("single", 2), ThreadActionCardQuestion("single", 1)))
        mainClock.autoAdvance = false
        showCard(card, viewer = "u-maya", store = answering())
        advance(100)

        // B: the confirms are buttons, the choice of two keeps its rows and Other, the card its one submit.
        onNodeWithText("Got it").assertIsNotSelected()
        onNodeWithText("Done too").assertIsNotSelected()
        onNodeWithText("Tue 10:00").assertExists()
        onNodeWithText("Other").assertExists()
        onNodeWithText("Answer Questions").assertExists()

        // The third question sits below the test window: press it through its click action, not a tap at its place.
        onNodeWithText("Done too").performSemanticsAction(SemanticsActions.OnClick)
        advance(PICK_MS + 50)
        onNodeWithText("Done too").assertIsSelected()
        onNodeWithText("Undo").assertDoesNotExist()
        onNodeWithText("Done too").performSemanticsAction(SemanticsActions.OnClick)
        advance(PICK_MS + 50)
        onNodeWithText("Done too").assertIsNotSelected()
    }

    @Test
    fun anAnsweredConfirmIsTheSameOneLineAsAnyAnswer() = runRobolectricComposeUiTest {
        showCard(confirmCard(state = "executed", executedBy = "Maya"), viewer = "u-ada")
        onNodeWithText("The deploy is done. Seen it?").assertExists()
        onNodeWithText("Maya answered Got it").assertExists()
        onNodeWithText("Got it").assertDoesNotExist()
    }

    @Test
    fun aRefusedAnswerShowsItsIssueAndItsTraceIdOnce() = runRobolectricComposeUiTest {
        val refused =
            ActionCardExecuteOutcome.FormInvalid(
                listOf(actionCardFormIssue("how", ActionCardFormIssueCode.InvalidFormat)),
                "The form has problems; nothing was done",
                "4bf92f3577b34da6a3ce929d0e0e4736",
            )
        mainClock.autoAdvance = false
        showCard(confirmCard(), viewer = "u-maya", store = answering(refused))
        advance(100)
        onNodeWithText("Got it").performClick()
        advance(PICK_MS + UNDO_MS + 100)

        // The issue under the question, and the 422's trace id once, under it (never once per field).
        onNodeWithText("This value is not valid.").assertExists()
        onAllNodesWithTag(RaftTraceIdNoteTestTag).assertCountEquals(1)
        onNodeWithText("4bf92f35…").assertExists()
        onNodeWithText("Got it").assertIsEnabled()
    }

    @Test
    fun anExpiredCardAskedOfAnyoneSaysNobodyAnswered() = runRobolectricComposeUiTest {
        val card = mayaCard(state = "expired").copy(questionAddresseeUserId = null, questionAddresseeHandle = null, questionAsksAnyone = true)
        showCard(card, viewer = "u-maya")
        onNodeWithText("Nobody answered within 7 days. Fixer was told.").assertExists()
    }
}
