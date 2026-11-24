package build.raft.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
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
import build.raft.app.data.AppLocale
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactsHomeActor
import build.raft.app.data.ArtifactsHomeCounts
import build.raft.app.data.ArtifactsHomeNews
import build.raft.app.data.ArtifactsHomeRow
import build.raft.app.data.ArtifactsHomeState
import build.raft.app.data.ArtifactsHomeStatus
import build.raft.app.data.ArtifactsHomeView
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import build.raft.designsystem.components.RAFT_PANE_GRID_COLUMN_TEST_TAG
import build.raft.designsystem.components.RAFT_PANE_GRID_MAIN_TEST_TAG
import build.raft.designsystem.components.RaftPaneGridExpand
import build.raft.designsystem.components.raftClickable
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Wide split-column Artifact viewer on the Artifacts home page (web `ArtifactsRoute.tsx` +
 * `ArtifactsDashboard.tsx`, botiverse/slock #16418):
 * - On wide windows, clicking a tile or list row opens the viewer in a 480dp split column beside
 *   the list using the shared `RaftPaneGridColumns.Artifact` column width, marks the open item
 *   selected, and lets the user switch Artifacts in place while keeping the list interactive.
 * - Focus Mode Expand fills the row leaving the list at 160dp; first Back restores side-by-side,
 *   second Back closes the viewer column; opening another Artifact while expanded restores.
 * - On narrow viewports, no split column mounts and clicking delegates to full-screen navigation.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class ArtifactsHomeSplitViewerUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    private val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 18),
            nowEpochMillis = 1_794_996_000_000L,
        )

    private var artifactPane by mutableStateOf(RaftArtifactColumnPane())
    private var view by mutableStateOf(ArtifactsHomeView.Grid)
    private var wide by mutableStateOf(true)
    private val expand = RaftPaneGridExpand()
    private val platformOpened = mutableListOf<String>()
    private val narrowOpened = mutableListOf<String>()
    private var pageBacks = 0
    private var observedOpenInColumn: Boolean? = null

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        artifactPane = RaftArtifactColumnPane()
        view = ArtifactsHomeView.Grid
        wide = true
        expand.restore()
        platformOpened.clear()
        narrowOpened.clear()
        pageBacks = 0
        observedOpenInColumn = null
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
    }

    private fun ComposeUiTest.mount(widthDp: Float = 1280f): () -> Unit {
        var dispatcher: OnBackPressedDispatcher? = null
        val transport = HostBridgeTransport { _, _, _ -> }
        val platform =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) {
                    platformOpened += pageName
                }

                override fun closePage() = Unit
            }
        val host = RaftCmpHost(BridgeWireHostCapabilities(transport), platform, bridgeTransport = transport)
        val homeState =
            ArtifactsHomeState(
                serverId = "s-1",
                status = ArtifactsHomeStatus.Ok,
                rows = listOf(homeRow("pt_a", "Launch plan"), homeRow("pt_b", "Metrics")),
                counts = ArtifactsHomeCounts(total = 2, filtered = 2, relevant = 0),
            )
        setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            RaftBackHandler(enabled = true, label = "test_artifacts_home_page") { pageBacks++ }
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            val grid = remember { RaftPaneGridStore() }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftHost provides host,
                LocalRaftPaneGridStore provides grid,
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
            ) {
                RaftDesignSystemTheme {
                    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(widthDp.dp, 720.dp)) {
                        ArtifactsHomeSplitLayout(
                            wide = wide,
                            artifact = { artifactPane },
                            onArtifactChange = { artifactPane = it },
                            active = true,
                            expand = expand,
                            modifier = Modifier.fillMaxSize(),
                            page = { entry, _ -> FixtureSplitViewer(entry) },
                        ) {
                            val inColumn = LocalRaftArtifactOpenInColumn.current
                            SideEffect { observedOpenInColumn = inColumn }
                            ArtifactsHomeScreen(
                                state = homeState,
                                view = view,
                                readerId = "me",
                                serverName = "Northwind Labs",
                                wide = wide,
                                onIntent = {},
                                onView = { view = it },
                                onOpen = { row ->
                                    if (wide) {
                                        val data = raftArtifactViewerPageData(ArtifactLinkTarget(row.summary.id)).toJsonObject()
                                        expand.restore()
                                        artifactPane = artifactPane.open(RAFT_ARTIFACT_VIEWER_PAGE, data)
                                    } else {
                                        narrowOpened += row.summary.id
                                    }
                                },
                                onBack = {},
                                currentArtifactId = artifactPane.currentArtifactIdOrNull().takeIf { wide },
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        return {
            runOnIdle { assertNotNull(dispatcher, "back dispatcher").onBackPressed() }
            waitForIdle()
        }
    }

    @Composable
    private fun FixtureSplitViewer(entry: RaftPaneEntry) {
        val navigator = LocalRaftHost.current.navigator
        val id = entry.pageData.toKuiklyJson().raftArtifactViewerTargetOrNull()?.id.orEmpty()
        Column(Modifier.fillMaxSize().testTag("split-viewer:$id")) {
            BasicText(
                "close",
                Modifier.fillMaxWidth().testTag("split-viewer-close").raftClickable(onClick = navigator::closePage),
            )
        }
    }

    private fun ComposeUiTest.widthDp(tag: String): Float =
        with(density) { onAllNodesWithTag(tag, useUnmergedTree = true)[0].fetchSemanticsNode().size.width.toDp().value }

    private fun ComposeUiTest.leftDp(tag: String): Float =
        with(density) { onAllNodesWithTag(tag, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.left.toDp().value }

    private fun near(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= 1f, "$what: expected $expected, was $actual")

    private val artifactColumnTag = RAFT_PANE_GRID_COLUMN_TEST_TAG + RaftPaneGridColumns.Artifact.id

    @Test
    fun wideClickOpensArtifactInSplitColumnSelectsActiveItemAndSwitchesInPlace() = runRobolectricComposeUiTest {
        mount()
        assertEquals(true, observedOpenInColumn)
        onNodeWithTag(artifactColumnTag, useUnmergedTree = true).assertDoesNotExist()
        onAllNodesWithTag(ArtifactsHomeTags.Tile)[0].assertIsNotSelected()
        onAllNodesWithTag(ArtifactsHomeTags.Tile)[1].assertIsNotSelected()

        // Click first tile -> split column opens at 480dp beside 800dp list; first tile selected.
        onAllNodesWithTag(ArtifactsHomeTags.Tile)[0].performClick()
        waitForIdle()
        assertTrue(artifactPane.isOpen)
        onNodeWithTag(ArtifactsHomeSplitTags.Viewer, useUnmergedTree = true).assertExists()
        onNodeWithTag("split-viewer:pt_a", useUnmergedTree = true).assertExists()
        near(480f, widthDp(artifactColumnTag), "split Artifact column width")
        near(800f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "Artifacts home list beside split viewer")
        near(800f, leftDp(artifactColumnTag), "split viewer stands right of list")
        assertTrue(platformOpened.isEmpty(), "wide click opens split column, not full-screen window")
        onAllNodesWithTag(ArtifactsHomeTags.Tile)[0].assertIsSelected()
        onAllNodesWithTag(ArtifactsHomeTags.Tile)[1].assertIsNotSelected()

        // Click second tile -> switches viewer in place and updates selection.
        onAllNodesWithTag(ArtifactsHomeTags.Tile)[1].performClick()
        waitForIdle()
        onNodeWithTag("split-viewer:pt_a", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("split-viewer:pt_b", useUnmergedTree = true).assertExists()
        onAllNodesWithTag(ArtifactsHomeTags.Tile)[0].assertIsNotSelected()
        onAllNodesWithTag(ArtifactsHomeTags.Tile)[1].assertIsSelected()

        // Switch to List view -> active row stays selected.
        val notSelected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true).not()
        view = ArtifactsHomeView.List
        waitForIdle()
        onAllNodesWithTag(ArtifactsHomeTags.Row)[0].assert(notSelected)
        onAllNodesWithTag(ArtifactsHomeTags.Row)[1].assertIsSelected()

        // Close via viewer's closePage -> column closes and list regains full width.
        onNodeWithTag("split-viewer-close", useUnmergedTree = true).performClick()
        waitForIdle()
        assertFalse(artifactPane.isOpen)
        onNodeWithTag(artifactColumnTag, useUnmergedTree = true).assertDoesNotExist()
        near(1280f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "list regains full width")
        onAllNodesWithTag(ArtifactsHomeTags.Row)[1].assert(notSelected)
    }

    @Test
    fun expandFillsRowLeavingListAt160AndTwoStepBackRestoresThenCloses() = runRobolectricComposeUiTest {
        val back = mount()
        onAllNodesWithTag(ArtifactsHomeTags.Tile)[0].performClick()
        waitForIdle()
        assertTrue(artifactPane.isOpen)

        // Focus Mode Expand leaves list at 160dp and fills the remaining width with the viewer column.
        runOnIdle { expand.expand(RaftPaneGridColumns.Artifact.id) }
        waitForIdle()
        assertEquals(RaftPaneGridColumns.Artifact.id, expand.region)
        near(160f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "list shrunk to 160dp while expanded")
        near(1120f, widthDp(artifactColumnTag), "viewer fills remaining row")

        // First Back restores side-by-side without closing the viewer.
        back()
        assertEquals(null, expand.region)
        assertTrue(artifactPane.isOpen)
        assertEquals(0, pageBacks)
        near(480f, widthDp(artifactColumnTag), "viewer restored to 480dp")
        near(800f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "list restored to 800dp")

        // Re-expanding with pt_b open and clicking the visible left-column tile (pt_a, inside the 160dp strip) restores side-by-side.
        onAllNodesWithTag(ArtifactsHomeTags.Tile)[1].performClick()
        waitForIdle()
        onNodeWithTag("split-viewer:pt_b", useUnmergedTree = true).assertExists()
        runOnIdle { expand.expand(RaftPaneGridColumns.Artifact.id) }
        waitForIdle()
        assertEquals(RaftPaneGridColumns.Artifact.id, expand.region)
        onAllNodesWithTag(ArtifactsHomeTags.Tile)[0].performClick()
        waitForIdle()
        assertEquals(null, expand.region)
        onNodeWithTag("split-viewer:pt_a", useUnmergedTree = true).assertExists()

        // Back now closes the viewer column; the next Back falls through to the page.
        back()
        assertFalse(artifactPane.isOpen)
        assertEquals(0, pageBacks)
        back()
        assertEquals(1, pageBacks)
    }

    @Test
    fun narrowViewportMountsNoSplitColumnAndDispatchesFullScreenOpen() = runRobolectricComposeUiTest {
        wide = false
        mount(widthDp = 390f)
        assertEquals(false, observedOpenInColumn)
        onAllNodesWithTag(artifactColumnTag, useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithTag(ArtifactsHomeSplitTags.Viewer, useUnmergedTree = true).assertCountEquals(0)

        onAllNodesWithTag(ArtifactsHomeTags.Tile)[0].performClick()
        waitForIdle()
        assertEquals(listOf("pt_a"), narrowOpened)
        assertFalse(artifactPane.isOpen)
    }

    private fun homeRow(id: String, title: String) =
        ArtifactsHomeRow(
            summary =
                ArtifactSummary(
                    id = id,
                    title = title,
                    description = null,
                    kind = ArtifactKind.Html,
                    version = 2,
                    pinned = false,
                    latestVersion = 2,
                    home = ArtifactHome.Conversation("proj-workspace", dm = false),
                    updatedAt = "2026-11-18T08:00:00.000Z",
                    commentCount = 2,
                    following = true,
                ),
            author = ArtifactsHomeActor("user", "u-1", "kirby", "Kirby"),
            openedAt = null,
            news = ArtifactsHomeNews(),
        )
}
