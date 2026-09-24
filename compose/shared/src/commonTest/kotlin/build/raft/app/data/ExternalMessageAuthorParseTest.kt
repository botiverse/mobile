package build.raft.app.data

import build.raft.app.MemberKindWireContract
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.memberDetailIdentityFromWire
import build.raft.app.memberKindWireValue
import build.raft.app.MemberDetailIdentitySource
import build.raft.app.model.MemberKind
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.decodeCanonicalMessageWire
import build.raft.app.network.sync.ProductionFace
import build.raft.app.network.sync.toAppMessage
import build.raft.app.strictMemberKindOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * task #395: Slack-bridge external sender attribution (`senderType=external_projection`).
 *
 * Sender slots accept `external_projection` via [MemberKindWireContract.MessageSender] and
 * require an `externalAuthor` aggregate; member slots (Identity/InternalActor) still reject it,
 * so an external sender can never be opened as a member profile or task assignee.
 */
class ExternalMessageAuthorParseTest {

    @Test
    fun restMessagesParsesExternalProjectionSenderWithAuthorAttribution() {
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [{
                    "id": "m-ext-1",
                    "seq": 3,
                    "channelId": "c1",
                    "senderType": "external_projection",
                    "senderId": "ext_slack_U123",
                    "senderName": "Wire display name",
                    "messageType": "chat",
                    "content": "hello from slack",
                    "createdAt": "2026-09-24T01:00:00.000Z",
                    "externalAuthor": {
                      "provider": "slack",
                      "actorKind": "human",
                      "displayName": "jinlu",
                      "avatarUrl": "https://avatars.slack-edge.com/u123.png",
                      "externalActorId": "U123",
                      "externalConversationId": "C456",
                      "externalMessageId": "1759.0001",
                      "installId": "inst-1",
                      "projectionId": "proj-1",
                      "actorProjectionRevision": 7,
                      "appRegistrationId": "app-1",
                      "workspaceId": "ws-1"
                    }
                  }]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("c1"),
            )

