package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A `#channel:shortid` chip waits on [RealConversationRepository.resolveMessageContext] before its
 * page opens: one `messages/context` round trip, not [RealConversationRepository.getMessageContext]'s
 * context plus `channels/{id}` (artin 11-02: the chip's dead time before the thread page).
 */
class ConversationRepositoryResolveMessageContextTest {
    @Test
    fun resolveMessageContextSkipsTheChannelDetailRequestAndKeepsTheThreadTarget() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/context/77a132eb" to
                            """
                            {
                              "channelId":"thread-1",
                              "targetMessageId":"77a132eb-reply",
                              "canonicalTarget":{
                                "kind":"thread",
                                "channelId":"proj-runtime",
                                "messageId":"77a132eb-reply",
                                "threadParentMessageId":"parent-1",
                                "threadChannelId":"thread-1"
                              },
                              "messages":[{"id":"77a132eb-reply","seq":1,"channelId":"thread-1","senderType":"user","senderId":"user-1","senderName":"Artin","messageType":"chat","content":"hello","createdAt":"2026-05-22T10:00:00.000Z"}]
                            }
                            """.trimIndent(),
                    ),
            )
        val repository = RealConversationRepository(conversationRepositoryTestSessionStore(), network)

        val result = repository.resolveMessageContext(MessageId("77a132eb"), ChannelId("proj-runtime"))

        val conversation = assertIs<DataResult.Content<ThreadSnapshot>>(result).data.conversation
        assertEquals(ConversationKind.Thread, conversation.kind)
        assertEquals("proj-runtime", conversation.thread?.parentChannelId?.value)
        assertEquals("parent-1", conversation.thread?.parentMessageId?.value)
        assertEquals(listOf("messages/context/77a132eb"), network.requests.map { it.path })
        assertEquals(listOf(DataRequestPriority.Foreground), network.requests.map { it.priority })
    }
}
