package build.raft.app.data

import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
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
        assertEquals(emptyList(), preview.guestsElsewhere)
        assertNull(preview.slotsLeft)
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
}
