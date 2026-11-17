package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import build.raft.app.components.rememberRaftDesignSystemEditor
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.data.TaskBacklogReasonMaxLength
import build.raft.app.data.TaskCreateStatuses
import build.raft.app.model.TaskStatus
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftSelectTrigger
import build.raft.designsystem.components.RaftTaskStatusPixel
import build.raft.designsystem.components.RaftTextField

internal const val TaskBacklogReasonDialogTestTag = "task-backlog-reason"
internal const val TaskBacklogReasonInputTestTag = "task-backlog-reason-input"
internal const val TaskBacklogReasonConfirmTestTag = "task-backlog-reason-confirm"
internal const val TaskBacklogReasonCancelTestTag = "task-backlog-reason-cancel"
internal const val TaskCreateStatusTestTag = "create-task-status"

/**
 * Asked when a task is moved into Backlog from another status, by any status menu (web
 * `TaskBacklogReasonPopover`, slock #12963; raft-design patterns/tasks.md, Moving a task to
 * Backlog): what would bring it back. The reason is optional and one line. Cancel, Back or the
 * header's close leave the task where it was; only Move to Backlog writes, through [onConfirm]
 * (null: no reason). On a phone the web popover's place is a small dialog: it holds the keyboard.
 */
@Composable
internal fun TaskBacklogReasonDialog(
    taskNumber: Int?,
    onDismiss: () -> Unit,
    onConfirm: (reason: String?) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    val confirmLabel = stringResource(Res.string.tasks_backlog_reason_confirm)
    val submit = { onConfirm(reason.trim().takeIf { it.isNotEmpty() }) }
    // The field has focus when it opens (web `initialFocus`); the keyboard's Done moves it, as Enter submits on web.
    val editor =
        rememberRaftDesignSystemEditor(
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            keyboardHeightChange = {},
            focusRequester = remember { FocusRequester() },
            autoFocus = true,
            onFocus = {},
            onBlur = {},
        )
    RaftUnifiedDialog(
        // Web aria-label "Move #25 to Backlog"; a task without its number yet reads as the button.
        title = taskNumber?.let { stringResource(Res.string.tasks_backlog_reason_title, it) } ?: confirmLabel,
        onDismiss = onDismiss,
        // Typed input: a stray tap on the scrim does not throw it away (web FormDialog).
        dismissOnOutsideTap = false,
        keyboardAvoidanceEnabled = true,
        minWidth = 280.dp,
        maxWidth = 360.dp,
        contentPadding = RaftAppSpacing.Md,
        contentSpacing = RaftAppSpacing.Sm,
        modifier = Modifier.testTag(TaskBacklogReasonDialogTestTag),
    ) {
        RaftField(label = stringResource(Res.string.tasks_backlog_reason_label)) {
            RaftTextField(
                value = reason,
                // slock TASK_BACKLOG_REASON_MAX_LENGTH, one line: the server folds line breaks anyway.
                onValueChange = { next -> reason = next.replace('\n', ' ').take(TaskBacklogReasonMaxLength) },
                modifier = Modifier.fillMaxWidth().then(editor.modifier).testTag(TaskBacklogReasonInputTestTag),
                placeholder = stringResource(Res.string.tasks_backlog_reason_placeholder),
                keyboardOptions = editor.keyboardOptions,
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        }
        RaftDialogFooter {
            // A small step: ghost and outline, not the pink button (web PopoverActions).
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Md,
                modifier = Modifier.testTag(TaskBacklogReasonCancelTestTag),
            )
            RaftButton(
                text = confirmLabel,
                onClick = submit,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md,
                modifier = Modifier.testTag(TaskBacklogReasonConfirmTestTag),
            )
        }
    }
}

/**
 * Create Task's Status field (web `CreateTaskDialog` on task v2, slock #12963): Backlog, Todo or In
 * Progress, each with its status pixel; Todo by default. A create in Backlog asks for no reason.
 * With more than one title the field says the status applies to all of them.
 */
@Composable
internal fun TaskCreateStatusField(
    value: TaskStatus,
    onValueChange: (TaskStatus) -> Unit,
    titleCount: Int,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    RaftField(
        label = stringResource(Res.string.task_head_status),
        modifier = modifier.fillMaxWidth(),
        description = if (titleCount > 1) stringResource(Res.string.tasks_create_status_applies_to_all, titleCount) else null,
        enabled = enabled,
    ) {
        Box {
            RaftSelectTrigger(
                value = value.localizedLabel(),
                onClick = { expanded = true },
                modifier = Modifier.onSizeChanged { triggerWidth = it.width }.testTag(TaskCreateStatusTestTag),
                expanded = expanded,
                onCollapse = { expanded = false },
                leadingIcon = { RaftTaskStatusPixel(value.raftTaskStatus()) },
            )
            RaftDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                width = maxOf(220.dp, with(density) { triggerWidth.toDp() }),
                offset = 4.dp,
            ) {
                TaskCreateStatuses.forEach { status ->
                    RaftMenuItem(
                        text = status.localizedLabel(),
                        checked = status == value,
                        leadingIcon = { RaftTaskStatusPixel(status.raftTaskStatus()) },
                        onClick = {
                            expanded = false
                            onValueChange(status)
                        },
                        modifier = Modifier.testTag("$TaskCreateStatusTestTag-${status.toWireValue()}"),
                    )
                }
            }
        }
    }
}
