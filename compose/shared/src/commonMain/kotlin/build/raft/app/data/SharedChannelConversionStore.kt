package build.raft.app.data

import build.raft.app.data.realtime.RealtimeLocalEvents
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Channel conversions' store: each shown channel's conversion, for the strip above its messages
 * (web botiverse/slock 922c8de664 #15525 `components/channel/useChannelConversionController.ts`).
 *
 * The channel row is the truth (ChannelConversionApi.kt). It is read when a page shows the channel
 * and on a reconnect that could not replay what it missed. The `channel:updated` rows the server
 * sends after every phase and when the job ends move it on (the realtime mapper reads their
 * conversion into [RealtimeRefreshRequired.channelConversion]). While a job runs, a command waits,
 * or the status could not be read, it is read again every [CHANNEL_CONVERSION_POLL_MILLIS] as a
 * fallback, only while a page shows it ([ChannelConversionIntent.Watch]) and the app is in front.
 * Retry and Cancel go through here; their answers patch the slice.
 *
 * Unlike the web, a command's identity is not kept across launches (the web's localStorage
 * observation of a start whose answer was lost): Retry and Cancel answer once the server ran them,
 * and a command that waits shows as the server's pending state. A failed read is read again at the
 * fallback pace, not the web's 1 s / 5 s.
 */

/** How often a running conversion is read when no realtime frame came (web `CONVERSION_POLL_MS`). */
const val CHANNEL_CONVERSION_POLL_MILLIS: Long = 10_000L

/** How long the status can stay unread before the notice says the outcome is unknown (web `CONVERSION_OBSERVATION_CONFIRM_MS`). */
const val CHANNEL_CONVERSION_CONFIRM_MILLIS: Long = 10_000L

/** One channel's conversion, in one server. */
data class ChannelConversionKey(val serverId: String, val channelId: String)

/** A Retry or Cancel that did not go through (web `convertError` from `handleRetry` / `handleCancel`). */
data class ChannelConversionActionError(
    val action: ChannelConversionAction,
    /** Cancel whose outcome is unknown and the job can't be canceled any more (web `channel.edit.cancelConversionUnavailable`). */
    val noLongerCancelable: Boolean = false,
    /** The server's sentence; null: the app's own words for [action]. */
    val serverMessage: String? = null,
    val traceId: String? = null,
)

/**
 * What the store knows of one channel's conversion: [read] false until the first answer (nothing
 * shows then), the [conversion], the job this app canceled ([canceledJobId], never shown again),
 * the command under way ([busy]), whether the last read failed ([statusUnavailable], web
 * `connectionWarning`; [statusTraceId] the failed request's trace id) and for how long
 * ([outcomeUnknown], web `observationSettled`), and the last Retry or Cancel that failed ([actionError]).
 */
