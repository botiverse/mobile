package build.raft.designsystem

import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastTimeoutMillis
import build.raft.designsystem.components.raftToastSafeBottomPx
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

    @Test
    fun safeBottomNeverDropsBelowSystemSafeDrawingForHardwareKeyboard() {
        // iPad hardware keyboard shortcut bar: host IME override reads 0 while safeDrawing reports the bar.
        assertEquals(56f, raftToastSafeBottomPx(overridePx = 0f, safeDrawingBottomPx = 56f))
        assertEquals(56f, raftToastSafeBottomPx(overridePx = 20f, safeDrawingBottomPx = 56f))
        // Software keyboard animation: host IME override exceeds safeDrawing and leads.
        assertEquals(300f, raftToastSafeBottomPx(overridePx = 300f, safeDrawingBottomPx = 56f))
        // No override registered: falls back to safeDrawing.
        assertEquals(56f, raftToastSafeBottomPx(overridePx = null, safeDrawingBottomPx = 56f))
    }

    @Test
    fun footnotePreservesSeparateLineIdentityAndSurvivesHandOff() = runTest {
        val source = host(limit = 2)
        val target = host(limit = 2)
        val id1 = source.show("Beta wasn't connected", description = "Too many messages.", footnote = "Trace ID 62ac7fa7…")
        val id2 = source.show("Beta wasn't connected", description = "Too many messages.", footnote = "Trace ID 49a2bff6…")
        assertTrue(id2 != id1, "distinct trace footnotes must not collapse into one toast")
        assertEquals(listOf("Trace ID 62ac7fa7…", "Trace ID 49a2bff6…"), source.visible.map { it.footnote })

        source.handOffTo(target)
        assertEquals(emptyList(), source.visible)
        assertEquals(listOf("Trace ID 62ac7fa7…", "Trace ID 49a2bff6…"), target.visible.map { it.footnote })
    }
}
