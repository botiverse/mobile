package build.raft.designsystem

import build.raft.designsystem.components.raftTableColumnWidths
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftTableTest {
    @Test
    fun roomOverTheContentIsSharedInProportionToIt() {
        assertEquals(listOf(100, 300), raftTableColumnWidths(container = 400, minimums = listOf(10, 10), contents = listOf(50, 150)))
    }

    @Test
    fun withoutRoomEachColumnGivesUpItsShareOfTheGapToItsMinimum() {
        assertEquals(listOf(50, 150), raftTableColumnWidths(container = 200, minimums = listOf(50, 50), contents = listOf(50, 350)))
    }

    @Test
    fun aTableIsNeverNarrowerThanItsMinimums() {
        assertEquals(listOf(80, 90), raftTableColumnWidths(container = 100, minimums = listOf(80, 90), contents = listOf(400, 90)))
    }

    @Test
    fun widthsAreWholePixelsThatFillTheTable() {
        val widths = raftTableColumnWidths(container = 100, minimums = listOf(0, 0, 0), contents = listOf(1, 1, 1))
        assertEquals(100, widths.sum())
        assertEquals(listOf(33, 34, 33), widths)
        assertEquals(100, raftTableColumnWidths(container = 100, minimums = listOf(0, 0), contents = listOf(0, 0)).sum())
    }
}
