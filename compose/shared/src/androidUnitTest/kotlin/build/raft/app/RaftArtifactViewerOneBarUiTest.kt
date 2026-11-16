package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #proj-workspace:#301 / the product lead's recording ("two top bars, two spinners"): from tap to `hostLoaded=1` the
 * Artifact viewer shows exactly one top bar and one loader, the App's port of the web's pixel splash.
 *
 * The page's first stage in the App (slock ArtifactPageLoadingLayout with `shell=host&back=host`) is a toolbar row
 * with Back alone, at the WebView's top. Before, a reused prewarmed page was never covered, so that row stood under the
 * App's bar: "← title ⋮" and a second "←". Here the viewer screen's own frame ([RaftArtifactViewerScreenFrame]) and
 * its splash cover ([RaftArtifactViewerSplashCover]) are composed over a stand-in for that page:
 * - before the page draws its bar: the App's bar, the cover over the whole body (the page's row hidden), no generic
 *   "Loading…" (RaftCenteredLoading);
 * - once it does (`hostToolbar=1`): no App bar, the page's bar uncovered, the splash where it was.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RaftArtifactViewerOneBarUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var pageDrawsBar by mutableStateOf(false)

    private fun ComposeUiTest.showViewer() {
        // The splash animates every frame: the clock is driven by hand.
        mainClock.autoAdvance = false
        // Past its 1-second wait: the icon and its line show.
        val openedAt = TimeSource.Monotonic.markNow() - 2.seconds
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            val kinds = remember { RaftArtifactSplashKindState(null) }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.requiredSize(411.dp, 891.dp).testTag("screen")) {
                        RaftArtifactViewerScreenFrame(
                            title = "Plan",
                            onBack = {},
                            pageSaysItDrawsBar = pageDrawsBar,
                            viewing = true,
                            scopeKnown = true,
                            actions = RaftArtifactViewerBarActions(onOpenInBrowser = {}),
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                // The WebView: the page's toolbar row at its top (Back alone while it loads; its bar once
                                // it draws the only one).
                                Box(Modifier.fillMaxSize().testTag("web")) {
                                    BasicText("page-row", Modifier.fillMaxWidth().height(56.dp).testTag("page-row"))
                                }
                                RaftArtifactViewerSplashCover(kinds, openedAt, desktopMode = false, underPageBar = pageDrawsBar)
                            }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
    }

    private fun ComposeUiTest.bounds(tag: String): Rect =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private val appBarButtons get() = hasAnyAncestor(hasTestTag("screen")) and hasClickAction() and !hasAnyAncestor(hasTestTag("web"))

    private fun ComposeUiTest.assertOneLoaderAndItIsTheSplash() {
        onAllNodesWithText("Loading…", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithTag(RAFT_ARTIFACT_LOADING_SPLASH_TAG, useUnmergedTree = true).assertCountEquals(1)
        onAllNodesWithTag(RAFT_ARTIFACT_VIEWER_SPLASH_COVER_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun fromTheFirstFrameToHostLoadedOneBarAndThePixelSplash() =
        runRobolectricComposeUiTest {
            showViewer()

            // Before the page draws its bar: the App's (Back and ⋮), and the page's own row is under the cover.
            onAllNodes(appBarButtons).assertCountEquals(2)
            assertOneLoaderAndItIsTheSplash()
            val cover = bounds(RAFT_ARTIFACT_VIEWER_SPLASH_COVER_TAG)
            val row = bounds("page-row")
            assertTrue(cover.top <= row.top && cover.bottom >= row.bottom, "the page's loading row is covered: no second bar ($cover, $row)")
            assertTrue(bounds("web").top > bounds("screen").top + 20f, "the page under the App's bar")

            // The page draws the only bar (`hostToolbar=1`), still loading: the App's bar is gone, the page's shows.
            pageDrawsBar = true
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            onAllNodes(appBarButtons).assertCountEquals(0)
            assertOneLoaderAndItIsTheSplash()
            val coverUnderPageBar = bounds(RAFT_ARTIFACT_VIEWER_SPLASH_COVER_TAG)
            val pageBar = bounds("page-row")
            assertTrue(pageBar.bottom <= coverUnderPageBar.top + 0.5f, "the page's bar is not covered ($pageBar, $coverUnderPageBar)")
            // Its bar takes the App's place: the splash does not move.
            assertEquals(cover.top, coverUnderPageBar.top, 0.5f)
            assertEquals(cover.bottom, coverUnderPageBar.bottom, 0.5f)
        }
}
