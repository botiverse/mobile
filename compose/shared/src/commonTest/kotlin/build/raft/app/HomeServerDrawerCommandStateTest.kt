package build.raft.app

import build.raft.app.data.HomeServerDrawerCommandIntent
import build.raft.app.data.transitionTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeServerDrawerCommandStateTest {
    @Test
    fun submittedOpenTapIsRejectedUntilFreshRowAndSettleOwnInteractivity() {
        val intent = HomeServerDrawerCommandIntent(11L, true)
        var state = HomeServerDrawerCommandState().submit(intent)

        state = state.rowLaidOut(11L, "row-11")
        assertFalse(state.acceptsConversationClick(11L, intent))

        state = state.settle(11L, desiredOpen = true)
        assertEquals(11L, state.interactiveGeneration)
        assertTrue(state.acceptsConversationClick(11L, intent))
    }

    @Test
    fun authoritativeProjectionRejectsLateLocalGenerationBeforeEffectRebind() {
        val openA = HomeServerDrawerCommandIntent(21L, true)
        val openC = HomeServerDrawerCommandIntent(23L, true)
        var local =
            HomeServerDrawerCommandState()
                .submit(openA)
                .rowLaidOut(21L, "row-a")
                .settle(21L, desiredOpen = true)

        assertFalse(local.acceptsRowObservation(21L, openC))
        assertFalse(local.acceptsConversationClick(21L, openC))
        assertEquals(local, local.rowLaidOut(23L, "premature-c"))

        local = local.submit(openC).rowLaidOut(23L, "row-c").settle(23L, desiredOpen = true)
        assertTrue(local.acceptsConversationClick(23L, openC))
        assertEquals("row-c", local.interactiveRowKey)
    }

    @Test
    fun submittedFalseSnapshotDoesNotCompleteUntilLaterSettledOpen() {
        val intent = HomeServerDrawerCommandIntent(generation = 1L, desiredOpen = true)
        var state = HomeServerDrawerCommandState().submit(intent)

        assertEquals(HomeServerDrawerCommandPhase.SubmittedOpen, state.phase)
        assertEquals(emptyList(), state.terminals)
        assertNull(state.interactiveGeneration)

        state = state.rowLaidOut(1L, "server:conversation")
        assertNull(state.interactiveGeneration)

        state = state.settle(1L, desiredOpen = true)
        assertEquals(HomeServerDrawerCommandPhase.SettledOpen, state.phase)
        assertEquals(HomeServerDrawerCommandTerminalKind.SettledOpen, state.terminals.single().kind)
        assertEquals(1L, state.interactiveGeneration)
        assertEquals("server:conversation", state.interactiveRowKey)

        assertEquals(state, state.settle(1L, desiredOpen = true))
        assertEquals(state, state.rowLaidOut(1L, "duplicate"))
    }

    @Test
    fun coalescedOpenCloseOpenUsesMonotonicOwnerGenerationAndRejectsLateCallbacks() {
        val initial = HomeServerDrawerCommandIntent()
        val openA = initial.transitionTo(true).intent
        val closeB = openA.transitionTo(false)
        val openC = closeB.intent.transitionTo(true)

        assertEquals(1L, openA.generation)
        assertEquals(1L, closeB.supersededGeneration)
        assertEquals(2L, closeB.intent.generation)
        assertEquals(2L, openC.supersededGeneration)
        assertEquals(3L, openC.intent.generation)

        var state = HomeServerDrawerCommandState().submit(openA)
        state = state.submit(openC.intent)
        val beforeLateCallbacks = state
        state = state.settle(openA.generation, desiredOpen = true)
        state = state.rowLaidOut(openA.generation, "late-a")
        state = state.settle(closeB.intent.generation, desiredOpen = false)
        assertEquals(beforeLateCallbacks, state)

        state = state.rowLaidOut(openC.intent.generation, "row-c")
        state = state.settle(openC.intent.generation, desiredOpen = true)
        assertEquals(openC.intent.generation, state.interactiveGeneration)
        assertEquals(1, state.terminals.size)
    }

    @Test
    fun programmaticCloseSettlesWithoutGestureDismiss() {
        val open = HomeServerDrawerCommandIntent(1L, true)
        val close = HomeServerDrawerCommandIntent(2L, false)
        var state = HomeServerDrawerCommandState().submit(open).settle(1L, true)

        state = state.submit(close).settle(2L, false)

        assertEquals(HomeServerDrawerCommandPhase.IdleClosed, state.phase)
        assertEquals(0, state.gestureDismissCount)
        assertEquals(state, state.nativeSettledClosedWithoutCommand())
    }

    @Test
    fun independentNativeCloseAfterSettledOpenDismissesExactlyOnce() {
        val open = HomeServerDrawerCommandIntent(7L, true)
        var state = HomeServerDrawerCommandState().submit(open).settle(7L, true)

        state = state.nativeSettledClosedWithoutCommand()
        assertEquals(1, state.gestureDismissCount)
        assertEquals(HomeServerDrawerCommandPhase.GestureCloseDispatched, state.phase)
        val once = state
        state = state.nativeSettledClosedWithoutCommand()
        assertEquals(once, state)
    }

    @Test
    fun nonObservableSettleStaysSubmittedAndFailClosed() {
        val intent = HomeServerDrawerCommandIntent(9L, true)
        val state = HomeServerDrawerCommandState().submit(intent)

        assertEquals(HomeServerDrawerCommandPhase.SubmittedOpen, state.phase)
        assertFalse(state.terminals.isNotEmpty())
        assertNull(state.interactiveGeneration)
        assertEquals(0, state.gestureDismissCount)
    }
}
