package build.raft.app.data

import build.raft.app.SharedCreateAgentRuntimeModelCatalog
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SharedCreateAgentCoordinatorTest {
    @Test
    fun loadRuntimeModelsUsesMachineEndpointAndLocalLabels() = runBlocking {
        val computersApi =
            FakeCreateAgentComputersApi(
                runtimeModelsBody =
                    """{"models":[{"id":"claude-sonnet-4-6","label":"Machine Sonnet","verified":"launchable"},{"id":"vendor/custom","label":"Vendor Custom","verified":"suggestion_only","supportedReasoningEfforts":["low","high"],"defaultReasoningEffort":"high"}],"default":"vendor/custom"}"""
            )
        val coordinator =
            SharedCreateAgentCoordinator(
                agentsApiClient = SlockAgentsApiClient(FakeCreateAgentAgentsApi()),
                computersApiClient = SlockComputersApiClient(computersApi)
            )

        val result = coordinator.loadRuntimeModels("server-1", "machine-1", "claude")

        val catalog = assertIs<DataResult.Content<SharedCreateAgentRuntimeModelCatalog>>(result).data
        assertEquals(listOf("runtimeModels:server-1:machine-1:claude"), computersApi.calls)
        assertEquals("vendor/custom", catalog.defaultModel)
        assertEquals(listOf("claude-sonnet-4-6", "vendor/custom"), catalog.models.map { it.id })
        assertEquals(listOf("Machine Sonnet", "Vendor Custom"), catalog.models.map { it.label })
        assertEquals(listOf("launchable", "suggestion_only"), catalog.models.map { it.verified })
        assertEquals(listOf("low", "high"), catalog.models.last().supportedReasoningEfforts)
        assertEquals("high", catalog.models.last().defaultReasoningEffort)
    }

    @Test
    fun loadRuntimeModelsCanonicalizesCodexGpt56OrderAndDefault() = runBlocking {
        val computersApi =
            FakeCreateAgentComputersApi(
                runtimeModelsBody =
                    """{"models":[{"id":"gpt-5.5","label":"GPT-5.5"},{"id":"gpt-5.6-sol","label":"GPT-5.6 Sol"},{"id":"gpt-5.6-terra","label":"GPT-5.6 Terra"},{"id":"gpt-5.6-luna","label":"GPT-5.6 Luna"},{"id":"gpt-5.4","label":"GPT-5.4"}],"default":"gpt-5.5"}"""
            )
        val coordinator =
            SharedCreateAgentCoordinator(
                agentsApiClient = SlockAgentsApiClient(FakeCreateAgentAgentsApi()),
                computersApiClient = SlockComputersApiClient(computersApi)
            )

        val result = coordinator.loadRuntimeModels("server-1", "machine-1", "codex")

        val catalog = assertIs<DataResult.Content<SharedCreateAgentRuntimeModelCatalog>>(result).data
        assertEquals("gpt-5.6-sol", catalog.defaultModel)
        assertEquals(
            listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5", "gpt-5.4"),
            catalog.models.map { it.id }
        )
        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max", "ultra"),
            catalog.models.first().supportedReasoningEfforts
        )
        assertEquals("medium", catalog.models.first().defaultReasoningEffort)
    }
}

private class FakeCreateAgentComputersApi(
    private val runtimeModelsBody: String
) : SlockComputersApi {
    val calls = mutableListOf<String>()

    override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
    override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
    override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
    override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
    override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")

    override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse {
        calls += "runtimeModels:${serverId.value}:$machineId:$runtime"
        return HomeNetworkResponse(code = 200, bodyText = runtimeModelsBody)
    }

    override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unused")
}

private class FakeCreateAgentAgentsApi : SlockAgentsApi {
    override suspend fun agents(): HomeNetworkResponse = error("unused")
    override suspend fun agent(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun createAgent(body: String): HomeNetworkResponse = error("unused")
    override suspend fun updateAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun startAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun stopAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun resetAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun deleteAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun assignMachine(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun agentDirectMessages(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun agentActivityLog(agentId: MemberId, limit: Int): HomeNetworkResponse = error("unused")
    override suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String?): HomeNetworkResponse = error("unused")
    override suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): HomeNetworkResponse = error("unused")
    override suspend fun agentSkills(agentId: MemberId): HomeNetworkResponse = error("unused")

    override suspend fun agentExternalStatus(agentId: MemberId): HomeNetworkResponse = error("unused")

    override suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun resetAgentScopes(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
}
