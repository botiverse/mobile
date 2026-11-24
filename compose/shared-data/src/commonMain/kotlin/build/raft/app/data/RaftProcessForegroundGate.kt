package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.TimeSource

/**
 * Whether the app process is in the foreground: the one switch periodic work reads to stay quiet
 * while the app is hidden. Fed only by [build.raft.app.network.updateRaftProcessNetworkVisibility]
 * (the platform hosts' process visibility owner, through [RaftStaleReadRecoverySignals]); true until
 * a host reports the process hidden.
 *
 * Why: a cached Android process is frozen, and a timer that fires each time it is thawed (a push, a
 * broadcast) adds a burst of work exactly while the system tries to freeze it again. Android 17
 * kills an app whose freeze keeps failing (Hands a8cd6426 "excessive binder traffic during cached",
 * fe049641 "Sync transaction while frozen").
 */
object RaftProcessForegroundGate {
    private val state = MutableStateFlow(true)

    val foreground: StateFlow<Boolean> = state.asStateFlow()

    /** Sets the state; returns the previous value. Only [RaftStaleReadRecoverySignals.onProcessVisibility] calls it. */
    @RaftCoreInternalApi fun update(foreground: Boolean): Boolean {
        val previous = state.value
        state.value = foreground
        return previous
    }
}

private val RaftForegroundGateClockOrigin = TimeSource.Monotonic.markNow()

@RaftCoreInternalApi fun raftForegroundGateNowMillis(): Long = RaftForegroundGateClockOrigin.elapsedNow().inWholeMilliseconds

/**
 * Runs [block] every [intervalMillis] while [foreground] is true, and not at all while it is false.
 *
 * - Start: [block] runs at once when [runImmediatelyOnStart], else one interval later.
 * - Hidden: nothing runs. A wait under way finishes, then the loop parks until the app is back
 *   (at most one wake-up after the hide, no work).
 * - Back in front: if an interval came due while hidden, [block] runs once at once (never one run
 *   per missed interval); otherwise it runs when the interval under way ends. Then every
 *   [intervalMillis] again.
 *
 * One coroutine, no child: whatever [block] or [wait] throws (a cancellation included) ends the
 * loop and reaches the caller. [wait] and [nowMillis] are for tests; production uses `delay` and a
 * monotonic clock. Never returns normally: cancel the calling coroutine to stop it.
 */
@RaftCoreInternalApi suspend fun repeatWhileProcessForeground(
    intervalMillis: Long,
    foreground: StateFlow<Boolean> = RaftProcessForegroundGate.foreground,
    runImmediatelyOnStart: Boolean = false,
    wait: suspend (Long) -> Unit = { delay(it) },
    nowMillis: () -> Long = ::raftForegroundGateNowMillis,
    block: suspend () -> Unit,
) {
    require(intervalMillis > 0L) { "interval must be positive" }
    var lastRunAtMillis: Long? = if (runImmediatelyOnStart) null else nowMillis()
    var nextWaitMillis = if (runImmediatelyOnStart) 0L else intervalMillis
    while (true) {
        if (!foreground.value) {
            foreground.first { it }
            val last = lastRunAtMillis
            nextWaitMillis = if (last == null) 0L else (intervalMillis - (nowMillis() - last)).coerceIn(0L, intervalMillis)
        }
        if (nextWaitMillis > 0L) wait(nextWaitMillis)
        // Hidden meanwhile: decide again once the app is back.
        if (!foreground.value) continue
        lastRunAtMillis = nowMillis()
        block()
        nextWaitMillis = intervalMillis
    }
}
