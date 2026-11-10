package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** "My assistant" reads (web assistantClient.ts `useMyAssistant` / `refetchMyAssistant`), on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedMyAssistantStoreTest {
    private val key = MyAssistantKey(serverId = "s1", userId = "u1")
    private val ready = MyAssistantAnswer(true, MyAssistantRecord("a1", "Ada", "a1", "dm1"))
    private val settingUp = MyAssistantAnswer(true, MyAssistantRecord("a1", "Ada", "a1", null))

    /** Runs everything the store queued on backgroundScope, retries included (advanceUntilIdle leaves it alone). */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    /** Answers in order; the last one repeats. A save answers [saveResult] and records the notes. */
    private class ScriptedApi(
        vararg results: MyAssistantFetchResult,
        val saveResult: MyAssistantOwnerNotesSaveResult = MyAssistantOwnerNotesSaveResult.Failed(500),
    ) : RaftMyAssistantApi {
        private val script = results.toList()
        val asked = mutableListOf<String>()
        val saved = mutableListOf<String>()

        override suspend fun fetch(serverId: ServerId): MyAssistantFetchResult {
            asked += serverId.value
            return script[minOf(asked.size - 1, script.lastIndex)]
        }

        override suspend fun updateOwnerNotes(serverId: ServerId, notes: String): MyAssistantOwnerNotesSaveResult {
            saved += notes
            return saveResult
        }
    }

    @Test
    fun readsOncePerPersonAndServer() =
        runTest {
            val api = ScriptedApi(MyAssistantFetchResult.Answer(ready))
            val store = SharedMyAssistantStore(api, scope = backgroundScope)
            store.ensureLoaded(key)
            store.ensureLoaded(key)
            settle()
            store.ensureLoaded(key)
            settle()
            assertEquals(listOf("s1"), api.asked)
            assertEquals(ready, store.answerFor(key))
        }

    @Test
    fun notAvailableAndAFailedFirstReadFailClosed() =
        runTest {
            val notAvailable = SharedMyAssistantStore(ScriptedApi(MyAssistantFetchResult.NotAvailable), scope = backgroundScope)
            notAvailable.ensureLoaded(key)
            val failed = SharedMyAssistantStore(ScriptedApi(MyAssistantFetchResult.Failed(503)), scope = backgroundScope)
            failed.ensureLoaded(key)
            settle()
            assertEquals(MyAssistantAnswer.NotEnabled, notAvailable.answerFor(key))
            assertEquals(MyAssistantAnswer.NotEnabled, failed.answerFor(key))
        }

    @Test
    fun asksAgainWithBackoffWhileTheDmIsSetUp() =
        runTest {
            val api =
                ScriptedApi(
                    MyAssistantFetchResult.Answer(settingUp),
                    // A transient failure on a refetch keeps the cached answer.
                    MyAssistantFetchResult.Failed(502),
                    MyAssistantFetchResult.Answer(ready),
                )
            val store = SharedMyAssistantStore(api, scope = backgroundScope)
            store.ensureLoaded(key)
            runCurrent()
            assertEquals(settingUp, store.answerFor(key))
            advanceTimeBy(2_001)
            assertEquals(2, api.asked.size, "first refetch after 2s")
            assertEquals(settingUp, store.answerFor(key), "a failed refetch keeps the answer")
            advanceTimeBy(5_001)
            assertEquals(3, api.asked.size, "second refetch 5s later")
            assertEquals(ready, store.answerFor(key))
            settle()
            assertEquals(3, api.asked.size, "no more once the DM exists")
        }

    @Test
    fun stopsAfterTheLastDelay() =
        runTest {
            val api = ScriptedApi(MyAssistantFetchResult.Answer(settingUp))
            val store = SharedMyAssistantStore(api, scope = backgroundScope)
            store.ensureLoaded(key)
            settle()
            assertEquals(1 + MyAssistantDmSetupRetryDelaysMillis.size, api.asked.size)
            assertEquals(settingUp, store.answerFor(key))
        }

    @Test
    fun stateFollowsTheWebGate() {
        // Guests never, whatever the flag and the answer say.
        assertEquals(MyAssistantState.Off, myAssistantState(viewerIsGuest = true, flagDecisionPending = false, flagEnabled = true, answer = ready))
        // The flag is a pre-check: off is off, pending is loading.
        assertEquals(MyAssistantState.Off, myAssistantState(false, flagDecisionPending = false, flagEnabled = false, answer = ready))
        assertEquals(MyAssistantState.Loading, myAssistantState(false, flagDecisionPending = true, flagEnabled = false, answer = null))
        // Then the API's `enabled` decides.
        assertEquals(MyAssistantState.Loading, myAssistantState(false, false, flagEnabled = true, answer = null))
        assertEquals(MyAssistantState.Off, myAssistantState(false, false, flagEnabled = true, answer = MyAssistantAnswer.NotEnabled))
        assertEquals(MyAssistantState.Ready(ready.assistant), myAssistantState(false, false, flagEnabled = true, answer = ready))
        assertEquals(MyAssistantState.Ready(null), myAssistantState(false, false, true, MyAssistantAnswer(true, null)))
    }

    @Test
    fun theOtterLiesOnTheComposerUsedLast() {
        val hosts = RaftAssistantOtterHosts()
        hosts.register("channel")
        assertEquals("channel", hosts.order.value.activeAssistantOtterHost())
        // A thread opened over the channel: the newest composer takes it.
        hosts.register("thread")
        assertEquals("thread", hosts.order.value.activeAssistantOtterHost())
        // Focus in the channel's composer moves it back.
        hosts.activate("channel")
        assertEquals("channel", hosts.order.value.activeAssistantOtterHost())
        // Activating a composer that is not registered does nothing.
        hosts.activate("gone")
        assertEquals(listOf("thread", "channel"), hosts.order.value)
        // The one it lay on leaves: the one before it takes it.
        hosts.unregister("channel")
        assertEquals("thread", hosts.order.value.activeAssistantOtterHost())
        hosts.unregister("thread")
        assertEquals(null, hosts.order.value.activeAssistantOtterHost())
    }

    // ---- Extra instructions (web assistantClient.ts `saveAssistantOwnerNotes`, RFC 077 #10762) ----

    private val withNotes = MyAssistantAnswer(true, MyAssistantRecord("a1", "Ada", "a1", "dm1", readsDms = true, sendAsOwner = false, ownerNotes = "Be brief."))

    @Test
    fun ownerNotesAreTextNoneOrNotReported() {
        fun notesOf(assistant: String) =
            parseSharedMyAssistantResponse("""{"enabled":true,"assistant":{"id":"a1","name":"Ada","agentId":"a1","dmChannelId":"dm1"$assistant}}""")
                ?.assistant
        // Web `ownerNotesOf`: the text as saved; null from the server is none ("").
        assertEquals("Reply in English.", notesOf(""","ownerNotes":"Reply in English."""")?.ownerNotes)
        assertEquals("", notesOf(""","ownerNotes":null""")?.ownerNotes)
        // A server without the field: not reported, the editor stays hidden.
        assertNull(notesOf("")?.ownerNotes)
        assertEquals(true, notesOf(""","sendAsOwner":true""")?.sendAsOwner)
        assertNull(notesOf("")?.sendAsOwner)
    }

    @Test
    fun theEditorChecksTheLimitOnTheTrimmedText() {
        val atLimit = "x".repeat(ASSISTANT_OWNER_NOTES_MAX_LENGTH)
        assertFalse(assistantOwnerNotesTooLong("  $atLimit\n"), "surrounding whitespace does not count")
        assertTrue(assistantOwnerNotesTooLong(atLimit + "x"))
        // The counter shows from 90% of the limit on (3600 of 4000).
        assertFalse(assistantOwnerNotesShowsCounter("x".repeat(3599)))
        assertTrue(assistantOwnerNotesShowsCounter("x".repeat(3600)))
        // Save needs a change once trimmed, within the limit.
        assertFalse(assistantOwnerNotesCanSave("Be brief.  ", savedNotes = "Be brief."))
        assertTrue(assistantOwnerNotesCanSave("Be briefer.", savedNotes = "Be brief."))
        assertTrue(assistantOwnerNotesCanSave("   ", savedNotes = "Be brief."), "clearing them is a change")
        assertFalse(assistantOwnerNotesCanSave(atLimit + "x", savedNotes = ""))
    }

    @Test
    fun thePatchSendsTheTrimmedNotesOrNull() {
        assertEquals("""{"ownerNotes":"Be brief."}""", encodeAssistantOwnerNotesPatchBody("  Be brief.\n"))
        assertEquals("""{"ownerNotes":null}""", encodeAssistantOwnerNotesPatchBody("  \n "))
    }

    @Test
    fun aPatchAnswerIsSavedTooLongGoneOrFailed() {
        val saved =
            myAssistantOwnerNotesSaveResultOf(
                200,
                """{"enabled":true,"assistant":{"id":"a1","name":"Ada","agentId":"a1","dmChannelId":"dm1","readsDms":true,"sendAsOwner":false,"ownerNotes":"Be brief."}}""",
            )
        assertEquals(MyAssistantOwnerNotesSaveResult.Saved(withNotes), saved)
        assertEquals(
            MyAssistantOwnerNotesSaveResult.TooLong,
            myAssistantOwnerNotesSaveResultOf(400, """{"error":"invalid_owner_notes"}"""),
        )
        assertEquals(
            MyAssistantOwnerNotesSaveResult.TooLong,
            myAssistantOwnerNotesSaveResultOf(400, """{"error":"Notes are too long","code":"invalid_owner_notes"}"""),
        )
        assertEquals(MyAssistantOwnerNotesSaveResult.Failed(400), myAssistantOwnerNotesSaveResultOf(400, """{"error":"bad_request"}"""))
        assertEquals(MyAssistantOwnerNotesSaveResult.NotAvailable, myAssistantOwnerNotesSaveResultOf(403, """{"code":"assistant_not_enabled"}"""))
        assertEquals(MyAssistantOwnerNotesSaveResult.NotAvailable, myAssistantOwnerNotesSaveResultOf(404, null))
        assertEquals(MyAssistantOwnerNotesSaveResult.Failed(500), myAssistantOwnerNotesSaveResultOf(500, null))
    }

    @Test
    fun aSaveTakesTheServersViewOfTheAssistant() =
        runTest {
            val reply = MyAssistantAnswer(true, withNotes.assistant?.copy(ownerNotes = "Reply in English."))
            val api = ScriptedApi(MyAssistantFetchResult.Answer(withNotes), saveResult = MyAssistantOwnerNotesSaveResult.Saved(reply))
            val store = SharedMyAssistantStore(api, scope = backgroundScope)
            store.ensureLoaded(key)
            settle()
            assertEquals(MyAssistantOwnerNotesSaveOutcome.Saved, store.saveOwnerNotes(key, "  Reply in English.  "))
            assertEquals(listOf("Reply in English."), api.saved, "the notes go trimmed")
            assertEquals(reply, store.answerFor(key))
        }

    @Test
    fun aSaveWithoutAReadableReplyKeepsTheTrimmedNotes() =
        runTest {
            val api = ScriptedApi(MyAssistantFetchResult.Answer(withNotes), saveResult = MyAssistantOwnerNotesSaveResult.Saved(null))
            val store = SharedMyAssistantStore(api, scope = backgroundScope)
            store.ensureLoaded(key)
            settle()
            assertEquals(MyAssistantOwnerNotesSaveOutcome.Saved, store.saveOwnerNotes(key, "   "))
            assertEquals("", store.answerFor(key)?.assistant?.ownerNotes, "cleared")
            assertEquals("Ada", store.answerFor(key)?.assistant?.name)
        }

    @Test
    fun aRefusedSaveChangesNothing() =
        runTest {
            val tooLong = ScriptedApi(MyAssistantFetchResult.Answer(withNotes), saveResult = MyAssistantOwnerNotesSaveResult.TooLong)
            val store = SharedMyAssistantStore(tooLong, scope = backgroundScope)
            store.ensureLoaded(key)
            settle()
            assertEquals(MyAssistantOwnerNotesSaveOutcome.TooLong, store.saveOwnerNotes(key, "x".repeat(4001)))
            assertEquals(withNotes, store.answerFor(key))

            val failed = ScriptedApi(MyAssistantFetchResult.Answer(withNotes), saveResult = MyAssistantOwnerNotesSaveResult.Failed(null))
            val other = SharedMyAssistantStore(failed, scope = backgroundScope)
            other.ensureLoaded(key)
            settle()
            assertEquals(MyAssistantOwnerNotesSaveOutcome.Failed, other.saveOwnerNotes(key, "Be briefer."))
            assertEquals(withNotes, other.answerFor(key))
            assertEquals(1, failed.asked.size, "a transient failure reads nothing again")
        }

    @Test
    fun aSaveTheServerNoLongerAllowsReadsTheAssistantAgain() =
        runTest {
            val api =
                ScriptedApi(
                    MyAssistantFetchResult.Answer(withNotes),
                    MyAssistantFetchResult.NotAvailable,
                    saveResult = MyAssistantOwnerNotesSaveResult.NotAvailable,
                )
            val store = SharedMyAssistantStore(api, scope = backgroundScope)
            store.ensureLoaded(key)
            settle()
            val outcome = store.saveOwnerNotes(key, "Be briefer.")
            settle()
            assertEquals(MyAssistantOwnerNotesSaveOutcome.Failed, outcome)
            assertEquals(2, api.asked.size)
            assertEquals(MyAssistantAnswer.NotEnabled, store.answerFor(key))
        }
}
