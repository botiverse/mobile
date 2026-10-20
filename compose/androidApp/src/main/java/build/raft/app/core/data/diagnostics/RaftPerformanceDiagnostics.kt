package build.raft.app.core.data.diagnostics

import android.os.Handler
import android.os.HandlerThread
import android.os.Trace
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import build.raft.app.BuildConfig
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

object RaftPerformanceDiagnostics {
    private const val Tag = "SlockPerf"
    private const val SlowOperationThresholdMs = 250L
    private const val TabSwitchOperationTtlMs = 700L
    private const val TabSwitchFrameTraceCount = 8
    private const val ThreadHostFrameTraceTtlMs = 1200L
    private const val ThreadHostFrameTraceCount = 10
    private const val ThreadHostTraversalTraceCount = 40
    private const val TraceNameLimit = 120
    private const val DefaultRefreshRateHz = 60f

    private val route = AtomicReference("unknown")
    private val operation = AtomicReference("idle")
    private val pendingTabSwitchOperation = AtomicReference<TabSwitchOperation?>(null)
    private val pendingTabSwitchFrameTrace = AtomicReference<TabSwitchFrameTrace?>(null)
    private val pendingThreadHostFrameTrace = AtomicReference<ThreadHostFrameTrace?>(null)
    private val pendingThreadHostTraversalTrace = AtomicReference<ThreadHostTraversalTrace?>(null)
    private val jankFrameCount = AtomicInteger(0)
    private val longestFrameMs = AtomicLong(0L)
    private val windowTrackings = IdentityHashMap<Window, WindowTracking>()
    private var frameMetricsThread: HandlerThread? = null

    @Synchronized
    fun install(window: Window) {
        if (windowTrackings.containsKey(window)) return
        RaftPerformanceTraceRing.configure(BuildConfig.PERFORMANCE_TRACE_RING_ENABLED)
        if (!RaftPerformanceTraceRing.isEnabled()) {
            windowTrackings[window] = WindowTracking(jankStats = null, frameMetricsListener = null)
            RaftAppLog.d(Tag, "performance_tracking_disabled windows=${windowTrackings.size}")
            return
        }
        val jankStats =
                JankStats.createAndTrack(window) { frameData ->
                    val durationMs = frameData.frameDurationUiNanos / 1_000_000L
                    val detailedJank = frameData.isJank && shouldLogDetailedJankFrame(durationMs)
                    val needsStates =
                            detailedJank ||
                                    pendingTabSwitchFrameTrace.get() != null ||
                                    pendingThreadHostFrameTrace.get() != null
                    val states =
                            if (needsStates) {
                                frameData.states.joinToString(separator = "|")
                            } else {
                                ""
                            }
                    recordTabSwitchFrame(
                            durationMs = durationMs,
                            isJank = frameData.isJank,
                            states = states
                    )
                    recordThreadHostFrame(
                            durationMs = durationMs,
                            isJank = frameData.isJank,
                            states = states
                    )
                    if (!frameData.isJank) return@createAndTrack
                    val total = jankFrameCount.incrementAndGet()
                    updateLongestFrame(durationMs)
                    if (detailedJank) {
                        RaftAppLog.w(
                                Tag,
                                "jank_frame route=${route.get()} operation=${operation.get()} " +
                                        "durationMs=$durationMs totalJank=$total " +
                                        "longestFrameMs=${longestFrameMs.get()} " +
                                        "states=$states"
                        )
                    }
                }
        jankStats.isTrackingEnabled = true
        val frameMetricsListener = installFrameMetrics(window)
        windowTrackings[window] = WindowTracking(jankStats, frameMetricsListener)
        RaftAppLog.d(
                Tag,
                "jankstats_enabled traceRing=${RaftPerformanceTraceRing.isEnabled()} windows=${windowTrackings.size}"
        )
    }

