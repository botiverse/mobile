package build.raft.webcontainer.resilience

import build.raft.webcontainer.WebNavigationState

/** Lifecycle & health phase tracked by [WebContainerWatchdog]. */
enum class WebContainerPhase {
    Idle,
    Loading,
    Committed,
    Ready,
    BlankSuspected,
    Failed,
    Recovering,
    CrashedTerminal,
}

/** Tuning parameters for load-stall detection, DOM blank probing, and renderer-crash circuit breaking. */
data class WebContainerWatchdogPolicy(
    val stallTimeoutMs: Long = 15_000L,
    val blankProbeDelayMs: Long = 1_500L,
    val crashWindowMs: Long = 60_000L,
    val maxRecoveriesInWindow: Int = 2,
) {
    init {
        require(stallTimeoutMs > 0L) { "stallTimeoutMs must be > 0." }
        require(blankProbeDelayMs >= 0L) { "blankProbeDelayMs must be >= 0." }
        require(crashWindowMs > 0L) { "crashWindowMs must be > 0." }
        require(maxRecoveriesInWindow >= 0) { "maxRecoveriesInWindow must be >= 0." }
    }
}

/** Input events fed into [WebContainerWatchdog]. */
sealed interface WebContainerWatchdogEvent {
    data class LoadStarted(val generation: Int) : WebContainerWatchdogEvent

    data class PageCommitted(val generation: Int) : WebContainerWatchdogEvent

    data class PageFinished(val generation: Int) : WebContainerWatchdogEvent

    /** [hasVisibleContent]: `true` = content painted, `false` = blank DOM, `null` = probe unavailable (fail open). */
    data class DomProbeResult(val generation: Int, val hasVisibleContent: Boolean?) : WebContainerWatchdogEvent

    data object Tick : WebContainerWatchdogEvent

    data class LoadFailed(val generation: Int, val reason: String) : WebContainerWatchdogEvent

    /** In-flight navigation was cleanly handed off (e.g. converted to a download) without failing the existing page. */
    data class LoadCancelled(val generation: Int) : WebContainerWatchdogEvent

    data class RenderProcessGone(val didCrash: Boolean) : WebContainerWatchdogEvent

    /** Container became hidden/backgrounded; pauses stall timeout and DOM blank probing without consuming foreground budget. */
    data object Backgrounded : WebContainerWatchdogEvent

    /** Container returned to foreground visibility; shifts active load/finish anchors forward by background dwell time. */
    data object Foregrounded : WebContainerWatchdogEvent

    /** Resets all load, crash-window, and background-pause state back to initial [WebContainerPhase.Idle]. */
    data object Reset : WebContainerWatchdogEvent
}

/** Action requested by [WebContainerWatchdog.onEvent]. */
sealed interface WebContainerWatchdogAction {
    data object None : WebContainerWatchdogAction

    data class RequestDomProbe(val generation: Int) : WebContainerWatchdogAction

    data class TriggerRecoveryReload(val recoveryAttempt: Int) : WebContainerWatchdogAction

    data class EmitTerminalError(val reason: String) : WebContainerWatchdogAction
}

/**
 * Pure, clock-injected container resilience state machine:
 * - Detects stalled main-frame loads exceeding [WebContainerWatchdogPolicy.stallTimeoutMs].
 * - Requests a DOM blank probe after [WebContainerWatchdogPolicy.blankProbeDelayMs] post-finish.
 * - Enforces a rolling-window renderer-crash circuit breaker so repeated crashes stop reload loops
 *   and escalate cleanly to [WebContainerWatchdogAction.EmitTerminalError].
 */
