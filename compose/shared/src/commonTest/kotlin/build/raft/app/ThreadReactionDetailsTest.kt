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

// task #569 follow-up: reactor names arrive empty on the addReaction mutation
// summary / reduced payloads even when reactor ids are present, so the tooltip
// degraded to "1 person" right after tapping. resolveThreadReactorNames
// backfills names from the thread member directory on-device.
class ResolveThreadReactorNamesTest {
    private val members = mapOf("u1" to "Alice", "u2" to "Bob", "self" to "Artin")

    @Test
    fun serverProvidedNamesArePreserved() {
        assertEquals(
            listOf("Alice", "Bob"),
            resolveThreadReactorNames(
                reactorIds = listOf("u1", "u2"),
                reactorNames = listOf("Alice", "Bob"),
                memberNameById = members
            )
        )
    }

    @Test
    fun selfReactionResolvesFromDirectoryWhenNamesEmpty() {
        // The addReaction summary named nobody but carried the current user's id.
        assertEquals(
            listOf("Artin"),
            resolveThreadReactorNames(
                reactorIds = listOf("self"),
                reactorNames = emptyList(),
                memberNameById = members
            )
        )
    }

    @Test
    fun namesAndIdsMergeWithoutDuplicates() {
        // Server named Alice; Bob only came through as an id — union, deduped.
        assertEquals(
            listOf("Alice", "Bob"),
            resolveThreadReactorNames(
                reactorIds = listOf("u1", "u2"),
                reactorNames = listOf("Alice"),
                memberNameById = members
            )
        )
    }

    @Test
    fun unknownIdsAreSkippedSoTheLabelCanFallBackToCount() {
        // Reactor not in the roster — leave it out; the "+K" remainder in
        // threadReactionDetailsLabel still reflects the true count.
        assertEquals(
            emptyList<String>(),
            resolveThreadReactorNames(
                reactorIds = listOf("ghost"),
                reactorNames = emptyList(),
                memberNameById = members
            )
        )
    }

    @Test
    fun blankIdsAndNamesAreDropped() {
        assertEquals(
            listOf("Alice"),
            resolveThreadReactorNames(
                reactorIds = listOf(" ", "u1"),
                reactorNames = listOf("  "),
                memberNameById = members
            )
        )
    }

    @Test
    fun selfReactionResolvesFromSessionEvenWhenNotInRoster() {
        // First principles: the current user's own name is authoritative from
        // the session, so a self-reaction must resolve even if the thread roster
        // is incomplete and doesn't list them.
        assertEquals(
            listOf("Me"),
            resolveThreadReactorNames(
                reactorIds = listOf("stranger-self"),
                reactorNames = emptyList(),
                memberNameById = emptyMap(),
                currentUserId = "stranger-self",
                currentUserName = "Me"
            )
        )
    }

    @Test
    fun dedupIsByReactorIdNotDisplayName() {
        // Dedup is anchored on reactor identity: a repeated id collapses to one,
        // and the true count is preserved for the label's "+K" remainder.
        assertEquals(
            listOf("Alice", "Bob"),
            resolveThreadReactorNames(
                reactorIds = listOf("u1", "u1", "u2"),
                reactorNames = listOf("Alice", "Alice", "Bob"),
                memberNameById = members
            )
        )
    }

    @Test
    fun sessionNameTakesPrecedenceOverRosterForCurrentUser() {
        assertEquals(
            listOf("Me", "Alice"),
            resolveThreadReactorNames(
                reactorIds = listOf("self", "u1"),
                reactorNames = emptyList(),
                memberNameById = members,
                currentUserId = "self",
                currentUserName = "Me"
            )
        )
    }
}