data class ChannelConversionSlice(
    val read: Boolean = false,
    val conversion: RaftChannelConversion = RaftChannelConversion.None,
    val canceledJobId: String? = null,
    val busy: ChannelConversionAction? = null,
    val statusUnavailable: Boolean = false,
    val statusTraceId: String? = null,
    val outcomeUnknown: Boolean = false,
    val actionError: ChannelConversionActionError? = null,
) {
    /** The job the strip follows (web `conversionReceipt`): one under way or failed, never one this app canceled. */
    val shownJob: RaftChannelConversionJob?
        get() =
            conversion.job?.takeIf {
                it.id != canceledJobId &&
                    (it.status == RaftChannelConversionJobStatus.Pending ||
                        it.status == RaftChannelConversionJobStatus.Running ||
                        it.status == RaftChannelConversionJobStatus.Failed)
            }

    /**
     * Web `useChannelConversionInProgress` (slock #16389): true while a conversion command is
     * pending/in-flight or a conversion job is pending, running, or failed. Connect entries stay
     * hidden until the conversion settles.
     */
    val isConversionInProgress: Boolean
        get() = busy != null || conversion.commandPending || shownJob != null

    /** A job under way (pending or running). */
    val runningJob: RaftChannelConversionJob?
        get() = shownJob?.takeIf { it.status != RaftChannelConversionJobStatus.Failed }

    /** Web `retry` defined: a failed job, and no command waits. Shown on the failure's notice. */
    val offersRetry: Boolean
        get() = !conversion.commandPending && shownJob?.status == RaftChannelConversionJobStatus.Failed

    /**
     * Web `cancel` defined: no command waits and the job can still be canceled, as the server says
     * (`canCancel`), else, from an older server, while it prepares and nothing was copied yet.
     */
    val offersCancel: Boolean
        get() {
            val job = shownJob ?: return false
            return !conversion.commandPending && (job.canCancel ?: (job.phase == "prepare" && !job.canonicalCopyStarted))
        }

    /** The fallback read is due: a job runs, a command waits, or the status could not be read. */
    internal val pollDue: Boolean
        get() = busy == null && (conversion.commandPending || runningJob != null || statusUnavailable)

    /** [conversion] as a newer answer or frame says it; the status is known again. */
    internal fun withConversion(next: RaftChannelConversion): ChannelConversionSlice {
        val moved = copy(read = true, conversion = next, statusUnavailable = false, statusTraceId = null, outcomeUnknown = false)
        // A failed Retry or Cancel stays beside a failed job; once the job goes on it is gone (web: the next read clears it).
        return if (moved.runningJob != null || next.commandPending) moved.copy(actionError = null) else moved
    }
}

sealed interface ChannelConversionIntent {
    /** Read it (again). Coalesced per channel: one request at a time, and one more after it when asked meanwhile. */
    data class Load(val key: ChannelConversionKey, val reason: String, val priority: DataRequestPriority) : ChannelConversionIntent

    /** A realtime event reached a page that shows [key]. */
    data class Realtime(val key: ChannelConversionKey, val eventServerId: String, val event: RealtimeEvent) : ChannelConversionIntent

    /**
     * A page shows [key]: read it every [CHANNEL_CONVERSION_POLL_MILLIS] while it is due, until the
     * matching [Unwatch] (counted, so two pages showing it poll once), in the store's scope.
     */
    data class Watch(val key: ChannelConversionKey) : ChannelConversionIntent

    data class Unwatch(val key: ChannelConversionKey) : ChannelConversionIntent

    /** View latest status: read it now. */
    data class CheckStatus(val key: ChannelConversionKey) : ChannelConversionIntent

    data class Retry(val key: ChannelConversionKey) : ChannelConversionIntent

    /** Cancel, once confirmed. */
    data class Cancel(val key: ChannelConversionKey) : ChannelConversionIntent

    /** Signed out, or another account: nothing of the last one is kept. */
    data object Reset : ChannelConversionIntent
}

sealed interface ChannelConversionEffect {
    /** Canceled: the original channel was restored (web toast `channel.edit.conversionCanceled`). */
    data class Canceled(val key: ChannelConversionKey) : ChannelConversionEffect
}

