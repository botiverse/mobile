package build.raft.designsystem

import build.raft.designsystem.components.RaftRowPlanColumn
import build.raft.designsystem.components.raftPaneGridFadeSpans
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
