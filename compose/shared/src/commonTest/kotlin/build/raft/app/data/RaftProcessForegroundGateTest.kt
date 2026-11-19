package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Periodic work is quiet while the app is hidden (Hands a8cd6426: a cached process thawed by a push
 * must not wake a burst of timers while Android tries to freeze it again), and catches up once, not
 * once per missed interval, when the app comes back.
 */
class RaftProcessForegroundGateTest {
    private fun TestScope.startRepeating(
        foreground: MutableStateFlow<Boolean>,
        runs: MutableList<Long>,
        intervalMillis: Long = 1_000L,
        runImmediatelyOnStart: Boolean = false,
    ) {
        backgroundScope.launch {
            repeatWhileProcessForeground(
                intervalMillis = intervalMillis,
                foreground = foreground,
                runImmediatelyOnStart = runImmediatelyOnStart,
                nowMillis = { testScheduler.currentTime },
            ) { runs += testScheduler.currentTime }
        }
        runCurrent()
    }

    @Test
    fun inFrontItRunsEveryInterval() =
        runTest {
            val runs = mutableListOf<Long>()
            startRepeating(MutableStateFlow(true), runs)
            advanceTimeBy(3_001L)
            assertEquals(listOf(1_000L, 2_000L, 3_000L), runs)
        }

    @Test
    fun runImmediatelyOnStartRunsAtOnce() =
        runTest {
            val runs = mutableListOf<Long>()
            startRepeating(MutableStateFlow(true), runs, runImmediatelyOnStart = true)
            advanceTimeBy(1_001L)
            assertEquals(listOf(0L, 1_000L), runs)
        }

    @Test
    fun hiddenItRunsNothingAndBackInFrontItCatchesUpOnce() =
        runTest {
            val foreground = MutableStateFlow(true)
            val runs = mutableListOf<Long>()
            startRepeating(foreground, runs)
            advanceTimeBy(1_001L)
            assertEquals(listOf(1_000L), runs)

            foreground.value = false
            advanceTimeBy(60_000L)
            assertEquals(listOf(1_000L), runs, "nothing while hidden")

            foreground.value = true
            runCurrent()
            assertEquals(listOf(1_000L, 61_001L), runs, "one catch-up at once, not sixty")
            advanceTimeBy(1_001L)
            assertEquals(listOf(1_000L, 61_001L, 62_001L), runs)
        }

    @Test
    fun aShortHideFinishesTheIntervalUnderWayInsteadOfRunningAtOnce() =
        runTest {
            val foreground = MutableStateFlow(true)
            val runs = mutableListOf<Long>()
            startRepeating(foreground, runs, intervalMillis = 60_000L)
            advanceTimeBy(10_000L)
            foreground.value = false
            advanceTimeBy(5_000L)
            foreground.value = true
            runCurrent()
            assertEquals(emptyList(), runs, "not due yet")
            advanceTimeBy(45_001L)
            assertEquals(listOf(60_000L), runs)
        }

    @Test
    fun startedHiddenItWaitsForTheForeground() =
        runTest {
            val foreground = MutableStateFlow(false)
            val runs = mutableListOf<Long>()
            startRepeating(foreground, runs, runImmediatelyOnStart = true)
            advanceTimeBy(10_000L)
            assertEquals(emptyList(), runs)
            foreground.value = true
            runCurrent()
            assertEquals(listOf(10_000L), runs)
        }

    @Test
    fun featureFlagRefreshStopsWhileHidden() =
        runTest {
            val foreground = MutableStateFlow(true)
            var refreshes = 0
            backgroundScope.launch {
                refreshSelectedServerFeatureFlags(
                    intervalMillis = ServerFeatureFlagRefreshIntervalMillis,
                    foreground = foreground,
                    nowMillis = { testScheduler.currentTime },
                ) { refreshes += 1 }
            }
            runCurrent()
            assertEquals(1, refreshes, "a selection reads at once")
            foreground.value = false
            advanceTimeBy(ServerFeatureFlagRefreshIntervalMillis * 30)
            assertEquals(1, refreshes, "no reads while hidden")
            foreground.value = true
            runCurrent()
            assertEquals(2, refreshes, "one read on return")
        }

    @Test
    fun processVisibilityFeedsTheGate() {
        try {
            RaftStaleReadRecoverySignals.onProcessVisibility(false)
            assertEquals(false, RaftProcessForegroundGate.foreground.value)
            assertEquals(false, RaftStaleReadRecoverySignals.foreground.value)
        } finally {
            RaftStaleReadRecoverySignals.onProcessVisibility(true)
        }
        assertEquals(true, RaftProcessForegroundGate.foreground.value)
    }
}
