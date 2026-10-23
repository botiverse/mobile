package build.raft.app.data

import build.raft.app.SharedCreateAgentMachineOption
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedComputersStoreTest {
    @Test
    fun ensureComputerPickerLoadsMachinesOncePerServer() = runBlocking {
        val api = RecordingComputersApi()
        val store = store(api)

        store.ensureComputerPickerForServer("server-1")
        store.ensureComputerPickerForServer("server-1")

        assertEquals(1, api.calls.count { it == "GET servers/server-1/machines" })
        assertEquals(
            SharedComputersPickerSlice(
                machines =
                    listOf(
                        SharedCreateAgentMachineOption(
                            id = "machine-1",
                            name = "Maria Laptop",
                            status = "online",
                            runtimes = listOf("claude", "codex"),
                            usageFactsKnown = true
                        )
                ),
                status = SharedComputersLoadStatus.Loaded,
                hasSnapshot = true
            ),
            store.state.value.selectComputerPickerSlice("server-1")
        )
    }

    @Test
    fun failedComputerPickerLoadRetries() = runBlocking {
        val api = RecordingComputersApi()
        api.respond = { call ->
            if (call == 1) {
                HomeNetworkResponse(code = 500, bodyText = """{"error":"boom"}""")
            } else {
                HomeNetworkResponse(code = 200, bodyText = machinesBody)
            }
        }
        val store = store(api)

        store.ensureComputerPickerForServer("server-1")
        assertEquals(SharedComputersLoadStatus.Error, store.state.value.selectComputerPickerSlice("server-1").status)

        store.ensureComputerPickerForServer("server-1")

        assertEquals(2, api.calls.count { it == "GET servers/server-1/machines" })
        assertEquals(SharedComputersLoadStatus.Loaded, store.state.value.selectComputerPickerSlice("server-1").status)
    }

    @Test
    fun selectorKeepsServerScopedSlicesSeparate() = runBlocking {
        val api = RecordingComputersApi()
        val store = store(api)

        store.ensureComputerPickerForServer("server-1")

        assertEquals(SharedComputersLoadStatus.Loaded, store.state.value.selectComputerPickerSlice("server-1").status)
        assertEquals(SharedComputersLoadStatus.Idle, store.state.value.selectComputerPickerSlice("server-2").status)
    }

    @Test
    fun settingsComputerSlicesProjectFromSharedMachineSnapshot() = runBlocking {
        val api = RecordingComputersApi()
        val store = store(api)

        val computers = store.loadSettingsComputersForServer("server-1")
        val detailComputer = store.loadSettingsComputerSnapshotForServer("server-1", "machine-1")

        assertEquals(1, api.calls.count { it == "GET servers/server-1/machines" })
        val snapshot = (computers as DataResult.Content).data
        assertEquals("0.53.0", snapshot.latestDaemonVersion)
        assertEquals("machine-1", snapshot.computers.single().id)
        assertEquals("Maria Laptop", snapshot.computers.single().name)
        assertEquals(true, snapshot.computers.single().online)
        assertEquals("darwin", snapshot.computers.single().platform)
        assertEquals("maria.local", snapshot.computers.single().hostname)
        assertEquals("sk_machine_abc", snapshot.computers.single().apiKeyPrefix)
        assertEquals(7L, snapshot.computers.single().statusVersion)

        val computer = (detailComputer as DataResult.Content).data
        assertEquals(snapshot.computers.single(), computer)
    }

    @Test
    fun computerListsPutTheComputersIAttachedFirst() {
        // artin task #53 (web orderSidebarMachineIds): mine first, then the rest, server order kept.
        fun machine(id: String, mine: Boolean) =
            ComputerSummary(
                id = id, name = id, status = ComputerStatus.Online, statusVersion = 0L, apiKeyPrefix = null,
                runtimes = emptyList(), daemonVersion = null, computerVersion = null, isComputer = true,
                hostname = null, os = null, description = null, lastHeartbeat = null, createdAt = null,
                attachedByCurrentUser = mine,
            )
        val ordered = listOf(machine("a", false), machine("b", true), machine("c", false), machine("d", true)).inComputerListOrder()
        assertEquals(listOf("b", "d", "a", "c"), ordered.map { it.id })
    }

    @Test
    fun startupPrewarmUsesBackgroundPriority() = runBlocking {
        val api = ControlledComputersApi()
        api.enqueue(HomeNetworkResponse(code = 200, bodyText = machinesBody))
        val store = controlledStore(api, this@runBlocking)

        store.ensureComputerPickerForServer(
            serverId = "server-1",
            priority = DataRequestPriority.Background
        )

        assertEquals(listOf(DataRequestPriority.Background), api.priorities)
        assertTrue(store.state.value.selectComputerPickerSlice("server-1").hasSnapshot)
    }

    @Test
    fun repeatedMembersAndDialogIntentsJoinOneInFlightRequest() = runBlocking {
        val api = ControlledComputersApi()
        val response = api.enqueuePending()
        val store = controlledStore(api, this@runBlocking)

        val membersLoad = async(start = CoroutineStart.UNDISPATCHED) {
            store.ensureComputerPickerForServer("server-1", force = true, priority = DataRequestPriority.Visible)
        }
        yield()
        val dialogEnsure = async(start = CoroutineStart.UNDISPATCHED) {
            store.ensureComputerPickerForServer("server-1", force = false, priority = DataRequestPriority.Visible)
        }
        val retryWhileLoading = async(start = CoroutineStart.UNDISPATCHED) {
            store.ensureComputerPickerForServer("server-1", force = true, priority = DataRequestPriority.Visible)
        }
        yield()

        assertEquals(1, api.priorities.size)
        assertTrue(store.state.value.selectComputerPickerSlice("server-1").initialLoadInFlight)

        response.complete(HomeNetworkResponse(code = 200, bodyText = machinesBody))
        membersLoad.await()
        dialogEnsure.await()
        retryWhileLoading.await()
        assertEquals(1, api.priorities.size)
    }

    @Test
    fun overlappingServerLoadsStayIsolatedWhenResponsesCompleteOutOfOrder() = runBlocking {
        val api = ControlledComputersApi()
        val responseA = api.enqueuePending()
        val responseB = api.enqueuePending()
        val store = controlledStore(api, this@runBlocking)

        val loadA = async(start = CoroutineStart.UNDISPATCHED) { store.ensureComputerPickerForServer("server-a") }
        yield()
        val loadB = async(start = CoroutineStart.UNDISPATCHED) { store.ensureComputerPickerForServer("server-b") }
        yield()

        responseB.complete(HomeNetworkResponse(code = 200, bodyText = machinesBodyFor("machine-b", "B Computer")))
        loadB.await()
        responseA.complete(HomeNetworkResponse(code = 200, bodyText = machinesBodyFor("machine-a", "A Computer")))
        loadA.await()

        assertEquals(listOf("server-a", "server-b"), api.serverIds)
        assertEquals(
            listOf("machine-a"),
            store.state.value.selectComputerPickerSlice("server-a").machines.map { it.id }
        )
        assertEquals(
            listOf("machine-b"),
            store.state.value.selectComputerPickerSlice("server-b").machines.map { it.id }
        )
    }

    @Test
    fun cachedSnapshotStaysVisibleWhileRefreshRunsAndFails() = runBlocking {
        val api = ControlledComputersApi()
        api.enqueue(HomeNetworkResponse(code = 200, bodyText = machinesBody))
        val refresh = api.enqueuePending()
        val store = controlledStore(api, this@runBlocking)
        store.ensureComputerPickerForServer("server-1")

        val refreshing = async(start = CoroutineStart.UNDISPATCHED) {
            store.ensureComputerPickerForServer("server-1", force = true)
        }
        yield()

        val warm = store.state.value.selectComputerPickerSlice("server-1")
        assertTrue(warm.hasSnapshot)
        assertTrue(warm.refreshing)
        assertFalse(warm.initialLoadInFlight)
        assertEquals("machine-1", warm.machines.single().id)

        refresh.complete(HomeNetworkResponse(code = 503, bodyText = """{"error":"temporarily unavailable"}"""))
        refreshing.await()

        val failedRefresh = store.state.value.selectComputerPickerSlice("server-1")
        assertEquals(SharedComputersLoadStatus.Loaded, failedRefresh.status)
        assertTrue(failedRefresh.hasSnapshot)
        assertFalse(failedRefresh.refreshing)
        assertNull(failedRefresh.initialLoadError)
        assertEquals("machine-1", failedRefresh.machines.single().id)
        assertIs<DataResult.Content<SettingsComputersSnapshot>>(
            store.loadSettingsComputersForServer("server-1")
        )
        Unit
    }

    @Test
    fun emptyCacheFailureExposesRetryableErrorAndRetryRecovers() = runBlocking {
        val api = ControlledComputersApi()
        api.enqueue(HomeNetworkResponse(code = 500, bodyText = """{"error":"boom"}"""))
        api.enqueue(HomeNetworkResponse(code = 200, bodyText = machinesBody))
        val store = controlledStore(api, this@runBlocking)

        store.ensureComputerPickerForServer("server-1")
        val failed = store.state.value.selectComputerPickerSlice("server-1")
        assertFalse(failed.hasSnapshot)
        assertEquals(SharedComputersLoadStatus.Error, failed.status)
        assertEquals(failed.error, failed.initialLoadError)
        assertTrue(failed.error?.retryable == true)

        store.ensureComputerPickerForServer("server-1", force = true)
        val recovered = store.state.value.selectComputerPickerSlice("server-1")
        assertTrue(recovered.hasSnapshot)
        assertEquals(SharedComputersLoadStatus.Loaded, recovered.status)
        assertEquals("machine-1", recovered.machines.single().id)
        assertEquals(2, api.priorities.size)
    }

    @Test
    fun silentTokenRefreshKeepsTheComputerListAndANewLoginClearsIt() = runBlocking {
        // Task #147 (HuaTuo 17e2cca3): a refresh wiped this list mid-dialog, the create dialog lost its
        // selected computer for a moment, reloaded its v2 form and dropped a typed API key.
        val api = RecordingComputersApi()
        val generation = SharedAuthLoginGeneration()
        var session =
            StoredAuthSession(
                user = StoredAuthUser(id = "account-1", email = "user@raft.build"),
                accessToken = "access-1",
                refreshToken = "refresh-1"
            )
        val store =
            SharedComputersStore(
                computersApiClient = RaftComputersApiClient(api),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                ownerProvider = { sharedComputersOwner(session, "https://api.example.test/", generation.current()) }
            )
        store.ensureComputerPickerForServer("server-1")
        val owner = store.state.value.owner

        session = session.copy(accessToken = "access-2", refreshToken = "refresh-2")
        generation.onAuthSessionEvent(AuthSessionEvent.TokensRotated(generation = 7))
        store.ensureComputerPickerForServer("server-1")

        assertEquals(owner, store.state.value.owner)
        assertEquals(listOf("machine-1"), store.state.value.selectComputerPickerSlice("server-1").machines.map { it.id })
        assertEquals(1, api.callCount, "the list is neither wiped nor fetched again")

        generation.onAuthSessionEvent(AuthSessionEvent.Authenticated(generation = 8))
        generation.onAuthSessionEvent(AuthSessionEvent.Authenticated(generation = 8))
        store.reconcileOwner()
        assertTrue(store.state.value.owner != owner, "a new login is a new owner")
        assertFalse(store.state.value.selectComputerPickerSlice("server-1").hasSnapshot)
        assertEquals(
            sharedComputersOwner(session, "https://api.example.test", "8"),
            store.state.value.owner,
            "the same event seen by two collectors moves the generation once"
        )
    }

    @Test
    fun accountSwitchClearsSnapshotsAndRejectsOldCompletion() = runBlocking {
        val ownerA = SharedComputersOwner("environment", "account-a", "session-a")
        val ownerB = SharedComputersOwner("environment", "account-b", "session-b")
        var owner = ownerA
        val api = ControlledComputersApi()
        val responseA = api.enqueuePending()
        val responseB = api.enqueuePending()
        val store =
            SharedComputersStore(
                computersApiClient = RaftComputersApiClient(api),
                scope = this@runBlocking,
                ownerProvider = { owner }
            )

        val loadA = async(start = CoroutineStart.UNDISPATCHED) {
            store.ensureComputerPickerForServer("shared-server")
        }
        yield()
        owner = ownerB
        store.reconcileOwner()
        assertEquals(ownerB, store.state.value.owner)
        assertFalse(store.state.value.selectComputerPickerSlice("shared-server").hasSnapshot)

        val loadB = async(start = CoroutineStart.UNDISPATCHED) {
            store.ensureComputerPickerForServer("shared-server")
        }
        yield()
        responseB.complete(HomeNetworkResponse(code = 200, bodyText = machinesBodyFor("machine-b", "B Computer")))
        loadB.await()

        responseA.complete(HomeNetworkResponse(code = 200, bodyText = machinesBodyFor("machine-a", "A Computer")))
        loadA.await()

        val slice = store.state.value.selectComputerPickerSlice("shared-server")
        assertEquals(ownerB, store.state.value.owner)
        assertEquals(listOf("machine-b"), slice.machines.map { it.id })
    }

    @Test
    fun ownerChangeAtPublicationRejectsResponseEvenBeforeExplicitReconcile() = runBlocking {
        val ownerA = SharedComputersOwner("environment", "account-a", "session-a")
        val ownerB = SharedComputersOwner("environment", "account-b", "session-b")
        var owner = ownerA
        val api = ControlledComputersApi()
        val responseA = api.enqueuePending()
        val store =
            SharedComputersStore(
                computersApiClient = RaftComputersApiClient(api),
                scope = this@runBlocking,
                ownerProvider = { owner }
            )

        val loadA = async(start = CoroutineStart.UNDISPATCHED) {
            store.ensureComputerPickerForServer("shared-server")
        }
        yield()
        owner = ownerB
        responseA.complete(HomeNetworkResponse(code = 200, bodyText = machinesBodyFor("machine-a", "A Computer")))
        loadA.await()

        assertEquals(ownerB, store.state.value.owner)
        assertFalse(store.state.value.selectComputerPickerSlice("shared-server").hasSnapshot)
    }

    private fun store(api: RecordingComputersApi): SharedComputersStore =
        SharedComputersStore(
            computersApiClient = RaftComputersApiClient(api),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )

    private fun controlledStore(
        api: ControlledComputersApi,
        scope: CoroutineScope
    ): SharedComputersStore =
        SharedComputersStore(
            computersApiClient = RaftComputersApiClient(api),
            scope = scope
        )

    private class RecordingComputersApi : RaftComputersApi {
        val calls: MutableList<String> = mutableListOf()
        var callCount: Int = 0
        var respond: (Int) -> HomeNetworkResponse = { HomeNetworkResponse(code = 200, bodyText = machinesBody) }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse {
            callCount += 1
            calls += "GET servers/${serverId.value}/machines"
            return respond(callCount)
        }

        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unused")
    }

    private class ControlledComputersApi : RaftComputersApi {
        val serverIds = mutableListOf<String>()
        val priorities = mutableListOf<DataRequestPriority>()
        private val responses = ArrayDeque<CompletableDeferred<HomeNetworkResponse>>()

        fun enqueue(response: HomeNetworkResponse) {
            responses += CompletableDeferred(response)
        }

        fun enqueuePending(): CompletableDeferred<HomeNetworkResponse> =
            CompletableDeferred<HomeNetworkResponse>().also { responses += it }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse =
            machines(serverId, DataRequestPriority.Visible)

        override suspend fun machines(
            serverId: ServerId,
            priority: DataRequestPriority
        ): HomeNetworkResponse {
            serverIds += serverId.value
            priorities += priority
            return responses.removeFirst().await()
        }

        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unused")
    }
}

private const val machinesBody: String =
    """{"machines":[{"id":"machine-1","name":"Maria Laptop","status":"online","statusVersion":7,"apiKeyPrefix":"sk_machine_abc","runtimes":["claude","codex"],"daemonVersion":"0.53.0","hostname":"maria.local","os":"darwin","lastHeartbeat":"2026-06-14T02:00:00.000Z","createdAt":"2026-05-01T00:00:00.000Z"}],"latestDaemonVersion":"0.53.0"}"""

private fun machinesBodyFor(machineId: String, machineName: String): String =
    """{"machines":[{"id":"$machineId","name":"$machineName","status":"online","runtimes":["codex"]}]}"""
