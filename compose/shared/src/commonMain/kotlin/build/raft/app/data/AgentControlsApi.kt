package build.raft.app.data

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

/** The one shared request boundary for the agent remote controls. */
internal class RaftAgentControlsApi(
    private val networkClient: RaftNetworkClient
) {
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
