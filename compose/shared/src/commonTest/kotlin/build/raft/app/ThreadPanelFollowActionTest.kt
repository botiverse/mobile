package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * task #180 (artin): the thread panel's follow row.
 *
 * Web reads `followedThreads` from its thread store and drives the same follow/unfollow store
 * actions from the panel menu. Mobile's equivalent is the thread's origin message, which carries
 * `threadChannelId` + `isThreadFollowed` -- the exact state the long-press menu already reads, so
 * the panel and the long-press menu cannot disagree.
 *
 * These assert the row's derived state and the dispatched action, i.e. what a user sees and what
 * pressing it does -- not how the menu is assembled.
 */
class ThreadPanelFollowActionTest {
    private fun message(
        threadChannelId: String? = "thread-ch-1",
        isThreadFollowed: Boolean = false,
        id: String = "msg-1",
    ) = KuiklyThreadMessageItem(
        id = id,
        authorId = "author-1",
        authorName = "Author",
        threadChannelId = threadChannelId,
        isThreadFollowed = isThreadFollowed,
    )

    @Test
    fun anUnfollowedThreadOffersFollowAndDispatchesTheExistingFollowAction() {
        val action = threadPanelFollowAction(message(isThreadFollowed = false))

        assertEquals(false, action?.followed, "an unfollowed thread must read as not followed")
        assertEquals(FOLLOW_THREAD_ACTION, action?.action)
        // The panel must reuse the action the long-press menu already routes, not invent a second path.
        assertEquals("followMessageThread", action?.action)
    }

    @Test
    fun aFollowedThreadOffersUnfollowAndDispatchesTheExistingUnfollowAction() {
        val action = threadPanelFollowAction(message(isThreadFollowed = true))

        assertEquals(true, action?.followed, "a followed thread must read as followed")
        assertEquals(UNFOLLOW_THREAD_ACTION, action?.action)
        assertEquals("unfollowMessageThread", action?.action)
    }

    @Test
    fun theMenuLabelFollowsTheStateItWillToggleTo() {
        // Label and action are derived from the same boolean, so a stale label cannot show
        // "Unfollow" on a thread that is not followed (the classic toggle-menu bug).
        val followed = threadPanelFollowAction(message(isThreadFollowed = true))
        val unfollowed = threadPanelFollowAction(message(isThreadFollowed = false))

        assertEquals(true, followed?.followed)
        assertEquals(UNFOLLOW_THREAD_ACTION, followed?.action)
        assertEquals(false, unfollowed?.followed)
        assertEquals(FOLLOW_THREAD_ACTION, unfollowed?.action)
    }

    @Test
    fun theActionCarriesTheThreadChannelTheHandlerNeeds() {
        // The existing handler reads the thread channel from the attachmentId slot, so the id must
        // travel with the action rather than being re-derived at dispatch time.
        val action = threadPanelFollowAction(message(threadChannelId = "thread-ch-9"))

        assertEquals("thread-ch-9", action?.threadChannelId)
    }

    @Test
    fun anEmptyThreadOffersNoRowAtAll() {
        // No origin message (empty thread): the row is absent, not a disabled button.
        assertNull(threadPanelFollowAction(null))
    }

    @Test
    fun aMessageWithoutAThreadChannelOffersNoRow() {
        // Nothing to follow yet (e.g. a parent with no replies and no thread channel). Offering the
        // action here would dispatch with a blank id and fail at the network layer.
        assertNull(threadPanelFollowAction(message(threadChannelId = null)))
        assertNull(threadPanelFollowAction(message(threadChannelId = "")))
        assertNull(threadPanelFollowAction(message(threadChannelId = "   ")))
    }

    @Test
    fun togglingTheStateFlipsTheOfferedAction() {
        // Mirrors "cancel then reopen the menu": the same thread re-derives the opposite action from
        // store state, with no cached label to go stale.
        val before = threadPanelFollowAction(message(isThreadFollowed = true))
        val after = threadPanelFollowAction(message(isThreadFollowed = false))

        assertEquals(UNFOLLOW_THREAD_ACTION, before?.action)
        assertEquals(FOLLOW_THREAD_ACTION, after?.action)
    }
}
