package build.raft.app.data

import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MessageParserTest {
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
}
