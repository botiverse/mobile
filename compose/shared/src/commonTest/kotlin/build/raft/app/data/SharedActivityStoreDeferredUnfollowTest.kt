package build.raft.app.data

import build.raft.app.ActivityPendingSwipeDone
import build.raft.app.ActivitySwipeDeferredAction
import build.raft.app.ActivitySwipeDoneTransition
import build.raft.app.ActivitySwipeDoneUndoState
import build.raft.app.activitySwipeDeferredCommitIntent
import build.raft.app.activitySwipeListedItems
import build.raft.app.model.ChannelId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * raft-design #790 (Jasper and Remy): a thread's Unfollow from Activity (swipe button or menu) takes the
 * row out at once and is only sent when its undo toast ends; Undo sends nothing and shows the row again
 * exactly as it was (unfollowing marks the thread read on the server, so a Follow after it could not).
 * Drives the panel's state machine ([ActivitySwipeDoneUndoState]) against the Activity store and a fake
 * thread API, the way SharedInboxPanel does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreDeferredUnfollowTest {

    private val threadA = threadItem("thread-a", unreadCount = 2).copy(isFollowing = true)
    private val threadB = threadItem("thread-b", unreadCount = 1).copy(isFollowing = true)

    /** The panel: the waiting row, the threads unfollowed while this list is shown, and its commit. */
    private class Panel(val store: SharedActivityStore, val ownerUserId: String = "user-1", val ownerServerId: String = "server-1") {
        var undo = ActivitySwipeDoneUndoState()
        var committedUnfollowKeys = emptySet<String>()

        val listed: List<InboxItem>
            get() = activitySwipeListedItems(store.state.value.items, undo.hiddenKey, committedUnfollowKeys)

        fun unfollow(item: InboxItem) =
            apply(undo.swipe(item, ActivitySwipeDeferredAction.Unfollow, ownerUserId, ownerServerId))

        fun done(item: InboxItem) = apply(undo.swipe(item))

        fun undoToast() {
            undo = undo.undo(undo.pending!!.token)
        }

        fun toastEnds() = apply(undo.expire(undo.pending!!.token))

        fun leave() = apply(undo.flush())

        private fun apply(transition: ActivitySwipeDoneTransition) {
            undo = transition.state
            transition.commit?.let(::commit)
        }

        private fun commit(pending: ActivityPendingSwipeDone) {
            val current = store.state.value.items.firstOrNull { it.key == pending.item.key }
            val intent = activitySwipeDeferredCommitIntent(pending, current) ?: return
            if (intent is SharedActivityIntent.UnfollowThread) committedUnfollowKeys = committedUnfollowKeys + pending.item.key
            store.dispatch(intent)
        }
    }

    @Test
    fun noRequestBeforeTheToastEndsAndExactlyOneAfter() = runTest {
        val conversation = FakeConversationRepository()
        val store =
            activityStore(backgroundScope, FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(threadA)))), conversationRepository = conversation)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val panel = Panel(store)

        panel.unfollow(threadA)
        runCurrent()
        assertEquals(emptyList(), conversation.unfollowedThreadChannelIds, "nothing is sent while the toast is up")
        assertEquals(emptyList(), panel.listed, "the row leaves at once")
        assertEquals(2, store.state.value.items.single().unreadCount, "the store's row is untouched while it waits")

        panel.toastEnds()
        runCurrent()
        assertEquals(listOf(ChannelId("thread-a")), conversation.unfollowedThreadChannelIds)
        // All lists unfollowed threads; this list still keeps the row out after the Unfollow lands.
        assertEquals(false, store.state.value.items.single().isFollowing)
        assertEquals(emptyList(), panel.listed)

        // A late timer or a leave afterwards sends nothing more.
        panel.leave()
        runCurrent()
        assertEquals(1, conversation.unfollowedThreadChannelIds.size)
    }

    @Test
    fun undoSendsNothingAndRestoresTheRowExactly() = runTest {
        val conversation = FakeConversationRepository()
        val store =
            activityStore(backgroundScope, FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(threadA)))), conversationRepository = conversation)
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        runCurrent()
        val panel = Panel(store)
        val before = panel.listed

        panel.unfollow(threadA)
        val token = panel.undo.pending!!.token
        panel.undoToast()
        // The toast's timer firing after Undo, and leaving the list, commit nothing.
        assertNull(panel.undo.expire(token).commit)
        panel.leave()
        runCurrent()

        assertEquals(emptyList(), conversation.unfollowedThreadChannelIds)
        assertEquals(emptyList(), conversation.followedParentMessageIds, "Undo has nothing to reverse")
        assertEquals(before, panel.listed, "same row, same place, still unread and followed")
        assertEquals(threadA, panel.listed.single())
    }

    @Test
    fun severalUnfollowsInARowEachSendOnce() = runTest {
        val conversation = FakeConversationRepository()
        val store =
            activityStore(
                backgroundScope,
                FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(threadA, threadB)))),
                conversationRepository = conversation,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val panel = Panel(store)

        panel.unfollow(threadA)
        runCurrent()
        assertEquals(emptyList(), conversation.unfollowedThreadChannelIds)

        // The second Unfollow's toast replaces the first: the first is sent now, the second waits.
        panel.unfollow(threadB)
        runCurrent()
        assertEquals(listOf(ChannelId("thread-a")), conversation.unfollowedThreadChannelIds)
        assertEquals(emptyList(), panel.listed, "both rows are out of the list")

        panel.toastEnds()
        runCurrent()
        assertEquals(listOf(ChannelId("thread-a"), ChannelId("thread-b")), conversation.unfollowedThreadChannelIds)
    }

    @Test
    fun aWaitingUnfollowIsSentOnceWhenTheListIsLeft() = runTest {
        val conversation = FakeConversationRepository()
        val store =
            activityStore(backgroundScope, FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(threadA)))), conversationRepository = conversation)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val panel = Panel(store)

        panel.unfollow(threadA)
        panel.leave()
        runCurrent()
        assertEquals(listOf(ChannelId("thread-a")), conversation.unfollowedThreadChannelIds)
    }

    @Test
    fun aWaitingUnfollowStillLandsAfterTheFilterDroppedTheRow() = runTest {
        val conversation = FakeConversationRepository()
        val store =
            activityStore(
                backgroundScope,
                FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(threadA)), snapshot(emptyList()))),
                conversationRepository = conversation,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val panel = Panel(store)

        panel.unfollow(threadA)
        // The person switches to Mentions: the store no longer holds the row when the list goes away.
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Mentions))
        runCurrent()
        assertEquals(emptyList(), store.state.value.items)
        panel.leave()
        runCurrent()
        assertEquals(listOf(ChannelId("thread-a")), conversation.unfollowedThreadChannelIds)
    }

    @Test
    fun aWaitingUnfollowFromAnotherServersListWritesNothing() = runTest {
        val conversation = FakeConversationRepository()
        val store =
            activityStore(backgroundScope, FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(threadA)))), conversationRepository = conversation)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val otherServerPanel = Panel(store, ownerServerId = "server-2")

        otherServerPanel.unfollow(threadA.copy(key = "thread:elsewhere"))
        otherServerPanel.leave()
        runCurrent()
        assertEquals(emptyList(), conversation.unfollowedThreadChannelIds)
    }

    @Test
    fun aWaitingUnfollowIsSentEvenIfTheThreadGotNewActivity() = runTest {
        val swiped = threadA
        val pending = ActivitySwipeDoneUndoState().swipe(swiped, ActivitySwipeDeferredAction.Unfollow, "user-1", "server-1").state.pending!!
        val newer = swiped.copy(unreadCount = 5, latestActivityAt = "2026-10-28T10:00:00Z", doneFrontierSeq = "99")
        val intent = assertIs<SharedActivityIntent.UnfollowThread>(activitySwipeDeferredCommitIntent(pending, newer))
        assertEquals(newer, intent.item)
        assertEquals("user-1", intent.listOwnerUserId)
        assertEquals("server-1", intent.listOwnerServerId)
        // A Done in the same spot is dropped (unchanged).
        val done = ActivitySwipeDoneUndoState().swipe(swiped).state.pending!!
        assertNull(activitySwipeDeferredCommitIntent(done, newer.copy(isFollowing = true)))
    }

    @Test
    fun aFailedUnfollowShowsTheRowAgain() = runTest {
        val conversation = FakeConversationRepository().apply { unfollowResult = DataResult.Error(ScreenError("offline")) }
        val store =
            activityStore(backgroundScope, FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(threadA)))), conversationRepository = conversation)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val panel = Panel(store)

        panel.unfollow(threadA)
        panel.toastEnds()
        runCurrent()
        assertEquals(1, conversation.unfollowedThreadChannelIds.size)
        assertEquals(listOf(threadA.key), panel.listed.map { it.key })
        assertEquals(true, panel.listed.single().isFollowing)
    }
}
