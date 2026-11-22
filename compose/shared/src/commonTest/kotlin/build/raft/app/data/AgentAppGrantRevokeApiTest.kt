package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `POST /api/integrations/grants/:grantId/revoke` as the web's AgentAppAccessTab sends and reads it
 * (botiverse/slock [AGENT_APP_REVOKE_SLOCK_SHA]: routes/integrations.ts; the 4xx bodies are
 * lib/refusal.ts `refusalBody`, the 500 routes/errorResponse.ts `sendJsonServerError`).
 */
class AgentAppGrantRevokeApiTest {
    @Test
    fun aRevokeIsOneForegroundPostOfTheWebsEmptyBodyPerGrant() =
        runTest {
            val network = ScriptedAgentControlsNetworkClient()
            network.respond("POST", "integrations/grants/$GRANT_ID/revoke", 200, REVOKED_GRANT_ROW)

            val outcome = RaftIntegrationsActionsPushApiClient(RaftIntegrationsActionsPushNetworkApi(network)).revokeIntegrationGrant(GRANT_ID)

            assertEquals(AgentAppGrantRevokeOutcome.Revoked, outcome)
            val request = network.requests.single()
            assertEquals(DataRequestPriority.Foreground, request.priority)
            assertEquals("POST", request.method)
            assertEquals("integrations/grants/$GRANT_ID/revoke", request.path)
            assertEquals(JsonObject(emptyMap()), Json.parseToJsonElement(request.bodyText.orEmpty()))
        }

    @Test
    fun everyAnswerReadsAsTheWebReadsItsStatus() {
        val traced = mapOf("X-Trace-Id" to listOf(TRACE_ID))
        val cases =
            listOf(
                "200, the revoked grant row" to
                    (HomeNetworkResponse(code = 200, bodyText = REVOKED_GRANT_ROW) to AgentAppGrantRevokeOutcome.Revoked),
                // The web reads nothing of a 2xx body.
                "204, no body" to (HomeNetworkResponse(code = 204, bodyText = null) to AgentAppGrantRevokeOutcome.Revoked),
                "404 Grant not found: gone, nothing to retry" to
                    (failure(404, NOT_FOUND_BODY, traced) to AgentAppGrantRevokeOutcome.Gone),
                "403: refused, with the server's reason and trace id" to
                    (
                        failure(403, FORBIDDEN_BODY, traced) to
                            AgentAppGrantRevokeOutcome.NotRevoked(
                                AgentAppGrantRevokeFailure.Refused(
                                    reason = "Only server owners and admins can manage integrations",
                                    traceId = TRACE_ID
                                )
                            )
                        ),
                "500: failed, with the server's reason and trace id" to
                    (
                        failure(500, SERVER_ERROR_BODY, traced) to
                            AgentAppGrantRevokeOutcome.NotRevoked(
                                AgentAppGrantRevokeFailure.Failed(reason = "Failed to revoke integration grant", traceId = TRACE_ID)
                            )
                        ),
                "502 from the edge, no envelope" to
                    (
                        failure(502, "<html>Bad gateway</html>", traced) to
                            AgentAppGrantRevokeOutcome.NotRevoked(AgentAppGrantRevokeFailure.Failed(reason = null, traceId = TRACE_ID))
                        ),
                "no response at all" to
                    (
                        HomeNetworkResponse(code = 0, bodyText = null) to
                            AgentAppGrantRevokeOutcome.NotRevoked(AgentAppGrantRevokeFailure.NoAnswer)
                        ),
                // SharedRaftNetworkClient's timeout answer: still no answer from the server.
                "timed out" to
                    (
                        HomeNetworkResponse(code = 0, bodyText = null, errorBodyText = """{"error":"network_timeout","message":"timeout"}""") to
                            AgentAppGrantRevokeOutcome.NotRevoked(AgentAppGrantRevokeFailure.NoAnswer)
                        ),
            )
        cases.forEach { (case, expectation) ->
            val (response, expected) = expectation
            assertEquals(expected, response.toAgentAppGrantRevokeOutcome(), case)
        }
    }

    private fun failure(code: Int, body: String, headers: Map<String, List<String>>) =
        HomeNetworkResponse(code = code, bodyText = null, errorBodyText = body, responseHeaders = headers)
}

/** The slock SHA the revoke fixtures (here and in AgentAppGrantsRevokeStoreTest) were read from. */
internal const val AGENT_APP_REVOKE_SLOCK_SHA = "5828cdce3ec138d8c1c61400b712b434daa4ca4b"

private const val GRANT_ID = "7f8c2b1e-4d5a-4c3b-9e2f-1a6b8c9d0e1f"
private const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"

/** `res.json(updated)`: the `oauth_grants` row (db/schema.ts `oauthGrants`) after the revoke. */
private val REVOKED_GRANT_ROW =
    """
    {"id":"$GRANT_ID","serverId":"5b0c7a8e-1f2d-4e3c-8a9b-0c1d2e3f4a5b","agentId":"8e9267eb-92c0-470e-8fb4-6b7c71cda0d4",
    "clientId":"2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f","scopes":["identity","openid","profile"],"resource":null,
    "grantedByUserId":"0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","grantSource":"agent_login",
    "revokedByUserId":"0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","revokedAt":"2026-11-21T08:00:00.000Z",
    "createdAt":"2026-10-05T01:00:00.000Z","updatedAt":"2026-11-21T08:00:00.000Z"}
    """.trimIndent()

/** `refuse(res, 403, { code: "insufficient_role", ... })`. */
private const val FORBIDDEN_BODY =
    """{"error":"Only server owners and admins can manage integrations","code":"insufficient_role","errorCode":"insufficient_role"}"""

/** `refuse(res, 404, { code: "not_found", error: "Grant not found" })`. */
private const val NOT_FOUND_BODY = """{"error":"Grant not found","code":"not_found","errorCode":"not_found"}"""

/** `sendJsonServerError(req, res, { error: "Failed to revoke integration grant", ... })`. */
private const val SERVER_ERROR_BODY =
    """{"error":"Failed to revoke integration grant","correlationId":"4bf92f3577b34da6a3ce929d0e0e4736"}"""
