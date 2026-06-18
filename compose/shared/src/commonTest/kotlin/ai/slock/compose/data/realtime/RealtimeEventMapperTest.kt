package ai.slock.compose.data.realtime

import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageType
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RealtimeEventMapperTest {
    @Test
    fun messageUpdatedMapsReactionPayload() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageUpdated,
                """
                {
                  "id": "message-1",
                  "channelId": "channel-1",
                  "senderType": "user",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "content": "hello",
                  "reactions": [
                    {
                      "emoji": "👀",
                      "count": 1,
                      "reactorIds": ["u2"],
                      "reactorNames": ["Cindy"]
                    }
                  ]
                }
                """.trimIndent()
            )

        val updated = assertIs<MessageUpdated>(event)
        val reaction = updated.message.reactions?.single()
        assertEquals("👀", reaction?.emoji)
        assertEquals(1, reaction?.count)
        assertEquals(listOf("u2"), reaction?.reactorIds)
        assertEquals(listOf("Cindy"), reaction?.reactorNames)
    }

    @Test
    fun messageUpdatedPreservesKnownEmptyReactionPayload() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageUpdated,
                """
                {
                  "id": "message-1",
                  "channelId": "channel-1",
                  "senderType": "user",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "content": "hello",
                  "reactions": []
                }
                """.trimIndent()
            )

        val updated = assertIs<MessageUpdated>(event)
        assertEquals(emptyList(), updated.message.reactions)
    }

    @Test
    fun messageNewRawPayloadMapsWithFullFields() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "msg-1",
                  "channelId": "ch-1",
                  "senderType": "user",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "senderMembershipStatus": "active",
                  "content": "hello world",
                  "attachments": [
                    {
                      "id": "att-1",
                      "filename": "photo.png",
                      "mimeType": "image/png",
                      "sizeBytes": 1024,
                      "width": 800,
                      "height": 600,
                      "thumbnailUrl": "https://example.com/thumb.png"
                    }
                  ],
                  "reactions": [
                    { "emoji": "👍", "count": 2, "reactorIds": ["u2"], "reactorNames": ["Bob"] }
                  ]
                }
                """.trimIndent()
            )

        val messageNew = assertIs<MessageNew>(event)
        val message = messageNew.message
        assertEquals("msg-1", message.id.value)
        assertEquals("Artin", message.author.displayName)
        assertEquals("active", message.senderMembershipStatus)
        assertEquals("hello world", message.body)

        val attachment = message.attachments.single()
        assertEquals("att-1", attachment.id.value)
        assertEquals("photo.png", attachment.name)
        assertEquals("image/png", attachment.mimeType)
        assertEquals("1024 B", attachment.sizeLabel)
        assertEquals(800, attachment.width)
        assertEquals(600, attachment.height)
        assertEquals("https://example.com/thumb.png", attachment.thumbnailUrl)

        val reaction = message.reactions?.single()
        assertEquals("👍", reaction?.emoji)
        assertEquals(2, reaction?.count)
        assertEquals(listOf("u2"), reaction?.reactorIds)
    }

    @Test
    fun messageNewPreservesThreadSystemMessageType() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "sys-done-1",
                  "channelId": "thread-1",
                  "senderType": "user",
                  "senderId": "system",
                  "senderName": "System",
                  "messageType": "system",
                  "content": "✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done",
                  "createdAt": "2026-06-13T01:00:00Z",
                  "seq": 44
                }
                """.trimIndent()
            )

        val message = assertIs<MessageNew>(event).message
        assertEquals("sys-done-1", message.id.value)
        assertEquals("thread-1", message.channelId?.value)
        assertEquals(MessageType.System, message.messageType)
        assertEquals(MemberKind.System, message.author.kind)
        assertEquals("✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done", message.body)
    }

    @Test
    fun messageNewRejectsEnvelopePayload() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "message": {
                    "id": "msg-1",
                    "channelId": "ch-1",
                    "senderType": "user",
                    "senderId": "u1",
                    "content": "hello"
                  }
                }
                """.trimIndent()
            )

        assertNull(event)
    }

    @Test
    fun directMessageNewOnlyMapsChannelCreatedPayload() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.DirectMessageNew,
                """{"channelId":"dm-1"}"""
            )

        val created = assertIs<DirectMessageCreated>(event)
        assertEquals("dm-1", created.channelId.value)
    }

    @Test
    fun directMessageNewRejectsMessagePayload() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.DirectMessageNew,
                """
                {
                  "id": "msg-1",
                  "channelId": "dm-1",
                  "senderType": "user",
                  "senderId": "u1",
                  "content": "hello"
                }
                """.trimIndent()
            )

        assertNull(event)
    }

    @Test
    fun agentActivityPreservesServerSeq() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentActivity,
                """
                {
                  "agentId": "agent-1",
                  "activity": "thinking",
                  "detail": "Planning",
                  "serverSeq": 42
                }
                """.trimIndent()
            )

        val presence = assertIs<MemberPresenceUpdated>(event)
        assertEquals("agent-1", presence.memberId.value)
        assertEquals(Presence.Active, presence.presence)
        assertEquals(MemberKind.Agent, presence.kind)
        assertEquals(AgentStatus.Thinking, presence.agentStatus)
        assertEquals("Planning", presence.statusLabel)
        assertEquals(42L, presence.serverSeq)
    }
}
