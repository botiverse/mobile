package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import build.raft.app.nav.LocalRaftStackPageLeaving
import build.raft.app.nav.RaftMobilePageStackDisplay
import build.raft.app.nav.RaftPageStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hands 712d8386: popping a thread page while it was still scrolling to a focused message crashed
 * (`LayoutNode should be attached to an owner`, from prefetch cleanup inside `LazyListState.scroll`),
 * because the focused-scroll job, already cancelling, scrolled a list that was leaving.
 *
 * [FocusedScrollList] is wired as ThreadMessagesListContent wires its viewport command effect: a
 * reversed [ThreadListViewport] over a real LazyColumn, the production
 * [scrollThreadItemToFocusedViewport] with [scrollOwnerLive] (job active, list not retired,
 * [LocalRaftStackPageLeaving]) folded into its currency check, the effect keyed on the leaving flag,
 * and [ThreadListViewportRetireOnDispose] after the effect. The pop runs through the real
 * [RaftMobilePageStackDisplay]. Test boundary: this proves no scroll is issued once the page is
 * leaving, disposed, or the job cancelling; it does not reproduce the device prefetch race itself.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadFocusScrollOwnerLifetimeUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Probe {
        var request by mutableStateOf(0)
        var shown by mutableStateOf(true)
        var viewport: ThreadListViewport? = null
        var job: Job? = null
        var finished = false
        var scrollsWhenLeaving: Int? = null
        var scrollsAtDispose: Int? = null
        var scrollsBeforeLastPlacement: Int? = null
        var cancelBeforePlacement = false
    }

    @Composable
    private fun FocusedScrollList(probe: Probe, targetIndex: Int) {
        val raw = rememberLazyListState()
        val viewport = remember(raw) { ThreadListViewport(raw, reverse = true) }
        probe.viewport = viewport
        Box(Modifier.height(400.dp)) {
            LazyColumn(state = raw, reverseLayout = true) {
                items(Rows) { Box(Modifier.fillMaxWidth().height(100.dp)) }
            }
        }
        val leaving = LocalRaftStackPageLeaving.current
        val leavingState = rememberUpdatedState(leaving)
        if (leaving) SideEffect { if (probe.scrollsWhenLeaving == null) probe.scrollsWhenLeaving = viewport.issuedScrolls }
        LaunchedEffect(probe.request, leaving) {
            if (probe.request == 0 || leaving) return@LaunchedEffect
            val context = coroutineContext
            probe.job = context[Job]
            viewport.scrollThreadItemToFocusedViewport(
                itemIndex = targetIndex,
                focusedScrollOffset = { _, _ ->
                    probe.scrollsBeforeLastPlacement = viewport.issuedScrolls
                    // The ticket's interleaving: the job is cancelling when its next scroll starts.
                    if (probe.cancelBeforePlacement) probe.job?.cancel()
                    0
                },
            ) {
                viewport.scrollOwnerLive(context, pageLeaving = leavingState.value)
            }
            probe.finished = true
        }
        ThreadListViewportRetireOnDispose(viewport)
        DisposableEffect(viewport) { onDispose { probe.scrollsAtDispose = viewport.issuedScrolls } }
    }

    /** Starts the focused scroll and steps frames until its first scroll is issued (mid-scroll). */
    private fun ComposeUiTest.startFocusedScroll(probe: Probe) {
        waitForIdle()
        mainClock.autoAdvance = false
        runOnUiThread { probe.request = 1 }
        repeat(MaxFrames) {
            if ((probe.viewport?.issuedScrolls ?: 0) > 0) return
            mainClock.advanceTimeByFrame()
        }
        error("waited for the first focused scroll, issued=${probe.viewport?.issuedScrolls} finished=${probe.finished}")
    }

    @Test
    fun poppingThePageMidFocusScrollIssuesNoFurtherScroll() =
        runRobolectricComposeUiTest {
            val stack = RaftPageStack()
            val probe = Probe()
            setContent {
                RaftMobilePageStackDisplay(stack) { page ->
                    if (page === stack.pages.first()) Box(Modifier) else FocusedScrollList(probe, targetIndex = FarTarget)
                }
            }
            runOnIdle { stack.open("about", JsonObject(emptyMap()), from = stack.pages.first()) }
            startFocusedScroll(probe)

            runOnUiThread { stack.back() }
            mainClock.advanceTimeBy(PopSettleMillis)
            mainClock.autoAdvance = true
            waitForIdle()

            val viewport = assertNotNull(probe.viewport)
            val atLeaving = assertNotNull(probe.scrollsWhenLeaving, "the popped page never saw itself leaving")
            assertTrue(atLeaving > 0, "the pop landed mid focus scroll")
            assertEquals(atLeaving, viewport.issuedScrolls, "a scroll was issued after the page started leaving")
            assertFalse(probe.finished, "the focused scroll ran to completion on a leaving page")
            assertEquals(1, stack.pages.size)
            assertTrue(viewport.isRetired, "the popped page's list was disposed without retiring its viewport")
            assertEquals(atLeaving, probe.scrollsAtDispose)
        }

    @Test
    fun disposingTheListMidFocusScrollIssuesNoFurtherScroll() =
        runRobolectricComposeUiTest {
            val probe = Probe()
            setContent { if (probe.shown) FocusedScrollList(probe, targetIndex = FarTarget) }
            startFocusedScroll(probe)

            runOnUiThread { probe.shown = false }
            mainClock.advanceTimeBy(PopSettleMillis)
            mainClock.autoAdvance = true
            waitForIdle()

            val viewport = assertNotNull(probe.viewport)
            val atDispose = assertNotNull(probe.scrollsAtDispose, "the list never left composition")
            assertTrue(atDispose > 0, "the disposal landed mid focus scroll")
            assertTrue(viewport.isRetired)
            assertEquals(atDispose, viewport.issuedScrolls, "a scroll was issued after the list was disposed")
            assertFalse(probe.finished)
        }

    @Test
    fun aCancellingJobDoesNotScroll() =
        runRobolectricComposeUiTest {
            val probe = Probe().apply { cancelBeforePlacement = true }
            // Visible from the start: the first attempt goes straight to the placement scroll.
            setContent { FocusedScrollList(probe, targetIndex = Rows - 2) }
            waitForIdle()
            val viewport = assertNotNull(probe.viewport)
            val before = viewport.raw.firstVisibleItemIndex to viewport.raw.firstVisibleItemScrollOffset

            runOnIdle { probe.request = 1 }
            waitForIdle()

            assertEquals(0, assertNotNull(probe.scrollsBeforeLastPlacement, "the placement step never ran"))
            assertTrue(assertNotNull(probe.job).isCancelled)
            assertEquals(0, viewport.issuedScrolls, "the cancelling job scrolled")
            assertEquals(before, viewport.raw.firstVisibleItemIndex to viewport.raw.firstVisibleItemScrollOffset)
            assertFalse(probe.finished)
        }

    private companion object {
        const val Rows = 60
        // Forward index near the oldest end of a reversed list parked at the newest: off screen.
        const val FarTarget = 10
        const val MaxFrames = 30
        const val PopSettleMillis = 2_000L
    }
}
