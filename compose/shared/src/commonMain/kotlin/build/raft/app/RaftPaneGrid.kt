package build.raft.app

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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.gesture.raftKeepsBackSwipe
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject

/*
 * The wide layout's pane grid (artin 11-02, "first principles, like a grid view"; web
 * ChannelSideArea + ColumnResizeHandle + lib/panelBounds.ts): a main pane, then columns right of
 * it, each a vertical stack of cells. Every boundary is a splitter: a column's left seam drags its
 * width, the seam between two stacked cells drags their split. A cell folds to its 56dp head where
 * it shares its column, and closes. Pages only declare their columns and cells
 * ([RaftPaneGridColumn], [RaftPaneGridCell]); the grid lays them out, drags, and keeps the sizes
 * and folds ([RaftPaneGridStore]) as web keeps them, per device (web: localStorage).
 *
 * The main pane keeps its place in the composition whatever the columns do, so opening or
 * closing a column never remounts it.
 */

/** Web `MAIN_COLUMN_MIN_WIDTH`: the main pane never renders narrower than this beside a column. */
internal const val RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP = 360f

/** Web `MIN_SECTION_HEIGHT`: a stacked cell, its head included, keeps 160 while it is open. */
internal const val RAFT_PANE_GRID_CELL_MIN_HEIGHT_DP = 160f

/** Web `HEAD_HEIGHT` (PanelHeader): a folded cell is its 56dp head, the seam under it included. */
internal const val RAFT_PANE_GRID_HEAD_HEIGHT_DP = 56f

/** Web `CHANNEL_SIDE_MAX_SAVED_WIDTH`: the widest width kept; the rendered one stops where the main pane keeps its minimum. */
internal const val RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP = 4000f

/** Web `KEYBOARD_STEP` / `KEYBOARD_STEP_LARGE` (also the split's): arrows move a seam 16, with Shift 64. */
internal const val RAFT_PANE_GRID_KEYBOARD_STEP_DP = 16f
internal const val RAFT_PANE_GRID_KEYBOARD_STEP_LARGE_DP = 64f

/** A kind of column and its width rules (web `COLUMN_BOUNDS`); [id] keys its kept width. */
@Immutable
internal data class RaftPaneGridColumnBounds(val id: String, val defaultWidthDp: Float, val minWidthDp: Float)

internal object RaftPaneGridColumns {
    /** Web `COLUMN_BOUNDS.thread`: 400, at least 360, growing until the main pane is at 360. */
    val Thread = RaftPaneGridColumnBounds(id = "thread", defaultWidthDp = 400f, minWidthDp = 360f)

    /** Web `COLUMN_BOUNDS.sideCards`: the Tasks / Files column, 360, at least 280, its left seam included. */
    val SideCards = RaftPaneGridColumnBounds(id = "sideCards", defaultWidthDp = 360f, minWidthDp = 280f)
}

// ---------------------------------------------------------------- the rules (pure)

/**
 * Web `fitColumnWidths`: rendered widths of the columns, left to right, sharing [availableDp] with
 * a main pane of at least [mainMinDp]. Where the kept widths don't fit, the rightmost column gives
 * way first, down to its minimum, then the one before it. Kept widths are never changed.
 */
