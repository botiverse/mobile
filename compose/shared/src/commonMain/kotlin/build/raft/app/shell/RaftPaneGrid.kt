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
import build.raft.designsystem.components.LocalRaftPaneGridArrangement
import build.raft.designsystem.components.LocalRaftPaneGridPlatform
import build.raft.designsystem.components.LocalRaftPaneGridSizes
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.LocalRaftPaneGridColumnControls
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftPaneGridColumnControls
import build.raft.designsystem.components.RaftPaneGridColumnHead
import build.raft.designsystem.components.RaftPaneGridExpand
import build.raft.designsystem.components.RaftPaneGridHeadAction
import build.raft.designsystem.components.RaftPaneGridHeadLabels
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
internal const val RAFT_PANE_GRID_KEPT_MIN_WIDTH_DP = build.raft.designsystem.components.RAFT_PANE_GRID_KEPT_MIN_WIDTH_DP
internal const val RAFT_PANE_GRID_KEYBOARD_STEP_DP = build.raft.designsystem.components.RAFT_PANE_GRID_KEYBOARD_STEP_DP
internal const val RAFT_PANE_GRID_KEYBOARD_STEP_LARGE_DP = build.raft.designsystem.components.RAFT_PANE_GRID_KEYBOARD_STEP_LARGE_DP

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

/** See the design system's `raftPaneGridInRow`: a grid declared here hands its columns to the outermost grid's planned row. */
@Composable
internal fun raftPaneGridInRow(): Boolean = build.raft.designsystem.components.raftPaneGridInRow()

/**
 * See the design system's `raftPaneGridFolds` (#189 rule 3): whether a column of [bounds] declared here (the reply
 * thread) folds into the main pane, by the app's kept widths. Null outside any grid's main pane.
 */
@Composable
internal fun raftPaneGridFolds(bounds: RaftPaneGridColumnBounds): Boolean? =
    build.raft.designsystem.components.raftPaneGridFolds(bounds, LocalRaftPaneGridStore.current)

/** See the design system's `raftPaneGridRoomDp`: the outermost grid's room for its main pane and the columns handed up. */
@Composable
internal fun raftPaneGridRoomDp(): Float? = build.raft.designsystem.components.raftPaneGridRoomDp()

internal object RaftPaneGridColumns {
    /** Web `COLUMN_BOUNDS.thread`: 400, at least 360, growing until the main pane is at 360. */
    val Thread = RaftPaneGridColumnBounds(id = "thread", defaultWidthDp = 400f, minWidthDp = 360f)

    /** Web `COLUMN_BOUNDS.sideCards`: the Tasks / Files column, 360, at least 280, its left seam included. */
    val SideCards = RaftPaneGridColumnBounds(id = "sideCards", defaultWidthDp = 360f, minWidthDp = 280f)

    /**
     * Web `COLUMN_BOUNDS.detail`: the right-hand detail column, here the Tasks page's task beside
     * its list (web TasksRoute); 400, at least 320 (web's content minimum).
     */
    val Detail = RaftPaneGridColumnBounds(id = "detail", defaultWidthDp = 400f, minWidthDp = 320f)

    /**
     * Web channelSideLayout's `[profile, context, details]` column (#189 step 3): a member's profile over the channel
     * details, 400 by default, at least 320 (`CHANNEL_SIDE_SECTION_MIN_WIDTH`); the row's last column.
     */
    val Profile = RaftPaneGridColumnBounds(id = "profile", defaultWidthDp = 400f, minWidthDp = 320f)

    /**
     * Web channelSideLayout's `[artifact]` column (#189 step 4; F41): the Artifact viewer, 480 by default and at least
     * 480 (`CHANNEL_SIDE_SECTION_MIN_WIDTH.artifact`); by default between Tasks / Files and the profile column.
     */
    val Artifact = RaftPaneGridColumnBounds(id = "artifact", defaultWidthDp = 480f, minWidthDp = 480f)

    /**
     * The main pane, the conversation (web rowLayout `slock:conversationWidth`, #189): none kept until a seam beside
     * it is let go (then the plan gives it what the columns leave, at least 400), kept at least its content's 360.
     */
    val Conversation: RaftPaneGridColumnBounds = build.raft.designsystem.components.RaftPaneGridConversationBounds

    /**
     * The Tasks page's list, the main pane beside the task's column: its own kept width, so letting the task
     * column's seam go never saves the list's width as the conversation's (PR #4661 review). Same rules as the
     * conversation's: none kept until then, at least 360.
     */
    val TasksList = RaftPaneGridColumnBounds(id = "tasksList", defaultWidthDp = 400f, minWidthDp = 360f)
}


// ---------------------------------------------------------------- what is kept

