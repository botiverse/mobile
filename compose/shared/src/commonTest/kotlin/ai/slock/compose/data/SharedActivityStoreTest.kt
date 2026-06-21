package ai.slock.compose.data

import ai.slock.compose.model.AttachmentId
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun unreadFilterMarkReadRemovesRowAndCallsReadAll() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 2)), totalUnreadCount = 2)))
        val read = FakeReadReceiptRepository()
        val store = activityStore(scope, inbox, readReceiptRepository = read)

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))

        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(listOf(ChannelId("channel-1")), read.readAllChannelIds)
        scope.cancel()
    }

    @Test
    fun markUnreadCallsUnreadEndpointAndRefreshes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val read = FakeReadReceiptRepository()
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 0)), totalUnreadCount = 0),
                        snapshot(listOf(activityItem("channel-1", unreadCount = 1)), totalUnreadCount = 1)
                    )
            )
        val store = activityStore(scope, inbox, readReceiptRepository = read)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.MarkUnread(store.state.value.items.single()))

        assertEquals(listOf(ChannelId("channel-1")), read.unreadChannelIds)
        assertEquals(1, store.state.value.items.single().unreadCount)
        assertEquals(1, store.state.value.totalUnreadCount)
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
    fun threadActionsRequireExplicitThreadChannelId() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val row = threadItem(channelId = "parent-or-compat", threadChannelId = null, unreadCount = 1)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(row), totalUnreadCount = 1),
                        snapshot(listOf(row), totalUnreadCount = 1),
                        snapshot(listOf(row), totalUnreadCount = 1),
                        snapshot(listOf(row), totalUnreadCount = 1)
                    )
            )
        val read = FakeReadReceiptRepository()
        val conversation = FakeConversationRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                readReceiptRepository = read,
                conversationRepository = conversation
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        store.dispatch(SharedActivityIntent.MarkUnread(store.state.value.items.single()))
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))

        assertEquals(emptyList(), read.readAllChannelIds)
        assertEquals(emptyList(), read.unreadChannelIds)
        assertEquals(emptyList(), inbox.doneChannelIds)
        assertEquals(emptyList(), conversation.doneThreadChannelIds)
        assertEquals(listOf("thread:parent-or-compat"), store.state.value.items.map { it.key })
        assertEquals(4, inbox.initialLoadCount)
        scope.cancel()
    }

    private fun activityStore(
        scope: CoroutineScope,
        inboxRepository: FakeInboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(emptyList()))),
        readReceiptRepository: FakeReadReceiptRepository = FakeReadReceiptRepository(),
        conversationRepository: FakeConversationRepository = FakeConversationRepository()
    ): SharedActivityStore =
        SharedActivityStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            inboxRepository = inboxRepository,
            readReceiptRepository = readReceiptRepository,
            conversationRepository = conversationRepository,
            scope = scope
        )

    private class FakeInboxRepository(
        private val initialSnapshots: List<InboxSnapshot>,
        private val moreSnapshots: List<InboxSnapshot> = emptyList(),
        private val failDone: Boolean = false
    ) : InboxRepository {
        var initialLoadCount = 0
        var moreLoadCount = 0
        val loadMoreOffsets = mutableListOf<Int>()
        val doneChannelIds = mutableListOf<ChannelId>()

        override suspend fun loadInitial(
            serverId: ServerId,
            filter: InboxFilter,
            limit: Int
        ): DataResult<InboxSnapshot> =
            DataResult.Content(initialSnapshots.getOrElse(initialLoadCount++) { initialSnapshots.last() })

        override suspend fun loadMore(
            serverId: ServerId,
            filter: InboxFilter,
            offset: Int,
            limit: Int
        ): DataResult<InboxSnapshot> {
            loadMoreOffsets += offset
            return DataResult.Content(moreSnapshots.getOrElse(moreLoadCount++) { moreSnapshots.last() })
        }

        override suspend fun markDone(channelId: ChannelId): DataResult<InboxDoneResult> {
            doneChannelIds += channelId
            return if (failDone) {
                DataResult.Error(ScreenError("done_failed"))
            } else {
                DataResult.Content(InboxDoneResult(channelId))
            }
        }
    }

    private class FakeReadReceiptRepository : ReadReceiptRepository {
        val readAllChannelIds = mutableListOf<ChannelId>()
        val unreadChannelIds = mutableListOf<ChannelId>()

        override suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot> =
            error("unused")

        override suspend fun markChannelReadToSeq(channelId: ChannelId, seq: Long): DataResult<ReadReceiptResult> =
            error("unused")

        override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> {
            readAllChannelIds += channelId
            return DataResult.Content(ReadReceiptResult(channelId = channelId))
        }

        override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> {
            unreadChannelIds += channelId
            return DataResult.Content(ReadReceiptResult(channelId = channelId, unreadCount = 1))
        }

        override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> =
            DataResult.Content(ReadReceiptResult())
    }

    private class FakeConversationRepository : ConversationRepository {
        val doneThreadChannelIds = mutableListOf<ChannelId>()

        override suspend fun getThread(threadId: ThreadId, includeFollowedSummaries: Boolean, limit: Int): DataResult<ThreadSnapshot> =
            error("unused")

        override suspend fun getReplyThread(parentChannelId: ChannelId, parentMessageId: MessageId, includeFollowedSummaries: Boolean, limit: Int): DataResult<ThreadSnapshot> =
            error("unused")

        override suspend fun getMessageContext(messageId: MessageId): DataResult<ThreadSnapshot> =
            error("unused")

        override suspend fun loadOlderMessages(threadId: ThreadId, beforeSeq: Long, limit: Int): DataResult<ThreadSnapshot> =
            error("unused")

        override suspend fun loadNewerMessages(threadId: ThreadId, afterSeq: Long, limit: Int): DataResult<ThreadSnapshot> =
            error("unused")

        override suspend fun getChannelThreads(channelId: ChannelId, limit: Int): DataResult<ThreadListSnapshot> =
            error("unused")

        override suspend fun getFollowedThreads(serverId: ServerId, limit: Int): DataResult<ThreadListSnapshot> =
            error("unused")

        override suspend fun createOrOpenThread(channelId: ChannelId, parentMessageId: MessageId): DataResult<ThreadActionResult> =
            error("unused")

        override suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> =
            error("unused")

        override suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
            error("unused")

        override suspend fun markThreadDone(threadChannelId: ChannelId): DataResult<ThreadActionResult> {
            doneThreadChannelIds += threadChannelId
            return DataResult.Content(ThreadActionResult(threadChannelId = threadChannelId, changed = true))
        }

        override suspend fun markThreadUndone(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
            error("unused")

        override suspend fun uploadAttachments(request: AttachmentUploadRequest): DataResult<AttachmentUploadResult> =
            error("unused")

        override suspend fun getAttachmentOpenTarget(attachmentId: AttachmentId): DataResult<AttachmentOpenTarget> =
            error("unused")

        override suspend fun getChannelFiles(channelId: ChannelId, limit: Int, cursor: String?): DataResult<ChannelFilesSnapshot> =
            error("unused")

        override suspend fun sendMessage(
            channelId: ChannelId,
            body: String,
            attachmentIds: List<AttachmentId>,
            asTask: Boolean
        ): DataResult<SendMessageResult> =
            error("unused")
    }

    private fun activityItem(
        channelId: String,
        unreadCount: Int = 0
    ): InboxItem =
        InboxItem(
            key = "channel:$channelId",
            kind = InboxItemKind.Channel,
            channelId = ChannelId(channelId),
            channelName = channelId,
            unreadCount = unreadCount,
            firstUnreadMessageId = if (unreadCount > 0) MessageId("first-$channelId") else null,
            lastMessageId = MessageId("last-$channelId"),
            latestActivityMessageId = MessageId("latest-$channelId")
        )

    private fun threadItem(
        channelId: String,
        threadChannelId: String? = channelId,
        unreadCount: Int = 0
    ): InboxItem =
        InboxItem(
            key = "thread:$channelId",
            kind = InboxItemKind.Thread,
            channelId = ChannelId(channelId),
            channelName = "general",
            threadChannelId = threadChannelId?.let(::ChannelId),
            parentChannelId = ChannelId("parent-channel"),
            parentMessageId = MessageId("parent-message"),
            unreadCount = unreadCount,
            firstUnreadMessageId = if (unreadCount > 0) MessageId("first-$channelId") else null,
            latestActivityMessageId = MessageId("latest-thread")
        )

    private fun snapshot(
        items: List<InboxItem>,
        hasMore: Boolean = false,
        nextOffset: Int? = null,
        totalCount: Int = items.size,
        totalUnreadCount: Int = items.sumOf { it.unreadCount }
    ): InboxSnapshot =
        InboxSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            items = items,
            totalCount = totalCount,
            totalUnreadCount = totalUnreadCount,
            hasMore = hasMore,
            nextOffset = nextOffset
        )

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@slock.local", displayName = "User One"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock"))
        )
}
