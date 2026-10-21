package build.raft.app.data

import build.raft.app.model.ChannelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Web `SOSDialog` phases: confirm the stop, then optionally resume with guidance. */
internal enum class ChannelSosPhase {
    Confirm,
    Stopped
}

/** Agents a resume did not reach; a retry names only these (web `ResumeShortfall`). */
internal data class ChannelSosResumeShortfall(
    val starting: Int,
    val failed: Int,
    val agentIds: List<String>
)

internal data class ChannelSosState(
    val phase: ChannelSosPhase = ChannelSosPhase.Confirm,
    val stopping: Boolean = false,
    val resuming: Boolean = false,
    val guidance: String = "",
    val shortfall: ChannelSosResumeShortfall? = null,
    /** The last request failed; the dialog stays where it was (web keeps the phase). */
    val errorCode: String? = null,
    /** One-shot: the resume reached every agent, so the dialog closes. */
    val closed: Boolean = false
) {
    val canResume: Boolean
        get() = phase == ChannelSosPhase.Stopped && guidance.isNotBlank() && !resuming
}

internal sealed interface ChannelSosIntent {
    data object Stop : ChannelSosIntent
    data class UpdateGuidance(val text: String) : ChannelSosIntent
    data object Resume : ChannelSosIntent
}

internal interface ChannelSosDataSource {
    suspend fun stopAll(channelId: ChannelId): DataResult<ChannelStopAllAgentsResult>
    suspend fun resumeAll(
        channelId: ChannelId,
        prompt: String,
        agentIds: List<String>?
    ): DataResult<ChannelResumeAllAgentsResult>
}

/**
 * Emergency stop for every agent in one channel (web `SOSDialog`): stop all,
 * then resume all with the person's correction, which the agents receive as
 * their resume prompt. One store per open dialog.
 */
internal class ChannelSosStore(
    private val channelId: ChannelId,
    private val channelName: String,
    private val dataSource: ChannelSosDataSource,
    private val scope: CoroutineScope
) {
    private val mutableState = MutableStateFlow(ChannelSosState())
    val state: StateFlow<ChannelSosState> = mutableState.asStateFlow()

    fun dispatch(intent: ChannelSosIntent) {
        when (intent) {
            ChannelSosIntent.Stop -> stop()
            is ChannelSosIntent.UpdateGuidance -> {
                if (mutableState.value.resuming) return
                if (mutableState.value.guidance != intent.text) {
                    mutableState.value = mutableState.value.copy(guidance = intent.text)
                }
            }
            ChannelSosIntent.Resume -> resume()
        }
    }

    private fun stop() {
        val current = mutableState.value
        if (current.phase != ChannelSosPhase.Confirm || current.stopping) return
        mutableState.value = current.copy(stopping = true, errorCode = null)
        scope.launch {
            val result = guarded("channel_sos_stop_failed") { dataSource.stopAll(channelId) }
            mutableState.value =
                when (result) {
                    is DataResult.Content -> mutableState.value.copy(stopping = false, phase = ChannelSosPhase.Stopped)
                    else -> mutableState.value.copy(stopping = false, errorCode = result.channelSosErrorCode("channel_sos_stop_failed"))
                }
        }
    }

    private fun resume() {
        val current = mutableState.value
        if (!current.canResume) return
        val retryIds = current.shortfall?.agentIds
        mutableState.value = current.copy(resuming = true, errorCode = null)
        scope.launch {
            val prompt = channelSosResumePrompt(channelName, current.guidance)
            val result = guarded("channel_sos_resume_failed") { dataSource.resumeAll(channelId, prompt, retryIds) }
            mutableState.value =
                when (result) {
                    is DataResult.Content -> {
                        val missed = result.data.missed
                        if (missed.isEmpty()) {
                            mutableState.value.copy(resuming = false, shortfall = null, closed = true)
                        } else {
                            val starting = missed.count { it.reason == CHANNEL_RESUME_START_IN_FLIGHT }
                            mutableState.value.copy(
                                resuming = false,
                                shortfall =
                                    ChannelSosResumeShortfall(
                                        starting = starting,
                                        failed = missed.size - starting,
                                        agentIds = missed.map { it.agentId }
                                    )
                            )
                        }
                    }
                    else -> mutableState.value.copy(resuming = false, errorCode = result.channelSosErrorCode("channel_sos_resume_failed"))
                }
        }
    }

    private suspend fun <T> guarded(fallbackCode: String, block: suspend () -> DataResult<T>): DataResult<T> =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(fallbackCode))
        }
}

private fun DataResult<*>.channelSosErrorCode(fallback: String): String =
    when (this) {
        is DataResult.Error -> error.code
        is DataResult.NoPermission -> blocker.code
        is DataResult.Content,
        is DataResult.Empty -> fallback
    }

/**
 * The resume prompt web `SOSDialog.handleResume` sends. It is an instruction
 * to the agents, not UI copy, so it stays in English like web's.
 */
internal fun channelSosResumePrompt(channelName: String, guidance: String): String =
    "[SOS] The user has emergency-stopped all agents in #$channelName because they were going off-track. " +
        "Here is the user's correction and new guidance:\n\n${guidance.trim()}\n\n" +
        "Read this carefully, acknowledge the correction, and adjust your approach accordingly. " +
        "Use check_messages and read_history to understand the current state before taking any action."
