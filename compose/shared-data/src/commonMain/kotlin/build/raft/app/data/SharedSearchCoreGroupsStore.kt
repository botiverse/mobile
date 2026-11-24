package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where one of core's groups stands for the words in the field (web `CoreGroupState`). */
enum class SearchCoreGroupStatus {
    /** Not asked: no words, off (a conversation's own search), or the server has no such group. */
    Idle,

    /** Asked, not answered yet: the local rows show without a count or Show all. */
    Loading,

    /** The server's rows (possibly none): they replace the local rows of the group. */
    Ok,

    /** No answer (offline, an error, a group `error`): the local rows stay, with a note and Retry. */
    Failed,
}

/** One core group's answer for [query] (web `CoreGroupState`). */
data class SearchCoreGroupState<T>(
    val status: SearchCoreGroupStatus = SearchCoreGroupStatus.Idle,
    /** The trimmed words this state answers. */
    val query: String = "",
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
    /** Every match the group has for [query] (the server's `total`). */
    val total: Int = 0,
    /** A next page is loading (the rows on screen stay). */
    val paging: Boolean = false,
    /** The last next page failed: the section shows Retry in Load more's place (no toast). */
    val pageFailed: Boolean = false,
    /** [SearchCoreGroupStatus.Loading] after a failure: asked again through Retry. */
    val retrying: Boolean = false,
    /** The failure ([SearchCoreGroupStatus.Failed], or the failed page), its trace id kept. */
    val error: ScreenError? = null,
)

/** Core's groups of global search (web `useCoreSearchGroups` state). */
data class SearchCoreGroupsState(
    val serverId: String? = null,
    val channels: SearchCoreGroupState<SearchChannelHit> = SearchCoreGroupState(),
    val people: SearchCoreGroupState<SearchPersonHit> = SearchCoreGroupState(),
)

sealed interface SearchCoreGroupsIntent {
    /**
     * The search's words for [serverId]; [enabled] is false in a conversation's own search (the
     * groups are server-wide). The same words again are a no-op.
     */
    data class Query(val serverId: String?, val query: String, val enabled: Boolean) : SearchCoreGroupsIntent

    /** The incomplete line's Retry: the failed groups again, at once (the others keep their answer). */
    data object Retry : SearchCoreGroupsIntent

    /** Load more (or its Retry) in an expanded group: its next page. */
    data class LoadMore(val group: SearchCoreGroup) : SearchCoreGroupsIntent
}

/** web `coreGroupsForQuery`: a leading `#` asks channels only, `@` people only. */
@RaftCoreInternalApi fun searchCoreGroupsForQuery(query: String): List<SearchCoreGroup> =
    when (query.firstOrNull()) {
        '#' -> listOf(SearchCoreGroup.Channels)
        '@' -> listOf(SearchCoreGroup.People)
        else -> SearchCoreGroup.entries
    }

/**
 * Core's channels and people groups of global search (web `useCoreSearchGroups`, slock #15387):
 *
 * - New words: the groups they ask for go [SearchCoreGroupStatus.Loading] at once (the page shows
 *   its local rows meanwhile, without a count), then each group is read on its own after the same
 *   pause as the Artifacts group, in parallel. An answer for older words is dropped ([generation]).
 * - A group that can't be read is [SearchCoreGroupStatus.Failed]; [SearchCoreGroupsIntent.Retry]
 *   asks the failed groups again. 404 APP_NOT_FOUND (an older server) is Idle: local rows, no note.
 * - Load more reads the group's next page with its cursor, 50 at a time (web
 *   `WEB_SEARCH_MAX_LIMIT`), without repeats; a failure keeps the rows and sets `pageFailed`.
 */