/**
 * The grid's kept sizes and folds, per device as web keeps them in localStorage:
 * - `raftPaneGrid:width:<column>`: a column's width in dp (web `slock:threadPanelWidth`; the
 *   Tasks / Files column's `width` in `slock:channelSideLayout`), and the main pane's under
 *   `raftPaneGrid:width:conversation` (web `slock:conversationWidth`), every shown one saved as it
 *   shows when a seam's drag or key ends (#189 rule 1);
 * - `raftPaneGrid:splits`: web's `slock:channelSideSplits` shape, `{"tasks+files":[0.6,0.4]}`;
 * - `raftPaneGrid:collapsed`: folded cells by id, web's `slock:channelSideCardsCollapsed` shape.
 */
@Stable
internal class RaftPaneGridStore(private val preferences: () -> SharedPreferenceStore? = { null }) : RaftPaneGridSizes {
    private val store: SharedPreferenceStore? by lazy { runCatching(preferences).getOrNull() }
    private var version by mutableIntStateOf(0)
    /** Each column's kept width as read or last saved; null where none is kept. */
    private val widths = mutableMapOf<String, Float?>()
    private var splits: MutableMap<String, List<Float>>? = null
    private var folded: MutableMap<String, Boolean>? = null

    private fun read(key: String): String? = runCatching { store?.getString(key) }.getOrNull()

    private fun write(key: String, value: String) {
        runCatching { store?.putString(key, value) }
        version++
    }

    /** The kept width, within the saved range (160 to 4000, web `parseChannelSideLayout`); else null. */
    override fun savedWidthDp(bounds: RaftPaneGridColumnBounds): Float? {
        version
        if (bounds.id in widths) return widths[bounds.id]
        return read("$WIDTH_KEY_PREFIX${bounds.id}")?.trim()?.toFloatOrNull()
            ?.takeIf { it in RAFT_PANE_GRID_KEPT_MIN_WIDTH_DP..RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP }
            .also { widths[bounds.id] = it }
    }

    /** The kept width; else the column's default. */
    override fun widthDp(bounds: RaftPaneGridColumnBounds): Float = savedWidthDp(bounds) ?: bounds.defaultWidthDp

