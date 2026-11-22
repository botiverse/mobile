package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Folds a burst of `agent:updated` (or `machine:status`) events into one agents re-read per server
 * (Tenny, #3948 review): one server-wide change, e.g. renaming an app identity or many Computers
 * reconnecting after a server restart, sends one event per bound bot or Computer. The first event
 * schedules a read [windowMillis] later; events before it fires join that read. An event after the
 * read has started schedules the next one, so the last change is never missed. Safe to call from
 * any thread: [pending] is only touched under [lock].
 */
internal class AgentProfileRefreshCoalescer(
    private val scope: CoroutineScope,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val refresh: suspend (serverId: String) -> Unit,
) {
    private val lock = SynchronizedObject()
    private val pending = mutableMapOf<String, Job>()

    fun request(serverId: String) {
        val toStart =
            synchronized(lock) {
                if (pending[serverId]?.isPendingOrActive() == true) return
                lateinit var scheduled: Job
                scheduled =
                    scope.launch(start = CoroutineStart.LAZY) {
                        var shouldRun = false
                        try {
                            delay(windowMillis)
                            shouldRun =
                                synchronized(lock) {
                                    if (pending[serverId] === scheduled) {
                                        pending.remove(serverId)
                                        true
                                    } else {
                                        false
                                    }
                                }
                        } finally {
                            if (!shouldRun) {
                                synchronized(lock) {
                                    if (pending[serverId] === scheduled) {
                                        pending.remove(serverId)
                                    }
                                }
                            }
                        }
                        if (shouldRun) refresh(serverId)
                    }
                if (scheduled.isPendingOrActive()) {
                    pending[serverId] = scheduled
                    scheduled
                } else {
                    null
                }
            }
        toStart?.start()
    }

    private fun Job.isPendingOrActive(): Boolean = !isCompleted && !isCancelled

    fun cancel(serverId: String) {
        val job = synchronized(lock) { pending.remove(serverId) }
        job?.cancel()
    }

    fun cancelAll() {
        val jobs =
            synchronized(lock) {
                pending.values.toList().also { pending.clear() }
            }
        jobs.forEach { it.cancel() }
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 1_500L
    }
}
