package build.raft.app.agents

import build.raft.app.RaftFloatingPillEdge
import build.raft.app.data.AgentVoiceCallEndReason
import build.raft.app.data.AgentVoiceCallPhase
import build.raft.app.data.AgentVoiceCallProblem
import build.raft.app.data.AgentVoiceCallState
import build.raft.app.data.AgentVoiceCallee
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentVoiceCallFloatingStateTest {
    private val lagoon = AgentVoiceCallee(id = "agent-lagoon", name = "Lagoon")
    private val coral = AgentVoiceCallee(id = "agent-coral", name = "Coral")

    private fun assertNear(
        expected: Float,
        actual: Float,
        tolerance: Float = 0.01f,
        message: String? = null,
    ) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            message ?: "Expected $expected ± $tolerance but was $actual",
        )
    }

    private fun liveCall(
        agent: AgentVoiceCallee = lagoon,
        serverId: String = "srv-1",
        callId: String? = "call-1",
        phase: AgentVoiceCallPhase = AgentVoiceCallPhase.Live,
        focusNonce: Long = 0L,
    ): AgentVoiceCallState =
        AgentVoiceCallState(
            agent = agent,
            serverId = serverId,
            callId = callId,
            phase = phase,
            connectedAtEpochMs = if (phase == AgentVoiceCallPhase.Live || phase == AgentVoiceCallPhase.Ended) 1_000_000L else null,
            focusNonce = focusNonce,
        )

    @Test
    fun collapseEligibilityAllowsActivePhasesAndBlocksEndedOrFailed() {
        assertTrue(canCollapseAgentVoiceCallToPill(AgentVoiceCallPhase.Starting))
        assertTrue(canCollapseAgentVoiceCallToPill(AgentVoiceCallPhase.Connecting))
        assertTrue(canCollapseAgentVoiceCallToPill(AgentVoiceCallPhase.Live))
        assertTrue(canCollapseAgentVoiceCallToPill(AgentVoiceCallPhase.Reconnecting))
        assertFalse(canCollapseAgentVoiceCallToPill(AgentVoiceCallPhase.Ended))
        assertFalse(canCollapseAgentVoiceCallToPill(AgentVoiceCallPhase.Failed))
    }

    @Test
    fun stateHolderCollapsesExpandsAndPreservesEdgeDockAcrossSameCallLifecycle() {
        val holder = AgentVoiceCallFloatingStateHolder()
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.Card, holder.state.value.mode)
        assertEquals<RaftFloatingPillEdge>(RaftFloatingPillEdge.Right, holder.state.value.pillEdge)
        assertNear(AGENT_VOICE_CALL_PILL_DEFAULT_Y_FRACTION, holder.state.value.pillYFraction)

        // Starting call with null callId can collapse and stays collapsed when callId is minted on Connecting -> Live -> Reconnecting.
        val starting = liveCall(callId = null, phase = AgentVoiceCallPhase.Starting)
        holder.syncWithCall(starting)
        holder.collapseToPill(starting)
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.EdgePill, holder.state.value.mode)

        val connecting = starting.copy(callId = "call-1", phase = AgentVoiceCallPhase.Connecting)
        holder.syncWithCall(connecting)
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.EdgePill, holder.state.value.mode)
        assertEquals<String?>("call-1", holder.state.value.callId)

        val live = connecting.copy(phase = AgentVoiceCallPhase.Live, connectedAtEpochMs = 1_000_000L)
        holder.syncWithCall(live)
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.EdgePill, holder.state.value.mode)

        val reconnecting = live.copy(phase = AgentVoiceCallPhase.Reconnecting)
        holder.syncWithCall(reconnecting)
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.EdgePill, holder.state.value.mode)

        // User drags pill to Left edge at 0.72f, then expands back to Card and collapses again -> remembers Left / 0.72f.
        holder.setPillDockPosition(RaftFloatingPillEdge.Left, 0.72f)
        assertEquals<RaftFloatingPillEdge>(RaftFloatingPillEdge.Left, holder.state.value.pillEdge)
        assertNear(0.72f, holder.state.value.pillYFraction)

        holder.expandToCard()
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.Card, holder.state.value.mode)
        assertEquals<RaftFloatingPillEdge>(RaftFloatingPillEdge.Left, holder.state.value.pillEdge)
        assertNear(0.72f, holder.state.value.pillYFraction)

        holder.collapseToPill(live)
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.EdgePill, holder.state.value.mode)
        assertEquals<RaftFloatingPillEdge>(RaftFloatingPillEdge.Left, holder.state.value.pillEdge)
        assertNear(0.72f, holder.state.value.pillYFraction)
    }

    @Test
    fun autoExpandsToCardOnEndedFailedNewCallFocusNonceAndIdleClear() {
        val holder = AgentVoiceCallFloatingStateHolder()
        val call1 = liveCall(callId = "call-1", phase = AgentVoiceCallPhase.Live)
        holder.collapseToPill(call1)
        holder.setPillDockPosition(RaftFloatingPillEdge.Left, 0.35f)
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.EdgePill, holder.state.value.mode)

        // Transitioning to Ended auto-expands so post-call 1-tap feedback rating is visible immediately.
        val ended = call1.copy(phase = AgentVoiceCallPhase.Ended, durationMs = 45_000L, endReason = AgentVoiceCallEndReason.Left)
        holder.syncWithCall(ended)
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.Card, holder.state.value.mode)
        // Attempting to collapse an Ended call is ignored.
        holder.collapseToPill(ended)
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.Card, holder.state.value.mode)

        // Failed call also auto-expands and refuses collapse.
        holder.collapseToPill(call1)
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.EdgePill, holder.state.value.mode)
        val failed = call1.copy(phase = AgentVoiceCallPhase.Failed, problem = AgentVoiceCallProblem.AgentOffline)
        holder.syncWithCall(failed)
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.Card, holder.state.value.mode)
        holder.collapseToPill(failed)
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.Card, holder.state.value.mode)

        // Focus bump (user taps Call again while collapsed) auto-expands to Card.
        holder.collapseToPill(call1)
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.EdgePill, holder.state.value.mode)
        holder.syncWithCall(call1.copy(focusNonce = 1L))
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.Card, holder.state.value.mode)

        // New callId on same agent auto-expands to Card.
        holder.collapseToPill(call1.copy(focusNonce = 1L))
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.EdgePill, holder.state.value.mode)
        holder.syncWithCall(liveCall(callId = "call-2", phase = AgentVoiceCallPhase.Live))
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.Card, holder.state.value.mode)
        assertEquals<String?>("call-2", holder.state.value.callId)

        // Switching to a different agent or server auto-expands to Card.
        holder.collapseToPill(liveCall(callId = "call-2", phase = AgentVoiceCallPhase.Live))
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.EdgePill, holder.state.value.mode)
        holder.syncWithCall(liveCall(agent = coral, callId = "call-2", phase = AgentVoiceCallPhase.Live))
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.Card, holder.state.value.mode)

        // Clearing when call goes idle resets mode + session tracking while preserving user's pill dock preference.
        val coralCall = liveCall(agent = coral, callId = "call-3", phase = AgentVoiceCallPhase.Live)
        holder.collapseToPill(coralCall)
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.EdgePill, holder.state.value.mode)
        holder.clearSession()
        assertEquals<AgentVoiceCallPresentationMode>(AgentVoiceCallPresentationMode.Card, holder.state.value.mode)
        assertNull(holder.state.value.sessionKey)
        assertNull(holder.state.value.callId)
        assertEquals<RaftFloatingPillEdge>(RaftFloatingPillEdge.Left, holder.state.value.pillEdge)
        assertNear(0.35f, holder.state.value.pillYFraction)
    }

    @Test
    fun pillBilateralSnapHorizontalSafeAreaAndImeVerticalClampingWork() {
        val portraitBounds =
            AgentVoiceCallPillBounds(
                viewportWidthPx = 390f,
                viewportHeightPx = 844f,
                pillWidthPx = 88f,
                pillHeightPx = 48f,
                leftSafeAreaPx = 0f,
                rightSafeAreaPx = 0f,
                topSafeAreaPx = 44f,
                bottomSafeAreaPx = 34f,
                imeBottomPx = 0f,
                topGapPx = 12f,
                bottomGapPx = 16f,
            )

        // Bilateral horizontal range: [0, 302], midpoint = 151.
        val range = agentVoiceCallPillHorizontalRange(portraitBounds)
        assertNear(0f, range.minX)
        assertNear(302f, range.maxX)
        assertNear(0f, resolveAgentVoiceCallPillDockedOffsetXPx(RaftFloatingPillEdge.Left, portraitBounds))
        assertNear(302f, resolveAgentVoiceCallPillDockedOffsetXPx(RaftFloatingPillEdge.Right, portraitBounds))
        assertEquals<RaftFloatingPillEdge>(
            RaftFloatingPillEdge.Left,
            resolveAgentVoiceCallPillSnapEdge(150f, portraitBounds, currentEdge = RaftFloatingPillEdge.Right),
        )
        assertEquals<RaftFloatingPillEdge>(
            RaftFloatingPillEdge.Right,
            resolveAgentVoiceCallPillSnapEdge(152f, portraitBounds, currentEdge = RaftFloatingPillEdge.Left),
        )
        assertEquals<RaftFloatingPillEdge>(
            RaftFloatingPillEdge.Right,
            resolveAgentVoiceCallPillSnapEdge(151f, portraitBounds, currentEdge = RaftFloatingPillEdge.Right),
        )
        assertEquals<RaftFloatingPillEdge>(
            RaftFloatingPillEdge.Left,
            resolveAgentVoiceCallPillSnapEdge(151f, portraitBounds, currentEdge = RaftFloatingPillEdge.Left),
        )

        // Landscape horizontal cutouts shift dock edges cleanly inside safe areas.
        val landscapeBounds =
            portraitBounds.copy(
                viewportWidthPx = 844f,
                viewportHeightPx = 390f,
                leftSafeAreaPx = 44f,
                rightSafeAreaPx = 44f,
                topSafeAreaPx = 0f,
                bottomSafeAreaPx = 21f,
            )
        assertNear(44f, resolveAgentVoiceCallPillDockedOffsetXPx(RaftFloatingPillEdge.Left, landscapeBounds))
        assertNear(712f, resolveAgentVoiceCallPillDockedOffsetXPx(RaftFloatingPillEdge.Right, landscapeBounds))
        assertNear(44f, clampAgentVoiceCallPillOffsetXPx(-100f, landscapeBounds))
        assertNear(712f, clampAgentVoiceCallPillOffsetXPx(999f, landscapeBounds))

        // Vertical bounds & IME clamping:
        // topBound = 56, bottomBound = 50, maxY = 844 - 50 - 48 = 746, span = 690.
        val defaultY = resolveAgentVoiceCallPillOffsetYPx(portraitBounds, AGENT_VOICE_CALL_PILL_DEFAULT_Y_FRACTION)
        assertNear(56f + 690f * AGENT_VOICE_CALL_PILL_DEFAULT_Y_FRACTION, defaultY)
        assertNear(
            AGENT_VOICE_CALL_PILL_DEFAULT_Y_FRACTION,
            clampAgentVoiceCallPillYFraction(defaultY, portraitBounds),
        )
        assertNear(56f, resolveAgentVoiceCallPillOffsetYPx(portraitBounds, -0.5f))
        assertNear(746f, resolveAgentVoiceCallPillOffsetYPx(portraitBounds, 1.5f))

        // Opening a 320px keyboard lifts a bottom-docked pill cleanly above the keyboard.
        val imeBounds = portraitBounds.copy(imeBottomPx = 320f)
        val bottomWithIme = resolveAgentVoiceCallPillOffsetYPx(imeBounds, 1f)
        // maxY = 844 - (320 + 16) - 48 = 460
        assertNear(460f, bottomWithIme)
    }

    @Test
    fun nonFiniteInputsAndCompressedSplitWindowFallBackCleanly() {
        val bounds =
            AgentVoiceCallPillBounds(
                viewportWidthPx = 390f,
                viewportHeightPx = 844f,
                pillWidthPx = 88f,
                pillHeightPx = 48f,
                topSafeAreaPx = 44f,
                bottomSafeAreaPx = 34f,
                topGapPx = 12f,
                bottomGapPx = 16f,
            )

        assertNear(
            AGENT_VOICE_CALL_PILL_DEFAULT_Y_FRACTION,
            normalizeAgentVoiceCallPillYFraction(Float.NaN),
        )
        assertNear(
            0.42f,
            normalizeAgentVoiceCallPillYFraction(Float.POSITIVE_INFINITY, fallback = 0.42f),
        )
        assertNear(
            302f,
            clampAgentVoiceCallPillOffsetXPx(Float.NaN, bounds, fallbackEdge = RaftFloatingPillEdge.Right),
        )
        assertNear(
            0f,
            clampAgentVoiceCallPillOffsetXPx(Float.NEGATIVE_INFINITY, bounds, fallbackEdge = RaftFloatingPillEdge.Left),
        )
        assertEquals<RaftFloatingPillEdge>(
            RaftFloatingPillEdge.Left,
            resolveAgentVoiceCallPillSnapEdge(Float.NaN, bounds, currentEdge = RaftFloatingPillEdge.Left),
        )
        assertNear(
            0.35f,
            clampAgentVoiceCallPillYFraction(Float.NaN, bounds, fallbackYFraction = 0.35f),
        )

        // Compressed split-screen where vertical span <= 1px preserves caller fallback fraction rather than resetting to 0.52f.
        val compressed = bounds.copy(viewportHeightPx = 140f, imeBottomPx = 120f)
        assertNear(
            0.28f,
            clampAgentVoiceCallPillYFraction(60f, compressed, fallbackYFraction = AGENT_VOICE_CALL_PILL_DEFAULT_Y_FRACTION),
        )

        // State holder ignores non-finite Y writes by preserving its previous finite fraction.
        val holder = AgentVoiceCallFloatingStateHolder()
        holder.setPillDockPosition(RaftFloatingPillEdge.Left, 0.4f)
        holder.setPillYFraction(Float.NaN)
        assertNear(0.4f, holder.state.value.pillYFraction)
        holder.setPillDockPosition(RaftFloatingPillEdge.Right, 1.8f)
        assertEquals<RaftFloatingPillEdge>(RaftFloatingPillEdge.Right, holder.state.value.pillEdge)
        assertNear(1f, holder.state.value.pillYFraction)
    }
}
