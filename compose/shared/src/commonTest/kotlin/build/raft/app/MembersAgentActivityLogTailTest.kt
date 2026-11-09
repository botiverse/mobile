package build.raft.app

import build.raft.app.data.parseSharedAgentActivity
import build.raft.app.model.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The agent Activity raw log's tail rules and the row ids it keys its rows by. */
class MembersAgentActivityLogTailTest {
    @Test
    fun theFirstNewestIsFollowedSoTheLogOpensAtTheBottom() {
        val update = membersAgentActivityTailOnNewLatest(MembersAgentActivityTail(), listOf("a", "b"), scrolling = false)
        assertTrue(update.scrollToLatest)
        assertEquals(MembersAgentActivityTail(following = true, seenLastKey = "b", newBelow = 0), update.tail)
    }

    @Test
    fun aParkedReaderFollowsButNotWhileScrolling() {
        val parked = MembersAgentActivityTail(following = true, seenLastKey = "b")
        assertTrue(membersAgentActivityTailOnNewLatest(parked, listOf("a", "b", "c"), scrolling = false).scrollToLatest)
        val dragging = membersAgentActivityTailOnNewLatest(parked, listOf("a", "b", "c"), scrolling = true)
        assertFalse(dragging.scrollToLatest)
        assertEquals(1, dragging.tail.newBelow)
    }

    @Test
    fun aReaderWhoScrolledUpGetsACountOfTheStepsBelowTheNewestTheySaw() {
        val away = MembersAgentActivityTail(following = false, seenLastKey = "c")
        val update = membersAgentActivityTailOnNewLatest(away, listOf("b", "c", "d", "e"), scrolling = false)
        assertFalse(update.scrollToLatest)
        assertEquals(2, update.tail.newBelow)
        // The capped log trimmed the row they saw last: count one more, never past the rows shown.
        val trimmed = membersAgentActivityTailOnNewLatest(update.tail.copy(seenLastKey = "gone"), listOf("x", "y"), scrolling = false)
        assertEquals(2, trimmed.tail.newBelow)
    }

    @Test
    fun theViewportParksAtTheBottomAndADragAwayStopsFollowing() {
        val away = MembersAgentActivityTail(following = false, seenLastKey = "c", newBelow = 2)
        assertEquals(
            MembersAgentActivityTail(following = true, seenLastKey = "e", newBelow = 0),
            membersAgentActivityTailOnViewport(away, latestKey = "e", scrolling = false, atBottom = true),
        )
        val parked = MembersAgentActivityTail(following = true, seenLastKey = "e")
        assertFalse(membersAgentActivityTailOnViewport(parked, "e", scrolling = true, atBottom = false).following)
        // Not at the bottom and not scrolling (a new row laid out below a parked reader): unchanged.
        assertEquals(parked, membersAgentActivityTailOnViewport(parked, "f", scrolling = false, atBottom = false))
    }

    @Test
    fun logRowIdsDoNotShiftWhenTheLatestWindowSlides() {
        fun row(t: Long, text: String) = """{"timestamp":$t,"entry":{"kind":"text","text":"$text"}}"""
        val first = parseSharedAgentActivity("[${row(1, "a")},${row(2, "b")},${row(3, "c")}]", MemberId("agent-1"))!!
        // The next load's window dropped the oldest row and gained a newer one.
        val next = parseSharedAgentActivity("[${row(2, "b")},${row(3, "c")},${row(4, "d")}]", MemberId("agent-1"))!!
        assertEquals(first.activity.drop(1).map { it.id }, next.activity.take(2).map { it.id })
    }

    @Test
    fun identicalLogRowsAtTheSameTimeKeepDistinctIds() {
        val row = """{"timestamp":5,"entry":{"kind":"status","activity":"working","detail":"Tool finished"}}"""
        val snapshot = parseSharedAgentActivity("[$row,$row]", MemberId("agent-1"))!!
        assertEquals(2, snapshot.activity.map { it.id }.toSet().size)
    }
}
