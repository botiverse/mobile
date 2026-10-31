package build.raft.app.data

import build.raft.app.MemberKindWireContract
import build.raft.app.SharedThreadStoreTest
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.memberDetailIdentityFromWire
import build.raft.app.memberKindWireValue
import build.raft.app.MemberDetailIdentitySource
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageNew
import build.raft.app.model.ThreadId
import build.raft.app.strictMemberKindOrNull
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * task #395: Slack-bridge external sender attribution (`senderType=external_projection`).
 *
 * Sender slots accept `external_projection` (and the agent-facing `third_party_app`) via
 * [MemberKindWireContract.MessageSender]; member slots (Identity/InternalActor) still reject it,
 * so an external sender can never be opened as a member profile or task assignee.
 *
 * 1.18 defence in depth: an External message is never dropped. A missing, partial or malformed
 * `externalAuthor` renders with a fallback author (the wire sender name, else the localized
 * "External user"), and a blank sender id becomes a synthetic, non-navigable key. User/agent
 * senders keep their strict rules.
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
        assertEquals("https://avatars.slack-edge.com/u123.png", message.author.avatar?.wireValue)
    }

    @Test
    fun restMessagesKeepExternalProjectionWithoutExternalAuthorWithFallbackAuthor() {
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

        val message = payload.messages.single()
        assertEquals("no author object", message.body)
        assertEquals(MemberKind.External, message.author.kind)
        assertEquals("ext_slack_U999", message.author.id.value)
        val author = assertNotNull(message.externalAuthor)
        assertTrue(author.displayNameIsFallback)
        assertEquals(EXTERNAL_AUTHOR_FALLBACK_DISPLAY_NAME, message.author.displayName)
        assertEquals("", author.provider)
        assertNull(message.author.description)
        assertTrue(diagnostics.any { "projection_value_defaulted" in it && "externalAuthor" in it })
    }

    @Test
    fun restMessagesKeepExternalProjectionWithMalformedExternalAuthor() {
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
                    "senderName": "Bridged Bob",
                    "messageType": "chat",
                    "content": "wrong type author object",
                    "createdAt": "2026-09-24T01:00:00.000Z",
                    "externalAuthor": "not-an-object"
                  }, {
                    "id": "m-user-1",
                    "seq": 6,
                    "channelId": "c1",
                    "senderType": "user",
                    "senderId": "u1",
                    "senderName": "Ada",
                    "messageType": "chat",
                    "content": "neighbour row",
                    "createdAt": "2026-09-24T01:00:01.000Z"
                  }]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("c1"),
            )

        assertEquals(listOf("m-ext-3", "m-user-1"), payload.messages.map { it.id.value })
        val external = payload.messages.first()
        assertEquals("Bridged Bob", external.author.displayName)
        assertEquals(false, assertNotNull(external.externalAuthor).displayNameIsFallback)
    }

    @Test
    fun restMessagesTreatThirdPartyAppSenderAsExternalAndKeepPartialAuthorFields() {
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [{
                    "id": "m-ext-4",
                    "seq": 7,
                    "channelId": "c1",
                    "senderType": "third_party_app",
                    "senderId": "",
                    "senderName": "",
                    "messageType": "chat",
                    "content": "anonymous bridge message",
                    "createdAt": "2026-09-24T01:00:00.000Z",
                    "externalAuthor": {
                      "provider": "slack",
                      "avatarUrl": "https://avatars.slack-edge.com/anon.png",
                      "externalActorId": "B42"
                    }
                  }]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("c1"),
            )

        val message = payload.messages.single()
        assertEquals(MemberKind.External, message.author.kind)
        // Blank wire id -> stable synthetic key from the real external actor id; never a member id.
        assertEquals("external:actor:B42", message.author.id.value)
        val author = assertNotNull(message.externalAuthor)
        assertTrue(author.displayNameIsFallback)
        assertEquals("slack", author.provider)
        assertEquals("B42", author.externalActorId)
        assertEquals("https://avatars.slack-edge.com/anon.png", message.author.avatar?.wireValue)
        assertEquals("Slack", message.author.description)
    }

    @Test
    fun threadRepliesKeepExternalSenderWithoutAuthorOrSenderId() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val replies =
            listOf(
                """{"id":"r-ext-1","channelId":"channel-1","senderType":"external_projection","senderId":"","messageType":"chat","content":"slack reply"}""",
                """{"id":"r-ext-2","channelId":"channel-1","senderType":"third_party_app","senderId":"ext-1","senderName":"Carol","messageType":"chat","content":"app reply","externalAuthor":{"displayName":"Carol (Slack)"}}""",
                """{"id":"r-ext-3","channelId":"channel-1","senderType":"external_projection","senderId":"ext-2","messageType":"chat","content":"bad author","externalAuthor":[1,2]}""",
            ).map { json.decodeFromString(WireMessage.serializer(), it) }
        val diagnostics = mutableListOf<String>()

        val payload =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = SharedThreadStoreTest.basePayload(),
                page = ThreadMessagesPage(messages = replies, canLoadMoreBefore = false, canLoadMoreAfter = null),
                session = null,
                diagnostics = diagnostics::add,
            )

        assertEquals(listOf("r-ext-1", "r-ext-2", "r-ext-3"), payload.messages.map { it.id })
        val anonymous = payload.messages[0]
        assertEquals("external", anonymous.authorKind)
        assertEquals("external:message:r-ext-1", anonymous.authorId)
        assertEquals(EXTERNAL_AUTHOR_FALLBACK_DISPLAY_NAME, anonymous.authorName)
        assertTrue(assertNotNull(anonymous.externalAuthor).displayNameIsFallback)
        assertNull(anonymous.authorDescription)
        assertEquals("Carol (Slack)", payload.messages[1].authorName)
        assertEquals("ext-1", payload.messages[1].authorId)
        assertNotNull(payload.messages[2].externalAuthor)
        assertTrue(diagnostics.any { "source=thread_wire_sender field=externalAuthor value=missing" in it })
    }

    @Test
    fun realtimeEventMapperParsesMessageNewWithExternalAuthor() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "m-rt-1",
                  "seq": 10,
                  "channelId": "c1",
                  "senderType": "external_projection",
                  "senderId": "ext_slack_U123",
                  "senderName": "Slack User",
                  "messageType": "chat",
                  "content": "realtime slack message",
                  "createdAt": "2026-09-24T01:00:00.000Z",
                  "externalAuthor": {
                    "provider": "slack",
                    "actorKind": "human",
                    "displayName": "jinlu",
                    "avatarUrl": "https://avatars.slack-edge.com/u123.png",
                    "externalActorId": "U123"
                  }
                }
                """.trimIndent(),
            )

        val messageNew = assertIs<MessageNew>(event)
        val external = assertNotNull(messageNew.message.externalAuthor)
        assertEquals("jinlu", external.displayName)
        assertEquals("slack", external.provider)
        assertEquals(MemberKind.External, messageNew.message.author.kind)
        assertEquals("Slack", messageNew.message.author.description)
        assertEquals("https://avatars.slack-edge.com/u123.png", messageNew.message.author.avatar?.wireValue)
    }

    @Test
    fun realtimeEventMapperKeepsExternalProjectionWithoutExternalAuthor() {
        val diagnostics = mutableListOf<String>()
        val event =
            RealtimeEventMapper(diagnosticsLogger = diagnostics::add).map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "m-rt-2",
                  "seq": 11,
                  "channelId": "c1",
                  "senderType": "external_projection",
                  "senderId": "ext_slack_U999",
                  "senderName": "Slack Sam",
                  "messageType": "chat",
                  "content": "missing external author",
                  "createdAt": "2026-09-24T01:00:00.000Z"
                }
                """.trimIndent(),
            )

        val message = assertIs<MessageNew>(event).message
        assertEquals("missing external author", message.body)
        assertEquals(MemberKind.External, message.author.kind)
        assertEquals("ext_slack_U999", message.author.id.value)
        assertEquals("Slack Sam", message.author.displayName)
        assertEquals("Slack Sam", assertNotNull(message.externalAuthor).displayName)
        assertTrue(diagnostics.any { "projection_value_defaulted" in it && "externalAuthor" in it })
    }

    @Test
    fun realtimeEventMapperKeepsExternalSenderWithBlankSenderId() {
        for (senderIdField in listOf(""""senderId": "",""", """"senderId": null,""", "")) {
            val event =
                RealtimeEventMapper(diagnosticsLogger = {}).map(
                    RealtimeSocketEvents.MessageNew,
                    """
                    {
                      "id": "m-rt-3",
                      "seq": 12,
                      "channelId": "c1",
                      "senderType": "third_party_app",
                      $senderIdField
                      "messageType": "chat",
                      "content": "anonymous slack message",
                      "createdAt": "2026-09-24T01:00:00.000Z",
                      "externalAuthor": { "provider": "slack", "projectionId": "proj-9" }
                    }
                    """.trimIndent(),
                )

            val message = assertIs<MessageNew>(event, "senderId field: $senderIdField").message
            assertEquals(MemberKind.External, message.author.kind)
            assertEquals("external:projection:proj-9", message.author.id.value)
            val author = assertNotNull(message.externalAuthor)
            assertTrue(author.displayNameIsFallback)
            assertEquals("Slack", message.author.description)
            // The synthetic key never resolves to a member-detail identity.
            assertNull(
                memberDetailIdentityFromWire(
                    memberId = message.author.id.value,
                    kindWireValue = message.author.kind.memberKindWireValue(),
                    source = MemberDetailIdentitySource.ThreadMessageAuthor,
                    diagnostics = {},
                )
            )
        }
    }

    @Test
    fun realtimeEventMapperStillDropsUserOrAgentWithBlankSenderId() {
        for (senderType in listOf("user", "agent")) {
            val diagnostics = mutableListOf<String>()
            val event =
                RealtimeEventMapper(diagnosticsLogger = diagnostics::add).map(
                    RealtimeSocketEvents.MessageNew,
                    """
                    {
                      "id": "m-rt-4",
                      "seq": 13,
                      "channelId": "c1",
                      "senderType": "$senderType",
                      "senderId": "  ",
                      "messageType": "chat",
                      "content": "no sender id",
                      "createdAt": "2026-09-24T01:00:00.000Z",
                      "externalAuthor": { "displayName": "spoof", "provider": "slack" }
                    }
                    """.trimIndent(),
                )

            assertNull(event, senderType)
            assertTrue(diagnostics.any { "field=member_id value=blank" in it }, senderType)
        }
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
        assertEquals(
            MemberKind.External,
            "third_party_app".strictMemberKindOrNull(
                source = "test_message_sender",
                diagnostics = diagnostics::add,
                contract = MemberKindWireContract.MessageSender,
            )
        )
        // third_party_app is a message-sender name only: member slots keep rejecting it.
        for (contract in listOf(MemberKindWireContract.Identity, MemberKindWireContract.InternalActor)) {
            assertNull("third_party_app".strictMemberKindOrNull(source = "test", diagnostics = diagnostics::add, contract = contract))
        }
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
}
