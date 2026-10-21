package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeFollowedThreadUnread
import build.raft.app.data.foldThreadUnreadByParent
import build.raft.app.model.ChannelId
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Home row projector turns the store's row mention and followed-thread unread into
 * `RaftHomeConversationItem.mentioned` / `followedThreadUnreadCount` (web Sidebar.tsx
 * `showMentionMarker = (mentionMarked || threadUnread.hasMention) && joined`, `threads={threadUnread.count}`).
 */
class HomeConversationRowProjectorMarksTest {
    private val general =
        HomeChannelRow(threadId = ThreadId("channel-general"), channelId = ChannelId("channel-general"), name = "general")
    private val threads =
        mapOf(
            "thread-a" to HomeFollowedThreadUnread(ChannelId("channel-general"), 2, hasMention = true),
            "thread-b" to HomeFollowedThreadUnread(ChannelId("channel-general"), 1, hasMention = false),
        )

    @Test
    fun channelRowsCarryTheRowMentionAndTheFoldedThreadMark() {
        val projector = SharedHomeConversationRowProjector(threadUnreadByParent = threads.foldThreadUnreadByParent())

        val item = projector.project(general)
        assertEquals(2, item.followedThreadUnreadCount)
        assertTrue(item.mentioned, "a followed thread's unread mention lights the parent's @")

        val ownMention = projector.project(general.copy(mentionCount = 1, mentionCountKnown = true))
        assertTrue(ownMention.mentioned)
    }

    @Test
    fun gatedOffThreadMarkLeavesOnlyTheRowMention() {
        // The Home screen passes an empty fold when thread_unread_badge_v0 is off.
        val projector = SharedHomeConversationRowProjector()

        val item = projector.project(general)
        assertEquals(0, item.followedThreadUnreadCount)
        assertFalse(item.mentioned)
        assertTrue(projector.project(general.copy(mentionCount = 1, mentionCountKnown = true)).mentioned)
    }

    @Test
    fun directMessageRowsNeverCarryAThreadSegment() {
        val dm = HomeDirectMessageRow(threadId = ThreadId("channel-general"), channelId = ChannelId("channel-general"), name = "cindy")
        val projector = SharedHomeConversationRowProjector(threadUnreadByParent = threads.foldThreadUnreadByParent())

        val item = projector.project(dm)
        assertEquals(0, item.followedThreadUnreadCount)
        assertFalse(item.mentioned)
    }
}
