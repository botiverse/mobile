package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlin.math.max
import kotlin.math.min

/**
 * The box the tasks of a message sit in, under it (task v2; slock web `TaskChipList`, variant
 * `boxed`, `COMPACT_LIST_BOXED`): a `layer-card` box with a 1dp `line-hairline` border and the
 * control radius, as wide as its longest row needs (at least 320dp, at most the message column,
 * like an attachment). Holds [RaftTaskCompactRow]s; the hairlines between them meet its border.
 */
@Composable
fun RaftTaskCompactList(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Control)
    Column(
        modifier
            .widthIn(min = RaftTaskCompactListMinWidth)
            .width(IntrinsicSize.Max)
            .clip(shape)
            .background(colors.layerCard, shape)
            .border(RaftStroke.Hairline, colors.lineHairline, shape),
        content = content,
    )
}

/**
 * One task in a [RaftTaskCompactList] (raft-design patterns/tasks.md "Rows in a compact list",
 * slock web `compactRow.ts`): [number] in Space Mono `mono-meta` hint, the [title] on one line
 * (`callout`, cut with an ellipsis) with the reply count right after it, then the [assignee]
 * and the status badge grouped at the right. Where the row is too narrow for the title's 160dp
 * and the right group, the group wraps, whole, onto a second line, still at the right.
 *
 * The row is the button ([onClick]; [contentDescription] names it): at least 44dp high with
 * 12dp ends and 6dp above and below; press lays `fill-pressed` over the whole row. [divider]
 * draws the hairline above it (every row but the first). Texts share the row's first baseline;
 * marks (the avatar) are centred.
 *
 * [replyCount] > 0 shows the size of the task's own conversation (a 12dp `message-square` and
 * the number in `mono-meta` hint); [replyCountDescription] is its spoken form. [status] null
 * hides the badge.
 */
