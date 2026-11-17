package build.raft.app.assistant

import build.raft.app.data.MyAssistantCreateOutcome
import build.raft.app.data.MyAssistantRecord
import build.raft.app.data.MyAssistantState
import build.raft.app.data.ScreenError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The Assistant page's states (web AssistantRoute / CreateAssistantView), on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantPageStateTest {
    private val mine = MyAssistantRecord(id = "a1", name = "Ottie", agentId = "a1", dmChannelId = "dm1")

    @Test
    fun loadingOffCreateThenTheConversation() {
        val form = AssistantCreateForm()
        assertEquals(AssistantPageView.Loading, assistantPageView(MyAssistantState.Loading, form))
        assertEquals(AssistantPageView.Off, assistantPageView(MyAssistantState.Off, form))
        // Enabled with no assistant yet: Create, with the form's own state.
        val failed = AssistantCreateForm(failure = AssistantCreateFailure.Generic(ScreenError("http_500")))
        assertEquals(AssistantPageView.Create(failed), assistantPageView(MyAssistantState.Ready(null), failed))
        // An assistant (its DM set up or not): the conversation, whatever the form says.
        assertEquals(AssistantPageView.Conversation(mine), assistantPageView(MyAssistantState.Ready(mine), failed))
        val settingUp = mine.copy(dmChannelId = null)
        assertEquals(AssistantPageView.Conversation(settingUp), assistantPageView(MyAssistantState.Ready(settingUp), form))
    }

    @Test
    fun createIsSingleFlightAndClearsTheLastFailure() =
        runTest {
            val answers = ArrayDeque<CompletableDeferred<MyAssistantCreateOutcome>>()
            var asked = 0
            val store =
                AssistantCreateStore(
                    create = {
                        asked += 1
                        CompletableDeferred<MyAssistantCreateOutcome>().also(answers::addLast).await()
                    },
                    scope = backgroundScope,
                )
            store.submit()
            store.submit()
            runCurrent()
            assertEquals(1, asked, "a press while one is on its way does nothing")
            assertTrue(store.state.value.submitting)
            answers.removeFirst().complete(MyAssistantCreateOutcome.Failed(ScreenError("http_500", traceId = "t")))
            runCurrent()
            val generic = assertIs<AssistantCreateFailure.Generic>(store.state.value.failure)
            assertEquals("t", generic.error.traceId)
            assertEquals(false, store.state.value.submitting)
            // Again: the failure clears while it tries.
            store.submit()
            runCurrent()
            assertEquals(2, asked)
            assertEquals(AssistantCreateForm(submitting = true), store.state.value)
            answers.removeFirst().complete(MyAssistantCreateOutcome.Done)
            runCurrent()
            assertEquals(AssistantCreateForm(), store.state.value)
        }

    @Test
    fun theLimitKeepsTheServersMessage() =
        runTest {
            val limit = ScreenError("assistant_capacity_limit", retryable = false, serverMessage = "Seat limit reached (1/1 on Pro plan).", traceId = "t")
            val store = AssistantCreateStore(create = { MyAssistantCreateOutcome.CapacityLimit(limit) }, scope = backgroundScope)
            store.submit()
            runCurrent()
            val failure = assertIs<AssistantCreateFailure.Capacity>(store.state.value.failure)
            assertEquals("Seat limit reached (1/1 on Pro plan).", failure.error.serverMessage)
            assertEquals("t", failure.error.traceId)
            assertNull(AssistantCreateForm().failure)
        }
}
