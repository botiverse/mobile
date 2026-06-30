package build.raft.app.data

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
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedActivityStoreTest {
    @Test
    fun clearSummaryDropsLoadedActivityCountsButKeepsFilter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 2)), totalUnreadCount = 2)))
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        assertEquals(2, store.state.value.totalUnreadCount)

        store.dispatch(SharedActivityIntent.ClearSummary)

        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter)
        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(false, store.state.value.loaded)
        scope.cancel()
    }

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
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(homeSnapshot(activityItem("channel-1", unreadCount = 2).toConversationSummary(), unreadTotal = 2))
        val store =
            activityStore(
                scope,
                inbox,
                readReceiptRepository = read,
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))

        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(listOf(ChannelId("channel-1")), read.readAllChannelIds)
        assertEquals(0, localStore.readHome(ServerId("server-1"))?.channels?.single()?.unreadCount)
        assertEquals(0, localStore.readHome(ServerId("server-1"))?.unreadTotal)
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
    fun staleUnreadFilterRefreshAfterMarkReadDoesNotRestoreRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)
        val unreadRow = activityItem("channel-1", unreadCount = 2)

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        inbox.completeNextSnapshot(snapshot(listOf(unreadRow), totalCount = 1, totalUnreadCount = 2))
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        inbox.completeNextSnapshot(snapshot(listOf(unreadRow), totalCount = 1, totalUnreadCount = 2))

        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalCount)
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
                firstUnreadMessageId = MessageId("first-channel-1-new")
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
    fun markAllReadRequestsUnreadCountRefreshAfterCommit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val read = FakeReadReceiptRepository()
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 1)), totalUnreadCount = 1),
                        snapshot(emptyList(), totalUnreadCount = 0)
                    )
            )
        var unreadRefreshRequests = 0
        val store =
            activityStore(
                scope,
                inbox,
                readReceiptRepository = read,
                onUnreadCountsChanged = { unreadRefreshRequests += 1 }
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.MarkAllRead)

        assertEquals(listOf(ServerId("server-1")), read.inboxReadAllServerIds)
        assertEquals(1, unreadRefreshRequests)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(2, inbox.initialLoadCount)
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
    fun markAllReadRemovesRowsInUnreadFilter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pendingReadAll = CompletableDeferred<DataResult<ReadReceiptResult>>()
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 3)), totalCount = 1, totalUnreadCount = 3)
                    )
            )
        val store =
            activityStore(
                scope,
                inbox,
                readReceiptRepository = FakeReadReceiptRepository(pendingInboxReadAllResult = pendingReadAll)
            )

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        store.dispatch(SharedActivityIntent.MarkAllRead)

        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        pendingReadAll.complete(DataResult.Content(ReadReceiptResult()))
        scope.cancel()
    }

    @Test
    fun markAllReadRemovesRowsInMentionsFilter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pendingReadAll = CompletableDeferred<DataResult<ReadReceiptResult>>()
        val row = activityItem("channel-1", unreadCount = 2).copy(hasMention = true)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(row), totalCount = 1, totalUnreadCount = 2)
                    )
            )
        val store =
            activityStore(
                scope,
                inbox,
                readReceiptRepository = FakeReadReceiptRepository(pendingInboxReadAllResult = pendingReadAll)
            )

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Mentions))
        store.dispatch(SharedActivityIntent.MarkAllRead)

        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalCount)
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

        store.dispatch(SharedActivityIntent.PullRefresh)

        assertEquals(false, store.state.value.loading)
        assertEquals(true, store.state.value.refreshing)
        assertEquals(true, store.state.value.pullRefreshing)
        inbox.completeNext(activityItem("channel-3"))
        assertEquals(false, store.state.value.refreshing)
        assertEquals(false, store.state.value.pullRefreshing)
        scope.cancel()
    }

    @Test
    fun scopedRealtimeRefreshesActivitySummaryCounts() = runBlocking {
        val refreshScopes =
            listOf(
                RealtimeRefreshScope.Home,
                RealtimeRefreshScope.ChannelMembers,
                RealtimeRefreshScope.ServerMembers,
                RealtimeRefreshScope.Agents,
                RealtimeRefreshScope.Machines,
                RealtimeRefreshScope.Settings
            )

        refreshScopes.forEachIndexed { index, refreshScope ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val inbox =
                FakeInboxRepository(
                    initialSnapshots =
                        listOf(
                            snapshot(listOf(activityItem("channel-$index", unreadCount = index + 1)), totalUnreadCount = index + 1),
                            snapshot(listOf(activityItem("channel-$index", unreadCount = index + 2)), totalUnreadCount = index + 2)
                        )
                )
            val store = activityStore(scope, inbox)

            store.dispatch(SharedActivityIntent.LoadInitial)
            store.dispatch(
                SharedActivityIntent.ApplyRealtimeEvent(
                    RealtimeRefreshRequired(
                        scope = refreshScope,
                        reason = "test"
                    )
                )
            )

            assertEquals(2, inbox.initialLoadCount, "scope=$refreshScope")
            assertEquals(index + 2, store.state.value.totalUnreadCount, "scope=$refreshScope")
            scope.cancel()
        }
    }

    @Test
    fun announcementRealtimeDoesNotRefreshActivitySummaryCounts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("channel-1", unreadCount = 1)), totalUnreadCount = 1),
                        snapshot(listOf(activityItem("channel-1", unreadCount = 2)), totalUnreadCount = 2)
                    )
            )
        val store = activityStore(scope, inbox)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                RealtimeRefreshRequired(
                    scope = RealtimeRefreshScope.Announcements,
                    reason = "announcement:new"
                )
            )
        )

        assertEquals(1, inbox.initialLoadCount)
        assertEquals(1, store.state.value.totalUnreadCount)
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
    fun loadInitialSeedsColdStoreFromMatchingActivityStateCache() = runBlocking {
        val cache = SharedActivityStateCache()
        val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val preloadStore =
            activityStore(
                scope = preloadScope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1"))))),
                activityStateCache = cache
            )
        preloadStore.dispatch(SharedActivityIntent.LoadInitial)

        val standaloneScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val standaloneInbox = DeferredInboxRepository()
        val standaloneStore =
            activityStore(
                scope = standaloneScope,
                inboxRepository = standaloneInbox,
                activityStateCache = cache
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
    fun loadInitialIgnoresActivityStateCacheForDifferentUser() = runBlocking {
        val cache = SharedActivityStateCache()
        val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val preloadStore =
            activityStore(
                scope = preloadScope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1"))))),
                activityStateCache = cache
            )
        preloadStore.dispatch(SharedActivityIntent.LoadInitial)

        val standaloneScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val standaloneStore =
            activityStore(
                scope = standaloneScope,
                authStore = InMemorySlockAuthSessionStore(sampleSession(userId = "user-2")),
                inboxRepository = DeferredInboxRepository(),
                activityStateCache = cache
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
    fun cachedActivityRowsUseMemberStoreSenderNamesBeforeRefreshCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val row =
            threadItem("thread-channel-1", unreadCount = 1)
                .copy(
                    latestActivitySenderId = "agent-1",
                    latestActivitySenderName = null,
                    latestActivityPreview = "Latest reply"
                )
        val cache = SharedActivityStateCache()
        val agentOne =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Agent One",
                handle = "agent-one",
                kind = MemberKind.Agent
            )
        cache.write(
            serverId = "server-1",
            userId = "user-1",
            state =
                SharedActivityState(
                    selectedFilter = InboxFilter.All,
                    snapshot = snapshot(listOf(row)),
                    senderProfiles = emptyMap(),
                    loaded = true
                )
        )
        val membersStore =
            SharedServerMembersStore(
                FakeMemberRepository(
                    members = listOf(agentOne)
                )
            )
        membersStore.seedProfiles(
            ServerId("server-1"),
            listOf(agentOne),
            server = ServerSummary(id = ServerId("server-1"), name = "Slock")
        )
        val inbox = DeferredInboxRepository()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                activityStateCache = cache,
                serverMembersStore = membersStore
            )

        store.dispatch(SharedActivityIntent.LoadInitial)

        assertEquals(true, store.state.value.refreshing)
        assertEquals("Agent One", store.state.value.senderProfiles.getValue("agent-1").displayName)
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
    fun mentionActionDoneRemovesRowWithoutCallingInboxDone() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val row = mentionActionItem("resolution-1")
        val inbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(row), totalCount = 1, totalUnreadCount = 0))
            )
        val conversation = FakeConversationRepository()
        val store = activityStore(scope, inbox, conversationRepository = conversation)

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))

        assertEquals(emptyList(), store.state.value.items)
        assertEquals(emptyList(), inbox.doneChannelIds)
        assertEquals(emptyList(), conversation.doneThreadChannelIds)
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
    fun staleActivityRefreshAfterMarkDoneDoesNotRestoreRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)
        val row = activityItem("channel-1", unreadCount = 1)

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(row), totalCount = 1, totalUnreadCount = 1))
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        inbox.completeNextSnapshot(snapshot(listOf(row), totalCount = 1, totalUnreadCount = 1))

        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalCount)
        assertEquals(0, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun markDoneAllowsLaterActivityWithDifferentAnchor() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = DeferredInboxRepository()
        val store = activityStore(scope, inbox)
        val row = activityItem("channel-1", unreadCount = 1)
        val laterRow =
            row.copy(
                unreadCount = 1,
                firstUnreadMessageId = MessageId("first-channel-1-new"),
                latestActivityMessageId = MessageId("latest-channel-1-new")
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        inbox.completeNextSnapshot(snapshot(listOf(row), totalCount = 1, totalUnreadCount = 1))
        store.dispatch(SharedActivityIntent.Refresh)
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        inbox.completeNextSnapshot(snapshot(listOf(laterRow), totalCount = 1, totalUnreadCount = 1))

        val visible = store.state.value.items.single()
        assertEquals(MessageId("first-channel-1-new"), visible.firstUnreadMessageId)
        assertEquals(1, store.state.value.totalCount)
        assertEquals(1, store.state.value.totalUnreadCount)
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
        inboxRepository: InboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(emptyList()))),
        readReceiptRepository: FakeReadReceiptRepository = FakeReadReceiptRepository(),
        conversationRepository: FakeConversationRepository = FakeConversationRepository(),
        memberRepository: MemberRepository? = null,
        serverMembersStore: SharedServerMembersStore? = null,
        localWriteThrough: ComposeLocalWriteThrough? = null,
        activityStateCache: SharedActivityStateCache? = null,
        authStore: SlockAuthSessionStore = InMemorySlockAuthSessionStore(sampleSession()),
        onUnreadCountsChanged: () -> Unit = {}
    ): SharedActivityStore =
        SharedActivityStore(
            authStore = authStore,
            inboxRepository = inboxRepository,
            readReceiptRepository = readReceiptRepository,
            conversationRepository = conversationRepository,
            memberRepository = memberRepository,
            serverMembersStore = serverMembersStore,
            localWriteThrough = localWriteThrough,
            activityStateCache = activityStateCache,
            onUnreadCountsChanged = onUnreadCountsChanged,
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

    private class FakeReadReceiptRepository(
        private val pendingInboxReadAllResult: CompletableDeferred<DataResult<ReadReceiptResult>>? = null
    ) : ReadReceiptRepository {
        val readAllChannelIds = mutableListOf<ChannelId>()
        val unreadChannelIds = mutableListOf<ChannelId>()
        val inboxReadAllServerIds = mutableListOf<ServerId>()

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

        override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> {
            inboxReadAllServerIds += serverId
            pendingInboxReadAllResult?.let { return it.await() }
            return DataResult.Content(ReadReceiptResult())
        }
    }

    private inner class DeferredInboxRepository : InboxRepository {
        private val pending = mutableListOf<CompletableDeferred<DataResult<InboxSnapshot>>>()

        override suspend fun loadInitial(
            serverId: ServerId,
            filter: InboxFilter,
            limit: Int
        ): DataResult<InboxSnapshot> {
            val result = CompletableDeferred<DataResult<InboxSnapshot>>()
            pending += result
            return withContext(NonCancellable) { result.await() }
        }

        override suspend fun loadMore(
            serverId: ServerId,
            filter: InboxFilter,
            offset: Int,
            limit: Int
        ): DataResult<InboxSnapshot> =
            DataResult.Empty(EmptyReason.NoInboxItems)

        override suspend fun markDone(channelId: ChannelId): DataResult<InboxDoneResult> =
            DataResult.Content(InboxDoneResult(channelId))

        fun completeNext(item: InboxItem) {
            completeNextSnapshot(snapshot(listOf(item)))
        }

        fun completeNextSnapshot(snapshot: InboxSnapshot) {
            pending.removeAt(0).complete(DataResult.Content(snapshot))
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

        override suspend fun deleteAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> =
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

        override suspend fun syncNewerMessages(threadId: ThreadId, sinceSeq: Long, limit: Int): DataResult<ThreadSnapshot> =
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

    private fun message(
        id: String,
        channelId: String = "channel-1",
        authorId: String = "user-2"
    ): Message =
        Message(
            id = MessageId(id),
            threadId = ThreadId(channelId),
            channelId = ChannelId(channelId),
            author =
                MemberProfile(
                    id = MemberId(authorId),
                    displayName = "User $authorId",
                    handle = authorId,
                    kind = MemberKind.Human
                ),
            timestampLabel = "now",
            body = "message $id"
        )

    private fun mentionActionItem(resolutionId: String): InboxItem =
        InboxItem(
            key = "mention_action:$resolutionId",
            kind = InboxItemKind.MentionAction,
            channelId = ChannelId("channel-1"),
            channelName = "android-dev",
            channelKind = ConversationKind.Channel,
            lastMessageId = MessageId("message-1"),
            lastMessagePreview = "Can someone ping @missing-human?",
            lastMessageAt = "2026-06-26T02:40:00Z",
            mentionActionId = resolutionId,
            pendingMentionActions =
                listOf(
                    PendingMentionAction(
                        resolutionId = resolutionId,
                        messageId = MessageId("message-1"),
                        channelId = ChannelId("channel-1"),
                        channelName = "android-dev",
                        targetType = "user",
                        targetHandle = "missing-human",
                        availableActions = listOf("notify", "invite")
                    )
                )
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

    private fun sampleSession(userId: String = "user-1"): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@slock.local", displayName = "User One"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock"))
        )
}
