package build.raft.app.data.realtime

import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class RealtimeEventMapperLocalEventsTest {
    @Test
    fun catchUpRequiredRefreshesHomeScopeOfItsServer() {
        val event =
            RealtimeEventMapper().map(
                RealtimeLocalEvents.CatchUpRequired,
                """{"serverId":"server-1","reason":"transport_reconnect_resume_unavailable"}"""
            )

        val refresh = assertIs<RealtimeRefreshRequired>(event)
        assertEquals(RealtimeRefreshScope.Home, refresh.scope)
        assertEquals(ServerId("server-1"), refresh.serverId)
        assertEquals("transport_reconnect_resume_unavailable", refresh.reason)
    }

    @Test
    fun catchUpRequiredIsNeverSubscribedOnTheSocket() {
        assertFalse(RealtimeLocalEvents.CatchUpRequired in RealtimeSocketEvents.ServerWireEvents)
    }
}
