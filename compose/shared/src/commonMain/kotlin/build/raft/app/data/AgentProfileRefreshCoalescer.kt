package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Folds a burst of `agent:updated` into one agents re-read per server (Tenny, #3948 review): one
 * server-wide change, e.g. renaming an app identity, sends one event per bound bot. The first event
 * schedules a read [windowMillis] later; events before it fires join that read. An event after the
 * read has started schedules the next one, so the last change is never missed.
 */
internal class AgentProfileRefreshCoalescer(
    private val scope: CoroutineScope,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val refresh: suspend (serverId: String) -> Unit,
) {
    private val pending = mutableMapOf<String, Job>()

    fun request(serverId: String) {
        if (pending[serverId]?.isActive == true) return
        pending[serverId] = scope.launch {
            delay(windowMillis)
            pending.remove(serverId)
            refresh(serverId)
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 1_500L
    }
}
