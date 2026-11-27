package build.raft.app

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.TestWebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.settings.RAFT_WEB_PAGE_LOADING_BAR_TAG
import build.raft.app.settings.SettingsWebViewContent
import build.raft.app.settings.SettingsWebViewErrorContent
import build.raft.app.settings.settingsWebViewErrorBody
import build.raft.designsystem.components.RaftPageLoadingBarAppearDelayMillis
import build.raft.designsystem.components.RaftPageLoadingBarFadeOutMillis
import build.raft.designsystem.components.RaftPageLoadingBarProgressMillis
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * dm #102: a Settings web page that never becomes ready is replaced by the error state after the
 * stall timeout (instead of a white page forever); Retry builds a new WebView (the stalled one is
 * gone, none piles up) with a new timer. Robolectric's WebView loads nothing, so every attempt stalls
 * unless the test finishes it through its WebViewClient.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class SettingsWebViewStallRetryUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType
    private var root: View? = null
    private val url = "https://app.raft.build/s/raft/settings/billing"

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun stalledPageShowsTheErrorAndRetryLoadsAFreshWebView() =
        runRobolectricComposeUiTest {
            setContent { Host { SettingsWebViewContent(url = url, diagnosticsName = "settings_billing") } }
            waitForIdle()
            assertEquals(1, webViews().size, "the page's WebView is attached")
            val first = webViews().single()
            onNodeWithText("Could not load this page").assertDoesNotExist()
            showLoadLine()
            onNodeWithTag(RAFT_WEB_PAGE_LOADING_BAR_TAG).assertExists()

            mainClock.advanceTimeBy(RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS - 1_000 - LOAD_LINE_APPEAR_MS)
            waitForIdle()
            onNodeWithText("Could not load this page").assertDoesNotExist()

            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            onNodeWithText("Could not load this page").assertIsDisplayed()
            onNodeWithText("Check your connection and try again.").assertIsDisplayed()
            assertEquals(0, webViews().size, "the stalled WebView is dropped")
            onNodeWithTag(RAFT_WEB_PAGE_LOADING_BAR_TAG).assertDoesNotExist()

            onNodeWithText("Retry").assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithText("Could not load this page").assertDoesNotExist()
            val second = webViews().single()
            assert(second !== first) { "Retry builds a new WebView" }

            // The new attempt has its own timer.
            mainClock.advanceTimeBy(RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS + 1_000)
            waitForIdle()
            onNodeWithText("Could not load this page").assertIsDisplayed()
            assertEquals(0, webViews().size)
        }

    @Test
    fun aFinishedLoadIsNotReadyUntilTheContentProbeSaysSo() =
        runRobolectricComposeUiTest {
            setContent { Host { SettingsWebViewContent(url = url, diagnosticsName = "settings_billing") } }
            waitForIdle()
            val webView = webViews().single()
            runOnUiThread {
                val client = shadowOf(webView).webViewClient
                client.onPageStarted(webView, url, null)
                client.onPageFinished(webView, url)
            }
            waitForIdle()
            mainClock.advanceTimeBy(RAFT_WEB_VIEW_CONTENT_PROBE_INTERVAL_MS)
            waitForIdle()
            // The finish asked the page whether it shows anything ...
            assertEquals(RaftWebViewContentProbeScript, shadowOf(webView).lastEvaluatedJavascript)
            onNodeWithText("Could not load this page").assertDoesNotExist()

            // ... and a page that never answers (Robolectric's never does) is not ready: it stalls.
            mainClock.advanceTimeBy(RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS)
            waitForIdle()
            onNodeWithText("Could not load this page").assertIsDisplayed()
        }

    @Test
    fun renderProcessGoneBeforeReadyShowsTheCrashErrorWithRetry() =
        runRobolectricComposeUiTest {
            setContent { Host { SettingsWebViewContent(url = url, diagnosticsName = "settings_billing") } }
            waitForIdle()
            val webView = webViews().single()
            runOnUiThread { shadowOf(webView).webViewClient.onRenderProcessGone(webView, null) }
            waitForIdle()
            onNodeWithText("Could not load this page").assertIsDisplayed()
            onNodeWithText("The page stopped unexpectedly. Try again.").assertIsDisplayed()
            assertEquals(0, webViews().size)

            onNodeWithText("Retry").performClick()
            waitForIdle()
            onNodeWithText("Could not load this page").assertDoesNotExist()
            assertEquals(1, webViews().size)
        }

    @Test
    fun backgroundDwellDoesNotStallAndBackgroundRenderGoneRecoversOnForegroundReturn() =
        runRobolectricComposeUiTest {
            val owner =
                object : LifecycleOwner {
                    val registry = LifecycleRegistry.createUnsafe(this)
                    override val lifecycle: Lifecycle get() = registry
                }.apply { registry.currentState = Lifecycle.State.RESUMED }
            lateinit var watchdog: RaftWebViewLoadWatchdog
            setContent {
                Host {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        val controller = rememberRaftWebViewController()
                        watchdog = remember { RaftWebViewLoadWatchdog(page = "artifact_viewer", url = url) }
                        if (!watchdog.showsError) {
                            RaftSandboxWebView(
                                url = url,
                                modifier = Modifier.fillMaxSize(),
                                allowNavigation = true,
                                domStorageEnabled = true,
                                controller = controller,
                                loadWatchdog = watchdog,
                            )
                        } else {
                            SettingsWebViewErrorContent(
                                actionLabel = "Retry",
                                actionIcon = RaftLucideIconName.RefreshCw,
                                body = settingsWebViewErrorBody(watchdog.state.phase),
                                onAction = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()
            assertEquals(1, webViews().size)

            // Part 1: Backgrounding mid-load pauses the 15s stall countdown so background dwell never stalls.
            runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            waitForIdle()
            mainClock.advanceTimeBy(RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS * 2)
            waitForIdle()
            onNodeWithText("Could not load this page").assertDoesNotExist()
            assertEquals(RaftWebViewLoadPhase.Loading, watchdog.state.phase)

            // Return to foreground and finish loading cleanly to Ready.
            runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            waitForIdle()
            val first = webViews().single()
            val sandbox = first.parent as RaftAndroidSandboxWebView
            runOnUiThread {
                val client = shadowOf(first).webViewClient
                client.onPageStarted(first, url, null)
                client.onPageFinished(first, url)
                watchdog.onPageReadyMessage()
            }
            waitForIdle()
            assertEquals(RaftWebViewLoadPhase.Ready, watchdog.state.phase)

            // Part 2: Ready Artifact goes to background, Android kills renderer, app stays in background > 15s.
            runOnIdle {
                owner.registry.currentState = Lifecycle.State.CREATED
                sandbox.dispatchWindowVisibilityChanged(View.GONE)
                shadowOf(first).webViewClient.onRenderProcessGone(first, null)
            }
            waitForIdle()
            mainClock.advanceTimeBy(RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS * 2)
            waitForIdle()
            onNodeWithText("Could not load this page").assertDoesNotExist()
            onNodeWithText("Check your connection and try again.").assertDoesNotExist()
            assertEquals(RaftWebViewLoadPhase.Ready, watchdog.state.phase)
            assertEquals(0, webViews().size, "dead WebView is destroyed and replacement waits for foreground")

            // User returns to foreground: WebView is recreated and watchdog re-arms with a fresh foreground load.
            runOnIdle {
                owner.registry.currentState = Lifecycle.State.RESUMED
                sandbox.dispatchWindowVisibilityChanged(View.VISIBLE)
            }
            waitForIdle()
            onNodeWithText("Could not load this page").assertDoesNotExist()
            onNodeWithText("Check your connection and try again.").assertDoesNotExist()
            assertEquals(RaftWebViewLoadPhase.Loading, watchdog.state.phase)
            assertEquals(1, webViews().size)
            assert(webViews().single() !== first) { "foreground resume should recreate a fresh WebView" }
        }

    @Test
    fun artifactViewerMainFrameLoadErrorClearsSplashAndShowsRetry() =
        runRobolectricComposeUiTest {
            val artifactUrl = "https://app.raft.build/artifacts/pt_a24673bc-23f2-49cd-81db-0810ea146d64"
            lateinit var watchdog: RaftWebViewLoadWatchdog
            setContent {
                Host {
                    var retry by remember { mutableStateOf(0) }
                    key(retry) {
                        val controller = rememberRaftWebViewController()
                        watchdog = remember { RaftWebViewLoadWatchdog(page = "artifact_viewer", url = artifactUrl, retry = retry) }
                        var platformLoadError by remember { mutableStateOf<String?>(null) }
                        var visible by remember { mutableStateOf(false) }
                        val loadError =
                            raftArtifactViewerHasLoadError(
                                platformLoadError = platformLoadError,
                                watchdogShowsError = watchdog.showsError,
                            )
                        val indicator =
                            raftArtifactViewerLoadingIndicator(
                                RaftArtifactViewerOpenState(
                                    sessionRead = true,
                                    documentShown = visible,
                                    reusesKeptPage = false,
                                    loadError = loadError,
                                    sinceDocumentReadyMs = null,
                                ),
                            )
                        if (!loadError) {
                            RaftSandboxWebView(
                                url = artifactUrl,
                                modifier = Modifier.fillMaxSize(),
                                allowNavigation = true,
                                domStorageEnabled = true,
                                controller = controller,
                                loadWatchdog = watchdog,
                                onLoadCommit = {
                                    platformLoadError = null
                                    visible = true
                                },
                                onLoadFailure = { reason ->
                                    visible = false
                                    platformLoadError = reason.ifBlank { "webview_load_failed" }
                                },
                            )
                        } else {
                            SettingsWebViewErrorContent(
                                actionLabel = "Retry",
                                actionIcon = RaftLucideIconName.RefreshCw,
                                body = settingsWebViewErrorBody(watchdog.state.phase),
                                onAction = { retry += 1 },
                            )
                        }
                        if (indicator == RaftArtifactViewerLoadingIndicator.AppSplash) {
                            Box(Modifier.fillMaxSize().testTag(RAFT_ARTIFACT_VIEWER_SPLASH_COVER_TAG))
                        }
                    }
                }
            }
            waitForIdle()
            assertEquals(1, webViews().size)
            val first = webViews().single()
            onNodeWithTag(RAFT_ARTIFACT_VIEWER_SPLASH_COVER_TAG).assertExists()
            onNodeWithText("Could not load this page").assertDoesNotExist()

            // Reproduce Hands a9f83f40: main-frame ERROR_CONNECT (-6) followed by Chromium error page finish.
            val mainFrameReq =
                object : WebResourceRequest {
                    override fun getUrl(): Uri = Uri.parse(artifactUrl)
                    override fun isForMainFrame(): Boolean = true
                    override fun isRedirect(): Boolean = false
                    override fun hasGesture(): Boolean = false
                    override fun getMethod(): String = "GET"
                    override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
                }
            runOnUiThread {
                val client = shadowOf(first).webViewClient
                client.onPageStarted(first, artifactUrl, null)
                client.onReceivedError(
                    first,
                    mainFrameReq,
                    TestWebResourceError(WebViewClient.ERROR_CONNECT, "net::ERR_CONNECTION_REFUSED"),
                )
                client.onPageFinished(first, artifactUrl)
            }
            waitForIdle()

            // Immediately clears the splash and shows the connection error + Retry (no infinite splash).
            assertEquals(RaftWebViewLoadPhase.Failed, watchdog.state.phase)
            assertEquals(0, webViews().size, "failed WebView must be unmounted")
            onNodeWithTag(RAFT_ARTIFACT_VIEWER_SPLASH_COVER_TAG).assertDoesNotExist()
            onNodeWithText("Could not load this page").assertIsDisplayed()
            onNodeWithText("Check your connection and try again.").assertIsDisplayed()

            onNodeWithText("Retry").assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithText("Could not load this page").assertDoesNotExist()
            onNodeWithTag(RAFT_ARTIFACT_VIEWER_SPLASH_COVER_TAG).assertExists()
            val second = webViews().single()
            assert(second !== first) { "Retry must mount a fresh WebView" }
        }

    /** Lets the load line's appear delay pass. */
    private fun ComposeUiTest.showLoadLine() {
        mainClock.advanceTimeBy(LOAD_LINE_APPEAR_MS)
        waitForIdle()
    }

    private fun ComposeUiTest.webViews(): List<WebView> {
        val found = mutableListOf<WebView>()
        runOnUiThread { root?.let { collectWebViews(it, found) } }
        return found
    }

    private fun collectWebViews(view: View, into: MutableList<WebView>) {
        if (view is WebView) into += view
        if (view is ViewGroup) for (index in 0 until view.childCount) collectWebViews(view.getChildAt(index), into)
    }

    private companion object {
        /** Past the line's appear delay, with room for the frames around it. */
        const val LOAD_LINE_APPEAR_MS = RaftPageLoadingBarAppearDelayMillis + 100L

        /** One frame of the test clock. */
        const val FRAME_MS = 16L

        /** Past the line's run to the end and its fade. */
        const val LOAD_LINE_EXIT_MS = RaftPageLoadingBarProgressMillis + RaftPageLoadingBarFadeOutMillis + 200L
    }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
        root = LocalView.current.rootView
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme { Box(Modifier.fillMaxSize()) { content() } }
        }
    }
}
