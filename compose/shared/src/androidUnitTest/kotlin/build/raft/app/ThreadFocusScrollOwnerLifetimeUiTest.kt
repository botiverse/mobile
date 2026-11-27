package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import build.raft.app.nav.LocalRaftStackPageLeaving
import build.raft.app.nav.RaftMobilePageStackDisplay
import build.raft.app.nav.RaftPageStack
import build.raft.app.thread.ThreadListScrollOwnerEffect
import build.raft.app.thread.ThreadListViewport
import build.raft.app.thread.scrollThreadItemToFocusedViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
 * [FocusedScrollList] composes the production [ThreadListScrollOwnerEffect] (the wrapper
 * ThreadMessagesListContent's viewport command effect is: keyed on [LocalRaftStackPageLeaving], no run
 * on a leaving page, [ThreadListViewportRetireOnDispose] after the effect) over a reversed
 * [ThreadListViewport] on a real LazyColumn, and runs the production
 * [scrollThreadItemToFocusedViewport] in it with [ThreadListScrollOwnerScope.ownerLive] as its
 * currency check, as the command body does. The pop runs through the real
 * [RaftMobilePageStackDisplay]. Test boundary: this proves no scroll is issued once the page is
 * leaving, the list is disposed, or the job is cancelling; the rest of the command body is
 * source-reviewed, and the device prefetch race itself is not reproduced.
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
        if (leaving) SideEffect { if (probe.scrollsWhenLeaving == null) probe.scrollsWhenLeaving = viewport.issuedScrolls }
        ThreadListScrollOwnerEffect(viewport, Unit, probe.request) {
            if (probe.request == 0) return@ThreadListScrollOwnerEffect
            probe.job = currentCoroutineContext()[Job]
            viewport.scrollThreadItemToFocusedViewport(
                itemIndex = targetIndex,
                focusedScrollOffset = { _, _ ->
                    probe.scrollsBeforeLastPlacement = viewport.issuedScrolls
                    // The ticket's interleaving: the job is cancelling when its next scroll starts.
                    if (probe.cancelBeforePlacement) probe.job?.cancel()
                    0
                },
            ) {
                ownerLive()
            }
            probe.finished = true
        }
        DisposableEffect(viewport) { onDispose { probe.scrollsAtDispose = viewport.issuedScrolls } }
    }

    // With the clock paused, a state write reaches the Recomposer only through the global snapshot
    // apply notification, which the paused clock never posts (MentionPreviewCardUiTest): send it.

    /** Starts the focused scroll and steps frames until its first scroll is issued (mid-scroll). */
    private fun ComposeUiTest.startFocusedScroll(probe: Probe) {
        waitForIdle()
        mainClock.autoAdvance = false
        runOnUiThread { probe.request = 1; Snapshot.sendApplyNotifications() }
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
            assertFalse(probe.finished, "startFocusedScroll must stop mid-scroll before popping")

            runOnUiThread { stack.back(); Snapshot.sendApplyNotifications() }
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

    private companion object {
        const val Rows = 60
        // Forward index near the oldest end of a reversed list parked at the newest: off screen.
        const val FarTarget = 10
        const val MaxFrames = 30
        const val PopSettleMillis = 2_000L
    }
}
