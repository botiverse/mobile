package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.ServerTaskCounts
import build.raft.app.data.SharedTasksIntent
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.TaskWireDto
import build.raft.app.data.TasksBatchAction
import build.raft.app.data.TasksBatchOutcome
import build.raft.app.data.TasksBatchViewer
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksLaneState
import build.raft.app.data.TasksPageQuery
import build.raft.app.data.TasksPageScope
import build.raft.app.data.TasksQueryState
import build.raft.app.data.TasksUiState
import build.raft.app.data.reduce
import build.raft.app.data.stringDescLocaleType
import build.raft.app.data.tasksCountsKey
import build.raft.app.data.tasksQueryLanes
import build.raft.app.data.tasksQueryScopeFilters
import build.raft.app.data.toTaskSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI tests for Tasks v2 bulk selection & batch actions
 * (`#wg-mobile-sync:#60`, web `TasksSelectionBar.tsx` / `slock #13852`).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w480dp-h844dp-mdpi")
class SharedTasksBulkActionsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
    }

    @Test
    fun longPressEntersSelectionModeTapTogglesRowsSelectAllAndClearExit() = runRobolectricComposeUiTest {
        val rows = sampleBulkTasks()
        val opened = mutableListOf<String>()
        val queryState = bulkAllScopeQueryState(rows)
        val toastHost = RaftToastHostState()

        setContent {
            var uiState by remember {
                mutableStateOf(TasksUiState(loadState = ScreenLoadState.Content(TasksContent(emptyMap(), rows))))
            }
            BulkTestHost(toastHost = toastHost) {
                SharedTasksScreen(
                    state = uiState,
                    onIntent = { intent -> uiState = uiState.applySelectionRouteIntent(intent) },
                    onOpenTask = { opened += it.id.value },
                    timeContext = BulkFixedTimeContext,
                    query = SharedTasksQueryBinding(queryState) {},
                    viewerUserId = "user-viewer",
                    serverRole = "member",
                )
            }
        }
        waitForIdle()

        // Not selecting yet: no floating bar or row checkboxes.
        assertTrue(onAllNodesWithTag(SharedTasksBulkTags.Bar).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithTag(SharedTasksBulkTags.RowCheckbox, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        // Long-press first row -> enters selection mode with 1 selected.
        onAllNodesWithTag(SharedTasksCompactRowTags.Row)[0].performTouchInput { longClick() }
        waitForIdle()

        onNodeWithTag(SharedTasksBulkTags.Bar).assertIsDisplayed()
        onNodeWithText("1 selected").assertIsDisplayed()
        assertEquals(3, onAllNodesWithTag(SharedTasksBulkTags.RowCheckbox, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(opened.isEmpty(), "Long-press must enter selection mode without opening task")

        // Tapping second row toggles selection instead of opening.
        onAllNodesWithTag(SharedTasksCompactRowTags.Row)[1].performClick()
        waitForIdle()
        onNodeWithText("2 selected").assertIsDisplayed()
        assertTrue(opened.isEmpty(), "Tapping a row while selecting must toggle selection, not open task")

        // Tap Select All checkbox -> selects all 3 shown tasks.
        onNodeWithTag(SharedTasksBulkTags.SelectAll).performClick()
        waitForIdle()
        onNodeWithText("All 3 selected").assertIsDisplayed()

        // Tap Clear (x) -> exits selection mode.
        onNodeWithTag(SharedTasksBulkTags.Clear).performClick()
        waitForIdle()
        assertTrue(onAllNodesWithTag(SharedTasksBulkTags.Bar).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithTag(SharedTasksBulkTags.RowCheckbox, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun bulkStatusAndAssigneeActionsExecuteToastAndClearSelection() = runRobolectricComposeUiTest {
        val rows = sampleBulkTasks()
        val queryState = bulkAllScopeQueryState(rows)
        val toastHost = RaftToastHostState()
        val executed = mutableListOf<Pair<TasksBatchAction, List<String>>>()
        val lagoon = MemberProfile(MemberId("agent-lagoon"), "Lagoon", "lagoon", MemberKind.Agent)
        val alex = MemberProfile(MemberId("user-viewer"), "Alex", "alex", MemberKind.Human)

        setContent {
            var uiState by remember {
                mutableStateOf(TasksUiState(loadState = ScreenLoadState.Content(TasksContent(emptyMap(), rows))))
            }
            BulkTestHost(toastHost = toastHost) {
                SharedTasksScreen(
                    state = uiState,
                    onIntent = { intent -> uiState = uiState.applySelectionRouteIntent(intent) },
                    identityOptions = listOf(alex, lagoon),
                    timeContext = BulkFixedTimeContext,
                    query = SharedTasksQueryBinding(queryState) {},
                    viewerUserId = "user-viewer",
                    serverRole = "member",
                    onBatchAction = { action, tasks, _ ->
                        executed += action to tasks.map { it.id.value }
                        val outcome = TasksBatchOutcome(done = tasks.size, skipped = 0, failedIds = emptyList())
                        uiState = uiState.reduce(SharedTasksIntent.BatchActionFinished(outcome.failedIds))
                        outcome
                    },
                )
            }
        }
        waitForIdle()

        // Select two rows and run bulk Status -> Done.
        onAllNodesWithTag(SharedTasksCompactRowTags.Row)[0].performTouchInput { longClick() }
        waitForIdle()
        onAllNodesWithTag(SharedTasksCompactRowTags.Row)[1].performClick()
        waitForIdle()

        onNodeWithTag(SharedTasksBulkTags.Status).performClick()
        waitForIdle()
        onNodeWithTag(SharedTasksBulkTags.statusItem(TaskStatus.Done)).performClick()
        waitForIdle()

        assertEquals(1, executed.size)
        assertEquals(TasksBatchAction.Status(TaskStatus.Done), executed[0].first)
        assertEquals(listOf("task-101", "task-102"), executed[0].second)
        assertEquals("Moved 2 tasks to Done", toastHost.visible.lastOrNull()?.title)
        assertTrue(onAllNodesWithTag(SharedTasksBulkTags.Bar).fetchSemanticsNodes().isEmpty())

        // Re-enter selection mode and run bulk Assignee -> Lagoon.
        onAllNodesWithTag(SharedTasksCompactRowTags.Row)[0].performTouchInput { longClick() }
        waitForIdle()
        onNodeWithTag(SharedTasksBulkTags.Assignee).performClick()
        waitForIdle()
        onNodeWithText("Lagoon").performClick()
        waitForIdle()

        assertEquals(2, executed.size)
        assertEquals(TasksBatchAction.Assignee(lagoon), executed[1].first)
        assertEquals(listOf("task-101"), executed[1].second)
        assertEquals("Assigned 1 task to Lagoon", toastHost.visible.lastOrNull()?.title)
        assertTrue(onAllNodesWithTag(SharedTasksBulkTags.Bar).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun bulkDeletePartialFailureKeepsConfirmDialogAndFailedTaskSelectedUntilRetrySucceeds() = runRobolectricComposeUiTest {
        val rows = sampleBulkTasks()
        val queryState = bulkAllScopeQueryState(rows)
        val toastHost = RaftToastHostState()
        var deleteAttempts = 0

        setContent {
            var uiState by remember {
                mutableStateOf(
                    TasksUiState(
                        loadState = ScreenLoadState.Content(TasksContent(emptyMap(), rows)),
                        selectedTaskIds = setOf("task-101", "task-102"),
                    )
                )
            }
            BulkTestHost(toastHost = toastHost) {
                SharedTasksScreen(
                    state = uiState,
                    onIntent = { intent -> uiState = uiState.applySelectionRouteIntent(intent) },
                    timeContext = BulkFixedTimeContext,
                    query = SharedTasksQueryBinding(queryState) {},
                    viewerUserId = "user-viewer",
                    serverRole = "admin",
                    onBatchAction = { action, tasks, _ ->
                        assertEquals(TasksBatchAction.Delete, action)
                        deleteAttempts += 1
                        val outcome =
                            if (deleteAttempts == 1) {
                                TasksBatchOutcome(
                                    done = 1,
                                    skipped = 1,
                                    failedIds = listOf("task-102"),
                                    firstError = ScreenError(code = "tasks_delete_http_403", retryable = false),
                                )
                            } else {
                                TasksBatchOutcome(done = tasks.size, skipped = 0, failedIds = emptyList())
                            }
                        uiState = uiState.reduce(SharedTasksIntent.BatchActionFinished(outcome.failedIds))
                        outcome
                    },
                )
            }
        }
        waitForIdle()

        onNodeWithTag(SharedTasksBulkTags.Delete).performClick()
        waitForIdle()
        onNodeWithTag(SharedTasksBulkTags.DeleteConfirm).assertIsDisplayed()
        onNodeWithText("Delete 2 tasks?").assertIsDisplayed()

        // First attempt partially fails -> dialog stays open with error banner & 1 remaining task.
        onNodeWithTag(SharedTasksBulkTags.DeleteConfirmAction).performClick()
        waitForIdle()
        onNodeWithTag(SharedTasksBulkTags.DeleteConfirm).assertIsDisplayed()
        onNodeWithTag(SharedTasksBulkTags.DeleteConfirmError).assertIsDisplayed()
        onNodeWithText("1 task wasn't deleted. Try again.").assertIsDisplayed()

        // Retry deletes the remaining task -> dialog closes, toast posted, selection clears.
        onNodeWithTag(SharedTasksBulkTags.DeleteConfirmAction).performClick()
        waitForIdle()
        assertTrue(onAllNodesWithTag(SharedTasksBulkTags.DeleteConfirm).fetchSemanticsNodes().isEmpty())
        assertEquals("Deleted 1 task", toastHost.visible.lastOrNull()?.title)
        assertTrue(onAllNodesWithTag(SharedTasksBulkTags.Bar).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun deleteHiddenWithoutPermissionAndNativeTabBarCoverRestoresOnClear() = runRobolectricComposeUiTest {
        val rows = sampleBulkTasks(creatorId = "user-other")
        val queryState = bulkAllScopeQueryState(rows)
        val toastHost = RaftToastHostState()
        val tabBarBridge = RaftNativeTabBarBridge()
        val publisherToken = Any()
        tabBarBridge.publish(
            token = publisherToken,
            state = RaftNativeTabBarState(items = emptyList(), selectedKey = "tasks", visible = true),
            onSelect = {},
        )

        setContent {
            var uiState by remember {
                mutableStateOf(TasksUiState(loadState = ScreenLoadState.Content(TasksContent(emptyMap(), rows))))
            }
            BulkTestHost(toastHost = toastHost, nativeTabBarBridge = tabBarBridge) {
                SharedTasksScreen(
                    state = uiState,
                    onIntent = { intent -> uiState = uiState.applySelectionRouteIntent(intent) },
                    timeContext = BulkFixedTimeContext,
                    query = SharedTasksQueryBinding(queryState) {},
                    viewerUserId = "user-viewer",
                    serverRole = "member",
                    onBatchAction = { _, _, _ ->
                        val outcome = TasksBatchOutcome(done = 0, skipped = 1, failedIds = emptyList())
                        uiState = uiState.reduce(SharedTasksIntent.BatchActionFinished(outcome.failedIds))
                        outcome
                    },
                )
            }
        }
        waitForIdle()

        assertTrue(tabBarBridge.currentState.visible, "Native tab bar stays visible before selection")

        // Enter selection mode -> native tab bar is covered and Delete button is hidden.
        onAllNodesWithTag(SharedTasksCompactRowTags.Row)[0].performTouchInput { longClick() }
        waitForIdle()
        onNodeWithTag(SharedTasksBulkTags.Bar).assertIsDisplayed()
        assertFalse(tabBarBridge.currentState.visible, "RaftNativeTabBarCover must hide native tab bar while selecting")
        assertTrue(
            onAllNodesWithTag(SharedTasksBulkTags.Delete).fetchSemanticsNodes().isEmpty(),
            "Delete action must be hidden when viewer cannot delete any selected task",
        )

        // All-skipped batch shows error-intent toast and restores native tab bar on clear.
        onNodeWithTag(SharedTasksBulkTags.Follow).performClick()
        waitForIdle()
        assertEquals(RaftToastIntent.Error, toastHost.visible.lastOrNull()?.intent)
        assertTrue(tabBarBridge.currentState.visible, "Native tab bar must restore after selection clears")
    }

    @Composable
    private fun BulkTestHost(
        toastHost: RaftToastHostState,
        nativeTabBarBridge: RaftNativeTabBarBridge? = null,
        content: @Composable () -> Unit,
    ) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
        CompositionLocalProvider(
            LocalActivity provides RaftPageActivity(pager),
            LocalRaftToastHostState provides toastHost,
            LocalRaftNativeTabBarBridge provides nativeTabBarBridge,
        ) {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) { content() }
            }
        }
    }
}

private fun TasksUiState.applySelectionRouteIntent(intent: SharedTasksRouteIntent): TasksUiState =
    when (intent) {
        is SharedTasksRouteIntent.ToggleTaskSelection -> reduce(SharedTasksIntent.ToggleTaskSelection(intent.taskId))
        is SharedTasksRouteIntent.SelectTasks -> reduce(SharedTasksIntent.SelectTasks(intent.taskIds))
        SharedTasksRouteIntent.ClearTaskSelection -> reduce(SharedTasksIntent.ClearTaskSelection)
        is SharedTasksRouteIntent.PruneTaskSelection -> reduce(SharedTasksIntent.PruneTaskSelection(intent.availableTaskIds))
        else -> this
    }

private val BulkFixedTimeContext =
    RaftTimeContext(
        localOffsetMinutes = 0,
        todayEpochDay = raftEpochDayForDate(2026, 11, 20),
        nowEpochMillis = 1_795_176_000_000L,
    )

private fun sampleBulkTasks(creatorId: String = "user-viewer"): List<TaskSummary> =
    listOf(
        bulkTask(101, "Implement bulk selection bar", "todo", "proj-mobile", creatorId = creatorId),
        bulkTask(102, "Wire batch status picker", "in_progress", "proj-mobile", creatorId = creatorId),
        bulkTask(103, "Verify safe area clearance", "todo", "design", creatorId = creatorId),
    )

private fun bulkTask(
    number: Int,
    title: String,
    status: String,
    channelName: String,
    creatorId: String,
): TaskSummary =
    TaskWireDto(
        id = "task-$number",
        messageId = "msg-task-$number",
        channelId = "ch-$channelName",
        channelName = channelName,
        channelType = "channel",
        taskNumber = number,
        title = title,
        status = status,
        createdByType = "user",
        createdById = creatorId,
        createdByName = "Alex",
        conversationChannelId = "conv-$number",
        updatedAt = "2026-11-20T10:00:00Z",
        activityAt = "2026-11-20T10:00:00Z",
    ).toTaskSummary()

private fun bulkAllScopeQueryState(rows: List<TaskSummary>): TasksQueryState {
    val query = TasksPageQuery(scope = TasksPageScope.All)
    val lanes = tasksQueryLanes(query, doneSince = "2026-11-19T12:00:00Z")
    val lane = lanes.single()
    val counts = ServerTaskCounts(total = rows.size, byStatus = rows.groupingBy { it.status }.eachCount())
    return TasksQueryState(
        query = query,
        started = true,
        viewerId = "user-viewer",
        doneSince = "2026-11-19T12:00:00Z",
        lanes = lanes,
        laneStates = mapOf(lane.key to TasksLaneState(ids = rows.map { it.id.value }, loaded = true)),
        tasks = rows.associateBy { it.id.value },
        counts = mapOf(tasksCountsKey(tasksQueryScopeFilters("2026-11-19T12:00:00Z")) to counts),
    )
}
