package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

/*
 * A pane grid column's head (#189 rule 6 / 7, web ChannelSideCard with raft-ui PanelHeader; the design lead's
 * prototype `headModel`): one head rule for every column, left to right
 *
 *   [lead]  title (or the row tabs)  own actions  ⋯  Expand  Close
 *
 * - the title ellipsizes; where the row makes columns tabs in this column's head (rule 4), the tabs stand in its place;
 * - the column's own actions show as buttons where they fit, else they fold into ⋯, the last first (headFit);
 * - ⋯ holds what didn't fit, then the column's other commands (Move Left / Move Right, rule 7); it shows only with
 *   something in it;
 * - Expand (rule 5) is a window control against Close, never in ⋯; shown only where the row has more than one column,
 *   and always on the column expanded, so it can come back (Restore);
 * - ⋯, Expand and Close never fold where the title keeps its floor beside them; narrower, Expand and then Close go into
 *   ⋯ (mobile's 44dp touch buttons, [raftPaneGridHeadFit]), and the title never vanishes.
 * The buttons are the bar buttons of the input in use ([build.raft.designsystem.theme.RaftControlMetrics]: 44 by
 * touch, 28 under a fine pointer), the head [build.raft.designsystem.theme.RaftControlMetrics.headHeight] tall with a
 * seam under it.
 */

/** Web headFit `HEAD_ACTION_GAP`: between a head's buttons. */
const val RAFT_PANE_GRID_HEAD_GAP_DP = 4f

/** Web headFit `TITLE_MIN` / `TABS_MIN`: the title's least room before the actions fold; tabs in its place, 120. */
const val RAFT_PANE_GRID_HEAD_TITLE_MIN_DP = 56f
const val RAFT_PANE_GRID_HEAD_TABS_MIN_DP = 120f

/** Test tags: a head (`+` its cell id), and its ⋯, Expand, Close and tabs (`+` the cell id and the suffix). */
const val RAFT_PANE_GRID_HEAD_TEST_TAG = "raftPaneGridHead:"
const val RAFT_PANE_GRID_HEAD_MORE_SUFFIX = ":more"
const val RAFT_PANE_GRID_HEAD_EXPAND_SUFFIX = ":expand"
const val RAFT_PANE_GRID_HEAD_CLOSE_SUFFIX = ":close"

/**
 * One command of a column head (web ColumnMenuItem): a button where it shows in the head, a row of ⋯ otherwise.
 * [label] is its tooltip and name; [glyph] (or [icon], drawn at the size it is given) its icon.
 */
@Immutable
class RaftPaneGridHeadAction(
    val id: String,
    val label: String,
    val onSelect: () -> Unit,
    val glyph: RaftGlyph? = null,
    val icon: (@Composable (size: Dp) -> Unit)? = null,
    val enabled: Boolean = true,
    val testTag: String? = null,
) {
    @Composable
    internal fun Icon(size: Dp) {
        val draw = icon
        if (draw != null) draw(size) else if (glyph != null) RaftGlyphIcon(glyph, size = size)
    }
}

/** A head's words, from the app's catalog (web `layout.row.more`, `artifacts.viewer.expand` / `restore`, `layout.row.tabs`). */
@Immutable
data class RaftPaneGridHeadLabels(val more: String, val expand: String, val restore: String, val tabs: String)

/**
 * Web headFit `shownOwnActions` (rule 6): how many of a head's [own] actions show as buttons in [widthDp] (0: not
 * measured yet, every one shows); the rest go into ⋯. [leadButtons] stand before the title (a fold chevron), [controls]
 * are the window controls shown (Expand, Close: 0 to 2), [menuItems] what ⋯ holds besides folded actions; [tabs]: the
 * row tabs stand in the title's place (their least room is 120, a title's 56). Every button is [buttonDp] wide,
 * [gapDp] apart; a lead button takes [leadDp] with its gap; the head's content starts [startDp] in and ends [endDp]
 * before its end, and [titleGapDp] stands between the title and the buttons after it.
 */
