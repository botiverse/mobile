package build.raft.app.thread

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.members.LocalRaftJointActorSurface
import build.raft.app.RaftAgentStatusResolver
import build.raft.app.RaftAvatarWithStatus
import build.raft.app.members.RaftMemberAvatar
import build.raft.app.members.RaftMemberAvatarIdentity
import build.raft.app.members.RaftMemberAvatarResolver
import build.raft.app.members.localizedLabel
import build.raft.app.data.ResolvedActor
import build.raft.app.localizedLabel
import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import build.raft.app.raftTaskStatus
import build.raft.app.rememberRaftAgentStatusIndicatorState
import build.raft.app.members.rememberRaftJointActor
import build.raft.app.resources.*
import build.raft.app.thread.localizedLabel
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftTaskCompactAssignee
import build.raft.designsystem.components.RaftTaskCompactList
import build.raft.designsystem.components.RaftTaskCompactRow
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

internal const val ThreadMessageSourceTaskDoneFoldTestTag = "message-task-chip-done-fold"
private const val ThreadMessageSourceTaskDoneFoldChevronMillis = 160
// not a token: web `min-h-11` compact row height matching RaftTaskCompactRow
private val ThreadMessageSourceTaskDoneFoldMinHeight = 44.dp

/** Partition of a message's tasks into open and completed (Done / Closed) lists (web `taskDoneFold.ts`). */
internal data class FoldedCompletedTasks<T>(
    val open: List<T>,
    val completed: List<T>,
)

private val ThreadTaskCompletedStatuses: Set<TaskStatus> = setOf(TaskStatus.Done, TaskStatus.Closed)

internal fun isThreadTaskCompleted(status: TaskStatus): Boolean = status in ThreadTaskCompletedStatuses

/**
 * Web `foldCompletedTasks` (`taskDoneFold.ts`): open tasks (in their order), then Done and Closed
 * behind one expandable row. Returns null when nothing would fold or nothing would remain open
 * (all tasks completed, all tasks open, or empty).
 */
internal fun <T> foldCompletedTasks(
    tasks: List<T>,
    statusOf: (T) -> TaskStatus,
): FoldedCompletedTasks<T>? {
    val open = mutableListOf<T>()
    val completed = mutableListOf<T>()
    tasks.forEach { task ->
        if (isThreadTaskCompleted(statusOf(task))) completed += task else open += task
    }
    return if (open.isNotEmpty() && completed.isNotEmpty()) FoldedCompletedTasks(open, completed) else null
}

internal fun foldCompletedTasks(
    tasks: List<ThreadMessageSourceTaskItem>,
): FoldedCompletedTasks<ThreadMessageSourceTaskItem>? =
    foldCompletedTasks(tasks, ThreadMessageSourceTaskItem::status)

/**
 * Task v2: the tasks created from a message, under it (slock web `TaskChipList`, variant
 * `boxed`): one row per task with its number, title, conversation size, assignee (avatar and
 * name, an agent's avatar with its presence light, the reader as "You") and status. Status and
 * assignee follow the shared task projection, so `task:*` frames update the rows live. Tapping a
 * row opens the task ([onOpenTask] with its id). When [foldDone] is true (a thread's first
 * message and its pull-down), Done and Closed tasks fold behind one expandable row while any
 * task remains open.
 */
