package build.raft.app

import build.raft.app.components.SlockAnchorPlacement
import build.raft.app.components.SlockAnchoredMetaChip
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.TaskWireDto
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksPresentationState
import build.raft.app.data.TasksUiState
import build.raft.app.data.TasksViewMode
import build.raft.app.data.summaryCounts
import build.raft.app.data.toTaskSummary
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.unit.dp

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

@Composable
internal fun TasksPanelVisualContent() {
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
                                summaryCounts = tasksPanelVisualTasks.summaryCounts(),
                                tasks = tasksPanelVisualTasks
                            )
                        )
                ),
            onIntent = {},
            // React's capture clicks "Show Done" before the shot; only Closed stays
            // collapsed so the done card is visible too (task #330/#353).
            initialPresentationOverride =
                TasksPresentationState.Default.copy(
                    selectedMode = TasksViewMode.List,
                    collapsedStatuses = setOf(TaskStatus.Closed)
                )
        )
    }
}

@Composable
internal fun TasksStatusMenuVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(260.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        // Same trigger chip + SlockBaseMenuOverlay call SharedTasksPage opens on a
        // status chip tap, held statically open with in_progress selected. Anchor
        // floats are root coordinates like RaftUiControlledOpenOverlaysVisualContent
        // (task #330/#353).
        SlockAnchoredMetaChip(
            label = "In Progress",
            iconName = null,
            color = SlockColor.AccentCyan,
            onClick = {},
            modifier = Modifier.widthIn(min = 78.dp),
            trailingIconName = SlockLucideIconName.Pencil,
            borderWidth = SlockStroke.Default,
            anchorPlacement = SlockAnchorPlacement.BottomEnd
        )
        SlockBaseMenuOverlay(
            anchor = Offset(16f, 44f),
            items =
                TaskStatus.entries.map { status ->
                    SlockBaseMenuItem(id = status.name, label = tasksStatusMenuVisualLabel(status))
                },
            selectedId = TaskStatus.InProgress.name,
            uppercaseLabels = false,
            horizontalOffset = 0.dp,
            verticalOffset = 0.dp,
            verticalPlacement = SlockFloatingVerticalPlacement.Below,
            onDismiss = {},
            onSelect = {}
        )
    }
}

// Mirrors the private TaskStatus.label mapping in SharedTasksPage.kt so the menu rows
// read exactly like the product's status menu.
private fun tasksStatusMenuVisualLabel(status: TaskStatus): String =
    when (status) {
        TaskStatus.Todo -> "Todo"
        TaskStatus.InProgress -> "In Progress"
        TaskStatus.InReview -> "In Review"
        TaskStatus.Done -> "Done"
        TaskStatus.Closed -> "Closed"
    }
