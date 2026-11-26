package build.raft.app

import android.os.Looper
import android.webkit.WebView
import build.raft.app.data.RaftWebSpanRecord
import build.raft.app.network.RaftTraceIds
import build.raft.webcontainer.WebContainerAndroidKeepAlive
import build.raft.webcontainer.WebContainerConfig
import build.raft.webcontainer.WebIsolationScope
import build.raft.webcontainer.pool.WebContainerPoolKey
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Hands f0c2fa63: a parked background `raft-app-warm` prewarm that is still loading must yield
 * immediately when any foreground WebView opens (e.g. `settings_marketplace`, `in_app_browser`)
 * and must abort after its safety load timeout rather than starving Android's shared Chromium
 * renderer process, while cleanly finished prewarms stay parked for normal warm-hit reuse.
 */
@RunWith(RobolectricTestRunner::class)
class RaftAndroidWebViewKeepAlivePrewarmYieldTest {
    private val warmOrigin = "https://app.raft.build"
    private val warmUrl = "$warmOrigin/artifacts/raft-app-warm?embed=1"
    private val keepAliveKey = "artifact-viewer|$warmOrigin|env|user|server"
    private val logs = mutableListOf<String>()
    private var fakeNowMs = 1_000L

    private fun warmConfig(key: String = keepAliveKey) =
        RaftWebViewConfig(
            url = warmUrl,
            allowScripts = true,
            allowNavigation = true,
            domStorageEnabled = true,
            useWideViewPort = false,
            cacheEnabled = true,
            ephemeral = false,
            injectedJavaScript = "",
            allowedNavigationSchemes = setOf("https"),
            hostEventsEnabled = false,
            mainFrameOrigin = warmOrigin,
            keepAliveKey = key,
            diagnosticsSurface = "artifact_viewer",
        )

    @BeforeTest
    fun setUp() {
        RaftAndroidWebViewKeepAlive.drop("test_setup")
        WebContainerAndroidKeepAlive.clearAll()
        logs.clear()
        fakeNowMs = 1_000L
        RaftAndroidWebViewKeepAlive.nowMs = { fakeNowMs }
        RaftAndroidWebViewKeepAlive.prewarmLoadTimeoutMs = RaftAndroidWebViewKeepAlive.PREWARM_LOAD_TIMEOUT_MS
        RaftAndroidWebViewKeepAlive.logInfo = { message -> logs += message }
    }

    @AfterTest
    fun tearDown() {
        RaftAndroidWebViewKeepAlive.drop("test_cleanup")
        RaftAndroidWebViewKeepAlive.resetTestHooks()
        WebContainerAndroidKeepAlive.clearAll()
    }

    @Test
    fun foregroundWebViewAbortsInFlightPrewarmAndLogsStructuredReason() {
        val app = RuntimeEnvironment.getApplication()
        RaftAndroidWebViewKeepAlive.prewarm(app, warmConfig())
        val parked = assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
        val webView = parked.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient

        // Still mid-load even after onPageCommitVisible (subresources/JS not yet page_finished).
        client.onPageStarted(webView, warmUrl, null)
        client.onPageCommitVisible(webView, warmUrl)
        assertTrue(parked.isMainFrameLoadInFlight)

        fakeNowMs += 3_025L
        val aborted =
            RaftAndroidWebViewKeepAlive.abortInFlightPrewarmForForeground(
                foregroundSurface = "settings_marketplace",
                foregroundKeepAliveKey = null,
            )

        assertTrue(aborted, "still-loading prewarm must yield to foreground WebView")
        assertTrue(parked.isDestroyed, "aborted prewarm view must be stopped and destroyed")
        assertNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
        assertNull(raftWebViewKeptDocument(keepAliveKey))
        val abortLog =
            logs.singleOrNull { it.contains("keep_alive result=aborted_for_foreground") }
        assertNotNull(abortLog, "expected structured abort log, got: $logs")
        assertTrue(abortLog.contains("reason=prewarm_aborted_for_foreground"), abortLog)
        assertTrue(abortLog.contains("surface=settings_marketplace"), abortLog)
        assertTrue(abortLog.contains("elapsedMs=3025"), abortLog)
    }

