package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * artin 10-29: reference-chip opens are single-flight (virtual time). The busy chip is the
 * reference resolver's fetch phase (artin 11-07), drawn only for this list's own open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadMarkdownRefOpenStoreTest {
    private fun TestScope.store() = ThreadMarkdownRefOpenStore(backgroundScope, settleMillis = 600L)

    @Test
    fun anOpenDropsEveryOtherTapUntilItSettles() = runTest {
        val store = store()
        val token = assertNotNull(store.begin("message-a:thread"))

        // Repeat taps on the same chip or any other ref in the list start nothing.
        assertNull(store.begin("message-a:thread"))
        assertNull(store.begin("message-b:channel"))
        assertEquals(token, store.state.value.active)

        store.finish(token)
        assertEquals(token, store.state.value.active)
        // The list still ignores taps while the page transition starts.
        advanceTimeBy(599L)
        runCurrent()
        assertNull(store.begin("message-a:thread"), "a tap inside the settle window must not open twice")
        advanceTimeBy(1L)
        runCurrent()
        assertNull(store.state.value.active)
        assertNotNull(store.begin("message-a:thread"), "the next tap after the settle window opens again")
    }

    @Test
    fun theChipIsBusyOnlyWhileTheResolverFetchesForThisListsOpen() = runTest {
        val resolver = build.raft.app.data.SharedReferenceResolver()
        val store = ThreadMarkdownRefOpenStore(backgroundScope, fetches = resolver.inFlight, settleMillis = 600L)
        runCurrent()
        assertNull(store.state.value.busyActionId, "no open, no busy chip")

        val token = assertNotNull(store.begin("message-a:thread"))
        assertNull(store.state.value.busyActionId, "a local hit (no fetch) shows no spinner")

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fetch = launch { resolver.fetchPhase("message-a:thread") { gate.await() } }
        runCurrent()
        assertEquals("message-a:thread", store.state.value.busyActionId, "busy for exactly its own fetch")

        // Another page's fetch never marks this list's chip, and outlives this one.
        val otherGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val other = launch { resolver.fetchPhase("message-z:thread") { otherGate.await() } }
        runCurrent()
        gate.complete(Unit)
        fetch.join()
        runCurrent()
        assertNull(store.state.value.busyActionId, "the fetch ended: the chip is restored")

        store.finish(token)
        otherGate.complete(Unit)
        other.join()
    }

    @Test
    fun staleOrMissingTokensLeaveTheOpenInFlightAlone() = runTest {
        val store = store()
        val token = assertNotNull(store.begin("message-a:thread"))

        store.finish(null)
        store.finish(token.copy(generation = token.generation + 1L))
        assertEquals(token, store.state.value.active)
        assertNull(store.begin(" "), "a blank action id never opens")
    }
}
