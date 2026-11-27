package build.raft.webcontainer

import android.app.Activity
import android.content.MutableContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import build.raft.webcontainer.jsb.JsbRegistry
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
import kotlin.test.assertTrue

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebContainerAndroidBackgroundAndInsetsTest {
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

    private fun createAttachedHost(config: WebContainerConfig): Pair<WebContainerAndroidView, WebView> {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val host =
            WebContainerAndroidView(
                context = MutableContextWrapper(activity),
                initialConfig = config,
                events = WebContainerEvents(),
                jsbRegistry = JsbRegistry.Empty,
                resourceInterceptor = null,
                telemetrySink = null,
                fileChooser = null,
                watchdogPolicy = WebContainerWatchdogPolicy(),
                nowMs = SystemClock::uptimeMillis,
            )
        createdHosts += host
        activity.setContentView(host)
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
        return host to (host.getChildAt(0) as WebView)
    }

    private fun sampleWindowInsets(
        statusBarTop: Int = 72,
        navBarBottom: Int = 48,
        imeBottom: Int = 620,
        cutoutLeft: Int = 0,
        cutoutTop: Int = 72,
        cutoutRight: Int = 0,
        cutoutBottom: Int = 0,
    ): WindowInsetsCompat =
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarTop, 0, 0))
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarTop, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBarBottom))
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBarBottom))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom))
            .setInsets(
                WindowInsetsCompat.Type.displayCutout(),
                Insets.of(cutoutLeft, cutoutTop, cutoutRight, cutoutBottom),
            )
            .setInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.displayCutout(),
                Insets.of(cutoutLeft, cutoutTop, cutoutRight, cutoutBottom),
            )
            .build()

    @Test
    fun filterWebContainerAndroidInsetsHonorsAllThreeModes() {
        val raw = sampleWindowInsets(cutoutLeft = 16, cutoutTop = 72, cutoutRight = 0, cutoutBottom = 0)

        val hostHandled = filterWebContainerAndroidInsets(raw, WebContainerAndroidInsetMode.HostHandled)
        assertEquals(Insets.NONE, hostHandled.getInsets(WindowInsetsCompat.Type.ime()))
        assertEquals(Insets.NONE, hostHandled.getInsets(WindowInsetsCompat.Type.systemBars()))
        assertEquals(Insets.NONE, hostHandled.getInsets(WindowInsetsCompat.Type.statusBars()))
        assertEquals(Insets.NONE, hostHandled.getInsets(WindowInsetsCompat.Type.navigationBars()))
        assertEquals(Insets.NONE, hostHandled.getInsets(WindowInsetsCompat.Type.displayCutout()))

        val nativeImeOnly = filterWebContainerAndroidInsets(raw, WebContainerAndroidInsetMode.NativeImeHostNavBar)
        assertEquals(Insets.of(0, 0, 0, 620), nativeImeOnly.getInsets(WindowInsetsCompat.Type.ime()))
        assertEquals(Insets.NONE, nativeImeOnly.getInsets(WindowInsetsCompat.Type.systemBars()))
        assertEquals(Insets.NONE, nativeImeOnly.getInsets(WindowInsetsCompat.Type.navigationBars()))
        assertEquals(Insets.NONE, nativeImeOnly.getInsets(WindowInsetsCompat.Type.displayCutout()))

        val nativeImeAndBottom =
            filterWebContainerAndroidInsets(raw, WebContainerAndroidInsetMode.NativeImeAndBottomSystemBars)
        assertEquals(Insets.of(0, 0, 0, 620), nativeImeAndBottom.getInsets(WindowInsetsCompat.Type.ime()))
        assertEquals(Insets.NONE, nativeImeAndBottom.getInsets(WindowInsetsCompat.Type.statusBars()))
        assertEquals(Insets.of(0, 0, 0, 48), nativeImeAndBottom.getInsets(WindowInsetsCompat.Type.navigationBars()))
        assertEquals(Insets.of(0, 0, 0, 48), nativeImeAndBottom.getInsets(WindowInsetsCompat.Type.systemBars()))
        assertEquals(Insets.of(16, 0, 0, 0), nativeImeAndBottom.getInsets(WindowInsetsCompat.Type.displayCutout()))

        val topOnlyCutout = sampleWindowInsets(cutoutLeft = 0, cutoutTop = 72, cutoutRight = 0, cutoutBottom = 0)
        val filteredTopOnly =
            filterWebContainerAndroidInsets(topOnlyCutout, WebContainerAndroidInsetMode.NativeImeAndBottomSystemBars)
        assertEquals(Insets.NONE, filteredTopOnly.getInsets(WindowInsetsCompat.Type.displayCutout()))
    }

    @Test
    fun defaultBackgroundRevertsToWhiteAfterPaintWhilePersistedBackgroundTracksTheme() {
        val darkCanvas = 0xFF1B1B19.toInt()
        val lightCanvas = 0xFFFAF9F7.toInt()
        val url = "https://app.raft.build/artifacts/pt_1"

        val (_, defaultWebView) =
            createAttachedHost(
                WebContainerConfig(
                    url = url,
                    backgroundArgb = darkCanvas,
                    persistBackgroundAfterPaint = false,
                ),
            )
        assertEquals(darkCanvas, (defaultWebView.background as ColorDrawable).color)
        shadowOf(defaultWebView).webViewClient.onPageCommitVisible(defaultWebView, url)
        assertEquals(Color.WHITE, (defaultWebView.background as ColorDrawable).color)

        val persistedConfig =
            WebContainerConfig(
                url = url,
                backgroundArgb = darkCanvas,
                persistBackgroundAfterPaint = true,
                isDarkTheme = true,
            )
        val (persistedHost, persistedWebView) = createAttachedHost(persistedConfig)
        assertEquals(darkCanvas, (persistedWebView.background as ColorDrawable).color)
        val client = shadowOf(persistedWebView).webViewClient
        client.onPageCommitVisible(persistedWebView, url)
        client.onPageFinished(persistedWebView, url)
        assertEquals(darkCanvas, (persistedWebView.background as ColorDrawable).color)

        // Live theme switch updates the painted WebView background immediately without waiting for reload.
        persistedHost.applyConfig(persistedConfig.copy(backgroundArgb = lightCanvas, isDarkTheme = false))
        assertEquals(lightCanvas, (persistedWebView.background as ColorDrawable).color)

        // Renderer death recovery preserves persistent background color across WebView recreation.
        assertTrue(shadowOf(persistedWebView).webViewClient.onRenderProcessGone(persistedWebView, null))
        val recreated = persistedHost.getChildAt(0) as WebView
        assertEquals(lightCanvas, (recreated.background as ColorDrawable).color)
        shadowOf(recreated).webViewClient.onPageCommitVisible(recreated, url)
        assertEquals(lightCanvas, (recreated.background as ColorDrawable).color)
    }

    @Test
    fun androidInsetListenerAppliesConfiguredModeAcrossUpdatesAndRecreation() {
        val initialConfig =
            WebContainerConfig(
                url = "https://app.raft.build/artifacts/pt_1",
                androidInsetMode = WebContainerAndroidInsetMode.HostHandled,
            )
        val (host, webView) = createAttachedHost(initialConfig)
        val raw = sampleWindowInsets()

        val appliedHostHandled = ViewCompat.dispatchApplyWindowInsets(webView, raw)
        assertEquals(Insets.NONE, appliedHostHandled.getInsets(WindowInsetsCompat.Type.ime()))
        assertEquals(Insets.NONE, appliedHostHandled.getInsets(WindowInsetsCompat.Type.systemBars()))

        host.applyConfig(initialConfig.copy(androidInsetMode = WebContainerAndroidInsetMode.NativeImeHostNavBar))
        val appliedImeOnly = ViewCompat.dispatchApplyWindowInsets(webView, raw)
        assertEquals(Insets.of(0, 0, 0, 620), appliedImeOnly.getInsets(WindowInsetsCompat.Type.ime()))
        assertEquals(Insets.NONE, appliedImeOnly.getInsets(WindowInsetsCompat.Type.systemBars()))

        host.applyConfig(initialConfig.copy(androidInsetMode = WebContainerAndroidInsetMode.NativeImeAndBottomSystemBars))
        val appliedImeAndBottom = ViewCompat.dispatchApplyWindowInsets(webView, raw)
        assertEquals(Insets.of(0, 0, 0, 620), appliedImeAndBottom.getInsets(WindowInsetsCompat.Type.ime()))
        assertEquals(Insets.NONE, appliedImeAndBottom.getInsets(WindowInsetsCompat.Type.statusBars()))
        assertEquals(Insets.of(0, 0, 0, 48), appliedImeAndBottom.getInsets(WindowInsetsCompat.Type.navigationBars()))

        // Recreation re-attaches the inset listener with the current mode.
        assertTrue(shadowOf(webView).webViewClient.onRenderProcessGone(webView, null))
        val recreated = host.getChildAt(0) as WebView
        val appliedRecreated = ViewCompat.dispatchApplyWindowInsets(recreated, raw)
        assertEquals(Insets.of(0, 0, 0, 620), appliedRecreated.getInsets(WindowInsetsCompat.Type.ime()))
        assertEquals(Insets.of(0, 0, 0, 48), appliedRecreated.getInsets(WindowInsetsCompat.Type.navigationBars()))
    }
}
