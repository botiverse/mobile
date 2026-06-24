package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
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

class SharedHomeSearchSavedStoreTest {
    @Test
    fun savedPanelLoadsAndUnsaveRemovesItem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = FakeSavedRepository(items = listOf(inboxItem("message-1")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = saved,
                searchRepository = FakeSearchRepository(),
                scope = scope
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)

        assertEquals(listOf("message-1"), store.state.value.saved.items.map { it.key })

        store.dispatch(SharedHomeSearchSavedIntent.Unsave(store.state.value.saved.items.single()))

        assertEquals(emptyList(), store.state.value.saved.items)
        assertEquals(MessageId("message-1"), saved.unsavedMessageId)
        scope.cancel()
    }

    @Test
    fun openSavedRefreshesLoadedPanelOnReentry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = FakeSavedRepository(items = listOf(inboxItem("message-1")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = saved,
                searchRepository = FakeSearchRepository(),
                scope = scope
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)

        assertEquals(1, saved.loadInitialCalls)
        assertEquals(listOf("message-1"), store.state.value.saved.items.map { it.key })

        saved.items = listOf(inboxItem("message-2"))
        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)

        assertEquals(2, saved.loadInitialCalls)
        assertEquals(listOf("message-2"), store.state.value.saved.items.map { it.key })
        scope.cancel()
    }

    @Test
    fun openSavedRequestsHundredItemsForHomeCount() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = FakeSavedRepository(items = listOf(inboxItem("message-1")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = saved,
                searchRepository = FakeSearchRepository(),
                scope = scope
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)

        assertEquals(listOf(100), saved.loadInitialLimits)
        scope.cancel()
    }

    @Test
    fun openSavedSeedsColdStoreFromMatchingSavedStateCache() = runBlocking {
        val cache = SharedHomeSavedStateCache()
        val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val preloadStore =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(items = listOf(inboxItem("message-1"))),
                searchRepository = FakeSearchRepository(),
                savedStateCache = cache,
                scope = preloadScope
            )
        preloadStore.dispatch(SharedHomeSearchSavedIntent.OpenSaved)

        val standaloneScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val standaloneSaved = DeferredSavedRepository()
        val standaloneStore =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = standaloneSaved,
                searchRepository = FakeSearchRepository(),
                savedStateCache = cache,
                scope = standaloneScope
            )

        standaloneStore.dispatch(SharedHomeSearchSavedIntent.OpenSaved)

        assertEquals(false, standaloneStore.state.value.saved.loading)
        assertEquals(true, standaloneStore.state.value.saved.refreshing)
        assertEquals(listOf("message-1"), standaloneStore.state.value.saved.items.map { it.key })
        standaloneSaved.completeNext("message-2")
        assertEquals(false, standaloneStore.state.value.saved.refreshing)
        assertEquals(listOf("message-2"), standaloneStore.state.value.saved.items.map { it.key })
        preloadScope.cancel()
        standaloneScope.cancel()
    }

    @Test
    fun openSavedIgnoresSavedStateCacheForDifferentUser() = runBlocking {
        val cache = SharedHomeSavedStateCache()
        val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val preloadStore =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(items = listOf(inboxItem("message-1"))),
                searchRepository = FakeSearchRepository(),
                savedStateCache = cache,
                scope = preloadScope
            )
        preloadStore.dispatch(SharedHomeSearchSavedIntent.OpenSaved)

        val standaloneScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val standaloneStore =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession(userId = "user-2")),
                savedRepository = DeferredSavedRepository(),
                searchRepository = FakeSearchRepository(),
                savedStateCache = cache,
                scope = standaloneScope
            )

        standaloneStore.dispatch(SharedHomeSearchSavedIntent.OpenSaved)

        assertEquals(true, standaloneStore.state.value.saved.loading)
        assertEquals(emptyList(), standaloneStore.state.value.saved.items.map { it.key })
        preloadScope.cancel()
        standaloneScope.cancel()
    }

    @Test
    fun openSavedShowsRefreshingAffordanceDuringLoadedReentry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = DeferredSavedRepository()
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = saved,
                searchRepository = FakeSearchRepository(),
                scope = scope
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        assertEquals(true, store.state.value.saved.loading)
        saved.completeNext("message-1")
        assertEquals(listOf("message-1"), store.state.value.saved.items.map { it.key })

        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)

        assertEquals(false, store.state.value.saved.loading)
        assertEquals(true, store.state.value.saved.refreshing)
        assertEquals(false, store.state.value.saved.pullRefreshing)
        assertEquals(listOf("message-1"), store.state.value.saved.items.map { it.key })
        saved.completeNext("message-2")
        assertEquals(false, store.state.value.saved.refreshing)
        assertEquals(listOf("message-2"), store.state.value.saved.items.map { it.key })
        scope.cancel()
    }

    @Test
    fun pullRefreshSavedShowsPullRefreshAffordanceDuringLoadedRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = DeferredSavedRepository()
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = saved,
                searchRepository = FakeSearchRepository(),
                scope = scope
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        saved.completeNext("message-1")

        store.dispatch(SharedHomeSearchSavedIntent.PullRefreshSaved)

        assertEquals(false, store.state.value.saved.loading)
        assertEquals(true, store.state.value.saved.refreshing)
        assertEquals(true, store.state.value.saved.pullRefreshing)
        assertEquals(listOf("message-1"), store.state.value.saved.items.map { it.key })
        saved.completeNext("message-2")
        assertEquals(false, store.state.value.saved.refreshing)
        assertEquals(false, store.state.value.saved.pullRefreshing)
        assertEquals(listOf("message-2"), store.state.value.saved.items.map { it.key })
        scope.cancel()
    }

    @Test
    fun searchPanelSendsQueryFiltersAndSortToRepository() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-2")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L,
                timeRangeBoundsProvider = { range ->
                    when (range) {
                        SearchTimeRange.Today ->
                            SearchIsoBounds(
                                afterIso = "2026-05-15T00:00:00Z",
                                beforeIso = "2026-05-15T15:04:43.904Z"
                            )
                        else -> SearchIsoBounds()
                    }
                }
            )

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))
        store.dispatch(SharedHomeSearchSavedIntent.SetMyMessagesOnly(true))
        store.dispatch(SharedHomeSearchSavedIntent.SetSearchChannel("channel-1"))
        store.dispatch(SharedHomeSearchSavedIntent.SetSearchTimeRange(SearchTimeRange.Today))
        store.dispatch(SharedHomeSearchSavedIntent.SetSearchSort(SearchSort.Recent))
        store.dispatch(SharedHomeSearchSavedIntent.SubmitSearch)

        assertEquals("release", search.query)
        assertEquals(SearchSort.Recent, search.options?.sort)
        assertEquals(listOf("user-1"), search.options?.senderIds)
        assertEquals(ChannelId("channel-1"), search.options?.channelId)
        assertEquals("2026-05-15T00:00:00Z", search.options?.afterIso)
        assertEquals("2026-05-15T15:04:43.904Z", search.options?.beforeIso)
        assertEquals(listOf("message-2"), store.state.value.search.items.map { it.key })
        scope.cancel()
    }

    @Test
    fun searchQueryChangeAutoRefreshesWithoutSubmit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-auto")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L
            )

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))

        assertEquals("release", search.query)
        assertEquals(listOf("message-auto"), store.state.value.search.items.map { it.key })
        assertEquals(true, store.state.value.search.searched)
        scope.cancel()
    }

    @Test
    fun searchQueryChangeShowsPendingAffordanceBeforeDebounceRuns() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-pending")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 250L
            )

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))

        assertEquals(true, store.state.value.search.querySearchPending)
        assertEquals(emptyList(), search.calls)
        scope.cancel()
    }

    @Test
    fun searchRefreshShowsRefreshingAffordanceDuringLoadedRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = QueuedSearchRepository()
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L
            )

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))
        assertEquals(true, store.state.value.search.loading)
        search.completeNext("message-1")
        assertEquals(listOf("message-1"), store.state.value.search.items.map { it.key })

        store.dispatch(SharedHomeSearchSavedIntent.RefreshSearch)

        assertEquals(false, store.state.value.search.loading)
        assertEquals(true, store.state.value.search.refreshing)
        assertEquals(listOf("message-1"), store.state.value.search.items.map { it.key })
        search.completeNext("message-2")
        assertEquals(false, store.state.value.search.refreshing)
        assertEquals(listOf("message-2"), store.state.value.search.items.map { it.key })
        scope.cancel()
    }

    @Test
    fun blankQueryWithFilterAutoBrowsesServerResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-filter-only")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L
            )

        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchSender("user-1"))

        assertEquals("", search.query)
        assertEquals(listOf("user-1"), search.options?.senderIds)
        assertEquals(listOf("message-filter-only"), store.state.value.search.items.map { it.key })
        assertEquals(true, store.state.value.search.searched)
        scope.cancel()
    }

    @Test
    fun blankQueryWithoutFiltersClearsResultsWithoutServerRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-clear")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L
            )

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))
        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged(""))

        assertEquals(listOf("release"), search.calls.map { it.query })
        assertEquals(emptyList(), store.state.value.search.items)
        assertEquals(false, store.state.value.search.searched)
        scope.cancel()
    }

    @Test
    fun staleSearchResultCannotOverwriteLatestQuery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = DeferredSearchRepository()
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L
            )

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("old"))
        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("new"))
        search.complete("new", "message-new")
        search.complete("old", "message-old")

        assertEquals(listOf("message-new"), store.state.value.search.items.map { it.key })
        assertEquals(listOf("old", "new"), search.calls.map { it.query })
        scope.cancel()
    }

    @Test
    fun clearSearchFiltersResetsSharedSearchParityState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-3")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L,
                timeRangeBoundsProvider = { range ->
                    when (range) {
                        SearchTimeRange.Last7Days ->
                            SearchIsoBounds(
                                afterIso = "2026-05-09T00:00:00Z",
                                beforeIso = "2026-05-15T15:04:43.904Z"
                            )
                        else -> SearchIsoBounds()
                    }
                }
            )

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))
        store.dispatch(SharedHomeSearchSavedIntent.SetMyMessagesOnly(true))
        store.dispatch(SharedHomeSearchSavedIntent.SetSearchChannel("channel-1"))
        store.dispatch(SharedHomeSearchSavedIntent.SetSearchTimeRange(SearchTimeRange.Last7Days))
        store.dispatch(SharedHomeSearchSavedIntent.SubmitSearch)
        store.dispatch(SharedHomeSearchSavedIntent.ClearSearchFilters)

        assertEquals(false, store.state.value.search.myMessagesOnly)
        assertEquals(null, store.state.value.search.selectedChannelId)
        assertEquals(SearchTimeRange.Any, store.state.value.search.timeRange)
        assertEquals(emptyList(), search.options?.senderIds)
        assertEquals(null, search.options?.channelId)
        assertEquals(null, search.options?.afterIso)
        assertEquals(null, search.options?.beforeIso)
        scope.cancel()
    }

    @Test
    fun explicitSenderFilterOverridesMyMessagesShortcut() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-4")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L
            )

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("handoff"))
        store.dispatch(SharedHomeSearchSavedIntent.SetMyMessagesOnly(true))
        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchSender("agent-1"))
        store.dispatch(SharedHomeSearchSavedIntent.SubmitSearch)

        assertEquals(false, store.state.value.search.myMessagesOnly)
        assertEquals(listOf("agent-1"), store.state.value.search.selectedSenderIds)
        assertEquals(listOf("agent-1"), search.options?.senderIds)
        scope.cancel()
    }

    @Test
    fun senderFilterSupportsMultipleSelectedSenders() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-multi-sender")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L
            )

        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchSender("user-1"))
        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchSender("agent-1"))

        assertEquals(listOf("user-1", "agent-1"), store.state.value.search.selectedSenderIds)
        assertEquals(listOf("user-1", "agent-1"), search.options?.senderIds)

        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchSender("user-1"))

        assertEquals(listOf("agent-1"), store.state.value.search.selectedSenderIds)
        assertEquals(listOf("agent-1"), search.options?.senderIds)
        scope.cancel()
    }

    private class FakeSavedRepository(
        var items: List<InboxItem> = emptyList()
    ) : SavedRepository {
        var unsavedMessageId: MessageId? = null
        var loadInitialCalls: Int = 0
        val loadInitialLimits = mutableListOf<Int>()

        override suspend fun loadInitial(serverId: ServerId, limit: Int): DataResult<SavedSnapshot> {
            loadInitialCalls++
            loadInitialLimits += limit
            return DataResult.Content(SavedSnapshot(server = testServer(serverId), items = items))
        }

        override suspend fun loadMore(serverId: ServerId, offset: Int, limit: Int): DataResult<SavedSnapshot> =
            DataResult.Empty(EmptyReason.NoInboxItems)

        override suspend fun saveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> =
            DataResult.Content(SavedMessageMutationResult(messageId))

        override suspend fun unsaveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> {
            unsavedMessageId = messageId
            return DataResult.Content(SavedMessageMutationResult(messageId))
        }
    }

    private class FakeSearchRepository(
        private val items: List<InboxItem> = emptyList()
    ) : SearchRepository {
        var query: String? = null
        var options: SearchQueryOptions? = null
        val calls = mutableListOf<SearchInvocation>()

        override suspend fun search(
            serverId: ServerId,
            query: String,
            offset: Int,
            limit: Int,
            options: SearchQueryOptions
        ): DataResult<SearchSnapshot> {
            this.query = query
            this.options = options
            calls += SearchInvocation(query = query, offset = offset, limit = limit, options = options)
            return DataResult.Content(SearchSnapshot(server = testServer(serverId), query = query, items = items))
        }
    }

    private inner class DeferredSavedRepository : SavedRepository {
        private val pending = mutableListOf<Pair<ServerId, CompletableDeferred<DataResult<SavedSnapshot>>>>()

        override suspend fun loadInitial(serverId: ServerId, limit: Int): DataResult<SavedSnapshot> {
            val result = CompletableDeferred<DataResult<SavedSnapshot>>()
            pending += serverId to result
            return withContext(NonCancellable) { result.await() }
        }

        override suspend fun loadMore(serverId: ServerId, offset: Int, limit: Int): DataResult<SavedSnapshot> =
            DataResult.Empty(EmptyReason.NoInboxItems)

        override suspend fun saveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> =
            DataResult.Content(SavedMessageMutationResult(messageId))

        override suspend fun unsaveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> =
            DataResult.Content(SavedMessageMutationResult(messageId))

        fun completeNext(messageId: String) {
            val (serverId, result) = pending.removeAt(0)
            result.complete(
                DataResult.Content(
                    SavedSnapshot(
                        server = testServer(serverId),
                        items = listOf(inboxItem(messageId))
                    )
                )
            )
        }
    }

    private inner class QueuedSearchRepository : SearchRepository {
        private val pending = mutableListOf<Pair<ServerId, CompletableDeferred<DataResult<SearchSnapshot>>>>()

        override suspend fun search(
            serverId: ServerId,
            query: String,
            offset: Int,
            limit: Int,
            options: SearchQueryOptions
        ): DataResult<SearchSnapshot> {
            val result = CompletableDeferred<DataResult<SearchSnapshot>>()
            pending += serverId to result
            return withContext(NonCancellable) { result.await() }
        }

        fun completeNext(messageId: String) {
            val (serverId, result) = pending.removeAt(0)
            result.complete(
                DataResult.Content(
                    SearchSnapshot(
                        server = testServer(serverId),
                        query = "release",
                        items = listOf(inboxItem(messageId))
                    )
                )
            )
        }
    }

    private inner class DeferredSearchRepository : SearchRepository {
        val calls = mutableListOf<SearchInvocation>()
        private val results = mutableMapOf<String, CompletableDeferred<DataResult<SearchSnapshot>>>()

        override suspend fun search(
            serverId: ServerId,
            query: String,
            offset: Int,
            limit: Int,
            options: SearchQueryOptions
        ): DataResult<SearchSnapshot> {
            calls += SearchInvocation(query = query, offset = offset, limit = limit, options = options)
            return withContext(NonCancellable) {
                results.getOrPut(query) { CompletableDeferred() }.await()
            }
        }

        fun complete(
            query: String,
            messageId: String
        ) {
            results
                .getOrPut(query) { CompletableDeferred() }
                .complete(
                    DataResult.Content(
                        SearchSnapshot(
                            server = testServer(ServerId("server-1")),
                            query = query,
                            items = listOf(inboxItem(messageId))
                        )
                    )
                )
        }
    }

    private data class SearchInvocation(
        val query: String,
        val offset: Int,
        val limit: Int,
        val options: SearchQueryOptions
    )

    private fun sampleSession(userId: String = "user-1"): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@slock.local", displayName = "User One"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock"))
        )

    private fun inboxItem(messageId: String): InboxItem =
        InboxItem(
            key = messageId,
            kind = InboxItemKind.Channel,
            channelId = ChannelId("channel-1"),
            channelName = "general",
            latestActivityMessageId = MessageId(messageId),
            latestActivityPreview = "Preview",
            latestActivitySenderName = "User One",
            participantIds = listOf(MemberId("user-1"))
        )

    @Test
    fun savedSearchThreadItemResolveAnchorMessageId3LevelChain() {
        // 反例 11 regression: saved store's `savedMessageIdOrNull` must use the shared
        // 3-level anchor chain (`resolveInboxAnchorMessageId`) and never fall back to
        // `parentMessageId` for Thread items. Mirrors SharedInboxPanel regression so the
        // saved inbox surface and the main inbox surface share the same anchor contract.
        val threadOnlyLatest =
            InboxItem(
                key = "thread-saved-latest",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentMessageId = MessageId("parent-msg"),
                latestActivityMessageId = MessageId("latest-reply")
            )
        assertEquals(MessageId("latest-reply"), threadOnlyLatest.savedMessageIdOrNull())

        val threadOnlyLast =
            threadOnlyLatest.copy(
                key = "thread-saved-last",
                latestActivityMessageId = null,
                lastMessageId = MessageId("last-reply")
            )
        assertEquals(MessageId("last-reply"), threadOnlyLast.savedMessageIdOrNull())

        val threadNoneButParent =
            threadOnlyLatest.copy(
                key = "thread-saved-none-but-parent",
                latestActivityMessageId = null,
                lastMessageId = null
            )
        // Critical: parentMessageId must NOT appear in the saved anchor chain.
        assertEquals(null, threadNoneButParent.savedMessageIdOrNull())
    }
}

private fun testServer(id: ServerId): ServerSummary =
    ServerSummary(id = id, name = "Slock")
