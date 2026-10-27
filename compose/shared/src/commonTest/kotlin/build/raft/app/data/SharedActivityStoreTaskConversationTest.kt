package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.ThreadFollowersUpdated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task v2 Activity rows (web ThreadsInbox / InboxContextMenu / inboxRowActions): a task
 * conversation row is a thread row keyed by its `threadChannelId` (the reader's local
 * conversation). Read / unread use `POST /channels/:id/read-all` / `unread` on that id, Unfollow
 * `/channels/threads/unfollow {threadChannelId}`, and Follow, which for a thread names the parent
 * message, names the conversation itself (`/channels/threads/follow {threadChannelId}`, slock
 * staging 0000139628e0 `routes/channels.ts`).
 */
class SharedActivityStoreTaskConversationTest {
    @Test
    fun taskRowReadUnreadFollowAndUnfollowAllUseTheConversationId() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val conversation =
            FakeConversationRepository().apply {
                followResult = DataResult.Content(ThreadActionResult(ChannelId("conv-1"), changed = true))
                unfollowResult = DataResult.Content(ThreadActionResult(ChannelId("conv-1"), changed = true))
            }
        val readReceipts = FakeReadReceiptRepository()
        val row = taskRow(unreadCount = 2)
        val store =
            activityStore(
                scope = scope,
                conversationRepository = conversation,
                readReceiptRepository = readReceipts,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(row)))),
            )
        store.dispatch(SharedActivityIntent.LoadInitial)

        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        assertEquals(0, store.state.value.items.single().unreadCount)
        store.dispatch(SharedActivityIntent.MarkUnread(store.state.value.items.single()))
        assertEquals(1, store.state.value.items.single().unreadCount)
        assertEquals(listOf(ChannelId("conv-1")), readReceipts.readAllChannelIds)
        assertEquals(listOf(ChannelId("conv-1")), readReceipts.unreadChannelIds)

        store.dispatch(SharedActivityIntent.UnfollowThread(store.state.value.items.single()))
        assertEquals(false, store.state.value.items.single().isFollowing)
        store.dispatch(SharedActivityIntent.FollowThread(store.state.value.items.single()))
        assertEquals(true, store.state.value.items.single().isFollowing)

        assertEquals(listOf(ChannelId("conv-1")), conversation.unfollowedThreadChannelIds)
        assertEquals(listOf(ChannelId("conv-1")), conversation.followedTaskConversationIds)
        assertEquals(emptyList(), conversation.followedParentMessageIds, "a task row never follows by a parent message")
        scope.cancel()
    }

    /**
     * `thread:followers-updated { threadChannelId }` for a task conversation row: the reader may have
     * unfollowed on another device, so Activity re-reads (background reconcile). A frame for a
     * conversation with no task row here re-reads nothing.
     */
    @Test
    fun followersUpdatedForATaskRowReReadsActivityAndOthersDoNot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val row = taskRow(unreadCount = 0)
        val inbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(row)), snapshot(listOf(row.copy(isFollowing = false))))
            )
        val store = activityStore(scope = scope, inboxRepository = inbox, realtimeBackgroundRefreshIntervalMillis = 0L)
        store.dispatch(SharedActivityIntent.LoadInitial)
        assertEquals(1, inbox.initialLoadCount)

        store.acceptRealtime(SharedActivityIntent.ApplyRealtimeEvent(ThreadFollowersUpdated(ChannelId("conv-other"))))
        assertEquals(1, inbox.initialLoadCount, "no task row for that conversation")

        store.acceptRealtime(SharedActivityIntent.ApplyRealtimeEvent(ThreadFollowersUpdated(ChannelId("conv-1"))))
        assertEquals(2, inbox.initialLoadCount)
        assertEquals(false, store.state.value.items.single().isFollowing)
        scope.cancel()
    }

    private fun taskRow(unreadCount: Int): InboxItem =
        InboxItem(
            key = "thread:conv-1",
            kind = build.raft.app.model.InboxItemKind.Thread,
            channelId = ChannelId("conv-1"),
            channelName = "general",
            unreadCount = unreadCount,
            firstUnreadMessageId = MessageId("reply-2"),
            threadChannelId = ChannelId("conv-1"),
            parentChannelId = ChannelId("channel-1"),
            parentChannelName = "general",
            parentChannelKind = ConversationKind.Channel,
            latestActivityMessageId = MessageId("reply-3"),
            isFollowing = true,
            taskNumber = 7,
            taskConversation = InboxTaskConversation(taskId = TaskId("task-1"), taskTitle = "Ship the task conversation"),
        )
}
