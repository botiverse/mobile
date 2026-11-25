package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * External invite wire vectors, copied from botiverse/slock staging
 * 0e8b4fab7440307f58c64e3448e820f517359194 (`packages/server/src/routes/externalInvites.api.test.ts`
 * "preview classifies ...", `services/externalInviteService.ts` `ListedExternalInvite`,
 * `routes/channels.ts` `sendExternalInviteError` / `sendExternalLimitError`).
 */
class ExternalInvitesApiTest {
    @Test
    fun previewParsesTheServersClassification() {
        val preview =
            parseSharedExternalInvitePreview(
                """
                {"valid":["new@example.com","outsider@example.com"],"duplicates":["new@example.com"],
                 "invalid":["not-an-email"],"alreadyPending":["pending@example.com"],"alreadyMembers":[],
                 "alreadyInChannel":["member@example.com","guest@example.com"],
                 "guestsElsewhere":[{"email":"g@example.com","channelName":null},{"email":"h@example.com","channelName":"vendor"}],
                 "slotsLeft":27}
                """.trimIndent()
            )!!
        assertEquals(listOf("new@example.com", "outsider@example.com"), preview.valid)
        assertEquals(listOf("new@example.com"), preview.duplicates)
        assertEquals(listOf("not-an-email"), preview.invalid)
        assertEquals(listOf("pending@example.com"), preview.alreadyPending)
        assertEquals(listOf("member@example.com", "guest@example.com"), preview.alreadyInChannel)
        assertEquals(listOf(null, "vendor"), preview.guestsElsewhere.map { it.channelName })
        assertEquals(27, preview.slotsLeft)
    }

    @Test
    fun anOlderServersPreviewLeavesTheNewFieldsEmpty() {
        val preview =
            parseSharedExternalInvitePreview("""{"valid":["a@example.com"],"duplicates":[],"invalid":[],"alreadyPending":[],"alreadyMembers":[]}""")!!
        assertEquals(emptyList(), preview.alreadyInChannel)
        assertEquals(emptyList(), preview.alreadyInChannelMembers)
        assertEquals(emptyList(), preview.self)
        assertEquals(emptyList(), preview.guestsElsewhere)
        assertNull(preview.slotsLeft)
    }

    @Test
    fun previewParsesSelfAndAlreadyInChannelMembers() {
        val preview =
            parseSharedExternalInvitePreview(
                """
                {"valid":["out@example.com"],"duplicates":[],"invalid":[],"alreadyPending":[],"alreadyMembers":["m@example.com"],
                 "alreadyInChannel":["guest@example.com","in-member@example.com"],
                 "alreadyInChannelMembers":["in-member@example.com"],
                 "self":["me@example.com"],
                 "guestsElsewhere":[],"slotsLeft":25}
                """.trimIndent()
            )!!
        assertEquals(listOf("me@example.com"), preview.self)
        assertEquals(listOf("in-member@example.com"), preview.alreadyInChannelMembers)
        assertEquals(listOf("guest@example.com", "in-member@example.com"), preview.alreadyInChannel)
    }

    @Test
    fun theListReadsEachOpenInviteAndTreatsAMissingRevocableAsRevocable() {
        val list =
            parseSharedChannelExternalInviteList(
                """
                {"invites":[
                  {"id":"i1","email":"a@example.com","awaitingApproval":false,"connecting":true,"connectPhase":"conversion",
                   "revocable":false,"invitedBy":{"id":"u1","name":"Owner"},"createdAt":"2026-11-01T00:00:00.000Z",
                   "expiresAt":"2026-11-15T00:00:00.000Z"},
                  {"id":"i2","email":"b@example.com","awaitingApproval":true,"connecting":false,"invitedBy":null,
                   "createdAt":"2026-11-02T00:00:00.000Z","expiresAt":"2026-11-16T00:00:00.000Z"}
                ],"slotsLeft":12}
                """.trimIndent()
            )!!
        assertEquals(listOf("i1", "i2"), list.invites.map { it.id })
        assertTrue(list.invites[0].connecting)
        assertEquals("conversion", list.invites[0].connectPhase)
        assertEquals(false, list.invites[0].revocable)
        assertEquals("Owner", list.invites[0].invitedBy?.name)
        assertTrue(list.invites[1].awaitingApproval)
        assertEquals(true, list.invites[1].revocable)
        assertNull(list.invites[1].invitedBy)
        assertEquals(12, list.slotsLeft)
    }

