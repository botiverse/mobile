package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.gesture.raftKeepsBackSwipe
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import kotlin.math.roundToInt


/*
 * The wide layout's pane grid (artin 11-02, "first principles, like a grid view"; web
 * ChannelSideArea + ColumnResizeHandle + lib/panelBounds.ts): a main pane, then columns right of
 * it, each a vertical stack of cells. Every boundary is a splitter: a column's left seam drags its
 * width, the seam between two stacked cells drags their split. A cell folds to its 56dp head where
 * it shares its column, and closes. Pages only declare their columns and cells
 * ([RaftPaneGridColumn], [RaftPaneGridCell]); the grid lays them out, drags, and keeps the sizes
 * and folds ([RaftPaneGridSizes]) as web keeps them, per device (web: localStorage).
 *
 * The main pane keeps its place in the composition whatever the columns do, so opening or
 * closing a column never remounts it.
 *
 * Left of the main pane a grid may have a leading column (the sidebar or a list,
 * [RaftPaneGridLeadingColumn]; its host puts the splitter on its seam). Grids nest the way pages
 * do: one declared in another's main pane hands its columns to the outermost grid, so the window
 * has one row, left to right list | main | the outer levels' columns | the inner levels' (web:
 * sidebar | conversation | thread | Tasks / Files), laid out by the #189 row plan
 * ([raftPaneGridRowPlan], RaftRowPlan.kt): short of room every column gives way in proportion,
 * then clips down to 160, then the rightmost become tabs.
 */

/** Web `MAIN_COLUMN_MIN_WIDTH`: the main pane never renders narrower than this beside a column. */
const val RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP = 360f

/** Web `MIN_SECTION_HEIGHT`: a stacked cell, its head included, keeps 160 while it is open. */
const val RAFT_PANE_GRID_CELL_MIN_HEIGHT_DP = 160f

/**
 * Web `HEAD_HEIGHT` (PanelHeader): a folded cell is its 56dp head, the seam under it included.
 * This is the touch head; the grid lays out with the head of the input in use
 * ([build.raft.designsystem.theme.RaftControlMetrics.headHeight]: 44 under a fine pointer,
 * sizing v12, raft-ui 0.65 desktop density), passed to the rules below as `headDp`.
 */
const val RAFT_PANE_GRID_HEAD_HEIGHT_DP = 56f

/** Web `CHANNEL_SIDE_MAX_SAVED_WIDTH`: the widest width kept; the rendered one stops where the main pane keeps its minimum. */
const val RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP = 4000f

/** Web `KEYBOARD_STEP` / `KEYBOARD_STEP_LARGE` (also the split's): arrows move a seam 16, with Shift 64. */
const val RAFT_PANE_GRID_KEYBOARD_STEP_DP = 16f
const val RAFT_PANE_GRID_KEYBOARD_STEP_LARGE_DP = 64f

/** A kind of column and its width rules (web `COLUMN_BOUNDS`); [id] keys its kept width. */
@Immutable
data class RaftPaneGridColumnBounds(val id: String, val defaultWidthDp: Float, val minWidthDp: Float)

/**
 * The main pane's saved width (web rowLayout `slock:conversationWidth`, #189): kept like a column's under the row
 * plan's `conversation` key. Before a seam beside it is first let go it has none, and the plan gives it what the
 * columns leave, at least 400 ([RAFT_ROW_CONVERSATION_DEFAULT_WIDTH_DP]); kept, it is at least its content's 360.
 */
val RaftPaneGridConversationBounds =
    RaftPaneGridColumnBounds(
        id = RAFT_ROW_CONVERSATION,
        defaultWidthDp = RAFT_ROW_CONVERSATION_DEFAULT_WIDTH_DP,
        minWidthDp = RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP,
    )


// ---------------------------------------------------------------- the rules (pure)

/**
 * Web `growToMainMinMax`: the widest a column may be dragged now, its rendered width plus what the
 * main pane has above its minimum; never below the column's own minimum.
 */
fun raftPaneGridDragMaxDp(
    minWidthDp: Float,
    renderedWidthDp: Float,
    mainWidthDp: Float,
    mainMinDp: Float = RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP,
): Float = maxOf(minWidthDp, minOf(RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP, renderedWidthDp + maxOf(0f, mainWidthDp - mainMinDp)))

/** Web `sideCardsStackMinHeight`: stacked cells each keep 160, plus the seams between them. */
fun raftPaneGridStackMinHeightDp(cells: Int): Float = cells * RAFT_PANE_GRID_CELL_MIN_HEIGHT_DP + (cells - 1)

/** A stack laid out: each cell's height, and whether a seam stands above it (none above the first or under a folded head). */
data class RaftPaneGridStackLayout(val heights: List<Float>, val seamAbove: List<Boolean>)

/**
 * Web `useStack` / `flexIn`: an open cell takes its share of what folded heads and seams leave of
 * [heightDp]; a folded one only its head ([headDp]). With every cell folded the heads stand at
 * the top.
 */
fun raftPaneGridStack(
    heightDp: Float,
    shares: List<Float>,
    collapsed: List<Boolean>,
    headDp: Float = RAFT_PANE_GRID_HEAD_HEIGHT_DP,
): RaftPaneGridStackLayout {
    val seamAbove = shares.indices.map { i -> i > 0 && !collapsed[i - 1] }
    val fixed = seamAbove.count { it } + collapsed.count { it } * headDp
    val openTotal = shares.indices.filter { !collapsed[it] }.sumOf { shares[it].toDouble() }.toFloat()
    val room = maxOf(0f, heightDp - fixed)
    val heights =
        shares.indices.map { i ->
            if (collapsed[i]) headDp
            else maxOf(headDp, shares[i] / (if (openTotal > 0f) openTotal else 1f) * room)
        }
    return RaftPaneGridStackLayout(heights, seamAbove)
}

/** Web `perPixel`: the share one dp of the stack is worth. */
fun raftPaneGridSharePerDp(
    heightDp: Float,
    shares: List<Float>,
    collapsed: List<Boolean>,
    headDp: Float = RAFT_PANE_GRID_HEAD_HEIGHT_DP,
): Float {
    val layout = raftPaneGridStack(heightDp, shares, collapsed, headDp)
    val fixed = layout.seamAbove.count { it } + collapsed.count { it } * headDp
    val openTotal = shares.indices.filter { !collapsed[it] }.sumOf { shares[it].toDouble() }.toFloat()
    return (if (openTotal > 0f) openTotal else 1f) / maxOf(1f, heightDp - fixed)
}

/**
 * Web `withTop`: the shares with seam [seam]'s upper cell at [top]; what it takes the lower one
 * gives (the pair keeps its total), each kept at least 160dp (or 45% of the pair in a short stack).
 */
fun raftPaneGridMoveSeam(
    shares: List<Float>,
    seam: Int,
    top: Float,
    heightDp: Float,
    collapsed: List<Boolean>,
    headDp: Float = RAFT_PANE_GRID_HEAD_HEIGHT_DP,
): List<Float> {
    val pair = shares[seam] + shares[seam + 1]
    val min =
        minOf(
            pair * 0.45f,
            if (heightDp > 0f) RAFT_PANE_GRID_CELL_MIN_HEIGHT_DP * raftPaneGridSharePerDp(heightDp, shares, collapsed, headDp) else 0.1f,
        )
    val out = shares.toMutableList()
    out[seam] = top.coerceIn(min, pair - min)
    out[seam + 1] = pair - out[seam]
    return out
}

/**
 * The grid's row by the #189 rules ([raftRowPlan]; web rowLayout `useRowPlan`): the main pane (the conversation)
 * and [columns] in [roomDp]. The main pane's saved width is [mainSavedDp] (kept under [RaftPaneGridConversationBounds]
 * when a seam is let go), else what the columns leave, at least [mainDefaultDp] (web `CONVERSATION_DEFAULT_WIDTH`,
 * 400); its content keeps 360. Short of room every column gives way in proportion above its minimum, then clips down
 * to 160, then the rightmost become tabs ([RaftRowPlan.groups]).
 *
 * The thread's fold (rule 3) is decided where the thread's column is declared ([raftPaneGridFolds]): a folded
 * thread is the main pane's page, so no thread is planned here.
 */
fun raftPaneGridRowPlan(
    roomDp: Float,
    columns: List<RaftRowPlanColumn>,
    mainSavedDp: Float? = null,
    mainDefaultDp: Float = RAFT_ROW_CONVERSATION_DEFAULT_WIDTH_DP,
    /** The region expanded (rule 5, [RaftPaneGridExpand]): its column fills the row, every other one is 160. */
    expanded: String? = null,
): RaftRowPlan =
    raftRowPlan(
        RaftRowPlanInput(
            available = roomDp,
            conversation =
                RaftRowPlanSize(
                    saved = mainSavedDp ?: maxOf(mainDefaultDp, roomDp - columns.sumOf { it.saved.toDouble() }.toFloat()),
                    contentMin = RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP,
                ),
            thread = null,
            columns = columns,
            expanded = expanded,
        )
    )

