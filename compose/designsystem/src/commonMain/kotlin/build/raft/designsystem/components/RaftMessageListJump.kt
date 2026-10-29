package build.raft.designsystem.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

// raft-ui MessageList > Jump controls (`@botiverse/raft-ui/wip`: MessageListJumpControls,
// MessageListJumpPill, MessageListJumpPillGroup, MessageListJumpPillDismiss;
// raft-design packages/ui/src/components/message-list/message-list-jump.tsx and
// message-list.recipe.ts `messageListJump`). Every pill is one component built from the
// Button recipe at `outline` / `md`, so "Back to Bottom", "3 New Messages" and
// "Next Unread Thread (3)" share their size, type, radius, stroke and icon.

/** The edge of the message list the jump controls float on. */
enum class RaftMessageListJumpSide {
    /** "Back to Bottom", "N New Messages", "Next Unread Thread (N)". */
    Bottom,

    /** "Earliest Unread Thread (N)": a pill that goes up, alone at the top. */
    Top,
}

/** The space between the controls and the list's edge (`bottom-3` / `top-3`) and its sides (`max-w-[calc(100%-1.5rem)]`). */
internal val RaftMessageListJumpEdgeInset = 12.dp

/** Between two pills side by side (`gap-x-2`). */
internal val RaftMessageListJumpRowGap = 8.dp

/** Between two stacked pills (`gap-y-3`), so their 44dp hit areas don't overlap. */
internal val RaftMessageListJumpStackGap = 12.dp

/**
 * raft-ui `MessageListJumpControls`: where the jump pills float over a message list, centred
 * on its [side] edge, 12dp from it and from the sides. Align it in the list's parent
 * (`Modifier.align(Alignment.BottomCenter)`), and add the height of whatever else floats on
 * that edge (raft-ui `--message-list-bottom-inset`) as padding on [modifier].
 *
 * Pills sit in one row, 8dp apart, while they fit; otherwise (a phone, usually) they stack
 * 12dp apart with the first one nearest the edge (`flex-wrap-reverse` at the bottom). Show
 * at most two at the bottom and one at the top. No shadow and no animation: a pill appears
 * and disappears at once.
 */
@Composable
fun RaftMessageListJumpControls(
    modifier: Modifier = Modifier,
    side: RaftMessageListJumpSide = RaftMessageListJumpSide.Bottom,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier =
            modifier.padding(
                start = RaftMessageListJumpEdgeInset,
                end = RaftMessageListJumpEdgeInset,
                top = if (side == RaftMessageListJumpSide.Top) RaftMessageListJumpEdgeInset else 0.dp,
                bottom = if (side == RaftMessageListJumpSide.Bottom) RaftMessageListJumpEdgeInset else 0.dp,
            ),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val rowGap = RaftMessageListJumpRowGap.roundToPx()
        val stackGap = RaftMessageListJumpStackGap.roundToPx()
        val layout =
            raftMessageListJumpLayout(
                sizes = placeables.map { it.width to it.height },
                maxWidth = constraints.maxWidth,
                rowGap = rowGap,
                stackGap = stackGap,
                side = side,
            )
        val width = layout.width.coerceIn(constraints.minWidth, constraints.maxWidth.coerceAtLeast(constraints.minWidth))
        val height =
            layout.height.coerceIn(
                constraints.minHeight,
                if (constraints.maxHeight == Constraints.Infinity) Int.MAX_VALUE else constraints.maxHeight.coerceAtLeast(constraints.minHeight),
            )
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val (x, y) = layout.positions[index]
                placeable.placeRelative(x + (width - layout.width) / 2, y)
            }
        }
    }
}

/** Where [raftMessageListJumpLayout] puts each pill, and the box they take. */
internal data class RaftMessageListJumpLayout(
    val width: Int,
    val height: Int,
    val stacked: Boolean,
    val positions: List<Pair<Int, Int>>,
)

/**
 * The jump controls' layout, pure so it can be tested: one centred row with [rowGap] while
 * every pill fits in [maxWidth], else one centred column with [stackGap], the first pill
 * nearest the [side] edge.
 */
internal fun raftMessageListJumpLayout(
    sizes: List<Pair<Int, Int>>,
    maxWidth: Int,
    rowGap: Int,
    stackGap: Int,
    side: RaftMessageListJumpSide,
): RaftMessageListJumpLayout {
    if (sizes.isEmpty()) return RaftMessageListJumpLayout(0, 0, stacked = false, positions = emptyList())
    val rowWidth = sizes.sumOf { it.first } + rowGap * (sizes.size - 1)
    if (sizes.size == 1 || rowWidth <= maxWidth) {
        val height = sizes.maxOf { it.second }
        var x = 0
        val positions =
            sizes.map { (w, h) ->
                val position = x to (height - h) / 2
                x += w + rowGap
                position
            }
        return RaftMessageListJumpLayout(rowWidth, height, stacked = false, positions = positions)
    }
    val width = sizes.maxOf { it.first }
    val height = sizes.sumOf { it.second } + stackGap * (sizes.size - 1)
    // Bottom: the first pill sits lowest; top: the first pill sits highest.
    val order = if (side == RaftMessageListJumpSide.Bottom) sizes.indices.reversed() else sizes.indices
    val positions = MutableList(sizes.size) { 0 to 0 }
    var y = 0
    for (index in order) {
        val (w, h) = sizes[index]
        positions[index] = (width - w) / 2 to y
        y += h + stackGap
    }
    return RaftMessageListJumpLayout(width, height, stacked = true, positions = positions)
}

