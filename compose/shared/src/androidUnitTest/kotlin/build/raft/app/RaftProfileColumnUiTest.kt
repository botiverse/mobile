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
 * SettingsHomePage composes it (the thread declared only while the plan doesn't fold it, the profile column, Focus
 * mode; the pages are fixtures), the conversation in its main pane with the header's Settings
 * toggle (as ThreadPage's, through [LocalRaftProfileColumn]) and a member avatar, both opening pages through the
 * host navigator as the pages do ([RaftPaneHost] routes them by role):
 * - the channel details and a member's profile open into the column, the profile over the details; the Settings
 *   toggle opens and closes the details; a page's Close closes its cell;
 * - Back closes the column's last page first, then the other cell's, before the page's own Back;
 * - the conversation and the thread give way to it by the row plan (in proportion, then the thread folds) instead
 *   of it covering them;
 * - Focus mode (PR #4664 review): a page opened in the column leaves it; entered with the column open, the column is
 *   out of sight and inert, and Back leaves Focus before it closes anything.
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
    /** The side thread's Focus mode (the shell's `sideThreadFocused`) and what the column last reported as shown. */
    private var focused by mutableStateOf(false)
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
        focused = false
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
            // column leaves. Focus is a step of its own there: Back leaves it first.
            RaftBackHandler(enabled = true, label = "test_page") { if (focused) focused = false else pageBacks++ }
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
     * Focus) with fixture pages.
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
                threadFocused = focused,
                onLeaveFocus = { focused = false },
                reportHost = true,
                onShown = { columnShown = it },
                page = { entry, _ -> FixturePage(entry) },
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

    /** A page in the column, opening and closing through its host as the real pages do; its first page shows Close. */
    @Composable
    private fun FixturePage(entry: RaftPaneEntry) {
        val navigator = LocalRaftHost.current.navigator
        val closes = LocalRaftPaneBackCloses.current
        Column(Modifier.fillMaxSize().testTag("page:${entry.pageName}")) {
            Button(if (closes) "close:${entry.pageName}" else "back:${entry.pageName}") { navigator.closePage() }
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

    private fun ComposeUiTest.topDp(tag: String): Float =
        with(density) { onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top.toDp().value }

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

    @Test
    fun aMemberPickedInTheDetailsShowsTheirProfileAboveThemAndBackClosesInOrder() =
        runRobolectricComposeUiTest {
            rowWidth = 1280f
            val back = showWorkspace()
            click("gear")
            // The details' first page has Close; their member list, pushed over them, has Back.
            onNodeWithTag("close:$CHANNEL_SETTINGS_PAGE", useUnmergedTree = true).assertExists()
            click("members")
            assertEquals(listOf(CHANNEL_SETTINGS_PAGE, THREAD_MEMBERS_PAGE), panes.details.entries.map { it.pageName }, "pushed in the details")
            onNodeWithTag("back:$THREAD_MEMBERS_PAGE", useUnmergedTree = true).assertExists()
            click("pick")
            assertEquals(listOf(RaftProfileColumnCell.Profile, RaftProfileColumnCell.Details), panes.cells)
            // One column, the profile on top of the member list, its first page with Close.
            onNodeWithTag("close:$MEMBER_DETAIL_PAGE", useUnmergedTree = true).assertExists()
            assertTrue(topDp("page:$MEMBER_DETAIL_PAGE") < topDp("page:$THREAD_MEMBERS_PAGE"), "the profile over the details")
            near(400f, widthDp(column), "still one column")
            // The details' toggle stays pressed with the profile over them.
            onNodeWithTag("gear-pressed", useUnmergedTree = true).assertExists()
            assertSettled()
            // A page without a role opened from the column opens as a window.
            click("computers")
            assertEquals(listOf(SETTINGS_COMPUTERS_PAGE), platformOpened)
            // Back: the profile, then the member list, then the details; then the page's own.
            back.press()
            assertEquals(listOf(RaftProfileColumnCell.Details), panes.cells)
            onNodeWithTag("page:$MEMBER_DETAIL_PAGE", useUnmergedTree = true).assertDoesNotExist()
            back.press()
            assertEquals(listOf(CHANNEL_SETTINGS_PAGE), panes.details.entries.map { it.pageName })
            back.press()
            assertFalse(panes.isOpen)
            onNodeWithTag(column, useUnmergedTree = true).assertDoesNotExist()
            assertEquals(0, pageBacks, "the column's Back came before the page's")
            back.press()
            assertEquals(1, pageBacks, "with the column closed Back is the page's")
            assertSettled()
        }

    @Test
    fun aCellsCloseClosesItAndLeavesTheOther() =
        runRobolectricComposeUiTest {
            rowWidth = 1280f
            showWorkspace()
            click("avatar")
            assertEquals(listOf(RaftProfileColumnCell.Profile), panes.cells)
            click("gear")
            assertEquals(listOf(RaftProfileColumnCell.Profile, RaftProfileColumnCell.Details), panes.cells)
            assertSettled()
            // The toggle closes only the details.
            click("gear-pressed")
            assertEquals(listOf(RaftProfileColumnCell.Profile), panes.cells)
            // The profile's Close closes it, and the column with it.
            click("close:$MEMBER_DETAIL_PAGE")
            assertFalse(panes.isOpen)
            onNodeWithTag(column, useUnmergedTree = true).assertDoesNotExist()
            assertSettled()
        }

    @Test
    fun theConversationAndTheThreadGiveWayToTheColumnInsteadOfBeingCovered() =
        runRobolectricComposeUiTest {
            threadOpen = true
            rowWidth = 1280f
            showWorkspace()
            assertEquals(false, folds)
            near(400f, widthDp(RAFT_PANE_GRID_COLUMN_TEST_TAG + RaftPaneGridColumns.Thread.id), "the thread alone")
            click("gear")
            // 1280 holds the conversation (480, what the columns leave), the thread (400) and the column (400): the
            // thread stays beside (it used to step aside), the column last.
            assertEquals(false, folds, "room for all three")
            onNodeWithTag("thread", useUnmergedTree = true).assertExists()
            near(480f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "the conversation")
            near(400f, widthDp(RAFT_PANE_GRID_COLUMN_TEST_TAG + RaftPaneGridColumns.Thread.id), "the thread")
            near(400f, widthDp(column), "the column")
            assertTrue(leftDp(RAFT_PANE_GRID_COLUMN_TEST_TAG + RaftPaneGridColumns.Thread.id) < leftDp(column), "the column last")
            assertSettled()
            // 1150: 50 short, each gives way in proportion above its minimum (360, 360, 320): nothing clips or covers.
            rowWidth = 1150f
            waitForIdle()
            assertEquals(false, folds)
            near(387.5f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "the conversation at 1150")
            near(387.5f, widthDp(RAFT_PANE_GRID_COLUMN_TEST_TAG + RaftPaneGridColumns.Thread.id), "the thread at 1150")
            near(375f, widthDp(column), "the column at 1150")
            assertSettled()
            // 1000: the three would clip, so the thread folds into the conversation (#189 rule 3) and the column
            // stands beside the conversation at its 400.
            rowWidth = 1000f
            waitForIdle()
            assertEquals(true, folds, "the thread folds before anything clips")
            onNodeWithTag("thread", useUnmergedTree = true).assertDoesNotExist()
            near(400f, widthDp(column), "the column beside the folded conversation")
            near(600f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "the conversation")
            assertSettled()
            // Closed, the thread comes back beside the conversation.
            rowWidth = 1280f
            click("gear-pressed")
            assertEquals(false, folds)
            onNodeWithTag("thread", useUnmergedTree = true).assertExists()
            assertSettled()
            threadOpen = false
        }

    private val threadTag = RAFT_PANE_GRID_COLUMN_TEST_TAG + RaftPaneGridColumns.Thread.id

    // PR #4664 review, A: in Focus a profile opened from the thread used to go to a column of no width, and the next
    // Back closed it unseen. Opening a page in the column leaves Focus (web restoreRowExpand), so it shows.
    @Test
    fun aPageOpenedInTheColumnWhileTheThreadFillsTheRowLeavesFocus() =
        runRobolectricComposeUiTest {
            threadOpen = true
            rowWidth = 1280f
            val back = showWorkspace()
            runOnIdle { focused = true }
            waitForIdle()
            near(1280f, widthDp(threadTag), "the thread fills the row in Focus")
            assertSettled()
            click("thread-author")
            assertEquals(listOf(RaftProfileColumnCell.Profile), panes.cells)
            assertFalse(focused, "opening the profile left Focus")
            // The row as without Focus: the conversation, the thread and the profile, all in sight.
            near(480f, widthDp(RAFT_PANE_GRID_MAIN_TEST_TAG), "the conversation")
            near(400f, widthDp(threadTag), "the thread")
            near(400f, widthDp(column), "the profile")
            onNodeWithTag("close:$MEMBER_DETAIL_PAGE", useUnmergedTree = true).assertExists()
            assertTrue(columnShown, "the shell counts the column as shown")
            assertSettled()
            // Back closes the profile it shows, then it is the shell's.
            back.press()
            assertFalse(panes.isOpen)
            assertEquals(0, pageBacks)
            back.press()
            assertEquals(1, pageBacks)
            assertSettled()
            threadOpen = false
        }

    // PR #4664 review, B: Focus entered with the column open puts it out of sight. It must be inert, not counted as
    // shown, and Back must leave Focus first, not close its pages unseen.
    @Test
    fun focusEnteredWithTheColumnOpenHidesItAndBackLeavesFocusFirst() =
        runRobolectricComposeUiTest {
            threadOpen = true
            rowWidth = 1280f
            val back = showWorkspace()
            click("avatar")
            click("gear")
            assertEquals(listOf(RaftProfileColumnCell.Profile, RaftProfileColumnCell.Details), panes.cells)
            assertTrue(columnShown)
            runOnIdle { focused = true }
            waitForIdle()
            // Out of sight: no width, nothing a screen reader or the keyboard can reach, not counted as shown.
            near(0f, widthDp(column), "the column in Focus")
            near(1280f, widthDp(threadTag), "the thread fills the row")
            onNodeWithTag("page:$MEMBER_DETAIL_PAGE", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("page:$CHANNEL_SETTINGS_PAGE", useUnmergedTree = true).assertDoesNotExist()
            assertFalse(columnShown, "the shell's Back step must not count a column out of sight")
            assertSettled()
            // Back leaves Focus; the pages are as they were and show again.
            back.press()
            assertFalse(focused, "Back left Focus")
            assertEquals(listOf(RaftProfileColumnCell.Profile, RaftProfileColumnCell.Details), panes.cells, "nothing closed unseen")
            near(400f, widthDp(column), "the column back in sight")
            onNodeWithTag("page:$MEMBER_DETAIL_PAGE", useUnmergedTree = true).assertExists()
            assertTrue(columnShown)
            // Then Back is the column's: the details (opened last), then the profile, then the shell's.
            back.press()
            assertEquals(listOf(RaftProfileColumnCell.Profile), panes.cells)
            back.press()
            assertFalse(panes.isOpen)
            assertEquals(0, pageBacks)
            back.press()
            assertEquals(1, pageBacks)
            assertSettled()
            threadOpen = false
        }

    private companion object {
        /** Far more compositions than a settled row ever needs, far fewer than a loop makes before the watchdog. */
        const val COMPOSITION_CAP = 200
    }
}
