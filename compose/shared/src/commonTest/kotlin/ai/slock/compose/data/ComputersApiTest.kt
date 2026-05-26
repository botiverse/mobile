package ai.slock.compose.data

import ai.slock.compose.model.ServerId
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

        assertEquals(1, assertIs<DataResult.Content<ComputerListSummary>>(client.machines(serverId)).data.machineCount)
        assertEquals("machine-1", assertIs<DataResult.Content<ComputerRegistrationSummary>>(client.registerMachine(serverId, "Maria")).data.machine.id)
        assertEquals("Maria Laptop", assertIs<DataResult.Content<ComputerSummary>>(client.renameMachine(serverId, "machine-1", "Maria Laptop")).data.name)
        assertEquals(true, assertIs<DataResult.Content<ComputerCommandResult>>(client.deleteMachine(serverId, "machine-1")).data.ok)
        assertEquals("sk_machine_1", assertIs<DataResult.Content<ComputerApiKeyRotationSummary>>(client.rotateMachineKey(serverId, "machine-1")).data.apiKeyPrefix)
        assertEquals(2, assertIs<DataResult.Content<ComputerWorkspacePage>>(client.machineWorkspaces(serverId, "machine-1")).data.itemCount)
        assertEquals("sonnet", assertIs<DataResult.Content<ComputerRuntimeModelsSummary>>(client.runtimeModels(serverId, "machine-1", "claude")).data.defaultModel)
        assertEquals(true, assertIs<DataResult.Content<ComputerCommandResult>>(client.deleteMachineWorkspace(serverId, "machine-1", "agent-old")).data.ok)

        assertEquals(
            listOf(
                "GET servers/server-1/machines",
                """POST servers/server-1/machines body={"name":"Maria"}""",
                """PATCH servers/server-1/machines/machine-1 body={"name":"Maria Laptop"}""",
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
        assertEquals("computer_delete_contract_mismatch", assertIs<DataResult.Error>(client.deleteMachine(serverId, "machine-1")).error.code)
        assertEquals("computer_rotate_key_contract_mismatch", assertIs<DataResult.Error>(client.rotateMachineKey(serverId, "machine-1")).error.code)
        assertEquals("computer_workspaces_contract_mismatch", assertIs<DataResult.Error>(client.machineWorkspaces(serverId, "machine-1")).error.code)
        assertEquals("computer_runtime_models_contract_mismatch", assertIs<DataResult.Error>(client.runtimeModels(serverId, "machine-1", "claude")).error.code)
    }

    private class RecordingComputersApi(
        private val machinesBody: String =
            """{"machines":[{"id":"machine-1","name":"Maria Laptop","status":"online","daemonVersion":"0.53.0","hostname":"maria.local","os":"darwin"}],"latestDaemonVersion":"0.53.0"}""",
        private val registerBody: String =
            """{"machine":{"id":"machine-1","name":"Maria","status":"offline","daemonVersion":null,"hostname":null,"os":null},"apiKey":"sk_machine_123456"}""",
        private val renameBody: String =
            """{"id":"machine-1","name":"Maria Laptop","status":"online","daemonVersion":"0.53.0","hostname":"maria.local","os":"darwin"}""",
        private val commandBody: String = """{"ok":true}""",
        private val rotateBody: String = """{"apiKey":"sk_machine_123456"}""",
        private val workspacesBody: String =
            """[{"directoryName":"agent-live","totalSizeBytes":2048,"lastModified":"2026-05-26T00:00:00.000Z","fileCount":12,"status":"active","agentName":"Noel","agentStatus":"active"},{"directoryName":"agent-old","totalSizeBytes":1,"lastModified":"2026-05-25T00:00:00.000Z","fileCount":1,"status":"orphan","agentName":null,"agentStatus":null}]""",
        private val runtimeModelsBody: String = """{"models":["sonnet","opus"],"default":"sonnet"}"""
    ) : SlockComputersApi {
        val calls = mutableListOf<String>()

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse {
            calls += "GET servers/${serverId.value}/machines"
            return ok(machinesBody)
        }

        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse {
            calls += "POST servers/${serverId.value}/machines body=$body"
            return ok(registerBody)
        }

        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse {
            calls += "PATCH servers/${serverId.value}/machines/$machineId body=$body"
            return ok(renameBody)
        }

        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse {
            calls += "DELETE servers/${serverId.value}/machines/$machineId"
            return ok(commandBody)
        }

        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse {
            calls += "POST servers/${serverId.value}/machines/$machineId/rotate-key"
            return ok(rotateBody)
        }

        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse {
            calls += "GET servers/${serverId.value}/machines/$machineId/workspaces"
            return ok(workspacesBody)
        }

        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse {
            calls += "GET servers/${serverId.value}/machines/$machineId/runtime-models/$runtime"
            return ok(runtimeModelsBody)
        }

        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse {
            calls += "DELETE servers/${serverId.value}/machines/$machineId/workspaces/$directoryName"
            return ok(commandBody)
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }
}
