package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** web MessageItem `shouldShowThreadRepliesBadge`: the footer badge stands in for a missing inline preview. */
class ThreadRepliesBadgeTest {
    private fun metadata(replies: Int, unread: Int = 0) =
        ThreadReplyMetadata(
            parentMessageId = "m1",
            threadChannelId = "t1",
            replyCount = replies,
            unreadReplyCount = unread,
            firstUnreadReplyMessageId = null,
            isThreadFollowed = false,
        )

    @Test
    fun repliesWithoutAnInlinePreviewShowTheBadge() {
        assertEquals(
            ThreadRepliesBadgeSpec(replyCount = 5, unreadCount = 2, hasDraft = false),
            threadRepliesBadgeSpec(metadata(5, 2), hasDraft = false, showReplyAction = true, hasInlineReplies = false),
        )
    }

    @Test
    fun anInlinePreviewReplacesTheBadge() {
        assertNull(threadRepliesBadgeSpec(metadata(5, 2), hasDraft = false, showReplyAction = true, hasInlineReplies = true))
    }

    @Test
    fun noRepliesAndNoDraftOrNoThreadActionsShowNothing() {
        assertNull(threadRepliesBadgeSpec(metadata(0), hasDraft = false, showReplyAction = true, hasInlineReplies = false))
        assertNull(threadRepliesBadgeSpec(metadata(3), hasDraft = true, showReplyAction = false, hasInlineReplies = false))
    }

    @Test
    fun aDraftAloneShowsTheBadge() {
        assertEquals(
            ThreadRepliesBadgeSpec(replyCount = 0, unreadCount = 0, hasDraft = true),
            threadRepliesBadgeSpec(metadata(0), hasDraft = true, showReplyAction = true, hasInlineReplies = false),
        )
    }
}
