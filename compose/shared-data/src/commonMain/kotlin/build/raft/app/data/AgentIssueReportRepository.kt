package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.raftEpochMillisUtcIso
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val RAFT_AGENT_FEEDBACK_BASE_URL = "https://slock-trace-upload.botiverse.dev"

private const val AGENT_FEEDBACK_SCOPE = "feedback-report:create"
private const val AGENT_FEEDBACK_CONTENT_TYPE = "application/json"

private val agentIssueReportJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Request bodies whose optional fields web omits (`undefined`) rather than sends as `null`:
 * the transcript route rejects `includeMachineLogTail: null` with 400
 * (slock packages/server/src/routes/servers.ts, `feedback/:reportId/transcript`).
 */
private val agentIssueRequestJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Web `TRANSCRIPT_REQUEST_CONFIRM_CAP_MS` (packages/web/src/feedback/transcriptRequestState.ts). */
@RaftCoreInternalApi const val AGENT_ISSUE_TRANSCRIPT_CONFIRM_CAP_MILLIS = 10_000L

/**
 * `RAFT_PRIVACY_MODE` and the agent report (botiverse/slock docs/observability/privacy-mode.md, web
 * AgentDetailPanel + ReportIssueDialog `isPrivacyModeOn()`): the bundle goes to Raft's trace-upload
 * worker before any ticket exists, so it is sent only once this environment's client config has
 * answered "off" ([RaftTelemetryPrivacy], published by [SharedClientConfigStore]). While the answer
 * is unknown or "on", the entry is hidden and nothing is uploaded.
 */
@RaftCoreInternalApi fun agentIssueReportAllowed(mode: RaftPrivacyMode): Boolean = mode == RaftPrivacyMode.Off

/** The submit error for a report privacy mode does not let out (null when it may go). */
@RaftCoreInternalApi fun agentIssueReportPrivacyError(mode: RaftPrivacyMode): ScreenError? =
    when (mode) {
        RaftPrivacyMode.Off -> null
        RaftPrivacyMode.On -> ScreenError(AGENT_ISSUE_REPORT_PRIVACY_MODE_ON, retryable = false)
        RaftPrivacyMode.Unknown -> ScreenError(AGENT_ISSUE_REPORT_PRIVACY_MODE_UNKNOWN, retryable = false)
    }

@RaftCoreInternalApi const val AGENT_ISSUE_REPORT_PRIVACY_MODE_ON = "agent_issue_report_privacy_mode_on"
@RaftCoreInternalApi const val AGENT_ISSUE_REPORT_PRIVACY_MODE_UNKNOWN = "agent_issue_report_privacy_mode_unknown"

/**
 * Closed range presets for Report issue's Computer diagnostics bundle (slock #16580,
 * `FEEDBACK_DIAGNOSTICS_BUNDLE_RANGES`). The server turns the preset into `{ scope, from, to }`
 * itself; clients never send timestamps or paths.
 */
@RaftCoreInternalApi enum class AgentIssueDiagnosticsBundleRange(val wire: String) {
    ReportWindow("report_window"),
    Last24h("last_24h"),
    Last7d("last_7d"),
}

@RaftCoreInternalApi data class AgentIssueReportSubmission(
    val server: ServerSummary,
    val member: MemberProfile,
    val detail: AgentDetailSnapshot?,
    val description: String,
    val diagnostics: String,
    val includeRecentMessages: Boolean = true,
    val includeLiveActivity: Boolean = true,
    val includeActivityHistory: Boolean = true,
    val includeRuntimeTranscript: Boolean = false,
    val liveActivity: List<AgentActivityEntry> = emptyList(),
    /** Web `agent.reportIssue.titleForAgent`, already localized; blank falls back to English. */
    val title: String = "",
    /** The active UI locale (`en`, `zh-CN`, `ja`), sent as the ticket's `metadata.locale`. */
    val locale: String = "en",
    /**
     * Tier 2 machine runner log tail. Only honoured when [canIncludeMachineLog] (the reporter
     * attached the agent's Computer, web `computerAttachedByCurrentUser`) and the runtime
     * transcript is requested; the server re-checks ownership.
     */
    val includeMachineLogTail: Boolean = false,
    val canIncludeMachineLog: Boolean = false,
    /**
     * Computer diagnostics bundle (slock #16580). Only honoured when [canIncludeDiagnosticsBundle]
     * (the reporter attached the agent's Computer) and the runtime transcript is requested; the
     * server re-checks ownership (`403 diagnostics_bundle_owner_required`) and daemon capability
     * (`feedback-diagnostics-bundle:v1`).
     */
    val includeDiagnosticsBundle: Boolean = false,
    val canIncludeDiagnosticsBundle: Boolean = false,
    val diagnosticsBundleRange: AgentIssueDiagnosticsBundleRange = AgentIssueDiagnosticsBundleRange.ReportWindow
)

