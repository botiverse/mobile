package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus

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
