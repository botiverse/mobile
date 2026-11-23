package build.raft.app

import build.raft.app.data.HomeFollowedThreadUnread
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.parentThreadUnread
import build.raft.app.data.withConversationUnread
import build.raft.app.data.withFollowedThreadUnread
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadUpdated
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

    /**
     * #wg-mobile-sync:#256 (artin, Mac app): someone else replies in a thread the person follows
     * while its channel is open. The channel row's thread mark counts it (Home reloads the summary
     * for a thread it did not list), but the live frame carries no unread (server
     * `emitPlainThreadUpdated`), so the parent's inline replies showed none: nothing in the channel
     * said which thread was unread, and its parent on screen gets no unread-thread pill. As on the
     * web, where the reply is one more unread on the parent's summary and on the followed-thread
     * list at once (socketBridge.ts `threadUpdated`), it shows under the parent until the thread
     * is read, and reading it clears both.
     */
    @Test
    fun aReplyTheChannelRowCountsShowsUnderItsParentUntilTheThreadIsRead() {
        val channel = ChannelId("channel-1")
        val parent =
            RaftThreadMessageItem(
                id = "parent-1",
                authorId = "u1",
                authorName = "Artin",
                body = "parent",
                threadChannelId = "thread-1",
                replyCount = 8,
            )
        val frame =
            ThreadUpdated(
                parentMessageId = MessageId("parent-1"),
                threadChannelId = ChannelId("thread-1"),
                replyCount = 9,
                lastReplyAt = "2026-07-24T08:00:00Z",
                participantIds = listOf(MemberId("sender-9")),
                latestReply = reply("r-9", 9),
            )
        val page =
            RaftThreadScreenPayload(sourceThreadId = channel.value, status = "content", messages = listOf(parent))
                .applyRealtimeEvent(frame, currentUserId = "u1")
                .toThreadReplyMetadataMap()
        val model = initialThreadRepliesReadModel(replies = (7L..9L).map { reply("r-$it", it) }, replyCount = 9, limit = 3)
        val me =
            MemberProfile(
                id = MemberId("u1"),
                displayName = "Artin",
                handle = "artin@slock.ai",
                kind = MemberKind.Human,
                presence = Presence.Active,
            )
        val home =
            HomeSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                currentMember = me,
                channels = emptyList(),
                directMessages = emptyList(),
                unreadTotal = 0,
            ).withFollowedThreadUnread(mapOf("thread-1" to HomeFollowedThreadUnread(channel, unreadCount = 1)))
        assertEquals(1, home.parentThreadUnread(channel)?.count, "the row's thread mark")

        val shown = page.withHomeFollowedThreadUnread(home.followedThreadUnread).getValue("parent-1")
        assertEquals(9, threadInlineReplyPresentation(model, shown, hasDraft = false)?.replyCount)
        assertEquals(1, threadInlineReplyPresentation(model, shown, hasDraft = false)?.unreadCount)

        val read = home.withConversationUnread("thread-1", 0)
        assertNull(read.parentThreadUnread(channel), "the row's thread mark clears")
        val afterRead = page.withHomeFollowedThreadUnread(read.followedThreadUnread).getValue("parent-1")
        assertEquals(0, threadInlineReplyPresentation(model, afterRead, hasDraft = false)?.unreadCount)
    }

    /** What the page knows is never lowered: a higher count of its own, and threads Home does not list. */
    @Test
    fun theInlineCountKeepsWhatThePageKnowsAboveHomesCount() {
        val page =
            mapOf(
                "parent-1" to metadata(replyCount = 9, unreadReplyCount = 3),
                "parent-2" to
                    metadata(replyCount = 2, unreadReplyCount = 2).copy(parentMessageId = "parent-2", threadChannelId = "thread-2"),
            )

        val home = mapOf("thread-1" to HomeFollowedThreadUnread(ChannelId("channel-1"), unreadCount = 1))

        val shown = page.withHomeFollowedThreadUnread(home)

        assertEquals(3, shown.getValue("parent-1").unreadReplyCount)
        assertEquals(2, shown.getValue("parent-2").unreadReplyCount)
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
