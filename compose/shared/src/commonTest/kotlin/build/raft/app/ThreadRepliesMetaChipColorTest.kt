package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadRepliesMetaChipColorTest {
    @Test
    fun unreadRepliesUseReactAlignedCyanReplyPill() {
        assertEquals(
            SlockColor.AccentCyan.copy(alpha = 0.2f),
            threadRepliesMetaChipColor(replyMetadata(unreadReplyCount = 1))
        )
    }

    @Test
    fun readRepliesKeepWhiteReplyPill() {
        assertEquals(
            SlockColor.SurfaceWhite,
            threadRepliesMetaChipColor(replyMetadata(unreadReplyCount = 0))
        )
    }

    private fun replyMetadata(unreadReplyCount: Int): ThreadReplyMetadata =
        ThreadReplyMetadata(
            parentMessageId = "parent-1",
            threadChannelId = "thread-1",
            replyCount = 4,
            unreadReplyCount = unreadReplyCount,
            firstUnreadReplyMessageId = if (unreadReplyCount > 0) "reply-1" else null,
            isThreadFollowed = true
        )
}
