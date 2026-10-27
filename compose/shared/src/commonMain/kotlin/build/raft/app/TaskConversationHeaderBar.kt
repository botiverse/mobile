package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftComboboxSheet
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftPickerTriggerButton
import build.raft.designsystem.components.RaftPickerTriggerSize
import build.raft.designsystem.components.RaftTaskStatus
import build.raft.designsystem.components.RaftTaskStatusBadge
import build.raft.designsystem.components.RaftTaskStatusPixel
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.theme.RaftTheme

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
 * Task v2: the bar under a task conversation's header (web `TaskConversationBar` with the task's
 * Status and Assignee properties, reply-design §6): the status as the raft-ui TaskStatusBadge that
 * opens the legal next statuses, the assignee as a picker trigger that opens the channel's people
 * and agents, and Follow / Unfollow of the conversation. Controls the reader may not use stay as
 * plain facts (no chevron, no tap).
 */
@Composable
internal fun TaskConversationHeaderBar(
    model: TaskConversationHeaderBarModel,
    onSelectStatus: (TaskStatus) -> Unit,
    onOpenAssignee: () -> Unit,
    onToggleFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    var statusMenuOpen by remember { mutableStateOf(false) }
    val statusLabel = model.status.localizedLabel()
    val statusFieldLabel = stringResource(Res.string.tasks_label_status)
    val unassigned = stringResource(Res.string.tasks_assignee_unassigned)
    Column(modifier = modifier.fillMaxWidth().background(colors.layerCanvas)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .raftSafeAreaHorizontalPadding()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                RaftTaskStatusBadge(
                    status = model.status.raftTaskStatus(),
                    text = statusLabel,
                    onClick = if (model.statusEditable) ({ statusMenuOpen = true }) else null,
                    enabled = !model.statusUpdating,
                    contentDescription = "$statusFieldLabel: $statusLabel",
                )
                RaftDropdownMenu(
                    expanded = statusMenuOpen && model.statusEditable,
                    onDismissRequest = { statusMenuOpen = false },
                    minWidth = 200.dp,
                ) {
                    model.statusOptions.forEach { option ->
                        RaftMenuItem(
                            text =
                                if (option.reopensToTodo) {
                                    stringResource(Res.string.tasks_status_reopen_to_todo)
                                } else {
                                    option.status.localizedLabel()
                                },
                            checked = option.status == model.status,
                            leadingIcon = { RaftTaskStatusPixel(option.status.raftTaskStatus()) },
                            onClick = {
                                statusMenuOpen = false
                                if (option.status != model.status) onSelectStatus(option.status)
                            },
                        )
                    }
                }
            }
            // The assignee takes the room left before the follow button; a long name ends in an ellipsis.
            Box(Modifier.weight(1f)) {
                RaftPickerTriggerButton(
                    text = model.assigneeName?.let { "@$it" } ?: unassigned,
                    onClick = onOpenAssignee,
                    hasValue = model.assigneeName != null,
                    enabled = model.assigneeEditable,
                    showIndicator = model.assigneeEditable,
                    modifier = Modifier.widthIn(max = 200.dp),
                    // The status badge's box (22dp chip): both are "tap to choose a value" (web
                    // InlineBadgeEditor draws Status and Assignee as the same 22px chip).
                    size = RaftPickerTriggerSize.Chip,
                )
            }
            model.followed?.let { followed ->
                // Web keeps Follow / Unfollow Task in the bar's More menu (ThreadOverflowMenu); here the
                // command is the bar's one icon button, with the web's icons and words as its tooltip.
                val followLabel =
                    stringResource(if (followed) Res.string.task_conversation_unfollow else Res.string.task_conversation_follow)
                RaftIconButton(
                    onClick = onToggleFollow,
                    tooltip = followLabel,
                    variant = RaftButtonVariant.Ghost,
                    loading = model.followPending,
                ) {
                    RaftLucideIcon(
                        if (followed) RaftLucideIconName.MessageCircleOff else RaftLucideIconName.MessageCirclePlus,
                        Modifier.size(18.dp),
                        color = raftArgbHex(colors.foreground),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineHairline))
    }
}

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

/**
 * The bar with its assignee sheet, for one task conversation page: [task] from the task channel's
 * projection, [state] the page's own header state. Writes go out through the callbacks.
 */
@Composable
internal fun TaskConversationBarRoute(
    task: TaskSummary,
    state: TaskConversationHeaderState,
    serverRole: String?,
    statusUpdating: Boolean,
    assigneeCandidates: List<MemberProfile>,
    onUpdateStatus: (TaskStatus) -> Unit,
    onAssign: (MemberProfile?) -> Unit,
    onToggleFollow: (currentlyFollowed: Boolean) -> Unit,
) {
    var assigneeSheetOpen by remember(task.id) { mutableStateOf(false) }
    var assigneeQuery by remember(task.id) { mutableStateOf("") }
    val model = task.taskConversationHeaderBarModel(state, serverRole, statusUpdating)
    RaftDesignSystemTheme {
        TaskConversationHeaderBar(
            model = model,
            onSelectStatus = onUpdateStatus,
            onOpenAssignee = {
                if (model.assigneeEditable) {
                    assigneeQuery = ""
                    assigneeSheetOpen = true
                }
            },
            onToggleFollow = { model.followed?.let(onToggleFollow) },
        )
        if (assigneeSheetOpen && model.assigneeEditable) {
            TaskConversationAssigneeSheet(
                current = task.assignee,
                candidates = taskConversationAssigneeCandidates(assigneeCandidates),
                query = assigneeQuery,
                onQueryChange = { assigneeQuery = it },
                onSelect = { member ->
                    val currentId = task.assignee?.let { it.kind to it.id }
                    val nextId = member?.let { it.kind to it.id }
                    if (currentId != nextId) onAssign(member)
                },
                onDismiss = { assigneeSheetOpen = false },
            )
        }
    }
}
