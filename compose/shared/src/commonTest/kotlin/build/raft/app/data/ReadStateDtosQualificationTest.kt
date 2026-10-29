package build.raft.app.data

import build.raft.app.data.generated.MarkConversationUnreadResponseDto
import build.raft.app.data.generated.MarkUnreadOutcomeDto
import build.raft.app.data.generated.ReadMutationPendingResponseDto
import build.raft.app.data.generated.ReadStateErrorResponseDto
import build.raft.app.model.ChannelId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KMP generator qualification for the generated `readState` DTOs (`data/generated/ReadStateDtos.kt`,
 * from botiverse/slock `packages/shared/openapi/openapi.json` at #11854 6893779e): field names,
 * nullability and defaults, forward/backward compatibility, and the error matrix of
 * `POST /api/channels/{channelId}/unread`. Payloads are the server's own: the 200 answers asserted by
 * `packages/server/src/routes/channels.markedUnread.api.test.ts`, the 202/403/404 bodies written by
 * `routes/channels.ts` (`/:id/unread`, `sendCompatibilityReadPending`), the 500 body by
 * `routes/errorResponse.ts` `sendJsonServerError`.
 */
class ReadStateDtosQualificationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun generatedShapesKeepContractNamesNullabilityAndDefaults() {
        // (DTO, contract field -> optional-with-null-default?) — required fields are non-null, no default.
        val cases: List<Pair<KSerializer<*>, Map<String, Boolean>>> =
            listOf(
                MarkConversationUnreadResponseDto.serializer() to
                    mapOf(
                        "ok" to false,
                        "unreadCount" to false,
                        "maxReadSeq" to false,
                        "readStateVersion" to false,
                        // Required by the contract, nullable for servers older than #11795.
                        "markedUnread" to true,
                        "outcome" to true,
                    ),
                ReadMutationPendingResponseDto.serializer() to
                    listOf("code", "status", "outcome", "mutationId", "authoritySeq", "frontierUrl").associateWith { false },
                ReadStateErrorResponseDto.serializer() to mapOf("error" to false, "code" to true),
            )
        cases.forEach { (serializer, fields) ->
            val descriptor = serializer.descriptor
            val name = descriptor.serialName
            assertEquals(fields.keys.toList(), (0 until descriptor.elementsCount).map(descriptor::getElementName), name)
            fields.values.forEachIndexed { index, optional ->
                val field = "$name.${descriptor.getElementName(index)}"
                assertEquals(optional, descriptor.isElementOptional(index), "$field has a default")
                assertEquals(optional, descriptor.getElementDescriptor(index).isNullable, "$field is nullable")
            }
        }
    }

    @Test
    fun outcomeWireValuesRoundTripAndUnknownValuesDoNotFailTheAnswer() {
        val known =
            mapOf(
                "counted" to MarkUnreadOutcomeDto.Counted,
                "marked" to MarkUnreadOutcomeDto.Marked,
                "nothing" to MarkUnreadOutcomeDto.Nothing,
            )
        known.forEach { (wire, value) ->
            assertEquals(value, json.decodeFromString(MarkUnreadOutcomeDto.serializer(), "\"$wire\""), wire)
            assertEquals("\"$wire\"", json.encodeToString(MarkUnreadOutcomeDto.serializer(), value), wire)
        }
        assertEquals(known.values.toSet() + MarkUnreadOutcomeDto.Unknown, MarkUnreadOutcomeDto.entries.toSet())

        val newer =
            json.decodeFromString(
                MarkConversationUnreadResponseDto.serializer(),
                """{"ok":true,"unreadCount":0,"maxReadSeq":12,"readStateVersion":9,"markedUnread":true,"outcome":"snoozed"}""",
            )
        assertEquals(MarkUnreadOutcomeDto.Unknown, newer.outcome)
        assertEquals(true, newer.markedUnread)
        assertFailsWith<Exception>("Unknown is never sent") {
            json.encodeToString(MarkUnreadOutcomeDto.serializer(), MarkUnreadOutcomeDto.Unknown)
        }
    }

    @Test
    fun okAnswersFromThisAndOlderServersParse() {
        // (case, body, expected DTO)
        val cases =
            listOf(
                Triple(
                    "this server (#11854)",
                    """{"ok":true,"unreadCount":0,"maxReadSeq":12,"readStateVersion":9,"markedUnread":true,"outcome":"marked"}""",
                    MarkConversationUnreadResponseDto(true, 0, 12, 9, true, MarkUnreadOutcomeDto.Marked),
                ),
                Triple(
                    "before outcome (#11795)",
                    """{"ok":true,"unreadCount":2,"maxReadSeq":10,"readStateVersion":9,"markedUnread":false}""",
                    MarkConversationUnreadResponseDto(true, 2, 10, 9, false, null),
                ),
                Triple(
                    "before markedUnread",
                    """{"ok":true,"unreadCount":0,"maxReadSeq":0,"readStateVersion":0}""",
                    MarkConversationUnreadResponseDto(true, 0, 0, 0, null, null),
                ),
            )
        cases.forEach { (name, body, expected) ->
            assertEquals(expected, json.decodeFromString(MarkConversationUnreadResponseDto.serializer(), body), name)
            val result = parseMarkConversationUnreadResult(body, ChannelId("c")) ?: error("dropped: $name")
            assertEquals(expected.outcome, result.serverMarkUnreadOutcome, name)
            assertEquals(expected.markedUnread, result.markedUnread, name)
            assertEquals(expected.readStateVersion, result.readStateVersion, name)
            assertEquals(expected.maxReadSeq, result.maxReadSeq, name)
        }
        assertNull(
            parseMarkConversationUnreadResult("""{"ok":true,"maxReadSeq":0,"readStateVersion":0}""", ChannelId("c")),
            "a required field that every served version sends is still required",
        )
    }

    @Test
    fun errorMatrixOfTheUnreadRoute() {
        // (status, body the server writes, expected error code)
        val errors =
            listOf(
                Triple("403 fence", """{"error":"Not authorized","code":"READ_MUTATION_FENCE_REFUSED"}""", "READ_MUTATION_FENCE_REFUSED"),
                Triple("404 fence", """{"error":"Resource not found","code":"READ_MUTATION_FENCE_REFUSED"}""", "READ_MUTATION_FENCE_REFUSED"),
                Triple("404 channel", """{"error":"Channel not found"}""", null),
                // Not declared in the OpenAPI responses (catch-all `sendJsonServerError`); decoded leniently.
                Triple(
                    "500",
                    """{"error":"Failed to mark as unread","correlationId":"6b3c0a3e-1f40-4d7e-9a51-0b8d2f7c9e11"}""",
                    null
                ),
            )
        errors.forEach { (status, body, code) ->
            val error = json.decodeFromString(ReadStateErrorResponseDto.serializer(), body)
            assertTrue(error.error.isNotBlank(), status)
            assertEquals(code, error.code, status)
            assertNull(parseMarkConversationUnreadResult(body, ChannelId("c")), "$status is not an ok answer")
        }

        val pendingBody =
            """{"code":"READ_MUTATION_PENDING","status":"admitted","outcome":"unknown",""" +
                """"mutationId":"0d4f8a52-8c1e-4b8f-9f0e-3a6c2b1d5e77","authoritySeq":41,""" +
                """"frontierUrl":"/api/read-mutations/frontier?mutationId=0d4f8a52-8c1e-4b8f-9f0e-3a6c2b1d5e77"}"""
        val pending = json.decodeFromString(ReadMutationPendingResponseDto.serializer(), pendingBody)
        assertEquals("READ_MUTATION_PENDING", pending.code)
        assertEquals(41L, pending.authoritySeq)
        assertTrue(pending.frontierUrl.endsWith(pending.mutationId))
        assertFailsWith<Exception>("a 202 body is not a 200 answer: `outcome: unknown` must not pass as one") {
            json.decodeFromString(MarkConversationUnreadResponseDto.serializer(), pendingBody)
        }
        // Today's reading of a 202: no count, no mark, no outcome.
        val admitted = parseMarkConversationUnreadResult(pendingBody, ChannelId("c")) ?: error("202 dropped")
        assertEquals(ReadReceiptResult(channelId = ChannelId("c"), changed = true), admitted)
        assertEquals(MarkUnreadOutcome.NothingToMark, admitted.markUnreadOutcome())
    }
}
