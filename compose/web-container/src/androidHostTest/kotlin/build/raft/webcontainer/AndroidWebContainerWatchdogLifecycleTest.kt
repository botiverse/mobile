package build.raft.webcontainer

import android.app.Activity
import android.content.MutableContextWrapper
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.resilience.WebContainerPhase
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
import kotlin.test.assertTrue

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidWebContainerWatchdogLifecycleTest {
    private val createdHosts = mutableListOf<WebContainerAndroidView>()

    @Before
    fun setUp() {
        WebContainerAndroidKeepAlive.clearAll()
    }

    @After
    fun tearDown() {
        for (host in createdHosts) {
            host.destroy()
        }
        createdHosts.clear()
        WebContainerAndroidKeepAlive.clearAll()
    }

    private fun createAttachedHost(
        events: WebContainerEvents,
        config: WebContainerConfig =
            WebContainerConfig(
                url = "https://app.raft.build/oauth-handoff",
                allowScripts = false,
            ),
        policy: WebContainerWatchdogPolicy =
            WebContainerWatchdogPolicy(
                stallTimeoutMs = 15_000L,
                blankProbeDelayMs = 1_500L,
            ),
    ): Pair<Activity, WebContainerAndroidView> {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val host =
            WebContainerAndroidView(
                context = MutableContextWrapper(activity),
                initialConfig = config,
                events = events,
                jsbRegistry = JsbRegistry.Empty,
                resourceInterceptor = null,
                telemetrySink = null,
                fileChooser = null,
                watchdogPolicy = policy,
                nowMs = SystemClock::uptimeMillis,
            )
        createdHosts += host
        activity.setContentView(host)
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
        return activity to host
    }

    @Test
    fun windowVisibilityGonePausesWatchdogAndVisibleResumesRemainingBudget() {
        val terminalErrors = mutableListOf<String>()
        val events = WebContainerEvents(onWatchdogTerminalError = { terminalErrors += it })
        val (_, host) = createAttachedHost(events)
        val webView = host.getChildAt(0) as WebView

        // Start a main-frame load and spend 5s in foreground (10s stall budget remaining).
        assertFalse(
            host.isWatchdogBackgrounded,
            "attached=${host.isAttachedToWindow} winVis=${host.windowVisibility} bg=${host.isWatchdogBackgrounded} sched=${host.isWatchdogTickScheduled} hostVis=${host.visibility} decorVis=${host.rootView.visibility}",
        )
        assertTrue(host.isWatchdogTickScheduled, "attached visible host must schedule watchdog ticks")
        shadowOf(webView).webViewClient.onPageStarted(webView, "https://app.raft.build/oauth-handoff", null)
        assertEquals(WebContainerPhase.Loading, host.watchdogPhase, "onPageStarted must enter Loading phase")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(terminalErrors.isEmpty())

        // Window goes invisible/backgrounded (e.g. OAuth browser handoff or screen lock) for 60s.
        host.dispatchWindowVisibilityChanged(View.GONE)
        assertTrue(host.isWatchdogBackgrounded, "View.GONE must pause watchdog")
        assertFalse(host.isWatchdogTickScheduled, "View.GONE must cancel scheduled watchdog tick")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertTrue(terminalErrors.isEmpty(), "watchdog must not fire load_stall_timeout while window is GONE")

        // Return to visible window (including duplicate VISIBLE signal): background dwell excluded.
        host.dispatchWindowVisibilityChanged(View.VISIBLE)
        host.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertFalse(host.isWatchdogBackgrounded, "View.VISIBLE must resume watchdog")
        assertTrue(host.isWatchdogTickScheduled, "View.VISIBLE must re-schedule watchdog tick")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertTrue(terminalErrors.isEmpty(), "remaining foreground budget must not expire prematurely after resume")

        // Exceed remaining foreground budget -> watchdog resumes and fires load_stall_timeout once.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(
            listOf("load_stall_timeout"),
            terminalErrors,
            "attached=${host.isAttachedToWindow} winVis=${host.windowVisibility} phase=${host.watchdogPhase} bg=${host.isWatchdogBackgrounded} sched=${host.isWatchdogTickScheduled} t=${SystemClock.uptimeMillis()}",
        )
    }

    @Test
    fun windowVisibilityGoneSuppressesDomProbeUntilForegroundDelayElapses() {
        val probeResults = mutableListOf<Boolean?>()
        val terminalErrors = mutableListOf<String>()
        val events =
            WebContainerEvents(
                onContentProbe = { probeResults += it },
                onWatchdogTerminalError = { terminalErrors += it },
            )
        val (_, host) = createAttachedHost(events)
        val webView = host.getChildAt(0) as WebView
        val client = shadowOf(webView).webViewClient

        client.onPageStarted(webView, "https://app.raft.build/oauth-handoff", null)
        client.onPageFinished(webView, "https://app.raft.build/oauth-handoff")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(probeResults.isEmpty())

        // Hide window before blankProbeDelayMs (1500ms) finishes and dwell for 30s.
        host.dispatchWindowVisibilityChanged(View.GONE)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertTrue(probeResults.isEmpty(), "DOM probe must not run while window is GONE")
        assertTrue(terminalErrors.isEmpty())

        // Resume visible window: probe waits for remaining ~1000ms of foreground delay then runs once.
        host.dispatchWindowVisibilityChanged(View.VISIBLE)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(probeResults.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, probeResults.size)
        assertTrue(terminalErrors.isEmpty())
    }

    @Test
    fun keepAliveParkAndReattachPreservesWatchdogForegroundBudget() {
        val terminalErrors = mutableListOf<String>()
        val events = WebContainerEvents(onWatchdogTerminalError = { terminalErrors += it })
        val config =
            WebContainerConfig(
                url = "https://app.raft.build/kept-page",
                allowScripts = false,
                keepAliveKey = "watchdog-bg-test",
            )
        val (activity, host) = createAttachedHost(events, config = config)
        val webView = host.getChildAt(0) as WebView

        shadowOf(webView).webViewClient.onPageStarted(webView, config.url, null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))

        // Park for keep-alive and idle 60s off-window -> zero false stall errors.
        host.detachForKeepAlive()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertTrue(terminalErrors.isEmpty())

        // Re-attach from keep-alive back into a visible Activity window.
        host.attachFromKeepAlive(
            hostContext = activity,
            nextEvents = events,
            nextRegistry = JsbRegistry.Empty,
            nextInterceptor = null,
            nextTelemetry = null,
            nextFileChooser = null,
        )
        activity.setContentView(host)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertTrue(terminalErrors.isEmpty(), "parked keep-alive dwell must not consume stall budget")

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(
            listOf("load_stall_timeout"),
            terminalErrors,
            "attached=${host.isAttachedToWindow} winVis=${host.windowVisibility} phase=${host.watchdogPhase} bg=${host.isWatchdogBackgrounded} sched=${host.isWatchdogTickScheduled} t=${SystemClock.uptimeMillis()}",
        )
    }
}
