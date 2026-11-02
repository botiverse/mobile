package build.raft.designsystem

import build.raft.designsystem.components.RaftToastActionTimeoutMillis
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.components.RaftToastTimeoutMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [RaftToastHostState] on virtual time: raft-ui's limit, timeouts, replace-not-queue, and the dedupe. */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftToastHostStateTest {
    private fun TestScope.host(limit: Int = 1): RaftToastHostState =
        RaftToastHostState(limit = limit, timeSource = testTimeSource).also { state ->
            backgroundScope.launch { state.runTimers() }
            runCurrent()
        }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun RaftToastHostState.titles(): List<String> = visible.map { it.title }

    @Test
    fun aToastClosesAfterRaftUiTimeout() = runTest {
        val state = host()
        state.show("Saved.")
        advance(RaftToastTimeoutMillis - 1)
        assertEquals(listOf("Saved."), state.titles())
        advance(1)
        assertEquals(emptyList(), state.titles())
    }

    @Test
    fun anActionOrAnErrorGetsTheActionTimeout() = runTest {
        val state = host(limit = 2)
        state.show("Message deleted.", actionLabel = "Undo", onAction = {})
        state.show("Couldn't send.", intent = RaftToastIntent.Error)
        assertEquals(listOf(RaftToastActionTimeoutMillis, RaftToastActionTimeoutMillis), state.visible.map { it.durationMillis })
        advance(RaftToastTimeoutMillis)
        assertEquals(listOf("Message deleted.", "Couldn't send."), state.titles())
        advance(RaftToastActionTimeoutMillis - RaftToastTimeoutMillis)
        assertEquals(emptyList(), state.titles())
    }

    @Test
    fun aNewToastReplacesTheOneOnScreenAndTheReplacedOneNeverComesBack() = runTest {
        val state = host()
        val first = state.show("Link copied.")
        advance(1_000)
        val second = state.show("Saved.")
        assertTrue(second != first)
        assertEquals(listOf("Saved."), state.titles())
        advance(RaftToastTimeoutMillis)
        assertEquals(emptyList(), state.titles())
        advance(RaftToastTimeoutMillis)
        assertEquals(emptyList(), state.titles(), "the replaced toast is closed, not queued")
    }

    @Test
    fun theSameMessageAgainRestartsTheNewestToastInsteadOfStacking() = runTest {
        val state = host(limit = 3)
        val id = state.show("Copied")
        advance(4_000)
        assertEquals(id, state.show("Copied"))
        assertEquals(listOf("Copied"), state.titles())
        advance(4_000)
        assertEquals(listOf("Copied"), state.titles(), "the timer started over at the repeat")
        advance(1_000)
        assertEquals(emptyList(), state.titles())

        // Only consecutive repeats fold: A, B, A stacks.
        state.show("A")
        state.show("B")
        state.show("A")
        assertEquals(listOf("A", "B", "A"), state.titles())
    }

    @Test
    fun stackedToastsCloseInDeadlineOrder() = runTest {
        val state = host(limit = 3)
        state.show("Undo me", actionLabel = "Undo", onAction = {}) // closes at 8.0s
        advance(1_000)
        state.show("First") // closes at 6.0s
        advance(1_500)
        state.show("Second") // closes at 7.5s
        advance(3_500) // 6.0s
        assertEquals(listOf("Undo me", "Second"), state.titles())
        advance(1_500) // 7.5s
        assertEquals(listOf("Undo me"), state.titles())
        advance(500) // 8.0s
        assertEquals(emptyList(), state.titles())
    }

    @Test
    fun pausedTimeDoesNotCount() = runTest {
        val state = host()
        state.show("Saved.")
        advance(1_000)
        state.setPaused(true)
        advance(60_000)
        assertEquals(listOf("Saved."), state.titles())
        state.setPaused(false)
        advance(RaftToastTimeoutMillis - 1_001)
        assertEquals(listOf("Saved."), state.titles())
        advance(1)
        assertEquals(emptyList(), state.titles())
    }

    @Test
    fun zeroDurationStaysUntilDismissed() = runTest {
        val state = host()
        val id = state.show("Uploading", durationMillis = 0)
        advance(60_000)
        assertEquals(listOf("Uploading"), state.titles())
        state.dismiss(id)
        assertEquals(emptyList(), state.titles())
    }
}
