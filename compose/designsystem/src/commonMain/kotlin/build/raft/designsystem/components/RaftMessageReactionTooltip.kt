package build.raft.designsystem.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

// raft-ui MessageItem > reaction tooltip (raft-design packages/ui/src/components/message-item/
// message-reaction.recipe.ts `tooltip` / `tooltipNames` / `tooltipName` / `tooltipNameText` /
// `tooltipSeparator` / `tooltipMeta`), as web MessageItem's `ReactionReactorsTooltipBody` composes
// it: who reacted with one emoji, in the HUD tooltip under the chip.

/** raft-ui reaction tooltip metrics (message-reaction.recipe.ts, MessageItem.tsx). */
internal object RaftMessageReactionTooltipMetrics {
    /** web `<MessageReactionTooltipContent sideOffset={4}>`: chip bottom to bubble top. */
    val AnchorSpacing = 4.dp

    /** `tooltip`: `max-w-[280px]`. */
    val MaxWidth = 280.dp

    /** `tooltipNames`: `max-w-60`, `gap-y-0.5`. */
    val NamesMaxWidth = 240.dp
    val NamesRowGap = 2.dp

    /** `tooltipNameText`: `max-w-28 truncate`. */
    val NameMaxWidth = 112.dp

    /** `tooltipName` / `tooltipMeta`: `leading-5`. */
    val LineHeight = 20.sp

    /** web `<MessageReactionTooltipSeparator className="pe-[0.3em]">`. */
    val SeparatorEndPadding = 0.3f

    /** `tooltipSeparator` / `tooltipMeta`: `text-foreground-on-hud/70`. */
    const val MutedInkAlpha = 0.7f
}

/**
 * raft-ui reaction tooltip around a reaction chip ([content]): who reacted, in the dark HUD
 * bubble ([RaftTooltip]'s look: `layer-hud`, `foreground-on-hud`, `radius-chip`, 10 x 4dp
 * padding, `text-footnote` medium, `shadow-float`, the caret pointing at the chip) under the chip,
 * its start edge on the chip's (web `side="bottom" align="start" sideOffset={4}`); above it when
 * there is no room below.
 *
 * - [names]: the reactors to show, already capped by the caller (web shows 5, "You" first). Each
 *   truncates at 112dp; they wrap within 240dp, comma-separated (the comma in `foreground-on-hud`
 *   at 70%), 20dp lines 2dp apart.
 * - [moreLabel]: the rest ("+3 more"), after the last comma in the muted ink; null when every
 *   reactor is named.
 * - No [names] (none known): [emptyContent] alone, web's bare emoji.
 *
 * [open] is the host's: a long press opens it on touch, and [onDismissRequest] (a tap elsewhere)
 * closes it. A mouse or trackpad pointer over [content] opens it too, until the pointer leaves,
 * as the web's hover tooltip. Not focusable and adds no semantics: the chip carries the label.
 */
@Composable
fun RaftMessageReactionTooltip(
    names: List<String>,
    moreLabel: String?,
    open: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    emptyContent: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val preview = LocalRaftTooltipPreview.current
    var hovered by remember { mutableStateOf(false) }
    val visible = remember { MutableTransitionState(preview) }
    visible.targetState = preview || open || hovered
    val density = LocalDensity.current
    val spacing = with(density) { RaftMessageReactionTooltipMetrics.AnchorSpacing.roundToPx() }
    val frame = LocalRaftFrameInsets.current.toPx(density)
    val placed = remember(spacing) { RaftTooltipBelowStartPositionProvider(spacing) }
    val framed = remember(placed, spacing, frame) { RaftTooltipFrameProvider(placed, spacing, frame) }
    val transition = rememberTransition(visible, label = "RaftMessageReactionTooltip")
    Box(modifier.raftMouseHover { hovered = it }, propagateMinConstraints = true) {
        if (visible.currentState || visible.targetState) {
            // A page pushed over this one closes the tooltip first.
            RaftDismissOnPageCover {
                hovered = false
                onDismissRequest()
            }
            Popup(
                popupPositionProvider = framed,
                onDismissRequest = {
                    hovered = false
                    onDismissRequest()
                },
                properties = PopupProperties(focusable = false, clippingEnabled = false),
            ) {
                RaftTooltipBubble(
                    transition,
                    Modifier.raftFramedCaret(framed, RaftTheme.colors.layerHud),
                    maxWidth = RaftMessageReactionTooltipMetrics.MaxWidth,
                ) {
                    Box(Modifier.padding(horizontal = RaftTooltipMetrics.ExtraPaddingX)) {
                        RaftMessageReactionTooltipBody(names, moreLabel, emptyContent)
                    }
                }
            }
        }
        Box { content() }
    }
}

/** The tooltip's text: `MessageReactionTooltipNames` (or [emptyContent] with no [names]). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RaftMessageReactionTooltipBody(
    names: List<String>,
    moreLabel: String?,
    emptyContent: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    val style: TextStyle =
        RaftTheme.typography.footnote.copy(
            fontWeight = FontWeight.Medium,
            lineHeight = RaftMessageReactionTooltipMetrics.LineHeight,
        )
    if (names.isEmpty()) {
        Box(contentAlignment = Alignment.CenterStart) { emptyContent() }
        return
    }
    val separatorEnd = with(LocalDensity.current) { (style.fontSize * RaftMessageReactionTooltipMetrics.SeparatorEndPadding).toDp() }
    val muted = colors.foregroundOnHud.let { it.copy(alpha = it.alpha * RaftMessageReactionTooltipMetrics.MutedInkAlpha) }
    FlowRow(
        modifier = Modifier.widthIn(max = RaftMessageReactionTooltipMetrics.NamesMaxWidth),
        verticalArrangement = Arrangement.spacedBy(RaftMessageReactionTooltipMetrics.NamesRowGap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEachIndexed { index, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RaftText(
                    text = name,
                    modifier = Modifier.widthIn(max = RaftMessageReactionTooltipMetrics.NameMaxWidth),
                    style = style,
                    color = colors.foregroundOnHud,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                if (index < names.lastIndex || moreLabel != null) {
                    RaftText(
                        text = ",",
                        modifier = Modifier.padding(end = separatorEnd),
                        style = style,
                        color = muted,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        if (moreLabel != null) {
            RaftText(text = moreLabel, style = style, color = muted, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * Below the anchor with the bubble's start edge on the anchor's (Base UI `side="bottom"
 * align="start"`), [spacing] px under it; above it when only above has room (`side: "flip"`),
 * and slid back inside the window (`align: "shift"`).
 */
internal class RaftTooltipBelowStartPositionProvider(private val spacing: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = raftTooltipBelowStartPosition(anchorBounds, windowSize, popupContentSize, spacing, layoutDirection)
}

internal fun raftTooltipBelowStartPosition(
    anchor: IntRect,
    window: IntSize,
    popup: IntSize,
    spacing: Int,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
): IntOffset {
    val start = if (layoutDirection == LayoutDirection.Ltr) anchor.left else anchor.right - popup.width
    val x = start.coerceAtMost(window.width - popup.width).coerceAtLeast(0)
    val below = anchor.bottom + spacing
    val above = anchor.top - spacing - popup.height
    val y = if (below + popup.height > window.height && above >= 0) above else below
    return IntOffset(x, y)
}

/** Reports a mouse / trackpad pointer entering and leaving; consumes nothing. */
private fun Modifier.raftMouseHover(onHover: (Boolean) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.firstOrNull()?.type != PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Enter -> onHover(true)
                    PointerEventType.Exit -> onHover(false)
                }
            }
        }
    }
