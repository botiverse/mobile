package build.raft.app.data

import build.raft.app.model.ChannelId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `POST channels/:id/connect-my-servers`, pinned to botiverse/slock `staging` @
 * 44ce160f14951fcd7555c880f5066155bf756e91:
 *  - `routes/channels.ts` `channelRouter.post("/:id/connect-my-servers")`: body `{ serverIds }`,
 *    answer `{ servers: result.servers.map(serializeServerConnectOutcome) }`; the whole request
 *    refused with `channelNotFound` (404 `{ error: "Channel not found" }`), `MemberConnectRefusal`
 *    (400 `channel_not_invitable`, 409 `guest_has_computer`) or 400 `invalid_input`.
 *  - `routes/externalInvites.ts` `serializeServerConnectOutcome` / `externalConnectRefusal`: a
 *    refused server carries `status` and the body it would have had alone (`error`, `code`, and
 *    `limitSide` / `limitServerId` for a limit), `internal_error` 500 for an unexpected one.
 *  - `routes/jointMemberConnect.api.test.ts`: `connected` (L164), `server_admin_required` (L220),
 *    404 unknown / flag off (L223, L242), 400 `channel_not_invitable` (L252), 409
 *    `guest_has_computer` (L314), `joint_server_limit` beside a connected one (L362), `connecting`
 *    on a private channel (L409), 409 `joint_channel_free_limit_reached` (L712).
 */
class JointMemberConnectApiTest {
    @Test
    fun bodyIsTheServerIdsInTheOrderChosen() {
        assertEquals("""{"serverIds":["s-b","s-a"]}""", buildSharedMemberConnectBody(listOf("s-b", "s-a")))
    }

    @Test
    fun everyOutcomeStateParsesAsSent() {
        val outcomes = assertNotNull(parseSharedMemberConnectOutcomes(PARTIAL_ANSWER))
        assertEquals(
            listOf(
                ServerConnectOutcome.Connected("srv-sg", "ch-sg-copy"),
                ServerConnectOutcome.Connecting("srv-sp"),
                ServerConnectOutcome.Refused(
                    serverId = "srv-member",
                    status = 403,
                    code = "server_admin_required",
                    error = "Only an owner or admin of that server can connect it",
                ),
                ServerConnectOutcome.Refused("srv-sg2", 409, "joint_server_limit", "This channel can't take any more servers."),
                ServerConnectOutcome.Refused(
                    serverId = "srv-free",
                    status = 409,
                    code = "free_external_limit",
                    error = "This server already has an external channel.",
                    limitSide = "connecting",
                    limitServerId = "srv-free",
                ),
                ServerConnectOutcome.Refused("srv-boom", 500, "internal_error", "Failed to connect the server"),
            ),
            outcomes,
        )
    }

    @Test
    fun aStateTheClientDoesNotKnowReadsAsARefusalAndABodyWithoutServersIsNotAnAnswer() {
        val outcomes = assertNotNull(parseSharedMemberConnectOutcomes("""{"servers":[{"serverId":"s1","state":"queued"}]}"""))
        assertEquals(listOf(ServerConnectOutcome.Refused("s1", status = 0, code = null, error = null)), outcomes)
        // No envelope sniffing: the answer is `{ servers }`, never a bare list or `{ outcomes }`.
        assertNull(parseSharedMemberConnectOutcomes("""[{"serverId":"s1","state":"connected","channelId":"c"}]"""))
        assertNull(parseSharedMemberConnectOutcomes("""{"outcomes":[{"serverId":"s1","state":"connected"}]}"""))
    }

    @Test
    fun answeredRequestKeepsItsTraceIdAndPostsToTheCallersCopy() = runTest {
        val client =
            RecordingJointNetworkClient { _, _ ->
                HomeNetworkResponse(200, PARTIAL_ANSWER, responseHeaders = mapOf("X-Trace-Id" to listOf(TRACE)))
            }
        val result = client.connectMyServers(ChannelId("ch-copy"), listOf("srv-sg", "srv-member"))
        val answered = assertIs<MemberConnectResponse.Answered>(assertIs<DataResult.Content<MemberConnectResponse>>(result).data)
        assertEquals(TRACE, answered.traceId)
        assertEquals(6, answered.servers.size)
        val call = client.calls.single()
        assertEquals("channels/ch-copy/connect-my-servers", call.path)
        assertEquals("POST", call.method)
        assertEquals(DataRequestPriority.Foreground, call.priority)
        assertEquals("""{"serverIds":["srv-sg","srv-member"]}""", call.body)
    }

    @Test
    fun aRefusedRequestKeepsItsStatusCodeAndTraceId() = runTest {
        data class Case(val status: Int, val body: String, val code: String?)
        listOf(
            Case(404, """{"error":"Channel not found"}""", null),
            Case(400, """{"code":"channel_not_invitable","error":"Only private channels and channels shared with other servers can take a server from their members"}""", "channel_not_invitable"),
            Case(400, """{"code":"invalid_input","error":"serverIds must be a list of 1 to 29 servers"}""", "invalid_input"),
            Case(409, """{"code":"guest_has_computer","error":"You have a computer attached to this channel's host server. Disconnect it there first, then connect your server."}""", "guest_has_computer"),
            Case(429, "", null),
        ).forEach { case ->
            val client =
                RecordingJointNetworkClient { _, _ ->
                    HomeNetworkResponse(case.status, null, errorBodyText = case.body, responseHeaders = mapOf("X-Trace-Id" to listOf(TRACE)))
                }
            val refused =
                assertIs<MemberConnectResponse.Refused>(
                    assertIs<DataResult.Content<MemberConnectResponse>>(client.connectMyServers(ChannelId("c"), listOf("s"))).data
                )
            assertEquals(case.status, refused.status)
            assertEquals(case.code, refused.code, "status=${case.status}")
            assertEquals(TRACE, refused.traceId)
        }
    }

    @Test
    fun signedOutIsTheAppsAuthBlocker() = runTest {
        val client = RecordingJointNetworkClient { _, _ -> HomeNetworkResponse(401, null, errorBodyText = """{"error":"Unauthorized"}""") }
        val result = assertIs<DataResult.NoPermission>(client.connectMyServers(ChannelId("c"), listOf("s")))
        assertEquals("auth_required", result.blocker.code)
    }

    private companion object {
        const val TRACE = "0123456789abcdef0123456789abcdef"

        val PARTIAL_ANSWER =
            """
            {"servers":[
              {"serverId":"srv-sg","state":"connected","channelId":"ch-sg-copy"},
              {"serverId":"srv-sp","state":"connecting"},
              {"serverId":"srv-member","state":"refused","status":403,"error":"Only an owner or admin of that server can connect it","code":"server_admin_required"},
              {"serverId":"srv-sg2","state":"refused","status":409,"error":"This channel can't take any more servers.","code":"joint_server_limit"},
              {"serverId":"srv-free","state":"refused","status":409,"error":"This server already has an external channel.","code":"free_external_limit","limitSide":"connecting","limitServerId":"srv-free"},
              {"serverId":"srv-boom","state":"refused","status":500,"error":"Failed to connect the server","code":"internal_error"}
            ]}
            """.trimIndent()
    }
}
