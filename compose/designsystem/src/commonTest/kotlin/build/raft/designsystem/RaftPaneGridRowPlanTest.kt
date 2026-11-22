package build.raft.designsystem

import build.raft.designsystem.components.RaftRowPlanColumn
import build.raft.designsystem.components.raftPaneGridRowPlan
import build.raft.designsystem.components.raftPaneGridShownUnit
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The grid's row (`raftPaneGridRowPlan`) on the #189 default arrangement (web channelSideLayout.ts
 * `DEFAULT_CHANNEL_SIDE_LAYOUT`): [thread 400] [tasks over files 360] [artifact 480] [profile over
 * context over details 400], section minimums 360, 280, 480, 320. Widths in, plan out: give way,
 * clip to 160, then tabs.
 */
class RaftPaneGridRowPlanTest {
    private val columns =
        listOf(
            RaftRowPlanColumn("thread", listOf("thread"), listOf(360f), 400f),
            RaftRowPlanColumn("sideCards", listOf("tasks", "files"), listOf(280f, 280f), 360f),
            RaftRowPlanColumn("artifact", listOf("artifact"), listOf(480f), 480f),
            RaftRowPlanColumn("profile", listOf("profile", "context", "details"), listOf(320f, 320f, 320f), 400f),
        )

    private fun rounded(widths: List<Float>) = widths.map { it.roundToInt() }

    @Test
    fun withRoomEveryColumnHasItsSavedWidthAndTheMainPaneTheRest() {
        val plan = raftPaneGridRowPlan(2400f, columns)
        assertEquals(listOf("conversation", "thread", "sideCards", "artifact", "profile"), plan.units.map { it.key })
        assertEquals(listOf(760, 400, 360, 480, 400), rounded(plan.widths))
        // With no width kept the conversation is what the columns leave, at least 400 (web CONVERSATION_DEFAULT_WIDTH):
        // in 2000 that is 40 short, which every column gives in proportion above its minimum.
        assertEquals(listOf(393, 393, 347, 480, 387), rounded(raftPaneGridRowPlan(2000f, columns).widths))
        assertFalse(plan.fold, "the grid plans no thread fold: the page that declares the thread decides it")
    }

    @Test
    fun aGroupShowsTheTabPickedForItElseItsFirst() {
        val plan = raftPaneGridRowPlan(400f, columns)
        val group = plan.groups[1]
        assertEquals(1, raftPaneGridShownUnit(plan, group, emptyMap()))
        assertEquals(4, raftPaneGridShownUnit(plan, group, mapOf("thread" to "profile")))
        // A pick for a unit no longer in the group, or for another group, shows the first.
        assertEquals(1, raftPaneGridShownUnit(plan, group, mapOf("thread" to "gone")))
        assertEquals(1, raftPaneGridShownUnit(plan, group, mapOf("sideCards" to "profile")))
    }
}
