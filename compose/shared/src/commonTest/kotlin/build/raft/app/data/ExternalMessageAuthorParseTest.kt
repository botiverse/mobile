package build.raft.app.data

import build.raft.app.MemberKindWireContract
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.memberDetailIdentityFromWire
import build.raft.app.memberKindWireValue
import build.raft.app.MemberDetailIdentitySource
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageNew
import build.raft.app.model.ThreadId
import build.raft.app.strictMemberKindOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * task #395: Slack-bridge external sender attribution (`senderType=external_projection`).
 *
 * Sender slots accept `external_projection` via [MemberKindWireContract.MessageSender] and
 * require an `externalAuthor` object; member slots (Identity/InternalActor) still reject it,
 * so an external sender can never be opened as a member profile or task assignee.
 *
 * Phase 1 scope: REST (`/api/messages`) and realtime (`message:new`) ingestion. The offline
 * canonical/V2 sync domain keeps InternalActor (fail-closed) until the SHA-locked
 * canonicalMessageManifest is upgraded — see PR #2595 review.
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
        assertEquals("https://avatars.slack-edge.com/u123.png", messageNew.message.author.avatarUrl)
    }

    @Test
    fun realtimeEventMapperDropsExternalProjectionWithoutExternalAuthor() {
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
                  "messageType": "chat",
                  "content": "missing external author",
                  "createdAt": "2026-09-24T01:00:00.000Z"
                }
                """.trimIndent(),
            )

        assertNull(event)
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
}
