package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import build.raft.app.data.ScreenError
import build.raft.app.data.TaskAmendPatch
import build.raft.app.data.TaskAmendResult
import build.raft.app.data.TaskDescriptionMaxLength
import build.raft.app.data.TaskTitleMaxLength
import build.raft.app.model.TaskSummary
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftFieldError
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextarea
import kotlinx.coroutines.launch

/** The field of a task's head being edited (web `TaskHeadEditField`). */
internal enum class TaskHeadEditField { Title, Description }

/** Why an edit is not saved yet, shown under the field (web `EditProblem`). */
internal sealed interface TaskHeadEditProblem {
    /** An empty title is refused here, before anything is sent. */
    data object TitleRequired : TaskHeadEditProblem

    /** Someone else changed the field since editing opened (or the server said 409): nothing was sent. */
    data object Stale : TaskHeadEditProblem

    /** Refused or failed: the server's sentence (or ours) and the request's trace ID. */
    data class Failed(val error: ScreenError) : TaskHeadEditProblem
}

/**
 * Editing a task's title or description in its head (web `TaskHeadFieldEditor`, slock #14114),
 * one field at a time. The draft never goes away on a failed save: a refused, stale or failed
 * write leaves what was typed with the reason under it.
 *
 * Someone else's edit is caught by the field itself, not the revision (status and assignee
 * changes move it too): the field's text when editing opened is the base, and a save that finds
 * the task's text no longer the base says so and sends nothing; saving again replaces their
 * text. The revision still goes along, so a race not yet seen comes back as a 409 and the same
 * warning, and the retry goes against the revision the server reported.
 */
@Stable
internal class TaskHeadEditState {
    var field by mutableStateOf<TaskHeadEditField?>(null)
        private set
    var draft by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var problem by mutableStateOf<TaskHeadEditProblem?>(null)
        private set

    /** The task the open edit is of. */
    var taskId by mutableStateOf<build.raft.app.model.TaskId?>(null)
        private set

    /** The field's text this edit started from; null after a 409 (the next save takes the task's text then). */
    private var base: String? = null
    private var retryRevision: Long? = null

    /** Opens [field] of [task] with its current text (an open edit of the other field closes first). */
    fun open(task: TaskSummary, field: TaskHeadEditField) {
        if (busy) return
        this.field = field
        taskId = task.id
        draft = if (field == TaskHeadEditField.Title) task.title else task.description.orEmpty()
        base = task.fieldValue(field)
        retryRevision = null
        problem = null
    }

    fun onDraftChange(value: String) {
        draft = value
        if (problem == TaskHeadEditProblem.TitleRequired) problem = null
    }

    /** Back / Cancel: the text goes back as it was. Not while a write is on its way. */
    fun cancel() {
        if (busy) return
        close()
    }

    /** Saves the draft against [task] as it is now (web `save`), writing through [send]. */
    suspend fun save(task: TaskSummary, send: suspend (TaskAmendPatch, Long?) -> TaskAmendResult) {
        val field = field ?: return
        if (busy || task.id != taskId) return
        val value = draft.trim()
        val current = task.fieldValue(field)
        if (field == TaskHeadEditField.Title && value.isEmpty()) {
            problem = TaskHeadEditProblem.TitleRequired
            return
        }
        val base = base ?: current
        // Nothing of ours to write: unchanged, or already what the task says.
        if (value == base || value == current) {
            close()
            return
        }
        if (current != base) {
            // Someone else changed this field since the edit opened: say so; the next save is against theirs.
            this.base = current
            problem = TaskHeadEditProblem.Stale
            return
        }
        this.base = current
        val patch = if (field == TaskHeadEditField.Title) TaskAmendPatch.Title(value) else TaskAmendPatch.Description(value)
        val retry = retryRevision
        val expectedRevision = if (retry != null && (task.revision == null || retry > task.revision)) retry else task.revision
        busy = true
        problem = null
        val result =
            try {
                send(patch, expectedRevision)
            } finally {
                busy = false
            }
        when (result) {
            is TaskAmendResult.Saved -> close()
            is TaskAmendResult.Stale -> {
                retryRevision = result.currentRevision
                this.base = null
                problem = TaskHeadEditProblem.Stale
            }
            is TaskAmendResult.Failed -> problem = TaskHeadEditProblem.Failed(result.error)
        }
    }

