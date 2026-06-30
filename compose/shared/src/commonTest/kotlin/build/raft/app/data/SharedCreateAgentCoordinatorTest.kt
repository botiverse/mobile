package build.raft.app.data

import build.raft.app.SharedCreateAgentRuntimeModelOption
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
                    """{"models":[{"id":"claude-sonnet-4-6","label":"Machine Sonnet","verified":"launchable"},{"id":"vendor/custom","label":"Vendor Custom","verified":"suggestion_only"}],"default":"vendor/custom"}"""
            )
        val coordinator =
            SharedCreateAgentCoordinator(
                agentsApiClient = SlockAgentsApiClient(FakeCreateAgentAgentsApi()),
                computersApiClient = SlockComputersApiClient(computersApi)
            )

        val result = coordinator.loadRuntimeModels("server-1", "machine-1", "claude")

        val models = assertIs<DataResult.Content<List<SharedCreateAgentRuntimeModelOption>>>(result).data
        assertEquals(listOf("runtimeModels:server-1:machine-1:claude"), computersApi.calls)
        assertEquals(listOf("vendor/custom", "claude-sonnet-4-6"), models.map { it.id })
        assertEquals(listOf("Vendor Custom", "Machine Sonnet"), models.map { it.label })
        assertEquals(listOf("suggestion_only", "launchable"), models.map { it.verified })
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
    override suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun resetAgentScopes(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
}
