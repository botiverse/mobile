package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftUiControlledMenu
import build.raft.app.components.RaftUiMenuItem
import build.raft.app.data.ScreenError
import build.raft.app.data.ServerTaskBoardStatuses
import build.raft.app.data.TasksBatchAction
import build.raft.app.data.TasksBatchOutcome
import build.raft.app.data.TasksBatchViewer
import build.raft.app.data.TasksUnassignedAssigneeFilterId
import build.raft.app.data.canApplyTasksBatchAction
import build.raft.app.data.tasksIdentityFilterId
import build.raft.app.localizedLabel
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.resources.*
import build.raft.app.shell.RaftNativeTabBarCover
import build.raft.app.thread.icon
import build.raft.app.thread.localizedLabel
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCheckbox
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.components.RaftSelectionToolbar
import build.raft.designsystem.components.RaftSelectionToolbarActionSpec
import build.raft.designsystem.components.RaftSelectionToolbarClose
import build.raft.designsystem.components.RaftSelectionToolbarDefaults
import build.raft.designsystem.components.RaftSelectionToolbarDock
import build.raft.designsystem.components.RaftSelectionToolbarMore
import build.raft.designsystem.components.RaftTaskStatusPixel
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.components.raftToastAvoid
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/** Test tags for Tasks v2 bulk selection & action bar (matching web `TasksSelectionBar.tsx`). */
internal object SharedTasksBulkTags {
    const val Bar = "tasks-page-selection-bar"
    const val SelectAll = "tasks-batch-select-all"
    const val Status = "tasks-batch-status"
    const val Assignee = "tasks-batch-assignee"
    const val Follow = "tasks-batch-follow"
    const val Delete = "tasks-batch-delete"
    const val More = "tasks-batch-more"
    const val Clear = "selection-toolbar-clear"
    const val DeleteConfirm = "tasks-batch-delete-confirm"
    const val DeleteConfirmAction = "tasks-batch-delete-confirm-action"
    const val DeleteConfirmError = "tasks-batch-delete-confirm-error"
    const val RowCheckbox = "tasks-batch-row-checkbox"

    fun statusItem(status: TaskStatus): String = "tasks-batch-status-${status.tasksBulkTagWire()}"
}

private fun TaskStatus.tasksBulkTagWire(): String =
    when (this) {
        TaskStatus.Backlog -> "backlog"
        TaskStatus.Todo -> "todo"
        TaskStatus.InProgress -> "in_progress"
        TaskStatus.InReview -> "in_review"
        TaskStatus.Done -> "done"
        TaskStatus.Closed -> "closed"
        TaskStatus.Unknown -> "unknown"
    }

private fun TaskStatus.tasksBulkLabelResource(): StringResource =
    when (this) {
        TaskStatus.Backlog -> Res.string.tasks_status_backlog
        TaskStatus.Todo -> Res.string.tasks_status_todo
        TaskStatus.InProgress -> Res.string.tasks_status_in_progress
        TaskStatus.InReview -> Res.string.tasks_status_in_review
        TaskStatus.Done -> Res.string.tasks_status_done
        TaskStatus.Closed -> Res.string.tasks_status_closed
        TaskStatus.Unknown -> Res.string.tasks_status_unknown
    }

/** Sentinel option id for "Me" in the bulk assignee picker (web `TasksSelectionBar.tsx`). */
internal const val TasksBatchAssignMeId = "__tasks_batch_assign_me__"

/** Three-state look of the Tasks selection toolbar's leading select-all checkbox. */
internal enum class TasksBulkSelectAllCheck {
    Unchecked,
    Indeterminate,
    Checked,
}

internal fun tasksBulkSelectAllCheck(
    selectedIds: Set<String>,
    shownSelectableIds: Set<String>,
): TasksBulkSelectAllCheck =
    when {
        shownSelectableIds.isNotEmpty() && shownSelectableIds.all { it in selectedIds } ->
            TasksBulkSelectAllCheck.Checked
        selectedIds.any { it in shownSelectableIds } ->
            TasksBulkSelectAllCheck.Indeterminate
        else -> TasksBulkSelectAllCheck.Unchecked
    }

/**
 * Next selection when the leading select-all checkbox is tapped: if all shown selectable tasks are
 * already selected, clears selection; otherwise selects every shown selectable task.
 */
