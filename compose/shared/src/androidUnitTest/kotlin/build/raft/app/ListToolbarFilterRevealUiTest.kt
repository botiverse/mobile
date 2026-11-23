package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.ComputersRackView
import build.raft.app.data.MembersAgentsGrouping
import build.raft.app.data.TasksPageQuery
import build.raft.app.data.TasksPageScope
import build.raft.app.data.TasksQueryState
import build.raft.app.data.TasksViewMode
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.TaskStatus
import build.raft.app.resources.StringDesc
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tasks, Computers, and Members list toolbars on `RaftFitToolbar` scroll an applied filter into
 * view on open and on change like web `raft-ui` `FitToolbar` (`useScrollGroup`) and keep default /
 * already-visible filters steady at the strip start (#proj-mobile:#295).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class ListToolbarFilterRevealUiTest {
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

    @Test
    fun tasksToolbarKeepsDefaultFiltersAtStart() =
        runRobolectricComposeUiTest {
            setContent {
                Host {
                    TasksToolbarAt(width = 300.dp, query = TasksPageQuery(scope = TasksPageScope.Mine))
                }
            }
            waitForIdle()
            assertAtStart(SharedTasksQueryToolbarTags.Filters, SharedTasksToolbarTags.Channel)
            assertTrue(
                bounds(SharedTasksToolbarTags.Assignee).right > bounds(SharedTasksQueryToolbarTags.Filters).right,
                "Assignee overflows past the right edge when nothing is applied",
            )
        }

    @Test
    fun tasksToolbarScrollsOffscreenActiveFilterIntoViewOnOpen() =
        runRobolectricComposeUiTest {
            setContent {
                Host {
                    TasksToolbarAt(
                        width = 300.dp,
                        query = TasksPageQuery(scope = TasksPageScope.Mine),
                        openFilterId = "assignee",
                    )
                }
            }
            waitForIdle()
            assertInStrip(SharedTasksQueryToolbarTags.Filters, SharedTasksToolbarTags.Assignee)
            assertTrue(
                bounds(SharedTasksToolbarTags.Channel).left < bounds(SharedTasksQueryToolbarTags.Filters).left,
                "strip scrolled past Channel on open to show active Assignee",
            )
        }

    @Test
    fun tasksToolbarScrollsAppliedFilterIntoViewOnChangeBothForwardAndAfterReorder() =
        runRobolectricComposeUiTest {
            var query by mutableStateOf(
                TasksPageQuery(
                    scope = TasksPageScope.Mine,
                    channelIds = setOf("ch-1"),
                    statuses = setOf(TaskStatus.InProgress),
                    creators = setOf("user:u1"),
                ),
            )
            setContent {
                Host { TasksToolbarAt(width = 300.dp, query = query) }
            }
            waitForIdle()
            // Multiple applied filters on open: first applied filter (Channel) stays at the start;
            // Assignee is past the right edge.
            assertAtStart(SharedTasksQueryToolbarTags.Filters, SharedTasksToolbarTags.Channel)
            assertTrue(
                bounds(SharedTasksToolbarTags.Assignee).right > bounds(SharedTasksQueryToolbarTags.Filters).right,
                "Assignee starts off-screen right while Channel, Status, Creator are applied",
            )

            // Applying Assignee appends it at index 3 (off-screen right) -> scrolls into view.
            query = query.copy(assignees = setOf("user:u2"))
            waitForIdle()
            assertInStrip(SharedTasksQueryToolbarTags.Filters, SharedTasksToolbarTags.Assignee)
            assertTrue(
                bounds(SharedTasksToolbarTags.Channel).left < bounds(SharedTasksQueryToolbarTags.Filters).left,
                "strip scrolled right to reveal newly applied Assignee",
            )

            // Reset to empty filters, manually scroll right so Assignee is visible, then apply
            // Assignee: it reorders to index 0 (off-screen left) and scrolls back into view.
            query = TasksPageQuery(scope = TasksPageScope.Mine)
            waitForIdle()
            onNodeWithTag(SharedTasksQueryToolbarTags.Filters).performTouchInput { swipeLeft() }
            waitForIdle()
            assertTrue(
                bounds(SharedTasksToolbarTags.Channel).left < bounds(SharedTasksQueryToolbarTags.Filters).left,
                "user scrolled right past Channel",
            )

            query = query.copy(assignees = setOf("user:u2"))
            waitForIdle()
            assertInStrip(SharedTasksQueryToolbarTags.Filters, SharedTasksToolbarTags.Assignee)
            assertAtStart(SharedTasksQueryToolbarTags.Filters, SharedTasksToolbarTags.Assignee)
        }

    @Test
    fun computersToolbarScrollsAppliedRuntimeFilterIntoViewOnChangeAndKeepsDefaultAtStart() =
        runRobolectricComposeUiTest {
            var filters by mutableStateOf(RaftComputersRackFilters())
            val runtimeOptions = listOf(RaftComputersRackRuntimeFilter.Claude, RaftComputersRackRuntimeFilter.Codex)
            setContent {
                Host {
                    ComputersToolbarAt(width = 230.dp, filters = filters, runtimeOptions = runtimeOptions)
                }
            }
            waitForIdle()
            // Default: All statuses at strip start, Runtime overflows on this narrow toolbar.
            assertAtStart(RaftComputersRackTags.FilterStrip, RaftComputersRackTags.Status)
            assertTrue(
                bounds(RaftComputersRackTags.Runtime).right > bounds(RaftComputersRackTags.FilterStrip).right,
                "Runtime overflows past the right edge at 230dp",
            )

            // Applying Status keeps Status at index 0 (start); then applying Runtime leaves
            // Runtime at index 1 (off-screen right) and scrolls it into view.
            filters = filters.copy(status = RaftComputersRackStatusFilter.Attention)
            waitForIdle()
            assertAtStart(RaftComputersRackTags.FilterStrip, RaftComputersRackTags.Status)

            filters = filters.copy(runtimes = listOf(RaftComputersRackRuntimeFilter.Claude))
            waitForIdle()
            assertInStrip(RaftComputersRackTags.FilterStrip, RaftComputersRackTags.Runtime)
            assertTrue(
                bounds(RaftComputersRackTags.Status).left < bounds(RaftComputersRackTags.FilterStrip).left,
                "strip scrolled past Status to reveal newly applied Runtime filter",
            )

            // Reset to default, manually scroll right, then apply Runtime alone: Runtime reorders
            // to index 0 (off-screen left) and scrolls back into view.
            filters = RaftComputersRackFilters()
            waitForIdle()
            onNodeWithTag(RaftComputersRackTags.FilterStrip).performTouchInput { swipeLeft() }
            waitForIdle()
            assertTrue(
                bounds(RaftComputersRackTags.Status).left < bounds(RaftComputersRackTags.FilterStrip).left,
                "user scrolled right past Status",
            )

            filters = filters.copy(runtimes = listOf(RaftComputersRackRuntimeFilter.Claude))
            waitForIdle()
            assertInStrip(RaftComputersRackTags.FilterStrip, RaftComputersRackTags.Runtime)
            assertAtStart(RaftComputersRackTags.FilterStrip, RaftComputersRackTags.Runtime)
        }

    @Test
    fun membersToolbarRendersCleanlyWithDefaultAndAppliedGroupingOnFitToolbar() =
        runRobolectricComposeUiTest {
            setContent {
                Host {
                    MembersListVisualContent(store = MembersGroupingTestStore(grouping = MembersAgentsGrouping.Computer))
                }
            }
            waitForIdle()
            onNodeWithTag(MembersSearchTestTag, useUnmergedTree = true).assertExists()
            onNodeWithTag(MembersScopeTestTag, useUnmergedTree = true).assertExists()
        }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftDesignSystemTheme { content() }
            }
        }
    }

    @Composable
    private fun TasksToolbarAt(
        width: Dp,
        query: TasksPageQuery,
        openFilterId: String? = null,
    ) {
        Box(Modifier.width(width)) {
            SharedTasksQueryToolbar(
                state = TasksQueryState(query = query, started = true),
                scopeCounts = mapOf(TasksPageScope.Mine to 3, TasksPageScope.All to 12),
                onIntent = {},
                selectedMode = TasksViewMode.List,
                onModeSelected = {},
                openFilterId = openFilterId,
                onFilterClick = {},
            )
        }
    }

    @Composable
    private fun ComputersToolbarAt(
        width: Dp,
        filters: RaftComputersRackFilters,
        runtimeOptions: List<RaftComputersRackRuntimeFilter>,
    ) {
        val state = RaftComputersRackToolbarState(serverId = "server-1", filters = filters)
        val model =
            RaftComputersRackToolbarModel(
                runtimeOptions = runtimeOptions,
                filters = filters,
                sort = null,
                sortMenuSections = listOf(listOf(null)),
            )
        Box(Modifier.width(width)) {
            RaftComputersTheme {
                RaftComputersRackToolbar(
                    state = state,
                    model = model,
                    onIntent = {},
                    view = ComputersRackView.Cards,
                    onAddComputer = {},
                )
            }
        }
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.assertInStrip(stripTag: String, chipTag: String) {
        val strip = bounds(stripTag)
        val chip = bounds(chipTag)
        assertTrue(
            chip.left >= strip.left - 0.5.dp && chip.right <= strip.right + 0.5.dp,
            "$chipTag is in view: chip ${chip.left}..${chip.right}, strip ${strip.left}..${strip.right}",
        )
    }

    private fun ComposeUiTest.assertAtStart(stripTag: String, chipTag: String) {
        val strip = bounds(stripTag)
        val chip = bounds(chipTag)
        assertTrue(
            chip.left >= strip.left - 0.5.dp && chip.left <= strip.left + 1.dp,
            "$chipTag stays at strip start: chip.left=${chip.left}, strip.left=${strip.left}",
        )
    }
}
