package build.raft.app.agents

import build.raft.app.RaftFloatingPillEdge
import build.raft.app.RaftFloatingPillHorizontalRange
import build.raft.app.clampRaftFloatingPillOffsetXPx
import build.raft.app.clampRaftFloatingPillYFraction
import build.raft.app.data.AgentVoiceCallPhase
import build.raft.app.data.AgentVoiceCallState
import build.raft.app.resolveRaftFloatingPillDockedOffsetXPx
import build.raft.app.resolveRaftFloatingPillHorizontalRange
import build.raft.app.resolveRaftFloatingPillOffsetYPx
import build.raft.app.resolveRaftFloatingPillSnapEdge
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default vertical dock fraction for the collapsed Agent Voice call edge pill. Placed in the
 * upper-right band (`0.28f`) so it stays well clear of the App/Artifact floating pill (`0.52f` /
 * `0.62f`) when both floating surfaces are active at the same time.
 */
internal const val AGENT_VOICE_CALL_PILL_DEFAULT_Y_FRACTION: Float = 0.28f

/** Default physical screen edge for the collapsed Agent Voice call edge pill. */
internal val AgentVoiceCallPillDefaultEdge: RaftFloatingPillEdge = RaftFloatingPillEdge.Right

/** Presentation mode of the floating Agent Voice call overlay. */
internal enum class AgentVoiceCallPresentationMode {
    Card,
    EdgePill,
}

/**
 * Immutable floating presentation state for the Agent Voice call overlay:
 * - [mode] tracks whether an active call is expanded ([AgentVoiceCallPresentationMode.Card]) or
 *   collapsed into a magnetic edge-docked pill ([AgentVoiceCallPresentationMode.EdgePill]).
 * - [pillEdge] and [pillYFraction] persist the user's bilateral Left/Right edge dock preference.
 */
internal data class AgentVoiceCallFloatingState(
    val sessionKey: String? = null,
    val callId: String? = null,
    val lastFocusNonce: Long = 0L,
    val mode: AgentVoiceCallPresentationMode = AgentVoiceCallPresentationMode.Card,
    val pillEdge: RaftFloatingPillEdge = AgentVoiceCallPillDefaultEdge,
    val pillYFraction: Float = AGENT_VOICE_CALL_PILL_DEFAULT_Y_FRACTION,
)

/** Container, pill, safe-area, and IME geometry (in physical pixels) for the voice call edge pill. */
internal data class AgentVoiceCallPillBounds(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val pillWidthPx: Float,
    val pillHeightPx: Float,
    val leftSafeAreaPx: Float = 0f,
    val rightSafeAreaPx: Float = 0f,
    val topSafeAreaPx: Float = 0f,
    val bottomSafeAreaPx: Float = 0f,
    val imeBottomPx: Float = 0f,
    val topGapPx: Float = 0f,
    val bottomGapPx: Float = 0f,
)

private fun Float.finiteNonNegativeOrZero(): Float = if (isFinite()) coerceAtLeast(0f) else 0f

/** Stable per-agent/per-server key used to scope floating position and presentation state. */
internal fun agentVoiceCallSessionKey(call: AgentVoiceCallState): String =
    "${call.serverId}:${call.agent.id}"

/**
 * Only active calls (`Starting`, `Connecting`, `Live`, `Reconnecting`) may collapse into an edge
 * pill. `Ended` (post-call rating / dismissal) and `Failed` (error + retry) always stay expanded.
 */
internal fun canCollapseAgentVoiceCallToPill(phase: AgentVoiceCallPhase): Boolean =
    phase != AgentVoiceCallPhase.Ended && phase != AgentVoiceCallPhase.Failed

/** Convenience overload of [canCollapseAgentVoiceCallToPill] for [AgentVoiceCallState]. */
internal fun canCollapseAgentVoiceCallToPill(call: AgentVoiceCallState): Boolean =
    canCollapseAgentVoiceCallToPill(call.phase)

/** Clamps [yFraction] into `[0f, 1f]`, falling back cleanly for non-finite inputs. */
internal fun normalizeAgentVoiceCallPillYFraction(
    yFraction: Float,
    fallback: Float = AGENT_VOICE_CALL_PILL_DEFAULT_Y_FRACTION,
): Float {
    val safeFallback = if (fallback.isFinite()) fallback.coerceIn(0f, 1f) else AGENT_VOICE_CALL_PILL_DEFAULT_Y_FRACTION
    return if (yFraction.isFinite()) yFraction.coerceIn(0f, 1f) else safeFallback
}

/**
 * Reconciles [current] floating presentation state against the live [call] snapshot:
 * - Idle (`call == null`) resets presentation mode to [AgentVoiceCallPresentationMode.Card] and
 *   clears session tracking while preserving the user's preferred pill dock edge and vertical slot.
 * - Starting a new call (different server/agent session or different server-minted `callId`),
 *   receiving a focus bump (`focusNonce` incremented while active), or transitioning into `Ended`
 *   / `Failed` automatically expands back to [AgentVoiceCallPresentationMode.Card].
 * - Minting the initial `callId` during `Starting -> Connecting` for the same session adopts the
 *   new `callId` without forcibly expanding a user-collapsed pill.
 */
