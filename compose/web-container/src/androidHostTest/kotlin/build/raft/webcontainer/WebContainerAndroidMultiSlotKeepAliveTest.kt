package build.raft.webcontainer

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.MutableContextWrapper
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.pool.WebContainerPoolKey
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import build.raft.webcontainer.pool.WebMemoryTrimLevel
import build.raft.webcontainer.resilience.WebContainerWatchdogPolicy
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Robolectric verification for multi-slot per-artifact keep-alive in `compose/web-container`
 * (`#wg-mobile-feedback:#335`):
 * - Switching A -> B -> A -> B reuses two distinct parked views with zero reload (`reloaded=false`).
 * - Opening 4 distinct artifacts evicts the LRU slot and calls `WebView.destroy()`.
 * - Returning to the same key with a different URL reloads cleanly without stale `onLoadSuccess`.
 * - Updating `documentStartScripts` (e.g. new `webViewGeneration` auth binding) rebinds scripts
 *   without reloading the parked document.
 * - Prewarm promotion moves a warm slot to an artifact slot atomically without wasting a slot.
 * - `onTrimMemory` preserves parked slots on `UI_HIDDEN`, trims half on `BACKGROUND`, and clears
 *   all on `COMPLETE`.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebContainerAndroidMultiSlotKeepAliveTest {
    private val createdHosts = mutableListOf<WebContainerAndroidView>()
    private val telemetry = mutableListOf<WebContainerTelemetryEvent>()
    private val telemetrySink = WebContainerTelemetrySink { telemetry += it }
    private lateinit var activity: Activity

    @Before
    fun setUp() {
        WebContainerAndroidKeepAlive.clearAll()
        telemetry.clear()
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        activity = controller.get()
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
    }

    @After
    fun tearDown() {
        for (host in createdHosts) {
            host.destroy()
        }
        createdHosts.clear()
        WebContainerAndroidKeepAlive.clearAll()
    }

    private fun artifactConfig(
        artifactId: String,
        url: String = "https://app.raft.build/artifacts/$artifactId?embed=raft-settings-v1&shell=host",
        keepAliveKey: String = "artifact-viewer|https://app.raft.build|env|u1|s1|artifact=$artifactId|conv=c1|head=|lang=en",
        scriptToken: String = "gen-1",
    ): WebContainerConfig =
        WebContainerConfig(
            url = url,
            surfaceId = "artifact_viewer",
            isolationScope = WebIsolationScope.Default,
            allowScripts = true,
            domStorageEnabled = true,
            cacheEnabled = true,
            keepAliveKey = keepAliveKey,
            isDarkTheme = false,
            documentStartScripts =
                listOf(
                    WebDocumentStartScript(
                        id = "raft_injected",
                        source = "window.__gen = '$scriptToken';",
                        allowedOrigins = setOf(WebContainerOrigin("https", "app.raft.build")),
                    ),
                ),
            navigationPolicy =
                WebNavigationPolicy(
                    allowNavigation = true,
                    allowedSchemes = setOf("https"),
                    lockedMainFrameOrigin = WebContainerOrigin("https", "app.raft.build"),
                ),
        )

    private fun createAndLoadHost(
        config: WebContainerConfig,
        events: WebContainerEvents = WebContainerEvents(),
        finishUrl: String = config.url,
    ): Pair<WebContainerAndroidView, WebView> {
        val host =
            WebContainerAndroidView(
                context = MutableContextWrapper(activity),
                initialConfig = config,
                events = events,
                jsbRegistry = JsbRegistry.Empty,
                resourceInterceptor = null,
                telemetrySink = telemetrySink,
                fileChooser = null,
                watchdogPolicy = WebContainerWatchdogPolicy(),
                nowMs = SystemClock::uptimeMillis,
            )
        createdHosts += host
        val webView = host.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient
        client.onPageStarted(webView, finishUrl, null)
        client.onPageCommitVisible(webView, finishUrl)
        client.onPageFinished(webView, finishUrl)
        return host to webView
    }

    @Test
    fun switchingBetweenTwoArtifactsReusesDistinctParkedSlotsWithoutReload() {
        val configA = artifactConfig("pt_d65a0269", scriptToken = "gen-1")
        val configB = artifactConfig("pt_0a8e2b76", scriptToken = "gen-2")
        val keyA = WebContainerPoolKey(configA.keepAliveKey!!, WebIsolationScope.Default, isDarkTheme = false)
        val keyB = WebContainerPoolKey(configB.keepAliveKey!!, WebIsolationScope.Default, isDarkTheme = false)

        // Open A -> finish load -> close & park A.
        val (hostA, webViewA) = createAndLoadHost(configA, finishUrl = "${configA.url}&hostToolbar=1")
        assertTrue(WebContainerAndroidKeepAlive.park(hostA, telemetrySink = telemetrySink))
        assertEquals(1, WebContainerAndroidKeepAlive.poolSize)

        // Open B -> finish load -> close & park B (A stays parked alongside B).
        val (hostB, webViewB) = createAndLoadHost(configB, finishUrl = "${configB.url}&hostToolbar=1")
        assertNotSame(hostA, hostB)
        assertTrue(WebContainerAndroidKeepAlive.park(hostB, telemetrySink = telemetrySink))
        assertEquals(2, WebContainerAndroidKeepAlive.poolSize)

        // Return to A with updated document-start script (new webViewGeneration) and identical URL.
        telemetry.clear()
        val configAReopen = configA.copy(documentStartScripts = artifactConfig("pt_d65a0269", scriptToken = "gen-3").documentStartScripts)
        var loadSuccessA = 0
        var loadingSeenOnReapply = false
        val eventsA =
            WebContainerEvents(
                onLoadSuccess = { loadSuccessA += 1 },
                onNavigationStateChange = { state -> if (state.isLoading) loadingSeenOnReapply = true },
            )
        val reusedA =
            WebContainerAndroidKeepAlive.take(
                key = keyA,
                hostContext = activity,
                events = eventsA,
                jsbRegistry = JsbRegistry.Empty,
                resourceInterceptor = null,
                telemetrySink = telemetrySink,
                fileChooser = null,
                incomingConfig = configAReopen,
            )
        assertSame(hostA, reusedA, "Switching back to Artifact A must reuse A's exact parked WebContainer")
        assertEquals(1, loadSuccessA, "Warm already-loaded reuse must immediately deliver onLoadSuccess")
        assertEquals(1, WebContainerAndroidKeepAlive.poolSize, "Artifact B must remain parked in the pool")
        val reuseEventA = telemetry.filterIsInstance<WebContainerTelemetryEvent.PoolEvent>().single { it.action == "reused" }
        assertEquals(false, reuseEventA.reloaded, "Exact-URL reuse must report reloaded=false")
        assertEquals("artifact_viewer", reuseEventA.surfaceId)

        // Applying re-open config with updated auth-binding script must NOT call loadUrl or reload.
        webViewA.clearHistory()
        assertEquals(0, webViewA.copyBackForwardList().size)
        loadingSeenOnReapply = false
        reusedA!!.applyConfig(configAReopen)
        assertEquals(0, webViewA.copyBackForwardList().size, "applyConfig must not invoke loadUrl when URL is unchanged")
        assertEquals(0, shadowOf(webViewA).reloadInvocations, "applyConfig must not invoke reload when viewport mode is unchanged")
        assertFalse(loadingSeenOnReapply, "applyConfig must not enter loading state on warm exact-URL reuse")
        assertFalse(reusedA.isLoadInFlight())

        // Re-park A, then switch back to B -> B also reuses cleanly with reloaded=false.
        assertTrue(WebContainerAndroidKeepAlive.park(reusedA, telemetrySink = telemetrySink))
        assertEquals(2, WebContainerAndroidKeepAlive.poolSize)

        telemetry.clear()
        var loadSuccessB = 0
        val reusedB =
            WebContainerAndroidKeepAlive.take(
                key = keyB,
                hostContext = activity,
                events = WebContainerEvents(onLoadSuccess = { loadSuccessB += 1 }),
                jsbRegistry = JsbRegistry.Empty,
                resourceInterceptor = null,
                telemetrySink = telemetrySink,
                fileChooser = null,
                incomingConfig = configB,
            )
        assertSame(hostB, reusedB, "Switching back to Artifact B must reuse B's exact parked WebContainer")
        assertSame(webViewB, reusedB!!.getChildAt(0) as WebView)
        assertEquals(1, loadSuccessB)
        val reuseEventB = telemetry.filterIsInstance<WebContainerTelemetryEvent.PoolEvent>().single { it.action == "reused" }
        assertEquals(false, reuseEventB.reloaded)
        reusedB.applyConfig(configB)
        assertFalse(reusedB.isLoadInFlight())
    }

    @Test
    fun openingFourDistinctArtifactsEvictsLruSlotAndDestroysUnderlyingWebView() {
        val configA = artifactConfig("pt_1")
        val configB = artifactConfig("pt_2")
        val configC = artifactConfig("pt_3")
        val configD = artifactConfig("pt_4")
        val keyA = WebContainerPoolKey(configA.keepAliveKey!!, WebIsolationScope.Default, isDarkTheme = false)

        val (hostA, webViewA) = createAndLoadHost(configA)
        val (hostB, webViewB) = createAndLoadHost(configB)
        val (hostC, webViewC) = createAndLoadHost(configC)
        val (hostD, webViewD) = createAndLoadHost(configD)

        assertTrue(WebContainerAndroidKeepAlive.park(hostA, telemetrySink = telemetrySink))
        assertTrue(WebContainerAndroidKeepAlive.park(hostB, telemetrySink = telemetrySink))
        assertTrue(WebContainerAndroidKeepAlive.park(hostC, telemetrySink = telemetrySink))
        assertEquals(3, WebContainerAndroidKeepAlive.poolSize)

        // Revisit A so LRU order becomes [B, C, A].
        val takenA =
            assertNotNull(
                WebContainerAndroidKeepAlive.take(
                    key = keyA,
                    hostContext = activity,
                    events = WebContainerEvents(),
                    jsbRegistry = JsbRegistry.Empty,
                    resourceInterceptor = null,
                    telemetrySink = telemetrySink,
                    fileChooser = null,
                    incomingConfig = configA,
                ),
            )
        assertTrue(WebContainerAndroidKeepAlive.park(takenA, telemetrySink = telemetrySink))

        // Parking 4th artifact D evicts LRU B (destroying webViewB) while preserving A, C, D.
        telemetry.clear()
        assertTrue(WebContainerAndroidKeepAlive.park(hostD, telemetrySink = telemetrySink))
        assertEquals(3, WebContainerAndroidKeepAlive.poolSize)
        assertTrue(shadowOf(webViewB).wasDestroyCalled(), "Evicted LRU Android WebView must be destroyed")
        assertFalse(shadowOf(webViewA).wasDestroyCalled())
        assertFalse(shadowOf(webViewC).wasDestroyCalled())
        assertFalse(shadowOf(webViewD).wasDestroyCalled())
        assertNull(WebContainerAndroidKeepAlive.keptDocument(configB.keepAliveKey!!))
        assertNotNull(WebContainerAndroidKeepAlive.keptDocument(configA.keepAliveKey!!))
        assertNotNull(WebContainerAndroidKeepAlive.keptDocument(configC.keepAliveKey!!))
        assertNotNull(WebContainerAndroidKeepAlive.keptDocument(configD.keepAliveKey!!))
        assertTrue(
            telemetry.filterIsInstance<WebContainerTelemetryEvent.PoolEvent>().any { it.action == "evict_lru" },
            "Expected evict_lru pool telemetry event",
        )
    }

    @Test
    fun returningToSameKeyWithDifferentUrlReloadsCleanlyWithoutStaleLoadSuccess() {
        val urlV1 = "https://app.raft.build/artifacts/pt_1?embed=raft-settings-v1&shell=host"
        val urlV2 = "https://app.raft.build/artifacts/pt_1/v/2?embed=raft-settings-v1&shell=host"
        val sharedKey = "artifact-viewer|https://app.raft.build|env|u1|s1|artifact=pt_1|conv=|head=|lang=en"
        val configV1 = artifactConfig("pt_1", url = urlV1, keepAliveKey = sharedKey)
        val configV2 = artifactConfig("pt_1", url = urlV2, keepAliveKey = sharedKey)
        val poolKey = WebContainerPoolKey(sharedKey, WebIsolationScope.Default, isDarkTheme = false)

        val (host, webView) = createAndLoadHost(configV1)
        assertTrue(WebContainerAndroidKeepAlive.park(host, telemetrySink = telemetrySink))

        telemetry.clear()
        var staleSuccessFired = 0
        val reused =
            assertNotNull(
                WebContainerAndroidKeepAlive.take(
                    key = poolKey,
                    hostContext = activity,
                    events = WebContainerEvents(onLoadSuccess = { staleSuccessFired += 1 }),
                    jsbRegistry = JsbRegistry.Empty,
                    resourceInterceptor = null,
                    telemetrySink = telemetrySink,
                    fileChooser = null,
                    incomingConfig = configV2,
                ),
            )
        assertEquals(0, staleSuccessFired, "Stale onLoadSuccess must NOT fire when URL changed and will reload")
        val reuseEvent = telemetry.filterIsInstance<WebContainerTelemetryEvent.PoolEvent>().single { it.action == "reused" }
        assertEquals(true, reuseEvent.reloaded, "URL change on same key must report reloaded=true")

        reused.applyConfig(configV2)
        assertEquals(urlV2, shadowOf(webView).lastLoadedUrl, "applyConfig must load the new URL cleanly")
    }

    @Test
    fun prewarmPromotionSurgicalDropAndTrimMemoryBehaveSafely() {
        val scopePrefix = "artifact-viewer|https://app.raft.build|env|u1|s1"
        val prewarmUrl = "https://app.raft.build/artifacts/raft-app-warm?embed=raft-settings-v1&shell=host"
        val prewarmConfig = artifactConfig("raft-app-warm", url = prewarmUrl, keepAliveKey = scopePrefix)
        val slotAKey = "$scopePrefix|artifact=pt_a|conv=c1|head=|lang=en"
        val slotBKey = "$scopePrefix|artifact=pt_b|conv=c1|head=|lang=en"
        val slotCKey = "$scopePrefix|artifact=pt_c|conv=c1|head=|lang=en"

        // Prewarm parked under scopePrefix -> promoted atomically to slotAKey.
        val (warmHost, warmWebView) = createAndLoadHost(prewarmConfig)
        assertTrue(WebContainerAndroidKeepAlive.park(warmHost, telemetrySink = telemetrySink))
        assertFalse(WebContainerAndroidKeepAlive.hasAnyWithPrefix("$scopePrefix|artifact="))

        assertTrue(WebContainerAndroidKeepAlive.promote(scopePrefix, slotAKey))
        assertEquals(1, WebContainerAndroidKeepAlive.poolSize)
        assertNull(WebContainerAndroidKeepAlive.keptDocument(scopePrefix))
        assertNotNull(WebContainerAndroidKeepAlive.keptDocument(slotAKey))
        assertTrue(WebContainerAndroidKeepAlive.hasAnyWithPrefix("$scopePrefix|artifact="))
        assertFalse(shadowOf(warmWebView).wasDestroyCalled())

        // Surgical dropAllThemes removes only slotAKey and keeps sibling slots alive.
        val (hostB, webViewB) = createAndLoadHost(artifactConfig("pt_b", keepAliveKey = slotBKey))
        val (hostC, webViewC) = createAndLoadHost(artifactConfig("pt_c", keepAliveKey = slotCKey))
        assertTrue(WebContainerAndroidKeepAlive.park(hostB, telemetrySink = telemetrySink))
        assertTrue(WebContainerAndroidKeepAlive.park(hostC, telemetrySink = telemetrySink))
        assertEquals(3, WebContainerAndroidKeepAlive.poolSize)

        assertEquals(1, WebContainerAndroidKeepAlive.dropAllThemes(slotAKey))
        assertTrue(shadowOf(warmWebView).wasDestroyCalled())
        assertFalse(shadowOf(webViewB).wasDestroyCalled())
        assertFalse(shadowOf(webViewC).wasDestroyCalled())
        assertEquals(2, WebContainerAndroidKeepAlive.poolSize)

        // Verify Android ComponentCallbacks2 trim-level mapping & execution.
        @Suppress("DEPRECATION")
        run {
            assertNull(mapAndroidTrimMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
            assertEquals(WebMemoryTrimLevel.MODERATE, mapAndroidTrimMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
            assertEquals(WebMemoryTrimLevel.MODERATE, mapAndroidTrimMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
            assertEquals(WebMemoryTrimLevel.MODERATE, mapAndroidTrimMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
            assertEquals(WebMemoryTrimLevel.MODERATE, mapAndroidTrimMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
            assertEquals(WebMemoryTrimLevel.CRITICAL, mapAndroidTrimMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
            assertEquals(WebMemoryTrimLevel.CRITICAL, mapAndroidTrimMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))

            // UI_HIDDEN preserves warm parked artifacts; BACKGROUND trims oldest half; COMPLETE clears all.
            assertEquals(0, WebContainerAndroidKeepAlive.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
            assertEquals(2, WebContainerAndroidKeepAlive.poolSize)

            assertEquals(1, WebContainerAndroidKeepAlive.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
            assertTrue(shadowOf(webViewB).wasDestroyCalled())
            assertFalse(shadowOf(webViewC).wasDestroyCalled())
            assertEquals(1, WebContainerAndroidKeepAlive.poolSize)

            assertEquals(1, WebContainerAndroidKeepAlive.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
            assertTrue(shadowOf(webViewC).wasDestroyCalled())
            assertEquals(0, WebContainerAndroidKeepAlive.poolSize)
        }
    }
}
