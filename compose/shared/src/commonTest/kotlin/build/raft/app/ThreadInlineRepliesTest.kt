package build.raft.app

import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.initialThreadRepliesReadModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThreadInlineRepliesTest {
    @Test
    fun presentationUsesAuthoritativeCountUnreadAndDraftWhileKeepingThreeRows() {
        val model =
            initialThreadRepliesReadModel(
                replies = (1L..4L).map { reply("r-$it", it) },
                replyCount = 9,
                limit = 3,
            )

        val presentation =
            assertNotNull(
                threadInlineReplyPresentation(
                    model = model,
                    replyMetadata = metadata(replyCount = 9, unreadReplyCount = 2),
                    hasDraft = true,
                )
            )

        assertEquals(listOf("r-2", "r-3", "r-4"), presentation.replies.map { it.messageId })
        assertEquals(9, presentation.replyCount)
        assertEquals(
            ThreadInlineReplyCountLabel(ThreadInlineReplyCountLabel.Kind.Many, 9),
            presentation.countLabel(),
        )
        assertEquals(2, presentation.unreadCount)
        assertEquals(true, presentation.hasDraft)
    }

    @Test
    fun zeroReplyCountHasNoSurfaceAndCachedPreviewCannotAdmitGeometry() {
        assertNull(
            threadInlineReplyPresentation(
                model = null,
                replyMetadata = metadata(replyCount = 0),
                hasDraft = true,
            )
        )
        val staleCachedPreview =
            initialThreadRepliesReadModel(
                replies = listOf(reply("reply-9", 9)),
                replyCount = 9,
                limit = 3,
            )
        assertNull(
            threadInlineReplyPresentation(
                model = staleCachedPreview,
                replyMetadata = metadata(replyCount = 0),
                hasDraft = false,
            )
        )
    }

    private fun reply(
        id: String,
        seq: Long,
        senderType: String = "user",
    ): ReplyPreview =
        ReplyPreview(
            messageId = id,
            seq = seq,
            content = "reply $seq",
            senderId = "sender-$seq",
            senderType = senderType,
            senderName = "handle-$seq",
            senderAvatarUrl = null,
            createdAt = "2026-07-24T08:00:00Z",
            senderDisplayName = "Display $seq",
        )

    private fun metadata(
        replyCount: Int,
        unreadReplyCount: Int = 0,
    ): ThreadReplyMetadata =
        ThreadReplyMetadata(
            parentMessageId = "parent-1",
            threadChannelId = "thread-1",
            replyCount = replyCount,
            unreadReplyCount = unreadReplyCount,
            firstUnreadReplyMessageId = null,
            isThreadFollowed = false,
        )
}
