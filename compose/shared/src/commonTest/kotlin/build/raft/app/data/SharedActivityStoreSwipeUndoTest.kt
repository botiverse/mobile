package build.raft.app.data

import build.raft.app.ActivitySwipeEndAction
import build.raft.app.ActivitySwipeEndCommand
import build.raft.app.activitySwipeEndApplyCommand
import build.raft.app.activitySwipeEndUndoCommand
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Rule L swipe toward the end on a thread row (ActivityConversationSwipe.kt): the intents the panel
 * sends on release and on Undo, run through the Activity store against a fake thread API.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreSwipeUndoTest {

    private val followed = threadItem("thread-a", unreadCount = 2).copy(isFollowing = true)

    private fun applyIntent(item: InboxItem): SharedActivityIntent =
        (activitySwipeEndApplyCommand(item, ActivitySwipeEndAction.UnfollowThread) as ActivitySwipeEndCommand.Activity).intent

    private fun undoIntent(item: InboxItem): SharedActivityIntent =
        (activitySwipeEndUndoCommand(item, ActivitySwipeEndAction.UnfollowThread) as ActivitySwipeEndCommand.Activity).intent

    @Test
    fun inAnAttentionFilterTheUnfollowedRowLeavesAndUndoFollowsItAndReloadsItBack() = runTest {
        val conversation = FakeConversationRepository()
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(followed))))
        val store = activityStore(backgroundScope, inbox, conversationRepository = conversation)
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        runCurrent()
        assertEquals(listOf(followed.key), store.state.value.items.map { it.key })

        store.dispatch(applyIntent(followed))
        runCurrent()
        assertEquals(listOf(ChannelId("thread-a")), conversation.unfollowedThreadChannelIds)
        assertEquals(emptyList(), store.state.value.items, "the Unread filter drops an unfollowed thread")
        val loadsBeforeUndo = inbox.initialLoadCount

        store.dispatch(undoIntent(followed))
        runCurrent()
        assertEquals(listOf(MessageId("parent-message")), conversation.followedParentMessageIds)
        assertEquals(loadsBeforeUndo + 1, inbox.initialLoadCount, "Undo reloads so the server brings the row back")
        val back = store.state.value.items.singleOrNull { it.key == followed.key }
        assertNotNull(back, "row back after Undo, items=${store.state.value.items.map { it.key }}")
        assertEquals(true, back.isFollowing)
    }

    @Test
    fun inAllTheUnfollowedRowStaysMarkedAndUndoFollowsItInPlace() = runTest {
        val conversation = FakeConversationRepository()
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(followed))))
        val store = activityStore(backgroundScope, inbox, conversationRepository = conversation)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(applyIntent(followed))
        runCurrent()
        assertEquals(false, store.state.value.items.single().isFollowing)

        store.dispatch(undoIntent(followed))
        runCurrent()
        assertEquals(listOf(MessageId("parent-message")), conversation.followedParentMessageIds)
        assertEquals(true, store.state.value.items.single().isFollowing)
        assertEquals(1, inbox.initialLoadCount, "a row still listed is followed in place, without a reload")
    }

    @Test
    fun undoWhileTheUnfollowIsInFlightFollowsOnceItSettles() = runTest {
        val gate = CompletableDeferred<Unit>()
        val conversation = FakeConversationRepository().apply { unfollowGate = gate }
        val store =
            activityStore(
                backgroundScope,
                FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(followed)))),
                conversationRepository = conversation,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(applyIntent(followed))
        runCurrent()
        store.dispatch(undoIntent(followed))
        runCurrent()
        assertEquals(emptyList(), conversation.followedParentMessageIds, "no Follow races the Unfollow on the wire")

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(MessageId("parent-message")), conversation.followedParentMessageIds)
        assertEquals(true, store.state.value.items.single().isFollowing)
    }

    @Test
    fun undoAfterAFailedUnfollowSendsNothing() = runTest {
        val conversation = FakeConversationRepository().apply { unfollowResult = DataResult.Error(ScreenError("offline")) }
        val store =
            activityStore(
                backgroundScope,
                FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(followed)))),
                conversationRepository = conversation,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(applyIntent(followed))
        runCurrent()
        // The failure restored the followed row; Undo has nothing to reverse.
        store.dispatch(undoIntent(followed))
        runCurrent()
        assertEquals(emptyList(), conversation.followedParentMessageIds)
        assertNull(store.state.value.items.single().isFollowing.takeIf { it == false })
    }
}
