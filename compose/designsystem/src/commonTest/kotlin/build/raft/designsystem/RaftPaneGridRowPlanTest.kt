package build.raft.designsystem

import build.raft.designsystem.components.RaftRowPlanColumn
import build.raft.designsystem.components.raftPaneGridRowPlan
import build.raft.designsystem.components.raftPaneGridShownUnit
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    private val minimums = listOf(360f, 360f, 280f, 480f, 320f)

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
    fun aKeptConversationWidthIsPlannedLikeAColumns() {
        // The row as a seam was let go (every shown width saved, the conversation's too): planned back exactly.
        val saved = listOf(
            RaftRowPlanColumn("thread", listOf("thread"), listOf(360f), 385f),
            RaftRowPlanColumn("sideCards", listOf("tasks", "files"), listOf(280f, 280f), 317f),
        )
        assertEquals(listOf(398, 385, 317), rounded(raftPaneGridRowPlan(1100f, saved, mainSavedDp = 398f).widths))
        // Without it the conversation's 400 default takes its share: the row would not be the one let go.
        assertEquals(listOf(399, 385, 316), rounded(raftPaneGridRowPlan(1100f, saved).widths))
    }

    @Test
    fun shortOfRoomTheColumnsGiveWayInProportionAboveTheirMinimumsBeforeAnyClips() {
        // 200 short: thread 40, Tasks / Files 80 and the profile 80 above their minimums; the Artifact has none.
        assertEquals(listOf(360, 360, 280, 480, 320), rounded(raftPaneGridRowPlan(1800f, columns).widths))
        val between = raftPaneGridRowPlan(1900f, columns)
        assertEquals(1900, between.widths.sum().roundToInt())
        between.widths.forEachIndexed { i, width -> assertTrue(width >= minimums[i] - 0.01f, "unit $i keeps its minimum") }
    }

    @Test
    fun pastTheMinimumsEveryColumnClipsInProportionDownTo160() {
        val plan = raftPaneGridRowPlan(1600f, columns)
        assertEquals(5, plan.groups.size, "five columns side by side, none a tab")
        assertEquals(1600, plan.widths.sum().roundToInt())
        assertTrue(plan.widths[3] < 480f, "the Artifact clips under its 480")
        assertTrue(plan.widths.all { it >= 160f })
        assertEquals(listOf(320, 320, 249, 427, 284), rounded(plan.widths))
        assertEquals(listOf(160, 160, 160, 160, 160), rounded(raftPaneGridRowPlan(800f, columns).widths))
    }

    @Test
    fun pastFiveTimes160TheRightmostColumnsBecomeTabs() {
        val four = raftPaneGridRowPlan(700f, columns)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4)), four.groups)
        assertEquals(700, four.widths.sum().roundToInt())
        assertTrue(four.widths.all { it >= 160f })
        val two = raftPaneGridRowPlan(400f, columns)
        assertEquals(listOf(listOf(0), listOf(1, 2, 3, 4)), two.groups)
        assertEquals(listOf(200, 200), rounded(two.widths))
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
