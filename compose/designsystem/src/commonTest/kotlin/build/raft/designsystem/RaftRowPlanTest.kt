package build.raft.designsystem

import build.raft.designsystem.components.RaftRowPlanColumn
import build.raft.designsystem.components.RaftRowPlanInput
import build.raft.designsystem.components.RaftRowPlanSize
import build.raft.designsystem.components.RaftRowUnit
import build.raft.designsystem.components.raftRowGroupUnits
import build.raft.designsystem.components.raftRowPlan
import build.raft.designsystem.components.raftRowSolveUnits
import build.raft.designsystem.components.raftRowUnitWidths
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // ------------------------------------------------------------ rule 4: give way, fold, clip, tabs

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
}
