package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** artin 10-29: reference-chip opens are single-flight with a busy chip (virtual time). */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadMarkdownRefOpenStoreTest {
    private fun TestScope.store() = ThreadMarkdownRefOpenStore(this, revealDelayMillis = 120L, settleMillis = 600L)

    @Test
    fun slowOpenShowsTheBusyChipAndDropsEveryOtherTapUntilItSettles() = runTest {
        val store = store()
        val token = assertNotNull(store.begin("message-a:thread"))

        // A lookup still running past the reveal delay: that chip, and only it, is busy.
        advanceTimeBy(119L)
        runCurrent()
        assertNull(store.state.value.busyActionId, "an open under the reveal delay shows no spinner")
        advanceTimeBy(1L)
        runCurrent()
        assertEquals("message-a:thread", store.state.value.busyActionId)

        // Repeat taps on the same chip or any other ref in the list start nothing.
        assertNull(store.begin("message-a:thread"))
        assertNull(store.begin("message-b:channel"))
        assertEquals(token, store.state.value.active)

        // Navigation dispatched (or failed): the chip is restored at once ...
        store.finish(token)
        assertNull(store.state.value.busyActionId)
        assertEquals(token, store.state.value.active)
        // ... and the list still ignores taps while the page transition starts.
        advanceTimeBy(599L)
        runCurrent()
        assertNull(store.begin("message-a:thread"), "a tap inside the settle window must not open twice")
        advanceTimeBy(1L)
        runCurrent()
        assertNull(store.state.value.active)
        assertNotNull(store.begin("message-a:thread"), "the next tap after the settle window opens again")
    }

    @Test
    fun instantOpenNeverShowsTheSpinnerButStillGuardsADoubleTap() = runTest {
        val store = store()
        // Navigation dispatched within the tap (no lookup).
        val token = assertNotNull(store.begin("message-a:channel"))
        store.finish(token)
        assertNull(store.begin("message-a:channel"), "the double tap of an instant open is dropped")

        advanceTimeBy(1_000L)
        runCurrent()
        assertNull(store.state.value.busyActionId, "the reveal timer of a settled open must not fire")
        assertNull(store.state.value.active)
    }

    @Test
    fun staleOrMissingTokensLeaveTheOpenInFlightAlone() = runTest {
        val store = store()
        val token = assertNotNull(store.begin("message-a:thread"))
        advanceTimeBy(120L)
        runCurrent()

        store.finish(null)
        store.finish(token.copy(generation = token.generation + 1L))
        assertEquals(token, store.state.value.active)
        assertEquals("message-a:thread", store.state.value.busyActionId)
        assertNull(store.begin(" "), "a blank action id never opens")
    }
}
