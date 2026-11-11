package build.raft.app.data

import build.raft.app.model.MemberId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One short line of feedback for a stop-this-sub-agent request; web `stopSubagentWithFeedback`
 * (a toast per outcome, a dedicated message for the refusals retrying won't fix, a plain
 * failure otherwise). [Failed] keeps the request's error for its trace ID.
 */
internal sealed interface AgentSubagentStopFeedback {
    data object Interrupted : AgentSubagentStopFeedback
    data object NotRunning : AgentSubagentStopFeedback
    data object Unsupported : AgentSubagentStopFeedback
    data class UpgradeRequired(val error: ScreenError) : AgentSubagentStopFeedback
    data class ComputerOffline(val error: ScreenError) : AgentSubagentStopFeedback
    data class Failed(val error: ScreenError?) : AgentSubagentStopFeedback
}

internal fun DataResult<AgentSubagentStopOutcome>.toAgentSubagentStopFeedback(): AgentSubagentStopFeedback =
    when (this) {
        is DataResult.Content ->
            when (data) {
                AgentSubagentStopOutcome.Interrupted -> AgentSubagentStopFeedback.Interrupted
                AgentSubagentStopOutcome.NotRunning -> AgentSubagentStopFeedback.NotRunning
                AgentSubagentStopOutcome.Unsupported -> AgentSubagentStopFeedback.Unsupported
            }
        is DataResult.Error ->
            when (error.serverCode) {
                AgentControlErrorCodes.ComputerUpgradeRequired -> AgentSubagentStopFeedback.UpgradeRequired(error)
                AgentControlErrorCodes.ComputerOffline -> AgentSubagentStopFeedback.ComputerOffline(error)
                else -> AgentSubagentStopFeedback.Failed(error)
            }
        is DataResult.Empty -> AgentSubagentStopFeedback.Failed(null)
        is DataResult.NoPermission -> AgentSubagentStopFeedback.Failed(null)
    }

internal data class AgentSubagentStopState(
    /** The sub-agent whose stop is out; its Stop button is disabled meanwhile. */
    val inFlightTaskId: String? = null,
    /** One-shot feedback for [feedbackAgentId]; the UI shows it once and dispatches [AgentSubagentStopIntent.ConsumeFeedback]. */
    val feedback: AgentSubagentStopFeedback? = null,
    val feedbackAgentId: MemberId? = null,
)

internal sealed interface AgentSubagentStopIntent {
    data class Stop(val agentId: MemberId, val subagentTaskId: String) : AgentSubagentStopIntent
    data object ConsumeFeedback : AgentSubagentStopIntent
}

/**
 * Stops one running sub-agent (the agent and its other sub-agents keep running). No
 * confirmation, as on web: the stop is idempotent. Single-flight: a second tap while a request
 * is out is a no-op. An id the server would refuse (over [AgentSubagentTaskIdMaxLength]) is not sent.
 */
internal class AgentSubagentStopStore(
    private val interruptSubagent: suspend (MemberId, String) -> DataResult<AgentSubagentStopOutcome>,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(AgentSubagentStopState())
    val state: StateFlow<AgentSubagentStopState> = mutableState.asStateFlow()

    fun dispatch(intent: AgentSubagentStopIntent) {
        when (intent) {
            is AgentSubagentStopIntent.Stop -> stop(intent.agentId, intent.subagentTaskId)
            AgentSubagentStopIntent.ConsumeFeedback -> {
                val current = mutableState.value
                if (current.feedback != null) mutableState.value = current.copy(feedback = null, feedbackAgentId = null)
            }
        }
    }

    private fun stop(agentId: MemberId, taskId: String) {
        if (mutableState.value.inFlightTaskId != null) return
        if (taskId.isBlank() || taskId.length > AgentSubagentTaskIdMaxLength) return
        mutableState.value = mutableState.value.copy(inFlightTaskId = taskId, feedback = null, feedbackAgentId = null)
        scope.launch {
            val result =
                try {
                    interruptSubagent(agentId, taskId)
                } catch (cancellation: CancellationException) {
                    mutableState.value = mutableState.value.copy(inFlightTaskId = null)
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError(AgentControlErrorCodes.NetworkError))
                }
            mutableState.value =
                mutableState.value.copy(
                    inFlightTaskId = null,
                    feedback = result.toAgentSubagentStopFeedback(),
                    feedbackAgentId = agentId,
                )
        }
    }
}
