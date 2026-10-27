package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Hands b205bd89: an explicit Activity scroll-to-top owns the top edge after it lands, until the
 * reader scrolls; its own animation is not the reader's scroll; a cancelled or superseded request
 * never pins after its suspension.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityScrollToTopOwnerTest {
    @Test
    fun `a landed scroll-to-top holds the top against a later move off it`() = runTest {
        val owner = ActivityScrollToTopOwner()
        var pins = 0
        launch {
            owner.runScrollToTop(scrollToTop = { delay(300) }, pinTopIfOwned = { pins += 1 })
        }
        runCurrent()
        // Its own animation is in flight: the scroll in progress is the owner's, not the reader's.
        owner.onScrollInProgress(true)
        assertFalse(owner.holdsTop, "nothing is held while the animation runs")
        advanceTimeBy(301)
        owner.onScrollInProgress(false)
        assertEquals(1, pins, "the request revalidates and pins once after its suspension")
        assertTrue(owner.holdsTop)

        // A refresh then moves the head row down; LazyList followed it by key to index 12.
        assertTrue(
            activityScrollToTopShouldPin(
                holdsTop = owner.holdsTop,
                scrollInProgress = false,
                firstVisibleItemIndex = 12,
                firstVisibleItemScrollOffset = 0,
            )
        )
        // Parked at the top: nothing to do.
        assertFalse(activityScrollToTopShouldPin(owner.holdsTop, false, 0, 0))
    }

    @Test
    fun `the reader's scroll after landing retires the owner`() = runTest {
        val owner = ActivityScrollToTopOwner()
        launch { owner.runScrollToTop(scrollToTop = { delay(100) }, pinTopIfOwned = {}) }
        advanceUntilIdle()
        assertTrue(owner.holdsTop)

        owner.onScrollInProgress(true) // a drag
        assertFalse(owner.holdsTop)
        owner.onScrollInProgress(false)
        assertFalse(
            activityScrollToTopShouldPin(owner.holdsTop, false, firstVisibleItemIndex = 9, firstVisibleItemScrollOffset = 40),
            "a reader who scrolled away is never moved back",
        )
    }

    @Test
    fun `another scroll cancelling the animation retires the owner and nothing pins`() = runTest {
        val owner = ActivityScrollToTopOwner()
        var pins = 0
        val job = launch { owner.runScrollToTop(scrollToTop = { delay(300) }, pinTopIfOwned = { pins += 1 }) }
        advanceTimeBy(100)
        job.cancel() // a drag took the scroll mutex
        advanceUntilIdle()
        assertEquals(0, pins)
        assertFalse(owner.holdsTop)
    }

    @Test
    fun `a newer request supersedes an older one whose completion arrives late`() = runTest {
        val owner = ActivityScrollToTopOwner()
        val pins = mutableListOf<String>()
        val firstAnimation = CompletableDeferred<Unit>()
        // A non-cooperative first animation: it completes after the second request began.
        launch { owner.runScrollToTop(scrollToTop = { firstAnimation.await() }, pinTopIfOwned = { pins += "first" }) }
        runCurrent()
        launch { owner.runScrollToTop(scrollToTop = { delay(200) }, pinTopIfOwned = { pins += "second" }) }
        runCurrent()
        firstAnimation.complete(Unit)
        runCurrent()
        assertEquals(emptyList(), pins, "the stale request fails the generation check after its suspension")
        assertFalse(owner.holdsTop, "the second request's animation is still running")
        advanceUntilIdle()
        assertEquals(listOf("second"), pins)
        assertTrue(owner.holdsTop)
    }

    @Test
    fun `a newer tap cancelling the older animation keeps the top owned`() = runTest {
        val owner = ActivityScrollToTopOwner()
        val first = launch { owner.runScrollToTop(scrollToTop = { delay(300) }, pinTopIfOwned = {}) }
        advanceTimeBy(50)
        // Tab re-tap: the effect restarts, cancelling the first request, then the second begins.
        first.cancel()
        runCurrent()
        launch { owner.runScrollToTop(scrollToTop = { delay(300) }, pinTopIfOwned = {}) }
        advanceUntilIdle()
        assertTrue(owner.holdsTop)
    }

    @Test
    fun `a held top pins only its own head row pushed down by key, anything else retires`() {
        // Parked: record the head.
        assertEquals(ActivityHeldTopAction.None, activityHeldTopAction("row0", 0, 0, "row0"))
        // A data change kept row0 by key and moved it to index 20: pin back to the owned edge.
        assertEquals(ActivityHeldTopAction.Pin, activityHeldTopAction("row0", 20, 0, "row0"))
        // The reader (drag, accessibility scroll) moved to another row or a partial offset.
        assertEquals(ActivityHeldTopAction.Retire, activityHeldTopAction("row0", 6, 0, "row6"))
        assertEquals(ActivityHeldTopAction.Retire, activityHeldTopAction("row0", 0, 120, "row0"))
        // No head recorded yet: never pin from an unknown layout.
        assertEquals(ActivityHeldTopAction.Retire, activityHeldTopAction(null, 20, 0, "row0"))
    }

    @Test
    fun `observing a held layout records the head, pins a key move and retires on the reader's move`() = runTest {
        val owner = ActivityScrollToTopOwner()
        launch { owner.runScrollToTop(scrollToTop = { delay(100) }, pinTopIfOwned = {}) }
        advanceUntilIdle()
        assertEquals(ActivityHeldTopAction.None, owner.observeHeldLayout(false, 0, 0, "row0"))
        assertEquals(ActivityHeldTopAction.None, owner.observeHeldLayout(true, 20, 0, "row0"), "never while scrolling")
        assertEquals(ActivityHeldTopAction.Pin, owner.observeHeldLayout(false, 20, 0, "row0"))
        assertTrue(owner.holdsTop)
        assertEquals(ActivityHeldTopAction.None, owner.observeHeldLayout(false, 0, 0, "row1"))
        assertEquals(ActivityHeldTopAction.Retire, owner.observeHeldLayout(false, 3, 0, "row4"))
        assertFalse(owner.holdsTop)
        assertEquals(ActivityHeldTopAction.None, owner.observeHeldLayout(false, 20, 0, "row1"))
    }

    @Test
    fun `diagnostics record start, end, hold correction, cancel and retire with their reasons`() = runTest {
        val lines = mutableListOf<String>()
        val owner = ActivityScrollToTopOwner(log = { lines += it })
        var index = 37
        launch {
            owner.runScrollToTop(
                scrollToTop = { delay(100); index = 0 },
                pinTopIfOwned = {},
                source = ActivityScrollToTopSource.Tab,
                position = { index to 0 },
            )
        }
        advanceUntilIdle()
        owner.observeHeldLayout(false, 0, 0, "row0")
        owner.logHoldCorrection(via = "layout", fromIndex = 20)
        owner.observeHeldLayout(false, 4, 30, "row4")

        val second = launch {
            owner.runScrollToTop(scrollToTop = { delay(300) }, pinTopIfOwned = {}, source = ActivityScrollToTopSource.Title)
        }
        advanceTimeBy(50)
        second.cancel()
        advanceUntilIdle()

        val events = lines.map(::parseScrollToTopLogLine)
        assertEquals(
            listOf(
                "activity_scroll_to_top_start",
                "activity_scroll_to_top_end",
                "activity_scroll_to_top_hold_correct",
                "activity_scroll_to_top_retire",
                "activity_scroll_to_top_start",
                "activity_scroll_to_top_cancel",
                "activity_scroll_to_top_retire",
            ),
            events.map { it.first },
        )
        assertEquals("tab", events[0].second["source"])
        assertEquals("37", events[0].second["fromIndex"])
        assertEquals("0", events[1].second["index"])
        assertEquals("20", events[2].second["fromIndex"])
        assertEquals("layout", events[2].second["via"])
        assertEquals("reader_moved", events[3].second["reason"])
        assertEquals("title", events[4].second["source"])
        assertEquals("other_scroll", events[5].second["reason"])
        assertEquals(events[4].second["generation"], events[5].second["generation"])
    }

    @Test
    fun `a superseded request logs a stale completion and never an end`() = runTest {
        val lines = mutableListOf<String>()
        val owner = ActivityScrollToTopOwner(log = { lines += it })
        val firstAnimation = CompletableDeferred<Unit>()
        launch { owner.runScrollToTop(scrollToTop = { firstAnimation.await() }, pinTopIfOwned = {}) }
        runCurrent()
        launch { owner.runScrollToTop(scrollToTop = { delay(200) }, pinTopIfOwned = {}) }
        runCurrent()
        firstAnimation.complete(Unit)
        advanceUntilIdle()
        val events = lines.map(::parseScrollToTopLogLine)
        val stale = events.single { it.first == "activity_scroll_to_top_stale" }
        assertEquals("1", stale.second["generation"])
        assertEquals("2", stale.second["current"])
        assertEquals(listOf("2"), events.filter { it.first == "activity_scroll_to_top_end" }.map { it.second["generation"] })
    }

    @Test
    fun `an explicit retire without a successor invalidates the pending request`() = runTest {
        val owner = ActivityScrollToTopOwner()
        var pins = 0
        launch { owner.runScrollToTop(scrollToTop = { delay(300) }, pinTopIfOwned = { pins += 1 }) }
        advanceTimeBy(100)
        owner.retire()
        advanceUntilIdle()
        assertEquals(0, pins)
        assertFalse(owner.holdsTop)
    }
}

/** One diagnostics line: its event (first token) and its key=value fields. */
internal fun parseScrollToTopLogLine(line: String): Pair<String, Map<String, String>> {
    val tokens = line.split(' ')
    return tokens.first() to
        tokens.drop(1).mapNotNull { token ->
            val eq = token.indexOf('=')
            if (eq <= 0) null else token.substring(0, eq) to token.substring(eq + 1)
        }.toMap()
}