fun raftPaneGridHeadShownActions(
    widthDp: Float,
    leadButtons: Int,
    own: Int,
    controls: Int,
    menuItems: Int,
    tabs: Boolean,
    buttonDp: Float,
    startDp: Float,
    endDp: Float,
    gapDp: Float = RAFT_PANE_GRID_HEAD_GAP_DP,
    leadDp: Float = buttonDp + gapDp,
    titleGapDp: Float = gapDp,
): Int {
    if (widthDp <= 0f) return own
    val titleMin = if (tabs) RAFT_PANE_GRID_HEAD_TABS_MIN_DP else RAFT_PANE_GRID_HEAD_TITLE_MIN_DP
    val room = widthDp - startDp - endDp - leadButtons * leadDp - titleMin - titleGapDp
    for (shown in own downTo 1) {
        val menu = if (menuItems + (own - shown) > 0) 1 else 0
        val count = shown + controls + menu
        val need = buttonDp * count + gapDp * (count - 1)
        if (need <= room) return shown
    }
    return 0
}

/**
 * The least the title keeps (a letter or two and the ellipsis; tabs in its place scroll in it) once the head's own
 * actions are all in ⋯. Mobile's own floor, see [raftPaneGridHeadFit].
 */
const val RAFT_PANE_GRID_HEAD_TITLE_FLOOR_DP = 32f

/** Test tag suffix of a head's title (`+` the cell id). */
const val RAFT_PANE_GRID_HEAD_TITLE_SUFFIX = ":title"

/** How a head lays out ([raftPaneGridHeadFit]): its own actions shown as buttons, and whether Expand and Close went into ⋯. */
data class RaftPaneGridHeadFit(val shownOwn: Int, val expandInMenu: Boolean = false, val closeInMenu: Boolean = false)

/**
 * The head's layout in [widthDp] (rule 6). First the web's headFit ([raftPaneGridHeadShownActions]): the own actions
 * fold into ⋯, the last first, until the title keeps 56 (tabs 120). Where they are all in ⋯ and the title still
 * hasn't its floor ([titleFloorDp]) beside ⋯, Expand and Close, the window controls go into ⋯ too: Expand first, then
 * Close. ⋯ itself never goes, and it stands last then.
 *
 * The web has no such step: there the title (raft-ui `PanelHeaderContent`, `min-w-0 flex-1`, its title `truncate`) is
 * the only thing that gives way, down to nothing, and Expand and Close never fold; its 28 to 32px buttons leave a
 * title even in a 160px column without a lead. Mobile's 44dp touch buttons don't: at 160 ⋯, Expand and Close alone take
 * 140, so the title would vanish (pane_grid_heads, run 43355784708). A head never loses its title: its name is the
 * column's (and the screen reader's).
 */
fun raftPaneGridHeadFit(
    widthDp: Float,
    leadButtons: Int,
    own: Int,
    expand: Boolean,
    close: Boolean,
    menuItems: Int,
    tabs: Boolean,
    buttonDp: Float,
    startDp: Float,
    endDp: Float,
    gapDp: Float = RAFT_PANE_GRID_HEAD_GAP_DP,
    leadDp: Float = buttonDp + gapDp,
    titleGapDp: Float = gapDp,
    titleFloorDp: Float = RAFT_PANE_GRID_HEAD_TITLE_FLOOR_DP,
): RaftPaneGridHeadFit {
    val controls = (if (expand) 1 else 0) + (if (close) 1 else 0)
    val shown = raftPaneGridHeadShownActions(widthDp, leadButtons, own, controls, menuItems, tabs, buttonDp, startDp, endDp, gapDp, leadDp, titleGapDp)
    if (widthDp <= 0f || shown > 0) return RaftPaneGridHeadFit(shown)
    val room = widthDp - startDp - endDp - leadButtons * leadDp - titleFloorDp - titleGapDp
    fun fits(bar: Int, menu: Boolean): Boolean {
        val count = bar + (if (menu) 1 else 0)
        return count == 0 || buttonDp * count + gapDp * (count - 1) <= room
    }
    val menu = own + menuItems > 0
    if (fits(controls, menu)) return RaftPaneGridHeadFit(0)
    if (expand) {
        if (fits(controls - 1, true)) return RaftPaneGridHeadFit(0, expandInMenu = true)
        return RaftPaneGridHeadFit(0, expandInMenu = true, closeInMenu = close)
    }
    return RaftPaneGridHeadFit(0, closeInMenu = close)
}

