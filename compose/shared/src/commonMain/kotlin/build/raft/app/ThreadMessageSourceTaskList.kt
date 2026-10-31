package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import build.raft.app.model.MemberKind
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftTaskCompactAssignee
import build.raft.designsystem.components.RaftTaskCompactList
import build.raft.designsystem.components.RaftTaskCompactRow
import build.raft.designsystem.theme.RaftTheme

/**
 * Task v2: the tasks created from a message, under it (slock web `TaskChipList`, variant
 * `boxed`): one row per task with its number, title, conversation size, assignee (avatar and
 * name, an agent's avatar with its presence light, the reader as "You") and status. Status and
 * assignee follow the shared task projection, so `task:*` frames update the rows live. Tapping a
 * row opens the task ([onOpenTask] with its id).
 */
@Composable
internal fun ThreadMessageSourceTaskList(
    tasks: List<ThreadMessageSourceTaskItem>,
    memberAvatarResolver: RaftMemberAvatarResolver,
    agentStatusResolver: RaftAgentStatusResolver,
    onOpenTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tasks.isEmpty()) return
    RaftTaskCompactList(modifier) {
        tasks.forEachIndexed { index, task ->
            key(task.taskId) {
                val number = task.taskNumber?.toString().orEmpty()
                val replyCountDescription =
                    if (task.replyCount == 1) {
                        stringResource(Res.string.tasks_source_list_reply_count_one)
                    } else {
                        stringResource(Res.string.tasks_source_list_reply_count_many, task.replyCount)
                    }
                RaftTaskCompactRow(
                    number = if (number.isEmpty()) "" else "#$number",
                    title = task.title,
                    onClick = { onOpenTask(task.taskId) },
                    status = task.status.raftTaskStatus(),
                    statusText = task.status.localizedLabel(),
                    assignee =
                        task.assigneeId?.let {
                            { ThreadMessageSourceTaskAssignee(task, memberAvatarResolver, agentStatusResolver) }
                        },
                    replyCount = task.replyCount,
                    replyCountDescription = replyCountDescription,
                    divider = index > 0,
                    contentDescription =
                        if (number.isEmpty()) {
                            stringResource(Res.string.tasks_source_list_open_no_number, task.title)
                        } else {
                            stringResource(Res.string.tasks_source_list_open, number, task.title)
                        },
                )
            }
        }
    }
}

@Composable
private fun ThreadMessageSourceTaskAssignee(
    task: ThreadMessageSourceTaskItem,
    memberAvatarResolver: RaftMemberAvatarResolver,
    agentStatusResolver: RaftAgentStatusResolver,
) {
    val name = if (task.assigneeIsViewer) stringResource(Res.string.thread_author_you) else task.assigneeName.orEmpty()
    val agentStatus =
        if (task.assigneeKind == MemberKind.Agent) {
            rememberRaftAgentStatusIndicatorState(agentStatusResolver, task.assigneeId)
        } else {
            null
        }
    val avatarSize = RaftAvatarSize.Xs.edge
    RaftTaskCompactAssignee(
        name = name,
        viewer = task.assigneeIsViewer,
        avatar = {
            RaftAvatarWithStatus(
                statusColor = agentStatus?.color,
                avatarSize = avatarSize,
                statusPulsing = agentStatus?.pulsing == true,
                cutoutColor = RaftTheme.colors.layerCard,
            ) {
                RaftMemberAvatar(
                    identity = RaftMemberAvatarIdentity(task.assigneeKind, task.assigneeId, serverId = null),
                    label = task.assigneeName.orEmpty(),
                    avatarSize = avatarSize,
                    resolver = memberAvatarResolver,
                )
            }
        },
    )
}
