@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app

import build.raft.app.data.MOBILE_WEBVIEW_TRACE_NAME
import build.raft.app.data.MobileUiErrorCode
import build.raft.app.data.RaftWebSpanRecord
import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.model.ServerId
import build.raft.app.network.RaftTraceIds
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

class MobileWebViewLoadTraceTest {
    private val ids = RaftTraceIds(traceId = "4bf92f3577b34da6a3ce929d0e0e4736", spanId = "00f067aa0ba902b7")
    private val baseEpochMs = 1_795_500_000_000L

    private fun RaftWebSpanRecord.str(key: String): String? = attrs[key]?.jsonPrimitive?.content

    @Test
    fun surfaceAndErrorCodeNormalizationCoverClosedRegistrySets() {
        assertEquals(
            setOf("artifact", "raft_app", "in_app_browser", "settings_webview", "prewarm_pool"),
            MobileWebViewLoadSurface.entries.map { it.wireValue }.toSet(),
        )
        assertEquals(
            setOf("ok", "failed", "timeout", "cancelled_for_foreground"),
            MobileWebViewLoadOutcome.entries.map { it.wireValue }.toSet(),
        )
        assertEquals(
            setOf("none", "timeout", "http_error", "ssl_error", "network_error", "render_process_gone", "watchdog_terminal", "other"),
            MobileWebViewLoadErrorCode.entries.map { it.wireValue }.toSet(),
        )

        assertEquals(MobileWebViewLoadSurface.Artifact, normalizeMobileWebViewSurface("artifact_viewer"))
        assertEquals(MobileWebViewLoadSurface.RaftApp, normalizeMobileWebViewSurface("raft_app"))
        assertEquals(MobileWebViewLoadSurface.SettingsWebView, normalizeMobileWebViewSurface("settings_marketplace"))
        assertEquals(MobileWebViewLoadSurface.InAppBrowser, normalizeMobileWebViewSurface("web_view"))
        assertEquals(
            MobileWebViewLoadSurface.PrewarmPool,
            normalizeMobileWebViewSurface("web_view", MobileWebViewLoadKind.Prewarm),
        )
        assertEquals(
            MobileWebViewLoadSurface.Artifact,
            normalizeMobileWebViewSurface("artifact_viewer", MobileWebViewLoadKind.Prewarm),
        )

        assertEquals(MobileWebViewLoadErrorCode.None, classifyMobileWebViewErrorCode("anything", MobileWebViewLoadOutcome.Ok))
        assertEquals(MobileWebViewLoadErrorCode.Timeout, classifyMobileWebViewErrorCode("stalled", MobileWebViewLoadOutcome.Timeout))
        assertEquals(MobileWebViewLoadErrorCode.RenderProcessGone, classifyMobileWebViewErrorCode("render_process_gone", MobileWebViewLoadOutcome.Failed))
        assertEquals(MobileWebViewLoadErrorCode.WatchdogTerminal, classifyMobileWebViewErrorCode("watchdog_terminal", MobileWebViewLoadOutcome.Failed))
        assertEquals(MobileWebViewLoadErrorCode.SslError, classifyMobileWebViewErrorCode("ssl_error_3", MobileWebViewLoadOutcome.Failed))
        assertEquals(MobileWebViewLoadErrorCode.HttpError, classifyMobileWebViewErrorCode("http_error", MobileWebViewLoadOutcome.Failed))
        assertEquals(MobileWebViewLoadErrorCode.NetworkError, classifyMobileWebViewErrorCode("err_failed", MobileWebViewLoadOutcome.Failed))
        assertEquals(MobileWebViewLoadErrorCode.Other, classifyMobileWebViewErrorCode("prewarm_aborted", MobileWebViewLoadOutcome.CancelledForForeground))

        assertEquals(MobileUiErrorCode.WebViewTimeout, mobileWebViewErrorCodeToUiErrorCode(MobileWebViewLoadErrorCode.Timeout))
        assertEquals(MobileUiErrorCode.WebViewSslError, mobileWebViewErrorCodeToUiErrorCode(MobileWebViewLoadErrorCode.SslError))
        assertEquals(MobileUiErrorCode.WebViewRenderGone, mobileWebViewErrorCodeToUiErrorCode(MobileWebViewLoadErrorCode.RenderProcessGone))
        assertEquals(MobileUiErrorCode.WebViewLoadFailed, mobileWebViewErrorCodeToUiErrorCode(MobileWebViewLoadErrorCode.HttpError))
    }