/**
 * A pane grid column's head (see the rule above). [cellId] is the cell it heads: its region for Expand and for the
 * row tabs, which come from the column it stands in ([LocalRaftPaneGridColumnControls]); outside a planned row it has
 * neither. [lead] stands before the title ([leadButtons] buttons: a stacked cell's fold chevron). [ownActions] show as
 * buttons where they fit; [menuItems] always go into ⋯ after the folded ones. [expandable] false: no Expand (web: the
 * channel details gain nothing from the row's width). [onClose] null: no Close.
 */
@Composable
fun RaftPaneGridColumnHead(
    title: String,
    labels: RaftPaneGridHeadLabels,
    modifier: Modifier = Modifier,
    cellId: String? = null,
    closeLabel: String? = null,
    onClose: (() -> Unit)? = null,
    lead: (@Composable () -> Unit)? = null,
    leadButtons: Int = if (lead != null) 1 else 0,
    ownActions: List<RaftPaneGridHeadAction> = emptyList(),
    menuItems: List<RaftPaneGridHeadAction> = emptyList(),
    expandable: Boolean = true,
) {
    val colors = RaftTheme.colors
    val metrics = RaftTheme.controls
    val grid = LocalRaftPaneGridColumnControls.current
    // The column's top head carries its row tabs, as the grid decided ([RaftPaneGridColumnControls.tabsCell]).
    val tabs = grid?.tabs?.takeIf { cellId != null && grid?.tabsCell == cellId }
    val expand = if (expandable && cellId != null) grid?.expandFor(cellId)?.takeIf { it.shown } else null
    val tag = RAFT_PANE_GRID_HEAD_TEST_TAG + (cellId ?: title)
    // In a column clipped under its minimum the cell is laid out wider than what shows: the head takes only what shows.
    val visible = LocalRaftPaneGridVisibleWidth.current
    BoxWithConstraints(modifier.fillMaxWidth().height(metrics.headHeight).background(colors.layerCanvas)) {
        val headWidth = if (visible != null) minOf(maxWidth, visible) else maxWidth
        val start = if (lead != null) metrics.barEdgeInset else 16.dp
        val end = metrics.barEdgeInset
        val fit =
            raftPaneGridHeadFit(
                widthDp = headWidth.value,
                leadButtons = if (lead != null) leadButtons else 0,
                own = ownActions.size,
                expand = expand != null,
                close = onClose != null,
                menuItems = menuItems.size,
                tabs = tabs != null,
                buttonDp = metrics.barButtonSize.value,
                startDp = start.value,
                endDp = end.value,
            )
        val shownOwn = fit.shownOwn
        // Too narrow for its window controls beside the title's floor, they stand last in ⋯: Expand, then Close.
        val expandItem =
            expand?.takeIf { fit.expandInMenu }?.let {
                RaftPaneGridHeadAction(
                    id = "expand",
                    label = if (it.pressed) labels.restore else labels.expand,
                    onSelect = { it.toggle() },
                    glyph = if (it.pressed) RaftGlyph.Minimize2 else RaftGlyph.Maximize2,
                    testTag = tag + RAFT_PANE_GRID_HEAD_EXPAND_SUFFIX,
                )
            }
        val closeItem =
            onClose?.takeIf { fit.closeInMenu }?.let {
                RaftPaneGridHeadAction(id = "close", label = closeLabel ?: title, onSelect = it, glyph = RaftGlyph.X, testTag = tag + RAFT_PANE_GRID_HEAD_CLOSE_SUFFIX)
            }
        val menu = ownActions.drop(shownOwn) + menuItems + listOfNotNull(expandItem, closeItem)
        // The head's buttons are bar buttons: 28 under a mouse or trackpad, 44 by touch (sizing v12).
        CompositionLocalProvider(LocalRaftBarButtons provides true) {
            Row(
                // At its visible width and cut there: never drawn past its column, even where the buttons that never
                // fold (⋯, Expand, Close) don't fit.
                Modifier
                    .width(headWidth)
                    .height(metrics.headHeight - RaftStroke.Hairline)
                    .clipToBounds()
                    .testTag(tag)
                    .padding(start = start, end = end),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RAFT_PANE_GRID_HEAD_GAP_DP.dp),
            ) {
                lead?.invoke()
                Box(Modifier.weight(1f).testTag(tag + RAFT_PANE_GRID_HEAD_TITLE_SUFFIX), contentAlignment = Alignment.CenterStart) {
                    if (tabs != null) {
                        // Rule 4: the row tabs in the title's place, the title's whole room; they scroll where they don't fit.
                        RaftTabStrip(
                            Modifier.testTag(RAFT_PANE_GRID_TABS_TEST_TAG + tabs.selected).semantics { contentDescription = labels.tabs },
                            inHead = true,
                        ) {
                            tabs.tabs.forEach { (key, label) -> RaftTab(text = label, selected = key == tabs.selected, onClick = { tabs.onSelect(key) }) }
                        }
                    } else {
                        // Truncated or not, its node carries the whole title: the head's name for screen readers.
                        RaftText(
                            title,
                            Modifier.semantics { heading() },
                            style = RaftTheme.typography.headline,
                            color = colors.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ownActions.take(shownOwn).forEach { action ->
                    RaftIconButton(
                        onClick = action.onSelect,
                        tooltip = action.label,
                        modifier = if (action.testTag != null) Modifier.testTag(action.testTag) else Modifier,
                        variant = RaftButtonVariant.Ghost,
                        size = RaftIconButtonSize.Lg,
                        enabled = action.enabled,
                    ) {
                        action.Icon(metrics.barIconSize)
                    }
                }
                if (menu.isNotEmpty()) RaftPaneGridHeadMenu(menu, labels.more, tag + RAFT_PANE_GRID_HEAD_MORE_SUFFIX)
                if (expand != null && !fit.expandInMenu) {
                    RaftIconButton(
                        onClick = { expand.toggle() },
                        tooltip = if (expand.pressed) labels.restore else labels.expand,
                        modifier = Modifier.testTag(tag + RAFT_PANE_GRID_HEAD_EXPAND_SUFFIX),
                        variant = RaftButtonVariant.Ghost,
                        size = RaftIconButtonSize.Lg,
                    ) {
                        RaftGlyphIcon(if (expand.pressed) RaftGlyph.Minimize2 else RaftGlyph.Maximize2, size = metrics.barIconSize)
                    }
                }
                if (onClose != null && !fit.closeInMenu) {
                    RaftIconButton(
                        onClick = onClose,
                        tooltip = closeLabel,
                        modifier = Modifier.testTag(tag + RAFT_PANE_GRID_HEAD_CLOSE_SUFFIX),
                        variant = RaftButtonVariant.Ghost,
                        size = RaftIconButtonSize.Lg,
                    ) {
                        RaftGlyphIcon(RaftGlyph.X, size = metrics.barIconSize)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
    }
}

/** A head's ⋯ (web ColumnOverflowMenu): its [items] in a menu under it, end-aligned; each closes it. */
@Composable
private fun RaftPaneGridHeadMenu(items: List<RaftPaneGridHeadAction>, label: String, tag: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        RaftIconButton(
            onClick = { open = true },
            tooltip = label,
            modifier = Modifier.testTag(tag),
            variant = RaftButtonVariant.Ghost,
            size = RaftIconButtonSize.Lg,
        ) {
            RaftGlyphIcon(RaftGlyph.EllipsisVertical, size = RaftTheme.controls.barIconSize)
        }
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, minWidth = 200.dp, alignEnd = true) {
            items.forEach { item ->
                val icon: (@Composable () -> Unit)? = if (item.glyph != null || item.icon != null) { { item.Icon(16.dp) } } else null
                RaftMenuItem(
                    text = item.label,
                    onClick = {
                        open = false
                        item.onSelect()
                    },
                    modifier = if (item.testTag != null) Modifier.testTag(item.testTag) else Modifier,
                    enabled = item.enabled,
                    leadingIcon = icon,
                )
            }
        }
    }
}
