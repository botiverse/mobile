package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.resources.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftBarButtons
import build.raft.designsystem.components.LocalRaftPaneGridPlatform
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftPaneSplitter
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRegionHost
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftPointerInputState
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RAFT_LIST_PANE_COLLAPSE_RELEASE_SHARE
import build.raft.app.data.RAFT_LIST_PANE_DIVIDER_DP
import build.raft.app.data.RAFT_LIST_PANE_SNAP_PAST_END_DP
import build.raft.app.data.RaftListPaneDragSession
import build.raft.app.data.RaftListPaneKind
import build.raft.app.data.raftListPaneDraggedWidthDp
import build.raft.app.data.raftListPanePreferredWidthDp
import build.raft.app.data.raftListPaneStoredWidth
import build.raft.app.data.raftListPaneWidthDp

/**
 * artin task #20: the remembered list column width of each [RaftListPaneKind] (web
 * columnWidthStore): every host of a kind shows the same live width, a drag previews without
 * writing and its end saves.
 */
@Stable
internal class RaftListPaneWidths(private val store: KeyValueChatLayoutPreferenceStore?) {
    private val preferred =
        RaftListPaneKind.entries.associateWith { kind ->
            mutableStateOf(raftListPanePreferredWidthDp(kind, store?.readAdaptiveListPaneWidthSync(kind)))
        }

    fun preferredDp(kind: RaftListPaneKind): Float = preferred.getValue(kind).value

    /** The width [kind]'s list renders at in a split row of [rowWidthDp]. */
    fun renderedDp(kind: RaftListPaneKind, rowWidthDp: Float?): Float =
        raftListPaneWidthDp(kind, preferredDp(kind), rowWidthDp)

    /** A drag in flight: [deltaDp] from [startWidthDp], within [kind]'s limits, the row and [maxDp] (a share cap). */
    fun drag(kind: RaftListPaneKind, startWidthDp: Float, deltaDp: Float, rowWidthDp: Float?, maxDp: Float = Float.MAX_VALUE) {
        preferred.getValue(kind).value =
            minOf(raftListPaneDraggedWidthDp(kind, startWidthDp, deltaDp, rowWidthDp), maxOf(kind.minDp, maxDp))
    }

    /** Shows [widthDp] (within the kind's range) without saving it: a drag in flight. */
    fun preview(kind: RaftListPaneKind, widthDp: Float) {
        preferred.getValue(kind).value = widthDp.coerceIn(kind.minDp, kind.maxDp)
    }

    fun save(kind: RaftListPaneKind) {
        store?.saveAdaptiveListPaneWidth(kind, raftListPaneStoredWidth(kind, preferredDp(kind)))
    }

    /** Shows and saves [widthDp]: a key, a double click, a screen reader's set. */
    fun commit(kind: RaftListPaneKind, widthDp: Float) {
        preview(kind, widthDp)
        save(kind)
    }
}

@Composable
internal fun rememberRaftListPaneWidths(store: KeyValueChatLayoutPreferenceStore?): RaftListPaneWidths =
    remember(store) { RaftListPaneWidths(store) }

/**
 * Web `shareCapWidth`: the widest a list may render when it must not outgrow the pane beside it,
 * [share] of the [roomDp] right of the rail, never below the kind's minimum (web Activity with an
 * item open: half, "the channel narrower than the list is odd", stdrc).
 */
internal fun raftListPaneShareCapDp(roomDp: Float, share: Float, minDp: Float): Float = maxOf(minDp, kotlin.math.floor(roomDp * share))

