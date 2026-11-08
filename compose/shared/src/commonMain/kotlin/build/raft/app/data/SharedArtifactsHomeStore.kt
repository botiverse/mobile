package build.raft.app.data

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

/**
 * The Artifacts home page's list (web `ArtifactsDashboard.tsx` `useAllArtifacts`, `allReducer`, and
 * `DashboardList`'s filter state; slock staging aae2b7a89).
 *
 * - The list is read when the page has a server with workspace_artifacts_v0 on, and again for a
 *   new server, filter, settled search (typing waits [SEARCH_DEBOUNCE_MILLIS]), the toggle or a sort.
 *   The same filter in another sort keeps the rows on screen while it reads; a new filter shows the
 *   skeleton. An answer to an older read is dropped ([generation]).
 * - A sort that fails goes back to the last answered one, with [ArtifactsHomeEvent.SortFailed].
 * - Load More continues the query the rows on screen were read for, and is dropped when a read
 *   replaced them meanwhile; it de-duplicates by id (keyset paging can list one twice).
 * - New activity on this server's Artifacts reads the first page again, rows kept, unless the
 *   viewer is open or Load More has extended the list (web `useArtifactActivityChanged`): one
 *   read at a time, at most one more queued. Closing the viewer always reads again.
 * - 403 ARTIFACTS_NOT_ENABLED: [ArtifactsHomeStatus.Off].
 * - Filters are this page's state: another server starts with none.
 */
