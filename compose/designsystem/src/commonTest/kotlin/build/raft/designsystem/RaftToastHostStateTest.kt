package build.raft.designsystem

import androidx.compose.ui.unit.Density
import build.raft.designsystem.components.RaftToastActionTimeoutMillis
import build.raft.designsystem.components.RaftToastAvoidance
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
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
import kotlin.test.assertNull
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

    @Test
    fun handOffPreservesRemainingTimeoutAppearAtOnceAndHeldPageHeight() = runTest {
        val from = host(limit = 2)
        val to = host(limit = 2)
        from.show("Saved.")
        from.putAvoidance("composer", RaftToastAvoidance.BottomBand(120f))
        assertEquals(120f, from.resolveBottomClearancePx(hostBottomPx = 800f, safeBottomPx = 24f))
        advance(3_000)

        var heldAtFirstEmission = -1f
        val observer =
            backgroundScope.launch {
                to.toasts.collect { list ->
                    if (list.isNotEmpty() && heldAtFirstEmission < 0f) {
                        heldAtFirstEmission = to.currentHeldPageHeightPx
                    }
                }
            }
        runCurrent()

        from.handOffTo(to)
        runCurrent()
        observer.cancel()

        assertEquals(emptyList(), from.titles())
        assertEquals(0f, from.currentHeldPageHeightPx)
        assertEquals(listOf("Saved."), to.titles())
        assertTrue(to.visible.single().appearAtOnce, "handed-off toast skips entry slide")
        assertEquals(120f, heldAtFirstEmission, "held height is visible before toasts flow emits")
        assertEquals(120f, to.currentHeldPageHeightPx, "handed-off toast keeps held height")
        assertEquals(120f, to.resolveBottomClearancePx(hostBottomPx = 800f, safeBottomPx = 24f))

        advance(RaftToastTimeoutMillis - 3_001)
        assertEquals(listOf("Saved."), to.titles())
        advance(1)
        assertEquals(emptyList(), to.titles(), "handed-off toast expires on its original remaining timeout")
        assertEquals(0f, to.currentHeldPageHeightPx, "held height resets once the stack empties")
    }

    @Test
    fun handOffHandlesPausedExpiredAndZeroDurationToasts() = runTest {
        // Paused source toast keeps its paused remaining duration on target.
        val pausedFrom = host(limit = 2)
        val pausedTo = host(limit = 2)
        pausedFrom.show("Paused")
        advance(2_000)
        pausedFrom.setPaused(true)
        advance(20_000)
        pausedFrom.handOffTo(pausedTo)
        assertEquals(listOf("Paused"), pausedTo.titles())
        advance(RaftToastTimeoutMillis - 2_001)
        assertEquals(listOf("Paused"), pausedTo.titles())
        advance(1)
        assertEquals(emptyList(), pausedTo.titles())

        // Zero-duration toast stays indefinite on target; expired toast without runTimers is dropped.
        val rawFrom = RaftToastHostState(limit = 3, timeSource = testTimeSource)
        val rawTo = host(limit = 3)
        rawFrom.show("Expiring", durationMillis = 1_000)
        rawFrom.show("Sticky", durationMillis = 0)
        advance(2_000)
        rawFrom.handOffTo(rawTo)
        assertEquals(listOf("Sticky"), rawTo.titles())
        advance(60_000)
        assertEquals(listOf("Sticky"), rawTo.titles())
    }

    @Test
    fun bottomInsetOverridesUseDeterministicLastRegisteredOrder() = runTest {
        val state = host()
        val density = Density(1f)
        val first = Any()
        val second = Any()
        val third = Any()

        state.putBottomInsetOverride(first) { 10f }
        state.putBottomInsetOverride(second) { 20f }
        state.putBottomInsetOverride(third) { 30f }
        assertEquals(30f, state.bottomInsetOverrideOrNull()?.invoke(density))

        state.removeBottomInsetOverride(third)
        assertEquals(20f, state.bottomInsetOverrideOrNull()?.invoke(density))

        state.removeBottomInsetOverride(second)
        assertEquals(10f, state.bottomInsetOverrideOrNull()?.invoke(density))

        state.removeBottomInsetOverride(first)
        assertNull(state.bottomInsetOverrideOrNull())
    }

    @Test
    fun bottomBandAvoidanceWorksEvenWhenHostBottomIsNotMeasuredYet() = runTest {
        val state = host()
        state.show("Copied")
        state.putAvoidance("tab-bar", RaftToastAvoidance.BottomBand(84f))
        assertEquals(84f, state.resolveBottomClearancePx(hostBottomPx = Float.NaN, safeBottomPx = 24f))
    }

    @Test
    fun heldPageHeightSurvivesHostBottomChangesWhileToastStaysVisible() = runTest {
        val state = host()
        state.show("Copied")
        state.putAvoidance("composer", RaftToastAvoidance.TopEdge(topPx = 680f))
        assertEquals(120f, state.resolveBottomClearancePx(hostBottomPx = 800f, safeBottomPx = 24f))

        // Composer gone and host resizes (rotation / multi-window): held height must not reset.
        state.removeAvoidance("composer")
        assertEquals(120f, state.resolveBottomClearancePx(hostBottomPx = 600f, safeBottomPx = 24f))
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
    fun liveKeyboardReaderUpdatesClearanceBeforeOnPlacedWithoutPollutingHeldHeight() = runTest {
        val state = host()
        state.show("Copied")
        var liveKeyboardPx = 0f
        val reader = { liveKeyboardPx }

        // Composer settled at resting height 120px (topPx = 680 in an 800px host, keyboard = 0).
        state.putAvoidance("composer", RaftToastAvoidance.TopEdge(topPx = 680f, keyboardPx = 0f, liveKeyboardPx = reader))
        assertEquals(120f, state.resolveBottomClearancePx(hostBottomPx = 800f, safeBottomPx = 24f))
        assertEquals(120f, state.currentHeldPageHeightPx)

        // Keyboard opens to 300px on Frame N before the deeply nested composer runs onPlaced:
        // clearance must immediately include resting (120) + liveKeyboard (300) = 420 without
        // leaking the keyboard height into heldPageHeightPx.
        liveKeyboardPx = 300f
        assertEquals(420f, state.resolveBottomClearancePx(hostBottomPx = 800f, safeBottomPx = 300f))
        assertEquals(120f, state.currentHeldPageHeightPx)

        // Composer finishes onPlaced at topPx = 380 with keyboardPx = 300: clearance stays exact.
        state.putAvoidance("composer", RaftToastAvoidance.TopEdge(topPx = 380f, keyboardPx = 300f, liveKeyboardPx = reader))
        assertEquals(420f, state.resolveBottomClearancePx(hostBottomPx = 800f, safeBottomPx = 300f))
        assertEquals(120f, state.currentHeldPageHeightPx)

        // Keyboard closes to 0px on Frame N before the composer's onPlaced runs:
        // clearance must immediately drop back to resting (120px) with no 1-frame mid-air float.
        liveKeyboardPx = 0f
        assertEquals(120f, state.resolveBottomClearancePx(hostBottomPx = 800f, safeBottomPx = 24f))
        assertEquals(120f, state.currentHeldPageHeightPx)
    }
}