    @Test
    fun foregroundOpenCarriesPrewarmHitWhilePrewarmOmitsIt() {
        val spans = mutableListOf<RaftWebSpanRecord>()
        val uiErrors = mutableListOf<Triple<String, MobileUiErrorCode, String>>()
        val recorder =
            MobileWebViewLoadTraceRecorder(
                nowEpochMs = { baseEpochMs + 420L },
                resolveServerId = { "srv-1" },
                resolveBaseRate = { _, kind -> if (kind == MobileWebViewLoadKind.Prewarm) 0.02 else 0.1 },
                random = { 0.01 },
                newTraceIds = { ids },
                emitSpan = { _, span -> spans += span },
                emitUiError = { _, route, code, traceId -> uiErrors += Triple(route, code, traceId) },
            )

        assertTrue(
            recorder.record(
                kind = MobileWebViewLoadKind.ForegroundOpen,
                surface = MobileWebViewLoadSurface.Artifact,
                engine = MobileWebViewLoadEngine.WebContainer,
                outcome = MobileWebViewLoadOutcome.Ok,
                errorCode = MobileWebViewLoadErrorCode.None,
                prewarmHit = true,
                uiErrorShown = false,
                startedAtEpochMs = baseEpochMs,
            ),
        )
        val fg = spans.single()
        assertEquals(MOBILE_WEBVIEW_LOAD_SPAN_NAME, fg.name)
        assertEquals("ok", fg.status)
        assertEquals("foreground_open", fg.str("kind"))
        assertEquals("artifact", fg.str("surface"))
        assertEquals("web_container", fg.str("engine"))
        assertEquals("ok", fg.str("outcome"))
        assertEquals("none", fg.str("error_code"))
        assertEquals(true, fg.attrs.getValue("prewarm_hit").jsonPrimitive.boolean)
        assertEquals(false, fg.attrs.getValue("ui_error_shown").jsonPrimitive.boolean)
        assertEquals(0.1, fg.attrs.getValue("sample_rate").jsonPrimitive.double)
        assertEquals(MOBILE_WEBVIEW_RECORD_CONDITION_TRACE_SAMPLE, fg.str("record_condition"))
        assertTrue(uiErrors.isEmpty())

        assertTrue(
            recorder.record(
                kind = MobileWebViewLoadKind.Prewarm,
                surface = MobileWebViewLoadSurface.PrewarmPool,
                engine = MobileWebViewLoadEngine.SandboxWebView,
                outcome = MobileWebViewLoadOutcome.CancelledForForeground,
                errorCode = MobileWebViewLoadErrorCode.Other,
                prewarmHit = false,
                uiErrorShown = false,
                startedAtEpochMs = baseEpochMs,
            ),
        )
        val prewarm = spans[1]
        assertEquals("error", prewarm.status)
        assertEquals("prewarm", prewarm.str("kind"))
        assertEquals("prewarm_pool", prewarm.str("surface"))
        assertEquals("sandbox_webview", prewarm.str("engine"))
        assertEquals("cancelled_for_foreground", prewarm.str("outcome"))
        assertNull(prewarm.attrs["prewarm_hit"])
        assertEquals(1.0, prewarm.attrs.getValue("sample_rate").jsonPrimitive.double)
        assertEquals(MOBILE_WEBVIEW_RECORD_CONDITION_FAILURE, prewarm.str("record_condition"))
        assertTrue(uiErrors.isEmpty())
    }