internal class SharedArtifactsHomeStore(
    private val api: ArtifactsHomeSource,
    private val scope: CoroutineScope,
    initialSort: ArtifactsHomeSort = ArtifactsHomeSort.Default,
    /** The sort the reader chose, to keep on the device (web `artifactDashboardPrefs`). */
    private val onSortChosen: (ArtifactsHomeSort) -> Unit = {},
    private val logger: ((String) -> Unit)? = null,
) {
    private val mutableState = MutableStateFlow(ArtifactsHomeState(query = ArtifactsHomeQuery(sort = initialSort)))
    val state: StateFlow<ArtifactsHomeState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<ArtifactsHomeEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ArtifactsHomeEvent> = mutableEvents.asSharedFlow()

    private var generation = 0
    private var readJob: Job? = null
    private var pageJob: Job? = null
    private var searchJob: Job? = null

    /** What the rows on screen were read with: their cursor continues only that. */
    private var shownQuery: ArtifactsHomeQuery? = null
    private var shownGeneration = 0

    /** The sort of the last read that answered: where a re-sort that fails goes back to. */
    private var answeredSort: ArtifactsHomeSort = initialSort

    /** The filter (sort aside) and server the rows on screen were read for. */
    private var readFor: String? = null

    private var activityRead: ActivityRead = ActivityRead.Idle

    private val choiceJobs = mutableMapOf<ArtifactsHomeChoiceKind, Job>()

    fun dispatch(intent: ArtifactsHomeIntent) {
        when (intent) {
            is ArtifactsHomeIntent.Start -> start(intent.serverId)
            is ArtifactsHomeIntent.Search -> search(intent.text)
            is ArtifactsHomeIntent.SetPlace -> applyQuery { it.copy(place = intent.place) }
            is ArtifactsHomeIntent.SetAuthor -> {
                mutableState.value = mutableState.value.copy(authorActor = intent.author)
                applyQuery { it.copy(author = intent.author?.filterValue) }
            }
            is ArtifactsHomeIntent.SetRelevant -> applyQuery { it.copy(relevant = intent.relevant) }
            is ArtifactsHomeIntent.SetSort -> {
                if (intent.sort == mutableState.value.query.sort) return
                onSortChosen(intent.sort)
                applyQuery { it.copy(sort = intent.sort) }
            }
            ArtifactsHomeIntent.ClearSearchAndFilters -> {
                searchJob?.cancel()
                mutableState.value = mutableState.value.copy(search = "", authorActor = null)
                applyQuery { it.copy(q = "", place = null, author = null) }
            }
            ArtifactsHomeIntent.ClearFilters -> {
                mutableState.value = mutableState.value.copy(authorActor = null)
                applyQuery { it.copy(place = null, author = null, relevant = false) }
            }
            ArtifactsHomeIntent.Retry -> read(keepRows = mutableState.value.rows != null)
            ArtifactsHomeIntent.LoadMore -> loadMore()
            is ArtifactsHomeIntent.ActivityChanged -> onActivity(intent.serverId)
            ArtifactsHomeIntent.ViewerOpened -> mutableState.value = mutableState.value.copy(viewerOpen = true)
            ArtifactsHomeIntent.ViewerClosed -> {
                mutableState.value = mutableState.value.copy(viewerOpen = false)
                // Opening it read the current version: what was new may be gone, the order may have moved.
                if (mutableState.value.serverId != null && mutableState.value.status != ArtifactsHomeStatus.Off) read(keepRows = true)
            }
            is ArtifactsHomeIntent.OpenChoices -> openChoices(intent.kind)
            is ArtifactsHomeIntent.CloseChoices -> closeChoices(intent.kind)
            is ArtifactsHomeIntent.ChoicesQuery -> choicesQuery(intent.kind, intent.text)
            is ArtifactsHomeIntent.RetryChoices -> {
                updateChoices(intent.kind) { it.copy(phase = ArtifactsHomeChoicesPhase.Pending, answeredFor = null) }
                askChoices(intent.kind, delayMillis = 0)
            }
        }
    }

    fun clear() {
        readJob?.cancel()
        pageJob?.cancel()
        searchJob?.cancel()
        choiceJobs.values.forEach { it.cancel() }
        choiceJobs.clear()
    }

    private fun start(serverId: String?) {
        val current = mutableState.value
        if (serverId == current.serverId && current.status != ArtifactsHomeStatus.Idle) return
        if (serverId != current.serverId) {
            // Both filters name someone or somewhere in one server: another's list starts with neither.
            searchJob?.cancel()
            choiceJobs.values.forEach { it.cancel() }
            choiceJobs.clear()
            mutableState.value =
                ArtifactsHomeState(
                    serverId = serverId,
                    query = ArtifactsHomeQuery(sort = current.query.sort),
                )
            shownQuery = null
            readFor = null
        }
        if (serverId == null) return
        read(keepRows = false, keepCounts = false)
    }

    private fun search(text: String) {
        val clipped = text.take(ARTIFACTS_HOME_SEARCH_MAX_CHARS)
        mutableState.value = mutableState.value.copy(search = clipped)
        searchJob?.cancel()
        val settled = clipped.trim()
        if (settled == mutableState.value.query.q) return
        searchJob =
            scope.launch {
                delay(SEARCH_DEBOUNCE_MILLIS)
                applyQuery { it.copy(q = settled) }
            }
    }

    private fun applyQuery(change: (ArtifactsHomeQuery) -> ArtifactsHomeQuery) {
        val current = mutableState.value
        val next = change(current.query)
        if (next == current.query) return
        mutableState.value = current.copy(query = next)
        if (current.serverId == null || current.status == ArtifactsHomeStatus.Off) return
        read(keepRows = readFor == filterKey(current.serverId, next))
    }

    private fun filterKey(serverId: String?, query: ArtifactsHomeQuery): String = "$serverId|${query.copy(sort = ArtifactsHomeSort.Default)}"

    private fun read(keepRows: Boolean, keepCounts: Boolean = true): Job? {
        val current = mutableState.value
        val serverId = current.serverId ?: return null
        val query = current.query
        val readGeneration = ++generation
        readFor = filterKey(serverId, query)
        pageJob?.cancel()
        mutableState.value =
            current.copy(
                status = ArtifactsHomeStatus.Loading,
                rows = if (keepRows) current.rows else null,
                nextCursor = if (keepRows) current.nextCursor else null,
                counts = if (keepCounts) current.counts else null,
                paging = false,
                error = null,
            )
        val priority = if (keepRows) DataRequestPriority.Visible else DataRequestPriority.Foreground
        logger?.invoke("artifacts_home_read generation=$readGeneration keepRows=$keepRows sort=${query.sort.wire} relevant=${query.relevant} narrowed=${query.narrowed}")
        readJob?.cancel()
        val job =
            scope.launch {
                val load = api.page(serverId, query, cursor = null, priority = priority)
                if (readGeneration != generation) return@launch
                val latest = mutableState.value
                when (load) {
                    is ArtifactsHomeLoad.Ok -> {
                        shownQuery = query
                        shownGeneration = readGeneration
                        answeredSort = query.sort
                        val page = load.value
                        mutableState.value =
                            latest.copy(
                                status = ArtifactsHomeStatus.Ok,
                                rows = page.rows,
                                nextCursor = page.nextCursor,
                                counts = page.counts ?: latest.counts,
                                countsNarrowed = if (page.counts != null) query.narrowed else latest.countsNarrowed,
                                rowsByOpened = !query.relevant && query.sort == ArtifactsHomeSort.Opened,
                                extended = false,
                                error = null,
                            )
                    }
                    ArtifactsHomeLoad.Off -> mutableState.value = latest.copy(status = ArtifactsHomeStatus.Off, error = null)
                    is ArtifactsHomeLoad.Missing, is ArtifactsHomeLoad.Failed -> {
                        val error = load.errorOrNull() ?: ScreenError("artifacts_home_failed")
                        mutableState.value = latest.copy(status = ArtifactsHomeStatus.Failed, error = error)
                        // The sort control goes back with the rows, which that sort never replaced.
                        if (answeredSort != query.sort) {
                            mutableEvents.tryEmit(ArtifactsHomeEvent.SortFailed(error))
                            val back = answeredSort
                            onSortChosen(back)
                            mutableState.value = mutableState.value.copy(query = mutableState.value.query.copy(sort = back))
                            readFor = filterKey(serverId, mutableState.value.query)
                        }
                    }
                }
                logger?.invoke("artifacts_home_result generation=$readGeneration status=${mutableState.value.status} rows=${mutableState.value.rows?.size ?: -1}")
            }
        readJob = job
        return job
    }

    private fun loadMore() {
        val current = mutableState.value
        val serverId = current.serverId ?: return
        val cursor = current.nextCursor ?: return
        val query = shownQuery ?: return
        // Off while the list reads itself again: this cursor continues rows that are being replaced.
        if (current.paging || current.status == ArtifactsHomeStatus.Loading) return
        val forGeneration = shownGeneration
        mutableState.value = current.copy(paging = true)
        pageJob =
            scope.launch {
                val load = api.page(serverId, query, cursor = cursor, priority = DataRequestPriority.Visible)
                val latest = mutableState.value
                val stillShown = forGeneration == shownGeneration && latest.status != ArtifactsHomeStatus.Loading
                if (load !is ArtifactsHomeLoad.Ok || !stillShown || latest.rows == null) {
                    mutableState.value = latest.copy(paging = false)
                    if (load !is ArtifactsHomeLoad.Ok && stillShown) {
                        mutableEvents.tryEmit(ArtifactsHomeEvent.MoreFailed(load.errorOrNull()))
                    }
                    return@launch
                }
                val seen = latest.rows.mapTo(HashSet()) { it.summary.id }
                mutableState.value =
                    latest.copy(
                        rows = latest.rows + load.value.rows.filter { it.summary.id !in seen },
                        nextCursor = load.value.nextCursor,
                        paging = false,
                        extended = true,
                    )
            }
    }

    private fun onActivity(serverId: String) {
        val current = mutableState.value
        if (current.serverId != serverId || current.viewerOpen || current.extended) return
        if (current.status == ArtifactsHomeStatus.Off || current.status == ArtifactsHomeStatus.Idle) return
        when (activityRead) {
            ActivityRead.Idle -> runActivityRead()
            ActivityRead.Reading -> activityRead = ActivityRead.Queued
            ActivityRead.Queued -> Unit
        }
    }

    private fun runActivityRead() {
        activityRead = ActivityRead.Reading
        val job = read(keepRows = true)
        if (job == null) {
            activityRead = ActivityRead.Idle
            return
        }
        job.invokeOnCompletion {
            val again = activityRead == ActivityRead.Queued
            activityRead = ActivityRead.Idle
            if (again) scope.launch { onActivity(mutableState.value.serverId ?: return@launch) }
        }
    }

    private fun openChoices(kind: ArtifactsHomeChoiceKind) {
        updateChoices(kind) { it.copy(open = true, text = "") }
        if (choicesOf(kind).phase != ArtifactsHomeChoicesPhase.Missing) askChoices(kind, delayMillis = 0)
    }

    private fun closeChoices(kind: ArtifactsHomeChoiceKind) {
        choiceJobs.remove(kind)?.cancel()
        updateChoices(kind) { it.copy(open = false, text = "") }
    }

    private fun choicesQuery(kind: ArtifactsHomeChoiceKind, text: String) {
        updateChoices(kind) { it.copy(text = text.take(ARTIFACTS_HOME_SEARCH_MAX_CHARS)) }
        if (choicesOf(kind).phase != ArtifactsHomeChoicesPhase.Missing) askChoices(kind, delayMillis = CHOICES_DEBOUNCE_MILLIS)
    }

    /** web `useFilterChoices`: the route asked with the settled words; an older answer never lands over a newer one. */
    private fun askChoices(kind: ArtifactsHomeChoiceKind, delayMillis: Long) {
        val serverId = mutableState.value.serverId ?: return
        choiceJobs.remove(kind)?.cancel()
        if (choicesOf(kind).answeredFor != choicesOf(kind).text.trim()) {
            updateChoices(kind) { it.copy(phase = ArtifactsHomeChoicesPhase.Pending) }
        }
        choiceJobs[kind] =
            scope.launch {
                if (delayMillis > 0) delay(delayMillis)
                val q = choicesOf(kind).text.trim()
                when (kind) {
                    ArtifactsHomeChoiceKind.Author -> {
                        val load = api.authors(serverId, q)
                        landChoices(kind, q, load) { choices, rows -> choices.copy(authors = rows, baseAuthors = if (q.isEmpty()) rows else choices.baseAuthors) }
                    }
                    ArtifactsHomeChoiceKind.Conversation -> {
                        val load = api.conversations(serverId, q)
                        landChoices(kind, q, load) { choices, rows ->
                            choices.copy(conversations = rows, baseConversations = if (q.isEmpty()) rows else choices.baseConversations)
                        }
                    }
                }
            }
    }

    private fun <T> landChoices(
        kind: ArtifactsHomeChoiceKind,
        q: String,
        load: ArtifactsHomeLoad<List<T>>,
        withRows: (ArtifactsHomeChoices, List<T>) -> ArtifactsHomeChoices,
    ) {
        updateChoices(kind) { choices ->
            when (load) {
                is ArtifactsHomeLoad.Ok -> withRows(choices, load.value).copy(phase = ArtifactsHomeChoicesPhase.Ok, answeredFor = q)
                is ArtifactsHomeLoad.Missing -> choices.copy(phase = ArtifactsHomeChoicesPhase.Missing, answeredFor = q)
                else -> choices.copy(phase = ArtifactsHomeChoicesPhase.Failed, answeredFor = q)
            }
        }
    }

    private fun choicesOf(kind: ArtifactsHomeChoiceKind): ArtifactsHomeChoices =
        when (kind) {
            ArtifactsHomeChoiceKind.Author -> mutableState.value.authorChoices
            ArtifactsHomeChoiceKind.Conversation -> mutableState.value.conversationChoices
        }

    private fun updateChoices(kind: ArtifactsHomeChoiceKind, change: (ArtifactsHomeChoices) -> ArtifactsHomeChoices) {
        val current = mutableState.value
        mutableState.value =
            when (kind) {
                ArtifactsHomeChoiceKind.Author -> current.copy(authorChoices = change(current.authorChoices))
                ArtifactsHomeChoiceKind.Conversation -> current.copy(conversationChoices = change(current.conversationChoices))
            }
    }

    private enum class ActivityRead { Idle, Reading, Queued }

    companion object {
        /** web `ALL_SEARCH_DEBOUNCE_MS`. */
        const val SEARCH_DEBOUNCE_MILLIS: Long = 300

        /** web `useFilterChoices` `SEARCH_DEBOUNCE_MS`. */
        const val CHOICES_DEBOUNCE_MILLIS: Long = 250
    }
}

