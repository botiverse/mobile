package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentIssueReportRepositoryTest {
    private val serverId = ServerId("server-1")
    private val session =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@example.com", name = "artin", displayName = "Artin"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = serverId.value,
            servers = listOf(StoredAuthServer(id = serverId.value, name = "Botiverse", slug = "botiverse"))
        )

    @Test
    fun submitUsesAgentFeedbackWorkerAndQueuesTranscript() = runBlocking {
        val network = RecordingAgentIssueNetworkClient()
        var hashedBundle = ""
        val repository =
            AgentIssueReportRepository(
                sessionStore = InMemoryRaftAuthSessionStore(session),
                selectedServerStore = selectedServerStore(),
                networkClient = network,
                sha256Hex = { bundle ->
                    hashedBundle = bundle
                    "a".repeat(64)
                },
                feedbackBaseUrl = "https://feedback.example.test",
                nowMillis = { 1234L }
            )
        val result =
            repository.submit(
                AgentIssueReportSubmission(
                    server = ServerSummary(id = serverId, name = "Botiverse", slug = "botiverse"),
                    member =
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Android Visual Engineer",
                            handle = "Android-Developer-1",
                            kind = MemberKind.Agent
                        ),
                    detail =
                        AgentDetailSnapshot(
                            agentId = MemberId("agent-1"),
                            summary =
                                AgentDetailSummary(
                                    id = "agent-1",
                                    name = "Android-Developer-1",
                                    displayName = "Android Visual Engineer",
                                    status = "Working",
                                    runtime = "codex",
                                    model = "gpt-5",
                                    machineId = "machine-1",
                                    machineName = "Jiachengs-MacBook-Pro.local",
                                    daemonVersion = "0.0.0-dev"
                                ),
                            directMessages =
                                listOf(
                                    AgentDmSummary(
                                        id = "dm-1",
                                        peerId = "user-1",
                                        peerName = "Artin"
                                    )
                                ),
                            reminders = emptyList(),
                            activity =
                                listOf(
                                    AgentActivityEntry(
                                        id = "activity-1",
                                        type = "status",
                                        title = "Working",
                                        body = "Building report flow"
                                    )
                                )
                        ),
                    description = "Report button was not connected",
                    diagnostics = "agentId=agent-1\nstatus=Working",
                    includeRecentMessages = true,
                    includeLiveActivity = true,
                    includeActivityHistory = true,
                    includeRuntimeTranscript = true,
                    liveActivity =
                        listOf(
                            AgentActivityEntry(
                                id = "realtime:agent-1:1",
                                type = "status",
                                title = "Thinking",
                                body = "Inspecting current state"
                            )
                        )
                )
            )

        val content = assertIs<DataResult.Content<AgentIssueReportResult>>(result)
        assertEquals("report-1", content.data.reportId)
        assertEquals("artifact-1", content.data.artifactId)
        assertEquals("server-1", content.data.serverId)
        assertEquals("Report button was not connected", content.data.issueDescription)
        assertTrue(content.data.transcriptRequested)
        assertTrue(hashedBundle.contains("\"schemaVersion\":\"slock-feedback-export-v2\""))
        assertTrue(hashedBundle.contains("\"mobileDiagnostics\":\"agentId=agent-1\\nstatus=Working\""))
        assertTrue(hashedBundle.contains("\"recentMessages\":{\"included\":true"))
        assertTrue(hashedBundle.contains("\"message-from-dm\""))
        assertTrue(hashedBundle.contains("\"ephemeralActivityBuffer\":{\"included\":true"))
        assertTrue(hashedBundle.contains("\"durableTrajectoryLog\":{\"included\":true"))

        assertEquals(
            listOf(
                "GET messages/channel/dm-1?limit=100",
                "POST servers/server-1/scope-attestation",
                "POST https://feedback.example.test/api/reports",
                "PUT https://feedback.example.test/api/reports/report-1/object?token=upload",
                "POST https://feedback.example.test/api/reports/report-1/complete",
                "POST servers/server-1/feedback/report-1/receipt",
                "POST servers/server-1/machines/machine-1/agents/agent-1/feedback/report-1/transcript"
            ),
            network.calls
        )
        val createBody = Json.parseToJsonElement(network.createBody).jsonObject
        assertEquals("feedback-report:create", Json.parseToJsonElement(network.attestationBody).jsonObject["scope"]?.jsonPrimitive?.content)
        assertEquals("slock-mobile", createBody["source"]?.jsonPrimitive?.content)
        assertEquals("dm-1", createBody["metadata"]?.jsonObject?.get("dmChannelId")?.jsonPrimitive?.content)
        assertEquals(hashedBundle.encodeToByteArray().size.toString(), createBody["bundleSizeBytes"]?.jsonPrimitive?.content)
        assertEquals("a".repeat(64), createBody["bundleSha256"]?.jsonPrimitive?.content)
    }

    @Test
    fun serverMismatchFailsBeforeNetwork() = runBlocking {
        val network = RecordingAgentIssueNetworkClient()
        val repository =
            AgentIssueReportRepository(
                sessionStore = InMemoryRaftAuthSessionStore(session),
                selectedServerStore = selectedServerStore(),
                networkClient = network,
                sha256Hex = { "a".repeat(64) }
            )

        val result =
            repository.submit(
                AgentIssueReportSubmission(
                    server = ServerSummary(id = ServerId("server-2"), name = "Other"),
                    member = MemberProfile(MemberId("agent-1"), "Agent", "agent", MemberKind.Agent),
                    detail = null,
                    description = "Issue",
                    diagnostics = "agentId=agent-1",
                    includeActivityHistory = true,
                    includeRuntimeTranscript = false
                )
            )

        val error = assertIs<DataResult.Error>(result)
        assertEquals("agent_issue_report_server_mismatch", error.error.code)
        assertTrue(network.calls.isEmpty())
    }

    private fun selectedServerStore(): SelectedServerStore =
        SelectedServerStore(RaftShellStoreScope.create("agent-issue-test", serverId.value))
}

