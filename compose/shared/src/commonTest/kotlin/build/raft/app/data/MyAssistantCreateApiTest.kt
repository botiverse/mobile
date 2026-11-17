package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * `POST servers/:id/assistant` against the upstream shapes, botiverse/slock staging
 * e136d7c9543fb84bf91330370d89e17c8d8fa044: `packages/server/src/routes/agents.ts`
 * (`myAssistantRouter.post`: 201 `myAssistantView`, 409 `{ error, code: "assistant_exists",
 * ...myAssistantView }`, 403 `assistant_not_enabled`), `services/assistantService.ts`
 * (`AssistantExistsError` "You already have an assistant in this server."), the bodies
 * `routes/myAssistant.api.test.ts` asserts (name "Ottie", `id == agentId`, a `dmChannelId`), and
 * web `assistantClient.ts` `createAssistant` / `handleMutationError` for the 400 limit (shared
 * `formatBillingCapacityLimitMessage`: "<label> reached (n/m on <plan> plan).").
 */
class MyAssistantCreateApiTest {
    private val view =
        """{"enabled":true,"assistant":{"id":"6f1c2a4e-0d7b-4c55-9a51-1f2e3d4c5b6a","name":"Ottie",""" +
            """"agentId":"6f1c2a4e-0d7b-4c55-9a51-1f2e3d4c5b6a","dmChannelId":"9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",""" +
            """"readsDms":true,"sendAsOwner":true,"ownerNotes":null}}"""

    @Test
    fun theBodyIsEmpty() {
        // Web `api.post(path(serverId), {})`: every assistant is Ottie, nothing to send.
        assertEquals(Json.parseToJsonElement("{}").jsonObject, Json.parseToJsonElement(encodeMyAssistantCreateBody()).jsonObject)
    }

    @Test
    fun createdReadsTheView() {
        val created = assertIs<MyAssistantCreateResult.Created>(myAssistantCreateResultOf(201, view))
        val mine = created.answer?.assistant
        assertEquals(true, created.answer?.enabled)
        assertEquals("Ottie", mine?.name)
        assertEquals(mine?.id, mine?.agentId)
        assertEquals("9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", mine?.dmChannelId)
        assertEquals(true, mine?.readsDms)
        // A 201 without a readable body still counts as created; the store reads it again.
        assertEquals(MyAssistantCreateResult.Created(null), myAssistantCreateResultOf(201, "not json"))
    }

    @Test
    fun anExistingAssistantIsAdopted() {
        val body =
            """{"error":"You already have an assistant in this server.","code":"assistant_exists",""" + view.removePrefix("{")
        val exists = assertIs<MyAssistantCreateResult.Exists>(myAssistantCreateResultOf(409, body, traceId = "t"))
        assertTrue(exists.answer.enabled)
        assertEquals("6f1c2a4e-0d7b-4c55-9a51-1f2e3d4c5b6a", exists.answer.assistant?.agentId)
        // Web adopts it `enabled: true` even if the view said otherwise.
        val disabledView = body.replace("\"enabled\":true", "\"enabled\":false")
        assertTrue(assertIs<MyAssistantCreateResult.Exists>(myAssistantCreateResultOf(409, disabledView)).answer.enabled)
    }

    @Test
    fun theOtherAnswersAreStaleClosedLimitedOrFailed() {
        // A 409 without the assistant, or a 404: read the assistant again (web `refresh`).
        assertEquals(
            MyAssistantCreateResult.Stale(409, "t"),
            myAssistantCreateResultOf(409, """{"error":"You already have an assistant in this server.","code":"assistant_exists","enabled":true,"assistant":null}""", "t"),
        )
        assertEquals(MyAssistantCreateResult.Stale(404, null), myAssistantCreateResultOf(404, """{"error":"User not found"}"""))
        // 403 assistant_not_enabled (or a revoked membership): the page closes.
        assertEquals(
            MyAssistantCreateResult.NotAvailable(403, "t"),
            myAssistantCreateResultOf(403, """{"error":"Personal assistants are not enabled on this server.","code":"assistant_not_enabled"}""", "t"),
        )
        // A 400 whose error is the limit's message: shown as the server words it.
        assertEquals(
            MyAssistantCreateResult.CapacityLimit("Seat limit reached (1/1 on Pro plan).", "t"),
            myAssistantCreateResultOf(400, """{"error":"Seat limit reached (1/1 on Pro plan).","capacityLimit":{"limitType":"universal","limit":1,"humans":1,"agents":0}}""", "t"),
        )
        // Any other 400, a 5xx, or no body: nothing created.
        assertEquals(MyAssistantCreateResult.Failed(400, null), myAssistantCreateResultOf(400, """{"error":"Bad request"}"""))
        assertEquals(MyAssistantCreateResult.Failed(500, "t"), myAssistantCreateResultOf(500, """{"error":"Failed to create your assistant"}""", "t"))
        assertEquals(MyAssistantCreateResult.Failed(502, null), myAssistantCreateResultOf(502, null))
    }

    @Test
    fun rejectsGuessedWrappersAndAliases() {
        // A wrapped 409 is not the server's shape: no assistant to adopt, read it again instead.
        val wrapped = """{"code":"assistant_exists","data":{"enabled":true,"assistant":{"id":"a1","name":"Ottie","agentId":"a1","dmChannelId":"d1"}}}"""
        assertEquals(MyAssistantCreateResult.Stale(409, null), myAssistantCreateResultOf(409, wrapped))
        // The limit is read from `error` only, not a guessed `message`.
        assertEquals(MyAssistantCreateResult.Failed(400, null), myAssistantCreateResultOf(400, """{"message":"Seat limit reached (1/1 on Pro plan)."}"""))
    }
}
