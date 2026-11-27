package build.raft.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import build.raft.app.RaftAdaptiveDisplay
import build.raft.app.RaftAdaptivePaneEvent
import build.raft.app.RaftAdaptivePanePosture
import build.raft.app.RaftAdaptiveReservedRegion
import build.raft.app.RaftAdaptiveReservedRegionKind
import build.raft.app.RaftAdaptiveSideColumn
import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import build.raft.app.optBoolean
import build.raft.app.optDouble
import build.raft.app.optString
import build.raft.designsystem.theme.RaftScale

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
        reservedRegions = optJSONArray("reservedRegions").toRaftAdaptiveReservedRegions(),
        display = optString("display").toRaftAdaptiveDisplay()
    )
}

private fun String.toRaftAdaptiveDisplay(): RaftAdaptiveDisplay =
    when (trim().lowercase()) {
        "duoouter" -> RaftAdaptiveDisplay.DuoOuter
        "duoinner" -> RaftAdaptiveDisplay.DuoInner
        else -> RaftAdaptiveDisplay.Other
    }

private fun String.toRaftAdaptiveReservedRegionKind(): RaftAdaptiveReservedRegionKind =
    when (trim().lowercase()) {
        "occlusion" -> RaftAdaptiveReservedRegionKind.Occlusion
        "division" -> RaftAdaptiveReservedRegionKind.Division
        else -> RaftAdaptiveReservedRegionKind.Unknown
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
                heightPt = item.optDouble("heightPt", 0.0).toFloat(),
                kind = item.optString("kind").toRaftAdaptiveReservedRegionKind()
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

/**
 * Whether a window [windowWidth] wide is a wide window: from the wide breakpoint above (744),
 * where the rail, the two panes and the wide type start ([RaftScale.of]). The one rule for every
 * phone-or-wide choice made by the window's width, so they all switch at once: several were
 * copied from the web's `md` breakpoint and kept their phone variant on an iPad mini held upright,
 * which already has the rail (#307). A part sized by its own column asks its own width.
 */
internal fun raftWindowWide(windowWidth: Dp): Boolean = RaftScale.of(windowWidth) == RaftScale.Wide

/** The width of the window this is composed in; 0 before the window is measured. */
@Composable
@ReadOnlyComposable
internal fun raftWindowWidth(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

/** [raftWindowWide] for the window this is composed in ([raftWindowWidth]). */
@Composable
@ReadOnlyComposable
internal fun raftWindowWide(): Boolean = raftWindowWide(raftWindowWidth())

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
