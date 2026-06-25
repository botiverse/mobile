package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus

data class TasksLoadRequest(
    val filter: TaskFilter = TaskFilter(),
    val priority: DataRequestPriority = DataRequestPriority.Visible
)

data class TasksStatusUpdateRequest(
    val taskId: TaskId,
    val status: TaskStatus
)

data class TasksCreateRequest(
    val channelId: ChannelId,
    val titles: List<String>
)