/** A grid column as the plan sees it: its cells' ids as its regions, each at the column's minimum, at its saved width. */
internal fun RaftPaneGridColumn.planColumn(sizes: RaftPaneGridSizes): RaftRowPlanColumn =
    RaftRowPlanColumn(
        key = bounds.id,
        regions = cells.map { it.id }.ifEmpty { listOf(bounds.id) },
        mins = cells.map { bounds.minWidthDp }.ifEmpty { listOf(bounds.minWidthDp) },
        saved = sizes.widthDp(bounds),
    )

/**
 * The unit a group of [plan] shows (web rowPlanStore `activeUnit`): the expanded one where the group holds it (rule 5:
 * its group shows it), else the one [picked] for it (by the key of its first unit), else the first. An index into
 * [RaftRowPlan.units].
 */
fun raftPaneGridShownUnit(plan: RaftRowPlan, group: List<Int>, picked: Map<String, String>): Int {
    val keys = group.map { plan.units[it].key }
    val choice = plan.expandedUnit?.takeIf { it in keys } ?: picked[keys.first()]
    val at = if (choice != null) keys.indexOf(choice) else -1
    return if (at >= 0) group[at] else group.first()
}

/**
 * Which unit each group of row tabs shows (#189 rule 4; web rowPlanStore `active`), by the key of the group's first
 * unit: presentation only, never saved. Snapshot state; one per outermost grid.
 */
@Stable
class RaftPaneGridTabPicks {
    var picked: Map<String, String> by mutableStateOf(emptyMap())
        private set

    /** Group [group] shows [unit]; nothing written where it does already. */
    fun pick(group: String, unit: String) {
        if (picked[group] != unit) picked = picked + (group to unit)
    }
}

/**
 * A column's (or the main pane's) row tabs (#189 rule 4): every unit of its group by key and label, the one shown,
 * and where a pick goes. Compared by value, so a grid recomposing with the same tabs changes nothing below it.
 */
@Immutable
data class RaftPaneGridRowTabs(
    val tabs: List<Pair<String, String>>,
    val selected: String,
    val group: String,
    val picks: RaftPaneGridTabPicks,
) {
    fun onSelect(unit: String) = picks.pick(group, unit)
}

/** Test tags: the main pane, each column (`+ bounds.id`), each row-tab strip (`+` the id of the column showing it, or `conversation`). */
const val RAFT_PANE_GRID_MAIN_TEST_TAG = "raftPaneGridMain"
const val RAFT_PANE_GRID_COLUMN_TEST_TAG = "raftPaneGridColumn:"
const val RAFT_PANE_GRID_TABS_TEST_TAG = "raftPaneGridTabs:"

/**
 * Rule 2's clip: the content is laid out at least [minDp] wide and stands at the start; a parent clips what is past
 * the column's edge. 0 lays it out at the column's width.
 */
private fun Modifier.raftPaneGridContentMinWidth(minDp: Float): Modifier =
    layout { measurable, constraints ->
        val min = minDp.dp.roundToPx()
        if (!constraints.hasBoundedWidth || min <= constraints.maxWidth) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        } else {
            val placeable = measurable.measure(constraints.copy(minWidth = min, maxWidth = min))
            layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(0, 0) }
        }
    }

/**
 * A tab not shown (#189 rule 4) is out of reach, as the web's `inert`: while [inert], Tab and the arrows never move
 * focus into it (its focus group cancels the entry), and focus left in it when it became one is dropped. It stays
 * composed, so its pages keep their state.
 */
