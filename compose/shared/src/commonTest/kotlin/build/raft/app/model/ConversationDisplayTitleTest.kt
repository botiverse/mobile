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
}
