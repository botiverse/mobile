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

/**
 * The thread's messages the page holds now: its first message ([origin], shown above the replies)
 * and the replies' window, with whether there is more before / after it.
 */
@RaftCoreInternalApi data class ThreadFindWindow(
    val threadChannelId: String,
    val origin: ThreadFindMessage? = null,
    val messages: List<ThreadFindMessage> = emptyList(),
    val hasOlder: Boolean = false,
    val hasNewer: Boolean = false,
)

/** Search in this thread (web `ThreadSearchPanelState` with its matches). */
@RaftCoreInternalApi data class ThreadFindState(
    val threadChannelId: String? = null,
    val open: Boolean = false,
    /** As typed; matching uses [normalizedQuery]. Kept when the bar closes (web: reopening shows it again). */
    val query: String = "",
    val activeIndex: Int = 0,
    /** The whole thread is being read (the count says "Loading"). */
    val loading: Boolean = false,
    /** The whole thread was read: [corpus] holds it. */
    val hydrated: Boolean = false,
    /** The thread as read for the search, oldest first (empty until [hydrated]). */
    val corpus: List<ThreadFindMessage> = emptyList(),
    val window: ThreadFindWindow? = null,
    /** The matching messages' ids, in thread order (the first message first). */
    val matches: List<String> = emptyList(),
    /** The last read of the thread failed: the bar counts what the page holds. */
    val error: ScreenError? = null,
) {
    val normalizedQuery: String get() = normalizeThreadFindQuery(query)

    /** The match the bar is on, while it is open. */
    val activeMessageId: String?
        get() = if (open) matches.getOrNull(activeIndex) else null
}

@RaftCoreInternalApi sealed interface ThreadFindIntent {
    /** The page's messages now ([ThreadFindWindow]); another thread starts over. */
    data class Window(val window: ThreadFindWindow) : ThreadFindIntent

    /** Search in thread (the header's menu): opens the bar with its last words. */
    data object Open : ThreadFindIntent

    /** Close, Esc or Back: the bar goes, its words stay for next time. */
    data object Close : ThreadFindIntent

    data class Query(val query: String) : ThreadFindIntent

    /** Next match (Enter, ↓), after the last the first. */
    data object Next : ThreadFindIntent

    /** Previous match (Shift+Enter, ↑), before the first the last. */
    data object Previous : ThreadFindIntent
}

@RaftCoreInternalApi sealed interface ThreadFindEvent {
    /** Reading the whole thread failed; the count is of what the page holds. */
    data class LoadFailed(val error: ScreenError) : ThreadFindEvent
}

/** web `normalizeThreadSearchQuery`: trimmed, lower case. */
@RaftCoreInternalApi fun normalizeThreadFindQuery(query: String): String = query.trim().lowercase()

/** web `buildThreadSearchMatches`: a message matches when its sender's name or its text holds the words. */
@RaftCoreInternalApi fun ThreadFindMessage.matchesThreadFind(normalizedQuery: String): Boolean =
    "$senderName\n$content".lowercase().contains(normalizedQuery)

/**
 * The messages the search covers, in thread order: the first message, then the thread as read
 * ([corpus]) with what the page holds now (the page's copy wins; a reply that came since is in).
 */
@RaftCoreInternalApi fun threadFindSearchable(window: ThreadFindWindow?, corpus: List<ThreadFindMessage>): List<ThreadFindMessage> {
    val replies = LinkedHashMap<String, ThreadFindMessage>()
    corpus.forEach { replies[it.id] = it }
    window?.messages?.forEach { replies[it.id] = it }
    val origin = window?.origin
    val ordered =
        replies.values
            .filter { it.id != origin?.id }
            .sortedWith(compareBy<ThreadFindMessage> { it.seq == null }.thenBy { it.seq ?: 0L })
    return listOfNotNull(origin) + ordered
}

@RaftCoreInternalApi fun threadFindMatches(searchable: List<ThreadFindMessage>, query: String): List<String> {
    val normalized = normalizeThreadFindQuery(query)
    if (normalized.isEmpty()) return emptyList()
    return searchable.filter { it.id.isNotBlank() && it.matchesThreadFind(normalized) }.map { it.id }
}

/**
 * Search in this thread (web `useThreadSearch` + `ThreadSearchBar`, slock staging e4c09353f):
 *
 * - Words wait for a pause ([debounceMillis], web `THREAD_SEARCH_HYDRATION_DEBOUNCE_MS` 250), then
 *   the whole thread is read once, a [THREAD_FIND_PAGE_LIMIT] page at a time, older then newer, from
 *   what the page holds, while pages come back full. Until then the matches are of what the page
 *   holds. New words in the pause restart it; an answer for a thread no longer shown is dropped.
 * - The matches are case-insensitive, of the sender's name and the text; the first message counts.
 * - Next / Previous wrap; new words go back to the first match.
 * - A failed read keeps counting what the page holds and says so once ([ThreadFindEvent.LoadFailed],
 *   with its trace id); new words try again (web: the next words read again).
 */
