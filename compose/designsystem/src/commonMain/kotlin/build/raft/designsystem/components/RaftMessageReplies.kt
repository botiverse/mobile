package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius

// raft-ui MessageReplies (raft-design packages/ui/src/components/message-replies/
// message-replies.recipe.ts `messageReplies`, message-replies.md) as the web draws it
// (slock packages/web/src/components/message/InlineThreadReplies.tsx): the thread under a
// message, a gray block with the reply count, a pink count of new replies, a draft mark and
// up to three reply previews, that opens the thread as one control.

object RaftMessageRepliesDefaults {
    /** The block sits 8dp under the message body (`mt-2`). */
    val TopSpacing = 8.dp

    /** At most 480dp wide (`max-w-[480px]`). */
    val MaxWidth = 480.dp

    /** A reply's time: Space Mono `mono-meta`, tabular figures. */
    val TimeStyle: TextStyle
        @Composable get() = RaftTheme.typography.monoMeta.copy(fontFeatureSettings = "tnum")

    /** A reply's time ink, `foreground-hint`. */
    val TimeInk: Color
        @Composable get() = RaftTheme.colors.foregroundHint
}

/**
 * raft-ui `MessageReplies`: the thread summary under a message. Full width up to 480dp,
 * `fill-muted` with no border, `rounded-container` (10), 10 x 8dp padding, the summary and
 * the [content] rows 4dp apart; a mouse hover lays `overlay-subtle` over it and a press
 * `overlay-medium`. The whole block is one button ([onClick] opens the thread); nothing
 * inside is focusable. Place it [RaftMessageRepliesDefaults.TopSpacing] under the body.
 *
 * The summary (`footnote` bold, items 6dp apart, `foreground-muted`, ink while there are new
 * replies): [countLabel] ("4 replies"), then [newCount] as the pink count sticker (Badge
 * solid accent: 20dp, `attention`, Space Mono bold, ink edge and die-cut ring) read as
 * [newCountLabel] ("1 new"), then, with [draftLabel], a middle dot and a 12dp pencil before
 * "Draft". No chevron. [content] holds up to three [RaftMessageRepliesItem]s.
 */
@Composable
fun RaftMessageReplies(
    countLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    newCount: Int = 0,
    newCountLabel: String? = null,
    draftLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed = raftIsPressed(source)
    val hovered = raftIsHovered(source)
    val focused = raftIsFocused(source)
    val shape = RoundedCornerShape(RaftRadius.Container)
    Column(
        modifier =
            modifier
                .widthIn(max = RaftMessageRepliesDefaults.MaxWidth)
                .fillMaxWidth()
                .raftFocusRing(focused, RaftRadius.Container, colors)
                .clip(shape)
                .background(colors.fillMuted, shape)
                .raftPressOverlay(pressed || hovered, if (pressed) colors.fillMedium else colors.fillSubtle, RaftRadius.Container)
                .raftPointerHover(source)
                .clickable(source, indication = null, role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RaftMessageRepliesSummary(countLabel, newCount, newCountLabel, draftLabel)
        content()
    }
}

@Composable
private fun RaftMessageRepliesSummary(
    countLabel: String,
    newCount: Int,
    newCountLabel: String?,
    draftLabel: String?,
) {
    val colors = RaftTheme.colors
    val ink = if (newCount > 0) colors.foreground else colors.foregroundMuted
    val style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftText(countLabel, style = style, color = ink, maxLines = 1, softWrap = false)
        if (newCount > 0) {
            Box(Modifier.clearAndSetSemantics { contentDescription = newCountLabel ?: newCount.toString() }) {
                RaftBadge(text = newCount.toString(), appearance = RaftBadgeAppearance.Solid, variant = RaftBadgeVariant.Accent)
            }
        }
        if (draftLabel != null) {
            RaftText("·", modifier = Modifier.clearAndSetSemantics {}, style = style, color = ink, maxLines = 1)
            Box(Modifier.padding(horizontal = 2.dp)) {
                RaftGlyphIcon(RaftGlyph.Pencil, size = 12.dp, color = ink)
            }
            RaftText(draftLabel, style = style, color = ink, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * raft-ui `MessageRepliesItem`: one reply preview, on one line, items 6dp apart and centred:
 * the small [avatar], the [sender] (`footnote` semibold, `foreground-muted`), the start of
 * the reply [content] (`footnote`, `foreground-hint`) and its [time] at the end (Space Mono
 * `mono-meta` in `foreground-hint`: [RaftMessageRepliesItemTime], or the host's own
 * timestamp in [RaftMessageRepliesDefaults.TimeStyle]). The time and the avatar keep their
 * width; the content gives way first, then the name; both truncate.
 */
@Composable
fun RaftMessageRepliesItem(
    sender: String,
    content: String,
    modifier: Modifier = Modifier,
    avatar: (@Composable () -> Unit)? = null,
    time: (@Composable () -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Layout(
        modifier = modifier,
        content = {
            if (avatar != null) Box(Modifier.layoutId(ReplyAvatar)) { avatar() }
            RaftText(
                sender,
                modifier = Modifier.layoutId(ReplySender),
                style = typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foregroundMuted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            RaftText(
                content,
                modifier = Modifier.layoutId(ReplyContent),
                style = typography.footnote,
                color = colors.foregroundHint,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (time != null) Box(Modifier.layoutId(ReplyTime)) { time() }
        },
    ) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val avatarPlaceable = measurables.firstOrNull { it.layoutId == ReplyAvatar }?.measure(loose)
        val timePlaceable = measurables.firstOrNull { it.layoutId == ReplyTime }?.measure(loose)
        val gaps = gap * (1 + (if (avatarPlaceable != null) 1 else 0) + (if (timePlaceable != null) 1 else 0))
        val fixed = (avatarPlaceable?.width ?: 0) + (timePlaceable?.width ?: 0) + gaps
        val bounded = constraints.maxWidth != Constraints.Infinity
        val room = if (bounded) (constraints.maxWidth - fixed).coerceAtLeast(0) else Constraints.Infinity
        val senderPlaceable = measurables.first { it.layoutId == ReplySender }.measure(loose.copy(maxWidth = room))
        val contentRoom = if (bounded) (room - senderPlaceable.width).coerceAtLeast(0) else Constraints.Infinity
        val contentPlaceable = measurables.first { it.layoutId == ReplyContent }.measure(loose.copy(maxWidth = contentRoom))
        val width = if (bounded) constraints.maxWidth else fixed + senderPlaceable.width + contentPlaceable.width
        val height =
            listOfNotNull(avatarPlaceable, senderPlaceable, contentPlaceable, timePlaceable)
                .maxOf { it.height }
                .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var x = 0
            avatarPlaceable?.let {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gap
            }
            senderPlaceable.placeRelative(x, (height - senderPlaceable.height) / 2)
            x += senderPlaceable.width + gap
            contentPlaceable.placeRelative(x, (height - contentPlaceable.height) / 2)
            timePlaceable?.let { it.placeRelative(width - it.width, (height - it.height) / 2) }
        }
    }
}

/** A reply's time as text, in [RaftMessageRepliesDefaults.TimeStyle] and `foreground-hint`. */
@Composable
fun RaftMessageRepliesItemTime(text: String, modifier: Modifier = Modifier) {
    RaftText(
        text,
        modifier = modifier,
        style = RaftMessageRepliesDefaults.TimeStyle,
        color = RaftMessageRepliesDefaults.TimeInk,
        maxLines = 1,
        softWrap = false,
    )
}

private const val ReplyAvatar = "avatar"
private const val ReplySender = "sender"
private const val ReplyContent = "content"
private const val ReplyTime = "time"