internal fun tasksBulkSelectAllNext(
    selectedIds: Set<String>,
    shownSelectableIds: Set<String>,
): Set<String> =
    if (tasksBulkSelectAllCheck(selectedIds, shownSelectableIds) == TasksBulkSelectAllCheck.Checked) {
        emptySet()
    } else {
        shownSelectableIds
    }

/** Says "All N selected" only when all shown selectable tasks are selected and no extra pages remain. */
internal fun tasksBulkSelectionSaysAll(
    check: TasksBulkSelectAllCheck,
    hasMoreUnloaded: Boolean,
): Boolean = check == TasksBulkSelectAllCheck.Checked && !hasMoreUnloaded

/** Formats the completion toast for a Tasks v2 bulk action (matching web `TasksSelectionBar.tsx`). */
internal fun formatTasksBatchOutcomeToast(
    action: TasksBatchAction,
    outcome: TasksBatchOutcome,
    statusLabel: String? = null,
    assigneeLabel: String? = null,
): String {
    val skippedSuffix =
        when {
            outcome.skipped <= 0 -> ""
            outcome.skipped == 1 -> Res.string.tasks_batch_skipped_one.format(outcome.skipped).resolve()
            else -> Res.string.tasks_batch_skipped_many.format(outcome.skipped).resolve()
        }
    val base =
        when (action) {
            is TasksBatchAction.Status -> {
                val resolvedStatus = statusLabel ?: action.status.tasksBulkLabelResource().desc().resolve()
                val res =
                    if (outcome.done == 1) {
                        Res.string.tasks_batch_status_done_one
                    } else {
                        Res.string.tasks_batch_status_done_many
                    }
                res.format(outcome.done, resolvedStatus).resolve()
            }
            is TasksBatchAction.Assignee -> {
                val targetAssignee = action.assignee
                if (targetAssignee != null) {
                    val resolvedName = assigneeLabel ?: targetAssignee.displayName
                    val res =
                        if (outcome.done == 1) {
                            Res.string.tasks_batch_assign_done_one
                        } else {
                            Res.string.tasks_batch_assign_done_many
                        }
                    res.format(outcome.done, resolvedName).resolve()
                } else {
                    val res =
                        if (outcome.done == 1) {
                            Res.string.tasks_batch_unassign_done_one
                        } else {
                            Res.string.tasks_batch_unassign_done_many
                        }
                    res.format(outcome.done).resolve()
                }
            }
            TasksBatchAction.Follow -> {
                val res =
                    if (outcome.done == 1) {
                        Res.string.tasks_batch_follow_done_one
                    } else {
                        Res.string.tasks_batch_follow_done_many
                    }
                res.format(outcome.done).resolve()
            }
            TasksBatchAction.Delete -> {
                val res =
                    if (outcome.done == 1) {
                        Res.string.tasks_batch_delete_done_one
                    } else {
                        Res.string.tasks_batch_delete_done_many
                    }
                res.format(outcome.done).resolve()
            }
        }
    return base + skippedSuffix
}

/**
 * Floating bulk action bar over the Tasks page while tasks are selected (web `TasksSelectionBar.tsx`,
 * botiverse/slock #13852).
 *
 * Uses [RaftSelectionToolbar] inside [RaftSelectionToolbarDock], covers any host-drawn native bottom
 * tab bar via [RaftNativeTabBarCover] while this page is active and showing the list, and registers
 * [Modifier.raftToastAvoid] so completion toasts clear the bar.
 *
 * Actions in order: Status, Assignee (if [viewer.canAssign]), Follow (secondary), Delete (secondary,
 * if any selected task may be deleted by [viewer]), More (auto-folded on narrow widths), × Clear.
 */