/**
 * task #13 / #37 / artin task #20: the wide list + detail split the Home, Activity and Search hosts
 * share (lifted out of SettingsHomePage). The list stays in the first slot in every state, so
 * picking an item or narrowing never remounts it. The split is a [RaftPaneGrid]: the list is its
 * leading column, the detail its main pane, so the detail's own columns (the reply thread, a
 * conversation's Tasks / Files) stand in the same row, right of the detail.
 *
 * - [dualPane]: the [kind]'s remembered width, clamped to the row so the detail keeps its
 *   minimum (and to [maxShare] of the row where given, web `maxShare`), the grid's 1dp seam and
 *   the drag handle on top ([RaftListPaneSeam]).
 * - [fillListWhenNothingPicked] with no [hasSelection] (web Search before a result is picked):
 *   the list takes the whole row and there is no empty detail beside it.
 * - [collapsed] (the rail's Collapse Sidebar, [RaftSidebarCollapseState]): the detail takes the
 *   row at once, without the list's handle; the list leaves composition until it is expanded
 *   (web: the collapsed sidebar is inert). Only while [hasSelection]: with nothing open the list shows
 *   beside the empty detail (#4394), and hides again once something opens. With [onCollapsedChange] (the columns the rail's toggle
 *   collapses) the handle collapses the list too, by the input in use ([raftListPaneDragCollapses]:
 *   with a mouse or trackpad 40dp past its minimum while held, by touch only its edge let go of
 *   near the rail), or Enter; and where the list was, a strip keeps the rail toggle's Expand
 *   sidebar in sight ([RaftListPaneExpandStrip]), which brings it back. [detailHeadTopInset] is
 *   the top safe area the detail's head pads, the shell's to give (it has no page host to read it
 *   from here): 0 under the wide top bar, the window's on iPhone Duo's inner screen, which has
 *   none; the strip's button stays level with that head.
 * - Otherwise (compact): the detail when something is selected, else the list.
 */
@Composable
internal fun RaftAdaptiveListDetailSplit(
    kind: RaftListPaneKind,
    widths: RaftListPaneWidths,
    dualPane: Boolean,
    active: Boolean,
    hasSelection: Boolean,
    detailBottomInset: Dp,
    listPane: @Composable (Boolean) -> Unit,
    detailPane: @Composable (Modifier) -> Unit,
    fillListWhenNothingPicked: Boolean = false,
    collapsed: Boolean = false,
    onCollapsedChange: ((Boolean) -> Unit)? = null,
    maxShare: Float? = null,
    onResizingChange: (Boolean) -> Unit = {},
    log: (String) -> Unit = {},
    detailHeadTopInset: Dp = 0.dp,
) {
    val density = LocalDensity.current
    var rowWidthDp by remember { mutableStateOf<Float?>(null) }
    val shareCap = maxShare?.let { share -> rowWidthDp?.let { raftListPaneShareCapDp(it, share, kind.minDp) } }
    val listWidthDp = widths.renderedDp(kind, rowWidthDp).let { if (shareCap != null) minOf(it, shareCap) else it }
    val listMaxDp = raftListPaneWidthDp(kind, kind.maxDp, rowWidthDp).let { if (shareCap != null) minOf(it, shareCap) else it }
    val listFillsRow = dualPane && fillListWhenNothingPicked && !hasSelection
    // #4394: with nothing open the list shows even when collapsed (else the page is only "Select a
    // conversation"); [collapsed] is kept, so it hides again once something opens.
    val listCollapsed = dualPane && !listFillsRow && collapsed && hasSelection
    val split = dualPane && !listFillsRow && !listCollapsed
    // Collapsed where the rail's toggle brings it back: the strip with Expand sidebar stands where it was.
    val expandStrip = listCollapsed && onCollapsedChange != null
    // The split stands outside any page's theme: the strip is as wide as the bar button of the input in use, which
    // the outermost RaftTheme (the strip's own) reads from RaftPointerInputState too.
    val expandStripWidthDp = raftListPaneExpandStripWidthDp(RaftPointerInputState.current)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    val widthDp = with(density) { size.width.toDp().value }
                    if (rowWidthDp != widthDp) rowWidthDp = widthDp
                }
    ) {
        RaftPaneGrid(
            columns = emptyList(),
            modifier = Modifier.fillMaxSize(),
            leading =
                RaftPaneGridLeadingColumn(
                    widthDp = if (expandStrip) expandStripWidthDp else listWidthDp,
                    mode =
                        when {
                            listFillsRow -> RaftPaneGridLeadingMode.Fill
                            split || expandStrip -> RaftPaneGridLeadingMode.Beside
                            else -> RaftPaneGridLeadingMode.Hidden
                        },
                ) {
                    if (dualPane && !listCollapsed) {
                        RaftFocusRegionHost(region = RaftFocusRegion.ListPane, modifier = Modifier.fillMaxSize()) {
                            listPane(active)
                        }
                    } else if (expandStrip) {
                        RaftListPaneExpandStrip(topInset = detailHeadTopInset, onExpand = { onCollapsedChange?.invoke(false) })
                    }
                },
        ) {
            Box(Modifier.fillMaxSize()) {
                if (split || listCollapsed || hasSelection) {
                    detailPane(Modifier.fillMaxSize().padding(bottom = detailBottomInset))
                } else if (!dualPane) {
                    RaftFocusRegionHost(region = RaftFocusRegion.ListPane, modifier = Modifier.fillMaxSize()) {
                        listPane(active)
                    }
                }
            }
        }
        // On top of the grid, so all of its hit area takes touches over the detail too.
        RaftListPaneSeam(
            kind = kind,
            widths = widths,
            split = split,
            active = active,
            listWidthDp = listWidthDp,
            listMaxDp = listMaxDp,
            rowWidthDp = rowWidthDp,
            collapsedSeamDp = if (expandStrip) expandStripWidthDp else 0f,
            onCollapsedChange = onCollapsedChange,
            onResizingChange = onResizingChange,
            log = log,
        )
    }
}

