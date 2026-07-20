package build.raft.app.core.data.diagnostics

internal const val PerformanceTraceRingCapacity = 512
internal const val PerformanceTraceRetentionNanos = 15_000_000_000L
internal const val PerformanceTraceMetadataCharLimit = 240

internal data class PerformanceTraceEvent(
    val elapsedRealtimeNanos: Long,
    val category: String,
    val name: String,
    val durationNanos: Long = 0L,
    val missedVsyncCount: Int = 0,
    val metadata: String = ""
)

internal class BoundedPerformanceTraceRing(
    private val capacity: Int,
    private val retentionNanos: Long
) {
    init {
        require(capacity > 0)
        require(retentionNanos > 0L)
    }

    private val entries = arrayOfNulls<PerformanceTraceEvent>(capacity)
    private var nextIndex = 0
    private var size = 0

    @Synchronized
    fun add(event: PerformanceTraceEvent) {
        entries[nextIndex] = event
        nextIndex = (nextIndex + 1) % capacity
        if (size < capacity) size += 1
    }

    @Synchronized
    fun snapshot(nowNanos: Long): List<PerformanceTraceEvent> {
        val cutoff = nowNanos - retentionNanos
        val entryCount = size
        val firstIndex = (nextIndex - entryCount + capacity) % capacity
        return buildList(entryCount) {
            repeat(entryCount) { offset ->
                val event = entries[(firstIndex + offset) % capacity] ?: return@repeat
                if (event.elapsedRealtimeNanos >= cutoff && event.elapsedRealtimeNanos <= nowNanos) {
                    this.add(event)
                }
            }
        }.sortedBy(PerformanceTraceEvent::elapsedRealtimeNanos)
    }

    @Synchronized
    fun clear() {
        entries.fill(null)
        nextIndex = 0
        size = 0
    }
}

internal fun shouldCapturePerformanceFrame(
    totalDurationNanos: Long,
    missedVsyncCount: Int,
    droppedReportCount: Int
): Boolean =
    totalDurationNanos >= 24_000_000L || missedVsyncCount > 0 || droppedReportCount > 0

internal fun shouldLogDetailedFrameMetrics(
    totalDurationNanos: Long,
    missedVsyncCount: Int,
    droppedReportCount: Int
): Boolean =
    totalDurationNanos >= 64_000_000L || missedVsyncCount >= 3 || droppedReportCount > 0
