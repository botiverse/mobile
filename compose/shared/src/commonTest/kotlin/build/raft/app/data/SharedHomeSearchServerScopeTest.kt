package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone Search tab stays composed across a server switch. Nothing found on, or recorded for,
 * the server the user left may surface on the server they switched to.
 */
class SharedHomeSearchServerScopeTest {
    @Test
    fun serverSwitchDropsPreviousServerRowsAndFiltersAndReRunsOnTheNewServer() = runBlocking<Unit> {
        val auth = EventingAuthStore(authSessionFixture(servers = twoServers, selectedServerId = "server-a"))
        val search = ServerTaggedSearchRepository()
        val storeScope = CoroutineScope(coroutineContext + Job())
        val store =
            SharedHomeSearchSavedStore(
                authStore = auth,
                savedRepository = EmptySavedRepository,
                searchRepository = search,
                scope = storeScope,
                searchDebounceMillis = 0L,
            )
        settle()
        assertEquals("server-a", store.state.value.search.serverId)

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("deploy"))
        settle()
        store.dispatch(SharedHomeSearchSavedIntent.SetSearchChannel("channel-on-a"))
        settle()
        assertEquals(listOf("server-a:deploy"), store.state.value.search.items.map { it.key })
        assertEquals("channel-on-a", store.state.value.search.selectedChannelId)

        auth.switchServer("server-b")
        settle()

        val after = store.state.value.search
        assertEquals("server-b", after.serverId)
        assertEquals("deploy", after.query)
        assertEquals(null, after.selectedChannelId)
        assertEquals(listOf("server-b:deploy"), after.items.map { it.key })
        assertTrue(after.items.none { it.key.startsWith("server-a") })
        val lastCall = search.calls.last()
        assertEquals("server-b", lastCall.first)
        assertEquals(null, lastCall.second.channelId)
        store.clear()
        storeScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun staleResponseForThePreviousServerIsDroppedAfterTheSwitch() = runBlocking<Unit> {
        // No auth event source: the switch is only visible through the session itself.
        val auth = InMemoryRaftAuthSessionStore(authSessionFixture(servers = twoServers, selectedServerId = "server-a"))
        val search = DeferredServerSearchRepository()
        val store =
            SharedHomeSearchSavedStore(
                authStore = auth,
                savedRepository = EmptySavedRepository,
                searchRepository = search,
                scope = this,
                searchDebounceMillis = 0L,
            )

        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("deploy"))
        settle()
        assertEquals(listOf("server-a"), search.requestedServers)

        auth.updateSelectedServer("server-b", "b")
        search.complete("server-a")
        settle()

        val afterStale = store.state.value.search
        assertEquals(emptyList(), afterStale.items.map { it.key })
        assertEquals("server-b", afterStale.serverId)
        assertEquals(listOf("server-a", "server-b"), search.requestedServers)

