package build.raft.app.data

import build.raft.app.AskSeatsOwner
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.sortAskSeatsOwners
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Non-owner Pro seat requests (`POST /api/actions/seat-requests`, `botiverse/slock` `#11507`,
 * behind `pro_seat_add`).
 */
class SeatRequestApiTest {
    private val validTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"

    @Test
    fun encodesAllPurposeVariantsAndOmitsBlankOptionalFields() {
        val joinBody =
            Json.parseToJsonElement(
                encodeSeatRequestBody(
                    purpose = SeatRequestPurpose.Join(userId = "user-grace", name = "Grace Hopper"),
                    ownerUserId = "owner-1",
                    reason = "  Joining the mobile team  ",
                )
            ).jsonObject
        assertEquals("owner-1", joinBody["ownerUserId"]?.jsonPrimitive?.content)
        assertEquals("Joining the mobile team", joinBody["reason"]?.jsonPrimitive?.content)
        val joinPurpose = joinBody["purpose"]!!.jsonObject
        assertEquals("join", joinPurpose["kind"]?.jsonPrimitive?.content)
        assertEquals("user-grace", joinPurpose["userId"]?.jsonPrimitive?.content)
        assertEquals("Grace Hopper", joinPurpose["name"]?.jsonPrimitive?.content)

        // Hidden roster: ownerUserId omitted; blank reason omitted.
        val assistantBody =
            Json.parseToJsonElement(
                encodeSeatRequestBody(
                    purpose = SeatRequestPurpose.AssistantCreate,
                    ownerUserId = null,
                    reason = "   ",
                )
            ).jsonObject
        assertNull(assistantBody["ownerUserId"])
        assertNull(assistantBody["reason"])
        assertEquals("assistant:create", assistantBody["purpose"]!!.jsonObject["kind"]?.jsonPrimitive?.content)

        val agentBody =
            Json.parseToJsonElement(
                encodeSeatRequestBody(
                    purpose = SeatRequestPurpose.AgentCreate("scout"),
                    ownerUserId = "owner-1",
                    reason = "x".repeat(SEAT_REQUEST_REASON_MAX_LENGTH + 40),
                )
            ).jsonObject
        assertEquals(SEAT_REQUEST_REASON_MAX_LENGTH, agentBody["reason"]?.jsonPrimitive?.content?.length)
        assertEquals("agent:create", agentBody["purpose"]!!.jsonObject["kind"]?.jsonPrimitive?.content)
        assertEquals("scout", agentBody["purpose"]!!.jsonObject["name"]?.jsonPrimitive?.content)

        val inviteBody =
            Json.parseToJsonElement(
                encodeSeatRequestBody(
                    purpose = SeatRequestPurpose.Invite(listOf(" a@example.com ", "", "b@example.com")),
                    ownerUserId = "owner-1",
                )
            ).jsonObject
        val emails = inviteBody["purpose"]!!.jsonObject["emails"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a@example.com", "b@example.com"), emails)

        val promoteBody =
            Json.parseToJsonElement(
                encodeSeatRequestBody(
                    purpose = SeatRequestPurpose.MemberPromote(userId = "guest-1", name = "Lin", role = "admin"),
                    ownerUserId = "owner-1",
                )
            ).jsonObject
        assertEquals("member:promote", promoteBody["purpose"]!!.jsonObject["kind"]?.jsonPrimitive?.content)
        assertEquals("admin", promoteBody["purpose"]!!.jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun parsesCreatedExistingAndErrorResponses() {
        val created =
            parseSeatRequestResponse(
                statusCode = 201,
                bodyText = """{"channelId":"dm-123","messageId":"msg-1","existing":false}""",
            )
        val createdContent = assertIs<DataResult.Content<SeatRequestResponse>>(created)
        assertEquals(SeatRequestResponse(channelId = "dm-123", messageId = "msg-1", existing = false), createdContent.data)

        val existing =
            parseSeatRequestResponse(
                statusCode = 200,
                bodyText = """{"channelId":"dm-123","messageId":"msg-1","existing":true}""",
            )
        assertTrue(assertIs<DataResult.Content<SeatRequestResponse>>(existing).data.existing)

        val mappings =
            listOf(
                400 to "seat_request_invalid",
                403 to "seat_request_forbidden",
                404 to "seat_request_not_found",
                409 to "seat_request_conflict",
                429 to "seat_request_too_many_open",
                500 to "seat_request_http_500",
            )
        for ((status, expectedCode) in mappings) {
            val result =
                parseSeatRequestResponse(
                    statusCode = status,
                    bodyText = """{"error":"Server refusal $status","code":"ERR_$status"}""",
                    traceId = validTraceId,
                )
            val err = assertIs<DataResult.Error>(result)
            assertEquals(expectedCode, err.error.code)
            assertEquals(status >= 500, err.error.retryable)
            assertEquals("Server refusal $status", err.error.serverMessage)
            assertEquals("ERR_$status", err.error.serverCode)
            assertEquals(validTraceId, err.error.traceId)
        }

        val missingChannel = parseSeatRequestResponse(201, """{"messageId":"msg-1"}""", traceId = validTraceId)
        assertEquals("seat_request_contract_mismatch", assertIs<DataResult.Error>(missingChannel).error.code)
    }

    @Test
    fun networkApiPostsStrictPayloadToActionsSeatRequests() = runTest {
        var capturedMethod: String? = null
        var capturedPath: String? = null
        var capturedServerId: ServerId? = null
        var capturedBody: String? = null
        val client =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse {
                    capturedMethod = method
                    capturedPath = path
                    capturedServerId = serverIdOverride
                    capturedBody = bodyText
                    return HomeNetworkResponse(
                        code = 201,
                        bodyText = """{"channelId":"dm-owner-1","messageId":"msg-9","existing":false}""",
                        responseHeaders = mapOf("X-Trace-Id" to listOf(validTraceId)),
                    )
                }
            }

        val result =
            NetworkSeatRequestApi(client).requestSeats(
                serverId = ServerId("srv-pro"),
                purpose = SeatRequestPurpose.Join(userId = "user-grace", name = "Grace Hopper"),
                ownerUserId = "owner-ada",
                reason = "Needs access for launch",
            )
        val content = assertIs<DataResult.Content<SeatRequestResponse>>(result)
        assertEquals("dm-owner-1", content.data.channelId)
        assertEquals("POST", capturedMethod)
        assertEquals("actions/seat-requests", capturedPath)
        assertEquals(ServerId("srv-pro"), capturedServerId)
        val parsedBody = Json.parseToJsonElement(capturedBody!!).jsonObject
        assertEquals("owner-ada", parsedBody["ownerUserId"]?.jsonPrimitive?.content)
        assertEquals("join", parsedBody["purpose"]!!.jsonObject["kind"]?.jsonPrimitive?.content)
        assertEquals("Needs access for launch", parsedBody["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun sortAskSeatsOwnersPutsCreatorFirstThenAlphabeticalAndExcludesNonOwnersAndAgents() {
        val members =
            listOf(
                MemberProfile(id = MemberId("u-zoe"), displayName = "Zoe", handle = "zoe", kind = MemberKind.Human, serverRole = "owner"),
                MemberProfile(id = MemberId("u-admin"), displayName = "Alice", handle = "alice", kind = MemberKind.Human, serverRole = "admin"),
                MemberProfile(id = MemberId("a-bot"), displayName = "Bot", handle = "bot", kind = MemberKind.Agent, serverRole = "owner"),
                MemberProfile(
                    id = MemberId("u-creator"),
                    displayName = "Martin",
                    handle = "martin",
                    kind = MemberKind.Human,
                    serverRole = "owner",
                    avatar = AvatarReference.Url("https://cdn.example/m.png"),
                ),
                MemberProfile(id = MemberId("u-ben"), displayName = "Ben", handle = "ben", kind = MemberKind.Human, serverRole = "owner"),
            )
        val sorted = sortAskSeatsOwners(members, creatorUserId = "u-creator")
        assertEquals(
            listOf(
                AskSeatsOwner("u-creator", "Martin", "https://cdn.example/m.png", isCreator = true),
                AskSeatsOwner("u-ben", "Ben", null, isCreator = false),
                AskSeatsOwner("u-zoe", "Zoe", null, isCreator = false),
            ),
            sorted,
        )
    }
}
