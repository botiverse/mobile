package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/** Task v2 "Create Task from Message" (slock web `CreateTaskSourceMessage`): the tasks hang under this message. */
internal data class TaskCreateSourceMessage(
    val channelId: ChannelId,
    val messageId: MessageId,
    val senderName: String,
    val text: String,
)

private const val TaskSourceTitleMax = 200

/** Web `titleFromMessage`: the message on one line, cut at 200 characters with an ellipsis. */
internal fun taskTitleFromMessage(text: String): String {
    val single = text.replace(Regex("\\s+"), " ").trim()
    return if (single.length <= TaskSourceTitleMax) single else single.take(TaskSourceTitleMax - 1).trimEnd() + "…"
}

/**
 * Task v2: "Create Task from Message" (slock web `CreateTaskDialog` with `sourceMessage`): the
 * message quoted (sender, three lines), then one title per task — the first starts as the
 * message's text — "Add Another", Cancel and Create (Create N Tasks). [onSubmit] gets the
 * non-blank titles; [errorText] is the failure banner (the server's `error` when it gave one, as
 * web shows it, else "Failed to create task"). Nothing dismisses it while [submitting].
 */
@Composable
internal fun TaskCreateFromMessageDialog(
    source: TaskCreateSourceMessage,
    submitting: Boolean,
    errorText: String?,
    onDismiss: () -> Unit,
    onSubmit: (List<String>) -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    var titles by remember(source.messageId) { mutableStateOf(listOf(taskTitleFromMessage(source.text))) }
    val filled = titles.map { it.trim() }.filter { it.isNotBlank() }
    val batch = titles.size > 1
    RaftUnifiedDialog(
        title =
            stringResource(
                if (batch) Res.string.tasks_create_from_message_title_many else Res.string.tasks_create_from_message_title_one
            ),
        onDismiss = { if (!submitting) onDismiss() },
        dismissEnabled = !submitting,
        dismissOnOutsideTap = false,
        keyboardAvoidanceEnabled = true,
        contentPadding = RaftAppSpacing.Md,
        contentSpacing = RaftAppSpacing.Md,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
        ) {
            // web: `rounded-control border border-line-hairline bg-layer-card px-3 py-2 text-footnote`.
            val quoteShape = RoundedCornerShape(RaftRadius.Control)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.layerCard, quoteShape)
                    .border(RaftStroke.Hairline, colors.lineHairline, quoteShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                RaftText(
                    text = source.senderName,
                    style = typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RaftText(
                    text = source.text,
                    style = typography.footnote,
                    color = colors.foregroundMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            errorText?.let { RaftBanner(description = it, status = RaftBannerStatus.Warning) }
            Column(verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)) {
                titles.forEachIndexed { index, title ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)) {
                        RaftTextField(
                            value = title,
                            onValueChange = { next -> titles = titles.toMutableList().also { it[index] = next } },
                            modifier = Modifier.weight(1f),
                            placeholder = stringResource(Res.string.tasks_create_title_placeholder, index + 1),
                            enabled = !submitting,
                        )
                        if (titles.size > 1) {
                            val remove = stringResource(Res.string.common_remove)
                            RaftIconButton(
                                onClick = { titles = titles.filterIndexed { i, _ -> i != index } },
                                tooltip = remove,
                                variant = RaftButtonVariant.Ghost,
                                enabled = !submitting,
                            ) {
                                RaftGlyphIcon(RaftGlyph.X, size = 16.dp, color = colors.foregroundMuted)
                            }
                        }
                    }
                }
                RaftButton(
                    text = stringResource(Res.string.tasks_action_add_another),
                    onClick = { titles = titles + "" },
                    enabled = !submitting,
                    leadingIcon = { RaftGlyphIcon(RaftGlyph.Plus, size = 16.dp) },
                )
            }
        }
        RaftDialogFooter {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = { if (!submitting) onDismiss() },
                enabled = !submitting,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
            )
            RaftButton(
                text =
                    when {
                        submitting -> stringResource(Res.string.tasks_create_action_creating)
                        // web `task.create.submit`: "Create" for one filled title, "Create N Tasks" for more.
                        filled.size > 1 -> stringResource(Res.string.tasks_create_submit_many, filled.size)
                        else -> stringResource(Res.string.tasks_create_action)
                    },
                onClick = { onSubmit(filled) },
                enabled = filled.isNotEmpty() && !submitting,
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
            )
        }
    }
}

