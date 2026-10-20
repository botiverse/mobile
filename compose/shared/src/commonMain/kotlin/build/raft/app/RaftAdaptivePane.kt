package build.raft.app

import build.raft.app.compat.json.JSONObject

/**
 * Host-owned window context. It deliberately contains layout facts only: route and child-pager
 * navigation continue to be owned by the AppShell.
 */
internal data class RaftAdaptivePaneState(
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val density: Float = 1f,
    val posture: RaftAdaptivePanePosture = RaftAdaptivePanePosture.Unknown,
    val layout: RaftAdaptivePaneLayout = RaftAdaptivePaneLayout.Compact
)

internal enum class RaftAdaptivePanePosture {
    Unknown,
    Flat,
    Folded,
    HalfFolded
}

internal enum class RaftAdaptivePaneLayout {
    Compact,
    DualPane
}

internal data class RaftAdaptivePaneEvent(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val posture: RaftAdaptivePanePosture
)

internal const val RaftAdaptivePanePagerEvent = "slockAdaptivePaneChanged"

/**
 * Pure reducer so every host delivers the same layout contract.
 *
 * Task #180 (artin 2026-10-17): a HALF_OPENED / half-folded posture no longer suppresses the second
 * pane. It follows the same width rule as every other posture -- half-folded devices are a real
 * large-screen shape (the folded-out half), and treating them as compact left those users on a
 * phone layout beside an 840dp-capable window. The Unknown posture still means "no window facts
 * yet" and stays compact. The side reply-thread column keeps its own, higher gate (>=1200dp plus
 * detail-area headroom), so this widens DualPane only.
 */
internal fun reduceRaftAdaptivePane(
    previous: RaftAdaptivePaneState,
    event: RaftAdaptivePaneEvent
): RaftAdaptivePaneState {
    if (event.widthPx <= 0 || event.heightPx <= 0 || event.density <= 0f) return previous

    val widthDp = event.widthPx / event.density
    val layout =
        if (
            event.posture != RaftAdaptivePanePosture.Unknown &&
            widthDp >= DualPaneMinimumWidthDp
        ) {
            RaftAdaptivePaneLayout.DualPane
        } else {
            RaftAdaptivePaneLayout.Compact
        }
    return RaftAdaptivePaneState(
        widthPx = event.widthPx,
        heightPx = event.heightPx,
        density = event.density,
        posture = event.posture,
        layout = layout
    )
}

internal fun JSONObject.toRaftAdaptivePaneEventOrNull(): RaftAdaptivePaneEvent? {
    val widthPx = optDouble("widthPx", 0.0).toInt()
    val heightPx = optDouble("heightPx", 0.0).toInt()
    val density = optDouble("density", 0.0).toFloat()
    if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return null
    return RaftAdaptivePaneEvent(
        widthPx = widthPx,
        heightPx = heightPx,
        density = density,
        posture = optString("posture").toRaftAdaptivePanePosture()
    )
}

private fun String.toRaftAdaptivePanePosture(): RaftAdaptivePanePosture =
    when (trim().lowercase()) {
        "flat", "expanded" -> RaftAdaptivePanePosture.Flat
        "folded" -> RaftAdaptivePanePosture.Folded
        "half_folded" -> RaftAdaptivePanePosture.HalfFolded
        else -> RaftAdaptivePanePosture.Unknown
    }

private const val DualPaneMinimumWidthDp = 840f

internal fun RaftAdaptivePanePosture.toRaftAdaptivePanePostureWireName(): String =
    when (this) {
        RaftAdaptivePanePosture.Flat -> "flat"
        RaftAdaptivePanePosture.Folded -> "folded"
        RaftAdaptivePanePosture.HalfFolded -> "half_folded"
        RaftAdaptivePanePosture.Unknown -> "unknown"
    }

/**
 * Snapshot a delivered [RaftAdaptivePaneEvent] into the adoption-request carrier. The capture
 * stamp is taken by the requesting pager, which owns the freshness sequence for its hostId.
 */
internal fun RaftAdaptivePaneEvent.toRaftAdaptiveOpenWindowFact(
    capturedAtMillis: Long,
): RaftAdaptiveOpenWindowFact =
    RaftAdaptiveOpenWindowFact(
        widthPx = widthPx,
        heightPx = heightPx,
        density = density,
        posture = posture.toRaftAdaptivePanePostureWireName(),
        capturedAtMillis = capturedAtMillis,
    )
