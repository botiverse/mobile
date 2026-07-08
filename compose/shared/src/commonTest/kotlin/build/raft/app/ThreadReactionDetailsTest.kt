package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

// task #569: reactor tooltip label — first N names + "+K" truncation, with a
// bare-count fallback when the payload carries no reactor names.
class ThreadReactionDetailsTest {
    @Test
    fun singleReactorShowsTheName() {
        assertEquals("Alice", threadReactionDetailsLabel(listOf("Alice"), count = 1))
    }

    @Test
    fun fewReactorsJoinWithCommas() {
        assertEquals(
            "Alice, Bob, Carol",
            threadReactionDetailsLabel(listOf("Alice", "Bob", "Carol"), count = 3)
        )
    }

    @Test
    fun manyReactorsTruncateToMaxNamesPlusRemainder() {
        assertEquals(
            "A, B, C, D +2",
            threadReactionDetailsLabel(listOf("A", "B", "C", "D", "E", "F"), count = 6)
        )
    }

    @Test
    fun remainderCountsUnnamedReactorsBeyondTheNameList() {
        // Server counted 5 reactors but only sent 2 names — the tooltip must
        // not silently understate the count.
        assertEquals(
            "Alice, Bob +3",
            threadReactionDetailsLabel(listOf("Alice", "Bob"), count = 5)
        )
    }

    @Test
    fun blankAndDuplicateNamesAreDropped()  {
        assertEquals(
            "Alice, Bob",
            threadReactionDetailsLabel(listOf(" Alice ", "", "Alice", "Bob"), count = 2)
        )
    }

    @Test
    fun noNamesFallsBackToBareCount() {
        assertEquals("1 person", threadReactionDetailsLabel(emptyList(), count = 1))
        assertEquals("4 people", threadReactionDetailsLabel(emptyList(), count = 4))
    }
}
