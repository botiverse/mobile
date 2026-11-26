package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
    fun computerListsPutMyComputersFirstAttachedOrOwned() {
        // artin task #53 and #triage:#332 (web orderSidebarMachineIds / isMyComputer): mine (attached
        // or owned) first, then the rest, server order kept; a Public Computer (no owner) is nobody's.
        fun machine(id: String, attached: Boolean = false, owner: String? = null) =
            ComputerSummary(
                id = id, name = id, status = ComputerStatus.Online, statusVersion = 0L, apiKeyPrefix = null,
                runtimes = emptyList(), daemonVersion = null, computerVersion = null, isComputer = true,
                hostname = null, os = null, description = null, lastHeartbeat = null, createdAt = null,
                attachedByCurrentUser = attached,
                ownerUserId = owner,
            )
        val machines =
            listOf(
                machine("a"),
                machine("b", attached = true),
                machine("public"),
                machine("owned", owner = "me"),
                machine("theirs", owner = "someone"),
                machine("d", attached = true, owner = "someone"),
            )
        val cases =
            listOf(
                "me" to listOf("b", "owned", "d", "a", "public", "theirs"),
                "someone" to listOf("b", "theirs", "d", "a", "public", "owned"),
                null to listOf("b", "d", "a", "public", "owned", "theirs"),
            )
        cases.forEach { (userId, expected) -> assertEquals(expected, machines.inComputerListOrder(userId).map { it.id }, "as $userId") }
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
    fun syncedMachineRowsPatchTheLoadedListWithoutAFetch() = runBlocking {
        val api = RecordingComputersApi()
        val store = store(api)
        store.ensureComputerPickerForServer("server-1")
        val renamed = parseSyncedComputerRow(syncMachineRow("machine-1", "Maria Desktop", "offline"))!!
        val added = parseSyncedComputerRow(syncMachineRow("machine-2", "Build Box", "online"))!!

        assertTrue(store.applySyncedMachineRows("server-1", listOf(renamed, added), deletes = emptySet()))

        val slice = store.state.value.selectComputerServerSlice("server-1")
        assertEquals(listOf("machine-1" to "Maria Desktop", "machine-2" to "Build Box"), slice.machines.map { it.id to it.name })
        assertEquals(2, slice.machineCount)
        assertEquals(1, slice.onlineCount)
        assertEquals(mapOf("machine-1" to "Maria Desktop", "machine-2" to "Build Box"), store.currentMachineNames("server-1"))

        assertTrue(store.applySyncedMachineRows("server-1", emptyList(), deletes = setOf("machine-1")))
        assertEquals(listOf("machine-2"), store.state.value.selectComputerServerSlice("server-1").machines.map { it.id })
        // The same delete again changes nothing.
        assertFalse(store.applySyncedMachineRows("server-1", emptyList(), deletes = setOf("machine-1")))
        assertEquals(1, api.calls.size)
    }

    @Test
    fun aSyncedMachineRowKeepsTheListsLoginSteps() = runBlocking {
        // A sync `machine` row never carries `attention` (the list computes it per viewer, slock
        // routes/servers.ts): the usage cells keep the list's step until the list is read again.
        val api = RecordingComputersApi()
        api.respond = {
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"machines":[{"id":"machine-1","name":"Maria Laptop","status":"online","runtimes":["codex"],""" +
                        """"attention":{"count":1,"items":[{"code":"signed_out","resource":{"kind":"login","key":"shared:codex"},""" +
                        """"actor":"computer_owner","action":"sign_in","agentIds":["a1"],"agentCount":1}],"waitingAgents":0}}]}"""
            )
        }
        val store = store(api)
        store.ensureComputerPickerForServer("server-1")
        val renamed = parseSyncedComputerRow(syncMachineRow("machine-1", "Maria Desktop", "online"))!!
        assertNull(renamed.loginSteps)
        assertNull(renamed.serverNeedsAttention)

        assertTrue(store.applySyncedMachineRows("server-1", listOf(renamed), deletes = emptySet()))

        val machine = store.state.value.selectComputerServerSlice("server-1").machines.single()
        assertEquals("Maria Desktop", machine.name)
        assertEquals(listOf(ComputerLoginStep("codex", ComputerLoginStepAction.SignIn)), machine.loginSteps)
        assertEquals(true, machine.serverNeedsAttention)
    }

    @Test
    fun syncedMachineRowsCarryAndClearRevocationAndVersionRefusedAt() = runBlocking {
        val api = RecordingComputersApi()
        val store = store(api)
        store.ensureComputerPickerForServer("server-1")

        val revokedRow =
            parseSyncedComputerRow(
                kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"id":"machine-1","name":"Maria Laptop","status":"offline","isComputer":false,"versionRefusedAt":"2026-11-24T10:00:00.000Z","refusedComputerVersion":"1.0.40","revocation":{"at":"2026-11-20T12:00:00.000Z","via":"key_revoked_manually","byUserName":"Alice","subjectUserName":null,"departureReason":null}}"""
                ) as kotlinx.serialization.json.JsonObject
            )!!
        assertTrue(store.applySyncedMachineRows("server-1", listOf(revokedRow), deletes = emptySet()))
        val afterRevoke = store.state.value.selectComputerServerSlice("server-1").machines.single()
        assertEquals(SettingsComputerRevokedVia.KeyRevokedManually, afterRevoke.revocation?.via)
        assertEquals("Alice", afterRevoke.revocation?.byUserName)
        assertEquals("2026-11-24T10:00:00.000Z", afterRevoke.versionRefusedAt)
        assertEquals("1.0.40", afterRevoke.refusedComputerVersion)

        // Reattaching sends a sync row with isComputer=true and revocation=null: revocation clears.
        val reattachedRow =
            parseSyncedComputerRow(
                kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"id":"machine-1","name":"Maria Laptop","status":"online","isComputer":true,"computerVersion":"1.0.53","revocation":null,"versionRefusedAt":null,"refusedComputerVersion":null}"""
                ) as kotlinx.serialization.json.JsonObject
            )!!
        assertTrue(store.applySyncedMachineRows("server-1", listOf(reattachedRow), deletes = emptySet()))
        val afterReattach = store.state.value.selectComputerServerSlice("server-1").machines.single()
        assertNull(afterReattach.revocation)
        assertNull(afterReattach.versionRefusedAt)
        assertNull(afterReattach.refusedComputerVersion)
        assertTrue(afterReattach.isComputer)
    }

    @Test
    fun aSyncReloadDoesNotJoinALoadReadBeforeTheChangeAndTheOlderAnswerIsDropped() = runBlocking {
        val api = ControlledComputersApi()
        api.enqueue(HomeNetworkResponse(code = 200, bodyText = machinesBody))
        val store = controlledStore(api, this@runBlocking)
        store.ensureComputerPickerForServer("server-1")
        // R0: a forced load read before the change; R1: the reload the change asks for.
        val olderResponse = api.enqueuePending()
        val newerResponse = api.enqueuePending()
        val older = async(start = CoroutineStart.UNDISPATCHED) {
            store.ensureComputerPickerForServer("server-1", force = true, priority = DataRequestPriority.Visible)
        }
        yield()
        val newer = async(start = CoroutineStart.UNDISPATCHED) { store.reloadMachinesForSync("server-1") }
        yield()
        assertEquals(3, api.serverIds.size, "the sync reload reads the server again instead of joining")

        newerResponse.complete(HomeNetworkResponse(code = 200, bodyText = machinesBodyFor("machine-1", "Renamed")))
        newer.await()
        olderResponse.complete(HomeNetworkResponse(code = 200, bodyText = machinesBodyFor("machine-1", "Stale")))
        older.await()

        val slice = store.state.value.selectComputerServerSlice("server-1")
        assertEquals(listOf("Renamed"), slice.machines.map { it.name })
        assertFalse(slice.refreshing)
    }

    @Test
    fun supersededSettingsLoadAwaitsReplacementInsteadOfReturningStaleOfflineSnapshot() = runBlocking {
        // #wg-mobile-feedback:#273: when an older forced load finishes FIRST after being superseded
        // by a concurrent reloadMachinesForSyncSucceeded, it must await the replacement load instead
        // of returning the stale pre-background Offline slice from _state.
        val api = ControlledComputersApi()
        api.enqueue(HomeNetworkResponse(code = 200, bodyText = machinesBodyWithStatus("machine-1", "Mac Studio", "offline")))
        val store = controlledStore(api, this@runBlocking)
        store.ensureComputerPickerForServer("server-1")
        assertEquals(ComputerStatus.Offline, store.state.value.selectComputerServerSlice("server-1").machines.single().status)

        val olderResponse = api.enqueuePending()
        val replacementResponse = api.enqueuePending()
        val olderLoad = async(start = CoroutineStart.UNDISPATCHED) {
            store.loadSettingsComputersForServer("server-1", force = true)
        }
        yield()
        val syncReload = async(start = CoroutineStart.UNDISPATCHED) {
            store.reloadMachinesForSyncSucceeded("server-1")
        }
        yield()
        assertEquals(3, api.serverIds.size)

        // Older load finishes FIRST and is dropped as stale_generation.
        olderResponse.complete(HomeNetworkResponse(code = 200, bodyText = machinesBodyWithStatus("machine-1", "Mac Studio", "offline")))
        yield()
        assertFalse(olderLoad.isCompleted, "superseded load must wait for the replacement flight")

        // Replacement finishes with Online: both callers must observe Online.
        replacementResponse.complete(HomeNetworkResponse(code = 200, bodyText = machinesBodyWithStatus("machine-1", "Mac Studio", "online")))
        assertTrue(syncReload.await())
        val olderSnapshot = assertIs<DataResult.Content<SettingsComputersSnapshot>>(olderLoad.await()).data
        assertTrue(olderSnapshot.computers.single().online, "older caller must see replacement Online state")
    }

    @Test
    fun observerCountTracksActiveCollectorsAndCleansUpOnCancel() = runBlocking {
        val api = RecordingComputersApi()
        val store = store(api)
        assertFalse(store.hasActiveObservers("server-1"))
        assertFalse(store.hasLoadedOrObservedServer("server-1"))

        val firstEmission = CompletableDeferred<SettingsComputersLiveUpdate>()
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                store.observeSettingsComputersForServer("server-1")!!.collect { update ->
                    if (!firstEmission.isCompleted) firstEmission.complete(update)
                }
            }
        firstEmission.await()
        assertTrue(store.hasActiveObservers("server-1"))
        assertTrue(store.hasLoadedOrObservedServer("server-1"))
        assertEquals(setOf("server-1"), store.loadedOrObservedServerIds())

        job.cancel()
        job.join()
        assertFalse(store.hasActiveObservers("server-1"))
        assertFalse(store.hasLoadedOrObservedServer("server-1"))
    }

    @Test
    fun supersededReloadReturnsWinningGenerationsOutcomeWhenReplacementFinishesFirst() = runBlocking {
        val api = ControlledComputersApi()
        api.enqueue(HomeNetworkResponse(code = 200, bodyText = machinesBodyWithStatus("machine-1", "Mac Studio", "offline")))
        val store = controlledStore(api, this@runBlocking)
        store.ensureComputerPickerForServer("server-1")

        val olderResponse = api.enqueuePending()
        val replacementResponse = api.enqueuePending()
        val olderReload = async(start = CoroutineStart.UNDISPATCHED) {
            store.reloadMachinesForSyncSucceeded("server-1")
        }
        yield()
        val replacementReload = async(start = CoroutineStart.UNDISPATCHED) {
            store.reloadMachinesForSyncSucceeded("server-1")
        }
        yield()

        // Replacement finishes FIRST with 503 failure; older flight finishes SECOND with dropped 200.
        replacementResponse.complete(HomeNetworkResponse(code = 503, bodyText = """{"error":"down"}"""))
        assertFalse(replacementReload.await())

        olderResponse.complete(HomeNetworkResponse(code = 200, bodyText = machinesBodyWithStatus("machine-1", "Mac Studio", "online")))
        assertFalse(olderReload.await(), "dropped flight must report the winning generation's failed outcome, not its own dropped 200")
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
    machinesBodyWithStatus(machineId, machineName, "online")

private fun machinesBodyWithStatus(machineId: String, machineName: String, status: String): String =
    """{"machines":[{"id":"$machineId","name":"$machineName","status":"$status","runtimes":["codex"]}]}"""

/** One `machines` item of `GET /api/servers/:id/machines` as a sync `machine` row carries it. */
private fun syncMachineRow(id: String, name: String, status: String): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.Json.parseToJsonElement(
        """{"id":"$id","name":"$name","status":"$status","runtimes":["codex"],"agentCount":1}"""
    ) as kotlinx.serialization.json.JsonObject
