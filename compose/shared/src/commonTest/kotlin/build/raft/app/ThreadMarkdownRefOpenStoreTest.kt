package build.raft.app

import build.raft.app.thread.ThreadMarkdownRefOpenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
