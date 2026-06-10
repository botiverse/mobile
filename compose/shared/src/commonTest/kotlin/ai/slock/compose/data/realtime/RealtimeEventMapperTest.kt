package ai.slock.compose.data.realtime

import ai.slock.compose.model.MessageUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
}
