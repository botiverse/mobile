package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import build.raft.app.data.DataResult
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.toThreadAttachmentCommentsFailureProjection
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskSummary
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.designsystem.components.RaftTaskStatusBadge
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/**
 * Where opening a task lands when it is not a page of its own yet (slock web `useOpenTask`):
 * - [Pending]: a task that opens as its conversation but has none yet (a pre-v2 task with the
 *   flag on, or a v2 task created to have one). Web opens the task dialog empty — "opening does
 *   not create" (`TaskConversationDialog`) — and the first send creates the conversation
 *   (`threadStore.ensureTaskModalThreadChannel`: POST /tasks/:id/conversation, then the message).
 * - [Legacy]: a legacy orphan (`isLegacy`), shown read-only (web `LegacyTaskPanel`).
 */
internal sealed interface TaskOpenSurface {
    val task: TaskSummary

    data class Pending(override val task: TaskSummary) : TaskOpenSurface

    data class Legacy(override val task: TaskSummary) : TaskOpenSurface
}

/** The surface [task] opens on, or null when it opens a page ([TaskOpenTarget] Conversation / ResolveConversation / HostThread). */
internal fun TaskSummary.taskOpenSurface(taskV2Enabled: Boolean): TaskOpenSurface? =
    when (taskOpenTarget(taskV2Enabled)) {
        TaskOpenTarget.PendingConversation -> TaskOpenSurface.Pending(this)
        TaskOpenTarget.LegacyPanel -> TaskOpenSurface.Legacy(this)
        else -> null
    }

/** The first send into a task without a conversation: [Started] on its conversation, or [Failed] with the server's words. */
internal sealed interface TaskConversationStart {
    data class Started(val conversationChannelId: ChannelId) : TaskConversationStart

    /** [serverMessage]: the server's `error` text when it gave one (web shows it), else null. */
    data class Failed(val serverMessage: String?) : TaskConversationStart
}

/**
 * Web `ensureTaskModalThreadChannel` + the send: POST /tasks/:id/conversation (this server's local
 * conversation; creating it broadcasts `task:updated`), then the message into it. Only a send
 * creates the conversation; opening never does.
 */
internal suspend fun startTaskConversation(
    task: TaskSummary,
    serverId: ServerId,
    body: String,
    tasksDataSource: SharedTasksDataSource,
    send: suspend (channelId: String, body: String) -> ThreadApiResult<*>,
): TaskConversationStart {
    val text = body.trim()
    if (text.isEmpty()) return TaskConversationStart.Failed(null)
    val conversation =
        when (val opened = tasksDataSource.openTaskConversation(serverId, task.id)) {
            is DataResult.Content -> opened.data.conversationChannelId
            is DataResult.Error -> return TaskConversationStart.Failed(opened.error.serverMessage)
            is DataResult.NoPermission -> return TaskConversationStart.Failed(opened.blocker.serverMessage)
            is DataResult.Empty -> return TaskConversationStart.Failed(null)
        }
    return when (val sent = send(conversation.value, text)) {
        is ThreadApiResult.Success -> TaskConversationStart.Started(conversation)
        is ThreadApiResult.Failure -> TaskConversationStart.Failed(sent.toThreadAttachmentCommentsFailureProjection().serverMessage)
    }
}

/** Renders [surface] (the pending conversation or the legacy panel); [onSend] runs the first send of a pending one. */
@Composable
internal fun TaskOpenSurfaceHost(
    surface: TaskOpenSurface?,
    sending: Boolean,
    errorText: String?,
    onDismiss: () -> Unit,
    onSend: (TaskSummary, String) -> Unit,
) {
    when (surface) {
        is TaskOpenSurface.Pending ->
            TaskConversationPendingSheet(
                task = surface.task,
                sending = sending,
                errorText = errorText,
                onDismiss = onDismiss,
                onSend = { text -> onSend(surface.task, text) },
            )
        is TaskOpenSurface.Legacy -> TaskLegacyPanelSheet(task = surface.task, onDismiss = onDismiss)
        null -> Unit
    }
}

/**
 * A task conversation before its first message (web `TaskConversationDialog` with no conversation
 * yet): the task's status and assignee, its title and description, the empty state ("No discussion
 * in this task yet", and for a pre-v2 task where its earlier discussion is), and the composer.
 * Sending creates the conversation; [errorText] is the failure (the server's words when it gave
 * them). Attachments, mentions and Follow wait for the conversation page that opens after the
 * first send.
 */
@Composable
internal fun TaskConversationPendingSheet(
    task: TaskSummary,
    sending: Boolean,
    errorText: String?,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var draft by remember(task.id) { mutableStateOf("") }
    RaftDialogSheet(
        onDismissRequest = { if (!sending) onDismiss() },
        title = task.taskRefTitle(),
        commitLabel = stringResource(Res.string.task_conversation_send),
        onCommit = { onSend(draft) },
        commitEnabled = draft.isNotBlank() && !sending,
        commitLoading = sending,
        closeEnabled = !sending,
    ) {
        TaskConversationPendingBody(task, draft, { draft = it }, sending, errorText, Modifier.weight(1f, fill = false))
    }
}

