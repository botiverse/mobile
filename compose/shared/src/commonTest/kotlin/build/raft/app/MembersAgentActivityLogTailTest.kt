package build.raft.app

import build.raft.app.data.parseSharedAgentActivity
import build.raft.app.members.MembersAgentActivityTail
import build.raft.app.members.membersAgentActivityTailOnNewLatest
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
    fun identicalLogRowsAtTheSameTimeKeepDistinctIds() {
        val row = """{"timestamp":5,"entry":{"kind":"status","activity":"working","detail":"Tool finished"}}"""
        val snapshot = parseSharedAgentActivity("[$row,$row]", MemberId("agent-1"))!!
        assertEquals(2, snapshot.activity.map { it.id }.toSet().size)
    }
}
