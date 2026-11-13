package build.raft.designsystem

import build.raft.designsystem.components.RaftPaneGridColumnControls
import build.raft.designsystem.components.RaftPaneGridExpand
import build.raft.designsystem.components.RaftRowPlanColumn
import build.raft.designsystem.components.raftPaneGridHeadShownActions
import build.raft.designsystem.components.raftPaneGridRowPlan
import build.raft.designsystem.components.raftPaneGridShownUnit
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #189 step 5's pure parts: the head fit (rule 6, web headFit.test.ts with the web's px: 32 buttons 4 apart, the
 * head's 20 / 13 margins, a lead 40), Expand's plan (rule 5: the expanded column fills, every other one 160) and its
 * state, and a group showing the column expanded.
 */
class RaftPaneGridHeadTest {
    /** Web `shownOwnActions` with its px: Expand and Close shown unless said otherwise. */
    private fun head(width: Float, own: Int, lead: Int = 0, menuItems: Int = 0, tabs: Boolean = false, controls: Int = 2): Int =
        raftPaneGridHeadShownActions(
            widthDp = width,
            leadButtons = lead,
            own = own,
            controls = controls,
            menuItems = menuItems,
            tabs = tabs,
            buttonDp = 32f,
            startDp = if (lead > 0) 13f else 20f,
            endDp = 13f,
            gapDp = 4f,
            leadDp = 40f,
            titleGapDp = 0f,
        )

    @Test
    fun withRoomEveryOwnActionIsAButtonAndAHeadNotMeasuredShowsThemAll() {
        assertEquals(1, head(400f, 1))
        assertEquals(3, head(0f, 3))
    }

    @Test
    fun theLastFoldsFirstAndTheMenuCountsOnceSomethingIsInIt() {
        // Tasks at 160: New Task goes into ⋯; Expand and Close stay.
        assertEquals(0, head(160f, 1))
        assertEquals(1, head(205f, 1))
        // Three own actions, Expand and Close: 5 x 32 + 16 = 176 of 265 - 33 - 56.
        assertEquals(3, head(265f, 3))
        assertEquals(1, head(264f, 3))
        assertEquals(1, head(229f, 3))
        assertEquals(0, head(228f, 3))
        // A menu with commands of its own (Move Left) is there anyway.
        assertEquals(2, head(265f, 3, menuItems = 1))
    }

    @Test
    fun aLeadButtonAndTabsInTheTitlesPlaceTakeTheirRoom() {
        assertEquals(0, head(205f, 1, lead = 1))
        assertEquals(1, head(270f, 1, tabs = true))
        assertEquals(0, head(250f, 1, tabs = true))
    }

    private val columns =
        listOf(
            RaftRowPlanColumn("thread", listOf("thread"), listOf(360f), 400f),
            RaftRowPlanColumn("sideCards", listOf("tasks", "files"), listOf(280f, 280f), 360f),
            RaftRowPlanColumn("profile", listOf("profile", "details"), listOf(320f, 320f), 400f),
        )

    @Test
    fun anExpandedRegionsColumnFillsTheRowAndEveryOtherIs160() {
        val plan = raftPaneGridRowPlan(1600f, columns, expanded = "files")
        assertEquals("files", plan.expanded)
        assertEquals("sideCards", plan.expandedUnit)
        // The conversation, the thread and the profile at 160; Tasks / Files the rest.
        assertEquals(listOf(160, 160, 1120, 160), plan.widths.map { it.roundToInt() })
        // A region the row no longer has: planned as if nothing were expanded (the grid then forgets it).
        val gone = raftPaneGridRowPlan(1600f, columns, expanded = "artifact")
        assertNull(gone.expanded)
        assertEquals(raftPaneGridRowPlan(1600f, columns).widths, gone.widths)
    }

    @Test
    fun aGroupOfTabsShowsTheColumnExpandedWhateverWasPicked() {
        // 400: the thread's group holds the three side columns as tabs; the profile expanded shows in it.
        val plan = raftPaneGridRowPlan(400f, columns, expanded = "details")
        val group = plan.groups.first { 3 in it }
        assertEquals(3, raftPaneGridShownUnit(plan, group, mapOf("thread" to "sideCards")))
        assertEquals(1, raftPaneGridShownUnit(raftPaneGridRowPlan(400f, columns), group, emptyMap()))
    }

    @Test
    fun expandTogglesRestoresAndWritesOnlyOnChange() {
        val expand = RaftPaneGridExpand()
        assertFalse(expand.restore(), "nothing to restore")
        expand.toggle("thread")
        assertEquals("thread", expand.region)
        // Another region's Expand moves the expansion there; the same one again restores.
        expand.toggle("tasks")
        assertEquals("tasks", expand.region)
        expand.toggle("tasks")
        assertNull(expand.region)
        expand.expand("profile")
        assertTrue(expand.restore())
        assertNull(expand.region)
    }

    @Test
    fun aHeadsExpandIsPressedOnlyOnTheRegionExpandedAndComparesByValue() {
        val expand = RaftPaneGridExpand()
        val controls = RaftPaneGridColumnControls(listOf(listOf("tasks", "files")), expandShown = true, expanded = "files", expandState = expand, tabs = null, tabsSlot = null)
        assertTrue(controls.expandFor("files")!!.pressed)
        assertFalse(controls.expandFor("tasks")!!.pressed, "another head in its column shows it unpressed")
        controls.expandFor("tasks")!!.toggle()
        assertEquals("tasks", expand.region, "and moves the expansion there")
        assertEquals(controls, controls.copy(rowCells = listOf(listOf("tasks", "files"))), "equal values, equal controls")
        assertNull(controls.copy(expandState = null).expandFor("tasks"), "no expand state, no Expand")
    }
}
