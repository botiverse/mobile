@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app

import build.raft.app.data.MOBILE_WEBVIEW_TRACE_NAME
import build.raft.app.data.MobileUiErrorCode
import build.raft.app.data.MobileUiErrorSurface
import build.raft.app.data.MobileUiErrorTrace
import build.raft.app.data.ProcessRaftDatetimeFoundationOwner
import build.raft.app.data.RaftClientTraceEvents
import build.raft.app.data.RaftDataStoreRegistry
import build.raft.app.data.RaftWebSpanRecord
import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.data.defaultWebViewForegroundTraceSampleRate
import build.raft.app.data.defaultWebViewPrewarmTraceSampleRate
import build.raft.app.data.resolveActiveServerTraceSampleRate
import build.raft.app.data.resolveTraceSampleRate
import build.raft.app.data.traceSampleEvaluations
import build.raft.app.data.traceSampled
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.network.RaftTraceIds
import build.raft.app.network.newRaftTraceIds
import kotlin.random.Random
import kotlinx.atomicfu.atomic
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Unified WebView load telemetry (`#proj-mobile:#399`, `botiverse/slock#16971`):
 * emits `mobile.webview.load` spans (`otel.traces`) across both `sandbox_webview` and
 * `web_container` engines for foreground opens (`kind = foreground_open`) and background prewarms
 * (`kind = prewarm`).
 *
 * Sampling follows the deterministic-partition pattern (`docs/observability/trace-sampling.md`):
 * - Explicit `0` on `trace_sample.mobile.webview` (or parent key) disables emission completely.
 * - Any failure (`outcome != ok`) or user-visible error (`ui_error_shown = true`) emits at
 *   `sample_rate = 1.0`, `record_condition = "failure"`, and when `ui_error_shown = true` also
 *   emits a paired `mobile.ui_error` record (`surface = webview`, `request_trace_id = span.traceId`).
 * - Smooth `ok` loads sample at `trace_sample.mobile.webview` (release default `0.1` foreground /
 *   `0.02` prewarm; `1.0` alpha/debug) with `record_condition = "trace_sample"`.
 */
internal const val MOBILE_WEBVIEW_LOAD_SPAN_NAME: String = "mobile.webview.load"
internal const val MOBILE_WEBVIEW_RECORD_CONDITION_FAILURE: String = "failure"
internal const val MOBILE_WEBVIEW_RECORD_CONDITION_TRACE_SAMPLE: String = "trace_sample"

internal enum class MobileWebViewLoadKind(val wireValue: String) {
    ForegroundOpen("foreground_open"),
    Prewarm("prewarm"),
}

internal enum class MobileWebViewLoadSurface(val wireValue: String) {
    Artifact("artifact"),
    RaftApp("raft_app"),
    InAppBrowser("in_app_browser"),
    SettingsWebView("settings_webview"),
    PrewarmPool("prewarm_pool"),
}

internal enum class MobileWebViewLoadEngine(val wireValue: String) {
    SandboxWebView("sandbox_webview"),
    WebContainer("web_container"),
}

internal enum class MobileWebViewLoadOutcome(val wireValue: String) {
    Ok("ok"),
    Failed("failed"),
    Timeout("timeout"),
    CancelledForForeground("cancelled_for_foreground"),
}

internal enum class MobileWebViewLoadErrorCode(val wireValue: String) {
    None("none"),
    Timeout("timeout"),
    HttpError("http_error"),
    SslError("ssl_error"),
    NetworkError("network_error"),
    RenderProcessGone("render_process_gone"),
    WatchdogTerminal("watchdog_terminal"),
    Other("other"),
}

internal data class MobileWebViewSampleDecision(
    val sampleRate: Double,
    val recordCondition: String,
)

