package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// Agent remote controls (web parity). Wire shapes verified against
// botiverse/slock 7d349b509d6511eb922f7870099017fdda0d3f61:
//  - POST /agents/:id/interrupt-turn  packages/server/src/routes/agents.ts
//    (`AgentTurnInterruptRequestResult`, AGENT_TURN_INTERRUPT_OUTCOMES in
//    packages/shared/src/index.ts; web packages/web/src/store/agentStore.ts
//    `stopAgentTurn` + components/agent/stopAgentTurn.ts).
//  - POST /channels/:id/stop-all-agents, /resume-all-agents  packages/server/src/routes/channels.ts
//  - POST /agents/:id/subagents/:taskId/interrupt  packages/server/src/routes/agents.ts, verified
//    against botiverse/slock 853c6998bcef45df5c25e63b1035e2388608721d
//    (AGENT_SUBAGENT_INTERRUPT_OUTCOMES, SUBAGENT_TASK_ID_MAX_LENGTH in packages/shared/src/index.ts;
//    web packages/web/src/components/agent/stopSubagent.ts).
private val agentControlsJson = Json { ignoreUnknownKeys = true }

/** What a stop-this-turn request reported; web `AgentTurnStopOutcome`. */
@RaftCoreInternalApi enum class AgentTurnStopOutcome(val wireValue: String) {
    Interrupted("interrupted"),
    Restarted("restarted"),
    NotRunning("not_running");
}

@Serializable
private data class AgentTurnInterruptResponseDto(
    val outcome: String? = null,
    val toolName: String? = null,
    val stuckForMs: Long? = null,
    val paused: Boolean? = null
)

/** Result of an admin pausing another user's personal assistant via `POST /agents/:id/interrupt-turn`. */
@RaftCoreInternalApi data class AgentPauseAssistantResult(
    val outcome: AgentTurnStopOutcome,
    val paused: Boolean
)

/** `{ error, code, errorCode, details }`: the agents/channels error envelope (`code`/`errorCode`/`details` are optional). */
@Serializable
private data class AgentControlsErrorDto(
    val error: String? = null,
    val code: String? = null,
    val errorCode: String? = null,
    val details: JsonElement? = null
)

/**
 * Parses a 2xx `POST /agents/:id/interrupt-turn` body. Like web
 * `stopAgentTurn`, anything other than `interrupted`/`restarted` (a hosted
 * provider's own kind, or a missing value) reads as "nothing was running".
 */
@RaftCoreInternalApi fun parseSharedAgentTurnInterruptOutcome(bodyText: String): AgentTurnStopOutcome? =
    parseSharedAgentPauseAssistantResult(bodyText)?.outcome

/**
 * Parses a 2xx `POST /agents/:id/interrupt-turn` body with its `paused` flag (web
 * `agentStore.pauseAssistant`): `paused == true` when this call newly paused a personal assistant.
 */
@RaftCoreInternalApi fun parseSharedAgentPauseAssistantResult(bodyText: String): AgentPauseAssistantResult? =
    runCatching {
        val dto = agentControlsJson.decodeFromString(AgentTurnInterruptResponseDto.serializer(), bodyText)
        val outcome =
            when (dto.outcome) {
                AgentTurnStopOutcome.Interrupted.wireValue -> AgentTurnStopOutcome.Interrupted
                AgentTurnStopOutcome.Restarted.wireValue -> AgentTurnStopOutcome.Restarted
                else -> AgentTurnStopOutcome.NotRunning
            }
        AgentPauseAssistantResult(outcome = outcome, paused = dto.paused == true)
    }.getOrNull()

/** What a stop-this-sub-agent request reported; web `SubagentStopOutcome` (`AgentSubagentInterruptOutcome`). */
@RaftCoreInternalApi enum class AgentSubagentStopOutcome(val wireValue: String) {
    Interrupted("interrupted"),
    NotRunning("not_running"),
    Unsupported("unsupported");
}

