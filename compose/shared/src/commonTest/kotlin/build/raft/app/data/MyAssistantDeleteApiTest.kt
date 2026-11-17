package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `DELETE servers/:id/assistant` against the upstream shapes, botiverse/slock staging
 * 34511fe7e4a36d0d5c054f836be70821fa8b4608: `packages/server/src/routes/agents.ts`
 * (`myAssistantRouter.delete`: 200 `{ enabled, assistant: null }`, 404 `assistant_not_found`,
 * 403 `forbidden` / guest `assistant_not_enabled`, `AgentArchiveError` status + code),
 * `routes/myAssistant.api.test.ts` (`deleted.body` deep-equals `{ enabled: true, assistant: null }`),
 * `services/agentArchiveService.ts` (409 `archive_op_pending`), and web `assistantClient.ts`
 * `deleteAssistant` / `handleMutationError`.
 */
class MyAssistantDeleteApiTest {
    @Test
    fun deletedReadsTheView() {
        val deleted = assertIs<MyAssistantDeleteResult.Deleted>(myAssistantDeleteResultOf(200, """{"enabled":true,"assistant":null}"""))
        assertEquals(MyAssistantAnswer(enabled = true, assistant = null), deleted.answer)
        // A 200 without a readable body still counts as deleted; the store reads it again.
        assertNull(assertIs<MyAssistantDeleteResult.Deleted>(myAssistantDeleteResultOf(200, null)).answer)
    }

    @Test
    fun refusalsCloseOrReadAgainAndFailuresKeepTheTrace() {
        // A guest, or a fenced refusal: not available here.
        assertEquals(
            MyAssistantDeleteResult.NotAvailable(403, "t"),
            myAssistantDeleteResultOf(403, """{"error":"Guests can't have a personal assistant.","code":"assistant_not_enabled"}""", "t"),
        )
        assertEquals(
            MyAssistantDeleteResult.NotAvailable(403, null),
            myAssistantDeleteResultOf(403, """{"error":"You cannot delete this assistant.","code":"forbidden"}"""),
        )
        // None to delete, or the archive is busy: read the assistant again.
        assertEquals(
            MyAssistantDeleteResult.Stale(404, "t"),
            myAssistantDeleteResultOf(404, """{"error":"You have no assistant in this server.","code":"assistant_not_found"}""", "t"),
        )
        assertEquals(
            MyAssistantDeleteResult.Stale(409, null),
            myAssistantDeleteResultOf(409, """{"error":"Another workspace operation is still running.","code":"archive_op_pending"}"""),
        )
        assertEquals(
            MyAssistantDeleteResult.Failed(500, "t"),
            myAssistantDeleteResultOf(500, """{"error":"Failed to delete your assistant"}""", "t"),
        )
        assertEquals(MyAssistantDeleteResult.Failed(502, null), myAssistantDeleteResultOf(502, null))
    }
}