class WebContainerWatchdog(
    private val policy: WebContainerWatchdogPolicy = WebContainerWatchdogPolicy(),
) {
    var phase: WebContainerPhase = WebContainerPhase.Idle
        private set
    var activeGeneration: Int = 0
        private set
    var isBackgrounded: Boolean = false
        private set

    private var loadStartedAtMs: Long = -1L
    private var finishedAtMs: Long = -1L
    private var backgroundedAtMs: Long = -1L
    private var probeRequestedForGen: Int = -1
    private var hasPreviouslyCommitted: Boolean = false
    private val recentRecoveryTimestampsMs = ArrayDeque<Long>()

    val recentRecoveryCount: Int
        get() = recentRecoveryTimestampsMs.size

    fun onEvent(
        event: WebContainerWatchdogEvent,
        nowMs: Long,
    ): WebContainerWatchdogAction {
        return when (event) {
            WebContainerWatchdogEvent.Reset -> {
                phase = WebContainerPhase.Idle
                activeGeneration = 0
                isBackgrounded = false
                loadStartedAtMs = -1L
                finishedAtMs = -1L
                backgroundedAtMs = -1L
                probeRequestedForGen = -1
                hasPreviouslyCommitted = false
                recentRecoveryTimestampsMs.clear()
                WebContainerWatchdogAction.None
            }

            WebContainerWatchdogEvent.Backgrounded -> {
                if (!isBackgrounded) {
                    isBackgrounded = true
                    backgroundedAtMs = nowMs
                }
                WebContainerWatchdogAction.None
            }

            WebContainerWatchdogEvent.Foregrounded -> {
                if (isBackgrounded) {
                    if (loadStartedAtMs >= 0L) {
                        val pauseAnchor = maxOf(loadStartedAtMs, backgroundedAtMs)
                        loadStartedAtMs += (nowMs - pauseAnchor).coerceAtLeast(0L)
                    }
                    if (finishedAtMs >= 0L) {
                        val pauseAnchor = maxOf(finishedAtMs, backgroundedAtMs)
                        finishedAtMs += (nowMs - pauseAnchor).coerceAtLeast(0L)
                    }
                    isBackgrounded = false
                    backgroundedAtMs = -1L
                }
                WebContainerWatchdogAction.None
            }

            is WebContainerWatchdogEvent.LoadStarted -> {
                activeGeneration = event.generation
                loadStartedAtMs = nowMs
                finishedAtMs = -1L
                probeRequestedForGen = -1
                phase = WebContainerPhase.Loading
                WebContainerWatchdogAction.None
            }

            is WebContainerWatchdogEvent.PageCommitted -> {
                if (event.generation != activeGeneration) return WebContainerWatchdogAction.None
                hasPreviouslyCommitted = true
                if (phase == WebContainerPhase.Loading || phase == WebContainerPhase.Recovering) {
                    phase = WebContainerPhase.Committed
                }
                WebContainerWatchdogAction.None
            }

            is WebContainerWatchdogEvent.PageFinished -> {
                if (event.generation != activeGeneration) return WebContainerWatchdogAction.None
                finishedAtMs = nowMs
                phase = WebContainerPhase.Committed
                if (policy.blankProbeDelayMs == 0L && !isBackgrounded) {
                    probeRequestedForGen = event.generation
                    WebContainerWatchdogAction.RequestDomProbe(event.generation)
                } else {
                    WebContainerWatchdogAction.None
                }
            }

            is WebContainerWatchdogEvent.DomProbeResult -> {
                if (event.generation != activeGeneration) return WebContainerWatchdogAction.None
                if (event.hasVisibleContent != false) {
                    // `true` (content present) or `null` (scripts disabled / probe unavailable -> fail open).
                    phase = WebContainerPhase.Ready
                    WebContainerWatchdogAction.None
                } else {
                    phase = WebContainerPhase.BlankSuspected
                    WebContainerWatchdogAction.EmitTerminalError("blank_screen_detected")
                }
            }

            WebContainerWatchdogEvent.Tick -> {
                if (isBackgrounded) return WebContainerWatchdogAction.None
                when (phase) {
                    WebContainerPhase.Loading -> {
                        if (loadStartedAtMs >= 0L && nowMs - loadStartedAtMs >= policy.stallTimeoutMs) {
                            phase = WebContainerPhase.Failed
                            WebContainerWatchdogAction.EmitTerminalError("load_stall_timeout")
                        } else {
                            WebContainerWatchdogAction.None
                        }
                    }

                    WebContainerPhase.Committed -> {
                        if (
                            finishedAtMs >= 0L &&
                            probeRequestedForGen != activeGeneration &&
                            nowMs - finishedAtMs >= policy.blankProbeDelayMs
                        ) {
                            probeRequestedForGen = activeGeneration
                            WebContainerWatchdogAction.RequestDomProbe(activeGeneration)
                        } else {
                            WebContainerWatchdogAction.None
                        }
                    }

                    else -> WebContainerWatchdogAction.None
                }
            }

            is WebContainerWatchdogEvent.LoadFailed -> {
                if (event.generation != activeGeneration) return WebContainerWatchdogAction.None
                phase = WebContainerPhase.Failed
                WebContainerWatchdogAction.EmitTerminalError(event.reason)
            }

            is WebContainerWatchdogEvent.LoadCancelled -> {
                if (event.generation != activeGeneration) return WebContainerWatchdogAction.None
                if (phase == WebContainerPhase.Loading) {
                    loadStartedAtMs = -1L
                    phase = if (hasPreviouslyCommitted) WebContainerPhase.Ready else WebContainerPhase.Idle
                }
                WebContainerWatchdogAction.None
            }

            is WebContainerWatchdogEvent.RenderProcessGone -> {
                pruneCrashWindow(nowMs)
                val wasUsable = phase == WebContainerPhase.Ready || phase == WebContainerPhase.Committed
                if (!wasUsable) {
                    phase = WebContainerPhase.CrashedTerminal
                    val reason = if (event.didCrash) "render_process_crashed_during_load" else "render_process_killed_during_load"
                    return WebContainerWatchdogAction.EmitTerminalError(reason)
                }
                if (recentRecoveryTimestampsMs.size >= policy.maxRecoveriesInWindow) {
                    phase = WebContainerPhase.CrashedTerminal
                    return WebContainerWatchdogAction.EmitTerminalError("render_process_circuit_open")
                }
                recentRecoveryTimestampsMs.addLast(nowMs)
                phase = WebContainerPhase.Recovering
                WebContainerWatchdogAction.TriggerRecoveryReload(recoveryAttempt = recentRecoveryTimestampsMs.size)
            }
        }
    }

    private fun pruneCrashWindow(nowMs: Long) {
        val cutoff = nowMs - policy.crashWindowMs
        while (recentRecoveryTimestampsMs.isNotEmpty() && recentRecoveryTimestampsMs.first() < cutoff) {
            recentRecoveryTimestampsMs.removeFirst()
        }
    }
}