    @Test
    fun attemptHandleEmitsAtMostOnceAcrossOverlappingCallbacks() {
        val spans = mutableListOf<RaftWebSpanRecord>()
        val uiErrors = mutableListOf<MobileUiErrorCode>()
        val recorder =
            MobileWebViewLoadTraceRecorder(
                nowEpochMs = { baseEpochMs + 150L },
                resolveServerId = { "srv-1" },
                resolveBaseRate = { _, _ -> 1.0 },
                newTraceIds = { ids },
                emitSpan = { _, span -> spans += span },
                emitUiError = { _, _, code, _ -> uiErrors += code },
            )
        val handle =
            MobileWebViewLoadAttemptHandle(
                kind = MobileWebViewLoadKind.ForegroundOpen,
                surface = MobileWebViewLoadSurface.SettingsWebView,
                engine = MobileWebViewLoadEngine.SandboxWebView,
                prewarmHit = false,
                startedAtEpochMs = baseEpochMs,
                recorder = recorder,
            )

        assertTrue(handle.finish(MobileWebViewLoadOutcome.Ok, MobileWebViewLoadErrorCode.None, uiErrorShown = false))
        assertFalse(handle.finish(MobileWebViewLoadOutcome.Timeout, MobileWebViewLoadErrorCode.Timeout, uiErrorShown = true))
        assertFalse(handle.finish(MobileWebViewLoadOutcome.Failed, MobileWebViewLoadErrorCode.RenderProcessGone, uiErrorShown = true))

        assertEquals(1, spans.size)
        assertEquals("ok", spans.single().str("outcome"))
        assertTrue(uiErrors.isEmpty())
    }

    @Test
    fun deterministicFailurePartitionSamplesSmoothLoadsAndHonorsZeroKillSwitch() {
        val unresolved = ServerFeatureFlagSnapshot.Unresolved

        // Smooth foreground ok uses 0.1 release fallback; smooth prewarm ok uses 0.02.
        assertEquals(
            MobileWebViewSampleDecision(0.1, MOBILE_WEBVIEW_RECORD_CONDITION_TRACE_SAMPLE),
            decideMobileWebViewTraceSampling(
                kind = MobileWebViewLoadKind.ForegroundOpen,
                outcome = MobileWebViewLoadOutcome.Ok,
                uiErrorShown = false,
                flags = unresolved,
                fallbackRate = 0.1,
                random = { 0.05 },
            ),
        )
        assertNull(
            decideMobileWebViewTraceSampling(
                kind = MobileWebViewLoadKind.Prewarm,
                outcome = MobileWebViewLoadOutcome.Ok,
                uiErrorShown = false,
                flags = unresolved,
                fallbackRate = 0.02,
                random = { 0.05 },
            ),
        )

        // Any failure or uiErrorShown boosts to sample_rate = 1.0 with record_condition = failure.
        assertEquals(
            MobileWebViewSampleDecision(1.0, MOBILE_WEBVIEW_RECORD_CONDITION_FAILURE),
            decideMobileWebViewTraceSampling(
                kind = MobileWebViewLoadKind.ForegroundOpen,
                outcome = MobileWebViewLoadOutcome.Failed,
                uiErrorShown = true,
                flags = unresolved,
                fallbackRate = 0.1,
                random = { 0.99 },
            ),
        )
        assertEquals(
            MobileWebViewSampleDecision(1.0, MOBILE_WEBVIEW_RECORD_CONDITION_FAILURE),
            decideMobileWebViewTraceSampling(
                kind = MobileWebViewLoadKind.Prewarm,
                outcome = MobileWebViewLoadOutcome.Timeout,
                uiErrorShown = false,
                flags = unresolved,
                fallbackRate = 0.02,
                random = { 0.99 },
            ),
        )

        // Flag override on trace_sample.mobile.webview applies to smooth loads.
        val flagged =
            ServerFeatureFlagSnapshot(
                serverId = ServerId("srv-1"),
                resolved = true,
                values = mapOf(
                    "trace_sample.mobile" to true,
                    "trace_sample.$MOBILE_WEBVIEW_TRACE_NAME" to true,
                ),
                reasons = mapOf(
                    "trace_sample.mobile" to "platform_rule",
                    "trace_sample.$MOBILE_WEBVIEW_TRACE_NAME" to "platform_rule",
                ),
                variants = mapOf(
                    "trace_sample.mobile" to "0.5",
                    "trace_sample.$MOBILE_WEBVIEW_TRACE_NAME" to "0.25",
                ),
            )
        assertEquals(
            MobileWebViewSampleDecision(0.25, MOBILE_WEBVIEW_RECORD_CONDITION_TRACE_SAMPLE),
            decideMobileWebViewTraceSampling(
                kind = MobileWebViewLoadKind.ForegroundOpen,
                outcome = MobileWebViewLoadOutcome.Ok,
                uiErrorShown = false,
                flags = flagged,
                fallbackRate = 0.1,
                random = { 0.2 },
            ),
        )

        // Explicit 0 / kill_switch suppresses everything, including failures.
        val killed =
            ServerFeatureFlagSnapshot(
                serverId = ServerId("srv-1"),
                resolved = true,
                values = mapOf("trace_sample.$MOBILE_WEBVIEW_TRACE_NAME" to false),
                reasons = mapOf("trace_sample.$MOBILE_WEBVIEW_TRACE_NAME" to "kill_switch"),
                variants = emptyMap(),
            )
        assertNull(
            decideMobileWebViewTraceSampling(
                kind = MobileWebViewLoadKind.ForegroundOpen,
                outcome = MobileWebViewLoadOutcome.Failed,
                uiErrorShown = true,
                flags = killed,
                fallbackRate = 1.0,
                random = { 0.0 },
            ),
        )
    }

