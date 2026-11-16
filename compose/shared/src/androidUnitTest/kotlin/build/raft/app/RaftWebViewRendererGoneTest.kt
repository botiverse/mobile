package build.raft.app

import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * One renderer death, as Chromium delivers it: one `onRenderProcessGone` per WebView on the
 * renderer, all in one main-thread turn. Every client must clean up its own WebView and return
 * true (else the app is killed), and the death is one event: one ticket at most.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftWebViewRendererGoneTest {
    private val tickets = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun setUp() {
        RaftWebViewRendererGone.resetForTest()
        RaftWebViewRendererGone.ticketSink = { message, extras -> tickets += message to extras }
    }

    @After
    fun tearDown() {
        RaftWebViewRendererGone.resetForTest()
    }

    private fun detail(didCrash: Boolean, priority: Int = WebView.RENDERER_PRIORITY_IMPORTANT): RenderProcessGoneDetail =
        object : RenderProcessGoneDetail() {
            override fun didCrash(): Boolean = didCrash

            override fun rendererPriorityAtExit(): Int = priority
        }

    private fun sandbox(surface: String): RaftAndroidSandboxWebView =
        RaftAndroidSandboxWebView(RuntimeEnvironment.getApplication(), RaftWebViewEvents()).apply {
            applyConfig(
                RaftWebViewConfig(
                    url = "https://app.raft.build/artifacts/pt_1",
                    allowScripts = true,
                    allowNavigation = true,
                    domStorageEnabled = true,
                    useWideViewPort = false,
                    cacheEnabled = false,
                    ephemeral = true,
                    injectedJavaScript = "",
                    allowedNavigationSchemes = setOf("https"),
                    hostEventsEnabled = false,
                    diagnosticsSurface = surface,
                )
            )
        }

    private fun RaftAndroidSandboxWebView.innerWebView(): WebView = getChildAt(0) as WebView

    private fun WebView.client() = shadowOf(this).webViewClient

    private fun endOfTurn() = shadowOf(Looper.getMainLooper()).idle()

    private class Warm {
        val gone = mutableListOf<WebView?>()
        val view: WebView =
            WebView(RuntimeEnvironment.getApplication()).apply {
                webViewClient =
                    RaftDetachedWebViewClient("warmup") { view, _ ->
                        gone += view
                        view?.destroy()
                    }
            }
    }

    @Test
    fun oneDeathAcrossAProductWebViewAndTheWarmInstanceIsOneTicket() {
        val viewer = sandbox("artifact_viewer")
        val warm = Warm()
        val dying = viewer.innerWebView()
        val crash = detail(didCrash = true)

        // Both WebViews on the renderer get the callback in the same turn; each must claim it.
        assertTrue(dying.client().onRenderProcessGone(dying, crash), "the product WebView claims the death")
        assertTrue(warm.view.client().onRenderProcessGone(warm.view, crash), "the warm instance claims the death")

        // Each cleaned up its own instance: the product page rebuilt its WebView, the warm one let go.
        assertNotSame(dying, viewer.innerWebView(), "the product WebView was rebuilt")
        assertTrue(shadowOf(dying).wasDestroyCalled(), "the dead product WebView was destroyed")
        assertEquals(listOf<WebView?>(warm.view), warm.gone)
        assertTrue(shadowOf(warm.view).wasDestroyCalled(), "the warm WebView was destroyed")

        assertTrue(tickets.isEmpty(), "the ticket waits for the end of the death event")
        endOfTurn()
        assertEquals(1, tickets.size, "one death, one ticket")
        val (message, extras) = tickets.single()
        assertEquals("webview_renderer_gone", extras["crash_type"])
        assertEquals(false, extras["crash_fatal"])
        assertEquals(true, extras["crash_did_crash"])
        assertEquals(WebView.RENDERER_PRIORITY_IMPORTANT, extras["crash_renderer_priority_at_exit"])
        assertEquals("artifact_viewer,warmup", extras["webview_surfaces"])
        assertEquals(2, extras["webview_count"])
        assertTrue(extras.containsKey("webview_package"))
        val text = message + extras.values.joinToString(" ")
        assertFalse(text.contains("http") || text.contains("raft.build"), "no URL in the ticket: $text")

        // Nothing more comes of that death.
        endOfTurn()
        assertEquals(1, tickets.size)

        // The rebuilt WebView is covered too: the next death is claimed the same way.
        val rebuilt = viewer.innerWebView()
        assertTrue(rebuilt.client().onRenderProcessGone(rebuilt, crash), "the rebuilt WebView claims the next death")
        assertNotSame(rebuilt, viewer.innerWebView())
    }

    @Test
    fun aSystemReclaimIsLoggedButFilesNoTicket() {
        val viewer = sandbox("settings_web_view")
        val warm = Warm()
        val reclaim = detail(didCrash = false, priority = WebView.RENDERER_PRIORITY_WAIVED)
        val dying = viewer.innerWebView()

        assertTrue(dying.client().onRenderProcessGone(dying, reclaim))
        assertTrue(warm.view.client().onRenderProcessGone(warm.view, reclaim))
        endOfTurn()

        assertNotSame(dying, viewer.innerWebView(), "the page still recovers")
        assertEquals(1, warm.gone.size)
        assertTrue(tickets.isEmpty(), "a background reclaim with no user impact files nothing")
    }

    @Test
    fun aParkedKeepAliveViewNamesItselfParked() {
        val viewer = sandbox("artifact_viewer")
        viewer.detachForKeepAlive()
        val dying = viewer.innerWebView()

        assertTrue(dying.client().onRenderProcessGone(dying, detail(didCrash = true)))
        endOfTurn()

        assertEquals("artifact_viewer_parked", tickets.single().second["webview_surfaces"])
    }

    @Test
    fun theWarmClientNeverNavigates() {
        val warm = Warm()
        @Suppress("DEPRECATION")
        assertTrue(warm.view.client().shouldOverrideUrlLoading(warm.view, "https://example.com/"))
    }

    @Test
    fun deathsInLaterTurnsWithinAMinuteAreDeduped() {
        var now = 1_700_000_000_000L
        RaftWebViewRendererGone.clock = { now }
        val crash = detail(didCrash = true)

        repeat(3) {
            val viewer = sandbox("in_app_browser")
            val dying = viewer.innerWebView()
            assertTrue(dying.client().onRenderProcessGone(dying, crash))
            endOfTurn()
            now += 20_000L
        }
        assertEquals(1, tickets.size, "three deaths within a minute, one ticket")

        now += 1_000L // 61 s after the ticket
        val viewer = sandbox("in_app_browser")
        val dying = viewer.innerWebView()
        assertTrue(dying.client().onRenderProcessGone(dying, crash))
        endOfTurn()
        assertEquals(2, tickets.size)
    }
}