@Serializable
private data class AgentSubagentInterruptResponseDto(
    val outcome: String? = null
)

/**
 * Parses a 2xx `POST /agents/:id/subagents/:taskId/interrupt` body. Like web `stopSubagent`,
 * anything other than `interrupted`/`unsupported` reads as "it isn't running".
 */
@RaftCoreInternalApi fun parseSharedAgentSubagentInterruptOutcome(bodyText: String): AgentSubagentStopOutcome? =
    runCatching {
        when (agentControlsJson.decodeFromString(AgentSubagentInterruptResponseDto.serializer(), bodyText).outcome) {
            AgentSubagentStopOutcome.Interrupted.wireValue -> AgentSubagentStopOutcome.Interrupted
            AgentSubagentStopOutcome.Unsupported.wireValue -> AgentSubagentStopOutcome.Unsupported
            else -> AgentSubagentStopOutcome.NotRunning
        }
    }.getOrNull()

/** The longest sub-agent id a stop request may carry (`SUBAGENT_TASK_ID_MAX_LENGTH`). */
@RaftCoreInternalApi const val AgentSubagentTaskIdMaxLength = 160

/** Refusal codes the server explains for interrupt-turn (409/503/504) and Worker observability (404). */
@RaftCoreInternalApi object AgentControlErrorCodes {
    const val ComputerUpgradeRequired = "computer_upgrade_required"
    const val ComputerOffline = "computer_offline"
    const val ComputerTimeout = "computer_timeout"
    const val WorkersSnapshotStale = "workers_snapshot_stale"
    const val WorkerTimelineNotFound = "worker_timeline_not_found"
    const val WorkerV1Off = "worker_v1_off"
    const val HostedStopFailed = "hosted_stop_failed"
    const val ContractMismatch = "agent_controls_contract_mismatch"
    const val NetworkError = "agent_controls_network_error"
}

/** Maps a non-2xx response to a typed error carrying the server `code`, `error` text, and `X-Trace-Id`. */
@RaftCoreInternalApi fun agentControlsErrorResult(response: HomeNetworkResponse): DataResult<Nothing> {
    if (response.code == 401) return authSessionRequiredResult()
    val envelope =
        runCatching {
            agentControlsJson.decodeFromString(
                AgentControlsErrorDto.serializer(),
                response.errorBodyText ?: response.bodyText.orEmpty()
            )
        }.getOrNull()
    val serverCode = looseRefusalCode(code = envelope?.code, errorCode = envelope?.errorCode)
    val serverMessage = envelope?.error?.takeIf { it.isNotBlank() }
    if (response.code == 403 && serverCode == null) {
        return DataResult.NoPermission(
            PermissionBlocker(code = "http_403", serverMessage = serverMessage, traceId = response.traceId)
        )
    }
    val serverFields =
        if (serverCode == AgentMigrationErrorCodes.TargetRuntimeNotReady) {
            parseMigrationTargetRuntimeNotReady(envelope?.details)?.let(::migrationTargetRuntimeNotReadyFields).orEmpty()
        } else {
            emptyMap()
        }
    return DataResult.Error(
        ScreenError(
            code = serverCode ?: "http_${response.code}",
            retryable = response.code >= 500 || response.code == 409,
            serverMessage = serverMessage,
            serverCode = serverCode,
            serverFields = serverFields,
            traceId = response.traceId
        )
    )
}

// ── Channel SOS ──
// POST /channels/:id/stop-all-agents and /channels/:id/resume-all-agents,
// packages/server/src/routes/channels.ts; web components/message/SOSDialog.tsx.

/** A resume `reason` meaning another start for that agent was already in flight. */
@RaftCoreInternalApi const val CHANNEL_RESUME_START_IN_FLIGHT = "start_in_flight"

@Serializable
private data class ChannelAgentRunResultDto(
    val agentId: String,
    val ok: Boolean,
    val reason: String? = null,
    val error: String? = null
)

