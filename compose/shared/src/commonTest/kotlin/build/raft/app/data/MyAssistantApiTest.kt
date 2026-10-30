package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET servers/:id/assistant` against the upstream shapes. Payloads follow botiverse/slock staging
 * ee41b3f3e24b2508a5db7e6bdf900f35b1ad275c: `packages/server/src/routes/agents.ts`
 * (`myAssistantView`: `{ enabled, assistant }`), `services/assistantService.ts` (`MyAssistant`:
 * `{ id, name, agentId, dmChannelId, readsDms, sendAsOwner, ownerNotes }`), and the bodies
 * `routes/myAssistant.api.test.ts` asserts.
 */
class MyAssistantApiTest {
    @Test
    fun flagOffOrNoRuntimeIsNotEnabled() {
        // myAssistant.api.test.ts: `assert.deepEqual(body, { enabled: false, assistant: null })`.
        assertEquals(MyAssistantAnswer.NotEnabled, parseSharedMyAssistantResponse("""{"enabled":false,"assistant":null}"""))
    }

    @Test
    fun enabledWithoutAnAssistant() {
        // myAssistant.api.test.ts: `{ enabled: true, assistant: null }`.
        assertEquals(
            MyAssistantAnswer(enabled = true, assistant = null),
            parseSharedMyAssistantResponse("""{"enabled":true,"assistant":null}"""),
        )
    }

    @Test
    fun anAssistantWithItsDm() {
        val answer =
            parseSharedMyAssistantResponse(
                """
                {"enabled":true,"assistant":{
                  "id":"6f1c2a4e-0d7b-4c55-9a51-1f2e3d4c5b6a","name":"Ada",
                  "agentId":"6f1c2a4e-0d7b-4c55-9a51-1f2e3d4c5b6a",
                  "dmChannelId":"0b9e8d7c-6f5a-4b3c-8d2e-1a0f9e8d7c6b",
                  "readsDms":true,"sendAsOwner":true,"ownerNotes":null}}
                """.trimIndent()
            )
        assertEquals(
            MyAssistantAnswer(
                enabled = true,
                assistant =
                    MyAssistantRecord(
                        id = "6f1c2a4e-0d7b-4c55-9a51-1f2e3d4c5b6a",
                        name = "Ada",
                        agentId = "6f1c2a4e-0d7b-4c55-9a51-1f2e3d4c5b6a",
                        dmChannelId = "0b9e8d7c-6f5a-4b3c-8d2e-1a0f9e8d7c6b",
                    ),
            ),
            answer,
        )
    }

    @Test
    fun aDmStillBeingSetUpHasNoDmChannelId() {
        val answer =
            parseSharedMyAssistantResponse(
                """{"enabled":true,"assistant":{"id":"a1","name":"Ada","agentId":"a1","dmChannelId":null,"readsDms":true,"sendAsOwner":true,"ownerNotes":"Be brief."}}"""
            )
        assertNull(answer?.assistant?.dmChannelId)
        assertEquals("a1", answer?.assistant?.agentId)
    }

    @Test
    fun rejectsGuessedWrappersAndAliases() {
        // An envelope is not this shape: nothing reads as enabled.
        val wrapped = parseSharedMyAssistantResponse("""{"data":{"enabled":true,"assistant":{"id":"a1","name":"Ada","agentId":"a1","dmChannelId":"d1"}}}""")
        assertEquals(MyAssistantAnswer.NotEnabled, wrapped)
        // snake_case is not read.
        val snake = parseSharedMyAssistantResponse("""{"enabled":true,"assistant":{"id":"a1","name":"Ada","agent_id":"a1","dm_channel_id":"d1"}}""")
        assertNull(snake, "no agentId: not an assistant record")
        // An assistant without ids, or a body that is not JSON, is unreadable.
        assertNull(parseSharedMyAssistantResponse("""{"enabled":true,"assistant":{"id":"","name":"Ada","agentId":"","dmChannelId":"d1"}}"""))
        assertNull(parseSharedMyAssistantResponse("<html>"))
    }

    @Test
    fun statusesMapAsTheWebDoes() {
        // 403 assistant_not_enabled, and 404 from a server without the route: not available.
        assertEquals(MyAssistantFetchResult.NotAvailable, myAssistantFetchResultOf(403, """{"error":"Personal assistants are not enabled on this server.","code":"assistant_not_enabled"}"""))
        assertEquals(MyAssistantFetchResult.NotAvailable, myAssistantFetchResultOf(404, null))
        // 5xx and unreadable bodies are transient.
        assertEquals(MyAssistantFetchResult.Failed(500), myAssistantFetchResultOf(500, """{"error":"Failed to load your assistant"}"""))
        assertEquals(MyAssistantFetchResult.Failed(200), myAssistantFetchResultOf(200, "not json"))
        val ok = myAssistantFetchResultOf(200, """{"enabled":true,"assistant":null}""")
        assertIs<MyAssistantFetchResult.Answer>(ok)
        assertTrue(ok.answer.enabled)
        assertFalse(ok.answer.assistant != null)
    }
}
