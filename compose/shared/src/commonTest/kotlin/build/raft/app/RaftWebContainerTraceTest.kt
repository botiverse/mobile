@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app

import build.raft.app.data.RaftWebSpanRecord
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.network.RaftTraceIds
import build.raft.app.thread.key
import build.raft.webcontainer.WebIsolationScope
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebIsolationClearResult
import build.raft.webcontainer.resource.WebResourceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class RaftWebContainerTraceTest {
    private val ids = RaftTraceIds(traceId = "4bf92f3577b34da6a3ce929d0e0e4736", spanId = "00f067aa0ba902b7")
    private val epochMs = 1_795_500_000_000L

    private fun RaftWebSpanRecord.str(key: String): String? = attrs[key]?.jsonPrimitive?.content

    @Test
    fun renderProcessGoneMapsCrashReclaimParkedAndPriorityWithoutLeakingSecrets() {
        val crashed =
            assertNotNull(
                buildWebContainerTraceSpan(
                    event =
                        WebContainerTelemetryEvent.RenderProcessGone(
                            surfaceId = "artifact_viewer",
                            didCrash = true,
                            recovered = true,
                            attempt = 1,
                            rendererPriorityAtExit = 2,
                            parked = false,
                            engine = "android_webview",
                        ),
                    ids = ids,
                    nowEpochMs = epochMs,
                ),
            )
        assertEquals(MOBILE_WEB_CONTAINER_RENDER_PROCESS_GONE_SPAN, crashed.name)
        assertEquals("error", crashed.status)
        assertEquals("artifact_viewer", crashed.str("surface"))
        assertEquals(true, crashed.attrs.getValue("did_crash").jsonPrimitive.boolean)
        assertEquals(true, crashed.attrs.getValue("recovered").jsonPrimitive.boolean)
        assertEquals(1, crashed.attrs.getValue("attempt").jsonPrimitive.int)
        assertEquals(false, crashed.attrs.getValue("parked").jsonPrimitive.boolean)
        assertEquals("android_webview", crashed.str("engine"))
        assertEquals("important", crashed.str("renderer_priority"))
        assertEquals(1.0, crashed.attrs.getValue("sample_rate").jsonPrimitive.double)

        // Recovered OS reclaim is recorded with status="ok" so crash rate and reclaim rate stay separable.
        val reclaimed =
            assertNotNull(
                buildWebContainerTraceSpan(
                    event =
                        WebContainerTelemetryEvent.RenderProcessGone(
                            surfaceId = "settings_web_view",
                            didCrash = false,
                            recovered = true,
                            attempt = 0,
                            rendererPriorityAtExit = 0,
                            parked = true,
                            engine = "android_webview",
                        ),
                    ids = ids,
                    nowEpochMs = epochMs,
                ),
            )
        assertEquals("ok", reclaimed.status)
        assertEquals(false, reclaimed.attrs.getValue("did_crash").jsonPrimitive.boolean)
        assertEquals(true, reclaimed.attrs.getValue("parked").jsonPrimitive.boolean)
        assertEquals("waived", reclaimed.str("renderer_priority"))

        // Unrecovered OS kill (e.g. killed during load) is still status="error".
        val killedUnrecovered =
            assertNotNull(
                buildWebContainerTraceSpan(
                    event =
                        WebContainerTelemetryEvent.RenderProcessGone(
                            surfaceId = "https://secret.example/page?token=abc",
                            didCrash = false,
                            recovered = false,
                            attempt = 0,
                            engine = "ios_wkwebview",
                        ),
                    ids = ids,
                    nowEpochMs = epochMs,
                ),
            )
        assertEquals("error", killedUnrecovered.status)
        assertEquals("other", killedUnrecovered.str("surface"))
        assertFalse(killedUnrecovered.attrs.toString().contains("secret.example"))
        assertFalse(killedUnrecovered.attrs.toString().contains("token=abc"))
    }

    @Test
    fun watchdogTerminalAndStartupSpansPreserveDurationsAndSanitizeFreeTextReasons() {
        val fatalWatchdog =
            assertNotNull(
                buildWebContainerTraceSpan(
                    event =
                        WebContainerTelemetryEvent.WatchdogTerminalError(
                            surfaceId = "artifact_viewer",
                            reason = "render_process_circuit_open",
                            engine = "macos_wkwebview",
                        ),
                    ids = ids,
                    nowEpochMs = epochMs,
                ),
            )
        assertEquals(MOBILE_WEB_CONTAINER_WATCHDOG_TERMINAL_SPAN, fatalWatchdog.name)
        assertEquals("error", fatalWatchdog.status)
        assertEquals(true, fatalWatchdog.attrs.getValue("fatal").jsonPrimitive.boolean)
        assertEquals("render_process_circuit_open", fatalWatchdog.str("reason"))
        assertEquals("macos_wkwebview", fatalWatchdog.str("engine"))

        val softWatchdog =
            assertNotNull(
                buildWebContainerTraceSpan(
                    event =
                        WebContainerTelemetryEvent.WatchdogTerminalError(
                            surfaceId = "in_app_browser",
                            reason = "blank_screen_detected",
                            engine = "ios_wkwebview",
                        ),
                    ids = ids,
                    nowEpochMs = epochMs,
                ),
            )
        assertEquals("error", softWatchdog.status)
        assertEquals(false, softWatchdog.attrs.getValue("fatal").jsonPrimitive.boolean)

        val startupReady =
            assertNotNull(
                buildWebContainerTraceSpan(
                    event = WebContainerTelemetryEvent.StartupCompleted(elapsedMs = 240L, engine = "android_webview"),
                    ids = ids,
                    nowEpochMs = epochMs,
                ),
            )
        assertEquals(MOBILE_WEB_CONTAINER_STARTUP_SPAN, startupReady.name)
        assertEquals("ok", startupReady.status)
        assertEquals("ready", startupReady.str("outcome"))
        assertEquals(240L, startupReady.attrs.getValue("elapsed_ms").jsonPrimitive.long)
        assertEquals(raftEpochMillisUtcIso(epochMs - 240L), startupReady.startTime)
        assertEquals(raftEpochMillisUtcIso(epochMs), startupReady.endTime)

        val startupFailedWithClass =
            assertNotNull(
                buildWebContainerTraceSpan(
                    event =
                        WebContainerTelemetryEvent.StartupFailed(
                            reason = "MissingWebViewPackageException: WebView updating https://secret.example/?k=1",
                            engine = "android_webview",
                        ),
                    ids = ids,
                    nowEpochMs = epochMs,
                ),
            )
        assertEquals("error", startupFailedWithClass.status)
        assertEquals("failed", startupFailedWithClass.str("outcome"))
        assertEquals("MissingWebViewPackageException", startupFailedWithClass.str("reason"))
        assertFalse(startupFailedWithClass.attrs.toString().contains("secret.example"))

        val startupFailedUrlOnly =
            assertNotNull(
                buildWebContainerTraceSpan(
                    event =
                        WebContainerTelemetryEvent.StartupFailed(
                            reason = "https://secret.example/path?token=xyz",
                            engine = "android_webview",
                        ),
                    ids = ids,
                    nowEpochMs = epochMs,
                ),
            )
        assertEquals("other", startupFailedUrlOnly.str("reason"))
        assertFalse(startupFailedUrlOnly.attrs.toString().contains("secret.example"))

        // Host:port and prose messages collapse to "other" without leaking hostnames or words.
        assertEquals("other", sanitizeWebContainerStartupReason("secret.example:8443/path"))
        assertEquals("other", sanitizeWebContainerStartupReason("secret.example:8443: handshake failed"))
        assertEquals("other", sanitizeWebContainerStartupReason("Failed to load WebView provider: No WebView installed"))
        assertEquals("desktop_engine_unavailable", sanitizeWebContainerStartupReason("desktop_engine_unavailable"))
        assertEquals("RejectedExecutionException", sanitizeWebContainerStartupReason("RejectedExecutionException"))
    }

    @Test
    fun poolAnomaliesTraceWithoutKeepAliveKeyWhileRoutineEventsStayLocalOnly() {
        for (action in TracedWebContainerPoolAnomalyActions) {
            val span =
                assertNotNull(
                    buildWebContainerTraceSpan(
                        event =
                            WebContainerTelemetryEvent.PoolEvent(
                                action = action,
                                keepAliveKey = "artifact:secret-room-id-999",
                                poolSizeAfter = 1,
                                engine = "ios_wkwebview",
                            ),
                        ids = ids,
                        nowEpochMs = epochMs,
                    ),
                    "anomaly action $action must produce a trace span",
                )
            assertEquals(MOBILE_WEB_CONTAINER_POOL_ANOMALY_SPAN, span.name)
            assertEquals("error", span.status)
            assertEquals(action, span.str("action"))
            assertEquals(1, span.attrs.getValue("pool_size_after").jsonPrimitive.int)
            assertFalse(span.attrs.toString().contains("secret-room-id-999"), "keepAliveKey must never leak into OTLP attrs")
        }

        // Routine high-volume events stay local-log only (never spam /api/web-traces).
        val ignored =
            listOf(
                WebContainerTelemetryEvent.PoolEvent("parked", "k1", 1, "android_webview"),
                WebContainerTelemetryEvent.PoolEvent("reused", "k1", 0, "android_webview"),
                WebContainerTelemetryEvent.PoolEvent("ios_spare_hit", "ios_default_spare", 0, "ios_wkwebview"),
                WebContainerTelemetryEvent.NavigationBlocked("in_app_browser", "https://evil.example", "origin_locked"),
                WebContainerTelemetryEvent.JsbCallCompleted("settings", "app.info", true, null, 2L),
                WebContainerTelemetryEvent.ResourceIntercepted("artifact_viewer", "https://app.raft.build/a.js", true, WebResourceSource.OFFLINE_BUNDLE, null),
                WebContainerTelemetryEvent.IsolationScopeCleared(WebIsolationScope.Default, 1, WebIsolationClearResult.CLEARED),
                WebContainerTelemetryEvent.MediaCapturePermission("in_app_browser", "Microphone", true, null, emptyList(), "https://app.raft.build", true),
            )
        for (event in ignored) {
            assertNull(buildWebContainerTraceSpan(event, ids, epochMs), "routine event $event must not emit OTLP span")
        }
    }

    @Test
    fun reporterEnforcesSlidingWindowBurstCapAndSwallowsEmitterErrors() {
        var monotonicMs = 10_000L
        var signedIn = false
        val emitted = mutableListOf<RaftWebSpanRecord>()
        val reporter =
            RaftWebContainerTraceReporter(
                nowEpochMs = { epochMs + monotonicMs },
                nowMonotonicMs = { monotonicMs },
                newTraceIds = { ids },
                maxSpansPerWindow = 3,
                windowMs = 60_000L,
                canEmit = { signedIn },
                emitSpan = { emitted += it },
            )
        val crash =
            WebContainerTelemetryEvent.RenderProcessGone(
                surfaceId = "artifact_viewer",
                didCrash = true,
                recovered = true,
                attempt = 1,
                engine = "android_webview",
            )

        // Signed-out events are skipped without consuming burst slots.
        assertFalse(reporter.report(crash))
        assertFalse(reporter.report(crash))
        assertTrue(emitted.isEmpty())

        signedIn = true
        assertTrue(reporter.report(crash))
        monotonicMs += 1_000L
        assertTrue(reporter.report(crash))
        monotonicMs += 1_000L
        assertTrue(reporter.report(crash))
        // 4th span inside 60s window is capped.
        monotonicMs += 1_000L
        assertFalse(reporter.report(crash))
        assertEquals(3, emitted.size)

        // Advancing past the first timestamp's 60s expiry re-opens a slot.
        monotonicMs = 70_000L
        assertTrue(reporter.report(crash))
        assertEquals(4, emitted.size)

        // Emitter exceptions never escape.
        val throwingReporter =
            RaftWebContainerTraceReporter(
                nowEpochMs = { epochMs },
                nowMonotonicMs = { monotonicMs },
                newTraceIds = { ids },
                canEmit = { true },
                emitSpan = { throw IllegalStateException("network boom") },
            )
        assertFalse(throwingReporter.report(crash))
    }

    @Test
    fun reporterHonorsWebContainerSampleRateAndZeroKillSwitch() {
        var sampleRate = 0.0
        var draw = 0.0
        val emitted = mutableListOf<RaftWebSpanRecord>()
        val reporter =
            RaftWebContainerTraceReporter(
                nowEpochMs = { epochMs },
                nowMonotonicMs = { 10_000L },
                newTraceIds = { ids },
                canEmit = { true },
                resolveSampleRate = { sampleRate },
                random = { draw },
                emitSpan = { emitted += it },
            )
        val crash =
            WebContainerTelemetryEvent.RenderProcessGone(
                surfaceId = "artifact_viewer",
                didCrash = true,
                recovered = false,
                attempt = 1,
                engine = "android_webview",
            )

        // Explicit 0 kill switch suppresses emission completely.
        assertFalse(reporter.report(crash))
        assertTrue(emitted.isEmpty())

        // Fractional rate drops draws >= rate and tags emitted spans with the effective sample_rate.
        sampleRate = 0.25
        draw = 0.5
        assertFalse(reporter.report(crash))
        assertTrue(emitted.isEmpty())

        draw = 0.1
        assertTrue(reporter.report(crash))
        assertEquals(0.25, emitted.single().attrs.getValue("sample_rate").jsonPrimitive.double)
    }
}