internal fun reconcileAgentVoiceCallFloatingState(
    current: AgentVoiceCallFloatingState,
    call: AgentVoiceCallState?,
): AgentVoiceCallFloatingState {
    val safeFraction = normalizeAgentVoiceCallPillYFraction(current.pillYFraction)
    if (call == null) {
        return current.copy(
            sessionKey = null,
            callId = null,
            lastFocusNonce = 0L,
            mode = AgentVoiceCallPresentationMode.Card,
            pillYFraction = safeFraction,
        )
    }
    val nextSessionKey = agentVoiceCallSessionKey(call)
    val nextCallId = call.callId?.trim()?.takeIf { it.isNotEmpty() }
    val currentCallId = current.callId?.trim()?.takeIf { it.isNotEmpty() }
    val isNewCall =
        current.sessionKey == null ||
            current.sessionKey != nextSessionKey ||
            (currentCallId != null && nextCallId != currentCallId)
    val focusRequested = !isNewCall && call.focusNonce != current.lastFocusNonce
    val mustExpand = isNewCall || focusRequested || !canCollapseAgentVoiceCallToPill(call.phase)
    val nextMode =
        if (mustExpand) {
            AgentVoiceCallPresentationMode.Card
        } else {
            current.mode
        }
    val resolvedCallId = if (isNewCall) nextCallId else (nextCallId ?: currentCallId)
    return current.copy(
        sessionKey = nextSessionKey,
        callId = resolvedCallId,
        lastFocusNonce = call.focusNonce,
        mode = nextMode,
        pillYFraction = safeFraction,
    )
}

/** Top safe-area + gap vertical boundary (in px) for the voice call edge pill. */
internal fun agentVoiceCallPillTopBoundPx(bounds: AgentVoiceCallPillBounds): Float =
    bounds.topSafeAreaPx.finiteNonNegativeOrZero() + bounds.topGapPx.finiteNonNegativeOrZero()

/** Bottom safe-area/IME + gap vertical boundary (in px) for the voice call edge pill. */
internal fun agentVoiceCallPillBottomBoundPx(bounds: AgentVoiceCallPillBounds): Float =
    maxOf(bounds.bottomSafeAreaPx.finiteNonNegativeOrZero(), bounds.imeBottomPx.finiteNonNegativeOrZero()) +
        bounds.bottomGapPx.finiteNonNegativeOrZero()

/** Clamped horizontal drag/dock range (in px) for the voice call edge pill. */
internal fun agentVoiceCallPillHorizontalRange(bounds: AgentVoiceCallPillBounds): RaftFloatingPillHorizontalRange =
    resolveRaftFloatingPillHorizontalRange(
        viewportWidthPx = bounds.viewportWidthPx,
        pillWidthPx = bounds.pillWidthPx,
        leftSafeAreaPx = bounds.leftSafeAreaPx,
        rightSafeAreaPx = bounds.rightSafeAreaPx,
    )

/** Docked physical left offset (in px) flush to [edge] inside horizontal safe-area bounds. */
internal fun resolveAgentVoiceCallPillDockedOffsetXPx(
    edge: RaftFloatingPillEdge,
    bounds: AgentVoiceCallPillBounds,
): Float =
    resolveRaftFloatingPillDockedOffsetXPx(
        edge = edge,
        viewportWidthPx = bounds.viewportWidthPx,
        pillWidthPx = bounds.pillWidthPx,
        leftSafeAreaPx = bounds.leftSafeAreaPx,
        rightSafeAreaPx = bounds.rightSafeAreaPx,
    )

/** Clamps a dragged physical left offset [offsetXPx] within the safe horizontal pill range. */
internal fun clampAgentVoiceCallPillOffsetXPx(
    offsetXPx: Float,
    bounds: AgentVoiceCallPillBounds,
    fallbackEdge: RaftFloatingPillEdge = AgentVoiceCallPillDefaultEdge,
): Float =
    clampRaftFloatingPillOffsetXPx(
        offsetXPx = offsetXPx,
        viewportWidthPx = bounds.viewportWidthPx,
        pillWidthPx = bounds.pillWidthPx,
        leftSafeAreaPx = bounds.leftSafeAreaPx,
        rightSafeAreaPx = bounds.rightSafeAreaPx,
        fallbackEdge = fallbackEdge,
    )

/** Resolves the closer Left or Right dock edge when releasing the voice call pill at [offsetXPx]. */
internal fun resolveAgentVoiceCallPillSnapEdge(
    offsetXPx: Float,
    bounds: AgentVoiceCallPillBounds,
    currentEdge: RaftFloatingPillEdge = AgentVoiceCallPillDefaultEdge,
): RaftFloatingPillEdge =
    resolveRaftFloatingPillSnapEdge(
        offsetXPx = offsetXPx,
        viewportWidthPx = bounds.viewportWidthPx,
        pillWidthPx = bounds.pillWidthPx,
        leftSafeAreaPx = bounds.leftSafeAreaPx,
        rightSafeAreaPx = bounds.rightSafeAreaPx,
        currentEdge = currentEdge,
    )