@Composable
private fun Modifier.raftPaneGridInert(inert: Boolean): Modifier {
    val blocked by rememberUpdatedState(inert)
    // Plain memory, not state: where the focus is never recomposes the row.
    val focusInside = remember { BooleanArray(1) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(inert) { if (inert && focusInside[0]) focusManager.clearFocus(force = true) }
    return this
        .onFocusChanged { focusInside[0] = it.hasFocus }
        .focusProperties { onEnter = { if (blocked) cancelFocusChange() } }
        .focusGroup()
}

/** A row-tab strip (web RowTabs) on top of the column it shows, which keeps its own head under it. */
@Composable
private fun RaftPaneGridTabStrip(tabs: RaftPaneGridRowTabs, tag: String) {
    RaftTabStrip(Modifier.testTag(tag)) {
        tabs.tabs.forEach { (key, label) ->
            RaftTab(text = label, selected = key == tabs.selected, onClick = { tabs.onSelect(key) })
        }
    }
}


// ---------------------------------------------------------------- what is kept

/**
 * Where a grid keeps its sizes and folds: a column's width ([widthDp]), a stack's shares
 * ([shares], keyed by its cell ids joined by `+`) and folded cells ([isCollapsed]). Reads are
 * snapshot state, so the grid follows a change. The app keeps them per device (web: localStorage);
 * [RaftPaneGridMemorySizes] keeps them for the composition's life (previews, tests).
 */
@Stable
interface RaftPaneGridSizes {
    fun widthDp(bounds: RaftPaneGridColumnBounds): Float

    /** The width kept for [bounds], null where none is (the main pane's before a seam beside it is first let go). */
    fun savedWidthDp(bounds: RaftPaneGridColumnBounds): Float? = null

    fun setWidth(bounds: RaftPaneGridColumnBounds, widthDp: Float)

    fun shares(key: String, defaults: List<Float>): List<Float>

    fun setShares(key: String, shares: List<Float>)

    fun isCollapsed(cell: String): Boolean

    fun setCollapsed(cell: String, collapsed: Boolean)
}

/** [RaftPaneGridSizes] in memory: the defaults until something is dragged or folded. */
@Stable
class RaftPaneGridMemorySizes : RaftPaneGridSizes {
    private val widths = mutableStateMapOf<String, Float>()
    private val splits = mutableStateMapOf<String, List<Float>>()
    private val folded = mutableStateMapOf<String, Boolean>()

    override fun widthDp(bounds: RaftPaneGridColumnBounds): Float = widths[bounds.id] ?: bounds.defaultWidthDp

    override fun savedWidthDp(bounds: RaftPaneGridColumnBounds): Float? = widths[bounds.id]

    override fun setWidth(bounds: RaftPaneGridColumnBounds, widthDp: Float) {
        widths[bounds.id] = widthDp.roundToInt().toFloat().coerceIn(bounds.minWidthDp, RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP)
    }

    override fun shares(key: String, defaults: List<Float>): List<Float> =
        splits[key]?.takeIf { saved -> saved.size == defaults.size && saved.all { it > 0f && it.isFinite() } } ?: defaults

    override fun setShares(key: String, shares: List<Float>) {
        splits[key] = shares
    }

    override fun isCollapsed(cell: String): Boolean = folded[cell] == true

    override fun setCollapsed(cell: String, collapsed: Boolean) {
        folded[cell] = collapsed
    }
}

/** The sizes the grids below keep to; the app provides its per-device store. */
val LocalRaftPaneGridSizes = staticCompositionLocalOf<RaftPaneGridSizes> { RaftPaneGridMemorySizes() }

/**
 * What the platform tells the grid's splitters: [finePointer] (a mouse or trackpad: a 12dp hit
 * area across a seam; a finger gets 24dp), and [splitterModifier], added to every splitter (the
 * app's native-dispatch capture on iOS).
 */
@Immutable
class RaftPaneGridPlatform(
    val finePointer: Boolean = true,
    val splitterModifier: Modifier = Modifier,
)

val LocalRaftPaneGridPlatform = staticCompositionLocalOf { RaftPaneGridPlatform() }

// ---------------------------------------------------------------- what pages declare

/** What a cell knows of its place: it may fold where it shares its column ([collapsible]). */
@Stable
interface RaftPaneGridCellScope {
    /** The cell's id ([RaftPaneGridCell.id]): its region for its head's Expand and row tabs. */
    val cellId: String get() = ""
    val collapsible: Boolean
    val collapsed: Boolean

    fun setCollapsed(collapsed: Boolean)
}

/**
 * One cell of a column: [id] keys its fold and, joined with its neighbours', the stack's split;
 * [defaultShare] is its part of the column's height before anyone drags (web `defaultShares`).
 * The cell draws its own head (the app's `RaftPaneGridCard`, or a page's own header); its close is the
 * page's, which then stops declaring it.
 */
@Immutable
class RaftPaneGridCell(
    val id: String,
    val defaultShare: Float = 1f,
    val content: @Composable RaftPaneGridCellScope.() -> Unit,
)

/**
 * A column right of the main pane: its [bounds], its cells top to bottom, the splitters' names
 * for screen readers. A column too short for its cells at 160 each shows only [shortShownCell]
 * under [shortSwitch] (web `sideCardsOneAtATime`); without one the stack just shrinks.
 */
@Immutable
class RaftPaneGridColumn(
    val bounds: RaftPaneGridColumnBounds,
    val resizeLabel: String,
    val cells: List<RaftPaneGridCell>,
    val splitLabel: String? = null,
    val shortShownCell: String? = null,
    val shortSwitch: (@Composable () -> Unit)? = null,
    /** Its tab's label where the row makes it a tab (#189 rule 4; web RowTabs): its sections' titles, ` · ` between. */
    val tabLabel: String? = null,
    /**
     * It stands after every column without it, whichever grid declared it (web DEFAULT_CHANNEL_SIDE_LAYOUT: the
     * profile / context / details column is the row's last, right of Tasks / Files a page declares further in).
     */
    val trailing: Boolean = false,
)

// ---------------------------------------------------------------- the leading column

/** Where the leading column stands: beside the main pane, filling the row (the main pane gets no width), or out of sight. */
enum class RaftPaneGridLeadingMode { Beside, Fill, Hidden }

/**
 * The column left of the main pane (web ShellSidebarColumn: the sidebar, or the Activity / Search
 * list): its width is the host's ([widthDp], already fitted to the row; the host keeps it), with
 * a 1dp seam on its right. The host draws the splitter on that seam ([RaftPaneSplitter]) above
 * the grid, since the sidebar's handle does more than resize (web `snap`: a drag past its end
 * collapses it, Enter too), and keeps it while a drag has the column out of sight.
 */
@Immutable
class RaftPaneGridLeadingColumn(
    val widthDp: Float,
    val mode: RaftPaneGridLeadingMode = RaftPaneGridLeadingMode.Beside,
    val content: @Composable () -> Unit,
)

// ---------------------------------------------------------------- one grid for nested pages

/**
 * The columns a grid inside another grid's main pane hands to it: they stand in the outermost
 * grid, after its own columns and the ones handed up from outer levels (a page inside the
 * workspace: conversation | thread | Tasks / Files). [locals] are where they were declared, so
 * their cells render as they would have there.
 */
internal class RaftPaneGridHandedUp(
    val depth: Int,
    val columns: List<RaftPaneGridColumn>,
    val fillColumn: String? = null,
    val topInset: Dp = 0.dp,
    val locals: CompositionLocalContext? = null,
    /** Where the main pane's width is kept, where the declaring grid names it ([RaftPaneGrid]'s `mainBounds`). */
    val mainBounds: RaftPaneGridColumnBounds? = null,
    /** The row's Expand ([RaftPaneGrid]'s `expand`), where the declaring grid keeps one. */
    val expand: RaftPaneGridExpand? = null,
) {
    /**
     * What the outermost grid lays out and draws by, compared by value: the level, the fill, the inset, the locals
     * (CompositionLocalContext compares its map: equal while nothing provided above the declaring grid changed, so a
     * theme change still reaches the columns) and each column's bounds, labels and cells. Not the cells' content,
     * new lambdas every time the declaring page composes: pages read what changes in them through state
     * (rememberUpdatedState), so the cells recompose by themselves.
     */
    val shape: List<Any?> =
        listOf(depth, fillColumn, topInset, locals, mainBounds, expand) +
            columns.map { column ->
                listOf(
                    column.bounds,
                    column.resizeLabel,
                    column.splitLabel,
                    column.tabLabel,
                    column.shortShownCell,
                    column.shortSwitch != null,
                    column.trailing,
                    column.cells.map { it.id to it.defaultShare },
                )
            }
}

/**
 * A column where the outermost grid lays it out, with the top inset and locals of the grid that declared it, and the
 * [token] of that grid's hand-up (null for the outermost grid's own), by which it draws the latest cells.
 */
internal class RaftPaneGridPlaced(
    val column: RaftPaneGridColumn,
    val topInset: Dp,
    val locals: CompositionLocalContext?,
    val token: Any? = null,
    /**
     * The ids of the declared columns its cells come from: its own, or, for a column the arrangement stacks anew from
     * several ([RaftPaneGridArrangement]), theirs. A filling column ([RaftPaneGrid]'s `fillColumn`) names a declared column.
     */
    val sourceIds: Set<String> = setOf(column.bounds.id),
)

// ---------------------------------------------------------------- the arrangement

/**
 * One column of a [RaftPaneGridArrangement]: the ids of the cells it stacks, top to bottom, and its width where a
 * column has to be made for them ([widthDp]: its default; the column keeps its own width once one is kept).
 */
@Immutable
data class RaftPaneGridArrangementColumn(val cells: List<String>, val widthDp: Float)

/**
 * Where the outermost grid stands and stacks the cells it is given, whichever grid declared them (#189 step 4; web
 * channelSideLayout): left to right, each column the cells it stacks. A declared column whose cells all stand in one
 * arrangement column, in its order, stays as declared (its bounds, its kept width) and stands where that column does.
 * Otherwise its cells are drawn where the arrangement puts them, in a column made for them (keyed by the arrangement
 * column's cell ids joined by `+`); a declared cell the arrangement doesn't name goes with its column's first arranged
 * cell. Declared columns with no arranged cell stand first, in their declared order, as without an arrangement.
 */
@Immutable
data class RaftPaneGridArrangement(val columns: List<RaftPaneGridArrangementColumn>)

/** The arrangement the outermost grid below stands its columns by; null: as declared. Compared by value. */
val LocalRaftPaneGridArrangement = compositionLocalOf<RaftPaneGridArrangement?> { null }

/** Where a column of the arranged row comes from ([raftPaneGridArrange]). */
internal sealed interface RaftPaneGridSlot {
    /** Declared column [index] as is. */
    data class Declared(val index: Int) : RaftPaneGridSlot

    /** A column made for [cells] (each a declared column's index and a cell id), top to bottom, keyed [key], [widthDp] by default. */
    data class Stacked(val key: String, val cells: List<Pair<Int, String>>, val widthDp: Float) : RaftPaneGridSlot
}

/**
 * The arranged row ([RaftPaneGridArrangement]) of declared columns with cell ids [columns], left to right: declared
 * columns with no arranged cell first, then one slot per arrangement column with a cell, in its order.
 */
internal fun raftPaneGridArrange(columns: List<List<String>>, arrangement: RaftPaneGridArrangement?): List<RaftPaneGridSlot> {
    if (arrangement == null) return columns.indices.map { RaftPaneGridSlot.Declared(it) }
    fun at(cell: String): Int? = arrangement.columns.indexOfFirst { cell in it.cells }.takeIf { it >= 0 }
    val unarranged = columns.indices.filter { i -> columns[i].none { at(it) != null } }
    val groups = mutableMapOf<Int, MutableList<Pair<Int, String>>>()
    columns.indices.filter { it !in unarranged }.forEach { i ->
        val first = columns[i].firstNotNullOf { at(it) }
        columns[i].forEach { cell -> groups.getOrPut(at(cell) ?: first) { mutableListOf() } += i to cell }
    }
    return unarranged.map { RaftPaneGridSlot.Declared(it) } +
        groups.keys.sorted().map { a ->
            val order = arrangement.columns[a].cells
            // Stable: cells the arrangement doesn't name stay last, in their declared order.
            val cells = groups.getValue(a).sortedBy { (_, id) -> order.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
            val sources = cells.map { it.first }.distinct()
            if (sources.size == 1 && columns[sources[0]] == cells.map { it.second }) RaftPaneGridSlot.Declared(sources[0])
            else RaftPaneGridSlot.Stacked(key = order.joinToString("+"), cells = cells, widthDp = arrangement.columns[a].widthDp)
        }
}

/** The outermost grid, as the grids and pages in its main pane see it. */
@Stable
internal class RaftPaneGridHost internal constructor() {
    /**
     * Each nested grid's hand-up as the row is laid out by: written only when its [RaftPaneGridHandedUp.shape]
     * changes. Read by the outermost grid's row and by raftPaneGridFolds, so by the pages in the main pane.
     */
    internal val handedUp = mutableStateMapOf<Any, RaftPaneGridHandedUp>()

    /**
     * Each nested grid's latest hand-up, one state per grid: the cells' content (new lambdas, with whatever they
     * captured) and labels as they are now. Read only where a column is drawn ([freshColumn]), never by the row or a
     * page, so a write recomposes the columns' drawing and nothing that hands up again. One state per grid, not one
     * map: a map is observed whole, so the conversation's grid handing up (every time it composes) redrew the thread's
     * and the profile's columns too (Lagoon, #4661). Plain memory holds the states; each is created before its grid
     * enters [handedUp], so a column is never drawn before its state exists.
     */
    private val fresh = mutableMapOf<Any, MutableState<RaftPaneGridHandedUp?>>()

    /**
     * [token]'s grid hands up [up] (null: nothing any more). A nested grid does so every time it composes, with new
     * column objects. The row ([handedUp]) is written only where what it lays out by changed: writing it every time
     * made the outermost grid recompose, which recomposed the page that declared the nested grid, which handed up
     * again, a loop every frame. The columns' drawing ([fresh]) always gets the latest, so a cell whose content
     * changed with the same shape (a captured value) is drawn anew.
     */
    internal fun handUp(token: Any, up: RaftPaneGridHandedUp?) {
        if (up == null || (up.columns.isEmpty() && up.fillColumn == null && up.mainBounds == null && up.expand == null)) {
            fresh.remove(token)?.let { if (it.value != null) it.value = null }
            if (token in handedUp) handedUp.remove(token)
            return
        }
        val latest = fresh.getOrPut(token) { mutableStateOf(null) }
        // The same column objects handed up again (a page that remembers its columns): nothing to draw anew.
        if (!latest.value.sameColumns(up)) latest.value = up
        if (handedUp[token]?.shape != up.shape) handedUp[token] = up
    }

    private fun RaftPaneGridHandedUp?.sameColumns(other: RaftPaneGridHandedUp): Boolean =
        this != null && columns.size == other.columns.size && columns.indices.all { columns[it] === other.columns[it] }

    /** [token]'s state of [fresh], for tests: what a hand-up writes. */
    internal fun freshState(token: Any): Any? = fresh[token]

    /** The latest [id] column [token]'s grid handed up; null where it no longer does. Read where the column is drawn. */
    internal fun freshColumn(token: Any, id: String): RaftPaneGridColumn? = fresh[token]?.value?.columns?.firstOrNull { it.bounds.id == id }

    /**
     * Left to right: the outermost grid's [own] columns, then the handed-up ones, outer levels first; the
     * [RaftPaneGridColumn.trailing] ones after all the others, in the same order among themselves.
     */
    internal fun placed(own: List<RaftPaneGridColumn>, ownTopInset: Dp): List<RaftPaneGridPlaced> =
        (
            own.map { RaftPaneGridPlaced(it, ownTopInset, null) } +
                handedUp.entries.sortedBy { it.value.depth }.flatMap { (token, up) ->
                    up.columns.map { RaftPaneGridPlaced(it, up.topInset, up.locals, token) }
                }
        ).sortedBy { it.column.trailing }

    /**
     * [placed] stood and stacked by [arrangement] ([raftPaneGridArrange]). A column made anew draws each of its cells
     * as the declaring grid's latest hand-up has it ([freshColumn]), with that grid's locals.
     */
    internal fun arranged(placed: List<RaftPaneGridPlaced>, arrangement: RaftPaneGridArrangement?): List<RaftPaneGridPlaced> {
        if (arrangement == null) return placed
        return raftPaneGridArrange(placed.map { place -> place.column.cells.map { it.id } }, arrangement).map { slot ->
            when (slot) {
                is RaftPaneGridSlot.Declared -> placed[slot.index]
                is RaftPaneGridSlot.Stacked -> stacked(placed, slot)
            }
        }
    }

    private fun stacked(placed: List<RaftPaneGridPlaced>, slot: RaftPaneGridSlot.Stacked): RaftPaneGridPlaced {
        val sources = slot.cells.map { it.first }.distinct().map { placed[it] }
        val cells =
            slot.cells.map { (index, id) ->
                val place = placed[index]
                val cell = place.column.cells.first { it.id == id }
                RaftPaneGridCell(id = id, defaultShare = cell.defaultShare) {
                    val scope = this
                    // The latest content handed up, read here so only this cell is drawn anew when it changes.
                    val latest = place.token?.let { freshColumn(it, place.column.bounds.id) }?.cells?.firstOrNull { it.id == id } ?: cell
                    val locals = place.locals
                    if (locals == null) {
                        latest.content(scope)
                    } else {
                        // The declaring grid's locals, then what the column gives its cells (as RaftPaneGridSideColumn).
                        val shown = LocalRaftPaneGridColumnShown.current
                        val controls = LocalRaftPaneGridColumnControls.current
                        val visible = LocalRaftPaneGridVisibleWidth.current
                        CompositionLocalProvider(locals) {
                            CompositionLocalProvider(
                                LocalRaftPaneGridHost provides null,
                                LocalRaftPaneGridColumnShown provides shown,
                                LocalRaftPaneGridColumnControls provides controls,
                                LocalRaftPaneGridVisibleWidth provides visible,
                            ) {
                                latest.content(scope)
                            }
                        }
                    }
                }
            }
        val first = sources.first().column
        val minWidthDp = sources.maxOf { it.column.bounds.minWidthDp }
        val column =
            RaftPaneGridColumn(
                bounds =
                    RaftPaneGridColumnBounds(
                        id = slot.key,
                        // Never made narrower than what its cells need to show unclipped (the Artifact's 480).
                        defaultWidthDp = maxOf(slot.widthDp, minWidthDp),
                        minWidthDp = minWidthDp,
                    ),
                resizeLabel = first.resizeLabel,
                cells = cells,
                splitLabel = sources.firstNotNullOfOrNull { it.column.splitLabel },
                shortShownCell = first.shortShownCell.takeIf { sources.size == 1 },
                shortSwitch = first.shortSwitch.takeIf { sources.size == 1 },
                tabLabel = sources.mapNotNull { it.column.tabLabel }.distinct().joinToString(" · ").ifEmpty { null },
            )
        return RaftPaneGridPlaced(
            column = column,
            topInset = sources.maxOf { it.topInset },
            locals = null,
            sourceIds = sources.map { it.column.bounds.id }.toSet(),
        )
    }

    /**
     * Where the main pane's width is kept: the innermost grid that names it (the page the main pane shows: the Tasks
     * page's list), else the outermost's [own], else the conversation's. Each page keeps its own (PR #4661 review: the
     * Tasks page's task column saved the list's width as the conversation's).
     */
    internal fun mainBounds(own: RaftPaneGridColumnBounds?): RaftPaneGridColumnBounds =
        handedUp.values.filter { it.mainBounds != null }.maxByOrNull { it.depth }?.mainBounds ?: own ?: RaftPaneGridConversationBounds

    /** The row's Expand: the innermost grid's that hands one up, else the outermost's [own]. */
    internal fun expandState(own: RaftPaneGridExpand?): RaftPaneGridExpand? =
        handedUp.values.filter { it.expand != null }.maxByOrNull { it.depth }?.expand ?: own

    /** Which unit each group of row tabs shows: one per outermost grid. */
    internal val tabPicks = RaftPaneGridTabPicks()

    /** Each column's [RaftPaneGridTabsSlot], by its key; plain memory, each slot kept for the column's key. */
    private val tabsSlots = mutableMapOf<String, RaftPaneGridTabsSlot>()

    internal fun tabsSlot(column: String): RaftPaneGridTabsSlot = tabsSlots.getOrPut(column) { RaftPaneGridTabsSlot() }

    /** The cells of the planned row the last time it was laid out (plain memory): a cell new since opened into it. */
    internal var lastRowCells: Set<String>? = null

    internal fun fillIds(ownFill: String?): Set<String> = (listOfNotNull(ownFill) + handedUp.values.mapNotNull { it.fillColumn }).toSet()

    /**
     * Web rowPlan `decideFold` (#189 rule 3) for the column [bounds] declared by a grid at [depth]: whether it folds
     * into the main pane. The row is [row]'s: the outermost grid's own columns, those handed up from outer levels, the
     * column itself (in place of its own where it is handed up already), then those handed up from inner levels, each
     * at its saved width in [sizes]; the main pane at its saved width, else what they leave, at least 400. It folds
     * where the main pane and it can't keep 360 each side by side, or where the row would make any column clip.
     */
    internal fun folds(row: RaftPaneGridRow, depth: Int, bounds: RaftPaneGridColumnBounds, sizes: RaftPaneGridSizes): Boolean {
        val ups = handedUp.values.sortedBy { it.depth }
        val others = { columns: List<RaftPaneGridColumn> -> columns.filter { it.bounds.id != bounds.id }.map { it.planColumn(sizes) } }
        val candidate = RaftRowPlanColumn(bounds.id, listOf(RAFT_ROW_THREAD), listOf(bounds.minWidthDp), sizes.widthDp(bounds))
        val columns =
            row.ownColumns.filter { it.key != bounds.id } +
                others(ups.filter { it.depth < depth }.flatMap { it.columns }) +
                candidate +
                others(ups.filter { it.depth >= depth }.flatMap { it.columns })
        val total = columns.sumOf { it.saved.toDouble() }.toFloat()
        return raftRowDecideFold(
            RaftRowPlanInput(
                available = row.roomDp,
                conversation =
                    RaftRowPlanSize(
                        saved = sizes.savedWidthDp(row.mainBounds) ?: maxOf(RAFT_ROW_CONVERSATION_DEFAULT_WIDTH_DP, row.roomDp - total),
                        contentMin = RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP,
                    ),
                thread = RaftRowPlanSize(sizes.widthDp(bounds), bounds.minWidthDp),
                columns = columns,
            )
        ).fold
    }
}

/**
 * The outermost grid's row as its main pane sees it, from the frame it is composed in (its constraints): [roomDp],
 * the row less the leading column; its [ownColumns] as the plan sees them and their widths as planned
 * ([ownWidthsDp]); and where the main pane's width is kept ([mainBounds]).
 *
 * Values only, compared by value: the grid recomposes whenever a nested grid hands its columns up again (each time a
 * page recomposes, with new column objects), and a new but equal row must not invalidate the pages that read it.
 * Otherwise a page reading it recomposes, hands its columns up again, the grid recomposes, and so on without end.
 */
@Immutable
internal data class RaftPaneGridRow(
    val roomDp: Float,
    val ownColumns: List<RaftRowPlanColumn>,
    val ownWidthsDp: Float,
    val mainBounds: RaftPaneGridColumnBounds,
)

internal val LocalRaftPaneGridRow = compositionLocalOf<RaftPaneGridRow?> { null }

internal val LocalRaftPaneGridHost = compositionLocalOf<RaftPaneGridHost?> { null }

/**
 * Whether the column a cell stands in is on screen: false while it is a row tab not shown (#189 rule 4) or out of
 * sight because another column fills the row ([RaftPaneGrid]'s `fillColumn`). Its pages stay composed; what acts on what the person sees
 * (Back, a page's foreground work) goes by this. True outside any column.
 */
val LocalRaftPaneGridColumnShown = compositionLocalOf { true }

/**
 * #189 rule 5 (web rowPlanStore `expanded`, `toggleRowExpand`, `restoreRowExpand`): the region (a cell id) whose
 * column fills the row, every other column at 160 and clipped; null: none. Pressing its Expand again, Back or Escape
 * restores the row as it was (nothing saved changes). The outermost grid forgets it once the region leaves the row,
 * and when a cell opens into the row (web: a region opening restores the row). Snapshot state; writes only on change.
 * The app keeps one per window (its shell) and hands it to its grid ([RaftPaneGrid]'s `expand`).
 */
@Stable
class RaftPaneGridExpand {
    var region: String? by mutableStateOf(null)
        private set

    fun expand(region: String) {
        if (this.region != region) this.region = region
    }

    /** Expand [region], or restore where it is the one expanded. */
    fun toggle(region: String) {
        if (this.region == region) restore() else expand(region)
    }

    /** Restores the row; false where nothing was expanded. */
    fun restore(): Boolean {
        if (region == null) return false
        region = null
        return true
    }
}

/**
 * A head's Expand (web RowExpandAction, `useRowExpand`) for [region]: [shown] where the row has more than one column
 * or its column is the one expanded, [pressed] where [region] is the region expanded. Compared by value.
 */
@Immutable
data class RaftPaneGridExpandControl(val region: String, val shown: Boolean, val pressed: Boolean, private val state: RaftPaneGridExpand) {
    fun toggle() = state.toggle(region)
}

/**
 * Which cell's head carries its column's row tabs (#189 rule 4, web RowTabs: the head of the column's top region, in
 * place of its title). A [RaftPaneGridColumnHead] claims it; while none does the grid draws the tabs as a strip on top
 * of the column, so they are never out of reach under a page that draws its own head.
 */
@Stable
class RaftPaneGridTabsSlot {
    var owner: String? by mutableStateOf(null)
}

/**
 * What the outermost grid tells the cells of one of its columns, for their heads (#189 step 5): [rowCells], every
 * column of the planned row by its cells' ids, left to right (the sections open in the row, for Move Left / Move
 * Right); [expandShown] and [expanded] for Expand ([expandFor]); the column's row [tabs], carried by the head that
 * holds [tabsSlot]. Values only, compared by value.
 */
@Immutable
data class RaftPaneGridColumnControls(
    val rowCells: List<List<String>>,
    val expandShown: Boolean,
    val expanded: String?,
    val expandState: RaftPaneGridExpand?,
    val tabs: RaftPaneGridRowTabs?,
    val tabsSlot: RaftPaneGridTabsSlot?,
) {
    /** [region]'s Expand; null where the row has no expand state. */
    fun expandFor(region: String): RaftPaneGridExpandControl? =
        expandState?.let { RaftPaneGridExpandControl(region, shown = expandShown, pressed = expanded == region, state = it) }
}

/**
 * The width of the column a cell stands in as it shows on screen, its seam left out; null outside a column, or where
 * the column fills the row. A column clipped under its minimum (#189 rule 2) lays its cells out at the minimum and
 * cuts them at its edge: a head ([RaftPaneGridColumnHead]) lays itself out at this width instead, folding its own
 * actions into ⋯ as it must, so nothing of it is cut off or drawn past its column.
 */
val LocalRaftPaneGridVisibleWidth = compositionLocalOf<Dp?> { null }

/** The controls of the column a cell stands in ([RaftPaneGridColumnControls]); null outside a planned row's column. */
val LocalRaftPaneGridColumnControls = compositionLocalOf<RaftPaneGridColumnControls?> { null }

/** [region]'s Expand in the column it stands in, for a page that draws its own head (the thread's Focus mode). */
@Composable
fun raftPaneGridCellExpand(region: String): RaftPaneGridExpandControl? = LocalRaftPaneGridColumnControls.current?.expandFor(region)

/** How many grids' main panes enclose this point (0: none). */
private val LocalRaftPaneGridDepth = compositionLocalOf { 0 }

/**
 * Whether a page here stands in a grid's row: a [RaftPaneGrid] it declares hands its columns to the outermost grid,
 * which lays them out by the #189 plan (they give way, clip and become tabs, never cover the page). Known on the
 * first frame.
 */
@Composable
fun raftPaneGridInRow(): Boolean = LocalRaftPaneGridHost.current != null && LocalRaftPaneGridRow.current != null

/**
 * The outermost grid's room for the main pane and the columns handed up to it (its row less the
 * leading column and its own columns): what the region around a nested grid measured when its
 * columns stood inside it. Null outside any grid's main pane. Read from the outermost grid's
 * constraints, so it is there on the first frame.
 */
@Composable
fun raftPaneGridRoomDp(): Float? {
    if (LocalRaftPaneGridHost.current == null) return null
    val row = LocalRaftPaneGridRow.current ?: return null
    return row.roomDp - row.ownWidthsDp
}

/**
 * #189 rule 3 (web rowPlan `decideFold`) for a column with [bounds] that a [RaftPaneGrid] declared here would hand up
 * (the reply thread beside the conversation): true where it folds into the main pane, so the page shows it there
 * instead (with Back). It counts every column the row has or would have with it, each at its saved width in [sizes],
 * and the main pane's saved width (else the web's 400 default). Null outside any grid's main pane. The room is there
 * on the first frame; a column handed up by a grid inside the main pane joins a frame later.
 *
 * The answer depends only on the room and the columns the row would have with this one, never on whether it is shown
 * now, so folding never changes it back. It is derived state: the caller recomposes only when the answer changes,
 * not each time a grid hands its columns up again (a grid the caller declares does so whenever it recomposes, which
 * would otherwise loop).
 */
@Composable
fun raftPaneGridFolds(bounds: RaftPaneGridColumnBounds, sizes: RaftPaneGridSizes = LocalRaftPaneGridSizes.current): Boolean? {
    val host = LocalRaftPaneGridHost.current ?: return null
    val row = LocalRaftPaneGridRow.current ?: return null
    val depth = LocalRaftPaneGridDepth.current
    val folds = remember(host, row, depth, bounds, sizes) { derivedStateOf { host.folds(row, depth, bounds, sizes) } }
    return folds.value
}

// ---------------------------------------------------------------- the grid

/**
 * The main pane and [columns] in one row, after an optional [leading] column. [fillColumn] (the
 * Tasks page's task with no room beside its list, or expanded) gives that column the row right of
 * the leading one and the main pane no width (it stays composed). [topInset] keeps the columns' heads out of the status bar, as the main pane's own
 * header does.
 *
 * A grid declared inside another grid's main pane draws only its main pane: its columns (and
 * [fillColumn], [topInset]) go to the outermost grid, after that grid's own columns and those of
 * the levels between, so every column of the window shares one row and one set of width rules
 * ([raftPaneGridRowPlan], #189: short of room all give way in proportion, then clip to 160, then
 * the rightmost become tabs, labelled by [RaftPaneGridColumn.tabLabel] and, for the main pane,
 * [mainTabLabel]).
 *
 * In the planned row the main pane has a saved width too, kept under [mainBounds] (web
 * `slock:conversationWidth`): the innermost grid that names it decides, so each page keeps its own
 * (the conversation's by default, the Tasks page its list's). Letting a seam go (a drag's release, a key, a screen
 * reader's set) saves every shown column's width as shown and the main pane's (web rowSeams), so
 * the row stays exactly as it was let go.
 *
 * [expand] is the row's Expand (#189 rule 5, [RaftPaneGridExpand]), handed up like the columns (the innermost grid
 * that names one decides): the region it names fills the row, every other column at 160 and clipped, the seams still
 * while it does. Each column's cells get its [RaftPaneGridColumnControls] for their heads ([RaftPaneGridColumnHead]):
 * Expand, the row's columns for Move Left / Move Right, and the row tabs, which a head carries in its title's place.
 */
@Composable
fun RaftPaneGrid(
    columns: List<RaftPaneGridColumn>,
    modifier: Modifier = Modifier,
    fillColumn: String? = null,
    topInset: Dp = 0.dp,
    leading: RaftPaneGridLeadingColumn? = null,
    mainTabLabel: String? = null,
    mainBounds: RaftPaneGridColumnBounds? = null,
    expand: RaftPaneGridExpand? = null,
    main: @Composable () -> Unit,
) {
    val outer = LocalRaftPaneGridHost.current
    val depth = LocalRaftPaneGridDepth.current
    if (outer != null) {
        val locals = currentCompositionLocalContext
        val token = remember { Any() }
        SideEffect { outer.handUp(token, RaftPaneGridHandedUp(depth, columns, fillColumn, topInset, locals, mainBounds, expand)) }
        DisposableEffect(outer) { onDispose { outer.handUp(token, null) } }
    }
    val host = outer ?: remember { RaftPaneGridHost() }
    val store = LocalRaftPaneGridSizes.current
    // The row's width comes from its constraints, so the first frame is already planned. (Measured
    // by onSizeChanged, the first frame had no plan: every column at its saved width, the main pane
    // squeezed to nothing and the rightmost columns past the row's end.)
    BoxWithConstraints(modifier) {
        val availableDp = if (constraints.hasBoundedWidth) maxWidth.value else 0f
        // The outermost grid lays out its own columns and every column handed up, left to right, stood and stacked by
        // the arrangement where there is one (#189 step 4, web channelSideLayout).
        val arrangement = LocalRaftPaneGridArrangement.current
        val placed = if (outer != null) emptyList() else host.arranged(host.placed(columns, topInset), arrangement)
        val fillIds = if (outer != null) emptySet() else host.fillIds(fillColumn)
        // Where the main pane's width is kept: the innermost page's that names it, else this grid's, else the conversation's.
        val keptMain = if (outer != null) mainBounds ?: RaftPaneGridConversationBounds else host.mainBounds(mainBounds)
        val beside = leading?.mode == RaftPaneGridLeadingMode.Beside
        val leadingDp =
            when (leading?.mode) {
                RaftPaneGridLeadingMode.Beside -> leading.widthDp + RaftStroke.Hairline.value
                RaftPaneGridLeadingMode.Fill -> availableDp
                else -> 0f
            }
        val roomDp = maxOf(0f, availableDp - leadingDp)
        val filling = placed.any { place -> place.sourceIds.any { it in fillIds } }
        val leadingFills = leading?.mode == RaftPaneGridLeadingMode.Fill
        // The #189 row (raftPaneGridRowPlan) once measured; a column filling the row (the Tasks page's task without room
        // beside its list) and a list filling the row lay out as before.
        val planned = availableDp > 0f && placed.isNotEmpty() && !filling && !leadingFills
        // Rule 5: the region expanded, read here so the row follows it.
        val expandState = if (outer != null) null else host.expandState(expand)
        val expandedRegion = expandState?.region
        val plan =
            if (!planned) {
                null
            } else {
                raftPaneGridRowPlan(
                    roomDp = roomDp,
                    columns = placed.map { it.column.planColumn(store) },
                    mainSavedDp = store.savedWidthDp(keptMain),
                    expanded = expandedRegion,
                )
            }
        // The row's cells as planned, left to right (the sections open in it).
        val rowCells = if (plan == null) emptyList() else placed.map { place -> place.column.cells.map { it.id } }
        if (outer == null && expandState != null) {
            SideEffect {
                val now = rowCells.flatten().toSet()
                val before = host.lastRowCells
                host.lastRowCells = if (plan == null) null else now
                val region = expandState.region
                // The expanded region left the row (closed, the row no longer planned): forgotten (web: the caller
                // forgets it). A cell opened into the row restores it (web rowLayout: a region opening restores).
                if (region != null && (plan?.expanded == null || (before != null && (now - before).isNotEmpty()))) expandState.restore()
            }
        }
        // A column holding tabs (rule 4): the unit it shows, by its first unit's key. Presentation only, never saved.
        val picked = host.tabPicks.picked
        val unitWidths = plan?.let { p -> raftRowUnitWidths(p) { group -> raftPaneGridShownUnit(p, p.groups[group], picked) } }
        val widthOf: Map<String, Float> = plan?.units?.mapIndexed { i, unit -> unit.key to unitWidths!![i] }?.toMap().orEmpty()
        val fitted = placed.map { place -> if (plan != null) widthOf[place.column.bounds.id] ?: 0f else store.widthDp(place.column.bounds) }
        // The tabs each shown unit's column carries where its group holds more than one (web RowTabs).
        val tabsOf: Map<String, RaftPaneGridRowTabs> =
            if (plan == null) {
                emptyMap()
            } else {
                val labels =
                    placed.associate { place ->
                        place.column.bounds.id to (place.column.tabLabel ?: place.column.cells.joinToString(" · ") { it.id })
                    } + (RAFT_ROW_CONVERSATION to (mainTabLabel ?: RAFT_ROW_CONVERSATION))
                plan.groups.filter { it.size > 1 }.associate { group ->
                    val first = plan.units[group.first()].key
                    val shown = plan.units[raftPaneGridShownUnit(plan, group, picked)].key
                    shown to
                        RaftPaneGridRowTabs(
                            tabs = group.map { plan.units[it].key }.map { it to (labels[it] ?: it) },
                            selected = shown,
                            group = first,
                            picks = host.tabPicks,
                        )
                }
            }
        val mainHidden = plan != null && (unitWidths?.firstOrNull() ?: 1f) <= 0f
        // Where a seam is let go in the planned row (#189 rule 1, web rowSeams): every shown column at the width it
        // shows ([changed] at [widthDp]) and the main pane at what they leave, so the plan made from them is the row as
        // it was let go (saving only the dragged column, the others' shares moved and the row jumped). Unplanned, only
        // the column's own.
        val commitRow: (RaftPaneGridColumnBounds, Float) -> Unit = { changed, widthDp ->
            if (plan == null) {
                store.setWidth(changed, widthDp)
            } else {
                val shown =
                    placed.indices.filter { fitted[it] > 0f }.map { i ->
                        val bounds = placed[i].column.bounds
                        bounds to if (bounds.id == changed.id) widthDp else fitted[i]
                    }
                shown.forEach { (bounds, width) -> store.setWidth(bounds, width) }
                if (!mainHidden) store.setWidth(keptMain, maxOf(0f, roomDp - shown.sumOf { it.second.toDouble() }.toFloat()))
            }
        }
        // What the main pane's pages read of the row (raftPaneGridRoomDp, raftPaneGridFolds), from this frame; a
        // nested grid passes on the outermost's.
        val row =
            if (outer != null) LocalRaftPaneGridRow.current
            else if (availableDp <= 0f) null
            else {
                // The own columns' widths wherever they stand (a trailing one stands after the handed-up ones).
                val ownWidthsDp = placed.indices.filter { placed[it].token == null && placed[it].column in columns }.sumOf { fitted[it].toDouble() }.toFloat()
                RaftPaneGridRow(roomDp, columns.map { it.planColumn(store) }, ownWidthsDp, keptMain)
            }
        Row(Modifier.fillMaxSize()) {
            if (leading != null) RaftPaneGridLeadingView(leading)
            // Collapsed by width, never weight(0f) (Compose rejects it): see raftParentPaneLayout.
            Box(
                (if (filling || leadingFills || mainHidden) Modifier.width(0.dp) else Modifier.weight(1f))
                    .fillMaxHeight()
                    .testTag(RAFT_PANE_GRID_MAIN_TEST_TAG)
                    // Beside the leading column the main pane's start is away from the screen edge.
                    .then(if (beside) Modifier.raftConsumeSafeAreaHorizontal(WindowInsetsSides.Start) else Modifier)
                    // A tab not shown (rule 4) is out of reach (web `inert`): no semantics, no focus.
                    .then(if (mainHidden) Modifier.clearAndSetSemantics {} else Modifier)
                    .raftPaneGridInert(mainHidden)
            ) {
                Column(Modifier.fillMaxSize()) {
                    // The row tabs at the pane's own width, so every tab stays in reach while the content clips.
                    val mainTabs = tabsOf[RAFT_ROW_CONVERSATION]
                    if (mainTabs != null) RaftPaneGridTabStrip(mainTabs, RAFT_PANE_GRID_TABS_TEST_TAG + RAFT_ROW_CONVERSATION)
                    // Rule 2: in the planned row the main pane's content keeps 360 and clips.
                    Box(Modifier.weight(1f).fillMaxWidth().then(if (plan != null) Modifier.clipToBounds() else Modifier)) {
                        Box(Modifier.fillMaxSize().raftPaneGridContentMinWidth(if (plan != null) RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP else 0f)) {
                            CompositionLocalProvider(
                                LocalRaftPaneGridHost provides host,
                                LocalRaftPaneGridDepth provides depth + 1,
                                LocalRaftPaneGridRow provides row,
                            ) { main() }
                        }
                    }
                }
            }
            placed.forEachIndexed { index, place ->
                val column = place.column
                key(column.bounds.id) {
                    val draw: @Composable () -> Unit = {
                        // The latest cells handed up (their content may have changed with the same shape): read here,
                        // in the column's own scope, so only the column is drawn anew.
                        val token = place.token
                        val id = column.bounds.id
                        val controls =
                            if (plan == null) {
                                null
                            } else {
                                RaftPaneGridColumnControls(
                                    rowCells = rowCells,
                                    // Web useRowExpand: only with more than one column in the row, or on the one expanded.
                                    expandShown = plan.groups.size > 1 || plan.expandedUnit == id,
                                    expanded = plan.expanded,
                                    expandState = expandState,
                                    tabs = tabsOf[id],
                                    tabsSlot = host.tabsSlot(id),
                                )
                            }
                        RaftPaneGridSideColumn(
                            column = token?.let { host.freshColumn(it, column.bounds.id) } ?: column,
                            widthDp = fitted[index],
                            // A column's width includes its left seam (web), so the main pane has the rest.
                            mainWidthDp = maxOf(0f, roomDp - fitted.sum()),
                            fill = filling && place.sourceIds.any { it in fillIds },
                            hidden = leadingFills || (filling && place.sourceIds.none { it in fillIds }),
                            topInset = place.topInset,
                            tabs = tabsOf[column.bounds.id],
                            tabHidden = plan != null && fitted[index] <= 0f,
                            onCommitWidth = { width -> commitRow(column.bounds, width) },
                            controls = controls,
                            // Expanded, the row is as the expansion has it until restored: no seam drags it.
                            resizable = plan?.expanded == null,
                        )
                    }
                    val locals = place.locals
                    if (locals != null) CompositionLocalProvider(locals) { draw() } else draw()
                }
            }
        }
    }
}

/** The leading column and its right seam. */
@Composable
private fun RowScope.RaftPaneGridLeadingView(leading: RaftPaneGridLeadingColumn) {
    val beside = leading.mode == RaftPaneGridLeadingMode.Beside
    Box(
        when (leading.mode) {
            RaftPaneGridLeadingMode.Fill -> Modifier.weight(1f)
            RaftPaneGridLeadingMode.Beside -> Modifier.width(leading.widthDp.dp + RaftStroke.Hairline)
            RaftPaneGridLeadingMode.Hidden -> Modifier.width(0.dp)
        }.fillMaxHeight()
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    // Beside the main pane the column's inner side is away from the screen edge.
                    .then(if (beside) Modifier.raftConsumeSafeAreaHorizontal(WindowInsetsSides.End) else Modifier)
            ) {
                CompositionLocalProvider(LocalRaftPaneGridHost provides null) { leading.content() }
            }
            // raft-ui: one line-seam between regions, as the rail's.
            if (beside) Box(Modifier.width(RaftStroke.Hairline).fillMaxHeight().background(RaftTheme.colors.lineSeam))
        }
    }
}

