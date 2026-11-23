package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
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
import build.raft.app.data.ServerTaskBoardFilters
import build.raft.app.data.ServerTaskCounts
import build.raft.app.data.ServerTaskRelation
import build.raft.app.data.TaskWireDto
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksLaneState
import build.raft.app.data.TasksPageQuery
import build.raft.app.data.TasksPageScope
import build.raft.app.data.TasksPresentationState
import build.raft.app.data.TasksQueryState
import build.raft.app.data.TasksUiState
import build.raft.app.data.TasksViewMode
import build.raft.app.data.stringDescLocaleType
import build.raft.app.data.tasksCountsKey
import build.raft.app.data.tasksQueryLanes
import build.raft.app.data.toTaskSummary
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phone Tasks list web-parity (#wg-mobile-sync:#249):
 * - Compact rows (title line + one concise meta line: `#channel`, icon-only status chip,
 *   assignee/Unassigned, short relative time) fit >= 6 tasks per 390x844 phone screen.
 * - Mine scope hides assignee on Assigned rows and shows assignee/Unassigned on Started rows,
 *   with section headings and totals aligned to web `TasksListView`.
 * - Wide containers (>= 448dp) render `#channel:#N` combined leading ref and full status badge.
 * - Tapping any compact row opens the task.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SharedTasksCompactListUiTest {
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
    fun phoneAllListFitsAtLeastSixCompactRowsAndOpensOnTap() = runRobolectricComposeUiTest {
        val opened = mutableListOf<String>()
        val rows = sampleAllTasks()
        val queryState = allScopeQueryState(rows)
        setContent {
            Host {
                SharedTasksScreen(
                    state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(emptyMap(), rows))),
                    onIntent = {},
                    onOpenTask = { opened += it.id.value },
                    timeContext = FixedTimeContext,
                    query = SharedTasksQueryBinding(queryState) {},
                )
            }
        }
        waitForIdle()

        val visibleRows =
            onAllNodesWithTag(SharedTasksCompactRowTags.Row)
                .fetchSemanticsNodes()
                .filter { it.boundsInRoot.bottom <= 844f * 2f + 4f && it.boundsInRoot.top >= 0f }
        assertTrue(
            visibleRows.size >= 6,
            "Phone 390x844 Tasks list must fit at least 6 compact rows (saw ${visibleRows.size})",
        )

        // Narrow row anatomy: bare #N leading ref, #channel in meta, icon-only status chip with
        // accessible label, short relative time, and no bulky card dropdown controls.
        onNodeWithText("#101").assertIsDisplayed()
        onAllNodes(hasTestTag(SharedTasksCompactRowTags.Channel) and hasText("#proj-mobile"), useUnmergedTree = true).fetchSemanticsNodes().let {
            assertTrue(it.isNotEmpty(), "narrow phone row shows #channel in the meta line")
        }
        onAllNodes(hasTestTag(SharedTasksCompactRowTags.Status) and hasContentDescription("In progress"), useUnmergedTree = true).fetchSemanticsNodes().let {
            assertTrue(it.isNotEmpty(), "icon-only status chip exposes localized status to screen readers")
        }

        // Tapping a compact row opens the task conversation.
        onNodeWithText(rows[0].title).performClick()
        waitForIdle()
        assertEquals(listOf(rows[0].id.value), opened)
    }

    @Test
    fun phoneMineSectionsHideAssigneeInAssignedAndShowInStarted() = runRobolectricComposeUiTest {
        val assigned = listOf(compactTask(201, "Review iOS share extension", "in_progress", "proj-mobile", assigneeId = "user-viewer", assigneeName = "Alex", assigneeType = "user"))
        val started = listOf(compactTask(202, "Audit desktop pane grid headers", "todo", "design", assigneeId = "agent-lagoon", assigneeName = "Lagoon", assigneeType = "agent"))
        val queryState = mineScopeQueryState(assigned, started)
        setContent {
            Host {
                SharedTasksScreen(
                    state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(emptyMap(), assigned + started))),
                    onIntent = {},
                    timeContext = FixedTimeContext,
                    query = SharedTasksQueryBinding(queryState) {},
                )
            }
        }
        waitForIdle()

        onNodeWithTag("tasks-page-section-assigned").assertIsDisplayed()
        onNodeWithTag("tasks-page-section-started").assertIsDisplayed()
        onNodeWithText(assigned[0].title).assertIsDisplayed()
        onNodeWithText(started[0].title).assertIsDisplayed()
        // Assigned section hides assignee; Started section shows Lagoon.
        val assigneeNodes = onAllNodesWithTag(SharedTasksCompactRowTags.Assignee, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, assigneeNodes.size, "only the Started by you row displays an assignee")
        onNodeWithText("Lagoon").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w900dp-h800dp-mdpi")
    fun wideListUsesCombinedConversationRefAndFullStatusBadge() = runRobolectricComposeUiTest {
        val rows = sampleAllTasks().take(3)
        val queryState = allScopeQueryState(rows)
        setContent {
            Host {
                Box(Modifier.width(900.dp).fillMaxSize()) {
                    SharedTasksPanel(
                        state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(emptyMap(), rows))),
                        onIntent = {},
                        timeContext = FixedTimeContext,
                        query = SharedTasksQueryBinding(queryState) {},
                        initialPresentationOverride = TasksPresentationState.Default.copy(selectedMode = TasksViewMode.List),
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithText("#proj-mobile:#101").assertIsDisplayed()
        assertTrue(
            onAllNodesWithTag(SharedTasksCompactRowTags.Channel, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
            "wide rows put #channel into the leading #channel:#N ref instead of repeating it in meta",
        )
        // Full status badge label is visible on wide screens.
        onAllNodes(hasAnyAncestor(hasTestTag(SharedTasksCompactRowTags.Status)) and hasText("In progress"), useUnmergedTree = true).fetchSemanticsNodes().let {
            assertTrue(it.isNotEmpty(), "wide rows render the full read-only status badge with text")
        }
        val rowBounds = onAllNodesWithTag(SharedTasksCompactRowTags.Row)[0].getUnclippedBoundsInRoot()
        assertTrue(
            rowBounds.bottom - rowBounds.top <= 56.dp,
            "wide single-line compact row stays compact (height=${rowBounds.bottom - rowBounds.top})",
        )
    }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) { content() }
            }
        }
    }
}

