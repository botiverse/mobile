package build.raft.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

// The side action column of iPhone Duo (Apple HIG "Designing for iPhone Duo"): on the folded
// outer screen, and on the unfolded inner screen held landscape, a page's toolbar and navigation
// buttons stand in one vertical column at the side, lined up with the camera and the status
// bar, so the content keeps the full height and the controls don't jump on fold / unfold.
// A standalone component: not RaftAppRail (the wide layout's destination rail, which stays as
// it is on the inner screen's other side).

/**
 * The window edge the column stands on, PHYSICAL: it goes where the hardware puts the camera and
 * the system strip, so it never mirrors in a right-to-left layout.
 */
enum class RaftSideActionColumnSide { Left, Right }

/**
 * A band of the column, [top] to [bottom] from the column's own top, that no button may cover:
 * the camera, the hinge, a status bar (the system's reserved regions, raft-ui's
 * `--raft-frame-inset-*`). The column's background still runs through it.
 */
@Immutable
data class RaftSideColumnKeepOut(val top: Dp, val bottom: Dp)

/**
 * One button of a [RaftSideActionColumn].
 *
 * - [id] keys it (stable across recompositions and overflow changes).
 * - [label] names it in Title Case ("Notifications"): it is the tooltip, the content
 *   description and, when it overflows, its row in the ⋯ menu.
 * - [icon] draws the glyph at the size it is given (22dp in the column, 16dp in the ⋯ menu)
 *   in [build.raft.designsystem.theme.LocalRaftContentColor].
 * - [attention] puts the pink attention pixel on the icon's corner (the bell's "new"), never a
 *   count; screen readers then read "[label], [attentionDescription]". While the action is in
 *   the ⋯ menu, the ⋯ wears the pixel instead.
 * - [keepVisible] keeps it out of the ⋯ before any [priority]; among the rest, a higher
 *   [priority] stays longer, and on a tie the action listed first does. Neither makes room
 *   that is not there, nor lifts the two-action cap
 *   ([RaftSideActionColumnDefaults.MaxActions]).
 * - [systemSymbol] names the SF Symbol ("chevron.backward") a host draws for it when the host
 *   draws the column with its own system bar buttons ([RaftSideActionHost]); null keeps the
 *   column drawing [icon]. Ignored without such a host.
 */
@Immutable
data class RaftSideAction(
    val id: String,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val attention: Boolean = false,
    val attentionDescription: String? = null,
    val keepVisible: Boolean = false,
    val priority: Int = 0,
    val systemSymbol: String? = null,
    val icon: @Composable (size: Dp) -> Unit,
)

/** [RaftSideActionColumn] metrics. */
object RaftSideActionColumnDefaults {
    /** The whole column, its hairline included: as wide as the app rail with its seam. */
    val Width: Dp = RaftAppRailDefaults.Width + RaftStroke.Hairline

    /** One button: a 44dp `lg` ghost icon button, the HIG's touch size. */
    val ItemSize: Dp = 44.dp

    /** The glyph inside a button (`lg`). */
    val IconSize: Dp = 22.dp

    /** Between buttons. */
    val ItemGap: Dp = 4.dp

    /** From the reserved bands at both ends to the first and last button. */
    val Inset: Dp = 8.dp

    /** At least this between the primary buttons and the actions. */
    val SectionGap: Dp = 16.dp

    /** The page actions a column shows, with Back or without; the rest go in the ⋯. */
    const val MaxActions: Int = 2
}