/** The create step's outcome: the tasks made, or the server's `error` text (null: none given). */
internal sealed interface TaskCreateFromMessageOutcome {
    data class Created(val tasks: List<build.raft.app.model.TaskSummary>) : TaskCreateFromMessageOutcome

    /** [blocker]: a 401/403 answer (the caller may route a lapsed session to sign-in). */
    data class Failed(
        val serverMessage: String?,
        val blocker: build.raft.app.data.PermissionBlocker? = null,
    ) : TaskCreateFromMessageOutcome
}

/**
 * Web `CreateTaskDialog.handleSubmit`: POST the titles under the message; the created tasks fold
 * into the shared projection at once (web waits for `task:created`; the fold agrees with it).
 * A failure keeps the server's words (400 "Cannot create more than 50 tasks at once", 403
 * `task_v2_not_enabled`, ...).
 */
internal suspend fun submitTasksFromMessage(
    source: TaskCreateSourceMessage,
    titles: List<String>,
    serverId: String,
    tasksDataSource: build.raft.app.data.SharedTasksDataSource,
    tasksDataStore: build.raft.app.data.SharedTasksDataStore?,
): TaskCreateFromMessageOutcome =
    when (
        val result =
            tasksDataSource.createTasksFromMessage(
                serverId = build.raft.app.model.ServerId(serverId),
                channelId = source.channelId,
                titles = titles,
                sourceMessageId = source.messageId,
            )
    ) {
        is build.raft.app.data.DataResult.Content -> {
            result.data.tasks.forEach { task -> tasksDataStore?.upsertTaskForCurrentOwner(serverId, task) }
            TaskCreateFromMessageOutcome.Created(result.data.tasks)
        }
        is build.raft.app.data.DataResult.Error -> TaskCreateFromMessageOutcome.Failed(result.error.serverMessage)
        is build.raft.app.data.DataResult.NoPermission -> TaskCreateFromMessageOutcome.Failed(result.blocker.serverMessage, result.blocker)
        is build.raft.app.data.DataResult.Empty -> TaskCreateFromMessageOutcome.Failed(null)
    }

/**
 * The page's "Create Task from Message" state: which message it is open for, whether its create is
 * in flight, and the failure to show. While a create runs nothing closes it (web `submitting`).
 */
internal class TaskCreateFromMessageFlow {
    var source by mutableStateOf<TaskCreateSourceMessage?>(null)
        private set
    var submitting by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set

    fun open(source: TaskCreateSourceMessage) {
        if (submitting) return
        this.source = source
        errorText = null
    }

    /** Closes it unless a create is in flight; true when it closed. */
    fun dismiss(): Boolean {
        if (submitting) return false
        source = null
        errorText = null
        return true
    }

    /**
     * Runs the create ([submitTasksFromMessage]); null when nothing is open or one already runs.
     * Created: it closes (web just closes). Failed: it stays open with the server's words, else
     * [fallbackError].
     */
    suspend fun submit(
        titles: List<String>,
        serverId: String,
        tasksDataSource: build.raft.app.data.SharedTasksDataSource,
        tasksDataStore: build.raft.app.data.SharedTasksDataStore?,
        fallbackError: String,
    ): TaskCreateFromMessageOutcome? {
        val current = source ?: return null
        if (submitting) return null
        submitting = true
        errorText = null
        try {
            val outcome = submitTasksFromMessage(current, titles, serverId, tasksDataSource, tasksDataStore)
            when (outcome) {
                is TaskCreateFromMessageOutcome.Created -> source = null
                is TaskCreateFromMessageOutcome.Failed -> errorText = outcome.serverMessage ?: fallbackError
            }
            return outcome
        } finally {
            submitting = false
        }
    }
}
