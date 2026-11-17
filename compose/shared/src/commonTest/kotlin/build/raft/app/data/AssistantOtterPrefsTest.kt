package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The otter's account setting against botiverse/slock staging
 * 34511fe7e4a36d0d5c054f836be70821fa8b4608: `packages/server/src/services/userService.ts`
 * `toPublicUser` (`assistantOtterVisible: user.assistantOtterVisible ?? true`), `routes/auth.ts`
 * `PATCH /me` (`parseAssistantOtterVisible`: a boolean or a 400), and web
 * `assistant/assistantOtterPrefs.ts` (absent = shown; a failed save puts the previous value back).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantOtterPrefsTest {
    /** A trimmed `toPublicUser` body, as `GET /api/auth/me` sends it. */
    private fun user(visible: Boolean?): String =
        """{"id":"u1","email":"ada@example.com","name":"ada","displayName":"Ada","railTabOrder":null,""" +
            (if (visible == null) "" else """"assistantOtterVisible":$visible,""") +
            """"assistantOtterShortcut":null,"composerSendKey":null,"shareUsageData":null}"""

    @Test
    fun readsTheAccountsSwitch() {
        assertEquals(DataResult.Content(false), parseSharedAssistantOtterVisible(200, user(false)))
        assertEquals(DataResult.Content(true), parseSharedAssistantOtterVisible(200, user(true)))
        // A server from before the setting doesn't send it: shown.
        assertEquals(DataResult.Content(true), parseSharedAssistantOtterVisible(200, user(null)))
    }

    @Test
    fun theBodyIsTheSwitchAlone() {
        val body = Json.parseToJsonElement(buildSharedAssistantOtterVisibleUpdateBody(false)).jsonObject
        assertEquals(setOf("assistantOtterVisible"), body.keys)
        assertEquals(JsonPrimitive(false), body["assistantOtterVisible"])
    }

    @Test
    fun rejectsGuessedWrappersAndKeepsTheTrace() {
        // Not the user (no `id`): a mismatch, not "shown".
        val wrapped = assertIs<DataResult.Error>(parseSharedAssistantOtterVisible(200, """{"user":{"id":"u1","assistantOtterVisible":false}}"""))
        assertEquals("assistant_otter_prefs_contract_mismatch", wrapped.error.code)
        val refused = assertIs<DataResult.Error>(parseSharedAssistantOtterVisible(400, """{"error":"assistantOtterVisible must be a boolean"}""", TRACE))
        assertEquals("assistant_otter_prefs_http_400", refused.error.code)
        assertEquals(TRACE, refused.error.traceId)
    }

    private class FakeSource(
        var read: DataResult<Boolean> = DataResult.Content(true),
        var save: DataResult<Boolean>? = null,
        val saveGate: CompletableDeferred<Unit>? = null,
    ) : AssistantOtterPrefsDataSource {
        var reads = 0
        val saves = mutableListOf<Boolean>()

        override suspend fun readVisible(): DataResult<Boolean> {
            reads += 1
            return read
        }

        override suspend fun saveVisible(visible: Boolean): DataResult<Boolean> {
            saves += visible
            saveGate?.await()
            return save ?: DataResult.Content(visible)
        }
    }

    @Test
    fun readsOncePerAccountAndAFailedReadShowsIt() =
        runTest {
            val source = FakeSource(read = DataResult.Content(false))
            val store = SharedAssistantOtterPrefsStore(source, backgroundScope)
            assertNull(store.state.value.visible, "unknown before the read")
            store.ensureLoaded("u1")
            store.ensureLoaded("u1")
            runCurrent()
            store.ensureLoaded("u1")
            runCurrent()
            assertEquals(1, source.reads)
            assertEquals(false, store.state.value.visible)
            // Another account starts over.
            source.read = DataResult.Error(ScreenError("network_unavailable", traceId = null))
            store.ensureLoaded("u2")
            runCurrent()
            assertEquals(2, source.reads)
            assertEquals(AssistantOtterPrefsState(accountId = "u2", visible = true), store.state.value)
        }

    @Test
    fun aFlipShowsAtOnceAndOneSaveRunsAtATime() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source = FakeSource(saveGate = gate)
            val store = SharedAssistantOtterPrefsStore(source, backgroundScope)
            store.ensureLoaded("u1")
            runCurrent()
            val first = async { store.setVisible("u1", false) }
            runCurrent()
            assertEquals(false, store.state.value.visible)
            assertTrue(store.state.value.saving)
            // The switch is disabled while it saves: a second flip sends nothing.
            assertFalse(store.setVisible("u1", true))
            gate.complete(Unit)
            assertTrue(first.await())
            assertEquals(listOf(false), source.saves)
            assertEquals(AssistantOtterPrefsState(accountId = "u1", visible = false), store.state.value)
        }

    @Test
    fun aFailedSavePutsThePreviousValueBackWithItsTrace() =
        runTest {
            val failure = ScreenError("assistant_otter_prefs_http_500", traceId = TRACE)
            val source = FakeSource(save = DataResult.Error(failure))
            val store = SharedAssistantOtterPrefsStore(source, backgroundScope)
            store.ensureLoaded("u1")
            runCurrent()
            assertTrue(store.setVisible("u1", false))
            assertEquals(true, store.state.value.visible)
            assertFalse(store.state.value.saving)
            assertEquals(TRACE, store.state.value.saveFailure?.traceId)
            // The next flip clears it.
            source.save = null
            store.setVisible("u1", false)
            assertNull(store.state.value.saveFailure)
            assertEquals(false, store.state.value.visible)
            // Another account's flip is not this one's.
            assertFalse(store.setVisible("u2", true))
        }
}

private const val TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