@Serializable
private data class ChannelStopAllAgentsResponseDto(
    val ok: Boolean,
    val stopped: Int,
    val total: Int,
    val results: List<ChannelAgentRunResultDto> = emptyList()
)

@Serializable
private data class ChannelResumeAllAgentsResponseDto(
    val ok: Boolean,
    val started: Int,
    val total: Int,
    val results: List<ChannelAgentRunResultDto> = emptyList()
)

@Serializable
private data class ChannelResumeAllAgentsRequestDto(
    val prompt: String,
    /** A retry names only the agents the last attempt missed; omitted otherwise. */
    val agentIds: List<String>? = null
)

@RaftCoreInternalApi data class ChannelStopAllAgentsResult(val stopped: Int, val total: Int)

@RaftCoreInternalApi data class ChannelResumeMiss(val agentId: String, val reason: String?)

@RaftCoreInternalApi data class ChannelResumeAllAgentsResult(
    val started: Int,
    val total: Int,
    val missed: List<ChannelResumeMiss>
)

private val agentControlsRequestJson = Json { explicitNulls = false }

@RaftCoreInternalApi fun parseSharedChannelStopAllAgents(bodyText: String): ChannelStopAllAgentsResult? =
    runCatching {
        val dto = agentControlsJson.decodeFromString(ChannelStopAllAgentsResponseDto.serializer(), bodyText)
        ChannelStopAllAgentsResult(stopped = dto.stopped, total = dto.total)
    }.getOrNull()

@RaftCoreInternalApi fun parseSharedChannelResumeAllAgents(bodyText: String): ChannelResumeAllAgentsResult? =
    runCatching {
        val dto = agentControlsJson.decodeFromString(ChannelResumeAllAgentsResponseDto.serializer(), bodyText)
        ChannelResumeAllAgentsResult(
            started = dto.started,
            total = dto.total,
            missed = dto.results.filterNot { it.ok }.map { ChannelResumeMiss(it.agentId, it.reason) }
        )
    }.getOrNull()

@RaftCoreInternalApi fun channelResumeAllAgentsRequestBody(prompt: String, agentIds: List<String>?): String =
    agentControlsRequestJson.encodeToString(
        ChannelResumeAllAgentsRequestDto.serializer(),
        ChannelResumeAllAgentsRequestDto(prompt = prompt, agentIds = agentIds)
    )