/**
 * Whether a drag of the list's edge has the list collapsed, as the rail's Collapse Sidebar does: the drag started at
 * [startWidthDp] and is now [dragDeltaDp] from where it started (right = wider), taken before any clamp; [released]:
 * the pointer has let go. [pointerInput] (the input in use, [RaftTheme.pointerInput]) picks the rule:
 * - [RaftPointerInput.Fine], a mouse or trackpad: the web's snap (ColumnResizeHandle `SNAP_PAST_END`, DS Resizable
 *   "Dragging past the end"). [RAFT_LIST_PANE_SNAP_PAST_END_DP] under the minimum the list collapses at once, while
 *   the edge is held, and dragging back over the line brings it back; letting go keeps what shows.
 * - [RaftPointerInput.Touch], a finger: a deliberate gesture, as the edge's 24dp touch target sits beside the list's
 *   rows. Only letting go under [RAFT_LIST_PANE_COLLAPSE_RELEASE_SHARE] of the minimum from the list's start (100dp for
 *   the sidebar, 120dp for the Activity list) collapses it; while held it never does, the list stopping at its minimum.
 */
internal fun raftListPaneDragCollapses(
    pointerInput: RaftPointerInput,
    kind: RaftListPaneKind,
    startWidthDp: Float,
    dragDeltaDp: Float,
    released: Boolean,
): Boolean {
    val reach = startWidthDp + dragDeltaDp
    return when (pointerInput) {
        RaftPointerInput.Fine -> reach < kind.minDp - RAFT_LIST_PANE_SNAP_PAST_END_DP
        RaftPointerInput.Touch -> released && reach < kind.minDp * RAFT_LIST_PANE_COLLAPSE_RELEASE_SHARE
    }
}

/**
 * The handle on the list's trailing seam (web ColumnResizeHandle on the sidebar slot, DS
 * Resizable), the design system's [RaftPaneSplitter]: a drag resizes the list within its kind's
 * range and the row (saved when it ends), arrows step 16dp (Shift 64), Home / End go to the ends,
 * a double click or tap restores the default width, and a mouse shows the resize pointer.
 *
 * With [onCollapsedChange], a drag collapses the list as the rail's Collapse Sidebar does, by the
 * input in use ([raftListPaneDragCollapses]): with a mouse or trackpad the web's snap, 40dp past
 * the minimum while held (back over the line brings it back, the release keeps what shows); by
 * touch only letting go near the rail, the list stopping at its minimum while the finger goes on
 * (a drag cut short, the host going inactive or the handle leaving, never collapses it). The width
 * from before the drag stays remembered (the over-drag is never saved), so Expand sidebar
 * restores it. Enter collapses it too. While a drag has the list collapsed the handle stays on the
 * edge of what is left ([collapsedSeamDp]: the collapsed strip's, else the row's start), so the
 * drag goes on.
 *
 * The split stands outside any page's theme: the handle takes the design system's
 * ([RaftDesignSystemTheme]) for its colours and the input in use ([RaftTheme.pointerInput]).
 *
 * A drag ends exactly once ([RaftListPaneDragSession]): on release, on cancel, when the host goes
 * inactive and when the seam leaves composition, so the shell never keeps a "resizing" state.
 */
