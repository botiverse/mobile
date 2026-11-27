package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import build.raft.app.shell.RaftSideColumnPosture
import build.raft.app.shell.toRaftAdaptivePaneEventOrNull
import build.raft.app.shell.toRaftSideColumnPosture
import build.raft.designsystem.components.RaftSideActionColumnSide
import build.raft.designsystem.components.RaftSideColumnKeepOut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * iPhone Duo (raft-design #859): the side column mapped from the host's [RaftAdaptivePaneEvent].
 * Values are what the iOS host (#3891) reports on the iPhone Duo simulator, iOS 27.2: folded and
 * unfolded-landscape `sideColumn=1, stripEdge=right, stripWidthPt=84` with an 84x124pt camera
 * region at the strip's top; an ordinary iPhone `sideColumn=0, stripEdge=none, stripWidthPt=0`.
 */
class RaftSideColumnPostureTest {
    private fun event(
        widthPt: Int,
        heightPt: Int,
        sideColumn: Boolean,
        stripEdge: RaftAdaptiveSideColumn,
        stripWidthPt: Float,
        nativeTabBarHeightPt: Float = 0f,
        reservedRegions: List<RaftAdaptiveReservedRegion> = emptyList(),
        display: RaftAdaptiveDisplay = RaftAdaptiveDisplay.Other,
    ) = RaftAdaptivePaneEvent(
        widthPx = widthPt * 3,
        heightPx = heightPt * 3,
        density = 3f,
        posture = RaftAdaptivePanePosture.Flat,
        sideColumn = sideColumn,
        stripEdge = stripEdge,
        stripWidthPt = stripWidthPt,
        nativeTabBarHeightPt = nativeTabBarHeightPt,
        reservedRegions = reservedRegions,
        display = display,
    )

    @Test
    fun `a regular iPhone has no side column`() {
        assertEquals(
            RaftSideColumnPosture.None,
            event(402, 874, sideColumn = false, stripEdge = RaftAdaptiveSideColumn.None, stripWidthPt = 0f).toRaftSideColumnPosture(),
        )
    }

    @Test
    fun `the folded Duo keeps an 84pt column on the right with the camera band at its top`() {
        val posture =
            event(
                466,
                678,
                sideColumn = true,
                stripEdge = RaftAdaptiveSideColumn.Right,
                stripWidthPt = 84f,
                nativeTabBarHeightPt = 260f,
                reservedRegions = listOf(RaftAdaptiveReservedRegion(leftPt = 382f, topPt = 0f, widthPt = 84f, heightPt = 124f)),
            ).toRaftSideColumnPosture()
        assertEquals(
            RaftSideColumnPosture(
                sideColumn = true,
                stripEdge = RaftSideActionColumnSide.Right,
                stripWidth = 84.dp,
                nativeTabBarHeight = 260.dp,
                keepOut = listOf(RaftSideColumnKeepOut(top = 0.dp, bottom = 124.dp)),
            ),
            posture,
        )
    }

    @Test
    fun `the posture follows the host physical edge whatever the reading direction`() {
        // The host already turned the system's logical edge into a physical one (in right-to-left
        // the trailing edge is "left"): the mapping takes it as is, and a region on the right of
        // the window does not cross a left column.
        val camera = RaftAdaptiveReservedRegion(leftPt = 382f, topPt = 0f, widthPt = 84f, heightPt = 124f)
        val left =
            event(466, 678, sideColumn = true, stripEdge = RaftAdaptiveSideColumn.Left, stripWidthPt = 84f, reservedRegions = listOf(camera))
                .toRaftSideColumnPosture()
        assertEquals(RaftSideActionColumnSide.Left, left.stripEdge)
        assertEquals(emptyList(), left.keepOut)
        val mirroredCamera = camera.copy(leftPt = 0f)
        assertEquals(
            listOf(RaftSideColumnKeepOut(top = 0.dp, bottom = 124.dp)),
            event(466, 678, sideColumn = true, stripEdge = RaftAdaptiveSideColumn.Left, stripWidthPt = 84f, reservedRegions = listOf(mirroredCamera))
                .toRaftSideColumnPosture()
                .keepOut,
        )
    }

    @Test
    fun `the host payload keys reach the posture through the pane event`() {
        val payload =
            JSONObject().apply {
                put("widthPx", 1398)
                put("heightPx", 2034)
                put("density", 3.0)
                put("posture", "flat")
                put("sideColumn", true)
                put("stripEdge", "right")
                put("stripWidthPt", 84.0)
                put("nativeTabBarHeightPt", 260.0)
                put(
                    "reservedRegions",
                    JSONArray().apply {
                        put(JSONObject().apply { put("leftPt", 382.0); put("topPt", 0.0); put("widthPt", 84.0); put("heightPt", 124.0) })
                    },
                )
            }
        val event = assertNotNull(payload.toRaftAdaptivePaneEventOrNull())
        assertEquals(
            RaftSideColumnPosture(
                sideColumn = true,
                stripEdge = RaftSideActionColumnSide.Right,
                stripWidth = 84.dp,
                nativeTabBarHeight = 260.dp,
                keepOut = listOf(RaftSideColumnKeepOut(top = 0.dp, bottom = 124.dp)),
            ),
            event.toRaftSideColumnPosture(),
        )
    }
}