private val FixedTimeContext =
    RaftTimeContext(
        localOffsetMinutes = 0,
        todayEpochDay = raftEpochDayForDate(2026, 11, 20),
        nowEpochMillis = 1_795_176_000_000L, // 2026-11-20T12:00:00Z
    )

private fun compactTask(
    number: Int,
    title: String,
    status: String,
    channelName: String,
    assigneeType: String? = null,
    assigneeId: String? = null,
    assigneeName: String? = null,
    updatedAt: String = "2026-11-20T10:00:00Z",
    replyCount: Int = 0,
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
        createdById = "user-viewer",
        createdByName = "Alex",
        claimedByType = assigneeType,
        claimedById = assigneeId,
        claimedByName = assigneeName,
        conversationChannelId = if (replyCount > 0) "conv-$number" else null,
        conversationReplyCount = replyCount,
        createdAt = "2026-11-19T09:00:00Z",
        updatedAt = updatedAt,
        activityAt = updatedAt,
    ).toTaskSummary(diagnostics = {}) ?: error("fixture task #$number must map")

private fun sampleAllTasks(): List<TaskSummary> =
    listOf(
        compactTask(101, "Compact phone Tasks list rows", "in_progress", "proj-mobile", "agent", "agent-lagoon", "Lagoon", "2026-11-20T11:45:00Z", replyCount = 4),
        compactTask(102, "Align toolbar active sort indicator", "todo", "proj-mobile", null, null, null, "2026-11-20T10:15:00Z"),
        compactTask(103, "Verify iOS keyboard dock spacing", "in_progress", "design", "user", "user-maya", "Maya", "2026-11-20T09:00:00Z", replyCount = 2),
        compactTask(104, "Migrate push token refresh", "todo", "backend", "agent", "agent-cora", "Cora", "2026-11-19T12:00:00Z"),
        compactTask(105, "Check stale warning ink on 4d tasks", "todo", "triage", null, null, null, "2026-11-15T08:00:00Z"),
        compactTask(106, "Update Japanese glossary strings", "done", "i18n", "user", "user-ken", "Ken", "2026-11-19T18:00:00Z"),
        compactTask(107, "Audit dark mode divider contrast", "backlog", "design", null, null, null, "2026-11-18T14:00:00Z"),
        compactTask(108, "Profile cold-start timeline rendering", "in_progress", "perf", "agent", "agent-lagoon", "Lagoon", "2026-11-20T11:00:00Z", replyCount = 1),
    )