private fun ArtifactsHomeLoad<*>.errorOrNull(): ScreenError? =
    when (this) {
        is ArtifactsHomeLoad.Failed -> error
        is ArtifactsHomeLoad.Missing -> error
        else -> null
    }

internal enum class ArtifactsHomeStatus { Idle, Loading, Ok, Failed, Off }

internal enum class ArtifactsHomeChoiceKind { Author, Conversation }

/** web `useFilterChoices` phases: `Missing` is a 404 from an older server. */
internal enum class ArtifactsHomeChoicesPhase { Idle, Pending, Ok, Failed, Missing }

/** One filter's choices: the latest answer, the answer to no search, and what the field holds. */
internal data class ArtifactsHomeChoices(
    val open: Boolean = false,
    val text: String = "",
    val phase: ArtifactsHomeChoicesPhase = ArtifactsHomeChoicesPhase.Idle,
    val answeredFor: String? = null,
    val authors: List<ArtifactsHomeActor> = emptyList(),
    val baseAuthors: List<ArtifactsHomeActor> = emptyList(),
    val conversations: List<ArtifactsHomeConversation> = emptyList(),
    val baseConversations: List<ArtifactsHomeConversation> = emptyList(),
) {
    /** The answer is for the words in the field now; else what is loaded is filtered here at once. */
    val answered: Boolean get() = phase == ArtifactsHomeChoicesPhase.Ok && answeredFor == text.trim()
}

