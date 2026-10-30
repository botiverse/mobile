package build.raft.app.desktop

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Window facts for the shared adaptive layout (`slockAdaptivePaneChanged`, the contract the
 * Android `AndroidAdaptivePanePublisher` and iOS `RaftIosAdaptivePanePublisher` feed). A desktop
 * window is always `flat`; shared code alone decides compact vs dual-pane (>= 768dp). Without these
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