    override fun setWidth(bounds: RaftPaneGridColumnBounds, widthDp: Float) {
        val width = widthDp.roundToInt().toFloat().coerceIn(RAFT_PANE_GRID_KEPT_MIN_WIDTH_DP, RAFT_PANE_GRID_MAX_SAVED_WIDTH_DP)
        if (savedWidthDp(bounds) == width) return
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

/** This device's side layout (#189 step 4, RaftChannelSideLayout.kt), by which the outermost grid stands and stacks the side columns. */
internal val LocalRaftChannelSideLayoutStore = staticCompositionLocalOf { RaftChannelSideLayoutStore.App }

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
    mainTabLabel: String? = null,
    /** Where the main pane's width is kept (the innermost grid naming it decides); null: the conversation's. */
    mainBounds: RaftPaneGridColumnBounds? = null,
    /** The row's Expand (#189 rule 5), handed up to the outermost grid: the shell's ([RaftWideSideColumns.expand]). */
    expand: RaftPaneGridExpand? = null,
    main: @Composable () -> Unit,
) {
    // The main pane's tab where the row makes columns tabs in its head (#189 rule 4): the conversation, web "Chat".
    val mainTab = mainTabLabel ?: stringResource(Res.string.thread_tab_chat)
    RaftPaneGridAppLocals {
        DsRaftPaneGrid(
            columns = columns,
            modifier = modifier,
            fillColumn = fillColumn,
            topInset = topInset,
            leading = leading,
            mainTabLabel = mainTab,
            mainBounds = mainBounds,
            expand = expand,
            main = main,
        )
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
    RaftPaneGridAppLocals {
        DsRaftPaneGridSideColumn(column = column, widthDp = widthDp, mainWidthDp = mainWidthDp, fill = fill, hidden = hidden, topInset = topInset)
    }
}

@Composable
internal fun RaftPaneGridAppLocals(content: @Composable () -> Unit) {
    val finePointer = raftPlatformHasFinePointer()
    val platform = remember(finePointer) { RaftPaneGridPlatform(finePointer = finePointer, splitterModifier = Modifier.raftNativeDispatchCapture()) }
    // The side sections stand and stack as this device's layout says (web channelSideLayout); equal values are the
    // same arrangement, so the grids below recompose only when the layout changes.
    val arrangement = LocalRaftChannelSideLayoutStore.current.layout.paneGridArrangement()
    CompositionLocalProvider(
        LocalRaftPaneGridSizes provides LocalRaftPaneGridStore.current,
        LocalRaftPaneGridPlatform provides platform,
        LocalRaftPaneGridArrangement provides arrangement,
        content = content,
    )
}

/** A column head's words ([RaftPaneGridColumnHead]), the web's (`layout.row.more`, `artifacts.viewer.expand` / `restore`, `layout.row.tabs`). */
@Composable
internal fun raftPaneGridHeadLabels(): RaftPaneGridHeadLabels =
    RaftPaneGridHeadLabels(
        more = stringResource(Res.string.common_more_actions),
        expand = stringResource(Res.string.pane_grid_column_expand),
        restore = stringResource(Res.string.pane_grid_column_restore),
        tabs = stringResource(Res.string.pane_grid_row_tabs),
    )

/**
 * #189 rule 7's keyboard path (web columnMoves `useColumnMoveItems`): [cell]'s Move Left / Move Right as ⋯ items, by
 * this device's side layout ([layoutStore]) among the sections open in the row ([RaftPaneGridColumnControls.rowCells]);
 * picked, the section moves there and the layout is kept, so the row stands anew. None outside a planned row, while a
 * region is expanded, in a head that shows the row tabs (they keep that room), for a cell that is no section, or where
 * it can go neither way; where it can go one way only, the other shows disabled (at the row's end).
 */
internal fun raftColumnMoveItems(
    cell: String,
    controls: RaftPaneGridColumnControls?,
    layoutStore: RaftChannelSideLayoutStore,
    moveLeft: String,
    moveRight: String,
    showsTabs: Boolean = false,
): List<RaftPaneGridHeadAction> {
    if (controls == null || controls.expanded != null || showsTabs) return emptyList()
    val section = RaftChannelSideSection.ofWire(cell) ?: return emptyList()
    val open = controls.rowCells.flatten().mapNotNull(RaftChannelSideSection::ofWire).toSet()
    if (section !in open) return emptyList()
    val layout = layoutStore.layout
    val moves = raftColumnMoves(layout, open, section)
    if (moves.left == null && moves.right == null) return emptyList()
    return listOf(
        RaftPaneGridHeadAction(
            id = "move-left",
            label = moveLeft,
            onSelect = { moves.left?.let { layoutStore.move(section, it) } },
            glyph = RaftGlyph.MoveLeft,
            enabled = moves.left != null,
            testTag = "$RAFT_COLUMN_MOVE_LEFT_TEST_TAG$cell",
        ),
        RaftPaneGridHeadAction(
            id = "move-right",
            label = moveRight,
            onSelect = { moves.right?.let { layoutStore.move(section, it) } },
            glyph = RaftGlyph.MoveRight,
            enabled = moves.right != null,
            testTag = "$RAFT_COLUMN_MOVE_RIGHT_TEST_TAG$cell",
        ),
    )
}

/** Test tags of a head's ⋯ > Move Left / Move Right (`+` the cell id; web `column-move-left-<section>`). */
internal const val RAFT_COLUMN_MOVE_LEFT_TEST_TAG = "columnMoveLeft:"
internal const val RAFT_COLUMN_MOVE_RIGHT_TEST_TAG = "columnMoveRight:"

/**
 * A card in a grid cell (web ChannelSideCard): the column head ([RaftPaneGridColumnHead], #189 rule 6: the collapse
 * chevron where it shares its column, the title or the row tabs, [ownActions], ⋯ with what didn't fit and Move Left /
 * Move Right, Expand, Close), then [body]. Folded, the head stays where it is unfolded, its own actions hidden, and the
 * body stays composed with no height (web keeps it mounted).
 */
@Composable
internal fun RaftPaneGridCellScope.RaftPaneGridCard(
    title: String,
    closeLabel: String,
    onClose: () -> Unit,
    ownActions: List<RaftPaneGridHeadAction> = emptyList(),
    body: @Composable () -> Unit,
) {
    val folded = collapsible && collapsed
    val controls = LocalRaftPaneGridColumnControls.current
    val layoutStore = LocalRaftChannelSideLayoutStore.current
    val moveLeft = stringResource(Res.string.pane_grid_column_move_left)
    val moveRight = stringResource(Res.string.pane_grid_column_move_right)
    // The head showing the row tabs offers no moves (web: its ⋯ isn't there for them alone).
    val showsTabs = controls?.tabs != null && controls.tabsCell == cellId
    val moves = raftColumnMoveItems(cellId, controls, layoutStore, moveLeft, moveRight, showsTabs)
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        RaftPaneGridColumnHead(
            title = title,
            labels = raftPaneGridHeadLabels(),
            cellId = cellId.ifEmpty { null },
            closeLabel = closeLabel,
            onClose = onClose,
            lead =
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
            ownActions = if (folded) emptyList() else ownActions,
            menuItems = moves,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { body() }
    }
}
