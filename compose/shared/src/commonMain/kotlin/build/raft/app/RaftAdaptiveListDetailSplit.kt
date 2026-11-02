package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import build.raft.app.resources.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftListPaneKind
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

    /** A width the splitter moved the list to (a drag in progress, a key): kept within [kind]'s limits and the row. */
    fun resize(kind: RaftListPaneKind, widthDp: Float, rowWidthDp: Float?) {
        preferred.getValue(kind).value = raftListPaneWidthDp(kind, widthDp, rowWidthDp)
    }

    fun save(kind: RaftListPaneKind) {
        store?.saveAdaptiveListPaneWidth(kind, raftListPaneStoredWidth(kind, preferredDp(kind)))
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
 *   minimum (and to [maxShare] of the row where given, web `maxShare`), a 1dp seam and the grid's
 *   splitter on it.
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
    maxShare: Float? = null,
    onResizingChange: (Boolean) -> Unit = {},
    log: (String) -> Unit = {},
) {
    val density = LocalDensity.current
    var rowWidthDp by remember { mutableStateOf<Float?>(null) }
    val shareCap = maxShare?.let { share -> rowWidthDp?.let { raftListPaneShareCapDp(it, share, kind.minDp) } }
    val listWidthDp = widths.renderedDp(kind, rowWidthDp).let { if (shareCap != null) minOf(it, shareCap) else it }
    val listMaxDp = raftListPaneWidthDp(kind, kind.maxDp, rowWidthDp).let { if (shareCap != null) minOf(it, shareCap) else it }
    val listFillsRow = dualPane && fillListWhenNothingPicked && !hasSelection
    val listCollapsed = dualPane && !listFillsRow && collapsed
    val split = dualPane && !listFillsRow && !listCollapsed
    val currentLog by rememberUpdatedState(log)
    val currentOnResizingChange by rememberUpdatedState(onResizingChange)
    val resizeLabel =
        stringResource(if (kind == RaftListPaneKind.Sidebar) Res.string.home_list_pane_resize_sidebar else Res.string.home_list_pane_resize_list)
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
                    resizeLabel = resizeLabel,
                    widthDp = listWidthDp,
                    minWidthDp = kind.minDp,
                    maxWidthDp = listMaxDp,
                    defaultWidthDp = kind.defaultDp,
                    onResize = { widthDp -> widths.resize(kind, minOf(widthDp, listMaxDp), rowWidthDp) },
                    onResizeEnd = {
                        widths.save(kind)
                        currentLog("adaptive_list_pane_resize end kind=$kind width=${widths.preferredDp(kind)} row=$rowWidthDp")
                    },
                    onResizingChange = { resizing ->
                        currentOnResizingChange(resizing)
                        if (resizing) currentLog("adaptive_list_pane_resize start kind=$kind width=$listWidthDp row=$rowWidthDp")
                    },
                    mode =
                        when {
                            listFillsRow -> RaftPaneGridLeadingMode.Fill
                            split -> RaftPaneGridLeadingMode.Beside
                            else -> RaftPaneGridLeadingMode.Hidden
                        },
                    active = active,
                ) {
                    if (dualPane && !listCollapsed) listPane(active)
                },
        ) {
            Box(Modifier.fillMaxSize()) {
                if (split || listCollapsed || hasSelection) {
                    detailPane(Modifier.fillMaxSize().padding(bottom = detailBottomInset))
                } else if (!dualPane) {
                    listPane(active)
                }
            }
        }
    }
}
