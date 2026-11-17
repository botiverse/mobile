package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RaftEventBuses
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Why a realtime-fed page asks its store to catch up on its own. */
internal enum class RaftLiveCatchUpTrigger(val wireName: String) {
    /** The app came back to the foreground (`appLifecycle` resume / show). */
    Foreground("foreground"),

    /** The realtime socket of the page's server (re)connected: pushes sent meanwhile were missed. */
    Reconnect("reconnect"),
}

/**
 * Decides when a page whose content follows realtime pushes must reload by itself: pushes stop
 * while the app is in the background or the socket is down, and such a page has no manual refresh
 * to fall back on.
 *
 * - One catch-up per foreground ([RaftRealtimeNotifyContract.KeyForegroundEpoch]) and per socket
 *   connection of the page's server ([RaftRealtimeNotifyContract.KeyPresenceTransportEpoch]): the
 *   host may post the same signal more than once.
 * - A signal that arrives while the page is hidden is kept (one, the first) and fires when the page
 *   becomes visible again, so a hidden page does not load for nobody.
 *
 * Confined to the UI thread (the effect below hands every signal to the composition scope).
 */
internal class RaftLiveCatchUpGate(
    private val foreground: Boolean = true,
    private val reconnect: Boolean = true,
) {
    private var lastForegroundEpoch = 0L
    private var lastTransportServerId: String? = null
    private var lastTransportEpoch = 0L
    private var pending: RaftLiveCatchUpTrigger? = null

    fun onRealtimeSignal(eventData: JsonObject, serverId: String?, visible: Boolean): RaftLiveCatchUpTrigger? {
        val trigger = classify(eventData, serverId) ?: return null
        if (!visible) {
            if (pending == null) pending = trigger
            return null
        }
        pending = null
        return trigger
    }

    fun onVisibilityChanged(visible: Boolean): RaftLiveCatchUpTrigger? {
        if (!visible) return null
        return pending.also { pending = null }
    }

    private fun classify(eventData: JsonObject, serverId: String?): RaftLiveCatchUpTrigger? =
        when (eventData.optString(RaftRealtimeNotifyContract.KeyAction)) {
            RaftRealtimeNotifyContract.ActionAppLifecycle -> foregroundTrigger(eventData)
            RaftRealtimeNotifyContract.ActionPresenceTransportConnected -> reconnectTrigger(eventData, serverId)
            else -> null
        }

    private fun foregroundTrigger(eventData: JsonObject): RaftLiveCatchUpTrigger? {
        if (!foreground) return null
        val event = eventData.optString(RaftRealtimeNotifyContract.KeyLifecycleEvent)
        if (
            event != RaftRealtimeNotifyContract.LifecycleVisibleResume &&
            event != RaftRealtimeNotifyContract.LifecycleVisibleShow
        ) {
            return null
        }
        val epoch = eventData.optLong(RaftRealtimeNotifyContract.KeyForegroundEpoch, 0L)
        if (epoch > 0L) {
            if (epoch == lastForegroundEpoch) return null
            lastForegroundEpoch = epoch
        }
        return RaftLiveCatchUpTrigger.Foreground
    }

    private fun reconnectTrigger(eventData: JsonObject, serverId: String?): RaftLiveCatchUpTrigger? {
        if (!reconnect) return null
        val pageServerId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (eventData.optString(RaftRealtimeNotifyContract.KeyServerId).trim() != pageServerId) return null
        val epoch = eventData.optLong(RaftRealtimeNotifyContract.KeyPresenceTransportEpoch, 0L)
        if (epoch > 0L) {
            if (lastTransportServerId == pageServerId && epoch <= lastTransportEpoch) return null
            lastTransportServerId = pageServerId
            lastTransportEpoch = epoch
        }
        return RaftLiveCatchUpTrigger.Reconnect
    }
}

/**
 * Calls [onCatchUp] when the realtime-fed page on screen must catch up: the app returned to the
 * foreground, or the realtime socket of [serverId] (re)connected. [onCatchUp] must reach a
 * single-flight, content-keeping store refresh (Background lane: the content is already shown).
 * Turn [foreground] off where the route already refreshes on foreground (app shell tabs).
 */
@Composable
internal fun RaftLiveCatchUpEffect(
    serverId: String?,
    visible: Boolean,
    foreground: Boolean = true,
    reconnect: Boolean = true,
    // The process app-scope bus: hosts post `appLifecycle` and transport signals there.
    eventBus: RaftEventBus = RaftEventBuses.appScope(),
    onCatchUp: (RaftLiveCatchUpTrigger) -> Unit,
) {
    val realtimeEvents = remember(eventBus) { RaftRealtimeEvents(eventBus) }
    val gate = remember(foreground, reconnect) { RaftLiveCatchUpGate(foreground = foreground, reconnect = reconnect) }
    val scope = rememberCoroutineScope()
    val latestServerId by rememberUpdatedState(serverId)
    val latestVisible by rememberUpdatedState(visible)
    val latestOnCatchUp by rememberUpdatedState(onCatchUp)
    DisposableEffect(realtimeEvents, gate) {
        val subscription =
            realtimeEvents.addListener { eventData ->
                // Every realtime frame passes here: only the two signals leave the listener.
                val action = eventData.optString(RaftRealtimeNotifyContract.KeyAction)
                if (
                    action != RaftRealtimeNotifyContract.ActionAppLifecycle &&
                    action != RaftRealtimeNotifyContract.ActionPresenceTransportConnected
                ) {
                    return@addListener
                }
                scope.launch {
                    gate.onRealtimeSignal(eventData, latestServerId, latestVisible)?.let { latestOnCatchUp(it) }
                }
            }
        onDispose { realtimeEvents.removeListener(subscription) }
    }
    LaunchedEffect(gate, visible) {
        gate.onVisibilityChanged(visible)?.let { latestOnCatchUp(it) }
    }
}

/** One catch-up reload at a time for a page whose store has no in-flight marker of its own. UI-thread confined. */
internal class RaftCatchUpSingleFlight {
    private var running = false

    /** False while a catch-up runs: the new trigger joins it. */
    fun tryStart(): Boolean {
        if (running) return false
        running = true
        return true
    }

    fun finish() {
        running = false
    }
}
