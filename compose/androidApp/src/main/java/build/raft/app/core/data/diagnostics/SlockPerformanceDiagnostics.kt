package build.raft.app.core.data.diagnostics

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
    private const val TabSwitchOperationTtlMs = 700L
    private const val TabSwitchFrameTraceCount = 8
    private const val ThreadHostFrameTraceTtlMs = 1200L
    private const val ThreadHostFrameTraceCount = 10
    private const val ThreadHostTraversalTraceCount = 12
    private const val TraceNameLimit = 120

    private val route = AtomicReference("unknown")
    private val operation = AtomicReference("idle")
    private val pendingTabSwitchOperation = AtomicReference<TabSwitchOperation?>(null)
    private val pendingTabSwitchFrameTrace = AtomicReference<TabSwitchFrameTrace?>(null)
    private val pendingThreadHostFrameTrace = AtomicReference<ThreadHostFrameTrace?>(null)
    private val pendingThreadHostTraversalTrace = AtomicReference<ThreadHostTraversalTrace?>(null)
    private val jankFrameCount = AtomicInteger(0)
    private val longestFrameMs = AtomicLong(0L)
    private var jankStats: JankStats? = null

    @Synchronized
    fun install(window: Window) {
        if (jankStats != null) return
        jankStats =
                JankStats.createAndTrack(window) { frameData ->
                    val durationMs = frameData.frameDurationUiNanos / 1_000_000L
                    recordTabSwitchFrame(
                            durationMs = durationMs,
                            isJank = frameData.isJank,
                            states = frameData.states.joinToString(separator = "|")
                    )
                    recordThreadHostFrame(
                            durationMs = durationMs,
                            isJank = frameData.isJank,
                            states = frameData.states.joinToString(separator = "|")
                    )
                    if (!frameData.isJank) return@createAndTrack
                    val total = jankFrameCount.incrementAndGet()
                    updateLongestFrame(durationMs)
                    SlockAppLog.w(
                            Tag,
                            "jank_frame route=${route.get()} operation=${operation.get()} " +
                                    "durationMs=$durationMs totalJank=$total " +
                                    "longestFrameMs=${longestFrameMs.get()} " +
                                    "states=${frameData.states.joinToString(separator = "|")}"
                    )
                }
        jankStats?.isTrackingEnabled = true
        SlockAppLog.d(Tag, "jankstats_enabled")
    }

    fun updateRoute(view: View, nextRoute: String) {
        val sanitized = nextRoute.takeIf { it.isNotBlank() } ?: "unknown"
        if (route.getAndSet(sanitized) != sanitized) {
            SlockAppLog.d(
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
        val effective = effectiveOperation(sanitized)
        operation.set(effective)
        runCatching {
            PerformanceMetricsState.getHolderForHierarchy(view)
                    .state
                    ?.putState("operation", effective)
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
        if (sanitizedPhase.startsWith("bottom_tab_")) {
            val now = System.currentTimeMillis()
            pendingTabSwitchOperation.set(
                    TabSwitchOperation(
                            operation = sanitizedPhase,
                            expiresAtMs = now + TabSwitchOperationTtlMs
                    )
            )
            pendingTabSwitchFrameTrace.set(
                    TabSwitchFrameTrace(
                            route = sanitizedRoute,
                            operation = sanitizedPhase,
                            remainingFrames = TabSwitchFrameTraceCount,
                            expiresAtMs = now + TabSwitchOperationTtlMs
                    )
            )
        }
        if (sanitizedRoute == "kuikly_thread" && sanitizedPhase.startsWith("thread_")) {
            val now = System.currentTimeMillis()
            pendingThreadHostFrameTrace.set(
                    ThreadHostFrameTrace(
                            markerPhase = sanitizedPhase,
                            remainingFrames = ThreadHostFrameTraceCount,
                            expiresAtMs = now + ThreadHostFrameTraceTtlMs
                    )
            )
            pendingThreadHostTraversalTrace.set(
                    ThreadHostTraversalTrace(
                            markerPhase = sanitizedPhase,
                            remainingEvents = ThreadHostTraversalTraceCount,
                            expiresAtMs = now + ThreadHostFrameTraceTtlMs
                    )
            )
        }
        updateRoute(view, sanitizedRoute)
        updateOperation(view, sanitizedPhase)
        SlockAppLog.d(
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
        SlockAppLog.d(
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
            SlockAppLog.d(
                    Tag,
                    "$level operation=$operationName route=${route.get()} durationMs=$durationMs " +
                            "jank=${jankFrameCount.get()} longestFrameMs=${longestFrameMs.get()}"
            )
            Trace.endSection()
        }
    }

    fun summary(reason: String) {
        SlockAppLog.d(
                Tag,
                "summary reason=$reason route=${route.get()} operation=${operation.get()} " +
                        "jank=${jankFrameCount.get()} longestFrameMs=${longestFrameMs.get()}"
        )
    }

    fun recordHostTraversal(
            stage: String,
            durationNanos: Long,
            width: Int,
            height: Int,
            childCount: Int
    ) {
        val durationMicros = durationNanos / 1_000L
        while (true) {
            val pending = pendingThreadHostTraversalTrace.get() ?: return
            if (pending.expiresAtMs < System.currentTimeMillis() || pending.remainingEvents <= 0) {
                pendingThreadHostTraversalTrace.compareAndSet(pending, null)
                return
            }
            val eventIndex = ThreadHostTraversalTraceCount - pending.remainingEvents + 1
            SlockAppLog.d(
                    Tag,
                    "thread_host_traversal marker=${pending.markerPhase} event=$eventIndex " +
                            "stage=$stage durationUs=$durationMicros width=$width height=$height " +
                            "childCount=$childCount route=${route.get()} operation=${operation.get()}"
            )
            val next = pending.copy(remainingEvents = pending.remainingEvents - 1)
            val replacement = if (next.remainingEvents > 0) next else null
            if (pendingThreadHostTraversalTrace.compareAndSet(pending, replacement)) {
                return
            }
        }
    }

    private fun updateLongestFrame(durationMs: Long) {
        while (true) {
            val current = longestFrameMs.get()
            if (durationMs <= current || longestFrameMs.compareAndSet(current, durationMs)) {
                return
            }
        }
    }

    private fun effectiveOperation(nextOperation: String): String {
        if (nextOperation != "scene_enter") return nextOperation
        val pending = pendingTabSwitchOperation.get() ?: return nextOperation
        return if (pending.expiresAtMs >= System.currentTimeMillis()) {
            pending.operation
        } else {
            pendingTabSwitchOperation.compareAndSet(pending, null)
            nextOperation
        }
    }

    private fun recordTabSwitchFrame(
            durationMs: Long,
            isJank: Boolean,
            states: String
    ) {
        while (true) {
            val pending = pendingTabSwitchFrameTrace.get() ?: return
            if (pending.expiresAtMs < System.currentTimeMillis() || pending.remainingFrames <= 0) {
                pendingTabSwitchFrameTrace.compareAndSet(pending, null)
                return
            }
            val frameIndex = TabSwitchFrameTraceCount - pending.remainingFrames + 1
            SlockAppLog.d(
                    Tag,
                    "tab_switch_frame route=${pending.route} operation=${pending.operation} " +
                            "frame=$frameIndex durationMs=$durationMs isJank=$isJank states=$states"
            )
            val next = pending.copy(remainingFrames = pending.remainingFrames - 1)
            val replacement = if (next.remainingFrames > 0) next else null
            if (pendingTabSwitchFrameTrace.compareAndSet(pending, replacement)) {
                return
            }
        }
    }

    private fun recordThreadHostFrame(
            durationMs: Long,
            isJank: Boolean,
            states: String
    ) {
        while (true) {
            val pending = pendingThreadHostFrameTrace.get() ?: return
            if (pending.expiresAtMs < System.currentTimeMillis() || pending.remainingFrames <= 0) {
                pendingThreadHostFrameTrace.compareAndSet(pending, null)
                return
            }
            val frameIndex = ThreadHostFrameTraceCount - pending.remainingFrames + 1
            SlockAppLog.d(
                    Tag,
                    "thread_host_frame marker=${pending.markerPhase} frame=$frameIndex " +
                            "durationMs=$durationMs isJank=$isJank states=$states " +
                            "route=${route.get()} operation=${operation.get()}"
            )
            val next = pending.copy(remainingFrames = pending.remainingFrames - 1)
            val replacement = if (next.remainingFrames > 0) next else null
            if (pendingThreadHostFrameTrace.compareAndSet(pending, replacement)) {
                return
            }
        }
    }

    private data class TabSwitchOperation(
            val operation: String,
            val expiresAtMs: Long
    )

    private data class TabSwitchFrameTrace(
            val route: String,
            val operation: String,
            val remainingFrames: Int,
            val expiresAtMs: Long
    )

    private data class ThreadHostFrameTrace(
            val markerPhase: String,
            val remainingFrames: Int,
            val expiresAtMs: Long
    )

    private data class ThreadHostTraversalTrace(
            val markerPhase: String,
            val remainingEvents: Int,
            val expiresAtMs: Long
    )
}
