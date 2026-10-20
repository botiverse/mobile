package build.raft.app.data

import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.raftEpochMillisUtcIso
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

const val RAFT_AGENT_FEEDBACK_BASE_URL = "https://slock-trace-upload.botiverse.dev"

private const val AGENT_FEEDBACK_SCOPE = "feedback-report:create"
private const val AGENT_FEEDBACK_CONTENT_TYPE = "application/json"

private val agentIssueReportJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal data class AgentIssueReportSubmission(
    val server: ServerSummary,
    val member: MemberProfile,
    val detail: AgentDetailSnapshot?,
    val description: String,
    val diagnostics: String,
    val includeRecentMessages: Boolean = true,
    val includeLiveActivity: Boolean = true,
    val includeActivityHistory: Boolean = true,
    val includeRuntimeTranscript: Boolean = false,
    val liveActivity: List<AgentActivityEntry> = emptyList()
)

internal data class AgentIssueReportResult(
    val reportId: String,
    val artifactId: String?,
    val serverId: String,
    val issueDescription: String?,
    val transcriptRequested: Boolean
)

internal class AgentIssueReportRepository(
    private val sessionStore: RaftAuthSessionStore,
    private val selectedServerStore: SelectedServerStore,
    private val networkClient: RaftNetworkClient,
    private val sha256Hex: (String) -> String,
    private val feedbackBaseUrl: String = RAFT_AGENT_FEEDBACK_BASE_URL,
    private val nowMillis: () -> Long = ::defaultNowMillis,
    private val logger: (String) -> Unit = {}
) {
    suspend fun submit(submission: AgentIssueReportSubmission): DataResult<AgentIssueReportResult> {
        val session = sessionStore.readSession()
            ?: return DataResult.Error(ScreenError("agent_issue_report_unauthenticated", retryable = false))
        val serverId = submission.server.id
        if (!selectedServerStore.isSelected(serverId.value)) {
            return DataResult.Error(ScreenError("agent_issue_report_server_mismatch", retryable = false))
        }

        val recentMessages =
            when (val result = collectRecentMessages(submission, session)) {
                is DataResult.Content -> result.data
                else -> return result.toFailure()
            }
        val bundle = submission.toBundle(session, recentMessages, generatedAtEpochMs = nowMillis())
        val bundleJson = agentIssueReportJson.encodeToString(bundle)
        val bundleBytes = bundleJson.encodeToByteArray()
        val bundleSha256 = sha256Hex(bundleJson).trim().lowercase()
        if (!bundleSha256.matches(Regex("^[0-9a-f]{64}$"))) {
            return DataResult.Error(ScreenError("agent_issue_report_hash_failed", retryable = true))
        }

        return try {
            val attestation =
                when (val result = createAttestation(serverId)) {
                    is DataResult.Content -> result.data
                    else -> return result.toFailure()
                }
            val createRequest =
                AgentIssueReportCreateRequest(
                    attestation = attestation.attestation,
                    agentId = submission.member.id.value,
                    agentName = submission.member.displayName,
                    bundleFilename = submission.bundleFilename(nowMillis()),
                    bundleContentType = AGENT_FEEDBACK_CONTENT_TYPE,
                    bundleSizeBytes = bundleBytes.size,
                    bundleSha256 = bundleSha256,
                    source = "slock-mobile",
                    category = "bug",
                    title = "Issue report for ${submission.member.displayName}",
                    description = submission.description.trim().takeIf { it.isNotBlank() },
                    appVersion = null,
                    daemonVersion = submission.detail?.summary?.daemonVersion,
                    metadata = submission.toMetadata(recentMessages.dmChannelId)
                )
            val created =
                when (val result = createReport(createRequest)) {
                    is DataResult.Content -> result.data
                    else -> return result.toFailure()
                }
            uploadBundle(created, bundleJson).failureOrNull()?.let { return it }
            completeReport(created).failureOrNull()?.let { return it }

            requestReceipt(serverId, created.id)
            val machineId = submission.detail?.summary?.machineId?.trim().orEmpty()
            val transcriptRequested = submission.includeRuntimeTranscript && machineId.isNotBlank()
            if (transcriptRequested) {
                requestTranscript(
                    serverId = serverId,
                    machineId = machineId,
                    agentId = submission.member.id.value,
                    reportId = created.id
                )
            }
            logger(
                "agent_issue_report_complete report=${created.id.take(8)} " +
                    "artifact=${created.artifactId?.take(8).orEmpty()} transcriptRequested=$transcriptRequested"
            )
            DataResult.Content(
                AgentIssueReportResult(
                    reportId = created.id,
                    artifactId = created.artifactId,
                    serverId = serverId.value,
                    issueDescription = submission.description.trim().takeIf { it.isNotBlank() },
                    transcriptRequested = transcriptRequested
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger("agent_issue_report_exception type=${throwable::class.simpleName}")
            DataResult.Error(ScreenError("agent_issue_report_failed", retryable = true))
        }
    }

    private suspend fun createAttestation(serverId: ServerId): DataResult<AgentIssueScopeAttestation> =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/scope-attestation",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = agentIssueReportJson.encodeToString(AgentIssueScopeAttestationRequest())
        ).decode("agent_issue_report_attestation_failed")

    private suspend fun collectRecentMessages(
        submission: AgentIssueReportSubmission,
        session: StoredAuthSession
    ): DataResult<AgentIssueRecentMessages> {
        if (!submission.includeRecentMessages) return DataResult.Content(AgentIssueRecentMessages())
        val reporterId = session.user.id?.trim().orEmpty()
        val dmChannelId =
            submission.detail?.directMessages.orEmpty()
                .firstOrNull { reporterId.isNotBlank() && it.peerId == reporterId }
                ?.id
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return DataResult.Content(AgentIssueRecentMessages())
        val response =
            networkClient.request(
                priority = DataRequestPriority.Foreground,
                path = "messages/channel/$dmChannelId",
                method = "GET",
                query = listOf("limit" to "100"),
                serverIdOverride = submission.server.id
            )
        if (!response.isSuccessful) {
            return DataResult.Error(
                ScreenError("agent_issue_report_messages_failed", retryable = response.code == 0 || response.code >= 500)
            )
        }
        val body = response.bodyText?.takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError("agent_issue_report_messages_failed", retryable = true))
        return try {
            val messages = agentIssueReportJson.parseToJsonElement(body).jsonObject["messages"] as? JsonArray
            DataResult.Content(AgentIssueRecentMessages(dmChannelId = dmChannelId, messages = messages?.toList().orEmpty()))
        } catch (_: Throwable) {
            DataResult.Error(ScreenError("agent_issue_report_messages_failed_decode", retryable = false))
        }
    }

    private suspend fun createReport(request: AgentIssueReportCreateRequest): DataResult<AgentIssueReportCreateResponse> =
        networkClient.requestRawAbsoluteUrl(
            priority = DataRequestPriority.Foreground,
            url = "${feedbackBaseUrl.trimEnd('/')}/api/reports",
            method = "POST",
            headers = mapOf("Content-Type" to AGENT_FEEDBACK_CONTENT_TYPE),
            bodyText = agentIssueReportJson.encodeToString(request)
        ).decode("agent_issue_report_create_failed")

    private suspend fun uploadBundle(
        created: AgentIssueReportCreateResponse,
        bundleJson: String
    ): DataResult<Unit> {
        val response =
            networkClient.requestRawAbsoluteUrl(
                priority = DataRequestPriority.Foreground,
                url = created.upload.url,
                method = created.upload.method,
                headers = created.upload.headers,
                bodyText = bundleJson
            )
        return response.toUnitResult("agent_issue_report_upload_failed")
    }

    private suspend fun completeReport(created: AgentIssueReportCreateResponse): DataResult<Unit> {
        val response =
            networkClient.requestRawAbsoluteUrl(
                priority = DataRequestPriority.Foreground,
                url = "${feedbackBaseUrl.trimEnd('/')}/api/reports/${created.id}/complete",
                method = "POST",
                headers = mapOf("Content-Type" to AGENT_FEEDBACK_CONTENT_TYPE),
                bodyText = agentIssueReportJson.encodeToString(AgentIssueReportCompleteRequest(created.completeToken))
            )
        return response.toUnitResult("agent_issue_report_complete_failed")
    }

    private suspend fun requestReceipt(serverId: ServerId, reportId: String) {
        val response =
            networkClient.request(
                priority = DataRequestPriority.Background,
                path = "servers/${serverId.value}/feedback/$reportId/receipt",
                method = "POST",
                serverIdOverride = serverId,
                bodyText = "{}"
            )
        logger("agent_issue_report_receipt code=${response.code}")
    }

    private suspend fun requestTranscript(
        serverId: ServerId,
        machineId: String,
        agentId: String,
        reportId: String
    ) {
        val response =
            networkClient.request(
                priority = DataRequestPriority.Background,
                path = "servers/${serverId.value}/machines/$machineId/agents/$agentId/feedback/$reportId/transcript",
                method = "POST",
                serverIdOverride = serverId,
                bodyText = "{}"
            )
        logger("agent_issue_report_transcript code=${response.code}")
    }
}

