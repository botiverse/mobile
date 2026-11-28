package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import build.raft.app.model.ToAnswerChangeKind
import build.raft.app.model.ToAnswerChanged
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlin.time.Clock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How long an ended row stays visible, saying how it ended, before leaving (web `TO_ANSWER_ENDED_LINGER_MS`). */
@RaftCoreInternalApi const val TO_ANSWER_ENDED_LINGER_MS: Long = 2_000L

/** Re-reads asked for within this window coalesce into one read (web `TO_ANSWER_REFETCH_DEBOUNCE_MS`). */
@RaftCoreInternalApi const val TO_ANSWER_REFETCH_DEBOUNCE_MS: Long = 500L

/** Maximum single expiry delay slice (matches web `MAX_TIMER_MS`). */
private const val TO_ANSWER_MAX_EXPIRY_TIMER_MS: Long = 2_147_483_647L

/** How a row stopped waiting: the card's state from `to-answer:changed` (or `"expired"` locally), and who ended it. */
@RaftCoreInternalApi data class ToAnswerEnded(
    val state: String,
    val byUserId: String? = null,
    val byName: String? = null,
)

@RaftCoreInternalApi enum class ToAnswerStatus {
    Idle,
    Loading,
    Loaded,
    Failed,
}

@RaftCoreInternalApi data class SharedToAnswerState(
    /** The server this list belongs to; null before the first read or after clear. */
    val serverId: String? = null,
    val items: List<ToAnswerItem> = emptyList(),
    /** Live count: server count (beyond the 100-item list cap), less rows ended since the last load (never negative). */
    val count: Int = 0,
    /** True when the server stopped scanning at its candidate bound (1000). */
    val capped: Boolean = false,
    val status: ToAnswerStatus = ToAnswerStatus.Idle,
    val errorCode: String? = null,
    /** Rows that stopped waiting and are lingering for [TO_ANSWER_ENDED_LINGER_MS], keyed by carrier messageId. */
    val ended: Map<String, ToAnswerEnded> = emptyMap(),
) {
    val loading: Boolean get() = status == ToAnswerStatus.Loading
    val loaded: Boolean get() = status == ToAnswerStatus.Loaded
}

/**
 * Activity's "To answer" store (KMP mirror of web `packages/web/src/store/toAnswerStore.ts`,
 * botiverse/slock #17252 / #17261, behind `to_answer_list`):
 *
 * - Reads `GET /api/actions/to-answer` only when `to_answer_list` is enabled for the target server.
 * - Live `to-answer:changed`: `opened` triggers a debounced refetch (500ms); `ended` marks the row
 *   ended, decrements the live count immediately, keeps the row visible for 2s linger, then removes
 *   it — fencing against in-flight reads started before the row ended so a settling read never
 *   resurrects an ended row.
 * - Deadline expiry: schedules a timer for the nearest `expiresAt` so rows past deadline end cleanly
 *   even with no socket event.
 * - Reconnect refetch: refetches on realtime reconnect only when the list is already loaded/active
 *   for the currently selected server and `to_answer_list` is enabled.
 * - Server switch / logout / flag-off / [clear]: cancels ALL timers and jobs cleanly (debounce,
 *   linger, expiry, fetch).
 */
