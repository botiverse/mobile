package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RaftForwardPickerGatedLoadTest {
    @Test
    fun typingBurstCoalescesIntoOneLoadForTheLastQuery() = runTest {
        val loads = mutableListOf<Pair<String, Long>>()
        val results = mutableListOf<String>()
        var job: Job? = null
        // Each keystroke restarts the effect, as the LaunchedEffect keyed by query does.
        fun type(query: String) {
            job?.cancel()
            job =
                launch {
                    raftForwardPickerGatedLoad(debounce = true, isCurrent = { true }) {
                        loads += query to currentTime
                        "result:$query"
                    }?.let { results += it }
                }
        }

        type("a")
        advanceTimeBy(RaftForwardPickerSearchDebounceMillis - 1)
        type("ab")
        advanceTimeBy(RaftForwardPickerSearchDebounceMillis - 1)
        type("abc")
        runCurrent()
        assertEquals(emptyList(), loads)

        advanceTimeBy(RaftForwardPickerSearchDebounceMillis)
        runCurrent()
        assertEquals(listOf("abc" to 2 * (RaftForwardPickerSearchDebounceMillis - 1) + RaftForwardPickerSearchDebounceMillis), loads)
        assertEquals(listOf("result:abc"), results)
    }

    @Test
    fun blankDirectoryAndPagingLoadsAreNotDebounced() = runTest {
        var loadedAt = -1L
        val result = raftForwardPickerGatedLoad(debounce = false, isCurrent = { true }) {
            loadedAt = currentTime
            "page"
        }
        assertEquals("page", result)
        assertEquals(0L, loadedAt)
    }

    @Test
    fun requestSupersededDuringDebounceNeverLoads() = runTest {
        var current = true
        var loadCount = 0
        val job = launch {
            assertNull(
                raftForwardPickerGatedLoad(debounce = true, isCurrent = { current }) {
                    loadCount += 1
                    "stale"
                }
            )
        }
        advanceTimeBy(RaftForwardPickerSearchDebounceMillis / 2)
        current = false
        advanceTimeBy(RaftForwardPickerSearchDebounceMillis)
        runCurrent()
        job.join()
        assertEquals(0, loadCount)
    }

    @Test
    fun resultSupersededWhileLoadingIsDropped() = runTest {
        var current = true
        val result = raftForwardPickerGatedLoad(debounce = true, isCurrent = { current }) {
            delay(50)
            current = false
            "stale"
        }
        assertNull(result)
    }
}