@Serializable
private data class AgentIssueScopeAttestationRequest(
    val scope: String = AGENT_FEEDBACK_SCOPE
)

@Serializable
private data class AgentIssueScopeAttestation(
    val attestation: String,
    val scope: String,
    val expiresAt: String
)

@Serializable
private data class AgentIssueReportCreateRequest(
    val attestation: String,
    val agentId: String,
    val agentName: String,
    val bundleFilename: String,
    val bundleContentType: String,
    val bundleSizeBytes: Int,
    val bundleSha256: String,
    val source: String,
    val category: String,
    val title: String,
    val description: String?,
    val appVersion: String?,
    val daemonVersion: String?,
    val metadata: AgentIssueReportMetadata
)

@Serializable
private data class AgentIssueReportMetadata(
    val schemaVersion: String,
    val dmChannelId: String?,
    val deploymentEnv: String?,
    val includes: AgentIssueReportIncludes,
    val transcript: AgentIssueReportTranscriptMetadata
)

@Serializable
private data class AgentIssueReportIncludes(
    val recentMessages: Boolean,
    val activityLog: Boolean,
    val trajectoryLog: Boolean,
    val ephemeralActivityBuffer: Boolean,
    val durableTrajectoryLog: Boolean,
    val runtimeSessionTranscript: Boolean
)