    @Test
    fun theSendAnswerNamesWhoWasInvitedAndWhoWasAdded() {
        val result =
            parseSharedSendExternalInvitesResult(
                """
                {"sent":[{"id":"i1","email":"a@example.com","expiresAt":"2026-11-25T00:00:00.000Z"}],"added":["m@example.com"],
                 "skipped":{"duplicates":[],"invalid":[],"alreadyPending":[],"alreadyMembers":[],"alreadyInChannel":[],"guestsElsewhere":[]}}
                """.trimIndent()
            )!!
        assertEquals(listOf("a@example.com"), result.sent.map { it.email })
        assertEquals(listOf("m@example.com"), result.added)
    }

    @Test
    fun aLimitRefusalCarriesItsCodeSideAndTraceId() {
        val refusal =
            parseSharedExternalInviteRefusal(
                409,
                """{"error":"Free plan limit","code":"free_external_limit","limitSide":"host","limitServerId":"s1"}""",
                "0123456789abcdef0123456789abcdef",
            )
        assertEquals("free_external_limit", refusal.code)
        assertEquals("host", refusal.limitSide)
        assertEquals("0123456789abcdef0123456789abcdef", refusal.traceId)
        // The flag off: `{ error: "Not found" }`, no code.
        assertNull(parseSharedExternalInviteRefusal(404, """{"error":"Not found"}""", null).code)
    }

    @Test
    fun theEmailsBodyNamesMakePrivateOnlyWhenSet() {
        val plain = Json.parseToJsonElement(buildSharedExternalInviteEmailsBody(listOf("a@example.com"))).jsonObject
        assertEquals(setOf("emails"), plain.keys)
        assertEquals(listOf("a@example.com"), plain.getValue("emails").jsonArray.map { it.jsonPrimitive.content })
        val private = Json.parseToJsonElement(buildSharedExternalInviteEmailsBody(listOf("a@example.com"), makePrivate = true)).jsonObject
        assertEquals(true, private.getValue("makePrivate").jsonPrimitive.boolean)
    }

    @Test
    fun pastedTextSplitsOnCommasSemicolonsSpacesAndNewLines() {
        assertEquals(
            listOf("a@example.com", "b@example.com", "c", "d@example.com"),
            parseExternalInviteEmailEntries(" a@example.com, b@example.com;c\n\nd@example.com  "),
        )
        assertEquals(emptyList(), parseExternalInviteEmailEntries("  ,; \n"))
    }

    private class Recorded(val path: String, val method: String, val body: String?)