/** The one shared request boundary for the agent remote controls. */
@RaftCoreInternalApi class RaftAgentControlsApi(
    private val networkClient: RaftNetworkClient
) : ChannelSosDataSource, AgentMigrationDataSource {
    override suspend fun stopAll(channelId: ChannelId): DataResult<ChannelStopAllAgentsResult> =
        send(
            priority = DataRequestPriority.Foreground,
            path = "channels/${channelId.value}/stop-all-agents",
            method = "POST",
            bodyText = "{}",
            parse = ::parseSharedChannelStopAllAgents
        )

    override suspend fun resumeAll(
        channelId: ChannelId,
        prompt: String,
        agentIds: List<String>?
    ): DataResult<ChannelResumeAllAgentsResult> =
        send(
            priority = DataRequestPriority.Foreground,
            path = "channels/${channelId.value}/resume-all-agents",
            method = "POST",
            bodyText = channelResumeAllAgentsRequestBody(prompt, agentIds),
            parse = ::parseSharedChannelResumeAllAgents
        )

    suspend fun interruptTurn(agentId: MemberId): DataResult<AgentTurnStopOutcome> =
        send(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/interrupt-turn",
            method = "POST",
            bodyText = "{}",
            parse = ::parseSharedAgentTurnInterruptOutcome
        )

    /** Server owner/admin pause of another member's personal assistant (`POST /agents/:id/interrupt-turn`). */
    suspend fun pauseAssistant(agentId: MemberId): DataResult<AgentPauseAssistantResult> =
        send(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/interrupt-turn",
            method = "POST",
            bodyText = "{}",
            parse = ::parseSharedAgentPauseAssistantResult
        )

    /** Stops one running sub-agent; the agent and its other sub-agents keep running. */
    suspend fun interruptSubagent(agentId: MemberId, subagentTaskId: String): DataResult<AgentSubagentStopOutcome> =
        send(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/subagents/${subagentTaskId.percentEncode()}/interrupt",
            method = "POST",
            bodyText = "{}",
            parse = ::parseSharedAgentSubagentInterruptOutcome
        )

    /** Worker v1 (`GET /api/agents/:id/workers`): the agent's live Workers from its Computer's last snapshot. */
    suspend fun listWorkers(
        agentId: MemberId,
        priority: DataRequestPriority = DataRequestPriority.Visible,
    ): DataResult<AgentWorkersReadSnapshot> =
        send(
            priority = priority,
            path = "agents/${agentId.value}/workers",
            parse = ::parseSharedAgentWorkersRead,
        )

    /** Worker v1 (`POST /api/agents/:id/workers/:worker/stop`): stops one Worker and hands its conversation back. */
    suspend fun stopWorker(agentId: MemberId, worker: String): DataResult<AgentWorkerStopOutcome> =
        send(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/workers/${worker.percentEncode()}/stop",
            method = "POST",
            bodyText = "{}",
            parse = ::parseSharedAgentWorkerStopOutcome,
        )

    /** Worker observability (`GET /api/agents/:id/workers/recent?days=7`): launches of the last 7 days. */
    suspend fun listRecentWorkers(
        agentId: MemberId,
        days: Int = AGENT_WORKER_RECENT_DAYS,
        priority: DataRequestPriority = DataRequestPriority.Visible,
    ): DataResult<List<AgentRecentWorkerRow>> =
        send(
            priority = priority,
            path = "agents/${agentId.value}/workers/recent",
            query = listOf("days" to days.coerceAtLeast(1).toString()),
            parse = ::parseSharedAgentRecentWorkers,
        )

    /** Worker observability (`GET /api/agents/:id/workers/:launchId/timeline`): one launch's events by seq. */
    suspend fun getWorkerTimeline(
        agentId: MemberId,
        launchId: String,
        priority: DataRequestPriority = DataRequestPriority.Visible,
    ): DataResult<AgentWorkerTimelineSnapshot> =
        send(
            priority = priority,
            path = "agents/${agentId.value}/workers/${launchId.percentEncode()}/timeline",
            parse = ::parseSharedAgentWorkerTimeline,
        )

    /**
     * Worker timeline `"View message"` lookup (`GET /api/messages/context/:messageId?before=0&after=0`,
     * web `ViewMessageLink`): resolves the channel under the viewer's own permissions.
     */
    suspend fun resolveMessageTarget(
        messageId: String,
        priority: DataRequestPriority = DataRequestPriority.Foreground,
        contextPageSink: RaftMessageContextPageSink? = null,
    ): DataResult<AgentWorkerMessageTarget> {
        val cleanId = messageId.trim().takeIf { it.isNotEmpty() }
            ?: return DataResult.Empty(EmptyReason.NoMessages)
        return try {
            val pendingContextPage = contextPageSink?.beginRequest(serverId = null)
            val response =
                networkClient.request(
                    priority = priority,
                    path = "messages/context/${cleanId.percentEncode()}",
                    query = listOf("before" to "0", "after" to "0"),
                    serverIdOverride = pendingContextPage?.serverId?.let(::ServerId),
                )
            if (response.code == 401) return authSessionRequiredResult()
            if (response.code == 403 || response.code == 404) {
                return DataResult.Empty(EmptyReason.NoMessages)
            }
            if (!response.isSuccessful) {
                return agentControlsErrorResult(response)
            }
            val body = response.bodyText.orEmpty()
            val payload = parseSharedMessageContext(body, fallbackThreadId = ThreadId(cleanId))
            val targetId = payload.targetMessageId ?: MessageId(cleanId)
            val message = payload.messages.firstOrNull { it.id == targetId }
                ?: return DataResult.Empty(EmptyReason.NoMessages)
            val channelId = (message.channelId ?: payload.channelId)?.value?.trim()?.takeIf { it.isNotEmpty() }
                ?: return DataResult.Empty(EmptyReason.NoMessages)
            pendingContextPage?.offer(body)
            DataResult.Content(AgentWorkerMessageTarget(channelId = channelId, messageId = message.id.value))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DataResult.Error(ScreenError(AgentControlErrorCodes.NetworkError, traceId = null))
        }
    }

    override suspend fun migrationStatus(
        agentId: MemberId,
        priority: DataRequestPriority
    ): DataResult<AgentMigrationStatusSnapshot> =
        send(
            priority = priority,
            path = "agents/${agentId.value}/migration",
            parse = ::parseSharedAgentMigrationStatus
        )

    override suspend fun startMigration(
        agentId: MemberId,
        target: AgentMigrationTarget,
        preflightOverride: Boolean,
        leaveOut: List<String>,
    ): DataResult<AgentMigrationStartResult> =
        send(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/migrate",
            method = "POST",
            bodyText = agentMigrationStartRequestBody(target, preflightOverride = preflightOverride, leaveOut = leaveOut),
            parse = ::parseSharedAgentMigrationStart
        )

    override suspend fun cancelMigration(
        agentId: MemberId,
        migrationRef: String,
        expectedRevision: Int
    ): DataResult<Unit> =
        send(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/migration/cancel",
            method = "POST",
            bodyText = agentMigrationRevisionRequestBody(migrationRef, expectedRevision),
            parse = { Unit }
        )

    override suspend fun confirmHostedMigration(
        agentId: MemberId,
        migrationRef: String,
        expectedRevision: Int
    ): DataResult<Unit> =
        send(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/migration/confirm-hosted",
            method = "POST",
            bodyText = agentMigrationRevisionRequestBody(migrationRef, expectedRevision),
            parse = { Unit }
        )

    override suspend fun dismissMigration(
        agentId: MemberId,
        migrationRef: String,
        expectedRevision: Int
    ): DataResult<Unit> =
        send(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/migration/dismiss",
            method = "POST",
            bodyText = agentMigrationRevisionRequestBody(migrationRef, expectedRevision),
            parse = { Unit }
        )

    /**
     * The server plan from `GET /billing/subscription` (web `serverStore.loadBilling`),
     * the Pro gate's advisory input. Owners/admins only; others get a 403 and no plan.
     */
    override suspend fun billingPlan(): DataResult<RaftBillingPlanSnapshot> =
        send(
            priority = DataRequestPriority.Visible,
            path = "billing/subscription",
            parse = { body -> parseSharedSettingsBillingSubscription(body)?.let { RaftBillingPlanSnapshot(it.effectivePlan) } }
        )

    private suspend fun <T> send(
        priority: DataRequestPriority,
        path: String,
        method: String = "GET",
        query: List<Pair<String, String>> = emptyList(),
        bodyText: String? = null,
        parse: (String) -> T?
    ): DataResult<T> =
        try {
            val response = networkClient.request(
                priority = priority,
                path = path,
                method = method,
                query = query,
                bodyText = bodyText
            )
            if (!response.isSuccessful) {
                agentControlsErrorResult(response)
            } else {
                parse(response.bodyText.orEmpty())?.let { DataResult.Content(it) }
                    ?: DataResult.Error(
                        ScreenError(
                            code = AgentControlErrorCodes.ContractMismatch,
                            retryable = false,
                            traceId = response.traceId,
                        )
                    )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DataResult.Error(ScreenError(AgentControlErrorCodes.NetworkError, traceId = null))
        }
}
