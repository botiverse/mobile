package build.raft.app.network.sync

import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.canonical.message.TaskDomainFields
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
    fun threeProducerFacesExposeTheirRawAggregateKeys() {
        val messageNew = decodeCanonicalMessageWire(
            requiredRow(
                "senderName" to JsonPrimitive("Ada"),
                "senderDescription" to JsonNull,
                "senderMembershipStatus" to JsonNull,
                "mentions" to buildJsonArray {},
                "attachments" to buildJsonArray {},
                "reactions" to buildJsonArray {},
                "commentRef" to JsonNull,
                "actionMetadata" to buildJsonObject {},
                "conversationContext" to buildJsonObject { put("channelType", "dm") },
                "externalAuthor" to JsonNull,
            ),
            ProductionFace.MessageNew,
        )
        val enriched = decodeCanonicalMessageWire(
            requiredRow(
                "senderName" to JsonPrimitive("Ada"),
                "senderDescription" to JsonNull,
                "senderMembershipStatus" to JsonNull,
                "mentions" to buildJsonArray {},
                "attachments" to buildJsonArray {},
                "reactions" to buildJsonArray {},
                "commentRef" to JsonNull,
                "actionMetadata" to buildJsonObject {},
                "externalAuthor" to JsonNull,
            ),
            ProductionFace.EnrichedUpdated,
        )
        val task = decodeCanonicalMessageWire(
            requiredRow(
                "senderName" to JsonPrimitive("Ada"),
                "actionMetadata" to buildJsonObject {},
            ),
            ProductionFace.TaskStatusUpdated,
        )

        assertEquals(MessageDomainEvent.PRESENT_MESSAGE_NEW, assertNotNull(messageNew).presentAggregates)
        assertEquals(MessageDomainEvent.PRESENT_ENRICHED_UPDATED, assertNotNull(enriched).presentAggregates)
        assertEquals(MessageDomainEvent.PRESENT_TASK_STATUS_UPDATED, assertNotNull(task).presentAggregates)
    }

    @Test
    fun nestedWireShapesDecodeWithoutAppDomainProjection() {
        val decoded = decodeCanonicalMessageWire(
            requiredRow(
                "senderName" to JsonPrimitive("Ada"),
                "attachments" to buildJsonArray {
                    add(buildJsonObject {
                        put("id", "a1")
                        put("filename", "photo.png")
                        put("mimeType", "image/png")
                        put("sizeBytes", 42L)
                        put("width", 10)
                        put("height", 20)
                        put("thumbnailUrl", "https://example.test/thumb")
                        put("commentCount", 99)
                    })
                },
                "mentions" to buildJsonArray {
                    add(buildJsonObject { put("id", "u2"); put("name", "Lin"); put("type", "human") })
                },
                "reactions" to buildJsonArray {
                    add(buildJsonObject {
                        put("emoji", ":+1:")
                        put("count", 2)
                        put("reactorIds", buildJsonArray { add("u1"); add("u2") })
                        put("reactorNames", buildJsonArray { add("Ada"); add("Lin") })
                    })
                },
                "commentRef" to buildJsonObject {
                    put("attachmentId", "a1")
                    put("filename", "photo.png")
                    put("anchorQuote", "quoted")
                    put("hostSource", buildJsonObject {
                        put("type", "message")
                        put("routeKind", "thread")
                        put("channelId", "c1")
                    })
                },
            ),
            ProductionFace.EnrichedUpdated,
        )

        val row = assertNotNull(decoded).row
        assertEquals(42L, row.attachments.single().sizeBytes)
        assertEquals("Lin", row.mentions.single().name)
        assertEquals(listOf("u1", "u2"), row.reactions.single().reactorIds)
        assertEquals("quoted", row.commentRef?.anchorQuote)
        assertEquals("thread", row.commentRef?.hostSource?.routeKind)
    }

    @Test
    fun externalAuthorDecodesWireShapeAndKeepsAbsentDistinctFromNull() {
        val author = buildJsonObject {
            put("provider", "slack")
            put("actorKind", "human")
            put("displayName", "jinlu")
            put("avatarUrl", JsonNull)
            put("avatarDigest", JsonNull)
            put("projectionId", "proj-1")
            put("externalActorId", "U123")
            put("externalConversationId", "C42")
            put("externalMessageId", "1700000000.000100")
            put("workspaceId", "T1")
            put("workspaceName", "Acme")
            put("installId", "inst-1")
            put("appRegistrationId", "app-1")
            put("actorProjectionRevision", 7)
        }
        val decoded = assertNotNull(
            decodeCanonicalMessageWire(
                requiredRow("senderType" to JsonPrimitive("external_projection"), "externalAuthor" to author),
                ProductionFace.EnrichedUpdated,
            ),
        )
        assertTrue(MessageAggregateField.ExternalAuthor in decoded.presentAggregates)
        val wire = assertNotNull(decoded.row.externalAuthor)
        assertEquals("jinlu", wire.displayName)
        assertEquals(7L, wire.actorProjectionRevision)
        assertEquals("Acme", wire.workspaceName)
        assertNull(wire.avatarUrl)

        val presentNull = assertNotNull(decodeCanonicalMessageWire(requiredRow("externalAuthor" to JsonNull), ProductionFace.EnrichedUpdated))
        assertTrue(MessageAggregateField.ExternalAuthor in presentNull.presentAggregates)
        assertNull(presentNull.row.externalAuthor)
        val absent = assertNotNull(decodeCanonicalMessageWire(requiredRow(), ProductionFace.EnrichedUpdated))
        assertFalse(MessageAggregateField.ExternalAuthor in absent.presentAggregates)

        // A partial author is kept, never nulled: a missing or mistyped leaf is stored blank (revision 0)
        // and every real field survives, so the message still shows who it came from.
        val missingLeaf = buildJsonObject { author.forEach { (k, v) -> if (k != "installId") put(k, v) } }
        val keptMissing = assertNotNull(
            assertNotNull(decodeCanonicalMessageWire(requiredRow("externalAuthor" to missingLeaf), ProductionFace.EnrichedUpdated)).row.externalAuthor,
        )
        assertEquals("", keptMissing.installId)
        assertEquals("jinlu", keptMissing.displayName)
        assertEquals("proj-1", keptMissing.projectionId)
        val stringRevision = buildJsonObject { author.forEach { (k, v) -> put(k, v) }; put("actorProjectionRevision", "7") }
        val keptRevision = assertNotNull(
            assertNotNull(decodeCanonicalMessageWire(requiredRow("externalAuthor" to stringRevision), ProductionFace.EnrichedUpdated)).row.externalAuthor,
        )
        assertEquals(0L, keptRevision.actorProjectionRevision)
        assertEquals("slack", keptRevision.provider)
        val onlyName = buildJsonObject { put("displayName", "Bridge Bot") }
        val keptName = assertNotNull(
            assertNotNull(decodeCanonicalMessageWire(requiredRow("externalAuthor" to onlyName), ProductionFace.EnrichedUpdated)).row.externalAuthor,
        )
        assertEquals("Bridge Bot", keptName.displayName)
        assertEquals("", keptName.provider)
        // A non-object value is still no author (the projection then builds the fallback).
        assertNull(
            assertNotNull(decodeCanonicalMessageWire(requiredRow("externalAuthor" to JsonPrimitive("x")), ProductionFace.EnrichedUpdated)).row.externalAuthor,
        )
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
    fun taskFieldsAreDualDispatchOnlyAndNeverEnterCanonicalRow() {
        val decoded = decodeCanonicalMessageWire(
            requiredRow(
                "senderName" to JsonPrimitive("Ada"),
                "actionMetadata" to buildJsonObject {},
                "taskStatus" to JsonPrimitive("in_progress"),
                "taskNumber" to JsonPrimitive(17),
                "taskAssigneeType" to JsonPrimitive("agent"),
                "taskAssigneeId" to JsonPrimitive("agent-1"),
                "taskClaimedAt" to JsonPrimitive("t1"),
                "taskCompletedAt" to JsonNull,
            ),
            ProductionFace.TaskStatusUpdated,
        )

        assertEquals(
            TaskDomainFields(
                taskStatus = "in_progress",
                taskNumber = 17,
                taskAssigneeType = "agent",
                taskAssigneeId = "agent-1",
                taskClaimedAt = "t1",
                taskCompletedAt = null,
            ),
            assertNotNull(decoded).taskFields,
        )
        assertEquals(MessageDomainEvent.PRESENT_TASK_STATUS_UPDATED, decoded.presentAggregates)
        assertEquals("task body", decoded.row.content)
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
