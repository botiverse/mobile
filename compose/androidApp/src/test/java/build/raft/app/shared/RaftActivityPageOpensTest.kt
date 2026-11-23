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
    fun aLostEnterCallbackStopsHoldingOpensAfterTheTimeout() {
        opens.created("shell", 1, "shell", JsonObject(emptyMap()))
        open(1, "t1")
        open(1, "t2")
        scheduled.toList().forEach { it() }
        assertEquals(listOf("t1", "t2"), started)
    }

    @Test
    fun topPageReflectsAuthoritativeStackAndDropsClearedActivitiesOnBringToFront() {
        opens.created("shell", 1, "compose_slock_app_shell", JsonObject(emptyMap()))
        opens.created("t1-activity", 1, "compose_thread", thread("t1"))
        assertEquals("compose_thread", opens.topPage(1)?.pageName)
        assertEquals("t1-activity", opens.topHost(1))

        // Finishing Activity is skipped immediately even before destroyed().
        leavingHosts += "t1-activity"
        assertEquals("compose_slock_app_shell", opens.topPage(1)?.pageName)
        assertEquals("shell", opens.topHost(1))

        // singleTask + CLEAR_TOP delivers onNewIntent to shell before the cleared thread Activity
        // runs onDestroy() or flips isFinishing: broughtToFront trims the stale entry synchronously
        // and drops any queued pending open rather than resurrecting it.
        leavingHosts.clear()
        assertEquals("compose_thread", opens.topPage(1)?.pageName)
        assertEquals(RaftNativePageOpenOutcome.Opened, open(1, "t2"))
        assertEquals(RaftNativePageOpenOutcome.Queued, open(1, "t3"))
        assertEquals(listOf("t2"), started)
        opens.broughtToFront("shell", 1)
        assertEquals("compose_slock_app_shell", opens.topPage(1)?.pageName)
        assertEquals("shell", opens.topHost(1))
        assertEquals(listOf("t2"), started)
    }
}
