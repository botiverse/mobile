package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the Artifacts group of global search stands. */
enum class SearchArtifactsStatus {
    /** Nothing to show: no words, Artifacts off for the server, or the answer has no group. */
    Idle,

    /** Waiting for the group's first page (its heading and skeleton show). */
    Loading,

    /** The group's results (possibly none: then the section is hidden, web "empty sections hidden"). */
    Ok,

    /** It took too long or failed: the section says so, with Retry. */
    Failed,
}

/**
 * The Artifacts group of global search (web `useAppSearchGroups` `AppGroupState`): the words it
 * answers, its own loading, failure and paging, apart from the message search beside it.
 */
data class SearchArtifactsState(
    val serverId: String? = null,
    /** The trimmed words the group answers ("" with none). */
    val query: String = "",
    val status: SearchArtifactsStatus = SearchArtifactsStatus.Idle,
    val hits: List<SearchArtifactHit> = emptyList(),
    val nextCursor: String? = null,
    /** Load more is reading the next page (the rows on screen stay). */
    val paging: Boolean = false,
    /** With [SearchArtifactsStatus.Failed]: the group took too long (web `search.appGroup.timedOut`). */
    val timedOut: Boolean = false,
    /** With [SearchArtifactsStatus.Failed]: the error, its trace id shown under the warning. */
    val error: ScreenError? = null,
) {
    /** The section shows: waiting, failed, or with results (web: an app with nothing for these words has no section). */
    val shown: Boolean
        get() = status == SearchArtifactsStatus.Loading || status == SearchArtifactsStatus.Failed || hits.isNotEmpty()
}

sealed interface SearchArtifactsIntent {
    /**
     * The search's words for [serverId]; [enabled] is false while workspace_artifacts_v0 is off or
     * not yet known for the server, or in a conversation's own search (the groups are
     * server-wide): then nothing is asked. The same words again are a no-op.
     */
    data class Query(val serverId: String?, val query: String, val enabled: Boolean) : SearchArtifactsIntent

    /** The section's Retry: the group again, at once. */
    data object Retry : SearchArtifactsIntent

    /** The group's next page. */
    data object LoadMore : SearchArtifactsIntent
}

sealed interface SearchArtifactsEvent {
    /** Load more failed; the rows on screen stay (web: a warning toast, `search.appGroup.moreFailed`). */
    data class MoreFailed(val error: ScreenError) : SearchArtifactsEvent
}

/**
 * The Artifacts group's store (web `useAppSearchGroups`, slock staging 93207e216):
 *
 * - New words wait for a pause ([debounceMillis], the message search's), then read the first page;
 *   the old words' rows leave at once and their heading waits with a skeleton (web `start` with the
 *   expected apps). An answer to older words is dropped ([generation]).
 * - [limit]: per page; the wide panel previews 5 (web `OVERLAY_MESSAGE_PREVIEW_LIMIT`), the page
 *   leaves it to the server (20).
 * - A group that timed out or failed, or no answer at all, is [SearchArtifactsStatus.Failed] with
 *   its error; Retry reads it again. Load more appends the next page without repeats; a failure
 *   keeps the rows and says so once ([SearchArtifactsEvent.MoreFailed]).
 * - Off (flag off or unknown, no server, no words, words too long): no request.
 */
