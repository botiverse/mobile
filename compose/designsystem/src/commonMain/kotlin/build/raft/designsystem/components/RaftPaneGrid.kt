package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
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
 */

/** Web `MAIN_COLUMN_MIN_WIDTH`: the main pane never renders narrower than this beside a column. */
const val RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP = 360f

/** Web `MIN_SECTION_HEIGHT`: a stacked cell, its head included, keeps 160 while it is open. */
const val RAFT_PANE_GRID_CELL_MIN_HEIGHT_DP = 160f

/** Web `HEAD_HEIGHT` (PanelHeader): a folded cell is its 56dp head, the seam under it included. */
const val RAFT_PANE_GRID_HEAD_HEIGHT_DP = 56f

/** Web `CHANNEL_SIDE_MAX_SAVED_WIDTH`: the widest width kept; the rendered one stops where the main pane keeps its minimum. */
const val RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP = 4000f

/** Web `KEYBOARD_STEP` / `KEYBOARD_STEP_LARGE` (also the split's): arrows move a seam 16, with Shift 64. */
const val RAFT_PANE_GRID_KEYBOARD_STEP_DP = 16f
const val RAFT_PANE_GRID_KEYBOARD_STEP_LARGE_DP = 64f

/** A kind of column and its width rules (web `COLUMN_BOUNDS`); [id] keys its kept width. */
@Immutable
data class RaftPaneGridColumnBounds(val id: String, val defaultWidthDp: Float, val minWidthDp: Float)


// ---------------------------------------------------------------- the rules (pure)

/**
 * Web `fitColumnWidths`: rendered widths of the columns, left to right, sharing [availableDp] with
 * a main pane of at least [mainMinDp]. Where the kept widths don't fit, the rightmost column gives
 * way first, down to its minimum, then the one before it. Kept widths are never changed.
 */
fun raftPaneGridFitWidths(
    availableDp: Float,
    columns: List<Pair<Float, Float>>,
    mainMinDp: Float = RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP,
): List<Float> =
    columns.mapIndexed { index, (width, min) ->
        val left = columns.take(index).sumOf { it.first.toDouble() }.toFloat()
        val rightMins = columns.drop(index + 1).sumOf { it.second.toDouble() }.toFloat()
        maxOf(min, minOf(width, availableDp - mainMinDp - left - rightMins))
    }

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
 * [heightDp]; a folded one only its head. With every cell folded the heads stand at the top.
 */
fun raftPaneGridStack(heightDp: Float, shares: List<Float>, collapsed: List<Boolean>): RaftPaneGridStackLayout {
    val seamAbove = shares.indices.map { i -> i > 0 && !collapsed[i - 1] }
    val fixed = seamAbove.count { it } + collapsed.count { it } * RAFT_PANE_GRID_HEAD_HEIGHT_DP
    val openTotal = shares.indices.filter { !collapsed[it] }.sumOf { shares[it].toDouble() }.toFloat()
    val room = maxOf(0f, heightDp - fixed)
    val heights =
        shares.indices.map { i ->
            if (collapsed[i]) RAFT_PANE_GRID_HEAD_HEIGHT_DP
            else maxOf(RAFT_PANE_GRID_HEAD_HEIGHT_DP, shares[i] / (if (openTotal > 0f) openTotal else 1f) * room)
        }
    return RaftPaneGridStackLayout(heights, seamAbove)
}

/** Web `perPixel`: the share one dp of the stack is worth. */
fun raftPaneGridSharePerDp(heightDp: Float, shares: List<Float>, collapsed: List<Boolean>): Float {
    val layout = raftPaneGridStack(heightDp, shares, collapsed)
    val fixed = layout.seamAbove.count { it } + collapsed.count { it } * RAFT_PANE_GRID_HEAD_HEIGHT_DP
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
): List<Float> {
    val pair = shares[seam] + shares[seam + 1]
    val min =
        minOf(
            pair * 0.45f,
            if (heightDp > 0f) RAFT_PANE_GRID_CELL_MIN_HEIGHT_DP * raftPaneGridSharePerDp(heightDp, shares, collapsed) else 0.1f,
        )
    val out = shares.toMutableList()
    out[seam] = top.coerceIn(min, pair - min)
    out[seam + 1] = pair - out[seam]
    return out
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
)

// ---------------------------------------------------------------- the grid

/**
 * The main pane and [columns] in one row. [fillColumn] (Focus mode) gives that column the whole
 * row and the main pane no width (it stays composed). [topInset] keeps the columns' heads out of
 * the status bar, as the main pane's own header does.
 */
