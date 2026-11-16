package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Search in this thread (web `useThreadSearch` + `ThreadSearchBar`, slock staging e4c09353f), on virtual time. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedThreadFindStoreTest {
    @Test
    fun countsTheLoadedMatchesAndWrapsBothWays() = runTest {
        val store = store(source = null)
        store.dispatch(ThreadFindIntent.Window(window(origin = msg("o", null, "Launch plan"), replies = listOf(msg("r1", 1, "lunch?"), msg("r2", 2, "the LAUNCH is on"), msg("r3", 3, "launch notes")))))

        store.dispatch(ThreadFindIntent.Query("  launch "))
        assertTrue(store.state.value.open)
        assertEquals(listOf("o", "r2", "r3"), store.state.value.matches, "the first message first, then the replies; any case")
        assertEquals("o", store.state.value.activeMessageId)

        store.dispatch(ThreadFindIntent.Previous)
        assertEquals("r3", store.state.value.activeMessageId, "before the first, the last")
        store.dispatch(ThreadFindIntent.Next)
        assertEquals("o", store.state.value.activeMessageId, "after the last, the first")
        store.dispatch(ThreadFindIntent.Next)
        assertEquals(1, store.state.value.activeIndex)

        // New words go back to the first match; no match: 0/0 and nowhere to go.
        store.dispatch(ThreadFindIntent.Query("nothing here"))
        assertEquals(emptyList(), store.state.value.matches)
        store.dispatch(ThreadFindIntent.Next)
        assertEquals(0, store.state.value.activeIndex)
        assertNull(store.state.value.activeMessageId)
    }

    @Test
    fun matchesTheSendersNameAsTheWebDoes() = runTest {
        val store = store(source = null)
        store.dispatch(ThreadFindIntent.Window(window(replies = listOf(msg("r1", 1, "hello", sender = "Artin"), msg("r2", 2, "bye", sender = "Lagoon")))))

        store.dispatch(ThreadFindIntent.Query("ARTIN"))

        assertEquals(listOf("r1"), store.state.value.matches)
    }

    @Test
    fun closingKeepsTheWordsAndReopeningShowsThem() = runTest {
        val store = store(source = null)
        store.dispatch(ThreadFindIntent.Window(window(replies = listOf(msg("r1", 1, "launch"), msg("r2", 2, "launch")))))
        store.dispatch(ThreadFindIntent.Query("launch"))
        store.dispatch(ThreadFindIntent.Next)

        store.dispatch(ThreadFindIntent.Close)
        assertFalse(store.state.value.open)
        assertNull(store.state.value.activeMessageId, "a closed bar has no open match")

        store.dispatch(ThreadFindIntent.Open)
        assertEquals("launch", store.state.value.query)
        assertEquals("r2", store.state.value.activeMessageId)
    }

    @Test
    fun readsTheWholeThreadOlderThenNewerAfterThePauseAndCountsItsHits() = runTest {
        val source =
            FakeSource { before, after ->
                when {
                    before == 200L -> page((100L until 200L).map { msg("m$it", it, if (it == 150L) "launch" else "x") })
                    before == 100L -> page((60L until 100L).map { msg("m$it", it, if (it == 70L) "launch" else "x") })
                    after == 250L -> page(listOf(msg("m251", 251, "launch")))
                    else -> error("unexpected page before=$before after=$after")
                }
            }
        val store = store(source)
        store.dispatch(ThreadFindIntent.Window(window(replies = (200L..250L).map { msg("m$it", it, if (it == 210L) "launch" else "x") }, hasOlder = true, hasNewer = true)))

        store.dispatch(ThreadFindIntent.Query("launch"))
        assertEquals(listOf("m210"), store.state.value.matches, "until the thread is read, what the page holds")
        advanceTimeBy(SharedThreadFindStore.DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertEquals(emptyList(), source.calls, "no read before the pause")
        advanceUntilIdle()

        val state = store.state.value
        assertFalse(state.loading)
        assertTrue(state.hydrated)
        assertEquals(listOf("m70", "m150", "m210", "m251"), state.matches, "in thread order")
        // A full page (100 rows) reads on; a short one is the start. Then newer pages from the newest held.
        assertEquals(listOf(Call(200, null), Call(100, null), Call(null, 250)), source.calls)
        assertEquals(setOf(DataRequestPriority.Visible), source.priorities.toSet())
        assertEquals(setOf(THREAD_FIND_PAGE_LIMIT), source.limits.toSet())

        // Read once: new words count the same thread without asking again.
        store.dispatch(ThreadFindIntent.Query("x"))
        advanceUntilIdle()
        assertEquals(3, source.calls.size)
        // A reply that came since is counted too.
        store.dispatch(ThreadFindIntent.Query("launch"))
        store.dispatch(ThreadFindIntent.Window(window(replies = (200L..252L).map { msg("m$it", it, if (it == 210L || it == 251L || it == 252L) "launch" else "x") }, hasOlder = true)))
        assertEquals(listOf("m70", "m150", "m210", "m251", "m252"), store.state.value.matches)
    }

    @Test
    fun aThreadThePageHoldsWholeIsNeverRead() = runTest {
        val source = FakeSource { _, _ -> error("nothing to read") }
        val store = store(source)
        store.dispatch(ThreadFindIntent.Window(window(replies = listOf(msg("r1", 1, "launch")))))

        store.dispatch(ThreadFindIntent.Query("launch"))
        advanceUntilIdle()

        assertEquals(emptyList(), source.calls)
        assertEquals(listOf("r1"), store.state.value.matches)
    }

    @Test
    fun wordsTypedInThePauseWaitAgainAndNoWordsAskNothing() = runTest {
        val source = FakeSource { _, _ -> page(emptyList()) }
        val store = store(source)
        store.dispatch(ThreadFindIntent.Window(window(replies = listOf(msg("r1", 10, "launch")), hasOlder = true)))

        store.dispatch(ThreadFindIntent.Query("   "))
        advanceUntilIdle()
        assertEquals(emptyList(), source.calls, "no words: nothing is read")

        store.dispatch(ThreadFindIntent.Query("la"))
        advanceTimeBy(SharedThreadFindStore.DEBOUNCE_MILLIS - 10)
        store.dispatch(ThreadFindIntent.Query("lau"))
        advanceTimeBy(SharedThreadFindStore.DEBOUNCE_MILLIS - 10)
        runCurrent()
        assertEquals(emptyList(), source.calls, "typing on restarts the pause")
        advanceUntilIdle()
        assertEquals(listOf(Call(10, null)), source.calls)

        // Closed before the pause ends: nothing is read.
        val idle = FakeSource { _, _ -> page(emptyList()) }
        val closed = store(idle)
        closed.dispatch(ThreadFindIntent.Window(window(replies = listOf(msg("r1", 10, "launch")), hasOlder = true)))
        closed.dispatch(ThreadFindIntent.Query("launch"))
        closed.dispatch(ThreadFindIntent.Close)
        advanceUntilIdle()
        assertEquals(emptyList(), idle.calls)
    }

    @Test
    fun anotherThreadDropsTheStaleReadAndStartsOver() = runTest {
        val slow = CompletableDeferred<ThreadFindPageLoad>()
        val source = FakeSource { before, _ -> if (before == 10L) slow.await() else page(emptyList()) }
        val store = store(source)
        store.dispatch(ThreadFindIntent.Window(window(replies = listOf(msg("r1", 10, "launch")), hasOlder = true)))
        store.dispatch(ThreadFindIntent.Query("launch"))
        advanceUntilIdle()
        assertTrue(store.state.value.loading, "the count says Loading while the thread is read")

        store.dispatch(ThreadFindIntent.Window(window(thread = "thread-2", replies = listOf(msg("t2", 5, "other")))))
        slow.complete(page(listOf(msg("stale", 9, "launch"))))
        advanceUntilIdle()

        val state = store.state.value
        assertEquals("thread-2", state.threadChannelId)
        assertFalse(state.open, "another thread starts closed")
        assertEquals("", state.query)
        assertFalse(state.loading)
        assertFalse(state.hydrated)
        assertTrue(state.corpus.isEmpty(), "the other thread's answer is dropped")
    }

    @Test
    fun aFailedReadSaysSoWithItsTraceIdKeepsTheLoadedMatchesAndNewWordsTryAgain() = runTest {
        var fail = true
        val source =
            FakeSource { before, _ ->
                if (fail) {
                    ThreadFindPageLoad.Failed(ScreenError("server_unavailable", traceId = TRACE))
                } else {
                    page(listOf(msg("old", before!! - 1, "launch")))
                }
            }
        val store = store(source)
        val events = mutableListOf<ThreadFindEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.toList(events) }
        store.dispatch(ThreadFindIntent.Window(window(replies = listOf(msg("r1", 10, "launch")), hasOlder = true)))

        store.dispatch(ThreadFindIntent.Query("launch"))
        advanceUntilIdle()

        val failed = store.state.value
        assertFalse(failed.loading)
        assertFalse(failed.hydrated)
        assertEquals(TRACE, failed.error?.traceId)
        assertEquals(listOf("r1"), failed.matches, "the count is of what the page holds")
        assertEquals(TRACE, assertIs<ThreadFindEvent.LoadFailed>(events.single()).error.traceId)

        fail = false
        store.dispatch(ThreadFindIntent.Query("launc"))
        advanceUntilIdle()
        assertTrue(store.state.value.hydrated)
        assertNull(store.state.value.error)
        assertEquals(listOf("old", "r1"), store.state.value.matches)
        assertEquals(1, events.size)
    }

    private data class Call(val before: Long?, val after: Long?)

    private class FakeSource(
        private val answer: suspend (before: Long?, after: Long?) -> ThreadFindPageLoad,
    ) : ThreadFindPageSource {
        val calls = mutableListOf<Call>()
        val priorities = mutableListOf<DataRequestPriority>()
        val limits = mutableListOf<Int>()

        override suspend fun page(
            threadChannelId: String,
            beforeSeq: Long?,
            afterSeq: Long?,
            limit: Int,
            priority: DataRequestPriority,
        ): ThreadFindPageLoad {
            calls += Call(beforeSeq, afterSeq)
            priorities += priority
            limits += limit
            return answer(beforeSeq, afterSeq)
        }
    }

    private fun TestScope.store(source: ThreadFindPageSource?): SharedThreadFindStore =
        // Not backgroundScope: advanceUntilIdle runs only foreground work. A scope of no job on the
        // test's scheduler runs with the test's clock and is never waited for when the test ends.
        SharedThreadFindStore(source = source, scope = CoroutineScope(StandardTestDispatcher(testScheduler)))

    private fun msg(id: String, seq: Long?, content: String, sender: String = "User"): ThreadFindMessage =
        ThreadFindMessage(id = id, seq = seq, senderName = sender, content = content)

    private fun page(messages: List<ThreadFindMessage>): ThreadFindPageLoad.Page =
        ThreadFindPageLoad.Page(messages, rawCount = messages.size)

    private fun window(
        thread: String = "thread-1",
        origin: ThreadFindMessage? = null,
        replies: List<ThreadFindMessage>,
        hasOlder: Boolean = false,
        hasNewer: Boolean = false,
    ): ThreadFindWindow = ThreadFindWindow(thread, origin, replies, hasOlder, hasNewer)

    private companion object {
        const val TRACE = "0123456789abcdef0123456789abcdef"
    }
}