/** Set inside a [RaftMessageListJumpPillGroup]: the pill gives its end edge to the dismiss. */
private val LocalRaftMessageListJumpPillGrouped = staticCompositionLocalOf { false }

/**
 * raft-ui `MessageListJumpPill`: one jump action, a Button at `outline` / `md`. 32dp tall,
 * a 1.5dp `line-strong` edge on `layer-card`, `radius-control`, the label in the 32dp
 * button's type (13/18 bold), a 16dp [icon] before it and 12dp of padding on both sides
 * (the web pill's icon carries no `data-icon`, so the tighter icon side does not apply).
 * Pressed (or hovered by a mouse) it lays `fill-pressed` over the card; its touch target
 * is widened to 44dp. Label in Title Case with its count: "3 New Messages",
 * "Next Unread Thread (3)".
 *
 * [busy] (raft-ui States › Busy, web ThreadUnreadJumpPill while its target loads): the `sm`
 * pixel spinner takes the icon's 16dp place and [text] should say what is happening ("Loading
 * Thread…"); taps do nothing and semantics say disabled, but the pill keeps its place and focus.
 */
@Composable
fun RaftMessageListJumpPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: RaftGlyph? = RaftGlyph.ArrowDown,
    interactionSource: MutableInteractionSource? = null,
    busy: Boolean = false,
) {
    val variant = RaftButtonVariant.Default
    val look = raftButtonLook(variant, enabled = true, frameless = false, colors = RaftTheme.colors)
    val metrics = raftButtonMetrics(RaftButtonSize.Md, variant)
    val textStyle = raftButtonTextStyle(RaftButtonSize.Md, variant, RaftTheme.typography)
    val grouped = LocalRaftMessageListJumpPillGrouped.current
    val padding = metrics.paddingX + RaftStroke.Control
    RaftButtonBox(
        look = look,
        onClick = { if (!busy) onClick() },
        modifier = modifier.height(metrics.height).semantics { if (busy) disabled() },
        enabled = true,
        loading = false,
        loadingLabel = "",
        interactionSource = interactionSource,
        widenTouchTarget = true,
        join = if (grouped) RaftButtonJoin.End else RaftButtonJoin.None,
    ) {
        Row(
            modifier = Modifier.padding(start = padding, end = padding),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                // DS Spinner › Size: in place of a 16px icon, `sm`; the label says it already.
                Box(Modifier.size(metrics.iconSize), contentAlignment = Alignment.Center) {
                    RaftSpinner(size = RaftSpinnerSize.Sm, color = look.ink, contentDescription = "")
                }
            } else if (icon != null) {
                RaftGlyphIcon(icon, size = metrics.iconSize, color = look.ink)
            }
            RaftText(
                text = text,
                style = textStyle,
                color = look.ink,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/**
 * raft-ui `MessageListJumpPillGroup`: a [RaftMessageListJumpPill] with its
 * [RaftMessageListJumpPillDismiss] joined to its end on one shared edge, like a split button.
 * Give a pill a dismiss only when the person may not want to go where it points (unread
 * threads), never Back to Bottom.
 */
@Composable
fun RaftMessageListJumpPillGroup(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalRaftMessageListJumpPillGrouped provides true) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/**
 * raft-ui `MessageListJumpPillDismiss`: the 32dp square (`icon-md`) at the end of a
 * [RaftMessageListJumpPillGroup], with no start edge of its own (the pill's edge is shared),
 * a 16dp ✕ in `foreground-muted` (`foreground` while pressed). [label] names it for screen
 * readers, in Title Case ("Dismiss"). Its 44dp touch target reaches past its end, never
 * over the pill.
 */
@Composable
fun RaftMessageListJumpPillDismiss(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val base = raftButtonLook(RaftButtonVariant.Default, enabled = true, frameless = false, colors = RaftTheme.colors)
    val isPressed = raftIsPressed(source)
    val isHovered = raftIsHovered(source)
    val pressed = isPressed || isHovered
    val ink = if (pressed) RaftTheme.colors.foreground else RaftTheme.colors.foregroundMuted
    RaftButtonBox(
        look = base.copy(ink = ink),
        onClick = onClick,
        modifier = modifier.size(RaftIconButtonSize.Md.box).semantics { contentDescription = label },
        enabled = true,
        loading = false,
        loadingLabel = "",
        interactionSource = source,
        widenTouchTarget = true,
        join = RaftButtonJoin.Start,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            RaftGlyphIcon(RaftGlyph.X, size = 16.dp, color = ink)
        }
    }
}
