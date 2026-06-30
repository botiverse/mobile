package build.raft.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationDisplayTitleTest {
    @Test
    fun conversationDisplayTitleNormalizesChannelAndMentionPrefixes() {
        assertEquals("对话流专修", conversationSafeDisplayTitleOrNull("#对话流专修"))
        assertEquals("artin", conversationSafeDisplayTitleOrNull("@artin"))
    }

    @Test
    fun conversationDisplayTitleRejectsIdentityLikeLabels() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        assertNull(conversationSafeDisplayTitleOrNull(uuid))
        assertNull(conversationSafeDisplayTitleOrNull("deadbeef"))
        assertNull(conversationSafeDisplayTitleOrNull("#channel-1", setOf("channel-1")))
    }

    @Test
    fun titleFallbackUsesOnlyCanonicalDisplayName() {
        val current =
            ConversationSummary(
                threadId = ThreadId("channel-1:message-1"),
                channelId = ChannelId("channel-1"),
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("message-1")
                    ),
                kind = ConversationKind.Thread,
                name = "channel-1"
            )
        val fallback =
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "#对话流专修"
            )

        assertEquals("对话流专修", current.withTitleFallback(fallback).name)
    }
}
