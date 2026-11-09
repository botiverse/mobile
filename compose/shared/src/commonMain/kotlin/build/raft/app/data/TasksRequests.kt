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
    val status: TaskStatus,
    /** A move into Backlog: what would bring the task back (slock #12962). Blank is no reason. */
    val reason: String? = null
)

data class TasksCreateRequest(
    val channelId: ChannelId,
    val titles: List<String>,
    /** Task v2: the status every created task starts in (web `TASK_CREATE_STATUSES`); null = the server's default. */
    val status: TaskStatus? = null
)
