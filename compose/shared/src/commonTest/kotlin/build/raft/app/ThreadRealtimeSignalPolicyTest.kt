package build.raft.app

import build.raft.app.model.Heartbeat
import build.raft.app.thread.shouldDeliverToActiveThreadRealtimeSignal
import kotlin.test.Test
import kotlin.test.assertTrue

class ThreadRealtimeSignalPolicyTest {
    @Test
    fun heartbeatIsDeliveredToActiveThreadForGapRecovery() {
        assertTrue(shouldDeliverToActiveThreadRealtimeSignal(Heartbeat(seq = 42)))
    }
}