/**
 * Re-entrancy-safe, deduplicating relay for [WebNavigationState] updates.
 * Prevents recursive callback ping-pong and drops identical consecutive snapshots unless [force] is true.
 */
class WebNavigationStateRelay(
    private val maxReentrySteps: Int = 8,
) {
    private var lastDelivered: WebNavigationState? = null
    private var delivering: Boolean = false
    private var pendingForce: Boolean = false
    private var pendingNonForced: Boolean = false

    fun reset() {
        lastDelivered = null
        pendingForce = false
        pendingNonForced = false
    }

    fun notify(
        force: Boolean = false,
        isActive: () -> Boolean,
        readState: () -> WebNavigationState,
        deliver: (WebNavigationState) -> Unit,
    ) {
        if (!isActive()) return
        if (delivering) {
            if (force) pendingForce = true else pendingNonForced = true
            return
        }
        delivering = true
        try {
            var steps = 0
            var nextForce = force
            while (isActive() && steps < maxReentrySteps) {
                pendingForce = false
                pendingNonForced = false
                val current = readState()
                val changed = nextForce || current != lastDelivered
                if (changed) {
                    lastDelivered = current
                    deliver(current)
                }
                steps += 1
                if (!pendingForce && !pendingNonForced) break
                nextForce = pendingForce
            }
        } finally {
            delivering = false
            pendingForce = false
            pendingNonForced = false
        }
    }
}
