package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import build.raft.designsystem.components.RaftSideActionColumnDefaults
import build.raft.designsystem.components.RaftSideActionColumnSide
import build.raft.designsystem.components.RaftSideColumnKeepOut
import kotlin.test.Test
import kotlin.test.assertEquals

/** iPhone Duo: the host's side-column facts in the adaptive-pane payload (raft-design #859). */
class RaftSideColumnPostureTest {
    private fun pane(): JSONObject =
        JSONObject().apply {
            put("widthPx", 1398)
            put("heightPx", 2034)
            put("density", 3.0)
            put("posture", "flat")
        }

    @Test
    fun aPayloadWithoutTheSideColumnIsNone() {
        assertEquals(RaftSideColumnPosture.None, pane().toRaftSideColumnPosture())
        assertEquals(RaftSideColumnPosture.None, pane().apply { put("sideColumn", false); put("stripWidth", 56.0) }.toRaftSideColumnPosture())
    }

    @Test
    fun theOuterScreenReadsInPoints() {
        val posture =
            pane().apply {
                put("sideColumn", true)
                put("stripEdge", "right")
                put("stripWidth", 56.0)
                put("nativeTabBarHeight", 260.0)
                put(
                    "reservedRegions",
                    JSONArray().apply {
                        put(JSONObject().apply { put("x", 410.0); put("y", 0.0); put("width", 56.0); put("height", 48.0); put("kind", "occlusion") })
                        put(JSONObject().apply { put("x", 0.0); put("y", 300.0); put("width", 466.0); put("height", 0.0) })
                        put(JSONObject().apply { put("x", 0.0); put("y", 330.0); put("width", 466.0); put("height", 8.0); put("kind", "division") })
                    },
                )
            }.toRaftSideColumnPosture()
        assertEquals(
            RaftSideColumnPosture(
                sideColumn = true,
                stripEdge = RaftSideActionColumnSide.Right,
                stripWidth = 56.dp,
                nativeTabBarHeight = 260.dp,
                reservedRegions =
                    listOf(
                        RaftReservedRegion(410.dp, 0.dp, 466.dp, 48.dp),
                        RaftReservedRegion(0.dp, 330.dp, 466.dp, 338.dp, RaftReservedRegion.Kind.Division),
                    ),
            ),
            posture,
        )
    }

    @Test
    fun missingOrBadNumbersFallBackToTheColumnDefaults() {
        val posture =
            pane().apply {
                put("sideColumn", true)
                put("stripEdge", "left")
                put("nativeTabBarHeight", -4.0)
            }.toRaftSideColumnPosture()
        assertEquals(RaftSideActionColumnSide.Left, posture.stripEdge)
        assertEquals(RaftSideActionColumnDefaults.Width, posture.stripWidth)
        assertEquals(0.dp, posture.nativeTabBarHeight)
    }

    @Test
    fun onlyRegionsCrossingTheColumnBecomeKeepOutBandsTopFirst() {
        val posture =
            RaftSideColumnPosture(
                sideColumn = true,
                stripWidth = 56.dp,
                reservedRegions =
                    listOf(
                        RaftReservedRegion(0.dp, 330.dp, 466.dp, 338.dp, RaftReservedRegion.Kind.Division),
                        RaftReservedRegion(420.dp, 0.dp, 460.dp, 48.dp),
                        // Left of the column: the page's problem, not the column's.
                        RaftReservedRegion(100.dp, 0.dp, 200.dp, 30.dp),
                    ),
            )
        assertEquals(
            listOf(RaftSideColumnKeepOut(0.dp, 48.dp), RaftSideColumnKeepOut(330.dp, 338.dp)),
            posture.keepOutBands(466.dp),
        )
        // A left column (physical) crosses only the fold.
        assertEquals(
            listOf(RaftSideColumnKeepOut(330.dp, 338.dp)),
            posture.copy(stripEdge = RaftSideActionColumnSide.Left).keepOutBands(466.dp),
        )
    }
}