@Composable
internal fun ThreadMessageSourceTaskList(
    tasks: List<ThreadMessageSourceTaskItem>,
    memberAvatarResolver: RaftMemberAvatarResolver,
    agentStatusResolver: RaftAgentStatusResolver,
    onOpenTask: (String) -> Unit,
    modifier: Modifier = Modifier,
    foldDone: Boolean = false,
) {
    if (tasks.isEmpty()) return
    var doneOpen by remember(foldDone) { mutableStateOf(false) }
    val folded = remember(foldDone, tasks) { if (foldDone) foldCompletedTasks(tasks) else null }
    val primaryTasks = folded?.open ?: tasks
    RaftTaskCompactList(modifier) {
        primaryTasks.forEachIndexed { index, task ->
            ThreadMessageSourceTaskListRow(
                task = task,
                divider = index > 0,
                memberAvatarResolver = memberAvatarResolver,
                agentStatusResolver = agentStatusResolver,
                onOpenTask = onOpenTask,
            )
        }
        if (folded != null) {
            ThreadMessageSourceTaskDoneFoldRow(
                count = folded.completed.size,
                open = doneOpen,
                onToggle = { doneOpen = !doneOpen },
            )
            if (doneOpen) {
                folded.completed.forEach { task ->
                    ThreadMessageSourceTaskListRow(
                        task = task,
                        divider = true,
                        memberAvatarResolver = memberAvatarResolver,
                        agentStatusResolver = agentStatusResolver,
                        onOpenTask = onOpenTask,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadMessageSourceTaskListRow(
    task: ThreadMessageSourceTaskItem,
    divider: Boolean,
    memberAvatarResolver: RaftMemberAvatarResolver,
    agentStatusResolver: RaftAgentStatusResolver,
    onOpenTask: (String) -> Unit,
) {
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
            divider = divider,
            contentDescription =
                if (number.isEmpty()) {
                    stringResource(Res.string.tasks_source_list_open_no_number, task.title)
                } else {
                    stringResource(Res.string.tasks_source_list_open, number, task.title)
                },
        )
    }
}

/** Expandable row holding completed (Done / Closed) tasks in a thread's source task list (web `TaskDoneFoldRow`). */
@Composable
private fun ThreadMessageSourceTaskDoneFoldRow(
    count: Int,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val reducedMotion = rememberRaftReducedMotion()
    val chevronTurn by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(ThreadMessageSourceTaskDoneFoldChevronMillis),
        label = "task-done-fold-chevron",
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ThreadMessageSourceTaskDoneFoldMinHeight)
                .drawBehind {
                    val stroke = RaftStroke.Hairline.toPx()
                    val y = stroke / 2f
                    drawLine(colors.lineHairline, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
                }
                .raftClickable(onClick = onToggle, role = Role.Button, tier = RaftHoverTier.Subtle)
                .semantics(mergeDescendants = true) {
                    if (open) {
                        collapse {
                            onToggle()
                            true
                        }
                    } else {
                        expand {
                            onToggle()
                            true
                        }
                    }
                }
                .testTag(ThreadMessageSourceTaskDoneFoldTestTag)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftGlyphIcon(
            glyph = RaftGlyph.ChevronRight,
            modifier = Modifier.rotate(chevronTurn),
            size = 14.dp,
            color = colors.foregroundHint,
        )
        RaftText(
            text = stringResource(Res.string.tasks_source_list_done_fold, count),
            style = typography.caption,
            color = colors.foregroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ThreadMessageSourceTaskAssignee(
    task: ThreadMessageSourceTaskItem,
    memberAvatarResolver: RaftMemberAvatarResolver,
    agentStatusResolver: RaftAgentStatusResolver,
) {
    // Web TaskAssignee.tsx (slock #14517): on a joint channel (by the task's own channelType or the
    // channel surface), an assignee absent from this server's rosters falls back to actors/resolve.
    val onJointChannel = task.isJointChannel || memberAvatarResolver.jointSurface || LocalRaftJointActorSurface.current
    val assigneeResolver =
        remember(memberAvatarResolver, onJointChannel) {
            memberAvatarResolver.forJointSurface(onJointChannel)
        }
    val jointActor =
        rememberRaftJointActor(
            memberAvatarResolver = assigneeResolver,
            kind = task.assigneeKind,
            memberId = task.assigneeId,
            enabled = onJointChannel && !task.assigneeIsViewer,
        )
    val name =
        threadMessageSourceTaskAssigneeName(
            assigneeIsViewer = task.assigneeIsViewer,
            viewerLabel = stringResource(Res.string.thread_author_you),
            assigneeName = task.assigneeName,
            jointActor = jointActor,
        )
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
                cutoutColor = RaftTheme.colors.layerCard,
            ) {
                RaftMemberAvatar(
                    identity = RaftMemberAvatarIdentity(task.assigneeKind, task.assigneeId, serverId = null),
                    label = name,
                    avatarSize = avatarSize,
                    resolver = assigneeResolver,
                )
            }
        },
    )
}

/**
 * Web `TaskAssignee.tsx` (`slock #14517`): viewer reads "You"; in a joint channel an assignee
 * absent from this server's rosters takes the display name `POST /api/actors/resolve` returned
 * (falling back to handle, then the task's `claimedByName`).
 */
internal fun threadMessageSourceTaskAssigneeName(
    assigneeIsViewer: Boolean,
    viewerLabel: String,
    assigneeName: String?,
    jointActor: ResolvedActor?,
): String {
    if (assigneeIsViewer) return viewerLabel
    val actorName =
        jointActor?.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: jointActor?.name?.trim()?.takeIf { it.isNotEmpty() }
    return actorName ?: assigneeName.orEmpty()
}