/** Maps internal [diagnosticsSurface] strings into the closed `mobile.webview.load` surface set. */
internal fun normalizeMobileWebViewSurface(
    diagnosticsSurface: String?,
    kind: MobileWebViewLoadKind = MobileWebViewLoadKind.ForegroundOpen,
): MobileWebViewLoadSurface {
    val clean = diagnosticsSurface?.trim()?.lowercase().orEmpty()
    if (kind == MobileWebViewLoadKind.Prewarm && (clean.isEmpty() || clean == RAFT_WEBVIEW_SURFACE_DEFAULT || "prewarm" in clean || "spare" in clean)) {
        return MobileWebViewLoadSurface.PrewarmPool
    }
    return when {
        "artifact" in clean -> MobileWebViewLoadSurface.Artifact
        "raft_app" in clean || "raft-app" in clean -> MobileWebViewLoadSurface.RaftApp
        "setting" in clean || "billing" in clean -> MobileWebViewLoadSurface.SettingsWebView
        "prewarm" in clean || "spare" in clean -> MobileWebViewLoadSurface.PrewarmPool
        else -> MobileWebViewLoadSurface.InAppBrowser
    }
}

/** Classifies a WebView load error reason into the closed `mobile.webview.load` `error_code` set. */
internal fun classifyMobileWebViewErrorCode(
    rawReason: String?,
    outcome: MobileWebViewLoadOutcome,
): MobileWebViewLoadErrorCode {
    if (outcome == MobileWebViewLoadOutcome.Ok) return MobileWebViewLoadErrorCode.None
    val clean = rawReason?.trim()?.lowercase().orEmpty()
    return when {
        outcome == MobileWebViewLoadOutcome.Timeout ||
            "timeout" in clean ||
            "timed_out" in clean ||
            "stalled" in clean ||
            "deadline" in clean -> MobileWebViewLoadErrorCode.Timeout
        "render" in clean ||
            "process_gone" in clean ||
            "terminated" in clean ||
            "crash" in clean -> MobileWebViewLoadErrorCode.RenderProcessGone
        "watchdog" in clean -> MobileWebViewLoadErrorCode.WatchdogTerminal
        "ssl" in clean ||
            "tls" in clean ||
            "cert" in clean -> MobileWebViewLoadErrorCode.SslError
        "http_" in clean ||
            "status_" in clean ||
            "404" in clean ||
            "500" in clean ||
            "502" in clean ||
            "503" in clean -> MobileWebViewLoadErrorCode.HttpError
        "network" in clean ||
            "dns" in clean ||
            "host_lookup" in clean ||
            "connect" in clean ||
            "offline" in clean ||
            "internet" in clean ||
            "nsurlerror" in clean ||
            "err_failed" in clean -> MobileWebViewLoadErrorCode.NetworkError
        outcome == MobileWebViewLoadOutcome.CancelledForForeground -> MobileWebViewLoadErrorCode.Other
        else -> MobileWebViewLoadErrorCode.Other
    }
}

/** Maps a `mobile.webview.load` error code to the paired `mobile.ui_error` error code. */
internal fun mobileWebViewErrorCodeToUiErrorCode(errorCode: MobileWebViewLoadErrorCode): MobileUiErrorCode =
    when (errorCode) {
        MobileWebViewLoadErrorCode.Timeout -> MobileUiErrorCode.WebViewTimeout
        MobileWebViewLoadErrorCode.SslError -> MobileUiErrorCode.WebViewSslError
        MobileWebViewLoadErrorCode.RenderProcessGone -> MobileUiErrorCode.WebViewRenderGone
        MobileWebViewLoadErrorCode.None,
        MobileWebViewLoadErrorCode.HttpError,
        MobileWebViewLoadErrorCode.NetworkError,
        MobileWebViewLoadErrorCode.WatchdogTerminal,
        MobileWebViewLoadErrorCode.Other,
        -> MobileUiErrorCode.WebViewLoadFailed
    }

/** Deterministic-partition sampling decision for `mobile.webview.load`. */
internal fun decideMobileWebViewTraceSampling(
    kind: MobileWebViewLoadKind,
    outcome: MobileWebViewLoadOutcome,
    uiErrorShown: Boolean,
    flags: ServerFeatureFlagSnapshot,
    fallbackRate: Double =
        if (kind == MobileWebViewLoadKind.Prewarm) {
            defaultWebViewPrewarmTraceSampleRate()
        } else {
            defaultWebViewForegroundTraceSampleRate()
        },
    random: () -> Double = { Random.nextDouble() },
): MobileWebViewSampleDecision? {
    val baseRate =
        resolveTraceSampleRate(
            traceName = MOBILE_WEBVIEW_TRACE_NAME,
            evaluations = flags.traceSampleEvaluations(MOBILE_WEBVIEW_TRACE_NAME),
            fallback = fallbackRate,
        )
    return decideMobileWebViewTraceSamplingFromRate(
        baseRate = baseRate,
        outcome = outcome,
        uiErrorShown = uiErrorShown,
        random = random,
    )
}

