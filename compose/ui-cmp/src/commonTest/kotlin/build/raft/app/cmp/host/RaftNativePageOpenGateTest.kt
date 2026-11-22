package build.raft.app.cmp.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * task #151 follow-up: a native stack's pushes (Android's Activity path) through
 * [RaftNativePageOpenGate]: same route on top is a no-op; during a push the same route is dropped
 * and a different one queued (latest wins) until the push's transition ends.
 */
class RaftNativePageOpenGateTest {
    /** The fake stack: route identities bottom first, as the host would read them. */
    private val stack = mutableListOf<Any>()
    private val opened = mutableListOf<String>()
    private val gate = RaftNativePageOpenGate(topRoute = { stack.lastOrNull() })

    private fun data(vararg pairs: Pair<String, Any>) =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v.toString()) })

    private fun open(pageName: String, pageData: JsonObject, replace: Boolean = false): RaftNativePageOpenOutcome =
        gate.open(pageName, pageData, replace) {
            opened += "$pageName ${(pageData["id"] as? JsonPrimitive)?.content.orEmpty()}".trim()
            // The pushed page reaches the top later (an Activity is created after startActivity).
        }

    /** The pushed page is on top now (its Activity was created). */
    private fun created(pageName: String, pageData: JsonObject) {
        stack += gate.routeOf(pageName, pageData)
    }

    @Test fun `the route already on top is not pushed again`() {
        created("thread", data("id" to "t1"))
        assertEquals(RaftNativePageOpenOutcome.Dropped, open("thread", data("id" to "t1", "nowEpochMillis" to 5)))
        assertTrue(opened.isEmpty())
        assertFalse(gate.transitionRunning)
    }

    @Test fun `a repeat during the push is dropped even before the pushed page is on top`() {
        created("shell", data())
        assertEquals(RaftNativePageOpenOutcome.Opened, open("thread", data("id" to "t1")))
        assertEquals(RaftNativePageOpenOutcome.Dropped, open("thread", data("id" to "t1")))
        created("thread", data("id" to "t1"))
        assertEquals(RaftNativePageOpenOutcome.Dropped, open("thread", data("id" to "t1")))
        gate.transitionEnded()
        assertEquals(RaftNativePageOpenOutcome.Dropped, open("thread", data("id" to "t1")))
        assertEquals(listOf("thread t1"), opened)
    }

    @Test fun `a different route during the push is queued and the latest opens when it ends`() {
        created("shell", data())
        open("thread", data("id" to "t1"))
        assertEquals(RaftNativePageOpenOutcome.Queued, open("thread", data("id" to "t2")))
        assertEquals(RaftNativePageOpenOutcome.Queued, open("thread", data("id" to "t3")))
        assertEquals(RaftNativePageOpenOutcome.Dropped, open("thread", data("id" to "t3")))
        created("thread", data("id" to "t1"))
        assertEquals(listOf("thread t1"), opened)
        gate.transitionEnded()
        assertEquals(listOf("thread t1", "thread t3"), opened)
        // The queued open is a push of its own: its transition runs now.
        assertTrue(gate.transitionRunning)
    }
}