@Composable
private fun RaftListPaneSeam(
    kind: RaftListPaneKind,
    widths: RaftListPaneWidths,
    split: Boolean,
    active: Boolean,
    listWidthDp: Float,
    listMaxDp: Float,
    rowWidthDp: Float?,
    collapsedSeamDp: Float,
    onCollapsedChange: ((Boolean) -> Unit)?,
    onResizingChange: (Boolean) -> Unit,
    log: (String) -> Unit,
) {
    RaftDesignSystemTheme {
        val currentPointerInput by rememberUpdatedState(RaftTheme.pointerInput)
        val currentOnResizingChange by rememberUpdatedState(onResizingChange)
        val currentOnCollapsedChange by rememberUpdatedState(onCollapsedChange)
        val currentLog by rememberUpdatedState(log)
        val currentKind by rememberUpdatedState(kind)
        val currentRow by rememberUpdatedState(rowWidthDp)
        val currentMax by rememberUpdatedState(listMaxDp)
        var startWidthDp by remember { mutableStateOf(0f) }
        // The remembered width when the drag started: where a list this drag collapsed comes back to.
        var restoreWidthDp by remember { mutableStateOf(0f) }
        // How far the pointer is from where the drag started (right = wider), before any clamp.
        var dragTotalDp by remember { mutableStateOf(0f) }
        // The drag in flight has the list collapsed (with a mouse or trackpad: the web's snap, while held).
        var dragCollapsed by remember { mutableStateOf(false) }
        val session =
            remember {
                RaftListPaneDragSession(onEnd = { reason ->
                    val kindNow = currentKind
                    // By touch the release decides (raftListPaneDragCollapses); a drag cut short never collapses.
                    val collapseOnRelease =
                        !dragCollapsed && currentOnCollapsedChange != null && reason == RAFT_LIST_PANE_DRAG_RELEASE &&
                            raftListPaneDragCollapses(currentPointerInput, kindNow, startWidthDp, dragTotalDp, released = true)
                    val collapsedByDrag = dragCollapsed || collapseOnRelease
                    if (collapsedByDrag) {
                        // Collapsed by this drag: the width from before it stays, nothing new is saved.
                        widths.preview(kindNow, restoreWidthDp)
                        if (collapseOnRelease) currentOnCollapsedChange?.invoke(true)
                    } else {
                        widths.save(kindNow)
                    }
                    currentOnResizingChange(false)
                    currentLog("adaptive_list_pane_resize end=$reason kind=$kindNow width=${widths.preferredDp(kindNow)} collapsed=$collapsedByDrag row=$currentRow")
                    dragCollapsed = false
                })
            }
        LaunchedEffect(active) { if (!active) session.end("inactive") }
        DisposableEffect(Unit) { onDispose { session.end("disposed") } }
        // Collapsed by the rail, the shortcut or a release (not by this drag), the list is inert and has no handle.
        if (!split && !dragCollapsed) return@RaftDesignSystemTheme
        val min = kind.minDp
        val max = maxOf(min, listMaxDp)
        val seamDp = if (split) listWidthDp else collapsedSeamDp
        RaftPaneGridAppLocals {
            // The splitter's hit area: 12dp across the seam for a mouse, 24dp for a finger.
            val hit = if (LocalRaftPaneGridPlatform.current.finePointer) 12.dp else 24.dp
            RaftPaneSplitter(
                vertical = true,
                label = stringResource(if (kind == RaftListPaneKind.Sidebar) Res.string.column_resize_sidebar else Res.string.column_resize_list),
                value = listWidthDp,
                range = min..max,
                onSet = { widths.commit(kind, it.coerceIn(min, max)) },
                onDragStart = {
                    startWidthDp = listWidthDp
                    restoreWidthDp = widths.preferredDp(kind)
                    dragTotalDp = 0f
                    dragCollapsed = false
                    session.start(listWidthDp)
                    currentOnResizingChange(true)
                    log("adaptive_list_pane_resize start kind=$kind width=$listWidthDp row=$rowWidthDp")
                },
                onDrag = { total ->
                    // Ended already (the host went inactive) with the pointer still down.
                    if (!session.active) return@RaftPaneSplitter
                    dragTotalDp = total
                    // While held: with a mouse or trackpad past the snap line and back; by touch never.
                    val collapse = currentOnCollapsedChange
                    val past = collapse != null && raftListPaneDragCollapses(currentPointerInput, kind, startWidthDp, total, released = false)
                    if (past != dragCollapsed) {
                        dragCollapsed = past
                        collapse?.invoke(past)
                    }
                    if (dragCollapsed) widths.preview(kind, restoreWidthDp) else widths.drag(kind, startWidthDp, total, currentRow, currentMax)
                },
                onDragEnd = { session.end(RAFT_LIST_PANE_DRAG_RELEASE) },
                onStep = { step -> widths.commit(kind, (listWidthDp + step).coerceIn(min, max)) },
                onHome = { widths.commit(kind, min) },
                onEnd = { widths.commit(kind, max) },
                onReset = { widths.commit(kind, kind.defaultDp) },
                onEnter = onCollapsedChange?.let { change -> { change(true) } },
                // The seam's 1dp line is the list's right neighbour; the handle is centred on it.
                modifier = Modifier.offset(x = seamDp.dp + (RAFT_LIST_PANE_DIVIDER_DP / 2f).dp - hit / 2),
            )
        }
    }
}

