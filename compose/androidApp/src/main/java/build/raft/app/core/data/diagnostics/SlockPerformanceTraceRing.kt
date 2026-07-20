package build.raft.app.core.data.diagnostics

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal object SlockPerformanceTraceRing {
    private val enabled = AtomicBoolean(false)
    private val ring =
        BoundedPerformanceTraceRing(
            capacity = PerformanceTraceRingCapacity,
            retentionNanos = PerformanceTraceRetentionNanos
        )

    fun configure(isEnabled: Boolean) {
        enabled.set(isEnabled)
        if (!isEnabled) ring.clear()
    }

    fun isEnabled(): Boolean = enabled.get()

    fun recordFrame(
        totalDurationNanos: Long,
        missedVsyncCount: Int,
        droppedReportCount: Int,
        layoutMeasureNanos: Long,
        drawNanos: Long
    ): Boolean {
        if (!enabled.get()) return false
        if (!shouldCapturePerformanceFrame(totalDurationNanos, missedVsyncCount, droppedReportCount)) {
            return false
        }
        ring.add(
            PerformanceTraceEvent(
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                category = "frame",
                name = "activity_frame",
                durationNanos = totalDurationNanos.coerceAtLeast(0L),
                missedVsyncCount = missedVsyncCount.coerceAtLeast(0),
                metadata =
                    "dropped=$droppedReportCount layoutUs=${layoutMeasureNanos.coerceAtLeast(0L) / 1_000L} " +
                        "drawUs=${drawNanos.coerceAtLeast(0L) / 1_000L}"
            )
        )
        return shouldLogDetailedFrameMetrics(totalDurationNanos, missedVsyncCount, droppedReportCount)
    }

    fun recordThreadMarker(phase: String, metadata: String) {
        if (!enabled.get()) return
        val cleanPhase = phase.trim().takeIf(String::isNotBlank) ?: return
        ring.add(
            PerformanceTraceEvent(
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                category = "thread",
                name = cleanPhase.take(80),
                metadata = metadata.take(PerformanceTraceMetadataCharLimit)
            )
        )
    }

    fun exportRecentJsonl(nowNanos: Long = SystemClock.elapsedRealtimeNanos()): String {
        val events = ring.snapshot(nowNanos)
        if (events.isEmpty()) return ""
        return buildList(events.size + 1) {
            add(
                JSONObject()
                    .put("category", "clock")
                    .put("name", "snapshot")
                    .put("elapsedRealtimeNanos", nowNanos)
                    .put("wallTimeMillis", System.currentTimeMillis())
                    .toString()
            )
            events.forEach { event ->
                add(
                    JSONObject()
                        .put("elapsedRealtimeNanos", event.elapsedRealtimeNanos)
                        .put("category", event.category)
                        .put("name", event.name)
                        .put("durationNanos", event.durationNanos)
                        .put("missedVsyncCount", event.missedVsyncCount)
                        .put("metadata", event.metadata)
                        .toString()
                )
            }
        }.joinToString(separator = "\n")
    }
}