    private class RecordingClient(private val answer: HomeNetworkResponse) : RaftNetworkClient {
        val calls = mutableListOf<Recorded>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            calls += Recorded(path, method, bodyText)
            return answer
        }
    }

    /**
     * "Your servers" (botiverse/slock staging 97a3e293d71a, `routes/channels.ts`
     * `GET /external-connect-servers`, `externalInviteMultiConnect.api.test.ts` `ownServers`).
     */
    @Test
    fun yourServersAreReadFromTheChannelsRouteNotAChannel() = runTest {
        val client =
            RecordingClient(
                HomeNetworkResponse(
                    200,
                    """
                    {"servers":[
                      {"id":"s2","name":"Beta","slug":"beta","avatarUrl":null,"canConnectDirectly":true,"needsApproval":false,"approvers":[],"atLimit":false},
                      {"id":"s3","name":"Gamma","slug":"gamma","avatarUrl":"https://x/g.png","canConnectDirectly":true,"needsApproval":false,"approvers":[],"atLimit":true,"canUpgrade":true},
                      {"id":"","name":"Blank"}
                    ]}
                    """.trimIndent(),
                ),
            )
        val answer = assertIs<DataResult.Content<ExternalInviteAnswer<OwnConnectableServers>>>(ExternalInvitesApi(client).ownConnectableServers())
        val servers = assertIs<ExternalInviteAnswer.Ok<OwnConnectableServers>>(answer.data).value.servers
        assertEquals(listOf("s2", "s3"), servers.map { it.id })
        assertEquals(listOf(false, true), servers.map { it.atLimit })
        assertEquals("https://x/g.png", servers[1].avatarUrl)
        assertEquals("channels/external-connect-servers", client.calls.single().path)
        assertEquals("GET", client.calls.single().method)
        assertNull(client.calls.single().body)
    }

    /** `POST channels/:id/external-connect-servers` `{ serverIds, makePrivate? }` → `{ servers: ServerConnectOutcome[] }`. */
    @Test
    fun connectingYourServersPostsTheirIdsAndReadsEachOutcome() = runTest {
        val client =
            RecordingClient(
                HomeNetworkResponse(
                    200,
                    """
                    {"servers":[
                      {"serverId":"s2","state":"connected","channelId":"c2"},
                      {"serverId":"s3","state":"connecting"},
                      {"serverId":"s4","state":"refused","status":409,"error":"limit","code":"free_external_limit","limitSide":"connecting","limitServerId":"s4"}
                    ]}
                    """.trimIndent(),
                    responseHeaders = mapOf("X-Trace-Id" to listOf("0123456789abcdef0123456789abcdef")),
                ),
            )
        val api = ExternalInvitesApi(client)
        val answer = assertIs<DataResult.Content<ExternalInviteAnswer<List<ServerConnectOutcome>>>>(
            api.connectOwnServers(ChannelId("c1"), listOf("s2", "s3", "s4"), makePrivate = true)
        )
        val ok = assertIs<ExternalInviteAnswer.Ok<List<ServerConnectOutcome>>>(answer.data)
        assertIs<ServerConnectOutcome.Connected>(ok.value[0])
        assertIs<ServerConnectOutcome.Connecting>(ok.value[1])
        val refused = assertIs<ServerConnectOutcome.Refused>(ok.value[2])
        assertEquals("free_external_limit", refused.code)
        assertEquals("s4", refused.limitServerId)
        assertEquals("0123456789abcdef0123456789abcdef", ok.traceId)

        val call = client.calls.single()
        assertEquals("channels/c1/external-connect-servers", call.path)
        assertEquals("POST", call.method)
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals(listOf("s2", "s3", "s4"), body.getValue("serverIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(true, body.getValue("makePrivate").jsonPrimitive.boolean)
    }

    /** `GET channels/external-availability` → `{ freeLimitReached }` (staging 97a3e293d71a). */
    @Test
    fun theAvailabilityIsReadFromTheChannelsRoute() = runTest {
        val client = RecordingClient(HomeNetworkResponse(200, """{"freeLimitReached":true}"""))
        val answer = assertIs<DataResult.Content<ExternalInviteAnswer<ExternalChannelAvailability>>>(ExternalInvitesApi(client).externalAvailability())
        assertEquals(true, assertIs<ExternalInviteAnswer.Ok<ExternalChannelAvailability>>(answer.data).value.freeLimitReached)
        assertEquals("channels/external-availability", client.calls.single().path)
        assertEquals("GET", client.calls.single().method)
    }

    /** Create Channel's External review: `POST channels/external-invites/preview` `{ emails }`, no channel id. */
    @Test
    fun theNewChannelPreviewPostsTheEmailsToTheChannelsRoute() = runTest {
        val client = RecordingClient(HomeNetworkResponse(200, """{"valid":["a@example.com"],"duplicates":[],"invalid":[],"alreadyPending":[],"alreadyMembers":[]}"""))
        val answer = assertIs<DataResult.Content<ExternalInviteAnswer<ExternalInvitePreview>>>(ExternalInvitesApi(client).previewForNewChannel(listOf("a@example.com")))
        assertEquals(listOf("a@example.com"), assertIs<ExternalInviteAnswer.Ok<ExternalInvitePreview>>(answer.data).value.valid)
        val call = client.calls.single()
        assertEquals("channels/external-invites/preview", call.path)
        assertEquals("POST", call.method)
        assertEquals(setOf("emails"), Json.parseToJsonElement(call.body!!).jsonObject.keys)
    }
}
