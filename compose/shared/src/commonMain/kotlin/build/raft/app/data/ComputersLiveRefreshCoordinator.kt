package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Single data-layer coordinator for live Computers refreshes (#wg-mobile-feedback:#273):
 *
 * 1. **Realtime `RealtimeRefreshScope.Machines` pushes** (`machine:status`, `machine:updated`,
 *    `machine:capabilities`, `computer:upgrade:*`, `machine:upgrade-request`, `daemon:status`):
 *    debounced per server within [windowMillis], single-flight with atomic `notStartedBefore`
 *    boundary so bursts fold into one `/machines` fetch and a push arriving mid-flight schedules
 *    one follow-up that never joins the pre-event read. Runs only for servers with a loaded or
 *    actively observed [SharedComputersStore] slice — even while the Computers page is not visible.
 *
 * 2. **Process freshness signals ([RaftFreshnessSignal])**:
 *    - When incremental sync covers the server ([syncChangesPullAllowed] is true):
 *      - Foreground catch-up is driven by `SharedForegroundReconciler` (no extra foreground read is
 *        added here); its start and outcome are reported via [onExternalForegroundRefreshStarted] /
 *        [onExternalForegroundRefreshCompleted] with a per-flight token so overlapping calls never
 *        clobber each other and `try/finally` guarantees the gate never wedges.
 *      - If that foreground refresh failed (e.g. app returned to foreground before network came
 *        back), the per-server outcome-tracked [RaftFreshnessGate] does NOT consume its 10 s window
 *        (and latches any [RaftFreshnessTrigger.NetworkRestored] arriving while the failing refresh
 *        is still in flight), so [RaftFreshnessTrigger.NetworkRestored] recovers both incremental
 *        `sync/changes` and live machine status immediately (coalescing cleanly against any already
 *        fresh `/machines` read).
 *    - When incremental sync is off for the server ([syncChangesPullAllowed] is false):
 *      - [RaftFreshnessTrigger.Foreground], [RaftFreshnessTrigger.NetworkRestored], and
 *        active-observer [RaftFreshnessTrigger.Reconnect] refresh [SharedComputersStore] through
 *        the same outcome-tracked [RaftFreshnessGate] and [refreshMachines].
 */
internal class ComputersLiveRefreshCoordinator(
    private val scope: CoroutineScope,
    private val loadedOrObservedServerIds: () -> Set<String>,
    private val hasLoadedOrObservedServer: (serverId: String) -> Boolean,
    private val hasActiveObservers: (serverId: String) -> Boolean,
    private val refreshMachines: suspend (
        serverId: String,
        reason: String,
        coalesceFreshWithin: Duration,
        notStartedBefore: ComparableTimeMark?,
    ) -> Boolean,
    private val syncChangesPullAllowed: (serverId: String) -> Boolean = { false },
    private val pullSyncChanges: suspend (serverId: String, trigger: String) -> Boolean = { _, _ -> false },
    private val windowMillis: Long = AgentProfileRefreshCoalescer.DEFAULT_WINDOW_MILLIS,
    private val coalesceFreshWithin: Duration = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW,
    private val freshnessCoalesceWithin: Duration = RAFT_FRESHNESS_COALESCE_MILLIS.milliseconds,
    private val nowMillis: () -> Long = ::raftForegroundGateNowMillis,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val logger: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()

    // Realtime burst debounce + active flights per server.
    private val pendingRealtime = mutableMapOf<String, Job>()
    private val activeRealtime = mutableMapOf<String, MutableSet<Job>>()
    private val latestRealtimeByServerId = mutableMapOf<String, PendingRealtime>()

    // Freshness gates + active freshness jobs + external foreground flight tokens per server.
    private val freshnessGates = mutableMapOf<String, RaftFreshnessGate>()
    private val activeFreshness = mutableMapOf<String, Job>()
    private val activeExternalTokens = mutableMapOf<String, MutableSet<Any>>()
    private val lastForegroundBoundaryByServerId = mutableMapOf<String, ComparableTimeMark>()
    private val pendingBoundaryByServerId = mutableMapOf<String, ComparableTimeMark>()

    private data class PendingRealtime(
        val reason: String,
        val eventAt: ComparableTimeMark,
    )

    fun startFreshness(freshness: Flow<RaftFreshnessEvent>): Job =
        scope.launch {
            freshness.collect { event ->
                onFreshnessEvent(event)
            }
        }

    fun onMachinesRealtimeEvent(serverId: String, reason: String) {
        val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return
        if (!hasLoadedOrObservedServer(normalizedServerId)) return
        val normalizedReason = reason.trim().ifBlank { "machines" }
        val toStart =
            synchronized(lock) {
                val eventAt = timeSource.markNow()
                if (pendingRealtime[normalizedServerId]?.isPendingOrActive() == true) {
                    latestRealtimeByServerId[normalizedServerId] = PendingRealtime(normalizedReason, eventAt)
                    null
                } else {
                    lateinit var scheduled: Job
                    scheduled =
                        scope.launch(start = CoroutineStart.LAZY) {
                            var pendingEvent: PendingRealtime? = null
                            var promotedToActive = false
                            try {
                                delay(windowMillis)
                                synchronized(lock) {
                                    if (scheduled.isActive && pendingRealtime[normalizedServerId] === scheduled) {
                                        pendingRealtime.remove(normalizedServerId)
                                        val recorded = latestRealtimeByServerId.remove(normalizedServerId)
                                        if (recorded != null) {
                                            pendingEvent = recorded
                                            activeRealtime.getOrPut(normalizedServerId) { mutableSetOf() }.add(scheduled)
                                            promotedToActive = true
                                        }
                                    }
                                }
                                val target = pendingEvent ?: return@launch
                                coroutineContext.ensureActive()
                                if (!hasLoadedOrObservedServer(normalizedServerId)) return@launch
                                logger("computers_realtime_refresh serverId=$normalizedServerId reason=${target.reason}")
                                try {
                                    refreshMachines(
                                        normalizedServerId,
                                        "realtime_${target.reason}",
                                        coalesceFreshWithin,
                                        target.eventAt,
                                    )
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (error: Throwable) {
                                    logger(
                                        "computers_realtime_refresh_failed serverId=$normalizedServerId " +
                                            "type=${error::class.simpleName ?: "unknown"}"
                                    )
                                }
                            } finally {
                                synchronized(lock) {
                                    if (pendingRealtime[normalizedServerId] === scheduled) {
                                        pendingRealtime.remove(normalizedServerId)
                                        latestRealtimeByServerId.remove(normalizedServerId)
                                    }
                                    if (promotedToActive) {
                                        val activeForServer = activeRealtime[normalizedServerId]
                                        if (activeForServer != null) {
                                            activeForServer.remove(scheduled)
                                            if (activeForServer.isEmpty()) {
                                                activeRealtime.remove(normalizedServerId)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    if (scheduled.isPendingOrActive()) {
                        latestRealtimeByServerId[normalizedServerId] = PendingRealtime(normalizedReason, eventAt)
                        pendingRealtime[normalizedServerId] = scheduled
                        scheduled
                    } else {
                        null
                    }
                }
            }
        toStart?.start()
    }

    /**
     * Notes that `SharedForegroundReconciler` started a foreground refresh for [serverId].
     * Always pair with [onExternalForegroundRefreshCompleted] in a `try/finally` block using the
     * returned token (or null if skipped).
     */
    fun onExternalForegroundRefreshStarted(
        serverId: String,
        returnBoundary: ComparableTimeMark? = null,
    ): Any? {
        val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return null
        if (!hasLoadedOrObservedServer(normalizedServerId)) return null
        val token = Any()
        synchronized(lock) {
            lastForegroundBoundaryByServerId[normalizedServerId] = returnBoundary ?: timeSource.markNow()
            activeExternalTokens.getOrPut(normalizedServerId) { mutableSetOf() }.add(token)
            if (activeFreshness[normalizedServerId]?.isPendingOrActive() != true) {
                gateForLocked(normalizedServerId).onExternalCatchUpStarted()
            }
        }
        return token
    }

    /**
     * Notes that the external foreground refresh identified by [token] finished. On success,
     * commits the coalescing window once all overlapping external foreground flights settle; on
     * failure, clears it and launches any latched `NetworkRestored` follow-up immediately.
     */
    fun onExternalForegroundRefreshCompleted(serverId: String, token: Any?, succeeded: Boolean) {
        if (token == null) return
        val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return
        // Evaluate provider callbacks outside [lock] to avoid nested lock acquisition.
        val relevant = hasLoadedOrObservedServer(normalizedServerId)
        val syncOn = syncChangesPullAllowed(normalizedServerId)
        val toStart =
            synchronized(lock) {
                val tokens = activeExternalTokens[normalizedServerId]
                val removed = tokens?.remove(token) == true
                if (tokens != null && tokens.isEmpty()) {
                    activeExternalTokens.remove(normalizedServerId)
                }
                if (!removed) return@synchronized null
                if (activeExternalTokens[normalizedServerId]?.isNotEmpty() == true) {
                    // Another external foreground flight is still active; keep the gate in flight.
                    return@synchronized null
                }
                if (activeFreshness[normalizedServerId]?.isPendingOrActive() == true) {
                    return@synchronized null
                }
                val gate = freshnessGates[normalizedServerId] ?: if (relevant) {
                    gateForLocked(normalizedServerId)
                } else {
                    return@synchronized null
                }
                val followUp = gate.onCatchUpCompleted(succeeded = succeeded, visible = relevant)
                if (followUp == null) {
                    null
                } else if (syncOn && followUp == RaftFreshnessTrigger.Foreground) {
                    // SharedForegroundReconciler owns Foreground refreshes when sync is on.
                    gate.onCatchUpCompleted(succeeded = false, visible = false)
                    null
                } else {
                    val boundary = resolveFollowUpBoundaryLocked(normalizedServerId, followUp)
                    launchFreshnessLoopLocked(normalizedServerId, followUp, boundary)
                }
            }
        toStart?.start()
    }

    internal fun onFreshnessEvent(event: RaftFreshnessEvent) {
        val targetServerIds =
            when (event.trigger) {
                RaftFreshnessTrigger.Foreground,
                RaftFreshnessTrigger.NetworkRestored -> loadedOrObservedServerIds()
                RaftFreshnessTrigger.Reconnect -> {
                    val serverId = event.serverId?.trim()?.takeIf { it.isNotBlank() } ?: return
                    if (!hasActiveObservers(serverId)) return
                    setOf(serverId)
                }
            }
        if (targetServerIds.isEmpty()) return
        val eventAt = timeSource.markNow()
        targetServerIds.forEach { rawServerId ->
            val serverId = rawServerId.trim().takeIf { it.isNotBlank() } ?: return@forEach
            val syncOn = syncChangesPullAllowed(serverId)
            // When incremental sync covers this server, SharedForegroundReconciler drives Foreground
            // and PresenceTransportRosterRefetch drives Reconnect: do not duplicate them here.
            // NetworkRestored still runs through the outcome-tracked gate to recover a failed
            // foreground refresh immediately.
            if (syncOn && event.trigger != RaftFreshnessTrigger.NetworkRestored) return@forEach
            val toStart =
                synchronized(lock) {
                    val gate = gateForLocked(serverId)
                    val beforeSeq = gate.acceptedEventCount()
                    val wasInFlight = gate.isInFlight()
                    val trigger = gate.onEvent(event, serverId, visible = true)
                    val accepted = gate.acceptedEventCount() != beforeSeq
                    if (accepted && event.trigger == RaftFreshnessTrigger.Foreground) {
                        lastForegroundBoundaryByServerId[serverId] = eventAt
                    }
                    val eventBoundary =
                        when (event.trigger) {
                            RaftFreshnessTrigger.Foreground,
                            RaftFreshnessTrigger.Reconnect -> eventAt
                            RaftFreshnessTrigger.NetworkRestored ->
                                lastForegroundBoundaryByServerId[serverId] ?: eventAt
                        }
                    if (accepted && trigger == null && wasInFlight) {
                        pendingBoundaryByServerId[serverId] = eventBoundary
                    }
                    if (trigger == null) {
                        null
                    } else {
                        pendingBoundaryByServerId.remove(serverId)
                        launchFreshnessLoopLocked(serverId, trigger, eventBoundary)
                    }
                }
            toStart?.start()
        }
    }

    private fun resolveFollowUpBoundaryLocked(
        serverId: String,
        followUp: RaftFreshnessTrigger,
    ): ComparableTimeMark? {
        val queuedBoundary = pendingBoundaryByServerId.remove(serverId)
        return when (followUp) {
            RaftFreshnessTrigger.Reconnect -> queuedBoundary
            RaftFreshnessTrigger.Foreground -> lastForegroundBoundaryByServerId[serverId] ?: queuedBoundary
            RaftFreshnessTrigger.NetworkRestored -> lastForegroundBoundaryByServerId[serverId] ?: queuedBoundary
        }
    }

    private fun launchFreshnessLoopLocked(
        serverId: String,
        initialTrigger: RaftFreshnessTrigger,
        initialBoundary: ComparableTimeMark?,
    ): Job {
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                var currentTrigger: RaftFreshnessTrigger? = initialTrigger
                var currentBoundary: ComparableTimeMark? = initialBoundary
                try {
                    while (currentTrigger != null) {
                        val trigger = currentTrigger
                        val boundary = currentBoundary
                        coroutineContext.ensureActive()
                        val stillRelevant =
                            when (trigger) {
                                RaftFreshnessTrigger.Reconnect -> hasActiveObservers(serverId)
                                RaftFreshnessTrigger.Foreground,
                                RaftFreshnessTrigger.NetworkRestored -> hasLoadedOrObservedServer(serverId)
                            }
                        if (!stillRelevant) {
                            // Clear currentTrigger BEFORE returning so `finally` does not double-release.
                            currentTrigger = null
                            synchronized(lock) {
                                if (activeFreshness[serverId] === job) {
                                    freshnessGates[serverId]?.onCatchUpCompleted(succeeded = false, visible = false)
                                }
                            }
                            return@launch
                        }
                        val useIncrementalSync = syncChangesPullAllowed(serverId)
                        logger(
                            "computers_freshness_refresh serverId=$serverId trigger=${trigger.wireName} " +
                                "mode=${if (useIncrementalSync) "sync_changes+machines" else "machines"}"
                        )
                        val succeeded =
                            try {
                                if (useIncrementalSync) {
                                    // Recover both descriptive change_log rows and Redis-backed
                                    // machine online/offline status; machinesSyncRefreshCoalescer
                                    // skips /machines if a fresh read already succeeded.
                                    var syncOk = false
                                    var machinesOk = false
                                    coroutineScope {
                                        launch {
                                            syncOk =
                                                runCatchingPreservingCancellation {
                                                    pullSyncChanges(serverId, "freshness_${trigger.wireName}")
                                                }.getOrDefault(false)
                                        }
                                        launch {
                                            machinesOk =
                                                runCatchingPreservingCancellation {
                                                    refreshMachines(
                                                        serverId,
                                                        "freshness_${trigger.wireName}",
                                                        freshnessCoalesceWithin,
                                                        boundary,
                                                    )
                                                }.getOrDefault(false)
                                        }
                                    }
                                    syncOk && machinesOk
                                } else {
                                    refreshMachines(
                                        serverId,
                                        "freshness_${trigger.wireName}",
                                        freshnessCoalesceWithin,
                                        boundary,
                                    )
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Throwable) {
                                logger(
                                    "computers_freshness_refresh_failed serverId=$serverId " +
                                        "trigger=${trigger.wireName} type=${error::class.simpleName ?: "unknown"}"
                                )
                                false
                            }
                        val next =
                            synchronized(lock) {
                                if (activeFreshness[serverId] !== job) {
                                    null to null
                                } else {
                                    val followUp = freshnessGates[serverId]?.onCatchUpCompleted(succeeded = succeeded, visible = true)
                                    val followUpBoundary =
                                        followUp?.let { resolveFollowUpBoundaryLocked(serverId, it) }
                                    followUp to followUpBoundary
                                }
                            }
                        currentTrigger = next.first
                        currentBoundary = next.second
                    }
                } finally {
                    synchronized(lock) {
                        if (activeFreshness[serverId] === job) {
                            activeFreshness.remove(serverId)
                            // If cancelled mid-flight, release the gate's in-flight latch without
                            // stamping lastCatchUpAt so a later return/network restore can retry.
                            if (currentTrigger != null) {
                                freshnessGates[serverId]?.onCatchUpCompleted(succeeded = false, visible = false)
                            }
                        }
                    }
                }
            }
        activeFreshness[serverId] = job
        return job
    }

    fun cancel(serverId: String) {
        val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return
        val jobsToCancel =
            synchronized(lock) {
                latestRealtimeByServerId.remove(normalizedServerId)
                lastForegroundBoundaryByServerId.remove(normalizedServerId)
                pendingBoundaryByServerId.remove(normalizedServerId)
                activeExternalTokens.remove(normalizedServerId)
                freshnessGates.remove(normalizedServerId)?.reset()
                buildList {
                    pendingRealtime.remove(normalizedServerId)?.let(::add)
                    activeRealtime.remove(normalizedServerId)?.let(::addAll)
                    activeFreshness.remove(normalizedServerId)?.let(::add)
                }
            }
        jobsToCancel.forEach { it.cancel() }
    }

    fun cancelAll() {
        val jobsToCancel =
            synchronized(lock) {
                latestRealtimeByServerId.clear()
                lastForegroundBoundaryByServerId.clear()
                pendingBoundaryByServerId.clear()
                activeExternalTokens.clear()
                freshnessGates.values.forEach { it.reset() }
                freshnessGates.clear()
                buildList {
                    addAll(pendingRealtime.values)
                    pendingRealtime.clear()
                    activeRealtime.values.forEach(::addAll)
                    activeRealtime.clear()
                    addAll(activeFreshness.values)
                    activeFreshness.clear()
                }
            }
        jobsToCancel.forEach { it.cancel() }
    }

    private fun gateForLocked(serverId: String): RaftFreshnessGate =
        freshnessGates.getOrPut(serverId) {
            RaftFreshnessGate(
                nowMillis = nowMillis,
                trackOutcome = true,
            )
        }

    private fun Job.isPendingOrActive(): Boolean = !isCompleted && !isCancelled
}
