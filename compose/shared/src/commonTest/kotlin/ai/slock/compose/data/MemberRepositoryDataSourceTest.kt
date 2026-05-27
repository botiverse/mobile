package ai.slock.compose.data

import ai.slock.compose.model.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MemberRepositoryDataSourceTest {
    @Test
    fun agentDetailIncludesTypedSummaryAndLifecycleActions() = runBlocking {
        val network = FakeMemberNetworkClient()
        val repository = RealMemberRepository(sampleStore(), network)
        val agentId = MemberId("agent-1")

        val detail = repository.getAgentDetail(DataRequestPriority.Visible, agentId)
        val start = repository.startAgent(DataRequestPriority.Foreground, agentId)
        val stop = repository.stopAgent(DataRequestPriority.Foreground, agentId)
        val reset = repository.resetAgent(DataRequestPriority.Foreground, AgentResetRequest(agentId, AgentResetMode.Restart))
        val assign = repository.assignAgentMachine(
            DataRequestPriority.Foreground,
            AgentMachineAssignmentRequest(agentId, "machine-2")
        )
        val delete = repository.deleteAgent(DataRequestPriority.Foreground, agentId)

        assertTrue(detail is DataResult.Content)
        assertEquals("agent-1", detail.data.summary?.id)
        assertEquals("codex", detail.data.summary?.runtime)
        assertEquals("gpt-5", detail.data.summary?.model)
        assertEquals("machine-1", detail.data.summary?.machineId)
        assertEquals("Mac Studio", detail.data.summary?.machineName)
        assertEquals("idle", detail.data.summary?.activity)
        assertEquals(1, detail.data.directMessages.size)
        assertEquals(1, detail.data.reminders.size)
        assertEquals(true, detail.data.skills.groups.isNotEmpty())

        assertTrue(start is DataResult.Content)
        assertEquals("start", start.data.action)
        assertTrue(stop is DataResult.Content)
        assertEquals("stop", stop.data.action)
        assertTrue(reset is DataResult.Content)
        assertEquals(AgentResetMode.Restart, reset.data.resetMode)
        assertTrue(assign is DataResult.Content)
        assertEquals("machine-2", assign.data.machineId)
        assertTrue(delete is DataResult.Content)
        assertEquals("delete", delete.data.action)

        assertEquals(
            listOf(
                "GET agents/agent-1",
                "GET agents/agent-1/agent-dms",
                "GET reminders ownerAgentId=agent-1 status=scheduled",
                "GET agents/agent-1/activity-log limit=50",
                "GET agents",
                "GET agents/agent-1/skills",
                "POST agents/agent-1/start",
                "POST agents/agent-1/stop",
                """POST agents/agent-1/reset body={"mode":"restart"}""",
                """POST agents/agent-1/assign-machine body={"machineId":"machine-2"}""",
                "DELETE agents/agent-1"
            ),
            network.calls
        )
    }

    private class FakeMemberNetworkClient : SlockNetworkClient {
        val calls = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ai.slock.compose.model.ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val normalized = path.trim('/')
            return when {
                normalized == "agents/agent-1" && method == "GET" -> {
                    calls += "GET agents/agent-1"
                    ok(
                        """
                        {
                          "id":"agent-1",
                          "name":"noel",
                          "displayName":"Noel Bot",
                          "status":"active",
                          "activity":"idle",
                          "activityDetail":"Ready",
                          "runtimeProfile":{"current":{"machineId":"machine-1","machineName":"Mac Studio","runtime":"codex","model":"gpt-5"}}
                        }
                        """.trimIndent()
                    )
                }
                normalized == "agents/agent-1/agent-dms" && method == "GET" -> {
                    calls += "GET agents/agent-1/agent-dms"
                    ok("""[{"id":"dm-1","peerId":"user-1","peerName":"Artin"}]""")
                }
                normalized == "reminders" && method == "GET" -> {
                    val queryMap = query.toMap()
                    calls += "GET reminders ownerAgentId=${queryMap["ownerAgentId"]} status=${queryMap["status"]}"
                    ok(
                        """
                        {"reminders":[{"reminderId":"reminder-1","ownerAgentId":"agent-1","title":"Ping","fireAt":"2026-05-27T00:00:00Z","status":"scheduled"}]}
                        """.trimIndent()
                    )
                }
                normalized == "agents/agent-1/activity-log" && method == "GET" -> {
                    calls += "GET agents/agent-1/activity-log limit=${query.toMap()["limit"]}"
                    ok(
                        """
                        [{"timestamp":1710000000,"entry":{"kind":"agent_status","activity":"working","detail":"Running task"}}]
                        """.trimIndent()
                    )
                }
                normalized == "agents" && method == "GET" -> {
                    calls += "GET agents"
                    ok("""[{"id":"agent-1","name":"noel","status":"active","activity":"idle","activityDetail":"Ready"}]""")
                }
                normalized == "agents/agent-1/skills" && method == "GET" -> {
                    calls += "GET agents/agent-1/skills"
                    ok(
                        """
                        {"global":[{"name":"docs","displayName":"Docs","description":"Reads docs"}],"workspace":[]}
                        """.trimIndent()
                    )
                }
                normalized == "agents/agent-1/start" && method == "POST" -> {
                    calls += "POST agents/agent-1/start"
                    ok("""{"ok":true}""")
                }
                normalized == "agents/agent-1/stop" && method == "POST" -> {
                    calls += "POST agents/agent-1/stop"
                    ok("""{"ok":true}""")
                }
                normalized == "agents/agent-1/reset" && method == "POST" -> {
                    calls += "POST agents/agent-1/reset body=${bodyText.orEmpty()}"
                    ok("""{"ok":true}""")
                }
                normalized == "agents/agent-1/assign-machine" && method == "POST" -> {
                    calls += "POST agents/agent-1/assign-machine body=${bodyText.orEmpty()}"
                    ok("""{"ok":true}""")
                }
                normalized == "agents/agent-1" && method == "DELETE" -> {
                    calls += "DELETE agents/agent-1"
                    ok("""{"ok":true}""")
                }
                else -> error("Unhandled request $method $normalized query=$query")
            }
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }

    private fun sampleStore(session: StoredAuthSession = sampleSession()): SlockAuthSessionStore =
        InMemorySlockAuthSessionStore(session)

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user =
                StoredAuthUser(
                    id = "user-1",
                    email = "user@slock.ai",
                    name = "User",
                    displayName = "Slock User",
                    description = null,
                    avatarUrl = null,
                    emailVerified = true
                ),
            accessToken = "sample-access",
            refreshToken = "sample-refresh",
            selectedServerId = "server-1",
            selectedServerSlug = "slock-android",
            servers =
                listOf(
                    StoredAuthServer(
                        id = "server-1",
                        name = "Slock Android",
                        slug = "slock-android",
                        ownerId = "user-1",
                        onboardingAgentId = "agent-1",
                        plan = "team",
                        planDowngradedAt = null,
                        role = "owner",
                        createdAt = "2026-05-06T00:00:00Z"
                    )
                )
        )
}
