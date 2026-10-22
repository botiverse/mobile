package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Data-layer plan step 5 (computers): machine lists are cached per environment + account + server,
 * seeded from the durable store on a cold start, fenced only by account boundaries, evicted on
 * lost access and capped (Tenny rules 1-3); the Computers page paints the cache and refreshes once.
 */
class SharedComputersCacheFirstTest {
    @Test
    fun switchingServersAToBToAPaintsCachedListOnFirstFrameAndRefreshesOnceInBackground() = runTest {
        val harness = Harness(this)
        val store = harness.store()
        store.ensureComputerPickerForServer(SERVER_A)
        store.ensureComputerPickerForServer(SERVER_B)
        assertEquals(1, harness.api.calls(SERVER_A))

        // Back on A: the first frame reads the store's current value — rows, no spinner.
        val firstFrame = assertNotNull(store.currentSettingsComputers(SERVER_A))
        assertEquals(listOf("Studio A"), firstFrame.computers.map { it.name })

        // The page open paints the cache at once, then validates it with exactly one fetch.
        harness.session = harness.session.copy(selectedServerId = SERVER_A)
        harness.api.names[SERVER_A] = "Studio A2"
        val gate = CompletableDeferred<Unit>()
        harness.api.gates[SERVER_A] = gate
        val page = harness.settingsStore(store)
        page.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        runCurrent()
        assertEquals(listOf("Studio A"), listNames(page))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("Studio A2"), listNames(page))
        assertEquals(2, harness.api.calls(SERVER_A), "exactly one background fetch after returning to A")
    }

    @Test
    fun coldStartSeedsFromDurableStoreThenRefreshesOnce() = runTest {
        val storage = MapRowStorage()
        val first = Harness(this, storage)
        first.store().ensureComputerPickerForServer(SERVER_A)
        runCurrent()

        val second = Harness(this, storage)
        val store = second.store()
        val cached = assertIs<DataResult.Content<SettingsComputersSnapshot>>(store.cachedSettingsComputersForServer(SERVER_A))
        assertEquals(listOf("Studio A"), cached.data.computers.map { it.name })
        assertEquals("sk_a", cached.data.computers.single().apiKeyPrefix)
        assertEquals(
            SettingsComputerCreatorSnapshot(id = "user-1", name = "artin", displayName = "Artin", avatarUrl = null, gravatarHash = "abc123"),
            cached.data.computers.single().creator,
            "the creator survives a cold start",
        )
        assertEquals(0, second.api.calls(SERVER_A), "the cold-start paint needs no network")
        assertTrue(store.state.value.selectComputerServerSlice(SERVER_A).seededFromDurable)
        assertEquals(SharedComputersLoadStatus.Loaded, store.state.value.selectComputerServerSlice(SERVER_A).status)

        // The seeded list is validated exactly once, even by a non-forced ensure.
        store.ensureComputerPickerForServer(SERVER_A)
        store.ensureComputerPickerForServer(SERVER_A)
        assertEquals(1, second.api.calls(SERVER_A))
        assertFalse(store.state.value.selectComputerServerSlice(SERVER_A).seededFromDurable)

        // Another account never reads it.
        val other = Harness(this, storage, accountId = "user-2")
        assertNull(other.store().cachedSettingsComputersForServer(SERVER_A))
    }

    @Test
    fun lateResponseAfterServerSwitchIsKeptButAfterAccountChangeIsDropped() = runTest {
        val harness = Harness(this)
        val store = harness.store()
        val gate = CompletableDeferred<Unit>()
        harness.api.gates[SERVER_A] = gate
        val pending = async { store.ensureComputerPickerForServer(SERVER_A) }
        runCurrent()
        store.ensureComputerPickerForServer(SERVER_B) // the user switched to B meanwhile
        gate.complete(Unit)
        pending.await()
        assertEquals(listOf("Studio A"), store.currentSettingsComputers(SERVER_A)?.computers?.map { it.name })
        assertNotNull(harness.durable.read(harness.key(SERVER_A)), "server A's late answer is A's data")

        // Account change while a request is in flight: neither memory nor disk takes it.
        val gate2 = CompletableDeferred<Unit>()
        harness.api.gates[SERVER_C] = gate2
        val pending2 = async { store.ensureComputerPickerForServer(SERVER_C) }
        runCurrent()
        harness.accountId = "user-2"
        harness.durable.clearAll() // sign-out of user-1
        gate2.complete(Unit)
        pending2.await()
        assertNull(store.currentSettingsComputers(SERVER_C))
        assertNull(harness.durable.read(harness.key(SERVER_C, accountId = "user-1")))
    }

    @Test
    fun lostAccessEvictsTheServerListFromMemoryAndDisk() = runTest {
        val harness = Harness(this)
        val store = harness.store()
        store.ensureComputerPickerForServer(SERVER_A)
        assertNotNull(harness.durable.read(harness.key(SERVER_A)))

        harness.api.codes[SERVER_A] = 403
        store.ensureComputerPickerForServer(SERVER_A, force = true)
        val slice = store.state.value.selectComputerServerSlice(SERVER_A)
        assertFalse(slice.hasSnapshot)
        assertEquals(SharedComputersLoadStatus.Error, slice.status)
        assertNull(harness.durable.read(harness.key(SERVER_A)))
        assertNull(store.cachedSettingsComputersForServer(SERVER_A))

        // A transient failure keeps the cached list.
        harness.api.codes.remove(SERVER_A)
        store.ensureComputerPickerForServer(SERVER_B)
        harness.api.codes[SERVER_B] = 503
        store.ensureComputerPickerForServer(SERVER_B, force = true)
        assertTrue(store.state.value.selectComputerServerSlice(SERVER_B).hasSnapshot)

        // Removed from the server: memory and disk drop it.
        store.dropServer(SERVER_B)
        assertNull(store.currentSettingsComputers(SERVER_B))
        assertNull(harness.durable.read(harness.key(SERVER_B)))
    }

    @Test
    fun pageShowsTheTerminalStateWhenValidationProvesLostAccess() = runTest {
        val harness = Harness(this)
        val store = harness.store()
        store.ensureComputerPickerForServer(SERVER_A)
        harness.session = harness.session.copy(selectedServerId = SERVER_A)
        harness.api.codes[SERVER_A] = 404
        val page = harness.settingsStore(store)
        page.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        runCurrent()
        assertIs<ScreenLoadState.Error>(page.state.value.computers)
    }

    @Test
    fun machinePageIsCachedPerServerAndForgottenWhenTheMachineIsGone() = runTest {
        val harness = Harness(this)
        val store = harness.store()
        store.ensureComputerPickerForServer(SERVER_A)
        val detail = SettingsComputerDetailSnapshot(store.currentSettingsComputers(SERVER_A)!!.computers.single(), emptyList(), emptyList(), emptyList())
        store.recordSettingsComputerDetail(SERVER_A, "m-$SERVER_A", detail)
        assertEquals(detail, store.cachedSettingsComputerDetail(SERVER_A, "m-$SERVER_A"))
        assertNull(store.cachedSettingsComputerDetail(SERVER_B, "m-$SERVER_A"))
        store.recordSettingsComputerDetail(SERVER_A, "m-$SERVER_A", null)
        assertNull(store.cachedSettingsComputerDetail(SERVER_A, "m-$SERVER_A"))
    }

    @Test
    fun memoryAndDurableCachesAreCapped() = runTest {
        val harness = Harness(this, maxServers = 2, maxRowsPerAccount = 2)
        val store = harness.store()
        store.ensureComputerPickerForServer(SERVER_A)
        store.ensureComputerPickerForServer(SERVER_B)
        store.ensureComputerPickerForServer(SERVER_C)
        assertEquals(setOf(SERVER_B, SERVER_C), store.state.value.serverSlices.keys)
        assertNull(harness.durable.read(harness.key(SERVER_A)), "least recently written row left")
        assertEquals(2, harness.durable.rowIdsForTest().size)
    }

    private fun listNames(store: SharedSettingsStore): List<String> =
        (store.state.value.computers as ScreenLoadState.Content).data.computers.map { it.name }

    private class Harness(
        private val testScope: TestScope,
        storage: RaftDurableRowStorage = MapRowStorage(),
        var accountId: String = "user-1",
        private val maxServers: Int = SharedComputersStore.DefaultMaxServers,
        maxRowsPerAccount: Int = 12,
    ) {
        val api = FakeMachinesApi()
        var session = settingsStoreSampleSession()
        val durable =
            RaftKeyedDurableSnapshotStore(
                storage = storage,
                serializer = RaftComputersDurableRecord.serializer(),
                label = "computers_test",
                maxRowsPerAccount = maxRowsPerAccount,
                scope = testScope.backgroundScope,
            )

        fun key(serverId: String, accountId: String = this.accountId) = RaftDurableSnapshotKey(ENVIRONMENT, accountId, serverId)

        fun store(): SharedComputersStore =
            SharedComputersStore(
                computersApiClient = RaftComputersApiClient(api),
                scope = testScope.backgroundScope,
                ownerProvider = { SharedComputersOwner(ENVIRONMENT, accountId, "login-1") },
                durableStore = durable,
                maxServers = maxServers,
            )

        fun settingsStore(computers: SharedComputersStore): SharedSettingsStore {
            val sessionStore = InMemoryRaftAuthSessionStore(session)
            return SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = sessionStore,
                        computersSource = computers,
                    ),
                scope = testScope.backgroundScope,
                standaloneNavigation = true,
            )
        }
    }

    private class FakeMachinesApi : RaftComputersApi {
        private val counts = mutableMapOf<String, Int>()
        val names = mutableMapOf<String, String>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val codes = mutableMapOf<String, Int>()

        fun calls(serverId: String): Int = counts[serverId] ?: 0

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse {
            val id = serverId.value
            counts[id] = calls(id) + 1
            gates.remove(id)?.await()
            codes[id]?.let { code -> return HomeNetworkResponse(code = code, bodyText = """{"error":"nope"}""") }
            val name = names[id] ?: "Studio ${id.removePrefix("server-").uppercase()}"
            val prefix = "sk_${id.removePrefix("server-")}"
            return HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"machines":[{"id":"m-$id","name":"$name","status":"online","statusVersion":1,"apiKeyPrefix":"$prefix","runtimes":["claude"],"daemonVersion":"0.53.0","hostname":"h.local","os":"darwin","creator":{"type":"human","id":"user-1","name":"artin","displayName":"Artin","avatarUrl":null,"gravatarHash":"abc123"}}],"latestDaemonVersion":"0.53.0"}""",
            )
        }

        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unused")
    }

    private companion object {
        const val ENVIRONMENT = "https://api.example"
        const val SERVER_A = "server-a"
        const val SERVER_B = "server-b"
        const val SERVER_C = "server-c"
    }
}

/** In-memory [RaftDurableRowStorage] for cache tests. */
internal class MapRowStorage : RaftDurableRowStorage {
    private val rows = linkedMapOf<String, String>()

    override suspend fun read(id: String): String? = rows[id]

    override suspend fun write(id: String, value: String) {
        rows[id] = value
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }

    override suspend fun ids(): List<String> = rows.keys.toList()
}