@Serializable
private data class AgentIssueReportTranscriptMetadata(
    val attachmentMode: String,
    val requested: Boolean,
    val requestable: Boolean,
    val skippedReason: String?
)

@Serializable
private data class AgentIssueReportCreateResponse(
    val id: String,
    val artifactId: String? = null,
    val upload: AgentIssueReportUpload,
    val completeToken: String,
    val expiresAt: String
)

@Serializable
private data class AgentIssueReportUpload(
    val method: String,
    val url: String,
    val headers: Map<String, String>
)

@Serializable
private data class AgentIssueReportCompleteRequest(
    val completeToken: String
)

private data class AgentIssueRecentMessages(
    val dmChannelId: String? = null,
    val messages: List<JsonElement> = emptyList()
)

@Serializable
private data class AgentIssueReportBundle(
    val schemaVersion: String = "slock-feedback-export-v2",
    val generatedAt: String,
    val generatedAtEpochMs: Long,
    val appVersion: String? = null,
    val daemonVersion: String?,
    val issue: AgentIssueBundleIssue,
    val reporter: AgentIssueBundleReporter,
    val server: AgentIssueBundleServer,
    val agent: AgentIssueBundleAgent,
    val logs: AgentIssueBundleLogs,
    val activityLog: List<AgentIssueBundleActivity>?,
    val trajectoryLog: List<AgentIssueBundleActivity>?,
    val browser: AgentIssueBundleClient,
    val mobileDiagnostics: String
)

@Serializable
private data class AgentIssueBundleIssue(val description: String?)