@RaftCoreInternalApi class SharedThreadFindStore(
    private val source: ThreadFindPageSource?,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val logger: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(ThreadFindState())
    val state: StateFlow<ThreadFindState> = mutableState.asStateFlow()

    private val mutableEvents =
        MutableSharedFlow<ThreadFindEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ThreadFindEvent> = mutableEvents.asSharedFlow()

    private var generation = 0
    private var pauseJob: Job? = null
    private var readJob: Job? = null

    fun dispatch(intent: ThreadFindIntent) {
        when (intent) {
            is ThreadFindIntent.Window -> window(intent.window)
            ThreadFindIntent.Open -> reduce(mutableState.value.copy(open = true), hydrate = true)
            ThreadFindIntent.Close -> {
                pauseJob?.cancel()
                reduce(mutableState.value.copy(open = false), hydrate = false)
            }
            is ThreadFindIntent.Query -> {
                if (intent.query == mutableState.value.query && mutableState.value.open) return
                reduce(mutableState.value.copy(open = true, query = intent.query, activeIndex = 0), hydrate = true)
            }
            ThreadFindIntent.Next -> step(1)
            ThreadFindIntent.Previous -> step(-1)
        }
    }

    fun clear() {
        generation++
        pauseJob?.cancel()
        readJob?.cancel()
    }

    private fun window(window: ThreadFindWindow) {
        val current = mutableState.value
        if (current.window == window) return
        if (current.threadChannelId != null && current.threadChannelId != window.threadChannelId) {
            // Another thread: nothing of this one carries over, and its answers are stale.
            clear()
            reduce(ThreadFindState(threadChannelId = window.threadChannelId, window = window), hydrate = false)
            return
        }
        reduce(current.copy(threadChannelId = window.threadChannelId, window = window), hydrate = false)
    }

    private fun step(direction: Int) {
        val current = mutableState.value
        val count = current.matches.size
        if (count == 0) return
        val from = current.activeIndex.coerceIn(0, count - 1)
        mutableState.value = current.copy(activeIndex = (from + direction + count) % count)
    }

    /** [next] with its matches (the active one kept in range); [hydrate]: words may start the read. */
    private fun reduce(next: ThreadFindState, hydrate: Boolean) {
        val matches = threadFindMatches(threadFindSearchable(next.window, next.corpus), next.query)
        val activeIndex = if (matches.isEmpty()) 0 else next.activeIndex.coerceIn(0, matches.lastIndex)
        val resolved = next.copy(matches = matches, activeIndex = activeIndex)
        if (resolved != mutableState.value) mutableState.value = resolved
        if (hydrate) scheduleRead()
    }

    private fun scheduleRead() {
        pauseJob?.cancel()
        val current = mutableState.value
        if (source == null || !current.open || current.normalizedQuery.isEmpty() || current.hydrated) return
        pauseJob =
            scope.launch {
                if (debounceMillis > 0) delay(debounceMillis)
                read()
            }
    }

    private fun read() {
        val source = source ?: return
        val start = mutableState.value
        val window = start.window ?: return
        if (start.hydrated || start.loading) return
        val generation = generation
        mutableState.value = start.copy(loading = true, error = null)
        readJob =
            scope.launch {
                val outcome = readThread(source, window)
                if (generation != this@SharedThreadFindStore.generation) {
                    logger("thread_find_load result=stale")
                    return@launch
                }
                val base = mutableState.value
                when (outcome) {
                    is ThreadReadOutcome.Read -> {
                        reduce(base.copy(loading = false, hydrated = true, corpus = outcome.messages, error = null), hydrate = false)
                        logger("thread_find_load result=ok messages=${outcome.messages.size} pages=${outcome.pages}")
                    }
                    is ThreadReadOutcome.Failed -> {
                        mutableState.value = base.copy(loading = false, error = outcome.error)
                        mutableEvents.tryEmit(ThreadFindEvent.LoadFailed(outcome.error))
                        logger("thread_find_load result=failed code=${outcome.error.code} pages=${outcome.pages}")
                    }
                }
            }
    }

    private sealed interface ThreadReadOutcome {
        val pages: Int

        data class Read(val messages: List<ThreadFindMessage>, override val pages: Int) : ThreadReadOutcome

        data class Failed(val error: ScreenError, override val pages: Int) : ThreadReadOutcome
    }

    /** web `loadThreadSearchCorpus`: older pages while they come back full, then newer ones. */
    private suspend fun readThread(source: ThreadFindPageSource, window: ThreadFindWindow): ThreadReadOutcome {
        val byId = LinkedHashMap<String, ThreadFindMessage>()
        window.messages.forEach { byId[it.id] = it }
        var pages = 0
        var hasOlder = window.hasOlder
        while (hasOlder && byId.isNotEmpty()) {
            val oldest = byId.values.mapNotNull { it.seq?.takeIf { seq -> seq > 0 } }.minOrNull() ?: break
            pages++
            when (val load = source.page(window.threadChannelId, beforeSeq = oldest, afterSeq = null, THREAD_FIND_PAGE_LIMIT, DataRequestPriority.Visible)) {
                is ThreadFindPageLoad.Failed -> return ThreadReadOutcome.Failed(load.error, pages)
                is ThreadFindPageLoad.Page -> {
                    load.messages.forEach { byId[it.id] = it }
                    hasOlder = load.rawCount >= THREAD_FIND_PAGE_LIMIT
                    if (load.rawCount == 0) break
                }
            }
        }
        var hasNewer = window.hasNewer
        while (hasNewer && byId.isNotEmpty()) {
            val newest = byId.values.mapNotNull { it.seq?.takeIf { seq -> seq > 0 } }.maxOrNull() ?: break
            pages++
            when (val load = source.page(window.threadChannelId, beforeSeq = null, afterSeq = newest, THREAD_FIND_PAGE_LIMIT, DataRequestPriority.Visible)) {
                is ThreadFindPageLoad.Failed -> return ThreadReadOutcome.Failed(load.error, pages)
                is ThreadFindPageLoad.Page -> {
                    load.messages.forEach { byId[it.id] = it }
                    hasNewer = load.rawCount >= THREAD_FIND_PAGE_LIMIT
                    if (load.rawCount == 0) break
                }
            }
        }
        return ThreadReadOutcome.Read(byId.values.toList(), pages)
    }

    companion object {
        /** web `THREAD_SEARCH_HYDRATION_DEBOUNCE_MS`. */
        const val DEBOUNCE_MILLIS: Long = 250L
    }
}
