package build.raft.app.shared

/**
 * Platform-agnostic snapshot of an Android `Display.Mode`, so the display-mode
 * selection policy can be unit-tested on the JVM without the Android framework.
 */
internal data class DisplayModeSpec(
    val modeId: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val refreshRate: Float,
)

/**
 * Pick the highest-refresh mode that keeps the current resolution, or null when
 * the window should keep its current mode (already the fastest, or no eligible
 * alternative). Modes at other resolutions are never selected: requesting them
 * would also switch resolution, which is out of scope for high-refresh support.
 */
internal fun selectHighestRefreshSameResolutionMode(
    current: DisplayModeSpec,
    supported: List<DisplayModeSpec>,
): DisplayModeSpec? {
    val best =
        supported
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .maxByOrNull { it.refreshRate }
            ?: return null
    return if (best.modeId != current.modeId && best.refreshRate > current.refreshRate) {
        best
    } else {
        null
    }
}