@Serializable
private data class AgentIssueBundleReporter(
    val id: String?,
    val email: String?,
    val name: String?,
    val displayName: String?
)

@Serializable
private data class AgentIssueBundleServer(
    val id: String?,
    val slug: String?,
    val name: String?
)

@Serializable
private data class AgentIssueBundleAgent(
    val id: String,
    val name: String,
    val displayName: String?,
    val description: String?,
    val status: String?,
    val runtime: String,
    val model: String,
    val reasoningEffort: String?,
    val machineId: String?,
    val machineName: String?,
    val machineStatus: String?
)

@Serializable
private data class AgentIssueBundleLogs(
    val recentMessages: AgentIssueBundleRecentMessagesSection,
    val ephemeralActivityBuffer: AgentIssueBundleActivitySection,
    val durableTrajectoryLog: AgentIssueBundleActivitySection
)

@Serializable
private data class AgentIssueBundleRecentMessagesSection(
    val included: Boolean,
    val source: String,
    val durability: String,
    val entries: List<JsonElement>?
)

@Serializable
private data class AgentIssueBundleActivitySection(
    val included: Boolean,
    val source: String,
    val durability: String,
    val entries: List<AgentIssueBundleActivity>?
)

@Serializable
private data class AgentIssueBundleActivity(
    val id: String,
    val type: String,
    val title: String,
    val body: String?,
    val timestamp: String?,
    val details: Map<String, String>,
    val toolName: String?,
    val toolInput: String?,
    val statusActivity: String?,
    val statusDetail: String?
)

@Serializable
private data class AgentIssueBundleClient(
    val url: String = "",
    val userAgent: String = "Raft mobile",
    val language: String = "",
    val languages: List<String> = emptyList(),
    val platform: String = "mobile",
    val timezone: String = "unknown",
    val viewport: AgentIssueBundleSize = AgentIssueBundleSize(),
    val screen: AgentIssueBundleSize = AgentIssueBundleSize()
)

@Serializable
private data class AgentIssueBundleSize(val width: Int = 0, val height: Int = 0)

private fun AgentIssueReportSubmission.toBundle(
    session: StoredAuthSession,
    recentMessages: AgentIssueRecentMessages,
    generatedAtEpochMs: Long
): AgentIssueReportBundle {
    val summary = detail?.summary
    val liveActivity =
        liveActivity
            .takeIf { includeLiveActivity }
            .orEmpty()
            .map { it.toIssueBundleActivity() }
    val durableActivity =
        detail?.activity.orEmpty()
            .takeIf { includeActivityHistory }
            .orEmpty()
            .filterNot { it.id.startsWith("realtime:") }
            .map { it.toIssueBundleActivity() }
    return AgentIssueReportBundle(
        generatedAt = raftEpochMillisUtcIso(generatedAtEpochMs),
        generatedAtEpochMs = generatedAtEpochMs,
        daemonVersion = summary?.daemonVersion,
        issue = AgentIssueBundleIssue(description.trim().takeIf { it.isNotBlank() }),
        reporter =
            AgentIssueBundleReporter(
                id = session.user.id,
                email = session.user.email,
                name = session.user.name,
                displayName = session.user.displayName
            ),
        server = AgentIssueBundleServer(server.id.value, server.slug, server.name),
        agent =
            AgentIssueBundleAgent(
                id = member.id.value,
                name = summary?.name?.takeIf { it.isNotBlank() } ?: member.handle,
                displayName = summary?.displayName ?: member.displayName,
                description = summary?.description ?: member.description,
                status = summary?.status,
                runtime = summary?.runtime.orEmpty(),
                model = summary?.model.orEmpty(),
                reasoningEffort = summary?.reasoning,
                machineId = summary?.machineId,
                machineName = summary?.machineName,
                machineStatus = null
            ),
        logs =
            AgentIssueBundleLogs(
                recentMessages =
                    AgentIssueBundleRecentMessagesSection(
                        included = includeRecentMessages,
                        source = "server_dm_history",
                        durability = "durable",
                        entries = recentMessages.messages.takeIf {
                            includeRecentMessages && recentMessages.dmChannelId != null
                        }
                    ),
                ephemeralActivityBuffer =
                    AgentIssueBundleActivitySection(
                        included = includeLiveActivity,
                        source = "client_socket_buffer",
                        durability = "ephemeral",
                        entries = liveActivity.takeIf { includeLiveActivity }
                    ),
                durableTrajectoryLog =
                    AgentIssueBundleActivitySection(
                        included = includeActivityHistory,
                        source = "server_activity_log",
                        durability = "durable",
                        entries = durableActivity.takeIf { includeActivityHistory }
                    )
            ),
        activityLog = liveActivity.takeIf { includeLiveActivity },
        trajectoryLog = durableActivity.takeIf { includeActivityHistory },
        browser = AgentIssueBundleClient(),
        mobileDiagnostics = diagnostics
    )
}

