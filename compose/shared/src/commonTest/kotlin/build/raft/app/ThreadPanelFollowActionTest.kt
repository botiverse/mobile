package build.raft.app

import build.raft.app.thread.FOLLOW_THREAD_ACTION
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.UNFOLLOW_THREAD_ACTION
import build.raft.app.thread.threadPanelFollowAction
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
    ) = RaftThreadMessageItem(
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
    fun theActionCarriesTheThreadChannelTheHandlerNeeds() {
        // The existing handler reads the thread channel from the attachmentId slot, so the id must
        // travel with the action rather than being re-derived at dispatch time.
        val action = threadPanelFollowAction(message(threadChannelId = "thread-ch-9"))

        assertEquals("thread-ch-9", action?.threadChannelId)
    }

    @Test
    fun aMessageWithoutAThreadChannelOffersNoRow() {
        // Nothing to follow yet (e.g. a parent with no replies and no thread channel). Offering the
        // action here would dispatch with a blank id and fail at the network layer.
        assertNull(threadPanelFollowAction(message(threadChannelId = null)))
        assertNull(threadPanelFollowAction(message(threadChannelId = "")))
        assertNull(threadPanelFollowAction(message(threadChannelId = "   ")))
    }
}
