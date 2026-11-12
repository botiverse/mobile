package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * artin 11-12: an Activity tap opens its conversation at the tapped message, and that focused open
 * read `messages/context` and only then `channels/{id}`: two round trips in a row before the page
 * could settle. The page already names its conversation, so
 * [RealConversationRepository.getMessageContextInConversation] reads the header detail beside the context.
 */
class ConversationRepositoryMessageContextInConversationTest {
    @Test
    fun detailOfTheNamedConversationIsRequestedWhileTheContextIsStillLoading() = runTest {
        val detailStarted = CompletableDeferred<Unit>()
        val releaseContext = CompletableDeferred<Unit>()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/context/message-1" to contextJson(channelId = "channel-1"),
                        "GET channels/channel-1" to """{"id":"channel-1","name":"general","type":"channel"}""",
                    ),
                requestStarted = mapOf("GET channels/channel-1" to detailStarted),
                requestRelease = mapOf("GET messages/context/message-1" to releaseContext),
            )
        val repository = RealConversationRepository(conversationRepositoryTestSessionStore(), network)

        val load =
            async {
                repository.getMessageContextInConversation(
                    messageId = MessageId("message-1"),
                    channelId = null,
                    expectedConversationId = ChannelId("channel-1"),
                )
            }
        // The context is held: the detail request can only start if it does not wait for it.
        detailStarted.await()
        assertFalse(load.isCompleted, "the load must still wait for the held context")
        releaseContext.complete(Unit)

        val snapshot = assertIs<DataResult.Content<ThreadSnapshot>>(load.await()).data
        assertEquals("general", snapshot.conversation.name)
        assertEquals(
            listOf(
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "messages/context/message-1"),
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "channels/channel-1"),
            ),
            network.requests,
        )
    }

    @Test
    fun contextInAnotherConversationDropsTheEarlyDetailAndReadsItsOwn() = runTest {
        val earlyDetailStarted = CompletableDeferred<Unit>()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/context/message-1" to contextJson(channelId = "channel-2"),
                        "GET channels/channel-1" to """{"id":"channel-1","name":"stale","type":"channel"}""",
                        "GET channels/channel-2" to """{"id":"channel-2","name":"moved","type":"channel"}""",
                    ),
                requestStarted = mapOf("GET channels/channel-1" to earlyDetailStarted),
                requestRelease =
                    mapOf(
                        // The context answers once the early detail is in flight ...
                        "GET messages/context/message-1" to earlyDetailStarted,
                        // ... which never answers: the load completes only if it is cancelled, not awaited.
                        "GET channels/channel-1" to CompletableDeferred(),
                    ),
            )
        val repository = RealConversationRepository(conversationRepositoryTestSessionStore(), network)

        val result =
            repository.getMessageContextInConversation(
                messageId = MessageId("message-1"),
                channelId = null,
                expectedConversationId = ChannelId("channel-1"),
            )

        val snapshot = assertIs<DataResult.Content<ThreadSnapshot>>(result).data
        assertEquals("moved", snapshot.conversation.name)
        assertEquals(
            listOf("messages/context/message-1", "channels/channel-1", "channels/channel-2"),
            network.requests.map { it.path },
        )
    }

    private fun contextJson(channelId: String): String =
        """{"channelId":"$channelId","targetMessageId":"message-1","messages":[""" +
            """{"id":"message-1","seq":1,"channelId":"$channelId","senderType":"user","senderId":"user-1",""" +
            """"senderName":"Artin","messageType":"chat","content":"hello","createdAt":"2026-05-22T10:00:00.000Z"}]}"""
}
