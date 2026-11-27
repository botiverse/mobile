@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app

import build.raft.app.data.MOBILE_WEB_CONTAINER_TRACE_NAME
import build.raft.app.data.ProcessRaftDatetimeFoundationOwner
import build.raft.app.data.RaftClientTraceEvents
import build.raft.app.data.RaftDataStoreRegistry
import build.raft.app.data.RaftWebSpanRecord
import build.raft.app.data.TRACE_SAMPLE_ERROR_DEFAULT_RATE
import build.raft.app.data.resolveActiveServerTraceSampleRate
import build.raft.app.data.traceSampled
import build.raft.app.matches
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.network.RaftTraceIds
import build.raft.app.network.newRaftTraceIds
import build.raft.app.settings.matches
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Structured OTLP tracing for `compose/web-container` crashes, watchdog terminal degradations,
 * engine startup health, and anti-zombie pool anomalies (`dm:@artin:#7`).
 *
 * Flows through the existing [RaftClientTraceEvents.emitSpan] pipeline (`POST /api/web-traces` ->
 * OTLP / ScopeDB / Grafana), which automatically attaches `clientPlatform`, `appVersion`, and
 * `app_build`, and enforces `RAFT_PRIVACY_MODE`, foreground gating, account scoping, and retry
 * backoff.
 *
 * Guardrails:
 * - High-signal events only (renderer deaths, watchdog terminal errors, engine startup ready/failed,
 *   and pool zombie/timeout anomalies); routine resource/JSB/pool traffic stays local-log only.
 * - Codes, booleans, counts, and durations only: never raw URLs, query parameters, keepAlive keys,
 *   tokens, or user PII.
 * - Process-local sliding-window burst cap ([WEB_CONTAINER_TRACE_MAX_SPANS_PER_MINUTE]) so a
 *   pathological page can never spam `/api/web-traces`.
 */
internal const val MOBILE_WEB_CONTAINER_RENDER_PROCESS_GONE_SPAN: String = "mobile.web_container.render_process_gone"
internal const val MOBILE_WEB_CONTAINER_WATCHDOG_TERMINAL_SPAN: String = "mobile.web_container.watchdog_terminal"
internal const val MOBILE_WEB_CONTAINER_STARTUP_SPAN: String = "mobile.web_container.startup"
internal const val MOBILE_WEB_CONTAINER_POOL_ANOMALY_SPAN: String = "mobile.web_container.pool_anomaly"

internal const val WEB_CONTAINER_TRACE_MAX_SPANS_PER_MINUTE: Int = 20
internal const val WEB_CONTAINER_TRACE_WINDOW_MS: Long = 60_000L

private val WebContainerTraceCodePattern = Regex("^[A-Za-z0-9_.:-]{1,64}$")
private val WebContainerStartupCodePattern = Regex("^[A-Za-z0-9_-]{1,64}$")
private val WebContainerStartupExceptionPrefixPattern = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")

/** Pool actions indicating a silent crash, hung prewarm, or anti-zombie eviction worth remote tracing. */
internal val TracedWebContainerPoolAnomalyActions: Set<String> =
    setOf(
        "ios_spare_zombie_rebuilt",
        "prewarm_aborted_timeout",
        "evict_unhealthy_on_peek",
        "evict_unhealthy_on_take",
        "ios_spare_rejected_unexpected_url",
    )

/**
 * Maps Android `WebView.RENDERER_PRIORITY_*` numeric values (API 26+) to stable lowercase labels
 * without introducing an Android SDK dependency into common code.
 */
internal fun raftWebContainerRendererPriorityLabel(priority: Int): String =
    when (priority) {
        0 -> "waived"
        1 -> "bound"
        2 -> "important"
        else -> "unknown"
    }

internal fun sanitizeWebContainerTraceCode(raw: String): String =
    raw.trim().takeIf { WebContainerTraceCodePattern.matches(it) } ?: "other"

internal fun sanitizeWebContainerStartupReason(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "other"
    if (WebContainerStartupCodePattern.matches(trimmed)) return trimmed
    val sep = trimmed.indexOf(": ")
    if (sep > 0) {
        val prefix = trimmed.substring(0, sep)
        if (WebContainerStartupExceptionPrefixPattern.matches(prefix)) return prefix
    }
    return "other"
}

/**
 * Converts a high-signal [WebContainerTelemetryEvent] into an OTLP-compatible [RaftWebSpanRecord],
 * or returns `null` for routine events that belong in local logs only.
 */
