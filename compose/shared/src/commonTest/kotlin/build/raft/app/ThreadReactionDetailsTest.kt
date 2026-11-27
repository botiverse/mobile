package build.raft.app

import build.raft.app.thread.RaftThreadReactionItem
import build.raft.app.thread.ThreadReactionTooltipNames
import build.raft.app.thread.resolveThreadReactorNames
import build.raft.app.thread.threadReactionTooltipNames
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
    fun fiveNamesThenTheRestAsHiddenCount() {
        assertEquals(
            ThreadReactionTooltipNames(listOf("A", "B", "C", "D", "E"), 3),
            names(reaction(listOf("A", "B", "C", "D", "E", "F"), count = 8))
        )
    }

    @Test
    fun youComeFirstAndYourNameIsDropped() {
        assertEquals(
            ThreadReactionTooltipNames(listOf("You", "Alice", "Bob"), 0),
            names(reaction(listOf("Alice", "Artin", "Bob"), count = 3, ids = listOf("a", "me", "b"), selected = true))
        )
    }
}

// task #569 follow-up: reactor names arrive empty on the addReaction mutation
// summary / reduced payloads even when reactor ids are present, so the tooltip
// degraded to "1 person" right after tapping. resolveThreadReactorNames
// backfills names from the thread member directory on-device.
class ResolveThreadReactorNamesTest {
    private val members = mapOf("u1" to "Alice", "u2" to "Bob", "self" to "Artin")

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
}