private fun allScopeQueryState(rows: List<TaskSummary>): TasksQueryState {
    val query = TasksPageQuery(scope = TasksPageScope.All)
    val lanes = tasksQueryLanes(query, doneSince = "2026-11-19T12:00:00Z")
    val lane = lanes.single()
    val counts = ServerTaskCounts(statuses = mapOf(TaskStatus.InProgress to 3, TaskStatus.Todo to 3, TaskStatus.Done to 1, TaskStatus.Backlog to 1), relations = emptyMap())
    return TasksQueryState(
        query = query,
        started = true,
        viewerId = "user-viewer",
        doneSince = "2026-11-19T12:00:00Z",
        lanes = lanes,
        laneStates =
            mapOf(
                lane.key to
                    TasksLaneState(
                        ids = rows.map { it.id.value },
                        keys = rows.associate { it.id.value to (it.activityAt ?: it.updatedAtLabel) },
                        loaded = true,
                    ),
            ),
        tasks = rows.associateBy { it.id.value },
        counts = mapOf(tasksCountsKey(ServerTaskBoardFilters()) to counts),
    )
}

private fun mineScopeQueryState(
    assigned: List<TaskSummary>,
    started: List<TaskSummary>,
): TasksQueryState {
    val query = TasksPageQuery(scope = TasksPageScope.Mine)
    val doneSince = "2026-11-19T12:00:00Z"
    val lanes = tasksQueryLanes(query, doneSince = doneSince)
    val assignedLane = lanes.first { it.filters.relation == ServerTaskRelation.AssignedToMe }
    val createdLane = lanes.first { it.filters.relation == ServerTaskRelation.CreatedByMe }
    val fromMsgLane = lanes.first { it.filters.relation == ServerTaskRelation.FromMyMessage }
    val all = assigned + started
    return TasksQueryState(
        query = query,
        started = true,
        viewerId = "user-viewer",
        doneSince = doneSince,
        lanes = lanes,
        laneStates =
            mapOf(
                assignedLane.key to TasksLaneState(ids = assigned.map { it.id.value }, keys = assigned.associate { it.id.value to (it.activityAt ?: it.updatedAtLabel) }, loaded = true),
                createdLane.key to TasksLaneState(ids = started.map { it.id.value }, keys = started.associate { it.id.value to (it.activityAt ?: it.updatedAtLabel) }, loaded = true),
                fromMsgLane.key to TasksLaneState(loaded = true),
            ),
        tasks = all.associateBy { it.id.value },
        counts =
            mapOf(
                assignedLane.key to ServerTaskCounts(statuses = mapOf(TaskStatus.InProgress to assigned.size), relations = emptyMap()),
                createdLane.key to ServerTaskCounts(statuses = mapOf(TaskStatus.Todo to started.size), relations = emptyMap()),
                fromMsgLane.key to ServerTaskCounts(statuses = emptyMap(), relations = emptyMap()),
                tasksCountsKey(ServerTaskBoardFilters()) to ServerTaskCounts(statuses = mapOf(TaskStatus.InProgress to all.size), relations = emptyMap()),
            ),
    )
}
