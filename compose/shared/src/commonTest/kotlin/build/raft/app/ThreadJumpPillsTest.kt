package build.raft.app

import build.raft.app.data.HomeFollowedThreadUnread
import build.raft.app.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The conversation's jump pills (web ChatPanel bottom button + ThreadUnreadJumpPill). */
class ThreadJumpPillsTest {
    private fun parent(id: String, thread: String? = null, followed: Boolean = false, unread: Int = 0) =
        ThreadJumpParentCandidate(messageId = id, threadChannelId = thread, threadFollowed = followed, unreadReplyCount = unread)

    // m0 .. m9, with unread followed threads under m1 (t1), m4 (t4) and m8 (t8).
    private val messages =
        (0..9).map { index ->
            when (index) {
                1, 4, 8 -> parent("m$index", thread = "t$index", followed = true, unread = 2)
                6 -> parent("m$index", thread = "t6", followed = true, unread = 0)
                else -> parent("m$index")
            }
        }
    private val unread = setOf("t1", "t4", "t8")

    @Test
    fun placementByIndexAgainstTheRowsInView() {
        assertEquals(ThreadJumpPlacement.Above, threadJumpPlacement(1, 3..5))
        assertEquals(ThreadJumpPlacement.Visible, threadJumpPlacement(3, 3..5))
        assertEquals(ThreadJumpPlacement.Visible, threadJumpPlacement(5, 3..5))
        assertEquals(ThreadJumpPlacement.Below, threadJumpPlacement(6, 3..5))
    }

    @Test
    fun theNearestParentBelowWinsAndNCountsEveryUnreadThread() {
        // In view: m2..m3. Below: m4 (nearest) and m8; above: m1.
        val jump = projectThreadUnreadThreadJump(unread, messages, visibleMessageIndexes = 2..3, dismissedAtCount = null)

        assertEquals(ThreadUnreadThreadJump(ThreadUnreadThreadJumpDirection.Next, count = 3, targetMessageId = "m4"), jump)
    }

    @Test
    fun withNoneBelowTheTopmostParentAboveShowsAtTheTop() {
        // In view: m8..m9 (m8 is on screen, so it is neither above nor below).
        val jump = projectThreadUnreadThreadJump(unread, messages, visibleMessageIndexes = 8..9, dismissedAtCount = null)

        assertEquals(ThreadUnreadThreadJump(ThreadUnreadThreadJumpDirection.Earliest, count = 3, targetMessageId = "m1"), jump)
    }

    @Test
    fun noPillWhenEveryLoadedParentIsInViewNothingIsUnreadOrNothingIsLaidOut() {
        assertNull(projectThreadUnreadThreadJump(unread, messages, visibleMessageIndexes = 0..9, dismissedAtCount = null))
        assertNull(projectThreadUnreadThreadJump(emptySet(), messages, visibleMessageIndexes = 2..3, dismissedAtCount = null))
        assertNull(projectThreadUnreadThreadJump(unread, messages, visibleMessageIndexes = null, dismissedAtCount = null))
    }

    @Test
    fun anUnloadedParentCountsButIsNoTarget() {
        val withUnloaded = unread + "t-unloaded"

        val jump = projectThreadUnreadThreadJump(withUnloaded, messages, visibleMessageIndexes = 2..3, dismissedAtCount = null)
        assertEquals(4, jump?.count)
        assertEquals("m4", jump?.targetMessageId)
        assertNull(projectThreadUnreadThreadJump(setOf("t-unloaded"), messages, visibleMessageIndexes = 2..3, dismissedAtCount = null))
    }

    @Test
    fun dismissHidesThePillUntilTheCountChanges() {
        assertNull(projectThreadUnreadThreadJump(unread, messages, visibleMessageIndexes = 2..3, dismissedAtCount = 3))

        val afterOneRead = projectThreadUnreadThreadJump(setOf("t1", "t8"), messages, visibleMessageIndexes = 2..3, dismissedAtCount = 3)
        assertEquals(ThreadUnreadThreadJump(ThreadUnreadThreadJumpDirection.Next, count = 2, targetMessageId = "m8"), afterOneRead)
    }

    @Test
    fun unreadThreadsComeFromHomeForThisChannelElseFromTheLoadedParents() {
        val home =
            mapOf(
                "t1" to HomeFollowedThreadUnread(ChannelId("c1"), unreadCount = 1),
                "t9" to HomeFollowedThreadUnread(ChannelId("c1"), unreadCount = 3, hasMention = true),
                "t-read" to HomeFollowedThreadUnread(ChannelId("c1"), unreadCount = 0),
                "t-other" to HomeFollowedThreadUnread(ChannelId("c2"), unreadCount = 4),
            )

        assertEquals(setOf("t1", "t9"), threadConversationUnreadThreadIds("c1", home, messages))
        // Home not loaded: followed parents with unread replies (t6 has none).
        assertEquals(unread, threadConversationUnreadThreadIds("c1", homeFollowedThreadUnread = null, loadedMessages = messages))
    }

