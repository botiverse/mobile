package build.raft.designsystem

import build.raft.designsystem.components.RAFT_ROW_PAIR_WIDTH_DP
import build.raft.designsystem.components.RaftRowPlanColumn
import build.raft.designsystem.components.RaftRowPlanInput
import build.raft.designsystem.components.RaftRowPlanSize
import build.raft.designsystem.components.RaftRowRebalance
import build.raft.designsystem.components.RaftRowUnit
import build.raft.designsystem.components.raftRowBuildUnits
import build.raft.designsystem.components.raftRowDecideFold
import build.raft.designsystem.components.raftRowGroupUnits
import build.raft.designsystem.components.raftRowPlan
import build.raft.designsystem.components.raftRowSolveUnits
import build.raft.designsystem.components.raftRowSplitPair
import build.raft.designsystem.components.raftRowUnitWidths
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The #189 row plan (RaftRowPlan.kt), ported with the web's own cases: slock staging ec13decd3b,
 * packages/web/tests/rowPlan.test.ts and seamCascade.test.ts (`solveRow`). The prototype's numbers: rail 64 and
 * sidebar 240 around the row; the conversation 400 (or what the others leave), the thread 420, Tasks 340, Files 320,
 * the Artifact 560, the profile 380; content minimums 360, 360, 280, 280, 480, 320.
 */
class RaftRowPlanTest {
    private fun col(key: String, regions: List<String>, mins: List<Float>, saved: Float, savedWithoutThread: Float? = null) =
        RaftRowPlanColumn(key, regions, mins, saved, savedWithoutThread)

    private val thread = col("col:0", listOf("thread"), listOf(360f), 420f, 400f)
    private val tasks = col("col:1", listOf("tasks"), listOf(280f), 340f)
    private val files = col("col:2", listOf("files"), listOf(280f), 320f)
    private val artifact = col("col:3", listOf("artifact"), listOf(480f), 560f)
    private val profile = col("col:4", listOf("profile"), listOf(320f), 380f)
    private val all = listOf(thread, tasks, files)

    /** The web test's `row`: a window less the rail and the sidebar; the conversation what the others leave, at least 400. */
    private fun row(window: Int, columns: List<RaftRowPlanColumn>): RaftRowPlanInput {
        val available = window - 64f - 240f
        val others = columns.sumOf { it.saved.toDouble() }.toFloat()
        return RaftRowPlanInput(
            available = available,
            conversation = RaftRowPlanSize(maxOf(400f, available - others), 360f),
            thread = if (columns.any { "thread" in it.regions }) RaftRowPlanSize(420f, 360f) else null,
            columns = columns,
        )
    }

    private fun rounded(widths: List<Float>) = widths.map { it.roundToInt() }

    private fun sum(widths: List<Float>) = widths.sum().roundToInt()

    private fun unit(saved: Float, contentMin: Float) = RaftRowUnit(saved, contentMin)

    // ------------------------------------------------------------ seamCascade `solveRow` (rule 4)

    @Test
    fun roomToSpareGrowsEveryColumnInProportionAndShortOfRoomTheyGiveWayAboveTheirMinimumsThenClipTo160() {
        val pair = listOf(unit(600f, 360f), unit(400f, 360f))
        // Room to spare: every column grows in proportion; the saved widths fill the row exactly.
        assertEquals(listOf(900, 600), rounded(raftRowSolveUnits(pair, 1500f).widths))
        assertEquals(listOf(600f, 400f), raftRowSolveUnits(pair, 1000f).widths, "what a release saved is what shows")
        // 80 short: the conversation has 240 above 360, the thread 40: they give 80 * 240/280 and 80 * 40/280.
        assertEquals(listOf(531, 389), rounded(raftRowSolveUnits(pair, 920f).widths))
        assertEquals(listOf(360, 360), rounded(raftRowSolveUnits(pair, 720f).widths))
        assertFalse(raftRowSolveUnits(pair, 720f).clip)
        // A column saved under its content's minimum keeps its saved width as its floor: it never grows to give way.
        assertEquals(listOf(200, 360), rounded(raftRowSolveUnits(listOf(unit(200f, 360f), unit(400f, 360f)), 560f).widths))
        // Past the content minimums all shrink in proportion, clipping, never under 160.
        val clipped = raftRowSolveUnits(pair, 520f)
        assertTrue(clipped.clip)
        assertEquals(listOf(260, 260), rounded(clipped.widths))
        assertEquals(listOf(160, 160), rounded(raftRowSolveUnits(pair, 200f).widths))
    }

    // ------------------------------------------------------------ rule 3: the thread folds

