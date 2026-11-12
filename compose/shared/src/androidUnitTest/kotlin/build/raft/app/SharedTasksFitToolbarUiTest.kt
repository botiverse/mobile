package build.raft.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.TaskWireDto
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksPresentationState
import build.raft.app.data.TasksUiState
import build.raft.app.data.TasksViewMode
import build.raft.app.data.summaryCounts
import build.raft.app.data.toTaskSummary
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Tasks toolbar on RaftFitToolbar (web TasksPageToolbar and the channel TasksPanel toolbar,
 * slock staging b76fbb9ea1): on the Tasks page Channel, Creator and Assignee, then List / Board
 * (List first) and no New Task; in a channel Creator and Assignee, then Board / List (Board
 * first) and New Task last. Clear all shows only while a filter is applied, a filter opens its
 * sheet, and the toolbar (it has no search) never takes system Back from the page.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h900dp")
class SharedTasksFitToolbarUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    // The panel reads its view-mode preference from the app-scope store; tests give it a blank one.
    @Before
    fun installPreferenceStore() {
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @After
    fun clearPreferenceStore() = RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

    /** System Back, and how many reached the page (a handler composed before the panel's). */
    private class Back(val press: () -> Unit, val pageBacks: () -> Int)

    private fun ComposeUiTest.show(
        channelMode: Boolean,
        initial: TasksPresentationState = TasksPresentationState.Default.copy(selectedMode = TasksViewMode.List),
    ): Back {
        var dispatcher: OnBackPressedDispatcher? = null
        var pageBacks = 0
        setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            RaftBackHandler(enabled = true, label = "test_page") { pageBacks++ }
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    SharedTasksPanel(
                        state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = tasks.summaryCounts(), tasks = tasks))),
                        onIntent = {},
                        showHeader = !channelMode,
                        showChannelFilter = !channelMode,
                        createTasksChannelId = if (channelMode) ChannelId("ch-ship-mobile") else null,
                        initialPresentationOverride = initial,
                        identityOptions = listOf(maya),
                    )
                }
            }
        }
        waitForIdle()
        return Back(
            press = {
                runOnIdle { assertNotNull(dispatcher, "the activity's Back dispatcher").onBackPressed() }
                waitForIdle()
            },
            pageBacks = { pageBacks },
        )
    }

    private fun ComposeUiTest.has(tag: String): Boolean = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.left(tag: String): Float = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left

    @Test
    fun theTasksPageHasEveryFilterAndListBeforeBoardButNoNewTask() = runRobolectricComposeUiTest {
        show(channelMode = false)
        assertTrue(has(SharedTasksToolbarTags.Toolbar))
        assertTrue(has(SharedTasksToolbarTags.Channel) && has(SharedTasksToolbarTags.Creator) && has(SharedTasksToolbarTags.Assignee))
        assertFalse(has(SharedTasksToolbarTags.Clear), "no Clear all while no filter is applied")
        assertFalse(has(SharedTasksToolbarTags.NewTask), "the server-wide page has no channel to make a task in")
        val list = "${SharedTasksToolbarTags.View}-list"
        val board = "${SharedTasksToolbarTags.View}-board"
        assertTrue(left(list) < left(board), "List first on the page")
        onNodeWithTag(board).performClick()
        waitForIdle()
        onNodeWithTag(board).assertIsSelected()
    }

    @Test
    fun aChannelsTasksHaveNoChannelFilterBoardFirstAndNewTaskLast() = runRobolectricComposeUiTest {
        show(channelMode = true, initial = TasksPresentationState.Default.copy(selectedMode = TasksViewMode.Board))
        assertFalse(has(SharedTasksToolbarTags.Channel), "a channel's tasks are that channel's")
        assertTrue(has(SharedTasksToolbarTags.Creator) && has(SharedTasksToolbarTags.Assignee))
        val list = "${SharedTasksToolbarTags.View}-list"
        val board = "${SharedTasksToolbarTags.View}-board"
        onNodeWithTag(board).assertIsSelected()
        assertTrue(left(board) < left(list), "Board first in a channel")
        assertTrue(has(SharedTasksToolbarTags.NewTask))
        assertTrue(left(list) < left(SharedTasksToolbarTags.NewTask), "New Task ends the line")
    }

    @Test
    fun clearAllShowsWhileAFilterIsAppliedAndClearsIt() = runRobolectricComposeUiTest {
        show(
            channelMode = false,
            initial = TasksPresentationState.Default.copy(selectedMode = TasksViewMode.List, selectedCreatorIds = setOf("user:user-maya")),
        )
        assertTrue(has(SharedTasksToolbarTags.Clear))
        assertTrue(
            onAllNodes(hasTestTag(SharedTasksToolbarTags.Creator) and hasContentDescription("Creator 1")).fetchSemanticsNodes().isNotEmpty(),
            "an applied filter says how many it has chosen",
        )
        assertTrue(left(SharedTasksToolbarTags.Creator) < left(SharedTasksToolbarTags.Channel), "the applied filter comes first")
        onNodeWithTag(SharedTasksToolbarTags.Clear).performClick()
        waitForIdle()
        assertFalse(has(SharedTasksToolbarTags.Clear))
        assertTrue(onAllNodes(hasTestTag(SharedTasksToolbarTags.Creator) and hasContentDescription("Creator")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aFilterOpensItsSheet() = runRobolectricComposeUiTest {
        show(channelMode = false)
        assertTrue(onAllNodes(hasText("Filter by creator")).fetchSemanticsNodes().isEmpty())
        onNodeWithTag(SharedTasksToolbarTags.Creator).performClick()
        waitForIdle()
        assertTrue(onAllNodes(hasText("Filter by creator")).fetchSemanticsNodes().isNotEmpty(), "Creator opens its searchable sheet")
    }

    @Test
    fun theToolbarNeverTakesBackFromThePage() = runRobolectricComposeUiTest {
        val back = show(channelMode = true)
        back.press()
        assertEquals(1, back.pageBacks(), "no search, so Back stays the page's")
    }
}

private val maya =
    MemberProfile(id = MemberId("user-maya"), displayName = "Maya Chen", handle = "maya", kind = MemberKind.Human)

private fun task(number: Int, title: String, status: String): TaskSummary =
    TaskWireDto(
        id = "task-$number",
        messageId = "msg-task-$number",
        channelId = "ch-ship-mobile",
        channelName = "ship-mobile",
        channelType = "channel",
        taskNumber = number,
        title = title,
        status = status,
        createdByType = "user",
        createdById = "user-maya",
        createdByName = "Maya Chen",
        createdAt = "2026-10-29T18:38:00Z",
        updatedAt = "2026-10-29T19:12:05Z",
    ).toTaskSummary() ?: error("fixture task #$number must map")

private val tasks =
    listOf(
        task(1, "Add dark mode to the onboarding screens", "todo"),
        task(4, "Migrate push notifications to the new API", "in_progress"),
    )
