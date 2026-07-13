package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageParserTest {
    @Test
    fun completeRawRowsExposeCanonicalCoverageInput() {
        val payload =
            parseSharedChannelMessages(
                """
                {"messages":[{
                  "id":"m-coverage","seq":31,"channelId":"channel-1",
                  "senderType":"user","senderId":"u-2","senderName":"User 2",
                  "messageType":"chat","content":"hello","randomId":null,"threadId":null,
                  "createdAt":"2026-07-14T00:00:00Z"
                }],"messageWindow":{"schemaVersion":1,"domain":"receiver_visible_messages_v1","serverId":"server-1","receiverKind":"user","receiverId":"user-1","scopeId":"channel-1","coveredAfterSeq":30,"coveredFromSeq":31,"coveredThroughSeq":31,"remoteHighWaterSeq":31,"hasGap":false,"hasNewer":false,"completeThroughLatest":true}}
                """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1"),
            )

        assertTrue(payload.canonicalRowsComplete)
        assertEquals(listOf("m-coverage"), payload.canonicalRows.map { it.id })
        assertEquals(listOf(31L), payload.canonicalRows.map { it.seq })
        assertEquals(30L, assertNotNull(payload.messageWindow).coveredAfterSeq)
    }

    @Test
    fun malformedOrUnknownMessageWindowFailsClosedWithoutDroppingLegacyRows() {
        val body =
            """{"messages":[{"id":"m-coverage","seq":31,"channelId":"channel-1","senderType":"user","senderId":"u-2","senderName":"User 2","messageType":"chat","content":"hello","randomId":null,"threadId":null,"createdAt":"2026-07-14T00:00:00Z"}],"messageWindow":{"schemaVersion":2,"domain":"receiver_visible_messages_v1","serverId":"server-1","receiverKind":"user","receiverId":"user-1","scopeId":"channel-1","coveredAfterSeq":31,"coveredFromSeq":31,"coveredThroughSeq":31,"remoteHighWaterSeq":31,"hasGap":false,"hasNewer":false,"completeThroughLatest":true}}"""

        val payload = parseSharedChannelMessages(body, fallbackThreadId = ThreadId("channel-1"))

        assertNull(payload.messageWindow)
        assertTrue(payload.canonicalRowsComplete)
        assertEquals(1, payload.messages.size)
    }

    @Test
    fun oneMalformedRawRowFailsCoverageClosedWithoutDroppingLegacyProjection() {
        val payload =
            parseSharedChannelMessages(
                """
                {"messages":[{
                  "id":"m-legacy","seq":31,"channelId":"channel-1",
                  "senderType":"user","senderId":"u-2","senderName":"User 2",
                  "messageType":"chat","content":"hello","randomId":null,"threadId":null,
                  "createdAt":"2026-07-14T00:00:00Z"
                },{
                  "id":"m-malformed","seq":32,"channelId":"channel-1",
                  "senderType":"user","senderId":"u-2","messageType":"chat",
                  "randomId":null,"threadId":null,"createdAt":"2026-07-14T00:00:01Z"
                }]}
                """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1"),
            )

        assertFalse(payload.canonicalRowsComplete)
        assertTrue(payload.canonicalRows.isEmpty())
        assertEquals(2, payload.messages.size, "legacy REST projection remains fail-open")
    }

    @Test
    fun parsesThreadSummaryIntoDomainMessageReplyMetadata() {
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [
                    {
                      "id": "m-1",
                      "seq": 10,
                      "channelId": "channel-1",
                      "senderType": "user",
                      "senderId": "u-1",
                      "senderName": "Artin",
                      "messageType": "chat",
                      "content": "parent",
                      "createdAt": "2026-06-06T00:00:00.000Z",
                      "threadSummary": {
                        "threadChannelId": "thread-1",
                        "replyCount": 4,
                        "unreadCount": 2,
                        "firstUnreadMessageId": "reply-2"
                      }
                    }
                  ],
                  "historyLimited": false
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1")
            )

        val message = payload.messages.single()
        assertEquals("thread-1", message.threadChannelId?.value)
        assertEquals(4, message.replyCount)
        assertEquals(2, message.unreadReplyCount)
        assertEquals("reply-2", message.firstUnreadReplyMessageId?.value)
    }

    @Test
    fun topLevelReplyCountsOverrideNestedThreadSummaryCounts() {
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [
                    {
                      "id": "m-1",
                      "channelId": "channel-1",
                      "senderType": "user",
                      "senderId": "u-1",
                      "messageType": "chat",
                      "content": "parent",
                      "createdAt": "2026-06-06T00:00:00.000Z",
                      "replyCount": 5,
                      "unreadReplyCount": 3,
                      "threadSummary": {
                        "threadChannelId": "thread-1",
                        "replyCount": 4,
                        "unreadCount": 2
                      }
                    }
                  ]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1")
            )

        val message = payload.messages.single()
        assertEquals("thread-1", message.threadChannelId?.value)
        assertEquals(5, message.replyCount)
        assertEquals(3, message.unreadReplyCount)
    }

    @Test
    fun preservesInitialMessageReactionsMembershipStatusAndAttachmentDimensions() {
        val payload =
            parseSharedChannelMessages(
                """
                {
                  "messages": [
                    {
                      "id": "m-2",
                      "channelId": "channel-1",
                      "senderType": "user",
                      "senderId": "u-1",
                      "senderName": "Artin",
                      "senderMembershipStatus": "removed",
                      "messageType": "chat",
                      "content": "image",
                      "randomId": "rid-parser-1",
                      "createdAt": "2026-06-06T00:00:00.000Z",
                      "attachments": [
                        {
                          "id": "att-1",
                          "filename": "diagram.png",
                          "mimeType": "image/png",
                          "sizeBytes": 2048,
                          "width": 640,
                          "height": 360
                        }
                      ],
                      "reactions": [
                        {
                          "emoji": "\uD83D\uDC40",
                          "count": 2,
                          "reactorIds": ["u-1", "agent-1"],
                          "reactorNames": ["Artin", "Bot"]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1")
            )

        val message = payload.messages.single()
        assertEquals("removed", message.senderMembershipStatus)
        assertEquals("rid-parser-1", message.randomId)

        val attachment = message.attachments.single()
        assertEquals(2048, attachment.sizeBytes)
        assertEquals("", attachment.sizeLabel)
        assertEquals(640, attachment.width)
        assertEquals(360, attachment.height)

        val reaction = assertNotNull(message.reactions).single()
        assertEquals("\uD83D\uDC40", reaction.emoji)
        assertEquals(2, reaction.count)
        assertEquals(listOf("u-1", "agent-1"), reaction.reactorIds)
        assertEquals(listOf("Artin", "Bot"), reaction.reactorNames)
    }

    @Test
    fun unknownSenderKindDropsOnlyInvalidRestMessageWithBoundedDiagnostic() {
        val diagnostics = mutableListOf<String>()
        val payload =
            parseSharedChannelMessages(
                bodyText =
                    """
                    {
                      "messages": [
                        {
                          "id": "bad-message-secret-id",
                          "channelId": "channel-1",
                          "senderType": "future sender kind with long suffix 123456789",
                          "senderId": "secret-user-id",
                          "messageType": "chat",
                          "content": "secret body"
                        },
                        {
                          "id": "bad-system-sender",
                          "channelId": "channel-1",
                          "senderType": "system",
                          "senderId": "system",
                          "messageType": "chat",
                          "content": "invalid frontend sender"
                        },
                        {
                          "id": "good-system-message",
                          "channelId": "channel-1",
                          "senderType": "user",
                          "senderId": "system",
                          "messageType": "system",
                          "content": "valid system row"
                        },
                        {
                          "id": "good-message",
                          "channelId": "channel-1",
                          "senderType": "agent",
                          "senderId": "agent-1",
                          "messageType": "chat",
                          "content": "kept"
                        }
                      ]
                    }
                    """.trimIndent(),
                fallbackThreadId = ThreadId("channel-1"),
                diagnostics = diagnostics::add,
            )

        assertEquals(listOf("good-system-message", "good-message"), payload.messages.map { it.id.value })
        assertEquals(MemberKind.System, payload.messages.first().author.kind)
        assertEquals(
            listOf(
                "projection_value_rejected source=rest_message_sender " +
                    "field=member_kind value=future_sender_kind_with_long_suf",
                "projection_value_rejected source=rest_message_sender " +
                    "field=member_kind value=system",
            ),
            diagnostics,
        )
        assertEquals(false, diagnostics.any { it.contains("secret") })
    }
}
