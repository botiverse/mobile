package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * [RaftMinBuildGateController]: follows the gate flag's evaluations, asks the install channel only
 * below the threshold, and turns the gate off as soon as the flag does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftMinBuildGateControllerTest {
    private val server = ServerId("server-1")
    private val key = MOBILE_MIN_BUILD_ANDROID_V1_FEATURE_FLAG_KEY
    private val url = "https://example.test/raft.apk"

    private class FakeChannel(var answer: suspend () -> RaftInstallChannelLatest) : RaftInstallChannelUpdates {
        override val name: String = "fake"
        var calls = 0

        override suspend fun latest(): RaftInstallChannelLatest {
            calls += 1
            return answer()
        }

        override fun fallbackUpdateUrl(): String = "https://store.example.test/raft"
    }

    private fun TestScope.controller(
        channel: RaftInstallChannelUpdates?,
        currentBuild: Long? = 11_900_000L,
    ): RaftMinBuildGateController =
        RaftMinBuildGateController(
            // The test scope itself: advanceUntilIdle runs its children (not backgroundScope's).
            scope = this,
            flagKey = key,
            currentBuild = { currentBuild },
            channel = { channel },
            now = { testScheduler.currentTime },
        )

    @Test
    fun blocksWhenTheChannelConfirmsAndLiftsWhenTheFlagTurnsOff() = runTest {
        val channel = FakeChannel { RaftInstallChannelLatest.Available(12_600_000L, url) }
        val gate = controller(channel)

        gate.onEvaluation(server, key, enabled = true, variant = "12000000")
        // Not decided until the channel answers: no gate meanwhile.
        assertEquals(RaftMinBuildGateDecision.None, gate.decision.value)
        advanceUntilIdle()
        assertEquals(RaftMinBuildGateDecision.Block(12_000_000L, 12_600_000L, url), gate.decision.value)

        // Flag off (next read or push): the block goes at once, without asking the channel.
        gate.onEvaluation(server, key, enabled = false, variant = null)
        assertEquals(RaftMinBuildGateDecision.None, gate.decision.value)
        assertEquals(1, channel.calls)
    }

    @Test
    fun anUnansweringOrFailingChannelIsOnlyABanner() = runTest {
        listOf<suspend () -> RaftInstallChannelLatest>(
            { error("offline") },
            { awaitCancellation() }, // never answers: times out
            { RaftInstallChannelLatest.Unknown },
            { RaftInstallChannelLatest.UpToDate },
        ).forEachIndexed { index, answer ->
            val gate = controller(FakeChannel(answer))
            gate.onEvaluation(server, key, enabled = true, variant = "12000000")
            advanceUntilIdle()
            assertEquals(
                RaftMinBuildGateDecision.Banner(12_000_000L, "https://store.example.test/raft"),
                gate.decision.value,
                "case $index",
            )
        }
        // No channel at all (desktop, or a host that installed none).
        val gate = controller(channel = null)
        gate.onEvaluation(server, key, enabled = true, variant = "12000000")
        advanceUntilIdle()
        assertEquals(RaftMinBuildGateDecision.Banner(12_000_000L, null), gate.decision.value)
    }

    @Test
    fun atOrAboveTheThresholdOrWithAnUnknownBuildNeverAsksTheChannel() = runTest {
        listOf(12_000_000L, 12_600_000L, null, 1L).forEach { current ->
            val channel = FakeChannel { RaftInstallChannelLatest.Available(12_600_000L, url) }
            val gate = controller(channel, currentBuild = current)
            gate.onEvaluation(server, key, enabled = true, variant = "12000000")
            advanceUntilIdle()
            assertEquals(RaftMinBuildGateDecision.None, gate.decision.value, "current=$current")
            assertEquals(0, channel.calls, "current=$current")
        }
    }

    @Test
    fun otherKeysAreIgnoredAndSignOutClearsTheGate() = runTest {
        val channel = FakeChannel { RaftInstallChannelLatest.Available(12_600_000L, url) }
        val gate = controller(channel)
        gate.onEvaluation(server, MOBILE_MIN_BUILD_IOS_V1_FEATURE_FLAG_KEY, enabled = true, variant = "12000000")
        advanceUntilIdle()
        assertEquals(RaftMinBuildGateDecision.None, gate.decision.value)
        assertEquals(0, channel.calls)

        gate.onEvaluation(server, key, enabled = true, variant = "12000000")
        advanceUntilIdle()
        assertEquals(RaftMinBuildGateDecision.Block(12_000_000L, 12_600_000L, url), gate.decision.value)
        assertEquals(listOf(server), gate.serversToRefetch)

        gate.onSignedOut()
        assertEquals(RaftMinBuildGateDecision.None, gate.decision.value)
        assertEquals(emptyList(), gate.serversToRefetch)
        assertEquals(null, gate.effective)
    }

    @Test
    fun repeatedReadsReuseTheChannelAnswerUntilItAgesOrTheAppReturns() = runTest {
        val channel = FakeChannel { RaftInstallChannelLatest.UpToDate }
        val gate = controller(channel)
        gate.onEvaluation(server, key, enabled = true, variant = "12000000")
        advanceUntilIdle()
        assertEquals(1, channel.calls)

        // The periodic flag refresh: same threshold, answer still fresh.
        gate.onEvaluation(server, key, enabled = true, variant = "12000000")
        advanceUntilIdle()
        assertEquals(1, channel.calls)
        assertEquals(RaftMinBuildGateDecision.Banner(12_000_000L, "https://store.example.test/raft"), gate.decision.value)

        // A store release meanwhile: back in the foreground the channel is asked again, and the
        // banner stays up while it is (no flicker).
        val answer = CompletableDeferred<RaftInstallChannelLatest>()
        channel.answer = { answer.await() }
        gate.onForeground()
        runCurrent()
        assertEquals(2, channel.calls)
        assertEquals(RaftMinBuildGateDecision.Banner(12_000_000L, "https://store.example.test/raft"), gate.decision.value)
        answer.complete(RaftInstallChannelLatest.Available(12_600_000L, url))
        advanceUntilIdle()
        assertEquals(RaftMinBuildGateDecision.Block(12_000_000L, 12_600_000L, url), gate.decision.value)

        // After the cache period a plain read asks again.
        channel.answer = { RaftInstallChannelLatest.Available(12_600_000L, url) }
        testScheduler.advanceTimeBy(RAFT_MIN_BUILD_GATE_CHANNEL_CACHE_MILLIS + 1)
        gate.onEvaluation(server, key, enabled = true, variant = "12000000")
        advanceUntilIdle()
        assertEquals(3, channel.calls)

        // A new threshold is a new question, whatever the cache holds.
        gate.onEvaluation(server, key, enabled = true, variant = "12100000")
        advanceUntilIdle()
        assertEquals(4, channel.calls)
        assertEquals(RaftMinBuildGateDecision.Block(12_100_000L, 12_600_000L, url), gate.decision.value)
    }

    @Test
    fun theHighestServerThresholdWinsWhateverOrderTheReadingsArrive() = runTest {
        val serverA = ServerId("server-a")
        val serverB = ServerId("server-b")
        // A asks for 1.20.0, B for 1.22.0; the channel offers 1.26.0.
        val readings =
            listOf(
                Triple(serverA, true, "12000000"),
                Triple(serverB, true, "12200000"),
            )
        listOf(readings, readings.reversed()).forEachIndexed { order, sequence ->
            val gate = controller(FakeChannel { RaftInstallChannelLatest.Available(12_600_000L, url) })
            sequence.forEach { (serverId, enabled, variant) -> gate.onEvaluation(serverId, key, enabled, variant) }
            advanceUntilIdle()
            assertEquals(RaftMinBuildGateEffective(12_200_000L, "server-b"), gate.effective, "order $order")
            assertEquals(RaftMinBuildGateDecision.Block(12_200_000L, 12_600_000L, url), gate.decision.value, "order $order")

            // B turns its flag off: only B's reading goes; A's threshold is in force again.
            gate.onEvaluation(serverB, key, enabled = false, variant = null)
            advanceUntilIdle()
            assertEquals(RaftMinBuildGateEffective(12_000_000L, "server-a"), gate.effective, "order $order")
            assertEquals(RaftMinBuildGateDecision.Block(12_000_000L, 12_600_000L, url), gate.decision.value, "order $order")

            // An invalid variant on A clears A as well: no gate.
            gate.onEvaluation(serverA, key, enabled = true, variant = "1.20.0")
            advanceUntilIdle()
            assertEquals(null, gate.effective, "order $order")
            assertEquals(RaftMinBuildGateDecision.None, gate.decision.value, "order $order")
        }
    }

    @Test
    fun leavingAServerDropsOnlyItsReading() = runTest {
        val serverA = ServerId("server-a")
        val serverB = ServerId("server-b")
        val gate = controller(FakeChannel { RaftInstallChannelLatest.Available(12_600_000L, url) })
        gate.onEvaluation(serverA, key, enabled = true, variant = "12000000")
        gate.onEvaluation(serverB, key, enabled = true, variant = "12200000")
        advanceUntilIdle()
        assertEquals(RaftMinBuildGateEffective(12_200_000L, "server-b"), gate.effective)

        gate.onServerLeft(serverB)
        advanceUntilIdle()
        assertEquals(RaftMinBuildGateEffective(12_000_000L, "server-a"), gate.effective)
        assertEquals(RaftMinBuildGateDecision.Block(12_000_000L, 12_600_000L, url), gate.decision.value)
        assertEquals(listOf(serverA), gate.serversToRefetch)

        gate.onServerLeft(serverA)
        assertEquals(null, gate.effective)
        assertEquals(RaftMinBuildGateDecision.None, gate.decision.value)
    }
}
