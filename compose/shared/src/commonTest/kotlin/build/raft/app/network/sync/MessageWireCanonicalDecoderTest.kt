package build.raft.app.network.sync

import build.raft.app.canonical.message.MessageAggregateField
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageWireCanonicalDecoderTest {

    @Test
    fun aggregatePresenceDistinguishesAbsentFromPresentNull() {
        val absent = decodeCanonicalMessageWire(requiredRow(), ProductionFace.EnrichedUpdated)
        val presentNull = decodeCanonicalMessageWire(
            requiredRow("commentRef" to JsonNull),
            ProductionFace.EnrichedUpdated,
        )

        assertNotNull(absent)
        assertNotNull(presentNull)
        assertFalse(MessageAggregateField.CommentRef in absent.presentAggregates)
        assertTrue(MessageAggregateField.CommentRef in presentNull.presentAggregates)
        assertNull(presentNull.row.commentRef)
    }

    @Test
    fun externalSenderWithoutSenderIdIsKeptButUserAndAgentStillFailClosed() {
        for (externalType in listOf("external_projection", "third_party_app")) {
            for (senderId in listOf<kotlinx.serialization.json.JsonElement?>(null, JsonNull)) {
                val raw = buildJsonObject {
                    requiredRow("senderType" to JsonPrimitive(externalType)).forEach { (key, value) ->
                        if (key != "senderId") put(key, value)
                    }
                    if (senderId != null) put("senderId", senderId)
                }
                val violations = mutableListOf<MessageWireCanonicalViolation>()
                val decoded = assertNotNull(decodeCanonicalMessageWire(raw, ProductionFace.MessageNew, violations::add))
                assertEquals("", decoded.row.senderId)
                assertTrue(violations.isEmpty())
                assertNotNull(decoded.row.toAppMessage(diagnostics = {}))
            }
        }
        for (memberType in listOf("user", "agent")) {
            val raw = buildJsonObject {
                requiredRow("senderType" to JsonPrimitive(memberType)).forEach { (key, value) ->
                    if (key != "senderId") put(key, value)
                }
            }
            val violations = mutableListOf<MessageWireCanonicalViolation>()
            assertNull(decodeCanonicalMessageWire(raw, ProductionFace.MessageNew, violations::add))
            assertEquals(setOf("senderId"), violations.single().missingOrInvalidRequiredFields)
        }
    }

    @Test
    fun missingCanonicalRequiredFieldFailsClosedAndReportsViolation() {
        val violations = mutableListOf<MessageWireCanonicalViolation>()
        val malformed = buildJsonObject {
            requiredRow().forEach { (key, value) -> if (key != "seq") put(key, value) }
        }

        assertNull(decodeCanonicalMessageWire(malformed, ProductionFace.MessageNew, violations::add))
        assertEquals(setOf("seq"), violations.single().missingOrInvalidRequiredFields)
        assertEquals(ProductionFace.MessageNew, violations.single().face)
    }

    private fun requiredRow(vararg additions: Pair<String, kotlinx.serialization.json.JsonElement>) = buildJsonObject {
        put("id", "m1")
        put("seq", 7L)
        put("channelId", "c1")
        put("senderType", "user")
        put("senderId", "u1")
        put("content", "task body")
        put("messageType", "chat")
        put("randomId", JsonNull)
        put("threadId", JsonNull)
        put("createdAt", "2026-07-12T00:00:00Z")
        additions.forEach { (key, value) -> put(key, value) }
    }
}
