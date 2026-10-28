package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.TaskStatusOption
import build.raft.app.data.canEditAssignee
import build.raft.app.data.canEditStatus
import build.raft.app.data.taskServerRoleCanManage
import build.raft.app.data.taskStatusOptions
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftComboboxSheet
import build.raft.designsystem.components.RaftTaskStatus

/** The app's task status as the design system's (same five, same order). */
internal fun TaskStatus.raftTaskStatus(): RaftTaskStatus =
    when (this) {
        TaskStatus.Todo -> RaftTaskStatus.Todo
        TaskStatus.InProgress -> RaftTaskStatus.InProgress
        TaskStatus.InReview -> RaftTaskStatus.InReview
        TaskStatus.Done -> RaftTaskStatus.Done
        TaskStatus.Closed -> RaftTaskStatus.Closed
    }

/** What the task conversation's bar shows, derived purely from the task, the page state and the reader's role. */
@Immutable
internal data class TaskConversationHeaderBarModel(
    val status: TaskStatus,
    /** Empty: the status cannot be changed here (read-only task, guest). */
    val statusOptions: List<TaskStatusOption>,
    val statusUpdating: Boolean,
    /** The assignee's display name; null = unassigned. */
    val assigneeName: String?,
    val assigneeEditable: Boolean,
    /** Null: not known yet (the follow button waits). */
    val followed: Boolean?,
    val followPending: Boolean,
) {
    val statusEditable: Boolean
        get() = statusOptions.size > 1
}

internal fun TaskSummary.taskConversationHeaderBarModel(
    state: TaskConversationHeaderState,
    serverRole: String?,
    statusUpdating: Boolean,
): TaskConversationHeaderBarModel =
    TaskConversationHeaderBarModel(
        status = status,
        statusOptions =
            if (canEditStatus(serverRole)) taskStatusOptions(status, taskServerRoleCanManage(serverRole)) else emptyList(),
        statusUpdating = statusUpdating,
        assigneeName = assignee?.displayName?.trim()?.takeIf { it.isNotBlank() },
        assigneeEditable = canEditAssignee(serverRole),
        followed = state.followed(conversationFollowed),
        followPending = state.followPending,
    )

/**
 * Web TaskProperties' assignee candidates (`useChannelMembers` + `dedupeTaskAssigneeMembers`): the
 * task channel's people first, then its agents, each by display name; "Unassigned" comes first in
 * the picker as its own option.
 */
internal fun taskConversationAssigneeCandidates(members: List<MemberProfile>): List<MemberProfile> =
    members
        .filter { it.kind == MemberKind.Human || it.kind == MemberKind.Agent }
        .distinctBy { it.kind to it.id }
        .sortedWith(compareBy<MemberProfile>({ if (it.kind == MemberKind.Human) 0 else 1 }, { it.displayName.lowercase() }))

/** The assignee picker's value for "no assignee" (its first option). */
internal const val TaskConversationUnassignedOptionId = "unassigned"

internal fun MemberProfile.taskConversationAssigneeOptionId(): String = "${kind.name}:${id.value}"

/**
 * The assignee picker (web TaskProperties' searchable InlineBadgeEditor): a bottom sheet of
 * "Unassigned", then the task channel's people and agents, filtered by what is typed. Picking
 * the current assignee closes it without a write.
 */
@Composable
internal fun TaskConversationAssigneeSheet(
    current: MemberProfile?,
    candidates: List<MemberProfile>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (MemberProfile?) -> Unit,
    onDismiss: () -> Unit,
) {
    val unassigned = stringResource(Res.string.tasks_assignee_unassigned)
    val options =
        remember(candidates, unassigned) {
            listOf(RaftComboboxOption(TaskConversationUnassignedOptionId, unassigned)) +
                candidates.map { member -> RaftComboboxOption(member.taskConversationAssigneeOptionId(), member.displayName) }
        }
    val noMatches = stringResource(Res.string.common_no_matches)
    RaftComboboxSheet(
        value = current?.taskConversationAssigneeOptionId() ?: TaskConversationUnassignedOptionId,
        options = options,
        onValueChange = { id ->
            if (id == TaskConversationUnassignedOptionId) {
                onSelect(null)
            } else {
                candidates.firstOrNull { it.taskConversationAssigneeOptionId() == id }?.let(onSelect)
            }
        },
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.tasks_assignee_picker_title),
        emptyText = { noMatches },
        query = query,
        onQueryChange = onQueryChange,
        searchPlaceholder = stringResource(Res.string.tasks_search_assignees),
    )
}
