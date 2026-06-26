package ai.slock.compose

import ai.slock.compose.model.Heartbeat
import kotlin.test.Test
import kotlin.test.assertTrue

class ThreadRealtimeSignalPolicyTest {
    @Test
    fun heartbeatIsDeliveredToActiveThreadForGapRecovery() {
        assertTrue(shouldDeliverToActiveThreadRealtimeSignal(Heartbeat(seq = 42)))
    }
}
