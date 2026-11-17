package build.raft.designsystem

import build.raft.designsystem.components.RaftRowPlanColumn
import build.raft.designsystem.components.raftPaneGridFadeSpans
import build.raft.designsystem.components.raftPaneGridMainShownDp
import build.raft.designsystem.components.raftPaneGridNativeFadePx
import build.raft.designsystem.components.raftPaneGridRowPlan
import build.raft.designsystem.components.raftRowUnitWidths
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The row right of the Activity list as the spec lays it out (raft-design responsive.md "The channel's side columns"):
 * the list is the sidebar, not one of the row's columns, so the row's room is the window less the rail and the list;
 * the open thread stands as the conversation, the Artifact as a column. Every width comes from that room, the total
 * never more; a column under its content's minimum clips (rule 2), and past 160 each the rightmost become tabs.
 */
class RaftPaneGridClipTest {
    private val artifact = RaftRowPlanColumn("artifact", listOf("artifact"), listOf(480f), 480f)
    private val sideThread = RaftRowPlanColumn("thread", listOf("thread"), listOf(360f), 400f)

    private fun shown(roomDp: Float, columns: List<RaftRowPlanColumn>): List<Float> {
        val plan = raftPaneGridRowPlan(roomDp, columns)
        return raftRowUnitWidths(plan) { plan.groups[it].first() }
    }

    private fun assertInRoom(roomDp: Float, widths: List<Float>) {
        assertTrue(abs(widths.sum() - roomDp) < 0.01f, "the row ${widths.sum()} is not its room $roomDp")
        widths.filter { it > 0f }.forEach { assertTrue(it >= 160f - 0.01f, "a column under 160: $widths") }
    }

    @Test
    fun aFoldableUnfoldedGivesTheThreadAndTheArtifactWhatTheListLeaves() {
        // Huawei foldable unfolded: 1016 wide, 954 right of the rail; the Activity list at half (477) and its seam:
        // the row has 476. The thread (360) and the Artifact (480) need 840: each gives way above its minimum (40 of
        // the thread's 400), then both shrink in proportion and clip (rule 4, step 4), never past the row's end.
        val room = 954f - 478f
        val widths = shown(room, listOf(artifact))
        assertEquals(listOf(204, 272), widths.map { it.roundToInt() })
        assertInRoom(room, widths)
        assertTrue(widths[0] < 360f && widths[1] < 480f, "both clipped (rule 2), their heads fitted to what shows")
        // The list narrowed by hand to 260: the row has 693, both still clipped, still inside it.
        val wider = shown(954f - 261f, listOf(artifact))
        assertInRoom(954f - 261f, wider)
        assertTrue(wider[1] < 480f)
        // With a side thread too, three units can't each have 160 in 476: the rightmost, the Artifact, becomes a tab in
        // the thread's head (rule 5), and nothing stands past the row's end.
        val plan = raftPaneGridRowPlan(room, listOf(sideThread, artifact))
        assertEquals(listOf(listOf(0), listOf(1, 2)), plan.groups)
        assertInRoom(room, shown(room, listOf(sideThread, artifact)))
    }

    @Test
    fun theNarrowestWideWindowKeepsEveryColumnAt160() {
        // 744 (RaftScale.WideMinWidth), 682 right of the rail, the list at 321 (the detail keeps 360 beside it): 360.
        val widths = shown(360f, listOf(artifact))
        assertEquals(listOf(160, 200), widths.map { it.roundToInt() })
        assertInRoom(360f, widths)
        // Three columns there: 480 is more than 360, so the Artifact becomes a tab.
        assertEquals(listOf(listOf(0), listOf(1, 2)), raftPaneGridRowPlan(360f, listOf(sideThread, artifact)).groups)
        assertInRoom(360f, shown(360f, listOf(sideThread, artifact)))
    }

    @Test
    fun aRowWithRoomIsUnchanged() {
        // 1600 wide (1538 right of the rail), the list at 380: the Artifact keeps its 480, the thread the rest.
        val widths = shown(1538f - 381f, listOf(artifact))
        assertEquals(listOf(677, 480), widths.map { it.roundToInt() })
        assertInRoom(1157f, widths)
    }

    @Test
    fun theMainPaneShowsWhatTheColumnsLeaveTheirSeamsIncluded() {
        // The foldable's row: 476 right of the list's seam. The Artifact's 272 holds its own 1dp left seam (drawn inside
        // its box), so the thread shows 204: the main pane has no seam of its own, the one right of it is the column's.
        val room = 954f - 478f
        val columns = shown(room, listOf(artifact)).drop(1)
        assertEquals(204, raftPaneGridMainShownDp(room, columns).roundToInt())
        // Main pane and columns, seams included, are the row exactly: nothing left over, nothing past its end.
        assertTrue(abs(raftPaneGridMainShownDp(room, columns) + columns.sum() - room) < 0.01f)
        // Two columns, two seams, each inside its column: still the row exactly.
        val two = shown(1200f, listOf(sideThread, artifact)).drop(1)
        assertTrue(abs(raftPaneGridMainShownDp(1200f, two) + two.sum() - 1200f) < 0.01f)
        // A row too narrow for its columns never shows the main pane under nothing.
        assertEquals(0f, raftPaneGridMainShownDp(300f, listOf(200f, 160f)))
    }

    @Test
    fun aNativeViewFadesOverItsVisibleEnd() {
        // The Artifact's WKWebView laid out at 480 from the column's start, 1dp in (the seam), in a 272 column: the
        // pane's end is 271 into the view, the fade the 24 before it.
        assertEquals(247f to 271f, raftPaneGridNativeFadePx(boxWidthPx = 272f, viewLeftPx = 1f, viewWidthPx = 480f, fadePx = 24f))
        // A view that ends inside the pane is not cut: no fade.
        assertEquals(null, raftPaneGridNativeFadePx(boxWidthPx = 500f, viewLeftPx = 1f, viewWidthPx = 480f, fadePx = 24f))
        // Less than the fade's length shows: it fades from its start.
        assertEquals(0f to 10f, raftPaneGridNativeFadePx(boxWidthPx = 11f, viewLeftPx = 1f, viewWidthPx = 480f, fadePx = 24f))
    }

    @Test
    fun theEndFadePassesTheHeadsBy() {
        // No head: the whole height fades.
        assertEquals(listOf(0f to 600f), raftPaneGridFadeSpans(600f, emptyList()))
        // A 56 head on top: under it only.
        assertEquals(listOf(56f to 600f), raftPaneGridFadeSpans(600f, listOf(0f to 56f)))
        // Two stacked cells' heads, given in any order: around both.
        assertEquals(
            listOf(56f to 300f, 356f to 600f),
            raftPaneGridFadeSpans(600f, listOf(300f to 356f, 0f to 56f)),
        )
        // A head past the bottom: nothing below it.
        assertEquals(listOf(56f to 580f), raftPaneGridFadeSpans(600f, listOf(0f to 56f, 580f to 640f)))
    }
}