internal fun decideMobileWebViewTraceSamplingFromRate(
    baseRate: Double,
    outcome: MobileWebViewLoadOutcome,
    uiErrorShown: Boolean,
    random: () -> Double = { Random.nextDouble() },
): MobileWebViewSampleDecision? {
    if (!baseRate.isFinite() || baseRate <= 0.0) return null
    val clampedBase = baseRate.coerceAtMost(1.0)
    if (outcome != MobileWebViewLoadOutcome.Ok || uiErrorShown) {
        return MobileWebViewSampleDecision(
            sampleRate = 1.0,
            recordCondition = MOBILE_WEBVIEW_RECORD_CONDITION_FAILURE,
        )
    }
    return if (traceSampled(clampedBase, random)) {
        MobileWebViewSampleDecision(
            sampleRate = clampedBase,
            recordCondition = MOBILE_WEBVIEW_RECORD_CONDITION_TRACE_SAMPLE,
        )
    } else {
        null
    }
}

internal class MobileWebViewLoadTraceRecorder(
    private val nowEpochMs: () -> Long = { ProcessRaftDatetimeFoundationOwner.nowEpochMillis() },
    private val resolveServerId: () -> String? = ::activeWebViewLoadTraceServerId,
    private val resolveBaseRate: (serverId: String, kind: MobileWebViewLoadKind) -> Double = { serverId, kind ->
        val fallback =
            if (kind == MobileWebViewLoadKind.Prewarm) {
                defaultWebViewPrewarmTraceSampleRate()
            } else {
                defaultWebViewForegroundTraceSampleRate()
            }
        resolveActiveServerTraceSampleRate(serverId, MOBILE_WEBVIEW_TRACE_NAME, fallback)
    },
    private val random: () -> Double = { Random.nextDouble() },
    private val newTraceIds: () -> RaftTraceIds = { newRaftTraceIds() },
    private val emitSpan: (serverId: String, span: RaftWebSpanRecord) -> Unit = { serverId, span ->
        RaftClientTraceEvents.emitSpan(serverId, span, emptyList())
    },
    private val emitUiError: (
        serverId: String,
        route: String,
        errorCode: MobileUiErrorCode,
        traceId: String,
    ) -> Unit = { serverId, route, errorCode, traceId ->
        MobileUiErrorTrace.record(
            surface = MobileUiErrorSurface.EmbeddedWebView,
            route = route,
            errorCode = errorCode,
            requestTraceId = traceId,
            serverIdOverride = serverId,
        )
    },
) {
    fun record(
        kind: MobileWebViewLoadKind,
        surface: MobileWebViewLoadSurface,
        engine: MobileWebViewLoadEngine,
        outcome: MobileWebViewLoadOutcome,
        errorCode: MobileWebViewLoadErrorCode,
        prewarmHit: Boolean? = null,
        uiErrorShown: Boolean = false,
        startedAtEpochMs: Long,
        endedAtEpochMs: Long = nowEpochMs(),
    ): Boolean {
        val serverId = resolveServerId()?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val baseRate = resolveBaseRate(serverId, kind)
        val decision = decideMobileWebViewTraceSamplingFromRate(baseRate, outcome, uiErrorShown, random) ?: return false
        val ids = newTraceIds()
        val safeStart = startedAtEpochMs.coerceAtLeast(0L)
        val safeEnd = maxOf(safeStart, endedAtEpochMs)
        val attrs =
            buildMap<String, JsonPrimitive> {
                put("kind", JsonPrimitive(kind.wireValue))
                put("surface", JsonPrimitive(surface.wireValue))
                put("engine", JsonPrimitive(engine.wireValue))
                put("outcome", JsonPrimitive(outcome.wireValue))
                put("error_code", JsonPrimitive(errorCode.wireValue))
                if (kind == MobileWebViewLoadKind.ForegroundOpen && prewarmHit != null) {
                    put("prewarm_hit", JsonPrimitive(prewarmHit))
                }
                put("ui_error_shown", JsonPrimitive(uiErrorShown))
                put("sample_rate", JsonPrimitive(decision.sampleRate))
                put("record_condition", JsonPrimitive(decision.recordCondition))
            }
        val span =
            RaftWebSpanRecord(
                traceId = ids.traceId,
                spanId = ids.spanId,
                name = MOBILE_WEBVIEW_LOAD_SPAN_NAME,
                status = if (outcome == MobileWebViewLoadOutcome.Ok && !uiErrorShown) "ok" else "error",
                startTime = raftEpochMillisUtcIso(safeStart),
                endTime = raftEpochMillisUtcIso(safeEnd),
                attrs = JsonObject(attrs),
            )
        val emitted =
            runCatching {
                emitSpan(serverId, span)
                true
            }.getOrDefault(false)
        if (emitted && uiErrorShown) {
            runCatching {
                emitUiError(
                    serverId,
                    surface.wireValue,
                    mobileWebViewErrorCodeToUiErrorCode(errorCode),
                    ids.traceId,
                )
            }
        }
        return emitted
    }
}

