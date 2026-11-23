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
 * server's socket connection) are set for [RaftFreshnessTrigger.Reconnect] only, and so is [afterDrop]:
 * the socket came back by itself after a drop the app did not cause (a deploy drops every client at
 * once; data/realtime/RaftRealtimeConnectionCauses.kt), not a connection the app opened (back in the
 * foreground after it closed the socket in the background).
 */
data class RaftFreshnessEvent(
    val trigger: RaftFreshnessTrigger,
    val serverId: String? = null,
    val transportEpoch: Long? = null,
    val afterDrop: Boolean = false,
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

    /**
     * [transportEpoch]: the socket connection that came back; its `connected` and `rooms:joined` share it.
     * [afterDrop]: it came back by itself after a drop the app did not cause ([RaftFreshnessEvent.afterDrop]).
     */
    fun onReconnected(serverId: String, transportEpoch: Long, afterDrop: Boolean = false) {
        val id = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        mutableEvents.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, id, transportEpoch.takeIf { it > 0L }, afterDrop))
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
    /**
     * When true, [lastCatchUpAt] is stamped only when [onCatchUpCompleted] reports success, so a
     * failed foreground catch-up never consumes the coalescing window and a later
     * [RaftFreshnessTrigger.NetworkRestored] or [RaftFreshnessTrigger.Reconnect] retries
     * immediately (#wg-mobile-feedback:#273). While a tracked catch-up is in flight, an arriving
     * trigger is queued and returned from [onCatchUpCompleted] if needed.
     */
    private val trackOutcome: Boolean = false,
) {
    private var lastCatchUpAt: Long? = null

    /** The last socket connection seen per server: a page may move to another server. */
    private val lastTransportEpochs = mutableMapOf<String, Long>()
    private var pending: RaftFreshnessTrigger? = null

    /** The kept event is a new socket connection: it fires inside the coalescing window too. */
    private var pendingNewConnection = false

    private var inFlight = false
    private var queuedWhileInFlight: RaftFreshnessTrigger? = null
    private var queuedWhileInFlightForce = false
    private var acceptedEventSeq = 0L

    /** Monotonic counter incremented only when [onEvent] accepts (fires or queues) an event. */
    fun acceptedEventCount(): Long = acceptedEventSeq

    fun onEvent(event: RaftFreshnessEvent, serverId: String?, visible: Boolean): RaftFreshnessTrigger? {
        val trigger = classify(event, serverId) ?: return null
        val newConnection = trigger == RaftFreshnessTrigger.Reconnect && noteNewConnection(event)
        acceptedEventSeq += 1L
        if (!visible) {
            recordPending(trigger, newConnection)
            return null
        }
        val force = newConnection || pendingNewConnection
        if (trackOutcome && inFlight) {
            // Preserve the trigger while an async catch-up is running so a NetworkRestored or
            // Reconnect arriving before a hanging Foreground read fails is never lost.
            pending = null
            pendingNewConnection = false
            recordQueuedWhileInFlight(trigger, force)
            return null
        }
        // A kept event is answered by this catch-up (or was just answered by a recent one).
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
        if (trackOutcome && inFlight) {
            recordQueuedWhileInFlight(trigger, force)
            return null
        }
        return fire(trigger, force)
    }

    /** True while an outcome-tracked catch-up is currently running. */
    fun isInFlight(): Boolean = trackOutcome && inFlight

    /**
     * Records that an external catch-up (e.g. `SharedForegroundReconciler`'s foreground refresh)
     * started for this subscriber without going through [onEvent], so a [RaftFreshnessTrigger.NetworkRestored]
     * arriving while it is in flight is latched instead of dropped.
     */
    fun onExternalCatchUpStarted() {
        if (trackOutcome) {
            inFlight = true
        }
    }

    /**
     * Reports the outcome of the catch-up returned by [onEvent] / [onVisibilityChanged].
     *
     * - [succeeded] true: commits [lastCatchUpAt] so subsequent non-forced events within
     *   [coalesceMillis] coalesce. If a new-connection reconnect arrived while this catch-up was
     *   in flight, returns it (when [visible]) or keeps it pending.
     * - [succeeded] false: clears [lastCatchUpAt] so the failed read does not block a later
     *   [RaftFreshnessTrigger.NetworkRestored] / [RaftFreshnessTrigger.Reconnect] within
     *   [coalesceMillis]. If another trigger arrived while this failed catch-up was running,
     *   returns it immediately (when [visible]) or keeps it pending.
     */
    fun onCatchUpCompleted(succeeded: Boolean, visible: Boolean = true): RaftFreshnessTrigger? {
        val queued = queuedWhileInFlight
        val queuedForce = queuedWhileInFlightForce
        inFlight = false
        queuedWhileInFlight = null
        queuedWhileInFlightForce = false
        if (succeeded) {
            lastCatchUpAt = nowMillis()
            if (queued != null && queuedForce) {
                return dispatchFollowUp(queued, force = true, visible = visible)
            }
            return null
        }
        lastCatchUpAt = null
        if (queued != null) {
            return dispatchFollowUp(queued, force = queuedForce, visible = visible)
        }
        return null
    }

    fun reset() {
        lastCatchUpAt = null
        lastTransportEpochs.clear()
        pending = null
        pendingNewConnection = false
        inFlight = false
        queuedWhileInFlight = null
        queuedWhileInFlightForce = false
    }

    private fun dispatchFollowUp(
        trigger: RaftFreshnessTrigger,
        force: Boolean,
        visible: Boolean,
    ): RaftFreshnessTrigger? {
        if (!visible) {
            recordPending(trigger, force)
            return null
        }
        return fire(trigger, force)
    }

    private fun recordPending(trigger: RaftFreshnessTrigger, force: Boolean) {
        if (pending == null || (force && !pendingNewConnection)) pending = trigger
        pendingNewConnection = pendingNewConnection || force
    }

    private fun recordQueuedWhileInFlight(trigger: RaftFreshnessTrigger, force: Boolean) {
        if (queuedWhileInFlight == null || (force && !queuedWhileInFlightForce)) queuedWhileInFlight = trigger
        queuedWhileInFlightForce = queuedWhileInFlightForce || force
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
        if (trackOutcome) {
            inFlight = true
        } else {
            lastCatchUpAt = now
        }
        return trigger
    }
}
