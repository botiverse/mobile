package build.raft.app.data

import build.raft.app.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadReceiptVisibilityGateTest {
    @Test
    fun seqOnlyAdvancesWhenHigherThanPreviousRequest() {
        val gate = ReadReceiptVisibilityGate()
        val channelId = ChannelId("channel-1")

        assertTrue(gate.shouldMarkReadToSeq(channelId, 10L))
        assertFalse(gate.shouldMarkReadToSeq(channelId, 10L))
        assertFalse(gate.shouldMarkReadToSeq(channelId, 9L))
        assertTrue(gate.shouldMarkReadToSeq(channelId, 11L))
    }

    @Test
    fun readAllIsOnlyRequestedOnceUntilCleared() {
        val gate = ReadReceiptVisibilityGate()
        val channelId = ChannelId("channel-2")

        assertTrue(gate.shouldMarkReadAll(channelId))
        assertFalse(gate.shouldMarkReadAll(channelId))

        gate.clear(channelId)

        assertTrue(gate.shouldMarkReadAll(channelId))
    }

    @Test
    fun clearAllowsFailedSeqToRetry() {
        val gate = ReadReceiptVisibilityGate()
        val channelId = ChannelId("channel-3")

        assertTrue(gate.shouldMarkReadToSeq(channelId, 42L))
        gate.clear(channelId)

        assertTrue(gate.shouldMarkReadToSeq(channelId, 42L))
    }
}