    @Test
    fun theThreadStandsBesideTheConversationWhileBothKeep360AndNarrowerItFoldsIntoItsColumn() {
        // Scenario b at 1440: 1136 of room, the pair at its saved widths in proportion.
        val wide = raftRowPlan(row(1440, listOf(thread)))
        assertFalse(wide.fold)
        assertEquals(listOf("conversation", "col:0"), wide.units.map { it.key })
        // At 1024 the room is 720: the two at 360 each, side by side.
        val pair = raftRowPlan(row(1024, listOf(thread)))
        assertFalse(pair.fold)
        assertEquals(listOf(360, 360), rounded(pair.widths))
        // A pixel narrower the thread folds: one column, the conversation's, which the two share.
        val folded = raftRowPlan(row(1023, listOf(thread)))
        assertTrue(folded.fold)
        assertEquals(listOf(listOf("conversation", "thread")), folded.units.map { it.regions })
        assertEquals(listOf(719), rounded(folded.widths))
    }

    @Test
    fun theThreadFoldsBeforeAnyOtherColumnHasToClip() {
        // Scenario c at 1280: the thread, Tasks; 976 of room is short of 360 + 360 + 280.
        val c = raftRowPlan(row(1280, listOf(thread, tasks)))
        assertTrue(c.fold)
        assertEquals(listOf("conversation", "col:1"), c.units.map { it.key })
        assertTrue(c.widths[1] >= 280f, "Tasks keeps its content's 280: nothing clips")
        // Scenario f at 1920: five columns' content minimums need 1760 of 1616; the thread folds and nothing clips.
        val f = raftRowPlan(row(1920, listOf(thread, tasks, files, artifact)))
        assertTrue(f.fold)
        assertEquals(listOf(true, true, true, true), rounded(f.widths).mapIndexed { i, w -> w >= listOf(360, 280, 280, 480)[i] })
        // With room again it stands in a column of its own; the sidebar collapsed is the window 240 wider.
        assertFalse(raftRowPlan(row(1440, listOf(thread, tasks))).fold)
        assertFalse(raftRowPlan(row(1280, listOf(thread, tasks)).copy(available = 1280f - 64f)).fold)
        // The Artifact beside them changes nothing: 1136 is short of 360 + 360 + 480, so the thread folds.
        val withArtifact = raftRowPlan(row(1440, listOf(thread, artifact)))
        assertTrue(withArtifact.fold)
        assertEquals(listOf("conversation", "col:3"), withArtifact.units.map { it.key })
    }

    @Test
    fun foldedTheThreadLeavesItsColumnAndTheSectionsStackedWithItStayAtTheColumnsWidth() {
        val stacked = col("col:0", listOf("thread", "tasks"), listOf(360f, 280f), 420f, 360f)
        val units = raftRowBuildUnits(row(1100, listOf(stacked)), fold = true)
        assertEquals(
            listOf(
                listOf("conversation", "conversation+thread", 400f, 360f),
                listOf("col:0", "tasks", 360f, 280f),
            ),
            units.map { listOf(it.key, it.regions.joinToString("+"), it.saved, it.contentMin) },
        )
        // Alone in its column, the column goes with it, and the pair's saved width is both of theirs.
        val alone = raftRowBuildUnits(row(1100, listOf(thread)), fold = true).single()
        assertEquals("conversation", alone.key)
        assertEquals(820f, alone.saved)
        assertEquals(RAFT_ROW_PAIR_WIDTH_DP, alone.floor)
        assertTrue(alone.merged)
    }

    @Test
    fun forceFoldKeepsTheThreadFoldedWhateverTheRoomAndNoThreadMeansNothingToFold() {
        assertTrue(raftRowDecideFold(row(1440, listOf(thread)).copy(forceFold = true)).fold)
        assertFalse(raftRowDecideFold(row(1440, listOf(tasks)).copy(forceFold = true)).fold, "no thread, nothing to fold")
    }

    @Test
    fun comingBackOutOfTheFoldThePairsSavedWidthsAreSharedSoNeitherStartsUnder360() {
        // The thread saved at 200 next to a conversation of 900: out of the fold it would show clipped at 200.
        val input =
            row(1400, listOf(col("col:0", listOf("thread"), listOf(360f), 200f)))
                .copy(conversation = RaftRowPlanSize(900f, 360f), thread = RaftRowPlanSize(200f, 360f))
        assertNull(raftRowDecideFold(input.copy(wasFolded = false)).rebalance, "only on the way back out")
        val out = raftRowDecideFold(input.copy(wasFolded = true))
        assertFalse(out.fold)
        val rebalance = assertNotNull(out.rebalance)
        assertEquals(1100, (rebalance.conversation + rebalance.thread).roundToInt(), "the pair keeps its total")
        assertTrue(rebalance.thread >= 360f * (1100f / 1096f) - 1f, "the thread gets its 360 of what the row gives the pair")
    }