/**
 * One column of the grid in a row whose main pane takes the weight: for a host that lays out its
 * own main pane ([RaftPaneGrid] does the rest). [widthDp] is its planned width, [mainWidthDp] the
 * main pane's beside it, which a drag may take down to 360. Narrower than its minimum (#189 rule
 * 2: to 160) its content keeps the minimum and is clipped. [tabs] are its group's row tabs (rule 4): in the head
 * of its top cell where that is a [RaftPaneGridColumnHead] (which claims [RaftPaneGridColumnControls.tabsSlot]),
 * else drawn on top of it; [tabHidden] is a tab not shown: no width, no semantics, no focus (web
 * `inert`), still composed. [onCommitWidth] saves a width let go on its seam (the grid's: the whole
 * row as shown, rule 1); without it the column saves only its own. A double click resets only its own.
 * [controls] go to its cells ([LocalRaftPaneGridColumnControls]); not [resizable] (a row expanded), it has no seam
 * handle.
 */
@Composable
fun RowScope.RaftPaneGridSideColumn(
    column: RaftPaneGridColumn,
    widthDp: Float,
    mainWidthDp: Float,
    fill: Boolean = false,
    hidden: Boolean = false,
    topInset: Dp = 0.dp,
    tabs: RaftPaneGridRowTabs? = null,
    tabHidden: Boolean = false,
    onCommitWidth: ((Float) -> Unit)? = null,
    controls: RaftPaneGridColumnControls? = null,
    resizable: Boolean = true,
) {
    val store = LocalRaftPaneGridSizes.current
    val commit: (Float) -> Unit = { width -> if (onCommitWidth != null) onCommitWidth(width) else store.setWidth(column.bounds, width) }
    var liveWidth by remember { mutableStateOf<Float?>(null) }
    val minWidth = column.bounds.minWidthDp
    val dragMax = raftPaneGridDragMaxDp(minWidth, widthDp, mainWidthDp)
    // Planned under its minimum it shows clipped there; a drag may start from there and saves at least the minimum.
    val dragFloor = minOf(minWidth, widthDp)
    val width = liveWidth?.coerceIn(dragFloor, maxOf(minWidth, dragMax)) ?: widthDp
    val gone = hidden || tabHidden
    val fine = LocalRaftPaneGridPlatform.current.finePointer
    val hit = if (fine) 12.dp else 24.dp
    Box(
        (if (fill) Modifier.weight(1f) else Modifier.width(if (gone) 0.dp else width.dp))
            .fillMaxHeight()
            .testTag(RAFT_PANE_GRID_COLUMN_TEST_TAG + column.bounds.id)
            .background(RaftTheme.colors.layerCanvas)
            // A tab not shown, or a column out of sight while another fills the row ([RaftPaneGrid]'s `fillColumn`), is out of reach (web
            // `inert`): no semantics, no focus.
            .then(if (gone) Modifier.clearAndSetSemantics {} else Modifier)
            .raftPaneGridInert(gone)
    ) {
        val seam = if (fill) 0.dp else RaftStroke.Hairline
        Column(Modifier.fillMaxSize()) {
            if (topInset > 0.dp) Spacer(Modifier.fillMaxWidth().height(topInset))
            // The row tabs at the column's own width, so every tab stays in reach while the content clips; in its top
            // cell's head instead where that head carries them (web RowTabs in place of the title).
            if (tabs != null && controls?.tabsSlot?.owner == null) {
                Box(Modifier.fillMaxWidth().padding(start = seam)) {
                    RaftPaneGridTabStrip(tabs, RAFT_PANE_GRID_TABS_TEST_TAG + column.bounds.id)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                Row(Modifier.fillMaxSize().raftPaneGridContentMinWidth(if (fill) 0f else minWidth)) {
                    Spacer(Modifier.width(seam).fillMaxHeight())
                    // A page in a cell is not in the main pane: a grid it declares is its own.
                    CompositionLocalProvider(
                        LocalRaftPaneGridHost provides null,
                        LocalRaftPaneGridColumnShown provides !gone,
                        LocalRaftPaneGridColumnControls provides controls,
                        // What of the column shows, for the heads (its content may be laid out wider and clipped).
                        LocalRaftPaneGridVisibleWidth provides if (fill) null else (width.dp - seam).coerceAtLeast(0.dp),
                    ) {
                        RaftPaneGridStackView(column, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
        // The column's left seam (web `border-l border-line-seam`), its full height; filling, the column is the row.
        if (!fill) Box(Modifier.width(RaftStroke.Hairline).fillMaxHeight().background(RaftTheme.colors.lineSeam))
        if (!fill && !gone && resizable) {
            var dragStart by remember { mutableStateOf(width) }
            val currentWidth by rememberUpdatedState(width)
            val bounds = column.bounds
            RaftPaneSplitter(
                vertical = true,
                label = column.resizeLabel,
                value = width,
                range = bounds.minWidthDp..maxOf(bounds.minWidthDp, dragMax),
                onSet = { commit(it.coerceIn(bounds.minWidthDp, dragMax)) },
                onDragStart = { dragStart = currentWidth },
                // The seam is the column's left edge: dragging left widens it.
                onDrag = { total -> liveWidth = (dragStart - total).coerceIn(dragFloor, maxOf(bounds.minWidthDp, dragMax)) },
                onDragEnd = {
                    liveWidth?.let(commit)
                    liveWidth = null
                },
                onStep = { step -> commit((width - step).coerceIn(bounds.minWidthDp, dragMax)) },
                onHome = { commit(bounds.minWidthDp) },
                onEnd = { commit(dragMax) },
                // Web resetColumnWidth: the column back to its default, nothing else saved (the plan shares the room).
                onReset = { store.setWidth(bounds, bounds.defaultWidthDp) },
                modifier = Modifier.offset(x = 0.5.dp - hit / 2),
            )
        }
    }
}

/** A column's cells stacked, with the seams and splitters between them. */
@Composable
private fun RaftPaneGridStackView(column: RaftPaneGridColumn, modifier: Modifier) {
    val store = LocalRaftPaneGridSizes.current
    val cells = column.cells
    val key = cells.joinToString("+") { it.id }
    val collapsible = cells.size > 1
    val collapsed = cells.map { collapsible && store.isCollapsed(it.id) }
    val defaults = cells.map { it.defaultShare }.let { d -> d.sum().let { total -> d.map { it / total } } }
    val saved = store.shares(key, defaults)
    var live by remember(key) { mutableStateOf<List<Float>?>(null) }
    val shares = live ?: saved
    val fine = LocalRaftPaneGridPlatform.current.finePointer
    val hit = if (fine) 12.dp else 24.dp
    // A folded cell is the head of the input in use: 56, or 44 under a fine pointer (sizing v12).
    val headDp = RaftTheme.controls.headHeight.value
    BoxWithConstraints(modifier) {
        val heightDp = maxHeight.value
        val short = cells.size > 1 && column.shortSwitch != null && heightDp < raftPaneGridStackMinHeightDp(cells.size)
        if (short) {
            val shown = cells.firstOrNull { it.id == column.shortShownCell } ?: cells.first()
            Column(Modifier.fillMaxSize()) {
                column.shortSwitch?.invoke()
                Box(Modifier.weight(1f).fillMaxWidth()) { RaftPaneGridCellHost(shown, canFold = false, folded = false) {} }
            }
            return@BoxWithConstraints
        }
        val layout = raftPaneGridStack(heightDp, shares, collapsed, headDp)
        Column(Modifier.fillMaxSize()) {
            cells.forEachIndexed { i, cell ->
                key(cell.id) {
                    if (layout.seamAbove[i]) Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(RaftTheme.colors.lineSeam))
                    Box(Modifier.height(layout.heights[i].dp).fillMaxWidth().clipToBounds()) {
                        RaftPaneGridCellHost(cell, collapsible, collapsed[i]) { store.setCollapsed(cell.id, it) }
                    }
                }
            }
        }
        // Web StackSeam: the seam above an open cell, under an open one, drags the pair's split.
        var top = 0f
        cells.indices.forEach { i ->
            if (i > 0) {
                if (layout.seamAbove[i] && !collapsed[i]) key(cells[i - 1].id, cells[i].id) {
                    val seam = i - 1
                    val seamY = top
                    var dragShares by remember(key, seam) { mutableStateOf(shares) }
                    val currentShares by rememberUpdatedState(shares)
                    val perDp = raftPaneGridSharePerDp(heightDp, shares, collapsed, headDp)
                    fun moved(base: List<Float>, to: Float) = raftPaneGridMoveSeam(base, seam, to, heightDp, collapsed, headDp)
                    RaftPaneSplitter(
                        vertical = false,
                        label = column.splitLabel ?: column.resizeLabel,
                        value = shares[seam] / (shares[seam] + shares[seam + 1]),
                        range = 0f..1f,
                        onSet = { fraction -> store.setShares(key, moved(shares, fraction * (shares[seam] + shares[seam + 1]))) },
                        onDragStart = { dragShares = currentShares },
                        onDrag = { total -> live = moved(dragShares, dragShares[seam] + total * perDp) },
                        onDragEnd = {
                            live?.let { store.setShares(key, it) }
                            live = null
                        },
                        onStep = { step -> store.setShares(key, moved(shares, shares[seam] + step * perDp)) },
                        onHome = { store.setShares(key, moved(shares, 0f)) },
                        onEnd = { store.setShares(key, moved(shares, shares[seam] + shares[seam + 1])) },
                        onReset = { store.setShares(key, defaults) },
                        modifier = Modifier.offset(y = seamY.dp + 0.5.dp - hit / 2),
                    )
                }
                if (layout.seamAbove[i]) top += 1f
            }
            top += layout.heights[i]
        }
    }
}

@Composable
private fun RaftPaneGridCellHost(cell: RaftPaneGridCell, canFold: Boolean, folded: Boolean, onCollapsedChange: (Boolean) -> Unit) {
    val currentOnChange by rememberUpdatedState(onCollapsedChange)
    val scope =
        remember(cell.id, canFold, folded) {
            object : RaftPaneGridCellScope {
                override val cellId = cell.id
                override val collapsible = canFold
                override val collapsed = folded

                override fun setCollapsed(collapsed: Boolean) = currentOnChange(collapsed)
            }
        }
    cell.content(scope)
}

/** The resize pointer a mouse shows over a splitter: left-right for a [vertical] seam, else up-down. */
internal expect fun raftSplitterPointerIcon(vertical: Boolean): PointerIcon

/**
 * The grid's splitter on a seam (web DS Resizable: ColumnResizeHandle, StackSeam): nothing at
 * rest but the seam the layout draws; a 1dp `foreground-muted` line while hovered or dragged, 2dp
 * `foreground` with keyboard focus. The hit area is 12dp across the seam, 24dp by touch.
 * [vertical]: a vertical seam dragged sideways; else a horizontal one dragged up and down.
 * [onDrag] gets the distance from the drag's start (right / down positive); arrows step 16dp
 * (Shift: 64), Home / End go to the ends, a double click or tap restores the default, and
 * [onEnter] (web `snap`: the sidebar's collapse) takes Enter. A mouse over it shows the resize
 * pointer (web `cursor-col-resize`). For screen readers it is an adjustable value over [range].
 * It keeps the iOS back swipe off its drag.
 */
@Composable
fun RaftPaneSplitter(
    vertical: Boolean,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onSet: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (totalDp: Float) -> Unit,
    onDragEnd: () -> Unit,
    onStep: (stepDp: Float) -> Unit,
    onHome: () -> Unit,
    onEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    onEnter: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    var dragging by remember { mutableStateOf(false) }
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnReset by rememberUpdatedState(onReset)
    val density = LocalDensity.current.density
    val hit = if (LocalRaftPaneGridPlatform.current.finePointer) 12.dp else 24.dp
    val line =
        when {
            focused -> RaftTheme.colors.foreground
            hovered || dragging -> RaftTheme.colors.foregroundMuted
            else -> null
        }
    Box(
        modifier
            .then(if (vertical) Modifier.width(hit).fillMaxHeight() else Modifier.height(hit).fillMaxWidth())
            .then(LocalRaftPaneGridPlatform.current.splitterModifier)
            .raftKeepsBackSwipe()
            .pointerHoverIcon(raftSplitterPointerIcon(vertical))
            .hoverable(interaction)
            .focusable(interactionSource = interaction)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range)
                setProgress { target ->
                    onSet(target)
                    true
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = if (event.isShiftPressed) RAFT_PANE_GRID_KEYBOARD_STEP_LARGE_DP else RAFT_PANE_GRID_KEYBOARD_STEP_DP
                when (event.key) {
                    if (vertical) Key.DirectionLeft else Key.DirectionUp -> onStep(-step)
                    if (vertical) Key.DirectionRight else Key.DirectionDown -> onStep(step)
                    // Web: Home / End go to the minimum / maximum (a column's width, the upper cell's height).
                    Key.MoveHome -> onHome()
                    Key.MoveEnd -> onEnd()
                    Key.Enter, Key.NumPadEnter -> onEnter?.invoke() ?: return@onKeyEvent false
                    else -> return@onKeyEvent false
                }
                true
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentOnReset() }) }
            .pointerInput(vertical, density) {
                var total = 0f
                val start: (Offset) -> Unit = {
                    total = 0f
                    dragging = true
                    currentOnDragStart()
                }
                val end: () -> Unit = {
                    if (dragging) {
                        dragging = false
                        currentOnDragEnd()
                    }
                }
                if (vertical) {
                    detectHorizontalDragGestures(onDragStart = start, onDragEnd = end, onDragCancel = end) { change, delta ->
                        change.consume()
                        total += delta / density
                        currentOnDrag(total)
                    }
                } else {
                    detectVerticalDragGestures(onDragStart = start, onDragEnd = end, onDragCancel = end) { change, delta ->
                        change.consume()
                        total += delta / density
                        currentOnDrag(total)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (line != null) {
            val stroke = if (focused) 2.dp else RaftStroke.Hairline
            Box((if (vertical) Modifier.width(stroke).fillMaxHeight() else Modifier.height(stroke).fillMaxWidth()).background(line))
        }
    }
}
