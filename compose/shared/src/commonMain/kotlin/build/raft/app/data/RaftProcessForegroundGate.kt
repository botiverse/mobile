package build.raft.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
    internal fun update(foreground: Boolean): Boolean {
        val previous = state.value
        state.value = foreground
        return previous
    }
}

private val RaftForegroundGateClockOrigin = TimeSource.Monotonic.markNow()

internal fun raftForegroundGateNowMillis(): Long = RaftForegroundGateClockOrigin.elapsedNow().inWholeMilliseconds

/**
 * Runs [block] every [intervalMillis] while [foreground] is true, and not at all while it is false.
 *
 * - Start: [block] runs at once when [runImmediatelyOnStart], else one interval later.
 * - Hidden: the timer stops (a [block] under way is cancelled with it).
 * - Back in front: if an interval came due while hidden, [block] runs once at once (never one run
 *   per missed interval); otherwise it runs when the interval that was under way ends. Then every
 *   [intervalMillis] again.
 *
 * [wait] and [nowMillis] are for tests; production uses `delay` and a monotonic clock. Never returns
 * normally: cancel the calling coroutine to stop it.
 */
internal suspend fun repeatWhileProcessForeground(
    intervalMillis: Long,
    foreground: Flow<Boolean> = RaftProcessForegroundGate.foreground,
    runImmediatelyOnStart: Boolean = false,
    wait: suspend (Long) -> Unit = { delay(it) },
    nowMillis: () -> Long = ::raftForegroundGateNowMillis,
    block: suspend () -> Unit,
) {
    require(intervalMillis > 0L) { "interval must be positive" }
    var lastRunAtMillis: Long? = if (runImmediatelyOnStart) null else nowMillis()
    var hiddenSinceStart = false
    foreground.distinctUntilChanged().collectLatest { inFront ->
        if (!inFront) {
            hiddenSinceStart = true
            return@collectLatest
        }
        val last = lastRunAtMillis
        val firstWait =
            when {
                last == null -> 0L
                // Never hidden: a plain interval (the clock is read only to measure a hide).
                !hiddenSinceStart -> intervalMillis
                else -> (intervalMillis - (nowMillis() - last)).coerceIn(0L, intervalMillis)
            }
        if (firstWait > 0L) wait(firstWait)
        while (true) {
            lastRunAtMillis = nowMillis()
            block()
            wait(intervalMillis)
        }
    }
}
