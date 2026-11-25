package build.raft.app

import android.app.Activity
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftWebViewRendererGoneTest {
    private val tickets = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun setUp() {
        RaftWebViewRendererGone.resetForTest()
        RaftWebViewRendererGone.ticketSink = { message, extras, done ->
            tickets += message to extras
            done(true)
        }
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

    private fun sandbox(surface: String): RaftAndroidSandboxWebView {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        return RaftAndroidSandboxWebView(activity, RaftWebViewEvents()).apply {
            activity.setContentView(this)
            controller.start().resume().visible()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
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
}

/**
 * [RaftWebViewRendererGoneTicketGate]: crashes only, one a minute per process, a cap per UTC day,
 * counted only for tickets Hands accepted.
 */
class RaftWebViewRendererGoneTicketGateTest {
    private val crash = RaftWebViewRendererGoneEvent(true, WebView.RENDERER_PRIORITY_BOUND, listOf("warmup"), 1, "pkg 1")
    private val dayStart = 20_000L * 24 * 60 * 60 * 1000 // 00:00 UTC

    /** Reserve, and settle a filed ticket as [accepted] at once. */
    private fun RaftWebViewRendererGoneTicketGate.decide(
        event: RaftWebViewRendererGoneEvent,
        at: Long,
        accepted: Boolean = true,
    ): RaftWebViewRendererGoneTicketDecision {
        val reservation = reserve(event, at)
        if (reservation.decision == RaftWebViewRendererGoneTicketDecision.File) settle(reservation, accepted)
        return reservation.decision
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
}
