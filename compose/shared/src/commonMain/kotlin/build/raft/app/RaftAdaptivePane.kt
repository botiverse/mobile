package build.raft.app

import build.raft.app.compat.json.JSONArray
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
    val layout: RaftAdaptivePaneLayout = RaftAdaptivePaneLayout.Compact,
    val sideColumn: RaftAdaptiveSideColumn = RaftAdaptiveSideColumn.None
)

/**
 * The side column the host lays out for the window: a vertical strip on one edge carrying
 * navigation, page actions and the tab bar.
 *
 * iPhone Duo (iOS 27, HIG "designing for iPhone Duo"): with the device folded (outer display) or
 * unfolded and held landscape, the system moves the tool bar, the tab bar and the navigation
 * buttons into one vertical strip on the right edge; only the inner display held portrait goes
 * back to a horizontal top bar. The host reports which shape the window is in, and the design
 * system's side column component places the controls accordingly.
 */
internal enum class RaftAdaptiveSideColumn {
    /** No side column: a horizontal top bar is used. */
    None,

    /** A vertical strip on the left of the window, whatever the language's reading direction. */
    Left,

    /** A vertical strip on the right of the window, whatever the language's reading direction. */
    Right
}

/**
 * A rectangle the host must keep interactive content out of, in window points: the front-facing
 * camera's occlusion region and the fold's division region, as reported by UIKit
 * (`UIView.reservedRegions(ofKind:)`). Empty on systems without those regions.
 */
internal data class RaftAdaptiveReservedRegion(
    val leftPt: Float,
    val topPt: Float,
    val widthPt: Float,
    val heightPt: Float
) {
    fun isValid(): Boolean =
        widthPt > 0f && heightPt > 0f && leftPt.isFinite() && topPt.isFinite() &&
            widthPt.isFinite() && heightPt.isFinite()
}

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
    val posture: RaftAdaptivePanePosture,
    /**
     * Whether the window is in the side-column shape (iPhone Duo: outer display, or the inner
     * display held landscape). False for every other device and window size.
     */
    val sideColumn: Boolean = false,
    /**
     * Which side of the window the vertical strip sits on, as a physical side. The host reports
     * the physical side, not the logical one: in a right-to-left language the system's trailing
     * edge is the left of the screen, and the strip is laid out by physical side.
     */
    val stripEdge: RaftAdaptiveSideColumn = RaftAdaptiveSideColumn.None,
    /** Width of the vertical strip, in points. */
    val stripWidthPt: Float = 0f,
    /** Height the native tab bar takes at the bottom of the strip, in points (0 when there is none). */
    val nativeTabBarHeightPt: Float = 0f,
    /** Camera and fold regions to keep content out of, in points. */
    val reservedRegions: List<RaftAdaptiveReservedRegion> = emptyList()
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
        layout = layout,
        sideColumn =
            if (event.sideColumn) {
                event.stripEdge
            } else {
                RaftAdaptiveSideColumn.None
            }
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
        posture = optString("posture").toRaftAdaptivePanePosture(),
        sideColumn = optBoolean("sideColumn", false),
        stripEdge = optString("stripEdge").toRaftAdaptiveSideColumn(),
        stripWidthPt = optDouble("stripWidthPt", 0.0).toFloat(),
        nativeTabBarHeightPt = optDouble("nativeTabBarHeightPt", 0.0).toFloat(),
        reservedRegions = optJSONArray("reservedRegions").toRaftAdaptiveReservedRegions()
    )
}

private fun String.toRaftAdaptiveSideColumn(): RaftAdaptiveSideColumn =
    when (trim().lowercase()) {
        "left" -> RaftAdaptiveSideColumn.Left
        "right" -> RaftAdaptiveSideColumn.Right
        else -> RaftAdaptiveSideColumn.None
    }

private fun JSONArray?.toRaftAdaptiveReservedRegions(): List<RaftAdaptiveReservedRegion> {
    if (this == null) return emptyList()
    val regions = mutableListOf<RaftAdaptiveReservedRegion>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        val region =
            RaftAdaptiveReservedRegion(
                leftPt = item.optDouble("leftPt", 0.0).toFloat(),
                topPt = item.optDouble("topPt", 0.0).toFloat(),
                widthPt = item.optDouble("widthPt", 0.0).toFloat(),
                heightPt = item.optDouble("heightPt", 0.0).toFloat()
            )
        if (region.isValid()) {
            regions.add(region)
        }
    }
    return regions
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
