package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal const val HOME_SCROLL_LIST_NAME = "home"
internal const val DEFAULT_HOME_SCROLL_DEFER_MAX_MILLIS = 1_500L

/**
 * Scroll start/stop edges for host frame probes (Android `RaftScrollFrameProbe` measures vsync
 * cadence only while a list is scrolling and logs one line per scroll session) and for deferring
 * non-visible background tab projections/persistence while Home is actively scrolling
 * (#wg-mobile-feedback:#271).
 *
 * Uses a plain [MutableStateFlow] (never Compose snapshot state) and per-list token tracking so
 * multi-window or overlapping Home lists never clobber each other, and background stores/flows can
 * safely await scroll edges from any dispatcher.
 */
object RaftScrollFrameSignal {
    var sink: ((list: String, scrolling: Boolean) -> Unit)? = null

    private val lock = SynchronizedObject()
    private val defaultToken = Any()
    private val activeHomeScrollTokens = mutableSetOf<Any>()
    private val _homeScrolling = MutableStateFlow(false)

    val homeScrolling: StateFlow<Boolean> = _homeScrolling.asStateFlow()

    val isHomeScrolling: Boolean
        get() = _homeScrolling.value

    internal fun onListScrollChanged(
        list: String,
        scrolling: Boolean,
        token: Any = defaultToken,
    ) {
        if (list == HOME_SCROLL_LIST_NAME) {
            synchronized(lock) {
                if (scrolling) activeHomeScrollTokens.add(token) else activeHomeScrollTokens.remove(token)
                _homeScrolling.value = activeHomeScrollTokens.isNotEmpty()
            }
        }
        sink?.invoke(list, scrolling)
    }

    /**
     * Suspends until Home finishes scrolling (or [cancelWhen] becomes true), bounded by
     * [maxWaitMillis] so continuous drags never starve background updates. Returns immediately
     * without suspending when Home is not scrolling or [cancelWhen] is already true.
     */
    internal suspend fun awaitHomeScrollSettled(
        maxWaitMillis: Long = DEFAULT_HOME_SCROLL_DEFER_MAX_MILLIS,
        cancelWhen: StateFlow<Boolean>? = null,
    ) {
        if (!_homeScrolling.value || cancelWhen?.value == true) return
        if (maxWaitMillis <= 0L) return
        withTimeoutOrNull(maxWaitMillis) {
            if (cancelWhen == null) {
                _homeScrolling.first { !it }
            } else {
                combine(_homeScrolling, cancelWhen) { scrolling, cancel -> !scrolling || cancel }.first { it }
            }
        }
    }
}

/**
 * While [paused] is true (and [enabled], if provided, is true), conflates upstream emissions and
 * waits until pausing ends (or [maxHoldMillis] elapses from the first deferred emission) before
 * forwarding the single latest value downstream. When unpaused or disabled, emissions pass straight
 * through without suspending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<T>.deferWhile(
    paused: StateFlow<Boolean>,
    enabled: StateFlow<Boolean>? = null,
    maxHoldMillis: Long = DEFAULT_HOME_SCROLL_DEFER_MAX_MILLIS,
    timeSource: TimeSource = TimeSource.Monotonic,
): Flow<T> = flow {
    var holdStartedAt: TimeMark? = null
    transformLatest { value ->
        if (!paused.value || enabled?.value == false) {
            holdStartedAt = null
            emit(value)
        } else {
            val startedAt = holdStartedAt ?: timeSource.markNow().also { holdStartedAt = it }
            val remaining = (maxHoldMillis - startedAt.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)
            if (remaining > 0L) {
                withTimeoutOrNull(remaining) {
                    if (enabled == null) {
                        paused.first { !it }
                    } else {
                        combine(paused, enabled) { isPaused, isEnabled -> !isPaused || !isEnabled }.first { it }
                    }
                }
            }
            holdStartedAt = null
            emit(value)
        }
    }.collect(this)
}

/** Reports [isScrolling] edges of one list to [RaftScrollFrameSignal]; [list] names it in the log. */
@Composable
internal fun RaftScrollFrameSignalEffect(list: String, isScrolling: () -> Boolean) {
    val latestIsScrolling by rememberUpdatedState(isScrolling)
    val token = remember { Any() }
    LaunchedEffect(list) {
        try {
            snapshotFlow { latestIsScrolling() }
                .distinctUntilChanged()
                .collect { scrolling -> RaftScrollFrameSignal.onListScrollChanged(list, scrolling, token) }
        } finally {
            // The list left composition mid-fling: close its session instead of leaving it open.
            RaftScrollFrameSignal.onListScrollChanged(list, false, token)
        }
    }
}
