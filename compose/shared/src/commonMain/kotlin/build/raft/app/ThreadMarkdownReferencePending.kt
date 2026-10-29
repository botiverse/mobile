package build.raft.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ThreadMarkdownReferencePendingToken(
    val actionId: String,
    val generation: Long
)

/**
 * One reference-chip open at a time per message list. [active] is the single-flight guard: it
 * holds from the tap until the open settles. [busyActionId] is the chip drawn busy, a subset of
 * it in time: set only once the open has outlived [ThreadMarkdownRefOpenStore.revealDelayMillis]
 * (an instant open shows no spinner) and cleared as soon as the navigation is dispatched or fails.
 */
internal data class ThreadMarkdownReferencePendingState(
    val active: ThreadMarkdownReferencePendingToken? = null,
    val generation: Long = 0L,
    val busyActionId: String? = null
) {
    fun start(actionId: String): ThreadMarkdownReferencePendingStart {
        val normalizedActionId = actionId.trim()
        if (normalizedActionId.isBlank() || active != null) {
            return ThreadMarkdownReferencePendingStart(state = this, token = null)
        }
        val token = ThreadMarkdownReferencePendingToken(normalizedActionId, generation + 1L)
        return ThreadMarkdownReferencePendingStart(
            state = copy(active = token, generation = token.generation, busyActionId = null),
            token = token
        )
    }

    fun reveal(token: ThreadMarkdownReferencePendingToken): ThreadMarkdownReferencePendingState =
        if (active == token && busyActionId == null) copy(busyActionId = token.actionId) else this

    /** The open was dispatched or failed: the chip is restored; the guard stays until [release]. */
    fun settle(token: ThreadMarkdownReferencePendingToken): ThreadMarkdownReferencePendingState =
        if (active == token && busyActionId != null) copy(busyActionId = null) else this

    fun finish(token: ThreadMarkdownReferencePendingToken): ThreadMarkdownReferencePendingState =
        if (active == token) copy(active = null, busyActionId = null) else this
}

internal data class ThreadMarkdownReferencePendingStart(
    val state: ThreadMarkdownReferencePendingState,
    val token: ThreadMarkdownReferencePendingToken?
)

/**
 * Store for reference-chip opens (artin 10-29: "点击这个 能不能有点加载态 还能防呆"). Every
 * channel / thread / DM / task ref open in one message list goes through [begin] and [finish];
 * a tap while another open is in flight gets no token and is dropped, whichever chip it hits.
 *
 * After [finish] the guard holds [settleMillis] longer with the chip already restored, so a
 * double tap on an instant open (no lookup: the navigation is dispatched within the tap) cannot
 * push the destination twice while the page transition starts.
 */
internal class ThreadMarkdownRefOpenStore(
    private val scope: CoroutineScope,
    val revealDelayMillis: Long = ThreadMarkdownRefBusyRevealDelayMillis,
    val settleMillis: Long = ThreadMarkdownRefOpenSettleMillis
) {
    private val mutableState = MutableStateFlow(ThreadMarkdownReferencePendingState())
    val state: StateFlow<ThreadMarkdownReferencePendingState> = mutableState.asStateFlow()

    private var timerJob: Job? = null

    /** The open's token, or null when another open holds the list (the tap is ignored). */
    fun begin(actionId: String?): ThreadMarkdownReferencePendingToken? {
        var token: ThreadMarkdownReferencePendingToken? = null
        mutableState.update { current ->
            val started = current.start(actionId.orEmpty())
            token = started.token
            started.state
        }
        val started = token ?: return null
        timerJob?.cancel()
        timerJob =
            scope.launch {
                delay(revealDelayMillis)
                mutableState.update { it.reveal(started) }
            }
        return started
    }

    /** The open was dispatched or failed. A null or stale token is a no-op. */
    fun finish(token: ThreadMarkdownReferencePendingToken?) {
        if (token == null || mutableState.value.active != token) return
        timerJob?.cancel()
        mutableState.update { it.settle(token) }
        if (settleMillis <= 0L) {
            mutableState.update { it.finish(token) }
            return
        }
        timerJob =
            scope.launch {
                delay(settleMillis)
                mutableState.update { it.finish(token) }
            }
    }
}

/** Under this an open reads as instant: its chip never shows the spinner. */
internal const val ThreadMarkdownRefBusyRevealDelayMillis = 120L

/** How long a settled open keeps ignoring ref taps while its page transition starts. */
internal const val ThreadMarkdownRefOpenSettleMillis = 600L