@RaftCoreInternalApi class SharedSearchCoreGroupsStore(
    private val source: SearchCoreGroupsSource,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = SharedSearchArtifactsStore.DEBOUNCE_MILLIS,
    private val logger: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(SearchCoreGroupsState())
    val state: StateFlow<SearchCoreGroupsState> = mutableState.asStateFlow()

    private var generation = 0
    private var asked: SearchCoreGroupsIntent.Query? = null
    private var debounceJob: Job? = null
    private val readJobs = mutableMapOf<SearchCoreGroup, Job>()

    fun dispatch(intent: SearchCoreGroupsIntent) {
        when (intent) {
            is SearchCoreGroupsIntent.Query -> query(intent)
            SearchCoreGroupsIntent.Retry -> retry()
            is SearchCoreGroupsIntent.LoadMore -> loadMore(intent.group)
        }
    }

    fun clear() {
        generation++
        cancelAll()
    }

    private fun cancelAll() {
        debounceJob?.cancel()
        readJobs.values.forEach { it.cancel() }
        readJobs.clear()
    }

    private fun query(intent: SearchCoreGroupsIntent.Query) {
        val serverId = intent.serverId?.trim()?.takeIf { it.isNotEmpty() }
        val words = webSearchQueryOrNull(intent.query)?.takeIf { it.trimStart('#', '@').isNotBlank() }
        val normalized = SearchCoreGroupsIntent.Query(serverId, words.orEmpty(), intent.enabled && serverId != null && words != null)
        if (normalized == asked) return
        asked = normalized
        generation++
        cancelAll()
        if (!normalized.enabled || words == null || serverId == null) {
            mutableState.value = SearchCoreGroupsState(serverId = serverId)
            return
        }
        val groups = searchCoreGroupsForQuery(words)
        val loading = { group: SearchCoreGroup ->
            if (group in groups) SearchCoreGroupStatus.Loading else SearchCoreGroupStatus.Idle
        }
        mutableState.value =
            SearchCoreGroupsState(
                serverId = serverId,
                channels = SearchCoreGroupState(status = loading(SearchCoreGroup.Channels), query = words),
                people = SearchCoreGroupState(status = loading(SearchCoreGroup.People), query = words),
            )
        val generation = generation
        debounceJob =
            scope.launch {
                if (debounceMillis > 0) delay(debounceMillis)
                if (generation != this@SharedSearchCoreGroupsStore.generation) return@launch
                groups.forEach { read(it, serverId, words, generation) }
            }
    }

    private fun retry() {
        val current = mutableState.value
        val serverId = current.serverId ?: return
        SearchCoreGroup.entries.forEach { group ->
            val groupState = current.group(group)
            if (groupState.status != SearchCoreGroupStatus.Failed) return@forEach
            updateTyped<Any?>(group) { it.copy(status = SearchCoreGroupStatus.Loading, retrying = true, error = null) }
            read(group, serverId, groupState.query, generation)
        }
    }

    private fun read(group: SearchCoreGroup, serverId: String, words: String, generation: Int) {
        readJobs[group]?.cancel()
        readJobs[group] =
            scope.launch {
                val load = load(group, serverId, words, cursor = null, limit = null)
                if (generation != this@SharedSearchCoreGroupsStore.generation) {
                    logger("search_core_group_load group=${group.wire} result=stale")
                    return@launch
                }
                when (group) {
                    SearchCoreGroup.Channels -> applyFirst(group, load.cast<SearchChannelHit>()) { it.id }
                    SearchCoreGroup.People -> applyFirst(group, load.cast<SearchPersonHit>()) { it.key }
                }
                logger("search_core_group_load group=${group.wire} ${load.diagnostics()} queryLen=${words.length}")
            }
    }

    private fun <T> applyFirst(group: SearchCoreGroup, load: WebSearchCoreGroupLoad<T>, keyOf: (T) -> String) {
        updateTyped<T>(group) { base ->
            when (load) {
                is WebSearchCoreGroupLoad.Page ->
                    base.copy(
                        status = SearchCoreGroupStatus.Ok,
                        items = load.hits.distinctBy(keyOf),
                        nextCursor = load.nextCursor,
                        total = load.total,
                        paging = false,
                        pageFailed = false,
                        retrying = false,
                        error = null,
                    )
                is WebSearchCoreGroupLoad.Failed ->
                    base.copy(status = SearchCoreGroupStatus.Failed, items = emptyList(), nextCursor = null, total = 0, retrying = false, error = load.error)
                WebSearchCoreGroupLoad.Unsupported ->
                    base.copy(status = SearchCoreGroupStatus.Idle, items = emptyList(), nextCursor = null, total = 0, retrying = false, error = null)
            }
        }
    }

    private fun loadMore(group: SearchCoreGroup) {
        val current = mutableState.value
        val serverId = current.serverId ?: return
        val groupState = current.group(group)
        val cursor = groupState.nextCursor
        if (groupState.status != SearchCoreGroupStatus.Ok || groupState.paging || cursor == null) return
        val generation = generation
        updateTyped<Any?>(group) { it.copy(paging = true, pageFailed = false) }
        readJobs[group] =
            scope.launch {
                val load = load(group, serverId, groupState.query, cursor = cursor, limit = WEB_SEARCH_MAX_LIMIT)
                if (generation != this@SharedSearchCoreGroupsStore.generation) return@launch
                when (group) {
                    SearchCoreGroup.Channels -> applyNext(group, load.cast<SearchChannelHit>()) { it.id }
                    SearchCoreGroup.People -> applyNext(group, load.cast<SearchPersonHit>()) { it.key }
                }
                logger("search_core_group_more group=${group.wire} ${load.diagnostics()}")
            }
    }

    private fun <T> applyNext(group: SearchCoreGroup, load: WebSearchCoreGroupLoad<T>, keyOf: (T) -> String) {
        updateTyped<T>(group) { base ->
            when (load) {
                is WebSearchCoreGroupLoad.Page -> {
                    // The server's order may have moved between pages: a row already shown isn't shown twice.
                    val seen = base.items.mapTo(HashSet(), keyOf)
                    base.copy(
                        items = base.items + load.hits.filter { seen.add(keyOf(it)) },
                        nextCursor = load.nextCursor,
                        total = load.total,
                        paging = false,
                        pageFailed = false,
                        error = null,
                    )
                }
                is WebSearchCoreGroupLoad.Failed -> base.copy(paging = false, pageFailed = true, error = load.error)
                // No such group any more: nothing more to read.
                WebSearchCoreGroupLoad.Unsupported -> base.copy(paging = false, nextCursor = null)
            }
        }
    }

    private suspend fun load(group: SearchCoreGroup, serverId: String, words: String, cursor: String?, limit: Int?): WebSearchCoreGroupLoad<Any> =
        when (group) {
            SearchCoreGroup.Channels -> source.channels(serverId, words, cursor, limit, DataRequestPriority.Visible)
            SearchCoreGroup.People -> source.people(serverId, words, cursor, limit, DataRequestPriority.Visible)
        }

    private fun update(group: SearchCoreGroup, change: (SearchCoreGroupState<*>) -> SearchCoreGroupState<*>) {
        val current = mutableState.value
        @Suppress("UNCHECKED_CAST")
        mutableState.value =
            when (group) {
                SearchCoreGroup.Channels -> current.copy(channels = change(current.channels) as SearchCoreGroupState<SearchChannelHit>)
                SearchCoreGroup.People -> current.copy(people = change(current.people) as SearchCoreGroupState<SearchPersonHit>)
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> updateTyped(group: SearchCoreGroup, change: (SearchCoreGroupState<T>) -> SearchCoreGroupState<T>) =
        update(group) { change(it as SearchCoreGroupState<T>) }

    @Suppress("UNCHECKED_CAST")
    private fun <T> WebSearchCoreGroupLoad<Any>.cast(): WebSearchCoreGroupLoad<T> = this as WebSearchCoreGroupLoad<T>

    private fun WebSearchCoreGroupLoad<*>.diagnostics(): String =
        when (this) {
            is WebSearchCoreGroupLoad.Page -> "result=ok count=${hits.size} total=$total hasMore=${nextCursor != null}"
            is WebSearchCoreGroupLoad.Failed -> "result=failed code=${error.code}"
            WebSearchCoreGroupLoad.Unsupported -> "result=unsupported"
        }
}

/** The state of [group]. */
fun SearchCoreGroupsState.group(group: SearchCoreGroup): SearchCoreGroupState<*> =
    when (group) {
        SearchCoreGroup.Channels -> channels
        SearchCoreGroup.People -> people
    }
