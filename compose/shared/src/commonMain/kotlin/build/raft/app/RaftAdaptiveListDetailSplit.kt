package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsetsSides
import build.raft.designsystem.components.raftConsumeSafeAreaHorizontal
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RAFT_LIST_PANE_DIVIDER_DP
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

    fun drag(kind: RaftListPaneKind, startWidthDp: Float, deltaDp: Float, rowWidthDp: Float?) {
        preferred.getValue(kind).value = raftListPaneDraggedWidthDp(kind, startWidthDp, deltaDp, rowWidthDp)
    }

    fun save(kind: RaftListPaneKind) {
        store?.saveAdaptiveListPaneWidth(kind, raftListPaneStoredWidth(kind, preferredDp(kind)))
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
 *   minimum, a 1dp seam and the drag handle on top.
 * - [fillListWhenNothingPicked] with no [hasSelection] (web Search before a result is picked):
 *   the list takes the whole row and there is no empty detail beside it.
 * - [collapsed] (the rail's Collapse Sidebar, [RaftSidebarCollapseState]): the detail takes the
 *   whole row at once, with no seam or handle; the list leaves composition until it is expanded
 *   (web: the collapsed sidebar is inert).
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
        if (split) {
            RaftListPaneResizeHandle(
                active = active,
                listWidthDp = listWidthDp,
                onDragStart = {
                    onResizingChange(true)
                    log("adaptive_list_pane_resize start kind=$kind width=$listWidthDp row=$rowWidthDp")
                },
                onDrag = { startWidthDp, deltaDp -> widths.drag(kind, startWidthDp, deltaDp, rowWidthDp) },
                onDragEnd = { reason ->
                    onResizingChange(false)
                    widths.save(kind)
                    log("adaptive_list_pane_resize end=$reason kind=$kind width=${widths.preferredDp(kind)} row=$rowWidthDp")
                },
            )
        }
    }
}
