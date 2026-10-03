package build.raft.app.data

/**
 * task #37: the list column of the wide list + detail split is user-resizable (the web
 * useResizablePanel): dragged within [SLOCK_LIST_PANE_MIN_DP]..[SLOCK_LIST_PANE_MAX_DP], saved per
 * device (one width for every server, like the web localStorage value), and clamped to the
 * current window so the detail keeps at least [SLOCK_LIST_PANE_DETAIL_MIN_DP].
 */
internal const val SLOCK_LIST_PANE_DEFAULT_DP = 360f
internal const val SLOCK_LIST_PANE_MIN_DP = 280f
internal const val SLOCK_LIST_PANE_MAX_DP = 520f

/** Narrowest detail the list may leave; the 840dp dual-pane window with the default list. */
internal const val SLOCK_LIST_PANE_DETAIL_MIN_DP = 400f

/** Divider between the list and the detail. */
internal const val SLOCK_LIST_PANE_DIVIDER_DP = 1f

/** The saved width, or the default when nothing (or nothing valid) was saved. */
internal fun slockListPanePreferredWidthDp(stored: String?): Float =
    stored?.toFloatOrNull()
        ?.takeIf { it.isFinite() && it >= SLOCK_LIST_PANE_MIN_DP && it <= SLOCK_LIST_PANE_MAX_DP }
        ?: SLOCK_LIST_PANE_DEFAULT_DP

/**
 * The width the list renders at: [preferredDp] within the limits and within what the split row
 * ([rowWidthDp], null before it is measured) leaves after the detail minimum. The saved preference
 * is not changed by this clamp, so a window that grows back gets the user's width again.
 */
internal fun slockListPaneWidthDp(preferredDp: Float, rowWidthDp: Float?): Float {
    val windowMax =
        rowWidthDp?.let { it - SLOCK_LIST_PANE_DIVIDER_DP - SLOCK_LIST_PANE_DETAIL_MIN_DP }
            ?: SLOCK_LIST_PANE_MAX_DP
    val max = minOf(SLOCK_LIST_PANE_MAX_DP, windowMax).coerceAtLeast(SLOCK_LIST_PANE_MIN_DP)
    return preferredDp.coerceIn(SLOCK_LIST_PANE_MIN_DP, max)
}

/**
 * A drag that started at the rendered [startWidthDp] and moved [dragDeltaDp] (right = wider).
 * Starting from the rendered width (not the saved one) keeps the divider under the finger when
 * the window had clamped the list.
 */
internal fun slockListPaneDraggedWidthDp(startWidthDp: Float, dragDeltaDp: Float, rowWidthDp: Float?): Float =
    slockListPaneWidthDp(startWidthDp + dragDeltaDp, rowWidthDp)

/** What a finished drag saves: whole dp, so the stored value is stable across densities. */
internal fun slockListPaneStoredWidth(widthDp: Float): String =
    widthDp.coerceIn(SLOCK_LIST_PANE_MIN_DP, SLOCK_LIST_PANE_MAX_DP).toInt().toString()

/**
 * One list-divider drag. [end] reports the end exactly once per started drag however it is
 * reached (lift, gesture cancel, host inactive, handle disposed, density change); movement after
 * the end is ignored.
 */
internal class SlockListPaneDragSession(private val onEnd: (reason: String) -> Unit) {
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
