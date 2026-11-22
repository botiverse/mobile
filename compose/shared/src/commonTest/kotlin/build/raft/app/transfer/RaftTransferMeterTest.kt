package build.raft.app.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RaftTransferMeterTest {

    @Test
    fun percentNeverGoesBackwardsWithinATransfer() = runTest {
        val meter = RaftTransferMeter(timeSource = testScheduler.timeSource, bytesTotal = 1_000L)
        assertEquals(1, meter.bytes(1L).percent, "the first byte shows 1, not 0")
        assertEquals(50, meter.bytes(500L).percent)
        // A late, smaller sample and a larger total both would rewind the bar.
        assertEquals(50, meter.bytes(200L).percent)
        assertEquals(500L, meter.progress.bytesDone)
        assertEquals(50, meter.bytes(600L, bytesTotal = 10_000L).percent)
        assertEquals(50, meter.pause().percent)
        assertEquals(50, meter.resume().percent)
        assertEquals(50, meter.failed().percent, "a failure keeps the last percent")
        assertEquals(RaftTransferPhase.Failed, meter.bytes(9_000L).phase, "nothing moves after the end")
    }

    @Test
    fun combinedPercentWeighsEachTransfersOwnPercent() = runTest {
        val small = RaftTransferMeter(timeSource = testScheduler.timeSource, bytesTotal = 100L)
        val large = RaftTransferMeter(timeSource = testScheduler.timeSource, bytesTotal = 900L)
        small.bytes(100L)
        large.bytes(450L)
        // (99 * 100 + 50 * 900) / 1000
        assertEquals(54, RaftTransferProgress.combinedPercent(listOf(small.progress, large.progress)))
        small.done()
        large.bytes(900L)
        assertEquals(99, RaftTransferProgress.combinedPercent(listOf(small.progress, large.progress)), "one not done")
        large.done()
        assertEquals(100, RaftTransferProgress.combinedPercent(listOf(small.progress, large.progress)))
        assertNull(RaftTransferProgress.combinedPercent(listOf(small.progress, RaftTransferMeter().progress)))
    }
}