        val message = payload.messages.single()
        val external = assertNotNull(message.externalAuthor)
        assertEquals("jinlu", external.displayName)
        assertEquals("slack", external.provider)
        assertEquals("human", external.actorKind)
        assertEquals("U123", external.externalActorId)
        assertEquals("https://avatars.slack-edge.com/u123.png", external.avatarUrl)
        assertEquals(MemberKind.External, message.author.kind)
        assertEquals("jinlu", message.author.displayName)
        assertEquals("Slack", message.author.description)
        assertEquals("https://avatars.slack-edge.com/u123.png", message.author.avatarUrl)
    }

    @Test
    fun restMessagesDropsExternalProjectionWithoutExternalAuthor() {
        val diagnostics = mutableListOf<String>()
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [{
                    "id": "m-ext-2",
                    "seq": 4,
                    "channelId": "c1",
                    "senderType": "external_projection",
                    "senderId": "ext_slack_U999",
                    "messageType": "chat",
                    "content": "no author object",
                    "createdAt": "2026-09-24T01:00:00.000Z"
                  }]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("c1"),
                diagnostics = diagnostics::add,
            )

        assertTrue(payload.messages.isEmpty())
        assertTrue(diagnostics.any { "externalAuthor" in it })
    }

    @Test
    fun restMessagesDropsExternalProjectionWithMalformedExternalAuthor() {
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [{
                    "id": "m-ext-3",
                    "seq": 5,
                    "channelId": "c1",
                    "senderType": "external_projection",
                    "senderId": "ext_slack_U888",
                    "messageType": "chat",
                    "content": "wrong type author object",
                    "createdAt": "2026-09-24T01:00:00.000Z",
                    "externalAuthor": "not-an-object"
                  }]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("c1"),
            )

        assertTrue(payload.messages.isEmpty())
    }

    @Test
    fun canonicalWireCarriesExternalAuthorAggregatePresence() {
        val decoded =
            decodeCanonicalMessageWire(
                canonicalRow(
                    "senderType" to JsonPrimitive("external_projection"),
                    "externalAuthor" to externalAuthorJson(),
                ),
                ProductionFace.MessageNew,
            )

        assertNotNull(decoded)
        assertTrue(MessageAggregateField.ExternalAuthor in decoded.presentAggregates)
        val author = assertNotNull(decoded.row.externalAuthor)
        assertEquals("jinlu", author.displayName)
        assertEquals("slack", author.provider)
    }

    @Test
    fun canonicalRowProjectionRendersExternalAuthorAndSkipsMemberHydration() {
        val decoded =
            decodeCanonicalMessageWire(
                canonicalRow(
                    "senderType" to JsonPrimitive("external_projection"),
                    "externalAuthor" to externalAuthorJson(),
                ),
                ProductionFace.MessageNew,
            )
        assertNotNull(decoded)

        var hydrationCalls = 0
        val message =
            decoded.row.toAppMessage(
                memberHydration = { profile ->
                    hydrationCalls += 1
                    profile
                },
            )
        assertNotNull(message)
        assertEquals(0, hydrationCalls)
        val external = assertNotNull(message.externalAuthor)
        assertEquals("jinlu", external.displayName)
        assertEquals(MemberKind.External, message.author.kind)
        assertEquals("Slack", message.author.description)
    }

    @Test
    fun canonicalRowProjectionDropsExternalSenderWithoutAuthor() {
        val diagnostics = mutableListOf<String>()
        val decoded =
            decodeCanonicalMessageWire(
                canonicalRow(
                    "senderType" to JsonPrimitive("external_projection"),
                    "externalAuthor" to JsonNull,
                ),
                ProductionFace.MessageNew,
            )
        assertNotNull(decoded)

        val message = decoded.row.toAppMessage(diagnostics = diagnostics::add)
        assertNull(message)
        assertTrue(diagnostics.any { "externalAuthor" in it })
    }

    @Test
    fun identityAndInternalActorContractsRejectExternalProjection() {
        val diagnostics = mutableListOf<String>()

        assertNull(
            "external_projection".strictMemberKindOrNull(
                source = "test_identity",
                diagnostics = diagnostics::add,
                contract = MemberKindWireContract.Identity,
            )
        )
        assertNull(
            "external_projection".strictMemberKindOrNull(
                source = "test_internal_actor",
                diagnostics = diagnostics::add,
                contract = MemberKindWireContract.InternalActor,
            )
        )
        assertEquals(
            MemberKind.External,
            "external_projection".strictMemberKindOrNull(
                source = "test_message_sender",
                diagnostics = diagnostics::add,
                contract = MemberKindWireContract.MessageSender,
            )
        )
    }

    @Test
    fun memberDetailIdentityFromWireFailsClosedForExternalAuthor() {
        assertNull(
            memberDetailIdentityFromWire(
                memberId = "ext_slack_U123",
                kindWireValue = MemberKind.External.memberKindWireValue(),
                source = MemberDetailIdentitySource.ThreadMessageAuthor,
                diagnostics = {},
            )
        )
    }

    @Test
    fun externalAuthorDecodesWithMissingOptionalFields() {
        val decoded =
            decodeCanonicalMessageWire(
                canonicalRow(
                    "senderType" to JsonPrimitive("external_projection"),
                    "externalAuthor" to
                        buildJsonObject {
                            put("provider", "slack")
                            put("actorKind", "bot")
                            put("externalActorId", "B9")
                        },
                ),
                ProductionFace.MessageNew,
            )
        assertNotNull(decoded)
        val author = assertNotNull(decoded.row.externalAuthor)
        assertNull(author.displayName)
        assertNull(author.avatarUrl)
        assertEquals("bot", author.actorKind)
    }

    private fun externalAuthorJson(): JsonObject =
        buildJsonObject {
            put("provider", "slack")
            put("actorKind", "human")
            put("displayName", "jinlu")
            put("avatarUrl", "https://avatars.slack-edge.com/u123.png")
            put("externalActorId", "U123")
            put("externalConversationId", "C456")
            put("externalMessageId", "1759.0001")
            put("installId", "inst-1")
            put("projectionId", "proj-1")
            put("actorProjectionRevision", 7)
            put("appRegistrationId", "app-1")
            put("workspaceId", "ws-1")
        }

    private fun canonicalRow(vararg additions: Pair<String, kotlinx.serialization.json.JsonElement>) =
        buildJsonObject {
            put("id", "m1")
            put("seq", 7L)
            put("channelId", "c1")
            put("senderType", "user")
            put("senderId", "u1")
            put("content", "body")
            put("messageType", "chat")
            put("randomId", JsonNull)
            put("threadId", JsonNull)
            put("createdAt", "2026-09-24T01:00:00Z")
            additions.forEach { (key, value) -> put(key, value) }
        }
}
