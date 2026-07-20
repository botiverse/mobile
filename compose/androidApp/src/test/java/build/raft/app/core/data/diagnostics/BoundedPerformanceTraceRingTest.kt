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
    fun snapshotSortsReverseArrivalByMonotonicTimestamp() {
        val ring = BoundedPerformanceTraceRing(capacity = 3, retentionNanos = 15L)
        ring.add(event(at = 20L, name = "later-time-first-arrival"))
        ring.add(event(at = 10L, name = "earlier-time-late-arrival"))

        assertEquals(
            listOf("earlier-time-late-arrival", "later-time-first-arrival"),
            ring.snapshot(nowNanos = 20L).map(PerformanceTraceEvent::name)
        )
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
    }

    @Test
    fun threadMetadataUsesPhaseAllowlistAndSanitizer() {
        val metadata =
            sanitizeThreadPerformanceMetadata(
                phase = "composer_send",
                metadata =
                    mapOf(
                        "result" to "failed user@example.com Bearer secret-token",
                        "attempt" to "2",
                        "localMessageId" to "local-sensitive-id",
                        "parentMessageId" to "parent-sensitive-id",
                        "body" to "private body"
                    )
            )

        assertTrue(metadata.contains("attempt:2"))
        assertTrue(metadata.contains("[redacted]"))
        assertFalse(metadata.contains("secret-token"))
        assertFalse(metadata.contains("local-sensitive-id"))
        assertFalse(metadata.contains("parent-sensitive-id"))
        assertFalse(metadata.contains("private body"))
    }

    private fun event(at: Long, name: String): PerformanceTraceEvent =
        PerformanceTraceEvent(
            elapsedRealtimeNanos = at,
            category = "test",
            name = name
        )
}
