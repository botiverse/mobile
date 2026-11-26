package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The invitee's wire vectors, copied from botiverse/slock staging
 * 0e8b4fab7440307f58c64e3448e820f517359194 (`packages/server/src/routes/externalInviteAccept.api.test.ts`,
 * `services/externalInviteService.ts` `ExternalInviteForInvitee` / `ConnectableServer`,
 * `routes/externalInvites.ts` `externalConnectRefusal`).
 */
class ExternalInviteAcceptApiTest {
    @Test
    fun anAccountTheInviteWasNotSentToReadsOnlyTheMaskedAddress() {
        // "the page tells a caller on load whether the invite is theirs, masking the address for anyone else"
        val page = parseSharedExternalInviteForInvitee("""{"addressedToYou":false,"invitedEmailMasked":"l•••@contoso.com"}""")!!
        assertFalse(page.addressedToYou)
        assertEquals("l•••@contoso.com", page.invitedEmailMasked)
        assertNull(page.options)
    }

    @Test
    fun thePageReadsTheWaysInAndEachConnectableServer() {
        val page =
            parseSharedExternalInviteForInvitee(
                """
                {"channelName":"launch","serverName":"Acme","inviterName":"Lena","expiresAt":"2026-11-25T00:00:00.000Z",
                 "status":"pending","addressedToYou":true,"channelFull":false,
                 "options":{"guest":true,"newServer":true,"connect":{"slotsLeft":2,"servers":[
                   {"id":"s1","name":"Contoso","slug":"contoso","avatarUrl":null,"canConnectDirectly":true,"needsApproval":false,
                    "approvers":[],"atLimit":false,"canUpgrade":false},
                   {"id":"s2","name":"Fabrikam","slug":"fabrikam","avatarUrl":null,"canConnectDirectly":false,"needsApproval":true,
                    "approvers":[{"id":"u1","name":"Sam","avatarUrl":null}],"atLimit":true,"canUpgrade":false}
                 ]}}}
                """.trimIndent()
            )!!
        assertTrue(page.addressedToYou)
        assertEquals("launch", page.channelName)
        assertEquals("Lena", page.inviterName)
        val options = page.options!!
        assertTrue(options.guest)
        assertTrue(options.newServer)
        assertEquals(2, options.connect.slotsLeft)
        assertEquals(listOf("s1", "s2"), options.connect.servers.map { it.id })
        assertTrue(options.connect.servers[0].canConnectDirectly)
        assertEquals(listOf("Sam"), options.connect.servers[1].approvers.map { it.name })
        assertTrue(options.connect.servers[1].atLimit)
    }

    @Test
    fun anAcceptedInviteTellsWhereItLanded() {
        val page =
            parseSharedExternalInviteForInvitee(
                """
                {"channelName":"launch","serverName":"Acme","inviterName":"Lena","expiresAt":"2026-11-25T00:00:00.000Z",
                 "status":"accepted","addressedToYou":true,
                 "accepted":{"outcome":"guest","serverId":"host","channelId":"c1","channelName":"launch"},
                 "options":{"guest":false,"connect":{"servers":[]},"newServer":false},"channelFull":false}
                """.trimIndent()
            )!!
        assertEquals("accepted", page.status)
        assertEquals("guest", page.accepted?.outcome)
        assertEquals("c1", page.accepted?.channelId)
    }

    @Test
    fun aWaitingInviteNamesTheRequestAndEachServerUnderWay() {
        val page =
            parseSharedExternalInviteForInvitee(
                """
                {"channelName":"launch","serverName":"Acme","inviterName":"Lena","expiresAt":"2026-11-25T00:00:00.000Z",
                 "status":"awaiting_approval","addressedToYou":true,
                 "pendingRequest":{"id":"r1","serverId":"s2","serverName":"Fabrikam","approverUserId":"u1","approverName":"Sam","expiresAt":"2026-11-25T00:00:00.000Z"},
                 "servers":[{"serverId":"s2","serverName":"Fabrikam","state":"awaiting_approval","requestId":"r1","approverName":"Sam"}],
                 "lastRequest":{"status":"rejected","decidedAt":null,"serverName":"Contoso","cancelReason":null},
                 "options":{"guest":false,"connect":{"servers":[]},"newServer":false},"channelFull":false}
                """.trimIndent()
            )!!
        assertEquals("Sam", page.pendingRequest?.approverName)
        assertEquals(listOf("awaiting_approval"), page.servers?.map { it.state })
        assertEquals("rejected", page.lastRequest?.status)
    }

    @Test
    fun aRefusalCarriesItsCodeSideKeptServerAndTraceId() {
        val refusal =
            parseSharedInviteeRefusal(
                409,
                """{"error":"limit","code":"free_external_limit","limitSide":"connecting","limitServerId":null,"serverId":"new-1"}""",
                "0123456789abcdef0123456789abcdef",
            )
        assertEquals("free_external_limit", refusal.code)
        assertEquals("connecting", refusal.limitSide)
        assertNull(refusal.limitServerId)
        assertEquals("new-1", refusal.serverId)
        assertEquals("0123456789abcdef0123456789abcdef", refusal.traceId)
        // The neutral 404: one body for every unusable invite.
        assertNull(parseSharedInviteeRefusal(404, """{"error":"This invite link is not valid"}""", null).code)
        // Refusal P3/P4: falls back to errorCode when code is absent.
        val fromErrorCode =
            parseSharedInviteeRefusal(
                409,
                """{"error":"Slug is already taken.","errorCode":"server_slug_taken"}""",
                null,
            )
        assertEquals("server_slug_taken", fromErrorCode.code)
        assertEquals("Slug is already taken.", fromErrorCode.error)
    }

