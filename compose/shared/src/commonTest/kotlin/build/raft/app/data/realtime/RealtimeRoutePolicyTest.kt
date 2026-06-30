package build.raft.app.data.realtime

import build.raft.app.model.AgentStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.Heartbeat
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealtimeRoutePolicyTest {
    @Test
    fun activeThreadDoesNotReceivePresenceEvents() {
        val event = agentPresence()

        assertFalse(RealtimeRoutePolicy.shouldForwardToActiveThread(event))
        assertFalse(RealtimeRoutePolicy.shouldEmitRouteMarker(event, activeThread = true))
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
