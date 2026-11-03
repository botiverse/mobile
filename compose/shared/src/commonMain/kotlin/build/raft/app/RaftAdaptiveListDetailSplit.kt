package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsetsSides
import build.raft.designsystem.components.raftConsumeSafeAreaHorizontal
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.resources.*
import build.raft.designsystem.components.LocalRaftPaneGridPlatform
import build.raft.designsystem.components.RaftPaneSplitter
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RAFT_LIST_PANE_DIVIDER_DP
import build.raft.app.data.RaftListPaneDragSession
import build.raft.app.data.RaftListPaneKind
import build.raft.app.data.raftListPaneDragCollapses
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

    fun drag(kind: RaftListPaneKind, startWidthDp: Float, deltaDp: Float, rowWidthDp: Float?) {
        preferred.getValue(kind).value = raftListPaneDraggedWidthDp(kind, startWidthDp, deltaDp, rowWidthDp)
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
 * task #13 / #37 / artin task #20: the wide list + detail split the Home, Activity and Search hosts
 * share (lifted out of SettingsHomePage). The list stays in the first slot in every state, so
 * picking an item or narrowing never remounts it.
 *
 * - [dualPane]: the [kind]'s remembered width, clamped to the row so the detail keeps its
 *   minimum, a 1dp seam and the drag handle on top ([RaftListPaneSeam]).
 * - [fillListWhenNothingPicked] with no [hasSelection] (web Search before a result is picked):
 *   the list takes the whole row and there is no empty detail beside it.
 * - [collapsed] (the rail's Collapse Sidebar, [RaftSidebarCollapseState]): the detail takes the
 *   whole row at once, with no seam or handle; the list leaves composition until it is expanded
 *   (web: the collapsed sidebar is inert). With [onCollapsedChange] (the columns the rail's toggle
 *   collapses) the handle collapses the list too: a drag 40dp under its minimum, or Enter.
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
    onResizingChange: (Boolean) -> Unit = {},
    log: (String) -> Unit = {},
) {
    val density = LocalDensity.current
    var rowWidthDp by remember { mutableStateOf<Float?>(null) }
    val listWidthDp = widths.renderedDp(kind, rowWidthDp)
    val listFillsRow = dualPane && fillListWhenNothingPicked && !hasSelection
    val listCollapsed = dualPane && !listFillsRow && collapsed
    val split = dualPane && !listFillsRow && !listCollapsed
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    val widthDp = with(density) { size.width.toDp().value }
                    if (rowWidthDp != widthDp) rowWidthDp = widthDp
                }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    when {
                        listFillsRow -> Modifier.weight(1f)
                        split -> Modifier.width(listWidthDp.dp)
                        else -> Modifier.width(0.dp)
                    }.fillMaxHeight()
                        // Split: the list's inner side is away from the screen edge, so its rows
                        // pad no horizontal safe area there (the detail pane's start likewise).
                        .then(if (split) Modifier.raftConsumeSafeAreaHorizontal(WindowInsetsSides.End) else Modifier),
            ) {
                if (dualPane && !listCollapsed) listPane(active)
            }
            Spacer(
                modifier =
                    Modifier
                        .width(if (split) RAFT_LIST_PANE_DIVIDER_DP.dp else 0.dp)
                        .fillMaxHeight()
                        .background(RaftColor.BorderSeam), // raft-ui: one line-seam between regions, as the rail's
            )
            Box(
                modifier =
                    (if (listFillsRow) Modifier.width(0.dp) else Modifier.weight(1f))
                        .fillMaxHeight()
                        .then(if (split) Modifier.raftConsumeSafeAreaHorizontal(WindowInsetsSides.Start) else Modifier)
            ) {
                if (split || listCollapsed || hasSelection) {
                    detailPane(Modifier.fillMaxSize().padding(bottom = detailBottomInset))
                } else if (!dualPane) {
                    listPane(active)
                }
            }
        }
        RaftListPaneSeam(
            kind = kind,
            widths = widths,
            split = split,
            active = active,
            listWidthDp = listWidthDp,
            rowWidthDp = rowWidthDp,
            onCollapsedChange = onCollapsedChange,
            onResizingChange = onResizingChange,
            log = log,
        )
    }
}

/**
 * The handle on the list's trailing seam (web ColumnResizeHandle on the sidebar slot, DS
 * Resizable), the design system's [RaftPaneSplitter]: a drag resizes the list within its kind's
 * range and the row (saved when it ends), arrows step 16dp (Shift 64), Home / End go to the ends,
 * a double click or tap restores the default width, and a mouse shows the resize pointer.
 *
 * With [onCollapsedChange], dragging 40dp past the minimum collapses the list as the rail's
 * Collapse Sidebar does (web `snap` "collapse"), during the drag, and dragging back across the
 * line while still held brings it back; the release keeps what shows. The width from before the
 * drag stays remembered (the over-drag is never saved), so Expand Sidebar restores it. Enter
 * collapses it too. While a drag has the list collapsed the handle stays at the row's start, so
 * the drag goes on.
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
    rowWidthDp: Float?,
    onCollapsedChange: ((Boolean) -> Unit)?,
    onResizingChange: (Boolean) -> Unit,
    log: (String) -> Unit,
) {
    val currentOnResizingChange by rememberUpdatedState(onResizingChange)
    val currentOnCollapsedChange by rememberUpdatedState(onCollapsedChange)
    val currentLog by rememberUpdatedState(log)
    val currentKind by rememberUpdatedState(kind)
    val currentRow by rememberUpdatedState(rowWidthDp)
    var startWidthDp by remember { mutableStateOf(0f) }
    // The remembered width when the drag started: where a collapsed list comes back to.
    var restoreWidthDp by remember { mutableStateOf(0f) }
    // The drag in flight has the list collapsed.
    var dragCollapsed by remember { mutableStateOf(false) }
    val session =
        remember {
            RaftListPaneDragSession(onEnd = { reason ->
                val kindNow = currentKind
                if (dragCollapsed) {
                    // Collapsed by the drag: the width from before it stays, nothing new is saved.
                    widths.preview(kindNow, restoreWidthDp)
                } else {
                    widths.save(kindNow)
                }
                currentOnResizingChange(false)
                currentLog("adaptive_list_pane_resize end=$reason kind=$kindNow width=${widths.preferredDp(kindNow)} collapsed=$dragCollapsed row=$currentRow")
                dragCollapsed = false
            })
        }
    LaunchedEffect(active) { if (!active) session.end("inactive") }
    DisposableEffect(Unit) { onDispose { session.end("disposed") } }
    // Collapsed by the rail (not by this drag), the list is inert and has no handle.
    if (!split && !dragCollapsed) return
    val min = kind.minDp
    val max = raftListPaneWidthDp(kind, kind.maxDp, rowWidthDp)
    val seamDp = if (split) listWidthDp else 0f
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
                dragCollapsed = false
                session.start(listWidthDp)
                currentOnResizingChange(true)
                log("adaptive_list_pane_resize start kind=$kind width=$listWidthDp row=$rowWidthDp")
            },
            onDrag = { total ->
                // Ended already (the host went inactive) with the pointer still down.
                if (!session.active) return@RaftPaneSplitter
                val collapse = currentOnCollapsedChange
                val past = collapse != null && raftListPaneDragCollapses(kind, startWidthDp, total)
                if (past != dragCollapsed) {
                    dragCollapsed = past
                    collapse?.invoke(past)
                }
                if (dragCollapsed) widths.preview(kind, restoreWidthDp) else widths.drag(kind, startWidthDp, total, currentRow)
            },
            onDragEnd = { session.end("lift") },
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