    @Test
    fun theGuestAndNewServerAnswersAreRead() {
        assertEquals(GuestAcceptance("s", "c"), parseSharedGuestAcceptance("""{"serverId":"s","channelId":"c"}"""))
        assertEquals("connecting", parseSharedNewServerConnectResult("""{"state":"connecting","serverId":"n1"}""")?.state)
        assertNull(parseSharedNewServerConnectResult("""{"state":"connected"}"""))
    }

    @Test
    fun theConnectBodyNamesAnApproverOnlyForAServerThatNeedsOne() {
        val body =
            Json.parseToJsonElement(
                buildSharedInviteConnectServersBody(listOf(InviteServerSelection("s1"), InviteServerSelection("s2", "u1")))
            ).jsonObject
        val servers = body.getValue("servers").jsonArray.map { it.jsonObject }
        assertEquals(setOf("serverId"), servers[0].keys)
        assertEquals("u1", servers[1].getValue("approverUserId").jsonPrimitive.content)
        assertFalse("makePrivate" in body)
    }

    @Test
    fun hostMemberInChannelPublicChannelAndOwnersAreParsed() {
        val page =
            parseSharedExternalInviteForInvitee(
                """
                {"channelName":"launch","serverName":"Acme","inviterName":"Lena","expiresAt":"2026-11-25T00:00:00.000Z",
                 "status":"pending","addressedToYou":true,"channelFull":false,
                 "alreadyInChannel":true,
                 "openChannel":{"serverId":"host-1","channelId":"ch-1","channelName":"launch"},
                 "publicChannel":{"canMakePrivate":true},
                 "options":{"guest":false,"newServer":true,"connect":{"slotsLeft":4,"servers":[
                   {"id":"s1","name":"Contoso","slug":"contoso","canConnectDirectly":true,"needsApproval":false,
                    "approvers":[],"atLimit":true,"canUpgrade":false,"owners":[{"id":"o1","name":"Pat"}]}
                 ]}}}
                """.trimIndent()
            )!!
        assertTrue(page.alreadyInChannel)
        assertEquals(InviteOpenChannel("host-1", "ch-1", "launch"), page.openChannel)
        assertEquals(InvitePublicChannel(canMakePrivate = true), page.publicChannel)
        assertEquals(listOf(InviteApprover("o1", "Pat")), page.options?.connect?.servers?.single()?.owners)

        val older =
            parseSharedExternalInviteForInvitee(
                """{"channelName":"launch","serverName":"Acme","inviterName":"Lena","status":"pending","addressedToYou":true}"""
            )!!
        assertFalse(older.alreadyInChannel)
        assertNull(older.openChannel)
        assertNull(older.publicChannel)
    }

    @Test
    fun connectBodiesSerializeMakePrivateOnlyWhenTrueAndConnectResultParsesBothStates() {
        val singlePlain = Json.parseToJsonElement(buildSharedInviteConnectBody("s1")).jsonObject
        assertEquals(setOf("serverId"), singlePlain.keys)
        val singlePrivate = Json.parseToJsonElement(buildSharedInviteConnectBody("s1", makePrivate = true)).jsonObject
        assertEquals(true, singlePrivate.getValue("makePrivate").jsonPrimitive.boolean)

        val multiPrivate =
            Json.parseToJsonElement(
                buildSharedInviteConnectServersBody(listOf(InviteServerSelection("s1")), makePrivate = true)
            ).jsonObject
        assertEquals(true, multiPrivate.getValue("makePrivate").jsonPrimitive.boolean)

        val newPlain = Json.parseToJsonElement(buildSharedInviteNewServerBody("Beta", "beta")).jsonObject
        assertFalse("makePrivate" in newPlain)
        val newPrivate = Json.parseToJsonElement(buildSharedInviteNewServerBody("Beta", "beta", makePrivate = true)).jsonObject
        assertEquals(true, newPrivate.getValue("makePrivate").jsonPrimitive.boolean)

        assertEquals(
            InviteConnectResult(state = "connected", serverId = "s1", channelId = "c1"),
            parseSharedInviteConnectResult("""{"state":"connected","serverId":"s1","channelId":"c1"}"""),
        )
        assertEquals(
            InviteConnectResult(state = "connecting", serverId = "s1"),
            parseSharedInviteConnectResult("""{"state":"connecting","serverId":"s1"}"""),
        )
        assertNull(parseSharedInviteConnectResult("""{"state":"connected"}"""))
    }

    @Test
    fun theTokenIsAPathSegment() {
        assertEquals("abc-DEF_123", "abc-DEF_123".encodeInviteTokenSegment())
        assertEquals("a%2Fb%20c", "a/b c".encodeInviteTokenSegment())
    }
}
