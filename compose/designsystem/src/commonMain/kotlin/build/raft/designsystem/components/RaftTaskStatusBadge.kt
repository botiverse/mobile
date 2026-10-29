package build.raft.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors

/** raft-ui `TASK_STATUS_IDS`: the five task statuses, each with its `--task-status-*` colours. */
enum class RaftTaskStatus {
    Todo,
    InProgress,
    InReview,
    Done,
    Closed,
}

/** raft-ui TaskChip `size`: `default` (22dp) or `sm` (20dp, beside `sm` badges in a row's marks). */
enum class RaftTaskStatusBadgeSize(internal val height: Dp, internal val horizontalPadding: Dp, internal val gap: Dp) {
    Md(height = 22.dp, horizontalPadding = 7.dp, gap = 6.dp),
    Sm(height = 20.dp, horizontalPadding = 5.dp, gap = 4.dp),
}

/** `--task-status-*`: the status pixel's fill. In progress is one value in both themes (palette). */
fun RaftTaskStatus.pixelColor(colors: RaftUiThemeColors): Color =
    when (this) {
        RaftTaskStatus.Todo -> colors.taskStatusTodo
        RaftTaskStatus.InProgress -> RaftUiPalette.taskStatusInProgress
        RaftTaskStatus.InReview -> colors.taskStatusInReview
        RaftTaskStatus.Done -> colors.taskStatusDone
        RaftTaskStatus.Closed -> colors.taskStatusClosed
    }

/** `--task-status-*-soft`: the badge's wash. */
fun RaftTaskStatus.softColor(colors: RaftUiThemeColors): Color =
    when (this) {
        RaftTaskStatus.Todo -> colors.taskStatusTodoSoft
        RaftTaskStatus.InProgress -> colors.taskStatusInProgressSoft
        RaftTaskStatus.InReview -> colors.taskStatusInReviewSoft
        RaftTaskStatus.Done -> colors.taskStatusDoneSoft
        RaftTaskStatus.Closed -> colors.taskStatusClosedSoft
    }

private val RaftTaskStatusPixelSize = 10.dp
private val RaftTaskStatusPixelStroke = 1.5.dp
private val RaftTaskStatusPixelRadius = 1.25.dp

/**
 * raft-ui `TaskStatusIcon` / `TaskStatusPixel`: a 10dp square (8.5dp fill inside a 1.5dp
 * sticker-ink edge, 1.25dp radius, raft-design #722) in the status colour. Decorative: the
 * label beside it says the status.
 */
@Composable
fun RaftTaskStatusPixel(status: RaftTaskStatus, modifier: Modifier = Modifier) {
    val fill = status.pixelColor(RaftTheme.colors)
    Canvas(modifier.size(RaftTaskStatusPixelSize)) {
        val strokePx = RaftTaskStatusPixelStroke.toPx()
        val inset = strokePx / 2f
        val rectSize = Size(size.width - strokePx, size.height - strokePx)
        val radius = CornerRadius(RaftTaskStatusPixelRadius.toPx())
        drawRoundRect(color = fill, topLeft = Offset(inset, inset), size = rectSize, cornerRadius = radius)
        drawRoundRect(
            color = RaftUiPalette.stickerInk,
            topLeft = Offset(inset, inset),
            size = rectSize,
            cornerRadius = radius,
            style = Stroke(width = strokePx),
        )
    }
}

/**
 * raft-ui 0.37 TaskStatusBadge (a `TaskChip` showing the status name): the status `-soft`
 * wash, chip radius, no edge; the [RaftTaskStatusPixel], then [text] in 12/16 semibold
 * `foreground`, one line. [RaftTaskStatusBadgeSize.Md] is 22dp with 7dp ends and a 6dp gap,
 * [RaftTaskStatusBadgeSize.Sm] 20dp with 5dp ends and a 4dp gap.
 *
 * With [onClick] it is the status editor's trigger (web InlineBadgeEditor with `badgeStatus`):
 * a 12dp chevron in `foreground-muted` after the label, `fill-pressed` laid over it while
 * pressed or under a mouse / trackpad, a focus ring, 44dp touch height; [enabled] = false keeps the look and drops the tap
 * (a read-only status still reads as the status). [contentDescription] names the trigger for
 * screen readers ("Status: In Progress").
 */
@Composable
fun RaftTaskStatusBadge(
    status: RaftTaskStatus,
    text: String,
    modifier: Modifier = Modifier,
    size: RaftTaskStatusBadgeSize = RaftTaskStatusBadgeSize.Md,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Chip)
    val clickable = onClick != null && enabled
    val source = interactionSource ?: remember { MutableInteractionSource() }
    // task-card-status.recipe.ts: `enabled:hover:overlay-medium enabled:active:overlay-medium`.
    val overlay = rememberRaftStateOverlay(source, RaftHoverTier.Medium, enabled = clickable)
    val isFocused = raftIsFocused(source)
    val focused = clickable && isFocused
    Row(
        modifier =
            modifier
                .then(if (clickable) Modifier.raftTouchTarget(true, source, onClick!!) else Modifier)
                .raftFocusRing(focused, RaftRadius.Chip, colors)
                // At least the chip's height: large text grows it instead of clipping the label.
                .heightIn(min = size.height)
                .background(status.softColor(colors), shape)
                .raftStateOverlay(overlay, shape)
                .then(
                    if (clickable) {
                        Modifier.raftPointerHover(source).clickable(source, indication = null, role = Role.Button, onClick = onClick!!)
                    } else {
                        Modifier
                    }
                )
                .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
                .padding(horizontal = size.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(size.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftTaskStatusPixel(status)
        RaftText(
            text = text,
            style = RaftTheme.typography.caption.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) {
            RaftGlyphIcon(RaftGlyph.ChevronDown, size = 12.dp, color = colors.foregroundMuted)
        }
    }
}
