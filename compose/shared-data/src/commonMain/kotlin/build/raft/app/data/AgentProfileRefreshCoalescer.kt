package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Folds a burst of `agent:updated` into one agents re-read per server (Tenny, #3948 review): one
 * server-wide change, e.g. renaming an app identity, sends one event per bound bot. The first event
 * schedules a read [windowMillis] later; events before it fires join that read. An event after the
 * read has started schedules the next one, so the last change is never missed. Safe to call from
 * any thread: [pending] and [active] are only touched under [lock].
 */
@RaftCoreInternalApi class AgentProfileRefreshCoalescer(
    private val scope: CoroutineScope,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val refresh: suspend (serverId: String) -> Unit,
) {
    private val lock = SynchronizedObject()
    private val pending = mutableMapOf<String, Job>()
    private val active = mutableMapOf<String, MutableSet<Job>>()

    fun request(serverId: String) {
        val toStart =
            synchronized(lock) {
                if (pending[serverId]?.isPendingOrActive() == true) {
                    null
                } else {
                    lateinit var scheduled: Job
                    scheduled =
                        scope.launch(start = CoroutineStart.LAZY) {
                            var promotedToActive = false
                            try {
                                delay(windowMillis)
                                synchronized(lock) {
                                    if (scheduled.isActive && pending[serverId] === scheduled) {
                                        pending.remove(serverId)
                                        active.getOrPut(serverId) { mutableSetOf() }.add(scheduled)
                                        promotedToActive = true
                                    }
                                }
                                if (promotedToActive) {
                                    coroutineContext.ensureActive()
                                    refresh(serverId)
                                }
                            } finally {
                                synchronized(lock) {
                                    if (pending[serverId] === scheduled) {
                                        pending.remove(serverId)
                                    }
                                    if (promotedToActive) {
                                        val activeForServer = active[serverId]
                                        if (activeForServer != null) {
                                            activeForServer.remove(scheduled)
                                            if (activeForServer.isEmpty()) {
                                                active.remove(serverId)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    if (scheduled.isPendingOrActive()) {
                        pending[serverId] = scheduled
                        scheduled
                    } else {
                        null
                    }
                }
            }
        toStart?.start()
    }

    private fun Job.isPendingOrActive(): Boolean = !isCompleted && !isCancelled

    fun cancel(serverId: String) {
        val jobsToCancel =
            synchronized(lock) {
                buildList {
                    pending.remove(serverId)?.let(::add)
                    active.remove(serverId)?.let(::addAll)
                }
            }
        jobsToCancel.forEach { it.cancel() }
    }

    fun cancelAll() {
        val jobsToCancel =
            synchronized(lock) {
                buildList {
                    addAll(pending.values)
                    pending.clear()
                    active.values.forEach(::addAll)
                    active.clear()
                }
            }
        jobsToCancel.forEach { it.cancel() }
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 1_500L
    }
}