/**
 * The side action column (iPhone Duo): a page's buttons in one vertical strip at the window's
 * [side], beside the content. Transparent (the page's own canvas shows through: the side is
 * the system's place, not the frame's) with a `line-hairline` on its content-facing edge; the
 * buttons are `lg` ghost icon buttons. It draws no theme decor (that belongs to the frame:
 * [RaftAppRail], [RaftTopBar]).
 *
 * Top to bottom (the HIG's order: primary navigation, then prominent actions, the rest from
 * the bottom up):
 * - [primary], pinned at the top under [topReserved]: Back / Close, or Back and Forward. Never
 *   moved into the ⋯, even when the column is too short for them.
 * - With a primary (a pushed page), [actions] are its prominent ones: in their listed order
 *   straight under it, [RaftSideActionColumnDefaults.SectionGap] below, top-aligned.
 * - Without one (a top-level page), [actions] in their listed order stacked against the bottom,
 *   above [bottomReserved] (the tab bar).
 * - When the column cannot fit them all, the ones that stay (see [RaftSideAction.keepVisible]
 *   and [RaftSideAction.priority]) keep their slots nearest the primary (pushed page) or the
 *   bottom (top-level page) and a ⋯ ([overflowLabel]) takes the next slot (under them, or
 *   above them), opening a [RaftDropdownMenu] of the rest with their labels.
 *
 * What goes in it (raft-design #859): at most [RaftSideActionColumnDefaults.MaxActions] page
 * actions on any page, the rest in the ⋯ whatever the height (a short column moves more there).
 * A pushed page shows Back, its actions and the ⋯, and no tab bar. A top-level page shows no
 * Back, its actions, the ⋯, and the host's icon-only tab bar under them ([bottomReserved]). A
 * button that acts on only one pane of a split layout does not belong here: keep it above that
 * pane.
 *
 * The ⋯ menu is a plain [RaftDropdownMenu]: it keeps inside the window's safe area, but does not
 * know [keepOut] bands, so raft-design's 8dp from reserved regions holds only where the host
 * also reports them as safe-area insets.
 *
 * Every button is one screen-reader stop, named by its label (the ⋯ by [overflowLabel]), read
 * top to bottom.
 *
 * Direction is physical: [side], the hairline, the keep-out bands and the placement don't
 * mirror in a right-to-left layout (the icons inside the buttons render as usual). Tooltips open
 * beside the buttons toward the content (leftward from a right column) whatever the layout
 * direction; the ⋯ menu opens above or below the ⋯, kept inside the window, so from a column at
 * the window's edge it reaches toward the content too.
 *
 * Host mapping (iOS `RaftIosAdaptivePanePublisher`, 1:1):
 * - `sideColumn`: whether the page shows this column at all (outer screen, or inner screen in
 *   landscape); with it off the page keeps its top bar and tab bar.
 * - `stripEdge`: [side], as is (physical: right is [RaftSideActionColumnSide.Right] in every
 *   layout direction; the only edge the host reports today).
 * - `stripWidth` (pt = dp): [width].
 * - `nativeTabBarHeight` (pt): [bottomReserved]. The native tab bar sits in the same strip
 *   under the column; the column's buttons stay above it, its hairline runs down beside it.
 *   0 when there is none (every page with Back).
 * - `reservedRegions` (window rects, pt): [keepOut], each rect that crosses the column's
 *   width as `RaftSideColumnKeepOut(rect.top - columnTop, rect.bottom - columnTop)` where
 *   `columnTop` is the column's top in the window. A band at the very top (camera, status bar)
 *   can be [topReserved] instead; both are honoured.
 *
 * The column needs a bounded height (it fills it, like [RaftAppRail]); under an unbounded one
 * it takes the height its buttons need.
 *
 * Under a [LocalRaftSideActionHost], the host may draw the buttons with its own system bar
 * buttons (iOS 27.1's vertical bar items), and only when every [primary] and [actions] entry has
 * a [RaftSideAction.systemSymbol]: never half the host's, half ours. The column then keeps its
 * [width] and height, so the page still ends at its inner edge, but draws neither buttons nor the
 * hairline (the system strip has its own). See [RaftSideActionHost].
 */
