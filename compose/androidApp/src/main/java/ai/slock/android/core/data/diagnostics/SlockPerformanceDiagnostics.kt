package ai.slock.android.core.data.diagnostics

import android.os.Trace
import android.view.View
import android.view.Window
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object SlockPerformanceDiagnostics {
    private const val Tag = "SlockPerf"
    private const val SlowOperationThresholdMs = 250L
    private const val TraceNameLimit = 120

    private val route = AtomicReference("unknown")
    private val operation = AtomicReference("idle")
    private val jankFrameCount = AtomicInteger(0)
    private val longestFrameMs = AtomicLong(0L)
    private var jankStats: JankStats? = null

    @Synchronized
    fun install(window: Window) {
        if (jankStats != null) return
        jankStats =
                JankStats.createAndTrack(window) { frameData ->
                    if (!frameData.isJank) return@createAndTrack
                    val durationMs = frameData.frameDurationUiNanos / 1_000_000L
                    val total = jankFrameCount.incrementAndGet()
                    updateLongestFrame(durationMs)
                    SlockDiagnosticsLogger.w(
                            Tag,
                            "jank_frame route=${route.get()} operation=${operation.get()} " +
                                    "durationMs=$durationMs totalJank=$total " +
                                    "longestFrameMs=${longestFrameMs.get()} " +
                                    "states=${frameData.states.joinToString(separator = "|")}"
                    )
                }
        jankStats?.isTrackingEnabled = true
        SlockDiagnosticsLogger.d(Tag, "jankstats_enabled")
    }

    fun updateRoute(view: View, nextRoute: String) {
        val sanitized = nextRoute.takeIf { it.isNotBlank() } ?: "unknown"
        if (route.getAndSet(sanitized) != sanitized) {
            SlockDiagnosticsLogger.d(
                    Tag,
                    "route route=$sanitized operation=${operation.get()} " +
                            "jank=${jankFrameCount.get()} longestFrameMs=${longestFrameMs.get()}"
            )
        }
        runCatching {
            PerformanceMetricsState.getHolderForHierarchy(view).state?.putState("route", sanitized)
        }
    }

    fun updateOperation(view: View, nextOperation: String) {
        val sanitized = nextOperation.takeIf { it.isNotBlank() } ?: "idle"
        operation.set(sanitized)
        runCatching {
            PerformanceMetricsState.getHolderForHierarchy(view)
                    .state
                    ?.putState("operation", sanitized)
        }
    }

    fun recordMarker(
            view: View,
            routeName: String,
            phase: String,
            event: String,
            sceneId: String,
            durationMs: Long?,
            metadata: String
    ) {
        val sanitizedRoute = routeName.takeIf { it.isNotBlank() } ?: "unknown"
        val sanitizedPhase = phase.takeIf { it.isNotBlank() } ?: "marker"
        val sanitizedEvent = event.takeIf { it.isNotBlank() } ?: "instant"
        val sanitizedSceneId = sceneId.takeIf { it.isNotBlank() } ?: sanitizedRoute
        updateRoute(view, sanitizedRoute)
        updateOperation(view, sanitizedPhase)
        SlockDiagnosticsLogger.d(
                Tag,
                buildString {
                    append("marker route=")
                    append(sanitizedRoute)
                    append(" operation=")
                    append(sanitizedPhase)
                    append(" phase=")
                    append(sanitizedPhase)
                    append(" event=")
                    append(sanitizedEvent)
                    append(" sceneId=")
                    append(sanitizedSceneId)
                    durationMs?.takeIf { it >= 0L }?.let {
                        append(" durationMs=")
                        append(it)
                    }
                    if (metadata.isNotBlank()) {
                        append(" metadata=")
                        append(metadata)
                    }
                }
        )
    }

    fun beginTrace(operationName: String): ActiveTrace {
        val traceName = operationName.take(TraceNameLimit)
        Trace.beginSection(traceName)
        SlockDiagnosticsLogger.d(
                Tag,
                "operation_start operation=$operationName route=${route.get()}"
        )
        return ActiveTrace(operationName, System.currentTimeMillis())
    }

    class ActiveTrace internal constructor(
            private val operationName: String,
            private val startedAt: Long
    ) : AutoCloseable {
        override fun close() {
            val durationMs = System.currentTimeMillis() - startedAt
            val level =
                    if (durationMs >= SlowOperationThresholdMs) {
                        "operation_slow"
                    } else {
                        "operation_finish"
                    }
            SlockDiagnosticsLogger.d(
                    Tag,
                    "$level operation=$operationName route=${route.get()} durationMs=$durationMs " +
                            "jank=${jankFrameCount.get()} longestFrameMs=${longestFrameMs.get()}"
            )
            Trace.endSection()
        }
    }

    fun summary(reason: String) {
        SlockDiagnosticsLogger.d(
                Tag,
                "summary reason=$reason route=${route.get()} operation=${operation.get()} " +
                        "jank=${jankFrameCount.get()} longestFrameMs=${longestFrameMs.get()}"
        )
    }

    private fun updateLongestFrame(durationMs: Long) {
        while (true) {
            val current = longestFrameMs.get()
            if (durationMs <= current || longestFrameMs.compareAndSet(current, durationMs)) {
                return
            }
        }
    }
}
