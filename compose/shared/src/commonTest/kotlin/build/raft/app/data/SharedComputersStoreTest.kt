package build.raft.app.data

import build.raft.app.SharedCreateAgentMachineOption
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

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
                            runtimes = listOf("claude", "codex")
                        )
                    ),
                status = SharedComputersLoadStatus.Loaded
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

    private fun store(api: RecordingComputersApi): SharedComputersStore =
        SharedComputersStore(
            computersApiClient = SlockComputersApiClient(api),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )

    private class RecordingComputersApi : SlockComputersApi {
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
}

private const val machinesBody: String =
    """{"machines":[{"id":"machine-1","name":"Maria Laptop","status":"online","statusVersion":7,"apiKeyPrefix":"sk_machine_abc","runtimes":["claude","codex"],"daemonVersion":"0.53.0","hostname":"maria.local","os":"darwin","lastHeartbeat":"2026-06-14T02:00:00.000Z","createdAt":"2026-05-01T00:00:00.000Z"}],"latestDaemonVersion":"0.53.0"}"""
