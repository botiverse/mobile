package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Coalesced `/agents` recovery on realtime `machine:status` (web `executeMachineReloadRecovery`
 * parity, slock `socketBridge.ts` / `agentOrchestrator.ts`): when a Computer's daemon reconnects
 * after a server restart or outage, the server emits `machine:status` and does NOT broadcast
 * `agent:activity_state: "online"` for idle agents. Re-reading `/agents` recovers their
 * server-derived online activity.
 *
 * Bursts (e.g. many Computers reconnecting together after a server restart) fold into one
 * `/agents` fetch per active server within [windowMillis], and repeated `machine:status` events
 * are rate-limited to at most one refresh per [minRefreshIntervalMillis] per server (coalescing
 * any events arriving during the cooldown into one trailing refresh with the latest arrival
 * boundary). Pending debounce jobs, active in-flight refresh jobs, the last refresh start mark,
 * and the latest `machine:status` arrival timestamp are coordinated under a single [lock] so:
 * - A single `machine:status` after idle runs promptly after [windowMillis].
 * - Events arriving before the scheduled refresh fires update the `notStartedBefore` boundary
 *   atomically without scheduling a duplicate follow-up.
 * - An event arriving after a refresh has already started schedules a single trailing follow-up
 *   once the remaining [minRefreshIntervalMillis] cooldown elapses (or after [windowMillis] when
 *   no cooldown applies) with its own arrival timestamp.
 * - [cancel] and [cancelAll] cancel both pending and in-flight refreshes for the server(s) and
 *   drop their recorded timestamps and cooldown marks; a missing timestamp never fabricates `now`
 *   or triggers a read.
 * - Forwarding `notStartedBefore` prevents joining or reusing a pre-event `/agents` read, while
 *   concurrent stores for the same server still share one post-event read via [coalesceFreshWithin].
 */
@RaftCoreInternalApi class MachineStatusAgentRefresher(
    private val scope: CoroutineScope,
    private val storesForActiveServer: (serverId: String) -> List<SharedServerMembersStore>,
    private val windowMillis: Long = AgentProfileRefreshCoalescer.DEFAULT_WINDOW_MILLIS,
    private val minRefreshIntervalMillis: Long = DEFAULT_MIN_REFRESH_INTERVAL_MILLIS,
    private val coalesceFreshWithin: Duration = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val logger: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private val pending = mutableMapOf<String, Job>()
    private val active = mutableMapOf<String, MutableSet<Job>>()
    private val latestEventAtByServerId = mutableMapOf<String, ComparableTimeMark>()
    private val lastRefreshStartedAtByServerId = mutableMapOf<String, ComparableTimeMark>()

    fun onMachineStatus(serverId: String) {
        val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return
        if (storesForActiveServer(normalizedServerId).isEmpty()) return
        val toStart =
            synchronized(lock) {
                val eventAt = timeSource.markNow()
                if (pending[normalizedServerId]?.isPendingOrActive() == true) {
                    latestEventAtByServerId[normalizedServerId] = eventAt
                    null
                } else {
                    val remainingCooldownMillis =
                        if (minRefreshIntervalMillis > 0L) {
                            val lastStarted = lastRefreshStartedAtByServerId[normalizedServerId]
                            if (lastStarted != null) {
                                val elapsedMillis = (eventAt - lastStarted).inWholeMilliseconds.coerceAtLeast(0L)
                                (minRefreshIntervalMillis - elapsedMillis).coerceAtLeast(0L)
                            } else {
                                0L
                            }
                        } else {
                            0L
                        }
                    val delayMillis = maxOf(windowMillis, remainingCooldownMillis)
                    lateinit var scheduled: Job
                    scheduled =
                        scope.launch(start = CoroutineStart.LAZY) {
                            var notStartedBefore: ComparableTimeMark? = null
                            var promotedToActive = false
                            try {
                                delay(delayMillis)
                                synchronized(lock) {
                                    if (scheduled.isActive && pending[normalizedServerId] === scheduled) {
                                        pending.remove(normalizedServerId)
                                        val recordedEventAt = latestEventAtByServerId.remove(normalizedServerId)
                                        if (recordedEventAt != null) {
                                            notStartedBefore = recordedEventAt
                                            active.getOrPut(normalizedServerId) { mutableSetOf() }.add(scheduled)
                                            promotedToActive = true
                                        }
                                    }
                                }
                                val boundary = notStartedBefore ?: return@launch
                                coroutineContext.ensureActive()
                                refreshNow(normalizedServerId, boundary, scheduled)
                            } finally {
                                synchronized(lock) {
                                    if (pending[normalizedServerId] === scheduled) {
                                        pending.remove(normalizedServerId)
                                        latestEventAtByServerId.remove(normalizedServerId)
                                    }
                                    if (promotedToActive) {
                                        val activeForServer = active[normalizedServerId]
                                        if (activeForServer != null) {
                                            activeForServer.remove(scheduled)
                                            if (activeForServer.isEmpty()) {
                                                active.remove(normalizedServerId)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    if (scheduled.isPendingOrActive()) {
                        latestEventAtByServerId[normalizedServerId] = eventAt
                        pending[normalizedServerId] = scheduled
                        scheduled
                    } else {
                        null
                    }
                }
            }
        toStart?.start()
    }

    fun cancel(serverId: String) {
        val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return
        val jobsToCancel =
            synchronized(lock) {
                latestEventAtByServerId.remove(normalizedServerId)
                lastRefreshStartedAtByServerId.remove(normalizedServerId)
                buildList {
                    pending.remove(normalizedServerId)?.let(::add)
                    active.remove(normalizedServerId)?.let(::addAll)
                }
            }
        jobsToCancel.forEach { it.cancel() }
    }

    fun cancelAll() {
        val jobsToCancel =
            synchronized(lock) {
                latestEventAtByServerId.clear()
                lastRefreshStartedAtByServerId.clear()
                buildList {
                    addAll(pending.values)
                    pending.clear()
                    active.values.forEach(::addAll)
                    active.clear()
                }
            }
        jobsToCancel.forEach { it.cancel() }
    }

    private fun Job.isPendingOrActive(): Boolean = !isCompleted && !isCancelled

    private suspend fun refreshNow(
        serverId: String,
        notStartedBefore: ComparableTimeMark,
        job: Job,
    ) {
        val stores = storesForActiveServer(serverId)
        if (stores.isEmpty()) return
        val stillActive =
            synchronized(lock) {
                if (active[serverId]?.contains(job) == true) {
                    if (minRefreshIntervalMillis > 0L) {
                        lastRefreshStartedAtByServerId[serverId] = timeSource.markNow()
                    }
                    true
                } else {
                    false
                }
            }
        if (!stillActive) return
        logger("machine_status_agents_refresh serverId=$serverId stores=${stores.size}")
        coroutineScope {
            stores.forEach { store ->
                launch {
                    try {
                        store.refreshAgents(
                            serverId = ServerId(serverId),
                            reason = REASON,
                            coalesceFreshWithin = coalesceFreshWithin,
                            notStartedBefore = notStartedBefore,
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        logger(
                            "machine_status_agents_refresh_failed serverId=$serverId " +
                                "type=${error::class.simpleName ?: "unknown"}"
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val REASON = "realtime_${RealtimeSocketEvents.MachineStatus}"
        const val DEFAULT_MIN_REFRESH_INTERVAL_MILLIS = 15_000L
    }
}