private class RecordingAgentIssueNetworkClient : RaftNetworkClient {
    val calls = mutableListOf<String>()
    var attestationBody = ""
    var createBody = ""

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse {
        calls += "$method $path${query.takeIf { it.isNotEmpty() }?.joinToString(prefix = "?", separator = "&") { "${it.first}=${it.second}" }.orEmpty()}"
        return when {
            path == "messages/channel/dm-1" ->
                HomeNetworkResponse(200, """{"messages":[{"id":"message-from-dm","content":"hello"}]}""")
            path.endsWith("/scope-attestation") -> {
                attestationBody = bodyText.orEmpty()
                HomeNetworkResponse(
                    200,
                    """{"attestation":"signed","scope":"feedback-report:create","expiresAt":"future"}"""
                )
            }
            path.endsWith("/transcript") -> HomeNetworkResponse(202, """{"accepted":true}""")
            path.endsWith("/receipt") -> HomeNetworkResponse(200, """{"ok":true}""")
            else -> HomeNetworkResponse(404, null)
        }
    }

    override suspend fun requestRawAbsoluteUrl(
        priority: DataRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?
    ): HomeNetworkResponse {
        calls += "$method $url"
        return when {
            method == "POST" && url.endsWith("/api/reports") -> {
                createBody = bodyText.orEmpty()
                HomeNetworkResponse(
                    200,
                    """{"id":"report-1","artifactId":"artifact-1","upload":{"method":"PUT","url":"https://feedback.example.test/api/reports/report-1/object?token=upload","headers":{"Content-Type":"application/json"}},"completeToken":"complete","expiresAt":"future"}"""
                )
            }
            method == "PUT" -> HomeNetworkResponse(200, null)
            method == "POST" && url.endsWith("/complete") -> HomeNetworkResponse(200, """{"ok":true}""")
            else -> HomeNetworkResponse(404, null)
        }
    }
}
