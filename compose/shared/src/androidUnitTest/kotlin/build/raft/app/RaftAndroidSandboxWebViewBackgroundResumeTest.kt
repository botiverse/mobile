package build.raft.app

import android.app.Activity
import android.os.Looper
import android.view.View
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
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hands 9769dc7a (#wg-mobile-feedback:#303):
 * - Background `onRenderProcessGone` (window not visible / not attached) destroys the dead WebView
 *   and defers `onContentProcessGone` + recreation/reload until the window becomes visible again.
 * - Parked keep-alive WebViews drop from [RaftAndroidWebViewKeepAlive] on `onRenderProcessGone`
 *   instead of recreating and reloading in the background.
 * - Foreground visible WebViews still recover immediately as before.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftAndroidSandboxWebViewBackgroundResumeTest {
    private val webOrigin = "https://app.raft.build"
    private val artifactUrl = "$webOrigin/artifacts/pt_bg_resume_1?embed=raft-settings-v1&shell=host"
    private val secondArtifactUrl = "$webOrigin/artifacts/pt_bg_resume_2?embed=raft-settings-v1&shell=host"
    private val keepAliveKey = "artifact:test:bg-resume"

    @Before
    fun setUp() {
        RaftWebViewRendererGone.resetForTest()
        RaftAndroidWebViewKeepAlive.drop("test_setup")
    }

    @After
    fun tearDown() {
        RaftAndroidWebViewKeepAlive.drop("test_cleanup")
        RaftWebViewRendererGone.resetForTest()
    }

    private fun detail(didCrash: Boolean, priority: Int = WebView.RENDERER_PRIORITY_WAIVED): RenderProcessGoneDetail =
        object : RenderProcessGoneDetail() {
            override fun didCrash(): Boolean = didCrash

            override fun rendererPriorityAtExit(): Int = priority
        }

    private fun artifactConfig(
        url: String = artifactUrl,
        injectedJavaScript: String = "",
        keepAlive: String? = keepAliveKey,
    ): RaftWebViewConfig =
        RaftWebViewConfig(
            url = url,
            allowScripts = true,
            allowNavigation = true,
            domStorageEnabled = true,
            useWideViewPort = false,
            cacheEnabled = true,
            ephemeral = false,
            injectedJavaScript = injectedJavaScript,
            allowedNavigationSchemes = setOf("https"),
            hostEventsEnabled = false,
            mainFrameOrigin = webOrigin,
            keepAliveKey = keepAlive,
            diagnosticsSurface = RAFT_ARTIFACT_VIEWER_WEBVIEW_SURFACE,
        )

    private fun attachedHost(
        events: RaftWebViewEvents,
        config: RaftWebViewConfig = artifactConfig(),
    ): Pair<Activity, RaftAndroidSandboxWebView> {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val host = RaftAndroidSandboxWebView(activity, events)
        activity.setContentView(host)
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
        host.applyConfig(config)
        return activity to host
    }

    private fun WebView.finishLoading(url: String) {
        val client = shadowOf(this).webViewClient
        client.onPageStarted(this, url, null)
        client.onPageFinished(this, url)
    }

    @Test
    fun renderProcessGoneWhileBackgroundedDefersRecoveryUntilWindowVisible() {
        val processGoneReasons = mutableListOf<String>()
        val events = RaftWebViewEvents().apply { onContentProcessGone = { processGoneReasons += it } }
        val (_, host) = attachedHost(events)
        val initialWebView = host.getChildAt(0) as WebView
        initialWebView.finishLoading(artifactUrl)
        assertEquals(artifactUrl, shadowOf(initialWebView).lastLoadedUrl)

        // Move window to background (e.g. user switched to another app / locked screen).
        host.dispatchWindowVisibilityChanged(View.GONE)

        // Android reclaims the renderer in the background.
        assertTrue(shadowOf(initialWebView).webViewClient.onRenderProcessGone(initialWebView, detail(didCrash = false)))
        assertTrue(shadowOf(initialWebView).wasDestroyCalled(), "dead WebView must be destroyed immediately")
        assertEquals(0, host.childCount, "must not recreate a WebView while backgrounded")
        assertTrue(host.hasPendingRenderProcessRestore)
        assertTrue(processGoneReasons.isEmpty(), "must not trigger onContentProcessGone while backgrounded")
        assertNull(host.keptDocument(), "pending-restore host must not report a kept document")
        assertFalse(RaftAndroidWebViewKeepAlive.park(host), "must refuse parking a view awaiting renderer recovery")

        // Config update with unchanged URL (e.g. rotated token script) while pending must not reload in background.
        host.applyConfig(artifactConfig(injectedJavaScript = "window.__token = 'fresh';"))
        assertEquals(0, host.childCount)
        assertTrue(host.hasPendingRenderProcessRestore)
        assertTrue(processGoneReasons.isEmpty())

        // User returns to foreground: pending restore runs, notifies watchdog once, and reloads the Artifact.
        host.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertFalse(host.hasPendingRenderProcessRestore)
        assertEquals(listOf("render_process_killed"), processGoneReasons)
        assertEquals(1, host.childCount)
        val recreated = host.getChildAt(0) as WebView
        assertNotSame(initialWebView, recreated)
        assertTrue(
            shadowOf(recreated).lastLoadedUrl == artifactUrl ||
                shadowOf(recreated).lastLoadDataWithBaseURL?.baseUrl?.startsWith(webOrigin) == true,
            "recreated WebView should load restored artifact URL on foreground return",
        )
    }

    @Test
    fun deferredRestoreIsClearedOnDestroyAndSupersededByNewUrlOrControllerLoad() {
        val processGoneReasons = mutableListOf<String>()
        val events = RaftWebViewEvents().apply { onContentProcessGone = { processGoneReasons += it } }

        // Case 1: destroy() while pending cancels recovery completely.
        val (_, destroyedHost) = attachedHost(events)
        val dyingA = destroyedHost.getChildAt(0) as WebView
        dyingA.finishLoading(artifactUrl)
        destroyedHost.dispatchWindowVisibilityChanged(View.GONE)
        assertTrue(shadowOf(dyingA).webViewClient.onRenderProcessGone(dyingA, detail(didCrash = false)))
        assertTrue(destroyedHost.hasPendingRenderProcessRestore)
        destroyedHost.destroy()
        assertFalse(destroyedHost.hasPendingRenderProcessRestore)
        destroyedHost.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals(0, destroyedHost.childCount)
        assertTrue(processGoneReasons.isEmpty())

        // Case 2: applyConfig with a new URL while pending supersedes the old restore cleanly.
        val (_, updatedHost) = attachedHost(events)
        val dyingB = updatedHost.getChildAt(0) as WebView
        dyingB.finishLoading(artifactUrl)
        updatedHost.dispatchWindowVisibilityChanged(View.GONE)
        assertTrue(shadowOf(dyingB).webViewClient.onRenderProcessGone(dyingB, detail(didCrash = false)))
        updatedHost.applyConfig(artifactConfig(url = secondArtifactUrl))
        assertEquals(0, updatedHost.childCount, "new URL while backgrounded must wait for foreground")
        updatedHost.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertFalse(updatedHost.hasPendingRenderProcessRestore)
        assertEquals(1, updatedHost.childCount)
        assertTrue(processGoneReasons.isEmpty(), "superseded URL must not emit stale onContentProcessGone")
        val rebuiltB = updatedHost.getChildAt(0) as WebView
        assertEquals(secondArtifactUrl, shadowOf(rebuiltB).lastLoadedUrl)

        // Case 3: controller.loadUrl while pending supersedes the old restore cleanly.
        val controller = RaftWebViewController()
        val (_, controllerHost) = attachedHost(events)
        controllerHost.bindController(controller)
        val dyingC = controllerHost.getChildAt(0) as WebView
        dyingC.finishLoading(artifactUrl)
        controllerHost.dispatchWindowVisibilityChanged(View.GONE)
        assertTrue(shadowOf(dyingC).webViewClient.onRenderProcessGone(dyingC, detail(didCrash = false)))
        assertTrue(controller.loadUrl(secondArtifactUrl))
        assertEquals(0, controllerHost.childCount)
        controllerHost.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals(1, controllerHost.childCount)
        assertEquals(secondArtifactUrl, shadowOf(controllerHost.getChildAt(0) as WebView).lastLoadedUrl)
    }

    @Test
    fun renderProcessGoneWhileParkedForKeepAliveDropsWithoutReloading() {
        val app = RuntimeEnvironment.getApplication()
        RaftAndroidWebViewKeepAlive.prewarm(app, artifactConfig())
        val parkedHost = assertNotNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey))
        val parkedWebView = parkedHost.getChildAt(0) as WebView

        assertTrue(shadowOf(parkedWebView).webViewClient.onRenderProcessGone(parkedWebView, detail(didCrash = false)))
        assertTrue(shadowOf(parkedWebView).wasDestroyCalled(), "dead parked WebView must be destroyed")
        assertTrue(parkedHost.isDestroyed, "parked host must be destroyed instead of recreated")
        assertEquals(0, parkedHost.childCount, "parked host must not recreate/reload a WebView in background")
        assertNull(RaftAndroidWebViewKeepAlive.kept(keepAliveKey), "parked host must be evicted from keep-alive")
        assertNull(RaftAndroidWebViewKeepAlive.take(keepAliveKey, app, RaftWebViewEvents()))
    }

    @Test
    fun renderProcessGoneWhileForegroundVisibleRecoversImmediately() {
        val processGoneReasons = mutableListOf<String>()
        val events = RaftWebViewEvents().apply { onContentProcessGone = { processGoneReasons += it } }
        val (_, host) = attachedHost(events)
        val initialWebView = host.getChildAt(0) as WebView
        initialWebView.finishLoading(artifactUrl)

        assertTrue(host.isAttachedToWindow)
        assertEquals(View.VISIBLE, host.windowVisibility)
        assertTrue(shadowOf(initialWebView).webViewClient.onRenderProcessGone(initialWebView, detail(didCrash = true)))

        assertEquals(listOf("render_process_crashed"), processGoneReasons)
        assertFalse(host.hasPendingRenderProcessRestore)
        assertTrue(shadowOf(initialWebView).wasDestroyCalled())
        assertEquals(1, host.childCount)
        val recreated = host.getChildAt(0) as WebView
        assertNotSame(initialWebView, recreated)
        assertEquals(artifactUrl, shadowOf(recreated).lastLoadedUrl)
    }
}
