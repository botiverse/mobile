package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
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
    }

    @Test
    fun theTokenIsAPathSegment() {
        assertEquals("abc-DEF_123", "abc-DEF_123".encodeInviteTokenSegment())
        assertEquals("a%2Fb%20c", "a/b c".encodeInviteTokenSegment())
    }
}
