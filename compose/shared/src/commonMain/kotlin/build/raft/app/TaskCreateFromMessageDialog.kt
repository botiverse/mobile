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
 * non-blank titles; [failed] shows web's "Failed to create task" banner.
 */
@Composable
internal fun TaskCreateFromMessageDialog(
    source: TaskCreateSourceMessage,
    submitting: Boolean,
    failed: Boolean,
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
        onDismiss = onDismiss,
        dismissEnabled = !submitting,
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
            if (failed) {
                RaftBanner(description = stringResource(Res.string.tasks_create_failed), status = RaftBannerStatus.Warning)
            }
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
                onClick = onDismiss,
                enabled = !submitting,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
            )
            RaftButton(
                text =
                    when {
                        submitting -> stringResource(Res.string.tasks_create_action_creating)
                        batch -> stringResource(Res.string.tasks_create_submit_many, filled.size)
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