@Composable
internal fun SharedTasksSelectionBar(
    selectedTasks: List<TaskSummary>,
    shownSelectableIds: Set<String>,
    hasMoreUnloaded: Boolean,
    viewer: TasksBatchViewer,
    identityOptions: List<MemberProfile>,
    busy: Boolean,
    onRunBatch: suspend (TasksBatchAction, List<TaskSummary>, TasksBatchViewer) -> TasksBatchOutcome?,
    onToggleSelectAll: () -> Unit,
    onClear: () -> Unit,
    bottomSafeArea: Dp,
    hostActive: Boolean,
    batchScope: CoroutineScope = rememberCoroutineScope(),
    modifier: Modifier = Modifier,
) {
    RaftNativeTabBarCover(active = hostActive && LocalRaftTasksListShown.current)

    val toastHost = LocalRaftToastHostState.current
    val density = LocalDensity.current

    var localBusy by remember { mutableStateOf(false) }
    val effectiveBusy = busy || localBusy
    var statusMenuAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var assigneePickerOpen by remember { mutableStateOf(false) }
    var assigneeSearch by remember { mutableStateOf("") }
    var moreMenuAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var moreFoldedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val barAnchor = remember { RaftLiveRootAnchorRef(identity = "tasks_batch_bar") }
    val statusAnchor = remember { RaftLiveRootAnchorRef(identity = "tasks_batch_status") }
    val moreAnchor = remember { RaftLiveRootAnchorRef(identity = "tasks_batch_more") }

    val count = selectedTasks.size
    val selectedIds = remember(selectedTasks) { selectedTasks.mapTo(LinkedHashSet()) { it.id.value } }
    val selectAllCheck = tasksBulkSelectAllCheck(selectedIds, shownSelectableIds)
    val saysAll = tasksBulkSelectionSaysAll(selectAllCheck, hasMoreUnloaded)
    val canDelete = remember(selectedTasks, viewer) {
        selectedTasks.any { canApplyTasksBatchAction(TasksBatchAction.Delete, it, viewer) }
    }

    RaftBackHandler(enabled = statusMenuAnchor != null || moreMenuAnchor != null || assigneePickerOpen) {
        when {
            statusMenuAnchor != null -> statusMenuAnchor = null
            moreMenuAnchor != null -> moreMenuAnchor = null
            assigneePickerOpen -> {
                assigneePickerOpen = false
                assigneeSearch = ""
            }
        }
    }

    fun runAndToast(
        action: TasksBatchAction,
        statusLabel: String? = null,
        assigneeLabel: String? = null,
    ) {
        if (effectiveBusy || selectedTasks.isEmpty()) return
        batchScope.launch {
            localBusy = true
            try {
                val outcome = onRunBatch(action, selectedTasks, viewer) ?: return@launch
                toastHost?.show(
                    title = formatTasksBatchOutcomeToast(action, outcome, statusLabel, assigneeLabel),
                    intent = if (outcome.done == 0) RaftToastIntent.Error else null,
                )
            } finally {
                localBusy = false
            }
        }
    }

    val openStatusMenu: () -> Unit = {
        if (!effectiveBusy) {
            statusMenuAnchor =
                statusAnchor.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero))
                    ?: moreAnchor.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero))
                    ?: barAnchor.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero))
        }
    }
    val openAssigneePicker: () -> Unit = {
        if (!effectiveBusy) {
            assigneeSearch = ""
            assigneePickerOpen = true
        }
    }
    val runFollow: () -> Unit = {
        runAndToast(TasksBatchAction.Follow)
    }
    val askDelete: () -> Unit = {
        if (!effectiveBusy) {
            confirmingDelete = true
        }
    }

    val statusLabel = stringResource(Res.string.tasks_label_status)
    val assigneeLabel = stringResource(Res.string.tasks_filter_assignee)
    val followLabel = stringResource(Res.string.tasks_batch_follow)
    val deleteLabel = stringResource(Res.string.tasks_batch_delete)
    val moreLabel = stringResource(Res.string.tasks_batch_more)
    val clearLabel = stringResource(Res.string.tasks_batch_clear)
    val selectAllLabel = stringResource(Res.string.tasks_batch_select_all)
    val barAriaLabel = stringResource(Res.string.tasks_batch_aria)
    val iconSize = RaftSelectionToolbarDefaults.IconSize

    val icons =
        mapOf(
            "status" to RaftLucideIconName.CircleDot,
            "assignee" to RaftLucideIconName.User,
            "follow" to RaftLucideIconName.FollowThread,
            "delete" to RaftLucideIconName.Trash2,
        )

    val actions =
        buildList {
            add(
                RaftSelectionToolbarActionSpec(
                    id = "status",
                    label = statusLabel,
                    onClick = openStatusMenu,
                    enabled = !effectiveBusy && count > 0,
                    secondary = false,
                    open = statusMenuAnchor != null,
                    modifier = Modifier.testTag(SharedTasksBulkTags.Status).raftCaptureLiveRootAnchor(statusAnchor),
                    icon = { SharedTasksSelectionGlyph(RaftLucideIconName.CircleDot, iconSize) },
                )
            )
            if (viewer.canAssign) {
                add(
                    RaftSelectionToolbarActionSpec(
                        id = "assignee",
                        label = assigneeLabel,
                        onClick = openAssigneePicker,
                        enabled = !effectiveBusy && count > 0,
                        secondary = false,
                        open = assigneePickerOpen,
                        modifier = Modifier.testTag(SharedTasksBulkTags.Assignee),
                        icon = { SharedTasksSelectionGlyph(RaftLucideIconName.User, iconSize) },
                    )
                )
            }
            add(
                RaftSelectionToolbarActionSpec(
                    id = "follow",
                    label = followLabel,
                    onClick = runFollow,
                    enabled = !effectiveBusy && count > 0,
                    secondary = true,
                    modifier = Modifier.testTag(SharedTasksBulkTags.Follow),
                    icon = { SharedTasksSelectionGlyph(RaftLucideIconName.FollowThread, iconSize) },
                )
            )
            if (canDelete) {
                add(
                    RaftSelectionToolbarActionSpec(
                        id = "delete",
                        label = deleteLabel,
                        onClick = askDelete,
                        enabled = !effectiveBusy && count > 0,
                        secondary = true,
                        modifier = Modifier.testTag(SharedTasksBulkTags.Delete),
                        icon = { SharedTasksSelectionGlyph(RaftLucideIconName.Trash2, iconSize) },
                    )
                )
            }
        }

    Box(modifier = modifier) {
        RaftSelectionToolbarDock(bottomSafeArea = bottomSafeArea) {
            RaftSelectionToolbar(
                count =
                    if (saysAll) {
                        stringResource(Res.string.tasks_batch_all_selected_count, count)
                    } else {
                        stringResource(Res.string.tasks_batch_selected_count, count)
                    },
                countValue = count.toString(),
                label = barAriaLabel,
                modifier =
                    Modifier
                        .testTag(SharedTasksBulkTags.Bar)
                        .raftCaptureLiveRootAnchor(barAnchor)
                        .raftToastAvoid(enabled = true),
                leading = {
                    RaftCheckbox(
                        checked = selectAllCheck == TasksBulkSelectAllCheck.Checked,
                        indeterminate = selectAllCheck == TasksBulkSelectAllCheck.Indeterminate,
                        onCheckedChange = { if (!effectiveBusy) onToggleSelectAll() },
                        enabled = !effectiveBusy && shownSelectableIds.isNotEmpty(),
                        modifier =
                            Modifier
                                .testTag(SharedTasksBulkTags.SelectAll)
                                .semantics { contentDescription = selectAllLabel },
                    )
                },
                actions = actions,
                more = { folded ->
                    RaftSelectionToolbarMore(
                        onClick = {
                            if (!effectiveBusy) {
                                moreFoldedIds = folded.map { it.id }
                                moreAnchor.resolve(RaftPopoverLocalPoint.BottomEnd)?.let { moreMenuAnchor = it }
                            }
                        },
                        label = moreLabel,
                        tooltip = moreLabel,
                        enabled = !effectiveBusy && count > 0,
                        open = moreMenuAnchor != null,
                        modifier = Modifier.testTag(SharedTasksBulkTags.More).raftCaptureLiveRootAnchor(moreAnchor),
                    ) {
                        SharedTasksSelectionGlyph(RaftLucideIconName.Ellipsis, iconSize)
                    }
                },
                close = {
                    RaftSelectionToolbarClose(
                        onClick = { if (!effectiveBusy) onClear() },
                        label = clearLabel,
                        modifier = Modifier.testTag(SharedTasksBulkTags.Clear),
                    )
                },
            )
        }

        if (assigneePickerOpen && viewer.canAssign) {
            val meUserId = viewer.userId
            val meLabel = stringResource(Res.string.tasks_batch_assign_to_me)
            val youLabel = stringResource(Res.string.tasks_batch_you)
            val unassignedLabel = stringResource(Res.string.tasks_assignee_unassigned)
            val needle = assigneeSearch.trim().lowercase()
            val meProfile =
                meUserId?.let { id ->
                    identityOptions.firstOrNull { it.kind == MemberKind.Human && it.id.value == id }
                }
            val people =
                identityOptions
                    .filter { it.kind != MemberKind.System }
                    .filterNot { meUserId != null && it.kind == MemberKind.Human && it.id.value == meUserId }
                    .filter {
                        needle.isEmpty() ||
                            it.displayName.lowercase().contains(needle) ||
                            it.handle.lowercase().contains(needle)
                    }
                    .sortedWith(compareByDescending<MemberProfile> { it.kind == MemberKind.Human }.thenBy { it.displayName.lowercase() })
            val options =
                buildList {
                    if (meUserId != null && (needle.isEmpty() || meLabel.lowercase().contains(needle))) {
                        add(RaftFilterOption(id = TasksBatchAssignMeId, label = meLabel, icon = RaftLucideIconName.User))
                    }
                    if (needle.isEmpty() || unassignedLabel.lowercase().contains(needle)) {
                        add(
                            RaftFilterOption(
                                id = TasksUnassignedAssigneeFilterId,
                                label = unassignedLabel,
                                icon = RaftLucideIconName.User,
                            )
                        )
                    }
                    people.forEach { member ->
                        add(
                            RaftFilterOption(
                                id = member.tasksIdentityFilterId(),
                                label = member.displayName,
                                icon = if (member.kind == MemberKind.Agent) RaftLucideIconName.Bot else RaftLucideIconName.User,
                            )
                        )
                    }
                }
            RaftFilterPicker(
                title = stringResource(Res.string.tasks_batch_assign_to),
                searchPlaceholder = stringResource(Res.string.tasks_search_assignees),
                searchText = assigneeSearch,
                onSearchChange = { assigneeSearch = it },
                options = options,
                selectedIds = emptySet(),
                onToggle = { pickedId ->
                    assigneePickerOpen = false
                    assigneeSearch = ""
                    when (pickedId) {
                        TasksBatchAssignMeId -> {
                            val meId = meUserId ?: return@RaftFilterPicker
                            val target =
                                meProfile
                                    ?: MemberProfile(
                                        id = MemberId(meId),
                                        displayName = youLabel,
                                        handle = "",
                                        kind = MemberKind.Human,
                                    )
                            runAndToast(TasksBatchAction.Assignee(target), assigneeLabel = youLabel)
                        }
                        TasksUnassignedAssigneeFilterId -> {
                            runAndToast(TasksBatchAction.Assignee(null))
                        }
                        else -> {
                            val member = people.firstOrNull { it.tasksIdentityFilterId() == pickedId } ?: return@RaftFilterPicker
                            runAndToast(TasksBatchAction.Assignee(member), assigneeLabel = member.displayName)
                        }
                    }
                },
                onClear = { assigneeSearch = "" },
                onDismiss = {
                    assigneePickerOpen = false
                    assigneeSearch = ""
                },
                multiSelect = false,
                showClear = false,
            )
        }
    }

    statusMenuAnchor?.let { anchor ->
        RaftModalAnchoredPopover(
            anchor = anchor,
            onDismiss = { statusMenuAnchor = null },
            verticalOffset = with(density) { 8.dp.toPx() },
            verticalPlacement = RaftFloatingVerticalPlacement.Above,
            horizontalAutoFlip = true,
            dismissActivationDelayMillis = 0L,
        ) {
            RaftMenuSurface(minWidth = RaftTaskStatusMenuMinWidth) {
                ServerTaskBoardStatuses.forEach { status ->
                    val label = status.localizedLabel()
                    RaftMenuItem(
                        text = label,
                        onClick = {
                            statusMenuAnchor = null
                            runAndToast(TasksBatchAction.Status(status), statusLabel = label)
                        },
                        enabled = !effectiveBusy,
                        leadingIcon = { RaftTaskStatusPixel(status.raftTaskStatus()) },
                        modifier = Modifier.testTag(SharedTasksBulkTags.statusItem(status)),
                    )
                }
            }
        }
    }

    moreMenuAnchor?.let { anchor ->
        val menuActions = actions.filter { it.id in moreFoldedIds }
        RaftUiControlledMenu(
            expanded = true,
            anchor = anchor,
            items =
                menuActions.map { spec ->
                    RaftUiMenuItem(
                        id = spec.id,
                        label = spec.label,
                        icon = icons[spec.id],
                        destructive = spec.id == "delete",
                        enabled = spec.enabled,
                    )
                },
            onSelect = { id ->
                moreMenuAnchor = null
                menuActions.firstOrNull { it.id == id }?.onClick?.invoke()
            },
            onDismiss = { moreMenuAnchor = null },
            verticalOffset = 8.dp,
            verticalPlacement = RaftFloatingVerticalPlacement.Above,
        )
    }

    if (confirmingDelete) {
        TasksBatchDeleteConfirmDialog(
            count = count,
            batchScope = batchScope,
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                localBusy = true
                try {
                    val outcome = onRunBatch(TasksBatchAction.Delete, selectedTasks, viewer)
                        ?: return@TasksBatchDeleteConfirmDialog null
                    if (outcome.failedIds.isNotEmpty()) {
                        val failedCount = outcome.failedIds.size
                        val message =
                            if (failedCount == 1) {
                                Res.string.tasks_batch_delete_failed_one.format(failedCount).resolve()
                            } else {
                                Res.string.tasks_batch_delete_failed_many.format(failedCount).resolve()
                            }
                        return@TasksBatchDeleteConfirmDialog TasksBatchDeleteFailure(
                            message = message,
                            traceId = outcome.firstError?.traceId,
                        )
                    }
                    confirmingDelete = false
                    toastHost?.show(
                        title = formatTasksBatchOutcomeToast(TasksBatchAction.Delete, outcome),
                        intent = if (outcome.done == 0) RaftToastIntent.Error else null,
                    )
                    null
                } finally {
                    localBusy = false
                }
            },
        )
    }
}

