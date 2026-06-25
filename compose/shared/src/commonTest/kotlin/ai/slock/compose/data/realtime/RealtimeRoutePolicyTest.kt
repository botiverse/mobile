package ai.slock.compose.data.realtime

import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.Presence
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealtimeRoutePolicyTest {
    @Test
    fun activeThreadReceivesPresenceEvents() {
        val event = agentPresence()

        assertTrue(RealtimeRoutePolicy.shouldForwardToActiveThread(event))
        assertTrue(RealtimeRoutePolicy.shouldEmitRouteMarker(event, activeThread = true))
        assertTrue(RealtimeRoutePolicy.shouldEmitRouteMarker(event, activeThread = false))
    }

    @Test
    fun activeThreadStillReceivesNonPresenceEvents() {
        val event = DirectMessageCreated(ChannelId("dm-1"))

        assertTrue(RealtimeRoutePolicy.shouldForwardToActiveThread(event))
        assertTrue(RealtimeRoutePolicy.shouldEmitRouteMarker(event, activeThread = true))
    }

    @Test
    fun heartbeatDoesNotForwardOrEmitRouteMarker() {
        val event = Heartbeat(seq = 10L)

        assertFalse(RealtimeRoutePolicy.shouldForwardToActiveThread(event))
        assertFalse(RealtimeRoutePolicy.shouldEmitRouteMarker(event, activeThread = true))
        assertFalse(RealtimeRoutePolicy.shouldEmitRouteMarker(event, activeThread = false))
    }

    private fun agentPresence(): MemberPresenceUpdated =
        MemberPresenceUpdated(
            memberId = MemberId("agent-1"),
            presence = Presence.Active,
            kind = MemberKind.Agent,
            agentStatus = AgentStatus.Working,
            statusLabel = "Running tests",
            serverSeq = 7L
        )
}
