package build.raft.app.shared

import build.raft.app.cmp.host.RaftNativePageOpenOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * task #151 follow-up: the Activity path's pushes per task. A notification (or banner) open of the
 * thread already on top, or a second one while its Activity is still coming in, opens nothing; a
 * different page waits for the enter animation (latest wins); a lost callback times out.
 */
class RaftActivityPageOpensTest {
    private val scheduled = mutableListOf<() -> Unit>()
    private val leavingHosts = mutableSetOf<String>()
    private val opens =
        RaftActivityPageOpens<String>(
            schedule = { _, block -> scheduled += block },
            leaving = { it in leavingHosts },
        )
    private val started = mutableListOf<String>()

    private fun thread(id: String, vararg extra: Pair<String, String>) =
        JsonObject(mapOf("id" to JsonPrimitive(id)) + extra.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun open(task: Int, id: String, vararg extra: Pair<String, String>): RaftNativePageOpenOutcome =
        opens.open(task, "thread", thread(id, *extra)) { started += id }

    @Test
    fun notificationForTheThreadOnTopOpensNothing() {
        opens.created("shell", 1, "shell", JsonObject(emptyMap()))
        opens.created("t1-activity", 1, "thread", thread("t1", "nowEpochMillis" to "1"))
        assertEquals(RaftNativePageOpenOutcome.Dropped, open(1, "t1", "nowEpochMillis" to "2"))
        assertEquals(emptyList<String>(), started)
    }

    @Test
    fun secondOpenWhileTheActivityComesInIsDroppedAndADifferentOneWaits() {
        opens.created("shell", 1, "shell", JsonObject(emptyMap()))
        assertEquals(RaftNativePageOpenOutcome.Opened, open(1, "t1"))
        // Before the new Activity exists (startActivity is asynchronous).
        assertEquals(RaftNativePageOpenOutcome.Dropped, open(1, "t1"))
        assertEquals(RaftNativePageOpenOutcome.Queued, open(1, "t2"))
        assertEquals(RaftNativePageOpenOutcome.Queued, open(1, "t3"))
        opens.created("t1-activity", 1, "thread", thread("t1"))
        assertEquals(listOf("t1"), started)
        opens.entered("t1-activity")
        assertEquals(listOf("t1", "t3"), started)
    }

    @Test
    fun tasksAreIndependentWindows() {
        opens.created("shell-a", 1, "shell", JsonObject(emptyMap()))
        opens.created("shell-b", 2, "shell", JsonObject(emptyMap()))
        open(1, "t1")
        assertEquals(RaftNativePageOpenOutcome.Opened, open(2, "t1"))
        assertEquals(listOf("t1", "t1"), started)
    }

    @Test
    fun aLostEnterCallbackStopsHoldingOpensAfterTheTimeout() {
        opens.created("shell", 1, "shell", JsonObject(emptyMap()))
        open(1, "t1")
        open(1, "t2")
        scheduled.toList().forEach { it() }
        assertEquals(listOf("t1", "t2"), started)
    }

    @Test
    fun theIncomingPageGoingAwayEndsThePush() {
        opens.created("shell", 1, "shell", JsonObject(emptyMap()))
        open(1, "t1")
        opens.created("t1-activity", 1, "thread", thread("t1"))
        opens.destroyed("t1-activity")
        assertEquals(RaftNativePageOpenOutcome.Opened, open(1, "t1"))
        assertEquals(listOf("t1", "t1"), started)
    }

    @Test
    fun aFinishingTopPageIsNotTheTopAnyMore() {
        opens.created("shell", 1, "shell", JsonObject(emptyMap()))
        open(1, "t1")
        opens.created("t1-activity", 1, "thread", thread("t1"))
        opens.entered("t1-activity")
        // Back: the thread Activity is finishing, not destroyed yet; the same row is tapped again.
        leavingHosts += "t1-activity"
        assertEquals(RaftNativePageOpenOutcome.Opened, open(1, "t1"))
    }
}
