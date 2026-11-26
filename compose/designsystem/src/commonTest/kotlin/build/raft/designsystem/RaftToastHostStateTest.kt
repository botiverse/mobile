package build.raft.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastTimeoutMillis
import build.raft.designsystem.components.raftToastSafeBottomPx
import build.raft.designsystem.components.raftToastTransformedBounds
import build.raft.designsystem.components.raftToastUnionBounds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun transformedBoundsIncludeSlideInScaleAndSwipeTranslation() {
        val base = Rect(left = 100f, top = 800f, right = 400f, bottom = 900f)
        // Settled toast with no drag keeps its exact base rect.
        assertEquals(base, raftToastTransformedBounds(base, entryProgress = 1f, swipeOffset = Offset.Zero))

        // Slide-in start (entryProgress = 0): scale 0.98 around center + 120% height downward shift.
        val entryStart = raftToastTransformedBounds(base, entryProgress = 0f, swipeOffset = Offset.Zero)
        assertEquals(103f, entryStart.left, absoluteTolerance = 0.01f)
        assertEquals(921f, entryStart.top, absoluteTolerance = 0.01f)
        assertEquals(397f, entryStart.right, absoluteTolerance = 0.01f)
        assertEquals(1019f, entryStart.bottom, absoluteTolerance = 0.01f)

        // Drag-dismiss offset shifts all four edges.
        val dragged = raftToastTransformedBounds(base, entryProgress = 1f, swipeOffset = Offset(24f, 18f))
        assertEquals(Rect(124f, 818f, 424f, 918f), dragged)
    }

    @Test
    fun unionBoundsCoversAllNonEmptyCardsAndReturnsNullWhenEmpty() {
        assertEquals(null, raftToastUnionBounds(emptyList()))
        assertEquals(null, raftToastUnionBounds(listOf(Rect.Zero)))
        val first = Rect(left = 40f, top = 700f, right = 340f, bottom = 780f)
        val second = Rect(left = 20f, top = 788f, right = 360f, bottom = 860f)
        assertEquals(Rect(left = 20f, top = 700f, right = 360f, bottom = 860f), raftToastUnionBounds(listOf(first, second)))
    }

    @Test
    fun activeHostFollowsLifoRegistrationAndHandsBackOnUnregister() {
        val state = RaftToastHostState()
        val activity = Any()
        val dialog1 = Any()
        val dialog2 = Any()

        // Empty-list fallback: before DisposableEffect commits, a single host is active.
        assertTrue(state.isActiveHost(activity))

        // Activity registers at base layer 0.
        state.registerHost(activity, layer = 0)
        assertTrue(state.isActiveHost(activity))
        assertFalse(state.isActiveHost(dialog1))

        // First dialog opens (layer 1) -> takes over from Activity.
        state.registerHost(dialog1, layer = 1)
        assertFalse(state.isActiveHost(activity))
        assertTrue(state.isActiveHost(dialog1))

        // Second sibling dialog opens (layer 1) -> topmost in same layer wins (LIFO).
        state.registerHost(dialog2, layer = 1)
        assertFalse(state.isActiveHost(activity))
        assertFalse(state.isActiveHost(dialog1))
        assertTrue(state.isActiveHost(dialog2))

        // Duplicate registration of an existing token must not duplicate or reorder it.
        state.registerHost(dialog1, layer = 1)
        assertTrue(state.isActiveHost(dialog2))

        // Closing top dialog hands back to dialog1, then closing dialog1 hands back to activity.
        state.unregisterHost(dialog2)
        assertTrue(state.isActiveHost(dialog1))
        assertFalse(state.isActiveHost(activity))

        state.unregisterHost(dialog1)
        assertTrue(state.isActiveHost(activity))

        // Unregistering the last host restores the empty-list fallback.
        state.unregisterHost(activity)
        assertTrue(state.isActiveHost(activity))
    }

    @Test
    fun higherLayerDialogBeatsRootHostEvenWhenRegisteredFirst() {
        val state = RaftToastHostState()
        val activity = Any()
        val outerDialog = Any()
        val nestedDialog = Any()

        // Frame 0 / recreation order where dialog effects commit ahead of the root host.
        state.registerHost(nestedDialog, layer = 2)
        state.registerHost(outerDialog, layer = 1)
        state.registerHost(activity, layer = 0)

        assertTrue(state.isActiveHost(nestedDialog))
        assertFalse(state.isActiveHost(outerDialog))
        assertFalse(state.isActiveHost(activity))

        // Unregistering middle dialog keeps topmost nested dialog active.
        state.unregisterHost(outerDialog)
        assertTrue(state.isActiveHost(nestedDialog))
        assertFalse(state.isActiveHost(activity))

        // Closing nested dialog falls back to root activity host.
        state.unregisterHost(nestedDialog)
        assertTrue(state.isActiveHost(activity))
    }
}