@Composable
fun RaftSideActionColumn(
    primary: List<RaftSideAction>,
    actions: List<RaftSideAction>,
    modifier: Modifier = Modifier,
    side: RaftSideActionColumnSide = RaftSideActionColumnSide.Right,
    width: Dp = RaftSideActionColumnDefaults.Width,
    topReserved: Dp = 0.dp,
    bottomReserved: Dp = 0.dp,
    keepOut: List<RaftSideColumnKeepOut> = emptyList(),
    overflowLabel: String = RaftTheme.strings.moreActions,
) {
    if (raftSideActionHostHandoff(primary, actions, overflowLabel)) {
        // The host's system strip draws the buttons and its own edge: hold the column's place only.
        Box(modifier.fillMaxHeight().width(width))
        return
    }
    val colors = RaftTheme.colors
    val onRight = side == RaftSideActionColumnSide.Right
    val hairline = colors.lineHairline
    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .width(width)
            .drawBehind {
                val w = RaftStroke.Hairline.toPx()
                drawRect(hairline, topLeft = Offset(if (onRight) 0f else size.width - w, 0f), size = Size(w, size.height))
            },
    ) {
        val density = LocalDensity.current
        val metrics =
            with(density) {
                RaftSideColumnMetrics(
                    item = RaftSideActionColumnDefaults.ItemSize.roundToPx(),
                    gap = RaftSideActionColumnDefaults.ItemGap.roundToPx(),
                    inset = RaftSideActionColumnDefaults.Inset.roundToPx(),
                    sectionGap = RaftSideActionColumnDefaults.SectionGap.roundToPx(),
                    topReserved = topReserved.roundToPx(),
                    bottomReserved = bottomReserved.roundToPx(),
                    keepOut = keepOut.map { it.top.roundToPx() to it.bottom.roundToPx() },
                )
            }
        val maxActions = RaftSideActionColumnDefaults.MaxActions
        val height =
            if (constraints.maxHeight != Constraints.Infinity) {
                constraints.maxHeight
            } else {
                raftSideColumnNaturalHeight(metrics, primary.size, actions.size, maxActions)
            }
        val plan =
            raftSideColumnPlan(
                height = height,
                metrics = metrics,
                primaryCount = primary.size,
                ranks = actions.map { if (it.keepVisible) Int.MAX_VALUE else it.priority },
                maxActions = maxActions,
            )
        CompositionLocalProvider(LocalRaftTooltipSide provides if (onRight) RaftTooltipSide.Left else RaftTooltipSide.Right) {
            Box(
                Modifier
                    .then(if (constraints.maxHeight != Constraints.Infinity) Modifier.fillMaxSize() else Modifier.size(width, with(density) { height.toDp() }))
                    // Centre the buttons in what the hairline leaves.
                    .absolutePadding(left = if (onRight) RaftStroke.Hairline else 0.dp, right = if (onRight) 0.dp else RaftStroke.Hairline),
            ) {
                // Composed top to bottom, the order a screen reader reads them in: the ⋯ is
                // under the actions on a pushed page, above them on a top-level one.
                val overflow: @Composable () -> Unit = {
                    plan.overflowTop?.let { top ->
                        RaftSideActionOverflow(
                            actions = plan.overflowed.map { actions[it] },
                            label = overflowLabel,
                            modifier = Modifier.raftSideSlot(top),
                        )
                    }
                }
                primary.forEachIndexed { i, action ->
                    key(action.id) { RaftSideActionItem(action, Modifier.raftSideSlot(plan.primaryTops[i])) }
                }
                if (primary.isEmpty()) overflow()
                plan.shown.forEachIndexed { j, index ->
                    val action = actions[index]
                    key(action.id) { RaftSideActionItem(action, Modifier.raftSideSlot(plan.shownTops[j])) }
                }
                if (primary.isNotEmpty()) overflow()
            }
        }
    }
}

/** A full-width row [top] px down the column; its button is centred in it. */
private fun Modifier.raftSideSlot(top: Int): Modifier = absoluteOffset { IntOffset(0, top) }.fillMaxWidth()