    @Test
    fun aSeamDragOnTheFoldedColumnSharesItsWidthByTheSavedWidths() {
        assertEquals(RaftRowRebalance(400f, 400f), raftRowSplitPair(800f, 400f, 400f, 360f, 360f))
        // At 720 or more each keeps its 360.
        assertEquals(RaftRowRebalance(360f, 360f), raftRowSplitPair(720f, 600f, 200f, 360f, 360f))
        // Under 720 the share alone.
        assertEquals(RaftRowRebalance(300f, 100f), raftRowSplitPair(400f, 300f, 100f, 360f, 360f))
    }

    // ------------------------------------------------------------ rule 4: give way, fold, clip, tabs

    @Test
    fun noThreadOpenNothingFoldsAndTheColumnsFillTheRow() {
        val plan = raftRowPlan(row(1440, listOf(tasks, files)))
        assertFalse(plan.fold)
        assertEquals(1136, sum(plan.widths))
    }

    @Test
    fun shortOfRoomEveryColumnGivesUpInProportionWhatItHasAboveItsMinimumAndNothingClips() {
        // Scenario e: the four columns' saved widths fill 1920's 1616 exactly.
        assertEquals(listOf(536, 420, 340, 320), rounded(raftRowPlan(row(1920, all)).widths))
        // At 1600 (1296 of room) they give 320 of the 336 they have above 360 / 360 / 280 / 280.
        val plan = raftRowPlan(row(1600, all))
        assertFalse(plan.fold)
        assertEquals(1296, sum(plan.widths))
        plan.widths.forEachIndexed { i, width -> assertTrue(width >= listOf(360f, 360f, 280f, 280f)[i] - 0.01f, "column $i keeps its minimum") }
    }

    @Test
    fun thenTheThreadFoldsTheFoldedColumnGivesWayTo360ThenAllShrinkInProportionAndClipTo160() {
        // Short of the four minimums: the thread folds first, nothing else clips.
        val folded = raftRowPlan(row(1920, all).copy(available = 1279f))
        assertTrue(folded.fold)
        assertTrue(folded.widths[1] >= 280f && folded.widths[2] >= 280f, "Tasks and Files keep 280")
        // Under 720 + 280 + 280 the folded column gives way first, down to its 360, before Tasks or Files clips.
        assertEquals(listOf(440, 280, 280), rounded(raftRowPlan(row(1920, all).copy(available = 1000f)).widths))
        // Past 360 + 280 + 280 every column shrinks in proportion, clipping.
        val clipping = raftRowPlan(row(1920, all).copy(available = 820f))
        assertEquals(820, sum(clipping.widths))
        assertTrue(clipping.widths[0] < 360f && clipping.widths[1] < 280f && clipping.widths[2] < 280f)
        assertTrue(clipping.widths[0] / clipping.widths[1] > 1.2f, "in proportion to what each had")
        // Never under 160.
        assertEquals(listOf(160, 160, 160), rounded(raftRowPlan(row(1920, all).copy(available = 480f)).widths))
    }

