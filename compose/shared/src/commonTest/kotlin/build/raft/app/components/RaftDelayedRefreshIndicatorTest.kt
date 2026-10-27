package build.raft.app.components

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
 * artin 10-27: the Activity header's silent-refresh indicator shows only for a refresh that
 * outlasts [RaftRefreshIndicatorShowDelayMillis] and then stays at least
 * [RaftRefreshIndicatorMinVisibleMillis], so a quick reload never flashes it. Virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftDelayedRefreshIndicatorTest {
    /** (virtual ms, visible) for each change the indicator makes. */
    private fun TestScope.track(active: MutableStateFlow<Boolean>): MutableList<Pair<Long, Boolean>> {
        val changes = mutableListOf<Pair<Long, Boolean>>()
        backgroundScope.launch {
            active.raftDelayedIndicatorVisibility(timeSource = testScheduler.timeSource).collect { visible ->
                changes += testScheduler.currentTime to visible
            }
        }
        runCurrent()
        return changes
    }

    @Test
    fun quickSilentRefreshNeverShows() = runTest {
        val active = MutableStateFlow(false)
        val changes = track(active)

        active.value = true
        advanceTimeBy(RaftRefreshIndicatorShowDelayMillis - 1)
        active.value = false
        advanceTimeBy(5_000L)

        assertEquals(listOf(0L to false), changes)
    }

    @Test
    fun longSilentRefreshShowsAfterTheDelayAndHoldsTheMinimum() = runTest {
        val active = MutableStateFlow(false)
        val changes = track(active)

        active.value = true
        advanceTimeBy(400L)
        active.value = false
        advanceTimeBy(5_000L)

        val shownAt = RaftRefreshIndicatorShowDelayMillis
        assertEquals(
            listOf(0L to false, shownAt to true, shownAt + RaftRefreshIndicatorMinVisibleMillis to false),
            changes
        )
    }

    @Test
    fun refreshOutlastingTheMinimumHidesWhenItEnds() = runTest {
        val active = MutableStateFlow(false)
        val changes = track(active)

        active.value = true
        advanceTimeBy(2_000L)
        active.value = false
        runCurrent()

        assertEquals(listOf(0L to false, RaftRefreshIndicatorShowDelayMillis to true, 2_000L to false), changes)
    }

    @Test
    fun refreshResumingDuringTheHoldStaysShownWithoutANewDelay() = runTest {
        val active = MutableStateFlow(false)
        val changes = track(active)

        active.value = true
        advanceTimeBy(400L)
        active.value = false
        advanceTimeBy(100L)
        active.value = true
        advanceTimeBy(1_000L)
        active.value = false
        runCurrent()

        assertEquals(listOf(0L to false, RaftRefreshIndicatorShowDelayMillis to true, 1_500L to false), changes)
    }
}
