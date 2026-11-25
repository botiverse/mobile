package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.data.realtime.RaftRealtimeConnectionCauses
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Data-layer freshness coordinator for actively observed channel member rosters
 * (`SharedServerMembersStore.channelMemberSliceFlow`, #wg-mobile-feedback:#274 Phase 1).
 *
 * Replaces page-owned `RaftLiveCatchUpEffect` in `ThreadMembersPage`:
 * - Tracks per-[SharedServerChannelMembersScope] catch-ups using outcome-tracked
 *   [RaftFreshnessGate] (`trackOutcome = true`) so a failed foreground read never consumes
 *   the 10 s coalescing window before `NetworkRestored` or `Reconnect`.
 * - Single-flight per channel scope; triggers arriving mid-flight are latched in the gate
 *   and dispatched immediately once a failing read (or a forced new-epoch reconnect) settles.
 * - Unobserved channel scopes receive zero catch-up refreshes.
 * - Automatic post-drop reconnects ([RaftFreshnessEvent.afterDrop]) wait a uniform 0–20 s
 *   jitter before refreshing, coalescing bursts and cancelling if an immediate trigger arrives
 *   or observers leave.
 */
@RaftCoreInternalApi class ChannelMembersLiveRefreshCoordinator(
    private val scope: CoroutineScope,
    private val observedScopes: () -> Set<SharedServerChannelMembersScope>,
    private val hasActiveObservers: (SharedServerChannelMembersScope) -> Boolean,
    private val refreshChannelMembers: suspend (
        scope: SharedServerChannelMembersScope,
        trigger: RaftFreshnessTrigger,
    ) -> Boolean,
    private val nowMillis: () -> Long = ::raftForegroundGateNowMillis,
    private val reconnectJitterMillis: () -> Long = { reconnectRefetchJitterMillis(Random.Default) },
    private val liveConnectionToken: (serverId: String) -> Long? = RaftRealtimeConnectionCauses::liveConnectionToken,
    private val logger: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private val freshnessGates = mutableMapOf<SharedServerChannelMembersScope, RaftFreshnessGate>()
    private val activeFreshness = mutableMapOf<SharedServerChannelMembersScope, Job>()
    private val pendingAfterDropReconnects = mutableMapOf<SharedServerChannelMembersScope, PendingAfterDropReconnect>()

    private class PendingAfterDropReconnect(
        var event: RaftFreshnessEvent,
        var job: Job? = null,
    )

    fun startFreshness(freshness: Flow<RaftFreshnessEvent>): Job =
        scope.launch {
            freshness.collect { event ->
                onFreshnessEvent(event)
            }
        }

    fun onFreshnessEvent(event: RaftFreshnessEvent) {
        val observed = observedScopes()
        if (observed.isEmpty()) return
        val targets =
            when (event.trigger) {
                RaftFreshnessTrigger.Foreground,
                RaftFreshnessTrigger.NetworkRestored -> observed
                RaftFreshnessTrigger.Reconnect -> {
                    val serverId = event.serverId?.trim()?.takeIf { it.isNotBlank() } ?: return
                    observed.filter { it.serverId == serverId }
                }
            }
        if (targets.isEmpty()) return
        targets.forEach { targetScope ->
            if (event.trigger == RaftFreshnessTrigger.Reconnect && event.afterDrop) {
                scheduleAfterDropReconnect(targetScope, event)
            } else {
                dispatchFreshnessEventForScope(targetScope, event)
            }
        }
    }

    fun onScopeUnobserved(targetScope: SharedServerChannelMembersScope) {
        val jobsToCancel =
            synchronized(lock) {
                freshnessGates.remove(targetScope)?.reset()
                buildList {
                    activeFreshness.remove(targetScope)?.let(::add)
                    pendingAfterDropReconnects.remove(targetScope)?.job?.let(::add)
                }
            }
        jobsToCancel.forEach { it.cancel() }
    }

    fun cancelServer(serverId: String) {
        val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return
        cancelWhere { it.serverId == normalizedServerId }
    }

    fun cancelWhere(predicate: (SharedServerChannelMembersScope) -> Boolean) {
        val jobsToCancel =
            synchronized(lock) {
                val matching =
                    (freshnessGates.keys + activeFreshness.keys + pendingAfterDropReconnects.keys)
                        .filterTo(mutableSetOf(), predicate)
                buildList {
                    matching.forEach { targetScope ->
                        freshnessGates.remove(targetScope)?.reset()
                        activeFreshness.remove(targetScope)?.let(::add)
                        pendingAfterDropReconnects.remove(targetScope)?.job?.let(::add)
                    }
                }
            }
        jobsToCancel.forEach { it.cancel() }
    }

    fun cancelAll() {
        val jobsToCancel =
            synchronized(lock) {
                freshnessGates.values.forEach { it.reset() }
                freshnessGates.clear()
                buildList {
                    addAll(activeFreshness.values)
                    activeFreshness.clear()
                    pendingAfterDropReconnects.values.mapNotNullTo(this) { it.job }
                    pendingAfterDropReconnects.clear()
                }
            }
        jobsToCancel.forEach { it.cancel() }
    }

    private fun scheduleAfterDropReconnect(
        targetScope: SharedServerChannelMembersScope,
        event: RaftFreshnessEvent,
    ) {
        val coalesced =
            synchronized(lock) {
                val existing = pendingAfterDropReconnects[targetScope]
                if (existing != null && existing.job?.isPendingOrActive() == true) {
                    existing.event = event
                    true
                } else {
                    false
                }
            }
        if (coalesced) return
        val waitMillis = reconnectJitterMillis().coerceIn(0L, RECONNECT_REFETCH_JITTER_MAX_MILLIS)
        if (waitMillis <= 0L) {
            dispatchFreshnessEventForScope(targetScope, event.copy(afterDrop = false))
            return
        }
        val toStart =
            synchronized(lock) {
                val existing = pendingAfterDropReconnects[targetScope]
                if (existing != null && existing.job?.isPendingOrActive() == true) {
                    existing.event = event
                    null
                } else {
                    val pending = PendingAfterDropReconnect(event = event)
                    lateinit var job: Job
                    job =
                        scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                delay(waitMillis)
                                val ready =
                                    synchronized(lock) {
                                        if (pendingAfterDropReconnects[targetScope] === pending) {
                                            pendingAfterDropReconnects.remove(targetScope)
                                            pending.event
                                        } else {
                                            null
                                        }
                                    } ?: return@launch
                                coroutineContext.ensureActive()
                                if (!hasActiveObservers(targetScope)) return@launch
                                dispatchFreshnessEventForScope(
                                    targetScope = targetScope,
                                    event = ready.copy(afterDrop = false),
                                )
                            } finally {
                                synchronized(lock) {
                                    if (pendingAfterDropReconnects[targetScope] === pending) {
                                        pendingAfterDropReconnects.remove(targetScope)
                                    }
                                }
                            }
                        }
                    if (job.isPendingOrActive()) {
                        pending.job = job
                        pendingAfterDropReconnects[targetScope] = pending
                        job
                    } else {
                        null
                    }
                }
            }
        if (toStart != null) {
            logger(
                "channel_members_freshness_reconnect_jitter serverId=${targetScope.serverId} " +
                    "channelId=${targetScope.channelId} delayMs=$waitMillis"
            )
            toStart.start()
        }
    }

    private fun dispatchFreshnessEventForScope(
        targetScope: SharedServerChannelMembersScope,
        event: RaftFreshnessEvent,
    ) {
        var cancelledJitter: Job? = null
        val toStart =
            synchronized(lock) {
                cancelledJitter = pendingAfterDropReconnects.remove(targetScope)?.job
                val gate = gateForLocked(targetScope)
                val trigger = gate.onEvent(event, targetScope.serverId, visible = true)
                if (trigger == null) {
                    null
                } else {
                    launchFreshnessLoopLocked(targetScope, trigger)
                }
            }
        cancelledJitter?.cancel()
        toStart?.start()
    }

    private fun launchFreshnessLoopLocked(
        targetScope: SharedServerChannelMembersScope,
        initialTrigger: RaftFreshnessTrigger,
    ): Job {
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                var currentTrigger: RaftFreshnessTrigger? = initialTrigger
                try {
                    while (currentTrigger != null) {
                        val trigger = currentTrigger
                        coroutineContext.ensureActive()
                        if (!hasActiveObservers(targetScope)) {
                            currentTrigger = null
                            synchronized(lock) {
                                if (activeFreshness[targetScope] === job) {
                                    freshnessGates[targetScope]?.onCatchUpCompleted(succeeded = false, visible = false)
                                }
                            }
                            return@launch
                        }
                        logger(
                            "channel_members_freshness_refresh serverId=${targetScope.serverId} " +
                                "channelId=${targetScope.channelId} trigger=${trigger.wireName}"
                        )
                        val succeeded =
                            try {
                                refreshChannelMembers(targetScope, trigger)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Throwable) {
                                logger(
                                    "channel_members_freshness_refresh_failed serverId=${targetScope.serverId} " +
                                        "channelId=${targetScope.channelId} trigger=${trigger.wireName} " +
                                        "type=${error::class.simpleName ?: "unknown"}"
                                )
                                false
                            }
                        val stillObserved = hasActiveObservers(targetScope)
                        val followUp =
                            synchronized(lock) {
                                if (activeFreshness[targetScope] !== job) {
                                    null
                                } else {
                                    freshnessGates[targetScope]?.onCatchUpCompleted(
                                        succeeded = succeeded,
                                        visible = stillObserved,
                                    )
                                }
                            }
                        currentTrigger = if (stillObserved) followUp else null
                    }
                } finally {
                    synchronized(lock) {
                        if (activeFreshness[targetScope] === job) {
                            activeFreshness.remove(targetScope)
                            if (currentTrigger != null) {
                                freshnessGates[targetScope]?.onCatchUpCompleted(succeeded = false, visible = false)
                            }
                        }
                    }
                }
            }
        activeFreshness[targetScope] = job
        return job
    }

    private fun gateForLocked(targetScope: SharedServerChannelMembersScope): RaftFreshnessGate =
        freshnessGates.getOrPut(targetScope) {
            RaftFreshnessGate(
                nowMillis = nowMillis,
                trackOutcome = true,
                liveConnectionToken = liveConnectionToken,
            )
        }

    private fun Job.isPendingOrActive(): Boolean = !isCompleted && !isCancelled
}