internal data class ArtifactsHomeState(
    val serverId: String? = null,
    val status: ArtifactsHomeStatus = ArtifactsHomeStatus.Idle,
    /** The settled query the list is read for. */
    val query: ArtifactsHomeQuery = ArtifactsHomeQuery(),
    /** What the search field holds now (the query's `q` follows it after a pause). */
    val search: String = "",
    /** The chosen author, kept whole so the chip names them when the list has moved on. */
    val authorActor: ArtifactsHomeActor? = null,
    /** The rows on screen; null before the first page (the skeleton). */
    val rows: List<ArtifactsHomeRow>? = null,
    val nextCursor: String? = null,
    /** The latest first page's numbers (later pages send none), kept through a read in the same server. */
    val counts: ArtifactsHomeCounts? = null,
    /** Whether [counts] were read with any filter on ("5 of 24"). */
    val countsNarrowed: Boolean = false,
    /** The rows on screen are in the order they were opened: their time says when, not when updated. */
    val rowsByOpened: Boolean = false,
    val paging: Boolean = false,
    /** Load More added rows: a read of the first page would drop them. */
    val extended: Boolean = false,
    val viewerOpen: Boolean = false,
    val error: ScreenError? = null,
    val authorChoices: ArtifactsHomeChoices = ArtifactsHomeChoices(),
    val conversationChoices: ArtifactsHomeChoices = ArtifactsHomeChoices(),
) {
    /** The list is reading itself again over rows already shown. */
    val refreshing: Boolean get() = status == ArtifactsHomeStatus.Loading && rows != null

    /** The author choices to show: the server's answer, or (an older server) the authors on screen. */
    val shownAuthorChoices: List<ArtifactsHomeActor>
        get() {
            val choices = authorChoices
            val words = choices.text.trim().lowercase()
            if (choices.phase == ArtifactsHomeChoicesPhase.Missing) {
                return artifactsHomeAuthorsOnScreen(rows.orEmpty().map { it.author }).filter { it.matches(words) }
            }
            if (choices.answered) return choices.authors
            return (choices.authors + choices.baseAuthors).distinctBy { it.filterValue }.filter { it.matches(words) }
        }

    val shownConversationChoices: List<ArtifactsHomeConversation>
        get() {
            val choices = conversationChoices
            if (choices.answered) return choices.conversations
            val words = choices.text.trim().lowercase().removePrefix("#")
            return (choices.conversations + choices.baseConversations).distinctBy { it.id }.filter {
                it.name.lowercase().contains(words) || (it.peerDisplayName ?: "").lowercase().contains(words)
            }
        }
}

