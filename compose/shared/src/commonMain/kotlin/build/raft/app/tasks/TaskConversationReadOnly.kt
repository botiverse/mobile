package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.DataResult
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftReadOnlyComposer
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * Server `code` for a historical joint task whose discussion is read-only on participant servers
 * (botiverse/slock #16739, #16748).
 */
internal const val JointTaskReadOnlyErrorCode = "joint_task_read_only"

internal object TaskConversationReadOnlyTestTags {
    const val Notice = "task-conversation-read-only-notice"
    const val Elsewhere = "task-conversation-elsewhere"
}

/**
 * Whether this task's discussion is writable here, read-only here (a historical joint task from a
 * participant server), or stays on the host server (403 `joint_task_read_only` on open).
 */
internal enum class TaskDiscussionAccess {
    Writable,
    ReadOnly,
    Elsewhere,
}

/**
 * Resolves the discussion access mode of a task surface (web `TaskConversationDialog` /
 * `ChatPanel` `isJointTaskReadOnly` + `discussionElsewhere`, slock #16739 / #16748).
 */
internal fun taskDiscussionAccess(
    readOnlyReason: String?,
    discussionElsewhere: Boolean = false,
): TaskDiscussionAccess =
    when {
        discussionElsewhere -> TaskDiscussionAccess.Elsewhere
        !readOnlyReason.isNullOrBlank() -> TaskDiscussionAccess.ReadOnly
        else -> TaskDiscussionAccess.Writable
    }

/**
 * True when `POST /tasks/:id/conversation` refused with `403 joint_task_read_only` (a historical
 * joint task whose discussion stays on the host server).
 */
internal fun DataResult<*>.isJointTaskReadOnlyRefusal(): Boolean =
    when (this) {
        is DataResult.NoPermission -> blocker.serverCode.equals(JointTaskReadOnlyErrorCode, ignoreCase = true)
        is DataResult.Error -> error.serverCode.equals(JointTaskReadOnlyErrorCode, ignoreCase = true)
        else -> false
    }

/**
 * Web `TaskConversationDialog` / `ChatPanel` read-only discussion footer (slock #16739): info
 * banner explaining why the discussion is read-only here over the one-line read-only composer.
 */
@Composable
internal fun TaskReadOnlyDiscussionComposer(modifier: Modifier = Modifier) {
    RaftDesignSystemTheme {
        Column(
            modifier = modifier.fillMaxWidth().testTag(TaskConversationReadOnlyTestTags.Notice),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RaftBanner(
                description = stringResource(Res.string.task_conversation_read_only_notice),
                status = RaftBannerStatus.Info,
            )
            RaftReadOnlyComposer(text = stringResource(Res.string.thread_composer_chat_unavailable))
        }
    }
}

/**
 * Empty state of a task's discussion pane:
 * - [TaskDiscussionAccess.Elsewhere]: "This discussion stays on the host server" (403 refusal).
 * - [TaskDiscussionAccess.ReadOnly]: "No discussion in this task yet" without inviting writing.
 * - [TaskDiscussionAccess.Writable]: "No discussion in this task yet" + pre-v2 host-thread hint.
 */
@Composable
internal fun TaskConversationEmptyState(
    hasHostMessage: Boolean,
    access: TaskDiscussionAccess,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val containerModifier =
        if (access == TaskDiscussionAccess.Elsewhere) {
            modifier.testTag(TaskConversationReadOnlyTestTags.Elsewhere)
        } else {
            modifier
        }
    Column(
        modifier = containerModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (access) {
            TaskDiscussionAccess.Elsewhere -> {
                RaftText(
                    text = stringResource(Res.string.task_conversation_elsewhere_title),
                    style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    textAlign = TextAlign.Center,
                )
                RaftText(
                    text = stringResource(Res.string.task_conversation_elsewhere_body),
                    style = typography.callout,
                    color = colors.foregroundMuted,
                    textAlign = TextAlign.Center,
                )
            }
            TaskDiscussionAccess.ReadOnly -> {
                RaftText(
                    text = stringResource(Res.string.task_conversation_empty_title),
                    style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    textAlign = TextAlign.Center,
                )
            }
            TaskDiscussionAccess.Writable -> {
                RaftText(
                    text = stringResource(Res.string.task_conversation_empty_title),
                    style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    textAlign = TextAlign.Center,
                )
                if (hasHostMessage) {
                    RaftText(
                        text = stringResource(Res.string.task_conversation_empty_body),
                        style = typography.callout,
                        color = colors.foregroundMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