internal class SharedChannelConversionStore(
    private val repository: ChannelConversionRepository,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    private val pollMillis: Long = CHANNEL_CONVERSION_POLL_MILLIS,
    private val confirmMillis: Long = CHANNEL_CONVERSION_CONFIRM_MILLIS,
    /** The poll runs only while this is true (the app in the foreground); see [repeatWhileProcessForeground]. */
    private val foreground: StateFlow<Boolean> = RaftProcessForegroundGate.foreground,
    private val nowMillis: () -> Long = ::raftForegroundGateNowMillis,
) {
    private val mutableState = MutableStateFlow<Map<ChannelConversionKey, ChannelConversionSlice>>(emptyMap())
    val state: StateFlow<Map<ChannelConversionKey, ChannelConversionSlice>> = mutableState.asStateFlow()

    private val mutableEffects = MutableSharedFlow<ChannelConversionEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<ChannelConversionEffect> = mutableEffects.asSharedFlow()

    private val lock = SynchronizedObject()
    private val generations = mutableMapOf<ChannelConversionKey, Long>()
    private val reading = mutableSetOf<ChannelConversionKey>()
    private val readAgain = mutableSetOf<ChannelConversionKey>()
    private val watchers = mutableMapOf<ChannelConversionKey, Int>()
    private val polls = mutableMapOf<ChannelConversionKey, Job>()
    private val settleTimers = mutableMapOf<ChannelConversionKey, Job>()
    private var epoch = 0L

    /** [key]'s slice alone, re-emitted only when it changes. */
    fun slice(key: ChannelConversionKey): Flow<ChannelConversionSlice> = state.map { it[key] ?: ChannelConversionSlice() }.distinctUntilChanged()

    fun current(key: ChannelConversionKey): ChannelConversionSlice = state.value[key] ?: ChannelConversionSlice()

    fun dispatch(intent: ChannelConversionIntent) {
        when (intent) {
            is ChannelConversionIntent.Load -> read(intent.key, intent.reason, intent.priority)
            is ChannelConversionIntent.Realtime -> realtime(intent.key, intent.eventServerId, intent.event)
            is ChannelConversionIntent.Watch -> watch(intent.key)
            is ChannelConversionIntent.Unwatch -> {
                val stopped =
                    synchronized(lock) {
                        val count = (watchers[intent.key] ?: return) - 1
                        if (count > 0) {
                            watchers[intent.key] = count
                            null
                        } else {
                            watchers.remove(intent.key)
                            polls.remove(intent.key)
                        }
                    }
                stopped?.cancel()
            }
            is ChannelConversionIntent.CheckStatus -> read(intent.key, "check_status", DataRequestPriority.Visible)
            is ChannelConversionIntent.Retry -> command(intent.key, ChannelConversionAction.Retry)
            is ChannelConversionIntent.Cancel -> command(intent.key, ChannelConversionAction.Cancel)
            ChannelConversionIntent.Reset -> {
                val stopped =
                    synchronized(lock) {
                        epoch += 1
                        watchers.clear()
                        generations.clear()
                        readAgain.clear()
                        (polls.values + settleTimers.values).toList().also {
                            polls.clear()
                            settleTimers.clear()
                        }
                    }
                stopped.forEach { it.cancel() }
                mutableState.value = emptyMap()
            }
        }
    }

    private fun realtime(key: ChannelConversionKey, eventServerId: String, event: RealtimeEvent) {
        val refresh = event as? RealtimeRefreshRequired ?: return
        if (eventServerId != key.serverId || refresh.scope != RealtimeRefreshScope.Home) return
        if (refresh.serverId != null && refresh.serverId.value != key.serverId) return
        when {
            refresh.reason == RealtimeSocketEvents.ChannelUpdated && refresh.channelId?.value == key.channelId -> {
                // A row without conversion fields (an edit, an archive) says nothing of it.
                val conversion = refresh.channelConversion ?: return
                // Newer than any read already on its way (web: the store patch wins over the hook's last read).
                synchronized(lock) { nextGeneration(key) }
                update(key) { it.withConversion(conversion) }
                stopSettleTimer(key)
            }
            refresh.channelId == null && refresh.reason == RealtimeLocalEvents.CatchUpRequired ->
                read(key, "catch_up", DataRequestPriority.Background)
        }
    }

    private fun watch(key: ChannelConversionKey) {
        val start =
            synchronized(lock) {
                val count = (watchers[key] ?: 0) + 1
                watchers[key] = count
                count == 1
            }
        if (!start) return
        val job =
            scope.launch {
                // Hidden, the page stays composed but nothing reads; back in front one read at once
                // if one came due meanwhile (web `waitVisible`).
                repeatWhileProcessForeground(intervalMillis = pollMillis, foreground = foreground, nowMillis = nowMillis) {
                    if (current(key).pollDue) read(key, "poll", DataRequestPriority.Background)
                }
            }
        synchronized(lock) {
            if (watchers.containsKey(key)) polls.put(key, job)?.cancel() else job.cancel()
        }
    }

    private fun nextGeneration(key: ChannelConversionKey): Long {
        val next = (generations[key] ?: 0L) + 1L
        generations[key] = next
        return next
    }

    private fun isCurrent(key: ChannelConversionKey, generation: Long, startedEpoch: Long): Boolean =
        synchronized(lock) { generations[key] == generation && epoch == startedEpoch }

    private inline fun update(key: ChannelConversionKey, crossinline transform: (ChannelConversionSlice) -> ChannelConversionSlice) {
        mutableState.update { state ->
            val old = state[key]
            val next = transform(old ?: ChannelConversionSlice())
            if (next == old) state else state + (key to next)
        }
    }

    private fun stopSettleTimer(key: ChannelConversionKey) {
        synchronized(lock) { settleTimers.remove(key) }?.cancel()
    }

    /** Web `setConnectionWarning(true)`: the last progress stays; after [confirmMillis] the notice says the outcome is unknown. */
    private fun raiseStatusUnavailable(key: ChannelConversionKey, traceId: String?) {
        val before =
            mutableState.getAndUpdate { state ->
                state + (key to (state[key] ?: ChannelConversionSlice()).copy(statusUnavailable = true, statusTraceId = traceId))
            }
        if (before[key]?.statusUnavailable == true) return
        val startedEpoch = synchronized(lock) { epoch }
        val timer =
            scope.launch {
                delay(confirmMillis)
                if (synchronized(lock) { epoch == startedEpoch }) update(key) { if (it.statusUnavailable) it.copy(outcomeUnknown = true) else it }
            }
        synchronized(lock) { settleTimers.put(key, timer) }?.cancel()
    }

    private fun read(key: ChannelConversionKey, reason: String, priority: DataRequestPriority) {
        val start =
            synchronized(lock) {
                if (key in reading) {
                    readAgain += key
                    false
                } else {
                    reading += key
                    true
                }
            }
        if (!start) return
        scope.launch {
            try {
                var why = reason
                while (true) {
                    val (generation, startedEpoch) =
                        synchronized(lock) {
                            readAgain -= key
                            nextGeneration(key) to epoch
                        }
                    // A job under way is read on its own route (web `getChannelConversionJob`), anything else from the channel.
                    val jobId = current(key).runningJob?.id
                    val result =
                        if (jobId != null) {
                            repository.readJob(ServerId(key.serverId), jobId, priority)
                        } else {
                            repository.readChannel(ServerId(key.serverId), ChannelId(key.channelId), priority)
                        }
                    if (result is DataResult.Content) {
                        if (isCurrent(key, generation, startedEpoch)) {
                            update(key) { it.withConversion(result.data) }
                            stopSettleTimer(key)
                        }
                    } else {
                        val error = (result as? DataResult.Error)?.error
                        logger("channel_conversion_read_failed channel=${key.channelId} reason=$why code=${error?.code} traceId=${error?.traceId}")
                        // A failed read is an observation failure, not a new job state (web): it matters while progress is followed.
                        val slice = current(key)
                        if (isCurrent(key, generation, startedEpoch) && (slice.runningJob != null || slice.conversion.commandPending || slice.statusUnavailable)) {
                            raiseStatusUnavailable(key, error?.traceId)
                        }
                    }
                    if (synchronized(lock) { key !in readAgain }) break
                    why = "coalesced"
                }
            } finally {
                synchronized(lock) {
                    reading -= key
                    readAgain -= key
                }
            }
        }
    }

    private fun command(key: ChannelConversionKey, action: ChannelConversionAction) {
        val slice = current(key)
        val job = slice.shownJob ?: return
        val offered = if (action == ChannelConversionAction.Retry) slice.offersRetry else slice.offersCancel
        if (!offered) return
        val started =
            synchronized(lock) {
                if (current(key).busy != null) return
                nextGeneration(key) to epoch
            }
        val (generation, startedEpoch) = started
        update(key) {
            it.copy(
                busy = action,
                actionError = null,
                statusUnavailable = false,
                statusTraceId = null,
                outcomeUnknown = false,
                canceledJobId = if (action == ChannelConversionAction.Retry) null else it.canceledJobId,
            )
        }
        stopSettleTimer(key)
        scope.launch {
            val result = repository.command(ServerId(key.serverId), job.id, action)
            if (synchronized(lock) { epoch != startedEpoch }) return@launch
            when (result) {
                is DataResult.Content -> commandSucceeded(key, action, job.id, generation, result.data)
                is DataResult.Error -> commandFailed(key, action, job.id, startedEpoch, result.error)
                else -> commandFailed(key, action, job.id, startedEpoch, ScreenError(ChannelConversionUnreadableCode))
            }
        }
    }

    private fun commandSucceeded(key: ChannelConversionKey, action: ChannelConversionAction, jobId: String, generation: Long, conversion: RaftChannelConversion) {
        // A frame newer than the answer may have come meanwhile (a retried job's first phase): keep it.
        val newest = synchronized(lock) { generations[key] == generation }
        update(key) { slice ->
            val next = if (newest) slice.withConversion(conversion) else slice
            next.copy(busy = null, canceledJobId = if (action == ChannelConversionAction.Cancel) jobId else next.canceledJobId)
        }
        if (action == ChannelConversionAction.Cancel) mutableEffects.tryEmit(ChannelConversionEffect.Canceled(key))
    }

    private suspend fun commandFailed(key: ChannelConversionKey, action: ChannelConversionAction, jobId: String, startedEpoch: Long, error: ScreenError) {
        logger("channel_conversion_${action.pathSegment}_failed channel=${key.channelId} code=${error.code} traceId=${error.traceId}")
        val unknown = error.channelConversionOutcomeUnknown()
        if (action == ChannelConversionAction.Retry) {
            if (unknown) {
                // The Retry may have gone through with its answer lost: find the job again (web: drop the old failed snapshot).
                update(key) { it.copy(busy = null) }
                raiseStatusUnavailable(key, error.traceId)
                read(key, "retry_outcome_unknown", DataRequestPriority.Background)
            } else {
                update(key) { it.copy(busy = null, actionError = ChannelConversionActionError(action, serverMessage = error.serverMessage, traceId = error.traceId)) }
            }
            return
        }
        // Web handleCancel: read the job again before saying anything.
        val latest = (repository.readJob(ServerId(key.serverId), jobId, DataRequestPriority.Visible) as? DataResult.Content)?.data
        if (synchronized(lock) { epoch != startedEpoch }) return
        val latestJob = latest?.job?.takeIf { it.id == jobId }
        if (latest != null && latestJob?.status == RaftChannelConversionJobStatus.Canceled) {
            update(key) { it.withConversion(latest).copy(busy = null, canceledJobId = jobId) }
            mutableEffects.tryEmit(ChannelConversionEffect.Canceled(key))
            return
        }
        if (latest != null) {
            synchronized(lock) { nextGeneration(key) }
            update(key) { it.withConversion(latest) }
        }
        if (unknown && latestJob?.status != RaftChannelConversionJobStatus.Done && latestJob?.canCancel != false) {
            // Keep watching until a read says how it went.
            update(key) { it.copy(busy = null) }
            raiseStatusUnavailable(key, error.traceId)
        } else {
            update(key) {
                it.copy(
                    busy = null,
                    actionError =
                        ChannelConversionActionError(
                            action,
                            noLongerCancelable = unknown,
                            serverMessage = if (unknown) null else error.serverMessage,
                            traceId = error.traceId,
                        ),
                )
            }
        }
    }
}
