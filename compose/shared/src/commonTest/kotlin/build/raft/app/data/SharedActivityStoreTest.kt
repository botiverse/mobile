package build.raft.app.data

import build.raft.app.SharedThreadFreshnessLedger
import build.raft.app.FakeChannelRepository
import build.raft.app.toConversationSummary
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageConversationContext
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.network.sync.ReadStateV1Fact
import build.raft.app.network.sync.ReadStateV1ProductionCanary
import build.raft.app.network.sync.ReadStateV1ProductionOwner
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedActivityStoreTest {

    @Test
    fun loadMoreMergesWithoutDuplicateRows() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1"), activityItem("channel-2")), hasMore = true, nextOffset = 2, totalCount = 3),
                    ),
                moreSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-2"), activityItem("channel-3")), hasMore = false, nextOffset = 4, totalCount = 3)
                    )
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.LoadMore)

        assertEquals(listOf("channel:channel-1", "channel:channel-2", "channel:channel-3"), store.state.value.items.map { it.key })
        assertEquals(listOf(2), inbox.loadMoreOffsets)
        assertEquals(false, store.state.value.hasMore)
        scope.cancel()
    }

    @Test
    fun activityFilterProjectionRetainsUnfollowedOnlyInAllAndNeverCountsItsAttention() {
        val unfollowed =
            threadItem("thread-unfollowed", unreadCount = 5).copy(
                isFollowing = false,
                hasMention = true
            )
        val followed =
            threadItem("thread-followed", unreadCount = 2).copy(
                isFollowing = true,
                hasMention = true
            )
        val raw = snapshot(listOf(unfollowed, followed), totalCount = 2, totalUnreadCount = 2)

        val all = raw.projectActivityFilter(InboxFilter.All)
        assertEquals(listOf("thread:thread-unfollowed", "thread:thread-followed"), all.items.map(InboxItem::key))
        assertEquals(0, all.items.first().unreadCount)
        assertEquals(false, all.items.first().hasMention)
        assertEquals(2, all.totalUnreadCount)
        assertEquals(2, all.totalCount)

        val unread = raw.projectActivityFilter(InboxFilter.Unread)
        assertEquals(listOf("thread:thread-followed"), unread.items.map(InboxItem::key))
        assertEquals(2, unread.totalUnreadCount)
        assertEquals(1, unread.totalCount)

        val mentions = raw.projectActivityFilter(InboxFilter.Mentions)
        assertEquals(listOf("thread:thread-followed"), mentions.items.map(InboxItem::key))
        assertEquals(2, mentions.totalUnreadCount)
        assertEquals(1, mentions.totalCount)
    }

    @Test
    fun activityTasksFilterProjectionKeepsOnlyTaskConversationsAndStripsObjectCounts() {
        val channel = activityItem("channel-1", unreadCount = 3)
        val regularThread = threadItem("thread-1", unreadCount = 1).copy(isFollowing = true)
        val followedTask =
            threadItem("task-conv-1", unreadCount = 4).copy(
                isFollowing = true,
                taskConversation = InboxTaskConversation(taskId = TaskId("task-1"), taskTitle = "Ship Tasks filter"),
            )
        val unfollowedTask =
            threadItem("task-conv-2", unreadCount = 2).copy(
                isFollowing = false,
                hasMention = true,
                taskConversation = InboxTaskConversation(taskId = TaskId("task-2"), taskTitle = "Muted task"),
            )
        val artifactObject =
            InboxItem(
                key = "object:artifact:00000000-0000-0000-0000-000000000001",
                kind = InboxItemKind.Object,
                channelId = ChannelId("object:artifact:00000000-0000-0000-0000-000000000001"),
                channelName = "Spec",
                unreadCount = 1,
                activityObject = ActivityObjectRow(objectKind = "artifact", objectId = "00000000-0000-0000-0000-000000000001", title = "Spec"),
            )
        val raw =
            snapshot(
                items = listOf(channel, regularThread, followedTask, unfollowedTask, artifactObject),
                totalCount = 4,
                totalUnreadCount = 8,
            ).copy(
                objectCounts = ActivityObjectCounts(objectUnreadCount = 1, objectMentionUnreadCount = 0, objectCount = 1),
            )

        val projected = raw.projectActivityFilter(InboxFilter.Tasks)

        assertEquals(listOf("thread:task-conv-1", "thread:task-conv-2"), projected.items.map(InboxItem::key))
        assertEquals(4, projected.items[0].unreadCount)
        assertEquals(0, projected.items[1].unreadCount)
        assertEquals(false, projected.items[1].hasMention)
        assertEquals(2, projected.totalCount)
        assertEquals(4, projected.totalUnreadCount)
        assertNull(projected.objectCounts)
    }

    @Test
    fun unfollowedThreadRemainsInAllAndRealtimeRepliesStayAttentionless() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val unfollowed =
            threadItem("thread-unfollowed", unreadCount = 0).copy(
                isFollowing = false,
                replyCount = 1,
                hasMention = false
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots = listOf(snapshot(listOf(unfollowed), totalUnreadCount = 0))
                    )
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("unfollowed-reply", channelId = "thread-unfollowed"))
            )
        )

        val row = store.state.value.items.single()
        assertEquals(false, row.isFollowing)
        assertEquals(2, row.replyCount)
        assertEquals(0, row.unreadCount)
        assertEquals(false, row.hasMention)
        assertEquals(null, row.firstUnreadMessageId)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun activityUnfollowKeepsRowAndClearsAttentionThroughSharedTransport() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val conversation = FakeConversationRepository().apply {
            unfollowResult = DataResult.Content(ThreadActionResult(ChannelId("thread-followed"), changed = true))
        }
        val followed =
            threadItem("thread-followed", unreadCount = 3).copy(
                isFollowing = true,
                hasMention = true
            )
        val store =
            activityStore(
                scope = scope,
                conversationRepository = conversation,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(followed), totalUnreadCount = 3)))
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.UnfollowThread(followed))

        val row = store.state.value.items.single()
        assertEquals(false, row.isFollowing)
        assertEquals(0, row.unreadCount)
        assertEquals(false, row.hasMention)
        assertEquals(null, row.firstUnreadMessageId)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(listOf(ChannelId("thread-followed")), conversation.unfollowedThreadChannelIds)
        scope.cancel()
    }

    @Test
    fun canonicalServerRefollowMakesChipDisappearAndRestoresOnlyServerAttention() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val unfollowed =
            threadItem("thread-unfollowed").copy(
                isFollowing = false,
                replyCount = 1,
                unreadCount = 0,
                hasMention = false
            )
        val serverRefollowed =
            unfollowed.copy(
                isFollowing = true,
                replyCount = 2,
                latestActivityMessageId = MessageId("direct-mention-reply"),
                unreadCount = 1,
                hasMention = true,
                firstUnreadMessageId = MessageId("direct-mention-reply")
            )
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(unfollowed)),
                        snapshot(listOf(serverRefollowed), totalUnreadCount = 1)
                    )
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.acceptRealtime(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("direct-mention-reply", channelId = "thread-unfollowed"))
            )
        )

        val row = store.state.value.items.single()
        assertEquals(true, row.isFollowing)
        assertEquals(1, row.unreadCount)
        assertEquals(true, row.hasMention)
        assertEquals(MessageId("direct-mention-reply"), row.firstUnreadMessageId)
        assertEquals(1, store.state.value.totalUnreadCount)
        assertEquals(2, inbox.initialLoadCount)
        scope.cancel()
    }

    @Test
    fun canonicalRefreshRequestsLoadedDepthAndPreservesLoadedTailOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val head = (1..30).map { index -> activityItem("channel-$index") }
        val tail = (31..60).map { index -> activityItem("channel-$index") }
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(head, hasMore = true, nextOffset = 30, totalCount = 60),
                        snapshot(head + tail, hasMore = false, nextOffset = 60, totalCount = 60)
                    ),
                moreSnapshots =
                    listOf(
                        snapshot(tail, hasMore = false, nextOffset = 60, totalCount = 60)
                    )
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.LoadMore)
        val loadedTailKeys = store.state.value.items.drop(30).map { item -> item.key }

        store.dispatch(SharedActivityIntent.Refresh)

        assertEquals(listOf(30, 60), inbox.initialLoadLimits)
        assertEquals(60, store.state.value.items.size)
        assertEquals(loadedTailKeys, store.state.value.items.drop(30).map { item -> item.key })
        assertEquals(false, store.state.value.loading)
        assertEquals(false, store.state.value.refreshing)
        scope.cancel()
    }

    @Test
    fun advancingDuplicateRefreshPagesAreBoundedAndKeepThePreviousSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rows = (1..30).map { index -> activityItem("channel-$index") }
        var initialLoads = 0
        var continuationLoads = 0
        val inbox =
            object : InboxRepository {
                override suspend fun loadInitial(
                    serverId: ServerId,
                    filter: InboxFilter,
                    limit: Int
                ): DataResult<InboxSnapshot> {
                    initialLoads += 1
                    return DataResult.Content(
                        if (initialLoads == 1) {
                            snapshot(rows, hasMore = false, nextOffset = 30, totalCount = 30)
                        } else {
                            snapshot(rows.take(1), hasMore = true, nextOffset = 1, totalCount = 30)
                        }
                    )
                }

                override suspend fun loadMore(
                    serverId: ServerId,
                    filter: InboxFilter,
                    offset: Int,
                    limit: Int
                ): DataResult<InboxSnapshot> {
                    continuationLoads += 1
                    return DataResult.Content(
                        snapshot(
                            rows.take(1),
                            hasMore = true,
                            nextOffset = offset + 1,
                            totalCount = 30
                        )
                    )
                }

                override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> =
                    DataResult.Content(InboxDoneResult(channelId))
            }
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        val stableKeys = store.state.value.items.map { item -> item.key }
        store.dispatch(SharedActivityIntent.Refresh)

        assertEquals(3, continuationLoads)
        assertEquals(stableKeys, store.state.value.items.map { item -> item.key })
        // A stalled later page ends the read with what it fetched (Hands f6c3f3a6), not an error.
        assertEquals(null, store.state.value.errorCode)
        assertEquals(false, store.state.value.refreshing)
        scope.cancel()
    }

    @Test
    fun unreadFilterMarkReadKeepsTheRowShownReadAndCallsReadAll() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 2)), totalUnreadCount = 2)))
        val read = FakeReadReceiptRepository()
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(homeSnapshot(activityItem("channel-1", unreadCount = 2).toConversationSummary(), unreadTotal = 2))
        val store =
            activityStore(
                scope,
                inbox,
                readReceiptRepository = read,
            )

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))

        // The row just read stays listed, read, until the user moves on (RetainedReadRowTest).
        assertEquals(listOf("channel:channel-1" to 0), store.state.value.items.map { it.key to it.unreadCount })
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(listOf(ChannelId("channel-1")), read.readAllChannelIds)
        assertEquals(2, localStore.readHome(ServerId("server-1"))?.channels?.single()?.unreadCount)
        assertEquals(2, localStore.readHome(ServerId("server-1"))?.unreadTotal)
        scope.cancel()
    }

    @Test
    fun staleActivityRefreshAfterMarkReadDoesNotRestoreUnread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)
        val unreadRow = activityItem("channel-1", unreadCount = 2)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(unreadRow), totalUnreadCount = 2))
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        inbox.completeNextSnapshot(snapshot(listOf(unreadRow), totalUnreadCount = 2))

        val row = store.state.value.items.single()
        assertEquals(0, row.unreadCount)
        assertEquals(null, row.firstUnreadMessageId)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun markReadAllowsLaterUnreadWithDifferentAnchor() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)
        val unreadRow = activityItem("channel-1", unreadCount = 1)
        val laterUnreadRow =
            unreadRow.copy(
                unreadCount = 1,
                firstUnreadMessageId = MessageId("first-channel-1-new"),
                latestActivityMessageId = MessageId("latest-channel-1-new")
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(unreadRow), totalUnreadCount = 1))
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        inbox.completeNextSnapshot(snapshot(listOf(laterUnreadRow), totalUnreadCount = 1))

        val row = store.state.value.items.single()
        assertEquals(1, row.unreadCount)
        assertEquals(MessageId("first-channel-1-new"), row.firstUnreadMessageId)
        assertEquals(1, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun markAllReadKeepsRowsAndClearsCountsInAllFilter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pendingReadAll = CompletableDeferred<DataResult<ReadReceiptResult>>()
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 3)), totalCount = 8, totalUnreadCount = 3)
                    )
            )
        val store =
            activityStore(
                scope,
                inbox,
                readReceiptRepository = FakeReadReceiptRepository(pendingInboxReadAllResult = pendingReadAll)
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.MarkAllRead)

        val item = store.state.value.items.single()
        assertEquals(0, item.unreadCount)
        assertEquals(null, item.firstUnreadMessageId)
        assertEquals(false, item.hasMention)
        assertEquals(8, store.state.value.totalCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        pendingReadAll.complete(DataResult.Content(ReadReceiptResult()))
        scope.cancel()
    }

    @Test
    fun activitySilentRefreshDoesNotDrivePullRefreshState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNext(activityItem("channel-1"))

        store.dispatch(SharedActivityIntent.Refresh)

        assertEquals(false, store.state.value.loading)
        assertEquals(true, store.state.value.refreshing)
        assertEquals(false, store.state.value.pullRefreshing)
        inbox.completeNext(activityItem("channel-2"))

        val staleSnapshot = store.state.value.snapshot
        store.dispatch(SharedActivityIntent.PullRefresh)

        assertEquals(false, store.state.value.loading)
        assertEquals(true, store.state.value.refreshing)
        assertEquals(true, store.state.value.pullRefreshing)
        assertEquals(staleSnapshot, store.state.value.snapshot)
        assertEquals(listOf("channel-2"), store.state.value.items.map { it.channelId.value })
        inbox.completeNext(activityItem("channel-3"))
        assertEquals(false, store.state.value.refreshing)
        assertEquals(false, store.state.value.pullRefreshing)
        assertEquals(listOf("channel-3"), store.state.value.items.map { it.channelId.value })
        scope.cancel()
    }

    @Test
    fun messageNewPatchesExistingActivityRowImmediatelyAndDedupesByMessageId() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(
                            listOf(threadItem("thread-channel-1", unreadCount = 1).copy(replyCount = 2)),
                            totalCount = 1,
                            totalUnreadCount = 1
                        )
                    )
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox
            )
        val event =
            MessageNew(
                message(
                    id = "message-new",
                    channelId = "thread-channel-1",
                    body = "Realtime preview",
                    timestamp = "2026-07-13T03:01:00Z",
                    conversationContext = MessageConversationContext(channelKind = ConversationKind.Thread)
                )
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(event))

        val patched = store.state.value.items.single()
        assertEquals(MessageId("message-new"), patched.latestActivityMessageId)
        assertEquals("Realtime preview", patched.latestActivityPreview)
        assertEquals("User user-2", patched.latestActivitySenderName)
        assertEquals(3, patched.replyCount)
        assertEquals(1, patched.unreadCount, "web never adds unread per reply")
        assertEquals(1, store.state.value.totalUnreadCount)
        assertEquals(1, inbox.initialLoadCount)

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(event))

        assertEquals(3, store.state.value.items.single().replyCount)
        assertEquals(1, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun threadMessagePatchUpdatesAndReordersExistingThreadRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val thread = threadItem("thread-channel-1", unreadCount = 1).copy(replyCount = 3)
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots =
                            listOf(
                                snapshot(
                                    listOf(activityItem("channel-1"), thread),
                                    totalCount = 2,
                                    totalUnreadCount = 1
                                )
                            )
                    )
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "reply-new",
                        channelId = "thread-channel-1",
                        body = "New reply",
                        timestamp = "2026-07-13T03:02:00Z",
                        conversationContext =
                            MessageConversationContext(
                                channelKind = ConversationKind.Thread,
                                parentMessageId = MessageId("parent-message"),
                                parentChannelId = ChannelId("parent-channel"),
                                parentChannelKind = ConversationKind.Channel
                            )
                    )
                )
            )
        )

        val patched = store.state.value.items.first()
        assertEquals("thread:thread-channel-1", patched.key)
        assertEquals(MessageId("reply-new"), patched.latestActivityMessageId)
        assertEquals("New reply", patched.latestActivityPreview)
        assertEquals(4, patched.replyCount)
        assertEquals(1, patched.unreadCount, "unread is the server's")
        scope.cancel()
    }

    @Test
    fun threadUpdatedPatchesMonotonicReplyMetaButNeverOverwritesUnread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots =
                            listOf(
                                snapshot(
                                    listOf(
                                        threadItem("thread-channel-1", unreadCount = 1)
                                            .copy(replyCount = 2, latestActivityAt = "2026-07-13T03:00:00Z")
                                    ),
                                    totalUnreadCount = 1
                                )
                            )
                    )
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("parent-message"),
                    threadChannelId = ChannelId("thread-channel-1"),
                    replyCount = 3,
                    lastReplyAt = "2026-07-13T03:05:00Z",
                    participantIds = emptyList(),
                    unreadCount = 9,
                    firstUnreadMessageId = MessageId("server-first-unread")
                )
            )
        )

        val patched = store.state.value.items.single()
        assertEquals(3, patched.replyCount)
        assertEquals("2026-07-13T03:05:00Z", patched.latestActivityAt)
        assertEquals(1, patched.unreadCount, "web updateThreadActivityMeta never overwrites unread")
        assertEquals(MessageId("first-thread-channel-1"), patched.firstUnreadMessageId)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("parent-message"),
                    threadChannelId = ChannelId("thread-channel-1"),
                    replyCount = 2,
                    lastReplyAt = "2026-07-13T03:01:00Z",
                    participantIds = emptyList()
                )
            )
        )

        assertEquals(3, store.state.value.items.single().replyCount)
        assertEquals("2026-07-13T03:05:00Z", store.state.value.items.single().latestActivityAt)
        assertEquals(1, store.state.value.items.single().unreadCount)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("parent-message"),
                    threadChannelId = ChannelId("thread-channel-1"),
                    replyCount = 3,
                    lastReplyAt = "2026-07-13T03:05:00Z",
                    participantIds = emptyList(),
                    unreadCount = 0,
                    firstUnreadMessageId = MessageId("must-be-cleared")
                )
            )
        )

        assertEquals(1, store.state.value.items.single().unreadCount)
        assertEquals(MessageId("first-thread-channel-1"), store.state.value.items.single().firstUnreadMessageId)
        scope.cancel()
    }

    @Test
    fun servedNewerReplyReplacesAThreadUpdatedOnlyPatchAtTheSameReplyCount() = runBlocking {
        // fb-c04c1199: a joint channel's thread row got `thread:updated` (count + time) but its
        // `message:new` came under the origin thread id, so the preview stayed on the old reply.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val shown =
            threadItem("thread-joint").copy(
                replyCount = 648,
                latestActivityMessageId = MessageId("arkbriar-0344"),
                latestActivityPreview = "old reply",
                latestActivityAt = "2026-11-08T19:44:00Z",
                latestActivitySeq = "21014662"
            )
        val served =
            shown.copy(
                replyCount = 650,
                latestActivityMessageId = MessageId("notzz-1008"),
                latestActivityPreview = "new reply",
                latestActivityAt = "2026-11-09T02:08:23Z",
                latestActivitySeq = "21035595"
            )
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(shown)), snapshot(listOf(served))))
        val store = activityStore(scope = scope, inboxRepository = inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("parent-message"),
                    threadChannelId = ChannelId("thread-joint"),
                    replyCount = 650,
                    lastReplyAt = "2026-11-09T02:08:23Z",
                    participantIds = emptyList()
                )
            )
        )
        assertEquals("old reply", store.state.value.items.single().latestActivityPreview)

        store.dispatch(SharedActivityIntent.Refresh)

        val row = store.state.value.items.single()
        assertEquals("new reply", row.latestActivityPreview)
        assertEquals(MessageId("notzz-1008"), row.latestActivityMessageId)
        assertEquals("21035595", row.latestActivitySeq)
        scope.cancel()
    }

    @Test
    fun aThreadRowPatchedToTheTopStaysThereThroughALaggingServedOrder() = runBlocking {
        // Hands 62498f69 (artin, 1.22.0): the reader replied in a thread; `message:new` moved its
        // row to the top, the re-read (warehouse view, one reply behind) put the old head back on
        // top, and the next read put the thread row back: the first row flipped back and forth.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val head =
            threadItem("thread-head").copy(
                parentMessageId = MessageId("parent-head"),
                replyCount = 5,
                latestActivityMessageId = MessageId("head-5"),
                latestActivitySeq = "21060639"
            )
        val mine =
            threadItem("thread-mine").copy(
                parentMessageId = MessageId("parent-mine"),
                replyCount = 22,
                latestActivityMessageId = MessageId("mine-22"),
                latestActivityPreview = "reply 22",
                latestActivitySeq = "21060637"
            )
        val caughtUp =
            mine.copy(
                replyCount = 23,
                latestActivityMessageId = MessageId("mine-23"),
                latestActivityPreview = "reply 23",
                latestActivitySeq = "21060660"
            )
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(head, mine)),
                        // The lagging re-read: the served order still has the old head first.
                        snapshot(listOf(head, mine)),
                        snapshot(listOf(caughtUp, head))
                    )
            )
        val store = activityStore(scope = scope, inboxRepository = inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        val heads = mutableListOf(store.state.value.items.first().key)

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("mine-23", channelId = "thread-mine", body = "reply 23", seq = 21060660L))
            )
        )
        heads += store.state.value.items.first().key
        store.dispatch(SharedActivityIntent.Refresh)
        heads += store.state.value.items.first().key
        assertEquals(listOf(mine.key, head.key), store.state.value.items.map(InboxItem::key), "the lagging order does not move the patched row down")
        assertEquals("reply 23", store.state.value.items.first().latestActivityPreview)
        store.dispatch(SharedActivityIntent.Refresh)
        heads += store.state.value.items.first().key

        assertEquals(listOf(head.key, mine.key, mine.key, mine.key), heads, "the first row changes once")
        assertEquals(listOf(mine.key, head.key), store.state.value.items.map(InboxItem::key))
        assertEquals("21060660", store.state.value.items.first().latestActivitySeq)
        scope.cancel()
    }

    @Test
    fun unknownChannelDmAndThreadRowsEnterThroughBackgroundReconcileWithoutRefreshChrome() = runBlocking {
        val existing = activityItem("channel-1")
        val direct =
            activityItem("dm-2").copy(
                key = "dm:dm-2",
                kind = InboxItemKind.DirectMessage,
                channelKind = ConversationKind.DirectMessage
            )
        val cases =
            listOf(
                Triple("channel", activityItem("channel-2"), message("message-channel", channelId = "channel-2")),
                Triple("dm", direct, message("message-dm", channelId = "dm-2")),
                Triple(
                    "thread",
                    threadItem("thread-2"),
                    message(
                        "message-thread",
                        channelId = "thread-2",
                        conversationContext = MessageConversationContext(channelKind = ConversationKind.Thread)
                    )
                )
            )

        cases.forEach { (label, arrived, realtimeMessage) ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val inbox =
                FakeInboxRepository(
                    initialSnapshots =
                        listOf(
                            snapshot(listOf(existing)),
                            snapshot(listOf(arrived, existing))
                        )
                )
            val store =
                activityStore(
                    scope = scope,
                    inboxRepository = inbox,
                    realtimeBackgroundRefreshIntervalMillis = 0L
                )

            store.dispatch(SharedActivityIntent.LoadInitial)
            store.acceptRealtime(
                SharedActivityIntent.ApplyRealtimeEvent(MessageNew(realtimeMessage))
            )

            assertEquals(2, inbox.initialLoadCount, label)
            assertEquals(listOf(arrived.key, existing.key), store.state.value.items.map { it.key }, label)
            assertEquals(false, store.state.value.refreshing, label)
            assertEquals(false, store.state.value.pullRefreshing, label)
            scope.cancel()
        }
    }

    @Test
    fun realtimeBackgroundReconcileFailureDoesNotPolluteVisibleErrorOrChrome() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )
        val stale = threadItem("thread-channel-1").copy(replyCount = 2)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale)))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-new", channelId = "thread-channel-1"))
            )
        )

        awaitPendingCount(inbox, 1)
        inbox.completeNextResult(DataResult.Error(ScreenError("background_failed")))
        delay(1L)

        assertEquals(null, store.state.value.errorCode)
        assertEquals(false, store.state.value.loading)
        assertEquals(false, store.state.value.refreshing)
        assertEquals(false, store.state.value.pullRefreshing)
        assertEquals(MessageId("message-new"), store.state.value.items.single().latestActivityMessageId)
        scope.cancel()
    }

    @Test
    fun selfAuthoredMessagePatchKeepsAllRowRead() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 60_000L
            )
        val stale = threadItem("thread-channel-1", unreadCount = 0).copy(replyCount = 2)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 0))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "self-message",
                        channelId = "thread-channel-1",
                        authorId = "user-1"
                    )
                )
            )
        )

        val patched = store.state.value.items.single()
        assertEquals(MessageId("self-message"), patched.latestActivityMessageId)
        assertEquals(0, patched.unreadCount)
        assertEquals(null, patched.firstUnreadMessageId)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun twoRetainedRowsDoNotDoubleCountCanonicalAggregateThatAlreadyCaughtUp() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )
        val first = threadItem("thread-first").copy(replyCount = 2)
        val second = threadItem("thread-second").copy(replyCount = 4)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(first, second), totalUnreadCount = 0))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-first", channelId = "thread-first"))
            )
        )
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-second", channelId = "thread-second"))
            )
        )

        awaitPendingCount(inbox, 1)
        inbox.completeNextSnapshot(snapshot(emptyList(), totalUnreadCount = 2))
        delay(1L)

        assertEquals(2, store.state.value.items.size)
        assertEquals(listOf(0, 0), store.state.value.items.map { it.unreadCount })
        assertEquals(2, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun filterSwitchFencesOlderAllBackgroundProjectionAndStartsCleanMembershipLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 0L
            )
        val stale = threadItem("thread-channel-1").copy(replyCount = 2)
        val patchedCanonical =
            stale.copy(
                latestActivityMessageId = MessageId("message-new"),
                latestActivityPreview = "message message-new",
                replyCount = 3,
                unreadCount = 1,
                firstUnreadMessageId = MessageId("message-new")
            )
        val unreadOnly = threadItem("thread-unread", unreadCount = 1)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(stale), totalUnreadCount = 0))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message("message-new", channelId = "thread-channel-1"))
            )
        )
        awaitPendingCount(inbox, 1)

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        awaitPendingCount(inbox, 2)
        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter)
        assertTrue(store.state.value.items.isEmpty())

        inbox.completeNextSnapshot(snapshot(listOf(patchedCanonical), totalUnreadCount = 1))
        delay(1L)
        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter)
        assertTrue(store.state.value.items.isEmpty())

        inbox.completeNextSnapshot(snapshot(listOf(unreadOnly), totalUnreadCount = 1))
        awaitState(store) { state -> state.items.map { it.key } == listOf(unreadOnly.key) }
        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter)
        scope.cancel()
    }

    @Test
    fun homeSummaryRealtimeIncrementsUnreadWithoutRefreshingActivityRows() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 1)), totalCount = 1, totalUnreadCount = 1),
                        snapshot(listOf(activityItem("channel-1", unreadCount = 9)), totalCount = 1, totalUnreadCount = 9)
                    )
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeSummaryEvent(
                MessageNew(message("message-new", channelId = "channel-1", authorId = "user-2")),
                activeConversationId = null
            )
        )

        assertEquals(1, inbox.initialLoadCount)
        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key })
        assertEquals(1, store.state.value.items.single().unreadCount)
        assertEquals(2, store.state.value.totalUnreadCount)
        assertEquals(1, store.state.value.totalCount)
        scope.cancel()
    }

    @Test
    fun homeSummaryRealtimeSkipsSelfAuthoredAndActiveConversationMessages() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(
                            listOf(activityItem("channel-1", unreadCount = 1), activityItem("channel-2", unreadCount = 1)),
                            totalCount = 2,
                            totalUnreadCount = 2
                        )
                    )
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeSummaryEvent(
                SyncResumeResponse(
                    messages =
                        listOf(
                            message("self-message", channelId = "channel-1", authorId = "user-1"),
                            message("active-message", channelId = "channel-2", authorId = "user-2"),
                            message("inactive-message", channelId = "channel-1", authorId = "user-2")
                        ),
                    currentSeq = 3L,
                    hasMore = false
                ),
                activeConversationId = "channel-2"
            )
        )

        assertEquals(1, inbox.initialLoadCount)
        assertEquals(3, store.state.value.totalUnreadCount)
        assertEquals(listOf("channel:channel-1", "channel:channel-2"), store.state.value.items.map { it.key })
        scope.cancel()
    }

    @Test
    fun loadInitialSeedsColdStoreFromMatchingActivityPersistSlice() = runBlocking {
        val persistStorage = InMemoryPersistStorage()
        val persistSlice = createSharedActivityPersistSlice(persistStorage)
        val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val preloadStore =
            activityStore(
                scope = preloadScope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1"))))),
                persistSlices = listOf(persistSlice)
            )
        preloadStore.dispatch(SharedActivityIntent.LoadInitial)

        val standaloneScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val standaloneInbox = DeferredInboxRepository()
        val standaloneStore =
            activityStore(
                scope = standaloneScope,
                inboxRepository = standaloneInbox,
                persistSlices = listOf(persistSlice)
            )

        standaloneStore.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(false, standaloneStore.state.value.loading)
        assertEquals(true, standaloneStore.state.value.refreshing)
        assertEquals(listOf("channel:channel-1"), standaloneStore.state.value.items.map { it.key })
        standaloneInbox.completeNext(activityItem("channel-2"))
        assertEquals(false, standaloneStore.state.value.refreshing)
        assertEquals(listOf("channel:channel-2"), standaloneStore.state.value.items.map { it.key })
        preloadScope.cancel()
        standaloneScope.cancel()
    }

    @Test
    fun loadInitialAppliesResultWhenAccessTokenRotatedMidFlight() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val authStore = InMemoryRaftAuthSessionStore(sampleSession())
        val inbox = DeferredInboxRepository()
        val diagnostics = mutableListOf<String>()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                authStore = authStore,
                diagnosticsLogger = { diagnostics += it }
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(true, store.state.value.loading)
        assertEquals(1, inbox.initialLoadCount)

        // A 401 inside the request rotates the tokens; the retried request still
        // returns fresh data for the same user + server and must be applied.
        authStore.updateTokens("rotated-access", "rotated-refresh")
        inbox.completeNext(activityItem("channel-1"))

        assertEquals(false, store.state.value.loading)
        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key })
        assertTrue(diagnostics.none { it.contains("stale_session") })
        scope.cancel()
    }

    @Test
    fun loadInitialIgnoresActivityPersistSliceForDifferentUser() = runBlocking {
        val persistStorage = InMemoryPersistStorage()
        val persistSlice = createSharedActivityPersistSlice(persistStorage)
        val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val preloadStore =
            activityStore(
                scope = preloadScope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1"))))),
                persistSlices = listOf(persistSlice)
            )
        preloadStore.dispatch(SharedActivityIntent.LoadInitial)

        val standaloneScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val standaloneStore =
            activityStore(
                scope = standaloneScope,
                authStore = InMemoryRaftAuthSessionStore(sampleSession(userId = "user-2")),
                inboxRepository = DeferredInboxRepository(),
                persistSlices = listOf(persistSlice)
            )

        standaloneStore.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(true, standaloneStore.state.value.loading)
        assertEquals(emptyList(), standaloneStore.state.value.items.map { it.key })
        preloadScope.cancel()
        standaloneScope.cancel()
    }

    @Test
    fun activityThreadRowsResolveMissingSenderNameFromMembers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val row =
            threadItem("thread-channel-1", unreadCount = 1)
                .copy(
                    latestActivitySenderId = "agent-1",
                    latestActivitySenderName = "@agent-one",
                    latestActivityPreview = "Latest reply"
                )
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(row))))
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                memberRepository =
                    FakeMemberRepository(
                        members =
                            listOf(
                                MemberProfile(
                                    id = MemberId("agent-1"),
                                    displayName = "Agent One",
                                    handle = "agent-one",
                                    kind = MemberKind.Agent
                                )
                            )
                    )
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        val item = store.state.value.items.single()
        assertEquals("@agent-one", item.latestActivitySenderName)
        assertEquals("Agent One", store.state.value.senderProfiles.getValue("agent-1").displayName)
        scope.cancel()
    }

    @Test
    fun activityThreadRowsReplaceStaleEmbeddedStatusWithSharedTaskProjection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tasksDataStore = SharedTasksDataStore()
        tasksDataStore.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("parent-channel")),
            snapshot =
                TaskSnapshot(
                    tasks =
                        listOf(
                            TaskSummary(
                                id = TaskId("task-339"),
                                title = "Canonical task",
                                status = TaskStatus.Done,
                                taskNumber = 339,
                                messageId = MessageId("parent-message"),
                                channelId = ChannelId("parent-channel"),
                                updatedAtLabel = "now"
                            )
                        ),
                    summaryCounts = emptyMap()
                )
        )
        val staleRow =
            threadItem("thread-channel-1").copy(
                taskNumber = 339,
                taskStatus = TaskStatus.InProgress,
                taskClaimedByName = "stale assignee"
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(staleRow)))),
                tasksDataStore = tasksDataStore
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        val item = store.state.value.items.single()
        assertEquals(339, item.taskNumber)
        assertEquals(TaskStatus.Done, item.taskStatus)
        assertEquals(null, item.taskClaimedByName)
        scope.cancel()
    }

    @Test
    fun jointTaskActivityFactDedupesAndOpensThroughEachServerLocalRoute() = runBlocking {
        val tasksDataStore = SharedTasksDataStore()
        val canonicalTask =
            TaskSummary(
                id = TaskId("task-joint"),
                title = "Joint task",
                status = TaskStatus.InProgress,
                taskNumber = 441,
                messageId = MessageId("joint-parent-message"),
                channelId = ChannelId("host-channel"),
                revision = 7L,
                updatedAtLabel = "now"
            )
        tasksDataStore.upsertTask("server-host", canonicalTask)
        tasksDataStore.upsertTask(
            "server-peer",
            canonicalTask.copy(channelId = ChannelId("peer-channel"), canClaim = false)
        )
        val hostCandidate =
            threadItem("thread-host")
                .copy(
                    parentChannelId = ChannelId("host-channel"),
                    parentMessageId = canonicalTask.messageId
                )
        val peerCandidate =
            threadItem("thread-peer")
                .copy(
                    parentChannelId = ChannelId("peer-channel"),
                    parentMessageId = canonicalTask.messageId
                )

        suspend fun load(serverId: String): InboxItem {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val store =
                activityStore(
                    scope = scope,
                    authStore = InMemoryRaftAuthSessionStore(sampleSession(serverId = serverId)),
                    inboxRepository =
                        FakeInboxRepository(
                            initialSnapshots =
                                listOf(
                                    snapshot(
                                        items = listOf(peerCandidate, hostCandidate),
                                        serverId = serverId
                                    )
                                )
                        ),
                    tasksDataStore = tasksDataStore
                )
            store.dispatch(SharedActivityIntent.LoadInitial)
            return store.state.value.items.single().also { scope.cancel() }
        }

        val hostRow = load("server-host")
        val peerRow = load("server-peer")

        assertEquals("thread:thread-host", hostRow.key)
        assertEquals(ChannelId("host-channel"), hostRow.parentChannelId)
        assertEquals("thread:thread-peer", peerRow.key)
        assertEquals(ChannelId("peer-channel"), peerRow.parentChannelId)
        assertEquals(441, hostRow.taskNumber)
        assertEquals(441, peerRow.taskNumber)
    }

    @Test
    fun reusedActivityStoreUpdatesRuntimeRepositoriesAndDropsPreviousServerSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstInbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1")))))
        val store =
            activityStore(
                scope = scope,
                inboxRepository = firstInbox
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key })

        val secondInbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-2")))))
        store.updateRuntimeIntegrations(
            authStore = InMemoryRaftAuthSessionStore(sampleSession(serverId = "server-2")),
            inboxRepository = secondInbox,
            readReceiptRepository = FakeReadReceiptRepository(),
            conversationRepository = FakeConversationRepository()
        )
        store.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(1, firstInbox.initialLoadCount)
        assertEquals(1, secondInbox.initialLoadCount)
        assertEquals("server-2", store.state.value.ownerServerId)
        assertEquals(listOf("channel:channel-2"), store.state.value.items.map { it.key })
        scope.cancel()
    }

    @Test
    fun doneUsesInboxDoneForChannelsAndThreadDoneForThreads() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(
                            listOf(
                                activityItem("channel-1"),
                                threadItem("thread-channel-1")
                            ),
                            totalCount = 2
                        )
                    )
            )
        val conversation = FakeConversationRepository()
        val store = activityStore(scope, inbox, conversationRepository = conversation)

        store.dispatch(SharedActivityIntent.LoadInitial)
        val channel = store.state.value.items.first { it.kind == InboxItemKind.Channel }
        val thread = store.state.value.items.first { it.kind == InboxItemKind.Thread }
        store.dispatch(SharedActivityIntent.MarkDone(channel))
        store.dispatch(SharedActivityIntent.MarkDone(thread))

        assertEquals(listOf(ChannelId("channel-1")), inbox.doneChannelIds)
        assertEquals(listOf(ChannelId("thread-channel-1")), conversation.doneThreadChannelIds)
        assertEquals(emptyList(), store.state.value.items)
        scope.cancel()
    }

    @Test
    fun failedDoneRefreshesAndRestoresRows() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val row = activityItem("channel-1")
        val inbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(row)), snapshot(listOf(row))),
                failDone = true
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))

        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key })
        assertEquals(2, inbox.initialLoadCount)
        scope.cancel()
    }

    @Test
    fun shellScopedActivityStoresKeepOwnerServerAndRequestsIndependent() = runBlocking {
        val processSession =
            sampleSession().copy(
                servers =
                    listOf(
                        StoredAuthServer(id = "server-1", name = "One"),
                        StoredAuthServer(id = "server-2", name = "Two")
                    )
            )
        val processStore = InMemoryRaftAuthSessionStore(processSession)
        val authA =
            RaftShellAuthSessionStore(
                processStore,
                SelectedServerStore(RaftShellStoreScope.create("shell-a", "server-1"))
            )
        val authB =
            RaftShellAuthSessionStore(
                processStore,
                SelectedServerStore(RaftShellStoreScope.create("shell-b", "server-2"))
            )
        val inboxA = FakeInboxRepository(initialSnapshots = listOf(snapshot(emptyList())))
        val inboxB = FakeInboxRepository(initialSnapshots = listOf(snapshot(emptyList())))
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storeA = activityStore(scopeA, inboxA, authStore = authA)
        val storeB = activityStore(scopeB, inboxB, authStore = authB)

        storeA.dispatch(SharedActivityIntent.LoadInitial)
        storeB.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals("server-1", storeA.state.value.ownerServerId)
        assertEquals("server-2", storeB.state.value.ownerServerId)
        assertEquals(listOf(ServerId("server-1")), inboxA.initialLoadServerIds)
        assertEquals(listOf(ServerId("server-2")), inboxB.initialLoadServerIds)
        scopeA.cancel()
        scopeB.cancel()
    }

    @Test
    fun internalJointStopLossKeepsCanonicalRowsButRemovesSummaryCountGhosts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val facts = mutableListOf<ActivityUnreadSummaryFact>()
        val safe = activityItem("channel-safe", unreadCount = 2)
        val unsafe =
            InboxItem(
                key = "thread:reply-stream",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("reply-stream"),
                channelName = "joint-storage-parent",
                threadChannelId = ChannelId("reply-stream"),
                parentChannelId = ChannelId("joint-storage-parent"),
                parentChannelName = "joint-storage-parent",
                parentChannelKind = ConversationKind.Joint,
                parentMessageId = MessageId("parent-message"),
                unreadCount = 3,
                latestActivityMessageId = MessageId("reply-1")
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots =
                            listOf(
                                snapshot(
                                    items = listOf(safe, unsafe),
                                    totalCount = 5,
                                    totalUnreadCount = 10
                                )
                            )
                    ),
                onSummaryFact = facts::add
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(listOf("channel:channel-safe", "thread:reply-stream"), store.state.value.items.map(InboxItem::key))
        assertEquals(5, store.state.value.totalCount)
        assertEquals(10, store.state.value.totalUnreadCount)
        val projectedFact = facts.last()
        assertEquals(4, projectedFact.totalCount)
        assertEquals(7, projectedFact.totalUnreadCount)
        assertTrue(projectedFact.authoritative)
        scope.cancel()
    }

    private fun readStateEvent(
        scopeId: String,
        version: Long,
        maxReadSeq: Long
    ): PerReceiverReadStateChanged =
        PerReceiverReadStateChanged(
            serverId = ServerId("server-1"),
            scopes =
                listOf(
                    PerReceiverReadStateScope(
                        scopeId = ChannelId(scopeId),
                        maxReadSeq = maxReadSeq,
                        readStateVersion = version
                    )
                )
        )

    private fun replyPrewarmMarker(): ActivityReplyThreadPrewarmMarker =
        ActivityReplyThreadPrewarmMarker(
            key =
                ActivityReplyThreadPrewarmKey(
                    serverId = "server-1",
                    parentChannelId = "parent-channel",
                    parentMessageId = "parent-message",
                    threadChannelId = "thread-channel"
                )
        )

    private fun keyedMarker(id: String): ActivityReplyThreadPrewarmMarker =
        ActivityReplyThreadPrewarmMarker(
            key =
                ActivityReplyThreadPrewarmKey(
                    serverId = "server-1",
                    parentChannelId = "parent-$id",
                    parentMessageId = "message-$id",
                    threadChannelId = "thread-$id"
                )
        )

    private inner class DeferredInboxRepository : InboxRepository {
        private val lock = SynchronizedObject()
        private val pending = mutableListOf<CompletableDeferred<DataResult<InboxSnapshot>>>()
        private var initialLoads = 0
        val initialLoadCount: Int
            get() = synchronized(lock) { initialLoads }
        val pendingCount: Int
            get() = synchronized(lock) { pending.size }

        override suspend fun loadInitial(
            serverId: ServerId,
            filter: InboxFilter,
            limit: Int
        ): DataResult<InboxSnapshot> {
            val result = CompletableDeferred<DataResult<InboxSnapshot>>()
            synchronized(lock) {
                initialLoads += 1
                pending += result
            }
            return withContext(NonCancellable) { result.await() }
        }

        override suspend fun loadMore(
            serverId: ServerId,
            filter: InboxFilter,
            offset: Int,
            limit: Int
        ): DataResult<InboxSnapshot> =
            DataResult.Empty(EmptyReason.NoInboxItems)

        override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> =
            DataResult.Content(InboxDoneResult(channelId))

        fun completeNext(item: InboxItem) {
            completeNextSnapshot(snapshot(listOf(item)))
        }

        fun completeNextSnapshot(snapshot: InboxSnapshot) {
            completeNextResult(DataResult.Content(snapshot))
        }

        fun completeNextResult(result: DataResult<InboxSnapshot>) {
            val next = synchronized(lock) { pending.removeAt(0) }
            next.complete(result)
        }
    }

    private class FakeMemberRepository(
        private val members: List<MemberProfile>
    ) : MemberRepository {
        override suspend fun getMembers(
            priority: DataRequestPriority,
            serverId: ServerId
        ): DataResult<MemberSnapshot> =
            DataResult.Content(MemberSnapshot(ServerSummary(id = serverId, name = "Slock"), members))

        override suspend fun getMemberProfile(priority: DataRequestPriority, memberId: MemberId, serverId: ServerId?): DataResult<MemberProfile> =
            error("unused")

        override suspend fun updateAgentProfile(priority: DataRequestPriority, request: AgentProfileUpdateRequest): DataResult<MemberProfile> =
            error("unused")

        override suspend fun uploadAgentAvatar(priority: DataRequestPriority, request: AgentAvatarUploadRequest): DataResult<MemberProfile> =
            error("unused")

        override suspend fun getAgentDetail(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDetailSnapshot> =
            error("unused")

        override suspend fun getAgentDirectMessages(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDmSnapshot> =
            error("unused")

        override suspend fun getAgentReminders(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentReminderSnapshot> =
            error("unused")

        override suspend fun getAgentActivity(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentActivitySnapshot> =
            error("unused")

        override suspend fun getAgentWorkspaces(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceSnapshot> =
            error("unused")

        override suspend fun getAgentWorkspaceTree(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceTreeSnapshot> =
            error("unused")

        override suspend fun getAgentWorkspaceChildren(
            priority: DataRequestPriority,
            agentId: MemberId,
            dirPath: String
        ): DataResult<AgentWorkspaceChildrenSnapshot> =
            error("unused")

        override suspend fun readAgentWorkspaceFile(
            priority: DataRequestPriority,
            agentId: MemberId,
            path: String
        ): DataResult<AgentWorkspaceFilePreviewSnapshot> =
            error("unused")

        override suspend fun getAgentSkills(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentSkillsSnapshot> =
            error("unused")

        override suspend fun startAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun stopAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun resetAgent(priority: DataRequestPriority, request: AgentResetRequest): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun deleteAgent(priority: DataRequestPriority, agentId: MemberId, workspaceChoice: AgentArchiveWorkspaceChoice): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun assignAgentMachine(
            priority: DataRequestPriority,
            request: AgentMachineAssignmentRequest
        ): DataResult<AgentLifecycleActionResult> =
            error("unused")

        override suspend fun getChannelMembers(priority: DataRequestPriority, channelId: ChannelId): DataResult<ChannelMemberSnapshot> =
            error("unused")

        override suspend fun addChannelMember(priority: DataRequestPriority, request: AddChannelMemberRequest): DataResult<ChannelMemberActionResult> =
            error("unused")

        override suspend fun removeChannelMember(
            priority: DataRequestPriority,
            request: RemoveChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> =
            error("unused")
    }

    @Test
    fun dmParentThreadRowsHydratePeerNamesFromDirectMessages() = runBlocking {
        // task #399: an inbox Thread row whose parent is a DM resolves the peer
        // display name through the Home DM list (channels.name is not a
        // reliable peer projection); channel-parent rows never trigger it.
        val dmThread =
            InboxItem(
                key = "thread:dm-thread-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("dm-thread-1"),
                channelName = "thread-stream",
                parentChannelId = ChannelId("dm-1"),
                parentChannelName = "raw-dm-name",
                parentChannelKind = ConversationKind.DirectMessage,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "DM root preview"
            )
        val channelThread =
            InboxItem(
                key = "thread:ch-thread-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("ch-thread-1"),
                channelName = "thread-stream-2",
                parentChannelId = ChannelId("channel-9"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-2"),
                parentMessagePreview = "Channel root preview"
            )
        val channelRepository =
            FakeChannelRepository(
                directMessagesResult = { serverId ->
                    DataResult.Content(
                        DirectMessageSnapshot(
                            server = ServerSummary(id = serverId, name = "Slock"),
                            directMessages =
                                listOf(
                                    HomeDirectMessageRow(
                                        threadId = ThreadId("dm-1"),
                                        channelId = ChannelId("dm-1"),
                                        name = "Jiacheng"
                                    )
                                )
                        )
                    )
                }
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots = listOf(snapshot(listOf(dmThread, channelThread)))
                    ),
                channelRepository = channelRepository
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        withTimeout(5_000L) {
            while (store.state.value.dmPeerNames["dm-1"] == null) {
                delay(1L)
            }
        }
        assertEquals(mapOf("dm-1" to "Jiacheng"), store.state.value.dmPeerNames)
        scope.cancel()
    }

    private suspend fun awaitPendingCount(
        inbox: DeferredInboxRepository,
        expected: Int
    ) {
        withTimeout(5_000L) {
            while (inbox.pendingCount != expected) {
                delay(1L)
            }
        }
    }

    private suspend fun awaitInitialLoadCount(
        inbox: FakeInboxRepository,
        expected: Int
    ) {
        withTimeout(5_000L) {
            while (inbox.initialLoadCount != expected) {
                delay(1L)
            }
        }
    }

    private suspend fun awaitState(
        store: SharedActivityStore,
        predicate: (SharedActivityState) -> Boolean
    ) {
        withTimeout(5_000L) {
            while (!predicate(store.state.value)) {
                delay(1L)
            }
        }
    }

    private fun homeSnapshot(
        conversation: ConversationSummary,
        unreadTotal: Int
    ): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock", unreadCount = unreadTotal),
            currentMember =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "User One",
                    handle = "user",
                    kind = MemberKind.Human
                ),
            channels = listOf(conversation.toHomeChannelRow()),
            directMessages = emptyList(),
            unreadTotal = unreadTotal
        )
}
