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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
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

    @Test
    fun searchPersonalizationPrewarmsSynchronouslyAtStoreConstruction() = runBlocking {
        val repository = PersistentSearchPersonalizationRepository(InMemoryRaftKeyValueStore())
        val now = 1_800_000_000_000L
        val writerStore =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = FakeSearchRepository(),
                scope = this,
                personalizationRepository = repository,
                nowMillis = { now },
            )
        writerStore.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        yield()
        writerStore.dispatch(
            SharedHomeSearchSavedIntent.RecordSuccessfulSearchOpen(
                query = "release",
                entityKind = SearchPersonalizationEntityKind.Channel,
                entityId = "channel-1",
            )
        )
        yield()
        writerStore.clear()

        // A brand-new store instance (fresh Search surface) must already expose the
        // persisted history/frequent rows in its initial state, before any intent
        // runs and without yielding to the async refresh.
        val readerStore =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = FakeSearchRepository(),
                scope = this,
                personalizationRepository = repository,
                nowMillis = { now },
            )
        assertEquals(true, readerStore.state.value.search.personalizationLoaded)
        assertEquals(listOf("release"), readerStore.state.value.search.history)
        assertEquals(
            setOf(SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Channel, "channel-1")),
            readerStore.state.value.search.frequentEntityRanks.keys,
        )

        // OpenSearch must reconcile without clearing the prewarmed rows.
        readerStore.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        assertEquals(true, readerStore.state.value.search.personalizationLoaded)
        assertEquals(listOf("release"), readerStore.state.value.search.history)
        assertEquals(1, readerStore.state.value.search.frequentEntityRanks.size)
        yield()
        assertEquals(listOf("release"), readerStore.state.value.search.history)
        assertEquals(1, readerStore.state.value.search.frequentEntityRanks.size)
        readerStore.clear()
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
    fun rapidSuccessfulOpensPersistInDispatchOrder() = runBlocking {
        val repository = PersistentSearchPersonalizationRepository(InMemoryRaftKeyValueStore())
        var now = 1_800_000_000_000L
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = FakeSearchRepository(),
                scope = this,
                personalizationRepository = repository,
                nowMillis = { now++ },
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        yield()
        store.dispatch(SharedHomeSearchSavedIntent.RecordSuccessfulSearchOpen("first"))
        store.dispatch(SharedHomeSearchSavedIntent.RecordSuccessfulSearchOpen("second"))
        yield()

        assertEquals(listOf("second", "first"), store.state.value.search.history)
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
    fun processReadPredictionClearsMatchingSavedAndSearchOwnerOnly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val unreadItem =
            inboxItem("message-unread").copy(
                unreadCount = 2,
                firstUnreadMessageId = MessageId("message-unread")
            )
        val coordinator =
            ReadReceiptCoordinator(
                scope = scope,
                repository = ImmediateReadReceiptRepository()
            )
        val ownerFactory = { serverId: String, accountId: String ->
            ReadReceiptOwner("production", accountId, serverId)
        }
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(items = listOf(unreadItem)),
                searchRepository = FakeSearchRepository(items = listOf(unreadItem)),
                scope = scope,
                searchDebounceMillis = 0L,
                readReceiptCoordinator = coordinator,
                readReceiptOwnerFactory = ownerFactory
            )
        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))

        coordinator.markReadAll(
            ReadReceiptOwner("production", "other-user", "server-1"),
            ChannelId("channel-1")
        )
        assertEquals(2, store.state.value.saved.items.single().unreadCount)
        assertEquals(2, store.state.value.search.items.single().unreadCount)

        coordinator.markReadAll(
            ReadReceiptOwner("production", "user-1", "server-1"),
            ChannelId("channel-1")
        )
        assertEquals(0, store.state.value.saved.items.single().unreadCount)
        assertEquals(0, store.state.value.search.items.single().unreadCount)
        scope.cancel()
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
    fun refreshStartedWhileUnsavePendingCannotRestoreItem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = DeferredMutationSavedRepository(items = listOf(inboxItem("message-1")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = saved,
                searchRepository = FakeSearchRepository(),
                scope = scope
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        store.dispatch(SharedHomeSearchSavedIntent.Unsave(store.state.value.saved.items.single()))
        saved.awaitUnsave("message-1")
        store.dispatch(SharedHomeSearchSavedIntent.RefreshSaved)

        assertEquals(emptyList(), store.state.value.saved.items)
        saved.completeUnsave("message-1", DataResult.Content(SavedMessageMutationResult(MessageId("message-1"))))
        assertEquals(emptyList(), store.state.value.saved.items)
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
    fun failedUnsaveRestoresCanonicalRowInsteadOfProjectedIntent() = runBlocking {
        val cache = SharedHomeSavedStateCache()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val canonicalItem = inboxItem("message-1").copy(latestActivityPreview = "Canonical preview")
        val saved = DeferredMutationSavedRepository(items = listOf(canonicalItem))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = saved,
                searchRepository = FakeSearchRepository(),
                savedStateCache = cache,
                scope = scope
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        val projectedIntent = canonicalItem.copy(latestActivityPreview = "Projected draft preview")
        store.dispatch(SharedHomeSearchSavedIntent.Unsave(projectedIntent))
        saved.awaitUnsave("message-1")
        saved.completeUnsave("message-1", DataResult.Error(ScreenError("delete_failed")))

        assertEquals(listOf(canonicalItem), store.state.value.saved.items)
        assertEquals(
            listOf(canonicalItem),
            cache.read("server-1", "user-1")?.items
        )
        scope.cancel()
    }

    @Test
    fun failedConcurrentUnsavesRestoreCanonicalOrderRegardlessOfCompletionOrder() = runBlocking {
        val canonicalItems =
            listOf(
                inboxItem("message-1"),
                inboxItem("message-2"),
                inboxItem("message-3")
            )

        listOf(
            listOf("message-1", "message-2"),
            listOf("message-2", "message-1")
        ).forEach { completionOrder ->
            val cache = SharedHomeSavedStateCache()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val saved = DeferredMutationSavedRepository(items = canonicalItems)
            val store =
                SharedHomeSearchSavedStore(
                    authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                    savedRepository = saved,
                    searchRepository = FakeSearchRepository(),
                    savedStateCache = cache,
                    scope = scope
                )

            store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
            store.dispatch(SharedHomeSearchSavedIntent.Unsave(canonicalItems[0]))
            store.dispatch(SharedHomeSearchSavedIntent.Unsave(canonicalItems[1]))
            saved.awaitUnsave("message-1")
            saved.awaitUnsave("message-2")

            completionOrder.forEach { messageId ->
                saved.completeUnsave(messageId, DataResult.Error(ScreenError("delete_failed")))
            }

            assertEquals(canonicalItems, store.state.value.saved.items)
            assertEquals(
                canonicalItems,
                cache.read("server-1", "user-1")?.items
            )
            scope.cancel()
        }
    }

    @Test
    fun optimisticUnsaveUpdatesSharedSavedCache() = runBlocking {
        val cache = SharedHomeSavedStateCache()
        val primaryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val primaryStore =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(items = listOf(inboxItem("message-1"))),
                searchRepository = FakeSearchRepository(),
                savedStateCache = cache,
                scope = primaryScope
            )
        primaryStore.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        primaryStore.dispatch(SharedHomeSearchSavedIntent.Unsave(primaryStore.state.value.saved.items.single()))

        val secondaryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val secondaryRepository = DeferredSavedRepository()
        val secondaryStore =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = secondaryRepository,
                searchRepository = FakeSearchRepository(),
                savedStateCache = cache,
                scope = secondaryScope
            )
        secondaryStore.dispatch(SharedHomeSearchSavedIntent.OpenSaved)

        assertEquals(emptyList(), secondaryStore.state.value.saved.items)
        assertEquals(0, secondaryStore.state.value.saved.totalCount)
        primaryScope.cancel()
        secondaryScope.cancel()
    }

    @Test
    fun clearSavedSummaryDropsSavedCountsButKeepsSearchState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(items = listOf(inboxItem("message-1"))),
                searchRepository = FakeSearchRepository(),
                scope = scope
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))

        assertEquals(listOf("message-1"), store.state.value.saved.items.map { it.key })

        store.dispatch(SharedHomeSearchSavedIntent.ClearSavedSummary)

        assertEquals(emptyList(), store.state.value.saved.items)
        assertEquals(0, store.state.value.saved.totalCount)
        assertEquals(false, store.state.value.saved.loaded)
        assertEquals("release", store.state.value.search.query)
        scope.cancel()
    }

    @Test
    fun openSavedRefreshesLoadedPanelOnReentry() = runBlocking {
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
    fun openSavedShowsRefreshingAffordanceDuringLoadedReentry() = runBlocking {
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
    fun searchQueryChangeShowsPendingAffordanceBeforeDebounceRuns() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-pending")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun preselectSearchChannelDoesNotStartSearch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-preselect")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L
            )

        store.dispatch(SharedHomeSearchSavedIntent.PreselectSearchChannel("channel-1"))

        assertEquals("channel-1", store.state.value.search.selectedChannelId)
        assertEquals(emptyList(), search.calls)
        assertEquals(false, store.state.value.search.searched)
        scope.cancel()
    }

    @Test
    fun searchRefreshShowsRefreshingAffordanceDuringLoadedRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = QueuedSearchRepository()
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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

    // Nav spec §9.7 / §10.2: the Search tab keeps its results and scroll. The tab refreshes when
    // the shell reappears (Back from a result, foreground); that refresh reloads every row shown,
    // not just the first page, so a reader scrolled past it keeps their place.
    @Test
    fun refreshSearchReloadsEveryLoadedPageWhileNewSearchStartsFromFirstPage() = runTest {
        val newSearches = listOf(null, SharedHomeSearchSavedIntent.SubmitSearch, SharedHomeSearchSavedIntent.SetSearchSort(SearchSort.Recent))
        for (newSearch in newSearches) {
            val search = PagedSearchRepository(total = 75)
            val store =
                SharedHomeSearchSavedStore(
                    authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                    savedRepository = FakeSavedRepository(),
                    searchRepository = search,
                    scope = backgroundScope,
                    searchDebounceMillis = 0L
                )
            store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))
            runCurrent()
            store.dispatch(SharedHomeSearchSavedIntent.LoadMoreSearch)
            runCurrent()
            assertEquals(60, store.state.value.search.items.size)
            search.calls.clear()

            store.dispatch(newSearch ?: SharedHomeSearchSavedIntent.RefreshSearch)
            runCurrent()

            val after = store.state.value.search
            if (newSearch == null) {
                assertEquals(listOf(0 to 30, 30 to 30), search.calls)
                assertEquals((0 until 60).map { "message-$it" }, after.items.map { it.key })
                assertEquals(true to 60, after.hasMore to after.nextOffset)
            } else {
                assertEquals(listOf(0 to 30), search.calls, "new search $newSearch")
                assertEquals(30, after.items.size, "new search $newSearch")
            }
            assertEquals(false, after.refreshing)
        }
    }

    @Test
    fun searchDepthLoadKeepsLoadedRowsWhenALaterPageFails() = runTest {
        val search = PagedSearchRepository(total = 100, failAtOffset = 60)
        val result =
            loadSearchSnapshotToDepth(depth = 90, pageSize = 30) { offset, limit ->
                search.search(ServerId("server-1"), "release", offset, limit, SearchQueryOptions())
            }
        val snapshot = (result as DataResult.Content).data
        assertEquals(Triple(60, true, 60), Triple(snapshot.items.size, snapshot.hasMore, snapshot.nextOffset))
        val firstPageFailure =
            loadSearchSnapshotToDepth(depth = 90, pageSize = 30) { _, _ -> DataResult.Error(ScreenError(code = "search_network")) }
        assertEquals("search_network", (firstPageFailure as DataResult.Error).error.code)
    }

    @Test
    fun sortChangeDuringInitialSearchStaysLoadingUntilReplacementCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = QueuedSearchRepository()
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L
            )

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))
        assertEquals(true, store.state.value.search.loading)
        assertEquals(false, store.state.value.search.refreshing)
        assertEquals(emptyList(), store.state.value.search.items)

        store.dispatch(SharedHomeSearchSavedIntent.SetSearchSort(SearchSort.Recent))

        assertEquals(true, store.state.value.search.loading)
        assertEquals(false, store.state.value.search.refreshing)
        assertEquals(emptyList(), store.state.value.search.items)

        search.completeNext("message-stale")
        assertEquals(true, store.state.value.search.loading)
        assertEquals(emptyList(), store.state.value.search.items)

        search.completeNext("message-recent")
        assertEquals(false, store.state.value.search.loading)
        assertEquals(false, store.state.value.search.refreshing)
        assertEquals(listOf("message-recent"), store.state.value.search.items.map { it.key })
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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

    @Test
    fun clearSearchFiltersClearsScopes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-scope-clear")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
                searchDebounceMillis = 0L
            )

        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchScope(SearchScope.Agents))
        val callsBeforeClear = search.calls.size
        store.dispatch(SharedHomeSearchSavedIntent.ClearSearchFilters)

        assertEquals(emptyList(), store.state.value.search.selectedScopes)
        assertEquals(false, store.state.value.search.searched)
        assertEquals(emptyList(), store.state.value.search.items)
        assertEquals(callsBeforeClear, search.calls.size)
        scope.cancel()
    }

    @Test
    fun explicitSenderKindOnlyPrunesAnExclusiveConflictingScope() {
        assertEquals(
            listOf(SearchScope.Mentioned),
            listOf(SearchScope.Mentioned, SearchScope.Agents)
                .withSenderKindConflictResolved(MemberKind.Human)
        )
        assertEquals(
            listOf(SearchScope.Mentioned),
            listOf(SearchScope.Mentioned, SearchScope.Humans)
                .withSenderKindConflictResolved(MemberKind.Agent)
        )
        assertEquals(
            SearchScope.entries,
            SearchScope.entries.withSenderKindConflictResolved(MemberKind.Human)
        )
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
            options: SearchQueryOptions
        ): DataResult<SearchSnapshot> {
            this.query = query
            this.options = options
            calls += SearchInvocation(query = query, offset = offset, limit = limit, options = options)
            return DataResult.Content(SearchSnapshot(server = testServer(serverId), query = query, items = items))
        }
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

    /** Serves `message-0 until total` by offset/limit and records each (offset, limit) call. */
    private inner class PagedSearchRepository(
        private val total: Int,
        private val failAtOffset: Int? = null,
    ) : SearchRepository {
        val calls = mutableListOf<Pair<Int, Int>>()

        override suspend fun search(
            serverId: ServerId,
            query: String,
            offset: Int,
            limit: Int,
            options: SearchQueryOptions
        ): DataResult<SearchSnapshot> {
            calls += offset to limit
            if (offset == failAtOffset) return DataResult.Error(ScreenError(code = "search_network"))
            val end = minOf(offset + limit, total)
            return DataResult.Content(
                SearchSnapshot(
                    server = testServer(serverId),
                    query = query,
                    items = (offset until end).map { inboxItem("message-$it") },
                    hasMore = end < total,
                    nextOffset = end
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
