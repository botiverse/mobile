package build.raft.app.data

import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A page that stays on screen across a server switch (the wide layout's inline Saved / Computers
 * beside the server rail) follows the shell's selection: the rows of the server the user left go,
 * and the list loads again for the new server (artin, Android 1.25: Saved kept the old server's
 * items after a switch).
 */
class RaftServerSwitchReloadTest {
    @Test
    fun openSavedListReloadsForTheNewServerAfterASwitch() = runBlocking<Unit> {
        val auth = SwitchingAuthStore(authSessionFixture(servers = twoServers, selectedServerId = "server-a"))
        val saved = ServerTaggedSavedRepository()
        val storeScope = CoroutineScope(coroutineContext + Job())
        val store =
            SharedHomeSearchSavedStore(
                authStore = auth,
                savedRepository = saved,
                searchRepository = NoSearchRepository,
                scope = storeScope,
            )
        settle()
        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        settle()
        assertEquals(listOf("server-a:saved"), store.state.value.saved.items.map { it.key })

        auth.switchServer("server-b")
        settle()

        assertEquals(listOf("server-b:saved"), store.state.value.saved.items.map { it.key })
        assertEquals(false, store.state.value.saved.loading)
        assertEquals(listOf("server-a", "server-b"), saved.loadedServers)
        store.clear()
        storeScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun savedNeverOpenedIsNotLoadedByASwitch() = runBlocking<Unit> {
        val auth = SwitchingAuthStore(authSessionFixture(servers = twoServers, selectedServerId = "server-a"))
        val saved = ServerTaggedSavedRepository()
        val storeScope = CoroutineScope(coroutineContext + Job())
        val store =
            SharedHomeSearchSavedStore(
                authStore = auth,
                savedRepository = saved,
                searchRepository = NoSearchRepository,
                scope = storeScope,
            )
        settle()

        auth.switchServer("server-b")
        settle()

        assertEquals(emptyList(), saved.loadedServers)
        assertEquals(emptyList(), store.state.value.saved.items)
        store.clear()
        storeScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun computersListReloadsForTheNewServerAfterASwitch() = runBlocking<Unit> {
        val auth = SwitchingAuthStore(authSessionFixture(servers = twoServers, selectedServerId = "server-a"))
        val computers = ServerTaggedComputersSource()
        val storeScope = CoroutineScope(coroutineContext + Job())
        val store =
            SharedSettingsStore(
                dataSource = SharedSettingsDataSource(sessionStore = auth, computersSource = computers),
                scope = storeScope,
                standaloneNavigation = true,
            )
        settle()
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        assertEquals(listOf("server-a-mac"), computerNames(store))

        auth.switchServer("server-b")
        settle()

        assertEquals(listOf("server-b-mac"), computerNames(store))
        assertEquals(listOf("server-a", "server-b"), computers.loadedServers)
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.Computers), store.state.value.route)
        store.clear()
        storeScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun accountOnlySettingsPageIsNotReloadedByASwitch() = runBlocking<Unit> {
        val auth = SwitchingAuthStore(authSessionFixture(servers = twoServers, selectedServerId = "server-a"))
        val computers = ServerTaggedComputersSource()
        val storeScope = CoroutineScope(coroutineContext + Job())
        val store =
            SharedSettingsStore(
                dataSource = SharedSettingsDataSource(sessionStore = auth, computersSource = computers),
                scope = storeScope,
                standaloneNavigation = true,
            )
        settle()
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.OpenSourceLicenses))

        auth.switchServer("server-b")
        settle()

        assertEquals(emptyList(), computers.loadedServers)
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.OpenSourceLicenses), store.state.value.route)
        store.clear()
        storeScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun serverScopeFlowFollowsSelectionAndIgnoresTokenRotation() = runBlocking<Unit> {
        val auth = SwitchingAuthStore(authSessionFixture(servers = twoServers, selectedServerId = "server-a"))
        val collected = mutableListOf<RaftServerScopeKey?>()
        val job = launch { auth.raftServerScopeFlow().take(2).toList(collected) }
        settle()
        auth.rotateTokens()
        settle()
        auth.switchServer("server-b")
        settle()
        job.join()

        assertEquals(
            listOf<RaftServerScopeKey?>(RaftServerScopeKey("user-1", "server-a"), RaftServerScopeKey("user-1", "server-b")),
            collected,
        )
    }

    private fun computerNames(store: SharedSettingsStore): List<String> =
        assertIs<ScreenLoadState.Content<SettingsComputersSnapshot>>(store.state.value.computers).data.computers.map { it.name }

    private suspend fun settle() {
        repeat(30) { yield() }
    }

    private val twoServers =
        listOf(
            StoredAuthServer(id = "server-a", name = "Botiverse", slug = "a"),
            StoredAuthServer(id = "server-b", name = "Raft", slug = "b"),
        )

    /** Mirrors the shell auth store: a server selection emits [AuthSessionEvent.BindingMetadataChanged]. */
    private class SwitchingAuthStore(
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

        suspend fun rotateTokens() {
            events.emit(AuthSessionEvent.TokensRotated(++generation))
        }
    }

    private class ServerTaggedSavedRepository : SavedRepository {
        val loadedServers = mutableListOf<String>()

        override suspend fun loadInitial(serverId: ServerId, limit: Int): DataResult<SavedSnapshot> {
            loadedServers += serverId.value
            return DataResult.Content(
                SavedSnapshot(
                    server = ServerSummary(serverId, serverId.value),
                    items = listOf(searchHitRow("${serverId.value}:saved")),
                )
            )
        }

        override suspend fun loadMore(serverId: ServerId, offset: Int, limit: Int): DataResult<SavedSnapshot> =
            DataResult.Empty(EmptyReason.NoInboxItems)

        override suspend fun saveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> =
            DataResult.Content(SavedMessageMutationResult(messageId))

        override suspend fun unsaveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> =
            DataResult.Content(SavedMessageMutationResult(messageId))
    }

    private object NoSearchRepository : SearchRepository {
        override suspend fun search(
            serverId: ServerId,
            query: String,
            offset: Int,
            limit: Int,
            options: SearchQueryOptions,
            cursor: String?
        ): DataResult<SearchSnapshot> = DataResult.Empty(EmptyReason.NoInboxItems)
    }

    private class ServerTaggedComputersSource : SharedSettingsComputersSource {
        val loadedServers = mutableListOf<String>()

        override suspend fun loadSettingsComputersForServer(
            serverId: String,
            force: Boolean
        ): DataResult<SettingsComputersSnapshot> {
            loadedServers += serverId
            return DataResult.Content(
                SettingsComputersSnapshot(
                    computers = listOf(SettingsComputerSnapshot(id = "$serverId-m", name = "$serverId-mac", daemonVersion = "0.51.1", online = true))
                )
            )
        }

        override suspend fun loadSettingsComputerSnapshotForServer(
            serverId: String,
            machineId: String,
            force: Boolean
        ): DataResult<SettingsComputerSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable)
    }
}
