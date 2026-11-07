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
 * holds from the tap until the open settles. The busy chip is not drawn from here: it is the
 * reference resolver's fetch phase (`SharedReferenceResolver.fetching`), so a reference opened from
 * local data never shows a spinner and one that fetches shows it for exactly the fetch.
 */
internal data class ThreadMarkdownReferencePendingState(
    val active: ThreadMarkdownReferencePendingToken? = null,
    val generation: Long = 0L
) {
    fun start(actionId: String): ThreadMarkdownReferencePendingStart {
        val normalizedActionId = actionId.trim()
        if (normalizedActionId.isBlank() || active != null) {
            return ThreadMarkdownReferencePendingStart(state = this, token = null)
        }
        val token = ThreadMarkdownReferencePendingToken(normalizedActionId, generation + 1L)
        return ThreadMarkdownReferencePendingStart(
            state = copy(active = token, generation = token.generation),
            token = token
        )
    }

    fun finish(token: ThreadMarkdownReferencePendingToken): ThreadMarkdownReferencePendingState =
        if (active == token) copy(active = null) else this

    /** The chip drawn busy: this list's open, while the resolver fetches for it. */
    fun busyActionId(fetchingActionId: String?): String? =
        active?.actionId?.takeIf { fetchingActionId != null && it == fetchingActionId }
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
 * After [finish] the guard holds [settleMillis] longer, so a double tap on an instant open (a
 * local hit: the navigation is dispatched within the tap) cannot push the destination twice while
 * the page transition starts.
 */
internal class ThreadMarkdownRefOpenStore(
    private val scope: CoroutineScope,
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
        return started
    }

    /** The open was dispatched or failed. A null or stale token is a no-op. */
    fun finish(token: ThreadMarkdownReferencePendingToken?) {
        if (token == null || mutableState.value.active != token) return
        timerJob?.cancel()
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

/** How long a settled open keeps ignoring ref taps while its page transition starts. */
internal const val ThreadMarkdownRefOpenSettleMillis = 600L