    private fun close() {
        field = null
        taskId = null
        problem = null
        base = null
        retryRevision = null
    }
}

/**
 * What a task head needs to edit its task's text: the edit (kept by the page, [TaskHeadScrollState.edit],
 * so it outlives the head's row leaving the list), the task as it is now and the write.
 */
internal class TaskHeadEditing(
    val state: TaskHeadEditState,
    val task: TaskSummary,
    val send: suspend (TaskAmendPatch, Long?) -> TaskAmendResult,
    /** False on a summary row whose description was not sent: no Edit / Add description. */
    val descriptionEditable: Boolean = task.descriptionLoaded,
)

/** The text of [field] as the task has it now, trimmed (web `fieldValueOf`). */
private fun TaskSummary.fieldValue(field: TaskHeadEditField): String =
    if (field == TaskHeadEditField.Title) title.trim() else description.orEmpty().trim()

/**
 * Who may edit the title and description (web TaskModalHead `canEdit`): the shared amend rule with
 * channel access assumed, the head only showing to readers of the task; the server answers a
 * refusal with its sentence. Never a legacy card, never a read-only (historical joint) task.
 */
internal fun TaskSummary.canAmendText(): Boolean = !isLegacy && readOnlyReason == null

internal const val TaskHeadTitleEditTestTag = "task-head-title-edit"
internal const val TaskHeadDescriptionEditTestTag = "task-head-description-edit"
internal const val TaskHeadAddDescriptionTestTag = "task-head-add-description"
internal const val TaskHeadTitleInputTestTag = "task-head-title-input"
internal const val TaskHeadDescriptionInputTestTag = "task-head-description-input"
internal const val TaskHeadEditSaveTestTag = "task-head-edit-save"
internal const val TaskHeadEditCancelTestTag = "task-head-edit-cancel"

/** The pencil beside the title or the description (web `TaskHeadEditButton`; always shown by touch). */
@Composable
internal fun TaskHeadEditButton(label: String, testTag: String, onClick: () -> Unit) {
    RaftIconButton(
        onClick = onClick,
        tooltip = label,
        variant = RaftButtonVariant.Ghost,
        size = RaftIconButtonSize.Sm,
        modifier = Modifier.testTag(testTag),
    ) {
        RaftGlyphIcon(RaftGlyph.Pencil)
    }
}

/** "Add a description" (web: a quiet ghost button where a description would be). */
@Composable
internal fun TaskHeadAddDescriptionButton(onClick: () -> Unit) {
    RaftButton(
        text = stringResource(Res.string.task_amend_add_description),
        onClick = onClick,
        variant = RaftButtonVariant.Ghost,
        size = RaftButtonSize.Md,
        modifier = Modifier.testTag(TaskHeadAddDescriptionTestTag),
    )
}

/**
 * The open editor of [TaskHeadEditState.field] in place of the title or the description (web
 * `TaskHeadFieldEditor`). The field takes focus with the caret at the end. The title saves on the
 * keyboard's Done; there is no Shift+Enter or Esc by touch, so Cancel and Save sit under the field
 * and Back cancels. A refusal shows under it with its trace ID; the draft stays.
 */
