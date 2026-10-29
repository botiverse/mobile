package build.raft.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentActivitySubscriptionsTest {
    private data class Emit(val serverId: String, val subscribe: Boolean, val agentIds: List<String>)

    private val delayMs = AgentActivitySubscriptions.DefaultFlushDelayMillis
    private val emits = mutableListOf<Emit>()

    private fun TestScope.subscriptions() =
        AgentActivitySubscriptions(scope = backgroundScope, send = { server, subscribe, ids -> emits += Emit(server, subscribe, ids) })

    private fun TestScope.flush() {
        advanceTimeBy(delayMs)
        runCurrent()
    }

    @Test
    fun leasesAreRefCountedAndOneDiffIsSentAfterTheDelay() = runTest {
        val subscriptions = subscriptions()
        val dm = subscriptions.acquire("s1", listOf("a1"))
        val members = subscriptions.acquire("s1", listOf("a1", "a2", " ", "a2"))
        advanceTimeBy(delayMs - 1)
        runCurrent()
        assertTrue(emits.isEmpty(), "nothing before the delay: $emits")
        runCurrent()
        flush()
        assertEquals(listOf(Emit("s1", true, listOf("a1", "a2"))), emits)

        // a1 is still held by the DM lease; only a2 goes.
        members.release()
        members.release()
        flush()
        assertEquals(Emit("s1", false, listOf("a2")), emits.last())
        assertEquals(2, emits.size)

        dm.release()
        flush()
        assertEquals(Emit("s1", false, listOf("a1")), emits.last())
        assertEquals(3, emits.size)
    }

    @Test
    fun aPageReplacingAnotherWithTheSameAgentSendsNothing() = runTest {
        val subscriptions = subscriptions()
        subscriptions.acquire("s1", listOf("a1")).let { first ->
            flush()
            emits.clear()
            // DM -> agent detail: the next lease is taken before the delay ends.
            val next = subscriptions.acquire("s1", listOf("a1"))
            first.release()
            flush()
            assertTrue(emits.isEmpty(), "no unsubscribe/subscribe churn: $emits")
            next.release()
        }
        flush()
        assertEquals(listOf(Emit("s1", false, listOf("a1"))), emits)
    }

    @Test
    fun emitsAreBatchedAtTheServerLimitPerServer() = runTest {
        val subscriptions = subscriptions()
        val ids = (1..250).map { "agent-$it" }
        subscriptions.acquire("s1", ids.take(200))
        subscriptions.acquire("s1", ids.drop(150))
        subscriptions.acquire("s2", listOf("b1"))
        flush()

        val s1 = emits.filter { it.serverId == "s1" }
        assertEquals(listOf(200, 50), s1.map { it.agentIds.size })
        assertEquals(ids.toSet(), s1.flatMap { it.agentIds }.toSet())
        assertEquals(listOf(Emit("s2", true, listOf("b1"))), emits.filter { it.serverId == "s2" })
        // One lease names at most 200 agents.
        subscriptions.acquire("s3", ids)
        flush()
        assertEquals(200, emits.single { it.serverId == "s3" }.agentIds.size)
    }

    @Test
    fun aReconnectSubscribesTheWholeWantedSetAgainAndResetsTheConfirmedSet() = runTest {
        val subscriptions = subscriptions()
        subscriptions.acquire("s1", listOf("a1", "a2"))
        flush()
        subscriptions.onSubscribed("s1", listOf("a1", "a2"))
        assertEquals(mapOf("s1" to setOf("a1", "a2")), subscriptions.confirmed.value)
        emits.clear()

        subscriptions.onConnected("s1")
        assertEquals(listOf(Emit("s1", true, listOf("a1", "a2"))), emits)
        assertEquals(emptyMap(), subscriptions.confirmed.value)

        // A reconnect also covers a diff still waiting for its delay.
        val more = subscriptions.acquire("s1", listOf("a3"))
        subscriptions.onConnected("s1")
        flush()
        assertEquals(Emit("s1", true, listOf("a1", "a2", "a3")), emits.last())
        assertEquals(2, emits.size)
        more.release()

        // A server nobody watches gets nothing.
        subscriptions.onConnected("s9")
        assertTrue(emits.none { it.serverId == "s9" })
    }

    @Test
    fun theConfirmedSetIsWhatTheServerJoinedAndStillWanted() = runTest {
        val subscriptions = subscriptions()
        val lease = subscriptions.acquire("s1", listOf("a1", "denied"))
        flush()
        // The server leaves out an agent it will not show this member.
        subscriptions.onSubscribed("s1", listOf("a1"))
        assertEquals(mapOf("s1" to setOf("a1")), subscriptions.confirmed.value)
        // An id this socket never asked for is ignored.
        subscriptions.onSubscribed("s1", listOf("other"))
        assertEquals(mapOf("s1" to setOf("a1")), subscriptions.confirmed.value)

        lease.release()
        flush()
        assertEquals(Emit("s1", false, listOf("a1", "denied")), emits.last())
        assertEquals(emptyMap(), subscriptions.confirmed.value)
        // A late answer to the released subscribe does not bring it back.
        subscriptions.onSubscribed("s1", listOf("a1"))
        assertEquals(emptyMap(), subscriptions.confirmed.value)
    }
}
