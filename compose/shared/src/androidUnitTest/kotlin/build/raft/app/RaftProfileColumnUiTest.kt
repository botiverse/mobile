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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
 * The profile column (#189 step 3; web channelSideLayout's profile / details column) on a real composition, in the
 * workspace's shape: the split's grid and, one level in, the shell's own [RaftWorkspaceDetailGrid] as
 * SettingsHomePage composes it (the thread declared only while the plan doesn't fold it, the profile column, the
 * row's Expand; the pages are fixtures), the conversation in its main pane with the header's Settings
 * toggle (as ThreadPage's, through [LocalRaftProfileColumn]) and a member avatar, both opening pages through the
 * host navigator as the pages do ([RaftPaneHost] routes them by role):
 * - the channel details and a member's profile open into the column, the profile over the details; the Settings
 *   toggle opens and closes the details; a page's Close closes its cell only, and a page pushed in a cell has Back
 *   too (web layout-memory.md "Close and Back");
 * - Back closes the column's last page first, then the other cell's, before the page's own Back;
 * - the conversation and the thread give way to it by the row plan (in proportion, then the thread folds) instead
 *   of it covering them;
 * - Expand (#189 rule 5, which replaced the thread's Focus mode): the expanded column fills the row, the others at
 *   160 and still in sight; a page opened in the column restores the row; Back restores it before it closes anything;
 * - a column that is a row tab not shown is not counted as shown, so the shell's history Back skips it as its own
 *   Back does (PR #4666 review).
 * Every scenario checks the row settles (no recomposition loop: the lesson of #4661).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class RaftProfileColumnUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val data = JsonObject(mapOf("serverId" to JsonPrimitive("s1"), "channelId" to JsonPrimitive("c1")))

    private var panes by mutableStateOf(RaftProfileColumnPanes())
    private var threadOpen by mutableStateOf(false)
    private var rowWidth by mutableStateOf(1280f)
    private var folds: Boolean? = null
    /** The row's Expand (the shell's [RaftWideSideColumns.expand]) and what the column last reported as shown. */
    private val expand = RaftPaneGridExpand()
    private var columnShown = false
    private var pageBacks = 0
    private val platformOpened = mutableListOf<String>()

    /** How often the workspace's pages composed; past [COMPOSITION_CAP] it is a loop, failed at once instead of hanging. */
    private var detailCompositions = 0
    private var conversationCompositions = 0

    private fun counted(count: Int, page: String): Int {
        check(count < COMPOSITION_CAP) { "the $page recomposes without end: a recomposition loop in the pane grid's row" }
        return count + 1
    }

    private class Back(val press: () -> Unit)

    private fun ComposeUiTest.showWorkspace(): Back {
        panes = RaftProfileColumnPanes()
        expand.restore()
        columnShown = false
        folds = null
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
        setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            // The shell's own Back (SettingsHomePage `popMainRoute`), composed before the column: it only gets what the
            // column and the row's Expand leave.
            RaftBackHandler(enabled = true, label = "test_page") { pageBacks++ }
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            val grid = remember { RaftPaneGridStore() }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftHost provides host,
                LocalRaftPaneGridStore provides grid,
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

    /**
     * SettingsHomePage's `adaptiveDetailPane`: the split's grid, the thread declared only while the plan doesn't fold
     * it, and the production [RaftWorkspaceDetailGrid] (the conversation's host, the thread's and the profile column,
     * the row's Expand) with fixture pages.
     */
    @Composable
    private fun Workspace() {
        RaftPaneGrid(columns = emptyList()) {
            detailCompositions = counted(detailCompositions, "detail")
            val threadFolds = raftPaneGridFolds(RaftPaneGridColumns.Thread)
            folds = threadFolds
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
                reportHost = true,
                onShown = { columnShown = it },
                page = { entry, _ -> FixturePage(entry) },
                expand = expand,
            ) {
                conversationCompositions = counted(conversationCompositions, "conversation")
                FixtureConversation()
            }
        }
    }

    /** The side thread: a message author, opening their profile through the host as the thread page does. */
    @Composable
    private fun FixtureThread() {
        val navigator = LocalRaftHost.current.navigator
        Column(Modifier.fillMaxSize().testTag("thread")) {
            Button("thread-author") { navigator.openPage(MEMBER_DETAIL_PAGE, data) }
        }
    }

    /** The conversation: its header's Settings toggle (ThreadPage `showSettings`) and a member's avatar. */
    @Composable
    private fun FixtureConversation() {
        val profileColumn = LocalRaftProfileColumn.current
        val navigator = LocalRaftHost.current.navigator
        val detailsOpen = profileColumn.showsChannelDetails("c1")
        Column(Modifier.fillMaxSize().testTag("conversation")) {
            Button(if (detailsOpen) "gear-pressed" else "gear") {
                if (detailsOpen) profileColumn.closeDetails() else navigator.openPage(CHANNEL_SETTINGS_PAGE, data)
            }
            Button("avatar") { navigator.openPage(MEMBER_DETAIL_PAGE, data) }
        }
    }

    /**
     * A page in the column, opening and closing through its host as the real pages do; its head's buttons as a side
     * pane's ([raftSidePaneHeadButtons]): Back (its own `closePage`) over a page of the cell's stack, Close (the cell's).
     */
    @Composable
    private fun FixturePage(entry: RaftPaneEntry) {
        val navigator = LocalRaftHost.current.navigator
        val pane = LocalRaftSidePanePage.current
        Column(Modifier.fillMaxSize().testTag("page:${entry.pageName}")) {
            if (raftSidePaneShowsBack()) Button("back:${entry.pageName}") { navigator.closePage() }
            if (pane != null) Button("close:${entry.pageName}") { pane.close() }
            when (entry.pageName) {
                CHANNEL_SETTINGS_PAGE -> Button("members") { navigator.openPage(THREAD_MEMBERS_PAGE, data) }
                THREAD_MEMBERS_PAGE -> Button("pick") { navigator.openPage(MEMBER_DETAIL_PAGE, data) }
                MEMBER_DETAIL_PAGE -> Button("computers") { navigator.openPage(SETTINGS_COMPUTERS_PAGE, data) }
            }
        }
    }

    /**
     * The row's width, so a click lands in its middle. Robolectric's legacy text is about a pixel per character: a
     * short label at a column's start sat under the column's seam handle (12dp into the column, drawn over it), which
     * took the click (CI run 43323216019: "members" never opened).
     */
    @Composable
    private fun Button(tag: String, onClick: () -> Unit) {
        BasicText(tag, Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick))
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag, useUnmergedTree = true).performClick()
        waitForIdle()
    }

    /** The outermost node with [tag]: the nested grids' own main panes carry the same tag inside it. */
    private fun ComposeUiTest.widthDp(tag: String): Float =
        with(density) { onAllNodesWithTag(tag, useUnmergedTree = true)[0].fetchSemanticsNode().size.width.toDp().value }

    private fun ComposeUiTest.leftDp(tag: String): Float =
        with(density) { onAllNodesWithTag(tag, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.left.toDp().value }

    private fun near(expected: Float, actual: Float, what: String) = assertTrue(abs(expected - actual) <= 1f, "$what: expected $expected, was $actual")

    /** The row settled: a few more frames compose none of the workspace's pages again. */
    private fun ComposeUiTest.assertSettled() {
        val detail = detailCompositions
        val conversation = conversationCompositions
        repeat(5) { mainClock.advanceTimeByFrame() }
        waitForIdle()
        assertEquals(detail, detailCompositions, "the detail recomposed with nothing changing")
        assertEquals(conversation, conversationCompositions, "the conversation recomposed with nothing changing")
    }

    private val column = RAFT_PANE_GRID_COLUMN_TEST_TAG + RaftPaneGridColumns.Profile.id

    @Test
    fun theSettingsToggleOpensTheDetailsIntoTheColumnAndClosesThem() =
        runRobolectricComposeUiTest {
            rowWidth = 1280f
            showWorkspace()
            onNodeWithTag(column, useUnmergedTree = true).assertDoesNotExist()
            click("gear")
            assertEquals(listOf(RaftProfileColumnCell.Details), panes.cells)
            onNodeWithTag("page:$CHANNEL_SETTINGS_PAGE", useUnmergedTree = true).assertExists()
            // A column of the row at its 400, the conversation beside it with the rest: nothing over it.
            near(400f, widthDp(column), "the column")
            near(880f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "the conversation")
            near(880f, leftDp(column), "the column right of the conversation")
            // The toggle is pressed while the details are open; pressed again, it closes them.
            onNodeWithTag("gear-pressed", useUnmergedTree = true).assertExists()
            assertSettled()
            click("gear-pressed")
            assertFalse(panes.isOpen)
            onNodeWithTag(column, useUnmergedTree = true).assertDoesNotExist()
            near(1280f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "the conversation takes the row back")
            assertSettled()
            assertTrue(platformOpened.isEmpty(), "nothing opened as a window: $platformOpened")
        }

    /**
     * web layout-memory.md "Close and Back": each cell is a side pane. Its first page has Close only; a page pushed in
     * it (the details' member list) has Back too, which goes back one page in the cell. Close closes that cell only:
     * the other cell and the conversation stay, nothing opens elsewhere.
     */
    @Test
    fun aCellsCloseClosesOnlyThatCellAndBackShowsOnlyOverAPagePushedInIt() =
        runRobolectricComposeUiTest {
            rowWidth = 1280f
            showWorkspace()
            click("gear")
            click("avatar")
            assertEquals(listOf(RaftProfileColumnCell.Profile, RaftProfileColumnCell.Details), panes.cells)
            // Each cell's only page: Close, no Back.
            for (page in listOf(CHANNEL_SETTINGS_PAGE, MEMBER_DETAIL_PAGE)) {
                onNodeWithTag("close:$page", useUnmergedTree = true).assertExists()
                onNodeWithTag("back:$page", useUnmergedTree = true).assertDoesNotExist()
            }
            // The member list pushed over the details (two pages in the cell): Back and Close.
            click("members")
            assertEquals(listOf(CHANNEL_SETTINGS_PAGE, THREAD_MEMBERS_PAGE), panes.details.entries.map { it.pageName })
            onNodeWithTag("back:$THREAD_MEMBERS_PAGE", useUnmergedTree = true).assertExists()
            onNodeWithTag("close:$THREAD_MEMBERS_PAGE", useUnmergedTree = true).assertExists()
            // Back goes back one page in the cell.
            click("back:$THREAD_MEMBERS_PAGE")
            assertEquals(listOf(CHANNEL_SETTINGS_PAGE), panes.details.entries.map { it.pageName })
            // Close on the pushed page closes the whole cell, and only it.
            click("members")
            click("close:$THREAD_MEMBERS_PAGE")
            assertFalse(panes.details.isOpen, "the details cell closed")
            assertEquals(listOf(MEMBER_DETAIL_PAGE), panes.profile.entries.map { it.pageName }, "the profile cell stays")
            onNodeWithTag("page:$MEMBER_DETAIL_PAGE", useUnmergedTree = true).assertExists()
            onNodeWithTag("conversation", useUnmergedTree = true).assertExists()
            assertTrue(platformOpened.isEmpty(), "nothing opened as a window: $platformOpened")
            assertSettled()
        }

    private companion object {
        /** Far more compositions than a settled row ever needs, far fewer than a loop makes before the watchdog. */
        const val COMPOSITION_CAP = 200
    }
}
