package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

// task #569: who the reaction tooltip names, as web MessageItem `reactionReactorNames`: "You"
// first when you reacted, at most 5 names, the rest counted from the reaction's count.
class ThreadReactionDetailsTest {
    private fun reaction(
        names: List<String>,
        count: Int,
        ids: List<String> = names.indices.map { "u$it" },
        selected: Boolean = false
    ) = RaftThreadReactionItem(emoji = "👌", count = count, selected = selected, reactorIds = ids, reactorNames = names)

    private fun names(item: RaftThreadReactionItem, viewer: String? = "me") =
        threadReactionTooltipNames(item, viewerUserId = viewer, youLabel = "You")

    @Test
    fun singleReactorShowsTheName() {
        assertEquals(ThreadReactionTooltipNames(listOf("Lagoon"), 0), names(reaction(listOf("Lagoon"), count = 1)))
    }

    @Test
    fun fiveNamesThenTheRestAsHiddenCount() {
        assertEquals(
            ThreadReactionTooltipNames(listOf("A", "B", "C", "D", "E"), 3),
            names(reaction(listOf("A", "B", "C", "D", "E", "F"), count = 8))
        )
    }

    @Test
    fun hiddenCountCoversUnnamedReactors() {
        // Five reacted, two are named: "Alice, Bob, +3 more".
        assertEquals(
            ThreadReactionTooltipNames(listOf("Alice", "Bob"), 3),
            names(reaction(listOf("Alice", "Bob"), count = 5))
        )
    }

    @Test
    fun youComeFirstAndYourNameIsDropped() {
        assertEquals(
            ThreadReactionTooltipNames(listOf("You", "Alice", "Bob"), 0),
            names(reaction(listOf("Alice", "Artin", "Bob"), count = 3, ids = listOf("a", "me", "b"), selected = true))
        )
    }

    @Test
    fun optimisticSelfReactionNotYetInTheRosterStillSaysYou() {
        assertEquals(
            ThreadReactionTooltipNames(listOf("You", "Alice"), 0),
            names(reaction(listOf("Alice"), count = 2, ids = listOf("a"), selected = true))
        )
    }

    @Test
    fun misalignedRosterWithYouInsideShowsTheNamesAsTheyAre() {
        assertEquals(
            ThreadReactionTooltipNames(listOf("Artin"), 1),
            names(reaction(listOf("Artin"), count = 2, ids = listOf("me", "ghost"), selected = true))
        )
    }

    @Test
    fun noViewerIdNoYou() {
        assertEquals(
            ThreadReactionTooltipNames(listOf("Artin"), 0),
            names(reaction(listOf("Artin"), count = 1, ids = listOf("me"), selected = true), viewer = null)
        )
    }

    @Test
    fun blankNamesAreDroppedAndNoNamesLeavesTheCount() {
        assertEquals(ThreadReactionTooltipNames(listOf("Alice"), 1), names(reaction(listOf(" Alice ", " "), count = 2)))
        assertEquals(ThreadReactionTooltipNames(emptyList(), 4), names(reaction(emptyList(), count = 4)))
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
        // Reactor not in the roster — leave it out; the "+K more" remainder in
        // threadReactionTooltipNames still reflects the true count.
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
