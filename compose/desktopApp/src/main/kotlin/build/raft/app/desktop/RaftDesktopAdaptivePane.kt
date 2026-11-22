package build.raft.app.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Frame-1 size seed for a desktop page from `LocalWindowInfo.current.containerSize`, falling back
 * to the window's configured [fallbackWindowSize] (converted at [density]) when `containerSize`
 * is still [IntSize.Zero] on the first composition. Only the top page of the stack fills the window
 * (pages under it are laid out at 0x0 by RaftStackScene), and non-positive dimensions stay
 * [IntSize.Zero] until `onSizeChanged` reports a real size.
 */
internal fun raftDesktopInitialAdaptivePaneSize(
    isTopPage: Boolean,
    containerSize: IntSize,
    fallbackWindowSize: DpSize = DpSize.Unspecified,
    density: Float = 1f,
): IntSize {
    if (!isTopPage) return IntSize.Zero
    if (containerSize.width > 0 && containerSize.height > 0) return containerSize
    if (
        fallbackWindowSize != DpSize.Unspecified &&
        density > 0f &&
        density.isFinite() &&
        fallbackWindowSize.width.value.isFinite() &&
        fallbackWindowSize.height.value.isFinite() &&
        fallbackWindowSize.width.value > 0f &&
        fallbackWindowSize.height.value > 0f
    ) {
        val widthPx = (fallbackWindowSize.width.value * density).roundToInt()
        val heightPx = (fallbackWindowSize.height.value * density).roundToInt()
        if (widthPx > 0 && heightPx > 0) return IntSize(widthPx, heightPx)
    }
    return IntSize.Zero
}

/**
 * Window facts for the shared adaptive layout (`slockAdaptivePaneChanged`, the contract the
 * Android `AndroidAdaptivePanePublisher` and iOS `RaftIosAdaptivePanePublisher` feed). A desktop
 * window is always `flat`; shared code alone decides compact vs dual-pane (>= 744dp). Without these
 * facts the posture stays Unknown and every window, however wide, gets the phone layout.
 */
internal class RaftDesktopAdaptivePanePublisher(
    private val sendEvent: (event: String, data: JsonObject) -> Unit,
) {
    private var latest: JsonObject? = null

    /** Sends the facts for a [widthPx] x [heightPx] page at [density]; false if invalid or unchanged. */
    fun publish(widthPx: Int, heightPx: Int, density: Float): Boolean {
        if (widthPx <= 0 || heightPx <= 0 || !(density > 0f) || density.isInfinite()) return false
        val data =
            JsonObject(
                mapOf(
                    "widthPx" to JsonPrimitive(widthPx),
                    "heightPx" to JsonPrimitive(heightPx),
                    "density" to JsonPrimitive(density),
                    "posture" to JsonPrimitive(POSTURE_FLAT),
                )
            )
        if (data == latest) return false
        latest = data
        sendEvent(EVENT_NAME, data)
        return true
    }

    companion object {
        const val EVENT_NAME = "slockAdaptivePaneChanged"
        const val POSTURE_FLAT = "flat"
    }
}