    @Test
    fun withEveryColumnAt160AndStillNoRoomTheRightmostColumnBecomesATabInItsLeftNeighboursHead() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3), listOf(4)), raftRowGroupUnits(5, 800f))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4)), raftRowGroupUnits(5, 799f))
        assertEquals(listOf(listOf(0), listOf(1, 2, 3, 4)), raftRowGroupUnits(5, 479f))
        assertEquals(listOf(listOf(0, 1, 2)), raftRowGroupUnits(3, 100f), "the conversation's column holds them all, at worst")
        // The prototype's row: the folded conversation, Tasks, Files, the Artifact and the profile in 640 - 304 = 336.
        val plan = raftRowPlan(row(1920, listOf(thread, tasks, files, artifact, profile)).copy(available = 336f))
        assertTrue(plan.fold)
        assertEquals(listOf(listOf(0), listOf(1, 2, 3, 4)), plan.groups)
        assertEquals(listOf(176, 160), rounded(plan.widths), "solved by each column's first unit")
        // Each column shows its group's width where its tab is the one picked, none where it isn't.
        assertEquals(listOf(176, 0, 0, 160, 0), rounded(raftRowUnitWidths(plan) { group -> if (group == 1) 3 else 0 }))
        assertEquals(listOf(176, 160, 0, 0, 0), rounded(raftRowUnitWidths(plan) { group -> plan.groups[group].first() }))
    }

    @Test
    fun wideningUndoesItInTheReverseOrderAndNothingSavedChanges() {
        val input = row(1920, listOf(thread, tasks, files, artifact))
        val before = input.copy()
        val wide = raftRowPlan(input)
        for (available in listOf(1500f, 1100f, 700f, 400f, 200f)) raftRowPlan(input.copy(available = available))
        assertEquals(wide, raftRowPlan(input))
        assertEquals(before, input)
    }

    @Test
    fun theSidebarCollapsingIsTheWindow240WiderAndAnExpandedColumnStaysExpanded() {
        val open = row(1280, listOf(thread, tasks))
        val collapsed = open.copy(available = open.available + 240f)
        assertEquals(raftRowPlan(collapsed), raftRowPlan(row(1520, listOf(thread, tasks)).copy(conversation = open.conversation)))
        val expanded = raftRowPlan(open.copy(expanded = "tasks"))
        assertEquals(expanded.expandedUnit, raftRowPlan(collapsed.copy(expanded = "tasks")).expandedUnit)
    }

    // ------------------------------------------------------------ rule 5: Expand

    @Test
    fun theExpandedRegionsColumnFillsTheRowAndEveryOtherColumnIs160() {
        val plan = raftRowPlan(row(1920, all).copy(expanded = "tasks"))
        assertEquals("col:1", plan.expandedUnit)
        assertEquals(listOf(160, 160, 1616 - 480, 160), rounded(plan.widths))
        // The thread folded when it was expanded stays folded, and expanding it expands the conversation's column.
        val folded = raftRowPlan(row(1280, listOf(thread, tasks)).copy(expanded = "thread", expandFold = true))
        assertTrue(folded.fold)
        assertEquals("conversation", folded.expandedUnit)
        assertEquals(listOf(976 - 160, 160), rounded(folded.widths))
        // Restored (nothing expanded), the row is the saved widths' again, to the pixel.
        assertEquals(raftRowPlan(row(1920, all)).widths, raftRowPlan(row(1920, all).copy(expanded = null)).widths)
    }

    @Test
    fun anExpandedRegionThatLeftTheRowExpandsNothingAndWithEveryColumnATabTheExpandedOneIsShown() {
        assertNull(raftRowPlan(row(1920, listOf(tasks)).copy(expanded = "files")).expanded)
        val tabbed =
            raftRowPlan(row(1920, listOf(thread, tasks, files, artifact, profile)).copy(available = 336f, expanded = "artifact", expandFold = true))
        assertEquals(listOf(listOf(0), listOf(1, 2, 3, 4)), tabbed.groups)
        assertEquals("col:3", tabbed.expandedUnit)
        assertEquals(listOf(160, 176), rounded(tabbed.widths))
    }

    // ------------------------------------------------------------ one more column at the row's end

    @Test
    fun aDetailColumnAtTheRowsEndGivesWayWithTheOthersAndNothingOverflows() {
        // Web's keyboard-shortcuts panel case: Tasks and Files and a 400 / 320 column, no thread.
        val panel = col("panel", listOf("panel"), listOf(320f), 400f)
        for (window in listOf(1280, 1100, 1024)) {
            val input = row(window, listOf(tasks, files, panel))
            val plan = raftRowPlan(input)
            assertEquals(listOf("conversation", "col:1", "col:2", "panel"), plan.units.map { it.key })
            assertTrue(abs(plan.widths.sum() - input.available) < 1f, "$window: the columns fill the row and no more")
            assertTrue(plan.widths.all { it >= 159.5f }, "$window: no column under 160: ${rounded(plan.widths)}")
        }
        // At 1280 the row's 976 is less than the four content minimums: all give way, side by side, none a tab.
        assertEquals(4, raftRowPlan(row(1280, listOf(tasks, files, panel))).groups.size)
    }

    @Test
    fun aSingleColumnBesideTheConversationGivesWayThenClipsAndExpandedFillsTheRow() {
        // Web's Tasks page task: no sidebar (the row is the window less the rail), the task at 400 / 320.
        val task = col("task", listOf("task"), listOf(320f), 400f)
        fun tasksRow(window: Int) = row(window + 240, listOf(task))
        assertEquals(listOf(976, 400), rounded(raftRowPlan(tasksRow(1440)).widths))
        // Narrower: both give up what they have above their content's minimum, in proportion; nothing clips.
        val narrow = raftRowPlan(tasksRow(764).copy(conversation = RaftRowPlanSize(976f, 360f)))
        assertEquals(700, sum(narrow.widths))
        assertTrue(narrow.widths[0] >= 360f && narrow.widths[1] >= 320f)
        // Narrower still: both under their content, each at 160 at the least.
        val clipped = raftRowPlan(tasksRow(564).copy(conversation = RaftRowPlanSize(976f, 360f)))
        assertEquals(500, sum(clipped.widths))
        assertTrue(clipped.widths[0] in 160f..360f && clipped.widths[1] in 160f..320f)
        // Rule 5: the task expanded fills the row, the conversation a 160 strip.
        assertEquals(listOf(160, 1216), rounded(raftRowPlan(tasksRow(1440).copy(expanded = "task")).widths))
    }
}