/**
 * Single-emission handle for one WebView load attempt (foreground open or prewarm). Calling
 * [finish] more than once is a no-op so overlapping watchdog + platform callbacks emit once.
 */
internal class MobileWebViewLoadAttemptHandle(
    private val kind: MobileWebViewLoadKind,
    private val surface: MobileWebViewLoadSurface,
    private val engine: MobileWebViewLoadEngine,
    private val prewarmHit: Boolean?,
    private val startedAtEpochMs: Long,
    private val recorder: MobileWebViewLoadTraceRecorder = MobileWebViewLoadTrace.defaultRecorder,
) {
    private val finished = atomic(false)

    fun finish(
        outcome: MobileWebViewLoadOutcome,
        errorCode: MobileWebViewLoadErrorCode =
            if (outcome == MobileWebViewLoadOutcome.Ok) MobileWebViewLoadErrorCode.None else MobileWebViewLoadErrorCode.Other,
        uiErrorShown: Boolean = false,
    ): Boolean {
        if (!finished.compareAndSet(expect = false, update = true)) return false
        return runCatching {
            recorder.record(
                kind = kind,
                surface = surface,
                engine = engine,
                outcome = outcome,
                errorCode = errorCode,
                prewarmHit = prewarmHit,
                uiErrorShown = uiErrorShown,
                startedAtEpochMs = startedAtEpochMs,
            )
        }.getOrDefault(false)
    }
}

internal object MobileWebViewLoadTrace {
    internal val defaultRecorder = MobileWebViewLoadTraceRecorder()

    fun startForegroundOpen(
        diagnosticsSurface: String?,
        useWebContainer: Boolean,
        prewarmHit: Boolean,
        startedAtEpochMs: Long = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
    ): MobileWebViewLoadAttemptHandle =
        MobileWebViewLoadAttemptHandle(
            kind = MobileWebViewLoadKind.ForegroundOpen,
            surface = normalizeMobileWebViewSurface(diagnosticsSurface, MobileWebViewLoadKind.ForegroundOpen),
            engine = if (useWebContainer) MobileWebViewLoadEngine.WebContainer else MobileWebViewLoadEngine.SandboxWebView,
            prewarmHit = prewarmHit,
            startedAtEpochMs = startedAtEpochMs,
            recorder = defaultRecorder,
        )

    fun startPrewarm(
        diagnosticsSurface: String?,
        useWebContainer: Boolean,
        startedAtEpochMs: Long = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
    ): MobileWebViewLoadAttemptHandle =
        MobileWebViewLoadAttemptHandle(
            kind = MobileWebViewLoadKind.Prewarm,
            surface = normalizeMobileWebViewSurface(diagnosticsSurface, MobileWebViewLoadKind.Prewarm),
            engine = if (useWebContainer) MobileWebViewLoadEngine.WebContainer else MobileWebViewLoadEngine.SandboxWebView,
            prewarmHit = null,
            startedAtEpochMs = startedAtEpochMs,
            recorder = defaultRecorder,
        )
}

private fun activeWebViewLoadTraceServerId(): String? =
    runCatching {
        RaftDataStoreRegistry.currentOrNull()
            ?.authStore
            ?.readInMemorySessionSnapshot()
            ?.session
            ?.currentServerId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
