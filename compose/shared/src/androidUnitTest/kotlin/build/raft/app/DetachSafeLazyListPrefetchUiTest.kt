package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import build.raft.app.thread.DetachSafeLazyListPrefetchStrategy
import build.raft.app.thread.detachSafeLazyListPrefetch
import build.raft.app.thread.rememberDetachSafeLazyListState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hands 9e3ca082: the thread message list's [LazyListState] outlives its LazyColumn node; a pending
 * prefetch of a node that left its owner was cancelled by a later scroll and crashed in
 * `requireOwner` (`LayoutNode should be attached to an owner`). The thread list now uses
 * [rememberDetachSafeLazyListState] + [detachSafeLazyListPrefetch] (ThreadMessagesListContent).
 *
 * Each case scrolls a real LazyColumn under a hoisted guarded state so the default strategy holds a
 * prefetch handle, takes that LazyColumn's node away from its owner, and scrolls the state in both
 * directions (a direction change is what makes the default strategy cancel its handle).
 *
 * Test boundary: this proves the guard drops the strategy's handle whenever the list's node detaches,
 * both when the LazyColumn leaves composition and when it is moved (detached without release, the
 * case that keeps the precomposed slot the crash disposes), and that the state keeps scrolling. The
 * device crash itself is not reproduced: in Compose UI 1.12.1 a released or deactivated
 * SubcomposeLayout clears its precomposed slots, so a plain Robolectric recreate cannot reach
 * `requireOwner`, and the device's detach-without-release window is not known to be constructible here.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetachSafeLazyListPrefetchUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Probe {
        val prefetch = DetachSafeLazyListPrefetchStrategy()
        var state: LazyListState? = null
        var generation by mutableStateOf(0)
        var moved by mutableStateOf(false)
    }

    private fun ComposeUiTest.scrollForwardAndSettle(state: LazyListState) {
        runOnIdle { state.dispatchRawDelta(250f) }
        waitForIdle()
        mainClock.advanceTimeBy(PrefetchSettleMillis)
        waitForIdle()
    }

    private fun ComposeUiTest.assertScrollsBothWays(state: LazyListState) {
        val before = runOnIdle { state.firstVisibleItemIndex }
        runOnIdle { state.dispatchRawDelta(-120f) }
        waitForIdle()
        runOnIdle { state.dispatchRawDelta(320f) }
        waitForIdle()
        val after = runOnIdle { state.firstVisibleItemIndex }
        assertTrue(after > before, "the state stopped scrolling after its LazyColumn changed: $before -> $after")
    }

    @Test
    fun aRecreatedListDropsThePrefetchOfTheOneThatLeft() =
        runRobolectricComposeUiTest {
            val probe = Probe()
            setContent {
                val state = rememberDetachSafeLazyListState(probe.prefetch)
                probe.state = state
                Box(Modifier.height(400.dp)) {
                    key(probe.generation) {
                        LazyColumn(state = state, modifier = Modifier.fillMaxSize().detachSafeLazyListPrefetch(probe.prefetch)) {
                            items(Rows) { Box(Modifier.fillMaxWidth().height(100.dp)) }
                        }
                    }
                }
            }
            val state = assertNotNull(probe.state)
            scrollForwardAndSettle(state)
            assertEquals(0, probe.prefetch.droppedLayouts, "nothing detached yet")

            runOnIdle { probe.generation += 1 }
            waitForIdle()

            assertEquals(1, probe.prefetch.droppedLayouts, "the LazyColumn that left composition kept its prefetch")
            assertScrollsBothWays(state)
        }

    @Test
    fun aMovedListDropsThePrefetchItHeldWhileDetached() =
        runRobolectricComposeUiTest {
            val probe = Probe()
            setContent {
                val state = rememberDetachSafeLazyListState(probe.prefetch)
                probe.state = state
                val list =
                    remember {
                        movableContentOf {
                            LazyColumn(state = state, modifier = Modifier.fillMaxSize().detachSafeLazyListPrefetch(probe.prefetch)) {
                                items(Rows) { Box(Modifier.fillMaxWidth().height(100.dp)) }
                            }
                        }
                    }
                Column {
                    Box(Modifier.height(400.dp)) { if (!probe.moved) list() }
                    Box(Modifier.height(400.dp)) { if (probe.moved) list() }
                }
            }
            val state = assertNotNull(probe.state)
            scrollForwardAndSettle(state)
            assertEquals(0, probe.prefetch.droppedLayouts, "nothing detached yet")

            runOnIdle { probe.moved = true }
            waitForIdle()

            assertTrue(probe.prefetch.droppedLayouts >= 1, "the moved LazyColumn's node detached without dropping its prefetch")
            assertScrollsBothWays(state)
        }

    private companion object {
        const val Rows = 100
        const val PrefetchSettleMillis = 200L
    }
}
