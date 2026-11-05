package build.raft.app

import build.raft.app.data.parseSharedMessageContext
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals

/** The `GET /messages/context/:id` answer the quoted-message card reads. Link recognition is in links/RaftMessageLinksTest. */
class RaftMessagePermalinkPreviewTest {

    @Test
    fun parsesContextPayloadWithTargetMessage() {
        val body =
            """
            {"channelId":"c1","targetMessageId":"ae7be26c-daa7-42ca-8480-68d5ac90eaca","historyLimited":false,
             "messages":[{"id":"ae7be26c-daa7-42ca-8480-68d5ac90eaca","seq":3,"channelId":"c1","senderType":"user","senderId":"u1","senderName":"Alice","messageType":"Chat","content":"hello"}]}
            """.trimIndent()
        val payload = parseSharedMessageContext(body, ThreadId("c1"))
        assertEquals("ae7be26c-daa7-42ca-8480-68d5ac90eaca", payload.targetMessageId?.value)
        assertEquals(1, payload.messages.size)
        assertEquals("Alice", payload.messages.first().author.displayName)
        assertEquals("hello", payload.messages.first().body)
    }
}
