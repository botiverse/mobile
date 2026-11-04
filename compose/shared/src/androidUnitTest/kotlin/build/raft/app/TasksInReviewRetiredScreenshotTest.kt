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
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * In Review is retired for task v2 (raft-design decision 0043, web slock #12884 TasksPanel): with
 * `task_v2_conversations_v0` on the Tasks board has no empty In Review column, and a pre-v2 task
 * still in it keeps the column so it stays reachable.
 * - `tasks.in-review-retired.board`: task v2 on, nothing in In Review: five columns.
 * - `tasks.in-review-retired.board-legacy`: task v2 on, a legacy task still In Review: its column stays.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1400dp-h420dp-mdpi")
class TasksInReviewRetiredScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun boardWithoutInReview() = capture("tasks.in-review-retired.board", v2Tasks)

    @Test
    fun boardKeepsALegacyInReviewTask() = capture("tasks.in-review-retired.board-legacy", v2Tasks + legacyInReview)

    // The panel reads its view-mode preference from the app-scope store; tests give it a blank one.
    @org.junit.Before
    fun installPreferenceStore() {
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @org.junit.After
    fun clearPreferenceStore() = RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

    private fun capture(name: String, tasks: List<TaskSummary>) =
        RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { TasksBoard(tasks) }
            }
        }
}

@Composable
private fun TasksBoard(tasks: List<TaskSummary>) {
    Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        SharedTasksPanel(
            state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = tasks.summaryCounts(), tasks = tasks))),
            onIntent = {},
            initialPresentationOverride = TasksPresentationState.Default.copy(selectedMode = TasksViewMode.Board),
            taskV2Enabled = true,
        )
    }
}

private fun task(number: Int, title: String, status: String, isLegacy: Boolean = false): TaskSummary =
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
        isLegacy = isLegacy,
    ).toTaskSummary() ?: error("fixture task #$number must map")

private val v2Tasks =
    listOf(
        task(1, "Add dark mode to the onboarding screens", "todo"),
        task(4, "Migrate push notifications to the new API", "in_progress"),
        task(6, "Translate the app into Japanese", "done"),
    )

private val legacyInReview = task(3, "Fix crash on the settings screen", "in_review", isLegacy = true)