/** [RaftWebViewRendererGoneTicketGate]: crashes only, one a minute per process, a cap per UTC day. */
class RaftWebViewRendererGoneTicketGateTest {
    private val crash = RaftWebViewRendererGoneEvent(true, WebView.RENDERER_PRIORITY_BOUND, listOf("warmup"), 1, "pkg 1")
    private val dayStart = 20_000L * 24 * 60 * 60 * 1000 // 00:00 UTC
    private val minute = 60_000L

    @Test
    fun theDecisionFollowsTheIntervalAndTheDailyCap() {
        val store = RaftWebViewRendererGoneDailyStore.InMemory()
        val gate = RaftWebViewRendererGoneTicketGate(store, minIntervalMillis = minute, maxPerDay = 3)
        val steps =
            listOf(
                0L to RaftWebViewRendererGoneTicketDecision.File,
                30_000L to RaftWebViewRendererGoneTicketDecision.RecentTicket,
                59_999L to RaftWebViewRendererGoneTicketDecision.RecentTicket,
                60_000L to RaftWebViewRendererGoneTicketDecision.File,
                // A clock set back is never a free slot.
                10_000L to RaftWebViewRendererGoneTicketDecision.RecentTicket,
                5 * minute to RaftWebViewRendererGoneTicketDecision.File,
                10 * minute to RaftWebViewRendererGoneTicketDecision.DailyCap,
                60 * minute to RaftWebViewRendererGoneTicketDecision.DailyCap,
                // The next UTC day starts a new count.
                24 * 60 * minute to RaftWebViewRendererGoneTicketDecision.File,
            )
        val decisions = steps.map { (offset, _) -> gate.decide(crash, dayStart + offset) }
        assertEquals(steps.map { it.second }, decisions)
        assertEquals(Math.floorDiv(dayStart, 24L * 60 * minute) + 1 to 1, store.load())
    }

    @Test
    fun theDailyCapHoldsAcrossProcesses() {
        val store = RaftWebViewRendererGoneDailyStore.InMemory()
        // A crash loop: every launch is a new process with a new gate, sharing the persisted count.
        val decisions =
            (0 until 7).map { launch ->
                RaftWebViewRendererGoneTicketGate(store, maxPerDay = 5).decide(crash, dayStart + launch * 1_000L)
            }
        assertEquals(5, decisions.count { it == RaftWebViewRendererGoneTicketDecision.File })
        assertEquals(RaftWebViewRendererGoneTicketDecision.DailyCap, decisions.last())
    }

    @Test
    fun aSystemReclaimNeverSpendsTheBudget() {
        val store = RaftWebViewRendererGoneDailyStore.InMemory()
        val gate = RaftWebViewRendererGoneTicketGate(store)
        assertEquals(RaftWebViewRendererGoneTicketDecision.SystemReclaim, gate.decide(crash.copy(didCrash = false), dayStart))
        assertEquals(null, store.load())
        assertEquals(RaftWebViewRendererGoneTicketDecision.File, gate.decide(crash, dayStart + 1))
    }

    @Test
    fun anEventIsAnyCrashAndEveryDistinctSurface() {
        val event =
            RaftWebViewRendererGoneEvent.of(
                listOf(
                    RaftWebViewRendererGoneSignal("warmup", didCrash = false, rendererPriorityAtExit = 0),
                    RaftWebViewRendererGoneSignal("artifact_viewer", didCrash = true, rendererPriorityAtExit = 2),
                    RaftWebViewRendererGoneSignal("artifact_viewer", didCrash = true, rendererPriorityAtExit = 2),
                ),
                "com.google.android.webview 120.0",
            )
        assertEquals(RaftWebViewRendererGoneEvent(true, 2, listOf("artifact_viewer", "warmup"), 3, "com.google.android.webview 120.0"), event)
    }
}
