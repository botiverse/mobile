package build.raft.app.compat.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

// Robolectric: the Compose runtime traces through android.os.Trace.
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RaftPageActiveBackHandlerTest {
    @Test
    fun backHandlersOfAnInactiveKeptAlivePageAreDisabled() = runTest {
        var tasksTabActive by mutableStateOf(true)
        val seen = mutableListOf<Boolean>()
        compose(
            content = {
                // Shell page (active) hosting the kept-alive Tasks tab page.
                RaftProvidePageActive(active = true) {
                    RaftProvidePageActive(active = tasksTabActive) {
                        seen += raftBackHandlerEnabled(enabled = true)
                    }
                }
            },
        ) { drain ->
            assertEquals(listOf(true), seen)
            // Switch to another tab: the Tasks page stays composed but inactive.
            tasksTabActive = false
            drain()
            assertEquals(listOf(true, false), seen)
            tasksTabActive = true
            drain()
            assertEquals(listOf(true, false, true), seen)
        }
    }

    @Test
    fun inactiveAncestorDisablesNestedPagesAndCallerFlagStillApplies() = runTest {
        val seen = mutableListOf<Pair<String, Boolean>>()
        compose(
            content = {
                RaftProvidePageActive(active = false) {
                    RaftProvidePageActive(active = true) {
                        seen += "nested" to raftBackHandlerEnabled(enabled = true)
                    }
                }
                RaftProvidePageActive(active = true) {
                    seen += "callerDisabled" to raftBackHandlerEnabled(enabled = false)
                }
                seen += "outsideAnyPage" to raftBackHandlerEnabled(enabled = true)
            },
        ) {}
        assertEquals(
            listOf("nested" to false, "callerDisabled" to false, "outsideAnyPage" to true),
            seen,
        )
    }

    private suspend fun TestScope.compose(
        content: @Composable () -> Unit,
        block: suspend (drain: () -> Unit) -> Unit,
    ) {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(UnitApplier(), recomposer)
        val recomposerJob = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        try {
            composition.setContent(content)
            advanceUntilIdle()
            var frameTimeNanos = 0L
            block {
                Snapshot.sendApplyNotifications()
                advanceUntilIdle()
                frameTimeNanos += 16_000_000L
                frameClock.sendFrame(frameTimeNanos)
                advanceUntilIdle()
            }
        } finally {
            composition.dispose()
            recomposer.close()
            recomposerJob.join()
        }
    }

    private class UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit

        override fun insertTopDown(index: Int, instance: Unit) = Unit

        override fun move(from: Int, to: Int, count: Int) = Unit

        override fun onClear() = Unit

        override fun remove(index: Int, count: Int) = Unit
    }
}