    @Test
    fun uiErrorShownEmitsPairedMobileUiErrorLinkedBySpanTraceId() {
        val spans = mutableListOf<RaftWebSpanRecord>()
        val uiErrors = mutableListOf<Triple<String, MobileUiErrorCode, String>>()
        var baseRate = 0.1
        val recorder =
            MobileWebViewLoadTraceRecorder(
                nowEpochMs = { baseEpochMs + 1_200L },
                resolveServerId = { "srv-1" },
                resolveBaseRate = { _, _ -> baseRate },
                random = { 0.99 },
                newTraceIds = { ids },
                emitSpan = { _, span -> spans += span },
                emitUiError = { _, route, code, traceId -> uiErrors += Triple(route, code, traceId) },
            )

        assertTrue(
            recorder.record(
                kind = MobileWebViewLoadKind.ForegroundOpen,
                surface = MobileWebViewLoadSurface.InAppBrowser,
                engine = MobileWebViewLoadEngine.SandboxWebView,
                outcome = MobileWebViewLoadOutcome.Timeout,
                errorCode = MobileWebViewLoadErrorCode.Timeout,
                prewarmHit = false,
                uiErrorShown = true,
                startedAtEpochMs = baseEpochMs,
            ),
        )
        assertEquals(1, spans.size)
        val span = spans.single()
        assertEquals("error", span.status)
        assertEquals(true, span.attrs.getValue("ui_error_shown").jsonPrimitive.boolean)
        assertEquals(1.0, span.attrs.getValue("sample_rate").jsonPrimitive.double)
        assertEquals(MOBILE_WEBVIEW_RECORD_CONDITION_FAILURE, span.str("record_condition"))
        assertEquals(listOf(Triple("in_app_browser", MobileUiErrorCode.WebViewTimeout, ids.traceId)), uiErrors)

        // When killed via trace_sample.mobile.webview = 0, neither span nor paired ui_error emits.
        spans.clear()
        uiErrors.clear()
        baseRate = 0.0
        assertFalse(
            recorder.record(
                kind = MobileWebViewLoadKind.ForegroundOpen,
                surface = MobileWebViewLoadSurface.InAppBrowser,
                engine = MobileWebViewLoadEngine.SandboxWebView,
                outcome = MobileWebViewLoadOutcome.Failed,
                errorCode = MobileWebViewLoadErrorCode.RenderProcessGone,
                prewarmHit = false,
                uiErrorShown = true,
                startedAtEpochMs = baseEpochMs,
            ),
        )
        assertTrue(spans.isEmpty())
        assertTrue(uiErrors.isEmpty())
    }
}
