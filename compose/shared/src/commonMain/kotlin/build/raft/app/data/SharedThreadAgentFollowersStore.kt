package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.TimeSource

internal data class ThreadFollowerUndo(val agent: ThreadAgentFollower, val token: String, val deadline: Long)
internal enum class ThreadFollowersNoticeKind { Removed, Restored, RemoveFailed, RestoreFailed }
internal data class ThreadFollowersNotice(val id: Long, val kind: ThreadFollowersNoticeKind, val agentLabel: String)
internal enum class ThreadFollowersError { Load, Remove, Restore }
internal data class ThreadAgentFollowersState(
    val roster: ThreadAgentFollowerRoster? = null,
    val loading: Boolean = false,
    val open: Boolean = false,
    val mutating: Boolean = false,
    val undo: ThreadFollowerUndo? = null,
    val error: ThreadFollowersError? = null,
    val notice: ThreadFollowersNotice? = null,
)
internal sealed interface ThreadAgentFollowersIntent {
    data object Refresh : ThreadAgentFollowersIntent
    data object Open : ThreadAgentFollowersIntent
    data object Close : ThreadAgentFollowersIntent
    data class Remove(val agentId: String) : ThreadAgentFollowersIntent
    data object Undo : ThreadAgentFollowersIntent
}

/** Route-owned MVI store. Reads coalesce; mutation revisions fence stale reads. */
internal class SharedThreadAgentFollowersStore(
    private val repository: ThreadAgentFollowersRepository,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = monotonicFollowerClock(),
) {
    private val mutableState = MutableStateFlow(ThreadAgentFollowersState())
    val state: StateFlow<ThreadAgentFollowersState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var expiryJob: Job? = null
    private var noticeJob: Job? = null
    private var noticeId = 0L
    private var refreshPending = false
    private var revision = 0L
    private var closed = false

    fun dispatch(intent: ThreadAgentFollowersIntent) {
        if (closed) return
        when (intent) {
            ThreadAgentFollowersIntent.Refresh -> refresh()
            ThreadAgentFollowersIntent.Open -> {
                mutableState.value = state.value.copy(open = true)
                refresh()
            }
            ThreadAgentFollowersIntent.Close -> mutableState.value = state.value.copy(open = false)
            is ThreadAgentFollowersIntent.Remove -> remove(intent.agentId)
            ThreadAgentFollowersIntent.Undo -> undo()
        }
    }

    private fun refresh() {
        if (state.value.loading || state.value.mutating) {
            refreshPending = true
            return
        }
        mutableState.value = state.value.copy(loading = true, error = state.value.error.takeUnless { it == ThreadFollowersError.Load })
        val expectedRevision = revision
        loadJob = scope.launch {
            val result = repository.load()
            if (closed) return@launch
            if (expectedRevision == revision) {
                mutableState.value = when (result) {
                    is DataResult.Content -> state.value.copy(
                        roster = result.data, loading = false,
                        error = state.value.error.takeUnless { it == ThreadFollowersError.Load },
                    )
                    else -> state.value.copy(loading = false, error = ThreadFollowersError.Load)
                }
            } else mutableState.value = state.value.copy(loading = false)
            drainRefresh()
        }
    }

    private fun remove(id: String) {
        val current = state.value
        if (current.mutating || current.undo != null) return
        val roster = current.roster ?: return
        val agent = roster.agents.singleOrNull { it.id == id } ?: return
        if (!roster.canManage || !agent.canRemove || !agent.isCurrentServer) return
        revision++
        mutableState.value = current.copy(mutating = true, error = null)
        mutationJob = scope.launch {
            when (val result = repository.remove(id)) {
                is DataResult.Content -> {
                    if (closed) return@launch
                    val removal = result.data
                    val pendingUndo = if (removal.removed && !removal.undoToken.isNullOrBlank()) {
                        ThreadFollowerUndo(agent, removal.undoToken, nowMillis() + 5_000L)
                    } else null
                    mutableState.value = state.value.copy(
                        roster = state.value.roster?.let { it.copy(agents = it.agents.filterNot { row -> row.id == id }) },
                        mutating = false, undo = pendingUndo,
                    )
                    showNotice(ThreadFollowersNoticeKind.Removed, agent.label)
                    expiryJob?.cancel()
                    if (pendingUndo != null) expiryJob = scope.launch {
                        delay(5_000L)
                        if (state.value.undo == pendingUndo) mutableState.value = state.value.copy(undo = null)
                    }
                }
                else -> if (!closed) {
                    mutableState.value = state.value.copy(mutating = false, error = ThreadFollowersError.Remove)
                    showNotice(ThreadFollowersNoticeKind.RemoveFailed, agent.label)
                }
            }
            if (!closed) { refreshPending = true; drainRefresh() }
        }
    }

    private fun undo() {
        val current = state.value
        val pending = current.undo ?: return
        if (current.mutating) return
        if (nowMillis() >= pending.deadline) {
            mutableState.value = current.copy(undo = null)
            return
        }
        revision++
        expiryJob?.cancel()
        mutableState.value = current.copy(mutating = true, error = null, undo = null)
        mutationJob = scope.launch {
            val result = repository.restore(pending.agent.id, pending.token)
            if (closed) return@launch
            val restored = result is DataResult.Content && result.data
            mutableState.value = state.value.copy(
                mutating = false,
                error = if (restored) null else ThreadFollowersError.Restore,
                roster = if (restored) state.value.roster?.let {
                    it.copy(agents = (it.agents + pending.agent).distinctBy { agent -> agent.id })
                } else state.value.roster,
            )
            showNotice(if (restored) ThreadFollowersNoticeKind.Restored else ThreadFollowersNoticeKind.RestoreFailed, pending.agent.label)
            // Refresh canonical permissions and any concurrent follow/unfollow.
            refreshPending = true
            drainRefresh()
        }
    }

    private fun showNotice(kind: ThreadFollowersNoticeKind, label: String) {
        val notice = ThreadFollowersNotice(++noticeId, kind, label)
        mutableState.value = state.value.copy(notice = notice)
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(5_000L)
            if (state.value.notice == notice) mutableState.value = state.value.copy(notice = null)
        }
    }

    private fun drainRefresh() {
        if (refreshPending && !state.value.loading && !state.value.mutating) {
            refreshPending = false
            refresh()
        }
    }

    fun close() {
        closed = true
        loadJob?.cancel()
        mutationJob?.cancel()
        expiryJob?.cancel()
        noticeJob?.cancel()
    }
}

private fun monotonicFollowerClock(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
