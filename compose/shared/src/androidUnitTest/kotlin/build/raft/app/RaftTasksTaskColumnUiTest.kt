package build.raft.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
 * The Tasks page's task column on the wide layout (web TasksRoute): a task opened from the list
 * stands beside it at 400dp while the list stays in use, another row replaces it, its card shows
 * it open, Expand fills the row, Close and system Back close it (Back leaves Expand first), and
 * where the row can't keep the list's 360 beside the column's 320 the task takes the row. The
 * production [RaftTasksTaskColumnLayout] and [SharedTasksPanel], the shell's state reduced to
 * what it keeps for this (SettingsHomePage `tasksDetailColumn*`).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class RaftTasksTaskColumnUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Before
    fun installPreferenceStore() {
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @After
    fun clearPreferenceStore() = RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

    /** What the shell keeps: the open task, Expand, and how often the column's Back reached it. */
    private class Shell {
        var openTaskId by mutableStateOf<String?>(null)
        var expanded by mutableStateOf(false)
        var pageBacks = 0

        fun open(task: TaskSummary) {
            openTaskId = task.id.value
            expanded = false
        }

        fun close() {
            openTaskId = null
            expanded = false
        }
    }

    private class Back(val press: () -> Unit)

    private fun ComposeUiTest.show(shell: Shell, widthDp: Int? = null): Back {
        var dispatcher: OnBackPressedDispatcher? = null
        setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            // The page's own Back, composed before the column's: it only gets what the column leaves.
            RaftBackHandler(enabled = true, label = "test_page") { shell.pageBacks++ }
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val grid = remember { RaftPaneGridStore() }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftPaneGridStore provides grid) {
                RaftDesignSystemTheme {
                    RaftTasksTaskColumnLayout(
                        open = shell.openTaskId != null,
                        expanded = shell.expanded,
                        active = true,
                        onToggleExpand = { shell.expanded = !shell.expanded },
                        onClose = shell::close,
                        onBack = shell::close,
                        modifier = (widthDp?.let { Modifier.width(it.dp).fillMaxHeight() } ?: Modifier.fillMaxSize()).testTag(ROOT),
                        task = { TaskFixture(shell.openTaskId) },
                    ) {
                        CompositionLocalProvider(LocalSharedTasksOpenTaskId provides shell.openTaskId) {
                            SharedTasksPanel(
                                state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = tasks.summaryCounts(), tasks = tasks))),
                                onIntent = {},
                                onOpenTask = shell::open,
                                initialPresentationOverride = TasksPresentationState.Default.copy(selectedMode = TasksViewMode.List),
                            )
                        }
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

    /** The page in the column: its head ends with the column's actions, as ThreadPageHeader does. */
    @Composable
    private fun TaskFixture(taskId: String?) {
        Box(Modifier.testTag("task-page-$taskId")) {
            LocalRaftTaskColumnHead.current?.let { RaftTaskColumnHeadActions(it) }
        }
    }

    private fun ComposeUiTest.has(tag: String): Boolean = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.widthOf(tag: String): Float = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width

    private fun ComposeUiTest.openCardShows(title: String): Boolean =
        onAllNodes(isSelected() and hasAnyDescendant(hasText(title))).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aTaskOpensBesideTheListWhichStaysInUseAndAnotherReplacesIt() = runRobolectricComposeUiTest {
        val shell = Shell()
        show(shell)
        assertFalse(has(RaftTasksTaskColumnTags.Column), "nothing open: the list has the row")

        onNodeWithText(tasks[0].title).performClick()
        waitForIdle()
        assertEquals("task-1", shell.openTaskId)
        onNodeWithTag("task-page-task-1").assertExists()
        val root = widthOf(ROOT)
        val column = widthOf(RaftTasksTaskColumnTags.Column)
        // The detail column's 400 (web COLUMN_BOUNDS.detail), its 1dp left seam included.
        assertEquals(399f, column, 1.5f, "the task column's default width")
        assertEquals(root - 400f, widthOf(RaftTasksTaskColumnTags.List), 1.5f, "the list keeps the rest of the row")
        assertTrue(openCardShows(tasks[0].title), "the open task's card shows it")

        // Non-modal: the list beside it still takes taps, and another row replaces the task.
        onNodeWithText(tasks[1].title).performClick()
        waitForIdle()
        assertEquals("task-4", shell.openTaskId)
        onNodeWithTag("task-page-task-4").assertExists()
        assertFalse(has("task-page-task-1"), "one task at a time")
        assertTrue(openCardShows(tasks[1].title))
        assertFalse(openCardShows(tasks[0].title), "the card of the task replaced no longer shows open")

        onNodeWithTag(RaftTasksTaskColumnTags.Close).performClick()
        waitForIdle()
        assertEquals(null, shell.openTaskId)
        assertFalse(has(RaftTasksTaskColumnTags.Column))
        assertEquals(root, widthOf(RaftTasksTaskColumnTags.List), 1.5f, "closed: the list has the row again")
    }

    @Test
    fun expandFillsTheRowAndBackLeavesExpandBeforeClosingTheColumn() = runRobolectricComposeUiTest {
        val shell = Shell()
        val back = show(shell)
        onNodeWithText(tasks[0].title).performClick()
        waitForIdle()

        onNodeWithTag(RaftTasksTaskColumnTags.Expand).performClick()
        waitForIdle()
        assertTrue(shell.expanded)
        assertEquals(widthOf(ROOT), widthOf(RaftTasksTaskColumnTags.Column), 1.5f, "expanded, the task fills the row")
        assertEquals(0f, widthOf(RaftTasksTaskColumnTags.List), 0.5f, "the list stays composed, out of sight")

        back.press()
        assertFalse(shell.expanded, "Back restores the row first")
        assertEquals("task-1", shell.openTaskId, "and keeps the task open")
        assertEquals(399f, widthOf(RaftTasksTaskColumnTags.Column), 1.5f)

        back.press()
        assertEquals(null, shell.openTaskId, "the next Back closes the column")
        assertEquals(0, shell.pageBacks, "neither Back reached the page")

        back.press()
        assertEquals(1, shell.pageBacks, "with the column closed Back is the page's")
    }

    @Test
    fun withoutRoomBesideTheListTheTaskTakesTheRow() = runRobolectricComposeUiTest {
        val shell = Shell()
        val back = show(shell, widthDp = 640)
        onNodeWithText(tasks[0].title).performClick()
        waitForIdle()
        assertEquals(widthOf(ROOT), widthOf(RaftTasksTaskColumnTags.Column), 1.5f, "under 360 + 320 the task has the row")
        assertFalse(has(RaftTasksTaskColumnTags.Expand), "nothing to expand into")
        onNodeWithTag(RaftTasksTaskColumnTags.Close).assertExists()

        back.press()
        assertEquals(null, shell.openTaskId, "Back returns to the list")
        assertEquals(widthOf(ROOT), widthOf(RaftTasksTaskColumnTags.List), 1.5f)
        assertEquals(0, shell.pageBacks)
    }

    @Test
    fun theColumnStandsBesideTheListFromTheTwoMinimums() {
        assertTrue(raftTasksTaskColumnBeside(680f))
        assertFalse(raftTasksTaskColumnBeside(679f))
    }

    private companion object {
        const val ROOT = "tasks-task-column-root"
    }
}

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
    ).toTaskSummary(diagnostics = {}) ?: error("fixture task #$number must map")

private val tasks =
    listOf(
        task(1, "Add dark mode to the onboarding screens", "todo"),
        task(4, "Migrate push notifications to the new API", "in_progress"),
    )