@Composable
fun RaftTaskCompactRow(
    number: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: RaftTaskStatus? = null,
    statusText: String = "",
    assignee: (@Composable () -> Unit)? = null,
    replyCount: Int = 0,
    replyCountDescription: String? = null,
    divider: Boolean = false,
    contentDescription: String? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed = raftIsPressed(source)
    val focused = raftIsFocused(source)
    Layout(
        modifier =
            modifier
                .fillMaxWidth()
                .raftPressOverlay(pressed, raftPressOverlayColor(RaftPressSurface.Paper, colors), 0.dp)
                .raftInsetFocusOutline(focused, colors.foreground)
                .then(if (divider) Modifier.topHairline(colors.lineHairline) else Modifier)
                .clickable(source, indication = null, role = Role.Button, onClick = onClick)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics(mergeDescendants = true) {
                            this.contentDescription = contentDescription
                            role = Role.Button
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = RaftTaskCompactRowInset),
        content = {
            RaftText(
                text = number,
                style = typography.monoMeta,
                color = colors.foregroundHint,
                maxLines = 1,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftText(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = typography.callout,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (replyCount > 0) {
                    Row(
                        modifier =
                            if (replyCountDescription != null) {
                                Modifier.clearAndSetSemantics { this.contentDescription = replyCountDescription }
                            } else {
                                Modifier
                            },
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftGlyphIcon(RaftGlyph.MessageSquare, size = 12.dp, color = colors.foregroundHint)
                        RaftText(
                            text = replyCount.toString(),
                            style = typography.monoMeta,
                            color = colors.foregroundHint,
                            maxLines = 1,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                assignee?.invoke()
                if (status != null) RaftTaskStatusBadge(status = status, text = statusText)
            }
        },
    ) { measurables, constraints ->
        val gapX = 8.dp.roundToPx()
        val gapY = 4.dp.roundToPx()
        val padY = 6.dp.roundToPx()
        val minHeight = RaftTaskCompactRowMinHeight.roundToPx()
        val titleBasis = RaftTaskCompactRowTitleBasis.roundToPx()
        val (numberM, titleM, trailingM) = measurables
        val loose = Constraints()
        val number = numberM.measure(loose)
        val trailingNatural = trailingM.maxIntrinsicWidth(Constraints.Infinity)
        val titleNatural = titleM.maxIntrinsicWidth(Constraints.Infinity)
        val hasTrailing = trailingNatural > 0
        val width =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                number.width + gapX + titleNatural + if (hasTrailing) gapX + trailingNatural else 0
            }
        val afterNumber = max(0, width - number.width - gapX)
        val oneLine = !hasTrailing || afterNumber - gapX - trailingNatural >= min(titleNatural, titleBasis)
        val trailing = trailingM.measure(Constraints(maxWidth = width))
        val titleWidth =
            if (oneLine && hasTrailing) {
                min(titleNatural, max(0, afterNumber - gapX - trailing.width))
            } else {
                min(titleNatural, afterNumber)
            }
        val titleGroup = titleM.measure(Constraints(maxWidth = titleWidth))

        // One line box per line: texts on its first baseline, a part without one centred.
        fun lineOf(parts: List<Placeable>): Pair<Int, List<Int>> {
            val baselines = parts.map { it[FirstBaseline] }
            val baseline = baselines.filter { it != androidx.compose.ui.layout.AlignmentLine.Unspecified }.maxOrNull() ?: 0
            val tops = parts.mapIndexed { index, part ->
                val b = baselines[index]
                if (b == androidx.compose.ui.layout.AlignmentLine.Unspecified) 0 else baseline - b
            }
            val height = parts.indices.maxOf { tops[it] + parts[it].height }
            val centred = parts.mapIndexed { index, part ->
                if (baselines[index] == androidx.compose.ui.layout.AlignmentLine.Unspecified) (height - part.height) / 2 else tops[index]
            }
            return height to centred
        }

        val firstParts = if (oneLine && hasTrailing) listOf(number, titleGroup, trailing) else listOf(number, titleGroup)
        val (firstHeight, firstTops) = lineOf(firstParts)
        val secondHeight = if (!oneLine) trailing.height else 0
        val contentHeight = firstHeight + if (!oneLine) gapY + secondHeight else 0
        val height = max(minHeight, contentHeight + padY * 2).coerceIn(constraints.minHeight, constraints.maxHeight)
        val top = (height - contentHeight) / 2
        layout(width, height) {
            number.placeRelative(0, top + firstTops[0])
            titleGroup.placeRelative(number.width + gapX, top + firstTops[1])
            if (oneLine && hasTrailing) {
                trailing.placeRelative(width - trailing.width, top + firstTops[2])
            } else if (hasTrailing) {
                trailing.placeRelative(width - trailing.width, top + firstHeight + gapY)
            }
        }
    }
}

/**
 * A task's assignee in a [RaftTaskCompactRow] (slock web `TaskAssigneeLabel`): the [avatar]
 * (19dp, `RaftAvatarSize.Xs`, an agent's with its presence light) and the [name] in `caption`,
 * `foreground-muted`, cut at 192dp. [viewer]: the reader is the assignee, so the name ("You")
 * is `foreground` semibold.
 */
@Composable
fun RaftTaskCompactAssignee(
    name: String,
    avatar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    viewer: Boolean = false,
) {
    val colors = RaftTheme.colors
    val caption = RaftTheme.typography.caption
    Row(
        modifier.widthIn(max = RaftTaskCompactAssigneeMaxWidth),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        RaftText(
            text = name,
            style = if (viewer) caption.copy(fontWeight = FontWeight.SemiBold) else caption,
            color = if (viewer) colors.foreground else colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `min-w-[min(320px,100%)]`: the narrowest the box under a message gets. */
private val RaftTaskCompactListMinWidth = 320.dp

/** `--compact-row-inset` inside the box under a message. */
private val RaftTaskCompactRowInset = 12.dp

/** `min-h-11`: a row on a touch screen. */
private val RaftTaskCompactRowMinHeight = 44.dp

/** `flex-[1_1_10rem]`: the title keeps 160dp before the right group wraps. */
private val RaftTaskCompactRowTitleBasis = 160.dp

/** `max-w-48` on the assignee. */
private val RaftTaskCompactAssigneeMaxWidth = 192.dp
