package ai.slock.compose.data

import ai.slock.compose.model.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class AgentsApiTest {
    @Test
    fun typedAgentsApiAcceptsCanonicalUpstreamShapes() = runBlocking {
        val transport = RecordingAgentsApi()
        val client = SlockAgentsApiClient(transport)
        val agentId = MemberId("agent-1")

        assertEquals(1, assertIs<DataResult.Content<AgentsPage>>(client.agents()).data.agentCount)
        val detail = assertIs<DataResult.Content<AgentSummary>>(client.agent(agentId)).data
        assertEquals("agent-1", detail.id)
        assertEquals("Noel Bot", detail.displayName)
        assertEquals("machine-1", detail.machineId)
        assertEquals("codex", detail.runtime)
        assertEquals("agent-2", assertIs<DataResult.Content<AgentSummary>>(client.createAgent("""{"name":"Noel"}""")).data.id)
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
        assertEquals(2, assertIs<DataResult.Content<AgentScopesSummary>>(client.agentScopes(agentId)).data.grantedCount)
        assertEquals(2, assertIs<DataResult.Content<AgentScopesSummary>>(client.updateAgentScopes(agentId, """{"mode":"default"}""")).data.grantedCount)
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
                """PUT agents/agent-1/scopes body={"mode":"default"}""",
                """POST agents/agent-1/bootstrap-tokens body={"ttlMs":60000}"""
            ),
            transport.calls
        )
    }

    @Test
    fun typedAgentsApiRejectsGuessedWrappersAndAliases() = runBlocking {
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
        private val agentBody: String = """{"id":"agent-1","name":"Agent One","displayName":"Noel Bot","status":"active","model":"gpt-5","runtime":"codex","machineId":"machine-1","activity":"idle","activityDetail":"Ready","deletedAt":null}""",
        private val createAgentBody: String = """{"id":"agent-2","name":"Noel","status":"active"}""",
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

        override suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse {
            calls += "POST agents/${agentId.value}/bootstrap-tokens body=$body"
            return ok(bootstrapBody)
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }
}
