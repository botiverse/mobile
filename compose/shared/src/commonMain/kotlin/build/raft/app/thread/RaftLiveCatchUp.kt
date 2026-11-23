package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.data.RaftFreshnessEvent
import build.raft.app.data.RaftFreshnessGate
import build.raft.app.data.RaftFreshnessSignal
import build.raft.app.data.RaftFreshnessTrigger
import build.raft.app.data.raftForegroundGateNowMillis
import kotlinx.coroutines.flow.Flow

/** Why a realtime-fed page asks its store to catch up on its own (the one signal, #198). */
internal typealias RaftLiveCatchUpTrigger = RaftFreshnessTrigger

/**
 * Calls [onCatchUp] when the realtime-fed page on screen must catch up: the app returned to the
 * foreground, the realtime socket of [serverId] rejoined its rooms, or the network came back
 * ([RaftFreshnessSignal], the process's one freshness signal, #proj-mobile:#198). [onCatchUp] must
 * reach a single-flight, content-keeping store refresh (Background lane: the content is already
 * shown). Events of one return are one catch-up and every new socket connection of [serverId] is
 * one ([RaftFreshnessGate]); one that arrives while the page is hidden waits until it shows. Turn
 * [foreground] off where the route already refreshes on foreground (app shell tabs).
 */
@Composable
internal fun RaftLiveCatchUpEffect(
    serverId: String?,
    visible: Boolean,
    foreground: Boolean = true,
    reconnect: Boolean = true,
    events: Flow<RaftFreshnessEvent> = RaftFreshnessSignal.events,
    // Monotonic: the coalescing window must not follow wall-clock changes.
    nowMillis: () -> Long = ::raftForegroundGateNowMillis,
    onCatchUp: (RaftLiveCatchUpTrigger) -> Unit,
) {
    val gate = remember(foreground, reconnect) { RaftFreshnessGate(foreground = foreground, reconnect = reconnect, nowMillis = nowMillis) }
    val latestServerId by rememberUpdatedState(serverId)
    val latestVisible by rememberUpdatedState(visible)
    val latestOnCatchUp by rememberUpdatedState(onCatchUp)
    LaunchedEffect(gate, events) {
        // Collected on the composition's (main) dispatcher: the gate is confined to it.
        events.collect { event ->
            gate.onEvent(event, latestServerId, latestVisible)?.let { latestOnCatchUp(it) }
        }
    }
    LaunchedEffect(gate, visible) {
        gate.onVisibilityChanged(visible, latestServerId)?.let { latestOnCatchUp(it) }
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
