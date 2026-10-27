package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Server-shaped fixtures (botiverse/slock staging 601f5e536c302fe9a830d24971e3f28cab86f0d0):
 * - trace-upload worker `POST /api/reports` response: packages/trace-upload-worker/src/feedback/reports.ts
 * - `POST /api/product-feedback` 201 body `ProductFeedbackReceipt`: packages/server/src/services/productFeedbackService.ts
 * - transcript route body: packages/server/src/routes/servers.ts (`feedback/:reportId/transcript`)
 */
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
    fun submitUploadsBundleFilesTicketAndRequestsTranscript() = runTest {
        val network = RecordingAgentIssueNetworkClient()
        var hashedBundle = ""
        val repository = repository(network, sha256Hex = { bundle -> hashedBundle = bundle; "a".repeat(64) })

        val result = repository.submit(submission(canIncludeMachineLog = true, includeMachineLogTail = true))

        val content = assertIs<DataResult.Content<AgentIssueReportResult>>(result)
        assertEquals("report-1", content.data.reportId)
        assertEquals("artifact-1", content.data.artifactId)
        assertEquals("server-1", content.data.serverId)
        assertEquals("Report button was not connected", content.data.issueDescription)
        assertTrue(content.data.transcriptRequested)
        assertEquals(AgentIssueTranscriptRequestState.Requested, content.data.transcriptRequestState)
        assertEquals(RECEIPT_TICKET_ID, content.data.ticketId)
        assertTrue(hashedBundle.contains("\"schemaVersion\":\"slock-feedback-export-v2\""))
        assertTrue(hashedBundle.contains("\"mobileDiagnostics\":\"agentId=agent-1\\nstatus=Working\""))
        assertTrue(hashedBundle.contains("\"message-from-dm\""))

        assertEquals(
            listOf(
                "GET messages/channel/dm-1?limit=100",
                "POST servers/server-1/scope-attestation",
                "POST https://feedback.example.test/api/reports",
                "PUT https://feedback.example.test/api/reports/report-1/object?token=upload",
                "POST https://feedback.example.test/api/reports/report-1/complete",
                "POST servers/server-1/machines/machine-1/agents/agent-1/feedback/report-1/transcript",
                "POST product-feedback"
            ),
            network.calls
        )
        assertEquals("feedback-report:create", network.attestationBody["scope"]?.jsonPrimitive?.content)
        val create = network.createBody
        assertEquals("slock-mobile", create["source"]?.jsonPrimitive?.content)
        assertEquals("bug", create["category"]?.jsonPrimitive?.content)
        assertEquals("Issue report for Android Visual Engineer (localized)", create["title"]?.jsonPrimitive?.content)
        assertEquals(hashedBundle.encodeToByteArray().size.toString(), create["bundleSizeBytes"]?.jsonPrimitive?.content)
        assertEquals("a".repeat(64), create["bundleSha256"]?.jsonPrimitive?.content)
        val metadata = create["metadata"]!!.jsonObject
        assertEquals("dm-1", metadata["dmChannelId"]?.jsonPrimitive?.content)
        assertEquals("1970-01-01T00:00:01.234Z", metadata["reportGeneratedAt"]?.jsonPrimitive?.content)
        assertEquals("true", metadata["includes"]?.jsonObject?.get("runtimeSessionTranscript")?.jsonPrimitive?.content)
        assertEquals("async_trace_bundle", metadata["transcript"]?.jsonObject?.get("attachmentMode")?.jsonPrimitive?.content)
        assertEquals("true", metadata["transcript"]?.jsonObject?.get("requestable")?.jsonPrimitive?.content)

        val transcript = network.transcriptBody!!
        assertEquals("1970-01-01T00:00:01.234Z", transcript["reportGeneratedAt"]?.jsonPrimitive?.content)
        assertEquals("true", transcript["includeMachineLogTail"]?.jsonPrimitive?.content)

        val ticket = network.ticketBody!!
        assertEquals("problem", ticket["type"]?.jsonPrimitive?.content)
        assertEquals(
            "Issue report for Android Visual Engineer (localized)\n\nReport button was not connected",
            ticket["message"]?.jsonPrimitive?.content
        )
        assertEquals("submission-1", ticket["submission_id"]?.jsonPrimitive?.content)
        assertEquals("false", ticket["may_contact"]?.jsonPrimitive?.content)
        assertEquals("report-1", ticket["feedback_report_id"]?.jsonPrimitive?.content)
        val ticketMetadata = Json.parseToJsonElement(ticket["metadata"]!!.jsonPrimitive.content).jsonObject
        assertEquals("zh-CN", ticketMetadata["locale"]?.jsonPrimitive?.content)
        assertFalse(ticketMetadata.containsKey("client_kind"))
        assertTrue(network.ticketHeaders.isEmpty())
        assertEquals(serverId, network.ticketServerId)
    }

    @Test
    fun ticketIsFiledIntoTheAppsFeedbackSourceLikeSettingsFeedback() = runTest {
        val network = RecordingAgentIssueNetworkClient()
        repository(network, feedbackClientKind = "android", feedbackClientVersion = "1.16.0").submit(submission())

        assertEquals("android", network.ticketHeaders[AGENT_ISSUE_CLIENT_KIND_HEADER])
        val metadata = Json.parseToJsonElement(network.ticketBody!!["metadata"]!!.jsonPrimitive.content).jsonObject
        assertEquals("android", metadata["client_kind"]?.jsonPrimitive?.content)
        assertEquals("android", metadata["platform"]?.jsonPrimitive?.content)
        assertEquals("1.16.0", metadata["client_version"]?.jsonPrimitive?.content)
        assertEquals("zh-CN", metadata["locale"]?.jsonPrimitive?.content)
    }

    @Test
    fun machineLogTailIsOmittedUnlessTheReporterOwnsTheMachine() = runTest {
        listOf(
            Triple(false, true, "not owner"),
            Triple(true, false, "unticked")
        ).forEach { (canInclude, include, label) ->
            val network = RecordingAgentIssueNetworkClient()
            repository(network).submit(submission(canIncludeMachineLog = canInclude, includeMachineLogTail = include))
            val transcript = assertNotNullBody(network.transcriptBody, label)
            assertFalse(transcript.containsKey("includeMachineLogTail"), "$label: includeMachineLogTail must be omitted")
        }
    }

    @Test
    fun agentWithoutMachineSkipsTranscriptAndOmitsTranscriptMetadata() = runTest {
        val network = RecordingAgentIssueNetworkClient()
        val result = repository(network).submit(submission(machineId = null))

        val content = assertIs<DataResult.Content<AgentIssueReportResult>>(result)
        assertFalse(content.data.transcriptRequested)
        assertNull(content.data.transcriptRequestState)
        assertTrue(network.calls.none { it.endsWith("/transcript") })
        val metadata = network.createBody["metadata"]!!.jsonObject
        assertFalse(metadata.containsKey("transcript"))
        assertFalse(metadata["includes"]!!.jsonObject.containsKey("runtimeSessionTranscript"))
    }

    @Test
    fun failedTicketStillReportsTheUploadAndRetryReusesTheSubmissionId() = runTest {
        val network = RecordingAgentIssueNetworkClient(ticketResponses = mutableListOf(HomeNetworkResponse(503, """{"code":"feedback_service_unavailable"}""")))
        val repository = repository(network)

        val content = assertIs<DataResult.Content<AgentIssueReportResult>>(repository.submit(submission()))
        assertNull(content.data.ticketId)
        val retryRequest = assertNotNullBody(content.data.ticketRequest, "ticket request")

        val retried = repository.fileTicket(serverId, retryRequest)

        assertEquals(RECEIPT_TICKET_ID, assertIs<DataResult.Content<String>>(retried).data)
        assertEquals(
            listOf("submission-1", "submission-1"),
            network.ticketSubmissionIds
        )
    }

    @Test
    fun transcriptRequestStateFollowsTheHttpAnswer() = runTest {
        listOf(
            HomeNetworkResponse(202, """{"accepted":true}""") to AgentIssueTranscriptRequestState.Requested,
            HomeNetworkResponse(403, """{"code":"machine_log_tail_owner_required"}""") to AgentIssueTranscriptRequestState.RequestFailed,
            HomeNetworkResponse(0, null) to AgentIssueTranscriptRequestState.RequestUnconfirmed
        ).forEach { (response, expected) ->
            val network = RecordingAgentIssueNetworkClient(transcriptResponse = response)
            val content = assertIs<DataResult.Content<AgentIssueReportResult>>(repository(network).submit(submission()))
            assertEquals(expected, content.data.transcriptRequestState, "code=${response.code}")
            assertEquals(RECEIPT_TICKET_ID, content.data.ticketId)
        }
    }

    @Test
    fun transcriptWithoutAnswerWithinTheCapIsUnconfirmed() = runTest {
        val network = RecordingAgentIssueNetworkClient(transcriptHangs = true)

        val content = assertIs<DataResult.Content<AgentIssueReportResult>>(repository(network).submit(submission()))

        assertEquals(AgentIssueTranscriptRequestState.RequestUnconfirmed, content.data.transcriptRequestState)
        assertEquals(RECEIPT_TICKET_ID, content.data.ticketId)
    }

    @Test
    fun ticketReceiptParsesServerShape() {
        val receipt =
            Json { ignoreUnknownKeys = true }.decodeFromString(
                AgentIssueTicketReceipt.serializer(),
                RECEIPT_JSON
            )
        assertEquals(RECEIPT_TICKET_ID, receipt.id)
        assertEquals("open", receipt.status)
        assertEquals("FB-1042", receipt.reference)
        assertEquals(0, receipt.attachments)
    }

    @Test
    fun serverMismatchFailsBeforeNetwork() = runTest {
        val network = RecordingAgentIssueNetworkClient()

        val result =
            repository(network).submit(
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

    private fun repository(
        network: RecordingAgentIssueNetworkClient,
        sha256Hex: (String) -> String = { "a".repeat(64) },
        feedbackClientKind: String = "",
        feedbackClientVersion: String = ""
    ): AgentIssueReportRepository =
        AgentIssueReportRepository(
            sessionStore = InMemoryRaftAuthSessionStore(session),
            selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("agent-issue-test", serverId.value)),
            networkClient = network,
            sha256Hex = sha256Hex,
            feedbackBaseUrl = "https://feedback.example.test",
            nowMillis = { 1234L },
            newSubmissionId = { "submission-1" },
            feedbackClientKind = feedbackClientKind,
            feedbackClientVersion = feedbackClientVersion
        )

    private fun submission(
        machineId: String? = "machine-1",
        canIncludeMachineLog: Boolean = false,
        includeMachineLogTail: Boolean = false
    ): AgentIssueReportSubmission =
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
                            machineId = machineId,
                            machineName = "Jiachengs-MacBook-Pro.local",
                            daemonVersion = "0.0.0-dev"
                        ),
                    directMessages = listOf(AgentDmSummary(id = "dm-1", peerId = "user-1", peerName = "Artin")),
                    reminders = emptyList(),
                    activity =
                        listOf(
                            AgentActivityEntry(id = "activity-1", type = "status", title = "Working", body = "Building report flow")
                        )
                ),
            description = "Report button was not connected",
            diagnostics = "agentId=agent-1\nstatus=Working",
            includeRuntimeTranscript = true,
            liveActivity =
                listOf(AgentActivityEntry(id = "realtime:agent-1:1", type = "status", title = "Thinking", body = "Inspecting")),
            title = "Issue report for Android Visual Engineer (localized)",
            locale = "zh-CN",
            includeMachineLogTail = includeMachineLogTail,
            canIncludeMachineLog = canIncludeMachineLog
        )
}

