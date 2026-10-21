package build.raft.app.data.realtime

import build.raft.app.model.MessageMention
import build.raft.app.model.MessageNew
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.mentionsUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `mentions` on `message:new` and on `thread:updated.latestReply` (botiverse/slock staging
 * 259f9fe0b549cfd589ac37bce75e2e1c5ffec6d1: `packages/server/src/services/messageRealtimeProducerRegistry.ts`
 * lists `mentions` among the enriched `message:new` payload keys; `services/messageService.ts` emits
 * `thread:updated` with `latestReply: projectThreadLatestReplyPayload(enriched message)`; entry shape is
 * web `packages/web/src/store/messageStore.ts` `MessageMention { type, id, name, displayName? }`).
 */
class RealtimeEventMapperMentionsTest {
    @Test
    fun messageNewCarriesMentions() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "msg-1",
                  "seq": 12,
                  "channelId": "ch-1",
                  "senderType": "user",
                  "messageType": "chat",
                  "senderId": "u2",
                  "senderName": "Cindy",
                  "content": "@Artin @agent-one look",
                  "createdAt": "2026-10-21T08:00:00Z",
                  "mentions": [
                    { "type": "user", "id": "u1", "name": "Artin" },
                    { "type": "agent", "id": "agent-1", "name": "agent-one", "displayName": "Agent One" },
                    { "type": "user", "id": "", "name": "blank id is dropped" }
                  ]
                }
                """.trimIndent()
            )

        val message = assertIs<MessageNew>(event).message
        assertEquals(
            listOf(MessageMention("user", "u1", "Artin"), MessageMention("agent", "agent-1", "agent-one")),
            message.mentions
        )
        assertTrue(message.mentions.mentionsUser("u1"))
        // An agent mention with the same id is not the signed-in human (web `mentionsCurrentUser`).
        assertFalse(listOf(MessageMention("agent", "u1")).mentionsUser("u1"))
    }

    @Test
    fun messageWithoutOrWithNullMentionsMapsToEmpty() {
        for (mentions in listOf("", """, "mentions": null""", """, "mentions": []""")) {
            val event =
                RealtimeEventMapper().map(
                    RealtimeSocketEvents.MessageNew,
                    """{"id":"msg-2","channelId":"ch-1","senderType":"user","messageType":"chat","senderId":"u2","content":"hi"$mentions}"""
                )
            assertEquals(emptyList(), assertIs<MessageNew>(event).message.mentions, mentions)
        }
    }

    @Test
    fun threadUpdatedCarriesTheLatestReplyMentions() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.ThreadUpdated,
                """
                {
                  "parentMessageId": "parent-1",
                  "threadChannelId": "thread-a",
                  "replyCount": 3,
                  "lastReplyAt": "2026-10-21T08:01:00Z",
                  "participantIds": ["u1", "u2"],
                  "latestReply": {
                    "id": "reply-3",
                    "seq": 9,
                    "channelId": "thread-a",
                    "content": "@Artin ping",
                    "senderId": "u2",
                    "senderType": "user",
                    "senderName": "Cindy",
                    "senderDisplayName": "Cindy",
                    "senderAvatarUrl": null,
                    "createdAt": "2026-10-21T08:01:00Z",
                    "messageType": "chat",
                    "mentions": [{ "type": "user", "id": "u1", "name": "Artin" }]
                  }
                }
                """.trimIndent()
            )

        val updated = assertIs<ThreadUpdated>(event)
        assertEquals("thread-a", updated.threadChannelId.value)
        assertEquals("u2", updated.latestReply?.senderId)
        assertEquals(listOf(MessageMention("user", "u1", "Artin")), updated.latestReplyMentions)
    }
}
