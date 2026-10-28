package build.raft.app

import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.RaftActivityMarkAction
import build.raft.app.data.SharedActivityIntent
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where "marked unread" (botiverse/slock #11795 at 1442d689c7) shows its dot, against web
 * `getChannelUnreadIndicatorState` (`showMarkedUnreadDot`, `unreadTone`), `SidebarPeerRowBody`
 * and ThreadsInbox `conversation-card-marked-unread`: unread with no number, a count wins over it,
 * and the row reads as unread for its read toggle.
 */
class MarkedUnreadRowDecisionTest {

    @Test
    fun homeChannelRowShowsTheDotOnlyWithNothingCounted() {
        data class Case(
            val name: String,
            val unread: Int = 0,
            val threads: Int = 0,
            val joined: Boolean = true,
            val muted: Boolean = false,
            val draft: Boolean = false,
            val dot: Boolean,
            val unreadTone: Boolean,
            val showDraft: Boolean = false,
        )
        listOf(
            Case("marked, joined, unmuted", dot = true, unreadTone = true),
            Case("marked, muted: dot, quiet name", muted = true, dot = true, unreadTone = false),
            Case("marked, not joined: dot, quiet name", joined = false, dot = true, unreadTone = false),
            Case("marked with a count: the count wins", unread = 2, dot = false, unreadTone = true),
            Case("marked with followed-thread unread: the thread count wins", threads = 1, dot = false, unreadTone = true),
            Case("marked with a draft: the dot, not the pencil", draft = true, dot = true, unreadTone = true, showDraft = false),
        ).forEach { case ->
            val marks =
                homeChannelRowMarks(
                    unreadCount = case.unread,
                    followedThreadUnreadCount = case.threads,
                    mentioned = false,
                    joined = case.joined,
                    muted = case.muted,
                    hasDraft = case.draft,
                    markedUnread = true,
                )
            assertEquals(case.dot, marks.markedUnreadDot, case.name)
            assertEquals(case.unreadTone, marks.unreadTone, case.name)
            assertEquals(case.showDraft, marks.showDraft, case.name)
        }
        val unmarked = homeChannelRowMarks(0, 0, mentioned = false, joined = true, muted = false, hasDraft = true)
        assertFalse(unmarked.markedUnreadDot)
        assertFalse(unmarked.unreadTone)
        assertTrue(unmarked.showDraft)
    }

    @Test
    fun homeBadgeTreatsAMarkedRowAsUnreadWithNoLabel() {
        val marked = homeConversationBadgeProjection(HomeConversationBadgeInput(unreadCount = 0, markedUnread = true))
        assertNull(marked.label)
        assertTrue(marked.hasUnread, "menu offers Mark as Read; a hidden DM stays listed")
        assertTrue(marked.markedUnreadDot)
        assertEquals(HomeSidebarBadgeTone.Loud, marked.tone)

        val mutedMarked =
            homeConversationBadgeProjection(HomeConversationBadgeInput(unreadCount = 0, muted = true, markedUnread = true))
        assertEquals(HomeSidebarBadgeTone.Quiet, mutedMarked.tone)
        assertTrue(mutedMarked.markedUnreadDot)

        val counted = homeConversationBadgeProjection(HomeConversationBadgeInput(unreadCount = 3, markedUnread = true))
        assertEquals("3", counted.label)
        assertFalse(counted.markedUnreadDot)

        assertFalse(homeConversationBadgeProjection(HomeConversationBadgeInput(unreadCount = 0)).hasUnread)
        assertTrue(RaftHomeConversationItem(id = "dm-1", markedUnread = true).homeConversationBadgeProjection().hasUnread)
    }

    @Test
    fun homeDirectMessageRowShowsTheDotAloneUnlessCounted() {
        assertEquals(
            listOf(RaftHomeDmTrailingSlot.MarkedUnreadDot),
            raftHomeDmTrailingSlots(badge = null, hasDraft = true, isAgent = true, markedUnread = true),
        )
        assertEquals(
            listOf(RaftHomeDmTrailingSlot.UnreadBadge),
            raftHomeDmTrailingSlots(badge = "2", hasDraft = false, isAgent = false, markedUnread = true),
        )
        assertEquals(
            listOf(RaftHomeDmTrailingSlot.Draft, RaftHomeDmTrailingSlot.AgentMeta),
            raftHomeDmTrailingSlots(badge = null, hasDraft = true, isAgent = true, markedUnread = false),
        )
    }

    @Test
    fun activityRowShowsTheDotAndReadsAsUnread() {
        val context = ActivityConversationListContext()
        val marked = channelRow(unread = 0, marked = true)

        val model = marked.activityConversationRowModel(context, InboxFilter.Unread)
        assertTrue(model.showMarkedUnreadDot)
        assertFalse(model.showUnreadCount)
        assertTrue(model.strong)
        assertNull(model.unreadCountLabel())

        val counted = channelRow(unread = 2, marked = true).activityConversationRowModel(context, InboxFilter.All)
        assertFalse(counted.showMarkedUnreadDot, "a count wins over the dot")
        assertTrue(counted.showUnreadCount)

        val muted =
            marked.activityConversationRowModel(context.copy(mutedChannelIds = setOf("channel-1")), InboxFilter.All)
        assertFalse(muted.showMarkedUnreadDot, "a muted row shows its muted mark instead")
        assertFalse(muted.strong)

        assertEquals(ActivitySwipeButton.Read, marked.activitySwipeStartButton())
        assertIs<SharedActivityIntent.MarkRead>(activityReadToggleIntent(marked))
        assertEquals(RaftActivityMarkAction.Read, activityReadToggleMarkAction(marked))
        assertIs<SharedActivityIntent.MarkRead>(marked.activityOpenReadIntent(), "opening sends the read that clears it")
        assertNull(channelRow(unread = 0, marked = false).activityOpenReadIntent())
        assertIs<SharedActivityIntent.MarkUnread>(activityReadToggleIntent(channelRow(unread = 0, marked = false)))
    }

    private fun channelRow(unread: Int, marked: Boolean) =
        InboxItem(
            key = "channel:channel-1",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("channel-1"),
            channelName = "general",
            unreadCount = unread,
            markedUnread = marked,
            lastMessageId = MessageId("m-1"),
            lastMessagePreview = "my own message",
        )
}
