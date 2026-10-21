package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** A row of an inline wide list and the detail it put in the pane beside the list. */
internal data class RaftInlineOpenedRow(val rowKey: String, val detailKey: String)

/**
 * The row an inline wide list (the rail's Search and Saved, two-pane layout) shows as selected:
 * the row that opened the conversation now in the detail pane, for as long as that conversation
 * stays open there, like the Home and Activity lists. None once the detail closes, the server
 * changes, or another list opens something else.
 */
internal fun raftInlineListSelectedRowKey(opened: RaftInlineOpenedRow?, openDetailKey: String?): String? =
    opened?.takeIf { openDetailKey != null && it.detailKey == openDetailKey }?.rowKey

/**
 * Which row a tap binds to the detail, given the pane before and after the (synchronous) open.
 * Only a tap that changed the detail binds: an unchanged pane means the tap opened nothing beside
 * the list (a member or computer result pushes its own page, an unresolved row only toasts) or
 * re-opened what is already there, and either way the existing mark, if any, stays.
 */
internal fun raftInlineListBindOpenedRow(
    current: RaftInlineOpenedRow?,
    rowKey: String,
    detailKeyBefore: String?,
    detailKeyAfter: String?,
): RaftInlineOpenedRow? =
    if (detailKeyAfter != null && detailKeyAfter != detailKeyBefore) {
        RaftInlineOpenedRow(rowKey, detailKeyAfter)
    } else {
        current
    }

/**
 * Row selection for one inline wide list. The host owns it (it reads the adaptive workspace):
 * [liveDetailKey] is the detail's identity key from the store snapshot (the inline open lands
 * synchronously), and the host keeps [openDetailKey] in step with the composed workspace state.
 */
@Stable
internal class RaftInlineListSelection(private val liveDetailKey: () -> String?) {
    var openDetailKey: String? by mutableStateOf(null)
    private var opened: RaftInlineOpenedRow? by mutableStateOf(null)

    val selectedRowKey: String?
        get() = raftInlineListSelectedRowKey(opened, openDetailKey)

    fun open(rowKey: String, open: () -> Unit) {
        val before = liveDetailKey()
        open()
        opened = raftInlineListBindOpenedRow(opened, rowKey, before, liveDetailKey())
    }
}

/** Runs [open] for [rowKey], binding the row to the detail it opens when a host owns selection. */
internal fun RaftInlineListSelection?.opening(rowKey: String, open: () -> Unit) {
    if (this == null) open() else open(rowKey, open)
}

/** The selected-row wash, the same as the Activity list's selected row. */
internal fun Modifier.raftInlineListSelected(selected: Boolean): Modifier =
    if (selected) background(RaftColor.InfoSoft) else this
