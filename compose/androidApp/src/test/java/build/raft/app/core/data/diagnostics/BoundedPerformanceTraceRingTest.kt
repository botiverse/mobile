package build.raft.app.core.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedPerformanceTraceRingTest {
    @Test
    fun snapshotKeepsOnlyCapacityBoundedRecentEvents() {
        val ring = BoundedPerformanceTraceRing(capacity = 3, retentionNanos = 15L)

        ring.add(event(at = 1L, name = "old"))
        ring.add(event(at = 10L, name = "first"))
        ring.add(event(at = 20L, name = "second"))
        ring.add(event(at = 30L, name = "third"))

        assertEquals(
            listOf("second", "third"),
            ring.snapshot(nowNanos = 30L).map(PerformanceTraceEvent::name)
        )
    }

    @Test
    fun clearDropsAllCapturedEvents() {
        val ring = BoundedPerformanceTraceRing(capacity = 2, retentionNanos = 15L)
        ring.add(event(at = 10L, name = "frame"))

        ring.clear()

        assertTrue(ring.snapshot(nowNanos = 10L).isEmpty())
    }

    @Test
    fun detailedJankLoggingStartsAtSixtyFourMilliseconds() {
        assertFalse(shouldLogDetailedJankFrame(63L))
        assertTrue(shouldLogDetailedJankFrame(64L))
    }

    @Test
    fun framePolicyKeepsRoutineFramesOutOfTheRingAndOnlyLogsSevereFrames() {
        assertFalse(shouldCapturePerformanceFrame(16_000_000L, 0, 0))
        assertTrue(shouldCapturePerformanceFrame(24_000_000L, 0, 0))
        assertTrue(shouldCapturePerformanceFrame(16_000_000L, 1, 0))
        assertFalse(shouldLogDetailedFrameMetrics(63_000_000L, 2, 0))
        assertTrue(shouldLogDetailedFrameMetrics(64_000_000L, 0, 0))
        assertTrue(shouldLogDetailedFrameMetrics(16_000_000L, 3, 0))
        assertTrue(shouldLogDetailedFrameMetrics(16_000_000L, 0, 1))
    }

    @Test
    fun productionBoundsKeepTheFifteenSecondBufferSmall() {
        assertEquals(512, PerformanceTraceRingCapacity)
        assertEquals(15_000_000_000L, PerformanceTraceRetentionNanos)
        assertTrue(PerformanceTraceMetadataCharLimit <= 240)
        assertTrue(PerformanceTraceRingCapacity * PerformanceTraceMetadataCharLimit <= 128 * 1024)
    }

    private fun event(at: Long, name: String): PerformanceTraceEvent =
        PerformanceTraceEvent(
            elapsedRealtimeNanos = at,
            category = "test",
            name = name
        )
}