    @Test
    fun foregroundWebViewPreservesCleanlyFinishedPrewarmForNormalWarmHitReuse() {
        val app = RuntimeEnvironment.getApplication()
        RaftAndroidWebViewKeepAlive.prewarm(app, warmConfig())
        val parked = assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
        val webView = parked.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient

        client.onPageStarted(webView, warmUrl, null)
        client.onPageCommitVisible(webView, warmUrl)
        client.onPageFinished(webView, warmUrl)
        assertFalse(parked.isMainFrameLoadInFlight)
        assertEquals(true, parked.keptDocument()?.loaded)

        // Opening settings_marketplace or in_app_browser must NOT discard a finished warm view.
        assertFalse(
            RaftAndroidWebViewKeepAlive.abortInFlightPrewarmForForeground(
                foregroundSurface = "settings_marketplace",
                foregroundKeepAliveKey = null,
            ),
        )
        assertFalse(parked.isDestroyed)
        assertSame(parked, RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
        assertTrue(logs.none { it.contains("aborted_for_foreground") }, logs.joinToString("\n"))

        // Artifact viewer subsequently gets a normal warm-hit reuse.
        val reused = RaftAndroidWebViewKeepAlive.take(keepAliveKey, app, RaftWebViewEvents())
        assertSame(parked, reused)
        reused?.destroy()
    }

    @Test
    fun matchingKeepAliveKeyDoesNotAbortItsOwnInFlightPrewarm() {
        val app = RuntimeEnvironment.getApplication()
        RaftAndroidWebViewKeepAlive.prewarm(app, warmConfig())
        val parked = assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
        assertTrue(parked.isMainFrameLoadInFlight)

        assertFalse(
            RaftAndroidWebViewKeepAlive.abortInFlightPrewarmForForeground(
                foregroundSurface = "artifact_viewer",
                foregroundKeepAliveKey = keepAliveKey,
            ),
        )
        assertFalse(parked.isDestroyed)
        assertSame(parked, RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
    }

    @Test
    fun stuckInFlightPrewarmIsAbortedAtSafetyTimeoutWhileFinishedPrewarmSurvives() {
        val app = RuntimeEnvironment.getApplication()
        RaftAndroidWebViewKeepAlive.prewarm(app, warmConfig())
        val stuck = assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
        val stuckWebView = stuck.getChildAt(0) as WebView
        shadowOf(stuckWebView).webViewClient.onPageStarted(stuckWebView, warmUrl, null)

        // Still loading at 11.9s -> still parked.
        fakeNowMs += 11_900L
        shadowOf(Looper.getMainLooper()).idleFor(11_900L, TimeUnit.MILLISECONDS)
        assertFalse(stuck.isDestroyed)
        assertSame(stuck, RaftAndroidWebViewKeepAlive.kept(keepAliveKey))

        // Past 12s safety timeout without onPageFinished -> automatically aborted and dropped.
        fakeNowMs += 200L
        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)
        assertTrue(stuck.isDestroyed, "stuck prewarm must be aborted at safety timeout")
        assertNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
        val timeoutLog = logs.singleOrNull { it.contains("keep_alive result=aborted_timeout") }
        assertNotNull(timeoutLog, "expected structured timeout log, got: $logs")
        assertTrue(timeoutLog.contains("reason=prewarm_load_timeout"), timeoutLog)

        // A prewarm that finishes cleanly before 12s cancels its timeout and survives past 15s.
        logs.clear()
        RaftAndroidWebViewKeepAlive.prewarm(app, warmConfig())
        val healthy = assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
        val healthyWebView = healthy.getChildAt(0) as WebView
        val healthyClient = shadowOf(healthyWebView).webViewClient
        healthyClient.onPageStarted(healthyWebView, warmUrl, null)
        healthyClient.onPageCommitVisible(healthyWebView, warmUrl)
        healthyClient.onPageFinished(healthyWebView, warmUrl)
        assertFalse(healthy.isMainFrameLoadInFlight)

        fakeNowMs += 15_000L
        shadowOf(Looper.getMainLooper()).idleFor(15_000L, TimeUnit.MILLISECONDS)
        assertFalse(healthy.isDestroyed, "finished prewarm must survive past load timeout")
        assertSame(healthy, RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
        assertTrue(logs.none { it.contains("aborted_timeout") }, logs.joinToString("\n"))
    }

    @Test
    fun webContainerKeepAliveAbortsInFlightPrewarmForForegroundAndOnTimeout() {
        val app = RuntimeEnvironment.getApplication()
        val telemetry = mutableListOf<WebContainerTelemetryEvent.PoolEvent>()
        val sink =
            build.raft.webcontainer.pool.WebContainerTelemetrySink { event ->
                if (event is WebContainerTelemetryEvent.PoolEvent) telemetry += event
            }
        val containerConfig =
            WebContainerConfig(
                url = warmUrl,
                surfaceId = "artifact_viewer",
                isolationScope = WebIsolationScope.Default,
                keepAliveKey = keepAliveKey,
                isDarkTheme = false,
            )

        assertTrue(
            WebContainerAndroidKeepAlive.prewarm(
                context = app,
                config = containerConfig,
                telemetrySink = sink,
            ),
        )
        assertNotNull(WebContainerAndroidKeepAlive.keptDocument(keepAliveKey))

        val aborted =
            WebContainerAndroidKeepAlive.abortInFlightPrewarmForForeground(
                foregroundSurface = "settings_marketplace",
                exceptKey = null,
                telemetrySink = sink,
            )
        assertEquals(1, aborted)
        assertNull(WebContainerAndroidKeepAlive.keptDocument(keepAliveKey))
        assertTrue(
            telemetry.any { it.action == "prewarm_aborted_for_foreground" && it.keepAliveKey == "settings_marketplace" },
            telemetry.joinToString("\n"),
        )

        // Stuck WebContainer prewarm also aborts after safety timeout.
        telemetry.clear()
        assertTrue(
            WebContainerAndroidKeepAlive.prewarm(
                context = app,
                config = containerConfig,
                telemetrySink = sink,
            ),
        )
        assertNotNull(WebContainerAndroidKeepAlive.keptDocument(keepAliveKey))
        shadowOf(Looper.getMainLooper()).idleFor(WebContainerAndroidKeepAlive.PREWARM_LOAD_TIMEOUT_MS + 100L, TimeUnit.MILLISECONDS)
        assertNull(WebContainerAndroidKeepAlive.keptDocument(keepAliveKey))
        assertTrue(
            telemetry.any { it.action == "prewarm_aborted_timeout" },
            telemetry.joinToString("\n"),
        )

        // Same-key exclusion does not abort its own in-flight prewarm.
        assertTrue(
            WebContainerAndroidKeepAlive.prewarm(
                context = app,
                config = containerConfig,
                telemetrySink = sink,
            ),
        )
        val sameKey = WebContainerPoolKey(keepAliveKey, WebIsolationScope.Default, isDarkTheme = false)
        assertEquals(0, WebContainerAndroidKeepAlive.abortInFlightPrewarmForForeground("artifact_viewer", exceptKey = sameKey, telemetrySink = sink))
        assertNotNull(WebContainerAndroidKeepAlive.keptDocument(keepAliveKey))
    }

    @Test
    fun prewarmEmitsMobileWebViewLoadSpansForOkForegroundYieldTimeoutAndDropErrors() {
        val app = RuntimeEnvironment.getApplication()
        val spans = mutableListOf<RaftWebSpanRecord>()
        val recorder =
            MobileWebViewLoadTraceRecorder(
                nowEpochMs = { 1_795_500_000_000L + fakeNowMs },
                resolveServerId = { "srv-1" },
                resolveBaseRate = { _, _ -> 1.0 },
                newTraceIds = { RaftTraceIds("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7") },
                emitSpan = { _, span -> spans += span },
            )
        RaftAndroidWebViewKeepAlive.startPrewarmTrace = { surface ->
            MobileWebViewLoadAttemptHandle(
                kind = MobileWebViewLoadKind.Prewarm,
                surface = normalizeMobileWebViewSurface(surface, MobileWebViewLoadKind.Prewarm),
                engine = MobileWebViewLoadEngine.SandboxWebView,
                prewarmHit = null,
                startedAtEpochMs = 1_795_500_000_000L + fakeNowMs,
                recorder = recorder,
            )
        }

        // 1. Clean finish -> ok / none.
        RaftAndroidWebViewKeepAlive.prewarm(app, warmConfig())
        val okView = assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
        val okWebView = okView.getChildAt(0) as WebView
        val okClient = shadowOf(okWebView).webViewClient
        okClient.onPageStarted(okWebView, warmUrl, null)
        okClient.onPageCommitVisible(okWebView, warmUrl)
        okClient.onPageFinished(okWebView, warmUrl)
        assertEquals(1, spans.size)
        assertEquals("prewarm", spans[0].attrs.getValue("kind").jsonPrimitive.content)
        assertEquals("ok", spans[0].attrs.getValue("outcome").jsonPrimitive.content)
        assertEquals("none", spans[0].attrs.getValue("error_code").jsonPrimitive.content)
        assertNull(spans[0].attrs["prewarm_hit"])
        RaftAndroidWebViewKeepAlive.drop("test_next")

        // 2. Yield for foreground -> cancelled_for_foreground.
        RaftAndroidWebViewKeepAlive.prewarm(app, warmConfig())
        assertTrue(RaftAndroidWebViewKeepAlive.abortInFlightPrewarmForForeground("settings_marketplace", null))
        assertEquals(2, spans.size)
        assertEquals("cancelled_for_foreground", spans[1].attrs.getValue("outcome").jsonPrimitive.content)

        // 3. Safety timeout -> timeout / timeout.
        RaftAndroidWebViewKeepAlive.prewarm(app, warmConfig())
        fakeNowMs += RaftAndroidWebViewKeepAlive.PREWARM_LOAD_TIMEOUT_MS + 100L
        shadowOf(Looper.getMainLooper()).idleFor(RaftAndroidWebViewKeepAlive.PREWARM_LOAD_TIMEOUT_MS + 100L, TimeUnit.MILLISECONDS)
        assertEquals(3, spans.size)
        assertEquals("timeout", spans[2].attrs.getValue("outcome").jsonPrimitive.content)
        assertEquals("timeout", spans[2].attrs.getValue("error_code").jsonPrimitive.content)

        // 4. Render process gone drop -> failed / render_process_gone.
        RaftAndroidWebViewKeepAlive.prewarm(app, warmConfig())
        val crashed = assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
        RaftAndroidWebViewKeepAlive.dropIfSame(crashed, "render_process_gone")
        assertEquals(4, spans.size)
        assertEquals("failed", spans[3].attrs.getValue("outcome").jsonPrimitive.content)
        assertEquals("render_process_gone", spans[3].attrs.getValue("error_code").jsonPrimitive.content)
    }
}
