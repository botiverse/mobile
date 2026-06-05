package ai.slock.android.core.model

import ai.slock.compose.model.explicitMessageStreamChannelId
import ai.slock.compose.model.isSameConversationAs
import ai.slock.compose.model.messageStreamChannelId
import ai.slock.compose.model.threadIdentity
import ai.slock.compose.model.withTitleFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSummaryIdentityTest {
    @Test
    fun parentChannelAndReplyThreadAreNotSameConversationWhenOnlyChannelMatchesThread() {
        val parent =
            ConversationSummary(
                threadId = ThreadId("parent-channel"),
                channelId = ChannelId("parent-channel"),
                kind = ConversationKind.Channel,
                name = "parent"
            )
        val replyThread =
            ConversationSummary(
                threadId = threadIdentity(ChannelId("parent-channel"), MessageId("parent-message")),
                channelId = ChannelId("parent-channel"),
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("parent-channel"),
                        parentMessageId = MessageId("parent-message"),
                        streamChannelId = ChannelId("reply-thread-channel")
                    ),
                kind = ConversationKind.Thread,
                name = "reply"
            )

        assertFalse(replyThread.isSameConversationAs(parent))
    }

    @Test
    fun topLevelChannelsCanMatchByChannelIdWhenThreadIdAlsoNamesThatChannel() {
        val first =
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "first"
            )
        val second =
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "second"
            )

        assertTrue(first.isSameConversationAs(second))
    }

    @Test
    fun threadMessageStreamUsesThreadChannelId() {
        val replyThread =
            ConversationSummary(
                threadId = threadIdentity(ChannelId("parent-channel"), MessageId("parent-message")),
                channelId = ChannelId("parent-channel"),
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("parent-channel"),
                        parentMessageId = MessageId("parent-message"),
                        streamChannelId = ChannelId("reply-thread-channel")
                    ),
                kind = ConversationKind.Thread,
                name = "reply"
            )

        assertEquals("reply-thread-channel", replyThread.messageStreamChannelId().value)
    }

    @Test
    fun compositeThreadIdDoesNotPretendItIsExplicitReplyStream() {
        val replyThread =
            ConversationSummary(
                threadId = threadIdentity(ChannelId("parent-channel"), MessageId("parent-message")),
                channelId = ChannelId("parent-channel"),
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("parent-channel"),
                        parentMessageId = MessageId("parent-message")
                    ),
                kind = ConversationKind.Thread,
                name = "reply"
            )

        assertEquals(null, replyThread.explicitMessageStreamChannelId())
    }

    @Test
    fun titleFallbackDoesNotBorrowParentTitleForDifferentThreadConversation() {
        val replyThread =
            ConversationSummary(
                threadId = threadIdentity(ChannelId("parent-channel"), MessageId("parent-message")),
                channelId = ChannelId("parent-channel"),
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("parent-channel"),
                        parentMessageId = MessageId("parent-message")
                    ),
                kind = ConversationKind.Thread,
                name = "reply-thread"
            )
        val parent =
            ConversationSummary(
                threadId = ThreadId("parent-channel"),
                channelId = ChannelId("parent-channel"),
                kind = ConversationKind.Channel,
                name = "parent"
            )

        assertEquals("reply-thread", replyThread.withTitleFallback(parent).name)
    }

    @Test
    fun replyThreadsMatchByParentChannelAndParentMessage() {
        val first =
            ConversationSummary(
                threadId = threadIdentity(ChannelId("parent-channel"), MessageId("parent-message")),
                channelId = ChannelId("parent-channel"),
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("parent-channel"),
                        parentMessageId = MessageId("parent-message"),
                        streamChannelId = ChannelId("reply-stream-a")
                    ),
                kind = ConversationKind.Thread,
                name = "first"
            )
        val second =
            ConversationSummary(
                threadId = ThreadId("legacy-reply-stream"),
                channelId = ChannelId("parent-channel"),
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("parent-channel"),
                        parentMessageId = MessageId("parent-message"),
                        streamChannelId = ChannelId("reply-stream-b")
                    ),
                kind = ConversationKind.Thread,
                name = "second"
            )

        assertTrue(first.isSameConversationAs(second))
    }
}
