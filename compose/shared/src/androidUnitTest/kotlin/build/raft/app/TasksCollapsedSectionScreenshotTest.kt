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
 * The server Tasks page as it opens: Done (and Closed) start collapsed, like the web. A
 * collapsed section shows only its header and count, so the header carries the raft-ui chevron
 * (turned right while collapsed, down while open) to say it expands — leading in the list,
 * trailing in a board column. Before it, "Done 1" read as a count over nothing to tap.
 * - `tasks.sections.collapsed-done.list`: phone list view, one task per open status + one done.
 * - `tasks.sections.collapsed-done.board`: the same tasks as board columns on a wide screen.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h960dp-xhdpi")
class TasksCollapsedSectionScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun listDoneCollapsed() = capture("tasks.sections.collapsed-done.list", TasksViewMode.List)

    @Test
    @Config(qualifiers = "w1400dp-h420dp-mdpi")
    fun boardDoneCollapsed() = capture("tasks.sections.collapsed-done.board", TasksViewMode.Board)

    // The panel reads its view-mode preference from the app-scope store; tests give it a blank one.
    @org.junit.Before
    fun installPreferenceStore() {
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @org.junit.After
    fun clearPreferenceStore() = RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

    private fun capture(name: String, mode: TasksViewMode) =
        RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { TasksPanel(mode) }
            }
        }
}

@Composable
private fun TasksPanel(mode: TasksViewMode) {
    Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        SharedTasksPanel(
            state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = tasks.summaryCounts(), tasks = tasks))),
            onIntent = {},
            // The product default (Done + Closed collapsed); only the view mode is pinned.
            initialPresentationOverride = TasksPresentationState.Default.copy(selectedMode = mode),
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
    ).toTaskSummary() ?: error("fixture task #$number must map")

private val tasks =
    listOf(
        task(1, "Add dark mode to the onboarding screens", "todo"),
        task(4, "Migrate push notifications to the new API", "in_progress"),
        task(3, "Fix crash on the settings screen", "in_review"),
        task(6, "Translate the app into Japanese", "done"),
    )
