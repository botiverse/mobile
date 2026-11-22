package build.raft.app.data

import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
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
 * `/agents` fetch per active server within [windowMillis]. The latest `machine:status` arrival
 * timestamp is forwarded as `notStartedBefore` so the read never joins or reuses a pre-event
 * `/agents` request, while concurrent stores for the same server (or a post-event reconnect read)
 * still share a single post-event `/agents` fetch via [coalesceFreshWithin].
 */
internal class MachineStatusAgentRefresher(
    scope: CoroutineScope,
    private val storesForActiveServer: (serverId: String) -> List<SharedServerMembersStore>,
    windowMillis: Long = AgentProfileRefreshCoalescer.DEFAULT_WINDOW_MILLIS,
    private val coalesceFreshWithin: Duration = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val logger: (String) -> Unit = {},
) {
    private val eventTimesLock = SynchronizedObject()
    private val latestEventAtByServerId = mutableMapOf<String, ComparableTimeMark>()
    private val coalescer =
        AgentProfileRefreshCoalescer(scope = scope, windowMillis = windowMillis) { serverId ->
            refreshNow(serverId)
        }

    fun onMachineStatus(serverId: String) {
        val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return
        if (storesForActiveServer(normalizedServerId).isEmpty()) return
        synchronized(eventTimesLock) {
            latestEventAtByServerId[normalizedServerId] = timeSource.markNow()
        }
        coalescer.request(normalizedServerId)
    }

    fun cancel(serverId: String) {
        val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return
        synchronized(eventTimesLock) {
            latestEventAtByServerId.remove(normalizedServerId)
        }
        coalescer.cancel(normalizedServerId)
    }

    fun cancelAll() {
        synchronized(eventTimesLock) {
            latestEventAtByServerId.clear()
        }
        coalescer.cancelAll()
    }

    private suspend fun refreshNow(serverId: String) {
        val notStartedBefore =
            synchronized(eventTimesLock) {
                latestEventAtByServerId.remove(serverId)
            } ?: timeSource.markNow()
        val stores = storesForActiveServer(serverId)
        if (stores.isEmpty()) return
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
    }
}
