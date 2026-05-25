package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
    fun searchPanelSendsQueryFiltersAndSortToRepository() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-2")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
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
    fun clearSearchFiltersResetsSharedSearchParityState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = FakeSearchRepository(items = listOf(inboxItem("message-3")))
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope,
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
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedRepository = FakeSavedRepository(),
                searchRepository = search,
                scope = scope
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

    private class FakeSavedRepository(
        private val items: List<InboxItem> = emptyList()
    ) : SavedRepository {
        var unsavedMessageId: MessageId? = null

        override suspend fun loadInitial(serverId: ServerId, limit: Int): DataResult<SavedSnapshot> =
            DataResult.Content(SavedSnapshot(server = testServer(serverId), items = items))

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

        override suspend fun search(
            serverId: ServerId,
            query: String,
            offset: Int,
            limit: Int,
            options: SearchQueryOptions
        ): DataResult<SearchSnapshot> {
            this.query = query
            this.options = options
            return DataResult.Content(SearchSnapshot(server = testServer(serverId), query = query, items = items))
        }
    }

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@slock.local", displayName = "User One"),
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
}

private fun testServer(id: ServerId): ServerSummary =
    ServerSummary(id = id, name = "Slock")