internal fun raftPaneGridFitWidths(
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
internal fun raftPaneGridDragMaxDp(
    minWidthDp: Float,
    renderedWidthDp: Float,
    mainWidthDp: Float,
    mainMinDp: Float = RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP,
): Float = maxOf(minWidthDp, minOf(RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP, renderedWidthDp + maxOf(0f, mainWidthDp - mainMinDp)))

/** Web `sideCardsStackMinHeight`: stacked cells each keep 160, plus the seams between them. */
internal fun raftPaneGridStackMinHeightDp(cells: Int): Float = cells * RAFT_PANE_GRID_CELL_MIN_HEIGHT_DP + (cells - 1)

/** A stack laid out: each cell's height, and whether a seam stands above it (none above the first or under a folded head). */
internal data class RaftPaneGridStackLayout(val heights: List<Float>, val seamAbove: List<Boolean>)

/**
 * Web `useStack` / `flexIn`: an open cell takes its share of what folded heads and seams leave of
 * [heightDp]; a folded one only its head. With every cell folded the heads stand at the top.
 */
internal fun raftPaneGridStack(heightDp: Float, shares: List<Float>, collapsed: List<Boolean>): RaftPaneGridStackLayout {
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
internal fun raftPaneGridSharePerDp(heightDp: Float, shares: List<Float>, collapsed: List<Boolean>): Float {
    val layout = raftPaneGridStack(heightDp, shares, collapsed)
    val fixed = layout.seamAbove.count { it } + collapsed.count { it } * RAFT_PANE_GRID_HEAD_HEIGHT_DP
    val openTotal = shares.indices.filter { !collapsed[it] }.sumOf { shares[it].toDouble() }.toFloat()
    return (if (openTotal > 0f) openTotal else 1f) / maxOf(1f, heightDp - fixed)
}

/**
 * Web `withTop`: the shares with seam [seam]'s upper cell at [top]; what it takes the lower one
 * gives (the pair keeps its total), each kept at least 160dp (or 45% of the pair in a short stack).
 */
internal fun raftPaneGridMoveSeam(
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
 * The grid's kept sizes and folds, per device as web keeps them in localStorage:
 * - `raftPaneGrid:width:<column>`: a column's width in dp (web `slock:threadPanelWidth`; the
 *   Tasks / Files column's `width` in `slock:channelSideLayout`), saved when a drag or key ends;
 * - `raftPaneGrid:splits`: web's `slock:channelSideSplits` shape, `{"tasks+files":[0.6,0.4]}`;
 * - `raftPaneGrid:collapsed`: folded cells by id, web's `slock:channelSideCardsCollapsed` shape.
 */
@Stable
internal class RaftPaneGridStore(private val preferences: () -> SharedPreferenceStore? = { null }) {
    private val store: SharedPreferenceStore? by lazy { runCatching(preferences).getOrNull() }
    private var version by mutableIntStateOf(0)
    private val widths = mutableMapOf<String, Float>()
    private var splits: MutableMap<String, List<Float>>? = null
    private var folded: MutableMap<String, Boolean>? = null

    private fun read(key: String): String? = runCatching { store?.getString(key) }.getOrNull()

    private fun write(key: String, value: String) {
        runCatching { store?.putString(key, value) }
        version++
    }

    /** The kept width, within the saved range (280 to 4000, web `parseChannelSideLayout`); else the column's default. */
    fun widthDp(bounds: RaftPaneGridColumnBounds): Float {
        version
        return widths.getOrPut(bounds.id) {
            read("$WIDTH_KEY_PREFIX${bounds.id}")?.trim()?.toFloatOrNull()
                ?.takeIf { it in bounds.minWidthDp..RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP }
                ?: bounds.defaultWidthDp
        }
    }

    fun setWidth(bounds: RaftPaneGridColumnBounds, widthDp: Float) {
        val width = widthDp.roundToInt().toFloat().coerceIn(bounds.minWidthDp, RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP)
        if (widthDp(bounds) == width) return
        widths[bounds.id] = width
        write("$WIDTH_KEY_PREFIX${bounds.id}", width.roundToInt().toString())
    }

    private fun splitsMap(): MutableMap<String, List<Float>> =
        splits ?: decodeRaftPaneGridSplits(read(SPLITS_KEY)).toMutableMap().also { splits = it }

    /** Web `savedOr`: the kept shares of the stack [key] (its cell ids joined by `+`), else [defaults]. */
    fun shares(key: String, defaults: List<Float>): List<Float> {
        version
        val saved = splitsMap()[key]
        return if (saved != null && saved.size == defaults.size && saved.all { it > 0f && it.isFinite() }) saved else defaults
    }

    fun setShares(key: String, shares: List<Float>) {
        splitsMap()[key] = shares
        write(SPLITS_KEY, encodeRaftPaneGridSplits(splitsMap()))
    }

    private fun foldedMap(): MutableMap<String, Boolean> =
        folded ?: decodeRaftPaneGridFlags(read(COLLAPSED_KEY)).toMutableMap().also { folded = it }

    fun isCollapsed(cell: String): Boolean {
        version
        return foldedMap()[cell] == true
    }

    fun setCollapsed(cell: String, collapsed: Boolean) {
        if (isCollapsed(cell) == collapsed) return
        foldedMap()[cell] = collapsed
        write(COLLAPSED_KEY, encodeRaftPaneGridFlags(foldedMap()))
    }

    companion object {
        const val WIDTH_KEY_PREFIX = "raftPaneGrid:width:"
        const val SPLITS_KEY = "raftPaneGrid:splits"
        const val COLLAPSED_KEY = "raftPaneGrid:collapsed"

        /** App-level: one grid's sizes for every page, in the shared local preferences. */
        val App: RaftPaneGridStore = RaftPaneGridStore { raftSharedPreferenceStore() }
    }
}

internal val LocalRaftPaneGridStore = staticCompositionLocalOf { RaftPaneGridStore.App }

private fun shareText(value: Float): String = ((value * 10_000f).roundToInt() / 10_000.0).toString()

internal fun encodeRaftPaneGridSplits(splits: Map<String, List<Float>>): String =
    splits.entries.joinToString(",", "{", "}") { (key, shares) -> "\"$key\":" + shares.joinToString(",", "[", "]", transform = ::shareText) }

internal fun decodeRaftPaneGridSplits(raw: String?): Map<String, List<Float>> {
    val value = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return emptyMap()
    return value.mapNotNull { (key, element) ->
        val shares = (element as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.floatOrNull ?: return@mapNotNull null }
        shares?.let { key to it }
    }.toMap()
}

internal fun encodeRaftPaneGridFlags(flags: Map<String, Boolean>): String =
    flags.entries.joinToString(",", "{", "}") { (key, on) -> "\"$key\":$on" }

/** Web `readFlags`: only a JSON `true` is on; anything unreadable is off. */
internal fun decodeRaftPaneGridFlags(raw: String?): Map<String, Boolean> {
    val value: JsonObject = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return emptyMap()
    return value.mapValues { (_, element) -> (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true }
}

// ---------------------------------------------------------------- what pages declare

/** What a cell knows of its place: it may fold where it shares its column ([collapsible]). */
@Stable
internal interface RaftPaneGridCellScope {
    val collapsible: Boolean
    val collapsed: Boolean

    fun setCollapsed(collapsed: Boolean)
}

/**
 * One cell of a column: [id] keys its fold and, joined with its neighbours', the stack's split;
 * [defaultShare] is its part of the column's height before anyone drags (web `defaultShares`).
 * The cell draws its own head ([RaftPaneGridCard], or a page's own header); its close is the
 * page's, which then stops declaring it.
 */
@Immutable
internal class RaftPaneGridCell(
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
internal class RaftPaneGridColumn(
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
internal fun RaftPaneGrid(
    columns: List<RaftPaneGridColumn>,
    modifier: Modifier = Modifier,
    fillColumn: String? = null,
    topInset: Dp = 0.dp,
    main: @Composable () -> Unit,
) {
    val store = LocalRaftPaneGridStore.current
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
internal fun RowScope.RaftPaneGridSideColumn(
    column: RaftPaneGridColumn,
    widthDp: Float,
    mainWidthDp: Float,
    fill: Boolean = false,
    hidden: Boolean = false,
    topInset: Dp = 0.dp,
) {
    val store = LocalRaftPaneGridStore.current
    var liveWidth by remember { mutableStateOf<Float?>(null) }
    val dragMax = raftPaneGridDragMaxDp(column.bounds.minWidthDp, widthDp, mainWidthDp)
    val width = (liveWidth ?: widthDp).coerceIn(column.bounds.minWidthDp, maxOf(column.bounds.minWidthDp, dragMax))
    val fine = raftPlatformHasFinePointer()
    val hit = if (fine) 12.dp else 24.dp
    Box(
        (if (fill) Modifier.weight(1f) else Modifier.width(if (hidden) 0.dp else width.dp))
            .fillMaxHeight()
            .background(RaftColor.Canvas)
    ) {
        Row(Modifier.fillMaxSize()) {
            // The column's left seam (web `border-l border-line-seam`); in Focus mode the column is the row.
            if (!fill) Box(Modifier.width(RaftAppStroke.Thin).fillMaxHeight().background(RaftColor.BorderSeam))
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
    val store = LocalRaftPaneGridStore.current
    val cells = column.cells
    val key = cells.joinToString("+") { it.id }
    val collapsible = cells.size > 1
    val collapsed = cells.map { collapsible && store.isCollapsed(it.id) }
    val defaults = cells.map { it.defaultShare }.let { d -> d.sum().let { total -> d.map { it / total } } }
    val saved = store.shares(key, defaults)
    var live by remember(key) { mutableStateOf<List<Float>?>(null) }
    val shares = live ?: saved
    val fine = raftPlatformHasFinePointer()
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
                    if (layout.seamAbove[i]) Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderSeam))
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
 * A card in a grid cell (web ChannelSideCard): the 56dp panel head (the collapse chevron where it
 * shares its column, the title, [actions], the ×), then [body]. Folded, the head stays where it is
 * unfolded, its actions hidden, and the body stays composed with no height (web keeps it mounted).
 */
@Composable
internal fun RaftPaneGridCellScope.RaftPaneGridCard(
    title: String,
    closeLabel: String,
    onClose: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    val folded = collapsible && collapsed
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        RaftPanelBackHeader(
            title = title,
            subtitle = null,
            onBack = null,
            leadingContent =
                if (collapsible) {
                    {
                        val label = stringResource(if (folded) Res.string.thread_side_card_expand else Res.string.thread_side_card_collapse, title)
                        RaftIconButton(
                            onClick = { setCollapsed(!folded) },
                            tooltip = label,
                            variant = RaftButtonVariant.Ghost,
                            size = RaftIconButtonSize.Lg,
                            // Web `aria-expanded`.
                            modifier =
                                Modifier.semantics {
                                    if (folded) expand { setCollapsed(false); true } else collapse { setCollapsed(true); true }
                                },
                        ) {
                            RaftSlotIcon(if (folded) RaftLucideIconName.ChevronRight else RaftLucideIconName.ChevronDown)
                        }
                    }
                } else {
                    null
                },
            trailingContent = {
                if (!folded) actions?.invoke()
                RaftIconButton(onClick = onClose, tooltip = closeLabel, variant = RaftButtonVariant.Ghost, size = RaftIconButtonSize.Lg) {
                    RaftSlotIcon(RaftLucideIconName.X)
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { body() }
    }
}

/**
 * The grid's splitter on a seam (web DS Resizable: ColumnResizeHandle, StackSeam): nothing at
 * rest but the seam the layout draws; a 1dp `foreground-muted` line while hovered or dragged, 2dp
 * `foreground` with keyboard focus. The hit area is 12dp across the seam, 24dp by touch.
 * [vertical]: a vertical seam dragged sideways; else a horizontal one dragged up and down.
 * [onDrag] gets the distance from the drag's start (right / down positive); arrows step 16dp
 * (Shift: 64), Home / End go to the ends, a double click or tap restores the default. For screen
 * readers it is an adjustable value over [range]. Like [RaftListPaneResizeHandle] it keeps the
 * iOS back swipe off its drag.
 */
@Composable
internal fun RaftPaneSplitter(
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
    val hit = if (raftPlatformHasFinePointer()) 12.dp else 24.dp
    val line =
        when {
            focused -> RaftColor.TextBlack
            hovered || dragging -> RaftColor.ForegroundMuted
            else -> null
        }
    Box(
        modifier
            .then(if (vertical) Modifier.width(hit).fillMaxHeight() else Modifier.height(hit).fillMaxWidth())
            .raftNativeDispatchCapture()
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
            val stroke = if (focused) 2.dp else RaftAppStroke.Thin
            Box((if (vertical) Modifier.width(stroke).fillMaxHeight() else Modifier.height(stroke).fillMaxWidth()).background(line))
        }
    }
}
