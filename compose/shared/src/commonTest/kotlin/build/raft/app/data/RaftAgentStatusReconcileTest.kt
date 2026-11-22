package build.raft.app.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Hands c76f162d / 46b6cebe: a list kept an agent Online while its profile (a fresh `GET /agents/:id`)
 * showed it working, because nothing read `/agents` again while the app stayed in front on one server.
 * The web reads agents and machines every 10 minutes while visible (`planStatusReconcile`).
 */
class RaftAgentStatusReconcileTest {
    private val interval = AGENT_STATUS_RECONCILE_INTERVAL_MILLIS

    private class Recorder {
        val reads = mutableListOf<Pair<String, Long>>()
        val logs = mutableListOf<String>()
    }

    private fun TestScope.reconcile(
        recorder: Recorder,
        foreground: MutableStateFlow<Boolean>,
        servers: () -> Collection<String> = { listOf("server-a") },
        failing: Set<String> = emptySet(),
    ) = RaftAgentStatusReconcile(
        scope = backgroundScope,
        foreground = foreground,
        serverIds = servers,
        reconcile = { serverId ->
            recorder.reads += serverId to currentTime
            if (serverId in failing) error("network down")
        },
        logger = { recorder.logs += it },
    )

    @Test
    fun whileInFrontEachIntervalReadsTheServersOnScreen() = runTest {
        val recorder = Recorder()
        reconcile(recorder, MutableStateFlow(true), servers = { listOf("server-a", " server-b ", "server-a", "") }).start()

        advanceTimeBy(interval - 1)
        runCurrent()
        assertEquals(emptyList(), recorder.reads, "nothing reads before the first interval ends")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("server-a" to interval, "server-b" to interval), recorder.reads, "each server once, trimmed")

        advanceTimeBy(interval)
        runCurrent()
        assertEquals(4, recorder.reads.size, "and again every interval: ${recorder.reads}")
        assertTrue(recorder.logs.any { it.startsWith("agent_status_reconcile serverId=server-a") }, "${recorder.logs}")
    }

    @Test
    fun noServerOnScreenReadsNothingAndAFailedReadKeepsTheClockRunning() = runTest {
        val recorder = Recorder()
        var servers = emptyList<String>()
        reconcile(recorder, MutableStateFlow(true), servers = { servers }, failing = setOf("server-a")).start()

        advanceTimeBy(interval)
        runCurrent()
        assertEquals(emptyList(), recorder.reads)
        assertTrue(recorder.logs.any { it == "agent_status_reconcile_skip cause=no_connected_server" }, "${recorder.logs}")

        servers = listOf("server-a")
        advanceTimeBy(interval)
        runCurrent()
        advanceTimeBy(interval)
        runCurrent()
        assertEquals(listOf("server-a" to interval * 2, "server-a" to interval * 3), recorder.reads)
        assertTrue(recorder.logs.any { it.startsWith("agent_status_reconcile_failed serverId=server-a") }, "${recorder.logs}")
    }

    @Test
    fun onlyAServerWhoseSocketConnectedCountsAsConnected() = runTest {
        val jitter = RaftReconnectRefetchJitter(scope = backgroundScope, random = Random(7))
        assertFalse(jitter.hasConnected("server-a"), "a socket that never connected reads on its first connect")

        jitter.onConnected(RaftReconnectSession("env", "user-1", "server-a"), "server_members") {}
        assertTrue(jitter.hasConnected(" server-a "))
        assertFalse(jitter.hasConnected("server-b"))

        jitter.dropServer("server-a", reason = "server_access_lost")
        assertFalse(jitter.hasConnected("server-a"), "a dropped server is not read")
        jitter.onConnected(RaftReconnectSession("env", "user-1", "server-b"), "server_members") {}
        jitter.reset(reason = "signed_out")
        assertFalse(jitter.hasConnected("server-b"), "sign-out forgets every socket")
    }
}
