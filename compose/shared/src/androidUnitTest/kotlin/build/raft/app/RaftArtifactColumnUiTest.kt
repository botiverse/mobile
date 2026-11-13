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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
import build.raft.designsystem.components.RAFT_PANE_GRID_HEAD_CLOSE_SUFFIX
import build.raft.designsystem.components.RAFT_PANE_GRID_HEAD_EXPAND_SUFFIX
import build.raft.designsystem.components.RAFT_PANE_GRID_HEAD_MORE_SUFFIX
import build.raft.designsystem.components.RAFT_PANE_GRID_HEAD_TEST_TAG
import build.raft.designsystem.components.RAFT_PANE_GRID_MAIN_TEST_TAG
import build.raft.designsystem.components.RAFT_PANE_GRID_TABS_TEST_TAG
import build.raft.designsystem.components.RaftPaneGridExpand
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
 * [RaftWorkspaceDetailGrid] (the thread, the Artifact and profile columns, the row's Expand), the conversation in its
 * main pane with a grid of its own for Tasks / Files (as ThreadPage's, each a production [RaftPaneGridCard] with the
 * column head), the pages fixtures opening through the host navigator as the real pages do ([RaftPaneHost] routes the
 * viewer by its Artifact role):
 * - an Artifact opened from the conversation opens in a 480 column beside it, its head with Close, not as a window;
 * - the columns stand in the side layout's order, thread | Tasks / Files | Artifact | profile, and a layout that
 *   stacks the Artifact under the profile draws them in one column;
 * - Back closes the column opened last first;
 * - opening it while the thread is expanded restores the row; expanded with it open, it stays at 160 and Back restores
 *   the row first;
 * - #189 step 5, the column head: title, ⋯, Expand, Close in that order; ⋯ holds Move Left / Move Right, which move the
 *   section in the side layout, keep it and stand the row anew; Expand / Restore and Back; the row tabs in the head's
 *   title slot (and on top of a column whose page draws its own head).
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
    /** The row's Expand (the shell's [RaftWideSideColumns.expand]). */
    private val expand = RaftPaneGridExpand()
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
        expand.restore()
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
            // The shell's own Back, composed before the columns: it only gets what they and the row's Expand leave.
            RaftBackHandler(enabled = true, label = "test_page") { pageBacks++ }
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
                expand = expand,
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
                            RaftPaneGridCell(id = "tasks") {
                                RaftPaneGridCard(title = "Tasks", closeLabel = "Close Tasks", onClose = { cardsOpen = false }) {
                                    BasicText("tasks", Modifier.fillMaxSize().testTag("tasks"))
                                }
                            },
                            RaftPaneGridCell(id = "files") {
                                RaftPaneGridCard(title = "Files", closeLabel = "Close Files", onClose = { cardsOpen = false }) {
                                    BasicText("files", Modifier.fillMaxSize().testTag("files"))
                                }
                            },
                        ),
                    tabLabel = "Tasks · Files",
                    headCarriesTabs = true,
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

    // As the profile column (PR #4664 review, A, now for Expand): opening the Artifact while the thread is expanded
    // restores the row.
    @Test
    fun anArtifactOpenedWhileTheThreadIsExpandedRestoresTheRow() =
        runRobolectricComposeUiTest {
            layout = RaftChannelSideLayoutStore()
            threadOpen = true
            rowWidth = 1280f
            val back = showWorkspace()
            runOnIdle { expand.toggle("thread") }
            waitForIdle()
            near(1120f, widthDp(threadTag), "the expanded thread")
            assertSettled()
            click("thread-artifact")
            assertTrue(artifact.isOpen)
            assertEquals(null, expand.region, "opening the Artifact restored the row")
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

    // As the profile column (PR #4664 review, B, now for Expand): expanded with the Artifact open, it stays in sight at
    // 160, and Back restores the row before it closes anything.
    @Test
    fun expandedWithTheArtifactOpenItStaysAt160AndBackRestoresFirst() =
        runRobolectricComposeUiTest {
            layout = RaftChannelSideLayoutStore()
            threadOpen = true
            rowWidth = 1280f
            val back = showWorkspace()
            click("artifact-card")
            assertTrue(artifactShown)
            runOnIdle { expand.toggle("thread") }
            waitForIdle()
            near(160f, widthDp(column), "the Artifact at 160")
            near(960f, widthDp(threadTag), "the thread fills the row")
            onNodeWithTag(viewer).assertExists()
            assertTrue(artifactShown, "a column at 160 is on screen")
            assertSettled()
            back.press()
            assertEquals(null, expand.region, "Back restored the row")
            assertTrue(artifact.isOpen, "nothing closed")
            near(480f, widthDp(column), "as it was")
            back.press()
            assertFalse(artifact.isOpen)
            assertEquals(0, pageBacks)
            back.press()
            assertEquals(1, pageBacks)
            assertSettled()
            threadOpen = false
        }

    private val tasksHead = RAFT_PANE_GRID_HEAD_TEST_TAG + "tasks"

    /**
     * The node that takes the click for [tag]: a head's icon button carries its tag on its tooltip's box, the click on
     * the button inside it; a menu item carries both.
     */
    private fun ComposeUiTest.clickable(tag: String) =
        onNode(hasClickAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)

    // #189 step 5, rule 6 / 7: the head is the title, its own actions, ⋯, Expand, Close, left to right (web
    // ChannelSideCard); ⋯ holds Move Left / Move Right, which move the section in the side layout and keep it, and the
    // row stands anew by it.
    @Test
    fun theCardHeadIsTitleMoreExpandCloseAndMoveReordersTheRow() =
        runRobolectricComposeUiTest {
            val store = RaftChannelSideLayoutStore()
            layout = store
            threadOpen = true
            cardsOpen = true
            rowWidth = 1600f
            showWorkspace()
            val more = leftDp(tasksHead + RAFT_PANE_GRID_HEAD_MORE_SUFFIX)
            val expandAt = leftDp(tasksHead + RAFT_PANE_GRID_HEAD_EXPAND_SUFFIX)
            val close = leftDp(tasksHead + RAFT_PANE_GRID_HEAD_CLOSE_SUFFIX)
            assertTrue(more < expandAt && expandAt < close, "⋯, Expand, Close: $more, $expandAt, $close")
            assertTrue(leftDp(tasksHead) < more, "the title first")
            // ⋯: Move Left and Move Right (Tasks shares its column: either way it gets one of its own).
            click(tasksHead + RAFT_PANE_GRID_HEAD_MORE_SUFFIX)
            onNodeWithTag(RAFT_COLUMN_MOVE_RIGHT_TEST_TAG + "tasks", useUnmergedTree = true).assertExists()
            click(RAFT_COLUMN_MOVE_LEFT_TEST_TAG + "tasks")
            val moved = store.layout.columns.map { column -> column.sections.map { it.wire } }
            assertEquals(listOf(listOf("thread"), listOf("tasks"), listOf("files")), moved.take(3), "kept: $moved")
            assertEquals(store.layout, raftParseChannelSideLayoutText(store.layout.toJson().toString()), "in the web's shape")
            val tasksColumn = RAFT_PANE_GRID_COLUMN_TEST_TAG + "tasks"
            val filesColumn = RAFT_PANE_GRID_COLUMN_TEST_TAG + "files"
            assertTrue(leftDp(tasksColumn) < leftDp(filesColumn), "Tasks stands left of Files")
            assertSettled()
            // Alone, Move Right swaps it with Files.
            click(tasksHead + RAFT_PANE_GRID_HEAD_MORE_SUFFIX)
            click(RAFT_COLUMN_MOVE_RIGHT_TEST_TAG + "tasks")
            assertTrue(leftDp(filesColumn) < leftDp(tasksColumn), "Files now left of Tasks")
            assertEquals(listOf(listOf("thread"), listOf("files"), listOf("tasks")), store.layout.columns.map { c -> c.sections.map { it.wire } }.take(3))
            // At the row's end (nothing right of Tasks): Move Right is there but disabled.
            click(tasksHead + RAFT_PANE_GRID_HEAD_MORE_SUFFIX)
            onNodeWithTag(RAFT_COLUMN_MOVE_RIGHT_TEST_TAG + "tasks", useUnmergedTree = true).assertIsNotEnabled()
            onNodeWithTag(RAFT_COLUMN_MOVE_LEFT_TEST_TAG + "tasks", useUnmergedTree = true).assertIsEnabled()
            assertSettled()
            threadOpen = false
            cardsOpen = false
        }

    // Rule 5 from a card's head: Expand fills the row with its column, every other column at 160; pressed again
    // (Restore), or Back, the row is as it was.
    @Test
    fun aCardsExpandFillsTheRowAndRestoreOrBackPutsItBack() =
        runRobolectricComposeUiTest {
            layout = RaftChannelSideLayoutStore()
            threadOpen = true
            cardsOpen = true
            rowWidth = 1600f
            val back = showWorkspace()
            val cards = RAFT_PANE_GRID_COLUMN_TEST_TAG + RaftPaneGridColumns.SideCards.id
            val before = widthDp(cards)
            click(tasksHead + RAFT_PANE_GRID_HEAD_EXPAND_SUFFIX)
            assertEquals("tasks", expand.region)
            near(1280f, widthDp(cards), "Tasks / Files fills the row")
            near(160f, widthDp(threadTag), "the thread at 160")
            near(160f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "the conversation at 160")
            assertSettled()
            // The same button, now Restore.
            click(tasksHead + RAFT_PANE_GRID_HEAD_EXPAND_SUFFIX)
            assertEquals(null, expand.region)
            near(before, widthDp(cards), "as it was")
            // Expanded again, Back restores it before anything else.
            click(tasksHead + RAFT_PANE_GRID_HEAD_EXPAND_SUFFIX)
            back.press()
            assertEquals(null, expand.region)
            assertEquals(0, pageBacks)
            assertTrue(cardsOpen, "nothing closed")
            assertSettled()
            threadOpen = false
            cardsOpen = false
        }

    // Rule 4 with the head: where the row makes columns tabs, they stand in the shown column's head, in its title's
    // place; a column whose page draws its own head (the Artifact viewer) gets them on top of it instead, so every tab
    // stays in reach.
    @Test
    fun theRowTabsStandInTheHeadsTitleSlot() =
        runRobolectricComposeUiTest {
            layout = RaftChannelSideLayoutStore()
            cardsOpen = true
            rowWidth = 1280f
            showWorkspace()
            click("artifact-card")
            // 400: the conversation, Tasks / Files and the Artifact can't have 160 each; the Artifact is a tab in
            // Tasks / Files' column.
            rowWidth = 400f
            waitForIdle()
            val cardsTabs = RAFT_PANE_GRID_TABS_TEST_TAG + RaftPaneGridColumns.SideCards.id
            onAllNodesWithTag(cardsTabs, useUnmergedTree = true).assertCountEquals(1)
            onNode(hasTestTag(cardsTabs) and hasAnyAncestor(hasTestTag(tasksHead)), useUnmergedTree = true).assertExists()
            // The head with tabs offers no moves (they keep that room). About 190 wide by touch with its fold chevron,
            // Expand and Close stand in the head where they fit beside the title slot's floor, else in its ⋯
            // (raftPaneGridHeadFit: Expand first, then Close); either way Expand works as the head's.
            val inHead = { suffix: String -> onAllNodesWithTag(tasksHead + suffix, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val more = inHead(RAFT_PANE_GRID_HEAD_MORE_SUFFIX)
            if (!more) {
                assertTrue(inHead(RAFT_PANE_GRID_HEAD_EXPAND_SUFFIX) && inHead(RAFT_PANE_GRID_HEAD_CLOSE_SUFFIX), "no ⋯: Expand and Close both in the head")
            } else {
                clickable(tasksHead + RAFT_PANE_GRID_HEAD_MORE_SUFFIX).performSemanticsAction(SemanticsActions.OnClick)
                waitForIdle()
                assertTrue(inHead(RAFT_PANE_GRID_HEAD_EXPAND_SUFFIX), "Expand in the head or in its open ⋯")
                assertTrue(inHead(RAFT_PANE_GRID_HEAD_CLOSE_SUFFIX), "Close in the head or in its open ⋯")
                assertTrue(
                    onAllNodesWithTag(RAFT_COLUMN_MOVE_LEFT_TEST_TAG + "tasks", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() &&
                        onAllNodesWithTag(RAFT_COLUMN_MOVE_RIGHT_TEST_TAG + "tasks", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
                    "no Move Left / Move Right in the ⋯ of a head showing the row tabs",
                )
            }
            clickable(tasksHead + RAFT_PANE_GRID_HEAD_EXPAND_SUFFIX).performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertEquals("tasks", expand.region, "Expand expanded Tasks / Files")
            runOnIdle { expand.restore() }
            waitForIdle()
            assertSettled()
            // The Artifact's tab picked (by its action: the narrow head scrolls it out of view): its column shows, the
            // tabs on top of it (the viewer draws its own head).
            onAllNodes(hasAnyAncestor(hasTestTag(cardsTabs)) and isSelectable(), useUnmergedTree = true)[1]
                .performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertTrue(widthDp(column) >= 160f, "the Artifact shown in the group's column")
            near(0f, widthDp(RAFT_PANE_GRID_COLUMN_TEST_TAG + RaftPaneGridColumns.SideCards.id), "Tasks / Files a tab not shown")
            onNodeWithTag(RAFT_PANE_GRID_TABS_TEST_TAG + RaftPaneGridColumns.Artifact.id, useUnmergedTree = true).assertExists()
            onNodeWithTag(viewer).assertExists()
            assertSettled()
            cardsOpen = false
        }

    private companion object {
        const val COMPOSITION_CAP = 200
    }
}