/** Resolves the clamped vertical top offset (in px) for the voice call edge pill. */
internal fun resolveAgentVoiceCallPillOffsetYPx(
    bounds: AgentVoiceCallPillBounds,
    yFraction: Float,
): Float =
    resolveRaftFloatingPillOffsetYPx(
        viewportHeightPx = bounds.viewportHeightPx,
        pillHeightPx = bounds.pillHeightPx,
        topBoundPx = agentVoiceCallPillTopBoundPx(bounds),
        bottomBoundPx = agentVoiceCallPillBottomBoundPx(bounds),
        yFraction = normalizeAgentVoiceCallPillYFraction(yFraction),
    )

/** Converts a dragged vertical offset [offsetYPx] into a clamped `[0f, 1f]` voice call pill fraction. */
internal fun clampAgentVoiceCallPillYFraction(
    offsetYPx: Float,
    bounds: AgentVoiceCallPillBounds,
    fallbackYFraction: Float = AGENT_VOICE_CALL_PILL_DEFAULT_Y_FRACTION,
): Float {
    val safeFallback = normalizeAgentVoiceCallPillYFraction(fallbackYFraction)
    val safeTop = agentVoiceCallPillTopBoundPx(bounds)
    val safeBottom = agentVoiceCallPillBottomBoundPx(bounds)
    val safeViewport = bounds.viewportHeightPx.finiteNonNegativeOrZero()
    val safePillHeight = bounds.pillHeightPx.finiteNonNegativeOrZero()
    val safeMax = (safeViewport - safeBottom - safePillHeight).coerceAtLeast(safeTop)
    val span = safeMax - safeTop
    if (!offsetYPx.isFinite() || span <= 1f) return safeFallback
    return clampRaftFloatingPillYFraction(
        offsetYPx = offsetYPx,
        viewportHeightPx = safeViewport,
        pillHeightPx = safePillHeight,
        topBoundPx = safeTop,
        bottomBoundPx = safeBottom,
    )
}

/**
 * Session-scoped holder for Agent Voice call floating presentation mode (Card ↔ bilateral
 * edge-docked pill) and pill dock coordinates. Backed by [MutableStateFlow] rather than top-level
 * Compose snapshot state so navigation between the app shell and standalone pushed pages preserves
 * collapse state cleanly without snapshot initialization hazards.
 */
internal class AgentVoiceCallFloatingStateHolder(
    initialState: AgentVoiceCallFloatingState = AgentVoiceCallFloatingState(),
) {
    private val lock = SynchronizedObject()
    private val mutableState =
        MutableStateFlow(
            initialState.copy(
                pillYFraction = normalizeAgentVoiceCallPillYFraction(initialState.pillYFraction),
            ),
        )

    val state: StateFlow<AgentVoiceCallFloatingState> = mutableState.asStateFlow()

    fun syncWithCall(call: AgentVoiceCallState?) {
        synchronized(lock) {
            val next = reconcileAgentVoiceCallFloatingState(mutableState.value, call)
            if (next != mutableState.value) {
                mutableState.value = next
            }
        }
    }

    fun collapseToPill(call: AgentVoiceCallState) {
        synchronized(lock) {
            val synced = reconcileAgentVoiceCallFloatingState(mutableState.value, call)
            mutableState.value =
                if (canCollapseAgentVoiceCallToPill(call.phase)) {
                    synced.copy(mode = AgentVoiceCallPresentationMode.EdgePill)
                } else {
                    synced
                }
        }
    }

    fun expandToCard() {
        synchronized(lock) {
            val current = mutableState.value
            if (current.mode != AgentVoiceCallPresentationMode.Card) {
                mutableState.value = current.copy(mode = AgentVoiceCallPresentationMode.Card)
            }
        }
    }

    fun setPillDockPosition(
        edge: RaftFloatingPillEdge,
        yFraction: Float,
    ) {
        synchronized(lock) {
            val current = mutableState.value
            val normalized = normalizeAgentVoiceCallPillYFraction(yFraction, fallback = current.pillYFraction)
            val next = current.copy(pillEdge = edge, pillYFraction = normalized)
            if (next != current) {
                mutableState.value = next
            }
        }
    }

    fun setPillEdge(edge: RaftFloatingPillEdge) {
        synchronized(lock) {
            val current = mutableState.value
            if (current.pillEdge != edge) {
                mutableState.value = current.copy(pillEdge = edge)
            }
        }
    }

    fun setPillYFraction(yFraction: Float) {
        synchronized(lock) {
            val current = mutableState.value
            val normalized = normalizeAgentVoiceCallPillYFraction(yFraction, fallback = current.pillYFraction)
            if (current.pillYFraction != normalized) {
                mutableState.value = current.copy(pillYFraction = normalized)
            }
        }
    }

    fun clearSession() {
        syncWithCall(null)
    }
}

internal val DefaultAgentVoiceCallFloatingStateHolder = AgentVoiceCallFloatingStateHolder()
