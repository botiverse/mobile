package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import build.raft.app.data.ServerTaskBoardFilters
import build.raft.app.data.ServerTaskCounts
import build.raft.app.data.ServerTaskRelation
import build.raft.app.data.TaskWireDto
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksLaneState
import build.raft.app.data.TasksPageQuery
import build.raft.app.data.TasksPageScope
import build.raft.app.data.TasksQueryState
import build.raft.app.data.TasksUiState
import build.raft.app.data.tasksCountsKey
import build.raft.app.data.tasksQueryLanes
import build.raft.app.data.toTaskSummary
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phone Tasks page (`w390dp-h844dp`, light and dark) with the production query toolbar and
 * web-parity compact task rows (`Mine`: `Assigned` + `Started by you`), verifying ~6+ rows fit
 * cleanly on a phone screen with zero truncation and crisp dark-mode borders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class TasksPhoneCompactListScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @BeforeTest
    fun setUp() {
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @AfterTest
    fun tearDown() {
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
    }

    @Test fun light() = capture("tasks.phone.compact-list", RaftAppAppearance.Light)

    @Test fun dark() = capture("tasks.phone.compact-list.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            val queryState = remember { phoneMineQueryState() }
            val allTasks = remember(queryState) { queryState.tasks.values.toList() }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        SharedTasksScreen(
                            state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(emptyMap(), allTasks))),
                            onIntent = {},
                            timeContext = PhoneShotTimeContext,
                            query = SharedTasksQueryBinding(queryState) {},
                        )
                    }
                }
            }
        }
}

private val PhoneShotTimeContext =
    RaftTimeContext(
        localOffsetMinutes = 0,
        todayEpochDay = raftEpochDayForDate(2026, 11, 20),
        nowEpochMillis = 1_795_176_000_000L, // 2026-11-20T12:00:00Z
    )

private fun phoneShotTask(
    number: Int,
    title: String,
    status: String,
    channelName: String,
    assigneeType: String? = null,
    assigneeId: String? = null,
    assigneeName: String? = null,
    updatedAt: String,
    replyCount: Int = 0,
): TaskSummary =
    TaskWireDto(
        id = "phone-task-$number",
        messageId = "msg-phone-task-$number",
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
        conversationChannelId = if (replyCount > 0) "conv-phone-$number" else null,
        conversationReplyCount = replyCount,
        createdAt = "2026-11-18T09:00:00Z",
        updatedAt = updatedAt,
        activityAt = updatedAt,
    ).toTaskSummary(diagnostics = {}) ?: error("fixture task #$number must map")

private fun phoneMineQueryState(): TasksQueryState {
    val assigned =
        listOf(
            phoneShotTask(249, "Phone Tasks list compact rows like web", "in_progress", "wg-mobile-sync", "user", "user-viewer", "Alex", "2026-11-20T11:48:00Z", replyCount = 5),
            phoneShotTask(244, "Verify close button placement on wide panes", "todo", "proj-mobile", "user", "user-viewer", "Alex", "2026-11-20T09:30:00Z"),
        )
    val started =
        listOf(
            phoneShotTask(314, "1.29 mobile web-parity round-2 audit", "in_progress", "proj-mobile", "agent", "agent-lagoon", "Lagoon", "2026-11-20T11:20:00Z", replyCount = 12),
            phoneShotTask(118, "Sync stale task copy across 8 locales", "todo", "i18n", null, null, null, "2026-11-20T06:00:00Z"),
            phoneShotTask(92, "Check dark mode hairline dividers", "todo", "design", "user", "user-maya", "Maya", "2026-11-15T10:00:00Z"),
            phoneShotTask(87, "Cut 1.29 preview build for Android", "done", "release", "agent", "agent-cora", "Cora", "2026-11-20T04:00:00Z", replyCount = 3),
            phoneShotTask(64, "Offline drafts after sync rewrite", "backlog", "proj-apps", null, null, null, "2026-11-18T14:00:00Z"),
        )
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
                assignedLane.key to ServerTaskCounts(statuses = mapOf(TaskStatus.InProgress to 1, TaskStatus.Todo to 1), relations = emptyMap()),
                createdLane.key to ServerTaskCounts(statuses = mapOf(TaskStatus.InProgress to 1, TaskStatus.Todo to 2, TaskStatus.Done to 1, TaskStatus.Backlog to 1), relations = emptyMap()),
                fromMsgLane.key to ServerTaskCounts(statuses = emptyMap(), relations = emptyMap()),
                tasksCountsKey(ServerTaskBoardFilters()) to ServerTaskCounts(statuses = mapOf(TaskStatus.InProgress to 4, TaskStatus.Todo to 5, TaskStatus.Done to 2, TaskStatus.Backlog to 1), relations = emptyMap()),
            ),
    )
}