private fun ArtifactsHomeActor.matches(words: String): Boolean =
    words.isEmpty() || name.lowercase().contains(words) || displayName.lowercase().contains(words)

/** web `authorsOnScreen`: the authors of the rows on screen, most frequent first, each once. */
internal fun artifactsHomeAuthorsOnScreen(actors: List<ArtifactsHomeActor>): List<ArtifactsHomeActor> =
    actors
        .filter { it.filterValue != null && it.gone == null }
        .groupBy { it.filterValue }
        .values
        .sortedByDescending { it.size }
        .take(ARTIFACTS_HOME_AUTHORS_LIMIT)
        .map { it.first() }

/** web `ARTIFACT_DASHBOARD_AUTHORS_LIMIT`. */
internal const val ARTIFACTS_HOME_AUTHORS_LIMIT: Int = 50

internal sealed interface ArtifactsHomeIntent {
    /** The page's server with the flag on; null leaves the list unread. */
    data class Start(val serverId: String?) : ArtifactsHomeIntent
    data class Search(val text: String) : ArtifactsHomeIntent
    data class SetPlace(val place: ArtifactsHomePlace?) : ArtifactsHomeIntent
    data class SetAuthor(val author: ArtifactsHomeActor?) : ArtifactsHomeIntent
    data class SetRelevant(val relevant: Boolean) : ArtifactsHomeIntent
    data class SetSort(val sort: ArtifactsHomeSort) : ArtifactsHomeIntent

