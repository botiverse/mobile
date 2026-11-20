package build.raft.app.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Why what the app shows may have fallen behind (#proj-mobile:#198, raft-design freshness rule 1). */
enum class RaftFreshnessTrigger(val wireName: String) {
    /** The process came back to the foreground: pushes stop while it is in the background. */
    Foreground("foreground"),

    /** A server's realtime socket (re)joined its rooms: pushes sent while it was down were missed. */
    Reconnect("reconnect"),

    /** The device's network came back: reads that failed meanwhile may succeed now. */
    NetworkRestored("network"),
}

/**
 * One "re-check what you show" moment. [serverId] and [transportEpoch] (the host's number for that
 * server's socket connection) are set for [RaftFreshnessTrigger.Reconnect] only.
 */
data class RaftFreshnessEvent(
    val trigger: RaftFreshnessTrigger,
    val serverId: String? = null,
    val transportEpoch: Long? = null,
)

/**
 * The one process-wide signal that what the app shows may be stale (#proj-mobile:#198 ②): the app
 * came back to the foreground, a server's realtime socket rejoined its rooms, or the network came
 * back. Data owners (stores, and pages through [build.raft.app.RaftLiveCatchUpEffect]) subscribe and
 * re-read silently (Background priority, single-flight, content kept); nothing listens to the
 * platform lifecycle on its own.
 *
 * Fed by the platform hosts' process visibility owner ([RaftStaleReadRecoverySignals.onProcessVisibility]),
 * the realtime ingress's rooms-joined signal and the host's authoritative-online network event
 * ([RaftSharedStoreProvider]). Coalescing per page/store is the subscriber's job
 * ([RaftFreshnessGate]): the sources may fire close together for one return.
 */
object RaftFreshnessSignal {
    private val mutableEvents =
        MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val events: SharedFlow<RaftFreshnessEvent> = mutableEvents.asSharedFlow()

    fun onForeground() {
        mutableEvents.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
    }

    /** [transportEpoch]: the socket connection that came back; its `connected` and `rooms:joined` share it. */
    fun onReconnected(serverId: String, transportEpoch: Long) {
        val id = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        mutableEvents.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, id, transportEpoch.takeIf { it > 0L }))
    }

    fun onNetworkRestored() {
        mutableEvents.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
    }
}

/** raft-design freshness rule 1: events of one return within this window are one catch-up. */
const val RAFT_FRESHNESS_COALESCE_MILLIS = 10_000L

/**
 * Turns [RaftFreshnessSignal] events into at most one catch-up per return for one subscriber.
 *
 * - [RaftFreshnessTrigger.Reconnect] counts only for the subscriber's own server. A socket
 *   connection the gate has not seen for that server ([RaftFreshnessEvent.transportEpoch]) is the
 *   moment pushes resume, so it always catches up, however recent the last catch-up is; the same
 *   connection reported again (`connected`, then `rooms:joined`) does not. One without an epoch is
 *   coalesced like the two below.
 * - [RaftFreshnessTrigger.Foreground] and [RaftFreshnessTrigger.NetworkRestored] are skipped when a
 *   catch-up ran less than [coalesceMillis] ago (one return fires several of them).
 * - While the subscriber is not visible, one event is kept and fires when it shows again
 *   (HarmonyOS widgets: record while hidden, refresh once visible): the first, or a new socket
 *   connection once one arrives, which then fires whatever the window says.
 *
 * Not thread-safe: confine it to one coroutine (the UI thread or the store's).
 */
class RaftFreshnessGate(
    private val foreground: Boolean = true,
    private val reconnect: Boolean = true,
    private val coalesceMillis: Long = RAFT_FRESHNESS_COALESCE_MILLIS,
    private val nowMillis: () -> Long,
) {
    private var lastCatchUpAt: Long? = null

    /** The last socket connection seen per server: a page may move to another server. */
    private val lastTransportEpochs = mutableMapOf<String, Long>()
    private var pending: RaftFreshnessTrigger? = null

    /** The kept event is a new socket connection: it fires inside the coalescing window too. */
    private var pendingNewConnection = false

    fun onEvent(event: RaftFreshnessEvent, serverId: String?, visible: Boolean): RaftFreshnessTrigger? {
        val trigger = classify(event, serverId) ?: return null
        val newConnection = trigger == RaftFreshnessTrigger.Reconnect && noteNewConnection(event)
        if (!visible) {
            if (pending == null || (newConnection && !pendingNewConnection)) pending = trigger
            pendingNewConnection = pendingNewConnection || newConnection
            return null
        }
        // A kept event is answered by this catch-up (or was just answered by a recent one).
        val force = newConnection || pendingNewConnection
        pending = null
        pendingNewConnection = false
        return fire(trigger, force)
    }

    fun onVisibilityChanged(visible: Boolean): RaftFreshnessTrigger? {
        if (!visible) return null
        val trigger = pending ?: return null
        val force = pendingNewConnection
        pending = null
        pendingNewConnection = false
        return fire(trigger, force)
    }

    private fun classify(event: RaftFreshnessEvent, serverId: String?): RaftFreshnessTrigger? =
        when (event.trigger) {
            RaftFreshnessTrigger.Foreground -> if (foreground) event.trigger else null
            RaftFreshnessTrigger.NetworkRestored -> if (reconnect) event.trigger else null
            RaftFreshnessTrigger.Reconnect -> {
                val own = serverId?.trim()?.takeIf { it.isNotEmpty() }
                when {
                    !reconnect || own == null || event.serverId != own -> null
                    // The same connection again (`connected` + `rooms:joined`): already caught up or kept.
                    event.transportEpoch != null && lastTransportEpochs[own] == event.transportEpoch -> null
                    else -> event.trigger
                }
            }
        }

    /** Records the connection of an own-server reconnect; false when the event does not name one. */
    private fun noteNewConnection(event: RaftFreshnessEvent): Boolean {
        val server = event.serverId ?: return false
        val epoch = event.transportEpoch ?: return false
        lastTransportEpochs[server] = epoch
        return true
    }

    private fun fire(trigger: RaftFreshnessTrigger, force: Boolean): RaftFreshnessTrigger? {
        val now = nowMillis()
        val last = lastCatchUpAt
        if (!force && last != null && now - last < coalesceMillis) return null
        lastCatchUpAt = now
        return trigger
    }
}
