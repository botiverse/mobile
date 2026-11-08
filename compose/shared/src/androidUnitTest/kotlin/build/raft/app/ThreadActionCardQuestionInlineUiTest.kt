package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.ActionCardExecuteOutcome
import build.raft.app.data.ActionCardFormValue
import build.raft.app.data.DataResult
import build.raft.app.data.ExecuteActionFormBody
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ThreadActionCard
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
 * answered in the thread (web QuestionInlineForm): its options are in the card, a tap on a single
 * choice says "Submitted · Undo" for 1.5 s (§5.10) and sends nothing; Undo keeps the choice and
 * sends nothing; another tap is sent once, with the form, when the wait is over.
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
        val spec = assertNotNull(card.actionCardSpecToRender(specEnabled = true, capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
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
        onNodeWithText("Postgres").assertIsSelected()
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
}
