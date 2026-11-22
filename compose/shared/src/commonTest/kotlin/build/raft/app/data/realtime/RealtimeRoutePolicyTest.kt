package build.raft.app.data.realtime

import build.raft.app.model.ChannelId
import build.raft.app.model.DirectMessageCreated
import kotlin.test.Test
import kotlin.test.assertTrue

class RealtimeRoutePolicyTest {
    @Test
    fun activeThreadStillReceivesNonPresenceEvents() {
        val event = DirectMessageCreated(ChannelId("dm-1"))

        assertTrue(RealtimeRoutePolicy.shouldForwardToActiveThread(event))
        assertTrue(RealtimeRoutePolicy.shouldEmitRouteMarker(event, activeThread = true))
    }
}