/**
 * What the report screen may say about the session-record request (web
 * `TranscriptRequestState`, packages/web/src/feedback/transcriptRequestState.ts): only the HTTP
 * result of our own request is known, never whether a record was found.
 */
@RaftCoreInternalApi enum class AgentIssueTranscriptRequestState {
    Requested,
    RequestFailed,
    RequestUnconfirmed
}

/**
 * The Hands ticket filed for a report (web `createAgentIssueTicket`). Kept on the result so a
 * failed ticket can be retried with the same [submissionId], which Hands dedupes on.
 */
@RaftCoreInternalApi data class AgentIssueTicketRequest(
    val message: String,
    val feedbackReportId: String,
    val submissionId: String,
    val locale: String
)

@RaftCoreInternalApi data class AgentIssueReportResult(
    val reportId: String,
    val artifactId: String?,
    val serverId: String,
    val issueDescription: String?,
    val transcriptRequested: Boolean,
    val transcriptRequestState: AgentIssueTranscriptRequestState? = null,
    /** Null when the bundle uploaded but filing the ticket failed; [reportId] is then the handle. */
    val ticketId: String? = null,
    val ticketRequest: AgentIssueTicketRequest? = null
)

@RaftCoreInternalApi class AgentIssueReportRepository(
    private val sessionStore: RaftAuthSessionStore,
    private val selectedServerStore: SelectedServerStore,
    private val networkClient: RaftNetworkClient,
    private val sha256Hex: (String) -> String,
    private val feedbackBaseUrl: String = RAFT_AGENT_FEEDBACK_BASE_URL,
    private val nowMillis: () -> Long = ::defaultNowMillis,
    private val logger: (String) -> Unit = {},
    private val newSubmissionId: () -> String = ::newAgentIssueSubmissionId,
    /**
     * The app's feedback client kind (`android` / `ios`), the same the Settings › Feedback page
     * sends as `X-Raft-Client-Kind` (web feedback/standalone/host.ts), so the ticket is filed into
     * — and listed by — the same Hands app that page reads. Blank files as web.
     */
    private val feedbackClientKind: String = "",
    private val feedbackClientVersion: String = "",
    /** This environment's privacy mode, read again before each request to the trace-upload worker. */
    private val privacyMode: () -> RaftPrivacyMode = { RaftTelemetryPrivacy.state.value }
) {
    suspend fun submit(submission: AgentIssueReportSubmission): DataResult<AgentIssueReportResult> {
        privacyBlock("start")?.let { return it }
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
                    title = submission.reportTitle(),
                    description = submission.description.trim().takeIf { it.isNotBlank() },
                    appVersion = null,
                    daemonVersion = submission.detail?.summary?.daemonVersion,
                    metadata = submission.toMetadata(recentMessages.dmChannelId, bundle.generatedAt)
                )
            // The answer can change while messages load (an environment change starts over at
            // unknown): check again right before the worker sees anything.
            privacyBlock("create")?.let { return it }
            val created =
                when (val result = createReport(createRequest)) {
                    is DataResult.Content -> result.data
                    else -> return result.toFailure()
                }
            privacyBlock("upload")?.let { return it }
            uploadBundle(created, bundleJson).failureOrNull()?.let { return it }
            completeReport(created).failureOrNull()?.let { return it }

            val machineId = submission.detail?.summary?.machineId?.trim().orEmpty()
            val transcriptRequested = submission.includeRuntimeTranscript && machineId.isNotBlank()
            val ticketRequest =
                AgentIssueTicketRequest(
                    message = listOf(submission.reportTitle(), submission.description.trim())
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n"),
                    feedbackReportId = created.id,
                    submissionId = newSubmissionId(),
                    locale = submission.locale
                )
            // Web starts the session-record request before filing the ticket and never lets it
            // fail the report; both run together here and the transcript answer is capped.
            val (transcriptState, ticketId) =
                coroutineScope {
                    val transcript =
                        if (transcriptRequested) {
                            // Undispatched: the request is on the wire before the ticket is filed, as web.
                            async(start = CoroutineStart.UNDISPATCHED) {
                                requestTranscript(
                                    serverId = serverId,
                                    machineId = machineId,
                                    agentId = submission.member.id.value,
                                    reportId = created.id,
                                    body =
                                        AgentIssueTranscriptRequest(
                                            reportGeneratedAt = bundle.generatedAt,
                                            includeMachineLogTail =
                                                true.takeIf {
                                                    submission.canIncludeMachineLog && submission.includeMachineLogTail
                                                },
                                            diagnosticsBundleRange =
                                                submission.diagnosticsBundleRange.wire.takeIf {
                                                    submission.canIncludeDiagnosticsBundle && submission.includeDiagnosticsBundle
                                                }
                                        )
                                )
                            }
                        } else {
                            null
                        }
                    val ticket = fileTicket(serverId, ticketRequest)
                    transcript?.await() to (ticket as? DataResult.Content)?.data
                }
            logger(
                "agent_issue_report_complete report=${created.id.take(8)} " +
                    "artifact=${created.artifactId?.take(8).orEmpty()} transcriptRequested=$transcriptRequested " +
                    "transcriptState=${transcriptState?.name.orEmpty()} ticketFiled=${ticketId != null}"
            )
            DataResult.Content(
                AgentIssueReportResult(
                    reportId = created.id,
                    artifactId = created.artifactId,
                    serverId = serverId.value,
                    issueDescription = submission.description.trim().takeIf { it.isNotBlank() },
                    transcriptRequested = transcriptRequested,
                    transcriptRequestState = transcriptState,
                    ticketId = ticketId,
                    ticketRequest = ticketRequest
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger("agent_issue_report_exception type=${throwable::class.simpleName}")
            DataResult.Error(ScreenError("agent_issue_report_failed", retryable = true))
        }
    }

    private fun privacyBlock(step: String): DataResult.Error? {
        val mode = privacyMode()
        val error = agentIssueReportPrivacyError(mode) ?: return null
        logger("agent_issue_report_blocked reason=privacy_mode mode=${mode.name.lowercase()} step=$step")
        return DataResult.Error(error)
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
            val envelope = response.parseHttpErrorEnvelope()
            return DataResult.Error(
                ScreenError(
                    code = "agent_issue_report_messages_failed",
                    retryable = response.code == 0 || response.code >= 500,
                    serverMessage = envelope?.serverMessage,
                    serverCode = envelope?.serverCode,
                    traceId = response.traceId,
                )
            )
        }
        val body = response.bodyText?.takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError("agent_issue_report_messages_failed", retryable = true, traceId = response.traceId))
        return try {
            val messages = agentIssueReportJson.parseToJsonElement(body).jsonObject["messages"] as? JsonArray
            DataResult.Content(AgentIssueRecentMessages(dmChannelId = dmChannelId, messages = messages?.toList().orEmpty()))
        } catch (_: Throwable) {
            DataResult.Error(ScreenError("agent_issue_report_messages_failed_decode", retryable = false, traceId = response.traceId))
        }
    }

    private suspend fun createReport(request: AgentIssueReportCreateRequest): DataResult<AgentIssueReportCreateResponse> =
        networkClient.requestRawAbsoluteUrl(
            priority = DataRequestPriority.Foreground,
            url = "${feedbackBaseUrl.trimEnd('/')}/api/reports",
            method = "POST",
            headers = mapOf("Content-Type" to AGENT_FEEDBACK_CONTENT_TYPE),
            bodyText = agentIssueRequestJson.encodeToString(request)
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

    /**
     * Files the Hands ticket for an uploaded report: `POST /api/product-feedback` with the fields
     * web's `productFeedbackForm` + `feedback_report_id` sends (packages/web/src/feedback/
     * agentIssueTicket.ts; server packages/server/src/routes/productFeedback.ts). Returns the ticket
     * id (`ProductFeedbackReceipt.id`). Safe to retry with the same request: Hands dedupes on
     * `submission_id`.
     */
    suspend fun fileTicket(serverId: ServerId, request: AgentIssueTicketRequest): DataResult<String> =
        try {
            val clientKind = feedbackClientKind.trim()
            val result =
                networkClient.requestWithHeaders(
                    priority = DataRequestPriority.Foreground,
                    path = "product-feedback",
                    headers = if (clientKind.isEmpty()) emptyMap() else mapOf(AGENT_ISSUE_CLIENT_KIND_HEADER to clientKind),
                    method = "POST",
                    serverIdOverride = serverId,
                    bodyText = agentIssueRequestJson.encodeToString(request.toWire(clientKind, feedbackClientVersion.trim()))
                ).decode<AgentIssueTicketReceipt>("agent_issue_ticket_failed")
            logger("agent_issue_ticket result=${result::class.simpleName}")
            when (result) {
                is DataResult.Content ->
                    result.data.id.trim().takeIf { it.isNotBlank() }?.let { DataResult.Content(it) }
                        ?: DataResult.Error(ScreenError("agent_issue_ticket_failed_decode", retryable = false))
                else -> result.toFailure()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger("agent_issue_ticket_exception type=${throwable::class.simpleName}")
            DataResult.Error(ScreenError("agent_issue_ticket_failed", retryable = true))
        }

    private suspend fun requestTranscript(
        serverId: ServerId,
        machineId: String,
        agentId: String,
        reportId: String,
        body: AgentIssueTranscriptRequest
    ): AgentIssueTranscriptRequestState {
        // Never throws: it runs beside the ticket in one coroutineScope, and a failure here must not
        // cancel the ticket (a "failed" report submitted again would file a second ticket).
        val response =
            try {
                withTimeoutOrNull(AGENT_ISSUE_TRANSCRIPT_CONFIRM_CAP_MILLIS) {
                    networkClient.request(
                        priority = DataRequestPriority.Foreground,
                        path = "servers/${serverId.value}/machines/$machineId/agents/$agentId/feedback/$reportId/transcript",
                        method = "POST",
                        serverIdOverride = serverId,
                        bodyText = agentIssueRequestJson.encodeToString(body)
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                logger("agent_issue_report_transcript_exception type=${throwable::class.simpleName}")
                null
            }
        logger("agent_issue_report_transcript code=${response?.code ?: "timeout"}")
        return agentIssueTranscriptRequestState(response)
    }
}

/**
 * Web `startTranscriptRequest`: a 2xx answer is "requested", an HTTP error "request failed", and
 * no answer (transport failure, code 0, or the confirm cap) "unconfirmed".
 */
@RaftCoreInternalApi fun agentIssueTranscriptRequestState(response: HomeNetworkResponse?): AgentIssueTranscriptRequestState =
    when {
        response == null || response.code <= 0 -> AgentIssueTranscriptRequestState.RequestUnconfirmed
        response.isSuccessful -> AgentIssueTranscriptRequestState.Requested
        else -> AgentIssueTranscriptRequestState.RequestFailed
    }

@OptIn(ExperimentalUuidApi::class)
private fun newAgentIssueSubmissionId(): String = Uuid.random().toString()

/**
 * `POST /api/product-feedback` body (server `productFeedbackRouter.post("/")`). Web sends
 * multipart form fields; the route's multer skips non-multipart bodies and the global
 * `express.json()` parses the same string fields, so the JSON shape is the form's field names.
 */
@Serializable
@RaftCoreInternalApi data class AgentIssueTicketWireRequest(
    val type: String = "problem",
    val message: String,
    @SerialName("submission_id") val submissionId: String,
    @SerialName("may_contact") val mayContact: String = "false",
    /** JSON-encoded string, as `form.set("metadata", JSON.stringify(...))`. */
    val metadata: String,
    @SerialName("feedback_report_id") val feedbackReportId: String
)

/** Server `trustedFeedbackClientKind` header (routes/productFeedback.ts). */
@RaftCoreInternalApi const val AGENT_ISSUE_CLIENT_KIND_HEADER = "X-Raft-Client-Kind"

/**
 * The form's `metadata` JSON (server `normalizeProductFeedbackMetadata`): `locale` as web, plus,
 * for a native client kind, `client_kind` / `platform` / `client_version` as the Settings ›
 * Feedback page's `hostMetadata` (a native kind requires `client_version`).
 */
@Serializable
private data class AgentIssueTicketMetadata(
    @SerialName("client_kind") val clientKind: String? = null,
    val platform: String? = null,
    @SerialName("client_version") val clientVersion: String? = null,
    val locale: String
)

@RaftCoreInternalApi fun AgentIssueTicketRequest.toWire(clientKind: String = "", clientVersion: String = ""): AgentIssueTicketWireRequest =
    AgentIssueTicketWireRequest(
        message = message,
        submissionId = submissionId,
        metadata = productFeedbackMetadataJson(clientKind, clientVersion, locale),
        feedbackReportId = feedbackReportId
    )

/**
 * The `POST /api/product-feedback` form's `metadata` field, JSON-encoded (web
 * `form.set("metadata", JSON.stringify(...))`): every App submission (an agent issue ticket,
 * Settings › Feedback) sends the same client facts.
 */
@RaftCoreInternalApi fun productFeedbackMetadataJson(clientKind: String, clientVersion: String, locale: String): String =
    agentIssueRequestJson.encodeToString(
        AgentIssueTicketMetadata(
            clientKind = clientKind.ifEmpty { null },
            platform = clientKind.ifEmpty { null },
            clientVersion = clientVersion.takeIf { clientKind.isNotEmpty() && it.isNotEmpty() },
            locale = locale
        )
    )

/** `ProductFeedbackReceipt` (server services/productFeedbackService.ts). */
@Serializable
@RaftCoreInternalApi data class AgentIssueTicketReceipt(
    val id: String,
    val status: String,
    val reference: String? = null,
    val attachments: Int = 0
)

/** Body of `POST /servers/:id/machines/:machineId/agents/:agentId/feedback/:reportId/transcript`. */
@Serializable
@RaftCoreInternalApi data class AgentIssueTranscriptRequest(
    val reportGeneratedAt: String,
    /** Sent only as `true`; omitted otherwise, as web. */
    val includeMachineLogTail: Boolean? = null,
    /** Closed range preset (`report_window` | `last_24h` | `last_7d`); omitted unless opted in on own machine. */
    val diagnosticsBundleRange: String? = null
)

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
    val reportGeneratedAt: String,
    val dmChannelId: String?,
    val deploymentEnv: String?,
    val includes: AgentIssueReportIncludes,
    /** Web sends `transcript` only when the agent has a machine. */
    val transcript: AgentIssueReportTranscriptMetadata? = null
)

@Serializable
private data class AgentIssueReportIncludes(
    val recentMessages: Boolean,
    val activityLog: Boolean,
    val trajectoryLog: Boolean,
    val ephemeralActivityBuffer: Boolean,
    val durableTrajectoryLog: Boolean,
    /** Web sends it only when the agent has a machine. */
    val runtimeSessionTranscript: Boolean? = null
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

private fun AgentIssueReportSubmission.reportTitle(): String =
    title.trim().ifBlank { "Issue report for ${member.displayName}" }

private fun AgentIssueReportSubmission.toMetadata(dmChannelId: String?, reportGeneratedAt: String): AgentIssueReportMetadata {
    val hasMachine = detail?.summary?.machineId?.trim().orEmpty().isNotBlank()
    return AgentIssueReportMetadata(
        schemaVersion = "slock-feedback-export-v2",
        reportGeneratedAt = reportGeneratedAt,
        dmChannelId = dmChannelId,
        deploymentEnv = null,
        includes =
            AgentIssueReportIncludes(
                recentMessages = includeRecentMessages,
                activityLog = includeLiveActivity,
                trajectoryLog = includeActivityHistory,
                ephemeralActivityBuffer = includeLiveActivity,
                durableTrajectoryLog = includeActivityHistory,
                runtimeSessionTranscript = includeRuntimeTranscript.takeIf { hasMachine }
            ),
        transcript =
            AgentIssueReportTranscriptMetadata(
                attachmentMode = "async_trace_bundle",
                requested = includeRuntimeTranscript,
                requestable = true,
                skippedReason = null
            ).takeIf { hasMachine }
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
    if (!isSuccessful) {
        val envelope = parseHttpErrorEnvelope()
        return DataResult.Error(
            ScreenError(
                code = errorCode,
                retryable = code == 0 || code >= 500,
                serverMessage = envelope?.serverMessage,
                serverCode = envelope?.serverCode,
                traceId = traceId,
            )
        )
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(errorCode, retryable = true, traceId = traceId))
    return try {
        DataResult.Content(agentIssueReportJson.decodeFromString<T>(body))
    } catch (_: Throwable) {
        DataResult.Error(ScreenError("${errorCode}_decode", retryable = false, traceId = traceId))
    }
}

private fun HomeNetworkResponse.toUnitResult(errorCode: String): DataResult<Unit> =
    if (isSuccessful) {
        DataResult.Content(Unit)
    } else {
        val envelope = parseHttpErrorEnvelope()
        DataResult.Error(
            ScreenError(
                code = errorCode,
                retryable = code == 0 || code >= 500,
                serverMessage = envelope?.serverMessage,
                serverCode = envelope?.serverCode,
                traceId = traceId,
            )
        )
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
