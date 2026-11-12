package build.raft.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.TasksPageQuery
import build.raft.app.data.TasksPageScope
import build.raft.app.data.TasksQueryIntent
import build.raft.app.data.TasksQueryState
import build.raft.app.data.TasksViewMode
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.TaskStatus
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Tasks page's toolbar (web TasksPageToolbar, slock staging c6580f2f41a6): Mine / All with
 * their numbers, the Status multi-select, Clear all while a filter is set, and the search's Back
 * as the Members toolbar's: only while the search covers the scope, text first, then the search.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedTasksQueryToolbarUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private class Harness(val press: () -> Unit, val pageBacks: () -> Int, val intents: List<TasksQueryIntent>, val state: () -> TasksQueryState)

    private fun ComposeUiTest.show(width: androidx.compose.ui.unit.Dp? = null): Harness {
        var dispatcher: OnBackPressedDispatcher? = null
        var pageBacks = 0
        val intents = mutableListOf<TasksQueryIntent>()
        var current = TasksQueryState(query = TasksPageQuery(scope = TasksPageScope.Mine), started = true)
        setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            RaftBackHandler(enabled = true, label = "test_page") { pageBacks++ }
            var state by remember { mutableStateOf(current) }
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    androidx.compose.foundation.layout.Box(
                        if (width == null) androidx.compose.ui.Modifier else androidx.compose.ui.Modifier.width(width),
                    ) {
                    SharedTasksQueryToolbar(
                        state = state,
                        scopeCounts = mapOf(TasksPageScope.Mine to 3, TasksPageScope.All to 12),
                        onIntent = { intent ->
                            intents += intent
                            state = state.reducedForTest(intent)
                            current = state
                        },
                        selectedMode = TasksViewMode.List,
                        onModeSelected = {},
                        openFilterId = null,
                        onFilterClick = {},
                    )
                    }
                }
            }
        }
        waitForIdle()
        return Harness(
            press = {
                runOnIdle { assertNotNull(dispatcher, "the activity's Back dispatcher").onBackPressed() }
                waitForIdle()
            },
            pageBacks = { pageBacks },
            intents = intents,
            state = { current },
        )
    }

    private fun ComposeUiTest.has(tag: String): Boolean = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    @Config(qualifiers = "w1280dp-h900dp")
    fun scopeStatusAndClearAllDriveTheQuery() = runRobolectricComposeUiTest {
        val harness = show()
        assertTrue(has("${SharedTasksQueryToolbarTags.Scope}-all"))
        assertFalse(has(SharedTasksToolbarTags.Clear), "no Clear all while no filter is set")

        onNodeWithTag("${SharedTasksQueryToolbarTags.Scope}-all").performClick()
        waitForIdle()
        assertEquals(TasksPageScope.All, harness.state().query.scope)

        onNodeWithTag(SharedTasksQueryToolbarTags.Status).performClick()
        waitForIdle()
        onNodeWithTag("${SharedTasksQueryToolbarTags.Status}-in_progress").performClick()
        waitForIdle()
        assertEquals(setOf(TaskStatus.InProgress), harness.state().query.statuses)
        assertTrue(has(SharedTasksToolbarTags.Clear), "Clear all once a filter is set")

        onNodeWithTag(SharedTasksToolbarTags.Clear).performClick()
        waitForIdle()
        assertTrue(harness.state().query.statuses.isEmpty())
        assertTrue(TasksQueryIntent.ClearFilters in harness.intents)
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp")
    fun anInlineSearchLeavesBackToThePage() = runRobolectricComposeUiTest {
        val harness = show()
        onNodeWithTag(SharedTasksQueryToolbarTags.Search).performTextInput("ship")
        waitForIdle()
        assertEquals("ship", harness.state().searchDraft)
        harness.press()
        assertEquals(1, harness.pageBacks(), "a search on its own line never takes Back")
        assertEquals("ship", harness.state().searchDraft)
    }

    @Test
    fun aSearchOverTheScopeTakesBackTextFirstThenItself() = runRobolectricComposeUiTest {
        // Narrow enough that even the search's least width doesn't fit (320dp still did, CI run
        // 43246137178); Computers' same case gives way at 150dp.
        val harness = show(width = 150.dp)
        assertFalse(has(SharedTasksQueryToolbarTags.Search), "a narrow toolbar gives the search way to its button")
        onAllNodes(hasContentDescription("Search tasks")).onFirst().performClick()
        waitForIdle()
        onNodeWithTag(SharedTasksQueryToolbarTags.Search).performTextInput("ship")
        waitForIdle()

        harness.press()
        assertEquals("", harness.state().searchDraft, "Back empties the search first")
        assertTrue(has(SharedTasksQueryToolbarTags.Search))
        assertEquals(0, harness.pageBacks())

        harness.press()
        assertFalse(has(SharedTasksQueryToolbarTags.Search), "then closes it")
        assertEquals(0, harness.pageBacks())

        harness.press()
        assertEquals(1, harness.pageBacks(), "then Back is the page's again")
    }
}

private fun TasksQueryState.reducedForTest(intent: TasksQueryIntent): TasksQueryState =
    when (intent) {
        is TasksQueryIntent.SetScope -> copy(query = query.copy(scope = intent.scope))
        is TasksQueryIntent.SetSearch -> copy(searchDraft = intent.text)
        is TasksQueryIntent.ToggleStatus ->
            copy(query = query.copy(statuses = if (intent.status in query.statuses) query.statuses - intent.status else query.statuses + intent.status))
        TasksQueryIntent.ClearFilters -> copy(query = query.copy(statuses = emptySet(), channelIds = emptySet(), creators = emptySet(), assignees = emptySet()))
        else -> this
    }
