package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task v2 Backlog (slock #12962, raft-design decision 0042, tasks.md / color.md#statuses): a backlog
 * task shows in the Backlog column, first on the board, with the hollow `line-strong` pixel and the
 * outlined chip (no tint); a status this build does not know keeps its task in a last, neutral column.
 * - `tasks.backlog.board`: Backlog expanded, light.
 * - `tasks.backlog.board-dark`: the same in dark (the hollow pixel's edge stays visible).
 * - `tasks.backlog.list`: the list as it opens: Backlog, Done and Closed collapsed.
 * - `tasks.backlog.unknown-status`: a task whose status the app does not know, in its last group (list).
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1400dp-h420dp-mdpi")
class TasksBacklogScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun boardShowsBacklogFirst() = capture("tasks.backlog.board", RaftAppAppearance.Light, tasks, TasksViewMode.Board, backlogOpen)

    @Test
    fun boardShowsBacklogFirstInDark() = capture("tasks.backlog.board-dark", RaftAppAppearance.Dark, tasks, TasksViewMode.Board, backlogOpen)

    @Test
    fun listStartsWithBacklogCollapsed() =
        capture("tasks.backlog.list", RaftAppAppearance.Light, tasks, TasksViewMode.List, TasksPresentationState.Default.collapsedStatuses)

    @Test
    fun anUnknownStatusTaskStillShows() =
        capture(
            "tasks.backlog.unknown-status",
            RaftAppAppearance.Light,
            listOf(task(1, "Add dark mode to the onboarding screens", "todo"), task(9, "Status from a newer server", "on_hold")),
            TasksViewMode.List,
            TasksPresentationState.Default.collapsedStatuses,
        )

    // The panel reads its view-mode preference from the app-scope store; tests give it a blank one.
    @org.junit.Before
    fun installPreferenceStore() {
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @org.junit.After
    fun clearPreferenceStore() = RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

    private fun capture(
        name: String,
        appearance: RaftAppAppearance,
        tasks: List<TaskSummary>,
        mode: TasksViewMode,
        collapsed: Set<TaskStatus>,
    ) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { BacklogTasksPanel(tasks, mode, collapsed) }
            }
        }
}

private val backlogOpen = setOf(TaskStatus.Done, TaskStatus.Closed)

@Composable
private fun BacklogTasksPanel(tasks: List<TaskSummary>, mode: TasksViewMode, collapsed: Set<TaskStatus>) {
    Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        SharedTasksPanel(
            state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = tasks.summaryCounts(), tasks = tasks))),
            onIntent = {},
            initialPresentationOverride = TasksPresentationState.Default.copy(selectedMode = mode, collapsedStatuses = collapsed),
            taskV2Enabled = true,
        )
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
        completedAt = if (status == "done") "2026-10-29T19:12:05Z" else null,
        createdAt = "2026-10-29T18:38:00Z",
        updatedAt = "2026-10-29T19:12:05Z",
    ).toTaskSummary(diagnostics = {}) ?: error("fixture task #$number must map")

private val tasks =
    listOf(
        task(2, "Offline drafts, after the sync rewrite", "backlog"),
        task(1, "Add dark mode to the onboarding screens", "todo"),
        task(4, "Migrate push notifications to the new API", "in_progress"),
        task(6, "Translate the app into Japanese", "done"),
    )
