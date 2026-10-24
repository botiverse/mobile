package build.raft.app.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RaftTransferMeterTest {

    @Test
    fun rateIsTheByteDeltaOverTheWindow() = runTest {
        val meter = RaftTransferMeter(timeSource = testScheduler.timeSource, bytesTotal = 100_000_000L)
        meter.bytes(1_000L)
        assertNull(meter.progress.bytesPerSecond, "one sample has no rate")
        delay(500)
        // 1 MB in 500 ms.
        assertEquals(2_000_000L, meter.bytes(1_001_000L).bytesPerSecond)
        // Samples older than the 3 s window drop out: a slow tail is not averaged with the fast start.
        delay(1_000)
        meter.bytes(1_101_000L)
        repeat(4) {
            delay(1_000)
            meter.bytes(meter.progress.bytesDone + 100_000L)
        }
        assertEquals(100_000L, meter.progress.bytesPerSecond)
    }

    @Test
    fun speedLabelChangesAtMostOncePerSecond() = runTest {
        val meter = RaftTransferMeter(timeSource = testScheduler.timeSource, bytesTotal = 1_000_000_000L)
        meter.bytes(1L)
        delay(100)
        val first = meter.bytes(100_001L).bytesPerSecond
        assertEquals(1_000_000L, first)
        val shown = mutableListOf<Long?>()
        // Samples every 100 ms at a changing rate: the shown speed may only change on a 1 s boundary.
        var changes = 0
        var last = first
        for (step in 1..30) {
            delay(100)
            val next = meter.bytes(meter.progress.bytesDone + step * 10_000L).bytesPerSecond
            shown += next
            if (next != last) changes += 1
            last = next
        }
        assertTrue(changes in 2..3, "3 s of samples changed the label $changes times: $shown")
        assertTrue(shown.take(9).all { it == first }, "the label held for the first second: $shown")
    }

    @Test
    fun hundredOnlyAfterCompletion() = runTest {
        val meter = RaftTransferMeter(timeSource = testScheduler.timeSource, bytesTotal = 1_000L)
        assertEquals(99, meter.bytes(999L).percent)
        // Last byte handed to the socket: not complete yet.
        assertEquals(99, meter.bytes(1_000L).percent)
        val finishing = meter.finishing()
        assertEquals(RaftTransferPhase.Finishing, finishing.phase)
        assertEquals(99, finishing.percent)
        assertNull(finishing.bytesPerSecond)
        val done = meter.done()
        assertEquals(RaftTransferPhase.Done, done.phase)
        assertEquals(100, done.percent)
        assertEquals(1_000L, done.bytesDone)
    }

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
    fun unknownTotalHasBytesAndSpeedButNoPercent() = runTest {
        val meter = RaftTransferMeter(timeSource = testScheduler.timeSource)
        assertEquals(RaftTransferPhase.Queued, meter.progress.phase)
        meter.bytes(10_000L)
        delay(1_000)
        val running = meter.bytes(510_000L)
        assertEquals(RaftTransferPhase.Running, running.phase)
        assertNull(running.percent)
        assertNull(running.bytesTotal)
        assertEquals(500_000L, running.bytesPerSecond)
        // The total arrives late: percent starts from the bytes so far.
        assertEquals(51, meter.total(1_000_000L).percent)
        assertEquals(100, meter.done().percent)
    }

    @Test
    fun decodedProgressCannotShowHundredBeforeDone() {
        val rows =
            listOf(
                Triple(RaftTransferPhase.Running, 100, 99),
                Triple(RaftTransferPhase.Finishing, 100, 99),
                Triple(RaftTransferPhase.Running, 0, 1),
                Triple(RaftTransferPhase.Done, 40, 100)
            )
        rows.forEach { (phase, sent, shown) ->
            assertEquals(shown, RaftTransferProgress.decoded(500L, 1_000L, phase, sent, null).percent, "$phase $sent")
        }
        assertNull(RaftTransferProgress.decoded(0L, 1_000L, RaftTransferPhase.Queued, 5, 10L).percent)
        assertNull(RaftTransferProgress.decoded(5L, 1_000L, RaftTransferPhase.Paused, 5, 10L).bytesPerSecond)
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