/** [RaftListPaneDragSession]'s end when the pointer lets go of the edge (or the gesture is cancelled): by touch the only end that may collapse. */
private const val RAFT_LIST_PANE_DRAG_RELEASE = "lift"

/** Test tag of the collapsed list's Expand sidebar ([RaftListPaneExpandStrip]). */
internal const val RAFT_LIST_PANE_EXPAND_TEST_TAG = "raftListPaneExpand"

/**
 * The collapsed list's strip ([RaftListPaneExpandStrip]) for [pointerInput]: the head's bar button with the 16dp
 * content margin on either side of its glyph (54 with a finger, 48 under a mouse or trackpad).
 */
internal fun raftListPaneExpandStripWidthDp(pointerInput: RaftPointerInput): Float =
    pointerInput.controls.let { controls -> controls.barButtonSize.value + 2 * controls.barEdgeInset.value }

/**
 * Where the list was while it is collapsed: a strip of the list's surface at the row's start holding the rail toggle's
 * Expand sidebar (web LeftRail SidebarToggleRailItem: PanelLeftOpen, "Expand sidebar"; the web shows it only on the
 * rail, at the foot of it, far from where the list was), a head's bar button level with the head beside it. It brings
 * the list back at the width it had ([onExpand]); the rail's toggle and ⌘\ / Ctrl+\ still do too. The split stands
 * outside any page's theme: the strip takes the design system's, for its colours and the input in use. [topInset]: the
 * top safe area the head beside it pads ([RaftAdaptiveListDetailSplit]'s `detailHeadTopInset`). Nothing here reads the
 * page host (LocalActivity), so the split composes without one, as it always did.
 */
@Composable
private fun RaftListPaneExpandStrip(topInset: Dp, onExpand: () -> Unit) {
    RaftDesignSystemTheme {
        val controls = RaftTheme.controls
        Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas), contentAlignment = Alignment.TopCenter) {
            // The head's icon-lg buttons are bar buttons: 44 with a finger, 28 under a mouse or trackpad (sizing v12).
            CompositionLocalProvider(LocalRaftBarButtons provides true) {
                RaftIconButton(
                    onClick = onExpand,
                    tooltip = stringResource(Res.string.rail_expand_sidebar),
                    modifier =
                        Modifier
                            .padding(top = topInset + (controls.headHeight - controls.barButtonSize) / 2)
                            .testTag(RAFT_LIST_PANE_EXPAND_TEST_TAG),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Lg,
                ) {
                    RaftContentLucideIcon(RaftLucideIconName.PanelLeftOpen, size = controls.barIconSize)
                }
            }
        }
    }
}