    /** The empty state's way out: the search, the conversation and the author. */
    data object ClearSearchAndFilters : ArtifactsHomeIntent

    /** The toolbar's Clear all: the conversation, the author and the toggle. */
    data object ClearFilters : ArtifactsHomeIntent
    data object Retry : ArtifactsHomeIntent
    data object LoadMore : ArtifactsHomeIntent

    /** `activity:changed` for [serverId]. */
    data class ActivityChanged(val serverId: String) : ArtifactsHomeIntent
    data object ViewerOpened : ArtifactsHomeIntent
    data object ViewerClosed : ArtifactsHomeIntent
    data class OpenChoices(val kind: ArtifactsHomeChoiceKind) : ArtifactsHomeIntent
    data class CloseChoices(val kind: ArtifactsHomeChoiceKind) : ArtifactsHomeIntent
    data class ChoicesQuery(val kind: ArtifactsHomeChoiceKind, val text: String) : ArtifactsHomeIntent
    data class RetryChoices(val kind: ArtifactsHomeChoiceKind) : ArtifactsHomeIntent
}

/** One-off notices (web toasts). */
internal sealed interface ArtifactsHomeEvent {
    data class SortFailed(val error: ScreenError) : ArtifactsHomeEvent
    data class MoreFailed(val error: ScreenError?) : ArtifactsHomeEvent
}
