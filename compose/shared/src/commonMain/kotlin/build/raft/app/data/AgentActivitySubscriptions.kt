package build.raft.app.data

import build.raft.app.data.realtime.RealtimeSocketEvents
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Which agents' detailed `agent:activity` this app wants, per server socket (botiverse/slock 968d102d
 * `packages/shared/src/agentActivitySubscriptions.ts`). The socket declares the capability, so without a
 * subscription it gets only the coarse `agent:activity_state` (enough for status dots).
 *
 * A screen that shows an agent's detail (its DM, its detail page, a channel's member list) [acquire]s a
 * lease for the agents it shows and releases it when it leaves. The wanted set is the union of the live
 * leases (ref-counted). Changes are sent [flushDelayMillis] later as one diff, so a page replacing another
 * with the same agent sends nothing, in emits of at most `AgentActivitySubscribeMaxIds` ids. A new socket
 * starts with nothing subscribed: [onConnected] sends the whole wanted set again. The server answers each
 * subscribe with the ids it actually joined ([onSubscribed], [confirmed]); it does not replay the current
 * detail, so a screen keeps what it shows until the agent's next change.
 */
internal class AgentActivitySubscriptions(
    private val scope: CoroutineScope,
    /** Emit `agent:activity:subscribe` (true) / `:unsubscribe` (false) `{ agentIds }` on [serverId]'s socket. */
    private val send: (serverId: String, subscribe: Boolean, agentIds: List<String>) -> Unit,
    private val flushDelayMillis: Long = DefaultFlushDelayMillis,
) {
    private class ServerState {
        val refCounts = mutableMapOf<String, Int>()

        /** What the current socket was asked for (subscribes minus unsubscribes since it connected). */
        var sent: Set<String> = emptySet()
        var flushJob: Job? = null
    }

    private val lock = SynchronizedObject()
    private val servers = mutableMapOf<String, ServerState>()
    private val confirmedState = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Per server, the ids the server said it subscribed this socket to (and not unsubscribed since). */
    val confirmed: StateFlow<Map<String, Set<String>>> = confirmedState.asStateFlow()

    fun acquire(serverId: String, agentIds: Collection<String>): AgentActivitySubscriptionLease {
        val server = serverId.trim()
        val ids =
            agentIds.map(String::trim).filter(String::isNotEmpty).distinct().take(RealtimeSocketEvents.AgentActivitySubscribeMaxIds)
        if (server.isEmpty() || ids.isEmpty()) return AgentActivitySubscriptionLease {}
        synchronized(lock) {
            val state = servers.getOrPut(server) { ServerState() }
            ids.forEach { id -> state.refCounts[id] = (state.refCounts[id] ?: 0) + 1 }
            scheduleFlushLocked(server, state)
        }
        return AgentActivitySubscriptionLease { release(server, ids) }
    }

    /** A new socket for [serverId] connected: it has no subscriptions yet, so send the whole wanted set. */
    fun onConnected(serverId: String) {
        val server = serverId.trim()
        synchronized(lock) {
            confirmedState.update { it - server }
            val state = servers[server] ?: return
            state.flushJob?.cancel()
            state.flushJob = null
            state.sent = state.refCounts.keys.toSet()
            sendLocked(server, subscribe = true, ids = state.sent)
        }
    }

    /** `agent:activity:subscribed`: the ids the server joined for one subscribe (still wanted ones only). */
    fun onSubscribed(serverId: String, agentIds: List<String>) {
        val server = serverId.trim()
        synchronized(lock) {
            val sent = servers[server]?.sent.orEmpty()
            val joined = agentIds.filter { it in sent }
            if (joined.isEmpty()) return
            confirmedState.update { current -> current + (server to (current[server].orEmpty() + joined)) }
        }
    }

    private fun release(server: String, ids: List<String>) {
        synchronized(lock) {
            val state = servers[server] ?: return
            ids.forEach { id ->
                val count = state.refCounts[id] ?: return@forEach
                if (count <= 1) state.refCounts.remove(id) else state.refCounts[id] = count - 1
            }
            scheduleFlushLocked(server, state)
        }
    }

    private fun scheduleFlushLocked(server: String, state: ServerState) {
        if (state.flushJob?.isActive == true) return
        state.flushJob =
            scope.launch {
                delay(flushDelayMillis)
                flush(server)
            }
    }

    private fun flush(server: String) {
        synchronized(lock) {
            val state = servers[server] ?: return
            state.flushJob = null
            val wanted = state.refCounts.keys.toSet()
            val removed = state.sent - wanted
            val added = wanted - state.sent
            state.sent = wanted
            if (wanted.isEmpty()) servers.remove(server)
            if (removed.isNotEmpty()) {
                confirmedState.update { current ->
                    val left = current[server].orEmpty() - removed
                    if (left.isEmpty()) current - server else current + (server to left)
                }
            }
            // Sent under the lock so two diffs can never reach the socket out of order.
            sendLocked(server, subscribe = false, ids = removed)
            sendLocked(server, subscribe = true, ids = added)
        }
    }

    private fun sendLocked(server: String, subscribe: Boolean, ids: Set<String>) {
        ids.chunked(RealtimeSocketEvents.AgentActivitySubscribeMaxIds).forEach { chunk -> send(server, subscribe, chunk) }
    }

    companion object {
        const val DefaultFlushDelayMillis = 300L
    }
}

/** Releases its agents once; releasing again does nothing. */
internal class AgentActivitySubscriptionLease(private val onRelease: () -> Unit) {
    private var released = false

    fun release() {
        if (released) return
        released = true
        onRelease()
    }
}
