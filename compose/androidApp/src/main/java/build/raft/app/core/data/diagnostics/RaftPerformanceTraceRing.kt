package build.raft.app.core.data.diagnostics

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal object RaftPerformanceTraceRing {
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

    fun recordThreadMarker(
        phase: String,
        event: String = "instant",
        durationMs: Long? = null,
        metadataFields: Map<String, String> = emptyMap()
    ) {
        if (!enabled.get()) return
        val cleanPhase = phase.trim().takeIf(String::isNotBlank) ?: return
        if (cleanPhase !in ThreadPerformanceMetadataAllowlist) return
        val cleanEvent = event.takeIf { it in ThreadPerformanceEventAllowlist } ?: "other"
        val cleanMetadata =
            listOf(
                buildString {
                    append("event=").append(cleanEvent)
                    durationMs?.takeIf { it >= 0L }?.let { append(" durationMs=").append(it) }
                },
                sanitizeThreadPerformanceMetadata(cleanPhase, metadataFields)
            )
                .filter(String::isNotBlank)
                .joinToString(separator = " ")
                .take(PerformanceTraceMetadataCharLimit)
        ring.add(
            PerformanceTraceEvent(
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                category = "thread",
                name = cleanPhase.take(80),
                metadata = cleanMetadata
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

internal fun sanitizeThreadPerformanceMetadata(
    phase: String,
    metadata: Map<String, String>
): String {
    val allowedKeys = ThreadPerformanceMetadataAllowlist[phase].orEmpty()
    return metadata.entries
        .asSequence()
        .filter { (key, _) -> key in allowedKeys }
        .mapNotNull { (key, value) ->
            val cleanValue =
                DiagnosticsSanitizer.sanitize(value)
                    .replace(Regex("[\\r\\n\\t]+"), "_")
                    .trim()
                    .take(80)
                    .takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
            "${key.take(40)}:$cleanValue"
        }
        .sorted()
        .joinToString(separator = ",")
        .take(PerformanceTraceMetadataCharLimit)
}

private val ThreadPerformanceMetadataAllowlist =
    mapOf(
        "thread_open_start" to setOf("source", "status"),
        "thread_first_render" to setOf("messages", "source", "status"),
        "thread_payload_ready" to setOf("budgetMs", "messages", "source", "status", "withinBudget"),
        "thread_first_frame" to
            setOf(
                "drawBudgetMs",
                "drawWithinBudget",
                "layoutBudgetMs",
                "layoutWithinBudget",
                "reason",
                "status"
            ),
        "thread_reconcile_generation" to setOf("generation", "messages", "status"),
        "thread_layout_generation" to setOf("generation", "height", "reconcileGeneration", "width"),
        "composer_send" to
            setOf(
                "asTask",
                "attachments",
                "attempt",
                "bodyLength",
                "code",
                "contractCode",
                "errorBytes",
                "errorType",
                "hasText",
                "httpCode",
                "messages",
                "result"
            )
    )

private val ThreadPerformanceEventAllowlist = setOf("end", "instant", "start")