@Composable
internal fun TaskHeadFieldEditor(editing: TaskHeadEditing) {
    val state = editing.state
    val field = state.field ?: return
    val scope = rememberCoroutineScope()
    val save: () -> Unit = { scope.launch { state.save(editing.task, editing.send) } }
    RaftBackHandler(enabled = true, label = "task_head_edit") { state.cancel() }
    val focus = remember(field) { FocusRequester() }
    var value by remember(field) {
        mutableStateOf(TextFieldValue(state.draft, selection = TextRange(state.draft.length)))
    }
    LaunchedEffect(field) { runCatching { focus.requestFocus() } }
    val onValueChange: (TextFieldValue) -> Unit = { next ->
        value = next
        state.onDraftChange(next.text)
    }
    val problem = state.problem
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (field == TaskHeadEditField.Title) {
            val label = stringResource(Res.string.task_amend_title_label)
            RaftTextField(
                value = value,
                onValueChange = { next -> if (next.text.length <= TaskTitleMaxLength) onValueChange(next) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(TaskHeadTitleInputTestTag).semantics { contentDescription = label },
                readOnly = state.busy,
                invalid = problem == TaskHeadEditProblem.TitleRequired,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
        } else {
            val label = stringResource(Res.string.task_amend_description_label)
            RaftTextarea(
                value = value,
                onValueChange = { next -> if (next.text.length <= TaskDescriptionMaxLength) onValueChange(next) },
                modifier = Modifier.fillMaxWidth(),
                inputModifier = Modifier.focusRequester(focus).testTag(TaskHeadDescriptionInputTestTag).semantics { contentDescription = label },
                readOnly = state.busy,
                maxHeight = 240.dp,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
        when (problem) {
            TaskHeadEditProblem.TitleRequired -> RaftFieldError(stringResource(Res.string.task_amend_title_required))
            TaskHeadEditProblem.Stale ->
                RaftBanner(description = stringResource(Res.string.task_amend_stale), status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
            is TaskHeadEditProblem.Failed ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RaftBanner(
                        description = problem.error.serverMessage ?: stringResource(Res.string.task_amend_failed),
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm,
                    )
                    RaftTraceIdNote(problem.error.traceId)
                }
            null -> Unit
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = { state.cancel() },
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Md,
                enabled = !state.busy,
                modifier = Modifier.testTag(TaskHeadEditCancelTestTag),
            )
            RaftButton(
                text = stringResource(Res.string.common_save),
                onClick = save,
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Md,
                loading = state.busy,
                modifier = Modifier.testTag(TaskHeadEditSaveTestTag),
            )
        }
    }
}

/**
 * Who may delete a task (web `useCanDeleteTask`, the server's DELETE /tasks/:id rule): the person
 * who created it, or someone holding `deleteAnyTask` (a server owner or admin, slock
 * `serverPermissions.ts`). Never a read-only (historical joint) task. The server still decides: a
 * refusal comes back in its words.
 */
internal fun TaskSummary.canBeDeletedBy(viewerUserId: String?, serverRole: String?): Boolean {
    if (readOnlyReason != null) return false
    val viewer = viewerUserId?.trim()?.takeIf { it.isNotEmpty() }
    val isCreator = viewer != null && creator?.kind == build.raft.app.model.MemberKind.Human && creator.id.value == viewer
    return isCreator || serverRole == "owner" || serverRole == "admin"
}

internal const val TaskDeleteConfirmTestTag = "task-delete-confirm"
internal const val TaskDeleteConfirmActionTestTag = "task-delete-confirm-action"
internal const val TaskDeleteConfirmErrorTestTag = "task-delete-confirm-error"

/**
 * Delete task's confirmation (web `TaskDeleteConfirmDialog`, slock #14115): "Delete task #N?", what
 * cannot be undone in a destructive banner, Cancel and a danger "Delete task" that shows loading. A
 * failure shows as a warning banner inside with its trace ID and the dialog stays open; nothing
 * closes it while the delete runs. [onConfirm] answers null once deleted (the caller closes it).
 */
@Composable
internal fun TaskDeleteConfirmDialog(
    taskNumber: Int?,
    onDismiss: () -> Unit,
    onConfirm: suspend () -> ScreenError?,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ScreenError?>(null) }
    val actionLabel = stringResource(Res.string.task_delete_action)
    val dismiss = { if (!busy) onDismiss() }
    build.raft.designsystem.components.RaftDialog(
        onDismissRequest = dismiss,
        title = taskNumber?.let { stringResource(Res.string.task_delete_confirm_title, it) } ?: actionLabel,
        size = build.raft.designsystem.components.RaftDialogSize.Sm,
        dismissOnClickOutside = !busy,
        closeLabel = stringResource(Res.string.common_close),
        modifier = Modifier.testTag(TaskDeleteConfirmTestTag),
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
                        error = null
                        scope.launch {
                            try {
                                error = onConfirm()
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Lg,
                loading = busy,
                modifier = Modifier.testTag(TaskDeleteConfirmActionTestTag),
            )
        },
    ) {
        RaftBanner(description = stringResource(Res.string.task_delete_confirm_body), status = RaftBannerStatus.Destructive, size = RaftBannerSize.Sm)
        error?.let { failure ->
            Column(Modifier.testTag(TaskDeleteConfirmErrorTestTag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftBanner(
                    description = failure.serverMessage ?: stringResource(Res.string.task_delete_failed),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                )
                RaftTraceIdNote(failure.traceId)
            }
        }
    }
}