@Composable
private fun RaftSideActionItem(action: RaftSideAction, modifier: Modifier) {
    RaftSideButton(
        label = action.label,
        onClick = action.onClick,
        enabled = action.enabled,
        attention = action.attention,
        attentionDescription = action.attentionDescription,
        modifier = modifier,
    ) { action.icon(RaftSideActionColumnDefaults.IconSize) }
}

@Composable
private fun RaftSideButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    attention: Boolean,
    attentionDescription: String?,
    modifier: Modifier,
    icon: @Composable () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        RaftIconButton(
            onClick = onClick,
            tooltip = label,
            variant = RaftButtonVariant.Ghost,
            size = RaftIconButtonSize.Lg,
            enabled = enabled,
            contentDescription = if (attention && attentionDescription != null) "$label, $attentionDescription" else label,
        ) {
            RaftSideActionIcon(RaftSideActionColumnDefaults.IconSize, attention, icon)
        }
    }
}

/** [icon] in a [size] box, with the attention pixel on its top-right corner (as [RaftAppRailItem]). */
@Composable
private fun RaftSideActionIcon(size: Dp, attention: Boolean, icon: @Composable () -> Unit) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        icon()
        if (attention) {
            RaftStatusDot(
                // Physical top-right in RTL too: the column does not mirror.
                modifier = Modifier.align(AbsoluteAlignment.TopRight).absoluteOffset(x = 4.dp, y = (-4).dp),
                size = RaftStatusSize.Sm,
                color = RaftUiPalette.attention,
            )
        }
    }
}

/** The ⋯: opens a [RaftDropdownMenu] of [actions], each row its label and icon. */
@Composable
private fun RaftSideActionOverflow(actions: List<RaftSideAction>, label: String, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val attention = actions.firstOrNull { it.attention }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Box {
            RaftIconButton(
                onClick = { open = true },
                tooltip = label,
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Lg,
                contentDescription = attention?.attentionDescription?.let { "$label, $it" } ?: label,
            ) {
                RaftSideActionIcon(RaftSideActionColumnDefaults.IconSize, attention != null) {
                    RaftGlyphIcon(RaftGlyph.Ellipsis, size = RaftSideActionColumnDefaults.IconSize)
                }
            }
            RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                actions.forEach { action ->
                    key(action.id) {
                        RaftMenuItem(
                            text = action.label,
                            onClick = {
                                open = false
                                action.onClick()
                            },
                            enabled = action.enabled,
                            leadingIcon = { RaftSideActionIcon(16.dp, action.attention) { action.icon(16.dp) } },
                        )
                    }
                }
            }
        }
    }
}

/** The column's lengths in px ([RaftSideActionColumnDefaults], the reserved bands). */
internal data class RaftSideColumnMetrics(
    val item: Int,
    val gap: Int,
    val inset: Int,
    val sectionGap: Int,
    val topReserved: Int = 0,
    val bottomReserved: Int = 0,
    /** Keep-out bands, top to bottom (exclusive), from the column's top. */
    val keepOut: List<Pair<Int, Int>> = emptyList(),
)

/**
 * Where a side column's buttons go, in px from its top. [primaryTops] for every primary (never
 * dropped); [shown] the indices of the actions that stay, in their listed order, at
 * [shownTops]; [overflowed] the rest, in their listed order, behind the ⋯ at [overflowTop]
 * (null when nothing overflows).
 */
internal data class RaftSideColumnPlan(
    val primaryTops: List<Int>,
    val shown: List<Int>,
    val shownTops: List<Int>,
    val overflowed: List<Int>,
    val overflowTop: Int?,
)

/**
 * Lays out a side column [height] px tall: the [primaryCount] primaries from the top, then the
 * actions ([ranks]: one per action, higher stays longer, ties keep the earlier), no slot crossing
 * a keep-out band. With primaries (a pushed page) the actions are its prominent ones: listed
 * order, straight under the primaries after the section gap, down to the bottom band, the ⋯
 * after them. Without (a top-level page) they stack against the bottom band, listed order
 * reading down, no higher than the top band, the ⋯ above them. When the actions don't all fit,
 * or there are more than [maxActions], the ⋯ takes the slot next to the ones that stay.
 */
