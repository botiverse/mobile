package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedHomeSearchSavedStoreTest {
    @Test
    fun searchPersonalizationHydratesAndClearPreservesFrequentUsage() = runBlocking {
        val repository = PersistentSearchPersonalizationRepository(InMemoryRaftKeyValueStore())
        val now = 1_800_000_000_000L
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = FakeSearchRepository(),
                scope = this,
                personalizationRepository = repository,
                nowMillis = { now },
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        yield()
        assertEquals(true, store.state.value.search.personalizationLoaded)

        store.dispatch(
            SharedHomeSearchSavedIntent.RecordSuccessfulSearchOpen(
                query = " release ",
                entityKind = SearchPersonalizationEntityKind.Channel,
                entityId = "channel-1",
            )
        )
        yield()
        assertEquals(listOf("release"), store.state.value.search.history)
        assertEquals(
            setOf(SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Channel, "channel-1")),
            store.state.value.search.frequentEntityRanks.keys,
        )

        store.dispatch(SharedHomeSearchSavedIntent.ClearSearchHistory)
        yield()
        assertEquals(emptyList(), store.state.value.search.history)
        assertEquals(1, store.state.value.search.frequentEntityRanks.size)
        store.clear()
    }

    @Test
    fun retainedMessageRowsKeepTheQueryThatProducedThem() = runBlocking {
        val search = DeferredSearchRepository()
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = this,
                searchDebounceMillis = 0L,
            )

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("first query"))
        yield()
        search.complete("first query", "message-first")
        yield()
        assertEquals("first query", store.state.value.search.resultQuery)

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("second query"))
        yield()
        assertEquals("second query", store.state.value.search.query)
        assertEquals(listOf("message-first"), store.state.value.search.items.map { it.key })
        assertEquals("first query", store.state.value.search.resultQuery)
        search.complete("second query", "message-second")
        yield()
        store.clear()
    }

    /**
     * Hands ANR fa8f14bb / 0c46a9d0 / 736be855 (1.15.4): the store is built lazily inside
     * composition on the main thread, and its constructor ran `runBlocking { readSession() }`.
     * When the session lock was not free the main thread parked (Input dispatching timed out).
     * Construction must return while the session read is still pending, read the session in the
     * store's own scope (never a private blocking loop), and project the rows once it resolves.
     */
    @Test
    fun storeConstructionDoesNotBlockOnAHeldSessionLock() = runBlocking {
        val now = 1_800_000_000_000L
        val repository = PersistentSearchPersonalizationRepository(InMemoryRaftKeyValueStore())
        repository.recordSuccessfulOpen(
            owner = SearchPersonalizationOwner("server-1", "user-1"),
            rawQuery = "release",
            entityKey = null,
            openedAtMillis = now,
        )
        val auth = HeldLockSessionStore(sampleSession(), storeDispatcher = Dispatchers.Unconfined)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val store =
            SharedHomeSearchSavedStore(
                authStore = auth,
                savedRepository = FakeSavedRepository(),
                searchRepository = FakeSearchRepository(),
                scope = scope,
                personalizationRepository = repository,
                nowMillis = { now },
            )

        // The constructor returned with all three session reads (server owner, personalization
        // prewarm, Saved's server-scope follow) still waiting for the lock, and none ran inside a
        // blocking event loop.
        assertEquals(0, auth.readsOnForeignDispatcher)
        assertEquals(3, auth.waitingReads)
        assertEquals(false, store.state.value.search.personalizationLoaded)

        auth.releaseLock()

        assertEquals("server-1", store.state.value.search.serverId)
        assertEquals(true, store.state.value.search.personalizationLoaded)
        assertEquals(listOf("release"), store.state.value.search.history)
        store.clear()
        scope.cancel()
    }

    @Test
    fun latePersonalizationReadCannotCrossAccountBoundary() = runBlocking {
        val auth = InMemoryRaftAuthSessionStore(sampleSession("user-a"))
        val repository = DeferredSearchPersonalizationRepository()
        val store =
            SharedHomeSearchSavedStore(
                authStore = auth,
                savedRepository = FakeSavedRepository(),
                searchRepository = FakeSearchRepository(),
                scope = this,
                personalizationRepository = repository,
                nowMillis = { 1_800_000_000_000L },
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        yield()
        auth.saveSession(sampleSession("user-b"))
        store.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        yield()
        assertEquals(emptyList(), store.state.value.search.history)

        repository.complete("user-b", history = listOf("owner-b"))
        yield()
        assertEquals(listOf("owner-b"), store.state.value.search.history)
        repository.complete("user-a", history = listOf("owner-a"))
        yield()
        assertEquals(listOf("owner-b"), store.state.value.search.history)
        store.clear()
    }

    @Test
    fun samePrincipalOnAnotherServerNeverSeesThePreviousServerHistory() = runBlocking {
        val auth = InMemoryRaftAuthSessionStore(sampleSession(serverId = "server-a"))
        val repository = PersistentSearchPersonalizationRepository(InMemoryRaftKeyValueStore())
        val store =
            SharedHomeSearchSavedStore(
                authStore = auth,
                savedRepository = FakeSavedRepository(),
                searchRepository = FakeSearchRepository(),
                scope = this,
                personalizationRepository = repository,
                nowMillis = { 1_800_000_000_000L },
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        yield()
        store.dispatch(SharedHomeSearchSavedIntent.RecordSuccessfulSearchOpen("server-a-only"))
        yield()
        assertEquals(listOf("server-a-only"), store.state.value.search.history)

        auth.saveSession(sampleSession(serverId = "server-b"))
        store.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        yield()

        assertEquals(emptyList(), store.state.value.search.history)
        assertEquals(true, store.state.value.search.personalizationLoaded)
        store.clear()
    }

    @Test
    fun savedPanelLoadsAndUnsaveRemovesItem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = FakeSavedRepository(items = listOf(inboxItem("message-1")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun staleRefreshCompletionCannotRestoreSuccessfullyUnsavedItem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = DeferredSavedRepository()
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = saved,
                searchRepository = FakeSearchRepository(),
                scope = scope
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        saved.completeNext("message-1")
        store.dispatch(SharedHomeSearchSavedIntent.RefreshSaved)
        store.dispatch(SharedHomeSearchSavedIntent.Unsave(store.state.value.saved.items.single()))

        saved.completeNext("message-1")

        assertEquals(emptyList(), store.state.value.saved.items)
        assertEquals(0, store.state.value.saved.totalCount)
        scope.cancel()
    }

    @Test
    fun failedConcurrentUnsaveRestoresOnlyItsOwnItem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved =
            DeferredMutationSavedRepository(
                items = listOf(inboxItem("message-1"), inboxItem("message-2"))
            )
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = saved,
                searchRepository = FakeSearchRepository(),
                scope = scope
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        val message1 = store.state.value.saved.items.first()
        val message2 = store.state.value.saved.items.last()
        store.dispatch(SharedHomeSearchSavedIntent.Unsave(message1))
        store.dispatch(SharedHomeSearchSavedIntent.Unsave(message2))
        saved.awaitUnsave("message-1")
        saved.awaitUnsave("message-2")

        saved.completeUnsave("message-1", DataResult.Content(SavedMessageMutationResult(MessageId("message-1"))))
        saved.completeUnsave("message-2", DataResult.Error(ScreenError("delete_failed")))

        assertEquals(listOf("message-2"), store.state.value.saved.items.map { it.key })
        assertEquals(1, store.state.value.saved.totalCount)
        assertEquals("saved_unsave_failed", store.state.value.saved.errorCode)
        scope.cancel()
    }

    @Test
    fun openSavedRequestsHundredItemsForHomeCount() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = FakeSavedRepository(items = listOf(inboxItem("message-1")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(items = listOf(inboxItem("message-1"))),
                searchRepository = FakeSearchRepository(),
                savedStateCache = cache,
                scope = preloadScope
            )
        preloadStore.dispatch(SharedHomeSearchSavedIntent.OpenSaved)

        val standaloneScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val standaloneStore =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession(userId = "user-2")),
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
    fun pullRefreshSavedShowsPullRefreshAffordanceDuringLoadedRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = DeferredSavedRepository()
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
        assertEquals("user-1", search.options?.senderId)
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun multiWordSearchQueryIsSubmittedIntactAndDiagnosedWithoutRawText() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val diagnostics = mutableListOf<String>()
        val search = FakeSearchRepository(items = listOf(inboxItem("message-phrase")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L,
                diagnosticsLogger = diagnostics::add
            )

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged(" do not "))

        assertEquals(" do not ", store.state.value.search.query)
        assertEquals(" do not ", search.query)
        assertEquals(listOf(" do not "), search.calls.map { it.query })
        assertEquals(true, diagnostics.any { it.contains("domain=search") && it.contains("queryLen=6") && it.contains("queryWords=2") })
        assertEquals(false, diagnostics.any { it.contains("do not") })
        scope.cancel()
    }

    @Test
    fun blankQueryWithFilterAutoBrowsesServerResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-filter-only")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L
            )

        store.dispatch(SharedHomeSearchSavedIntent.SetSearchSender("user-1"))

        assertEquals("", search.query)
        assertEquals("user-1", search.options?.senderId)
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
        assertEquals(null, search.options?.senderId)
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L
            )

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("handoff"))
        store.dispatch(SharedHomeSearchSavedIntent.SetMyMessagesOnly(true))
        store.dispatch(SharedHomeSearchSavedIntent.SetSearchSender("agent-1"))
        store.dispatch(SharedHomeSearchSavedIntent.SubmitSearch)

        assertEquals(false, store.state.value.search.myMessagesOnly)
        assertEquals("agent-1", store.state.value.search.selectedSenderId)
        assertEquals("agent-1", search.options?.senderId)
        scope.cancel()
    }

    @Test
    fun scopeFilterSupportsMentionAndSenderTypeMultiSelect() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-scope")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L
            )

        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchScope(SearchScope.Mentioned))
        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchScope(SearchScope.Humans))

        assertEquals(listOf(SearchScope.Mentioned, SearchScope.Humans), store.state.value.search.selectedScopes)
        assertEquals(listOf(SearchScope.Mentioned, SearchScope.Humans), search.options?.scopes)
        assertEquals("", search.query)

        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchScope(SearchScope.Agents))

        assertEquals(SearchScope.entries, store.state.value.search.selectedScopes)
        assertEquals(null, search.options?.scopes?.exclusiveSenderTypeWireValue())
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

    private class ImmediateReadReceiptRepository : ReadReceiptRepository {
        override suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot> =
            DataResult.Empty(EmptyReason.NoInboxItems)

        override suspend fun markChannelReadToSeq(
            channelId: ChannelId,
            seq: Long
        ): DataResult<ReadReceiptResult> =
            DataResult.Content(ReadReceiptResult(channelId = channelId, seq = seq))

        override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> =
            DataResult.Content(ReadReceiptResult(channelId = channelId))

        override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> =
            DataResult.Content(ReadReceiptResult(channelId = channelId, unreadCount = 1))

        override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> =
            DataResult.Content(ReadReceiptResult())
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
            options: SearchQueryOptions,
            cursor: String?
        ): DataResult<SearchSnapshot> {
            this.query = query
            this.options = options
            calls += SearchInvocation(query = query, offset = offset, limit = limit, options = options)
            return DataResult.Content(SearchSnapshot(server = testServer(serverId), query = query, items = items))
        }
    }

    /**
     * A session store whose lock is held by someone else until [releaseLock]. A read issued from
     * a dispatcher other than the store scope's (e.g. a nested `runBlocking` loop) is counted and
     * answered at once, so a regression fails the assertions instead of hanging the test.
     */
    private class HeldLockSessionStore(
        private val session: StoredAuthSession,
        private val storeDispatcher: ContinuationInterceptor,
    ) : RaftAuthSessionStore {
        private val lock = CompletableDeferred<Unit>()
        var readsOnForeignDispatcher = 0
            private set
        var waitingReads = 0
            private set

        fun releaseLock() {
            lock.complete(Unit)
        }

        override suspend fun readSession(): StoredAuthSession? {
            if (currentCoroutineContext()[ContinuationInterceptor] !== storeDispatcher) {
                readsOnForeignDispatcher++
                return session
            }
            waitingReads++
            lock.await()
            waitingReads--
            return session
        }

        override suspend fun saveSession(session: StoredAuthSession) = error("not used")

        override suspend fun updateTokens(accessToken: String, refreshToken: String) = error("not used")

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) = error("not used")

        override suspend fun clearSession(reason: String, source: String) = error("not used")
    }

    private class DeferredSearchPersonalizationRepository : SearchPersonalizationRepository {
        private val reads = mutableMapOf<String, CompletableDeferred<SearchPersonalizationSnapshot>>()

        override suspend fun read(
            owner: SearchPersonalizationOwner,
            nowMillis: Long,
        ): SearchPersonalizationSnapshot =
            reads.getOrPut(owner.principalId) { CompletableDeferred() }.await()

        override suspend fun recordSuccessfulOpen(
            owner: SearchPersonalizationOwner,
            rawQuery: String,
            entityKey: SearchPersonalizationEntityKey?,
            openedAtMillis: Long,
        ): SearchPersonalizationSnapshot = error("not used")

        override suspend fun removeHistoryEntry(
            owner: SearchPersonalizationOwner,
            rawQuery: String,
            nowMillis: Long,
        ): SearchPersonalizationSnapshot = error("not used")

        override suspend fun clearHistory(
            owner: SearchPersonalizationOwner,
            nowMillis: Long,
        ): SearchPersonalizationSnapshot = error("not used")

        fun complete(principalId: String, history: List<String>) {
            reads.getOrPut(principalId) { CompletableDeferred() }.complete(
                SearchPersonalizationSnapshot(
                    owner = SearchPersonalizationOwner("server-1", principalId),
                    history = history,
                )
            )
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

    private inner class DeferredMutationSavedRepository(
        var items: List<InboxItem>
    ) : SavedRepository {
        private val unsaveStarted = mutableMapOf<String, CompletableDeferred<Unit>>()
        private val unsaveResults = mutableMapOf<String, CompletableDeferred<DataResult<SavedMessageMutationResult>>>()

        override suspend fun loadInitial(serverId: ServerId, limit: Int): DataResult<SavedSnapshot> =
            DataResult.Content(SavedSnapshot(server = testServer(serverId), items = items))

        override suspend fun loadMore(serverId: ServerId, offset: Int, limit: Int): DataResult<SavedSnapshot> =
            DataResult.Empty(EmptyReason.NoInboxItems)

        override suspend fun saveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> =
            DataResult.Content(SavedMessageMutationResult(messageId))

        override suspend fun unsaveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> {
            unsaveStarted.getOrPut(messageId.value) { CompletableDeferred() }.complete(Unit)
            val result = unsaveResults.getOrPut(messageId.value) { CompletableDeferred() }
            return withContext(NonCancellable) { result.await() }
        }

        suspend fun awaitUnsave(messageId: String) {
            unsaveStarted.getOrPut(messageId) { CompletableDeferred() }.await()
        }

        fun completeUnsave(messageId: String, result: DataResult<SavedMessageMutationResult>) {
            unsaveResults.getOrPut(messageId) { CompletableDeferred() }.complete(result)
        }
    }

    private inner class QueuedSearchRepository : SearchRepository {
        private val pending = mutableListOf<Pair<ServerId, CompletableDeferred<DataResult<SearchSnapshot>>>>()

        override suspend fun search(
            serverId: ServerId,
            query: String,
            offset: Int,
            limit: Int,
            options: SearchQueryOptions,
            cursor: String?
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
            options: SearchQueryOptions,
            cursor: String?
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

    private fun sampleSession(
        userId: String = "user-1",
        serverId: String = "server-1",
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@slock.local", displayName = "User One"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = serverId,
            servers = listOf(StoredAuthServer(id = serverId, name = "Slock"))
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
            // Channel-kind rows anchor on lastMessageId per the React row anchor
            // contract (85cf746c); saved unsave resolves the message id from it.
            lastMessageId = MessageId(messageId),
            participantIds = listOf(MemberId("user-1"))
        )

    @Test
    fun savedSearchThreadItemResolveAnchorMessageIdUsesReplyActivityOnly() {
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

        val threadStaleUnread =
            threadOnlyLatest.copy(
                key = "thread-saved-stale-unread",
                firstUnreadMessageId = MessageId("stale-unread-reply"),
                unreadCount = 0
            )
        assertEquals(MessageId("latest-reply"), threadStaleUnread.savedMessageIdOrNull())

        val threadOnlyLast =
            threadOnlyLatest.copy(
                key = "thread-saved-last",
                latestActivityMessageId = null,
                lastMessageId = MessageId("last-reply")
            )
        assertEquals(null, threadOnlyLast.savedMessageIdOrNull())

        val threadNoneButParent =
            threadOnlyLatest.copy(
                key = "thread-saved-none-but-parent",
                latestActivityMessageId = null,
                lastMessageId = null
            )
        // Critical: parentMessageId must NOT appear in the saved anchor chain.
        assertEquals(null, threadNoneButParent.savedMessageIdOrNull())
    }

    @Test
    fun savedMutationUsesCanonicalSavedKeyBeforeNavigationAnchor() {
        val savedReply =
            InboxItem(
                key = "saved:canonical-reply",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                latestActivityMessageId = MessageId("newer-reply")
            )

        assertEquals(MessageId("canonical-reply"), savedReply.savedMessageIdOrNull())
    }
}

private fun testServer(id: ServerId): ServerSummary =
    ServerSummary(id = id, name = "Slock")