        search.complete("server-b")
        settle()
        assertEquals(listOf("server-b:deploy"), store.state.value.search.items.map { it.key })
        store.clear()
    }

    @Test
    fun searchPersonalizationIsKeyedByServerAndRejectsRowsFromAnotherServer() = runBlocking<Unit> {
        val now = 1_800_000_000_000L
        val auth = EventingAuthStore(authSessionFixture(servers = twoServers, selectedServerId = "server-a"))
        val storeScope = CoroutineScope(coroutineContext + Job())
        val store =
            SharedHomeSearchSavedStore(
                authStore = auth,
                savedRepository = EmptySavedRepository,
                searchRepository = ServerTaggedSearchRepository(),
                scope = storeScope,
                personalizationRepository = PersistentSearchPersonalizationRepository(InMemoryRaftKeyValueStore()),
                nowMillis = { now },
            )
        store.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        settle()
        store.dispatch(open(query = "deploy", channelId = "channel-a", serverId = "server-a"))
        settle()
        assertEquals(setOf(channelKey("channel-a")), store.state.value.search.frequentEntityRanks.keys)

        auth.switchServer("server-b")
        settle()
        assertEquals(emptyMap(), store.state.value.search.frequentEntityRanks)
        assertEquals(emptyList(), store.state.value.search.history)

        // A row resolved from the server the user left (a stale candidate) is not recorded here.
        store.dispatch(open(query = "deploy", channelId = "channel-a", serverId = "server-a"))
        settle()
        assertEquals(emptyMap(), store.state.value.search.frequentEntityRanks)
        assertEquals(emptyList(), store.state.value.search.history)

        store.dispatch(open(query = "infra", channelId = "channel-b", serverId = "server-b"))
        settle()
        assertEquals(setOf(channelKey("channel-b")), store.state.value.search.frequentEntityRanks.keys)
        assertEquals(listOf("infra"), store.state.value.search.history)

        auth.switchServer("server-a")
        settle()
        assertEquals(setOf(channelKey("channel-a")), store.state.value.search.frequentEntityRanks.keys)
        assertEquals(listOf("deploy"), store.state.value.search.history)
        store.clear()
        storeScope.coroutineContext[Job]?.cancel()
    }

    private suspend fun settle() {
        repeat(30) { yield() }
    }

    private fun open(query: String, channelId: String, serverId: String) =
        SharedHomeSearchSavedIntent.RecordSuccessfulSearchOpen(
            query = query,
            entityKind = SearchPersonalizationEntityKind.Channel,
            entityId = channelId,
            serverId = serverId,
        )

    private fun channelKey(id: String) = SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Channel, id)

    private val twoServers =
        listOf(
            StoredAuthServer(id = "server-a", name = "Botiverse", slug = "a"),
            StoredAuthServer(id = "server-b", name = "Raft", slug = "b"),
        )

    /** Mirrors the shell auth store: a server selection emits [AuthSessionEvent.BindingMetadataChanged]. */
    private class EventingAuthStore(
        initial: StoredAuthSession,
        private val inner: InMemoryRaftAuthSessionStore = InMemoryRaftAuthSessionStore(initial),
    ) : RaftAuthSessionStore by inner, RaftAuthSessionEventSource {
        private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 8)
        private var generation = 0L
        override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

        suspend fun switchServer(serverId: String) {
            inner.updateSelectedServer(serverId, null)
            events.emit(AuthSessionEvent.BindingMetadataChanged(++generation))
        }
    }

    private class ServerTaggedSearchRepository : SearchRepository {
        val calls = mutableListOf<Pair<String, SearchQueryOptions>>()

        override suspend fun search(
            serverId: ServerId,
            query: String,
            offset: Int,
            limit: Int,
            options: SearchQueryOptions,
            cursor: String?
        ): DataResult<SearchSnapshot> {
            calls += serverId.value to options
            return DataResult.Content(
                SearchSnapshot(
                    server = ServerSummary(serverId, serverId.value),
                    query = query,
                    items = listOf(searchHitRow("${serverId.value}:$query")),
                )
            )
        }
    }

    private class DeferredServerSearchRepository : SearchRepository {
        val requestedServers = mutableListOf<String>()
        private val results = mutableMapOf<String, CompletableDeferred<Unit>>()

        override suspend fun search(
            serverId: ServerId,
            query: String,
            offset: Int,
            limit: Int,
            options: SearchQueryOptions,
            cursor: String?
        ): DataResult<SearchSnapshot> {
            requestedServers += serverId.value
            withContext(NonCancellable) { results.getOrPut(serverId.value) { CompletableDeferred() }.await() }
            return DataResult.Content(
                SearchSnapshot(
                    server = ServerSummary(serverId, serverId.value),
                    query = query,
                    items = listOf(searchHitRow("${serverId.value}:$query")),
                )
            )
        }

        fun complete(serverId: String) {
            results.getOrPut(serverId) { CompletableDeferred() }.complete(Unit)
        }
    }
}
