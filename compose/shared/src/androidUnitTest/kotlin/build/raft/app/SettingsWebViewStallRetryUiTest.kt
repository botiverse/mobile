package build.raft.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
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

            mainClock.advanceTimeBy(RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS - 1_000)
            waitForIdle()
            onNodeWithText("Could not load this page").assertDoesNotExist()

            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            onNodeWithText("Could not load this page").assertIsDisplayed()
            onNodeWithText("Check your connection and try again.").assertIsDisplayed()
            assertEquals(0, webViews().size, "the stalled WebView is dropped")

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

    private fun ComposeUiTest.webViews(): List<WebView> {
        val found = mutableListOf<WebView>()
        runOnUiThread { root?.let { collectWebViews(it, found) } }
        return found
    }

    private fun collectWebViews(view: View, into: MutableList<WebView>) {
        if (view is WebView) into += view
        if (view is ViewGroup) for (index in 0 until view.childCount) collectWebViews(view.getChildAt(index), into)
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
