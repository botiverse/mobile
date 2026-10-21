package build.raft.app.data

import build.raft.app.model.MemberId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One short line of feedback for a stop-this-turn request; web
 * `stopAgentTurnWithFeedback` (success toast per outcome, a dedicated message
 * for the refusals retrying won't fix, a plain failure otherwise).
 */
internal enum class AgentTurnStopFeedback {
    Interrupted,
    Restarted,
    NotRunning,
    UpgradeRequired,
    ComputerOffline,
    Failed;

    val isError: Boolean
        get() = this == UpgradeRequired || this == ComputerOffline || this == Failed
}

internal fun DataResult<AgentTurnStopOutcome>.toAgentTurnStopFeedback(): AgentTurnStopFeedback =
    when (this) {
        is DataResult.Content ->
            when (data) {
                AgentTurnStopOutcome.Interrupted -> AgentTurnStopFeedback.Interrupted
                AgentTurnStopOutcome.Restarted -> AgentTurnStopFeedback.Restarted
                AgentTurnStopOutcome.NotRunning -> AgentTurnStopFeedback.NotRunning
            }
        is DataResult.Error ->
            when (error.serverCode) {
                AgentControlErrorCodes.ComputerUpgradeRequired -> AgentTurnStopFeedback.UpgradeRequired
                AgentControlErrorCodes.ComputerOffline -> AgentTurnStopFeedback.ComputerOffline
                else -> AgentTurnStopFeedback.Failed
            }
        is DataResult.Empty,
        is DataResult.NoPermission -> AgentTurnStopFeedback.Failed
    }

internal data class AgentTurnStopState(
    val inFlightAgentId: MemberId? = null,
    /** One-shot feedback for [agentId]; the UI shows it once and dispatches [AgentTurnStopIntent.ConsumeFeedback]. */
    val feedback: AgentTurnStopFeedback? = null,
    val feedbackAgentId: MemberId? = null
)

internal sealed interface AgentTurnStopIntent {
    data class Stop(val agentId: MemberId) : AgentTurnStopIntent
    data object ConsumeFeedback : AgentTurnStopIntent
}

/**
 * Stops an agent's running turn (the agent stays). No confirmation, as on web:
 * stopping a runaway turn must be quick. Single-flight: a second tap while a
 * request is out is a no-op (the server stop is idempotent anyway).
 */
internal class AgentTurnStopStore(
    private val interruptTurn: suspend (MemberId) -> DataResult<AgentTurnStopOutcome>,
    private val scope: CoroutineScope
) {
    private val mutableState = MutableStateFlow(AgentTurnStopState())
    val state: StateFlow<AgentTurnStopState> = mutableState.asStateFlow()

    fun dispatch(intent: AgentTurnStopIntent) {
        when (intent) {
            is AgentTurnStopIntent.Stop -> stop(intent.agentId)
            AgentTurnStopIntent.ConsumeFeedback -> {
                val current = mutableState.value
                if (current.feedback != null) {
                    mutableState.value = current.copy(feedback = null, feedbackAgentId = null)
                }
            }
        }
    }

    private fun stop(agentId: MemberId) {
        if (mutableState.value.inFlightAgentId != null) return
        mutableState.value = mutableState.value.copy(inFlightAgentId = agentId, feedback = null, feedbackAgentId = null)
        scope.launch {
            val result =
                try {
                    interruptTurn(agentId)
                } catch (cancellation: CancellationException) {
                    mutableState.value = mutableState.value.copy(inFlightAgentId = null)
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError(AgentControlErrorCodes.NetworkError))
                }
            mutableState.value =
                mutableState.value.copy(
                    inFlightAgentId = null,
                    feedback = result.toAgentTurnStopFeedback(),
                    feedbackAgentId = agentId
                )
        }
    }
}