@Composable
fun RaftPaneGrid(
    columns: List<RaftPaneGridColumn>,
    modifier: Modifier = Modifier,
    fillColumn: String? = null,
    topInset: Dp = 0.dp,
    main: @Composable () -> Unit,
) {
    val store = LocalRaftPaneGridSizes.current
    val density = LocalDensity.current.density
    var availableDp by remember { mutableStateOf(0f) }
    val fitted =
        raftPaneGridFitWidths(
            availableDp = availableDp.takeIf { it > 0f } ?: Float.MAX_VALUE,
            columns = columns.map { store.widthDp(it.bounds) to it.bounds.minWidthDp },
        )
    val filling = fillColumn != null && columns.any { it.bounds.id == fillColumn }
    Row(modifier.onSizeChanged { availableDp = it.width / density }) {
        // Collapsed by width, never weight(0f) (Compose rejects it): see raftParentPaneLayout.
        Box((if (filling) Modifier.width(0.dp) else Modifier.weight(1f)).fillMaxHeight()) { main() }
        columns.forEachIndexed { index, column ->
            key(column.bounds.id) {
                RaftPaneGridSideColumn(
                    column = column,
                    widthDp = fitted[index],
                    // A column's width includes its left seam (web), so the main pane has the rest.
                    mainWidthDp = maxOf(0f, availableDp - fitted.sum()),
                    fill = filling && column.bounds.id == fillColumn,
                    hidden = filling && column.bounds.id != fillColumn,
                    topInset = topInset,
                )
            }
        }
    }
}

/**
 * One column of the grid in a row whose main pane takes the weight: for a host that lays out its
 * own main pane ([RaftPaneGrid] does the rest). [widthDp] is its fitted width, [mainWidthDp] the
 * main pane's beside it, which a drag may take down to 360.
 */
@Composable
fun RowScope.RaftPaneGridSideColumn(
    column: RaftPaneGridColumn,
    widthDp: Float,
    mainWidthDp: Float,
    fill: Boolean = false,
    hidden: Boolean = false,
    topInset: Dp = 0.dp,
) {
    val store = LocalRaftPaneGridSizes.current
    var liveWidth by remember { mutableStateOf<Float?>(null) }
    val dragMax = raftPaneGridDragMaxDp(column.bounds.minWidthDp, widthDp, mainWidthDp)
    val width = (liveWidth ?: widthDp).coerceIn(column.bounds.minWidthDp, maxOf(column.bounds.minWidthDp, dragMax))
    val fine = LocalRaftPaneGridPlatform.current.finePointer
    val hit = if (fine) 12.dp else 24.dp
    Box(
        (if (fill) Modifier.weight(1f) else Modifier.width(if (hidden) 0.dp else width.dp))
            .fillMaxHeight()
            .background(RaftTheme.colors.layerCanvas)
    ) {
        Row(Modifier.fillMaxSize()) {
            // The column's left seam (web `border-l border-line-seam`); in Focus mode the column is the row.
            if (!fill) Box(Modifier.width(RaftStroke.Hairline).fillMaxHeight().background(RaftTheme.colors.lineSeam))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (topInset > 0.dp) Spacer(Modifier.fillMaxWidth().height(topInset))
                RaftPaneGridStackView(column, Modifier.weight(1f).fillMaxWidth())
            }
        }
        if (!fill && !hidden) {
            var dragStart by remember { mutableStateOf(width) }
            val currentWidth by rememberUpdatedState(width)
            val bounds = column.bounds
            RaftPaneSplitter(
                vertical = true,
                label = column.resizeLabel,
                value = width,
                range = bounds.minWidthDp..maxOf(bounds.minWidthDp, dragMax),
                onSet = { store.setWidth(bounds, it.coerceIn(bounds.minWidthDp, dragMax)) },
                onDragStart = { dragStart = currentWidth },
                // The seam is the column's left edge: dragging left widens it.
                onDrag = { total -> liveWidth = (dragStart - total).coerceIn(bounds.minWidthDp, maxOf(bounds.minWidthDp, dragMax)) },
                onDragEnd = {
                    liveWidth?.let { store.setWidth(bounds, it) }
                    liveWidth = null
                },
                onStep = { step -> store.setWidth(bounds, (width - step).coerceIn(bounds.minWidthDp, dragMax)) },
                onHome = { store.setWidth(bounds, bounds.minWidthDp) },
                onEnd = { store.setWidth(bounds, dragMax) },
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
        val layout = raftPaneGridStack(heightDp, shares, collapsed)
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
                    val perDp = raftPaneGridSharePerDp(heightDp, shares, collapsed)
                    fun moved(base: List<Float>, to: Float) = raftPaneGridMoveSeam(base, seam, to, heightDp, collapsed)
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
        remember(canFold, folded) {
            object : RaftPaneGridCellScope {
                override val collapsible = canFold
                override val collapsed = folded

                override fun setCollapsed(collapsed: Boolean) = currentOnChange(collapsed)
            }
        }
    cell.content(scope)
}

/**
 * The grid's splitter on a seam (web DS Resizable: ColumnResizeHandle, StackSeam): nothing at
 * rest but the seam the layout draws; a 1dp `foreground-muted` line while hovered or dragged, 2dp
 * `foreground` with keyboard focus. The hit area is 12dp across the seam, 24dp by touch.
 * [vertical]: a vertical seam dragged sideways; else a horizontal one dragged up and down.
 * [onDrag] gets the distance from the drag's start (right / down positive); arrows step 16dp
 * (Shift: 64), Home / End go to the ends, a double click or tap restores the default. For screen
 * readers it is an adjustable value over [range]. It keeps the iOS back swipe off its drag.
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

