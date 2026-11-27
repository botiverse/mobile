package build.raft.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
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
import build.raft.app.data.AppLocale
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.TaskWireDto
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksPresentationState
import build.raft.app.data.TasksUiState
import build.raft.app.data.TasksViewMode
import build.raft.app.data.stringDescLocaleType
import build.raft.app.data.summaryCounts
import build.raft.app.data.toTaskSummary
import build.raft.app.model.TaskSummary
import build.raft.app.resources.StringDesc
import build.raft.app.shell.LocalRaftPaneGridStore
import build.raft.app.shell.LocalRaftTopSafeAreaConsumed
import build.raft.app.shell.RaftPaneGridStore
import build.raft.designsystem.gesture.LocalRaftBackSwipeRegions
import build.raft.designsystem.gesture.RaftBackSwipeRegions
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

    private var previousLocale = StringDesc.localeType

    @Before
    fun installPreferenceStore() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @After
    fun clearPreferenceStore() {
        StringDesc.localeType = previousLocale
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
    }

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

    /**
     * An icon button's test tag sits on its tooltip box; its click is on the button inside (the
     * same split RaftFitToolbarPartsTest found). Click the clickable node, tagged or inside the tag.
     */
    private fun ComposeUiTest.clickTagged(tag: String) {
        onNode(hasClickAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag)))).performClick()
        waitForIdle()
    }

    private class Back(val press: () -> Unit)

    private fun ComposeUiTest.show(shell: Shell, widthDp: Int? = null, swipe: RaftBackSwipeRegions? = null): Back {
        var dispatcher: OnBackPressedDispatcher? = null
        setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            // The page's own Back, composed before the column's: it only gets what the column leaves.
            RaftBackHandler(enabled = true, label = "test_page") { shell.pageBacks++ }
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val grid = remember { RaftPaneGridStore() }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftPaneGridStore provides grid,
                LocalRaftBackSwipeRegions provides swipe,
            ) {
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
        // The head's actions at its end, as the task page's header has them: at the column's start
        // they sat under its resize edge, which took the tap.
        Box(Modifier.fillMaxWidth().testTag("task-page-$taskId"), contentAlignment = androidx.compose.ui.Alignment.TopEnd) {
            androidx.compose.foundation.layout.Row {
                LocalRaftTaskColumnHead.current?.let { RaftTaskColumnHeadActions(it) }
            }
        }
    }

    private fun ComposeUiTest.has(tag: String): Boolean = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.widthOf(tag: String): Float = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width

    private fun ComposeUiTest.openCardShows(title: String): Boolean =
        onAllNodes(isSelected() and hasAnyDescendant(hasText(title))).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aTaskOpensBesideTheListWhichStaysInUseAndAnotherReplacesIt() = runRobolectricComposeUiTest {
        val shell = Shell()
        val swipe = RaftBackSwipeRegions()
        show(shell, swipe = swipe)
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
        runOnIdle {
            assertFalse(swipe.contentConsumesBackSwipeAt(200f, 500f), "beside the list the column keeps the page's back swipe")
        }

        // Non-modal: the list beside it still takes taps, and another row replaces the task.
        onNodeWithText(tasks[1].title).performClick()
        waitForIdle()
        assertEquals("task-4", shell.openTaskId)
        onNodeWithTag("task-page-task-4").assertExists()
        assertFalse(has("task-page-task-1"), "one task at a time")
        assertTrue(openCardShows(tasks[1].title))
        assertFalse(openCardShows(tasks[0].title), "the card of the task replaced no longer shows open")

        clickTagged(RaftTasksTaskColumnTags.Close)
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

        clickTagged(RaftTasksTaskColumnTags.Expand)
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

    private fun ComposeUiTest.clickTaskInList(title: String) {
        onNode(hasText(title) and hasAnyAncestor(hasTestTag(RaftTasksTaskColumnTags.List))).performClick()
        waitForIdle()
    }

    @Test
    fun pendingTaskWithoutDiscussionOpensInColumnWithExpandAndCloseAndNoBack() = runRobolectricComposeUiTest {
        val flow = TaskOpenSurfaceFlow()
        var expanded by mutableStateOf(false)
        var pendingColumnAllowed by mutableStateOf(true)
        var liveDetailColumnOpen by mutableStateOf(false)
        var serverId by mutableStateOf("server-1")
        var pendingSurfaceServerId by mutableStateOf<String?>(null)
        var pageBacks = 0
        var dispatcher: OnBackPressedDispatcher? = null

        fun closePending() {
            if (flow.requestClose()) {
                pendingSurfaceServerId = null
                if (!liveDetailColumnOpen) {
                    expanded = false
                }
            }
        }

        setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            RaftBackHandler(enabled = true, label = "test_pending_page") { pageBacks++ }
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            val grid = remember { RaftPaneGridStore() }
            val pendingSurface =
                (flow.surface as? TaskOpenSurface.Pending).takeIf {
                    pendingColumnAllowed && !liveDetailColumnOpen && pendingSurfaceServerId == serverId
                }
            TasksTabPendingColumnEffects(
                flow = flow,
                pendingColumnAllowed = pendingColumnAllowed,
                liveDetailColumnOpen = liveDetailColumnOpen,
                serverId = serverId,
                onPendingClosed = {
                    pendingSurfaceServerId = null
                    if (!liveDetailColumnOpen) expanded = false
                },
            )
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftPaneGridStore provides grid,
                LocalRaftTopSafeAreaConsumed provides true,
            ) {
                RaftDesignSystemTheme {
                    RaftTasksTaskColumnLayout(
                        open = liveDetailColumnOpen || pendingSurface != null,
                        expanded = expanded,
                        active = true,
                        onToggleExpand = { expanded = !expanded },
                        onClose = {
                            if (pendingSurface != null) {
                                closePending()
                            } else {
                                liveDetailColumnOpen = false
                                expanded = false
                            }
                        },
                        onBack = {
                            if (pendingSurface != null) {
                                closePending()
                            } else {
                                liveDetailColumnOpen = false
                                expanded = false
                            }
                        },
                        modifier = Modifier.fillMaxSize().testTag(ROOT),
                        task = {
                            if (pendingSurface != null) {
                                TaskPendingSurfaceContent(
                                    surface = pendingSurface,
                                    flow = flow,
                                    onSend = { _, _ -> },
                                    serverId = serverId,
                                    useHostSafeArea = true,
                                    onBack = { closePending() },
                                )
                            } else {
                                TaskFixture("live-task")
                            }
                        },
                    ) {
                        CompositionLocalProvider(LocalSharedTasksOpenTaskId provides pendingSurface?.task?.id?.value) {
                            SharedTasksPanel(
                                state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = tasks.summaryCounts(), tasks = tasks))),
                                onIntent = {},
                                onOpenTask = { clicked ->
                                    if (!flow.sending) {
                                        if (liveDetailColumnOpen) liveDetailColumnOpen = false
                                        expanded = false
                                        pendingSurfaceServerId = serverId
                                        flow.open(TaskOpenSurface.Pending(clicked))
                                    }
                                },
                                initialPresentationOverride = TasksPresentationState.Default.copy(selectedMode = TasksViewMode.List),
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        assertFalse(has(RaftTasksTaskColumnTags.Column), "nothing open initially")

        // LS-07: clicking a task without discussion opens inside the right column beside the list,
        // with Expand + Close at the header end and NO leading Back arrow.
        clickTaskInList(tasks[0].title)
        assertTrue(has(RaftTasksTaskColumnTags.Column), "pending task opens in right column")
        val root = widthOf(ROOT)
        assertEquals(399f, widthOf(RaftTasksTaskColumnTags.Column), 1.5f, "pending task uses default detail column width")
        assertEquals(root - 400f, widthOf(RaftTasksTaskColumnTags.List), 1.5f, "list stays beside the pending task")
        assertTrue(openCardShows(tasks[0].title), "open pending task card is highlighted in the list")
        onNode(hasText("No discussion in this task yet")).assertExists()
        assertTrue(has(RaftTasksTaskColumnTags.Expand), "header ends with Expand")
        assertTrue(has(RaftTasksTaskColumnTags.Close), "header ends with Close")
        assertTrue(
            onAllNodes(hasContentDescription("Back")).fetchSemanticsNodes().isEmpty(),
            "header in right column must not show a leading Back button",
        )

        // Expand fills the row; first Back restores, second Back closes the pending column.
        clickTagged(RaftTasksTaskColumnTags.Expand)
        assertTrue(expanded)
        assertEquals(root, widthOf(RaftTasksTaskColumnTags.Column), 1.5f, "expanded pending task fills row")
        assertEquals(0f, widthOf(RaftTasksTaskColumnTags.List), 0.5f)

        runOnIdle { assertNotNull(dispatcher).onBackPressed() }
        waitForIdle()
        assertFalse(expanded, "first Back restores Expand")
        assertTrue(has(RaftTasksTaskColumnTags.Column), "pending task remains open after restoring Expand")

        runOnIdle { assertNotNull(dispatcher).onBackPressed() }
        waitForIdle()
        assertFalse(has(RaftTasksTaskColumnTags.Column), "second Back closes the pending column")
        assertEquals(null, flow.surface)
        assertEquals(0, pageBacks)

        // Non-modal: opening task 0 then clicking task 1 replaces task 0 in-place; Close (×) dismisses it.
        clickTaskInList(tasks[0].title)
        assertEquals("task-1", flow.surface?.task?.id?.value)
        clickTaskInList(tasks[1].title)
        assertEquals("task-4", flow.surface?.task?.id?.value)
        assertTrue(openCardShows(tasks[1].title))
        assertFalse(openCardShows(tasks[0].title))
        clickTagged(RaftTasksTaskColumnTags.Close)
        assertEquals(null, flow.surface)
        assertFalse(has(RaftTasksTaskColumnTags.Column))

        // Leaving Tasks / wide layout or switching server closes the pending column cleanly.
        clickTaskInList(tasks[0].title)
        clickTagged(RaftTasksTaskColumnTags.Expand)
        assertTrue(expanded)
        runOnIdle { pendingColumnAllowed = false }
        waitForIdle()
        assertEquals(null, flow.surface, "leaving wide Tasks closes pending surface")
        assertFalse(expanded, "leaving wide Tasks resets expanded")

        runOnIdle { pendingColumnAllowed = true }
        waitForIdle()
        clickTaskInList(tasks[0].title)
        assertEquals("task-1", flow.surface?.task?.id?.value)
        runOnIdle { serverId = "server-2" }
        waitForIdle()
        assertEquals(null, flow.surface, "switching server closes pending surface")
        assertEquals(null, pendingSurfaceServerId, "server switch clears pending server fence")

        // Restoring a live detail column clears any open pending surface cleanly, and clicking a
        // pending task while a live task is open replaces the live task in the same column.
        clickTaskInList(tasks[0].title)
        assertEquals("task-1", flow.surface?.task?.id?.value)
        runOnIdle { liveDetailColumnOpen = true }
        waitForIdle()
        assertEquals(null, flow.surface, "opening a live task clears pending surface")
        onNodeWithTag("task-page-live-task").assertExists()

        clickTaskInList(tasks[1].title)
        assertFalse(liveDetailColumnOpen, "opening a pending task closes the live task column")
        assertEquals("task-4", flow.surface?.task?.id?.value)
        assertTrue(has(RaftTasksTaskColumnTags.Column), "pending task occupies the column")
        onNode(hasText("No discussion in this task yet")).assertExists()
    }

    @Test
    fun pendingTaskModalWindowClearsEnclosingTaskColumnHead() = runRobolectricComposeUiTest {
        val flow = TaskOpenSurfaceFlow()
        flow.open(TaskOpenSurface.Pending(tasks[0]))
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTaskColumnHead provides RaftTaskColumnHead(expanded = false, onToggleExpand = {}, onClose = {}),
            ) {
                RaftDesignSystemTheme {
                    TaskOpenSurfaceHost(flow = flow, onSend = { _, _ -> }, serverId = "server-1")
                }
            }
        }
        waitForIdle()
        assertTrue(
            onAllNodes(hasContentDescription("Back")).fetchSemanticsNodes().isNotEmpty(),
            "full-screen pending task window keeps Back even when opened from inside the Tasks column",
        )
        assertFalse(has(RaftTasksTaskColumnTags.Expand), "modal window must not inherit column Expand")
        assertFalse(has(RaftTasksTaskColumnTags.Close), "modal window must not inherit column Close")
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
