package build.raft.app

import build.raft.app.data.AppLocale
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.TaskWireDto
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksPresentationState
import build.raft.app.data.TasksUiState
import build.raft.app.data.TasksViewMode
import build.raft.app.data.stringDescLocaleType
import build.raft.app.data.summaryCounts
import build.raft.app.data.toTaskSummary
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.resources.StringDesc

private const val TasksPanelChineseLocalizationFixture = "zh-cn-localization"

// Canonical /tasks/server rows for components.tasks.panel.states — same rows as
// packages/visual-testing/shared/tasksFixture.json in the React repo, built through the
// real wire mapping (TaskWireDto.toTaskSummary) so the fixture renders exactly what the
// product tasks list renders for this payload (task #330/#353).
private fun tasksPanelVisualTask(
    taskNumber: Int,
    title: String,
    status: String,
    createdAt: String,
    updatedAt: String,
    description: String? = null,
    claimedByType: String? = null,
    claimedById: String? = null,
    claimedByName: String? = null,
    claimedAt: String? = null,
    completedAt: String? = null,
    createdByType: String = "user",
    createdById: String = VisualFixtureData.Humans.Owner.ID,
    createdByName: String = VisualFixtureData.Humans.Owner.NAME
): TaskSummary =
    TaskWireDto(
        id = "msg-task-visual-$taskNumber",
        messageId = "msg-task-visual-$taskNumber",
        channelId = VisualFixtureData.Channels.Design.ID,
        channelName = VisualFixtureData.Channels.Design.NAME,
        channelType = VisualFixtureData.Channels.Design.TYPE,
        taskNumber = taskNumber,
        title = title,
        description = description,
        status = status,
        createdByType = createdByType,
        createdById = createdById,
        createdByName = createdByName,
        claimedByType = claimedByType,
        claimedById = claimedById,
        claimedByName = claimedByName,
        claimedAt = claimedAt,
        completedAt = completedAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    ).toTaskSummary()
        ?: error("visual fixture task #$taskNumber must map to a valid TaskSummary (check status literal)")

private val tasksPanelVisualTasks: List<TaskSummary> =
    listOf(
        tasksPanelVisualTask(
            taskNumber = 214,
            title = "Align the tabbar capture crops between React and Android",
            status = "todo",
            description = "Both providers must wrap exactly the tabbar at width 390.",
            createdAt = VisualFixtureData.Times.RECENT_ACTIVITY_AT_ISO,
            updatedAt = VisualFixtureData.Times.RECENT_ACTIVITY_AT_ISO
        ),
        tasksPanelVisualTask(
            taskNumber = 212,
            title = "Fill the React fixture gaps before the Android diff",
            status = "in_progress",
            claimedByType = "agent",
            claimedById = VisualFixtureData.Agents.Cindy.ID,
            claimedByName = VisualFixtureData.Agents.Cindy.DISPLAY_NAME,
            claimedAt = VisualFixtureData.Times.THREAD_MESSAGE_AT_ISO,
            createdAt = VisualFixtureData.Times.ENTITY_CREATED_AT_ISO,
            updatedAt = VisualFixtureData.Times.THREAD_MESSAGE_AT_ISO
        ),
        tasksPanelVisualTask(
            taskNumber = 210,
            title = "Review the thread header strip baselines",
            status = "in_review",
            claimedByType = "agent",
            claimedById = VisualFixtureData.Agents.ProductUx.ID,
            claimedByName = VisualFixtureData.Agents.ProductUx.DISPLAY_NAME,
            claimedAt = VisualFixtureData.Times.RECENT_ACTIVITY_AT_ISO,
            createdByType = "agent",
            createdById = VisualFixtureData.Agents.Cindy.ID,
            createdByName = VisualFixtureData.Agents.Cindy.DISPLAY_NAME,
            createdAt = VisualFixtureData.Times.ENTITY_CREATED_AT_ISO,
            updatedAt = VisualFixtureData.Times.THREAD_REPLY_AT_ISO
        ),
        tasksPanelVisualTask(
            taskNumber = 208,
            title = "Publish the visual parity report",
            status = "done",
            claimedByType = "agent",
            claimedById = VisualFixtureData.Agents.Cindy.ID,
            claimedByName = VisualFixtureData.Agents.Cindy.DISPLAY_NAME,
            claimedAt = VisualFixtureData.Times.RECENT_ACTIVITY_AT_ISO,
            completedAt = VisualFixtureData.Times.THREAD_REPLY_AT_ISO,
            createdAt = VisualFixtureData.Times.ENTITY_CREATED_AT_ISO,
            updatedAt = VisualFixtureData.Times.THREAD_REPLY_AT_ISO
        )
    )

private const val TasksPanelStatusMenuRecycleFixture = "status-menu-recycle"