internal fun raftSideColumnPlan(
    height: Int,
    metrics: RaftSideColumnMetrics,
    primaryCount: Int,
    ranks: List<Int>,
    maxActions: Int = Int.MAX_VALUE,
): RaftSideColumnPlan {
    val item = metrics.item
    fun crosses(top: Int): Pair<Int, Int>? = metrics.keepOut.firstOrNull { (kTop, kBottom) -> top < kBottom && top + item > kTop }

    // The first free top at or below [top], and the first free bottom-anchored top above [bottom].
    fun slotBelow(top: Int): Int {
        var t = top
        while (true) t = crosses(t)?.second ?: break
        return t
    }
    fun slotAbove(bottom: Int): Int {
        var t = bottom - item
        while (true) t = (crosses(t)?.first ?: break) - item
        return t
    }

    val primaryTops = ArrayList<Int>(primaryCount)
    var cursor = metrics.topReserved + metrics.inset
    repeat(primaryCount) {
        cursor = slotBelow(cursor)
        primaryTops += cursor
        cursor += item + metrics.gap
    }
    val ceiling = if (primaryCount > 0) primaryTops.last() + item + metrics.sectionGap else metrics.topReserved + metrics.inset
    val floor = height - metrics.bottomReserved - metrics.inset
    val downwards = primaryCount > 0

    // Action slots in stacking order: downwards from the ceiling, or upwards from the floor.
    val actionCount = ranks.size
    val slots = ArrayList<Int>()
    if (downwards) {
        var next = ceiling
        while (slots.size < actionCount) {
            val top = slotBelow(next)
            if (top + item > floor) break
            slots += top
            next = top + item + metrics.gap
        }
    } else {
        var next = floor
        while (slots.size < actionCount) {
            val top = slotAbove(next)
            if (top < ceiling) break
            slots += top
            next = top - metrics.gap
        }
    }
    // The [count] actions that stay, listed order, top to bottom.
    fun tops(count: Int): List<Int> = (0 until count).map { if (downwards) slots[it] else slots[count - 1 - it] }

    if (actionCount <= minOf(slots.size, maxActions)) {
        return RaftSideColumnPlan(primaryTops, (0 until actionCount).toList(), tops(actionCount), emptyList(), null)
    }
    val keep = minOf(maxActions, slots.size - 1).coerceAtLeast(0)
    val stays = ranks.indices.sortedWith(compareByDescending<Int> { ranks[it] }.thenBy { it }).take(keep).sorted()
    val overflowed = ranks.indices.filter { it !in stays }
    // No slot left for the ⋯ itself: it takes the next one anyway, past the band it would keep to.
    val overflowTop =
        slots.getOrNull(keep)
            ?: if (downwards) {
                slotBelow(if (keep > 0) slots[keep - 1] + item + metrics.gap else ceiling)
            } else {
                slotAbove(floor).coerceAtLeast(0)
            }
    return RaftSideColumnPlan(primaryTops, stays, tops(keep), overflowed, overflowTop)
}

/** The height a side column's buttons need without keep-outs (for an unbounded height). */
internal fun raftSideColumnNaturalHeight(metrics: RaftSideColumnMetrics, primaryCount: Int, actionCount: Int, maxActions: Int): Int {
    val slots = if (actionCount <= maxActions) actionCount else maxActions + 1
    fun run(n: Int) = if (n == 0) 0 else n * metrics.item + (n - 1) * metrics.gap
    val between = if (primaryCount > 0 && slots > 0) metrics.sectionGap else 0
    return metrics.topReserved + metrics.inset * 2 + run(primaryCount) + between + run(slots) + metrics.bottomReserved
}