internal fun buildWebContainerTraceSpan(
    event: WebContainerTelemetryEvent,
    ids: RaftTraceIds,
    nowEpochMs: Long,
    sampleRate: Double = TRACE_SAMPLE_ERROR_DEFAULT_RATE,
): RaftWebSpanRecord? =
    when (event) {
        is WebContainerTelemetryEvent.RenderProcessGone -> {
            val timestamp = raftEpochMillisUtcIso(nowEpochMs)
            val attrs =
                buildMap<String, JsonPrimitive> {
                    put("surface", JsonPrimitive(sanitizeWebContainerTraceCode(event.surfaceId)))
                    put("did_crash", JsonPrimitive(event.didCrash))
                    put("recovered", JsonPrimitive(event.recovered))
                    put("attempt", JsonPrimitive(event.attempt.coerceAtLeast(0)))
                    put("parked", JsonPrimitive(event.parked))
                    event.engine?.takeIf { it.isNotBlank() }?.let {
                        put("engine", JsonPrimitive(sanitizeWebContainerTraceCode(it)))
                    }
                    event.rendererPriorityAtExit?.let {
                        put("renderer_priority", JsonPrimitive(raftWebContainerRendererPriorityLabel(it)))
                    }
                    put("sample_rate", JsonPrimitive(sampleRate))
                }
            RaftWebSpanRecord(
                traceId = ids.traceId,
                spanId = ids.spanId,
                name = MOBILE_WEB_CONTAINER_RENDER_PROCESS_GONE_SPAN,
                status = if (event.didCrash || !event.recovered) "error" else "ok",
                startTime = timestamp,
                endTime = timestamp,
                attrs = JsonObject(attrs),
            )
        }

        is WebContainerTelemetryEvent.WatchdogTerminalError -> {
            val timestamp = raftEpochMillisUtcIso(nowEpochMs)
            val attrs =
                buildMap<String, JsonPrimitive> {
                    put("surface", JsonPrimitive(sanitizeWebContainerTraceCode(event.surfaceId)))
                    put("reason", JsonPrimitive(sanitizeWebContainerTraceCode(event.reason)))
                    put("fatal", JsonPrimitive(isFatalWebContainerWatchdogReason(event.reason)))
                    event.engine?.takeIf { it.isNotBlank() }?.let {
                        put("engine", JsonPrimitive(sanitizeWebContainerTraceCode(it)))
                    }
                    put("sample_rate", JsonPrimitive(sampleRate))
                }
            RaftWebSpanRecord(
                traceId = ids.traceId,
                spanId = ids.spanId,
                name = MOBILE_WEB_CONTAINER_WATCHDOG_TERMINAL_SPAN,
                status = "error",
                startTime = timestamp,
                endTime = timestamp,
                attrs = JsonObject(attrs),
            )
        }

        is WebContainerTelemetryEvent.StartupCompleted -> {
            val elapsedMs = event.elapsedMs.coerceAtLeast(0L)
            val startEpochMs = (nowEpochMs - elapsedMs).coerceAtLeast(0L)
            val attrs =
                buildMap<String, JsonPrimitive> {
                    put("outcome", JsonPrimitive("ready"))
                    put("elapsed_ms", JsonPrimitive(elapsedMs))
                    event.engine?.takeIf { it.isNotBlank() }?.let {
                        put("engine", JsonPrimitive(sanitizeWebContainerTraceCode(it)))
                    }
                    put("sample_rate", JsonPrimitive(sampleRate))
                }
            RaftWebSpanRecord(
                traceId = ids.traceId,
                spanId = ids.spanId,
                name = MOBILE_WEB_CONTAINER_STARTUP_SPAN,
                status = "ok",
                startTime = raftEpochMillisUtcIso(startEpochMs),
                endTime = raftEpochMillisUtcIso(maxOf(startEpochMs, nowEpochMs)),
                attrs = JsonObject(attrs),
            )
        }

        is WebContainerTelemetryEvent.StartupFailed -> {
            val timestamp = raftEpochMillisUtcIso(nowEpochMs)
            val attrs =
                buildMap<String, JsonPrimitive> {
                    put("outcome", JsonPrimitive("failed"))
                    put("reason", JsonPrimitive(sanitizeWebContainerStartupReason(event.reason)))
                    event.engine?.takeIf { it.isNotBlank() }?.let {
                        put("engine", JsonPrimitive(sanitizeWebContainerTraceCode(it)))
                    }
                    put("sample_rate", JsonPrimitive(sampleRate))
                }
            RaftWebSpanRecord(
                traceId = ids.traceId,
                spanId = ids.spanId,
                name = MOBILE_WEB_CONTAINER_STARTUP_SPAN,
                status = "error",
                startTime = timestamp,
                endTime = timestamp,
                attrs = JsonObject(attrs),
            )
        }

        is WebContainerTelemetryEvent.PoolEvent -> {
            if (event.action !in TracedWebContainerPoolAnomalyActions) return null
            val timestamp = raftEpochMillisUtcIso(nowEpochMs)
            val attrs =
                buildMap<String, JsonPrimitive> {
                    put("action", JsonPrimitive(sanitizeWebContainerTraceCode(event.action)))
                    put("pool_size_after", JsonPrimitive(event.poolSizeAfter.coerceAtLeast(0)))
                    event.engine?.takeIf { it.isNotBlank() }?.let {
                        put("engine", JsonPrimitive(sanitizeWebContainerTraceCode(it)))
                    }
                    put("sample_rate", JsonPrimitive(sampleRate))
                }
            RaftWebSpanRecord(
                traceId = ids.traceId,
                spanId = ids.spanId,
                name = MOBILE_WEB_CONTAINER_POOL_ANOMALY_SPAN,
                status = "error",
                startTime = timestamp,
                endTime = timestamp,
                attrs = JsonObject(attrs),
            )
        }

        is WebContainerTelemetryEvent.NavigationBlocked,
        is WebContainerTelemetryEvent.JsbCallCompleted,
        is WebContainerTelemetryEvent.ResourceIntercepted,
        is WebContainerTelemetryEvent.IsolationScopeCleared,
        is WebContainerTelemetryEvent.MediaCapturePermission,
        is WebContainerTelemetryEvent.MainFrameNavigation,
        -> null
    }