    @Synchronized
    fun uninstall(window: Window) {
        val tracking = windowTrackings.remove(window) ?: return
        tracking.jankStats?.isTrackingEnabled = false
        tracking.frameMetricsListener?.let { listener ->
            runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
        }
        if (windowTrackings.isEmpty()) {
            frameMetricsThread?.quitSafely()
            frameMetricsThread = null
        }
    }

    fun updateRoute(view: View, nextRoute: String) {
        val sanitized = nextRoute.takeIf { it.isNotBlank() } ?: "unknown"
        if (route.getAndSet(sanitized) != sanitized) {
            RaftAppLog.d(
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
            metadata: String,
            performanceMetadata: Map<String, String> = emptyMap()
    ) {
        val sanitizedRoute = routeName.takeIf { it.isNotBlank() } ?: "unknown"
        val sanitizedPhase = phase.takeIf { it.isNotBlank() } ?: "marker"
        val sanitizedEvent = event.takeIf { it.isNotBlank() } ?: "instant"
        val sanitizedSceneId = sceneId.takeIf { it.isNotBlank() } ?: sanitizedRoute
        if (sanitizedRoute == "kuikly_thread") {
            RaftPerformanceTraceRing.recordThreadMarker(
                    phase = sanitizedPhase,
                    event = sanitizedEvent,
                    durationMs = durationMs,
                    metadataFields = performanceMetadata
            )
            if (sanitizedPhase.isThreadGenerationRingOnlyPhase()) return
        }
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
        RaftAppLog.d(
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
        RaftAppLog.d(
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
            RaftAppLog.d(
                    Tag,
                    "$level operation=$operationName route=${route.get()} durationMs=$durationMs " +
                            "jank=${jankFrameCount.get()} longestFrameMs=${longestFrameMs.get()}"
            )
            Trace.endSection()
        }
    }

    fun summary(reason: String) {
        RaftAppLog.d(
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
            RaftAppLog.d(
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

    private fun installFrameMetrics(window: Window): Window.OnFrameMetricsAvailableListener? {
        if (!RaftPerformanceTraceRing.isEnabled()) return null
        val refreshRate = window.decorView.display?.refreshRate?.takeIf { it > 0f } ?: DefaultRefreshRateHz
        val frameIntervalNanos = (1_000_000_000.0 / refreshRate.toDouble()).toLong().coerceAtLeast(1L)
        val thread =
                frameMetricsThread
                        ?: HandlerThread("slock-frame-metrics").apply {
                            start()
                            frameMetricsThread = this
                        }
        val listener =
                Window.OnFrameMetricsAvailableListener { _, metrics, droppedSinceLastInvocation ->
                    val totalNanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION).coerceAtLeast(0L)
                    val missedVsyncCount =
                            (ceil(totalNanos.toDouble() / frameIntervalNanos.toDouble()).toInt() - 1)
                                    .coerceAtLeast(0)
                    val detailed =
                            RaftPerformanceTraceRing.recordFrame(
                                    totalDurationNanos = totalNanos,
                                    missedVsyncCount = missedVsyncCount,
                                    droppedReportCount = droppedSinceLastInvocation,
                                    layoutMeasureNanos = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                                    drawNanos = metrics.getMetric(FrameMetrics.DRAW_DURATION)
                            )
                    if (detailed) {
                        RaftAppLog.w(
                                Tag,
                                "frame_metrics route=${route.get()} operation=${operation.get()} " +
                                        "durationMs=${totalNanos / 1_000_000L} missedVsync=$missedVsyncCount " +
                                        "droppedReports=$droppedSinceLastInvocation"
                        )
                    }
                }
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        return listener
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
            RaftAppLog.d(
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
            RaftAppLog.d(
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

    private data class WindowTracking(
            val jankStats: JankStats?,
            val frameMetricsListener: Window.OnFrameMetricsAvailableListener?
    )
}

internal fun shouldLogDetailedJankFrame(durationMs: Long): Boolean =
        durationMs >= 64L

private fun String.isThreadGenerationRingOnlyPhase(): Boolean =
        this == "thread_reconcile_generation" || this == "thread_layout_generation"
