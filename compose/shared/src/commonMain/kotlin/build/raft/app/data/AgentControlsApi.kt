package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Agent remote controls (web parity). Wire shapes verified against
// botiverse/slock 7d349b509d6511eb922f7870099017fdda0d3f61:
//  - POST /agents/:id/interrupt-turn  packages/server/src/routes/agents.ts
//    (`AgentTurnInterruptRequestResult`, AGENT_TURN_INTERRUPT_OUTCOMES in
//    packages/shared/src/index.ts; web packages/web/src/store/agentStore.ts
//    `stopAgentTurn` + components/agent/stopAgentTurn.ts).
//  - POST /channels/:id/stop-all-agents, /resume-all-agents  packages/server/src/routes/channels.ts
private val agentControlsJson = Json { ignoreUnknownKeys = true }

/** What a stop-this-turn request reported; web `AgentTurnStopOutcome`. */
internal enum class AgentTurnStopOutcome(val wireValue: String) {
    Interrupted("interrupted"),
    Restarted("restarted"),
    NotRunning("not_running");
}

@Serializable
private data class AgentTurnInterruptResponseDto(
    val outcome: String? = null,
    val toolName: String? = null,
    val stuckForMs: Long? = null
)

/** `{ error, code }`: the agents/channels error envelope (`code` is optional). */
@Serializable
private data class AgentControlsErrorDto(
    val error: String? = null,
    val code: String? = null
)

/**
 * Parses a 2xx `POST /agents/:id/interrupt-turn` body. Like web
 * `stopAgentTurn`, anything other than `interrupted`/`restarted` (a hosted
 * provider's own kind, or a missing value) reads as "nothing was running".
 */
internal fun parseSharedAgentTurnInterruptOutcome(bodyText: String): AgentTurnStopOutcome? =
    runCatching {
        val dto = agentControlsJson.decodeFromString(AgentTurnInterruptResponseDto.serializer(), bodyText)
        when (dto.outcome) {
            AgentTurnStopOutcome.Interrupted.wireValue -> AgentTurnStopOutcome.Interrupted
            AgentTurnStopOutcome.Restarted.wireValue -> AgentTurnStopOutcome.Restarted
            else -> AgentTurnStopOutcome.NotRunning
        }
    }.getOrNull()

/** Refusal codes the server explains for interrupt-turn (409/503/504). */
internal object AgentControlErrorCodes {
    const val ComputerUpgradeRequired = "computer_upgrade_required"
    const val ComputerOffline = "computer_offline"
    const val ComputerTimeout = "computer_timeout"
    const val HostedStopFailed = "hosted_stop_failed"
    const val ContractMismatch = "agent_controls_contract_mismatch"
    const val NetworkError = "agent_controls_network_error"
}

/** Maps a non-2xx response to a typed error carrying the server `code` and `error` text. */
internal fun agentControlsErrorResult(response: HomeNetworkResponse): DataResult<Nothing> {
    if (response.code == 401) return authSessionRequiredResult()
    val envelope =
        runCatching {
            agentControlsJson.decodeFromString(
                AgentControlsErrorDto.serializer(),
                response.errorBodyText ?: response.bodyText.orEmpty()
            )
        }.getOrNull()
    val serverCode = envelope?.code?.takeIf { it.isNotBlank() }
    val serverMessage = envelope?.error?.takeIf { it.isNotBlank() }
    if (response.code == 403 && serverCode == null) {
        return DataResult.NoPermission(
            PermissionBlocker(code = "http_403", serverMessage = serverMessage)
        )
    }
    return DataResult.Error(
        ScreenError(
            code = serverCode ?: "http_${response.code}",
            retryable = response.code >= 500 || response.code == 409,
            serverMessage = serverMessage,
            serverCode = serverCode
        )
    )
}

// ── Channel SOS ──
// POST /channels/:id/stop-all-agents and /channels/:id/resume-all-agents,
// packages/server/src/routes/channels.ts; web components/message/SOSDialog.tsx.

/** A resume `reason` meaning another start for that agent was already in flight. */
internal const val CHANNEL_RESUME_START_IN_FLIGHT = "start_in_flight"

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

internal data class ChannelStopAllAgentsResult(val stopped: Int, val total: Int)

internal data class ChannelResumeMiss(val agentId: String, val reason: String?)

internal data class ChannelResumeAllAgentsResult(
    val started: Int,
    val total: Int,
    val missed: List<ChannelResumeMiss>
)

private val agentControlsRequestJson = Json { explicitNulls = false }

internal fun parseSharedChannelStopAllAgents(bodyText: String): ChannelStopAllAgentsResult? =
    runCatching {
        val dto = agentControlsJson.decodeFromString(ChannelStopAllAgentsResponseDto.serializer(), bodyText)
        ChannelStopAllAgentsResult(stopped = dto.stopped, total = dto.total)
    }.getOrNull()

internal fun parseSharedChannelResumeAllAgents(bodyText: String): ChannelResumeAllAgentsResult? =
    runCatching {
        val dto = agentControlsJson.decodeFromString(ChannelResumeAllAgentsResponseDto.serializer(), bodyText)
        ChannelResumeAllAgentsResult(
            started = dto.started,
            total = dto.total,
            missed = dto.results.filterNot { it.ok }.map { ChannelResumeMiss(it.agentId, it.reason) }
        )
    }.getOrNull()

internal fun channelResumeAllAgentsRequestBody(prompt: String, agentIds: List<String>?): String =
    agentControlsRequestJson.encodeToString(
        ChannelResumeAllAgentsRequestDto.serializer(),
        ChannelResumeAllAgentsRequestDto(prompt = prompt, agentIds = agentIds)
    )

/** The one shared request boundary for the agent remote controls. */
internal class RaftAgentControlsApi(
    private val networkClient: RaftNetworkClient
) : ChannelSosDataSource {
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

    private suspend fun <T> send(
        priority: DataRequestPriority,
        path: String,
        method: String = "GET",
        bodyText: String? = null,
        parse: (String) -> T?
    ): DataResult<T> =
        try {
            val response = networkClient.request(
                priority = priority,
                path = path,
                method = method,
                bodyText = bodyText
            )
            if (!response.isSuccessful) {
                agentControlsErrorResult(response)
            } else {
                parse(response.bodyText.orEmpty())?.let { DataResult.Content(it) }
                    ?: DataResult.Error(ScreenError(AgentControlErrorCodes.ContractMismatch, retryable = false))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DataResult.Error(ScreenError(AgentControlErrorCodes.NetworkError))
        }
}
