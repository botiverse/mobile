package build.raft.app

import build.raft.app.data.AGENT_CREATE_FORM_REFRESH_FAILED
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.SharedCreateAgentCoordinator
import build.raft.app.data.SharedRuntimeFormDefinitionRef
import build.raft.app.data.SharedThreadNetworkApi
import build.raft.app.data.RaftAgentsApi
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RaftIntegrationsActionsPushApi
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.ThreadActionCard
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeHostUserState
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SharedThreadAgentCreateActionCardTest {
    @Test
    fun dialogSubmitForwardsRuntimeContractAndMarksCardExecuted() = runPortableCoroutineTest {
        val agentsApi = RecordingCreateAgentApi()
        val actionsApi = RecordingActionCardApi()
        val computersApi = UnusedComputersApi()
        val dataSource = dataSource(agentsApi, actionsApi, computersApi)
        val formRef = SharedRuntimeFormDefinitionRef(1, "builtin", "builtin-pi.create.v3")
        val submit =
            SharedCreateAgentDialogSubmit(
                name = "Noel",
                description = "Helps with release review.",
                machineId = "machine-2",
                runtimeConfig =
                    RuntimeConfig(
                        runtime = "builtin",
                        provider = RuntimeProviderConfig.Preset("deepseek", "secret-preset"),
                        model = RuntimeModelConfig.Preset("deepseek/deepseek-v4-pro"),
                        hostUserState = RuntimeHostUserState.Forbidden
                    ),
                formDefinitionRef = formRef
            )

        val result =
            dataSource.commitAgentCreateActionCardFromDialog(
                messageId = "m-card",
                card = ThreadActionCard(type = "agent:create", name = "@Draft"),
                submit = submit,
                formStore = null,
                serverId = "server-1"
            )

        val executed = assertIs<build.raft.app.data.DataResult.Content<ThreadActionCard>>(result).data
        assertEquals("executed", executed.state)
        assertEquals("agent-created", executed.resultId)
        val body = Json.parseToJsonElement(agentsApi.createBodies.single()).jsonObject
        assertEquals("Noel", body.getValue("name").jsonPrimitive.content)
        assertEquals("machine-2", body.getValue("machineId").jsonPrimitive.content)
        val runtime = body.getValue("runtimeConfig").jsonObject
        assertEquals("builtin", runtime.getValue("runtime").jsonPrimitive.content)
        assertEquals("deepseek/deepseek-v4-pro", runtime.getValue("model").jsonObject.getValue("id").jsonPrimitive.content)
        val ref = body.getValue("formDefinitionRef").jsonObject
        assertEquals(1, ref.getValue("protocolVersion").jsonPrimitive.content.toInt())
        assertEquals("builtin-pi.create.v3", ref.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(1, actionsApi.markBodies.size)
    }

    @Test
    fun rejectedRuntimeFormUsesRecoveryWithoutReplayingCreateOrMarkingCardExecuted() = runPortableCoroutineTest {
        val agentsApi =
            RecordingCreateAgentApi(
                response =
                    HomeNetworkResponse(
                        code = 409,
                        bodyText =
                            """{"error":"Runtime form definition is stale or invalid","issues":[{"code":"form_definition_ref_required","pointer":"/formDefinitionRef"}]}"""
                    )
            )
        val actionsApi = RecordingActionCardApi()
        val computersApi = UnusedComputersApi()
        val dataSource = dataSource(agentsApi, actionsApi, computersApi)
        val submit =
            SharedCreateAgentDialogSubmit(
                name = "Noel",
                description = null,
                machineId = "machine-2",
                runtimeConfig =
                    RuntimeConfig(
                        runtime = "builtin",
                        model = RuntimeModelConfig.Preset("deepseek/deepseek-v4-pro")
                    ),
                formDefinitionRef = SharedRuntimeFormDefinitionRef(1, "builtin", "builtin-pi.create.v3")
            )

        val result =
            dataSource.commitAgentCreateActionCardFromDialog(
                messageId = "m-card",
                card = ThreadActionCard(type = "agent:create", name = "@Noel"),
                submit = submit,
                formStore = null,
                serverId = "server-1"
            )

        assertEquals(
            AGENT_CREATE_FORM_REFRESH_FAILED,
            assertIs<build.raft.app.data.DataResult.Error>(result).error.code
        )
        assertEquals(1, agentsApi.createBodies.size)
        assertTrue(actionsApi.markBodies.isEmpty())
    }

    private fun dataSource(
        agentsApi: RecordingCreateAgentApi,
        actionsApi: RecordingActionCardApi,
        computersApi: UnusedComputersApi
    ): SharedThreadDataSource =
        testSharedThreadDataSource(
            networkApi = UnusedThreadNetworkApi(),
            sessionStore = InMemoryRaftAuthSessionStore(),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionsApi),
            createAgentCoordinator =
                SharedCreateAgentCoordinator(
                    agentsApiClient = RaftAgentsApiClient(agentsApi),
                    computersApiClient = RaftComputersApiClient(computersApi)
                )
        )
}

