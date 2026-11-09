package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.resources.*
import build.raft.designsystem.components.LocalRaftPaneGridPlatform
import build.raft.designsystem.components.LocalRaftPaneGridSizes
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftPaneGridPlatform
import build.raft.designsystem.components.RaftPaneGrid as DsRaftPaneGrid
import build.raft.designsystem.components.RaftPaneGridSideColumn as DsRaftPaneGridSideColumn
import build.raft.designsystem.components.RaftPaneGridSizes
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject

/*
 * The app's side of the wide layout's pane grid. The grid itself (columns, stacks, splitters, the
 * layout rules) is the design system's `RaftPaneGrid` (build.raft.designsystem.components); this
 * file keeps what is the app's: the kinds of column ([RaftPaneGridColumns]), the per-device store
 * of sizes and folds ([RaftPaneGridStore], web's localStorage shapes), the card head
 * ([RaftPaneGridCard]), and the platform facts the splitters need. The names below forward to the
 * design system so pages keep calling them as before.
 */

internal typealias RaftPaneGridColumnBounds = build.raft.designsystem.components.RaftPaneGridColumnBounds
internal typealias RaftPaneGridCellScope = build.raft.designsystem.components.RaftPaneGridCellScope
internal typealias RaftPaneGridCell = build.raft.designsystem.components.RaftPaneGridCell
internal typealias RaftPaneGridColumn = build.raft.designsystem.components.RaftPaneGridColumn
internal typealias RaftPaneGridStackLayout = build.raft.designsystem.components.RaftPaneGridStackLayout
internal typealias RaftPaneGridLeadingColumn = build.raft.designsystem.components.RaftPaneGridLeadingColumn
internal typealias RaftPaneGridLeadingMode = build.raft.designsystem.components.RaftPaneGridLeadingMode

internal const val RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP = build.raft.designsystem.components.RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP
internal const val RAFT_PANE_GRID_CELL_MIN_HEIGHT_DP = build.raft.designsystem.components.RAFT_PANE_GRID_CELL_MIN_HEIGHT_DP
internal const val RAFT_PANE_GRID_HEAD_HEIGHT_DP = build.raft.designsystem.components.RAFT_PANE_GRID_HEAD_HEIGHT_DP
internal const val RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP = build.raft.designsystem.components.RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP
internal const val RAFT_PANE_GRID_KEYBOARD_STEP_DP = build.raft.designsystem.components.RAFT_PANE_GRID_KEYBOARD_STEP_DP
internal const val RAFT_PANE_GRID_KEYBOARD_STEP_LARGE_DP = build.raft.designsystem.components.RAFT_PANE_GRID_KEYBOARD_STEP_LARGE_DP

internal fun raftPaneGridFitWidths(
    availableDp: Float,
    columns: List<Pair<Float, Float>>,
    mainMinDp: Float = RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP,
): List<Float> = build.raft.designsystem.components.raftPaneGridFitWidths(availableDp, columns, mainMinDp)

internal fun raftPaneGridDragMaxDp(
    minWidthDp: Float,
    renderedWidthDp: Float,
    mainWidthDp: Float,
    mainMinDp: Float = RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP,
): Float = build.raft.designsystem.components.raftPaneGridDragMaxDp(minWidthDp, renderedWidthDp, mainWidthDp, mainMinDp)

internal fun raftPaneGridStackMinHeightDp(cells: Int): Float = build.raft.designsystem.components.raftPaneGridStackMinHeightDp(cells)

internal fun raftPaneGridStack(heightDp: Float, shares: List<Float>, collapsed: List<Boolean>): RaftPaneGridStackLayout =
    build.raft.designsystem.components.raftPaneGridStack(heightDp, shares, collapsed)

internal fun raftPaneGridSharePerDp(heightDp: Float, shares: List<Float>, collapsed: List<Boolean>): Float =
    build.raft.designsystem.components.raftPaneGridSharePerDp(heightDp, shares, collapsed)

internal fun raftPaneGridMoveSeam(
    shares: List<Float>,
    seam: Int,
    top: Float,
    heightDp: Float,
    collapsed: List<Boolean>,
): List<Float> = build.raft.designsystem.components.raftPaneGridMoveSeam(shares, seam, top, heightDp, collapsed)

/** See the design system's `raftPaneGridSpareDp`: the room a grid declared here could give its columns. */
@Composable
internal fun raftPaneGridSpareDp(): Float? = build.raft.designsystem.components.raftPaneGridSpareDp()

