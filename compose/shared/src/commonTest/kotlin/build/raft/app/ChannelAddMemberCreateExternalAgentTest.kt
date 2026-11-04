package build.raft.app

import build.raft.app.data.AgentSummary
import build.raft.app.data.CreateAgentRequest
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAgentsApi
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.SharedCreateAgentCoordinator
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.model.serverPermissionsForRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Add Member's "Create External Agent" (web ChannelMembers `createAgentFooter`, slock staging
 * 947bd94): who may use it, the rows it shows, and what it sends: `POST /agents` with
 * `{ name, description, avatarUrl: "pixel:…", external: true }` and no Computer or runtime, then
 * the channel join as a one-agent batch.
 */
class ChannelAddMemberCreateExternalAgentTest {
    @Test
    fun onlyOwnersAndAdminsMayCreateAgentsFromAddMember() {
        assertTrue(threadAddMemberCanCreateAgents(serverPermissionsForRole("owner")))
        assertTrue(threadAddMemberCanCreateAgents(serverPermissionsForRole("admin")))
        assertFalse(threadAddMemberCanCreateAgents(serverPermissionsForRole("member")))
        assertFalse(threadAddMemberCanCreateAgents(serverPermissionsForRole("guest")))
        assertFalse(threadAddMemberCanCreateAgents(serverPermissionsForRole(null)))
        assertFalse(threadAddMemberCanCreateAgents(null))
    }

    @Test
    fun theFooterListsCreateAgentThenCreateExternalAgent() {
        val specs = threadAddMemberCreateRowSpecs(canCreateAgents = true, createName = "", adding = false)
        assertEquals(listOf(MembersCreateAgentMode.Managed, MembersCreateAgentMode.External), specs.map { it.mode })
        assertTrue(specs.all { it.enabled && it.showsAutoJoin && !it.promoted && it.initialName.isEmpty() })
    }

    @Test
    fun aSearchThatFoundNoOneNamesOnlyTheManagedRow() {
        val (managed, external) = threadAddMemberCreateRowSpecs(canCreateAgents = true, createName = "mia", adding = false)
        assertEquals("mia", managed.initialName)
        assertTrue(managed.promoted)
        assertEquals("", external.initialName)
        assertFalse(external.promoted)
    }

    @Test
    fun withoutPermissionBothRowsStayListedButDisabled() {
        val specs = threadAddMemberCreateRowSpecs(canCreateAgents = false, createName = "mia", adding = false)
        assertEquals(listOf(MembersCreateAgentMode.Managed, MembersCreateAgentMode.External), specs.map { it.mode })
        assertTrue(specs.none { it.enabled || it.showsAutoJoin || it.promoted })
        assertTrue(specs.all { it.initialName.isEmpty() })
    }

    @Test
    fun bothRowsAreDisabledWhileABatchIsBeingAdded() {
        val specs = threadAddMemberCreateRowSpecs(canCreateAgents = true, createName = "", adding = true)
        assertTrue(specs.none { it.enabled })
        assertTrue(specs.all { it.showsAutoJoin })
    }

    @Test
    fun theExternalSubmitSendsNoComputerAndNoRuntime() {
        val submit =
            assertNotNull(
                createAgentDialogSubmit(
                    name = " @mia ",
                    description = "  ",
                    machineId = "machine-1",
                    external = true,
                    runtime = "claude",
                    loaded = null,
                    values = emptyMap(),
                )
            )
        assertEquals("mia", submit.name)
        assertNull(submit.description)
        assertNull(submit.machineId)
        assertTrue(submit.external)
        assertNull(submit.runtimeFormV2)
    }

    @Test
    fun theExternalCreatePostsTheWebPayload() =
        runPortableCoroutineTest {
            val api = ExternalAgentRecordingAgentsApi()
            val coordinator = SharedCreateAgentCoordinator(RaftAgentsApiClient(api), RaftComputersApiClient(ExternalAgentUnusedComputersApi))
            val request: CreateAgentRequest =
                SharedCreateAgentDialogSubmit(name = "mia", description = "Runs on my laptop", machineId = null, external = true)
                    .toCreateAgentRequest()

            val result = coordinator.createAgent(request)

            assertEquals("agent-ext", assertIs<DataResult.Content<AgentSummary>>(result).data.id)
            val body = Json.parseToJsonElement(api.bodies.single()).jsonObject
            assertEquals(setOf("name", "description", "avatarUrl", "external"), body.keys)
            assertEquals("mia", body.string("name"))
            assertEquals("Runs on my laptop", body.string("description"))
            assertTrue(body.string("avatarUrl").startsWith("pixel:"))
            assertTrue(body.getValue("external").jsonPrimitive.boolean)
        }

    @Test
    fun anExternalCreateWithoutADescriptionLeavesItOut() =
        runPortableCoroutineTest {
            val api = ExternalAgentRecordingAgentsApi()
            val coordinator = SharedCreateAgentCoordinator(RaftAgentsApiClient(api), RaftComputersApiClient(ExternalAgentUnusedComputersApi))
            coordinator.createAgent(
                SharedCreateAgentDialogSubmit(name = "mia", description = null, machineId = null, external = true).toCreateAgentRequest()
            )
            assertEquals(setOf("name", "avatarUrl", "external"), Json.parseToJsonElement(api.bodies.single()).jsonObject.keys)
        }

    @Test
    fun theCreatedAgentJoinsTheChannelAsAOneAgentBatch() {
        val request = createdAgentJoinRequest(ChannelId("channel-design"), "agent-ext")
        assertEquals(ChannelId("channel-design"), request.channelId)
        assertEquals(emptyList(), request.userIds)
        assertEquals(listOf(MemberId("agent-ext")), request.agentIds)
    }

    @Test
    fun anExternalAgentOpensItsProfileBeforeTheDialogCloses() {
        assertEquals(
            listOf(CreatedAgentAfterJoinStep.OpenProfile, CreatedAgentAfterJoinStep.DismissDialog),
            createdAgentAfterJoinSteps(MembersCreateAgentMode.External, isExternalAgent = true),
        )
        assertEquals(
            listOf(CreatedAgentAfterJoinStep.DismissDialog),
            createdAgentAfterJoinSteps(MembersCreateAgentMode.Managed, isExternalAgent = true),
        )
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private class ExternalAgentRecordingAgentsApi : RaftAgentsApi {
    val bodies = mutableListOf<String>()

    override suspend fun createAgent(body: String): HomeNetworkResponse {
        bodies += body
        return HomeNetworkResponse(code = 201, bodyText = """{"id":"agent-ext","name":"mia","status":"active","avatarUrl":"pixel:tree"}""")
    }

    override suspend fun agents(): HomeNetworkResponse = error("unused")
    override suspend fun agent(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun updateAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun startAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun stopAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun resetAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun deleteAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
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

private object ExternalAgentUnusedComputersApi : RaftComputersApi {
    override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
    override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
    override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
    override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
    override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
    override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
    override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
        error("unused")
}
