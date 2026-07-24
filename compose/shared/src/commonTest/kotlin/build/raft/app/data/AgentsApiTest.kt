package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeHostUserState
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.model.buildRuntimeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AgentsApiTest {
    @Test
    fun typedAgentsApiAcceptsCanonicalUpstreamShapes() = runTest {
        val transport = RecordingAgentsApi()
        val client = SlockAgentsApiClient(transport)
        val agentId = MemberId("agent-1")

        assertEquals(1, assertIs<DataResult.Content<AgentsPage>>(client.agents()).data.agentCount)
        val detail = assertIs<DataResult.Content<AgentSummary>>(client.agent(agentId)).data
        assertEquals("agent-1", detail.id)
        assertEquals("Noel Bot", detail.displayName)
        assertEquals("admin", detail.serverRole)
        assertEquals("machine-1", detail.machineId)
        assertEquals("codex", detail.runtime)
        assertEquals("preset", detail.runtimeModelKind)
        assertEquals("gpt-5.6-sol", detail.model)
        assertEquals("medium", detail.reasoning)
        assertEquals("local", detail.executionMode)
        assertEquals("0.57.1", detail.daemonVersion)
        assertEquals("pixel:robot", detail.avatarUrl)
        assertEquals("user-1", detail.creatorId)
        assertEquals("human", detail.creatorType)
        assertEquals("Artin", detail.creator?.displayName)
        assertEquals("artin", detail.creator?.name)
        assertEquals("pixel:user", detail.creator?.avatarUrl)
        assertEquals(listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY"), detail.envVarNames)
        assertEquals("Helper", detail.createdAgents.single().displayName)
        val created = assertIs<DataResult.Content<AgentSummary>>(client.createAgent("""{"name":"Noel"}""")).data
        assertEquals("agent-2", created.id)
        assertEquals("pixel:tree", created.avatarUrl)
        assertEquals("Agent One", assertIs<DataResult.Content<AgentSummary>>(client.updateAgent(agentId, """{"displayName":"Agent One"}""")).data.name)
        assertEquals(true, assertIs<DataResult.Content<AgentCommandResult>>(client.startAgent(agentId)).data.ok)
        assertEquals(true, assertIs<DataResult.Content<AgentCommandResult>>(client.stopAgent(agentId)).data.ok)
        assertEquals(true, assertIs<DataResult.Content<AgentCommandResult>>(client.resetAgent(agentId, """{"mode":"session"}""")).data.ok)
        assertEquals(true, assertIs<DataResult.Content<AgentCommandResult>>(client.deleteAgent(agentId)).data.ok)
        assertEquals(true, assertIs<DataResult.Content<AgentCommandResult>>(client.assignMachine(agentId, """{"machineId":"machine-1"}""")).data.ok)
        assertEquals(1, assertIs<DataResult.Content<AgentDmPage>>(client.agentDirectMessages(agentId)).data.itemCount)
        assertEquals(1, assertIs<DataResult.Content<AgentActivityPage>>(client.agentActivityLog(agentId)).data.entryCount)
        assertEquals(1, assertIs<DataResult.Content<AgentWorkspaceFilePage>>(client.agentWorkspaceFiles(agentId)).data.fileCount)
        assertEquals("/repo/README.md", assertIs<DataResult.Content<AgentWorkspaceFileRead>>(client.readAgentWorkspaceFile(agentId, "/repo/README.md")).data.path)
        assertEquals(1, assertIs<DataResult.Content<AgentSkillsSummary>>(client.agentSkills(agentId)).data.globalCount)
        assertEquals(2, assertIs<DataResult.Content<AgentScopeSet>>(client.agentScopes(agentId)).data.granted.size)
        val scopesUpdateBody = client.updateAgentScopes(agentId, listOf("server:read", "channel:read"))
        assertEquals(2, assertIs<DataResult.Content<AgentScopeSet>>(scopesUpdateBody).data.granted.size)
        assertEquals(true, assertIs<DataResult.Content<AgentBootstrapTokenSummary>>(client.issueBootstrapToken(agentId, """{"ttlMs":60000}""")).data.bootstrapTokenPresent)

        assertEquals(
            listOf(
                "GET agents",
                "GET agents/agent-1",
                """POST agents body={"name":"Noel"}""",
                """PATCH agents/agent-1 body={"displayName":"Agent One"}""",
                "POST agents/agent-1/start",
                "POST agents/agent-1/stop",
                """POST agents/agent-1/reset body={"mode":"session"}""",
                "DELETE agents/agent-1",
                """POST agents/agent-1/assign-machine body={"machineId":"machine-1"}""",
                "GET agents/agent-1/agent-dms",
                "GET agents/agent-1/activity-log limit=50",
                "GET agents/agent-1/workspace-files dirPath=null",
                "GET agents/agent-1/workspace-files/read path=/repo/README.md",
                "GET agents/agent-1/skills",
                "GET agents/agent-1/scopes",
                """PUT agents/agent-1/scopes body={"scopes":["server:read","channel:read"]}""",
                """POST agents/agent-1/bootstrap-tokens body={"ttlMs":60000}"""
            ),
            transport.calls
        )
    }

    @Test
    fun publicAgentDetailLoadsOnlyTheJointAuthorizedAgentProfile() = runTest {
        val transport = RecordingAgentsApi()
        val detail =
            assertIs<DataResult.Content<AgentDetailSnapshot>>(
                SlockAgentsApiClient(transport).publicAgentDetail(MemberId("agent-1"))
            ).data

        assertEquals("agent-1", detail.agentId.value)
        assertEquals("Noel Bot", detail.summary?.displayName)
        assertEquals(emptyList(), detail.directMessages)
        assertEquals(emptyList(), detail.channels)
        assertEquals(emptyList(), detail.reminders)
        assertEquals(emptyList(), detail.activity)
        assertEquals(listOf("GET agents/agent-1"), transport.calls)
    }

    @Test
    fun createAgentBodySerializesAvatarAndRuntimeSelectionFields() {
        val body =
            buildCreateAgentBody(
                CreateAgentBody(
                    name = "Noel",
                    description = "Runs builds",
                    model = "gpt-5.3-codex",
                    runtime = "codex",
                    reasoningEffort = "high",
                    machineId = "machine-1",
                    avatarUrl = "pixel:robot",
                    runtimeConfig =
                        buildRuntimeConfig(
                            runtime = "codex",
                            model = "gpt-5.3-codex",
                            customModelMode = false,
                            fastMode = true,
                            reasoningEffort = "high"
                        )
                )
            )
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals("Noel", json.getValue("name").jsonPrimitive.content)
        assertEquals("pixel:robot", json.getValue("avatarUrl").jsonPrimitive.content)
        assertEquals("codex", json.getValue("runtime").jsonPrimitive.content)
        assertEquals("gpt-5.3-codex", json.getValue("model").jsonPrimitive.content)
        assertEquals("high", json.getValue("reasoningEffort").jsonPrimitive.content)
        assertEquals("machine-1", json.getValue("machineId").jsonPrimitive.content)
        val runtimeConfig = json.getValue("runtimeConfig").jsonObject
        assertEquals("codex", runtimeConfig.getValue("runtime").jsonPrimitive.content)
        assertEquals(
            "preset",
            runtimeConfig.getValue("model").jsonObject.getValue("kind").jsonPrimitive.content
        )
        assertEquals(
            "gpt-5.3-codex",
            runtimeConfig.getValue("model").jsonObject.getValue("id").jsonPrimitive.content
        )
        assertEquals(
            "fast",
            runtimeConfig.getValue("mode").jsonObject.getValue("kind").jsonPrimitive.content
        )
    }

    @Test
    fun createAgentBodySerializesBuiltInPresetAndExactFormRef() {
        val body = buildCreateAgentBody(
            CreateAgentBody(
                name = "Pi Preset",
                machineId = "machine-1",
                runtimeConfig = RuntimeConfig(
                    runtime = "builtin",
                    provider = RuntimeProviderConfig.Preset("deepseek", "secret-preset"),
                    model = RuntimeModelConfig.Preset("deepseek/deepseek-v4-pro"),
                    hostUserState = RuntimeHostUserState.Forbidden
                ),
                formDefinitionRef = SharedRuntimeFormDefinitionRef(1, "builtin", "builtin-pi.create.v2")
            )
        )
        val json = Json.parseToJsonElement(body).jsonObject
        val runtimeConfig = json.getValue("runtimeConfig").jsonObject
        assertEquals("builtin", runtimeConfig.getValue("runtime").jsonPrimitive.content)
        assertEquals("forbidden", runtimeConfig.getValue("hostUserState").jsonPrimitive.content)
        assertEquals("preset", runtimeConfig.getValue("provider").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("deepseek", runtimeConfig.getValue("provider").jsonObject.getValue("providerId").jsonPrimitive.content)
        assertEquals("secret-preset", runtimeConfig.getValue("provider").jsonObject.getValue("apiKey").jsonPrimitive.content)
        assertEquals("preset", runtimeConfig.getValue("model").jsonObject.getValue("kind").jsonPrimitive.content)
        val ref = json.getValue("formDefinitionRef").jsonObject
        assertEquals(1, ref.getValue("protocolVersion").jsonPrimitive.content.toInt())
        assertEquals("builtin", ref.getValue("runtimeId").jsonPrimitive.content)
        assertEquals("builtin-pi.create.v2", ref.getValue("schemaVersion").jsonPrimitive.content)
    }

    @Test
    fun createAgentBodySerializesBuiltInGatewayCustomModel() {
        val body = buildCreateAgentBody(
            CreateAgentBody(
                name = "Pi Gateway",
                machineId = "machine-1",
                runtimeConfig = RuntimeConfig(
                    runtime = "builtin",
                    provider = RuntimeProviderConfig.Gateway(
                        providerId = "openai-compatible",
                        baseUrl = "https://gateway.example.com/v1",
                        apiKey = "secret-gateway",
                        supportsImageInput = true
                    ),
                    model = RuntimeModelConfig.Custom("vendor/model-v2"),
                    hostUserState = RuntimeHostUserState.Forbidden
                ),
                formDefinitionRef = SharedRuntimeFormDefinitionRef(1, "builtin", "builtin-pi.create.v2")
            )
        )
        val runtimeConfig = Json.parseToJsonElement(body).jsonObject.getValue("runtimeConfig").jsonObject
        val provider = runtimeConfig.getValue("provider").jsonObject
        assertEquals("gateway", provider.getValue("kind").jsonPrimitive.content)
        assertEquals("openai-compatible", provider.getValue("providerId").jsonPrimitive.content)
        assertEquals("https://gateway.example.com/v1", provider.getValue("baseUrl").jsonPrimitive.content)
        assertEquals("secret-gateway", provider.getValue("apiKey").jsonPrimitive.content)
        assertEquals(true, provider.getValue("supportsImageInput").jsonPrimitive.boolean)
        val model = runtimeConfig.getValue("model").jsonObject
        assertEquals("custom", model.getValue("kind").jsonPrimitive.content)
        assertEquals("vendor/model-v2", model.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun repeatedAgentReadsNeverRetainRuntimeProviderSecrets() = runTest {
        val sentinel = "sentinel-provider-secret"
        val client = SlockAgentsApiClient(
            RecordingAgentsApi(
                agentBody =
                    """{"id":"agent-1","name":"Pi","runtimeConfig":{"version":1,"runtime":"builtin","provider":{"kind":"preset","providerId":"deepseek","apiKey":"$sentinel"},"model":{"kind":"preset","id":"deepseek/deepseek-v4-pro"},"mode":{"kind":"default"},"hostUserState":"forbidden"}}"""
            )
        )

        repeat(2) {
            val detail = assertIs<DataResult.Content<AgentSummary>>(client.agent(MemberId("agent-1"))).data
            assertEquals("builtin", detail.runtime)
            assertEquals("deepseek/deepseek-v4-pro", detail.model)
            assertEquals(false, sentinel in detail.toString())
        }
    }

    @Test
    fun typedAgentsApiPreservesCustomRuntimeModelKind() = runTest {
        val client =
            SlockAgentsApiClient(
                RecordingAgentsApi(
                    agentBody =
                        """{"id":"agent-1","name":"Agent One","model":"legacy-flat","runtime":"claude","runtimeConfig":{"version":1,"runtime":"claude","provider":{"kind":"custom","apiUrl":"https://example.invalid","apiKey":"secret"},"model":{"kind":"custom","name":"vendor/private-model"},"mode":{"kind":"default"}}}"""
                )
            )

        val detail = assertIs<DataResult.Content<AgentSummary>>(client.agent(MemberId("agent-1"))).data

        assertEquals("claude", detail.runtime)
        assertEquals("custom", detail.runtimeModelKind)
        assertEquals("vendor/private-model", detail.model)
    }

    @Test
    fun typedAgentsApiRejectsGuessedWrappersAndAliases() = runTest {
        val client =
            SlockAgentsApiClient(
                RecordingAgentsApi(
                    agentsBody = """{"agents":[{"id":"agent-1"}]}""",
                    agentBody = """{"agent":{"id":"agent-1","name":"Noel"}}""",
                    commandBody = """{"success":true}""",
                    agentDmsBody = """{"dms":[{"id":"dm-1"}]}""",
                    activityBody = """{"activityLog":[{"id":"a1"}]}""",
                    workspaceFilesBody = """{"items":[{"path":"/repo/README.md"}]}""",
                    workspaceFileReadBody = """{"file":{"path":"/repo/README.md","content":"Hello"}}""",
                    skillsBody = """{"skills":{"global":[],"workspace":[]}}""",
                    scopesBody = """{"scopes":["messages:read"]}""",
                    bootstrapBody = """{"token":"raw","id":"token-1"}"""
                )
            )
        val agentId = MemberId("agent-1")

        assertEquals("agents_list_contract_mismatch", assertIs<DataResult.Error>(client.agents()).error.code)
        assertEquals("agent_detail_contract_mismatch", assertIs<DataResult.Error>(client.agent(agentId)).error.code)
        assertEquals("agent_start_contract_mismatch", assertIs<DataResult.Error>(client.startAgent(agentId)).error.code)
        assertEquals("agent_dms_contract_mismatch", assertIs<DataResult.Error>(client.agentDirectMessages(agentId)).error.code)
        assertEquals("agent_activity_log_contract_mismatch", assertIs<DataResult.Error>(client.agentActivityLog(agentId)).error.code)
        assertEquals("agent_workspace_files_contract_mismatch", assertIs<DataResult.Error>(client.agentWorkspaceFiles(agentId)).error.code)
        assertEquals("agent_workspace_file_read_contract_mismatch", assertIs<DataResult.Error>(client.readAgentWorkspaceFile(agentId, "/repo/README.md")).error.code)
        assertEquals("agent_skills_contract_mismatch", assertIs<DataResult.Error>(client.agentSkills(agentId)).error.code)
        assertEquals("agent_scopes_contract_mismatch", assertIs<DataResult.Error>(client.agentScopes(agentId)).error.code)
        assertEquals("agent_bootstrap_token_contract_mismatch", assertIs<DataResult.Error>(client.issueBootstrapToken(agentId, "{}")).error.code)
    }

    private class RecordingAgentsApi(
        private val agentsBody: String = """[{"id":"agent-1","name":"Noel","status":"active"}]""",
        private val agentBody: String = """{"id":"agent-1","name":"Agent One","displayName":"Noel Bot","role":"Admin","status":"active","model":"gpt-5","runtime":"codex","runtimeConfig":{"version":1,"runtime":"codex","model":{"kind":"preset","id":"gpt-5.6-sol"},"mode":{"kind":"default"}},"machineId":"machine-1","reasoningEffort":"medium","executionMode":"local","avatarUrl":"pixel:robot","creatorId":"user-1","creatorType":"human","creator":{"type":"human","id":"user-1","name":"artin","displayName":"Artin","avatarUrl":"pixel:user","gravatarHash":"hash-1"},"daemonVersion":"0.57.1","envVars":{"OPENAI_API_KEY":"configured","ANTHROPIC_API_KEY":"configured"},"createdAgents":[{"id":"agent-child","name":"helper","displayName":"Helper","runtime":"codex","status":"idle"}],"activity":"idle","activityDetail":"Ready","deletedAt":null}""",
        private val createAgentBody: String = """{"id":"agent-2","name":"Noel","status":"active","avatarUrl":"pixel:tree"}""",
        private val commandBody: String = """{"ok":true}""",
        private val agentDmsBody: String = """[{"id":"dm-1","name":"Agent chat"}]""",
        private val activityBody: String = """[{"id":"a1","type":"tool_start","timestamp":"2026-05-22T00:00:00.000Z"}]""",
        private val workspaceFilesBody: String = """{"files":[{"path":"/repo/README.md","name":"README.md"}]}""",
        private val workspaceFileReadBody: String = """{"path":"/repo/README.md","content":"Hello","binary":false,"size":5,"mimeType":"text/markdown","encoding":"utf-8","modifiedAt":"2026-05-22T00:00:00.000Z"}""",
        private val skillsBody: String = """{"global":[{"name":"openai-docs"}],"workspace":[{"name":"android-regression"}]}""",
        private val scopesBody: String = """{"agentId":"agent-1","granted":["messages:read","messages:send"],"revision":7,"updatedAt":"2026-05-22T00:00:00.000Z"}""",
        private val bootstrapBody: String = """{"tokenId":"token-1","bootstrapToken":"sk_agent_secret","tokenPrefix":"sk_agent","ttlExpiresAt":"2026-05-22T01:00:00.000Z","scopes":["messages:read"],"agentId":"agent-1","agentName":"Noel","serverId":"server-1"}"""
    ) : SlockAgentsApi {
        val calls = mutableListOf<String>()

        override suspend fun agents(): HomeNetworkResponse {
            calls += "GET agents"
            return ok(agentsBody)
        }

        override suspend fun agent(agentId: MemberId): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}"
            return ok(agentBody)
        }

        override suspend fun createAgent(body: String): HomeNetworkResponse {
            calls += "POST agents body=$body"
            return ok(createAgentBody)
        }

        override suspend fun updateAgent(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "PATCH agents/${agentId.value} body=$body"
            return ok(agentBody)
        }

        override suspend fun startAgent(agentId: MemberId): HomeNetworkResponse {
            calls += "POST agents/${agentId.value}/start"
            return ok(commandBody)
        }

        override suspend fun stopAgent(agentId: MemberId): HomeNetworkResponse {
            calls += "POST agents/${agentId.value}/stop"
            return ok(commandBody)
        }

        override suspend fun resetAgent(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "POST agents/${agentId.value}/reset body=$body"
            return ok(commandBody)
        }

        override suspend fun deleteAgent(agentId: MemberId): HomeNetworkResponse {
            calls += "DELETE agents/${agentId.value}"
            return ok(commandBody)
        }

        override suspend fun assignMachine(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "POST agents/${agentId.value}/assign-machine body=$body"
            return ok(commandBody)
        }

        override suspend fun agentDirectMessages(agentId: MemberId): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/agent-dms"
            return ok(agentDmsBody)
        }

        override suspend fun agentActivityLog(agentId: MemberId, limit: Int): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/activity-log limit=$limit"
            return ok(activityBody)
        }

        override suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String?): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/workspace-files dirPath=$dirPath"
            return ok(workspaceFilesBody)
        }

        override suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/workspace-files/read path=$path"
            return ok(workspaceFileReadBody)
        }

        override suspend fun agentSkills(agentId: MemberId): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/skills"
            return ok(skillsBody)
        }

        override suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse {
            calls += "GET agents/${agentId.value}/scopes"
            return ok(scopesBody)
        }

        override suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "PUT agents/${agentId.value}/scopes body=$body"
            return ok(scopesBody)
        }

        override suspend fun resetAgentScopes(agentId: MemberId): HomeNetworkResponse {
            calls += "PUT agents/${agentId.value}/scopes body={\"mode\":\"default\"}"
            return ok(scopesBody)
        }

        override suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "POST agents/${agentId.value}/bootstrap-tokens body=$body"
            return ok(bootstrapBody)
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }
}