@RaftCoreInternalApi class SharedToAnswerStore(
    private val api: RaftToAnswerApi,
    private val scope: CoroutineScope,
    private val isFlagEnabled: (ServerId) -> Boolean = { true },
    private val isServerActive: (String) -> Boolean = { true },
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val logger: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private val _state = MutableStateFlow(SharedToAnswerState())
    val state: StateFlow<SharedToAnswerState> = _state.asStateFlow()

    private var generation: Long = 0L

    /**
     * Carrier messageIds that ended, mapped to the read generation current when they ended: any read
     * started at or before that generation may still list the card and must not resurrect it once
     * its linger removes it. Cleared by a read started after.
     */
    private val endedAtGeneration = mutableMapOf<String, Long>()

    private var loadJob: Job? = null
    private var refetchJob: Job? = null
    private var expiryJob: Job? = null
    private val lingerJobs = mutableMapOf<String, Job>()

    /** Launches a fetch of `GET /api/actions/to-answer` for [serverId] when `to_answer_list` is enabled. */
    fun load(serverId: String): Job? {
        val cleanServerId = serverId.trim().takeIf { it.isNotEmpty() } ?: return null
        if (!isFlagEnabled(ServerId(cleanServerId))) {
            resetServer(cleanServerId)
            return null
        }
        val launched =
            synchronized(lock) {
                refetchJob?.cancel()
                refetchJob = null
                loadJob?.cancel()
                scope.launch {
                    loadNow(cleanServerId)
                }.also { job ->
                    loadJob = job
                }
            }
        return launched
    }

    /**
     * Reads `GET /api/actions/to-answer` for [serverId] immediately. A read for another server,
     * overtaken by a newer generation, or settling after the flag turned off is dropped cleanly.
     */
    suspend fun loadNow(serverId: String) {
        val cleanServerId = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        if (!isFlagEnabled(ServerId(cleanServerId))) {
            resetServer(cleanServerId)
            return
        }
        val request =
            synchronized(lock) {
                val nextGeneration = ++generation
                refetchJob?.cancel()
                refetchJob = null
                val current = _state.value
                if (current.serverId != cleanServerId) {
                    cancelTimersLocked()
                    endedAtGeneration.clear()
                    _state.value =
                        SharedToAnswerState(
                            serverId = cleanServerId,
                            status = ToAnswerStatus.Loading,
                        )
                } else if (current.status != ToAnswerStatus.Loaded) {
                    _state.value = current.copy(status = ToAnswerStatus.Loading, errorCode = null)
                }
                nextGeneration
            }
        val result =
            try {
                api.listToAnswer(ServerId(cleanServerId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                logger(
                    "to_answer_load_failed serverId=$cleanServerId " +
                        "error=${throwable::class.simpleName ?: "Throwable"}"
                )
                DataResult.Error(ScreenError(code = "network", retryable = true, traceId = null))
            }
        synchronized(lock) {
            if (request != generation || _state.value.serverId != cleanServerId) return
            if (!isFlagEnabled(ServerId(cleanServerId))) {
                clearLocked()
                return
            }
            when (result) {
                is DataResult.Content -> {
                    val data = result.data
                    val currentEnded = _state.value.ended
                    val stale = mutableSetOf<String>()
                    val iter = endedAtGeneration.entries.iterator()
                    while (iter.hasNext()) {
                        val (id, at) = iter.next()
                        if (request <= at) {
                            stale.add(id)
                        } else {
                            iter.remove()
                        }
                    }
                    val items = data.items.filter { row -> row.messageId !in stale || row.messageId in currentEnded }
                    val dropped = data.items.size - items.size
                    val endedListedCount = items.count { it.messageId in currentEnded }
                    val liveCount = (data.count - dropped - endedListedCount).coerceAtLeast(0)
                    _state.value =
                        _state.value.copy(
                            serverId = cleanServerId,
                            items = items,
                            count = liveCount,
                            capped = data.capped,
                            status = ToAnswerStatus.Loaded,
                            errorCode = null,
                        )
                    scheduleExpiryLocked()
                }
                is DataResult.Error -> {
                    val current = _state.value
                    // Rows already shown stay on a failed re-read; a failed first read reports failure.
                    if (current.status != ToAnswerStatus.Loaded) {
                        _state.value = current.copy(status = ToAnswerStatus.Failed, errorCode = result.error.code)
                    }
                }
                is DataResult.NoPermission -> {
                    val current = _state.value
                    if (current.status != ToAnswerStatus.Loaded) {
                        _state.value = current.copy(status = ToAnswerStatus.Failed, errorCode = result.blocker.code)
                    }
                }
                is DataResult.Empty -> {
                    val current = _state.value
                    if (current.status != ToAnswerStatus.Loaded) {
                        _state.value =
                            current.copy(
                                items = emptyList(),
                                count = 0,
                                capped = false,
                                status = ToAnswerStatus.Loaded,
                                errorCode = null,
                            )
                    }
                }
            }
        }
    }

    /**
     * Applies one `to-answer:changed` event from realtime. Ignored if the list has never been read,
     * belongs to another server, or `to_answer_list` is off.
     */
    fun onToAnswerChanged(serverId: String, event: ToAnswerChanged) {
        val targetServerId = event.serverId.value.trim().takeIf { it.isNotEmpty() } ?: return
        val grantServerId = serverId.trim()
        if (grantServerId.isNotEmpty() && grantServerId != targetServerId) return
        if (!isFlagEnabled(ServerId(targetServerId))) {
            resetServer(targetServerId)
            return
        }
        when (event.change) {
            ToAnswerChangeKind.Opened -> requestRefetch(targetServerId)
            ToAnswerChangeKind.Ended -> {
                val endedState = event.state?.trim()?.takeIf { it.isNotEmpty() } ?: return
                synchronized(lock) {
                    val current = _state.value
                    if (current.status == ToAnswerStatus.Idle || current.serverId != targetServerId) return
                    endRowLocked(
                        messageId = event.messageId.value,
                        how =
                            ToAnswerEnded(
                                state = endedState,
                                byUserId = event.byUserId,
                                byName = event.byName,
                            ),
                    )
                }
            }
        }
    }

    /**
     * Re-reads [serverId] after a short debounce window ([TO_ANSWER_REFETCH_DEBOUNCE_MS]) that
     * folds a burst into one read, only when the list is already active/loaded for the currently
     * selected server and `to_answer_list` is enabled.
     */
    fun requestRefetch(serverId: String?) {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            val current = _state.value
            if (current.serverId != cleanServerId || current.status == ToAnswerStatus.Idle) return
            if (!isFlagEnabled(ServerId(cleanServerId))) {
                clearLocked()
                return
            }
            if (!isServerActive(cleanServerId)) return
            refetchJob?.cancel()
            refetchJob =
                scope.launch {
                    delay(TO_ANSWER_REFETCH_DEBOUNCE_MS)
                    val shouldRun =
                        synchronized(lock) {
                            refetchJob = null
                            val now = _state.value
                            now.serverId == cleanServerId &&
                                now.status != ToAnswerStatus.Idle &&
                                isServerActive(cleanServerId) &&
                                isFlagEnabled(ServerId(cleanServerId))
                        }
                    if (shouldRun) {
                        loadNow(cleanServerId)
                    }
                }
        }
    }

    /** After a realtime reconnect's `rooms:joined`: refetches if active and enabled for [serverId]. */
    fun onRealtimeReconnected(serverId: String?) {
        requestRefetch(serverId)
    }

    /**
     * Reacts to runtime `to_answer_list` changes for [serverId]: turning OFF clears the list and
     * cancels all timers cleanly; turning ON triggers a load when [loadWhenEnabled] is true.
     */
    fun onFlagStateChanged(serverId: String?, enabled: Boolean, loadWhenEnabled: Boolean = false) {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
        if (!enabled) {
            if (cleanServerId != null) {
                resetServer(cleanServerId)
            } else {
                clear()
            }
            return
        }
        if (loadWhenEnabled && cleanServerId != null) {
            load(cleanServerId)
        }
    }

    /** Drops stored list and cancels every job/timer if [serverId] is currently held. */
    fun resetServer(serverId: String) {
        val cleanServerId = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            if (_state.value.serverId == cleanServerId) {
                clearLocked()
            }
        }
    }

    /** Forgets everything and cancels ALL jobs and timers (logout, server switch, flag-off, tests). */
    fun clear() {
        synchronized(lock) {
            clearLocked()
        }
    }

    private fun clearLocked() {
        generation += 1L
        loadJob?.cancel()
        loadJob = null
        cancelTimersLocked()
        endedAtGeneration.clear()
        if (_state.value != SharedToAnswerState()) {
            _state.value = SharedToAnswerState()
        }
    }

    private fun cancelTimersLocked() {
        refetchJob?.cancel()
        refetchJob = null
        expiryJob?.cancel()
        expiryJob = null
        lingerJobs.values.forEach(Job::cancel)
        lingerJobs.clear()
    }

    /** Marks [messageId] ended, drops live count at once, and removes the row after [TO_ANSWER_ENDED_LINGER_MS]. */
    private fun endRowLocked(messageId: String, how: ToAnswerEnded) {
        val current = _state.value
        if (messageId in current.ended || current.items.none { it.messageId == messageId }) return
        val expectedServerId = current.serverId ?: return
        endedAtGeneration[messageId] = generation
        _state.value =
            current.copy(
                ended = current.ended + (messageId to how),
                count = (current.count - 1).coerceAtLeast(0),
            )
        lingerJobs.remove(messageId)?.cancel()
        lingerJobs[messageId] =
            scope.launch {
                delay(TO_ANSWER_ENDED_LINGER_MS)
                synchronized(lock) {
                    lingerJobs.remove(messageId)
                    val now = _state.value
                    if (now.serverId != expectedServerId) return@synchronized
                    _state.value =
                        now.copy(
                            items = now.items.filterNot { it.messageId == messageId },
                            ended = now.ended - messageId,
                        )
                }
            }
    }

    /** Ends any listed rows past their deadline and schedules the next deadline check cleanly. */
    private fun scheduleExpiryLocked() {
        expiryJob?.cancel()
        expiryJob = null
        val current = _state.value
        val expectedServerId = current.serverId ?: return
        val now = nowEpochMillis()
        var nextAt: Long? = null
        val expiredIds = mutableListOf<String>()
        for (item in current.items) {
            if (item.messageId in current.ended) continue
            val expiresRaw = item.expiresAt ?: continue
            val at = parseRaftTimestampEpochMillisOrNull(expiresRaw) ?: continue
            if (at <= now) {
                expiredIds += item.messageId
            } else if (nextAt == null || at < nextAt) {
                nextAt = at
            }
        }
        expiredIds.forEach { id -> endRowLocked(id, ToAnswerEnded(state = "expired")) }
        if (nextAt != null) {
            val waitMs = (nextAt - now).coerceIn(1L, TO_ANSWER_MAX_EXPIRY_TIMER_MS)
            expiryJob =
                scope.launch {
                    delay(waitMs)
                    synchronized(lock) {
                        expiryJob = null
                        if (_state.value.serverId == expectedServerId) {
                            scheduleExpiryLocked()
                        }
                    }
                }
        }
    }
}
