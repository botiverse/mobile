package build.raft.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@OptIn(ExperimentalCoroutinesApi::class)
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
    fun aCancellationThrownByTheBlockReachesTheCaller() =
        runTest {
            var runs = 0
            val stopped =
                runCatching {
                    repeatWhileProcessForeground(
                        intervalMillis = 1_000L,
                        foreground = MutableStateFlow(true),
                        runImmediatelyOnStart = true,
                    ) {
                        runs += 1
                        if (runs == 2) throw kotlinx.coroutines.CancellationException("stop")
                    }
                }
            assertEquals(2, runs)
            assertEquals("stop", stopped.exceptionOrNull()?.message)
        }
}
