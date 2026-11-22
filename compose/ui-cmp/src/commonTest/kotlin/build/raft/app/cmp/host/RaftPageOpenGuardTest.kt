package build.raft.app.cmp.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * task #151: two quick taps on a message's replies row opened the thread twice (the page being
 * covered still takes taps during the push). One page's opens go through [RaftPageOpenGuard].
 */
class RaftPageOpenGuardTest {
    private var clock = 0L
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val opened = mutableListOf<String>()
    private val guard =
        RaftPageOpenGuard(
            now = { clock },
            schedule = { delay, block -> scheduled += (clock + delay) to block },
            windowMillis = 450,
        )

    private fun data(vararg pairs: Pair<String, Any>) =
        JsonObject(pairs.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })

    private fun open(pageName: String, pageData: JsonObject) =
        guard.open(pageName, pageData) { name, sent -> opened += "$name ${(sent["id"] as? JsonPrimitive)?.content.orEmpty()}".trim() }

    /** Moves the clock to [to] and runs every scheduled block that is due. */
    private fun advanceTo(to: Long) {
        clock = to
        while (true) {
            val due = scheduled.firstOrNull { it.first <= clock } ?: break
            scheduled.remove(due)
            due.second()
        }
    }

    @Test fun `a double push of the same route opens once`() {
        open("thread", data("id" to "t1"))
        advanceTo(120)
        open("thread", data("id" to "t1"))
        advanceTo(2_000)
        assertEquals(listOf("thread t1"), opened)
        assertTrue(scheduled.isEmpty())
    }

    @Test fun `per-open facts do not make a repeat a new route`() {
        open("thread", data("id" to "t1", "nowEpochMillis" to 1))
        advanceTo(100)
        open("thread", data("id" to "t1", "nowEpochMillis" to 2, "slockOpenWithoutAnimation" to true))
        assertEquals(listOf("thread t1"), opened)
    }

    @Test fun `a different route during the push is queued and the latest one wins`() {
        open("thread", data("id" to "t1"))
        advanceTo(100)
        open("thread", data("id" to "t2"))
        advanceTo(150)
        open("profile", data("id" to "p1"))
        advanceTo(200)
        open("profile", data("id" to "p1"))
        assertEquals(listOf("thread t1"), opened, "nothing more opens mid push")
        advanceTo(450)
        assertEquals(listOf("thread t1", "profile p1"), opened)
        // The queued push opened its own window.
        advanceTo(600)
        open("profile", data("id" to "p1"))
        assertEquals(listOf("thread t1", "profile p1"), opened)
    }
}