private class RecordingCreateAgentApi(
    private val response: HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = """{"id":"agent-created","name":"Noel","status":"active"}""")
) : RaftAgentsApi {
    val createBodies = mutableListOf<String>()

    override suspend fun createAgent(body: String): HomeNetworkResponse = response.also { createBodies += body }
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

private class UnusedComputersApi : RaftComputersApi {
    override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
    override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
    override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
    override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
    override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
    override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
    override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unused")
}

private class RecordingActionCardApi : RaftIntegrationsActionsPushApi {
    val markBodies = mutableListOf<String>()

    override suspend fun markActionExecuted(messageId: MessageId, body: String): HomeNetworkResponse {
        markBodies += body
        return HomeNetworkResponse(code = 200, bodyText = """{"messageId":"${messageId.value}","metadata":{"kind":"action-card"}}""")
    }

    override suspend fun integrationsOverview(): HomeNetworkResponse = error("unused")
    override suspend fun integrationClients(): HomeNetworkResponse = error("unused")
    override suspend fun createIntegrationClient(body: String): HomeNetworkResponse = error("unused")
    override suspend fun approveIntegrationRequest(requestId: String, body: String): HomeNetworkResponse = error("unused")
    override suspend fun denyIntegrationRequest(requestId: String): HomeNetworkResponse = error("unused")
    override suspend fun revokeIntegrationGrant(grantId: String): HomeNetworkResponse = error("unused")
    override suspend fun agentIntegrations(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun executeAction(messageId: MessageId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun emitActionEvent(messageId: MessageId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun pushVapidKey(): HomeNetworkResponse = error("unused")
    override suspend fun recordPushPromptEvent(body: String): HomeNetworkResponse = error("unused")
    override suspend fun subscribePush(body: String): HomeNetworkResponse = error("unused")
    override suspend fun unsubscribePush(body: String): HomeNetworkResponse = error("unused")
    override suspend fun testPush(): HomeNetworkResponse = error("unused")
}

private class UnusedThreadNetworkApi : SharedThreadNetworkApi {
    override suspend fun channelMessages(threadId: String, limit: Int, beforeSeq: Long?, afterSeq: Long?) = unused()
    override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int) = unused()
    override suspend fun sendMessage(channelId: String, body: String, asTask: Boolean, attachmentIds: List<String>, randomId: String?) = unused()
    override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>) = unused()
    override suspend fun attachmentUrl(attachmentId: String, disposition: String?) = unused()
    override suspend fun attachmentHtmlPreviewUrl(attachmentId: String) = unused()
    override suspend fun attachmentComments(attachmentId: String) = unused()
    override suspend fun sendAttachmentComment(attachmentId: String, body: String) = unused()
    override suspend fun markChannelReadToSeq(channelId: String, seq: Long) = unused()
    override suspend fun markChannelReadAll(channelId: String) = unused()
    override suspend fun saveMessage(messageId: String) = unused()
    override suspend fun unsaveMessage(messageId: String) = unused()
    override suspend fun addReaction(messageId: String, emoji: String) = unused()
    override suspend fun removeReaction(messageId: String, emoji: String) = unused()
    override suspend fun createOrOpenThread(channelId: String, parentMessageId: String) = unused()
    override suspend fun messageContext(messageId: String, channelId: String?) = unused()

    private fun unused(): HomeNetworkResponse = error("unused")
}
