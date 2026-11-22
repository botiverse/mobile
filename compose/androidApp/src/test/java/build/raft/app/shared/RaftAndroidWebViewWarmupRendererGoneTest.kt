package build.raft.app.shared

import android.app.Application
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import build.raft.app.RaftDetachedWebViewClient
import build.raft.app.RaftWebViewRendererGone
import build.raft.app.RaftWebViewRendererGoneDailyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The kept warm-up WebView shares the app's renderer: when it dies, the warm instance's client
 * must claim the death (return true, or Chromium kills the app), let the instance go and destroy
 * it. Drives the production construction path ([RaftAndroidWebViewWarmup.newWarmWebView]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftAndroidWebViewWarmupRendererGoneTest {
    private val warnings = mutableListOf<String>()
    private val tickets = mutableListOf<Map<String, Any?>>()

    @Before
    fun setUp() {
        RaftAndroidWebViewWarmup.resetForTest()
        RaftAndroidWebViewWarmup.log = { }
        RaftAndroidWebViewWarmup.logWarn = { warnings += it }
        RaftAndroidWebViewWarmup.dispatch = { task ->
            task()
            true
        }
        RaftWebViewRendererGone.dailyStore = RaftWebViewRendererGoneDailyStore.InMemory()
        RaftWebViewRendererGone.ticketSink = { _, extras, done ->
            tickets += extras
            done(true)
        }
    }

    @After
    fun tearDown() {
        RaftAndroidWebViewWarmup.resetForTest()
        RaftWebViewRendererGone.ticketSink = null
        RaftWebViewRendererGone.dailyStore = RaftWebViewRendererGoneDailyStore.InMemory()
    }

    private fun gone(didCrash: Boolean, priority: Int): RenderProcessGoneDetail =
        object : RenderProcessGoneDetail() {
            override fun didCrash(): Boolean = didCrash

            override fun rendererPriorityAtExit(): Int = priority
        }

    private fun warmWebView(): WebView {
        RaftAndroidWebViewWarmup.schedule(RuntimeEnvironment.getApplication())
        return RaftAndroidWebViewWarmup.warmInstanceForTest as WebView
    }

    @Test
    fun theWarmInstanceClaimsItsRendererDeathAndIsReleased() {
        val warm = warmWebView()
        val client = shadowOf(warm).webViewClient
        assertTrue("the warm instance holds the detached client", client is RaftDetachedWebViewClient)

        val handled = client.onRenderProcessGone(warm, gone(didCrash = true, priority = WebView.RENDERER_PRIORITY_BOUND))

        assertTrue("returning false lets Chromium kill the app", handled)
        assertNull("the warm instance is let go", RaftAndroidWebViewWarmup.warmInstanceForTest)
        assertTrue("the warm WebView is destroyed", shadowOf(warm).wasDestroyCalled())
        assertEquals(
            listOf("webview_warmup released reason=render_process_gone didCrash=true priority=${WebView.RENDERER_PRIORITY_BOUND} held=true"),
            warnings,
        )
        // The death is reported once its turn ends, as the warm-up surface.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("warmup", tickets.single()["webview_surfaces"])
    }
}
