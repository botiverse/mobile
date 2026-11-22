package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueTasksPageFoldPreferenceRepository
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksPageFolds
import build.raft.app.data.TasksQueryTestFixtures
import build.raft.app.data.TasksUiState
import build.raft.app.data.summaryCounts
import build.raft.app.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Tasks page keeps its folds per server (web tasksPageStore, botiverse/slock eb63aec86e, #15507):
 * a section opened by hand on one server's page is open when that server's page opens again, after
 * another server's page or a restart, and another server's page starts from its own folds. In the
 * app each server's page gets its route's [TasksPageFolds] (a server switch makes a new route); here
 * the folds handed to the page change the same way, each over a new store on the same device store.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h960dp")
class SharedTasksPageFoldsUiTest {
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

    private fun ComposeUiTest.shows(text: String): Boolean = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aSectionOpenedOnAServerIsOpenWhenItsPageOpensAgainAndAnotherServerHasItsOwn() = runRobolectricComposeUiTest {
        val device = InMemoryRaftKeyValueStore()
        fun foldsOf(server: String) = TasksPageFolds(server, KeyValueTasksPageFoldPreferenceRepository(device))
        var folds by mutableStateOf(foldsOf("server-a"))
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    SharedTasksPanel(
                        state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = tasks.summaryCounts(), tasks = tasks))),
                        onIntent = {},
                        folds = folds,
                    )
                }
            }
        }
        waitForIdle()
        assertTrue(shows(OpenTaskTitle), "the phone's list shows the open task")
        assertFalse(shows(DoneTaskTitle), "Done starts folded")

        // The Done section's header (its tasks are folded away, so the label is the header's alone).
        onNodeWithText("Done").performClick()
        waitForIdle()
        assertTrue(shows(DoneTaskTitle), "opened by hand")

        runOnIdle { folds = foldsOf("server-b") }
        waitForIdle()
        assertFalse(shows(DoneTaskTitle), "another server's page starts from its own folds")

        runOnIdle { folds = foldsOf("server-a") }
        waitForIdle()
        assertTrue(shows(DoneTaskTitle), "the first server's page opens with its Done open again")
    }
}

private const val OpenTaskTitle = "Add dark mode to the onboarding screens"
private const val DoneTaskTitle = "Translate the app into Japanese"

private val tasks =
    listOf(
        TasksQueryTestFixtures.task("task-1", status = TaskStatus.Todo, title = OpenTaskTitle),
        TasksQueryTestFixtures.task("task-2", status = TaskStatus.Done, title = DoneTaskTitle),
    )
