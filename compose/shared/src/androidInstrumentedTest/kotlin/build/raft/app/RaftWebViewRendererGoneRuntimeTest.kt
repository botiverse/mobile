package build.raft.app

import android.content.Context
import android.content.MutableContextWrapper
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Kills the real WebView renderer on a device and checks the app survives it: a product page
 * ([RaftAndroidSandboxWebView]) and a warm-up style WebView ([RaftDetachedWebViewClient]) share
 * the renderer, each gets `onRenderProcessGone`, and the page rebuilds and loads again.
 *
 * "The app survives" is this process: it hosts the WebViews, so an unclaimed death kills it and
 * the instrumentation reports a process crash instead of a result.
 *
 * Device waits: renderer death and page loads have no deterministic hook a test can drive, so
 * the waits poll a recorded state on a short bounded deadline and fail naming what they saw
 * (AGENTS.md "Unit Test Case Rule", device waits).
 */
@RunWith(AndroidJUnit4::class)
class RaftWebViewRendererGoneRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext.applicationContext
    private val tickets: MutableList<Map<String, Any?>> = Collections.synchronizedList(mutableListOf())
    private val loads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val processGone: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val warmGone: MutableList<WebView?> = Collections.synchronizedList(mutableListOf())
    private var host: RaftAndroidSandboxWebView? = null
    private var warm: WebView? = null

    @Before
    fun setUp() {
        assumeTrue("onRenderProcessGone needs API 26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        RaftAndroidWebViewHooks.mainDocumentProvider = { url ->
            if (url.startsWith(PAGE)) RaftWebViewDocument("<html><body><p>renderer gone</p></body></html>".toByteArray(), "text/html", "test") else null
        }
        instrumentation.runOnMainSync {
            RaftWebViewRendererGone.resetForTest()
            RaftWebViewRendererGone.ticketSink = { _, extras, done ->
                tickets += extras
                done(true)
            }
            warm =
                WebView(context).apply {
                    webViewClient =
                        RaftDetachedWebViewClient("warmup") { view, _ ->
                            warmGone += view
                            view?.destroy()
                        }
                    loadUrl("about:blank")
                }
            val events = RaftWebViewEvents()
            events.onLoadSuccess = { loads += "load" }
            events.onContentProcessGone = { reason -> processGone += reason }
            host =
                // :shared's androidInstrumentedTest has no Activity window, so treat this test host as foreground.
                RaftAndroidSandboxWebView(
                    MutableContextWrapper(context),
                    events,
                    isForegroundWindowForRecovery = { true },
                ).apply {
                    applyConfig(
                        RaftWebViewConfig(
                            url = PAGE,
                            allowScripts = true,
                            allowNavigation = false,
                            domStorageEnabled = false,
                            useWideViewPort = false,
                            cacheEnabled = false,
                            ephemeral = true,
                            injectedJavaScript = "",
                            allowedNavigationSchemes = setOf("https"),
                            hostEventsEnabled = false,
                            diagnosticsSurface = "instrumented_test",
                        )
                    )
                }
        }
        waitFor("the first page load") { loads.size >= 1 }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            host?.destroy()
            if (warmGone.isEmpty()) warm?.destroy()
            RaftWebViewRendererGone.resetForTest()
        }
        RaftAndroidWebViewHooks.mainDocumentProvider = null
    }

    private fun inner(): WebView {
        var view: WebView? = null
        instrumentation.runOnMainSync { view = host?.getChildAt(0) as? WebView }
        return checkNotNull(view) { "the page has no WebView" }
    }

    @Test
    fun aRendererCrashIsClaimedByEveryWebViewAndThePageComesBack() {
        val before = inner()
        instrumentation.runOnMainSync { before.loadUrl("chrome://crash") }

        waitFor("the page to see its renderer crash") { processGone.isNotEmpty() }
        assertEquals(listOf("render_process_crashed"), processGone.toList())
        waitFor("the warm WebView on the same renderer to be let go") { warmGone.size == 1 }
        assertTrue(warmGone.single() === warm)
        waitFor("the rebuilt page to load") { loads.size >= 2 }
        assertNotSame("the page rebuilt its WebView", before, inner())

        // One death across two WebViews: one ticket, naming both.
        waitFor("the death event's ticket") { tickets.isNotEmpty() }
        instrumentation.waitForIdleSync()
        assertEquals(1, tickets.size)
        assertEquals("instrumented_test,warmup", tickets.single()["webview_surfaces"])
        assertEquals(true, tickets.single()["crash_did_crash"])
        assertEquals(2, tickets.single()["webview_count"])
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_MS)
        }
        error(
            "waited ${WAIT_MS}ms for $what: loads=${loads.size} processGone=$processGone " +
                "warmGone=${warmGone.size} tickets=${tickets.size}"
        )
    }

    private companion object {
        const val PAGE = "https://renderer-gone.raft.test/page"
        const val WAIT_MS = 20_000L
        const val POLL_MS = 50L
    }
}
