package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/**
 * task #37: the list column of the wide list + detail split is user-resizable (the web
 * useResizablePanel), saved per device (one width per column kind for every server, like the web
 * localStorage value), and clamped to the current window so the detail keeps at least
 * [RAFT_LIST_PANE_MAIN_MIN_DP].
 *
 * artin task #20 (large-screen gap 6): one width per column kind, with the web's numbers
 * (web `lib/panelBounds.ts` COLUMN_BOUNDS): [RaftListPaneKind.Sidebar] for the navigation lists
 * (Home channels, Members) and [RaftListPaneKind.List] for the Activity and Search lists.
 */
@RaftCoreInternalApi
enum class RaftListPaneKind(
    val defaultDp: Float,
    val minDp: Float,
    val maxDp: Float,
    /** Preference key of this kind's saved width. */
    val storageKey: String,
) {
    /** Web `sidebar`: 240 (200–400). */
    Sidebar(240f, 200f, 400f, "mobile:adaptive_list_pane_width_dp:sidebar"),

    /** Web `list` (Activity / Search list beside a picked item): 380 (240–640). */
    List(380f, 240f, 640f, "mobile:adaptive_list_pane_width_dp:list"),
}

/** The single width saved before artin task #20; read once into every kind (clamped), then removed. */
@RaftCoreInternalApi
const val RAFT_LIST_PANE_LEGACY_STORAGE_KEY = "mobile:adaptive_list_pane_width_dp"

/**
 * Narrowest the detail beside the list may get (web `MAIN_COLUMN_MIN_WIDTH`). It was 400 when the
 * list defaulted to 360 and the 840dp window had to fit both; the web's 360 fits the same window
 * with either kind's default (64 rail + 380 + 1 + 360 = 805).
 */
@RaftCoreInternalApi
const val RAFT_LIST_PANE_MAIN_MIN_DP = 360f

/** Divider between the list and the detail. */
@RaftCoreInternalApi
const val RAFT_LIST_PANE_DIVIDER_DP = 1f

private fun parseStoredWidth(stored: String?): Float? =
    stored?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }

/**
 * The width [kind] starts at: its own saved value, else the pre-#20 single value ([legacyStored]),
 * else the default. A saved value outside the kind's range is clamped into it, never reset (web
 * columnWidthStore), so a width someone dragged is kept as closely as the kind allows.
 */
@RaftCoreInternalApi
fun raftListPanePreferredWidthDp(kind: RaftListPaneKind, stored: String?, legacyStored: String? = null): Float {
    val value = parseStoredWidth(stored) ?: parseStoredWidth(legacyStored) ?: return kind.defaultDp
    return value.coerceIn(kind.minDp, kind.maxDp)
}

/**
 * What to write under [kind]'s own key when moving the pre-#20 value over, or null when there is
 * nothing to move (the kind already has its own value, or the legacy value is missing/invalid).
 */
@RaftCoreInternalApi
fun raftListPaneMigratedWidth(kind: RaftListPaneKind, stored: String?, legacyStored: String?): String? {
    if (parseStoredWidth(stored) != null) return null
    val legacy = parseStoredWidth(legacyStored) ?: return null
    return raftListPaneStoredWidth(kind, legacy)
}

/**
 * The width the list renders at: [preferredDp] within the kind's limits and within what the split
 * row ([rowWidthDp], null before it is measured) leaves after the main minimum, but never under
 * the kind's minimum. The saved preference is not changed by this clamp, so a window that grows
 * back gets the user's width again.
 */
@RaftCoreInternalApi
fun raftListPaneWidthDp(kind: RaftListPaneKind, preferredDp: Float, rowWidthDp: Float?): Float {
    val windowMax =
        rowWidthDp?.let { it - RAFT_LIST_PANE_DIVIDER_DP - RAFT_LIST_PANE_MAIN_MIN_DP } ?: kind.maxDp
    val max = minOf(kind.maxDp, windowMax).coerceAtLeast(kind.minDp)
    return preferredDp.coerceIn(kind.minDp, max)
}

/**
 * A drag that started at the rendered [startWidthDp] and moved [dragDeltaDp] (right = wider).
 * Starting from the rendered width (not the saved one) keeps the divider under the finger when
 * the window had clamped the list.
 */
@RaftCoreInternalApi
fun raftListPaneDraggedWidthDp(
    kind: RaftListPaneKind,
    startWidthDp: Float,
    dragDeltaDp: Float,
    rowWidthDp: Float?,
): Float = raftListPaneWidthDp(kind, startWidthDp + dragDeltaDp, rowWidthDp)

/** What a finished drag saves: whole dp, so the stored value is stable across densities. */
@RaftCoreInternalApi
fun raftListPaneStoredWidth(kind: RaftListPaneKind, widthDp: Float): String =
    widthDp.coerceIn(kind.minDp, kind.maxDp).toInt().toString()

/**
 * One list-divider drag. [end] reports the end exactly once per started drag however it is
 * reached (lift, gesture cancel, host inactive, handle disposed, density change); movement after
 * the end is ignored.
 */
@RaftCoreInternalApi
class RaftListPaneDragSession(private val onEnd: (reason: String) -> Unit) {
    var active: Boolean = false
        private set
    private var startWidthDp = 0f
    private var deltaDp = 0f

    fun start(renderedWidthDp: Float) {
        if (active) end("restart")
        active = true
        startWidthDp = renderedWidthDp
        deltaDp = 0f
    }

    /**
     * The drag's (start width, total delta) for a finger now [totalDeltaPx] from where the drag
     * started (window coordinates), or null when no drag is active. Using the total from a fixed
     * origin keeps the delta exact even though the handle itself moves and resizes mid-drag.
     */
    fun moveTo(totalDeltaPx: Float, density: Float): Pair<Float, Float>? {
        if (!active || density <= 0f) return null
        deltaDp = totalDeltaPx / density
        return startWidthDp to deltaDp
    }

    /**
     * Ends the active drag; true only for the call that actually ended it. [reason] (lift, cancel,
     * inactive, disposed, density, restart) is reported so device runs can tell the paths apart.
     */
    fun end(reason: String): Boolean {
        if (!active) return false
        active = false
        onEnd(reason)
        return true
    }
}