// Real SharedTasksPanel interaction fixture for the LazyColumn recycling regression in
// task #525. The first viewport is intentionally filled with Todo cards; scrolling into
// the In Progress group forces the same lazy slots/pointer inputs to bind new task ids,
// statuses, click callbacks, and status-chip geometry.
private val tasksPanelStatusMenuRecycleTasks: List<TaskSummary> =
    (320 downTo 309).map { taskNumber ->
        tasksPanelVisualTask(
            taskNumber = taskNumber,
            title = "Todo recycled slot $taskNumber",
            status = "todo",
            createdAt = VisualFixtureData.Times.ENTITY_CREATED_AT_ISO,
            updatedAt = VisualFixtureData.Times.RECENT_ACTIVITY_AT_ISO
        )
    } +
        (308 downTo 293).map { taskNumber ->
            tasksPanelVisualTask(
                taskNumber = taskNumber,
                title = "In progress visible row $taskNumber",
                status = "in_progress",
                claimedByType = "agent",
                claimedById = VisualFixtureData.Agents.Cindy.ID,
                claimedByName = VisualFixtureData.Agents.Cindy.DISPLAY_NAME,
                claimedAt = VisualFixtureData.Times.THREAD_MESSAGE_AT_ISO,
                createdAt = VisualFixtureData.Times.ENTITY_CREATED_AT_ISO,
                updatedAt = VisualFixtureData.Times.RECENT_ACTIVITY_AT_ISO
            )
        }

@Composable
internal fun TasksPanelVisualContent(
    fixture: String = "",
    // PROTOTYPE (native nav): the whole screen (the host draws the tab bar).
    protoFullScreen: Boolean = false,
) {
    val chineseLocalizationFixture = fixture == TasksPanelChineseLocalizationFixture
    val previousLocaleType = remember(chineseLocalizationFixture) { StringDesc.localeType }
    if (chineseLocalizationFixture) {
        StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
    }
    DisposableEffect(chineseLocalizationFixture) {
        onDispose {
            if (chineseLocalizationFixture) {
                StringDesc.localeType = previousLocaleType
            }
        }
    }
    val tasks =
        if (fixture == TasksPanelStatusMenuRecycleFixture) {
            tasksPanelStatusMenuRecycleTasks
        } else {
            tasksPanelVisualTasks
        }
    Box(
        modifier =
            (if (protoFullScreen) Modifier.fillMaxSize() else Modifier.width(390.dp).height(844.dp))
                .background(RaftColor.SurfaceWhite)
    ) {
        SharedTasksPanel(
            state =
                TasksUiState(
                    loadState =
                        ScreenLoadState.Content(
                            TasksContent(
                                summaryCounts = tasks.summaryCounts(),
                                tasks = tasks
                            )
                        )
                ),
            onIntent = {},
            // React's capture clicks "Show Done" before the shot; only Closed stays
            // collapsed so the done card is visible too (task #330/#353).
            initialPresentationOverride =
                TasksPresentationState.Default.copy(
                    selectedMode = TasksViewMode.List,
                    collapsedStatuses =
                        if (fixture == TasksPanelStatusMenuRecycleFixture) {
                            setOf(TaskStatus.InReview, TaskStatus.Done, TaskStatus.Closed)
                        } else {
                            setOf(TaskStatus.Closed)
                        }
                ),
            // #373: deterministic clock so the card timestamps render as relative
            // labels ("Just now"/"Yesterday"/…) in the capture, matching the fixed
            // fixture "now" the other visual pages use.
            timeContext =
                RaftTimeContext(
                    localOffsetMinutes = VisualFixtureData.Locale.UTC_OFFSET_MINUTES,
                    todayEpochDay = VisualFixtureData.Locale.TODAY_EPOCH_DAY.toLong(),
                    nowEpochMillis = VisualFixtureData.Locale.NOW_EPOCH_MILLIS,
                    timeFormat = VisualFixtureData.Locale.TIME_FORMAT
                )
        )
    }
}

@Composable
internal fun TasksStatusMenuVisualContent() {
    // web: InlineBadgeEditor held open with in_progress selected — the TaskCard status chip
    // at (16, 18)dp (the case wrapper's 16dp padding + the chip's `mt-0.5`), and its
    // menu portalled `top: trigger.bottom + 4`, left-aligned with the trigger
    // (`dropdownAlign="left"`). The menu panel is pinned in place here instead of floating
    // in RaftTaskStatusMenuOverlay's modal window, so the capture does not depend on where
    // the harness composites dialog windows; it is the same panel that overlay draws.
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(260.dp)
                .background(RaftColor.SurfaceWhite)
    ) {
        Column(modifier = Modifier.offset(x = 16.dp, y = 18.dp)) {
            RaftTaskStatusChip(status = TaskStatus.InProgress, onClick = {})
            Spacer(Modifier.height(4.dp))
            RaftTaskStatusMenuPanel(
                items =
                    TaskStatus.entries.map { status ->
                        RaftBaseMenuItem(id = status.name, label = status.localizedLabel())
                    },
                selectedId = TaskStatus.InProgress.name,
                onSelect = {}
            )
        }
    }
}
