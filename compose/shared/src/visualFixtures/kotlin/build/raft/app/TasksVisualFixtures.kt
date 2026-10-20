package build.raft.app

import build.raft.app.components.SlockAnchorPlacement
import build.raft.app.components.SlockAnchoredMetaChip
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
import build.raft.app.model.SlockTimeContext
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

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
internal fun TasksPanelVisualContent(fixture: String = "") {
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
            Modifier
                .width(390.dp)
                .height(844.dp)
                .background(SlockColor.SurfaceWhite)
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
                SlockTimeContext(
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
    // No container padding: SlockAnchoredPopover clamps content to an 8dp viewport
    // gap inside its host, so a padded host used to shove the menu to the host's
    // top-left (+24px/-66px vs react @3x). The chip is offset instead and the menu
    // anchor is the chip's bottom-left in root PIXELS (SlockFloatingOverlay works in
    // px; react's chip box is x 16dp, y 18..42dp and the menu opens 4dp below it at
    // (16, 46)dp — task #370).
    val anchorRef = remember { SlockLiveRootAnchorRef(identity = "tasks_status_menu_fixture") }
    var anchor by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(260.dp)
                .background(SlockColor.SurfaceWhite)
    ) {
        // Same trigger chip + SlockTaskStatusMenuOverlay call SharedTasksPage opens on a
        // status chip tap, held statically open with in_progress selected. Text
        // params mirror the product status chip (SharedTasksPage task card) and the
        // box is pinned to react's measured chip border-box, 98x24dp at (16,18)dp —
        // the 10sp/20dp-min defaults rendered an 80x20dp chip (task #370).
        Box(
            modifier =
                Modifier
                    .offset(x = 16.dp, y = 18.dp)
                    .width(98.dp)
                    .height(24.dp)
                    .slockCaptureLiveRootAnchor(anchorRef) {
                        anchor = anchorRef.resolve(SlockPopoverLocalPoint.BottomEnd)
                    }
        ) {
            SlockAnchoredMetaChip(
                label = TaskStatus.InProgress.localizedLabel(),
                iconName = null,
                color = SlockColor.AccentCyan,
                onClick = {},
                modifier = Modifier.fillMaxSize(),
                trailingIconName = SlockLucideIconName.Pencil,
                minHeight = 22.dp,
                horizontalPadding = 8.dp,
                labelFontSize = 12.sp,
                labelLineHeight = 14.sp,
                borderWidth = SlockStroke.Default,
                anchorPlacement = SlockAnchorPlacement.BottomEnd
            )
        }
        anchor?.let { resolvedAnchor ->
            SlockTaskStatusMenuOverlay(
                anchor = resolvedAnchor,
                items =
                    TaskStatus.entries.map { status ->
                        SlockBaseMenuItem(id = status.name, label = status.localizedLabel())
                    },
                selectedId = TaskStatus.InProgress.name,
                onDismiss = {},
                onSelect = {}
            )
        }
    }
}
