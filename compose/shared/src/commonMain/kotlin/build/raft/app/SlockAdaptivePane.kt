package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * Host-owned window context. It deliberately contains layout facts only: route and child-pager
 * navigation continue to be owned by the AppShell.
 */
internal data class SlockAdaptivePaneState(
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val density: Float = 1f,
    val posture: SlockAdaptivePanePosture = SlockAdaptivePanePosture.Unknown,
    val layout: SlockAdaptivePaneLayout = SlockAdaptivePaneLayout.Compact
)

internal enum class SlockAdaptivePanePosture {
    Unknown,
    Flat,
    Folded,
    HalfFolded
}

internal enum class SlockAdaptivePaneLayout {
    Compact,
    DualPane
}

internal data class SlockAdaptivePaneEvent(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val posture: SlockAdaptivePanePosture
)

internal const val SlockAdaptivePanePagerEvent = "slockAdaptivePaneChanged"

/**
 * Pure reducer so every host delivers the same layout contract. A half-folded posture never
 * opens a second pane because the hinge is part of the interaction surface.
 */
internal fun reduceSlockAdaptivePane(
    previous: SlockAdaptivePaneState,
    event: SlockAdaptivePaneEvent
): SlockAdaptivePaneState {
    if (event.widthPx <= 0 || event.heightPx <= 0 || event.density <= 0f) return previous

    val widthDp = event.widthPx / event.density
    val layout =
        if (
            event.posture != SlockAdaptivePanePosture.Unknown &&
            event.posture != SlockAdaptivePanePosture.HalfFolded &&
            widthDp >= DualPaneMinimumWidthDp
        ) {
            SlockAdaptivePaneLayout.DualPane
        } else {
            SlockAdaptivePaneLayout.Compact
        }
    return SlockAdaptivePaneState(
        widthPx = event.widthPx,
        heightPx = event.heightPx,
        density = event.density,
        posture = event.posture,
        layout = layout
    )
}

internal fun JSONObject.toSlockAdaptivePaneEventOrNull(): SlockAdaptivePaneEvent? {
    val widthPx = optDouble("widthPx", 0.0).toInt()
    val heightPx = optDouble("heightPx", 0.0).toInt()
    val density = optDouble("density", 0.0).toFloat()
    if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return null
    return SlockAdaptivePaneEvent(
        widthPx = widthPx,
        heightPx = heightPx,
        density = density,
        posture = optString("posture").toSlockAdaptivePanePosture()
    )
}

private fun String.toSlockAdaptivePanePosture(): SlockAdaptivePanePosture =
    when (trim().lowercase()) {
        "flat", "expanded" -> SlockAdaptivePanePosture.Flat
        "folded" -> SlockAdaptivePanePosture.Folded
        "half_folded" -> SlockAdaptivePanePosture.HalfFolded
        else -> SlockAdaptivePanePosture.Unknown
    }

private const val DualPaneMinimumWidthDp = 840f
