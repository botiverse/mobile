package build.raft.app

import build.raft.app.data.HomeFollowedThreadUnread
import build.raft.app.model.ChannelId
import build.raft.app.thread.ThreadJumpArrival
import build.raft.app.thread.ThreadJumpLoadedRange
import build.raft.app.thread.ThreadJumpParentCandidate
import build.raft.app.thread.ThreadNewMessagesBelow
import build.raft.app.thread.ThreadUnreadJumpHistoryAction
import build.raft.app.thread.ThreadUnreadThreadJump
import build.raft.app.thread.ThreadUnreadThreadJumpDirection
import build.raft.app.thread.ThreadUnreadThreadParent
import build.raft.app.thread.projectThreadUnreadThreadJump
import build.raft.app.thread.reduceThreadNewMessagesBelow
import build.raft.app.thread.threadBottomJumpCount
import build.raft.app.thread.threadConversationUnreadThreadIds
import build.raft.app.thread.threadUnreadJumpHistoryAction
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
    fun theNearestParentBelowWinsAndNCountsEveryUnreadThread() {
        // In view: m2..m3. Below: m4 (nearest) and m8; above: m1.
        val jump = projectThreadUnreadThreadJump(unread, messages, visibleMessageIndexes = 2..3, dismissedAtCount = null)

        assertEquals(ThreadUnreadThreadJump(ThreadUnreadThreadJumpDirection.Next, count = 3, targetMessageId = "m4", targetThreadChannelId = "t4"), jump)
    }

    @Test
    fun noPillWhenEveryLoadedParentIsInViewNothingIsUnreadOrNothingIsLaidOut() {
        assertNull(projectThreadUnreadThreadJump(unread, messages, visibleMessageIndexes = 0..9, dismissedAtCount = null))
        assertNull(projectThreadUnreadThreadJump(emptySet(), messages, visibleMessageIndexes = 2..3, dismissedAtCount = null))
        assertNull(projectThreadUnreadThreadJump(unread, messages, visibleMessageIndexes = null, dismissedAtCount = null))
    }

    private fun resolved(thread: String, parentMessage: String, createdAt: Long? = null) =
        thread to ThreadUnreadThreadParent(thread, parentChannelId = "c1", parentMessageId = parentMessage, parentCreatedAtEpochMillis = createdAt)

    @Test
    fun aResolvedParentOlderThanTheLoadedMessagesIsTheEarliestAboveEvenWithOnlyItUnread() {
        // artin 1.15.2: one unread thread, its parent outside the loaded tail. Reader at the bottom.
        val parents = mapOf(resolved("t-old", "p-old", createdAt = 1_000L))
        val atTail = ThreadJumpLoadedRange(newestCreatedAtEpochMillis = 9_000L, hasNewer = false)

        val jump = projectThreadUnreadThreadJump(setOf("t-old"), messages, 7..9, dismissedAtCount = null, unloadedParents = parents, loadedRange = atTail)
        assertEquals(ThreadUnreadThreadJump(ThreadUnreadThreadJumpDirection.Earliest, count = 1, targetMessageId = "p-old", targetThreadChannelId = "t-old"), jump)

        // It is above every loaded parent, so it wins Earliest over m1; a loaded parent below still wins Next.
        val mixed = projectThreadUnreadThreadJump(unread + "t-old", messages, 2..3, dismissedAtCount = null, unloadedParents = parents, loadedRange = atTail)
        assertEquals(ThreadUnreadThreadJump(ThreadUnreadThreadJumpDirection.Next, count = 4, targetMessageId = "m4", targetThreadChannelId = "t4"), mixed)
        val noneBelow = projectThreadUnreadThreadJump(unread + "t-old", messages, 8..9, dismissedAtCount = null, unloadedParents = parents, loadedRange = atTail)
        assertEquals(ThreadUnreadThreadJump(ThreadUnreadThreadJumpDirection.Earliest, count = 4, targetMessageId = "p-old", targetThreadChannelId = "t-old"), noneBelow)
    }

    /**
     * Server #11517 / web #11494: a parent past the plan's history window opens the thread when its
     * replies are inside the window, else the jump lands on the paywall and reads the thread; an
     * older server (either flag absent) keeps the ordinary jump.
     */
    @Test
    fun theHistoryFlagsDecideWhatAPillTapDoes() {
        val cases =
            listOf(
                Triple(null, null, ThreadUnreadJumpHistoryAction.FocusParent),
                Triple(true, true, ThreadUnreadJumpHistoryAction.FocusParent),
                Triple(true, false, ThreadUnreadJumpHistoryAction.FocusParent),
                Triple(false, true, ThreadUnreadJumpHistoryAction.OpenThread),
                Triple(false, false, ThreadUnreadJumpHistoryAction.FocusParentAndRead),
                Triple(false, null, ThreadUnreadJumpHistoryAction.FocusParent),
            )
        for ((parentInHistory, repliesInHistory, expected) in cases) {
            val parent = resolved("t-old", "p-old", createdAt = 1_000L).second.copy(
                parentInHistory = parentInHistory,
                repliesInHistory = repliesInHistory,
            )
            val jump =
                projectThreadUnreadThreadJump(
                    unreadThreadIds = setOf("t-old"),
                    messages = messages,
                    visibleMessageIndexes = 8..9,
                    dismissedAtCount = null,
                    unloadedParents = mapOf("t-old" to parent),
                    loadedRange = ThreadJumpLoadedRange(newestCreatedAtEpochMillis = 10_000L, hasNewer = false),
                )
            assertEquals(expected, jump?.historyAction, "parentInHistory=$parentInHistory repliesInHistory=$repliesInHistory")
            assertEquals("p-old", jump?.targetMessageId)
        }
        // A thread whose parent is not resolved (no flags) keeps the ordinary jump.
        assertEquals(ThreadUnreadJumpHistoryAction.FocusParent, threadUnreadJumpHistoryAction(null))
    }

    @Test
    fun dismissHidesThePillUntilTheCountChanges() {
        assertNull(projectThreadUnreadThreadJump(unread, messages, visibleMessageIndexes = 2..3, dismissedAtCount = 3))

        val afterOneRead = projectThreadUnreadThreadJump(setOf("t1", "t8"), messages, visibleMessageIndexes = 2..3, dismissedAtCount = 3)
        assertEquals(ThreadUnreadThreadJump(ThreadUnreadThreadJumpDirection.Next, count = 2, targetMessageId = "m8", targetThreadChannelId = "t8"), afterOneRead)
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