private object WebContainerTraceClock {
    private val origin = TimeSource.Monotonic.markNow()

    fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds
}

/**
 * Rate-capped reporter turning high-signal [WebContainerTelemetryEvent]s into OTLP spans via
 * [emitSpan], gated on `trace_sample.mobile.web_container` (default `1.0`, #proj-mobile:#399).
 * Thread-safe and never throws.
 */
internal class RaftWebContainerTraceReporter(
    private val nowEpochMs: () -> Long = { ProcessRaftDatetimeFoundationOwner.nowEpochMillis() },
    private val nowMonotonicMs: () -> Long = WebContainerTraceClock::nowMs,
    private val newTraceIds: () -> RaftTraceIds = { newRaftTraceIds() },
    private val maxSpansPerWindow: Int = WEB_CONTAINER_TRACE_MAX_SPANS_PER_MINUTE,
    private val windowMs: Long = WEB_CONTAINER_TRACE_WINDOW_MS,
    private val canEmit: () -> Boolean = ::hasActiveWebContainerTraceServer,
    private val resolveSampleRate: () -> Double = ::resolveActiveWebContainerTraceSampleRate,
    private val random: () -> Double = { Random.nextDouble() },
    private val emitSpan: (RaftWebSpanRecord) -> Unit = ::emitRaftWebContainerSpan,
) {
    private val lock = SynchronizedObject()
    private val recentMonotonicMs = ArrayDeque<Long>()

    fun report(event: WebContainerTelemetryEvent): Boolean {
        val span =
            runCatching {
                if (!canEmit()) return false
                val rate = resolveSampleRate()
                if (!rate.isFinite() || rate <= 0.0) return false
                val clampedRate = rate.coerceAtMost(1.0)
                if (!traceSampled(clampedRate, random)) return false
                val epochMs = nowEpochMs()
                val candidate = buildWebContainerTraceSpan(event, newTraceIds(), epochMs, sampleRate = clampedRate) ?: return false
                if (!tryAcquireSlot()) return false
                candidate
            }.getOrNull() ?: return false
        return runCatching {
            emitSpan(span)
            true
        }.getOrDefault(false)
    }

    private fun tryAcquireSlot(): Boolean {
        if (maxSpansPerWindow <= 0) return false
        return synchronized(lock) {
            val now = nowMonotonicMs()
            val cutoff = now - windowMs
            while (recentMonotonicMs.isNotEmpty() && recentMonotonicMs.first() <= cutoff) {
                recentMonotonicMs.removeFirst()
            }
            if (recentMonotonicMs.size >= maxSpansPerWindow) {
                false
            } else {
                recentMonotonicMs.addLast(now)
                true
            }
        }
    }
}

internal object RaftWebContainerTrace {
    private val defaultReporter = RaftWebContainerTraceReporter()

    fun report(event: WebContainerTelemetryEvent) {
        defaultReporter.report(event)
    }
}

private fun activeWebContainerTraceServerId(): String? =
    runCatching {
        RaftDataStoreRegistry.currentOrNull()
            ?.authStore
            ?.readInMemorySessionSnapshot()
            ?.session
            ?.currentServerId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

private fun hasActiveWebContainerTraceServer(): Boolean = activeWebContainerTraceServerId() != null

private fun resolveActiveWebContainerTraceSampleRate(): Double {
    val serverId = activeWebContainerTraceServerId() ?: return TRACE_SAMPLE_ERROR_DEFAULT_RATE
    return resolveActiveServerTraceSampleRate(
        serverId = serverId,
        traceName = MOBILE_WEB_CONTAINER_TRACE_NAME,
        fallback = TRACE_SAMPLE_ERROR_DEFAULT_RATE,
    )
}

/**
 * Emits [span] under the currently signed-in shell's server via the in-memory session snapshot
 * (zero disk I/O, safe from Android main-thread WebView callbacks). Signed out: no-op. Never throws.
 */
internal fun emitRaftWebContainerSpan(span: RaftWebSpanRecord) {
    try {
        val serverId = activeWebContainerTraceServerId() ?: return
        RaftClientTraceEvents.emitSpan(serverId, span, emptyList())
    } catch (_: Throwable) {
        // Container telemetry must never affect WebView recovery or UI rendering.
    }
}
