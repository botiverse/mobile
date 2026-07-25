package build.raft.app

import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.applyReplyFrame
import build.raft.app.network.sync.initialThreadRepliesReadModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ThreadInlineRepliesTest {
    @Test
    fun systemRowsAdvanceSnapshotSeamButDoNotConsumeLatestThreeUiSlots() {
        val model =
            initialThreadRepliesReadModel(
                replies =
                    listOf(
                        reply("r-1", 1),
                        reply("r-2", 2),
                        reply("r-3", 3),
                        reply("system-4", 4, senderType = "system"),
                        reply("r-5", 5),
                    ),
                replyCount = 5,
                limit = 3,
            )

        assertEquals(listOf("r-2", "r-3", "r-5"), model.replies.map { it.messageId })
        assertEquals(5L, model.snapshotSeq)
        assertEquals(5, model.replyCount)
    }

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
        assertEquals(6, presentation.undisplayedReplyCount)
        assertEquals(2, presentation.unreadCount)
        assertEquals(true, presentation.hasDraft)
    }

    @Test
    fun noReplyAndSystemOnlyStatesDoNotCreateInlineSurface() {
        assertNull(
            threadInlineReplyPresentation(
                model = null,
                replyMetadata = metadata(replyCount = 0),
                hasDraft = true,
            )
        )
        val systemOnly =
            initialThreadRepliesReadModel(
                replies = listOf(reply("system-1", 1, senderType = "system")),
                replyCount = 1,
                limit = 3,
            )
        assertNull(
            threadInlineReplyPresentation(
                model = systemOnly,
                replyMetadata = metadata(replyCount = 1),
                hasDraft = false,
            )
        )
    }

    @Test
    fun derivedPreviewNeverEntersSerializedRouteOrPageDataMessageState() {
        val model =
            initialThreadRepliesReadModel(
                replies = listOf(reply("reply-secret-xyz", 1)),
                replyCount = 1,
                limit = 3,
            )
        val message =
            KuiklyThreadMessageItem(
                id = "parent-1",
                authorId = "user-1",
                authorName = "User",
                inlineReplies = model,
            )

        val encoded = Json.encodeToString(message)

        assertFalse("inlineReplies" in encoded)
        assertFalse("reply-secret-xyz" in encoded)
    }

    @Test
    fun staleSnapshotDoesNotReplaceNewerRealtimePreview() {
        val realtime =
            initialThreadRepliesReadModel(
                replies = listOf(reply("reply-10", 10)),
                replyCount = 10,
                limit = 3,
            ).applyReplyFrame(
                reply = reply("reply-11", 11),
                frameReplyCount = 11,
                limit = 3,
            )
        val staleSnapshot =
            initialThreadRepliesReadModel(
                replies = listOf(reply("reply-8", 8), reply("reply-9", 9)),
                replyCount = 9,
                limit = 3,
            )

        assertSame(realtime, realtime.adoptNewerSnapshot(staleSnapshot))
        assertEquals(listOf("reply-10", "reply-11"), realtime.replies.map { it.messageId })
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
