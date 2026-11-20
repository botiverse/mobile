package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A reply thread's load keeps the total the server names when the thread is opened, so the page
 * counts every reply and not only the page of replies it read.
 */
class ConversationRepositoryReplyThreadReplyCountTest {
    @Test
    fun replyThreadCarriesTheTotalItsOpenAnswerNames() = runTest {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        // botiverse/slock 7c3e67b8 routes/channels.ts POST /:id/threads:
                        // `{ threadChannelId, ...getThreadInfoForChannel() }`.
                        "POST channels/channel-1/threads" to
                            """{"threadChannelId":"thread-1","replyCount":412,"lastReplyAt":"2026-11-20T08:00:00.000Z","participantIds":["u1","u2"]}""",
                        "GET messages/channel/thread-1" to
                            """{"messages":[{"id":"reply-412","seq":412,"channelId":"thread-1","senderType":"user","senderId":"user-1","senderName":"Artin","messageType":"chat","content":"latest reply","createdAt":"2026-11-20T08:00:00.000Z"}],"historyLimited":false}""",
                    )
            )
        val repository = RealConversationRepository(conversationRepositoryTestSessionStore(), network)

        val replyThread =
            assertIs<DataResult.Content<ThreadSnapshot>>(
                repository.getReplyThread(
                    parentChannelId = ChannelId("channel-1"),
                    parentMessageId = MessageId("parent-1"),
                    limit = 30
                )
            ).data

        assertEquals(listOf("latest reply"), replyThread.messages.map { it.body })
        // One reply was read; the thread has 412.
        assertEquals(412, replyThread.replyThreadReplyCount)
    }
}
