package build.raft.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PR #4666 review (blocker): the viewer in the Artifact column has ONE bar, as the window page (#4635, web #14672).
 * The production [RaftArtifactColumnCell] hosts a page drawn as the viewer draws itself ([RaftArtifactViewerFrame]
 * with its `inColumn`), closing through its host as the viewer's `closePage` does:
 * - the page draws the bar (`hostToolbar=1`): no App bar over it, and the page's own Back (`#host=close`, which the
 *   viewer turns into `closePage`, RaftArtifactViewerEmbedTest) closes the column;
 * - the page doesn't (an older web): the App's bar, its only button the column's Close.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class RaftArtifactColumnBarUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var pane by mutableStateOf(RaftArtifactColumnPane())

    private fun ComposeUiTest.showColumn(pageDrawsBar: Boolean) {
        pane = RaftArtifactColumnPane().open(RAFT_ARTIFACT_VIEWER_PAGE, JsonObject(mapOf("artifactId" to JsonPrimitive("pt_a1"))))
        val transport = HostBridgeTransport { _, _, _ -> }
        val platform =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) = Unit

                override fun closePage() = Unit
            }
        val host = RaftCmpHost(BridgeWireHostCapabilities(transport), platform, bridgeTransport = transport)
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftHost provides host) {
                RaftDesignSystemTheme {
                    Box(Modifier.requiredSize(480.dp, 700.dp).testTag("column")) {
                        RaftArtifactColumnCell(
                            pane = { pane },
                            active = true,
                            backOn = { true },
                            onChange = { pane = it },
                            openArtifact = { _, _ -> },
                            openSupporting = { _, _ -> },
                            page = { _, _ ->
                                // As RaftArtifactViewerScreen draws itself, its close being the page's `closePage`.
                                val navigator = LocalRaftHost.current.navigator
                                RaftArtifactViewerFrame(
                                    title = "Plan",
                                    onBack = { navigator.closePage() },
                                    pageDrawsBar = pageDrawsBar,
                                    inColumn = LocalRaftArtifactViewerInColumn.current,
                                ) {
                                    Box(Modifier.fillMaxSize().testTag("web")) {
                                        if (pageDrawsBar) {
                                            // The page's toolbar Back: `#host=close`, which the viewer turns into closePage.
                                            BasicText("page-back", Modifier.testTag("page-back").clickable { navigator.closePage() })
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.topDp(tag: String): Float =
        with(density) { onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top.toDp().value }

    private val appBarButtons get() = hasAnyAncestor(hasTestTag("column")) and hasClickAction() and !hasAnyAncestor(hasTestTag("web"))

    @Test
    fun wherePageDrawsTheBarTheColumnHasNoAppBarAndThePagesBackClosesIt() =
        runRobolectricComposeUiTest {
            showColumn(pageDrawsBar = true)
            // One bar: the page's. Nothing of the App's above it, not even the window's safe area.
            onAllNodes(appBarButtons).assertCountEquals(0)
            assertTrue(topDp("web") - topDp("column") < 1f, "the page starts at the column's top")
            onNodeWithTag("page-back", useUnmergedTree = true).performClick()
            waitForIdle()
            assertFalse(pane.isOpen, "the page's Back closed the column")
        }

    @Test
    fun whereThePageDoesntTheAppBarHasTheColumnsClose() =
        runRobolectricComposeUiTest {
            showColumn(pageDrawsBar = false)
            // The App's bar, with only the leading Close (no ⋮: this fixture offers no App items).
            onAllNodes(appBarButtons).assertCountEquals(1)
            assertTrue(topDp("web") - topDp("column") > 20f, "the page under the App's bar")
            onAllNodes(appBarButtons)[0].performClick()
            waitForIdle()
            assertFalse(pane.isOpen, "Close closed the column")
        }
}
