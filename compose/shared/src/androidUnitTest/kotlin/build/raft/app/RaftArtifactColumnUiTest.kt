package build.raft.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocusable
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
import build.raft.designsystem.components.RAFT_PANE_GRID_COLUMN_TEST_TAG
import build.raft.designsystem.components.RAFT_PANE_GRID_MAIN_TEST_TAG
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Artifact column (#189 step 4; web F41, channelSideLayout's `artifact` section) on a real composition, in the
 * workspace's shape as [RaftProfileColumnUiTest] has it: the split's grid and, one level in, the shell's own
 * [RaftWorkspaceDetailGrid] (the thread, the Artifact and profile columns, Focus), the conversation in its main pane
 * with a grid of its own for Tasks / Files (as ThreadPage's), the pages fixtures opening through the host navigator as
 * the real pages do ([RaftPaneHost] routes the viewer by its Artifact role):
 * - an Artifact opened from the conversation opens in a 480 column beside it, its head with Close, not as a window;
 * - the columns stand in the side layout's order, thread | Tasks / Files | Artifact | profile, and a layout that
 *   stacks the Artifact under the profile draws them in one column;
 * - Back closes the column opened last first;
 * - opening it while the thread fills the row leaves Focus; Focus entered with it open puts it out of sight (inert,
 *   checked on the merged tree) and Back leaves Focus first.
 * Every scenario checks the row settles (no recomposition loop: the lesson of #4661).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class RaftArtifactColumnUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val data = JsonObject(mapOf("serverId" to JsonPrimitive("s1"), "channelId" to JsonPrimitive("c1")))
    private val artifactData = JsonObject(mapOf("artifactId" to JsonPrimitive("pt_a1")))

    private var panes by mutableStateOf(RaftProfileColumnPanes())
    private var artifact by mutableStateOf(RaftArtifactColumnPane())
    private var threadOpen by mutableStateOf(false)
    private var cardsOpen by mutableStateOf(false)
    private var rowWidth by mutableStateOf(1280f)
    private var focused by mutableStateOf(false)
    private var artifactShown = false
    private var pageBacks = 0
    private val platformOpened = mutableListOf<String>()
    private var layout = RaftChannelSideLayoutStore()

    private var detailCompositions = 0
    private var conversationCompositions = 0

    private fun counted(count: Int, page: String): Int {
        check(count < COMPOSITION_CAP) { "the $page recomposes without end: a recomposition loop in the pane grid's row" }
        return count + 1
    }

    private class Back(val press: () -> Unit)

    private fun ComposeUiTest.showWorkspace(): Back {
        panes = RaftProfileColumnPanes()
        artifact = RaftArtifactColumnPane()
        focused = false
        artifactShown = false
        pageBacks = 0
        platformOpened.clear()
        detailCompositions = 0
        conversationCompositions = 0
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
        val sideLayout = layout
        setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            // The shell's own Back, composed before the columns: it only gets what they leave; Focus is a step of its own.
            RaftBackHandler(enabled = true, label = "test_page") { if (focused) focused = false else pageBacks++ }
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            val grid = remember { RaftPaneGridStore() }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftHost provides host,
                LocalRaftPaneGridStore provides grid,
                LocalRaftChannelSideLayoutStore provides sideLayout,
            ) {
                RaftDesignSystemTheme {
                    // Wider than the test window: laid out from its start, unclipped by the window.
                    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(rowWidth.dp, 700.dp)) {
                        Workspace()
                    }
                }
            }
        }
        waitForIdle()
        return Back {
            runOnIdle { assertNotNull(dispatcher, "the activity's Back dispatcher").onBackPressed() }
            waitForIdle()
        }
    }

    /** SettingsHomePage's `adaptiveDetailPane`, as in [RaftProfileColumnUiTest], with the Artifact column wired as the shell does. */
    @Composable
    private fun Workspace() {
        RaftPaneGrid(columns = emptyList()) {
            detailCompositions = counted(detailCompositions, "detail")
            val threadFolds = raftPaneGridFolds(RaftPaneGridColumns.Thread)
            val threadColumn =
                if (threadOpen && threadFolds == false) {
                    RaftPaneGridColumn(
                        bounds = RaftPaneGridColumns.Thread,
                        resizeLabel = "Resize thread",
                        cells = listOf(RaftPaneGridCell(id = "thread") { FixtureThread() }),
                        tabLabel = "Thread",
                    )
                } else {
                    null
                }
            RaftWorkspaceDetailGrid(
                modifier = Modifier.fillMaxSize(),
                wide = true,
                panes = { panes },
                onPanesChange = { panes = it },
                columnOpen = panes.isOpen,
                tabSelected = true,
                active = true,
                sideThreadColumn = threadColumn,
                threadFocused = focused,
                onLeaveFocus = { focused = false },
                reportHost = true,
                onShown = {},
                artifact = { artifact },
                onArtifactChange = { artifact = it },
                artifactOpen = artifact.isOpen,
                onArtifactShown = { artifactShown = it },
                page = { entry, _ -> FixturePage(entry) },
            ) {
                conversationCompositions = counted(conversationCompositions, "conversation")
                FixtureConversation()
            }
        }
    }

    @Composable
    private fun FixtureThread() {
        val navigator = LocalRaftHost.current.navigator
        Column(Modifier.fillMaxSize().testTag("thread")) {
            Button("thread-artifact") { navigator.openPage(RAFT_ARTIFACT_VIEWER_PAGE, artifactData) }
        }
    }

    /** The conversation: an Artifact card's Open and a member's avatar; Tasks / Files in its own grid (ThreadPage's). */
    @Composable
    private fun FixtureConversation() {
        val navigator = LocalRaftHost.current.navigator
        val cards =
            remember {
                RaftPaneGridColumn(
                    bounds = RaftPaneGridColumns.SideCards,
                    resizeLabel = "Resize Tasks and Files",
                    cells =
                        listOf(
                            RaftPaneGridCell(id = "tasks") { BasicText("tasks", Modifier.fillMaxSize().testTag("tasks")) },
                            RaftPaneGridCell(id = "files") { BasicText("files", Modifier.fillMaxSize().testTag("files")) },
                        ),
                    tabLabel = "Tasks · Files",
                )
            }
        RaftPaneGrid(columns = if (cardsOpen) listOf(cards) else emptyList()) {
            Column(Modifier.fillMaxSize().testTag("conversation")) {
                Button("artifact-card") { navigator.openPage(RAFT_ARTIFACT_VIEWER_PAGE, artifactData) }
                Button("avatar") { navigator.openPage(MEMBER_DETAIL_PAGE, data) }
            }
        }
    }

    /** A page in a column, closing through its host as the real pages do; a column's first page shows Close. */
    @Composable
    private fun FixturePage(entry: RaftPaneEntry) {
        val navigator = LocalRaftHost.current.navigator
        val closes = LocalRaftPaneBackCloses.current
        Column(Modifier.fillMaxSize().testTag("page:${entry.pageName}")) {
            Button(if (closes) "close:${entry.pageName}" else "back:${entry.pageName}") { navigator.closePage() }
        }
    }

    /** The row's width, so a click lands in its middle, clear of the column's 12dp seam handle. */
    @Composable
    private fun Button(tag: String, onClick: () -> Unit) {
        BasicText(tag, Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick))
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag, useUnmergedTree = true).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.widthDp(tag: String): Float =
        with(density) { onAllNodesWithTag(tag, useUnmergedTree = true)[0].fetchSemanticsNode().size.width.toDp().value }

    private fun ComposeUiTest.topDp(tag: String): Float =
        with(density) { onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top.toDp().value }

    private fun ComposeUiTest.leftDp(tag: String): Float =
        with(density) { onAllNodesWithTag(tag, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.left.toDp().value }

    private fun near(expected: Float, actual: Float, what: String) = assertTrue(abs(expected - actual) <= 1f, "$what: expected $expected, was $actual")

    private fun ComposeUiTest.assertSettled() {
        val detail = detailCompositions
        val conversation = conversationCompositions
        repeat(5) { mainClock.advanceTimeByFrame() }
        waitForIdle()
        assertEquals(detail, detailCompositions, "the detail recomposed with nothing changing")
        assertEquals(conversation, conversationCompositions, "the conversation recomposed with nothing changing")
    }

    private val column = RAFT_PANE_GRID_COLUMN_TEST_TAG + RaftPaneGridColumns.Artifact.id
    private val threadTag = RAFT_PANE_GRID_COLUMN_TEST_TAG + RaftPaneGridColumns.Thread.id
    private val viewer = "page:$RAFT_ARTIFACT_VIEWER_PAGE"

    @Test
    fun anArtifactOpenedFromTheConversationOpensInItsColumnWithClose() =
        runRobolectricComposeUiTest {
            layout = RaftChannelSideLayoutStore()
            rowWidth = 1280f
            showWorkspace()
            onNodeWithTag(column, useUnmergedTree = true).assertDoesNotExist()
            click("artifact-card")
            assertTrue(artifact.isOpen)
            onNodeWithTag(viewer, useUnmergedTree = true).assertExists()
            // A column of the row at its 480, the conversation beside it with the rest: no page over the window.
            near(480f, widthDp(column), "the Artifact column")
            near(800f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "the conversation")
            near(800f, leftDp(column), "right of the conversation")
            assertTrue(platformOpened.isEmpty(), "nothing opened as a window: $platformOpened")
            assertTrue(artifactShown, "the shell counts it as shown")
            assertSettled()
            // Its head's Close closes it, and the conversation takes the row back.
            click("close:$RAFT_ARTIFACT_VIEWER_PAGE")
            assertFalse(artifact.isOpen)
            onNodeWithTag(column, useUnmergedTree = true).assertDoesNotExist()
            near(1280f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "the conversation takes the row back")
            assertFalse(artifactShown)
            // Another Artifact replaces the one open: one at a time.
            click("artifact-card")
            val first = artifact.entry?.id
            click("artifact-card")
            assertTrue(artifact.entry?.id != first, "replaced")
            onAllNodesWithTag(viewer, useUnmergedTree = true).assertCountEquals(1)
            assertSettled()
        }

    @Test
    fun theColumnsStandInTheSideLayoutsOrder() =
        runRobolectricComposeUiTest {
            layout = RaftChannelSideLayoutStore()
            threadOpen = true
            cardsOpen = true
            // The conversation (400) and every column at its default: thread 400, Tasks / Files 360, Artifact 480, profile 400.
            rowWidth = 2100f
            showWorkspace()
            click("artifact-card")
            click("avatar")
            val tags = listOf(threadTag, RaftPaneGridColumns.SideCards.id, RaftPaneGridColumns.Artifact.id, RaftPaneGridColumns.Profile.id)
                .map { if (it.startsWith(RAFT_PANE_GRID_COLUMN_TEST_TAG)) it else RAFT_PANE_GRID_COLUMN_TEST_TAG + it }
            val lefts = tags.map { leftDp(it) }
            assertEquals(lefts.sorted(), lefts, "thread | Tasks / Files | Artifact | profile: $lefts")
            near(480f, widthDp(column), "the Artifact at its 480")
            near(400f, widthDp(threadTag), "the thread")
            // One column per layout column with an open section: the thread's own and three of the four side columns.
            val columns = SemanticsMatcher("a pane grid column") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(RAFT_PANE_GRID_COLUMN_TEST_TAG) == true }
            assertEquals(4, onAllNodes(columns, useUnmergedTree = true).fetchSemanticsNodes().size)
            assertSettled()
            threadOpen = false
            cardsOpen = false
        }

    @Test
    fun aLayoutThatStacksTheArtifactUnderTheProfileDrawsThemInOneColumn() =
        runRobolectricComposeUiTest {
            // As step 5's Move will: the Artifact into the profile's column, at its bottom.
            layout = RaftChannelSideLayoutStore().apply { move(RaftChannelSideSection.Artifact, RaftChannelSideDrop.Into(3, null)) }
            rowWidth = 1280f
            val back = showWorkspace()
            click("avatar")
            click("artifact-card")
            val stacked = RAFT_PANE_GRID_COLUMN_TEST_TAG + "profile+context+details+artifact"
            onNodeWithTag(stacked, useUnmergedTree = true).assertExists()
            onNodeWithTag(column, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(RAFT_PANE_GRID_COLUMN_TEST_TAG + RaftPaneGridColumns.Profile.id, useUnmergedTree = true).assertDoesNotExist()
            assertTrue(topDp("page:$MEMBER_DETAIL_PAGE") < topDp(viewer), "the profile over the Artifact")
            // At least the Artifact's 480.
            near(480f, widthDp(stacked), "one column")
            assertSettled()
            // Back still closes the one opened last first: the Artifact, then the profile.
            back.press()
            assertFalse(artifact.isOpen)
            assertTrue(panes.isOpen)
            back.press()
            assertFalse(panes.isOpen)
            assertEquals(0, pageBacks)
            assertSettled()
        }

    @Test
    fun backClosesTheColumnOpenedLastFirst() =
        runRobolectricComposeUiTest {
            layout = RaftChannelSideLayoutStore()
            rowWidth = 1600f
            val back = showWorkspace()
            // The Artifact, then a profile: the profile closes first.
            click("artifact-card")
            click("avatar")
            back.press()
            assertFalse(panes.isOpen, "the profile, opened last")
            assertTrue(artifact.isOpen)
            back.press()
            assertFalse(artifact.isOpen)
            assertEquals(0, pageBacks)
            // A profile, then the Artifact: the Artifact closes first.
            click("avatar")
            click("artifact-card")
            back.press()
            assertFalse(artifact.isOpen, "the Artifact, opened last")
            assertTrue(panes.isOpen)
            back.press()
            assertFalse(panes.isOpen)
            assertEquals(0, pageBacks, "the columns' Back came before the page's")
            back.press()
            assertEquals(1, pageBacks)
            assertSettled()
        }

    // As the profile column (PR #4664 review, A): opening the Artifact while the thread fills the row leaves Focus.
    @Test
    fun anArtifactOpenedWhileTheThreadFillsTheRowLeavesFocus() =
        runRobolectricComposeUiTest {
            layout = RaftChannelSideLayoutStore()
            threadOpen = true
            rowWidth = 1280f
            val back = showWorkspace()
            runOnIdle { focused = true }
            waitForIdle()
            near(1280f, widthDp(threadTag), "the thread fills the row in Focus")
            assertSettled()
            click("thread-artifact")
            assertTrue(artifact.isOpen)
            assertFalse(focused, "opening the Artifact left Focus")
            near(400f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "the conversation")
            near(400f, widthDp(threadTag), "the thread")
            near(480f, widthDp(column), "the Artifact")
            assertTrue(artifactShown)
            assertSettled()
            back.press()
            assertFalse(artifact.isOpen)
            assertEquals(0, pageBacks)
            assertSettled()
            threadOpen = false
        }

    // As the profile column (PR #4664 review, B): Focus entered with the Artifact open puts it out of sight, inert, and
    // Back leaves Focus first.
    @Test
    fun focusEnteredWithTheArtifactOpenHidesItAndBackLeavesFocusFirst() =
        runRobolectricComposeUiTest {
            layout = RaftChannelSideLayoutStore()
            threadOpen = true
            rowWidth = 1280f
            val back = showWorkspace()
            click("artifact-card")
            assertTrue(artifactShown)
            runOnIdle { focused = true }
            waitForIdle()
            near(0f, widthDp(column), "the Artifact in Focus")
            near(1280f, widthDp(threadTag), "the thread fills the row")
            // The merged tree, the one screen readers get: the page is gone and nothing in the column is in reach.
            onNodeWithTag(viewer).assertDoesNotExist()
            onAllNodes(hasAnyAncestor(hasTestTag(column)) and (hasClickAction() or isFocusable())).assertCountEquals(0)
            assertFalse(artifactShown, "the shell's Back step must not count a column out of sight")
            assertSettled()
            back.press()
            assertFalse(focused, "Back left Focus")
            assertTrue(artifact.isOpen, "nothing closed unseen")
            near(480f, widthDp(column), "back in sight")
            onNodeWithTag(viewer).assertExists()
            assertTrue(artifactShown)
            back.press()
            assertFalse(artifact.isOpen)
            assertEquals(0, pageBacks)
            back.press()
            assertEquals(1, pageBacks)
            assertSettled()
            threadOpen = false
        }

    private companion object {
        const val COMPOSITION_CAP = 200
    }
}
