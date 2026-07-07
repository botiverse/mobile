package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class ComputersApiTest {
    @Test
    fun typedComputersApiAcceptsCanonicalUpstreamShapes() = runBlocking {
        val transport = RecordingComputersApi()
        val client = SlockComputersApiClient(transport)
        val serverId = ServerId("server-1")

        val machines = assertIs<DataResult.Content<ComputerListSummary>>(client.machines(serverId)).data
        assertEquals(1, machines.machineCount)
        assertEquals("0.0.62", machines.latestComputerVersion)
        val firstMachine = machines.machines.single()
        assertEquals(ComputerStatus.Online, firstMachine.status)
        assertEquals(7L, firstMachine.statusVersion)
        assertEquals("sk_machine_abc", firstMachine.apiKeyPrefix)
        assertEquals(listOf("claude", "codex"), firstMachine.runtimes)
        assertEquals(true, firstMachine.isComputer)
        assertEquals("0.0.61", firstMachine.computerVersion)
        assertEquals("Runs production agents", firstMachine.description)
        assertEquals("2026-06-14T02:00:00.000Z", firstMachine.lastHeartbeat)
        assertEquals("2026-05-01T00:00:00.000Z", firstMachine.createdAt)

        assertEquals("machine-1", assertIs<DataResult.Content<ComputerRegistrationSummary>>(client.registerMachine(serverId, "Maria")).data.machine.id)
        assertEquals("Maria Laptop", assertIs<DataResult.Content<ComputerSummary>>(client.renameMachine(serverId, "machine-1", "Maria Laptop")).data.name)
        assertEquals(
            "Build host",
            assertIs<DataResult.Content<ComputerSummary>>(
                client.updateMachineDescription(serverId, "machine-1", "Build and release host")
            ).data.description
        )
        assertEquals(true, assertIs<DataResult.Content<ComputerCommandResult>>(client.deleteMachine(serverId, "machine-1")).data.ok)
        assertEquals("sk_machine_1", assertIs<DataResult.Content<ComputerApiKeyRotationSummary>>(client.rotateMachineKey(serverId, "machine-1")).data.apiKeyPrefix)
        assertEquals(2, assertIs<DataResult.Content<ComputerWorkspacePage>>(client.machineWorkspaces(serverId, "machine-1")).data.itemCount)
        val runtimeModels = assertIs<DataResult.Content<ComputerRuntimeModelsSummary>>(client.runtimeModels(serverId, "machine-1", "claude")).data
        assertEquals("sonnet", runtimeModels.defaultModel)
        assertEquals(listOf("sonnet", "opus"), runtimeModels.models.map { it.id })
        assertEquals(listOf("Claude Sonnet", "Claude Opus"), runtimeModels.models.map { it.label })
        assertEquals(listOf("launchable", null), runtimeModels.models.map { it.verified })
        assertEquals(true, assertIs<DataResult.Content<ComputerCommandResult>>(client.deleteMachineWorkspace(serverId, "machine-1", "agent-old")).data.ok)

        assertEquals(
            listOf(
                "GET servers/server-1/machines",
                """POST servers/server-1/machines body={"name":"Maria"}""",
                """PATCH servers/server-1/machines/machine-1 body={"name":"Maria Laptop"}""",
                """PATCH servers/server-1/machines/machine-1 body={"description":"Build and release host"}""",
                "DELETE servers/server-1/machines/machine-1",
                "POST servers/server-1/machines/machine-1/rotate-key",
                "GET servers/server-1/machines/machine-1/workspaces",
                "GET servers/server-1/machines/machine-1/runtime-models/claude",
                "DELETE servers/server-1/machines/machine-1/workspaces/agent-old"
            ),
            transport.calls
        )
    }

    @Test
    fun typedComputersApiAcceptsRawRowShapeForRename() = runBlocking {
        // Server returns the raw machines DB row for PATCH (no status / statusVersion
        // enrichment). The typed API must accept this shape, NOT flag it as contract
        // mismatch, and still produce a ComputerSummary with name + id propagated.
        val transport = RecordingComputersApi(
            renameBody = """{"id":"machine-1","name":"Maria Laptop","description":"Build host","apiKeyPrefix":"sk_machine_abc","hostname":"maria.local","createdAt":"2026-05-01T00:00:00.000Z"}"""
        )
        val client = SlockComputersApiClient(transport)
        val serverId = ServerId("server-1")

        val renamed = assertIs<DataResult.Content<ComputerSummary>>(client.renameMachine(serverId, "machine-1", "Maria Laptop")).data
        assertEquals("machine-1", renamed.id)
        assertEquals("Maria Laptop", renamed.name)
        assertEquals("sk_machine_abc", renamed.apiKeyPrefix)
        assertEquals("Build host", renamed.description)
        assertEquals(ComputerStatus.Unknown, renamed.status)
        assertEquals(0L, renamed.statusVersion)
    }

    @Test
    fun typedComputersApiMapsHttpErrorsToStructuredCodes() = runBlocking {
        // 307 fly-replay, 400 bad request, 409 has-agents, 503 machine offline each
        // produce a specific structured error code so the Settings page can show
        // human-readable copy instead of "http_307".
        val transport = RecordingComputersApi()
        val client = SlockComputersApiClient(transport)
        val serverId = ServerId("server-1")

        transport.respond = { code -> HomeNetworkResponse(code = code, bodyText = """{"error":"x"}""") }
        assertEquals("machine_offline_replay", assertIs<DataResult.Error>(client.machineWorkspaces(serverId, "m")).error.code)
        assertEquals(true, assertIs<DataResult.Error>(client.machineWorkspaces(serverId, "m")).error.retryable)
        assertEquals("computer_bad_request", assertIs<DataResult.Error>(client.renameMachine(serverId, "m", "n")).error.code)
        assertEquals(false, assertIs<DataResult.Error>(client.renameMachine(serverId, "m", "n")).error.retryable)
        assertEquals("computer_bad_request", assertIs<DataResult.Error>(client.updateMachineDescription(serverId, "m", "d")).error.code)
        assertEquals("computer_has_agents", assertIs<DataResult.Error>(client.deleteMachine(serverId, "m")).error.code)
        assertEquals(false, assertIs<DataResult.Error>(client.deleteMachine(serverId, "m")).error.retryable)
        assertEquals("server_forbidden", assertIs<DataResult.NoPermission>(client.machines(serverId)).blocker.code)
    }

    @Test
    fun typedComputersApiRejectsGuessedWrappersAndAliases() = runBlocking {
        val client =
            SlockComputersApiClient(
                RecordingComputersApi(
                    machinesBody = """{"items":[{"id":"machine-1","name":"Maria"}]}""",
                    registerBody = """{"computer":{"id":"machine-1","name":"Maria"},"key":"secret"}""",
                    renameBody = """{"machine":{"id":"machine-1","name":"Maria"}}""",
                    commandBody = """{"success":true}""",
                    rotateBody = """{"key":"sk_machine_123"}""",
                    workspacesBody = """{"workspaces":[{"directoryName":"agent-1"}]}""",
                    runtimeModelsBody = """{"items":["sonnet"],"defaultModel":"sonnet"}"""
                )
            )
        val serverId = ServerId("server-1")

        assertEquals("computers_list_contract_mismatch", assertIs<DataResult.Error>(client.machines(serverId)).error.code)
        assertEquals("computer_register_contract_mismatch", assertIs<DataResult.Error>(client.registerMachine(serverId, "Maria")).error.code)
        assertEquals("computer_rename_contract_mismatch", assertIs<DataResult.Error>(client.renameMachine(serverId, "machine-1", "Maria")).error.code)
        assertEquals("computer_update_contract_mismatch", assertIs<DataResult.Error>(client.updateMachineDescription(serverId, "machine-1", "Maria host")).error.code)
        assertEquals("computer_delete_contract_mismatch", assertIs<DataResult.Error>(client.deleteMachine(serverId, "machine-1")).error.code)
        assertEquals("computer_rotate_key_contract_mismatch", assertIs<DataResult.Error>(client.rotateMachineKey(serverId, "machine-1")).error.code)
        assertEquals("computer_workspaces_contract_mismatch", assertIs<DataResult.Error>(client.machineWorkspaces(serverId, "machine-1")).error.code)
        assertEquals("computer_runtime_models_contract_mismatch", assertIs<DataResult.Error>(client.runtimeModels(serverId, "machine-1", "claude")).error.code)
    }

    private class RecordingComputersApi(
        private val machinesBody: String =
            """{"machines":[{"id":"machine-1","name":"Maria Laptop","status":"online","statusVersion":7,"apiKeyPrefix":"sk_machine_abc","runtimes":["claude","codex"],"daemonVersion":"0.53.0","computerVersion":"0.0.61","isComputer":true,"hostname":"maria.local","os":"darwin","description":"Runs production agents","lastHeartbeat":"2026-06-14T02:00:00.000Z","createdAt":"2026-05-01T00:00:00.000Z"}],"latestDaemonVersion":"0.53.0","latestComputerVersion":"0.0.62"}""",
        private val registerBody: String =
            """{"machine":{"id":"machine-1","name":"Maria","status":"offline","statusVersion":0,"apiKeyPrefix":"sk_machine_123456","runtimes":[],"daemonVersion":null,"hostname":null,"os":null,"lastHeartbeat":null,"createdAt":null},"apiKey":"sk_machine_123456"}""",
        private val renameBody: String =
            """{"id":"machine-1","name":"Maria Laptop","status":"online","statusVersion":7,"apiKeyPrefix":"sk_machine_abc","runtimes":["claude","codex"],"daemonVersion":"0.53.0","hostname":"maria.local","os":"darwin","description":"Build host","lastHeartbeat":"2026-06-14T02:00:00.000Z","createdAt":"2026-05-01T00:00:00.000Z"}""",
        private val commandBody: String = """{"ok":true}""",
        private val rotateBody: String = """{"apiKey":"sk_machine_123456"}""",
        private val workspacesBody: String =
            """[{"directoryName":"agent-live","totalSizeBytes":2048,"lastModified":"2026-05-26T00:00:00.000Z","fileCount":12,"status":"active","agentName":"Noel","agentStatus":"active"},{"directoryName":"agent-old","totalSizeBytes":1,"lastModified":"2026-05-25T00:00:00.000Z","fileCount":1,"status":"orphan","agentName":null,"agentStatus":null}]""",
        private val runtimeModelsBody: String =
            """{"models":[{"id":"sonnet","label":"Claude Sonnet","verified":"launchable"},{"id":"opus","label":"Claude Opus"}],"default":"sonnet"}"""
    ) : SlockComputersApi {
        var respond: ((Int) -> HomeNetworkResponse)? = null
        val calls = mutableListOf<String>()

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse {
            calls += "GET servers/${serverId.value}/machines"
            return respond?.invoke(403) ?: ok(machinesBody)
        }

        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse {
            calls += "POST servers/${serverId.value}/machines body=$body"
            return respond?.invoke(403) ?: ok(registerBody)
        }

        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse {
            calls += "PATCH servers/${serverId.value}/machines/$machineId body=$body"
            return respond?.invoke(400) ?: ok(renameBody)
        }

        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse {
            calls += "DELETE servers/${serverId.value}/machines/$machineId"
            return respond?.invoke(409) ?: ok(commandBody)
        }

        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse {
            calls += "POST servers/${serverId.value}/machines/$machineId/rotate-key"
            return respond?.invoke(403) ?: ok(rotateBody)
        }

        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse {
            calls += "GET servers/${serverId.value}/machines/$machineId/workspaces"
            return respond?.invoke(307) ?: ok(workspacesBody)
        }

        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse {
            calls += "GET servers/${serverId.value}/machines/$machineId/runtime-models/$runtime"
            return respond?.invoke(503) ?: ok(runtimeModelsBody)
        }

        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse {
            calls += "DELETE servers/${serverId.value}/machines/$machineId/workspaces/$directoryName"
            return ok(commandBody)
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }
}