private const val RECEIPT_TICKET_ID = "3f6c2a10-8d4b-4e2f-9a1c-5b7d8e9f0a1b"
private const val RECEIPT_JSON = """{"id":"$RECEIPT_TICKET_ID","status":"open","reference":"FB-1042","attachments":0}"""

private fun <T : Any> assertNotNullBody(value: T?, label: String): T = value ?: error("missing $label")

private class RecordingAgentIssueNetworkClient(
    private val transcriptResponse: HomeNetworkResponse = HomeNetworkResponse(202, """{"accepted":true}"""),
    private val transcriptHangs: Boolean = false,
    private val ticketResponses: MutableList<HomeNetworkResponse> = mutableListOf()
) : RaftNetworkClient {
    val calls = mutableListOf<String>()
    var attestationBody = JsonObject(emptyMap())
    var createBody = JsonObject(emptyMap())
    var transcriptBody: JsonObject? = null
    var ticketBody: JsonObject? = null
    var ticketServerId: ServerId? = null
    val ticketSubmissionIds = mutableListOf<String>()
    var ticketHeaders: Map<String, String> = emptyMap()

    override suspend fun requestWithHeaders(
        priority: DataRequestPriority,
        path: String,
        headers: Map<String, String>,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse {
        if (path == "product-feedback") ticketHeaders = headers
        return request(priority, path, method, query, serverIdOverride, bodyText)
    }

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
                attestationBody = Json.parseToJsonElement(bodyText.orEmpty()).jsonObject
                HomeNetworkResponse(
                    200,
                    """{"attestation":"signed","scope":"feedback-report:create","expiresAt":"2026-10-27T00:05:00.000Z"}"""
                )
            }
            path.endsWith("/transcript") -> {
                transcriptBody = Json.parseToJsonElement(bodyText.orEmpty()).jsonObject
                if (transcriptHangs) awaitCancellation()
                transcriptResponse
            }
            path == "product-feedback" -> {
                val body = Json.parseToJsonElement(bodyText.orEmpty()).jsonObject
                ticketBody = body
                ticketServerId = serverIdOverride
                ticketSubmissionIds += body["submission_id"]?.jsonPrimitive?.content.orEmpty()
                ticketResponses.removeFirstOrNull() ?: HomeNetworkResponse(201, RECEIPT_JSON)
            }
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
                createBody = Json.parseToJsonElement(bodyText.orEmpty()).jsonObject
                HomeNetworkResponse(
                    200,
                    """{"id":"report-1","artifactId":"artifact-1","upload":{"method":"PUT","url":"https://feedback.example.test/api/reports/report-1/object?token=upload","headers":{"Content-Type":"application/json"}},"completeToken":"complete","expiresAt":"2026-10-27T00:15:00.000Z"}"""
                )
            }
            method == "PUT" -> HomeNetworkResponse(200, null)
            method == "POST" && url.endsWith("/complete") -> HomeNetworkResponse(200, """{"ok":true}""")
            else -> HomeNetworkResponse(404, null)
        }
    }
}