@RaftCoreInternalApi class SharedSearchArtifactsStore(
    private val source: SearchArtifactsSource,
    private val scope: CoroutineScope,
    private val limit: Int? = null,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val logger: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(SearchArtifactsState())
    val state: StateFlow<SearchArtifactsState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<SearchArtifactsEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<SearchArtifactsEvent> = mutableEvents.asSharedFlow()

    private var generation = 0
    private var asked: SearchArtifactsIntent.Query? = null
    private var readJob: Job? = null
    private var pageJob: Job? = null

    fun dispatch(intent: SearchArtifactsIntent) {
        when (intent) {
            is SearchArtifactsIntent.Query -> query(intent)
            SearchArtifactsIntent.Retry -> retry()
            SearchArtifactsIntent.LoadMore -> loadMore()
        }
    }

    fun clear() {
        generation++
        readJob?.cancel()
        pageJob?.cancel()
    }

    private fun query(intent: SearchArtifactsIntent.Query) {
        val serverId = intent.serverId?.trim()?.takeIf { it.isNotEmpty() }
        val words = webSearchQueryOrNull(intent.query)
        val normalized = SearchArtifactsIntent.Query(serverId, words.orEmpty(), intent.enabled && serverId != null && words != null)
        if (normalized == asked) return
        asked = normalized
        generation++
        readJob?.cancel()
        pageJob?.cancel()
        if (!normalized.enabled) {
            mutableState.value = SearchArtifactsState(serverId = serverId)
            return
        }
        mutableState.value = SearchArtifactsState(serverId = serverId, query = normalized.query, status = SearchArtifactsStatus.Loading)
        read(debounce = true)
    }

    private fun retry() {
        val current = mutableState.value
        if (current.status != SearchArtifactsStatus.Failed || current.serverId == null || current.query.isEmpty()) return
        mutableState.value = current.copy(status = SearchArtifactsStatus.Loading, error = null, timedOut = false)
        read(debounce = false)
    }

    private fun read(debounce: Boolean) {
        val generation = generation
        val current = mutableState.value
        val serverId = current.serverId ?: return
        val words = current.query
        readJob?.cancel()
        readJob =
            scope.launch {
                if (debounce && debounceMillis > 0) delay(debounceMillis)
                val load = source.search(serverId, words, cursor = null, limit = limit, priority = DataRequestPriority.Visible)
                if (generation != this@SharedSearchArtifactsStore.generation) {
                    logger("search_artifacts_load result=stale")
                    return@launch
                }
                val base = mutableState.value
                mutableState.value =
                    when (load) {
                        is WebSearchArtifactsLoad.Page ->
                            base.copy(status = SearchArtifactsStatus.Ok, hits = load.hits.distinctBy { it.id }, nextCursor = load.nextCursor, error = null, timedOut = false)
                        is WebSearchArtifactsLoad.GroupFailed ->
                            base.copy(status = SearchArtifactsStatus.Failed, hits = emptyList(), nextCursor = null, error = load.error, timedOut = load.timedOut)
                        is WebSearchArtifactsLoad.Failed ->
                            base.copy(status = SearchArtifactsStatus.Failed, hits = emptyList(), nextCursor = null, error = load.error, timedOut = false)
                        WebSearchArtifactsLoad.NoGroup -> base.copy(status = SearchArtifactsStatus.Idle, hits = emptyList(), nextCursor = null, error = null)
                    }
                logger("search_artifacts_load ${load.diagnostics()} queryLen=${words.length}")
            }
    }

    private fun loadMore() {
        val current = mutableState.value
        val cursor = current.nextCursor
        val serverId = current.serverId
        if (current.status != SearchArtifactsStatus.Ok || current.paging || cursor == null || serverId == null) return
        val generation = generation
        mutableState.value = current.copy(paging = true)
        pageJob =
            scope.launch {
                val load = source.search(serverId, current.query, cursor = cursor, limit = limit, priority = DataRequestPriority.Visible)
                if (generation != this@SharedSearchArtifactsStore.generation) return@launch
                val base = mutableState.value
                when (load) {
                    is WebSearchArtifactsLoad.Page -> {
                        // The index may have moved between pages: an Artifact already shown isn't shown twice.
                        val seen = base.hits.mapTo(HashSet()) { it.id }
                        mutableState.value = base.copy(paging = false, hits = base.hits + load.hits.filter { seen.add(it.id) }, nextCursor = load.nextCursor)
                    }
                    is WebSearchArtifactsLoad.GroupFailed -> {
                        mutableState.value = base.copy(paging = false)
                        mutableEvents.tryEmit(SearchArtifactsEvent.MoreFailed(load.error))
                    }
                    is WebSearchArtifactsLoad.Failed -> {
                        mutableState.value = base.copy(paging = false)
                        mutableEvents.tryEmit(SearchArtifactsEvent.MoreFailed(load.error))
                    }
                    // Switched off since: nothing more to read.
                    WebSearchArtifactsLoad.NoGroup -> mutableState.value = base.copy(paging = false, nextCursor = null)
                }
                logger("search_artifacts_more ${load.diagnostics()}")
            }
    }

    private fun WebSearchArtifactsLoad.diagnostics(): String =
        when (this) {
            is WebSearchArtifactsLoad.Page -> "result=ok count=${hits.size} hasMore=${nextCursor != null}"
            is WebSearchArtifactsLoad.GroupFailed -> "result=group_${if (timedOut) "timeout" else "failed"}"
            is WebSearchArtifactsLoad.Failed -> "result=failed code=${error.code}"
            WebSearchArtifactsLoad.NoGroup -> "result=no_group"
        }

    companion object {
        /** The message search's debounce beside it (web `APP_SEARCH_DEBOUNCE_MS`: "the same debounce as the message search"). */
        const val DEBOUNCE_MILLIS: Long = 250L

        /** The wide panel's preview (web `OVERLAY_MESSAGE_PREVIEW_LIMIT`, sent as `limit`). */
        const val OVERLAY_LIMIT: Int = 5
    }
}