    @Test
    fun visibleRangeKeepsOnlyMessageRowsThatIntersectTheViewport() {
        // List: 0 header, 1..5 messages m0..m4, 6 bottom padding. Viewport 100..400.
        val rows =
            listOf(
                ThreadJumpVisibleRow(listIndex = 1, offset = 0, size = 100), // ends at the viewport start: out
                ThreadJumpVisibleRow(listIndex = 2, offset = 100, size = 100),
                ThreadJumpVisibleRow(listIndex = 3, offset = 200, size = 150),
                ThreadJumpVisibleRow(listIndex = 4, offset = 350, size = 100), // partly in view
                ThreadJumpVisibleRow(listIndex = 5, offset = 450, size = 100), // beyond the end: out
                ThreadJumpVisibleRow(listIndex = 6, offset = 300, size = 50), // not a message row
            )

        assertEquals(1..3, threadVisibleMessageIndexRange(rows, viewportStart = 100, viewportEnd = 400, firstMessageListItemIndex = 1, messageCount = 5))
        assertNull(threadVisibleMessageIndexRange(rows.take(1), viewportStart = 100, viewportEnd = 400, firstMessageListItemIndex = 1, messageCount = 5))
        assertNull(threadVisibleMessageIndexRange(rows, viewportStart = 100, viewportEnd = 400, firstMessageListItemIndex = 1, messageCount = 0))
    }

    private fun arrivals(vararg ids: String, ownLocal: Set<String> = emptySet()) =
        ids.map { ThreadJumpArrival(messageId = it, countable = it !in ownLocal) }

    @Test
    fun newMessagesCountArrivalsWhileAwayFromTheBottomAndClearThere() {
        var state = reduceThreadNewMessagesBelow(ThreadNewMessagesBelow(), arrivals("a", "b"), awayFromBottom = true, canLoadMoreAfter = false)
        assertEquals(ThreadNewMessagesBelow(count = 0, lastMessageId = "b"), state)

        state = reduceThreadNewMessagesBelow(state, arrivals("a", "b", "c", "d"), awayFromBottom = true, canLoadMoreAfter = false)
        assertEquals(2, state.count)
        // The reader's own local send does not count.
        state = reduceThreadNewMessagesBelow(state, arrivals("a", "b", "c", "d", "local", "e", ownLocal = setOf("local")), awayFromBottom = true, canLoadMoreAfter = false)
        assertEquals(3, state.count)

        state = reduceThreadNewMessagesBelow(state, arrivals("a", "b", "c", "d", "local", "e"), awayFromBottom = false, canLoadMoreAfter = false)
        assertEquals(ThreadNewMessagesBelow(count = 0, lastMessageId = "e"), state)
    }

    @Test
    fun newMessagesIgnorePagesReloadsAndNewerPageLoads() {
        val start = ThreadNewMessagesBelow(count = 1, lastMessageId = "b")

        // More than five rows at once is a page load.
        val page = reduceThreadNewMessagesBelow(start, arrivals("a", "b", "c", "d", "e", "f", "g", "h"), awayFromBottom = true, canLoadMoreAfter = false)
        assertEquals(ThreadNewMessagesBelow(count = 1, lastMessageId = "h"), page)
        // The previous newest message is gone: a replaced list rebases without counting.
        val replaced = reduceThreadNewMessagesBelow(start, arrivals("x", "y"), awayFromBottom = true, canLoadMoreAfter = false)
        assertEquals(ThreadNewMessagesBelow(count = 1, lastMessageId = "y"), replaced)
        // Newer messages not loaded: appends are newer pages.
        val newer = reduceThreadNewMessagesBelow(start, arrivals("a", "b", "c"), awayFromBottom = true, canLoadMoreAfter = true)
        assertEquals(ThreadNewMessagesBelow(count = 1, lastMessageId = "c"), newer)
    }

    @Test
    fun bottomCountUsesTheConversationUnreadWhileNewerMessagesAreNotLoaded() {
        assertEquals(7, threadBottomJumpCount(canLoadMoreAfter = true, conversationUnreadCount = 7, newMessagesBelow = 2))
        assertEquals(2, threadBottomJumpCount(canLoadMoreAfter = false, conversationUnreadCount = 7, newMessagesBelow = 2))
        assertEquals(0, threadBottomJumpCount(canLoadMoreAfter = false, conversationUnreadCount = 0, newMessagesBelow = 0))
    }
}