private fun AgentActivityEntry.toIssueBundleActivity(): AgentIssueBundleActivity =
    AgentIssueBundleActivity(
        id = id,
        type = type,
        title = title,
        body = body,
        timestamp = timestamp,
        details = details.toMap(),
        toolName = toolName,
        toolInput = toolInput,
        statusActivity = statusActivity,
        statusDetail = statusDetail
    )

private fun AgentIssueReportSubmission.toMetadata(dmChannelId: String?): AgentIssueReportMetadata {
    val machineId = detail?.summary?.machineId?.trim().orEmpty()
    val transcriptRequested = includeRuntimeTranscript && machineId.isNotBlank()
    return AgentIssueReportMetadata(
        schemaVersion = "slock-feedback-export-v2",
        dmChannelId = dmChannelId,
        deploymentEnv = null,
        includes =
            AgentIssueReportIncludes(
                recentMessages = includeRecentMessages,
                activityLog = includeLiveActivity,
                trajectoryLog = includeActivityHistory,
                ephemeralActivityBuffer = includeLiveActivity,
                durableTrajectoryLog = includeActivityHistory,
                runtimeSessionTranscript = transcriptRequested
            ),
        transcript =
            AgentIssueReportTranscriptMetadata(
                attachmentMode = "async_trace_bundle",
                requested = transcriptRequested,
                requestable = machineId.isNotBlank(),
                skippedReason = if (includeRuntimeTranscript && machineId.isBlank()) "agent has no machine" else null
            )
    )
}

private fun AgentIssueReportSubmission.bundleFilename(nowMillis: Long): String {
    val safeName =
        member.displayName
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "agent" }
    return "slock-feedback-export-$safeName-$nowMillis.json"
}

private inline fun <reified T> HomeNetworkResponse.decode(errorCode: String): DataResult<T> {
    if (!isSuccessful) return DataResult.Error(ScreenError(errorCode, retryable = code == 0 || code >= 500))
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(errorCode, retryable = true))
    return try {
        DataResult.Content(agentIssueReportJson.decodeFromString<T>(body))
    } catch (_: Throwable) {
        DataResult.Error(ScreenError("${errorCode}_decode", retryable = false))
    }
}

private fun HomeNetworkResponse.toUnitResult(errorCode: String): DataResult<Unit> =
    if (isSuccessful) {
        DataResult.Content(Unit)
    } else {
        DataResult.Error(ScreenError(errorCode, retryable = code == 0 || code >= 500))
    }

private fun <T> DataResult<T>.toFailure(): DataResult<Nothing> =
    when (this) {
        is DataResult.Content -> error("Content cannot be converted to failure")
        is DataResult.Empty -> DataResult.Empty(reason)
        is DataResult.Error -> DataResult.Error(error)
        is DataResult.NoPermission -> DataResult.NoPermission(blocker)
    }

private fun DataResult<Unit>.failureOrNull(): DataResult<Nothing>? =
    when (this) {
        is DataResult.Content -> null
        is DataResult.Empty -> DataResult.Empty(reason)
        is DataResult.Error -> DataResult.Error(error)
        is DataResult.NoPermission -> DataResult.NoPermission(blocker)
    }