private data class TasksBatchDeleteFailure(
    val message: String,
    val traceId: String?,
)

/**
 * Bulk delete confirmation dialog (web `TaskDeleteConfirmDialog`, slock #13852): asks "Delete N
 * tasks?", warns in a destructive banner, shows loading on the Danger button, and keeps the dialog
 * open with an inline warning banner + trace ID if any task was refused by the server.
 */
@Composable
private fun TasksBatchDeleteConfirmDialog(
    count: Int,
    batchScope: CoroutineScope,
    onDismiss: () -> Unit,
    onConfirm: suspend () -> TasksBatchDeleteFailure?,
) {
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<TasksBatchDeleteFailure?>(null) }
    val safeCount = count.coerceAtLeast(1)
    val title =
        if (safeCount == 1) {
            stringResource(Res.string.tasks_batch_delete_confirm_one, safeCount)
        } else {
            stringResource(Res.string.tasks_batch_delete_confirm_many, safeCount)
        }
    val body =
        if (safeCount == 1) {
            stringResource(Res.string.task_delete_confirm_body)
        } else {
            stringResource(Res.string.tasks_batch_delete_confirm_body_many)
        }
    val actionLabel =
        if (safeCount == 1) {
            stringResource(Res.string.tasks_batch_delete_action_one, safeCount)
        } else {
            stringResource(Res.string.tasks_batch_delete_action_many, safeCount)
        }
    val dismiss = { if (!busy) onDismiss() }
    RaftDialog(
        onDismissRequest = dismiss,
        title = title,
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = !busy,
        closeLabel = stringResource(Res.string.common_close),
        modifier = Modifier.testTag(SharedTasksBulkTags.DeleteConfirm),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = dismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !busy,
            )
            RaftButton(
                text = actionLabel,
                onClick = {
                    if (!busy) {
                        busy = true
                        failure = null
                        batchScope.launch {
                            try {
                                failure = onConfirm()
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Lg,
                loading = busy,
                modifier = Modifier.testTag(SharedTasksBulkTags.DeleteConfirmAction),
            )
        },
    ) {
        RaftBanner(description = body, status = RaftBannerStatus.Destructive, size = RaftBannerSize.Sm)
        failure?.let { error ->
            Column(Modifier.testTag(SharedTasksBulkTags.DeleteConfirmError), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftBanner(
                    description = error.message,
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                )
                RaftTraceIdNote(error.traceId)
            }
        }
    }
}

@Composable
private fun SharedTasksSelectionGlyph(icon: RaftLucideIconName, size: Dp) {
    val ink =
        LocalRaftContentColor.current.takeIf { it != Color.Unspecified }
            ?: RaftTheme.colors.foreground
    RaftLucideIcon(icon, modifier = Modifier.size(size), color = raftArgbHex(ink))
}