/** The pending conversation's body (the sheet's content; screenshot tests frame it themselves). */
@Composable
internal fun TaskConversationPendingBody(
    task: TaskSummary,
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    errorText: String?,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    run {
        Column(
            modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TaskFacts(task)
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RaftText(
                    text = stringResource(Res.string.task_conversation_empty_title),
                    style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                )
                if (task.hostMessageId != null) {
                    RaftText(
                        text = stringResource(Res.string.task_conversation_empty_body),
                        style = typography.callout,
                        color = colors.foregroundMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            errorText?.let { RaftBanner(description = it, status = RaftBannerStatus.Warning) }
            RaftTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(Res.string.task_conversation_composer_placeholder),
                enabled = !sending,
            )
        }
    }
}

/**
 * A legacy task (web `LegacyTaskPanel`): read-only — its status, title and description, who created
 * it and who has it, and the read-only notice. Posting is not possible.
 */
@Composable
internal fun TaskLegacyPanelSheet(task: TaskSummary, onDismiss: () -> Unit) {
    RaftDialogSheet(
        onDismissRequest = onDismiss,
        title = taskLegacyPanelTitle(task),
        commitLabel = null,
        onCommit = {},
    ) {
        TaskLegacyPanelBody(task, Modifier.weight(1f, fill = false))
    }
}

@Composable
internal fun taskLegacyPanelTitle(task: TaskSummary): String =
    stringResource(Res.string.task_legacy_heading, task.taskNumber?.toString() ?: "?")

/** The legacy panel's body (the sheet's content; screenshot tests frame it themselves). */
@Composable
internal fun TaskLegacyPanelBody(task: TaskSummary, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val unknown = stringResource(Res.string.task_legacy_unknown)
    run {
        Column(
            modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RaftTaskStatusBadge(status = task.status.raftTaskStatus(), text = task.status.localizedLabel())
            LegacyCard {
                LegacyLabel(stringResource(Res.string.task_legacy_title))
                RaftText(task.title, style = typography.headline, color = colors.foreground)
                LegacyLabel(stringResource(Res.string.task_legacy_description), Modifier.padding(top = 12.dp))
                RaftText(
                    task.description?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.task_legacy_no_description),
                    style = typography.callout,
                    color = colors.foreground,
                )
            }
            LegacyCard {
                LegacyProperty(stringResource(Res.string.task_legacy_created_by), task.creator?.displayName?.let { "@$it" } ?: unknown)
                LegacyProperty(
                    stringResource(Res.string.task_legacy_assignee),
                    task.assignee?.displayName?.let { "@$it" } ?: stringResource(Res.string.tasks_assignee_unassigned),
                )
            }
            RaftBanner(
                title = stringResource(Res.string.task_legacy_read_only),
                description = stringResource(Res.string.task_legacy_read_only_hint),
                status = RaftBannerStatus.Info,
            )
        }
    }
}

@Composable
private fun TaskFacts(task: TaskSummary) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RaftTaskStatusBadge(status = task.status.raftTaskStatus(), text = task.status.localizedLabel())
            RaftText(
                text = task.assignee?.displayName?.let { "@$it" } ?: stringResource(Res.string.tasks_assignee_unassigned),
                style = typography.caption,
                color = colors.foregroundMuted,
                maxLines = 1,
            )
        }
        RaftText(task.title, style = typography.headline, color = colors.foreground)
        task.description?.trim()?.takeIf { it.isNotEmpty() }?.let {
            RaftText(it, style = typography.callout, color = colors.foregroundMuted)
        }
    }
}

@Composable
private fun LegacyCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(RaftRadius.Container)
    Column(
        Modifier
            .fillMaxWidth()
            .background(RaftTheme.colors.layerCard, shape)
            .border(RaftStroke.Hairline, RaftTheme.colors.lineHairline, shape)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun LegacyLabel(text: String, modifier: Modifier = Modifier) {
    RaftText(
        text,
        modifier = modifier.padding(bottom = 4.dp),
        style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
        color = RaftTheme.colors.foregroundHint,
    )
}

@Composable
private fun LegacyProperty(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftText(
            label,
            modifier = Modifier.width(112.dp),
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
            color = RaftTheme.colors.foregroundHint,
        )
        RaftText(value, style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
    }
}

/** `#N` and the title, as the task conversation's bar names it. */
internal fun TaskSummary.taskRefTitle(): String = taskNumber?.let { "#$it $title" } ?: title

/**
 * One open per task at a time: a second tap while the first is still resolving (the joint POST)
 * is dropped, and so is a repeat tap within [repeatWindowMs] of a finished open, so a double tap
 * neither POSTs twice nor pushes the same page twice. [nowMs] is injectable for tests.
 */
internal class TaskOpenGate(
    private val repeatWindowMs: Long = 700L,
    private val nowMs: () -> Long = { TaskOpenGateEpoch.elapsedNow().inWholeMilliseconds },
) {
    private var inFlight: String? = null
    private var lastTaskId: String? = null
    private var lastAtMs: Long = Long.MIN_VALUE

    fun tryBegin(taskId: String): Boolean {
        if (inFlight != null) return false
        val now = nowMs()
        if (taskId == lastTaskId && lastAtMs != Long.MIN_VALUE && now - lastAtMs < repeatWindowMs) return false
        inFlight = taskId
        return true
    }

    fun finish(taskId: String) {
        if (inFlight != taskId) return
        inFlight = null
        lastTaskId = taskId
        lastAtMs = nowMs()
    }
}

private val TaskOpenGateEpoch = kotlin.time.TimeSource.Monotonic.markNow()
