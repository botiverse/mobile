package build.raft.app.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RaftReconnectRefetchJitterTest {
    private val serverA = RaftReconnectSession("env", "user-1", "server-a")
    private val serverB = RaftReconnectSession("env", "user-1", "server-b")

    private class Recorder {
        val runs = mutableListOf<Pair<String, Long>>()
        val logs = mutableListOf<String>()
    }

    private fun TestScope.jitter(recorder: Recorder, seed: Int = 7) =
        RaftReconnectRefetchJitter(scope = backgroundScope, random = Random(seed), logger = { recorder.logs += it })

    /** The delay a jitter seeded with [seed] picks for its first wait. */
    private fun expectedDelay(seed: Int = 7): Long = reconnectRefetchJitterMillis(Random(seed))

    @Test
    fun jitterIsUniformWithinZeroToTwentySecondsBothEndsIncluded() {
        val random = Random(42)
        val samples = List(5_000) { reconnectRefetchJitterMillis(random) }
        assertTrue(samples.all { it in 0L..RECONNECT_REFETCH_JITTER_MAX_MILLIS }, "every wait is in 0..20 s")
        assertTrue(samples.any { it < 2_000L } && samples.any { it > 18_000L }, "the waits cover the window")
        assertEquals(0L, reconnectRefetchJitterMillis(random, maxMillis = 0L))
    }

    @Test
    fun firstConnectIsImmediateAndASecondConnectOfTheSameSessionWaitsTheJitter() = runTest {
        val recorder = Recorder()
        val policy = jitter(recorder)
        val delayMs = expectedDelay()

        val first = policy.onConnected(serverA, "server_members") { recorder.runs += "first" to currentTime }
        assertEquals(RaftReconnectRefetchDecision.Immediate, first)
        runCurrent()
        assertEquals(emptyList(), recorder.runs, "an immediate decision leaves the fetch to the caller")

        val second = policy.onConnected(serverA, "server_members") { recorder.runs += "reconnect" to currentTime }
        assertEquals(RaftReconnectRefetchDecision.Delayed, second)
        assertTrue(delayMs in 0L..RECONNECT_REFETCH_JITTER_MAX_MILLIS)
        if (delayMs > 0L) {
            advanceTimeBy(delayMs)
            assertEquals(emptyList(), recorder.runs, "nothing runs before the delay ends")
        }
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        assertEquals(listOf("reconnect" to delayMs), recorder.runs)
        assertTrue(
            recorder.logs.any { it.startsWith("reconnect_refetch_jitter decision=delayed") && "delayMs=$delayMs" in it },
            "one diagnostics line names the delay: ${recorder.logs}",
        )
    }

    @Test
    fun reconnectsDuringTheWaitCoalesceIntoOneRefetchPerSourceAndServer() = runTest {
        val recorder = Recorder()
        val policy = jitter(recorder)
        val delayMs = expectedDelay()
        policy.onConnected(serverA, "server_members") {}
        policy.onConnected(serverB, "server_members") {}

        assertEquals(
            RaftReconnectRefetchDecision.Delayed,
            policy.onConnected(serverA, "server_members") { recorder.runs += "a-old" to currentTime },
        )
        assertEquals(
            RaftReconnectRefetchDecision.Coalesced,
            policy.onConnected(serverA, "server_members") { recorder.runs += "a-new" to currentTime },
        )
        assertEquals(
            RaftReconnectRefetchDecision.Coalesced,
            policy.onConnected(serverB, "server_members") { recorder.runs += "b" to currentTime },
        )
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)

        assertEquals(
            listOf("a-new" to delayMs, "b" to delayMs),
            recorder.runs,
            "one wait per client; the newest refetch per server runs once, nothing stacks",
        )
        assertEquals(1, recorder.logs.count { it.startsWith("reconnect_refetch_jitter decision=delayed") })
    }

    @Test
    fun aServerSwitchRunsThatServersWaitingRefetchNowAndLeavesTheOthersWaiting() = runTest {
        val recorder = Recorder()
        val policy = jitter(recorder, seed = 3)
        val delayMs = expectedDelay(seed = 3)
        policy.onConnected(serverA, "server_members") {}
        policy.onConnected(serverB, "server_members") {}
        policy.onConnected(serverA, "server_members") { recorder.runs += "a" to currentTime }
        policy.onConnected(serverB, "server_members") { recorder.runs += "b" to currentTime }

        policy.flushServer("server-b", reason = "server_selected")
        runCurrent()
        assertEquals(listOf("b" to 0L), recorder.runs, "the selected server fetches at once")

        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        assertEquals(listOf("b" to 0L, "a" to delayMs), recorder.runs, "the other server keeps its wait")
    }

    @Test
    fun signOutCancelsTheWaitAndTheNextConnectIsAFirstOne() = runTest {
        val recorder = Recorder()
        val policy = jitter(recorder)
        policy.onConnected(serverA, "server_members") {}
        policy.onConnected(serverA, "server_members") { recorder.runs += "stale" to currentTime }

        policy.reset(reason = "signed_out")
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        assertEquals(emptyList(), recorder.runs, "nothing waiting runs for the old binding")

        assertEquals(
            RaftReconnectRefetchDecision.Immediate,
            policy.onConnected(serverA, "server_members") {},
            "the first connect after sign-in fetches at once",
        )
    }

    @Test
    fun aLostServerDropsItsWaitingRefetch() = runTest {
        val recorder = Recorder()
        val policy = jitter(recorder)
        policy.onConnected(serverA, "server_members") {}
        policy.onConnected(serverB, "server_members") {}
        policy.onConnected(serverA, "server_members") { recorder.runs += "a" to currentTime }
        policy.onConnected(serverB, "server_members") { recorder.runs += "b" to currentTime }

        policy.dropServer("server-a", reason = "server_access_lost")
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)

        assertEquals(listOf("b"), recorder.runs.map { it.first })
        assertEquals(
            RaftReconnectRefetchDecision.Immediate,
            policy.onConnected(serverA, "server_members") {},
            "a server joined again is a first connect",
        )
    }

    @Test
    fun aNewServerOrAccountIsAFirstConnect() = runTest {
        val recorder = Recorder()
        val policy = jitter(recorder)
        policy.onConnected(serverA, "server_members") {}

        assertEquals(RaftReconnectRefetchDecision.Immediate, policy.onConnected(serverB, "server_members") {})
        assertEquals(
            RaftReconnectRefetchDecision.Immediate,
            policy.onConnected(serverA.copy(principalId = "user-2"), "server_members") {},
        )
    }

    /**
     * Hands 46b6cebe: the user went back to a server whose socket stayed connected while another
     * server was in front. No connect follows, so nothing re-read its agents and the lists kept an
     * old "Online" the profile's fresh read contradicted. The switch now reads them itself.
     */
    @Test
    fun switchBackToAServerWhoseSocketStayedConnectedReadsItsLiveStatus() = runTest {
        val recorder = Recorder()
        val policy = jitter(recorder)
        policy.onConnected(serverA, "server_members") {}

        policy.onServerSelected(serverA.serverId, reason = "server_selected") { recorder.runs += "catch_up" to currentTime }
        advanceTimeBy(SERVER_SWITCH_CATCH_UP_DELAY_MILLIS - 1L)
        assertEquals(emptyList(), recorder.runs, "the switch first gives the socket time to connect")
        advanceTimeBy(2L)

        assertEquals(listOf("catch_up" to SERVER_SWITCH_CATCH_UP_DELAY_MILLIS), recorder.runs)
        assertTrue(
            recorder.logs.any {
                it.startsWith("reconnect_refetch_jitter_switch_catch_up serverId=server-a") && "cause=no_connect_after_switch" in it
            },
            "the catch-up is logged: ${recorder.logs}",
        )
    }

    @Test
    fun aConnectAfterTheSwitchRefetchesInsteadOfTheCatchUp() = runTest {
        val recorder = Recorder()
        val policy = jitter(recorder)
        policy.onConnected(serverA, "server_members") {}

        policy.onServerSelected(serverA.serverId, reason = "server_selected") { recorder.runs += "catch_up" to currentTime }
        assertEquals(
            RaftReconnectRefetchDecision.ImmediateAfterSwitch,
            policy.onConnected(serverA, "server_members") {},
        )
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)

        assertEquals(emptyList(), recorder.runs, "the connect's own refetch covers the switch: one read, not two")
    }

    @Test
    fun theFirstSelectionAtLaunchLeavesTheReadToTheFirstConnect() = runTest {
        val recorder = Recorder()
        val policy = jitter(recorder)

        policy.onServerSelected(serverA.serverId, reason = "server_selected") { recorder.runs += "catch_up" to currentTime }
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)

        assertEquals(emptyList(), recorder.runs, "a server never connected yet reads everything on its first connect")
    }

    @Test
    fun switchingAgainBeforeTheCatchUpRunsReadsOnceAndAResetCancelsIt() = runTest {
        val recorder = Recorder()
        val policy = jitter(recorder)
        policy.onConnected(serverA, "server_members") {}
        policy.onConnected(serverB, "server_members") {}

        policy.onServerSelected(serverA.serverId, reason = "server_selected") { recorder.runs += "a-old" to currentTime }
        advanceTimeBy(1_000L)
        policy.onServerSelected(serverA.serverId, reason = "server_selected") { recorder.runs += "a-new" to currentTime }
        policy.onServerSelected(serverB.serverId, reason = "server_selected") { recorder.runs += "b" to currentTime }
        policy.reset("sign_out")
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        assertEquals(emptyList(), recorder.runs, "a reset drops every waiting catch-up")

        policy.onConnected(serverA, "server_members") {}
        policy.onServerSelected(serverA.serverId, reason = "server_selected") { recorder.runs += "a-1" to currentTime }
        advanceTimeBy(1_000L)
        policy.onServerSelected(serverA.serverId, reason = "server_selected") { recorder.runs += "a-2" to currentTime }
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        assertEquals(listOf("a-2"), recorder.runs.map { it.first }, "the newest selection's catch-up runs, once")
    }
}