/** See the design system's `raftPaneGridRoomDp`: the outermost grid's room for its main pane and the columns handed up. */
@Composable
internal fun raftPaneGridRoomDp(): Float? = build.raft.designsystem.components.raftPaneGridRoomDp()

internal object RaftPaneGridColumns {
    /** Web `COLUMN_BOUNDS.thread`: 400, at least 360, growing until the main pane is at 360. */
    val Thread = RaftPaneGridColumnBounds(id = "thread", defaultWidthDp = 400f, minWidthDp = 360f)

    /** Web `COLUMN_BOUNDS.sideCards`: the Tasks / Files column, 360, at least 280, its left seam included. */
    val SideCards = RaftPaneGridColumnBounds(id = "sideCards", defaultWidthDp = 360f, minWidthDp = 280f)
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
internal class RaftPaneGridStore(private val preferences: () -> SharedPreferenceStore? = { null }) : RaftPaneGridSizes {
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
    override fun widthDp(bounds: RaftPaneGridColumnBounds): Float {
        version
        return widths.getOrPut(bounds.id) {
            read("$WIDTH_KEY_PREFIX${bounds.id}")?.trim()?.toFloatOrNull()
                ?.takeIf { it in bounds.minWidthDp..RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP }
                ?: bounds.defaultWidthDp
        }
    }

    override fun setWidth(bounds: RaftPaneGridColumnBounds, widthDp: Float) {
        val width = widthDp.roundToInt().toFloat().coerceIn(bounds.minWidthDp, RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP)
        if (widthDp(bounds) == width) return
        widths[bounds.id] = width
        write("$WIDTH_KEY_PREFIX${bounds.id}", width.roundToInt().toString())
    }

    private fun splitsMap(): MutableMap<String, List<Float>> =
        splits ?: decodeRaftPaneGridSplits(read(SPLITS_KEY)).toMutableMap().also { splits = it }

    /** Web `savedOr`: the kept shares of the stack [key] (its cell ids joined by `+`), else [defaults]. */
    override fun shares(key: String, defaults: List<Float>): List<Float> {
        version
        val saved = splitsMap()[key]
        return if (saved != null && saved.size == defaults.size && saved.all { it > 0f && it.isFinite() }) saved else defaults
    }

    override fun setShares(key: String, shares: List<Float>) {
        splitsMap()[key] = shares
        write(SPLITS_KEY, encodeRaftPaneGridSplits(splitsMap()))
    }

    private fun foldedMap(): MutableMap<String, Boolean> =
        folded ?: decodeRaftPaneGridFlags(read(COLLAPSED_KEY)).toMutableMap().also { folded = it }

    override fun isCollapsed(cell: String): Boolean {
        version
        return foldedMap()[cell] == true
    }

    override fun setCollapsed(cell: String, collapsed: Boolean) {
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

// ---------------------------------------------------------------- the grid

/**
 * The design system's grid with the app's sizes ([LocalRaftPaneGridStore]) and platform facts
 * (a fine pointer, iOS native dispatch capture): see `build.raft.designsystem.components.RaftPaneGrid`.
 */
@Composable
internal fun RaftPaneGrid(
    columns: List<RaftPaneGridColumn>,
    modifier: Modifier = Modifier,
    fillColumn: String? = null,
    topInset: Dp = 0.dp,
    leading: RaftPaneGridLeadingColumn? = null,
    main: @Composable () -> Unit,
) {
    RaftPaneGridAppLocals {
        DsRaftPaneGrid(columns, modifier, fillColumn, topInset, leading, main)
    }
}

/** One column for a host that lays out its own main pane: see the design system's `RaftPaneGridSideColumn`. */
@Composable
internal fun RowScope.RaftPaneGridSideColumn(
    column: RaftPaneGridColumn,
    widthDp: Float,
    mainWidthDp: Float,
    fill: Boolean = false,
    hidden: Boolean = false,
    topInset: Dp = 0.dp,
) {
    RaftPaneGridAppLocals { DsRaftPaneGridSideColumn(column, widthDp, mainWidthDp, fill, hidden, topInset) }
}

@Composable
internal fun RaftPaneGridAppLocals(content: @Composable () -> Unit) {
    val finePointer = raftPlatformHasFinePointer()
    val platform = remember(finePointer) { RaftPaneGridPlatform(finePointer = finePointer, splitterModifier = Modifier.raftNativeDispatchCapture()) }
    CompositionLocalProvider(
        LocalRaftPaneGridSizes provides LocalRaftPaneGridStore.current,
        LocalRaftPaneGridPlatform provides platform,
        content = content,
    )
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

