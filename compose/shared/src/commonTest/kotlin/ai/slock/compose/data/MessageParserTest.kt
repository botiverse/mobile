package ai.slock.compose.data

import ai.slock.compose.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
